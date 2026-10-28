# Audit C: home and drawer, iOS 27 profile (2026-10-28)

Scope: home grid, dock, Search pill / page dots, icons and labels, badges, folders, widgets and the widget gallery, edit
mode and the wiggle, long-press menus, App Library, Spotlight, wallpaper, and home's own motion (page scroll, rubber band,
depth, unlock arrival). Read-only audit: code at `main` 494dd31, no device run.

Ratings: **ok** (matches the reference as far as it is known), **off** (close, details differ), **wrong** (visibly
different), **missing** (not built). Severity follows the backlog: P1 a core-journey gap the owner will see at once,
P2 a visible gap, P3 polish.

## Sources and how far to trust them

| Source | Used for | Confidence |
| --- | --- | --- |
| Our iOS 27 Simulator captures, `ios-reference/out/pass4..6` (`test17_pages`, `test18_appLibrary`, `test19_spotlight`, `test20_iconMenu`, `test21_editMode`, `test23_folder`, `test33_pageHoldRelease`, `test34_iconMenuClean`) | Folder and page springs (measured, `docs/IOS27_MOTION.md`); the icon menu's open and close (looked at frame by frame for this audit, see below) | High for what was measured; the CI Simulator has no GPU, so home's blur behind a menu is not rendered in these frames and some frames are missing while layers animate |
| Apple's iOS 27 UI kit (`docs/IOS27_KIT.md`) | Geometry, materials, menus, alerts, widgets | High (exact layer values) |
| Apple press images (iOS 26, `docs/IOS_DESIGN.md`) | Grid, dock, App Library geometry | High (measured) |
| WWDC25 "Meet Liquid Glass" (session 219, developer.apple.com/videos/play/wwdc2025/219) | How glass reacts to touch and how menus appear | High for the principle, no numbers. Quotes: the glass "responds to interaction by instantly flexing and energizing with light"; it has "an inherent gel-like flexibility"; a menu's "bubble simply pops open"; glass that grows "changes to simulate a thicker, more substantial material"; Reduced Motion "disables any elastic properties" |
| Secondary: a Medium guide on SwiftUI's `.glassEffect(.interactive())` (scale on press, bounce, shimmer, touch-point light spreading to nearby glass); 9to5Mac on iOS 26 beta 6 (apps "leap out of their icon" faster, ~150 ms quicker); 9to5Mac/letemsvetemapplem on iOS 26.2 (menu opening animation brought closer to the WWDC demo) | Interactive glass, launch pace | Medium (not Apple's text) |

Where nothing above covers a behaviour it is marked **to capture in the Simulator**.

### New evidence from this audit: the icon menu (pass 4, `test20_iconMenu/f1`, 402 x 874 pt frames)

- **Press**: the held icon (Settings) dims at the touch (frames 76-98) and comes back to full brightness about 0.25 s
  later while the finger still holds (frames 100-128), before the menu shows. No visible scale of the icon during the
  hold or after the menu opened (frames 128 vs 135, same size).
- **Open** (frames 130-135, 25.49-25.66 s): nothing at 130-131; at 132 (+70 ms) the panel is already there but only about
  100 x 100 pt, faint, its rows laid out and scaled down, sitting right under the icon; at 133 about 160 x 170 pt, left of
  the icon; at 134 about 200 x 240; at 135 full width and still growing in height. It grows **out of the icon** (anchored
  at it, the panel's far corner travelling away), from about **0.4 of its size**, with the width and height not in step
  (it starts nearly square, ends 250 x ~260): the "pop" and stretch the owner noticed. Whether it overshoots could not be
  read (the Simulator's keyboard came up over it from frame 136: a test artefact).
- **Close** (`s/sheet01.png`, frames 259-260): in one recorded frame the menu is at ~0.6 of its size, faded, and has moved
  up and to the right **toward the icon** it came from; one frame later it is gone.

## Element table

| # | Element | Layout | Look | Motion | Interaction | What iOS does (source, confidence) | Ours (where) | Fix: tokens and motion roles |
|---|---|---|---|---|---|---|---|---|
| 1 | Home grid (icons, pitch, first row) | ok | ok | n/a | ok | Icons 64 at x 30, column pitch 92.67, first row 90, row pitch 100.33 (kit, high); free placement since iOS 18 | `home/HomeMetrics.kt:38-72` (30.1, 92.5, 89.5; 64 pt icon) | Geometry is literals in `HomeMetrics` (polish pass: no literals). Move to `comp.home.grid.icon`, `.side-inset`, `.top`, `.row-extra` so a layout or theme can change them (P3) |
| 2 | Dock | ok | ok | ok | missing (folders) | 101 tall, inset 17.5, radius 39 concentric, dock glass (kit, high); folders go in the dock | `HomeMetrics.kt:54-58`; `comp.home.dock.material` | Dock folders come with home folders (#6). Dock geometry to `comp.home.dock.*` (P3) |
| 3 | Search pill / page dots | ok | ok | off | missing (glass reaction) | iOS 26/27: "Search" at rest, dots while pages move; hold = edit mode, swipe across = turn page, tap = Spotlight (capture `test72`, high). Liquid Glass flexes and lights from within when touched (WWDC25 219, high in principle) | `home/HomeViews.kt:1021-1046`: fixed width (`widthFor`), "Search" and the dots crossfade on `motion.home.pill-hide/-show` (beziers, 140/260 ms); press: none | Interactive glass on press (#21). Whether the capsule changes width between "Search" and dots: **to capture in the Simulator** (`test17_pages` exists, not measured); if it does, a width spring `motion.home.pill-morph` |
| 4 | Icons and labels | ok | off | ok | ok | Labels SF 11 (Inter 10.7 stands in). iOS 26/27 Customize: Default, Dark, Clear, Tinted icons; small or large icons (MacRumors iOS 26 home guide, high) | `home/IconView.kt`; shapes from `apps/Icons.kt` (system mask by default) | Customize's icon styles missing (P2): `comp.home.icon.style` (default/dark/clear/tinted), Clear rendered as glass over the icon's layers; a menu row "Customize" in the Edit menu |
| 5 | Icon press | ok | ok | off | ok | Dims at the touch (no shrink); on a long hold the dim clears ~0.25 s later, before the menu (pass 4 frames 76-128, this audit, medium-high) | `IconView.kt:195-201`: dim 0.32 in 70 ms, kept until release or the menu; `motion.icon.press-in/out` are fixed beziers | Clear the dim when the long press is recognised (it already fades in `ContextMenuView`), on `motion.icon.press-out` (P3). Press-in as a spring (`{"spring":[0.15,1]}`) so a quick re-press retargets |
| 6 | **Home folders** | missing | missing | missing | missing | Drop an app on another to make a folder; folder icon (3 x 3 minis), opens as a large glass panel with its name, closes into its icon, rename, folders in the dock; open 0.49 / 0.92, close 0.37 / 0.97 (capture `test23_folder`, measured, high) | Model only: `HomeItem.Folder` (`home/HomeModel.kt:31-33`); `home/HomeScreen.kt:923` "folders: a later build"; no hover-to-make in `home/EditMode.kt`. `docs/AUDIT.md` lists it as P1 since 2026-10-03 | Build it (P1): `comp.home.folder.*` (icon grid, panel material = `component.panel.material`, title type, radius), `motion.folder.open/close` already measured; new `motion.edit.folder-hover` (the target icon swelling into a folder plate while an app hovers), a `Folders` moment |
| 7 | App Library category (folder) | ok | ok | ok | ok | Grows out of its tile; springs as home's folder (measured) | `drawer/FolderOverlay.kt` on `motion.folder.open/close` (0.49/0.92, 0.37/0.97) | none |
| 8 | App Library | ok | ok | off | off | Tiles 168.3 wide at x 24.3 / 209.3, first row y 168 (running iOS 27, high); badges on its icons (Settings, "Show in App Library", high); a pull down on the tiles brings the A-Z list | `drawer/AppLibraryView.kt`, `TilesPane.kt`; tiles 166.6 max (`HomeMetrics.kt:101`); pull past a fixed 70 pt switches to the list (`TilesPane.kt:188`) by a 220 ms crossfade (`motion.mode-crossfade`) | Badges on library icons (P2, `comp.library.badge` aliasing `comp.badge.*`). How the list arrives (follows the finger? field drops?) **to capture** (`test18_appLibrary` exists, not measured); the 70 pt threshold to `comp.library.pull-to-list` |
| 9 | Spotlight | ok | ok | off | ok | iOS 26/27: pulled down from any page, field at the bottom, suggestions above (capture `test19`, medium) | `home/SpotlightView.kt:209-312`: progress over a fixed 190 pt pull, open past 40 % or a fling, rubber band past full (`Motion.rubberBand`), content shift 30/40 pt, field from 60 pt below: all literals | Distances and threshold to `comp.spotlight.pull`, `.open-at`, `.content-shift`, `.field-rise` (P3). Its pull, overshoot and settle **to capture** (`test19_spotlight` exists, not measured); the open on its own role `motion.spotlight.open` instead of `motion.drawer` |
| 10 | **Long-press menu (Quick Actions)** | ok | ok | **wrong** | ok | Kit: 250 wide, radius 30, Regular glass, rows 42 (high). Motion (pass 4, this audit): the panel pops out **of the icon**, from ~0.4 of its size, width and height not in step, reaching full size in ~100-130 ms; closes back **into the icon** (~0.6 and fading in one frame). WWDC25: the bubble "pops open", gel-like, thicker glass as it grows. The lifted icon is not visibly scaled | `components/Menu.kt:171-181`: uniform scale from `component.menu.grow-from` 0.8, about the panel's own top or bottom centre, fading with the same spring; `home/ContextMenuView.kt:240`: lifted copy 1.06 (literal); `motion.menu.open` 0.35 / 0.8, close 0.3 / 1 | P1 (the owner's squash and stretch): grow from the item's frame (anchor at the icon's centre, not the panel's edge), `component.menu.grow-from` 0.8 -> ~0.35, width and height on separate springs (new roles `motion.menu.open-width`, `motion.menu.open-height`, the height a little slower so it stretches open), the panel's alpha in over the first ~40 % (`motion.menu.fade`); the close aims at the icon. Lift 1.06 to a token `comp.home.menu.lift` and **to capture** (frames show none) |
| 11 | Edit mode: wiggle | ok | ok | off | ok | ~4 Hz, ~1.5-2°, random phase (docs, medium). Whether iOS adds a small bob (translation) to the rotation: **to capture** (`test21_editMode` exists, not measured) | `home/EditMode.kt:188-194`: rotation only, 1.6°, period 0.26 s (`motion.edit.jiggle`, `.jiggle-period`), widgets 0.375 x (literal) | Measure `test21`; if iOS bobs, add `motion.edit.jiggle-bob` (pt). Widgets' factor to `motion.edit.jiggle-widget` (P3) |
| 12 | Edit mode: Edit / Done, Edit menu | ok | ok | off | off | iOS 26/27 Edit menu: Add Widget, Customize, Edit Pages (MacRumors, high); the glass buttons flex and light on touch (WWDC25) | `HomeScreen.kt:2310-2331`: Add Widget, Change Wallpaper, Hide/Show App Names and Widget Names, Appearance, Home Settings. Press: a tint over the capsule only (`EditMode.kt:1201-1217`) | Edit Pages missing (P2: hide/reorder pages, `comp.home.pages.*`, `motion.home.pages-overview`); press flex (#21) |
| 13 | Drag lift, reflow, drop, stacks | ok | ok | ok | ok | Lift with a little overshoot, reflow without bounce (docs, medium); several apps carried as a stack with a count | `EditMode.kt:1022,1092`: 1.08 lift (literal), `motion.edit.drag-lift` 0.26 / 0.8, `.reflow` 0.36 / 1, `.drag-settle` 0.34 / 0.86 | Lift scale to `comp.home.drag.lift` (P3); values **to capture** with `test21` |
| 14 | Widgets on home | ok | ok | ok | missing (stacks) | Small 164.67 radius 28, sizes 2x2 / 4x2 / 4x4 / 4x6 (kit, high); Smart Stacks and stacks of your own (swipe within) | `home/Widgets.kt`; resize animated; `comp.home.widget.material` | Widget stacks missing (P2): `comp.widgets.stack.*` (dots), `motion.widgets.stack-scroll` (vertical paging with rubber band) |
| 15 | Widget gallery | ok | ok | ok | ok | Sheet with grabber, search, featured, per-app pages (iOS 26, medium) | `home/WidgetPicker.kt`; `motion.sheet` 0.45 / 1, follows a pull down | none found |
| 16 | Remove alert | ok | ok | ok | ok | Kit alert: 300 wide, radius 34, buttons 48 capsules (high) | `home/AlertView.kt`, `comp.home.alert.*`, `motion.alert.open/close` | Button press flex (#21) |
| 17 | Badges | ok | ok | off | ok | Red count, top right (kit). How a count change animates: **to capture** | `IconView.kt:70-84`: new badge pops in on `motion.appear`, cleared shrinks; a changed count swaps its digits at once with a bump, and the capsule's width jumps (9 -> 10) | Rule 3 names badge counts (P3): roll or crossfade the digits and spring the width (`motion.badge.count`, `component.badge.*` unchanged) |
| 18 | Wallpaper | ok | ok | ok | missing (parallax) | Dark appearance dims the wallpaper (high); perspective zoom and iOS 26 spatial scenes (MacRumors iOS 26 guide, high) | `home/WallpaperView.kt:75-77` (dim token); no device-motion parallax | Parallax / spatial scene (P3): `comp.home.wallpaper.parallax` (pt), sensor-driven, off under Reduce Motion |
| 19 | Page scroll and snap | ok | ok | off | off | Page snap released near half: **0.37-0.40 / 0.86** (capture `test33`, measured): a very small overshoot. Swiping right on page 1 opens the Today View (`test72`) | `HomeScreen.kt:1240-1267`: `motion.home.page-snap` 0.38 / 1 (kept on purpose: "the difference cannot be seen"); page 1 rubber-bands (no Today View) | Snap to the measured 0.38 / 0.86 so a fast throw shows the faint settle (P3); Today View missing (P2, own plan; until then the rubber band at page 1 is correct for a page with nothing before it) |
| 20 | Rubber band (pages, App Library end, sheets) | ok | n/a | ok | ok | UIScrollView: offset = (1 - 1/(x c / d + 1)) d, c 0.55; return critically damped | `motion/Motion.kt:118-122`, `motion.scroll.rubber-band` 0.55, `motion.scroll.overscroll-return` 0.42 / 1 | none (verify the S24's feel against `test17_pages`' end, **to capture** the return's time) |
| 21 | **Interactive glass (every glass you touch on home)** | n/a | n/a | missing | missing | WWDC25 219: glass "flexes and energizes with light" on touch, gel-like, the glow spreading to nearby glass; `.interactive()` scales and bounces on press (secondary, medium) | Search pill: no press. Edit/Done, alert buttons, gallery buttons: a tint (`EditMode.kt:1213`, `AlertView.kt:279-281`, `WidgetPicker.kt:1161`). Control Center's controls scale (`motion.cc.press-in/out`, area A) | P2: one shared reaction in `MaterialPainter`/`GlassView`: scale up under the finger (`component.glass.press-scale` ~1.06-1.1), stretch toward a drag (`component.glass.press-stretch`), light from the touch point (`component.glass.press-glow`), on `motion.glass.press-in` (spring ~0.2 / 0.6) and `motion.glass.press-out` (~0.35 / 0.55, the bounce). Numbers **to capture** (press-and-hold the Search pill and Done in the Simulator) |
| 22 | Home depth (app open/close) | ok | ok | ok | ok | Home zooms and blurs behind an opening app (docs, medium) | `motion.home.depth-open/close` 0.45 / 1, 0.5 / 1; content 1.12, wallpaper 1.04, blur 14 pt (all judged) | Values judged, not measured: **to capture** (`test16_appOpenClose` exists). Launch pace is area B: iOS 26 b6 made apps "leap out" ~150 ms faster; our `motion.app.open` 0.42 / 0.92 against the best-sampled 0.21 / 1.02 |
| 23 | Unlock / cold-start arrival | ok | ok | off | ok | Home's icons settle from larger after unlock: **to capture** (no capture exists; the S24's lock screen is secure) | `motion.arrival` 0.62 / 1, zoom 1.18, wallpaper 1.06 / 0.7 / 1 (judged) | Capture an unlock in the Simulator (`xcrun simctl` lock/unlock), fit; possibly a slight bounce |
| 24 | Glass clock widget | ok | ok | ok | ok | iOS home has no lock-screen clock (ours by design, styled after the lock screen) | `home/HomeViews.kt` clock | none |

## Squash and stretch, called out

What iOS 26/27 does on home that reads as squash and stretch, and where we are:

1. **Icon press squish**: iOS home icons do **not** squish; they dim (pass 4, frames 76-98). Ours matches. The squish the
   owner felt is the glass around them (2, 3) and the menu (4).
2. **Interactive glass** (Search pill, Edit/Done, alert and sheet buttons): flexes, swells and lights under the finger
   and bounces back (WWDC25). Ours: none or a tint. **Missing**, P2 (#21).
3. **Search pill into page dots**: a crossfade in a capsule of fixed width; iOS's capsule may reshape. **To capture** (#3).
4. **Menus' spring-in**: iOS pops the panel out of the icon from ~0.4 of its size, its width and height out of step, and
   closes it back into the icon. Ours grows uniformly from 0.8 about its own edge, so it barely moves and never looks
   like it came from the icon. **Wrong**, P1 (#10). Overshoot: ours 0.35 / 0.8 (about 1.5 % past), iOS's could not be read.
5. **Folder open/close stretch**: measured on iOS 27 as 0.49 / 0.92 (no visible overshoot); App Library categories use
   it. Home folders do not exist (#6), so the most-used folder motion is missing entirely.
6. **App Library and Spotlight pull**: both follow the finger and rubber-band past full on the UIScrollView curve;
   Spotlight's distances and the library's switch to the list are literals and not measured. **To capture** (#8, #9).
7. **Page overscroll**: the UIScrollView rubber band (c 0.55) at both ends of the pages and past the App Library: ok
   (#20). The page snap itself is critically damped where iOS's has a faint settle (0.86): off, P3 (#19).

## Pixel home layout

None. `layout/Layouts.kt:103-139` writes one home layout (`IOS_HOME`, "grid") and one drawer (`APP_LIBRARY`, with the
placements page-after-last, page-before-first and swipe-up sheet); `assets/layouts/` holds only `shade/` (ios, pixel).
A Pixel (Android 17) home would need its own layout spec (Pixel's grid, the At a Glance row, the search bar in the dock,
the swipe-up all-apps drawer: the sheet placement exists, its look does not). The spec's v1 names only the shade as
shipping two layouts, so this is a note, not a gap.

## Gaps by severity

- P1: 2 (home folders missing; menu spring-in wrong)
- P2: 7 (interactive glass; Customize icon styles; Edit Pages; widget stacks; App Library badges; Today View; Spotlight
  and App Library pulls not measured)
- P3: 10 (menu lift and icon press dim clear; badge count change; page snap damping; wiggle bob unmeasured; geometry
  literals in HomeMetrics; Spotlight literals; lift literals; depth and arrival judged not measured; wallpaper parallax;
  pill width morph)
- To capture in the Simulator (captures already exist for most, never measured): pages' end and return (`test17`),
  App Library pull (`test18`), Spotlight pull (`test19`), wiggle and drag (`test21`), depth on launch (`test16`), Search
  pill reshaping, interactive glass press, unlock arrival (new), badge count change (new).

## Proposed backlog items (ranked)

- [B-new] P1, home (iOS), there are no folders on home or in the dock: an app dropped on another only takes its place, and a saved `HomeItem.Folder` is not drawn (`HomeScreen.kt:923`). Repro: edit mode, drag one app onto another and hold. Status: open
- [B-new] P1, home (iOS), the long-press menu grows in place from 80 % about its own edge instead of popping out of the icon from ~40 % with its width and height out of step, and closes away from the icon (iOS 27 capture pass 4 `test20_iconMenu` frames 130-135, 259-260). Repro: long-press any home icon, record, compare frame by frame. Status: open
- [B-new] P2, home (iOS), glass you touch does not react: the Search pill has no press feedback, Edit/Done, the alert's and the gallery's buttons only tint, where Liquid Glass swells, stretches and lights under the finger and bounces back (WWDC25 219). Repro: press and hold the Search pill, then Done in edit mode. Status: open
- [B-new] P2, home (iOS), the Edit menu has no Customize (dark, clear, tinted icons; large icons) and no Edit Pages (hide or reorder pages), both in iOS 26/27's. Repro: edit mode, tap Edit. Status: open
- [B-new] P2, home (iOS), no widget stacks (Smart Stack or a stack of your own: drop a widget on one of its size). Repro: edit mode, drag a 2 x 2 widget onto another. Status: open
- [B-new] P2, App Library (iOS), notification badges do not show on the library's icons (iOS shows them there). Repro: an app with a badge on home, swipe to the App Library. Status: open
- [B-new] P2, motion (iOS), the App Library's pull to the list and Spotlight's pull are judged, never measured, though captures exist (`ios-reference/out/pass4/test18_appLibrary`, `test19_spotlight`); their distances are literals (`TilesPane.kt:188`, `SpotlightView.kt:225-307`). Repro: compare the captures with ours slowed. Status: open
- [B-new] P2, home (iOS), a swipe right on the first page rubber-bands; iOS 27 opens the Today View there (`test72_homeGestures`). Repro: page 1, swipe right. Status: open (needs the owner's plan)
- [B-new] P3, home (iOS), a long hold keeps the icon dimmed until the menu opens; iOS clears the dim ~0.25 s into the hold, and its lifted icon shows no 1.06 enlargement (pass 4 frames 76-135). Repro: long-press an icon, record. Status: open
- [B-new] P3, home (iOS), a badge whose count changes swaps its digits in one frame and its capsule's width jumps (9 to 10). Repro: post a tenth notification for an app on home. Status: open
- [B-new] P3, motion (iOS), the page snap is critically damped (0.38 / 1) where iOS 27's was measured 0.37-0.40 / 0.86 (`docs/IOS27_MOTION.md`). Repro: release a page near halfway from a standstill. Status: open
- [B-new] P3, home (iOS), the wiggle is rotation only; whether iOS adds a bob is unmeasured (`test21_editMode` exists). Repro: compare edit mode with the capture. Status: open, to capture
- [B-new] P3, design system, home and library geometry (grid, dock, pill, tiles, folder, list rows: `HomeMetrics.kt`), the menu lift 1.06 (`ContextMenuView.kt:240`), the drag lift 1.08 (`EditMode.kt:1022,1092`) and the widgets' wiggle factor are literals, not `comp.home.*` / `comp.library.*` tokens. Repro: read the files. Status: open
- [B-new] P3, motion (iOS), home's depth on launch and close and the unlock arrival are judged values, never fitted to iOS 27 (`test16_appOpenClose` exists; no unlock capture). Status: open, to capture
- [B-new] P3, home (iOS), no wallpaper parallax or spatial scene. Status: open
