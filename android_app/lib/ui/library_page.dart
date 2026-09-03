import 'dart:io';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/song.dart';
import '../services/audio_reader.dart';
import '../services/library_service.dart';
import 'marquee_text.dart';
import 'settings_page.dart';

class LibraryPage extends StatefulWidget {
  const LibraryPage({super.key});

  @override
  State<LibraryPage> createState() => _LibraryPageState();
}

class _LibraryPageState extends State<LibraryPage> {
  /// 单击选中的歌曲 id 集合（用主题色高亮，类比安卓文件管理）。
  final Set<String> _selected = {};

  Future<void> _pickFolder(BuildContext context, LibraryService lib) async {
    final reader = AudioReader();
    final pick = await reader.pickFolder(withSubfolders: true);
    if (pick.cancelled) return;
    if (context.mounted) {
      _selected.clear();
      await lib.loadFolder(pick);
    }
  }

  @override
  Widget build(BuildContext context) {
    final lib = context.watch<LibraryService>();
    return Scaffold(
      appBar: AppBar(
        title: const Text('RUN BPM · 曲库'),
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
      // 底部批量操作栏：多选后对全部选中歌曲统一 ×2 / ÷2 / 重新检测 / 手动修改。
      bottomNavigationBar: _selected.isEmpty
          ? null
          : _BatchBar(
              count: _selected.length,
              onX2: () => _batchScale(lib, 2),
              onDiv2: () => _batchScale(lib, 0.5),
              onRetry: () => _batchRetry(lib),
              onManual: () => _batchManual(lib),
              onClear: () => setState(_selected.clear),
            ),
      body: lib.folderPath == null
          ? _EmptyState(onPick: () => _pickFolder(context, lib))
          : Column(
              children: [
                _FolderHeader(lib: lib, onPick: () => _pickFolder(context, lib)),
                if (lib.songs.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 8),
                    child: Row(
                      children: [
                        Text('点击选歌，长按单首操作',
                            style: Theme.of(context).textTheme.bodySmall),
                        const Spacer(),
                        TextButton(
                          onPressed: () => setState(() {
                            if (_selected.length == lib.songs.length) {
                              _selected.clear();
                            } else {
                              _selected
                                ..clear()
                                ..addAll(lib.songs.map((s) => s.id));
                            }
                          }),
                          child: Text(
                              _selected.length == lib.songs.length ? '清空' : '全选'),
                        ),
                      ],
                    ),
                  ),
                if (lib.isAnalyzing)
                  Padding(
                    padding: const EdgeInsets.all(8),
                    child: LinearProgressIndicator(
                      value: lib.analyzingTotal == 0
                          ? null
                          : lib.analyzingDone / lib.analyzingTotal,
                    ),
                  ),
                Expanded(
                  child: _SongList(
                    lib: lib,
                    selected: _selected,
                    onToggle: (id) => setState(() {
                      if (!_selected.remove(id)) _selected.add(id);
                    }),
                    onLongPress: (song) => _showActions(context, lib, song),
                  ),
                ),
              ],
            ),
    );
  }

  /// 长按弹出操作菜单（重新检测 / 乘二 / 除以二 / 手动修改）。
  Future<void> _showActions(
      BuildContext context, LibraryService lib, Song song) async {
    final action = await showModalBottomSheet<String>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.music_note),
              title: MarqueeText(song.title),
              subtitle: Text(_statusText(song)),
            ),
            const Divider(height: 1),
            ListTile(
              leading: const Icon(Icons.refresh),
              title: const Text('重新检测'),
              subtitle: const Text('重新分析这首歌曲的 BPM'),
              onTap: () => Navigator.pop(ctx, 'retry'),
            ),
            if (song.hasBpm) ...[
              ListTile(
                leading: const Icon(Icons.double_arrow),
                title: const Text('BPM ×2'),
                subtitle: const Text('把 BPM 乘以二（常用于半拍/休止误判）'),
                onTap: () => Navigator.pop(ctx, 'x2'),
              ),
              ListTile(
                leading: const Icon(Icons.horizontal_rule),
                title: const Text('BPM ÷2'),
                subtitle: const Text('把 BPM 除以二'),
                onTap: () => Navigator.pop(ctx, 'div2'),
              ),
              ListTile(
                leading: const Icon(Icons.edit),
                title: const Text('手动修改'),
                subtitle: const Text('直接输入一个数值作为 BPM'),
                onTap: () => Navigator.pop(ctx, 'manual'),
              ),
            ],
          ],
        ),
      ),
    );

    if (action == null || !mounted) return;
    switch (action) {
      case 'retry':
        lib.retryAnalyze(song);
        break;
      case 'x2':
        lib.setManualBpm(song, ((song.originalBpm ?? 0) * 2).clamp(20, 400));
        break;
      case 'div2':
        lib.setManualBpm(song, ((song.originalBpm ?? 0) / 2).clamp(20, 400));
        break;
      case 'manual':
        await _editBpm(context, lib, song);
        break;
    }
  }

  String _statusText(Song song) {
    switch (song.bpmStatus) {
      case BpmStatus.pending:
        return '等待分析';
      case BpmStatus.analyzing:
        return '分析中…';
      case BpmStatus.failed:
        return song.bpmError ?? '分析失败';
      case BpmStatus.done:
        final conf = (song.bpmConfidence ?? 0) * 100;
        return '${song.originalBpm!.round()} BPM · 可信度 ${conf.round()}%';
    }
  }

  Future<void> _editBpm(
      BuildContext context, LibraryService lib, Song song) async {
    final controller = TextEditingController(
      text: song.originalBpm?.round().toString() ?? '',
    );
    final value = await showDialog<double>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('修改 BPM'),
        content: TextField(
          controller: controller,
          keyboardType: TextInputType.number,
          decoration: const InputDecoration(labelText: 'BPM (20–400)'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          FilledButton(
            onPressed: () {
              final v = double.tryParse(controller.text);
              if (v != null && v >= 20 && v <= 400) Navigator.pop(ctx, v);
            },
            child: const Text('确定'),
          ),
        ],
      ),
    );
    if (value != null) {
      lib.setManualBpm(song, value);
    }
  }

  List<Song> _selectedSongs(LibraryService lib) =>
      lib.songs.where((s) => _selected.contains(s.id)).toList();

  void _batchScale(LibraryService lib, double factor) {
    for (final s in _selectedSongs(lib)) {
      final orig = s.originalBpm;
      if (orig == null || orig <= 0) continue;
      lib.setManualBpm(s, (orig * factor).clamp(20, 400));
    }
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('已对 ${_selected.length} 首歌曲 ${factor == 2 ? '×2' : '÷2'}')),
    );
  }

  void _batchRetry(LibraryService lib) {
    for (final s in _selectedSongs(lib)) {
      lib.retryAnalyze(s);
    }
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('已重新检测 ${_selected.length} 首歌曲')),
    );
  }

  Future<void> _batchManual(LibraryService lib) async {
    if (_selectedSongs(lib).isEmpty) return;
    final controller = TextEditingController();
    final value = await showDialog<double>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('批量修改 BPM'),
        content: TextField(
          controller: controller,
          keyboardType: TextInputType.number,
          decoration:
              const InputDecoration(labelText: '统一设为 BPM (20–400)'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          FilledButton(
            onPressed: () {
              final v = double.tryParse(controller.text);
              if (v != null && v >= 20 && v <= 400) Navigator.pop(ctx, v);
            },
            child: const Text('确定'),
          ),
        ],
      ),
    );
    if (value == null) return;
    for (final s in _selectedSongs(lib)) {
      lib.setManualBpm(s, value);
    }
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('已把 ${_selected.length} 首歌曲设为 $value BPM')),
      );
    }
  }
}

class _BatchBar extends StatelessWidget {
  final int count;
  final VoidCallback onX2;
  final VoidCallback onDiv2;
  final VoidCallback onRetry;
  final VoidCallback onManual;
  final VoidCallback onClear;
  const _BatchBar({
    required this.count,
    required this.onX2,
    required this.onDiv2,
    required this.onRetry,
    required this.onManual,
    required this.onClear,
  });

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Material(
      color: colorScheme.surfaceContainerHighest,
      child: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                '已选 $count 首 · 对全部执行操作',
                style: Theme.of(context).textTheme.bodyMedium,
              ),
              const SizedBox(height: 4),
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceAround,
                children: [
                  _BatchButton(
                      icon: Icons.double_arrow, label: '×2', onPressed: onX2),
                  _BatchButton(
                      icon: Icons.horizontal_rule,
                      label: '÷2',
                      onPressed: onDiv2),
                  _BatchButton(
                      icon: Icons.refresh, label: '重测', onPressed: onRetry),
                  _BatchButton(
                      icon: Icons.edit, label: '改值', onPressed: onManual),
                  _BatchButton(
                      icon: Icons.deselect, label: '清空', onPressed: onClear),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _BatchButton extends StatelessWidget {
  final IconData icon;
  final String label;
  final VoidCallback onPressed;
  const _BatchButton({
    required this.icon,
    required this.label,
    required this.onPressed,
  });

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        IconButton(
          onPressed: onPressed,
          icon: Icon(icon),
          color: colorScheme.primary,
          tooltip: label,
        ),
        Text(label, style: Theme.of(context).textTheme.labelSmall),
      ],
    );
  }
}

class _EmptyState extends StatelessWidget {
  final VoidCallback onPick;
  const _EmptyState({required this.onPick});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.music_note, size: 80, color: Colors.grey),
          const SizedBox(height: 16),
          const Text('选择本地文件夹，直接读取其中的音乐\n无需上传，全程离线'),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: onPick,
            icon: const Icon(Icons.folder_open),
            label: const Text('选择音乐文件夹'),
          ),
        ],
      ),
    );
  }
}

class _FolderHeader extends StatelessWidget {
  final LibraryService lib;
  final VoidCallback onPick;
  const _FolderHeader({required this.lib, required this.onPick});

  @override
  Widget build(BuildContext context) {
    return ListTile(
      leading: const Icon(Icons.folder),
      title: Text(lib.folderPath ?? ''),
      subtitle: Text('${lib.songs.length} 首音乐'),
      trailing: TextButton(onPressed: onPick, child: const Text('更换')),
    );
  }
}

class _SongList extends StatelessWidget {
  final LibraryService lib;
  final Set<String> selected;
  final ValueChanged<String> onToggle;
  final void Function(Song) onLongPress;
  const _SongList({
    required this.lib,
    required this.selected,
    required this.onToggle,
    required this.onLongPress,
  });

  @override
  Widget build(BuildContext context) {
    if (lib.songs.isEmpty) {
      return const Center(child: Text('文件夹中没有音乐文件'));
    }
    return ListView.builder(
      itemCount: lib.songs.length,
      itemBuilder: (context, i) {
        final s = lib.songs[i];
        return _SongTile(
          song: s,
          selected: selected.contains(s.id),
          onToggle: () => onToggle(s.id),
          onLongPress: () => onLongPress(s),
        );
      },
    );
  }
}

class _SongTile extends StatelessWidget {
  final Song song;
  final bool selected;
  final VoidCallback onToggle;
  final VoidCallback onLongPress;
  const _SongTile({
    required this.song,
    required this.selected,
    required this.onToggle,
    required this.onLongPress,
  });

  String _statusText() {
    switch (song.bpmStatus) {
      case BpmStatus.pending:
        return '等待分析';
      case BpmStatus.analyzing:
        return '分析中…';
      case BpmStatus.failed:
        return song.bpmError ?? '分析失败';
      case BpmStatus.done:
        final conf = (song.bpmConfidence ?? 0) * 100;
        return '${song.originalBpm!.round()} BPM · 可信度 ${conf.round()}%';
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final color = switch (song.bpmStatus) {
      BpmStatus.done => Colors.greenAccent,
      BpmStatus.failed => Colors.redAccent,
      _ => Colors.orangeAccent,
    };
    return ListTile(
      // 单击选中；长按弹出操作菜单。
      onTap: onToggle,
      onLongPress: onLongPress,
      selected: selected,
      selectedTileColor: theme.colorScheme.primary.withValues(alpha: 0.15),
      leading: ClipRRect(
        borderRadius: BorderRadius.circular(4),
        child: SizedBox(
          width: 48,
          height: 48,
          child: song.artworkPath != null
              ? Image.file(
                  File(song.artworkPath!),
                  fit: BoxFit.cover,
                  errorBuilder: (_, __, ___) =>
                      const Icon(Icons.music_note, color: Colors.grey),
                )
              : const Icon(Icons.music_note, color: Colors.grey),
        ),
      ),
      title: MarqueeText(song.title),
      subtitle: Text(_statusText(), style: TextStyle(color: color)),
      // 与推荐页对齐：不显示三点/勾选等 trailing 图标，仅用主题色高亮标记选中。
    );
  }
}
