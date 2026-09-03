import 'package:flutter/material.dart';

/// 单行文字若超出可用宽度则自动水平滚动显示（跑马灯/横滚），
/// 否则静止显示为单行省略。用于曲库/推荐里较长的歌名。
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
  // 每周期末尾多留一段空隙，循环跳回起点时更接近无缝横滚。
  static const double _GAP = 48.0;

  final ScrollController _controller = ScrollController();
  late final AnimationController _anim;
  double _maxScroll = 0;

  @override
  void initState() {
    super.initState();
    _anim = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 6),
    )
      ..addListener(_tick)
      ..repeat();
  }

  @override
  void dispose() {
    _anim.dispose();
    _controller.dispose();
    super.dispose();
  }

  void _tick() {
    if (_maxScroll <= 0 || !_controller.hasClients) return;
    // 动画值 0→1 对应滚动 0 → 末尾(+空隙缓冲)，满周期跳回起点循环。
    final p = _anim.value * (_maxScroll + _GAP);
    _controller.jumpTo(p > _maxScroll ? _maxScroll : p);
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
        final overflow = tp.width - maxWidth;
        _maxScroll = overflow > 0 ? overflow : 0;

        if (overflow <= 0) {
          return Text(
            widget.text,
            style: style,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          );
        }
        return ClipRect(
          child: SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            controller: _controller,
            physics: const NeverScrollableScrollPhysics(),
            child: Text(
              widget.text,
              style: style,
              maxLines: 1,
              softWrap: false,
            ),
          ),
        );
      },
    );
  }
}
