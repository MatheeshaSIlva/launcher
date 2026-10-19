"""Builds the test run's report page (docs/report/test-run.html) from the sweep CSVs.

    python tools/report/make_report.py BEFORE.csv AFTER.csv [--after-run NAME ...]

Rows: run,step,window,shown,missed,gpu_p90,gpu_max,cpu_max (tools/scenario_sweep.sh). A run named *-cold was taken right
after an install (nothing warmed), *-warm after that. The page compares the before runs with the after runs, step by step
(missed refreshes summed over our windows, the slowest frame's CPU time).
"""
import csv
import json
import sys
from collections import defaultdict
from pathlib import Path

FIELDS = ['run', 'step', 'window', 'shown', 'missed', 'gpu_p90', 'gpu_max', 'cpu_max']

SECTIONS = [
    ('Home', ['page_to_library', 'library_scroll', 'library_to_page_swipe', 'library_to_page_bar', 'folder_open',
              'folder_close_bar', 'library_search_open', 'library_search_close', 'spotlight_open', 'spotlight_close',
              'home_pull', 'edit_enter', 'edit_exit']),
    ('Apps', ['calc_launch', 'calc_close', 'clock_launch_folder', 'clock_close_folder', 'switch_sideways', 'switch_back',
              'close_cancel', 'calc_close_2', 'launch_grabbed']),
    ('App Switcher', ['switcher_open', 'switcher_scroll', 'switcher_home']),
    ('Shade', ['cc_open', 'cc_close', 'nc_open', 'nc_close']),
    ('Settings', ['settings_launch', 'settings_close_gesture', 'edit_menu_open', 'settings_from_home', 'settings_back_to_home']),
]

NAMES = {
    'page_to_library': 'Page to App Library', 'library_scroll': 'App Library scroll', 'library_to_page_swipe': 'Library back to page (swipe)',
    'library_to_page_bar': 'Library back to page (bar)', 'folder_open': 'Library folder opens', 'folder_close_bar': 'Library folder closes',
    'library_search_open': 'Library search opens', 'library_search_close': 'Library search closes', 'spotlight_open': 'Spotlight opens',
    'spotlight_close': 'Spotlight closes', 'home_pull': 'Swipe up on home', 'edit_enter': 'Edit mode in', 'edit_exit': 'Edit mode out',
    'calc_launch': 'App opens (library)', 'calc_close': 'App closes', 'clock_launch_folder': 'App opens (folder)',
    'clock_close_folder': 'App closes (folder)', 'switch_sideways': 'Quick switch', 'switch_back': 'Quick switch back',
    'close_cancel': 'Close cancelled', 'calc_close_2': 'App closes (again)', 'launch_grabbed': 'Opening app grabbed',
    'switcher_open': 'App Switcher opens', 'switcher_scroll': 'App Switcher scroll', 'switcher_home': 'App Switcher to home',
    'cc_open': 'Control Center opens', 'cc_close': 'Control Center closes', 'nc_open': 'Notification Center opens',
    'nc_close': 'Notification Center closes', 'settings_launch': 'Settings open', 'settings_close_gesture': 'Settings close (gesture)',
    'edit_menu_open': "Home's edit menu opens", 'settings_from_home': 'Home Settings slides in', 'settings_back_to_home': 'Settings slide back home',
}


def load(path):
    rows = [r for r in csv.DictReader(open(path), fieldnames=FIELDS) if r['run'] != 'run']
    out = defaultdict(lambda: defaultdict(lambda: {'missed': 0, 'shown': 0, 'cpu_max': 0.0}))
    for r in rows:
        kind = 'cold' if r['run'].endswith('cold') else 'warm'
        cell = out[(r['run'], kind)][r['step']]
        cell['missed'] += int(r['missed'])
        cell['shown'] += int(r['shown'])
        cell['cpu_max'] = max(cell['cpu_max'], float(r['cpu_max']))
    return out


def pick(runs, kind, wanted=None):
    for (name, k), steps in runs.items():
        if k == kind and (wanted is None or name in wanted):
            return steps
    return {}


def main():
    before_path, after_path = sys.argv[1], sys.argv[2]
    wanted = None
    if '--after-run' in sys.argv:
        wanted = set()
        for a in sys.argv[sys.argv.index('--after-run') + 1:]:
            if a.startswith('--'): break
            wanted.add(a)
    before = load(before_path)
    after = load(after_path)
    data = {'sections': []}
    for title, steps in SECTIONS:
        sec = {'title': title, 'steps': []}
        for s in steps:
            row = {'id': s, 'name': NAMES.get(s, s)}
            for kind in ('cold', 'warm'):
                b = pick(before, kind).get(s)
                a = pick(after, kind, wanted).get(s)
                row[kind] = {
                    'before': None if b is None else {'missed': b['missed'], 'cpu': round(b['cpu_max'], 1), 'shown': b['shown']},
                    'after': None if a is None else {'missed': a['missed'], 'cpu': round(a['cpu_max'], 1), 'shown': a['shown']},
                }
            sec['steps'].append(row)
        data['sections'].append(sec)
    here = Path(__file__).parent
    page = (here / 'report_template.html').read_text(encoding='utf-8')
    page = page.replace('/*DATA*/null', json.dumps(data))
    meta = {}
    if '--meta' in sys.argv:
        meta = json.loads(Path(sys.argv[sys.argv.index('--meta') + 1]).read_text(encoding='utf-8'))
    page = page.replace('/*META*/{}', json.dumps(meta))
    page = page.replace('/*P29*/', meta.get('p29', '<b>Fixed.</b> The controls are recorded ahead while the shade is idle.'))
    out = Path('docs/report/test-run.html')
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(page, encoding='utf-8')
    print(out)


if __name__ == '__main__':
    main()
