import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:just_audio/just_audio.dart' hide LoopMode;
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/song.dart';
import '../services/audio_player_service.dart';
import '../services/library_service.dart';
import '../services/metronome.dart';
import '../services/queue_service.dart';
import 'beat_ruler.dart';
import 'settings_page.dart';

class PlayerPage extends StatefulWidget {
  const PlayerPage({super.key});

  @override
  State<PlayerPage> createState() => _PlayerPageState();
}

class _PlayerPageState extends State<PlayerPage> {
  double _metVolume = 0.5;
  double _musicVolume = 1.0;
  // 下列设置持久化到 SharedPreferences（对应 Web 的 localStorage）：
  //   runbpm.metronomeOn / runbpm.phaseNudge / runbpm.beatMode / runbpm.tapSound
  late BeatMode _activeBeatMode; // 缓存默认 grid；持久化
  late double _phaseNudgePct; // 缓存默认 0；持久化（-50..+50）
  late bool _metEnabled; // 缓存默认 true（对齐 Web 默认开启）；持久化
  late bool _tapSoundOn; // 缓存默认 false；持久化（对齐 Web runbpm.tapSound 默认关）
  // 打拍校准：每次点击记录墙钟时间与媒体位置；>=8 次后取中位间隔算 BPM。
  final List<double> _tapMediaSec = [];
  final List<double> _tapMarks = []; // 最近 20 个打拍位置（媒体时间秒），供标尺 amber
  double? _tapBpm;
  bool _tapSetFirst = false; // 设首拍：每次打拍都用媒体位置对齐首拍
  StreamSubscription<Duration>? _posSub;
  StreamSubscription<PlayerState>? _stateSub;
  StreamSubscription<Duration?>? _durSub;
  StreamSubscription<ProcessingState>? _procSub;
  bool _handlingEnded = false;

  static const String _prefMetOn = 'runbpm.metronomeOn';
  static const String _prefPhaseNudge = 'runbpm.phaseNudge';
  static const String _prefBeatMode = 'runbpm.beatMode';
  static const String _prefTapSound = 'runbpm.tapSound';

  @override
  void initState() {
    super.initState();
    // 先用默认值初始化，异步读盘后再补用持久化值（读盘很快，UI 一帧内即到位）。
    _activeBeatMode = BeatMode.grid;
    _phaseNudgePct = 0.0;
    _metEnabled = true;
    _tapSoundOn = false;
    _loadPrefs();
    final player = context.read<AudioPlayerService>().player;
    _posSub = player.positionStream.listen((_) {
      if (mounted) setState(() {});
    });
    _stateSub = player.playerStateStream.listen((ps) {
      if (!mounted) return;
      context.read<QueueService>().setPlaying(ps.playing);
      if (ps.playing) {
        _onPlaybackStart(player);
      } else {
        // 外部暂停（控制中心/耳机/来电打断等）也要停节拍器：
        // 之前只在 _toggle 里停，外部暂停时节拍器仍继续响。
        context.read<Metronome>().setEnabled(false);
      }
    });
    _durSub = player.durationStream.listen((_) {
      if (mounted) setState(() {});
    });
    // 一首播完（processingState == completed）时按循环/随机模式处理。
    _procSub = player.processingStateStream.listen((ps) {
      if (!mounted) return;
      if (ps == ProcessingState.completed) {
        if (_handlingEnded) return;
        _handlingEnded = true;
        _onEnded(player);
      }
    });
  }

  /// 读取持久化设置（对应 Web 的 localStorage 记忆）。
  Future<void> _loadPrefs() async {
    final prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    final bpm = prefs.getDouble(_prefPhaseNudge) ?? 0.0;
    final modeStr = prefs.getString(_prefBeatMode) ?? BeatMode.grid.name;
    BeatMode? mode;
    for (final m in BeatMode.values) {
      if (m.name == modeStr) {
        mode = m;
        break;
      }
    }
    setState(() {
      _phaseNudgePct = bpm.clamp(-50.0, 50.0);
      if (mode != null) _activeBeatMode = mode;
      _metEnabled = prefs.getBool(_prefMetOn) ?? true;
      _tapSoundOn = prefs.getBool(_prefTapSound) ?? false;
    });
  }

  Future<void> _savePref(String key, Object value) async {
    final prefs = await SharedPreferences.getInstance();
    switch (value) {
      case final bool b:
        await prefs.setBool(key, b);
      case final double d:
        await prefs.setDouble(key, d);
      case final String s:
        await prefs.setString(key, s);
      case final int i:
        await prefs.setInt(key, i);
      default:
        break;
    }
  }

  /// 播放开始时：若节拍器设置开启则自动开启节拍器并锚定（对齐 Web 默认开启）。
  Future<void> _onPlaybackStart(AudioPlayer player) async {
    final met = context.read<Metronome>();
    final q = context.read<QueueService>();
    if (_metEnabled && !met.isEnabled) {
      await met.ensureInitialized();
      met.setBpm(q.current?.targetBpm ?? 120);
      met.setEnabled(true);
    }
    _reAnchor(player);
  }

  @override
  void dispose() {
    _posSub?.cancel();
    _stateSub?.cancel();
    _durSub?.cancel();
    _procSub?.cancel();
    super.dispose();
  }

  /// 当前曲自然播放结束：按循环/随机模式决定下一首与播放状态。
  Future<void> _onEnded(AudioPlayer player) async {
    final q = context.read<QueueService>();
    final svc = context.read<AudioPlayerService>();
    final before = q.index;
    q.onEnded();
    final after = q.index;
    if (q.current == null) return;
    if (q.playing) {
      if (before == after && q.repeatingOne) {
        // 单曲循环：重播同一首
        await svc.player.seek(Duration.zero);
        await svc.player.play();
        _reAnchor(svc.player);
      } else {
        await _loadCurrent(context, q);
      }
    }
    _handlingEnded = false;
  }

  void _reAnchor(AudioPlayer player) {
    final met = context.read<Metronome>();
    final lib = context.read<LibraryService>();
    if (!met.isEnabled) return;
    final queue = context.read<QueueService>();
    final current = queue.current;
    if (current == null) return;
    met.setBpm(current.targetBpm);
    met.setPhaseOffset(_phaseNudgeSeconds(current.targetBpm));
    // 找到当前歌曲，按所选节拍模式取对应拍点；无则回退到 grid / 等距网格。
    Song? song;
    for (final s in lib.songs) {
      if (s.filePath == current.filePath) {
        song = s;
        break;
      }
    }
    List<double>? beatTimes;
    double? beatOffset;
    if (song != null) {
      beatTimes =
          song.beatMaps?[_activeBeatMode.name] ?? song.beatTimes;
      beatOffset = song.beatOffset;
    }
    if (beatTimes != null && beatTimes.isNotEmpty) {
      met.setBeatMap(beatTimes);
    } else {
      met.setBeatMap(null);
      met.setPhase(beatOffset);
    }
    met.reAnchor(player.position);
  }

  /// 偏差滑杆（±50% 拍距）换算成秒位移。
  double _phaseNudgeSeconds(double targetBpm) => _phaseNudgePct / 100.0 * (60.0 / targetBpm);

  /// 当前歌曲的激活拍点（已叠加偏差位移），供标尺显示；无则回退 grid。
  List<double> _currentBeatList(Song? song, double targetBpm) {
    final map = song?.beatMaps?[_activeBeatMode.name] ?? song?.beatTimes;
    final offset = _phaseNudgeSeconds(targetBpm);
    if (map == null || map.isEmpty) return const [];
    return [for (final t in map) t + offset];
  }

  @override
  Widget build(BuildContext context) {
    final queue = context.watch<QueueService>();
    final player = context.read<AudioPlayerService>().player;
    final met = context.watch<Metronome>();
    final current = queue.current;

    if (!queue.hasQueue || current == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('播放')),
        body: const Center(
          child: Text('到「推荐」页选择运动模式并开始播放'),
        ),
      );
    }

    final position = player.position;
    final duration = player.duration ?? Duration.zero;
    final speed = current.speed;
    final displayBpm = current.originalBpm * speed;

    // 从曲库找到当前曲目以读取内嵌封面 + 拍点/相位可靠性。
    final lib = context.watch<LibraryService>();
    String? artwork;
    Song? song;
    for (final s in lib.songs) {
      if (s.filePath == current.filePath) {
        artwork = s.artworkPath;
        song = s;
        break;
      }
    }

    final beatList = _currentBeatList(song, current.targetBpm);

    return Scaffold(
      appBar: AppBar(
        title: const Text('播放'),
        actions: [
          IconButton(
            icon: const Icon(Icons.settings_outlined),
            tooltip: '设置',
            onPressed: () => Navigator.push(
              context,
              MaterialPageRoute(builder: (_) => const SettingsPage()),
            ),
          ),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Row(
                        children: [
                          // 歌曲封面（无封面时显示占位图标）
                          ClipRRect(
                            borderRadius: BorderRadius.circular(8),
                            child: SizedBox(
                              width: 96,
                              height: 96,
                              child: artwork != null
                                  ? Image.file(
                                      File(artwork),
                                      fit: BoxFit.cover,
                                      errorBuilder: (_, __, ___) =>
                                          const _ArtworkPlaceholder(),
                                    )
                                  : const _ArtworkPlaceholder(),
                            ),
                          ),
                          const SizedBox(width: 16),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(current.filePath.split('/').last,
                                    maxLines: 2,
                                    overflow: TextOverflow.ellipsis,
                                    style:
                                        Theme.of(context).textTheme.titleLarge),
                                const SizedBox(height: 8),
                                Text(
                                    '${current.originalBpm.round()}→${current.targetBpm.round()} BPM'),
                                Text('变速 ×${speed.toStringAsFixed(2)}'),
                                Text('实际节奏 ≈ ${displayBpm.round()} BPM',
                                    style: Theme.of(context).textTheme.bodySmall),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 8),
                  _buildBeatRuler(beatList, player, _tapMarks),
                  const SizedBox(height: 8),
                  _buildBeatControls(context, met, player, current.targetBpm),
                  const SizedBox(height: 12),
                  _buildMetronomeControls(context, met, player, current.targetBpm),
                ],
              ),
            ),
          ),
          // 底部固定控制条：进度 + 随机/单曲循环 + 上一首/播放暂停/下一首，
          // 常驻可见，不必翻到列表底部。
          _buildPlaybackBar(context, queue, player, position, duration),
        ],
      ),
    );
  }

  /// 底部固定控制条：进度滑杆 + 随机/单曲循环开关 + 播放控制，全部常驻可见。
  /// 随机与单曲循环都是「点一下切换」的独立开关（不做互斥循环），且与播放键同处一行，
  /// 节省垂直空间；进度时间字号调小。
  Widget _buildPlaybackBar(
    BuildContext context,
    QueueService queue,
    AudioPlayer player,
    Duration position,
    Duration duration,
  ) {
    final colorScheme = Theme.of(context).colorScheme;
    return Material(
      color: colorScheme.surfaceContainerHighest,
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(8, 6, 8, 4),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // 进度滑杆 + 时间（小字号）
              Row(
                children: [
                  Text(_fmt(position),
                      style: const TextStyle(
                          fontSize: 12, fontFeatures: [FontFeature.tabularFigures()])),
                  Expanded(
                    child: Slider(
                      value: duration.inMilliseconds > 0
                          ? (position.inMilliseconds / duration.inMilliseconds)
                              .clamp(0.0, 1.0)
                          : 0.0,
                      onChanged: (v) {
                        if (duration.inMilliseconds > 0) {
                          player.seek(Duration(
                              milliseconds:
                                  (v * duration.inMilliseconds).round()));
                        }
                      },
                    ),
                  ),
                  Text(_fmt(duration),
                      style: const TextStyle(
                          fontSize: 12, fontFeatures: [FontFeature.tabularFigures()])),
                ],
              ),
              // 播放控制一行：随机 / 上一首 / 播放暂停 / 下一首 / 单曲循环
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  IconButton(
                    tooltip: queue.shuffle ? '随机播放：开' : '随机播放：关',
                    icon: Icon(
                      Icons.shuffle,
                      color: queue.shuffle ? colorScheme.primary : null,
                    ),
                    onPressed: () =>
                        context.read<QueueService>().setShuffle(!queue.shuffle),
                  ),
                  IconButton(
                    iconSize: 36,
                    icon: const Icon(Icons.skip_previous),
                    onPressed: () => _prev(context),
                  ),
                  IconButton(
                    iconSize: 56,
                    icon: Icon(
                        queue.playing ? Icons.pause_circle : Icons.play_circle),
                    onPressed: () => _toggle(context),
                  ),
                  IconButton(
                    iconSize: 36,
                    icon: const Icon(Icons.skip_next),
                    onPressed: () => _next(context),
                  ),
                  IconButton(
                    tooltip: _loopLabel(queue.loopMode),
                    icon: Icon(
                      queue.repeatingOne ? Icons.repeat_one : Icons.repeat,
                      color: queue.repeatingOne ? colorScheme.primary : null,
                    ),
                    // 单曲循环：点一下切换开启/关闭（不做 off/all/one 循环）。
                    onPressed: () {
                      final q = context.read<QueueService>();
                      q.setLoopMode(q.repeatingOne ? LoopMode.all : LoopMode.one);
                    },
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  String _loopLabel(LoopMode m) {
    return switch (m) {
      LoopMode.off => '列表循环：关闭（播完即停）',
      LoopMode.all => '列表循环：开启',
      LoopMode.one => '单曲循环：开启',
    };
  }

  String _fmt(Duration d) {
    final m = d.inMinutes.toString().padLeft(2, '0');
    final s = (d.inSeconds % 60).toString().padLeft(2, '0');
    return '$m:$s';
  }

  Future<void> _toggle(BuildContext context) async {
    final q = context.read<QueueService>();
    final svc = context.read<AudioPlayerService>();
    final met = context.read<Metronome>();
    final cur = q.current;
    if (cur == null) return;
    if (q.playing) {
      await svc.player.pause();
      q.setPlaying(false);
      met.setEnabled(false);
    } else {
      _reAnchor(svc.player);
      await svc.player.play();
    }
  }

  Future<void> _next(BuildContext context) async {
    final q = context.read<QueueService>();
    q.next();
    await _loadCurrent(context, q);
  }

  Future<void> _prev(BuildContext context) async {
    final q = context.read<QueueService>();
    q.prev();
    await _loadCurrent(context, q);
  }

  /// 载入当前曲并播放；若载入失败（坏文件/无法读取）自动跳到下一首，
  /// 最多连续跳过 [_maxFailures] 次，避免坏文件卡死整条推荐列表。
  Future<void> _loadCurrent(BuildContext context, QueueService q) async {
    final svc = context.read<AudioPlayerService>();
    var cur = q.current;
    if (cur == null) return;
    // 切歌时清空打拍校准与标尺标记（对应 Web 切换曲目时清空 tapMarks）。
    _tapMediaSec.clear();
    _tapMarks.clear();
    _tapBpm = null;
    var attempts = 0;
    while (cur != null && attempts < _maxFailures) {
      if (await svc.tryPlay(cur)) {
        _reAnchor(svc.player);
        return;
      }
      attempts++;
      // 载入失败：按循环/随机规则前进到下一首（满 1 首队列时 onEnded 会原地）。
      q.next();
      if (q.current == cur) {
        break; // 只有一首且载入失败：不无限循环
      }
      cur = q.current;
    }
  }

  static const int _maxFailures = 3 + 1;

  Widget _buildBeatRuler(List<double> beats, AudioPlayer player, List<double> tapMarks) {
    if (beats.isEmpty) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 8),
        child: Text('（该曲无拍点信息，标尺暂不可用）',
            textAlign: TextAlign.center,
            style: TextStyle(color: Colors.grey, fontSize: 12)),
      );
    }
    return BeatRuler(
      beats: beats,
      getPositionSeconds: () => player.position.inMilliseconds / 1000.0,
      tapMarks: tapMarks,
    );
  }

  /// 节拍模式 + 偏差微调 + 打拍校准（对应 Web 的三档节拍、偏差、tap 校准）。
  Widget _buildBeatControls(
    BuildContext context,
    Metronome met,
    AudioPlayer player,
    double targetBpm,
  ) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('节拍模式', style: TextStyle(fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final m in kBeatModes)
                  ChoiceChip(
                    label: Text(m.label),
                    tooltip: m.desc,
                    selected: _activeBeatMode == m.id,
                    onSelected: (_) {
                      setState(() => _activeBeatMode = m.id);
                      _savePref(_prefBeatMode, m.id.name);
                      if (met.isEnabled) _reAnchor(player);
                    },
                  ),
              ],
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                const Text('偏差', style: TextStyle(fontWeight: FontWeight.bold)),
                const SizedBox(width: 8),
                Expanded(
                  child: Slider(
                    value: _phaseNudgePct,
                    min: -50,
                    max: 50,
                    divisions: 20,
                    label: '${_phaseNudgePct.round()}%',
                    onChanged: (v) {
                      setState(() => _phaseNudgePct = v);
                      _savePref(_prefPhaseNudge, v);
                      if (met.isEnabled) {
                        met.setPhaseOffset(_phaseNudgeSeconds(targetBpm));
                        met.reAnchor(player.position);
                      }
                    },
                  ),
                ),
                const SizedBox(width: 8),
                Text('${_phaseNudgePct.round()}%'),
                const SizedBox(width: 4),
                // 归零按钮（对齐 Web：始终显示，非零时才可点，为零时灰色禁用）。
                IconButton(
                  tooltip: '把偏差归零',
                  onPressed: _phaseNudgePct == 0
                      ? null
                      : () {
                          setState(() => _phaseNudgePct = 0);
                          _savePref(_prefPhaseNudge, 0);
                          if (met.isEnabled) {
                            met.setPhaseOffset(_phaseNudgeSeconds(targetBpm));
                            met.reAnchor(player.position);
                          }
                        },
                  icon: Icon(
                    Icons.replay,
                    color: _phaseNudgePct == 0
                        ? Theme.of(context).disabledColor
                        : Theme.of(context).colorScheme.primary,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 4),
            _buildTapCalibration(context, met, player, targetBpm),
          ],
        ),
      ),
    );
  }

  Widget _buildTapCalibration(
    BuildContext context,
    Metronome met,
    AudioPlayer player,
    double targetBpm,
  ) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            const Text('打拍校准', style: TextStyle(fontWeight: FontWeight.bold)),
            const SizedBox(width: 8),
            const Text('按节拍点 8 下即可测出 BPM',
                style: TextStyle(color: Colors.grey, fontSize: 12)),
          ],
        ),
        const SizedBox(height: 8),
        Row(
          children: [
            Expanded(
              child: FilledButton.tonalIcon(
                onPressed: () => _onTap(met, player, targetBpm),
                icon: const Icon(Icons.touch_app),
                label: Text(_tapBpm != null
                    ? '测出 ${_tapBpm!.round()} BPM · 再点 ${8 - _tapMediaSec.length} 下'
                    : '点击 ${8 - _tapMediaSec.length} 下'),
              ),
            ),
            const SizedBox(width: 8),
            Tooltip(
              message:
                  '开启后每次打拍都会把首拍（第一下）对齐到点击位置，适合首拍不在 0 秒的歌曲',
              child: FilterChip(
                label: const Text('设首拍'),
                selected: _tapSetFirst,
                onSelected: (v) => setState(() => _tapSetFirst = v),
              ),
            ),
            const SizedBox(width: 8),
            // 🔊 打拍音效开关（对应 Web，默认关、持久化）
            FilterChip(
              avatar: const Icon(Icons.volume_up, size: 16),
              label: Text(_tapSoundOn ? '音效开' : '音效关'),
              selected: _tapSoundOn,
              onSelected: (v) {
                setState(() => _tapSoundOn = v);
                _savePref(_prefTapSound, v);
              },
            ),
          ],
        ),
        if (_tapBpm != null && _tapMediaSec.length >= 8)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Row(
              children: [
                Text(
                  '测得节拍 ${_tapBpm!.round()} BPM',
                  style: const TextStyle(color: Colors.greenAccent),
                ),
                const Spacer(),
                TextButton(
                  onPressed: () {
                    final t = _tapBpm ?? targetBpm;
                    met.setBpm(t);
                    if (met.isEnabled) {
                      met.setPhaseOffset(_phaseNudgeSeconds(t));
                      met.reAnchor(player.position);
                    }
                    setState(() {});
                  },
                  child: const Text('应用到节拍器'),
                ),
                TextButton(
                  onPressed: () {
                    setState(() {
                      _tapMediaSec.clear();
                      _tapMarks.clear();
                      _tapBpm = null;
                    });
                  },
                  child: const Text('重置'),
                ),
              ],
            ),
          ),
      ],
    );
  }

  void _onTap(Metronome met, AudioPlayer player, double targetBpm) {
    // 打拍音效开关（对应 Web 🔊 音效，默认关、持久化）
    if (_tapSoundOn) {
      met.playTapClick();
    }
    final media = player.position.inMilliseconds / 1000.0;
    _tapMediaSec.add(media);
    // 标尺上显示最近 20 个打拍标记（琥珀色）
    _tapMarks.add(media);
    if (_tapMarks.length > 20) {
      _tapMarks.removeRange(0, _tapMarks.length - 20);
    }
    if (_tapSetFirst && met.isEnabled) {
      met.setPhase(media);
      met.setPhaseOffset(_phaseNudgeSeconds(targetBpm));
      met.reAnchor(player.position);
    }
    if (_tapMediaSec.length >= 8) {
      // 对齐 Web 版 TapBpm：用播放媒体时间差（单调、不随系统调时回拨），
      // 过滤过小（≤0.1s，双击/误触/seek 回退）间隔，取中位，
      // 结果四舍五入为整数 BPM，杜绝负值/巨大等异常。
      final intervals = <double>[];
      for (var i = 1; i < _tapMediaSec.length; i++) {
        final d = _tapMediaSec[i] - _tapMediaSec[i - 1];
        if (d > 0.1) intervals.add(d);
      }
      if (intervals.length >= 2) {
        intervals.sort();
        final median = intervals[intervals.length ~/ 2];
        _tapBpm = (60.0 / median).roundToDouble();
      }
    }
    setState(() {});
  }

  Widget _buildMetronomeControls(
    BuildContext context,
    Metronome met,
    AudioPlayer player,
    double targetBpm,
  ) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          children: [
            Row(
              children: [
                const Text('节拍器'),
                Switch(
                  value: _metEnabled,
                  onChanged: (v) async {
                    if (v) {
                      // 记录用户意图（默认开启，对齐 Web），持久化后再建池启用。
                      _metEnabled = true;
                      _savePref(_prefMetOn, true);
                      // 先确保节拍器声音池就绪（写 WAV + 预建播放器池），
                      // 再启用定时器，最后才锚定拍点/相位 ——
                      // 顺序很关键：若在 setEnabled 前 _reAnchor，
                      // 因 isEnabled 仍为 false 会直接 return，
                      // 导致节拍器被启用却没有拍点，彻底无声。
                      await met.ensureInitialized();
                      met.setBpm(targetBpm);
                      met.setEnabled(true);
                      _reAnchor(player);
                    } else {
                      _metEnabled = false;
                      _savePref(_prefMetOn, false);
                      met.setEnabled(false);
                    }
                    setState(() {});
                  },
                ),
                const Spacer(),
                Text('${targetBpm.round()} BPM'),
              ],
            ),
            // 音乐 + 拍子音量（对齐 Web：两个滑杆同一行各占一半）
            Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text('🔊 音乐',
                          style: TextStyle(fontSize: 12)),
                      Slider(
                        value: _musicVolume,
                        onChanged: (v) {
                          setState(() => _musicVolume = v);
                          player.setVolume(v);
                        },
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text('🥁 拍子',
                          style: TextStyle(fontSize: 12)),
                      Slider(
                        value: _metVolume,
                        onChanged: (v) {
                          setState(() => _metVolume = v);
                          met.setVolume(v);
                        },
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// 无封面时的占位图标。
class _ArtworkPlaceholder extends StatelessWidget {
  const _ArtworkPlaceholder();

  @override
  Widget build(BuildContext context) {
    return Container(
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      child: const Center(
        child: Icon(Icons.music_note, size: 40, color: Colors.grey),
      ),
    );
  }
}
