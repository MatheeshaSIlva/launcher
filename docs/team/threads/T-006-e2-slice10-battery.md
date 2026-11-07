# T-006: E2 slice 10, the battery as `box` + fill + text with expressions

- **Step:** E2 · **Lead:** claude · **Opened:** 2026-11-08 · **Status:** PROPOSED
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

## 3. Agreed approach

What was agreed, what changed from the proposal and why.

- `codex: AGREE approach — <one line>`

## 4. Split

| Sub-task | Owner | Files owned | Branch | Verified by |
| --- | --- | --- | --- | --- |
| | | | | |

## 5. Reports

### claude — report

- **Commits:**
- **What changed:**
- **Verified (measured / judged):**
- **Not verified:**
- **Surprises:**

### codex — report

(appended by `scripts/team/codex.sh … work`)

## 6. Cross-check

Each agent reviews the other's diff and report. Findings as `fix`, `question` or `note`, with file:line. The owner of a
finding replies inline when it is resolved. Security checklist (protocol §7) answered here when it applies.

## 7. Sign-off

- `claude: AGREE / OBJECT — <what was checked>`
- `codex: AGREE / OBJECT — <what was checked>`
- `owner: <for on-screen work: signed off on the S24, date>`
