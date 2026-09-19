package com.bunbeat.nativeapp.ui

// 对应 Dart `ui/library_page.dart`。
//
// 曲库页结构（自下而上与 Dart 一一对应）：
//   顶部工具栏（Bunbeat · 曲库 + 归档 / 使用说明 / 设置）
//   → 操作条（添加音乐 / 音源 + 排序 + 搜索）
//   → 搜索框（可折叠）
//   → 扫描 / 分析进度条
//   → 列表（点击选中、长按呼出操作菜单）或空状态
//
// 与 Dart 的承载方式差异只有两处：
//   1. Dart 的页面自带 `Scaffold + AppBar`；原生侧曲库是 HomePage 底部 Tab 的一页
//      （外层已经有 Scaffold 与 Snackbar），所以这里只画一条与 AppBar 同高同色的工具栏行。
//   2. Dart 的底部面板（showModalBottomSheet）在原生侧用 material3 的 `ModalBottomSheet`，
//      行为一致（可下拉关闭、背景可点关闭）。

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoubleArrow
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bunbeat.nativeapp.AppState
import com.bunbeat.nativeapp.audio.AudioReader
import com.bunbeat.nativeapp.model.BpmStatus
import com.bunbeat.nativeapp.model.Song
import com.bunbeat.nativeapp.store.LibraryStore
import com.bunbeat.nativeapp.ui.theme.isDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** 一个预设音源：常见音乐 App 的下载目录（对应 Dart `_MusicSource`）。 */
private class MusicSource(val name: String, val path: String)

/** 常见音源的默认下载目录（可再通过「自定义文件夹」手动选其它目录，对应 Dart `_kSources`）。 */
private val kMusicSources: List<MusicSource> = listOf(
    MusicSource("网易云音乐", "/storage/emulated/0/Download/netease/cloudmusic/Music"),
    MusicSource("QQ音乐", "/storage/emulated/0/Music/qqmusic/song"),
    MusicSource("酷狗音乐", "/storage/emulated/0/Download/kgmusic/download"),
)

/** 曲库歌曲排序方式（对应 Dart `_SortMode` 与其 label 扩展）。 */
private enum class LibrarySortMode(val label: String) {
    SCAN("默认顺序"),
    TITLE_AZ("标题 A→Z"),
    TITLE_ZA("标题 Z→A"),
    BPM_ASC("BPM 从低到高"),
    BPM_DESC("BPM 从高到低"),
    DURATION("时长从长到短"),
}

/** 长按菜单里的操作（对应 Dart `_showActions` 里 pop 出来的那几个字符串）。 */
private enum class LibraryAction { PLAYLIST, ARCHIVE, RETRY, X2, MANUAL }

/**
 * 一次长按请求：长按的那一首 + 本次操作的目标集合。
 * 对应 Dart `_showActions` 里的 `song` / `targets` / `isMulti`（在弹出面板时就算好，
 * 面板里的按钮文案要用同一份快照）。
 */
private class LibraryActionRequest(
    val song: Song,
    val targets: List<Song>,
    val isMulti: Boolean,
)

/** 长按呼出菜单的触发时长（对应 Dart `_SongTile._longPressDuration`，比系统默认更短）。 */
private const val kQuickLongPressMillis = 250L

/**
 * 预设音源导入时写入的「上次文件夹」键。
 *
 * 等于 `LibraryStore` 的私有常量 `kLastFolder`：原生侧曲库缺少「直接用一份扫描结果
 * 载入」的公开入口，只能先写这个键再调 `LibraryStore.restoreLastFolder()` 绕开，
 * 详见报告里的「缺失接口」一节。
 */
private const val kLastFolderPrefKey = "last_folder"

/** 封面缩略图的目标边长（像素）：48dp 在 3x 屏上是 144px，避免整张大图进内存。 */
private const val kArtworkThumbPx = 144

/** 与 Dart `Colors.grey` / `Colors.green.shade700` 等一一对应的 Material 2 调色板原值。 */
private val kGrey = Color(0xFF9E9E9E)
private val kGreenAccent = Color(0xFF69F0AE)
private val kGreen700 = Color(0xFF388E3C)
private val kRedAccent = Color(0xFFFF5252)
private val kRed700 = Color(0xFFD32F2F)
private val kOrangeAccent = Color(0xFFFFAB40)
private val kOrange800 = Color(0xFFEF6C00)

/**
 * 曲库页（对应 Dart `LibraryPage` + `_LibraryPageState`）。
 *
 * 页内状态与 Dart 的 StatefulWidget 状态一一对应：排序方式、搜索关键字与开关、
 * 点击选中的歌曲 id 集合、以及三个弹窗（音源面板 / 长按菜单 / 手动改 BPM）。
 */
@Composable
fun LibraryPage(app: AppState) {
    val library = app.library

    // 排序方式：Dart 用 enum，这里存序号以便 rememberSaveable 跨配置变更保留。
    var sortIndex by rememberSaveable { mutableIntStateOf(LibrarySortMode.SCAN.ordinal) }
    val sortMode = LibrarySortMode.entries.getOrElse(sortIndex) { LibrarySortMode.SCAN }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    // 单击选中的歌曲 id：插入序 = 点击先后（对应 Dart 的 LinkedHashSet `_selected`），
    // 供「加入播放列表」按点击顺序批量处理。
    val selected = remember { mutableStateListOf<String>() }

    var showSourceSheet by remember { mutableStateOf(false) }
    var actionRequest by remember { mutableStateOf<LibraryActionRequest?>(null) }
    var manualBpmSong by remember { mutableStateOf<Song?>(null) }
    var showHelp by remember { mutableStateOf(false) }

    // 需要在协程里做的事（预设音源导入）走 AppState 的作用域。
    val scope = rememberCoroutineScope()

    /** 顶部「添加音乐」：没选过文件夹直接弹系统选择器；选过则弹音源面板（对应 Dart `_addMusic`）。 */
    fun addMusic() {
        if (library.folderPath == null) {
            app.requestFolderPick()
        } else {
            showSourceSheet = true
        }
    }

    /**
     * 直接按预设音源路径扫描载入（不弹系统文件夹选择器，对应 Dart `_pickSource`）。
     *
     * 缺口绕开：`LibraryStore` 没有 Dart 那样的 `loadFolder(pick)` 公开入口，
     * 这里先把路径写成「上次文件夹」再调 `restoreLastFolder()`（内部会扫描、落地路径、
     * 对齐曲库并排队分析），效果等价；代价是这条路径会被扫描两次。
     */
    fun pickSource(path: String) {
        scope.launch {
            val pick = AudioReader.scanFolder(app.context, path, withSubfolders = true)
            if (pick.audioFiles.isEmpty()) {
                app.snackbar("未在该音源目录找到音乐，可能是路径不存在或还没有下载歌曲")
                return@launch
            }
            app.prefs.putString(kLastFolderPrefKey, pick.path ?: path)
            library.restoreLastFolder()
            app.snackbar("已导入，正在分析 BPM…")
        }
    }

    /** 单选切换（对应 Dart `onToggle`）。 */
    fun toggleSong(id: String) {
        if (!selected.remove(id)) selected.add(id)
    }

    /** 长按：按当前选中情况决定目标集合（对应 Dart `_showActions` 开头的计算）。 */
    fun requestActions(song: Song) {
        val isMulti = selected.isNotEmpty()
        actionRequest = LibraryActionRequest(
            song = song,
            targets = if (isMulti) selectedSongs(library, selected) else listOf(song),
            isMulti = isMulti,
        )
    }

    /**
     * 「加入播放列表」：对当前选中集合切换与播放队列的关系（对应 Dart `_togglePlaylist`）。
     * 全部已在队列 → 全部移出；部分在 → 补入剩余；都不在 → 全部加入。
     * 未选中任何歌时只处理长按的这一首。
     *
     * 注意：Kotlin 的局部函数只能引用**先声明**的局部函数，所以本函数必须写在
     * [handleAction] 之前（Dart 里两个方法的先后顺序不影响调用）。
     */
    fun togglePlaylist(request: LibraryActionRequest) {
        val target = if (selected.isNotEmpty()) selectedSongs(library, selected) else listOf(request.song)
        // 只含已有 BPM 的歌曲（对应 Dart `lib.buildPlaylist`）。
        val playlist = target.filter { it.hasBpm }
        if (playlist.isEmpty()) {
            app.snackbar("这些歌曲还没有 BPM，无法加入播放列表")
            return
        }
        // 对应 Dart `queue.toggleAddRemove(playlist)`：原生侧用已有成员组合出同样的两种分支。
        val presentPaths = app.queue.items.map { it.filePath }.toSet()
        val allPresent = playlist.all { presentPaths.contains(it.filePath) }
        var added = 0
        var removed = 0
        if (allPresent) {
            val removePaths = playlist.map { it.filePath }.toSet()
            app.queue.removeWhere { removePaths.contains(it.filePath) }
            removed = removePaths.size
        } else {
            added = app.queue.append(playlist)
        }
        if (selected.isNotEmpty()) selected.clear()
        app.snackbar(
            if (removed > 0) "已从播放列表移出 $removed 首" else "已把 $added 首加入播放列表",
        )
    }

    /** 面板里选中某项后的处理（对应 Dart `_showActions` 末尾的 switch）。 */
    fun handleAction(action: LibraryAction, request: LibraryActionRequest) {
        actionRequest = null
        when (action) {
            LibraryAction.PLAYLIST -> togglePlaylist(request)
            LibraryAction.ARCHIVE -> {
                for (s in request.targets) library.archive(s.id)
                if (selected.isNotEmpty()) selected.clear()
                app.snackbar(
                    if (request.targets.size == 1) {
                        "已归档，可在曲库右上角归档入口查看"
                    } else {
                        "已归档 ${request.targets.size} 首，可在归档入口查看"
                    },
                )
            }
            LibraryAction.RETRY -> {
                for (s in request.targets) library.analyzeOne(s)
                if (selected.isNotEmpty()) selected.clear()
            }
            LibraryAction.X2 -> {
                for (s in request.targets) {
                    if (s.hasBpm) {
                        library.setManualBpm(s, ((s.originalBpm ?: 0.0) * 2).coerceIn(20.0, 400.0))
                    }
                }
                if (selected.isNotEmpty()) selected.clear()
            }
            LibraryAction.MANUAL -> manualBpmSong = request.song
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LibraryTopBar(
            onArchive = { app.nav.openArchive() },
            onHelp = { showHelp = true },
            onSettings = { app.nav.openSettings() },
        )

        // 顶部操作条：音源按钮（首次进入是「添加音乐」，选过文件夹后显示「音源」）+ 排序 + 搜索。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // material3 没有 FilledButton（那是 Flutter 的组件名），带图标的实心按钮就用 Button。
            Button(
                onClick = { addMusic() },
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (library.folderPath == null) "添加音乐" else "音源")
            }
            Spacer(modifier = Modifier.width(4.dp))
            SortMenuButton(
                sortMode = sortMode,
                onSelect = { sortIndex = it.ordinal },
            )
            IconButton(
                onClick = {
                    searching = !searching
                    if (!searching) query = ""
                },
            ) {
                Icon(
                    imageVector = if (searching) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searching) "关闭搜索" else "搜索",
                )
            }
        }

        if (searching) {
            // 搜索框：对应 Dart 的 TextField（prefixIcon = 放大镜、hintText = 搜索歌名 / 歌手、
            // OutlineInputBorder）。M3 没有 isDense，用 OutlinedTextField 的默认密度代替。
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                runCatching { focusRequester.requestFocus() }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 4.dp)
                    .focusRequester(focusRequester),
                placeholder = { Text("搜索歌名 / 歌手") },
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                },
                singleLine = true,
            )
        }

        // 进度条：Dart 只在分析时显示；原生侧扫描与分析的进度都由同一个 store 暴露，
        // 这里两种都显示（扫描很快，视觉上是「一进页面就有反馈」）。
        if (library.analyzing || library.scanning) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
            )
        }

        LibraryStatusStrip(library)

        LibraryBody(
            app = app,
            sortMode = sortMode,
            query = query,
            selected = selected,
            onToggle = { toggleSong(it) },
            onLongPress = { requestActions(it) },
            modifier = Modifier.weight(1f),
        )
    }

    // —— 弹窗（与 Dart 的 showModalBottomSheet / showDialog 一一对应）——

    if (showSourceSheet) {
        MusicSourceSheet(
            onDismiss = { showSourceSheet = false },
            onPickSource = { path ->
                showSourceSheet = false
                pickSource(path)
            },
            onCustomFolder = {
                showSourceSheet = false
                app.requestFolderPick()
            },
        )
    }

    actionRequest?.let { request ->
        SongActionsSheet(
            app = app,
            request = request,
            onDismiss = { actionRequest = null },
            onAction = { action -> handleAction(action, request) },
        )
    }

    manualBpmSong?.let { song ->
        ManualBpmDialog(
            song = song,
            onDismiss = { manualBpmSong = null },
            onConfirm = { value ->
                manualBpmSong = null
                library.setManualBpm(song, value)
            },
        )
    }

    if (showHelp) {
        HelpDialogContent(section = HelpSection.library, onDismiss = { showHelp = false })
    }
}

/**
 * 顶部工具栏（对应 Dart 曲库页的 `AppBar`：标题 + 归档 / 使用说明 / 设置三个图标按钮）。
 *
 * 高度取 56dp 与 Flutter 的 AppBar 一致（material3 的 TopAppBar 是 64dp）；
 * 图标按钮的 tooltip 在 Compose 侧没有稳定 API，这里只保留 contentDescription（无障碍文案一致）。
 */
@Composable
private fun LibraryTopBar(
    onArchive: () -> Unit,
    onHelp: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Bunbeat · 曲库",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onArchive) {
                Icon(imageVector = Icons.Outlined.Inventory2, contentDescription = "归档")
            }
            IconButton(onClick = onHelp) {
                Icon(imageVector = Icons.Outlined.HelpOutline, contentDescription = "使用说明")
            }
            IconButton(onClick = onSettings) {
                Icon(imageVector = Icons.Outlined.Settings, contentDescription = "设置")
            }
        }
    }
}

/** 排序菜单（对应 Dart 的 `PopupMenuButton<_SortMode>`：图标 + 六个选项，当前项带勾）。 */
@Composable
private fun SortMenuButton(
    sortMode: LibrarySortMode,
    onSelect: (LibrarySortMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(imageVector = Icons.Filled.Sort, contentDescription = "排序")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            for (mode in LibrarySortMode.entries) {
                // 当前排序方式带勾（对应 Dart `PopupMenuButton(initialValue: ...)` 的选中态）。
                val checked: (@Composable () -> Unit)? = if (mode == sortMode) {
                    { Icon(imageVector = Icons.Filled.Check, contentDescription = null) }
                } else {
                    null
                }
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    onClick = {
                        expanded = false
                        onSelect(mode)
                    },
                    leadingIcon = checked,
                )
            }
        }
    }
}

/** 扫描 / 分析状态文案与错误提示（对应 Dart `LibraryService.statusText` / `errors`）。 */
@Composable
private fun LibraryStatusStrip(library: LibraryStore) {
    val status = library.statusText
    if (status == null && library.errors.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
    ) {
        if (status != null) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (library.errors.isNotEmpty()) {
            Text(
                text = library.errors.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 「添加音乐」底部面板：可选预设音源或自定义文件夹（对应 Dart `_showSourceSheet`）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicSourceSheet(
    onDismiss: () -> Unit,
    onPickSource: (String) -> Unit,
    onCustomFolder: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetGesturesEnabled = true,
    ) {
        SheetTile(
            leading = Icons.Filled.Add,
            title = "添加音乐",
            subtitle = "选择一个音源或文件夹，会自动扫描导入",
            subtitleStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            ),
        )
        HorizontalDivider()
        for (source in kMusicSources) {
            SheetTile(
                leading = Icons.Filled.LibraryMusic,
                title = source.name,
                subtitle = source.path,
                onClick = { onPickSource(source.path) },
            )
        }
        HorizontalDivider()
        SheetTile(
            leading = Icons.Filled.FolderOpen,
            title = "自定义文件夹",
            subtitle = "自己挑选一个音乐文件夹",
            onClick = onCustomFolder,
        )
    }
}

/**
 * 长按操作菜单（对应 Dart `_showActions` 弹出的底部面板）。
 *
 * 面板内容：标题行（多选时显示「已选 N 首」）、加入播放列表 / 归档 / 重新检测，
 * 单曲且已有 BPM 时再补 BPM ×2 与手动修改。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongActionsSheet(
    app: AppState,
    request: LibraryActionRequest,
    onDismiss: () -> Unit,
    onAction: (LibraryAction) -> Unit,
) {
    // 「加入播放列表」的项目文案取决于目标歌曲是否都已在队列里（对应 Dart 的 allInQueue）。
    val targetPaths = request.targets.filter { it.hasBpm }.map { it.filePath }
    val queuePaths = app.queue.items.map { it.filePath }.toSet()
    val allInQueue = targetPaths.isNotEmpty() && targetPaths.all { queuePaths.contains(it) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetGesturesEnabled = true,
    ) {
        // 面板高度不足时内容可滚动，避免底部选项（如「手动修改」）被遮挡（与 Dart 的
        // SingleChildScrollView 一致）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            if (request.isMulti) {
                SheetTile(
                    leading = Icons.Filled.PlaylistRemove,
                    title = "已选 ${request.targets.size} 首",
                    subtitle = "以下操作将同时作用于所选的歌曲",
                )
            } else {
                // 单曲：标题用跑马灯、副标题是分析状态（颜色随状态变化）。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        MarqueeText(
                            text = request.song.title,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = libraryStatusText(app, request.song),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = libraryStatusColor(request.song, isDarkTheme()),
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            HorizontalDivider()
            SheetTile(
                leading = Icons.Filled.PlaylistAdd,
                title = "加入播放列表",
                subtitle = if (allInQueue) {
                    "全部已在播放列表中，点击从列表删除"
                } else {
                    "点击加入播放列表"
                },
                onClick = { onAction(LibraryAction.PLAYLIST) },
            )
            SheetTile(
                leading = Icons.Outlined.Inventory2,
                title = "归档",
                subtitle = if (request.isMulti) {
                    "把所选 ${request.targets.size} 首从曲库移除，可在归档页放回"
                } else {
                    "从曲库与推荐中隐藏，可在归档页放回"
                },
                onClick = { onAction(LibraryAction.ARCHIVE) },
            )
            SheetTile(
                leading = Icons.Filled.Refresh,
                title = "重新检测",
                subtitle = if (request.isMulti) {
                    "重新分析所选歌曲的 BPM"
                } else {
                    "重新分析这首歌曲的 BPM"
                },
                onClick = { onAction(LibraryAction.RETRY) },
            )
            if (!request.isMulti && request.song.hasBpm) {
                SheetTile(
                    leading = Icons.Filled.DoubleArrow,
                    title = "BPM ×2",
                    subtitle = "把 BPM 乘以二（常用于半拍/休止误判）",
                    onClick = { onAction(LibraryAction.X2) },
                )
                SheetTile(
                    leading = Icons.Filled.Edit,
                    title = "手动修改",
                    subtitle = "直接输入一个数值作为 BPM",
                    onClick = { onAction(LibraryAction.MANUAL) },
                )
            }
        }
    }
}

/** 面板里的一行（对应 Dart 的 `ListTile`：leading 图标 + 标题 + 可选副标题 + 可选点击）。 */
@Composable
private fun SheetTile(
    leading: ImageVector,
    title: String,
    subtitle: String? = null,
    subtitleStyle: TextStyle? = null,
    onClick: (() -> Unit)? = null,
) {
    // 只有可点的行才挂 clickable（面板顶部的说明行与 Dart 一样不可点、无水波纹）。
    val handler = onClick
    val rowModifier = if (handler != null) {
        Modifier.fillMaxWidth().clickable { handler() }
    } else {
        Modifier.fillMaxWidth()
    }
    Row(
        modifier = rowModifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = leading,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = subtitleStyle ?: MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 手动修改 BPM 对话框（对应 Dart `_editBpm`）：初值为当前 BPM，仅接受 20–400。 */
@Composable
private fun ManualBpmDialog(
    song: Song,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    var text by remember(song.id) {
        mutableStateOf(song.originalBpm?.roundToLong()?.toString() ?: "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改 BPM") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("BPM (20–400)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val v = text.toDoubleOrNull()
                    if (v != null && v >= 20.0 && v <= 400.0) onConfirm(v)
                },
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 列表主体（对应 Dart `_buildBody` / `_SongList`）：
 * 没选过文件夹 → 空状态引导；选了但过滤后为空 → 「没有匹配的音乐」；否则是歌曲列表。
 */
@Composable
private fun LibraryBody(
    app: AppState,
    sortMode: LibrarySortMode,
    query: String,
    selected: List<String>,
    onToggle: (String) -> Unit,
    onLongPress: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    val library = app.library

    if (library.folderPath == null) {
        EmptyLibraryState(modifier = modifier)
        return
    }

    // 已排序 + 搜索过滤后的可见歌曲（归档歌曲不在 visibleSongs(showArchived = false) 里，自然排除）。
    val songs = remember(library.songs, library.archivedIds, sortMode, query) {
        sortedLibrarySongs(library.visibleSongs(showArchived = false, query = query), sortMode)
    }

    if (songs.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "没有匹配的音乐")
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(items = songs, key = { it.id }) { song ->
            SongTile(
                app = app,
                song = song,
                selected = selected.contains(song.id),
                onToggle = { onToggle(song.id) },
                onLongPress = { onLongPress(song) },
            )
        }
    }
}

/** 还没导入过任何文件夹时的空状态（对应 Dart `_buildBody` 的 folderPath == null 分支）。 */
@Composable
private fun EmptyLibraryState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = kGrey,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "还没有音乐\n点上方「添加音乐」选择一个文件夹导入",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "无需上传，全程离线", fontSize = 12.sp)
        }
    }
}

/**
 * 单首歌曲行（对应 Dart `_SongTile`）：
 * 封面缩略图 + 标题（跑马灯）+ 状态文案（颜色随分析状态变化），点击选中、长按呼出菜单。
 */
@Composable
private fun SongTile(
    app: AppState,
    song: Song,
    selected: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val tint = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    } else {
        Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tint)
            .indication(interactionSource, LocalIndication.current)
            .pointerInput(song.id) {
                detectLibraryItemGestures(
                    quickLongPressMillis = kQuickLongPressMillis,
                    interactionSource = interactionSource,
                    onTap = onToggle,
                    onLongPress = onLongPress,
                )
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkThumb(path = song.artworkPath)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            MarqueeText(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = libraryStatusText(app, song),
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = libraryStatusColor(song, isDarkTheme()),
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 48×48 圆角封面缩略图；没有封面或解码失败时回退成音符图标。 */
@Composable
private fun ArtworkThumb(path: String?) {
    val shape = RoundedCornerShape(4.dp)
    if (path == null) {
        ArtworkPlaceholder(shape)
        return
    }
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) { decodeArtworkThumb(path) }
    }
    val loaded = bitmap
    if (loaded == null) {
        // 解码中或解码失败：与 Dart `Image.file(errorBuilder: ...)` 一样显示音符图标。
        ArtworkPlaceholder(shape)
    } else {
        Image(
            bitmap = loaded,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(shape),
            contentScale = ContentScale.Crop,
        )
    }
}

/** 封面缺省占位（48×48、居中、灰色音符），对应 Dart 的 `Icon(Icons.music_note, color: grey)`。 */
@Composable
private fun ArtworkPlaceholder(shape: RoundedCornerShape) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            tint = kGrey,
        )
    }
}

/**
 * 歌曲状态文案（对应 Dart 曲库页的 `_statusText`）。
 * BPM 显示精度走 `SettingsStore.formatBpm`（即 Dart 的 `BpmDisplayController.format`）。
 */
private fun libraryStatusText(app: AppState, song: Song): String = when (song.bpmStatus) {
    BpmStatus.PENDING -> "等待分析"
    BpmStatus.ANALYZING -> "分析中…"
    BpmStatus.FAILED -> song.bpmError ?: "分析失败"
    BpmStatus.DONE -> {
        val conf = (song.bpmConfidence ?: 0.0) * 100.0
        val algo = if (song.algorithm != null) " · 算法${song.algorithm}" else ""
        "${app.settings.formatBpm(song.originalBpm)} BPM · 可信度 ${conf.roundToInt()}%$algo"
    }
}

/** 状态颜色（对应 Dart `_SongTile.build` 里的 switch，浅色用深色、深色用高亮色）。 */
private fun libraryStatusColor(song: Song, dark: Boolean): Color = when (song.bpmStatus) {
    BpmStatus.DONE -> if (dark) kGreenAccent else kGreen700
    BpmStatus.FAILED -> if (dark) kRedAccent else kRed700
    BpmStatus.PENDING, BpmStatus.ANALYZING -> if (dark) kOrangeAccent else kOrange800
}

/** 排序（对应 Dart `_visibleSongs` 里的 switch；SCAN 即扫描顺序，不做处理）。 */
private fun sortedLibrarySongs(songs: List<Song>, mode: LibrarySortMode): List<Song> = when (mode) {
    LibrarySortMode.SCAN -> songs
    LibrarySortMode.TITLE_AZ -> songs.sortedWith(compareBy<Song> { it.title.lowercase() })
    LibrarySortMode.TITLE_ZA -> songs.sortedWith(compareByDescending<Song> { it.title.lowercase() })
    LibrarySortMode.BPM_ASC -> songs.sortedWith(compareBy<Song> { it.originalBpm ?: 0.0 })
    LibrarySortMode.BPM_DESC -> songs.sortedWith(compareByDescending<Song> { it.originalBpm ?: 0.0 })
    LibrarySortMode.DURATION -> songs.sortedWith(compareByDescending<Song> { it.duration ?: 0.0 })
}

/**
 * 按用户点击顺序返回选中的歌曲（对应 Dart `_selectedSongs`）。
 *
 * `selected` 是插入序列表，迭代顺序即点击先后；这里只从未归档歌曲里取，
 * 与 Dart 用 `lib.songs`（不含归档）建表的行为一致。
 */
private fun selectedSongs(library: LibraryStore, selected: List<String>): List<Song> {
    val byId = library.activeSongs().associateBy { it.id }
    return selected.mapNotNull { byId[it] }
}

/** 解码封面缩略图；文件不存在或不是图片时返回 null（对应 Dart `errorBuilder` 分支）。 */
private fun decodeArtworkThumb(path: String): ImageBitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        null
    } else {
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= kArtworkThumbPx &&
            bounds.outHeight / (sample * 2) >= kArtworkThumbPx
        ) {
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
 * 列表项手势：点击选中 / 长按呼出菜单。
 *
 * Dart 用自定义 `LongPressGestureRecognizer(duration: 250ms)` 把长按阈值缩短到 250ms
 * （比系统默认约 500ms 更快呼出菜单），并且长按胜出后点击不再生效。
 * 原生侧把点击与长按放进**同一个**手势判定里，避免两个识别器互相抢事件：
 * 250ms 内抬手 → 点击；超时 → 长按；中途被父级（列表滚动）接管 → 两者都不触发。
 *
 * 按压状态手动发给 [interactionSource]，让 `Modifier.indication` 照常显示水波纹
 * （等价 Flutter 的 ListTile 按压反馈）。
 *
 * 实现要点：`pointerInput` 的 block 是**普通**挂起 lambda，而 `awaitEachGesture` /
 * `awaitPointerEventScope` 的 block 是「受限挂起作用域」（只能调用该作用域自己的
 * 成员挂起函数）。`interactionSource.emit` 与 `withTimeout` 都不属于受限作用域，
 * 所以这里用 `while (true)` 在外层循环里驱动，只在取值时进入 `awaitPointerEventScope`。
 */
private suspend fun PointerInputScope.detectLibraryItemGestures(
    quickLongPressMillis: Long,
    interactionSource: MutableInteractionSource,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    while (true) {
        val down = awaitPointerEventScope { awaitFirstDown() }
        val press = PressInteraction.Press(down.position)
        interactionSource.emit(press)

        var longPressed = false
        var up: PointerInputChange? = null
        try {
            // 250ms 内抬手 → 点击；超时抛 TimeoutCancellationException → 长按。
            up = withTimeout(quickLongPressMillis) {
                awaitPointerEventScope { waitForUpOrCancellation() }
            }
        } catch (_: TimeoutCancellationException) {
            longPressed = true
        }

        when {
            longPressed -> {
                // 消费掉随后的抬起，避免被父容器再当成一次点击。
                awaitPointerEventScope { waitForUpOrCancellation()?.consume() }
                interactionSource.emit(PressInteraction.Release(press))
                onLongPress()
            }
            up != null -> {
                up.consume()
                interactionSource.emit(PressInteraction.Release(press))
                onTap()
            }
            else -> {
                // 手势被取消（例如手指移出条目、列表开始滚动）。
                interactionSource.emit(PressInteraction.Cancel(press))
            }
        }
    }
}
