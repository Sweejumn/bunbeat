package com.bunbeat.nativeapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.bunbeat.nativeapp.audio.AnalysisCache
import com.bunbeat.nativeapp.core.Prefs
import com.bunbeat.nativeapp.player.Metronome
import com.bunbeat.nativeapp.player.PlayerController
import com.bunbeat.nativeapp.store.LibraryStore
import com.bunbeat.nativeapp.store.QueueStore
import com.bunbeat.nativeapp.store.SettingsStore
import com.bunbeat.nativeapp.update.UpdateController
import com.bunbeat.nativeapp.ui.AboutPage
import com.bunbeat.nativeapp.ui.ArchivePage
import com.bunbeat.nativeapp.ui.HomePage
import com.bunbeat.nativeapp.ui.LibraryPage
import com.bunbeat.nativeapp.ui.PlayerPage
import com.bunbeat.nativeapp.ui.RecommendPage
import com.bunbeat.nativeapp.ui.SettingsPage
import com.bunbeat.nativeapp.ui.theme.BunbeatTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 顶层页面。
 *
 * v0.3 起导航结构对齐 Shizuku：**没有底部 Tab**，首页是一屏卡片流，
 * 其余页面都是从首页「推入」的子页（各自带返回箭头的 M3 顶栏）。
 */
private enum class Screen { HOME, LIBRARY, RECOMMEND, PLAYER, ARCHIVE, SETTINGS, ABOUT }

class MainActivity : ComponentActivity() {

    private lateinit var app: AppState
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var screen by mutableStateOf(Screen.HOME)

    /**
     * 页面历史（就是 Shizuku 那种「子页返回上一层」的简单栈）。
     * 必须用栈而不是只记一层：首页 → 曲库 → 设置 → 关于 之后要能一层层退回去。
     */
    private val backStack = mutableStateListOf<Screen>()

    private fun go(target: Screen) {
        if (screen == target) return
        backStack.add(screen)
        screen = target
    }

    private fun back() {
        val prev = backStack.removeLastOrNull() ?: Screen.HOME
        screen = prev
    }

    /** SAF 目录选择器：`ACTION_OPEN_DOCUMENT_TREE` 对应 Dart 侧 file_picker 的目录选择。 */
    private val folderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            // 持久化读权限，重启后仍能扫描同一目录。
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // 部分设备/目录不允许持久化，忽略即可，本次会话内仍可读。
            }
            scope.launch { app.library.openFolder(uri) }
        }

    /**
     * 运行时媒体权限申请（Dart 侧由 permission_handler / on_audio_query 内部处理）。
     * API 33+ 用 READ_MEDIA_AUDIO，32 及以下用 READ_EXTERNAL_STORAGE。
     */
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.any { it }) {
                scope.launch { app.library.refresh() }
            } else {
                app.snackbar("未授予媒体读取权限，无法扫描歌曲")
            }
        }

    private fun ensureMediaPermission() {
        val needed = if (android.os.Build.VERSION.SDK_INT >= 33) {
            arrayOf(android.Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = needed.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = Prefs(this)
        val cache = AnalysisCache(this)
        val library = LibraryStore(this, prefs, cache, scope)
        val queue = QueueStore(prefs, scope)
        val settings = SettingsStore(prefs)
        val player = PlayerController(this, prefs, cache)
        val metronome = Metronome(this)
        val update = UpdateController(this, scope)

        app = AppState(
            context = this,
            activity = this,
            prefs = prefs,
            cache = cache,
            library = library,
            queue = queue,
            settings = settings,
            player = player,
            metronome = metronome,
            update = update,
            scope = scope,
        )
        app.folderPicker = { folderLauncher.launch(null) }
        // 更新流程的提示统一走底部 Snackbar（与页面提示同一个通道）。
        app.update.onMessage = { app.snackbar(it) }
        app.nav = NavActions(
            openLibrary = { go(Screen.LIBRARY) },
            openRecommend = { go(Screen.RECOMMEND) },
            openPlayer = { go(Screen.PLAYER) },
            openArchive = { go(Screen.ARCHIVE) },
            openSettings = { go(Screen.SETTINGS) },
            openAbout = { go(Screen.ABOUT) },
            back = { back() },
        )

        // 播完后的行为完全交给 QueueStore.onEnded()：
        // 单曲循环 → 重播当前曲；列表循环/随机 → 下一首；顺序播完 → 停下（返回 null）。
        player.onCompleted = {
            val next = queue.onEnded()
            if (next != null) {
                player.load(next, autoPlay = true)
            }
        }

        setContent {
            BunbeatTheme(
                seed = app.settings.seed,
                darkMode = settings.darkModeOverride(),
                // Android 12+ 默认跟随壁纸取色（Material You），可在设置里关掉。
                useSystemColor = app.settings.useSystemColor,
            ) {
                CompositionLocalProvider(LocalApp provides app) {
                    AppRoot(app)
                }
            }
        }

        // 首帧之后再恢复状态 + 延迟做一次静默更新自检（对应 Dart `_StartupGate`）。
        scope.launch {
            app.restoreOnStart()
            delay(8_000)
            app.update.check(manual = false)
        }

        // 媒体读取权限：没有它 MediaStore 扫描恒为空。
        ensureMediaPermission()
    }

    override fun onDestroy() {
        app.player.release()
        app.metronome.release()
        scope.cancel()
        super.onDestroy()
    }

    @androidx.compose.runtime.Composable
    private fun AppRoot(app: AppState) {
        val hostState = remember { SnackbarHostState() }

        // 底部提示：页面调用 app.snackbar(...) 后在这里统一弹出。
        LaunchedEffect(app.snackbarMessage) {
            val msg = app.snackbarMessage
            if (msg != null) {
                app.consumeSnackbar()
                hostState.showSnackbar(msg)
            }
        }

        // 切歌后把节拍器对齐到新歌的 BPM / 拍点。
        val currentId = app.player.currentSong?.id
        LaunchedEffect(currentId) {
            val song = app.player.currentSong ?: return@LaunchedEffect
            app.metronome.updateBpm(song.originalBpm ?: app.library.targetBpm, song.beatTimes)
        }

        BackHandler(enabled = screen != Screen.HOME) { back() }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(hostState) },
        ) { _ ->
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                val back = { back() }
                when (screen) {
                    Screen.HOME -> HomePage(app)
                    Screen.LIBRARY -> LibraryPage(app, onBack = back)
                    Screen.RECOMMEND -> RecommendPage(app, onBack = back)
                    Screen.PLAYER -> PlayerPage(app, onBack = back)
                    Screen.ARCHIVE -> ArchivePage(app, onBack = back)
                    Screen.SETTINGS -> SettingsPage(app, onBack = back)
                    Screen.ABOUT -> AboutPage(app, onBack = back)
                }
            }
        }

        // 「发现新版本」弹窗（用户手动检查时失败/无新版走 Snackbar，由 UpdateController 负责）。
        val pending = app.update.pending
        if (pending != null) {
            AlertDialog(
                onDismissRequest = { app.update.dismiss() },
                title = { Text("发现新版本 ${pending.version}") },
                text = {
                    Text(
                        if (pending.notes.isBlank()) "建议更新到最新版本。" else pending.notes,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        app.update.dismiss()
                        app.update.downloadAndInstall()
                    }) { Text("立即更新") }
                },
                dismissButton = {
                    TextButton(onClick = { app.update.dismiss() }) { Text("稍后") }
                },
            )
        }
    }
}

/** 把主题模式设置转成 `BunbeatTheme` 需要的三态（null = 跟随系统）。 */
private fun SettingsStore.darkModeOverride(): Boolean? = when (themeMode) {
    com.bunbeat.nativeapp.store.ThemeModeSetting.SYSTEM -> null
    com.bunbeat.nativeapp.store.ThemeModeSetting.LIGHT -> false
    com.bunbeat.nativeapp.store.ThemeModeSetting.DARK -> true
}
