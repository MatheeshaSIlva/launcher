#!/usr/bin/env python3
"""T-007: the whole bar's pixel check, region by region, where a whole-band mean can hide a small thing.

usage: bar_regions.py DIR    (DIR holds view/ and kdl/, as TEST_STATUS --es do bar writes them)

Cuts each reference picture into runs of columns that hold something, and for each run reports how far the KDL bar's
bounding box is from the reference's and how much the pixels differ. It judges nothing: which regions must be exact
(text and solid shapes, D-074) and which may differ (D-083's wifi.3) is the reader's, from the printed table.
"""
import os
import struct
import sys
import zlib


def read_png(path):
    data = open(path, 'rb').read()
    pos, idat, w, h, ct = 8, b'', 0, 0, 0
    while pos < len(data):
        n, kind = struct.unpack('>I4s', data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + n]
        if kind == b'IHDR':
            w, h, depth, ct = struct.unpack('>IIBB', body[:10])
            assert depth == 8 and ct in (2, 6), path
        elif kind == b'IDAT':
            idat += body
        pos += 12 + n
    channels = 3 if ct == 2 else 4
    raw, rows, stride, prev, i = zlib.decompress(idat), [], w * channels, bytearray(w * channels), 0
    for _ in range(h):
        f = raw[i]
        line = bytearray(raw[i + 1:i + 1 + stride])
        i += 1 + stride
        for x in range(stride):
            a = line[x - channels] if x >= channels else 0
            b = prev[x]
            c = prev[x - channels] if x >= channels else 0
            if f == 1:
                line[x] = (line[x] + a) & 255
            elif f == 2:
                line[x] = (line[x] + b) & 255
            elif f == 3:
                line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(line)
        prev = line
    return w, h, channels, rows


def grey(img, x, y):
    w, h, ch, rows = img
    r = rows[y]
    return (r[x * ch] + r[x * ch + 1] + r[x * ch + 2]) // 3


def runs(img, bg, gap=6):
    """Column runs holding something; columns closer than [gap] join."""
    w, h, _, _ = img
    on = [any(abs(grey(img, x, y) - bg) > 24 for y in range(h)) for x in range(w)]
    out, x = [], 0
    while x < w:
        if not on[x]:
            x += 1
            continue
        s = x
        last = x
        while x < w and (on[x] or x - last <= gap):
            if on[x]:
                last = x
            x += 1
        out.append((s, last))
    return out


def box(img, bg, x0, x1):
    w, h, _, _ = img
    pts = [(x, y) for x in range(max(0, x0), min(w, x1 + 1)) for y in range(h) if abs(grey(img, x, y) - bg) > 24]
    if not pts:
        return None
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    return min(xs), max(xs), min(ys), max(ys)


def main():
    root = sys.argv[1]
    worst_shift = 0
    for name in sorted(os.listdir(os.path.join(root, 'view'))):
        a = read_png(os.path.join(root, 'view', name))
        b = read_png(os.path.join(root, 'kdl', name))
        bg = grey(a, 0, 0)
        print(name)
        for s, e in runs(a, bg):
            pa, pb = box(a, bg, s, e), box(b, bg, s - 8, e + 8)
            if pb is None:
                print(f'  x {s:4}-{e:4}  MISSING in kdl')
                continue
            shift = tuple(pb[i] - pa[i] for i in range(4))
            worst_shift = max(worst_shift, max(abs(v) for v in shift))
            diffs = [abs(grey(a, x, y) - grey(b, x, y)) for x in range(s, e + 1) for y in range(a[1])]
            over = sum(1 for d in diffs if d > 24)
            print(f'  x {s:4}-{e:4}  box shift {shift}  max {max(diffs):3}  mean {sum(diffs) / len(diffs):5.2f}  over-24 {over}')
    print('largest box shift, px:', worst_shift)


if __name__ == '__main__':
    main()
