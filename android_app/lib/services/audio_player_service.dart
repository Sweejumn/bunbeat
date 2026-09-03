/// 音频播放服务：基于 just_audio（ExoPlayer）。
///
/// 变速：ExoPlayer 的 `setSpeed` 默认保持音高（time-stretch），
/// 因此把 playbackRate 设为 targetBpm / originalBpm 即可在不变调的前提下
/// 把节奏统一到目标 BPM —— 对应 Web 版的 FFmpeg atempo 时间拉伸。
library;

import 'package:flutter/foundation.dart';
import 'package:just_audio/just_audio.dart';

class AudioPlayerService {
  final AudioPlayer _player = AudioPlayer();

  /// 播放一首（可设置目标播放速率）。
  AudioPlayer get player => _player;

  Future<void> play(PlaylistItem item) async {
    await _player.setUrl(item.filePath);
    await _player.setSpeed(item.speed);
    if (!_player.playing) {
      await _player.play();
    }
  }

  /// 容错版播放：载入失败时不抛异常，返回 false。
  ///
  /// 对应成熟项目（如 koel/player）的「出错跳过」策略：
  /// 单曲载入失败不应卡死整条推荐播放列表，而应让上层跳到下一首。
  Future<bool> tryPlay(PlaylistItem item) async {
    try {
      await _player.setUrl(item.filePath);
    } catch (e) {
      debugPrint('play load failed: $e');
      return false;
    }
    try {
      await _player.setSpeed(item.speed);
      if (!_player.playing) {
        await _player.play();
      }
    } catch (e) {
      debugPrint('play setSpeed/play failed: $e');
      return false;
    }
    return true;
  }

  /// 只装载当前曲（设源 + 变速），但不自动播放。
  /// 用于退出后恢复队列时把歌曲「准备好」，等用户点播放键再出声。
  Future<bool> loadPaused(PlaylistItem item) async {
    try {
      await _player.setUrl(item.filePath);
      await _player.setSpeed(item.speed);
      await _player.pause();
      return true;
    } catch (e) {
      debugPrint('loadPaused failed: $e');
      return false;
    }
  }

  Future<void> setSpeed(double speed) => _player.setSpeed(speed);

  Future<void> toggle() {
    if (_player.playing) {
      return _player.pause();
    } else {
      return _player.play();
    }
  }

  Future<void> seekTo(Duration pos) => _player.seek(pos);

  Future<void> stop() => _player.stop();

  Future<void> dispose() => _player.dispose();
}

/// 播放列表中的一项：本地文件 + 目标播放速率（保持音高的变速）。
class PlaylistItem {
  final String filePath;
  final double originalBpm;
  final double targetBpm;

  /// 变速播放速率 = 目标 BPM / 原始 BPM
  double get speed {
    if (originalBpm <= 0) return 1.0;
    final s = targetBpm / originalBpm;
    return s.clamp(0.5, 2.0);
  }

  const PlaylistItem({
    required this.filePath,
    required this.originalBpm,
    required this.targetBpm,
  });
}
