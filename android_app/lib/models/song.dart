/// 一首从所选文件夹中读取的音乐。
library;

enum BpmStatus { pending, analyzing, done, failed }

/// 可选的节拍模式（与 Web 版一致）。
enum BeatMode {
  /// 固定拍子：完全等距，默认推荐。
  grid,

  /// 跟随起音：±12% 内吸附打击点。
  snap,
}

/// 节拍模式的展示信息（与 Web 版 BEAT_MODES 一致）。
class BeatModeDef {
  final BeatMode id;
  final String label;
  final String desc;
  const BeatModeDef(this.id, this.label, this.desc);
}

const List<BeatModeDef> kBeatModes = [
  BeatModeDef(BeatMode.grid, '固定拍子', '完全等距 · 默认推荐'),
  BeatModeDef(BeatMode.snap, '跟随起音', '±12% 吸附打击点'),
];

class Song {
  /// 唯一 id（用文件路径 hash），便于做播放列表与缓存键。
  final String id;

  /// 可播放文件路径。普通音频 = 源文件；NCM 源在解密后会被重定向到
  /// 缓存目录里的真实音频文件（标题/文件名仍保留原始 .ncm 信息）。
  String filePath;
  final String filename;

  String title;
  String artist;

  double? duration; // 秒
  double? originalBpm;
  double? bpmConfidence; // 0..1
  BpmStatus bpmStatus;
  String? bpmError;

  /// 分析出第一拍在原始时间轴上的秒数（节拍相位锚点）。
  double? beatOffset;

  /// 固定拍子时间轴（秒），供节拍器逐拍对齐；为 null 时回退到等距网格。
  List<double>? beatTimes;

  /// 各节拍模式的时间轴（秒），键为 BeatMode 名称；grid 与 beatTimes 等价。
  Map<String, List<double>>? beatMaps;

  /// 0..1：两个独立相位信号是否一致（低 = 建议手动校准）。null 表示未评估。
  double? phaseReliability;

  /// 内嵌封面图的缓存文件路径（jpeg/png），null 表示无封面。
  String? artworkPath;

  Song({
    required this.id,
    required this.filePath,
    required this.filename,
    required this.title,
    required this.artist,
    this.duration,
    this.originalBpm,
    this.bpmConfidence,
    this.bpmStatus = BpmStatus.pending,
    this.bpmError,
    this.beatOffset,
    this.beatTimes,
    this.beatMaps,
    this.phaseReliability,
    this.artworkPath,
  });

  bool get hasBpm => originalBpm != null && bpmStatus == BpmStatus.done;
}

/// 推荐结果：distance = |原BPM − 目标BPM|，score 为 1..5 星。
class Recommendation {
  final Song song;
  final double distance;
  final int score;

  const Recommendation({
    required this.song,
    required this.distance,
    required this.score,
  });
}
