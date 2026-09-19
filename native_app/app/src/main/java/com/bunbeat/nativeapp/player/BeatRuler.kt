package com.bunbeat.nativeapp.player

// 拍点标尺（对应 Dart `android_app/lib/ui/beat_ruler.dart`）。
//
// 契约签名带 durationSec 与 onSeek，因此这里是「整首时间轴」形态：0..duration 映射到 0..宽度，
// 拍点按比例落位，播放头随 positionSec 移动，点击 / 横向拖动可 seek（Dart 版是 ±4 秒滚动窗口 +
// 居中播放头、不可点；本实现把同一套视觉语言搬到了全曲标尺上）。
//
// 视觉对齐 Dart：黑 35% 底 + 8dp 圆角 + white12 描边、主题色竖线表示拍点（宽 2dp、上下各留 4dp）、
// 纯白竖线表示当前播放位置。原生增强：每小节第一拍（4/4，下标 % 4 == 0）画成并排的「小节线」
// —— 更高更亮，与节拍器重音同一套模型；另加一层很淡的「已播放区域」底色。

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 拍点标尺。
 *
 * @param beatTimes 拍点时间轴（秒，升序）；null / 空表示该曲没有拍点信息。
 * @param durationSec 曲长（秒）；<= 0 表示时长未知，此时标尺不可 seek。
 * @param positionSec 当前播放位置（秒）。
 * @param modifier 外层修饰符。
 * @param onSeek 点击 / 拖动标尺时回调目标秒数。
 * @param tapMarks 打拍校准标记（媒体时间秒，仅保留最近 20 个）；对应 Dart `BeatRuler.tapMarks`，
 *   用琥珀色画在标尺上，让用户看到自己刚才敲在哪。
 */
@Composable
fun BeatRuler(
    beatTimes: List<Double>?,
    durationSec: Double,
    positionSec: Double,
    modifier: Modifier = Modifier,
    onSeek: (Double) -> Unit = {},
    tapMarks: List<Double> = emptyList(),
) {
    val colors = MaterialTheme.colorScheme
    // 时长未知时（还没 prepare 完 / 容器读不出时长）用「最后一个拍点 / 当前位置」兜底，
    // 保证标尺仍能画出线条，只是不允许 seek。
    val beats = beatTimes.orEmpty()
    val seekable = durationSec > 0.0
    val total = if (seekable) {
        durationSec
    } else {
        maxOf(beats.lastOrNull() ?: 0.0, positionSec, 1.0)
    }
    val playX = ((positionSec / total).coerceIn(0.0, 1.0)).toFloat()
    val latestOnSeek = rememberUpdatedState(onSeek)
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .pointerInput(seekable, total) {
                if (!seekable) return@pointerInput
                detectTapGestures { offset ->
                    latestOnSeek.value(secAt(offset.x, size.width.toFloat(), total))
                }
            }
            .pointerInput(seekable, total) {
                if (!seekable) return@pointerInput
                detectHorizontalDragGestures { change, _ ->
                    latestOnSeek.value(secAt(change.position.x, size.width.toFloat(), total))
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // 已播放区域（很淡的主题色底，原生增强，用于一眼看出进度）。
            if (playX > 0f) {
                drawRect(
                    color = colors.primary.copy(alpha = 0.12f),
                    topLeft = Offset.Zero,
                    size = Size(w * playX, h),
                )
            }

            // 拍点竖线：每小节第一拍画满高、更亮更粗；其余拍点居中短竖线。
            val minGap = 2.dp.toPx()
            val weakStroke = 1.dp.toPx()
            val barStroke = 1.6.dp.toPx()
            val weakColor = colors.primary.copy(alpha = 0.55f)
            val barColor = colors.primary.copy(alpha = 0.95f)
            val topPad = 4.dp.toPx()
            val weakInset = h * 0.32f
            var lastX = Float.NEGATIVE_INFINITY
            for (i in beats.indices) {
                val t = beats[i]
                if (t < 0.0 || t > total) continue
                val x = (t / total).toFloat() * w
                // 拍点过密时抽稀，避免长曲子糊成一片（小节线始终保留）。
                val isBar = i % 4 == 0
                if (!isBar && x - lastX < minGap) continue
                lastX = x
                if (isBar) {
                    drawLine(
                        color = barColor,
                        start = Offset(x, topPad),
                        end = Offset(x, h - topPad),
                        strokeWidth = barStroke,
                    )
                } else {
                    drawLine(
                        color = weakColor,
                        start = Offset(x, weakInset),
                        end = Offset(x, h - weakInset),
                        strokeWidth = weakStroke,
                    )
                }
            }

            // 打拍校准标记（对应 Dart `BeatRuler.tapMarks`：琥珀色，最近 20 个）。
            for (t in tapMarks) {
                if (t < 0.0 || t > total) continue
                val x = (t / total).toFloat() * w
                drawLine(
                    color = kTapMarkColor,
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 2.dp.toPx(),
                )
            }

            // 当前播放位置（Dart 版是居中白线，这里随位置移动）。
            drawLine(
                color = Color.White,
                start = Offset(w * playX, 0f),
                end = Offset(w * playX, h),
                strokeWidth = 2.dp.toPx(),
            )
        }

        if (beats.isEmpty()) {
            // 该曲没有拍点信息时的提示（文字与 Dart 页面保持一致）。
            Text(
                text = "（该曲无拍点信息，标尺暂不可用）",
                color = colors.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** 打拍校准标记的颜色（Dart `Colors.amber` = 0xFFFFC107）。 */
private val kTapMarkColor = Color(0xFFFFC107)

/** 把标尺上的横坐标换算成媒体时间秒（自动钳制到 [0, total]）。 */
private fun secAt(x: Float, width: Float, total: Double): Double {
    if (width <= 0f) return 0.0
    val ratio = (x / width).coerceIn(0f, 1f)
    return ratio.toDouble() * total
}
