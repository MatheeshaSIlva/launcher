# The shade: can our engine turn the iOS shade into Pixel's? (2026-10-19)

Matheesha (2026-10-19): "let's start with the shade before the status bar, because I want to see how the theming engine
works. I want you to evaluate how easy or difficult it was with the current foundational steps we've taken for developing
new layouts (the users should be able to make their own layouts). Check whether we have all the necessary tools and settings
to transform our available shade into the Pixel shade, not just copy and paste available open source code. Then compare
what additional settings we need to implement to be able to transform the current layout into the Pixel shade layout
accurately."

The same as a page, with the captures side by side and the comparison filterable by kind:
https://claude.ai/artifact/S62u7QfkrVHeyuzFFGGKVL

So the test is not "can we build a Pixel shade" (the first Pixel run did that with its own panel, `docs/PIXEL_PROFILE.md`,
withdrawn) but "can our own shade become Pixel's through the engine's settings": the theme for the look, the layout's
options for the structure, the animation preset for the motion. Where it cannot, what setting is missing. AOSP is the
reference for numbers and behaviour, as Apple's kit was for iOS; no code is taken from it.

## In short

- **The look: mostly there.** A theme file already reaches every colour (Material You's roles by name), surface (solid,
  blurred, glass), font (Google fonts), size and corner of the shade. Missing for Pixel: corners that change with a
  control's state (a pill when off, 24 dp when on), the shape of the box behind a control's symbol (always a circle today),
  cells that are not square, and the symbols themselves (ours are iOS-style; Pixel draws Material Symbols).
- **The structure: mostly not.** The shade's structure is code, written for iOS: two panels split at the camera, a cover
  sheet of stacked platters, a free-placement grid of iOS modules. Its layout has one placement and one option (Control
  Center's columns). Pixel's one panel with a quick row that grows into the full grid, the paged tile grid, the horizontal
  brightness slider, sections of joined notification cards, the footers and the big-clock header have no setting that
  makes them: each needs a new option, or a new building block.
- **The motion: half there.** The preset can retime and reshape every animation the shade has. Pixel's own (the quick row
  growing into the grid, corners morphing as a tile turns on, the clock growing in the header, neighbouring tiles giving way
  to a press) are motions our shade does not have yet.
- **For layouts people make themselves:** the theme and motion layers are ready (data files, a builder, settings, search,
  changes). The layout layer is not: a layout is code plus a few options. The plan already names the missing piece
  ("custom layouts: a declarative description (zones, grids, stacks)"); this test lists the blocks and options that
  description needs, with Pixel and iOS as its first two layouts.

## How the engine works today

| Layer | Where it lives | What it controls in the shade |
| --- | --- | --- |
| Theme (the look) | `assets/themes/*.json`, data, `"extends"`, Material You roles, the builder, the token editor | Every colour, material (glass, frost, solid, strokes, gradients), font, text size, padding, corner, icon size: `comp.nc.*` (51), `comp.cc.*` (78 + one accent per control), `comp.banner.*`, the shared components (`component.notification`, `control`, `slider`, `panel`...) |
| Motion preset | `assets/motion/*.json`, data | Every animation by role (springs, curves), per moment Speed and Bounce |
| Layout (the structure) | Code (`shade/*.kt`) plus `Layouts.IOS_SHADE` | One placement ("split-top": left of the camera Notification Center, right Control Center), one option (Control Center's columns, 4-6). Everything else structural is code: the two panels and how each opens, the cover sheet with the clock and the torch and camera buttons, iOS's stacks of platters, the free-placement grid of iOS modules (`CcLayout`, sizes per control in `Controls.kt`), the gallery and the in-place editor |

## The Pixel shade (the reference)

Captured on the Android 17 emulator's own SystemUI (Material 3 Expressive, our bar and shade off for the captures) and
read from AOSP's main branch (`packages/SystemUI`: `qs/panels/ui/compose`, `shade/ui/composable`, `res/values/dimens.xml`,
`config.xml`). Pixel phones run this SystemUI with Google's additions; the shade is the same in all that matters here.

- **First pull** (anywhere along the top): the header (time, date; status icons), the quick row (the first two rows of the
  tile grid: `quick_qs_panel_max_rows` 2), then the notifications over the blurred, veiled screen: cards with 28 dp corners
  (`notification_corner_radius`), 16 dp from the sides (`notification_side_paddings`), cards of a group or section joined
  with 4 dp corners between them (`notification_corner_radius_small`), a "Silent" section with its own heading and a clear
  button, and a footer row: history, a wide "Clear all", notification settings.
- **Second pull** (or pulling on, or two fingers): Quick Settings. A large clock with the date and the carrier; the
  brightness slider (64 dp tall, a pill; the level's end rounds less while dragged; its symbol inside the track; a 4 dp stop
  dot); the tile grid: 4 columns (`quick_settings_infinite_grid_num_columns`), 8 dp gaps (`qs_tile_margin_*`), tiles 72 dp
  tall (`CommonTileDefaults.TileHeight`), small tiles one column (symbol only, 32 dp), large tiles two columns (a 56 dp
  symbol box, the name in labelLarge, the state in bodyMedium), 4 rows a page (`quick_settings_paginated_grid_num_rows`)
  with page dots; the build number, the edit button; a footer: active apps, user, settings, power (primary).
- **Tiles' look**: off, a pill (50 dp corners) in surfaceVariant with onSurfaceVariant; on, a 24 dp rounded rectangle in
  primary with onPrimary (`ActiveTileCornerRadius`), the corners animating between them; a large tile's symbol box primary
  when on, surfaceContainerHighest when off (16 dp corners when on); unavailable tiles in surface, faded.
- **Editing**: a page of its own ("Edit tiles"): tiles selected to move, resized with a handle on the selected tile's side;
  the tiles not used listed by category below.
- **Separate mode**: AOSP also has a "dual shade" (notifications pulled from the left of the top, Quick Settings from the
  right: `NotificationsShadeOverlay`, `QuickSettingsShadeOverlay`), our split's arrangement.
- **Motion**: the quick row's tiles grow into the full grid as one (shared elements), the clock grows into the header, a
  tile's corners and colours animate with its state, pressed tiles push their neighbours slightly (Expressive "bounce").

## Part by part: today, and what is missing

Kinds of answer: **Theme** (a token exists: a theme file does it), **New token** (a look value the code does not read
yet), **New option** (a structural choice for the layout, a small piece of code), **New block** (a part the shade does not
have; code, and a parameter set a layout file would fill).

### Panels and how they open

| Pixel | Our shade today | What it takes |
| --- | --- | --- |
| One panel, pulled from anywhere along the top | Two panels, split at the camera | **New option** (placement "pull anywhere") and **new block**: a panel that holds both the quick row and the notifications |
| Two stages: quick row, then the full grid (pull on, second pull, two fingers) | None | **New block**: the quick row growing into the grid, its tiles moving as one (with the motion below) |
| Separate mode (left notifications, right Quick Settings) | This is our split | Placement exists |
| The screen behind blurred and veiled | Panel material and live blur | **Theme** |

### The header

| Pixel | Our shade today | What it takes |
| --- | --- | --- |
| First pull: time and date at the top left, status icons at the right | Our status bar stays in the shade; no date beside the time | **New option** of the status bar layout (date beside the time) or a shade header block |
| Full grid: a large clock, the date, the carrier, status icons | Control Center has a status row; Notification Center the lock-screen clock | **New block**: an expanded header (clock size, date, carrier as options) |

### The tile grid

| Pixel | Our shade today | What it takes |
| --- | --- | --- |
| 4 columns | Control Center's columns, 4-6 | **Option exists** |
| 72 dp rows, 8 dp gaps, cells wider than tall | Square cells (one `comp.cc.grid.cell`), gap token | **New token**: the cell's height apart from its width (**done in step 2**: `comp.cc.grid.cell-height`, and `comp.cc.grid.side` for cells that fill the width) |
| Two sizes only: one column (symbol), two columns (symbol box and words) | Sizes per control, in code (connectivity 2x2, media 2x2 and 4x2, sliders 1x2 and 1x3...) | **New option**: the sizes a layout allows (others shown at the nearest allowed) |
| Tiles flow in order, no empty cells | Free placement with empty cells (iOS 18); a flowing pass exists for landscape (`CcLayout.flowed`) | **New option**: arrangement "free" or "in order" |
| Pages of 4 rows with dots, swiped sideways | One page | **New block**: pages and their dots |
| The quick row: the first two rows, shown at the first pull | None | Part of the two-stage panel |

### Tiles

| Pixel | Our shade today | What it takes |
| --- | --- | --- |
| Off: a pill; on: 24 dp corners; animated | One corner for every state | **New token** (the corner when on) and a motion role (**done in step 2**, on the toggle's own animation: no new role) |
| Large tile: a 56 dp symbol box (16 dp corners when on), name and state beside it | The wide control: a circle behind the symbol (always a circle), name and state beside it | **New token**: the symbol box's corner (off and on; **done in step 2**); sizes and fonts are **Theme** |
| Small tile: the symbol alone, 32 dp | A one-cell control with its symbol | **Theme** |
| On: primary for every tile; off: surfaceVariant | A material for off and on, a colour per control | **Theme** (every accent set to `@primary`) |
| Unavailable: faded, in surface | App tiles know it (AppTiles); no look for it | **New token** (the unavailable look) |
| Two targets: the symbol box toggles, the rest opens details | A tap toggles, a long press opens the expanded module | **New option** (what a tap on the words does) |
| Material Symbols | Our own symbols, iOS-style, for every control | **New token** for the theme: a symbol set (and the set itself: Material Symbols are Apache-2.0) |
| A press pushes the neighbours | A press sinks the control | **New motion** (a role and its code) |

### The brightness slider

| Pixel | Our shade today | What it takes |
| --- | --- | --- |
| Full width over the grid, 64 dp, horizontal, its symbol inside the track, the level's end rounding less while dragged | Brightness and volume as vertical modules in the grid (1x2, 1x3), filled from the bottom | **New option**: the slider's direction and a full-width size; **new tokens**: its height, the dragging corner, the stop dot |

### Notifications

| Pixel | Our shade today | What it takes |
| --- | --- | --- |
| Cards with 28 dp corners, 16 dp from the sides | Platter corner and list margin tokens | **Theme** |
| Groups and sections as joined cards (4 dp between them), a group opened by its chevron | iOS's stacks (shelves under the front platter, a collapsed stack with "+N from App") | **New option**: groups stacked or joined; **new token**: the corner between joined cards |
| Sections by importance ("Silent" with its own heading and clear button) | By app, newest first | **New option**: sections |
| A card: the app's small symbol in a coloured circle, a header line "App · 19h" with an expand chevron, then the title and text | iOS's: the app's icon, the title with the time at its right, the text | **New options** for the card: where the time goes, which icon (app icon or small symbol in a circle), the chevron |
| The list at the top, under the quick row; it scrolls | Gathered at the bottom, opening as one collapsed stack (iOS 27) | **New options**: where the list sits, whether it opens collapsed |
| Swipe either way dismisses | A swipe left reveals Options and Clear; a long swipe clears | **New option** |
| A footer: history, Clear all, settings | None (the cover sheet's torch and camera instead) | **New block**: a row of buttons a layout lists |

### Quick Settings' own parts

| Pixel | Our shade today | What it takes |
| --- | --- | --- |
| Footer: active apps, user, settings, power | "+" and power at the top of Control Center | **New block**: a button row (which buttons, where) |
| Build number, page dots, edit button under the grid | None | Part of the pages block and the button row |
| Editing on a page of its own (select, move, resize by a handle, tiles by category) | iOS's: controls wiggle in place, the gallery sheet adds | **New block** (or an option between the two editors) |
| The media player as a card with the cover behind its controls | Now Playing module (Control Center) and the lock-screen player (Notification Center) | **New option** for the media card's look and place |

### Motion

| Pixel | Our shade today | What it takes |
| --- | --- | --- |
| The panel follows the finger and settles on a spring | Roles for Control Center's and Notification Center's opening | **Theme** of motion (the preset) |
| The quick row grows into the grid as one | None | **New motion** with the two-stage block |
| Haptics: MSDL tokens by interaction (a switch on, off, a long press), one feel per action (added 2026-10-19: Matheesha on the S24, "the animations and haptics are not the same") | Fixed Android constants in code (CLOCK_TICK, LONG_PRESS), the same in every theme and preset | **New layer**: haptics as roles a motion preset sets (Pixel's from MSDL's compositions) |
| Corners and colours animate with a tile's state | Colours do (the toggle role) | **New role** with the new corner token (**done in step 2** without one: the corner follows the toggle's animation) |
| The clock grows into the expanded header | None | **New motion** with the header block |

## How easy or hard: the verdict

- **The look: easy.** A Pixel theme for the shade can be written today without code: Material You's roles by name (primary,
  surfaceVariant, surfaceContainerHighest...), solid surfaces, the blurred veil behind, 28 dp cards, 72 dp cells' sizes,
  Google fonts. Four small additions make it accurate: the corner when on, the symbol box's corner, the cell's height, the
  symbol set.
- **The structure: hard.** Our layout layer is the iOS shade's code with two settings. Making Pixel's structure by settings
  needs about a dozen new options (among them the horizontal slider, joined notification sections, the card's parts, the
  tile sizes and their arrangement) and five new blocks (the two-stage panel with its quick row, the expanded header, pages,
  button rows under the notifications and the grid, the editor page).
- **The motion: medium.** The preset retimes everything that exists; Pixel's own transitions come with the blocks.

The foundation that is ready: the theme and motion layers, the component catalogue (a layout reads "control",
"notification card", "slider", never iOS's tokens), the settings app that turns options into controls, the setup file
(per element: layout, placement, options). What is missing for layouts people make: the structure as data.

## What it would take: a shade made of blocks

Instead of a Pixel shade beside the iOS one (the first run's way), the shade becomes a set of blocks with parameters, and a
layout is a file that arranges them, as a theme is a file of tokens:

- **Panels and gestures**: one panel or two; where each is pulled from; one stage or two (quick row, then full).
- **Header**: none, the status bar's row, or an expanded header (clock size, date, carrier, icons).
- **Tile grid**: columns, cell height, the sizes allowed, free or in order, pages and dots, how many rows in the quick row.
- **Sliders**: where (in the grid or over it), direction, size.
- **Notification list**: where it sits, groups stacked or joined, sections, the card's anatomy (icon kind, time place,
  chevron), the swipe, opening collapsed or not.
- **Button rows**: which buttons (history, Clear all, settings, edit, power, user...), where (under the list, under the grid,
  at the top).
- **Media**: its card, its place.
- **Editor**: in place (iOS) or on a page (Pixel).

Each block reads the look through the component tokens it already has and moves through motion roles, so any theme and any
preset work with any layout file. iOS 27's shade becomes the first file (nothing visible changes), Pixel's the second, and
a builder (later) lets people write their own.

## Proposed order (each step checked on the emulator, then the S24)

1. **The look first, no code**: a Pixel theme for the shade as it is, to see the theme engine at work and what it leaves
   (iOS's structure in Pixel's look). A day.
2. **The four look additions**: corner when on, symbol box corner, cell height, symbol set (with Material Symbols). Done
   but the symbol set (below).
3. **The shade as blocks**: the iOS shade rebuilt from blocks and described by a layout file, with nothing changing on
   screen (checked frame by frame against today's).
4. **Pixel as a layout file**: the missing blocks and options added one by one, each compared with the captures and AOSP's
   numbers, until the file reproduces Pixel's shade.
5. **Later**: the layout builder in the settings, so the same file format is open to everyone.

## Step 1 done: the theme alone (2026-10-19)

Matheesha: "yes, go with the blocks approach, start step 1". Built without a line of code: `assets/themes/pixel.json`
(150 tokens, written by `tools/design/build_pixel_theme.py` with each value's source: AOSP's numbers, the captures, or a
judgement where Pixel has nothing like an iOS part), on iOS 27's, its colours the phone's palette by role.

**What the theme reached** (emulator, light and dark): tiles that are off as light (dark, in dark) pills over the blur,
lit tiles and the sliders' level in the palette's primary, every symbol in onSurfaceVariant, a large tile's symbol in a
circle of surfaceContainerHighest; notification cards opaque in surfaceContainerHigh with 28 dp corners, 16 dp from the
sides, a 40 dp icon, text from 72 dp, 16/14/12 sp type; groups as one card (no shelves); banners as opaque cards (no live
blur); Material's round buttons; Google Sans Flex everywhere (downloaded at first use); the wallpaper's colours.

**What the engine could not do, found on the way** (besides the gaps listed above):
- **Control Center's backdrop only darkens.** Its material's fills are reduced to one dimming amount when the picture
  behind is baked (`Shade.ccDim`): a light veil, Pixel's in light mode, is dropped, so the blurred screen shows at full
  colour behind the tiles (dark mode is right: its veil darkens). Needs code: the backdrop drawn with its fills' colours.
  (Done in step 2.)
- **Notification Center's veil lies only under the list.** The cover sheet keeps the wallpaper and the lock-screen clock;
  Pixel's veil over the whole panel is structure (step 4) and code.
- **iOS 27's Material You section sets some `comp.*` tokens itself** (Control Center's glass, its wells, its veil, its
  buttons and gallery, Notification Center's count, the banners' plain colour). With the wallpaper's colours on (Pixel
  turns them on) those hid the `component.*` values a theme built on iOS 27 gives; Control Center's tiles stayed iOS glass
  until Pixel pointed those tokens back at the components. Better: iOS 27's builder writes its Material You at the
  component level, so a theme built on it needs no pointing back. (Done in step 2.)

**Confirmed on screen**: lit tiles keep the pill (no corner when on), cells are square, the modules are iOS's (2x2
connectivity and media, vertical sliders), and none of Pixel's structure is there (header, quick row, pages, footers).

**To see it on the phone**: Settings › Notifications & Control Center › Look › Pixel, then Apply (only the shade takes
it). Or Look › Pixel for the whole phone: so far only the shade is Pixel's; the rest takes its font and the wallpaper's
colours.

**Step 2 grows by two**: the four look additions (the corner when on, the symbol box's corner, the cell's height, a symbol
set), plus Control Center's backdrop drawn with its veil's colours, and iOS 27's Material You moved to the component level.

## Step 2: the look additions (2026-10-19)

Matheesha, with the S24's screenshots of step 1: "continue with the next step". Each addition is a token with iOS 27's value
chosen so that nothing changes there, and Pixel's value from AOSP:

- **A tile's corner, off and on** (`component.control.row-corner`, `row-on-corner`, read as `comp.cc.module.row-corner`,
  `row-on-corner`; iOS 27: round both ways). It follows the tile as it lights, on the toggle's own animation (no new motion
  role): Pixel's capsule (`InactiveCornerRadius` 50 dp, at most half the height) turns into a 24 dp rounded square
  (`ActiveTileCornerRadius`). Modules larger than a tile keep `component.control.corner` (Pixel: `qs_corner_radius` 28 dp).
- **The symbol box's corner, off and on** (`component.control.well-corner`, `well-on-corner`; iOS 27: a circle): Pixel's 56
  dp box is a circle off and 16 dp on (`ActiveIconCornerRadius`).
- **The cell's height apart from its width** (`comp.cc.grid.cell-height`; iOS 27: the cell's width) and **cells that fill
  the width** a margin in from each side (`comp.cc.grid.side`; iOS 27: 0, cells of their own width, centred): Pixel's four
  tiles across between 16 dp margins (`qs_horizontal_margin`), 72 dp tall. Landscape keeps the cell's own width.
- **Control Center's backdrop drawn with its veil's colours** (`Shade.ccVeil`, was `ccDim`): the background material's
  plain fills composited as they are, a fill of another blend by how much it darkens (a plain veil cannot tint as a colour
  blend does, and the live blur is another window's), into one colour over the blurred picture or the live blur; the status
  bar's content turns black over a light veil. iOS 27's black 50 % is exactly the dim it was.
- **iOS 27's Material You at the component level**: its tinted glass for the controls, their wells and the veil is written
  on `component.control.material`, `component.control.well-material` and `component.panel.material` (it was on `comp.cc.*`,
  which hid Pixel's components whenever the wallpaper's colours were on); Pixel's three pointers back are gone.

**Checked on the emulator**:
- iOS 27 against the committed build, Control Center opened by a real pull over the same home: with the theme's colours the
  same picture (background to the unit, the controls alike); with the wallpaper's colours the background is a touch lighter
  (0.39 of the wallpaper's brightness against 0.33) and tinted: the veil now shows the wash iOS 27's Material You gives it
  (the palette's dark secondary tone at 30 %), which the old dim turned into plain black. The controls are unchanged.
- Pixel on the shade alone, wallpaper's colours, light and dark: tiles across the width, 72 dp tall, capsules off and
  rounded squares on, the symbol box a circle off and a rounded square on, the shape changing with the colour as a tile turns
  on (8x slow motion); the light veil with black time and icons in light mode, white over the dark veil; edit mode's empty
  slots, badges and handles on the new grid.
- 129 unit tests. Not yet on the S24: the light veil over One UI's live blur (drawn over Samsung's view blur as the dim was).

**The symbol set is waiting**: its code is small (a token naming the set, each control's symbol looked up in it); the
symbols are the question. AOSP SystemUI's own Quick Settings icons are exactly what a Pixel shows; Material Symbols Rounded
are the family they come from; both are Apache-2.0, and either means downloading about 30 to 40 small vector files.

**On the S24** (Matheesha, with screenshots in light and dark): the veil, the status bar's colours, the shapes and the
symbol boxes came through; "the animations and haptics are not the same" as AOSP's (motion is step 4's with the blocks;
haptics were in no layer: added above as a gap). "We will provide different icon sets, it is also required for the status
bar themes as well": the symbol set below is built as icon sets any element can read.

### The symbol set, as icon sets (2026-10-19)

- **Icon sets** are data, as themes are (`assets/icons/<id>.json`, `files/icons/` through the inbox): each symbol vector
  paths with its source, drawn in place of the app's own wherever a theme names the set (`sys.symbols`, read through each
  element's own token: `comp.cc.symbols`, `comp.nc.symbols`, `comp.banner.symbols`). A name a set leaves out is drawn
  with the app's own; `NAME.off` is a control's look while off, crossfaded as it turns on.
- **The app's own symbols turned out to be Material Symbols Rounded already**, so the set that makes Pixel's look is
  AOSP's own: `pixel`, 35 symbols (11 with their off look) from SystemUI's Quick Settings icons, as each tile names them
  (`qs/tiles/*.java`): Wi-Fi's wedge, Bluetooth, airplane, DND's minus, Battery Saver, data saver, hotspot, location (a
  crossed pin when off), flashlight (an outline when off), auto-rotate, dark theme, colour inversion and correction,
  extra dim, cast, NFC, notes, QR, font size, wallet, alarm, live caption, hearing devices, the settings gear, brightness,
  media volume (Pixel's music note), the ringer, the media buttons. Calculator, timer, camera and the others Android has no
  tile for keep the app's own. Animated icons are taken at their last frame (what SystemUI shows at rest).
- **Built by `tools/icons/build_icon_set.py`** from Android vector drawables (plain or animated: groups' transforms,
  motion paths, several animation targets per group, trims, clip paths) and SVGs; reviewed on the preview page
  (`PreviewActivity --es icons pixel`: each symbol beside the app's own and its off look).
- **The Pixel theme names the set**; the settings choose one for the whole phone (Look › More › Symbols: Launcher or
  Pixel), so theme and symbols combine freely (iOS 27's glass with Pixel's symbols was checked on the emulator).
- **What Pixel animates, we crossfade**: its icons morph between off and on (animated vectors); ours fade one into the other
  on the toggle's curve. The morphs are motion's (step 4).

## Step 3: the shade as blocks (started 2026-10-20)

Matheesha: "continue" (to the proposal: four stages, each changing nothing on screen, checked against the build before it).

**Stage 1, the layout file and its loader: done (2026-10-20).** The shade's layout is a file now:
`assets/layouts/shade/ios.json` (format and every key: docs/LAYOUT_FILES.md), read strictly by `layout/LayoutFile` (a typo
or an unknown value refuses the file with its reason) into `layout/ShadeStructure`, at app start before the setup
(`Layouts.init`; log "[layouts] files: shade/ios"). What the code declared before (`Layouts.IOS_SHADE`: id, name, place,
the columns option) moved into the file, unchanged. The file names the panels, where each is pulled from and the blocks
each is made of; the shade reads what its panels already did in more than one way: how Notification Center opens
(`opens`: stacked or list), how it lies in landscape (`clock-beside-list` or `as-portrait`), where Control Center's column
count comes from (an option or a number), and which panel each side of the camera opens. The rest is checked to be today's
and refused otherwise ("so far"). A copy of your own through the inbox wins (`files/layouts/shade/ios.json`; one that
does not load is refused, the shipped one used), reloaded at once.

Checked on the emulator: the shipped file against the build before it, Notification Center and Control Center at rest,
upright and turned, the same pictures but for what the time changes (the clock, "2m ago", home's clock widget under
Control Center's glass); a copy of your own (opens as the list, upright in landscape, 5 columns fixed) took effect at the
next opening (Notification Center as the list; turned, the old stretched layout; Control Center 5 columns wide once a
smaller Display size made room) and went when removed; the settings' page shows "Control columns" from the file. Unit
tests of the file and its reader (`LayoutFilesTest`: what ships loads, the iOS file is what the code declared and today's
shade, 17 kinds of mistake refused, your own copy winning only when valid).

**Stage 2, Notification Center as blocks: done (2026-10-20)**, in three steps, each checked on the emulator against the
one before and pushed on its own:
- 2a (4e3a0d3): the clock and the corner buttons are blocks (`shade/nc/LockClockBlock`, `CornerButtonsBlock`), given the
  panel's view, size, point, progress, wallpaper and glass renderer through `shade/nc/NcPanel`. The same pictures upright
  and turned (the buttons' band and the list's identical to the pixel), a tap on the camera does nothing and a hold opens
  it, the clock turns to the next minute with the panel open.
- 2b (fdc0918): the list is a block (`shade/nc/NotificationListBlock`: grouping, the collapsed stack, swipes and Clear,
  headers, the player, the long look, scrolling, its touches, the overlay it lays over the wallpaper), moved as it was;
  the panel keeps the sheet, the wallpaper and its blurs, the glass renderer and the arrangement, and gives a touch to the
  buttons if it starts on one, else to the list (`NotificationCenterView` went from 2025 lines to 527). Checked: the
  pictures, the stack fanned out by a tap, a swipe revealing Options and Clear and put back, a long look opened and
  closed, Clear All on a stack, a drag on the empty sheet closing the panel; turned: a long list fanned out, scrolled and
  swiped in its column.
- 2c: which blocks Notification Center has is the file's: the notifications always, the clock and the buttons or not, in
  their order; the list takes the room either leaves (`comp.nc.list.top`: under the bar; `comp.nc.list.bottom`: where the
  buttons' bottom edge is) and fades only where it has a neighbour to fade over (over the clock under the bar, over the
  buttons), else at its area's top and at the screen's edge. `clock-beside-list` needs the clock. Checked: the shipped
  file as before; copies of your own without the clock and the buttons (the stack resting at the bottom of the taller
  area) and without the buttons (lower, under the clock).

On the S24 (9d54bb3, Matheesha): "everything works the same".

**Stage 3, Control Center as blocks (started 2026-10-20)**, in three steps, as stage 2:
- 3a: "+" and power (`shade/cc/TopButtonsBlock`: their drawing, the press, the power button held until the phone
  vibrates) and the status row (`shade/cc/StatusRowBlock`: its centre, pulled with the controls, and how far in it runs
  turned) are blocks, given the panel's view, size, point, presence, pull and its closing choreography through
  `shade/cc/CcPanel` (the choreography's numbers: `CcClose`). Checked on the emulator against 2c: Control Center upright
  and turned the same pictures but for home's clock widget under the glass; "+" enters edit mode, a press dragged off it
  does nothing, a tap on power does nothing and a hold opens the power dialog.
- 3b: the grid (its controls, modules, sliders, edit mode, the menu of a held control, the expanded modules).
- 3c: which blocks Control Center has is the file's.

## References

- Captures (emulator, Android 17 SystemUI, 1080 x 2400, 420 dpi): `tools/shots/shade_qqs.png` (first pull),
  `shade_qs.png` (Quick Settings), `shade_edit.png` (editing); ours: `ours_cc.png`, `ours_nc.png` (git-ignored).
- AOSP (main, read 2026-10-19): `packages/SystemUI/src/com/android/systemui/qs/panels/ui/compose/infinitegrid/`
  (`CommonTile.kt`: tile 72 dp, padding 8, icon 32, large tile icon 28 in a 56 dp box, off corner 50; `Tile.kt`: on corner
  24, icon box 16, the colour roles), `QuickQuickSettings.kt`, `PaginatedGridLayout.kt`, `compose/core/.../PlatformSlider.kt`
  (64 dp), `res/values/dimens.xml` (cards 28 dp, 4 dp between, side 16, tile gaps 8), `res/values/config.xml` (4 columns, 2
  quick rows, 4 rows a page), `compose/features/.../shade/ui/composable/` (single shade, overlays for the separate mode).
- Coverage of the Material 3 Expressive shade (Android 16 QPR1): Android Authority, 9to5Google (resizable tiles, pills when
  off, rounded rectangles when on, the blur, the Clear all row); the split ("Separate") option reported in Android 16 betas.
