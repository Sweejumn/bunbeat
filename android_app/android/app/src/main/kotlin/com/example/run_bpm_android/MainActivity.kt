package com.example.run_bpm_android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "run_bpm/install").setMethodCallHandler { call, result ->
            when (call.method) {
                "installApk" -> {
                    val path = call.argument<String>("path")
                    if (path == null) {
                        result.error("bad_arg", "missing path", null)
                    } else {
                        try {
                            val installed = installApk(File(path))
                            result.success(installed)
                        } catch (e: Exception) {
                            Log.e("RUN_BPM", "installApk failed", e)
                            result.error("install_failed", e.message, null)
                        }
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    /** 用 FileProvider 把缓存 APK 分享给系统安装器。返回是否真正拉起安装界面。 */
    private fun installApk(file: File): Boolean {
        if (!file.exists()) throw IllegalStateException("APK 文件不存在")
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        @Suppress("DEPRECATION")
        val ctx = applicationContext
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.e("RUN_BPM", "no activity to install APK", e)
            false
        }
    }
}
