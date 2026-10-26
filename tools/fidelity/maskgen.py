#!/usr/bin/env python3
"""Mask and region generator for the fidelity diff (MF slice 3, D-038, D-041).

    python tools/fidelity/maskgen.py generate --rules tools/fidelity/mask-rules.json --dump DIR
        --out-masks tools/fidelity/masks/pixel-system-font.json --out-regions tools/fidelity/masks/pixel-regions.json
        [--out-provenance FILE]
    python tools/fidelity/maskgen.py pick FILE STATE.png      (one state's list of masks, or of regions, as diff.py reads it)

DIR is a stock capture made with `capture.sh --side stock --dump` (DIR/stock/<theme>/<state>.xml, or DIR itself holding
<theme>/<state>.xml): stock's uiautomator tree of each state. The rules (mask-rules.json) say which nodes of that tree hide
parts of the picture and why; the result is a mask file for `diff.py --mask-file` ({"state.png": [[x, y, w, h], ...]}: the
union over the themes, the geometry is the same in light and dark) and a regions file ({"state.png": {"name": [x, y, w, h]}},
one entry per tile, the header, the first notification card and the footer, from the same trees).

Rule kinds (every rule has an "id", a "why" and an "evidence"; "enabled": false keeps a rule that was weighed and not needed;
"states": [...] limits a rule to those states; "required": true fails the run when it matches nothing where it applies):
  node    {"match": {"resource_id": RE, "text": RE, "content_desc": RE}, "pad": N | [PX, PY], "center": [W, H], "text_node": true,
           "limit": [{"match": MATCH, "scope": RE, "from": "left" | "top"}]}
          the bounds of every node matching (all given regexes, re.search) grown by pad; with "center" the box is W x H around
          the node's centre instead (a button whose words are the family-named part, never its fill). A "limit" keeps the growth
          off a neighbour: the box stops at the left (from "left") or top (from "top") edge of every node matching MATCH inside the
          nearest ancestor of the node whose resource-id matches "scope" (no such ancestor: the run fails) that lies beyond the
          node's own edge (starts at or right of its right edge, or at or below its bottom edge) and overlaps the grown box across
          the other axis. The node's own bounds are never cut: the pad is what is limited.
  below   {"anchor": MATCH, "climb": RE, "until": MATCH, "pad": ...}   from the bottom of the anchor node (or its nearest ancestor whose
          resource-id matches "climb") to the bottom of the "until" node, across that node's width
  before  {"anchor": MATCH, "until": MATCH, "pad": ...}   the row of the first node, from its left edge to the left edge of the second
A pad may be negative (it shrinks the box).
A mask that is not a family-named text node's own bounds ("text_node": true) must not touch a tile, the header or the first
notification card (the regions marked "protect"): the run fails. Each state's masks may cover at most "max_share" (default 0.06)
of the picture: over it the run fails and names the rule that takes the most. Deterministic: no clock, sorted output.
Exit 0 done, 1 a rule failed (a limit, a protected region, a required rule matching nothing), 2 usage or unreadable input.
ASCII output, Python 3 standard library only.
"""
import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
KEYS = {"resource_id": "resource-id", "text": "text", "content_desc": "content-desc"}


class MaskError(Exception):
    pass


class Node:
    def __init__(self, el, parent):
        a = el.attrib
        self.id = a.get("resource-id", "").split("/")[-1]
        self.text = a.get("text", "")
        self.desc = a.get("content-desc", "")
        m = BOUNDS.fullmatch(a.get("bounds", ""))
        self.box = tuple(int(v) for v in m.groups()) if m else None   # x0, y0, x1, y1
        self.parent = parent
        self.children = []

    def attr(self, key):
        return {"resource_id": self.id, "text": self.text, "content_desc": self.desc}[key]

    def walk(self):
        yield self
        for c in self.children:
            yield from c.walk()


def load_tree(path):
    """The nodes of a uiautomator dump, in document order, and the picture's size (the root node's bounds)."""
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as e:
        raise MaskError("cannot read %s: %s" % (path, e))
    nodes = []

    def rec(el, parent):
        for ch in el:
            if ch.tag != "node":
                continue
            n = Node(ch, parent)
            if parent is not None:
                parent.children.append(n)
            nodes.append(n)
            rec(ch, n)

    rec(root, None)
    top = [n for n in nodes if n.box]
    if not top:
        raise MaskError("%s has no node with bounds" % path)
    w = max(n.box[2] for n in top)
    h = max(n.box[3] for n in top)
    return nodes, (w, h)


def matches(node, match):
    if not node.box:
        return False
    for key, rx in match.items():
        if key not in KEYS:
            raise MaskError("unknown match key '%s' (use resource_id, text, content_desc)" % key)
        if not re.search(rx, node.attr(key)):
            return False
    return True


def find(nodes, match):
    return [n for n in nodes if matches(n, match)]


def clip(box, size):
    x0, y0, x1, y1 = box
    x0, y0, x1, y1 = max(0, x0), max(0, y0), min(size[0], x1), min(size[1], y1)
    return (x0, y0, x1, y1) if x1 > x0 and y1 > y0 else None


def pad_box(box, pad):
    px, py = (pad, pad) if isinstance(pad, int) else pad
    return (box[0] - px, box[1] - py, box[2] + px, box[3] + py)


def center_box(box, wh):
    cx, cy = (box[0] + box[2]) // 2, (box[1] + box[3]) // 2
    return (cx - wh[0] // 2, cy - wh[1] // 2, cx - wh[0] // 2 + wh[0], cy - wh[1] // 2 + wh[1])


def limited(box, n, limits):
    """box (the grown bounds of node n) cut at the edges the rule's limits name; never inside n's own bounds."""
    x0, y0, x1, y1 = box
    for lim in limits:
        side = lim.get("from")
        if side not in ("left", "top"):
            raise MaskError("a limit's 'from' is 'left' or 'top', not %r" % (side,))
        scope = n.parent
        while scope is not None and not re.search(lim["scope"], scope.id):
            scope = scope.parent
        if scope is None:
            raise MaskError("a limit's scope '%s' is no ancestor of node %s" % (lim["scope"], n.id or "?"))
        for o in scope.walk():
            if o is n or not matches(o, lim["match"]):
                continue
            if side == "left" and o.box[0] >= n.box[2] and o.box[0] < x1 and o.box[1] < y1 and y0 < o.box[3]:
                x1 = o.box[0]
            elif side == "top" and o.box[1] >= n.box[3] and o.box[1] < y1 and o.box[0] < x1 and x0 < o.box[2]:
                y1 = o.box[1]
    return (x0, y0, x1, y1)


def to_xywh(box):
    return [box[0], box[1], box[2] - box[0], box[3] - box[1]]


def union_area(boxes):
    """Area of the union of boxes (x0, y0, x1, y1), by coordinate compression."""
    if not boxes:
        return 0
    xs = sorted({b[0] for b in boxes} | {b[2] for b in boxes})
    area = 0
    for i in range(len(xs) - 1):
        xa, xb = xs[i], xs[i + 1]
        spans = sorted((b[1], b[3]) for b in boxes if b[0] <= xa and b[2] >= xb)
        cur_end, covered = None, 0
        start = None
        for s, e in spans:
            if cur_end is None or s > cur_end:
                if cur_end is not None:
                    covered += cur_end - start
                start, cur_end = s, e
            else:
                cur_end = max(cur_end, e)
        if cur_end is not None:
            covered += cur_end - start
        area += covered * (xb - xa)
    return area


def intersects(a, b):
    return a[0] < b[2] and b[0] < a[2] and a[1] < b[3] and b[1] < a[3]


def slug(s):
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


def first_label(node):
    for n in node.walk():
        if n.text.strip():
            return n.text
    for n in node.walk():
        if n.desc.strip():
            return n.desc
    return ""


def make_regions(rules, nodes, size):
    """{name: box} from the rules' "regions" list; protect flag per name."""
    out, protect = {}, set()
    for r in rules.get("regions", []):
        found = find(nodes, r["match"])
        if r.get("first"):
            found = found[:1]
        if not found:
            continue
        if r.get("union"):
            boxes = [clip(n.box, size) for n in found]
            boxes = [b for b in boxes if b]
            if not boxes:
                continue
            groups = [(r["name"], (min(b[0] for b in boxes), min(b[1] for b in boxes), max(b[2] for b in boxes), max(b[3] for b in boxes)))]
        else:
            groups = []
            for i, n in enumerate(found, 1):
                b = clip(n.box, size)
                if b:
                    groups.append((r["name"].format(n=i, label=slug(first_label(n)) or "tile"), b))
        for name, b in groups:
            if name in out:
                raise MaskError("region name '%s' is made twice (state: rule %s)" % (name, r.get("id", "?")))
            out[name] = b
            if r.get("protect"):
                protect.add(name)
    return out, protect


def rule_boxes(rule, nodes, size):
    """The boxes (x0, y0, x1, y1) one rule makes on one tree, each with the ids of the nodes it came from."""
    kind = rule["kind"]
    res = []
    if kind == "node":
        for n in find(nodes, rule["match"]):
            b = center_box(n.box, rule["center"]) if "center" in rule else n.box
            b = pad_box(b, rule.get("pad", 0))
            if rule.get("limit"):
                b = limited(b, n, rule["limit"])
            b = clip(b, size)
            if b:
                res.append((b, n.id or "?"))
    elif kind == "below":
        for a in find(nodes, rule["anchor"])[:1]:
            edge = a
            if rule.get("climb"):
                p = a
                while p is not None and not re.search(rule["climb"], p.id):
                    p = p.parent
                if p is None or not p.box:
                    continue
                edge = p
            ends = find(nodes, rule["until"])[:1]
            if not ends:
                continue
            b = clip(pad_box((ends[0].box[0], edge.box[3], ends[0].box[2], ends[0].box[3]), rule.get("pad", 0)), size)
            if b:
                res.append((b, "%s..%s" % (a.id or "?", ends[0].id or "?")))
    elif kind == "before":
        for a in find(nodes, rule["anchor"])[:1]:
            ends = find(nodes, rule["until"])[:1]
            if not ends:
                continue
            b = clip(pad_box((a.box[0], a.box[1], ends[0].box[0], a.box[3]), rule.get("pad", 0)), size)
            if b:
                res.append((b, "%s..%s" % (a.id or "?", ends[0].id or "?")))
    else:
        raise MaskError("rule %s: unknown kind '%s'" % (rule.get("id", "?"), kind))
    return res


def check_rules(rules):
    seen = set()
    for r in rules.get("rules", []):
        for k in ("id", "kind", "why", "evidence"):
            if not r.get(k):
                raise MaskError("a rule has no '%s' (every rule says why and on what evidence): %s" % (k, json.dumps(r)[:80]))
        if r["id"] in seen:
            raise MaskError("rule id '%s' is used twice" % r["id"])
        seen.add(r["id"])


def build_state(rules, nodes, size, state):
    """-> (masks [(box, rule id, node)], regions {name: box}); raises MaskError over a limit."""
    regions, protect = make_regions(rules, nodes, size)
    masks, shares = [], {}
    for r in rules.get("rules", []):
        if r.get("enabled", True) is False:
            continue
        if r.get("states") and state not in r["states"]:
            continue
        boxes = rule_boxes(r, nodes, size)
        if not boxes and r.get("required"):
            raise MaskError("rule %s matched nothing in %s (required): the dump is not what the rule was written for" % (r["id"], state))
        for b, nid in boxes:
            if not r.get("text_node"):
                for name in sorted(protect):
                    if intersects(b, regions[name]):
                        raise MaskError("rule %s (node %s, box %s) covers the protected region '%s' %s in %s: only a family-named text node's own bounds may" % (
                            r["id"], nid, to_xywh(b), name, to_xywh(regions[name]), state))
            masks.append((b, r["id"], nid))
    return masks, regions


def share_of(masks, size):
    return union_area([m[0] for m in masks]) / float(size[0] * size[1])


def find_dumps(dump):
    root = os.path.join(dump, "stock") if os.path.isdir(os.path.join(dump, "stock")) else dump
    found = {}
    for theme in sorted(os.listdir(root)):
        d = os.path.join(root, theme)
        if not os.path.isdir(d):
            continue
        for f in sorted(os.listdir(d)):
            if f.endswith(".xml"):
                found.setdefault(f[:-4], {})[theme] = os.path.join(d, f)
    if not found:
        raise MaskError("no <theme>/<state>.xml under %s (capture.sh --side stock --dump)" % dump)
    return found


def generate(rules, dump):
    check_rules(rules)
    limit = rules.get("max_share", 0.06)
    masks_out, regions_out, prov = {}, {}, {"max_share": limit, "states": {}}
    for state, per_theme in sorted(find_dumps(dump).items()):
        all_masks, reg, size = {}, {}, None
        for theme, path in sorted(per_theme.items()):
            nodes, size = load_tree(path)
            ms, rg = build_state(rules, nodes, size, state)
            for b, rid, nid in ms:
                all_masks[(b, rid, nid)] = True
            for name, b in rg.items():
                reg.setdefault(name, set()).add(b)
        merged = sorted(all_masks)
        boxes = [m[0] for m in merged]
        share = share_of(merged, size)
        if share > limit:
            by_rule = {}
            for rid in {m[1] for m in merged}:
                by_rule[rid] = share_of([m for m in merged if m[1] == rid], size)
            worst = max(by_rule, key=lambda k: (by_rule[k], k))
            raise MaskError("%s: the masks cover %.2f %% of the picture, over the %.0f %% limit; the largest is rule %s (%.2f %%): stop and report" % (
                state, share * 100, limit * 100, worst, by_rule[worst] * 100))
        key = state + ".png"
        masks_out[key] = [to_xywh(b) for b in sorted(set(boxes))]
        # the same node in light and dark must be the same place; if the themes disagree the union is kept (and the larger box is the mask)
        regions_out[key] = {name: to_xywh((min(b[0] for b in bs), min(b[1] for b in bs), max(b[2] for b in bs), max(b[3] for b in bs)))
                            for name, bs in sorted(reg.items())}
        by_rule_boxes = {}
        for b, rid, nid in merged:
            by_rule_boxes.setdefault(rid, []).append({"box": to_xywh(b), "node": nid})
        prov["states"][key] = {"size": list(size), "mask_boxes": len(set(boxes)), "mask_share": round(share, 5),
                               "rules": {rid: v for rid, v in sorted(by_rule_boxes.items())}}
    why = {r["id"]: {"why": r["why"], "evidence": r["evidence"], "enabled": r.get("enabled", True) is not False} for r in rules.get("rules", [])}
    prov["rules"] = dict(sorted(why.items()))
    return masks_out, regions_out, prov


def dump_json(obj):
    text = json.dumps(obj, indent=1, sort_keys=True)
    # a box or a size on one line: [x, y, w, h]
    text = re.sub(r"\[\s+(-?\d+),\s+(-?\d+),\s+(-?\d+),\s+(-?\d+)\s+\]", r"[\1, \2, \3, \4]", text)
    text = re.sub(r"\[\s+(-?\d+),\s+(-?\d+)\s+\]", r"[\1, \2]", text)
    return text + "\n"


def pick(path, name):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    if name not in data and "*" not in data:
        raise MaskError("no entry '%s' in %s" % (name, path))
    v = data.get(name)
    if isinstance(v, dict):
        return v
    return list(data.get("*", [])) + list(v or [])


def main(argv=None):
    p = argparse.ArgumentParser(description="Mask and region generator (see the file's docstring).")
    sub = p.add_subparsers(dest="cmd", required=True)
    g = sub.add_parser("generate")
    g.add_argument("--rules", required=True)
    g.add_argument("--dump", required=True)
    g.add_argument("--out-masks", required=True)
    g.add_argument("--out-regions", required=True)
    g.add_argument("--out-provenance")
    k = sub.add_parser("pick")
    k.add_argument("file")
    k.add_argument("name")
    try:
        opt = p.parse_args(argv)
    except SystemExit as e:
        return 2 if e.code else 0
    try:
        if opt.cmd == "pick":
            try:
                sys.stdout.write(json.dumps(pick(opt.file, opt.name)) + "\n")
            except MaskError as e:
                print("maskgen: %s" % e, file=sys.stderr)
                return 2
            return 0
        with open(opt.rules, encoding="utf-8") as f:
            rules = json.load(f)
        masks, regions, prov = generate(rules, opt.dump)
    except (OSError, ValueError) as e:
        print("maskgen: %s" % e, file=sys.stderr)
        return 2
    except MaskError as e:
        print("maskgen FAILED: %s" % e, file=sys.stderr)
        return 1
    for path, obj in ((opt.out_masks, masks), (opt.out_regions, regions), (opt.out_provenance, prov)):
        if path:
            os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(dump_json(obj))
    for key in sorted(prov["states"]):
        s = prov["states"][key]
        print("%s: %d mask boxes, %.2f %% of the picture; %d regions" % (key, s["mask_boxes"], s["mask_share"] * 100, len(regions[key])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
