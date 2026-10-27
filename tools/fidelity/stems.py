#!/usr/bin/env python3
"""Stem width of text in two captures: is ours' text as heavy as stock's (B-061, D-045)?

    python tools/fidelity/stems.py STOCK.png OURS.png --box NAME=x0,y0,x1,y1 [--box ...]
    python tools/fidelity/stems.py STOCK.png OURS.png --boxes BOXES.json      ({"NAME": [x0, y0, x1, y1], ...})

For each box, in each picture, prints the median width in px of the horizontal ink runs (the strokes a row crosses): a
measure of the weight that does not depend on the words, as long as the font and its size are the same. One JSON line
(ASCII): {"boxes": [{"name", "box", "stock": {"stem", "runs"}, "ours": {"stem", "runs"}, "delta"}]}; "stem" is null when a
box holds no ink, "delta" is ours minus stock.

How: the box in grey; its background is the median of its top and bottom rows (so a box must have background along both,
either polarity); each pixel counts as ink by its contrast with it, |grey - background| / CONTRAST, up to 1 (an antialiased
edge counts in part); a run is a row's ink summed over consecutive pixels above NOISE; runs under 1 px are dropped (stray
edge pixels); the stem is the upper median of the rest. It judges nothing: the plan sets the tolerance (B-061: 0.25 px).
Exit 0 when it printed, 2 when a picture or a box cannot be read. Needs Pillow only.
"""
import argparse
import json
import os
import sys

from PIL import Image

CONTRAST = 180.0   # grey difference counted as a whole pixel of ink (text on the shade's surfaces is ~200-240 from them)
NOISE = 0.05       # coverage under it is background
MIN_RUN = 1.0      # runs narrower than a pixel are edge fringe, not strokes


class CannotMeasure(Exception):
    pass


def runs_of(img, box):
    """The ink runs (px) of the rows of one box of a PIL picture."""
    x0, y0, x1, y1 = box
    W, H = img.size
    if not (0 <= x0 < x1 <= W and 0 <= y0 < y1 <= H):
        raise CannotMeasure("box %s is not inside the %dx%d picture" % (list(box), W, H))
    g = img.convert("L").crop(box)
    w, h = g.size
    px = g.load()
    border = sorted([px[x, 0] for x in range(w)] + [px[x, h - 1] for x in range(w)])
    bg = border[len(border) // 2]
    runs = []
    for y in range(h):
        r = 0.0
        for x in range(w):
            c = min(1.0, abs(px[x, y] - bg) / CONTRAST)
            if c > NOISE:
                r += c
            elif r > 0:
                runs.append(r)
                r = 0.0
        if r > 0:
            runs.append(r)
    return sorted(r for r in runs if r >= MIN_RUN)


def stem(img, box):
    runs = runs_of(img, box)
    return {"stem": round(runs[len(runs) // 2], 2) if runs else None, "runs": len(runs)}


def measure(stock_path, ours_path, boxes):
    pics = []
    for p in (stock_path, ours_path):
        if not os.path.isfile(p):
            raise CannotMeasure("missing file: " + p)
        pics.append(Image.open(p))
    out = []
    for name, box in boxes:
        s, o = stem(pics[0], box), stem(pics[1], box)
        delta = round(o["stem"] - s["stem"], 2) if s["stem"] is not None and o["stem"] is not None else None
        out.append({"name": name, "box": list(box), "stock": s, "ours": o, "delta": delta})
    return {"boxes": out}


def parse_box(text):
    name, sep, nums = text.partition("=")
    try:
        box = tuple(int(v) for v in nums.split(","))
    except ValueError:
        box = ()
    if not sep or not name or len(box) != 4:
        raise CannotMeasure("a box is NAME=x0,y0,x1,y1, not " + repr(text))
    return name, box


def load_boxes(path):
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        boxes = [(str(k), tuple(int(v) for v in b)) for k, b in data.items()]
    except (OSError, ValueError, AttributeError, TypeError) as e:
        raise CannotMeasure("cannot read boxes from %s: %s" % (path, e))
    if not boxes or any(len(b) != 4 for _, b in boxes):
        raise CannotMeasure(path + " holds no boxes of four numbers")
    return boxes


def main(argv=None):
    ap = argparse.ArgumentParser(description="Median stem width of text in boxes of two captures.")
    ap.add_argument("stock")
    ap.add_argument("ours")
    ap.add_argument("--box", action="append", default=[], help="NAME=x0,y0,x1,y1 (repeatable)")
    ap.add_argument("--boxes", help='JSON file {"NAME": [x0, y0, x1, y1], ...}')
    a = ap.parse_args(argv)
    try:
        boxes = [parse_box(b) for b in a.box] + (load_boxes(a.boxes) if a.boxes else [])
        if not boxes:
            raise CannotMeasure("no box given (--box or --boxes)")
        print(json.dumps(measure(a.stock, a.ours, boxes)))
    except (CannotMeasure, OSError) as e:
        print("stems.py: " + str(e), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
