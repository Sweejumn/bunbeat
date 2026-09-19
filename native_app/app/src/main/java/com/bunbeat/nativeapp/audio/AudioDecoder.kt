package com.bunbeat.nativeapp.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * 把任意受支持音频解码为单声道 22050Hz 归一化 PCM，供本地 BPM 分析。
 *
 * 对应 Dart 的 `AudioDecodeService`（ffmpeg `-vn -ac 1 -ar 22050 -sample_fmt s16`）。
 * 原生侧没有 ffmpeg，改用平台自带的 MediaExtractor + MediaCodec：
 *   - `-vn`（丢弃视频轨）→ 只选第一条音频轨道（mime 以 `audio/` 开头）；
 *   - `-ac 1`（混单声道）→ 各声道取平均（对立体声与 ffmpeg 默认系数一致）；
 *   - `-ar 22050` → 线性插值重采样；
 *   - `-sample_fmt s16` → 16bit 定点，除以 32768 归一化到 -1..1（与 Dart 端完全一致的语义）。
 *
 * ffmpeg-kit 是「单会话/单执行队列」模型，Dart 用全局链把 `decode / probe / artwork`
 * 串行化以避免 SESSION_NOT_FOUND。原生侧没有会话概念，但解码是 CPU/IO 双密集操作，
 * 并发跑多首会互相拖慢并放大内存峰值，所以这里保留同样的「全局串行」语义（[mutex]）。
 */
object AudioDecoder {
    /** 分析用的目标采样率（与 Dart `BpmAnalyzer.kSampleRate` 一致）。 */
    const val SAMPLE_RATE: Int = 22050

    /** 解码诊断日志统一使用该 tag，方便 `adb logcat -s BunbeatDecode` 定位失败原因。 */
    private const val TAG = "BunbeatDecode"

    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /** 连续多少轮 dequeue 都没有任何进展就认定解码器挂起（防御性退出，避免死循环）。 */
    private const val MAX_STALL_ROUNDS = 400

    private val mutex = Mutex()

    /**
     * 把 [path] 解码为 22050Hz 单声道、取值 -1..1 的 PCM。失败返回 null。
     *
     * 与 Dart 的 `decodeToWav` 不同：这里不落 WAV 文件，直接返回样本数组
     * （原生侧 BpmAnalyzer.analyzePcm 直接吃 DoubleArray，省掉一次磁盘往返）。
     */
    suspend fun decodeToPcm(context: Context, path: String): DoubleArray? =
        withContext(Dispatchers.IO) {
            mutex.withLock { decodePcm(context.applicationContext, path) }
        }

    /** 音频时长（秒）；拿不到返回 null。对应 Dart 的 `probeDuration`（ffprobe）。 */
    suspend fun probeDuration(context: Context, path: String): Double? =
        withContext(Dispatchers.IO) {
            mutex.withLock { probeSeconds(context.applicationContext, path) }
        }

    /**
     * 提取内嵌封面（MP3 APIC / M4A covr / FLAC picture）并落地到
     * `filesDir/artwork/<路径 hash>.jpg|png`，返回落地后的绝对路径。
     * 没有内嵌封面或提取失败返回 null（调用方保持 artwork 为空即可）。
     *
     * 对应 Dart 的 `extractEmbeddedArtwork`：那里用 ffmpeg `-c:v copy` 原样写出图片流，
     * 原生侧用 `MediaMetadataRetriever.embeddedPicture` 拿到同样的原始图片字节（不重编码）。
     */
    suspend fun extractArtwork(context: Context, path: String): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock { extractArtworkLocked(context.applicationContext, path) }
        }

    // ---- 解码 ----

    private fun decodePcm(context: Context, path: String): DoubleArray? {
        val src = File(path)
        if (!src.exists()) {
            Log.w(TAG, "decode_fail 文件不存在: $path")
            return null
        }
        var afd: AssetFileDescriptor? = null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            afd = attachExtractor(context, extractor, path)
            val track = selectAudioTrack(extractor)
            if (track < 0) {
                Log.w(TAG, "decode_fail 未找到音频轨道: ${src.name}")
                return null
            }
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)
            if (mime.isNullOrEmpty()) {
                Log.w(TAG, "decode_fail 轨道缺少 mime: ${src.name}")
                return null
            }
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(inFormat, null, null, 0)
            decoder.start()

            val sourceRate = inFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE)
            val resampler = MonoResampler(sourceRate)
            val out = DoubleBuf(1 shl 16)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var channels = inFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
            // 解码器输出格式：绝大多数是 16bit 定点，部分设备的 FLAC/Opus 解码器输出 float。
            var encoding = inFormat.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            var stalled = 0

            while (!outputDone) {
                var progressed = false

                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        progressed = true
                        val input = decoder.getInputBuffer(inIndex)
                        if (input == null) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0L, 0)
                        } else {
                            input.clear()
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        progressed = true
                        val flags = info.flags
                        if (info.size > 0 &&
                            (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            val buffer = decoder.getOutputBuffer(outIndex)
                            if (buffer != null) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                feedPcm(buffer, encoding, channels, resampler, out)
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if ((flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }

                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // 真实采样率/声道数往往在首帧之后才确定，必须用变更后的格式覆盖。
                        progressed = true
                        val fmt = decoder.outputFormat
                        channels = fmt.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        val rate = fmt.intOr(MediaFormat.KEY_SAMPLE_RATE, 0)
                        if (rate > 0) resampler.sourceRate = rate
                        encoding = fmt.intOr(MediaFormat.KEY_PCM_ENCODING, encoding)
                    }
                }

                if (progressed) {
                    stalled = 0
                } else if (++stalled > MAX_STALL_ROUNDS) {
                    Log.w(TAG, "decode_fail 解码器长时间无进展（疑似挂起）: ${src.name}")
                    return null
                }
            }

            resampler.finish(out)
            val pcm = out.toArray()
            if (pcm.isEmpty()) {
                Log.w(TAG, "decode_fail 解码结果为空: ${src.name}")
                return null
            }
            Log.i(TAG, "decode_ok ${src.name} frames=${pcm.size} srcRate=${resampler.sourceRate}")
            return pcm
        } catch (t: Throwable) {
            Log.w(TAG, "decode_fail ${src.name}: ${t.javaClass.simpleName}: ${t.message}", t)
            return null
        } finally {
            releaseCodec(codec)
            try {
                extractor.release()
            } catch (t: Throwable) {
                Log.w(TAG, "extractor.release 失败: ${t.message}")
            }
            closeQuietly(afd)
        }
    }

    /**
     * 绑定数据源。普通文件直接给路径；SAF / MediaStore 的 `content://` 走
     * ContentResolver 打开的文件描述符（注意：fd 必须活到 extractor.release 之后才能关）。
     */
    private fun attachExtractor(
        context: Context,
        extractor: MediaExtractor,
        path: String,
    ): AssetFileDescriptor? {
        if (path.startsWith("content://")) {
            val afd = context.contentResolver.openAssetFileDescriptor(Uri.parse(path), "r")
                ?: throw IllegalStateException("无法打开 content uri: $path")
            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            return afd
        }
        extractor.setDataSource(path)
        return null
    }

    /** 选第一条音频轨道（mime 以 `audio/` 开头，等价 ffmpeg 的 `-vn`：封面所在的视频轨必须丢弃）。 */
    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    /**
     * 把一块解码输出转成单声道浮点样本喂给重采样器。
     * 16bit 定点除以 32768（与 ffmpeg `s16 -> float` 的换算一致），float 输出直接使用。
     */
    private fun feedPcm(
        buffer: ByteBuffer,
        encoding: Int,
        channels: Int,
        resampler: MonoResampler,
        out: DoubleBuf,
    ) {
        val ch = if (channels < 1) 1 else channels
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val src = buffer.asFloatBuffer()
            val frames = src.remaining() / ch
            if (frames <= 0) return
            val mono = FloatArray(frames)
            for (i in 0 until frames) {
                var acc = 0f
                for (c in 0 until ch) acc += src.get()
                mono[i] = acc / ch
            }
            resampler.feed(mono, frames, out)
        } else {
            // 其它编码（24/32bit）在本平台音频解码器上不会出现；统一按 16bit 解读并记录一次，
            // 一旦真的出现异常编码，日志里能立刻看出来（而不是静默产出错误样本）。
            if (encoding != AudioFormat.ENCODING_PCM_16BIT) {
                Log.w(TAG, "未知 PCM 编码 $encoding，按 16bit 处理")
            }
            val src = buffer.asShortBuffer()
            val frames = src.remaining() / ch
            if (frames <= 0) return
            val mono = FloatArray(frames)
            for (i in 0 until frames) {
                var acc = 0
                for (c in 0 until ch) acc += src.get().toInt()
                mono[i] = acc / (ch * 32768f)
            }
            resampler.feed(mono, frames, out)
        }
    }

    // ---- 时长 ----

    private fun probeSeconds(context: Context, path: String): Double? {
        val src = File(path)
        val retriever = MediaMetadataRetriever()
        try {
            setRetrieverSource(context, retriever, path)
            // MMR 给的是毫秒整数（ffprobe 给的是容器里的精确秒数，见报告中的差异说明）。
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.trim()?.toLongOrNull()
            if (ms != null && ms > 0) return ms / 1000.0
        } catch (t: Throwable) {
            Log.w(TAG, "probe_fail(MMR) ${src.name}: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            try {
                retriever.release()
            } catch (t: Throwable) {
                Log.w(TAG, "retriever.release 失败: ${t.message}")
            }
        }
        // 回退：mp4/m4a 等容器在轨道格式里带微秒级时长，比 MMR 更精确。
        val fallback = extractorDurationSeconds(context, path)
        if (fallback == null) Log.w(TAG, "probe_fail 无法取得时长: ${src.name}")
        return fallback
    }

    private fun extractorDurationSeconds(context: Context, path: String): Double? {
        var afd: AssetFileDescriptor? = null
        val extractor = MediaExtractor()
        try {
            afd = attachExtractor(context, extractor, path)
            val track = selectAudioTrack(extractor)
            if (track < 0) return null
            val fmt = extractor.getTrackFormat(track)
            if (!fmt.containsKey(MediaFormat.KEY_DURATION)) return null
            val us = fmt.getLong(MediaFormat.KEY_DURATION)
            return if (us > 0) us / 1_000_000.0 else null
        } catch (t: Throwable) {
            return null
        } finally {
            try {
                extractor.release()
            } catch (t: Throwable) {
                // 忽略：回退路径失败不影响主流程
            }
            closeQuietly(afd)
        }
    }

    // ---- 封面 ----

    private fun extractArtworkLocked(context: Context, path: String): String? {
        val dir = File(context.filesDir, "artwork")
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "artwork_fail 无法创建封面目录: ${dir.absolutePath}")
            return null
        }
        // 落地文件名用源路径的稳定 hash（与歌曲 id 同算法），同一文件重复调用直接复用，
        // 避免每次分析都重新解一次内嵌图片。
        val hash = stableId(path)
        for (ext in listOf("jpg", "png")) {
            val cached = File(dir, "$hash.$ext")
            if (cached.exists() && cached.length() > 0) return cached.absolutePath
        }

        val retriever = MediaMetadataRetriever()
        try {
            setRetrieverSource(context, retriever, path)
            val bytes = retriever.embeddedPicture
            if (bytes == null || bytes.isEmpty()) {
                Log.i(TAG, "artwork_none ${File(path).name}（该文件没有内嵌封面）")
                return null
            }
            // 与 Dart 的 `-c:v copy` 一致：原样写图片字节，按魔数决定扩展名。
            val ext = if (isPng(bytes)) "png" else "jpg"
            val out = File(dir, "$hash.$ext")
            out.writeBytes(bytes)
            if (out.length() <= 0L) {
                Log.w(TAG, "artwork_fail 写出为空: ${out.absolutePath}")
                return null
            }
            Log.i(TAG, "artwork_ok ${File(path).name} -> ${out.name} (${bytes.size}B)")
            return out.absolutePath
        } catch (t: Throwable) {
            Log.w(TAG, "artwork_fail ${File(path).name}: ${t.javaClass.simpleName}: ${t.message}")
            return null
        } finally {
            try {
                retriever.release()
            } catch (t: Throwable) {
                Log.w(TAG, "retriever.release 失败: ${t.message}")
            }
        }
    }

    private fun setRetrieverSource(context: Context, retriever: MediaMetadataRetriever, path: String) {
        if (path.startsWith("content://")) {
            retriever.setDataSource(context, Uri.parse(path))
        } else {
            retriever.setDataSource(path)
        }
    }

    /** PNG 魔数（\x89PNG）→ png，其余（含 JPEG/未知）一律按 jpg 落盘。 */
    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()

    // ---- 工具 ----

    /** 与 Dart `_stableId` 完全一致的 31 进制 hash（8 位十六进制），保证两边文件名可对齐。 */
    private fun stableId(s: String): String {
        var h = 0
        for (ch in s) {
            h = (h * 31 + ch.code) and 0x7fffffff
        }
        return h.toString(16).padStart(8, '0')
    }

    private fun MediaFormat.intOr(key: String, def: Int): Int = try {
        if (containsKey(key)) getInteger(key) else def
    } catch (t: Throwable) {
        def
    }

    private fun releaseCodec(codec: MediaCodec?) {
        if (codec == null) return
        try {
            codec.stop()
        } catch (t: Throwable) {
            // configure 之前就失败时 stop() 会抛 IllegalStateException，忽略即可。
        }
        try {
            codec.release()
        } catch (t: Throwable) {
            Log.w(TAG, "codec.release 失败: ${t.message}")
        }
    }

    private fun closeQuietly(afd: AssetFileDescriptor?) {
        if (afd == null) return
        try {
            afd.close()
        } catch (t: Throwable) {
            Log.w(TAG, "afd.close 失败: ${t.message}")
        }
    }

    /** 可增长的 DoubleArray 缓冲（避免 ArrayList<Double> 的装箱开销与内存峰值）。 */
    private class DoubleBuf(initialCapacity: Int) {
        private var data = DoubleArray(initialCapacity.coerceAtLeast(1024))
        private var size = 0

        fun add(v: Double) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun toArray(): DoubleArray = data.copyOf(size)
    }

    /**
     * 流式线性插值重采样器：把任意采样率的单声道输入转成 22050Hz，
     * 不缓存整段音频（一首 5 分钟 44.1kHz 的歌若先攒齐原速样本要 ~100MB）。
     *
     * 说明：ffmpeg 的 swresample 会在降采样前加抗混叠低通；这里按约定只用线性插值，
     * 44.1k→22.05k 这类整数倍降采样时等价于直接抽取，高频会有少量混叠
     * （对以低频能量包络为主的 BPM 检测影响很小，但结果不会与 Flutter 版逐位相同）。
     */
    private class MonoResampler(initialRate: Int) {
        var sourceRate: Int = if (initialRate > 0) initialRate else SAMPLE_RATE

        private var buf = FloatArray(1 shl 14)
        private var len = 0

        /** buf[0] 在输入流中的全局下标（丢弃前缀后用于定位插值点）。 */
        private var base = 0L

        /** 下一个待输出采样在输入流中的位置（带小数）。 */
        private var pos = 0.0

        fun feed(src: FloatArray, count: Int, out: DoubleBuf) {
            if (count <= 0) return
            ensure(len + count)
            System.arraycopy(src, 0, buf, len, count)
            len += count
            drain(out, flush = false)
        }

        /** 输入结束后补齐末尾不足一个采样间隔的部分（用最后一个样本外推）。 */
        fun finish(out: DoubleBuf) = drain(out, flush = true)

        private fun step(): Double = sourceRate / SAMPLE_RATE.toDouble()

        private fun drain(out: DoubleBuf, flush: Boolean) {
            val s = step()
            if (s <= 0.0) return
            while (true) {
                val i = floor(pos).toLong()
                val local = (i - base).toInt()
                // 线性插值需要 floor(pos) 与 floor(pos)+1 两个样本都存在。
                if (local < 0 || local + 1 >= len) break
                val a = buf[local]
                val frac = (pos - i).toFloat()
                out.add((a + (buf[local + 1] - a) * frac).toDouble())
                pos += s
            }
            if (flush && len > 0) {
                val last = buf[len - 1].toDouble()
                val end = base + len
                while (pos < end) {
                    out.add(last)
                    pos += s
                }
            }
            // 压缩缓冲区：floor(pos) 之前的样本不可能再被插值用到。
            val keepFrom = (floor(pos).toLong() - base).coerceIn(0L, len.toLong()).toInt()
            if (keepFrom > 0) {
                System.arraycopy(buf, keepFrom, buf, 0, len - keepFrom)
                len -= keepFrom
                base += keepFrom
            }
        }

        private fun ensure(need: Int) {
            if (need <= buf.size) return
            var cap = buf.size
            while (cap < need) cap *= 2
            buf = buf.copyOf(cap)
        }
    }
}
