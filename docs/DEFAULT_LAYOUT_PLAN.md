# The default layout: build plan

Draft 2 of this plan, 2026-11-09, lead, after Codex's first review (T-008). The design is draft 2 in Figma (https://www.figma.com/design/7E43AzlCpFNqhUkApcstZA, page "Draft 2"),
approved by the owner (D-093, D-094); the language is the owner's design system (D-092). This plan says what the engine must
gain to ship it, in what order, and what needs the owner first. It does not change the spec's build order: it fills steps 2
to 5 with this layout's parts.

## 1. What the drafts need, screen by screen

| Screen | Surface | Engine needs | Launcher code to reuse |
| --- | --- | --- | --- |
| Island, at rest and live (states, volume) | `shade`, its `status` slot | cutout-aware placement; docked (home) or floating (apps) shape; width and height springs; states from sources; decode text | the KDL bar (slices 6-12), `StatusBarView`'s cutout maths, charging and sharing watchers |
| Panel grown from the island (2, 3, 8, 9, 16) | `shade` | the island and panel as one shape; finger-driven progress (`sheet` preset); sections composed from header and body with a reusable expand-and-group behaviour; a slider; toggles; input and action dispatch | `ControlState`, `Controls` (torch, timer, media volume), `Notifs` (open, cancel, send), `Media` (controls) |
| Power sheet (10) | `shade` | sheet; hold-to-confirm; actions `power.lock`, `power.restart`, `power.off` | the shell service (verify `svc power` as shell uid) |
| Volume on the island (11, 12) | `shade` | global volume key interception; per-stream volume and output device (`audio`, `media.volume`) | `Controls` reads and sets media volume; `Shade` forwards volume keys while its focus holder is active. New: global interception, other streams, output devices |
| Home with the docked island (1, 15) | `home` | the frame (6 dp) and the wallpaper window; a clock widget; a dock of pinned apps; the island's dock shape flared into the frame | `home/`, `Wallpaper`, `apps/` |
| Launcher (6, 13, 14) | `drawer` | grows out of the dock with the finger; most-used; A to Z with a fast index; search above the keyboard; `>` commands | `drawer/`, the app index, `LaunchStats` (the launcher's own frequency and recency, not device-wide usage), IME insets handling |
| Recents (5) and app swipes | `recents` | the deck: cards, neighbours, clear all; up = home, hold = recents, along the bottom = switch | `GestureNav`, the card window, `switcher/` (host, input and physics kept; the drawing rewritten as a KDL `deck`) |
| Settings (7) | the app itself, not a surface | the settings app in the design language; rows from the active packages' props | `settings/` is scrapped (D-092); `SettingsActivity` shell only |
| Apply moment (17) | all | an engine-owned transition between layouts | `motion/transition` |
| Lock (draft 1) | not available | none | Android owns it |

## 2. New engine pieces

Names follow the spec's Sources and Actions; nothing here creates a competing name.

- **Shapes.** Rounded boxes with concave joins, as engine geometry: drawn first (paths, reusable by any component), an AGSL
  smooth union only if measurement shows the drawn joins cannot match during expansion, reversal and separation. Bounded shape
  count and drawing area, GPU path warmed, clipping and hit regions defined, behaviour below Android 13 defined. The default
  layout owns the arrangement; the engine owns the drawing.
- **Motion for size and presence.** The host animates translation, alpha and scale today and removes a hidden node outright; the
  island needs size springs and presence (arrive, leave) on the existing `motion/Curves` machinery (springs and beziers), not a
  new timing engine. The design language's beziers become presets there. Also E2's generic place springs and rolling text.
- **Cutout.** From the `device` source (cutout insets, as the spec names them), with a placement that centres on it, and a
  no-cutout fallback.
- **Decode text.** Typographic motion: per-character scramble, number ticks, stagger. Its clock stops when nothing decodes.
- **Sources (spec names).** `media`, `audio` (streams, output device), `device`, `apps` (usage order), `storage`; new and needing
  a capability and availability contract: `calendar`, `weather`, CPU and temperature telemetry. Island ranking (what is live
  first) stays in the default component's policy, not the engine: call, alarm, volume (transient), notification arrival,
  charging, media, in that order, a higher one interrupting a lower and the lower returning after.
- **Actions (spec names).** `notifications.open/dismiss/action/reply`, `media.*`, `media.volume`, power actions (capability and
  availability defined), `commands.*` (the `>` registry), `surface.show/hide`. Inline reply stays withheld until the keyboard-
  under-the-overlay problem the spec names is solved.
- **Input and action dispatch.** Buttons, sliders and holds calling actions from KDL, before Lua (E6).
- **Prop metadata for settings.** `PropDef` has name, type, default and span today; settings need label, doc, min, max, step,
  enum values, group, validation and persisted user values. Built-in packages first, without the package manager (E8).

## 3. Order

E2's remaining slices are re-aimed at the island (owner, 2026-11-09), and E2 keeps both proofs: the KDL bar against
StatusBarView as the engine's parity test (D-092), and the island against goldens the owner accepted as the product's.

| Slice | What | Exit |
| --- | --- | --- |
| E2.13a | Size and presence motion on the existing curves; place springs; rolling text | motion unit tests; frames at exact steps, reversal included |
| E2.13b | `device` cutout and the centring placement, no-cutout fallback | placement tests; emulator and S24 |
| E2.13c | Shape geometry: rounded boxes, concave joins, a minimal home frame fixture for the docked island | pixel checks of the joins at rest and in motion |
| E2.14a | The island floating and docked, at rest (the layout's "Island at rest" prop) | screenshots, both themes |
| E2.14b | `media` adapter and the live media state | state fixtures |
| E2.14c | Arrivals and the ranking policy: notification, charging; Samsung's charging and cast chips still covered (`StockBarCover`), privacy indicators visible | fixtures; S24 with a real charge and a cast |
| E2.14d | Decode text | frames at exact steps; its clock idles |
| E2.14e | Surface progress: transition tracks bound to the shade's progress (the island following a pull), hidden-by-app and transient show, `BarHost` routing the shade's calls to the KDL component | frames at intermediate progress and on reversal; emulator pulls; S24 frame stats |
| E2.15 | Fixtures and goldens: the old bar's parity suite and the island's | `diff.py` on both |
| E2.16 | Hot reload keeping state | reload test |
| E2.17 | Exit on the S24: the island over apps and home, pulls and idle | at most 1 dropped frame in 100 at 120 Hz, idle under 1 ms |
| E3 | The panel: shapes joined, the `sheet` pull from the island, summon and presentation config, sections, toggles, brightness, media, notifications with open, dismiss and actions, Today, System; power sheet; volume on the island; a second fill of one panel proving mixing; banners kept until the island carries calls and alarms | the shade's inventory as a checklist of functions; S24 frame bound; the owner |
| Settings track | the settings app in the design language; prop metadata and persistence; built-in packages' rows; engine pages | the owner on the S24 (alongside E3 only with the owner's exception to one element at a time; otherwise after E3) |
| E4 | Home frame, clock, dock, launcher from the dock, A to Z, search over the keyboard, `>` commands; the apply moment | the owner; frame bound on the launcher rise |
| E5 | Recents and app swipes: the proven host, input and physics kept; a KDL `deck` primitive replacing `DeckView`'s drawing, on the nav thread, the thread question answered by measurement (spec step 5) | the S24 frame bound, as the launcher's switcher today |

Each slice about 200 changed production lines; a slice that grows is split, not stretched.

**Verification for every step,** beyond resting screenshots: frames at exact intermediate steps and on reversal, content
changing during a pull, the home-to-app handover, rotation and a phone without a cutout, large text, denied permissions and
missing services, the shell host lost, privacy on the lock screen, reduced motion, idle clocks stopped.

## 4. Needs the owner first

1. **Weather**: an external service (Open-Meteo, no key) and the phone's location (a new permission, or a city typed once in
   settings). Which?
2. **Calendar**: `READ_CALENDAR`, a new permission, for Today's events.
3. **Volume keys**: filtering them through our accessibility service, and keeping Android's own volume panel away. Measured
   first on the S24; if Android's panel cannot be kept away, the island only follows it.
4. **The island's live media bars**: real audio levels need `RECORD_AUDIO` (Android's Visualizer). Without it, the bars move
   with playback only, not the sound. Which?
5. **Power actions** from the shell: restart and power off as shell uid, verified on the S24 before E3 promises them.
6. **Settings alongside E3**: an exception to one element at a time, or after E3.

## 5. Risks

- The shape union's cost on a 120 Hz pull is unknown until measured; the fallback is separate shapes with drawn flares (as the
  Figma draft does), which look the same at rest.
- Banners: hiding the stock shade suppresses heads-up and full-screen intents. The existing banners stay active, for every shade
  fill, until the island proves equivalent handling of calls and alarms; only then does the island replace them.
- The island over apps sits where the status bar was: an app that hides the status bar (video) hides the island too (as the bar
  today), unless the owner wants otherwise.
- Samsung's charging and cast chips sit at the bar's edges, not at the camera: the island does not cover them. The existing
  covers (`StockBarCover`) stay until a replacement is proved; Android's privacy indicators always show.
