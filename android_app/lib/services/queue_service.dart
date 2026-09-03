import 'dart:math' as math;

import 'package:flutter/foundation.dart';

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
  }

  void setShuffle(bool on) {
    if (_shuffle == on) return;
    _shuffle = on;
    _shuffleHistory.clear();
    notifyListeners();
  }

  /// 用选定歌曲建立播放列表并从第一首开始。
  void start(List<PlaylistItem> items) {
    _items = List.of(items);
    _index = 0;
    _playing = true;
    _shuffleHistory.clear();
    notifyListeners();
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
      return;
    }
    if (_shuffle) {
      _index = _randomNext();
    } else {
      _index = (_index + 1) % _items.length;
    }
    _playing = true;
    notifyListeners();
  }

  void prev() {
    if (_items.isEmpty) return;
    if (_items.length == 1) {
      _index = 0;
      _playing = true;
      notifyListeners();
      return;
    }
    if (_shuffle) {
      _index = _randomNext();
    } else {
      _index = (_index - 1 + _items.length) % _items.length;
    }
    _playing = true;
    notifyListeners();
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
        return;
      }
    }
    _playing = true;
    notifyListeners();
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

  void clear() {
    _items = [];
    _index = -1;
    _playing = false;
    _shuffleHistory.clear();
    notifyListeners();
  }
}
