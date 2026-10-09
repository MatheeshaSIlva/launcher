# Design system first: the plan to make the launcher consistent (2026-10-07)

Matheesha: "the current design is very inconsistent, especially compared to iOS: different blur levels, different tints,
buggy animations. Fix one issue at a time, and build around the phases to come so nothing has to be scrapped."

## Why it is a mess (measured, not felt)

- **Four glass renderers** (`GlassDrawable`, `LiveGlass`, `PanelGlass`, `WallpaperTransition`'s shader), each surface choosing
  its own backdrop, blur and tint: about 120 places in the code set a blur (wallpaper "blurred" vs "heavy", Control
  Center's 26 pt picture, the long look's 22 pt, One UI's dim-to-blur at 0.15, menus' own blur...).
- **Values written into drawing code**: the shade alone holds ~126 colour literals, 400+ lengths and ~47 springs of its
  own; tints and scrims are spread over ten files. A theme engine cannot reach any of them.
- **No record of where a value came from**: some are Apple's kit read exactly, some measured on the Simulator, some judged
  by eye; nothing in the code says which. Figma's glass parameters were translated to our shader by hand, never checked.
- **Checks were ad hoc**: dark mode not looked at for most of the shade; animations frame-checked only where a bug was
  suspected; the S24 measured for some transitions only.

## What we build toward (phase 5, `docs/REQUIREMENTS.md`)

A theme is four swappable layers: **Motion**, **Colour and effects**, **Shape and spacing**, **Layout**, in plain-text
theme files, edited live by a builder. Everything below is that structure, built now, so the iOS 27 look becomes the first
theme instead of code that has to be rewritten later.

## How we work from now

1. **One source of truth.** Every visual value is a named token in the design-system layer; drawing code reads tokens,
   never literals. Colour tokens have a light and a dark value (as Figma's variable modes).
2. **Provenance.** Every token records where its value came from: *kit* (Figma node id), *measured* (Simulator pass and
   test, or an S24 measurement) or *judged* (with the reason). Judged values are the first to be replaced by evidence.
3. **One material system.** Every glass or blur surface is a named material from Apple's kit (clear glass, regular
   glass, dock glass, small control, the classic materials, the dims and overlays), drawn by one renderer.
4. **Components like Figma's.** An element is layers: paints (colour, gradient or image, with blend mode and opacity),
   strokes, effects (glass, blur, shadow, inner shadow), geometry, text styles; with variants and states (on, off,
   pressed, disabled). Shared painters draw them; views keep only layout and touch.
5. **Motion by role.** Every animation uses a named role (spring or ease) from the motion layer; a transition is an
   implementation of an interface, so a theme can swap it.
6. **One issue at a time**, each ending with the checks below, one commit, and Matheesha's look on the phone before the
   next one starts.

### Definition of done (every issue)

- No literal left in the touched drawing code (a script checks colours, lengths and springs).
- Every new or changed token has its provenance.
- Emulator: every touched surface screenshotted in light and dark, side by side with the iOS reference; every touched
  transition recorded and looked at frame by frame (`tools/frame_glitch.py` on its regions).
- S24: frame stats of every touched animation (the scenario scripts), compared with the previous build: no new missed
  refreshes. Notification Center and banners: frame stats only, never captured.
- `docs/` updated; one commit; Matheesha checks it on the phone.

## The steps

**Step 0: baseline.** Commit today's uncommitted work as a checkpoint (its fixes are real: the crash on hidden settings,
the shade after an accessibility restart, the notification flicker), so every step after it is a clean diff. Then get
the evidence the next steps need, and only that: Apple's kit for the expanded modules and every control it shows; a
Simulator pass with notifications granted first (the list scrolled, the long look, opening an app, dark mode); an
authoritative list of iOS 27's controls.

**Step 1: the design-system core** (no visible change). *Done 2026-10-07:* `app/.../design/` (keys, values with
light/dark, aliases, provenance, materials with Apple's per-mode layers, the resolver, the live `Design` object, the
`Scale` policy); the iOS 27 theme is already a theme file (`assets/themes/ios27.json`, 130 `ref` tokens generated from
the kit by `tools/design/build_ios27_theme.py` out of `docs/tokens/ios27-kit.json`, 32 `sys` tokens), which pulls part of
step 5 forward; the **token editor** ("Launcher design" in the app list, or "Design tokens" in the developer panel):
every token with its source, editors for every kind, edits kept in `files/design/user.json`; unit tests
(`DesignThemeTest`). Nothing draws with tokens yet: that starts with step 2a.
Original plan: A `design/` package: tokens for colour (light/dark), materials,
shape and spacing, type, motion roles; how points become pixels (today 1 pt = width / 402; the policy becomes a token so
Android's display size and font size can be honoured later); the iOS 27 profile as data with provenance; unit tests.

**Step 2: one material system.** One material spec (backdrop source: wallpaper, picture of what is behind, or live; blur;
lens; fills with blend modes; rims; shadows) and one renderer replacing the four. Surfaces move over one per issue:
2a Notification Center (platters, collapsed stack, buttons, clock), 2b Control Center (controls, expanded modules,
gallery), 2c banners and the long look, 2d home (dock, folders, widgets, menus, Spotlight, App Library). This is where
"different blur levels, different tints" ends: one value per material, from the kit.
*2a done 2026-10-07* (`docs/PROGRESS.md`): `design/MaterialPainter` (one shader for every material token), `FrostCache`
(the backdrop blurred per frost), Notification Center entirely on it and on 57 `comp.nc.*` tokens, checked against the
kit's own render over its own wallpaper. The long look's menu moved with it (its card was already the kit's). Left for
later steps: the kit's progressive scroll edge (a judged fade stands in), the stacked front's measured detail beyond the
kit's numbers. On the S24 it is no slower than before (the fan-out is lighter); the fan-out's GPU time and an occasional
late scroll frame, there before too, are still to be traced.
*2b done 2026-10-08*: Control Center, its expanded modules and the gallery on `CcSurfaces` (the same renderer) and 91
`comp.cc.*` tokens, compared with the kit's own render over its own wallpaper.
*2c done 2026-10-08*: banners on the regular glass (`comp.banner.*`), over the wallpaper on home and over an app's
background colour elsewhere; `PanelGlass` removed. The long look moved in 2a.
*2d, part 1 (2026-10-08)*: the dock, the Search pill, the widgets' glass and edit mode's buttons on the kit's dock material
(`comp.home.*`) through `GlassView` and the renderer (which learnt home's depth zoom, the wallpaper's reveal and the
arrival's light). *Part 2*: the long-press menu (the kit's Regular glass), the search fields (the kit's search field: the
small glass), App Library, Spotlight and the widget gallery, through the renderer's live form (`drawLive`) or over the
blurred backdrop (`drawer/BackdropGlass`); `LiveGlass` removed. Left: the glass clock's numerals (a text shape) stay on
`GlassDrawable` until the renderer draws shapes other than rounded rectangles.

**Step 3: components.** Platter, control tile (sizes and states), slider, pill and round buttons, sheet, menu, badge,
status row, clock numerals: specs plus painters. The shade's views shrink to layout and touch. *Menu done 2026-10-08*
(`components/Menu.kt`: home's menus, the App Switcher's and, since 2026-10-09, Notification Center's long look).
*Badge done 2026-10-09* (`components/Badge.kt`: the count on icons and cards, Notification Center's stack count, edit
mode's remove badge; `comp.badge.*`, `comp.nc.count.*`). *Slider done 2026-10-09* (`shade/CcParts.kt`: Control Center's
module and expanded slider). The rest goes by a sweep of what is still decided in code: colours first (the shade's done
2026-10-09: about 40 literals into `comp.cc.*`, `comp.nc.*`, `comp.banner.*`, `comp.statusbar.*`; home's palette, which
`Appearance` held, is the theme's `sys.color.material.*`, `sys.color.press`, `sys.glass.tint`), then symbol, type and
corner sizes; positions stay in the iOS profile's layout code. Still in code: the strengths of the soft shadows under
white text over pictures (several judged values), surfaces' fallbacks without the glass renderer, fade masks.
With it, the **look audit** Matheesha asked for (2026-10-08): tints and opacities that differ between surfaces (or with
the wallpaper) listed surface by surface over a light, a mid and a dark wallpaper, and every difference from iOS 27 that
is not a decision of ours, each fixed in its token. Already done: the kit's hairline rims at a theme strength
(`sys.glass.rim`: they read as a black outline over dark backdrops); the dock's edge fringe (frosted backdrops cover their
image exactly); home's names and the glass clock take a light or dark tone from the wallpaper under them (`LabelTone`,
`comp.home.label.*`, `comp.home.clock.*`). *Audit done 2026-10-09* (every other surface consistent over the three
wallpapers in both modes).

**Step 3+: Android conveniences** (requested 2026-10-08; built on step 3's menu and buttons, which come first; the rest of
step 3 after them). The aim, in Matheesha's
words: the Liquid Glass look with the convenience and customizability of Android.
- Edit mode: **Change Wallpaper** in the Edit menu (the system's wallpaper picker; the new one arrives with the reveal). *Done 2026-10-08.*
- App Switcher: a **Clear All** button (the cards leave one after another; the apps' tasks are removed), and a **menu
  from the app's name** above a card (App info, Close, and what Android offers there: split screen, pop-up view, pin). *Done
  2026-10-08* (App Info, Keep Open, Close; split screen and pop-up view stay out: launches into them from the shell stick).
- Banners over an app on real glass: a picture of the app behind (its task snapshot when the banner comes) instead of
  the flat colour they sit on now. *Done 2026-10-08 (unchecked on the phone).*
- One UI's "Brief" notification pop-ups (edge lighting) come from the system server and are not stopped by the flags
  that stop the stock heads-up: onboarding finds the setting and offers to switch it to "Detailed" (and back on restore).
  *Done 2026-10-09* (`PopupStyle`: one of our banners offers it when our shade takes over).
- Optional, by token: the glass's light follows the phone's tilt (iOS moves its highlights with the device); today its
  direction is fixed (the kit's inner shadows at top and bottom, the rim light from a fixed angle).

**Step 4: motion.** Every animation listed with a role name; the registry; transitions behind interfaces (launch, close,
panel open and close, module expand, stack fan-out, banner in and out...). Then the animation audit: each one recorded
and frame-checked (S24 where it shows no private content, emulator otherwise), every defect listed in `docs/` and fixed
one at a time.

**Step 5: the theme file.** The iOS 27 profile written as a plain-text theme file, loaded at start; a second small test
theme proves a theme restyles the shade; reloading over adb for fast iteration (the builder's groundwork).

**Step 6: features again**, each built on the system: hiding Notification Center's list into a count, Control Center's
pages, gallery search, the missing controls, grouping by conversation, "Carrier" in the status rows, honouring Android's
display and font size.

## Decided / open

- Decided (2026-10-07): today's work is committed as the baseline; every finished issue is pushed to `main` and
  Matheesha installs it with UPDATE.
- Decided: a **token settings screen** (in the app) where Matheesha adjusts the tokens himself and sees the result, to test
  the system as it grows. It is the builder's first form (phase 5).
- The depth to aim for (not all now, but nothing may block it): the tokens, materials, components and motion must be
  able to turn the iOS look into a Pixel (Material You) look, or any other look, **completely**. So: no element whose
  look or motion is decided in code; layouts and component sets swappable per profile, not only colours and numbers.
- Decided (2026-10-08): smoothness is measured and tuned **once, at the end** of this plan (every code change moves the
  numbers). Until then each step is checked for looks on the emulator; known items for that pass are kept in
  `docs/PROGRESS.md` (step 2a: the fan-out's GPU time, an occasional late scroll frame; the look audit: every
  `HardwareRenderer` of the app shares its one render thread, so a frost bake (`FrostCache`, `BlurBaker`) holds up the
  next frame of every window while it runs: time the bakes on the S24, split or move them off the busy moments).
- Open: the theme file's syntax (step 5).
- Decided (2026-10-08): the goal is the Liquid Glass look with Android's convenience and customizability: where iOS
  leaves something out that Android users rely on (Back from the edge closes a panel, Clear All, an app's menu in the
  switcher, choosing a wallpaper from home), we keep it, drawn in the same glass.
