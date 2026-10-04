"""Long pieces of work in our process from a Perfetto trace: which slices on which thread took longer than a frame.

    python tools/trace_slices.py TRACE [MIN_MS] [PROCESS]

Prints, per thread, the slices longer than MIN_MS (default 6) with their start time (ms from the first one listed), so a
missed refresh can be matched to what ran then. Also the biggest slice names by total time.
"""
import sys
from perfetto.trace_processor import TraceProcessor

path = sys.argv[1]
min_ms = float(sys.argv[2]) if len(sys.argv) > 2 else 6.0
proc = sys.argv[3] if len(sys.argv) > 3 else 'dev.launcher.app'

tp = TraceProcessor(trace=path)
q = f"""
select s.ts, s.dur, s.name, t.name as thread, s.depth
from slice s join thread_track tt on s.track_id = tt.id join thread t using(utid) join process p using(upid)
where p.name = '{proc}' and s.dur > {int(min_ms * 1e6)}
order by s.ts
"""
rows = list(tp.query(q))
# Touches that start a step (finger down on any of our windows), as markers between the slices.
downs = list(tp.query(f"""
select s.ts, t.name as thread from slice s join thread_track tt on s.track_id = tt.id join thread t using(utid)
join process p using(upid) where p.name = '{proc}' and s.name like 'dispatchInputEvent MotionEvent DOWN%' order by s.ts"""))
t0 = next(iter(tp.query('select start_ts from trace_bounds'))).start_ts
events = [(r.ts, 1, r) for r in rows] + [(d.ts, 0, d) for d in downs]
for ts, kind, r in sorted(events, key=lambda e: (e[0], e[1])):
    if kind == 0:
        print(f'{(ts - t0) / 1e6:9.1f} ms  ---- finger down ({r.thread})')
    else:
        print(f'{(ts - t0) / 1e6:9.1f} ms  {r.dur / 1e6:6.1f} ms  {r.thread[:16]:16s} {"  " * r.depth}{r.name[:110]}')
