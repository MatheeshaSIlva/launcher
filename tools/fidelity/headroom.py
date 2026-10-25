#!/usr/bin/env python3
"""Headroom of two captures: how big is the harness's own noise between two PNGs of one state?

    python tools/fidelity/headroom.py A.png B.png [--region x,y,w,h] [--grid 12x24]

Prints one JSON line (ASCII) with diff.py's own maths on diff.py's default grid and tolerances:
  overall_mean       mean difference over the whole picture
  worst_cell_mean    the largest mean difference of any grid cell (flagged or not: diff.py reports only flagged ones)
  worst_cell         that cell's col, row and box
  flagged            cells diff.py would flag (mean >= 2.0 or >= 2 % of pixels over 12)
  flagged_outside    (with --region) flagged cells that are not wholly inside the region grown by one cell all round
Exit 0 when it printed, 2 when the pictures cannot be compared (as diff.py). It judges nothing: selfdiff.sh sets the limits
(overall <= 0.2, worst cell <= 0.5) and the sanity checks. Needs Pillow only.
"""
import argparse
import json
import os
import sys
from types import SimpleNamespace

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import diff  # noqa: E402  (the differ itself: no maths of its own here)
from PIL import Image  # noqa: E402

# diff.py's defaults (its argparse), used unchanged; test_headroom.py checks the counts against diff.py's own report
DEFAULTS = dict(pixel=12, tol_mean=2.0, tol_frac=0.02, grid=(12, 24))


def measure(ref_path, ours_path, region=None, grid=None):
    for p in (ref_path, ours_path):
        if not os.path.isfile(p):
            raise diff.CannotCompare("missing file: " + p)
    a = Image.open(ref_path).convert("RGB")
    b = Image.open(ours_path).convert("RGB")
    if a.size != b.size:
        raise diff.CannotCompare("size differs: %dx%d, %dx%d" % (a.size + b.size))
    opt = SimpleNamespace(**DEFAULTS)
    if grid:
        opt.grid = grid
    W, H = a.size
    d = diff.diff_image(a, b)
    cols, rows = opt.grid
    cw, ch = W / cols, H / rows
    overall = diff.stats(d, (0, 0, W, H), opt.pixel, None) or {"mean": 0.0}
    worst, flagged, outside = None, 0, 0
    if region:
        rx, ry, rw, rh = region
        grown = (rx - cw, ry - ch, rx + rw + cw, ry + rh + ch)
    for r in range(rows):
        for c in range(cols):
            box = (c * W // cols, r * H // rows, (c + 1) * W // cols, (r + 1) * H // rows)
            st = diff.stats(d, box, opt.pixel, None)
            if st is None:
                continue
            if worst is None or st["mean"] > worst["mean"]:
                worst = {"mean": st["mean"], "col": c, "row": r, "box": [box[0], box[1], box[2] - box[0], box[3] - box[1]]}
            if diff.flagged(st, opt):
                flagged += 1
                if region and not (box[0] >= grown[0] and box[1] >= grown[1] and box[2] <= grown[2] and box[3] <= grown[3]):
                    outside += 1
    res = {"overall_mean": overall["mean"], "worst_cell_mean": worst["mean"] if worst else 0.0,
           "worst_cell": worst, "flagged": flagged, "cells_total": cols * rows}
    if region:
        res["region"] = list(region)
        res["flagged_outside"] = outside
    return res


def main(argv=None):
    p = argparse.ArgumentParser(description="Headroom of two PNGs (see the file's docstring).")
    p.add_argument("a")
    p.add_argument("b")
    p.add_argument("--region", type=diff.parse_box, help="x,y,w,h: flagged cells outside it (plus one cell all round) are counted")
    p.add_argument("--grid", type=diff.parse_grid, default=None)
    opt = p.parse_args(argv)
    try:
        print(json.dumps(measure(opt.a, opt.b, opt.region, opt.grid)))
        return 0
    except diff.CannotCompare as e:
        print("cannot compare: %s" % e, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
