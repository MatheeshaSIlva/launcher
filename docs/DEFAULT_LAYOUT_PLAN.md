# The default layout: build plan

Draft, 2026-11-09, lead. The design is draft 2 in Figma (https://www.figma.com/design/7E43AzlCpFNqhUkApcstZA, page "Draft 2"),
approved by the owner (D-093, D-094); the language is the owner's design system (D-092). This plan says what the engine must
gain to ship it, in what order, and what needs the owner first. It does not change the spec's build order: it fills steps 2
to 5 with this layout's parts.

## 1. What the drafts need, screen by screen

| Screen | Surface | Engine needs | Launcher code to reuse |
| --- | --- | --- | --- |
| Island, at rest and live (states, volume) | `shade`, its `status` slot | cutout-aware placement; docked (home) or floating (apps) shape; width and height springs; states from sources; decode text | the KDL bar (slices 6-12), `StatusBarView`'s cutout maths, charging and sharing watchers |
| Panel grown from the island (2, 3, 8, 9, 16) | `shade` | the island and panel as one shape; finger-driven progress (`sheet` preset); accordion sections, one open; a slider; toggles | `ControlState`, `Controls`, `Notifs`, `Media`, `LiveBlur` (not used: the panel is matte) |
| Power sheet (10) | `shade` | sheet; hold-to-confirm; actions `power.lock`, `power.restart`, `power.off` | the shell service (verify `svc power` as shell uid) |
| Volume on the island (11, 12) | `shade` | volume key filter; per-stream volume source and action; output device | none: new (the accessibility service can filter keys) |
| Home with the docked island (1, 15) | `home` | the frame (6 dp) and the wallpaper window; a clock widget; a dock of pinned apps; the island's dock shape flared into the frame | `home/`, `Wallpaper`, `apps/` |
| Launcher (6, 13, 14) | `drawer` | grows out of the dock with the finger; most-used; A to Z with a fast index; search above the keyboard; `>` commands | `drawer/`, the app index and usage order, IME insets handling |
| Recents (5) and app swipes | `recents` | the deck: cards, neighbours, clear all; up = home, hold = recents, along the bottom = switch | `GestureNav`, the card window, `switcher/` (all proven; reskinned, not rewritten) |
| Settings (7) | the app itself, not a surface | the settings app in the design language; rows from the active packages' props | `settings/` is scrapped (D-092); `SettingsActivity` shell only |
| Apply moment (17) | all | an engine-owned transition between layouts | `motion/transition` |
| Lock (draft 1) | not available | none | Android owns it |

## 2. New engine pieces

- **Shape union.** Rounded boxes merged with a circular smooth minimum (Caelestia's method, `docs` of T-007's Figma notes): the
  frame, the docked island, the panel and the launcher drawer are one shape at their joins. An AGSL shader on a RenderNode the
  engine owns, drawn under the components; components only say which boxes exist. Risk: cost per frame on a pull (the S24
  measures it, slice by slice).
- **Cutout placement.** `env.display.cutout` (the camera's rect) and a placement that centres a component on it.
- **Accordion state.** A `section` primitive (header, body, `open`), with a group rule of one open at a time, and its motion.
- **Decode text.** Typographic motion as an engine motion: per-character scramble, number ticks, stagger (the design language's
  motion section).
- **Design-language motion.** The language's durations and cubic beziers as motion presets beside the springs the engine has.
- **Sources.** `media` (exists in the launcher), `volume` (per stream, output device), `calendar` (events), `weather`, `system`
  (cpu, memory, temperature, storage, network rate, uptime), `apps.usage` (most used), `island` (what is live and ranked).
- **Actions.** `notifications.open/dismiss/reply` (spec), `media.*`, `volume.set`, `power.*`, `commands.*` (the `>` registry:
  calc, wallpaper, scheme, timer, flashlight, lock), `surface.show/hide`.
- **Prop types for settings.** `enum`, `color`, `font`, `token`, `duration`, `bool` with labels (the design system's settings
  section asks for them).

## 3. Order

E2's remaining slices are re-aimed at the island (owner, 2026-11-09): the same engine work, on the bar that ships. The old bar
keeps the pixel proof it has already passed (slices 9, 10, 12).

| Slice | What | Exit |
| --- | --- | --- |
| E2.13 | Island geometry: cutout placement; floating and docked shapes; size springs on the language's curves | emulator and S24 screenshots of both shapes; unit tests of the placement |
| E2.14 | Island states: at rest (the layout's "Island at rest" prop), live media, charging, notification arrival with decode | state fixtures; the owner on the S24 |
| E2.15 | Fixtures and goldens for every island state, light and dark | `diff.py` against the goldens the owner accepted |
| E2.16 | Hot reload keeping state | reload test on the emulator |
| E2.17 | Exit on the S24: the island over apps and home, docked and floating, pulls and idle | ≤ 1 dropped frame in 100 at 120 Hz, idle < 1 ms |
| E3 | The panel: shape union, the `sheet` pull from the island, accordion sections, toggles, brightness, media, notifications with actions, Today, System; power sheet; volume on the island; banners as island arrivals (calls and alarms must ring through it) | the shade's inventory as a checklist of functions, all present; the owner on the S24 |
| Settings track (alongside E3) | the settings app in the design language; prop types; rows from active packages; engine pages | the owner on the S24 |
| E4 | Home frame, clock, dock, launcher from the dock, A to Z, search over the keyboard, `>` commands; the apply moment | the owner on the S24; frame budget on the launcher rise |
| E5 | Recents and app swipes on the existing deck, reskinned | frame stats as the launcher's switcher today |

Each slice about 200 changed production lines, as now; each step gets its own thread and split with Codex.

## 4. Needs the owner first

1. **Weather**: an external service (Open-Meteo, no key) and the phone's location (a new permission, or a city typed once in
   settings). Which?
2. **Calendar**: `READ_CALENDAR`, a new permission, for Today's events.
3. **Volume keys**: filtering them through our accessibility service, and keeping Android's own volume panel away. Measured
   first on the S24; if Android's panel cannot be kept away, the island only follows it.
4. **The island's live media bars**: real audio levels need `RECORD_AUDIO` (Android's Visualizer). Without it, the bars move
   with playback only, not the sound. Which?
5. **Power actions** from the shell: restart and power off as shell uid, verified on the S24 before E3 promises them.

## 5. Risks

- The shape union's cost on a 120 Hz pull is unknown until measured; the fallback is separate shapes with drawn flares (as the
  Figma draft does), which look the same at rest.
- Banners: hiding the stock shade suppresses heads-up and full-screen intents. The island must carry incoming calls and alarms
  from day one of E3, or the stock shade stays.
- The island over apps sits where the status bar was: an app that hides the status bar (video) hides the island too (as the bar
  today), unless the owner wants otherwise.
