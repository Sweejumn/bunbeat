package com.bunbeat.nativeapp

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.bunbeat.nativeapp.audio.AnalysisCache
import com.bunbeat.nativeapp.core.Prefs
import com.bunbeat.nativeapp.player.Metronome
import com.bunbeat.nativeapp.player.PlayerController
import com.bunbeat.nativeapp.store.LibraryStore
import com.bunbeat.nativeapp.store.QueueStore
import com.bunbeat.nativeapp.store.SettingsStore
import com.bunbeat.nativeapp.update.UpdateController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 页面跳转动作：由 MainActivity 注入，页面一律通过 [AppState.nav] 跳转。 */
class NavActions(
    val openArchive: () -> Unit,
    val openSettings: () -> Unit,
    val openAbout: () -> Unit,
    val back: () -> Unit,
)

/**
 * 全局应用状态（对应 Flutter 版的 MultiProvider：Library/Queue/Theme/BpmDisplay/Player/Metronome）。
 *
 * 原生侧直接用一个持有者把各 store 组合起来，通过 [LocalApp] 提供给所有页面，
 * 避免多层 Provider 嵌套；页面只依赖本类暴露的 store，不直接接触 Activity。
 */
class AppState(
    val context: Context,
    val activity: ComponentActivity?,
    val prefs: Prefs,
    val cache: AnalysisCache,
    val library: LibraryStore,
    val queue: QueueStore,
    val settings: SettingsStore,
    val player: PlayerController,
    val metronome: Metronome,
    val update: UpdateController,
    val scope: CoroutineScope,
) {
    /** 由 MainActivity 在创建后立即注入（页面在首帧前不会用到）。 */
    lateinit var nav: NavActions

    /** 底部提示文案；MainActivity 消费后清空。 */
    var snackbarMessage: String? by mutableStateOf(null)
        private set

    /** SAF 目录选择器（由 MainActivity 注册），页面通过 [requestFolderPick] 触发。 */
    var folderPicker: (() -> Unit)? = null

    fun snackbar(msg: String) {
        snackbarMessage = msg
    }

    fun consumeSnackbar() {
        snackbarMessage = null
    }

    /** 触发系统文件夹选择器；选择结果由 MainActivity 交给 [LibraryStore.openFolder]。 */
    fun requestFolderPick() {
        val picker = folderPicker
        if (picker == null) {
            snackbar("当前环境不支持文件夹选择")
            return
        }
        picker()
    }

    /** 启动时恢复上次状态（对应 Dart `_StartupGate` 的首帧回调）。 */
    fun restoreOnStart() {
        scope.launch {
            settings.load()
            library.loadTargetBpm()
            // 先读归档 id，再恢复文件夹：让扫描时能正确区分归档/未归档歌曲。
            library.loadArchived()
            library.restoreLastFolder()
            // 恢复上次的播放队列 + 当前曲 + 循环/随机模式（恢复到就绪不自动播放）。
            queue.loadFromPrefs()
            queue.current?.let { cur ->
                player.loadPaused(cur)
                metronome.updateBpm(cur.originalBpm ?: library.targetBpm, cur.beatTimes)
            }
        }
    }
}

/** 全局状态入口（页面用 `LocalApp.current` 或参数 `app: AppState` 取用）。 */
val LocalApp = staticCompositionLocalOf<AppState> { error("AppState 未提供") }
