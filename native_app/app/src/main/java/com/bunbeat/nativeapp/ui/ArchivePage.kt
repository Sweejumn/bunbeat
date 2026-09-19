package com.bunbeat.nativeapp.ui

// 对应 Dart `ui/archive_page.dart`。
//
// 归档页：展示已归档（从曲库 / 推荐中隐藏）的歌曲，可放回曲库或继续操作 BPM。
//
// Dart 的结构是 Scaffold(AppBar('归档')) + 「空状态 Center(Text)」或
// ListView.builder(_ArchivedTile)；每行点击 / 长按都弹出同一个操作面板
// （Dart 用 showModalBottomSheet，这里等价用 M3 的 ModalBottomSheet）。
//
// 与 Dart 的一个结构性差异：Dart 的 `_ArchivedTile` 自己持有「弹面板 / 改 BPM」流程，
// 原生侧把面板状态提到页面级（按 song id 记），这样后台分析更新 Song 实例时
// 面板里显示的状态会跟着刷新，而不是停留在打开面板那一刻的旧快照。

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DoubleArrow
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bunbeat.nativeapp.AppState
import com.bunbeat.nativeapp.model.BpmStatus
import com.bunbeat.nativeapp.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

/** 未找到封面时占位图标的颜色（Dart `Colors.grey`）。 */
private val kPlaceholderGrey = Color(0xFF9E9E9E)

/** 封面缩略图的目标像素边长：行高 48dp，中等密度屏幕约 144px。 */
private const val kArtworkTargetPx = 144

/**
 * 封面解码缓存。
 * LazyColumn 滚动时同一行会反复进出组合，没有缓存就会反复解码同一张图。
 * Dart 的 `Image.file` 内部也有图片缓存，这里对齐这个行为。
 */
private val kArtworkCache = LruCache<String, ImageBitmap>(24)

/** 操作面板里可选的动作用枚举表示（对应 Dart 里 pop 出来的字符串 'unarchive' / 'retry' / …）。 */
private enum class ArchiveAction { UNARCHIVE, RETRY, X2, DIV2, MANUAL }

/**
 * 归档页。对应 Dart `ArchivePage`。
 *
 * @param onBack 顶部返回按钮的动作（对应 Dart AppBar 自带的返回箭头）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivePage(app: AppState, onBack: () -> Unit) {
    val archived = app.library.archivedSongs()

    // 面板 / 弹窗只记 song id，再按 id 取最新实例：分析进度变化时面板内容会跟着更新。
    var actionsForId by remember { mutableStateOf<String?>(null) }
    var editingId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("归档") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (archived.isEmpty()) {
            // Dart: Center(child: Text('暂无归档歌曲\n在曲库长按歌曲 → 归档'))
            // 两行文字整体居中，行内保持左对齐（不设 textAlign，与 Dart 的 Text 默认一致）。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无归档歌曲\n在曲库长按歌曲 → 归档")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(items = archived, key = { it.id }) { song ->
                    ArchivedTile(
                        app = app,
                        song = song,
                        onOpenActions = { actionsForId = song.id },
                    )
                }
            }
        }
    }

    val actionsSong = actionsForId?.let { app.library.byId(it) }
    if (actionsSong != null) {
        // 取出到不可变局部变量，回调里用的就是这一份快照（面板打开期间歌曲不会被移除）。
        val target = actionsSong
        ArchivedActionsSheet(
            app = app,
            song = target,
            onDismiss = { actionsForId = null },
            onAction = { action ->
                // 「手动修改」在面板收起后弹输入框，其余动作立即执行。
                if (action == ArchiveAction.MANUAL) {
                    editingId = target.id
                } else {
                    performArchiveAction(app, target, action)
                }
            },
        )
    }

    val editingSong = editingId?.let { app.library.byId(it) }
    if (editingSong != null) {
        val target = editingSong
        EditBpmDialog(
            song = target,
            onDismiss = { editingId = null },
            onConfirm = { bpm ->
                app.library.setManualBpm(target, bpm)
                editingId = null
            },
        )
    }
}

/**
 * 归档列表的一行。对应 Dart `_ArchivedTile`。
 *
 * 点击与长按都打开操作面板；右侧「放回」按钮直接放回曲库（等价 Dart 的 trailing TextButton）。
 */
@Composable
private fun ArchivedTile(app: AppState, song: Song, onOpenActions: () -> Unit) {
    // 归档行淡显，示意已隐藏（Dart: surfaceContainerHighest.withValues(alpha: 0.3)）。
    val tileColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tileColor)
            .combinedClickable(
                onClick = onOpenActions,
                onLongClick = onOpenActions,
            )
            // 上下各 12dp + 48dp 封面 = 72dp，对齐 Dart 两行 ListTile 的行高。
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkThumb(path = song.artworkPath)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            // Dart 用的是 song.title（不是 displayTitle）。
            MarqueeText(text = song.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = statusText(app, song),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { app.library.unarchive(song.id) }) { Text("放回") }
    }
}

/** 操作面板。对应 Dart `_ArchivedTile._showActions` 里的 showModalBottomSheet。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchivedActionsSheet(
    app: AppState,
    song: Song,
    onDismiss: () -> Unit,
    onAction: (ArchiveAction) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    // 与 Dart 的 `Navigator.pop(ctx, action)` 一致：先收起面板，再执行动作。
    fun choose(action: ArchiveAction) {
        scope.launch {
            sheetState.hide()
            onDismiss()
            onAction(action)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Flutter 的 showModalBottomSheet 默认不显示拖拽把手，这里去掉以保持一致。
        dragHandle = null,
    ) {
        // 头部：封面图标 + 歌名 + 状态（Dart 的 ListTile(leading, title: MarqueeText, subtitle)）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                MarqueeText(text = song.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = statusText(app, song),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Dart: const Divider(height: 1)
        HorizontalDivider(thickness = 1.dp)

        SheetAction(Icons.Filled.Unarchive, "放回曲库") { choose(ArchiveAction.UNARCHIVE) }
        SheetAction(Icons.Filled.Refresh, "重新检测") { choose(ArchiveAction.RETRY) }
        // Dart: if (song.hasBpm) ...
        if (song.hasBpm) {
            SheetAction(Icons.Filled.DoubleArrow, "BPM ×2") { choose(ArchiveAction.X2) }
            SheetAction(Icons.Filled.HorizontalRule, "BPM ÷2") { choose(ArchiveAction.DIV2) }
            SheetAction(Icons.Filled.Edit, "手动修改") { choose(ArchiveAction.MANUAL) }
        }
    }
}

/** 面板里的一个操作行（对应 Dart 的 `ListTile(leading: Icon, title: Text)`）。 */
@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // 上下各 16dp + 24dp 图标 = 56dp，对齐 Dart ListTile 的单行高度。
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * 48×48 圆角 4dp 的封面缩略图，没有封面或解码失败时退回音符占位图标。
 * 对应 Dart: ClipRRect(borderRadius: 4) + SizedBox(48, 48) + Image.file(..., errorBuilder: Icon(music_note, color: Colors.grey))。
 */
@Composable
private fun ArtworkThumb(path: String?) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = if (path.isNullOrEmpty()) null else decodeArtwork(path)
    }
    val bmp = bitmap

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = kPlaceholderGrey,
            )
        }
    }
}

/** 手动改 BPM 弹窗。对应 Dart `_ArchivedTile._editBpm`。 */
@Composable
private fun EditBpmDialog(song: Song, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    // 初值 = 当前 BPM 四舍五入后的整数（Dart: cur.originalBpm?.round().toString() ?? ''）。
    // 用 song.id 做 key，后台分析更新实例时不会覆盖用户正在输入的内容。
    var text by remember(song.id) {
        mutableStateOf(song.originalBpm?.roundToLong()?.toString().orEmpty())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改 BPM") },
        text = {
            TextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("BPM (20–400)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        },
        confirmButton = {
            Button(onClick = {
                // Dart 用 double.tryParse（允许首尾空白）；解析失败或超出 20–400 时
                // 点「确定」不关闭弹窗，这里保持一致。
                val v = text.trim().toDoubleOrNull()
                if (v != null && v >= 20.0 && v <= 400.0) onConfirm(v)
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 行 / 面板里的状态文案。对应 Dart `_ArchivedTile._statusText`。 */
private fun statusText(app: AppState, song: Song): String = when (song.bpmStatus) {
    BpmStatus.PENDING -> "等待分析"
    BpmStatus.ANALYZING -> "分析中…"
    BpmStatus.FAILED -> song.bpmError ?: "分析失败"
    BpmStatus.DONE -> {
        // Dart: (song.bpmConfidence ?? 0) * 100 后 .round() 取整百分比。
        val conf = (song.bpmConfidence ?: 0.0) * 100.0
        "${app.settings.formatBpm(song.originalBpm)} BPM · 可信度 ${conf.roundToLong()}%"
    }
}

/** 执行面板选中的动作。对应 Dart `_showActions` 里的 switch。 */
private fun performArchiveAction(app: AppState, song: Song, action: ArchiveAction) {
    when (action) {
        // Dart: await lib.unarchive(song)（原生侧按 id 归档，id 与歌曲一一对应）。
        ArchiveAction.UNARCHIVE -> app.library.unarchive(song.id)

        // Dart: lib.retryAnalyze(song)（原生侧同义实现叫 analyzeOne）。
        ArchiveAction.RETRY -> app.library.analyzeOne(song)

        // Dart: ((song.originalBpm ?? 0) * 2).clamp(20, 400)
        ArchiveAction.X2 -> app.library.setManualBpm(
            song,
            ((song.originalBpm ?: 0.0) * 2.0).coerceIn(20.0, 400.0),
        )

        // Dart: ((song.originalBpm ?? 0) / 2).clamp(20, 400)
        ArchiveAction.DIV2 -> app.library.setManualBpm(
            song,
            ((song.originalBpm ?: 0.0) / 2.0).coerceIn(20.0, 400.0),
        )

        // 由调用处弹「修改 BPM」对话框。
        ArchiveAction.MANUAL -> Unit
    }
}

/**
 * 后台解码封面文件（对应 Dart `Image.file` 的解码路径），失败返回 null 走占位图标。
 * 先只读边界拿到原图尺寸，再按 2 的幂次降采样，避免为 48dp 缩略图解码整张封面。
 */
private suspend fun decodeArtwork(path: String): ImageBitmap? {
    kArtworkCache.get(path)?.let { return it }
    return withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= kArtworkTargetPx &&
            bounds.outHeight / (sample * 2) >= kArtworkTargetPx
        ) {
            sample *= 2
        }

        val bmp: Bitmap = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return@withContext null

        val image = bmp.asImageBitmap()
        kArtworkCache.put(path, image)
        image
    }
}
