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

    # Confidence from inter-beat interval regularity.
    confidence = 0.0
    if beats is not None and len(beats) >= 4:
        intervals = np.diff(np.asarray(beats, dtype=float)) / sr
        mean_iv = float(np.mean(intervals))
        std_iv = float(np.std(intervals))
        if mean_iv > 0:
            cv = std_iv / mean_iv  # coefficient of variation
            confidence = float(np.clip(1.0 - cv * 3.0, 0.0, 1.0))

    duration = None
    try:
        duration = float(librosa.get_duration(path=str(path)))
    except Exception:  # noqa: BLE001
        duration = probe_duration(path) or float(y.size / sr)

    return BpmResult(
        bpm=bpm, confidence=confidence, duration=duration,
        octave_corrected=octave_corrected,
    )
