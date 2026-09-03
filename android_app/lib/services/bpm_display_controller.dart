/// BPM 显示格式控制器：是否把歌曲/播放相关的 BPM 保留两位小数显示；
/// 持久化到 SharedPreferences。
library;

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 全局 BPM 显示格式（对应设置页的一个开关）。
/// 默认开启：BPM 保留两位小数；关闭后回退为四舍五入整数。
class BpmDisplayController extends ChangeNotifier {
  static const String _prefKey = 'runbpm.bpmTwoDecimals';

  bool _twoDecimals = true;

  bool get twoDecimals => _twoDecimals;

  /// 读取持久化设置（默认开启）。
  Future<void> load() async {
    final prefs = await SharedPreferences.getInstance();
    _twoDecimals = prefs.getBool(_prefKey) ?? true;
    notifyListeners();
  }

  Future<void> setTwoDecimals(bool on) async {
    if (_twoDecimals == on) return;
    _twoDecimals = on;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_prefKey, on);
  }

  /// 按当前开关把 BPM 格式化成字符串（两位小数或四舍五入整数）。
  String format(double? bpm) {
    if (bpm == null) return '—';
    if (_twoDecimals) {
      return bpm.toStringAsFixed(2);
    }
    return bpm.round().toString();
  }
}
