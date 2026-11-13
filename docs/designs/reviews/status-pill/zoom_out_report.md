# Goldfish Review Report

**Target:** `D:\Launcher\docs\designs\status-pill.md` (`1-306`)
**Stage:** Step 5, initial zoom-out review (uninterrupted preset)
**Context given to reviewers:** `docs/ENGINE_SPEC.md`, `docs/DEFAULT_LAYOUT_PLAN.md` (the island and status rows, "Shapes" and "Opaque surfaces"), plus the code the document names.

## Reviewer Status

| Reviewer | Status |
| --- | --- |
| `egm-zoom-out` (zoom-out-standard) | completed |
| `egm-inventive-catalyst` | completed |

**Severity breakdown (after deduplication):** 0 Blocker, 9 Major, 5 Nit.
Three findings were raised by both reviewers and merged: the lock-time privacy hook, the hidden elastic constants, and the overlap with the shade's banner.

## Prioritized Findings (Blockers -> Major -> Nits)

### 1. The lock-time privacy hook bypasses the declarative model and depends on an accessibility label - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`, `egm-inventive-catalyst`
* **Affected Scope:** Goal 5 (lines 50-51), CUJ 8 (139-145), Technical Plan "Privacy" (195-197), Target File 3 "Exits" (256), Target File 6 KdlBar (275)
* **Discrepancy Description**: When the phone locks, app code calls `view.cutExits("Banner content")` (`KdlBar.kt:100`). This walks the tree for nodes whose `label` matches that string (`ComponentView.kt:259-261`). There are four problems:
  * The lock state reaches the component as an imperative call, not as a subscribed source value, which goes against ENGINE_SPEC rules 1 and 2.
  * `label` is the accessibility label. Renaming it for TalkBack would silently break privacy.
  * A creator's replacement island cannot get this protection, so the guarantee lives in the app's wiring, not in the component contract. ENGINE_SPEC also says "No component reaches into another's state".
  * Every lock deliberately throws away exit motion, against goal 3 ("never a pop"). A second imperative path is needed only to cover the timing race between redaction and the screen broadcasts.
* **Rationale & Recommendation**: Make the lock state a source value (for example `device.locked`). Then do one of two things:
  * **Minimum:** express the cut in KDL, for example an `exit` that is disabled while locked.
  * **Better (inventive catalyst):** mark private fields (such as `text` and `name`) in the `notifications` source schema. The engine then draws them redacted whenever the phone is locked, including snapshots held by exiting nodes. The exit can still play, now with redacted text. This removes `cutExits` and the string match, and every creator's component inherits the guarantee. It has a cost: the source schema needs a privacy attribute, and an exit with redacted text needs a width rule.
  * **At the very least:** label the current hook in the doc as a temporary workaround with a named follow-up.

### 2. The elastic physics constants are hidden engine values behind a boolean - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`, `egm-inventive-catalyst`
* **Affected Scope:** Technical Plan "The blob" (186-190), Target Files 2 and 4 (244-262)
* **Discrepancy Description**: The KDL exposes only `elastic=#true`, a BOOL that cannot be bound (`Primitives.kt:97`). The constants in `BlobElastic.kt` are all `const val`: PULL_PARENT 0.32, PULL_BUD 0.22, NECK_WIDE/THIN, FREQUENCY 4.5, DAMPING 0.32 and TINT_MS 160. This breaks ENGINE_SPEC "Precision": "every value the engine itself uses is an attribute a creator can set. Nothing is hidden behind a preset." It also leaves the engine holding a model tuned for one island.
* **Rationale & Recommendation**: Make `elastic` a motion value with named attributes, for example `follow`, `hold`, `spring` (frequency and damping) and `turn`. Use today's numbers as the defaults and have the validator check their ranges. If that is not done now, the doc should say that `elastic` is an island-specific preset and record it as a known deviation from the spec. The doc then also needs defined ranges, a reduced-motion variant, and tests for more than one parameter set.

### 3. Two arrival decisions and two show times: the island and the shade's banner overlap - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`, `egm-inventive-catalyst`
* **Affected Scope:** Non-Goals (58), CUJs 4-6, Technical Plan "Sources" (180-183), Rollout (228-229)
* **Discrepancy Description**: The doc names ArrivalTracker as the place that "decides what arrives". In the code, the decision is made twice, and the two filters can disagree:
  * **Shade, `Shade.kt:1966-1976`:** filters on peeks, an open panel, our own screen in front, urgent and locked.
  * **The island, `KdlArrivals.alerts()` plus ArrivalTracker:** filters on importance, suppressPeek, ongoing, summary and media. It has no urgent, open-panel or own-screen checks.

  As a result, an arrival over an open panel can still play on the island, and a ringing alarm that is not ongoing can show on both. ENGINE_SPEC "Banners are mandatory" assigns the banner to a shade `banner` fill with a 7 s show time. The island's detailed mode is a banner inside the status component that holds for 4 s (`HOLD_MS = 4000L`). The doc says neither which component owns the banner contract nor why the hold time differs.
* **Rationale & Recommendation**: Add a "relationship to the shade's banner" paragraph covering three points:
  * whether the island's banner becomes the `banner` fill later;
  * how double-showing is prevented now;
  * the reason for 4 s against 7 s, recorded as a decision.

  The structural fix is to decide each arrival once and emit a classified stream (urgent to the legacy banner, ordinary to the island, suppressed), with one hold value for both. This means re-proving the ringing-call cases that were already fixed in Shade.

### 4. The smooth union and elastic physics conflict with the plan's "Shapes" section without saying so - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Goal 4 (47-49), Technical Plan "The blob" (184-190), Alternatives (199-218), Target File 2 (244-248)
* **Discrepancy Description**: DEFAULT_LAYOUT_PLAN "Shapes" says: "concave joins are no longer needed (D-098); a smooth union (AGSL) only if a later design asks for it". It names E2.13c's morphing rounded boxes as the island's geometry. The doc adds a new `blob` primitive with an AGSL smooth union, per-frame physics, a neck and a different look below Android 13. It does not cite the clause, and no decision records that the owner's "fluid" request of 2026-11-13 is the "later design" the plan mentions. `blob` is also missing from the spec's full list of primitives.
* **Rationale & Recommendation**: Add one sentence that cites the clause and a D-entry stating that the owner's request unlocks the deferred smooth union. Update the plan and the spec's primitive table to match. Also list "a second rounded box morphing out of the pill (E2.13c geometry, no union)" as an explicitly rejected alternative.

### 5. The fluid parting is defined only for Android 13+, while the floor is Android 10 - Severity: Major
* **Reviewer Lens:** `egm-inventive-catalyst`
* **Affected Scope:** Goal 4 (47-49), Open Questions (70-72), Technical Plan "The blob" (184-190), Target File 2 (247-248)
* **Discrepancy Description**: Below Android 13 the blob draws "plain shapes (no neck)". The headline behaviour (CUJ 5, the owner's "exactly like a fluid") therefore depends on AGSL and has not been verified on any device. DEFAULT_LAYOUT_PLAN "Shapes" requires the behaviour below 13 to be defined, not just degraded.
* **Rationale & Recommendation**: Draw the neck as geometry:
  * Build a Path with two cubic bridges between the shapes' tangent points, the classic metaball approximation.
  * Drive it from the neck width BlobElastic already computes, and fill it in the pill's colour.

  This works on every API level, and AGSL becomes a smoother upgrade on 13+. The cost is two render paths, which need a golden test each, and colour blending at the join may have to be a hard swap.

### 6. The frame cost is measured only at the end, with no fallback planned - Severity: Major
* **Reviewer Lens:** `egm-inventive-catalyst`
* **Affected Scope:** Goal 6 (52-53), Open Questions (68-69), NFR Performance (226-227), Verification Plan S24 (293-295)
* **Discrepancy Description**: The shader and the per-frame elastic step are fixed before their cost on the S24 is known. The doc says the cost is "measured at this milestone" but gives no action if it breaks the bound of 1 dropped frame per 100. ENGINE_SPEC "Sandbox and performance" already provides an escape route (an engine-owned layer), which the doc neither uses nor rules out.
* **Rationale & Recommendation**: State a degradation order now, in this sequence:
  1. Limit the shader to the neck region.
  2. Run the elastic step at half rate and interpolate.
  3. Fall back to the path neck (finding 5).
  4. Fall back to plain shapes.

  Optionally, switch tiers at runtime from FrameMetrics jank counts, with hysteresis so the tiers do not flap. Each tier then needs its own proof.

### 7. Literal colours and sizes in the default theme's own component, while the CUJs imply tokens - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Non-Goals (61-62), CUJs 3, 5 and 7
* **Discrepancy Description**: ENGINE_SPEC "The default theme" says it is built from the same tokens a creator has, and that components reference tokens with `@name`. `island.kdl` hard-codes #F2F2F2, #D9865F, #7DBE93, #121212, #1F1F1F, #8A8A8A and #C4C4C4. The CUJs use names such as `ok`, `surface`, `ink`, `ink-2` and `muted` as if they were tokens. The token resolver is already ported, so "until the default theme supplies tokens" is a deferral, not a constraint. Pure black (#000000) is a deliberate exception under D-105.
* **Rationale & Recommendation**: Either bind the colours to `sys` tokens now, or keep the non-goal and rewrite the CUJs with hex values. In the second case, name the deferral as debt and give it an owning step.

### 8. "Done" leaves out exits the plan sets for this element - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Overview (19-22), Verification Plan (281-296), What "done" means (298-302)
* **Discrepancy Description**: The doc calls the island the "first element ... finished" but never maps itself to the plan's slices E2.13a-E2.17. These are missing:
  * **E2.14a:** screenshots in both themes.
  * **E2.14c:** StockBarCover still covering Samsung's charging and cast chips, privacy indicators visible, and an S24 check with a real charge and a real cast.
  * **E2.14d:** decode text. It is unclear whether this is dropped for the digit roll or deferred.
  * **E2.14e:** the island following a pull, hidden-by-app, and BarHost routing.
  * **E2.15:** goldens and `diff.py`, including parity with the old bar. Step 2's exit in ENGINE_SPEC is "pixel-matched".
  * **E2.17:** S24 frame stats on pulls.
  * **The plan's checks for every step:** rotation, no cutout, large text, denied permissions or missing services, and a lost shell host.
* **Rationale & Recommendation**: Add a table that gives each plan slice one status:
  * done here;
  * deferred, with where it goes;
  * dropped, with a decision.

  Then either add the missing checks or narrow the milestone's claim, for example to "E2.13-E2.14c plus the detailed arrival".

### 9. Detailed mode carries most of the complexity but is off by default - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Overview (15-17), Goal 4, Non-Goals (59-60), Open Questions (73), CUJs 5-6
* **Discrepancy Description**: Minimal mode is the default, and detailed mode is reached only over adb. Detailed mode holds most of the complexity:
  * the AGSL shader and the elastic model;
  * the blur presence and blur track;
  * the keyed list of arrivals;
  * `cutExits` and the turn-back;
  * the only frame cost not yet measured.

  The milestone gate therefore mostly tests a path users will not see. Every `visible` expression also branches on `@mode`, which doubles the number of states to test.
* **Rationale & Recommendation**: Decide explicitly whether detailed mode is part of this milestone's "done" or a follow-up milestone. If it stays in, give the reason (for example, that it is meant to become the default). If it moves out, the doc and the gate shrink considerably.

### 10. Content blur contradicts the "no blur" non-goal - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Non-Goals (64), against Goal 4, CUJs 5-6, Technical Plan "Motion" (191-194), Target File 4 (262)
* **Discrepancy Description**: The non-goals say "Glass, blur or shadows: the default theme is opaque (D-118)", but the design adds a `blur` presence effect and a blur track. D-118 concerns backdrop and glass blur, so there is no real conflict, but the wording contradicts itself.
* **Rationale & Recommendation**: Reword the non-goal to "no backdrop or glass blur and no shadows; content blur in presence motion is allowed".

### 11. Too much tuning detail for a design document - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Lines 186-190, 226-227, 247-248, 254-257, 268
* **Discrepancy Description**: The doc carries detail that will change with every tuning commit:
  * the shader pad formula;
  * the 32 % and 22 % factors, 4.5 Hz and 0.32;
  * `applyBlur`-on-change;
  * `HostNode.noExit`, `endExits`, `seq` and `replaced`.

  This will make drift reports noisy.
* **Rationale & Recommendation**: Describe behaviour and capabilities instead, for example "exits can be ended or suppressed for a subtree", and point to `BlobElastic.kt` for the numbers.

### 12. The ranking is repeated in each node rather than held in one ranked state - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Technical Plan "The component" (170-174), Goal 2
* **Discrepancy Description**: The doc speaks of "a `visible` condition on the ranked state", but no ranked state exists. About 15 expressions in `island.kdl` each repeat the whole ranking condition. Adding the calls, alarms and volume ranks will mean editing all of them.
* **Rationale & Recommendation**: If KDL `state` or a computed binding is available, derive one `rank` value. If not, say so and correct the doc's wording.

### 13. The keyed exit and enter rely on a one-item list - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Lines 174, 180-181, Alternative C (214-218)
* **Discrepancy Description**: `notifications.shown` is a one-item list keyed by arrival only so that `each` produces an exit and an enter when a message is replaced. This bends the source's shape to fit what the host can do, and it duplicates the `Message` and `Next message` subtrees (`island.kdl:115-130`).
* **Rationale & Recommendation**: This is acceptable for now. Note in the doc that a keyed presence attribute on a single node (for example `key="{notifications.arrival.seq}"`) would remove both the list and the duplicated subtree, and record it as a candidate engine addition.

### 14. Fixed widths per state, and no unlock behaviour - Severity: Nit
* **Reviewer Lens:** `egm-inventive-catalyst`
* **Affected Scope:** Goal 1, CUJs 2-4 (89, 99, 106), "four fixed-width rows" (171-172), CUJ 8
* **Discrepancy Description**:
  * **Fixed widths:** the pill widths are literals (132, 236, 164 and 200 dp) whatever the content, so short names leave empty black and long ones are cut off.
  * **Unlock not described:** CUJ 8 covers only redaction at lock. It does not say what a redacted banner does at unlock.
* **Rationale & Recommendation**: Fit widths to the content, clamped between a minimum and today's literal as the maximum, using the existing `textWidth` derive path. Add a sentence on unlock. Fitted widths mean CUJ 6 replacements also resize, and goldens then depend on the content.
