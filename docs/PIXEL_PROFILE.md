# The Pixel profile (Android 16 as a Pixel shows it)

> **Status (2026-10-10): withdrawn from `main`, kept for reference.** This first run made "Pixel" a theme that also
> switched every layout; on the S24 it was buggy and broke iOS after switching back. Its code is on branch `pixel-v1`
> (parts 1-8, up to `9920d65`); `main` went back to `5339fa9`. The new direction (layouts, themes and animations as three
> separate layers) is `docs/PLAN_LAYOUTS_THEMES.md`. The measurements and reference captures below stay valid for the
> Pixel layouts and theme built under that plan.

Matheesha (2026-10-09): "let's try with the Pixel OS theme first. Not just the colours but the search, QS, notifications,
recents, dock, App Library, everything." A theme (`assets/themes/pixel.json`, built on iOS 27) plus Android 16's own
layouts where they differ from iOS's, chosen by layout tokens (`sys.layout.*`). Any theme may pick any layout; the Pixel
theme picks Android's.

## Reference

Captured on the Android 17 emulator's own launcher (Pixel Launcher, `com.google.android.apps.nexuslauncher`) and SystemUI,
our app disabled for the captures: `tools/shots/pxref/` (git-ignored; screenshots in dark and light, UI dumps with the
bounds of every element, the system palette in `palette.txt`). Display: 1080 x 2400, 420 dpi (2.625 px per dp).

| Surface | Reference | What it is |
| --- | --- | --- |
| Shade, first pull | `shade1.png` | Status row (time, date), two rows of tiles (QQS), the notification list on a lighter rounded area: cards 16 dp in, an ongoing one with outlined action buttons, groups with a count chip, a "Silent" section, a row of history / Clear all / settings |
| Shade, expanded | `shade2.png`, `.xml` | Large clock (36 sp) and date, carrier; brightness slider (52 dp, full round, primary); tiles 72 dp tall, 8 dp apart, four columns, wide tiles span two (icon box 56 dp lit in primary, name and state); page dots; footer: user, settings, power |
| Home | `home.png`, `.xml` | "At a glance" (date) at the top left, 4 x 5 grid with names, hotseat row without a platter, Google search bar at the bottom (dark pill, G, mic, lens) |
| Apps | `drawer.png`, `.xml` | A sheet from the bottom (handle), search bar, a row of predicted apps, "All apps", alphabetical 4-column grid, fast scroller |
| Search | `drawer_search.png` | The sheet's search field focused |
| Recents | `recents.png`, `.xml` | A carousel: the current app's card centred (734 x 1631 px), neighbours beside it, the app's icon and name in a chip above the card, "Screenshot" / "Select" below |
| Menus | `icon_menu.png`, `home_menu.png` | An app's long-press menu: a popup above the icon (App info, Pause app, Widgets, Remove...), shortcuts below; home's: wallpaper previews, Wallpaper & style, Widgets, Apps list, Home settings |

Colours come from the system's palette (Material You, from the wallpaper). Measured roles on the emulator's blue palette:
tiles and notification cards `accent2_900` (dark) / `accent2_100` (light); lit tiles, icon boxes, the brightness slider
`primary`; "Clear all" `accent2_800`; the shade's veil about 70 % black in dark mode, 55 % white in light, over a blur.

## Built

- **Theme groundwork** (2026-10-09). Colours may name the system's roles: `@primary`, `@surface_container_high`,
  `@on_surface_variant` (light and dark from `system_<role>_light/_dark`), `@system_accent2_900` (one exact colour), with
  an alpha (`@primary/40`). A theme that uses them is read again when the palette changes (the wallpaper). Layout choices
  `sys.layout.shade|home|drawer|switcher|menu|statusbar`, font family `sys.font.family` ("inter" or "system").
  `DESIGN_RELOAD --ez palette true` logs the system palette.
- **The shade** (`shade/PixelShadeView`, tokens `comp.px.*` in `shade/PxTokens`). A third panel next to Notification
  Center and Control Center: pulled from anywhere along the top when `sys.layout.shade` = "pixel". First pull: two rows of
  tiles, the notification list (cards, groups with a count chip that opens them, the Silent section, swipe to dismiss,
  outlined action buttons, Clear all with history and notification settings beside it). Pulling on, or a second pull:
  Quick Settings expand (large clock and date, brightness slider, four rows a page with paging, settings and power);
  pulling up collapses, then closes. The status bar stays, its content in the panel's colours, the date beside the time;
  its time goes as the panel expands. Checked against the reference on the emulator in dark and light.

- **Heads-up notifications** (2026-10-09): with the Pixel shade, banners are its notification cards (`shade/PxCardPainter`,
  shared with the shade's list), opaque, sliding down from above the top edge (no live blur plate); actions as outlined
  capsules. Checked on the emulator in dark and light.

- **Home** (2026-10-10, `sys.layout.home` = "pixel"): sizes in dp as the Pixel Launcher's (`HomeMetrics`, `pixel`):
  60 dp icons in four columns with names in 14 sp, At a glance (the date) at the top left (`GlanceView`), the grid's rows
  between it and the page dots (`PixelDots`, 6 dp, above the hotseat), the hotseat row without a platter, the search bar
  at the bottom (48 dp, 26 dp above the navigation bar, a solid surface from the palette: `comp.home.pixel.search-fill`,
  label `comp.home.pixel.search-label`). Changing the layout, font or scale tokens rebuilds home. Checked on the emulator
  against the reference in dark and light. Still iOS: the glance's colour (white, not the labels' tone), the Google logo
  and lens (Android's own marks, not drawn).

- **All apps** (2026-10-10, `drawer/AppGridView`, tokens `comp.grid.*` in `drawer/GridTokens`): with
  `sys.layout.drawer` = "grid" the drawer is a sheet that a swipe up on home pulls from the bottom (whatever the drawer
  placement setting says): rounded top corners and a handle, the search field, a row of predicted apps (most used, filled
  up from the list), "All apps" and every app in an alphabetical four-column grid, a fast scroller at the right edge (the
  thumb follows the finger, a drop-shaped bubble with the letter points at it, a tick per letter). Typing filters the
  grid: matches glide to their new places, the rest fade where they are; Go opens the first. The field loses its fill and
  its content moves left while focused (Pixel's). Past an end the list stretches. Home's search bar opens the sheet with
  search focused. A pull follows Launcher3's timing (`comp.grid.motion.*`): home fades over the first 40 %, the scrim
  (`comp.grid.scrim-fill`, its own veil over the blurred wallpaper) comes in from 10 % to 50 %, the apps from 10 % to
  60 %. Checked on the emulator against the reference: colours within a few levels in dark and light, rows and icons on
  the same pixels; search, Back (keyboard, search, sheet), launch and close into the drawer's icon, long press, pull
  down to close. Not drawn: Google's G, mic and lens (ours: a magnifier and "Search apps").

- **Recents** (2026-10-10, `sys.layout.switcher` = "carousel", in `switcher/DeckView`, tokens
  `comp.switcher.carousel.*`): the same switcher (hold during a home swipe; gestures, pictures, springs) laid out as
  Android's: cards side by side at 68 % of the screen, the focused one centred and staying the app the swipe started in,
  16 dp apart, 27 dp corners, no shadows; the app's chip inside each card's top left (icon, name, chevron; a tap opens the
  app's menu); "Clear all" after the oldest card (the carousel rests with it in the middle); home's icons fade out behind
  (only the wallpaper, dimmed 13 %). Flick up to close, tap to open, Clear all: checked on the emulator against the
  reference. Not there: Pixel's Screenshot and Select buttons under the cards.
- **Wallpaper**: Pixel does not dim it in dark mode (`sys.appearance.wallpaper-dim` 0).

- **Popups** (2026-10-10, `sys.layout.menu` = "pixel", `components/PixelMenu`, tokens `comp.pxmenu.*`): a long press
  leaves home as it is (no lift, no blur) and opens Android's popup: the app's actions (App info, Widgets when it has
  some: the gallery opens on them, Remove on home, Uninstall) in one rounded container and its shortcuts in another,
  nearest the icon, 2 dp apart, with a pointer at the icon (its edge 26 dp from the pointer, flipped at the screen's
  edge); widgets get Widget settings, Remove and their sizes. A long press on empty space opens home's options (Wallpaper
  & style, Widgets, Home settings) in one translucent, blurred container at the touch. Checked on the emulator against the
  reference (`icon_menu*.png`, `home_menu*.png`, re-captured): colours equal in dark and light. Not there: Pause app,
  Bubble, App lock (system features), the options popup's wallpaper previews, the "+" that pins a shortcut to home.

- **Status bar** (2026-10-10, `sys.layout.statusbar` = "pixel", tokens `comp.statusbar.pixel.*`): the time at the
  left edge (15 sp, medium), notification icons after it; at the right four rounded signal bars, Android's Wi-Fi (a dot
  and two arcs) and the capsule battery (filled to the level, green while charging with the bolt cut into its end), as
  measured on the emulator's SystemUI. Every slot keeps its springs (levels glide, icons grow in and out); a theme change
  lays the bar out anew at once.

- **Swipe down on home** (2026-10-10, with the Pixel home): a pull down anywhere below the status bar brings our shade,
  following the finger as a pull from the top edge does (`Shade.homePull`); Spotlight is not used by the Pixel profile.

## To do (in order)

6. Motion: Material's springs; icons: Material Symbols (needs the font, to be downloaded with permission).
7. The shade's remaining parts: media player in Quick Settings, tile editing, the header's carrier and icons.
8. Behaviour: a home gesture from an app opened from All apps may land on home with the sheet closed on a Pixel (to
   check on a real Pixel); ours returns to the sheet (iOS's).
   Recents' Screenshot (and Select, Google's).
