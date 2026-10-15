#!/usr/bin/env python3
"""
Writes the animation presets that bundle transition styles (docs/MOTION_STYLES.md, docs/PLAN_SETTINGS_V2.md V4):
Playful, Calm and Cinematic, each built on iOS 27 with its own way of opening and closing apps and a few curves of its own.

    python tools/design/build_style_presets.py
"""
import json
import os

ROOT = os.path.join(os.path.dirname(__file__), '..', '..')
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'motion')

PRESETS = {
    'playful': {
        'name': 'Playful', 'description': 'Apps pour out of their icons',
        'about': 'Genie openings and closings, with livelier springs where iOS 27 bounces.',
        'tokens': {
            'motion.style.opening': {'choice': 'genie', 'src': "judged:Playful: the app pours out of its icon (macOS's genie)"},
            'motion.style.closing': {'choice': 'genie', 'src': "judged:Playful: the app is poured back into its icon"},
            'motion.app.open': {'spring': [0.5, 0.86], 'src': "judged:Playful: a little longer and springier than iOS 27's, for the pour to read"},
            'motion.app.close-size': {'spring': [0.46, 0.88], 'src': "judged:Playful: the pour back, a touch slower than the zoom"},
            'motion.app.close-position': {'spring': [0.46, 0.88], 'src': "judged:Playful: with the size"},
            'motion.banner.in': {'spring': [0.5, 0.62], 'src': "judged:Playful: a livelier drop"},
            'motion.folder.open': {'spring': [0.42, 0.7], 'src': "judged:Playful: folders open with a bounce"},
        },
    },
    'calm': {
        'name': 'Calm', 'description': 'Everything fades, nothing bounces',
        'about': "Apps fade in and out in place; every spring critically damped and a little slower (iOS's Reduce Motion spirit).",
        'tokens': {
            'motion.style.opening': {'choice': 'fade', 'src': "judged:Calm: the app fades in growing a little (Reduce Motion)"},
            'motion.style.closing': {'choice': 'fade', 'src': "judged:Calm: the app fades out shrinking a little"},
            'motion.app.open': {'spring': [0.5, 1.0], 'src': "judged:Calm: unhurried, no overshoot"},
            'motion.app.close-size': {'spring': [0.46, 1.0], 'src': "judged:Calm: unhurried, no overshoot"},
            'motion.app.close-position': {'spring': [0.46, 1.0], 'src': "judged:Calm: with the size"},
            'motion.banner.in': {'spring': [0.5, 1.0], 'src': "judged:Calm: banners settle without a bounce"},
            'motion.folder.open': {'spring': [0.45, 1.0], 'src': "judged:Calm: folders open without a bounce"},
        },
    },
    'cinematic': {
        'name': 'Cinematic', 'description': 'A camera flying into the icon',
        'about': 'Dolly-zoom openings and closings on slower, smoother springs.',
        'tokens': {
            'motion.style.opening': {'choice': 'dolly', 'src': "judged:Cinematic: home flies toward the icon as the app grows (a dolly zoom)"},
            'motion.style.closing': {'choice': 'dolly', 'src': "judged:Cinematic: home flies back out as the app goes into its icon"},
            'motion.app.open': {'spring': [0.6, 0.95], 'src': "judged:Cinematic: slower, for the camera move to read"},
            'motion.home.depth-open': {'spring': [0.66, 1.0], 'src': "judged:Cinematic: home's flight in, a little behind the app"},
            'motion.app.close-size': {'spring': [0.52, 0.95], 'src': "judged:Cinematic: slower into the icon"},
            'motion.app.close-position': {'spring': [0.52, 0.95], 'src': "judged:Cinematic: with the size"},
            'motion.home.depth-close': {'spring': [0.58, 1.0], 'src': "judged:Cinematic: home's flight back out"},
        },
    },
}


def main():
    for pid, p in PRESETS.items():
        preset = {'format': 1, 'name': p['name'], 'description': p['description'], 'author': 'Launcher', 'version': '0.1',
                  'extends': 'ios27', '_about': p['about'] + ' Written by tools/design/build_style_presets.py.',
                  'tokens': dict(sorted(p['tokens'].items()))}
        path = os.path.join(OUT, pid + '.json')
        with open(path, 'w', encoding='utf-8', newline='\n') as f:
            json.dump(preset, f, indent=1, ensure_ascii=False)
            f.write('\n')
        print(f'{path}: {len(p["tokens"])} tokens')


if __name__ == '__main__':
    main()
