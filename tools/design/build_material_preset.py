#!/usr/bin/env python3
"""
Builds the "Material" animation preset (app/src/main/assets/motion/material.json) from the iOS 27 preset: Android 16's
motion, Material 3 Expressive's springs (androidx's ExpressiveMotionTokens, v0_14_0). Every role takes the spring of its
kind (spatial: places, sizes, scales; effects: fades, colours, blur) and its size (fast: small parts, presses, toggles;
default: menus, folders, banners, panels' contents; slow: apps and whole-screen motion):

    spatial  fast  damping 0.6, stiffness 800     effects  fast     damping 1, stiffness 3800
             default       0.8,           380              default             1,           1600
             slow          0.8,           200              slow                1,            800

A spatial spring bounces (Expressive's damping) only where iOS 27's does visibly (damping 0.86 or less, or a bezier past
its ends): code that never saw an overshoot is never handed one (a fade drawn with an int alpha, a panel whose edge would
show a gap), and those take the stiffness of their size critically damped. Timing curves that hand one picture over to
another (a card fading into the app, the reveal of a new look) stay iOS 27's. Only what differs from iOS 27 is written;
run again after the iOS 27 preset changes (docs/PLAN_MOTION.md, C4a).

    python tools/design/build_material_preset.py
"""
import json
import math
import os

ROOT = os.path.join(os.path.dirname(__file__), '..', '..')
BASE = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'motion', 'ios27.json')
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'motion', 'material.json')

# androidx.compose.material3.tokens.ExpressiveMotionTokens (v0_14_0): (damping, stiffness).
SPATIAL = {'fast': (0.6, 800.0), 'default': (0.8, 380.0), 'slow': (0.8, 200.0)}
EFFECTS = {'fast': (1.0, 3800.0), 'default': (1.0, 1600.0), 'slow': (1.0, 800.0)}

# Every curve role: its kind and size.
ROLES = {
    # Apps opening and closing
    'motion.app.open': ('spatial', 'slow'),
    'motion.app.close-position': ('spatial', 'slow'),
    'motion.app.close-size': ('spatial', 'slow'),
    'motion.app.cancel': ('spatial', 'default'),
    'motion.home.depth-open': ('spatial', 'slow'),
    'motion.home.depth-close': ('spatial', 'slow'),
    'motion.switch.commit': ('spatial', 'slow'),
    'motion.switch.cancel': ('spatial', 'default'),
    # App Switcher
    'motion.switcher.enter': ('spatial', 'slow'),
    'motion.switcher.scroll': ('spatial', 'default'),
    'motion.switcher.open': ('spatial', 'slow'),
    'motion.switcher.home': ('spatial', 'slow'),
    'motion.switcher.flick': ('spatial', 'default'),
    'motion.switcher.reflow': ('spatial', 'default'),
    'motion.switcher.clear-press': ('spatial', 'fast'),
    # Home
    'motion.home.page-snap': ('spatial', 'default'),
    'motion.drawer': ('spatial', 'slow'),
    'motion.folder.open': ('spatial', 'default'),
    'motion.folder.close': ('spatial', 'default'),
    'motion.arrival': ('spatial', 'slow'),
    'motion.arrival.wallpaper': ('spatial', 'slow'),
    'motion.appear': ('spatial', 'default'),
    'motion.appear.fade': ('effects', 'default'),
    'motion.disappear.fade': ('effects', 'default'),
    'motion.icon.press-in': ('effects', 'fast'),
    'motion.icon.press-out': ('effects', 'default'),
    'motion.home.label-tone': ('effects', 'slow'),
    'motion.home.pill-hide': ('effects', 'default'),
    'motion.home.pill-show': ('effects', 'default'),
    'motion.home.pill-edit': ('effects', 'default'),
    'motion.clock.tick': ('effects', 'slow'),
    'motion.clock.appear': ('effects', 'default'),
    'motion.wallpaper.appear': ('effects', 'slow'),
    'motion.wallpaper.crossfade': ('effects', 'slow'),
    'motion.home.rebuild-settle': ('spatial', 'default'),
    # Editing home
    'motion.edit.bar': ('spatial', 'default'),
    'motion.edit.drag-lift': ('spatial', 'fast'),
    'motion.edit.drag-settle': ('spatial', 'default'),
    'motion.edit.reflow': ('spatial', 'default'),
    'motion.edit.jiggle-stop': ('spatial', 'fast'),
    'motion.edit.press-in': ('spatial', 'fast'),
    'motion.edit.press-out': ('spatial', 'fast'),
    'motion.widget.resize': ('spatial', 'default'),
    'motion.widget.layout-fade': ('effects', 'default'),
    # Menus, sheets and search
    'motion.menu.open': ('spatial', 'default'),
    'motion.menu.close': ('spatial', 'default'),
    'motion.alert.open': ('spatial', 'default'),
    'motion.alert.close': ('effects', 'default'),
    'motion.sheet': ('spatial', 'slow'),
    'motion.sheet.push': ('spatial', 'default'),
    'motion.settings.open': ('spatial', 'default'),
    'motion.settings.focus': ('spatial', 'slow'),
    'motion.mode-crossfade': ('effects', 'default'),
    'motion.index.scroll': ('spatial', 'default'),
    'motion.index.bubble-in': ('spatial', 'fast'),
    'motion.index.bubble-out': ('spatial', 'fast'),
    'motion.index.follow': ('spatial', 'fast'),
    'motion.field.clear-in': ('spatial', 'fast'),
    'motion.field.clear-out': ('spatial', 'fast'),
    'motion.widgets.preview': ('effects', 'default'),
    # Scrolling
    'motion.scroll.overscroll-return': ('spatial', 'default'),
    # Control Center
    'motion.cc.open': ('spatial', 'slow'),
    'motion.cc.close': ('spatial', 'default'),
    'motion.cc.close-style': ('effects', 'fast'),
    'motion.cc.settle': ('spatial', 'default'),
    'motion.cc.stretch-back': ('spatial', 'default'),
    'motion.cc.press-in': ('spatial', 'fast'),
    'motion.cc.press-out': ('spatial', 'fast'),
    'motion.cc.toggle': ('effects', 'default'),
    'motion.cc.level': ('spatial', 'default'),
    'motion.cc.expanded.open': ('spatial', 'default'),
    'motion.cc.expanded.close': ('spatial', 'default'),
    'motion.cc.expanded.level': ('spatial', 'default'),
    'motion.cc.edit': ('spatial', 'default'),
    'motion.cc.reflow': ('spatial', 'default'),
    'motion.cc.resize': ('spatial', 'default'),
    'motion.cc.appear': ('spatial', 'default'),
    'motion.cc.leave': ('spatial', 'fast'),
    'motion.cc.lift': ('spatial', 'fast'),
    'motion.cc.drop': ('spatial', 'default'),
    'motion.cc.gallery.open': ('spatial', 'slow'),
    'motion.cc.gallery.close': ('spatial', 'default'),
    # Notifications
    'motion.nc.open': ('spatial', 'slow'),
    'motion.nc.close': ('spatial', 'default'),
    'motion.nc.appear': ('spatial', 'default'),
    'motion.nc.leave': ('spatial', 'default'),
    'motion.nc.reflow': ('spatial', 'default'),
    'motion.nc.swipe-back': ('spatial', 'default'),
    'motion.nc.swipe-out': ('spatial', 'default'),
    'motion.nc.menu-open': ('spatial', 'default'),
    'motion.nc.menu-close': ('spatial', 'default'),
    'motion.nc.confirm': ('spatial', 'fast'),
    'motion.nc.hold': ('spatial', 'fast'),
    'motion.nc.toggle': ('effects', 'default'),
    'motion.nc.clock-tick': ('effects', 'slow'),
    'motion.nc.time-fade': ('effects', 'default'),
    'motion.press-in': ('spatial', 'fast'),
    'motion.press-out': ('spatial', 'fast'),
    # Banners
    'motion.banner.in': ('spatial', 'default'),
    'motion.banner.out': ('spatial', 'default'),
    'motion.banner.fly': ('spatial', 'default'),
    'motion.banner.fly-fade': ('effects', 'default'),
    'motion.banner.back': ('spatial', 'default'),
    'motion.banner.resize': ('spatial', 'default'),
    # Status bar and gesture bar
    'motion.statusbar.move': ('spatial', 'fast'),
    'motion.statusbar.appear': ('spatial', 'fast'),
    'motion.statusbar.leave': ('spatial', 'fast'),
    'motion.statusbar.level': ('spatial', 'default'),
    'motion.statusbar.roll': ('spatial', 'fast'),
    'motion.statusbar.hide': ('effects', 'default'),
    'motion.statusbar.show': ('effects', 'default'),
    'motion.statusbar.tone': ('effects', 'default'),
    'motion.statusbar.lock': ('effects', 'default'),
    'motion.statusbar.charge-open': ('spatial', 'fast'),
    'motion.statusbar.charge-content': ('spatial', 'default'),
    'motion.statusbar.charge-close': ('spatial', 'default'),
    'motion.strip.hide': ('effects', 'default'),
    'motion.strip.show': ('effects', 'default'),
}

# Timing curves that hand one picture over to another, or were tuned on the phone as a whole: iOS 27's.
KEEP = {
    'motion.card.color', 'motion.card.snapshot', 'motion.card.catch-up', 'motion.card.fade-home', 'motion.card.fade-app',
    'motion.arrival.hold', 'motion.wallpaper.reveal', 'motion.home.rebuild', 'motion.settings.hint', 'motion.widgets.row',
    'motion.cc.backdrop-hold', 'motion.nc.launch-fade', 'motion.banner.app-fade', 'motion.appearance.change',
    'motion.statusbar.charge-shimmer',
    # Android's shade's own (already Android's: read from SystemUIGoogle).
    'motion.qs.open', 'motion.qs.close', 'motion.qs.expand', 'motion.qs.collapse', 'motion.qs.tile-bounce', 'motion.qs.tile-state',
    'motion.qs.tile-pop', 'motion.qs.tile-pop-back', 'motion.qs.slider-icon', 'motion.qs.edit', 'motion.qs.edit-place',
    'motion.qs.edit-appear', 'motion.qs.media',
}

# iOS 27's spring bounces visibly at this damping or below (0.5 % past its place).
VISIBLE_BOUNCE = 0.86


def response(stiffness):
    """The spring's response (s): its undamped period, 2 pi / sqrt(stiffness) for a unit mass."""
    return round(2 * math.pi / math.sqrt(stiffness), 4)


def bounces(v):
    if 'spring' in v:
        return v['spring'][1] <= VISIBLE_BOUNCE
    if 'bezier' in v:
        return any(y < 0 or y > 1 for y in (v['bezier'][1], v['bezier'][3]))
    return False


def main():
    base = json.load(open(BASE, encoding='utf-8'))['tokens']
    curves = {k for k, v in base.items() if 'spring' in v or 'bezier' in v}
    missing = curves - set(ROLES) - KEEP
    stray = (set(ROLES) | KEEP) - curves
    if missing or stray:
        raise SystemExit(f'not classified: {sorted(missing)}; not curves in iOS 27: {sorted(stray)}')
    out = {}
    for k in sorted(ROLES):
        kind, size = ROLES[k]
        if kind == 'effects':
            damping, stiffness = EFFECTS[size]
            why = f'effects, {size}'
        else:
            damping, stiffness = SPATIAL[size]
            why = f'spatial, {size}'
            if not bounces(base[k]):
                damping = 1.0
                why += ', critically damped: iOS 27 does not overshoot here'
        out[k] = {'spring': [response(stiffness), damping],
                  'src': f"judged:Material 3 Expressive's {why} spring (stiffness {stiffness:g}, damping {damping:g})"}
    # Material 3's container transform: the icon's box becomes the window and back, contents fading through.
    for k, why in (('motion.style.opening', 'opening'), ('motion.style.closing', 'going home')):
        out[k] = {'choice': 'container', 'src': f"judged:Material 3's container transform for {why} (docs/MOTION_STYLES.md)"}
    preset = {'format': 1, 'name': 'Material', 'description': "Android 16's springs",
              'author': "Launcher (Material 3 Expressive's motion tokens, androidx v0_14_0)", 'version': '0.1', 'extends': 'ios27',
              '_about': "Android 16's motion on iOS 27's roles: Material 3 Expressive's springs by each role's kind and size, a "
                        "bounce only where iOS 27 has one. Written by tools/design/build_material_preset.py.",
              'tokens': out}
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(preset, f, indent=1, ensure_ascii=False)
        f.write('\n')
    bouncy = sum(1 for v in out.values() if 'spring' in v and v['spring'][1] < 1)
    print(f'{OUT}: {len(out)} roles ({bouncy} bounce), {len(KEEP)} kept as iOS 27')


if __name__ == '__main__':
    main()
