import 'package:flutter/material.dart';

/// 单行文字若超出可用宽度则自动水平滚动显示（跑马灯/横滚），
/// 否则静止显示为单行省略。用于曲库/推荐里较长的歌名。
///
/// 实现：Stack + Positioned 布置两份相同文字（第二份相对第一份偏移
/// 恰好一整段「文字宽+空隙」），用 Transform.translate 整体左移。
/// 动画值 0→1 位移正好一整段，回绕时第二份恰好接上第一份，视觉无缝、
/// 连续滚动、永不停顿。用 Stack+Positioned 而非 Row/OverflowBox，
/// 避免柔性布局宽度误差导致的回绕跳变。
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
  // 一段文字的实际测量宽度。
  double _textWidth = 0;
  // 一周期移动的总距离 = 文字宽 + 空隙。
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

        // 文字未超出宽度：静止显示单行省略。
        if (textWidth <= maxWidth) {
          _cycle = 0;
          return Text(
            widget.text,
            style: style,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          );
        }

        _textWidth = textWidth;
        _cycle = textWidth + _kGap;

        return ClipRect(
          child: AnimatedBuilder(
            animation: _anim,
            builder: (context, _) {
              // 位移 = 动画值 * 整段距离；回绕时第二份恰好接上第一份。
              return Transform.translate(
                offset: Offset(-(_anim.value * _cycle), 0),
                child: Stack(
                  clipBehavior: Clip.hardEdge,
                  children: [
                    Positioned(
                      left: 0,
                      child: Text(
                        widget.text,
                        style: style,
                        maxLines: 1,
                        softWrap: false,
                      ),
                    ),
                    Positioned(
                      left: _textWidth + _kGap,
                      child: Text(
                        widget.text,
                        style: style,
                        maxLines: 1,
                        softWrap: false,
                      ),
                    ),
                  ],
                ),
              );
            },
          ),
        );
      },
    );
  }
}
