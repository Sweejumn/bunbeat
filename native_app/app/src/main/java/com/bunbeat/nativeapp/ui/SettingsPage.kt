package com.bunbeat.nativeapp.ui

// 对应 Dart `ui/settings_page.dart`。
//
// - `SettingsPage`        ← `SettingsPage`（StatelessWidget）
// - `SectionHeader`       ← `SettingsPage._sectionHeader`
// - `ThemeModeSelector`   ← `_ThemeModeSelector`
// - `ColorSwatch`         ← `_ColorSwatch`
// - `SettingsTopBar`      ← `Scaffold.appBar`（AppBar）
// - `ThemeModeEntry`      ← `_ThemeModeSelector.entries` 里的三元组

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bunbeat.nativeapp.AppState
import com.bunbeat.nativeapp.store.ThemeModeSetting
import com.bunbeat.nativeapp.ui.theme.kThemeColors

/**
 * 设置页：外观（主题模式 + 主题色）+ BPM 显示格式 + 使用说明/关于入口。
 *
 * [onBack] 返回首页（对应 Dart 的 `Navigator.pop`）；「关于应用」通过
 * [AppState.nav]`.openAbout()` 跳转（对应 Dart `Navigator.push(AboutPage)`）。
 */
@Composable
fun SettingsPage(app: AppState, onBack: () -> Unit) {
    // 「使用说明」弹窗开关（对应 Dart `HelpDialog.showAll` 的即时弹窗）。
    var showAllHelp by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { SettingsTopBar(title = "设置", onBack = onBack) },
    ) { padding ->
        // Dart 侧是 ListView；这里条目固定且很少，用可滚动的 Column 等价实现。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // 外观
            SectionHeader("外观")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    // Dart `Card(margin: EdgeInsets.symmetric(horizontal: 12))`：
                    // 显式 margin 会覆盖 Card 默认的 4dp 四边距，故这里只有水平 12dp。
                    .padding(horizontal = 12.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text("主题", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(12.dp))
                    // 「跟随系统」选项特意更宽（flex 2），其余两个等宽（flex 1），
                    // 让默认推荐项更醒目。
                    ThemeModeSelector(app)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("主题色", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    // 主题色选择：一行行排列的色块，选中的带对勾。
                    // Dart `Wrap(spacing: 12, runSpacing: 12)` ↔ `FlowRow`。
                    ColorSwatchWrap(app)
                }
            }

            // BPM 显示格式
            SectionHeader("BPM 显示")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                // Dart `SwitchListTile`：整行可点 + 右侧开关。
                // material3 没有 SwitchListTile，用 ListItem + Switch 手工拼。
                ListItem(
                    modifier = Modifier.clickable {
                        app.settings.setTwoDecimals(!app.settings.bpmTwoDecimals)
                    },
                    headlineContent = { Text("BPM 保留两位小数") },
                    supportingContent = { Text("曲库/推荐/播放页的 BPM 显示两位小数；关闭则按整数显示。") },
                    leadingContent = {
                        Icon(Icons.Filled.Numbers, contentDescription = null)
                    },
                    trailingContent = {
                        Switch(
                            checked = app.settings.bpmTwoDecimals,
                            onCheckedChange = { app.settings.setTwoDecimals(it) },
                        )
                    },
                )
            }

            // 帮助与关于
            SectionHeader("帮助与关于")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                ListItem(
                    modifier = Modifier.clickable { showAllHelp = true },
                    headlineContent = { Text("使用说明") },
                    supportingContent = { Text("曲库/推荐/播放/设置说明，左右滑动切换查看") },
                    leadingContent = {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null)
                    },
                    trailingContent = {
                        Icon(Icons.Filled.ChevronRight, contentDescription = null)
                    },
                )
                HorizontalDivider(thickness = 1.dp)
                ListItem(
                    modifier = Modifier.clickable { app.nav.openAbout() },
                    headlineContent = { Text("关于应用") },
                    supportingContent = { Text("版本、简介、最新版本公告、检查更新与源码") },
                    // Dart `Icons.info_outline`；material-icons 里没有 `InfoOutline`，
                    // 用同一枚图标的线框变体 `Icons.Outlined.Info` 等价替代。
                    leadingContent = {
                        Icon(Icons.Outlined.Info, contentDescription = null)
                    },
                    trailingContent = {
                        Icon(Icons.Filled.ChevronRight, contentDescription = null)
                    },
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // 「使用说明」全部分段弹窗（与 Dart `HelpDialog.showAll(context)` 一致）。
    if (showAllHelp) {
        AllHelpDialog(onDismiss = { showAllHelp = false })
    }
}

/**
 * 顶栏（对应 Dart `AppBar(title: Text('设置'))`）。
 *
 * 这里手写而不是用 material3 `TopAppBar`：Flutter 的 AppBar 高度是 56dp，
 * 而 M3 TopAppBar 是 64dp；手写能严格对齐 Dart 的高度与左侧返回键位置。
 */
@Composable
private fun SettingsTopBar(title: String, onBack: () -> Unit) {
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

/** 分组标题（对应 Dart `SettingsPage._sectionHeader`）。 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge.copy(
            color = MaterialTheme.colorScheme.primary,
        ),
    )
}

/** 主题模式选择条的一个分段（对应 Dart 里的 `(ThemeMode, String, int)` 三元组）。 */
private data class ThemeModeEntry(
    val mode: ThemeModeSetting,
    val label: String,
    /** 对应 Dart `Expanded(flex: ...)`。 */
    val flex: Float,
)

private val kThemeModeEntries = listOf(
    ThemeModeEntry(ThemeModeSetting.SYSTEM, "跟随系统", 2f),
    ThemeModeEntry(ThemeModeSetting.LIGHT, "浅色", 1f),
    ThemeModeEntry(ThemeModeSetting.DARK, "深色", 1f),
)

/**
 * 三段主题选择条（对应 Dart `_ThemeModeSelector`）。
 * 用 Row + weight 而不是 SegmentedButton，因为后者强制各段等宽，无法让某项更长。
 */
@Composable
private fun ThemeModeSelector(app: AppState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(3.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            for (entry in kThemeModeEntries) {
                val selected = app.settings.themeMode == entry.mode
                // Dart 的 `Material` + `InkWell` 在 material3 里没有对应组件，
                // 用「背景色 + clickable」等价实现（ripple 由主题的 LocalIndication 提供）。
                Box(
                    modifier = Modifier
                        .weight(entry.flex)
                        .padding(1.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Transparent
                            },
                            shape = RoundedCornerShape(9.dp),
                        )
                        .clickable { app.settings.setThemeMode(entry.mode) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Dart 里 `fontWeight: selected ? bold : null` 会保留 labelLarge 自身的字重，
                    // 所以这里显式取回 base.fontWeight，避免 TextStyle.copy(fontWeight = null)
                    // 把字重清成「未指定」而回落到 LocalTextStyle（bodyLarge，Normal）。
                    val base = MaterialTheme.typography.labelLarge
                    Text(
                        entry.label,
                        textAlign = TextAlign.Center,
                        style = base.copy(
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = if (selected) FontWeight.Bold else base.fontWeight,
                        ),
                    )
                }
            }
        }
    }
}

/** 主题色色块的整体排布（对应 Dart 的 `Wrap(spacing: 12, runSpacing: 12)`）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorSwatchWrap(app: AppState) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (opt in kThemeColors) {
            ColorSwatch(
                color = opt.color,
                name = opt.name,
                // Dart 比较的是 `toARGB32()`；Compose 的 `Color` 是值类，
                // 直接相等比较等价（kThemeColors 里的颜色都是不透明 ARGB）。
                selected = app.settings.seed == opt.color,
                onTap = { app.settings.setSeed(opt.color) },
            )
        }
    }
}

/** 单个主题色色块（对应 Dart `_ColorSwatch`）：圆形色点 + 名字，选中显示对勾。 */
@Composable
private fun ColorSwatch(
    color: Color,
    name: String,
    selected: Boolean,
    onTap: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(52.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onTap)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(color = color, shape = CircleShape)
                .border(
                    width = 2.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        Color.Transparent
                    },
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            name,
            style = TextStyle(
                fontSize = 11.sp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ),
        )
    }
}
