@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bunbeat.nativeapp.ui

// Shizuku 风格的 Material 3 组件层。
//
// 这套尺寸/结构是照着 Shizuku（RikkaApps/Shizuku）的 `manager` 模块逐个对出来的：
//   - 卡片    ：MaterialCardView + materialCardViewFilledStyle，cardCornerRadius=28dp、
//               cardElevation=0dp、背景 = elevationOverlayColor@5%(浅)/4%(深)
//               → Compose 里对应 `surfaceContainerHighest`。内容内边距 16dp/20dp。
//   - 图标徽章：@style/CardIcon = oval 背景（内缩 8dp）+ colorPrimaryContainer 底
//               + colorOnPrimaryContainer 图标 → 40dp 圆 + 24dp 图标。
//   - 标题    ：bodyLarge / 16sp；副标题 bodyMedium + onSurfaceVariant，间距 4dp。
//   - 列表行  ：app_list_item.xml = minHeight 64dp、左右 16dp、图标 32dp、
//               图标与文字间距 24dp、标题 bodyLarge、副标题 bodyMedium/14sp。
//   - 顶栏    ：AppBarLayout(liftOnScroll) + MaterialToolbar → Compose 的
//               TopAppBar + pinnedScrollBehavior（滚动才升起容器色）。

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shizuku 卡片的圆角（`cardCornerRadius=28dp`）。 */
val kCardCorner = RoundedCornerShape(28.dp)

/** 卡片内容内边距（Shizuku `Card` style：左右 16dp、上下 20dp）。 */
private val kCardPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp)

/** 首页/子页统一的外边距：卡片之间 4dp、左右 16dp（对应 Shizuku 的 addItemSpacing/addEdgeSpacing）。 */
val kScreenHorizontalPadding = 16.dp

/**
 * 顶栏（对应 Shizuku 的 `AppBarLayout(liftOnScroll) + MaterialToolbar`）。
 *
 * [onBack] 非空时左侧显示返回箭头；[actions] 是右侧的图标按钮（对应 toolbar 的 menu）。
 * 页面把 [scrollBehavior] 接到自己的滚动容器上即可复刻「滚动才升起」的效果。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BunbeatTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    scrollBehavior: BunbeatScrollBehavior? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        scrollBehavior = scrollBehavior?.impl,
    )
}

/**
 * 「滚动才升起」的顶栏行为。
 *
 * 包一层的原因：material3 的 `TopAppBarScrollBehavior` 是实验 API（`@ExperimentalMaterial3Api`），
 * 一旦出现在公共签名里，**每个调用方**都要写 `@OptIn`。这里把它藏在内部，
 * 对外只暴露稳定的 `nestedScrollConnection`，页面侧就不需要任何 opt-in。
 */
class BunbeatScrollBehavior internal constructor(
    internal val impl: TopAppBarScrollBehavior,
) {
    /** 交给页面的滚动容器：`Modifier.nestedScroll(behavior.nestedScrollConnection)`。 */
    val nestedScrollConnection get() = impl.nestedScrollConnection
}

/** 建一个「滚动才升起」的顶栏行为（对应 Shizuku 的 `app:liftOnScroll="true"`）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberBunbeatScrollBehavior(): BunbeatScrollBehavior =
    BunbeatScrollBehavior(TopAppBarDefaults.pinnedScrollBehavior())

/**
 * 圆形图标徽章（对应 Shizuku `@style/CardIcon`）：
 * `colorPrimaryContainer` 圆底 + `colorOnPrimaryContainer` 图标，图标 24dp、圆 40dp。
 */
@Composable
fun CardIconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * 首页卡片（对应 Shizuku 的 `home_item_container.xml` + `CardTitle`/`CardSummary`）。
 *
 * 结构：28dp 圆角填充卡 → 内边距 16/20 → [图标徽章 + 16dp + 标题] → 可选副标题 → 可选内容槽。
 */
@Composable
fun HomeCard(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    iconContainer: Color = MaterialTheme.colorScheme.primaryContainer,
    iconContent: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
    val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    val body: @Composable ColumnScope.() -> Unit = {
        Column(modifier = Modifier.padding(kCardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardIconBadge(icon = icon, container = iconContainer, content = iconContent)
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            if (!summary.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content?.invoke(this)
        }
    }
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = kCardCorner,
            colors = colors,
            elevation = elevation,
            content = body,
        )
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = kCardCorner,
            colors = colors,
            elevation = elevation,
            content = body,
        )
    }
}

/**
 * 「行动卡片」（对应 Shizuku `@style/FilledCard`）：
 * 28dp 圆角 + `colorSecondaryContainer` 底色 + 24dp 内边距，用来放页面里最主要的那个动作。
 */
@Composable
fun FilledActionCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    val body: @Composable ColumnScope.() -> Unit = {
        Column(modifier = Modifier.padding(24.dp), content = content)
    }
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = kCardCorner,
            colors = colors,
            elevation = elevation,
            content = body,
        )
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = kCardCorner,
            colors = colors,
            elevation = elevation,
            content = body,
        )
    }
}

/**
 * 列表行（对应 Shizuku `app_list_item.xml`）：
 * 高 ≥64dp、左右 16dp、上下 16dp、图标 32dp、图标与文字间距 24dp，
 * 标题 bodyLarge、副标题 bodyMedium/14sp、右侧可选控件。
 */
@Composable
fun BunbeatListRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(modifier = Modifier.width(24.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!summary.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(12.dp))
            trailing()
        }
    }
}

/** 分组标题（设置页/首页用的小节标签，主色小字）。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium,
    )
}
