#!/usr/bin/env python3
"""
A scenario's gesture marks on the video's own timeline. The test prints `REFMARK <unix time> <what>` (simulator clock =
the runner's clock); the recorder's first output line is stamped with the wall time it appeared ("Recording started"),
which is where the video's time 0 is (to within a frame or two; check against the first visible motion).

    python tools/ios_ref/marks.py SCENARIO_DIR [--offset S]

Prints "video_time  what" per mark.
"""
import argparse
import os
import re
import sys


def rec_start(d):
    p = os.path.join(d, "rec.log")
    if not os.path.exists(p):
        return None
    for line in open(p, encoding="utf-8", errors="replace"):
        m = re.match(r"([0-9.]+)\s+(.*)", line)
        if m and ("record" in m.group(2).lower() or "start" in m.group(2).lower()):
            return float(m.group(1))
    for line in open(p, encoding="utf-8", errors="replace"):
        m = re.match(r"([0-9.]+)\s+", line)
        if m:
            return float(m.group(1))
    return None


def marks(d):
    src = os.path.join(d, "marks.txt")
    if not os.path.exists(src):
        src = os.path.join(d, "test.log")
    out = []
    seen = set()
    for line in open(src, encoding="utf-8", errors="replace"):
        m = re.search(r"REFMARK ([0-9.]+) (.*)", line)
        if m and line not in seen:
            seen.add(line)
            out.append((float(m.group(1)), m.group(2).strip()))
    out.sort()
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dir")
    ap.add_argument("--offset", type=float, default=0.0, help="added to every video time (a measured correction)")
    a = ap.parse_args()
    t0 = rec_start(a.dir)
    ms = marks(a.dir)
    if t0 is None:
        print("# no recorder start time; showing times from the first mark")
        t0 = ms[0][0] if ms else 0.0
    for t, what in ms:
        print("%8.3f  %s" % (t - t0 + a.offset, what))


if __name__ == "__main__":
    sys.exit(main())
