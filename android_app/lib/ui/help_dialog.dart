import 'package:flutter/material.dart';

/// 使用说明条目：一个小标题 + 一行正文。
class HelpItem {
  final String title;
  final String body;
  const HelpItem(this.title, this.body);
}

/// 一个页面的使用说明集合，按页面分组。
/// 各页右上角打开时只显示本页说明；设置页打开时用 [all] 滑动切换全部。
class HelpSection {
  final String label;
  final List<HelpItem> items;
  const HelpSection(this.label, this.items);

  static const library = HelpSection('曲库', [
    HelpItem('添加音乐',
        '点顶部「添加音乐」可快速选择预设音源（网易云音乐 / QQ音乐 / 酷狗音乐），'
        '或选「自定义文件夹」手动挑选目录；选定后自动扫描导入其中的音乐。'),
    HelpItem('长按操作',
        '长按任一首歌可：归档（从曲库与推荐隐藏，右上「归档」可查看并放回）、'
        '重新检测 BPM、BPM ×2、手动修改 BPM。'),
    HelpItem('排序与搜索',
        '顶部排序按钮可切换顺序（默认 / 标题 / BPM / 时长）；点搜索按钮可按歌名、歌手筛选。'),
    HelpItem('移除歌曲',
        '曲库不直接删除本地文件；不想要的歌可长按「归档」隐藏，需要时在归档页一键放回。'),
  ]);

  static const recommend = HelpSection('推荐', [
    HelpItem('选择节奏区间',
        '拖动滑块会自动切换运动模式（走路/慢跑/跑步/快跑）；也可点上方模式卡片快速切换。'
        '想用区间外的节奏，直接在右侧输入框手动输入 BPM（40–300）。'),
    HelpItem('推荐标记含义',
        '= 与原 BPM 差 <3%（几乎不用变速）；↑/↓ 3–8%（轻微变速）；'
        '红 8–12%（变速较多）；✕ >12%（不适合变速，默认不勾选）。'),
    HelpItem('变速并播放',
        '勾选歌曲后点底部「变速并播放」，会保持音高把每首变速到目标 BPM 连续播放；'
        '可用上方「自动勾选可变速 / 全选 / 清空」调整勾选。'),
    HelpItem('与曲库联动',
        '本页推荐来自「曲库」当前文件夹；在曲库「添加音乐」或长按「归档」会同步影响这里的推荐结果。'),
  ]);

  static const player = HelpSection('播放', [
    HelpItem('变速播放',
        '保持歌曲音高，把当前歌曲变速到目标 BPM 连续播放。'),
    HelpItem('节拍器',
        '开启节拍器并选择音效，可跟随节拍跑；支持音量调节与打拍校准。'),
    HelpItem('播放模式',
        '左下角按钮点按循环切换：列表循环 → 单曲循环 → 随机。'),
    HelpItem('播放列表',
        '右下角打开播放列表：点选跳歌、长按拖动排序、删除单首、清空。'),
  ]);

  static const settings = HelpSection('设置', [
    HelpItem('外观',
        '选择主题（跟随系统 / 浅色 / 深色）与主题色，实时生效。'),
    HelpItem('BPM 显示',
        '开关「BPM 保留两位小数」，控制曲库/推荐/播放页的 BPM 显示精度。'),
  ]);

  /// 全部页面的说明，供设置页「使用说明」滑动切换查看。
  static const List<HelpSection> all = [library, recommend, player, settings];
}

/// 使用说明弹窗。
///
/// - 各页右上角打开：用 [HelpDialog.show] 传对应 [HelpSection]，只显示本页说明；
/// - 设置页「使用说明」打开：用 [HelpDialog.showAll]，顶部可分段切换、内容可左右滑动。
class HelpDialog {
  HelpDialog._();

  static void show(BuildContext context, {required HelpSection section}) {
    showDialog<void>(
      context: context,
      builder: (ctx) {
        return AlertDialog(
          title: Text('使用说明 · ${section.label}'),
          content: SingleChildScrollView(
            child: _ItemsList(section: section),
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

  /// 展示所有页面的说明：顶部可点击分段，正文可左右滑动切换。
  static void showAll(BuildContext context) {
    showDialog<void>(
      context: context,
      builder: (_) => const _AllHelpDialog(),
    );
  }
}

/// 展示全部使用说明：顶部 TabBar 分段 + 正文 TabBarView 左右滑动。
class _AllHelpDialog extends StatelessWidget {
  const _AllHelpDialog();

  @override
  Widget build(BuildContext context) {
    final sections = HelpSection.all;
    final theme = Theme.of(context);
    return Dialog(
      insetPadding: const EdgeInsets.symmetric(horizontal: 24, vertical: 32),
      // TabBar / TabBarView 必须共享同一个 TabController，
      // 否则打开时 TabBarView 会因找不到 controller 直接抛错、弹窗打不开。
      child: DefaultTabController(
        length: sections.length,
        child: SizedBox(
          width: double.infinity,
          height: 420,
          child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
              child: Text('使用说明',
                  style: theme.textTheme.titleMedium
                      ?.copyWith(fontWeight: FontWeight.bold)),
            ),
            const SizedBox(height: 8),
            // 顶部可点击的分段条（左滑右滑与点击均可切换）。
            TabBar(
              isScrollable: true,
              tabAlignment: TabAlignment.center,
              tabs: [for (final s in sections) Tab(text: s.label)],
            ),
            const Divider(height: 1),
            Expanded(
              child: TabBarView(
                children: [
                  for (final s in sections)
                    SingleChildScrollView(
                      padding: const EdgeInsets.all(16),
                      child: _ItemsList(section: s),
                    ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(8),
              child: Align(
                alignment: Alignment.centerRight,
                child: TextButton(
                  onPressed: () => Navigator.pop(context),
                  child: const Text('知道了'),
                ),
              ),
            ),
          ],
        ),
        ),
      ),
    );
  }
}

/// 单个页面的说明条目列表（标题 + 正文）。供单页与全部模式共用。
class _ItemsList extends StatelessWidget {
  final HelpSection section;
  const _ItemsList({required this.section});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final titleStyle =
        const TextStyle(fontWeight: FontWeight.bold, fontSize: 13);
    final bodyStyle = TextStyle(
      fontSize: 13,
      color: theme.colorScheme.onSurfaceVariant,
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        for (final item in section.items)
          Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(item.title, style: titleStyle),
                const SizedBox(height: 2),
                Text(item.body, style: bodyStyle),
              ],
            ),
          ),
      ],
    );
  }
}
