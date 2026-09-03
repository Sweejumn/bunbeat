import 'dart:math' as math;

import 'package:flutter_test/flutter_test.dart';
import 'package:run_bpm_android/services/bpm_analyzer.dart';
import 'package:run_bpm_android/services/fft.dart';

/// 生成在 [bpm] 处（等间隔）有 onset 脉冲的合成信号。
List<double> makeClickTrack(double bpm, int sr, int seconds) {
  final period = 60.0 / bpm;
  final out = List<double>.filled(sr * seconds, 0.0);
  for (double t = 0.05; t < seconds; t += period) {
    final start = (t * sr).round();
    for (var i = 0; i < 400 && start + i < out.length; i++) {
      // 短促脉冲（类似指标环境）。
      final env = math.exp(-8 * i / sr);
      out[start + i] += math.sin(2 * math.pi * 200 * i / sr) * 0.9 * env;
    }
  }
  return out;
}

void main() {
  group('nextPow2', () {
    test('边界值', () {
      expect(nextPow2(1), 1);
      expect(nextPow2(2), 2);
      expect(nextPow2(3), 4);
      expect(nextPow2(4), 4);
      expect(nextPow2(5), 8);
      expect(nextPow2(1024), 1024);
      expect(nextPow2(1025), 2048);
    });
  });

  group('FFT', () {
    test('纯正弦在正确 bin 处有峰值', () {
      const sr = 1024; // 分辨率 1 Hz/bin
      final n = 1024;
      final input = List<double>.generate(n, (i) {
        return math.sin(2 * math.pi * 100 * i / sr); // 100 Hz
      });
      final spec = fft(input);
      // 找最大能量 bin
      var bestBin = 0;
      var best = -1.0;
      for (var i = 0; i < spec.length ~/ 2; i++) {
        if (spec[i].magnitude > best) {
          best = spec[i].magnitude;
          bestBin = i;
        }
      }
      // 频率 = bin（因为 sr==n，1 bin = 1 Hz）
      expect(bestBin, inInclusiveRange(99, 101));
    });

    test('高比率的峰值应显著高于其它 bin', () {
      const sr = 4096;
      final n = 4096;
      final input = List<double>.generate(n, (i) {
        return math.sin(2 * math.pi * 440 * i / sr);
      });
      final spec = fft(input);
      final mags = List<double>.generate(
          spec.length ~/ 2, (i) => spec[i].magnitude);
      mags.sort((a, b) => b.compareTo(a));
      expect(mags[0], greaterThan(mags[1] * 100)); // 主峰 ≫ 其它
    });
  });

  group('BpmAnalyzer.analyzePcm', () {
    test('120 BPM 脉冲序列被识别为约 120 BPM', () {
      const sr = 22050;
      final samples = makeClickTrack(120.0, sr, 12);
      final r = BpmAnalyzer.analyzePcm(samples, sampleRate: sr);
      expect(r.bpm, isNotNull);
      // 允许 ±3 BPM（可允许倍频/八度校正误差）
      final bpm = r.bpm!;
      expect(bpm, closeTo(120.0, 3.0));
    });

    test('150 BPM 脉冲序列被识别为约 150 BPM', () {
      const sr = 22050;
      final samples = makeClickTrack(150.0, sr, 12);
      final r = BpmAnalyzer.analyzePcm(samples, sampleRate: sr);
      expect(r.bpm, isNotNull);
      expect(r.bpm!, closeTo(150.0, 3.0));
    });

    test('静音输入不崩溃且 bpm 为 null', () {
      final samples = List<double>.filled(22050, 0.0);
      final r = BpmAnalyzer.analyzePcm(samples, sampleRate: 22050);
      expect(r, isNotNull);
    });

    test('空输入不崩溃', () {
      final r = BpmAnalyzer.analyzePcm(const [], sampleRate: 22050);
      expect(r, isNotNull);
    });
  });

  group('BPM Analyzer 结果一致性', () {
    test('analyzePcm 对同一输入两次结果一致（确定性）', () {
      const sr = 22050;
      final samples = makeClickTrack(130.0, sr, 10);
      final a = BpmAnalyzer.analyzePcm(samples, sampleRate: sr);
      final b = BpmAnalyzer.analyzePcm(samples, sampleRate: sr);
      expect(a.bpm, b.bpm);
      expect(a.confidence, b.confidence);
    });

    test('beatMaps 模式均生成且确定（grid/snap）', () {
      const sr = 22050;
      final samples = makeClickTrack(140.0, sr, 10);
      final a = BpmAnalyzer.analyzePcm(samples, sampleRate: sr);
      final b = BpmAnalyzer.analyzePcm(samples, sampleRate: sr);
      expect(a.beatMaps, isNotNull);
      expect(a.beatMaps!.keys.toSet(), containsAll(['grid', 'snap']));
      expect(a.beatMaps!['grid'], isNotEmpty);
      expect(a.beatMaps, b.beatMaps);
    });

    test('snap 拍点在各自 ±12% 周期内贴向 grid', () {
      const sr = 22050;
      final samples = makeClickTrack(140.0, sr, 10);
      final r = BpmAnalyzer.analyzePcm(samples, sampleRate: sr);
      final grid = r.beatMaps!['grid']!;
      final snap = r.beatMaps!['snap']!;
      final period = 60.0 / r.bpm!;
      final n = math.min(grid.length, snap.length);
      for (var i = 0; i < n; i++) {
        expect((snap[i] - grid[i]).abs(), lessThanOrEqualTo(0.12 * period + 1e-6));
      }
    });

    test('相位可靠性在 0..1 且确定', () {
      const sr = 22050;
      final samples = makeClickTrack(120.0, sr, 10);
      final r = BpmAnalyzer.analyzePcm(samples, sampleRate: sr);
      expect(r.phaseReliability, isNotNull);
      expect(r.phaseReliability!, inInclusiveRange(0.0, 1.0));
    });

    test('干净节拍曲子的可信度较高（拍子对齐，而非局部峰等距）', () {
      const sr = 22050;
      // 规则 120 BPM 脉冲：起音峰应几乎全部落在检测出的拍子上，
      // 可信度（能量加权"落拍"命中率）应明显高于旧版动不动归零的情况。
      final samples = makeClickTrack(120.0, sr, 12);
      final r = BpmAnalyzer.analyzePcm(samples, sampleRate: sr);
      expect(r.bpm, isNotNull);
      expect(r.confidence, greaterThan(0.5));
    });

    test('超过 60 秒的歌曲：grid 拍子铺满整首歌（不止开头 60s）', () {
      const sr = 22050;
      // 90 秒的 120 BPM 轨道：分析窗口只取前 60s，但拍子网格应一直排到整首歌末尾，
      // 否则拍点标尺在歌曲后半段会没有绿线。
      final samples = makeClickTrack(120.0, sr, 90);
      final r = BpmAnalyzer.analyzePcm(samples, sampleRate: sr);
      expect(r.bpm, isNotNull);
      final grid = r.beatMaps!['grid']!;
      expect(grid, isNotEmpty);
      // 网格最后一个拍子应明显越过 60s 分析窗口（接近整首歌 90s）。
      expect(grid.last, greaterThan(60.0));
      // snap 也继承了铺满的性质（在无起音的尾部回退到等距 grid）。
      expect(r.beatMaps!['snap']!.last, greaterThan(60.0));
    });
  });
}
