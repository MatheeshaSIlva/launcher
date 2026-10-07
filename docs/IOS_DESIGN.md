# iOS design reference (measured)

Numbers the iOS theme is built from. Apple publishes no Home Screen layout spec, so these were **measured from Apple's own
full-resolution press images** (pixel analysis, 2026-10-03). `HomeMetrics` implements them; change them here and there together.

Sources:
- iOS 26 Home Screen: Apple Newsroom, "Apple elevates the iPhone experience with iOS 26" (June 2025), press image
  `Apple-WWDC25-iOS-26-Home-Screen-customization-250609` (3840 x 2160, straight-on renders of iPhone 16 Pro). The right-hand phone
  (default icons) was measured: display 827 x 1800 px = 402 x 874 pt, so 1 pt = 2.057 px.
- App Library: Apple Newsroom, "Apple reimagines the iPhone experience with iOS 14" (June 2020), press image
  `Apple_ios14-app-library-screen_06222020` (iPhone 11 Pro, 375 pt wide, 1 pt = 1.757 px). Layout unchanged since; iOS 26 restyles
  the tiles with Liquid Glass.

## Home Screen (iPhone 16 Pro, points)

| Item | Value |
| --- | --- |
| App icon | **64** (not 60: iOS 26 on the Pro uses 64 pt) |
| First column's icon, from the display edge | 30.1 |
| Column pitch (centre to centre) | 92.5 (gap between icons 28.4) |
| First row's icon top, from the display top | 89.5 (status bar 62 + 27.5) |
| Row pitch | 100.6 |
| Label | SF 11 (cap height 7.8), white, baseline 16.4 below the icon |
| 2 x 2 widget | spans its two columns + 4 pt each side, its two rows (icon top to icon bottom) |
| Search pill (replaces page dots at rest) | 78 x 29.6, clear glass, 19.4 above the dock |
| Dock | 101 tall (icon 64 + 18.5 above and below), inset **17.5 from the sides and the bottom** |
| Dock corner radius | 39 = display corner radius − inset (concentric) = 0.385 x dock height |
| Dock icon pitch | 89.4 (tighter than the page columns), centred |

The S24's aspect (2340/1080 = 2.167) is almost the iPhone 16 Pro's (2.174), so these scale by width alone.

## App Library (iPhone 11 Pro, points of a 375 pt screen)

| Item | Value |
| --- | --- |
| Side margin | 23.3 (same for the search field and tiles) |
| Tile | 155.4 square (0.414 of the width); gap between tiles 18.2 |
| Inside a tile | padding 11.4, icons 59.2, gap 13.7 (0.0734 / 0.381 / 0.088 of the tile) |
| Tile corner radius | 22 (0.142 of the tile, continuous corner) |
| Tile label | SF 13, baseline 17 below the tile |
| Row pitch | 190.7 |
| Search field | 46.7 tall, 13 below the status bar; tiles start 25.6 below it |

## Liquid Glass (iOS 26): one material for every surface

Decided with Matheesha (2026-10-03, after comparing builds): iOS glass is **clear** and looks the same in light and dark mode:
no white tint, almost no blur, a lens at the edge, and **only a slight light rim**. `GlassStyle.IOS` (rendered offline in
`docs/design/glass-material-v5-clear.png` with `docs/design/glass_proto.py`, the shader's own maths):

- Body: the backdrop with about 1.5 pt of blur (`Wallpaper.blurred`), saturation 1.4, no tint, no inner glow.
- Edge: lens band 20 pt bending 30 pt (narrower on small shapes: at most 35 % of the shorter side), dispersion 0.25.
- Rim: 1.2 pt; 0.14 all round, +0.22 facing the light (top left), +0.10 on the far side.
- Every glass surface uses it: dock, widgets, Search pill, App Library tiles and search field, folder panels. Surfaces in the
  App Library refract the library's blurred background (`GlassDrawable.Source.BACKDROP`).
- Rejected: frosted body with white tint and inner glow (v4, "everything has a white tint"); heavier blur ("frosted, not liquid").
- Behind an open folder the library is covered completely by its own blurred background (no faint icons).

### iOS 27 changes (researched 2026-10-03; iOS 27 shipped 2026-09-14 with macOS 27 "Golden Gate")

Sources: MacRumors "Here's How Liquid Glass Is Changing in iOS 27", the MacStories iOS 27 review, iDropNews, Stuff, Ryan
Thomson's first impressions. What changed in the material: a **darkened edge** around glass elements for separation;
**brighter, static specular highlights** (no longer gyroscope-driven); stronger diffusion of busy content behind glass;
crisper refraction; **context menus are glass** (no longer dark); a system slider from "ultra clear" to "fully tinted";
icons built from layers of glass; an **extra-large 4 x 6 widget** (a whole page in portrait).

What we took (kept within Matheesha's "clear, slight rim" decision):
- `GlassStyle.IOS`: rim 0.08 all round, +0.40 facing the light, +0.18 opposite, gathered at the corners (`specPower` 1.8),
  and a darkened edge (16 %, 1.6 pt). On straight edges the rim is as bright as before; corners are brighter, the far side
  darker. To go back to the iOS 26 look: rimBase 0.14, rimLight 0.22, rimBack 0.10, edgeDark 0, specPower 1.
- Not taken: the stronger diffusion (Matheesha rejected heavier blur); a clear-to-tinted slider could become a setting.
- Menus and sheets that float over blurred home use the same glass over live content (`LiveGlass`: what is really behind,
  blurred by the same radius as home, then the same lens and light), so they match the dock exactly.
- Glass numerals (the clock): the same shader with a text mask instead of a rounded rectangle; the lens and light come from
  the slope of the blurred text (`GlassStyle.IOS_CLOCK`: frosted, 30 % white, shallow lens, soft shadow).

## Widgets and the Search pill

- Widget frame (measured): spans its columns' icons plus 4 pt each side, from its first row's icon top to its last row's icon
  bottom; corner radius 28 pt (iOS 27 kit; was 23 measured from a picture); content padding 16 pt; name below like an app label.
- Search pill: 78 x 29.6 pt (wider when many page dots need room); "Search" with a magnifier at rest, page dots while pages move.

- Widget sizes (iOS): small 2 x 2, medium 4 x 2, large 4 x 4, extra large 4 x 6 (iOS 27). Android widgets are shown at the
  iOS size their minimum size fits (any of them if resizable), clipped to the 28 pt corners, their app's name below.
- Clock widget: the lock screen's "Glass" clock: the date line (weekday and day, semibold) above the time in tall glass
  numerals filling the 4 x 2 box; 12 or 24 hours as the system is set, no AM/PM; no card and no label.

## Motion (every animation, checked against iOS)

All roles live in `motion/Motion.kt` (`MotionProfile`); springs are SwiftUI-style (response s, damping fraction).

| Animation | iOS reference | Ours |
| --- | --- | --- |
| App open | icon zoom, slight settle | 0.42 / 0.92 (tuned on the S24 with Matheesha) |
| App close | position and size on separate springs, into the icon | position 0.5 / 0.92, size 0.44 / 0.9 |
| Home depth on open/close | home zooms and blurs behind the app | depth 0.45 / 1 and 0.5 / 1; icons 1.12, wallpaper 1.04, blur 14 pt |
| Page snap | UIScrollView paging, no bounce | 0.38 / 1 |
| Scroll | deceleration 0.998 / ms, rubber band 0.55 | same |
| App Library page, sheet | critically damped | 0.4 / 1 |
| Folder open / close | slight overshoot / none | 0.42 / 0.86 and 0.36 / 1 |
| Long-press menu | one spring for lift, blur and menu: 0.35 / 0.8 (UIContextMenuInteraction) | 0.35 / 0.8 open, 0.3 / 1 close; item lifts 1.06x; home blurs 18 pt and dims 15 % |
| Edit mode wiggle | about 4 per second, ~1.5 degrees, random phase | 1.6 degrees, 0.26 s period, widgets 0.6 degrees |
| Icons making room | smooth, no bounce | 0.36 / 1, interruptible (keeps velocity when the target changes) |
| Drag lift / drop | lift with a little overshoot, drop into the cell | lift 0.26 / 0.8 (1.08x, shadow); drop 0.34 / 0.86 |
| Sheet (widget gallery) | UIKit sheet spring, follows a pull down | 0.45 / 1; pull down past 30 % or fast closes, carrying the finger's speed |
| Push inside a sheet | slide in from the right, the page below moves a third and fades | 0.42 / 1 |
| Icon touch | dims (no shrink) | 32 %, 70 ms in, 220 ms out |

Changed in this pass: the menu, edit-mode reflow (was 300 ms decelerate), drag lift (was 160 ms) and drop now come from the
profile, and reflow and lift are springs that keep their velocity when interrupted. Still on fixed durations (fine for what
they are): Spotlight's results cross-fade (220 ms), the status bar's light/dark change (220 ms), icon removal (200 ms).

## Typeface

SF Pro may not be used outside Apple platforms (its licence covers mock-ups of software for Apple's operating systems only).
The iOS theme uses **Inter** 4.1 (SIL Open Font License, `assets/fonts/InterVariable.ttf` + licence), whose optical-size axis
stands in for SF Text/Display. Inter's caps are taller (0.727 em vs SF's 0.705), so sizes are set to match SF's cap height
(SF 11 → Inter 10.7).

## Shade: Control Center, Notification Center, banners (iOS 27 profile)

Values the shade is built from (pt of the 402 pt layout, iPhone 16/17 Pro proportions, scaled by width on the S24). Since
2026-10-07 from Apple's iOS 27 UI kit and the running iOS 27 (docs/IOS27_KIT.md, docs/IOS27_MOTION.md).

| Item | Value |
| --- | --- |
| Status bar | time 15 (tabular digits), notification icons 14.5 high, 4.5 apart, up to 5; both groups 34 from the edges |
| Control Center grid | cells 70 on an 85.33 pitch from 38 (the running iOS 27); grid top 132.3 (86 in edit mode) |
| Control Center top | + and power buttons 29 at (38, 23) and (335, 23); status row centred 104.2 |
| Control Center motion | iOS 27, measured: blur and controls in over ~110 pt of pull (0.3 / 1 after release); the controls pulled down 100 x (1 - e^(-d/270)) pt and settling on 0.42 / 0.68 (they overshoot a little); close 0.28 / 1 with the controls lifting 8 pt; backdrop blur 26, dim 36 % |
| Notification Center | slides down as a sheet (its bottom edge under the finger); open 0.44 / 1, close 0.38 / 1 |
| Notification platter | (iOS 27 kit) margins 14, radius 24, 8 apart; icon 38.33 at 14; text from 62.33; 15 pt text on 17-18 pt lines; min 66.33 tall; stacks show 8 pt shelves, 10 / 20 narrower per side |
| Notification Center clock | date and glass numerals in (36, 110)-(W-36, 206); flashlight and camera buttons 50 round, 71 from the sides, 78 above the bottom |
| Banner | 8 from the sides, top at half the status bar + 16 (iOS: just under the status bar), platter as Notification Center's (radius 24); iOS 27, measured: out of the camera (the island) on 0.64 / 0.61, overshooting a little, 7 s, back into it on 0.3 / 1 |
| Call banner (compact incoming call) | 76 tall, radius 30, photo 50, name 17 semibold, Decline / Answer 46 round (system red / green), 12 apart |
| Alarm and other ringing banners | the notification's actions as capsules 40 tall, 8 apart, under the text |
