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

## Liquid Glass (iOS 26), measured on the dock and the Search pill

- Body colour vs the wallpaper right outside it: **red drops, blue rises**, i.e. strong saturation (common CSS recreations use
  `saturate(180%)`), plus a slight lift.
- Blur: a thin wallpaper line keeps its position but loses about two thirds of its contrast under the dock: Gaussian sigma
  roughly 6–8 pt. The Search pill is nearly clear (sigma about 1–2 pt).
- Rim: thin bright edge (about 1–1.5 pt), strongest at the top left and on the right curve; edges refract (lensing).
- Ours: `GlassStyle.IOS_DOCK` (blur sigma about 6.5 pt via `Wallpaper.blurred`, saturation 1.7, lift 0.04, specular 0.28) and
  `IOS_CAPSULE` (frost 0.3 = mostly sharp wallpaper).

## Typeface

SF Pro may not be used outside Apple platforms (its licence covers mock-ups of software for Apple's operating systems only).
The iOS theme uses **Inter** 4.1 (SIL Open Font License, `assets/fonts/InterVariable.ttf` + licence), whose optical-size axis
stands in for SF Text/Display. Inter's caps are taller (0.727 em vs SF's 0.705), so sizes are set to match SF's cap height
(SF 11 → Inter 10.7).
