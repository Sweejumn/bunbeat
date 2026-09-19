package com.bunbeat.nativeapp.ui

// 对应 Dart `ui/player_page.dart`：本文件覆盖第 1–1051 行（`PlayerPage` / `_PlayerPageState`）、
// 第 1037–1051 行（`_ArtworkPlaceholder`）、第 1273–1341 行（`_TapPulseButton`）。
// 第 1052–1272 行的 `_PlaylistSheet` / `_PlaylistTile` 由 `ui/PlaylistSheet.kt` 提供（本文件只调用）。
//
// 与 Dart 的承载差异（行为尽量对齐，写法按 Compose 惯例）：
//   1. Dart 的 PlayerPage 自带 Scaffold + AppBar；原生侧播放页是 HomePage 底部 Tab 的一页，
//      外层已有 Scaffold。这里沿用 RecommendPage 的做法：内层再套一个 contentWindowInsets 归零的
//      Scaffold（topBar = 工具栏行、bottomBar = 常驻播放控制条），结构上与 Dart 一一对应。
//   2. Dart 靠 just_audio 的 positionStream / durationStream / playerStateStream 订阅触发 setState；
//      原生侧 PlayerController 已把位置 / 时长 / 播放状态刷成 Compose state，页面直接读即会重组，
//      只有「播放开始 / 暂停」这两个副作用需要 LaunchedEffect(isPlaying) 复刻。
//   3. Dart 的页面状态挂在 State 上（含 _tapMediaSec 等），这里集中放进 `PlayerPageState`，
//      方法名与 Dart 一一对应（loadPrefs / savePref / reAnchor / onPlaybackStart / loadCurrent ...）。
//   4. Dart 的 `_savePref` 串行写盘链 + 生命周期 `_flushPrefs`：原生 `Prefs` 每次调用即刻写入
//      （SharedPreferences.apply 自身异步且有序），不再需要排队与后台落盘。

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bunbeat.nativeapp.AppState
import com.bunbeat.nativeapp.model.BeatMode
import com.bunbeat.nativeapp.model.Song
import com.bunbeat.nativeapp.player.BeatRuler
import com.bunbeat.nativeapp.player.PlayerController
import com.bunbeat.nativeapp.player.kMetronomeSounds
import com.bunbeat.nativeapp.store.LoopMode
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// —— 持久化键：与 Dart 的 `_prefXxx` 常量逐字一致（对应 Web 的 localStorage 键）——
private const val kPrefMetOn = "runbpm.metronomeOn"
private const val kPrefPhaseNudge = "runbpm.phaseNudge"
private const val kPrefBeatMode = "runbpm.beatMode"
private const val kPrefTapSound = "runbpm.tapSound"
private const val kPrefMusicVolume = "runbpm.musicVolume"
private const val kPrefMetVolume = "runbpm.metVolume"
private const val kPrefMetSound = "runbpm.metSound"

/** 载入失败时最多连续跳过几首（对应 Dart `_maxFailures = 3 + 1`）。 */
private const val kMaxFailures = 3 + 1

/** 打拍按钮的一次闪光时长（毫秒，对应 Dart `AnimationController(duration: 240ms)`）。 */
private const val kTapPulseMillis = 240f

/** 封面解码的目标边长（像素）：96dp 在 3x 屏上是 288px。 */
private const val kArtworkPx = 288

/** 与 Dart `Colors.grey` 一致的占位图标色。 */
private val kGrey = Color(0xFF9E9E9E)

/**
 * 由一批打拍点计算 BPM（对应 Dart `computeTapBpm`，第 23–33 行）。
 * 取相邻媒体时间差、过滤 ≤0.1s（双击 / 误触 / seek 回退）、取中位后返回 60/中位。
 * **不四舍五入成整数**：中位间隔的 ~1ms 精度能让 BPM 可靠到 1~2 位小数，
 * 以便两位小数显示有意义。不足 2 个有效间隔返回 null。
 */
private fun computeTapBpm(taps: List<Double>): Double? {
    val intervals = ArrayList<Double>(taps.size)
    for (i in 1 until taps.size) {
        val d = taps[i] - taps[i - 1]
        if (d > 0.1) intervals.add(d)
    }
    if (intervals.size < 2) return null
    intervals.sort()
    val median = intervals[intervals.size / 2]
    return 60.0 / median
}

/**
 * 播放页（对应 Dart `PlayerPage` + `_PlayerPageState`，第 35–1034 行）。
 *
 * 页面结构与 Dart 一致：顶栏（播放 + 使用说明 / 设置）→ 可滚动主体
 * （封面卡片 / 拍点标尺 / 节拍模式与打拍校准 / 节拍器面板）→ 底部常驻播放控制条。
 */
@Composable
fun PlayerPage(app: AppState, onBack: () -> Unit = {}) {
    val state = remember(app) { PlayerPageState(app) }
    var showHelp by remember { mutableStateOf(false) }
    // 顶栏「滚动才升起」（对应 Shizuku 的 liftOnScroll）。
    val behavior = rememberBunbeatScrollBehavior()

    // 对应 Dart `initState` 里的异步 `_loadPrefs()`（默认值先上，读盘到位后覆盖）。
    LaunchedEffect(Unit) { state.loadPrefs() }

    // 对应 Dart 的 `playerStateStream` 订阅：
    //   播放开始 → `_onPlaybackStart`（必要时自动开节拍器并锚定，对齐 Web 默认开启）；
    //   暂停（含控制中心 / 耳机 / 来电打断等外部暂停）→ 停节拍器。
    LaunchedEffect(app.player.isPlaying) {
        if (app.player.isPlaying) {
            state.onPlaybackStart()
        } else {
            app.metronome.stop()
        }
    }

    if (showHelp) {
        HelpDialogContent(section = HelpSection.player, onDismiss = { showHelp = false })
    }

    // 播放列表底部弹层（内容由 `ui/PlaylistSheet.kt` 实现，接口已冻结）。
    if (state.sheetVisible) {
        PlaylistSheet(
            app = app,
            onDismiss = { state.sheetVisible = false },
            onPlayIndex = { i ->
                app.queue.jumpTo(i)
                state.loadCurrent()
            },
        )
    }

    val queue = app.queue
    val current = queue.current

    if (queue.items.isEmpty() || current == null) {
        // 对应 Dart `build` 的 `!queue.hasQueue || current == null` 分支。
        Column(modifier = Modifier.fillMaxSize()) {
            PlayerTopBar(
                onBack = onBack,
                showActions = false,
                onHelp = { showHelp = true },
                onSettings = { app.nav.openSettings() },
            )
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("到「推荐」页选择运动模式并开始播放")
            }
        }
        return
    }

    // 从曲库找到当前曲目以读取内嵌封面 + 拍点 / 相位可靠性（对应 Dart 的 for 循环）。
    val song = state.songFor(current.filePath)
    val artwork = song?.artworkPath
    val targetBpm = app.library.targetBpm
    val beatList = state.rulerBeats(song)

    Scaffold(
        // 本页嵌在 HomePage 的 Scaffold 内容区里（外层已处理系统栏内边距）。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            PlayerTopBar(
                onBack = onBack,
                showActions = true,
                onHelp = { showHelp = true },
                onSettings = { app.nav.openSettings() },
                scrollBehavior = behavior,
            )
        },
        // 底部固定控制条：进度 + 播放模式 + 上一首 / 播放暂停 / 下一首 + 播放列表入口，
        // 常驻可见，不必翻到列表底部。Dart 还包了一层 SafeArea(top: false)，
        // 原生侧由 HomePage 的 Scaffold（底部 Tab 栏）统一处理系统栏内边距。
        bottomBar = { PlayerPlaybackBar(app = app, state = state) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 可滚动主体（对应 Dart 的 Expanded + SingleChildScrollView(padding: 16)）。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(behavior.nestedScrollConnection)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                // 歌曲封面 / 标题 / BPM 信息（对应 Dart 的 Card + Padding(16) + Row）。
                Card(modifier = Modifier.fillMaxWidth(), shape = kCardCorner) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlayerArtwork(path = artwork)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                // Dart: `current.filePath.split('/').last`。
                                text = current.filePath.substringAfterLast('/'),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                // Dart: `format(originalBpm) → format(targetBpm) BPM`。
                                text = "${app.settings.formatBpm(current.originalBpm)}→" +
                                    "${app.settings.formatBpm(targetBpm)} BPM",
                            )
                            Text(
                                text = "变速 ×" + String.format(Locale.ROOT, "%.2f", app.player.speed),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                PlayerBeatRuler(
                    beats = beatList,
                    positionSec = app.player.positionSec,
                    durationSec = app.player.durationSec,
                    onSeek = { sec -> app.player.seekTo(sec) },
                )
                Spacer(modifier = Modifier.height(8.dp))
                PlayerBeatControls(app = app, state = state)
                Spacer(modifier = Modifier.height(12.dp))
                PlayerMetronomeControls(app = app, state = state)
            }
        }
    }
}

/**
 * 顶部工具栏（对应 Dart 播放页的 `AppBar`：标题「播放」+ 使用说明 / 设置）。
 *
 * v0.3 起改用公共的 [BunbeatTopBar]（M3 TopAppBar，带返回箭头 + 滚动升起），
 * 尺寸/配色对齐 Shizuku 的 `AppBarLayout + MaterialToolbar`。
 * 图标按钮的 tooltip 在 Compose 侧没有稳定 API，这里只保留 contentDescription（无障碍文案一致）。
 * [showActions] 对应空队列分支（没有 actions 时仍然显示返回）。
 */
@Composable
private fun PlayerTopBar(
    onBack: () -> Unit,
    showActions: Boolean,
    onHelp: () -> Unit,
    onSettings: () -> Unit,
    scrollBehavior: BunbeatScrollBehavior? = null,
) {
    BunbeatTopBar(
        title = "播放",
        onBack = onBack,
        scrollBehavior = scrollBehavior,
        actions = {
            if (showActions) {
                IconButton(onClick = onHelp) {
                    Icon(imageVector = Icons.Outlined.HelpOutline, contentDescription = "使用说明")
                }
                IconButton(onClick = onSettings) {
                    Icon(imageVector = Icons.Outlined.Settings, contentDescription = "设置")
                }
            }
        },
    )
}

/**
 * 页面状态（对应 Dart `_PlayerPageState`，第 42–1034 行的状态字段与逻辑方法）。
 *
 * 所有会被 UI 读到的字段都是 Compose state；`tapMediaSec` / `tapMarks` 用快照列表，
 * 使其长度变化能直接驱动重组（Dart 侧靠 setState 达到同样效果）。
 */
private class PlayerPageState(private val app: AppState) {

    // —— 音量（对应 _metVolume / _musicVolume）——
    var metVolume by mutableStateOf(0.5f)
    var musicVolume by mutableStateOf(1f)

    // —— 持久化设置（对应 _activeBeatMode / _phaseNudgePct / _metEnabled / _tapSoundOn / _metSoundIndex）——
    var activeBeatMode by mutableStateOf(BeatMode.GRID)
    var phaseNudgePct by mutableStateOf(0.0)
    var metEnabled by mutableStateOf(true)
    var tapSoundOn by mutableStateOf(false)
    var metSoundIndex by mutableStateOf(0)

    // —— 打拍校准（对应 _tapMediaSec / _lastTapAtMs / _tapMarks / _tapBpm / _tapSetFirst）——
    val tapMediaSec = mutableStateListOf<Double>()
    var lastTapAtMs = 0L
    val tapMarks = mutableStateListOf<Double>()
    var tapBpm by mutableStateOf<Double?>(null)
    var tapSetFirst by mutableStateOf(false)

    /** 播放列表弹层是否可见（对应 Dart 的 showModalBottomSheet 调用点）。 */
    var sheetVisible by mutableStateOf(false)

    /** 读盘后把恢复值应用到播放器 / 节拍器（对应 Dart `_loadPrefs`，第 116–145 行）。 */
    fun loadPrefs() {
        val prefs = app.prefs
        // Dart 用 `for (m in BeatMode.values) if (m.name == modeStr)`：找不到就保持 grid。
        activeBeatMode = BeatMode.fromKey(prefs.getString(kPrefBeatMode))
        phaseNudgePct = prefs.getDouble(kPrefPhaseNudge, 0.0).coerceIn(-50.0, 50.0)
        metEnabled = prefs.getBool(kPrefMetOn, true)
        tapSoundOn = prefs.getBool(kPrefTapSound, false)
        val idx = prefs.getInt(kPrefMetSound, 0).coerceIn(0, kMetronomeSounds.size - 1)
        metSoundIndex = idx
        musicVolume = prefs.getDouble(kPrefMusicVolume, 1.0).coerceIn(0.0, 1.0).toFloat()
        metVolume = prefs.getDouble(kPrefMetVolume, 0.5).coerceIn(0.0, 1.0).toFloat()
        // 把恢复的音量 / 音色应用到播放器 / 节拍器。
        app.player.setVolume(musicVolume)
        app.metronome.setVolume(metVolume)
        // 恢复上次选中的音色（无副作用：索引相同则 setSound 内部直接返回）。
        app.metronome.setSound(idx)
    }

    /**
     * 写一项设置（对应 Dart `_savePref`）。
     * 原生 `Prefs` 每次调用即刻写入 SharedPreferences，无需 Dart 那样的串行排队。
     */
    fun savePref(key: String, value: Any) {
        when (value) {
            is Boolean -> app.prefs.putBool(key, value)
            is Double -> app.prefs.putDouble(key, value)
            is Float -> app.prefs.putDouble(key, value.toDouble())
            is Int -> app.prefs.putInt(key, value)
            is String -> app.prefs.putString(key, value)
            else -> Unit
        }
    }

    /** 偏差滑杆（±50% 拍距）换算成秒位移（对应 Dart `_phaseNudgeSeconds`）。 */
    fun phaseNudgeSeconds(targetBpm: Double): Double = phaseNudgePct / 100.0 * (60.0 / targetBpm)

    /** 从曲库按 filePath 找当前曲（对应 Dart `_reAnchor` / `build` 里的 for 循环）。 */
    fun songFor(filePath: String): Song? = app.library.songs.firstOrNull { it.filePath == filePath }

    /** 当前曲对应的拍点表（按所选节拍模式取，对应 Dart `song.beatMaps?[mode] ?? song.beatTimes`）。 */
    fun beatsFor(song: Song?): List<Double>? =
        song?.beatMaps?.get(activeBeatMode.key) ?: song?.beatTimes

    /**
     * 交给拍点标尺显示的拍点（对应 Dart `_currentBeatList` 的取值部分）。
     *
     * 有意差异：Dart 会把「偏差微调」的秒位移叠加到每个拍点后再显示；原生侧不做这一步
     * ——拍点标尺是整首时间轴形态（点击 / 拖动即 seek），若把拍点整体位移，绘制的竖线会与
     * 真实播放位置错开；相位微调在原生侧由 `Metronome.setPhaseOffset` / `setPhase` 承担。
     */
    fun rulerBeats(song: Song?): List<Double> = beatsFor(song).orEmpty()

    /**
     * 播放开始时：若节拍器设置开启则自动开启并锚定（对应 Dart `_onPlaybackStart`）。
     */
    fun onPlaybackStart() {
        if (metEnabled && !app.metronome.enabled) startMetronome()
        reAnchor()
    }

    /**
     * 启动节拍器线程（对应 Dart `ensureInitialized → setBpm(targetBpm) → setEnabled(true)`）。
     * 原生 Metronome 用 AudioTrack 静态音轨池，无需 Dart 的「写 WAV + 预建播放器池」预热步骤，
     * 音轨在音频线程内首次出声时按需建立。
     */
    private fun startMetronome() {
        val song = app.queue.current?.let { songFor(it.filePath) }
        val beats = beatsFor(song)
        app.metronome.start(
            bpm = app.library.targetBpm,
            beatTimes = beats,
            fromSec = app.player.positionSec,
            isPlaying = { app.player.isPlaying },
            positionSec = { app.player.positionSec },
        )
        if (beats.isNullOrEmpty()) app.metronome.setPhase(song?.beatOffset)
    }

    /**
     * 把当前 BPM / 拍点 / 相位重新锚定到节拍器（对应 Dart `_reAnchor`，第 237–268 行）。
     *
     * 与 Dart 的差异：Dart 的入参 `player.position` 只用于末尾的 `met.reAnchor(player.position)`
     * （重置基于 Stopwatch 的时钟锚点）；原生 Metronome 没有该入口，它按 `positionSec()` 轮询 +
     * 偏差超阈值时硬重同步来自行校正，因此这里不需要播放位置入参。
     */
    fun reAnchor() {
        val met = app.metronome
        if (!met.enabled) return
        val current = app.queue.current ?: return
        val target = app.library.targetBpm
        val song = songFor(current.filePath)
        val beats = beatsFor(song)
        met.updateBpm(target, beats)
        met.setPhaseOffset(phaseNudgeSeconds(target))
        if (beats.isNullOrEmpty()) {
            met.setPhase(song?.beatOffset)
        }
    }

    /** 播放 / 暂停切换（对应 Dart `_toggle`）。 */
    fun toggle() {
        if (app.queue.current == null) return
        if (app.player.isPlaying) {
            app.player.pause()
            app.metronome.stop()
        } else {
            // 对应 Dart：先 `_reAnchor` 再 play；真正把节拍器重新开起来的是
            // `isPlaying` 变化触发的 `onPlaybackStart`（与 Dart 的 playerStateStream 一致）。
            reAnchor()
            app.player.play()
        }
    }

    /** 下一首（对应 Dart `_next`）。 */
    fun next() {
        app.queue.next()
        loadCurrent()
    }

    /** 上一首（对应 Dart `_prev`）。 */
    fun previous() {
        app.queue.previous()
        loadCurrent()
    }

    /**
     * 载入当前曲并播放；若载入失败（坏文件 / 无法读取）自动跳到下一首，
     * 最多连续跳过 [kMaxFailures] 次（对应 Dart `_loadCurrent`，第 607–631 行）。
     */
    fun loadCurrent() {
        val queue = app.queue
        var cur = queue.current ?: return
        // 切歌时清空打拍校准与标尺标记（对应 Web 切换曲目时清空 tapMarks）。
        clearTapCalibration()
        var attempts = 0
        while (attempts < kMaxFailures) {
            if (tryPlay(cur)) {
                reAnchor()
                return
            }
            attempts++
            // 载入失败：按循环 / 随机规则前进到下一首（满 1 首队列时 onEnded 会原地）。
            queue.next()
            if (queue.current == cur) break // 只有一首且载入失败：不无限循环
            cur = queue.current ?: break
        }
    }

    /**
     * 载入并播放一首，返回是否成功（对应 Dart `AudioPlayerService.tryPlay`）。
     *
     * 原生 `PlayerController.load` 不抛异常、失败走 `onError` 回调，因此这里临时挂一个回调，
     * 捕获「文件不存在 / 打开失败 / ExoPlayer 同步抛错」这一类失败（正是 Dart tryPlay 会返回
     * false 的场景），随后立刻把回调还原，不占住上层（例如主控将来接的提示逻辑）。
     */
    private fun tryPlay(song: Song): Boolean {
        app.player.setSpeed(PlayerController.computeSpeed(song.originalBpm, app.library.targetBpm))
        var failed = false
        val previous = app.player.onError
        app.player.onError = { msg ->
            failed = true
            previous?.invoke(msg)
        }
        try {
            app.player.load(song, autoPlay = true)
        } finally {
            app.player.onError = previous
        }
        return !failed
    }

    /** 打开播放列表弹层（对应 Dart `_openPlaylist`：空队列只给提示，不弹层）。 */
    fun openPlaylist() {
        if (app.queue.items.isEmpty()) {
            app.snackbar("播放列表为空")
            return
        }
        sheetVisible = true
    }

    /**
     * 当前播放模式的图标与简短文字（对应 Dart `_playModeInfo`）。
     * 注意原生 `QueueStore` 用 `loopMode == ONE` 代替 Dart 的 `q.repeatingOne`。
     */
    fun playModeInfo(): Pair<ImageVector, String> = when {
        app.queue.shuffle -> Icons.Filled.Shuffle to "随机"
        app.queue.loopMode == LoopMode.ONE -> Icons.Filled.RepeatOne to "单曲循环"
        else -> Icons.Filled.Repeat to "列表循环"
    }

    /** 点一下循环切换播放模式：列表循环 → 单曲循环 → 随机 → 列表循环…（对应 Dart `_cyclePlayMode`）。 */
    fun cyclePlayMode() {
        val q = app.queue
        if (q.shuffle) {
            q.setShuffle(false)
            q.setLoopMode(LoopMode.ALL)
        } else if (q.loopMode == LoopMode.ONE) {
            q.setShuffle(true)
        } else {
            q.setLoopMode(LoopMode.ONE)
        }
    }

    /** 复位打拍校准（对应 Dart `_buildTapCalibration` 里「复位」按钮的 setState 块）。 */
    fun resetTaps() {
        tapMediaSec.clear()
        tapMarks.clear()
        tapBpm = null
    }

    /** 切歌时清空打拍数据（对应 Dart `_loadCurrent` 开头）。 */
    private fun clearTapCalibration() {
        tapMediaSec.clear()
        tapMarks.clear()
        tapBpm = null
    }

    /**
     * 打拍一次（对应 Dart `_onTap`，第 871–905 行）。
     * 与上次打拍间隔 >2s 就另起一批，避免跨暂停 / 停顿的旧间隔混入误判 BPM。
     */
    fun onTap() {
        // 打拍音效开关（对应 Web 的音效开关，默认关、持久化）。
        if (tapSoundOn) app.metronome.playTapClick()
        val nowMs = System.currentTimeMillis()
        val media = app.player.positionSec
        if (lastTapAtMs != 0L && nowMs - lastTapAtMs > 2000) tapMediaSec.clear()
        lastTapAtMs = nowMs
        tapMediaSec.add(media)
        // 标尺上显示最近 20 个打拍标记（琥珀色）。
        tapMarks.add(media)
        while (tapMarks.size > 20) tapMarks.removeAt(0)
        if (tapSetFirst && app.metronome.enabled) {
            app.metronome.setPhase(media)
            app.metronome.setPhaseOffset(phaseNudgeSeconds(app.library.targetBpm))
        }
        if (tapMediaSec.size >= 8) {
            val bpm = computeTapBpm(tapMediaSec)
            if (bpm != null) tapBpm = bpm
            // 算完重置本批，以便下一次连续 8 次重算（对齐 Web 版 TapBpm）。
            tapMediaSec.clear()
            lastTapAtMs = 0L
        }
    }

    /**
     * 把测出的 BPM 应用到节拍器（对应 Dart 「应用(n/8)」按钮）。
     *
     * 有意差异：Dart 在这里还调了 `met.reAnchor(player.position)`，而 `_reAnchor` 会用
     * `current.targetBpm` 覆盖刚设置的 BPM（相当于「应用」被立刻撤销）；原生侧只设置
     * BPM 与相位偏移，保留用户测得的值（详见报告）。
     */
    fun applyTapBpm(bpm: Double) {
        val met = app.metronome
        // Dart `met.setBpm(bpm)`：只改 BPM、不动拍点表，所以把当前拍点表原样传回。
        val song = app.queue.current?.let { songFor(it.filePath) }
        met.updateBpm(bpm, beatsFor(song))
        if (met.enabled) {
            met.setPhaseOffset(phaseNudgeSeconds(bpm))
        }
    }

    /** 偏差归零（对应 Dart 归零按钮）。 */
    fun resetPhaseNudge() {
        val target = app.library.targetBpm
        phaseNudgePct = 0.0
        savePref(kPrefPhaseNudge, 0.0)
        if (app.metronome.enabled) {
            app.metronome.setPhaseOffset(phaseNudgeSeconds(target))
        }
    }

    /** 偏差滑杆变化（对应 Dart 偏差 Slider 的 onChanged）。 */
    fun setPhaseNudge(percent: Double) {
        phaseNudgePct = percent
        savePref(kPrefPhaseNudge, percent)
        if (app.metronome.enabled) {
            app.metronome.setPhaseOffset(phaseNudgeSeconds(app.library.targetBpm))
        }
    }

    /** 切换节拍模式（对应 Dart ChoiceChip 的 onSelected）。 */
    fun setBeatMode(mode: BeatMode) {
        activeBeatMode = mode
        savePref(kPrefBeatMode, mode.key)
        if (app.metronome.enabled) reAnchor()
    }

    /** 打开节拍器开关（对应 Dart 开关的 true 分支）。 */
    fun enableMetronome() {
        startMetronome()
        reAnchor()
    }
}

/**
 * 拍点标尺（对应 Dart `_buildBeatRuler`，第 633–647 行）。
 * 无拍点信息时与 Dart 一样只显示一行灰字（原生 `BeatRuler` 自身也有等价空状态）。
 */
@Composable
private fun PlayerBeatRuler(
    beats: List<Double>,
    positionSec: Double,
    durationSec: Double,
    onSeek: (Double) -> Unit,
) {
    if (beats.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "（该曲无拍点信息，标尺暂不可用）",
                color = kGrey,
                fontSize = 12.sp,
            )
        }
        return
    }
    BeatRuler(
        beatTimes = beats,
        durationSec = durationSec,
        positionSec = positionSec,
        modifier = Modifier.fillMaxWidth(),
        onSeek = onSeek,
    )
}

/**
 * 底部固定控制条（对应 Dart `_buildPlaybackBar`，第 406–511 行）：
 * 进度滑杆 + 时间 + 播放模式按钮 + 上一首 / 播放暂停 / 下一首 + 播放列表入口。
 */
@Composable
private fun PlayerPlaybackBar(app: AppState, state: PlayerPageState) {
    val colors = MaterialTheme.colorScheme
    val position = app.player.positionSec
    val duration = app.player.durationSec
    val playing = app.player.isPlaying

    // Dart 外层是 Material(color: surfaceContainerHighest) + SafeArea(top: false)。
    Surface(color = colors.surfaceContainerHighest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 4.dp),
        ) {
            // 进度滑杆 + 时间（小字号、等宽数字）。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = fmtTime(position),
                    fontSize = 10.sp,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                )
                Slider(
                    value = if (duration > 0.0) (position / duration).coerceIn(0.0, 1.0).toFloat() else 0f,
                    onValueChange = { v ->
                        if (duration > 0.0) app.player.seekTo(v.toDouble() * duration)
                    },
                    modifier = Modifier.weight(1f),
                    // Dart 用 SliderTheme 把已播放部分改成主题主色（默认 M3 在此背景上几乎不可见）。
                    colors = SliderDefaults.colors(
                        thumbColor = colors.primary,
                        activeTrackColor = colors.primary,
                        inactiveTrackColor = colors.outlineVariant,
                        activeTickColor = colors.primary,
                        inactiveTickColor = colors.outlineVariant,
                    ),
                )
                Text(
                    text = fmtTime(duration),
                    fontSize = 10.sp,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp),
                    style = TextStyle(fontFeatureSettings = "tnum"),
                )
            }

            // 播放控制一行：左下 = 播放模式（点按循环切换），中间 = 传输控制，右下 = 播放列表。
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 左下：播放模式按钮。Dart 是 Tooltip + IconButton（active 时用主题色）。
                val (modeIcon, modeLabel) = state.playModeInfo()
                val modeActive = app.queue.shuffle || app.queue.loopMode == LoopMode.ONE
                IconButton(
                    onClick = { state.cyclePlayMode() },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = modeIcon,
                        contentDescription = "播放模式（点按切换）：$modeLabel",
                        modifier = Modifier.size(26.dp),
                        tint = if (modeActive) colors.primary else LocalContentColor.current,
                    )
                }

                // 中间：上一首 / 播放暂停 / 下一首。
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { state.previous() },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipPrevious,
                            contentDescription = "上一首",
                            modifier = Modifier.size(36.dp),
                        )
                    }
                    IconButton(
                        onClick = { state.toggle() },
                        modifier = Modifier.size(64.dp),
                    ) {
                        Icon(
                            imageVector = if (playing) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
                            contentDescription = if (playing) "暂停" else "播放",
                            modifier = Modifier.size(56.dp),
                        )
                    }
                    IconButton(
                        onClick = { state.next() },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = "下一首",
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }

                // 右下：播放列表入口。
                IconButton(
                    onClick = { state.openPlaylist() },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.QueueMusic,
                        contentDescription = "播放列表",
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

/**
 * 节拍模式 + 偏差微调 + 打拍校准（对应 Dart `_buildBeatControls`，第 650–734 行）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerBeatControls(app: AppState, state: PlayerPageState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Text(text = "节拍模式", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            // Dart 用 Wrap(spacing: 8, runSpacing: 8)；方案只有两个（固定拍子 / 跟随起音），
            // 一行放得下，故用 Row + spacedBy 等价呈现。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (m in BeatMode.all) {
                    val selected = state.activeBeatMode == m
                    // Dart ChoiceChip 默认选中态带对勾（showCheckmark 默认 true）。
                    val check: (@Composable () -> Unit)? = if (selected) {
                        { Icon(imageVector = Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    }
                    FilterChip(
                        selected = selected,
                        onClick = { state.setBeatMode(m) },
                        label = { Text(m.label) },
                        leadingIcon = check,
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            // 偏差滑杆：Dart `Slider(min: -50, max: 50, divisions: 20)` —— 步长 5%。
            // Compose 侧不用 steps 参数（本工程 material3 版本未验证该参数），改成在回调里吸附。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "偏差", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(8.dp))
                Slider(
                    value = state.phaseNudgePct.toFloat(),
                    onValueChange = { v ->
                        val snapped = (v / 5.0).roundToInt() * 5.0
                        state.setPhaseNudge(snapped.coerceIn(-50.0, 50.0))
                    },
                    modifier = Modifier.weight(1f),
                    valueRange = -50f..50f,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "${state.phaseNudgePct.roundToInt()}%")
                Spacer(modifier = Modifier.width(4.dp))
                // 归零按钮（对齐 Web：始终显示，非零时才可点，为零时灰色禁用）。
                IconButton(
                    onClick = { state.resetPhaseNudge() },
                    enabled = state.phaseNudgePct != 0.0,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Replay,
                        contentDescription = "把偏差归零",
                        tint = if (state.phaseNudgePct == 0.0) {
                            MaterialTheme.colorScheme.outline
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            PlayerTapCalibration(app = app, state = state)
        }
    }
}

/**
 * 打拍校准区（对应 Dart `_buildTapCalibration`，第 736–837 行）：
 * 固定宽度 BPM 数字框 + 「应用(n/8)」+ 复位 / 设首拍 / 音效 / 点按打拍。
 */
@Composable
private fun PlayerTapCalibration(app: AppState, state: PlayerPageState) {
    val bpm = state.tapBpm
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "打拍校准", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "按节拍点 8 下测出 BPM", color = kGrey, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))

        // 第一行：BPM + 数字框（固定宽，容纳三位数 + 两位小数）… 靠右 应用(n/8)。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "BPM", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(width = 108.dp, height = 44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(scheme.surfaceContainerHighest)
                    .border(width = 1.2.dp, color = scheme.outline, shape = RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (bpm != null) app.settings.formatBpm(bpm) else "—",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.W700,
                    color = if (bpm != null) scheme.primary else scheme.outline,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            // 应用(n/8)：n 为已点次数，需先算出 BPM 才能应用（Dart 用 FilledButton）。
            Button(
                onClick = { bpm?.let { state.applyTapBpm(it) } },
                enabled = bpm != null,
            ) {
                Text("应用(${state.tapMediaSec.size}/8)")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // 第二行：复位（左）… 靠右 设首拍 → 音效 → 点按打拍。
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { state.resetTaps() }) {
                Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("复位")
            }
            Spacer(modifier = Modifier.weight(1f))
            PlayerToggleButton(
                label = "设首拍",
                active = state.tapSetFirst,
            ) {
                state.tapSetFirst = !state.tapSetFirst
            }
            Spacer(modifier = Modifier.width(8.dp))
            PlayerToggleButton(
                label = "音效",
                active = state.tapSoundOn,
            ) {
                state.tapSoundOn = !state.tapSoundOn
                state.savePref(kPrefTapSound, state.tapSoundOn)
            }
            Spacer(modifier = Modifier.width(8.dp))
            PlayerTapPulseButton(onTap = { state.onTap() })
        }
    }
}

/**
 * 变色切换按钮（对应 Dart `_toggleButton`，第 840–869 行）：
 * 开启时整块变主题色（无对勾），关闭时是浅灰底。
 */
@Composable
private fun PlayerToggleButton(
    label: String,
    active: Boolean,
    onTap: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) scheme.primary else scheme.surfaceContainerHighest)
            .clickable { onTap() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = if (active) scheme.onPrimary else scheme.onSurfaceVariant,
            fontWeight = FontWeight.W600,
        )
    }
}

/**
 * 点按打拍按钮（对应 Dart `_TapPulseButton`，第 1273–1341 行）：
 * 每点一下闪一次光晕 + 缩放回弹，让用户能明确感知到每一次打拍都被记录了。
 *
 * Dart 用 AnimationController + AnimatedBuilder；Compose 侧用 `withFrameNanos` 逐帧推进
 * 0→1 的进度（240ms），公式与 Dart 逐行一致。
 */
@Composable
private fun PlayerTapPulseButton(onTap: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var progress by remember { mutableFloatStateOf(0f) }
    var pulseKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(pulseKey) {
        if (pulseKey == 0) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val t = ((now - start) / 1_000_000L).toFloat() / kTapPulseMillis
            if (t >= 1f) {
                // 停在动画终点（t=1）：光晕熄灭、缩放回到 1.0，与 Dart AnimationController
                // 播完后停在 value = 1 的观感一致。
                progress = 1f
                break
            }
            progress = t
        }
    }

    val t = progress
    // 中间(t=0.5)最亮，两端熄灭 → 每点一下闪一次光晕。
    val flash = abs(t - 0.5f) * 2f
    val glow = ((1f - flash).coerceIn(0f, 1f)) * 0.5f
    // 按下前 1/4 略微缩小，之后回弹放大，形成实感。
    val scale = if (t < 0.25f) 0.94f + 0.24f * (t / 0.25f) else 1.18f - 0.18f * ((t - 0.25f) / 0.75f)

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // Dart 用 boxShadow(blur 16+20*glow, spread 2+6*glow, primary α=glow)；
            // Compose 的 shadow 只有单一半径参数，这里取「模糊 + 扩散」的中值近似，
            // 光晕颜色用主题主色的 alpha 表达同一观感。
            .then(
                if (glow > 0f) {
                    Modifier.shadow(
                        elevation = (2f + 6f * glow + (16f + 20f * glow) / 2f).dp,
                        shape = CircleShape,
                        ambientColor = scheme.primary.copy(alpha = glow),
                        spotColor = scheme.primary.copy(alpha = glow),
                    )
                } else {
                    Modifier
                },
            )
            .clip(CircleShape)
            .background(scheme.primaryContainer)
            .clickable {
                onTap()
                pulseKey += 1
            }
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.TouchApp,
            contentDescription = "点按打拍",
            modifier = Modifier.size(28.dp),
            tint = scheme.onPrimaryContainer,
        )
    }
}

/**
 * 节拍器面板（对应 Dart `_buildMetronomeControls`，第 907–1033 行）：
 * 开关 + 音色选择 + 音乐 / 拍子音量。
 */
@Composable
private fun PlayerMetronomeControls(app: AppState, state: PlayerPageState) {
    var soundMenuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val soundName = kMetronomeSounds.getOrElse(state.metSoundIndex) { kMetronomeSounds.first() }.name

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("节拍器")
                Switch(
                    checked = state.metEnabled,
                    onCheckedChange = { v ->
                        if (v) {
                            // 记录用户意图（默认开启，对齐 Web）并落盘，再启用节拍器并锚定。
                            // 顺序很关键：若在启用前锚定，enabled 仍为 false 会直接返回，
                            // 导致节拍器被启用却没有拍点，彻底无声。
                            state.metEnabled = true
                            state.savePref(kPrefMetOn, true)
                            state.enableMetronome()
                        } else {
                            state.metEnabled = false
                            state.savePref(kPrefMetOn, false)
                            app.metronome.stop()
                        }
                    },
                )
                Spacer(modifier = Modifier.weight(1f))

                // 节拍器音色选择（Dart PopupMenuButton：图标 + 音色名 + 下拉箭头，选中项带单选标记）。
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { soundMenuOpen = true }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Audiotrack,
                            contentDescription = "选择节拍器音效",
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(soundName)
                        Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = soundMenuOpen,
                        onDismissRequest = { soundMenuOpen = false },
                    ) {
                        for (i in kMetronomeSounds.indices) {
                            val selected = i == state.metSoundIndex
                            DropdownMenuItem(
                                text = { Text(kMetronomeSounds[i].name) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (selected) {
                                            Icons.Filled.RadioButtonChecked
                                        } else {
                                            // Dart 用的是 `Icons.radio_button_off`；Compose 图标集里
                                            // 同一字形叫 RadioButtonUnchecked（无 RadioButtonOff）。
                                            Icons.Filled.RadioButtonUnchecked
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = if (selected) colors.primary else LocalContentColor.current,
                                    )
                                },
                                onClick = {
                                    soundMenuOpen = false
                                    state.metSoundIndex = i
                                    state.savePref(kPrefMetSound, i)
                                    app.metronome.setSound(i)
                                },
                            )
                        }
                    }
                }
            }

            // 音乐 + 拍子音量（对齐 Web：两个滑杆同一行各占一半）。
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "🔊 音乐", fontSize = 12.sp)
                    Slider(
                        value = state.musicVolume,
                        onValueChange = { v ->
                            state.musicVolume = v
                            // PlayerController.setVolume 自带 400ms 落盘去抖（对应 Dart 的
                            // onChangeEnd 才写盘，避免拖动期间大量并发异步写）。
                            app.player.setVolume(v)
                        },
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "🥁 拍子", fontSize = 12.sp)
                    Slider(
                        value = state.metVolume,
                        onValueChange = { v ->
                            state.metVolume = v
                            // Metronome.setVolume 立即落盘（runbpm.metVolume），与 Dart 一致。
                            app.metronome.setVolume(v)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 歌曲封面（对应 Dart `Image.file(File(artwork), fit: cover, errorBuilder: _ArtworkPlaceholder)`）：
 * 96×96、8dp 圆角，无封面或解码失败时回退成占位图标。
 */
@Composable
private fun PlayerArtwork(path: String?) {
    val shape = RoundedCornerShape(8.dp)
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    if (path != null) {
        LaunchedEffect(path) {
            bitmap = withContext(Dispatchers.IO) { decodeArtwork(path) }
        }
    }
    val loaded = bitmap
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(shape),
    ) {
        if (loaded == null) {
            PlayerArtworkPlaceholder()
        } else {
            Image(
                bitmap = loaded,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** 无封面时的占位图标（对应 Dart `_ArtworkPlaceholder`，第 1037–1049 行）。 */
@Composable
private fun PlayerArtworkPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = kGrey,
        )
    }
}

/** 解码封面（不引入图片库，按需采样到 [kArtworkPx]；失败返回 null → 走占位图标）。 */
private fun decodeArtwork(path: String): ImageBitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        null
    } else {
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= kArtworkPx && bounds.outHeight / (sample * 2) >= kArtworkPx) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded: Bitmap? = BitmapFactory.decodeFile(path, options)
        decoded?.asImageBitmap()
    }
} catch (_: OutOfMemoryError) {
    null
} catch (_: Exception) {
    null
}

/**
 * 秒 → `mm:ss`（对应 Dart `_fmt(Duration)`）。
 * Dart 用 `d.inMinutes` 与 `d.inSeconds % 60` 左补零，正数时长下与这里等价。
 */
private fun fmtTime(seconds: Double): String {
    val total = if (seconds.isFinite() && seconds > 0.0) seconds.toLong() else 0L
    val m = (total / 60L).toString().padStart(2, '0')
    val s = (total % 60L).toString().padStart(2, '0')
    return "$m:$s"
}
