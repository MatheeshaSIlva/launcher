# The settings, second structure: less on screen, everything in reach (proposal, 2026-10-14)

Status: **agreed 2026-10-15** (Matheesha's answers below, under "Decided"). Matheesha (2026-10-14): "the current motion
settings looks a bit cluttered with lots of lines of settings. is there any way we make it less cluttered and reduce
cognitive load, and make every setting understandable to the user? We should rethink the settings ui and ux to make it
properly accommodate the increasing complexity of the settings. I'm fine with significant changes if it requires."

The transition styles that would give each moment a choice of choreography (and make the presets differ) are in
`docs/MOTION_STYLES.md`. This plan makes room for them.

## Why it feels cluttered (counted in the code)

- **Motion lists 142 animations.** Its 11 groups open pages of rows, all alike: Home 27, Control Center 22, Menus 18,
  Notifications 17, Apps 16, Status bar 11, Editing 11, App Switcher 9, Banners 7, Scrolling 3, Light and dark 1. Each
  row opens an editor with seven controls (Spring | Curve, a graph with handles, Duration, Bounce, shapes, Slow,
  Reset/Done).
- **The rows name the machinery, not what you see.** "Card catching up", "Picture arriving", "Banner's glass",
  "Background holding", "Arrival held" are the glue between our windows: nobody notices them on their own.
- **The values are numbers** ("Spring · 0.38 s", "Curve · 140 ms", "Spring · 0.42 s · 22 %"): meaningless before you
  open the editor, and two rows with different numbers look the same.
- **One page, three questions.** Motion asks "which preset?", "how fast and bouncy overall?" and "how does each of 142
  things move?" with no step between the second and the third.
- **The presets differ only in curves**, so choosing one changes little you can see (`docs/MOTION_STYLES.md`).
- The same pattern elsewhere: Look's Colours stacks six controls (the switch, "made from", five swatches, six styles,
  intensity, the colour editor); "Only for one part" sits second on Look though it is the rarest choice; Phone holds 25
  rows, 15 of them tools for testing the launcher.
- **A part's settings live in three rooms.** Control Center's layout is in Layouts, its own theme in Look ("Only for one
  part"), its animations in Motion (group 7 of 11). The rooms follow how the code is built (layouts, themes, motion),
  not what a person wants to change.

## What people come to the settings for

1. Make it look different: a theme, colours, a font, light or dark.
2. Make it feel different: calmer, snappier, bouncier; another way of opening apps.
3. Change one part: more icons on home, Control Center's controls, where the App Library is.
4. Fix or update: Shizuku, an update, restore the system.
5. Every value (theme and preset authors, us).

Today's rooms serve 1, 2 and 4; 3 is spread over three rooms; 5 is in front of everyone.

## Principles

1. **Three depths, the same on every page.**
   - **Pick**: whole things (a theme, a preset, a layout, a style) as cards that show themselves.
   - **Adjust**: at most five controls a page, in plain words, each played on the picture while touched.
   - **Every detail**: every value, one row at the bottom of a page ("Every detail · 6"), never in the way. Experts
     find everything there; nobody else meets it by accident.
2. **Name what you see or do.** "Opening apps", "Folders", "A little bounce". Never a part of our machinery; no units
   except under Every detail, and there with a sentence that says what the number does.
3. **One page, one question, one picture.** A page is about one thing, and the picture of home plays exactly that.
4. **Values in words, numbers small.** "Quick", "Relaxed", "Lively bounce"; "0.38 s" only beside them, in grey.
5. **Changes you can see and take back.** A dot on what differs from the theme or preset; "3 changes" at every level,
   leading to a list with Reset each; Apply and Discard as now.
6. **Find anything.** Search by name or by what it does ("bounce", "grid", "blur").
7. **Testing tools out of sight** until turned on (Android's way: tap the build number).

## The structure

Three ways to arrange it. All keep the studio (the picture of home above a sheet, tiles growing into pages), Apply,
and every component built so far.

**A. The four rooms, calmer (least change).** Layouts, Look, Motion, Phone as now, each page rebuilt on the three
depths. Motion becomes presets, Feel and moments (below). Cheapest, but a part's settings stay spread over three rooms.

**B. The whole phone, and its parts (recommended).** The start page has two tiles for the whole phone, **Look** and
**Motion**, and one tile per part, each holding everything about that part: its layout, options, its own theme if it
has one, and its moments. This is how launchers and phones arrange it (Nova: Home screen, App drawer, Folders, Look &
feel; One UI and iOS: a page per feature plus Display and Wallpaper), and the picture already zooms to a part.

**C. Simple and Advanced modes.** One short page for most people and a switch to the full studio. Two interfaces to
keep alike; people lose their place when the mode changes. Staged disclosure (B's depths) gives the same calm without
a mode.

### B in detail

```
Settings (the picture of home; search at the top of the sheet)
├─ Look ................. the whole phone: theme, colours, light & dark, fonts, size
├─ Motion ............... the whole phone: preset, Feel (speed, bounce), every moment by part
├─ Home ................. home's grid, dock, folders, widgets; its look; its moments
├─ Apps ................. opening, closing and switching apps, the App Switcher, the gestures
├─ App Library .......... where it lives, its layout, search; its look; its moments
├─ Notifications & Control Center ... the shade's layout and pulls, banners; its look; its moments
├─ Status bar ........... its layout and icons; its look; its moments
└─ Phone ................ Shizuku, updates, safety, about (Developer and Expert once turned on)
```

"Layouts" stops being a room: each element's layout, placement and options move into its part. "Only for one part"
moves into each part's Look row. The settings app's own layout and moments go under Look and Motion (it is not a part
of the phone).

**A part's page** has the same sections every time, each short:

```
[the picture zooms to the part]
Layout ......... cards, one per layout (a single line while a part has only one)
Where it lives . the pulls and swipes, shown by the finger on the picture (Layouts' hints, as now)
Options ........ its counts and switches (home's grid, the dock...)
Look ........... "Follows Look" or a theme of its own (chips)
Motion ......... its moments as rows: "Opening apps · Zoom from icon · Quick"
```

### Motion, redesigned

```
Motion
├─ Presets ......... cards, each playing a small app opening in its own way (no curve graphs)
├─ Feel ............ Speed (Relaxed ... Snappy) and Bounce (Still ... Lively), the whole preset
└─ Moments ......... by part, each a row with its style and feel in words
     └─ a moment's page (the picture plays it, again and again)
          ├─ Style ......... cards playing the choices (once a moment has more than one: docs/MOTION_STYLES.md)
          ├─ Speed, Bounce . for this moment, "As the preset" in the middle
          ├─ one character slider where it has one ("How far home steps back", "Wiggle")
          └─ Every detail · N ... its animations, each with today's editor
```

The 142 animations become 22 moments, two to six per part. Each moment's page shows two or three sliders; its
animations are one level further down. The glue (13 animations nobody sees alone: the launch colour, a late picture
fading in, a card catching up, the banner's glass...) follows its moment's feel and leaves the settings (Expert keeps
it). A role used in two places (a sheet rising in the widget gallery and in the font search) belongs to the moment where
it shows most and follows that moment's feel everywhere.

| Part | Moments (animations under Every detail) |
| --- | --- |
| Apps | Opening apps (5) · Going home (4) · Switching apps (2) · App Switcher (9) |
| Home | Pages (4) · Folders (2) · Unlocking (4) · Editing (12) · Little things (9) · A new look (4) |
| App Library | Opening and search (8) · Menus and sheets (7) |
| Notifications & Control Center | Control Center (5) · Controls (7) · Editing controls (9) · Notification Center (12) · Lock screen (4) · Banners (5) |
| Status bar | Status icons (5) · Hiding and showing (5) |
| (Look/Motion) | Scrolling (3) · The settings app (4) |

Per moment Speed and Bounce are new tokens (`motion.adjust.<moment>.speed`, `.bounce`), applied by `MotionAdjust` on
top of the preset's, so a change of feel is one value whatever the moment holds. The character sliders move existing
numbers together (home's depth: `home.content-zoom`, `home.wallpaper-zoom`, `home.depth-blur`; Unlocking's zoom;
Editing's wiggle; Clear All's rhythm).

**Every detail** first lists a moment's animations in the order they play, with what you see ("The app growing out of
its icon", "Home stepping back") and its feel in words ("Quick, a little bounce"), the editor one tap away. Later a
timeline (each animation a bar where it plays in a typical run) can replace the list for moments with many parts.

### Look, calmer

```
Look
├─ Theme ............ cards (as now)
├─ Colours .......... one choice: The theme's | The wallpaper's | Your own; the palette shown under it
│    └─ Adjust colours ... style, intensity, each palette's own colour (Every detail)
├─ Light & dark ..... Light | Dark | Automatic
├─ Fonts ............ "Inter · Inter" -> its page (as now, More Fonts)
└─ Size ............. "Follows the phone" -> its page
```

### Phone, calmer

Shizuku, Updates, Safety, About. Seven taps on the build number show Developer (today's tools, the log) and Expert (the
token editor), until turned off there.

### Search and "what you changed"

- A search field at the top of the sheet. Every setting is indexed with its words and a few more ("icons" finds the
  grid, "bounce" every Bounce); a result names its place ("Home › Options › Columns") and opens the page with the row
  lit (S3's glide and light, kept).
- "3 changes" at the top of a page leads to its changes: where, before and after, Reset each. The start page shows them
  all; a dot marks each changed row.

## Making every setting understandable: what each row must have

1. A name for what you see or do; a sentence under it saying what changes ("How quickly apps grow out of their icons").
2. A preview that plays the moment the control is touched (the picture of home, or the real component drawn small).
3. Its value in words, the number small beside it.
4. Its limits in words at the slider's ends ("Relaxed", "Snappy").
5. A dot when it differs from the theme's or preset's, and Reset.
6. Under Every detail only: spring, curve, bounce in their own words, with a line on what a spring is the first time.

## What stays

The studio (picture and sheet), tiles growing into pages, the segmented picker, pages pushed inside the sheet, the
editor and its graph, Apply and Discard, every sample built for C4, the colour editor, the font search. Most of the work
is moving them into the new pages and writing the moments.

## Steps (each checked on the emulator, then the S24)

| Step | What |
| --- | --- |
| V1 | The new start page (Look, Motion, the parts, Phone; search field shown), the parts' pages from Layouts' content and the per-part looks; Look and Phone calmer; Developer behind the build number |
| V2 | Motion as moments: the 22 moments, per-moment Speed and Bounce, character sliders, Every detail (today's rows and editor), the glue out of the settings; presets as small app openings |
| V3 | Search and "what you changed" |
| V4 | Transition styles, one moment at a time (`docs/MOTION_STYLES.md`: Opening and closing apps first) |

## To decide (Matheesha)

1. Structure **B** (Look, Motion, the parts, Phone: recommended), **A** (the four rooms, calmer) or **C** (Simple and
   Advanced)?
2. Motion as moments with Speed and Bounce each, every animation under "Every detail", the 13 glue animations out of the
   settings: yes?
3. Testing tools behind the build number (seven taps), or keep them on Phone?

## Decided (Matheesha, 2026-10-15)

1. Structure **B**: Look and Motion for the whole phone, then one tile per part, then Phone.
2. Motion as **moments**, each with a style, Speed and Bounce; every animation one level down (Every detail).
3. The 13 glue animations **leave the settings** and follow their moment (Expert keeps them).
4. Testing tools **hidden until the build number is tapped seven times**.
5. Transition styles: presets bundle styles; the first moment is opening and closing apps; build every style in
   `docs/MOTION_STYLES.md` except those that are today's (his list: opening and closing apps: container transform,
   slide up, circle reveal, fade and scale, dolly zoom, genie; banners: island morph, slide down, pop; folders: dolly
   zoom, pop out in place, sheet; home pages: parallax, cube, stack, carousel, cascade, crossfade; switching apps: shared
   axis, deck, cube, crossfade; entering the App Switcher: tilted stack, grid; a new look: circle, wipe, ink, dissolve;
   Control Center: one sheet, cascade, unfold from the finger; unlocking: ripple, cascade, focus). "The more options for
   the user, the better. This is more than enough for the first version of our app that we will ship. Afterwards, we can
   gather user feedback to add more layouts, animations and more."

Before any of it: One UI 9's breakage and the app's speed under fast use (his priority, 2026-10-15).
