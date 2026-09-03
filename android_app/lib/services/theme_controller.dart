/// 全局外观/主题控制器：管理「跟随系统 / 浅色 / 深色」与「主题色」，
/// 持久化到 SharedPreferences。
library;

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 预设主题色（名字 + 种子色值）。默认天蓝（Bunbeat 品牌色）。
class ThemeColorOption {
  final String name;
  final Color color;
  const ThemeColorOption(this.name, this.color);
}

const List<ThemeColorOption> kThemeColors = [
  ThemeColorOption('天蓝', Color(0xFF38BDF8)),
  ThemeColorOption('薄荷绿', Color(0xFF34D399)),
  ThemeColorOption('活力橙', Color(0xFFF97316)),
  ThemeColorOption('热情红', Color(0xFFEF4444)),
  ThemeColorOption('靛蓝', Color(0xFF6366F1)),
  ThemeColorOption('紫罗兰', Color(0xFF8B5CF6)),
  ThemeColorOption('樱花粉', Color(0xFFEC4899)),
  ThemeColorOption('青柠', Color(0xFF84CC16)),
  ThemeColorOption('琥珀金', Color(0xFFF59E0B)),
];

/// 全局主题服务（对应 Web 没有、但成熟 App 应有的「外观设置」：
/// 深浅色 + 主题色）。
class ThemeController extends ChangeNotifier {
  static const String _prefModeKey = 'runbpm.themeMode';
  static const String _prefSystem = 'system';
  static const String _prefLight = 'light';
  static const String _prefDark = 'dark';

  static const String _prefColorKey = 'runbpm.themeColor';

  ThemeMode _mode = ThemeMode.system;
  Color _seed = kThemeColors.first.color;

  ThemeMode get mode => _mode;
  Color get seed => _seed;

  /// 读取持久化主题（模式 + 主题色）。
  Future<void> load() async {
    final prefs = await SharedPreferences.getInstance();
    final s = prefs.getString(_prefModeKey);
    _mode = switch (s) {
      _prefLight => ThemeMode.light,
      _prefDark => ThemeMode.dark,
      _ => ThemeMode.system,
    };
    final colorStr = prefs.getString(_prefColorKey);
    if (colorStr != null) {
      final parsed = _parseColor(colorStr);
      if (parsed != null) _seed = parsed;
    }
    notifyListeners();
  }

  Future<void> setMode(ThemeMode m) async {
    if (_mode == m) return;
    _mode = m;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    final s = switch (m) {
      ThemeMode.light => _prefLight,
      ThemeMode.dark => _prefDark,
      ThemeMode.system => _prefSystem,
    };
    await prefs.setString(_prefModeKey, s);
  }

  Future<void> setSeed(Color c) async {
    _seed = c;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_prefColorKey, _colorToString(c));
  }

  static Color? _parseColor(String hex) {
    final v = int.tryParse(hex.replaceFirst('#', ''), radix: 16);
    if (v == null) return null;
    return Color(0xFF000000 | v);
  }

  static String _colorToString(Color c) {
    // 输出 #RRGGBB（不带 alpha，保持简洁可读）。
    final r = (c.r * 255).round().toRadixString(16).padLeft(2, '0').toUpperCase();
    final g = (c.g * 255).round().toRadixString(16).padLeft(2, '0').toUpperCase();
    final b = (c.b * 255).round().toRadixString(16).padLeft(2, '0').toUpperCase();
    return '#$r$g$b';
  }
}
