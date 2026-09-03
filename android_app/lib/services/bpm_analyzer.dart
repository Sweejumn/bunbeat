/// 设备端 BPM / 拍点分析（纯 Dart 实现，移植自后端 librosa 逻辑）。
///
/// 流程（与 Web 版保持一致）：
///   1. 读取单声道 22050 Hz PCM
///   2. STFT → 谱通量起音包络（spectral-flux onset strength）
///   3. 对起音包络自相关 → 粗略周期
///   4. 抛物线插值细化 BPM
///   5. 八度/脉冲修正（2x / 0.5x / 1.5x / 2/3x）
///   6. 用拍间间隔的规整度计算置信度
library;

import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';

import 'fft.dart';

const int kSampleRate = 22050;
const int kHop = 512;
const int kWin = 1024;

class BpmResult {
  final double? bpm;
  final double confidence;
  final double? duration;
  final String? error;
  final double? beatOffset;
  final List<double>? beatTimes;

  /// 各节拍模式的时间轴（秒），键为 BeatMode 名称（grid/light/snap）。
  final Map<String, List<double>>? beatMaps;

  /// 0..1：两个独立相位信号是否一致（低 = 建议手动校准）。
  final double? phaseReliability;

  const BpmResult({
    this.bpm,
    this.confidence = 0.0,
    this.duration,
    this.error,
    this.beatOffset,
    this.beatTimes,
    this.beatMaps,
    this.phaseReliability,
  });
}

class BpmAnalyzer {
  /// 从 ffmpeg 解码得到的单声道 16-bit WAV 文件中读取 PCM 并分析。
  static Future<BpmResult> analyzeWavFile(String wavPath) async {
    try {
      final bytes = await File(wavPath).readAsBytes();
      // 核心工作是 CPU 密集的 FFT 分析与 PCM 解码，放到后台 isolate 执行，
      // 避免阻塞 UI 主线程导致卡顿。
      return await compute(_analyzeWavBytes, bytes);
    } catch (e) {
      return BpmResult(bpm: null, confidence: 0.0, error: '解析音频失败: $e');
    }
  }

  /// 后台 isolate 入口：解码 WAV 字节并执行完整 BPM 分析。
  /// 必须为顶层/静态函数（供 `compute` 跨 isolate 调用）。
  static BpmResult _analyzeWavBytes(Uint8List bytes) {
    final samples = _decodeWavPcm16(bytes);
    if (samples == null || samples.length < kSampleRate * 2) {
      return const BpmResult(
        bpm: null,
        confidence: 0.0,
        error: '音频过短或格式无法解析，无法可靠检测 BPM',
      );
    }
    return analyzePcm(samples, sampleRate: kSampleRate);
  }

  /// 分析一段单声道 PCM（float -1..1 或 int16 范围内的样本）。
  static BpmResult analyzePcm(
    List<double> samples, {
    required int sampleRate,
  }) {
    if (samples.length < sampleRate * 2) {
      return const BpmResult(
        bpm: null,
        confidence: 0.0,
        error: '音频过短，无法可靠检测 BPM',
      );
    }
    // 仅分析前 60 秒（加快速度）；但拍子网格会按整首歌时长铺满，见下面。
    final maxLen = sampleRate * 60;
    final data = samples.length > maxLen ? samples.sublist(0, maxLen) : samples;
    // 整首歌的时长（秒）：相位/BPM 从前 60s 分析窗口推定，
    // 但 grid/light/snap 拍子时间轴要铺满整首歌，标尺后段才有绿线。
    final fullDuration = samples.length / sampleRate;

    try {
      final onset = _onsetStrengthFlux(data, sampleRate);
      final ac = _autocorrelate(onset);

      // 粗略 BPM：在 40–400 范围内取自相关最强周期的候选
      final bpmCandidates = <double>[-1.0, -1.0];
      final strength = <double>[-1.0, -1.0];
      for (double bpm = 40; bpm <= 400; bpm += 1.0) {
        final s = _strengthAtLag(ac, bpm);
        if (s > strength[0]) {
          strength[1] = strength[0];
          bpmCandidates[1] = bpmCandidates[0];
          strength[0] = s;
          bpmCandidates[0] = bpm;
        } else if (s > strength[1]) {
          strength[1] = s;
          bpmCandidates[1] = bpm;
        }
      }
      // 优先选择更强的峰；若第二峰明显更强则用第二峰（避免局部抖动）
      final coarse = strength[0] >= strength[1] * 0.98 ? bpmCandidates[0] : bpmCandidates[1];
      if (coarse <= 0) {
        return const BpmResult(bpm: null, confidence: 0.0, error: '无法可靠检测 BPM');
      }

      final refined = _refineTempo(ac, coarse);
      final (bpm, _) = _octaveCorrect(ac, refined);

      // 置信度：起音包络相邻强拍间隔的规整度
      final confidence = _confidence(onset);
      // 默认固定拍子网格 + 三种节拍模式 + 相位可靠性
      final beatMaps = _buildBeatMaps(onset, bpm, total: fullDuration);
      final grid = beatMaps['grid'] ?? <double>[];
      final reliability = _phaseReliability(onset, bpm, grid.isNotEmpty ? grid.first : 0.0);

      return BpmResult(
        bpm: bpm,
        confidence: confidence,
        duration: fullDuration,
        beatOffset: grid.isNotEmpty ? grid.first : null,
        beatTimes: grid,
        beatMaps: beatMaps,
        phaseReliability: reliability,
      );
    } catch (e) {
      return BpmResult(bpm: null, confidence: 0.0, error: '节拍检测失败: $e');
    }
  }

  // ---------- 起音强度包络（谱通量） ----------
  static List<double> _onsetStrengthFlux(List<double> data, int sr) {
    final nFrames = (data.length - kWin) ~/ kHop;
    if (nFrames < 2) return <double>[];

    // 汉宁窗
    final window = List<double>.generate(kWin, (i) {
      return 0.5 * (1 - math.cos(2 * math.pi * i / (kWin - 1)));
    });

    final flux = <double>[];
    List<double> prevMag = List<double>.filled(kWin ~/ 2, 0.0);
    final frame = List<double>.filled(kWin, 0.0);
    for (int f = 0; f < nFrames; f++) {
      final start = f * kHop;
      for (int i = 0; i < kWin; i++) {
        frame[i] = data[start + i] * window[i];
      }
      final spec = fft(frame);
      final half = kWin ~/ 2;
      double s = 0.0;
      for (int b = 0; b < half; b++) {
        final mag = spec[b].magnitude;
        final d = mag - prevMag[b];
        if (d > 0) s += d;
        prevMag[b] = mag;
      }
      flux.add(s);
    }
    return flux;
  }

  static List<double> _autocorrelate(List<double> x) {
    final n = x.length;
    final mean = x.reduce((a, b) => a + b) / n;
    final centered = x.map((v) => v - mean).toList();
    // 用 FFT 实现自相关：IFFT(|FFT(x)|^2)
    final len = nextPow2(n * 2);
    final buf = List<double>.filled(len, 0.0);
    for (var i = 0; i < n; i++) {
      buf[i] = centered[i];
    }
    final spec = fft(buf);
    final n2 = spec.length;
    for (var i = 0; i < n2; i++) {
      final m = spec[i].magnitude;
      spec[i] = Complex(m * m, 0.0);
    }
    ifft(spec);
    return List<double>.generate(n, (i) => spec[i].re);
  }

  static double _strengthAtLag(List<double> ac, double bpm) {
    if (bpm <= 0) return 0.0;
    // 将该 BPM 换算到 22050Hz/512hop 帧网格的滞后量
    double lag = (60.0 / bpm) * kSampleRate / kHop;
    if (lag < 2 || lag >= ac.length - 2) return 0.0;
    final lo = math.max(1, (lag * 0.9).floor());
    final hi = math.min(ac.length - 1, (lag * 1.1).ceil());
    if (hi <= lo) return 0.0;
    double best = -1e18;
    for (int i = lo; i <= hi && i < ac.length; i++) {
      if (ac[i] > best) best = ac[i];
    }
    return best;
  }

  static double _refineTempo(List<double> ac, double baseBpm) {
    double lag0 = (60.0 / baseBpm) * kSampleRate / kHop;
    final lo = math.max(1, (lag0 * 0.85).floor());
    final hi = math.min(ac.length - 1, (lag0 * 1.15).ceil());
    if (hi <= lo) return baseBpm;
    int bestIdx = lo;
    double bestVal = -1e18;
    for (int i = lo; i <= hi; i++) {
      if (ac[i] > bestVal) {
        bestVal = ac[i];
        bestIdx = i;
      }
    }
    // 抛物线插值
    double idx = bestIdx.toDouble();
    if (bestIdx > 0 && bestIdx < ac.length - 1) {
      final y0 = ac[bestIdx - 1], y1 = ac[bestIdx], y2 = ac[bestIdx + 1];
      final denom = y0 - 2 * y1 + y2;
      if (denom.abs() > 1e-12) {
        final delta = 0.5 * (y0 - y2) / denom;
        idx += delta.clamp(-1.0, 1.0);
      }
    }
    return 60.0 / (math.max(idx, 1e-6) * kHop / kSampleRate);
  }

  static (double, bool) _octaveCorrect(List<double> ac, double tempo) {
    var best = tempo;
    var corrected = false;
    final sCur = _strengthAtLag(ac, tempo);
    final sDouble = _strengthAtLag(ac, tempo * 2.0);
    final s15 = _strengthAtLag(ac, tempo * 1.5);
    final sHalf = _strengthAtLag(ac, tempo / 2.0);
    final s23 = _strengthAtLag(ac, tempo / 1.5);

    if (tempo < 100 && 100 <= tempo * 2.0 && tempo * 2.0 <= 210 && sDouble > sCur * 0.8) {
      best = tempo * 2.0;
      corrected = true;
    } else if (tempo * 1.5 >= 100 && tempo * 1.5 <= 210 && s15 > sCur * 0.8 && tempo * 1.5 > tempo) {
      best = tempo * 1.5;
      corrected = true;
    } else if (s23 > sCur * 1.3 && tempo / 1.5 >= 50) {
      best = tempo / 1.5;
      corrected = true;
    } else if (sHalf > sCur * 1.3 && tempo / 2.0 >= 50) {
      best = tempo / 2.0;
      corrected = true;
    }

    while (best < 60 && best * 2 <= 300) {
      best *= 2.0;
      corrected = true;
    }
    while (best > 200) {
      best /= 2.0;
      corrected = true;
    }
    // 保留抛物线插值得到的真实精度（不再强制 1 位小数），
    // 使两位小数显示有意义；仅去除浮点尾部噪声到 6 位。
    return (double.parse(best.toStringAsFixed(6)), corrected);
  }

  static double _confidence(List<double> onset) {
    if (onset.length < 4) return 0.0;
    // 对起音包络取局部峰，得到“拍位”序列，衡量间隔规整度
    final peaks = <int>[];
    for (int i = 1; i < onset.length - 1; i++) {
      if (onset[i] > onset[i - 1] && onset[i] >= onset[i + 1]) {
        peaks.add(i);
      }
    }
    if (peaks.length < 4) return 0.0;
    final intervals = <double>[];
    for (int i = 1; i < peaks.length; i++) {
      intervals.add((peaks[i] - peaks[i - 1]).toDouble());
    }
    final mean = intervals.reduce((a, b) => a + b) / intervals.length;
    if (mean <= 0) return 0.0;
    final variance = intervals
        .map((iv) => (iv - mean) * (iv - mean))
        .reduce((a, b) => a + b) /
        intervals.length;
    final cv = math.sqrt(variance) / mean;
    return (1.0 - cv * 3.0).clamp(0.0, 1.0);
  }

  /// 以目标 BPM 生成固定等距拍子网格（秒），并推定相位。
  /// 相位从 [onset]（通常仅为歌曲前 60s 的分析窗口）推定，但网格会一直铺满到
  /// [total]（整首歌时长）——这样拍点标尺在歌曲后段也有连续的拍子，
  /// 而不是只在开头一段有绿线。
  static List<double> _buildGrid(List<double> onset, double bpm, {required double total}) {
    if (bpm <= 0) return <double>[];
    final period = 60.0 / bpm;
    // 用起音能量定位相位：取每个周期内能量最强的位置
    final phase = _findPhase(onset, period);
    final times = <double>[];
    for (double t = phase; t < total; t += period) {
      times.add(double.parse(t.toStringAsFixed(3)));
    }
    return times;
  }

  /// 在 [0, period) 内找一个相位，使得 period*k + phase 处起音能量总和最大。
  static double _findPhase(List<double> onset, double period) {
    final total = onset.length * kHop / kSampleRate;
    final nk = (total / period).ceil() + 1;
    double bestPhi = 0, bestE = -1e18;
    const steps = 40.0;
    for (int i = 0; i < steps; i++) {
      final phi = (i * period) / steps;
      double e = 0;
      for (int k = 0; k < nk; k++) {
        final sec = phi + k * period;
        final idx = (sec * kSampleRate / kHop).round();
        if (idx >= 0 && idx < onset.length) e += onset[idx];
      }
      if (e > bestE) {
        bestE = e;
        bestPhi = phi;
      }
    }
    return bestPhi;
  }

  /// 生成节拍模式的时间轴（原始时间轴秒）：
  ///   grid（固定拍子，完全等距，铺满整首歌）/
  ///   snap（跟随起音，±12% 内吸附打击点；超出窗口部分回退等距）。
  /// 键与 [BeatMode] 名称对应，保证始终含 grid。
  static Map<String, List<double>> _buildBeatMaps(
    List<double> onset,
    double bpm, {
    required double total,
  }) {
    if (bpm <= 0) return <String, List<double>>{};
    final period = 60.0 / bpm;
    final grid = _buildGrid(onset, bpm, total: total);
    if (grid.isEmpty) return <String, List<double>>{'grid': grid};

    // 以 grid 为基础，向局部起音峰吸附（在原周期内，snap 不越界）；
    // grid 已铺满整首歌，窗口外无起音峰时会自然回退到等距位置。
    final snap = _followOnsets(onset, grid, period, 0.12);
    return <String, List<double>>{
      'grid': grid,
      'snap': snap,
    };
  }

  /// 把 grid 里每个拍的时间，在 ±[fraction]*period 窗口内吸附到最近的
  /// 局部起音峰（若窗口内存在峰），否则保持原等距位置。
  static List<double> _followOnsets(
    List<double> onset,
    List<double> grid,
    double period,
    double fraction,
  ) {
    final win = period * fraction; // 秒
    final result = <double>[];
    final frameSec = kHop / kSampleRate;
    for (final t in grid) {
      final lo = (t - win).clamp(0.0, double.infinity);
      final hi = t + win;
      final loIdx = math.max(0, (lo / frameSec).floor());
      final hiIdx = math.min(onset.length - 1, (hi / frameSec).ceil());
      // 在窗口内找局部峰（能量最大的位置）
      var bestIdx = -1;
      var bestE = -1e18;
      for (int i = loIdx; i <= hiIdx; i++) {
        final isPeak = i > 0 && i < onset.length - 1 &&
            onset[i] >= onset[i - 1] && onset[i] > onset[i + 1];
        if (isPeak && onset[i] > bestE) {
          bestE = onset[i];
          bestIdx = i;
        }
      }
      if (bestIdx >= 0) {
        result.add(double.parse((bestIdx * frameSec).toStringAsFixed(3)));
      } else {
        result.add(t);
      }
    }
    return result;
  }

  /// 相位可靠性（0..1）：用「能量和最大化」与「强峰相位中位」两个独立
  /// 信号交叉验证。两者在周期上的差越大（最多 1/4 周期 → 0），可靠性越低。
  static double _phaseReliability(List<double> onset, double bpm, double phaseA) {
    if (bpm <= 0 || onset.length < 8) return 0.5;
    final period = 60.0 / bpm;
    final frameSec = kHop / kSampleRate;
    // 方法 B：从强起音峰（高于均值）取相位模周期后的中位数。
    final mean = onset.reduce((a, b) => a + b) / onset.length;
    final thr = mean * 0.6;
    final offs = <double>[];
    for (int i = 2; i < onset.length - 2; i++) {
      if (onset[i] >= onset[i - 1] && onset[i] > onset[i + 1] && onset[i] > thr) {
        final t = i * frameSec;
        offs.add(t % period);
      }
    }
    if (offs.length < 4) return 0.5;
    offs.sort();
    final med = offs[offs.length ~/ 2];
    // 圆上距离：phaseA 与 med 的最小弧长
    var d = (phaseA % period - med).abs();
    d = math.min(d, period - d);
    final frac = d / period;
    return (1.0 - frac / 0.25).clamp(0.0, 1.0);
  }

  // ---------- WAV 解码 ----------
  /// 仅支持 ffmpeg 生成的 PCM16 单声道 WAV。返回 float(-1..1) 样本。
  static List<double>? _decodeWavPcm16(Uint8List bytes) {
    // 找 'data' 块
    int pos = 12;
    while (pos + 8 <= bytes.length) {
      final tag = String.fromCharCodes(bytes.sublist(pos, pos + 4));
      final size = bytes.buffer.asByteData().getUint32(pos + 4, Endian.little);
      if (tag == 'data') {
        final dataStart = pos + 8;
        final n = size;
        // 16-bit: 数据可能是奇数长度，但 PCM16 通常偶长
        final sampleCount = n ~/ 2;
        final out = List<double>.generate(sampleCount, (i) {
          final raw = bytes.buffer.asByteData().getInt16(dataStart + i * 2, Endian.little);
          return raw / 32768.0;
        });
        return out;
      }
      pos += 8 + size + (size.isOdd ? 1 : 0);
    }
    return null;
  }
}
