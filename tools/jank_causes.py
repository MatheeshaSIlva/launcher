"""Every missed frame of our process in a Perfetto trace, with what our threads were doing meanwhile.

    python tools/jank_causes.py TRACE [MIN_SLICE_MS]

For each frame the frame timeline calls late (App Deadline Missed, a dropped frame, a late present) it prints the frame
(window, time, jank type) and, on the main thread, gesture nav's thread (nav-ui), the render thread and the GC thread, the
longest slices that overlap the frame's vsync-to-present span (at least MIN_SLICE_MS, default 2), deepest name first. The
touch markers (finger down) are listed as well, so a frame can be placed in its gesture. Built for the test run
(docs/TEST_RUN.md): run a scenario under tools/trace.sh, then this.
"""
import sys

from perfetto.trace_processor import TraceProcessor

THREADS = ('ev.launcher.app', 'nav-ui', 'RenderThread', 'HeapTaskDaemon', 'binder')


def main():
    trace = sys.argv[1]
    min_ms = float(sys.argv[2]) if len(sys.argv) > 2 else 2.0
    tp = TraceProcessor(trace=trace)
    t0 = next(iter(tp.query('select start_ts from trace_bounds'))).start_ts
    pid_rows = list(tp.query("select upid, pid from process where name like 'dev.launcher%'"))
    if not pid_rows:
        print('our process is not in the trace')
        return
    upids = ','.join(str(r.upid) for r in pid_rows)
    frames = list(tp.query(f"""
        select a.ts, a.dur, a.jank_type, a.present_type, a.layer_name, a.display_frame_token
        from actual_frame_timeline_slice a
        where a.upid in ({upids}) and (a.jank_type like '%App Deadline%' or a.jank_type like '%Dropped%'
              or a.present_type = 'Late Present' and a.jank_type not like '%Display HAL%')
        order by a.ts"""))
    touches = list(tp.query(f"""
        select s.ts from slice s join thread_track tt on s.track_id = tt.id join thread t using(utid)
        where t.upid in ({upids}) and s.name like 'deliverInputEvent%' and s.name like '%' order by s.ts"""))
    downs = [r.ts for r in tp.query(f"""
        select s.ts from slice s join thread_track tt on s.track_id = tt.id join thread t using(utid)
        where t.upid in ({upids}) and s.name like 'dispatchInputEvent MotionEvent DOWN%' order by s.ts""")]
    print(f'{len(frames)} late frames; touches down at: ' + ', '.join(f'{(d - t0) / 1e6:.0f}' for d in downs))
    for f in frames:
        start = f.ts
        end = f.ts + f.dur
        layer = (f.layer_name or '').replace('TX - VRI-', '').split('$')[0].split('/')[-1][:28]
        since = [d for d in downs if d <= start]
        rel = f' {(start - since[-1]) / 1e6:.0f} ms after a touch down' if since else ''
        print(f'\n{(start - t0) / 1e6:9.1f} ms  {f.dur / 1e6:5.1f} ms  {layer:28s} {f.jank_type} / {f.present_type}{rel}')
        rows = tp.query(f"""
            select s.ts, s.dur, s.depth, s.name, t.name as thread from slice s
            join thread_track tt on s.track_id = tt.id join thread t using(utid)
            where t.upid in ({upids}) and s.ts < {end} and s.ts + s.dur > {start - 20_000_000}
              and s.dur >= {int(min_ms * 1e6)}
            order by t.name, s.ts, s.depth""")
        by = {}
        for r in rows:
            name = r.thread or ''
            if not any(name.startswith(k) for k in THREADS):
                continue
            by.setdefault(name, []).append(r)
        for thread, rs in sorted(by.items()):
            # The deepest long slice per top-level one says the most (e.g. Recomposer > measure > a text).
            shown = 0
            for r in rs:
                if shown >= 6:
                    break
                print(f'      {thread[:15]:15s} {(r.ts - t0) / 1e6:9.1f} {r.dur / 1e6:6.1f} ms  {"  " * min(r.depth, 6)}{r.name[:110]}')
                shown += 1


if __name__ == '__main__':
    main()
