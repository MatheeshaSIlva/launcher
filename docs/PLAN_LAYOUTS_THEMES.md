# Layouts, themes and animations: the plan

Status: **agreed 2026-10-10** (phase A done: `main` back to the S24-checked build). Replaces the "theme switches
everything" approach of `docs/PIXEL_PROFILE.md` (the Pixel profile, parts 1-8, was a first run: on the S24 the Pixel app
drawer's blur stopped working after a moment, the widget gallery stayed iOS, the shade's pull and Quick Settings'
behaviour were not Pixel's, it felt like a cheap copy, and after switching back to iOS the context menus were broken).

## What was decided (Q&A, 2026-10-10)

- **Three independent layers**, any combination valid:
  - **Layouts**: for each main element (status bar, notification / Quick Settings shade, recents, app drawer, home) the
    user picks a layout, where the element lives (a fixed set of placements per element), and the layout's options.
  - **Themes**: the look only. A Figma-equivalent property set (fills, strokes, effects, corners, type, spacing),
    component styles (toggles, sliders, buttons...), Material You colours available on every theme, fonts changeable
    everywhere (Google Fonts built in), paddings and radii included. Customisable in the settings app with previews.
  - **Animations**: every motion role gets a spring or a bezier curve; presets, and a curve editor.
  - Beyond the built-ins: shader effects (AGSL), sandboxed scripts, declarative custom layouts.
- **Context menu**: one menu with every useful option from every OS (the space used cleverly); how it presents itself
  (the item lifts and home blurs, or a popup in place) is part of the theme.
- **Layout catalogue**: iOS, Pixel and One UI for every element, plus our own ideas (below).
- **Themes**: iOS glass, Pixel (Material), One UI, the Claude app's look, and other popular modern design languages;
  each can take Material You colours.
- **Settings app**: Jetpack Compose, follows the active theme, full of motion: a first-run walkthrough, live previews,
  editors that show exactly which value changes, guides.
- **Business**: free app; the advanced features that need Shizuku are the paid tier; a website before publishing. So no
  GPL code (ideas from GPL projects only).
- **References**: AOSP SystemUI and Launcher3 (Apache-2.0: the exact behaviour and curves of Pixel's shade, Quick
  Settings and recents), Lawnchair (Apache-2.0), captures of One UI's stock UI on the S24 (allowed, phone free, never
  Notification Center).
- **Order of the elements**: status bar, shade, recents, drawer, home, context menu.
- **Way of working**: one element per round; each round agreed first, checked on the emulator and on the S24 (switching
  layouts and themes both ways included) before the next one starts.

## The model

### Layouts (what goes where and how it behaves)

- Each element is an interface (`StatusBarLayout`, `ShadeLayout`, `RecentsLayout`, `DrawerLayout`, `HomeLayout`) with
  any number of implementations in a registry: id, name, preview, the placements it supports, and an options schema
  (types, ranges, defaults) that the settings app turns into controls.
- The **setup** (one file, `files/setup.json`): per element, the chosen layout, its placement and its options. Separate
  from the theme: a theme file never names a layout.
- Switching a layout tears the old one down and builds the new one cleanly, with a transition (no frame without an
  element; gesture nav, icons on screen, pictures of home all re-published before the next gesture: the "card flies into
  the top-left corner" bug of the Pixel run came from a home rebuilt behind an app).
- A layout reads the theme only through **roles and component styles** ("panel surface", "notification card", "toggle",
  "title text"), never through one OS's tokens: the same Pixel shade layout draws in iOS glass or in One UI's look.
- Geometry that belongs to the structure (how many tile columns, where the panel docks, row counts, gestures) is the
  layout's; sizes that are part of a look (paddings, radii, text sizes, icon size) are the theme's.

### Themes (how everything looks)

- **Variables** (Figma's variables and styles): colour roles with light and dark values and a Material You mapping
  (palette from the wallpaper through material-color-utilities, so Samsung gets real palettes too), text styles, effect
  styles, numbers (radii, spacing). Change one and everything using it follows.
- **Paint** (Figma's properties, one renderer for all of it, grown from today's `MaterialPainter`):
  - fills: solid, linear / radial / angular gradients, image, noise; several stacked, each with opacity and blend mode;
  - strokes: inside / centre / outside, per-side widths, dashes, gradient strokes;
  - effects: drop shadow, inner shadow, layer blur, background blur, glass (refraction, rim light, frost);
  - corners: per-corner radius and corner smoothing (squircle);
  - opacity and blend mode of the whole.
- **Components**: each UI component (panel, card, notification card, tile, toggle, slider, button, segmented control,
  chip, list row, search field, menu, badge, scrim...) has a style: paints for its parts, text styles, paddings and gaps,
  states (pressed, on, disabled), and a variant where the shape itself differs (toggle: iOS switch, Material switch with
  an icon in the thumb, One UI; slider: thick iOS, Material Expressive with gap and stop dot, One UI).
- **Type**: a font per text role; Google Fonts through Android's downloadable fonts (no API key); our bundled fonts.
- **Icons**: mask shape, icon packs, themed (monochrome) icons.
- **Context menu presentation**: lift and blur, or popup in place.
- Theme files extend each other and hold only what differs (as today); users' edits are kept per theme.

### Animations (how everything moves)

- **Roles** (app open and close, panel pull and settle, page snap, menu open, list reflow, toggle, appear, leave, press,
  ...) each get a curve: a spring (response and damping) or a cubic bezier with a duration; presets (iOS, Material
  Expressive, One UI, snappy, calm) and the user's own.
- What follows the finger stays the layout's behaviour; how it settles when let go is the animation's.

### Beyond the built-ins (later phase)

- **Shader effects** (AGSL, Android 13+): a paint or effect type with a code field; compiled on the phone, errors shown.
- **Scripts** (JavaScript in a sandbox, Zipline/QuickJS, Apache-2.0): gesture actions, data for widgets, theming by time or
  battery, reactions to events; no files or network unless granted. Play policy on interpreted code to check.
- **Custom layouts**: a declarative description (zones, grids, stacks) previewed live, not raw code.

## Layout catalogue (to decide element by element)

| Element | From the OSes | Our ideas |
|---|---|---|
| Status bar | iOS, Pixel, One UI | live island round the camera (timers, music, calls, charging; our banners), centred clock, minimal |
| Shade | iOS split (notifications / controls), Pixel combined, One UI (and its split option) | side panel from an edge, notifications stacked at the bottom of home, timeline grouped by time |
| Recents | iOS deck, Pixel carousel, One UI cards / list | Mission-Control grid, vertical Rolodex (Android 5), dock-style strip |
| Drawer | App Library, Pixel vertical grid, One UI paged grid | honeycomb (Apple Watch), category tabs, alphabet list with a scrubbing wave, command palette (apps, contacts, settings, actions) |
| Home | iOS grid, Pixel grid, One UI grid | bento grid (mixed tile sizes, live tiles), one-hand list, search-first home, radial quick launcher |

Placements per element (examples): drawer as a page after the last, before the first, or a sheet from the bottom;
shade pulled from the left / right / anywhere on the top edge, or anywhere on home; recents from a hold during the home
swipe or its own gesture.

## Open-source projects

| Use code (Apache-2.0 / MIT, notices kept) | What for |
|---|---|
| AOSP SystemUI, Launcher3 | Pixel's exact shade, Quick Settings, recents, drawer behaviour and curves |
| Lawnchair | settings structure, icon packs, themed icons, Google Fonts loading, hidden apps, gestures, backup |
| material-color-utilities | Material You palettes for every theme on every phone |
| Haze | blur and glass in the Compose settings app |
| Lottie, Rive | motion graphics and guides in the settings app |
| Zipline | sandboxed scripts |
| Material Symbols, Lawnicons | Android's icon font; a themed icon pack |
| Taskbar, PieLauncher | desktop / freeform ideas; the radial launcher |

Ideas only (GPL-3.0): Kvaesitso, Neo Launcher, Smartspacer, Olauncher, Fossify Launcher.

## Phases

Each phase ends with a check on the S24 and Matheesha's OK.

**A. Repair** (done 2026-10-10)
1. `main` back to the last S24-checked build (`5339fa9`) by reverting the Pixel commits (`8f9419c`); the Pixel work stays
   on branch `pixel-v1` for parts to reuse later. UPDATE is safe again.
2. What broke was all in the Pixel code, gone with the revert; carried into the rounds that rebuild those parts:
   - the Pixel drawer's blur stopped after a moment, leaving it translucent (the App Library was fine): D4;
   - after switching from Pixel back to iOS the context menus were broken: B4 (switching must leave every element
     working; the cause found on `pixel-v1` first) and D6;
   - after a theme switch, closing an app sent its card into the top-left corner (home rebuilt behind the app): B4.

**B. Foundation** (no visible change for iOS users)
1. The setup file and the layout registry; today's iOS elements registered as the first layouts. **Done 2026-10-10**:
   `layout/Layouts.kt` (the five elements; per layout its id, name, origin, plain-words description, placements and
   options with their limits, for the settings app to show) and `layout/Setup.kt` (`files/setup.json`, made from the
   home settings of earlier builds on first start; everything read from it valid, invalid changes refused and logged;
   listeners on the main thread). Home reads its drawer style and placement and its options through it (`HomeConfig`).
   Test over adb: `am broadcast -a dev.launcher.app.SETUP -p dev.launcher.app --es element drawer --es placement
   swipe-up` (or `--es layout ID`, `--es option KEY=VALUE`); applied when home next comes to the front (live switching
   is B4). Checked on the emulator: migration, refusals, the drawer moved and labels toggled through the setup.
2. Theme engine v2: variables, the paint model, component styles; the current iOS 27 tokens migrated into it with the
   same look (checked surface by surface against today's screenshots). In four steps:
   - **B2a, colour sources** (done 2026-10-10): a colour may name the phone's palette (Material You): `@primary`,
     `@surface_container_high`, `@system_accent2_900`, with an alpha (`@primary/40`); kept as written (a user's edit
     keeps following the wallpaper) and looked up when drawn, again whenever the palette changes (a configuration change
     or a new wallpaper). Every theme may carry a `materialYou` section: the tokens that change when the user turns the
     wallpaper's colours on (`sys.color.source` = "wallpaper", kept with their edits of that theme). iOS 27's: every
     accent becomes the palette's primary, red its error colour (judged). A theme that can no longer be loaded falls back
     to iOS 27 and the fallback is remembered. Over adb: `DESIGN_RELOAD --es colors wallpaper|theme`, `--ez palette
     true` (logs the palette). Known: Control Center draws its symbols on an accent in the same white as on glass, low
     contrast on a pale primary; B2b gives controls their own "on" colours.
   - **B2b, the component catalogue** (done 2026-10-10): 14 shared components (notification card, control, slider,
     round button, button, menu, badge, remove badge, search field, names on the wallpaper, card, dock, sheet, panel)
     with 81 properties as `component.*` tokens, each named and described in plain words (`design/Components.kt`, for
     the settings app). The iOS 27 layouts' own tokens took their values from them and are now their aliases: every one
     of the 574 earlier tokens resolves to exactly the value it had (checked token by token; Control Center and home
     pixel-identical on the emulator). Below them stay the shared styles (materials `sys.material.*`, text, colours).
     A theme restyles every layout through `component.*` (Graphite now does). New: a symbol on a control's accent has
     its own colour (`component.control.accent-symbol-color`: white in iOS 27, on-primary in its wallpaper colours).
     Pure layout geometry (Control Center's grid, Notification Center's stack offsets) stays in each layout's tokens;
     the element rounds move it into the layouts. Later components (toggle, segmented control, chip, list row) come
     with the first layout or settings screen that draws them.
   - **B2c, paint v2** (done 2026-10-10): the one renderer (`MaterialPainter`) draws Figma's remaining paint
     properties. Fills may be gradients (linear, radial, angular, diamond; 2-4 stops, each colour any colour value,
     palette ones included), written as `{"gradient": {"type", "from", "to", "stops"}, "opacity", "blend"}` beside the
     short `["#colour", opacity, "BLEND"]`. Materials may have up to two strokes (`"strokes": [{"color", "width",
     "align": inside|center|outside, "opacity", "blend"}]`), the outer part drawn with the drop shadows. A surface may
     have a radius per corner (`radii` on `draw` / `drawLive`). Corners can be smoothed theme-wide
     (`sys.shape.corner-smoothing`, 0..1): iOS's continuous corner (the curve reaching 1.528 r along each edge, a
     superellipse of exponent 3.25 at full smoothing), giving way where the sides leave no room, so circles and capsules
     stay round. iOS 27: no gradients or strokes, smoothing 0 (round, as drawn so far; to judge against the kit later):
     Control Center and home pixel-identical. The Graphite test theme shows all three (emulator).
   - **B2d, fonts** (done 2026-10-10): a theme names two families, `sys.font.text` (body and labels) and
     `sys.font.display` (large titles); a text style uses one of them (`"family": "text"|"display"`) or names its own.
     Families (`theme/FontFamily.kt`): `inter` (bundled, variable; its optical sizes play SF's Text and Display cuts),
     `system`, `system-serif`, `system-mono` (the phone's own; on One UI the font picked in its settings) and
     `google:<Family Name>` (any family of fonts.google.com). Google fonts come from Google Play services' font provider,
     asked directly as AndroidX does (no AndroidX dependency; the provider's signature checked against Google's two
     certificates), one file per weight (rounded to 100) kept in `files/fonts/google/`: from then on they load at once,
     offline too, from the first frame of a cold start. Until a font arrives the system's sans stands in; on arrival
     (all weights asked for together) the design notifies once and the surfaces that follow tokens draw again. iOS 27:
     `inter` for both, pixel-identical on the emulator. Checked on the emulator: Manrope and Fraunces downloaded (1.6 s
     the first, ~0.1 s each further weight), Control Center switched live, everything after a restart, the glass
     clock's numerals in Fraunces. Known, for B4 and C: paints made once when a view is built (home's labels, the
     status bar) keep the font they got until they are rebuilt (B4's rebuild on a theme change); a font not yet on the
     phone shows the fallback until it arrives (the settings app downloads first and applies after, C2). Over adb:
     write `sys.font.text` into `files/design/user.json` (run-as) and `DESIGN_RELOAD`.
3. Animation engine v2: roles, springs and beziers, presets; today's iOS motion as the first preset. In three steps:
   - **B3a, curves and presets** (done 2026-10-10): every motion role (`motion.*`) is a curve: a spring (`{"spring":
     [response, damping]}`) or a cubic bezier over a duration (`{"bezier": [x1, y1, x2, y2], "ms": 300}`, CSS's
     cubic-bezier; x1 and x2 within 0..1), either kind for any role. The code runs both through one interface
     (`motion/Curves.kt`: `Mover`, the analytic spring and `Ease`), so every animation stays interruptible: a bezier started
     mid-motion keeps the speed it had (carried on and faded out by its end: `v0 · t · (1 − t/d)²`) and ends exactly on
     its target. Motion left the theme: the animation presets are files of their own (`assets/motion/<id>.json`, or
     `files/motion/`, built on each other like themes, checked against iOS 27's; a preset holds only `motion.*`, a theme
     none); chosen apart from the theme (`files/design/motion.txt`; adb `DESIGN_RELOAD --es motion ID`; the token
     editor's Animations button), the user's edits kept per preset (`files/design/motion-edits-<id>.json`; earlier motion
     edits moved there once). iOS 27's preset: the 112 motion tokens as they were (`comp.cc.motion.open` became
     `motion.cc.open`), every value unchanged. Checked on the emulator: launch, close, App Library, Spotlight, menu,
     Control Center, Notification Center; the edits moved.
   - **B3b, every animation a role** (done 2026-10-10): the 30 animations still timed in code (the cards' colour, picture,
     catch-up and final fades, the gesture pill, the status bar's hide, tone and lock fades, light and dark, the clock's
     tick and first showing, the wallpaper's fade and reveal, the Search pill, Spotlight's clear button, widgets' new
     layouts, the widget gallery's rows and previews, banners' glass, notification times, Control Center's backdrop hold,
     an app opened from a notification, the unlock's hold) are roles now, each with the curve it had: an exact bezier
     where the code's ease was one (cubic and quadratic ease-outs, smoothstep, Android's overshoot and accelerate), and
     (0.365, 0, 0.635, 1) for Android's default ease (within 0.04% of it). Six duration tokens became curves; the
     stagger delays became numbers (145 roles). Where code runs Android's own animators (their cancel and end-action
     behaviour kept), a role gives them a duration and an interpolator (`CurveTiming`, `ValueAnimator.timed`,
     `ViewPropertyAnimator.timed`): a bezier as it is, a spring sampled over its settling time; motion drawn from the
     clock reads `RoleTiming`. Every role is named, described and grouped in plain words (`motion/MotionRoles.kt`,
     12 groups; the App Switcher's geometry marked as layout behaviour, moving to the recents layout in D3); a test keeps
     the catalogue and the preset in step. Checked on the emulator: the tour of B3a, light and dark, Spotlight's clear
     button.
   - **B3c, a second preset** (done 2026-10-10): "Eased (test)" (`assets/motion/eased.json`, built on iOS 27 by
     `tools/design/build_test_presets.py`): every one of the 86 spring roles as a bezier (Material's emphasized decelerate,
     or a gentle back-out where iOS 27 overshoots; about as long as the spring takes to settle). Checked on the emulator:
     switched both ways (remembered), the tour of B3a on beziers, a launch grabbed 180 ms in and closed, plain closes
     (12 runs, both presets, all home); a recording shows the grabbed card leaving the launch without a jump (the
     emulator's recorder skips frames in the close itself: that is judged on the S24). Real presets (Material
     Expressive, One UI, snappy, calm) come with the themes (E) and the settings app's curve editor (C2).
4. Clean layout switching (teardown, rebuild, re-publish, transition), tested by switching every element back and forth.

**C. Settings app** (Compose, themed by the active theme)
1. Dashboard per element and per layer, live previews made of the real components in miniature.
2. Editors: every property named in plain words with its value and a preview of exactly what it changes; colour,
   gradient, shadow and blur pickers; the curve editor; font picker with Google Fonts.
3. First-run walkthrough (permissions, Shizuku, choosing layouts and a theme), guides with motion graphics.
4. Replaces today's "Launcher design" token editor (kept as an expert view).

**D. Elements, one per round**: D1 status bar, D2 shade, D3 recents, D4 drawer, D5 home, D6 context menu. Each round:
references (captures of iOS, Pixel, One UI; AOSP code for Pixel), the list of layouts and options agreed with Matheesha,
build, check every layout under every theme on the emulator, then on the S24.

**E. Themes**: iOS glass, Pixel (Material), One UI, the Claude look, then other modern design languages; each with
Material You colours; each checked on every element's layouts.

**F. Scripts and shader effects.**

**G. Performance pass** (frame stats on the S24 for every element and layout), then the website and release preparation.
