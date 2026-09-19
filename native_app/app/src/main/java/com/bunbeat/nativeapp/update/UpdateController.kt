package com.bunbeat.nativeapp.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * 更新流程控制器（对应 Dart `update_flow.dart` + `UpdateService` 的 UI 侧状态）。
 *
 * 页面/Activity 只读这里的状态：`pending` 非空就弹「发现新版本」对话框，
 * 用户点「立即更新」后走 [downloadAndInstall]。
 */
class UpdateController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    /** 是否正在检查。 */
    var checking: Boolean by mutableStateOf(false)
        private set

    /** 有新版时非空 → 由 MainActivity 弹窗提示。 */
    var pending: ReleaseInfo? by mutableStateOf(null)
        private set

    /** 最新的发布信息（关于页展示「最新版本公告」用）。 */
    var latest: ReleaseInfo? by mutableStateOf(null)
        private set

    /** 最近一次检查的结果文案（关于页/设置页显示）。 */
    var statusText: String? by mutableStateOf(null)
        private set

    /** 是否正在下载更新包。 */
    var downloading: Boolean by mutableStateOf(false)
        private set

    /** 下载进度 0..1（未知总大小时为 0）。 */
    var progress: Float by mutableStateOf(0f)
        private set

    /** 需要提示用户时的回调（由 MainActivity 接到 Snackbar）。 */
    var onMessage: ((String) -> Unit)? = null

    /** 正在进行的下载任务（供 [cancelDownload] 取消）。 */
    private var downloadJob: Job? = null

    /** 当前安装版本的完整串，形如 `0.2.0+3`（与 Release tag 对应）。 */
    fun currentVersionFull(): String {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            val name = pi.versionName ?: "0.0.0"
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION") pi.versionCode.toLong()
            }
            "$name+$code"
        } catch (_: Throwable) {
            "0.0.0+0"
        }
    }

    /** 检查更新；[manual] 为 true 时无论结果如何都给出提示，false（开机自检）时静默。 */
    suspend fun check(manual: Boolean) {
        if (checking) return
        checking = true
        statusText = null
        // 网络请求必须切到 IO：调用方（MainActivity/页面）跑在主线程上，
        // 直接在主线程序跑 HttpURLConnection 会抛 NetworkOnMainThreadException。
        val res = withContext(Dispatchers.IO) { UpdateService.check(currentVersionFull()) }
        checking = false
        when (res.status) {
            UpdateStatus.AVAILABLE -> {
                latest = res.info
                pending = res.info
                statusText = null
            }
            UpdateStatus.UP_TO_DATE -> {
                latest = res.info
                statusText = null
                if (manual) onMessage?.invoke("已是最新版本")
            }
            UpdateStatus.FAILED -> {
                statusText = "检查更新失败"
                if (manual) onMessage?.invoke("检查更新失败，请稍后再试")
            }
        }
    }

    /** 关闭「发现新版本」弹窗（本次不再提示）。 */
    fun dismiss() {
        pending = null
    }

    /** 刷新「最新发布」信息（关于页进入时调用），失败静默。 */
    fun refreshLatest() {
        scope.launch {
            val info = withContext(Dispatchers.IO) { UpdateService.fetchLatestRelease() }
            if (info != null) latest = info
        }
    }

    /** 下载最新 APK 并交给系统安装器。 */
    fun downloadAndInstall() {
        val info = pending ?: latest ?: return
        if (downloading) return
        downloadJob = scope.launch {
            downloading = true
            progress = 0f
            try {
                val file = withContext(Dispatchers.IO) { download(info.apkUrl) }
                if (file == null) {
                    onMessage?.invoke("更新包下载失败，请稍后再试")
                    return@launch
                }
                install(file)
            } finally {
                // 正常结束、下载失败、被用户取消（协程取消）都会走到这里。
                downloading = false
                downloadJob = null
            }
        }
    }

    /**
     * 取消正在进行的下载（对应 Dart 下载弹窗里的「取消」按钮）。
     * 已写入的半包文件会在下次下载时被覆盖，无需单独清理。
     */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        downloading = false
        progress = 0f
    }

    private suspend fun download(apkUrl: String): File? {
        var conn: HttpURLConnection? = null
        return try {
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            val out = File(dir, "bunbeat-native-update.apk")
            conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "bunbeat-native")
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) return null
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } > 0) {
                        // 每块都检查一次取消，用户点「取消」时能及时停下。
                        coroutineContext.ensureActive()
                        output.write(buf, 0, read)
                        done += read
                        if (total > 0) progress = (done.toFloat() / total).coerceIn(0f, 1f)
                    }
                    output.flush()
                }
            }
            out
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 用户取消：交给上层 finally 复位状态，不再提示失败。
            throw e
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 用 FileProvider 把 APK 交给系统安装器（Android 7+ 不允许 file:// 直传）。 */
    private fun install(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            // 缺少安装权限或系统不允许时，退化为提示用户手动下载。
            onMessage?.invoke("无法自动安装：$e")
        }
    }
}
