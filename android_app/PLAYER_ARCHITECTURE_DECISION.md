# 播放器架构决策（基于 3 份开源调研报告的结论）

> 结论：**保留当前手写队列（koel 路线 / 方案 B）+ 每首变速加载**，不做高风险重写。

## 为什么保留手写队列而不是切换到 just_audio 官方 ConcatenatingAudioSource（方案 A）

调研（`research_tmp/player_architecture_research.md`）显示两条成熟路线都成立：

- **方案 A（官方 `setAudioSources`/`ConcatenatingAudioSource`）**：原生 loop/shuffle/next/gapless/后台，代码少。**劣势**：变速是全局的（`setSpeed` 作用于整个 player），每首不同速需要额外用 `currentIndexStream` 粘合 `setSpeed`，且 auto-advance 时旧速残留几帧；官方 shuffle/loop 是平台黑盒（issues #460 等）。
- **方案 B（koel 路线）**：手写 `List<PlaylistItem>` 队列 + 每次只加载一首 + `completed` 手动切歌。**正好符合「每首不同变速」的核心需求**，且可完全单测。

**RUN BPM 是 BPM 跑步播放器，核心功能 = 每首歌按 targetBpm/originalBpm 单独变速。** 这正是方案 A 的短板、方案 B 的长项。当前 `QueueService` 的循环/随机/上下一首已用 25 个单元测试覆盖边界（off/all/one、随机不越界、单首原地、末尾回绕）。因此**沿用方案 B**。

当前实现与 koel/player 参考实现的结构对照：
| 职责 | RUN BPM | koel/player |
|---|---|---|
| 队列状态 | `QueueService`（ChangeNotifier） | `QueueHandler` + 自维护 `List<MediaItem>` |
| 单曲加载 | `AudioPlayerService.play()` → `setUrl + setSpeed` | `_playAtIndex` → `setFilePath/setUrl` |
| 自然播完 | `processingStateStream.completed` → `q.onEnded()` → `_loadCurrent` | `processingStateStream` → `skipToNext`/`seek(0)` |
| 列表循环 | 手写在 `onEnded`（末尾回 0） | 手写在 `skipToNext` |
| 单曲循环 | `LoopMode.one` → `seek(0)+play` | 透传 `LoopMode.one` |
| 随机 | `_randomNext()` 手写防重 | `items.shuffle()` 整队替换 |

## 阶段改进（低风险、防 bug，已做/将做）

1. **持久化缓存**（已完成并端到端验证）：`AnalysisCache` 把每首分析结果放应用支持目录，size/mtime 失效；`_loadSong` 缓存命中即秒开；手动 BPM 记 `manual=true` 不被自动覆盖；`restoreLastFolder` 启动自动恢复上次文件夹。→ 满足「不要每次打开就重新解析」。
2. **单曲加载容错**（见下文）：给 `AudioPlayerService.play` 加非抛异常版本 `tryPlay`，载入失败自动跳到下一首，避免坏文件卡死整条播放（对应 koel 的 MAX_ERROR_COUNT/出错跳过）。

## 明确不做的高风险项（避免“写一堆 bug 再 debug”）

- 不重写为 `ConcatenatingAudioSource` 方案 A（会破坏每首精确变速，且引入平台黑盒行为）。
- 不迁移 audio_service/通知栏（当前仅本地前台播放，无后台/锁屏需求）。
- 节拍器不立刻迁移 flutter_soloud（当前池化已工作；引擎时钟排程是更优路线，作为后续可选优化，见 `metronome_audio_research.md`）。
