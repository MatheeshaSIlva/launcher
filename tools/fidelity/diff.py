#!/usr/bin/env python3
"""Fidelity differ: how far is OURS from the REFERENCE, region by region?

    python tools/fidelity/diff.py REF OURS [--out DIR] [options]

REF and OURS are two PNGs of the same size (same device, same state), or two folders: files with the same name
are compared. It prints a summary, writes DIR/report.json, DIR/heatmap.png and DIR/worst.png (reference | ours |
difference, for the worst cells), and exits 0 (within tolerance), 1 (over tolerance or a file missing) or 2 (the
pictures cannot be compared: missing path, different sizes).

A pixel "differs" when any colour channel is more than --pixel apart (default 12 of 255). The picture is cut into a
grid (--grid COLSxROWS, default 12x24) and a cell is flagged when its mean difference is >= --tol-mean (default
2.0) or the share of differing pixels is >= --tol-frac (default 0.02). Named regions (--regions FILE, JSON
{"name": [x, y, w, h]}) are scored the same way, so "the active tile" can have its own line.
Ignore what can never match (text in a font the emulator lacks, a clock) with --mask x,y,w,h (repeatable) or
--mask-file FILE (JSON: a list of boxes, or {"*": [...], "name.png": [...]} for folders).

The defaults are a starting point: calibrate them on two captures of the same state (their diff must pass) and
on a known-good region, then record the values in docs/DECISIONS.md. Needs Pillow only. ASCII output (Windows
consoles).
"""
import argparse
import json
import os
import sys

from PIL import Image, ImageChops, ImageDraw


class CannotCompare(Exception):
    pass


def parse_grid(s):
    c, r = s.lower().split("x")
    return int(c), int(r)


def parse_box(s):
    x, y, w, h = [int(v) for v in s.split(",")]
    return [x, y, w, h]


def diff_image(a, b):
    """Largest difference over the colour channels, 0..255, as an L image."""
    r, g, bl = ImageChops.difference(a, b).split()
    return ImageChops.lighter(ImageChops.lighter(r, g), bl)


def mask_image(size, boxes):
    if not boxes:
        return None
    m = Image.new("L", size, 0)
    d = ImageDraw.Draw(m)
    for x, y, w, h in boxes:
        d.rectangle([x, y, x + w - 1, y + h - 1], fill=255)
    return m


def stats(diff, box, pixel, ignore):
    """box = (x0, y0, x1, y1). Returns mean, share over `pixel` and max, or None when (nearly) all is ignored."""
    region = diff.crop(box)
    area = region.width * region.height
    ignored = 0
    if ignore is not None:
        m = ignore.crop(box)
        ignored = m.histogram()[255]
        if ignored:
            region = region.copy()
            region.paste(0, mask=m)
    valid = area - ignored
    if area <= 0 or valid < 0.1 * area:
        return None
    h = region.histogram()
    total = sum(i * n for i, n in enumerate(h))
    over = sum(h[pixel + 1:])
    mx = max((i for i, n in enumerate(h) if n), default=0)
    return {"mean": round(total / valid, 3), "frac": round(over / valid, 4), "max": mx}


def flagged(st, opt):
    return st is not None and (st["mean"] >= opt.tol_mean or st["frac"] >= opt.tol_frac)


def compare(ref_path, ours_path, opt, out_dir=None, masks=()):
    for p in (ref_path, ours_path):
        if not os.path.isfile(p):
            raise CannotCompare("missing file: " + p)
    a = Image.open(ref_path).convert("RGB")
    b = Image.open(ours_path).convert("RGB")
    if a.size != b.size:
        if not opt.allow_resize:
            raise CannotCompare("size differs: reference %dx%d, ours %dx%d (same device and state? or --allow-resize)"
                                % (a.size + b.size))
        b = b.resize(a.size, Image.LANCZOS)
    W, H = a.size
    d = diff_image(a, b)
    ignore = mask_image((W, H), masks)
    cols, rows = opt.grid
    cells = []
    for r in range(rows):
        for c in range(cols):
            box = (c * W // cols, r * H // rows, (c + 1) * W // cols, (r + 1) * H // rows)
            st = stats(d, box, opt.pixel, ignore)
            if st is None:
                continue
            st.update(col=c, row=r, box=[box[0], box[1], box[2] - box[0], box[3] - box[1]])
            st["flagged"] = flagged(st, opt)
            cells.append(st)
    regions = {}
    if opt.regions:
        with open(opt.regions, encoding="utf-8") as f:
            for name, (x, y, w, h) in json.load(f).items():
                st = stats(d, (x, y, x + w, y + h), opt.pixel, ignore)
                if st is not None:
                    st["flagged"] = flagged(st, opt)
                    st["box"] = [x, y, w, h]
                    regions[name] = st
    overall = stats(d, (0, 0, W, H), opt.pixel, ignore) or {"mean": 0.0, "frac": 0.0, "max": 0}
    bad_cells = sorted((c for c in cells if c["flagged"]), key=lambda c: -c["mean"])
    bad_regions = sorted(n for n, s in regions.items() if s["flagged"])
    report = {
        "reference": ref_path, "ours": ours_path, "size": [W, H],
        "tolerance": {"pixel": opt.pixel, "mean": opt.tol_mean, "frac": opt.tol_frac, "grid": list(opt.grid)},
        "overall": overall, "cells_total": len(cells), "cells_flagged": len(bad_cells),
        "worst": bad_cells[:10], "regions": regions, "regions_flagged": bad_regions,
        "pass": not bad_cells and not bad_regions,
    }
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
        with open(os.path.join(out_dir, "report.json"), "w", encoding="utf-8") as f:
            json.dump(report, f, indent=1)
        heatmap(b, d, bad_cells, regions, os.path.join(out_dir, "heatmap.png"))
        if bad_cells:
            worst_sheet(a, b, d, bad_cells[:opt.worst], os.path.join(out_dir, "worst.png"))
    return report


def heatmap(ours, d, bad_cells, regions, path):
    base = Image.blend(ours, Image.new("RGB", ours.size, (0, 0, 0)), 0.55)
    alpha = d.point(lambda v: 0 if v <= 6 else min(255, 90 + v * 3))
    img = Image.composite(Image.new("RGB", ours.size, (255, 40, 40)), base, alpha)
    dr = ImageDraw.Draw(img)
    for c in bad_cells:
        x, y, w, h = c["box"]
        dr.rectangle([x, y, x + w - 1, y + h - 1], outline=(255, 255, 0))
    for name, s in regions.items():
        if s["flagged"]:
            x, y, w, h = s["box"]
            dr.rectangle([x, y, x + w - 1, y + h - 1], outline=(0, 255, 255))
    img.save(path)


def worst_sheet(a, b, d, cells, path, margin=24, zoom=3):
    rows = []
    for c in cells:
        x, y, w, h = c["box"]
        box = (max(0, x - margin), max(0, y - margin), min(a.width, x + w + margin), min(a.height, y + h + margin))
        trio = [a.crop(box), b.crop(box), d.crop(box).point(lambda v: min(255, v * 4)).convert("RGB")]
        trio = [t.resize((t.width * zoom, t.height * zoom), Image.NEAREST) for t in trio]
        row = Image.new("RGB", (sum(t.width for t in trio) + 8, trio[0].height), (40, 40, 40))
        xo = 0
        for t in trio:
            row.paste(t, (xo, 0))
            xo += t.width + 4
        rows.append(row)
    sheet = Image.new("RGB", (max(r.width for r in rows), sum(r.height + 6 for r in rows)), (40, 40, 40))
    yo = 0
    for r in rows:
        sheet.paste(r, (0, yo))
        yo += r.height + 6
    sheet.save(path)


def line(name, rep):
    o = rep["overall"]
    w = rep["worst"][0] if rep["worst"] else None
    s = "%s %s: %d of %d cells over, overall mean %.2f, %.1f%% pixels differ" % (
        "PASS" if rep["pass"] else "FAIL", name, rep["cells_flagged"], rep["cells_total"], o["mean"], o["frac"] * 100)
    if w:
        s += "; worst col %d row %d box %s mean %.1f" % (w["col"], w["row"], ",".join(map(str, w["box"])), w["mean"])
    if rep["regions_flagged"]:
        s += "; regions over: " + ", ".join(rep["regions_flagged"])
    return s


def load_masks(opt):
    boxes = [parse_box(s) for s in (opt.mask or [])]
    per = {}
    if opt.mask_file:
        with open(opt.mask_file, encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, list):
            boxes += data
        else:
            boxes += data.get("*", [])
            per = {k: v for k, v in data.items() if k != "*"}
    return boxes, per


def main(argv=None):
    p = argparse.ArgumentParser(description="Region-by-region screenshot differ (see the file's docstring).")
    p.add_argument("ref")
    p.add_argument("ours")
    p.add_argument("--out", help="folder for report.json, heatmap.png, worst.png (a folder per file in folder mode)")
    p.add_argument("--pixel", type=int, default=12)
    p.add_argument("--tol-mean", type=float, default=2.0)
    p.add_argument("--tol-frac", type=float, default=0.02)
    p.add_argument("--grid", type=parse_grid, default=(12, 24))
    p.add_argument("--regions")
    p.add_argument("--mask", action="append")
    p.add_argument("--mask-file")
    p.add_argument("--worst", type=int, default=6, help="cells shown in worst.png")
    p.add_argument("--allow-resize", action="store_true")
    opt = p.parse_args(argv)
    try:
        boxes, per = load_masks(opt)
        if os.path.isdir(opt.ref) != os.path.isdir(opt.ours):
            raise CannotCompare("one is a folder and the other a file")
        if not os.path.isdir(opt.ref):
            rep = compare(opt.ref, opt.ours, opt, opt.out, boxes)
            print(line(os.path.basename(opt.ours), rep))
            return 0 if rep["pass"] else 1
        names = sorted(n for n in os.listdir(opt.ref) if n.lower().endswith(".png"))
        if not names:
            raise CannotCompare("no PNG files in " + opt.ref)
        summary, failed = {}, 0
        for n in names:
            if not os.path.isfile(os.path.join(opt.ours, n)):
                print("FAIL %s: missing in ours" % n)
                summary[n] = {"pass": False, "missing": True}
                failed += 1
                continue
            out = os.path.join(opt.out, os.path.splitext(n)[0]) if opt.out else None
            rep = compare(os.path.join(opt.ref, n), os.path.join(opt.ours, n), opt, out, boxes + per.get(n, []))
            print(line(n, rep))
            summary[n] = {"pass": rep["pass"], "cells_flagged": rep["cells_flagged"], "mean": rep["overall"]["mean"]}
            failed += 0 if rep["pass"] else 1
        if opt.out:
            os.makedirs(opt.out, exist_ok=True)
            with open(os.path.join(opt.out, "summary.json"), "w", encoding="utf-8") as f:
                json.dump(summary, f, indent=1)
        print("%d of %d files within tolerance" % (len(names) - failed, len(names)))
        return 0 if not failed else 1
    except CannotCompare as e:
        print("cannot compare: %s" % e, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
