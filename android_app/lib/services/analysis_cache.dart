/// 分析结果持久化缓存：避免每次打开应用都重新 ffmpeg+FFT 解析。
///
/// 设计：
///  - 缓存目录放在应用支持目录（`getApplicationSupportDirectory`）下，随应用常驻，
///    而不是临时目录 —— 临时目录可能被系统随时清理，导致封面丢失、重新解析。
///  - 每首歌一个 JSON 条目（键 = 稳定 id），记录 BPM、可信度、时长、拍点、手动标记与实际封面文件名。
///  - 为判断「文件是否已变更」，记录源文件的 size 与 mtime；一旦源文件在磁盘上变化，
///    缓存即视为失效，重新分析（被替换/更新的歌曲会正确刷新）。
///  - 封面从临时目录拷贝进缓存目录持久保存，重新打开时不再依赖临时目录。
///  - schemaVersion：分析算法/产物格式变化时递增，强制旧条目失效并重新分析，
///    从而让 BPM/拍点的改进（例如拍子铺满整首歌）能应用到已分析过的歌曲。
library;

import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

/// 分析产物格式版本：算法/拍点结构变化时递增，使旧缓存失效并强制重新分析。
const int _schemaVersion = 2;

/// 从缓存恢复的一首歌的分析结果。
class CachedAnalysis {
  final double? bpm;
  final double? confidence;
  final double? duration;
  final double? beatOffset;
  final List<double>? beatTimes;

  /// 各节拍模式的时间轴（键为 BeatMode 名称）。
  final Map<String, List<double>>? beatMaps;

  /// 0..1 相位可靠性。
  final double? phaseReliability;

  /// 是否为用户手动指定的 BPM（True 表示不要被自动分析覆盖）。
  final bool manual;

  /// 缓存的封面文件名（位于缓存目录内），无封面为 null。
  final String? artworkFile;

  const CachedAnalysis({
    this.bpm,
    this.confidence,
    this.duration,
    this.beatOffset,
    this.beatTimes,
    this.beatMaps,
    this.phaseReliability,
    this.manual = false,
    this.artworkFile,
  });
}

/// 歌曲分析结果的磁盘缓存。线程安全通过 Dart 单 isolate 顺序执行保证。
class AnalysisCache {
  AnalysisCache._({Directory? baseDir}) : _baseDir = baseDir;

  static final AnalysisCache instance = AnalysisCache._();

  /// 测试用工厂：注入固定基目录，避免触碰平台通道。
  @visibleForTesting
  factory AnalysisCache.forTesting({required Directory baseDir}) {
    return AnalysisCache._(baseDir: baseDir);
  }

  /// 测试注入用：传入一个目录则不再查应用支持目录。
  final Directory? _baseDir;
  Directory? _dir;

  Future<Directory> _cacheDir() async {
    if (_dir != null) return _dir!;
    final base = _baseDir ?? await getApplicationSupportDirectory();
    final dir = Directory(p.join(base.path, 'analysis'));
    if (!await dir.exists()) await dir.create(recursive: true);
    _dir = dir;
    return dir;
  }

  /// 读取一条缓存。源文件已变更（size/mtime 不同）时返回 null 视为失效。
  Future<CachedAnalysis?> read(String id, String filePath) async {
    try {
      final dir = await _cacheDir();
      final f = File(p.join(dir.path, '$id.json'));
      if (!await f.exists()) return null;
      final map = jsonDecode(await f.readAsString()) as Map<String, dynamic>;

      // 源文件变更检测
      final stat = await File(filePath).stat();
      final size = map['fileSize'];
      final mtime = map['fileMtime'];
      if (size is int && size != stat.size) return null;
      if (mtime is int && mtime != stat.modified.millisecondsSinceEpoch) return null;
      // 分析产物版本不匹配 → 失效并重新分析（让算法/拍点改进应用到旧歌曲）
      if (map['schemaVersion'] != _schemaVersion) return null;

      final beatTimes = (map['beatTimes'] as List?)
          ?.map((e) => (e as num).toDouble())
          .toList();
      final beatMaps = <String, List<double>>{};
      final rawMaps = map['beatMaps'];
      if (rawMaps is Map) {
        rawMaps.forEach((k, v) {
          if (v is List) {
            beatMaps[k.toString()] = v
                .map((e) => (e as num).toDouble())
                .toList();
          }
        });
      }
      return CachedAnalysis(
        bpm: (map['bpm'] as num?)?.toDouble(),
        confidence: (map['confidence'] as num?)?.toDouble(),
        duration: (map['duration'] as num?)?.toDouble(),
        beatOffset: (map['beatOffset'] as num?)?.toDouble(),
        beatTimes: beatTimes,
        beatMaps: beatMaps.isEmpty ? null : beatMaps,
        phaseReliability: (map['phaseReliability'] as num?)?.toDouble(),
        manual: (map['manual'] as bool?) ?? false,
        artworkFile: map['artworkFile'] as String?,
      );
    } catch (_) {
      return null;
    }
  }

  /// 写入（或覆盖）一条缓存，并记录源文件大小与修改时间用于失效判断。
  Future<void> write(
    String id,
    String filePath, {
    double? bpm,
    double? confidence,
    double? duration,
    double? beatOffset,
    List<double>? beatTimes,
    Map<String, List<double>>? beatMaps,
    double? phaseReliability,
    bool manual = false,
    String? artworkFile,
  }) async {
    try {
      final dir = await _cacheDir();
      final stat = await File(filePath).stat();
      final map = <String, dynamic>{
        'schemaVersion': _schemaVersion,
        'bpm': bpm,
        'confidence': confidence,
        'duration': duration,
        'beatOffset': beatOffset,
        'beatTimes': beatTimes,
        'beatMaps': beatMaps,
        'phaseReliability': phaseReliability,
        'manual': manual,
        'artworkFile': artworkFile,
        'fileSize': stat.size,
        'fileMtime': stat.modified.millisecondsSinceEpoch,
      };
      await File(p.join(dir.path, '$id.json'))
          .writeAsString(jsonEncode(map), flush: true);
    } catch (_) {}
  }

  /// 缓存目录内某首歌的封面绝对路径；无则 null。
  Future<String?> artworkPath(String id, String? artworkFile) async {
    if (artworkFile == null) return null;
    try {
      final dir = await _cacheDir();
      final f = File(p.join(dir.path, artworkFile));
      if (await f.exists()) return f.path;
    } catch (_) {}
    return null;
  }

  /// 把 [tmpPath] 的封面拷入缓存目录，返回持久化的文件名（不含目录）。
  Future<String?> persistArtwork(String id, String tmpPath) async {
    try {
      final dir = await _cacheDir();
      final ext = p.extension(tmpPath).isEmpty ? '.jpg' : p.extension(tmpPath);
      final name = '${id}_art$ext';
      final dest = File(p.join(dir.path, name));
      await dest.writeAsBytes(await File(tmpPath).readAsBytes(), flush: true);
      return name;
    } catch (_) {
      return null;
    }
  }
}
