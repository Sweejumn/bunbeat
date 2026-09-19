package com.bunbeat.nativeapp.ui

// 对应 Dart `ui/help_dialog.dart`。
//
// - 各页右上角打开：`showHelpDialog(section)`，只显示本页说明；
// - 设置页「使用说明」打开：`showAllHelpDialog()`，顶部 TabRow 分段、正文可左右滑动。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/** 使用说明条目：一个小标题 + 一行正文（对应 Dart `HelpItem`）。 */
data class HelpItem(val title: String, val body: String)

/**
 * 一个页面的使用说明集合，按页面分组（对应 Dart `HelpSection`）。
 * 各页右上角打开时只显示本页说明；设置页打开时用 [all] 滑动切换全部。
 */
class HelpSection(val label: String, val items: List<HelpItem>) {
    companion object {
        val library = HelpSection(
            "曲库",
            listOf(
                HelpItem(
                    "添加音乐",
                    "点顶部「添加音乐」可快速选择预设音源（网易云音乐 / QQ音乐 / 酷狗音乐），" +
                        "或选「自定义文件夹」手动挑选目录；选定后自动扫描导入其中的音乐。",
                ),
                HelpItem(
                    "长按操作",
                    "长按任一首歌可：归档（从曲库与推荐隐藏，右上「归档」可查看并放回）、" +
                        "重新检测 BPM、BPM ×2、手动修改 BPM。",
                ),
                HelpItem(
                    "排序与搜索",
                    "顶部排序按钮可切换顺序（默认 / 标题 / BPM / 时长）；点搜索按钮可按歌名、歌手筛选。",
                ),
                HelpItem(
                    "移除歌曲",
                    "曲库不直接删除本地文件；不想要的歌可长按「归档」隐藏，需要时在归档页一键放回。",
                ),
            ),
        )

        val recommend = HelpSection(
            "推荐",
            listOf(
                HelpItem(
                    "选择节奏区间",
                    "拖动滑块会自动切换运动模式（走路/慢跑/跑步/快跑）；也可点上方模式卡片快速切换。" +
                        "想用区间外的节奏，直接在右侧输入框手动输入 BPM（40–300）。",
                ),
                HelpItem(
                    "推荐标记含义",
                    "= 与原 BPM 差 <3%（几乎不用变速）；↑/↓ 3–8%（轻微变速）；" +
                        "红 8–12%（变速较多）；✕ >12%（不适合变速，默认不勾选）。",
                ),
                HelpItem(
                    "变速并播放",
                    "勾选歌曲后点底部「变速并播放」，会保持音高把每首变速到目标 BPM 连续播放；" +
                        "可用上方「自动勾选可变速 / 全选 / 清空」调整勾选。",
                ),
                HelpItem(
                    "与曲库联动",
                    "本页推荐来自「曲库」当前文件夹；在曲库「添加音乐」或长按「归档」会同步影响这里的推荐结果。",
                ),
            ),
        )

        val player = HelpSection(
            "播放",
            listOf(
                HelpItem("变速播放", "保持歌曲音高，把当前歌曲变速到目标 BPM 连续播放。"),
                HelpItem("节拍器", "开启节拍器并选择音效，可跟随节拍跑；支持音量调节与打拍校准。"),
                HelpItem("播放模式", "左下角按钮点按循环切换：列表循环 → 单曲循环 → 随机。"),
                HelpItem("播放列表", "右下角打开播放列表：点选跳歌、长按拖动排序、删除单首、清空。"),
            ),
        )

        val settings = HelpSection(
            "设置",
            listOf(
                HelpItem("外观", "选择主题（跟随系统 / 浅色 / 深色）与主题色，实时生效。"),
                HelpItem("BPM 显示", "开关「BPM 保留两位小数」，控制曲库/推荐/播放页的 BPM 显示精度。"),
            ),
        )

        /** 全部页面的说明，供设置页「使用说明」滑动切换查看。 */
        val all = listOf(library, recommend, player, settings)
    }
}

/** 单页说明弹窗（对应 Dart `HelpDialog.show`）。 */
@Composable
fun HelpDialogContent(section: HelpSection, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("使用说明 · ${section.label}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                HelpItemsList(section)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}

/** 全部说明弹窗（对应 Dart `HelpDialog.showAll`）：顶部分段 + 正文左右滑动。 */
@Composable
fun AllHelpDialog(onDismiss: () -> Unit) {
    val sections = HelpSection.all
    val pagerState = rememberPagerState(pageCount = { sections.size })
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "使用说明",
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 顶部可点击的分段条（左滑右滑与点击均可切换）。
                TabRow(selectedTabIndex = pagerState.currentPage) {
                    sections.forEachIndexed { i, s ->
                        Tab(
                            selected = pagerState.currentPage == i,
                            onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                            text = { Text(s.label) },
                        )
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    // 固定高度 420dp（对应 Dart `SizedBox(height: 420)`），
                    // 再减去标题/分段条占用的空间。
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                ) { page ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        HelpItemsList(sections[page])
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("知道了") }
                }
            }
        }
    }
}

/** 单个页面的说明条目列表（标题 + 正文），供单页与全部模式共用。 */
@Composable
private fun HelpItemsList(section: HelpSection) {
    val titleStyle = MaterialTheme.typography.bodySmall.copy(
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
    )
    val bodyStyle = MaterialTheme.typography.bodySmall.copy(
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (item in section.items) {
            Column {
                Text(item.title, style = titleStyle)
                Spacer(modifier = Modifier.height(2.dp))
                Text(item.body, style = bodyStyle)
            }
        }
    }
}
