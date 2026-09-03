import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../services/bpm_display_controller.dart';
import '../services/theme_controller.dart';
import 'about_page.dart';
import 'help_dialog.dart';

/// 设置页：外观（主题）+ 使用说明/关于入口。
/// 成熟 App 的基本设置骨架；可继续扩充更多条目。
class SettingsPage extends StatelessWidget {
  const SettingsPage({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final themeCtrl = context.watch<ThemeController>();
    final bpmCtrl = context.watch<BpmDisplayController>();

    return Scaffold(
      appBar: AppBar(title: const Text('设置')),
      body: ListView(
        children: [
          // 外观
          _sectionHeader(theme, '外观'),
          Card(
            margin: const EdgeInsets.symmetric(horizontal: 12),
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('主题', style: theme.textTheme.titleSmall),
                  const SizedBox(height: 12),
                  SegmentedButton<ThemeMode>(
                    segments: const [
                      ButtonSegment(
                        value: ThemeMode.system,
                        label: Text('跟随系统'),
                      ),
                      ButtonSegment(
                        value: ThemeMode.light,
                        label: Text('浅色'),
                      ),
                      ButtonSegment(
                        value: ThemeMode.dark,
                        label: Text('深色'),
                      ),
                    ],
                    selected: {themeCtrl.mode},
                    // 不显示选中对勾图标，避免三个选项宽度变化导致布局被顶开；
                    // 选中态仅靠背景填充色区分。
                    showSelectedIcon: false,
                    onSelectionChanged: (s) =>
                        themeCtrl.setMode(s.first),
                  ),
                  const SizedBox(height: 16),
                  Text('主题色', style: theme.textTheme.titleSmall),
                  const SizedBox(height: 8),
                  // 主题色选择：一行行排列的色块，选中的带对勾。
                  Wrap(
                    spacing: 12,
                    runSpacing: 12,
                    children: [
                      for (final opt in kThemeColors)
                        _ColorSwatch(
                          color: opt.color,
                          name: opt.name,
                          selected: themeCtrl.seed.toARGB32() ==
                              opt.color.toARGB32(),
                          onTap: () => themeCtrl.setSeed(opt.color),
                        ),
                    ],
                  ),
                ],
              ),
            ),
          ),

          // BPM 显示格式
          _sectionHeader(theme, 'BPM 显示'),
          Card(
            margin: const EdgeInsets.symmetric(horizontal: 12),
            child: SwitchListTile(
              secondary: const Icon(Icons.numbers),
              title: const Text('BPM 保留两位小数'),
              subtitle: const Text('曲库/推荐/播放页的 BPM 显示两位小数；关闭则按整数显示。（跑步设置除外）'),
              value: bpmCtrl.twoDecimals,
              onChanged: (v) => bpmCtrl.setTwoDecimals(v),
            ),
          ),

          // 帮助与关于
          _sectionHeader(theme, '帮助与关于'),
          Card(
            margin: const EdgeInsets.symmetric(horizontal: 12),
            child: Column(
              children: [
                ListTile(
                  leading: const Icon(Icons.help_outline),
                  title: const Text('使用说明'),
                  subtitle: const Text('选择节奏、推荐标记与变速播放说明'),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => HelpDialog.show(context),
                ),
                const Divider(height: 1),
                ListTile(
                  leading: const Icon(Icons.info_outline),
                  title: const Text('关于应用'),
                  subtitle: const Text('版本、简介、最新版本公告、检查更新与源码'),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => Navigator.push(
                    context,
                    MaterialPageRoute(builder: (_) => const AboutPage()),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 24),
        ],
      ),
    );
  }

  Widget _sectionHeader(ThemeData theme, String text) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
      child: Text(
        text,
        style: theme.textTheme.labelLarge?.copyWith(
          color: theme.colorScheme.primary,
        ),
      ),
    );
  }
}

/// 单个主题色色块：圆形色点 + 名字，选中显示对勾。
class _ColorSwatch extends StatelessWidget {
  final Color color;
  final String name;
  final bool selected;
  final VoidCallback onTap;
  const _ColorSwatch({
    required this.color,
    required this.name,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return InkWell(
      borderRadius: BorderRadius.circular(8),
      onTap: onTap,
      child: Container(
        width: 52,
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Column(
          children: [
            Container(
              width: 40,
              height: 40,
              decoration: BoxDecoration(
                color: color,
                shape: BoxShape.circle,
                border: Border.all(
                  color: selected
                      ? theme.colorScheme.onSurface
                      : Colors.transparent,
                  width: 2,
                ),
              ),
              child: selected
                  ? const Icon(Icons.check, color: Colors.white)
                  : null,
            ),
            const SizedBox(height: 4),
            Text(
              name,
              style: TextStyle(
                fontSize: 11,
                color: selected
                    ? theme.colorScheme.primary
                    : theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

