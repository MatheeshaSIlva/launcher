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

## 3. Agreed approach

## 4. Split

| Sub-task | Owner | Files owned | Branch | Verified by |
| --- | --- | --- | --- | --- |
| `device` source, `DisplayInfo`, its tests; `place` grammar and the `device` requirement | codex | new `engine/.../source/DeviceSource.kt`, `SystemSources.kt` (registering it), new `engine/src/test/.../source/DeviceSourceTest.kt`; `model/Primitives.kt`, `Validator.kt` (`place` only), `MotionGrammarTest.kt` or a new `PlaceTest.kt`; `docs/KDL_*` | `team/codex/e2-13b-cutout` | JVM tests, §7 checklist |
| `CutoutPlacement`, the root's placement in `ComponentView`, the app's `DisplayInfo`, proofs | claude | new `engine/.../ui/CutoutPlacement.kt`, `ComponentView.kt`, new `engine/src/test/.../ui/CutoutPlacementTest.kt`, androidTest `PlacementProof.kt` | `team/claude/e2-13b-cutout` | JVM tests, emulator overlays both paths |

## 5. Reports

## 6. Cross-check

## 7. Sign-off
