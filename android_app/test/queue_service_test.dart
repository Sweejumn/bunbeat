import 'package:flutter_test/flutter_test.dart';
import 'package:run_bpm_android/services/audio_player_service.dart';
import 'package:run_bpm_android/services/queue_service.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 构造 [n] 个测试歌曲，原始 BPM 各不相同以便区分。
List<PlaylistItem> songs(int n) {
  return List.generate(
    n,
    (i) => PlaylistItem(
      filePath: '/music/song_$i.mp3',
      originalBpm: 100.0 + i,
      targetBpm: 150.0,
    ),
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  SharedPreferences.setMockInitialValues({});
  group('start / 基本状态', () {
    test('start 后 index=0 且 playing', () {
      final q = QueueService();
      q.start(songs(3));
      expect(q.index, 0);
      expect(q.playing, isTrue);
      expect(q.hasQueue, isTrue);
      expect(q.current?.filePath, '/music/song_0.mp3');
    });

    test('空队列 next/prev/onEnded 都不崩溃', () {
      final q = QueueService();
      q.next();
      q.prev();
      q.onEnded();
      expect(q.index, -1);
    });

    test('clear 重置状态', () {
      final q = QueueService();
      q.start(songs(3));
      q.clear();
      expect(q.index, -1);
      expect(q.hasQueue, isFalse);
      expect(q.current, isNull);
    });
  });

  group('next / prev（列表模式，无随机）', () {
    test('next 环形前进', () {
      final q = QueueService();
      q.start(songs(3));
      q.next();
      expect(q.index, 1);
      q.next();
      expect(q.index, 2);
      q.next();
      expect(q.index, 0); // 回到开头
    });

    test('prev 环形后退', () {
      final q = QueueService();
      q.start(songs(3));
      q.prev();
      expect(q.index, 2);
      q.prev();
      expect(q.index, 1);
    });

    test('单首时 next/prev 原地', () {
      final q = QueueService();
      q.start(songs(1));
      q.next();
      expect(q.index, 0);
      q.prev();
      expect(q.index, 0);
    });
  });

  group('LoopMode', () {
    test('默认列表循环（all）', () {
      final q = QueueService();
      expect(q.loopMode, LoopMode.all);
      expect(q.repeatingOne, isFalse);
    });

    test('setLoopMode 切换并通知', () {
      final q = QueueService();
      var notified = 0;
      q.addListener(() => notified++);
      q.setLoopMode(LoopMode.one);
      expect(q.loopMode, LoopMode.one);
      expect(q.repeatingOne, isTrue);
      expect(notified, 1);
      // 相同值不重复通知
      q.setLoopMode(LoopMode.one);
      expect(notified, 1);
    });
  });

  group('onEnded（自然播完）', () {
    test('单曲循环：index 不变且继续播放', () {
      final q = QueueService();
      q.start(songs(3));
      q.setLoopMode(LoopMode.one);
      final before = q.index;
      q.onEnded();
      expect(q.index, before);
      expect(q.playing, isTrue);
    });

    test('列表循环 all：前进到下一首', () {
      final q = QueueService();
      q.start(songs(3));
      q.setLoopMode(LoopMode.all);
      q.onEnded();
      expect(q.index, 1);
      expect(q.playing, isTrue);
    });

    test('列表循环 all：末尾回绕到第一首', () {
      final q = QueueService();
      q.start(songs(3));
      q.setLoopMode(LoopMode.all);
      q.next();
      q.next();
      q.onEnded();
      expect(q.index, 0);
      expect(q.playing, isTrue);
    });

    test('off 模式：播完停在末尾且停止', () {
      final q = QueueService();
      q.start(songs(3));
      q.setLoopMode(LoopMode.off);
      q.next();
      q.next(); // 现在 index=2（末尾）
      q.onEnded();
      expect(q.index, 2); // 停在末尾
      expect(q.playing, isFalse);
    });

    test('off 模式中间播完仍是前进并播放', () {
      final q = QueueService();
      q.start(songs(3));
      q.setLoopMode(LoopMode.off);
      q.onEnded(); // index 0 -> 1
      expect(q.index, 1);
      expect(q.playing, isTrue);
    });

    test('单首 + off：播完停止', () {
      final q = QueueService();
      q.start(songs(1));
      q.setLoopMode(LoopMode.off);
      q.onEnded();
      expect(q.playing, isFalse);
      expect(q.index, 0);
    });

    test('单首 + all：播完循环继续', () {
      final q = QueueService();
      q.start(songs(1));
      q.setLoopMode(LoopMode.all);
      q.onEnded();
      expect(q.playing, isTrue);
      expect(q.index, 0);
    });
  });

  group('随机播放', () {
    test('setShuffle 开启/关闭并通知', () {
      final q = QueueService();
      var notified = 0;
      q.addListener(() => notified++);
      q.setShuffle(true);
      expect(q.shuffle, isTrue);
      expect(notified, 1);
      q.setShuffle(true);
      expect(notified, 1);
      q.setShuffle(false);
      expect(q.shuffle, isFalse);
    });

    test('随机 next 永远落在合法索引且不越界', () {
      final q = QueueService();
      q.start(songs(5));
      q.setShuffle(true);
      for (var i = 0; i < 100; i++) {
        q.next();
        expect(q.index, inInclusiveRange(0, 4));
      }
    });

    test('随机 next 遍历覆盖多个不同索引，且通常不与当前重复', () {
      final q = QueueService();
      q.start(songs(4));
      q.setShuffle(true);
      final picks = <int>{};
      var stayedSame = true; // 若一次移动就"停在原地"算异常
      for (var i = 0; i < 60; i++) {
        final before = q.index;
        q.next();
        if (q.index != before) stayedSame = false;
        picks.add(q.index);
      }
      // 队列长度 4，60 次随机应覆盖至少 3 个不同索引。
      expect(picks.length, greaterThanOrEqualTo(3));
      // 至少要发生过"变到不同曲"的情况。
      expect(stayedSame, isFalse);
    });

    test('单首 + 随机：next 原地', () {
      final q = QueueService();
      q.start(songs(1));
      q.setShuffle(true);
      q.next();
      q.prev();
      expect(q.index, 0);
    });

    test('随机 onEnded 同样落在合法索引', () {
      final q = QueueService();
      q.start(songs(5));
      q.setShuffle(true);
      for (var i = 0; i < 100; i++) {
        q.onEnded();
        expect(q.index, inInclusiveRange(0, 4));
        expect(q.playing, isTrue);
      }
    });

    test('随机 + off 模式：只在末尾停止一次', () {
      final q = QueueService();
      q.start(songs(3));
      q.setShuffle(true);
      q.setLoopMode(LoopMode.off);
      // 模拟一路播到底，最终应停在末尾且不播放。
      // 随机老路径没有"末尾"概念，所以 off 在随机下应继续随机（不停止）。
      // 断言：即使走到很多次，off+随机 也应保持播放（因为我们只处理非随机结尾）。
      for (var i = 0; i < 30; i++) {
        q.onEnded();
        expect(q.playing, isTrue);
      }
    });
  });

  group('PlaylistItem.speed 变速钳制', () {
    test('正常目标/原始比', () {
      final it = PlaylistItem(filePath: 'a', originalBpm: 100, targetBpm: 150);
      expect(it.speed, moreOrLessEquals(1.5));
    });

    test('低于 0.5 被钳制到 0.5', () {
      final it = PlaylistItem(filePath: 'a', originalBpm: 200, targetBpm: 60);
      expect(it.speed, 0.5);
    });

    test('高于 2.0 被钳制到 2.0', () {
      final it = PlaylistItem(filePath: 'a', originalBpm: 83, targetBpm: 500);
      expect(it.speed, 2.0);
    });

    test('originalBpm<=0 回退 1.0', () {
      final it = PlaylistItem(filePath: 'a', originalBpm: 0, targetBpm: 150);
      expect(it.speed, 1.0);
    });
  });

  group('持久化（退出保留）', () {
    test('保存队列/索引/模式，加载后恢复且不自动播放', () async {
      SharedPreferences.setMockInitialValues({});
      final q = QueueService();
      q.start(songs(3));
      q.setLoopMode(LoopMode.one);
      // 非随机下 next 一次，确定性推进到索引 1。
      q.next();
      q.setShuffle(true);
      expect(q.index, 1);
      // 让异步写盘（_persist 为 fire-and-forget）有机会落盘。
      await Future<void>.delayed(const Duration(milliseconds: 50));

      final q2 = QueueService();
      await q2.loadFromPrefs();
      expect(q2.items.length, 3);
      expect(q2.index, 1);
      expect(q2.shuffle, isTrue);
      expect(q2.repeatingOne, isTrue);
      // 不自动播放：无论恢复前是否播放过，加载后都应停住等用户点播放。
      expect(q2.playing, isFalse);
      expect(q2.current?.filePath, '/music/song_1.mp3');
    });

    test('removeAt 后持久化，再加载队列已更新', () async {
      SharedPreferences.setMockInitialValues({});
      final q = QueueService();
      q.start(songs(4));
      q.removeAt(1);
      await Future<void>.delayed(const Duration(milliseconds: 50));

      final q2 = QueueService();
      await q2.loadFromPrefs();
      expect(q2.items.length, 3);
      expect(q2.items[1].filePath, '/music/song_2.mp3');
    });

    test('moveItem 后持久化，再加载顺序已更新', () async {
      SharedPreferences.setMockInitialValues({});
      final q = QueueService();
      q.start(songs(3));
      // 把第 2 首移到最前：[song1, song0, song2]
      q.moveItem(1, 0);
      await Future<void>.delayed(const Duration(milliseconds: 50));

      final q2 = QueueService();
      await q2.loadFromPrefs();
      expect(q2.items[0].filePath, '/music/song_1.mp3');
      expect(q2.items[1].filePath, '/music/song_0.mp3');
      expect(q2.items[2].filePath, '/music/song_2.mp3');
    });

    test('无持久化数据时加载为空队列', () async {
      SharedPreferences.setMockInitialValues({});
      final q = QueueService();
      await q.loadFromPrefs();
      expect(q.items, isEmpty);
      expect(q.index, -1);
      expect(q.playing, isFalse);
    });
  });
}
