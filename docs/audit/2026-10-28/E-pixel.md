# Audit E: the Pixel profile (Android 17, Material 3 Expressive), 2026-10-28

**In short.** Of the Pixel profile only the shade exists on `main`, and it is a careful copy of Android 17's: the two
stages, the squished quick row, the tile corner and box morphs, the press bounce, the pager and its dots, the edit page,
the media player and the notification cards all have AOSP-sourced numbers, roles and tokens. What is off there is mostly
motion that the Pixel shade borrows from iOS (the notification list scrolls and springs on iOS's roles and physics) and
Android 16 QPR1 behaviour we never copied (magnetic swipes, stretch overscroll, tile dialogs, heads-up cards). The rest of a
Pixel phone (status bar, home, drawer, recents, menus, app open and close) is **not built on `main`**: those are scope
gaps, not bugs.

Read-only audit: code and docs on `main` (494dd31), the golden captures, AOSP on the web. No device was used. Items not
confirmed by a capture are marked **to capture in the emulator**.

## Reference and versions

- **Our reference is Android 17** (`CP41.260828.004.A7`, the emulator's SystemUIGoogle and Pixel Launcher;
  `tools/fidelity/golden/CP41.260828.004.A7/`, `tools/shots/pxref`, `tools/shots/stock`). The Material 3 Expressive shade
  (resizable tiles, pill to rounded-square tiles, the blur, the new slider, remedia) shipped in **Android 16 QPR1**
  (Pixel, September 2025) and Android 17 carries it with the scene container ("Flexiglass"). Where this audit says
  "Android" it means Android 17 as captured, unless it names 16 QPR1.
- Sources, by weight: (1) the repo's measured captures and the dex read of SystemUIGoogle (docs/SHADE_PIXEL_EVALUATION.md
  stages 3-8), (2) AOSP main (`packages/SystemUI/src/com/android/systemui/qs/panels/ui/compose/infinitegrid/Tile.kt`,
  read 2026-10-28: `ActiveTileCornerRadius` 24 dp, `ActiveIconCornerRadius` 16 dp, `animateDpAsState` with default spring,
  `verticalSquish(squishiness)`, `bounceable(...)` with `animateBounce()` after a toggle, `detailsViewModel?.onTileClicked`
  under `QsDetailedView`, `tile.onClick(expandable)`), (3) AOSP commits for `MagneticNotificationRowManagerImpl`
  (flag `com.android.systemui.magnetic_notification_swipes`, Android 16 QPR1), (4) press coverage (Android Authority,
  9to5Google on 16 QPR1: neighbours react to a swiped notification, then it detaches with a haptic).
- M3 Expressive motion physics: the expressive scheme's **spatial** springs bounce (fast stiffness 800 / damping 0.6,
  default 380 / 0.8, slow 200 / 0.8), its **effects** springs never do (3800 / 1600 / 800 at damping 1). These are the values
  `tools/design/build_material_preset.py` already uses (androidx material3 tokens v0_14_0); confirmed on the web only
  partly (effects values; the spatial damping values are the repo's reading).

Confidence: **H** read from code or measured on the emulator; **M** read from AOSP main or press but not captured;
**L** inferred.

Ratings: **ok** matches; **off** close but measurably different; **wrong** behaves otherwise than Android; **missing**
Android has it, our built element does not; **scope** the element or part is not built for the Pixel profile at all.

## What exists for the Pixel profile on `main`

| Element | Pixel layout on main | Pixel look (theme `pixel`) | Pixel motion |
|---|---|---|---|
| Shade (Quick Settings + notifications) | yes, `assets/layouts/shade/pixel.json` (`"preview": true`: offered only with the developer tools on) | yes, 233 tokens: `comp.qs.*` 79, `comp.cc.*` 79, `comp.nc.*` 24, `component.*` 43, `comp.banner.*` 4 | own roles `motion.qs.*` (19, AOSP values, in `ios27.json`), the rest borrowed (below) |
| Heads-up (banners) | no (iOS banner anatomy) | colours only (`comp.banner.*` 4) | iOS's drop-in |
| Status bar | no (`Layouts.kt:89-93`: iOS only) | no `comp.statusbar.*` token in `pixel.json` | iOS's |
| Home | no (`Layouts.kt:118`, iOS grid only) | no `comp.home.*` | `material` preset (generic) |
| App drawer | no (`Layouts.kt:109`, App Library only) | no `comp.library.*` | as iOS's |
| Recents | no (`Layouts.kt:96`, iOS deck only) | no `comp.switcher.*` | as iOS's |
| Context menus | no | no | as iOS's |
| App open / close | style `container` in `material.json` (judged, Material 3's container transform) | n/a | `material` preset |

The 2026-10-10 Pixel run built a Pixel status bar, home, drawer, recents carousel, popups and heads-up cards on branch
`pixel-v1` (up to `9920d65`); it was withdrawn (docs/PIXEL_PROFILE.md: the drawer's blur stopped, menus broke after
switching back, a close flew into the corner). Its measurements remain valid reference; its code is not on `main`.

## Element by element

Columns: what is compared, our rating, Android (source, confidence), ours (file:line or doc), the fix with tokens and
roles. Existing bugs are cited, not repeated.

### 1. Shade: panel, stages and gestures

| Aspect | Rating | Android 17 | Ours | Fix |
|---|---|---|---|---|
| Layout: one panel from anywhere along the top, two stages | ok | Scene container Gone > Shade > QuickSettings (H, stage 3) | `pixel.json` place `together`; `ShadePanelView.kt:62` stage 1, `:222-252` expand | none |
| Interaction: commit thresholds 56 dp or 125 dp/s; two fingers go straight to QS; swipe up and Back go back a stage | ok | DragControllerImpl, SwipeDetectorDefaults (H) | `ShadePanelView.kt:226`, `:235-247`, `VELOCITY_DP` `:541` | none |
| Motion: open, close, expand, collapse | ok | Scene swipe spring, bounded at the place (H) | `motion.qs.open/close/expand/collapse` (spring 0.32 s, 0.8; held at 1 by the stage clamp) | none |
| Motion: quick row tiles grow from 30 % height (squash) | ok | GoneScene TilesSquishiness 0.3, `verticalSquish` (H) | `comp.qs.tiles.squish`; `ShadePanelView.kt:74`, `TileGridBlock.kt:66,201-206` | none |
| Motion: overscroll at the end of a pull | missing? | Scene transitions can declare an overscroll per scene (M); whether Android 17's Shade/QS scenes stretch or translate past their end is unknown | ours holds at 1 (`ShadePanelView.kt:62`) | **to capture in the emulator** (10x slow, pull past QS); if Android stretches, a `motion.qs.overscroll` role and `comp.qs.overscroll-*` |
| Look: blurred, veiled background (shadePanel colours) | off | ShadeColors.shadePanel over the blur (H) | `comp.qs.background`; dark tone B-057 | B-057 |
| Motion: what is behind zooms out with the pull | missing? | NotificationShadeDepthController applies blur and a wallpaper zoom-out (L, Android 12+) | blur only (no zoom in `ShadePanelView`/`Shade`) | **to capture in the emulator** (home behind, slow pull); if present, `motion.qs.depth` and `comp.qs.depth-scale` |
| Layout: landscape | off? | stage 6 measured the split with a media session (H); `docs/PLAN_LAYOUTS_THEMES.md` D describes one row of tiles over a centred 70 % column (H, 2026-10-20, no media) | `"landscape": "split"`; `TileGridBlock.kt:74,215` | the two docs disagree: **to capture in the emulator** turned, without media; if Android differs, split only while media shows |
| Gesture handle under the shade | missing | drawn (H) | not drawn | B-055 |

### 2. Header (first stage row, expanded clock)

| Aspect | Rating | Android 17 | Ours | Fix |
|---|---|---|---|---|
| Layout: date beside the time; big clock, date, carrier when expanded | ok | ShadeHeader, ExpandedHeight 120 dp (H) | `QsHeaderBlock.kt` | none |
| Motion: bar time grows into the clock (ClockScale 2.57), rows come in from 58 % | ok | ShadeHeaderClock (H) | `QsHeaderBlock.kt:112-125`; `comp.qs.header.*-shift` | none |
| Look: status icons in the header row (vibrate, signal, Wi-Fi, battery glyph) | wrong | (H) | our iOS status bar layout | B-053 |
| Look: carrier text source | wrong | "No service" while unregistered (H) | "T-Mobile" | B-054 |
| Look: clock size | off | 221 x 69 px (H) | 225 x 70 px | B-064 |
| Look: carrier weight | ok | 500 on a Pixel (L, family name) | 500 | B-061 closed |

### 3. Tiles

| Aspect | Rating | Android 17 | Ours | Fix |
|---|---|---|---|---|
| Layout: 4 columns, 72 dp, 8 dp gaps, small 1 column, large 2 columns, flowed in order | ok | CommonTileDefaults, config (H) | `TileGridBlock.kt:72-96`, `QsTiles.flow` | none |
| Look: off capsule, on 24 dp, box circle to 16 dp, colours by role | ok | Tile.kt (H, re-read 2026-10-28) | `TileGridBlock.kt:394-430`; `comp.qs.tile.corner/on-corner/box-corner/box-on-corner` | none (fixed stage 8) |
| Motion: **shape morph** of corners and box with the state, spring stiffness 1500 | ok | `animateDpAsState`, `animateColorAsState` (H) | `motion.qs.tile-state` | none |
| Motion: **press bounce** 8 dp into neighbours (squash of the neighbours) | ok | BounceableTileViewModel, BounceOnPressNode (H) | `TileGridBlock.kt:221-256,288-308`; `motion.qs.tile-bounce`, `comp.qs.tile.bounce` | none |
| Motion: tap pop (symbol 1.1, words 1.06) | ok | animateBounce, 167 ms out and back (H) | `motion.qs.tile-pop`, `-pop-back` | none |
| Motion: symbol animations on toggle | ok | animated vectors (H) | B-036 (awaiting S24); Extra dim static B-048 | B-048 |
| Look: active tile tone | off? | 67,96,138 (H, emulator) | 42,97,153 | B-046 |
| Look: unavailable tile (no torch) | wrong | shown faded (H) | hidden (`TileGridBlock.kt:84-87`) | B-059, B-065 |
| Interaction: two-target tile, box toggles, words open details | ok | `handlesSecondaryClick` (H) | `TileGridBlock.kt:311-335` | none |
| Interaction: **details** (Internet, Bluetooth, Modes, Cast, Hotspot...) | wrong | `tile.onClick(expandable)` opens the tile's dialog over the shade, grown from the tile (DialogTransitionAnimator); Android 17 also has an in-shade details view behind `QsDetailedView` (M) | `Controls.kt:529-545` launches a Settings activity (`ACTION_WIFI_SETTINGS`...) with no origin motion | new block `tile-details` (Internet, Bluetooth, Modes first), tokens `comp.qs.dialog.*` from `component.sheet`, roles `motion.qs.dialog-open`, `-close` (grow from the tile's rect); **to capture in the emulator** which tiles use dialogs vs details |
| Interaction: activity launch from a tile or the footer grows from it | missing | ActivityTransitionAnimator from the Expandable (M) | `Shade.kt:249` `launchIntent` (no rect); only notifications pass one (`CardsBlock.kt:1482`) | pass the tile's rect to `Shade.openFrom`-style launches; role `motion.qs.launch` |
| Interaction: haptics (toggle on, off, long press) | ok | TileHapticsViewModel, MSDL (H) | `TileGridBlock.kt:320-348` | haptics as preset roles: SHADE_PIXEL_EVALUATION "Motion" (open) |
| Tile set | off | Screen record in the default set (H) | no control | noted stage 8 (no MediaProjection dialog for apps) |

### 4. Quick Settings pager and page indicator

| Aspect | Rating | Android 17 | Ours | Fix |
|---|---|---|---|---|
| Layout: 4 rows a page, pages side by side | ok | `quick_settings_paginated_grid_num_rows` 4, HorizontalPager (H) | `pixel.json` `"rows": 4`; `TileGridBlock.kt:90-92,194-215` | none |
| Interaction: fling 400 dp/s turns one page, else the nearer | ok | PagerDefaults snap (H, stage 7) | `ShadePanelView.kt:524-539`; `motion.qs.page` | none |
| Look: dots (other pages onSurface 34 %, current a pill at 70 %) | ok | measured (H) | `QsFooterBlock.kt:99-111`; `comp.qs.dots.*` | none |
| Motion: **past the first or last page** | wrong? | Compose's pager uses the platform overscroll: a stretch on Android 12+ (M) | moves linearly up to a quarter page (`ShadePanelView.kt:468`, coerce -0.25 .. pages - 0.75), springs back | **to capture in the emulator**; a stretch (scale along x from the edge) with `comp.qs.overscroll-stretch` and `motion.qs.overscroll` |
| Layout: page spacing between pages | to capture | InterPageSpacing (M, value not read) | pages abut at the panel width (`pageWidth()` `TileGridBlock.kt:215`) | capture a half-swiped page |
| Owner's "missing QS pages" | to capture | n/a | with the default set (6 large + 7 small = 5 rows) we show 2 pages at 4 rows (`QsTiles.kt:47-61`); fewer rows when the screen is short (`TileGridBlock.kt:90`) | **to capture in the emulator** and ask the owner which screen: the golden's 7 tiles fit one page, so pages and dots were never compared; if he meant iOS Control Center, that is area A/B (iOS 18+ Control Center has vertical pages) |

### 5. Brightness slider

| Aspect | Rating | Android 17 | Ours | Fix |
|---|---|---|---|---|
| Layout and look: two tracks, 4 x 52 dp handle, 6 dp gaps, 12/2 dp corners, symbol by level | ok | BrightnessSliderDimensions (H) | `BrightnessBlock.kt`; `comp.qs.brightness.*` | none |
| Motion: symbol between tracks, tween 100 ms | ok | IconAppearSpec (H) | `BrightnessBlock.kt:72`; `motion.qs.slider-icon` | none |
| Motion: **handle narrows while dragged** (M3 Expressive slider: the thumb's width halves while pressed) | missing? | Material3 `SliderDefaults.Thumb` (M) | constant width (`BrightnessBlock.kt:121-141`) | **to capture in the emulator** (hold the handle); `comp.qs.brightness.thumb-pressed-width`, role `motion.qs.slider-press` |
| Motion: level on a tap or an outside change | off | AOSP animates the level (M) | `motion.cc.level` (iOS Control Center's role, `BrightnessBlock.kt:46,83`) | `motion.qs.slider-level` (below, B-new 2) |
| Interaction: ticks while dragged | ok | haptic ticks (H) | `lastTickLevel` | none |

### 6. Edit page

| Aspect | Rating | Android 17 | Ours | Fix |
|---|---|---|---|---|
| Layout, look: Edit tiles, box, categories, Reset dialog | ok | EditModeKt (H, stage 4) | `QsEditPage.kt`; `comp.qs.edit-page.*` | none |
| Motion: crossfade 500 ms, placement spring 400 / 0.75, appear | ok | (H) | `motion.qs.edit`, `-edit-place`, `-edit-appear` | none |
| Interaction: resize by handle, long-press drag, auto-scroll | ok | (H) | `QsEditPage.kt` | none |
| Motion: overscroll of the page's scroll | to capture | platform stretch (M) | our scroll (not read) | as section 4 |

### 7. Media player

| Aspect | Rating | Android 17 | Ours | Fix |
|---|---|---|---|---|
| Layout, look: 379 x 176 dp card, art colours, squiggly progress | ok | remedia (H, stage 5) | `MediaBlock.kt` | none |
| Motion: **play button shape morph** (round paused, 16 dp playing), wave calming | ok | (H) | `motion.qs.media` | none |
| Interaction: swipe aside to settings | ok | SwipeToReveal (H) | `MediaBlock.kt:231-257`; `motion.qs.media-reveal` | none |
| Interaction: several sessions | missing | a carousel of players (H, noted stage 5) | one session | block option `media.carousel`; role `motion.qs.page` reused |
| Interaction: long press (hide the player), output switcher | missing | Android's options and its output dialog (H) | output opens Settings' media output panel (`MediaBlock.kt:580`) | stock seam: a `media-output` sheet later |
| Look: light following the touch | missing | remedia ripple (H) | none | `motion.qs.ripple` with the card's colours |

### 8. Notifications (cards, groups, sections, footer)

| Aspect | Rating | Android 17 | Ours | Fix |
|---|---|---|---|---|
| Layout: cards 28 dp, 16 dp sides, joined 4 dp, sections, Silent | ok | (H) | `CardsBlock.kt`, `NotifRows.kt`; positions B-056 | B-056 |
| Look: groups, children, hairlines, chevrons | ok | (H, B-034) | B-034 slices 1-3b | B-039, B-040, B-043, B-044 |
| Motion: swipe, reflow, appear, leave | **wrong** | SwipeHelper fling and the stack's own animations (M) | iOS Notification Center roles: `motion.nc.swipe-out/swipe-back/reflow/appear/leave` (`CardsBlock.kt:223-341,1435-1438,1509`) | own roles `motion.qs.card-*` with Android's values (B-new 2) |
| Motion: Clear all, rows flying out one after another | wrong | the stack's own stagger (M; delay not read) | the App Switcher's `motion.switcher.clear-stagger` (`CardsBlock.kt:1500`) | `motion.qs.clear-stagger` (B-new 2) |
| Motion: **magnetic swipe** (neighbours follow the swiped card, it detaches with a haptic, reattaches past a threshold) | missing | `MagneticNotificationRowManagerImpl`, Android 16 QPR1 (M) | none; a cut's corners do round (B-034 3b-2) | B-new 3: roles `motion.qs.card-magnetic`, `-detach`; token `comp.qs.card.magnetic-pull`, `comp.qs.card.detach-threshold` |
| Motion: list scroll physics and its ends | **wrong** | OverScroller fling and the platform stretch at the ends (M) | `IosScroller` (`CardsBlock.kt:110,585,1406-1408`): iOS deceleration and rubber band | B-new 1: an Android scroller (fling friction, stretch) chosen by the layout or the preset |
| Motion: swiped card keeps its surface, content fades; row stays until its swipe ends | off | (H, B-035) | B-045 | B-045 |
| Look: recently alerted bell | missing | RowAlertTimeCoordinator (H) | none | B-051 |
| Interaction: inline reply and smart replies | missing | Reply action with RemoteInput, smart reply chips (H) | actions with RemoteInput filtered out (`CardsBlock.kt:567`) | B-new 8 (and B-001's keyboard) |
| Interaction: long press (Alerting, Silent, Turn off) and snooze | missing | notification guts (M) | none | B-new 9; tokens from `component.notification` |
| Look: messaging, big picture, progress (Android 16 progress-centric) styles | missing | template styles (M) | title and text only | to capture which styles the S24 shows; slice per style |
| Interaction: tap opens the app from the card | ok | ActivityTransitionAnimator from the row (M) | `CardsBlock.kt:1482` passes the rect (B-011 for iOS) | none |
| Footer: history, Clear all, settings | ok | (H) | `CardsBlock.kt:1463-1464`; stage 8 sizes | none |
| Privacy over the lock screen | ok | redaction (H) | B-041 fixed; B-042 open | B-042 |

### 9. Quick Settings footer

| Aspect | Rating | Android 17 | Ours | Fix |
|---|---|---|---|---|
| Layout: build number, dots, edit; active apps, user, settings, power | off | (H) | build row and edit ~210 px low | B-052 |
| Look: "N apps are active" pill | off | (H) | B-058 | B-058 |
| Interaction: the pill opens the foreground apps' dialog (Stop buttons) | wrong | FgsManager dialog (M) | Settings' Manage apps (`QsFooterBlock.kt:177`) | B-new 10 |
| Interaction: user avatar | off | the user's picture (H) | generic symbol | noted stage 3 |

### 10. Heads-up notifications (banners) under the Pixel layout

| Aspect | Rating | Android 17 | Ours | Fix |
|---|---|---|---|---|
| Layout and look: the notification card itself (app line "App · now", chevron, actions as words) | wrong | heads-up = the row (M; the withdrawn `pixel-v1` measured it: `PxCardPainter`) | `BannerView.kt` keeps iOS's anatomy, Pixel colours (`comp.banner.flat-color` etc.) | B-new 5: banners draw `CardsBlock`'s card under the Pixel layout; tokens `comp.qs.card.*` |
| Motion: slides down from above the edge | off | HeadsUpAnimator (M) | iOS drop-in (`motion.banner.*`) | `motion.qs.heads-up-in/out`; **to capture in the emulator** |

### 11. Status bar (Pixel)

| Aspect | Rating | Android 17 | Ours | Fix |
|---|---|---|---|---|
| Layout: time left, notification icons, system icons right, battery glyph | scope | (H, PIXEL_PROFILE: built on `pixel-v1`) | iOS layout only (`Layouts.kt:89-93`) | the D1 round: a `pixel` status bar layout; tokens `comp.statusbar.pixel.*`; B-053 is its in-shade symptom |
| Look: Android 16 live-update chips (timers, navigation, rides), call chip, screen-record chip | scope | (M) | none | with the layout; B-002, B-003 |
| Battery glyph (Android 16 QPR2+ may draw the percentage inside) | to capture | (L) | iOS battery | **to capture in the emulator** |

### 12. Home, app drawer, recents, menus (Pixel Launcher)

All **scope**: no Pixel layout on `main`, and the Pixel theme writes no `comp.home.*`, `comp.library.*`, `comp.switcher.*`
token, so "Look › Pixel" for the whole phone leaves them iOS glass with Google Sans Flex.

| Element | Rating | Android 17's Pixel Launcher (H from docs/PIXEL_PROFILE.md captures unless marked) | Ours | What a Pixel layout needs |
|---|---|---|---|---|
| Home | scope | At a Glance top left, 4 x 5 grid, 60 dp icons with names, dock without platter, page dots above it, search bar at the bottom (G, mic, Lens), long press empty space: Wallpaper & style, Widgets, Home settings; swipe down opens the shade (M: depends on its setting) | iOS grid (`Layouts.kt:118`) | `home` layout `pixel` (HomeMetrics `pixel`), tokens `comp.home.pixel.*`; `pixel-v1` parts 3 and 8 as reference |
| App drawer | scope | a sheet from the bottom: search, predicted row, "All apps", A-Z 4 columns, fast scroller; Launcher3 pull timing (home fades over 40 %, scrim 10-50 %, apps 10-60 %) | App Library only (`Layouts.kt:109`) | `drawer` layout `grid`, tokens `comp.grid.*`, roles `motion.grid.*`; the `pixel-v1` blur bug (D4) to avoid |
| Recents | scope | carousel, cards 68 % of the screen, app chip above each card (menu: App info, Split, Pin), Screenshot and Select under the card, Clear all after the oldest, swipe up to dismiss | iOS deck (`Layouts.kt:96`) | `recents` layout `carousel`, tokens `comp.switcher.carousel.*`, roles for dismiss and Clear all |
| Context menus | scope | popup above the icon (App info, Widgets, Pause app...), shortcuts below, M3E rounded containers | iOS lift-and-blur menu | `menu` layout `pixel`, tokens `comp.pxmenu.*` |
| Folders | scope | Android 16 QPR3 folder motion (docs/MOTION_STYLES.md: dolly zoom, closing bounce; M) | iOS folders | with the home round |

### 13. App open and close, and the Material motion preset

| Aspect | Rating | Android 17 | Ours | Fix |
|---|---|---|---|---|
| Opening style | wrong for a Pixel profile | Pixel Launcher grows the window out of the icon (FloatingIconView) with home stepping back (M) | `material.json` `motion.style.opening` = `container` (judged: Material 3's container transform, not Pixel's launch) | a `pixel` motion preset: `motion.style.opening` `zoom` with Launcher3's springs; **to capture in the emulator** (10x slow, Pixel Launcher) |
| Closing (swipe home) | to capture | Launcher3 RectFSpringAnim into the icon (M; its damping and stiffness not read) | `container` back, `motion.app.close-*` at 200 / 1.0 | read Launcher3's `swipe_up_rect_*` resources from the emulator APK, then the preset |
| Spring overshoot (M3 Expressive spatial springs 0.6-0.8) | off, by design | expressive scheme bounces on spatial moves (M) | `material.json`: 124 springs, only 25 with damping < 1; 99 forced to 1.0 "where iOS 27 does not overshoot" (CLAUDE.md: code never handed an overshoot it never saw) | scope: make roles overshoot-safe one by one (int alphas, edge-anchored panels), then give Material its 0.8 / 0.6 |
| Predictive back (app shrinks with the back swipe) | scope | Android 15+ (M) | stock Back arrow | B-020 |

### 14. Cross-element seams under the Pixel profile

| Aspect | Rating | Android 17 | Ours | Fix |
|---|---|---|---|---|
| Volume keys | scope | Android 16 QPR1's M3E volume panel (vertical sliders, M) | the phone's own panel shows (no volume panel of ours; `GestureNav.kt:630` only mentions the system's) | a volume element; out of v1 unless the owner adds it (rule 2: no stock panel beside ours) |
| Haptics as a preset layer | missing | MSDL tokens per interaction (H) | fixed constants in code (`TileGridBlock.kt:346-348`) | SHADE_PIXEL_EVALUATION "Motion", the haptics layer |

## Material 3 Expressive: shape morphs and squash-and-stretch (the owner's question)

Where Android 17's shade squashes, stretches or morphs, and where ours does:

| Effect | Android 17 | Ours |
|---|---|---|
| Quick row tiles grow from 30 % height as the shade comes (`verticalSquish`) | yes (H) | **built** (`comp.qs.tiles.squish`) |
| Pressed tile grows 8 dp into its row, neighbours squeezed | yes (H) | **built** (`motion.qs.tile-bounce`) |
| Tapped tile's symbol 1.1 / words 1.06 pop | yes (H) | **built** (`motion.qs.tile-pop`) |
| Tile corners capsule to 24 dp, box circle to 16 dp | yes (H) | **built** (stage 8) |
| Play button round to rounded square | yes (H) | **built** (`motion.qs.media`) |
| A swiped child's neighbours round at the cut | yes (H) | **built** (B-034 3b-2) |
| Swiped notification pulls its neighbours, then snaps free (magnetic) | yes, 16 QPR1 (M) | **missing** (B-new 3) |
| Lists and the pager stretch at their ends | platform stretch (M) | **missing**: iOS rubber band in the list, linear quarter page in the pager (B-new 1, B-new 6) |
| Slider handle narrows while held | M3E slider (M) | **missing?** to capture (B-new 7) |
| Spatial springs overshoot (bounce) | expressive scheme (M) | Pixel shade's own roles follow AOSP (which mostly does not bounce in the shade: H); the generic `material` preset damps 99 of 124 springs |

So the shade's own squash effects are in; the ones missing are the platform's (stretch overscroll), 16 QPR1's magnetic
swipe and possibly the slider's pressed handle. The broader "everything bounces" feel of M3 Expressive belongs to the
motion preset and to elements we have not built for Pixel.

## The fidelity harness under-covers the Pixel shade

- The golden holds 4 states x 2 themes; `tile-on` is byte-identical to `expanded` (same md5 `ec6a2cc1...` light,
  `ebef6bd6...` dark), so no active large tile and no active two-target tile is compared (the stage-8 corner bug class).
- 7 tiles fit one page: the pager, its dots and page 2 were never compared.
- No golden for the edit page, the media player, an expanded or grouped card, landscape, or any motion (the MF motion
  criterion is still open).

## Gap counts

| Severity | Count | Items |
|---|---|---|
| wrong | 9 (6 new) | new: tile details open Settings, card motion on iOS roles, Clear all on the switcher's stagger, list scroll physics, FGS pill target, heads-up anatomy; existing: header icons (B-053), carrier (B-054), unavailable tile (B-059, B-065) |
| off | 10 (4 new) | new: tile set (no Screen record), slider level role, user avatar, heads-up motion; existing: backdrop (B-057), clock size (B-064), active tone (B-046), swiped card (B-045), footer rows (B-052), pill (B-058) |
| missing (in a built element) | 13 (11 new) | new: magnetic swipe, stretch overscroll (list, pager), slider pressed handle, launch from a tile's rect, media carousel, media long press and output, media touch light, inline reply, guts and snooze, notification styles, haptics layer; existing: recently alerted (B-051), gesture handle (B-055) |
| scope (not built for Pixel) | 11 | status bar, live-update chips, home, drawer, recents, context menus, folders, a Pixel motion preset (open/close), M3E overshoot in the `material` preset, predictive back (B-020), volume panel |
| to capture in the emulator | 10 | shade overscroll, depth zoom, landscape without media, pager stretch and spacing, the owner's missing pages, slider handle, tile dialogs vs details, heads-up motion, Pixel Launcher open/close springs, battery glyph |

## Proposed bugs, ranked

- [B-new] P2, shade (Pixel), the notification list scrolls on iOS physics (`IosScroller`, `CardsBlock.kt:110`): iOS deceleration and a rubber band at its ends, where Android 17 flings with OverScroller and stretches at the ends. Repro: Pixel shade with a list taller than the screen, fling to the end and past it; compare with the emulator's own shade at 10x. Status: open (to capture in the emulator)
- [B-new] P2, shade (Pixel), the notification cards, Clear all and the brightness level move on other elements' roles: `motion.nc.swipe-out/swipe-back/reflow/appear/leave` (iOS Notification Center), `motion.switcher.clear-stagger` (the App Switcher) and `motion.cc.level` (Control Center) (`CardsBlock.kt:223-341,1435-1509`, `BrightnessBlock.kt:46,83`); under iOS 27's preset they bounce as iOS, and the settings' Quick Settings moment does not hold them. Fix: `motion.qs.card-*`, `motion.qs.clear-stagger`, `motion.qs.slider-level` with AOSP's values, in the Quick Settings moment. Repro: Pixel shade on the iOS 27 preset, swipe a card back, Clear all, tap the slider; slowed. Status: open
- [B-new] P2, shade (Pixel), no magnetic swipe: a swiped notification does not pull its neighbours nor detach with a haptic and reattach past a threshold, as Android 16 QPR1+ does (`MagneticNotificationRowManagerImpl`, flag `magnetic_notification_swipes`). Repro: Pixel shade with three cards, drag the middle one slowly sideways; compare with the emulator's own. Status: open (to capture in the emulator)
- [B-new] P2, shade (Pixel), tapping a tile's details (Internet, Bluetooth, Modes, Cast) launches a Settings activity (`Controls.kt:529-545`) with no motion from the tile, where Android 17 opens the tile's own dialog grown out of the tile (`tile.onClick(expandable)`; an in-shade details view behind `QsDetailedView`): a stock seam. Repro: Pixel shade, expand, tap the Wi-Fi tile's words. Status: open (to capture which tiles use dialogs on the emulator)
- [B-new] P2, shade (Pixel), heads-up banners keep iOS's anatomy (`BannerView.kt`) in Pixel colours; Android 17's heads-up is the notification card (app line, chevron, actions as words). Repro: Pixel layout, `TEST_NOTIFY --es title T --es text X --ei id 7` over home; compare with the emulator's own heads-up. Status: open
- [B-new] P3, shade (Pixel), the tile pager moves linearly up to a quarter page past its first and last page (`ShadePanelView.kt:468`) where Compose's pager stretches. Repro: Pixel shade with two pages, drag right on page 1. Status: open (to capture in the emulator)
- [B-new] P3, shade (Pixel), the brightness handle keeps its width while dragged (`BrightnessBlock.kt:121-141`); Material 3 Expressive's slider narrows it while pressed. Repro: hold the handle, compare with the emulator's own. Status: open, unverified (to capture in the emulator)
- [B-new] P3, shade (Pixel), notification actions that take a reply are dropped (`CardsBlock.kt:567` filters RemoteInput) and no smart replies show: a message notification shows no Reply. Related to B-001. Repro: a messaging notification with a reply action in the Pixel shade. Status: open
- [B-new] P3, shade (Pixel), a long press on a notification does nothing: no Alerting / Silent / Turn off options and no snooze, as Android 17 offers. Repro: Pixel shade, long press a card. Status: open (to capture in the emulator)
- [B-new] P3, shade (Pixel), the footer's "N apps are active" pill opens Settings' Manage apps (`QsFooterBlock.kt:177`); Android opens its foreground apps dialog with Stop buttons; footer and tile launches have no origin motion (`Shade.kt:249`). Repro: start a foreground service, tap the pill. Status: open
- [B-new] P3, shade (Pixel), one media session only: no carousel of players, no long press to hide, the output chip opens Settings' panel (`MediaBlock.kt:580`). Repro: two apps playing. Status: open
- [B-new] P3, fidelity harness, the golden's `tile-on` is byte-identical to `expanded` and its 7 tiles fit one page, so active large tiles, two-target tiles, the pager and its dots are never compared; no edit page, media, landscape or motion goldens. Repro: `tools/fidelity/golden/CP41.260828.004.A7/manifest.json` md5s. Status: open
- [B-new] P3, docs, Pixel's landscape is described two ways (PLAN_LAYOUTS_THEMES D: one row of tiles over a 70 % column; SHADE_PIXEL_EVALUATION stage 6: the split with a player); stage 6 was measured with media. Repro: the emulator's own shade turned, no media. Status: open (to capture in the emulator)
- [B-new] P3, shade (Pixel), owner reports Quick Settings pages missing; the pager is built (`ShadePanelView.kt:451-539`, dots `QsFooterBlock.kt:99-111`) and the default set makes 2 pages: which screen, tile set and device height he saw is unknown. Repro: ask the owner; the S24 on the Pixel layout, expand, swipe the tiles sideways. Status: needs the owner's details

Scope gaps (not bugs, for the owner to place on the roadmap): the Pixel status bar (D1), home (D5), drawer (D4), recents
(D3), context menus (D6) and folders; a `pixel` motion preset with Pixel Launcher's own launch and close (the `material`
preset's container transform is not Pixel's); Material 3 Expressive's overshoot in the `material` preset; predictive back
(B-020); a volume panel of our own; haptics as a preset layer; the Pixel layout leaving developer preview.
