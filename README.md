# RUN BPM · 基于 BPM 的跑步音乐播放器

让音乐跟随你的跑步节奏：上传自己的音乐 → 自动识别 BPM → 选择运动模式 → 推荐合适歌曲 → 变速统一到目标 BPM（保持音高）→ 可选节拍器 → 连续播放。

## 功能

- 🎵 **音乐导入**：一次上传多首 MP3 / WAV / M4A / AAC / OGG / FLAC（单文件上限默认 100 MB，可配置）
- 🔍 **BPM 分析**：基于 librosa 的异步分析，显示分析进度（"正在分析 2/5"）与 BPM 可信度；内置 2 倍/0.5 倍八度修正与 1.5 倍三连音脉冲修正；分析失败可重试，也支持**手动修改 BPM**
- 🏃 **运动模式**：走路 100–120 / 慢跑 120–145 / 跑步 145–165 / 快跑 165–185 / 自定义，目标 BPM 可微调
- ⭐ **推荐**：按 `|原BPM − 目标BPM|` 距离排序并给出星级，规则透明可解释
- ⏩ **BPM 统一**：FFmpeg `atempo` 时间拉伸，**保持音高不变**；结果按 歌曲+目标BPM 缓存，避免重复处理
- 🥁 **节拍器**：浏览器端 Web Audio 实现，与目标 BPM 对齐，自动对准歌曲的实际节拍相位（点击落在音乐的拍点上）；带**相位微调滑杆**（±50% 拍距，点击偏早/偏晚时手动校准，偏好本地保存）与音量调节
- ▶️ **播放器**：播放/暂停、上一首/下一首、进度拖动、音量、当前/目标 BPM 显示、播放列表连续播放
- 📱 **移动端可用**：响应式布局，跑步时用手机操作

> 隐私与版权边界：只处理用户自己上传的音乐文件，用于本地分析与播放；不抓取任何音乐平台的受保护音频，不对外公开传播。

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | React 19 + TypeScript + Vite 8 + Tailwind CSS 4 |
| 后端 | Python 3.12 + FastAPI + Uvicorn |
| 数据库 | SQLite（标准库 sqlite3，无 ORM） |
| BPM 检测 | librosa（成熟音频分析库） |
| 时间拉伸 | FFmpeg `atempo`（经 imageio-ffmpeg 自带静态构建，也可用系统 ffmpeg） |

## 环境要求

- Python ≥ 3.11
- Node.js ≥ 20（仅开发时需要）
- FFmpeg **无需手动安装**：默认使用 `imageio-ffmpeg` 包自带的静态构建；也可通过 `RUNBPM_FFMPEG_PATH` 指定或安装到 PATH

## 安装

```bash
# 1. 后端依赖
cd backend
python -m pip install -r requirements.txt

# 2. 前端依赖（仅开发时需要）
cd ../frontend
npm install
```

## 启动

### 方式 A：开发模式（前后端分离，推荐开发时用）

```bash
# 终端 1：后端
cd backend
python run.py            # http://127.0.0.1:8000  (API + 管理界面)

# 终端 2：前端（热更新）
cd frontend
npm run dev              # http://localhost:5173
```

### 方式 B：生产模式（单端口演示，无需 Node）

```bash
cd frontend
npm run build            # 生成 frontend/dist
cd ../backend
python run.py            # http://127.0.0.1:8000 直接访问完整应用
```

## 使用方法

1. 打开页面，拖拽或点击上传若干首音乐（支持多选）
2. 等待 BPM 分析完成（页面显示"正在分析 n 首…"，完成后每首歌显示 BPM 与可信度）
   - 分析失败可点"重试"；检测不准可直接点击 BPM 数字手动修改
3. 选择运动模式（或自定义），微调目标 BPM
4. 点"生成推荐"，勾选想要的歌曲
5. 点"变速并播放"：后端将歌曲统一到目标 BPM（保持音高），完成后自动连续播放
6. 播放器中可开关节拍器、调节拍器音量、拖动进度、切换上下首；节拍器会自动对准当前歌曲的节拍相位（BPM 分析时记录首个节拍位置，变速后换算到处理后音频的时间轴）

## 音频处理依赖

| 用途 | 工具 | 说明 |
|---|---|---|
| BPM / 节拍检测 | librosa | 节拍追踪 + 自相关精修，误差约 ±0.5% |
| 时间拉伸（保持音高） | FFmpeg `atempo` | 比例 0.5–2.0 自动拆分多级滤波器 |
| 音频元数据 | mutagen | 读取 ID3 标题/歌手 |
| 时长探测 | librosa / ffprobe | 兜底方案 |

`atempo` 是业界成熟的 WSOLA 类实现，变速时保持音高不变，不会产生"花栗鼠"效果。

## 常见问题

- **上传提示"不支持的文件类型"**：仅支持 MP3/WAV/M4A/AAC/OGG/FLAC/OPUS；请确认扩展名。
- **"文件过大"**：默认单文件 100 MB，可用 `RUNBPM_MAX_UPLOAD_MB` 调整。
- **"无法可靠检测 BPM"**：音频过短或节奏过于自由；可重试或手动输入 BPM（20–400）。
- **"处理失败" / 变速失败**：目标与原始 BPM 差距过大（超出 1/3–3 倍）会拒绝以免严重失真；请选择更接近的目标 BPM，或手动修正歌曲 BPM 后重试。
- **浏览器不自动播放**：首次点击"变速并播放"即用户手势，一般不会拦截；如仍被拦截请手动点播放键。
- **节拍器没声音**：节拍器使用 Web Audio，需浏览器支持；开启后请确认音量滑块。
- **ffmpeg 相关报错**：默认使用 imageio-ffmpeg 自带静态构建（无需安装）；若自定义了 `RUNBPM_FFMPEG_PATH`，请确认路径有效。

## 项目结构

```
muzrun/
├── backend/
│   ├── app/
│   │   ├── main.py              # FastAPI 入口、静态托管、健康检查
│   │   ├── config.py            # 配置（环境变量驱动，无硬编码路径）
│   │   ├── database.py          # SQLite 数据层
│   │   ├── schemas.py           # Pydantic 模型
│   │   ├── api/
│   │   │   ├── songs.py         # 上传/列表/详情/改BPM/删除/重新分析
│   │   │   ├── recommend.py     # 推荐（距离排序 + 星级）
│   │   │   ├── process.py       # 变速任务（单个/批量/查询）
│   │   │   └── audio.py         # 原始/处理后的音频流
│   │   └── services/
│   │       ├── bpm.py           # librosa BPM 检测 + 八度/脉冲修正
│   │       ├── ffmpeg_tools.py  # FFmpeg 封装、atempo 链
│   │       ├── processor.py     # 变速 + 磁盘缓存
│   │       └── tasks.py         # 异步任务队列（限并发）
│   ├── scripts/
│   │   ├── gen_test_audio.py    # 生成已知 BPM 的合成测试音频
│   │   └── acceptance_test.py   # 端到端验收：处理→重检 BPM
│   ├── requirements.txt
│   └── run.py
├── frontend/
│   ├── src/
│   │   ├── App.tsx              # 页面编排与状态
│   │   ├── api.ts               # REST 客户端
│   │   ├── types.ts             # 类型与运动模式定义
│   │   ├── lib/metronome.ts     # Web Audio 节拍器（前瞻调度）
│   │   └── components/          # 上传/歌单/模式/推荐/播放器
│   ├── vite.config.ts           # 含 /api 代理到 8000
│   └── package.json
├── .env.example
└── README.md
```

## API 一览

```
POST /api/songs/upload                上传（multipart，多文件）
GET  /api/songs                       歌曲列表（含分析状态）
GET  /api/songs/{id}                  歌曲详情
PATCH /api/songs/{id}                 手动修改 BPM / 标题 / 歌手
POST /api/songs/{id}/analyze          触发（重新）BPM 分析
DELETE /api/songs/{id}                删除歌曲及所有处理产物
POST /api/recommend                   推荐 {target_bpm, song_ids?}
POST /api/process                     处理单首 {song_id, target_bpm}
POST /api/process/batch               批量处理 {song_ids, target_bpm}
GET  /api/process/tasks/{id}          查询处理任务状态
GET  /api/audio/{id}                  原始音频流（支持 Range 拖动）
GET  /api/audio/{id}/processed?target_bpm=  处理后的音频流
GET  /api/health                      健康检查（含 ffmpeg 状态）
```

## 数据模型

- **songs**：id / filename / title / artist / duration / original_bpm / bpm_confidence / bpm_status / bpm_error / file_path / mime_type / size / created_at
- **processing_tasks**：id / song_id / target_bpm / status / error / output_path / created_at / updated_at

处理结果缓存键为 `song_id + target_bpm`，重复请求直接复用，不会反复跑 FFmpeg。
