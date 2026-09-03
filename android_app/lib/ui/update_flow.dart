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
    var received = 0;
    var total = 0;
    var failed = false;

    final cancel = ValueNotifier<bool>(false);
    showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => ValueListenableBuilder<bool>(
        valueListenable: cancel,
        builder: (ctx, cancelled, _) => AlertDialog(
          title: const Text('正在下载更新'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              LinearProgressIndicator(
                value: failed
                    ? null
                    : total > 0
                        ? received / total
                        : null,
              ),
              const SizedBox(height: 12),
              Text(
                failed
                    ? '下载失败，请稍后再试'
                    : total > 0
                        ? '${(received / 1024 / 1024).toStringAsFixed(1)} MB / '
                            '${(total / 1024 / 1024).toStringAsFixed(1)} MB'
                        : '准备下载…',
                style: Theme.of(ctx).textTheme.bodySmall,
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => cancel.value = true,
              child: const Text('取消'),
            ),
          ],
        ),
      ),
    );

    try {
      final path = await svc.downloadApk(
        info,
        (r, t) {
          received = r;
          total = t;
        },
      );
      if (cancel.value) return;
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
      failed = true;
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
