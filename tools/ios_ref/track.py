#!/usr/bin/env python3
"""
Follows one element through a recording: its centre and scale in every frame, found by normalised cross-correlation of
the element as it looks at rest (a rectangle of a reference frame) against each frame, over a range of scales. Fading
and a changing background behind it do not matter much (the correlation ignores brightness and contrast).

    python tools/ios_ref/track.py FRAMES_DIR REF_INDEX X Y W H [--scales 0.2:1.3:0.02] [--from T0] [--to T1] [--out FILE]

FRAMES_DIR comes from frames.py (fNNNN.png + frames.txt); X Y W H are in that folder's pixels. Prints and writes
"t cx cy scale score" per frame (score < ~0.5: not found, e.g. not on screen yet).
"""
import argparse
import os
import sys

import numpy as np
from PIL import Image
from scipy.signal import fftconvolve


def load(path):
    return np.asarray(Image.open(path).convert("L"), dtype=np.float32) / 255.0


def ncc(img, tpl):
    """Normalised cross-correlation of tpl over img (valid positions); returns the map."""
    th, tw = tpl.shape
    t = tpl - tpl.mean()
    tn = np.sqrt((t * t).sum())
    if tn < 1e-6:
        return None
    num = fftconvolve(img, t[::-1, ::-1], mode="valid")
    ones = np.ones_like(tpl)
    s1 = fftconvolve(img, ones, mode="valid")
    s2 = fftconvolve(img * img, ones, mode="valid")
    n = tpl.size
    var = np.maximum(s2 - s1 * s1 / n, 1e-9)
    return num / (np.sqrt(var) * tn)


def track(frames_dir, ref_index, rect, scales, t0=None, t1=None):
    frames = [tuple(l.split()) for l in open(os.path.join(frames_dir, "frames.txt")) if l.strip()]
    frames = [(int(i), float(t)) for i, t in frames]
    ref = load(os.path.join(frames_dir, "f%04d.png" % ref_index))
    x, y, w, h = rect
    patch = Image.fromarray((ref[y:y + h, x:x + w] * 255).astype(np.uint8))
    out = []
    prev = None
    for i, t in frames:
        if (t0 is not None and t < t0) or (t1 is not None and t > t1):
            continue
        img = load(os.path.join(frames_dir, "f%04d.png" % i))
        best = (-2, 0, 0, 0)
        cand = scales if prev is None else [s for s in scales if abs(s - prev) <= 0.25] or scales
        for s in cand:
            tw, th = max(4, int(round(w * s))), max(4, int(round(h * s)))
            if tw >= img.shape[1] or th >= img.shape[0]:
                continue
            tpl = np.asarray(patch.resize((tw, th), Image.BILINEAR), dtype=np.float32) / 255.0
            m = ncc(img, tpl)
            if m is None:
                continue
            k = int(np.argmax(m))
            py, px = divmod(k, m.shape[1])
            if m[py, px] > best[0]:
                best = (float(m[py, px]), px + tw / 2.0, py + th / 2.0, s)
        score, cx, cy, s = best
        prev = s if score > 0.6 else prev
        out.append((t, cx, cy, s, score))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("frames")
    ap.add_argument("ref", type=int)
    ap.add_argument("x", type=int)
    ap.add_argument("y", type=int)
    ap.add_argument("w", type=int)
    ap.add_argument("h", type=int)
    ap.add_argument("--scales", default="0.2:1.3:0.02")
    ap.add_argument("--from", dest="t0", type=float)
    ap.add_argument("--to", dest="t1", type=float)
    ap.add_argument("--out")
    a = ap.parse_args()
    lo, hi, st = map(float, a.scales.split(":"))
    scales = list(np.round(np.arange(lo, hi + st / 2, st), 4))
    rows = track(a.frames, a.ref, (a.x, a.y, a.w, a.h), scales, a.t0, a.t1)
    lines = ["%.4f %.1f %.1f %.3f %.3f" % r for r in rows]
    print("# t cx cy scale score")
    print("\n".join(lines))
    if a.out:
        with open(a.out, "w") as fh:
            fh.write("# t cx cy scale score\n" + "\n".join(lines) + "\n")


if __name__ == "__main__":
    sys.exit(main())
