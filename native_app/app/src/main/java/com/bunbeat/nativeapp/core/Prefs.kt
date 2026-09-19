package com.bunbeat.nativeapp.core

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences 薄封装（对应 Flutter 版的 shared_preferences）。
 *
 * 键名特意与 Dart 版保持一致（`last_folder` / `target_bpm` / `runbpm.*`），
 * 这样两版行为一致、排查问题时可以直接对照。
 * 注意：原生版是独立 applicationId，因此数据是各自独立的一份。
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("bunbeat_prefs", Context.MODE_PRIVATE)

    fun getString(key: String, def: String? = null): String? = sp.getString(key, def)
    fun putString(key: String, value: String?) {
        sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    fun getDouble(key: String, def: Double): Double {
        val v = sp.all[key]
        return when (v) {
            is Double -> v
            is Float -> v.toDouble()
            is Long -> v.toDouble()
            is Int -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: def
            else -> def
        }
    }

    fun putDouble(key: String, value: Double) {
        // Dart 的 shared_preferences 在 Android 上把 double 存成 double；这里保持一致。
        sp.edit().putString(key, value.toString()).apply()
    }

    fun getInt(key: String, def: Int): Int = when (val v = sp.all[key]) {
        is Int -> v
        is Long -> v.toInt()
        is Double -> v.toInt()
        is String -> v.toIntOrNull() ?: def
        else -> def
    }

    fun putInt(key: String, value: Int) {
        sp.edit().putInt(key, value).apply()
    }

    fun getBool(key: String, def: Boolean): Boolean = when (val v = sp.all[key]) {
        is Boolean -> v
        is String -> v.toBooleanStrictOrNull() ?: def
        else -> def
    }

    fun putBool(key: String, value: Boolean) {
        sp.edit().putBoolean(key, value).apply()
    }

    /** 有序字符串列表，用 '\n' 分隔（id 都是十六进制 hash，不会含换行）。 */
    fun getStringList(key: String): List<String> {
        val raw = sp.getString(key, null) ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split('\n')
    }

    fun putStringList(key: String, values: List<String>) {
        sp.edit().putString(key, values.joinToString("\n")).apply()
    }

    fun remove(key: String) {
        sp.edit().remove(key).apply()
    }

    fun contains(key: String): Boolean = sp.contains(key)
}
