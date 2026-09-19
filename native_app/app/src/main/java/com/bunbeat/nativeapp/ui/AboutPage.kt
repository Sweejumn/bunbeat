package com.bunbeat.nativeapp.ui

// 对应 Dart `ui/about_page.dart`（并参考 `ui/update_flow.dart` 的下载进度弹窗）。
//
// - `AboutPage`        ← `AboutPage`（StatefulWidget）
// - `AboutTopBar`      ← `Scaffold.appBar`（AppBar）
// - `AboutCard`        ← 各 `Card` + `Padding(16)` + `Column` 的重复结构
// - `openRepo`         ← `_AboutPageState._openRepo`
// - `formatMb`         ← `update_flow.dart` 里的 `toStringAsFixed(1)` 兆字节文案
//
// Dart `_formatTime`（更新时间）没有搬：原生侧 `ReleaseInfo` 不带发布时间字段，
// 因此「更新时间：…」这一行也没有输出（详见交付说明）。

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.bunbeat.nativeapp.AppState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 关于页：应用图标/名称、版本、简介、最新版本公告、致谢与开源，
 * 以及「检查更新」与「查看 GitHub 源代码」入口。
 *
 * [onBack] 返回设置页（对应 Dart 的 `Navigator.pop`）。
 */
@Composable
fun AboutPage(app: AppState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    // 对应 Dart `_loadVersion()`：原生侧版本信息可以同步取到，但保留 Dart 的
    // 「先显示占位、取到再替换」行为。
    var version by remember { mutableStateOf<String?>(null) }
    // 对应 Dart `_loading`（初始为 true）。
    var loading by remember { mutableStateOf(app.update.latest == null) }

    LaunchedEffect(Unit) {
        version = app.update.currentVersionFull()
    }

    // 对应 Dart `_loadLatest()`：只拉「最新发布信息」，不做「检查更新」。
    // 这里刻意不调用 `app.update.check(manual = false)`——那条路径在有新版时会把
    // `pending` 置位，从而弹出 MainActivity 的「发现新版本」对话框，与 Dart 的
    // AboutPage 行为（只 fetchLatestRelease）不一致。
    LaunchedEffect(Unit) {
        app.update.refreshLatest()
        // `UpdateController.latest` 是 private set，`refreshLatest()` 也不返回结果，
        // 只能轮询等它落地；最多等 12 秒，避免网络卡住时永远转圈。
        var waited = 0L
        while (app.update.latest == null && waited < 12_000L) {
            delay(250L)
            waited += 250L
        }
        loading = false
    }

    // 启动图标：Dart 用 `assets/ic_launcher.png`；原生工程没有这份资源也没在
    // manifest 声明 `android:icon`，于是直接向 PackageManager 要当前应用图标
    // （内容等同桌面图标），失败则退回品牌图标占位。
    val iconSizePx = with(LocalDensity.current) { 88.dp.roundToPx() }
    val icon = remember(iconSizePx) {
        runCatching {
            app.context.packageManager
                .getApplicationIcon(app.context.packageName)
                .toBitmap(iconSizePx, iconSizePx)
                .asImageBitmap()
        }.getOrNull()
    }

    val latest = app.update.latest

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AboutTopBar(title = "关于", onBack = onBack) },
    ) { padding ->
        // Dart 侧是 `ListView(padding: EdgeInsets.all(24))`。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            // 图标与名称（图标与桌面启动图标一致）
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(88.dp)
                            .clip(RoundedCornerShape(20.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.LibraryMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(44.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Bunbeat",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(modifier = Modifier.height(4.dp))
                val shown = version
                Text(
                    if (shown != null) "版本 $shown" else "版本 …",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(modifier = Modifier.height(24.dp))

            // 简介
            AboutCard {
                Text("简介", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Bunbeat 是一款跑步音乐播放器：选择本地文件夹直接读取音乐，自动识别 BPM，" +
                        "按你选的节奏变速（保持音高）连续播放，并配合节拍器帮你踩点跑。" +
                        "完全离线运行，音乐文件不离开你的设备。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            // 最新版本公告
            AboutCard {
                Text("最新版本公告", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                if (loading) {
                    Text(
                        "正在检查最新版本…",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.outline,
                        ),
                    )
                } else if (latest == null) {
                    Text(
                        "获取最新版本信息失败，请确认网络后再试。",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.outline,
                        ),
                    )
                } else {
                    Text(
                        "v${latest.version}",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    // 「更新时间：…」（对应 Dart `_formatTime(publishedAt)`）。
                    val published = com.bunbeat.nativeapp.update.UpdateService
                        .formatPublishedAt(latest.publishedAt)
                    if (published != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "更新时间：$published",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.outline,
                            ),
                        )
                    }
                    if (latest.notes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(latest.notes, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            // 致谢与开源
            AboutCard {
                Text("致谢与开源", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "基于 Flutter 构建，播放与变速使用 just_audio / ExoPlayer，" +
                        "BPM 分析使用本地频谱（FFT）实现。界面与交互对齐 Web 版 RUN BPM。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // 检查更新 / 查看源代码
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
            ) {
                ListItem(
                    modifier = Modifier.clickable {
                        // Dart `UpdateFlow.checkAndPrompt(context)`（manual = true）：
                        // 有新版 → `pending` 置位，由 MainActivity 弹「发现新版本」；
                        // 已最新/失败 → UpdateController 走 Snackbar。
                        scope.launch { app.update.check(manual = true) }
                    },
                    headlineContent = { Text("检查更新") },
                    // 检查失败时 `statusText` 为「检查更新失败」，直接替换副标题提示用户。
                    supportingContent = {
                        Text(app.update.statusText ?: "从 GitHub Releases 检查并安装新版本")
                    },
                    leadingContent = {
                        Icon(Icons.Filled.SystemUpdateAlt, contentDescription = null)
                    },
                    trailingContent = {
                        Icon(Icons.Filled.ChevronRight, contentDescription = null)
                    },
                )
                HorizontalDivider(thickness = 1.dp)
                ListItem(
                    modifier = Modifier.clickable { openRepo(app) },
                    headlineContent = { Text("查看 GitHub 源代码") },
                    supportingContent = { Text("在浏览器中查看项目源码与更新记录") },
                    leadingContent = {
                        Icon(Icons.Filled.Code, contentDescription = null)
                    },
                    trailingContent = {
                        Icon(Icons.Filled.ChevronRight, contentDescription = null)
                    },
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "仅供学习与个人使用\n本 App 不收集任何用户数据",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.outline,
                ),
            )
        }
    }

    // 下载进度弹窗（对应 `update_flow.dart` 的 `_downloadAndInstall` 对话框）。
    // 原生侧下载由 `UpdateController.downloadAndInstall()` 驱动，这里只做展示。
    val downloading = app.update.downloading
    if (downloading) {
        val info = app.update.pending ?: latest
        val total = info?.sizeBytes ?: 0L
        val received = (total.toFloat() * app.update.progress).toLong()
        AlertDialog(
            onDismissRequest = { },
            title = { Text("正在下载更新") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { app.update.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        if (total > 0L) "${formatMb(received)} / ${formatMb(total)}" else "准备下载…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            // 对应 Dart 下载弹窗里的「取消」按钮（现在 UpdateController 有 cancelDownload 了）。
            confirmButton = {
                TextButton(onClick = { app.update.cancelDownload() }) { Text("取消") }
            },
        )
    }
}

/**
 * 顶栏（对应 Dart `AppBar(title: Text('关于'))`）。
 *
 * 手写而不用 material3 `TopAppBar`：Flutter 的 AppBar 高度是 56dp，
 * M3 TopAppBar 是 64dp；手写能严格对齐 Dart 的高度与左侧返回键位置。
 */
@Composable
private fun AboutTopBar(title: String, onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
    }
}

/**
 * 统一的三段式卡片（对应 Dart `Card(child: Padding(EdgeInsets.all(16), child: Column(...)))`）。
 * Dart 的 Card 默认外边距是 4dp（四边），这里保持一致。
 */
@Composable
private fun AboutCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.Start,
            content = content,
        )
    }
}

/** 打开 GitHub 项目主页（对应 Dart `_AboutPageState._openRepo`，走系统浏览器）。 */
private fun openRepo(app: AppState) {
    val url = "https://github.com/Sweejumn/bunbeat"
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        app.context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // 与 Dart `launchUrl` 返回 false 的分支一致。
        app.snackbar("无法打开浏览器，请稍后再试")
    } catch (_: Throwable) {
        // 与 Dart catch 分支一致。
        app.snackbar("无法打开链接：$url")
    }
}

/** 兆字节文案（对应 Dart `(bytes / 1024 / 1024).toStringAsFixed(1) + ' MB'`）。 */
private fun formatMb(bytes: Long): String =
    String.format(Locale.ROOT, "%.1f", bytes.toDouble() / 1024.0 / 1024.0) + " MB"
