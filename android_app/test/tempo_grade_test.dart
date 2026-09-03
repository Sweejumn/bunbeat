import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:run_bpm_android/ui/tempo_grade.dart';

void main() {
  group('gradeTempo（对应 Web TempoArrow 图例）', () {
    test('无 BPM → 灰色 —', () {
      final g = gradeTempo(null, 155);
      expect(g.symbol, '—');
    });

    test('差 <3% → 绿 =', () {
      final g = gradeTempo(156, 155); // 差 0.64%
      expect(g.symbol, '=');
      expect(g.color, Colors.greenAccent);
    });

    test('差 3–5% → 绿 ↑/↓（高于目标需放慢 ↓）', () {
      final g = gradeTempo(160, 155); // 差 3.1%
      expect(g.symbol, '↓');
      expect(g.color, Colors.greenAccent);
    });

    test('差 3–5% → 绿 ↑（低于目标需加快 ↑）', () {
      final g = gradeTempo(150, 155); // 差 3.3%
      expect(g.symbol, '↑');
      expect(g.color, Colors.greenAccent);
    });

    test('差 5–8% → 琥珀 ↑/↓', () {
      final g = gradeTempo(166, 155); // 差 6.6%
      expect(g.color, Colors.amber);
      expect(g.symbol, '↓');
    });

    test('差 8–12% → 红 ↑/↓（勉强可变速）', () {
      final g = gradeTempo(170, 155); // 差 8.8%
      expect(g.color, Colors.redAccent);
      expect(g.symbol, '↓');
    });

    test('差 >12% → 红 ✕（不可变速）', () {
      final g = gradeTempo(180, 155); // 差 13.9%
      expect(g.color, Colors.redAccent);
      expect(g.symbol, '✕');
    });

    test('pctLabel 带符号显示', () {
      expect(gradeTempo(160, 155).pctLabel, '+3.1%');
      expect(gradeTempo(150, 155).pctLabel, '-3.3%');
    });

    test('原始 BPM 等于目标 → 0% 且 =', () {
      final g = gradeTempo(155, 155);
      expect(g.symbol, '=');
      expect(g.pctLabel, '0%');
    });
  });
}
