package com.bunbeat.nativeapp.ui

// 对应 Dart `ui/mode_picker.dart`（与 Web 版 ModePicker 对齐）。
//
// 主要交互是一条长滑块，覆盖恰好四个运动区间（100–185）：
// 拖动滑块跨区间会自动切换运动模式；区间外（<100 或 >185）在右侧
// 输入框手动输入（自定义）。Quick-jump 芯片点击跳到该区间默认 BPM。
//
// Dart 侧的 `models/modes.dart`（ModeId / ModeDef / kModes）在 native_app 还没有
// 对应文件，按「只新增本页面文件」的约定，把这份最小定义放在本文件内，
// 成员名与取值和 Dart 一一对应，后续若要抽到 model 包可直接整体搬走。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// 滑块覆盖恰好四个运动区间；区间外只能手动输入（自定义）。
private const val kMin = 100
private const val kMax = 185
private const val kManualMin = 40
private const val kManualMax = 300

/** 运动模式 id（对应 Dart `ModeId`）。 */
enum class ModeId { WALK, JOG, RUN, SPRINT, CUSTOM }

/** 运动模式定义（对应 Dart `ModeDef`）。 */
data class ModeDef(
    val id: ModeId,
    val label: String,
    val icon: String,
    val rangeLow: Int,
    val rangeHigh: Int,
    val defaultBpm: Double,
)

/** 全部运动模式（对应 Dart `kModes`，顺序与取值完全一致）。 */
val kModes: List<ModeDef> = listOf(
    ModeDef(ModeId.WALK, "走路", "🚶", 100, 120, 110.0),
    ModeDef(ModeId.JOG, "慢跑", "🏃", 120, 145, 132.0),
    ModeDef(ModeId.RUN, "跑步", "🏃‍♂️", 145, 165, 155.0),
    ModeDef(ModeId.SPRINT, "快跑", "⚡", 165, 185, 175.0),
    ModeDef(ModeId.CUSTOM, "自定义", "🎯", 60, 220, 95.0),
)

/** 根据 BPM 判定所属模式（与 Web modeFromBpm 一致）。 */
fun modeFromBpm(bpm: Double): ModeId {
    if (bpm >= 100 && bpm < 120) return ModeId.WALK
    if (bpm >= 120 && bpm < 145) return ModeId.JOG
    if (bpm >= 145 && bpm < 165) return ModeId.RUN
    if (bpm >= 165 && bpm <= 185) return ModeId.SPRINT
    return ModeId.CUSTOM
}

/** 四个区间（与 Web ModePicker 一致，仅显示这四段颜色）。 */
private class Zone(
    val from: Int,
    val to: Int,
    val color: Color,
    val label: String,
    val icon: String,
)

private val kZones: List<Zone> = listOf(
    Zone(100, 120, Color(0xFF38BDF8), "走路", "🚶"),
    Zone(120, 145, Color(0xFF34D399), "慢跑", "🏃"),
    Zone(145, 165, Color(0xFFFBBF24), "跑步", "🏃‍♂️"),
    Zone(165, 185, Color(0xFFF87171), "快跑", "⚡"),
)

/**
 * 运动模式选择器（对应 Dart `ModePicker` / `_ModePickerState`）。
 *
 * @param bpm 当前目标 BPM（外部状态，页面负责持久化）
 * @param onChanged 用户改动目标 BPM 时回调
 */
@Composable
fun ModePicker(
    bpm: Double,
    onChanged: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Dart 用 TextEditingController + FocusNode 保存草稿与焦点；
    // Compose 侧用「字符串状态 + 焦点状态」等价表达（见下方 LaunchedEffect 的同步逻辑）。
    var draft by remember { mutableStateOf(dartRoundToString(bpm)) }
    var focused by remember { mutableStateOf(false) }
    var lastBpm by remember { mutableStateOf(bpm) }

    // 对应 Dart `didUpdateWidget`：外部 BPM 变化时同步输入框草稿
    // （仅在非聚焦文本时，避免覆盖用户正在输入的内容）。
    // 这里额外用 lastBpm 记录「上一次已处理过的 BPM」，避免焦点变化引发的重复同步。
    LaunchedEffect(bpm, focused) {
        if (bpm != lastBpm) {
            if (!focused) draft = dartRoundToString(bpm)
            lastBpm = bpm
        }
    }

    val mode = modeFromBpm(bpm)
    val activeDef = kModes.first { it.id == mode }
    val isCustom = mode == ModeId.CUSTOM

    // Dart `_commitDraft`：解析失败回填当前 BPM；成功则四舍五入并夹到 40–300 后回调。
    val commitDraft: () -> Unit = {
        val v = draft.trim().toDoubleOrNull()
        if (v == null || !v.isFinite()) {
            draft = dartRoundToString(bpm)
        } else {
            // Dart 先 round 再 clamp(40, 300)；这里先 clamp 再 round：
            // 对有限输入结果完全等价，同时避免超范围值 roundToInt 溢出。
            val c = v.coerceIn(kManualMin.toDouble(), kManualMax.toDouble()).roundToInt().toDouble()
            onChanged(c)
            draft = c.roundToInt().toString()
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("今天想怎么跑？", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = draft,
                onValueChange = { raw ->
                    draft = raw
                    // 与 Dart 的 onChanged 一致：只有「非空 + 合法数字 + 整数 + 落在手动区间内」
                    // 才立即生效；其余（含只输了一半的中间态）保留文本不动。
                    val v = parseManualInput(raw)
                    if (v != null) onChanged(v)
                },
                modifier = Modifier
                    .width(84.dp)
                    .onFocusChanged { focused = it.isFocused },
                singleLine = true,
                // Dart：style = TextStyle(fontSize: 20, FontWeight.bold) + textAlign: right。
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                keyboardActions = KeyboardActions(onDone = { commitDraft() }),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("BPM", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(10.dp))

        // 当前模式横幅（放在四个快捷芯片上方）。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = if (isCustom) {
                    "${activeDef.icon} ${activeDef.label} · 区间外自定义（手动输入）"
                } else {
                    "${activeDef.icon} ${activeDef.label} · ${activeDef.rangeLow}–${activeDef.rangeHigh} BPM"
                },
                fontSize = 13.sp,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))

        // Quick-jump 芯片（走路/慢跑/跑步/快跑）：放在滑块上方，便于单手快速选模式，
        // 也让滑块相对页面居中以方便拖动。
        Row {
            kModes.filter { it.id != ModeId.CUSTOM }.forEach { m ->
                val selected = mode == m.id
                val accent = MaterialTheme.colorScheme.primary
                Box(
                    // Dart 用 `Expanded(child: Padding(horizontal: 3, child: InkWell(...)))`：
                    // 四张卡等分宽度、各自左右留 3dp。
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 3.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) accent.copy(alpha = 0.18f)
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                        .border(
                            width = 1.dp,
                            color = if (selected) accent else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(10.dp),
                        )
                        // Dart 用 InkWell + borderRadius；material3 1.4.0 已无 InkWell，
                        // 这里用 clickable，水波纹由 clip 限定在 10dp 圆角内。
                        .clickable { handleChip(m.id, onChanged) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(m.icon, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(m.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        // 彩色区间滑块（对应 Dart `_buildSlider`）。
        ModePickerSlider(bpm = bpm, onChanged = onChanged)
        Spacer(modifier = Modifier.height(2.dp))

        // 区间标签（对应 Dart `_buildZoneLabels`）。
        ZoneLabels()
        Spacer(modifier = Modifier.height(6.dp))
    }
}

/**
 * 彩色区间滑块：滑块轨道透明，背后绘制四段区间色块（对应 Dart `_buildSlider`）。
 *
 * 差异说明：Compose 的 material3 `Slider` 没有 trackHeight / overlayColor / label 参数，
 * 因此轨道高度与拖动浮标无法像 Dart 那样定制，只能保留透明轨道 + 主题色圆点。
 */
@Composable
private fun ModePickerSlider(
    bpm: Double,
    onChanged: (Double) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 四段彩色区间（端到端）。
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp)),
        ) {
            kZones.forEach { z ->
                Box(
                    modifier = Modifier
                        .weight((z.to - z.from).toFloat())
                        .fillMaxHeight()
                        .background(z.color),
                )
            }
        }

        // 滑块（轨道透明，只保留拖动交互与滑块圆点）。
        Slider(
            value = sliderValue(bpm),
            onValueChange = { v -> onChanged(v.toDouble()) },
            modifier = Modifier.fillMaxWidth(),
            valueRange = kMin.toFloat()..kMax.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

/** 区间标签（带图标与 BPM 范围），按区间宽度居中对齐（对应 Dart `_buildZoneLabels`）。 */
@Composable
private fun ZoneLabels() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        kZones.forEach { z ->
            Box(
                modifier = Modifier
                    .weight((z.to - z.from).toFloat())
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${z.icon} ${z.from}–${z.to}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Quick-jump 芯片点击（对应 Dart `_handleChip`）：
 * 跳到该区间的默认 BPM；自定义模式没有区间默认值，点击无效。
 */
private fun handleChip(m: ModeId, onChanged: (Double) -> Unit) {
    val def = kModes.first { it.id == m }
    if (def == kModes.last() && m == ModeId.CUSTOM) return
    onChanged(def.defaultBpm)
}

/**
 * 解析手动输入框的即时输入（对应 Dart `onChanged` 里的那串判断）：
 * 非空、可解析、有限、是整数且落在 40–300 内才返回该值，否则返回 null。
 *
 * 与 Dart 的差异：Dart 对 "NaN" / "Infinity" 会走到 `round()` 抛异常，
 * 这里直接判为非法输入（更稳，且不影响任何正常输入路径）。
 */
private fun parseManualInput(raw: String): Double? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val v = text.toDoubleOrNull() ?: return null
    if (!v.isFinite()) return null
    if (v < kManualMin || v > kManualMax) return null
    if (v != v.roundToInt().toDouble()) return null
    return v
}

/** 滑块取值：夹到 100–185（对应 Dart `_bpm.clamp(_kMin, _kMax)`）。 */
private fun sliderValue(bpm: Double): Float {
    if (!bpm.isFinite()) return kMin.toFloat()
    return bpm.coerceIn(kMin.toDouble(), kMax.toDouble()).toFloat()
}

/** BPM 取整成文本（对应 Dart `bpm.round().toString()`；BPM 恒为正数，故用 roundToInt）。 */
private fun dartRoundToString(bpm: Double): String =
    if (bpm.isFinite()) bpm.roundToInt().toString() else kMin.toString()
