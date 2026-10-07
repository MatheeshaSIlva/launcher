#!/usr/bin/env python3
"""
Builds the iOS 27 theme (app/src/main/assets/themes/ios27.json) from Apple's kit as exported in docs/tokens/ios27-kit.json.

- The `ref.*` tier is generated from the kit every time (colours with light and dark, type styles, dimensions, the Liquid
  Glass globals, every material's recipe), each token with its kit id as `src`.
- The `sys.*` and `comp.*` tiers are written by hand in the theme file; this script keeps them as they are, and only adds
  the seed values below when a key is missing (a fresh file).

    python tools/design/build_ios27_theme.py
"""
import json
import os
import re

ROOT = os.path.join(os.path.dirname(__file__), '..', '..')
KIT = os.path.join(ROOT, 'docs', 'tokens', 'ios27-kit.json')
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'themes', 'ios27.json')


def slug(name):
    """'Backgrounds (Grouped)/Primary - Elevated' -> 'backgrounds-grouped.primary-elevated'."""
    parts = []
    for p in name.split('/'):
        p = p.lower().replace('(', '').replace(')', '').replace('+', ' ')
        p = re.sub(r'[^a-z0-9]+', '-', p).strip('-')
        parts.append(p)
    return '.'.join(parts)


WEIGHTS = {'Regular': 400, 'Medium': 510, 'Semibold': 590, 'Bold': 700}


def font(s):
    # "SF Pro Semibold Italic" -> weight 590 (italics are drawn upright: no italic face yet)
    for w, v in WEIGHTS.items():
        if w in s:
            return v
    return 400


def fill(s):
    # "#ffffff@0.7 LIGHTEN" -> ("#ffffff", 0.7, "LIGHTEN")
    m = re.match(r'(#[0-9a-f]+)@([\d.]+) (\w+)', s)
    return m.group(1), float(m.group(2)), m.group(3)


def shadow(s):
    # "#00000040 0,8 r48 NORMAL" / "#dbdbdb 0,0 spread0.5 LINEAR_BURN" / "#e6e6e6 0,40 r30 spread-40 LINEAR_BURN"
    toks = s.split()
    color = toks[0]
    dx, dy = (float(x) for x in toks[1].split(','))
    blur = spread = 0.0
    blend = 'NORMAL'
    for t in toks[2:]:
        if t.startswith('r') and re.match(r'r-?[\d.]+$', t):
            blur = float(t[1:])
        elif t.startswith('spread'):
            spread = float(t[6:])
        else:
            blend = t
    return [color, dx, dy, blur, spread, blend]


def union_fills(light, dark):
    """One fill list holding both modes: the light layers (zero in dark), then the dark layers (zero in light)."""
    out = [[c, [o, 0.0], b] for c, o, b in (fill(f) for f in light)]
    out += [[c, [0.0, o], b] for c, o, b in (fill(f) for f in dark)]
    return out


def pair_shadows(light, dark):
    """Shadows that differ in colour only between the modes (the kit's case), as '#light|#dark'."""
    if len(light) != len(dark):
        raise ValueError('shadow lists differ between modes')
    out = []
    for a, b in zip(light, dark):
        sa, sb = shadow(a), shadow(b)
        if sa[1:] != sb[1:]:
            raise ValueError(f'shadows differ beyond colour: {a} / {b}')
        sa[0] = sa[0] if sa[0] == sb[0] else sa[0] + '|' + sb[0]
        out.append(sa)
    return out


def lens(g):
    return {'refraction': g['refraction'], 'depth': g['depth'], 'dispersion': g['dispersion'], 'splay': g['splay'],
            'light': g['light'], 'lightAngle': g['lightAngle']}


def build_ref(kit):
    ref = {}
    for name, c in kit['colors'].items():
        if name.startswith('_'):
            continue
        key = 'ref.color.' + slug(name)
        if isinstance(c['light'], str) and c['light'].startswith('alias '):
            ref[key] = {'ref': 'ref.color.' + slug(c['light'][6:]), 'src': 'kit:' + c['id'], 'note': 'dark ' + c['dark'] + ' (alias in light)'}
            continue
        ref[key] = {'light': c['light'], 'dark': c['dark'], 'src': 'kit:' + c['id']}
    for name, g in kit['liquidGlassGlobals'].items():
        if name.startswith('_'):
            continue
        key = 'ref.glass.' + slug(name).replace('liquid-glass.', '') if name.startswith('Liquid') else 'ref.scroll-edge.' + slug(name).split('.', 1)[1]
        ref[key] = {'pt' if 'Frost' in name or 'Depth' in name or 'Top' in name or 'Bottom' in name or 'Radius' in name
                                          else ('deg' if 'Angle' in name else 'percent'): g['value'], 'src': 'kit:' + g['id']}
    for name, d in kit['dimensions'].items():
        if name.startswith('_') or not isinstance(d['iPhone'], (int, float)):
            continue
        ref['ref.dimen.' + slug(name)] = {'pt': d['iPhone'], 'src': 'kit:' + d['id']}
    for name, t in kit['textStyles'].items():
        if name.startswith('_'):
            continue
        family, size, line, tracking = t
        ref['ref.type.' + slug(name)] = {'text': {'family': 'display' if size >= 20 else 'text', 'weight': font(family), 'size': size,
                                                  'line': line, 'tracking': tracking}, 'src': 'kit:text style ' + name}
    mats = kit['materials']
    for style in ['Ultrathin', 'Thin', 'Regular', 'Thick', 'Chrome']:
        l, d = mats['Classic/Light/' + style], mats['Classic/Dark/' + style]
        ref['ref.material.classic.' + style.lower()] = {'material': {
            'frost': l['backgroundBlur'], 'frostDark': d['backgroundBlur'], 'fills': union_fills(l['fills'], d['fills']),
            'innerShadows': [], 'shadows': []}, 'src': 'kit:' + l['node'] + ' / ' + d['node']}
    clear = mats['LiquidGlass/Clear']
    ref['ref.material.liquid-glass.clear'] = {'material': {
        'frost': clear['glass']['frost'], 'lens': lens(clear['glass']), 'fills': [[c, o, b] for c, o, b in (fill(f) for f in clear['fills'])],
        'innerShadows': [shadow(s) for s in clear['innerShadows']], 'shadows': [shadow(s) for s in clear['shadows']]}, 'src': 'kit:' + clear['node']}
    rl, rd = mats['LiquidGlass/Regular/Medium+Large/Light'], mats['LiquidGlass/Regular/Medium+Large/Dark']
    ref['ref.material.liquid-glass.regular'] = {'material': {
        'frost': rl['glass']['frost'], 'lens': lens(rl['glass']), 'fills': union_fills(rl['fills'], rd['fills']),
        'innerShadows': pair_shadows(rl['innerShadows'], rd['innerShadows']), 'shadows': pair_shadows(rl['shadows'], rd['shadows'])},
        'src': 'kit:' + rl['node'] + ' / ' + rd['node'], 'note': 'the glass layer multiplies white (no change); not drawn'}
    sl, sd = mats['LiquidGlass/Regular/Small/Light/Inactive'], mats['LiquidGlass/Regular/Small/Dark/Inactive']
    ref['ref.material.liquid-glass.small'] = {'material': {
        'frost': 0, 'fills': union_fills(sl['fills'], sd['fills']), 'innerShadows': [],
        'shadows': pair_shadows(sl['shadows'], sd['shadows'])}, 'src': 'kit:' + sl['node'] + ' / ' + sd['node']}
    al, ad = mats['LiquidGlass/Regular/Small/Light/Active'], mats['LiquidGlass/Regular/Small/Dark/Active']
    ref['ref.material.liquid-glass.small-active'] = {'material': {
        'frost': al['glass']['frost'], 'lens': lens(al['glass']), 'fills': union_fills(al['fills'], ad['fills']),
        'innerShadows': pair_shadows(al['innerShadows'], ad['innerShadows']), 'shadows': pair_shadows(al['shadows'], ad['shadows'])},
        'src': 'kit:' + al['node'] + ' / ' + ad['node']}
    ov = kit['notifications']['Overlay']
    ref['ref.material.overlay.lock-screen'] = {'material': {
        'frost': 0, 'fills': [[c, o, b] for c, o, b in (fill(f) for f in ov['fills'])], 'innerShadows': [], 'shadows': []},
        'src': 'kit:' + ov['node']}
    cc = kit['controlCenter']
    ref['ref.material.overlay.control-center'] = {'material': {
        'frost': cc['Overlay']['backgroundBlur'], 'fills': [[c, o, b] for c, o, b in (fill(f) for f in cc['Overlay']['fills'])],
        'innerShadows': [], 'shadows': []}, 'src': 'kit:' + cc['Overlay']['node']}
    ref['ref.material.control-center.on'] = {'material': {
        'frost': 0, 'fills': [[c, o, b] for c, o, b in (fill(f) for f in cc['On']['fills'])], 'innerShadows': [], 'shadows': []},
        'src': 'kit:10486:20857'}
    ref['ref.material.control-center.well'] = {'material': {
        'frost': 0, 'fills': [[c, o, b] for c, o, b in (fill(f) for f in cc['Well']['fills'])], 'innerShadows': [], 'shadows': []},
        'src': 'kit:2570:20607'}
    ref['ref.material.control-center.button'] = {'material': {
        'frost': 0, 'fills': [['#121212', 1.0, 'LINEAR_DODGE']], 'innerShadows': [], 'shadows': []}, 'src': 'kit:10491:21418'}
    return ref


# Seeds for the hand-written tiers (only used for keys missing from the theme file).
SEED = {
    'sys.scale.policy': {'choice': 'reference-width', 'src': 'judged:the screen is as many points wide as iOS 27\'s iPhone, so proportions and, on the S24, physical sizes match it'},
    'sys.scale.reference-width': {'pt': 402, 'src': 'kit:the kit\'s iPhone 17 Pro frame, 402 x 874'},
    'sys.color.label.primary': {'ref': 'ref.color.labels.primary', 'src': 'kit:VariableID:507:29167'},
    'sys.color.label.secondary': {'ref': 'ref.color.labels.secondary', 'src': 'kit:VariableID:507:29162'},
    'sys.color.label.tertiary': {'ref': 'ref.color.labels.tertiary', 'src': 'kit:VariableID:508:77449'},
    'sys.color.label.quaternary': {'ref': 'ref.color.labels.quaternary', 'src': 'kit:VariableID:508:77450'},
    'sys.color.label.vibrant.primary': {'ref': 'ref.color.labels-vibrant.primary', 'src': 'kit:VariableID:5500:4615'},
    'sys.color.label.vibrant.secondary': {'ref': 'ref.color.labels-vibrant.secondary', 'src': 'kit:VariableID:5500:4616'},
    'sys.color.fill.primary': {'ref': 'ref.color.fills.primary', 'src': 'kit:VariableID:508:77441'},
    'sys.color.fill.secondary': {'ref': 'ref.color.fills.secondary', 'src': 'kit:VariableID:509:77491'},
    'sys.color.fill.tertiary': {'ref': 'ref.color.fills.tertiary', 'src': 'kit:VariableID:509:77493'},
    'sys.color.separator': {'ref': 'ref.color.separators.non-opaque', 'src': 'kit:VariableID:509:77495'},
    'sys.color.overlay': {'ref': 'ref.color.overlays.default', 'src': 'kit:VariableID:513:90698'},
    'sys.color.accent': {'ref': 'ref.color.accents.blue', 'src': 'kit:VariableID:507:29166'},
    'sys.color.destructive': {'ref': 'ref.color.accents.red', 'src': 'kit:VariableID:509:77471'},
    'sys.material.glass.clear': {'ref': 'ref.material.liquid-glass.clear', 'src': 'kit:5914:23547'},
    'sys.material.glass.regular': {'ref': 'ref.material.liquid-glass.regular', 'src': 'kit:5584:31857'},
    'sys.material.glass.small': {'ref': 'ref.material.liquid-glass.small', 'src': 'kit:10472:45029'},
    'sys.material.glass.small-active': {'ref': 'ref.material.liquid-glass.small-active', 'src': 'kit:5564:42811'},
    'sys.material.thin': {'ref': 'ref.material.classic.thin', 'src': 'kit:510:78331'},
    'sys.material.regular': {'ref': 'ref.material.classic.regular', 'src': 'kit:510:78333'},
    'sys.material.thick': {'ref': 'ref.material.classic.thick', 'src': 'kit:510:78335'},
    'sys.type.large-title': {'ref': 'ref.type.large-title.emphasized', 'src': 'kit:text style Large Title/Emphasized'},
    'sys.type.title2': {'ref': 'ref.type.title2.emphasized', 'src': 'kit:text style Title2/Emphasized'},
    'sys.type.headline': {'ref': 'ref.type.headline.regular', 'src': 'kit:text style Headline/Regular'},
    'sys.type.body': {'ref': 'ref.type.body.regular', 'src': 'kit:text style Body/Regular'},
    'sys.type.callout': {'ref': 'ref.type.callout.regular', 'src': 'kit:text style Callout/Regular'},
    'sys.type.subheadline': {'ref': 'ref.type.subheadline.regular', 'src': 'kit:text style Subheadline/Regular'},
    'sys.type.subheadline-emphasized': {'ref': 'ref.type.subheadline.emphasized', 'src': 'kit:text style Subheadline/Emphasized'},
    'sys.type.footnote': {'ref': 'ref.type.footnote.regular', 'src': 'kit:text style Footnote/Regular'},
    'sys.type.caption1': {'ref': 'ref.type.caption1.regular', 'src': 'kit:text style Caption1/Regular'},
    'sys.spacing.margin': {'ref': 'ref.dimen.margin', 'src': 'kit:VariableID:10442:65'},
    # Notification Center (docs/tokens/ios27-kit.json, "notifications" and "lockScreen")
    'comp.nc.platter.material': {'ref': 'sys.material.glass.clear', 'src': 'kit:0:11292'},
    'comp.nc.platter.corner': {'pt': 24, 'src': 'kit:0:11292'},
    'comp.nc.platter.padding': {'pt': 14, 'src': 'kit:0:11292'},
    'comp.nc.platter.icon': {'pt': 38.33, 'src': 'kit:0:11292'},
    'comp.nc.platter.text-x': {'pt': 62.33, 'src': 'kit:0:11292'},
    'comp.nc.platter.text-top': {'pt': 15.67, 'src': 'kit:0:11292'},
    'comp.nc.platter.min-height': {'pt': 66.33, 'src': 'kit:0:11292'},
    'comp.nc.platter.title': {'text': {'family': 'text', 'weight': 590, 'size': 15, 'line': 17, 'tracking': -0.23}, 'src': 'kit:0:11292'},
    'comp.nc.platter.body': {'text': {'family': 'text', 'weight': 400, 'size': 15, 'line': 18, 'tracking': -0.23}, 'src': 'kit:0:11292'},
    'comp.nc.platter.time': {'text': {'family': 'text', 'weight': 400, 'size': 15, 'line': 17, 'tracking': 0}, 'src': 'kit:0:11292'},
    'comp.nc.platter.label': {'color': '#ffffff', 'src': 'kit:0:11292'},
    'comp.nc.platter.time-color': {'color': '#4d4d4d', 'src': 'kit:533:7108'},
    'comp.nc.platter.time-blend': {'choice': 'LINEAR_DODGE', 'src': 'kit:533:7108'},
    'comp.nc.list.margin': {'pt': 14, 'src': 'kit:135:73247'},
    'comp.nc.list.gap': {'pt': 8, 'src': 'kit:135:73247'},
    'comp.nc.stack.shelf-height': {'pt': 64, 'src': 'kit:533:5978'},
    'comp.nc.stack.shelf-show': {'pt': 8, 'src': 'kit:533:5978'},
    'comp.nc.stack.inset1': {'pt': 10, 'src': 'kit:533:5978'},
    'comp.nc.stack.inset2': {'pt': 20, 'src': 'kit:5549:4949'},
    'comp.nc.stack.front-text-top': {'pt': 12, 'src': 'kit:533:5978 (the front of Stack=2 and 3: text and icon 12 pt from its top)'},
    'comp.nc.stack.front-min-height': {'pt': 63, 'src': 'kit:533:5978'},
    'comp.nc.overlay.material': {'ref': 'ref.material.overlay.lock-screen', 'src': 'kit:5551:17757'},
    'comp.nc.peek.show': {'pt': 32, 'src': 'measured:iOS 27 Simulator, pass 6 test10 and pass 7 test40 (collapsed stack)'},
    'comp.nc.peek.height': {'pt': 40, 'src': 'judged:its hidden top part (only 32 pt show)'},
    'comp.nc.peek.inset': {'pt': 11, 'src': 'measured:iOS 27 Simulator, pass 6 test10'},
    'comp.nc.peek.more-inset': {'pt': 22, 'src': 'measured:iOS 27 Simulator, pass 6 test10'},
    'comp.nc.peek.corner': {'pt': 18, 'src': "judged:between the platter's 24 and the pill's height"},
    'comp.nc.peek.icon': {'pt': 20, 'src': 'judged:the icon of the one-line peek'},
    'comp.nc.button.material': {'ref': 'sys.material.glass.clear', 'src': 'kit:5592:28123'},
    'comp.nc.button.size': {'pt': 58, 'src': 'kit:5592:28123'},
    'comp.nc.button.inset-x': {'pt': 46, 'src': 'kit:5592:29063'},
    'comp.nc.button.bottom': {'pt': 50, 'src': 'kit:5592:29063 (874 - 766 - 58)'},
    'comp.nc.button.symbol': {'pt': 38.5, 'src': "measured:the kit's lock screen rendered at 3x (SF Pro Bold 21 symbols: the flashlight 31.3 pt tall, the camera 32.7 pt wide); our symbols fill 5/6 of their box"},
    'comp.nc.button.symbol-color': {'color': '#d9d9d9e6', 'src': 'kit:5592:28123 (#d9d9d9 linear dodge at 90 %)'},
    'comp.nc.button.symbol-blend': {'choice': 'LINEAR_DODGE', 'src': 'kit:5592:28123'},
    'comp.nc.button.on-color': {'color': '#ffffff', 'src': 'judged:the flashlight on: a white disc (as iOS\'s)'},
    'comp.nc.look.card-color': {'light': '#ffffff', 'dark': '#1c1c1e', 'src': 'kit:143:62847 (light; dark judged: Backgrounds/Primary - Elevated)'},
    'comp.nc.look.card-corner': {'pt': 26, 'src': 'kit:143:62847'},
    'comp.nc.look.inset': {'pt': 16, 'src': 'kit:143:62847'},
    'comp.nc.look.label': {'ref': 'sys.color.label.primary', 'src': 'kit:143:62847'},
    'comp.nc.look.menu-material': {'ref': 'sys.material.glass.clear', 'src': 'kit:143:62847'},
    'comp.nc.look.menu-width': {'pt': 250, 'src': 'kit:143:62847'},
    'comp.nc.look.menu-corner': {'pt': 26, 'src': 'kit:143:62847'},
    'comp.nc.look.menu-gap': {'pt': 10, 'src': 'kit:143:62847'},
    'comp.nc.look.menu-pad-x': {'pt': 26, 'src': 'kit:143:62847'},
    'comp.nc.look.menu-pad-y': {'pt': 20, 'src': 'kit:143:62847'},
    'comp.nc.look.menu-row': {'pt': 22, 'src': 'kit:143:62847'},
    'comp.nc.look.menu-row-gap': {'pt': 20, 'src': 'kit:143:62847'},
    'comp.nc.look.menu-symbol': {'pt': 20, 'src': 'kit:143:62847'},
    'comp.nc.look.menu-symbol-gap': {'pt': 14, 'src': 'kit:143:62847'},
    'comp.nc.look.menu-label': {'text': {'family': 'text', 'weight': 400, 'size': 17, 'line': 22, 'tracking': -0.43}, 'src': 'kit:143:62847'},
    'comp.nc.look.menu-label-color': {'color': '#ffffff', 'src': 'kit:143:62847'},
    'comp.nc.look.dim': {'color': '#00000066', 'src': 'judged:the rest of Notification Center dims behind a long look (the kit does not show it)'},
    'comp.nc.look.blur': {'pt': 25, 'src': 'judged:the list behind the long look is out of focus (the kit does not show it); a blur radius as the kit gives them (sigma 12.5 pt)'},
    # Control Center (docs/tokens/ios27-kit.json "controlCenter"; geometry of the running system where it differs: docs/IOS27_KIT.md)
    'comp.cc.background.material': {'ref': 'ref.material.overlay.control-center', 'src': 'kit:2524:24518'},
    'comp.cc.background.samsung-strength': {'factor': 0.15, 'src': "judged:One UI's dim-to-blur strength (blur and dim come together there; 0.35 and 0.6 were too strong on the S24)"},
    'comp.cc.module.material': {'ref': 'sys.material.glass.clear', 'src': 'kit:10486:21122'},
    'comp.cc.module.on-material': {'ref': 'ref.material.control-center.on', 'src': 'kit:10486:20857'},
    'comp.cc.well.material': {'ref': 'ref.material.control-center.well', 'src': 'kit:2570:20607'},
    'comp.cc.button.material': {'ref': 'ref.material.control-center.button', 'src': 'kit:10491:21418'},
    'comp.cc.button.size': {'pt': 28.67, 'src': 'kit:10491:21418'},
    'comp.cc.button.inset-x': {'pt': 38, 'src': 'kit:10491:21418'},
    'comp.cc.button.top': {'pt': 23, 'src': 'kit:10491:21421'},
    'comp.cc.button.symbol': {'pt': 18, 'src': "measured:the kit's render at 3x (plus 12, power 15.7 pt); our symbols fill 5/6 of their box"},
    'comp.cc.button.symbol-color': {'color': '#f5f5f5', 'src': 'kit:10491:21415'},
    'comp.cc.grid.cell': {'pt': 70, 'src': 'kit:2524:24582'},
    'comp.cc.grid.gap': {'pt': 15.333, 'src': 'measured:iOS 27 Simulator, accessibility frames (85.33 pt pitch; the kit has 15 / 17)'},
    'comp.cc.grid.top': {'pt': 132.3, 'src': 'measured:iOS 27 Simulator, accessibility frames (the kit: 132)'},
    'comp.cc.grid.top-edit': {'pt': 86, 'src': 'measured:iOS 27 Simulator, edit mode'},
    'comp.cc.status.row-y': {'pt': 104.2, 'src': 'measured:iOS 27 Simulator, accessibility frames (the kit: 91.7)'},
    'comp.cc.module.corner': {'pt': 30, 'src': 'kit:2547:2662 (2x2)'},
    'comp.cc.slider.corner': {'pt': 34, 'src': 'kit:2570:20682'},
    'comp.cc.symbol': {'pt': 35, 'src': "measured:the kit's render at 3x (the camera, SF Pro Bold 19: 29.3 pt wide); our symbols fill 5/6 of their box"},
    'comp.cc.symbol-color': {'color': '#ffffff', 'src': 'kit:2524:24527'},
    'comp.cc.wide.padding': {'pt': 14, 'src': 'kit:2547:2623'},
    'comp.cc.wide.gap': {'pt': 8, 'src': 'kit:2547:2623'},
    'comp.cc.well.size': {'pt': 40, 'src': 'kit:2543:2535'},
    'comp.cc.well.symbol': {'pt': 23, 'src': "measured:the kit's render at 3x (Focus's moon 19 pt)"},
    'comp.cc.module.title': {'text': {'family': 'text', 'weight': 510, 'size': 15, 'line': 18, 'tracking': -0.2}, 'src': 'kit:2547:2574'},
    'comp.cc.module.title-large': {'text': {'family': 'text', 'weight': 590, 'size': 14, 'line': 17, 'tracking': -0.08}, 'src': 'kit:2547:2646'},
    'comp.cc.module.detail': {'text': {'family': 'text', 'weight': 510, 'size': 14, 'line': 18, 'tracking': -0.08}, 'src': 'kit:2547:2575'},
    'comp.cc.module.label-color': {'color': '#ffffff', 'src': 'kit:2547:2574'},
    'comp.cc.module.detail-color': {'color': '#ffffff54', 'src': 'kit:2547:2575 (white 33 %)'},
    'comp.cc.module.detail-blend': {'choice': 'LINEAR_DODGE', 'src': 'kit:2547:2575'},
    'comp.cc.connectivity.big': {'pt': 57, 'src': 'kit:2570:20593'},
    'comp.cc.connectivity.small': {'pt': 25.67, 'src': 'kit:2570:20671'},
    'comp.cc.connectivity.symbol-big': {'pt': 28, 'src': "measured:the kit's render at 3x (23.3-23.7 pt)"},
    'comp.cc.connectivity.symbol-small': {'pt': 17, 'src': "measured:the kit's render at 3x (8.3-15.3 pt)"},
    'comp.cc.media.art': {'pt': 53.67, 'src': 'kit:2570:20726'},
    'comp.cc.media.art-corner': {'pt': 14, 'src': 'kit:2570:20726'},
    'comp.cc.media.art-x': {'pt': 12.67, 'src': 'kit:2570:20726'},
    'comp.cc.media.art-y': {'pt': 13.33, 'src': 'kit:2570:20726'},
    'comp.cc.media.output': {'pt': 40, 'src': 'kit:2570:20736'},
    'comp.cc.media.title': {'text': {'family': 'text', 'weight': 510, 'size': 15, 'line': 17, 'tracking': -0.2}, 'src': 'kit:2570:20722'},
    'comp.cc.media.text-color': {'color': '#afafaf', 'src': 'kit:2570:20722'},
    'comp.cc.media.text-blend': {'choice': 'LINEAR_DODGE', 'src': 'kit:2570:20722'},
    'comp.cc.media.transport': {'pt': 24, 'src': "measured:the kit's render at 3x (previous, next: 20 pt)"},
    'comp.cc.media.play': {'pt': 27, 'src': "measured:the kit's render at 3x (play: 22.3 pt)"},
    'comp.cc.gallery.sheet': {'material': {'frost': 0, 'fills': [['#272925', 0.85, 'NORMAL']], 'innerShadows': [], 'shadows': []},
                              'src': 'measured:iOS 27 Simulator, the gallery (pass 5): its sheet #272925, nearly opaque over Control Center (the kit has no gallery)'},
    'comp.cc.gallery.entry': {'material': {'frost': 0, 'fills': [['#ffffff', 0.21, 'NORMAL']], 'innerShadows': [], 'shadows': []},
                              'src': 'measured:iOS 27 Simulator, the gallery: flat circles #555852 on the sheet (white 21 %)'},
    'comp.cc.gallery.dim': {'color': '#00000080', 'src': 'judged:Control Center dims behind the gallery'},
    'comp.cc.gallery.corner': {'pt': 38, 'src': "judged:a sheet of iOS 27's size (the kit's sheets are rounded 38)"},
    'comp.nc.fade.top': {'pt': 50, 'src': 'judged:where a scrolled notification has faded out (to be replaced by the kit\'s scroll edge)'},
    'comp.nc.fade.bottom': {'pt': 108, 'src': 'judged:gone by the top of the flashlight and camera buttons (bottom 50 + 58 pt): nothing shows under them'},
    'comp.nc.fade.length': {'pt': 70, 'src': 'judged:over how far it fades'},
}


CONTROL_ACCENTS = {
    'connectivity': 'blue', 'media': 'pink', 'brightness': 'yellow', 'volume': 'cyan', 'focus': 'indigo',
    'rotation-lock': 'red', 'silent': 'red', 'flashlight': 'blue', 'timer': 'orange', 'calculator': 'orange',
    'camera': 'gray', 'mirroring': 'blue', 'scan-code': 'gray', 'dark-mode': None, 'low-power': 'yellow',
    'airplane': 'orange', 'wifi': 'blue', 'bluetooth': 'blue', 'cellular': 'green', 'hotspot': 'green', 'location': 'blue',
    'nfc': 'blue', 'alarm': 'orange', 'stopwatch': 'orange', 'notes': 'yellow', 'settings': 'gray', 'quick-share': 'blue',
    'vpn': 'blue', 'data-saver': 'green', 'video': 'gray', 'selfie': 'gray', 'voice-memo': 'red', 'recognize-music': 'blue',
    'translate': 'blue', 'magnifier': 'gray', 'wallet': 'gray', 'home': 'orange', 'text-size': 'gray', 'invert': 'gray',
    'grayscale': 'gray', 'extra-dim': 'yellow', 'live-captions': 'blue', 'accessibility': 'blue',
}
for _c, _a in CONTROL_ACCENTS.items():
    if _a is None:
        SEED['comp.cc.accent.' + _c] = {'color': '#1c1c1e', 'src': 'judged:a dark glyph on the white of an active control'}
    else:
        _ref = 'ref.color.grays.gray' if _a == 'gray' else 'ref.color.accents.' + _a
        SEED['comp.cc.accent.' + _c] = {'ref': _ref, 'src': 'judged:the iOS colour of this control (' + _a + ')'}
for _c in ('wifi', 'bluetooth'):
    SEED['comp.cc.accent.' + _c]['src'] = 'kit:2570:20609 (Wi-Fi on: #0088ff)'
SEED['comp.cc.accent.cellular']['src'] = 'kit:2570:20677 (Cellular on: #34c759)'
SEED['comp.cc.accent.brightness']['src'] = 'kit:2570:20698 (sun #ffcc00)'
SEED['comp.cc.accent.volume']['src'] = 'kit:2570:20700 (speaker #00c0e8)'
SEED['comp.cc.accent.silent']['src'] = 'kit:2524:24523 (silent on: red)'


def main():
    kit = json.load(open(KIT, encoding='utf-8'))
    old = {}
    if os.path.exists(OUT):
        old = json.load(open(OUT, encoding='utf-8')).get('tokens', {})
    tokens = {}
    ref = build_ref(kit)
    tokens.update(dict(sorted(ref.items())))
    kept = {k: v for k, v in old.items() if not k.startswith('ref.')}
    for k, v in SEED.items():
        kept.setdefault(k, v)
    tokens.update(dict(sorted(kept.items())))
    theme = {'format': 1, 'name': 'iOS 27', 'author': 'Launcher (from Apple\'s iOS 27 UI kit)', 'version': '0.1',
             '_about': 'ref.* is generated by tools/design/build_ios27_theme.py from docs/tokens/ios27-kit.json; sys.* and comp.* are written here.',
             'tokens': tokens}
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(theme, f, indent=1, ensure_ascii=False)
        f.write('\n')
    print(f'{OUT}: {len(ref)} ref tokens, {len(kept)} sys/comp tokens')


if __name__ == '__main__':
    main()
