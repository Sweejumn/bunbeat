import 'package:flutter/material.dart';

/// 使用说明对话框：把界面上省略掉的图例与操作提示收在这里。
/// 供推荐页「？」图标与设置页「使用说明」入口共用。
class HelpDialog {
  HelpDialog._();

  static void show(BuildContext context) {
    showDialog<void>(
      context: context,
      builder: (ctx) {
        TextStyle titleStyle() =>
            const TextStyle(fontWeight: FontWeight.bold, fontSize: 13);
        TextStyle bodyStyle() =>
            const TextStyle(fontSize: 13, color: Colors.white70);
        Widget item(String title, String body) {
          return Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: titleStyle()),
                const SizedBox(height: 2),
                Text(body, style: bodyStyle()),
              ],
            ),
          );
        }

        return AlertDialog(
          title: const Text('使用说明'),
          content: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                item('选择节奏区间', '拖动滑块会自动切换运动模式（走路/慢跑/跑步/快跑）；也可点上方模式卡片快速切换。想用区间外的节奏，直接在右侧输入框手动输入 BPM（40–300）。'),
                item('推荐标记含义', '= 与原 BPM 差 <3%（几乎不用变速）；↑/↓ 3–8%（轻微变速）；🔴 8–12%（变速较多）；✕ >12%（不适合变速，默认不勾选）。'),
                item('变速并播放', '勾选歌曲后点底部「变速并播放」，会保持音高把每首变速到目标 BPM 连续播放；可以在上方“选择/自动勾选可变速/全选/全不选”调整勾选。'),
                item('对齐 Web 版', '本页与 Web 版 RUN BPM 的推荐与节拍模式选择器一致，操作习惯相同。'),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('知道了'),
            ),
          ],
        );
      },
    );
  }
}
