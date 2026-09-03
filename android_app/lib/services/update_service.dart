import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:path_provider/path_provider.dart';

/// 联网更新服务：从 GitHub Releases 检测新版本并下载/安装 APK。
///
/// 版本约定：repo 的每个 Release 用一个 tag（如 `v0.1.0+29`），并附带一个
/// 名为 `app-release.apk` 的构建产物。默认仓库为开机自检用的远端。
class UpdateInfo {
  final String latestVersion; // 如 0.1.0+29
  final String apkUrl;
  final String? releaseNotes;
  const UpdateInfo({
    required this.latestVersion,
    required this.apkUrl,
    this.releaseNotes,
  });
}

/// 一次检查更新的结果，让 UI 能区分「已是最新」「有新版」「网络失败」。
class UpdateCheck {
  final UpdateStatus status;
  final UpdateInfo? info;
  const UpdateCheck({required this.status, this.info});
}

enum UpdateStatus { upToDate, available, failed }

/// 最新一次发布的信息，供关于页展示「最新版本公告」。包含发布时间与公告正文。
class LatestRelease {
  final String latestVersion; // 如 0.1.0+45
  final DateTime? publishedAt; // GitHub 上该发布的发布时间
  final String? releaseNotes; // 公告正文
  final String? apkUrl; // 对应 APK 下载地址
  const LatestRelease({
    required this.latestVersion,
    this.publishedAt,
    this.releaseNotes,
    this.apkUrl,
  });
}

class UpdateService {
  /// 检查更新用的 GitHub 仓库（Sweejumn/bunbeat）。
  static const String repo = 'Sweejumn/bunbeat';
  static const String _api =
      'https://api.github.com/repos/$repo/releases/latest';
  static const MethodChannel _installChannel =
      MethodChannel('run_bpm/install');

  /// 请求 GitHub Releases/latest，判断是否有比当前更新的版本。
  Future<UpdateCheck> checkForUpdate() async {
    try {
      final current = await PackageInfo.fromPlatform();
      // PackageInfo.version 是 versionName（如 0.1.0），不带 build 号；
      // build 号在 versionCode（buildNumber）里。这里拼成 0.1.0+32，
      // 否则 _isNewer 因 current 无 build 号会回退到 semver 相等而漏判更新。
      final currentFull = current.buildNumber.isNotEmpty
          ? '${current.version}+${current.buildNumber}'
          : current.version;
      final dio = Dio();
      final resp = await dio.get<Map<String, dynamic>>(
        _api,
        options: Options(
          responseType: ResponseType.json,
          headers: {
            'Accept': 'application/vnd.github+json',
            'User-Agent': 'run-bpm-android',
          },
        ),
      );
      final data = resp.data;
      if (data == null) return const UpdateCheck(status: UpdateStatus.failed);

      final tag = data['tag_name'] as String? ?? '';
      final latest = _cleanVersion(tag);
      if (latest.isEmpty) return const UpdateCheck(status: UpdateStatus.failed);

      // 找 APK 资产
      final assets = data['assets'] as List? ?? const [];
      String? apkUrl;
      for (final a in assets) {
        final name = (a['name'] as String?) ?? '';
        if (name.toLowerCase().endsWith('.apk')) {
          apkUrl = a['browser_download_url'] as String?;
          break;
        }
      }
      if (apkUrl == null) return const UpdateCheck(status: UpdateStatus.failed);

      if (!_isNewer(latest, currentFull)) {
        return const UpdateCheck(status: UpdateStatus.upToDate);
      }

      final notes = data['body'] as String?;
      return UpdateCheck(
        status: UpdateStatus.available,
        info: UpdateInfo(
          latestVersion: latest,
          apkUrl: apkUrl,
          releaseNotes:
              notes == null || notes.trim().isEmpty ? null : notes.trim(),
        ),
      );
    } on DioException catch (_) {
      // 任何网络/HTTP 错误（含 404：仓库还没发布 Release）都归为 failed，
      // 便于调试时区分「真网络问题」与「有更新」，不当作已是最新。
      return const UpdateCheck(status: UpdateStatus.failed);
    } catch (_) {
      // 其它解析类异常也归为 failed，绝不影响正常使用。
      return const UpdateCheck(status: UpdateStatus.failed);
    }
  }

  /// 获取最新发布的信息（版本号、发布时间、公告正文），供关于页展示。
  /// 网络失败或尚无发布时返回 null。
  Future<LatestRelease?> fetchLatestRelease() async {
    try {
      final dio = Dio();
      final resp = await dio.get<Map<String, dynamic>>(
        _api,
        options: Options(
          responseType: ResponseType.json,
          headers: {
            'Accept': 'application/vnd.github+json',
            'User-Agent': 'run-bpm-android',
          },
        ),
      );
      final data = resp.data;
      if (data == null) return null;

      final tag = data['tag_name'] as String? ?? '';
      final latest = _cleanVersion(tag);
      if (latest.isEmpty) return null;

      DateTime? published;
      final pubStr = data['published_at'] as String?;
      if (pubStr != null) {
        published = DateTime.tryParse(pubStr)?.toLocal();
      }

      final notes = data['body'] as String?;
      final assets = data['assets'] as List? ?? const [];
      String? apkUrl;
      for (final a in assets) {
        final name = (a['name'] as String?) ?? '';
        if (name.toLowerCase().endsWith('.apk')) {
          apkUrl = a['browser_download_url'] as String?;
          break;
        }
      }

      return LatestRelease(
        latestVersion: latest,
        publishedAt: published,
        releaseNotes:
            notes == null || notes.trim().isEmpty ? null : notes.trim(),
        apkUrl: apkUrl,
      );
    } catch (_) {
      return null;
    }
  }

  /// 下载 APK 到应用缓存目录，进度 [onProgress](received, total)。
  /// 完成后返回本地文件路径。
  Future<String> downloadApk(
    UpdateInfo info,
    void Function(int received, int total) onProgress,
  ) async {
    final dir = await getTemporaryDirectory();
    final out = File('${dir.path}/run_bpm_update.apk');
    final dio = Dio();
    await dio.download(
      info.apkUrl,
      out.path,
      onReceiveProgress: (received, total) {
        onProgress(received, total == 0 ? received : total);
      },
    );
    return out.path;
  }

  /// 调用原生方法，把下载好的 APK 交给系统安装器。
  Future<bool> installApk(String path) async {
    final ok = await _installChannel.invokeMethod<bool>(
      'installApk',
      {'path': path},
    );
    return ok ?? false;
  }

  /// 去掉 tag 前导的 v/V，得到形如 `0.1.0+29` 的版本串。
  String _cleanVersion(String tag) {
    var v = tag.trim();
    if (v.startsWith('v') || v.startsWith('V')) v = v.substring(1);
    return v;
  }

  /// 版本串比较：优先比 build 号（`+N`），否则按 semver 数字比较。
  /// 返回 true 表示 [latest] 比 [current] 新。
  bool _isNewer(String latest, String current) {
    final latestBuild = _buildNumber(latest);
    final currentBuild = _buildNumber(current);
    if (latestBuild != null && currentBuild != null) {
      return latestBuild > currentBuild;
    }
    final lParts = _numParts(latest);
    final cParts = _numParts(current);
    final len = lParts.length > cParts.length ? lParts.length : cParts.length;
    for (var i = 0; i < len; i++) {
      final l = i < lParts.length ? lParts[i] : 0;
      final c = i < cParts.length ? cParts[i] : 0;
      if (l != c) return l > c;
    }
    return false;
  }

  int? _buildNumber(String v) {
    final idx = v.indexOf('+');
    if (idx < 0) return null;
    return int.tryParse(v.substring(idx + 1).trim());
  }

  List<int> _numParts(String v) {
    // 取 `+` 之前的主版本部分，再按 `.` 拆数字
    final dotIdx = v.indexOf('+');
    final core = dotIdx >= 0 ? v.substring(0, dotIdx) : v;
    final parts = <int>[];
    for (final seg in core.split('.')) {
      final n = int.tryParse(seg.trim());
      if (n == null) return parts;
      parts.add(n);
    }
    return parts;
  }
}
