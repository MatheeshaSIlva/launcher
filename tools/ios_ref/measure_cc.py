#!/usr/bin/env python3
"""
Control Center's release: where the first row of controls is in every frame (correlation with templates/cc_row.png, the
row of six test controls at rest, its centre at y 167), cut into releases (from its lowest point back up to rest) and
fitted to one spring over all of them.

    python tools/ios_ref/measure_cc.py SCENARIO_DIR [SCENARIO_DIR ...] [--rest 167]

Prints each release's samples, the joint fit and the overshoot (how far above rest it went).
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
from track import ncc  # noqa: E402


def frames_of(d):
    f = os.path.join(d, "f1")
    if not os.path.exists(os.path.join(f, "frames.txt")):
        subprocess.run([sys.executable, os.path.join(HERE, "frames.py"), os.path.join(d, "video.mp4"), f, "--scale", "1"],
                       check=True, capture_output=True)
    return f, [(int(a), float(b)) for a, b in (l.split() for l in open(os.path.join(f, "frames.txt")))]


def row_y(img, tpl):
    m = ncc(img[60:470, :], tpl)
    k = int(np.argmax(m))
    py, px = divmod(k, m.shape[1])
    return float(m[py, px]), 60 + py + tpl.shape[0] / 2.0, px + tpl.shape[1] / 2.0


def series(d, tpl):
    f, fr = frames_of(d)
    out = []
    for i, t in fr:
        img = np.asarray(Image.open(os.path.join(f, "f%04d.png" % i)).convert("L"), dtype=np.float32) / 255.0
        score, y, x = row_y(img, tpl)
        out.append((t, y if score > 0.72 and abs(x - 201) < 8 else None))
    return out


def releases(rows, rest):
    """From each lowest point (well below rest) to where it rests again: the release, repeated frames dropped."""
    out = []
    k = 0
    while k < len(rows):
        t, y = rows[k]
        if y is None or y < rest + 20:
            k += 1
            continue
        # walk to the lowest point of this excursion
        j = k
        while j + 1 < len(rows) and rows[j + 1][1] is not None and rows[j + 1][1] >= rows[j][1]:
            j += 1
        seg = [rows[j]]
        q = j + 1
        while q < len(rows) and rows[q][1] is not None and rows[q][0] - seg[-1][0] < 0.3:
            if rows[q][1] != seg[-1][1]:
                seg.append(rows[q])
            q += 1
        if len(seg) >= 6 and min(p[1] for p in seg) < rest + 2:
            out.append(seg)
        k = q
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dirs", nargs="+")
    ap.add_argument("--rest", type=float, default=167.0)
    a = ap.parse_args()
    tpl = np.asarray(Image.open(os.path.join(HERE, "templates", "cc_row.png")).convert("L"), dtype=np.float32) / 255.0
    runs = []
    for d in a.dirs:
        rows = series(d, tpl)
        for seg in releases(rows, a.rest):
            runs.append(seg)
            over = a.rest - min(p[1] for p in seg)
            print("%s: release from %.0f at %.3f, %d frames, overshoot %.1f pt" % (os.path.basename(d.rstrip("/")), seg[0][1], seg[0][0], len(seg), over))
    if not runs:
        print("no releases found")
        return
    f = sf.fit_joint([tuple(zip(*s)) for s in runs], target=a.rest)
    print("Control Center release, %d runs: response %.3f s, damping %.3f, rms %.2f pt" % (len(runs), f["response"], f["damping"], f["rms"]))
    for s in runs:
        g = sf.fit([p[0] for p in s], [p[1] for p in s], target=a.rest)
        print("   single: %.3f / %.3f (rms %.2f)" % (g["response"], g["damping"], g["rms"]))


if __name__ == "__main__":
    main()
