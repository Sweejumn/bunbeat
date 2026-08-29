"""End-to-end acceptance test: process all songs to a target BPM and
re-analyze the outputs to confirm they land close to the target."""
import json
import subprocess
import sys
import time
import urllib.request

from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

BASE = "http://127.0.0.1:8000"


def http(method, path, body=None):
    req = urllib.request.Request(BASE + path, method=method)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, data=data, timeout=60) as resp:
        return json.loads(resp.read())


def _matches_target(detected: float, target: float) -> float:
    """Best relative error (in %) when `detected` is interpreted as a
    musically-valid pulse of `target` (half, 2/3, 3/4, 1x, 5/4, 3/2, 2x)."""
    best = abs(detected - target) / target
    for mult in (0.5, 2.0 / 3.0, 0.75, 1.25, 1.5, 2.0):
        best = min(best, abs(detected - target * mult) / (target * mult))
    return best * 100


def main():
    target = float(sys.argv[1]) if len(sys.argv) > 1 else 165.0
    songs = http("GET", "/api/songs")
    print(f"--- {len(songs)} songs, target {target:g} BPM")
    for s in songs:
        print(f"    {s['filename']:12s} {s['original_bpm']:6g} BPM (conf {s['bpm_confidence']:.2f})")

    batch = http("POST", "/api/process/batch", {"song_ids": [s["id"] for s in songs], "target_bpm": target})
    tasks = batch["tasks"]
    pending = {t["id"]: t for t in tasks}
    while pending:
        for tid in list(pending):
            t = http("GET", f"/api/process/tasks/{tid}")
            if t["status"] in ("done", "failed"):
                print(f"    task {tid[:8]} -> {t['status']}" + (f"  {t['error']}" if t["error"] else ""))
                del pending[tid]
        if pending:
            time.sleep(1.0)

    # Re-analyze processed outputs directly on disk.
    from pathlib import Path
    from app.services.bpm import analyze_bpm

    ok = True
    for s in songs:
        out = Path(f"data/processed/{s['id']}__{target:.1f}.mp3")
        if not out.exists():
            print(f"    {s['filename']}: MISSING output")
            ok = False
            continue
        r = analyze_bpm(out)
        err = _matches_target(r.bpm, target)
        mark = "OK " if err <= 5.0 else "FAIL"
        if err > 5.0:
            ok = False
        print(
            f"    {s['filename']:12s} {s['original_bpm']:6g} -> {r.bpm:6.1f} BPM "
            f"(target {target:g}, best-pulse err {err:4.1f}%) [{mark}]"
        )

    print("RESULT:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
