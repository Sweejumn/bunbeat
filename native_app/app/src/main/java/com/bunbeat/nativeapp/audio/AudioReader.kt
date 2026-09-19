package com.bunbeat.nativeapp.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 选择 / 扫描文件夹并读出其中的音频文件（对应 Dart `services/audio_reader.dart`）。
 *
 * 与 Dart 版的差异集中在两点（都是平台差异，不改变对外语义）：
 *  - Dart 用 on_audio_query 在扫描时**请求**媒体权限；原生侧不主动弹权限框
 *    （Context 未必是 Activity），只做「已授权/未授权」判断，未授权时给出与
 *    Dart 完全相同的提示文案并回退到文件系统扫描。权限申请由宿主负责。
 *  - Dart 用 file_picker 弹目录选择器；原生侧由 SAF（ACTION_OPEN_DOCUMENT_TREE）
 *    选目录后调用 [AudioReader.scanTree]。
 */

/** 支持的音频扩展名（小写，不含点）——与 Dart `kAudioExtensions` 完全一致。 */
val kAudioExtensions: Set<String> = setOf(
    "mp3", "wav", "m4a", "aac", "ogg", "flac", "opus", "aiff", "aif", "wma",
    "ncm",
)

/**
 * 一次文件夹选择 / 扫描的结果（对应 Dart `FolderPick`）。
 *
 * @param path 选中文件夹的真实绝对路径；null 表示用户取消。
 * @param audioFiles 该文件夹下扫描到的音频文件绝对路径。
 * @param errors 扫描过程中遇到的错误（例如无权限的父目录），仅提示用，不阻断。
 */
data class FolderPick(
    val path: String? = null,
    val audioFiles: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
) {
    val cancelled: Boolean get() = path == null
}

object AudioReader {

    /** 递归扫描的最大深度（防御极端深目录），对应 Dart `_scan` 的 `depth > 12`。 */
    private const val MAX_DEPTH = 12

    /**
     * 非递归模式下的深度防线，对应 Dart `_scan` 的 `depth > 4 && !recursive`。
     *
     * 注意：Dart 的非递归模式只读取目录自身的第一层文件（子目录直接跳过），
     * 因此这个上限实际上永远触发不到；这里保留同样的判断，保持逐行等价。
     */
    private const val NON_RECURSIVE_MAX_DEPTH = 4

    /** Android 13+ 读音频媒体的权限名（低版本用 READ_EXTERNAL_STORAGE）。 */
    private const val PERMISSION_READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO"

    // ------------------------------------------------------------------
    // 路径归一化
    // ------------------------------------------------------------------

    /**
     * 把选择器返回的目录标识归一化为真实绝对路径（用于路径前缀匹配）。
     *
     * 与 Dart `normalizeFolderPath` 等价：
     *  - `content://com.android.externalstorage.documents/tree/primary%3AMusic`
     *    → `/storage/emulated/0/Music`
     *  - 其余按绝对路径规范化（等价 `path.normalize`）。
     */
    fun normalizeFolderPath(raw: String): String {
        val s = raw.trim()
        val lower = s.lowercase()
        if (lower.contains("document/tree/")) {
            val idx = lower.lastIndexOf("/tree/")
            if (idx >= 0) {
                var tail = s.substring(idx + "/tree/".length)
                tail = tail.split('/').first()
                tail = decodeComponent(tail)
                if (tail.contains(':')) {
                    val parts = tail.split(':')
                    val volume = parts.first()
                    // 剩余部分用 ':' 重新拼回（与 Dart 的 parts.sublist(1).join(':') 一致）
                    val rel = parts.drop(1).joinToString(":")
                    val relPath = rel.split('/').joinToString("/") { decodeComponent(it) }
                    val volumeRoot = if (volume == "primary" || volume == "internal") {
                        "/storage/emulated/0"
                    } else {
                        "/storage/$volume"
                    }
                    return normalizePosix("$volumeRoot/$relPath")
                }
            }
        }
        // 已经是真实路径
        return normalizePosix(s)
    }

    /**
     * 稳定 id：路径 hash，用作缓存文件名与播放列表键。
     *
     * 与 Dart `LibraryService._stableId` / `NcmService._stableId` **逐位一致**：
     * ```dart
     * var h = 0;
     * for (final code in s.codeUnits) { h = (h * 31 + code) & 0x7fffffff; }
     * return h.toRadixString(16).padLeft(8, '0');
     * ```
     * Dart 的 int 是 64 位，`h * 31 + code` 不会溢出；Kotlin 用 [Long] 运算再掩码，
     * 避免 Int 溢出导致与 Dart 结果不同（掩码后两者完全相等）。
     * `codeUnits` 是 UTF-16 码元，与 Kotlin 的 `Char.code` 语义相同。
     */
    fun stableId(path: String): String {
        var h = 0L
        for (ch in path) {
            h = (h * 31L + ch.code.toLong()) and 0x7fffffffL
        }
        return h.toString(16).padStart(8, '0')
    }

    // ------------------------------------------------------------------
    // 扫描：按已知文件夹路径（启动恢复 / 预设音源）
    // ------------------------------------------------------------------

    /**
     * 不弹选择器，直接根据已知文件夹路径扫描其中的音频文件。
     * 用于启动时自动恢复上次选择的文件夹，对应 Dart `AudioReader.scanFolder`。
     */
    suspend fun scanFolder(
        context: Context,
        rawFolder: String,
        withSubfolders: Boolean = true,
    ): FolderPick = withContext(Dispatchers.IO) {
        val folder = normalizeFolderPath(rawFolder)
        val files = ArrayList<String>()
        val errors = ArrayList<String>()

        // 1) 优先 MediaStore（作用域存储下的正确途径，与 Dart 一致）
        try {
            val scanned = scanViaMediaStore(context, folder)
            files.addAll(scanned.first)
            errors.addAll(scanned.second)
        } catch (e: Throwable) {
            errors.add("MediaStore 扫描失败: $e")
        }

        // 2) MediaStore 无结果或不可用时回退到文件系统扫描
        //    （Android 10 及以下、或媒体权限被拒时仍可工作）。
        if (files.isEmpty()) {
            try {
                val root = File(folder)
                if (root.exists()) {
                    scanDir(root, files, errors, depth = 0, recursive = withSubfolders)
                    // Dart 侧这里的顺序取决于文件系统返回顺序（本身不稳定）；
                    // 原生侧按路径排序，保证同一目录多次扫描结果稳定。
                    files.sortBy { it.lowercase() }
                }
                // 目录不存在时不追加错误（与 Dart scanFolder 一致，由调用方提示）
            } catch (e: Throwable) {
                errors.add("无法读取文件夹: $e")
            }
        }

        FolderPick(path = folder, audioFiles = ArrayList(files), errors = ArrayList(errors))
    }

    // ------------------------------------------------------------------
    // 扫描：SAF 目录树（用户选择目录后）
    // ------------------------------------------------------------------

    /**
     * 用 `DocumentFile.fromTreeUri` 递归列举 SAF 目录树中的音频文件，
     * 并尽量还原为真实路径（`/storage/emulated/0/...`）。
     *
     * 拿不到真实路径的文件会被跳过并在 [FolderPick.errors] 里说明原因
     * ——库服务需要真实路径做 MediaStore 过滤、NCM 重定向与缓存失效判断。
     * 非 `externalstorage` 提供者（例如 Downloads）的文档 id 无法映射成文件路径，
     * 这类目录下的音频会全部跳过。
     */
    suspend fun scanTree(
        context: Context,
        treeUri: Uri,
        withSubfolders: Boolean = true,
    ): FolderPick = withContext(Dispatchers.IO) {
        val files = ArrayList<String>()
        val errors = ArrayList<String>()

        val root: DocumentFile? = try {
            DocumentFile.fromTreeUri(context, treeUri)
        } catch (e: Throwable) {
            null
        }
        if (root == null) {
            errors.add("无法打开所选文件夹: $treeUri")
            return@withContext FolderPick(path = null, audioFiles = emptyList(), errors = ArrayList(errors))
        }

        // 根目录的真实路径（解析失败不阻断扫描，只是路径前缀没法用）。
        val rootReal = documentIdToRealPath(treeDocumentId(treeUri))
        if (rootReal == null) {
            errors.add("无法解析文件夹真实路径: $treeUri")
        }

        try {
            walkDocumentTree(root, rootReal, files, errors, depth = 0, recursive = withSubfolders)
        } catch (e: Throwable) {
            errors.add("无法读取文件夹: $e")
        }

        // 提供者返回顺序不稳定，这里统一按路径排序（Dart 侧 SAF 路径由 file_picker
        // 扫描，同样没有稳定顺序）。
        files.sortBy { it.lowercase() }

        FolderPick(
            // 解析不出真实路径时退回 tree URI 字符串：normalizeFolderPath 仍能把它
            // 还原成真实路径，下次启动的 scanFolder 恢复流程照样可用。
            path = rootReal ?: treeUri.toString(),
            audioFiles = ArrayList(files),
            errors = ArrayList(errors),
        )
    }

    // ------------------------------------------------------------------
    // MediaStore
    // ------------------------------------------------------------------

    /**
     * 通过 MediaStore 查询音频并按选中文件夹做路径前缀过滤。
     *
     * 对应 Dart `_scanViaMediaStore`：`withSubfolders` 不影响这里——MediaStore
     * 只支持按路径前缀匹配，子目录文件天然包含在内。
     *
     * @return `first` = 文件路径列表（保持查询顺序 `_ID ASC`），`second` = 错误列表。
     */
    private fun scanViaMediaStore(
        context: Context,
        folder: String,
    ): Pair<List<String>, List<String>> {
        val files = ArrayList<String>()
        val errors = ArrayList<String>()

        val folderLower = normalizePosix(folder).lowercase()

        // 读取权限：媒体权限被拒时与 Dart 给出同样的提示（Dart 会主动请求，
        // 原生侧由宿主在启动时申请，这里只判断结果）。
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PERMISSION_READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val permitted = try {
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            errors.add("无法请求媒体权限: $e")
            return files to errors
        }
        if (!permitted) {
            errors.add("未授予媒体读取权限，无法扫描歌曲")
            return files to errors
        }

        val projection = ArrayList<String>(3)
        projection.add(MediaStore.Audio.Media.DATA)
        projection.add(MediaStore.Audio.Media.DISPLAY_NAME)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+ 才有 RELATIVE_PATH（DATA 在新系统上可能为空/不可用）
            projection.add(MediaStore.Audio.Media.RELATIVE_PATH)
        }

        try {
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection.toTypedArray(),
                null,
                null,
                // 对应 Dart 的 OrderType.ASC_OR_SMALLER（按 _ID 升序）
                MediaStore.Audio.Media._ID + " ASC",
            )
            if (cursor == null) {
                errors.add("查询媒体库失败: 返回空游标")
                return files to errors
            }
            cursor.use { c ->
                val dataIdx = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                val nameIdx = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val relIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                } else {
                    -1
                }

                while (c.moveToNext()) {
                    val data = if (dataIdx >= 0 && !c.isNull(dataIdx)) c.getString(dataIdx) else null
                    val raw: String = if (!data.isNullOrEmpty()) {
                        data
                    } else {
                        // DATA 不可用（作用域存储）时用 RELATIVE_PATH + DISPLAY_NAME 拼回
                        val rel = if (relIdx >= 0 && !c.isNull(relIdx)) c.getString(relIdx) else null
                        val name = if (nameIdx >= 0 && !c.isNull(nameIdx)) c.getString(nameIdx) else null
                        if (rel.isNullOrEmpty() || name.isNullOrEmpty()) continue
                        joinUnder(externalStorageRoot(), rel, name)
                    }

                    if (!kAudioExtensions.contains(extensionOf(raw))) continue

                    // 文件必须位于所选文件夹（或子目录）内；与 Dart 一样按小写前缀比较
                    val norm = normalizePosix(raw)
                    val lower = norm.lowercase()
                    if (!lower.startsWith(folderLower)) continue
                    if (lower.length == folderLower.length) continue // 不应发生（等同于 Dart 的 rel.isEmpty）

                    files.add(norm)
                }
            }
        } catch (e: Throwable) {
            errors.add("查询媒体库失败: $e")
        }

        return files to errors
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 递归扫描文件系统；深度与错误语义对齐 Dart `_scan`。 */
    private fun scanDir(
        dir: File,
        files: MutableList<String>,
        errors: MutableList<String>,
        depth: Int,
        recursive: Boolean,
    ) {
        if (depth > NON_RECURSIVE_MAX_DEPTH && !recursive) return // 防御：非递归时不深入
        if (depth > MAX_DEPTH) return // 防御：防止极端深目录

        val children = try {
            dir.listFiles()
        } catch (e: Throwable) {
            errors.add("无法读取 ${dir.path}: $e")
            return
        }
        if (children == null) {
            errors.add("无法读取 ${dir.path}: 目录不可读或不存在")
            return
        }

        for (entity in children) {
            try {
                if (entity.isDirectory) {
                    if (recursive) scanDir(entity, files, errors, depth + 1, recursive)
                } else if (kAudioExtensions.contains(extensionOf(entity.path))) {
                    files.add(normalizePosix(entity.path))
                }
            } catch (e: Throwable) {
                errors.add("无法读取 ${entity.path}: $e")
            }
        }
    }

    /** 递归列举 SAF 目录树，把子项解析成真实路径。 */
    private fun walkDocumentTree(
        dir: DocumentFile,
        dirReal: String?,
        files: MutableList<String>,
        errors: MutableList<String>,
        depth: Int,
        recursive: Boolean,
    ) {
        if (depth > NON_RECURSIVE_MAX_DEPTH && !recursive) return
        if (depth > MAX_DEPTH) return

        val children = try {
            dir.listFiles()
        } catch (e: Throwable) {
            errors.add("无法读取 ${dir.name ?: dir.uri}: $e")
            return
        }

        for (child in children) {
            val name = child.name
            if (child.isDirectory) {
                if (!recursive) continue
                val childReal = documentIdToRealPath(documentId(child.uri))
                    ?: joinName(dirReal, name)
                walkDocumentTree(child, childReal, files, errors, depth + 1, recursive)
                continue
            }

            if (name == null) {
                errors.add("无法得到文件名，已跳过: ${child.uri}")
                continue
            }
            if (!kAudioExtensions.contains(extensionOf(name))) continue

            val real = documentIdToRealPath(documentId(child.uri)) ?: joinName(dirReal, name)
            if (real == null) {
                // 拿不到真实路径的文件无法参与缓存键/播放，只能跳过
                errors.add("无法得到真实路径，已跳过: $name")
                continue
            }
            files.add(real)
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /**
     * 等价 Dart `path.normalize`（POSIX 风格）：折叠多余分隔符、消解 `.` 与 `..`、
     * 去掉结尾分隔符；空串返回 `.`，根目录保持 `/`。
     */
    private fun normalizePosix(raw: String): String {
        if (raw.isEmpty()) return "."
        val absolute = raw.startsWith("/")
        val out = ArrayList<String>()
        for (seg in raw.split('/')) {
            when {
                seg.isEmpty() || seg == "." -> Unit
                seg == ".." -> {
                    if (out.isNotEmpty() && out.last() != "..") {
                        out.removeAt(out.size - 1)
                    } else if (!absolute) {
                        out.add("..")
                    }
                    // 绝对路径上超出根目录的 `..` 直接丢弃（Dart 同样处理）
                }
                else -> out.add(seg)
            }
        }
        val joined = out.joinToString("/")
        return when {
            absolute -> "/$joined"
            joined.isEmpty() -> "."
            else -> joined
        }
    }

    /**
     * 取扩展名（小写、不含点），等价 Dart
     * `p.extension(path).toLowerCase().replaceFirst('.', '')`：没有扩展名时为空串。
     */
    private fun extensionOf(path: String): String {
        val slash = path.lastIndexOf('/')
        val dot = path.lastIndexOf('.')
        if (dot <= slash) return "" // 点在最后一个分隔符之前（或在目录名里）→ 无扩展名
        return path.substring(dot + 1).lowercase()
    }

    /** Dart `Uri.decodeComponent`：只处理 %XX 转义，不把 `+` 当空格。 */
    private fun decodeComponent(s: String): String = try {
        Uri.decode(s)
    } catch (e: Throwable) {
        s
    }

    /** 外部存储根目录（用于从 RELATIVE_PATH 拼回绝对路径）。 */
    @Suppress("DEPRECATION")
    private fun externalStorageRoot(): String =
        try {
            Environment.getExternalStorageDirectory().absolutePath
        } catch (e: Throwable) {
            "/storage/emulated/0"
        }

    /** `root` + `rel` + `name` 拼成规范路径（`rel` 通常以 `/` 结尾）。 */
    private fun joinUnder(root: String, rel: String, name: String): String {
        val r = rel.trim('/')
        return if (r.isEmpty()) normalizePosix("$root/$name") else normalizePosix("$root/$r/$name")
    }

    /** 已知父目录真实路径 + 子项名字拼接；父目录未知时返回 null。 */
    private fun joinName(dirReal: String?, name: String?): String? {
        if (dirReal == null || name == null) return null
        return normalizePosix("${dirReal.trimEnd('/')}/$name")
    }

    /** `DocumentsContract.getDocumentId`，失败返回 null（非文档 URI 会抛异常）。 */
    private fun documentId(uri: Uri): String? = try {
        DocumentsContract.getDocumentId(uri)
    } catch (e: Throwable) {
        null
    }

    /** `DocumentsContract.getTreeDocumentId`，失败返回 null。 */
    private fun treeDocumentId(uri: Uri): String? = try {
        DocumentsContract.getTreeDocumentId(uri)
    } catch (e: Throwable) {
        null
    }

    /**
     * 文档 id → 真实路径。
     *
     * `primary:Music/a.mp3` → `/storage/emulated/0/Music/a.mp3`；
     * `1234-5678:Podcast` → `/storage/1234-5678/Podcast`；
     * `raw:/storage/...` → 原样规范化；无法识别的提供者（裸 id）→ null。
     */
    private fun documentIdToRealPath(docId: String?): String? {
        if (docId.isNullOrEmpty()) return null
        val sep = docId.indexOf(':')
        if (sep <= 0) return null // 没有卷标（Downloads 等提供者的裸 id）→ 无法映射
        val volume = docId.substring(0, sep)
        val rel = docId.substring(sep + 1)
        if (volume == "raw") {
            return if (rel.startsWith("/")) normalizePosix(rel) else null
        }
        val volumeRoot = if (volume == "primary" || volume == "internal") {
            "/storage/emulated/0"
        } else {
            "/storage/$volume"
        }
        return if (rel.isEmpty()) normalizePosix(volumeRoot) else normalizePosix("$volumeRoot/$rel")
    }
}
