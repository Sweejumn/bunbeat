package com.bunbeat.nativeapp.audio

import android.content.Context
import com.bunbeat.bpm.BpmAnalyzer
import com.bunbeat.bpm.BpmResult
import com.bunbeat.nativeapp.model.BpmStatus
import com.bunbeat.nativeapp.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 单曲分析管线（对应 Dart `LibraryService._prepareNcm` + `_loadSong` + `_analyze`
 * 与 `AudioDecodeService.decodeAndAnalyze` 的组合）。
 *
 * 顺序与 Dart 完全一致：
 *  1. NCM 源先解密为可播放文件，成功则把 [Song.filePath] 指向解密产物
 *     （[Song.id] / filename / title 仍基于原始 .ncm，保证缓存键稳定）；
 *  2. 查缓存：命中且不是「旧算法结果」→ 直接返回填好的 Song（秒开）；
 *  3. 未命中：解码为 22050Hz 单声道 PCM → BPM 引擎（CPU 密集，走 Dispatchers.Default）
 *     → 抽内嵌封面 → 写缓存 → 返回填好的 Song；
 *  4. 任何失败都要落 [BpmStatus.FAILED] 与与 Dart 逐字相同的中文错误文案。
 */
object Analyzer {

    /** NCM 解密失败（逐字对齐 Dart `_prepareNcm`）。 */
    private const val ERR_NCM = "NCM 解密失败，无法读取"

    /** 解码失败（逐字对齐 Dart `AudioDecodeService.decodeAndAnalyze` 的返回）。 */
    private const val ERR_DECODE = "无法解码音频文件"

    /** 引擎无结果时的兜底文案（逐字对齐 Dart `_analyze` 的 `res.error ?? ...`）。 */
    private const val ERR_NO_BPM = "无法可靠检测 BPM"

    /**
     * 解码出的 PCM 过短（逐字对齐 Dart `BpmAnalyzer.analyzeWavFile` 里
     * `decodeWavPcm16` 失败 / 采样点不足时的返回）。
     */
    private const val ERR_TOO_SHORT = "音频过短或格式无法解析，无法可靠检测 BPM"

    /**
     * 分析一首歌并返回分析后的 [Song]（[Song] 不可变，结果通过 copy 返回，
     * 调用方需用 `LibraryStore.updateSong(...)` 回写）。
     *
     * @param force true 表示忽略缓存强制重测（对应 UI 的「重新测量」）。
     */
    suspend fun analyze(
        context: Context,
        cache: AnalysisCache,
        song: Song,
        force: Boolean = false,
    ): Song = withContext(Dispatchers.IO) {
        // 1) NCM 源先解密（Dart 在 loadFolder 里对每首歌先做 _prepareNcm）。
        var s = song
        if (NcmDecoder.isNcm(s.filePath)) {
            val decrypted = try {
                NcmDecoder.decrypt(context, s.filePath)
            } catch (e: Throwable) {
                null
            }
            if (decrypted.isNullOrEmpty()) {
                // 解密失败标记为 failed，不阻断整批导入；不去碰缓存。
                return@withContext s.copy(
                    bpmStatus = BpmStatus.FAILED,
                    bpmError = ERR_NCM,
                )
            }
            // 指向解密后的可播放文件；标题仍用原始 .ncm 名。
            s = s.copy(filePath = decrypted)
        }

        // 2) 缓存命中（非 force）→ 秒开。
        //    手动 BPM 永远优先（Dart 的 `stale && !manual` 判断）：即使结果由旧算法
        //    产生也不自动覆盖用户手填的值。
        if (!force) {
            val cached = try {
                cache.load(s)
            } catch (e: Throwable) {
                null
            }
            if (cached != null && (!cached.stale || cached.manual)) {
                return@withContext applyCached(s, cached)
            }
        }

        // 3) 真正分析（含异常兜底文案）
        analyzeFresh(context, cache, s)
    }

    // ------------------------------------------------------------------
    // 缓存命中
    // ------------------------------------------------------------------

    /** 把缓存结果映射回 [Song]（对应 Dart `_loadSong` 的缓存分支）。 */
    private fun applyCached(song: Song, cached: AnalysisCache.Cached): Song {
        // 仅当缓存里有持久化封面才覆盖；否则保留（例如 NCM 解密出的）既有封面。
        val artwork = cached.artworkFile
        val artworkPath = if (!artwork.isNullOrEmpty() && File(artwork).exists()) {
            artwork
        } else {
            song.artworkPath
        }
        return song.copy(
            bpmStatus = BpmStatus.DONE,
            bpmError = null,
            originalBpm = cached.bpm,
            bpmConfidence = cached.confidence,
            duration = cached.duration,
            beatOffset = cached.beatOffset,
            beatTimes = cached.beatTimes,
            beatMaps = cached.beatMaps,
            phaseReliability = cached.phaseReliability,
            algorithm = cached.algorithm,
            byAlgorithm = cached.byAlgorithm,
            artworkPath = artworkPath,
        )
    }

    // ------------------------------------------------------------------
    // 实际分析与写缓存
    // ------------------------------------------------------------------

    /** 分析主体；异常统一转成 Dart 的 `分析失败: $e` 文案。 */
    private suspend fun analyzeFresh(
        context: Context,
        cache: AnalysisCache,
        song: Song,
    ): Song = try {
        runAnalysis(context, cache, song)
    } catch (e: Throwable) {
        // Dart：song.bpmError = '分析失败: $e'（$e 是 Dart 异常的 toString，
        // Kotlin 侧是 Java 异常的 toString，前缀文案一致）。
        song.copy(bpmStatus = BpmStatus.FAILED, bpmError = "分析失败: $e")
    }

    /** 解码 → 分析 → 抽封面 → 写缓存；顺序与 Dart `_analyze` 一致。 */
    private suspend fun runAnalysis(
        context: Context,
        cache: AnalysisCache,
        song: Song,
    ): Song {
        val res: BpmResult = when (val samples = AudioDecoder.decodeToPcm(context, song.filePath)) {
            null -> BpmResult(bpm = null, confidence = 0.0, error = ERR_DECODE)
            // 等价 Dart analyzeWavFile 的前置校验：PCM 太短直接给「过短」文案，
            // 而不是让引擎给出各自的短音频错误。
            else -> if (samples.size < BpmAnalyzer.kSampleRate * 2) {
                BpmResult(bpm = null, confidence = 0.0, error = ERR_TOO_SHORT)
            } else {
                // DSP 是 CPU 密集的：Dart 用 compute() 丢到后台 isolate，这里走 Default。
                withContext(Dispatchers.Default) {
                    BpmAnalyzer.analyzePcm(samples, BpmAnalyzer.kSampleRate)
                }
            }
        }

        val bpm = res.bpm
        var out: Song = if (res.error != null || bpm == null) {
            song.copy(bpmStatus = BpmStatus.FAILED, bpmError = res.error ?: ERR_NO_BPM)
        } else {
            song.copy(
                bpmStatus = BpmStatus.DONE,
                bpmError = null,
                originalBpm = bpm,
                bpmConfidence = res.confidence,
                duration = res.duration,
                beatOffset = res.beatOffset,
                beatTimes = res.beatTimes,
                beatMaps = res.beatMaps,
                phaseReliability = res.phaseReliability,
                algorithm = BpmAnalyzer.kActiveAlgorithm,
                byAlgorithm = song.byAlgorithm +
                    (BpmAnalyzer.kActiveAlgorithm.toString() to bpm),
            )
        }

        // 封面提取是独立一步：失败只影响封面，不影响 BPM 结果（对齐 Dart）。
        var artworkFile: String? = null
        try {
            val art = AudioDecoder.extractArtwork(context, song.filePath)
            // 抽出的文件可能极小/损坏，Dart 的校验就是「非空即采用」。
            if (art != null && File(art).length() > 0) {
                artworkFile = art
                out = out.copy(artworkPath = art)
            }
        } catch (e: Throwable) {
            // 无封面/提取失败：保持 artworkPath 为空即可
        }

        // 只写成功结果（失败不写缓存，对齐 Dart：失败的歌曲每次都会重试）。
        if (out.bpmStatus == BpmStatus.DONE) {
            try {
                cache.save(out, res, false, artworkFile)
            } catch (e: Throwable) {
                // 写缓存失败不影响本次分析结果
            }
        }

        return out
    }
}
