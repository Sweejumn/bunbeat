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
    beat_times: list[float] | None = None  # default beat map = "grid" mode
    beat_maps: dict[str, list[float]] | None = None  # all selectable modes
    phase_reliability: float = 0.0  # 0..1 agreement between independent signals


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


def _strongest_onset(onset_env: np.ndarray, center: float, win: float) -> float:
    """Frame of the strongest onset energy within [center-win, center+win].

    Unlike picking the *nearest* local maximum (which grabs envelope ripples
    around a drum hit), this returns the dominant transient — the actual beat
    — with parabolic sub-frame refinement.
    """
    lo = max(0, int(np.floor(center - win)))
    hi = min(onset_env.shape[0] - 1, int(np.ceil(center + win)))
    if hi <= lo:
        return float(center)
    seg = onset_env[lo : hi + 1]
    m = int(np.argmax(seg)) + lo
    if 1 <= m < onset_env.shape[0] - 1:
        y0, y1, y2 = onset_env[m - 1], onset_env[m], onset_env[m + 1]
        denom = y0 - 2 * y1 + y2
        if abs(denom) > 1e-12:
            delta = 0.5 * (y0 - y2) / denom
            m = m + float(np.clip(delta, -1.0, 1.0))
    return float(m)


def _periodic_phase(env: np.ndarray, P: float, step: float = 0.5) -> float:
    """Frame-phase in [0, P) maximizing energy at phase + k*P over the span.

    Robust to loud subdivisions (hi-hats/backbeats): instead of trusting a
    per-beat "strongest onset", it evaluates every candidate phase against the
    total energy of ALL beats, picking the one that sits on the most beat
    energy. Parabolic refinement on the energy-vs-phase curve.
    """
    f0, f1 = 0.0, float(env.shape[0])
    kk = np.arange(int(np.floor((f0 - P) / P)), int(np.ceil((f1 + P) / P)) + 1)
    best_phi, best_e = 0.0, -1.0
    for phi in np.arange(0.0, P, step):
        idx = np.round(phi + kk * P).astype(int)
        idx = idx[(idx >= 0) & (idx < env.shape[0])]
        if idx.size == 0:
            continue
        e = float(np.sum(env[idx]))
        if e > best_e:
            best_e, best_phi = e, phi
    cands = np.arange(best_phi - 1.0, best_phi + 1.01, 0.25)
    vals = np.empty(cands.size)
    for i, phi in enumerate(cands):
        p = phi if phi >= 0 else phi + P
        idx = np.round(p + kk * P).astype(int)
        idx = idx[(idx >= 0) & (idx < env.shape[0])]
        vals[i] = float(np.sum(env[idx])) if idx.size else 0.0
    i = int(np.argmax(vals))
    phi = cands[i]
    if 0 < i < cands.size - 1:
        y0, y1, y2 = vals[i - 1], vals[i], vals[i + 1]
        denom = y0 - 2 * y1 + y2
        if abs(denom) > 1e-12:
            phi = cands[i] + 0.5 * (y0 - y2) / denom
    return float(phi) % P


def _rms_env(y: np.ndarray, hop: int = HOP) -> np.ndarray:
    """Time-domain RMS energy envelope on the same frame grid (independent
    signal used to cross-validate the beat phase)."""
    n = y.shape[0]
    frames = n // hop
    rms = np.empty(frames)
    for i in range(frames):
        seg = y[i * hop : (i + 1) * hop]
        rms[i] = float(np.sqrt(np.mean(seg * seg)))
    mx = float(np.max(rms)) if rms.size else 0.0
    if mx > 0:
        rms = rms / mx
    return rms


def _phase_reliability(onset_env: np.ndarray, y: np.ndarray, P: float) -> float:
    """0..1 agreement between spectral-flux and time-domain-RMS phase picks.

    Large disagreement (|phi_flux - phi_rms| > ~25% of the period) means the
    song's energy structure is ambiguous (backbeat/subdivision dominant) and
    automatic phase anchoring is unreliable — the UI should suggest manual
    calibration for such songs.
    """
    try:
        phi_f = _periodic_phase(onset_env, P)
        phi_r = _periodic_phase(_rms_env(y), P)
        d = min(abs(phi_f - phi_r), P - abs(phi_f - phi_r))
        return float(np.clip(1.0 - d / (0.25 * P), 0.0, 1.0))
    except Exception:  # noqa: BLE001
        return 0.0


def _beat_grid(onset_env: np.ndarray, frames: np.ndarray) -> tuple[np.ndarray, float, float]:
    """Shared grid computation for all beat-map modes.

    Returns (grid, P, phi) in FRAME units, where:
      grid  regular beat grid        phi + k*P
      P     robust beat period       median of dominant-onset spacings
      phi   phase from the periodic energy search (robust to subdivisions)
    """
    f = np.asarray(frames, dtype=float)
    diffs = np.diff(f)
    P0 = float(np.median(diffs)) if diffs.size else 0.0
    if not (P0 > 2.0):
        raise ValueError("beat frames too sparse")

    k_idx = np.arange(f.size)
    nearest = np.array([_strongest_onset(onset_env, fr, 0.45 * P0) for fr in f])

    # Refine period & phase with a robust WEIGHTED least-squares fit over
    # the dominant-onset positions (slope = period, intercept = phase).
    # A plain median of per-beat spacings is systematically biased when the
    # onset envelope has double peaks or ghost onsets (measured -0.5..-1.0%
    # on real songs, which accumulates into audible drift). Fitting ALL beats
    # at once averages that noise away: for constant-tempo songs the period
    # error drops to ~0.01%, i.e. negligible drift over a whole song.
    k = np.arange(nearest.size, dtype=float)
    P0 = float(np.median(np.diff(f))) if f.size > 1 else P0
    w = np.ones(nearest.size)
    P, phi = P0, 0.0
    for _ in range(3):
        sw = float(w.sum())
        if sw <= 0 or nearest.size < 2:
            break
        sk = float(np.sum(w * k))
        s2 = float(np.sum(w * k * k))
        sy = float(np.sum(w * nearest))
        sky = float(np.sum(w * k * nearest))
        denom = sw * s2 - sk * sk
        if abs(denom) < 1e-12:
            break
        P = (sw * sky - sk * sy) / denom
        phi = (s2 * sy - sk * sky) / denom
        res = nearest - (phi + k * P)
        mad = float(np.median(np.abs(res))) + 1e-9
        w = 1.0 / (1.0 + (res / (3.0 * mad)) ** 2)  # robust weights
    if not (P > 2.0):
        P = P0

    # Phase via periodic energy search: robust to loud subdivisions that would
    # fool a per-beat "strongest onset" picker.
    phi = _periodic_phase(onset_env, P)
    start_k = int(np.floor((f[0] - phi) / P))
    end_k = int(np.ceil((f[-1] - phi) / P))
    kk = np.arange(start_k, end_k + 1)
    return phi + kk * P, P, phi


def _beat_map_variants(
    onset_env: np.ndarray, frames: np.ndarray, sr: int, hop: int
) -> dict[str, list[float]]:
    """Produce the three selectable beat-map modes (seconds, original timeline).

      grid  固定拍子 (default): perfectly regular grid — the music's true
            median beat period (no drift) + onset-anchored phase, no per-beat
            snapping, so beats are exactly evenly spaced.
      light 轻跟随: grid pulled up to +-5% of a beat toward the dominant
            onset — follows local tempo lightly while staying mostly regular.
      snap  跟随起音: each beat follows the dominant onset within +-12% of a
            beat (with interval validation) — tracks the music's transients,
            at the cost of regularity on songs with loose/syncopated drums.
    """
    grid, P, _ = _beat_grid(onset_env, frames)
    variants: dict[str, list[float]] = {
        "grid": [round(float(t) * hop / sr, 3) for t in grid]
    }
    if onset_env.size <= 0:
        return variants

    light = np.array([_strongest_onset(onset_env, g, 0.05 * P) for g in grid])
    variants["light"] = [round(float(t) * hop / sr, 3) for t in light]

    snap = np.array([_strongest_onset(onset_env, g, 0.12 * P) for g in grid])
    # validation: re-grid beats creating irregular intervals
    for _ in range(4):
        iv = np.diff(snap)
        bad = np.flatnonzero((iv < 0.85 * P) | (iv > 1.15 * P))
        if bad.size == 0:
            break
        fix: set[int] = set()
        for b in bad:
            fix.add(int(b))
            fix.add(int(b) + 1)
        changed = False
        for b in sorted(fix):
            if 0 <= b < snap.size and snap[b] != grid[b]:
                snap[b] = grid[b]
                changed = True
        if not changed:
            snap = grid.copy()
            break
    variants["snap"] = [round(float(t) * hop / sr, 3) for t in snap]
    return variants


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

    # Beat maps locked to the CORRECTED tempo pulse. The free-running tracker
    # can lock to a sub-multiple of the reported tempo (e.g. half-time), so we
    # re-run the DP tracker with bpm fixed to the corrected value, then derive
    # the three selectable beat-map modes (fixed grid / light / onset-snapped).
    beat_offset: float | None = None
    beat_times: list[float] | None = None
    beat_maps: dict[str, list[float]] | None = None
    try:
        from librosa.beat import __beat_tracker

        mask = __beat_tracker(
            onset_env,
            bpm=np.array([bpm]),
            frame_rate=float(sr) / HOP,
            tightness=100,
            trim=True,
        )
        beat_maps = _beat_map_variants(onset_env, np.flatnonzero(mask), sr, HOP)
    except Exception:  # noqa: BLE001  (private API; fall back to free beats)
        if beats is not None and len(beats) >= 2:
            try:
                beat_maps = _beat_map_variants(onset_env, np.asarray(beats, dtype=float), sr, HOP)
            except Exception:  # noqa: BLE001
                beat_maps = None
    if beat_maps and beat_maps.get("grid"):
        beat_times = beat_maps["grid"]
        beat_offset = beat_times[0]

    # Phase reliability: do two independent signals (spectral flux vs
    # time-domain RMS) agree on where the beats sit?
    phase_reliability = 0.0
    if bpm and bpm > 0:
        phase_reliability = _phase_reliability(onset_env, y, (60.0 / bpm) * sr / HOP)

    duration = None
    try:
        duration = float(librosa.get_duration(path=str(path)))
    except Exception:  # noqa: BLE001
        duration = probe_duration(path) or float(y.size / sr)

    return BpmResult(
        bpm=bpm, confidence=confidence, duration=duration,
        octave_corrected=octave_corrected, beat_offset=beat_offset,
        beat_times=beat_times, beat_maps=beat_maps,
        phase_reliability=phase_reliability,
    )
