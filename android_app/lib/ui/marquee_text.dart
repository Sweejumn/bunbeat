import 'package:flutter/material.dart';

/// 单行文字若超出可用宽度则自动水平滚动显示（跑马灯/横滚），
/// 否则静止显示为单行省略。用于曲库/推荐里较长的歌名。
///
/// 实现：在 SingleChildScrollView 里渲染两份相同文字（中间隔一段空隙）。
/// 动画让内容在 0 → (文字宽 + 空隙) 之间循环滚动，这个位移正好让
/// 第二份接上第一份，视觉无缝；且永不滚到滚动条末尾，因此不会出现
/// "滚到底停很久"。用 SingleChildScrollView 是为了在真实 ListTile
/// 布局中稳定渲染（比带约束的 Transform/OverflowBox 更可靠）。
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

  final ScrollController _controller = ScrollController();
  late final AnimationController _anim;
  // 一周期滚动的距离 = 文字宽 + 空隙。
  double _cycle = 0;
  // 是否确实需要滚动（文字超出可用宽度）。
  bool _scrolling = false;

  @override
  void initState() {
    super.initState();
    _anim = AnimationController(vsync: this, duration: _duration)..repeat();
    _anim.addListener(_tick);
  }

  @override
  void dispose() {
    _anim.dispose();
    _controller.dispose();
    super.dispose();
  }

  void _tick() {
    if (!_scrolling || !_controller.hasClients) return;
    // 在 0 → _cycle 间循环滚动；因存在第二份文字，这个位移恰好无缝衔接，
    // 且永远不会滚到滚动条末尾，所以不会在尽头停顿。
    _controller.jumpTo(_anim.value * _cycle);
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
          _scrolling = false;
          _cycle = 0;
          return Text(
            widget.text,
            style: style,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          );
        }

        _scrolling = true;
        _cycle = textWidth + _kGap;
        return ClipRect(
          child: SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            controller: _controller,
            physics: const NeverScrollableScrollPhysics(),
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
    );
  }
}
