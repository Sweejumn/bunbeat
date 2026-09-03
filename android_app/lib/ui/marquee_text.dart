import 'package:flutter/material.dart';
import 'package:marquee/marquee.dart';

/// 单行文字若超出可用宽度则自动水平滚动显示（跑马灯/横滚），
/// 否则静止显示为单行省略。用于曲库/推荐里较长的歌名。
///
/// 底层委托给成熟的 `marquee` 开源包（在真实设备上被广泛验证，
/// 内部用动画 + 位移实现无缝连续滚动，无循环停顿、也不易丢内容）。
/// pauseAfterRound 设为 0，确保每一轮之间不停留、持续滚动。
class MarqueeText extends StatelessWidget {
  const MarqueeText(
    this.text, {
    super.key,
    this.style,
  });

  final String text;
  final TextStyle? style;

  @override
  Widget build(BuildContext context) {
    final s = style ?? DefaultTextStyle.of(context).style;
    return Marquee(
      text: text,
      style: s,
      scrollAxis: Axis.horizontal,
      crossAxisAlignment: CrossAxisAlignment.center,
      blankSpace: 48,
      velocity: 60,
      // 每轮之间不暂停，保持连续滚动。
      pauseAfterRound: Duration.zero,
      startPadding: 0,
      // 不开淡出边缘（保持与文字左对齐，避免边缘渐隐影响可读性）。
      fadingEdgeStartFraction: 0.0,
      fadingEdgeEndFraction: 0.0,
      // numberOfRounds 不传 → 无限滚动。
    );
  }
}
