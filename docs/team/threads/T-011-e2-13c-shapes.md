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

## 3. Agreed approach

## 4. Split

| Sub-task | Owner | Files owned | Branch | Verified by |
| --- | --- | --- | --- | --- |
| `radius` channel and `full` in the grammar, docs | codex | `model/MotionSpec.kt`, `MotionGrammar.kt`, `Primitives.kt` (`radius` only), `MotionGrammarTest.kt`, `docs/KDL_MOTION.md` | `team/codex/e2-13c-shapes` | JVM tests, §7 |
| radius track, `BoxPainter`, outline clip, hit region, proofs | claude | `ui/motion/NodeMotion.kt`, `ui/Host.kt`, `ui/BoxPainter.kt`, `ui/ComposeHost.kt`, tests, androidTest `ShapeProof.kt`, `AdbProofInstrumentation.kt` | `team/claude/e2-13c-shapes` | JVM, emulator both paths |

## 5. Reports

## 6. Cross-check

## 7. Sign-off
