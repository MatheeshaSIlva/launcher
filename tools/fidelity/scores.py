#!/usr/bin/env python3
"""Scores of a fidelity baseline run: one table for every state and theme, and what the differences are (MF slice 3).

    python tools/fidelity/scores.py REPORTS --golden DIR --ours DIR --masks FILE --trees DIR [--md OUT.md] [--json OUT.json]

REPORTS holds diff.py's output per picture: REPORTS/<theme>/<state>/report.json (diff.py --out). --golden and --ours are the two
folders that were compared (<theme>/<state>.png), --masks the mask file used ({"state.png": [[x, y, w, h]]}, maskgen.py's), and
--trees stock's uiautomator trees (TREES/<theme>/<state>.xml or TREES/<state>.xml, maskgen.py's input) that name what a difference is.

It prints (and writes with --md / --json) a markdown table, a row per picture: cells flagged of total, the overall mean and the share
of differing pixels exactly as the report says, the share of the picture the masks hide, the worst cell and the named regions over
tolerance; then, per picture, the difference clusters. A cluster is a blob of pixels that differ by more than the report's pixel
tolerance, grown by 16 px so that the strokes of one word join; it is named by the tightest stock node around it: the smallest node
that covers at least 60 % of the cluster's box and has text, a description or an id (anonymous containers are skipped); else
the largest such node that the cluster covers by at least 60 %; else the best overlap; containers over 35 % of the picture are not
named. At most 14 clusters are listed per picture (the rest are counted: "N more not listed").
Cells flagged only by their mean (under 0.5 % of their pixels over the tolerance) are one tone line: a wash of colour or
brightness, not a shape. The flagged-cell count, the overall mean and the worst cell's mean are worked out again with diff.py's own functions and must
equal the report's.
Exit 0 printed; 2 an input missing or a report that does not match its pictures. Needs Pillow only. ASCII output.
"""
import argparse
import json
import os
import sys
from types import SimpleNamespace

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import diff  # noqa: E402  (the differ itself: its maths, unchanged)
import maskgen  # noqa: E402  (the trees' reader and the union area)
from PIL import Image, ImageFilter  # noqa: E402

STATES = ["quick", "expanded", "tile-off", "tile-on"]
THEMES = ["light", "dark"]
BLOCK = 8            # a cluster is found on 8 x 8 px blocks
GROW = 2             # grown by 2 blocks (16 px)
MIN_BLOCK_PX = 2     # a block counts when at least this many of its 64 pixels differ
TONE_FRAC = 0.005    # a flagged cell with less than this share of pixels over the tolerance is a tone cell
BIG_NODE = 0.35      # nodes larger than this share of the picture are containers, never a name
MAX_CLUSTERS = 14


class ScoreError(Exception):
    pass


def load_json(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError) as e:
        raise ScoreError("cannot read %s: %s" % (path, e))


def tree_for(trees, theme, state):
    for p in (os.path.join(trees, theme, state + ".xml"), os.path.join(trees, "light", state + ".xml"), os.path.join(trees, state + ".xml")):
        if os.path.isfile(p):
            return maskgen.load_tree(p)[0]
    raise ScoreError("no tree for %s under %s" % (state, trees))


def ascii_of(text):
    """Stock's words as plain ASCII (a bullet becomes \u2022): the output is ASCII."""
    return text.encode("ascii", "backslashreplace").decode("ascii")


def node_label(n, box=None):
    """The node's id and words; a node without words of its own lists up to two labels of the nodes inside it that the cluster touches."""
    parts = []
    if n.id:
        parts.append(n.id)
    if n.text.strip():
        parts.append("'%s'" % ascii_of(n.text.strip()[:40]))
    elif n.desc.strip():
        parts.append("'%s'" % ascii_of(n.desc.strip()[:40]))
    elif box is not None:
        inside = []
        for o in n.walk():
            w = ascii_of((o.text.strip() or o.desc.strip())[:40])
            if w and o.box and maskgen.intersects(box, o.box) and w not in inside:
                inside.append(w)
        if inside:
            parts.append("containing " + ", ".join("'%s'" % w for w in inside[:2]) + (", ..." if len(inside) > 2 else ""))
    return " ".join(parts) or "(a view without id)"


def useful(n):
    """A node worth naming a difference by: it has text, a description or an id."""
    return bool(n.id or n.text.strip() or n.desc.strip())


def name_box(nodes, box, size):
    """The stock node that names a cluster's box; None when nothing in the tree is there.
    1. the smallest useful node that covers at least 60 % of the box (the tightest thing around the difference);
    2. the useful node the box covers by at least 60 % with the most overlap (a box that spans several nodes);
    3. the useful node with the best overlap (IoU); 4. the same over every node when no useful one is there."""
    area_box = max(1, (box[2] - box[0]) * (box[3] - box[1]))
    cands = []   # (node, node area, intersection)
    for n in nodes:
        if not n.box or n.box[2] <= n.box[0] or n.box[3] <= n.box[1]:
            continue
        na = (n.box[2] - n.box[0]) * (n.box[3] - n.box[1])
        if na > BIG_NODE * size[0] * size[1]:
            continue
        ix = min(box[2], n.box[2]) - max(box[0], n.box[0])
        iy = min(box[3], n.box[3]) - max(box[1], n.box[1])
        if ix <= 0 or iy <= 0:
            continue
        cands.append((n, na, ix * iy))
    for pool in ([c for c in cands if useful(c[0])], cands):
        around = [c for c in pool if c[2] / float(area_box) >= 0.6]
        if around:
            return min(around, key=lambda c: (c[1], -c[2]))[0]
        inside = [c for c in pool if c[2] / float(c[1]) >= 0.6]
        if inside:
            return max(inside, key=lambda c: (c[2], -c[1]))[0]
        if pool:
            return max(pool, key=lambda c: c[2] / float(c[1] + area_box - c[2]))[0]
    return None


def clusters_of(d, pixel, size):
    """Blobs of pixels over `pixel` (d: the masked difference image): [(box x0,y0,x1,y1, differing pixels)], largest first."""
    W, H = size
    binary = d.point(lambda v: 255 if v > pixel else 0)
    bw, bh = (W + BLOCK - 1) // BLOCK, (H + BLOCK - 1) // BLOCK
    small = binary.resize((bw, bh), Image.BOX).point(lambda v: 255 if v >= MIN_BLOCK_PX * 255 // (BLOCK * BLOCK) else 0)
    grown = small.filter(ImageFilter.MaxFilter(2 * GROW + 1))
    data = list(grown.tobytes())
    sm = small.load()
    seen = [False] * (bw * bh)
    out = []
    for start in range(bw * bh):
        if not data[start] or seen[start]:
            continue
        stack, cells = [start], []
        seen[start] = True
        while stack:
            c = stack.pop()
            cells.append(c)
            cx, cy = c % bw, c // bw
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    nx, ny = cx + dx, cy + dy
                    if 0 <= nx < bw and 0 <= ny < bh:
                        k = ny * bw + nx
                        if data[k] and not seen[k]:
                            seen[k] = True
                            stack.append(k)
        real = [c for c in cells if sm[c % bw, c // bw]]   # the blocks that differ themselves (the growth only joins them)
        if not real:
            continue
        x0 = min(c % bw for c in real) * BLOCK
        y0 = min(c // bw for c in real) * BLOCK
        x1 = min(W, (max(c % bw for c in real) + 1) * BLOCK)
        y1 = min(H, (max(c // bw for c in real) + 1) * BLOCK)
        n = binary.crop((x0, y0, x1, y1)).histogram()[255]
        out.append(((x0, y0, x1, y1), n))
    out.sort(key=lambda t: (-t[1], t[0]))
    return out


def recount(ref, ours, masks, rep):
    """diff.py's own functions on the same pictures: (cells with their stats, the overall stats, the masked difference image, size, options)."""
    a = Image.open(ref).convert("RGB")
    b = Image.open(ours).convert("RGB")
    W, H = a.size
    tol = rep["tolerance"]
    opt = SimpleNamespace(pixel=tol["pixel"], tol_mean=tol["mean"], tol_frac=tol["frac"], grid=tuple(tol["grid"]))
    d = diff.diff_image(a, b)
    ignore = diff.mask_image((W, H), masks)
    cols, rows = opt.grid
    cells = []
    for r in range(rows):
        for c in range(cols):
            box = (c * W // cols, r * H // rows, (c + 1) * W // cols, (r + 1) * H // rows)
            st = diff.stats(d, box, opt.pixel, ignore)
            if st is None:
                continue
            st.update(col=c, row=r, box=[box[0], box[1], box[2] - box[0], box[3] - box[1]])
            st["flagged"] = diff.flagged(st, opt)
            cells.append(st)
    overall = diff.stats(d, (0, 0, W, H), opt.pixel, ignore) or {"mean": 0.0, "frac": 0.0, "max": 0}
    masked = d.copy()
    if ignore is not None:
        masked.paste(0, mask=ignore)
    return cells, overall, masked, (W, H), opt


def fmt_box(b):
    return ",".join(str(v) for v in b)


def score_one(reports, golden, ours, mask_data, trees, theme, state):
    rep = load_json(os.path.join(reports, theme, state, "report.json"))
    ref = os.path.join(golden, theme, state + ".png")
    out = os.path.join(ours, theme, state + ".png")
    for p in (ref, out):
        if not os.path.isfile(p):
            raise ScoreError("missing picture " + p)
    name = state + ".png"
    boxes = list(mask_data.get("*", [])) + list(mask_data.get(name, [])) if isinstance(mask_data, dict) else list(mask_data)
    cells, overall, masked, size, opt = recount(ref, out, boxes, rep)
    flagged = [c for c in cells if c["flagged"]]
    if len(flagged) != rep["cells_flagged"] or len(cells) != rep["cells_total"]:
        raise ScoreError("%s/%s: the report says %d of %d cells flagged, diff.py's functions on the same pictures and masks say %d of %d "
                         "(were the masks the ones diff.py ran with?)" % (theme, state, rep["cells_flagged"], rep["cells_total"], len(flagged), len(cells)))
    worst_mean = max((c["mean"] for c in flagged), default=None)
    rep_worst = rep["worst"][0]["mean"] if rep["worst"] else None
    if overall["mean"] != rep["overall"]["mean"] or overall["frac"] != rep["overall"]["frac"] or worst_mean != rep_worst:
        raise ScoreError("%s/%s: the report's overall mean %s (%s of pixels over), worst cell %s differ from diff.py's functions on the same "
                         "pictures and masks: %s (%s), %s" % (theme, state, rep["overall"]["mean"], rep["overall"]["frac"], rep_worst,
                                                              overall["mean"], overall["frac"], worst_mean))
    share = maskgen.union_area([(x, y, x + w, y + h) for x, y, w, h in boxes]) / float(size[0] * size[1])
    nodes = tree_for(trees, theme, state)
    tone = [c for c in flagged if c["frac"] < TONE_FRAC]
    named = []
    every = clusters_of(masked, opt.pixel, size)
    for box, n in every[:MAX_CLUSTERS]:
        node = name_box(nodes, box, size)
        crop = masked.crop(box)
        area = max(1, crop.width * crop.height)
        hist = crop.histogram()
        over = [rn for rn, rs in rep["regions"].items() if rs["flagged"] and maskgen.intersects(
            box, (rs["box"][0], rs["box"][1], rs["box"][0] + rs["box"][2], rs["box"][1] + rs["box"][3]))]
        named.append({"box": [box[0], box[1], box[2] - box[0], box[3] - box[1]], "differing_pixels": n,
                      "mean": round(sum(i * v for i, v in enumerate(hist)) / float(area), 2),
                      "node": node_label(node, box) if node else "(nothing in stock's tree)",
                      "node_box": [node.box[0], node.box[1], node.box[2] - node.box[0], node.box[3] - node.box[1]] if node else None,
                      "regions": sorted(over)})
    worst = rep["worst"][0] if rep["worst"] else None
    return {
        "state": state, "theme": theme, "cells_flagged": rep["cells_flagged"], "cells_total": rep["cells_total"],
        "overall_mean": rep["overall"]["mean"], "pixels_differing_pct": round(rep["overall"]["frac"] * 100, 2),
        "mask_share_pct": round(share * 100, 2), "mask_boxes": len(boxes),
        "worst": {"col": worst["col"], "row": worst["row"], "box": worst["box"], "mean": worst["mean"]} if worst else None,
        "regions_flagged": rep["regions_flagged"], "regions": {k: {"mean": v["mean"], "frac": v["frac"], "flagged": v["flagged"]} for k, v in rep["regions"].items()},
        "tone_cells": [{"col": c["col"], "row": c["row"], "mean": c["mean"]} for c in tone],
        "pass": rep["pass"], "clusters": named, "clusters_more": max(0, len(every) - MAX_CLUSTERS),
        "report": "%s/%s/report.json" % (theme, state), "worst_png": "%s/%s/worst.png" % (theme, state) if worst else None,
    }


def table(rows):
    lines = ["| state | theme | cells flagged / total | overall mean | % pixels differing | mask share | worst cell | regions over tolerance |",
             "|---|---|---|---|---|---|---|---|"]
    for r in rows:
        w = r["worst"]
        worst = "col %d, row %d, box %s, mean %s" % (w["col"], w["row"], fmt_box(w["box"]), w["mean"]) if w else "none"
        lines.append("| %s | %s | %d / %d | %s | %.2f | %.2f %% | %s | %s |" % (
            r["state"], r["theme"], r["cells_flagged"], r["cells_total"], r["overall_mean"], r["pixels_differing_pct"], r["mask_share_pct"],
            worst, ", ".join(r["regions_flagged"]) or "none"))
    return lines


def details(rows):
    lines = []
    for r in rows:
        lines.append("")
        lines.append("### %s, %s" % (r["state"], r["theme"]))
        lines.append("")
        lines.append("Report: %s" % r["report"] + ("; worst cells: %s" % r["worst_png"] if r["worst_png"] else ""))
        if r["tone_cells"]:
            ms = [c["mean"] for c in r["tone_cells"]]
            lines.append("")
            lines.append("Tone: %d of the flagged cells carry less than %.1f %% differing pixels (a wash of colour or brightness, mean %s to %s)." % (
                len(r["tone_cells"]), TONE_FRAC * 100, min(ms), max(ms)))
        lines.append("")
        lines.append("| cluster box (x,y,w,h) | differing px | mean diff in box | stock node that names it | regions over |")
        lines.append("|---|---|---|---|---|")
        for c in r["clusters"]:
            lines.append("| %s | %d | %s | %s | %s |" % (fmt_box(c["box"]), c["differing_pixels"], c["mean"], c["node"], ", ".join(c["regions"]) or "none"))
        if not r["clusters"]:
            lines.append("| none | | | | |")
        if r["clusters_more"]:
            lines.append("")
            lines.append("%d more not listed (the %d largest are shown)." % (r["clusters_more"], MAX_CLUSTERS))
    return lines


def main(argv=None):
    p = argparse.ArgumentParser(description="Scores of a fidelity baseline (see the file's docstring).")
    p.add_argument("reports")
    p.add_argument("--golden", required=True)
    p.add_argument("--ours", required=True)
    p.add_argument("--masks", required=True)
    p.add_argument("--trees", required=True)
    p.add_argument("--md")
    p.add_argument("--json")
    opt = p.parse_args(argv)
    try:
        mask_data = load_json(opt.masks)
        rows = []
        for state in STATES:
            for theme in THEMES:
                if os.path.isfile(os.path.join(opt.reports, theme, state, "report.json")):
                    rows.append(score_one(opt.reports, opt.golden, opt.ours, mask_data, opt.trees, theme, state))
        if not rows:
            raise ScoreError("no report.json under %s/<theme>/<state>/" % opt.reports)
    except ScoreError as e:
        print("scores: %s" % e, file=sys.stderr)
        return 2
    md = table(rows) + details(rows)
    print("\n".join(md))
    if opt.md:
        with open(opt.md, "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(md) + "\n")
    if opt.json:
        with open(opt.json, "w", encoding="utf-8", newline="\n") as f:
            json.dump(rows, f, indent=1, sort_keys=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
