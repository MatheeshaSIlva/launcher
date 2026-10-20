#!/usr/bin/env python3
"""
Builds the Pixel theme (app/src/main/assets/themes/pixel.json): Android's Material 3 Expressive look as a theme on iOS 27's,
holding only what differs. Step 1 of docs/SHADE_PIXEL_EVALUATION.md: the shade as it is (Notification Center, Control
Center, banners) dressed in Pixel's look by tokens alone, no code, to see how far the theme engine reaches. Its colours are
the phone's palette by role name (Pixel always draws in Material You), so it follows the wallpaper as a Pixel does.

Sources: AOSP's SystemUI and framework (main, read 2026-10-19: CommonTile.kt, Tile.kt, PlatformSlider.kt, SystemUI and
core dimens.xml), the Android 17 emulator's own shade captured on 2026-10-19 (tools/shots/shade_qs.png, shade_qqs.png;
measured against the emulator's palette), and judgements where Pixel has no counterpart for an iOS part.

    python tools/design/build_pixel_theme.py
"""
import json
import os
import re

ROOT = os.path.join(os.path.dirname(__file__), '..', '..')
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'themes', 'pixel.json')
IOS = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'themes', 'ios27.json')
CONTROLS = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'dev', 'launcher', 'app', 'shade', 'Controls.kt')

FONT = 'google:Google Sans Flex'
AOSP_TILE = 'kit:AOSP SystemUI qs/panels/ui/compose/infinitegrid/CommonTile.kt'
AOSP_TILE_STATE = 'kit:AOSP SystemUI qs/panels/ui/compose/infinitegrid/Tile.kt'
CAPTURE = 'measured:Android 17 emulator shade, 2026-10-19 (tools/shots/shade_qs.png, shade_qqs.png) against its palette'


def text(size, line, weight, tracking, src):
    return {'text': {'family': FONT, 'weight': weight, 'size': size, 'line': line, 'tracking': tracking}, 'src': src}


def material(fills, frost=0, src=''):
    return {'material': {'frost': frost, 'fills': fills, 'innerShadows': [], 'shadows': []}, 'src': src}


def color(c, src):
    return {'color': c, 'src': src} if '|' not in c else {'light': c.split('|')[0], 'dark': c.split('|')[1], 'src': src}


def pt(v, src):
    return {'pt': v, 'src': src}


T = {
    # The fonts: Google Sans Flex, Pixel's own (on Google Fonts since 2025), for every text the theme names.
    'sys.font.text': {'choice': FONT, 'src': 'judged:Pixel draws its system UI in Google Sans Flex (Material 3 Expressive)'},
    'sys.font.display': {'choice': FONT, 'src': 'judged:Pixel draws its system UI in Google Sans Flex (Material 3 Expressive)'},
    # Android's own Quick Settings symbols (assets/icons/pixel.json, tools/icons/build_icon_set.py).
    'sys.symbols': {'choice': 'pixel', 'src': "kit:AOSP SystemUI's Quick Settings icons, each as its tile names it (qs/tiles/*.java)"},
    # A Pixel always draws in the wallpaper's colours.
    'sys.color.source': {'choice': 'wallpaper', 'src': "judged:a Pixel always takes the wallpaper's colours (Material You)"},

    # ---- What covers the screen behind the controls (Quick Settings' veil over a blur).
    'component.panel.material': material([['@surface_container_lowest', [0.55, 0.72], 'NORMAL']], frost=48,
        src="measured:the shade's veil over a blur, about 55 % light in light and 72 % dark in dark (docs/PIXEL_PROFILE.md, the captures); heavy enough that home's icons are soft blobs (frost 30 left the shape of home's large clock)"),

    # ---- Quick Settings tiles (our controls).
    'component.control.material': material([['@surface_container_lowest|@surface_container_highest', [0.45, 0.55], 'NORMAL']],
        src=CAPTURE + ': a tile that is off is a light layer of about 45 % over the veil (light); dark judged alike'),
    'component.control.on-material': material([['@primary', 1.0, 'NORMAL']],
        src=AOSP_TILE_STATE + ': activeTileColors, background primary (the capture: exactly the palette\'s primary)'),
    # A tile's shape: a capsule when off, a rounded square when on, changing as it turns on (its symbol's box alike).
    'component.control.row-corner': pt(50, AOSP_TILE + ': InactiveCornerRadius 50 dp, a capsule on a 72 dp tile'),
    'component.control.row-on-corner': pt(24, AOSP_TILE_STATE + ': ActiveTileCornerRadius 24 dp'),
    'component.control.well-corner': pt(50, AOSP_TILE + ': the symbol box takes InactiveCornerRadius 50 dp when off, a circle on 56 dp'),
    'component.control.well-on-corner': pt(16, AOSP_TILE_STATE + ': ActiveIconCornerRadius 16 dp'),
    'component.control.corner': pt(28, "kit:AOSP SystemUI dimens.xml qs_corner_radius 28 dp (a Pixel has no control larger than a tile: Quick Settings' own corner for iOS's modules)"),
    'component.control.symbol': pt(26, CAPTURE + ': a small tile\'s symbol about 25 dp across (CommonTileDefaults.IconSize 32 dp with its padding)'),
    'component.control.symbol-color': color('@on_surface_variant', AOSP_TILE_STATE + ': inactiveTileColors, icon onSurfaceVariant'),
    'component.control.on-symbol-color': color('@on_primary', AOSP_TILE_STATE + ': activeTileColors, icon onPrimary'),
    'component.control.accent-symbol-color': color('@on_primary', AOSP_TILE_STATE + ': activeTileColors, icon onPrimary'),
    'component.control.label-color': color('@on_surface_variant', AOSP_TILE_STATE + ': inactiveTileColors, label onSurfaceVariant (on, onPrimary: no token for a name when on yet)'),
    'component.control.detail-color': color('@on_surface_variant', AOSP_TILE_STATE + ': secondaryLabel onSurfaceVariant'),
    'component.control.detail-blend': {'choice': 'NORMAL', 'src': 'judged:Material draws words plainly (no vibrancy)'},
    'component.control.chevron-color': color('@on_surface_variant', 'judged:as the state\'s words'),
    'component.control.title': text(14, 20, 500, 0.1, AOSP_TILE + ': LargeTileLabels, labelLarge (14/20, 500, 0.1)'),
    'component.control.title-large': text(16, 24, 500, 0.15, 'judged:titleMedium (16/24, 500) for the larger names'),
    'component.control.detail': text(14, 20, 400, 0.25, AOSP_TILE + ': secondary label, bodyMedium (14/20, 400, 0.25)'),
    'component.control.well-material': material([['@surface_container_highest', 1.0, 'NORMAL']],
        src=AOSP_TILE_STATE + ': inactiveDualTargetTileColors, iconBackground surfaceContainerHighest'),

    # The grid: four tiles as wide as the screen allows 16 dp in from each side, 72 dp tall, 8 dp between them.
    'comp.cc.grid.cell': pt(72, AOSP_TILE + ": TileHeight 72 dp (a cell's width where the screen is not filled: landscape)"),
    'comp.cc.grid.cell-height': pt(72, AOSP_TILE + ': TileHeight 72 dp'),
    'comp.cc.grid.side': pt(16, 'kit:AOSP SystemUI dimens.xml qs_horizontal_margin 16 dp (notification_side_paddings): tiles fill the width between'),
    'comp.cc.grid.gap': pt(8, 'kit:AOSP SystemUI dimens.xml qs_tile_margin_horizontal / _vertical 8 dp'),
    # A large tile: its symbol in a 56 dp box 8 dp in, its words 6 dp after it.
    'comp.cc.well.size': pt(56, AOSP_TILE + ': ToggleTargetSize 56 dp'),
    'comp.cc.well.symbol': pt(24, AOSP_TILE + ': LargeTileIconSize 28 dp with its padding, about 24 dp drawn'),
    'comp.cc.wide.padding': pt(8, AOSP_TILE + ': TilePadding 8 dp'),
    'comp.cc.wide.gap': pt(6, AOSP_TILE + ': TileArrangementPadding 6 dp'),
    # Every tile lights in the palette's primary (no colour per control on a Pixel).
    # (comp.cc.accent.* filled in below, one per control iOS 27 colours.)

    # Sliders (brightness, volume): a pill, its level in primary (the on surface above).
    'component.slider.corner': pt(36, 'kit:AOSP compose/core PlatformSlider.kt: the track rounded by half its size (a pill) on a 72 dp module'),
    'component.slider.expanded-corner': pt(40, 'judged:the opened slider as a pill too'),

    # "+" and power at the top of Control Center (on a Pixel: settings and power at the foot, round, 48 dp).
    'comp.cc.button.material': material([['@surface_container_highest', [0.85, 0.85], 'NORMAL']],
        src='judged:as Quick Settings\' round footer buttons (qs_footer_action_button_size 48 dp), surfaceContainerHighest'),
    'comp.cc.button.symbol-color': color('@on_surface_variant', 'judged:as the footer buttons\' symbols'),
    'comp.cc.button.size': pt(34, 'judged:larger than iOS 27\'s (Pixel\'s are 48 dp; kept within the status row)'),
    'comp.cc.button.symbol': pt(18, 'judged:as iOS 27\'s'),

    # Now Playing.
    'comp.cc.media.text-color': color('@on_surface_variant', 'judged:Material\'s secondary words'),
    'comp.cc.media.text-blend': {'choice': 'NORMAL', 'src': 'judged:Material draws words plainly'},
    'comp.cc.media.title': text(14, 20, 500, 0.1, 'judged:labelLarge, as the tiles\' names'),
    'comp.cc.media.placeholder-color': color('@surface_container_highest', 'judged:an empty cover in surfaceContainerHighest'),
    'comp.cc.media.track-color': color('@outline_variant', 'judged:a track in outlineVariant'),
    'comp.cc.media.progress-color': color('@primary', 'judged:progress in primary'),
    'comp.cc.media.level-color': color('@primary', 'judged:the level in primary'),
    'comp.cc.media.secondary-color': color('@on_surface_variant', 'judged:Material\'s secondary words'),

    # Editing Control Center and its gallery ("Add a Control"): Material's surfaces and words.
    'comp.cc.add.fill-color': color('@primary', 'judged:the add badge in primary'),
    'comp.cc.edit.badge-color': color('@surface_container_highest', 'judged:the remove badge on surfaceContainerHighest'),
    'comp.cc.edit.badge-minus-color': color('@on_surface_variant', 'judged:its minus in onSurfaceVariant'),
    'comp.cc.edit.handle-color': color('@primary', 'judged:the resize handle in primary (Pixel\'s editor marks the selected tile in primary)'),
    'comp.cc.gallery.sheet': material([['@surface_container', 1.0, 'NORMAL']], src='judged:the gallery as a Material sheet (surfaceContainer)'),
    'comp.cc.gallery.entry': material([['@surface_container_highest', 1.0, 'NORMAL']], src='judged:entries as tiles that are off'),
    'comp.cc.gallery.dim': color('#00000052', 'judged:Material\'s scrim (32 % black)'),
    'comp.cc.gallery.corner': pt(28, 'judged:a Material sheet\'s top corners (28 dp)'),
    'comp.cc.gallery.grabber-color': color('@on_surface_variant', 'judged:Material\'s drag handle'),
    'comp.cc.gallery.heading-color': color('@on_surface', 'judged:Material\'s words'),
    'comp.cc.gallery.section-color': color('@on_surface_variant', 'judged:Material\'s secondary words'),
    'comp.cc.gallery.name-color': color('@on_surface_variant', 'judged:Material\'s secondary words'),
    'comp.cc.menu.label': color('@on_surface', 'judged:Material\'s menu words'),
    'comp.cc.menu.press': color('@on_surface/12', 'judged:Material\'s pressed state (12 %)'),

    # ---- Notifications (the 2025 template).
    'component.notification.material': material([['@surface_container_high', 1.0, 'NORMAL']],
        src=CAPTURE + ': cards opaque in surfaceContainerHigh (#e7e6ef against the palette\'s #e7e7f1)'),
    'component.notification.floating-material': material([['@surface_container_high', 1.0, 'NORMAL']],
        src='judged:a heads-up notification is the same opaque card'),
    'component.notification.corner': pt(28, 'kit:AOSP SystemUI dimens.xml notification_corner_radius 28 dp (between joined cards 4 dp: no token for that yet)'),
    'component.notification.padding': pt(16, 'kit:AOSP core dimens.xml notification_2025_margin 16 dp'),
    'component.notification.icon': pt(40, 'kit:AOSP core dimens.xml notification_2025_icon_circle_size 40 dp'),
    'component.notification.title': text(16, 24, 500, 0.15, 'kit:AOSP core dimens.xml notification_big_title_text_size 16 sp (the 2025 template as captured), titleMedium'),
    'component.notification.body': text(14, 20, 400, 0.25, 'kit:AOSP core dimens.xml notification_text_size 14 sp, bodyMedium'),
    'component.notification.time': text(12, 16, 400, 0.4, 'kit:AOSP core dimens.xml notification_subtext_size 12 sp, bodySmall'),
    'component.notification.label-color': color('@on_surface', 'judged:Material\'s words on a card (onSurface)'),
    'component.notification.time-color': color('@on_surface_variant', 'judged:Material\'s secondary words'),
    'component.notification.time-blend': {'choice': 'NORMAL', 'src': 'judged:Material draws words plainly'},
    'component.notification.gap': pt(2, CAPTURE + ': a section\'s cards joined about 2 dp apart'),
    'comp.nc.list.margin': pt(16, 'kit:AOSP SystemUI dimens.xml notification_side_paddings 16 dp'),
    'comp.nc.platter.text-x': pt(72, 'kit:AOSP core dimens.xml notification_2025_content_margin_start 72 dp'),
    'comp.nc.platter.text-top': pt(16, 'kit:AOSP core dimens.xml notification_2025_margin 16 dp'),
    'comp.nc.platter.min-height': pt(72, 'kit:AOSP core dimens.xml notification_2025_min_height 72 dp'),
    'comp.nc.stack.front-text-top': pt(16, 'judged:as a single card'),
    'comp.nc.stack.front-min-height': pt(72, 'judged:as a single card'),
    # A group shows as one card (no shelves peeking below it, as a Pixel's collapsed group), its count on a chip.
    'comp.nc.stack.shelf-show': pt(0, 'judged:a Pixel\'s collapsed group is one card with a count, no shelves below it'),
    'comp.nc.count.fill': color('@primary', 'judged:the group\'s count on a primary chip'),
    'comp.nc.count.label': color('@on_primary', 'judged:the count in onPrimary'),
    'comp.nc.count.type': text(12, 16, 500, 0.5, 'judged:labelMedium'),
    # What covers the wallpaper under the list: the shade's veil over a blur.
    'comp.nc.overlay.material': material([['@surface_container_lowest', [0.55, 0.72], 'NORMAL']], frost=30,
        src='measured:the shade\'s veil over a blur, as Quick Settings\' (docs/PIXEL_PROFILE.md, the captures)'),
    'comp.nc.header-color': color('@on_surface', 'judged:Material\'s words on the veil'),
    'comp.nc.text-shadow-color': color('#00000000', 'judged:Material puts no shadow under words'),
    'comp.nc.media.placeholder-color': color('@surface_container_highest', 'judged:an empty cover in surfaceContainerHighest'),
    'comp.nc.media.track-color': color('@outline_variant', 'judged:a track in outlineVariant'),
    # The long look (a notification held) and its menu.
    'comp.nc.look.card-color': color('@surface_container_high', 'judged:the held card as the cards'),
    'comp.nc.look.card-corner': pt(28, 'kit:AOSP SystemUI dimens.xml notification_corner_radius 28 dp'),
    'comp.nc.look.dim': color('#00000052', 'judged:Material\'s scrim (32 % black)'),
    'comp.nc.look.label': color('@on_surface', 'judged:Material\'s words'),
    'comp.nc.menu.material': material([['@surface_container', 1.0, 'NORMAL']], src='judged:a Material menu (surfaceContainer)'),
    'comp.nc.menu.label': color('@on_surface', 'judged:Material\'s menu words'),
    'comp.nc.menu.press': color('@on_surface/12', 'judged:Material\'s pressed state (12 %)'),
    'comp.nc.menu.corner': pt(16, 'judged:Material 3 Expressive\'s menu corner'),
    'comp.nc.menu.type': text(14, 20, 500, 0.1, 'judged:labelLarge, a menu\'s items'),
    # The torch and camera buttons of the cover sheet (a Pixel has none: drawn as Material's round buttons).
    'component.round-button.material': material([['@surface_container_high', 1.0, 'NORMAL']], src='judged:Material\'s round button (surfaceContainerHigh)'),
    'component.round-button.symbol-color': color('@on_surface_variant', 'judged:Material\'s symbol colour'),
    'component.round-button.symbol-blend': {'choice': 'NORMAL', 'src': 'judged:Material draws symbols plainly'},
    'component.round-button.on-color': color('@primary', 'judged:lit in primary'),
    'component.round-button.size': pt(48, 'judged:Material\'s 48 dp round button'),
    'component.round-button.symbol': pt(24, 'judged:Material\'s 24 dp symbol'),

    # ---- Banners (heads-up notifications): opaque cards, no live blur.
    'comp.banner.system-blur': {'factor': 0, 'src': 'judged:a Pixel\'s heads-up notification is an opaque card (no blur behind it)'},
    'comp.banner.flat-color': color('@surface_container_high', 'judged:the card\'s colour'),
    'comp.banner.label': color('@on_surface', 'judged:Material\'s words on a card'),
    'comp.banner.secondary': color('@on_surface_variant', 'judged:Material\'s secondary words'),
    # A notification's actions: Material's filled tonal buttons.
    'component.button.fill': color('@secondary_container', 'judged:Material\'s filled tonal button (secondaryContainer)'),
    'component.button.height': pt(40, 'judged:Material 3 Expressive\'s small button (40 dp)'),
    'component.button.text': text(14, 20, 500, 0.1, 'judged:labelLarge'),

    # ---- Android's shade (comp.qs.*: its sizes are in iOS 27's already; here its words and colours as a Pixel's).
    'comp.qs.header.clock': text(36, 44, 400, 0, 'kit:AOSP SystemUI shade header: displaySmall (36/44)'),
    'comp.qs.header.date': text(14, 20, 500, 0.1, 'kit:AOSP SystemUI shade header: titleSmall (14/20, 500)'),
    'comp.qs.header.carrier': text(14, 20, 500, 0.1, 'kit:AOSP SystemUI shade header: titleSmall (14/20, 500)'),
    'comp.qs.header.color': color('@on_surface', 'kit:AOSP SystemUI shade header: onSurface'),
    'comp.qs.brightness.track': material([['@secondary_container', 1.0, 'NORMAL']], src='kit:AOSP compose PlatformSlider: the inactive track in secondaryContainer'),
    'comp.qs.brightness.level': material([['@primary', 1.0, 'NORMAL']], src='kit:AOSP compose PlatformSlider: the active track in primary'),
    'comp.qs.brightness.symbol-color': color('@on_primary', 'kit:AOSP compose PlatformSlider: the symbol on the active track in onPrimary'),
    'comp.qs.build.text': text(12, 16, 400, 0.4, 'kit:AOSP SystemUI: the build number in bodySmall'),
    'comp.qs.build.color': color('@on_surface_variant', 'kit:AOSP SystemUI: the build number in onSurfaceVariant'),
    'comp.qs.dots.color': color('@on_surface_variant/38', 'judged:the other pages\' dots faint'),
    'comp.qs.dots.active-color': color('@on_surface_variant', 'judged:the current page\'s pill'),
    'comp.qs.cards.shelf': material([['@surface_container_lowest|@surface_container_highest', [0.3, 0.06], 'NORMAL']],
        src=CAPTURE + ': the notifications lie on a lighter layer (light: (171, 209, 236) over (141, 189, 218); dark: a faint lift)'),
    'comp.qs.card.action-outline': color('@outline', 'kit:AOSP notification: actions as outlined buttons in outline'),
    'comp.qs.card.icon-fill': color('@primary', CAPTURE + ': an app\'s small symbol in a circle of primary'),
    'comp.qs.card.icon-color': color('@on_primary', CAPTURE + ': the symbol in onPrimary'),
    'comp.qs.card.app': text(14, 20, 400, 0.25, 'kit:AOSP notification 2025 template: the header line in bodyMedium'),
    'comp.qs.card.title-color': color('@on_surface', 'kit:AOSP notification: the title in onSurface'),
    'comp.qs.card.text-color': color('@on_surface_variant', 'kit:AOSP notification: the text in onSurfaceVariant'),
    'comp.qs.card.chip-fill': color('@surface_container_highest', CAPTURE + ': a group\'s count chip in surfaceContainerHighest'),
    'comp.qs.card.chip': text(12, 16, 500, 0.5, 'kit:AOSP notification: the count in labelMedium'),
    'comp.qs.section.text': text(14, 20, 500, 0.1, 'kit:AOSP notification section header: titleSmall'),
    'comp.qs.section.color': color('@on_surface_variant', 'kit:AOSP notification section header: onSurfaceVariant'),
    'comp.qs.clear.fill': color('@surface_container_high', CAPTURE + ': Clear all on a card\'s colour'),
    'comp.qs.clear.text': text(14, 20, 500, 0.1, 'kit:AOSP notification footer: labelLarge'),
    'comp.qs.clear.text-color': color('@on_surface', 'kit:AOSP notification footer: onSurface'),
}


def colour_controls():
    """The controls that fill with their accent when on (Style.COLOR in Controls.kt), as accent token names."""
    out = set()
    for line in open(CONTROLS, encoding='utf-8'):
        m = re.match(r'\s*([A-Z_]+)\(".*Style\.COLOR\)', line)
        if m:
            out.add('comp.cc.accent.' + m.group(1).lower().replace('_', '-'))
    return out


def main():
    ios = json.load(open(IOS, encoding='utf-8'))['tokens']
    # A control's accent is its fill when on for the colour controls (primary on a Pixel), and its symbol's colour when on
    # for the others (lit in the on surface, primary here: so onPrimary, or the symbol vanished into it on the phone).
    filled = colour_controls()
    for k in sorted(ios):
        if k.startswith('comp.cc.accent.'):
            if k in filled:
                T[k] = color('@primary', AOSP_TILE_STATE + ': every active tile in primary (no colour per control)')
            else:
                T[k] = color('@on_primary', AOSP_TILE_STATE + ": activeTileColors, icon onPrimary (this accent is the lit symbol's colour)")
    missing = [k for k in T if k not in ios]
    if missing:
        raise SystemExit('not tokens of iOS 27 (the theme would be refused): ' + ', '.join(missing))
    theme = {
        'format': 1,
        'name': 'Pixel',
        'description': "Android's Material 3 Expressive, so far for the shade (notifications, controls, banners)",
        'author': 'Launcher',
        'version': '0.1',
        'extends': 'ios27',
        '_about': "Pixel's look as a theme on iOS 27's (docs/SHADE_PIXEL_EVALUATION.md, step 1): written by "
                  "tools/design/build_pixel_theme.py from AOSP's numbers and captures of the Android 17 emulator's shade. "
                  "Only what differs from iOS 27 is written here; its colours are the phone's palette by role, as a Pixel's.",
        'tokens': T,
    }
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(theme, f, indent=1, ensure_ascii=False)
        f.write('\n')
    print(f'{OUT}: {len(T)} tokens')


if __name__ == '__main__':
    main()
