import 'package:flutter_test/flutter_test.dart';

import 'package:run_bpm_android/ui/player_page.dart';

void main() {
  group('computeTapBpm（打拍校准，对应 Web TapBpm 取中位）', () {
    test('等间隔 0.5s → 120 BPM（保留小数不强制整数）', () {
      final taps = [0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0];
      final bpm = computeTapBpm(taps)!;
      expect(bpm, closeTo(120.0, 0.001));
      // 两位小数不会是 .00 以外被吞掉的假整数——这里验证是真实 double。
      expect(bpm.toStringAsFixed(2), '120.00');
    });

    test('非整数间隔 → 保留小数（有意义的两位小数）', () {
      // 间隔 0.4975s → 120.60 BPM，绝不是整数 .00。
      final taps = <double>[];
      var t = 1.0;
      for (var i = 0; i < 8; i++) {
        taps.add(t);
        t += 0.4975;
      }
      final bpm = computeTapBpm(taps)!;
      expect(bpm.toStringAsFixed(2), '120.60');
      expect(bpm == bpm.roundToDouble(), isFalse); // 证明不是整数
    });

    test('过滤 ≤0.1s 的双击/误触/seek 回退间隔', () {
      // 第 2~3 个间隔 0.05s（误触），其余 0.5s
      final taps = [0.0, 0.5, 0.55, 1.05, 1.55, 2.05, 2.55, 3.05];
      final bpm = computeTapBpm(taps)!;
      expect(bpm, closeTo(120.0, 0.001));
    });

    test('有效间隔不足 2 个 → null', () {
      // 每拍都被 ≤0.1s 的误触间隔打断，只剩 1 个有效间隔，无法取中位。
      final taps = [0.0, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30, 6.0];
      expect(computeTapBpm(taps), isNull);
    });

    test('有 ≥2 个有效间隔即计算（批次数由调用方 _onTap 控制）', () {
      final taps = [0.0, 0.5, 1.0];
      expect(computeTapBpm(taps), closeTo(120.0, 0.001));
    });
  });
}
