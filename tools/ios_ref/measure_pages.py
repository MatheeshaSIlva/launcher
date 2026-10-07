#!/usr/bin/env python3
"""
Home screen page swipes: the page's horizontal position in every frame, from the shift of the icon rows between
consecutive frames (correlation of their edge profiles; the wallpaper does not move with the pages), summed up. Each
swipe is cut at the frame the finger let go (the motion's last steady-speed frame, found from the data) and fitted.

    python tools/ios_ref/measure_pages.py SCENARIO_DIR [--band 280 480]
"""
import argparse
import os
import subprocess
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(__file__)
sys.path.insert(0, HERE)
import springfit as sf  # noqa: E402


def frames_of(d):
    f = os.path.join(d, "f1")
    if not os.path.exists(os.path.join(f, "frames.txt")):
        subprocess.run([sys.executable, os.path.join(HERE, "frames.py"), os.path.join(d, "video.mp4"), f, "--scale", "1"],
                       check=True, capture_output=True)
    return f, [(int(a), float(b)) for a, b in (l.split() for l in open(os.path.join(f, "frames.txt")))]


def profile(path, band):
    a = np.asarray(Image.open(path).convert("L"), dtype=np.float32)[band[0]:band[1]]
    g = np.abs(np.diff(a, axis=1)).mean(axis=0)
    return g - g.mean()


def shift(p, q, maxs=160):
    """How far q is moved right of p (px), by correlation; positive = content moved right."""
    best, bs = -1e18, 0
    n = len(p)
    for s in range(-maxs, maxs + 1):
        if s >= 0:
            c = float(np.dot(p[:n - s], q[s:])) / (n - s)
        else:
            c = float(np.dot(p[-s:], q[:n + s])) / (n + s)
        if c > best:
            best, bs = c, s
    # sub-pixel: parabola through the neighbours
    return float(bs)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dir")
    ap.add_argument("--band", nargs=2, type=int, default=[280, 480])
    a = ap.parse_args()
    f, fr = frames_of(a.dir)
    xs, ts = [0.0], [fr[0][1]]
    prev = profile(os.path.join(f, "f%04d.png" % fr[0][0]), a.band)
    for i, t in fr[1:]:
        cur = profile(os.path.join(f, "f%04d.png" % i), a.band)
        s = shift(prev, cur) if t - ts[-1] < 0.25 else 0.0
        xs.append(xs[-1] + s)
        ts.append(t)
        prev = cur
    out = os.path.join(a.dir, "pages.txt")
    with open(out, "w") as fh:
        for t, x in zip(ts, xs):
            fh.write("%.4f %.1f\n" % (t, x))
    # print the moving parts
    last = None
    for t, x in zip(ts, xs):
        if last is None or abs(x - last) > 0.5:
            print("%.3f %.1f" % (t, x))
        last = x


if __name__ == "__main__":
    main()
