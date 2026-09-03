import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:run_bpm_android/ui/marquee_text.dart';

void main() {
  testWidgets('MarqueeText 短文字可被找到，且不抛异常', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: SizedBox(width: 200, child: MarqueeText('短歌名')),
        ),
      ),
    );
    // marquee 包内部可能以多份 Text 渲染（无缝滚动），这里只要求至少能找到该文本。
    // 额外 pump 让 startAfter 定时器触发，避免测试结束时的 pending timer 断言。
    await tester.pump(const Duration(milliseconds: 100));
    expect(tester.takeException(), isNull);
    expect(find.text('短歌名'), findsWidgets);
    // 卸载组件树，触发 marquee 的 dispose 以取消内部定时器。
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('MarqueeText 长文字也不抛错并显示该文本', (tester) async {
    // 在很窄的容器里放超长文字，验证溢出分支可构建（自动滚动）。
    final long = '这是一首特别特别特别特别特别特别特别特别特别特别长的歌名示例';
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SizedBox(
            width: 100,
            child: MarqueeText(long, style: const TextStyle(fontSize: 16)),
          ),
        ),
      ),
    );
    // 不 pumpAndSettle：跑马灯在长文字下会无限循环。pump 让起始定时器触发后进入滚动。
    await tester.pump(const Duration(milliseconds: 100));
    expect(tester.takeException(), isNull);
    expect(find.textContaining('这是一首'), findsWidgets);
    // 卸载组件树，触发 marquee 的 dispose 以取消内部定时器。
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(milliseconds: 100));
  });
}
