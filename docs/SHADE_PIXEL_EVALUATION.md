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

**Stage 3, Control Center as blocks: done (2026-10-20)**, in three steps, as stage 2:
- 3a: "+" and power (`shade/cc/TopButtonsBlock`: their drawing, the press, the power button held until the phone
  vibrates) and the status row (`shade/cc/StatusRowBlock`: its centre, pulled with the controls, and how far in it runs
  turned) are blocks, given the panel's view, size, point, presence, pull and its closing choreography through
  `shade/cc/CcPanel` (the choreography's numbers: `CcClose`). Checked on the emulator against 2c: Control Center upright
  and turned the same pictures but for home's clock widget under the glass; "+" enters edit mode, a press dragged off it
  does nothing, a tap on power does nothing and a hold opens the power dialog.
- 3b: the grid is a block (`shade/cc/ControlGridBlock`: the page and its columns, every control in its own layer, the
  modules and sliders, Connectivity's circles and the player, apps' tiles, edit mode with its empty cells, drags, resizes
  and "Add a Control", a held control's menu, the expanded modules, and their touches), moved as it was; the panel keeps
  how present and how far pulled it is, the closing fold, the blocks' layout, "+" and power drawn over the grid as far as
  it rests (`quiet`), and gives a touch to the buttons if it starts on one, else to the grid (`ControlCenterView` went
  from 1550 lines to 278). Checked on the emulator against 3a: the same pictures upright and turned (but for the
  emulator's signal bars and home's clock widget under the glass); a toggle on and off, the brightness slider up, down
  and past its end, the camera held (its menu) and a tap outside, the player held (the large player) and Back, "+" into
  edit mode, a control dragged to another cell and back, Focus resized to one cell and back, Quick Share removed and
  added again from the gallery, a tap on empty space out of edit mode and a long press into it, a drag up closing the
  panel; turned: a toggle, a long press (no edit mode there), the camera's menu, a drag closing it.
- 3c: which blocks Control Center has is the file's: the controls always (`control-grid`), the status row or not, "+"
  and power (`top-buttons`, new in the vocabulary) or not, in their order; the buttons only with the row (they sit in the
  status bar's band, which only the row clears). Without "+" edit mode is a long press on empty space; without the row
  the status bar stays as it is over Control Center (its colour following the veil, the bar showing over an app that
  hides it; `StatusBarView.setPanel(ccHasRow)`) and the controls start under it (`comp.cc.grid.top-bare`, 59.7 pt; edit
  mode leaves them there; turned, under the bar's line as before). A panel takes a new structure at its next opening
  (`ControlCenterView.prepare`). Checked on the emulator: the shipped file as before (Control Center identical to 3b but
  for home's clock widget under the glass, Notification Center to 3a but for its clock); a copy of your own without the
  buttons (the row and the controls as before; a tap where "+" was is a tap on empty space, closing the panel; a long
  press into edit mode and a tap out), one without the buttons and the row (the bar's time and icons in place in white,
  the controls under it, edit mode in place; turned the same), and the shipped layout back once the copy was removed.

**Stage 4, panels and gestures: done (2026-10-20)**, in two steps:
- 4a: the shade's panels are objects (`Shade.Panel`): each says how it comes in with the finger and goes (its opening,
  its curves, how far the finger travels for it, its progress under a finger and when caught on its way out), what it
  shows on the way (Control Center's controls pulled below their place, risen into it, folded back closing), how it
  takes a drag, a touch and Back, and what it leaves behind; the shade drives one spring for the panel pulled and one for
  a panel on its way out under it, and knows neither by name. Nothing changes on screen but two details: Control Center
  going out under a pull of Notification Center now lifts its controls as it fades, as its own close does (the pull's
  start stopped the lift that letting it go had just begun), and edit mode's empty cells are recorded ahead when Control
  Center rests (they were also when Notification Center did). Checked on the emulator: both panels pulled open, a short
  pull let go, a swipe up from the bottom, Back, Control Center pulled past its place and risen, dragged closed, closed
  by a tap on empty space and under a pull of the other, each caught on its way out; a toggle, the brightness held and
  Back, "+" into edit mode and Back out of it, the gallery, a notification opening its app out of the fanned stack; both
  panels turned; both over the lock screen. (A Control Center over the emulator's lock screen that closed by itself was
  the old build's too: an injected swipe lifting a third of the way down, the known `input swipe` quirk.)
- 4b: which panels there are and where each is pulled from are the file's: Notification Center always, Control Center
  or not; each place of the layout says which panel each part of the top edge opens (`pulls`: the whole edge for one
  panel, each side of the camera for two, either way round; docs/LAYOUT_FILES.md). Control Center folds back into the
  corner it is pulled from (`CcPanel.cornerX`); the settings' stage plays the place's strokes (its finger down from each
  part of the edge, opening that part's panel); a layout without Control Center records nothing ahead for it. Checked on
  the emulator: the shipped file as before; a copy of your own with the sides swapped (the left opened Control Center,
  the right Notification Center; Control Center's close, slowed six times, folding into the top left, the far controls
  first); one with two places (the settings offer both under "Where it lives"; the mirrored one chosen: the pulls and the
  stage followed, its finger from the left opening Control Center first, then from the right Notification Center); one
  with Notification Center alone, from the top (from the right and the middle; nothing recorded ahead for Control
  Center; the settings' stage one stroke from the middle; the Grid group gone with its option).

One stage or two (Pixel's first pull: the quick row and the notifications; the second: the full Quick Settings) comes with
Pixel's combined panel, the first panel to have two. Next: step 4, Pixel's shade as a second layout file.

## Step 4: Pixel's shade as a layout file (started 2026-10-21)

Matheesha (2026-10-21), on whether iOS should offer Control Center on the left: "it should be configurable for each
layout. just like samsung does, the user can choose to combine both notifications and qs or keep them separate if the
layout supports it"; and on a written plan for Pixel's shade: "no need to write up a plan".

**Stage 1, places choose their panels (2026-10-21).** A layout's places may use different panels: one place pulls one
panel from the whole top edge (together), another two from each side of the camera (separately), as One UI's Quick
panel setting; every place has a panel for the notifications, every panel is used by some place (docs/LAYOUT_FILES.md).
iOS offers its two arrangements: Notifications on the left (iOS's own, the default) and Control Center on the left.
Checked on the emulator: the settings show both under "Where it lives" (the stage playing each place's strokes); the
mirrored one chosen and applied there ("[setup] shade: ios (split-top-mirrored)"): the left pull opened Control Center,
the right Notification Center (its fold into the left corner was checked in stage 4b). The layout tests now read the
file with Windows' line endings turned into plain ones (they failed on a fresh checkout, where Git writes CRLF).

**Stage 2, Android's shade, together: a first version (2026-10-21).** Pixel's layout file (`assets/layouts/shade/pixel.json`,
a preview: offered in the settings only with the developer tools on) with one place so far, Together: one panel pulled
from anywhere along the top, of the new kind `shade` (`shade/ShadePanelView`, blocks in `shade/qs/`, tokens `comp.qs.*`:
in iOS 27 aliases of the shared components with AOSP's sizes, in the Pixel theme Pixel's words and colours). The first
stage: the status bar's row with the date beside the time, the quick row (the first page's first two rows) and the
notifications as cards on a lighter shelf in three sections, joined columns, a group as one card with its count, outlined
actions on an ongoing card, the history, Clear all and settings under them. Pulling on (or a drag down on the panel)
expands Quick Settings: the bar's time hands over to one clock that grows into the large one (the date moving under it,
the carrier coming in beside it), the quick row's tiles move to their places in the grid as the rest of it comes in, the
brightness slider comes in at its place once the tiles have passed it, the build number and page dots under the grid,
settings and power at the foot, the cards going down out of sight. A drag up collapses, and from the first stage
closes; Back collapses first. Over the lock screen the cards show what its settings allow, the date taking the time's
place. Checked on the emulator against its own shade's captures (tools/shots/pxref): both stages in light and dark;
a pull, a drag down and up, Back from each stage, a pull past the first stage into Quick Settings, a swipe up closing it;
a tile's box toggling Do Not Disturb (and its corners and colours following), the second page swiped to, the brightness
dragged (the screen following) and set from elsewhere (the slider following); a group opened by its chip, a card swiped
away (the rest moving up), Clear all (the cards flicked away one after another, the one that cannot be cleared moving up
with its heading); the lock screen with the content hidden; slowed six times, the open, the expand and the collapse frame
by frame (no part crossing another: the slider once passed over the tiles and then the date, fixed). Turned, the panel
lies as upright with as many rows a page as fit (Pixel's own landscape, a row of tiles over a centred column, is still
to come).

**Stage 3, Pixel's shade as Android 17 plays it (2026-10-21).** Matheesha on the S24 (c10e23e): "swiping it down went
straight to the controls only, which is not how android handles it ... the button states, haptics and animations still
needs work ... the button radiuses and paddings didn't match in several places". Everything below was read from the
emulator's own SystemUIGoogle (Android 17, `adb pull /system_ext/priv-app/SystemUIGoogle/SystemUIGoogle.apk`: its
resources with `aapt2 dump`, its code with `dexdump -d`; names are not obfuscated) and measured on its own shade
(tools/shots/stock: screenshots while a real touch held each stage, `uiautomator dump` for bounds; our accessibility
service off). The emulator's shade is the scene container ("Flexiglass"): scenes Gone, Shade (the quick row over the
notifications) and Quick Settings, their transitions declared in `scene/ui/composable/transitions/`.

- The stages: a pull from the top never goes past the quick row (Gone to Shade: the finger's travel over the screen's
  height, counted from where it passed the touch slop); a second swipe down on the open shade (on the tiles, or on the
  cards once their list is at its top) expands Quick Settings, the notification scrim and its cards following the finger
  one to one out through the bottom edge (Shade to Quick Settings: the distance is the scrim's way to the bottom edge); two
  fingers bring Quick Settings with the pull. A swipe up goes back a stage, Back too. On letting go a stage commits once
  the finger moved 56 dp toward it or flung at 125 dp/s (DragControllerImpl, SwipeDetectorDefaults), else the nearer end;
  the spring is stopped at its place (the scene layout bounds the offset).
- Gone to Shade (toSingleShadeTransition, measured frame by frame): the quick row's tiles grow from 30 % of their height
  (GoneScene TilesSquishiness 0.3, verticalSquish: the box too, the words and symbols not), the notification scrim comes
  down from the top edge over them (translate from Edge.Top) and comes in with the pull, the header's date beside the time
  over the first half (linear), the cards from a quarter to 88 %, the background's tint early and its blur with the pull.
- Shade to Quick Settings (shadeToQuickSettingsTransition): the quick row's tiles move to their places in the grid (shared
  elements), the rest of Quick Settings fades in from 43 % to 95 % moving with the grid (anchoredTranslate to the grid
  anchor), the footer's actions over the first 83 ms of 500 (under the scrim until it passes), the bar's row (date,
  status icons) down 48 dp and out by 14 %, the clock growing from the bar's time (ShadeHeaderClock, ClockScale 2.57), the
  expanded rows (date with the status icons, the carrier) from 72 and 48 dp above, in from 58 %.
- The background: ShadeColors.shadePanel (shade_panel_fg accent1_100 at 40 % / accent1_800 at 32 % over shade_panel_bg
  neutral tone 98 at 40 % / tone 4 at 32 % over black at 32 % / 64 %) over the blur; the notification scrim surfaceEffect0
  (accent1_100 / accent1_800 at 50 %, 32 dp corners). Measured against the emulator's own at each stage of a pull: the
  composite and the blur's detail within a few levels.
- Tiles (TileDefaults, CommonTileDefaults, the resources): 72 dp, 8 dp apart; off in surfaceEffect1 (surface at 54 %), a
  capsule; on in primary, 24 dp corners; a two-target tile lights its 56 dp box only (surfaceEffect2 off: white 32 % /
  accent1_100 15 %, a circle; primary on, 16 dp); unavailable surface at 18 % with onSurfaceVariant at 38 %; names in
  labelLarge emphasized, states in labelMedium, onSurface. Colours and corners follow the state on spring() (1500, 1). A
  press grows the tile 8 dp into its neighbours (BounceableTileViewModel, BounceOnPressNode: spring 1500, back once three
  quarters there); a tap pops its symbol to 1.1 (a small tile) or its words to 1.06 (167 ms out on cubic 0.05/0/0/1, 167
  ms back on 1/0/0.95/1); haptics as TileHapticsViewModel's tokens: toggle on, toggle off, long press. Pixel's default
  set and order (quick_settings_tiles_default, large: Wi-Fi, Bluetooth, Mobile data, Quick Share, Do Not Disturb, Wallet).
  Android 17's own glyphs where they changed from AOSP main (vd_wifi, vd_cell, the plain Bluetooth, Wallet, Device
  controls; Do Not Disturb filled when on): tools/icons/extract_apk_vectors.py.
- The slider (BrightnessSliderDimensions, InternalDimensions): 40 dp tracks either side of a 4 x 52 dp handle, 6 dp gaps,
  12 dp outer and 2 dp inner corners, the symbol (low up to 20 %, full from 80 %, medium between) at the end of the
  inactive track or of the active one when the other has no room, crossing on a 100 ms tween; ticks while dragged.
- The header 120 dp; the build number's row 48 dp right under the grid, the edit button (Dashboard2Edit) at its end; the
  footer's actions: "N apps are active" (the shell's foreground services of apps with a launcher entry), the user,
  settings, power; 40 dp buttons 48 dp apart, 52 dp above the bottom edge.
- Cards (Android 16's template, measured): the first expanded (config_autoExpandFirstNotification) with its app's line,
  title, whole text and actions as words in primary; the app's own icon in the 40 dp circle; all words onSurface; Clear
  all and the Silent heading's clear button only while something can go; history and settings 60 x 48 dp, 14 dp under the
  last card.
- Motion roles of their own (motion.qs.*: open, close, expand, collapse, tile-bounce, tile-state, tile-pop, slider-icon;
  the moment "Quick Settings"), every size a comp.qs.* token with its source.

Not like Android 17's yet: the status bar's own look in the header's row (our status bar's layout: its time and icons,
as on home), Quick Share's glyph (Play services' own), a Bluetooth device's dots (the connection is not read), the user's
avatar. Still to come: the dual shade (Android 17 on a phone ignores its `dual_shade` setting: Pixel's file keeps one
place).

**Stage 4, the edit page (2026-10-21).** Android 17's "Edit tiles" (`shade/qs/QsEditPage`), read from SystemUIGoogle
(EditModeKt, EditTileKt, EditModeTileDefaults, QuickSettingsScene's InternalScenes) and measured on the emulator's own
(tools/shots/stock: ee_*, ea_*, ed_*, er_*: screenshots under 10x slow animations and while `input motionevent` held a
drag):

- Quick Settings give way to it on a tween of 500 ms (FastOutSlowIn): they fade out over the first half, the page in over
  the second (fractionRange on each scene's root), the header and the status bar's row with them; back the same way.
  Back (or the arrow) leaves it. Over the lock screen the edit button asks for the unlock first and the shade comes back
  with the page.
- The page: the arrow, undo (a 40 dp circle on surfaceEffect1, once something changed) and Remove (primary, 36 dp tall;
  faint while no tile is chosen) in a 64 dp row 40 dp under the status bar; "Edit tiles" (28 sp) and "Select tiles to
  rearrange and resize" (14 sp) scrolling under the row, the title coming into it once gone (Material's top title
  easing). The current tiles in a box 178 dp under the status bar: 10 dp inside, 28 dp corners, a 2 dp primary border,
  primary at 15 % inside, tiles 6 dp apart (the panel's are 8), each drawn off and at rest (an app's tile with its app's
  name under its own). One is always chosen (AutoSelectTiles: the first, else one just added): a 2 dp primary outline
  inside its edge and an 8 x 16 dp handle on it at its end.
- A tap chooses a tile; the handle (or a drag of it: the width follows the finger, past halfway the tile is the other
  size) makes it small or large; a long press lifts a tile (left at half at its place, a dark slot where it will land:
  onSurface at 11 %, the drag shadow at 0.7071), the others making way on TilePlacementSpec (spring 400, damping 0.75);
  let go outside the box it is taken out; near the page's ends the page scrolls (AUTO_SCROLL_DISTANCE, _SPEED).
- The tiles to add, by category (TileCategory: Connectivity, Utilities, Display, Privacy, Accessibility, From system
  apps, From apps; SystemUI's own category symbols), each category a box on surface at 32 %, 2 dp apart, the first and
  last rounded 28 dp; 4 a row, 82 dp wide, the name under it (two lines at most, labelMedium), a 24 dp primary badge with
  a plus; one already in the box at 38 % without a badge. A tap adds it at the end (chosen), a long press drags it in.
  Apps' tiles: a system app's in the category its tile declares (`android.service.quicksettings.TILE_CATEGORY`, Settings'
  Audio streams in Connectivity), else From system apps; others From apps; one named as a control of ours (Quick Share,
  Live Caption) is that control.
- Reset at the end (40 dp, primary): "Reset all tiles?" (a 379 dp box on surface, 28 dp corners, the page dimmed by black
  at 60 %, Android's dialog dim), Cancel and OK; undo can bring the tiles back.
- Every change is kept at once (`shade/qs/QsTileStore`, files/shared_prefs/qs_tiles.xml; a Pixel's own until changed) and
  Quick Settings under the page follow it. Do Not Disturb's tile is "Modes" there (Android 16's Modes tile), "Do Not
  Disturb" in Quick Settings while it is on.
- Found on the way: a symbol's circle on a two-target tile was drawn over the screen behind the panel, not over the tile
  (the renderer composes a surface over its backdrop, not over what was drawn): it came out darker than the tile, Quick
  Settings' off tiles too. The tile's fills now lie under it (`under`), as the box's under the tiles in it.
- The emulator has no Google Sans (its SystemUI draws in Roboto Flex): its words are about 11 % narrower than ours (the
  Pixel theme's Google Sans Flex, as a Pixel's), so a name like "Airplane mode" takes two lines here and one there.

Motion roles: motion.qs.edit (the crossfade), motion.qs.edit-place (tiles to their places), motion.qs.edit-appear (undo, a
tile added or taken out, Reset's question); tokens `comp.qs.edit-page.*`.

**Stage 5, the media player (2026-10-21).** Android 17's (SystemUIGoogle's "remedia", MediaKt; measured on the emulator
with a test session of ours: `TEST_NOTIFY --ez media true [--ez playing false] [--ez cancel true]`, a session with art and
its media notification, as a music app's): the layout file's `media` block (`shade/qs/MediaBlock`), on our `Media`.

- A 379 x 176 dp card 16 dp under the quick row at the first stage and 16 dp under the build number's row in Quick
  Settings, moving between the two as they expand; the notification scrim rests under it, and while it comes or goes
  (the appear role) the notifications make way. Its media notification leaves the cards meanwhile.
- Its colours from the art, as Android's media colour scheme: the art's main colour (WallpaperColors) as a hue; the ground
  that hue at tone 14 and chroma 27 with the art over it, centre-cropped, at 25 % in the middle falling to 12 % at the
  edges (one picture the card's size, made off the drawing thread for each art); the play button, the app's symbol and
  the output chip at tone 90, the play symbol at tone 10; the words, the wave, the thumb and the arrows white. A new art
  crossfades in. Against the emulator's own card under the same art: every point within 2 to 6 levels.
- The app's own small symbol at the top left (24 dp), the output chip at the top right (32 x 24 dp outlined, the cast
  symbol: Settings' media output panel), the title (titleMedium) and artist (bodyMedium) centred on the play button (72 x
  48 dp: a rounded square of 16 dp corners while playing, round while paused, its symbol crossfading; its corners tighten
  while pressed), previous and next (Material Symbols' outlined skip symbols, 48 dp targets) with the line between them:
  Android's squiggly progress, a wave 20 dp long, 1.5 dp high and 2 dp thick travelling 8 dp a second up to a 4 x 16 dp
  thumb, the track after it white at 30 %; paused, or while the finger drags the thumb (seeking), the wave calms to a line
  (motion.qs.media). A tap on the card opens the app.

Not like Android 17's yet: several sessions (Android shows a carousel; ours the session `Media` picks), the swipe that
reveals the card's settings and dismisses it, timestamps while seeking, the output switcher itself (Android's dialog: ours
opens Settings' media output panel), and the card's light following the touch (remedia's ripple).

**Stage 6, turned (2026-10-21).** Pixel's file says `"landscape": "split"`, Android 17's own arrangement on a turned phone
(measured on the emulator turned, 914 x 411 dp: tools/shots/stock/ls_*):

- The first stage: the quick row on the left half (433 dp, 4 columns, 2 rows), a compact player on the right half (433 x
  128 dp: no line; next beside the play button) centred on the quick row; the notifications under them on the full-width
  scrim, as cards 644 dp wide, centred.
- Quick Settings: the brightness slider across, 22 dp under the status bar; the tiles on the left half 96 dp under it (2
  rows a page, pages turning within the half), the build number's row under them (its dots and edit button within the
  half); the full player on the right half, centred on the tiles and that row, growing out of the compact one as they
  expand (its line row coming in); the footer across at the foot. No large clock: the status bar's row stays as it is,
  the date beside the time.
- Against the emulator's own: the compact player 36.2 to 163.4 dp (its 163.8), the full player 132.2 to 307.4 (132.6 to
  308.2), the slider 52.2 to 91.4 (91.8); upright unchanged (the player 192.0 to 367.6 and 608.0 to 783.6, its 608.4 to
  784.0).

**Stage 7, Matheesha on the S24 (2026-10-21): "the expanded controls swiping mechanism is broken ... when music is playing,
I couldn't scroll down the notifications ... not sure if the shade background colours work properly".**

- Quick Settings' pages: a sideways swipe left the pages where the finger let go (the release was never handled). Now
  Compose's pager as Quick Settings use it (PagerDefaults' snap): a fling of 400 dp/s turns to the next page that way,
  else the nearer page, one page at most, on `motion.qs.page` (spring, stiffness 400). Checked with real touches: flings
  both ways, a short drag back, a drag past half on.
- The notifications' scroll: its end was worked out when the list was laid out, while the shade was still closed (the
  scrim at the top: the whole height to scroll in), so a list that only just overflowed could not scroll at all, and the
  media player (it lowers the scrim 192 dp) made it so for most lists. The list's viewport is now under the scrim where it
  will rest (the player's coming or going counted), checked again whenever that place moves and at each drag.
- The background under five wallpapers (warm, green, blue, dark, light; Android's palette given each one's main colour,
  as a wallpaper change does on a Pixel: the emulator does not take a palette from a wallpaper an app sets until its
  picker or a screen-off), the emulator's own shade and ours over the same home: the first stage and Quick Settings within
  0 to 3 levels everywhere (tools/shots/wp). Found on the way: the panel's tiles, buttons and cards (plain Material colours,
  no glass) were composed by the painter over our picture of what is behind, which comes after the panel starts (over
  2 s on a busy emulator) and stood in for a dark grey until then: the shade opened dark grey and brown and lightened
  once the picture came. Plain materials are now drawn as they are, over what really is under them (the system's live
  blur, the veil, a tile under a symbol's circle): `CcSurfaces.plainDirect`, on while Android's shade draws.
- Performance (emulator, simpleperf and Perfetto while the shade stayed open with music playing, which draws it at every
  frame): recording a frame of the panel took 7.7 ms, the tiles 6.75 of it: every tile asked the package manager at every
  frame whether its app exists (`ControlState.available` for Wallet, Quick Share, Device controls: a tenth of the
  process), every text style parsed its font family's name again (`Fonts.parse`), every tile's words were ellipsized again.
  Kept now (per opening, once, per words and width): 2.7 ms a frame, the tiles 0.65. The media player's wave is one
  wavelength drawn into a strip once and laid along the line (a stroked path the line's length, made at every frame,
  went through the GPU's stencil), and the clips around the scrim and the cards have one radius (their bottom corners
  below the screen). The emulator's frame times swing by 2x between identical runs: the frame pacing is the S24's to judge.
- The corners: every tile, box, slider, button, card and the media card measured against the emulator's own at full
  resolution: the same. None of Android 17's buttons change shape when pressed (its footer, tiles and cards checked in
  SystemUIGoogle). Not reproduced; the S24's are to be shown.

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
