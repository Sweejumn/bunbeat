import 'package:flutter/material.dart';

/// 单行文字若超出可用宽度则自动水平滚动显示（跑马灯/横滚），
/// 否则静止显示为单行省略。用于曲库/推荐里较长的歌名。
///
/// 采用「双文本段 + Transform.translate」的标准无缝跑马灯：
/// 绘制两份相同文字，一周期内整体左移一个「文字宽 + 间距」，
/// 首份完全滚出时第二份恰好接上，视觉上连续滚动、永不停顿。
class MarqueeText extends StatefulWidget {
  const MarqueeText(
    this.text, {
    super.key,
    this.style,
  });

  final String text;
  final TextStyle? style;

  @override
  State<MarqueeText> createState() => _MarqueeTextState();
}

class _MarqueeTextState extends State<MarqueeText>
    with SingleTickerProviderStateMixin {
  // 两段文字之间的空隙。
  static const double _kGap = 48.0;
  static const Duration _duration = Duration(seconds: 6);

  late final AnimationController _anim;
  // 需要的动画进度：一段需要移动的总距离（文字宽 + 间距）。
  double _cycle = 0;

  @override
  void initState() {
    super.initState();
    _anim = AnimationController(vsync: this, duration: _duration)..repeat();
  }

  @override
  void dispose() {
    _anim.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final style = widget.style ?? DefaultTextStyle.of(context).style;
    return LayoutBuilder(
      builder: (context, constraints) {
        final maxWidth = constraints.maxWidth;
        final tp = TextPainter(
          text: TextSpan(text: widget.text, style: style),
          maxLines: 1,
          textDirection: TextDirection.ltr,
        )..layout();
        final textWidth = tp.width;

        // 只有当文字超出可用宽度时才滚动。
        if (textWidth <= maxWidth) {
          _cycle = 0;
          return Text(
            widget.text,
            style: style,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          );
        }

        // 一周期移动 文本宽 + 间距；两段紧邻即可无缝。
        // OverflowBox 给内部 Row 无界宽度（自然尺寸可超出裁剪区），由外层
        // ClipRect 负责裁剪；这是用于无缝跑马灯、故意允许溢出的标准做法，
        // 不会触发 RenderFlex 溢出报错，滚动永不停顿。
        _cycle = textWidth + _kGap;

        return ClipRect(
          child: AnimatedBuilder(
            animation: _anim,
            builder: (context, _) {
              // 每帧从 _anim.value 重新计算位移，确保真正持续滚动。
              final offset = -(_anim.value * _cycle);
              return Transform.translate(
                offset: Offset(offset, 0),
                child: OverflowBox(
                  minWidth: 0,
                  maxWidth: double.infinity,
                  alignment: Alignment.centerLeft,
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        widget.text,
                        style: style,
                        maxLines: 1,
                        softWrap: false,
                      ),
                      const SizedBox(width: 48),
                      Text(
                        widget.text,
                        style: style,
                        maxLines: 1,
                        softWrap: false,
                      ),
                    ],
                  ),
                ),
              );
            },
          ),
        );
      },
    );
  }
}
