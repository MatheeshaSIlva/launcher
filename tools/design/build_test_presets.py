#!/usr/bin/env python3
"""
Builds the test animation preset "Eased (test)" (app/src/main/assets/motion/eased.json) from the iOS 27 preset: every role
that is a spring there becomes a bezier curve, so the whole launcher moves on beziers. It exists to prove that a preset
retimes everything from tokens alone and that bezier curves can be grabbed and interrupted like springs (B3c,
docs/PLAN_LAYOUTS_THEMES.md). Only what differs from iOS 27 is written; run again after the iOS 27 preset changes.

    python tools/design/build_test_presets.py
"""
import json
import os

ROOT = os.path.join(os.path.dirname(__file__), '..', '..')
BASE = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'motion', 'ios27.json')
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'motion', 'eased.json')

# Material's emphasized decelerate for motion that settles without overshoot; a gentle back-out for the springy ones.
SETTLE = [0.05, 0.7, 0.1, 1]
BOUNCE = [0.3, 1.25, 0.55, 1]


def main():
    base = json.load(open(BASE, encoding='utf-8'))['tokens']
    out = {}
    for k, v in base.items():
        if 'spring' not in v:
            continue
        response, damping = v['spring']
        springy = damping < 0.9
        # About as long as the spring takes to look settled (a springy one rings a little longer).
        ms = round(response * 1000 * (1.5 if springy else 1.25) / 10) * 10
        out[k] = {'bezier': BOUNCE if springy else SETTLE, 'ms': ms,
                  'src': f"judged:test preset: iOS 27's spring ({response} s, damping {damping}) as a bezier "
                         f"({'back-out' if springy else 'emphasized decelerate'}, {ms} ms)"}
    preset = {'format': 1, 'name': 'Eased (test)', 'author': 'Launcher', 'version': '0.1', 'extends': 'ios27',
              '_about': 'A test animation preset built on iOS 27: every spring role as a bezier curve (Material\'s emphasized '
                        'decelerate, or a gentle back-out where iOS 27 overshoots). Written by tools/design/build_test_presets.py.',
              'tokens': dict(sorted(out.items()))}
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(preset, f, indent=1, ensure_ascii=False)
        f.write('\n')
    print(f'{OUT}: {len(out)} roles as beziers')


if __name__ == '__main__':
    main()
