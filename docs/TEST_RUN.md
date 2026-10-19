# Full test run (started 2026-10-19)

Matheesha: "Time to do a full testing run on my device. flag any and all issues you can find. leave no stone unturned ...
test every case which each element is affected and test it's behaviour". Every case below is run on the S24 (timings:
frame stats, Perfetto; looks: recordings of home and our own screens only, never Notification Center, banners or other
apps) and, where only behaviour matters, on the emulator first. Each line ends with its result: **ok**, **issue** (with
what was seen and the fix's commit), or **not run** (why).

## Reported before the run

| # | What | Found | Fix |
| --- | --- | --- | --- |
| R1 | A flicker after closing the settings into home | S24 recording: ~120 ms after the slide back, home vanished into a flat dark grey for ~90 ms, then faded in over ~150 ms. The shell's start of home got a snapshot starting window (home keeps no snapshots: a grey surface), which the system faded out over home after home's picture had already gone (it went when home *drew*, not when home was on screen). | Home started with no starting window (`android.activity.disableStarting`, InstantTransitions); the picture goes once home's transition is committed (GestureNav.coverHome). S24: no starting window, no jump. |
| R2 | Changing themes, then scrolling home to the App Library, freezes the launcher for a moment | Perfetto: the settings, out of sight, made a still of themselves and composed and laid out their whole tree again at the theme change (~100 ms of the main thread, as home's reveal began); the first swipe to the App Library after the rebuild recorded every tile and uploaded every icon in one frame (59 ms). | The settings hold a change of the look while away (LookGate) and make it once the launcher is still (design/Still) or as they come back; home draws the App Library once off screen after each build, once idle. S24: the App Library warmed 2 s after each change (31-37 ms, off the main thread), its first swipe no longer a long frame; the settings' work moved off the reveal but is still 160 ms when made (see P2). |
| R3 | Animations skipping sometimes (rare) | Found one cause for all of them (P1): after every update our own code ran interpreted until the JIT had seen it, so the first time each animation ran after an update was slower (Perfetto: "JIT compiling dev.launcher.app..." inside long frames). | P1. |

## Found during the run

| # | What | Found | Fix |
| --- | --- | --- | --- |
| P1 | Our own code was never compiled at install | The install profile (`.dm`) held only the libraries' rules: 0 of our methods (`mergeDebugArtProfile/baseline-prof.txt`). Home, gesture nav, the shade and the settings ran interpreted after each update until the JIT warmed each path: every first open, close, theme change after an UPDATE was slower. | `app/src/main/baseline-prof.txt`: every class and method of ours (wildcards, profgen expands them). S24: 62k of our entries in the phone's profile, `speed-profile [reason=install-dm]`; the settings' first frame after a start 225 → 154 ms; no JIT of our code in traces. Install 18 s. |
| P2 | The settings away still compose their whole tree for a change of the look | 100 ms recompose + 58-85 ms layout once the launcher is still (every page is kept built). A touch landing then waits for it. | To fix. |
| P3 | A long press on home re-lays out the card window | nav-ui `relayoutWindow ... params=true` of LauncherCards at the touch (11 ms): the status bar's frame missed. It was made at window alpha 0 and shown at the first touch on home (once per window). | Made at alpha 1 (it stays so anyway). |
| P7 | Blurred shapes drawn again on the CPU at every frame they move | simpleperf of the render thread during App Library swipes: `GrBlurUtils::DrawShapeWithMaskFilter` (7 %), a new texture per shape per frame (Skia keys a blurred mask by its fractional offset): the dock's shadow (a blurred rounded rect sliding with the dock), the library field's magnifier (a stroke with a shadow layer). Also the lifted icon's shadow in edit mode, the scrub bubble's, the widget gallery cards'. | `theme/SoftShadow`: rendered once per size into an alpha bitmap (a quarter size for wide blurs), drawn scaled in the paint's colour; the magnifier's shadow likewise (its stroke stays vector). |
| P8 | Live glass partly off screen made a new GPU layer every frame | The App Library field's glass node was cut to the screen: as the library slid in, a node of another width each frame (`drawLayer [material] 108..1000 x 293`). 1768 image allocations (85 ms) in 3 swipe pairs; the GPU cache filled with scratch layers (99.8 MB of 121), so the library's icons left it and were uploaded again at each slide's first frame (prepareTree 10-14 ms). | `MaterialPainter.drawLive`: the node keeps its whole size, its content read inside the screen only (a clamp pass before the blur, as the cut node's edge did). 174 allocations (3.6 ms), the cache at 47 MB, texture uploads 1189 → 216. |
| P9 | Glass uniforms set one by one through JNI at every draw | simpleperf of the main thread: `RuntimeShader.setFloatUniform` 19 % during the library's slide (each tile set all ~30 uniforms; between tiles only the place changes). | `MaterialPainter`: a uniform is set only when its value changed. |
| P10 | The library's tiles drawn twice per frame during its slide | The field's live glass drew the tiles pane again into its own recording (`v.draw`: a quarter of the main thread). | `TilesPane` records itself into a render node once per frame; the field draws that node. Main thread 2.6 ms a frame on average during swipes; 3 late frames in 11 swipes (was 5 in 6), GPU-bound. |
| P11 | The first swipe to the App Library after an app was open re-uploads its icons | ~45-83 icons, prepareTree 6-11 ms in that swipe's first frame (the renderer lets textures go that its frames have not used for a while). | `HomeScreen.warmIconTextures`: when home comes back to the front and is idle, `prepareToDraw` on every app icon (uploads only what left, between frames). S24: to measure. |
| P12 | Spotlight's first pull: 25 and 45 ms frames | Not shaders: the autofill framework built home's whole window structure (every text laid out, 17-25 ms) when Spotlight's field first took the focus. | No autofill on home's and the settings' windows (`IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`). |
| P13 | Every minute, a burst of garbage (GC pauses in whatever moves then) | Heap profile (Perfetto, Java allocations) of home's swipes: 84 MB in 70 s, 80 of it the glass clock's numerals made at each minute: ~25 MB of arrays per clock, most of it every pixel's index boxed for the distance transform's lambda. Plus every launch and close: 2 "NativeAlloc" GCs (snapshots and pictures wrapped as bitmaps), checkpoint pauses up to 3.8 ms on our threads. | `ClockNumerals.build`: working memory kept, the transform reads the mask directly, the percentile by selection (tested against a sort), the field written row by row. 84 → 4.8 MB in the same 70 s. Native GCs: to measure. |
| P14 | Notification Center's first pull: one 82 ms frame | Gesture nav's thread read another app's name and icon while drawing (`LoadApkAssets` of the Permission Controller: 65 ms). | `shade/AppInfo`: names and icons read on a worker before `Notifs` publishes a new app's notifications; the painters only look them up. |
| P15 | Control Center's first opening: SurfaceFlinger 13 ms longer | Its first blur ("BlurRegion #0, radius=4"); the live blur's prewarm used strength 0.02, which rounds to radius 0: no blur. | A real blur in a small square in the display's bottom-left corner (hidden by its rounded corner) for 300 ms after the window is added. |
| P16 | First uses after an update compile in their first frames | Edit menu 46 ms (its glass, rows, symbols), the library's search 18 ms (every row's name laid out), blurs whose radius ramps (one program per kernel size). | Warm-ups off screen while idle: home's menu (a menu made for it), every blur radius (`GpuWarmUp.blurLadder`), the library's list; renders wait while home is touched or a gesture runs (`GpuWarmUp.quiet`). |
| P17 | Home's picture for the theme cards read back on the render thread | `copy(ARGB_8888)` of a GPU bitmap: 14-32 ms of the render thread every window draws with, as the settings slid in; and taken 0.9 s into a new look's 1.8 s reveal. | `ViewPicture.softCopy`: halved on the GPU into a CPU-readable buffer; taken once the launcher is still (`Still`), never while a page or scroll moves, not again right after home handed one over. |
| P18 | After a crash while Android bound the notification listener, our shade stayed empty | Emulator: an install raced the app's start (Android's handleBindApplication crashed), the system kept the listener's binding as made ("is already bound") and never retried: no notifications, no badges until a reboot. | `LauncherApp.watchNotificationListener`: not connected 8 s after a start (access granted): the component switched off and on (the system binds it afresh), up to three times. |
| P4 | Entering edit mode: a 32 ms frame | Home's frame at the Edit tap (main 18.8 ms, render 24.8 ms). | To fix. |
| P5 | Home's menu stalls while the settings open over it | The settings' first frame (154 ms on the main thread) holds home's frames: the menu's close stops for it. | To fix. |
| P6 | The tiles' scenes stutter while the pages are built ahead | Pages built one a frame after the slide in: 20-45 ms frames while the tile scenes play. | To fix. |

## Plan

### Home
- H1 Cold start and unlock arrivals; screen off and on over home.
- H2 Pages and their ends (rubber band); the page indicator.
- H3 App Library: in and out, scroll, a category folder (open, launch, close), search (keyboard, results, launch, cancel).
- H4 Spotlight: pull, type, results, launch, dismiss.
- H5 Launch and close from the dock, a page, the library, a folder, Spotlight, in every transition style; a launch reversed
  during its close, a launch grabbed, a cancelled close.
- H6 Menus: an icon's, a widget's, home's (edit mode); drag, drop, remove, add a widget, resize, widget styles; Done.
- H7 Widgets: the clock's minute, an app's widget, dark mode, a palette change.
- H8 Badges coming and going.
- H9 A new wallpaper (from the picker; while home shows), the colours following it.
- H10 Another theme with home in front (reveal), the App Library after it.
- H11 Light and dark: the phone's switch, home's menu.

### Gesture nav
- G1 Home from apps: a flick, a slow drag, a drag back (cancel).
- G2 App Switcher: hold, scroll, tap a card, flick one away, Clear All, empty space (home).
- G3 Quick switch both ways, quickly repeated.
- G4 Back gesture from the side over our screens and apps.
- G5 A mini player (PiP): a close into it, the switcher with it up.

### Shade
- S1 Status bar: time, icons, battery, charging moment, a full-screen app hiding it.
- S2 Control Center: pull, every toggle, sliders, media, edit mode and gallery, closing (tap, swipe).
- S3 Notification Center (frame stats only on the S24): pull, fan out, scroll, dismiss, open an app, clear.
- S4 Banners (frame stats only on the S24): in, out, swipe away, tap.
- S5 Over the lock screen.

### Settings
- T1 Opening from home's menu, the drawer, the App Switcher; closing with Back on the tiles, the home gesture, the App Switcher.
- T2 Every part's page; Look's and System's pages below; folds; search; Your changes and Reset.
- T3 Apply: theme, the four colour sources, style, intensity, your colour, key colours, Other apps, symbols, fonts (a Google
  font downloaded), size, motion preset, a moment's speed and bounce, Every detail, every part's layout and options.
- T4 The font sheet: open, search, categories, more results, pick, close (drag, the button, Back).

### System
- Y1 Restore system; the watchdog; Shizuku gone and back.

## Results

(filled in as the run goes)
