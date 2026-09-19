# Bunbeat BPM 引擎 · Kotlin 移植版

`android_app/lib/services/` 里 Dart BPM 引擎的 Kotlin 等价实现。目标是**完全复刻**：
同样的算法、同样的常数、同样的浮点运算顺序与结合方式，甚至同样的 Dart 特有语义
（欧几里得取模、`toStringAsFixed` 的十进制量化、半数远离 0 的取整……）。

不依赖 Gradle、不依赖网络：编译用 IntelliJ IDEA 自带的 Kotlin 编译器。

---

## 1. 结论（可复核）

对 **82 个音频文件 / 462 次「文件 × 引擎」运行**与 Dart 原实现逐一数值比对：

| 字段 | 结果 |
| --- | --- |
| `bpm`、`duration`、`beatOffset` | **逐位相同**（rel_err = 0.000e+00） |
| `beatTimes` / `beatMaps['grid']` / `beatMaps['snap']` | **逐位相同**（长度、和、平方和、位置加权和、min、max、前 4 项全部 0 偏差） |
| `error` 文案、`beatMaps` 键顺序 | **完全相同** |
| `confidence`、`phaseReliability` | 1e-15 ~ 4e-13 相对偏差（末位 1~2 ulp，见 §6） |

即：**所有下游会用到的结果（BPM 数值、整曲拍点时间轴、相位、错误文案）逐位一致**，
仅两个「置信度类」标量存在浮点末位差异。

---

## 2. 目录结构

```
kotlin_bpm/
├─ src/main/kotlin/com/bunbeat/bpm/
│  ├─ Fft.kt          fft.dart 的移植（复数、nextPow2、fft、ifft、位反转基-2 蝶形）
│  └─ BpmAnalyzer.kt  bpm_analyzer.dart 的移植（1429 行，6 个引擎 + 全部辅助函数）
├─ tools/             ← 仅为验证服务的 harness，不属于移植库
│  ├─ ParityMain.kt   裸 PCM 入口：对每个 WAV 跑 6 个引擎，输出与 Dart golden 同构的 JSON
│  ├─ WavProbeMain.kt App 入口探针：调用 analyzeWavFile，覆盖立体声/24bit/坏容器
│  └─ compare.py      数值比对器（逐字段相对偏差 + 是否逐位相同）
├─ build.ps1          编译（IntelliJ 自带 kotlinc，无需 Gradle）
└─ run_parity.ps1     编译 → 跑 manifest → 与 Dart golden 数值比对
```

### 与 Dart 的接口对应

| Dart | Kotlin |
| --- | --- |
| `BpmAnalyzer.analyzeWavFile(path)` | `BpmAnalyzer.analyzeWavFile(path)`（同步；Dart 侧用 `compute()` 丢后台 isolate，Kotlin 侧由调用方放到工作线程） |
| `BpmAnalyzer.analyzePcm(samples, sampleRate:)` | `BpmAnalyzer.analyzePcm(samples, sampleRate)` |
| `analyzeLibrosaPcm` … `analyzeCombFoldPcm` | 同名 6 个函数 |
| `kSampleRate` / `kActiveAlgorithm` | `BpmAnalyzer.kSampleRate` / `BpmAnalyzer.kActiveAlgorithm`（同样为 **5**） |
| `BpmResult` | `BpmResult`（同名字段 + 默认值） |

算法 6（脉冲梳折叠）也已移植，与 Dart 一样保留但不激活。

---

## 3. 编译与运行

本机 shell 是 **Windows PowerShell 5.1**（没有 pwsh 7），默认执行策略禁止运行脚本，
且会按 ANSI 代码页读取文件，所以每个命令前都要放开策略：

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
cd C:\Users\123\Desktop\muzrun\kotlin_bpm
.\build.ps1                    # 只编译 -> build\parity.jar
```

`build.ps1` 绕开了 IDEA 里那个坏掉的 `kotlinc.bat`
（`ClassNotFoundException: org.jetbrains.kotlin.preloading.Preloader`），
直接调用：

```
java -cp "<IDEA>\plugins\Kotlin\kotlinc\lib\*.jar" `
     org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -cp kotlin-stdlib.jar -d parity.jar <sources>
```

跑一遍完整比对：

```powershell
.\run_parity.ps1 -SkipBuild
.\run_parity.ps1 -SkipBuild -Manifest ...\manifest_holdout.json -Out ...\actual_kotlin_holdout_v2.json -Expected ...\expected_dart_holdout.json
.\run_parity.ps1 -SkipBuild -Manifest ...\manifest_third.json   -Out ...\actual_kotlin_third.json   -Expected ...\expected_dart_third.json
.\run_parity.ps1 -SkipBuild -Main com.bunbeat.bpm.WavProbeMainKt -Manifest ...\probe.json -Out ...\actual_kotlin_probe.json -Expected ...\expected_dart_probe.json
```

---

## 4. 验证方法（为什么"复刻"是可证的，而不是自说自话）

判据不是"看起来一样"，而是**数值比对器 + 预先存在的 Dart golden**：

1. **golden 由 Dart 侧独立生成**（`android_app/test/_parity_dump_test.dart`，
   跑的是 App 自己的 `bpm_analyzer.dart`），在 Kotlin 源码之前就落盘，
   时间戳/哈希可查 → 移植版无法"顺着答案写"。
2. **比对器** `kotlin_bpm/tools/compare.py` 逐字段算相对偏差，容差 rel 1e-9，
   任一字段超限即 exit 1 并打印 `NOT FAITHFUL` 与具体文件名。
   它还会输出每个字段是否 **rel_err == 0（逐位相同）**。

> 比对所需的清单与 golden 放在 `research_tmp/parity/`（`manifest*.json`、
> `expected_dart*.json`、`actual_kotlin*.json`）——它们依赖本地音频曲库
> （`research_tmp/realmusic/`，2 万多秒的真实曲目解码结果，体积大且涉版权），
> 所以按项目惯例留在 scratch 目录、不进 git。两个 Dart harness
> （`android_app/test/_parity_dump_test.dart`、`_wav_probe_test.dart`）在清单
> 不存在时会自动 skip，不影响干净检出上的 `flutter test`。
3. **互不重叠的四套样本**，其中第三套是专门为了排除"拟合"而新造的：
   - **dev（32 文件）**：17 首合成电子乐 + 15 首真实曲目；
   - **holdout（16 文件）**：真实曲目，与 dev 选取下标错开，零重叠；
   - **third（28 文件）**：全新的**第二代合成器**（新 BPM、纯反拍 hi-hat、军鼓反拍、
     无 hi-hat、swing、±6ms 人性化抖动）+ 退化输入（10s 纯静音、直流偏移、
     削波方波、仅 30000 样本的奇数长度 data 块、`data` 前夹 `LIST` 块）+
     11 首前两套都没用过的真实曲目；与 dev/holdout **零重叠**；
   - **probe（6 文件）**：走 App 真正入口 `analyzeWavFile`，覆盖立体声交织、
     24-bit 载荷、完全没有 `data` 块、短于 2 秒、正好 2 秒边界。
4. **指纹含 `wsum = Σ x[i]·(i+1)`**（位置加权精确累加和）：
   只比 `sum/sq` 是聚合量、理论上可能被抵消掉，加上位置权重后，
   任何单个元素出现 1 ulp 差异都必然体现在指纹里。
5. **harness 改动被单独审计**：往指纹里加 `wsum` 后重新生成的四份 golden，
   与改动前的 v1 版本在所有旧字段上**完全一致**（`_chk.py` 校验），
   说明该改动是纯增量，且 Dart 输出可复现。

真实音频的来源：`research_tmp/realmusic/src_*.wav` 是从用户本地音乐库用 librosa
解码出的 232 首真实曲目的前 60 秒（22050 Hz 单声道）；合成曲目由
`synth_elec.py`（第一代）与 `synth_edge.py`（第二代）生成，带精确 BPM 真值。

---

## 5. Dart → Kotlin 的语义坑（复刻时必须逐条对齐）

| Dart | 坑 | Kotlin 处理 |
| --- | --- | --- |
| `a % b`（double） | 欧几里得取模，正除数时结果非负 | `dmod()` |
| `a % b`（int） | 结果符号跟除数 | `Math.floorMod` |
| `x.round()`（double） | 半数**远离** 0 | `dartRound()`（**不是** `roundToInt`，后者半数取偶） |
| `double.parse(x.toStringAsFixed(6))` | C 的 `%.*f` 十进制量化再解析 | `dartRoundFixed()`：`BigDecimal(v)` 精确展开 + HALF_UP/HALF_DOWN |
| `a / b`（int/int） | 是 double 除法 | 显式 `.toDouble()`（如 `kHop / kSampleRate.toDouble()`） |
| `-0.5 * x * x` | 左结合；而 `-0.5 * pow(x,2)` 带括号 | 保留原括号结构，不"化简" |
| `(a, b)` record | 无对应 | `Pair` |
| `List<double>.filled(n, v)` | 定长、按引用语义不同 | `DoubleArray(n) { v }` |
| `beatMaps` 键序 `grid` → `snap` | Dart `Map` 是插入序 | `LinkedHashMap` 保插入序 |
| `math.ln2` / `math.ln10` | 精确双精度字面量 | 硬编码 `0.6931471805599453` / `2.302585092994046` |
| `.clamp(a, b)` | 越界取边界 | `coerceIn(a, b)` |
| `Map['k']` 缺失 → `null` | 可空语义 | 显式可空 + `?.` |

`Fft.kt` 里额外提供了 `fftInPlace` / `ifftInPlace`，避免在热点路径上反复装箱。

---

## 6. 唯一的已知差异，以及原因

`confidence` 与 `phaseReliability` 在 1e-15 ~ 4e-13 量级不一致（其余 30 个字段逐位相同）。

原因是这两个量由 `exp` / `ln` / `sin` / `cos` / `pow` 这类超越函数的结果导出，
而 **Dart VM 与 JVM 的 libm 实现不同**（同为 IEEE-754 双精度，但末位可差 1~2 ulp）。
误差只在这两个标量上出现、且不参与 `bpm` 与拍点时间轴的计算，
所以对 App 行为没有影响；若将来需要绝对逐位，可对这两处改用
`StrictMath`（会牺牲一点速度）或自行实现对应 libm 例程。

---

## 7. 后续

当前交付是**纯 Kotlin/JVM 库**（与 Dart 引擎一一对应、可独立编译验证）。
若要让 Bunbeat 走原生实现，下一步可选：

- 打包成 Android library 模块（AGP 需要联网拉依赖，本机当前不具备条件）；
- 或直接把这几个 `.kt` 拷进原生工程，用 `analyzeWavFile()` 替换 `BpmAnalyzer.analyzeWavFile()`。
