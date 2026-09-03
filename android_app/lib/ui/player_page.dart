import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:just_audio/just_audio.dart' hide LoopMode;
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/song.dart';
import '../services/audio_player_service.dart';
import '../services/library_service.dart';
import '../services/bpm_display_controller.dart';
import '../services/metronome.dart';
import '../services/queue_service.dart';
import 'beat_ruler.dart';
import 'settings_page.dart';

/// 由一批打拍点计算 BPM。
/// 取相邻媒体时间差、过滤 ≤0.1s（双击/误触/seek 回退）、取中位后返回 60/中位。
/// **不四舍五入成整数**：中位间隔的 ~1ms 精度能让 BPM 可靠到 1~2 位小数，
/// 以便两位小数显示有意义（否则始终是 .00）。不足 2 个有效间隔返回 null。
double? computeTapBpm(List<double> taps) {
  final intervals = <double>[];
  for (var i = 1; i < taps.length; i++) {
    final d = taps[i] - taps[i - 1];
    if (d > 0.1) intervals.add(d);
  }
  if (intervals.length < 2) return null;
  intervals.sort();
  final median = intervals[intervals.length ~/ 2];
  return 60.0 / median;
}

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
  int _lastTapAtMs = 0; // 上次打拍的墙钟毫秒，用于 >2s 间隔时重置本批（对齐 Web）
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
  static const String _prefMusicVolume = 'runbpm.musicVolume';
  static const String _prefMetVolume = 'runbpm.metVolume';

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
      _musicVolume = (prefs.getDouble(_prefMusicVolume) ?? 1.0).clamp(0.0, 1.0);
      _metVolume = (prefs.getDouble(_prefMetVolume) ?? 0.5).clamp(0.0, 1.0);
    });
    // 把恢复的音量应用到播放器/节拍器。
    final player = context.read<AudioPlayerService>().player;
    player.setVolume(_musicVolume);
    context.read<Metronome>().setVolume(_metVolume);
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
                                    '${context.read<BpmDisplayController>().format(current.originalBpm)}→${context.read<BpmDisplayController>().format(current.targetBpm)} BPM'),
                                Text('变速 ×${speed.toStringAsFixed(2)}'),
                                Text('实际节奏 ≈ ${context.read<BpmDisplayController>().format(displayBpm)} BPM',
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
                          fontSize: 10,
                          fontFeatures: [FontFeature.tabularFigures()])),
                  Expanded(
                    child: SliderTheme(
                      data: SliderTheme.of(context).copyWith(
                        // 让已播放部分用主题主色清晰显示（默认 M3 在此背景上几乎不可见）
                        activeTrackColor: colorScheme.primary,
                        inactiveTrackColor: colorScheme.outlineVariant,
                        thumbColor: colorScheme.primary,
                        activeTickMarkColor: colorScheme.primary,
                        inactiveTickMarkColor: colorScheme.outlineVariant,
                      ),
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
                  ),
                  Padding(
                    padding: const EdgeInsets.only(left: 2),
                    child: Text(_fmt(duration),
                        style: TextStyle(
                            fontSize: 10,
                            color: colorScheme.onSurfaceVariant,
                            fontFeatures: const [
                              FontFeature.tabularFigures()
                            ])),
                  ),
                ],
              ),
              // 播放控制一行：左下=播放模式（点按循环切换），中间=传输控制，右下=播放列表。
              Row(
                children: [
                  // 左下：播放模式按钮（点一下循环：列表循环→单曲循环→随机→列表循环…）。
                  _buildModeButton(queue),
                  // 中间：上一首 / 播放暂停 / 下一首。
                  Expanded(
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        IconButton(
                          iconSize: 36,
                          icon: const Icon(Icons.skip_previous),
                          onPressed: () => _prev(context),
                        ),
                        IconButton(
                          iconSize: 56,
                          icon: Icon(queue.playing
                              ? Icons.pause_circle
                              : Icons.play_circle),
                          onPressed: () => _toggle(context),
                        ),
                        IconButton(
                          iconSize: 36,
                          icon: const Icon(Icons.skip_next),
                          onPressed: () => _next(context),
                        ),
                      ],
                    ),
                  ),
                  // 右下：播放列表入口。
                  IconButton(
                    tooltip: '播放列表',
                    iconSize: 28,
                    icon: const Icon(Icons.queue_music),
                    onPressed: () => _openPlaylist(context),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// 当前播放模式的图标与简短文字（左下角显示）。
  (IconData, String) _playModeInfo(QueueService q) {
    if (q.shuffle) return (Icons.shuffle, '随机');
    if (q.repeatingOne) return (Icons.repeat_one, '单曲循环');
    return (Icons.repeat, '列表循环');
  }

  /// 点一下循环切换播放模式：列表循环 → 单曲循环 → 随机 → 列表循环…
  void _cyclePlayMode(QueueService q) {
    if (q.shuffle) {
      q.setShuffle(false);
      q.setLoopMode(LoopMode.all);
    } else if (q.repeatingOne) {
      q.setShuffle(true);
    } else {
      q.setLoopMode(LoopMode.one);
    }
  }

  /// 左下角「播放模式」按钮：图标 + 文字，点按循环切换，方便直接看到当前模式。
  Widget _buildModeButton(QueueService q) {
    final colorScheme = Theme.of(context).colorScheme;
    final (icon, label) = _playModeInfo(q);
    final active = q.shuffle || q.repeatingOne;
    return Tooltip(
      message: '播放模式（点按切换）：$label',
      child: InkWell(
        borderRadius: BorderRadius.circular(8),
        onTap: () => _cyclePlayMode(q),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon,
                  size: 22, color: active ? colorScheme.primary : null),
              Text(label,
                  style: TextStyle(
                      fontSize: 9,
                      color: active ? colorScheme.primary : null)),
            ],
          ),
        ),
      ),
    );
  }

  /// 打开右下角「播放列表」底部弹层：封面/标题/BPM、当前高亮、点击跳歌、
  /// 删除、长按拖动排序、清空。
  void _openPlaylist(BuildContext context) {
    final q = context.read<QueueService>();
    if (q.items.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('播放列表为空')),
      );
      return;
    }
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => _PlaylistSheet(
        onPlayIndex: (index) {
          q.jumpTo(index);
          _loadCurrent(context, q);
        },
      ),
    );
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
                    ? '测出 ${context.read<BpmDisplayController>().format(_tapBpm)} BPM · 再点 ${8 - _tapMediaSec.length} 下'
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
        if (_tapBpm != null)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Row(
              children: [
                Text(
                  '测得节拍 ${context.read<BpmDisplayController>().format(_tapBpm)} BPM',
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
    final nowMs = DateTime.now().millisecondsSinceEpoch;
    final media = player.position.inMilliseconds / 1000.0;
    // 对齐 Web 版 TapBpm：与上次打拍间隔 >2s 就另起一批，
    // 避免跨暂停/停顿的旧间隔混入误判 BPM。
    if (_lastTapAtMs != 0 && nowMs - _lastTapAtMs > 2000) {
      _tapMediaSec.clear();
    }
    _lastTapAtMs = nowMs;
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
      final bpm = computeTapBpm(_tapMediaSec);
      if (bpm != null) {
        _tapBpm = bpm;
      }
      // 算完重置本批，以便下一次连续 8 次重算（对齐 Web 版 TapBpm）。
      _tapMediaSec.clear();
      _lastTapAtMs = 0;
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
                Text('${context.read<BpmDisplayController>().format(targetBpm)} BPM'),
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
                          _savePref(_prefMusicVolume, v);
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
                          _savePref(_prefMetVolume, v);
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

/// 播放列表底部弹层：显示当前队列，支持点击跳歌、长按拖动排序、删除单首、清空。
class _PlaylistSheet extends StatefulWidget {
  final void Function(int index) onPlayIndex;
  const _PlaylistSheet({required this.onPlayIndex});

  @override
  State<_PlaylistSheet> createState() => _PlaylistSheetState();
}

class _PlaylistSheetState extends State<_PlaylistSheet> {
  @override
  Widget build(BuildContext context) {
    final q = context.watch<QueueService>();
    final lib = context.watch<LibraryService>();
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    final items = q.items;

    // 头部：标题 + 当前模式 + 清空按钮。
    String modeLabel;
    IconData modeIcon;
    if (q.shuffle) {
      modeLabel = '随机';
      modeIcon = Icons.shuffle;
    } else if (q.repeatingOne) {
      modeLabel = '单曲循环';
      modeIcon = Icons.repeat_one;
    } else {
      modeLabel = '列表循环';
      modeIcon = Icons.repeat;
    }

    return SafeArea(
      child: SizedBox(
        height: MediaQuery.of(context).size.height * 0.7,
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 8, 4),
              child: Row(
                children: [
                  Text('播放列表（${items.length}）',
                      style: theme.textTheme.titleMedium),
                  const SizedBox(width: 8),
                  Icon(modeIcon, size: 18, color: colorScheme.primary),
                  const SizedBox(width: 4),
                  Text(modeLabel,
                      style: TextStyle(
                          fontSize: 12, color: colorScheme.onSurfaceVariant)),
                  const Spacer(),
                  TextButton.icon(
                    onPressed: items.isEmpty
                        ? null
                        : () {
                            q.clear();
                            // 清空后没有可播放内容，关闭弹层。
                            Navigator.of(context).pop();
                          },
                    icon: const Icon(Icons.delete_sweep, size: 18),
                    label: const Text('清空'),
                  ),
                ],
              ),
            ),
            const Divider(height: 1),
            Expanded(
              child: items.isEmpty
                  ? const Center(child: Text('播放列表为空'))
                  : ReorderableListView.builder(
                      buildDefaultDragHandles: false,
                      itemCount: items.length,
                      // 长按手柄即可拖动排序。
                      onReorder: (oldIndex, newIndex) {
                        setState(() {
                          if (newIndex > oldIndex) newIndex--;
                          q.moveItem(oldIndex, newIndex);
                        });
                      },
                      itemBuilder: (context, index) {
                        final item = items[index];
                        final isCurrent = index == q.index;
                        return ReorderableDragStartListener(
                          key: ValueKey('item-$index'),
                          index: index,
                          child: _PlaylistTile(
                            item: item,
                            isCurrent: isCurrent,
                            artworkPath: _artworkFor(lib, item.filePath),
                            onTap: isCurrent
                                ? null
                                : () {
                                    widget.onPlayIndex(index);
                                    Navigator.of(context).pop();
                                  },
                            onDelete: () => q.removeAt(index),
                          ),
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
    );
  }

  /// 从曲库按文件路径匹配封面（队列可能来自持久化，曲库未就绪时返回 null）。
  String? _artworkFor(LibraryService lib, String filePath) {
    for (final s in lib.songs) {
      if (s.filePath == filePath) return s.artworkPath;
    }
    return null;
  }
}

/// 播放列表单行：封面 + 标题 + 原/目标 BPM，当前播放高亮。
class _PlaylistTile extends StatelessWidget {
  final PlaylistItem item;
  final bool isCurrent;
  final String? artworkPath;
  final VoidCallback? onTap;
  final VoidCallback onDelete;
  const _PlaylistTile({
    required this.item,
    required this.isCurrent,
    required this.artworkPath,
    required this.onTap,
    required this.onDelete,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    return ListTile(
      onTap: onTap,
      selected: isCurrent,
      selectedTileColor: colorScheme.primary.withValues(alpha: 0.12),
      leading: ClipRRect(
        borderRadius: BorderRadius.circular(4),
        child: SizedBox(
          width: 42,
          height: 42,
          child: artworkPath != null
              ? Image.file(
                  File(artworkPath!),
                  fit: BoxFit.cover,
                  errorBuilder: (_, __, ___) =>
                      const Icon(Icons.music_note, color: Colors.grey),
                )
              : const Icon(Icons.music_note, color: Colors.grey),
        ),
      ),
      title: Text(
        item.filePath.split('/').last,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      subtitle: Text(
        '${context.read<BpmDisplayController>().format(item.originalBpm)}→'
        '${context.read<BpmDisplayController>().format(item.targetBpm)} BPM',
      ),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (isCurrent)
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: Icon(Icons.graphic_eq,
                  size: 20, color: colorScheme.primary),
            ),
          // 拖动排序手柄（长按整行或此图标即可拖动）。
          Icon(Icons.drag_handle, color: colorScheme.onSurfaceVariant),
          IconButton(
            tooltip: '从播放列表移除',
            icon: const Icon(Icons.close, size: 20),
            onPressed: onDelete,
          ),
        ],
      ),
      dense: true,
    );
  }
}
