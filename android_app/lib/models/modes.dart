/// 运动模式与目标 BPM 预设（与 Web 版一致）。
library;

enum ModeId { walk, jog, run, sprint, custom }

class ModeDef {
  final ModeId id;
  final String label;
  final String icon;
  final int rangeLow;
  final int rangeHigh;
  final double defaultBpm;

  const ModeDef({
    required this.id,
    required this.label,
    required this.icon,
    required this.rangeLow,
    required this.rangeHigh,
    required this.defaultBpm,
  });
}

const List<ModeDef> kModes = [
  ModeDef(id: ModeId.walk, label: '走路', icon: '🚶', rangeLow: 100, rangeHigh: 120, defaultBpm: 110),
  ModeDef(id: ModeId.jog, label: '慢跑', icon: '🏃', rangeLow: 120, rangeHigh: 145, defaultBpm: 132),
  ModeDef(id: ModeId.run, label: '跑步', icon: '🏃‍♂️', rangeLow: 145, rangeHigh: 165, defaultBpm: 155),
  ModeDef(id: ModeId.sprint, label: '快跑', icon: '⚡', rangeLow: 165, rangeHigh: 185, defaultBpm: 175),
  ModeDef(id: ModeId.custom, label: '自定义', icon: '🎯', rangeLow: 60, rangeHigh: 220, defaultBpm: 95),
];
