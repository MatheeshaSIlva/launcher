#!/usr/bin/env python3
"""
Takes the given sys.* / comp.* tokens of the iOS 27 theme back to the seed values in build_ios27_theme.py (after a
seed was corrected), then rebuilds the theme.

    python tools/design/reseed.py comp.nc.button.symbol [more keys...]
"""
import json
import os
import subprocess
import sys

ROOT = os.path.join(os.path.dirname(__file__), '..', '..')
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'themes', 'ios27.json')

theme = json.load(open(OUT, encoding='utf-8'))
for k in sys.argv[1:]:
    if theme['tokens'].pop(k, None) is None:
        print(f'{k}: not in the theme (added from the seed if it has one)')
with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
    json.dump(theme, f, indent=1, ensure_ascii=False)
    f.write('\n')
subprocess.run([sys.executable, os.path.join(os.path.dirname(__file__), 'build_ios27_theme.py')], check=True)
