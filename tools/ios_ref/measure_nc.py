#!/usr/bin/env python3
"""
Notification Center (iOS 27's cover sheet): where its bottom edge is in every frame, found by the home indicator bar it
carries (a bright bar ~140 pt wide, centred), then one spring fitted jointly over every open and every close in the
given scenario folders (test08_ncOpenClose of several passes).

    python tools/ios_ref/measure_nc.py SCENARIO_DIR [SCENARIO_DIR ...]

Only frames after the finger let go are used: below the finger's last point when opening (the drag ends at y 541),
above it when closing (it ends at 393). Positions are the bar's centre in points; at rest open it is at 863 (sheet
bottom 874), closed at -11.
"""
import os
import subprocess
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
import springfit as sf  # noqa: E402

HERE = os.path.dirname(__file__)


def frames_of(d):
    f = os.path.join(d, "f1")
    if not os.path.exists(os.path.join(f, "frames.txt")):
        subprocess.run([sys.executable, os.path.join(HERE, "frames.py"), os.path.join(d, "video.mp4"), f, "--scale", "1"],
                       check=True, capture_output=True)
    return f, [(int(a), float(b)) for a, b in (l.split() for l in open(os.path.join(f, "frames.txt")))]


def bar_y(a):
    h = a.shape[0]
    best = None
    for y in range(4, h - 4):
        row = a[y, 120:282]
        if (row > 215).sum() >= 95:
            c = a[y, 140:262].mean() - 0.5 * (a[y - 4, 140:262].mean() + a[y + 4, 140:262].mean())
            if c > 25 and (best is None or c > best[1]):
                best = (y, c)
    return None if best is None else best[0]


def series(d):
    f, fr = frames_of(d)
    out = []
    for i, t in fr:
        a = np.asarray(Image.open(os.path.join(f, "f%04d.png" % i)).convert("L"), dtype=np.int16)
        out.append((t, bar_y(a)))
    return out


def split(rows):
    """Opens (bar going down to rest) and closes (going up and off), as lists of (t, y), dropping repeated frames."""
    opens, closes = [], []
    cur, prev = [], None
    for t, y in rows:
        if y is None:
            if cur:
                (closes if cur[-1][1] < cur[0][1] else opens).append(cur)
            cur, prev = [], None
            continue
        if prev is not None and y == prev:
            continue  # the screen did not change (a stalled frame): its time says nothing
        cur.append((t, y))
        prev = y
    if cur:
        (closes if cur[-1][1] < cur[0][1] else opens).append(cur)
    return opens, closes


def main():
    all_open, all_close = [], []
    for d in sys.argv[1:]:
        rows = series(d)
        # an open: from the bar's first appearance below the top until it rests; a close: from rest until it is gone
        seg, last = [], None
        for t, y in rows:
            if y is None:
                if seg:
                    all_close.append(seg) if seg[-1][1] < 300 else None
                seg = []
                last = None
                continue
            if last is not None and y == last:
                continue
            seg.append((t, y))
            last = y
        # opens: rising runs ending at 863 within the same visible stretch
        cur = []
        for t, y in rows:
            if y is None:
                cur = []
                continue
            cur.append((t, y))
            if y >= 862 and len(cur) > 3:
                pts = [(a, b) for a, b in cur if b > 548]
                dedup = [p for k, p in enumerate(pts) if k == 0 or p[1] != pts[k - 1][1]]
                if len(dedup) >= 3:
                    all_open.append(dedup + [(pts[-1][0] + 0.25, 863), (pts[-1][0] + 0.5, 863)])
                cur = []
    closes = []
    for seg in all_close:
        pts = [(a, b) for a, b in seg if b < 370]
        if len(pts) >= 3:
            closes.append(pts)
    print("opens %d, closes %d" % (len(all_open), len(closes)))
    if all_open:
        f = sf.fit_joint([tuple(zip(*o)) for o in all_open], target=863)
        print("NC open  (after release): response %.3f s, damping %.3f, rms %.1f pt" % (f["response"], f["damping"], f["rms"]))
        for s in f["starts"]:
            print("   start %.3f at %.0f, %.0f pt/s" % s)
    if closes:
        f = sf.fit_joint([tuple(zip(*c)) for c in closes], target=-11)
        print("NC close (after release): response %.3f s, damping %.3f, rms %.1f pt" % (f["response"], f["damping"], f["rms"]))
        for s in f["starts"]:
            print("   start %.3f at %.0f, %.0f pt/s" % s)


if __name__ == "__main__":
    main()
