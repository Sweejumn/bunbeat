/// 拍点标尺（对应 Web 版 BeatRuler）：主题色线 = 每个拍点，横向流过中心线；
/// 中心线正对的主题色线就是「现在这一拍」。暂停即静止。
///
/// 只渲染可见窗口内的拍点（now ±4 秒），每帧按位置重算，快照帧数有界，
/// 因此歌曲再长也不会因为标的数量而卡顿（Web 用 CSS transform 逐帧同思路）。
import 'package:flutter/material.dart';

class BeatRuler extends StatefulWidget {
  /// 当前激活的拍点（已叠加偏差微调位移后的秒），升序。
  final List<double> beats;

  /// 返回当前播放位置（秒）；随时间推进，驱动标尺滚动。
  final double Function() getPositionSeconds;

  /// 打拍校准标记（媒体时间秒，仅保留最近 20 个），用琥珀色显示在标尺上，
  /// 对应 Web 版 PlayerBar 里 tapMarks 的最近 20 个标记。
  final List<double> tapMarks;

  const BeatRuler({
    super.key,
    required this.beats,
    required this.getPositionSeconds,
    this.tapMarks = const [],
  });

  @override
  State<BeatRuler> createState() => _BeatRulerState();
}

class _BeatRulerState extends State<BeatRuler>
    with SingleTickerProviderStateMixin {
  late final AnimationController _ticker = AnimationController(
    vsync: this,
    duration: const Duration(seconds: 60),
  )..repeat();

  static const double _px = 80.0; // 像素 / 秒（与 Web 一致）

  @override
  void dispose() {
    _ticker.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;
        final beatColor = Theme.of(context).colorScheme.primary;
        return AnimatedBuilder(
          animation: _ticker,
          builder: (context, _) {
            final now = widget.getPositionSeconds();
            final from = now - 4.0;
            final to = now + 4.0;
            final center = width / 2;

            final marks = <Widget>[];
            final beats = widget.beats;
            var lo = _lowerBound(beats, from);
            for (var i = lo; i < beats.length; i++) {
              final t = beats[i];
              if (t > to) break;
              final left = center + (t - now) * _px;
              marks.add(Positioned(
                left: left - 1,
                top: 4,
                bottom: 4,
                child: Container(width: 2, color: beatColor),
              ));
            }

            // 打拍标记（琥珀色，只保留最近 20 个）——与主题色线同一坐标系。
            final taps = widget.tapMarks;
            var tl = _lowerBound(taps, from);
            for (var i = tl; i < taps.length; i++) {
              final t = taps[i];
              if (t > to) break;
              final left = center + (t - now) * _px;
              marks.add(Positioned(
                left: left - 1.5,
                top: 0,
                bottom: 0,
                child: Container(width: 3, color: Colors.amber),
              ));
            }

            return Container(
              height: 48,
              clipBehavior: Clip.hardEdge,
              decoration: BoxDecoration(
                color: Colors.black.withValues(alpha: 0.35),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: Colors.white12),
              ),
              child: Stack(
                children: [
                  ...marks,
                  // 中心播放头
                  Align(
                    alignment: Alignment.center,
                    child: Container(width: 2, color: Colors.white),
                  ),
                ],
              ),
            );
          },
        );
      },
    );
  }

  int _lowerBound(List<double> list, double value) {
    int low = 0, high = list.length;
    while (low < high) {
      final mid = (low + high) >> 1;
      if (list[mid] < value) {
        low = mid + 1;
      } else {
        high = mid;
      }
    }
    return low;
  }
}
