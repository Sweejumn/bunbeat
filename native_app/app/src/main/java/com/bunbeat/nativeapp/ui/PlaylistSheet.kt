package com.bunbeat.nativeapp.ui

// 对应 Dart `ui/player_page.dart` 第 1052–1272 行：
//   _PlaylistSheet（1052–1058） / _PlaylistSheetState（1060–1188，含 build、_artworkFor、_confirmClear）
//   / _PlaylistTile（1192–1269）
//
// 播放列表面板：标题 + 当前播放模式 + 清空按钮，下面是可拖动排序的歌曲列表。
// 高度固定为屏幕的 70%（Dart 的 SizedBox(height: size.height * 0.7)）。
//
// 承载方式：Dart 用 showModalBottomSheet 弹出独立路由，原生侧由调用方
// （PlayerPage）用 `if (sheetVisible) PlaylistSheet(...)` 控制显隐，
// 因此这里只负责渲染，一切「关闭」都走 onDismiss 回调（对应 Dart 的 Navigator.pop）。
//
// 队列数据的来源差异：Dart 的队列元素是轻量的 PlaylistItem（filePath + originalBpm + targetBpm），
// 原生侧 QueueStore 直接存 Song（不可变 data class），所以
//   - 「原 BPM」读 song.originalBpm；
//   - 「目标 BPM」读全局的 app.library.targetBpm（原生侧目标 BPM 是全局设置，不逐项存）。

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.bunbeat.nativeapp.AppState
import com.bunbeat.nativeapp.model.Song
import com.bunbeat.nativeapp.store.LoopMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 封面占位图标与拖动手柄的灰色（Dart `Colors.grey`）。 */
private val kPlaylistGrey = Color(0xFF9E9E9E)

/**
 * 单行固定高度 64dp，对齐 Dart 的 dense 两行 ListTile。
 * 固定行高是拖动排序的前提：位移除以行高就是「移动了几行」。
 */
private val kPlaylistRowHeight = 64.dp

/** 行内封面 42dp，按中等密度屏幕约 126px 采样即可。 */
private const val kPlaylistArtworkTargetPx = 126

/**
 * 封面解码缓存。列表滚动时同一行会反复进出组合，没有缓存就会反复解码同一张图
 * （Dart 的 `Image.file` 内部也有图片缓存，这里对齐该行为）。
 */
private val kPlaylistArtworkCache = LruCache<String, ImageBitmap>(24)

/**
 * 播放列表面板。对应 Dart `_PlaylistSheet` + `_PlaylistSheetState`。
 *
 * @param app 全局状态：队列取 [AppState.queue]，目标 BPM 取 [AppState.library]，BPM 格式化取 [AppState.settings]。
 * @param onDismiss 关闭面板（对应 Dart 的 `Navigator.of(context).pop()`）。
 * @param onPlayIndex 点选某一行时跳到队列中该下标的歌（对应 Dart `widget.onPlayIndex(index)`）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistSheet(
    app: AppState,
    onDismiss: () -> Unit,
    onPlayIndex: (Int) -> Unit,
) {
    val queue = app.queue
    // queue.items 返回的是底层的快照列表实例，读它会随队列变化而重组。
    val items = queue.items
    val scheme = MaterialTheme.colorScheme

    // Dart 的面板一打开就是完整高度，所以跳过「半展开」中间态，直接展开到内容高度。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // 拖动排序状态：dragFrom = 被拖起的行下标；dragTo = 当前预览落点；dragOffsetY = 相对起点的像素位移。
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragTo by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    // 「清空」的二次确认弹窗（对应 Dart `_confirmClear` 里的 showDialog）。
    var confirmClear by remember { mutableStateOf(false) }

    val rowHeightPx = with(LocalDensity.current) { kPlaylistRowHeight.toPx() }

    /** 收起面板并通知调用方（对应 Dart 的 `Navigator.pop`）。 */
    fun dismissSheet() {
        scope.launch {
            sheetState.hide()
            onDismiss()
        }
    }

    /** 拖动结束：把预览落点真正应用成一次队列移动（对应 Dart onReorder 里的 `q.moveItem`）。 */
    fun endDrag() {
        val from = dragFrom
        val to = dragTo
        dragFrom = null
        dragTo = null
        dragOffsetY = 0f
        // moveItem 内部会做边界检查，并修正「正在播放的那首」的下标。
        if (from != null && to != null && from != to) queue.moveItem(from, to)
    }

    /** 拖动被系统取消：只清状态，不改队列。 */
    fun cancelDrag() {
        dragFrom = null
        dragTo = null
        dragOffsetY = 0f
    }

    /** 对应 Dart `_confirmClear` 确认后的三步：清空队列 → 立刻停止播放 → 关闭面板。 */
    fun clearQueueAndStop() {
        queue.clear()
        // Dart: context.read<AudioPlayerService>().stop()。
        // 原生 PlayerController 没有 stop()，用「暂停 + 回到 0 秒」达到同样的
        // 「不再出声、进度归零」效果（isPlaying 变 false 后节拍器会自动关闭）。
        app.player.pause()
        app.player.seekTo(0.0)
        dismissSheet()
    }

    // 头部模式标签：与 Dart 的 if/else 链同序（随机 > 单曲循环 > 列表循环）。
    // Dart 的 `q.repeatingOne` 就是 `loopMode == LoopMode.one`。
    val modeLabel: String
    val modeIcon: ImageVector
    if (queue.shuffle) {
        modeLabel = "随机"
        modeIcon = Icons.Filled.Shuffle
    } else if (queue.loopMode == LoopMode.ONE) {
        modeLabel = "单曲循环"
        modeIcon = Icons.Filled.RepeatOne
    } else {
        modeLabel = "列表循环"
        modeIcon = Icons.Filled.Repeat
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Flutter 的 showModalBottomSheet 默认不画拖拽把手，去掉以保持一致。
        dragHandle = null,
        // 与列表行背景同色：拖动时被拖起的行才能干净地盖住其它行（静止时视觉无差别）。
        containerColor = scheme.surfaceContainerLow,
    ) {
        // Dart: SafeArea > SizedBox(height: MediaQuery.of(context).size.height * 0.7)
        // ModalBottomSheet 自身已处理系统栏 inset，这里只需要 70% 的高度。
        Column(modifier = Modifier.fillMaxHeight(0.7f)) {

            // ---- 头部：标题 + 当前模式 + 清空 ----
            // Dart: Padding(EdgeInsets.fromLTRB(16, 0, 8, 4), Row(...))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 0.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "播放列表（${items.size}）",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = modeIcon,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = modeLabel,
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                // Dart: TextButton.icon(Icons.delete_sweep, '清空')，列表为空时禁用。
                TextButton(
                    onClick = { confirmClear = true },
                    enabled = items.isNotEmpty(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("清空")
                }
            }

            // Dart: const Divider(height: 1)
            HorizontalDivider(thickness = 1.dp)

            // ---- 列表 / 空状态 ----
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (items.isEmpty()) {
                    // Dart: const Center(child: Text('播放列表为空'))
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("播放列表为空")
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(
                            items = items,
                            // 同一首歌可能在一份队列里出现多次，key 里带上下标保证唯一。
                            key = { index, song -> "playlist-$index-${song.id}" },
                        ) { index, song ->
                            val isCurrent = index == queue.index
                            // -1 表示「没有正在拖动的行」；用 Int 而不是 Int? 是为了让下面的
                            // when 分支全部走值比较，不依赖可空类型的智能转换。
                            val from = dragFrom ?: -1
                            val to = dragTo ?: -1
                            // 拖动预览位移：被拖起的行跟着手指走，其余行整体让位一格。
                            val translationY: Float = when {
                                from < 0 -> 0f
                                index == from -> dragOffsetY
                                to < 0 -> 0f
                                from < to && index in (from + 1)..to -> -rowHeightPx
                                from > to && index in to until from -> rowHeightPx
                                else -> 0f
                            }

                            PlaylistRow(
                                song = song,
                                subtitle = bpmSubtitle(app, song),
                                isCurrent = isCurrent,
                                artworkPath = artworkFor(app, song.filePath),
                                translationY = translationY,
                                onTop = index == from,
                                // 当前播放的那首点按无效（Dart: onTap: isCurrent ? null : ...）。
                                tappable = !isCurrent,
                                onTap = {
                                    onPlayIndex(index)
                                    dismissSheet()
                                },
                                onDelete = { queue.removeAt(index) },
                                dragHandleModifier = Modifier.pointerInput(index, items.size) {
                                    // 只有按在右侧手柄上长按才能拖动，整行仍可正常滚动列表。
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { _ ->
                                            dragFrom = index
                                            dragTo = index
                                            dragOffsetY = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetY += dragAmount.y
                                            val cur = dragFrom
                                            if (cur != null) {
                                                // 位移换算成「移动了几行」，再夹进列表范围。
                                                val shift = (dragOffsetY / rowHeightPx).roundToInt()
                                                val last = queue.items.size - 1
                                                dragTo = (cur + shift).coerceIn(0, last)
                                            }
                                        },
                                        onDragEnd = { endDrag() },
                                        onDragCancel = { cancelDrag() },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // Dart: showDialog<bool>(AlertDialog('清空播放列表？'))；确认后清空 + 停止 + 关闭。
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空播放列表？") },
            text = { Text("将移除队列中所有歌曲并停止当前播放。") },
            confirmButton = {
                Button(onClick = {
                    confirmClear = false
                    clearQueueAndStop()
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 从曲库按文件路径匹配封面（对应 Dart `_artworkFor`）：
 * 队列可能来自持久化、曲库还没扫完，那时返回 null 走占位图标。
 */
private fun artworkFor(app: AppState, filePath: String): String? {
    for (s in app.library.songs) {
        if (s.filePath == filePath) return s.artworkPath
    }
    return null
}

/**
 * 行副标题：`原BPM→目标BPM BPM`。
 * Dart 侧两个值都来自 PlaylistItem 的非空 double；原生侧原 BPM 可能尚未分析（null），
 * 这时 app.settings.formatBpm 会给出「—」，与曲库其它位置的显示口径一致。
 */
private fun bpmSubtitle(app: AppState, song: Song): String {
    val from = app.settings.formatBpm(song.originalBpm)
    val to = app.settings.formatBpm(app.library.targetBpm)
    return "${from}→${to} BPM"
}

/**
 * 播放列表单行。对应 Dart `_PlaylistTile`：
 * 封面 + 文件名 + 原/目标 BPM，当前播放高亮；右侧是拖动手柄与「移除」按钮。
 */
@Composable
private fun PlaylistRow(
    song: Song,
    subtitle: String,
    isCurrent: Boolean,
    artworkPath: String?,
    translationY: Float,
    onTop: Boolean,
    tappable: Boolean,
    onTap: () -> Unit,
    onDelete: () -> Unit,
    dragHandleModifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(kPlaylistRowHeight)
            // 被拖起的行要浮在其它行之上，否则会被后面绘制的行盖住。
            .zIndex(if (onTop) 1f else 0f)
            .offset { IntOffset(0, translationY.roundToInt()) }
            // 先铺弹层底色（拖动时相邻行重叠也不透字），再叠当前曲高亮。
            .background(scheme.surfaceContainerLow)
            .then(
                if (isCurrent) {
                    // Dart: selectedTileColor: colorScheme.primary.withValues(alpha: 0.12)
                    Modifier.background(scheme.primary.copy(alpha = 0.12f))
                } else {
                    Modifier
                },
            )
            .clickable(enabled = tappable, onClick = onTap)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistArtwork(path = artworkPath)
        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                // Dart: item.filePath.split('/').last
                text = song.filePath.split('/').last(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Dart: trailing Row(mainAxisSize: min) —— 正在播放图标 + 拖动手柄 + 移除按钮。
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isCurrent) {
                Box(modifier = Modifier.padding(end = 8.dp)) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            // 拖动手柄：只有按住这一小块区域长按才能拖动排序。
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .then(dragHandleModifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = "拖动排序",
                    tint = kPlaylistGrey,
                    modifier = Modifier.size(22.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    // Dart 这里是 tooltip: '从播放列表移除'，原生侧用无障碍描述承载同一文案。
                    contentDescription = "从播放列表移除",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * 42×42、圆角 4dp 的封面缩略图；没有封面或解码失败时退回音符占位图标。
 * 对应 Dart: ClipRRect(borderRadius: 4) + SizedBox(42, 42) + Image.file(..., errorBuilder: Icon(music_note, color: Colors.grey))。
 */
@Composable
private fun PlaylistArtwork(path: String?) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = if (path.isNullOrEmpty()) null else decodePlaylistArtwork(path)
    }
    val bmp = bitmap

    Box(
        modifier = Modifier
            .size(42.dp)
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
                tint = kPlaylistGrey,
            )
        }
    }
}

/**
 * 后台解码封面文件（对应 Dart `Image.file` 的解码路径），失败返回 null 走占位图标。
 * 先只读边界拿到原图尺寸，再按 2 的幂次降采样，避免为 42dp 缩略图解码整张封面。
 */
private suspend fun decodePlaylistArtwork(path: String): ImageBitmap? {
    kPlaylistArtworkCache.get(path)?.let { return it }
    return withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= kPlaylistArtworkTargetPx &&
            bounds.outHeight / (sample * 2) >= kPlaylistArtworkTargetPx
        ) {
            sample *= 2
        }

        val bmp: Bitmap = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return@withContext null

        val image = bmp.asImageBitmap()
        kPlaylistArtworkCache.put(path, image)
        image
    }
}
