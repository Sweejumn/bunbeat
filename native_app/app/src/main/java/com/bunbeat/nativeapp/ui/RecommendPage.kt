package com.bunbeat.nativeapp.ui

// 对应 Dart `ui/recommend_page.dart`。
//
// 页面结构：顶部工具栏（标题 + 使用说明 / 设置）+ 固定表头（选择 / 自动勾选 / 全选清空）
// + 可滚动列表（运动模式选择器 + 推荐歌曲行）+ 底部常驻「变速并播放」按钮。

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bunbeat.nativeapp.AppState
import com.bunbeat.nativeapp.model.Recommendation
import com.bunbeat.nativeapp.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

// 封面缺省图标颜色（对应 Dart `Colors.grey`）。
private val kArtworkGrey = Color(0xFF9E9E9E)

/**
 * 运动模式与推荐页（对应 Dart `RecommendPage` / `_RecommendPageState`）。
 *
 * @param app 全局状态容器（等价 Dart 的 Provider：曲库 / 队列 / 播放器 / 设置 / 提示）
 */
@Composable
fun RecommendPage(app: AppState) {
    val library = app.library

    // Dart 是 `lib.recommend(target: lib.targetBpm)..sort(distance)`（context.watch 触发重建）；
    // Kotlin 侧 `recommendations()` 内部已按「距离升序、距离相同置信度高者在前」排好，等价。
    val recs = library.recommendations()
    val target = library.targetBpm
    val eligible = recs.count { it.song.hasBpm }

    // Dart 的 _selected 是插入序 LinkedHashSet（迭代顺序 = 点击先后，入队顺序也照此）。
    // Compose 用 SnapshotStateList 保留同样的插入序；key 是 song.id。
    val selected = remember { mutableStateListOf<String>() }
    var showHelp by remember { mutableStateOf(false) }

    // Dart `HelpDialog.show(context, section: HelpSection.recommend)`。
    if (showHelp) {
        HelpDialogContent(section = HelpSection.recommend, onDismiss = { showHelp = false })
    }

    Scaffold(
        // 本页嵌在 HomePage 的 Scaffold 内容区里（外层已处理系统栏内边距），
        // 这里把 contentWindowInsets 置零，避免底部播放条再被系统栏顶一次。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            RecommendTopBar(
                onHelp = { showHelp = true },
                // Dart `Navigator.push(MaterialPageRoute(builder: (_) => SettingsPage()))`。
                onSettings = { app.nav.openSettings() },
            )
        },
        bottomBar = {
            // 底部常驻播放条：无论列表多长，按钮始终可见，无需滚动到底。
            // Dart 还包了一层 SafeArea，native_app 侧由 HomePage 的 Scaffold 统一处理。
            if (selected.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 12.dp),
                    ) {
                        // Dart 用 `FilledButton.icon`（minimumSize 高 52、文字 16 粗体）。
                        // material3 里 FilledButton 与 Button 是同一个组件，这里用 Button +
                        // 「图标 + 8dp 间距 + 文字」的行实现，视觉与 .icon 变体一致。
                        Button(
                            onClick = { playSelected(app, selected) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                        ) {
                            Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "变速并播放（${selected.size} 首）",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 固定头部：只有「选择 + 自动勾选/全选/全不选」按钮行。
            // 翻动歌曲列表时保持可见，方便反复点选（运动模式选择不再固定，随列表滚动）。
            if (eligible > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 8.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("选择", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            selected.clear()
                            for (r in recs) {
                                if (processable(r, library.targetBpm)) selected.add(r.song.id)
                            }
                        },
                    ) {
                        Text("自动勾选可变速")
                    }
                    // 与曲库页对齐：全部勾选时变为「清空」，点一下全清；否则「全选」点一下全勾。
                    TextButton(
                        onClick = {
                            if (selected.size == recs.size) {
                                selected.clear()
                            } else {
                                selected.clear()
                                selected.addAll(recs.map { it.song.id })
                            }
                        },
                    ) {
                        Text(if (selected.size == recs.size) "清空" else "全选")
                    }
                }
                HorizontalDivider(thickness = 1.dp)
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 8.dp,
                    end = 16.dp,
                    bottom = if (selected.isEmpty()) 16.dp else 96.dp,
                ),
            ) {
                // 运动模式选择：随列表一起滚动（不固定）。
                item(key = "mode-picker") {
                    Box(modifier = Modifier.padding(bottom = 8.dp)) {
                        ModePicker(
                            bpm = library.targetBpm,
                            onChanged = { v -> library.setTargetBpm(v) },
                        )
                    }
                }

                items(items = recs, key = { it.song.id }) { r ->
                    RecTile(
                        r = r,
                        targetBpm = target,
                        selected = selected.contains(r.song.id),
                        onToggle = {
                            // Dart：`if (!_selected.remove(id)) _selected.add(id)`（再点一次取消）。
                            if (!selected.remove(r.song.id)) selected.add(r.song.id)
                        },
                        formatBpm = { v -> app.settings.formatBpm(v) },
                    )
                }

                if (recs.isEmpty()) {
                    item(key = "empty") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("请先在「曲库」选择文件夹并等待 BPM 分析")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 顶部工具栏（对应 Dart 推荐页的 `AppBar`：标题 + 使用说明 / 设置两个图标按钮）。
 *
 * 高度取 56dp 与 Flutter 的 AppBar 一致（material3 的 TopAppBar 是 64dp，且需要实验性注解）；
 * 图标按钮的 tooltip 在 Compose 侧没有稳定 API，这里只保留 contentDescription（无障碍文案一致）。
 */
@Composable
private fun RecommendTopBar(
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
                text = "运动模式与推荐",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onHelp) {
                Icon(imageVector = Icons.Outlined.HelpOutline, contentDescription = "使用说明")
            }
            IconButton(onClick = onSettings) {
                Icon(imageVector = Icons.Outlined.Settings, contentDescription = "设置")
            }
        }
    }
}

/**
 * 一行推荐（对应 Dart `_RecTile`）。
 *
 * Dart 用 `ListTile(selected: ..., selectedTileColor: primary@15%, selectedColor: primary, dense: true)`；
 * Compose 侧由 material3 `ListItem` 承担（行内边距与 ListTile 一致）：
 * selectedTileColor → containerColor，selectedColor → headlineColor。
 * `dense` 在 ListItem 上没有对应参数，行高按 ListItem 默认值（略高于 Dart 的 dense）。
 */
@Composable
private fun RecTile(
    r: Recommendation,
    targetBpm: Double,
    selected: Boolean,
    onToggle: () -> Unit,
    formatBpm: (Double?) -> String,
) {
    val s = r.song
    val orig = s.originalBpm
    // Dart `gradeTempo(orig, targetBpm, brightness: theme.brightness)`；
    // Compose 侧取 `gradeTempo(orig, target)`，深浅色由实际背景色判定（见 TempoGrade.kt）。
    val grade = gradeTempo(orig, targetBpm)
    val accent = MaterialTheme.colorScheme.primary

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() },
        headlineContent = {
            // Dart `MarqueeText(s.title)`：过长时横向滚动，放不下则省略。
            MarqueeText(text = s.title)
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 分级箭头/符号（绿= · 绿/琥珀/红↑↓ · 红✕），颜色与 Web 图例一致。
                Text(
                    text = grade.symbol,
                    color = grade.color,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // BPM 走设置开关（对应 Dart `BpmDisplayController.format`：默认两位小数，关=整数）。
                    Text(
                        text = formatBpm(orig),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(" · ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // 带符号百分比（如 +3.4%），颜色与箭头一致；太窄时省略号截断。
                    Text(
                        text = if (orig != null) grade.pctLabel else "未知 BPM",
                        modifier = Modifier.weight(1f, fill = false),
                        color = grade.color,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        leadingContent = { RecArtwork(path = s.artworkPath) },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) accent.copy(alpha = 0.15f) else Color.Transparent,
            headlineColor = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
            leadingIconColor = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

/** 是否可用于变速：与原 BPM 的偏移 ≤12%（对应 Web TempoArrow 的可变速判定）。 */
private fun processable(r: Recommendation, target: Double): Boolean {
    val orig = r.song.originalBpm
    if (orig == null || orig <= 0.0) return false
    return abs(orig - target) / orig <= 0.12
}

/**
 * 入队并开始播放（对应 Dart `_play`）。
 *
 * native_app 侧的接口缺口与等价做法：
 * - Dart 走 `lib.buildPlaylist(selected, target:)`（产出带 filePath/originalBpm/targetBpm 的
 *   PlaylistItem）与 `AudioPlayerService.tryPlay(...)`；Kotlin 侧 QueueStore 直接存 Song、
 *   LibraryStore 也没有 buildPlaylist，所以这里用 `filter { it.hasBpm }` 复刻 buildPlaylist
 *   的过滤条件，并在 load 之前把第一首的变速倍率算好（load 会把当前 speed 写进播放器）。
 * - Dart 的 `tryPlay` 失败时返回 false；这里由 `PlayerController.onError` 承担同等的报错路径。
 */
private fun playSelected(app: AppState, selectedIds: List<String>) {
    val library = app.library
    // 按点击顺序（selectedIds 的迭代顺序即点击先后）取选中的歌曲，使入队顺序与点选一致。
    val byId = library.songs.associateBy { it.id }
    val chosen = selectedIds.mapNotNull { byId[it] }
    // 对应 Dart `buildPlaylist`：只保留有 BPM 的歌（`where((s) => s.hasBpm)`）。
    val playlist = chosen.filter { it.hasBpm }
    // Dart `queue.start(playlist)`：从第 0 首开始（Kotlin 的 start 需要显式给起始下标）。
    app.queue.start(playlist, 0)
    // 真正开始播放第一首（与「变速并播放」文案一致），失败也不阻塞入队。
    if (playlist.isNotEmpty()) {
        val first = playlist.first()
        app.player.setSpeed(PlayerController.computeSpeed(first.originalBpm, library.targetBpm))
        app.player.load(first, autoPlay = true)
    }
    // Dart `ScaffoldMessenger.of(context).showSnackBar(...)`（由外层宿主统一展示）。
    app.snackbar("已入队并开始播放第一首")
}

/**
 * 48×48 圆角封面缩略图；没有封面或解码失败时回退成灰色音符图标
 * （对应 Dart `Image.file(..., errorBuilder: (_, __, ___) => Icon(Icons.music_note, color: grey))`）。
 *
 * native_app 不使用图片加载库，这里在 IO 线程上按路径解码，结果按 path 记忆。
 */
@Composable
private fun RecArtwork(path: String?) {
    val shape = RoundedCornerShape(4.dp)
    if (path == null) {
        RecArtworkPlaceholder(shape)
        return
    }
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) { decodeRecArtwork(path) }
    }
    val loaded = bitmap
    if (loaded == null) {
        // 解码中或解码失败：与 Dart 的 errorBuilder 一样显示音符图标。
        RecArtworkPlaceholder(shape)
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

/** 封面缺省占位（48×48、居中、灰色音符）。 */
@Composable
private fun RecArtworkPlaceholder(shape: RoundedCornerShape) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            tint = kArtworkGrey,
        )
    }
}

/** 解码封面图；路径不存在或不是图片时返回 null，由调用方回退成图标。 */
private fun decodeRecArtwork(path: String): ImageBitmap? = try {
    BitmapFactory.decodeFile(path)?.asImageBitmap()
} catch (_: Exception) {
    null
}
