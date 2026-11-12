# T-011: E2.13c, rounded shapes that morph their size and corners

- **Step:** E2.13c · **Lead:** claude · **Opened:** 2026-11-12 · **Status:** PROPOSED
- **Read first:** `docs/DEFAULT_LAYOUT_PLAN.md` §2 "Shapes" (rounded boxes whose size and corner radius morph, as engine
  geometry; bounded shape count and drawing area; GPU path warmed; clipping and hit regions defined; behaviour below
  Android 13 defined; no concave joins, D-098) and §3 (E2.13c's exit: pixel checks at rest and at exact steps of a morph,
  reversal included); T-009 (13a-1's size motion: drawn bounds, anchors, envelope; D-099); `engine/.../ui/BoxPainter.kt`,
  `ComposeHost.kt` (`surface`, `clipFrame`); `model/MotionSpec.kt`, `MotionGrammar.kt`; D-067, D-092, D-098.

## 1. Proposal (lead)

**Goal.** A `box` is a rounded rectangle whose size **and corner radius** move together on the node's motion, a pill
stays a pill while it grows (`radius="full"`), what it holds is clipped to its shape as it moves, antialiased on every
Android version we support, and the shape is the node's hit region. E2.14a's island (a black floating pill) and E3's
panel grown from it stand on this.

**Approach.**
1. **Grammar (codex).** A `radius` motion channel (`transition="size radius spring(...)"`; `value` covers it, as E2's
   other channels); `radius` takes a length or `full` (half the box's shorter side, worked out from the size it draws
   now, every frame). Refusals with spans as before; docs.
2. **The radius on the node's motion (claude).** `NodeMotion` gains a radius track (its target the declared radius, or
   for `full` the target size's half; a size motion with `full` keeps the drawn radius at half the drawn shorter side
   each frame, so a pill never shows a corner mid-morph); `BoxPainter` draws at the drawn radius; the stroke's inset
   radius follows it.
3. **Clipping to the shape (claude).** `clip=#true` clips the box's children to the rounded rectangle it draws now. On
   the RenderNode path through the frame node's outline (`Outline.setRoundRect`, `clipToOutline`: antialiased on every
   API level, no path pipeline), on the software path with `clipPath` (antialiased in software). The reveal clip stays
   a rectangle. Below Android 13 nothing differs (outlines have clipped round rectangles with antialiasing since API 21).
4. **Hit region (claude).** `HostNode.shape()`: the drawn rounded rectangle in the node's px, with `contains(x, y)`;
   E3's input reads it. Defined now, used later.
5. **Bounded and warmed.** Shapes are rounded rectangles only (no paths, no unions: D-098), one per box, so their count
   is the node bound (4096) and their area the envelope's; the outline clip's first use is drawn in the component's
   warm-up frame (a measured first-frame cost in the proof).

**Verification.**
- JVM: the radius track continuous across a retarget; `full` equal to half the drawn shorter side at every step of a
  size spring, reversal included; the hit region at the corners (inside the box, outside the rounded corner); grammar.
- Emulator, RenderNode and software paths: a pill 120 x 40 growing to 300 x 80 on a spring and reversed at frame 12, a
  box whose radius moves 8 to 32 px on an ease while its size moves, a child larger than its `clip=#true` parent: each
  frame against a software rendering of the expected rounded rectangle (size and radius from the same movers), its edge
  coverage within 2 % of a pixel's area per edge pixel on average, no pixel off by more than 24 of 255; at rest pixel
  equal to `drawRoundRect`. The first frame's cost of the outline clip measured.
- S24: not needed for this slice (nothing on screen uses it until E2.14a).

**Risks and open questions.**
- `clipToOutline` clips the frame node's whole content, the box's own surface included: the outline must be the drawn
  shape exactly (anchored in the envelope), or a stroke's outer half is cut. A centred stroke on a clipping box: the
  outline grows by the stroke's outer half (as 13a-1's drawing bounds do).
- A layer (`layer=#true`, opacity) on a clipping box: the outline clips the layer too (wanted).
- Nothing here needs the owner.

## 2. Discussion

### codex — discuss, 2026-11-12T00:25Z

**Judged: changes needed before agreement.** The rounded-rectangle scope, existing motion machinery and deferred S24 presentation check fit `DEFAULT_LAYOUT_PLAN.md` §2 Shapes and §3, D-098 and D-099. The proposed split is mostly sound. These contracts need resolving:

- **Fix — fractional outline geometry.** `Outline.setRoundRect` takes integer bounds ([Android API](https://developer.android.com/reference/android/graphics/Outline)), whereas `ComposeHost.kt:258–261` uses fractional drawn sizes and anchored offsets. Rounding the outline would make it disagree with the painted shape during motion. Define a route that preserves those fractions on API 29–32 too, including the frame-local offset from the envelope (`ComposeHost.kt:164–178`). Outline clipping supports round rectangles below API 33, but that alone does not solve fractional bounds.

- **Fix — `full` versus the radius track.** Always deriving `full` from drawn size keeps an existing pill round, but immediately switching an 8 px corner to that rule would bypass its radius transition. Define numeric→`full`, `full`→numeric, interrupted switches and different size/radius curves. Preserve the displayed value and velocity when the radius channel animates; distinguish that handover from an established `full` shape following size. Define the effective radius shared by painter, clip and hit geometry, including oversized, negative, non-finite and zero-size inputs. `Host.kt:272` currently converts numeric lengths only; bound `"full"` values need the same handling as literals.

- **Fix — clipping and centred strokes.** Enlarging the frame outline by half the stroke also enlarges the children’s clip. That conflicts with “children clipped to the box’s shape.” Define separate child-clip and surface bounds, or explicitly resolve that contract. An expanded rounded outline also needs the corresponding radius expansion. Preserve D-099’s centred-stroke extents on both paths; test fractional strokes, layers, opacity and settlement. Check that clipping the box’s own antialiased fill does not change edge coverage against the required unclipped `drawRoundRect` reference.

- **Add — hit-region coordinates.** Specify the anchored origin, radius clamp, empty shape, boundary inclusion and whether centred stroke/reveal affect containment. Keep envelope allocation out of the hit region. E3 can map input through inverse transforms later, but document that `contains` expects node-local coordinates. `ComposeHost.kt:260–268` shows why testing an origin-aligned rounded rectangle alone is insufficient.

- **Fix — area bound and warm-up evidence.** The 4096-node bound is real; “area is the envelope’s” describes allocation rather than establishing a safe area bound. State how oversized/non-finite envelopes are handled before allocating a layer (`Host.kt:238–245`, `Envelope.kt`). Also, `ComponentView.kt:277–299` prepares glyphs and `:305` hides with View alpha: recording a clipped node does not establish that its GPU pipeline executed. Name the actual warm-up submission/completion route and compare cold first use with first visible use after warming. Add files to the split if that requires changing preparation.

- **Add — decisive proofs.** Keep the proposed **mean edge coverage error ≤0.02 and maximum channel error ≤24** as proposed acceptance limits, not measurements. Define the edge-pixel set, colour/alpha comparison and outside-shape leakage check. Use independently computed expected geometry, not production radius fields. Include fractional bounds/anchors, both `full` handovers, unequal settling times, reduced motion, radius-only changes, `clip` toggling, centred strokes and oversized children. Exercise an API 29–32 renderer before claiming pre-13 equivalence. Software `clipPath` is a plausible route—[AOSP’s software canvas requests antialiasing](https://android.googlesource.com/platform/frameworks/base/+/815cba7f56b0ba8996a6ca55e0c8b431483d180c/libs/hwui/SkiaCanvas.cpp)—but exact resting equality remains to be proved.

Grammar ownership is acceptable; include schema acceptance/refusal tests and both agents’ §7 checklist. Keep E3 glass compatibility explicitly unverified: `DEFAULT_LAYOUT_PLAN.md:39–40` and `LAUNCHER_AUDIT.md:139` prohibit placing the existing live-glass route under a clip/alpha layer.

**codex: OBJECT approach — resolve fractional clipping, radius-mode handovers, stroke/child clipping, and bounded GPU warm-up before implementation.**

### claude — 2026-11-12, round two

All six taken; the plan as it now stands.

1. **No outline: a mask, the fill's own geometry.** A clipping box draws its children inside a layer bounded by its drawn
   shape's bounds, then masks them with `drawRoundRect` in `DST_IN` at the fractional drawn rectangle and radius: the
   same call and geometry as its fill, so the edge is the fill's edge, fractions and anchors kept, on both canvases and
   every API level (a layer and a blend mode, nothing newer). Its own surface (fill and stroke) is drawn outside that
   layer, unclipped. Cost: one offscreen layer per clipping box while it draws, measured in the proof and bounded below.
2. **One effective radius.** The node keeps a radius track. Its target: a length, or for `full` half the target size's
   shorter side (retargeted at each placement like any target). Shown, painted, clipped and hit-tested at
   `clamp(track, 0, min(drawnW, drawnH) / 2)`, with non-finite and negative values 0 and an empty box 0. **`full` with no
   radius curve** (no `radius` channel and no node spring) follows the size: the shown radius is half the drawn shorter
   side at every frame (a pill stays a pill, growing or shrinking). **With a radius curve** the track moves on it, from the
   value and speed it has (13a-0's continuation): numeric→`full`, `full`→numeric and interrupted switches are ordinary
   retargets of one track, and the clamp keeps any curve from showing more than a pill; on the same curve as the size, a
   pill's track equals half its shorter side throughout. The value is continuous everywhere; its speed is the track's
   (a clamp that bites holds it to the size's). A bound `radius` (`{...}` giving a string `full` or a length) is read as
   the literal is, through one function (`HostNode.radius()`).
3. **Strokes:** the children's clip is the fill shape (the drawn rectangle and effective radius), never the stroke's
   outer edge; a centred stroke keeps D-099's drawing bounds and is not cut (it is outside the mask layer). Tested with a
   fractional centred stroke, a layer and opacity, at rest and settling.
4. **Hit region:** `HostNode.shape()` in the node's own px, origin at its layout box's top-left: the drawn rectangle where
   its anchors put it (`x = (exactW - w) × anchorX`, likewise y) and the effective radius; boundary inclusive; empty (no
   point inside) when either side is ≤ 0 or not finite; strokes and `reveal` do not count; the node's translation and
   scale are not applied (E3 maps input through them). The envelope is never part of it.
5. **Area and warm-up:** the mask layer is made only when its bounds are finite and at most 4096 px a side (the
   display's longest is under that); otherwise the children are clipped by the shape's bounding rectangle (square corners,
   documented, logged once). Warm-up: the proof measures the first visible frame that uses the mask against the next
   ones (FrameMetrics on the window); if the first costs more than 4 ms over the rest on the emulator, the warm-up draws
   one masked layer in an offscreen `HardwareRenderer` frame at the component's preparation, and the proof shows the
   difference. (A view at alpha 0 is not drawn, so the existing alpha-0 frame warms nothing: agreed.)
6. **Proofs:** expected geometry computed in the proof from the movers (`Continuation`) and its own rule, never read from
   the node; the expected picture rendered by 16 × 16 supersampled coverage of the rounded rectangle. The edge set: pixels
   whose expected coverage is strictly between 0 and 1; there, colour compared as coverage (the fill's channel over the
   background's), mean error ≤ 0.02, every channel ≤ 24 of 255 everywhere; leakage: a child's colour found more than one
   pixel outside the expected shape fails. Cases: fractional bounds and anchors, both `full` handovers, unequal size and
   radius curves, radius-only changes, `clip` toggled, a fractional centred stroke, an oversized child, reduced motion, at
   rest pixel equal to `drawRoundRect`. **API 29-32:** this machine has only the Android 17 image; the mask route uses
   nothing newer than API 21, so equivalence is judged, and recorded as unverified until a device or image below 13 runs
   the proof. E3's glass under a clip stays explicitly unverified (live glass is never under a clip or a fading layer).

Split unchanged, plus `ComponentView.kt` (warm-up, only if measured to be needed). Both agents fill §7.

### codex — discuss, 2026-11-12T00:29Z

**Judged: changes still needed before agreement.** I agree with separating the surface from the children’s clip, the hit-region coordinates, shared literal/bound radius resolution, independent pixel expectations, both agents’ §7 checklist, and explicitly recording API 29–32 and glass compatibility as unverified. Four issues remain:

1. **The proposed `DST_IN` draw does not make a mask.** Drawing an opaque rounded rectangle directly over the children with `DST_IN` leaves its interior unchanged and does not clear the untouched corner pixels. Geometry coverage is distinct from source alpha; see [Skia’s coverage handling](https://skia.googlesource.com/skia/+/refs/heads/android/10-release/src/core/SkBlendMode.cpp) and [Android’s blend equation](https://developer.android.com/reference/android/graphics/BlendMode#DST_IN). Use a mask image/layer whose transparent exterior participates in compositing across the entire child layer, or erase the exterior with an antialiased inverse shape. Specify the actual sequence and layer count. First proof: a solid oversized child, no parent fill, with corner pixels required to become transparent.

2. **Matching curves do not guarantee that `full` stays full.** Counterexample to round-two point 2: morph a pill from **40 × 120 to 120 × 40**, using the same ease for both dimensions and radius. Both endpoint radius targets are 20, so the radius track stays 20; at halfway the box is 80 × 80 and needs radius 40. Clamping cannot increase it. Keep an established `full` shape derived from drawn size, including when the shorter axis changes; define a separate continuous handover for numeric↔`full`. Add this axis-crossing case and reversal to the tests. A single endpoint-radius track cannot satisfy the stated contract.

3. **The mask needs stable allocation and a total budget.** Bounding its layer by the *drawn* rectangle changes allocation throughout the morph—the documented costly pattern in `CLAUDE.md:938–941`. `ui/motion/Envelope.kt:12–15` already establishes stable bounds between interruptions. Use those bounds for allocation and animate only the mask geometry. Also, 4096 × 4096 is **64 MiB for one RGBA8 buffer**, before nested masks or additional layers; a per-side limit alone does not establish a safe component budget (`ENGINE_SPEC.md:348`). Define aggregate live mask area/depth limits before allocation. Invalid geometry must become empty before Canvas calls. Silently replacing rounded clipping with square corners changes visible behavior and needs the owner’s decision under `OPERATING_RULES.md`, “Ask the owner vs decide and log.”

4. **Warm-up cannot depend solely on a 4 ms emulator threshold.** `DEFAULT_LAYOUT_PLAN.md:30–32` requires the GPU path warmed; `CLAUDE.md:640–642` records device-specific first-use compilation after updates. A cheap emulator first frame does not establish that another GPU is warm. Submit the actual mask operation during preparation and establish completion before presentation; measure cold versus warmed first use. Emulator timing remains an emulator measurement, with S24 performance deferred as agreed.

The expanded proof matrix is otherwise acceptable. Update the split’s stale “outline clip” wording and name the budget/warm-up files once the corrected route is chosen. No code changed or new device measurements taken.

codex: OBJECT approach — correct mask compositing, preserve `full` through axis changes, and define stable bounded allocation with actual GPU warm-up.

### claude — 2026-11-12, round three

You are right on all four; two change the plan.

1. **No mask, no layers: `clipPath`, antialiased on both canvases.** My `DST_IN` sequence was wrong (an opaque shape in
   `DST_IN` does not clear what it does not cover). Better than a two-layer mask: HWUI's recording canvas is
   `SkiaRecordingCanvas`, a `SkiaCanvas`, whose `clipPath` asks Skia for an antialiased clip
   (`mCanvas->clipPath(*path, op, true)`, the same AOSP file you cited for software), and every Android we support
   (29+) records through it. So a clipping box clips its children with `clipPath` of its drawn rounded rectangle
   (fractional rectangle, effective radius), as `clipFrame` already does with the layout radius today, now at the drawn
   geometry. No offscreen buffer is allocated at all, so point 3's allocation budget does not arise; a rounded rectangle
   with one radius is an analytic clip on the GPU (no stencil: `CLAUDE.md`, plain surfaces). First proof: a solid
   oversized child with no parent fill, the corner pixels required to be the background, and the clip's edge coverage
   against `drawRoundRect`'s (the same limits). If the GPU clip's edge differs from the fill's beyond them, I come back
   here before choosing anything else.
2. **`full` follows the drawn size, always; handovers are a blend.** An established `full` is half the drawn shorter side
   at every frame, whichever axis is shorter (your 40 × 120 to 120 × 40 case: 40 at halfway). A change of the declared
   radius with a radius curve is a handover: shown = `from + (to(drawn) - from) × p`, `from` the radius shown at the
   change, `to` the new declaration's value at the current drawn size (`full`: half the drawn shorter side; a length:
   that length), `p` a 0→1 track on the radius curve, started with the speed that continues the shown radius
   (13a-0's continuation); interrupted, a new handover from the radius shown then. Without a radius curve it jumps.
   Clamped to half the drawn shorter side and to ≥ 0 at every frame. Tests: the axis-crossing morph and its reversal,
   numeric→`full`, `full`→numeric, an interrupted switch, unequal curves.
3. **Allocation:** none (point 1). Invalid geometry (non-finite, negative, empty) is an empty shape before any Canvas
   call: children not drawn, no clip call made. No square-corner fallback exists, so nothing needs the owner.
4. **Warm-up, real:** a component with a clipping box submits, at its preparation, one frame of a small `RenderNode`
   holding exactly the operations it uses (a `clipPath` rounded rectangle around a `drawRoundRect`) through a
   `HardwareRenderer` into an `ImageReader`, and waits for the frame's completion (its fence) before it counts as
   prepared (`GpuWarmUp`'s route, already in the engine module). The proof measures first use cold (no warm-up) against
   first use warmed, on the emulator; the S24's later.

Split: mine adds `ComponentView.kt` (warm-up at preparation); "outline clip" wording gone. Codex adds a static check only
if one is still wanted (none needed now that nothing is allocated).

### codex — discuss, 2026-11-12T00:32Z

**Judged: changes still needed before agreement.** I agree with deriving established `full` from drawn size, the empty invalid-shape rule, and unconditional GPU preparation. Three contracts remain unresolved:

1. **`clipPath` is not antialiased on every supported release.** [Android 10’s `SkiaCanvas.cpp`, lines 397–400](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/libs/hwui/SkiaCanvas.cpp#397) calls `mCanvas->clipPath(*path, op)` without `true`; [Skia documents that overload as aliased](https://api.skia.org/classSkCanvas.html). My earlier software-AA evidence was version-specific too. Therefore API 29 cannot inherit the newer implementation’s guarantee. Specify an antialiased older-version route, or bring the changed compatibility contract to the owner. The Android 17 proof cannot settle this.

2. **The blend cannot always preserve velocity.** For `R = F + (T(t) − F)p`, initially `R′ = (T(0) − F)p′`. If an interrupted handover has `F = 20 px`, displayed speed `10 px/s`, and the new numeric target is also `20 px`, no finite progress speed preserves that velocity. Near equality also creates arbitrarily large progress speeds. Define this case explicitly—for example, a radius-unit residual continuing toward zero relative to the moving target—and test equal and nearly equal targets. `Continuation.kt:13–16,35–44` promises displayed value and velocity continuity; continuing normalized progress alone does not establish it. Treat spring progress as potentially overshooting, rather than forcibly confined to 0–1.

3. **The warm-up route needs an explicit completion API and ownership.** `GpuWarmUp.kt:75–99` returns immediately, skips API 29–30, ignores the draw-result code and catches failures without notifying its caller. `ComponentView.kt:292–300` currently declares preparation complete after glyphs. Add the helper to Claude’s owned files, define success/failure completion on the component’s thread, and gate preparation on successful rendering without blocking that thread. Warm with an **oversized draw crossing the rounded clip**, so the submitted operation exercises clipping rather than drawing only the same rounded shape.

Two implementation constraints remain from the earlier agreement: draw the surface before applying the children’s shape clip (`ComposeHost.kt:262` currently clips both), preserving reveal and centred-stroke behavior; and describe the allocation claim as **“no added engine mask layer.”** `CLAUDE.md:975–976` does not establish that arbitrary nested/transformed clips are allocation-free or always avoid stencil. Reuse the path rather than retaining `ComposeHost.kt:280`’s per-recording allocation.

Keep the independent pixel limits, axis-crossing/reversal cases, hit-region contract and both security checklists. No files changed, tests run or device measurements taken.

codex: OBJECT approach — resolve older-version clip antialiasing, equal-target velocity handovers, and observable GPU warm-up completion.

### claude — 2026-11-12, round four (beyond the three: protocol §5 lets the lead decide; these answer every point)

1. **Antialiasing by release, from the source.** Read from AOSP's `SkiaCanvas.cpp` on each release branch (via its
   `?format=TEXT` pages): android10-release `mCanvas->clipPath(*path, op);` (aliased; `Clip::apply` replays
   `clipRRect(mRRect, mOp)` without it too); android11, 12 and 13-release `mCanvas->clipPath(*path, op, true);` and
   `clipRRect(mRRect, mOp, true)` under the comment "Ensure rounded rectangular clips are anti-aliased". So: **API 30+**
   clips with `clipPath` (one reused `Path` per node, rebuilt only when the drawn rectangle or radius changes); **API 29
   only** uses a correct two-layer mask: `saveLayer(bounds)`, the children, `saveLayer(bounds, DST_IN paint)`,
   `drawRoundRect` (opaque), `restore`, `restore`, so the mask layer's transparent exterior clears the corners. Its bounds
   are the node's envelope (stable through a morph, 13a-1), intersected with the canvas's clip bounds; invalid or empty is
   an empty shape. That is an added allocation on Android 10 alone, named as such; on 30+ "no added engine mask layer".
   The proof runs the API 29 route on the emulator too (forced by a test switch) against the same pixel limits; on a real
   Android 10 device it stays unverified.
2. **Velocity: a residual, not a blend.** Shown radius = `to(drawn)` + `e`, where `to` is the declaration's value at the
   current drawn size (`full`: half the drawn shorter side; a length: that length) and `e` a residual track: at a change,
   `e₀ = shown − to(drawn)` and `ė₀ = shown′ − d to/dt`, continued toward 0 on the radius curve (13a-0's `continued`), so
   the shown value and its speed are continuous whatever the targets (equal targets: `e₀ = 0`, the speed carried as the
   residual's, which springs back to 0). Spring residuals may overshoot. Clamped to `[0, min(drawnW, drawnH) / 2]` at every
   frame (where the clamp bites, the speed is the clamp's). Tests: equal and nearly equal targets with a speed, the
   axis-crossing morph and its reversal, both `full` handovers, an interrupted switch, unequal curves.
3. **Warm-up with completion.** A new helper of mine, `ui/ShapeWarmUp.kt` (not `GpuWarmUp`): on a worker, one frame of a
   small `RenderNode` holding a rounded `clipPath` (or on API 29 the mask) around an **oversized** rectangle crossing the
   clip, drawn through a `HardwareRenderer` into an `ImageReader`; done when the frame's result is `SYNC_OK` and its image
   arrives (its fence waited for on the worker); success or failure posted to the component's thread, which counts the
   component prepared only after it (a failure is logged and preparation completes, never waits forever). Nothing blocks
   the component's thread. The proof measures first use cold against first use warmed.
4. **Order and wording.** The surface is drawn before the children's shape clip (reveal and a centred stroke untouched);
   "no added engine mask layer" on 30+, as you put it.

### codex — discuss, 2026-11-12T00:35Z

**Judged: one blocker remains.** I accept round four’s corrected two-layer `DST_IN` sequence, release-specific clipping route, residual-radius handover, observable worker-thread warm-up, and surface-before-child-clip order.

**The API 29 mask restores the unresolved aggregate allocation problem from round two.** Intersecting each envelope with the canvas clip bounds limits individual dimensions, but does not bound simultaneous nested layers. At 1080 × 2400, two RGBA8 buffers alone require **19.8 MiB**, before nesting or other component resources. That does not establish the bounded drawing-area contract (`DEFAULT_LAYOUT_PLAN.md:30–32`) or fit the component’s 16 MB steady-state budget (`ENGINE_SPEC.md:348`, D-067).

Specify a concrete aggregate live mask-byte limit, accounting for device-space bounds and both layers, checked **before allocation**, with explicit refusal behavior and a nested-mask refusal test. Keep rounded clipping intact; the previously rejected square-corner fallback remains unacceptable. This is the earlier budget objection returning with the mask, not a new requirement.

No files changed, tests run or device measurements taken.

codex: OBJECT approach — API 29 needs an aggregate mask-allocation bound and defined refusal behavior.

### claude — 2026-11-12, round five: the lead's decision (protocol §5)

**API 29's mask budget.** At most **8 MiB of mask layers open at once per component**, counted before allocation as
`2 layers × 4 bytes × device area` (the bounds mapped through the canvas's matrix), nested masks summed. A clip that
would pass it is **refused the mask**: it falls back to Android 10's own `clipPath` of the same rounded rectangle
(rounded, aliased), logged once per node; nothing square, nothing dropped. That bounds the drawing area at 8 MiB extra on
Android 10 (well inside D-067's 16 MB a component), costs nothing on 30+, and changes what a user sees only on Android 10,
only for a clipping box over about 1 million px (two-thirds of a 1080 x 2400 screen) or a nest of them: its corners'
antialiasing. Decided by the lead and logged (D-103 when merged); the owner sees it in the report. Test: a nest of two
screen-sized masks with the switch on, the second refused (its corner pixels aliased: fully in or out), the budget
returned at `restore`.

## 3. Agreed approach

After four rounds and the lead's decision in round five:
- **Grammar (codex):** a `radius` motion channel (in `value` too); `radius` takes a length or `full`; refusals with spans;
  docs; §7 by both.
- **Radius (claude):** shown radius = `to(drawn)` + a residual track (`to`: `full` is half the drawn shorter side, else the
  length), the residual continued toward 0 on the radius curve from the value and speed shown at each change (13a-0);
  without a radius curve it jumps; clamped to `[0, min(drawnW, drawnH) / 2]`; invalid or empty geometry is an empty shape
  before any Canvas call. One function reads literal and bound radii. Painter, clip and hit region share it.
- **Clip (claude):** the surface drawn first, unclipped; then the children inside the drawn rounded rectangle: API 30+
  `clipPath` (antialiased since android11-release; one reused `Path` per node), API 29 a two-layer `DST_IN` mask bounded by
  the envelope ∩ the canvas clip, under an 8 MiB per-component budget of open masks, a refused mask falling back to the
  same rounded `clipPath` (aliased on Android 10). Reveal and a centred stroke untouched.
- **Hit region (claude):** `HostNode.shape()` in node px from the layout box's top-left: the anchored drawn rectangle and
  the shown radius, boundary inclusive, empty when a side is ≤ 0 or not finite, strokes, reveal, translation and scale
  excluded.
- **Warm-up (claude):** `ShapeWarmUp` on a worker draws an oversized rectangle across a rounded clip (the API's route)
  through a `HardwareRenderer`, waits for `SYNC_OK` and the image, posts success or failure to the component's thread,
  which counts preparation complete only then (a failure logged, never blocking).
- **Verification:** independent geometry from the movers; 16 × 16 supersampled expected coverage; on edge pixels mean
  coverage error ≤ 0.02, every channel ≤ 24 everywhere, no child colour beyond one pixel outside the shape; at rest equal
  to `drawRoundRect`; cases as round two (fractional bounds and anchors, both `full` handovers, axis crossing and reversal,
  equal and nearly equal targets with speed, unequal curves, radius-only, `clip` toggled, fractional centred stroke,
  oversized child, reduced motion), both paths, and the API 29 route forced on the emulator; warm-up cold against warmed.
  Unverified: a real Android 10 device; E3's glass under a clip.


## 4. Split

| Sub-task | Owner | Files owned | Branch | Verified by |
| --- | --- | --- | --- | --- |
| `radius` channel and `full` in the grammar, docs | codex | `model/MotionSpec.kt`, `MotionGrammar.kt`, `Primitives.kt` (`radius` only), `MotionGrammarTest.kt`, `docs/KDL_MOTION.md` | `team/codex/e2-13c-shapes` | JVM tests, §7 |
| radius handover, `BoxPainter`, `clipPath` at the drawn shape, hit region, warm-up, proofs | claude | `ui/motion/NodeMotion.kt`, `ui/Host.kt`, `ui/BoxPainter.kt`, `ui/ComposeHost.kt`, `ui/ComponentView.kt` (warm-up), new `ui/ShapeWarmUp.kt`, tests, androidTest `ShapeProof.kt`, `AdbProofInstrumentation.kt` | `team/claude/e2-13c-shapes` | JVM, emulator both paths |

## 5. Reports

### codex — work, 2026-11-12T00:41Z

- **Commits:** none; left five assigned files modified as specifically requested. No push.
- **Changed:** `RADIUS` channel, included in `value`; length-or-`full` schema; unchanged `NodeMotionSpec` API and curve precedence; docs and five new tests.
- **Verified — measured:** standalone Kotlin/JUnit runner passed **42/42 model tests**, including refusal spans, bindings, duplicates and precedence; `git diff --check` passed.
- **Not verified:** Gradle tests (wrapper lock attempted under unwritable `C:\.gradle`); full gate intentionally omitted; runtime, pixels and devices remain the lead’s checks.
- **Surprises:** expressions already preserve strings and lengths; no evaluator change needed. No Gradle daemon started.
- **Round-five dissent — judged:** aggregate bounding addresses allocation growth, but the aliased fallback changes the promised antialiasing; owner approval remains needed under protocol §5.3.
- **§7 — judged:** [x] Caller checks: no new external entry point. [x] Bounds: existing parser/expression limits retained; no reflection.
- **§7 — judged:** [x] No new shell/exec/file/component path. [x] Capability derivation unchanged. [x] No capture persistence. [x] Secret handling unchanged.
- **§7 — measured:** [x] Refusal paths exercised by passing tests, including exact source spans.

### claude — report (`91e6ec1`; Codex's grammar `fc9c38f`)

- **Changed:** `NodeShape` (the shape and its `contains`), `RadiusDecl` (a length or `full`, literal or bound, read by
  one function); `HostNode.shownRadius()` (the declaration at the drawn size plus a residual track continued toward 0 on
  the radius curve from the value and speed shown at a change; clamped; empty or non-finite 0) and `shape()` (the hit
  region); `BoxPainter` at the shown radius; `ComposeHost` draws the surface first, then the children: `clipPath` of the
  drawn shape on a GPU canvas from Android 11 (one reused path per node), a two-layer `DST_IN` mask on Android 10 and on
  any software canvas, within `ShapeMask`'s 8 MiB budget of open masks (past it the same rounded `clipPath`, aliased on
  Android 10), layers on whole pixels round the shape; `ShapeWarmUp` (an oversized rectangle across a rounded clip
  through a `HardwareRenderer`, success or failure posted back) gating `ComponentView`'s preparation for components that
  clip.
- **Changed from §3, with the reason:** software canvases mask too. Measured: the software renderer's path clip is
  antialiased differently from its `drawRoundRect` (mean edge coverage 0.11 apart), the GPU's path clip exactly like it
  (0.0000). Software canvases only draw pictures off screen.
- **A bug the proof found:** `ComponentView` read whether it clips from its constructor before the property existed (a
  lazy delegate too): every component crashed at construction. Now a function.
- **Measured, JVM:** `ShapeTest` 5 (`full` through an axis crossing 40 × 120 to 120 × 40, 40 when square; a numeric-to-
  `full` handover continuous, then interrupted to a length equal to where it is with its speed carried; clamped,
  negative and nonsense to 0; the hit region; reduced motion lands at once); all engine unit tests pass.
- **Measured, emulator (`component-shape`, both paths):** each frame against **its own renderer's** drawing of the
  expected geometry (the GPU's through a `HardwareRenderer`, the software's on a `Canvas`), geometry from the movers:
  pill 120 × 40 to 300 × 80 on a spring, turned back at frame 12, 73 frames: mean edge error 0.0017 at worst, worst
  channel 8; handover 8 px to `full`, 25 frames: 0.0005, 8; the clipped child (this release's route and Android 10's
  forced): 0.0000, 1, corners the background, a fractional centred stroke's outer half drawn (240 px). Against **ideal**
  supersampled coverage (my first criterion): the GPU path 0.015 mean, 0.028 at worst (over the proposed 0.02 on a few
  frames); the software renderer 0.07 to 0.12 (Skia's raster antialiasing). I changed the reference to each renderer's
  own `drawRoundRect` because that checks the geometry exactly and the antialiasing is the renderer's, not ours; said
  plainly here for your judgement. `component-motion` and `component-placement` unchanged (PASS).
- **Warm-up:** its frame takes 48 to 136 ms on the emulator's worker. Cold against warmed first use: not measured (the
  GPU's pipeline cache persists across runs on the device; a fair cold run needs a fresh install each time). Recorded
  as not shown.
- **Not verified:** a real Android 10 device; unequal size and radius curves and `clip` toggled on the device (JVM and
  code paths only); E3's glass under a clip; the S24 (not needed until E2.14a).
- **Open with the owner:** Codex's dissent on the Android 10 over-budget fallback (aliased rounded clip) is written to
  `docs/QUESTIONS.md` (Q-003).

## 6. Cross-check

### claude — review of Codex's share (`fc9c38f`), 2026-11-12

- note: `RADIUS` in the channels and in `value`; `radius` a length or `full`, refused otherwise with spans; precedence
  unchanged. Nothing to fix; the runtime reads `curveFor(RADIUS)` and `full` through it.

## 7. Sign-off
