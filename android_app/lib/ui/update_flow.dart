import 'package:flutter/material.dart';

import '../services/update_service.dart';

/// 联网更新的 UI 流程：检查 → 确认 → 下载(带进度) → 交由系统安装器。
/// 供「设置页检查更新」按钮与「启动自动检测」共用。
class UpdateFlow {
  /// 检查一次更新并根据结果给出提示。
  /// [manual] 为 true 表示用户主动点击（检查失败会提示），false 表示后台自动。
  static Future<void> checkAndPrompt(BuildContext context,
      {bool manual = true}) async {
    final messenger = ScaffoldMessenger.of(context);
    final result = await UpdateService().checkForUpdate();
    if (!context.mounted) return;

    switch (result.status) {
      case UpdateStatus.available:
        final info = result.info!;
        final go = await showDialog<bool>(
          context: context,
          builder: (ctx) => AlertDialog(
            title: const Text('发现新版本'),
            content: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('检测到新版本 v${info.latestVersion}'),
                  if (info.releaseNotes != null) ...[
                    const SizedBox(height: 8),
                    Text('更新内容：\n${info.releaseNotes}',
                        style: Theme.of(ctx)
                            .textTheme
                            .bodySmall
                            ?.copyWith(color: Theme.of(ctx).colorScheme.outline)),
                  ],
                ],
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(ctx, false),
                child: const Text('稍后'),
              ),
              FilledButton(
                onPressed: () => Navigator.pop(ctx, true),
                child: const Text('立即更新'),
              ),
            ],
          ),
        );
        if (go == true && context.mounted) {
          await _downloadAndInstall(context, UpdateService(), info);
        }
      case UpdateStatus.upToDate:
        if (manual) {
          messenger.showSnackBar(
            const SnackBar(content: Text('已是最新版本')),
          );
        }
      case UpdateStatus.failed:
        if (manual) {
          messenger.showSnackBar(
            const SnackBar(content: Text('检查更新失败，请确认网络后再试')),
          );
        }
    }
  }

  /// 下载并安装（下载对话框内显示进度，下载完成后自动拉起系统安装器）。
  static Future<void> _downloadAndInstall(
    BuildContext context,
    UpdateService svc,
    UpdateInfo info,
  ) async {
    // 同步锁：同一时间只有一个下载对话框。
    final navigator = Navigator.of(context, rootNavigator: true);
    // 用单个 ValueNotifier 承载下载状态，进度回调里更新它会触发对话框重建，
    // 从而实时刷新进度条与 MB 数（此前只改了局部变量，UI 不重建导致一直显示"准备下载"）。
    final state = ValueNotifier<_DownloadState>(const _DownloadState(
      received: 0,
      total: 0,
      cancelled: false,
      failed: false,
    ));

    showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => ValueListenableBuilder<_DownloadState>(
        valueListenable: state,
        builder: (ctx, s, _) {
          final progress = s.total > 0 ? s.received / s.total : null;
          return AlertDialog(
            title: const Text('正在下载更新'),
            content: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                LinearProgressIndicator(
                  value: s.failed || progress == null ? null : progress,
                ),
                const SizedBox(height: 12),
                Text(
                  s.failed
                      ? '下载失败，请稍后再试'
                      : progress != null
                          ? '${(s.received / 1024 / 1024).toStringAsFixed(1)} MB / '
                              '${(s.total / 1024 / 1024).toStringAsFixed(1)} MB'
                          : '准备下载…',
                  style: Theme.of(ctx).textTheme.bodySmall,
                ),
              ],
            ),
            actions: [
              TextButton(
                onPressed: () => state.value = _DownloadState(
                  received: s.received,
                  total: s.total,
                  cancelled: true,
                  failed: s.failed,
                ),
                child: const Text('取消'),
              ),
            ],
          );
        },
      ),
    );

    try {
      final path = await svc.downloadApk(
        info,
        (r, t) {
          state.value = _DownloadState(
            received: r,
            total: t == 0 ? r : t,
            cancelled: state.value.cancelled,
            failed: state.value.failed,
          );
        },
      );
      if (state.value.cancelled) return;
      if (navigator.mounted) navigator.pop(); // 关闭下载对话框
      if (!context.mounted) return;
      final ok = await svc.installApk(path);
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            ok ? '已拉起安装程序，请按系统提示完成安装' : '无法打开安装程序',
          ),
        ),
      );
    } catch (_) {
      state.value = _DownloadState(
        received: state.value.received,
        total: state.value.total,
        cancelled: state.value.cancelled,
        failed: true,
      );
      // 让下载对话框显示失败态；2 秒后自动关闭。
      await Future<void>.delayed(const Duration(seconds: 2));
      if (navigator.mounted) navigator.pop();
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('下载失败，请稍后再试')),
      );
    }
  }
}

/// 下载对话框的瞬时状态，驱动 UI 重建以实时显示进度。
class _DownloadState {
  final int received;
  final int total;
  final bool cancelled;
  final bool failed;
  const _DownloadState({
    required this.received,
    required this.total,
    required this.cancelled,
    required this.failed,
  });
}
