/// 选择文件夹并读取其中的音频文件（离线、本地直接读取）。
library;

import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:on_audio_query/on_audio_query.dart';
import 'package:path/path.dart' as p;

/// 支持的音频扩展名（小写，不含点）。
///
/// 包含网易云加密格式 ncm：扫描后在库服务里解密为真实音频再走播放/BPM 管线。
const Set<String> kAudioExtensions = {
  'mp3', 'wav', 'm4a', 'aac', 'ogg', 'flac', 'opus', 'aiff', 'aif', 'wma',
  'ncm',
};

class FolderPick {
  /// 用户选中的文件夹绝对路径；null 表示取消。
  final String? path;

  /// 该文件夹下扫描到的音频文件（绝对路径）。
  final List<String> audioFiles;

  /// 扫描过程中遇到的错误（例如无权限的父目录），仅提示用，不阻断。
  final List<String> errors;

  const FolderPick({this.path, this.audioFiles = const [], this.errors = const []});

  bool get cancelled => path == null;
}

class AudioReader {
  final OnAudioQuery _audioQuery = OnAudioQuery();

  /// 通过系统文件夹选择器（Android 上为 SAF 目录选择器）让用户挑选文件夹，
  /// 随后递归扫描其中所有受支持的音频文件。
  ///
  /// Android 11+（作用域存储）下 `dart:io` 无法枚举共享媒体目录，因此这里
  /// 优先通过 MediaStore（on_audio_query）查询音频并按选中的文件夹路径过滤。
  /// [withSubfolders] 为 true 时把子目录也视为同一文件夹（MediaStore 仅支持
  /// 按路径前缀匹配，子目录文件自然包含）；false 时只匹配第一层。
  Future<FolderPick> pickFolder({bool withSubfolders = true}) async {
    final selected = await FilePicker.platform.getDirectoryPath(
      dialogTitle: '选择音乐文件夹',
      lockParentWindow: true,
    );
    if (selected == null || selected.isEmpty) {
      return const FolderPick();
    }
    final folder = normalizeFolderPath(selected);
    final files = <String>[];
    final errors = <String>[];

    // 1) 尝试 MediaStore（Android 作用域存储的正确途径）
    try {
      final scanned = await _scanViaMediaStore(folder);
      files.addAll(scanned.files);
      errors.addAll(scanned.errors);
    } catch (e) {
      errors.add('MediaStore 扫描失败，改用文件系统扫描: $e');
    }

    // 2) 若 MediaStore 无结果或不可用，回退到 dart:io 文件系统扫描
    //    （Android 10 及以下、iOS、或 MediaStore 权限被拒时仍可工作）。
    if (files.isEmpty) {
      try {
        final root = Directory(folder);
        if (await root.exists()) {
          await _scan(root, files, errors,
              depth: 0, recursive: withSubfolders);
        } else {
          errors.add('文件夹不存在: $selected');
        }
      } catch (e) {
        errors.add('无法读取文件夹: $e');
      }
    }

    return FolderPick(path: folder, audioFiles: files, errors: errors);
  }

  /// 不弹选择器，直接根据已知文件夹路径扫描其中的音频文件。
  /// 用于启动时自动恢复上次选择的文件夹。
  Future<FolderPick> scanFolder(String rawFolder) async {
    final folder = normalizeFolderPath(rawFolder);
    final files = <String>[];
    final errors = <String>[];

    // 1) 优先 MediaStore（与 pickFolder 一致）
    try {
      final scanned = await _scanViaMediaStore(folder);
      files.addAll(scanned.files);
      errors.addAll(scanned.errors);
    } catch (e) {
      errors.add('MediaStore 扫描失败: $e');
    }

    // 2) 回退文件系统扫描
    if (files.isEmpty) {
      try {
        final root = Directory(folder);
        if (await root.exists()) {
          await _scan(root, files, errors, depth: 0, recursive: true);
        }
      } catch (e) {
        errors.add('无法读取文件夹: $e');
      }
    }

    return FolderPick(path: folder, audioFiles: files, errors: errors);
  }

  /// 把 file_picker 返回的目录标识归一化为真实绝对路径（用于路径前缀匹配）。
  ///
  /// 兼容两种返回：(a) 真实路径如 `/storage/emulated/0/Music`；
  /// (b) SAF tree 形式如 `content://com.android.externalstorage.documents/tree/primary%3AMusic`。
  String normalizeFolderPath(String raw) {
    var s = raw.trim();
    final lower = s.toLowerCase();
    if (lower.contains('document/tree/')) {
      // 解析 content://…/tree/primary%3AFoo/…  => /storage/emulated/0/Foo
      final idx = lower.lastIndexOf('/tree/');
      if (idx >= 0) {
        var tail = s.substring(idx + '/tree/'.length);
        tail = tail.split('/').first;
        tail = Uri.decodeComponent(tail);
        if (tail.contains(':')) {
          final parts = tail.split(':');
          final volume = parts.first;
          final rel = parts.sublist(1).join(':');
          final relPath = rel.split('/').map((seg) => Uri.decodeComponent(seg)).join('/');
          final volumeRoot = (volume == 'primary' || volume == 'internal')
              ? '/storage/emulated/0'
              : '/storage/$volume';
          return p.normalize('$volumeRoot/$relPath');
        }
      }
    }
    // 已经是真实路径
    return p.normalize(s);
  }

  Future<({List<String> files, List<String> errors})> _scanViaMediaStore(
      String folder) async {
    final files = <String>[];
    final errors = <String>[];

    final folderNorm = p.normalize(folder);
    final folderLower = folderNorm.toLowerCase();

    // 请求读取权限（READ_MEDIA_AUDIO on 13+，READ_EXTERNAL_STORAGE on ≤12）
    var permitted = false;
    try {
      permitted = await _audioQuery.checkAndRequest();
    } catch (e) {
      errors.add('无法请求媒体权限: $e');
      return (files: files, errors: errors);
    }
    if (!permitted) {
      errors.add('未授予媒体读取权限，无法扫描歌曲');
      return (files: files, errors: errors);
    }

    List<SongModel> songs = const [];
    try {
      songs = await _audioQuery.querySongs(
        sortType: null,
        orderType: OrderType.ASC_OR_SMALLER,
        uriType: UriType.EXTERNAL,
      );
    } catch (e) {
      errors.add('查询媒体库失败: $e');
      return (files: files, errors: errors);
    }

    for (final s in songs) {
      final data = s.data;
      if (data.isEmpty) continue;
      if (!kAudioExtensions.contains(p.extension(data).toLowerCase().replaceFirst('.', ''))) {
        continue;
      }
      final norm = p.normalize(data);
      final lower = norm.toLowerCase();
      // 文件必须位于所选文件夹（或子目录）内
      if (!lower.startsWith(folderLower)) continue;
      final rel = lower.substring(folderLower.length);
      if (rel.isEmpty) continue; // 不应发生
      files.add(norm);
    }

    return (files: files, errors: errors);
  }

  Future<void> _scan(
    Directory dir,
    List<String> files,
    List<String> errors, {
    required int depth,
    required bool recursive,
  }) async {
    if (depth > 4 && !recursive) return; // 防御：非递归时不深入
    if (depth > 12) return; // 防御：防止极端深目录
    try {
      await for (final entity in dir.list(followLinks: false)) {
        if (entity is File) {
          final ext = p.extension(entity.path).toLowerCase().replaceFirst('.', '');
          if (kAudioExtensions.contains(ext)) {
            files.add(entity.path);
          }
        } else if (entity is Directory && recursive) {
          await _scan(entity, files, errors, depth: depth + 1, recursive: recursive);
        }
      }
    } catch (e) {
      errors.add('无法读取 ${dir.path}: $e');
    }
  }
}
