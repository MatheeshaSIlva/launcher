# T-010: E2.13b, the `device` source and placement on the camera cutout

- **Step:** E2.13b · **Lead:** claude · **Opened:** 2026-11-12 · **Status:** PROPOSED
- **Read first:** `docs/DEFAULT_LAYOUT_PLAN.md` §1 (the island's row: "cutout-aware placement"), §2 ("Cutout. From the
  `device` source (cutout insets, as the spec names them), with a placement that centres on it, and a no-cutout fallback"),
  §3 (E2.13b's exit: placement tests; emulator and S24); `docs/ENGINE_SPEC.md` Sources (`device`: model, screen metrics,
  cutout insets, fold state, dpi; no capability) and Surfaces (placements measured from the display cutout);
  `app/.../statusbar/StatusBarView.kt` `centreLine()` and `cameraLeft()` (the rule as built); `engine/.../ui/ComponentView.kt`
  (the root placed on `centreY`); `engine/.../source/Sources.kt`, `SystemSources.kt` (how a source is written); D-067, D-092.

## 1. Proposal (lead)

**Goal.** A component can be placed on the phone's camera: centred on the cutout when there is one at the top, on a
defined fallback when there is none, kept right through rotation and display changes; and any component can read the
screen's metrics and the cutout through the spec's `device` source. E2.14a's island stands on this.

**Approach.** Three parts, about 200 production lines each at most.

1. **`device` source (codex).** `engine/.../source/DeviceSource.kt`: a `Device` value read member by member, all lengths in
   px typed as px (`Value.Num(v, "px")`), screen coordinates of the display:
   `width`, `height`, `density`, `rotation` (0, 90, 180, 270), `status-height` (the top system-bar inset),
   `cutout` (bool: a cutout on the top edge), `cutout-left`, `cutout-top`, `cutout-right`, `cutout-bottom`,
   `cutout-centre-x`, `cutout-centre-y` (all null without one), `model`. Capability `none` (the spec's). A `DisplayInfo`
   interface between it and Android (the app's: `Display.getCutout()` `boundingRectTop`, the window metrics, the top inset;
   a fake in tests), updates from `DisplayManager.DisplayListener` and configuration changes on the given handler, cold,
   conflated, no repeats, as every source. Model only from `Build.MODEL` (no identifiers beyond that).
2. **The placement (claude).** A root-only attribute `place`: `"window"` (today's: left 0, the centre line from the host's
   `centreY`) or `"cutout"`. With `"cutout"`, `ComponentView` places the root's horizontal centre on the cutout's centre and
   its centre line on the cutout's centre line, in the window's px (screen coordinates less the view's place on screen,
   read at layout); the same whole-pixel-and-fraction split the centre line has today, horizontally too. **Fallback** when
   no cutout is on the top edge (none; a side cutout in landscape; one outside the status band): the window's horizontal
   centre, and StatusBarView's own rule for the line: the cutout's centre if it lies between 20 % and 80 % of the status
   height, else 52 % of it. A pure function (`CutoutPlacement`) does the arithmetic, so the rule is tested on the JVM. A
   change of the device value places the root again (the 13a-1 place transition carries it if the root declares one).
   The model side (codex): `place` in the grammar, root only, refused elsewhere with a span; a root with `place="cutout"`
   must declare `uses { source device }` (validator, with a reason).
3. **The bar stays on StatusBarView's line for now (claude).** The KDL bar keeps reading `bar.timeBaseline` (its parity
   test against StatusBarView, D-092); a JVM test pins `CutoutPlacement`'s line to StatusBarView's numbers so E2.14a can
   move the bar onto `place="cutout"` without a pixel's change.

**Verification.**
- JVM: `CutoutPlacement` for no cutout, a centred hole, a corner cutout, a wide notch, a side cutout in landscape, a cutout
  outside the band, a view not at the screen's origin; the `device` source with a fake `DisplayInfo` (values, updates, no
  repeats, unregistered on cancel); grammar refusals with spans.
- Emulator (Android 17): the cutout emulation overlays (none, punch hole, corner, double, tall:
  `cmd overlay enable-exclusive --category com.android.internal.display.cutout.emulation.*`), portrait and landscape: a
  fixture component (a box, `place="cutout"`) captured on both paths, its centre within 0.25 px of the cutout's centre (or
  of the fallback), and placed again within a frame of an overlay change. Pass: every case within 0.25 px.
- S24 (later, the owner's go): the punch hole, portrait and landscape, from the same fixture. Not part of this slice's merge.

**Risks and open questions.**
- The cutout in the window's coordinates depends on the window's place on screen; overlay windows here are full-display at
  the origin (`LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`), but a component in an activity window is offset by its insets:
  handled by reading the view's place on screen at layout (StatusBarView does the same).
- `Display.getCutout()` is API 29+ (our floor): fine. Foldables' inner and outer displays: the source follows the view's
  display, not the default one.
- Nothing here needs the owner: no new permission (the cutout and metrics need none), no new dependency.

## 2. Discussion

### codex — discuss, 2026-11-11T21:43Z

**Agree with the direction; changes needed before agreement.** This is a source-and-code review (*judged*); no builds, emulator checks or phone measurements run.

1. **Define the source accurately.** `ENGINE_SPEC.md:39,329` requires cutout **insets**, while the proposed `cutout-left/top/right/bottom` describe a bounding rectangle. Expose safe insets separately and name the rectangle unambiguously. Keep physical top-cutout presence separate from placement eligibility: an out-of-band cutout should remain readable. `density` is unitless; lengths are px. Explicitly defer fold state and dpi rather than describe this as the complete spec source.

2. **Resolve display versus window metrics and updates.** Window bounds are not necessarily display bounds ([Android Display documentation](https://developer.android.com/reference/android/view/Display)). Specify one coordinate space for width, height and cutout geometry, with an API-29 route. Explain how `DisplayInfo` follows the attached view’s display, handles display removal/migration, and updates when window insets change without a display/configuration event. Define `status-height` independently of bar visibility so hiding the stock bar does not collapse the fallback ([WindowInsets documentation](https://developer.android.com/reference/android/view/WindowInsets)).

3. **Pin the fallback precisely.** `StatusBarView.kt:958–966` accepts the **view-local** cutout centre only when `cy > 0.2 × barHeight && cy < 0.8 × barHeight`; otherwise it uses `0.52 × barHeight`. Include exact 20%/80% boundary tests and distinguish `barHeight` from the full host height. The proposal should explicitly state whether an out-of-band top cutout also loses its horizontal anchor. For the fixture box, test geometric centring; for text/rows, preserve the engine’s centre-line semantics (`ComponentView.kt:130`).

4. **Avoid translated-coordinate feedback.** Reading `getLocationOnScreen()` at layout is insufficient by itself: `CLAUDE.md:932` records the existing 13 px error caused by translation. Define the resting origin used for conversion and test a translated/offset host. Placement must also refresh when **only** device geometry changes: today the binder callback settles expression dependencies (`ComponentView.kt:110`), and `applied()` only sends snapshot notifications (`:267`).

5. **Agree with literal, root-only `place`, defaulting to `"window"`, and the explicit `uses device` requirement.** Cover invalid values, bindings, nested nodes, fallback roots and variant overrides; `Validator.kt:35–38` visits those through different paths. This component anchor is additional to the spec’s **surface summon zones**, which remain E3 (`ENGINE_SPEC.md:164,380`; `LAUNCHER_AUDIT.md:110`).

6. **Tighten verification and ownership.** Keep the proposed JVM/emulator matrix; add source initial-delivery/race cleanup, display filtering, device-only relayout, default-placement regression, and interrupted/reduced-motion placement. Define “both paths” as software and RenderNode. Measure settled position within 0.25 px; a declared spring should **retarget**, not finish, within the update frame. Name the app adapter, wiring and proof-runner files in the split.

S24 deferral must remain an explicit outstanding exit check: `DEFAULT_LAYOUT_PLAN.md:70` requires emulator **and S24**, and protocol §1 prevents marking this DONE before owner sign-off. The unchanged bar parity path is appropriate under D-092; matching its vertical line alone does not prove a later full-bar switch to horizontal cutout centring.

### claude — 2026-11-12, round two

All six taken; the plan as it now stands:

1. **Source.** Members, screen px unless said: `width`, `height`, `density` (unitless), `rotation`, `status-height`,
   `safe-left`, `safe-top`, `safe-right`, `safe-bottom` (the cutout's safe insets, 0 without one), `cutout` (a cutout on
   the top edge exists, whatever its place), `cutout-rect-left`, `cutout-rect-top`, `cutout-rect-right`,
   `cutout-rect-bottom`, `cutout-centre-x`, `cutout-centre-y` (its bounding rectangle on the top edge; null without one),
   `model`. Placement eligibility is not a member: it is `CutoutPlacement`'s rule. Fold state and dpi are deferred (named
   in `docs/KDL_SOURCES` or the source's doc as later), not claimed.
2. **One space, following the view's display.** The display's own logical space: on API 30+ `WindowManager`
   `maximumWindowMetrics` of the view's display context, on 29 `Display.getRealSize`; the cutout from that display's
   `getCutout()` in the same space. The app's `DisplayInfo` is bound to the attached view's `display` (rebound at each
   attach; a removed display delivers no cutout until the view is attached again). Updates: `DisplayListener` for that
   display id, configuration changes, and the view's window insets (chained through the view's own
   `onApplyWindowInsets`, nothing consumed). `status-height`: `getInsetsIgnoringVisibility(statusBars())` on 30+, the
   stable inset on 29: hiding the stock bar does not collapse it.
3. **Fallback, exactly.** The line: the cutout's centre in the window's px if `0.2 × status-height < cy < 0.8 ×
   status-height` (strict, as `StatusBarView.kt:963`), else `0.52 × status-height`, with `status-height` the bar height (not
   the host's). The horizontal anchor stays on a top cutout whatever its band (StatusBarView's `cameraLeft` uses any
   non-empty top cutout); only without a top cutout is it the window's centre. Tests at exactly 20 % and 80 %. The fixture
   box is centred geometrically (its centre line is its middle); text and rows keep the engine's centre-line meaning.
4. **Resting origin, device-only relayout.** The conversion uses the `ComponentView`'s own view's place on screen less
   that view's own translation (the root's motion lives inside the composition, never on that view), read at layout; a
   proof with the host offset by a margin and translated. The `device` value the placement reads is held in Compose state
   by `ComponentView` (from the source's collector), so a geometry-only change lays the root out again without touching
   bindings; a test that changes only the device value and sees the root placed again.
5. **`place`** as you list: literal, root only, `"window"` default, `uses device` required for `"cutout"`; invalid values,
   bindings, nested nodes, fallback roots and variants covered. The spec's summon zones stay E3; this is a component
   anchor.
6. **Verification added:** source initial delivery and cancel cleanup, display filtering, device-only relayout, `"window"`
   unchanged (every existing proof), placement interrupted (a device change mid-motion retargets a declared place spring,
   continuous) and reduced motion (lands at once). "Both paths" = software and RenderNode. Settled within 0.25 px. Files
   named: the app's adapter `app/.../DeviceDisplayInfo.kt` and its registration in `SystemSources.all` (claude, as it needs
   the view), the proof `engine/src/androidTest/.../ui/PlacementProof.kt` and its mode in `AdbProofInstrumentation.kt`
   (claude). S24 stays an explicit outstanding exit check: T-010 is not DONE before the owner's sign-off on the S24; it may
   merge once both agents sign and the gate passes, as REPORTED with the S24 open.

### codex — discuss, 2026-11-11T21:45Z

**Most concerns are resolved; one coordinate correction remains before agreement.** This is *judged* from source and documentation; no builds or device measurements run.

- **Agree:** separate safe insets and cutout rectangles, unitless density, explicit deferrals, strict 20%/80% boundaries, retaining the horizontal anchor for an out-of-band top cutout, root-only literal `place`, device-only relayout, and the expanded motion/proof matrix. These preserve `StatusBarView.kt:937–966` and the engine’s centre-line semantics. S24 remains an outstanding exit check.
- **Fix the resting-origin calculation** (`T-010:101–103`). `getLocationOnScreen()` returns rounded integer coordinates; subtracting the view’s fractional translation afterward cannot recover its resting origin. For resting x=100 and translation=0.4, it yields `100 − 0.4 = 99.6`: a 0.4 px error, exceeding the proposed 0.25 px bound. Android rounds after applying the view/ancestor transforms ([AOSP View.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/view/View.java)). Derive the resting origin before rounding, or from an untransformed reference; add positive and negative fractional-translation proofs alongside the offset-host case. This directly addresses `CLAUDE.md:932`.
- **Reconcile the split before work.** `T-010:112` assigns `SystemSources.all` registration to Claude; `:123` assigns `SystemSources.kt` to Codex. Keep that file with Codex and inject `DisplayInfo`; Claude owns app adapter creation and wiring. Explicitly add `DeviceDisplayInfo.kt`, the wiring file, and `AdbProofInstrumentation.kt` to Claude’s row. `KdlBar.kt:52–53` currently creates sources before `ComponentView`, so the view-binding lifecycle needs a named wiring point.

No other approach objection remains; the fractional-origin correction needs resolving before I sign AGREE.

### claude — 2026-11-12, round three

- **Resting origin (agreed, changed):** never from `getLocationOnScreen`. The resting origin is the window's place on
  screen (`WindowManager.LayoutParams`/`getLocationOnScreen` of the window's root view, whose window position is whole
  pixels) plus the view's layout place inside the window, summed through its ancestors from their `left`/`top` (whole
  pixels) less their scroll, with every view's translation and scale left out: it is where the view rests, exactly, in
  integers. A proof places the host offset by a margin and translated by +0.4 and −0.4 px (and an ancestor translated), and
  checks the root lands on the cutout within 0.25 px of where it lands untranslated, plus the translation.
- **Split (agreed):** `SystemSources.kt` stays yours, with the `DisplayInfo` injected (`SystemSources.all(context, handler,
  display: DisplayInfo)`, or an overload; your choice of shape). Mine: the app's `app/.../DeviceDisplayInfo.kt` (made by
  the caller, bound to the component's view with `bind(view)` right after the `ComponentView` is made: the wiring point is
  `KdlBar.make`, and the proof's own harness), `KdlBar.kt` (wiring only; the bar keeps `place="window"`),
  `PlacementProof.kt` and `AdbProofInstrumentation.kt`. The table in §4 is updated with this.

### codex — discuss, 2026-11-11T21:47Z

**Agree with round three and the reconciled §4 split.** *Judged* from the thread and repository source; no builds or device measurements run.

- The integer resting origin resolves my fractional-translation objection: exclude host and ancestor transforms before converting the cutout into local coordinates. Preserve one implementation invariant: the window reference must itself be untransformed; a root-view `getLocationOnScreen()` shortcut is valid only under that invariant. This addresses the feedback error recorded in `CLAUDE.md:932`.
- Keep **both** proof assertions: absolute settled placement within 0.25 px of the expected anchor, and displacement from the untranslated result by the applied ±0.4 px translation, including the ancestor case. Relative comparisons alone could miss a shared origin error.
- The split resolves the ownership conflict and construction order at `KdlBar.kt:52–53`: Codex owns `SystemSources.kt` and injected `DisplayInfo`; Claude owns adapter creation, `bind(view)`, caller wiring and instrumentation.
- No further approach objection. Before work, consolidate the agreed revisions into the currently empty §3, as protocol §2 requires. Emulator proofs, the gate, and owner sign-off on the S24 remain outstanding.

codex: AGREE approach — integer resting coordinates excluding transforms, the reconciled ownership split, and the retained absolute-placement and motion proofs.

## 3. Agreed approach

The proposal with the three rounds' changes:
- **`device` source:** `width`, `height`, `density` (unitless), `rotation`, `status-height` (visibility ignored: 30+
  `getInsetsIgnoringVisibility(statusBars())`, 29 the stable inset), `safe-left/top/right/bottom` (the cutout's safe
  insets), `cutout` (a top-edge cutout exists), `cutout-rect-left/top/right/bottom`, `cutout-centre-x/y` (null without
  one), `model`; lengths in px of the display's own logical space (30+ the view's display's maximum window metrics, 29
  `getRealSize`), the cutout from that display. Fold state and dpi deferred, named as later. A `DisplayInfo` injected into
  `SystemSources.all`; the app's adapter follows the attached view's display (rebound at each attach), updated by the
  display listener, configuration changes and the view's window insets (nothing consumed).
- **`place`:** literal, root only, `"window"` (default, today's) or `"cutout"` (requires `uses { source device }`).
  `CutoutPlacement` in screen space: horizontally on a top cutout's centre whatever its band, else the window's centre;
  the line on the cutout's centre strictly inside 20-80 % of `status-height`, else 52 % of it; then into the view's px by
  its **resting origin**: the untransformed window's place on screen plus integer layout places through the ancestors,
  every translation and scale excluded. Placed again on a device-only change (Compose state, not bindings) and on the
  view's move; the fraction carried on the root's own translation, both axes. The spec's summon zones stay E3.
- **The bar** keeps `place="window"` and StatusBarView's line (D-092's parity); a JVM test pins the rule's numbers.
- **Verification:** JVM (placement cases with the band's edges, an offset view; the source with a fake: initial delivery,
  updates, no repeats, cancel cleanup, display filtering; grammar refusals over every validator path); emulator, software
  and RenderNode paths, every cutout overlay in portrait and landscape: absolute settled placement within 0.25 px of the
  expected anchor, and with the host offset and translated ±0.4 px (and an ancestor translated) displaced by exactly that
  translation; a device change mid-motion retargets a declared place spring; reduced motion lands at once; every existing
  proof unchanged. S24 is an outstanding exit check: not DONE before the owner's sign-off there.

- `codex: AGREE approach — integer resting coordinates excluding transforms, the reconciled ownership split, and the retained absolute-placement and motion proofs.`

## 4. Split

| Sub-task | Owner | Files owned | Branch | Verified by |
| --- | --- | --- | --- | --- |
| `device` source and `DisplayInfo` interface, its tests; `SystemSources.kt` taking the injected `DisplayInfo`; `place` grammar and the `device` requirement | codex | new `engine/.../source/DeviceSource.kt`, `SystemSources.kt`, new `engine/src/test/.../source/DeviceSourceTest.kt`; `model/Primitives.kt`, `Validator.kt` (`place` only), a new `model/PlaceTest.kt`; `docs/KDL_*` | `team/codex/e2-13b-cutout` | JVM tests, §7 checklist |
| `CutoutPlacement`, the root's placement and resting origin in `ComponentView`, the app's `DeviceDisplayInfo` and its wiring, proofs | claude | new `engine/.../ui/CutoutPlacement.kt`, `ComponentView.kt`, new `engine/src/test/.../ui/CutoutPlacementTest.kt`; new `app/.../DeviceDisplayInfo.kt`, `app/.../statusbar/KdlBar.kt` (wiring only); androidTest `PlacementProof.kt`, `AdbProofInstrumentation.kt` | `team/claude/e2-13b-cutout` | JVM tests, emulator overlays both paths |

## 5. Reports

### codex — work, 2026-11-11T21:58Z

- **Commits:** none; files left modified on `team/codex/e2-13b-cutout`, following the lead’s specific instruction. Nothing pushed.
- **Changed:** `DeviceSource`, the requested interfaces and members, optional registration, root-only literal `place`, device declaration checks, tests and two KDL docs.
- **Verified — measured:** 74 source/model JVM tests passed, including 13 new tests; zero failures/errors. `git diff --check` clean. Both workspace Gradle daemons stopped.
- **Not verified:** full gate (explicitly excluded), Android adapter/display filtering, placement proofs, emulator or S24.
- **§7 security — judged:** caller authentication N/A (internal validation); existing input bounds unchanged; no reflection or privileged execution paths; capability remains `none`, with `uses device` enforced; no capture persistence; no secret handling changes; refusal paths tested.
- **Surprises:** initial grammar fixtures emitted an empty statement; corrected and rerun successfully. No separate known-source registry found.

Automatic command-policy review rejected recursive cleanup of the temporary Gradle cache; it supplied only “blocked by policy.” `.tmp/` remains untracked and must be excluded from the commit.

### claude — report (`d9c66ab`; Codex's `5964189`)

- **Changed:** `CutoutPlacement` (the rule in screen space, then the view's px), `ComponentView` (`place="cutout"`: the root's
  horizontal centre and line from the `device` source, both axes split into a whole pixel and a fraction on the root's
  translation; the resting origin from the window's place plus integer layout places, translations and scales left out;
  placed again on a device-only change through Compose state and on the view's move), `HostNode.snapPlace`, the display
  adapter `DeviceDisplayInfo` and its wiring in `KdlBar` (the bar stays `place="window"`).
- **Moved from §4, with the reason:** the adapter is `engine/.../source/DeviceDisplayInfo.kt`, not under `app/`: the
  engine's on-device proof must run it, and it is platform code like the rest of `:engine`'s `dev.launcher.app.*`.
- **Two bugs the emulator found, fixed:** (1) the status bar's height read 0: an insets listener of ours on the
  `ComposeView` is replaced by Compose's own; the adapter now reads the view's `rootWindowInsets` (ignoring visibility)
  when asked. (2) A root with a place transition slid in from 273 px above on its first frame: the first layout ran
  before the window was in its place, and the move from there was taken for motion. A change of the resting origin (the
  host laid out or moved) now places the root at once (`snapPlace`); only a change of the device moves it on its
  transition.
- **Measured, JVM:** `CutoutPlacementTest` 8 (no cutout, centred hole, corner, wide notch, out of band both sides, strict
  20 % and 80 %, an offset view, landscape fallback); all engine unit tests pass, with Codex's 13 new ones.
- **Measured, emulator (`component-placement`, RenderNode and software paths):** overlays none, hole, corner, double and
  tall, upright and turned: the box's centre within 0.003 px of the rule's point in every case (turned: no top cutout,
  the fallback); with the host offset 37 px, and the view translated +0.4 and −0.4 px and an ancestor +0.4, moved by the
  translation within 0.05 px. A moved cutout on a `place 200ms` transition, turned back at frame 6: 31 frames within
  0.003 px of the movers on both paths, the clock stopped; with motion reduced (animator scale 0) at once.
  `component-motion` unchanged (PASS).
- **Not verified:** the S24 (outstanding exit check, the owner's); a display moved between screens (no second display).
- **Note:** a fallback root may declare `place` (Codex's grammar allows it) but the runtime places from the view's root
  only; no component has a fallback yet. Parked for when one does.

## 6. Cross-check

### claude — review of Codex's share (`5964189`), 2026-11-12

- note: `place` is a common attribute (`oneOf`, not bindable), refused on children and variant overrides, and `"cutout"`
  without `uses { source device }` refused with a hint. `DeviceSource` copies the cutout array before delivering and
  closes the register race with a second `trySend`. Nothing to fix.
- question: a fallback root with `place="cutout"` passes the validator but is not placed on the camera by the runtime
  (my report's note); fine to leave until a fallback exists?

### codex — review, 2026-11-11T22:29Z

*Judged* review of `d9c66ab` and its report, using the lead’s repository. *Measured:* commit diff whitespace check passed. No files changed; no gate, emulator or phone run. **Fixes remain before sign-off.**

- **fix — `engine/.../source/DeviceDisplayInfo.kt:33,59,82`: inset changes are never published.** Reading `rootWindowInsets` in `current()` does not notify the source. Attach can publish `statusHeight=0` before insets arrive; subsequent inset-only changes also have no trigger. Add a Compose-compatible, non-consuming inset notification and test late initial insets plus an inset-only update on an existing host. Android documents that [root insets require attachment](https://developer.android.com/reference/android/view/View#getRootWindowInsets()).
- **fix — `engine/.../source/DeviceDisplayInfo.kt:37`: configuration callbacks bypass `handler`.** The shade owns this adapter on `nav-ui`, but component callbacks arrive through the context’s configuration dispatch. Calling `changed()` directly reads the view and races `last`/`listeners` against registration, cancellation and display callbacks. Marshal configuration notifications onto the owning handler; test delivery and cancellation there. See [Android’s callback threading model](https://developer.android.com/guide/components/processes-and-threads).
- **fix — `engine/.../ui/ComponentView.kt:137,189`: ancestor/window moves lack invalidation.** The listener observes only the ComposeView’s own bounds. Moving its parent with unchanged child bounds, scrolling an ancestor, or moving the window can change `restingOrigin()` without changing those bounds or device state. The component then moves away from its screen anchor until another measure happens. Observe resting-origin changes through the hierarchy/window and test an ancestor-only layout move and scroll.
- **fix — `engine/.../source/DeviceDisplayInfo.kt:29,51,57`: display following is incomplete.** Removal clears `display`, but `current()` immediately retries `view.display` or the default display, violating the agreed “no cutout until reattached” behavior. Density also comes from the constructor context rather than the bound display. Preserve explicit removed/unavailable state and derive density from the selected display’s resources; cover removal, rebinding and differing display densities.
- **question — `engine/.../ui/ComponentView.kt:178,163`; `engine/.../ui/Host.kt:213`: should every origin change cancel existing device motion?** Equal origins do **not** trigger snapping every layout. However, `windowWidth` counts as an origin change, and any detected change snaps both place tracks—including an already-running device transition or authored translation. A resize/move during that transition can therefore jump to its target. Separate initial placement correction from preserving motion across coordinate changes, or explicitly justify and test this cancellation policy.
- **note — `engine/.../source/DeviceDisplayInfo.kt:45,73`; `app/.../statusbar/KdlBar.kt:59`: cleanup is partial, but an indefinite leak is not established.** Cancelling the device collector unregisters display/config callbacks. The attach listener remains on the view, retaining the adapter/context while that view remains retained; the cycle alone is collectible. Add explicit unbind/dispose cleanup through `addOnDispose`, including disposal before attachment.
- **note — `engine/.../androidTest/.../ui/PlacementProof.kt:73,109,124,279`: the proof’s limits matter.** Each overlay/rotation case creates a fresh session after waiting, so it does not prove live Android updates or the one-frame update bound. Translation checks capture only the GPU result and do not force relayout afterward. Retargeting uses a fake display and a timed ease, not a spring. Ancestor layout moves, simultaneous origin/device changes, multi-window, secondary displays and startup frames remain unproved.
- **note — `engine/.../model/Validator.kt:37`; `engine/.../ui/ComponentView.kt:73` — answer to §6:** deferring fallback-specific placement is reasonable because fallback rendering itself is absent from this runtime. Record that limitation explicitly and implement placement from the **active** root when fallback rendering arrives. Also correct `docs/KDL_PLACEMENT.md:21`: it still describes the eligibility test as view-local, whereas the implementation now tests the screen-space centre.
- **note — §7 security, judged:** caller checks: N/A, no new external production entry point; input bounds/reflection: unchanged; privileged execution paths: none added in production; capabilities: public `device`, declaration enforced; other-app capture persistence: none introduced—the proof captures its own fixture; secrets: untouched; refusal coverage: existing grammar refusal tests inspected, not rerun.

### claude — reply to the review, 2026-11-12

All five fixes taken; the question answered by changing the policy. Measured on the emulator (`component-placement`,
both paths): every overlay upright and turned within 0.003 px, now also with an ancestor moved by layout (its own bounds
unchanged) and scrolled, live, the box staying on its anchor; the moving-cutout flight with its host moved 29 px at frame
3 and turned back at frame 6: 31 frames within 0.003 px of the movers; reduced motion at once; `component-motion` PASS;
all engine unit tests pass.

- **Insets (fixed):** the adapter adds a global layout listener on the view's window while attached (insets arriving or
  changing lay the window out) and publishes when its state changes; nothing consumed, Compose's own listener untouched.
  Not shown separately on the emulator (its insets arrive before the first frame); judged.
- **Threads (fixed):** configuration callbacks are posted to the adapter's handler.
- **Ancestors, scrolls, the window (fixed):** `ComponentView` follows its window's global layout and scroll changes while
  attached and places the root again when its resting origin changed (own bounds, an ancestor, a scroll, the window).
- **Display following (fixed):** a removed display leaves no display (no cutout, nothing measured) until the view is
  attached again; density from the selected display's own context. A second display could not be shown on this emulator.
- **Origin policy (question, changed):** no snapping any more. When the resting origin moves, the root's motion is
  rebased (`NodeMotion.rebase`): its last layout place is re-expressed in the new coordinates, so the next placement's
  shift is only a real change of anchor; a device motion already running goes on (the frame-3 move above), and a host's
  first layout or move never slides the root. A window-width change alone is a change of anchor (the fallback's centre)
  and moves on the transition.
- **Cleanup (note, done):** `DeviceDisplayInfo.unbind`, called from `KdlBar`'s dispose (and safe before attaching).
- **Proof limits (note):** agreed and recorded: a live overlay or rotation change recreates the proof's activity, so live
  Android display updates are shown through the fake display only; secondary displays, multi-window and startup frames
  stay unproved. The proof also waits until the display reports the overlay before measuring (the tall overlay once read
  late) and draws the software path with a margin (a box near the edge was cut by the view's bounds there, not on screen).
- **§6 answer (agreed):** the fallback limitation is written in `docs/KDL_PLACEMENT.md`, with the doc's view-local wording
  corrected to the screen-space test.

## 7. Sign-off
