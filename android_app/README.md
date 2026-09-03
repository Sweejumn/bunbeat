# Bunbeat（安卓版）· 本地文件夹离线跑步播放器

这是 **Bunbeat** 的安卓端源码目录。Bunbeat 是原 Web 版「RUN BPM」的安卓离线版：
**直接读取手机本地音乐文件夹**，全部处理（BPM 识别、变速、节拍器、播放）都在设备端完成，
无需联网、无需上传。

> 仓库根目录 README 有整体介绍；本文件聚焦安卓端的工程细节。

---

## 功能

- 📁 **本地文件夹直读**：Android SAF 目录选择器选文件夹后，用 MediaStore（on_audio_query）
  读取其中的 MP3 / WAV / M4A / FLAC / OGG 等音频（Android 10+ 分区存储兼容）。
- 🔍 **设备端 BPM 分析**：纯 Dart FFT + 自相关（移植自 Web 版 librosa 逻辑），后台 isolate 执行不卡 UI，
  含八度 / 脉冲修正；可手动改 BPM、重新检测、BPM ×2。
- 🏃 **运动模式**：走路 / 慢跑 / 跑步 / 快跑 / 自定义，目标 BPM 可滑杆微调或手输。
- ⭐ **推荐**：按 `|原BPM − 目标BPM|` 距离排序，颜色分级 + 方向箭头 + 差距百分比。
- ✅ **多选操作**：长按多选后，可同时对所选歌曲执行加入播放列表 / 归档 / 重新检测 / BPM ×2。
- ⏩ **变速（保持音高）**：ExoPlayer `speed`（time-stretch），0.5–2.0 倍内统一到目标 BPM。
- 🥁 **节拍器**：与真实拍点（或等距网格）逐拍对齐的前瞻调度器，多音效、可调音量与打拍校准。
- 📏 **拍点标尺 + 偏差微调**：主题色拍点标尺随播放实时滚动，偏差滑杆整体平移，所见即所听。
- 📥 **播放列表（队列）**：跳歌 / 长按拖动排序 / 删除单首 / 清空包装。
- 🔄 **检查更新**：从 GitHub Release 拉取最新 APK 自装。
- 🎨 **外观**：跟随系统 / 浅色 / 深色 + 主题色，实时生效。

---

## 技术栈

| 层 | 技术 |
|---|---|
| 框架 | Flutter (Dart) 3.47 / Dart 3.13 |
| 状态管理 | Provider |
| 播放 / 变速 | just_audio（ExoPlayer，变速默认保持音高） |
| 文件夹选择 | file_picker（Android SAF 目录选择器） |
| 扫描音频 | on_audio_query（MediaStore） |
| 音频解码（BPM 用） | ffmpeg_kit_flutter_audio → PCM 后本地分析 |
| 系统要求 | minSdk 24 / compileSdk & targetSdk 37 |

---

## 构建与发布

```bash
cd android_app
flutter pub get
flutter build apk --release   # 产物：build/app/outputs/flutter-apk/app-release.apk
```

接真机后 `flutter install` 或直接复制 APK 安装。**新 APK 的 build 号（versionCode / +N）必须高于已装版本**，
否则 Vivo 等系统会拒绝安装。

发布流程（维护者）：`flutter analyze`（0 error）+ `flutter test`（全绿）→ 递增 `0.1.0+N` →
构建 release APK → 提交推送 → 在 GitHub 创建 `v0.1.0+N` 发行版并上传 `Bunbeat-v0.1.0.N.apk`。
App「检查更新」读取仓库 `releases/latest`。

---

## 目录结构

```
android_app/
├── pubspec.yaml               # 依赖与版本号
├── android/                   # Android 平台脚手架（gradle / manifest / 图标）
└── lib/
    ├── main.dart              # 入口 + Provider 装配 + 更新自检
    ├── models/                # Song / modes（运动模式）
    ├── services/
    │   ├── audio_reader.dart        # 文件夹选择 + MediaStore 扫描
    │   ├── bpm_analyzer.dart        # 设备端 BPM（FFT + 自相关）
    │   ├── library_service.dart      # 曲库 + 分析 + 推荐 + 归档 + 播放列表构建
    │   ├── queue_service.dart        # 播放队列
    │   ├── audio_player_service.dart # just_audio 播放 + 变速
    │   ├── metronome.dart            # 节拍器（前瞻调度）
    │   └── update_service.dart       # GitHub Release 检查更新
    └── ui/
        ├── home_page.dart     # 底部导航（曲库 / 推荐 / 播放）
        ├── library_page.dart  # 选文件夹 + 歌曲列表 + 长按多选 + 归档
        ├── recommend_page.dart# 运动模式 + 推荐 + 入队
        ├── player_page.dart   # 播放器 + 节拍器 + 播放列表
        ├── settings_page.dart # 设置 + 检查更新
        └── help_dialog.dart   # 使用说明（单页 / 设置页分段）
```

---

## 常见问题

- **打了文件夹却 0 首**：Android 13+ 需授予「音乐和音频」权限（`READ_MEDIA_AUDIO`），授权后重选文件夹。
- **BPM 不准**：FFT 自相关是简化实现，对自由/短音频可能不准，手动改 BPM 兜底。
- **变速失真**：超出 0.5–2.0 会失真，代码已限速；选更接近原 BPM 的目标。
- **节拍器无声**：播放页开开关并调拍子音量，首次打开会生成嗒声文件。

---

## 与 Web 版的关系

- **Web 版**：上传 → 后端 librosa 分析 → FFmpeg 变速 → 浏览器播放（需 FastAPI + SQLite）。
- **安卓版（本目录）**：本地读文件夹 → 设备端 FFT 分析 → ExoPlayer 变速 → 本地播放，全离线。

两者共享相同的运动模式、推荐规则与拍点对齐理念；安卓版为当前主力，Web 源码（`backend/`、`frontend/`）为历史保留。
