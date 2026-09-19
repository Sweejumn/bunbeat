package com.bunbeat.nativeapp.player

// 播放控制：media3 ExoPlayer 封装（对应 Dart `AudioPlayerService` + just_audio）。
//
// 变速：ExoPlayer 的 `PlaybackParameters(speed)` 默认保持音高（time-stretch），
// 因此把 speed 设为 targetBpm / originalBpm 即可在不变调的前提下把节奏统一到目标 BPM
// —— 对应 Web 版的 FFmpeg atempo 时间拉伸、Dart 版的 `setSpeed`。
//
// 与 Dart 版的两点工程差异（Dart 用 just_audio 的 positionStream / durationStream 订阅）：
//   1. 位置/时长/播放状态用 `Player.Listener` + 一个 50ms 的协程轮询刷到 Compose state，
//      暂停时停掉轮询省电（ExoPlayer 没有等价的流式位置事件）；
//   2. 载入失败不再抛异常，而是记录日志并回调 `onError`，让上层决定跳过还是提示
//      （对应 Dart `tryPlay` 返回 false 的「出错跳过」策略）。

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bunbeat.nativeapp.audio.AnalysisCache
import com.bunbeat.nativeapp.core.Prefs
import com.bunbeat.nativeapp.model.Song
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放控制器：一个 ExoPlayer 实例 + 面向 Compose 的状态。
 *
 * 状态属性都是 `mutableStateOf`（契约里的 `val` 读语义不变，只是内部可写），
 * 页面直接读即可触发重组。
 */
class PlayerController(
    context: Context,
    private val prefs: Prefs,
    private val cache: AnalysisCache,
) {
    private val appContext: Context = context.applicationContext

    /** 底层播放器（页面需要 ExoPlayer 原生能力时可直接用）。 */
    val exo: ExoPlayer = ExoPlayer.Builder(appContext).build()

    /** 轮询协程的作用域：只用于位置轮询与「时长兜底」这两件事。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null
    private var volumeSaveJob: Job? = null
    private var released = false

    /** 一首播完只回调一次（对应 Dart 页面里的 `_handlingEnded` 守卫）。 */
    private var completionNotified = false

    /** 当前曲；未载入为 null。 */
    var currentSong: Song? by mutableStateOf(null)
        private set

    /** 是否正在出声（对应 just_audio 的 `player.playing`）。 */
    var isPlaying: Boolean by mutableStateOf(false)
        private set

    /** 当前播放位置（秒，媒体时间轴）。 */
    var positionSec: Double by mutableStateOf(0.0)
        private set

    /** 当前曲时长（秒）；ExoPlayer 拿不到时用曲库/分析缓存里的时长兜底。 */
    var durationSec: Double by mutableStateOf(0.0)
        private set

    /** 变速倍率（保持音高）。 */
    var speed: Float by mutableStateOf(1f)
        private set

    /** 音乐音量 0..1（对应 Dart `player.setVolume`）。 */
    var volume: Float by mutableStateOf(1f)
        private set

    /** 缓冲就绪（该曲已可播放/正在播放）。载入新曲或出错时回到 false。 */
    var ready: Boolean by mutableStateOf(false)
        private set

    /** 播完自动下一首（对应 Dart `processingState == completed` → `_onEnded`）。 */
    var onCompleted: (() -> Unit)? = null

    /**
     * 载入/播放失败回调（契约之外的额外成员，用于「出错跳过」）：
     * 坏文件、路径不存在、解码失败都不崩溃，只记日志并把可读原因交给上层。
     */
    var onError: ((String) -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) {
                // 离开「播完」状态（重播/seek）后，允许下一次播完再次回调。
                completionNotified = false
            }
            when (playbackState) {
                Player.STATE_READY -> {
                    ready = true
                    syncFromPlayer()
                }
                Player.STATE_BUFFERING -> {
                    // 播放中途的缓冲不把 ready 打回 false，避免 UI / 节拍器来回抖动。
                    syncFromPlayer()
                }
                Player.STATE_ENDED -> {
                    ready = true
                    if (durationSec > 0.0) positionSec = durationSec
                    if (!completionNotified) {
                        completionNotified = true
                        onCompleted?.invoke()
                    }
                }
                else -> {
                    // STATE_IDLE：未载入或出错
                    ready = false
                    syncFromPlayer()
                }
            }
            refreshPlaying()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            refreshPlaying()
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            // media3 的 isPlaying 在缓冲瞬间会变 false，只借它做一次状态刷新，不作为对外语义。
            refreshPlaying()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            speed = playbackParameters.speed
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "play load failed: ${error.errorCodeName} ${error.message}")
            ready = false
            isPlaying = false
            stopTicker()
            onError?.invoke("无法播放「${currentSong?.displayTitle() ?: ""}」：${error.errorCodeName}")
        }
    }

    init {
        // 恢复上次的音乐音量（对应 Dart `runbpm.musicVolume`，默认 1.0）。
        volume = prefs.getDouble(kPrefMusicVolume, 1.0).toFloat().coerceIn(0f, 1f)
        exo.volume = volume
        exo.addListener(listener)
    }

    /**
     * 载入一首并（可选）立即播放。
     *
     * NCM 等场景：直接用 [Song.filePath]（解密后的可播放路径由分析阶段写回曲库）。
     * 文件不存在 / 打开失败都不抛异常，只记日志并走 [onError]。
     */
    fun load(song: Song, autoPlay: Boolean = false) {
        if (released) {
            Log.w(TAG, "player 已释放，忽略 load：${song.filePath}")
            return
        }
        currentSong = song
        completionNotified = false
        ready = false
        positionSec = 0.0
        durationSec = song.duration ?: 0.0
        if (song.duration == null) fallbackDurationFromCache(song)

        val path = song.filePath
        if (!isContentUri(path) && !File(path).isFile) {
            Log.w(TAG, "play load failed: 文件不存在或不可读 $path")
            onError?.invoke("文件不存在：${song.displayTitle()}")
            return
        }

        try {
            val uri = if (isContentUri(path)) Uri.parse(path) else Uri.fromFile(File(path))
            exo.setMediaItem(MediaItem.fromUri(uri))
            exo.prepare()
            // 变速与音量沿用当前设置（对应 Dart `tryPlay` 里每次 setUrl 后重新 setSpeed）。
            exo.playbackParameters = PlaybackParameters(speed)
            exo.volume = volume
            if (autoPlay) exo.play() else exo.pause()
        } catch (e: Exception) {
            Log.w(TAG, "play load failed: $e")
            ready = false
            onError?.invoke("载入失败：${song.displayTitle()}")
        }
    }

    /**
     * 只装载当前曲（设源 + 变速）但不自动播放：退出后恢复队列时把歌曲「准备好」，
     * 等用户点播放键再出声（对应 Dart `loadPaused`）。
     */
    fun loadPaused(song: Song) = load(song, autoPlay = false)

    fun play() {
        if (released) return
        if (exo.playbackState == Player.STATE_ENDED) {
            // 播完后再点播放：从头重播（单曲循环场景，Dart 也是 seek(0) + play）。
            exo.seekTo(0L)
            positionSec = 0.0
        }
        exo.play()
    }

    fun pause() {
        if (released) return
        exo.pause()
    }

    fun toggle() {
        if (exo.isPlaying) pause() else play()
    }

    /** 跳转到指定秒（自动钳制到 [0, durationSec]）。 */
    fun seekTo(sec: Double) {
        if (released) return
        val target = if (durationSec > 0.0) sec.coerceIn(0.0, durationSec) else sec.coerceAtLeast(0.0)
        positionSec = target
        exo.seekTo((target * 1000.0).toLong())
    }

    /** 相对跳转（秒，可为负）。 */
    fun seekBy(deltaSec: Double) = seekTo(positionSec + deltaSec)

    /** 设置变速倍率（保持音高）；上下限与 Dart `PlaylistItem.speed` 的 clamp 一致。 */
    @kotlin.jvm.JvmName("applySpeed")
    fun setSpeed(v: Float) {
        val clamped = v.coerceIn(kMinSpeed, kMaxSpeed)
        speed = clamped
        if (!released) exo.playbackParameters = PlaybackParameters(clamped)
    }

    /**
     * 设置音乐音量 0..1。
     *
     * 写盘做了 400ms 去抖：滑杆拖动期间每帧都会调用，Dart 版也是拖完才落盘
     * （避免大量并发异步写盘）。
     */
    @kotlin.jvm.JvmName("applyVolumeSetting")
    fun setVolume(v: Float) {
        val clamped = v.coerceIn(0f, 1f)
        volume = clamped
        if (!released) exo.volume = clamped
        volumeSaveJob?.cancel()
        volumeSaveJob = scope.launch {
            delay(400L)
            prefs.putDouble(kPrefMusicVolume, clamped.toDouble())
        }
    }

    /** 释放播放器与轮询协程；释放后本对象不可再用。 */
    fun release() {
        if (released) return
        released = true
        stopTicker()
        volumeSaveJob?.cancel()
        volumeSaveJob = null
        scope.cancel()
        exo.removeListener(listener)
        exo.release()
        ready = false
        isPlaying = false
    }

    // —— 内部实现 ——

    /** 从播放器拉一次状态刷到 Compose state。 */
    private fun syncFromPlayer() {
        positionSec = if (exo.playbackState == Player.STATE_ENDED) {
            durationSec
        } else {
            exo.currentPosition.coerceAtLeast(0L) / 1000.0
        }
        val d = exo.duration
        if (d != C.TIME_UNSET && d > 0L) durationSec = d / 1000.0
        val s = exo.playbackParameters.speed
        if (s != speed) speed = s
    }

    /**
     * 刷新「正在播放」与位置轮询。
     *
     * 对外语义用 playWhenReady（播放意图）而不是 media3 的 isPlaying：后者在缓冲中会瞬间
     * 变成 false，而 just_audio 的 `playing` 始终等于播放意图，UI 不应因为缓冲闪一下；
     * 播完（ENDED）时按 just_audio 的观感回到「未播放」，让播放键重新可用。
     */
    private fun refreshPlaying() {
        val playing = exo.playWhenReady && exo.playbackState != Player.STATE_ENDED
        isPlaying = playing
        if (playing) {
            startTicker()
        } else {
            stopTicker()
            syncFromPlayer()
        }
    }

    private fun startTicker() {
        if (released) return
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                syncFromPlayer()
                delay(kPositionPollMs)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /**
     * 时长兜底：少数容器 ExoPlayer 拿不到时长（C.TIME_UNSET），
     * 用分析缓存里记录的解码时长顶上，让进度条 / 拍点标尺仍可用。
     */
    private fun fallbackDurationFromCache(song: Song) {
        scope.launch {
            val cached = withContext(Dispatchers.IO) {
                runCatching { cache.load(song)?.duration }.getOrNull()
            }
            // 只在仍是同一首、且播放器还没给出时长时兜底。
            if (cached != null && cached > 0.0 &&
                currentSong?.id == song.id && durationSec <= 0.0
            ) {
                durationSec = cached
            }
        }
    }

    private fun isContentUri(path: String): Boolean = path.startsWith("content://")

    companion object {
        private const val TAG = "PlayerController"

        /** 播放速率上下限：与 Dart `PlaylistItem.speed` 的 clamp(0.5, 2.0) 一致。 */
        const val kMinSpeed: Float = 0.5f
        const val kMaxSpeed: Float = 2.0f

        /** 位置轮询周期（毫秒）。暂停时会停掉轮询。 */
        private const val kPositionPollMs = 50L

        /** 与 Dart 同键：音乐音量落盘键。 */
        private const val kPrefMusicVolume = "runbpm.musicVolume"

        /**
         * 播放速率 = 目标 BPM / 原始 BPM（保持音高），对应 Dart `PlaylistItem.speed`。
         * 契约之外的额外成员：页面算「变速 ×1.05」与调用 setSpeed 时不用重复实现钳制。
         */
        fun computeSpeed(originalBpm: Double?, targetBpm: Double): Float {
            if (originalBpm == null || originalBpm <= 0.0) return 1f
            return (targetBpm / originalBpm).coerceIn(kMinSpeed.toDouble(), kMaxSpeed.toDouble()).toFloat()
        }
    }
}
