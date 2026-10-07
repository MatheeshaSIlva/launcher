#!/usr/bin/env python3
"""
Follows a card that stands out by brightness (an app's white launch screen growing out of its icon, a light banner, a
sheet): in every frame, the connected region of pixels brighter (or darker) than a threshold that contains a seed point,
and its bounding box. Prints "t left top right bottom cx cy width height" per frame (px of the frames folder).

    python tools/ios_ref/bbox.py FRAMES_DIR SEED_X SEED_Y [--min 225] [--max] [--from T0] [--to T1] [--out FILE]

--min L: pixels with luminance >= L belong to the card (default 225); --dark: <= L instead (a dark card).
"""
import argparse
import os
import sys

import numpy as np
from PIL import Image
from scipy import ndimage


def bbox(frames_dir, seed, level=225, dark=False, t0=None, t1=None):
    frames = [l.split() for l in open(os.path.join(frames_dir, "frames.txt")) if l.strip()]
    out = []
    for i, t in frames:
        t = float(t)
        if (t0 is not None and t < t0) or (t1 is not None and t > t1):
            continue
        a = np.asarray(Image.open(os.path.join(frames_dir, "f%04d.png" % int(i))).convert("L"), dtype=np.int16)
        mask = a <= level if dark else a >= level
        mask = ndimage.binary_opening(mask, iterations=1)
        lab, n = ndimage.label(mask)
        sx, sy = seed
        k = lab[min(sy, a.shape[0] - 1), min(sx, a.shape[1] - 1)]
        if k == 0:
            out.append((t, None))
            continue
        ys, xs = np.nonzero(lab == k)
        out.append((t, (xs.min(), ys.min(), xs.max() + 1, ys.max() + 1)))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("frames")
    ap.add_argument("x", type=int)
    ap.add_argument("y", type=int)
    ap.add_argument("--min", dest="level", type=int, default=225)
    ap.add_argument("--dark", action="store_true")
    ap.add_argument("--from", dest="t0", type=float)
    ap.add_argument("--to", dest="t1", type=float)
    ap.add_argument("--out")
    a = ap.parse_args()
    rows = bbox(a.frames, (a.x, a.y), a.level, a.dark, a.t0, a.t1)
    lines = []
    for t, b in rows:
        if b is None:
            lines.append("%.4f nan nan nan nan nan nan nan nan" % t)
        else:
            l, tp, r, bt = b
            lines.append("%.4f %d %d %d %d %.1f %.1f %d %d" % (t, l, tp, r, bt, (l + r) / 2, (tp + bt) / 2, r - l, bt - tp))
    print("# t left top right bottom cx cy width height")
    print("\n".join(lines))
    if a.out:
        with open(a.out, "w") as fh:
            fh.write("# t left top right bottom cx cy width height\n" + "\n".join(lines) + "\n")


if __name__ == "__main__":
    sys.exit(main())
