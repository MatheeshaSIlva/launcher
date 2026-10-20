# CLAUDE.md — Launcher (working name)

Read this first. It is the briefing for everything built so far and everything planned. Deeper detail lives in
`docs/REQUIREMENTS.md` (what we are building) and `docs/PROBE_FINDINGS.md` (what the feasibility probes proved, with numbers).

## What this project is

An Android launcher that merges the **home screen, recents and the notification / quick-settings shade** into one
themeable interface, with a theme engine and theme builder ("Hyprland-level" control over looks and motion).
It works **without root**, using **Shizuku** (a helper process with shell rights, uid 2000, started through wireless
debugging). The app also works without Shizuku as a plain launcher; Shizuku unlocks the advanced features (and those are the paid tier).

Owner: Matheesha (CS student, strong Linux/sysadmin background). Test device: **Galaxy S24, Android 16, One UI**.

## The three rules that outrank every feature

1. **Perfectly smooth.** Open, close, swipe and panel pulls follow the finger and hold the display's refresh rate
   (120 Hz). Matheesha's words: "not a single stutter". Measure it (frame logs), do not eyeball it.
2. **Fully consistent.** One shade, one status bar, one gesture set, everywhere. No stock panel may appear next to ours.
   Showing our status bar "on the home screen only" was explicitly rejected.
3. **Attention to detail, always.** Every single change on screen is animated, fluidly and cleverly: nothing pops, snaps,
   jumps or "just appears". That includes the small things (a badge count, a label toggle, a list arriving, a minute
   change, a bar sliding in) and the states between states (home after unlock or a cold start, a handover from a card to
   the real icon, a folder closing while another opens). Every animation is a spring or an ease that follows the finger
   where there is one, can be interrupted and retargeted without a jump, and never makes the user wait for it to finish
   before the next thing can be tapped. Before calling a screen done, go through every element on it and every way it can
   change, and ask "how does this move, and what shows on the frame in between?". Matheesha reviews at this level.

## How we work with Matheesha

- He tests on the phone and pastes logs or describes what he saw. Give him **short, exact steps** and ask for specific
  results ("paste the log", "did it look like X or Y?"). Do not bury the ask.
- He prefers direct, honest feedback; say plainly when something failed or when a result is inconclusive.
- Do not claim something works until a device log or his words confirm it. Several earlier "it works" claims were withdrawn
  (blur tests that silently drew nothing; a watchdog that never fired). Verify, then state.
- Builds: GitHub Actions builds every push to `main`; the in-app **UPDATE** button installs the newest build silently
  through Shizuku. The first line of the app log shows `BUILD <sha7>` — check it matches the commit before trusting a test.
- Write results and decisions into `docs/` as we go; the spec is a living document.

## Architecture (target)

- **Launcher app** (our process): home canvas, drawer, recents, shade, status bar, theme engine, builder — all our own views/windows.
- **Shizuku user service** (shell uid): calls hidden framework APIs by reflection (task snapshots, task switching, statusbar
  disable flags, animation scales, app launch, silent install). Windows created *from* this process do not work, so
  everything visible lives in the app process.
- **Overlay windows** (`TYPE_APPLICATION_OVERLAY`, always `FLAG_HARDWARE_ACCELERATED`): gesture strip + full-display card window.
  Every overlay of ours lies above a mini player (picture-in-picture); while the App Switcher is open with one up (Android
  14+), the deck moves into an activity's window under it (`switcher/SwitcherActivity`, a translucent activity of its own
  task drawn in software, showing a `SurfaceControlViewHost` that gesture nav's thread draws: `switcher/SwitcherHost`), in
  one frame of both windows (`SurfaceSyncGroup`), and back into the card window when an app or home is chosen.
- **Watchdog** (shell-side loop): restores animation scales and status bar if the app's heartbeat goes stale. Must exist
  before any feature that hides stock UI.
- **Layouts** (`layout/`, plan `docs/PLAN_LAYOUTS_THEMES.md`; counts as options with the room the size leaves: home's
  columns, rows and dock icons, Control Center's columns, the App Library's tile columns): the five elements (status bar, shade, recents, drawer,
  home), the layouts registered for each (`Layouts`: id, placements, options with limits) and the user's choice of each
  (`Setup`, `files/setup.json`; never in a theme). The shade's layouts are files (`assets/layouts/shade/<id>.json`,
  `docs/LAYOUT_FILES.md`: what the layout is, its places (each saying where each panel is pulled from) and options, and its structure: panels and blocks), read
  strictly at start before the setup (`Layouts.init`, `layout/LayoutFile`, `layout/ShadeStructure`); a copy of your own
  through the inbox wins. `HomeConfig` reads the drawer and home options from it. Over adb:
  `am broadcast -a dev.launcher.app.SETUP -p dev.launcher.app --es element ID [--es layout ID] [--es placement ID]
  [--es option KEY=VALUE]`.
- **Settings app** (`settings/`, plans `docs/PLAN_SETTINGS.md`, then `docs/PLAN_SETTINGS_V2.md`): Jetpack Compose
  (`SettingsActivity`, own task), an element of
  its own (`Element.SETTINGS`, layout "studio", tokens `comp.settings.*`), drawn with the theme's tokens through
  `SettingsTheme`/`Look` (no Material components; motion roles as Compose specs). Studio (draft D in Figma; V5,
  2026-10-16: no live picture of home, the live preview belongs to the token editor): a sheet of cards under the title; a
  card grows into the stage at the top of its page (`motion.settings.open`), one value moving everything; pages are built ahead and only placed while open (a page built in a transition's first frame cost the S24
  60-80 ms); solid groups (no glass per card). Changes wait in a draft for Apply (`Draft`, `ApplyBar`, `Design.batch`:
  one reveal for all of them); a look change is revealed from the tap
  (`ThemeReveal`). Structure B (V1, 2026-10-15; regrouped 2026-10-19, `docs/SETTINGS_NAVIGATION.md`): tiles (`Part`, in
  the start page's order) one per part of the phone (Home, App Library, Apps, Notifications & Control Center, Status bar:
  its layout, where it lives, its options, its own theme, on its page: `PartPage`), Look and Motion (the whole phone) and
  System (the launcher's own; "Phone" until 2026-10-19), under headings (`TILE_GROUPS`: Your phone, Style, System alone),
  the quick choices over them (`QuickGroup`: light and dark, the colours' source, the animation preset, the same draft as
  Look's and Motion's). Three layers on every page: what most people change in sight, the rest in folds (`settings/Fold.kt`:
  a row that names what it holds, its rows unfolding in place on the appear role over `comp.settings.fold.fill`, built
  at the tap and opened two frames later, left as it was in the next session too: `SettingsPrefs.openFolds`): More
  (Look's fonts, size and the settings' theme; each part's moments, one Motion row on its page; Motion's moments by part)
  and Advanced (Look's palette style, intensity and key colours; a moment's animations, its Every detail). Pages never go
  more than two deep; editors open in place. The tiles are solid cards, as the groups (no glass), one
  under another as wide as the sheet (Matheesha, 2026-10-15: "more polished and well thought out"): the part's name and
  what it holds on the left, on the right a scene of the part (`TileArt`, on the preset's springs and styles as the draft
  would apply them, on a clock that runs only while the cards are in sight) drawn on the card itself, no box of its own
  (Matheesha, 2026-10-16: "make it blend with the card itself"): in a frame inset from the card's edges, its colours from
  the part's colour under the theme's veil (`comp.settings.tile.visual*`; panels the veil's pastel in light, the card being
  white), cut only by the card's outline and kept off its words. Every scene rests on a whole picture of its part and
  plays one act a cycle (`ArtMoves.cycle`: 6 s, longer when the preset's moves are slower than an act and a rest), the
  acts running down the column a beat apart (`STEP`): a card or two moves at a time, and the cards come alive one after
  another as the page appears. Groups on the sheet take iOS's elevated cell colour (the
  sheet's own grey hid them in dark); the sheet is iOS's grouped grey in light (#F2F2F7: a white sheet left the white
  cards no edge). Under the wallpaper's colours each part keeps its own hue, harmonised toward the palette's accent
  (`partColor`, `MaterialYou.harmonize`: the palette's one primary had made every card the same). A part with one layout
  or one place says so in a caption under its stage (`PageLead`, which search lands on), not in rows that choose nothing;
  a part's own theme is offered only while there is another theme. Look and System push pages one level down (`SubPager`,
  `Sub`: Look's fonts and size, from its More; System's Developer page). A part's stage (`settings/Stage.kt`, over the page, no
  touches): the phone drawn as a motion graphic (`settings/PhoneScene.kt`) in the part's colours, at the part's band of
  the phone (`motion.settings.focus`), following the draft (home's grid, dock and names through `HomeMetrics`, gliding on
  a change; Control Center's columns; the App Library's place; Look on your wallpaper in the palette's light tones, light
  or dark; Look's Fonts page turns it into a type specimen), playing how the part is
  reached (a finger and what it opens, on the draft's curves) and its own act; a moment's page pushed below plays its
  samples on it (`MotionSamples` with `SampleKit.scene`). iOS 27's overview: the page is the blurred wallpaper, the panel on it, its buttons clear glass
  (`comp.settings.glass`). System holds Shizuku, updates, safety and About; seven taps on the build turn on the developer
  tools (Developer: the tests and the log; the token editor), until switched off on their page (`SettingsPrefs.developer`).
  Search (V3, `settings/Search.kt`): a field over the tiles; every setting indexed with its place and the words it
  answers to (settings before moments before single animations); a result grows its part's page out of its row, pushes
  the page it is on, opens the fold the row waits in (`SearchEntry.fold`) or a moment's Every detail, scrolls the row into
  sight (no further than the page goes) and lights it (rows register with
  `Modifier.anchor(name)` under their page, `LocalAnchorPage`; an animation's row as `roleAnchor(key)`). What you changed
  (`settings/Changes.kt`): every difference from the theme, the preset or a layout's defaults with before and after and
  Reset (into the draft); "Your changes" over the tiles, "N changes" at the foot of each page, a dot on each changed row.
  Opened from the drawer, home's edit menu ("Home Settings") and the "Launcher Settings" control. From the edit menu
  home goes into them (`settings/SettingsEntry`, `EntryState`): home pictures itself at rest with our bar on it, their
  first frame waits for it and shows it over the whole screen (the screen's own corners, no frame), and once the window
  is on screen it steps back, blurs and fades into the page as the sheet rises over it (`settings/HomeSlide.kt`); Look's
  theme cards keep such pictures per theme (`HomePictures`); Back on the tiles plays it the other way,
  the card window holds home's picture while they go back at rest underneath (their App Switcher card) and home comes
  (`GestureNav.ownScreenToHome`). The home gesture over them is a panel's, not an app's (Matheesha: "should just close
  it"): gesture nav hands the swipe up to them (`OwnScreenSwipe`, phase `OWN_PULL`) and the finger plays the slide Back
  plays, the way they opened the other way round (the sheet sinks, home's picture comes forward; 45 % of the window's height
  for the whole slide, from wherever a slide was), and a release closes them into home as Back does (the same hand-over) or
  springs them back (the Home gesture's thresholds); no App Switcher from them. (It once carried the sheet up and away with
  the finger; Matheesha, 2026-10-19: "should close it just like swiping back gesture does".) Their task is the one of ours
  in the App Switcher (`switchable`: by activity class).
- **Home (iOS profile, swappable)**: `apps/` (app list, categories, shaped icons), `motion/` (every animation by role, iOS scroll
  physics), `drawer/` (drawer style × placement; App Library), `home/` (config, iOS-proportioned metrics, layout model, home screen).
  Design notes in `docs/PROGRESS.md` ("Home experience, iOS profile").
- **Design system** (`design/`, plan in `docs/DESIGN_SYSTEM_PLAN.md`): every look value is a token in the active theme file
  (`assets/themes/ios27.json`: `ref.*` from Apple's kit with its ids, `sys.*` roles, `comp.*` per component), read through
  `Design` (light/dark blended at `Appearance.dark`, aliases followed, the user's edits from the token editor on top);
  `Scale` turns points into pixels. Themes: `assets/themes/<id>.json` (or `files/themes/<id>.json`, pushed with
  `DESIGN_RELOAD --es files import`;
  wins over the app's), `"extends"` another and hold only what differs; checked at load against iOS 27 (refused if a token
  the code reads is missing or of another kind); switch/reload: `am broadcast -a dev.launcher.app.DESIGN_RELOAD -p
  dev.launcher.app [--es theme ID]`, or the token editor's Theme button. Colours may name the phone's palette (`@primary`,
  `@system_accent2_900/40`: Material You, looked up when drawn); every theme may have a `materialYou` section used when the
  user picks the wallpaper's colours (`sys.color.source`; adb: `DESIGN_RELOAD --es colors wallpaper|theme`). iOS 27's maps
  its backgrounds, fills, labels and separators to Android 14's tonal roles (`@surface_container...`, `@on_surface`), its
  white "on" states to the primary container, and tints every glass (a COLOR layer of `@system_accent1_500` and a faint
  wash; written by the builder). A theme's own tokens win over the Material You of the theme it extends. The palette is
  the phone's (Wallpaper & style) or made by `design/MaterialYou` from the wallpaper's main colour in a style (tonal,
  vibrant, expressive, neutral, monochrome); `sys.color.style`, `sys.color.intensity` (chroma 0..2); made from the
  wallpaper or the user's colour (`sys.color.seed`), any palette's key colour set by hand (`sys.color.key.*`). The default
  style is Auto: the phone's palette only while it is the wallpaper's (`Design.phonePaletteIsWallpapers`: Android's record
  `theme_customization_overlay_packages`, its `color_source`, where the phone keeps one; else whether the phone's primary
  is one of the wallpaper's hues, `MaterialYou.followsWallpaper`), otherwise a tonal palette made from the wallpaper (One
  UI's Color palette off or set to a basic colour leaves the phone's palette blue under any wallpaper: Matheesha's green
  wallpaper came out blue). Its decision is in the log ("[design] the phone's palette ..."). The palette in use is one
  immutable object per state (`Design.Palette`: the choice and what the phone says, `Phone`, read on the main thread when
  the assets or the wallpaper change, never while drawing); a change of the phone's palette is shown (as a change of the
  look) only if a colour in use changed. The wallpaper's colours change only when home shows a new wallpaper (kept across
  starts in `files/design/wallpaper-colours.txt`): a palette that follows it changes along the wallpaper's reveal (home
  built again under it, its old look giving way on the same front: `home/FrontCover`). On a Galaxy "the system's" roles come
  from One UI's own shades (`MaterialYou.ShadePalette`; Android's roles follow Android's setting, which Other apps changes).
  Under Material You the settings' parts take the palette's primary, tertiary and secondary in turn (`partColor`, at the
  theme colour's lightness), as Android's Settings does. The settings name four
  sources (`Draft.colourSource`): the theme's, the system's (the phone's palette as it is: style `system` with no colour of
  one's own), the wallpaper's (made by the launcher), your own. **Other apps** (Look › Colours, `design/SystemPalette`, off
  by default, its own preference, never a theme's): Android's palette setting given the chosen colours (Matheesha: "system
  colours option push oneui's palette, wallpaper colours option will push the launchers"): the system's push One UI's
  palette as Samsung's picker would (its shade 300 of the first palette, TONAL_SPOT or MONOCHROMATIC), so Google's apps
  match Samsung's; the wallpaper's and your own push the launcher's seed and style; the theme's colours, the switch off and
  Restore system give the phone's own value back (unless changed since); Auto on a phone whose palette is already the
  wallpaper's gives nothing. Written with the launcher's self-granted WRITE_SECURE_SETTINGS, else the shell; only when it
  differs (a write makes every app reload its colours), and only once the launcher is still (not while the settings are in
  front nor during a reveal: a change of Android's palette lays every window out again), then on a home at rest and left
  untouched 5 s (its return to the front counts as a touch) or with the screen off, never while an app is in front (it
  would be told mid-use); log lines "[palette] ...".
  **Icon sets** (`design/IconSets`, `assets/icons/<id>.json` or `files/icons/<id>.json` through the inbox): symbols as
  vector paths that the shade draws in place of its own (`res/drawable/sym_*`, Material Symbols Rounded: the set
  `launcher`) where a theme names a set (`sys.symbols`, read through each element's own token: `comp.cc.symbols`,
  `comp.nc.symbols`, `comp.banner.symbols`; the status bar's next); a name the set leaves out falls back to the app's own,
  `NAME.off` is a control's look while off (crossfaded as it turns on: `Glyphs.draw(..., on)`), `scale` a small symbol in
  a larger slot. Written by `tools/icons/build_icon_set.py CACHE` from Android vector drawables (an animated one at its
  first or last frame) and SVGs, each symbol with its source: `pixel` is AOSP SystemUI's Quick Settings icons as each
  tile names them (the Pixel theme names it). The settings choose one for the whole phone (Look › More › Symbols); a
  set's every symbol beside the app's own: `am start -n dev.launcher.app/.PreviewActivity --es icons ID`.
  **A theme per element** (`design/TokenParts`): an element's code reads only its own namespace (`comp.statusbar.*`,
  `comp.nc/cc/banner.*`, `comp.switcher.*`, `comp.library/spotlight.*`, `comp.home/widgets/badge.*`, `comp.settings.*`;
  a test checks it), aliases into `sys.*`/`component.*`; an element given its own theme resolves its namespace there
  (materials' colour references bound in that theme). Default: everything follows the active theme. Stored in
  `files/design/elements.json`; adb: `DESIGN_RELOAD --es element shade --es theme graphite` (`--es theme follow` undoes).
  **Component catalogue** (`design/Components.kt`): shared styles as `component.*` tokens (notification card, control,
  slider, menu, badge, field, card, dock, sheet, panel...); the iOS layouts' `comp.*` tokens are aliases of them. A theme
  restyles components through `component.*`; new layouts read `component.*`, never another layout's `comp.*`.
  Paint v2: fills may be gradients (object form `{"gradient": ...}`), materials may have `strokes`, surfaces a radius per
  corner, corners smoothing (`sys.shape.corner-smoothing`). In AGSL, uniform arrays are indexed only by loop indices and
  constants (a function parameter as an index does not compile): gradient stops are read inside the fill loop.
  Fonts (`theme/Fonts.kt`, `FontFamily`): `sys.font.text` / `sys.font.display` name a family (`inter`, `system`,
  `system-serif`, `system-mono`, `google:Name`); Google fonts are downloaded through Play services' font provider into
  `files/fonts/google/` (then instant, offline); the settings' font search lists every family (`theme/GoogleFontCatalog`)
  and previews them from the cache without touching the design (`FontPreviews`). A paint made once takes its font through its view's
  `Fonts.Followers` (`fonts.text(this, 600)`, never `typeface = Fonts.text(600)` outside home, which is built again on
  every design change: `HomeScreen.rebuild`).
  **Motion is not the theme's**: animation presets (`assets/motion/<id>.json`, same file format, `ios27` the base) hold
  every `motion.*` role as a curve, a spring or a bezier (`{"bezier": [x1, y1, x2, y2], "ms": N}`); code asks
  `Motion.role(MotionTokens.X)` / `Motion.profile` and runs it through `Curve.mover()` / `MotionValue` (both kinds
  interruptible). Code never times an animation with a literal: Android's animators take a role through `.timed(curve)`,
  clock-drawn motion through `RoleTiming`; new roles are named in `motion/MotionRoles.kt` (a test checks it). Switch:
  `DESIGN_RELOAD --es motion ID`. Test preset: `eased` (every spring as a bezier; `tools/design/build_test_presets.py`;
  `"test": true`, shown in the settings only with Developer's switch). `material`: Material 3 Expressive's springs
  (`tools/design/build_material_preset.py`), bouncing only where iOS 27 does (code that never saw an overshoot is never
  handed one: an int alpha past 255 flashes, an edge-anchored panel shows a gap). A preset's Speed and Bounce
  (`motion.adjust.*`, the settings' Fine-tune) apply where a curve is read (`Design.curve` through `motion/MotionAdjust`;
  Bounce scales existing overshoot only); times a motion is staged by go through `Motion.ms` (speed and slow motion).
  The settings show motion as **moments** (V2, `docs/PLAN_SETTINGS_V2.md`; `motion/Moments.kt`): 23 things a person
  notices (Charging, the status bar's charging moment, joined the plan's 22 on 2026-10-18) ("Opening apps", "Folders"), each holding its roles (every editable role in exactly one; a test checks it) and
  its glue (roles nobody sees alone, unlisted, following its feel). Each moment has its own Speed and Bounce
  (`motion.adjust.<moment>.speed/.bounce`, 1 = as the preset), applied on top of the preset's wherever a curve is read
  (`Design.curve`, `Design.previewCurve`, `Draft.motionCurve` through `MotionAdjust.speed/bounce`), and some a character
  slider moving their numbers together (`Character`: home's depth, the unlock zoom, the wiggle, Clear All's rhythm). A
  moment's page (Motion's list by part, and each part's page) has Speed, Bounce, its character and Every detail (its
  Advanced fold): its
  roles with today's editor (`settings/MotionPages`: spring or curve, a graph with handles, sliders, shapes), samples on
  the picture of home (`settings/MotionSamples`, one loop for the whole settings), the graph's arithmetic in
  `motion/CurveFit` (springs fitted to curves and back); kept as the preset's user edits
  (`files/design/motion-edits-<id>.json`). Values read in words first ("Smooth, bouncy · 0.42 s").
  **Transition styles** (V4, `docs/MOTION_STYLES.md`): how apps open and go home is a choreography a preset names
  (`motion.style.opening`/`.closing`, `motion/AppStyles`: zoom, container transform, slide, circle reveal (closing: a
  bubble), fade and scale, dolly zoom, genie), drawn from the same card springs (`GestureNav.applyStyle` sets the frame
  and `CardView`'s style parts: `contentK`/`iconK`/`boxK`, the app over `contentFrame` at `contentFullK`, the icon at
  `iconFrame`, a genie mesh), so grabs, reversals and launches mid-close work for every style. A style a card still
  shows fades out over a new flight's first half (`StyleDev`); a grabbed card's style gives way to a plain card on the
  cancel's curve (the App Switcher's deck draws plain cards: it waits), and a style with a frame of its own (circle,
  genie) is grabbed by the plain card it is drawn on (`StyleDev.grab`), and a flight reversed starts from it too (the
  reversal of a genie once started from the mesh's bounds: a card twice the size for a frame). A closing app reopened
  plays the chosen opening style from where its card is (`flightBlend`: the style at the card's place on the whole way
  from its icon, coming in over the flight's first half as what the card showed goes; a genie's mesh bends into the new
  one; slide and fade grow the card back as it is); it once always zoomed (Matheesha: launches during a close "would
  revert back to the default animation"). Slide and fade closes play also for an app with no icon on home. The genie is macOS's: a
  funnel narrowing to the icon from both sides, the app's near edge dropping in first (`AppStyles.genie`). A close that
  leaves the icon on home (slide, fade) shows home's picture with it; the dolly zoom on home's picture is a fraction that
  gives way as the App Switcher opens (`PreviewView.fadeDolly`), and is re-aimed with the depth when a flight goes to
  another icon than the one home was zooming toward (`PreviewView.aimDolly`: an app opened while another's card still
  lands, the close of a launch grabbed; it once kept the first app's icon: Matheesha, Cinematic, apps opened rapidly). The styles' drawing is warmed off screen while home is
  idle (`CardStyleWarmUp`). Presets bundle styles (iOS 27 zoom, Material
  container, Playful genie, Calm fade, Cinematic dolly: `tools/design/build_style_presets.py`). The settings show a style
  by playing it (`settings/AppStyleArt`: the picture of home, the presets' cards, the Style group of Opening apps and
  Going home), never by name alone. Theme seeds and motion seeds both live in the builder, which
  writes the two files. Test theme: `graphite`. `pixel` (`tools/design/build_pixel_theme.py`, from AOSP's numbers and
  captures of the Android 17 emulator's shade): Material 3 Expressive in the phone's palette by role, so far for the shade
  (step 1 of `docs/SHADE_PIXEL_EVALUATION.md`, the engine test before the shade becomes blocks and layout files). New and migrated drawing code reads tokens, never literals; every token says where its
  value came from (kit, measured, judged). Points to pixels: one unit everywhere, `Scale.unitPx` (policy `adaptive`: 402 pt
  across a phone, never larger than the dp at the phone's default density, times the Display size's zoom; text through
  `Design.text` follows the Font size up to 130 %, not the status bar nor home's labels; policy `custom`: the same base
  times the user's `sys.scale.size` and text times `sys.scale.text` (all but the status bar; home's own text through
  `HomeMetrics` and `Scale.homeText`); a change rebuilds everything). A view that keeps the unit in a field measures it
  again on every design change, not only in `onSizeChanged` (the window keeps its size when the unit changes: Control
  Center and Notification Center stayed small after Follow was turned off).
  The token editor is `design/DesignActivity` (opened from the settings app: System, Developer, once the developer tools are on; no drawer entry).
  Surfaces are drawn by **one renderer**, `design/MaterialPainter` (a material token layer by layer as the kit defines it:
  frost, lens, fills with blend modes, inner shadows, rims), over a backdrop blurred per frost by `design/FrostCache`.
  Notification Center is on it (step 2a, tokens `comp.nc.*` in `shade/NcTokens.kt`), and Control Center with its expanded
  modules and gallery (2b, `comp.cc.*` in `shade/CcTokens.kt`, drawn through `shade/CcSurfaces`), and the banners (2c,
  `comp.banner.*`), and home's dock, Search pill, widgets and edit buttons (2d part 1, `comp.home.*` in `home/HomeTokens.kt`, through
  `GlassView` with a material) and the rest of home's glass (2d part 2: menus, search fields, App Library, Spotlight, the widget
  gallery, over live content through `MaterialPainter.drawLive` or over the blurred backdrop through `drawer/BackdropGlass`);
  only the glass clock's numerals (a text shape) still use `GlassDrawable`. Blur sizes in tokens are the kit's (sigma = radius / 2); Android's
  `RenderEffect` blur takes its own radius: convert with `design/Blur`. Theme seeds live in `tools/design/build_ios27_theme.py`
  (`reseed.py KEY` re-applies a corrected seed). To compare with the kit over its own picture: push it to
  `/sdcard/Android/data/dev.launcher.app/files/wallpaper.png` (the app shows it instead of the wallpaper; delete to undo).
- **Glass/blur**: three layers — own snapshot blur (baseline, all phones) → standard cross-window blur where the system enables it
  → Samsung dim-behind blur upgrade.
- **Shade (iOS 27 profile)**: `statusbar/` (the bar: springs for every slot, notification icons) and `shade/`: one full-screen
  overlay window (`Shade`) holding the bar, Notification Center (`NotificationCenterView`: the sheet, the wallpaper and the
  arrangement of its blocks, `shade/nc/`: `LockClockBlock`, `NotificationListBlock`, `CornerButtonsBlock`; in landscape iOS 27's own
  layout, `comp.nc.land.*`: every layout handles its own landscape as its original does, Pixel's and One UI's specs in
  `docs/PLAN_LAYOUTS_THEMES.md` D), Control Center
  (`ControlCenterView`: how present and pulled it is, its closing fold and the arrangement of its blocks, `shade/cc/`:
  `TopButtonsBlock`, `StatusRowBlock`, `ControlGridBlock`; without a status row the bar stays as it is over it; `CcLayout`,
  `CcGallery`, `Controls`; apps' Quick Settings tiles as controls: `AppTiles`, run by SystemUI
  through `cmd statusbar add-tile/click-tile`) and banners (`BannerView`), all drawn by the material renderer. Notifications come from `Notifs` (the listener); what each control does from `ControlState` (own APIs, else
  shell commands). Design notes and measurements: `docs/PROGRESS.md` ("Phase 4"), `docs/IOS_DESIGN.md`.
  Control Center's live background is `LiveBlur`: an empty window of its own, added just before the shade's (so under it),
  that blurs what is behind (One UI: Samsung's view blur on its full-screen view; Android's cross-window blur elsewhere); `CcExpanded` is iOS's expanded
  modules; a notification's app opens out of its platter on a card inside the shade's window (`Shade.openFrom`). iOS 27
  consistency, item by item: `docs/IOS27_EVALUATION.md`.

## Proven mechanisms (reference implementations are in the probe repo)

Probe repo: https://github.com/MatheeshaSIlva/requirementchecks (public). Clone it next to this project for reference; do not
copy it wholesale — port the working pieces cleanly. File map:

| Mechanism | Probe file | Notes |
| --- | --- | --- |
| Shizuku user service, hidden-API reflection | `ProbeService.kt`, `IProbeService.aidl` | explicit AIDL transaction codes; `runShell`, `runDetached`, `downloadFile` |
| Gesture strip + card that follows the finger, grab mid-animation, sideways quick switch | `GestureStrip.kt` | card window = exact full-display size, gravity TOP\|START, cutout ALWAYS, `setFitInsetsTypes(0)` |
| Launch animation (system anims off + own expanding card) | `V6Lab.kt` (`launchLab`, `runLaunch`) | animation scale 0 for transition + window only; animator scale untouched |
| Close animation (snapshot card shrinks to icon while home starts) | `V7Lab.kt` (`closeLab`, `runClose`) | cached snapshots 1–3 ms |
| Own status bar, stock bar hidden | `V6Lab.kt` (`statusBarLab`), `ProbeService.statusBarCmd` | `cmd statusbar send-disable-flag clock system-icons notification-icons` |
| Watchdog with heartbeat | `V7Lab.kt` (`armWatchdog`) | app touches `/data/local/tmp/wd.hb` every second; loop restores after 4 s stale |
| Blur: snapshot blur | `BlurLab.kt` | `RenderEffect` on our own content |
| Blur: Samsung dim-behind | `SemBlurLab.kt` (`animated`, `strength(2)`) | see pitfalls |
| In-app updater | `Updater.kt`, `ProbeService.downloadFile`, `.github/workflows/build.yml` | rolling GitHub release `latest` |
| Boot / Shizuku-after-reboot logging | `BootReceiver.kt`, `V6Lab.kt` | thedjchi fork recommended |

## Pitfalls we already paid for (do not repeat)

- **Overlay windows without `FLAG_HARDWARE_ACCELERATED`** silently draw no blur/RenderEffect.
- **`pkill -f X` / `pgrep -f X` match their own shell** (the pattern is in the command line) and kill it, skipping the rest of the
  command. Use the bracket form: `pkill -f 'wd[.]sh'`.
- **Name-based process checks (`pidof`, `ps | grep`) are unreliable** for watchdogs. Use a heartbeat file.
- **Stale detached "restore" timers** (`sleep N; cmd statusbar send-disable-flag none`) later clear newer flags. Kill them before setting flags.
- **The shell cannot read the app's own external folder**; download the update APK *inside the shell process* to `/data/local/tmp`.
- **Never copy a hardware snapshot bitmap to a software bitmap inside the shell process** (crashed the service). And a hardware
  Bitmap sent over Binder is *read back into a 10 MB software copy* by Binder itself: send the `HardwareBuffer`
  (`taskSnapshotBuffer`) and wrap it in the app. The software copies cost a 5+ ms GPU upload at first draw and native-memory
  GCs (5 ms pauses of the whole process) mid-gesture.
- **Android 17 (One UI 9) moved task snapshots out of IActivityTaskManager** into `ITaskSnapshotManager`
  (`IActivityTaskManager.getTaskSnapshotManager()`): `getTaskSnapshot(taskId, latestCaptureTime, resolution)` (-1: the caller
  holds none, so one always comes back; resolution 1 high, 2 low, 3 any), `takeTaskSnapshot(taskId, updateCache,
  lowResolution, includeDecors)` (visible tasks only). And `TaskSnapshot.getHardwareBuffer()` is deprecated: it logs
  "getHardwareBuffer is deprecated!" and returns null; the buffer is still its `mSnapshot` field (`ShellService.bufferOf`).
  Without them every App Switcher card, close card and warm launch showed the app's launch screen (icon on a plain colour).
  When a reflected call stops working after an update, read the phone's own framework: `adb pull
  /system/framework/framework.jar` (and `services.jar` for the server side), `dexdump` from the build tools.
- **Our own frame log ("frames N ... dropped ~K") times the drawing thread, not the screen.** A frame the GPU finishes late is
  shown a refresh later and the log does not see it. What the screen showed: `tools/framestats.py` (present times).
- **Windows created from the Shizuku process fail** ("Unknown pid=… uid=2000").
- **Freeform / split-screen launches from the shell** become stuck overlays after ~5 s. Out of scope (experimental only).
- **Stock recents animation API does not exist on Android 16**; we draw our own.
- **Standard cross-window blur is off on this Samsung** (`ro.surface_flinger.supports_background_blur` empty, not changeable without root).
  Samsung's plain `View.semSetBlurRadius` gives fog. What worked up to One UI 8: window with `FLAG_DIM_BEHIND` + `semAddExtensionFlags(SEM_EXTENSION_FLAG_CHANGE_DIM_EFFECT_TO_BLUR)`;
  strength follows `dimAmount`; whole-screen only (never a rectangle). One UI 9 gives our overlay window no dim layer at all
  (the flags are set, SurfaceFlinger shows none), so Control Center's live blur is Samsung's view blur on a full-screen view
  now (`LiveBlur`, mode SAMSUNG_VIEW: its radius set again each frame, no relayout). Reflection on `sem*` worked without changing the hidden-API policy.
  **For one view's rectangle: `View.semSetBlurInfo(SemBlurInfo.Builder(BLUR_MODE_WINDOW)...)` is real live blur** (the "fog" was
  `semSetBlurRadius` only): `design/SamsungBlur`. It blurs what is behind the window within the view's bounds, follows the view's
  translation and scale, works inside a GPU layer, takes `setBackgroundCornerRadius`; it ignores the view's alpha (fade it by
  re-applying a smaller radius each frame: works). Radius = 4.7 x Gaussian sigma (measured). `setColorCurve(sat, curve, minX,
  maxX, minY, maxY)`: ranges 0..255 (a linear map), sat 0 turns the whole curve off, chroma grows ~1 + 1.9 x sat; `curve` did
  nothing visible. Banners use it (`comp.banner.system-blur` 1, on in iOS 27 since 2026-10-12: Matheesha wants them live
  as Control Center; `BannerView` plates with the material's colour curve `systemLook`, the glass drawing only its own
  layers over it: `MaterialPainter.drawOverSystemBlur`). The lens cannot bend what the system draws: a band along the edge
  drawn whole over our picture of what is behind, fading into the live blur, was tried (b7e9ee5) and taken out (Matheesha:
  it broke the whole live look). Fitted on the S24 against our glass over the same wallpaper
  (`SemBlurProbe` glass mode, `--ef sat`/`--ef span`): chroma 1.67 per saturation, the brightness range widened 1.11.
  Check it in `dumpsys SurfaceFlinger` ("Blur Regions": our window's region, radius, curve), never by screenshot. (It was
  off once because banners vanished during app opens and closes: that was the card window over the shade's.) Probe:
  `TEST_SHADE --es do semblur` (`shade/SemBlurProbe`).
- **A media notification can outlive its session** (Chrome keeps one for a tab whose session is gone: inactive, state
  NONE), and Android's own shade hides paused media after 10 minutes. Ours: `Notifs.mediaLive` / `Media.active` (stopped:
  at once, paused: after `Media.IDLE_MS`); read the session from the notification's own token, not the active-session list
  (an inactive session is not in it).
- **Stock notification launch animation** (SystemUI) still plays even with animation scales at 0; solved by replacing the shade so taps go through us (re-test).
- **Status bar race**: a restore-on-start can clear freshly set flags; set flags only after restore has finished.
- **Never change an overlay window's layout params at the start of a gesture** (alpha, flags, size): each change is a
  10-50 ms window manager re-layout on that window's thread. The card window stays at alpha 1. An activity window's bar
  flags (`setSystemBarsAppearance`) are a relayout too, at its next frame: the settings change theirs at a slide's end and
  tell our bar mid-slide (`GestureNav.ownScreenTone`, which holds off reading our window's stale flags meanwhile).
- **A first frame held back (`OnPreDrawListener` returning false) holds the window's opening transition**: count any
  "it should be on screen by now" timer from that frame's commit (`registerFrameCommitCallback`), never from before it.
  Counted from home's picture arriving, the settings slid in before their window showed: their first composition after a
  start took 0.6-0.8 s on the emulator. A key pressed meanwhile waits in InputDispatcher until the window has the focus.
- **Recording home into a Picture costs ~20 ms of main thread**: never inside an animation frame (settle callbacks run inside
  the last frame); reuse the last picture when home has not drawn since. **No GPU layer on any view inside home** (only on
  the recorded root): a layered child is drawn in software inside the recording and the glass shader crashes the app.
- **Glass over live content (`MaterialPainter.drawLive`: menus, search fields, the widget gallery's sheet) must never be drawn
  inside a clip or a smaller layer** (clipToOutline, a parent's clip, view alpha < 1): the effect's input is cut there and the
  edges break. Use the paint form (`MaterialPainter.draw` over a backdrop image) for anything clipped. With a content key
  (what is behind kept blurred) its node covers the whole limit only for a surface that grows (the gallery's sheet); one
  that keeps its size (the App Switcher's Clear All) keeps a node around itself, and an unchanged surface is drawn again
  without a new effect (a new one re-runs the shader over the node: Clear All ran it over the whole screen every frame).
- **One UI 9 renders with Vulkan, and live glass broke when only part of its window redrew**: the App Library's search
  field showed a dark rectangle the size of its text box once the cursor blinked (the field's glass came out cut there
  until something redrew it all). While live glass shows on home (App Library, Spotlight, a menu, the widget gallery),
  `HomeScreen.onDescendantInvalidated` marks the whole window changed. A window's frames on the S24 are full redraws
  anyway (Perfetto "Drawing 0 0 1080 2340"); what a partial change cost was the glass, not the time.
- **A blur looks about as strong as the logarithm of its radius**: Control Center's live blur, its radius falling in step
  with the panel, stayed a heavy blur after the controls had gone and then cleared at once (Matheesha's video). `LiveBlur`
  sets the radius on that scale, (1 + max)^k - 1. And One UI 9 has no `View.semSetBlurEnabled`: a null `SemBlurInfo`
  clears a view's blur (`SamsungBlur.clear`).
- **The picture of home behind cards is blurrier than home's own blur at the same radius** (it blurs a quarter-size copy
  and scales it up: about twice the look). Handing over from one to the other mid-flight pulsed the whole screen sharp
  for a frame (opening an app during a close). Never swap them mid-motion: keep the picture until something replaces
  it, fade between them, and home's own depth blur follows the picture's look (`home/DepthBlur`).
- **Text-shaped glass needs a distance field, not a blurred mask**: a blur wide enough for a lens turns a whole thin stroke
  into "edge". Judge glass offline only with the app's own pipeline at the device's real size (`docs/design/clock_proto.py`).
- **Glass and transforms**: a glass surface's uniforms are recorded into its display list; a parent moving it does not redraw
  it. GlassView re-checks its real place before every frame. Never count a transform of a view drawn through a GPU layer
  (home's depth zoom of `fg`, tagged `glass_root`): the layer scales the glass again (e9401f1 showed the wrong wallpaper in the
  dock after every close). Contact sheets at 1/3 size hide thin glass rims: judge glass at full resolution.
- **Text shadows with a translucent colour do not fade with the paint's alpha** (Android draws them at the colour's own
  alpha): a label faded through its paint leaves its shadow behind. Use `theme/FadingShadow.apply(paint)` before each draw
  (LabelPainter does). View alpha (a layer) is fine.
- **Touchable region of an overlay** (`rootSurfaceControl.setTouchableRegion`, API 34): set it from the view's attach
  callback (before that it is dropped), and never as an empty Region (not sent: the whole window stays touchable); use an
  off-screen pixel for "nowhere".
- **Our remote transition must not finish before its start transaction is committed.** SystemUI applies the finish
  transaction from its own process; transactions from two processes are not ordered, so an early finish let home end up
  inside a removed transition container (black home, "no focused window" ANR, survives reinstalls). Finish from
  `addTransactionCommittedListener` (`InstantTransitions`).
- **adbd restarts kill everything started through Shizuku** (SIGKILL of adbd's cgroup; escaping it is denied). On the S24 this happened on every
  lock/unlock because Default USB configuration had data functions; fixed by "debugging only"/"No data transfer". Hence: status bar flags are set
  through the binder API with a token owned by our service (system drops them when it dies), never with `cmd` (those persist until reboot).
- **Everything that changes system state must auto-restore and be recoverable without Shizuku** (notification action + safe-settings screen). A reboot always clears these in-memory flags.
  The safe-settings screen (plain views) opens from the Restore notification only; the same is on the settings app's System
  page, the developer tools on its Developer page once the build has been tapped seven times (`DevTools`; the separate dev
  panel and its home entry are gone).
- **The window manager takes a pull from the top edge away from us.** After 24 dp (within 500 ms) of a pull that starts in the
  top band, `DisplayPolicy.requestTransientBars` transfers the touch to SystemUI's status bar (`transferTouch`) unless the
  focused window shows its bars transiently: our shade window gets ACTION_CANCEL mid-pull. It cannot be blocked from an app
  (spy windows, providing insets, FLAG_SLIPPERY all need signature permissions). Home sets
  `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` (no transfer there); over apps the shade follows the finger from the touchscreen's
  raw events, read by the shell service (`TouchStream`, `/dev/input`) only while a pull lasts.
- **`adb shell input` never reaches `/dev/input`** (injected after the kernel): the raw stream sees nothing, so scripted pulls
  over apps always end at the handover. On the emulator `tools/kswipe.sh` sends real touchscreen events through the console.
  On a loaded emulator an injected swipe can also end early (its lift where the app last saw it, a quarter of the way): a
  pulled panel then closes by itself. Scripted pulls use `tools/kswipe.sh`.
- **Blocking the stock shade also blocks heads-up notifications** (SystemUI: "No heads up: disabled panel"); sound and
  vibration still play. Not One UI's "Brief" pop-up style (system setting `edge_lighting=1`): those come from the system
  server's EdgeLightingManager (SystemUI logs "no Heads up : edgelighting enabled app") and show next to our banners;
  pop-up style "Detailed" hands them back to SystemUI's heads-up, which the flags stop (`PopupStyle` offers the switch
  when our shade takes over; Restore system switches back). Our banners (`shade/BannerView.kt`) replace them; never ship DISABLE_EXPAND without them. With the
  heads-up suppressed, SystemUI does not open a call's or an alarm's full screen either while the phone is unlocked (it expects
  the heads-up to show it): our ringing banners (Answer/Decline from the call's own intents, an alarm's actions) are the only
  way such a notification shows; they stay until it stops ringing, also over an open panel.
- **One UI's status bar shows parts of itself that no disable flag hides** (read from its SystemUI, One UI 9:
  HomeStatusBarViewModelImpl's visibility rules; the clock, system icons and carrier name do obey the flags). While the
  screen is cast or shared (its CastController counts any MediaProjection and Smart View; Link to Windows excepted) its
  notification icons and its call/cast chips show whatever the flags say: Matheesha's "the stock status bar comes back under
  our own one" while sharing his screen. The icons stay away when System `simple_status_bar` is 2 (notification style
  "none"); its live-notification chips (timers, navigation) when Secure `quickstar_indicator_hide_ongoing_chip` is 1;
  `StockBarSettings` sets both while our bar is on and gives the user's values back (bar off, Restore system, the watchdog's
  plan). The cast chip is covered by our sharing capsule (the time in red, `StockBarCover`), on whenever One UI's cast
  controller says it casts: its log (`SamsungOngoingCastController`, every line ending "isCasting:T" or "isCasting:f"),
  followed by the shell's `watchLog` (a live logcat stream). Link to Windows' phone screen shows the chip too (Samsung's
  service `com.samsung.android.mdx` sends SystemUI an `LTW_CAST_CHIP` broadcast) though it is no capture: it mirrors through
  a private display of that service, which only `dumpsys display` shows (also how the state is known at our start when the
  log line is gone). On Samsung the capsule is sized over the chip (S24: x 70-158, y 34-79 px; ours 28 dp tall from 21 dp,
  solid within ~30 ms) and stays 400 ms after the chip goes (its own exit animation). And One UI's charging animation
  (`ChargingVI`, a chip 205 x 58 px at x 815, y 26 on the S24 by its own log, starting 65-100 ms after the plug-in) plays
  whatever the flags say (visible = playing || system info allowed; no setting turns it off: its style is chosen from the
  device's state, "INDICATOR_CHARGING" off the lock screen, and it is skipped only while the stock shade is open): our
  charging moment covers it, ended when its log says "Resetting animation state" (the shell's `awaitLog`, read from half
  a second back: `StockBarCover` lets lines from before the plug-in go). The chip is anchored at the hidden stock battery's
  cached bounds (offsetX = battery right - 205: 815-1020 on 2026-10-18, 823-1029 on 2026-10-19) and comes first as a small
  faint pill there (x ~945-1028, y 37-73), stretching left over its width ~125 ms later; One UI's log has started it 29-152
  ms after ours (S24), so its first frame can come ~30 ms after our first: the capsule's right end leads the rest, out by
  25 ms (`CHARGE_RIGHT_MS`). Unplugged mid-moment, the capsule stays while One UI's animation may still play
  (`StockBarCover.oneUiActive`), and a plug-in again waits for One UI's next animation (only the newest wait ends it). See
  One UI's chip on its own with `TEST_STATUS --es do cover --ez on false` (and `true` after). The moment (its
  own moment in the settings, "Charging"): an opaque capsule grows out from behind the battery on a led outline
  (`motion.statusbar.charge-open`, 1 - (1 - k)^4: over the chip within ~55 ms; on the spring itself the chip's end showed
  for five frames; never less than 85 % at 70 ms whatever the settings' speed), the battery and its bolt move to its right
  end, the battery's number slides out through its side and grows into the percentage (a hole in the battery inside it,
  written on the capsule outside), the icons beside it dissolve (shrink, blur, fade), a light sweeps through the charge
  while it holds, and it folds back on a smoothstep (on the lead its last 5 % lingered as a dark ring for ~150 ms), the
  colours of what is on it following the capsule under them. Checked frame by frame at exact 120 Hz times with
  `TEST_STATUS --es do charge-frames` (the real drawing into PNGs: an emulator recording shows ~5 frames a second, and our
  springs pause through slow frames). Never build a test that starts a screen capture behind the user's consent: the
  sharing path is checked with a share the user starts, the capsule's look with `TEST_STATUS`.
- **Never screenshot or record Notification Center or banners on Matheesha's phone** (they show his messages). Measure them
  with framestats only (`tools/scenario_shade.sh` never captures them; its test banner is our own text).
- **"No animation" options (`makeCustomAnimation(0, 0)`, or any custom animation) are ignored for a cross-app start**:
  the system's transitions play their default open animation. Starts we make ourselves go through our own transition
  (`NoAnimStarts`, the shell); a notification's tap (it must come from our process) is sent with the system's transition
  animations off (`NavLink.quietStarts`).
- **`setTouchableRegion(null)` does not give a window its full touchable area back**: set the full region explicitly.
- **A PendingIntent sent by the Shizuku shell starts its activity behind everything** (Android 15+: the sender must itself be
  allowed to start activities; the shell is not a visible app). Notification taps are sent from our process (its windows
  are on screen), never through the shell; check `adb logcat | grep "would be moved to the foreground"`.
- **A focusable overlay becomes the keyboard's target** and the system lifts an open keyboard above it: the shade's
  window takes `FLAG_ALT_FOCUSABLE_IM` together with focus (never without it: on a non-focusable window it inverts).
- **One UI's fingerprint icon** (`FP Iconview`, a system window above all of ours) hides only when the lock screen's window
  loses the focus. Over the lock screen `shade/FocusHolder` takes the focus, but never in a pull's first 500 ms: with the lock
  screen focused the window manager does not hand a pull from the top to the stock status bar, with our window focused it does.
- **What is behind the banners and the status bar changes when a card starts, not when the window reports**: a launch from
  home keeps home resumed until the app's window comes (2 s on the emulator), so asked then, a banner showed the wallpaper
  over an app opening and the bar's white time sat on a white launch screen. `GestureNav.cardBrings`/`cardGoesHome` tell
  them at the card's start.
- **Whether home is in front: ask `GestureNav.homeVisible`** (HomeActivity's own onResume/onPause), not the accessibility
  window events: Home coming back with the Home key can report a plain FrameLayout, and the last event then still named
  the app (banners drew over an app's flat colour on home).
- **Changing the touchable region of a window that holds a touch is fine, changing its flags is not**: making the shade
  focusable (for back) was a relayout (16.7 ms of its thread on the S24); the focus window (`shade/FocusHolder`) takes the
  focus for Back instead, 300 ms after a panel came to rest (during the pull already over the lock screen).
- **Android's back gesture watches every window's side edges** (it pilfers the touch: our window gets ACTION_CANCEL):
  a pull from a top corner that starts sideways, a swipe from the right edge over a notification. Keep it off with
  `setSystemGestureExclusionRects` (only where the touch lands matters). Home in edit mode too: a 4-wide widget's resize
  handle is in the S24's right back band (the drag became Back and left edit mode); `EditMode.updateExclusion`. A window gets 200 dp per edge, counted from the
  bottom up, unless it requests the navigation bar hidden with BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE: the shade's window
  does, and must therefore never take the focus (the focused window controls the bars; it would hide the navigation bar).
- **iOS reference capture** (`ios-reference/`, `tools/ios_ref/`, results in `docs/IOS27_KIT.md` and `docs/IOS27_MOTION.md`):
  the CI Simulator has no GPU, so the recorder misses frames while SpringBoard animates heavy layers: use frame times,
  never counts; repeat a motion and fit one spring over the runs; release from a standstill (hold before lifting), since
  XCUITest lifts while still moving. Synthesized touches start ~1 s late and `XCUIApplication.launch()` waits ~10 s for
  the app to idle (post notifications 20 s ahead, wait for `NotificationShortLookView`). The Simulator's Slow Animations
  switch does nothing on iOS 27. Each run is a fresh simulator: Control Center starts empty (`test00_setupControls`).
  Download the run's small artifact only (the originals are ~1 GB and this connection gets ~75 KB/s).
- **A glass recorded where something rests shows the wrong backdrop while it moves** (its sampling position is baked
  into the display list): Notification Center's platters, cached as layers and redrawn three a frame, flickered while the
  list scrolled. Draw a moving surface's glass where it is, every frame; cache only what it shows on top.
- **Hidden settings throw for an app since Android 12** (`Settings.Secure.getInt("reduce_bright_colors_activated")`:
  SecurityException, not a default): one unguarded read crashed the app on every ringer broadcast. Read them through the
  shell (`ControlState.readHidden`), guard every other read.
- **The shade belongs to the accessibility service that made it**: after the service restarted (off and on, rebound by
  the system) every window the old shade adds is refused (BadTokenException), so GestureNav makes a new shade.
- **iOS 27 Notification Center opens collapsed** (one stack at the bottom, "+N from App"); a tap fans it out. A fling
  down on it hides the list into a count ("● 12 Notifications") and that state lasts across tests in the same simulator:
  find platters on screen before pressing them. The Control Center gallery is another process: SpringBoard's tree has no
  labels for it (read the screenshots).
- **Our overlay windows are one type (accessibility overlay) and stack in the order they were added.** The card window must
  be under the shade's (status bar, banners): removing it on the lock screen and adding it again on unlock put it over
  the shade, and the status bar and every banner vanished during each app open and close (the banners' "missing during
  animations" was this, not One UI's blur). On the lock screen only the strip goes; a card window made while the shade
  is up has the shade added again after it (`GestureNav.update`).
- **A local `adb install` over our app starts the phone's other home app** (Lawnchair on the S24) while ours is replaced,
  locked or not: after every local install run `am start -a android.intent.action.MAIN -c android.intent.category.HOME -p
  dev.launcher.app` and check the focus. And gate every S24 screenshot on the focused window right before it: the phone can
  lock between two steps, and a screenshot of the lock screen can show notifications (delete such a shot unviewed).
- **Git Bash path conversion**: `MSYS_NO_PATHCONV=1` is needed for adb paths like `/sdcard/...`, but exported for a whole
  command it also stops `tools/device.sh install` from converting the APK's path: the install fails quietly and the old
  build keeps running. Set it per adb call, never for the scripts. (An install while the app starts can also crash it in
  Android's own `handleBindApplication`: force-stop and start again before judging a build.)
- **AGSL**: `out` is a reserved word (a variable named so fails to compile); uniform arrays (`uniform half4 x[8]`) are
  set with one `setFloatUniform(name, FloatArray)` of the whole array.
- **Keystore**: debug builds are signed with a committed keystore so CI builds install over each other. Keep that pattern (new key file for this app).
- **Never `am force-stop dev.launcher.app` on the phone**: it disables our accessibility service (gesture nav, the shade)
  and once left stock gestures blocked with no strip. Install local builds with `adb install -r -d` alone, then check the
  log for "gesture strip on". Token experiments without a reinstall: put `design/user.json` in the inbox (below) and send
  `am broadcast -a dev.launcher.app.DESIGN_RELOAD -p dev.launcher.app`.
- **The renderer's GPU cache has a budget** (S24: 121 MB, `dumpsys gfxinfo` "Max resource usage"). Each RenderEffect or
  layer is a screen-sized image and its filtered output another; past the budget every frame frees and allocates images
  (`allocateImageMemory` slices of 0.5-2 ms in a trace, 30-40 a frame in the widget gallery). Blur kept content at reduced
  size, never give a layer a new size every frame, and check the budget with the surface open.
- **A `Picture` of a view is drawn in software**: outline clips, RenderEffects and glass that only draws on the GPU are
  lost (a widget's copy above its menu had square corners, the glass clock no numerals). Copies that must look like the
  item are render nodes (`ContextMenuView.record`). And **Android empties the app's GPU pipeline cache with every update**:
  the first draw of a surface compiles its pipelines inside its first frame (the widget gallery: 141 ms; the first bubble
  close: 76 ms, 9 missed refreshes); warm such surfaces off screen while idle (`design/GpuWarmUp`, `CardStyleWarmUp`).
- **A container that fades is drawn through a layer the size of the screen** (a ViewGroup overlaps by default): the cards'
  container fading into the app at the end of every launch allocated one in that frame (7-9 ms of the S24's GPU; a circle
  reveal's tipped over into a missed refresh in 5 of 7 runs). When only one card draws one picture, its fade applies to its
  draws (`GestureNav.drawsAlone`, `forceHasOverlappingRendering`): 0 missed in 4 runs, the frame 5-7 ms.
- **An app's existing task brought back may send no window event** (Settings sends none, from the dock or a panel): never
  wait on the accessibility event alone; ask the system for the task in front (`topTaskPackage`). And while a panel holds
  our focus window, the launched app cannot take the focus at all: a launch from the shade lets it go.
- **Predictive back did nothing in the settings app on the Android 17 emulator**: the system logged "Trigger back without
  dispatching to animator" and our window then "ProgressAnimator was not in progress, skip onBackInvoked()" (neither
  progress nor the back itself arrived). The settings app uses a plain back handler (its own swipe back was removed: a
  scroll that started sideways took the page back).
- **A drag that moves its own node reads the finger in that node's coordinates**: the font sheet's top, dragged, chased
  the finger at half its speed and the release speed read 0. Read the finger on the window (`localToWindow` of the node's
  coordinates) for anything that moves with the finger.
- **Seeking a Compose transition whose specs are springs runs ahead of the finger** (a seek maps the fraction to time, and
  a spring covers most of its distance early). Seek on linear specs while the finger drives, then spring the fraction
  yourself from the finger's speed.
- **Compose re-reads its whole accessibility tree every 100 ms while layers move, whenever any accessibility service is
  on, and ours always is** (gesture nav; the S24 also has others): every frame where a `graphicsLayer` changes its
  transform (translation, scale) counts as a layout change. The settings app lost 7-25 ms of its main thread every
  ~110 ms of every transition (`checkForSemanticsChanges` in a Perfetto trace); with no tree, 0 missed refreshes.
  Scrolling counts too (a long page lost 12-17 ms every ~117 ms of a scroll). The settings leave the tree while a page
  moves (`clearAndSetSemantics` while the spring runs) or scrolls (`IosScrollState.moving`), and press effects scale in
  drawing, not as a layer. Any Compose surface with motion needs the same.
- **Compose: nothing that moves may be read in composition.** Which page is open, the element chosen, a spring's value:
  read them in drawing, layers or effects (`derivedStateOf` + `snapshotFlow`), never in a composable's body; a value
  handed down (a `CompositionLocal`, a lambda) is made once (`remember`), or everything that reads it composes again. And
  no file reading behind a call made in composition (`Design.themes()` parsed every theme file: cached now). The settings
  lost 11-27 ms in the first frame of a page opening to each of these (S24, Perfetto: `Compose:recompose`).
- **Compose compares font families by the wrapper's identity**: `FontFamily(typeface)` made again for the same typeface
  is another font to it, so a text style built in composition with a new one made every text that composed again measure
  again (the settings: 25 ms of layout in one frame). Styles and families are made once (`Look.text`, `familyOf`).
- **A `key()` inside an `if` in a loop is matched by position**, not by key: when the list changed order, rows were not
  recognised and were built anew (eleven in one frame). Filter first, key at the top of the loop body.
- **A `SnapshotStateMap` invalidates every reader at every write**: the draft's motion edits change at each step of a
  slider, and every row and group count on Motion's pages that read one key of it worked itself out again (~140 a step,
  8 ms of the S24's main thread). One state per key for one-key readers (`Draft.motionCells`), derived states for counts,
  and a value that changes in a composable of its own. Likewise: an effect keyed on a state composes its whole composable
  again when it changes (`LaunchedEffect(motionOpen)` in the picture of home: 2 missed refreshes at each push and pop),
  follow it with `snapshotFlow` from `LaunchedEffect(Unit)`; a list made in composition (`listOf(...)` for chips) is a
  new one each time and the callee composes again; an `onPlaced` that reads coordinates places its node again in every
  frame anything above it moves (53 chips in a page's slide: 4-13 ms a frame): measure sizes instead.
- **State read only inside a `SideEffect` (or an effect's lambda) is not followed by composition**: a row that compared its
  words with the last ones there never composed again when they changed (it kept the old value). Read the state in the
  composable's body, act on it in the effect.
- **A fading layer is drawn through a buffer of its size** (Compose's default compositing with alpha below 1): two pages
  fading in a push allocated and filled two page-sized buffers in its first frame (13 ms of the render thread on the S24).
  `CompositingStrategy.ModulateAlpha` fades draw by draw (fine where nothing on the page overlaps much: no glass, no
  pictures under text), but its translucent draws use pipelines of their own: warm them off screen (`MotionWarmUp`), or
  the first use after an update compiles them inside a frame (142 ms).
- **Many small composables per list row cost ~3 ms a row to build on the debug build** (Motion's rows: a dozen each).
  Rows that only show, and are many, are one node that draws its parts (Android's text paint, `LinePaints`), with their
  animations made when first needed (`RoleRow`).
- **Build what a tap opens at the tap, and start its motion two frames later** (the row stays lit meanwhile): built in
  a slide's first frame, the slide began late with a jump; built while a finger rested on the row, a scroll starting
  there stuttered. And a new kind of drawing compiles its GPU pipeline inside its first frame after every update (the
  curve graph's stroke: 99 ms of the render thread): draw one off screen while home is idle (`settings/MotionWarmUp`).
- **Loops nobody drives count a long frame as a short one** (Motion's demo, samples, the cards' dots: at most 1/30 s a
  frame): a stall pauses them; counted from the clock they jumped ahead after it. A finger-driven or started motion keeps
  real time.
- **The build is not debuggable (since 2026-10-14, Matheesha's call)**: a debuggable app's code runs interpreted until
  hot, and Compose ran ~2x slower for it on the S24 (a page built in 91 ms against 42; the emulator 5x). `assembleDebug`
  (the build type CI ships, signed with the committed key) has `isDebuggable = false`; the manifest's `<profileable
  android:shell="true">` keeps simpleperf and heap dumps working, Perfetto shows the app's sections as before. With it
  comes the libraries' baseline profile as dex metadata (`baselineProfiles/0/app-debug.dm`): installed with the APK
  (`tools/device.sh install` uses `adb install-multiple`; UPDATE a session with both), Android compiles the profiled code
  at install (`pm art dump dev.launcher.app`: `speed-profile [reason=install-dm]`), so the first start after an update
  is not interpreted either. **There is no run-as**: files go in and out through the app's external folder,
  `/sdcard/Android/data/dev.launcher.app/files/` (adb reads and writes it): push to `dev-in/<path>` and send
  `DESIGN_RELOAD --es files import` (copied over `files/<path>`, the inbox emptied, design reloaded); `--es files export`
  copies `design/`, `themes/`, `motion/` and `setup.json` to `dev-out/` (ends with `dev-out/.done`) for `adb pull`;
  `--es files remove --es path P` deletes `files/P` (`design/DevFiles.kt`). Backups of the phone's design files are an
  export and a pull (byte-exact).
- **"Presented" is not drawn**: a `HardwareRenderer` drawing into an `ImageReader` returns when the buffer is queued, and
  the GPU may still be drawing into it. Wrapped at once, the settings' picture of home came out half drawn after a cold
  start (the rest transparent) and was kept so. `ViewPicture` waits for the image's fence (API 33+).
- **One UI drops the display to 20-24 Hz when nothing is touched, and a key event does not wake it**: an animation started
  by a scripted Back key waited 35-60 ms for buffers in its first frames (SurfaceFlinger `NextFrameInterval 24_Hz`). Start
  benched animations with a tap, or read a key-started one knowing this.
- **Our overlay windows are never told whether the status bar shows** (they lie above it); an application overlay (under
  it) is, the moment it changes: `statusbar/BarWatch`, one invisible pixel (`docs/STATUS_BAR.md`). Android then posts
  "Launcher is displaying over other apps" (also with a foreground service running): `Notifs` leaves it out. And Android
  16+ writes `dumpsys window` insets as `InsetsSource ... type=statusBars ... visible=false` (not `mType=... mVisible=`):
  a parser of the old form never saw an app hide the bar. A swipe from the top while an app hides the bar is seen in the
  touchscreen's raw events by the shell (`TouchStream.watchEdge`); the kernel reports an axis only when it changes (a straight
  swipe down never says x). Home is locked to portrait (it rotated into a broken layout with auto-rotate on).
- **Android hides every overlay window of ours while a fixed rotation is on** (AsyncRotationController: any window type
  past the application types, ours all are): from the moment an upright-only home is started over a turned display until
  the display has turned and each window has drawn again. Anything that must stay on screen then is an activity's window
  (`TurnCover`, under the card during a close from a landscape app; home starts once the shell saw its transition
  committed, `transitionShownAt`: its first frame drawn was up to 300 ms early). Its window draws the picture as its
  background (a view inside the bars' insets for a few frames left black bands), with transparent bar colours.
- **A rotation waits for every visible window of ours to draw in the new orientation**, and a window whose view is GONE
  never draws: LiveBlur's hidden window held each rotation for its 2 s timeout (now a visible 1 x 1 window). Seamless
  rotation (`ROTATION_ANIMATION_SEAMLESS`) left all our overlays waiting to draw; home uses the jump cut.
- **An overlay strip cannot hand a drag to the app under it.** Android's own gestures spy on every touch and take only
  their swipes (MONITOR_INPUT, which the shell lacks); ours is a window and takes every touch in its band. Injecting the
  touch into the app works only once the finger has lifted (taps: `StripForward`, aimed at the app's uid, after the strip
  lets touches through): while the finger is down Android refuses a DOWN from that device (it is touching the strip),
  from any other device too, and an injected CANCEL of the real touch ("Inconsistent event"). A drag inside the band
  (TikTok's seek bar) stays ours. The strip keeps Android's gesture height (32 dp): at 20 dp swipes home were missed half
  the time (Matheesha).
- **An app that goes into picture-in-picture mid-close takes the screen with it** (YouTube playing does, the moment
  anything covers it). The cover over it must be opaque from its theme (`TurnCover`): translucent (even switched to opaque
  in onCreate), home behind it became visible sideways, got Android's fixed rotation early (our overlays hidden mid-flight)
  and was relaunched in landscape (black, then the cold-start arrival). And home starts only once the PiP entry has played
  out (its live frame still, `whenPipSettled`): started earlier, Android hid our windows and its own PiP animation showed
  the video full size, turning and shrinking. A close of an app that will enter PiP (the shell reads its task's PiP
  parameters: auto-enter, source rect hint) flies the card into the mini player, cropping in on the video, and keeps it
  covered until it has settled. The mini player's jump to its upright place at the display's turn is Android's and cannot
  be animated by us: our windows are hidden from home's start until the turn (above), One UI's crossfade is the spin, a
  seamless rotation moved it the same way.
- **Where a mini player really is: SurfaceFlinger's window information** (`WindowWatch` in the shell, through
  `android.window.WindowInfosListenerForTest`; its task's bounds change only once it has settled, the finger's raw stream
  guessed). The S24 reports every refresh, 0-4 ms to us; the Android 17 emulator every 30-150 ms. Our hole shows ~3.3
  refreshes after a report's state did, so it is drawn ahead at the window's speed (taken over ~4 refreshes of reports:
  SystemUI moves a dragged PiP unevenly, and its drag starts with a jump of the touch slop that must not count as speed),
  a little inside the window while it moves (a hole outside showed a strip of home), from the touch-down on
  (FLAG_WATCH_OUTSIDE_TOUCH on the switcher's input window). SurfaceFlinger waits for every listener's answer before it
  sends the next report, input's included: the shell's handler stays light. A busy nav thread queued reports (the
  emulator: 57 ms late on average): only the newest waiting one is taken. Screen recording on the S24 halves the report
  rate and adds missed refreshes: judge alignment from recordings, timing from unrecorded frame stats.
- **Android refuses an app's own entry into picture-in-picture while another app's mini player is up** (auto-enter and
  entry from a pause; `checkEnterPictureInPictureState`): test with one mini player at a time. The test app
  (`PipTestActivity`, System, Developer, "Mini player test"; `am start -n dev.launcher.app/.PipTestActivity [--es orient
  landscape] [--ez pip true]`) shows a fixed bright picture; "pip" enters at once (allowed while resumed: it replaces
  another app's). Without seamless resizing Android shows the app's icon over a plain colour while a PiP resizes.
- **A small view moved by its bounds makes Android record its parent again** (`View.setFrame` marks the parent
  invalidated), and the renderer then redraws all of the parent: the mini player's hole, moved with
  `setLeftTopRightBottom`, redrew the whole card window every frame (GPU 10.5 ms a frame on the S24). Move small things by
  translation (a property of their own node): only where they were and are is drawn again (7.1 ms). Check the redrawn area
  in a Perfetto trace ("Drawing L T R B" on the RenderThread).
- **Every window of our process draws on one render thread**: a frame of any of them that takes long holds up the frames
  of the others (gesture nav's thread waits in `postAndWait`). A window holding a SurfaceView does not allocate its
  buffers ahead (it requests transparent regions; `ViewRootImpl` then skips `allocateBuffers`): each of its first frames
  allocated a screen-sized buffer on the render thread (3.8 ms on the S24) and the deck's next frame missed its refresh
  (a repeated frame at the App Switcher's handover). The switcher's activity draws nothing and is drawn in software
  (`android:hardwareAccelerated="false"`): its frames never reach the render thread. In a software window frame-commit
  callbacks never run and `applyTransactionOnDraw` applies at once (the deck's surface is embedded before the window's
  first frame; "on screen" is `onEnterAnimationComplete`). Making a hidden `SurfaceControlViewHost`'s view visible cost
  its thread a 4 ms relayout and the render thread an 11 ms first frame (buffers, the blurred picture of home): the
  switcher's window content is drawn ahead while a mini player is up and nothing moves (`updateSwitcherWarmth`).
- **What moves from one window into another must look the same in both, frame for frame**: the switcher's dim was its
  window's background, under the picture of home that the window holds (in the card window the dim is the root, above
  the picture): the background brightened in one frame at the handover (S24 recording, 150 to 183 of 255). Check a
  handover's frames for a step in the background's brightness and for a repeated frame (`tools/shots`, per-frame diff).
- **`MotionValue` rests within half a unit of its scale**: the deck's scroll in hundredths of a card (~2.5 px a unit) ended
  with a 1 px jump onto its place; thousandths now. Give a value a scale with a unit under a pixel.
- **SurfaceFlinger's composite on the S24 takes 6-8 ms while the App Switcher is open** (with or without a mini player;
  ~3 ms otherwise), most of it the display's present (several screen-sized layers: our status bar's full-screen window,
  the card window, the app under it): little room is left, and one late composite repeats a frame. Not addressed yet.
- **A quick flick turns sideways before the gesture's task lookup is back** (~50 ms): the run of quick switches was built
  from the previous gesture's order without the app in front, and "one older" switched to the app already in front. It
  now takes the app in front from the window events and is built again when the lookup comes.
- **A start whose transition another handler plays never calls our remote transition** (a close into picture-in-picture:
  Android's PiP handler takes it). `NoAnimStarts` once read two such starts as "the system never hands ours over" and
  switched instant starts off for the session; it now remembers a phone where ours were seen called, and needs six silent
  starts before giving up. HomeActivity handles orientation changes itself (portrait only; never relaunched for them).
- **`Window.getInsetsController()` is null until the window has its decor**: asked in an activity's onCreate before
  `setContentView`, it crashed the launcher on every close from a full-screen video (`TurnCover`); use
  `window.decorView.windowInsetsController`.
- **Pictures of home draw the glass clock through its live shader**: when home rebuilds (any inset change used to do it,
  also a 1 px one: shown turned under a landscape app the status bar reads a pixel shorter), every picture drawn again
  after that loses the numerals. Home ignores inset changes up to 2 px (`HomeScreen.INSET_SLOP_PX`).
- **A view made for one window dies with that window**: the card window is made again whenever gesture nav's service
  restarts, and a view kept in a field (the cover that hands the settings back to home) stayed in the removed window, never
  drew, and the hand-back waited for it forever (the settings stuck on home's picture: "home freezes"). Make such views in
  the window they show in (`parent === host`), drop them with it, and never let a wait for a frame be the only way out.
- **A theme's Material You values go on the token another theme restyles**: a component's value on the `component.*`
  token, never on a `comp.*` alias of it. iOS 27's once tinted Control Center's glass, wells and veil on `comp.cc.*`: with
  the wallpaper's colours on, the resolver found those before the alias, and Pixel's tiles (set on the components) stayed
  iOS glass. A `comp.*` token that is no alias (the banners' plain colour, the count) is fine: a derived theme sets it
  directly and wins. Control Center's veil is drawn as one plain colour from its material's fills (`Shade.ccVeil`): a
  blend other than normal counts only as darkening there.
- **AOSP's animated icons are Lottie exports**: one group can be the target of several entries (one moving it, one turning
  it), groups move along motion paths (`translateXY` with `propertyXName`/`propertyYName` and a `pathData`), and a symbol
  shows its last frame (SystemUI jumps an icon set without animation to its end). Reading only the last target per name and
  skipping motion paths left Battery Saver's plus below the battery. Check a set on the preview page before shipping it.
- **A settings choice equal to the theme's own is no edit**: kept as one, it hides what a theme chosen later says (an Inter
  picked under iOS 27 would have kept Pixel's titles in Inter, the launcher's own symbols Pixel's set away). The draft
  resets such tokens instead (`Draft.apply`: symbols, fonts).
- **The wallpaper's colour must be read again when the wallpaper changes, not only when the palette does**: Design kept
  its first reading (at boot it could fail and fall back to the phone's palette) until the phone's palette changed, which
  One UI with its Color palette off never does. The stamp now counts the wallpaper's own colours, and a failed reading is
  not kept.
- **One UI 9 answers apps' `getWallpaperColors` with nothing**: the palette is made from Android's reading of the picture
  home draws (`Wallpaper.colors`, `WallpaperColors.fromBitmap` at load; the load line in the log shows it).
- **A change of the phone's palette is a change of every app's assets (a runtime overlay), and Android makes every
  activity anew for it** unless the activity handles `assetsPaths` (ActivityInfo.CONFIG_ASSETS_PATHS): home and the
  settings were made anew mid-reveal (the stutter), home again when the settings closed (a cold start's arrival: the
  flicker), twice under a new wallpaper (its reveal never played). compileSdk 35 cannot name the flag: `configChanges`
  points at an integer resource (`res/values/config_changes.xml`; the startup log says how the phone read it, "[app] home
  stays through palette changes"). Kept, home must do what being made anew did: apps' widgets are inflated again when the
  phone's palette or its light/dark changes (`LauncherWidgetHostView.refreshColours`, a blank layout first, else the same
  layout is only re-applied); a launcher that is not a system app cannot give widgets its own palette on Android 17
  (`AppWidgetHostView.setColorResources` builds a framework overlay: "no resources were overlaid"). And SystemUI writes
  `theme_customization_overlay_packages` again in its own form (Android 17: capitals, no alpha, a seed list): compare by
  seed and style, never as a string.
- **The phone's palettes, as found on the S24 (One UI 9.0, Android 17; read-only adb, services.jar searched with dexdump)**:
  Android's Material You palette is the Secure setting `theme_customization_overlay_packages` (JSON: `system_palette` seed
  hex, `color_source` "preset" or a wallpaper, `theme_style`, `_applied_timestamp`); AOSP's SystemUI rebuilds every app's
  palette from it within a second (emulator), but skips the one change that follows its own write. One UI keeps its own
  palette as two fabricated overlays, `android:SemWT_MonetPalette` (Samsung's apps) and `android:SemWT_G_MonetPalette`
  (Material You), built by system_server's `SemWallpaperThemeManager` from two 65-colour lists (5 palettes x 13 shades,
  Settings.System `wallpapertheme_color` and `wallpapertheme_color_for_g`, with `_isgray`, `_state`, `_style`); Wallpaper
  and style applies a palette through Samsung's `IOverlayManager.applyWallpaperColor(List, List, boolean)` (or
  `...WithStyle(..., String)`), which checks that the caller is signed with the system key (`checkSignatures(1000, uid)`;
  the shell's package is, `b378e95c` on the S24) and then rewrites the AOSP setting too (seed, "preset", TONAL_SPOT or
  MONOCHROMATIC). Tried from the shell (2026-10-19, with Matheesha's go): it passes Samsung's check, stores the lists in
  memory and builds the overlays, then Android refuses the commit ("Non-root shell cannot fabricate overlays": Samsung
  commits with the caller's identity), so **One UI's own palette cannot be set through Shizuku**; the same call with the
  original lists put its memory back. **Android's setting does work on One UI 9 for the role colours** (`system_primary_*`,
  surfaces...: SystemUI's `com.android.systemui:dynamic` overlay, above Samsung's): modern Material You apps (Google's)
  follow it; the shade palettes (`system_accent1_*`...) and Samsung's apps stay on One UI's. The palette generator is in
  Wallpaper and style, not the system. A palette once picked is a preset that later wallpapers do not replace (the S24's
  stayed beige under a green wallpaper).
- **A surface has a fill budget** (`MaterialPainter.MAX_FILLS`, now 12): under, own and over fills share it, and Material
  You adds two tint layers to every glass. Past it, layers were left out silently (a lit Control Center well lost its
  accent and looked off). The painter logs the first overflow.
- **Home closes what is open on it when it is out of sight: onStop, not onPause.** A launch pauses home at its start, but
  home shows under the growing card until the app covers it (the edit menu vanished as Change Wallpaper's card began).
  The card window's launch backdrop is a recording of home, which leaves the menu and its blur out: a launch from an open
  menu draws home as it shows (`HomeActivity.pictureAsShown`, a GPU render).
- **A wallpaper changed while away reaches home while a card still covers it** (gesture nav brings home forward as a
  close starts, and home loads the new wallpaper on that resume): its reveal played unseen under the card, and the
  card's picture (the old wallpaper) handed over to a home caught mid-reveal, the whole screen changing in one frame
  (Matheesha: "a full screen flicker for a single frame"). Home holds a change that would show until it is uncovered
  (`HomeBridge.whenUncovered`). Test wallpapers: `TEST_WALLPAPER --es file <path under files/>` (`Wallpaper.testHook`).
- **What is recorded must be recorded again when what it shows arrives later**: a notification's picture (loaded off the
  main thread after the platter's first draw) and an app's shaped icon (rendered by home, the system's until then) were
  kept in Notification Center's recorded content as they first drew (the app's icon in place of a sender's photo for the
  whole session). Put such inputs in the recording's key (`NotifPainter.pictureLoaded`, `iconKey`).
- **A hardware Enter calls a field's editor action twice** (its key down and its key up, actionId IME_NULL): Spotlight
  launched its top hit twice. Fields act once per Go or Enter through `components/onGo`.
- **One UI's own home turns a landscape app's mini player upright during the swipe; we cannot.** Its launcher moves the
  app's own window during the gesture and hands SystemUI the mini player's place (`IPip.startSwipePipToHome`, a shell
  interface SystemUI gives only the phone's recents component). Ours draws a card; from home's start until the display has
  turned Android hides our windows, and the mini player moves upright then (One UI's crossfade, ~180 ms on the S24).
  Flying the card upright instead would show the real mini player in its old corner for those frames.
- **A view with a hardware layer that only moves (translation, alpha) is not drawn again**: Notification Center's sheet
  slides in as a layer, so work started from its onDraw "once it is open" never ran (the stack's content recorded ahead);
  start such work where the motion is set (its progress setter). And framestats' "GPU ms" counts from the frame's issue to
  its GPU fence, buffer waits included: values stuck near 16.6 ms with a bare material are waiting, not drawing (judge the
  misses, and look in a trace before blaming a shader). The first run after an update reads the app's code and fonts from
  disk (`folio_wait_bit_common` in the trace): judge a step after a warm-up run.
- **A card's icon comes from its app, which may be known after its picture**: a fresh picture taken from the task
  already known (the app changed since its kept picture) arrives before the recent-tasks lookup, and neither path set the
  icon: the close crossfaded into nothing and the real icon appeared a moment later. The card takes its icon once its
  app is known (`GestureNav.cardKnows`).
- **Home's idle check must count every motion on it** (`HomeScreen.isIdle`): the theme reveal was not counted, and home's
  picture recorded mid-reveal (400 ms after onResume) missed the items not yet revealed, behind the next close.
- **A still picture fading out over something that moves shows everything on it twice**: the widget menu's lifted copy,
  a widget's old look and an app's update arriving mid-resize all faded out as pictures over a widget still growing or
  shrinking (the clock's date and a widget's buttons twice). Fade between live copies (recorded again at every frame:
  `ContextMenuView.LiveCopy`; an old layout kept as a live view laid out with the card: `LauncherWidgetHostView` ghost), or
  wait for rest. And a home rebuild must carry its modes over (edit mode once ended unseen under the old look's picture).
- **Kotlin's incremental build can miss a changed interface** (an implementer in another file not recompiled: the build
  "succeeds"). After changing an interface or abstract class, run `./gradlew compileDebugKotlin --rerun-tasks` once.
- **Another app's name or icon read through the PackageManager loads that app's resources** (`LoadApkAssets`: 60 ms for a
  plugin, 65 ms for the Permission Controller on the S24): never on a thread that draws. The App Switcher's first opening
  after a start read eight on gesture nav's thread (a 231 ms frame); names and icons come from caches filled in the
  background as soon as the recent apps or notifications are known (`GestureNav.lookAhead`, `shade/AppInfo`), and what
  comes late fades in.
- **A window added without a parent draws with the hardware renderer** (WindowManagerGlobal takes the app's
  `hardwareAccelerated`), so a window that draws nothing still allocates its screen-sized buffers on the render thread all
  our windows share (the App Switcher's touch window: 7.8 ms at each opening). Such a window draws in software on its own
  thread: made with a context whose `ApplicationInfo` has no `FLAG_HARDWARE_ACCELERATED` (`SwitcherInput.SoftwareDrawn`).
- **A picture rendered with `HardwareRenderer` + `ImageReader` and `setWaitForPresent` blocks its caller** for the buffer's
  allocation (on the shared render thread, 4 ms for a screen) and then for the GPU (6.6 ms): the settings' home gesture
  waited 15 ms at its touch. Where the picture can come a frame later, `ViewPicture.drawLater` (a `HardwareBufferRenderer`
  into a buffer made ahead with `prepare`, the fence waited for on a worker; the caller waits only for the sync).
- **HardwareBuffers a binder call returns stay open in the sender** until its garbage collector runs: the shell service kept
  every task snapshot it sent (~10 MB of graphics memory each). Close them once written into the reply (`ShellService.onTransact`).
- **A warm-up must run after what it warms has arrived**: the App Library's ran 0.9 s after a start, before its apps and
  icons, and the first swipe to it still recorded and measured everything (16-20 ms). It runs again when the library's
  content changes (`DrawerHost.onDrawerContentChanged`).
- **The renderer lets GPU images go that no frame used for ~10 s while frames run**, and a node's layer goes when the node
  leaves the drawn tree: a surface shown now and then (home's edit menu: its glass layer and blur images, ~6 ms) allocates
  them in its first frame each time.
- **A cached picture is keyed by what the view showed when it was recorded, not by the state the app has already
  taken**: a new wallpaper is taken (and home rebuilt and recorded for its colours) before its reveal starts, and the old
  wallpaper recorded under the new one's key was kept: every close showed the old wallpaper behind the card
  (`HomeActivity.recordNow` keys on `wallpaperView.wallpaper`). And the key holds everything that shapes the result:
  Notification Center's glass numerals were keyed by time, wallpaper and fonts but not the panel's size, and after a
  landscape app the portrait panel drew the landscape numerals, a sliver of the time at its edge.
- **CI is the build machine**: the cloud sandbox cannot reach Google Maven. If a local Android setup exists, prefer local builds; keep CI as a backup.
- **Our own code is compiled at install only through `app/src/main/baseline-prof.txt`** (`HSPLdev/launcher/app/**;->**(**)**`
  and the class rule): the install profile held only the libraries' rules before (2026-10-19), and everything of ours ran
  interpreted after each update until the JIT had seen it (every first open and close after an UPDATE slower). Check with
  `cmd package dump-profiles dev.launcher.app --dump-classes-and-methods` (our methods listed) and `pm art dump`.
- **A layer that changes size every frame is a new GPU image every frame**, and scattered sizes fill the renderer's GPU
  cache until icons and other textures leave it (then they are uploaded again inside a later first frame: prepareTree
  10 ms). Live glass partly off the screen kept a node cut to the screen (the library's field sliding in: 1768 image
  allocations in three swipe pairs); `MaterialPainter.drawLive` keeps the node whole and clamps what it reads instead.
  `dumpsys gfxinfo` "Scratch TextureRenderTarget" shows it (99.8 of 121 MB then).
- **Glass faded by its opacity shows what is behind it sharp through itself** (the shader multiplies its whole result):
  behind a menu home is blurred, which hid it; an alert over home showed home crisp through the panel until it was whole.
  Glass that appears over unblurred content materialises instead (`Material.materialized(k)`: its frost grows on the
  log scale, its lens, fills, rims and shadows come in; at 0 it is exactly what is behind it), drawn at opacity 1.
- **Home takes a touch for edit mode before its overlays see it** (`HomeScreen.onInterceptTouchEvent`: an item under
  the finger belongs to edit mode): an overlay that handles every touch itself (Spotlight, the menu, an alert) is
  named there, or a tap on its button over an icon went to edit mode (the alert over edit mode did nothing).
- **A shape drawn with a blur mask filter or a shadow layer is blurred again on the CPU at every frame it moves by a
  fraction of a pixel** (Skia keys the mask by the fractional offset) and uploaded as a new texture: shadows that move use
  `theme/SoftShadow` (rendered once per size, drawn scaled). Text shadows are fine (glyph cache).
- **`RuntimeShader.setFloatUniform` is a JNI call that finds the uniform by name**: a glass surface setting ~30 at every
  draw was a fifth of the main thread with many surfaces (the library's tiles); `MaterialPainter.uniform` sets only what
  changed. Constructing a `RuntimeShader` compiles its source (1-2.5 ms on the S24; traced as "RuntimeShader NAME").
- **A 1 x 1 window on the screen is composed on the GPU by SurfaceFlinger ("CLIENT")** at every frame the screen changes
  (the display's composer refuses it; 8 px too, 64 px is taken). **Window alpha 0 is no way out**: such a layer is not
  visible for input (a focus window at 0 could not take the keys: Back over a panel ended in "not responding", S24) and no
  longer counts as drawn for a rotation (Android's async rotation timeout, 2 s with every overlay of ours hidden). Idle
  tiny windows are parked just off the screen (`LiveBlur`, `FocusHolder`). Check `dumpsys SurfaceFlinger` "HWC layers".
- **Focus in a text field starts autofill, which builds the window's whole view structure** on the main thread (every text
  laid out: 17-25 ms in Spotlight's first opening): home and the settings set `IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`.
  **A focused field that moves sends the keyboard its cursor position at every frame** (Samsung's keyboard asks for it:
  17 % of the main thread over Spotlight's opening): our search fields are `components/SearchEditText`. And a field that
  follows the keyboard moves by translation, never by layout params (a layout of home at every frame of the keyboard).
- **SurfaceFlinger's first blur of a boot costs its composite ~13 ms** (Control Center's first opening): the live blur is
  prewarmed with a real blur in a small square inside the display's rounded bottom-left corner (hidden by it) for 300 ms
  after its window is added (`LiveBlur.prewarmCorner`; a strength that rounds to radius 0 is no blur at all).
- **Another app's name or icon (`getApplicationLabel`, `getApplicationIcon`) loads that app's resources** (`LoadApkAssets`:
  65 ms for the Permission Controller): never on a drawing thread. `shade/AppInfo` reads them before Notifs publishes.
- **A Kotlin lambda taking an `Int` boxes it at every call** (`(Int) -> Boolean` called per pixel: ~40 MB of Integers per
  glass clock build, a GC at every minute). Hot loops take arrays or plain parameters.
- **A GPU bitmap's `copy(ARGB_8888)` reads the whole image back on the render thread** (14-32 ms for home's picture, every
  window of ours waiting): `ViewPicture.softCopy` draws it scaled on the GPU into a CPU-readable buffer instead.
- **A change for every app (Android's palette, light and dark) keeps the system busy a while after it reaches us**
  (emulator: 0.4-1.4 s): every window of ours is laid out again (each a relayout call into the busy window manager, up to
  194 ms; the frame after it 1.5 s once), and every binder call of ours waits: apps' widgets inflated again inside the
  change's own callback dropped their views, and a clock's TextClock unregistering its receiver waited 116 ms, one after
  another on the main thread (home froze a moment after Other apps gave Android a palette: "it freezes for a second").
  Widgets follow such a change once `SystemCalm` finds the system answering at once (a receiver of ours registered and
  dropped from a worker thread, timed), one a frame, on a home at rest; Other apps gives Android a palette only when
  nothing would show the hitch (above). Nothing of ours makes a binder call on the main thread right after such a change.
- **Android reads "no starting window" (`ActivityOptions`' `android.activity.disableStarting`) only for a new activity and
  where a task is moved to the front** (`findTaskToMoveToFront`): a start that finds the activity's task already there
  brings it forward with a snapshot starting window whatever the options say (`ActivityStarter.recycleTask`). Home in an
  ordinary task (the S24's since an update: `type=standard`) got one at every return, a flat grey frame over home after
  its picture had gone (the flash after closing the settings, twice). Home's task comes back through
  `startActivityFromRecents` with the option (`InstantTransitions.start`); check with the S24's system log
  (`SnapshotStartingWindow for taskId=`) or, on the emulator, `cmd protolog logcat enable WM_DEBUG_STARTING_WINDOW`.
- **After a crash while Android binds the notification listener, the system keeps the binding as made** ("is already
  bound") and never retries: our shade empty until a reboot. `LauncherApp.watchNotificationListener` switches the component
  off and on when it has not connected 8 s after a start (a rebind request alone is ignored for an enabled listener).

## Build, release, update pipeline (to recreate in this repo)

1. GitHub Actions on push to `main`: `./gradlew assembleDebug -PbuildSha=${GITHUB_SHA}`; `versionName` = first 7 chars of the sha.
2. Publish a rolling prerelease tagged `latest` with the APK (`Launcher.apk`) and its baseline profile's dex metadata
   (`Launcher.dm`), release notes = full sha: **updated in place** (tag moved,
   `gh release upload --clobber`, `gh release edit --draft=false --notes SHA`; `gh release create` only if none exists).
   Deleting and recreating it at once left it a draft (GitHub deletes the old tag in the background, after the new release
   took it): a draft is not public and the phone's updater got 404.
3. App shows `BUILD <sha7>` in its first log line; **UPDATE** compares that to the release, downloads the APK and the
   `.dm` inside the shell process, installs both in one session (`pm install-create/-write/-commit`; the APK alone if the
   `.dm` is missing or refused), then `am start`; if still alive after ~12 s it prints the installer's output.
4. Publish build logs to an orphan `ci-logs` branch so a cloud session can read failures without log paste.
5. The repo must be public for the updater (no auth on the phone).

## Checking a build before Matheesha does (required)

Every change is checked on the emulator before it is pushed, with screenshots looked at, not assumed:
- `tools/build.sh` (errors only), `./gradlew testDebugUnitTest` (logic tests), `DEVICE=emulator-5554 tools/reinstall.sh`.
- `tools/device.sh shot|tap|long|drag|swipe|log|gfx` drives the device; screenshots land in `tools/shots/` (git-ignored).
- Emulator `Medium_Phone` (Android 17): start with `emulator -avd Medium_Phone -no-window -gpu host`. Shizuku is installed:
  start it with `adb shell <shizuku apk dir>/lib/x86_64/libshizuku.so` only if `ps -A | grep shizuku_server` shows none
  (two servers: our service attaches to the wrong one and never connects; two were also seen right after a boot: kill the
  older, `kill <pid>`, then restart the app); a long-running emulator runs out of memory
  and swaps (blurs take seconds, ANRs): restart it after some hours; enable our accessibility service with
  `settings put secure enabled_accessibility_services dev.launcher.app/dev.launcher.app.NavAccessibilityService`.
- `PreviewActivity` (`am start -n dev.launcher.app/.PreviewActivity`) shows components on their own.
- A panel at rest for a screenshot: `am broadcast -a dev.launcher.app.TEST_SHADE -p dev.launcher.app --es do open [--es
  panel cc|nc]` (opened as a released pull opens it), `--es do close` (emulator; on the S24 never Notification Center).
- A theme pushed through the inbox (`files/themes/<id>.json`) wins over the app's own until removed (`--es files remove
  --es path themes/<id>.json`): a stale copy from an earlier test hid a new build's theme changes.
- Do not run `uiautomator dump` while gesture nav is on (it unbinds accessibility services).
- Scripted swipes on a busy emulator arrive as a few coarse events: a release's speed uses only the last 100 ms of
  samples, so a 120-300 ms `input swipe` can read as still and not turn a page (home's pages, the deck); swipe past
  halfway, and check a pulled panel really stayed open (its log, the focus) before tapping into it.
- **A tap during a close cannot be scripted at full speed on the emulator.** An `input tap` right after an `input swipe`
  never reaches the input dispatcher (not in `dumpsys input`'s RecentQueue, exit code 0); send the tap as touchscreen
  events (`adb emu event send`, as `tools/kswipe.sh`), the flick with `input swipe` (console flicks are slow and open the
  App Switcher). And there home takes ~1 s to come to the front, so every tap lands after the card: slow motion first
  (`design/motion-edits-ios27.json` with `"motion.debug.slow": {"factor": 4}` through the inbox; `--es files remove` undoes).
- What only the S24 can show (Samsung blur, One UI quirks, real frame pacing) is reported to Matheesha as unverified.
- Settings changes are checked with the theme's colours and the wallpaper's, in light and dark: a path only the theme's
  colours took (a part's colour calling itself) crashed the settings at the first frame after a reset to them.
- Smoothness on the S24 is measured from what the screen showed: `tools/scenario_spotlight_close.sh`, `scenario_library.sh`,
  `scenario_switch.sh` run launches/closes/switches N times and print per-animation missed refreshes and GPU/CPU per frame
  (`tools/framestats.py` on `dumpsys gfxinfo framestats`; frames whose present time is before their own vsync are not real
  and are dropped). `tools/scenario_more.sh` covers every other animation (home's own motion, folders, Spotlight, dock, grab,
  cancel, long deck, flick). For a late frame, record a Perfetto trace on the phone and list our long slices with touch
  markers: `tools/trace_slices.py TRACE [MIN_MS]`. Compare against the previous build (`git stash`, build, install,
  run, `git stash pop`) before calling a change an improvement. `tools/find_icon.py` finds an icon on a screenshot
  (template in `tools/shots/`) so a script never taps a guessed position. The App Switcher (`scenario_switcher.sh`) is
  measured on the phone by frame stats only: it shows other apps' snapshots, so it is looked at on the emulator.
  The shade: `scenario_shade.sh` (Control Center open/close/edit, Notification Center, a test banner; window
  `LauncherStatusBar`). Test notifications and banners: `am broadcast -a dev.launcher.app.TEST_NOTIFY -p dev.launcher.app
  --es title T --es text X --ei id N` (`--ez cancel true` removes it).
- When the S24 is connected (USB or wireless adb, `DEVICE=<serial>`): animations are checked frame by frame with
  `tools/device.sh rec NAME [SECS]` … `recpull NAME [WAIT]` (screenrecord captures every composed frame, 120 fps; contact
  sheets and frame times land in `tools/shots/NAME/`; needs `pip install imageio-ffmpeg`). Frame logs give the numbers.
- **A warm S24 caps its CPUs** (after an hour of tracing: processor 51 °C, both clusters at 2.0 GHz of 2.6 and 3.2):
  timings drift by 2-3x within minutes. Compare builds interleaved, not an hour apart, and check `dumpsys thermalservice`
  and `/sys/devices/system/cpu/cpu*/cpufreq/scaling_max_freq` before judging a number. CPU profiles of the debug build:
  `simpleperf record --app dev.launcher.app -g -f 4000 --duration N -o /data/local/tmp/x.data` (`-p PID` is refused),
  `simpleperf report-sample --show-callchain` for per-callee sums.
- Traces: `DEVICE=... tools/trace.sh start NAME [SECS]`, act, `tools/trace.sh pull NAME` (config `tools/perfetto_launcher.cfg`:
  scheduling, CPU frequency, the app's sections, graphics, input, window manager, SurfaceFlinger's frame timeline), then
  `python tools/trace_slices.py tools/shots/NAME.pftrace [MIN_MS]`; missed frames per window are in
  `actual_frame_timeline_slice` (jank types "App Deadline Missed", "Dropped Frame"). `DESIGN_RELOAD --es files export`
  also copies `home_layout.json` (the dock and pages, for checking a layout without run-as).
- `dumpsys gfxinfo ... framestats` keeps only a window's last ~120 frames (one second at 120 Hz): reset just before a
  step and read it within a second; a longer window reads only its last second (sample several, or trace).
- `tools/frame_glitch.py REC.mp4 X Y W H [THR] [OUTDIR]` finds 1-4 frame flickers in a region of a recording (how the dock's
  one-frame glitch at the end of a grabbed close was found); `tools/appearance_tour.sh PREFIX` screenshots every surface.
- Unlock animations: the S24's lock screen is secure (Matheesha unlocks; read the "[home] arrival" log lines afterwards).
  On the emulator: `locksettings set-disabled false` once, then KEYCODE_SLEEP, KEYCODE_WAKEUP, start a recording,
  `wm dismiss-keyguard` (a swipe lock screen, dismissible from adb).
- On home, "HomeActivity focused" is not a checked state: a sheet, menu or folder may be open inside it. Start every block
  of scripted taps with two swipes up on the bar (a dock tap once hit the widget gallery's Add Widget button).
- On the phone, every scripted tap is gated on a checked state (focused window, keyboard shown, or a screenshot looked at):
  the Home key is blocked while our gestures run, and a tap on the wrong screen once opened a private app. Never record or
  screenshot inside private apps (messaging); delete any recording that caught one without viewing it.

## Local build (Windows dev machine)

- Package / applicationId: `dev.launcher.app` (placeholder, like the name). Source in `app/src/main/java/dev/launcher/app/`.
- Gradle 8.9 + AGP 8.7.3 + Kotlin 2.0.21 (same as the probe, proven on CI). Gradle 8.9 cannot run on JDK 25 (Android Studio's JBR), so build with JDK 17:
  `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"; .\gradlew.bat assembleDebug`
- Keystore: `launcher-debug.keystore` (alias `launcher`, password `android`), committed on purpose. Local and CI builds install over each other.
- `local.properties` (SDK path) is git-ignored; local builds are stamped `BUILD local`, so UPDATE always replaces them with the CI build.

## Plan

**Current plan (2026-10-10): `docs/PLAN_LAYOUTS_THEMES.md`.** Layouts (per element, with placement and options), themes
(the look only, Figma-equivalent properties, Material You on any theme) and animations are three separate layers, any
combination valid; a Compose settings app themed by the active theme; elements one per round (status bar, shade, recents,
drawer, home, context menu), each checked on the S24 before the next. A theme file never chooses a layout. The first
Pixel attempt (a theme that switched layouts) is on branch `pixel-v1`, notes in `docs/PIXEL_PROFILE.md`.

Original build order (details and gates in `docs/REQUIREMENTS.md`): 1 Foundation → **2 Smooth core** → 3 Home and drawer → 4 Shade → 5 Themes and builder → 6 Glass and polish → 7 Release.
Phase 2 decides whether the whole idea works.

**First tasks, in order**
1. Project skeleton: Gradle, CI + keystore + rolling release + updater, `BUILD` stamp, Shizuku link, `HOME` launcher intent filter.
2. Safe-settings screen + notification "Restore system" action (no Shizuku needed).
3. Watchdog with heartbeat from a **foreground service**; kill-test it (force-stop the app, expect restore in ≈4 s).
4. Phase 2 slice: gesture strip, launch and close cards, recents carousel from cached snapshots, own status bar.
5. Frame-log test screen: a scripted open/close/gesture run that prints dropped frames, so "no visible stutter" is a number.

## Open items (not decided or not verified)

- Play Store policy: downloaded/interpreted theme scripts; Play Billing for the paywall; review risk of self-granting permissions via Shizuku; whether the silent updater may ship in the store build.
- Longer stress test of Samsung dim-blur (one run dropped to 60 Hz at the strongest level; first use had a 125 ms hitch → pre-warm a zero-dim blur window).
- Our shade replaces the stock one (and its heads-up, by our banners). Still stock: in-app links' animations; launching from a
  notification uses the app's own transition (no card from the platter yet); replying in place (the keyboard sits under our
  overlay).
- Stock status bar chips ignore the disable flags on the Android 17 emulator (modern SystemUI), DISABLE_ONGOING_CALL_CHIP
  included (set since 2026-10-18; AOSP's chips no longer read it): a call in progress's timer chip draws under our time;
  the mic/camera privacy chip (a system safeguard, it must stay) draws beside our battery; and a charging chip plays on
  plug-in (seen 2026-10-18: a dark pill round the stock battery, the bar's full height, ~2 s after the plug-in for a few
  seconds), under our bar as One UI's does: our charging moment plays on Samsung only, and its capsule (57 px tall in the
  emulator's 63 px bar) would not cover a full-height chip. On One UI see the pitfall "One UI's status bar shows parts of
  itself that no disable flag hides". Our bar has no call capsule yet (iOS's green).
- Review the thedjchi Shizuku fork's code before recommending it in onboarding.
- Accessibility requirements need writing. Theme gallery/sharing plan, app name: undecided (placeholder "Launcher").
- A licences page before release: Inter (OFL), AOSP's Quick Settings icons (Apache-2.0: `assets/icons/pixel.json`, each
  symbol with its source), Material Symbols (Apache-2.0: the app's own `sym_*`).
- Everything was probed on one S24; re-run the key probes on other devices after phase 2.
