# Layout files

A layout of an element described as data, as a theme file describes a look (docs/PLAN_LAYOUTS_THEMES.md: "a layout is a
file that arranges them, as a theme is a file of tokens"). The shade is the first element whose layouts are files (step 3
of docs/SHADE_PIXEL_EVALUATION.md); the other elements' layouts are still written in `layout/Layouts.kt`.

## Where they are, how they are read

- Shipped: `app/src/main/assets/layouts/<element>/<id>.json` (the folder is the element, the name the layout's id). The
  order they are offered in, and so each element's default, is `Layouts.SHIPPED` (the shade: `ios`).
- Your own copy of a shipped file, `files/layouts/<element>/<id>.json` in the app's files, wins over it when it is valid;
  one that is not is logged and left aside ("[layouts] ... of your own refused (reason): the shipped one is used").
  Pushed through the inbox: copy it to `/sdcard/Android/data/dev.launcher.app/files/dev-in/layouts/shade/ios.json`, then
  `adb shell am broadcast -a dev.launcher.app.DESIGN_RELOAD -p dev.launcher.app --es files import`; taken away with
  `--es files remove --es path layouts/shade/ios.json`. A panel takes the new structure the next time it opens.
- Read at app start, before the setup (which checks each element's chosen layout, place and options against them):
  `Layouts.init`, log line "[layouts] files: shade/ios". A shipped file that does not load is a broken build (it throws;
  the unit tests load every one: `LayoutFilesTest`).
- New layouts of your own (ids that do not ship) are not read yet: they come with the layout builder (step 5).

## What a file says

| Key | Meaning |
| --- | --- |
| `format` | 1. A file of another format is refused (it may mean something this build cannot do). |
| `element` | `status-bar`, `shade`, `recents`, `drawer`, `home` or `settings`. |
| `id` | Lower case letters, digits and dashes; what the setup stores (`files/setup.json`). |
| `title`, `origin`, `description` | The layout's name, the design it comes from, what it is in plain words (the settings show them). |
| `src` | Where it came from (measurements, captures, AOSP): for people, read by nothing. |
| `placements` | The places it can live, each `{"id", "title", "description"}`; the first is its default. |
| `options` | What the user can set, each `{"key", "kind", "title", "description", "default", ...}`: `count` (with `min`, `max`), `toggle`, `choice` (with `choices`: `[{"id", "title"}]`). The settings turn them into controls. |
| `structure` | The element's structure, for elements whose structure is data (the shade, below). |

Every key is read strictly: an unknown key (a typo) or value, a missing one or one of another kind refuses the whole file,
with the reason. Nothing passes as a default by accident.

## The shade

`structure.panels`: the panels the shade has. Each is `{"panel", "pull", "blocks", "landscape"}`:

| Key | Values | Read by |
| --- | --- | --- |
| `panel` | `notification-center` (iOS's sheet pulled down over the screen), `control-center` (the controls over the blurred screen) | |
| `pull` | `left-of-camera`, `right-of-camera`: the part of the top edge a pull opens it from (the camera's place, or the middle where there is none) | `Shade.onTouch` |
| `blocks` | What the panel is made of, in order (below) | |
| `landscape` | Notification Center: `clock-beside-list` (iOS 27: the date and time small at the top left, the notifications in a column of the portrait width beside them; measurements in tokens `comp.nc.land.*`) or `as-portrait` (as upright, across the turned screen). Control Center: `flow` (the grid flowed across as many columns as fit) | `NotificationCenterView.land`, `ControlCenterView.syncTokens` |

Blocks, each `{"block": ID, ...its parameters}`:

| Block | Parameters | What it is |
| --- | --- | --- |
| `lock-clock` | | The date and the glass clock (iOS's lock screen). Optional: without it the list starts under the status bar (`comp.nc.list.top`) and fades as it leaves its area's top. Required by `landscape: clock-beside-list`. |
| `notifications` | `opens`: `stacked` (iOS 27: gathered in one stack at the bottom, the newest in front, the next peeking out, "+N" under it; a tap fans them out) or `list` (every app's group in its place). | The notifications, grouped by app (`shade/nc/NotificationListBlock`). Required. |
| `corner-buttons` | | The flashlight and the camera in the bottom corners (touch and hold; upright only). Optional: without them the list goes down to `comp.nc.list.bottom` and the screen's edge cuts it. |
| `status-row` | | The status bar's row along the top, its icons gliding in from the bar. |
| `control-grid` | `columns`: a whole number, or `{"option": KEY}` naming one of the file's `count` options (the user's value). `editor`: `in-place`. Read by `ControlCenterView.syncTokens` (at most what fits, at least 4). | The controls on a grid, edited in place. |

**Stage 1 (2026-10-20): the vocabulary, and the choices the panels already have.** The file speaks for the whole shade,
but only what today's panels can already do in more than one way is the file's to choose: how Notification Center opens
(`opens`), how it lies in landscape (`landscape`), and where Control Center's column count comes from (`columns`). The rest
is checked to be what the panels do and refused otherwise, with the reason "(so far)": one Notification Center pulled
from the left of the camera and one Control Center from the right; Notification Center made of `lock-clock`,
`notifications`, `corner-buttons`, Control Center of `status-row`, `control-grid`, in that order; the editor in place;
Control Center's landscape `flow`. Later stages lift these one by one:

- Stage 2, Notification Center as blocks (done 2026-10-20): the clock, the list and the corner buttons are blocks
  (`shade/nc/LockClockBlock`, `NotificationListBlock`, `CornerButtonsBlock`) given what they need by the panel
  (`shade/nc/NcPanel`), which places them; which of them Notification Center has is the file's: the notifications always,
  the clock and the buttons or not (in their order: clock, notifications, buttons), the list taking the room either leaves.
- Stage 3, Control Center as blocks: the status row, the grid, its modules and sliders, the editor.
- Stage 4, panels and gestures: one panel or two, pulled from where, one stage or two (Pixel: the quick row first).
- Then step 4: Pixel's shade as a second file (`pixel.json`), One UI's after it (their landscape in
  docs/PLAN_LAYOUTS_THEMES.md, D).

The iOS file (`assets/layouts/shade/ios.json`) is exactly what the code declared before (`Layouts.IOS_SHADE`: the same id,
name, place and option) and the shade as it is: checked by `LayoutFilesTest`.
