package com.bunbeat.nativeapp.audio

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * NCM（网易云音乐 .ncm）文件解码。
 *
 * 让 App 在本地直接读取/播放网易云导出的加密格式音频，而无需先手动解密。
 * 算法与常量逐条对照权威开源实现核对（现在是 Dart `ncm_decoder.dart` 的逐行移植）：
 *   - nowa277/OpenConverter 的 src/decoders/ncm.js（完整参考）
 *   - Johnserf-Seed/ncm2mp3（Rust，format.rs 常量注释一致）
 *   - taurusxin/ncmdump（Rust 参考实现）
 *
 * 文件布局：
 *   偏移     长度        内容
 *   ------------------------------------------------------------------
 *   0        8          魔数 ASCII "CTENFDAM"
 *   8        2          跳过（gap）
 *   10       4          加密 key 段长度（LE）
 *   14       N          加密 key：逐字节 ^0x64 → AES-128-ECB(core_key)
 *                        → PKCS7 去填充 → 去掉 17 字节 "neteasecloudmusic\0"
 *                        → 余下即 RC4/box 用的真正 key
 *   ...      4          元数据段长度（LE，0 表示无）
 *   ...      M          元数据：逐字节 ^0x63 → 去 "163 key(Don't modify):" 22 字节头
 *                        → base64 解码 → AES-128-ECB(meta_key) → PKCS7 去填充
 *                        → 去 "music:" 6 字节 → JSON（含 format 字段、内嵌封面 base64 等）
 *   ...      5          跳过（gap）
 *   ...      4          image_space（LE，含填充的总空间）
 *   ...      4          image_size（LE）
 *   ...      S          封面图字节（image_size 长）；其后 (image_space-image_size) 填充
 *   ...      *          音频数据：用 box key 逐字节异或还原
 *
 * 音频解密用的是「改进 RC4 / box key」：由真正 key 生成 256 字节 S-box，
 * 再生成 256 字节密钥流 k[i] = S[(S[i] + S[(i + S[i]) & 0xff]) & 0xff]，
 * 音频第 i 字节异或 k[(i + 1) % 256]（密钥流整体从 1 偏移）。
 */
object NcmDecoder {

    private const val TAG = "BunbeatDecode"

    /** 文件魔数 "CTENFDAM"（8 字节）。 */
    private val MAGIC = "CTENFDAM".toByteArray(Charsets.US_ASCII)

    /** 解密 key 段的 AES-128 密钥 = "hzHRAmso5kInbaxW"。 */
    private val CORE_KEY = byteArrayOf(
        0x68, 0x7A, 0x48, 0x52, 0x41, 0x6D, 0x73, 0x6F,
        0x35, 0x6B, 0x49, 0x6E, 0x62, 0x61, 0x78, 0x57,
    )

    /** 解密元数据段的 AES-128 密钥 = "#14ljk_!\]&0U<'("。 */
    private val META_KEY = byteArrayOf(
        0x23, 0x31, 0x34, 0x6C, 0x6A, 0x6B, 0x5F, 0x21,
        0x5C, 0x5D, 0x26, 0x30, 0x55, 0x3C, 0x27, 0x28,
    )

    /** key 段按字节异或掩码。 */
    private const val KEY_XOR = 0x64

    /** 元数据段按字节异或掩码。 */
    private const val META_XOR = 0x63

    /** key 段解密后需去掉的前缀（16 字节 + 结尾 \0）。 */
    private val KEY_PREFIX = "neteasecloudmusic".toByteArray(Charsets.US_ASCII)

    /** 元数据段 base64 前的定长头部（"163 key(Don't modify):"，22 字节）。 */
    private val META_PLAIN_PREFIX = "163 key(Don't modify):".toByteArray(Charsets.US_ASCII)

    /** 元数据 JSON 前的 "music:" 前缀（6 字节）。 */
    private val MUSIC_PREFIX = "music:".toByteArray(Charsets.US_ASCII)

    /** 防御：任一段长度超过该值即视为损坏文件。 */
    private const val MAX_SEGMENT_LEN = 64L * 1024 * 1024

    private val mutex = Mutex()

    /** 是否 .ncm 源文件（Dart 侧用 `p.extension(path) == '.ncm'` 判断）。 */
    fun isNcm(path: String): Boolean =
        path.substringBefore('?').substringBefore('#')
            .substringAfterLast('.', "")
            .equals("ncm", ignoreCase = true)

    /**
     * 把 [srcPath] 解密为可直接播放的音频文件，返回其绝对路径；失败返回 null。
     *
     * 产物落在 `cacheDir/ncm/<路径 hash>.<真实格式扩展名>`（扩展名取自 NCM 元数据的 format：
     * mp3/flac/m4a/ogg/wav，无元数据按 Dart 参考实现兜底 mp3）。
     * 另写一个 `<hash>.json` 记录源文件 size/mtime 用于失效判断 —— 源未变时直接复用，
     * 与 Dart `NcmService.resolve` 的策略一致；有内嵌封面时顺带写出 `<hash>.art.jpg|png`。
     */
    suspend fun decrypt(context: Context, srcPath: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock { decryptLocked(context.applicationContext, srcPath) }
    }

    private fun decryptLocked(context: Context, srcPath: String): String? {
        val src = File(srcPath)
        if (!src.exists() || !src.isFile) {
            Log.w(TAG, "ncm_fail 源文件不存在: $srcPath")
            return null
        }
        if (!looksLikeNcm(src)) {
            Log.w(TAG, "ncm_fail 不是 NCM 文件（缺少 CTENFDAM 魔数）: ${src.name}")
            return null
        }
        // 说明：Dart 版把解密产物放在 application support 目录（常驻）；这里按约定放
        // cacheDir/ncm —— 系统清理该目录后，歌曲文件会缺失，下次调用会重新解密
        // （sidecar 里的 size/mtime 校验可保证不会命中半成品）。
        val dir = File(context.cacheDir, "ncm")
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "ncm_fail 无法创建解密缓存目录: ${dir.absolutePath}")
            return null
        }
        val id = stableId(srcPath)
        val sidecar = File(dir, "$id.json")
        val facts = src.length() to src.lastModified()

        // 1) 命中且源未变（size/mtime 一致）直接复用，避免每次打开都重解一遍。
        val cached = readSidecar(sidecar)
        if (cached != null && cached.fileSize == facts.first && cached.fileMtime == facts.second) {
            val audio = File(dir, "$id.${cached.format}")
            if (audio.exists() && audio.length() > 0) {
                Log.i(TAG, "ncm_cache_hit ${src.name}")
                return audio.absolutePath
            }
        }

        // 2) 解密一次并落盘
        try {
            val decoded = decode(src.readBytes())
            val audio = File(dir, "$id.${decoded.format}")
            audio.writeBytes(decoded.audio)

            var coverName: String? = null
            val cover = decoded.coverImage
            if (cover != null && cover.isNotEmpty()) {
                val name = "$id.art.${if (isPng(cover)) "png" else "jpg"}"
                File(dir, name).writeBytes(cover)
                coverName = name
            }

            val meta = JSONObject()
            meta.put("fileSize", facts.first)
            meta.put("fileMtime", facts.second)
            meta.put("format", decoded.format)
            meta.put("cover", coverName ?: JSONObject.NULL)
            sidecar.writeText(meta.toString(), Charsets.UTF_8)

            Log.i(
                TAG,
                "ncm_ok ${src.name} -> ${audio.name} (${decoded.audio.size}B" +
                    (coverName?.let { ", cover=$it" } ?: "") + ")",
            )
            return audio.absolutePath
        } catch (t: Throwable) {
            Log.w(TAG, "ncm_fail ${src.name}: ${t.javaClass.simpleName}: ${t.message}")
            // 清理半成品（音频 / 封面 / sidecar），避免下次误命中损坏结果。
            try {
                dir.listFiles()?.forEach { f ->
                    if (f.name.startsWith("$id.")) f.delete()
                }
            } catch (ignored: Throwable) {
                // 忽略：清理失败不影响「返回 null」这一结论
            }
            return null
        }
    }

    // ---- 主解码（纯函数，便于对齐参考实现做单测） ----

    /** 解码 [bytes] 为真实音频。纯函数：只依赖入参，不做任何 IO。 */
    fun decode(bytes: ByteArray): NcmDecoded {
        val n = bytes.size.toLong()

        // 1) 魔数
        if (n < 10 || !bytesEqual(bytes, 0, MAGIC, 0, MAGIC.size)) {
            throw NcmFormatException("不是有效的 NCM 文件（缺少 CTENFDAM 魔数）")
        }
        var off = 10L // 8 魔数 + 2 gap

        // 2) key 段
        val keyLength = readU32(bytes, off.toInt())
        off += 4
        if (keyLength <= 0 || keyLength > MAX_SEGMENT_LEN || off + keyLength > n) {
            throw NcmFormatException("无效的 key 长度: $keyLength")
        }
        val keyEnc = xorBytes(bytes.copyOfRange(off.toInt(), (off + keyLength).toInt()), KEY_XOR)
        off += keyLength
        val keyPlain = pkcs7Unpad(aesEcbDecrypt(keyEnc, CORE_KEY), "key")
        // 校验并去掉 "neteasecloudmusic\0" 前缀
        val prefixLen = KEY_PREFIX.size + 1
        if (keyPlain.size < prefixLen || !bytesEqual(keyPlain, 0, KEY_PREFIX, 0, KEY_PREFIX.size)) {
            throw NcmFormatException("key 段解出内容异常（缺少 neteasecloudmusic 前缀）")
        }
        val rc4Key = keyPlain.copyOfRange(prefixLen, keyPlain.size)
        val sBox = buildBox(rc4Key)

        // 3) 元数据段
        if (off + 4 > n) {
            throw NcmFormatException("文件过早结束（元数据长度字段）")
        }
        val metaLength = readU32(bytes, off.toInt())
        off += 4
        var meta: JSONObject? = null
        if (metaLength > 0) {
            if (metaLength > MAX_SEGMENT_LEN || off + metaLength > n) {
                throw NcmFormatException("元数据长度超出文件大小")
            }
            val metaEnc = xorBytes(bytes.copyOfRange(off.toInt(), (off + metaLength).toInt()), META_XOR)
            off += metaLength
            meta = decodeMeta(metaEnc)
        }

        // 4) 5 字节 gap
        off += 5

        // 5) 封面
        if (off + 8 > n) {
            throw NcmFormatException("文件过早结束（封面长度字段）")
        }
        val imageSpace = readU32(bytes, off.toInt())
        off += 4
        val imageSize = readU32(bytes, off.toInt())
        off += 4
        var coverImage: ByteArray? = null
        if (imageSize > 0 && off + imageSize <= n) {
            coverImage = bytes.copyOfRange(off.toInt(), (off + imageSize).toInt())
        }
        off += imageSize
        off += imageSpace - imageSize
        if (off < 0 || off > n) {
            throw NcmFormatException("头部解析越界")
        }

        // 6) 音频
        val encryptedAudio = bytes.copyOfRange(off.toInt(), bytes.size)
        val audio = decryptAudio(sBox, encryptedAudio)

        return NcmDecoded(
            audio = audio,
            meta = meta,
            coverImage = coverImage,
            format = inferFormat(meta),
        )
    }

    // ---- 内部算法 ----

    /** AES-128-ECB/NoPadding 解密（PKCS7 填充由调用方去除，密文长度必须是 16 的倍数）。 */
    private fun aesEcbDecrypt(block: ByteArray, key: ByteArray): ByteArray {
        if (block.isEmpty() || block.size % 16 != 0) {
            throw NcmFormatException("AES 密文长度不是 16 的倍数: ${block.size}")
        }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(block)
    }

    private fun pkcs7Unpad(buf: ByteArray, what: String): ByteArray {
        if (buf.isEmpty()) {
            throw NcmFormatException("$what 解密结果为空，无法去填充")
        }
        val pad = buf[buf.size - 1].toInt() and 0xFF
        if (pad < 1 || pad > 16) {
            throw NcmFormatException("$what 的 PKCS7 填充非法: $pad")
        }
        if (pad > buf.size) {
            throw NcmFormatException("$what 的 PKCS7 填充长度异常")
        }
        return buf.copyOfRange(0, buf.size - pad)
    }

    private fun xorBytes(src: ByteArray, byte: Int): ByteArray {
        val out = ByteArray(src.size)
        for (i in src.indices) {
            out[i] = (src[i].toInt() xor byte).toByte()
        }
        return out
    }

    /** 由真正 key 生成 box/密钥流（与 ncmdump 经典算法一致）。 */
    private fun buildBox(key: ByteArray): ByteArray {
        if (key.isEmpty()) {
            throw NcmFormatException("RC4 key 为空")
        }
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
        }
        // 生成 256 字节密钥流
        val k = ByteArray(256)
        for (i in 0 until 256) {
            k[i] = s[(s[i] + s[(i + s[i]) and 0xFF]) and 0xFF].toByte()
        }
        return k
    }

    private fun decryptAudio(k: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        for (i in data.indices) {
            // 密钥流从下标 1 开始（偏移一位）
            out[i] = (data[i].toInt() xor k[(i + 1) and 0xFF].toInt()).toByte()
        }
        return out
    }

    /** 元数据：去定长头 → base64 → AES(meta_key) → 去 "music:" → JSON。 */
    private fun decodeMeta(metaXored: ByteArray): JSONObject? {
        if (metaXored.size < META_PLAIN_PREFIX.size) return null
        // 跳过 "163 key(Don't modify):" 头（22 字节）；base64 是 ASCII，按 Latin-1 逐字节映射。
        val b64 = String(
            metaXored,
            META_PLAIN_PREFIX.size,
            metaXored.size - META_PLAIN_PREFIX.size,
            Charsets.ISO_8859_1,
        ).trim()
        val aesBytes = try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (t: Throwable) {
            return null
        }
        if (aesBytes.isEmpty() || aesBytes.size % 16 != 0) return null
        val plain = try {
            pkcs7Unpad(aesEcbDecrypt(aesBytes, META_KEY), "metadata")
        } catch (t: Throwable) {
            return null
        }
        if (plain.size < MUSIC_PREFIX.size) return null
        // 元数据 JSON 为 UTF-8 编码（含中文歌名），需按 UTF-8 解码。
        val jsonStr = try {
            String(plain, MUSIC_PREFIX.size, plain.size - MUSIC_PREFIX.size, Charsets.UTF_8)
        } catch (t: Throwable) {
            return null
        }
        return try {
            JSONObject(jsonStr)
        } catch (t: Throwable) {
            null
        }
    }

    /** 由元数据 format 字段推断扩展名；无元数据时按 mp3（与参考实现一致）。 */
    private fun inferFormat(meta: JSONObject?): String {
        // 与 Dart 一致：只接受字符串且非空（数字/布尔等异常值一律兜底 mp3）。
        val raw = meta?.opt("format")
        if (raw is String && raw.isNotEmpty()) return raw.lowercase()
        return "mp3"
    }

    // ---- 工具 ----

    /** NCM 头部探测：只读前 8 字节，避免对普通音频做整文件读入。 */
    private fun looksLikeNcm(file: File): Boolean = try {
        file.inputStream().use { input ->
            val head = ByteArray(MAGIC.size)
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            read == head.size && bytesEqual(head, 0, MAGIC, 0, MAGIC.size)
        }
    } catch (t: Throwable) {
        false
    }

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()

    /** 与 Dart `NcmService._stableId` / `LibraryService._stableId` 一致的 31 进制 hash。 */
    private fun stableId(s: String): String {
        var h = 0
        for (ch in s) {
            h = (h * 31 + ch.code) and 0x7fffffff
        }
        return h.toString(16).padStart(8, '0')
    }

    private fun readSidecar(file: File): CachedNcm? = try {
        if (!file.exists()) {
            null
        } else {
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            CachedNcm(
                fileSize = obj.optLong("fileSize", -1L),
                fileMtime = obj.optLong("fileMtime", -1L),
                format = obj.optString("format").takeIf { it.isNotEmpty() } ?: "mp3",
            )
        }
    } catch (t: Throwable) {
        null
    }

    private fun readU32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun bytesEqual(a: ByteArray, aOff: Int, b: ByteArray, bOff: Int, len: Int): Boolean {
        for (i in 0 until len) {
            if (a[aOff + i] != b[bOff + i]) return false
        }
        return true
    }

    private data class CachedNcm(val fileSize: Long, val fileMtime: Long, val format: String)
}

/** 结果：解出的真实音频字节 + 元数据 + 内嵌封面（对应 Dart `NcmDecoded`）。 */
class NcmDecoded(
    val audio: ByteArray,
    val meta: JSONObject?,
    /** 内嵌封面图字节（PNG/JPEG），无则为 null。 */
    val coverImage: ByteArray?,
    /** 音频真实格式扩展名（mp3/flac/m4a/ogg/wav）；无法识别时按 mp3 兜底。 */
    val format: String,
) {
    /** 与 Dart 的 `extension` getter 一致：扩展名即 format。 */
    val extension: String get() = format
}

/** 解码失败时抛出：带对用户友好的中文 message 与原始 cause（对应 Dart `NcmFormatException`）。 */
class NcmFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
