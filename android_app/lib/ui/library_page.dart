import 'dart:io';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/song.dart';
import '../services/audio_reader.dart';
import '../services/library_service.dart';
import 'marquee_text.dart';

class LibraryPage extends StatelessWidget {
  const LibraryPage({super.key});

  Future<void> _pickFolder(BuildContext context, LibraryService lib) async {
    final reader = AudioReader();
    final pick = await reader.pickFolder(withSubfolders: true);
    if (pick.cancelled) return;
    if (context.mounted) {
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
            icon: const Icon(Icons.folder_open),
            tooltip: '选择文件夹',
            onPressed: () => _pickFolder(context, lib),
          ),
        ],
      ),
      body: lib.folderPath == null
          ? _EmptyState(onPick: () => _pickFolder(context, lib))
          : Column(
              children: [
                _FolderHeader(lib: lib, onPick: () => _pickFolder(context, lib)),
                if (lib.isAnalyzing)
                  Padding(
                    padding: const EdgeInsets.all(8),
                    child: LinearProgressIndicator(
                      value: lib.analyzingTotal == 0
                          ? null
                          : lib.analyzingDone / lib.analyzingTotal,
                    ),
                  ),
                Expanded(child: _SongList(lib: lib)),
              ],
            ),
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
  const _SongList({required this.lib});

  @override
  Widget build(BuildContext context) {
    if (lib.songs.isEmpty) {
      return const Center(child: Text('文件夹中没有音乐文件'));
    }
    return ListView.builder(
      itemCount: lib.songs.length,
      itemBuilder: (context, i) {
        final s = lib.songs[i];
        return _SongTile(song: s, lib: lib);
      },
    );
  }
}

class _SongTile extends StatelessWidget {
  final Song song;
  final LibraryService lib;
  const _SongTile({required this.song, required this.lib});

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
    final color = switch (song.bpmStatus) {
      BpmStatus.done => Colors.greenAccent,
      BpmStatus.failed => Colors.redAccent,
      _ => Colors.orangeAccent,
    };
    return ListTile(
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
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (song.hasBpm)
            IconButton(
              icon: const Icon(Icons.edit, size: 20),
              tooltip: '手动修改 BPM',
              onPressed: () => _editBpm(context),
            ),
          if (song.bpmStatus == BpmStatus.failed)
            IconButton(
              icon: const Icon(Icons.refresh, size: 20),
              tooltip: '重试分析',
              onPressed: () => lib.retryAnalyze(song),
            ),
        ],
      ),
    );
  }

  Future<void> _editBpm(BuildContext context) async {
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
}
