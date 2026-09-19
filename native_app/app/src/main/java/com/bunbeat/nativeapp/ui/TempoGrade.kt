package com.bunbeat.nativeapp.ui

// 对应 Dart `ui/tempo_grade.dart`（Web 版 TempoArrow / RecommendPanel 图例）。
//
// 按「与原 BPM 的相对差百分比」分档：
//   差 <3%   绿  =    （几乎无需变速）
//   差 3–5%  绿  ↑/↓
//   差 5–8%  琥珀 ↑/↓
//   差 8–12% 红  ↑/↓（勉强可变速）
//   差 >12%  红  ✕   （不适合变速，默认不自动选中）

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs

// —— Dart `Colors.*` 的等价取值（Material 2 调色板原值）——
private val kGrey = Color(0xFF9E9E9E)
private val kGrey600 = Color(0xFF757575)
private val kGreenAccent = Color(0xFF69F0AE)
private val kGreen800 = Color(0xFF2E7D32)
private val kAmber = Color(0xFFFFC107)
private val kAmber800 = Color(0xFFFF8F00)
private val kRedAccent = Color(0xFFFF5252)
private val kRed800 = Color(0xFFC62828)

/** 变速分级结果（对应 Dart `TempoGrade`）。 */
data class TempoGrade(
    val color: Color,
    val symbol: String,
    val absPct: Double,
    val pctLabel: String,
)

/**
 * [orig] 为歌曲原 BPM，[target] 为目标 BPM。
 * 方向：原 BPM 高于目标 → 需放慢 ↓；低于目标 → 需加快 ↑。
 * [dark] 让分级色随深浅色模式调整：浅色用深色可读色，深色用高亮色。
 */
fun gradeTempo(orig: Double?, target: Double, dark: Boolean): TempoGrade {
    // 浅色模式用偏深的颜色保证在白底上可读；深色模式用高亮色。
    val none = if (dark) kGrey else kGrey600
    val green = if (dark) kGreenAccent else kGreen800
    val amber = if (dark) kAmber else kAmber800
    val red = if (dark) kRedAccent else kRed800

    if (orig == null || orig <= 0.0) {
        return TempoGrade(color = none, symbol = "—", absPct = 0.0, pctLabel = "—")
    }
    val absPct = abs(orig - target) / orig * 100.0
    val signed = (orig - target) / orig * 100.0
    val pctLabel = if (abs(signed) < 0.05) {
        "0%"
    } else {
        // Dart `toStringAsFixed(1)`：固定一位小数。
        val sign = if (signed > 0) "+" else ""
        sign + String.format(java.util.Locale.ROOT, "%.1f", signed) + "%"
    }
    val arrow = if (orig > target) "↓" else "↑"
    return when {
        absPct <= 3.0 -> TempoGrade(green, "=", absPct, pctLabel)
        absPct <= 5.0 -> TempoGrade(green, arrow, absPct, pctLabel)
        absPct <= 8.0 -> TempoGrade(amber, arrow, absPct, pctLabel)
        absPct <= 12.0 -> TempoGrade(red, arrow, absPct, pctLabel)
        else -> TempoGrade(red, "✕", absPct, pctLabel)
    }
}

/**
 * 在 Composable 里取分级：自动按当前主题底色判断深浅色
 * （对应 Dart `Theme.of(context).brightness`，但这里读实际背景色，
 * 用户在设置里强制浅/深色时也能正确取色）。
 */
@Composable
fun gradeTempo(orig: Double?, target: Double): TempoGrade =
    gradeTempo(orig, target, dark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
