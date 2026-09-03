import 'package:flutter/material.dart';
import 'package:marquee/marquee.dart';

/// 单行文字：若超出可用宽度则自动水平滚动（跑马灯/横滚），否则静止单行省略。
///
/// 只对"确实超宽"的文本启用滚动，其余一律用最朴素的单行 [Text]。
/// 实测在这台真机上，任何"滚动/位移/裁剪"结构里的文字都会整块消失
/// （静态普通 Text 正常），因此用宽度判断把绝大多数歌名保持在普通 Text 分支，
/// 只有真正超长的才进滚动分支，最大限度避免文字消失。
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
    return LayoutBuilder(
      builder: (context, constraints) {
        final tp = TextPainter(
          text: TextSpan(text: text, style: s),
          maxLines: 1,
          textDirection: TextDirection.ltr,
        )..layout(maxWidth: constraints.maxWidth, minWidth: 0);
        final overflows = tp.didExceedMaxLines;

        if (!overflows) {
          // 不超宽：静态单行省略，绝不动画/位移，确保文字显示。
          return Text(
            text,
            style: s,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          );
        }

        // 超宽：启用跑马灯滚动（委托 marquee 包）。
        return Marquee(
          text: text,
          style: s,
          scrollAxis: Axis.horizontal,
          crossAxisAlignment: CrossAxisAlignment.center,
          blankSpace: 48,
          velocity: 48,
          startAfter: Duration.zero,
          // 每轮之间不暂停，保持连续滚动。
          pauseAfterRound: Duration.zero,
          startPadding: 0,
          fadingEdgeStartFraction: 0.0,
          fadingEdgeEndFraction: 0.0,
          // numberOfRounds 不传 → 无限滚动。
        );
      },
    );
  }
}
