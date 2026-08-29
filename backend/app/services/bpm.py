"""BPM / beat detection powered by librosa (a mature audio-analysis library).

Pipeline:
  1. onset strength envelope
  2. coarse tempo from librosa's beat tracker
  3. fine tempo from a parabolic-refined autocorrelation peak
  4. octave correction: compare autocorrelation strength at tempo vs. its
     double/half, then clamp into a running-friendly range
  5. confidence from inter-beat interval regularity

Users can always override the detected BPM manually from the UI.
"""
from __future__ import annotations

import logging
import warnings
from dataclasses import dataclass
from pathlib import Path

import librosa
import numpy as np

from ..config import settings
from .ffmpeg_tools import probe_duration

# Cosmetic: numba emits an "invalid value encountered in cast" warning inside
# librosa's onset strength on some Windows/numpy combinations.
warnings.filterwarnings("ignore", category=RuntimeWarning, module="numba")

logger = logging.getLogger(__name__)

HOP = 512


@dataclass
class BpmResult:
    bpm: float | None
    confidence: float  # 0..1
    duration: float | None
    error: str | None = None
    octave_corrected: bool = False
    beat_offset: float | None = None  # seconds of the first detected beat (original timeline)
    beat_times: list[float] | None = None  # every detected beat, seconds (original timeline)


def _onset_ac(onset_env: np.ndarray) -> np.ndarray:
    y_env = onset_env - float(np.mean(onset_env))
    return librosa.autocorrelate(y_env, max_size=max(2, onset_env.shape[0] // 2))


def _strength_at(ac: np.ndarray, sr: int, bpm: float) -> float:
    """Max autocorrelation in a +/-10% window around the lag for `bpm`."""
    if bpm <= 0:
        return 0.0
    lag = (60.0 / bpm) * sr / HOP
    if lag < 2 or lag >= ac.shape[0] - 2:
        return 0.0
    lo, hi = int(lag * 0.9), int(lag * 1.1) + 1
    lo = max(lo, 1)
    hi = min(hi, ac.shape[0])
    if hi <= lo:
        return 0.0
    return float(np.max(ac[lo:hi]))


def _refine_tempo(ac: np.ndarray, sr: int, base_tempo: float) -> float:
    """Parabolic interpolation around the autocorrelation peak near base_tempo."""
    lag0 = (60.0 / base_tempo) * sr / HOP
    lo = max(1, int(lag0 * 0.85))
    hi = min(ac.shape[0], int(lag0 * 1.15) + 1)
    if hi <= lo:
        return base_tempo
    idx = int(np.argmax(ac[lo:hi])) + lo
    if 1 <= idx < ac.shape[0] - 1:
        y0, y1, y2 = ac[idx - 1], ac[idx], ac[idx + 1]
        denom = y0 - 2 * y1 + y2
        if abs(denom) > 1e-12:
            delta = 0.5 * (y0 - y2) / denom
            idx += float(np.clip(delta, -1.0, 1.0))
    return 60.0 / (max(idx, 1e-6) * HOP / sr)


def _peak_pick(x: np.ndarray) -> np.ndarray:
    """Local maxima of a 1-D signal with parabolic sub-sample refinement.

    Returns peak positions as float frame indices.
    """
    if x.shape[0] < 3:
        return np.array([], dtype=float)
    mask = (x[1:-1] > x[:-2]) & (x[1:-1] >= x[2:])
    idx = np.flatnonzero(mask) + 1
    if idx.size == 0:
        return np.array([], dtype=float)
    y0, y1, y2 = x[idx - 1], x[idx], x[idx + 1]
    denom = y0 - 2 * y1 + y2
    safe = np.where(np.abs(denom) > 1e-12, denom, 1e-12)
    delta = np.where(np.abs(denom) > 1e-12, 0.5 * (y0 - y2) / safe, 0.0)
    return idx + np.clip(delta, -1.0, 1.0)


def _snap_beats_to_onsets(
    onset_env: np.ndarray, beat_frames: np.ndarray, sr: int, hop: int
) -> np.ndarray:
    """Snap each beat frame to the nearest onset-envelope peak.

    The bpm-locked grid has two flaws: frame quantization (about half a frame
    of jitter) and a biased period (the detected bpm can be a fraction of a
    percent off, which accumulates into drift). The onset peaks are where the
    audible beats actually are, so snapping pulls every click onto the real
    transients — eliminating both jitter and cumulative drift (each beat is
    found independently, never extrapolated).
    """
    peaks = _peak_pick(onset_env)
    if peaks.size < 2 or beat_frames.size == 0:
        return np.asarray(beat_frames, dtype=float)

    frames = np.asarray(beat_frames, dtype=float)
    if frames.size >= 2:
        radius = 0.55 * float(np.median(np.diff(frames)))
    else:
        radius = 8.0
    radius = max(radius, 3.0)

    refined = np.empty_like(frames)
    pi = 0
    n = peaks.size
    for i, bf in enumerate(frames):
        while pi < n - 1 and peaks[pi] < bf - radius:
            pi += 1
        best, best_d = bf, radius + 1.0
        j = pi
        while j < n and peaks[j] <= bf + radius:
            d = abs(peaks[j] - bf)
            if d < best_d:
                best_d = d
                best = peaks[j]
            j += 1
        refined[i] = best
        pi = max(pi, j - 1) if j > pi else pi
    return refined


def _octave_correct(ac: np.ndarray, sr: int, tempo: float) -> tuple[float, bool]:
    """Resolve tempo-ambiguity (2x / 0.5x octaves plus 1.5x / 2/3 pulses).

    Half-time readings are the dominant failure mode for a running app, and
    triplet-groove music (common in J-pop/Vocaloid) is often felt at 2/3 of
    the running pulse, so:

      * when the detected tempo is <100 and doubling lands in the running
        cadence zone (100-210), double it if the autocorrelation at the
        doubled tempo is at least 80% as strong;
      * similarly, when 1.5x lands in the running zone and is nearly as
        strong, prefer the faster (running) pulse;
      * values are finally clamped into a sensible range.

    Genuinely slow songs keep their slow tempo because the faster hypothesis
    has much weaker autocorrelation. Users can always override manually.
    """
    best, corrected = tempo, False
    s_cur = _strength_at(ac, sr, tempo)
    s_double = _strength_at(ac, sr, tempo * 2.0)
    s_1_5 = _strength_at(ac, sr, tempo * 1.5)
    s_half = _strength_at(ac, sr, tempo / 2.0)
    s_2_3 = _strength_at(ac, sr, tempo / 1.5)

    if tempo < 100 and 100 <= tempo * 2.0 <= 210 and s_double > s_cur * 0.8:
        best, corrected = tempo * 2.0, True
    elif 100 <= tempo * 1.5 <= 210 and s_1_5 > s_cur * 0.8 and tempo * 1.5 > tempo:
        best, corrected = tempo * 1.5, True
    elif s_2_3 > s_cur * 1.3 and tempo / 1.5 >= 50:
        best, corrected = tempo / 1.5, True
    elif s_half > s_cur * 1.3 and tempo / 2.0 >= 50:
        best, corrected = tempo / 2.0, True

    # Conservative clamps for pathological readings.
    while best < 60 and best * 2 <= 300:
        best *= 2.0
        corrected = True
    while best > 200:
        best /= 2.0
        corrected = True
    return round(best, 1), corrected


def _load_audio(path: Path):
    """Load audio (mono float32 @ ANALYZE_SR, limited to the analysis window).

    soundfile handles wav/flac/ogg/opus/mp3 natively; AAC/M4A is decoded to
    a temporary WAV via FFmpeg first (the bundled static ffmpeg is used when
    none is on PATH).
    """
    try:
        return librosa.load(
            str(path),
            sr=settings.ANALYZE_SR,
            mono=True,
            duration=settings.ANALYZE_DURATION_SECONDS,
        )
    except Exception:
        # Fall back to FFmpeg decode for formats soundfile cannot read.
        import subprocess

        from .ffmpeg_tools import get_ffmpeg

        tmp_dir = settings.DATA_DIR / "tmp"
        tmp_dir.mkdir(parents=True, exist_ok=True)
        wav = tmp_dir / f"decode_{path.stem}.wav"
        cmd = [
            get_ffmpeg(), "-y", "-hide_banner", "-loglevel", "error",
            "-i", str(path),
            "-t", f"{settings.ANALYZE_DURATION_SECONDS}",
            "-ac", "1", "-ar", str(settings.ANALYZE_SR),
            str(wav),
        ]
        try:
            subprocess.run(
                cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                timeout=300, check=True,
            )
            return librosa.load(str(wav), sr=settings.ANALYZE_SR, mono=True)
        finally:
            wav.unlink(missing_ok=True)


def analyze_bpm(path: Path) -> BpmResult:
    """Detect BPM (and duration) for an audio file.

    Only the first ANALYZE_DURATION_SECONDS are analysed for speed.
    """
    try:
        y, sr = _load_audio(path)
    except Exception as exc:  # noqa: BLE001
        return BpmResult(
            bpm=None, confidence=0.0, duration=None,
            error=f"无法解码音频文件: {exc}",
        )

    if y.size < sr * 2:  # less than 2 seconds of audio
        return BpmResult(
            bpm=None, confidence=0.0, duration=None,
            error="音频过短，无法可靠检测 BPM",
        )

    try:
        onset_env = librosa.onset.onset_strength(y=y, sr=sr)
        _, beats = librosa.beat.beat_track(onset_envelope=onset_env, sr=sr)
        ac = _onset_ac(onset_env)
        coarse = librosa.feature.tempo(onset_envelope=onset_env, sr=sr)
        coarse = float(np.atleast_1d(coarse)[0])
        if not np.isfinite(coarse) or coarse <= 0:
            return BpmResult(bpm=None, confidence=0.0, duration=None, error="无法可靠检测 BPM")
        refined = _refine_tempo(ac, sr, coarse)
        bpm, octave_corrected = _octave_correct(ac, sr, refined)
    except Exception as exc:  # noqa: BLE001
        return BpmResult(
            bpm=None, confidence=0.0, duration=None,
            error=f"节拍检测失败: {exc}",
        )

    # Confidence from the free-running tracker's inter-beat regularity.
    confidence = 0.0
    if beats is not None and len(beats) >= 4:
        beat_arr = np.asarray(beats, dtype=float)
        intervals = np.diff(beat_arr) / sr
        mean_iv = float(np.mean(intervals))
        std_iv = float(np.std(intervals))
        if mean_iv > 0:
            cv = std_iv / mean_iv  # coefficient of variation
            confidence = float(np.clip(1.0 - cv * 3.0, 0.0, 1.0))

    # Beat map locked to the CORRECTED tempo pulse. The free-running tracker
    # can lock to a sub-multiple of the reported tempo (e.g. half-time), which
    # would make the metronome click at the wrong pulse level; re-running the
    # DP tracker with bpm fixed to the corrected value yields beats at the
    # right density. Each grid beat is then SNAPPED to the nearest onset peak,
    # so the map follows the real transients (no frame jitter, no cumulative
    # drift) instead of a possibly-biased constant-period grid.
    beat_offset: float | None = None
    beat_times: list[float] | None = None
    try:
        from librosa.beat import __beat_tracker

        mask = __beat_tracker(
            onset_env,
            bpm=np.array([bpm]),
            frame_rate=float(sr) / HOP,
            tightness=100,
            trim=True,
        )
        frames = _snap_beats_to_onsets(onset_env, np.flatnonzero(mask), sr, HOP)
        if frames.size >= 2:
            beat_times = [round(float(f) * HOP / sr, 3) for f in frames]
            beat_offset = beat_times[0]
    except Exception:  # noqa: BLE001  (private API; fall back to free beats)
        if beats is not None and len(beats) >= 2:
            beat_arr = _snap_beats_to_onsets(onset_env, np.asarray(beats, dtype=float), sr, HOP)
            beat_times = [round(float(f) * HOP / sr, 3) for f in beat_arr if np.isfinite(f)]
            beat_offset = beat_times[0]

    duration = None
    try:
        duration = float(librosa.get_duration(path=str(path)))
    except Exception:  # noqa: BLE001
        duration = probe_duration(path) or float(y.size / sr)

    return BpmResult(
        bpm=bpm, confidence=confidence, duration=duration,
        octave_corrected=octave_corrected, beat_offset=beat_offset,
        beat_times=beat_times,
    )
