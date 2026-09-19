package com.bunbeat.nativeapp.player

// 节拍器：对应 Dart `android_app/lib/services/metronome.dart`。
//
// Dart 版把每种音色渲染成 44 字节头 + PCM16 的临时 WAV 文件，再用 just_audio 播放。
// 原生版【不落盘、不用 SoundPool】：直接用 AudioTrack 合成 PCM16——
//   * 参数（频率/时长/衰减/振幅/方波）与 Dart `kMetronomeSounds` 逐字一致；
//   * MODE_STATIC：PCM 一次性写入，之后每次「嗒」只需 stop() → reloadStaticData() → play()，
//     没有按次解码/IO 延迟（这正是 Dart 版当年「时有时无」的根因，它靠预建播放器池规避）；
//   * 每种音色维护一个小音轨池，每次选「最久未用」的一条，避免快速连击互相打断丢拍。

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bunbeat.nativeapp.core.Prefs
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** 节拍器音色（对应 Dart `MetronomeSound`，字段含义一一对应）。 */
data class MetronomeSound(
    /** 显示名。 */
    val name: String,
    /** 基频 Hz。 */
    val freq: Double,
    /** 时长（秒）。 */
    val dur: Double,
    /** 指数衰减系数（越大衰减越快、越短促）。 */
    val decay: Double,
    /** 振幅 0..1。 */
    val amp: Double,
    /** true = 方波，false = 正弦。 */
    val square: Boolean = false,
)

/** 内置音色表，数值与 Dart `kMetronomeSounds` 完全一致。 */
val kMetronomeSounds: List<MetronomeSound> = listOf(
    MetronomeSound(name = "木鱼", freq = 2000.0, dur = 0.06, decay = 18.0, amp = 0.8),
    MetronomeSound(name = "滴", freq = 3000.0, dur = 0.05, decay = 15.0, amp = 0.7),
    MetronomeSound(name = "嗒", freq = 600.0, dur = 0.09, decay = 12.0, amp = 0.9),
    MetronomeSound(name = "哔", freq = 1000.0, dur = 0.07, decay = 14.0, amp = 0.75, square = true),
)

/**
 * 节拍器（Compose 友好的音频调度器）。
 *
 * 时间基准：独立线程按 [kTickMs] 轮询，维护一个「媒体时间估算值」est：
 *  - 播放中 est 按墙钟推进（乘上由播放位置差分校准出的速率 rate）；
 *  - 每 [kSyncIntervalNs] 用调用方给的 `positionSec()` 校一次：偏差超过 [kHardResyncSec]
 *    直接硬重同步（seek / 卡顿之后），否则只用差分速率微调 —— 不做比例回拉，因为
 *    `positionSec()` 一般来自 50ms 的位置轮询、带固定上报滞后，比例回拉会把点击整体拖后，
 *    而差分会把这段固定滞后自然消掉；
 *  - 暂停时 est 不推进，也就不再出声（对应 Web 版；Dart 版 Flutter 侧 tick 仍在推进）。
 */
class Metronome(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs = Prefs(appContext)

    /** 是否开启（契约 `val` 的读语义不变，内部可写）。 */
    var enabled: Boolean by mutableStateOf(false)
        private set

    /** 音量 0..1。 */
    var volume: Float by mutableStateOf(kDefaultVolume.toFloat())
        private set

    /** 当前音色索引（对应 [kMetronomeSounds]）。 */
    var soundIndex: Int by mutableStateOf(0)
        private set

    /** 相位微调（秒）：正 = 点击整体滞后，负 = 提前。调用方应钳制在 ±半拍。 */
    var phaseOffset: Double by mutableStateOf(0.0)
        private set

    /** 每次真正「嗒」出声时回调，参数为媒体时间秒（页面用它画标尺上的打拍标记）。 */
    var onClick: ((Double) -> Unit)? = null

    /**
     * 是否启用「每小节第一拍重音」（4/4 拍，小节头更亮更高）。
     *
     * 说明：Dart / Web 版只有单一音高、既无重音也无小节线；这里是原生版的增强，默认开启，
     * 用 [setAccent] 关掉即可完全回到 Dart 的听感（音轨池会按新设置重建）。
     */
    var accentEnabled: Boolean by mutableStateOf(true)
        private set

    // —— 调度参数：UI 线程写、音频线程读，故用 @Volatile；复合更新走 lock ——

    private val lock = Any()

    @Volatile
    private var bpm: Double = kDefaultBpm

    @Volatile
    private var beatTimes: List<Double>? = null

    /** 等距网格锚点（秒）：start 的 fromSec；[phase] 非空时以 [phase] 优先。 */
    @Volatile
    private var gridAnchor: Double = 0.0

    /** 真实相位（秒）；null = 未知，退回以 [gridAnchor] 起的等距网格。 */
    @Volatile
    private var phase: Double? = null

    /** 音频线程读的镜像值（避免在音频线程上读 Compose state）。 */
    @Volatile
    private var volumeForAudio: Float = kDefaultVolume.toFloat()

    @Volatile
    private var soundForAudio: Int = 0

    @Volatile
    private var accentForAudio: Boolean = true

    /** 拍点游标：下一个待触发的真实拍点下标；-1 表示需要重定位。 */
    private var mapIdx: Int = -1

    /** 等距网格游标：上一次触发的格点序号。 */
    private var lastGridK: Long = Long.MIN_VALUE

    /** 估算时间被硬重同步 / 参数变化过 → 拍点游标需要重定位（避免补发一串「嗒」）。 */
    private var pointersStale: Boolean = true

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var released: Boolean = false

    /** 调度线程代号：start 会自增，旧线程发现代号变了立即退出（避免两条线程同时出声）。 */
    @Volatile
    private var generation: Int = 0

    private var thread: Thread? = null

    @Volatile
    private var isPlayingFn: () -> Boolean = { false }

    @Volatile
    private var positionFn: () -> Double = { 0.0 }

    // —— 音轨池 ——

    /** 一条静态音轨及其最近使用时间（LRU 选池用）。 */
    private class Slot(val track: AudioTrack) {
        val lastUsedMs = AtomicLong(0L)
    }

    /** 池内容对应的音色索引；-1 表示池为空。 */
    @Volatile
    private var poolSound: Int = -1

    /** 池内容对应的重音开关（切换时需要重建池）。 */
    @Volatile
    private var poolAccent: Boolean = true

    @Volatile
    private var weakPool: List<Slot> = emptyList()

    @Volatile
    private var accentPool: List<Slot> = emptyList()

    @Volatile
    private var tapSlot: Slot? = null

    init {
        // 恢复上次的音量与音色（对应 Dart 的 runbpm.metVolume / runbpm.metSound）。
        val v = prefs.getDouble(kPrefMetVolume, kDefaultVolume).toFloat().coerceIn(0f, 1f)
        volume = v
        volumeForAudio = v
        val idx = prefs.getInt(kPrefMetSound, 0).coerceIn(0, kMetronomeSounds.size - 1)
        soundIndex = idx
        soundForAudio = idx
    }

    /**
     * 启动节拍器。
     *
     * @param bpm 目标 BPM（<= 0 时退回 120）。
     * @param beatTimes 真实拍点（媒体时间秒，升序）；null 或少于 2 个则用等距网格。
     * @param fromSec 起始媒体时间（无相位信息时作为等距网格锚点）。
     * @param isPlaying 查询「当前是否在播放」；暂停时不推进、不出声。
     * @param positionSec 查询当前播放位置（秒），用于漂移校正。
     */
    fun start(
        bpm: Double,
        beatTimes: List<Double>?,
        fromSec: Double,
        isPlaying: () -> Boolean,
        positionSec: () -> Double,
    ) {
        if (released) return
        synchronized(lock) {
            this.bpm = if (bpm > 0.0) bpm else kDefaultBpm
            this.beatTimes = normalizeBeatTimes(beatTimes)
            this.gridAnchor = fromSec
            this.mapIdx = -1
            this.lastGridK = Long.MIN_VALUE
            this.pointersStale = true
        }
        isPlayingFn = isPlaying
        positionFn = positionSec
        enabled = true
        stopThread()
        startThread(fromSec)
    }

    /** 停止（保留已建好的音轨池，下次 start 可立即出声）。 */
    fun stop() {
        enabled = false
        stopThread()
    }

    /**
     * 运行中改变 BPM / 拍点：只刷新参数与游标，不重启线程（避免爆音与丢拍）。
     * 拍点表变化后游标会重定位到当前时间附近。
     */
    fun updateBpm(bpm: Double, beatTimes: List<Double>?) {
        synchronized(lock) {
            this.bpm = if (bpm > 0.0) bpm else kDefaultBpm
            this.beatTimes = normalizeBeatTimes(beatTimes)
            this.mapIdx = -1
            this.lastGridK = Long.MIN_VALUE
            this.pointersStale = true
        }
    }

    /** 设置音量 0..1：立即作用到池中所有音轨，并落盘（SharedPreferences 自身异步，无需去抖）。 */
    @kotlin.jvm.JvmName("applyVolumeSetting")
    fun setVolume(v: Float) {
        val clamped = v.coerceIn(0f, 1f)
        volume = clamped
        volumeForAudio = clamped
        for (slot in weakPool) applyVolume(slot, clamped)
        for (slot in accentPool) applyVolume(slot, clamped)
        tapSlot?.let { applyVolume(it, clamped) }
        prefs.putDouble(kPrefMetVolume, clamped.toDouble())
    }

    /** 切换音色；音轨池会在下次出声时按新音色重建。 */
    fun setSound(index: Int) {
        val clamped = index.coerceIn(0, kMetronomeSounds.size - 1)
        if (clamped == soundIndex) return
        soundIndex = clamped
        soundForAudio = clamped
        prefs.putInt(kPrefMetSound, clamped)
    }

    /** 开关「每小节第一拍重音」；音轨池会按新设置重建（下次出声时）。 */
    fun setAccent(on: Boolean) {
        if (on == accentEnabled) return
        accentEnabled = on
        accentForAudio = on
    }

    /** 设置真实相位（秒）；null = 未知（退回以 start 的 fromSec 起的等距网格）。 */
    fun setPhase(phase: Double?) {
        synchronized(lock) {
            this.phase = if (phase != null && phase >= 0.0) phase else null
            this.mapIdx = -1
            this.lastGridK = Long.MIN_VALUE
            this.pointersStale = true
        }
    }

    /** 相位微调（秒）：正 = 滞后，负 = 提前。 */
    @kotlin.jvm.JvmName("applyPhaseOffsetSec")
    fun setPhaseOffset(sec: Double) {
        val clamped = sec.coerceIn(-kMaxPhaseOffsetSec, kMaxPhaseOffsetSec)
        if (clamped == phaseOffset) return
        phaseOffset = clamped
        synchronized(lock) {
            this.mapIdx = -1
            this.lastGridK = Long.MIN_VALUE
            this.pointersStale = true
        }
    }

    /**
     * 播放一声「打拍校准」用的短促高音（G6 1568Hz 方波 + 快速衰减）。
     * 与节拍器音色刻意区分，对应 Dart `_writeTapWav` / Web `playTapClick`。
     */
    fun playTapClick() {
        if (released) return
        var slot = tapSlot
        if (slot == null) {
            slot = try {
                Slot(buildTrack(renderTapClick()))
            } catch (e: Exception) {
                Log.w(TAG, "打拍音轨创建失败：${e.message}")
                return
            }
            tapSlot = slot
        }
        trigger(slot, volumeForAudio)
    }

    /** 释放线程与全部音轨；释放后本对象不可再用。 */
    fun release() {
        if (released) return
        released = true
        enabled = false
        stopThread()
        synchronized(lock) {
            for (slot in weakPool) slot.track.release()
            for (slot in accentPool) slot.track.release()
            tapSlot?.track?.release()
            weakPool = emptyList()
            accentPool = emptyList()
            poolSound = -1
            tapSlot = null
        }
    }

    // —— 调度线程 ——

    private fun startThread(startEst: Double) {
        // 先彻底停掉上一条线程（等价于 Dart 的 _stopTimer + 重建 Timer）。
        running = false
        val old = thread
        thread = null
        old?.interrupt()
        old?.let { runCatching { it.join(kThreadJoinMs) } }

        generation++
        val gen = generation
        val t = Thread({ runLoop(gen, startEst) }, "bunbeat-metronome")
        t.isDaemon = true
        thread = t
        running = true
        t.start()
    }

    private fun stopThread() {
        running = false
        val t = thread
        thread = null
        t?.interrupt()
    }

    /**
     * 调度主循环。
     *
     * est 是「媒体时间估算」：播放中按墙钟推进；每 [kSyncIntervalNs] 与真实播放位置比对，
     * 偏差过大直接硬重同步，否则仅用差分速率微调（差分能消掉位置上报的固定滞后）。
     */
    private fun runLoop(gen: Int, startEst: Double) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        } catch (e: Exception) {
            Log.w(TAG, "设置音频线程优先级失败：${e.message}")
        }
        var est = startEst
        var rate = 1.0
        var lastWallNs = System.nanoTime()
        var lastSyncNs = lastWallNs
        var lastSyncMedia = startEst

        while (running && gen == generation) {
            val iterStartNs = System.nanoTime()
            val dtSec = (iterStartNs - lastWallNs) / 1e9
            lastWallNs = iterStartNs

            val playing = isPlayingFn()
            if (playing) est += dtSec * rate

            if (iterStartNs - lastSyncNs >= kSyncIntervalNs) {
                val windowSec = (iterStartNs - lastSyncNs) / 1e9
                val actual = runCatching { positionFn() }.getOrNull()
                if (actual != null && windowSec >= kMinSyncWindowSec) {
                    val diff = actual - est
                    if (abs(diff) > kHardResyncSec) {
                        // seek / 卡顿造成的整段偏移：直接对齐，并让拍点游标重定位。
                        est = actual
                        rate = 1.0
                        synchronized(lock) { pointersStale = true }
                    } else if (playing) {
                        val measured = (actual - lastSyncMedia) / windowSec
                        if (measured in kMinRate..kMaxRate) {
                            rate += (measured - rate) * kRateGain
                        }
                    }
                    lastSyncMedia = actual
                }
                lastSyncNs = iterStartNs
            }

            if (playing) schedule(est)

            val elapsedMs = (System.nanoTime() - iterStartNs) / 1_000_000L
            val sleepMs = kTickMs - elapsedMs
            if (sleepMs > 0L) {
                try {
                    Thread.sleep(sleepMs)
                } catch (e: InterruptedException) {
                    break
                }
            } else {
                Thread.yield()
            }
        }
    }

    /**
     * 找出 est 附近该出声的拍点并触发。
     *
     * 触发与「持锁」分离：先在锁内算出待触发列表，再在锁外播放（避免播放调用阻塞 UI 线程）。
     * 提前量 [kLookaheadSec] 补偿「判定 → 真正出声」的固有延迟（Dart 同为 0.03s）；
     * 落后超过 [kStaleSec] 的拍点静默丢弃，避免 seek 之后补发一串「嗒」。
     */
    private fun schedule(est: Double) {
        if (released) return
        // 音色 / 重音开关变了就重建池（幂等，正常情况下每帧只做一次比较）。
        if (poolSound != soundForAudio || poolAccent != accentForAudio) ensurePools()

        val fireTimes = ArrayList<Double>(2)
        val fireIndex = ArrayList<Long>(2)
        synchronized(lock) {
            val offset = phaseOffset
            val beats = beatTimes
            if (beats != null) {
                if (pointersStale || mapIdx < 0 ||
                    (mapIdx > 0 && beats[mapIdx - 1] + offset > est - kLookaheadSec)
                ) {
                    // 首次、参数变化、或 est 倒退（seek 回退）→ 游标重定位。
                    mapIdx = lowerBound(beats, est - kLookaheadSec)
                    pointersStale = false
                }
                while (mapIdx < beats.size) {
                    val beat = beats[mapIdx] + offset
                    if (beat > est + kLookaheadSec) break
                    if (beat >= est - kStaleSec) {
                        fireTimes.add(beat)
                        fireIndex.add(mapIdx.toLong())
                    }
                    mapIdx++
                }
            } else {
                val period = 60.0 / bpm
                if (period > 0.0) {
                    val anchor = (phase ?: gridAnchor) + offset
                    var k = ceil((est - kLookaheadSec - anchor) / period).toLong()
                    if (k < 0L) k = 0L
                    while (true) {
                        val beat = anchor + k * period
                        if (beat > est + kLookaheadSec) break
                        if (k != lastGridK && beat >= est - kStaleSec) {
                            fireTimes.add(beat)
                            fireIndex.add(k)
                            lastGridK = k
                        }
                        k++
                    }
                }
            }
        }

        for (i in fireTimes.indices) fire(fireTimes[i], fireIndex[i])
    }

    /** 真正触发一声：先回调，再按「是否是每小节第一拍」选重音池或弱音池播放。 */
    private fun fire(mediaSeconds: Double, barIndex: Long) {
        onClick?.invoke(mediaSeconds)
        val pool = if (accentForAudio && barIndex % kBeatsPerBar == 0L) accentPool else weakPool
        if (pool.isEmpty()) return
        // 选取「最久未用」的一条，降低快速连击时的重叠/丢拍概率（Dart 同策略）。
        val nowMs = System.currentTimeMillis()
        var best = pool[0]
        for (slot in pool) {
            if (slot.lastUsedMs.get() < best.lastUsedMs.get()) best = slot
        }
        best.lastUsedMs.set(nowMs)
        trigger(best, volumeForAudio)
    }

    /** 建 / 重建弱音与重音池（音频线程调用；音色或重音开关变化后触发）。 */
    private fun ensurePools() {
        if (released) return
        val idx = soundForAudio
        val accentOn = accentForAudio
        val sound = kMetronomeSounds.getOrNull(idx) ?: return
        val old = weakPool + accentPool
        val weak = ArrayList<Slot>(kPoolSize)
        val accent = ArrayList<Slot>(kPoolSize)
        try {
            val weakPcm = renderClick(sound, accent = false)
            val accentPcm = if (accentOn) renderClick(sound, accent = true) else weakPcm
            for (i in 0 until kPoolSize) {
                weak.add(Slot(buildTrack(weakPcm)))
                accent.add(Slot(buildTrack(accentPcm)))
            }
        } catch (e: Exception) {
            Log.w(TAG, "节拍器音轨创建失败：${e.message}")
            for (slot in weak) slot.track.release()
            for (slot in accent) slot.track.release()
            // 记下已尝试的设置，避免每帧都重建失败一遍（音色/重音再次变化时会重试）。
            poolSound = idx
            poolAccent = accentOn
            return
        }
        weakPool = weak
        accentPool = accent
        poolSound = idx
        poolAccent = accentOn
        for (slot in old) slot.track.release()
    }

    /** 播放一条静态音轨：播完会停在末尾，必须先 stop + reloadStaticData 再 play。 */
    private fun trigger(slot: Slot, vol: Float) {
        val track = slot.track
        try {
            if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
            track.reloadStaticData()
            track.setVolume(vol)
            track.play()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "节拍器播放失败：${e.message}")
        }
    }

    private fun applyVolume(slot: Slot, vol: Float) {
        try {
            slot.track.setVolume(vol)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "节拍器音量设置失败：${e.message}")
        }
    }

    private companion object {
        const val TAG = "BunbeatMetronome"

        /** 与 Dart 同键：节拍器音量 / 音色。 */
        const val kPrefMetVolume = "runbpm.metVolume"
        const val kPrefMetSound = "runbpm.metSound"

        const val kDefaultVolume = 0.5
        const val kDefaultBpm = 120.0

        /** 每小节拍数：重音落在每小节第一拍（4/4）。 */
        const val kBeatsPerBar = 4L

        /** 轮询周期（毫秒）：Dart 是 16ms，原生用 8ms 让提前量更稳。 */
        const val kTickMs = 8L

        /** 等待旧调度线程退出的上限（毫秒）。 */
        const val kThreadJoinMs = 200L

        /** 提前触发量（秒）：补偿「判定 → 真正出声」的固有延迟（Dart 为 0.03）。 */
        const val kLookaheadSec = 0.03

        /** 落后超过该值的拍点视为已过期，静默丢弃（避免 seek 后补发串音）。 */
        const val kStaleSec = 0.12

        /** 估算值与真实播放位置偏差超过该值 → 硬重同步。 */
        const val kHardResyncSec = 0.15

        /** 位置比对周期与最小比对窗口。 */
        const val kSyncIntervalNs = 500_000_000L
        const val kMinSyncWindowSec = 0.3

        /** 速率微调增益与允许区间。 */
        const val kRateGain = 0.25
        const val kMinRate = 0.25
        const val kMaxRate = 4.0

        /** 每个池的音轨数（Dart `_poolSize = 4`）。 */
        const val kPoolSize = 4

        /** 相位微调上限（±半拍左右的安全值）。 */
        const val kMaxPhaseOffsetSec = 0.5

        /** 拍点表少于 2 个视为无效（Dart 同规则）。 */
        fun normalizeBeatTimes(src: List<Double>?): List<Double>? {
            if (src == null || src.size < 2) return null
            return src.sorted()
        }

        /** 首个 >= target 的下标（Dart `_lowerBound`）。 */
        fun lowerBound(list: List<Double>, target: Double): Int {
            var lo = 0
            var hi = list.size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (list[mid] < target) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }
}

// —— PCM 合成：样本公式与 Dart 生成 WAV 时逐行对应 ——

/** 合成采样率：与 Dart 生成的 WAV 头部一致（44100Hz 单声道 PCM16）。 */
private const val kSamplesPerSec = 44100

/** 重音相对弱音的变形：更高、更短、更亮、更响（Dart / Web 无重音，此处为原生增强）。 */
private const val kAccentFreqMul = 1.5
private const val kAccentDurMul = 0.85
private const val kAccentDecayMul = 1.2
private const val kAccentAmpMul = 1.15

/**
 * 渲染一声节拍器「嗒」：`env = exp(-decay*t)`、`wave = 方波 ? sign(sin) : sin`、
 * `v = wave * env * amp`，再量化成 PCM16（Dart `_writeClickWav` 的同一套公式）。
 * [accent] = true 时用更亮更高的变形（仅原生版有重音）。
 */
private fun renderClick(sound: MetronomeSound, accent: Boolean): ShortArray {
    val freq = if (accent) sound.freq * kAccentFreqMul else sound.freq
    val dur = if (accent) sound.dur * kAccentDurMul else sound.dur
    val decay = if (accent) sound.decay * kAccentDecayMul else sound.decay
    val amp = if (accent) (sound.amp * kAccentAmpMul).coerceAtMost(1.0) else sound.amp
    val n = (kSamplesPerSec * dur).roundToInt().coerceAtLeast(1)
    val out = ShortArray(n)
    for (i in 0 until n) {
        val t = i.toDouble() / kSamplesPerSec
        val env = exp(-decay * t)
        val s = sin(2.0 * PI * freq * t)
        val wave = if (sound.square) (if (s >= 0.0) 1.0 else -1.0) else s
        val v = wave * env * amp
        out[i] = (v * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort()
    }
    return out
}

/**
 * 渲染「打拍校准」短音：G6（1568Hz）方波 70ms、`exp(-45*t)` 快速衰减、振幅 0.35。
 * 对应 Dart `_writeTapWav` / Web `metronome.playTapClick`。
 */
private fun renderTapClick(): ShortArray {
    val freq = 1568.0
    val dur = 0.07
    val n = (kSamplesPerSec * dur).roundToInt().coerceAtLeast(1)
    val out = ShortArray(n)
    for (i in 0 until n) {
        val t = i.toDouble() / kSamplesPerSec
        val env = exp(-45.0 * t)
        val wave = if (sin(2.0 * PI * freq * t) >= 0.0) 1.0 else -1.0
        val v = wave * env * 0.35
        out[i] = (v * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort()
    }
    return out
}

/** 用一段 PCM 建一条 MODE_STATIC 音轨（数据一次写入，之后靠 reloadStaticData 重播）。 */
private fun buildTrack(pcm: ShortArray): AudioTrack {
    val needBytes = pcm.size * 2
    val minBuf = AudioTrack.getMinBufferSize(
        kSamplesPerSec,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )
    val bufBytes = if (minBuf > needBytes) minBuf else needBytes
    val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(kSamplesPerSec)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setBufferSizeInBytes(bufBytes)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()
    track.write(pcm, 0, pcm.size)
    return track
}
