/// NCM（网易云音乐 .ncm）文件解码。
///
/// 让 App 在本地直接读取/播放网易云导出的加密格式音频，而无需先手动解密。
/// 算法与常量逐条对照权威开源实现核对：
///   - nowa277/OpenConverter 的 src/decoders/ncm.js（完整参考）
///   - Johnserf-Seed/ncm2mp3（Rust，format.rs 常量注释一致）
///   - taurusxin/ncmdump（Rust 参考实现）
///
/// 文件布局：
///   偏移     长度        内容
///   ------------------------------------------------------------------
///   0        8          魔数 ASCII "CTENFDAM"
///   8        2          跳过（gap）
///   10       4          加密 key 段长度（LE）
///   14       N          加密 key：逐字节 ^0x64 → AES-128-ECB(core_key)
///                        → PKCS7 去填充 → 去掉 17 字节 "neteasecloudmusic\0"
///                        → 余下即 RC4/box 用的真正 key
///   ...      4          元数据段长度（LE，0 表示无）
///   ...      M          元数据：逐字节 ^0x63 → 去 "163 key(Don't modify):"
///                        22 字节头 → base64 解码 → AES-128-ECB(meta_key)
///                        → PKCS7 去填充 → 去 "music:" 6 字节 → JSON
///                        （含 format 字段、内嵌封面 base64 等）
///   ...      5          跳过（gap）
///   ...      4          image_space（LE，含填充的总空间）
///   ...      4          image_size（LE）
///   ...      S          封面图字节（image_size 长）；其后 (image_space-image_size) 填充
///   ...      *          音频数据：用 box key 逐字节异或还原
///
/// 音频解密用的是「改进 RC4 / box key」：由真正 key 生成 256 字节 S-box，
/// 再生成 256 字节密钥流 k[i] = S[(S[i] + S[(i + S[i]) & 0xff]) & 0xff]，
/// 音频第 i 字节异或 k[(i + 1) % 256]（密钥流整体从 1 偏移）。
library;

import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:pointycastle/export.dart';

/// 结果：解出的真实音频字节 + 元数据 + 内嵌封面。
class NcmDecoded {
  final Uint8List audio;
  final Map<String, dynamic>? meta;

  /// 内嵌封面图字节（PNG/JPEG），无则为 null。
  final Uint8List? coverImage;

  /// 音频真实格式扩展名（mp3/flac/m4a/ogg/wav）；无法识别时按 mp3 兜底。
  final String format;

  NcmDecoded({
    required this.audio,
    required this.meta,
    required this.coverImage,
    required this.format,
  });

  /// 由元数据 format 字段推断扩展名；无元数据时按 mp3。（与参考实现一致）
  String get extension => format;
}

/// 解码失败时抛出：带对用户友好的中文 message 与原始 cause。
class NcmFormatException implements Exception {
  final String message;
  final Object? cause;
  NcmFormatException(this.message, [this.cause]);
  @override
  String toString() =>
      'NcmFormatException: $message${cause != null ? ' ($cause)' : ''}';
}

class NcmDecoder {
  NcmDecoder._();

  // ---- 常量（与 OpenConverter/Johnserf-Seed 一致）----

  /// 文件魔数 "CTENFDAM"（8 字节）。
  static final Uint8List _magic =
      Uint8List.fromList('CTENFDAM'.codeUnits);

  /// 解密 key 段的 AES-128 密钥 = "hzHRAmso5kInbaxW"。
  static final Uint8List _coreKey = Uint8List.fromList(
      <int>[0x68, 0x7A, 0x48, 0x52, 0x41, 0x6D, 0x73, 0x6F, 0x35, 0x6B, 0x49,
        0x6E, 0x62, 0x61, 0x78, 0x57]);

  /// 解密元数据段的 AES-128 密钥 = "#14ljk_!\\]&0U<'("。
  static final Uint8List _metaKey = Uint8List.fromList(
      <int>[0x23, 0x31, 0x34, 0x6C, 0x6A, 0x6B, 0x5F, 0x21, 0x5C, 0x5D, 0x26,
        0x30, 0x55, 0x3C, 0x27, 0x28]);

  /// key 段按字节异或掩码。
  static const int _keyXor = 0x64;

  /// 元数据段按字节异或掩码。
  static const int _metaXor = 0x63;

  /// key 段解密后需去掉的前缀（含结尾 0）。
  static final Uint8List _keyPrefix =
      Uint8List.fromList('neteasecloudmusic'.codeUnits); // 16 字节 + \0

  /// 元数据段 base64 前的定长头部（"163 key(Don't modify):"，22 字节）。
  static final Uint8List _metaPlainPrefix =
      Uint8List.fromList('163 key(Don\'t modify):'.codeUnits);

  /// 元数据 JSON 前的 "music:" 前缀（6 字节）。
  static final Uint8List _musicPrefix =
      Uint8List.fromList('music:'.codeUnits);

  /// 防御：任一段长度超过该值即视为损坏文件。
  static const int _maxSegmentLen = 64 * 1024 * 1024;

  // ---- 主入口 ----

  /// 解码 [ncmBytes] 为真实音频。纯函数，便于单元测试。
  static NcmDecoded decode(Uint8List ncmBytes) {
    final data = ncmBytes;
    int off = 0;

    // 1) 魔数
    if (data.length < 10 || !_bytesEqual(data, 0, _magic, 0, _magic.length)) {
      throw NcmFormatException('不是有效的 NCM 文件（缺少 CTENFDAM 魔数）');
    }
    off = 10; // 8 魔数 + 2 gap

    // 2) key 段
    final keyLength = _readU32(data, off);
    off += 4;
    if (keyLength <= 0 ||
        keyLength > _maxSegmentLen ||
        off + keyLength > data.length) {
      throw NcmFormatException('无效的 key 长度: $keyLength');
    }
    var keyEnc = Uint8List.sublistView(data, off, off + keyLength);
    off += keyLength;
    keyEnc = _xorBytes(keyEnc, _keyXor);
    final keyPlain = _pkcs7Unpad(_aesEcbDecrypt(keyEnc, _coreKey), 'key');
    // 校验并去掉 "neteasecloudmusic\0" 前缀
    final prefixLen = _keyPrefix.length + 1;
    if (keyPlain.length < prefixLen ||
        !_bytesEqual(keyPlain, 0, _keyPrefix, 0, _keyPrefix.length)) {
      throw NcmFormatException('key 段解出内容异常（缺少 neteasecloudmusic 前缀）');
    }
    final rc4Key = Uint8List.sublistView(keyPlain, prefixLen);
    final sBox = _buildBox(rc4Key);

    // 3) 元数据段
    if (off + 4 > data.length) {
      throw NcmFormatException('文件过早结束（元数据长度字段）');
    }
    final metaLength = _readU32(data, off);
    off += 4;
    Map<String, dynamic>? meta;
    if (metaLength > 0) {
      if (metaLength > _maxSegmentLen || off + metaLength > data.length) {
        throw NcmFormatException('元数据长度超出文件大小');
      }
      var metaEnc = Uint8List.sublistView(data, off, off + metaLength);
      off += metaLength;
      metaEnc = _xorBytes(metaEnc, _metaXor);
      meta = _decodeMeta(metaEnc);
    }

    // 4) 5 字节 gap
    off += 5;

    // 5) 封面
    if (off + 8 > data.length) {
      throw NcmFormatException('文件过早结束（封面长度字段）');
    }
    final imageSpace = _readU32(data, off);
    off += 4;
    final imageSize = _readU32(data, off);
    off += 4;
    Uint8List? coverImage;
    if (imageSize > 0 && off + imageSize <= data.length) {
      coverImage = Uint8List.sublistView(data, off, off + imageSize);
    }
    off += imageSize;
    off += imageSpace - imageSize;
    if (off < 0 || off > data.length) {
      throw NcmFormatException('头部解析越界');
    }

    // 6) 音频
    final encryptedAudio = Uint8List.sublistView(data, off);
    final audio = _decryptAudio(sBox, encryptedAudio);

    final format = _inferFormat(meta);
    return NcmDecoded(audio: audio, meta: meta, coverImage: coverImage, format: format);
  }

  /// 从文件中读取并解码。返回 [NcmDecoded]。
  static Future<NcmDecoded> decodeFile(String path) async {
    final bytes = await File(path).readAsBytes();
    return decode(Uint8List.fromList(bytes));
  }

  /// 便捷：解码 [path]，并把音频写为 [outPath]（扩展名由 format 决定）。
  /// 返回 (输出路径, 元数据, 封面字节)。若 [coverPath] 非空且含封面则一并写出。
  static Future<NcmDecoded> decodeToFiles(
    String path, {
    required String outDir,
    required String outBase,
    String? coverPath,
  }) async {
    final decoded = await decodeFile(path);
    final out = '$outBase.${decoded.format}';
    final outFile = File(out);
    await outFile.create(recursive: true);
    await outFile.writeAsBytes(decoded.audio, flush: true);
    if (coverPath != null && decoded.coverImage != null) {
      final cf = File(coverPath);
      await cf.create(recursive: true);
      await cf.writeAsBytes(decoded.coverImage!, flush: true);
    }
    return decoded;
  }

  // ---- 内部算法 ----

  static Uint8List _aesEcbDecrypt(Uint8List block, Uint8List key) {
    final cipher = ECBBlockCipher(AESEngine())
      ..init(false, KeyParameter(Uint8List.fromList(key)));
    // PKCS7 填充会让密文长度是 16 的倍数，直接按整块处理。
    final out = Uint8List(block.length);
    var srcOff = 0;
    var dstOff = 0;
    while (srcOff < block.length) {
      final n = cipher.processBlock(block, srcOff, out, dstOff);
      srcOff += n;
      dstOff += n;
    }
    return Uint8List.sublistView(out, 0, dstOff);
  }

  static Uint8List _pkcs7Unpad(Uint8List buf, String what) {
    if (buf.isEmpty) {
      throw NcmFormatException('$what 解密结果为空，无法去填充');
    }
    final pad = buf[buf.length - 1];
    if (pad < 1 || pad > 16) {
      throw NcmFormatException('$what 的 PKCS7 填充非法: $pad');
    }
    if (pad > buf.length) {
      throw NcmFormatException('$what 的 PKCS7 填充长度异常');
    }
    return Uint8List.sublistView(buf, 0, buf.length - pad);
  }

  static Uint8List _xorBytes(Uint8List src, int byte) {
    final out = Uint8List(src.length);
    for (var i = 0; i < src.length; i++) {
      out[i] = src[i] ^ byte;
    }
    return out;
  }

  /// 由真正 key 生成 box/密钥流（与 ncmdump 经典算法一致）。
  static Uint8List _buildBox(Uint8List key) {
    final s = Uint8List(256);
    for (var i = 0; i < 256; i++) {
      s[i] = i;
    }
    var j = 0;
    for (var i = 0; i < 256; i++) {
      j = (j + s[i] + key[i % key.length]) & 0xff;
      final tmp = s[i];
      s[i] = s[j];
      s[j] = tmp;
    }
    // 生成 256 字节密钥流
    final k = Uint8List(256);
    for (var i = 0; i < 256; i++) {
      k[i] = s[(s[i] + s[(i + s[i]) & 0xff]) & 0xff];
    }
    return k;
  }

  /// 测试专用：暴露 box 生成，便于单元测试对齐参考实现。
  @visibleForTesting
  static Uint8List debugBuildBox(Uint8List key) => _buildBox(key);

  static Uint8List _decryptAudio(Uint8List k, Uint8List data) {
    final out = Uint8List(data.length);
    for (var i = 0; i < data.length; i++) {
      // 密钥流从下标 1 开始（偏移一位）
      out[i] = data[i] ^ k[(i + 1) % 256];
    }
    return out;
  }

  /// 元数据：去定长头 → base64 → AES(meta_key) → 去 "music:" → JSON。
  static Map<String, dynamic>? _decodeMeta(Uint8List metaXored) {
    if (metaXored.length < _metaPlainPrefix.length) return null;
    // 跳过 "163 key(Don't modify):" 头（22 字节）
    final b64 = String.fromCharCodes(
        metaXored.sublist(_metaPlainPrefix.length));
    final b64Trimmed = b64.trim();
    Uint8List aesBytes;
    try {
      aesBytes = base64Decode(b64Trimmed);
    } catch (_) {
      return null;
    }
    if (aesBytes.isEmpty || aesBytes.length % 16 != 0) return null;
    Uint8List plain;
    try {
      plain = _pkcs7Unpad(_aesEcbDecrypt(aesBytes, _metaKey), 'metadata');
    } catch (_) {
      return null;
    }
    if (plain.length < _musicPrefix.length) return null;
    // 元数据 JSON 为 UTF-8 编码（含中文歌名），需按 UTF-8 解码。
    String jsonStr;
    try {
      jsonStr = utf8.decode(plain.sublist(_musicPrefix.length));
    } catch (_) {
      return null;
    }
    try {
      final json = jsonDecode(jsonStr);
      return (json is Map<String, dynamic>) ? json : null;
    } catch (_) {
      return null;
    }
  }

  static String _inferFormat(Map<String, dynamic>? meta) {
    if (meta != null) {
      final f = meta['format'];
      if (f is String && f.isNotEmpty) {
        return f.toLowerCase();
      }
    }
    return 'mp3'; // 无元数据时按 mp3 兜底（参考实现一致）
  }

  // ---- 工具 ----

  static int _readU32(Uint8List b, int off) =>
      b[off] | (b[off + 1] << 8) | (b[off + 2] << 16) | (b[off + 3] << 24);

  static bool _bytesEqual(
      Uint8List a, int aOff, Uint8List b, int bOff, int len) {
    for (var i = 0; i < len; i++) {
      if (a[aOff + i] != b[bOff + i]) return false;
    }
    return true;
  }
}
