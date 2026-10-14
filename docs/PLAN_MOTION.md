# The Motion page: the plan (step C4)

Status: **agreed 2026-10-14** (Matheesha: "per preset, material yes, groups as pages"). Step C4 of
`docs/PLAN_SETTINGS.md` ("Motion: preset gallery with demos, the curve editor, roles by group").

## What it must do

- Every animation of the launcher can be retimed on its own, in plain words, and is seen while it changes: "previews
  that show exactly which value changes" (the token editor, where curves are typed as numbers today, was "vague and hard
  to understand").
- Basic first: a preset and two sliders (speed, bounce) are enough for most people; every single animation is one level
  down.
- Changes wait for Apply, as everything else in the settings.
- The settings move on the same roles: a change to one of the settings' own roles changes the settings once applied.

## What there is now

- 150 motion tokens in a preset (`assets/motion/ios27.json`): 127 curves (89 springs, 38 beziers) and 23 numbers, each
  named, described and grouped in plain words in `motion/MotionRoles.kt` (12 groups).
- Two presets: iOS 27 and Eased (test: every spring as a bezier). The user's edits are already kept per preset
  (`files/design/motion-edits-<id>.json`), and `Design.previewCurve` reads any preset without applying it.
- The Motion page (S4): the presets as cards, each drawing its app-opening curve; the picture of home plays an app opening
  and closing on the chosen one. "Each animation" opens the token editor (Expert).

## The page

```
Motion
├─ Presets ............... cards side by side, each playing its own curve; the chosen one ringed;
│                          the picture above plays a short reel of the chosen preset
├─ Fine-tune ............. Speed and Bounce for the whole preset (sliders; the reel shows them at once);
│                          "3 of your own changes" with Reset all, when there are some
└─ Every animation ....... the 11 groups as rows: name, what it covers, how many you changed
     └─ a group's page ... pushed inside the sheet; the picture focuses on where the group plays
          ├─ each animation a row: its name, its curve drawn small, its value in words
          │     └─ tapped: the editor opens in place under it (one at a time); the picture plays it
          └─ the numbers that shape a motion (how far, how blurred, how long between): rows the same way, a slider
```

- A group's page slides in over Motion from the right and back (P2's push: the page beneath moves a third of the way
  and dims, `motion.sheet.push`), under the round back button; the picture zooms to the group's part on
  `motion.settings.focus`, as it does for Layouts' elements (and to a smaller part for an animation that plays in one: a
  control pressed, a status icon). C3's "customise by component" needs the same pages inside a page: built here once.
- Not on these pages: the App Switcher's seven geometry and gesture numbers (card size, hold to open, throw reach...):
  they are behaviour, and move into the Recents layout's options with D3 (in Expert until then). Slow motion
  (`motion.debug.slow`) moves to Phone > Developer as "Slow animations".
- The Motion tile's line reads "iOS 27 · 3 changes".

The groups (142 animations: 127 curves, 15 numbers):

| Group | Animations | The picture focuses on |
|---|---|---|
| Apps opening and closing | 16 | the whole phone |
| App Switcher | 9 | the whole phone |
| Home | 27 | the whole phone |
| Editing home | 11 | the page's icons |
| Menus, sheets and search | 18 | the whole phone |
| Scrolling | 3 | the list |
| Control Center | 22 | its controls |
| Notifications | 17 | the whole phone |
| Banners | 7 | the top of the screen |
| Status bar and gesture bar | 11 | the bar at the top (the bottom edge for the pill) |
| Light and dark | 1 | the whole phone |

## Presets and fine-tuning

- **Cards**: each card draws its preset's app-opening curve with a dot running along it again and again (on that
  preset's own timing), its name and where it comes from ("Measured on iOS 27"); a preset you changed says how ("Speed
  120 % · 3 changes").
- **The reel**: while Motion is open the picture plays the chosen preset across the launcher, one after another: an app
  opening and closing, a folder, a banner arriving, Control Center opening (the samples of the groups below; until those
  exist, the app opening and closing as today).
- **Speed** (50-200 %, the preset's own at 100 %): every curve's time divided by it (a spring's duration, a curve's
  length). **Bounce** (none to twice, the preset's own at 100 %): every overshoot scaled by it (a spring's bounce, a curve's
  handles past its ends); a motion that does not overshoot keeps not overshooting. Kept with the preset
  (`motion.adjust.speed`, `motion.adjust.bounce`, edited like any role) and applied where a curve is read, so every
  animation follows them, the settings' own too.
- **A second real preset, Material**: Android 16's motion (Material 3 Expressive's springs). Every role takes the spring
  of its kind (spatial: places and sizes; effects: fades, colours, blur) and its size (small: presses, toggles; default:
  menus, folders, banners; large: apps and panels), from androidx's own motion tokens; written by the builder, as Eased
  is. Eased (test) shows only with Developer on.

## The editor

It opens in place under its row (the rows below make room, the page glides to keep it in view, as the colour editor
does). From the top:

1. **What it is**: the role's description ("A banner swooping in from the top.") and where its value comes from, in
   plain words ("Measured on iOS 27", "Set by the preset", "Your change").
2. **Spring | Curve**, a segmented control. Switching keeps the feel: a spring becomes the bezier that best fits it
   (sampled until it rests), a curve the spring that best fits it; the graph morphs from one into the other.
3. **The graph**: how far the motion has gone (up) over time (across), the place it comes to rest dashed; once changed,
   the preset's own curve faint behind yours; a dot runs along it in step with the sample on the picture. The time axis
   stays put while you work (it grows smoothly when the curve outgrows it), so a longer motion looks longer.
   - Spring: a handle on the curve's first peak (or where it comes to rest, with no bounce): up and down for bounce,
     sideways for duration.
   - Curve: two handles joined to the curve's ends, as cubic-bezier editors have them: across within the motion's time,
     up and down past the ends for an overshoot or a run-up.
4. **Sliders**: a spring's **Duration** (0.1-1.5 s) and **Bounce** (from "gentler" through none to "more": SwiftUI's
   own parameters, damping = 1 − bounce, below none 1 / (1 + bounce)); a curve's **Duration** (50-1500 ms). Under them, in
   small text: "Settles in 0.61 s · goes 4 % past". A soft tick (haptic) at no bounce and at the preset's value.
5. **Shapes**, chips that set the shape and keep the duration. Spring: Smooth (no bounce), Snappy (15 %), Bouncy (30 %),
   SwiftUI's own three. Curve: Ease out, Ease in and out, Emphasized (Material's decelerate), Linear.
6. **Slow**, a chip: the sample plays at a quarter of its speed (the end of a motion is where the differences are).
7. **Reset** (back to the preset's, only when changed) and **Done**. A changed animation has a dot on its row; its
   group's row counts it.

The 15 numbers open the same way: a slider in their unit (×, %, degrees, ms, speed) within the range that still works,
Reset; the sample shows what the number shapes (icons wiggling that far, home that blurred behind a menu).

All of it moves: the editor grows open on `motion.appear`; a shape chip or Reset morphs the curve and glides the
sliders; values roll as they change; another row's editor opening closes the first on the same spring.

## What each animation plays on

The picture of home is the stage, as it is for the presets' demo and Layouts' gestures.

- **Moved by the settings, drawn by the real parts.** Each sample is a small choreography in the settings moved by the
  draft's curve (so it shows a change before Apply), drawn with the launcher's own painters and pictures: home's picture
  in layers (the wallpaper, each page's icons and widgets, the dock, where each icon is), our status bar's picture, the
  notification card's painter, Control Center's modules, the menu painter, glass from the material renderer. Never the
  phone's own notifications or another app's screen (Matheesha's messages would show, and every screenshot of the
  settings with them): notifications, banners and the App Switcher's cards are sample content ("Maya · See you at
  six"; app pages drawn in the theme's colours, as the presets' demo draws one).
- **Again and again**: the animation, a short pause, back on its partner (an app opening, then closing on the closing
  role), a longer pause. Only the animation being edited changes; the graph's dot runs during its part only. A change
  made while it plays carries on from where it is on the new curve (no jump).
- **The finger** (Layouts' soft dot with a trail) shows the gesture where one starts it: a pull, a swipe, a hold, a
  throw.

Group by group:

**Apps opening and closing**: home's picture and an app's page growing out of the dock's middle icon (today's demo).
- App opening; App closing: path, size: the icon grows into the app's page; the page goes back into it, its path and its
  size each on their own role.
- Close let go: the page pulled down a little by the finger and let go: back to the whole screen.
- Home stepping back, Home coming forward, Depth: icons, wallpaper, blur: the same opening with the page see-through,
  so home's zoom, the wallpaper's and its blur show behind it.
- Switching apps, Switch let go: the page slides out sideways under the finger as another app's slides in; a short
  swipe slides back.
- Launch colour, Picture arriving, Card into the app, Card into home, Card catching up: the card's colour turning into
  the app's launch screen; the app's picture fading in over its card; the card fading into the app at the end of an
  opening, into the icon at the end of a closing; a late card gliding from the whole screen to the finger.

**App Switcher**: sample cards over home blurred and dimmed.
- Deck coming: the finger swipes up and rests: the app's page shrinks into the deck.
- Deck scrolling: the finger throws the deck: it comes to rest on a card.
- Card opening, Deck going home: a card grows into its app; the deck leaves for home.
- Card flicked away, Gap closing: a card thrown up and off, the others closing the gap.
- Clear All pressed, Clear All: between cards, then home: Clear All pressed, the cards flying off one after another,
  home coming.

**Home**: home's picture in layers.
- Page snapping, Page fling: the finger pushes the page sideways and lets go: it settles on the next page (the dock and
  the wallpaper stay). The fling's sample is a short quick flick of a known speed (shown on the slider): it turns the
  page when the threshold is below it.
- App Library: the App Library sliding in and out (home's own picture of it).
- Folder opening, closing: a folder on the page (else one made of home's icons) opening out of its icon, and back.
- Home arriving, Wallpaper arriving, Arrival zoom (both), Arrival held: home zooming out to rest after a fresh start,
  the wallpaper settling, the screen easing into it.
- Something new, Fading in, Fading out: an app's icon growing into a free place on the page; fading in and out.
- Icon pressed (dim, in, let go): the finger on a dock icon: it darkens, and back.
- Names changing tone: icons' names (home's label painter) turning dark as the wallpaper behind them lightens, and back.
- Search into dots, Dots into Search, Search in edit mode: the Search pill turning into the page dots as the page
  moves, and back.
- Clock: the minute, Clock appearing: the clock widget's numerals changing to the next minute; fading in.
- Wallpaper appearing, crossfade, reveal: the wallpaper fading in; another wallpaper (the same, its colours turned)
  crossfading in; revealed.
- New look, New look: items: home in another theme (its last picture, kept for the theme cards) spreading over home
  from a point, each item settling as it passes.

**Editing home**: home's icons cut out of its picture.
- Edit bar: the edit bar's buttons sliding in from the top and out.
- Wiggle, Wiggle speed, Wiggle stopping: home's icons wiggling that far at that speed, then coming to rest.
- Lifting, Making room, Dropping: an icon lifted by the finger and moved one place (the others make room), dropped.
- Edit button pressed, let go: an icon's remove button pressed.
- Widget resizing, Widget's new layout: a widget on the page (else a sample one) growing a size, its new layout fading in.

**Menus, sheets and search**
- Menu opening, closing, blur behind: an icon held: it lifts, home blurs that much, the icon's menu (home's own rows,
  the menu painter) grows out of it; and back.
- Sheet, Page in a sheet: the widget gallery's sheet rising and going; a page pushed inside it.
- Settings: a tile opening, the picture focusing, the gesture shown: the settings' own, drawn small (a tile growing into
  its page; the picture zooming to the status bar and back; the finger showing a pull).
- Results crossfade: Spotlight's suggestions crossfading into results as a letter is typed.
- Index (scrolling, letter in, letter out, following): the App Library's list and its A-Z index: the finger runs along
  it, the letter bubble pops in and follows, the list glides, the bubble goes.
- Clear button in, out: a search field's text typed and cleared: its clear button popping in, shrinking away.
- Gallery rows (and between them), Gallery previews: the widget gallery's rows arriving one after another, previews
  fading in.

**Scrolling**
- Slowing down, Rubber band, Back from the edge: a list (the App Library's) thrown by the finger: it slows, runs past
  its end, stretches and comes back.

**Control Center**: Control Center as it is now (pictured by the shade, as our status bar is), its controls drawn by its
own painters with sample states.
- Opening, Settling, Stretch back, Closing, Closing choreography: the finger pulls from the top right and lets go: the
  controls settle; pulled further and let go: the stretch goes back; a swipe up: it goes.
- Control pressed, let go, Turning on or off: the finger on Wi-Fi: it presses in, turns on, and back.
- Slider following: the brightness slider moving to a new level.
- Module expanding, closing, Expanded slider: the connectivity module growing into its expanded form, its slider
  moving, and back.
- Editing, Making room, Resizing, Control added, removed, Lifting, Dropping: edit mode coming; a control resized, the
  others making room; one added, one removed; one lifted and dropped.
- Gallery opening, closing: the controls gallery rising and going.
- Background holding: the live background turning into a still picture (an app starting from a control).

**Notifications**: Notification Center with sample notifications, drawn by the notification card's painter.
- Opening, Closing: Notification Center coming down over home, and going.
- Notification arriving, leaving, List moving: a notification arriving at the top, the others moving down; one leaving.
- Swipe let go, Swiped away: a notification swiped part of the way and let go: back; further: away.
- Long look opening, closing: a notification held: its long look opening, and closing.
- Clear turning: a group's clear button turning into Clear.
- Lock screen buttons held, Flashlight lighting: the flashlight button held: it grows and turns on.
- Clock: the minute: the lock screen clock's numerals changing.
- Opening an app: a notification tapped: its app's card fading into the app.
- Time changing: "now" turning into "1m ago".
- Notification pressed, let go.

**Banners**: a sample banner over home.
- Banner in, out: swooping in from the camera, and going back up.
- Banner flung, Banner fading as flung, Banner let go: the finger throws it sideways (it fades as it goes); dragged and
  let go: back.
- Banner resizing: a second line arriving.
- Banner's glass: the app behind it changing (home to an app's page): its glass taking the new picture.

**Status bar and gesture bar**: the bar drawn with its own glyphs and font, the picture zoomed on it.
- Icon coming, going, moving: an icon arriving (the others move aside), and leaving.
- Battery level: the battery filling a little.
- Digit rolling: the time's minute rolling to the next.
- Bar hiding, showing: the bar fading as a full-screen app comes, and back.
- Bar's tone: the bar's content turning dark over a light app, and back.
- Time over the lock screen: the time fading over the lock screen, and back.
- Gesture pill hiding, showing (the picture at the bottom edge): the pill fading as a panel opens, and back.

**Light and dark**
- Light and dark: a group of rows in the theme's light colours crossfading into its dark ones (both from the theme's
  tokens), and back.

## How it is built

- **The draft**: `Draft` keeps, per preset, the animations changed (a curve or a number, or "back to the preset's").
  The rows, the graph and every sample read the draft's value (the draft's change, else the preset's with your saved
  changes: `previewCurve`), with the draft's Speed and Bounce applied. Apply writes each preset's changes to its own file
  (the active preset's through `Design.set` / `reset`, another's through a new `Design.setMotionEdits`), in one batch;
  Discard forgets them; the Apply bar counts them.
- **Design**: `motion.adjust.speed` and `motion.adjust.bounce` (factors, seeded 1 in iOS 27 by the builder), applied by
  one function where a curve is read (`Design.curve` and `previewCurve`, cached per design version), the same function
  the draft's previews use. `MotionRoles` names them (its test checks that every motion token is named).
- **Compose**: the group pages are built ahead (one a frame once Motion opens) and placed only while open; their rows
  are built two a frame near what shows (as the font search's); everything that moves (the sample, the graph's dot, a
  handle being dragged) is read in drawing, never in composition; pages leave the accessibility tree while they move or
  scroll (as every page does). The graph's handles read the finger on the window.
- **Home and the shade**: home pictured in layers on request (`HomeBridge`: the wallpaper, each page's content, the
  dock, the icons' places), Control Center pictured at rest by the shade's thread (as the status bar is); `NotifPainter`,
  `CcSurfaces` and `MenuPainter` used with sample data; one blurred copy of home's picture for the samples' glass (made
  once per picture, never per frame).
- **Fits**: a spring into a bezier and back by least squares over the sampled curves.
- **Tests**: the bounce mapping both ways; Speed and Bounce on springs and beziers; the fits stay within a few percent of
  the original; Apply writes each preset's file; every animation on these pages has a sample (a test over MotionRoles).

## Steps (each checked on the emulator, then on the S24 before the next)

| Step | What |
|---|---|
| C4a | Presets and fine-tuning: cards each playing their curve, Speed and Bounce (tokens, applied everywhere), "N changes" with Reset all, the Material preset, Eased behind Developer, Slow animations in Developer |
| C4b | Every animation: the groups, a group's page pushed inside the sheet with the picture's focus, the rows, the editor (spring and curve, the graph and its handles, sliders, shapes, Slow, Reset), changes in the draft and Apply. Samples: the Apps group on home's picture (growing today's demo); every other animation a stand-in until its group's step (a glass card doing that kind of motion where the element is) |
| C4c | Samples on home: home's picture in layers; Home, Editing home, Menus, sheets and search, Scrolling, the App Switcher, Light and dark |
| C4d | Samples on the shade: Control Center (its picture and its controls), Notifications and Banners (sample notifications), the status bar and the pill; the reel on Motion |

Measured on the S24 at each step: a group's page opening, scrolling it, an editor opening, a slider and a handle
dragged, samples playing (0-2 missed refreshes an action, as the settings' bench).

## To decide (Matheesha)

1. **Speed and Bounce kept with each preset** (switching presets shows that preset's own), or one Speed and one Bounce
   for whichever preset is chosen? Recommended: with each preset, as every other change of yours is.
2. **Material as the second real preset**, Eased only for testing? Any other you want, for example one with almost no
   motion (short fades)?
3. **Groups as rows that open their own page** (recommended: room for each group's description and count), or a row of
   group chips at the top of Motion, one level, like Layouts' picker?

Later, not in C4: reduced motion (iOS's Reduce Motion: zooms become fades) is behaviour in every element, not a curve;
search across the settings (with C3's); sharing presets.

## C4a, presets and fine-tuning (2026-10-14, emulator; the S24 not yet)

- **Speed and Bounce** (`motion.adjust.speed`, `motion.adjust.bounce`, factors kept with each preset as its edits; seeded
  1 in iOS 27): `motion/MotionAdjust` divides every curve's time by Speed and scales every overshoot by Bounce (a spring's
  through its damping: the overshoot of a spring from rest is exp(−ζπ/√(1−ζ²)); a bezier's handles past its ends), so a
  motion that does not overshoot never gets one. Applied once where code reads a curve (`Design.curve`, cached per design
  version; the token editor still shows and edits a role's own curve) and in previews (`Design.previewCurve`). The times
  a motion is staged by (Clear All's cards one after another, home after them, the widget gallery's rows) follow Speed and
  slow motion through `Motion.ms`. Checked: a launch's animation 788-902 ms at 100 %, 616-724 ms at 200 % on the emulator
  (it includes waiting for the app's first frame).
- **Material** (`assets/motion/material.json`, `tools/design/build_material_preset.py`): Material 3 Expressive's springs
  (androidx's `ExpressiveMotionTokens`, v0_14_0) by each role's kind and size; 113 roles, 24 of them bouncing. A role
  bounces only where iOS 27's does visibly (damping 0.86 or less, or a bezier past its ends): code that never saw an
  overshoot is never handed one (a fade drawn with an int alpha flashes past 255; an edge-anchored panel would show a gap
  past its rest), the others take their size's stiffness critically damped. Fourteen timing curves that hand one picture
  over to another stay iOS 27's. A test checks the rule (`MotionTest.materialBouncesOnlyWhereIos27Does`).
- **The page**: the presets as cards (the default first, test presets last; two share the row, more scroll sideways with
  the next peeking in), each drawing the curve a banner drops in on with a dot running along it on its own timing, one
  time axis for all (it glides to fit the slowest). Changed from the plan's app-opening curve: iOS 27's app opening
  overshoots 0.03 %, so Bounce changed nothing anyone could see; the banner's goes 9 % past (twice that at 200 %). The
  card's second line says what you changed ("Speed 145 %", "3 changes") or what the preset is. The picture's demo plays an
  app opening and closing, then a banner dropping in out of the camera and going (a stand-in in the theme's colours until
  C4d's real banner). Fine-tune: Speed (50-200 %, the preset's own in the middle of the slider, halving and doubling
  evenly) and Bounce (none to 200 %), and Reset all (growing in when the preset differs from how it comes: its animations,
  speed and bounce). Everything waits for Apply (`Draft.motionEdits` per preset, `motionCleared`); Apply writes each
  preset's changes to its own file (`Design.setMotionEdit`, `clearMotionEdits`) before the choice of preset.
- **Developer** (Phone): "Test animation presets" (Eased shows in Motion; `SettingsPrefs`), "Slow animations" (Off, 2×, 4×,
  10×: `motion.debug.slow` of the preset in use, at once).
- Checked on the emulator: the cards and dots, Speed 145 % (the curve rising sooner, the card's line, the Apply bar), a
  preset chosen with another's change waiting, Apply (iOS 27's speed saved in its own file while Material became active),
  Reset all and Apply (iOS 27's file gone), Bounce 200 % (the hump twice as high), the Developer switch and slow motion,
  and a tour on Material with Bounce 200 % (launch, close, Control Center, a banner: no errors).
- **On the S24** (fd7c240, dark mode, wallpaper colours; frame stats of what the screen showed, Perfetto): Motion opens
  with 0 missed refreshes (GPU 5.4 ms a frame); at rest it draws a steady 120 frames a second (959 in 8 s, no long work on
  our threads; once a single 36 ms frame in ten one-second samples, not seen again in the trace); dragging Speed, tapping
  a card and scrolling 0 missed. Speed reaches real animations: the page's close drew 73-78 frames at 100 %, 43-47 at
  200 %. Material: Control Center, Notification Center and banners 0-1 missed (3 runs), App Library launches and closes 0
  (3 + 3). At 200 % the settings' page transitions missed more (closes 1-2, two of six openings 8-10; at 100 % 0-1 in all
  twelve): the late frames are a slide's first ~100 ms, where the settings lay out (2-4 ms) and record (5-8 ms) in every
  frame, the Phone page too (untouched by C4a: 13 late frames over three close/open pairs, Motion 8). Compose takes a moving
  layer for a layout change and runs every position callback (each glass button and chip) in each frame; a quicker spring
  puts more of the motion into those frames. To be fixed with C4b, which pushes more pages inside the sheet.
- Found on the S24 and fixed: under wallpaper colours in dark mode the Apply button's white label sat on the palette's
  light primary; the settings' accent label is the palette's on-primary there (`comp.settings.accent-label` in the
  theme's Material You section), checked on the phone.
- For C4b: our `Spring` treats any damping above 1 as 1 (Compose's springs do not): the editor's "gentler than no bounce"
  needs the overdamped case in `Spring` first.
