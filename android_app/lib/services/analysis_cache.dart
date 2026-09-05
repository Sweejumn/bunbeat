/// 分析结果持久化缓存：避免每次打开应用都重新 ffmpeg+FFT 解析。
///
/// 设计：
///  - 缓存目录放在应用支持目录（`getApplicationSupportDirectory`）下，随应用常驻，
///    而不是临时目录 —— 临时目录可能被系统随时清理，导致封面丢失、重新解析。
///  - 每首歌一个 JSON 条目（键 = 稳定 id），记录 BPM、可信度、时长、拍点、手动标记与实际封面文件名。
///  - 为判断「文件是否已变更」，记录源文件的 size 与 mtime；一旦源文件在磁盘上变化，
///    缓存即视为失效，重新分析（被替换/更新的歌曲会正确刷新）。
///  - 封面从临时目录拷贝进缓存目录持久保存，重新打开时不再依赖临时目录。
///  - schemaVersion 5（v5）：记录「产生结果的算法编号」+「各算法最近一次 BPM 历史」。
///    算法升级时旧缓存【不再失效】——旧结果照常秒开显示并标记 stale（旧算法结果），
///    由调用方在后台自动用当前算法重测，从而保留不同算法测得的 BPM，
///    无需用户逐首手动点击「重新测量」。手动 BPM 永远优先，不受重测覆盖。
library;

import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

/// 分析产物格式版本。
/// v4：BPM 保留真实精度；可信度为「拍子对齐度」语义。
/// v5：新增 algorithm（产生结果的算法）与 byAlgorithm（各算法 BPM 历史）字段。
/// v5 及更高均可读取（>= 4 的旧缓存视为旧算法结果，标记 stale 后后台自动重测）。
const int _schemaVersion = 5;

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

  /// 产生当前结果的算法编号；旧格式缓存（未记录）为 null。
  final int? algorithm;

  /// 结果是否由旧算法产生（当前活动算法 ≠ algorithm）。为 true 时，
  /// 调用方应秒开显示并在后台自动用当前算法重测，而不是让用户手动重测。
  final bool stale;

  /// 各算法最近一次测得的 BPM 历史（键 = 算法编号字符串）。保留不同算法结果。
  final Map<String, double> byAlgorithm;

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
    this.algorithm,
    this.stale = false,
    this.byAlgorithm = const {},
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
  ///
  /// [currentAlgorithm]：当前活动算法编号。结果由旧算法产生（或旧格式无记录）时，
  /// 仍正常返回结果并令 [CachedAnalysis.stale] 为 true，供调用方秒开 + 后台自动重测。
  Future<CachedAnalysis?> read(String id, String filePath,
      {int? currentAlgorithm}) async {
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
      if (mtime is int && mtime != stat.modified.millisecondsSinceEpoch) {
        return null;
      }
      // 缓存格式过旧（v4 之前无法可靠解析）→ 失效并重新分析。
      final rawVersion = map['schemaVersion'];
      if (rawVersion is int && rawVersion < 4) return null;

      final algorithm = map['algorithm'] as int?;
      final byAlgorithm = <String, double>{};
      final rawByAlgo = map['byAlgorithm'];
      if (rawByAlgo is Map) {
        rawByAlgo.forEach((k, v) {
          if (k is String && v is num) byAlgorithm[k] = v.toDouble();
        });
      }

      final beatTimes = (map['beatTimes'] as List?)
          ?.map((e) => (e as num).toDouble())
          .toList();
      final beatMaps = <String, List<double>>{};
      final rawMaps = map['beatMaps'];
      if (rawMaps is Map) {
        rawMaps.forEach((k, v) {
          if (v is List) {
            beatMaps[k.toString()] = v.map((e) => (e as num).toDouble()).toList();
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
        algorithm: algorithm,
        stale: currentAlgorithm != null && algorithm != currentAlgorithm,
        byAlgorithm: byAlgorithm,
      );
    } catch (_) {
      return null;
    }
  }

  /// 写入（或覆盖）一条缓存，并记录源文件大小与修改时间用于失效判断。
  ///
  /// [algorithm]：产生该结果的算法编号。写入时会合并已有 byAlgorithm 历史，
  /// 保留其它算法测得的 BPM；本算法条目更新为本次结果。
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
    int? algorithm,
  }) async {
    try {
      final dir = await _cacheDir();
      // 合并已有 byAlgorithm 历史，避免覆盖其它算法测得的 BPM。
      final byAlgorithm = <String, double>{};
      final oldFile = File(p.join(dir.path, '$id.json'));
      if (await oldFile.exists()) {
        try {
          final old = jsonDecode(await oldFile.readAsString()) as Map<String, dynamic>;
          final raw = old['byAlgorithm'];
          if (raw is Map) {
            raw.forEach((k, v) {
              if (k is String && v is num) byAlgorithm[k] = v.toDouble();
            });
          }
        } catch (_) {}
      }
      if (algorithm != null && bpm != null) {
        byAlgorithm[algorithm.toString()] = bpm;
      }

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
        'algorithm': algorithm,
        'byAlgorithm': byAlgorithm.isEmpty ? null : byAlgorithm,
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
