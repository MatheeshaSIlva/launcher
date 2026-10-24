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

A symbol that comes from an animated vector at its last frame also carries its animation (`"play"`, file format 2; the
static layers stay as they were, the animation is read by the symbol player): `ms` the whole length; `groups` (the groups
that move or turn, each with its static pivot / scale / rotate / translate and its `parent`, -1 for none); `layers` every
path in drawing order (`g` its group, `d` its path, `a` fill, `s` stroke width, `sa` stroke alpha, `trim`, `clip`: indexes
into `clips`, each `d` and `g`); `tracks` (`k` g, l or c: a group, a layer or a clip; `i` its index; `p` the property:
rotation, scaleX, scaleY, translateX, translateY, translateXY (`pts` a polyline, `len` its cumulative lengths), pathData
(`from` / `to` the numbers of its absolute M/L/C/Z path, the same commands in the layer's `d`), fillAlpha, strokeAlpha
(already times the colour's own alpha), strokeWidth, trimPathStart / End / Offset; `at` and `ms` start and length; `e`
the easing: a cubic [x1, y1, x2, y2] or "accdec", what Android uses for an animator that names none). A target's value at
t: its base value, then every track whose `at` has come in file order, each at its eased fraction (held at its end after
it): the last to write a property wins. Before a property's first track begins Android shows that track's `from`; the
play shows the base, so an animation whose delayed first track does not start from its target's base is refused (as is a
looping animation, or an interpolator that is not one cubic).

    python -I tools/icons/build_icon_set.py --play AVD.xml [T1,T2,...]    its play and its values at those times (ms)

    python -I tools/icons/extract_apk_vectors.py SystemUIGoogle.apk CACHE_DIR vd_wifi vd_cell ic_wallet_lockscreen controls_icon \
        vd_bluetooth_buds/bluetooth_plain/circle_left+circle_right
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


def apktile(name):
    """Android 17's own tile pair out of its SystemUIGoogle (extract_apk_vectors.py into CACHE_DIR/apk): on and off, each at
    the end of its animation."""
    return {'on': ('apk', name + '_on', 'end'), 'off': ('apk', name + '_off', 'end')}


def aosp(path, frame='static', scale=None):
    """[scale]: drawn at that part of the symbol's size (Android draws some at their own small size in a larger slot)."""
    return {'on': ('aosp', path, frame) + ((scale,) if scale else ())}


# (Bluetooth on with no device has no play: Android's off icon ends on the slash, but qs_bluetooth_icon_disconnected starts
# with the two circles and no slash, so playing it from off would pop. It crossfades from the slash; bluetooth.off keeps its play.)
# Symbols that are one control's two looks (Android's auto-rotate on and off; ours is the lock, inverted): on, off.
PAIRS = [('rotation', 'rotation_lock')]

# Pixel: Android's own Quick Settings symbols, as each tile names them: Android 17's own (its SystemUIGoogle APK, read
# 2026-10-21: AOSP main's differ, Auto-rotate's, Flashlight's, Battery Saver's...), AOSP main's where the APK has none.
PIXEL = {
    'airplane': apktile('qs_airplane_icon'),
    'low_power': apktile('qs_battery_saver_icon'),
    # Bluetooth on without a device: its base drawn without the earbuds' dots (what qs_bluetooth_icon_disconnected ends on).
    'bluetooth': {'on': ('apk', 'bluetooth_plain', 'static'), 'off': ('apk', 'qs_bluetooth_icon_off', 'end')},
    'data_saver': apktile('qs_data_saver_icon'),
    # Modes: the filled circle with its bar, on and off alike (Android 17's Modes tile keeps its icon; only its colours
    # follow the state).
    'moon': {'on': ('url', 'https://raw.githubusercontent.com/google/material-design-icons/master/symbols/web/do_not_disturb_on/materialsymbolsrounded/do_not_disturb_on_fill1_24px.svg', 'static')},
    'flashlight': apktile('qs_flashlight_icon'),
    'hotspot': apktile('qs_hotspot_icon'),
    'location': apktile('qs_location_icon'),
    'screen_record': apktile('qs_screen_record_icon'),
    'dark_mode': apktile('qs_light_dark_theme_icon'),
    'invert': apktile('qs_invert_colors_icon'),
    # Rotation: Pixel's tile is "Auto-rotate", lit while the screen turns; ours is the lock, lit while it does not.
    'rotation': {'on': ('apk', 'qs_auto_rotate_icon_on', 'end')},
    'rotation_lock': {'on': ('apk', 'qs_auto_rotate_icon_off', 'end')},
    'alarm': {'on': ('apk', 'ic_alarm', 'static')},
    'captions': {'on': ('apk', 'ic_volume_odi_captions', 'static')},
    'grayscale': {'on': ('apk', 'ic_qs_color_correction', 'static')},
    'extra_dim': aosp(SUI + 'ic_reduce_bright_colors.xml'),
    'hearing': {'on': ('apk', 'qs_hearing_devices_icon', 'static')},
    'mirroring': {'on': ('apk', 'ic_cast', 'static')},
    'nfc': {'on': ('apk', 'ic_qs_nfc', 'static')},
    'note': {'on': ('apk', 'ic_qs_notes', 'static')},
    'qr': {'on': ('apk', 'ic_qr_code_scanner', 'static')},
    'text_size': {'on': ('apk', 'ic_qs_font_scaling', 'static')},
    'wallet': {'on': ('apk', 'ic_wallet_lockscreen', 'static')},
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
    # Android 17's tiles where they changed from AOSP main's (its SystemUIGoogle: extract_apk_vectors.py into CACHE_DIR/apk
    # first): Wi-Fi's two arcs and a dot, Mobile data's arrows (vd_cell: what ic_cell_on rests on), Wallet's card, Device controls.
    'wifi': {'on': ('apk', 'vd_wifi', 'static')},
    'cellular': {'on': ('apk', 'vd_cell', 'static')},
    'home': {'on': ('apk', 'controls_icon', 'static')},
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
    if kind == 'apk':
        # Android 17's own icons, out of its SystemUIGoogle APK by extract_apk_vectors.py (never downloaded here).
        return open(os.path.join(cache, 'apk', path + '.xml'), 'rb').read()
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


# ---------------------------------------------------------------------------------------------------------------- play

class PlayError(Exception):
    """A symbol's animation cannot be exported: the build stops (a symbol must not lose it silently)."""


CMD_ARGS = {'M': 2, 'L': 2, 'C': 6, 'Z': 0}
GROUP_DEFAULTS = {'pivot': [0, 0], 'scale': [1, 1], 'rotate': 0, 'translate': [0, 0]}   # what a group written without a key has
STD_EASES = {'linear': [0, 0, 1, 1], 'fast_out_slow_in': [0.4, 0, 0.2, 1], 'linear_out_slow_in': [0, 0, 0.2, 1],
             'accelerate_decelerate': 'accdec'}
GROUP_PROPS = {'rotation', 'scaleX', 'scaleY', 'translateX', 'translateY', 'translateXY'}
PATH_PROPS = {'pathData', 'fillAlpha', 'strokeAlpha', 'strokeWidth', 'trimPathStart', 'trimPathEnd', 'trimPathOffset'}


def r4(v):
    v = round(v, 4) or 0.0
    return int(v) if v == int(v) else v


def enum(v, names):
    """An attribute that aapt2 dumps as its number (strokeLineCap="1") or that a source writes as its name."""
    return names[int(v)] if v is not None and v.isdigit() else v


def normalise(d):
    """Path data as absolute M/L/C/Z: (command letters, flat numbers). Arcs are refused (they cannot morph)."""
    tokens = re.findall(r'[MmLlHhVvCcSsQqTtAaZz]|' + NUMBER.pattern, d)
    cmds, nums = '', []
    x = y = sx = sy = px = py = 0.0
    prev = cmd = None
    i = 0
    while i < len(tokens):
        if tokens[i].isalpha():
            cmd, i = tokens[i], i + 1
            if cmd in 'Zz':
                cmds, x, y, prev = cmds + 'Z', sx, sy, 'z'
            continue
        c, rel = cmd.lower(), cmd.islower()
        if c == 'a':
            raise ValueError('an arc cannot be morphed or normalised: ' + d[:40])
        v = [float(tokens[i + k]) for k in range(ARGS[c])]
        i += ARGS[c]
        ox, oy = (x, y) if rel else (0.0, 0.0)
        if c in 'mlhv':
            x, y = (ox + v[0], y) if c == 'h' else (x, oy + v[0]) if c == 'v' else (ox + v[0], oy + v[1])
            cmds, nums = cmds + ('M' if c == 'm' else 'L'), nums + [x, y]
            if c == 'm':
                sx, sy, cmd = x, y, ('l' if rel else 'L')
        else:
            if c == 'c':
                x1, y1, x2, y2, ex, ey = ox + v[0], oy + v[1], ox + v[2], oy + v[3], ox + v[4], oy + v[5]
                px, py = x2, y2
            elif c == 's':
                x1, y1 = (2 * x - px, 2 * y - py) if prev in ('c', 's') else (x, y)
                x2, y2, ex, ey = ox + v[0], oy + v[1], ox + v[2], oy + v[3]
                px, py = x2, y2
            else:   # a quadratic (q, t) as the cubic with the same curve
                qx, qy = (ox + v[0], oy + v[1]) if c == 'q' else ((2 * x - px, 2 * y - py) if prev in ('q', 't') else (x, y))
                ex, ey = (ox + v[2], oy + v[3]) if c == 'q' else (ox + v[0], oy + v[1])
                x1, y1, x2, y2 = x + 2 / 3 * (qx - x), y + 2 / 3 * (qy - y), ex + 2 / 3 * (qx - ex), ey + 2 / 3 * (qy - ey)
                px, py = qx, qy
            cmds, nums, x, y = cmds + 'C', nums + [x1, y1, x2, y2, ex, ey], ex, ey
        prev = c
    return cmds, nums


def path_str(cmds, nums):
    out, k = [], 0
    for c in cmds:
        out.append(c + ' '.join(str(r4(n)) for n in nums[k:k + CMD_ARGS[c]]))
        k += CMD_ARGS[c]
    return ''.join(out)


def polyline(d):
    """A motion path as points (flat x, y) and their cumulative lengths; one contour, each curve cut into 16 pieces."""
    cmds, nums = normalise(d)
    if cmds.count('M') != 1 or cmds[0] != 'M':
        raise ValueError('a motion path must be one contour: ' + d[:40])
    pts, k = nums[:2], 2
    for c in cmds[1:]:
        x0, y0 = pts[-2], pts[-1]
        if c == 'L':
            pts += nums[k:k + 2]
        elif c == 'C':
            x1, y1, x2, y2, x3, y3 = nums[k:k + 6]
            for j in range(1, 17):
                u = j / 16
                a, b, cc, e = (1 - u) ** 3, 3 * (1 - u) ** 2 * u, 3 * (1 - u) * u * u, u ** 3
                pts += [a * x0 + b * x1 + cc * x2 + e * x3, a * y0 + b * y1 + cc * y2 + e * y3]
        else:
            pts += pts[:2]
        k += CMD_ARGS[c]
    lens = [0.0]
    for j in range(2, len(pts), 2):
        lens.append(lens[-1] + math.hypot(pts[j] - pts[j - 2], pts[j + 1] - pts[j - 1]))
    return [r4(p) for p in pts], [r4(n) for n in lens]


def ease_of(el, ref=None):
    """An animator's interpolator (its inline element from extract_apk_vectors.py, or a reference to a standard one) as a cubic
    [x1, y1, x2, y2] (a pathInterpolator's controls, or its path data when that is one cubic from 0,0 to 1,1) or "accdec"."""
    if el is None and ref is None:
        return 'accdec'   # an animator that names none: ValueAnimator's default, which Compose's painter uses too
    if el is None:
        if ref.split('/')[-1] in STD_EASES:
            return STD_EASES[ref.split('/')[-1]]
        raise ValueError(f'interpolator {ref} is not inlined (extract_apk_vectors.py puts it in)')
    if el.tag == 'linearInterpolator':
        return [0, 0, 1, 1]
    if el.tag == 'accelerateDecelerateInterpolator':
        return 'accdec'
    if el.tag != 'pathInterpolator':
        raise ValueError(f'interpolator <{el.tag}> is not a cubic Bezier')
    a = attrs(el)
    if a.get('pathData'):
        cmds, n = normalise(a['pathData'])
        if cmds == 'ML' and n == [0, 0, 1, 1]:
            return [0, 0, 1, 1]
        if cmds != 'MC' or n[:2] != [0, 0] or n[-2:] != [1, 1]:
            raise ValueError(f"interpolator path {a['pathData']!r} is not one cubic from 0,0 to 1,1")
        return [r4(v) for v in n[2:6]]
    k = ['controlX1', 'controlY1', 'controlX2', 'controlY2']
    if not all(x in a for x in k):
        raise ValueError('a pathInterpolator with fewer than two control points is not a cubic Bezier')
    return [r4(float(a[x])) for x in k]


def tracks_of(el, start, out):
    """Every objectAnimator under [el] as a track, a sequential set one after another; returns where [el] ends."""
    if el.tag == 'set':
        mine, t = [], start
        for ch in el:
            end = tracks_of(ch, t, mine)
            if el.get(A + 'ordering') in ('sequentially', '1'):
                t = end
        out += mine
        return max([start] + [o['at'] + o['ms'] for o in mine])
    if el.tag != 'objectAnimator':
        return start
    a = attrs(el)
    if a.get('repeatCount') not in (None, '0'):
        raise ValueError('a repeating animation has no rest to play to')
    if el.findall('propertyValuesHolder'):
        raise ValueError('propertyValuesHolder is not supported')
    at, ms = start + int(float(a.get('startOffset', 0))), int(float(a.get('duration', 300)))
    tr = {'p': a.get('propertyName'), 'at': at, 'ms': ms, 'e': ease_of(inline(el, 'android:interpolator'), el.get(A + 'interpolator'))}
    if a.get('propertyXName') and a.get('pathData'):
        tr.update(p='translateXY', path=a['pathData'])
    elif 'valueFrom' in a and 'valueTo' in a:
        tr.update({'from': a['valueFrom'], 'to': a['valueTo']})
    else:
        raise ValueError(f"an animator of {tr['p']} without valueFrom and valueTo")
    out.append(tr)
    return at + ms


def rests(k, i, tr, groups, layers, clips, colours):
    """What the track starts from, and what its target rests at before it (the base), each as a list."""
    p = tr['p']
    if p == 'pathData':
        return tr['from'], normalise((layers[i] if k == 'l' else clips[i])['d'])[1]
    if p == 'translateXY':
        return tr['pts'][:2], groups[i].get('translate', GROUP_DEFAULTS['translate'])
    if k == 'g':
        g = {key: groups[i].get(key, dflt) for key, dflt in GROUP_DEFAULTS.items()}
        base = {'rotation': g['rotate'], 'scaleX': g['scale'][0], 'scaleY': g['scale'][1], 'translateX': g['translate'][0],
                'translateY': g['translate'][1]}[p]
    else:
        lay, trim = layers[i], layers[i].get('trim', [0, 1, 0])
        base = {'fillAlpha': lay.get('a', 1), 'strokeAlpha': lay.get('sa', 1 if colours[i][1] else 0), 'strokeWidth': lay.get('s', 0),
                'trimPathStart': trim[0], 'trimPathEnd': trim[1], 'trimPathOffset': trim[2]}[p]
    return [tr['from']], [base]


def play_of(data):
    """The animation of an animated vector (the shape is described at the top of this file)."""
    root = ET.fromstring(data)
    vector = inline(root, 'android:drawable')
    if root.tag != 'animated-vector' or vector is None:
        raise ValueError('not an animated vector with its vector inline')
    todo = {}
    for t in root.findall('target'):
        anim = inline(t, 'android:animation')
        if anim is not None:
            tracks_of(anim, 0, todo.setdefault(t.get(A + 'name'), []))
    groups, layers, clips, where, colours = [], [], [], {}, []

    def walk(el, parent, active):
        active = list(active)
        for ch in el:
            a = attrs(ch)
            name = a.get('name')
            if ch.tag == 'group':
                g = {'pivot': [a.get('pivotX', 0.0), a.get('pivotY', 0.0)], 'scale': [a.get('scaleX', 1.0), a.get('scaleY', 1.0)],
                     'rotate': a.get('rotation', 0.0), 'translate': [a.get('translateX', 0.0), a.get('translateY', 0.0)]}
                idx = parent
                if name in todo or any(g[k] != GROUP_DEFAULTS[k] for k in ('scale', 'rotate', 'translate')):
                    idx = len(groups)
                    groups.append({'n': name, 'parent': parent, **{k: [r4(x) for x in v] if isinstance(v, list) else r4(v) for k, v in g.items() if v != GROUP_DEFAULTS[k]}})
                    where[name] = ('g', idx)
                walk(ch, idx, active)
            elif ch.tag == 'clip-path':
                clips.append({'n': name, 'd': a['pathData'].strip(), 'g': parent})
                where[name] = ('c', len(clips) - 1)
                active.append(len(clips) - 1)
            elif ch.tag == 'path' and (a.get('pathData') or '').strip():
                fc, sc = colour_alpha(a.get('fillColor')), colour_alpha(a.get('strokeColor'))
                lay = {'n': name, 'g': parent, 'd': a['pathData'].strip()}
                trim = [r4(a.get('trimPathStart', 0.0)), r4(a.get('trimPathEnd', 1.0)), r4(a.get('trimPathOffset', 0.0))]
                for key, val, dflt in (('a', r4(fc * a.get('fillAlpha', 1.0)), 1), ('s', r4(a.get('strokeWidth', 0.0)), 0),
                                       ('sa', r4(sc * a.get('strokeAlpha', 1.0)) if sc else None, 1), ('trim', trim, [0, 1, 0]),
                                       ('cap', enum(a.get('strokeLineCap'), ('butt', 'round', 'square')), 'butt'),
                                       ('join', enum(a.get('strokeLineJoin'), ('miter', 'round', 'bevel')), 'miter'),
                                       ('even', int(enum(a.get('fillType'), ('nonZero', 'evenOdd')) == 'evenOdd'), 0),
                                       ('clip', active[:], [])):
                    if val not in (dflt, None):
                        lay[key] = val
                layers.append(lay)
                colours.append((fc, sc))
                where[name] = ('l', len(layers) - 1)

    walk(vector, -1, [])
    tracks = []
    for name, found in todo.items():
        if name not in where:
            raise ValueError(f'an animator targets {name!r}, which the vector does not have')
        k, i = where[name]
        for tr in found:
            p = tr['p']
            if p not in {'g': GROUP_PROPS, 'l': PATH_PROPS, 'c': {'pathData'}}[k]:
                raise ValueError(f'{name!r}: the property {p!r} cannot be played on it')
            if p == 'translateXY':
                tr['pts'], tr['len'] = polyline(tr.pop('path'))
            elif p == 'pathData':
                owner = layers[i] if k == 'l' else clips[i]
                cmds, nums = normalise(owner['d'])
                (c1, n1), (c2, n2) = normalise(tr['from']), normalise(tr['to'])
                if not cmds == c1 == c2:
                    raise ValueError(f'{name!r}: its paths cannot morph (commands {cmds} / {c1} / {c2})')
                tr['from'], tr['to'] = [r4(v) for v in n1], [r4(v) for v in n2]
                owner['d'] = path_str(cmds, nums)
            else:
                f = colours[i][0] if p == 'fillAlpha' else colours[i][1] if p == 'strokeAlpha' else 1.0
                tr['from'], tr['to'] = r4(float(tr['from']) * f), r4(float(tr['to']) * f)
            if tr['at'] > 0 and tr['at'] == min(t['at'] for t in found if t['p'] == p):   # a property's first track, delayed
                want, got = rests(k, i, tr, groups, layers, clips, colours)
                if any(abs(x - y) > 1e-3 for x, y in zip(want, got)):
                    raise ValueError(f"{name!r}: {p} starts at {tr['at']} ms from {want} but rests at {got} (Android shows the "
                                     "track's from until it starts, the play its base: they must be one)")
            tracks.append({'k': k, 'i': i, **tr})
    tracks.sort(key=lambda t: t['at'])
    return {'ms': max([t['at'] + t['ms'] for t in tracks] or [0]), 'groups': groups, 'layers': layers,
            **({'clips': clips} if clips else {}), 'tracks': tracks}


# ------------------------------------------------------------------------ the reference evaluator (what the Kotlin player must equal)

def ease(e, f):
    if e == 'accdec':
        return math.cos((f + 1) * math.pi) / 2 + 0.5
    bez = lambda u, a, b: 3 * (1 - u) ** 2 * u * a + 3 * (1 - u) * u * u * b + u ** 3   # noqa: E731
    lo, hi = 0.0, 1.0
    for _ in range(60):
        mid = (lo + hi) / 2
        lo, hi = (mid, hi) if bez(mid, e[0], e[2]) < f else (lo, mid)
    return bez((lo + hi) / 2, e[1], e[3])


def along(tr, y):
    """A motion path's point at fraction [y] of its length (past either end the last / first piece goes on)."""
    pts, lens = tr['pts'], tr['len']
    if len(lens) < 2 or lens[-1] == 0:
        return pts[0], pts[1]
    want, j = y * lens[-1], 1
    while j < len(lens) - 1 and lens[j] < want:
        j += 1
    u = (want - lens[j - 1]) / ((lens[j] - lens[j - 1]) or 1.0)
    return pts[2 * j - 2] + (pts[2 * j] - pts[2 * j - 2]) * u, pts[2 * j - 1] + (pts[2 * j + 1] - pts[2 * j - 1]) * u


def tracked(play, t):
    """{(k, i): {property: value}} of every track that has begun at [t]; the last to write a property wins."""
    st = {}
    for tr in play['tracks']:
        if t < tr['at']:
            continue
        y = ease(tr['e'], 1.0 if tr['ms'] <= 0 else min(1.0, (t - tr['at']) / tr['ms']))
        d = st.setdefault((tr['k'], tr['i']), {})
        if tr['p'] == 'pathData':
            d['pathData'] = [a + (b - a) * y for a, b in zip(tr['from'], tr['to'])]
        elif tr['p'] == 'translateXY':
            d['translateX'], d['translateY'] = along(tr, y)
        else:
            d[tr['p']] = tr['from'] + (tr['to'] - tr['from']) * y
    return st


def evaluate(play, t):
    """The play at [t] ms, per layer: its world matrix `m`, its path numbers `p` (None: as `d`), `a`, `sa` and `s` (alphas
    clamped to 0..1, width to 0 and up, as drawn), `trim`, and its clips (`m`, `p`, `d`)."""
    st, mats = tracked(play, t), []
    for i, g in enumerate(play['groups']):
        v, (px, py), (sx, sy), (tx, ty) = st.get(('g', i), {}), g.get('pivot', [0, 0]), g.get('scale', [1, 1]), g.get('translate', [0, 0])
        m = group_matrix({'pivotX': px, 'pivotY': py, 'scaleX': v.get('scaleX', sx), 'scaleY': v.get('scaleY', sy),
                          'rotation': v.get('rotation', g.get('rotate', 0)), 'translateX': v.get('translateX', tx), 'translateY': v.get('translateY', ty)})
        mats.append(mul(mats[g['parent']], m) if g['parent'] >= 0 else m)
    world = lambda gi: mats[gi] if gi >= 0 else IDENTITY   # noqa: E731
    out = []
    for i, lay in enumerate(play['layers']):
        v, tm = st.get(('l', i), {}), lay.get('trim', [0, 1, 0])
        out.append({'n': lay.get('n'), 'm': world(lay['g']), 'p': v.get('pathData'), 'd': lay['d'],
                    'a': min(1.0, max(0.0, v.get('fillAlpha', lay.get('a', 1)))), 'sa': min(1.0, max(0.0, v.get('strokeAlpha', lay.get('sa', 1)))),
                    's': max(0.0, v.get('strokeWidth', lay.get('s', 0))),
                    'trim': [v.get('trimPathStart', tm[0]), v.get('trimPathEnd', tm[1]), v.get('trimPathOffset', tm[2])],
                    'clip': [{'m': world(play['clips'][c]['g']), 'p': st.get(('c', c), {}).get('pathData'), 'd': play['clips'][c]['d']}
                             for c in lay.get('clip', [])]})
    return out


def shown(l):
    """Does an evaluated layer draw anything (the static layers leave out what does not)?"""
    return (l['a'] > 0.004 or (l['s'] > 0 and l['sa'] > 0.004)) and abs(l['trim'][1] - l['trim'][0]) >= 0.001


def points(m, p, d):
    """A path's points in the symbol's box: its numbers [p] (evaluated) or its data [d], through matrix [m]."""
    try:
        n = p if p is not None else normalise(d)[1]
    except ValueError:   # a path with arcs: the corners of its 64 box, so that its matrix is still compared
        n = [0, 0, 64, 0, 0, 64, 64, 64]
    return [(m[0] * n[k] + m[1] * n[k + 1] + m[2], m[3] * n[k] + m[4] * n[k + 1] + m[5]) for k in range(0, len(n) - 1, 2)]


def gap(a, b):
    """How far two evaluated layers are: (largest point distance, alpha, stroke width), None if their shapes differ."""
    pa, pb = points(a['m'], a['p'], a['d']), points(b['m'], b['p'], b['d'])
    for ca, cb in zip(a['clip'], b['clip']):
        pa += points(ca['m'], ca['p'], ca['d'])
        pb += points(cb['m'], cb['p'], cb['d'])
    if len(pa) != len(pb) or len(a['clip']) != len(b['clip']):
        return None
    scale = lambda m: math.sqrt(abs(m[0] * m[4] - m[1] * m[3]))   # noqa: E731
    return [max([math.hypot(x[0] - y[0], x[1] - y[1]) for x, y in zip(pa, pb)] or [0.0]),
            max(abs(a['a'] - b['a']), abs(a['sa'] - b['sa'])), abs(a['s'] * scale(a['m']) - b['s'] * scale(b['m']))]


def continuity(end_of, start_of):
    """What differs between the look [end_of] ends on and the one [start_of] begins at, paths matched by name: the largest
    point distance (in the symbol's box), alpha and stroke width, and how many shown paths only one of them has."""
    a, b = [l for l in evaluate(end_of, end_of['ms']) if shown(l)], [l for l in evaluate(start_of, 0) if shown(l)]
    res, only = [0.0, 0.0, 0.0], 0
    for l in a:
        m = next((x for x in b if x['n'] == l['n']), None)
        g = gap(l, m) if m else None
        only += g is None
        res = [max(x, y) for x, y in zip(res, g or res)]
    return res + [only + len([x for x in b if not any(x['n'] == l['n'] for l in a)])]


def matches_static(play, layers):
    """Does the play's last frame draw the static layers (the same shown paths, in order)? '' if so, else what differs."""
    last = [l for l in evaluate(play, play['ms']) if shown(l)]
    if len(last) != len(layers):
        return f'{len(last)} paths shown at the end, {len(layers)} static'
    for l, s in zip(last, layers):
        if any(abs(x - y) > 1e-3 for x, y in zip(l['m'], s.get('m', IDENTITY))):
            return f"{l['n']}: matrix"
        if l['p'] is None and l['d'] != s['d'] or l['p'] is not None and \
                any(abs(x - y) > 1e-3 for x, y in zip(l['p'], normalise(s['d'])[1])):
            return f"{l['n']}: path"
        stroke = lambda w, a: a if w > 0 else 0   # noqa: E731 (a static layer has an alpha only for a stroke it draws)
        if abs(l['a'] - s.get('a', 1)) > 2e-3 or abs(stroke(l['s'], l['sa']) - stroke(s.get('s', 0), s.get('sa', 1))) > 2e-3 or abs(l['s'] - s.get('s', 0)) > 2e-3 or \
                any(abs(x - y) > 1e-3 for x, y in zip(l['trim'], s.get('trim', [0, 1, 0]))) or len(l['clip']) != len(s.get('clip', [])):
            return f"{l['n']}: paint, trim or clip"
    return ''


def symbol(cache, src):
    kind, path, frame = src[:3]
    data = fetch(cache, kind, path)
    box, layers = from_svg(data) if path.endswith('.svg') else from_vector(data, frame, cache, kind)
    if not layers:
        raise ValueError('nothing drawn')
    where = ('AOSP frameworks/base ' + path) if kind == 'aosp' else         (f"Android 17 SystemUIGoogle res/drawable/{path}.xml (tools/icons/extract_apk_vectors.py)" if kind == 'apk' else path)
    what = {'end': ' (its last frame)', 'start': ' (its first frame)', 'static': ''}[frame]
    out = {'src': where + what, 'box': [round(x, 3) for x in box], 'layers': layers}
    if len(src) > 3:
        out['scale'] = src[3]
    if kind == 'apk' and frame == 'end':
        try:
            out['play'] = play_of(fetch(cache, kind, path))
        except Exception as e:  # noqa: BLE001
            raise PlayError(f'{path}: {e}') from e
    return out


def rr(o):
    """[o] with every number rounded to 5 places (the expected values of --play)."""
    if isinstance(o, float):
        return round(o, 5) + 0.0
    if isinstance(o, (list, tuple)):
        return [rr(x) for x in o]
    return {k: rr(v) for k, v in o.items()} if isinstance(o, dict) else o


def report(icons):
    """Per animated symbol: does its last frame draw its static look; per on / off pair: the gaps where one's end meets the
    other's start. Returns how many symbols' last frames differ."""
    bad = 0
    for key, ic in sorted(icons.items()):
        if 'play' in ic:
            why = matches_static(ic['play'], ic['layers'])
            bad += bool(why)
            print(f"  {key}: {ic['play']['ms']} ms, {len(ic['play']['tracks'])} tracks; " + (f'LAST FRAME IS NOT THE STATIC LOOK ({why})' if why else 'last frame = static look'))
    pairs = [(n, n + '.off') for n in icons if n + '.off' in icons] + [p for p in PAIRS if p[0] in icons and p[1] in icons]
    for on, off in sorted(pairs):
        if 'play' in icons[on] and 'play' in icons[off]:
            a, b = continuity(icons[off]['play'], icons[on]['play']), continuity(icons[on]['play'], icons[off]['play'])
            f = lambda c: f'{c[0]:.3f} box units, alpha {c[1]:.3f}, width {c[2]:.3f}, {c[3]} unmatched'   # noqa: E731
            print(f'  {on} / {off}: {off} end -> {on} start: {f(a)};  {on} end -> {off} start: {f(b)}')
    return bad


def main():
    if len(sys.argv) >= 3 and sys.argv[1] == '--play':
        play = play_of(open(sys.argv[2], 'rb').read())
        times = [float(x) for x in sys.argv[3].split(',')] if len(sys.argv) > 3 else []
        json.dump({'play': play, 'expected': [{'t': t, 'layers': rr(evaluate(play, t))} for t in times]}, sys.stdout, indent=1)
        print()
        return
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    cache = sys.argv[1]
    os.makedirs(OUT, exist_ok=True)
    for sid, spec in SETS.items():
        icons = {}
        failed, play_failed = [], []
        for name, states in spec['symbols'].items():
            for state, src in states.items():
                key = name if state == 'on' else name + '.off'
                try:
                    icons[key] = symbol(cache, src)
                except PlayError as e:
                    play_failed.append(f'{key}: {e}')
                except Exception as e:  # noqa: BLE001 (a symbol that cannot be read is left to the app's own)
                    failed.append(f'{key}: {e}')
        bad = report(icons)
        if play_failed or bad:
            raise SystemExit('animations that cannot be exported: ' + '; '.join(play_failed) + (f'; {bad} last frames differ' if bad else ''))
        out = {'format': 2, 'name': spec['name'], 'description': spec['description'], 'license': spec['license'],
               '_about': 'Written by tools/icons/build_icon_set.py; each symbol keeps its source. A name left out is drawn '
                         "with the app's own symbol; NAME.off is the look of a control that is off. A symbol with 'play' "
                         "carries its animation (format 2: see the top of the script); its layers are the look it rests on.",
               'icons': dict(sorted(icons.items()))}
        path = os.path.join(OUT, sid + '.json')
        # (each play on one line: indented, its numbers one to a line would be most of the file)
        plays = {f'@@{k}@@': json.dumps(v.pop('play'), separators=(',', ':')) for k, v in out['icons'].items() if 'play' in v}
        for tok in plays:
            out['icons'][tok[2:-2]]['play'] = tok
        text = json.dumps(out, indent=1, ensure_ascii=False)
        for tok, js in plays.items():
            text = text.replace(json.dumps(tok), js)
        with open(path, 'w', encoding='utf-8', newline='\n') as f:
            f.write(text + '\n')
        print(f'{path}: {len(icons)} symbols' + (f'; not read: {"; ".join(failed)}' if failed else ''))


if __name__ == '__main__':
    main()
