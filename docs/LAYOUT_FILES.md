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
| `placements` | The places it can live, each `{"id", "title", "description"}`; the first is its default. A shade's place also says where each panel is pulled from (`pulls`, below). |
| `options` | What the user can set, each `{"key", "kind", "title", "description", "default", ...}`: `count` (with `min`, `max`), `toggle`, `choice` (with `choices`: `[{"id", "title"}]`). The settings turn them into controls. |
| `structure` | The element's structure, for elements whose structure is data (the shade, below). |

Every key is read strictly: an unknown key (a typo) or value, a missing one or one of another kind refuses the whole file,
with the reason. Nothing passes as a default by accident.

## The shade

`structure.panels`: the panels the shade has, one or two: Notification Center always (the notifications need a panel:
banners open it, the lock screen shows it), Control Center or not; each kind once. Each is `{"panel", "blocks",
"landscape"}`:

| Key | Values | Read by |
| --- | --- | --- |
| `panel` | `notification-center` (iOS's sheet pulled down over the screen), `control-center` (the controls over the blurred screen) | |
| `blocks` | What the panel is made of, in order (below) | |
| `landscape` | Notification Center: `clock-beside-list` (iOS 27: the date and time small at the top left, the notifications in a column of the portrait width beside them; measurements in tokens `comp.nc.land.*`) or `as-portrait` (as upright, across the turned screen). Control Center: `flow` (the grid flowed across as many columns as fit) | `NotificationCenterView.land`, `ControlCenterView.syncTokens` |

Where each panel is pulled from is the place's (the user chooses among the layout's places, as for every element):
`placements[].pulls`, which panel each part of the top edge opens, each `{"from", "panel"}`. `from`: `top` (anywhere along
it), `left-of-camera` or `right-of-camera` (the camera's place, or the middle where there is none); `panel`: one of the
structure's. One panel is pulled from the top; two, one from each side of the camera, either way round. Read by
`Shade.onTouch` (in the place chosen: `Setup.placement`), Control Center's fold (it folds back into the corner it is pulled
from: `CcPanel.cornerX`) and the settings' stage (its finger from each part of the edge in the file's order, opening its
panel: `settings/Stage.gestureOf`). iOS's one place:

```json
{"id": "split-top", "title": "...", "description": "...",
 "pulls": [{"from": "left-of-camera", "panel": "notification-center"}, {"from": "right-of-camera", "panel": "control-center"}]}
```

Blocks, each `{"block": ID, ...its parameters}`:

| Block | Parameters | What it is |
| --- | --- | --- |
| `lock-clock` | | The date and the glass clock (iOS's lock screen). Optional: without it the list starts under the status bar (`comp.nc.list.top`) and fades as it leaves its area's top. Required by `landscape: clock-beside-list`. |
| `notifications` | `opens`: `stacked` (iOS 27: gathered in one stack at the bottom, the newest in front, the next peeking out, "+N" under it; a tap fans them out) or `list` (every app's group in its place). | The notifications, grouped by app (`shade/nc/NotificationListBlock`). Required. |
| `corner-buttons` | | The flashlight and the camera in the bottom corners (touch and hold; upright only). Optional: without them the list goes down to `comp.nc.list.bottom` and the screen's edge cuts it. |
| `top-buttons` | | "+" (edit mode) and the power button (held) at Control Center's top corners, where the status bar was (upright only; `shade/cc/TopButtonsBlock`). Optional: without them edit mode is a long press on empty space. Needs `status-row` (only the row clears the bar's band). |
| `status-row` | | The status bar's row along the top, its icons gliding in from the bar (`shade/cc/StatusRowBlock`). Optional: without it the status bar stays as it is over Control Center (its colour following the veil) and the controls start under it (`comp.cc.grid.top-bare`; edit mode leaves them there). |
| `control-grid` | `columns`: a whole number, or `{"option": KEY}` naming one of the file's `count` options (the user's value). `editor`: `in-place`. Read by `ControlGridBlock.syncTokens` (at most what fits, at least 4). | The controls on a grid, edited in place (`shade/cc/ControlGridBlock`). Required. |

**Stage 1 (2026-10-20): the vocabulary, and the choices the panels already have.** The file speaks for the whole shade,
but only what today's panels can already do in more than one way is the file's to choose: how Notification Center opens
(`opens`), how it lies in landscape (`landscape`), and where Control Center's column count comes from (`columns`). The rest
is checked to be what the panels do and refused otherwise, with the reason "(so far)": one Notification Center pulled
from the left of the camera and one Control Center from the right (until stage 4); Notification Center made of `lock-clock`,
`notifications`, `corner-buttons`, Control Center of `top-buttons`, `status-row`, `control-grid`, in that order (a
block may be left out where the stages below say so, never moved); the editor in place; Control Center's landscape
`flow`. Later stages lift these one by one:

- Stage 2, Notification Center as blocks (done 2026-10-20): the clock, the list and the corner buttons are blocks
  (`shade/nc/LockClockBlock`, `NotificationListBlock`, `CornerButtonsBlock`) given what they need by the panel
  (`shade/nc/NcPanel`), which places them; which of them Notification Center has is the file's: the notifications always,
  the clock and the buttons or not (in their order: clock, notifications, buttons), the list taking the room either leaves.
- Stage 3, Control Center as blocks (done 2026-10-20): "+" and power, the status row and the grid are blocks
  (`shade/cc/TopButtonsBlock`, `StatusRowBlock`, `ControlGridBlock`) given what they need by the panel (`shade/cc/CcPanel`),
  which places them; which of them Control Center has is the file's: the controls always, the status row or not, "+"
  and power or not (only with the row).
- Stage 4, panels and gestures (done 2026-10-20): the shade knows its panels only as panels (`Shade.Panel`: each says
  how it comes in with the finger and goes, what it shows on the way and what it leaves behind); which there are is the
  file's (Notification Center, and Control Center or not), and where each is pulled from is each place's (`pulls`: the
  whole edge for one panel, each side of the camera for two, either way round). One stage or two (Pixel's first pull
  showing the quick row, the second the full Quick Settings) comes with Pixel's panel, the first to have two.
- Then step 4: Pixel's shade as a second file (`pixel.json`), One UI's after it (their landscape in
  docs/PLAN_LAYOUTS_THEMES.md, D).

The iOS file (`assets/layouts/shade/ios.json`) is exactly what the code declared before (`Layouts.IOS_SHADE`: the same id,
name, place and option) and the shade as it is: checked by `LayoutFilesTest`.
