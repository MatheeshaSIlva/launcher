#!/usr/bin/env python3
"""
The checks of tools/tests/icon_play_test.sh (B-036 slice 1): tools/icons/build_icon_set.py turns an animated vector into a
"play" block. Run on the committed fixture tools/tests/fixtures/icon_play/fixture_on.xml (a rotation with an overshoot and a
motion path on one group, a morph, a stroke and a trim, a fade on path-data easing) and on variants of it that must be
refused, then on the committed assets/icons/pixel.json (every play ends on its symbol's static look).

    python -I tools/tests/icon_play_check.py REPO_DIR       exit 0: all passed; 1: a check failed (listed)
"""
import json
import math
import os
import subprocess
import sys
import tempfile

REPO = sys.argv[1]
sys.path.insert(0, os.path.join(REPO, 'tools', 'icons'))
import build_icon_set as B   # noqa: E402

FIXTURE = os.path.join(REPO, 'tools', 'tests', 'fixtures', 'icon_play', 'fixture_on.xml')
SRC = open(FIXTURE, encoding='utf-8').read()
FAILS = []


def check(name, ok, detail=''):
    print(('PASS  ' if ok else 'FAIL  ') + name + (f'  [{detail}]' if detail and not ok else ''))
    if not ok:
        FAILS.append(name)


def near(a, b, eps=1e-3):
    return len(a) == len(b) and all(abs(x - y) <= eps for x, y in zip(a, b))


def refused(name, text, why):
    try:
        B.play_of(text.encode())
        check(name, False, 'accepted')
    except ValueError as e:
        check(name, why in str(e), str(e))


play = B.play_of(SRC.encode())
tracks = {(t['k'], t['i'], t['p']): t for t in play['tracks']}

# ----------------------------------------------------------------------------------------------------- the play block
check('length is the last track end', play['ms'] == 400, play['ms'])
check('only the groups that move or turn are kept, with their parent',
      [(g['n'], g['parent']) for g in play['groups']] == [('root_group', -1), ('turn', 0), ('grow', 0)] and play['groups'][1]['pivot'] == [8, 8], play['groups'])
grow = play['groups'][2]
check('a group at scale 0 keeps it (each key has its own default) and is hidden until its delayed track begins',
      grow['scale'] == [0, 0] and grow['pivot'] == [20, 9] and 'rotate' not in grow and 'translate' not in grow
      and B.evaluate(play, 99)[3]['m'][0] == 0 and abs(B.evaluate(play, 100)[3]['m'][0]) < 1e-9
      and abs(B.evaluate(play, 200)[3]['m'][0] - 0.25) < 1e-9 and abs(B.evaluate(play, 300)[3]['m'][0] - 0.5) < 1e-9, grow)
for key, base in (('pivot', [1, 1]), ('translate', [1, 1]), ('scale', [0, 0])):   # non-default values that an equal-for-all filter dropped
    one = SRC.replace('<group android:name="grow" android:pivotX="20" android:pivotY="9" android:scaleX="0" android:scaleY="0">',
                      '<group android:name="grow" android:pivotX="1" android:pivotY="1" android:translateX="1" android:translateY="1" android:scaleX="0" android:scaleY="0">')
    g1 = B.play_of(one.encode())['groups'][2]
    check(f'a group keeps {key} {base}', g1[key] == base, g1)
check('both entries aimed at one group are kept',
      ('g', 1, 'rotation') in tracks and ('g', 1, 'translateXY') in tracks)
check('a pathInterpolator is inlined as its cubic', tracks[('g', 1, 'rotation')]['e'] == [0.26, 1.5, 0.27, 1], tracks[('g', 1, 'rotation')]['e'])
check('an interpolator given as one cubic path is inlined', tracks[('l', 2, 'fillAlpha')]['e'] == [0.4, 0, 0.2, 1], tracks[('l', 2, 'fillAlpha')]['e'])
check('a linear interpolator is the straight cubic', tracks[('l', 1, 'trimPathEnd')]['e'] == [0, 0, 1, 1])
check('no interpolator is the default easing', tracks[('l', 0, 'pathData')]['e'] == 'accdec')
blob = play['layers'][0]
check('a morph is normalised to absolute M/L/C/Z with one command list',
      blob['d'] == 'M4 4C6 2 10 2 12 4L12 12L4 12Z' and tracks[('l', 0, 'pathData')]['from'] == [2, 2, 4, 0, 8, 0, 10, 2, 10, 10, 2, 10]
      and tracks[('l', 0, 'pathData')]['to'] == [4, 4, 6, 2, 10, 2, 12, 4, 12, 12, 4, 12], blob['d'])
mp = tracks[('g', 1, 'translateXY')]
check('a motion path is a polyline with cumulative lengths',
      len(mp['pts']) == 36 and mp['len'][1] == 10 and mp['len'][-1] > 20 and mp['pts'][-2:] == [0, 10] and len(mp['len']) == 18, mp['len'][-3:])
bar = play['layers'][1]
check('a stroke keeps its static properties (enum written as a number decoded)', bar.get('s') == 3 and bar.get('cap') == 'round' and 'a' in bar and bar['a'] == 0, bar)
check('a sequential set runs one entry after the other', tracks[('l', 1, 'trimPathEnd')]['at'] == 100 and tracks[('l', 1, 'strokeWidth')]['at'] == 0)
fade = play['layers'][2]
fa = tracks[('l', 2, 'fillAlpha')]   # the colour #80ffffff is 128/255 opaque
check("alpha tracks carry the colour's own alpha", abs(fade['a'] - 128 / 255) < 1e-3 and abs(fa['from'] - 128 / 255) < 1e-3
      and abs(fa['to'] - 0.4 * 128 / 255) < 1e-3, (fade, fa))

# --------------------------------------------------------------------------------------------- the reference evaluator
ev0, ev1 = B.evaluate(play, 0), B.evaluate(play, 400)
check('at 0 a morph is its first look, at the end its last', near(ev0[0]['p'], tracks[('l', 0, 'pathData')]['from']) and near(ev1[0]['p'], tracks[('l', 0, 'pathData')]['to']))
vals = [B.tracked(play, t)[('g', 1)]['rotation'] for t in range(100, 401, 5)]   # the group's rotation over its 300 ms
check('an overshoot goes past its target and settles on it (not clamped)', max(vals) > 90.5 and abs(vals[-1] - 90) < 1e-6, (max(vals), vals[-1]))
check('a track waits for its start', 'rotation' not in B.tracked(play, 99).get(('g', 1), {}) and 'rotation' in B.tracked(play, 100)[('g', 1)])
x, y = B.points(ev1[1]['m'], None, 'M2,20 L22,20')[0]   # (2,20) turned 90 degrees about (8,8), moved by (0,10), halved about (12,12)
check('a group turned and moved puts a point where the hand calculation says', abs(x - 4) < 1e-6 and abs(y - 12) < 1e-6, (x, y))
mid = B.evaluate(play, 150)[2]['a']   # 100 ms into a 200 ms fade on (0.4, 0, 0.2, 1)
want = fa['from'] + (fa['to'] - fa['from']) * B.ease([0.4, 0, 0.2, 1], 0.5)
check('a fade follows its easing', abs(mid - want) < 1e-9 and fa['to'] < mid < fa['from'], (mid, want))
check('the cubic is solved for x (known points)', abs(B.ease([0.25, 0.1, 0.25, 1], 0.0)) < 1e-9 and abs(B.ease([0.25, 0.1, 0.25, 1], 1.0) - 1) < 1e-9
      and abs(B.ease([0, 0, 1, 1], 0.3) - 0.3) < 1e-9 and abs(B.ease('accdec', 0.5) - 0.5) < 1e-9)

# ----------------------------------------------------------------------------------- symbols, continuity, the static look
with tempfile.TemporaryDirectory() as cache:
    os.makedirs(os.path.join(cache, 'apk'))
    with open(os.path.join(cache, 'apk', 'fixture_on.xml'), 'w', encoding='utf-8') as f:
        f.write(SRC)
    sym = B.symbol(cache, ('apk', 'fixture_on', 'end'))
    check('a symbol from an animated vector at its end carries its play', sym.get('play') == play)
    check('the play ends on the symbol\'s static look', B.matches_static(sym['play'], sym['layers']) == '', B.matches_static(sym['play'], sym['layers']))
    check('a symbol at another frame carries none (the set crossfades as before)', 'play' not in B.symbol(cache, ('apk', 'fixture_on', 'static')))
c = B.continuity({**play, 'ms': 0}, play)
check('a look meeting itself has no gap', c == [0.0, 0.0, 0.0, 0], c)
c = B.continuity(play, play)
check('a look meeting another is measured', c[0] > 1, c)

# ----------------------------------------------------------------------------------------------------------- refusals
refused('an interpolator of two cubics is refused', SRC.replace('M 0.0,0.0 c0.4,0 0.2,1 1.0,1.0', 'M 0,0 C0.1,0 0.2,0.5 0.5,0.5 C0.6,0.5 0.8,1 1,1'), 'one cubic')
refused('a cubic whose control x leaves 0..1 is refused (x would not be a function of time)', SRC.replace('controlX2="0.27"', 'controlX2="1.3"'), 'outside 0..1')
refused('a path-data cubic whose control x leaves 0..1 is refused', SRC.replace('M 0.0,0.0 c0.4,0 0.2,1 1.0,1.0', 'M 0,0 C-0.2,0 0.2,1 1,1'), 'outside 0..1')
refused('a repeating animation is refused', SRC.replace('android:duration="300"', 'android:duration="300" android:repeatCount="-1"'), 'repeating')
refused('an interpolator that is not a cubic is refused', SRC.replace('<linearInterpolator>\n</linearInterpolator>', '<accelerateInterpolator>\n</accelerateInterpolator>', 1), 'not a cubic Bezier')
refused('a delayed first track that does not start from the base is refused',
        SRC.replace('android:valueFrom="0" android:valueTo="1" android:propertyName="scaleX"', 'android:valueFrom="0.5" android:valueTo="1" android:propertyName="scaleX"'), 'rests at')
refused('paths that cannot morph are refused', SRC.replace('M4,4 C6,2 10,2 12,4 L12,12 L4,12 Z" android:valueType', 'M4,4 L12,12 Z" android:valueType'), 'cannot morph')
refused('a reference nobody inlined is refused', SRC.replace('android:propertyName="pathData">', 'android:propertyName="pathData" android:interpolator="@0x7f010999">'), 'not inlined')
refused('an animator aimed at nothing is refused', SRC.replace('target android:name="blob"', 'target android:name="nothing"'), 'does not have')
refused('an arc cannot be morphed', SRC.replace('M4,4 C6,2 10,2 12,4 L12,12 L4,12 Z" android:valueType', 'M4,4 A6,6 0 1,1 12,4 L12,12 L4,12 Z" android:valueType'), 'arc')
ok = B.play_of(SRC.replace('android:propertyName="pathData">', 'android:propertyName="pathData" android:interpolator="@android:interpolator/fast_out_slow_in">').encode())
check('a standard interpolator named by reference is its cubic', [t for t in ok['tracks'] if t['p'] == 'pathData'][0]['e'] == [0.4, 0, 0.2, 1])

# ------------------------------------------------------------------------------------- path data against hand-computed numbers
def norm(d, cmds, nums):
    got = B.normalise(d)
    check('normalise ' + d, got[0] == cmds and near(got[1], nums), got)


third = 20 / 3
norm('M0,0 Q10,10 20,0 T40,0', 'MCC', [0, 0, third, third, 40 / 3, third, 20, 0, 20 + third, -third, 40 - third, -third, 40, 0])
norm('M0,0 C0,10 10,10 10,0 S20,-10 20,0', 'MCC', [0, 0, 0, 10, 10, 10, 10, 0, 10, -10, 20, -10, 20, 0])
norm('M0,0 c0,10 10,10 10,0 s10,-10 10,0', 'MCC', [0, 0, 0, 10, 10, 10, 10, 0, 10, -10, 20, -10, 20, 0])
norm('M0,0 L10,0 S20,10 30,0', 'MLC', [0, 0, 10, 0, 10, 0, 20, 10, 30, 0])
norm('m5,5 l5,0 s10,10 20,0', 'MLC', [5, 5, 10, 5, 10, 5, 20, 15, 30, 5])
norm('M0,0 L10,0 T20,0', 'MLC', [0, 0, 10, 0, 10, 0, 40 / 3, 0, 20, 0])
norm('m1,1 q4,4 8,0 t8,0', 'MCC', [1, 1, 1 + 8 / 3, 1 + 8 / 3, 9 - 8 / 3, 1 + 8 / 3, 9, 1, 9 + 8 / 3, 1 - 8 / 3, 17 - 8 / 3, 1 - 8 / 3, 17, 1])
norm('M1,2 H5 V7 h-2 v-3 Z', 'MLLLLZ', [1, 2, 5, 2, 5, 7, 3, 7, 3, 4])
norm('M0,0 10,10 20,0', 'MLL', [0, 0, 10, 10, 20, 0])
norm('m1,1 2,2', 'ML', [1, 1, 3, 3])
norm('M0,0 L4,0 L4,4 z m1,1 l1,0', 'MLLZML', [0, 0, 4, 0, 4, 4, 1, 1, 2, 1])

# ------------------------------------------------------------------- the extractor's framework interpolators, id by id
import xml.etree.ElementTree as ET   # noqa: E402
sys.path.insert(0, os.path.join(REPO, 'tools', 'icons'))
import extract_apk_vectors as E   # noqa: E402

fw = {i: ET.fromstring(x) for i, x in E.FRAMEWORK_INTERPOLATORS.items()}
check('framework ids: linear, fast_out_slow_in, linear_out_slow_in, accelerate_decelerate are what android.R says',
      B.ease_of(fw['0x010c000b']) == [0, 0, 1, 1] and B.ease_of(fw['0x010c000d']) == [0.4, 0, 0.2, 1]
      and B.ease_of(fw['0x010c000e']) == [0, 0, 0.2, 1] and B.ease_of(fw['0x010c0006']) == 'accdec')
check('framework ids: the others are their own elements, which the builder refuses',
      [fw[i].tag for i in ('0x010c0000', '0x010c0001', '0x010c0002', '0x010c0003', '0x010c0004', '0x010c0005', '0x010c0007', '0x010c0008', '0x010c0009', '0x010c000a')]
      == ['accelerateInterpolator', 'decelerateInterpolator', 'accelerateInterpolator', 'decelerateInterpolator', 'accelerateInterpolator',
          'decelerateInterpolator', 'anticipateInterpolator', 'overshootInterpolator', 'anticipateOvershootInterpolator', 'bounceInterpolator'])
try:
    B.ease_of(fw['0x010c0008'])
    check('framework ids: overshoot (not a cubic) is refused', False)
except ValueError as e:
    check('framework ids: overshoot (not a cubic) is refused', 'not a cubic' in str(e), str(e))

# ---------------------------------------------------------------------------------------------------------- the command
r = subprocess.run([sys.executable, '-I', os.path.join(REPO, 'tools', 'icons', 'build_icon_set.py'), '--play', FIXTURE, '0,200,400'],
                   capture_output=True, text=True)
out = json.loads(r.stdout) if r.returncode == 0 else {}
check('--play prints the play and the values at the times asked', out.get('play') == play and [e['t'] for e in out.get('expected', [])] == [0, 200, 400], r.stderr[-200:])

# ------------------------------------------------------------------------------------------------ the committed icon set
pixel = json.load(open(os.path.join(REPO, 'app', 'src', 'main', 'assets', 'icons', 'pixel.json'), encoding='utf-8'))
animated = {k: v for k, v in pixel['icons'].items() if 'play' in v}
check('pixel.json is format 2 and animates the tiles', pixel['format'] == 2 and len(animated) >= 20, (pixel['format'], len(animated)))
kinds = {}
for v in animated.values():
    for t in v['play']['tracks']:
        kinds[json.dumps(t['e'])] = kinds.get(json.dumps(t['e']), 0) + 1
# (Android's anim/overshoot, ease_out and ease, and the default easing: an extractor that dropped the interpolators would not give these)
check('pixel.json keeps the interpolators: every easing the tiles use, counted',
      kinds == {'[0.26, 1.5, 0.27, 1]': 44, '[0.5, 0, 1, 1]': 6, '[0.5, 0, 0.5, 1]': 2, '"accdec"': 26}, kinds)
fl = [t for t in animated['flashlight']['play']['tracks'] if t['p'] == 'pathData']
check('flashlight morphs on the overshoot', len(fl) == 1 and fl[0]['e'] == [0.26, 1.5, 0.27, 1], fl)
check('bluetooth on has no play (it crossfades from the slash), bluetooth off has', 'play' not in pixel['icons']['bluetooth'] and 'play' in pixel['icons']['bluetooth.off'])
bad = {k: B.matches_static(v['play'], v['layers']) for k, v in animated.items() if B.matches_static(v['play'], v['layers'])}
check('every play in pixel.json ends on its symbol\'s static look', not bad, bad)

print()
if FAILS:
    print(f'FAILED: {len(FAILS)} check(s): ' + '; '.join(FAILS))
    sys.exit(1)
print('PASSED')
