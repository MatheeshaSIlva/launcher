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
}


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
