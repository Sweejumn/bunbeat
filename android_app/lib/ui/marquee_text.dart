import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

/// 单行文字若超出可用宽度则自动水平滚动显示（跑马灯/横滚），
/// 否则静止显示为单行省略。用于曲库/推荐里较长的歌名。
///
/// 实现：在 SingleChildScrollView 里渲染两份相同文字（中间隔一段空隙），
/// 用每帧 Ticker 让滚动位置随流逝时间平滑前进，并对「文字宽+空隙」取模。
/// 由于位移一整段时第二份恰好接上第一份，取模回绕在视觉上无缝、也不会有
/// 0→1 循环动画那种"到顶回跳"的停顿；用 SingleChildScrollView 渲染也更
/// 稳定可靠（不会像 Transform/OverflowBox 那样在真机 ListTile 里丢内容）。
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
  // 滚动速度（像素/秒）。
  static const double _kSpeed = 90.0;

  final ScrollController _controller = ScrollController();
  late final Ticker _ticker;
  // 一周期滚动的距离 = 文字宽 + 空隙。
  double _cycle = 0;
  // 是否确实需要滚动（文字超出可用宽度）。
  bool _scrolling = false;

  @override
  void initState() {
    super.initState();
    _ticker = createTicker(_onTick)..start();
  }

  @override
  void dispose() {
    _ticker.dispose();
    _controller.dispose();
    super.dispose();
  }

  void _onTick(Duration elapsed) {
    if (!_scrolling || !_controller.hasClients) return;
    // 位置随流逝时间线性增长，对一整段距离取模；回绕时内容无缝衔接。
    final pos = (elapsed.inMicroseconds / 1e6) * _kSpeed;
    _controller.jumpTo(pos % _cycle);
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
