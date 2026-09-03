/// 用 ffmpeg 把任意受支持音频解码为单声道 22050Hz PCM16 WAV，供本地 BPM 分析。
library;

import 'dart:io';

import 'package:ffmpeg_kit_flutter_audio/ffmpeg_kit.dart';
import 'package:ffmpeg_kit_flutter_audio/ffprobe_kit.dart';
import 'package:ffmpeg_kit_flutter_audio/return_code.dart';

import 'bpm_analyzer.dart';

class AudioDecodeService {
  // ffmpeg-kit 是「单会话/单执行队列」模型：若同一时刻有第二个
  // execute/probe 同时发起，原生会话注册表查不到当前 id，
  // 会抛 PlatformException(SESSION_NOT_FOUND, "Session not found.")。
  // 用全局链把每次 native 调用串行化，彻底避免并发（例如批量导入仍在
  // 分析时又对某首歌点「重新测量」）。
  static Future<T> _serialized<T>(Future<T> Function() action) {
    final next = _chain.then((_) => action());
    // 无论成功失败都继续链，否则后续调用会永久卡死。
    _chain = next.then((_) {}, onError: (_) {});
    return next;
  }

  static Future<void> _chain = Future.value();

  /// 将 [srcPath] 解码为 [outWavPath]（单声道 16-bit 22050Hz）。
  /// 返回 true 表示成功。
  static Future<bool> decodeToWav(String srcPath, String outWavPath) {
    return _serialized(() async {
      final session = await FFmpegKit.execute(
        '-y -hide_banner -loglevel error '
        '-i "$srcPath" '
        '-vn -ac 1 -ar $kSampleRate -sample_fmt s16 '
        '"$outWavPath"',
      );
      final rc = await session.getReturnCode();
      return ReturnCode.isSuccess(rc);
    });
  }

  /// 便捷方法：解码并直接分析，返回 BpmResult。
  static Future<BpmResult> decodeAndAnalyze(
    String srcPath,
    String outWavPath, {
    bool alwaysAnalysis = true,
  }) async {
    final ok = await decodeToWav(srcPath, outWavPath);
    if (!ok) {
      return const BpmResult(bpm: null, confidence: 0.0, error: '无法解码音频文件');
    }
    return BpmAnalyzer.analyzeWavFile(outWavPath);
  }

  /// 获取音频时长（秒），失败返回 null。
  static Future<double?> probeDuration(String srcPath) {
    return _serialized(() async {
      final probe = await FFprobeKit.getMediaInformation(srcPath);
      final info = probe.getMediaInformation();
      if (info == null) return null;
      final d = info.getDuration(); // String?
      return (d == null || d.isEmpty) ? null : double.tryParse(d);
    });
  }

  /// 提取音频内嵌封面（MP3 APIC / M4A covr / FLAC picture）到 [outImagePath]。
  /// 没有内嵌封面、或提取失败时返回 false（此时调用方保持 artwork 为空即可）。
  ///
  /// 使用 `-c:v copy` 直接把内嵌图片原样写为 jpg/png，不重新编码，速度快。
  static Future<bool> extractEmbeddedArtwork(
    String srcPath,
    String outImagePath,
  ) {
    return _serialized(() async {
      final session = await FFmpegKit.execute(
        '-y -hide_banner -loglevel error '
        '-i "$srcPath" '
        '-an -c:v copy '
        '"$outImagePath"',
      );
      final rc = await session.getReturnCode();
      if (!ReturnCode.isSuccess(rc)) return false;
      try {
        return await File(outImagePath).exists();
      } catch (_) {
        return false;
      }
    });
  }
}
