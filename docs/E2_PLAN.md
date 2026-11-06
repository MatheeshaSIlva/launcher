# E2 plan: the interpreter core and a KDL status bar

_2026-11-06, branch `engine` at a0d48f9 (+ the uncommitted probe removal). Read-only plan for review; no code yet._

**What E2 is.** ENGINE_SPEC's build step 2 with the audit's exit: *a KDL status bar renders inside the existing shade
window, pixel-matched against `StatusBarView`, inside the frame budget on the reference device*; plus the step-2 additions
(expression bindings, fixtures, hot reload keeping state) and D-070's deferred capability registry and callback-to-flow
adapters, now with the bar as their consumer. "Measured" below means read from the code or a device; "est." is an estimate.

## a. The status bar as the acceptance test

`statusbar/StatusBarView.kt` is 1,588 lines (measured), one `View` drawing everything with Canvas. It reads 6 colour
tokens (`comp.statusbar.battery-low/saver/charging-color`, `capsule-color`, `capsule-label-color`, `sharing-color`), the
label colours through `Appearance`, 13 motion roles (`motion.statusbar.*`: appear, leave, move, level, roll, tone, show,
hide, lock, charge-open/content/close/shimmer) and the unit (`Scale.unitPx`). Its geometry is **about 40 literal constants**
(`SIDE_PT 34`, `TIME_PT 15`, `ICON_PT 14.5`, the charging capsule's 15 numbers...), the inventory's B-114. Its sources are
its own: a battery/power/airplane/DND broadcast receiver, two `ConnectivityManager` callbacks (Wi-Fi RSSI, VPN), two
`TelephonyCallback`s (signal and service; display info for 5G/LTE), `Notifs.barIcons` with its listener, a minute ticker.
It is driven from outside by 12 calls (`setPanel`, `setDark`, `setLocked`, `setSharing`, `setShadeHeader`, `setHiddenByApp`,
`showTransient`, `setRowAlpha`, `setSuppressed`, `chargeMomentWanted`, `pictureOnHome`, `preview`; 15 call sites in
`GestureNav` and `Shade`). It lives in the shade's window (`root.addView(bar, ...)`, `Shade.kt:325`) on **gesture nav's
`nav-ui` HandlerThread**, not the main thread (measured: `Shade(ctx, wm, nav, ...)`, `HandlerThread("nav-ui")`).

| Part | States and motion StatusBarView draws | In E2's exit | Why not, if not |
| --- | --- | --- | --- |
| Time | 15 pt semibold `tnum`, centred on the camera's line; digits roll at the minute | yes, roll included | |
| DND moon | slot appears and leaves | yes | |
| Notification icons | up to 3 app icons from `Notifs.barIcons`, overflow dot, loaded off-thread | yes | |
| Cellular | 0-4 bars; No SIM, No Service text; airplane glyph | yes | |
| Network type | 5G/LTE label when not on Wi-Fi, rolls on change | yes | |
| Wi-Fi | 1-3 arcs, glides between levels | yes | |
| VPN | badge | yes | |
| Battery | body with level fill and the percentage inside; low (red), Low Power (yellow), charging (green) colour blends; bolt slot | yes | |
| Slots | every element a slot with a place spring and a presence spring: arrive grows, leave shrinks, others make room | yes | the bar's signature motion |
| Tone | white/black content crossfaded (`motion.statusbar.tone`) | yes | |
| Hidden by app | fades and slides up (`show`/`hide`), transient show on swipe | yes | |
| NC pull | left group fades with the pull | yes | first use of "transitions bound to surface progress" |
| CC pull | right group glides into CC's status row, "100%" appears | yes (est. the hardest part: row geometry comes from CC) | |
| Lock screen | the time fades (`lockK`) | deferred to E3 | belongs to the shade's lock handling, ported with the shade |
| Pixel shade header | `setShadeHeader`: the bar becomes QS's header row | deferred to E3 | the Pixel shade is E3's second theme |
| Charging moment | capsule led out of the battery over One UI's chip: ~300 lines of bespoke fold/lead/shine maths, timed against One UI's log | deferred | a One UI cover, driven by the shell's log watch; port it as a custom primitive once `canvas` exists |
| Sharing capsule | red capsule over One UI's cast chip | deferred | same: One UI cover, shell log watch |
| Landscape | the bar full width over a landscape app | follows the layout (no special case in the view) | |

**Measurement (the exit).**
- *Pixels*: ours-vs-ours, the M4 method: the same fixture state rendered by `StatusBarView` and by the KDL bar, both drawn
  off-screen into PNGs at the device's size and unit (StatusBarView already draws itself into bitmaps for
  `charge-frames`), then `tools/fidelity/diff.py`. One TEST hook renders both for every fixture state (section d), so it
  runs on the emulator and the S24 alike and never captures the screen. Pass = diff.py PASS on every state; the target is
  pixel-identical where both draw the same glyphs with the same Paint (question 8).
- *Frames*: on the S24, `scenario_shade.sh` (window `LauncherStatusBar`) with the KDL bar shown against StatusBarView,
  interleaved as in E1's exit (`tools/e1_s24.sh`'s pattern), plus idle cost (gfxinfo over a minute of minute ticks,
  bound < 1 ms/frame). Bound from the spec: ≤ 1 dropped frame per 100 on a pull at 120 Hz. **The Redmi (the spec's
  reference device) is not connected; E2's frame numbers are the S24's, and the Redmi run is owed.**

## b. The interpreter

**Where.** `:engine`, new package `dev.launcher.engine` (the E1 files keep `dev.launcher.app.*` until the rename step).
`kdl/` (parser, document), `model/` (component model, validation), `expr/` (expressions), `ui/` (primitives, host),
`source/` (registry, flows). Parser, validator, expressions and the layout maths are plain Kotlin with unit tests on the JVM.

**KDL 2 parser, our own** (BENCH_STEP0 §2). Hand-written recursive descent over the text, no regex, one pass:
identifiers, quoted / raw (`#"..."#`) / multi-line strings, numbers (decimal, hex, octal, binary, keeping the raw text),
`#true #false #null #inf #-inf #nan`, type annotations `(dp)48`, properties, arguments, children, `/-` slashdash on nodes,
arguments, properties and blocks, line and block comments, line continuations. Every node, argument and property carries a
`Span(start, end, line, col)`. Errors stop at the first one with kdl4j's wording as the floor ("Missing closing brace at the
end of children list at 3:1"). Checked against kdl-org/kdl's official `tests/test_cases` (input and expected output,
v2), vendored as test resources (licence to check: question 12). Performance floor: kdl4j's 0.52 ms for the 99-line bar
on the S24 (measured in step 0); ours is expected faster (no hybrid pass), measured in the slice.

**Document model.** `KdlDocument(nodes)`, `KdlNode(name, type?, args, props: LinkedHashMap, children, span)`,
`KdlValue` sealed (String, Number with raw text, Bool, Null, each with type annotation and span). Immutable.

**Component model and validation with reasons.** `ComponentDef(name, version, props: List<PropDef>, uses: Uses,
state: List<StateDef>, view: ViewNode, variants, fallback)`, built from the document by `ComponentReader`, which returns
the definition or a list of `Diagnostic(span, code, message, hint)`: unknown node or attribute with a nearest-name hint,
wrong type, a `$state` not declared, a source not in `uses`, a token that does not exist in the resolver
(`Design.has(key)`), a primitive used outside its allowed parents. Every primitive declares its attribute schema in Kotlin
(name, type, default, bindable); the validator and the editor's docs both read it.

**Tokens.** `@comp.statusbar.battery-low-color` and every `@sys.*`/`@ref.*` resolve through the existing three-tier
resolver (`Design`, with element themes through `TokenParts`). A token attribute binds to a `Key` once, at load; its
value is read at draw time, so a theme or appearance change redraws without recomposing (the launcher's rule already).
Props are `@name` too; a prop and a token cannot share a name (the validator refuses it).

**Expressions** (step 2 addition). One syntax in attributes: `"{expr}"` inside a string, mixed with text
(`"{battery.level}%"`), or a whole value (`width="{battery.level * 0.27}pt"`). Grammar: literals (number with unit,
string, bool), `$state`, `@prop` / `@token`, source paths (`battery.level`, `network.wifi.level`), `+ - * / %`,
comparisons, `&& || !`, `?:`, member access, and formatter calls as pipes (`{time.now | date:'H:mm'}`,
`| percent`, `| pad:2`). Pratt parser to an AST, compiled once to Kotlin closures over an environment; each compiled
expression knows its dependency set (which state keys, sources and tokens it reads), so a source change re-evaluates only
the bindings that read it. No Lua round-trip, no reflection, no allocation per evaluation for numbers (est.). The spec
writes three syntaxes (`{expr}`, `{{$t | date:...}}`, `@thickness*3`): question 5.

**Primitives, in the order the bar needs them.** The spec's first twelve are `box row column stack text icon image glass
list grid slider toggle`; the bar uses five of them and one more:

| Order | Primitive | The bar's use | Notes |
| --- | --- | --- | --- |
| 1 | `row` | the left and right groups | gap, alignment on the camera's centre line |
| 2 | `stack` | the bar's two sides over each other; the battery body over its fill | |
| 3 | `text` | time, type label, percentage, No SIM | `roll` attribute (the launcher's `RollingText`), `tnum`, weight |
| 4 | `box` | battery body (radius, stroke), fill (width bound to the level), the capsule later | clip, radius, stroke, fill |
| 5 | `icon` | Wi-Fi, cell, moon, bolt, VPN, airplane, notification icons | a symbol from an icon set (`IconSets`, vector paths, crossfade on change), or a `Drawable` from a source (app icons) |
| 6 | `spacer` | room the slots make | not in the twelve; trivial |

`column`, `image`, `glass`, `list`, `grid`, `slider`, `toggle` come after E2's exit, in E3 with the shade that needs them
(question 11). The bar's hand-drawn glyphs (`drawWifi`, `drawCell`, `drawBattery`, `drawMoon`, `drawBolt`) become a
`statusbar` symbol set (vector paths per level), drawn by the existing `SymbolDrawer`.

**Layout model.** Compose owns the tree and the layout: each primitive is a Compose `Layout` with an engine
`MeasurePolicy` (row: main-axis gap, cross alignment including `centre-line`, fixed or wrap sizes; stack: alignment,
overlap). The maths is a pure function (measure inputs to placements) tested on the JVM. Units: `pt` (the launcher's
`Scale.unitPx`), `dp`, `px`, fractions.

**What a primitive is in code** (decided, with fact 7 in mind). One Compose `Layout` node per primitive plus one engine
`Modifier.Node` implementing `DrawModifierNode` that draws the primitive's **own `RenderNode`**, recorded with
`android.graphics.Canvas` (text through Android's paint, as `LinePaints` taught: many small composables per row cost
~3 ms a row). Why not composables drawing with Compose's `DrawScope`: the bench's `nodes` mode (Compose holding draw nodes
over engine RenderNodes) measured p50 3.1 ms per frame against 11.6 for Compose layers and equalled the View deck;
fact 7's costs (accessibility re-reads, `SnapshotStateMap` churn, alpha layers per item, seeking springs through state)
all come from moving things through Compose state, which this design never does. Rules from the launcher's Compose
pitfalls, enforced in the host: nothing that moves is read in composition; one `MutableState` per binding (never a map);
semantics cleared on the bar (`clearAndSetSemantics` with one label); no alpha layer per node.

**Gesture-driven values bypass Compose state.** The shade's pull progress (`cc`, `nc`) and every spring (slot place,
presence, level glide, tone) live in engine `MotionValue`s on the bar's thread. A value change writes the affected
RenderNodes' properties (translation, alpha, scale) directly and asks for a frame; it never writes Compose state and never
recomposes. Content changes (a new time string, a new icon) re-record that primitive's RenderNode only. Transitions bound
to surface progress use the ported `motion/transition` tracks (`PanelPlayer`) reading `nc`/`cc` progress, the same tracks
StatusBarView's pull behaviour is re-expressed in.

**Hosting inside the shade's window.** A `ComposeView` added to the shade's root `FrameLayout` beside `StatusBarView`
(same z-order slot), on the `nav-ui` thread, with its own lifecycle and saved-state owner (the bench's `ThreadOwner`), a
`Snapshot.sendApplyNotifications()` after each state write on that thread (step 0: otherwise changes wait for the main
thread). **This needs Compose ≥ 1.11.4**: 1.7.5's accessibility delegate measures the view on the main thread while another
thread draws it and crashes whenever an accessibility service is on, and ours always is (BENCH_STEP0 §4). First-frame
rules: the first composition in a process took 151 ms on the S24 (step 0), so the bar is composed and drawn once, hidden,
when the shade window attaches, never inside a frame where it must show; the window's layout params are never changed
for it (pitfall: 10-50 ms relayouts). A dev switch (`SETUP --es option statusbar-impl=kdl`, est.) shows the KDL bar
instead of StatusBarView; StatusBarView stays the default until parity (question 2). The 12 control calls are routed to
whichever bar shows through one interface (`BarHost`), so GestureNav and Shade change once.

## c. Capability registry and flow adapters, with the bar as consumer

| Source | Mechanism (today, in StatusBarView) | Shell (AIDL) | E2 |
| --- | --- | --- | --- |
| `time` | minute ticker, `Calendar` | no | yes |
| `battery` | sticky `ACTION_BATTERY_CHANGED` receiver; power save; | no | yes |
| `network` | 2 `ConnectivityManager` callbacks, 2 `TelephonyCallback`s, airplane, SIM state | no (READ_PHONE_STATE granted once through Shizuku) | yes |
| `display.dnd` | `NotificationManager` interruption filter | no | yes |
| `notifications.icons` | `Notifs.barIcons` + listener | no | yes |
| `oneui.charging`, `oneui.casting` | the shell's `awaitLog` / `watchLog` (ILogWatch) | **yes** | deferred with the charging and sharing capsules |

**Finding: none of the bar's E2 sources needs the shell.** The AIDL-backed part of the registry gets its first consumer
only with the deferred One UI covers (or E3's shade: tasks, capture, toggles). So E2 builds the registry's shape and its
app-side sources; the AIDL capabilities follow with their first consumer (question 3).

Shape: `Sources.get(name): Source<T>`, where `Source<T>` is a cold `Flow<T>` (callbackFlow over the Android callback,
`conflate()`d, `distinctUntilChanged()`), plus `current()` for the first frame. Each source declares its capability
(`none`, `network.read`, `notifications.read`) so the validator checks a component's `uses`. Collection happens on the
bar's thread (`Handler(navLooper).asCoroutineDispatcher()`), coalesced to one delivery per frame (Choreographer), written
into the bound states. Values are plain data (`Battery(level, charging, powerSave)`), never Android objects, except the
notification icons, delivered as `Drawable`s loaded off-thread (the projection for Lua is step 6's). The readers move out
of StatusBarView into the sources; StatusBarView keeps its own copies until it is deleted (no behaviour change for it).
Shell capabilities, when they come: one registry entry per IShellService method group, behind `ShizukuLink.service`, with
an `available` flow (connected or not) and every call off the drawing threads.

## d. Fixtures, hot reload, the dev hook

- **Fixtures** (`engine/src/main/assets/kdl/fixtures/statusbar/*.kdl`, est. 14 states): time 9:41 and 23:59, battery
  100 / 50 / 15 / charging / Low Power, Wi-Fi 1-3, cell 0-4, No SIM, No Service, airplane, 5G and LTE, VPN, DND, 0 / 2 /
  4 notification icons, light and dark content, NC 0.5, CC 0.5. A fixture is a set of source values (`battery level=15
  charging=#false`). A `FixtureSources` replaces the registry for a render, so the same fixture drives the unit tests
  (layout placements), the pixel harness and the editor later.
- **Pixel harness**: `TEST_STATUS --es do kdl-fixtures` renders each fixture through both bars into
  `files/dev-out/statusbar/<state>/{view,kdl}.png` (+ `.done`); `tools/fidelity/diff.py` compares the two folders.
  StatusBarView needs a fixture entry point beside `preview()` (which takes only busy, level, charging and icons): a small
  edit in it, the one change to it in E2.
- **Hot reload** keeps state: `KDL_RELOAD --es path components/status-bar.kdl` (adb only, senders holding DUMP, as
  DESIGN_RELOAD) re-reads the file from `files/kdl/` (pushed through the existing inbox, `DESIGN_RELOAD --es files
  import`), validates it, logs each diagnostic as `path:line:col message`, and on success swaps the component, keeping
  every `state` value whose name and type are unchanged; on failure the running bar stays.
- **Where KDL lives**: built-ins in `engine/src/main/assets/kdl/`, overrides in `files/kdl/` (wins, as themes do).

## e. Slices

Each ≤ 200 changed production lines (tests apart), its exit `scripts/check.sh` plus what is listed; logic slices ship
unit tests. The first vertical slice (a static KDL bar showing the time in the shade window) is slice 6; it cannot come
earlier than the Compose upgrade (slices 0a-0b).

| # | Slice | Exit beyond check.sh | Phone behaviour |
| --- | --- | --- | --- |
| 0a | Compose 1.7.5 → 1.11.4 (BOM or pinned), Kotlin decision (question 1), app builds | settings app frame stats on the S24 interleaved old/new (`scenario_more.sh`'s settings parts, est.) | yes: every Compose screen; measured |
| 0b | fixes the upgrade forces (deprecations, `LocalLifecycleOwner`, activity-compose) | same | as 0a |
| 1 | KDL lexer and parser core: nodes, args, props, children, strings, numbers, comments, slashdash, spans | parser tests (est. 60 cases) | none |
| 2 | KDL rest: raw and multi-line strings, escapes, keywords, type annotations, continuations; the official suite | the kdl-org suite green | none |
| 3 | Component model and validator with reasons; attribute schemas | validator tests (each diagnostic with its span) | none |
| 4 | Expressions: lexer, Pratt parser, compiled closures, dependency sets, formatters (date, number, percent, pad) | expression tests | none |
| 5 | Layout model (row, stack, spacer) as pure maths + Compose `Layout` host; the RenderNode draw node | layout tests | none |
| 6 | **First vertical slice**: `ComposeView` in the shade window on `nav-ui` (ThreadOwner, warm composition, semantics cleared), `text` primitive, a static `status-bar.kdl` with the time; dev switch | emulator screenshot; gfxinfo idle; S24 frame stats on a pull | only with the dev switch on |
| 7 | Sources registry + `time`, `battery` flows; `uses` binding; per-frame coalescing; live time and percentage | flow tests (fake callbacks) | dev switch only |
| 8 | Token binding (`@comp.*`, `@sys.*`, fonts) read at draw; theme and tone change redraw | token tests; emulator light/dark | dev switch only |
| 9 | `icon` primitive over IconSets; the `statusbar` symbol set (wifi, cell, moon, bolt, vpn, airplane from the draw code) | symbol set test; PNG diff of the glyphs | dev switch only |
| 10 | Battery as `box` + fill + text with expressions; state colours | first pixel harness run on battery states | dev switch only |
| 11 | `network`, `dnd`, power-save sources extracted from StatusBarView's readers | source tests | dev switch only |
| 12 | `notifications.icons` source, `each` over icons, max 3 + overflow dot | | dev switch only |
| 13 | Motion: `spring` / presence and place springs on slots; `roll` text | slot motion recorded frame by frame (`charge-frames`'s method) | dev switch only |
| 14 | Surface progress: NC fade and CC glide as transition tracks; hidden-by-app show/hide; `BarHost` routing the 12 calls | emulator pulls; S24 frame stats | dev switch only |
| 15 | Fixtures (14 states) and the pixel harness for both bars; StatusBarView's fixture entry | diff.py PASS on every state (the exit's pixel half) | none |
| 16 | Hot reload keeping state, `KDL_RELOAD` hook | reload test on the emulator | adb only |
| 17 | E2 exit run on the S24: KDL bar against StatusBarView, interleaved, pulls and idle | ≤1/100 dropped at 120 Hz, idle < 1 ms | the owner's go |

**Estimate: 19 slices** (17 + the two upgrade slices), est. 2,500-3,000 production lines; the charging and sharing capsules,
lock fade and the Pixel header are E3's.

## f. Questions for the reviewer and the owner

1. **Compose version.** 1.11.4 (the first with the off-main-thread semantics fix) built in step 0 on Kotlin 2.0.21 only with
   `-Xskip-metadata-version-check`, so its metadata needs a newer Kotlin (est. 2.2, unverified; 1.12 needs AGP 9.1 and SDK
   37). Options: (a) Kotlin 2.2.x + Compose 1.11.4 cleanly, which moves the Kotlin plugin, the Compose compiler plugin and
   possibly AGP compatibility; (b) the skip flag as a stopgap. The upgrade touches every Compose screen (the settings app:
   11,400 lines), `activity-compose`, the libraries' baseline profile (the `.dm`) and the APK's size. I recommend (a),
   measured on the settings' scenarios before and after, as its own two slices.
2. Keep StatusBarView in parallel until parity (the audit says yes): I recommend yes, behind a dev switch, default
   StatusBarView, deleted only after slice 17 passes.
3. The AIDL capability registry has no consumer in E2's exit (finding above): build its shape now and the shell entries
   with their first consumer, or pull the charging/sharing capsules into E2 to give it one?
4. KDL subset vs full KDL 2 for the first parser: I recommend full KDL 2 in two slices (1-2) against the official suite; the
   grammar is small and a subset would need a second migration of every file.
5. One expression syntax: the spec writes `{expr}`, `{{$t | date:...}}` and `@size*3`. I recommend `{expr}` with pipes for
   formatters; the spec's example (invalid KDL per step 0) is rewritten in it. A spec change: the owner's.
6. The spec says the `status` surface is "not available as a replacement", while E2's exit is a status bar. I read the
   bar as a component of the `shade` surface (the row it draws), not a `status` fill: wording for the spec.
7. Geometry: StatusBarView's ~40 literals become the KDL bar's defaults. Props (per component, set by a layout) or
   `comp.statusbar.*` tokens (set by a theme)? I recommend tokens for what a theme restyles (sizes, gaps, colours: B-114)
   and props for structure (which slots, max icons).
8. Pixel target: diff.py's defaults (mean < 2, < 2 % pixels over 12) or pixel-identical? Text drawn with the same Paint
   should match exactly; springs mid-motion are compared at rest states only.
9. The deferrals: charging moment, sharing capsule, lock fade, Pixel header to E3. Agreed?
10. The bar on `nav-ui`: Compose off the main thread is the plan's premise (step 0 measured it for the deck). Any reason
    to move the shade's window to the main thread instead? I see none: everything else there is on `nav-ui`.
11. Step 2's "first twelve primitives": E2 builds the six the bar uses and leaves `column image glass list grid slider
    toggle` to E3 with their first consumer (the same rule as the registry). A change of the build order's wording.
12. The kdl-org test suite's licence for vendoring into the repo (unverified).
13. Package: new code in `dev.launcher.engine.*` now, while E1's files stay `dev.launcher.app.*` until the rename step?

## Decisions on the questions (reviewing session, 2026-11-06)

Decided here: **2** yes, StatusBarView stays the default behind a dev switch until slice 17 passes. **3** the registry's
shape and the app-side sources now; the AIDL entries come with their first consumer (E3), the D-070 rule. **4** full
KDL 2 in slices 1-2 against the official suite. **7** tokens for what a theme restyles (sizes, gaps, colours; B-114),
props for structure. **8** pixel-identical wherever both bars draw the same glyphs with the same Paint (text, solid
shapes); where a path-drawn symbol replaces a Canvas arc, diff.py's defaults, each such state named in the result.
**9** the four deferrals to E3, agreed; the charging moment's One UI cover is E3's first shell consumer. **10** the bar
stays on `nav-ui`. **11** the six primitives the bar uses now, the other six with their first consumer; the build
order's "first twelve" is read that way (logged). **12** check the suite's licence before vendoring; if it is CC-BY-SA,
the test files sit in their own folder with the licence and attribution, never mixed with our sources. **13** new code
in `dev.launcher.engine.*`.
Order: slices 1-5 start now (no Compose in them); 0a-0b wait for the owner's answer to question 1; slice 6 after both.
For the owner: **1** (Compose 1.11.4 with Kotlin 2.2.x, or the skip flag as a stopgap; recommended (a), measured on the
settings before and after), **5** (one expression syntax, `{expr}` with pipes: a spec change, together with the
example's fix from step 0) and **6** (the bar as a component of the shade surface: spec wording).
