#!/usr/bin/env python3
"""
Builds the icon sets (app/src/main/assets/icons/<id>.json): symbols as vector paths that the shade (and, later, the status
bar) draws in place of its own wherever a theme names the set (`sys.symbols`, read through each element's own token,
`comp.cc.symbols` ...). A name the set leaves out falls back to the app's own symbol (res/drawable/sym_<name>.xml: Material
Symbols Rounded). A name with ".off" after it is the symbol's look while its control is off (the shade crossfades the two
as the control turns on and off).

Sources are Android vector drawables (a plain vector, or an animated vector at its first or last frame: AOSP's Quick
Settings icons animate between their off and on looks) and SVGs, downloaded once into CACHE_DIR (untrusted data: parsed,
never run). Each symbol keeps its source in the file.

    python -I tools/icons/build_icon_set.py CACHE_DIR
"""
import base64
import json
import math
import os
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'icons')
AOSP = 'https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/'
A = '{http://schemas.android.com/apk/res/android}'
AAPT = '{http://schemas.android.com/aapt}'
SUI = 'packages/SystemUI/res/drawable/'
KEY = 'packages/SystemUI/res-keyguard/drawable/'
CORE = 'core/res/res/drawable/'


def tile(path):
    """An AOSP Quick Settings tile's animated pair: its on look (the end of qs_*_on), its off look (the end of qs_*_off)."""
    return {'on': ('aosp', path + '_on.xml', 'end'), 'off': ('aosp', path + '_off.xml', 'end')}


def aosp(path, frame='static', scale=None):
    """[scale]: drawn at that part of the symbol's size (Android draws some at their own small size in a larger slot)."""
    return {'on': ('aosp', path, frame) + ((scale,) if scale else ())}


# Pixel: Android's own Quick Settings symbols (AOSP SystemUI, read 2026-10-19), as each tile names them (qs/tiles/*.java).
PIXEL = {
    'airplane': tile(SUI + 'qs_airplane_icon'),
    'low_power': tile(SUI + 'qs_battery_saver_icon'),
    'bluetooth': tile(KEY + 'qs_bluetooth_icon'),
    'data_saver': tile(SUI + 'qs_data_saver_icon'),
    'moon': tile(SUI + 'qs_dnd_icon'),
    'flashlight': tile(SUI + 'qs_flashlight_icon'),
    'hotspot': tile(SUI + 'qs_hotspot_icon'),
    'location': tile(SUI + 'qs_location_icon'),
    'screen_record': tile(SUI + 'qs_screen_record_icon'),
    'dark_mode': tile(KEY + 'qs_light_dark_theme_icon'),
    'invert': tile(KEY + 'qs_invert_colors_icon'),
    # Rotation: Pixel's tile is "Auto-rotate", lit while the screen turns; ours is the lock, lit while it does not.
    'rotation': aosp(KEY + 'qs_auto_rotate_icon_on.xml', 'end'),
    'rotation_lock': aosp(KEY + 'qs_auto_rotate_icon_off.xml', 'end'),
    'alarm': aosp(SUI + 'ic_alarm.xml'),
    'captions': aosp(SUI + 'ic_volume_odi_captions.xml'),
    'grayscale': aosp(SUI + 'ic_qs_color_correction.xml'),
    'extra_dim': aosp(SUI + 'ic_reduce_bright_colors.xml'),
    'hearing': aosp(SUI + 'qs_hearing_devices_icon.xml'),
    'mirroring': aosp(SUI + 'ic_cast.xml'),
    'nfc': aosp(SUI + 'ic_qs_nfc.xml'),
    'note': aosp(SUI + 'ic_qs_notes.xml'),
    'qr': aosp(SUI + 'ic_qr_code_scanner.xml'),
    'text_size': aosp(SUI + 'ic_qs_font_scaling.xml'),
    'wallet': aosp(SUI + 'ic_qs_wallet.xml'),
    'settings': aosp(SUI + 'ic_settings.xml'),
    'sun': aosp(SUI + 'ic_brightness_full.xml'),
    'volume': aosp(SUI + 'ic_volume_media.xml'),
    'volume_off': aosp(SUI + 'ic_volume_media_mute.xml'),
    'bell': aosp(SUI + 'ic_volume_ringer.xml'),
    'bell_off': aosp(SUI + 'ic_volume_ringer_mute.xml'),
    'play': aosp(SUI + 'ic_media_play.xml'),
    'pause': aosp(SUI + 'ic_media_pause.xml'),
    # The media player's skip buttons: 12 dp symbols in 24 dp slots (filled to the slot they were twice play's size).
    'forward': aosp(SUI + 'ic_media_next.xml', scale=0.5),
    'rewind': aosp(SUI + 'ic_media_prev.xml', scale=0.5),
    'wifi': aosp(CORE + 'ic_wifi_signal_4.xml'),
    # (The media output button keeps the app's own: AOSP's seamless-transfer symbol is a music note.)
}

SETS = {
    'pixel': {
        'name': 'Pixel',
        'description': "Android's own Quick Settings symbols (AOSP SystemUI); the app's own (Material Symbols Rounded) for the rest",
        'license': 'Apache-2.0 (The Android Open Source Project)',
        'symbols': PIXEL,
    },
}


# ---------------------------------------------------------------------------------------------------------------- fetch

def fetch(cache, kind, path):
    dest = os.path.join(cache, kind, path.replace('/', '__'))
    if not os.path.exists(dest):
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        if kind == 'aosp':
            with urllib.request.urlopen(AOSP + path + '?format=TEXT', timeout=60) as r:
                data = base64.b64decode(r.read())
        else:
            with urllib.request.urlopen(path, timeout=60) as r:
                data = r.read()
        with open(dest, 'wb') as f:
            f.write(data)
    return open(dest, 'rb').read()


# --------------------------------------------------------------------------------------------------------------- matrix

IDENTITY = (1.0, 0.0, 0.0, 0.0, 1.0, 0.0)   # x' = a x + b y + c, y' = d x + e y + f (Android's MSCALE_X ... order)


def mul(p, q):
    """p after q (q applied first)."""
    a, b, c, d, e, f = p
    A_, B, C, D, E, F = q
    return (a * A_ + b * D, a * B + b * E, a * C + b * F + c, d * A_ + e * D, d * B + e * E, d * C + e * F + f)


def group_matrix(g):
    """VectorDrawable's group: translate(-pivot), scale, rotate, translate(translate + pivot)."""
    px, py = g.get('pivotX', 0.0), g.get('pivotY', 0.0)
    sx, sy = g.get('scaleX', 1.0), g.get('scaleY', 1.0)
    r = math.radians(g.get('rotation', 0.0))
    tx, ty = g.get('translateX', 0.0), g.get('translateY', 0.0)
    m = (1.0, 0.0, -px, 0.0, 1.0, -py)
    m = mul((sx, 0.0, 0.0, 0.0, sy, 0.0), m)
    m = mul((math.cos(r), -math.sin(r), 0.0, math.sin(r), math.cos(r), 0.0), m)
    return mul((1.0, 0.0, tx + px, 0.0, 1.0, ty + py), m)


# ----------------------------------------------------------------------------------------------------- vector drawables

NUMERIC = {'pivotX', 'pivotY', 'scaleX', 'scaleY', 'rotation', 'translateX', 'translateY', 'fillAlpha', 'strokeAlpha',
           'strokeWidth', 'trimPathStart', 'trimPathEnd', 'trimPathOffset', 'alpha'}


def attrs(el):
    out = {}
    for k, v in el.attrib.items():
        if k.startswith(A):
            name = k[len(A):]
            out[name] = float(v) if name in NUMERIC else v
    return out


def colour_alpha(v):
    """How opaque a colour attribute is (a reference to a theme colour counts as opaque; no colour: 0)."""
    if v is None:
        return 0.0
    v = v.strip()
    if v.startswith('#'):
        h = v[1:]
        if len(h) in (3, 6):
            return 1.0
        if len(h) == 4:
            return int(h[0] * 2, 16) / 255.0
        if len(h) == 8:
            return int(h[:2], 16) / 255.0
    return 1.0


def inline(el, name):
    for at in el.findall(AAPT + 'attr'):
        if at.get('name') == name:
            return list(at)[0]
    return None


def animators(el, start=0.0, out=None):
    """Every objectAnimator under [el] as (property, from, to, start, end), sequential sets one after another."""
    if out is None:
        out = []
    tag = el.tag
    if tag == 'set':
        t = start
        seq = el.get(A + 'ordering') == 'sequentially'
        for ch in el:
            if ch.tag in ('set', 'objectAnimator'):
                end = animators(ch, t, out)
                if seq:
                    t = end
        return max([start] + [o[4] for o in out])
    if tag == 'objectAnimator':
        a = attrs(el)
        s = start + float(a.get('startOffset', 0))
        e = s + float(a.get('duration', 300))
        if a.get('repeatCount') not in (None, '0'):
            raise ValueError('a looping animation has no last frame')
        holders = el.findall('propertyValuesHolder')
        if a.get('propertyXName') and a.get('pathData'):
            # Moved along a motion path (translateXY): from its first point to its last.
            (x0, y0), (x1, y1) = path_ends(a['pathData'])
            out.append((a['propertyXName'], x0, x1, s, e))
            out.append((a['propertyYName'], y0, y1, s, e))
        elif holders:
            for h in holders:
                ha = attrs(h)
                frames = h.findall('keyframe')
                if frames:
                    vals = [attrs(k).get('value') for k in frames]
                    out.append((ha['propertyName'], vals[0], vals[-1], s, e))
                else:
                    out.append((ha['propertyName'], ha.get('valueFrom'), ha.get('valueTo'), s, e))
        else:
            out.append((a['propertyName'], a.get('valueFrom'), a.get('valueTo'), s, e))
        return e
    return start


NUMBER = re.compile(r'[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?')
ARGS = {'m': 2, 'l': 2, 'h': 1, 'v': 1, 'c': 6, 's': 4, 'q': 4, 't': 2, 'a': 7, 'z': 0}


def path_ends(d):
    """The first and last points of path data [d] (a motion path)."""
    tokens = re.findall(r'[MmLlHhVvCcSsQqTtAaZz]|' + NUMBER.pattern, d)
    x = y = sx = sy = 0.0
    first = None
    cmd = None
    i = 0
    while i < len(tokens):
        t = tokens[i]
        if t.isalpha():
            cmd = t
            i += 1
            if cmd in 'Zz':
                x, y = sx, sy
            continue
        n = ARGS[cmd.lower()]
        v = [float(tokens[i + k]) for k in range(n)]
        i += n
        rel = cmd.islower()
        c = cmd.lower()
        if c == 'h':
            x = x + v[0] if rel else v[0]
        elif c == 'v':
            y = y + v[0] if rel else v[0]
        else:
            nx, ny = v[-2], v[-1]
            x, y = (x + nx, y + ny) if rel else (nx, ny)
        if c == 'm':
            sx, sy = x, y
            cmd = 'l' if rel else 'L'
        if first is None:
            first = (x, y)
    return first or (0.0, 0.0), (x, y)


def frame_values(root, frame, cache, kind):
    """
    {target name: {property: value}} of an animated vector at its first or last frame. A name may be the target of several
    entries (one moving a group, another turning it): their animators count together.
    """
    found_by = {}
    for t in root.findall('target'):
        name = t.get(A + 'name')
        anim = inline(t, 'android:animation')
        if anim is None:
            ref = t.get(A + 'animation')
            if ref and ref.startswith('@animator/'):
                anim = ET.fromstring(fetch(cache, kind, 'packages/SystemUI/res/animator/' + ref[10:] + '.xml'))
        if anim is None:
            continue
        animators(anim, 0.0, found_by.setdefault(name, []))
    vals = {}
    for name, found in found_by.items():
        per = {}
        for prop, frm, to, s, e in found:
            if frame == 'end':
                if prop not in per or e >= per[prop][1]:
                    per[prop] = (to if to is not None else frm, e)
            else:
                if frm is not None and (prop not in per or s <= per[prop][1]):
                    per[prop] = (frm, s)
        vals[name] = {p: (float(v) if p in NUMERIC else v) for p, (v, _) in per.items() if v is not None}
    return vals


def flatten(vector, overrides):
    """The vector's paths as layers: each with its group transforms multiplied in, its alpha, fill rule, stroke, trim."""
    layers = []

    def walk(el, matrix, clips):
        clips = list(clips)
        for ch in el:
            if ch.tag == 'group':
                g = attrs(ch)
                g.update(overrides.get(g.get('name'), {}))
                walk(ch, mul(matrix, group_matrix(g)), clips)
            elif ch.tag == 'clip-path':
                c = attrs(ch)
                c.update(overrides.get(c.get('name'), {}))
                if c.get('pathData'):
                    clips.append(dict(d=c['pathData'].strip(), **({'m': rnd(matrix)} if matrix != IDENTITY else {})))
            elif ch.tag == 'path':
                p = attrs(ch)
                p.update(overrides.get(p.get('name'), {}))
                d = (p.get('pathData') or '').strip()
                if not d:
                    continue
                fill = colour_alpha(p.get('fillColor')) * float(p.get('fillAlpha', 1.0))
                stroke_w = float(p.get('strokeWidth', 0.0))
                stroke = colour_alpha(p.get('strokeColor')) * float(p.get('strokeAlpha', 1.0)) if stroke_w > 0 else 0.0
                if fill <= 0.004 and stroke <= 0.004:
                    continue
                layer = {'d': d}
                if matrix != IDENTITY:
                    layer['m'] = rnd(matrix)
                if fill > 0.004:
                    if fill < 0.996:
                        layer['a'] = round(fill, 3)
                else:
                    layer['a'] = 0
                if p.get('fillType') == 'evenOdd':
                    layer['even'] = 1
                if stroke > 0.004:
                    layer['s'] = round(stroke_w, 3)
                    if stroke < 0.996:
                        layer['sa'] = round(stroke, 3)
                    if p.get('strokeLineCap') in ('round', 'square'):
                        layer['cap'] = p['strokeLineCap']
                    if p.get('strokeLineJoin') in ('round', 'bevel'):
                        layer['join'] = p['strokeLineJoin']
                trim = [float(p.get('trimPathStart', 0.0)), float(p.get('trimPathEnd', 1.0)), float(p.get('trimPathOffset', 0.0))]
                if trim != [0.0, 1.0, 0.0]:
                    if abs(trim[1] - trim[0]) < 0.001:
                        continue
                    layer['trim'] = [round(x, 4) for x in trim]
                if clips:
                    layer['clip'] = clips
                layers.append(layer)

    walk(vector, IDENTITY, [])
    return layers


def rnd(m):
    return [round(x, 5) for x in m]


def from_vector(data, frame, cache, kind):
    root = ET.fromstring(data)
    overrides = {}
    if root.tag == 'animated-vector':
        vector = inline(root, 'android:drawable')
        if vector is None:
            ref = root.get(A + 'drawable')
            vector = ET.fromstring(fetch(cache, kind, SUI + ref.split('/')[-1] + '.xml'))
        if frame != 'static':
            overrides = frame_values(root, frame, cache, kind)
    else:
        vector = root
    if vector.tag != 'vector':
        raise ValueError('not a vector: ' + vector.tag)
    v = attrs(vector)
    box = [0, 0, float(v['viewportWidth']), float(v['viewportHeight'])]
    return box, flatten(vector, overrides)


def from_svg(data):
    root = ET.fromstring(data)
    box = [float(x) for x in re.split(r'[\s,]+', root.get('viewBox').strip())]
    layers = [{'d': p.get('d').strip()} for p in root.iter('{http://www.w3.org/2000/svg}path')
              if p.get('d') and p.get('fill') != 'none']
    return box, layers


def symbol(cache, src):
    kind, path, frame = src[:3]
    data = fetch(cache, kind, path)
    box, layers = from_svg(data) if path.endswith('.svg') else from_vector(data, frame, cache, kind)
    if not layers:
        raise ValueError('nothing drawn')
    where = ('AOSP frameworks/base ' + path) if kind == 'aosp' else path
    what = {'end': ' (its last frame)', 'start': ' (its first frame)', 'static': ''}[frame]
    out = {'src': where + what, 'box': [round(x, 3) for x in box], 'layers': layers}
    if len(src) > 3:
        out['scale'] = src[3]
    return out


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    cache = sys.argv[1]
    os.makedirs(OUT, exist_ok=True)
    for sid, spec in SETS.items():
        icons = {}
        failed = []
        for name, states in spec['symbols'].items():
            for state, src in states.items():
                key = name if state == 'on' else name + '.off'
                try:
                    icons[key] = symbol(cache, src)
                except Exception as e:  # noqa: BLE001 (a symbol that cannot be read is left to the app's own)
                    failed.append(f'{key}: {e}')
        out = {'format': 1, 'name': spec['name'], 'description': spec['description'], 'license': spec['license'],
               '_about': 'Written by tools/icons/build_icon_set.py; each symbol keeps its source. A name left out is drawn '
                         'with the app\'s own symbol; NAME.off is the look of a control that is off.',
               'icons': dict(sorted(icons.items()))}
        path = os.path.join(OUT, sid + '.json')
        with open(path, 'w', encoding='utf-8', newline='\n') as f:
            json.dump(out, f, indent=1, ensure_ascii=False)
            f.write('\n')
        print(f'{path}: {len(icons)} symbols' + (f'; not read: {"; ".join(failed)}' if failed else ''))


if __name__ == '__main__':
    main()
