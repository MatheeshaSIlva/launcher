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
| 72 dp rows, 8 dp gaps, cells wider than tall | Square cells (one `comp.cc.grid.cell`), gap token | **New token**: the cell's height apart from its width |
| Two sizes only: one column (symbol), two columns (symbol box and words) | Sizes per control, in code (connectivity 2x2, media 2x2 and 4x2, sliders 1x2 and 1x3...) | **New option**: the sizes a layout allows (others shown at the nearest allowed) |
| Tiles flow in order, no empty cells | Free placement with empty cells (iOS 18); a flowing pass exists for landscape (`CcLayout.flowed`) | **New option**: arrangement "free" or "in order" |
| Pages of 4 rows with dots, swiped sideways | One page | **New block**: pages and their dots |
| The quick row: the first two rows, shown at the first pull | None | Part of the two-stage panel |

### Tiles

| Pixel | Our shade today | What it takes |
| --- | --- | --- |
| Off: a pill; on: 24 dp corners; animated | One corner for every state | **New token** (the corner when on) and a motion role |
| Large tile: a 56 dp symbol box (16 dp corners when on), name and state beside it | The wide control: a circle behind the symbol (always a circle), name and state beside it | **New token**: the symbol box's corner (off and on); sizes and fonts are **Theme** |
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
| Corners and colours animate with a tile's state | Colours do (the toggle role) | **New role** with the new corner token |
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
2. **The four look additions**: corner when on, symbol box corner, cell height, symbol set (with Material Symbols).
3. **The shade as blocks**: the iOS shade rebuilt from blocks and described by a layout file, with nothing changing on
   screen (checked frame by frame against today's).
4. **Pixel as a layout file**: the missing blocks and options added one by one, each compared with the captures and AOSP's
   numbers, until the file reproduces Pixel's shade.
5. **Later**: the layout builder in the settings, so the same file format is open to everyone.

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
