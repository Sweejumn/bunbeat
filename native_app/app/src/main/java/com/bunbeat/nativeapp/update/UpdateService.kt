package com.bunbeat.nativeapp.update

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** 一次检查更新的结果（对应 Dart `UpdateStatus`）。 */
enum class UpdateStatus { UP_TO_DATE, AVAILABLE, FAILED }

/** 发布信息（版本号、公告、APK 地址、发布时间）。 */
data class ReleaseInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val pageUrl: String,
    val sizeBytes: Long,
    /** GitHub 的 `published_at`（ISO-8601，UTC）；缺失为 null（对应 Dart `publishedAt`）。 */
    val publishedAt: String? = null,
)

data class UpdateCheck(val status: UpdateStatus, val info: ReleaseInfo? = null)

/**
 * 联网更新服务（对应 Dart `update_service.dart`）。
 *
 * 与 Flutter 版的差异（刻意）：原生版是独立 applicationId 的并行实现，
 * 因此只认 **tag 以 `native-` 开头** 的 Release，绝不会把 Flutter 版的 APK
 * 当成自己的更新（反之亦然，Flutter 版只查 `releases/latest`）。
 *
 * 版本约定：tag 形如 `native-v0.2.0+3`，Release 里带一个 `.apk` 资产。
 */
object UpdateService {

    const val REPO = "Sweejumn/bunbeat"

    /** 原生版 Release 的 tag 前缀。 */
    const val TAG_PREFIX = "native-"

    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val API_LIST = "https://api.github.com/repos/$REPO/releases?per_page=30"

    /** 请求 GitHub API，返回解析后的 JSON 文本；失败返回 null。 */
    private fun httpGetJson(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "bunbeat-native")
            }
            val code = conn.responseCode
            if (code !in 200..299) return null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        } catch (_: Throwable) {
            // 任何网络/解析异常都视为失败，绝不影响正常使用（与 Dart 一致的容错策略）。
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 从一个 release JSON 里解析出 [ReleaseInfo]；没有 .apk 资产时返回 null。 */
    private fun parseRelease(obj: JSONObject): ReleaseInfo? {
        val tag = obj.optString("tag_name", "")
        val version = cleanVersion(tag)
        if (version.isEmpty()) return null
        val assets = obj.optJSONArray("assets") ?: JSONArray()
        var apkUrl: String? = null
        var size = 0L
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "")
            if (name.lowercase().endsWith(".apk")) {
                apkUrl = a.optString("browser_download_url", null)
                size = a.optLong("size", 0L)
                break
            }
        }
        val url = apkUrl ?: return null
        val body = obj.optString("body", "").trim()
        return ReleaseInfo(
            version = version,
            notes = body,
            apkUrl = url,
            pageUrl = obj.optString("html_url", "https://github.com/$REPO/releases"),
            sizeBytes = size,
            publishedAt = obj.optString("published_at", "").takeIf { it.isNotBlank() },
        )
    }

    /**
     * 把 GitHub 的 `published_at`（形如 `2026-09-19T13:05:41Z`）转成设备本地时间的
     * `yyyy-MM-dd HH:mm`，对应 Dart `_formatTime`。解析失败返回 null。
     *
     * 只处理 GitHub 实际返回的这两种格式（带 Z 的 UTC、或带偏移量的 ISO-8601）。
     */
    fun formatPublishedAt(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val patterns = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        )
        for (p in patterns) {
            val fmt = java.text.SimpleDateFormat(p, java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val parsed = try {
                fmt.parse(raw)
            } catch (_: java.text.ParseException) {
                null
            } ?: continue
            val out = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            return out.format(parsed)
        }
        return null
    }

    /**
     * 找到原生版最新的一个 Release。
     *
     * 先看 `releases/latest`（快路径，多数情况一次请求就够）；如果它不是原生版系列
     * （例如当前 GitHub 上最新的是 Flutter 版或 Kotlin 引擎版），再拉列表逐个筛选。
     */
    suspend fun fetchLatestRelease(): ReleaseInfo? {
        val latestText = httpGetJson(API_LATEST)
        if (latestText != null) {
            val obj = runCatching { JSONObject(latestText) }.getOrNull()
            if (obj != null) {
                val tag = obj.optString("tag_name", "")
                if (tag.startsWith(TAG_PREFIX) && !obj.optBoolean("draft", false)) {
                    val info = parseRelease(obj)
                    if (info != null) return info
                }
            }
        }
        val listText = httpGetJson(API_LIST) ?: return null
        val arr = runCatching { JSONArray(listText) }.getOrNull() ?: return null
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optBoolean("draft", false)) continue
            if (!obj.optString("tag_name", "").startsWith(TAG_PREFIX)) continue
            val info = parseRelease(obj)
            if (info != null) return info
        }
        return null
    }

    /**
     * 检查是否有比 [currentFull]（形如 `0.2.0+3`）更新的原生版发布。
     *
     * 失败与「已是最新」严格区分：网络失败、仓库没有原生版 Release、或原生版 Release
     * 里没有 `.apk` 资产时都返回 [UpdateStatus.FAILED]（与 Dart 版同策略）。
     */
    suspend fun check(currentFull: String): UpdateCheck {
        val info = fetchLatestRelease() ?: return UpdateCheck(UpdateStatus.FAILED)
        return if (isNewer(info.version, currentFull)) {
            UpdateCheck(UpdateStatus.AVAILABLE, info)
        } else {
            UpdateCheck(UpdateStatus.UP_TO_DATE, info)
        }
    }

    /** 去掉 tag 前导的 `native-` / `v`，得到形如 `0.2.0+3` 的版本串。 */
    fun cleanVersion(tag: String): String {
        var v = tag.trim()
        if (v.startsWith(TAG_PREFIX)) v = v.substring(TAG_PREFIX.length)
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1)
        return v
    }

    /** 版本串比较：优先比 build 号（`+N`），否则按 semver 数字比较（逐字复刻 Dart `_isNewer`）。 */
    fun isNewer(latest: String, current: String): Boolean {
        val latestBuild = buildNumber(latest)
        val currentBuild = buildNumber(current)
        if (latestBuild != null && currentBuild != null) {
            return latestBuild > currentBuild
        }
        val lParts = numParts(latest)
        val cParts = numParts(current)
        val len = maxOf(lParts.size, cParts.size)
        for (i in 0 until len) {
            val l = lParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (l != c) return l > c
        }
        return false
    }

    private fun buildNumber(v: String): Int? {
        val idx = v.indexOf('+')
        if (idx < 0) return null
        return v.substring(idx + 1).trim().toIntOrNull()
    }

    private fun numParts(v: String): List<Int> {
        val dotIdx = v.indexOf('+')
        val core = if (dotIdx >= 0) v.substring(0, dotIdx) else v
        val parts = mutableListOf<Int>()
        for (seg in core.split('.')) {
            val n = seg.trim().toIntOrNull() ?: return parts
            parts.add(n)
        }
        return parts
    }
}
