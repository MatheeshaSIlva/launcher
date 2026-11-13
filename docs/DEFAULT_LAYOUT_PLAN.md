# The default layout: build plan

Draft 3 of this plan, 2026-11-11, lead: draft 2 (agreed with Codex, T-008) updated for the design's draft 4 (D-098). The design is draft 4 in Figma (https://www.figma.com/design/7E43AzlCpFNqhUkApcstZA, page "Draft 4"),
approved by the owner (D-093, D-098); the language is the owner's design system (D-092). This plan says what the engine must
gain to ship it, in what order, and what needs the owner first. It does not change the spec's build order: it fills steps 2
to 5 with this layout's parts.

## 1. What the drafts need, screen by screen

Numbers in brackets are draft 4's screens; screens draft 4 does not redraw (volume on the island, the launcher typing and its
commands, settings, the apply moment) keep draft 2's numbers and design.

| Screen | Surface | Engine needs | Launcher code to reuse |
| --- | --- | --- | --- |
| Island, at rest and live (states, volume) | `shade`, its `status` slot | cutout-aware placement; one floating shape everywhere (no docked form, D-098); width and height springs; states from sources; decode text | the KDL bar (slices 6-12), `StatusBarView`'s cutout maths, charging and sharing watchers |
| Panel grown from the island (03-07) | `shade` | the island morphing into the panel (one rounded shape changing size and radius); finger-driven progress (`sheet` preset); a stack of pages swiped sideways (page 1: time, Controls with four toggles and brightness, the playing song, the two newest notifications without buttons; Today; System) with the row-or-page swipe rule (D-098); Controls and Notifications each growing into a full panel of their own, the full Notifications panel with no pages; a slider; toggles (white when on, a terracotta tap animation); a reusable paging and gesture-arbitration mechanism (E4 and E5 use it too); input and action dispatch; opaque surfaces | `ControlState`, `Controls` (torch, timer, media volume), `Notifs` (open, cancel, send), `Media` (controls) |
| Power (10) | `recents` | the recents blob morphing into Power and back; Lock at a tap; Restart and Power off held until the fill completes (a release before that cancels); actions `power.lock`, `power.restart`, `power.off` | the shell service (verify `svc power` as shell uid on the S24 before E5 promises them) |
| Volume on the island (11, 12) | `shade` | global volume key interception; per-stream volume and output device (`audio`, `media.volume`) | `Controls` reads and sets media volume; `Shade` forwards volume keys while its focus holder is active. New: global interception, other streams, output devices |
| Home (01) | `home` | the wallpaper edge to edge (no frame, D-098); a clock widget; an opaque dock of pinned apps | `home/`, `Wallpaper`, `apps/` |
| Launcher (11; typing 13, 14) | `drawer` | grows out of the dock with the finger, opaque; most-used; A to Z with a fast index; search above the keyboard; `>` commands | `drawer/`, the app index, `LaunchStats` (the launcher's own frequency and recency, not device-wide usage), IME insets handling |
| Recents (08, 09) and app swipes | `recents` | the solid gesture pill morphing into an opaque blob and back; app icons with names swiped sideways, the selected one centred and ringed in `signal`; the screen behind showing the selected app's picture (on the GPU only, never kept); tap opens, a flick up closes that app, clear all; the power button; up = home, hold = recents, along the bottom = switch | `GestureNav`, the card window, `switcher/` (host, input, physics and the snapshot path kept and re-checked for the new interactions; the drawing rewritten as a KDL `deck` primitive, ENGINE_SPEC's, presented as the strip) |
| Settings (7) | the app itself, not a surface | the settings app in the design language; rows from the active packages' props | `settings/` is scrapped (D-092); `SettingsActivity` shell only |
| Apply moment (17) | all | an engine-owned transition between layouts | `motion/transition` |
| Lock (draft 1) | not available | none | Android owns it |

## 2. New engine pieces

Names follow the spec's Sources and Actions; nothing here creates a competing name.

- **Shapes.** Rounded boxes whose size and corner radius morph (the island into the panel, the pill into recents and
  Power), as engine geometry: paths reusable by any component, bounded shape count and drawing area, GPU path warmed, clipping
  and hit regions defined, behaviour below Android 13 defined. Draft 4 docks nothing, so concave joins are no longer needed
  (D-098); a smooth union (AGSL) only if a later design asks for it.
- **Opaque surfaces, no glass, no shadows** (owner, 2026-11-13, D-118: "we'll scrap the blur idea and go back to opaque
  background for the whole theme"). What floats over content (the panel, recents, Power, the launcher, the dock, banners) is
  an opaque surface from the design language: `surface` for panels, sheets and banners, `surface-2` for the controls and
  cards inside them, separated by surface steps and never by a shadow. The island and the pill are solid black. Text is
  `ink`, `ink-2` and `muted` by role. No live blur and no backdrop picture are drawn for the default layout (the engine's
  glass tiers stay for creators' layouts).
- **`signal` (terracotta), one meaning** (D-098): live or selected only: levels, the current page, the selected app, the
  power-off hold, the playing song's button, the next event, a nearly full disk, the notification count, the clock's minutes,
  and the tap animation on a control tile (active tiles themselves stay white). Each step's inventory lists its uses.
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
| E2.13c | Shape geometry: rounded boxes morphing size and radius; the island's floating shape | pixel checks at rest and at exact steps of a morph, reversal included |
| E2.14a | The island at rest, floating on home and over apps (the layout's "Island at rest" prop) | screenshots, both themes |
| E2.14b | `media` adapter and the live media state | state fixtures |
| E2.14c | Arrivals and the ranking policy: notification, charging; Samsung's charging and cast chips still covered (`StockBarCover`), privacy indicators visible | fixtures; S24 with a real charge and a cast |
| E2.14d | Decode text | frames at exact steps; its clock idles |
| E2.14e | Surface progress: transition tracks bound to the shade's progress (the island following a pull), hidden-by-app and transient show, `BarHost` routing the shade's calls to the KDL component | frames at intermediate progress and on reversal; emulator pulls; S24 frame stats |
| E2.15 | Fixtures and goldens: the old bar's parity suite and the island's | `diff.py` on both |
| E2.16 | Hot reload keeping state | reload test |
| E2.17 | Exit on the S24: the island over apps and home, pulls and idle | at most 1 dropped frame in 100 at 120 Hz, idle under 1 ms |
| E3 | The panel: the island morphing into it, the `sheet` pull, summon and presentation config, the paging and gesture-arbitration mechanism, sections growing into their own panels and back, toggles with the tap animation, brightness, media, notifications with open, dismiss and actions, Today, System; opaque surfaces; volume on the island; a second fill of one panel proving mixing; banners kept until the island carries calls and alarms | the shade's inventory as a checklist of functions; the swipe rule's tests (direction decided after 12 dp; sideways from a notification row moves and clears it; sideways elsewhere changes page; a pull that drifts sideways still closes; the full Notifications panel never pages; a section opened and closed mid-motion reverses); S24 frame bound; the owner |
| Settings track | the settings app in the design language; prop metadata and persistence; built-in packages' rows; engine pages | the owner on the S24, after E3 (D-096) |
| E4 | Home (no frame), clock, opaque dock, the launcher rising from the dock, A to Z, search over the keyboard, `>` commands; the apply moment | the owner; S24 frame bound on the launcher rise |
| E5 | Recents and Power: the solid pill morphing into the opaque blob and back, the strip (the KDL `deck` primitive), the selected app's picture behind, tap to open, flick up to close, clear all, Power with Lock and its holds; the proven host, input and physics kept and re-checked for these interactions, the switcher's drawing replaced on the nav thread, the thread question answered by measurement (spec step 5) | the handover pill to blob and back, interrupted mid-way, with no doubled pill and no step in brightness (frames at exact steps); the S24 frame bound, as the launcher's switcher today |

Each slice about 200 changed production lines; a slice that grows is split, not stretched.

**Verification for every step,** beyond resting screenshots: frames at exact intermediate steps and on reversal, content
changing during a pull, the home-to-app handover, rotation and a phone without a cutout, large text, denied permissions and
missing services, the shell host lost, privacy on the lock screen, reduced motion, idle clocks stopped.

## 4. Needs the owner first

Answered: weather from Open-Meteo with the phone's location, asked when Today first needs it, a city setting when denied;
`READ_CALENDAR`, a row saying so when denied (D-096); the island's music bars move with playback only, no audio read (D-097);
the settings app after E3 (D-096). Still open:

1. **Volume keys**: filtering them through our accessibility service, and keeping Android's own volume panel away. Measured
   first on the S24; if Android's panel cannot be kept away, the island only follows it. Before E3's volume.
2. **Power actions** from the shell: restart and power off as shell uid, verified on the S24 before E5 promises them.
3. **Draft 4's questions** (D-098), before E5 (the first two) and E4 (the third): does a long press on the pill open Power
   directly; what recents shows behind it when opened from home (proposal: the most recent app); does a swipe up from the
   pill on home open the launcher.

## 5. Risks

- Banners: hiding the stock shade suppresses heads-up and full-screen intents. The existing banners stay active, for every shade
  fill, until the island proves equivalent handling of calls and alarms; only then does the island replace them.
- The island over apps sits where the status bar was: an app that hides the status bar (video) hides the island too (as the bar
  today), unless the owner wants otherwise.
- Samsung's charging and cast chips sit at the bar's edges, not at the camera: the island does not cover them. The existing
  covers (`StockBarCover`) stay until a replacement is proved; Android's privacy indicators always show.
