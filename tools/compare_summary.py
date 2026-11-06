"""The summary of a tools/compare_s24.sh run: missed refreshes and GPU/CPU p90 per animation, per run, from framestats.

    python tools/compare_summary.py RUN_DIR "ref new ref new" "switch shade settings" PASSES

Every animation the chosen scenarios measure is expected in every pass of every run (EXPECTED below, kept in step with
the scenario scripts' `measure` calls). Anything missing, unreadable or not expected is listed and the exit is 3, so a
gap never vanishes into a total (Codex's cross-check of T-002: expectations once came from the files found, and an empty
run printed "complete"). Exit 0 only when every expected measurement is there and nothing else is. FRAMESTATS names the
parser (default tools/framestats.py; the test hands it a stub).
"""
import os, re, subprocess, sys

# Each scenario's animations, as its script names them (fs_<step>.txt), and whether it runs once or PASSES times.
EXPECTED = {
    "switch": (["switch_fwd_1", "switch_fwd_2", "switch_back_1", "switch_back_2"], False),
    "shade": (["s_banner", "s_cc_open", "s_cc_close_tap", "s_cc_edit_in", "s_cc_edit_out", "s_cc_close_bar",
               "s_nc_open", "s_nc_close_bar"], True),
    "settings": (["set_open", "set_page_open", "set_page_close", "set_scroll", "set_page2_open", "set_page2_close",
                  "set_close"], False),
}
FRAMESTATS = os.environ.get("FRAMESTATS", "tools/framestats.py")


def window(name):
    return "LauncherCards" if name.startswith("switch") else "SettingsActivity" if name.startswith("set_") else "LauncherStatusBar"


def stats(path, w):
    t = subprocess.run([sys.executable, FRAMESTATS, path, w], capture_output=True, text=True).stdout
    n = re.search(r"shown: (\d+) frames.*?missed refreshes ~(\d+)", t, re.S)
    g = re.search(r"GPU ms: median ([\d.]+)\s+p90 ([\d.]+)", t)
    c = re.search(r"CPU ms: median ([\d.]+)\s+p90 ([\d.]+)", t)
    if not n or not g or not c:
        return None
    return (int(n.group(2)), float(g.group(2)), float(c.group(2)))


def main(run, order, scenarios, passes):
    labels = [f"{i + 1}-{b}" for i, b in enumerate(order)]
    gaps = []
    if not labels:
        gaps.append("no runs in the order")
    unknown = [s for s in scenarios if s not in EXPECTED]
    if unknown:
        gaps.append(f"scenarios with no expectations: {' '.join(unknown)}")
    if not [s for s in scenarios if s in EXPECTED]:
        gaps.append("no scenario to measure")
    want = {}   # step -> pass count expected in every run
    for sc in scenarios:
        if sc in EXPECTED:
            names, repeated = EXPECTED[sc]
            for s in names:
                want[s] = passes if repeated else 1
    steps = {s: {} for s in want}
    for lab in labels:
        d = os.path.join(run, lab)
        files = sorted(f for f in os.listdir(d) if f.startswith("fs_")) if os.path.isdir(d) else []
        if not os.path.isdir(d):
            gaps.append(f"{lab}: no folder")
        found = set()
        for f in files:
            m = re.fullmatch(r"fs_(.+)@(\d+)\.txt", f)
            if not m or m.group(1) not in want or not 1 <= int(m.group(2)) <= want[m.group(1)]:
                gaps.append(f"{lab}: {f} is not an expected measurement")
                continue
            step = m.group(1)
            found.add((step, int(m.group(2))))
            s = stats(os.path.join(d, f), window(step))
            if s:
                steps[step].setdefault(lab, []).append(s)
            else:
                gaps.append(f"{lab}: {f} (no frames, or no GPU/CPU values, for {window(step)})")
        for step, n in want.items():
            for p in range(1, n + 1):
                if (step, p) not in found:
                    gaps.append(f"{lab}: {step} pass {p} missing")

    print("missed refreshes (summed over passes) / worst GPU p90 ms / worst CPU p90 ms per animation, from framestats")
    print("runs: " + ", ".join(labels))
    print(f"{'animation':24s}" + "".join(f"{l:>20s}" for l in labels) + f"{'ref miss/pass':>15s}{'new miss/pass':>15s}")

    def cell(v):
        return "-" if not v else f"{sum(x[0] for x in v)} / {max(x[1] for x in v):.1f} / {max(x[2] for x in v):.1f}"

    def mean(row, b):
        v = [x[0] for l in labels if l.endswith(b) for x in row.get(l, [])]
        return f"{sum(v) / len(v):.2f}" if v else "-"

    tot = {"ref": [0, 0], "new": [0, 0]}
    for step in sorted(steps):
        row = steps[step]
        for l in labels:
            b = l.split("-")[1]
            tot.setdefault(b, [0, 0])
            tot[b][0] += sum(x[0] for x in row.get(l, []))
            tot[b][1] += len(row.get(l, []))
        print(f"{step:24s}" + "".join(f"{cell(row.get(l)):>20s}" for l in labels)
              + f"{mean(row, 'ref'):>15s}{mean(row, 'new'):>15s}")
    for b in ("ref", "new"):
        print(f"total {b}: {tot[b][0]} missed over {tot[b][1]} measured animations")
    if gaps:
        print("INCOMPLETE: these measurements are missing or unexpected, so the totals above do not compare like with like:")
        for g in sorted(set(gaps)):
            print("  " + g)
        return 3
    print(f"complete: all {sum(want.values())} expected measurements in each of the {len(labels)} runs")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2].split(), sys.argv[3].split(), int(sys.argv[4])))
