import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:just_audio/just_audio.dart' hide LoopMode;
import 'package:provider/provider.dart';

import '../models/song.dart';
import '../services/audio_player_service.dart';
import '../services/library_service.dart';
import '../services/metronome.dart';
import '../services/queue_service.dart';
import 'beat_ruler.dart';

class PlayerPage extends StatefulWidget {
  const PlayerPage({super.key});

  @override
  State<PlayerPage> createState() => _PlayerPageState();
}

class _PlayerPageState extends State<PlayerPage> {
  double _metVolume = 0.5;
  BeatMode _activeBeatMode = BeatMode.grid;
  double _phaseNudgePct = 0.0; // 偏差 ±%（半拍），-50..+50
  // 打拍校准：每次点击记录墙钟时间与媒体位置；>=8 次后取中位间隔算 BPM。
  final List<double> _tapWallSec = [];
  final List<double> _tapMediaSec = [];
  double? _tapBpm;
  bool _tapSetFirst = false; // 设首拍：每次打拍都用媒体位置对齐首拍
  StreamSubscription<Duration>? _posSub;
  StreamSubscription<PlayerState>? _stateSub;
  StreamSubscription<Duration?>? _durSub;
  StreamSubscription<ProcessingState>? _procSub;
  bool _handlingEnded = false;

  @override
  void initState() {
    super.initState();
    final player = context.read<AudioPlayerService>().player;
    _posSub = player.positionStream.listen((_) {
      if (mounted) setState(() {});
    });
    _stateSub = player.playerStateStream.listen((ps) {
      if (!mounted) return;
      context.read<QueueService>().setPlaying(ps.playing);
      if (ps.playing) {
        _reAnchor(player);
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

  /// 当前歌曲的相位可靠性（0..1，null=未评估）。
  double? _currentReliability(Song? song) => song?.phaseReliability;

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

    // 相位可靠性过低 → 提示手动校准。
    final rel = _currentReliability(song);
    final lowReliability = rel != null && rel < 0.6;

    final beatList = _currentBeatList(song, current.targetBpm);

    return Scaffold(
      appBar: AppBar(title: const Text('播放')),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: SingleChildScrollView(
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
                              style: Theme.of(context).textTheme.titleLarge),
                          const SizedBox(height: 8),
                          Text('原始 BPM ${current.originalBpm.round()} · 目标 BPM ${current.targetBpm.round()}'),
                          Text('变速播放速率 ×${speed.toStringAsFixed(2)}（保持音高）'),
                          Text('实际节奏 ≈ ${displayBpm.round()} BPM',
                              style: Theme.of(context).textTheme.bodySmall),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
            if (lowReliability)
              const Padding(
                padding: EdgeInsets.only(top: 8),
                child: Card(
                  color: Color(0x33FF9800),
                  child: Padding(
                    padding: EdgeInsets.all(10),
                    child: Row(
                      children: [
                        Icon(Icons.warning_amber, color: Colors.orangeAccent),
                        SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            '相位检测可靠性偏低，自动拍点可能不稳。建议用下方「打拍校准」手动对齐。',
                            style: TextStyle(fontSize: 13),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            const SizedBox(height: 8),
            _buildBeatRuler(beatList, player),
            const SizedBox(height: 8),
            _buildBeatControls(context, met, player, current.targetBpm),
            const SizedBox(height: 12),
            _buildProgress(position, duration, player),
            const SizedBox(height: 8),
            // 随机播放 + 单曲/列表循环开关
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                IconButton(
                  tooltip: queue.shuffle ? '随机播放：开' : '随机播放：关',
                  icon: Icon(
                    Icons.shuffle,
                    color: queue.shuffle ? Theme.of(context).colorScheme.primary : null,
                  ),
                  onPressed: () => context.read<QueueService>().setShuffle(!queue.shuffle),
                ),
                const SizedBox(width: 40),
                IconButton(
                  tooltip: _loopLabel(queue.loopMode),
                  icon: Icon(
                    queue.repeatingOne ? Icons.repeat_one : Icons.repeat,
                    color: queue.loopMode != LoopMode.off
                        ? Theme.of(context).colorScheme.primary
                        : null,
                  ),
                  onPressed: () {
                    final q = context.read<QueueService>();
                    final next = switch (q.loopMode) {
                      LoopMode.off => LoopMode.all,
                      LoopMode.all => LoopMode.one,
                      LoopMode.one => LoopMode.off,
                    };
                    q.setLoopMode(next);
                  },
                ),
              ],
            ),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                IconButton(
                  iconSize: 36,
                  icon: const Icon(Icons.skip_previous),
                  onPressed: () => _prev(context),
                ),
                IconButton(
                  iconSize: 56,
                  icon: Icon(queue.playing ? Icons.pause_circle : Icons.play_circle),
                  onPressed: () => _toggle(context),
                ),
                IconButton(
                  iconSize: 36,
                  icon: const Icon(Icons.skip_next),
                  onPressed: () => _next(context),
                ),
              ],
            ),
            const SizedBox(height: 12),
            _buildMetronomeControls(context, met, player, current.targetBpm),
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

  Widget _buildProgress(Duration position, Duration duration, AudioPlayer player) {
    final seconds = duration.inMilliseconds > 0
        ? (position.inMilliseconds / duration.inMilliseconds).clamp(0.0, 1.0)
        : 0.0;
    return Column(
      children: [
        Slider(
          value: seconds,
          onChanged: (v) {
            if (duration.inMilliseconds > 0) {
              player.seek(Duration(milliseconds: (v * duration.inMilliseconds).round()));
            }
          },
        ),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(_fmt(position)),
            Text(_fmt(duration)),
          ],
        ),
      ],
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

  Widget _buildBeatRuler(List<double> beats, AudioPlayer player) {
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
                      if (met.isEnabled) {
                        met.setPhaseOffset(_phaseNudgeSeconds(targetBpm));
                        met.reAnchor(player.position);
                      }
                    },
                  ),
                ),
                const SizedBox(width: 8),
                Text('${_phaseNudgePct.round()}%'),
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
                    ? '测出 ${_tapBpm!.round()} BPM · 再点 ${8 - _tapWallSec.length} 下'
                    : '点击 ${8 - _tapWallSec.length} 下'),
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
          ],
        ),
        if (_tapBpm != null && _tapWallSec.length >= 8)
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
                      _tapWallSec.clear();
                      _tapMediaSec.clear();
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
    final media = player.position.inMilliseconds / 1000.0;
    _tapWallSec.add(DateTime.now().millisecondsSinceEpoch / 1000.0);
    _tapMediaSec.add(media);
    if (_tapSetFirst && met.isEnabled) {
      met.setPhase(media);
      met.setPhaseOffset(_phaseNudgeSeconds(targetBpm));
      met.reAnchor(player.position);
    }
    if (_tapWallSec.length >= 8) {
      // 中位间隔 → BPM
      final intervals = <double>[];
      for (var i = 1; i < _tapWallSec.length; i++) {
        intervals.add(_tapWallSec[i] - _tapWallSec[i - 1]);
      }
      intervals.sort();
      final median = intervals[intervals.length ~/ 2];
      if (median > 0) {
        _tapBpm = 60.0 / median;
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
                  value: met.isEnabled,
                  onChanged: (v) async {
                    if (v) {
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
                      met.setEnabled(false);
                    }
                    setState(() {});
                  },
                ),
                const Spacer(),
                Text('${targetBpm.round()} BPM'),
              ],
            ),
            Row(
              children: [
                const Icon(Icons.volume_down),
                Expanded(
                  child: Slider(
                    value: _metVolume,
                    onChanged: (v) {
                      setState(() => _metVolume = v);
                      met.setVolume(v);
                    },
                  ),
                ),
                const Icon(Icons.volume_up),
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
