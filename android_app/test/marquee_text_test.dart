import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:run_bpm_android/ui/marquee_text.dart';

void main() {
  testWidgets('MarqueeText 短文字静止显示，可通过按文本找见', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: SizedBox(width: 200, child: MarqueeText('短歌名')),
        ),
      ),
    );
    expect(find.text('短歌名'), findsOneWidget);
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
    await tester.pump(); // 不 pumpAndSettle：跑马灯在长文字下会无限循环。
    expect(find.text(long), findsOneWidget);
  });
}
