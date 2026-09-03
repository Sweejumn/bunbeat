// 变速分级（对应 Web 版 TempoArrow / RecommendPanel 图例）。
//
// 按「与原 BPM 的相对差百分比」分档：
//   差 <3%  绿  =    （几乎无需变速）
//   差 3–5% 绿  ↑/↓
//   差 5–8% 琥珀 ↑/↓
//   差 8–12%红  ↑/↓（勉强可变速）
//   差 >12% 红  ✕   （不适合变速，默认不自动选中）
import 'package:flutter/material.dart';

class TempoGrade {
  final Color color;
  final String symbol;
  final double absPct;
  final String pctLabel;
  const TempoGrade({
    required this.color,
    required this.symbol,
    required this.absPct,
    required this.pctLabel,
  });
}

/// [orig] 为歌曲原 BPM，[target] 为目标 BPM。
/// 方向：原 BPM 高于目标 → 需放慢 ↓；低于目标 → 需加快 ↑。
TempoGrade gradeTempo(double? orig, double target) {
  if (orig == null || orig <= 0) {
    return const TempoGrade(color: Colors.grey, symbol: '—', absPct: 0, pctLabel: '—');
  }
  final absPct = (orig - target).abs() / orig * 100;
  final signed = (orig - target) / orig * 100;
  final pctLabel = signed.abs() < 0.05
      ? '0%'
      : '${signed > 0 ? '+' : ''}${signed.toStringAsFixed(1)}%';
  final arrow = orig > target ? '↓' : '↑';
  if (absPct <= 3) {
    return TempoGrade(color: Colors.greenAccent, symbol: '=', absPct: absPct, pctLabel: pctLabel);
  }
  if (absPct <= 5) {
    return TempoGrade(color: Colors.greenAccent, symbol: arrow, absPct: absPct, pctLabel: pctLabel);
  }
  if (absPct <= 8) {
    return TempoGrade(color: Colors.amber, symbol: arrow, absPct: absPct, pctLabel: pctLabel);
  }
  if (absPct <= 12) {
    return TempoGrade(color: Colors.redAccent, symbol: arrow, absPct: absPct, pctLabel: pctLabel);
  }
  return TempoGrade(color: Colors.redAccent, symbol: '✕', absPct: absPct, pctLabel: pctLabel);
}
