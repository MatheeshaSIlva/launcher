# T-009: E2.13a, size and presence motion, place springs, rolling text

- **Step:** E2 · **Lead:** claude · **Opened:** 2026-11-11 · **Status:** AGREED
- **Read first:** `docs/DEFAULT_LAYOUT_PLAN.md` §2 "Motion for size and presence" and §3 (E2.13a, and Codex's three points in
  T-008's last rounds); `docs/ENGINE_SPEC.md` lines 121 (animations declared on the view), 149 (engine-owned transitions),
  327 (motion capabilities); `docs/E2_PLAN.md` slice 13; D-067 (frame bound), D-098; `engine/.../app/motion/Curves.kt`
  (`Mover`, `Ease`, `Spring`); `engine/.../engine/ui/Host.kt` (`Motion`, `HostNode`, `ComponentHost.reconcile`, `shown`),
  `ComposeHost.kt` (placement, `softwareFrame`), `ComponentView.kt`.

## 1. Proposal (lead)

**Goal.** Any KDL node can declare how its size, its place and its arrival and leaving move, and text can roll when it
changes; the engine runs it on the node's own drawing (no Compose state per frame), interruptible and retargetable without a
jump, with its clock stopped when nothing moves. This is what the island's width, a slot making room and a time that changes
need (E2.14), and what every later surface reuses.

**Approach.** Three sub-slices of about 200 production lines each, in this order, one commit each.

1. **13a-1, the motion clock and place and size springs.**
   - KDL: `transition="<what> <curve>"`, `what` one or more of `place`, `size`, `alpha`, `scale`; `curve` a motion role
     (`@motion.<role>`, resolved through the theme as tokens are) or a literal the spec allows (`200ms ease-out`,
     `cubic-bezier(a,b,c,d) 220ms`, `spring(stiff)`, `spring(response, damping)`). Validated with reasons and spans like every
     attribute (`model/` schemas); unknown words refused.
   - `NodeMotion` per node with a declared transition: one `Mover` (from `motion/Curves`) per animated channel (x, y, width,
     height, alpha, scale). Layout still runs once at the target (no relayout per frame). A node's new place is reached by a
     translation that starts at old place minus new place and springs to 0; a new size by a drawn size that springs from the
     old to the new, the node's content laid out at the target and clipped to the drawn bounds while it moves. So siblings
     "make room" through their own place springs, and the frame cost is drawing only.
   - Retarget: a new target mid-motion starts the `Mover` from the current value and current velocity (`Mover.start(from,
     velocity, to)` already does this; tests pin it for both kinds).
   - One clock per `ComponentView` on its thread (Choreographer of nav-ui), running only while some channel is not settled
     (`Mover.settled`, epsilon half a pixel or 1/255 of alpha); each frame sets `Motion`'s fields, which redraw only the
     affected RenderNodes. Settled: the clock unregisters (no callback, no frame). `Motion.slow()` is honoured as today.
   - Reduced motion (`env.reducedMotion`): every transition jumps to its target.
2. **13a-2, presence.**
   - KDL: `enter="<what> <curve>"`, `exit="<what> <curve>"`, `what` from `fade`, `grow` (size from 0 along the parent's axis),
     `scale`, `rise` (a short offset), combinable.
   - A node that leaves (`visible` false, or an `each` item gone) is kept as a leaving node in its parent's drawing, out of
     layout, at its last place, until its exit settles; then released exactly once (`onRemoved`). With no `exit`, it goes at
     once, as today.
   - A node that arrives starts from its `enter` values. A leaving node whose key comes back before its exit ends is revived
     in place and reverses from its current values and velocity (no second node, no jump).
   - The parent's own size spring makes the room appear and close (`grow`), so the island widening for a new slot is one
     motion.
3. **13a-3, rolling text.**
   - `text roll="<curve>"`: when the text changes, characters that changed move out (up for a larger number, down for a
     smaller, else up) and the new ones in, per character, staggered by a theme token (the design language's 24 ms); characters
     that did not change stay still; a width change uses the size spring. Rolling only for what the text painter already lays
     out per glyph (`TextPainter`, `GlyphCache`); a change in the middle of a roll retargets from where each glyph is.
   - The decode effect (scramble) is E2.14d, not here.

The design language's motion tokens (dur-state 120 ms, dur-move 220 ms, dur-sheet 250 ms, ease-out, ease-move with its single
6 % overshoot, stagger 24 ms) become roles of a `default` motion preset (`assets/motion/`), read off the design artifact
when written; nothing in code is timed by a literal (the launcher's rule, a test checks it).

**Who does what (proposed split).** Codex: the KDL side of all three, in `engine/.../model/` and `kdl/` (attribute grammar,
validation with reasons, curve resolution to `Curve`), with parser and validator tests, as one branch ahead of mine. Claude:
`NodeMotion`, the clock, presence and roll in `engine/.../ui/` and their tests, the preset, and the frame captures. No file
has two owners.

**Verification.**
- Unit tests (JVM): value and velocity continuous across a retarget for springs and beziers (difference under 1e-3 of the
  range at the switch); a place change of a sibling produces a translation that ends at exactly 0; a size spring ends on the
  target; a leaving node released exactly once, after its exit, also when its component is disposed mid-exit; a revived node
  is the same `HostNode`; the clock reports idle when every channel settled, and requests no frame after; reduced motion lands
  in one frame; roll changes only the glyphs that differ.
- Frames at exact steps: a dev hook like the launcher's `charge-frames` (`TEST_STATUS --es do motion-frames`) draws a fixture
  component (a pill whose content grows, a slot arriving and leaving, a time rolling, each also reversed mid-way) at exact
  120 Hz steps into PNGs; checked on the emulator for no jump between consecutive frames (largest per-frame move of an edge
  below the spring's own peak step; no frame where a node is absent then present).
- Idle: `dumpsys gfxinfo` shows no new frames for the bar's window once motion settles (emulator), and E2's idle bound (under
  1 ms a frame) is not touched while still.
- `scripts/check.sh` green; `tools/dexlist.sh` not needed (nothing moves).
- Nothing on screen changes for users: the KDL bar stays behind its dev switch, and the old bar is untouched.

**Risks and open questions.**
- Clipping a size spring's content: a clip on a RenderNode is cheap for a rounded rectangle; a node with live glass under it
  must not be clipped (Part 2 of `CLAUDE.md`). No glass is in E2, so the rule is written into `NodeMotion` now and tested
  when E3 adds glass.
- One clock per view on nav-ui: the shade's other work shares that thread; the clock does nothing between frames, and its
  per-frame cost is measured on the S24 at E2.17, not before (D-067's bound).
- The roll needs per-glyph drawing that `TextPainter` may not expose cleanly; if it does not, 13a-3 is split again rather
  than stretched.
- Nothing here needs the owner: no dependency, permission or visible change.

## 2. Discussion

Alternate `### codex — <date>` and `### claude — <date>` entries, Codex first. Each entry: what you agree with, what you
would change and why (with evidence), what is missing. At most three rounds each (protocol §5).

### codex — discuss, 2026-11-11T05:09Z

**Judged from code inspection; no tests or device measurements run.** I agree with target layout, engine-owned motion, retained exits and one motion clock per `ComponentView`. The proposal needs these corrections before implementation. Paths below are under `engine/src/main/java/dev/launcher/` unless stated otherwise.

1. **Bezier velocity continuity is not already solved.** `app/motion/Curves.kt:55–65` adds the bezier’s initial velocity to the supplied velocity: `(target-from) * slope(0) / duration + v0`. Thus `start(current, velocity, target)` preserves velocity only for curves starting with zero slope. The existing test explicitly uses that case (`app/src/test/java/dev/launcher/app/MotionTest.kt:88–100`). Add a prerequisite fix and regression tests for nonzero initial slopes, the overshoot curve, reversal and equal endpoints with nonzero velocity. Assign `Curves.kt` and its existing tests explicitly; neither belongs to the proposed split.

2. **Drawn size needs a rendering contract beyond clipping.** `ui/BoxPainter.kt:15–30` draws fill and stroke at the supplied bounds. Clipping a target-sized rounded box during growth cuts off its far corner/stroke; during shrink, clipping cannot recover pixels beyond its smaller target. Keep descendants laid out at target, but distinguish their reveal bounds from the primitive’s animated drawing bounds. Define anchoring and overshoot, retain sufficient recording bounds during shrink, and keep compositing allocations stable (`DEFAULT_LAYOUT_PLAN.md` §2; `CLAUDE.md:938–941`). E2.13c can own radius morphing; E2.13a still needs correct size drawing. Test growth, shrink, reversal, stroke and rounded corners on both rendering paths (`ui/ComposeHost.kt:109–143,169–200`).

3. **Place motion needs separate contributions and explicit coordinates.** `ui/Motion.place()` currently stores fractional placement only (`Host.kt:58–68`); integer placement happens separately (`ComposeHost.kt:70–82`). Capture the complete old/current displayed position and new layout position. On retarget, use **current translation + old layout position − new layout position**, preserving velocity. Keep this contribution separate from declared transforms, presence offsets and root baseline correction: `ComponentView.kt:119` already writes `root.motion.translationY`. Also wire actual target bindings: `translate-x/y`, `opacity` and `scale` are schema attributes, but `Host.kt` currently does not apply them. Test repeated placement without restarting motion, moving ancestors and children together, fractional icon placement, and root centre-line changes.

4. **Retaining a `HostNode` alone will not retain its drawing.** Compose builds children directly from `n.children` (`ComposeHost.kt:45`), while reconciliation removes and releases missing instances (`Host.kt:284–291`). Specify separate layout and drawing membership, including how an exiting subtree stays composed/drawable without participating in measurement. Freeze its last displayed geometry and define draw order. Cover root visibility, parent-and-child exits, ancestor removal, revival with updated item data, and disposal. Bound **active plus retained** nodes: the current 4096-node preflight (`Host.kt:217–251`) bounds only the new plan; repeated unique arrivals could otherwise accumulate exits. Retained painters must release exactly once.

5. **Rolling text is a larger slice than described.** `TextPainter.kt:28–47` measures and draws a complete string; it exposes no per-glyph layout. `GlyphCache.kt:3–12` caches **icon symbols**, not font glyphs. The existing reference is `app/.../statusbar/StatusBarView.kt:1592` onward: unchanged characters stay put for equal-length text, while length changes roll the whole string. Define a shaping-safe contract before promising arbitrary per-character motion: surrogate pairs, combining marks, ligatures, RTL, proportional advances, insertions and alignment all matter. Include `09→10`, `59→00`, length changes and multiple mid-roll replacements without dropping visible outgoing glyphs. Split preparation/shaping from animation if necessary; this is not plausibly guaranteed within 200 production lines.

6. **Agree the model/runtime boundary and syntax before splitting files.** Keep parsing these attribute strings in the model layer; the KDL scanner need not change. Return validated, plain motion descriptions with role references; resolve roles through the active **motion preset**, preserving speed/bounce adjustments (`app/design/Design.kt:912–922`), rather than treating them as ordinary theme values. `token/Tokens.kt:82–86` currently returns `Null` for curves, so this needs an explicit resolver interface and owner. Reconcile existing `spring`, `roll=#true`, and the spec’s `transition="value …"` / `enter="fade-up …"` examples with the proposed grammar (`model/Primitives.kt:55,69`; `ENGINE_SPEC.md:121`). Document the E2 subset and later capabilities rather than silently refusing established forms. Test unknown roles, wrong token types, bounded positive durations/spring parameters, finite numbers and bezier x controls in `[0,1]`. Both agents complete protocol §7 for validator changes.

7. **The clock direction is right; its integration remains unspecified.** Inject a cancellable clock for deterministic tests, coalesce frame requests, snap settled channels exactly to target, and use a pixel-based epsilon for scale. Include delayed roll glyphs in the busy state. Define cancellation on disposal and reduced motion changing **during** an exit or roll. Reduced motion needs real input wiring: `ComponentView.kt:79–86` supplies no environment values, and `SourceBinder.kt:55–61` only delegates unresolved sources. Also distinguish property-only movement from size/text repainting: `ComposeHost.kt:127–141` re-records frame nodes whenever traversed, so “only affected RenderNodes” needs counter evidence.

The three headings are sensible **milestones**, but not yet three bounded commits. I support Codex owning the pure grammar/model and validation tests, with Claude owning runtime/rendering, provided we first name the resolver API and assign token integration, reduced-motion wiring, curve fixes, role/moment declarations and harness files. Deliver each grammar portion with its corresponding runtime slice, avoiding accepted attributes that temporarily do nothing.

For verification, add nested movement, grow-plus-parent-size interaction, rapid repeat churn, knockout text, and mid-motion font/unit/preset changes. Compare deterministic frames against expected geometry and `diff.py` results; a “largest edge step” threshold alone can miss wrong clipping or restarted motion. Exercise hardware RenderNodes as well as software PNG drawing. Prove callback cancellation separately from window frame counts, since sources and other shade work can produce frames. Keeping S24 performance measurement at E2.17 matches the plan; emulator frames establish drawing correctness only.

codex: OBJECT approach — retain the architecture, but resolve bezier continuity, size rendering, exit ownership and text shaping, then complete the split and verification contracts.

### claude — 2026-11-11

All seven accepted; you checked the code where I assumed. Point by point:

1. **Bezier continuity.** Confirmed: `Ease.velocity(0)` is `Δ·slope(0)/d + v0`, so a retarget of an ease-out adds the curve's
   own initial slope to the carried speed. Fix without touching the launcher's motion (proven, and the owner's): a second
   entry on `Mover`, `continueFrom(from, velocity, to)`, used only by the engine's node motion. For `Ease` it carries
   `v0 = velocity − Δ·slope(0)/d`, so the speed at the switch is exactly `velocity` and the value still ends on the target at
   `d`; from rest (velocity exactly 0, a value that settled) it is today's `start`, so an authored curve from rest keeps its
   shape. `Spring` already continues (it is a state solve); `continueFrom` delegates. Tests: nonzero initial slope, the 6 %
   overshoot curve, reversal, equal endpoints with nonzero velocity, both kinds. Owner: claude (`Curves.kt`, `MotionTest`'s
   new cases in a new `NodeMotionCurveTest` so the existing file stays as it is).
2. **Size drawing.** Accepted as you put it: the primitive draws its own surface (fill, stroke, corners) at the animated
   bounds, anchored (default: the parent's axis start for a row, centre for a stack and for the root; a `transition-anchor`
   later if a design needs another); descendants are laid out at the target and revealed through a clip of the animated
   bounds, never the primitive itself clipped. Recording bounds for the motion's whole life are the union of old, new and the
   curve's peak (a spring's peak from its damping, a bezier's from its y controls), so no layer or allocation changes size per
   frame. Radius morphing stays in E2.13c. Tests on both paths: grow, shrink, reversal, stroke inside and centred, rounded
   corners.
3. **Place.** Accepted: the motion keeps the last displayed position (integer and fractional parts together); a new layout
   position starts the place channel at `current translation + old layout position − new layout position`, velocity kept.
   Contributions stay separate and add at draw: layout place, place spring, declared transform (`translate-x/y`, `opacity`,
   `scale`, now wired as targets that animate when a transition names them, jump otherwise), presence offset, and the root
   baseline correction in `ComponentView`. Tests as you list them.
4. **Exits.** Accepted: `HostNode` gets layout children and drawn children; a leaving node is in the second only, at its
   old index, with its last geometry frozen. An ancestor's exit carries its subtree (descendants' own exits do not start);
   root visibility follows the root's exit. Revival takes the item's new bindings and reverses from current values. Retained
   nodes are bounded: active plus retained under the 4096-node limit, and at most 64 retained per component (the oldest exit
   finishes at once past that). Painters released exactly once, disposal mid-exit included.
5. **Roll.** Accepted, and it was my mistake to read `GlyphCache` as font glyphs. The contract is StatusBarView's
   (`StatusBarView.kt` from 1592): equal length, only differing characters roll; a length change rolls the whole string.
   Characters are grapheme clusters (`BreakIterator`); text with RTL runs or where measured cluster advances do not add up to
   the whole (ligatures, combining shaping) always rolls whole. Mid-roll replacement keeps outgoing glyphs visible and
   retargets. Split into 13a-3a (cluster preparation and the per-cluster painter) and 13a-3b (the animation), each its own
   commit.
6. **Boundary and syntax.** The model parses attribute strings into plain `MotionSpec` data with role references; the KDL
   scanner does not change. Roles resolve through a `MotionResolver` interface the runtime implements on the active motion
   preset (`Design.curve`, so speed and bounce adjustments apply), not through `Tokens`. Grammar, reconciling what exists:
   `transition="<channels> <curve>"`, channels `value` (all), `place`, `size`, `opacity`, `scale`; `spring="<preset or role>"`
   is the node's default curve for every channel a transition names without a curve; `roll=#true` uses the role
   `motion.text.roll`, `roll="<curve>"` names one; `enter`/`exit="<effect> <curve>"`, effects `fade`, `grow`, `scale`, `rise`
   and the spec's compound `fade-up` (= fade + rise). Curves: `@motion.<role>`, `<n>ms <ease>` with `ease-out`, `ease-in-out`,
   `linear`, `cubic-bezier(a,b,c,d) <n>ms` (x in 0..1, finite), `spring(<name>)`, `spring(<response>, <damping>)` (bounded,
   positive). Durations 1..5000 ms. The E2 subset is documented in `docs/KDL_MOTION.md` with what comes later (keyframes,
   stagger formulas, gesture progress). Both of us fill §7's checklist for the validator.
7. **Clock.** Accepted: an injectable `FrameClock` (tests drive time), one registration coalescing every request, cancel on
   dispose, settled channels snapped exactly to target, scale's epsilon in pixels of the node's size, delayed roll clusters
   counted as busy. Reduced motion is wired as a real `env` value (Android's animator scale 0, or remove animations) with a
   change mid-exit or mid-roll snapping to the end. A re-record counter on `ComposeHost` gives the evidence for "only
   affected nodes redraw".

**Revised order and split** (each one commit, about 200 production lines; grammar lands with its runtime, never ahead):

| Sub-slice | Claude | Codex |
| --- | --- | --- |
| 13a-0 | `Curves.continueFrom` and tests; `FrameClock`; reduced motion in `env`; `MotionResolver` interface | none |
| 13a-1 | `NodeMotion` place, size, opacity, scale; target bindings wired; size drawing and reveal clip; re-record counter | `MotionSpec`, the `transition` and `spring` grammar and validation, `docs/KDL_MOTION.md` |
| 13a-2 | drawn children, retained exits, revival, bounds | `enter`/`exit` grammar and validation |
| 13a-3a/b | cluster preparation, per-cluster painter, roll animation | `roll` grammar (bool and curve) and validation |

Verification additions accepted: nested movement, grow inside a parent's size spring, rapid repeat churn, knockout text,
font, unit and preset changes mid-motion; frames compared with the expected geometry computed from the same `Mover` at that
time (a per-frame dump of node bounds, as `BarDump`) and with `diff.py`, on the hardware RenderNode path and the software path;
the clock's registration count proves cancellation apart from window frame counts. S24 performance stays at E2.17.

### codex — discuss, 2026-11-11T05:13Z

**Judged from code inspection and analytic calculations; no builds, tests or device measurements run.** I agree with the revised ownership, staged grammar/runtime delivery, separate transform contributions, injected clock and reduced-motion wiring. Four details still prevent agreement. Paths below are relative to `engine/src/main/java/dev/launcher/`.

1. **`continueFrom` needs an explicit fresh-start distinction.** The correction `v0 = velocity − Δ*slope(0)/d` is correct for a finite, correctly computed initial slope (`app/motion/Curves.kt:55–65`). But zero velocity does not imply settled: a spring has zero velocity at a turning point. Retargeting there must still use the correction. Let runtime choose `start` for a fresh motion and `continueFrom` for every interruption, including zero velocity.

   There is also an endpoint derivative problem in `Curves.kt:74–78`: `cubic-bezier(0,0,1,1)` is linear, but `slope(0)` returns 0 because both derivatives are zero. The proposed correction therefore fails to preserve the actual initial speed even with nonzero carried velocity. A vertical initial tangent cannot be cancelled by a finite correction either. Define engine handling for these accepted curves, preserving launcher behaviour. Tests must compare finite differences of **value**, as well as `velocity(0)`, including linear, degenerate controls and interruption exactly at a spring turning point.

2. **Recording bounds must include carried velocity.** Old/new bounds plus the authored curve’s peak are insufficient. With equal endpoints, Ease’s carried term alone reaches `4*v0*d/27` (`Curves.kt:59`): at 1000 px/s and 200 ms, that is **29.63 px** beyond the shared endpoint. A critically damped spring also overshoots equal endpoints with nonzero velocity (`app/Spring.kt:51–54`); response 0.4 s gives **23.42 px** for that speed. These are analytic calculations, not rendering measurements.

   Reserve a conservative envelope of the **complete continued trajectory**, including corrected velocity, anchor offsets and stroke extents. Keep it stable between retargets; at a retarget, enlarge once if necessary without losing current pixels. Apply that envelope to both RenderNode recording/compositing bounds and software layer bounds, currently target-sized in `ui/ComposeHost.kt:120–129,179–180`. The revised surface-versus-descendant clipping contract otherwise addresses my concern.

3. **The grammar still misses canonical examples and the existing value classifier.** `ENGINE_SPEC.md:121` declares `enter="fade-up 240ms"` and `exit="fade 120ms"`; the revised curve grammar only permits duration **with** an easing. Accept duration-only curves with a documented default.

   Also, `roll="@motion.text.roll"` becomes `AttrValue.Ref` in `model/ComponentReader.kt:86–94`; ordinary validation then treats it as a token reference, while fixed attributes reject bindings (`model/Validator.kt:87–104`). Motion attributes need dedicated model handling before ordinary token/expression validation. The scanner can remain unchanged. Document omitted-curve precedence, what happens without `spring`, which channel controls declared `translate-x/y`, and that `value` covers the implemented E2 channels. Keep unsupported capabilities explicit.

4. **The rolling fallback needs stronger shaping evidence.** Grapheme boundaries are useful, but equality between summed cluster widths and whole-string width does not prove independent cluster drawing reproduces the shaped string. Compare individual advances/positions and rendered output, or restrict independent rolling to a provably safe subset and roll everything else whole. `ui/TextPainter.kt:28–47` currently measures and draws the whole string, so this remains work for 13a-3a.

**Exit retention:** I agree with separate layout/drawing membership, ancestor-owned exits, revival and bounded retention. Make the Compose contract explicit: compose drawn children, map active children into layout calculations, and keep retained subtrees placed and drawable at frozen geometry. Merely omitting them from measurement/placement will not preserve their drawing (`ui/ComposeHost.kt:45–82`). Count retained descendants toward both budgets; evict before allocating beyond 4096, and include retained instances in disposal (`ui/Host.kt:186–195`). These are implementation acceptance conditions for the agreed architecture.

The revised split is workable once these contracts are corrected; split commits further when the rendering or shaping work exceeds the slice limit.

codex: OBJECT approach — resolve continued-curve derivatives, velocity-aware recording bounds, grammar compatibility and shaping safety before implementation.

### claude — 2026-11-11, round two

All four accepted, and the exit-retention conditions taken as acceptance criteria of 13a-2.

1. **Fresh start versus interruption.** The runtime decides, not the velocity: a channel that has settled (snapped to its
   target) starts with `start`; every interruption, zero velocity at a spring's turning point included, uses `continueFrom`.
   The initial slope is computed from the curve's initial tangent, not from `slope(0)`'s derivative ratio: the first control
   point among P1, P2, P3 = (1, 1) that is not at the origin gives the direction, so `cubic-bezier(0,0,1,1)` has slope 1 and
   `(0,0,x2,y2)` has `y2/x2`. A vertical initial tangent (x of that point 0, y not) cannot be corrected by a finite term: such
   a curve, when interrupted, continues as the spring the launcher's `motion/CurveFit` fits to it, from the current value and
   velocity (a fresh start keeps the authored curve). `start` and the launcher's motion are untouched. Tests compare the
   finite difference of value across the switch (1 ms either side, within 1 % of the carried speed or 0.5 px/s) as well as
   `velocity(0)`, for linear, degenerate controls, overshoot, vertical tangents, equal endpoints and a spring turning point.
2. **Envelope of the whole continued trajectory.** At each start or interruption the channel computes the extent of its
   complete trajectory by sampling the closed form it will follow (256 samples over its duration, or a spring's settle time,
   plus 1 px), corrected velocity included; the node's envelope adds the anchor offsets and the stroke's outer extent. The
   envelope only grows at an interruption (never shrinks mid-motion), and is dropped when every channel has settled. It sets
   the RenderNode's recording bounds and the software layer's bounds alike (`ComposeHost` 120-129, 179-180). Your two
   figures (29.63 px for the ease, 23.42 px for the spring) become test cases.
3. **Grammar.** Duration alone is a curve: `<n>ms` takes `ease-out`, so `enter="fade-up 240ms"` and `exit="fade 120ms"` read
   as the spec writes them. Motion attributes (`transition`, `spring`, `enter`, `exit`, `roll`) go through a dedicated model
   path before token and expression validation, so `@motion.<role>` is a role reference there and never a token, and none of
   them binds to expressions. Precedence, documented in `docs/KDL_MOTION.md`: the transition's own curve; else the node's
   `spring`; else the preset's role `motion.move` (`motion.state` for opacity alone). `translate-x/y` belong to `place`;
   `value` means every channel E2 implements (place, size, opacity, scale) and the document says so; everything the spec names
   that E2 does not implement (keyframes, stagger formulas, gesture progress, rotation) is refused with a reason that says it
   is not yet supported, not that it is unknown.
4. **Rolling only where it is provably the same picture.** Independent per-cluster rolling only when the string has no bidi
   runs and each cluster's shaped position in the whole string (`Paint.getRunAdvance` at each cluster boundary) equals the sum
   of its predecessors' individually measured advances within 0.01 px, for both the old and the new string; anything else
   rolls whole. 13a-3a's test draws the fixture strings (times, percentages, counts, in the bar's fonts with and without
   `tnum`) both ways and requires pixel identity at rest.

**Exit retention in Compose:** the host composes drawn children, measures and places active children, and places retained
subtrees at their frozen geometry so they keep drawing; retained descendants count toward both budgets; eviction happens
before an allocation would pass 4096; disposal walks retained instances too. Each is a test in 13a-2.

### codex — discuss, 2026-11-11T05:16Z

**Judged from code inspection and mathematical reasoning; no builds, tests or device measurements run.** I agree with the revised architecture, split and exit-retention contract. Two correctness gaps remain.

1. **Initial tangent and fallback: agreed.** The first non-origin control point gives the initial tangent, including degenerate linear curves. Runtime must choose `start` only after settlement and `continueFrom` for every interruption. `CurveFit.springLike` exists and returns bounded spring parameters (`engine/src/main/java/dev/launcher/app/motion/CurveFit.kt:129–143,179–180`); starting that spring from the current value and velocity gives a valid continuation for vertical tangents. Cache the fit by curve rather than running its three optimisation searches on every interruption.

   Implementation acceptance: engine velocity evaluation must agree with the actual value derivative, including finite initial slopes above the launcher's `MAX_SLOPE=20` (`Curves.kt:74–82`). Preserve launcher behaviour through the separate engine path. Check derivative convergence with decreasing time steps; a fixed 1 ms finite difference includes acceleration and cannot universally meet the proposed tolerance.

2. **The sampled envelope is still not conservative.** Sampling 256 points and adding 1 px can miss an extremum by more than 1 px. The error grows with displacement, carried velocity and curve controls; none currently establishes that margin as sufficient. Also, `CurveFit.settleTime` explicitly describes a **from-rest, normalised** trajectory (`CurveFit.kt:53–72`), so it cannot provide the horizon for an arbitrary continued spring unchanged.

   Replace the fixed sampling guarantee with analytic bounds or adaptive subdivision with a proven error bound, including a conservative spring tail. Sampling may assist that calculation. Bounds must cover the complete continued trajectory, then add anchor and stroke extents, and grow only on retarget as agreed. Tests should exercise extrema between samples and springs with carried velocity, beyond the two earlier regression examples.

3. **Grammar: agreed.** Duration-only curves now match `ENGINE_SPEC.md:121`. Dedicated motion handling resolves the reference-classification problem. Explicit curve → node `spring` → preset role is clear, as are `place` owning `translate-x/y`, `value` covering E2's four channel groups, and diagnostics distinguishing unsupported capabilities.

4. **Rolling safety still needs a rendering guarantee.** Matching every cluster boundary's advance is stronger than matching total width, but does not establish identical glyph shapes. Android documents `getRunAdvance` as cursor measurement and explicitly permits advances inside a formed ligature at grapheme boundaries. [Android Paint reference](https://developer.android.com/reference/android/graphics/Paint#getRunAdvance(char[],int,int,int,int,boolean,int))

   Fixture pixel identity proves those fixtures, fonts and settings only. Either establish identical shaped glyphs and positions for each candidate string, or limit independent rolling to a verified subset of strings and font configurations and roll everything else whole. Re-evaluate eligibility when font, features or size changes.

codex: OBJECT approach — replace the unproven sampled envelope and advance-only rolling eligibility; the initial-tangent rule, spring fallback, grammar precedence and exit-retention contract are agreed.

### claude — 2026-11-11, round three

Both accepted; no dissent to record. The fit for vertical tangents is cached per curve; engine velocity is the exact
derivative of the value (no `MAX_SLOPE` clamp on the engine path); continuity is checked by derivative convergence over
decreasing steps (1, 0.1, 0.01 ms), not one fixed difference.

- **Envelope, analytic.** Ease: the value is `from + Δ·p(u) + v0·t·(1−u)²`; `p` lies within the hull of its y controls,
  `[min(0, y1, y2), max(1, y1, y2)]` (the convex-hull property of a Bézier), and the carried term within `|v0|·4d/27`, so the
  extent is `from + Δ·[that range] ± |v0|·4d/27`, conservative by construction. Spring, from its closed form with the
  continued value and velocity: underdamped `|x − target| ≤ √(A² + B²)` (the decaying factor is at most 1), critically damped
  `≤ |A| + |B|/(e·ω)` for `(A + Bt)·e^(−ωt)`, overdamped `≤ |C1| + |C2|`; no time horizon needed. Sampling is not used. The
  node's envelope adds anchor offsets and the stroke's outer extent, grows only at an interruption, and is dropped at rest.
  Tests: extrema between any sample points (dense numeric check against the bound), springs of all three dampings with
  carried velocity, and your two figures.
- **Rolling, a verified subset.** Independent per-cluster rolling only for strings made of ASCII digits and `: . , % / -`
  and space, and only in a font configuration (family, weight, size, features such as `tnum`) verified once: every character
  of the subset, and every adjacent pair, drawn whole and drawn as independent clusters at the shaped positions on a small
  bitmap, must match pixel for pixel. The verdict is cached per configuration and computed again when any of those change;
  anything else, or a configuration that fails, rolls whole. (Superseded in §3 after Codex's confirmation round: every
  complete string is verified, the pair check only filters.)

### codex — discuss, 2026-11-11T05:17Z

**Judged from the thread, code and mathematical review; no builds, tests or device measurements run.** The analytic envelopes in round three are conservative, using corrected `v0` and ordering interval endpoints for negative displacement. §3 faithfully records the other agreed corrections.

One statement still needs correction: round three, lines 275–279, treats character-and-pair pixel checks as verification for arbitrary strings over that alphabet. Longer contextual substitutions or ligatures can pass every pair check; OpenType permits these patterns ([GSUB specification](https://learn.microsoft.com/typography/opentype/spec/gsub)). This does not establish the verified subset required by my preceding round.

For §3’s roll contract, verify each complete candidate string against independent-cluster drawing, cache by string and font configuration, and roll whole on failure or pending verification. Character-and-pair checks can remain a preliminary filter.

codex: OBJECT approach — analytic bounds agreed; pair-only checks do not establish rolling safety for longer strings.

### codex — discuss, 2026-11-11T05:19Z

**Judged from the complete thread and project documents; no tests or device measurements run.** §3’s Roll bullet (`T-009-e2-13a-motion.md:311–316`) resolves my remaining objection: character-and-pair checks only filter candidates; every complete string requires pixel-identical whole-string versus independent-cluster rendering. Verification runs off the drawing thread, uses a bounded cache keyed by string and font configuration, and falls back to whole-string rolling while pending or on failure.

Apply eligibility to both outgoing and incoming strings. The earlier curve, analytic-envelope, grammar and exit-retention corrections remain agreed. No further approach changes requested; §4’s file ownership table remains to be completed before implementation.

codex: AGREE approach — complete-string rendering verification and whole-string fallback resolve the remaining rolling-safety objection.

## 3. Agreed approach

What was agreed, what changed from the proposal and why.

The proposal's architecture, with Codex's corrections over three rounds:
- **Curves (13a-0, claude).** `Mover.continueFrom` on an engine-only path; `start` and the launcher's motion untouched.
  The runtime starts settled channels with `start`, interrupts with `continueFrom` always; the carried term is corrected by
  the initial slope from the curve's initial tangent (first non-origin control point); a vertical tangent continues as the
  spring `CurveFit` fits to it (cached per curve); engine velocity is the exact derivative. Plus the injectable
  `FrameClock`, reduced motion in `env`, and the `MotionResolver` interface (roles through the active motion preset, with its
  speed and bounce adjustments).
- **Place, size, opacity, scale (13a-1).** Layout at the target; place as a separate contribution from the full old
  displayed position, velocity kept; the primitive draws its own surface at the animated bounds (anchored), descendants
  revealed through a clip of them, never the primitive itself; declared `translate-x/y`, `opacity`, `scale` wired as targets;
  the analytic envelope of the whole continued trajectory for recording and layer bounds, growing only at an interruption;
  a re-record counter as evidence.
- **Presence (13a-2).** Layout children and drawn children; retained exits at frozen geometry, composed and placed so they
  draw; ancestor-owned exits; revival with new bindings, reversing; active plus retained bounded (4096, 64 retained, eviction
  before allocation); disposal releases retained painters exactly once.
- **Roll (13a-3a, 13a-3b).** StatusBarView's contract. Per-cluster rolling only for a string that is verified: its
  characters in the subset (ASCII digits, `: . , % / -`, space; a preliminary filter, with the character-and-pair check per
  font configuration) and the complete string itself drawn whole and as independent clusters at the shaped positions, pixel
  for pixel equal, off the drawing thread; verdicts cached by string and font configuration (bounded, least recently used),
  dropped when the configuration changes. A string not yet verified, or failing, rolls whole. Mid-roll replacement keeps
  outgoing glyphs and retargets (Codex, round three's confirmation: pair checks alone miss longer contextual substitutions).
- **Grammar (codex, each part landing with its runtime).** A dedicated model path for `transition`, `spring`, `enter`,
  `exit`, `roll` before token and expression validation; duration alone takes `ease-out`; precedence curve > node `spring` >
  preset role (`motion.move`, `motion.state` for opacity alone); `value` = E2's four channels; unsupported capabilities
  refused as not yet supported; `docs/KDL_MOTION.md`. Both agents fill §7's checklist for the validator.
- **Verification.** The proposal's, plus: derivative convergence at switches, bound-versus-dense-numeric checks, nested and
  churn cases, knockout text, font/unit/preset changes mid-motion, frames against expected geometry from the same `Mover`
  and with `diff.py` on both drawing paths, clock registration counts. S24 performance at E2.17.

## 4. Split

| Sub-task | Owner | Files owned | Branch | Verified by |
| --- | --- | --- | --- | --- |
| 13a-0 continued curves, `FrameClock`, reduced motion in `env`, `MotionResolver` | claude | `engine/.../app/motion/Curves.kt` (engine path only), new `engine/.../engine/ui/motion/` (`FrameClock`, `MotionResolver`, `Continued`), `ComponentView.kt` env wiring, new tests under `engine/src/test/.../ui/motion/` | `team/claude/e2-13a-motion` | derivative convergence, clock registration tests |
| 13a-1/2/3 grammar: `MotionSpec`, `transition`, `spring`, `enter`, `exit`, `roll`, the dedicated model path, `docs/KDL_MOTION.md` | codex | new `engine/.../engine/model/MotionSpec.kt`, `MotionGrammar.kt`; `Validator.kt` and `ComponentReader.kt` (motion attributes only); `model/Primitives.kt` (motion attribute defs only); new `engine/src/test/.../model/MotionGrammarTest.kt`; `docs/KDL_MOTION.md` | `team/codex/e2-13a-motion` | parser and validator tests, §7 checklist |
| 13a-1 node motion: place, size, opacity, scale, envelope, re-record counter | claude | new `engine/.../engine/ui/motion/NodeMotion.kt`, `Envelope.kt`; `Host.kt`, `ComposeHost.kt`, `BoxPainter.kt` | `team/claude/e2-13a-motion` | unit tests, frames against expected geometry on both paths |
| 13a-2 presence: drawn children, retained exits, revival, bounds | claude | `Host.kt`, `ComposeHost.kt`, `NodeMotion.kt` | `team/claude/e2-13a-motion` | exit and disposal tests, frames |
| 13a-3a/b roll: verification, per-cluster painter, animation | claude | `TextPainter.kt`, new `engine/.../engine/ui/RollPainter.kt`, `RollEligibility.kt` | `team/claude/e2-13a-motion` | pixel identity, roll frames |

Each grammar part lands with its runtime part (integrated by the lead in one commit pair); no attribute is accepted before it
does something.

## 5. Reports

### claude — report

**13a-0** (`5e4073d` on `team/claude/e2-13a-motion`)
- **What changed:** `engine/.../ui/motion/Continuation.kt` (`Continuation.fresh/continued`, `EngineEase`: initial and end
  tangents from the first control point off the origin or the end, carried term corrected by the initial slope, exact
  derivative with the tangent limit where dx vanishes, vertical tangent continued as `CurveFit.springLike`, cached);
  `FrameClock.kt` (`FrameSource`, `ChoreographerSource`, `MotionTicker`: one registration, none at rest, cancel on dispose);
  `MotionResolver.kt` (`PresetMotion` through `Motion.role`, `ReducedMotion` from the animator scale with a watch);
  `Env.reducedMotion` (default false) and `ComponentView` wiring it (watch closed on dispose; nothing reacts to a change
  yet, that is 13a-1's node motion). `Curves.kt` untouched: the engine path is separate.
- **Verified (measured, JVM unit tests):** `ContinuationTest` (8) and `MotionTickerTest` (4) green: speed at the switch
  from the value by Richardson-extrapolated differences (best of six steps within 1 % or 2 px/s) and reported, for ease-out,
  standard, overshoot, linear, degenerate and steep curves at four speeds; equal endpoints; vertical tangent; a spring at its
  turning point; reported speed equal to the value's central difference at five points; a fresh start equal to the
  launcher's curve; the launcher's ease jumping (the reason, a test that fails on its path). `scripts/check.sh` PASSED.
- **Not verified:** nothing runs on a device yet (no node uses it until 13a-1).
- **Surprises:** a curve whose first control point is at the origin reaches its initial speed at a square-root rate (its
  value has a u^1.5 term), so fixed small differences converge slowly; and the launcher's ease reads such a start as slope
  0, so ease-out (0, 0, 0.58, 1) does not jump there, only curves with a sloped start do.

**13a-1, runtime** (`7df5eed`, over Codex's grammar `9b8be46`, committed by the lead from its worktree: its sandbox
could not take git's index lock)
- **What changed:** `ui/motion/NodeMotion.kt` (`Track`: fresh start when settled, `Continuation.continued` at every
  interruption, `shift` for a layout move, the envelope `low..high` set at a start, grown only at an interruption, the target
  alone at rest; `NodeMotion`: place x/y (declared `translate-x/y` as target, a layout move shifting from where it was shown),
  width/height, opacity, scale; epsilons 0.5 px, 1/255, half a pixel of the node's size for scale; `MotionContext`).
  `ui/motion/Envelope.kt`: the analytic bounds of round three, with a second bound per spring regime so near-critical damping
  stays finite (`|x0| + |v0 + ζωx0|/(eζω)` underdamped, `/(e(ζω − β))` overdamped; the smaller of each pair). `Host.kt`:
  `Motion` keeps place, rest place, drawn size, anchor and envelope apart from the root's baseline translation; `HostNode.placed`
  (bound `translate-x/y`, `opacity`, `scale` now applied, animated where a transition names them); `ComponentHost` keeps the
  moving nodes, `step`, `snapMotion`, released and disposed nodes dropped. `ComposeHost.kt`: the parent places each child
  (`placed`, its anchor from the parent: a row's start and cross alignment, a stack's alignment, the root centred); whole pixels
  worked out from the rest place; the surface drawn at the moving size where its anchor puts it, the box's clips at that size,
  children revealed through it; the frame node's bounds the envelope while a size moves, its pivot kept at the layout centre;
  the software layer over the envelope. `ComponentView.kt`: the ticker on `ChoreographerSource`, `PresetMotion`, reduced motion
  snapping what moves.
- **Verified (measured, JVM):** `NodeMotionTest` (8): a layout move starts where it was shown and ends at exactly 0; placing
  again does not restart; a second move mid-motion keeps place and speed (ease and spring); a size spring ends on its target
  inside its envelope at every frame; the envelope grows only at an interruption; reduced motion jumps; unnamed channels jump;
  Codex's figures exact (29.63, 23.42 px) and eight curves (three ease, five springs including ζ 0.999, 1, 1.001) x three
  speeds x three spans within their bounds at every 0.2 ms. `HostMotionTest` (4): the clock stops once settled (one wake for two
  placements, no step after), bound transforms without a transition, opacity following its binding, reduced motion and
  disposal. `ComposeHostTest` (5, one new): the software layer covers the envelope while moving, the layout box at rest.
- **Not verified:** nothing on a device. Compose re-drawing every node on `invalidate` (so a frame clock step reaches every
  moving node's draw) is judged from the existing `frameRecords` behaviour, not measured; the frames-against-geometry check on
  both drawing paths is the next commit (13a-1b: the `motion-frames` hook on the emulator), with the re-record counts.
- **Decided (judged, logged at integration):** a layered box's centred stroke is cut at its drawn bounds at rest and in
  motion alike (the envelope holds the drawn size only), so nothing changes when motion stops; the status bar has no layered
  centred stroke. A preset without `motion.move`/`motion.state` makes those channels jump (none of the launcher's presets has
  them yet: the default preset's roles come with E2.14a).
- **Size:** 405 production lines with the grammar's 166 apart, over the slice's ~200: the drawing contract (anchor, clips,
  envelope on both paths) did not split without leaving one path wrong in between.

### codex — report

(appended by `scripts/team/codex.sh … work`)

### codex — work, 2026-11-11T13:12Z

- **Commits:** none. `git add` cannot create `D:/Launcher/.git/worktrees/launcher-codex/index.lock` under this sandbox. Seven assigned files remain uncommitted; nothing pushed.
- **Changed:** added the exact motion API, transition/spring grammar, reader/validator integration, diagnostics with spans, tests and `KDL_MOTION.md`. Enter/exit/roll unchanged.
- **Verified (measured):** final source passed 25/25 standalone model tests, including six motion tests. The Gradle run passed 330 engine and 437 app tests. `git diff --check` passed; daemon stopped.
- **Not verified:** fast gate failed on four native configurations (`clang++.exe: Permission denied`); no green build/lint gate. Full gate remains with integration. No device or runtime verification.
- **Surprises:** existing primitive definitions needed no changes. Added a 4,096-character declaration bound; propose recording that limit in the lead’s decision log.
- **Security §7 (judged):** caller checks—no new IPC entry point; inputs—bounded, nonrecursive parsing without reflection; privileged paths—none added; capabilities—unchanged; captures—none; secrets—none. **Refusal paths (measured):** tested bindings, unsupported features, malformed/out-of-range numbers, duplicate channels, empty clauses, limits, codes and spans.

### codex — work, 2026-11-11T16:05Z

- **Commits:** none, per sandbox instruction; four assigned files left modified. Lead to add `Co-Authored-By: Codex <noreply@openai.com>`.
- **Changed:** enter/exit models and grammar, fade-up expansion, curve precedence, refusal diagnostics with spans, tests and documentation.
- **Verified (measured):** 29 model tests, including 10 motion grammar tests; full suites: 355 engine + 437 app tests passed. All six shell scripts passed, including 746 capture checks. `git diff --check` passed.
- **Full gate:** failed only on four native configurations: sandbox denied execution of NDK `clang++.exe`. Log: `build/codex-13a-2-check.log`.
- **Not verified:** complete build/lint, runtime presence rendering or S24 behavior. No phone used.
- **Security §7 (judged):** caller checks N/A—no IPC entry added; inputs bounded to 4096 characters, no reflection; no execution escape added; capabilities unchanged; no captures persisted; secrets untouched.
- **Security §7 (measured):** refusal tests cover codes, reasons and spans.
- **Surprises/next:** existing reader/validator paths needed no edits. Gradle daemon stopped. Lead reviews, commits and reruns the gate outside the sandbox.

### claude — 13a-2 report (`39cf77d`, `2d5d20d`; Codex's grammar `afeb1dd`)

- **Runtime.** A node with `enter` arrives from its effects' values at its first placement (not on the component's first
  build): fade 0, scale 0.85, rise 8 pt below, grow 0 along its parent's axis. A node removed from the plan with `exit`
  stays drawn at its last place, right after the surviving sibling it followed, out of layout (`HostNode.drawn` vs `children`), until its tracks settle;
  then its subtree is released once. A node planned again before then is the same node turning back from where it is, at
  its speed (`revive`, on the enter curve). An ancestor's exit carries its subtree (inner exits do not start). The root
  leaves on its exit and is hidden after. Bounded: at most 64 exits (the oldest ends at once); active plus retained nodes
  never pass 4096. Reduced motion, or a role the preset lacks (`motion.enter`/`motion.exit` are in no preset yet), goes
  at once. `snapMotion` ends running exits; `dispose` releases retained nodes once.
- **Measured, JVM:** `PresenceTest` 9 tests (exit timing ~120 ms, release once, revival same node and continuous,
  ancestor subtree, reduced motion before and mid-exit, disposal, the 64 bound, arrival from fade 0, root exit then
  hidden); all 116 `ui.*` and `model.*` tests pass.
- **Measured, emulator (`component-motion`, both paths):** `presence`: leave 25 frames, arrive 25, leave and called back
  at frame 10 in 35; red's alpha within 0.004 of the movers, the green sibling's edge within 0.00 px; the clock stops
  after each. `motion` and `root` unchanged (0.01 px, 0.00 px).
- **Bug the proof found:** a leaving child was drawn over its later siblings (Compose draws in placement order; leaving
  children were placed last). Fixed: placed in drawing order. Before the fix the proof failed (green's edge 0.4-0.8 px
  off: green under the fading red); after it, 0.00 px.
- **Decisions (judged, to log):** scale arrives from 0.85; rise is 8 pt; revival turns back on the enter curve (else
  exit's). Test clock: `now` reads the last stepped frame, as `ComponentView`'s clock does.
- **Not verified:** the S24 (no component declares presence yet, nothing on screen changes).

### codex — work, 2026-11-11T18:27Z

- **Commits / changed:** none, per round instruction; six assigned files left modified. Added `roll`, `rollCurve()`, agreed precedence, text-only validation, refusal spans, tests and documentation. No UI files touched.
- **Verified (measured):** standalone Kotlin 2.2.21/JUnit: **32 model tests passed**, including 13 MotionGrammarTest tests (3 new). `git diff --check` passed.
- **Not verified / surprises:** Gradle could not start: sandbox denied its wrapper lock. Full gate remains with lead; no device used or Gradle daemon started.
- [x] **§7, judged—callers:** no new executable entry point; caller checks unchanged.
- [x] **Inputs:** roll strings capped at 4096 characters; shared bounded curve grammar; no reflection.
- [x] **Isolation:** no new route to shell, execution, external paths or other apps.
- [x] **Capabilities:** requirements and `uses` derivation unchanged.
- [x] **Captures:** no capture, persistence or transmission added.
- [x] **Secrets:** no secret handling or logging added.
- [x] **Refusal tests, measured:** bindings, unsupported words, invalid curves/types, oversize values and non-text use rejected with checked spans.

### claude — 13a-3 report (`944e0d6`; Codex's grammar `f5c8af2`)

- **Eligibility (`ui/RollEligibility.kt`).** The agreed filter (ASCII digits, `: . , % / -`, space, 32 characters at most),
  then the complete string in its configuration. **Changed from §3, with the reason:** the background pixel check left
  every new string pending at the moment it changes (a clock's next minute is always new), so on the emulator every change
  rolled whole. On Android 12+ the check is now your round-two alternative, "identical shaped glyphs and positions":
  `TextRunShaper` shapes the whole run and each character alone; eligible only if there is one glyph per character with the
  same glyph id and font, at the run's advance for its offset (plus the glyph's own offset), on the same baseline. It runs
  at once (0.18 ms a string on the emulator, once per string and configuration: cached, LRU 256, keyed by the paint's
  typeface, size, features, letter spacing, scale, skew, flags and the tokens' version). Before Android 12 the pixel check
  stays, off the drawing thread, whole while pending.
- **Measured, emulator (`component-roll`):** 208 fixture strings × configurations (text family 400/600, tnum or not, 12,
  17, 22.5 px, and the system font) equal pixel for pixel whole vs by characters at four sub-pixel origins; all 208 alike
  by glyphs, none alike by glyphs and unlike by pixels; `ffi` (a ligature) refused by both checks; the cache bounded.
- **Roll (`ui/motion/RollMotion.kt`, wired in `TextPainter`, stepped with `NodeMotion`).** StatusBarView's contract:
  by characters when both strings are eligible, of one length and every unchanged character where it was; else whole.
  Up for a larger number, down for a smaller, else up; changed characters start `motion.text.stagger` apart (the preset
  role's duration; none in any preset yet, so 0); each glyph its own track, so a change mid-roll turns every glyph from
  where it is (a glyph that never started arriving goes at once instead of crossing its place); at most 2 leaving per
  slot. The roll starts from `measure` (which sees every content change); at rest the text draws whole; while rolling the
  painter's key holds the glyphs' places (re-recorded each frame). Rise 0.55 × size (StatusBarView's).
- **Measured, JVM:** `RollMotionTest` 8 tests; all `ui.*` and `model.*` pass.
- **Measured, emulator (`component-motion` `roll`, both paths):** `09:58` to `09:59` in 20 frames, `09:59` to `10:00`
  turned to `10:01` at frame 6 in 26 frames: the unchanged characters' columns equal the rest frame's pixels at every
  frame, the clock stops, the last frame equals a fresh view of the final text. Judged from the frames: the changed digits
  roll in their columns (`tools/shots/roll_sheet.png`, not committed). The jump check (ink centre per column) is weak:
  outgoing and incoming ink average out; the frames were looked at.
- **Decisions (judged, to log):** the glyph check replaces the pixel check on Android 12+; stagger from a preset role's
  duration; at most 2 leaving per slot; glyphs are not clipped to the text's box (StatusBarView's look: they fade out as
  they leave it).
- **Not verified:** the S24 (no component rolls yet), a font with kerning between digits (none of the fixtures kern).

## 6. Cross-check

Each agent reviews the other's diff and report. Findings as `fix`, `question` or `note`, with file:line. The owner of a
finding replies inline when it is resolved. Security checklist (protocol §7) answered here when it applies.

### codex — review, 2026-11-11T06:08Z

Reviewed `5e4073d`. **Judged:** source and maths review. **Measured:** independent Python calculations; existing XML records 8 continuation and 4 ticker tests passing. I did not rerun Gradle: this session is read-only.

- **fix — `Continuation.kt:97–99`:** Zero `dx` can occur inside a valid curve. For `cubic-bezier(1,0,0,1)` at progress 0.5, `dx=0`, `dy=1.5`; the code substitutes endpoint slope 0. A continued 0→120 motion with zero carried speed therefore reports zero velocity at its steepest point. Handle interior singularities explicitly and add a regression.
- **fix — `Continuation.kt:55,78,86`:** Snapping one microsecond early creates a position jump for vertical end tangents. For `(0,0,1,0)`, 220 ms and a 1000 px range, the remaining distance immediately before this cutoff is approximately **3.69 px**. Use the actual duration boundary; test vertical and degenerate end tangents immediately before completion.
- **fix — `ContinuationTest.kt:71`:** `.let { true }` always passes and cannot prove the fit was cached. Replace it with an observable fit-count assertion.
- **note — `Continuation.kt:71–88,110–120`:** Away from singularities, the carried coefficient and its derivative are correct; first noncoincident control points give the correct endpoint tangent limits. The vertical-start spring fallback preserves supplied position and velocity.
- **note — `FrameClock.kt:41–57`; `ComponentView.kt:71,233–236`:** Same-thread ticker coalescing and cancellation look correct. The watch is closed by the component’s idempotent disposal. Tests cover ticker cancellation and scale conversion, but do not exercise Android observer registration, delivery or unregistration.
- **note — security §7:** No caller-facing entry point, external-input parser, privileged path, capability change, capture or secret handling is added; refusal-path testing is not applicable. The `Env` addition does not change expression evaluation.

**codex: OBJECT — resolve the derivative and early-completion defects and replace the ineffective cache assertion before signing off 13a-0.**

### codex — review, 2026-11-11T06:33Z

Reviewed `b48e029`. **Judged:** source and maths review. **Measured:** independent Python calculation; existing XML records 10 continuation and 4 ticker tests passing. Gradle was not rerun: this session is read-only.

- **fix — `engine/src/main/java/dev/launcher/engine/ui/motion/Continuation.kt:106–112`:** Interior singularities remain incorrect when **both** `dx` and `dy` vanish. For `Curve.Ease(1f,1f,0f,0f,220f)`, x and y are identical, so progress is linear with slope 1. At its midpoint, the fallback returns `1e6 * signum(0) = 0`. A fresh 0→120 motion therefore reports **0 instead of 545.45 px/s**, losing velocity on interruption. Evaluate the derivative-ratio limit when both vanish and add this regression.
- **note — `Continuation.kt:80–95`; `ContinuationTest.kt:71–73`:** Exact-duration completion and the observable cache-count assertion resolve those two findings. The added vertical-instant regression covers the original nonzero-`dy` example.
- **note — thread §6:** The requested lead reply above §7 was absent when read; I reviewed the latest commit directly. Security assessment remains unchanged: this correction touches none of §7’s attack surfaces.

codex: OBJECT — 13a-0: resolve the remaining interior derivative limit before sign-off.

### codex — review, 2026-11-11T06:57Z

Reviewed `b48e029` and `9cc9071`. **Judged:** source and maths inspection. **Measured evidence:** existing JVM XML records 11 continuation and 4 ticker tests passing; I did not rerun Gradle in this read-only session.

- **note — `Continuation.kt:119–126`:** The derivative-ratio limit resolves the remaining finding: `(1,1,0,0)` now returns slope 1, preserving 545.45 px/s at its midpoint.
- **note — `Continuation.kt:80–95`; `ContinuationTest.kt:71–73,118–130`:** Exact-duration completion, observable cache checking and regression coverage resolve the earlier findings.
- **note — thread §6:** The requested lead reply above §7 is still absent; both commits were reviewed directly.
- **note — security §7:** These corrections touch none of the listed attack surfaces; the previous assessment stands.

codex: AGREE — 13a-0: checked both corrective commits, derivative limits, exact completion, cache assertion and recorded regression results; all blocking findings are resolved.

**claude — on Codex's 13a-0 review and sign-off check:** fixed in `b48e029` and `9cc9071`: an interior vertical instant
(dx 0, dy not) returns the vertical slope; where both vanish inside the curve the speed is the ratio of their first
non-vanishing derivatives (`aPauseInBothCoordinatesKeepsTheLimit`: `cubic-bezier(1,1,0,0)` reports 545.45 px/s at its middle
for 120 px over 220 ms); ends keep their tangent limits; the curve ends at its exact duration (`aVerticalEndLandsWithoutAJump`);
the fit cache is counted (`fitCount`). Each new test fails on the commit before its fix. `scripts/check.sh` PASSED. The
watch's Android registration is exercised on the device only.

### claude — review of 13a-1's grammar (`9b8be46`), 2026-11-11

Read the diff; 6 tests pass in the merged build (and every model test). No blocking finding.
- **note — `MotionGrammar.kt:114`:** `numbers.any { it.toFloat() <= 0f }` repeats the two bounds before it.
- **note — `MotionGrammar.kt:54–55`:** a word after the channels that is a plain name (`place linear`) is reported as an
  unknown channel; a hint that a curve needs a duration (`200ms linear`) would say more. Not blocking.
- **note — the 4096-character bound:** agreed; logged with 13a-1's decisions.
- **note — security §7 (claude, judged):** the grammar reads file input only, bounded (4096 characters, no recursion, regexes
  without nested quantifiers over unbounded input), never evaluates or resolves a token, and refuses bindings; no IPC, privilege,
  capability, capture or secret is touched. Refusal paths are tested (Codex's list).

### codex — review, 2026-11-11T13:20Z

Reviewed `7df5eed` against §3. **Judged:** source, maths and drawing geometry. **Recorded measurement:** existing JVM XML shows 8 NodeMotion, 4 HostMotion and 5 ComposeHost tests passing. I did not rerun tests or use a device; this session is read-only. Paths below are under `engine/src/main/java/dev/launcher/`.

- **fix — `engine/ui/ComposeHost.kt:76,224`; `engine/ui/motion/NodeMotion.kt:98`:** Centred size and place motion double-compensate the same layout change. In a 300 px stack, a centred child growing 100→200 moves its layout x from 100→50. Place adds 50, and the size anchor adds another 50: its first drawn left becomes **150 instead of 100**. Include the old/new anchor offsets when computing place continuity. Test simultaneous size/place growth, shrink and reversal.

- **fix — `engine/ui/ComposeHost.kt:81–85`:** Whole-pixel placement omits the child’s own rest translation. With parent fraction 0, residual 0.3 and child `translate-x=0.5`, the node finishes at x=0.5 and the painter receives residual 0.3; the complete rest position is 0.8, requiring whole origin 0 and residual 0.8. Include the child’s rest contribution in rounding and compensation; test fractional declared translations and interrupted motion.

- **fix — `engine/ui/ComposeHost.kt:69`; `engine/ui/ComponentView.kt:127–130`:** Root placement always supplies `(0,0)`, although its actual integer placement and baseline fraction can change outside `NodeMotion`. A centre-line change therefore jumps the root even with a declared place transition. Pass the actual root placement through the continuity calculation while keeping baseline correction separate; test centreY and font/centre-line changes.

- **fix — `engine/ui/Host.kt:185–192`; `engine/ui/motion/NodeMotion.kt:60,65`:** The node envelope is rebuilt from track bounds every frame, but each track discards its bounds upon settlement. If width settles before height, the layer’s horizontal bounds shrink while height still moves. Retain the node-level envelope until size motion finishes, expanding only at interruptions. Test unequal width/height settling times and assert unchanged layer bounds between retargets.

- **fix — `engine/ui/Host.kt:189–192`; `engine/ui/ComposeHost.kt:203,232`:** Stroke extents remain absent from the envelope. The report’s assertion that a layered centred stroke is cut identically during motion and at rest does not follow: during shrink, the retained larger envelope permits the moving right/bottom stroke outside the drawn rectangle; at rest, the target-sized layer cuts it. Preserve stroke extents as §3 requires, or resolve an explicitly different clipping contract. Add layered centred-stroke growth/shrink captures on both paths.

- **question — `engine/ui/Host.kt:262–265`; `engine/ui/ComponentView.kt:108`; `engine/ui/ComposeHost.kt:107`:** Please establish the View-invalidation assumption in 13a-1b. Steps change plain fields and call `view.invalidate()`; they neither apply RenderNode properties directly nor explicitly invalidate the DrawModifierNode. Android provides [`invalidateDraw`](https://developer.android.com/reference/kotlin/androidx/compose/ui/node/DrawModifierNode#extension-functions) for requesting its draw pass. This is an integration question, not a confirmed caching defect: require progressing pixels and record counts after an otherwise unchanged layout, including a nested moving node.

- **note — `engine/ui/motion/NodeMotion.kt:44–53`; `engine/ui/motion/Envelope.kt:30–62`:** Fresh versus continued selection correctly depends on track state, preserving interruptions at zero velocity. The ease hull uses corrected carry; both underdamped bounds, both overdamped bounds and the critical bound are conservative in the implemented spring regimes. The dense numerical tests are useful, but do not cover the placement, anchoring, stroke or envelope-lifetime failures above.

- **note — `engine/ui/Host.kt:301`; `engine/ui/ComponentView.kt:241–245`:** Moving-set removal, snap, release and disposal look sound. The tests exercise host bookkeeping directly; actual drawing, cancellation registrations and both rendering paths still need the planned integration checks.

- **note — security §7:** This runtime commit changes none of the listed attack surfaces: no caller-facing entry point, parser/evaluator, privileged path, capability derivation, capture or secret handling. Refusal-path testing is therefore not applicable to this commit.

**codex: OBJECT — resolve the geometry, envelope-lifetime and stroke findings; complete the planned drawing-path verification before signing off 13a-1.**

**claude — on Codex's 13a-1 review (fixed in `7e85bf2`):** all five fixes accepted; my report's stroke claim was wrong.
- Place continuity is measured at the anchor point (`NodeMotion.update`'s `anchorX/Y`, the old box's anchor against the
  new): a centred box growing 100 → 200 in a 300 px stack is drawn from 100, not 150 (`aCentredGrowthIsNotCompensatedTwice`,
  with a reversal mid-way, no jump).
- Whole pixels include the node's own rest translation (`wholePixel(parent, residual, rest)`: parent 0, residual 0.3,
  translate 0.5 gives origin 0 on the screen, residual 0.8; `wholePixelsIncludeTheNodesOwnRestTranslation`).
- The root is placed from its window (`ComponentView`'s layout: `root.placed(0, whole, …)`, the baseline's fraction kept on
  its own translation); `ComposeHost` no longer places it at (0, 0) (`theRootsWindowPlaceMovesOnItsTransition`).
- The node's envelope is kept and only grown until both size tracks settle (`theEnvelopeHoldsUntilEverySizeTrackSettles`:
  height settles first, all four edges never shrink, dropped at rest).
- A box's centred stroke: its outer half is in the bounds at rest and in motion (`HostNode.drawBounds`, both paths: the frame
  node's position and the software layer); no layered centred stroke exists in the status bar, so nothing at rest changes on
  screen (`aCentredStrokeIsInsideTheBoundsAtRestAndInMotion`).
- **Failed before the fix: judged, not run.** The new tests call what the fixes added (`wholePixel`, `drawBounds`, the anchor
  parameters), so they cannot compile on `7df5eed`; each case was worked through on the old code (150 instead of 100; residual
  0.3 instead of 0.8; envelope edges shrinking at height's settle; the stroke's outer half outside the target-sized layer).
- **Your question (invalidation):** taken into 13a-1b as an acceptance check: progressing pixels and record counts for a
  nested moving node after an unchanged layout, on both paths; `invalidateDraw` on the draw node if `view.invalidate()` does
  not reach it.
- UI tests 75/75 green (measured, JVM).

**claude — on Codex's 13a-1 sign-off objection (fixed in `c230651`):** both accepted. The anchor counts in place continuity
only while the size has a curve (a size that jumps leaves the old edge to hold: `aPlaceOnlyTransitionHoldsTheOldEdgeWhenTheSizeJumps`,
growth and shrink, centred in 300 px: starts at 100 / 50 where it was); the root is placed with its full `top`, the baseline's
fraction included, while that fraction is still applied on its own translation (`fractionalPlaceChangesAreCarried`: 12.25 →
12.75 and across a whole pixel, 12.75 → 13.25, shown where it was). UI tests green (measured, JVM); the root's path through
`ComponentView` is on the device check list of 13a-1b. 13a-1b (frames against geometry on both paths, nested invalidation,
record counts) stays the merge condition into `engine`, as you put it.

### claude — 13a-1b report (`80876d2`)

- **What changed:** `engine/src/androidTest/.../ui/MotionProof.kt`, mode `component-motion` of the proof runner: a component
  in the test APK's window on blue, its motion stepped at exact 120 Hz times through two new `ComponentView` parameters
  (`frames: FrameSource`, `clock`; the display's by default). Each step: the window by `PixelCopy` (GPU) and the view drawn
  into a software bitmap, edges read from colour coverage (sub-pixel), against movers built with the same continuation rules.
  `motion`: a red box 100 → 300 px on `spring(0.4, 0.7)`, its yellow child sliding 0 → 100 on its own `place 120ms`
  (nested, revealed through the red box's drawn bounds), its green sibling making room on `place 200ms`, all reversed at
  frame 12 to 150 px. `root`: the root with `place 200ms`, its centre line 400.25 → 460.75 (60.5 px, across whole pixels).
  Runtime fix: **your invalidation question was right.** On the GPU path a step recorded no frame and nothing moved
  (measured: frame 1 "no frame recorded", red edge 60 against 101.6; the software path moved): the window's invalidation does
  not draw a Compose node again. Each moving node now asks its own draw node (`HostNode.redraw`, set by `DrawNode` while
  attached: `invalidateDraw`), at every step and at a snap.
- **Verified (measured, Android 17 emulator):** `motion`: 77 frames, both paths within 0.01 px of the movers (red size, green
  place, yellow nested place, the reversal), a frame recorded at every captured step, the frame source holding no callback once
  settled (the clock stopped). `root`: 25 frames within 0.00 px on both paths, through `ComponentView`'s real root path.
  Record counts: content (the painters' recordings) is re-recorded only while a size moves; frames are re-recorded for every
  node at each step (root case: +2 a step for its 2 nodes): a node without a Compose layer of its own redraws its whole window.
  Its cost on the S24 is E2.17's measure. `scripts/check.sh`: see below.
- **Proof harness lessons (not engine bugs):** the fake frame source must drop its callback when it runs, as Choreographer does;
  the green sibling legitimately covers the red box's edge when it slides over it (drawn later); the window's title bar has
  red in its text, so edges are read from the fixture's own colours only.
- **Not verified:** the S24; reduced motion turned on mid-motion on a device (JVM-tested).

### codex — review, 2026-11-11T14:39Z

**codex: OBJECT — the runtime fix looks sound, but two proof gaps leave the merge condition incomplete.** Reviewed `80876d2` and the lead’s report; judged from source. No files changed, builds rerun or device used.

- **fix — `engine/src/androidTest/java/dev/launcher/engine/ui/MotionProof.kt:100,128–135,165`:** Missing animation can pass: `motion` accepts zero callbacks/frames; `root` accepts stopping at frame 0, where expected displacement is zero. Require callbacks and intermediate movement before accepting settlement. Also reject nonfinite edges explicitly: the edge readers return `NaN` when absent, and `NaN > 1f` is false.
- **fix — `MotionProof.kt:151–163`:** The 1 px tolerance accepts the previous fractional-root bug. Passing only `floor(top)` produces an immediate **0.5 px jump** in this fixture, below the failure threshold. Use a coverage tolerance below 0.5 px and include a fractional-only centre-line change; demonstrate failure with the former root placement.
- **question — `MotionProof.kt:115–117,230–232`:** Only aggregate frame-count growth is asserted; content counts are discarded and root counts appear only on failure. Please retain the measured count deltas supporting the report’s content-cache and “+2 per step” claims.
- **note — `ComponentView.kt:54–56,78,112`; `MotionProof.kt:45–55,104–111,188–189`:** Clock and callback timestamps share the injected nanosecond base; callbacks are consumed once. Reversal expectations correctly carry current value/velocity and the sibling’s layout delta. PixelCopy and software drawing exercise separate paths with frozen motion time.
- **note — `ComposeHost.kt:96–115`; `Host.kt:283–298`:** Attachment installs the redraw callback, detachment clears it, and stepping/snap invalidate moving draw nodes, including the final settled draw. No runtime objection found.
- **note — security §7:** This commit changes none of the listed attack surfaces. Captures contain the test fixture’s own window; caller/capability, parser-boundary, secret-storage and refusal-path checks are not applicable here.
- **note — verification:** The reported emulator measurements support the approach, but I did not reproduce them. The report’s “`scripts/check.sh`: see below” has no corresponding 13a-1b result; record the required full gate before integration.

**claude — on Codex's 13a-1b review (fixed in `3dfbd34`):** all accepted, and the tighter proof found a real bug.
- The proof now requires motion: `motion` at least 20 frames and the red edge seen midway (110..290 px) on the GPU; each
  `root` move at least 8 frames and seen away from both ends; a missing edge (NaN) is an error.
- Tolerance 0.25 px (edges read from coverage agree to 0.01). A second root move inside a pixel (460.75 → 460.10, floor
  unchanged). **It failed on `80876d2`:** the first frame showed the new place without the motion's shift (+59.85 against
  +60.5, a 0.65 px jump): placement had set the shift, but placement does not draw a node again. `HostNode.placed` now asks
  for a draw when what it shows changed (`aPlacementThatChangesWhatIsShownAsksForADraw`, JVM). Measured on the emulator: with
  the root placed at its whole pixel only (the former code, built for the run and restored), the proof fails ("move 1: no
  motion seen"); with the current code it passes.
- Counts kept in the result (measured): `motion`: content re-recorded 1 a step (the growing box's surface; 2 at the
  reversal's relayout), frames 4..40 a captured step (4: every node; 40: ten steps between samples after frame 60); `root`:
  content +0 over both moves, frames 2 a step (its 2 nodes).
- Results (measured, emulator, `3dfbd34`): `motion` 77 frames within 0.01 px on both paths; `root` 60.5 px in 25 frames and
  −0.65 px in 25 frames, within 0.00 px on both paths. Full gate: `scripts/check.sh` PASSED on `80876d2`; run again on
  `3dfbd34` before the merge.

### claude — review of 13a-2's grammar (`afeb1dd`), 2026-11-11

- note: `enter`/`exit` stay data-only (bindings refused, `@` only for `@motion.` roles, 4096-char cap), one clause, effects
  before the curve, `fade-up` = fade + rise, duplicates and channel words refused with spans. Precedence matches the
  agreed API. Nothing to fix; the runtime compiles and runs against it unchanged.

### codex — review, 2026-11-11T17:09Z

**codex: OBJECT — resolve the lifecycle and continuity defects below.** Reviewed `39cf77d` and `2d5d20d` from `D:/Launcher`. **Judged:** source inspection. **Recorded measurement:** lead’s XML shows 9 presence tests passing. No files changed, builds rerun or device used. Production paths below are relative to `engine/src/main/java/dev/launcher/engine/`.

- **fix — `ui/Host.kt:460,474,506`:** Preflight double-counts nodes being revived: 4095 active nodes plus one retained node fit the bound, but a 4096-node plan including that retained key evicts it before revival, replacing it and breaking continuity. Also, replacement nodes are allocated before removed active nodes are released or evicted, allowing temporary allocations above 4096. Account for reused identities and make room before allocation; test both cases.
- **fix — `ui/ComposeHost.kt:69–75`:** Fixed integer constraints do not preserve exact geometry. A leaving box with width `(px)10.2`, allocated 11 px, is remeasured with minimum width 11; its exact width becomes 11 immediately. Its descendants are also laid out again. Preserve fractional measures and subtree placement; add fractional and nested exit proofs on both drawing paths.
- **fix — `ui/Host.kt:498–506,523–528`:** An independently exiting child beneath a revived ancestor is collected as an orphan when it remains absent. `startExit` appends it to `exits` again without removing its existing entry. Completion leaves a stale entry; later eviction can match and release a newer retained instance with the same key. Preserve existing exits without duplicate registration; test child exit → ancestor exit → ancestor revival without child → child completion and key reuse.
- **fix — `ui/Host.kt:180`; `ui/motion/NodeMotion.kt:126–133`:** Replacing a revived node’s bound presence target can snap it. For example, revive a fading node with a new opacity of 0.5: `revive()` heads toward its former opacity, then placement calls `alpha.to(0.5, null, …)` and snaps. Retarget presence toward the updated bindings on the revival curve; test changed opacity and size during revival.
- **fix — `ui/Host.kt:180,510–512`; `ui/motion/NodeMotion.kt:132`:** The root still receives normal placement while leaving. Replacing it midway through a fade calls `alpha.to(1, null, …)`, cancelling the fade; the next step hides it abruptly. Exercise a centre-line/layout change during root exit. After a completed exit, showing the root also bypasses `enter`; the current root test checks only `hidden`, without placement or alpha.
- **question — `ui/Host.kt:533–539`:** Is retained order defined by the previous index or surviving predecessor? Old `[a,b,c]`, new active `[c,a]`, with `b` leaving, produces `[c,a,b]`. The report promises the old index; implementation follows `a`. Settle the contract and test simultaneous removal, insertion and reordering.
- **note — `ui/motion/NodeMotion.kt:167–172`; `engine/src/androidTest/java/dev/launcher/engine/ui/MotionProof.kt:210–282`:** Existing place/size tracks continue during exit, so “frozen geometry” does not freeze the displayed position of an already-moving node. The proof covers fade with one moving sibling; it does not establish that case, grow/rise/scale, the arrival’s frame zero, nested retention or root presence. Add regressions alongside the fixes. Record the full gate result on the corrected tip.
- **note — security §7:** These commits change none of the listed attack surfaces. No checklist is required; the proof captures its own fixture window.

### claude — reply to the 13a-2 review (`76fde1a`), 2026-11-11

All five fixes taken; each has a regression test. Measured: `PresenceTest` 16 tests, all 123 `ui.*` + `model.*` pass; on
the old code 4 of the new tests fail (named below), and the emulator's new `rise` proof fails (101.0 vs 100.4 px).

- **Bounds (agreed, fixed):** the preflight counts what is alive while the plan is built (active + retained + the plan's
  new nodes; a planned key reuses its node), evicts the oldest exits that are not planned again, then releases removed
  nodes at once before anything is made. `aRetainedNodePlannedAgainAtTheBoundIsRevivedNotReplaced` (old: replaced),
  `aWholeNewPlanNeverHasMoreThan4096NodesAlive` (old: 8176 alive).
- **Fractional measure (agreed, fixed):** a leaving child is measured again with the constraints it last had
  (`HostNode.measuredWith`), so its exact size and its subtree come out as they were. Emulator `rise`: a 100.4 px box with
  a 40.3 px child rising 8 px, within 0.01 px on both paths; with the old fixed constraints the box drew 101.0.
- **Orphan registered twice (agreed in part, fixed):** the double registration was real, but the stale entry could not
  outlive the exit: `finishExit` removes every exit in the subtree it releases, the node itself included. So no
  failing-before case exists; the test (`anExitUnderARevivedAncestorKeepsItsOwnExitAndEndsOnce`, your sequence plus the
  64-exit cap with the key reused) guards it. Fixed anyway: a node already leaving keeps its exit (and what it carries
  stays with it), `startExit` never registers twice, and `finishExit` releases by key and identity.
- **Revival retarget (agreed, fixed):** a track on a presence motion carries its curve (`Track.present`); a channel with
  no curve retargets on it until it settles. `aRevivedNodeGoesToItsNewValuesWithoutAJump` (old: snapped to 0.5).
- **Root while leaving (agreed, fixed):** while a node leaves, its exit's tracks keep the exit's values whatever placement
  says (`NodeMotion.update`); at the exit's end the root is set at rest (`ended`), and shown again it arrives on its
  `enter`. `theRootPlacedWhileItLeavesKeepsFadingAndArrivesWhenShownAgain` (old: alpha 1 at once).
- **Order (answer):** the contract is the surviving predecessor: a leaving node draws right after the sibling it followed
  that is still there (first if none), so it stays next to what it was next to when the others reorder. My report's "old
  index" was wrong wording. Test: `aLeavingNodeFollowsTheSurvivingSiblingItFollowed` (`[a,b,c]` to `[c,a]`: `[c,a,b]`).
- **Frozen geometry (note, agreed):** what is frozen is layout: a leaving node is not laid out again. Motions already in
  flight go on to their targets (its parent no longer changes them). Proofs added: `rise` (rise effect, nested child,
  fractional size); unit tests cover each effect's arrival and exit values, an arrival's first value, nested retention and
  the root. The gate runs on the tip before merging.

### claude — review of 13a-3's grammar (`f5c8af2`), 2026-11-12

- note: `roll` is data-only (#true/#false or a literal curve, bindings refused, 4096 cap), only on `text`
  (`motion-roll-not-text`), `stagger`/`up`/`down` refused as not yet supported; precedence curve > node spring >
  `motion.text.roll` through an internal marker. Nothing to fix; the runtime reads `rollCurve()` unchanged.

### codex — review, 2026-11-11T18:54Z

**codex: OBJECT — resolve the configuration, retention and proof defects below.** Reviewed `944e0d6`, its diff from `D:/Launcher`, and §5’s report. **Judged from source**; no files changed, tests rerun or device used.

- **fix — `ui/motion/RollMotion.kt:51–54`; `ui/TextPainter.kt:47–58,75–79`:** Same-text measurement ignores new positions unless eligibility changes. After a font/size/token change, glyphs retain their former offsets and widths; during a roll, outgoing glyphs also immediately use the new paint, baseline and rise. Rechecking eligibility under the new paint does not verify the stored geometry. Track the configuration with the roll and define its handover; add regressions for unchanged content followed by a change, and simultaneous content/configuration changes.
- **fix — `ui/motion/RollMotion.kt:101–115`:** Never-started arrivals count against the leaving bound before `step` removes them. Start at `"1"`, then call `to("2")`, `to("3")`, `to("4")` without stepping: invisible `"2"` and `"3"` evict the still-visible `"1"`, leaving every retained glyph at zero alpha. Remove unseen arrivals before bounding; test this sequence and the staggered equivalent.
- **fix — `ui/RollEligibility.kt:40,59–64,72–73`:** The verdict LRU is bounded, but the pre-12 pending set and executor queue are unbounded. Every distinct candidate retains a key, paint and task until processed. Bound outstanding verification too, falling back to whole-string rolling when full; test with an executor whose tasks are held.
- **fix — `ui/motion/RollMotion.kt:125–130`:** Removing every nondigit reverses numeric direction for supported strings: `"-3" → "-2"` rolls down; `"1.9" → "1.10"` rolls up. Preserve signed/decimal numeric ordering, retain the agreed clock comparison, and use the fallback for ambiguous labels.
- **question — `ui/RollEligibility.kt:28–32,97–107`:** Glyph/font/position equality is a sound shaping argument **under identical rendering settings**, but these comparisons allow position differences of 0.001 px, which do not establish pixel identity. Letter spacing is checked through shaped positions; fake-bold/subpixel flags and explicit skew are keyed. Hinting and text locales are absent from the key. Restrict/document the supported paint configuration or include its remaining relevant fields, and justify the tolerance. Alpha and knockout do not independently invalidate identical coverage with the same paint/order; they need coverage tests too. Android’s drawing path also applies paint settings and font synthesis after layout ([AOSP Canvas](https://android.googlesource.com/platform/frameworks/base/+/master/libs/hwui/hwui/Canvas.cpp)).
- **fix — `ui/TextPainter.kt:58`; `ui/Host.kt:238–241`; `ui/ComposeHost.kt:229–233`:** Roll ink extends beyond the text box, but isolation bounds include only layout/size motion and stroke. In particular, software `saveLayerAlpha` can cut rolling ink when the text has opacity or a layer. Include roll extents and outgoing widths in the drawing bounds; prove both paths with isolation and with a simultaneous SIZE transition.
- **fix — `androidTest/.../ui/MotionProof.kt:375–399`; `RollProof.kt:37–59`:** The roll proof requires a callback but no intermediate movement; snapping on the first callback can pass. Missing changing ink (`NaN`) is ignored, and continuity is checked only through the acknowledged weak software ink average. Require intermediate changed-glyph motion on both paths and a geometry-based interruption check. `RollProof` records pixel failures without requiring none, and its ligature control need not reject anything; make the advertised assertions explicit.
- **note — `RollMotion.kt:51–54`; `NodeMotion.kt:163,228`; `ComponentView.kt:74–75,249–262`:** Repeated measurement of unchanged content does not restart timing. Reduced motion snaps delayed and active glyphs; disposal cancels the ticker and releases host nodes. Verdict-map and pending-set accesses are synchronized, although Android 12+ shaping holds the shared cache lock and serializes callers.
- **note — security §7 / verification:** This commit touches none of §7’s listed attack surfaces; the checklist is not applicable. The reported emulator/JVM results were not reproduced. Targeted Gradle tests would write build outputs in this read-only session; the full gate was excluded as requested. S24 performance remains unverified.

### claude — reply to the 13a-3 review (`0946494`), 2026-11-12

All six fixes taken, the question answered by tightening. Measured: `RollMotionTest` 11 tests, all `ui.*` + `model.*` pass;
on the old code `directionFollowsSignedDecimalNumbersAndTimes` and `unseenArrivalsNeverPushOutAVisibleGlyph` fail, the
font test does not compile (it uses the new configuration argument); the emulator's reach proof fails without the bounds
fix (0 pixels of rising ink above the box) and passes with it (118, both paths).

- **Configuration (fixed):** the roll keeps the configuration its glyphs were laid out in (`RollEligibility.Config`, from
  the painter's paint and the tokens' version). A new one lays the text out again at once, mid-roll too; a text change
  that comes with it shows at once (a change of look is not a roll). Test: `anotherFontLaysTheTextOutAgainAtRestEvenMidRoll`.
- **Unseen arrivals and the bound (fixed):** arrivals never seen are removed before the bound counts. Your sequence and
  the staggered one: `unseenArrivalsNeverPushOutAVisibleGlyph`.
- **Pending (fixed):** at most `MAX_PENDING` (16) background verifications wait; past that a string rolls whole and is not
  queued. Emulator, worker held: 40 asked, 16 pending, 24 whole, 16 queued, 0 after the worker.
- **Direction (fixed):** times (digits and `:`) keep the agreed comparison; otherwise each string's one number, signed and
  decimal with `,` grouping (`-3`→`-2` up, `1.9`→`1.10` down); none or several numbers: up.
- **Tolerance and settings (answered, tightened):** measured on the emulator, the largest shaped position error over every
  fixture string is exactly 0, so the tolerance is now 0 (equal positions only). The key now also holds variation
  settings, word spacing, hinting, text locales and elegant height (fake bold, subpixel and the rest are in `flags`).
  Translucent colour and knockout (`DST_OUT` over an opaque fill): all 208 strings pixel-equal whole and by characters.
  The glyph check is still a shaping argument; the pixel check on the device is its evidence for these configurations,
  and `RollProof` fails if any string alike by glyphs is unlike by pixels.
- **Drawing bounds (fixed):** while a text rolls, its drawing bounds reach a rise above and below and an outgoing string's
  extra width on each side (`HostNode.rollReach`), on both paths; proof `reach`: translucent text under a size transition.
- **Proofs (fixed):** `roll` now draws, at each frame, what the roll's model says (the same `RollMotion` fed the same
  changes at the same times) and requires every changing column of the software frame to be those glyphs to the pixel row
  (glyphs sit on whole rows; a place within a hair of a half row rounds either way, each glyph on its own: measured, one
  row), the GPU frame equal to the software one in the text's region (0 pixels differ), and at least 4 frames in motion on
  both paths (9 and 18 seen). `RollProof` now fails on any pixel failure, on a control that does not differ, and on an
  unbounded queue.
- **Note (agreed):** shaping no longer holds the cache lock.

### claude — reply to the 13a-3 sign-off round, 2026-11-12

- **Configuration (fixed):** the roll's configuration is geometry only (the tokens' version left out: a colour's
  crossfade changes nothing). A real font or size change no longer snaps: every glyph keeps its track, the showing ones
  take their places in the new layout (each character its new start, a whole string its new width), leaving ones keep
  theirs, and a text change with it rolls on from there. Test: `anotherFontLaysTheGlyphsOutAgainAndTheRollGoesOn` (the
  roll still moving across the change, the arriving glyph at the same y, new offsets, settling on the new layout).
- **Ancestors' layers (fixed):** at every step each rolling text's drawing bounds are carried up through its ancestors as
  far as they leave a box (`ComponentHost.spread`, `HostNode.childReach`), cleared and worked out again each step, and the
  ancestors whose bounds change draw again. Emulator, both paths, rising ink above the text's box: translucent text under
  a size transition 118 px, a row with a layer 125, a translucent row 118; without the fix the layered row shows 0 (fails).
  All other proofs unchanged.

### claude — reply to the second 13a-3 sign-off round, 2026-11-12

- **Font change mid-roll (owner's decision, D-101):** the same finding has now needed a third try, so I asked the owner.
  Matheesha chose "switch at once": a font or size change is a change of look, not of content; the text shows in the new
  font at once, a roll in flight ends, and a text change with it shows at once (the clusters flag is set from the new
  configuration's verdict, so no unverified character drawing goes on). Colour changes still leave a roll alone (geometry
  only in the key). Your objection is recorded as overruled by the owner. Test: `anotherFontShowsTheTextInItAtOnceEvenMidRoll`
  (same font: the roll goes on; new and ineligible mid-roll: whole, at rest; text and font together: at once).
- **Scale (fixed):** a child's bounds go into its parent's px scaled about its box's centre (the frame's pivot), then
  moved. Emulator, both paths: a text at `scale=2` in a row with a layer shows 81 px of rising ink 23-30 rows above its
  box; without the fix 0 (fails). All other proofs and `RollProof` pass on the tip.

## 7. Sign-off

- **13a-0:** `claude: AGREE — 13a-0: 15 motion tests and the full gate green; Codex's four findings fixed with regressions.`
  `codex: AGREE — 13a-0: checked both corrective commits, derivative limits, exact completion, cache assertion and recorded regression results; all blocking findings are resolved.`

- **13a-1a** (grammar `9b8be46`, runtime `7df5eed`, fixes `7e85bf2`, `c230651`):
  `claude: AGREE — 13a-1a: UI tests green on c230651, model tests and the full gate green on 7e85bf2; Codex's seven findings fixed with tests.`
  `codex: AGREE — 13a-1a: checked c230651 against both sign-off findings and the lead's reply; both are resolved at source level.`
  Merge condition into `engine` (both): 13a-1b passes (frames against geometry on both drawing paths, nested invalidation,
  record counts, `ComponentView`'s root path for fractional and centre-line changes).

- **13a-1** (13a-1a and 13a-1b, `9b8be46`..`3dfbd34`), merged into `engine`:
  `claude: AGREE — 13a-1: the motion proof passes on the emulator on both drawing paths (77 frames within 0.01 px; the root's 60.5 and −0.65 px moves within 0.00 px), fails on the former root placement; JVM tests and scripts/check.sh green on 3dfbd34.`
  `codex: AGREE — 13a-1 (13a-1a + 13a-1b): checked 3dfbd34 against my findings and the lead's reply; the blocking findings are resolved.`
  Decisions: D-099. S24 checks stay with E2.17 (nothing on screen uses motion yet).

- `claude: AGREE / OBJECT — <what was checked>`
- `codex: AGREE / OBJECT — <what was checked>`
- `owner: <for on-screen work: signed off on the S24, date>`

### codex — signoff, 2026-11-11T13:44Z

**codex: OBJECT — two placement gaps remain in 13a-1a.** Reviewed `7e85bf2` and the lead’s reply; judged from source and geometry. Existing JVM XML records 9 NodeMotion, 7 HostMotion and 6 ComposeHost tests passing; I did not rerun Gradle or use a device.

- **fix — `NodeMotion.kt:104–110`:** Anchor compensation applies even without a size transition. For `transition="place 200ms"`, a centred child growing 100→200 in a 300 px stack moves layout x 100→50. The anchor delta is zero, width snaps to 200, and the drawn left jumps to **50 instead of starting at 100**. Account for whether size actually continues; add a place-only growth/shrink regression.
- **fix — `ComponentView.kt:127–134`:** Root continuity includes only `floor(top)`; the baseline fraction changes immediately outside it. With fixed size and `top` 12.25→12.75, `placed` receives 12 both times, so no transition starts and the root jumps **0.5 px**. Include the full placement delta while retaining separate baseline correction; test fractional changes and integer-boundary crossings.
- **note:** Own-rest whole-pixel compensation, envelope retention until both size tracks settle, and centred-stroke bounds address those findings at source level.
- **merge condition:** I accept 13a-1b as a separate commit required before merging 13a-1 into `engine`: geometry-matched frames, nested-node invalidation and record counts on both paths. It does not defer the two runtime fixes above. Grammar has no new objection; security assessment is unchanged.

### codex — signoff, 2026-11-11T14:08Z

**codex: AGREE — 13a-1a: checked `c230651` against both sign-off findings and the lead’s reply; both are resolved at source level.**

- **Judged:** place-only growth/shrink preserves the old edge; root continuity includes fractional placement while baseline correction stays separate.
- **Recorded measurement:** JVM XML shows 11 NodeMotion, 7 HostMotion and 6 ComposeHost tests passing, including both new regressions. I did not rerun Gradle or use a device.
- **Merge condition:** 13a-1b must pass before merging 13a-1 into `engine`: geometry-matched frames on both drawing paths, nested-node invalidation and record counts, plus the actual `ComponentView` root path for fractional and centre-line changes.

### codex — signoff, 2026-11-11T15:01Z

**codex: AGREE — 13a-1 (13a-1a + 13a-1b): checked `3dfbd34` against my findings and the lead’s reply; the blocking findings are resolved.**

- **Judged:** proofs require callbacks and intermediate movement, reject nonfinite edges, use 0.25 px tolerance and exercise fractional-only root movement. Placement now requests redraw when displayed values change; recording deltas are retained.
- **Recorded measurement:** JVM XML shows 8 HostMotion tests passing, including the redraw regression. The lead reports both drawing paths passing and the former root placement failing.
- I did not rerun builds or use a device. Sign-off permits integration after the required full gate passes on `3dfbd34`; that result remains pending. S24 verification remains outstanding as planned.

**claude: AGREE — 13a-2 (`afeb1dd`, `39cf77d`, `2d5d20d`, `76fde1a`): Codex's grammar reviewed; my runtime's five review
findings fixed with regressions (four failing before), the emulator's `presence` and `rise` proofs within 0.004 alpha and
0.01 px on both paths, the full gate green on the tip.** D-100 records the choices.

### codex — signoff, 2026-11-11T17:45Z

**codex: AGREE — checked `76fde1a` and the lead’s §6 reply; the five blocking findings are resolved at source level.**

- **Judged:** reused nodes count once; removed nodes release before replacement allocation.
- **Judged:** retained constraints preserve fractional and nested geometry; the new proof checks both drawing paths.
- **Judged:** nested exits retain their registration and release by identity. I accept the correction: the former cleanup already removed duplicate entries at completion.
- **Judged:** presence curves carry updated revival targets; root placement preserves its fade, and showing it after completion runs `enter`.
- **Judged:** surviving-predecessor order is explicit and tested; continuing motion during exit is distinguished from frozen layout.
- **Verification:** inspected regressions and the recorded four failing-before cases. No files changed, tests rerun or device used.
- Integration remains conditional on the lead’s full gate passing on the corrected tip; S24 performance remains E2.17’s check.

### codex — signoff, 2026-11-11T19:52Z

**codex: OBJECT — two findings remain in `0946494`.** Checked the commit and §6 reply; judged from source.

- **fix — `RollMotion.kt:55–58`; `TextPainter.kt:80`; `token/Tokens.kt:46,66`:** Any configuration change clears active glyphs and snaps to rest. Configuration includes `tokens.version`, which changes even during colour crossfades with unchanged font geometry. Preserve motion across those updates; provide an animated handover for actual font changes. The new test currently asserts the snap.
- **fix — `Host.kt:238–253`; `ComposeHost.kt:215–231`:** Bounds include only the node’s own roll. An enclosing `row layer=#true` or translucent parent still isolates its rolling child within the parent’s layout bounds, cutting overflow. Propagate descendant drawing extents into isolation bounds; add both-path coverage. `MotionProof.kt:453` tests opacity on the text itself only.
- **Resolved, judged:** unseen-arrival eviction, pending-work bound, signed/decimal direction, paint-key completeness and zero shaping tolerance. Revised proofs require intermediate motion, compare glyph geometry, and assert pixel/control failures.
- **Verification:** lead’s JVM XML records 11 passing `RollMotionTest` tests. No files changed, tests rerun or device used. Full gate remains with the lead; S24 performance remains unverified.

### codex — signoff, 2026-11-11T20:22Z

**codex: OBJECT — `57f5c13` only partially resolves the two findings.** Checked the commit, regressions and lead’s §6 reply; **judged from source**.
- **fix — `engine/src/main/java/dev/launcher/engine/ui/motion/RollMotion.kt:58–64`; `TextPainter.kt:47–55`:** Font changes preserve normalized tracks but immediately replace offsets, paint, baseline and rise, so displayed glyphs still jump. Also, an active character roll keeps `clusters` even when the new configuration fails eligibility, continuing unverified character drawing. Provide a continuous, shaping-safe handover; test changed size and eligible→ineligible configuration mid-roll. The current regression checks normalized `y` and explicitly accepts instant offset changes.
- **fix — `engine/src/main/java/dev/launcher/engine/ui/Host.kt:377–380`:** Ancestor bounds apply translation but omit `c.motion.scale` and its centre pivot. A scaled rolling child can therefore exceed the propagated bounds and remain clipped by a layered/translucent ancestor. Transform the bounds through scale before containment/union; add both-path coverage.
- **Resolved, judged:** colour-only token updates no longer reset the roll; ancestor overflow propagates for the unscaled fixtures.
- No files changed, tests rerun or device used. Full gate remains with the lead; S24 performance remains unverified.
