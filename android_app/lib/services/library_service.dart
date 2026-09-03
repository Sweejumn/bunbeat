/// 曲库状态服务：管理从文件夹读取到的歌曲、逐个分析 BPM、产出入列播放列表。
library;

import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/song.dart';
import 'analysis_cache.dart';
import 'audio_decode.dart';
import 'audio_player_service.dart';
import 'audio_reader.dart';
import 'ncm_service.dart';

/// 曲库高可观察状态（变更事件驱动 UI）。
class LibraryService extends ChangeNotifier {
  final List<Song> _songs = [];
  String? folderPath;
  int _analyzingTotal = 0;
  int _analyzingDone = 0;

  /// 缓存的歌曲 id（路径 hash），用于缓存文件名与播放列表键。
  static const _kLastFolder = 'last_folder';
  static const _kTargetBpm = 'target_bpm';

  /// 当前选中的目标 BPM（推荐与入队用）。设置时持久化，退出后下次启动恢复。
  double _targetBpm = 155;
  double get targetBpm => _targetBpm;
  set targetBpm(double v) {
    if (_targetBpm == v) return;
    _targetBpm = v;
    notifyListeners();
    _persistTargetBpm(v);
  }

  Future<void> _persistTargetBpm(double v) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setDouble(_kTargetBpm, v);
    } catch (_) {}
  }

  /// 启动时恢复上次保存的目标 BPM（未保存过则保持默认）。
  Future<void> loadTargetBpm() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final v = prefs.getDouble(_kTargetBpm);
      if (v != null && v > 0) {
        // 直接写私有字段避免 setter 触发多余的通知/写盘。
        _targetBpm = v;
        notifyListeners();
      }
    } catch (_) {}
  }

  List<Song> get songs => List.unmodifiable(_songs);
  bool get isAnalyzing => _analyzingTotal > 0 && _analyzingDone < _analyzingTotal;

  /// 替换曲库为给定文件夹中扫描到的音频文件。
  ///
  /// 已解析过且源文件未变化的歌曲直接走缓存秒开，不重新 ffmpeg+FFT；
  /// 只有新文件或内容变化的文件才真正分析。
  Future<void> loadFolder(FolderPick pick) async {
    folderPath = pick.path;
    await _rememberFolder(pick.path);
    _songs.clear();
    final dir = await _tempDir();
    _analyzingTotal = pick.audioFiles.length;
    _analyzingDone = 0;
    notifyListeners();

    for (final path in pick.audioFiles) {
      final id = _stableId(path);
      final song = Song(
        id: id,
        filePath: path,
        filename: p.basename(path),
        title: p.basenameWithoutExtension(path),
        artist: '未知',
        bpmStatus: BpmStatus.pending,
      );
      await _prepareNcm(song);
      _songs.add(song);
      notifyListeners();
      await _loadSong(song, dir);
    }
    _analyzingTotal = 0;
    _analyzingDone = 0;
    notifyListeners();
  }

  /// 若 [song] 源为 .ncm，先解密为可播放文件并缓存；成功则把 song 指向解密产物。
  /// 解密失败时标记为 failed（附加错误信息），不阻断整批导入。
  Future<void> _prepareNcm(Song song) async {
    final ext = p.extension(song.filePath).toLowerCase();
    if (ext != '.ncm') return; // 普通音频无需处理

    final resolved = await NcmService.instance.resolve(song.filePath);
    if (resolved == null) {
      song.bpmStatus = BpmStatus.failed;
      song.bpmError = 'NCM 解密失败，无法读取';
      return;
    }
    // 指向解密后的可播放文件；标题仍用原始 .ncm 名。
    song.filePath = resolved.audioPath;
    song.artworkPath = resolved.coverPath;
  }

  /// 单首歌：先查缓存，命中则秒开；未命中/文件已变更才分析。
  Future<void> _loadSong(Song song, String workDir) async {
    final cached = await AnalysisCache.instance.read(song.id, song.filePath);
    if (cached != null) {
      debugPrint(
          '[RUNBPM] cache_hit ${song.filename} bpm=${cached.bpm?.toStringAsFixed(1)} manual=${cached.manual}');
      song.bpmStatus = BpmStatus.done;
      song.originalBpm = cached.bpm;
      song.bpmConfidence = cached.confidence;
      song.duration = cached.duration;
      song.beatOffset = cached.beatOffset;
      song.beatTimes = cached.beatTimes;
      song.beatMaps = cached.beatMaps;
      song.phaseReliability = cached.phaseReliability;
      // 仅当缓存里有持久化封面才覆盖；否则保留 NCM 解密出的内嵌封面。
      if (cached.artworkFile != null) {
        song.artworkPath = await AnalysisCache.instance
            .artworkPath(song.id, cached.artworkFile);
      }
      _analyzingDone++;
      notifyListeners();
      return;
    }
    await _analyze(song, workDir);
  }

  /// 是否解析了足够帧用于显示分析进度。
  int get analyzingTotal => _analyzingTotal;
  int get analyzingDone => _analyzingDone;

  Future<void> _analyze(Song song, String workDir) async {
    debugPrint('[RUNBPM] analyze_start ${song.filename}');
    song.bpmStatus = BpmStatus.analyzing;
    notifyListeners();
    final wav = p.join(workDir, '${_stableId(song.id)}.wav');
    // 提取内嵌封面（失败则保持 artwork 为 null，不影响 BPM 分析）。
    final art = p.join(workDir, '${_stableId(song.id)}_art.jpg');
    String? persistedArtwork;
    try {
      final res = await AudioDecodeService.decodeAndAnalyze(song.filePath, wav);
      if (res.error != null || res.bpm == null) {
        song.bpmStatus = BpmStatus.failed;
        song.bpmError = res.error ?? '无法可靠检测 BPM';
      } else {
        song.bpmStatus = BpmStatus.done;
        song.originalBpm = res.bpm;
        song.bpmConfidence = res.confidence;
        song.duration = res.duration;
        song.beatOffset = res.beatOffset;
        song.beatTimes = res.beatTimes;
        song.beatMaps = res.beatMaps;
        song.phaseReliability = res.phaseReliability;
      }
      // 封面提取从独立的 ffmpeg 会话进行，双向都要 try。
      try {
        final ok = await AudioDecodeService.extractEmbeddedArtwork(song.filePath, art);
        // 提取出的文件可能极小/损坏，简单校验非空即采用。
        if (ok && await File(art).length() > 0) {
          // 持久化：拷入缓存目录，避免临时目录被清理后丢失封面。
          persistedArtwork = await AnalysisCache.instance.persistArtwork(song.id, art);
          song.artworkPath = await AnalysisCache.instance
              .artworkPath(song.id, persistedArtwork);
        }
      } catch (_) {}
      // 分析完成后把结果写入缓存（含失败的标记不写失败，只写成功缓存）。
      if (song.bpmStatus == BpmStatus.done) {
        await AnalysisCache.instance.write(
          song.id,
          song.filePath,
          bpm: song.originalBpm,
          confidence: song.bpmConfidence,
          duration: song.duration,
          beatOffset: song.beatOffset,
          beatTimes: song.beatTimes,
          beatMaps: song.beatMaps,
          phaseReliability: song.phaseReliability,
          artworkFile: persistedArtwork,
        );
      }
    } catch (e) {
      song.bpmStatus = BpmStatus.failed;
      song.bpmError = '分析失败: $e';
    } finally {
      try {
        if (await File(wav).exists()) await File(wav).delete();
      } catch (_) {}
      // 若分析失败，清掉可能残留的空封面文件。
      if (song.bpmStatus == BpmStatus.failed) {
        try {
          if (await File(art).exists()) await File(art).delete();
        } catch (_) {}
      }
      _analyzingDone++;
      notifyListeners();
    }
  }

  /// 重试分析某首（此前失败的）歌曲：重置为 pending 后重新走一遍 _analyze。
  Future<void> retryAnalyze(Song song) async {
    song.bpmStatus = BpmStatus.pending;
    song.bpmError = null;
    notifyListeners();
    final dir = await _tempDir();
    await _analyze(song, dir);
  }

  /// 手动修改某首歌的 BPM（持久化，重启后仍保留）。
  void setManualBpm(Song song, double bpm) {
    song.originalBpm = bpm;
    song.bpmConfidence = 1.0;
    song.bpmStatus = BpmStatus.done;
    song.bpmError = null;
    notifyListeners();
    // 保留已持久化的封面文件名（若有），并标记 manual=true，
    // 让下次走缓存时不会用自动分析结果覆盖用户手动的 BPM。
    AnalysisCache.instance.read(song.id, song.filePath).then((cached) {
      AnalysisCache.instance.write(
        song.id,
        song.filePath,
        bpm: bpm,
        confidence: 1.0,
        duration: song.duration,
        beatOffset: song.beatOffset,
        beatTimes: song.beatTimes,
        beatMaps: cached?.beatMaps ?? song.beatMaps,
        phaseReliability: cached?.phaseReliability ?? song.phaseReliability,
        manual: true,
        artworkFile: cached?.artworkFile,
      );
    });
  }

  /// 规则推荐：按 |原BPM − 目标BPM| 升序，低置信度靠后，并给星级。
  List<Recommendation> recommend({double? target}) {
    final t = target ?? targetBpm;
    final eligible = _songs.where((s) => s.hasBpm).toList();
    final scored = eligible.map((s) {
      var dist = (s.originalBpm! - t).abs();
      final conf = s.bpmConfidence ?? 0.0;
      if (conf < 0.4) dist += 4.0;
      return Recommendation(song: s, distance: dist, score: _stars(dist));
    }).toList();
    scored.sort((a, b) {
      final d = a.distance.compareTo(b.distance);
      if (d != 0) return d;
      return -(a.song.bpmConfidence ?? 0).compareTo(b.song.bpmConfidence ?? 0);
    });
    return scored;
  }

  /// 把推荐结果入队为一个连续播放列表（保持音高的变速播放）。
  List<PlaylistItem> buildPlaylist(List<Song> selected, {double? target}) {
    final t = target ?? targetBpm;
    return selected
        .where((s) => s.hasBpm)
        .map((s) => PlaylistItem(
              filePath: s.filePath,
              originalBpm: s.originalBpm!,
              targetBpm: t,
            ))
        .toList();
  }

  int _stars(double d) {
    if (d <= 3) return 5;
    if (d <= 8) return 4;
    if (d <= 16) return 3;
    if (d <= 28) return 2;
    return 1;
  }

  /// 稳定的 id（路径 hash），用于缓存文件名与播放列表键。
  String _stableId(String s) {
    var h = 0;
    for (final code in s.codeUnits) {
      h = (h * 31 + code) & 0x7fffffff;
    }
    return h.toRadixString(16).padLeft(8, '0');
  }

  Future<String> _tempDir() async {
    final dir = await getTemporaryDirectory();
    final sub = Directory(p.join(dir.path, 'runbpm_analysis'));
    if (!await sub.exists()) await sub.create(recursive: true);
    return sub.path;
  }

  /// 记住上次选择的文件夹（SharedPreferences），下次启动直接恢复。
  Future<void> _rememberFolder(String? path) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      if (path == null) {
        await prefs.remove(_kLastFolder);
      } else {
        await prefs.setString(_kLastFolder, path);
      }
    } catch (_) {}
  }

  /// 上次选择的文件夹路径；null 表示从未选择过。
  Future<String?> lastFolder() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      return prefs.getString(_kLastFolder);
    } catch (_) {
      return null;
    }
  }

  /// 启动时恢复上次文件夹并载入（命中缓存即秒开，不重新解析）。
  Future<void> restoreLastFolder() async {
    final folder = await lastFolder();
    debugPrint('[RUNBPM] restore_last_folder folder=$folder');
    if (folder == null || folder.isEmpty) return;
    try {
      final pick = await AudioReader().scanFolder(folder);
      debugPrint('[RUNBPM] restore_scanned files=${pick.audioFiles.length}');
      if (pick.audioFiles.isNotEmpty) {
        await loadFolder(pick);
      }
    } catch (_) {}
  }
}
