#!/usr/bin/env python3
"""
App launch (and close) from home: the app's bright launch card's box in every frame (bbox.py), cut into launches (the
card's height growing from small to the full screen) and fitted (one spring over all launches; each also alone).

    python tools/ios_ref/measure_app.py SCENARIO_DIR [SCENARIO_DIR ...]
"""
import os
import subprocess
import sys

import numpy as np

HERE = os.path.dirname(__file__)
sys.path.insert(0, HERE)
import springfit as sf  # noqa: E402
from bbox import bbox  # noqa: E402


def frames_of(d):
    f = os.path.join(d, "f1")
    if not os.path.exists(os.path.join(f, "frames.txt")):
        subprocess.run([sys.executable, os.path.join(HERE, "frames.py"), os.path.join(d, "video.mp4"), f, "--scale", "1"],
                       check=True, capture_output=True)
    return f


def launches(d):
    f = frames_of(d)
    rows = bbox(f, (201, 437), 232, False)
    out, cur = [], []

    def finish():
        if len(cur) >= 4 and cur[-1][1] >= 872 and cur[0][1] < 800:
            out.append(list(cur))
        cur.clear()

    for t, b in rows:
        h = None if b is None else b[3] - b[1]
        if h is None or h < 80:
            finish()
            continue
        if cur and h == cur[-1][1]:
            continue
        if cur and h < cur[-1][1] - 2:  # shrinking: a close begins; the launch (if any) is over
            finish()
            continue
        if cur and cur[-1][1] >= 874:
            continue
        cur.append((t, h, b[2] - b[0], b[0], b[1]))
    finish()
    return out


def main():
    runs = []
    for d in sys.argv[1:]:
        for r in launches(d):
            runs.append(r)
            print("%s: launch at %.3f, first height %d, %d frames" % (os.path.basename(d.rstrip("/")), r[0][0], r[0][1], len(r)))
    for name, k, tg in (("height", 1, 874.0), ("width", 2, 402.0), ("left", 3, 0.0), ("top", 4, 0.0)):
        good = [r for r in runs if r[0][1] < 600]  # runs that caught the early part
        if not good:
            continue
        f = sf.fit_joint([([p[0] for p in r], [p[k] for p in r]) for r in good], target=tg)
        singles = [sf.fit([p[0] for p in r], [p[k] for p in r], target=tg) for r in good]
        print("app launch %-6s (%d runs from < 600 pt): joint %.3f / %.3f (rms %.1f); singles %s" % (
            name, len(good), f["response"], f["damping"], f["rms"],
            ", ".join("%.3f/%.3f" % (g["response"], g["damping"]) for g in singles)))


if __name__ == "__main__":
    main()
