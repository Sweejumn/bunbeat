package com.bunbeat.nativeapp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Recommend
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Recommend
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.bunbeat.nativeapp.AppState

/** 底部 Tab 定义（对应 Dart `HomePage` 的三个 destination）。 */
private data class HomeTab(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val kHomeTabs = listOf(
    HomeTab("曲库", Icons.Outlined.LibraryMusic, Icons.Filled.LibraryMusic),
    HomeTab("推荐", Icons.Outlined.Recommend, Icons.Filled.Recommend),
    HomeTab("播放", Icons.Outlined.PlayCircleOutline, Icons.Filled.PlayCircle),
)

/**
 * 首页：底部三个 Tab（曲库 / 推荐 / 播放），对应 Dart `home_page.dart`。
 *
 * Dart 用 `IndexedStack` 保留各页状态；这里用 `when` 切换，列表滚动位置由各页内部的
 * `rememberLazyListState`（本身可保存）自行保持，观感等价。
 */
@Composable
fun HomePage(app: AppState) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                kHomeTabs.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = {
                            // 切页时收起上一条提示，避免挡住底部操作（对应 Dart 的 hideCurrentSnackBar）。
                            app.consumeSnackbar()
                            tab = index
                        },
                        icon = {
                            Icon(
                                imageVector = if (tab == index) item.selectedIcon else item.icon,
                                contentDescription = item.label,
                            )
                        },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> LibraryPage(app)
                1 -> RecommendPage(app)
                else -> PlayerPage(app)
            }
        }
    }
}
