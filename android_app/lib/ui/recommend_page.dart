import 'dart:io';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/song.dart';
import '../services/audio_player_service.dart';
import '../services/library_service.dart';
import '../services/bpm_display_controller.dart';
import '../services/queue_service.dart';
import 'help_dialog.dart';
import 'marquee_text.dart';
import 'mode_picker.dart';
import 'settings_page.dart';
import 'tempo_grade.dart';

class RecommendPage extends StatefulWidget {
  const RecommendPage({super.key});

  @override
  State<RecommendPage> createState() => _RecommendPageState();
}

class _RecommendPageState extends State<RecommendPage> {
  final Set<String> _selected = {};

  @override
  Widget build(BuildContext context) {
    final lib = context.watch<LibraryService>();
    final recs = lib.recommend(target: lib.targetBpm)
      ..sort((a, b) => a.distance.compareTo(b.distance));
    final eligible = recs.where((r) => r.song.hasBpm).length;
    final target = lib.targetBpm;

    return Scaffold(
      appBar: AppBar(
        title: const Text('运动模式与推荐'),
        actions: [
          IconButton(
            icon: const Icon(Icons.help_outline),
            tooltip: '使用说明',
            onPressed: () => HelpDialog.show(context),
          ),
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
      // 底部常驻播放条：无论列表多长，按钮始终可见，无需滚动到底。
      bottomNavigationBar: _selected.isEmpty
          ? null
          : SafeArea(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
                child: FilledButton.icon(
                  onPressed: () => _play(context, lib),
                  icon: const Icon(Icons.play_arrow),
                  style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(52),
                    textStyle: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  label: Text('变速并播放（${_selected.length} 首）'),
                ),
              ),
            ),
      body: Column(
        children: [
          // 固定头部：只有「选择 + 自动勾选/全选/全不选」按钮行。
          // 翻动歌曲列表时保持可见，方便反复点选（运动模式选择不再固定，随列表滚动）。
          if (eligible > 0) ...[
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
              child: Row(
                children: [
                  Text('选择', style: Theme.of(context).textTheme.bodyMedium),
                  const Spacer(),
                  TextButton(
                    onPressed: () {
                      setState(() {
                        _selected.clear();
                        for (final r in recs) {
                          if (_processable(r, lib.targetBpm)) {
                            _selected.add(r.song.id);
                          }
                        }
                      });
                    },
                    child: const Text('自动勾选可变速'),
                  ),
                  TextButton(
                    onPressed: () {
                      setState(() {
                        _selected.clear();
                        for (final r in recs) {
                          _selected.add(r.song.id);
                        }
                      });
                    },
                    child: const Text('全选'),
                  ),
                  TextButton(
                    onPressed: () => setState(() => _selected.clear()),
                    child: const Text('全不选'),
                  ),
                ],
              ),
            ),
            const Divider(height: 1),
          ],
          Expanded(
            child: ListView(
              padding: EdgeInsets.fromLTRB(16, 8, 16, _selected.isEmpty ? 16 : 96),
              children: [
                // 运动模式选择：随列表一起滚动（不固定）。
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: ModePicker(
                    bpm: lib.targetBpm,
                    onChanged: (v) => setState(() => lib.targetBpm = v),
                  ),
                ),
                ...recs.map((r) => _RecTile(
                      r: r,
                      targetBpm: target,
                      selected: _selected.contains(r.song.id),
                      onToggle: () => setState(() {
                        if (!_selected.remove(r.song.id)) {
                          _selected.add(r.song.id);
                        }
                      }),
                    )),
                if (recs.isEmpty)
                  const Padding(
                    padding: EdgeInsets.all(24),
                    child: Center(
                        child: Text('请先在「曲库」选择文件夹并等待 BPM 分析')),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// 是否可用于变速：与原 BPM 的偏移 ≤12%（对应 Web TempoArrow 的可变速判定）。
  bool _processable(Recommendation r, double target) {
    final orig = r.song.originalBpm;
    if (orig == null || orig <= 0) return false;
    return (r.song.originalBpm! - target).abs() / orig <= 0.12;
  }

  void _play(BuildContext context, LibraryService lib) {
    final queue = context.read<QueueService>();
    final selected = lib.songs.where((s) => _selected.contains(s.id)).toList();
    final playlist = lib.buildPlaylist(selected, target: lib.targetBpm);
    queue.start(playlist);
    // 真正开始播放第一首（与「变速并播放」文案一致），
    // 由 AudioPlayerService 载入并变速；失败也不阻塞入队。
    final svc = context.read<AudioPlayerService>();
    if (playlist.isNotEmpty) {
      svc.tryPlay(playlist.first);
    }
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('已入队并开始播放第一首')),
    );
  }
}

class _RecTile extends StatelessWidget {
  final Recommendation r;
  final double targetBpm;
  final bool selected;
  final VoidCallback onToggle;
  const _RecTile({
    required this.r,
    required this.targetBpm,
    required this.selected,
    required this.onToggle,
  });

  @override
  Widget build(BuildContext context) {
    final s = r.song;
    final orig = s.originalBpm;
    final grade = gradeTempo(orig, targetBpm);
    final theme = Theme.of(context);
    return ListTile(
      onTap: onToggle,
      // 对齐 Web：选中行用主题色高亮，不再显示勾选框。
      selected: selected,
      selectedTileColor: theme.colorScheme.primary.withValues(alpha: 0.15),
      selectedColor: theme.colorScheme.primary,
      leading: ClipRRect(
        borderRadius: BorderRadius.circular(4),
        child: SizedBox(
          width: 48,
          height: 48,
          child: s.artworkPath != null
              ? Image.file(
                  File(s.artworkPath!),
                  fit: BoxFit.cover,
                  errorBuilder: (_, __, ___) =>
                      const Icon(Icons.music_note, color: Colors.grey),
                )
              : const Icon(Icons.music_note, color: Colors.grey),
        ),
      ),
      title: MarqueeText(s.title),
      subtitle: Row(
        children: [
          // 分级箭头/符号（绿= · 绿/琥珀/红↑↓ · 红✕），颜色与 Web 图例一致。
          Text(
            grade.symbol,
            style: TextStyle(
              color: grade.color,
              fontWeight: FontWeight.bold,
              fontSize: 16,
            ),
          ),
          const SizedBox(width: 6),
          Expanded(
            child: Text(
              orig != null
                  ? '${context.read<BpmDisplayController>().format(orig)} BPM · ${grade.pctLabel} · ${r.distance.toStringAsFixed(1)}'
                  : '未知 BPM',
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ],
      ),
      dense: true,
    );
  }
}

