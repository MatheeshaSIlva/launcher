#!/usr/bin/env python3
"""
Fits a measured motion (time, value) to a spring in SwiftUI's terms: response R (s) and damping fraction z, with the
start time, start velocity and (optionally) the target free. iOS's system animations are springs of this form
(CASpringAnimation / UISpringTimingParameters with mass 1: stiffness = (2 pi / R)^2, damping = 4 pi z / R).

    python tools/ios_ref/springfit.py DATA.txt [--target X] [--from T]      DATA: "t value" per line

As a library: fit(t, x, target=None) -> dict(response, damping, t0, v0, x0, target, rms).
"""
import argparse
import math
import sys

import numpy as np
from scipy.optimize import least_squares


def spring(tau, x0, v0, target, response, damping):
    """Position at time tau >= 0 of a spring released at x0 with velocity v0 towards target."""
    w0 = 2 * math.pi / response
    z = damping
    a = x0 - target
    tau = np.maximum(tau, 0.0)
    if z < 0.999:
        wd = w0 * math.sqrt(1 - z * z)
        b = (v0 + z * w0 * a) / wd
        return target + np.exp(-z * w0 * tau) * (a * np.cos(wd * tau) + b * np.sin(wd * tau))
    if z <= 1.001:
        return target + np.exp(-w0 * tau) * (a + (v0 + w0 * a) * tau)
    s = w0 * math.sqrt(z * z - 1)
    r1, r2 = -z * w0 + s, -z * w0 - s
    c2 = (v0 - r1 * a) / (r2 - r1)
    c1 = a - c2
    return target + c1 * np.exp(r1 * tau) + c2 * np.exp(r2 * tau)


def fit(t, x, target=None, t0=None):
    t = np.asarray(t, float)
    x = np.asarray(x, float)
    span = max(abs(x.max() - x.min()), 1e-6)
    free_target = target is None
    tgt0 = x[-1] if free_target else target
    start = t[0] if t0 is None else t0
    v_guess = (x[min(2, len(x) - 1)] - x[0]) / max(t[min(2, len(t) - 1)] - t[0], 1e-3)

    def unpack(p):
        r, z, v0, ts, x0 = p[:5]
        tg = p[5] if free_target else tgt0
        return r, z, v0, ts, x0, tg

    def resid(p):
        r, z, v0, ts, x0, tg = unpack(p)
        return (spring(t - ts, x0, v0, tg, r, z) - x) / span

    best = None
    for r_init in (0.25, 0.4, 0.6):
        for z_init in (0.7, 0.9, 1.0):
            p0 = [r_init, z_init, v_guess, start, x[0]] + ([tgt0] if free_target else [])
            lo = [0.05, 0.1, -1e6, start - 0.2, x[0] - span] + ([tgt0 - span] if free_target else [])
            hi = [3.0, 3.0, 1e6, start + 0.1, x[0] + span] + ([tgt0 + span] if free_target else [])
            try:
                res = least_squares(resid, p0, bounds=(lo, hi))
            except ValueError:
                continue
            if best is None or res.cost < best.cost:
                best = res
    r, z, v0, ts, x0, tg = unpack(best.x)
    rms = float(np.sqrt(np.mean(resid(best.x) ** 2))) * span
    settle = settling_time(r, z)
    return dict(response=r, damping=z, v0=v0, t0=ts, x0=x0, target=tg, rms=rms, settle=settle)


def settling_time(response, damping, tol=0.005):
    """Time until the envelope is within tol of the distance (roughly when it looks still)."""
    w0 = 2 * math.pi / response
    rate = damping * w0 if damping < 1 else w0 * (damping - math.sqrt(max(damping * damping - 1, 0))) if damping > 1 else w0
    return -math.log(tol) / max(rate, 1e-6)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("data")
    ap.add_argument("--target", type=float)
    ap.add_argument("--from", dest="t_from", type=float)
    a = ap.parse_args()
    rows = [list(map(float, l.split()[:2])) for l in open(a.data) if l.strip() and not l.startswith("#")]
    if a.t_from is not None:
        rows = [r for r in rows if r[0] >= a.t_from]
    t, x = zip(*rows)
    f = fit(t, x, a.target)
    print("response %.3f s  damping %.3f  (start %.3f s, v0 %.1f/s, %.2f -> %.2f, rms %.3f, settles ~%.2f s)" %
          (f["response"], f["damping"], f["t0"], f["v0"], f["x0"], f["target"], f["rms"], f["settle"]))


if __name__ == "__main__":
    sys.exit(main())


def fit_joint(runs, target=None):
    """
    One spring (response, damping and, unless given, target) shared by several runs of the same motion, each run with
    its own start time, start position and start velocity: [(t, x), ...]. Runs that drop different frames fill each
    other's gaps. Returns dict(response, damping, target, rms, starts=[(t0, x0, v0), ...]).
    """
    runs = [(np.asarray(t, float), np.asarray(x, float)) for t, x in runs if len(t) >= 3]
    span = max(max(abs(x.max() - x.min()) for _, x in runs), 1e-6)
    free_target = target is None
    tgt0 = float(np.median([x[-1] for _, x in runs])) if free_target else target
    n = len(runs)

    def unpack(p):
        r, z = p[0], p[1]
        tg = p[2] if free_target else tgt0
        k = 3 if free_target else 2
        starts = [(p[k + 3 * i], p[k + 3 * i + 1], p[k + 3 * i + 2]) for i in range(n)]
        return r, z, tg, starts

    def resid(p):
        r, z, tg, starts = unpack(p)
        out = []
        for (t, x), (ts, x0, v0) in zip(runs, starts):
            out.append((spring(t - ts, x0, v0, tg, r, z) - x) / span)
        return np.concatenate(out)

    best = None
    for r_init in (0.3, 0.45, 0.6):
        for z_init in (0.75, 0.9, 1.0):
            p0 = [r_init, z_init] + ([tgt0] if free_target else [])
            lo = [0.05, 0.1] + ([tgt0 - span] if free_target else [])
            hi = [3.0, 3.0] + ([tgt0 + span] if free_target else [])
            for t, x in runs:
                v = (x[1] - x[0]) / max(t[1] - t[0], 1e-3)
                p0 += [t[0] - 0.01, x[0], v]
                lo += [t[0] - 0.25, x[0] - span, -1e5]
                hi += [t[0] + 0.02, x[0] + span, 1e5]
            p0 = [min(max(v, l + 1e-9), h - 1e-9) for v, l, h in zip(p0, lo, hi)]
            try:
                res = least_squares(resid, p0, bounds=(lo, hi))
            except ValueError:
                continue
            if best is None or res.cost < best.cost:
                best = res
    r, z, tg, starts = unpack(best.x)
    rms = float(np.sqrt(np.mean(resid(best.x) ** 2))) * span
    return dict(response=r, damping=z, target=tg, rms=rms, starts=starts, settle=settling_time(r, z))
