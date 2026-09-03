import 'dart:convert';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'audio_player_service.dart';

/// 播放循环模式。
enum LoopMode {
  /// 列表播完即停
  off,

  /// 列表循环（播到末尾回到第一首）
  all,

  /// 单曲循环（当前曲播完自动重播）
  one,
}

/// 当前播放队列状态（驱动“推荐 → 播放”流程与播放页展示）。
class QueueService extends ChangeNotifier {
  List<PlaylistItem> _items = [];
  int _index = -1;
  bool _playing = false;

  LoopMode _loopMode = LoopMode.all;
  bool _shuffle = false;

  // ---------- 持久化（退出后恢复上次播放内容/模式） ----------
  static const String _prefQueue = 'runbpm.queue';
  static const String _prefQueueIndex = 'runbpm.queueIndex';
  static const String _prefLoopMode = 'runbpm.loopMode';
  static const String _prefShuffle = 'runbpm.shuffle';

  /// 启动时从持久化恢复上次的播放队列 + 当前曲 + 循环/随机模式。
  /// 只恢复到「已就绪不自动播放」状态：不触发播放，需用户点击播放键。
  Future<void> loadFromPrefs() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_prefQueue);
    if (raw == null || raw.isEmpty) return;
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! List) return;
      final items = <PlaylistItem>[];
      for (final e in decoded) {
        if (e is! Map) continue;
        final fp = e['f'];
        if (fp is! String || fp.isEmpty) continue;
        final t = (e['t'] as num?)?.toDouble() ?? 120.0;
        final o = (e['o'] as num?)?.toDouble() ?? t;
        items.add(PlaylistItem(filePath: fp, originalBpm: o, targetBpm: t));
      }
      if (items.isEmpty) return;
      _items = items;
      _index = (prefs.getInt(_prefQueueIndex) ?? 0).clamp(0, _items.length - 1);
      final lm = prefs.getString(_prefLoopMode);
      for (final m in LoopMode.values) {
        if (m.name == lm) {
          _loopMode = m;
          break;
        }
      }
      _shuffle = prefs.getBool(_prefShuffle) ?? false;
      _playing = false; // 恢复后不自动播放
      _shuffleHistory.clear();
      notifyListeners();
    } catch (_) {
      // 反序列化失败则忽略，保持空队列。
    }
  }

  /// 把当前队列/当前曲/循环/随机模式写盘（退出后可恢复）。
  Future<void> _persist() async {
    final prefs = await SharedPreferences.getInstance();
    final list = [
      for (final it in _items)
        {'f': it.filePath, 't': it.targetBpm, 'o': it.originalBpm},
    ];
    await prefs.setString(_prefQueue, jsonEncode(list));
    await prefs.setInt(_prefQueueIndex, _index);
    await prefs.setString(_prefLoopMode, _loopMode.name);
    await prefs.setBool(_prefShuffle, _shuffle);
  }

  /// 随机播放时记住上一首，避免立刻重复；仅队列内随机。
  final List<int> _shuffleHistory = [];

  List<PlaylistItem> get items => List.unmodifiable(_items);
  int get index => _index;
  PlaylistItem? get current =>
      (_index >= 0 && _index < _items.length) ? _items[_index] : null;
  bool get playing => _playing;
  bool get hasQueue => _items.isNotEmpty;

  LoopMode get loopMode => _loopMode;
  bool get shuffle => _shuffle;
  bool get repeatingOne => _loopMode == LoopMode.one;

  void setLoopMode(LoopMode mode) {
    if (_loopMode == mode) return;
    _loopMode = mode;
    notifyListeners();
    _persist();
  }

  void setShuffle(bool on) {
    if (_shuffle == on) return;
    _shuffle = on;
    _shuffleHistory.clear();
    notifyListeners();
    _persist();
  }

  /// 用选定歌曲建立播放列表并从第一首开始。
  void start(List<PlaylistItem> items) {
    _items = List.of(items);
    _index = 0;
    _playing = true;
    _shuffleHistory.clear();
    notifyListeners();
    _persist();
  }

  void setPlaying(bool p) {
    if (_playing == p) return;
    _playing = p;
    notifyListeners();
  }

  /// 手动切下一首（用户点 next 时）。单曲循环下点 next 仍前进；
  /// 只有自然播完（ended）才遵循单曲循环。
  void next() {
    if (_items.isEmpty) return;
    if (_items.length == 1) {
      _index = 0;
      _playing = true;
      notifyListeners();
      _persist();
      return;
    }
    if (_shuffle) {
      _index = _randomNext();
    } else {
      _index = (_index + 1) % _items.length;
    }
    _playing = true;
    notifyListeners();
    _persist();
  }

  void prev() {
    if (_items.isEmpty) return;
    if (_items.length == 1) {
      _index = 0;
      _playing = true;
      notifyListeners();
      _persist();
      return;
    }
    if (_shuffle) {
      _index = _randomNext();
    } else {
      _index = (_index - 1 + _items.length) % _items.length;
    }
    _playing = true;
    notifyListeners();
    _persist();
  }

  /// 一首自然播放结束后的行为（由播放器 ended 事件调用）。
  void onEnded() {
    if (_items.isEmpty) return;
    if (_loopMode == LoopMode.one) {
      // 单曲循环：停在原曲（index 不变）
      _playing = true;
      notifyListeners();
      return;
    }
    if (_items.length == 1) {
      // 只有一首：off 模式自然停，all/one 循环。
      if (_loopMode == LoopMode.off) {
        _playing = false;
        notifyListeners();
        _persist();
      } else {
        _playing = true;
        notifyListeners();
      }
      return;
    }
    if (_shuffle) {
      _index = _randomNext();
    } else {
      _index = (_index + 1) % _items.length;
      if (_index == 0 && _loopMode == LoopMode.off) {
        // 列表播完且非循环：停在末尾，停止播放
        _index = _items.length - 1;
        _playing = false;
        notifyListeners();
        _persist();
        return;
      }
    }
    _playing = true;
    notifyListeners();
    _persist();
  }

  /// 跳转到队列中指定索引的歌曲（由播放列表点击触发）。
  void jumpTo(int index) {
    if (index < 0 || index >= _items.length) return;
    _index = index;
    _playing = true;
    _shuffleHistory.clear();
    notifyListeners();
    _persist();
  }

  /// 从队列移除指定索引；若移除的是当前曲，则自动落到下一首（或越界时回退）。
  void removeAt(int index) {
    if (index < 0 || index >= _items.length) return;
    _items.removeAt(index);
    if (_items.isEmpty) {
      _index = -1;
      _playing = false;
    } else if (index < _index) {
      _index = _index - 1; // 删的是当前曲之前
    } else if (index == _index) {
      _index = _index.clamp(0, _items.length - 1); // 删的是当前曲
    }
    _shuffleHistory.clear();
    notifyListeners();
    _persist();
  }

  /// 把队列里 [from] 索引的歌曲移动到 [to] 索引（长按拖动排序）。
  void moveItem(int from, int to) {
    if (from < 0 || from >= _items.length) return;
    if (to < 0 || to >= _items.length) return;
    if (from == to) return;
    final item = _items.removeAt(from);
    _items.insert(to, item);
    // 修正当前曲索引，保持「播放中的歌」不变。
    if (from == _index) {
      _index = to;
    } else if (from < _index && to >= _index) {
      _index = _index - 1;
    } else if (from > _index && to <= _index) {
      _index = _index + 1;
    }
    _shuffleHistory.clear();
    notifyListeners();
    _persist();
  }

  int _randomNext() {
    if (_items.length <= 1) return 0;
    final rng = math.Random();
    var pick = rng.nextInt(_items.length);
    // 避免与当前曲重复；若队列只有 1 首则必然重复（已在外面处理）。
    if (_items.length > 1 && pick == _index) {
      pick = (pick + 1) % _items.length;
    }
    // 简单去重：最近两首不重复（若队列极小可放宽）。
    if (_items.length > 2) {
      int guard = 0;
      while (guard < _items.length &&
          _shuffleHistory.contains(pick) &&
          _shuffleHistory.length >= _items.length - 1) {
        pick = (pick + 1) % _items.length;
        guard++;
      }
    }
    _shuffleHistory.add(pick);
    if (_shuffleHistory.length > _items.length) {
      _shuffleHistory.removeAt(0);
    }
    return pick;
  }

  /// 把一组待变速歌曲与当前队列做「切换」：
  /// - 全部已在队列 → 全部从队列移出；
  /// - 部分在队列   → 只补入还没在队列的那部分；
  /// - 都不在队列   → 全部加入队列。
  /// 返回 `(added, removed)` 数量，供界面向用户提示结果。
  (int, int) toggleAddRemove(Iterable<PlaylistItem> candidates) {
    final list = candidates.toList();
    if (list.isEmpty) return (0, 0);
    final presentPaths = _items.map((e) => e.filePath).toSet();
    var allPresent = true;
    final toAdd = <PlaylistItem>[];
    for (final c in list) {
      if (presentPaths.contains(c.filePath)) {
        // 已在队列，跳过。
      } else {
        allPresent = false;
        toAdd.add(c);
      }
    }

    if (allPresent) {
      // 全部已在队列 → 全部移出。
      final removePaths = list.map((e) => e.filePath).toSet();
      final currentPath = (_index >= 0 && _index < _items.length)
          ? _items[_index].filePath
          : null;
      _items.removeWhere((it) => removePaths.contains(it.filePath));
      if (_items.isEmpty) {
        _index = -1;
        _playing = false;
      } else if (currentPath != null) {
        // 尽量让当前曲保持原样：仍能找到就指向它，否则落到队首。
        final i = _items.indexWhere((it) => it.filePath == currentPath);
        _index = i < 0 ? 0 : i;
      } else {
        _index = _index.clamp(0, _items.length - 1);
      }
      _shuffleHistory.clear();
      _persist();
      notifyListeners();
      return (0, removePaths.length);
    }

    // 部分或都不在 → 把缺失的补齐加入队列末尾。
    _items.addAll(toAdd);
    _shuffleHistory.clear();
    _persist();
    notifyListeners();
    return (toAdd.length, 0);
  }

  void clear() {
    _items = [];
    _index = -1;
    _playing = false;
    _shuffleHistory.clear();
    notifyListeners();
    _persist();
  }
}
