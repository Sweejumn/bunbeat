package com.bunbeat.nativeapp.ui

// 首页：Shizuku 风格的「卡片流」。
//
// 对应 Shizuku 的 `HomeActivity`：一个纵向列表，每张卡 = 一个功能入口或一块状态，
// 顶栏只有标题 + overflow 菜单（设置/关于），没有底部 Tab。
//   - 状态卡在前（Shizuku 的 ServerStatusViewHolder）；
//   - 中间是「行动卡」（Shizuku 的 StartRootViewHolder：说明 + 按钮）；
//   - 后面是各个入口卡（Shizuku 的 ManageApps / LearnMore）。
//
// 卡片尺寸/内边距全部来自 `ui/Components.kt`（照着 Shizuku 的 styles.xml 对出来的）。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.bunbeat.nativeapp.AppState

/** 首页列表里的一张卡（用 `LazyColumn` + `items` 渲染，key 用这个）。 */
private data class HomeEntry(
    val key: String,
    val icon: ImageVector,
    val title: String,
    val summary: String?,
    val onClick: () -> Unit,
)

/**
 * 首页（对应 Dart 的 `home_page.dart`，但结构改成了 Shizuku 的卡片流：
 * 曲库/推荐/播放 三个目的地从底部 Tab 变成首页上的卡片 + 顶栏 overflow 菜单）。
 */
@Composable
fun HomePage(app: AppState) {
    val behavior = rememberBunbeatScrollBehavior()
    var menuOpen by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    val library = app.library
    val player = app.player
    val current = player.currentSong
    val fmt = { bpm: Double? -> app.settings.formatBpm(bpm) }
    val target = library.targetBpm

    // —— 卡片 1：曲库状态（对应 Shizuku 的 ServerStatusViewHolder）——
    val songCount = library.songs.size
    val libraryTitle = when {
        library.folderPath == null -> "还没有音乐"
        else -> "$songCount 首音乐"
    }
    val librarySummary = buildString {
        when {
            library.folderPath == null -> append("点进去选择一个本地音乐文件夹并自动分析 BPM")
            else -> {
                append("文件夹：")
                append(library.currentFolderName.ifBlank { library.folderPath })
                if (library.archivedIds.isNotEmpty()) {
                    append(" · 已归档 ")
                    append(library.archivedIds.size)
                    append(" 首")
                }
            }
        }
        val status = library.statusText
        if (library.analyzing && !status.isNullOrBlank()) {
            append("\n")
            append(status)
        }
    }
    val libraryContainer = if (library.folderPath == null) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val libraryContent = if (library.folderPath == null) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    // —— 卡片 2：播放状态（对应 Shizuku 「Start / Restart」那种带按钮的行动卡）——
    val playingTitle = current?.displayTitle() ?: "还没有在播放"
    val playingSummary = if (current != null) {
        val orig = current.originalBpm
        val speed = if (orig != null && orig > 0.0) target / orig else 1.0
        "${fmt(orig)}→${fmt(target)} BPM · 变速 ×${String.format(java.util.Locale.ROOT, "%.2f", speed)}"
    } else {
        "去「推荐」挑几首适合变速的歌，或从「曲库」里选一首开始"
    }

    val entries = listOf(
        HomeEntry(
            key = "archive",
            icon = Icons.Outlined.Inventory2,
            title = "归档",
            summary = if (library.archivedIds.isEmpty()) {
                "长按曲库里的歌可以归档，这里是归档后的列表"
            } else {
                "${library.archivedIds.size} 首已归档 · 可随时放回曲库"
            },
            onClick = { app.nav.openArchive() },
        ),
        HomeEntry(
            key = "help",
            icon = Icons.Outlined.HelpOutline,
            title = "使用说明",
            summary = "曲库 / 推荐 / 播放 / 设置说明，左右滑动切换查看",
            onClick = { showHelp = true },
        ),
        HomeEntry(
            key = "settings",
            icon = Icons.Outlined.Settings,
            title = "设置",
            summary = "外观（含跟随壁纸取色）/ BPM 显示",
            onClick = { app.nav.openSettings() },
        ),
        HomeEntry(
            key = "about",
            icon = Icons.Outlined.Info,
            title = "关于",
            summary = "版本 ${app.update.currentVersionFull()} · 更新公告与源码",
            onClick = { app.nav.openAbout() },
        ),
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            BunbeatTopBar(
                title = "Bunbeat",
                scrollBehavior = behavior,
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("使用说明") },
                            onClick = { menuOpen = false; showHelp = true },
                        )
                        DropdownMenuItem(
                            text = { Text("设置") },
                            onClick = { menuOpen = false; app.nav.openSettings() },
                        )
                        DropdownMenuItem(
                            text = { Text("关于") },
                            onClick = { menuOpen = false; app.nav.openAbout() },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            color = MaterialTheme.colorScheme.background,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .nestedScroll(behavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    start = kScreenHorizontalPadding,
                    end = kScreenHorizontalPadding,
                    top = 8.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 1) 曲库状态卡（可点进去）
                item(key = "library-status") {
                    HomeCard(
                        icon = Icons.Filled.LibraryMusic,
                        title = libraryTitle,
                        summary = librarySummary,
                        iconContainer = libraryContainer,
                        iconContent = libraryContent,
                        onClick = { app.nav.openLibrary() },
                    )
                }

                // 2) 播放状态卡（带播放/暂停 + 打开播放页）
                item(key = "player-status") {
                    HomeCard(
                        icon = Icons.Filled.PlayCircle,
                        title = playingTitle,
                        summary = playingSummary,
                        content = {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = {
                                        if (current == null) {
                                            app.nav.openRecommend()
                                        } else {
                                            player.toggle()
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.width(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        when {
                                            current == null -> "去推荐"
                                            player.isPlaying -> "暂停"
                                            else -> "播放"
                                        },
                                    )
                                }
                                if (current != null) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    TextButton(onClick = { app.nav.openPlayer() }) { Text("打开播放页") }
                                }
                            }
                        },
                    )
                }

                // 3) 行动卡：开始跑步（对应 Shizuku 的 FilledCard）
                item(key = "start") {
                    FilledActionCard(onClick = { app.nav.openRecommend() }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CardIconBadge(
                                icon = Icons.Filled.DirectionsRun,
                                container = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f),
                                content = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "开始跑步",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "选好运动模式，自动挑出适合变速的歌，保持音高连续播放",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
                }

                // 4) 其余入口卡（Shizuku 的 ManageApps / LearnMore 位置）
                items(items = entries, key = { it.key }) { entry ->
                    HomeCard(
                        icon = entry.icon,
                        title = entry.title,
                        summary = entry.summary,
                        onClick = entry.onClick,
                    )
                }
            }
        }
    }

    if (showHelp) {
        AllHelpDialog(onDismiss = { showHelp = false })
    }
}
