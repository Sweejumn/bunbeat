import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api } from './api'
import { ModePicker } from './components/ModePicker'
import { PlayerBar } from './components/PlayerBar'
import { RecommendPanel } from './components/RecommendPanel'
import { SongTable } from './components/SongTable'
import { UploadZone } from './components/UploadZone'
import { Metronome } from './lib/metronome'
import { startDiagnostics } from './lib/monitor'
import type { BeatMode, ModeId, PlaylistItem, ProcessTask, Recommendation, Song } from './types'
import { BEAT_MODES, MODES } from './types'

interface Toast {
  msg: string
  kind: 'error' | 'ok'
}

function hasPendingAnalysis(songs: Song[]): boolean {
  return songs.some((s) => s.bpm_status === 'pending' || s.bpm_status === 'analyzing')
}

export default function App() {
  const [songs, setSongs] = useState<Song[]>([])
  const [loading, setLoading] = useState(true)
  const [uploading, setUploading] = useState(false)
  const [toast, setToast] = useState<Toast | null>(null)

  const [mode, setMode] = useState<ModeId>('run')
  const [targetBpm, setTargetBpm] = useState(155)
  const [recs, setRecs] = useState<Recommendation[] | null>(null)

  const [processing, setProcessing] = useState<{ total: number; done: number; failed: string[] } | null>(null)

  const [playlist, setPlaylist] = useState<PlaylistItem[]>([])
  const [currentIndex, setCurrentIndex] = useState(-1)
  const [playing, setPlaying] = useState(false)
  const [currentTime, setCurrentTime] = useState(0)
  const [duration, setDuration] = useState(0)
  const [volume, setVolume] = useState(0.9)
  const [metronomeOn, setMetronomeOn] = useState(true) // 默认开启
  const [metronomeVolume, setMetronomeVolume] = useState(0.7)
  // Manual beat phase fine-tune, in percent of a beat (-50..50), persisted.
  const [phaseNudge, setPhaseNudge] = useState<number>(() => {
    const v = Number(localStorage.getItem('runbpm.phaseNudge') ?? 0)
    return Number.isFinite(v) ? Math.max(-50, Math.min(50, v)) : 0
  })

  const audioRef = useRef<HTMLAudioElement>(null)
  const metronomeRef = useRef<Metronome | null>(null)
  const pollTimer = useRef<number | null>(null)
  // Selectable beat-map mode (固定拍子 default, persisted).
  const [beatMode, setBeatMode] = useState<BeatMode>(() => {
    const v = localStorage.getItem('runbpm.beatMode') ?? 'grid'
    return BEAT_MODES.some((m) => m.id === v) ? (v as BeatMode) : 'grid'
  })
  // Manual beat calibration (null = use auto-detected map).
  const [calBpm, setCalBpm] = useState<number | null>(null)
  const [calFirstBeat, setCalFirstBeat] = useState<number | null>(null)
  // Simple beat ruler (default on).
  const [rulerOn, setRulerOn] = useState(() => localStorage.getItem('runbpm.rulerOn') !== '0')

  // Reset manual calibration when the song changes.
  useEffect(() => {
    setCalBpm(null)
    setCalFirstBeat(null)
  }, [currentIndex])

  const onSetCal = useCallback((bpm: number | null, firstBeat: number | null) => {
    if (bpm != null) setCalBpm(bpm)
    if (firstBeat != null) setCalFirstBeat(firstBeat)
  }, [])

  // Stable accessors (identity must not change per render — otherwise the
  // metronome effect would tear down and restart constantly).
  const getCurrentTime = useCallback(() => audioRef.current?.currentTime ?? 0, [])

  // Runtime diagnostics: logs DOM/heap/fps/pendingClicks every 10s so a
  // resource runaway is easy to locate next time (see lib/monitor.ts).
  useEffect(() => {
    window.__runbpmPendingClicks = () => metronomeRef.current?.pendingCount ?? 0
    const stop = startDiagnostics()
    return () => {
      stop()
      delete window.__runbpmPendingClicks
    }
  }, [])

  const showToast = useCallback((msg: string, kind: 'error' | 'ok' = 'ok') => {
    setToast({ msg, kind })
  }, [])

  // Auto-dismiss toasts.
  useEffect(() => {
    if (!toast) return
    const t = window.setTimeout(() => setToast(null), 4500)
    return () => window.clearTimeout(t)
  }, [toast])

  // ------------------------------------------------------------------ data
  const refreshSongs = useCallback(async () => {
    try {
      const list = await api.listSongs()
      setSongs(list)
      return list
    } catch (e) {
      showToast(e instanceof Error ? e.message : '加载歌曲失败', 'error')
      return []
    }
  }, [showToast])

  useEffect(() => {
    let alive = true
    void (async () => {
      try {
        const list = await api.listSongs()
        if (alive) setSongs(list)
      } catch (e) {
        showToast(e instanceof Error ? e.message : '无法连接后端服务', 'error')
      } finally {
        if (alive) setLoading(false)
      }
    })()
    return () => {
      alive = false
    }
  }, [showToast])

  // Poll analysis status while any song is pending/analyzing.
  useEffect(() => {
    if (!hasPendingAnalysis(songs)) return
    if (pollTimer.current != null) return
    pollTimer.current = window.setInterval(async () => {
      const list = await refreshSongs()
      if (!hasPendingAnalysis(list) && pollTimer.current != null) {
        window.clearInterval(pollTimer.current)
        pollTimer.current = null
      }
    }, 2000)
    return () => {
      if (pollTimer.current != null) {
        window.clearInterval(pollTimer.current)
        pollTimer.current = null
      }
    }
  }, [songs, refreshSongs])

  // ----------------------------------------------------------------- upload
  const handleFiles = useCallback(
    async (files: File[]) => {
      setUploading(true)
      try {
        const created = await api.upload(files)
        showToast(`已上传 ${created.length} 首，开始分析 BPM…`, 'ok')
        await refreshSongs()
      } catch (e) {
        showToast(e instanceof Error ? e.message : '上传失败', 'error')
      } finally {
        setUploading(false)
      }
    },
    [refreshSongs, showToast],
  )

  // ------------------------------------------------------------------- mode
  const handleMode = useCallback((m: ModeId) => {
    setMode(m)
    const def = MODES.find((x) => x.id === m)
    if (def && m !== 'custom') setTargetBpm(def.defaultBpm)
    setRecs(null) // old recommendations no longer match the new target
  }, [])

  const handleTarget = useCallback((bpm: number) => {
    setTargetBpm(bpm)
    setRecs(null)
  }, [])

  // ---------------------------------------------------------- recommendation
  const generateRecs = useCallback(async () => {
    try {
      setRecs(await api.recommend(targetBpm))
    } catch (e) {
      showToast(e instanceof Error ? e.message : '生成推荐失败', 'error')
    }
  }, [targetBpm, showToast])

  // ----------------------------------------------------------------- process
  const handlePlay = useCallback(
    async (songIds: string[]) => {
      if (songIds.length === 0) return
      setProcessing({ total: songIds.length, done: 0, failed: [] })
      try {
        const { tasks } = await api.processBatch(songIds, targetBpm)
        const pending = new Map(tasks.map((t) => [t.id, t]))
        const failed: string[] = []
        const songById = new Map(songs.map((s) => [s.id, s]))
        const doneTasks: ProcessTask[] = []

        const tick = async (): Promise<void> => {
          for (const [id] of Array.from(pending)) {
            const t = await api.getTask(id)
            if (t.status === 'done') {
              doneTasks.push(t)
              pending.delete(id)
            } else if (t.status === 'failed') {
              const song = songById.get(t.song_id)
              failed.push(song?.title ?? t.song_id.slice(0, 8))
              pending.delete(id)
            }
          }
          const done = doneTasks.length + failed.length
          setProcessing({ total: songIds.length, done, failed: Array.from(failed) })
          if (pending.size > 0) {
            await new Promise((r) => setTimeout(r, 1200))
            await tick()
          }
        }
        await tick()

        const items: PlaylistItem[] = doneTasks
          .map((t) => {
            const song = songById.get(t.song_id)
            if (!song) return null
            return {
              song,
              targetBpm,
              url: api.processedUrl(t.song_id, targetBpm),
              processedBeatTimes: t.processed_beat_times ?? null,
              processedBeatMaps: t.processed_beat_maps ?? null,
            }
          })
          .filter((x): x is PlaylistItem => x != null)

        if (items.length === 0) {
          setProcessing(null)
          showToast('没有歌曲处理成功，请检查后重试', 'error')
          return
        }
        setPlaylist(items)
        setCurrentIndex(0)
        if (failed.length > 0) {
          showToast(`${failed.length} 首处理失败已跳过：${failed.join('、')}`, 'error')
        } else {
          showToast(`已就绪，开始播放 ${items.length} 首！`, 'ok')
        }
      } catch (e) {
        showToast(e instanceof Error ? e.message : '处理请求失败', 'error')
      } finally {
        setProcessing(null)
      }
    },
    [targetBpm, songs, showToast],
  )

  // ------------------------------------------------------------------ audio
  useEffect(() => {
    const audio = audioRef.current
    if (!audio) return
    if (currentIndex >= 0 && currentIndex < playlist.length) {
      audio.src = playlist[currentIndex].url
      audio.currentTime = 0
      void audio.play().catch(() => {
        showToast('浏览器阻止了自动播放，请点击播放按钮', 'error')
      })
    }
  }, [currentIndex, playlist, showToast])

  useEffect(() => {
    const audio = audioRef.current
    if (!audio) return
    const onTime = () => setCurrentTime(audio.currentTime)
    const onDur = () => setDuration(audio.duration || 0)
    const onPlay = () => setPlaying(true)
    const onPause = () => setPlaying(false)
    const onSeeked = () => metronomeRef.current?.onSeek()
    // Buffer stall: stop clicks while the music is stuck; resume on 'playing'.
    const onWaiting = () => metronomeRef.current?.onPause()
    const onPlaying = () => metronomeRef.current?.onPlay()
    const onEnded = () => {
      if (currentIndex < playlist.length - 1) setCurrentIndex((i) => i + 1)
      else setCurrentIndex(0)
    }
    audio.addEventListener('timeupdate', onTime)
    audio.addEventListener('loadedmetadata', onDur)
    audio.addEventListener('play', onPlay)
    audio.addEventListener('pause', onPause)
    audio.addEventListener('seeked', onSeeked)
    audio.addEventListener('waiting', onWaiting)
    audio.addEventListener('playing', onPlaying)
    audio.addEventListener('ended', onEnded)
    audio.volume = volume
    return () => {
      audio.removeEventListener('timeupdate', onTime)
      audio.removeEventListener('loadedmetadata', onDur)
      audio.removeEventListener('play', onPlay)
      audio.removeEventListener('pause', onPause)
      audio.removeEventListener('seeked', onSeeked)
      audio.removeEventListener('waiting', onWaiting)
      audio.removeEventListener('playing', onPlaying)
      audio.removeEventListener('ended', onEnded)
    }
  }, [currentIndex, playlist.length, volume])

  // ------------------------------------------------------- active beat map
  // The beat map the metronome clicks and the visualizer shows. Priority:
  //  0. MANUAL calibration (tap tempo / user BPM / user first beat) —
  //     regenerates the grid client-side, applies instantly
  //  1. the stretched file's OWN map for this mode (ground truth of what
  //     actually plays — captures atempo's tiny ratio error too)
  //  2. the original song's map for this mode, converted to the stretched
  //     timeline (divide by ratio)
  const activeBeatMap = useMemo(() => {
    const item = currentIndex >= 0 ? playlist[currentIndex] : null
    const srcBpm = item?.song.original_bpm
    if (!item || srcBpm == null || srcBpm <= 0) return null
    const ratio = targetBpm / srcBpm
    const auto =
      item.processedBeatMaps?.[beatMode] ??
      (beatMode === 'grid' ? item.processedBeatTimes : null)
    const fromSong =
      beatMode === 'grid' ? item.song.beat_times : item.song.beat_maps?.[beatMode]
    if (calBpm != null || calFirstBeat != null) {
      const autoMap = auto ?? (fromSong ? fromSong.map((t) => t / ratio) : null)
      const autoRate = autoMap
        ? (autoMap[autoMap.length - 1] - autoMap[0]) / Math.max(1, autoMap.length - 1)
        : 60 / targetBpm
      const period = calBpm != null ? 60 / calBpm : autoRate
      const anchor = calFirstBeat ?? (autoMap ? autoMap[0] : 0)
      const dur = audioRef.current?.duration ?? 600
      const count = Math.max(2, Math.ceil((dur - anchor) / period) + 1)
      return Array.from({ length: count }, (_, k) => anchor + k * period)
    }
    if (auto && auto.length >= 2) return auto
    if (fromSong && fromSong.length >= 2) return fromSong.map((t) => t / ratio)
    return null
  }, [currentIndex, playlist, targetBpm, beatMode, calBpm, calFirstBeat])

  // -------------------------------------------------------------- metronome
  useEffect(() => {
    const audio = audioRef.current
    if (!audio) return
    if (!metronomeRef.current) metronomeRef.current = new Metronome(audio)
    const m = metronomeRef.current
    const item = currentIndex >= 0 ? playlist[currentIndex] : null
    let phase: number | null = null
    if (!activeBeatMap && item?.song.beat_offset && item.song.original_bpm) {
      phase = ((item.song.beat_offset * item.song.original_bpm) / targetBpm) % (60 / targetBpm)
    }
    m.setBeatMap(activeBeatMap)
    m.setPhase(phase)
    m.setBpm(targetBpm)
    // Manual nudge: percent of a beat -> seconds, clamped to +/- half beat.
    m.setPhaseOffset((Math.max(-50, Math.min(50, phaseNudge)) / 100) * (60 / targetBpm))
    m.setVolume(metronomeVolume)
    m.setEnabled(metronomeOn)
  }, [targetBpm, metronomeVolume, metronomeOn, phaseNudge, activeBeatMap, currentIndex])

  useEffect(() => {
    const m = metronomeRef.current
    if (!m) return
    if (playing) m.onPlay()
    else m.onPause()
  }, [playing])

  useEffect(() => {
    return () => metronomeRef.current?.dispose()
  }, [])

  // ------------------------------------------------------------------ render
  const analyzingCount = songs.filter((s) => s.bpm_status === 'analyzing').length
  const queuedCount = songs.filter((s) => s.bpm_status === 'pending').length
  const analyzedCount = songs.filter((s) => s.bpm_status === 'done' || s.bpm_status === 'failed').length
  const inProgress = analyzingCount + queuedCount

  const curItem = currentIndex >= 0 ? playlist[currentIndex] : null

  return (
    <div className="min-h-full pb-44">
      <audio ref={audioRef} className="hidden" />
      {/* header */}
      <header className="border-b border-line bg-panel/80 backdrop-blur">
        <div className="mx-auto flex max-w-5xl items-center justify-between px-4 py-4">
          <div>
            <h1 className="text-2xl font-black tracking-tight text-white">
              RUN <span className="text-run">BPM</span>
            </h1>
            <p className="text-sm text-white/50">让音乐跟随你的跑步节奏</p>
          </div>
          <div className="text-right text-xs text-white/40">
            {loading ? (
              <span>连接后端…</span>
            ) : inProgress > 0 ? (
              <span>
                <span className="text-run">正在分析 {analyzedCount} / {songs.length}</span>
                <span className="ml-1 text-white/30">（剩 {inProgress} 首）</span>
              </span>
            ) : (
              <span>{songs.length} 首歌</span>
            )}
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-5xl space-y-8 px-4 py-6">
        <UploadZone onFiles={handleFiles} busy={uploading} />

        {songs.length > 0 && <SongTable songs={songs} onChanged={refreshSongs} onToast={showToast} />}

        <ModePicker
          mode={mode}
          targetBpm={targetBpm}
          onMode={handleMode}
          onTarget={handleTarget}
          disabled={loading}
        />

        <section>
          <button
            onClick={generateRecs}
            disabled={loading || songs.length === 0}
            className="w-full rounded-xl bg-run py-3.5 text-lg font-bold text-ink transition-colors hover:bg-run-dim disabled:cursor-not-allowed disabled:opacity-40 sm:w-auto sm:px-10"
          >
            生成推荐
          </button>
          <p className="mt-2 text-xs text-white/35">
            {songs.length === 0
              ? '先上传音乐，系统将分析每首歌的 BPM 后按差距推荐'
              : `按 |原BPM − ${targetBpm}| 排序，越接近越靠前`}
          </p>
        </section>

        <RecommendPanel
          recs={recs}
          targetBpm={targetBpm}
          processing={processing}
          onSelected={() => undefined}
          onPlay={handlePlay}
        />

        <footer className="pt-4 text-center text-xs text-white/25">
          RUN BPM · 本地音乐分析 · 上传内容仅用于播放，不对外传播
        </footer>
      </main>

      {(() => (
        <PlayerBar
          item={curItem}
          playing={playing}
          currentTime={currentTime}
          duration={duration}
          volume={volume}
          metronomeOn={metronomeOn}
          metronomeVolume={metronomeVolume}
          phaseNudge={phaseNudge}
          beatMode={beatMode}
          onBeatMode={(m) => {
            setBeatMode(m)
            localStorage.setItem('runbpm.beatMode', m)
          }}
          calBpm={calBpm}
          calFirstBeat={calFirstBeat}
          songPhaseReliability={curItem?.song.phase_reliability ?? null}
          rulerOn={rulerOn}
          rulerBeats={activeBeatMap ?? []}
          getCurrentTime={getCurrentTime}
          onSetCal={onSetCal}
          onResetCal={() => {
            setCalBpm(null)
            setCalFirstBeat(null)
          }}
          onToggleRuler={() => {
            setRulerOn((v) => {
              localStorage.setItem('runbpm.rulerOn', v ? '0' : '1')
              return !v
            })
          }}
          onTogglePlay={() => {
            const audio = audioRef.current
            if (!audio) return
            if (audio.paused) void audio.play()
            else audio.pause()
          }}
          onPrev={() => setCurrentIndex((i) => (i - 1 + playlist.length) % playlist.length)}
          onNext={() => setCurrentIndex((i) => (i + 1) % playlist.length)}
          onSeek={(t) => {
            const audio = audioRef.current
            if (audio) audio.currentTime = t
          }}
          onVolume={(v) => setVolume(v)}
          onMetronome={setMetronomeOn}
          onMetronomeVolume={setMetronomeVolume}
          onPhaseNudge={(v) => {
            setPhaseNudge(v)
            localStorage.setItem('runbpm.phaseNudge', String(v))
          }}
        />
      ))()}

      {/* toast */}
      {toast && (
        <div
          className={`pointer-events-auto fixed left-1/2 top-4 z-50 max-w-[90vw] -translate-x-1/2 rounded-xl px-4 py-2.5 text-sm font-medium shadow-lg ${
            toast.kind === 'error' ? 'bg-red-500/90 text-white' : 'bg-run text-ink'
          }`}
        >
          {toast.msg}
          <button className="ml-3 text-xs opacity-70 hover:opacity-100" onClick={() => setToast(null)}>
            ✕
          </button>
        </div>
      )}
    </div>
  )
}
