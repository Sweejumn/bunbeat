package com.bunbeat.nativeapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** 预设主题色（对应 Dart `kThemeColors`，默认天蓝 = Bunbeat 品牌色）。 */
data class ThemeColorOption(val name: String, val color: Color)

val kThemeColors: List<ThemeColorOption> = listOf(
    ThemeColorOption("天蓝", Color(0xFF38BDF8)),
    ThemeColorOption("薄荷绿", Color(0xFF34D399)),
    ThemeColorOption("活力橙", Color(0xFFF97316)),
    ThemeColorOption("热情红", Color(0xFFEF4444)),
    ThemeColorOption("靛蓝", Color(0xFF6366F1)),
    ThemeColorOption("紫罗兰", Color(0xFF8B5CF6)),
    ThemeColorOption("樱花粉", Color(0xFFEC4899)),
    ThemeColorOption("青柠", Color(0xFF84CC16)),
    ThemeColorOption("琥珀金", Color(0xFFF59E0B)),
)

/** 应用基础配色（深色为主，取自 Flutter 版深色配色）。 */
object BunbeatColors {
    val bg = Color(0xFF0E0E12)
    val surface = Color(0xFF17171E)
    val surfaceAlt = Color(0xFF1F1F29)
    val textPrimary = Color(0xFFF2F2F5)
    val textSecondary = Color(0xFF9A9AA8)
    val divider = Color(0xFF2A2A36)
    val ok = Color(0xFF4CD07D)
    val warn = Color(0xFFFFB454)
    val err = Color(0xFFFF6B6B)

    /** 浅色模式下的底色。 */
    val bgLight = Color(0xFFF6F7FB)
    val surfaceLight = Color(0xFFFFFFFF)
    val surfaceAltLight = Color(0xFFEDEFF6)
    val textPrimaryLight = Color(0xFF14141A)
    val textSecondaryLight = Color(0xFF5C5C6B)
    val dividerLight = Color(0xFFD8DAE4)
}

/**
 * 用种子色生成配色（近似 Flutter 的 `ColorScheme.fromSeed`）。
 *
 * Flutter 侧用 M3 的 HCT 色调板；这里用「基色 + 种子色混色」近似，
 * 观感一致（深色底 + 彩色强调），不需要把 HCT 整套搬过来。
 */
fun seedColorScheme(seed: Color, dark: Boolean): ColorScheme {
    return if (dark) {
        val surface = lerp(BunbeatColors.bg, seed, 0.06f)
        val surfaceAlt = lerp(BunbeatColors.bg, seed, 0.12f)
        darkColorScheme(
            primary = seed,
            onPrimary = if (seed.luminance() > 0.6f) Color(0xFF10121A) else Color.White,
            primaryContainer = lerp(BunbeatColors.bg, seed, 0.35f),
            onPrimaryContainer = BunbeatColors.textPrimary,
            secondary = lerp(seed, Color.White, 0.25f),
            onSecondary = Color(0xFF10121A),
            background = surface,
            onBackground = BunbeatColors.textPrimary,
            surface = surface,
            onSurface = BunbeatColors.textPrimary,
            surfaceVariant = surfaceAlt,
            onSurfaceVariant = BunbeatColors.textSecondary,
            outline = BunbeatColors.divider,
            error = BunbeatColors.err,
        )
    } else {
        val surface = BunbeatColors.surfaceLight
        val surfaceAlt = lerp(BunbeatColors.surfaceAltLight, seed, 0.10f)
        lightColorScheme(
            primary = lerp(seed, Color.Black, 0.18f),
            onPrimary = Color.White,
            primaryContainer = lerp(Color.White, seed, 0.28f),
            onPrimaryContainer = Color(0xFF10121A),
            secondary = lerp(seed, Color.Black, 0.30f),
            background = BunbeatColors.bgLight,
            onBackground = BunbeatColors.textPrimaryLight,
            surface = surface,
            onSurface = BunbeatColors.textPrimaryLight,
            surfaceVariant = surfaceAlt,
            onSurfaceVariant = BunbeatColors.textSecondaryLight,
            outline = BunbeatColors.dividerLight,
            error = Color(0xFFD32F2F),
        )
    }
}

/**
 * 全局主题。
 * [darkMode] 为 null 表示跟随系统（对应 Dart 的 `ThemeMode.system`）。
 */
@Composable
fun BunbeatTheme(
    seed: Color,
    darkMode: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val dark = darkMode ?: isSystemInDarkTheme()
    MaterialTheme(colorScheme = seedColorScheme(seed, dark), content = content)
}

/** 当前是否深色（便于页面取用）。 */
@Composable
fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f
