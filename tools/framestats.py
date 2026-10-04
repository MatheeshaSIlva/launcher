"""GPU and CPU time per frame of one of our windows, from `dumpsys gfxinfo dev.launcher.app framestats`.

    python tools/framestats.py FILE [WINDOW]      WINDOW: LauncherCards (default), HomeActivity, ...

GPU = GpuCompleted - IssueDrawCommandsStart (render thread hands the frame to the GPU until the GPU is done); CPU = from
the intended vsync until the render thread issued the frame. At 120 Hz a frame has 8.3 ms; a GPU time near that drops frames.
"""
import sys


def frames(path, window):
    lines = open(path, encoding='utf-8', errors='replace').read().splitlines()
    out, inside, header = [], False, None
    for i, line in enumerate(lines):
        if line.startswith('Window: '):
            inside = window in line
            header = None
            continue
        if not inside:
            continue
        if line.startswith('---PROFILEDATA---'):
            if header is not None:
                inside = False   # end of this window's block
            continue
        if line.startswith('Flags,'):
            header = line.rstrip(',').split(',')
            continue
        if header and line and line[0].isdigit():
            row = dict(zip(header, (int(v) for v in line.rstrip(',').split(',') if v != '')))
            if row.get('Flags', 1) == 0:
                out.append(row)
    return out


def main():
    path = sys.argv[1]
    window = sys.argv[2] if len(sys.argv) > 2 else 'LauncherCards'
    fs = frames(path, window)
    if not fs:
        print(f'no frames for {window}')
        return
    gpu = sorted((f['GpuCompleted'] - f['IssueDrawCommandsStart']) / 1e6 for f in fs if f.get('GpuCompleted', 0) > 0)
    cpu = sorted((f['IssueDrawCommandsStart'] - f['IntendedVsync']) / 1e6 for f in fs)

    def p(v, q):
        return v[min(len(v) - 1, int(len(v) * q))]
    print(f'{window}: {len(fs)} frames')
    if gpu:
        print(f'  GPU ms: median {p(gpu, .5):.2f}  p90 {p(gpu, .9):.2f}  max {gpu[-1]:.2f}  over 8.3 ms: {sum(1 for g in gpu if g > 8.3)}')
    print(f'  CPU ms: median {p(cpu, .5):.2f}  p90 {p(cpu, .9):.2f}  max {cpu[-1]:.2f}')
    # What the screen showed: intervals between consecutive frames' present times. Our own frame log times the drawing
    # thread, so a frame the GPU finished late (shown a refresh later) only appears here.
    ok = sorted((f for f in fs if f.get('DisplayPresentTime', 0) > 0), key=lambda f: f['DisplayPresentTime'])
    if len(ok) > 2:
        gaps = [(b['DisplayPresentTime'] - a['DisplayPresentTime']) / 1e6 for a, b in zip(ok, ok[1:])]
        period = sorted(gaps)[len(gaps) // 4]   # the display's refresh period (most gaps are one period)
        late = [(i + 1, g) for i, g in enumerate(gaps) if period * 1.5 < g < 200]
        print(f'  shown: {len(ok)} frames, refresh {period:.2f} ms, late {len(late)} (missed refreshes ~{sum(round(g / period) - 1 for _, g in late)})')
        t0 = ok[0]['IntendedVsync']
        for i, g in late[:6]:
            f = ok[i]
            # Where the late frame lost its time: from its vsync to the render thread issuing it, then the GPU.
            print(f'    frame #{i} at {(f["IntendedVsync"] - t0) / 1e6:.0f} ms shown {g:.1f} ms after the previous: '
                  f'start->issue {(f["IssueDrawCommandsStart"] - f["IntendedVsync"]) / 1e6:.1f} ms '
                  f'(ui {(f["SyncStart"] - f["IntendedVsync"]) / 1e6:.1f}), GPU {(f["GpuCompleted"] - f["IssueDrawCommandsStart"]) / 1e6:.1f} ms, '
                  f'dequeue {f.get("DequeueBufferDuration", 0) / 1e6:.1f} ms, vsync late by {(f["Vsync"] - f["IntendedVsync"]) / 1e6:.1f} ms')
    slow = [(i, (f['GpuCompleted'] - f['IssueDrawCommandsStart']) / 1e6) for i, f in enumerate(fs) if f.get('GpuCompleted', 0) > 0]
    slow = [(i, g) for i, g in slow if g > 8.3]
    if slow:
        print('  GPU > 8.3 ms at frames: ' + ', '.join(f'#{i} {g:.1f}' for i, g in slow[:10]))


if __name__ == '__main__':
    main()
