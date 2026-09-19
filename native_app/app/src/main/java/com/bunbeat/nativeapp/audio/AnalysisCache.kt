package com.bunbeat.nativeapp.audio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.bunbeat.bpm.BpmAnalyzer
import com.bunbeat.bpm.BpmResult
import com.bunbeat.nativeapp.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 分析结果持久化缓存：避免每次打开应用都重新解码 + FFT 解析。
 *
 * 对应 Dart 的 `AnalysisCache`（`android_app/lib/services/analysis_cache.dart`），
 * 设计要点逐条照搬：
 *  - 缓存目录放在应用私有目录（`filesDir/analysis`，对应 Dart 的 application support
 *    目录），随应用常驻；不放 cacheDir —— 系统清理临时目录会导致封面丢失、重新解析。
 *  - 每首歌一个 `<id>.json`（id 由 LibraryStore 用与 Dart 一致的 31 进制 hash 生成）。
 *  - 记录源文件 size / mtime 判断文件是否被替换或更新，变了即视为失效重新分析。
 *  - schemaVersion 5：记录 `algorithm`（产生结果的算法）与 `byAlgorithm`（各算法 BPM 历史）。
 *    算法升级时旧缓存【不失效】，照常返回并令 [Cached.stale] 为 true，由调用方秒开 + 后台重测。
 *    手动 BPM 永远优先，不会被重测覆盖。
 *  - Dart 侧靠单 isolate 顺序执行保证线程安全；Kotlin 侧用 [mutex] 串行化所有读写。
 */
class AnalysisCache(context: Context) {

    /**
     * 缓存格式版本。v4：BPM 保留真实精度；v5：新增 algorithm / byAlgorithm。
     * v5 及更高均可读取，< 4 的旧缓存视为无法可靠解析 → 失效重测。
     */
    private val schemaVersion = 5

    /** 从缓存恢复的一首歌的分析结果（对应 Dart `CachedAnalysis`）。 */
    data class Cached(
        val bpm: Double?,
        val confidence: Double,
        val duration: Double?,
        val beatOffset: Double?,
        val beatTimes: List<Double>?,
        /** 各节拍模式的时间轴（键为 BeatMode.key）。 */
        val beatMaps: Map<String, List<Double>>?,
        /** 0..1 相位可靠性。 */
        val phaseReliability: Double?,
        /** 用户手动指定的 BPM（true 表示不要被自动分析覆盖）。 */
        val manual: Boolean,
        /**
         * 封面文件绝对路径（无封面为 null）。
         *
         * 注意：Dart 里这里存的是「缓存目录内的文件名」，调用方还要再拼一次目录；
         * 原生侧 [load] 已经把它解析成可直接交给 BitmapFactory / Compose 的绝对路径，
         * 不存在或空文件时返回 null（等价 Dart 里 `artworkPath()` 的最终结果）。
         */
        val artworkFile: String?,
        /** 产生当前结果的算法编号；旧格式缓存（未记录）为 null。 */
        val algorithm: Int?,
        /** 结果由旧算法产生（当前活动算法 ≠ algorithm）。 */
        val stale: Boolean,
        /** 各算法最近一次测得的 BPM 历史（键 = 算法编号字符串）。 */
        val byAlgorithm: Map<String, Double>,
    )

    private val appContext: Context = context.applicationContext
    private val mutex = Mutex()

    /** 读取一条缓存。源文件已变更（size/mtime 不同）时返回 null 视为失效。 */
    suspend fun load(song: Song): Cached? = withContext(Dispatchers.IO) {
        mutex.withLock { readLocked(song) }
    }

    /** 写入（或覆盖）一条缓存，并记录源文件大小与修改时间用于失效判断。 */
    suspend fun save(song: Song, res: BpmResult, manual: Boolean, artworkFile: String?) =
        withContext(Dispatchers.IO) {
            mutex.withLock { saveLocked(song, res, manual, artworkFile) }
        }

    /** 只更新已缓存记录的封面字段（分析结果不动）。对应 Dart 里把封面随 write 一起落盘的场景。 */
    suspend fun attachArtwork(song: Song, artworkFile: String) = withContext(Dispatchers.IO) {
        mutex.withLock { attachArtworkLocked(song, artworkFile) }
    }

    /**
     * 记录「某算法最近一次测得的 BPM」，并把该值作为当前结果写回（confidence=1.0、manual=true）。
     *
     * 语义对齐 Dart 的 `LibraryService.setManualBpm`：用户手动改 BPM 时保留已有缓存里的
     * beatMaps / phaseReliability / 封面，不被自动分析覆盖，同时把本次值记进 byAlgorithm 历史。
     */
    suspend fun setByAlgorithm(song: Song, algorithm: Int, bpm: Double) = withContext(Dispatchers.IO) {
        mutex.withLock { setByAlgorithmLocked(song, algorithm, bpm) }
    }

    // ---- 读 ----

    private fun readLocked(song: Song): Cached? {
        try {
            val file = cacheFile(song.id)
            if (!file.exists()) return null
            val map = JSONObject(file.readText(Charsets.UTF_8))

            // 源文件变更检测：size / mtime 任一不同即失效（被替换/更新的歌曲会正确刷新）。
            val facts = fileFacts(song.filePath) ?: return null
            if (hasValue(map, "fileSize") && map.optLong("fileSize", -1L) != facts.first) return null
            if (hasValue(map, "fileMtime") && map.optLong("fileMtime", -1L) != facts.second) return null

            // 缓存格式过旧（v4 之前无法可靠解析）→ 失效并重新分析。
            if (hasValue(map, "schemaVersion") && map.optInt("schemaVersion", schemaVersion) < 4) {
                return null
            }

            val algorithm = if (hasValue(map, "algorithm")) map.optInt("algorithm") else null
            val byAlgorithm = readByAlgorithm(map)
            val beatTimes = map.optJSONArray("beatTimes")?.let { arr ->
                (0 until arr.length()).map { arr.optDouble(it, 0.0) }
            }
            val beatMaps = readBeatMaps(map)
            val bpm = optDoubleOrNull(map, "bpm")
            val manual = map.optBoolean("manual", false)

            // 只写了封面、还没写分析结果的占位记录不算命中（否则会把 originalBpm 置空）。
            if (bpm == null && !manual && beatTimes == null) return null

            return Cached(
                bpm = bpm,
                confidence = optDoubleOrNull(map, "confidence") ?: 0.0,
                duration = optDoubleOrNull(map, "duration"),
                beatOffset = optDoubleOrNull(map, "beatOffset"),
                beatTimes = beatTimes,
                beatMaps = beatMaps,
                phaseReliability = optDoubleOrNull(map, "phaseReliability"),
                manual = manual,
                artworkFile = resolveArtwork(optStringOrNull(map, "artworkFile")),
                algorithm = algorithm,
                stale = algorithm != BpmAnalyzer.kActiveAlgorithm,
                byAlgorithm = byAlgorithm,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "cache_read_fail ${song.filename}: ${t.javaClass.simpleName}: ${t.message}")
            return null
        }
    }

    // ---- 写 ----

    private fun saveLocked(song: Song, res: BpmResult, manual: Boolean, artworkFile: String?) {
        try {
            // 与 Dart 一致：分析失败的（bpm 为 null）结果不写缓存，避免污染下次启动的秒开。
            val bpm = res.bpm
            if (bpm == null) {
                Log.i(TAG, "cache_skip ${song.filename}（bpm 为空，不写缓存）")
                return
            }
            val file = cacheFile(song.id)
            val old = readJson(file)
            // 合并已有 byAlgorithm 历史，避免覆盖其它算法测得的 BPM。
            val byAlgorithm = readByAlgorithm(old)
            val algorithm = BpmAnalyzer.kActiveAlgorithm
            byAlgorithm[algorithm.toString()] = bpm

            val facts = fileFacts(song.filePath) ?: return

            val obj = JSONObject()
            putNullable(obj, "schemaVersion", schemaVersion)
            putNullable(obj, "bpm", bpm)
            putNullable(obj, "confidence", res.confidence)
            putNullable(obj, "duration", res.duration)
            putNullable(obj, "beatOffset", res.beatOffset)
            putNullable(obj, "beatTimes", toJsonArray(res.beatTimes))
            putNullable(obj, "beatMaps", toJsonBeatMaps(res.beatMaps))
            putNullable(obj, "phaseReliability", res.phaseReliability)
            putNullable(obj, "manual", manual)
            // 本次没提取到新封面时沿用旧记录里的封面，避免重测后封面丢失
            // （Dart 的 write 会用 null 覆盖，此处是有意的健壮性改进）。
            putNullable(obj, "artworkFile", artworkFile ?: optStringOrNull(old, "artworkFile"))
            putNullable(obj, "algorithm", algorithm)
            putNullable(obj, "byAlgorithm", if (byAlgorithm.isEmpty()) null else JSONObject(byAlgorithm.toMap()))
            putNullable(obj, "fileSize", facts.first)
            putNullable(obj, "fileMtime", facts.second)

            writeJson(file, obj)
            Log.i(TAG, "cache_write ${song.filename} bpm=$bpm manual=$manual stale=false")
        } catch (t: Throwable) {
            // Dart 的 write 同样吞掉所有异常：缓存写失败不应影响分析主流程。
            Log.w(TAG, "cache_write_fail ${song.filename}: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun attachArtworkLocked(song: Song, artworkFile: String) {
        try {
            val file = cacheFile(song.id)
            val old = readJson(file)
            val obj: JSONObject
            if (old != null) {
                obj = old
                putNullable(obj, "artworkFile", artworkFile)
            } else {
                val facts = fileFacts(song.filePath) ?: return
                obj = JSONObject()
                putNullable(obj, "schemaVersion", schemaVersion)
                putNullable(obj, "manual", false)
                putNullable(obj, "artworkFile", artworkFile)
                putNullable(obj, "fileSize", facts.first)
                putNullable(obj, "fileMtime", facts.second)
            }
            writeJson(file, obj)
        } catch (t: Throwable) {
            Log.w(TAG, "cache_artwork_fail ${song.filename}: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun setByAlgorithmLocked(song: Song, algorithm: Int, bpm: Double) {
        try {
            val file = cacheFile(song.id)
            val old = readJson(file)
            val byAlgorithm = readByAlgorithm(old)
            byAlgorithm[algorithm.toString()] = bpm
            val facts = fileFacts(song.filePath) ?: return

            val obj = JSONObject()
            putNullable(obj, "schemaVersion", schemaVersion)
            putNullable(obj, "bpm", bpm)
            putNullable(obj, "confidence", 1.0)
            putNullable(obj, "duration", song.duration)
            putNullable(obj, "beatOffset", song.beatOffset)
            putNullable(obj, "beatTimes", toJsonArray(song.beatTimes))
            // build 拍点 / 相位可靠性沿用缓存里已算好的，没有则退回歌曲自身字段。
            putNullable(
                obj,
                "beatMaps",
                old?.optJSONObject("beatMaps") ?: toJsonBeatMaps(song.beatMaps),
            )
            putNullable(
                obj,
                "phaseReliability",
                optDoubleOrNull(old, "phaseReliability") ?: song.phaseReliability,
            )
            putNullable(obj, "manual", true)
            putNullable(obj, "artworkFile", optStringOrNull(old, "artworkFile"))
            putNullable(obj, "algorithm", algorithm)
            putNullable(obj, "byAlgorithm", if (byAlgorithm.isEmpty()) null else JSONObject(byAlgorithm.toMap()))
            putNullable(obj, "fileSize", facts.first)
            putNullable(obj, "fileMtime", facts.second)

            writeJson(file, obj)
            Log.i(TAG, "cache_manual ${song.filename} bpm=$bpm algorithm=$algorithm")
        } catch (t: Throwable) {
            Log.w(TAG, "cache_manual_fail ${song.filename}: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ---- 目录与文件 ----

    private fun analysisDir(): File = File(appContext.filesDir, "analysis")

    private fun artworkDir(): File = File(appContext.filesDir, "artwork")

    private fun cacheFile(id: String): File {
        val dir = analysisDir()
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$id.json")
    }

    private fun readJson(file: File): JSONObject? = try {
        if (file.exists()) JSONObject(file.readText(Charsets.UTF_8)) else null
    } catch (t: Throwable) {
        null
    }

    private fun writeJson(file: File, obj: JSONObject) {
        file.writeText(obj.toString(), Charsets.UTF_8)
    }

    /**
     * 源文件的 (size, mtimeMillis)。普通路径走 java.io.File；
     * SAF / MediaStore 的 `content://` 走 ContentResolver + DocumentFile（防御性支持）。
     * 取不到信息返回 null → 调用方按「无法校验」处理（读=失效，写=放弃）。
     */
    private fun fileFacts(path: String): Pair<Long, Long>? = try {
        if (path.startsWith("content://")) {
            val uri = Uri.parse(path)
            var size = -1L
            appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) {
                    size = cursor.getLong(idx)
                }
            }
            val mtime = DocumentFile.fromSingleUri(appContext, uri)?.lastModified() ?: 0L
            if (size < 0) null else size to mtime
        } else {
            val f = File(path)
            if (!f.exists()) null else f.length() to f.lastModified()
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * 解析封面路径：原生侧 [AudioDecoder.extractArtwork] 与 NCM 解密产物存的是绝对路径，
     * 优先直接用；同时兼容 Dart 语义（存的是缓存目录内的相对文件名）。
     */
    private fun resolveArtwork(stored: String?): String? {
        if (stored.isNullOrBlank()) return null
        val direct = File(stored)
        if (direct.isAbsolute && direct.exists() && direct.length() > 0) return direct.absolutePath
        for (base in listOf(artworkDir(), analysisDir())) {
            val candidate = File(base, stored)
            if (candidate.exists() && candidate.length() > 0) return candidate.absolutePath
        }
        return null
    }

    // ---- JSON 工具 ----

    private fun hasValue(obj: JSONObject?, key: String): Boolean =
        obj != null && obj.has(key) && !obj.isNull(key)

    private fun optDoubleOrNull(obj: JSONObject?, key: String): Double? {
        if (obj == null || !hasValue(obj, key)) return null
        val raw = obj.opt(key)
        return (raw as? Number)?.toDouble()
    }

    private fun optStringOrNull(obj: JSONObject?, key: String): String? {
        if (obj == null || !hasValue(obj, key)) return null
        return obj.optString(key).takeIf { it.isNotEmpty() }
    }

    private fun readByAlgorithm(obj: JSONObject?): MutableMap<String, Double> {
        val out = mutableMapOf<String, Double>()
        val raw = obj?.optJSONObject("byAlgorithm") ?: return out
        for (key in raw.keys()) {
            val v = raw.opt(key)
            if (v is Number) out[key] = v.toDouble()
        }
        return out
    }

    private fun readBeatMaps(obj: JSONObject): Map<String, List<Double>>? {
        val raw = obj.optJSONObject("beatMaps") ?: return null
        val out = mutableMapOf<String, List<Double>>()
        for (key in raw.keys()) {
            val arr = raw.optJSONArray(key) ?: continue
            out[key] = (0 until arr.length()).map { arr.optDouble(it, 0.0) }
        }
        return out.ifEmpty { null }
    }

    private fun toJsonArray(values: List<Double>?): JSONArray? {
        if (values == null) return null
        val arr = JSONArray()
        for (v in values) arr.put(v)
        return arr
    }

    private fun toJsonBeatMaps(maps: Map<String, List<Double>>?): JSONObject? {
        if (maps == null || maps.isEmpty()) return null
        val obj = JSONObject()
        for ((key, values) in maps) obj.put(key, toJsonArray(values) ?: JSONArray())
        return obj
    }

    /** null 写成 JSON 的 `null`（org.json 默认 put(null) 会删除键，与 Dart 输出不一致）。 */
    private fun putNullable(obj: JSONObject, key: String, value: Any?) {
        obj.put(key, value ?: JSONObject.NULL)
    }

    private companion object {
        const val TAG = "BunbeatDecode"
    }
}
