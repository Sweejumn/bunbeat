package com.bunbeat.nativeapp.store

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.bunbeat.nativeapp.core.Prefs
import com.bunbeat.nativeapp.ui.theme.kThemeColors
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/** 全局外观模式（对应 Dart `ThemeMode` 的三态）。 */
enum class ThemeModeSetting { SYSTEM, LIGHT, DARK }

/**
 * 全局设置（对应 Dart `ThemeController` + `BpmDisplayController`）：
 * 深浅色模式、主题色种子、BPM 显示格式。
 *
 * 持久化键与 Dart 完全一致（`runbpm.themeMode` / `runbpm.themeColor` /
 * `runbpm.bpmTwoDecimals`），主题色以 `#RRGGBB` 字符串落盘。
 * 设置项都是「用户点一下就要立刻生效」的小数据，直接同步写 SharedPreferences
 * （[Prefs] 内部用 `apply()`，不会阻塞主线程）。
 */
class SettingsStore(private val prefs: Prefs) {

    companion object {
        private const val PREF_MODE = "runbpm.themeMode"
        private const val PREF_SYSTEM = "system"
        private const val PREF_LIGHT = "light"
        private const val PREF_DARK = "dark"

        private const val PREF_COLOR = "runbpm.themeColor"
        private const val PREF_TWO_DECIMALS = "runbpm.bpmTwoDecimals"

        /**
         * 是否跟随壁纸动态取色（Material You）。
         * 键名与 Shizuku 的 `use_system_color` 语义一致，默认开（Android 12+ 生效）。
         */
        private const val PREF_USE_SYSTEM_COLOR = "runbpm.useSystemColor"

        /**
         * 解析 `#RRGGBB`（对应 Dart `ThemeController._parseColor`）：
         * 去掉第一个 `#` 后按十六进制解析，失败返回 null；成功则强制不透明
         * （Dart 是 `Color(0xFF000000 | v)`，只取低 24 位颜色分量）。
         */
        private fun parseColor(hex: String): Color? {
            val body = hex.replaceFirst("#", "")
            val v = body.toLongOrNull(16) ?: return null
            return Color(0xFF000000L or (v and 0xFFFFFFL))
        }

        /**
         * 序列化成 `#RRGGBB`（对应 Dart `ThemeController._colorToString`）：
         * 各分量四舍五入到 0..255，两位大写十六进制，不带 alpha。
         */
        private fun colorToString(c: Color): String {
            val argb = c.toArgb()
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return String.format(Locale.ROOT, "#%02X%02X%02X", r, g, b)
        }

        /**
         * 与 Dart `double.round()` 一致的「四舍五入（.5 远离零）」。
         * Kotlin 的 `Math.round` 对负数是向上取整，BPM 虽不会为负，但保持一致更稳。
         */
        private fun dartRound(v: Double): Long =
            if (v >= 0) floor(v + 0.5).toLong() else ceil(v - 0.5).toLong()
    }

    /** 深浅色模式，默认跟随系统。 */
    var themeMode by mutableStateOf(ThemeModeSetting.SYSTEM)
        private set

    /** 主题色种子，默认第一个预设色（天蓝，Bunbeat 品牌色）。 */
    var seed by mutableStateOf(kThemeColors.first().color)
        private set

    /** BPM 是否保留两位小数，默认开启。 */
    var bpmTwoDecimals by mutableStateOf(true)
        private set

    /** 是否跟随壁纸动态取色，默认开启（Android 12 以下无效果）。 */
    var useSystemColor by mutableStateOf(true)
        private set

    /** 读取持久化设置（对应 Dart `ThemeController.load` + `BpmDisplayController.load`）。 */
    fun load() {
        themeMode = when (prefs.getString(PREF_MODE)) {
            PREF_LIGHT -> ThemeModeSetting.LIGHT
            PREF_DARK -> ThemeModeSetting.DARK
            else -> ThemeModeSetting.SYSTEM
        }
        val colorStr = prefs.getString(PREF_COLOR)
        if (colorStr != null) {
            // 解析失败就保持默认种子色（Dart 同样只在解析成功时覆盖）。
            parseColor(colorStr)?.let { seed = it }
        }
        bpmTwoDecimals = prefs.getBool(PREF_TWO_DECIMALS, true)
        useSystemColor = prefs.getBool(PREF_USE_SYSTEM_COLOR, true)
    }

    @kotlin.jvm.JvmName("applyThemeMode")
    fun setThemeMode(m: ThemeModeSetting) {
        if (themeMode == m) return
        themeMode = m
        prefs.putString(
            PREF_MODE,
            when (m) {
                ThemeModeSetting.LIGHT -> PREF_LIGHT
                ThemeModeSetting.DARK -> PREF_DARK
                ThemeModeSetting.SYSTEM -> PREF_SYSTEM
            },
        )
    }

    @kotlin.jvm.JvmName("applySeed")
    fun setSeed(c: Color) {
        // Dart 的 setSeed 不判重，重复设置同一色值也照写，这里保持一致。
        seed = c
        prefs.putString(PREF_COLOR, colorToString(c))
        // 手动选预设色 = 明确表示不用壁纸色（Shizuku 的交互也是二选一）。
        setUseSystemColor(false)
    }

    /**
     * 开关动态取色。选预设色时会被自动关掉；关掉后立刻回落到 [seed] 生成的配色，
     * 所以设置页里两者是「互斥的一行 + 一排色块」。
     */
    @kotlin.jvm.JvmName("applyUseSystemColor")
    fun setUseSystemColor(on: Boolean) {
        if (useSystemColor == on) return
        useSystemColor = on
        prefs.putBool(PREF_USE_SYSTEM_COLOR, on)
    }

    fun setTwoDecimals(on: Boolean) {
        if (bpmTwoDecimals == on) return
        bpmTwoDecimals = on
        prefs.putBool(PREF_TWO_DECIMALS, on)
    }

    /**
     * 按当前开关把 BPM 格式化成字符串（对应 Dart `BpmDisplayController.format`）：
     * null → 「—」；开启 → 两位小数；关闭 → 四舍五入整数。
     */
    fun formatBpm(bpm: Double?): String {
        if (bpm == null) return "—"
        if (bpmTwoDecimals) {
            // 用 Locale.ROOT 保证小数点始终是 '.'（Dart 的 toStringAsFixed 行为）。
            return String.format(Locale.ROOT, "%.2f", bpm)
        }
        return dartRound(bpm).toString()
    }
}
