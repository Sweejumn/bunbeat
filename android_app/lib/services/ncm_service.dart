/// NCM 解析成可播放文件的高层服务。
///
/// 负责把 .ncm 源文件一次性解密为真实音频（mp3/flac/…）并**持久缓存**到
/// 应用支持目录，之后的 BPM 分析、播放、封面展示全部复用现有管线；
/// 只要源文件未变（size/mtime 一致）就不再重复解密 —— 避免「每次打开都重解」。
///
/// 设计要点（对齐项目里 AnalysisCache 的失效策略）：
///  - 解密缓存目录位于 getApplicationSupportDirectory()/ncm，随应用常驻。
///  - 每源文件一个输出文件 + 一个 .json 记录源 size/mtime 用于失效判断。
///  - 封面直接从 NCM 元数据里解出的 base64 图片写出（不依赖 ffmpeg）。
library;

import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import 'ncm_decoder.dart';

/// 一首 NCM 源文件解析出的可播放产物。
class NcmResolved {
  /// 解密后的音频文件绝对路径（mp3/flac/…），可直接交给播放与 BPM 分析。
  final String audioPath;

  /// 解出的内嵌封面绝对路径（jpeg/png）；无封面为 null。
  final String? coverPath;

  /// 真实音频扩展名（mp3/flac/m4a/ogg/wav）。
  final String format;

  const NcmResolved({
    required this.audioPath,
    required this.coverPath,
    required this.format,
  });
}

class NcmService {
  NcmService._();
  static final NcmService instance = NcmService._();

  Directory? _dir;

  Future<Directory> _cacheDir() async {
    if (_dir != null) return _dir!;
    final base = await getApplicationSupportDirectory();
    final dir = Directory(p.join(base.path, 'ncm'));
    if (!await dir.exists()) await dir.create(recursive: true);
    _dir = dir;
    return dir;
  }

  /// 由源路径生成稳定的缓存键（与库服务的 _stableId 一致，避免依赖其私有实现）。
  String _stableId(String s) {
    var h = 0;
    for (final code in s.codeUnits) {
      h = (h * 31 + code) & 0x7fffffff;
    }
    return h.toRadixString(16).padLeft(8, '0');
  }

  /// 解析 [ncmPath]：命中且源未变则直接返回缓存，否则解密并持久化。
  /// 返回 null 表示解密失败（调用方应把该歌曲标记为不可用）。
  Future<NcmResolved?> resolve(String ncmPath) async {
    final dir = await _cacheDir();
    final id = _stableId(ncmPath);
    final audioPath = p.join(dir.path, '$id.decoded');
    final sidecar = p.join(dir.path, '$id.json');

    // 1) 源文件信息（用于失效判断）
    late final FileStat srcStat;
    try {
      srcStat = await File(ncmPath).stat();
    } catch (e) {
      debugPrint('[NCM] 无法读取源文件 $ncmPath: $e');
      return null;
    }

    // 2) 若已有解密产物且源未变，直接复用
    final audioFile = File(audioPath);
    if (await audioFile.exists() && await File(sidecar).exists()) {
      try {
        final meta = jsonDecode(await File(sidecar).readAsString())
            as Map<String, dynamic>;
        if (meta['fileSize'] == srcStat.size &&
            meta['fileMtime'] == srcStat.modified.millisecondsSinceEpoch) {
          final cover =
              (meta['cover'] as String?)?.isNotEmpty == true
                  ? p.join(dir.path, meta['cover'] as String)
                  : null;
          debugPrint('[NCM] cache_hit ${p.basename(ncmPath)}');
          return NcmResolved(
            audioPath: audioPath,
            coverPath: (cover != null && await File(cover).exists()) ? cover : null,
            format: (meta['format'] as String?) ?? 'mp3',
          );
        }
      } catch (_) {}
    }

    // 3) 否则解密一次
    try {
      final decoded = await NcmDecoder.decodeFile(ncmPath);
      await audioFile.writeAsBytes(decoded.audio, flush: true);

      String? coverName;
      if (decoded.coverImage != null) {
        coverName = '${id}_art';
        final ext = _sniffImageExt(decoded.coverImage!);
        final coverNameFull = '$coverName.$ext';
        await File(p.join(dir.path, coverNameFull))
            .writeAsBytes(decoded.coverImage!, flush: true);
        coverName = coverNameFull;
      }

      await File(sidecar).writeAsString(
        jsonEncode({
          'fileSize': srcStat.size,
          'fileMtime': srcStat.modified.millisecondsSinceEpoch,
          'format': decoded.format,
          'cover': coverName,
        }),
        flush: true,
      );

      debugPrint('[NCM] decode_ok ${p.basename(ncmPath)} -> .${decoded.format}');
      return NcmResolved(
        audioPath: audioPath,
        coverPath: coverName != null
            ? p.join(dir.path, coverName)
            : null,
        format: decoded.format,
      );
    } catch (e) {
      debugPrint('[NCM] 解密失败 ${p.basename(ncmPath)}: $e');
      // 清理半成品，避免下次误命中
      try {
        if (await audioFile.exists()) await audioFile.delete();
      } catch (_) {}
      return null;
    }
  }

  /// 由图片魔数推断扩展名（jpeg/png），兜底 jpg。
  String _sniffImageExt(Uint8List bytes) {
    if (bytes.length >= 8 &&
        bytes[0] == 0x89 &&
        bytes[1] == 0x50 &&
        bytes[2] == 0x4E &&
        bytes[3] == 0x47) {
      return 'png';
    }
    return 'jpg';
  }
}
