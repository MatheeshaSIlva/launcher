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

**Step 1: the design-system core** (no visible change). A `design/` package: tokens for colour (light/dark), materials,
shape and spacing, type, motion roles; how points become pixels (today 1 pt = width / 402; the policy becomes a token so
Android's display size and font size can be honoured later); the iOS 27 profile as data with provenance; unit tests.

**Step 2: one material system.** One material spec (backdrop source: wallpaper, picture of what is behind, or live; blur;
lens; fills with blend modes; rims; shadows) and one renderer replacing the four. Surfaces move over one per issue:
2a Notification Center (platters, collapsed stack, buttons, clock), 2b Control Center (controls, expanded modules,
gallery), 2c banners and the long look, 2d home (dock, folders, widgets, menus, Spotlight, App Library). This is where
"different blur levels, different tints" ends: one value per material, from the kit.

**Step 3: components.** Platter, control tile (sizes and states), slider, pill and round buttons, sheet, menu, badge,
status row, clock numerals: specs plus painters. The shade's views shrink to layout and touch.

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
- Open: the theme file's syntax (step 5).
