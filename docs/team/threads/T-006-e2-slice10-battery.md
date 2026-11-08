# T-006: E2 slice 10, the battery as `box` + fill + text with expressions

- **Step:** E2 · **Lead:** claude · **Opened:** 2026-11-08 · **Status:** DONE
- **Read first:** `docs/E2_PLAN.md` §a (the battery row) and §e (slice 10); D-074 (decision 8, the pixel targets); D-081
  (one expression syntax); `app/src/main/java/dev/launcher/app/statusbar/StatusBarView.kt` `drawBattery` (:1116) and the
  right slots' layout (:870-895); `engine/src/main/java/dev/launcher/engine/ui/` (Host, ComposeHost, ComponentView,
  TextPainter, IconPainter); `engine/src/main/assets/kdl/components/status-bar.kdl`; T-005's thread (the glyph pixel
  check this extends).

## 1. Proposal (lead)

**Goal.** The KDL bar (still behind the developer switch) shows the battery on the right as StatusBarView draws it at
rest: the faint body, the level fill clipped to the level, the number knocked out of both, the faint nub, and the state
colour (low, Low Power, charging), all from `box`, `stack` and `text` with expressions; and a first pixel harness that
renders the battery both ways for a set of battery states and diffs them.

**What StatusBarView draws** (`drawBattery`, at rest; `u` the unit, `a` the content alpha, `k` its grey, white or black):
- body: 24.6 x 12.6 pt (`27 - 1.6 - 0.8`), radius 4.2, grey `k` at alpha `89/255 * a`, centred on the bar's line;
- level: the same rounded body clipped to `width * level / 100`, in `levelColor`;
- number: `level` in the text font at 700, 9.6 pt, `tnum`, centred on the body, baseline `cy + 0.727 * size / 2`, drawn
  with `DST_OUT` inside a layer over body and level (it cuts through both);
- nub: 1.6 x 4.4 pt, radius 1.6, 0.8 pt right of the body, grey `k` at `102/255 * a` (outside the layer);
- `levelColor`: the content colour, mixed toward `comp.statusbar.battery-low` by `clamp((25 - level) / 8, 0, 1)` (a
  continuous blend between 25 % and 17 %, not a threshold), then toward `battery-saver` by Low Power, then toward
  `battery-charging` by charging; the last two are animated (`saveAnim`, `chargeAnim`: slice 13), so at rest 0 or 1;
  the result takes the content's alpha.
- placement: the battery slot is 27 pt wide; the right group is laid out from `width - SIDE_PT` leftwards (bolt,
  battery, ...). Slice 10 places the battery alone at the right (the bolt, an `icon` from slice 9, joins when the
  slots do, slice 11-13).

**Approach.**
1. *`box` draws* (`ui/BoxPainter.kt`, new): `fill` (colour), `radius`, `stroke` + `stroke-color`; `clip=#true` clips
   its children to its rounded bounds (a RenderNode outline clip on the draw node, so nothing is recorded twice).
2. *Knockout.* `text ... blend="knockout"`: the text erases what is under it (`DST_OUT`) instead of painting. It cuts
   only within its nearest ancestor that sets `layer=#true` (a new common attribute: that primitive's frame node is a
   compositing layer), so the cut never reaches the wallpaper. A knockout with no layer ancestor is a validator error
   (`knockout-needs-layer`), since it would punch a hole through everything under the bar. Schema: `blend` on `text`
   (`normal` | `knockout`), `layer` in COMMON. Logged as a D-entry (format detail, no owner question: the spec is
   silent on blend modes, and this is the narrowest form that draws the iOS battery).
3. *Colour maths in expressions.* `mix(a, b, t)` (colours, `t` clamped to 0..1) and `alpha(c, k)` (multiply a colour's
   alpha) as built-in functions of the expression language (D-081's `{expr}`), plus `clamp(x, lo, hi)`. The battery's
   colour becomes one expression over `battery.level`, `battery.power-save`, `battery.charging`, `@color` and the three
   tokens; faint body and nub are `{alpha(@color, 0.349)}` and `{alpha(@color, 0.4)}` (grey `k` is the content colour
   itself, white or black).
4. *The KDL:* the bar's row gets a `spacer flex=1` and, at the right (`padding-end=34`), the battery:
   ```kdl
   row gap=0.8 align="centre-line" label="Battery" {
     stack width=24.6 height=12.6 layer=#true {
       box width=24.6 height=12.6 radius=4.2 fill="{alpha(@color, 0.349)}"
       stack width=24.6 height=12.6 align="start" {
         box width="{24.6pt * clamp(battery.level / 100, 0, 1)}" height=12.6 clip=#true align="start" {
           box width=24.6 height=12.6 radius=4.2 fill="{...the state colour...}"
         }
       }
       text "{battery.level}" size=9.6 weight=700 tnum=#true align="center" blend="knockout"
     }
     box width=1.6 height=4.4 radius=1.6 fill="{alpha(@color, 0.4)}"
   }
   ```
   (exact nesting is the implementation's; the numbers are StatusBarView's, tokens for them stay B-114's.)
5. *The pixel harness.* `TEST_STATUS --es do battery`: for each state (level 100, 50, 21, 17, 5; charging at 50 and at
   5; Low Power at 50 and at 15; tones white, black, white at alpha 128 → 27 pairs), StatusBarView draws its battery
   (`drawBattery` through an `internal` entry with the state given, as `drawGlyph` did) and the battery sub-tree of the
   KDL bar is drawn by a `ComponentView` built over `Sources.fixture` with that `battery` value, attached invisibly to
   the shade's window (alpha 0) and drawn with `view.draw(softwareCanvas)` (`DrawNode` already paints straight onto a
   non-GPU canvas). PNGs into `files/dev-out/battery/{view,kdl}/`, then `tools/fidelity/diff.py`.

**Split (proposed).** **codex**: `ui/BoxPainter.kt`, the `layer` attribute in `ComposeHost`'s draw node, the
expression functions (`mix`, `alpha`, `clamp`) with their tests, the validator's `blend` / `layer` schema and the
`knockout-needs-layer` diagnostic with tests. **claude**: `TextPainter`'s knockout, the KDL battery in `status-bar.kdl`,
`StatusBarView`'s battery entry, the harness and the emulator diff, `KdlBar`.

**Verification.**
- JVM: `BoxPainter` key and measure; `mix`/`alpha`/`clamp` (range, clamping, alpha arithmetic, type errors as
  diagnostics); the validator's new schema and diagnostic; `StatusBarKdlTest` (the bar file valid, the battery's
  expressions evaluating to StatusBarView's numbers at 100, 21, 17 and 5 %).
- Emulator: the 27 pairs through `diff.py --grid 1x1` (each picture one cell: mean < 2, < 2 % of pixels over 12, as
  T-005). Target: pixel-identical for the body, nub and fill (same Paint, same rounded rect); the number is text drawn
  by `TextPainter` against `cut` (same font, weight, size, `tnum`, centre): expected identical, named if not.
- `scripts/check.sh` full; a dex/manifest comparison against `engine`'s tip (new classes only under
  `dev.launcher.engine`, the app's `statusbar` package gaining the entry).

**Risks and open questions.**
- `layer=#true` makes the battery an offscreen buffer every frame it records: small (25 x 13 pt), only while the bar
  redraws; idle cost is slice 17's to measure.
- Antialiasing of a rounded rect clipped by a RenderNode outline versus `clipRect` + `drawRoundRect`: the fill's right
  edge is a straight cut in both, but outline clipping may antialias it differently. If it does, `BoxPainter` clips
  with the canvas (`clipRect`) when the clip is a plain rectangle.
- The battery source's member is `power-save`: does the expression lexer read `battery.power-save` as one path or as a
  subtraction? If the latter, the source member is renamed (`powerSave`) in this slice rather than special-casing the
  lexer.
- Codex: is `blend` on `text` only right, or should `box` and `icon` take it too now (E3's controls will want cut-out
  glyphs)? The lead leans to `text` only until a consumer needs more.
## 2. Discussion

Alternate `### codex — <date>` and `### claude — <date>` entries, Codex first. Each entry: what you agree with, what you
would change and why (with evidence), what is missing. At most three rounds each (protocol §5).

### codex — discuss, 2026-11-07T22:05Z

**Judged from source review in `D:/Launcher`; no files changed, tests run or device used.** I agree with the primitive-based battery, text-only knockout for this consumer, and built-in functions inside D-081’s `{expr}` syntax. The following need resolving before approach agreement.

- **Geometry:** §1 matches `StatusBarView.kt:1119–1159`, but the proposed clipping tree cannot preserve it: `Layout.kt:155–163` constrains children to their parent’s width, so the full rounded fill shrinks inside the partial-width box. Keep the rounded body full-size and clip its drawing separately. Also preserve fractional drawing bounds: at unit 2.625, 24.6 pt is 64.575 px, whereas layout rounds it (`Layout.kt:74–85`). Name the generic mechanism and its owner before work.
- **Number placement:** `TextPainter.kt:26–41` measures font bounds and draws at `-ascent`; a stack centres those bounds, whereas the reference centres the capitals with baseline `cy + 0.727 * size / 2`. Match that baseline and the body’s exact horizontal centre. Knockout must remain fully opaque at rest even with alpha-128 content (`StatusBarView.kt:1158`); inheriting `@color` alpha would leave the number partly filled.
- **Colour arithmetic:** use `89 / 255`, not `0.349`, and reproduce truncation for faint alpha and rounded channel interpolation (`StatusBarView.kt:1132–1138,1458–1462`). The reference forces content opaque before mixing, then **replaces** the result’s alpha with content alpha (`:1127–1130`). Multiplying the alpha of an ordinary ARGB mix is different, including with opaque state tokens and translucent content. Define the function contract and show how the battery expression reproduces this; test translucent tokens too.
- **Layer and validation:** layer isolation and clipping must enclose the painter **and children**, on hardware and software paths (`ComposeHost.kt:90–114`). Require a provably enabled ancestor layer: either literal-only `layer`/`blend`, or an explicit runtime safety rule for bindings. Test root knockout, `layer=#false`, nested ancestors, fallback and variant handling. Include a coloured background/sibling proof that knockout cannot erase outside its layer.
- **Expression implementation:** call syntax is new (`ExprParser.kt:102–125`), so the split must include AST, parser, compiler and dependency traversal (`Deps.kt`). Whitelist the three functions; diagnose unknown names, arity and known type/unit errors; define reversed clamp bounds and non-finite inputs. Test dependencies through every argument and safe handling of bad dynamically bound values. Both agents must complete protocol §7 for these evaluation changes.
- **`battery.power-save` already works:** `ExprParser.kt:144–153` accepts the hyphen as part of the path, and `SystemSources.kt:23–27` exposes that exact member. Keep the name; add a parse/dependency/evaluation regression.
- **Harness and acceptance:** an attached fixture component is the right starting point, but software drawing currently bypasses `HostNode.motion`, including ComponentView’s fractional root translation (`ComponentView.kt:107–113`, `ComposeHost.kt:90–92`). Address that discrepancy, wait for attachment/layout and committed content, assert nonblank output, and dispose/remove every fixture. Add a hardware-rendered proof for layer isolation and clipping; software PNGs cannot establish those GPU behaviours. D-074 requires **pixel identity** for these same-Paint shapes and text, not merely passing diff.py’s defaults. Add boundary levels 0/25 and a charging-plus-saver case.
- **Split:** the engine/app division is sensible. Explicitly assign `BoxPainterTest`, expression/model tests, host/layout changes needed above, and `ComponentView.defaultPainter` wiring (`:206–209`, currently no box painter). Claude can own that wiring alongside TextPainter/KDL/harness; Codex can own the remaining engine changes. Keep staged commits near the slice limit.

codex: OBJECT approach — the direction is sound; resolve full-size clipping, exact placement and alpha semantics, layer validation, and hardware harness coverage before implementation.

### claude — 2026-11-08

All accepted; each point checked against the code you cite. The plan changes as follows.

1. **Clipping (you are right: `Layout.kt:155-163` would shrink the fill).** A generic mechanism instead of the nested
   box: `box`'s `reveal` (0..1, default 1): the box and its children are drawn at full size and clipped to the first
   `reveal` of its width (`clipRect`, a plain rectangle: the fill's right edge is a straight cut, as the reference's).
   Owner: codex (`BoxPainter` and the draw node).
2. **Fractional bounds.** A `box` (and `stack`) with literal `width`/`height` draws its painter at those exact px
   (64.575 at unit 2.625), not at the laid-out whole pixels; layout still reserves `ceil`. The draw node positions the
   battery as `ComponentView` does the root: whole-pixel placement plus the node's own fractional translation. Owner:
   codex (`Layout`/host), with a test that a 24.6 pt box draws 64.575 px wide.
3. **Number placement.** `stack align="centre-line"`: children are centred horizontally on the stack's exact float
   width and placed so their centre lines meet the stack's centre (the box's middle); `TextPainter`'s centre line is
   already `baseline - CAP * size / 2`, StatusBarView's rule, and its `align="center"` centres on the exact float width.
   Owner: codex (the stack alignment), claude (`TextPainter`).
4. **Knockout opacity.** A knockout erases at full strength whatever the `color`'s alpha (only `opacity`, an animation
   value, scales it): with alpha-128 content the number stays fully cut, as `cut.alpha = 255 * numberK` at rest.
5. **Colour arithmetic, as functions with exact contracts** (whitelisted; any other name, wrong arity or a known
   type/unit error is a validator diagnostic):
   - `mix(a, b, t)`: StatusBarView's `blend` exactly: `t <= 0` gives `a`, `t >= 1` gives `b`, otherwise each of the four
     channels `(int)(a + (b - a) * t + 0.5f)` in float, as `:1458-1462`.
   - `opaque(c)`: alpha 255. `withAlpha(c, n)`: alpha replaced by `n` (0..255, an integer). `alphaOf(c)`: 0..255.
   - `scaleAlpha(c, num, den)`: alpha `alpha * num / den` in integer division, as `89 * a / 255` (`:1132-1138`).
   - `clamp(x, lo, hi)`: `lo > hi` is a diagnostic when both are literal, else returns `lo`; NaN or infinity in any
     argument gives `lo` (never NaN into a colour).
   The battery's colour then reads as the reference computes it:
   `withAlpha(mix(mix(mix(opaque(@color), @comp.statusbar.battery-low, clamp((25 - battery.level) / 8, 0, 1)),
   @comp.statusbar.battery-saver, battery.power-save ? 1 : 0), @comp.statusbar.battery-charging, battery.charging ? 1 : 0),
   alphaOf(@color))`, body `scaleAlpha(@color, 89, 255)`, nub `scaleAlpha(@color, 102, 255)`. Tests over all 256 alphas
   for `scaleAlpha` against the integer formula, `mix` against `blend` on random pairs and on translucent tokens.
6. **Layer and validation.** `layer` and `blend` are literal-only (not bindable), so the validator proves a knockout has
   an enabled `layer` ancestor; refused: a knockout at the root, under `layer=#false`, or with no layer ancestor; nested
   layers fine. The layer encloses painter and children on both paths: the frame RenderNode with
   `setUseCompositingLayer(true)` on hardware, `saveLayer` around painter and children on a software canvas.
7. **Expressions (call syntax is new).** Owner: codex: AST call node, parser, compiler, dependency traversal through
   every argument (`Deps`), the whitelist and diagnostics, with tests (dependencies via arguments, unknown function,
   arity, a bad bound value at run time evaluating safely). Both complete protocol §7 for the evaluation changes.
8. **`battery.power-save`:** kept; a parse, dependency and evaluation regression added.
9. **Harness.** The software path applies the node's motion (translation, alpha) as the hardware path does (owner:
   codex, in `ComposeHost`'s draw node, with a test); the harness waits for attachment, layout and a committed frame,
   asserts each PNG is not blank, and removes and disposes every fixture view. A **hardware proof** too: in the test APK's
   `ProofActivity`, the battery over a coloured background beside a coloured sibling, captured with `PixelCopy` of the
   activity's own window: the cut shows the layer's backdrop only inside the body, the sibling and background untouched.
   **Target: pixel identity** for body, fill, nub and number (D-074 decision 8), each failure named. States add level
   0 and 25 and charging plus Low Power: 12 states x 3 tones = 36 pairs.
10. **Split, exact.** **codex** (`team/codex/t006-battery`): `ui/BoxPainter.kt` + `BoxPainterTest`; `reveal`, `layer`
    and the motion-on-software fix in `ui/ComposeHost.kt` + tests; `stack align="centre-line"` and fractional draw
    bounds in `layout/Layout.kt` + `LayoutTest`; the expression call syntax and the six functions in `expr/` + tests;
    the schema (`model/Primitives.kt`: `blend`, `layer`, `reveal`) and `knockout-needs-layer` in the validator + tests.
    **claude** (`team/claude/t006-battery`): `TextPainter` knockout and exact centring + test; `ComponentView`'s
    `defaultPainter` (box) wiring; the KDL battery in `status-bar.kdl` + `StatusBarKdlTest`; `StatusBarView`'s battery
    entry; the software harness and the hardware `PixelCopy` proof; the emulator diff. Staged commits, each near the
    slice limit.

### codex — discuss, 2026-11-07T22:08Z

**Judged from source review in `D:/Launcher`; no files changed, tests run or device used.** I agree with `reveal`, isolated knockout, opaque knockout text, the colour formula for valid inputs, literal-only `layer`/`blend`, the hardware PixelCopy proof, and D-074’s pixel-identity target. Three gaps remain:

- **Fractional placement must cover sibling advances and centre lines.** Exact painter bounds alone are insufficient: `Layout.kt:116,121,132` advances rows using measured integer widths. At unit 2.625, reserving 65 px for the 64.575 px body puts the nub at 67.1 px instead of 66.675 px. Carry exact drawing extents and placement residuals through the battery row as well as its stack; keep integer allocation separate. Also use the child’s unrounded centre line: `ComposeHost.kt:57` currently reads Compose’s integer alignment line. Test body/nub positions and the number’s baseline in the assembled subtree, alongside the isolated width test.

- **The six function contracts still need invalid-input rules.** “Any non-finite argument gives `lo`” returns NaN when `lo` itself is NaN. `Compiled.kt:154–157` can produce non-finite values through arithmetic. Specify a finite fallback or `Value.Null` for invalid bounds, and define `mix` with NaN, `scaleAlpha` with zero denominator or overflowing operands, and fractional/out-of-range `withAlpha` inputs. My preference: diagnose statically known invalid calls; return `Value.Null` for invalid dynamic arguments, without throwing. Require unitless finite factors and alpha integers; preserve compatible units for valid `clamp`. Add refusal tests for these cases.

- **The exact split omits `ui/Host.kt`.** It owns the painter’s integer-size interface (`:32`) and stack dispatch, so assign it explicitly to Codex for the geometry/alignment integration. Layout residuals must compose with `Motion` translations rather than overwrite them; `ComponentView.kt:107–113` already uses the root translation for its centre-line correction. Retain Round 1’s fallback/variant validator tests, including acceptance when an outer enabled layer encloses an intermediate `layer=#false` node.

The revised harness and verification direction are otherwise sufficient for implementation; both agents still complete protocol §7 during cross-check.

codex: OBJECT approach — resolve fractional row placement, invalid function inputs, and Host.kt ownership before implementation.

### claude — round 3, 2026-11-08

All three accepted.

1. **Fractional placement through the row.** `Measured` carries the exact float extent beside the integer allocation;
   `row` and `stack` advance and centre children by exact extents, and each child's placement residual (exact minus
   whole-pixel) is applied as a translation of its own frame node, **added to** its `Motion` translation (never written
   over it; `ComponentView`'s root correction composes the same way). Centre lines are read unrounded from the child
   (`HostNode.centre`), not from Compose's integer alignment line. Tests on the assembled battery subtree at unit
   2.625: body 64.575 px wide at 0, nub at 66.675 px, the number's baseline at `cy + 0.727 * size / 2`, plus the
   isolated width test.
2. **Invalid inputs.** Statically known invalid calls are diagnostics; at run time an invalid argument makes the call
   `Value.Null` (never a throw, never NaN), and a `Null` colour draws nothing for `fill` and the painter's default
   for `color`. Rules: factors (`t`, `clamp`'s three) unitless and finite, `clamp` also accepting three compatible
   lengths (result in that unit), `lo > hi` or NaN bound gives `Null`; `mix` with NaN `t` gives `Null`; `withAlpha`
   takes an integer 0..255 (a fraction or out of range is `Null`); `scaleAlpha` needs integers `num >= 0`, `den > 0`,
   computed in `Long` and clamped to 0..255; `alphaOf`/`opaque` on a non-colour are `Null`. Refusal tests for each.
3. **`ui/Host.kt` is codex's** (the painter's size interface, stack dispatch, residuals with `Motion`). The round 1
   validator tests stay, including acceptance when an outer `layer=#true` encloses an intermediate `layer=#false`.

Split as round 2 with `ui/Host.kt` and `layout/Measured` added to codex's row.

### codex — discuss, 2026-11-08T11:03Z

**Judged from the thread; no code changed, tests run or device used.** All three round-2 gaps are resolved by §2, “claude — round 3”:

- **Fractional placement:** exact extents drive row advances and stack centring; residuals add to Motion; unrounded centre lines and assembled-subtree tests cover the body, nub and number.
- **Invalid inputs:** static diagnostics and runtime `Value.Null` define safe refusal, with finite/unit checks, alpha integer bounds and guarded `Long` arithmetic. Refusal tests are required.
- **Ownership:** `ui/Host.kt` and `layout/Measured` are explicitly Codex’s. The outer-enabled-layer/intermediate-false validator test remains required.

Nothing further is missing from the approach; implementation and verification remain to establish correctness.

codex: AGREE approach — round 3 resolves fractional row placement, invalid function inputs and Host.kt ownership.

## 3. Agreed approach

Agreed 2026-11-08 (`codex: AGREE approach`, 11:03Z): §1 as amended by the lead's answers in §2 (rounds 1 to 3).

- **`box`:** `fill`, `radius`, `stroke`, `stroke-color`; `reveal` (0..1): box and children drawn at full size, clipped by
  a plain rectangle to the first `reveal` of its width.
- **Exact geometry:** `Measured` carries the exact float extent beside the integer allocation; `row` and `stack` advance
  and centre by exact extents; each child's residual is a translation of its own frame node, added to `Motion`; centre
  lines read unrounded (`HostNode.centre`). `stack align="centre-line"`.
- **Knockout:** `text blend="knockout"` erases at full strength whatever the colour's alpha (only `opacity` scales it).
  `layer` and `blend` are literal-only; the validator requires an enabled `layer` ancestor (`knockout-needs-layer`:
  root, `layer=#false`, none are refused; an outer `layer=#true` over an intermediate `layer=#false` is accepted). The
  layer encloses painter and children on both paths (compositing frame node on hardware, `saveLayer` on software).
- **Expressions:** call syntax (AST, parser, compiler, dependencies through every argument); six whitelisted functions:
  `mix` (StatusBarView's `blend` exactly), `opaque`, `withAlpha`, `alphaOf`, `scaleAlpha` (integer division, `Long`),
  `clamp`. Known invalid calls are diagnostics; invalid at run time gives `Value.Null`, never a throw or NaN; a `Null`
  colour draws no `fill`, and the painter's default for `color`. `battery.power-save` kept, with a regression.
- **Harness:** 12 states x 3 tones = 36 pairs; the software path applies `Motion`; it waits for attachment, layout and a
  committed frame, asserts non-blank, removes and disposes each fixture; a hardware `PixelCopy` proof of layer isolation
  and clipping in `ProofActivity`. **Target: pixel identity** for body, fill, nub and number (D-074); each failure named.
- Both complete §7 for the evaluation changes. `scripts/check.sh`, a dex and manifest comparison.
- `codex: AGREE approach — round 3 resolves fractional row placement, invalid function inputs and Host.kt ownership.`

## 4. Split

| Sub-task | Owner | Files owned | Branch | Verified by |
| --- | --- | --- | --- | --- |
| Box, layer, geometry, expressions, schema | codex | `ui/BoxPainter.kt` + `BoxPainterTest`; `ui/ComposeHost.kt` (`reveal`, `layer`, motion on software) + tests; `ui/Host.kt`; `layout/Layout.kt`, `Measured` (exact extents, `stack align="centre-line"`) + `LayoutTest`; `expr/` (call syntax, six functions) + tests; `model/Primitives.kt` and the validator (`blend`, `layer`, `reveal`, `knockout-needs-layer`) + tests | `team/codex/t006-battery` | JVM tests, including the assembled battery subtree's positions at unit 2.625 |
| Text knockout, KDL, harness | claude | `ui/TextPainter.kt` (knockout, exact centring) + test; `ComponentView`'s `defaultPainter`; `assets/kdl/components/status-bar.kdl` + `StatusBarKdlTest`; `StatusBarView`'s battery entry; the software harness and the `PixelCopy` proof; the emulator diff | `team/claude/t006-battery` | 36 pairs with `diff.py`; the hardware proof |

## 5. Reports

### claude — report

- **Commits:**
- **What changed:**
- **Verified (measured / judged):**
- **Not verified:**
- **Surprises:**

### codex — report

(appended by `scripts/team/codex.sh … work`)

### codex — work, 2026-11-08T11:20Z

- **Commits:** None. `git add` cannot create `D:/Launcher/.git/worktrees/launcher-codex/index.lock` under sandbox permissions. Changes remain unstaged; no push. Suggested slices: expressions; geometry/Host; box/drawing/schema.
- **What changed:** Assigned BoxPainter, reveal/clipping, hardware/software layers, software Motion, exact row/stack geometry, centre-line alignment, six expression functions, schemas and knockout validation, with tests.
- **Verified (measured):** Targeted Kotlin 2.2.21/Compose compilation; **83 JUnit tests passed**, including **338/338 KDL cases**. Battery at unit 2.625: body **64.575 px**, nub **66.675 px**, baseline within **0.00001 px**. Tested all 256 alphas and 1,000 colour blends. `git diff --check` clean.
- **Verified (judged), §7:** No new IPC requiring caller authentication; expressions bounded to 16,384 characters, 512 nodes and parser depth 64. Fixed function allowlist; no shell, reflection, file or component access added. Source capability checks retained and refusal-tested. Captures and secrets untouched.
- **Not verified:** `CHECK_FAST=1 bash scripts/check.sh` failed before compilation on the unwritable wrapper lock. Installed Gradle with a writable offline cache failed resolving AGP 8.13.2. No full gate, APK/lint, device, pixel or dex verification; no Gradle daemons remain.
- **Surprises:** Tight 71 px allocation needed fractional room preserved for the nub. Please log the expression limits. TextPainter should use the new Float `paint` overload and exact measured extents during integration; Android rasterisation and hardware isolation still need the lead’s harness.

## 6. Cross-check

Each agent reviews the other's diff and report. Findings as `fix`, `question` or `note`, with file:line. The owner of a
finding replies inline when it is resolved. Security checklist (protocol §7) answered here when it applies.

### codex — review, 2026-11-08T12:57Z

**Judged:** reviewed `engine..23fa37e`, including the lead’s share and both edits in Codex-owned files. No files changed; no tests or device runs performed. `git diff --check` passed.

- **fix — `engine/src/main/java/dev/launcher/engine/layout/Layout.kt:134`:** `room` uses raw `f.width` even when parent constraints shrink it. With `Frame(width=200f)`, maximum width 100, a flex spacer and a 10 px child, the child starts at x=190 outside the allocated 100 px row. Derive room from the constrained outer width while retaining permitted fractions; add this regression test.
- **fix — `app/src/main/java/dev/launcher/app/statusbar/BatteryDump.kt:76`:** blank output only logs, then increments `pairs` and eventually writes a successful `.done`. Fail the run on either blank picture; ensure fixture removal/disposal also happens when drawing fails.
- **note — `engine/src/main/java/dev/launcher/engine/ui/ComposeHost.kt:174`:** naming `reveal` now clips at 1 on both drawing paths, as requested. Add a regression exercising this condition: the current operation-order test supplies its own clip callback, and `LayerProof.kt:39` exercises only 0.5.
- **note — `engine/src/main/java/dev/launcher/engine/ui/TextPainter.kt:53`:** opaque knockout and exact centring match the agreed contract. `StatusBarView.kt:1167` matches RollingText’s resting draw at `:1609`. The reported 36 identical pairs remain the lead’s emulator measurement; GPU battery identity, S24 appearance and smoothness remain unverified.

**Protocol §7 — judged from source:**

- [x] Caller checks: the battery route retains the DUMP-gated receiver; expression functions add no IPC entry point.
- [x] Bounds/reflection: expression limits are 16,384 characters, 512 counted nodes and nesting 64; calls use six fixed names without reflection.
- [x] No new route to shell, execution, external paths or another app’s components.
- [x] Capabilities: calls traverse every argument’s dependencies; source declaration checks remain, including the nested-call refusal test. No new capability is introduced.
- [x] Captures: BatteryDump renders fixtures; LayerProof captures its own test window. Neither captures another app.
- [x] Secrets: pairing keys and tokens are untouched; no secret logging added.
- [x] Refusal tests cover unknown calls, arity/types/units, excessive input, non-finite values and integer overflow; inspected, not rerun.

**codex: OBJECT — resolve the constrained-row overflow and harness false-success path before sign-off.**

## 7. Sign-off

- `claude: AGREE — slice 10: read all of codex's diff and wrote the rest; JVM 178+ engine tests including the battery's colour at 240 resting states against StatusBarView.blend and the exact geometry; 36 of 36 pairs pixel-identical on the emulator; component-layer PASS on the GPU; final gate CHECK PASSED on 8129d1f; dex and manifest against ff943b7: classes only in engine expr, layout, model, ui and the app's statusbar and GestureNav; manifest unchanged.` (2026-11-08)
- `codex: AGREE — judged from source review of 8129d1f: both fixes and the note are answered (13:04Z).`
- `owner: <for on-screen work: signed off on the S24, date>`

### claude — first pixel run, two findings for codex, 2026-11-08

Lead's share on `team/claude/t006-battery`. **Measured (JVM):** your tests through Gradle, engine 178/178;
`theBatterysColourIsStatusBarViewsAtEveryRestingState` (240 combinations against `blend`). **Measured (emulator,
GPU):** `component-layer` PASS (holes on both sides of the reveal, green only before half, white only after, sibling and
outside untouched). **The 36 pairs: 0 within the defaults** (`tools/shots/battery*` in `D:/launcher-claude-t006`):
centroid +0.42 to +0.47 px in x. Two causes, both in your files:

- **fix — `layout/Layout.kt:99-101,132-136`:** `framed` rounds padding (`px(89.25) = 89` twice), so `inner.maxWidth` is 902
  where the exact room is 1080 - 178.5 = 901.5, and `row`'s `leftover` shares out the extra 0.5 px: the battery lands at
  920.375, StatusBarView's at 919.875. A row's flex leftover and `exactWidth` must use the exact room (`c.maxWidth` minus
  the float paddings, or `f.width` minus them), integer allocation apart. Test: root row with fractional padding, a flex
  spacer and a fractional child: the child's exact x.
- **fix — `ui/ComposeHost.kt` `clipFrame`:** StatusBarView always draws the level through `clipRect(left, top, left + w * lvl,
  bottom)`, also at 100 %, and that antialiased clip on a fractional bottom edge thins it (the rounded rect's coverage
  times the clip's). `reveal` skips the clip at 1, so the KDL fill's bottom row is heavier (+0.11 px centroid in y at
  level 100, coverage +1 %). A box that names `reveal` clips whatever its value (a test on the operation order through
  `softwareFrame`, and the hardware path alike).

Nothing else is running on the machine.

### claude — 36 pairs identical, 2026-11-08

Your run on the two findings hung (exit 124, no changes), with nothing else running. As lead I made those two fixes in your
files on `team/claude/t006-battery` (the tip; please review them as yours): `Layout.row` shares the exact room;
`clipFrame` clips any box naming `reveal`; `aFlexRowSharesTheExactRoomLeftByFractionalPadding`.

**Measured (emulator, Android 17, unit 2.625):** `TEST_STATUS --es do battery`: 12 states (100, 50, 25, 21, 17, 5, 0;
charging at 50 and 5; Low Power at 50 and 15; charging plus Low Power at 50) x 3 tones: **36 of 36 pixel-identical** (diff.py
mean 0.00; `l100`, `l50`, `l0` white: max channel difference 0, same centroid and coverage), the D-074 target. Not blank
(coverage 675 to 1648; the same harness showed +0.45 px before the fix). `component-layer` PASS on the GPU.
**Not measured:** the software harness and the GPU proof use different pictures, so GPU pixel identity with the bar's
own window is not shown; the bolt is not placed (slices 11-13); light and dark use the bar's real tokens; nothing on the S24.

### claude — review fixes, 2026-11-08

`8129d1f`. Both fixes and the note taken: `room` is limited by what the parent allows (`aFlexRowNarrowerThanItsWidthStaysInsideItsParent`: your 200-in-100 case, the child at 90); a blank picture or a failed draw ends the run with no `.done`, and the fixture is removed and disposed in a `finally`; `revealFraction` is the one place that decides the clip, tested at 1, 0.5, past 1 and not named (`aBoxNamingRevealClipsAtFullToo`). **Measured (emulator):** the 36 pairs again pixel-identical, `.done` written.

### codex — signoff, 2026-11-08T13:04Z

codex: AGREE — judged from source review of `8129d1f`: both fixes and the note are answered.
- Constrained-row room preserves fractions and places the 200-in-100 regression’s child at x=90.
- Blank pictures and drawing exceptions stop without `.done`; fixture removal/disposal is in `finally`.
- Both drawing paths use the shared reveal rule; regression covers 1, 0.5, above 1 and absent.
- Measured: `git diff --check` passed. Tests/device checks not rerun; full gate and dex/manifest comparison remain pending. No files changed.
