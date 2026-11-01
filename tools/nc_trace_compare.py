"""Compares two Notification Center traces (tools/nc_trace.sh) value by value (docs/PLAN_PANEL_TRANSITIONS.md, step 2b).

    python tools/nc_trace_compare.py BEFORE.csv AFTER.csv [--tol 0.01]

Both runs saw the same events (pull, commit, landed, close...), at different moments: each value is compared as a function
of the time since the event before it. The traces run on a fixed step a frame (NcTrace), so frames are matched by that time,
never interpolated: a curve that is the same in both reads 0. Prints the largest difference per value and segment, and exits 1 when
any is over the tolerance. Events that only one run has are listed (a landing marked a frame apart is matched by name).
"""
import csv
import sys


def load(path):
    frames, events = [], []
    with open(path, newline="") as f:
        r = csv.reader(f)
        head = next(r)
        cols = head[2:]
        for row in r:
            t = float(row[0])
            if row[1] == "frame":
                frames.append((t, [float(x) for x in row[2:]]))
            else:
                events.append((t, row[1]))
    return cols, frames, events


def at(frames, t):
    """Each value at time t, interpolated between the frames around it (None outside the frames)."""
    if not frames or t < frames[0][0] or t > frames[-1][0]:
        return None
    lo, hi = 0, len(frames) - 1
    while hi - lo > 1:
        mid = (lo + hi) // 2
        if frames[mid][0] <= t:
            lo = mid
        else:
            hi = mid
    (t0, a), (t1, b) = frames[lo], frames[hi]
    k = 0.0 if t1 == t0 else (t - t0) / (t1 - t0)
    return [x + (y - x) * k for x, y in zip(a, b)]


def segments(events, end):
    """(name, start, end) for each event up to the next one (the last to the trace's end)."""
    out = []
    for i, (t, n) in enumerate(events):
        out.append((n, t, events[i + 1][0] if i + 1 < len(events) else end))
    return out


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    tol = 0.01
    if "--tol" in sys.argv:
        tol = float(sys.argv[sys.argv.index("--tol") + 1])
    cols, fa, ea = load(args[0])
    _, fb, eb = load(args[1])
    names_a = [n for _, n in ea]
    names_b = [n for _, n in eb]
    if names_a != names_b:
        print(f"events differ:\n  before {names_a}\n  after  {names_b}")
    sa = segments(ea, fa[-1][0] if fa else 0)
    sb = segments(eb, fb[-1][0] if fb else 0)
    worst_all = 0.0
    for (na, a0, a1), (nb, b0, b1) in zip(sa, sb):
        if na != nb:
            print(f"segment {na} vs {nb}: not compared")
            continue
        span = min(a1 - a0, b1 - b0)
        worst = [0.0] * len(cols)
        when = [0.0] * len(cols)
        n = 0
        # The traces run on a fixed step (NcTrace, MotionClock): both runs' frames lie on the same grid from each event, so
        # frames are matched by their time since it (within 2 ms), never interpolated (a jump between two frames is no error).
        fb_seg = {round((t - b0) / 2.0): v for t, v in fb if b0 <= t <= b1}
        for t, va in fa:
            # (The frame at the event's own time was drawn before it was handled: it shows what came before.)
            # (Nor the frame of the next event: it shows that event's first effects.)
            if not (a0 + 1.0 <= t < a0 + span - 1.0):
                continue
            vb = fb_seg.get(round((t - a0) / 2.0))
            if vb is None:
                continue
            n += 1
            for i, (x, y) in enumerate(zip(va, vb)):
                if abs(x - y) > worst[i]:
                    worst[i], when[i] = abs(x - y), t - a0
        bad = [(cols[i], worst[i], when[i]) for i in range(len(cols)) if worst[i] > tol]
        worst_all = max([worst_all] + worst)
        line = f"{na:>9} ({span:6.0f} ms, {n} samples): "
        line += "same" if not bad else ", ".join(f"{c} {w:.4f} at +{t:.0f} ms" for c, w, t in bad)
        print(line)
    print(f"largest difference {worst_all:.4f} (tolerance {tol})")
    sys.exit(1 if worst_all > tol else 0)


if __name__ == "__main__":
    main()
