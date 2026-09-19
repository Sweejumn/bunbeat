# -*- coding: utf-8 -*-
"""Numerically diff two parity JSON dumps (Dart golden vs Kotlin port).

Usage: python compare.py expected_dart.json actual_kotlin.json [rel_tol]

Reports, per engine, the worst deviation on every compared field so a
"完全复刻" (faithful replication) claim can be judged objectively.
"""
import json
import sys
import math

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

SCALARS = ["bpm", "confidence", "duration", "beatOffset", "phaseReliability"]
FP_FIELDS = ["beats", "grid", "snap"]
FP_NUM = ["sum", "sq", "wsum", "min", "max"]


def load(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def bit_identical(a, b):
    """Exact equality, treating None/NaN as matching counterparts."""
    if a is None and b is None:
        return True
    if isinstance(a, float) and isinstance(b, float):
        if math.isnan(a) and math.isnan(b):
            return True
    return a == b


def rel_err(a, b):
    if a is None and b is None:
        return 0.0
    if a is None or b is None:
        return float("inf")
    if isinstance(a, bool) or isinstance(b, bool):
        return 0.0 if a == b else float("inf")
    if not isinstance(a, (int, float)) or not isinstance(b, (int, float)):
        return 0.0 if a == b else float("inf")
    if math.isnan(a) and math.isnan(b):
        return 0.0
    d = abs(a - b)
    scale = max(abs(a), abs(b), 1e-12)
    return d / scale


def main():
    exp = load(sys.argv[1])
    act = load(sys.argv[2])
    tol = float(sys.argv[3]) if len(sys.argv) > 3 else 1e-9

    ef = exp["files"]
    af = act["files"]
    problems = []
    missing = [k for k in ef if k not in af]
    extra = [k for k in af if k not in ef]
    if missing:
        problems.append(f"Kotlin missing {len(missing)} files: {missing[:5]}")
    if extra:
        problems.append(f"Kotlin has {len(extra)} extra files: {extra[:5]}")

    engines = sorted({e for v in ef.values() for e in v if e != "samples"})
    worst = {}          # (engine, field) -> (err, detail)
    mismatches = []     # hard failures
    field_worst = {}    # field -> worst err across all engines/files

    def note_field(field, err):
        if field not in field_worst or err > field_worst[field]:
            field_worst[field] = err

    for name in ef:
        if name not in af:
            continue
        for eng in engines:
            d = ef[name].get(eng)
            k = af[name].get(eng)
            if d is None or k is None:
                mismatches.append(f"{name}/{eng}: missing side")
                continue
            if "exception" in d or "exception" in k:
                de, ke = d.get("exception"), k.get("exception")
                both = de is not None and ke is not None
                if not both:
                    mismatches.append(f"{name}/{eng}: exception mismatch dart={de!r} kotlin={ke!r}")
                continue

            def note(field, err, detail=""):
                key = (eng, field)
                if key not in worst or err > worst[key][0]:
                    worst[key] = (err, f"{name} {detail}")
                note_field(field, err)
                if err > tol:
                    mismatches.append(f"{name}/{eng}/{field}: rel_err={err:.3e} {detail}")

            for f in SCALARS:
                note(f, rel_err(d.get(f), k.get(f)),
                     f"dart={d.get(f)} kotlin={k.get(f)}")
            # error string
            if (d.get("error") or None) != (k.get("error") or None):
                mismatches.append(f"{name}/{eng}/error: dart={d.get('error')!r} kotlin={k.get('error')!r}")
            # mapKeys
            if (d.get("mapKeys") or []) != (k.get("mapKeys") or []):
                mismatches.append(f"{name}/{eng}/mapKeys: {d.get('mapKeys')} vs {k.get('mapKeys')}")
            for fp in FP_FIELDS:
                df, kf = d.get(fp) or {}, k.get(fp) or {}
                if df.get("n") != kf.get("n"):
                    mismatches.append(f"{name}/{eng}/{fp}.n: {df.get('n')} vs {kf.get('n')}")
                    continue
                if df.get("n", 0) <= 0:
                    continue
                for nf in FP_NUM:
                    note(f"{fp}.{nf}", rel_err(df.get(nf), kf.get(nf)),
                         f"dart={df.get(nf)} kotlin={kf.get(nf)}")
                dh, kh = df.get("head") or [], kf.get("head") or []
                if len(dh) != len(kh):
                    mismatches.append(f"{name}/{eng}/{fp}.head len: {len(dh)} vs {len(kh)}")
                else:
                    for i, (a, b) in enumerate(zip(dh, kh)):
                        note(f"{fp}.head[{i}]", rel_err(a, b), f"dart={a} kotlin={b}")

    print(f"files compared: {len(ef) - len(missing)}   engines: {len(engines)}")
    print(f"tolerance: rel {tol:.1e}")
    print()
    print("worst deviation per engine/field:")
    for eng in engines:
        rows = sorted(((f, v) for (e, f), v in worst.items() if e == eng))
        top = sorted(rows, key=lambda r: -r[1][0])[:4]
        summary = "  ".join(f"{f}={v[0]:.2e}" for f, v in top) if top else "(none)"
        print(f"  {eng:14s} {summary}")
    print()
    exact = sorted(f for f, v in field_worst.items() if v == 0.0)
    inexact = sorted(((v, f) for f, v in field_worst.items() if v != 0.0), reverse=True)
    print(f"bit-identical fields ({len(exact)}): {', '.join(exact) if exact else '(none)'}")
    if inexact:
        print("not bit-identical: " + "  ".join(f"{f}={v:.2e}" for v, f in inexact))
    else:
        print("not bit-identical: (none)")
    print()
    if mismatches:
        print(f"NOT FAITHFUL: {len(mismatches)} mismatch(es); first 40:")
        for m in mismatches[:40]:
            print("   -", m)
        sys.exit(1)
    print("PARITY OK: Kotlin port reproduces the Dart reference within tolerance.")


if __name__ == "__main__":
    main()
