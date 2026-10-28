# Audit B: controls (switches, sliders, segmented controls, steppers, progress)

Date: 2026-10-28. Scope: the iOS 27 theme and the Pixel (Android 17, Material 3 Expressive) theme, and what the theme
engine needs to draw both. Read-only audit: no device was used, nothing but this file was changed.

**Summary.** Our switches and sliders are flat drawings with one look (iOS-like) whatever the theme: the iOS 26/27 Liquid
Glass behaviour (the knob lifting into a magnifying glass lens, stretching while dragged) is missing everywhere, and the
Pixel theme still draws iOS's green switch and round-knob slider in the settings app. Control Center's big sliders and
Android 17's brightness slider are close; the rest needs new component tokens, behaviour flags, motion roles of their own
and one engine feature (a lens that magnifies).

Confidence keys used below: **kit** (a primary source: Apple's kit or docs, AOSP / androidx / MDC source, the project's
own reading of the emulator's APK), **3rd** (a third-party source), **judged** (ours), **capture** (to capture in the
Simulator, the emulator, or on a real iPhone where the Simulator cannot show it: haptics).

---

## 1. Reference against ours, per control and theme

Ratings: **ok** (matches), **off** (close but differs, or a literal where a token belongs), **wrong** (another look or
behaviour), **missing** (not there). Each non-ok row is a gap, numbered for section 4.

### 1.1 Switch (the settings app's `Toggle`, `settings/Pages.kt:3231-3256`)

Ours: a capsule `comp.settings.toggle.width/height` (aliases of `component.toggle.*`: 64 x 28 pt in iOS 27, Pixel inherits
it), track mixed off→on, a knob 60 % of the width less an 8 % inset, a black 12 % shadow (literal), sliding on
`motion.cc.toggle` (Control Center's role). The whole row toggles on tap (`ToggleRow`, `BaseRow` click); the knob cannot
be dragged; no press state; no haptic.

**iOS 27 reference** (iOS 26 introduced it, iOS 27 keeps it; Liquid Glass):
- Layout: capsule track **63 x 28 pt** on iOS 26+ (was 51 x 31) (3rd: React Native's iOS 26 patch; capture against
  Apple's iOS 27 kit). Knob a white horizontal capsule inset in the track (size capture; roughly 37 x 24 pt).
- Look: on = green (`systemGreen` #34C759 / #30D158 dark; HIG: green "tends to work well", the app's accent allowed);
  off = a grey fill (capture which kit fill).
- States and interaction: "control thumbs, like those on switch and segmentedControl, automatically adopt the glass
  look during interactions" (kit: WWDC25 284). On touch-down the knob lifts into Liquid Glass, larger than the track,
  refracting what is under it (3rd: Macworld on the iOS 26 betas); the knob can be dragged across and follows the finger;
  a tap makes it jump across in its glass form; on release it settles back to the opaque white knob. Under Reduce Motion
  the material's elastic properties are off (kit: WWDC25 219). Sizes, lens scale and timing: capture.
- Row: in a list row the switch is the control; tapping the row's label does not toggle it (capture: UITableView cell
  with an accessory switch is not selectable; SwiftUI `Toggle` in a `Form`).
- Haptics: capture on a real iPhone (the Simulator plays none).

| # | Aspect | Reference | Ours | Rating |
|---|---|---|---|---|
| S1 | Size | 63 x 28 (3rd, capture) | 64 x 28 (`component.toggle.width` judged) | off |
| S2 | Knob shape and inset | white capsule, size capture | 0.6 w, 8 % inset, literals (`Pages.kt:3248-3251`) | off |
| S3 | Press | knob lifts into a glass lens, grows | nothing | missing |
| S4 | Drag | knob follows the finger, value decided on release | no drag; the row toggles on tap | missing |
| S5 | Lens | glass knob refracts / magnifies the track under it | flat knob; the engine's `Lens` cannot magnify (`design/Token.kt:144-157`) | missing |
| S6 | Motion | own spring (capture), interruptible | `motion.cc.toggle` (Control Center's "Turning on or off": retiming CC retimes every settings switch) | off |
| S7 | Colours | green on, grey off, white knob | `accents.green`, `fill.primary`, `#fff` | ok |
| S8 | Shadow | kit shadow (capture) | literal black 12 %, offset 6 % of the knob (`Pages.kt:3254`) | off |
| S9 | Row tap | label does not toggle (capture) | whole row toggles | wrong (capture) |
| S10 | Haptic | capture on iPhone | none | missing (capture) |
| S11 | Disabled | system dimming (capture) | per-call alpha 0.45 on `cc.toggle` (`Pages.kt:1761`) | off |

**Pixel (Android 17 / M3 Expressive) reference** (kit: androidx `material3/tokens/SwitchTokens.kt`, `Switch.kt`;
MDC `materialswitch/res/values/tokens.xml` v34):
- Track **52 x 32 dp**, full corners, **2 dp outline** when off (`Outline`), off track `SurfaceContainerHighest`, on
  track `Primary`.
- Handle (thumb): **16 dp off**, **24 dp on** (and 24 dp off when it carries an icon), **28 dp pressed**; colours off
  `Outline`, on `OnPrimary`; pressed on `PrimaryContainer`, pressed off `OnSurfaceVariant`. While pressed the handle
  snaps (no animation) to 28 dp and 2 dp toward its edge (`Switch.kt` ThumbNode: `SnapSpec` while pressed).
- Icon in the handle: 16 dp, on `OnPrimaryContainer`, off `SurfaceContainerHighest` (optional in M3; whether Android
  17's Settings shows a check: capture in the emulator's Settings app).
- Motion: handle offset and size on `MotionSchemeKeyTokens.FastSpatial` (expressive: stiffness 800, damping 0.6 → our
  spring [0.2221 s, 0.6]); colours change at once in Compose (capture what Settings, a View switch, does).
- Disabled: track 12 %, off handle 38 %, icons 38 %.
- Row: a preference row toggles on a tap anywhere on it (capture).
- Haptics: Android 14+ has `TOGGLE_ON` / `TOGGLE_OFF` haptic constants; whether Settings plays them: capture.

| # | Aspect | Reference | Ours (Pixel theme) | Rating |
|---|---|---|---|---|
| P1 | Size and shape | 52 x 32, outline 2 | iOS 64 x 28, no outline (pixel.json sets no `component.toggle.*`) | wrong |
| P2 | Handle sizes by state | 16 / 24 / 28 | one knob size | missing |
| P3 | Colours | Primary / OnPrimary / SurfaceContainerHighest / Outline | iOS green / white / grey | wrong |
| P4 | Icon in handle | optional 16 dp (capture Settings) | none | missing (capture) |
| P5 | Press | snap to 28 dp, 2 dp toward the edge | nothing | missing |
| P6 | Motion | FastSpatial [0.2221, 0.6] | `cc.toggle` (material preset: effects spring [0.1571, 1]) | off |
| P7 | Haptic | TOGGLE_ON/OFF (capture) | none | missing (capture) |
| P8 | Row tap | toggles | toggles | ok |

### 1.2 Linear slider (settings: `SettingsSlider` `Pages.kt:2028-2081`, used by `SliderRow` `:2432`, `FeelSlider` `MotionPages.kt:144`, `EditorSlider` `MotionPages.kt:892`; `ColourSlider` `Pages.kt:1897`)

Ours: a 4 pt track in `comp.settings.chip.fill`, the level in `comp.settings.accent`, a 26 pt round knob in
`toggle.knob-color` with a literal black 12 % shadow; a drag puts the knob under the finger (absolute), a tap jumps there,
the shown value glides on `motion.cc.level`. ColourSlider: a 10 pt gradient track, a ring knob. No haptics, no press state.

**iOS 27 reference** (kit: WWDC25 284 "Build a UIKit app with the new design"; HIG Sliders):
- The thumb gets the glass look during interaction; sliders "preserve momentum and stretch when moved" (kit: WWDC25 284).
- New in iOS 26: `neutralValue` (the fill runs from a neutral point, not the minimum), ticks (`TrackConfiguration`,
  `allowsTickValuesOnly`), a thumbless style (`sliderStyle = .thumbless`) for progress-like use (kit: WWDC25 284; SwiftUI
  `Slider(... neutralValue:, enabledBounds:, tick:)`).
- Fill from the minimum (or neutral) to the thumb in the tint; optional min/max icons at the ends (kit: HIG Sliders).
- Geometry (track height, thumb shape: a capsule on iOS 26 as far as screenshots show), whether a tap on the track moves
  the thumb, haptics at ticks or ends: capture.

| # | Aspect | Reference | Ours | Rating |
|---|---|---|---|---|
| L1 | Track and thumb geometry | capture (capsule thumb) | 4 pt track, 26 pt circle, literals (`Pages.kt:2051, 2073`) | off (capture) |
| L2 | Thumb lifts into glass, magnifies | yes | flat knob | missing |
| L3 | Momentum and stretch | thumb keeps momentum, stretches | none | missing |
| L4 | Tap on track | capture | jumps | off (capture) |
| L5 | Neutral value, ticks | supported | none; Speed / Bounce (1 = the preset's) would read better from a neutral 1x | missing |
| L6 | Haptics (ticks, ends) | capture on iPhone | none | missing (capture) |
| L7 | Literals | tokens | track, knob radius, shadow are literals; reads chip and toggle tokens, not a slider's | off |
| L8 | Colours | tint fill, grey track | accent fill, chip fill | ok |

**Pixel reference** (kit: androidx `tokens/SliderTokens.kt`; MDC `slider/res/values/tokens.xml` v34 and `styles.xml`;
`Slider.kt` Thumb):
- Handle a vertical bar **4 dp wide**, **44 dp tall** (XS, S, M), 68 (L), 108 (XL); **2 dp while pressed or dragged**
  (`PressedHandleWidth`; Compose's Thumb halves the width at once, no animation).
- **6 dp gap** between handle and track on each side (`ActiveHandleLeadingSpace/TrailingSpace`).
- Track height by size: **XS 16**, S 24, M 40, L 56, XL 96 dp; outer corner 8, 8, 12, 16, 28 dp; inside corners
  (beside the handle) **2 dp**; inset icon 24 dp (M, L), 32 dp (XL).
- **Stop indicator** dot **4 dp**, 6 dp from the end (`StopIndicatorSize`, `StopIndicatorTrailingSpace`).
- Colours: active track and handle `Primary`, inactive track and stop `SecondaryContainer`; value label
  `InverseSurface` / `InverseOnSurface` (discrete sliders). Disabled: active 38 %, inactive 12 %, handle 38 %.
- A tap jumps the value there.

| # | Aspect | Reference | Ours (Pixel theme) | Rating |
|---|---|---|---|---|
| M1 | Shape | bar handle, 6 dp gaps, 16 dp track, 8/2 dp corners | iOS track and round knob | wrong |
| M2 | Handle narrowing on press | 4 → 2 dp | none | missing |
| M3 | Stop indicator | 4 dp dot | none | missing |
| M4 | Colours | Primary / SecondaryContainer | accent / chip fill (iOS fills unless Material You remaps them) | off |
| M5 | Tap | jumps | jumps | ok |

### 1.3 Control Center's level sliders (module: `shade/cc/ControlGridBlock.kt` `slide` `:1432`, `endSlider` `:1447`, `placementOf` `:555-556`; drawing `shade/CcParts.kt:16-30`; expanded: `shade/CcExpanded.kt` `slide` `:945`, player bars `:634-661`)

**iOS 27 reference**: 70 x 155 pt modules, corner 34, the level filling from the bottom with the kit's "on" fill, the
symbol 27 pt (kit: `docs/IOS27_KIT.md`, Apple's iOS 27 kit 2570:20682). The drag is relative (the level moves with the
finger from where it was). Pulled past either end, the module stretches along the drag and narrows a little across
(rubber band), springing back on release (capture: the squash amount, whether the module swells while held; we have
measured only the stretch's existence). Haptic at the ends: capture on iPhone. The expanded module's large slider and the
expanded player's bars behave the same way; the player's progress can be scrubbed and its bars thicken while touched
(capture).

| # | Aspect | Reference | Ours | Rating |
|---|---|---|---|---|
| C1 | Module geometry, fill, symbol crossing | kit | `component.slider.corner` 34, `cover` 0.8 | ok |
| C2 | Relative drag | yes | yes (`sliderFrom - dy/len`) | ok |
| C3 | Overpull | stretch along, squash across (capture) | taller only (`h += st`), no squash | off |
| C4 | Stretch constants | tokens | literals `len * 0.6f, 0.4f` (`ControlGridBlock.kt:1439`) | off |
| C5 | Stretch-back role | own role | `motion.cc.stretch-back`, described and filed as the panel's overpull (moment Control Center, `Moments.kt:89-91`) | off |
| C6 | Haptic at an end | capture | none | missing (capture) |
| C7 | Expanded slider overpull | stretches | clamps (`CcExpanded.kt:947`) | missing |
| C8 | Expanded slider end haptic | capture | none (brightness, volume) | missing (capture) |
| C9 | Player progress | scrubbable | not touchable (no part in `partAt`) | missing |
| C10 | Player bars while touched | thicken (capture) | fixed 6 / 8 pt | missing (capture) |
| C11 | Player bars' geometry | tokens | literals `pt(6f)`, `pt(8f)`, `pt(128f)`, `pt(278f)`, absolute volume jump (`CcExpanded.kt:636, 655, 976`) | off |
| C12 | Module scrubber | moves smoothly | redrawn every 250 ms (`ControlGridBlock.kt:927`): a 4 px line stepping | off |

Pixel theme: Control Center under the Pixel theme keeps iOS's structure (layouts and themes are separate); its corner is
a pill (`component.slider.corner` 36, judged from PlatformSlider). Nothing more to rate.

### 1.4 Android 17's brightness slider (`shade/qs/BrightnessBlock.kt`)

Reference: the project's own reading of SystemUIGoogle in the Android 17 emulator's APK (kit: `docs/SHADE_PIXEL_EVALUATION.md`
§ stage 4): 40 dp tracks either side of a 4 x 52 dp handle, 6 dp gaps, 12 dp outer and 2 dp inner corners, the symbol
(low / medium / full) at the inactive end or the active end, crossing on a 100 ms tween, ticks while dragged.
M3's default Thumb narrows the handle to half while pressed (kit: `Slider.kt`); M3's default track draws a stop dot.
Older AOSP (Android 15's compose `BrightnessSlider.kt`) follows the system's value with `animateFloatAsState` defaults
(spring stiffness 1500, damping 1 → [0.1622 s, 1]).

| # | Aspect | Reference | Ours | Rating |
|---|---|---|---|---|
| Q1 | Geometry, colours, symbol | APK | as read | ok |
| Q2 | Handle while dragged | M3 default: 4 → 2 dp (capture whether SystemUI keeps the default thumb) | 4 dp always | missing (capture) |
| Q3 | Stop dot | M3 default 4 dp; step 1 notes a 4 dp stop dot (capture on 17) | none | missing (capture) |
| Q4 | Following the system | default spring [0.1622, 1] (Android 15 source; capture 17) | `motion.cc.level` (iOS's [0.45, 1]) | off |
| Q5 | Haptics | SliderHapticFeedbackProvider | ticks every 2 %, firmer at ends | ok (approximation) |
| Q6 | Tokens | a theme restyles every slider at once | `comp.qs.brightness.*` are plain values in ios27.json, no `component.*` alias: a theme cannot restyle sliders together | off |

### 1.5 Segmented control

Ours: the settings define `comp.settings.segment.{track,thumb,height,text,symbol,corner}` (`Pages.kt:133-138`, ios27.json)
but nothing draws them (the settings use chips: `Pages.kt:2363`). Home's menus have a choice row (`components/Menu.kt:241`):
the chosen choice on a capsule of `Appearance.pressFill` x 2.2 alpha (not a token), gliding to a tap, no drag.

Reference iOS 27: capsule track (tertiary fill), selected thumb (kit `segmented-control-selected-fill`: white / white 27 %
dark), the thumb draggable and lifting into glass while touched (kit: WWDC25 284). Pixel: M3 Expressive has no segmented
control in Android 17's shade; the closest is the connected button group (capture which Android 17 Settings uses).

| # | Aspect | Rating |
|---|---|---|
| G1 | `comp.settings.segment.*` defined, never drawn (dead tokens) | off |
| G2 | Menu's choice row: thumb not a token, no drag, no glass lift (iOS) | off / missing |
| G3 | No M3 variant for the Pixel theme | missing |

### 1.6 Stepper (`settings/Pages.kt:2608-2660`)

Ours: iOS's capsule, 94 x 32 pt (literals), minus and plus halves, a hairline, the pressed half lit, an end that cannot go
further fading on `cc.toggle`. Reference iOS: `UIStepper` repeats while held (`autorepeat`, on by default: kit, UIKit
docs); iOS 26's size and shape: capture. Android has no stepper; for Pixel the counterpart is two tonal icon buttons
(judged).

| # | Aspect | Rating |
|---|---|---|
| T1 | Geometry literals, no `component.stepper.*` | off |
| T2 | No hold-to-repeat | missing |
| T3 | No M3 variant | missing |

### 1.7 Progress

Pixel: the media card's travelling wave (`shade/qs/MediaBlock.kt`, `comp.qs.media.*`) is in place: ok. iOS: see C9-C12.
There is no shared `component.progress.*`: each player draws its own line.

### 1.8 Engine

| # | Gap | Rating |
|---|---|---|
| E1 | `Lens` (refraction, depth, dispersion, splay, light) has no magnification: iOS's slider and switch lenses enlarge what is under them | missing |
| E2 | The catalogue (`design/Components.kt`) has no states (pressed, disabled), no behaviour flags, no stepper, segmented or progress; `SLIDER` mixes the CC module (corner, cover) with nothing for linear sliders | missing |
| E3 | No motion roles of their own: every switch, slider, chip, check and stepper in the settings runs on `motion.cc.toggle` / `motion.cc.level` | off |

**Gap count: 51** (S 10, P 7, L 7, M 4, C 10, Q 4, G 3, T 3, E 3; 12 of them rated "(capture)": the reference must be
captured before the fix).

---

## 2. Proposed tokens and flags

Conventions: the shared style is `component.<name>.*` (the catalogue); each element reads its own namespace as an alias
(`comp.settings.toggle.*`, `comp.cc.level.*`, `comp.qs.brightness.*`, `comp.home.menu.choice-*`); a theme overrides
`component.*` (never the alias, per the pitfall "A theme's Material You values go on the token another theme restyles").
Flags are tokens that pick a behaviour: strings (`{"value": "..."}`) and numbers (`{"factor": n}`), read through `Design`.
Every value carries its `src`. In iOS 27's column "capture" means: seed a judged value now (given), replace it from the
Simulator or Apple's kit.

The existing `component.toggle.*` is the switch (the brief's `component.switch.*`): kept by name, extended. The existing
`component.slider.{corner,cover,expanded-corner}` describe Control Center's level module: moved to a new
`component.level.*`, and `component.slider.*` becomes the linear slider (migration in step 1 of section 3).

### 2.1 `component.toggle.*` (switch)

| Token | Kind | iOS 27 | Pixel | Sources |
|---|---|---|---|---|
| `style` | flag | `"ios-glass"` | `"m3"` | kit WWDC25 284 / kit SwitchTokens |
| `width`, `height` | pt | 63 x 28 (capture) | 52 x 32 | 3rd RN patch / kit `TrackWidth/Height` |
| `knob-width`, `knob-height` | pt | 37 x 24 (judged, capture) | 16 x 16 | / kit `UnselectedHandleWidth` |
| `knob-on-width`, `knob-on-height` | pt | = knob | 24 x 24 | kit `SelectedHandleWidth` |
| `knob-pressed` | pt | = knob (the lens grows instead) | 28 | kit `PressedHandleWidth` |
| `press-offset` | pt | 0 | 2 | kit `Switch.kt` (TrackOutlineWidth toward the edge) |
| `outline-width`, `outline-color` | pt / colour | 0 | 2, `@outline` | kit `TrackOutlineWidth`, `UnselectedTrackOutlineColor` |
| `on-color` | colour | `ref.color.accents.green` | `@primary` | kit HIG / kit `SelectedTrackColor` |
| `off-color` | colour | `sys.color.fill.primary` (capture) | `@surface_container_highest` | judged / kit |
| `knob-color` (on), `knob-off-color` | colour | `#ffffff`, `#ffffff` | `@on_primary`, `@outline` | judged / kit |
| `knob-pressed-color`, `knob-off-pressed-color` | colour | = knob | `@primary_container`, `@on_surface_variant` | kit |
| `knob-shadow` | shadow list | kit (capture), replaces the literal | none | capture / kit (no elevation) |
| `thumb-icon` | flag | `"none"` | `"check"` (capture) | / M3 allows; Settings capture |
| `icon-size`, `icon-color`, `icon-off-color` | pt / colour | n/a | 16, `@on_primary_container`, `@surface_container_highest` | kit |
| `thumb-lens` | flag (bool) | `true` | `false` | kit WWDC25 284 |
| `lens-material` | material | `sys.material.glass.small` (kit 10472:45029; capture) | n/a | |
| `lens-scale` | factor | knob growth while held (capture; seed 1.35) | 1 | capture |
| `lens-zoom` | factor | magnification inside the lens (capture; seed 1.15) | 1 | capture |
| `stretch` | factor | knob elongation per unit of drag speed (capture; seed 0.15) | 0 | capture |
| `draggable` | flag | `true` | `true` (capture Settings) | |
| `row-tap` | flag | `false` (capture) | `true` | capture / judged |
| `haptic` | flag | `"none"` (capture on iPhone) | `"toggle"` (TOGGLE_ON/OFF, capture) | |
| `disabled-track-opacity`, `disabled-knob-opacity` | factor | capture (seed 0.5, 0.5) | 0.12, 0.38 | kit SwitchTokens |

Aliases: `comp.settings.toggle.*` for every new property (as today's five).

### 2.2 `component.slider.*` (linear slider: the settings, Android's brightness, the expanded player's volume)

| Token | Kind | iOS 27 | Pixel | Sources |
|---|---|---|---|---|
| `style` | flag | `"ios-glass"` | `"m3-expressive"` | kit WWDC25 284 / kit MDC styles |
| `size` | flag | n/a | `"xs"` (settings), `"brightness"` (Android's own 40/52) | kit |
| `track-height` | pt | 6 (judged, capture) | 16 | kit `InactiveTrackHeight` |
| `corner` | pt | half the track | 8 | kit `xsmall_active_track_shape_leading` |
| `inside-corner` | pt | n/a | 2 | kit MDC `trackInsideCornerSize` |
| `thumb-width`, `thumb-height` | pt | 37 x 24 capsule (judged, capture) | 4 x 44 | kit `HandleWidth/Height` |
| `thumb-pressed-width` | pt | = thumb | 2 | kit `PressedHandleWidth` |
| `gap` | pt | 0 | 6 | kit `ActiveHandleLeadingSpace` |
| `stop-size`, `stop-inset` | pt | 0 | 4, 6 | kit `StopIndicatorSize/TrailingSpace` |
| `fill-color`, `track-color`, `thumb-color`, `stop-color` | colour | `sys.color.accent`, `ref.color.fills.tertiary`, `#ffffff`, n/a | `@primary`, `@secondary_container`, `@primary`, `@secondary_container` | judged / kit |
| `thumb-shadow` | shadow list | capture | none | |
| `thumb-lens`, `lens-material`, `lens-scale`, `lens-zoom` | as switch | `true`, glass small, capture | `false` | kit WWDC25 284 |
| `stretch` | factor | momentum stretch (capture; seed 0.15) | 0 | kit WWDC25 284 ("stretch when moved") |
| `tap-jumps` | flag | capture (seed `false`) | `true` | / kit M3 |
| `haptic` | flag `"none"` / `"ends"` / `"ticks-and-ends"` | capture | `"ticks-and-ends"` | / AOSP SliderHapticFeedbackProvider |
| `disabled-opacity` | factor | capture | 0.38 (active), 0.12 (inactive) | kit |

Aliases: `comp.settings.slider.*` (new: the settings stop borrowing chip and toggle tokens), `comp.qs.brightness.*`
(today's plain values become aliases where they are the component's; the APK-read 40/52/12 stay `comp.qs.*` values,
since they are Android's brightness size, not the component's default), `comp.cc.expanded.volume-*`.

### 2.3 `component.level.*` (Control Center's thick level module, module and expanded)

| Token | Kind | iOS 27 | Pixel theme | Sources |
|---|---|---|---|---|
| `corner`, `cover`, `expanded-corner` | moved from `component.slider.*` | 34, 0.8, 46 | 36, (inherits), 40 | kit 2570:20682 / judged |
| `stretch-reach` | factor of the length | 0.6 (as in code, judged; capture) | same | judged |
| `stretch-strength` | factor | 0.4 (as in code, judged; capture) | same | judged |
| `squash` | factor | width lost per unit of stretch (capture; seed 0.1) | same | capture |
| `haptic` | flag `"none"` / `"ends"` | capture (seed `"ends"`) | `"ends"` | capture |
| `bar-height`, `bar-height-active` | pt | 6 / 8 today, active capture | same | judged / capture |

Aliases: `comp.cc.slider.*` (today's two plus the new ones), `comp.cc.expanded.slider-*`.

### 2.4 `component.stepper.*`, `component.segmented.*`, `component.progress.*`

| Token | iOS 27 | Pixel | Sources |
|---|---|---|---|
| `stepper.style` | `"ios-capsule"` | `"m3-buttons"` (two tonal icon buttons, judged) | judged |
| `stepper.width`, `.height`, `.corner` | 94, 32, 16 (judged, capture iOS 26) | 40 dp buttons, full corner (judged) | |
| `stepper.fill`, `.separator`, `.symbol-color`, `.press` | chip fill, separator, label, press fill | `@secondary_container`, none, `@on_secondary_container` | judged |
| `stepper.autorepeat` | `true` (interval capture; seed 0.5 s then 0.1 s) | `false` | kit UIKit `autorepeat` default |
| `segmented.style` | `"ios-glass"` | `"m3-connected"` (capture) | kit WWDC25 284 |
| `segmented.height`, `.corner`, `.track`, `.thumb`, `.text`, `.symbol` | today's `comp.settings.segment.*` values move here | M3 40 dp (judged) | kit / judged |
| `segmented.thumb-lens`, `.draggable` | `true`, `true` | `false`, `false` | kit WWDC25 284 |
| `progress.style` | `"line"` | `"wave"` | judged / kit (Android's media) |
| `progress.height`, `.height-active`, `.track`, `.fill` | 6 / capture / media track / media progress | from `comp.qs.media.*` | |

Aliases: `comp.settings.segment.*` (already named, now drawn), `comp.home.menu.choice-*` (replacing `Appearance.pressFill`),
`comp.settings.stepper.*`, `comp.cc.media.progress-*`, `comp.qs.media.*` (wave).

### 2.5 Motion roles

New roles (named in `motion/MotionRoles.kt`, a test checks that each is in exactly one moment), values per preset:

| Role | ios27 preset | material preset | Moment | Sources |
|---|---|---|---|---|
| `motion.toggle.slide` (knob travel) | spring [0.32, 0.9] seed (= today), capture | [0.2221, 0.6] FastSpatial | new "Switches and sliders" | capture / kit `Switch.kt` + `ExpressiveMotionTokens` |
| `motion.toggle.colour` (track colour) | capture | [0.1019, 1] fast effects (capture Settings) | same (glue) | |
| `motion.toggle.lens-in`, `.lens-out` | capture (seed [0.22, 1], [0.38, 0.7]) | snap / [0.2221, 0.6] | same | capture / kit (pressed: SnapSpec) |
| `motion.slider.level` (a tap's or the system's glide) | [0.45, 1] (= `cc.level`) | [0.3223, 1] | same | judged |
| `motion.slider.lens-in`, `.lens-out`, `.stretch-back` | capture | snap (thumb width, no animation) | same | kit `Slider.kt` Thumb |
| `motion.segmented.thumb` | capture | [0.2221, 0.6] | same | |
| `motion.cc.slider-stretch-back` (split from `cc.stretch-back`) | [0.38, 0.7] (= today) | [0.3223, 0.8] | Controls (`Moments.CONTROLS`) | judged |
| `motion.qs.slider-level` | [0.1622, 1] | [0.1622, 1] | Quick Settings (glue) | kit AOSP `BrightnessSlider.kt` (Android 15; capture 17) |

`motion.cc.stretch-back` stays the panel's overpull (its description already says so). The new moment
"Switches and sliders" (part EVERYWHERE, Speed and Bounce of its own) is a user-visible change to the settings: **ask
the owner** (alternative: put the roles into the existing "Settings app" moment).

### 2.6 Engine additions

- `Lens.zoom` (magnification at the lens's centre, 1 = none), parsed and written in `design/Theme.kt` (`"zoom"`), a
  uniform in `MaterialPainter`'s shader, an editor row in `DesignActivity` (with refraction). Needed by `thumb-lens`.
- Flag tokens read as strings (`Design.text`-like `Design.flag(key)`) if no string token kind exists yet; the theme check
  (refuse a token the code reads that is missing or of another kind) covers them.
- Catalogue states: a `ComponentProp` per state colour (pressed, disabled), as above, so the token editor lists them.

---

## 3. Who would read them, and the steps

Readers today and tomorrow:

| Control | Drawn in | Reads after the change |
|---|---|---|
| Switch | `settings/Pages.kt` `Toggle` (`:3238`), `ToggleRow` (`:3231`) | `comp.settings.toggle.*`, `motion.toggle.*` |
| Linear slider | `Pages.kt` `SettingsSlider` (`:2028`), `ColourSlider` (`:1897`), through `SliderRow`, `MotionPages.kt` `FeelSlider` / `EditorSlider` / `CurveGraph` knob (`:922`) | `comp.settings.slider.*`, `motion.slider.*` |
| Android's brightness | `shade/qs/BrightnessBlock.kt` | `comp.qs.brightness.*` (aliases), `motion.qs.slider-level` |
| CC level module | `shade/cc/ControlGridBlock.kt` (`slide`, `endSlider`, `placementOf`), `shade/CcParts.kt` `CcSlider.draw` | `comp.cc.slider.*` → `component.level.*`, `motion.cc.slider-stretch-back` |
| CC expanded slider, player | `shade/CcExpanded.kt` (`drawSlider`, `slide`, player bars, `volumeAt`) | `comp.cc.expanded.*`, `component.level.*`, `component.progress.*` |
| Segmented | `components/Menu.kt` `drawChoices`; settings (the dead `comp.settings.segment.*`) | `component.segmented.*` via aliases |
| Stepper | `Pages.kt` `Stepper` (`:2621`) | `comp.settings.stepper.*` |

Steps (each one concern, about 200 lines; fidelity track for anything that must match a reference: capture first):

0. **Captures** (no code): iOS 27 Simulator: a Settings switch at rest, pressed, dragged, tapped (frame times); a slider
   (Display brightness in Settings) at rest, held, dragged fast, released; Control Center's brightness overpulled at both
   ends (width and length over time); the expanded player's bars touched; a segmented control dragged; a stepper held.
   Apple's iOS 27 kit: switch, slider, stepper, segmented components (sizes, fills, shadows). Android 17 emulator: the
   Settings app's switch (icon in thumb? colours, press) and a Settings slider; the brightness slider held (handle width,
   stop dot). Real iPhone (owner): which of these play haptics.
1. **Catalogue and tokens**: `Components.kt` (TOGGLE extended, LEVEL new with the three moved keys, SLIDER redefined,
   STEPPER, SEGMENTED, PROGRESS); seeds in `tools/design/build_ios27_theme.py` and `build_pixel_theme.py`; aliases
   re-pointed (`comp.cc.slider.*` → `component.level.*`). Migrate user edits on the old keys (`files/design/user.json`,
   per-theme edits) at load. Tests: the theme check, `TokenParts` namespaces. No drawing change yet.
2. **Motion roles and the moment** (after the owner's answer): `MotionRoles.kt`, `MotionTokens.kt`, `Moments.kt`,
   presets (`assets/motion/ios27.json`, `material.json`; `eased` through `tools/design/build_test_presets.py`); the
   settings' controls switch from `cc.toggle` / `cc.level` to their roles.
3. **`Lens.zoom`** in `Token.kt`, `Theme.kt`, `MaterialPainter` (uniform, set only when changed), `DesignActivity`; warm
   the pipeline in `GpuWarmUp` / `MotionWarmUp`.
4. **Switch, M3 path**: one `ThemeSwitch` composable (settings) reading `style`: sizes per state, outline, icon, press
   snap and offset, `motion.toggle.slide`, `row-tap`, `haptic`. Pixel's look complete without glass.
5. **Switch, iOS path**: knob drag (follows the finger, decides on release by position and speed), lens on press. The
   lens is drawn in the paint form (`MaterialPainter.draw` over a small backdrop image of the track itself, redrawn when
   the track's colour changes), never `drawLive`: the settings' rows sit inside layers and clips (pitfall "Glass over live
   content"). Elastic stretch off under Reduce Motion (Android's animator duration scale 0 / remove animations).
6. **Linear slider**: one `ThemeSlider` for `SettingsSlider`, `ColourSlider` (its gradient a track override),
   `EditorSlider`; the M3 path from `BrightnessBlock`'s geometry (gap, inside corners, narrowing, stop dot); the iOS path
   with lens, momentum (release velocity into `motion.slider.level`) and stretch; `neutral` as a parameter (Speed and
   Bounce from 1x).
7. **Android's brightness**: `BrightnessBlock` reads the aliases, the handle narrows while held and the stop dot shows if
   step 0 confirms them, `motion.qs.slider-level`.
8. **CC level**: `stretch-reach`, `stretch-strength`, `squash` tokens in `ControlGridBlock.slide` / `placementOf`, the
   same stretch in `CcExpanded.slide`, end haptics, `motion.cc.slider-stretch-back`.
9. **Expanded player bars**: tokens instead of literals, the progress scrubbable, bars growing while touched, the
   volume bar relative; the module's scrubber redrawn per frame while visible and playing (or interpolated).
10. **Segmented and stepper**: `Menu.drawChoices` on `component.segmented.*` (draggable thumb, lens for iOS), the
    settings' `comp.settings.segment.*` either used or removed; `Stepper` on `component.stepper.*` with autorepeat and
    the M3 variant.

Each step: polish pass (`.claude/skills/polish-pass`), every state and its interruption (a tap mid-slide, a drag
reversed, the theme or palette changing while a knob is held), light and dark, the theme's colours and the wallpaper's;
Compose rules from CLAUDE.md (no moving value read in composition; press effects in drawing, not as a layer; leave the
semantics tree while a lens or stretch moves).

---

## 4. Bug proposals (backlog format)

- [B-new] P2, settings (Pixel theme), the settings' switches stay iOS's (64 x 28, green, white knob) under the Pixel theme: pixel.json sets no `component.toggle.*` and the switch has no M3 form (52 x 32, outline, 16/24/28 dp handle, Primary). Repro: Look, choose Pixel, Apply, open System: compare with the emulator's Settings. Status: open
- [B-new] P2, settings (iOS 27), the switch has no Liquid Glass behaviour: no press state, the knob cannot be dragged, no glass lens; it slides on Control Center's `cc.toggle` role. Repro: press and hold a switch on System's page: nothing changes until release. Status: open (capture the reference first)
- [B-new] P3, settings, tapping a switch row's label toggles it; iOS toggles only on the switch (to capture). Repro: tap the words "Developer tools" on System's Developer page. Status: open (capture)
- [B-new] P2, settings (both themes), the settings' sliders draw one iOS-like look (4 pt track, round 26 pt knob, literals) for every theme: no M3 Expressive form (bar handle, 6 dp gaps, stop dot, narrowing on press) and no iOS 27 glass thumb, momentum or stretch. Repro: Motion, any moment, drag Speed under iOS 27 and under Pixel. Status: open
- [B-new] P3, shade (iOS 27), Control Center's sliders stretch past their ends only in length (no squash across) with literal constants, and the stretch's spring is the panel's `cc.stretch-back`. Repro: open Control Center, drag Brightness up past full. Status: open (capture the squash)
- [B-new] P3, shade (iOS 27), the expanded Brightness and Volume sliders clamp at their ends instead of stretching, and give no haptic at an end. Repro: hold Brightness to expand it, drag past the top. Status: open
- [B-new] P3, shade (iOS 27), the expanded player's progress cannot be scrubbed, its bars do not grow while touched, and its sizes are literals (6, 8, 128, 278 pt). Repro: play music, hold the player in Control Center, drag along the progress. Status: open (capture)
- [B-new] P3, shade (Pixel), the brightness slider's handle keeps 4 dp while dragged and shows no stop dot (M3's defaults: 2 dp, a 4 dp dot), and it follows the system on iOS's `cc.level` spring instead of Android's (stiffness 1500). Repro: open Android's shade, hold the slider. Status: open (capture on the Android 17 emulator)
- [B-new] P4, settings, `comp.settings.segment.*` tokens are defined in ios27.json and `SettingsTokens` but nothing draws them; home menus' choice row draws its thumb from `Appearance.pressFill` (not a token) with no drag. Repro: grep `SEGMENT_`; long-press a widget with sizes. Status: open
- [B-new] P4, settings, the stepper is drawn from literals (94 x 32 pt) with no component tokens, no hold-to-repeat (UIStepper's default) and no M3 form. Repro: Home's page, hold the plus of Columns: it adds one. Status: open
- [B-new] P3, design engine, glass lenses cannot magnify (`Lens` has no zoom), which iOS 27's switch, slider and segmented thumbs need. Repro: read `design/Token.kt` Lens. Status: open

---

## Sources

- Apple, WWDC25 219 "Meet Liquid Glass": https://developer.apple.com/videos/play/wwdc2025/219/ (lift into glass on
  interaction, gel-like flexibility, elasticity off under Reduce Motion).
- Apple, WWDC25 284 "Build a UIKit app with the new design": https://developer.apple.com/videos/play/wwdc2025/284/ (switch
  and segmented thumbs adopt glass during interaction; slider thumb glass, momentum and stretch; neutral value, ticks,
  thumbless).
- Apple, WWDC25 356 "Get to know the new design system": https://developer.apple.com/videos/play/wwdc2025/356/ (capsule
  shapes, mirrored proportions of sliders and switches).
- Apple HIG Toggles and Sliders (JSON of the pages): https://developer.apple.com/tutorials/data/design/human-interface-guidelines/toggles.json,
  https://developer.apple.com/tutorials/data/design/human-interface-guidelines/sliders.json
- Apple Developer Forums on `neutralValue`: https://developer.apple.com/forums/thread/788239
- iOS 26 switch size 63 x 28 (3rd, React Native patch): https://gist.github.com/scarlac/3c29b40484ae629d1cdf86e721c71c63
- Macworld on the iOS 26 glass toggle (3rd): https://www.macworld.com/article/2891233/this-liquid-glass-toggle-is-a-window-into-apples-broken-design-process.html
- androidx Compose Material 3: `tokens/SwitchTokens.kt`, `tokens/SliderTokens.kt`, `Switch.kt`, `Slider.kt`,
  `MotionScheme.kt`, `tokens/ExpressiveMotionTokens.kt` (branch androidx-main, read 2026-10-28):
  https://github.com/androidx/androidx/tree/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3
- Material Components for Android v34 tokens: `slider/res/values/tokens.xml`, `slider/res/values/styles.xml`,
  `materialswitch/res/values/tokens.xml`: https://github.com/material-components/material-components-android
- AOSP SystemUI `brightness/ui/compose/BrightnessSlider.kt` (the aosp-mirror copy, an Android 15 version):
  https://github.com/aosp-mirror/platform_frameworks_base
- The project's own: `docs/IOS27_KIT.md` (Apple's iOS 27 kit), `docs/SHADE_PIXEL_EVALUATION.md` (Android 17 APK reading).
- m3.material.io's spec pages render only in a browser (no text for a fetch): the numbers above are from the token source
  files instead.
