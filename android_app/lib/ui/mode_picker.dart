/// 运动模式选择器（与 Web 版 ModePicker 对齐）。
///
/// 主要交互是一条长滑块，覆盖恰好四个运动区间（100–185）：
/// 拖动滑块跨区间会自动切换运动模式；区间外（<100 或 >185）在右侧
/// 输入框手动输入（自定义）。Quick-jump 芯片点击跳到该区间默认 BPM。
library;

import 'package:flutter/material.dart';

import '../models/modes.dart';

/// 滑块覆盖恰好四个运动区间；区间外只能手动输入（自定义）。
const int _kMin = 100;
const int _kMax = 185;
const int _kManualMin = 40;
const int _kManualMax = 300;

/// 四个区间（与 Web ModePicker 一致，仅显示这四段颜色）。
class _Zone {
  final int from;
  final int to;
  final Color color;
  final String label;
  final String icon;
  const _Zone(this.from, this.to, this.color, this.label, this.icon);
}

const List<_Zone> _zones = [
  _Zone(100, 120, Color(0xFF38BDF8), '走路', '🚶'),
  _Zone(120, 145, Color(0xFF34D399), '慢跑', '🏃'),
  _Zone(145, 165, Color(0xFFFBBF24), '跑步', '🏃‍♂️'),
  _Zone(165, 185, Color(0xFFF87171), '快跑', '⚡'),
];

/// 根据 BPM 判定所属模式（与 Web modeFromBpm 一致）。
ModeId modeFromBpm(double bpm) {
  if (bpm >= 100 && bpm < 120) return ModeId.walk;
  if (bpm >= 120 && bpm < 145) return ModeId.jog;
  if (bpm >= 145 && bpm < 165) return ModeId.run;
  if (bpm >= 165 && bpm <= 185) return ModeId.sprint;
  return ModeId.custom;
}

class ModePicker extends StatefulWidget {
  final double bpm;
  final ValueChanged<double> onChanged;
  const ModePicker({super.key, required this.bpm, required this.onChanged});

  @override
  State<ModePicker> createState() => _ModePickerState();
}

class _ModePickerState extends State<ModePicker> {
  late TextEditingController _draft;
  final FocusNode _focusNode = FocusNode();

  @override
  void initState() {
    super.initState();
    _draft = TextEditingController(text: widget.bpm.round().toString());
  }

  @override
  void didUpdateWidget(covariant ModePicker old) {
    super.didUpdateWidget(old);
    // 外部 BPM 变化时同步输入框草稿（仅在非聚焦聚焦文本时避免覆盖用户输入）。
    if (old.bpm != widget.bpm && !_focusNode.hasFocus) {
      _draft.text = widget.bpm.round().toString();
    }
  }

  @override
  void dispose() {
    _draft.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  double get _bpm => widget.bpm;

  void _handleSlider(double v) {
    widget.onChanged(v);
  }

  void _handleChip(ModeId m) {
    final def = kModes.firstWhere((x) => x.id == m);
    if (def == kModes.last && m == ModeId.custom) return;
    widget.onChanged(def.defaultBpm.toDouble());
  }

  void _commitDraft(String raw) {
    final v = double.tryParse(raw.trim());
    if (v == null) {
      _draft.text = _bpm.round().toString();
      return;
    }
    final c = v.round().clamp(_kManualMin, _kManualMax).toDouble();
    widget.onChanged(c);
    _draft.text = c.round().toString();
  }

  @override
  Widget build(BuildContext context) {
    final mode = modeFromBpm(_bpm);
    final activeDef = kModes.firstWhere((x) => x.id == mode);
    final isCustom = mode == ModeId.custom;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Text('今天想怎么跑？', style: Theme.of(context).textTheme.titleMedium),
            const Spacer(),
            SizedBox(
              width: 84,
              child: TextField(
                controller: _draft,
                focusNode: _focusNode,
                keyboardType: TextInputType.number,
                textAlign: TextAlign.right,
                style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
                decoration: const InputDecoration(
                  isDense: true,
                  border: OutlineInputBorder(),
                  contentPadding: EdgeInsets.symmetric(horizontal: 8, vertical: 6),
                ),
                onChanged: (raw) {
                  final v = double.tryParse(raw.trim());
                  if (raw.trim().isNotEmpty &&
                      v != null &&
                      v == v.roundToDouble() &&
                      v >= _kManualMin &&
                      v <= _kManualMax) {
                    widget.onChanged(v);
                  }
                },
                onSubmitted: (raw) => _commitDraft(raw),
              ),
            ),
            const SizedBox(width: 6),
            const Text('BPM', style: TextStyle(color: Colors.white54)),
          ],
        ),
        const SizedBox(height: 10),
        // 当前模式横幅（放在四个快捷芯片上方）。
        Container(
          width: double.infinity,
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.surfaceContainerHighest,
            borderRadius: BorderRadius.circular(10),
          ),
          child: Text(
            isCustom
                ? '${activeDef.icon} ${activeDef.label} · 区间外自定义（手动输入）'
                : '${activeDef.icon} ${activeDef.label} · ${activeDef.rangeLow}–${activeDef.rangeHigh} BPM',
            style: const TextStyle(fontSize: 13),
          ),
        ),
        const SizedBox(height: 10),
        // Quick-jump 芯片（走路/慢跑/跑步/快跑）：放在滑块上方，便于单手快速选模式，
        // 也让滑块相对页面居中以方便拖动。
        Row(
          children: kModes.where((m) => m.id != ModeId.custom).map((m) {
            final selected = mode == m.id;
            return Expanded(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 3),
                child: InkWell(
                  borderRadius: BorderRadius.circular(10),
                  onTap: () => _handleChip(m.id),
                  child: Container(
                    padding: const EdgeInsets.symmetric(vertical: 8),
                    decoration: BoxDecoration(
                      color: selected
                          ? Theme.of(context).colorScheme.primary.withValues(alpha: 0.18)
                          : Theme.of(context).colorScheme.surfaceContainerHighest,
                      borderRadius: BorderRadius.circular(10),
                      border: Border.all(
                        color: selected
                            ? Theme.of(context).colorScheme.primary
                            : Theme.of(context).colorScheme.outlineVariant,
                      ),
                    ),
                    child: Column(
                      children: [
                        Text(m.icon, style: const TextStyle(fontSize: 16)),
                        const SizedBox(height: 2),
                        Text(m.label, style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600)),
                      ],
                    ),
                  ),
                ),
              ),
            );
          }).toList(),
        ),
        const SizedBox(height: 12),
        _buildSlider(),
        const SizedBox(height: 2),
        _buildZoneLabels(),
        const SizedBox(height: 6),
      ],
    );
  }

  /// 彩色区间滑块：滑块轨道透明，背后绘制四段区间色块。
  Widget _buildSlider() {
    return SizedBox(
      height: 34,
      child: Stack(
        alignment: Alignment.center,
        children: [
          // 四段彩色区间（端到端）。
          Positioned.fill(
            child: ClipRRect(
              borderRadius: BorderRadius.circular(6),
              child: Row(
                children: [
                  for (final z in _zones)
                    Expanded(
                      flex: z.to - z.from,
                      child: Container(color: z.color),
                    ),
                ],
              ),
            ),
          ),
          // 滑块（轨道透明，只保留拖动交互与滑块圆点）。
          IgnorePointer(
            ignoring: false,
            child: SliderTheme(
              data: SliderTheme.of(context).copyWith(
                activeTrackColor: Colors.transparent,
                inactiveTrackColor: Colors.transparent,
                trackHeight: 28,
                thumbColor: Theme.of(context).colorScheme.primary,
                overlayColor: Theme.of(context).colorScheme.primary.withValues(alpha: 0.15),
              ),
              child: Slider(
                min: _kMin.toDouble(),
                max: _kMax.toDouble(),
                // 无 divisions：连续值，拖动更跟手（不与推荐页整页重建冲突）。
                value: _bpm.clamp(_kMin.toDouble(), _kMax.toDouble()),
                label: _bpm.round().toString(),
                onChanged: _handleSlider,
              ),
            ),
          ),
        ],
      ),
    );
  }

  /// 区间标签（带图标与 BPM 范围），按区间宽度居中对齐。
  Widget _buildZoneLabels() {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (final z in _zones)
          Expanded(
            flex: z.to - z.from,
            child: Center(
              child: Text(
                '${z.icon} ${z.from}–${z.to}',
                style: const TextStyle(fontSize: 10, color: Colors.white54),
                maxLines: 1,
              ),
            ),
          ),
      ],
    );
  }
}
