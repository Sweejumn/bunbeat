package com.bunbeat.nativeapp.ui

// 对应 Dart `ui/marquee_text.dart`。
//
// 单行文字若超出可用宽度则自动水平滚动显示（跑马灯/横滚），否则静止显示为单行省略。
// 用于曲库/推荐里较长的歌名。
//
// Dart 用 SingleChildScrollView + 两份相同文字（中间隔一段空隙）+ Ticker 每帧改滚动位置；
// Compose 侧等价做法是 `Modifier.horizontalScroll` + `withFrameNanos` 逐帧 `scrollTo`，
// 位移对「文字宽 + 空隙」取模，回绕时第二份恰好接上第一份，视觉上无缝。
// 滚动期间禁用用户拖动（`enabled = false`），与 Dart 的 `NeverScrollableScrollPhysics` 一致。

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// 两段文字之间的空隙（对应 Dart `_kGap`）。
private val kMarqueeGap = 48.dp

// 滚动速度（像素/秒，对应 Dart `_kSpeed`）。
private const val kMarqueeSpeed = 90.0

/**
 * 单行文字：放得下就省略号截断，放不下就横向滚动。
 *
 * @param text 要显示的文字
 * @param style 文字样式；默认取当前 `LocalTextStyle`（等价 Dart 的 `DefaultTextStyle.of(context).style`）
 */
@Composable
fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = modifier) {
        val maxWidthPx = constraints.maxWidth

        // 量一次文字实际宽度（Dart 用 TextPainter.layout() 做同样的事）。
        val textWidthPx = remember(text, style, maxWidthPx) {
            measurer.measure(
                text = AnnotatedString(text),
                style = style,
                maxLines = 1,
                softWrap = false,
            ).size.width
        }

        // 文字未超出宽度：静止显示单行省略。
        if (maxWidthPx <= 0 || textWidthPx <= maxWidthPx) {
            Text(
                text = text,
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            return@BoxWithConstraints
        }

        // 一周期滚动的距离 = 文字宽 + 空隙。
        val gapPx = with(LocalDensity.current) { kMarqueeGap.toPx() }
        val cycle = textWidthPx + gapPx
        val scroll = rememberScrollState()
        if (cycle > 0f) {
            LaunchedEffect(cycle) {
                // 位置随流逝时间线性增长，对一整段距离取模；回绕时内容无缝衔接。
                val start = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    val seconds = (now - start) / 1_000_000_000.0
                    val pos = ((seconds * kMarqueeSpeed) % cycle).toFloat()
                    scroll.scrollTo(pos.toInt())
                }
            }
        }

        // Row 内容宽度 = 2 × 文字宽 + 空隙，滚动范围恰为一个 cycle。
        Row(modifier = Modifier.horizontalScroll(state = scroll, enabled = false)) {
            Text(text = text, style = style, maxLines = 1, softWrap = false)
            Spacer(modifier = Modifier.width(kMarqueeGap))
            Text(text = text, style = style, maxLines = 1, softWrap = false)
        }
    }
}
