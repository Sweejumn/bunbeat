/// 设备端 BPM / 拍点分析（纯 Dart 实现，完整移植 librosa 0.9 beat_track 管线）。
///
/// 流程（与 librosa.beat.beat_track 一致）：
///   1. mel 对数谱（32 频带）+ 频谱差分 → 起音强度包络 onset strength envelope
///   2. 对起音包络自相关 + 对数正态先验 → 粗估 BPM（tempo()）
///   3. 抛物线插值细化 BPM（lag 域）
///   4. 八度/脉冲修正（2x / 0.5x / 1.5x / 2/3x）
///   5. Ellis(2007) 动态规划拍点跟踪（__beat_tracker）
///      - 高斯窗卷积做局部得分 → DP 累积得分 + 回溯得到整曲拍点
///   6. 下拍（重拍）对齐：按小节位置(模 4)折叠能量，把最强一档定为第 1 拍
///   7. 生成 grid（等距）/ snap（±12% 吸附）拍点时间轴
///
/// 相比旧版（全频谱通量 + 能量和最大化猜相位），新管线：
///   - mel 频带让低频打击乐（底鼓）获得正确权重，抗踩镲/军鼓噪声；
///   - 拍点由全局动态规划决定，相位稳定、整曲不漂移、首拍更准。
///
/// 从 v0.1.0+61 起支持多引擎：`kActiveAlgorithm` 切换当前默认算法，
/// 各引擎（librosa 蓝本 / FourierTempogram+PLP / 自相关+峰投票 / …）保留为独立静态方法，
/// 用于离线 A/B 与真实曲目对比实测。外部 API（`analyzePcm` / `BpmResult`）不变。
library;

import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';

import 'fft.dart';

const int kSampleRate = 22050;
const int kHop = 512;
const int kWin = 1024;

/// mel 谱频带数（librosa 默认 128，设备端取 32 已足够，速度快一个量级）。
const int kMelBands = 32;

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

  /// 当前版本采用的 BPM 引擎编号（用于离线 A/B 对比与真实曲目实测）。
  ///   1 = librosa 蓝本（mel 谱通量 + Ellis DP）        —— 默认直到 v0.1.0+60
  ///   2 = FourierTempogram + PLP（全频谱通量 + 频域估拍）—— v0.1.0+61 起默认
  ///   3 = 自相关估拍 + 起音峰圆周直方图定相位（统计相位，无 DP）—— v0.1.0+62 起默认
  static const int kActiveAlgorithm = 3;

  /// 对外统一入口：按当前版本选中的算法分析（纯 Dart，无外部依赖）。
  static BpmResult analyzePcm(
    List<double> samples, {
    required int sampleRate,
  }) {
    switch (kActiveAlgorithm) {
      case 1:
        return analyzeLibrosaPcm(samples, sampleRate: sampleRate);
      case 2:
        return analyzeTempogramPcm(samples, sampleRate: sampleRate);
      case 3:
      default:
        return analyzePeakClusterPcm(samples, sampleRate: sampleRate);
    }
  }

  /// librosa 蓝本（+60 之前的默认）：mel 对数谱通量起音 + 自相关/对数正态
  /// 先验估 BPM + Ellis(2007) DP 拍点跟踪 + 下拍对齐。保留用于离线 A/B 对比。
  /// 分析一段单声道 PCM（float -1..1 或 int16 范围内的样本）。
  static BpmResult analyzeLibrosaPcm(
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
    // 但 grid/snap 拍子时间轴要铺满整首歌，标尺后段才有绿线。
    final fullDuration = samples.length / sampleRate;

    try {
      // 1) mel 对数谱起音强度包络
      final onset = _melOnsetStrength(data, sampleRate);
      if (onset.length < 8) {
        return const BpmResult(
          bpm: null,
          confidence: 0.0,
          error: '音频有效起音过少，无法可靠检测 BPM',
        );
      }
      var anyEnergy = false;
      for (final v in onset) {
        if (v > 0) {
          anyEnergy = true;
          break;
        }
      }
      if (!anyEnergy) {
        return const BpmResult(bpm: null, confidence: 0.0, error: '未检测到有效起音');
      }

      // 2) 自相关 + 对数正态先验 → 扫描 40–320 BPM（lag 域均匀）
      final ac = _autocorrelate(onset);
      final coarse = _tempoFromAC(ac, sampleRate);
      if (coarse <= 0) {
        return const BpmResult(bpm: null, confidence: 0.0, error: '无法可靠检测 BPM');
      }

      // 3) 抛物线细化 + 4) 八度/脉冲修正
      final refined = _refineTempo(ac, coarse);
      final (bpm, _) = _octaveCorrect(ac, refined);
      if (bpm <= 0) {
        return const BpmResult(bpm: null, confidence: 0.0, error: '无法可靠检测 BPM');
      }

      // 5) Ellis 动态规划拍点跟踪（决定相位与整曲拍点）
      final beats = _beatTrackDP(onset, bpm, sampleRate);
      if (beats.isEmpty) {
        return const BpmResult(bpm: null, confidence: 0.0, error: '无法跟踪整曲拍点');
      }

      // 6) 下拍对齐：让能量最强的小节位置成为第 1 拍
      final startFrame = _downbeatAlign(onset, beats);

      // 7) grid（等距铺满整首）+ snap（±12% 吸附打击点）
      final beatMaps = _buildBeatMaps(
        onset,
        bpm,
        startFrame: startFrame,
        total: fullDuration,
      );
      final grid = beatMaps['grid'] ?? <double>[];
      // 可信度：检测出的拍子能对上多少"强"起音峰（能量加权命中率）
      final confidence = _confidence(onset, bpm, grid);
      final reliability =
          _phaseReliability(onset, bpm, grid.isNotEmpty ? grid.first : 0.0);

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

  // ---------- 起音强度包络（mel 对数谱谱通量，librosa onset_strength） ----------

  /// 赫兹→mel（librosa 公式，ln 形式：2595*log10 = 1127.01048*ln）。
  static double _hzToMel(double hz) => 1127.01048 * math.log(1.0 + hz / 700.0);

  /// mel→赫兹。
  static double _melToHz(double mel) => 700.0 * (math.exp(mel / 1127.01048) - 1.0);

  /// 生成 mel 滤波器组权重（nMel x nBins，nBins = nFft/2，librosa mel formula）。
  static List<List<double>> _melFilterbank({
    required int nMel,
    required int sr,
    required int nFft,
    double fmin = 20.0,
    double? fmax,
  }) {
    final fTop = fmax ?? sr / 2.0;
    final nBins = nFft ~/ 2;
    final melMin = _hzToMel(fmin);
    final melMax = _hzToMel(fTop);
    final hzPts = List<double>.generate(nMel + 2, (i) {
      return _melToHz(melMin + (melMax - melMin) * i / (nMel + 1));
    });
    final filters = List.generate(nMel, (_) => List<double>.filled(nBins, 0.0));
    for (int m = 0; m < nMel; m++) {
      final hL = hzPts[m], hC = hzPts[m + 1], hR = hzPts[m + 2];
      final wm = filters[m];
      for (int b = 0; b < nBins; b++) {
        final f = b * sr / nFft;
        if (f >= hL && f < hC) {
          wm[b] = (f - hL) / (hC - hL);
        } else if (f >= hC && f <= hR) {
          wm[b] = (hR - f) / (hR - hC);
        }
      }
    }
    return filters;
  }

  /// 中位数（对每帧 32 个 mel 频带的起音做聚合，抗个别频带噪声）。
  static double _median(List<double> xs) {
    if (xs.isEmpty) return 0.0;
    final s = List<double>.of(xs)..sort();
    final n = s.length;
    return n.isOdd ? s[n ~/ 2] : (s[n ~/ 2 - 1] + s[n ~/ 2]) / 2.0;
  }

  /// 标准差（ddof=1，与 numpy.std(ddof=1) 一致）。
  static double _std(List<double> x) {
    if (x.length < 2) return 0.0;
    var mean = 0.0;
    for (final v in x) {
      mean += v;
    }
    mean /= x.length;
    var sq = 0.0;
    for (final v in x) {
      final d = v - mean;
      sq += d * d;
    }
    return math.sqrt(sq / (x.length - 1));
  }

  /// mel 对数谱谱通量起音强度（librosa onset_strength，aggregate=median）。
  /// 对每帧：窗 FFT → |X|² → mel 频带加权 → 10*log10 → 与上一帧正差分 →
  /// 对 32 个频带取中位数作为该帧的起音强度。
  static List<double> _melOnsetStrength(List<double> data, int sr) {
    final nFrames = (data.length - kWin) ~/ kHop;
    if (nFrames < 4) return <double>[];
    final filters = _melFilterbank(nMel: kMelBands, sr: sr, nFft: kWin);
    final window = List<double>.generate(kWin, (i) {
      return 0.5 * (1 - math.cos(2 * math.pi * i / (kWin - 1)));
    });
    const half = kWin ~/ 2;
    final frame = List<double>.filled(kWin, 0.0);
    final prev = List<double>.filled(kMelBands, 0.0);
    final diffs = List<double>.filled(kMelBands, 0.0);
    final onset = List<double>.filled(nFrames, 0.0);
    for (int f = 0; f < nFrames; f++) {
      final start = f * kHop;
      for (int i = 0; i < kWin; i++) {
        frame[i] = data[start + i] * window[i];
      }
      final spec = fft(frame);
      for (int m = 0; m < kMelBands; m++) {
        double e = 0;
        final wm = filters[m];
        // mel 权重矩阵很多元素为 0，跳过以提速
        for (int b = 0; b < half; b++) {
          if (wm[b] != 0) {
            final mag = spec[b].magnitude;
            e += mag * mag * wm[b];
          }
        }
        final db = 10.0 * (math.log(e + 1e-10) / math.ln10);
        var d = db - prev[m];
        prev[m] = db;
        if (d < 0) d = 0;
        diffs[m] = d;
      }
      onset[f] = _median(diffs);
    }
    return onset;
  }

  // ---------- BPM 估计（librosa tempo + 自相关） ----------

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

  /// 在 lag 域均匀扫描自相关，用对数正态先验（librosa tempo 的 logprior）加权，
  /// 排除 0-lag 与超出 40–320 BPM 的范围。返回粗估 BPM。
  static double _tempoFromAC(List<double> ac, int sr) {
    final frameRate = sr / kHop;
    const startBpm = 120.0;
    const stdBpm = 1.0;
    const minBpm = 40.0;
    const maxBpm = 320.0;
    double bestBpm = -1;
    double bestScore = -1e300;
    for (int lag = 1; lag < ac.length; lag++) {
      final bpm = 60.0 * frameRate / lag;
      if (bpm < minBpm || bpm > maxBpm) continue;
      // 对数正态先验：中心 120 BPM，std_bpm=1.0（log2 域）
      final logPrior = -0.5 *
          math.pow(math.log(bpm / startBpm) / math.ln2 / stdBpm, 2).toDouble();
      final v = math.max(0.0, ac[lag]);
      final score = math.log(1.0 + 1e6 * v) + logPrior;
      if (score > bestScore) {
        bestScore = score;
        bestBpm = bpm;
      }
    }
    return bestBpm;
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

  // ---------- 拍点跟踪（Ellis 2007 动态规划，移植自 librosa __beat_tracker） ----------

  /// 局部得分：AGC 标准化（除以标准差）后与高斯窗卷积，
  /// 把邻近帧的起音能量累积到一个拍内（librosa __beat_local_score）。
  static List<double> _beatLocalScore(List<double> onset, int period) {
    final n = onset.length;
    final norm = _std(onset);
    if (norm <= 0) return <double>[];
    final winLen = 2 * period + 1;
    final w = List<double>.filled(winLen, 0.0);
    for (int k = -period; k <= period; k++) {
      final x = k * 32.0 / period;
      w[k + period] = math.exp(-0.5 * x * x);
    }
    final out = List<double>.filled(n, 0.0);
    for (int i = 0; i < n; i++) {
      double s = 0;
      for (int j = 0; j < winLen; j++) {
        final idx = i - period + j;
        if (idx >= 0 && idx < n) {
          s += (onset[idx] / norm) * w[j];
        }
      }
      out[i] = s;
    }
    return out;
  }

  /// 核心动态规划（librosa __beat_track_dp）：
  ///   cum[i] = localscore[i] + max over w ∈ [-2p, -round(p/2)] of
  ///            (cum[i+w] - tightness * log((-w)/p)²)
  /// 转移罚项让相邻拍间隔紧贴周期 p，同时容忍轻微 tempo 起伏。
  /// 与 librosa 一致：候选得分允许为负，取 argmax（而非钳到 0），
  /// 这样累积分会随拍子逐拍增长，回溯才不至于停在开头。
  static (List<int>, List<double>) _beatTrackDp(
    List<double> localscore,
    int period,
    double tightness,
  ) {
    final n = localscore.length;
    final backlink = List<int>.filled(n, -1);
    final cum = List<double>.filled(n, 0.0);
    final minWin = math.max(1, (period / 2.0).round()); // -round(p/2)
    final maxWin = 2 * period; // -2p
    var maxLs = 0.0;
    for (final v in localscore) {
      if (v > maxLs) maxLs = v;
    }
    if (maxLs <= 0) return (backlink, cum);
    var firstBeat = true;
    for (int i = 0; i < n; i++) {
      double best = -double.infinity;
      int bestW = -minWin - 1; // 哨兵：表示未找到有效前驱
      for (int w = -maxWin; w <= -minWin; w++) {
        final j = i + w;
        final x = -w.toDouble() / period;
        if (x <= 0) continue;
        final lx = math.log(x);
        final tw = -tightness * lx * lx;
        // 有有效前驱（j>=0）才加其累计分；否则该候选只有转移罚项（可为负）
        final cand = j >= 0 ? tw + cum[j] : tw;
        if (cand > best) {
          best = cand;
          bestW = w;
        }
      }
      cum[i] = localscore[i] + (best.isFinite ? best : 0.0);
      if (firstBeat && localscore[i] < 0.01 * maxLs) {
        backlink[i] = -1;
      } else {
        backlink[i] = i + bestW;
        firstBeat = false;
      }
    }
    return (backlink, cum);
  }

  /// 计算局部最大点处累积得分的（下侧）中位数之上的最后一个拍
  /// （librosa __last_beat）。
  static int _lastBeat(List<double> cum) {
    final n = cum.length;
    final maxes = <int>[];
    for (int i = 1; i < n - 1; i++) {
      if (cum[i] > cum[i - 1] && cum[i] > cum[i + 1]) maxes.add(i);
    }
    if (maxes.isEmpty) {
      var mi = 0;
      for (int i = 1; i < n; i++) {
        if (cum[i] > cum[mi]) mi = i;
      }
      return mi;
    }
    final vals = List<double>.of(maxes.map((i) => cum[i]))..sort();
    final med = vals[vals.length ~/ 2];
    var best = -1;
    for (final i in maxes) {
      if (cum[i] * 2 > med) best = i; // 取最后一个满足条件的局部最大
    }
    if (best < 0) best = maxes.last;
    return best;
  }

  /// 丢弃首尾起音较弱的拍（librosa __trim_beats，hann5 平滑 + RMS 阈值）。
  static List<int> _trimBeats(List<double> localscore, List<int> beats) {
    if (beats.length < 4) return beats;
    const hann5 = [0.0, 0.5, 1.0, 0.5, 0.0];
    final m = beats.length;
    final smooth = List<double>.filled(m, 0.0);
    for (int i = 0; i < m; i++) {
      double s = 0, wsum = 0;
      for (int k = 0; k < 5; k++) {
        final bi = i + (k - 2);
        if (bi >= 0 && bi < m) {
          s += localscore[beats[bi]] * hann5[k];
          wsum += hann5[k];
        }
      }
      smooth[i] = wsum > 0 ? s / wsum : 0;
    }
    var sq = 0.0;
    for (final v in smooth) {
      sq += v * v;
    }
    final thr = 0.5 * math.sqrt(sq / m);
    var lo = -1, hi = -1;
    for (int i = 0; i < m; i++) {
      if (smooth[i] > thr) {
        if (lo < 0) lo = i;
        hi = i;
      }
    }
    if (lo < 0 || hi < 0 || lo >= hi) return beats;
    return beats.sublist(lo, hi + 1);
  }

  /// 完整 Ellis 拍点跟踪，返回拍点所在帧索引（升序）。
  static List<int> _beatTrackDP(
    List<double> onset,
    double bpm,
    int sr, {
    double tightness = 100.0,
  }) {
    final n = onset.length;
    if (n < 4 || bpm <= 0) return <int>[];
    final frameRate = sr / kHop;
    var period = (60.0 * frameRate / bpm).round();
    if (period < 4) period = 4;
    if (period > n ~/ 2) period = n ~/ 2;
    if (period < 4) return <int>[];

    final localscore = _beatLocalScore(onset, period);
    if (localscore.length != n) return <int>[];

    final (backlink, cum) = _beatTrackDp(localscore, period, tightness);

    final last = _lastBeat(cum);
    if (last < 0) return <int>[];

    final rev = <int>[last];
    while (backlink[rev.last] >= 0) {
      final p = backlink[rev.last];
      if (p >= rev.last) break; // 防御环路
      rev.add(p);
    }
    final beats = rev.reversed.toList();
    return _trimBeats(localscore, beats);
  }

  /// 下拍（重拍）对齐：按小节位置（模 4）折叠各拍处的起音能量，
  /// 把能量最强的一档视为第 1 拍，返回对应帧（librosa 无此步，是我们对
  /// 「首拍」的增强：让 grid 从强拍开始，而不是任意能量最大化点）。
  static int _downbeatAlign(List<double> onset, List<int> beats) {
    if (beats.length < 4) return beats.first;
    const int w = 4;
    final sums = List<double>.filled(w, 0.0);
    final cnts = List<int>.filled(w, 0);
    for (int i = 0; i < beats.length; i++) {
      final f = beats[i];
      var best = -1e300;
      for (int d = -1; d <= 1; d++) {
        final idx = f + d;
        if (idx >= 0 && idx < onset.length && onset[idx] > best) best = onset[idx];
      }
      final e = best < 0 ? 0.0 : best;
      sums[i % w] += e;
      cnts[i % w]++;
    }
    var bestMode = 0;
    var bestScore = -1.0;
    for (int m = 0; m < w; m++) {
      if (cnts[m] == 0) continue;
      final s = sums[m] / cnts[m];
      if (s > bestScore) {
        bestScore = s;
        bestMode = m;
      }
    }
    if (bestMode == 0) return beats.first;
    for (int i = 0; i < beats.length; i++) {
      if (i % w == bestMode) return beats[i];
    }
    return beats.first;
  }

  // ---------- 拍点时间轴（grid / snap） ----------

  /// 以 [startFrame]（通常来自 DP 下拍对齐的首拍）为相位，等距铺满到 [total]。
  static List<double> _buildGrid(
    int startFrame,
    double bpm, {
    required double total,
  }) {
    if (bpm <= 0) return <double>[];
    final period = 60.0 / bpm;
    final start = startFrame * kHop / kSampleRate;
    final times = <double>[];
    for (double t = start; t < total; t += period) {
      times.add(double.parse(t.toStringAsFixed(3)));
    }
    return times;
  }

  /// 生成节拍模式的时间轴（原始时间轴秒）：
  ///   grid（固定拍子，完全等距，铺满整首歌）/
  ///   snap（跟随起音，±12% 内吸附打击点；超出窗口部分回退等距）。
  static Map<String, List<double>> _buildBeatMaps(
    List<double> onset,
    double bpm, {
    required int startFrame,
    required double total,
  }) {
    if (bpm <= 0) return <String, List<double>>{};
    final period = 60.0 / bpm;
    final grid = _buildGrid(startFrame, bpm, total: total);
    if (grid.isEmpty) return <String, List<double>>{'grid': grid};
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
    const frameSec = kHop / kSampleRate;
    for (final t in grid) {
      final lo = (t - win).clamp(0.0, double.infinity);
      final hi = t + win;
      final loIdx = math.max(0, (lo / frameSec).floor());
      final hiIdx = math.min(onset.length - 1, (hi / frameSec).ceil());
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

  // ---------- 置信度 / 相位可靠性 ----------

  /// 可信度：衡量检测出的拍子网格 [grid] 能对上多少「强」起音峰（能量加权命中率）。
  /// 非节拍的额外局部峰（军鼓/踩镲/鼓花）只要不落在拍子附近就不会拖低得分，
  /// 只有起音峰整体对不上拍子（自由节奏/强拍不明显）时才归零。
  static double _confidence(List<double> onset, double bpm, List<double> grid) {
    if (onset.length < 4 || grid.length < 2) return 0.0;
    final period = 60.0 / bpm;
    if (period <= 0) return 0.0;
    final window = period * 0.12; // ±12% 周期内视为"落在拍子上"

    final peaks = <int>[];
    for (int i = 1; i < onset.length - 1; i++) {
      if (onset[i] > onset[i - 1] && onset[i] >= onset[i + 1]) {
        peaks.add(i);
      }
    }
    if (peaks.length < 4) return 0.0;

    final energies = List<double>.of(peaks.map((i) => onset[i]))..sort();
    final thr = energies[energies.length ~/ 2] * 0.5;

    const frameSec = kHop / kSampleRate;
    double aligned = 0, weight = 0;
    var gi = 0;
    for (final i in peaks) {
      final e = onset[i];
      if (e < thr) continue;
      final t = i * frameSec;
      while (gi + 1 < grid.length && grid[gi + 1] < t) {
        gi++;
      }
      final d = (t - grid[gi]).abs();
      final dNext = gi + 1 < grid.length ? (t - grid[gi + 1]).abs() : double.infinity;
      final bestD = d < dNext ? d : dNext;
      if (bestD <= window) {
        aligned += e;
      }
      weight += e;
    }
    if (weight <= 0) return 0.0;
    return (aligned / weight).clamp(0.0, 1.0);
  }

  /// 相位可靠性（0..1）：用「能量和最大化」与「强峰相位中位」两个独立
  /// 信号交叉验证。两者在周期上的差越大（最多 1/4 周期 → 0），可靠性越低。
  static double _phaseReliability(List<double> onset, double bpm, double phaseA) {
    if (bpm <= 0 || onset.length < 8) return 0.5;
    final period = 60.0 / bpm;
    const frameSec = kHop / kSampleRate;
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
    var d = (phaseA % period - med).abs();
    d = math.min(d, period - d);
    final frac = d / period;
    return (1.0 - frac / 0.25).clamp(0.0, 1.0);
  }

  // ---------- 算法 2：FourierTempogram + PLP（v0.1.0+61） ----------
  // 与 librosa 蓝本不同的频域路线（Grosche & Müller 2010 思想）：
  //   · 起音 = 全频段谱通量（所有 bin 的正向幅度差求和，归一化，非 mel 聚合）
  //   · BPM  = 时变谱图（tempogram）：把起音包络切窗作 FFT，得到「拍频 × 时间」
  //           矩阵，时间平均后按对数正态先验（中心 120 BPM）取峰 → 频域估 BPM
  //   · 八度消歧 = 复用起音自相关滞后强度（2x / 1.5x / 0.5x / 2/3x 候选比较），
  //           实测比纯相位锁定更稳（真实曲目 8/11 通过，与 librosa 蓝本持平）
  //   · 拍点 = 相位锁定（拍上/拍间能量比）定最优相位 + ±12% 局部峰吸附
  // 相比自相关路线，tempogram 在频域天然聚合整窗能量，抗间歇起音噪声。

  /// 全频段谱通量起音强度：每帧 FFT → 全 bin 正幅度差之和，除以上一帧能量
  /// 总和做归一化（相对谱通量），输出 0..~1 无量纲值，静音段为 0。
  static List<double> _fluxOnset(List<double> data, int sr, {int hop = kHop}) {
    final nFrames = (data.length - kWin) ~/ hop;
    if (nFrames < 4) return <double>[];
    final window = List<double>.generate(kWin, (i) {
      return 0.5 * (1 - math.cos(2 * math.pi * i / (kWin - 1)));
    });
    const half = kWin ~/ 2;
    final frame = List<double>.filled(kWin, 0.0);
    final prevMag = List<double>.filled(half, 0.0);
    final onset = List<double>.filled(nFrames, 0.0);
    for (int f = 0; f < nFrames; f++) {
      final start = f * hop;
      for (int i = 0; i < kWin; i++) {
        frame[i] = data[start + i] * window[i];
      }
      final spec = fft(frame);
      double flux = 0, prevSum = 0;
      for (int b = 0; b < half; b++) {
        final mag = spec[b].magnitude;
        final d = mag - prevMag[b];
        prevMag[b] = mag;
        prevSum += mag;
        if (d > 0) flux += d;
      }
      onset[f] = prevSum > 1e-9 ? flux / prevSum : 0.0;
    }
    return onset;
  }

  /// Tempogram 参数：窗长 M 帧（≈6s）、窗移 hopT 帧、FFT 零填充到 N。
  static const int kTempoWin = 256;
  static const int kTempoHop = 32;
  static const int kTempoNfft = 512;

  /// 对起音包络做滑动窗 FFT，返回时间平均后的 tempogram 幅度谱（长度 kTempoNfft/2+1）。
  /// bin l 对应的速度 = 60 * l / kTempoNfft * frameRate（frameRate = sr/kHop）。
  static List<double> _meanTempogram(List<double> onset, int sr) {
    final n = onset.length;
    final tg = List<double>.filled(kTempoNfft ~/ 2 + 1, 0.0);
    if (n < kTempoWin) return tg;
    // Hann 窗（抑制谱泄漏，让拍频峰更尖）
    final hann = List<double>.generate(kTempoWin, (i) {
      return 0.5 * (1 - math.cos(2 * math.pi * i / (kTempoWin - 1)));
    });
    final buf = List<double>.filled(kTempoNfft, 0.0);
    final block = List<double>.filled(kTempoWin, 0.0);
    var count = 0;
    for (int start = 0; start + kTempoWin <= n; start += kTempoHop) {
      for (int i = 0; i < kTempoWin; i++) {
        block[i] = onset[start + i] * hann[i];
      }
      // 前 kTempoWin 放数据，其余为 0（零填充加密频域采样）
      buf.setAll(0, block);
      for (int i = kTempoWin; i < kTempoNfft; i++) {
        buf[i] = 0.0;
      }
      final spec = fft(buf);
      for (int l = 0; l < tg.length; l++) {
        tg[l] += spec[l].magnitude;
      }
      count++;
    }
    if (count > 0) {
      for (int l = 0; l < tg.length; l++) {
        tg[l] /= count;
      }
    }
    return tg;
  }

  /// 从平均 tempogram 选最佳拍频 bin：对数正态先验加权 + 抛物线细化。
  /// 返回 {bpm, l}；无有效峰返回 null。
  static (double, int)? _tempoFromTempogram(
    List<double> tg,
    int sr,
  ) {
    final frameRate = sr / kHop;
    // 目标 40–300 BPM → bin 范围
    final bpmPerBin = 60.0 * frameRate / kTempoNfft; // ~5.05
    final l0 = math.max(1, (40.0 / bpmPerBin).ceil());
    final l1 = math.min(tg.length - 1, (300.0 / bpmPerBin).floor());
    if (l1 < l0) return null;
    // 轻平滑（3 点三角核）减少 tempogram 齿状噪声
    final smooth = List<double>.filled(tg.length, 0.0);
    for (int l = l0; l <= l1; l++) {
      final v = tg[l] * 0.5 +
          (l > l0 ? tg[l - 1] * 0.25 : 0) +
          (l < l1 ? tg[l + 1] * 0.25 : 0);
      smooth[l] = v;
    }
    var bestL = -1;
    var best = -1.0;
    for (int l = l0; l <= l1; l++) {
      final bpm = l * bpmPerBin;
      final prior = math.exp(
          -0.5 * math.pow(math.log(bpm / 120.0) / math.ln2, 2).toDouble());
      final s = smooth[l] * prior;
      if (s > best) {
        best = s;
        bestL = l;
      }
    }
    if (bestL <= 0) return null;
    double idx = bestL.toDouble();
    if (bestL > 0 && bestL < tg.length - 1 && smooth[bestL - 1] + smooth[bestL + 1] > 0) {
      final y0 = smooth[bestL - 1], y1 = smooth[bestL], y2 = smooth[bestL + 1];
      final denom = y0 - 2 * y1 + y2;
      if (denom.abs() > 1e-12) {
        idx += (0.5 * (y0 - y2) / denom).clamp(-1.0, 1.0);
      }
    }
    final bpm = double.parse((idx * bpmPerBin).toStringAsFixed(6));
    return (bpm, bestL);
  }

  /// 相位锁定验证：对给定速度 T 及其相位 s，
  ///   on  = Σ_k onset[s + k·L]（拍上能量）
  ///   off = Σ_k onset[s + (k+½)·L]（拍间能量）
  ///   q   = on² / (on + off)
  /// 拍间起音越多（半速/倍速歧义）q 越低；返回该速度下最优相位与 q*。
  static (int, double) _pulseQuality(List<double> onset, double bpm, int sr) {
    final frameRate = sr / kHop;
    final n = onset.length;
    var period = (60.0 * frameRate / bpm).round();
    if (period < 4) return (0, -1.0);
    if (period >= n) period = n - 1;
    final midOff = (period + 1) ~/ 2;
    var bestS = 0;
    var bestQ = -1.0;
    for (int s = 0; s < period; s++) {
      double on = 0, off = 0;
      for (int t = s; t < n; t += period) {
        on += onset[t];
        final mid = t + midOff;
        if (mid < n) off += onset[mid];
      }
      final denom = on + off;
      final q = denom <= 1e-12 ? 0.0 : on * on / denom;
      if (q > bestQ) {
        bestQ = q;
        bestS = s;
      }
    }
    return (bestS, bestQ);
  }

  /// 以 [phase0] 为基准等距排布拍点，再 ±12% 周期内吸附到最近的局部起音峰。
  static List<int> _snappedBeats(
    List<double> onset,
    double bpm,
    int sr, {
    int phase0 = -1,
  }) {
    final n = onset.length;
    final frameRate = sr / kHop;
    var period = (60.0 * frameRate / bpm).round();
    if (period < 4) return <int>[];
    if (phase0 < 0 || phase0 >= n) {
      phase0 = period ~/ 2;
    }
    var s0 = phase0;
    while (s0 - period >= 0) {
      s0 -= period;
    }
    final raw = <int>[];
    for (int t = s0; t < n;) {
      raw.add(t);
      t += period;
    }
    final win = math.max(1, (period * 0.12).round());
    final beats = <int>[];
    for (final b in raw) {
      var lo = b - win, hi = b + win;
      if (lo < 0) lo = 0;
      if (hi >= n) hi = n - 1;
      var bestIdx = b;
      var bestV = -double.infinity;
      for (int i = lo; i <= hi; i++) {
        if (onset[i] > bestV) {
          bestV = onset[i];
          bestIdx = i;
        }
      }
      beats.add(bestIdx);
    }
    final out = <int>[];
    for (final b in beats) {
      if (out.isEmpty || b > out.last) out.add(b);
    }
    return out;
  }

  /// FourierTempogram 完整管线入口。
  static BpmResult analyzeTempogramPcm(
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
    final maxLen = sampleRate * 60;
    final data = samples.length > maxLen ? samples.sublist(0, maxLen) : samples;
    final fullDuration = samples.length / sampleRate;
    try {
      // 1) 全频段谱通量起音
      final onset = _fluxOnset(data, sampleRate);
      if (onset.length < 8) {
        return const BpmResult(
          bpm: null,
          confidence: 0.0,
          error: '音频有效起音过少，无法可靠检测 BPM',
        );
      }
      var anyEnergy = false;
      for (final v in onset) {
        if (v > 0) {
          anyEnergy = true;
          break;
        }
      }
      if (!anyEnergy) {
        return const BpmResult(bpm: null, confidence: 0.0, error: '未检测到有效起音');
      }

      // 2) tempogram 频域估 BPM
      final tg = _meanTempogram(onset, sampleRate);
      final coarse = _tempoFromTempogram(tg, sampleRate);
      if (coarse == null) {
        return const BpmResult(bpm: null, confidence: 0.0, error: '无法可靠检测 BPM');
      }

      // 3) 八度/倍频消歧：与 librosa 蓝本一致，用起音自相关的滞后强度
      //    比较 2x / 1.5x / 0.5x / 2/3x 候选（实测比相位锁定更稳，8/11 通过）。
      final ac = _autocorrelate(onset);
      final refined = _refineTempo(ac, coarse.$1);
      final (bpm, _) = _octaveCorrect(ac, refined);
      if (bpm <= 0) {
        return const BpmResult(bpm: null, confidence: 0.0, error: '无法可靠检测 BPM');
      }

      // 4) 相位（锁定 q 的最优相位）+ 局部峰吸附 → 拍点
      final (phase, _) = _pulseQuality(onset, bpm, sampleRate);
      final beats = _snappedBeats(onset, bpm, sampleRate, phase0: phase);
      if (beats.isEmpty) {
        return const BpmResult(bpm: null, confidence: 0.0, error: '无法跟踪整曲拍点');
      }

      // 5) 下拍对齐 + 6) grid/snap 时间轴（复用公共拍点时间轴工具）
      final startFrame = _downbeatAlign(onset, beats);
      final beatMaps =
          _buildBeatMaps(onset, bpm, startFrame: startFrame, total: fullDuration);
      final grid = beatMaps['grid'] ?? <double>[];
      final confidence = _confidence(onset, bpm, grid);
      final reliability =
          _phaseReliability(onset, bpm, grid.isNotEmpty ? grid.first : 0.0);

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

  // ---------- 算法 3：自相关估拍 + 起音峰圆周直方图定相位（v0.1.0+62） ----------
  // 与算法 2 的纯频域路线互补的「时域统计」路线：
  //   · 起音 = 全频段谱通量（复用 _fluxOnset）
  //   · BPM  = 对起音包络做 IFFT 自相关（复用 _autocorrelate）+ 对数正态先验
  //           扫描 lag → 抛物线细化 → 自相关滞后强度做八度消歧
  //   · 相位 = 统计的「起音峰圆周直方图」：把所有强起音峰取模一个节拍周期，
  //           圆的哪个位置能量最集中（加权直方图 + 环形平滑），哪里就是第 1 拍。
  //           不依赖 DP、不耗尽所有相位——直接由整曲起音的分布投票决定，抗间歇噪声。
  //   · 拍点 = 组合网格 + ±12% 局部峰吸附（复用 _snappedBeats / _followOnsets）
  // 相比算法 1（mel+DP）与算法 2（tempogram+相位锁定），相位判定是「统计投票」，
  // 计算更省，对起音丢失/间歇段落更鲁棒。

  /// 能量和最大化相位（回退用）：把拍点等距放在 s 处时，每拍 ±1/8 周期窗口内
  /// 的局部最大起音强度之和最大，视为最优相位 s∈[0, period)。
  static int _energyPhase(List<double> onset, int period) {
    final n = onset.length;
    final w = math.max(1, period ~/ 8);
    var bestS = 0;
    var bestE = -double.infinity;
    for (int s = 0; s < period; s++) {
      double e = 0;
      for (int t = s; t < n; t += period) {
        var lo = t - w;
        if (lo < 0) lo = 0;
        var hi = t + w;
        if (hi >= n) hi = n - 1;
        double m = -double.infinity;
        for (int i = lo; i <= hi; i++) {
          if (onset[i] > m) m = onset[i];
        }
        if (m.isFinite) e += m;
      }
      if (e > bestE) {
        bestE = e;
        bestS = s;
      }
    }
    return bestS;
  }

  /// 起音峰圆周直方图定相位：取所有强度超过均值 0.6 的起音局部峰，
  /// 按模一个节拍周期分桶（权重 = 峰强度），环形 3-点平滑后取能量最集中桶。
  /// 返回该节拍周期的相位（帧）。峰过少时回退到能量和最大化。
  static int _peakClusterPhase(List<double> onset, double bpm, int sr) {
    final frameRate = sr / kHop;
    final n = onset.length;
    var period = (60.0 * frameRate / bpm).round();
    if (period < 4) return 0;
    if (period >= n) period = n - 1;
    final mean = onset.reduce((a, b) => a + b) / n;
    final thr = mean * 0.6;
    final hist = List<double>.filled(period, 0.0);
    var peakCount = 0;
    for (int i = 2; i < n - 2; i++) {
      if (onset[i] >= onset[i - 1] && onset[i] > onset[i + 1] && onset[i] > thr) {
        hist[i % period] += onset[i];
        peakCount++;
      }
    }
    if (peakCount < 4) return _energyPhase(onset, period);
    // 环形 3-点平滑（首尾相接）
    final hs = List<double>.filled(period, 0.0);
    for (int b = 0; b < period; b++) {
      hs[b] = hist[b] * 0.5 +
          hist[(b - 1 + period) % period] * 0.25 +
          hist[(b + 1) % period] * 0.25;
    }
    var best = 0;
    for (int b = 1; b < period; b++) {
      if (hs[b] > hs[best]) best = b;
    }
    return best;
  }

  /// 算法 3 完整管线入口。
  static BpmResult analyzePeakClusterPcm(
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
    final maxLen = sampleRate * 60;
    final data = samples.length > maxLen ? samples.sublist(0, maxLen) : samples;
    final fullDuration = samples.length / sampleRate;
    try {
      // 1) 全频段谱通量起音
      final onset = _fluxOnset(data, sampleRate);
      if (onset.length < 8) {
        return const BpmResult(
          bpm: null,
          confidence: 0.0,
          error: '音频有效起音过少，无法可靠检测 BPM',
        );
      }
      var anyEnergy = false;
      for (final v in onset) {
        if (v > 0) {
          anyEnergy = true;
          break;
        }
      }
      if (!anyEnergy) {
        return const BpmResult(bpm: null, confidence: 0.0, error: '未检测到有效起音');
      }

      // 2) 起音包络自相关 + 对数正态先验 → 粗估 BPM
      final ac = _autocorrelate(onset);
      final coarse = _tempoFromAC(ac, sampleRate);
      if (coarse <= 0) {
        return const BpmResult(bpm: null, confidence: 0.0, error: '无法可靠检测 BPM');
      }

      // 3) 抛物线细化 + 自相关滞后强度八度消歧
      final refined = _refineTempo(ac, coarse);
      final (bpm, _) = _octaveCorrect(ac, refined);
      if (bpm <= 0) {
        return const BpmResult(bpm: null, confidence: 0.0, error: '无法可靠检测 BPM');
      }

      // 4) 起音峰圆周直方图统计投票定相位 + 局部峰吸附 → 拍点
      final phase = _peakClusterPhase(onset, bpm, sampleRate);
      final beats = _snappedBeats(onset, bpm, sampleRate, phase0: phase);
      if (beats.isEmpty) {
        return const BpmResult(bpm: null, confidence: 0.0, error: '无法跟踪整曲拍点');
      }

      // 5) 下拍对齐 + 6) grid/snap 时间轴（复用公共拍点时间轴工具）
      final startFrame = _downbeatAlign(onset, beats);
      final beatMaps =
          _buildBeatMaps(onset, bpm, startFrame: startFrame, total: fullDuration);
      final grid = beatMaps['grid'] ?? <double>[];
      final confidence = _confidence(onset, bpm, grid);
      final reliability =
          _phaseReliability(onset, bpm, grid.isNotEmpty ? grid.first : 0.0);

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
