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
    dl, dd = mats['Dock/Light'], mats['Dock/Dark']
    ref['ref.material.liquid-glass.dock'] = {'material': {
        'frost': dl['glass']['frost'], 'lens': lens(dl['glass']), 'fills': union_fills(dl['fills'], dd['fills']),
        'innerShadows': pair_shadows(dl['innerShadows'], dd['innerShadows']), 'shadows': pair_shadows(dl['shadows'], dd['shadows'])},
        'src': 'kit:' + dl['node'] + ' / ' + dd['node'], 'note': "the System page's _Dock Material: a Fill + Shadow layer under a Glass Effect layer"}
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


# The theme in the wallpaper's colours (Material You): what changes when the user turns them on (sys.color.source =
# wallpaper). Rewritten from here on every build. iOS 27 keeps its look and takes the palette's roles for its accents.
MATERIAL_YOU = {
    **{'ref.color.accents.' + _a: {'color': '@primary', 'src': "judged:Material You: the accents become the wallpaper's primary (as Android's)"}
       for _a in ('blue', 'brown', 'cyan', 'green', 'indigo', 'mint', 'orange', 'pink', 'purple', 'teal', 'yellow')},
    'ref.color.accents.red': {'color': '@error', 'src': "judged:Material You: destructive red becomes the palette's error colour"},
    'component.control.accent-symbol-color': {'color': '@on_primary', 'src': "judged:Material You: a symbol on the primary is on-primary"},
}

# Seeds for the hand-written tiers (only used for keys missing from the theme file).
SEED = {
    'sys.shape.corner-smoothing': {'factor': 0, 'src': "judged:round corners as the iOS surfaces have been drawn so far (iOS's own are continuous: to judge against the kit)"},
    'sys.color.source': {'choice': 'theme', 'src': "judged:the theme's own colours until the user picks the wallpaper's (Material You)"},
    'sys.scale.policy': {'choice': 'reference-width', 'src': 'judged:the screen is as many points wide as iOS 27\'s iPhone, so proportions and, on the S24, physical sizes match it'},
    'sys.scale.reference-width': {'pt': 402, 'src': 'kit:the kit\'s iPhone 17 Pro frame, 402 x 874'},
    # Motion: every animation by its role (step 4; was motion/Motion.kt's iOS profile, same values).
    'motion.app.open': {'spring': [0.42, 0.92], 'src': 'measured:tuned on the S24 (launch and close "really smooth", close elasticity reduced on request)'},
    'motion.app.close-position': {'spring': [0.5, 0.92], 'src': 'measured:tuned on the S24 (launch and close "really smooth", close elasticity reduced on request); position and size on their own springs, as iOS'},
    'motion.app.close-size': {'spring': [0.44, 0.9], 'src': 'measured:tuned on the S24 (launch and close "really smooth", close elasticity reduced on request)'},
    'motion.app.cancel': {'spring': [0.38, 1.0], 'src': 'judged:a home drag that does not commit: back to the app'},
    'motion.home.depth-open': {'spring': [0.45, 1.0], 'src': 'judged:home recedes behind an opening app'},
    'motion.home.depth-close': {'spring': [0.5, 1.0], 'src': 'judged:home comes forward again on close'},
    'motion.switch.commit': {'spring': [0.35, 1.0], 'src': 'judged:a sideways switch to the next app'},
    'motion.switch.cancel': {'spring': [0.3, 1.0], 'src': 'judged:a sideways switch let go'},
    'motion.home.page-snap': {'spring': [0.38, 1.0], 'src': "judged:UIKit's (paging and bounces critically damped)"},
    'motion.drawer': {'spring': [0.4, 1.0], 'src': "judged:UIKit's (paging and bounces critically damped)"},
    'motion.folder.open': {'spring': [0.49, 0.92], 'src': 'measured:iOS 27 (docs/IOS27_MOTION.md): the icons grow out'},
    'motion.folder.close': {'spring': [0.37, 0.97], 'src': 'measured:iOS 27 (docs/IOS27_MOTION.md): the icons go back'},
    'motion.mode-crossfade': {'ms': 220, 'src': 'judged:light and dark mode crossfading'},
    'motion.index.scroll': {'spring': [0.32, 1.0], 'src': "judged:the A-Z index: the list gliding to a letter's section"},
    'motion.index.bubble-in': {'spring': [0.3, 0.72], 'src': 'judged:the letter bubble popping in'},
    'motion.index.bubble-out': {'spring': [0.22, 1.0], 'src': 'judged:and out'},
    'motion.index.follow': {'spring': [0.16, 1.0], 'src': 'judged:and following the finger'},
    'motion.scroll.overscroll-return': {'spring': [0.42, 1.0], 'src': "judged:UIKit's (paging and bounces critically damped)"},
    'motion.scroll.rubber-band': {'factor': 0.55, 'src': "judged:UIScrollView's rubber band"},
    'motion.scroll.deceleration': {'factor': 0.998, 'src': "judged:UIScrollView's normal deceleration (per ms)"},
    'motion.home.page-fling': {'factor': 320, 'src': 'judged:a fling faster than this (dp/s) turns the page even when it has moved only a little'},
    'motion.icon.press-dim': {'factor': 0.32, 'src': 'judged:touching an icon dims it (iOS), instead of shrinking it'},
    'motion.icon.press-in': {'ms': 70, 'src': 'judged:the dim coming'},
    'motion.icon.press-out': {'ms': 220, 'src': 'judged:the dim going'},
    'motion.home.content-zoom': {'factor': 1.12, 'src': 'judged:home behind an open app: icons zoom more than the wallpaper (the sense of depth)'},
    'motion.home.wallpaper-zoom': {'factor': 1.04, 'src': 'judged:the wallpaper behind an open app'},
    'motion.home.depth-blur': {'pt': 14, 'src': 'judged:home blurred when it has fully receded behind an open app'},
    'motion.menu.open': {'spring': [0.35, 0.8], 'src': 'judged:the item lifts, home blurs and the menu grows on one spring (UIContextMenuInteraction)'},
    'motion.menu.close': {'spring': [0.3, 1.0], 'src': 'judged:closing is quicker and does not bounce'},
    'motion.menu.blur': {'pt': 18, 'src': "judged:home's blur behind a menu"},
    'motion.edit.reflow': {'spring': [0.36, 1.0], 'src': 'judged:icons making room for a dragged one'},
    'motion.edit.drag-lift': {'spring': [0.26, 0.8], 'src': 'judged:the dragged one lifting'},
    'motion.edit.drag-settle': {'spring': [0.34, 0.86], 'src': 'judged:and dropping into its place'},
    'motion.edit.jiggle': {'deg': 1.6, 'src': 'judged:the wiggle (widgets get less)'},
    'motion.edit.jiggle-period': {'factor': 0.26, 'src': "judged:the wiggle's period (s)"},
    'motion.sheet': {'spring': [0.45, 1.0], 'src': 'judged:a sheet presenting and dismissing (the widget gallery)'},
    'motion.sheet.push': {'spring': [0.42, 1.0], 'src': 'judged:a page pushed inside a sheet'},
    'motion.widget.resize': {'spring': [0.4, 0.86], 'src': 'judged:a widget changing size (the content crossfades)'},
    'motion.appear': {'spring': [0.42, 0.78], 'src': 'judged:something new on home growing into its place'},
    'motion.appear.fade': {'ms': 180, 'src': 'judged:its fade'},
    'motion.disappear.fade': {'ms': 200, 'src': 'judged:something leaving fading'},
    'motion.edit.bar': {'spring': [0.4, 0.9], 'src': 'judged:the edit bar sliding in from the top and out'},
    'motion.arrival': {'spring': [0.62, 1.0], 'src': 'judged:home arriving (after unlock, after a cold start): zooming out to rest'},
    'motion.arrival.wallpaper': {'spring': [0.7, 1.0], 'src': 'judged:the wallpaper settling on a cold start'},
    'motion.arrival.zoom': {'factor': 1.18, 'src': 'judged:how far home is zoomed when it starts arriving'},
    'motion.arrival.wallpaper-zoom': {'factor': 1.06, 'src': 'judged:and the wallpaper'},
    'motion.clock.tick': {'ms': 420, 'src': "judged:the clock's numerals crossfading at the minute change"},
    'motion.switcher.card-scale': {'factor': 0.68, 'src': "measured:Apple's illustration of the iOS 27 App Switcher (card size, of the screen)"},
    'motion.switcher.card-center-y': {'factor': 0.515, 'src': "measured:the same (its vertical centre, of the screen's height)"},
    'motion.switcher.focus-left': {'factor': 0.225, 'src': "measured:the same (the focused card's left edge, of its width)"},
    'motion.switcher.hold': {'ms': 150, 'src': 'judged:the finger resting this long during a home swipe opens the deck'},
    'motion.switcher.hold-travel': {'factor': 56, 'src': 'judged:at least this far (dp) above where the swipe started'},
    'motion.switcher.enter': {'spring': [0.38, 0.9], 'src': 'judged:the deck coming'},
    'motion.switcher.scroll': {'spring': [0.4, 1.0], 'src': 'judged:scrolling to rest on a card'},
    'motion.switcher.fling-projection': {'factor': 0.22, 'src': 'judged:how far ahead (s) a throw is projected to choose a card'},
    'motion.switcher.open': {'spring': [0.42, 0.92], 'src': 'judged:a card opening'},
    'motion.switcher.home': {'spring': [0.42, 1.0], 'src': 'judged:the deck going home'},
    'motion.switcher.flick': {'spring': [0.35, 1.0], 'src': 'judged:a card flicked up and away'},
    'motion.switcher.flick-speed': {'factor': 900, 'src': 'judged:faster than this (dp/s), or a third of its height'},
    'motion.switcher.reflow': {'spring': [0.38, 0.92], 'src': 'judged:the gap closing'},
    # The shade's, Control Center's, the status bar's and edit mode's springs (they were in code; same values).
    'motion.cc.close-style': {'spring': [0.2, 1.0], 'src': "judged:Control Center's closing choreography blending in and out"},
    'motion.cc.press-in': {'spring': [0.22, 1.0], 'src': 'judged:a control pressed'},
    'motion.cc.press-out': {'spring': [0.38, 0.7], 'src': 'judged:a control let go (springing back)'},
    'motion.cc.toggle': {'spring': [0.32, 0.9], 'src': 'judged:a control turning on or off'},
    'motion.cc.level': {'spring': [0.45, 1.0], 'src': "judged:a slider's level following the system's"},
    'motion.cc.edit': {'spring': [0.42, 0.9], 'src': 'judged:edit mode coming and going'},
    'motion.cc.reflow': {'spring': [0.36, 0.9], 'src': 'judged:controls making room in edit mode'},
    'motion.cc.resize': {'spring': [0.4, 0.82], 'src': 'judged:a control changing size'},
    'motion.cc.appear': {'spring': [0.42, 0.78], 'src': 'judged:a control added'},
    'motion.cc.leave': {'spring': [0.26, 1.0], 'src': 'judged:a control removed'},
    'motion.cc.lift': {'spring': [0.26, 0.8], 'src': 'judged:a control lifted to be dragged'},
    'motion.cc.drop': {'spring': [0.34, 0.86], 'src': 'judged:and dropped into its place'},
    'motion.cc.stretch-back': {'spring': [0.38, 0.7], 'src': 'judged:the grid stretched by an overpull, back'},
    'motion.cc.expanded.open': {'spring': [0.42, 0.84], 'src': 'judged:a module growing into its expanded form'},
    'motion.cc.expanded.close': {'spring': [0.34, 1.0], 'src': 'judged:and back into its place'},
    'motion.cc.expanded.level': {'spring': [0.4, 1.0], 'src': "judged:the expanded slider's level"},
    'motion.cc.gallery.open': {'spring': [0.45, 1.0], 'src': 'judged:the gallery sheet coming up'},
    'motion.cc.gallery.close': {'spring': [0.38, 1.0], 'src': 'judged:and going'},
    'motion.nc.reflow': {'spring': [0.4, 0.88], 'src': "judged:the list's notifications moving to new places"},
    'motion.nc.appear': {'spring': [0.42, 0.82], 'src': 'judged:a notification arriving in the list'},
    'motion.nc.leave': {'spring': [0.28, 1.0], 'src': 'judged:a notification leaving'},
    'motion.nc.swipe-back': {'spring': [0.36, 0.86], 'src': 'judged:a swiped notification let go: back'},
    'motion.nc.swipe-out': {'spring': [0.3, 1.0], 'src': 'judged:or away'},
    'motion.press-in': {'spring': [0.2, 1.0], 'src': 'judged:a platter or a button pressed (Notification Center, banners)'},
    'motion.press-out': {'spring': [0.35, 1.0], 'src': 'judged:and let go'},
    'motion.nc.hold': {'spring': [0.38, 1.0], 'src': 'judged:the flashlight and camera buttons growing while held'},
    'motion.nc.toggle': {'spring': [0.3, 1.0], 'src': 'judged:the flashlight button lighting'},
    'motion.nc.confirm': {'spring': [0.34, 0.8], 'src': "judged:a group's clear button turning into Clear"},
    'motion.nc.menu-open': {'spring': [0.35, 0.8], 'src': 'judged:a notification held: the long look opening'},
    'motion.nc.menu-close': {'spring': [0.26, 1.0], 'src': 'judged:and closing'},
    'motion.nc.clock-tick': {'spring': [0.45, 1.0], 'src': "judged:the clock's numerals crossfading at the minute"},
    'motion.banner.in': {'spring': [0.64, 0.61], 'src': 'judged:a banner swooping in from the top'},
    'motion.banner.out': {'spring': [0.3, 1.0], 'src': 'judged:and going'},
    'motion.banner.fly': {'spring': [0.35, 1.0], 'src': 'judged:a banner flung away sideways'},
    'motion.banner.back': {'spring': [0.38, 0.82], 'src': 'judged:a dragged banner let go: back'},
    'motion.banner.resize': {'spring': [0.4, 1.0], 'src': 'judged:a banner changing height'},
    'motion.cc.close': {'spring': [0.34, 1.0], 'src': 'measured:iOS 27 (docs/IOS27_MOTION.md): Control Center closing takes ~0.18 s'},
    'motion.cc.settle': {'spring': [0.42, 0.68], 'src': 'measured:iOS 27 (docs/IOS27_MOTION.md): the controls settle, overshooting a little'},
    'motion.nc.open': {'spring': [0.44, 1.0], 'src': 'judged:Notification Center coming down'},
    'motion.nc.close': {'spring': [0.38, 1.0], 'src': 'judged:and going'},
    'motion.statusbar.move': {'spring': [0.36, 1.0], 'src': 'judged:a slot moving aside'},
    'motion.statusbar.appear': {'spring': [0.38, 0.78], 'src': 'judged:an icon coming'},
    'motion.statusbar.leave': {'spring': [0.26, 1.0], 'src': 'judged:an icon going'},
    'motion.statusbar.level': {'spring': [0.6, 1.0], 'src': "judged:the battery's level"},
    'motion.statusbar.roll': {'spring': [0.42, 1.0], 'src': 'judged:a digit rolling to the next'},
    'motion.edit.press-in': {'spring': [0.12, 1.0], 'src': "judged:edit mode's buttons pressed"},
    'motion.edit.press-out': {'spring': [0.3, 1.0], 'src': 'judged:and let go'},
    'motion.switcher.clear-press': {'spring': [0.16, 1.0], 'src': 'judged:Clear All pressed'},
    'motion.home.label-tone': {'spring': [0.5, 1.0], 'src': 'judged:names and the clock turning light or dark with a new wallpaper (as gently as the wallpaper changes)'},
    'motion.banner.fly-fade': {'spring': [0.5, 1.0], 'src': 'judged:a flung banner fading as it goes'},
    # Frosted glass for what carries text over home (menus, sheets, the switcher's menu, Clear All) and over anything
    # (banners): Matheesha found the kit's Regular glass barely translucent next to the rest (judged 2026-10-09).
    'sys.material.glass.frosted': {'material': {'frost': 16, 'lens': {'refraction': 0.7, 'depth': 30, 'dispersion': 0.2, 'splay': 0.2, 'light': 0.2, 'lightAngle': 0}, 'fills': [['#ffffff', [0.15, 0.0], 'LIGHTEN'], ['#999999', [0.33, 0.0], 'LUMINOSITY'], ['#333333', [0.0, 0.45], 'LUMINOSITY']], 'innerShadows': [['#282828', 0.0, -40.0, 10.0, -40.0, 'LINEAR_DODGE'], ['#282828', 0.0, 40.0, 10.0, -40.0, 'LINEAR_DODGE'], ['#282828', 0.0, -1.25, 0.25, 0.0, 'LINEAR_DODGE'], ['#282828', 0.0, 1.25, 0.25, 0.0, 'LINEAR_DODGE']], 'shadows': [['#00000040|#00000073', 0.0, 8.0, 48.0, 0.0, 'NORMAL'], ['#dbdbdb|#a6a6a6', 0.0, 0.0, 0.0, 0.5, 'LINEAR_BURN'], ['#dbdbdb|#a6a6a6', -1.25, 0.0, 0.0, -0.75, 'LINEAR_BURN'], ['#dbdbdb|#a6a6a6', 1.25, 0.0, 0.0, -0.75, 'LINEAR_BURN']]}, 'src': "judged:the dock's glass frosted as the kit's Regular (16), a light veil in light mode, a darker one in dark mode: as translucent as the rest, its text readable over home's blurred, veiled backdrop"},
    'sys.material.glass.frosted-legible': {'material': {'frost': 16, 'lens': {'refraction': 0.7, 'depth': 30, 'dispersion': 0.2, 'splay': 0.2, 'light': 0.25, 'lightAngle': 0}, 'fills': [['#ffffff', [0.35, 0.0], 'LIGHTEN'], ['#bfbfbf', [0.05, 0.0], 'DARKEN'], ['#1a1a1a', [0.0, 0.35], 'LUMINOSITY'], ['#1a1a1a', [0.0, 0.45], 'LUMINOSITY'], ['#1a1a1a', [0.0, 0.5], 'LIGHTEN']], 'innerShadows': [['#282828|#1a1a1a', 0.0, -40.0, 10.0, -40.0, 'LINEAR_DODGE'], ['#282828|#1a1a1a', 0.0, 40.0, 10.0, -40.0, 'LINEAR_DODGE']], 'shadows': [['#00000040|#00000073', 0.0, 8.0, 48.0, 0.0, 'NORMAL'], ['#dbdbdb|#a6a6a6', 0.0, 0.0, 0.0, 0.5, 'LINEAR_BURN'], ['#dbdbdb|#a6a6a6', -1.25, 0.0, 0.0, -0.75, 'LINEAR_BURN'], ['#dbdbdb|#a6a6a6', 1.25, 0.0, 0.0, -0.75, 'LINEAR_BURN']]}, 'src': "judged:the kit's Regular glass with half its fills: still glass, readable over any app (banners)"},
    'motion.debug.slow': {'factor': 1, 'src': "judged:slow motion for checking animations frame by frame (1 = normal; every spring's period is stretched by it)"},
    'sys.glass.rim': {'factor': 0.4, 'src': "judged:Matheesha: the kit's hairline rims (its blur-0 drop shadows, linear burn #cccccc / #a6a6a6) read as a black outline over dark backdrops on the phone; 1 is the kit's"},
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
    # Text and lines on the materials (App Library, folders, Spotlight, menus, sheets): iOS's label colours, adjusted where
    # the app needed it (they were Appearance's; kept as they were).
    'sys.color.material.label': {'light': '#000000f2', 'dark': '#ffffff', 'src': "judged:the kit's primary label (95 % in light)"},
    'sys.color.material.secondary-label': {'light': '#3c3c4399', 'dark': '#ebebf599', 'src': "judged:the kit's secondary label (dark: 60 %)"},
    'sys.color.material.tertiary-label': {'light': '#3c3c438c', 'dark': '#ebebf5b3', 'src': "judged:a little stronger than the kit's tertiary label (readable over any wallpaper)"},
    'sys.color.material.separator': {'light': '#00000024', 'dark': '#ffffff26', 'src': 'judged:lines on the materials'},
    'sys.color.material.destructive': {'light': '#ff3b30', 'dark': '#ff453a', 'src': "judged:iOS's system red"},
    'sys.color.press': {'light': '#00000014', 'dark': '#ffffff1f', 'src': 'judged:a pressed row or button'},
    'sys.glass.tint': {'light': '#ffffff30', 'dark': '#00000052', 'src': "judged:the glass's own tint: light in light mode, dark in dark mode (the dock's)"},
    'sys.glass.label.dark-from': {'factor': 0.56, 'src': "judged:text on glass straight on the wallpaper is white (with its shadow) up to this brightness behind the tinted glass, dark only over clearly light glass (Matheesha: dark labels over a darker wallpaper were hard to read; the old blend 0.40-0.52 gave grey text)"},
    'sys.glass.label.dark-full': {'factor': 0.62, 'src': 'judged:dark from here; in between a label keeps the tone it has'},
    'sys.appearance.wallpaper-dim': {'factor': 0.14, 'src': 'judged:dark mode dims the wallpaper a little (iOS: "Dark Appearance Dims Wallpaper")'},
    # Fallbacks: a surface drawn without the glass renderer.
    'comp.home.menu.fallback': {'light': '#f2f2f7f2', 'dark': '#2c2c2ed9', 'src': 'judged:a menu without the glass renderer'},
    'comp.widgets.fallback-color': {'light': '#f2f2f7f2', 'dark': '#202024e6', 'src': 'judged:the gallery sheet without the glass renderer'},
    'comp.widgets.grabber-color': {'light': '#0000004d', 'dark': '#ffffff59', 'src': "judged:the gallery sheet's grabber"},
    'comp.home.field.clear-color': {'light': '#3c3c4399', 'dark': '#ffffffd9', 'src': "judged:a search field's clear button"},
    'comp.home.field.clear-symbol-color': {'light': '#ffffff', 'dark': '#1c1c1e', 'src': 'judged:its cross'},
    'sys.color.overlay': {'ref': 'ref.color.overlays.default', 'src': 'kit:VariableID:513:90698'},
    'sys.color.accent': {'ref': 'ref.color.accents.blue', 'src': 'kit:VariableID:507:29166'},
    'sys.color.destructive': {'ref': 'ref.color.accents.red', 'src': 'kit:VariableID:509:77471'},
    'sys.material.glass.clear': {'ref': 'ref.material.liquid-glass.clear', 'src': 'kit:5914:23547'},
    'sys.material.glass.regular': {'ref': 'ref.material.liquid-glass.regular', 'src': 'kit:5584:31857'},
    'sys.material.glass.small': {'ref': 'ref.material.liquid-glass.small', 'src': 'kit:10472:45029'},
    'sys.material.glass.small-active': {'ref': 'ref.material.liquid-glass.small-active', 'src': 'kit:5564:42811'},
    'sys.material.glass.dock': {'ref': 'ref.material.liquid-glass.dock', 'src': 'kit:10486:20740'},
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
    # Home (step 2d): the dock's material is the kit's own; the Search pill is made of it (kit 5593:10801); the
    # widgets' glass and edit mode's buttons are ours (the kit's widgets have the app's own background) and take it too.
    'comp.home.dock.material': {'ref': 'sys.material.glass.dock', 'src': 'kit:558:50551'},
    'comp.home.search.material': {'ref': 'sys.material.glass.dock', 'src': 'kit:5593:10801'},
    'comp.home.widget.material': {'ref': 'sys.material.glass.dock', 'src': "judged:the kit's widgets show the app's own background; ours sit on home's glass, the dock's"},
    'comp.home.button.material': {'ref': 'sys.material.glass.dock', 'src': "judged:edit mode's Edit and Done on home's glass, as the Search pill"},
    # Home's names under icons and widgets: white with a soft shadow (iOS), dark over a bright wallpaper (Android's
    # convenience: always readable), blended by the wallpaper's luminance under each name (dark mode's dim counted).
    'comp.home.label.light': {'color': '#ffffff', 'src': "kit:the Home Screen's app names"},
    'comp.home.label.dark': {'color': '#1c1c1e', 'src': 'judged:dark names over a bright wallpaper (the kit\'s label primary, light)'},
    'comp.home.label.shadow': {'color': '#00000040', 'src': 'judged:the soft shadow under white names (none under dark ones)'},
    'comp.home.label.dark-from': {'factor': 0.62, 'src': 'judged:names start turning dark over a wallpaper this light (luminance)'},
    'comp.home.label.dark-full': {'factor': 0.78, 'src': 'judged:names are fully dark over a wallpaper this light'},
    # The glass clock (home's widget, Notification Center's) takes the same tone as the names, from the wallpaper under it:
    # light glass over a dark or mid wallpaper, dark glass over a bright one (white glass vanished there, dark glass was
    # muddy over a dark one), whatever the appearance.
    # Badges (not in the kit's data: measured on iOS before, kept as they were).
    'comp.badge.fill': {'color': '#ff3b30', 'src': "judged:iOS's notification badge red"},
    'comp.badge.label': {'color': '#ffffff', 'src': 'judged:the white count'},
    'comp.badge.type': {'text': {'family': 'text', 'weight': 500, 'size': 15.5, 'line': 18, 'tracking': 0}, 'src': 'judged:measured on iOS'},
    'comp.badge.height': {'pt': 24, 'src': 'judged:measured on iOS'},
    'comp.badge.pad-x': {'pt': 7, 'src': 'judged:on each side of a long count'},
    'comp.badge.offset-x': {'pt': 5, 'src': "judged:its right edge 5 pt past the icon's, as on iOS"},
    'comp.badge.offset-y': {'pt': -5, 'src': "judged:its top 5 pt above the icon's, as on iOS"},
    'comp.badge.max': {'factor': 999, 'src': 'judged:larger counts show as 999+'},
    # A stack's count in Notification Center (iOS 16+: white, the number dark grey; measured on the iOS 27 Simulator).
    'comp.nc.count.fill': {'color': '#ffffff', 'src': 'measured:the iOS 27 Simulator'},
    'comp.nc.count.label': {'color': '#3a3a3c', 'src': 'measured:the iOS 27 Simulator'},
    'comp.nc.count.type': {'text': {'family': 'text', 'weight': 600, 'size': 12.5, 'line': 15, 'tracking': 0}, 'src': 'measured:the iOS 27 Simulator'},
    'comp.nc.count.height': {'pt': 18, 'src': 'measured:18 pt round'},
    'comp.nc.count.pad-x': {'pt': 3.5, 'src': 'judged:a pill for two digits'},
    'comp.nc.count.offset-x': {'pt': 3, 'src': "measured:its right edge ~3 pt past the icon's"},
    'comp.nc.count.offset-y': {'pt': -6, 'src': "measured:its centre 3 pt below the icon's top"},
    'comp.nc.count.max': {'factor': 99, 'src': 'judged:larger stacks show as 99+'},
    # Notification Center's colours that were in code (judged before; kept as they were).
    'comp.nc.media.placeholder-color': {'light': '#0000001a', 'dark': '#ffffff26', 'src': "judged:the player's artwork while there is none"},
    'comp.nc.media.track-color': {'light': '#00000026', 'dark': '#ffffff33', 'src': "judged:the player's progress track"},
    'comp.nc.header-color': {'color': '#ffffff', 'src': "judged:an app's name over its expanded group, on the wallpaper"},
    'comp.nc.text-shadow-color': {'color': '#00000059', 'src': 'judged:the soft shadow under the date and group names'},
    # The banners' colours that were in code.
    'comp.banner.flat-color': {'light': '#f7f7f9f2', 'dark': '#222224eb', 'src': 'judged:a banner without the glass renderer (a plain platter)'},
    'comp.banner.call.button-color': {'color': '#888888', 'src': "judged:a call's button without a colour of its own"},
    'comp.banner.call.press-color': {'color': '#00000040', 'src': "judged:a call's button pressed (darker)"},
    'comp.banner.call.symbol-color': {'color': '#ffffff', 'src': "judged:the symbol on a call's button"},
    # The status bar's battery (iOS's system colours).
    'comp.statusbar.battery-low-color': {'color': '#ff3b30', 'src': "judged:iOS's red below 20 %"},
    'comp.statusbar.battery-saver-color': {'color': '#ffcc00', 'src': "judged:iOS's yellow in Low Power Mode"},
    'comp.statusbar.battery-charging-color': {'color': '#34c759', 'src': "judged:iOS's green while charging"},
    'comp.badge.remove.disc': {'light': '#e5e5eaf2', 'dark': '#747480e6', 'src': "judged:edit mode's grey disc"},
    'comp.badge.remove.minus': {'light': '#3c3c43', 'dark': '#ffffff', 'src': 'judged:its minus'},
    'comp.badge.remove.radius': {'pt': 11, 'src': 'judged:measured on iOS'},
    'comp.badge.remove.stroke': {'pt': 2.2, 'src': 'judged:the minus'},
    'comp.home.clock.tint-light': {'color': '#ffffff30', 'src': "judged:the clock over a dark or mid wallpaper: the glass's light tint (the dock's in light mode)"},
    'comp.home.clock.tint-dark': {'color': '#00000029', 'src': "judged:the clock over a bright wallpaper: the light glass only slightly dimmed, close to the dock's glass (Matheesha, 2026-10-09; the dark mode tint 52 read as grey)"},
    # The long-press menu: the kit's Home Screen Quick Actions (System page 2607:25374, its menu 5626:51776).
    'comp.home.menu.material': {'ref': 'sys.material.glass.frosted', 'src': "judged:the frosted glass (was the kit's Regular, 5626:51776: barely translucent next to the rest)"},
    'comp.home.menu.corner': {'pt': 30, 'src': 'kit:10411:18581'},
    'comp.home.menu.width': {'pt': 250, 'src': 'kit:5626:51776'},
    'comp.home.menu.row': {'pt': 42, 'src': 'kit:5626:51776 (rows 42 pt apart)'},
    'comp.home.menu.pad-top': {'pt': 10, 'src': 'kit:5621:50116 (actions 10 pt below the top)'},
    'comp.home.menu.pad-bottom': {'pt': 8, 'src': 'kit:5621:50116'},
    'comp.home.menu.symbol-x': {'pt': 36, 'src': 'kit:5619:49811 (a 20 pt symbol column 26 pt in: its centre)'},
    'comp.home.menu.label-x': {'pt': 60, 'src': 'kit:5619:49810'},
    'comp.home.menu.type': {'ref': 'ref.type.body.regular', 'src': 'kit:5619:49810 (17 Regular)'},
    'comp.home.menu.label': {'light': '#1a1a1a', 'dark': '#ffffff', 'src': 'kit:5619:49810 (light; dark judged: white on the dark glass)'},
    'comp.home.menu.destructive': {'ref': 'ref.color.accents.red', 'src': 'kit:5622:50175 (#ff383c)'},
    'comp.home.menu.press': {'light': '#00000014', 'dark': '#ffffff1f', 'src': "judged:a pressed row's highlight (was the app's press fill)"},
    'comp.home.menu.symbol': {'pt': 18, 'src': "judged:a symbol from a drawable, the size of the drawn ones (18 pt)"},
    'comp.home.menu.grow-from': {'factor': 0.8, 'src': 'judged:the panel grows out of what it belongs to from 80 % of its size'},
    # The App Switcher's menu of an app and its Clear All button: not in iOS (Android's convenience), so judged: the menu
    # is home's menu, the button a capsule of the same glass.
    **{'comp.switcher.menu.' + _k: {'ref': 'comp.home.menu.' + _k, 'src': "judged:the App Switcher's app menu looks as home's menus"}
       for _k in ['material', 'corner', 'width', 'row', 'pad-top', 'pad-bottom', 'symbol-x', 'label-x', 'type', 'label', 'destructive', 'press',
                  'symbol', 'grow-from', 'fallback']},
    # Over the app's own picture (bright in dark mode too): the frosted glass that stays readable over anything.
    'comp.switcher.menu.material': {'ref': 'sys.material.glass.frosted-legible', 'src': "judged:the switcher's menu lies over an app's picture, which may be bright in dark mode"},
    'comp.switcher.clear.material': {'ref': 'comp.home.menu.material', 'src': "judged:Clear All on the menus' glass (the Regular glass)"},
    'comp.switcher.clear.height': {'pt': 44, 'src': "judged:the kit's button height (a sheet's 44 pt buttons)"},
    'comp.switcher.clear.padding-x': {'pt': 22, 'src': 'judged:the label 22 pt from the capsule\'s ends'},
    'comp.switcher.clear.bottom': {'pt': 44, 'src': 'judged:the capsule\'s bottom this far above the screen\'s bottom edge (above the gesture bar)'},
    'comp.switcher.clear.type': {'ref': 'ref.type.headline.regular', 'src': 'judged:17 semibold, a button\'s label'},
    'comp.switcher.clear.label': {'ref': 'comp.home.menu.label', 'src': 'judged:as the menus\' labels'},
    'comp.switcher.clear.press': {'ref': 'comp.home.menu.press', 'src': 'judged:as a pressed menu row'},
    # Search fields (App Library, Spotlight, the widget gallery): the kit's search field (Toolbars, _Search - 48pt).
    'comp.home.field.material': {'ref': 'sys.material.glass.small-active', 'src': 'kit:5720:33170 (the 48 pt search field: the small glass, active)'},
    # Over the App Library's and Spotlight's blurred background: the dock's glass (kept from before; the kit has no App Library).
    'comp.library.tile.material': {'ref': 'sys.material.glass.dock', 'src': "judged:App Library's tiles, folders and search bar on the dock's glass (the kit has no App Library)"},
    'comp.spotlight.card.material': {'ref': 'sys.material.glass.dock', 'src': "judged:Spotlight's card on the dock's glass, as the App Library's tiles"},
    # The widget gallery: a tall glass sheet (the kit's medium sheet is the Regular glass; its large one is opaque).
    'comp.widgets.sheet.material': {'ref': 'sys.material.glass.frosted', 'src': "judged:the frosted glass (was the kit's Regular)"},
    'comp.widgets.button.material': {'ref': 'sys.material.glass.small-active', 'src': "kit:5566:7888 (a sheet's 44 pt buttons: the small glass, active)"},
    'comp.widgets.card.material': {'ref': 'sys.material.glass.dock', 'src': "judged:a widget's preview card on the dock's glass (as on home)"},
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
    'comp.nc.button.on-symbol-color': {'ref': 'comp.cc.symbol-on-color', 'src': "judged:dark on the white disc, as Control Center's lit controls"},
    'comp.nc.button.on-color': {'color': '#ffffff', 'src': 'judged:the flashlight on: a white disc (as iOS\'s)'},
    'comp.nc.look.card-color': {'light': '#ffffff', 'dark': '#1c1c1e', 'src': 'kit:143:62847 (light; dark judged: Backgrounds/Primary - Elevated)'},
    'comp.nc.look.card-corner': {'pt': 26, 'src': 'kit:143:62847'},
    'comp.nc.look.inset': {'pt': 16, 'src': 'kit:143:62847'},
    'comp.nc.look.label': {'ref': 'sys.color.label.primary', 'src': 'kit:143:62847'},
    'comp.nc.look.menu-gap': {'pt': 10, 'src': 'kit:143:62847'},
    # The long look's menu, drawn by the menu component (MenuSpec.NC): the kit's rows of a 22 pt line 20 pt apart with 20 pt
    # of padding, a 20 pt symbol 26 pt in and the label 14 pt after it, as the component's row, paddings and columns.
    'comp.nc.menu.material': {'ref': 'sys.material.glass.clear', 'src': 'kit:143:62847'},
    'comp.nc.menu.corner': {'pt': 26, 'src': 'kit:143:62847'},
    'comp.nc.menu.width': {'pt': 250, 'src': 'kit:143:62847'},
    'comp.nc.menu.row': {'pt': 42, 'src': 'kit:143:62847 (a 22 pt line and 20 pt between rows)'},
    'comp.nc.menu.pad-top': {'pt': 10, 'src': 'kit:143:62847 (20 pt of padding less half the 20 pt between rows)'},
    'comp.nc.menu.pad-bottom': {'pt': 10, 'src': 'kit:143:62847 (as the top)'},
    'comp.nc.menu.symbol-x': {'pt': 36, 'src': 'kit:143:62847 (a 20 pt symbol 26 pt in: its centre)'},
    'comp.nc.menu.label-x': {'pt': 60, 'src': 'kit:143:62847 (26 pt in, a 20 pt symbol, 14 pt after it)'},
    'comp.nc.menu.symbol': {'pt': 17, 'src': 'judged:a symbol drawn at 85 % of its 20 pt column (as before)'},
    'comp.nc.menu.type': {'text': {'family': 'text', 'weight': 400, 'size': 17, 'line': 22, 'tracking': -0.43}, 'src': 'kit:143:62847'},
    'comp.nc.menu.label': {'color': '#ffffff', 'src': 'kit:143:62847'},
    'comp.nc.menu.destructive': {'ref': 'ref.color.accents.red', 'src': "judged:as home's menus"},
    'comp.nc.menu.press': {'color': '#ffffff26', 'src': 'judged:a pressed row on the clear glass (as before)'},
    'comp.nc.menu.fallback': {'ref': 'comp.home.menu.fallback', 'src': "judged:as home's menus"},
    'comp.nc.menu.grow-from': {'factor': 0.6, 'src': 'judged:the menu grows out of the card from 60 % of its size (as before)'},
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
    'comp.cc.motion.open-travel': {'pt': 240, 'src': "judged:Matheesha: iOS 27's (fully in after ~110 pt, measured: docs/IOS27_MOTION.md) is too fast to see"},
    'comp.cc.motion.open': {'spring': [0.45, 1.0], 'src': "judged:Matheesha wants the opening seen; it was 0.3 / 1 (iOS: in within ~75 ms of a quick pull)"},
    'comp.cc.grid.gap': {'pt': 15.333, 'src': 'measured:iOS 27 Simulator, accessibility frames (85.33 pt pitch; the kit has 15 / 17)'},
    'comp.cc.grid.top': {'pt': 132.3, 'src': 'measured:iOS 27 Simulator, accessibility frames (the kit: 132)'},
    'comp.cc.grid.top-edit': {'pt': 86, 'src': 'measured:iOS 27 Simulator, edit mode'},
    'comp.cc.status.row-y': {'pt': 104.2, 'src': 'measured:iOS 27 Simulator, accessibility frames (the kit: 91.7)'},
    'comp.cc.module.corner': {'pt': 30, 'src': 'kit:2547:2662 (2x2)'},
    'comp.cc.slider.corner': {'pt': 34, 'src': 'kit:2570:20682'},
    # Control Center's colours that were in code (judged before; kept as they were).
    'comp.cc.symbol-on-color': {'color': '#1c1c1e', 'src': 'judged:a symbol on a lit (white) control'},
    'comp.cc.chevron-color': {'color': '#ffffffb3', 'src': "judged:Focus's chevron beside its name"},
    'comp.cc.add.fill-color': {'color': '#ffffff', 'src': 'judged:edit mode\'s "Add a Control" disc'},
    'comp.cc.media.placeholder-color': {'color': '#ffffff80', 'src': 'judged:the music symbol while there is no artwork'},
    'comp.cc.media.track-color': {'color': '#ffffff40', 'src': "judged:a progress or volume bar's track"},
    'comp.cc.media.progress-color': {'color': '#ffffffe6', 'src': "judged:the track's progress"},
    'comp.cc.media.level-color': {'color': '#fffffff2', 'src': "judged:the expanded player's volume level"},
    'comp.cc.media.secondary-color': {'color': '#ffffff99', 'src': "judged:the expanded player's times and volume symbols"},
    'comp.cc.edit.badge-color': {'color': '#e5e5eaf2', 'src': "judged:edit mode's remove badge (home's light one)"},
    'comp.cc.edit.badge-minus-color': {'color': '#3c3c43', 'src': 'judged:its minus'},
    'comp.cc.edit.handle-color': {'color': '#ffffff', 'src': "judged:edit mode's resize arc"},
    'comp.cc.edit.handle-shadow-color': {'color': '#00000059', 'src': 'judged:the shadow under it'},
    'comp.cc.gallery.grabber-color': {'color': '#ffffff66', 'src': "judged:the gallery sheet's grabber"},
    'comp.cc.gallery.heading-color': {'color': '#ffffff', 'src': 'judged:"Add a Control"'},
    'comp.cc.gallery.section-color': {'color': '#ffffff99', 'src': "judged:the sections' names"},
    'comp.cc.gallery.name-color': {'color': '#ffffffd9', 'src': "judged:the controls' names"},
    'comp.cc.slider.cover': {'factor': 0.8, 'src': "judged:the symbol takes the slider's colour while the level crosses its middle 80 % (the expanded slider's 100 % made one with the module's)"},
    # The expanded modules (iOS 18+; not in the kit: measured on iOS before, kept as they were).
    'comp.cc.expanded.slider-width': {'pt': 152, 'src': 'judged:the expanded slider (as it was in code)'},
    'comp.cc.expanded.slider-height': {'pt': 380, 'src': 'judged:the expanded slider (as it was in code)'},
    'comp.cc.expanded.slider-corner': {'pt': 46, 'src': 'judged:the expanded slider (as it was in code)'},
    'comp.cc.expanded.slider-symbol': {'pt': 34, 'src': 'judged:its symbol (as it was in code; smaller while the slider grows)'},
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
    # Banners (docs/IOS27_KIT.md "Banners"): Notification Center's platter (NotifPainter) on regular glass.
    'comp.banner.material': {'ref': 'sys.material.glass.frosted-legible', 'src': "judged:the frosted glass that stays readable over any app (was the kit's Regular)"},
    'comp.banner.behind': {'ref': 'ref.color.backgrounds.primary', 'src': "judged:what is behind a banner (an app) is not known: an app's usual background in this appearance"},
    'comp.banner.corner': {'pt': 24, 'src': "kit:0:11292 (the platter)"},
    'comp.banner.margin': {'pt': 8, 'src': "measured:iOS 27 Simulator run 4, BannerNotification at x 8, 386 wide"},
    'comp.banner.label': {'ref': 'sys.color.label.primary', 'src': "measured:iOS 27 Simulator run 4 (black text in light)"},
    'comp.banner.secondary': {'ref': 'sys.color.label.secondary', 'src': "measured:iOS 27 Simulator run 4 (the time in grey)"},
    'comp.banner.call.corner': {'pt': 30, 'src': "judged:iOS's compact incoming call, rounder than a platter"},
    'comp.banner.call.height': {'pt': 76, 'src': "judged:iOS's compact incoming call"},
    'comp.banner.call.button': {'pt': 46, 'src': "judged:iOS's compact incoming call's buttons"},
    'comp.banner.call.decline': {'ref': 'ref.color.accents.red', 'src': "kit:VariableID:509:77471 (red)"},
    'comp.banner.call.answer': {'ref': 'ref.color.accents.green', 'src': "kit:VariableID:509:77474 (green)"},
    'comp.banner.action.height': {'pt': 40, 'src': "judged:a ringing notification's action capsules"},
    'comp.banner.action.fill': {'ref': 'ref.color.fills.tertiary', 'src': "judged:a faint capsule in the label colour (the kit's tertiary fill)"},
    'comp.banner.action.type': {'text': {'family': 'text', 'weight': 590, 'size': 15, 'line': 20, 'tracking': -0.23}, 'src': "judged:the platter title's type"},
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
    'grayscale': 'gray', 'extra-dim': 'yellow', 'live-captions': 'blue', 'accessibility': 'blue', 'app-tile': 'blue',
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


# The component catalogue (B2b, docs/PLAN_LAYOUTS_THEMES.md): shared styles every layout reads, whichever design it
# comes from. Each component.* token takes the value the iOS 27 layout's own token had; that token becomes an alias of
# it (so the look is unchanged, and a theme restyles every layout by setting component.*). Applied on every build, once
# per key (a component.* token already in the file is left as it is).
COMPONENTS = {
    # Notification card (Notification Center's platters, banners).
    'component.notification.material': 'comp.nc.platter.material',
    'component.notification.floating-material': 'comp.banner.material',
    'component.notification.corner': 'comp.nc.platter.corner',
    'component.notification.padding': 'comp.nc.platter.padding',
    'component.notification.icon': 'comp.nc.platter.icon',
    'component.notification.title': 'comp.nc.platter.title',
    'component.notification.body': 'comp.nc.platter.body',
    'component.notification.time': 'comp.nc.platter.time',
    'component.notification.label-color': 'comp.nc.platter.label',
    'component.notification.time-color': 'comp.nc.platter.time-color',
    'component.notification.time-blend': 'comp.nc.platter.time-blend',
    'component.notification.gap': 'comp.nc.list.gap',
    # Control (Control Center's modules and toggles).
    'component.control.material': 'comp.cc.module.material',
    'component.control.on-material': 'comp.cc.module.on-material',
    'component.control.corner': 'comp.cc.module.corner',
    'component.control.symbol': 'comp.cc.symbol',
    'component.control.symbol-color': 'comp.cc.symbol-color',
    'component.control.on-symbol-color': 'comp.cc.symbol-on-color',
    'component.control.label-color': 'comp.cc.module.label-color',
    'component.control.detail-color': 'comp.cc.module.detail-color',
    'component.control.detail-blend': 'comp.cc.module.detail-blend',
    'component.control.title': 'comp.cc.module.title',
    'component.control.title-large': 'comp.cc.module.title-large',
    'component.control.detail': 'comp.cc.module.detail',
    'component.control.chevron-color': 'comp.cc.chevron-color',
    'component.control.well-material': 'comp.cc.well.material',
    # Slider.
    'component.slider.corner': 'comp.cc.slider.corner',
    'component.slider.cover': 'comp.cc.slider.cover',
    'component.slider.expanded-corner': 'comp.cc.expanded.slider-corner',
    # Round button (the lock screen's, Control Center's small buttons).
    'component.round-button.material': 'comp.nc.button.material',
    'component.round-button.size': 'comp.nc.button.size',
    'component.round-button.symbol': 'comp.nc.button.symbol',
    'component.round-button.symbol-color': 'comp.nc.button.symbol-color',
    'component.round-button.symbol-blend': 'comp.nc.button.symbol-blend',
    'component.round-button.on-color': 'comp.nc.button.on-color',
    # Button (a capsule with a label: a banner's actions).
    'component.button.fill': 'comp.banner.action.fill',
    'component.button.height': 'comp.banner.action.height',
    'component.button.text': 'comp.banner.action.type',
    # Menu (home's, the App Switcher's).
    **{'component.menu.' + _k: 'comp.home.menu.' + _k for _k in (
        'material', 'corner', 'width', 'row', 'pad-top', 'pad-bottom', 'symbol-x', 'label-x', 'type', 'label', 'destructive',
        'press', 'fallback', 'symbol', 'grow-from')},
    # Badge (the count on icons) and the remove badge (edit mode).
    **{'component.badge.' + _k: 'comp.badge.' + _k for _k in ('fill', 'height', 'label', 'max', 'offset-x', 'offset-y', 'pad-x', 'type')},
    **{'component.remove-badge.' + _k: 'comp.badge.remove.' + _k for _k in ('disc', 'minus', 'radius', 'stroke')},
    # Search field.
    'component.field.material': 'comp.home.field.material',
    'component.field.clear-color': 'comp.home.field.clear-color',
    'component.field.clear-symbol-color': 'comp.home.field.clear-symbol-color',
    # Names over the wallpaper.
    **{'component.wallpaper-label.' + _k: 'comp.home.label.' + _k for _k in ('light', 'dark', 'dark-from', 'dark-full', 'shadow')},
    # Card (widgets, App Library tiles, Spotlight's results), dock, sheet, panel.
    'component.card.material': 'comp.home.widget.material',
    'component.dock.material': 'comp.home.dock.material',
    'component.sheet.material': 'comp.widgets.sheet.material',
    'component.sheet.grabber-color': 'comp.widgets.grabber-color',
    'component.sheet.fallback-color': 'comp.widgets.fallback-color',
    'component.panel.material': 'comp.cc.background.material',
    'component.panel.samsung-strength': 'comp.cc.background.samsung-strength',
}

# Other iOS tokens that are the same component (same value now): aliases of it too.
COMPONENT_ALIASES = {
    'comp.banner.corner': 'component.notification.corner',
    'comp.widgets.card.material': 'component.card.material',
    'comp.library.tile.material': 'component.card.material',
    'comp.spotlight.card.material': 'component.card.material',
}

# New properties of the catalogue (no iOS token before).
COMPONENT_SEEDS = {
    'component.control.accent-symbol-color': {'color': '#ffffff', 'src': "kit:2570:20609 (a symbol on an accent well: white)"},
}


def apply_components(tokens):
    """The catalogue over the theme's tokens: component.* takes the iOS token's entry, the iOS token becomes its alias."""
    for ck, src in COMPONENTS.items():
        if ck in tokens:
            continue
        if src not in tokens:
            raise SystemExit(f'{ck}: its source {src} is not in the theme')
        tokens[ck] = tokens[src]
        tokens[src] = {'ref': ck, 'src': "judged:the shared component style (" + ck + ")"}
    for ak, ck in COMPONENT_ALIASES.items():
        v = tokens.get(ak)
        if v is not None and v.get('ref') != ck:
            tokens[ak] = {'ref': ck, 'src': "judged:the shared component style (" + ck + ")"}
    for k, v in COMPONENT_SEEDS.items():
        tokens.setdefault(k, v)


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
    apply_components(tokens)
    tokens = dict(sorted(tokens.items(), key=lambda kv: (not kv[0].startswith('ref.'), kv[0])))
    theme = {'format': 1, 'name': 'iOS 27', 'author': 'Launcher (from Apple\'s iOS 27 UI kit)', 'version': '0.1',
             '_about': 'ref.* is generated by tools/design/build_ios27_theme.py from docs/tokens/ios27-kit.json; sys.* and comp.* are written here.',
             'tokens': tokens,
             'materialYou': dict(sorted(MATERIAL_YOU.items()))}
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(theme, f, indent=1, ensure_ascii=False)
        f.write('\n')
    print(f'{OUT}: {len(ref)} ref tokens, {len(kept)} sys/comp tokens')


if __name__ == '__main__':
    main()
