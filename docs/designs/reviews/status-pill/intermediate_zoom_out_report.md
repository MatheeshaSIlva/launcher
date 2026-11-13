# Goldfish Review Report: Intermediate Zoom Out (step 8)

**Target:** `D:\Launcher\docs\designs\status-pill.md` (`1-306`)

**Context given to the reviewer:** `docs/ENGINE_SPEC.md`, `docs/DEFAULT_LAYOUT_PLAN.md`, and the earlier stage reports `zoom_out_report.md`, `comprehension_report.md` and `critic_report.md`.

**Focus:** the additions and mitigations raised by the Comprehension and Critic stages, and whether the document's altitude and scope still hold up after them.

**State of the target:** none of the earlier findings has been applied yet. Line numbers still match the first zoom-out report, so all of that report's findings are still open. The reviewer repeats one only where it decides how a later mitigation should be scoped, and says so each time.

## Reviewer Status

| Reviewer | Status |
| --- | --- |
| `egm-zoom-out` | completed |

**Severity breakdown:** 0 Blocker, 9 Major, 5 Nit.

## Prioritized Findings (Blockers -> Major -> Nits)

### 1. Leave detailed mode out of this milestone, or limit it to Android 13+. This cuts most of the proposed mitigations - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Overview lines 15-17; Goal 4; Open Questions lines 68-73; CUJs 5, 6 and 8; Verification Plan lines 290-295; "done" lines 298-302.
* **Discrepancy Description**: This is the highest-priority finding. It was the first zoom-out report's finding 9; it is still open and has grown worse. Most of the extra work the Critic stage proposed exists only because detailed mode is in scope:
  * M-7: a fallback order, a device below Android 13, and blur on API 29-30;
  * zoom-out finding 5: a Path-based neck as a second render path;
  * zoom-out finding 6: a four-tier degradation ladder that switches at runtime from FrameMetrics;
  * M-8: a cap on retained exits;
  * M-4(b): the label-based `cutExits`;
  * M-11: the 0.45 s and 0.28 s timings.

  Detailed mode is off by default and reached only over adb. Yet it is the main reason the doc and the gate keep growing. With every mitigation added, the doc becomes a robustness plan for a feature no user sees by default.
* **Rationale & Recommendation**: Decide this first; it is the simplest way to cut scope. Choose one of two options:
  * (a) Close this milestone on minimal mode, and give detailed mode its own milestone and gate.
  * (b) Keep detailed mode but make it Android 13+ only, with minimal mode forced below 13. M-7 already offers this.

  Option (b) removes the Path-neck proposal, the API 29-30 blur question and most of the fallback ladder. Record the choice as a D-entry, then add only the mitigations that are still needed.

### 2. Two degradation ladders are premature optimisation: measure first and keep one fallback - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Goal 6 (52-53); Open Questions (68-69); NFR Performance (226-227); Verification Plan, S24 (293-295).
* **Discrepancy Description**: Two separate ladders have been proposed:
  * Zoom-out finding 6: shader limited to the neck, then half-rate stepping, then the Path neck, then plain shapes, with optional runtime tier switching and hysteresis.
  * Critic M-7: elastic off, then neck off, then blur off, then minimal mode.

  Both are designed for a frame cost nobody has measured. Each tier needs its own proof. Runtime switching adds a control loop that must itself be tested against flapping.
* **Rationale & Recommendation**:
  * Add one sentence saying what happens if the S24 measurement misses the bound, for example: "if missed, detailed mode falls back to plain shapes (the existing below-13 path) and the bound is measured again." That path already exists.
  * Do not add runtime tiering.
  * Add one Verification Plan line for a Redmi frame check (the floor device in ENGINE_SPEC), or one line saying why it is skipped.

### 3. Drop the Path-based metaball neck below Android 13 - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Open Questions (70-72); Technical Plan, "The blob" (184-190); Target File 2 (244-248).
* **Discrepancy Description**: The first zoom-out report's finding 5 proposes a second, hand-built geometry renderer with its own golden test. Its purpose would be to reproduce on Android 10-12 an effect that is off by default there.
* **Rationale & Recommendation**:
  * Do not adopt it. Either take option (b) from finding 1, or use the plain-shapes fallback that is already built.
  * Reword M-18 to say exactly what has verified the below-13 path, in one sentence, for example: "a unit test forces the plain path; no device below 13 has run it."

### 4. Add a Risks & Mitigations section for the island's own risks only, and link to the plan's §5 instead of copying it - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** a new section between Alternatives (199) and NFR (222).
* **Discrepancy Description**: The reviewer would rate Critic B-1 Major, not Blocker. B-1 asks for a full catalogue of risks. Several of them are not the island's risks:
  * Listener disconnect belongs to the shade's notification source.
  * Banners and heads-up, Samsung chips and privacy indicators are already covered in DEFAULT_LAYOUT_PLAN §5.
  * Island failure is covered because `StatusBarView` is still the default bar (line 229).

  Copying all of this creates a second place that can drift from the plan.
* **Rationale & Recommendation**: Keep the section to about five rows, each naming the risk with a one-line mitigation or a link:
  * unmeasured frame cost (finding 2);
  * devices below Android 13 (findings 1 and 3);
  * lock and redaction timing (finding 5);
  * an arrival shown twice, by the island and by the shade's banner (finding 7);
  * the rollout fallback: the developer switch, with `StatusBarView` as the default.

  Then add one line: "Plan §5 risks apply unchanged."

### 5. Merge the lock and privacy fixes into one structural rule instead of stacking local patches - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Goal 5; CUJ 8 (139-145); Privacy (195-197); Target File 3 "Exits" (256); Target File 6 KdlBar (275).
* **Discrepancy Description**: This is the first zoom-out report's finding 1, still open, repeated because the Critic stage added more patches to it. Five findings each propose a separate local fix:
  * M-4(a): a `KeyguardLockedStateListener` with a fallback, plus three lock-case tests;
  * M-4(b): an engine contract on sensitive sources;
  * zoom-out finding 1: the lock state as a source value, plus private fields;
  * Comprehension finding 6: reconciling `cutExits("Banner content")` with `cutExits(top)`;
  * M-15: rewording "however late its removal comes".

  Applied one at a time, they would leave the doc describing the imperative hook, its timing race and its label-match fix, all at code level. Naming an Android listener API also drops the doc below design level.
* **Rationale & Recommendation**: State one approach at design level: "The lock state is a source value. Notification text and names are marked sensitive, and the engine draws them redacted while locked, including in exits already playing." Which keyguard API feeds that value belongs in the code and its tests, not in the doc.

  If this is deferred, label `cutExits` as a temporary workaround with a named follow-up task. Either way, most of the Comprehension and Critic wording fixes then fall away.

### 6. Notification visibility rules: decide the scope in one line and keep the policy where arrivals are decided - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Goal 5; CUJ 8; NFR Privacy (224-225).
* **Discrepancy Description**: Critic M-3 is a real gap: the doc does not cover `visibility` (including SECRET), `publicVersion` or the user's lock-screen setting. But this is a policy for the whole launcher, not for the island alone; the shade's banner and panel need the same rule. A fixture matrix for each visibility level in this doc would put the logic in the wrong layer.
* **Rationale & Recommendation**: Add one sentence. Either the island follows the shade's visibility policy (with a link to it), or it overrides that policy for a reason recorded as a D-entry. Put the fixtures where arrivals are decided, not in this doc's Verification Plan.

### 7. Add one paragraph on the shade's banner; the classified arrival stream and ranking alignment belong to E3 - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Non-Goals (57-58); Goal 2 (43-44); CUJs 4-6; Sources (180-183).
* **Discrepancy Description**: This combines the first zoom-out report's finding 3 and Critic M-6, partly still open. Two proposals pull E3 work into E2:
  * a single classified arrival stream (urgent arrivals to the old banner, ordinary ones to the island), which would mean proving the ringing-call cases again;
  * aligning Goal 2's ranking with calls, alarms and volume. Non-Goals line 58 and DEFAULT_LAYOUT_PLAN already place calls, alarms and volume on the island in E3.
* **Rationale & Recommendation**: Write one "Relationship to the shade's banner" paragraph that answers three questions:
  * what stops an arrival from showing twice today;
  * why the island holds an arrival for 4 s while the shade's banner holds it for 7 s (record this as a D-entry);
  * that unifying the two, and adding calls, alarms and volume to the ranking, are E3 work.

  Do not design the classified stream here.

### 8. Record the hidden elastic constants as a deviation from ENGINE_SPEC "Precision" for now, and expose them in E5 - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** "The blob" (186-190); Target Files 2 and 4.
* **Discrepancy Description**: This is the first zoom-out report's finding 2, still open; this finding narrows its recommendation. That report proposed named `elastic` attributes, validator ranges, a reduced-motion variant and tests across several parameter sets. ENGINE_SPEC's "Precision" section does require the values to be exposed ("nothing is hidden behind a preset"). But designing the parameters from a single user of the blob risks choosing the wrong ones. DEFAULT_LAYOUT_PLAN E5 already plans a second user ("the solid pill morphing into the opaque blob").
* **Rationale & Recommendation**:
  * Record a D-entry saying `elastic` stays a preset until E5, and name E5 as the step that exposes its parameters, once two users exist.
  * Remove the numbers from the doc and point to `BlobElastic.kt`.

### 9. Keep the CUJs about behaviour: point to the KDL for tuning values instead of copying them - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** CUJs 1-9 (lines 45, 79, 89-90, 145, 152); "The blob" (186-190); Target File 2 (247); Target File 3 (254-257); Target File 5 (268).
* **Discrepancy Description**: The earlier stages pull in opposite directions:
  * Critic M-15 and N-10, and Comprehension findings 4 and 5, ask to replace vague phrases ("a beat apart", "soft spring", "for a moment") with KDL values, give sources for 32 %, 22 %, 4.5 Hz and the pad formula, and define `n`, `k`, "gap" and "reach".
  * The first zoom-out report's finding 11 asks to remove that tuning detail, because it makes drift reports noisy.

  Copying tuning values into the CUJs makes the doc a second, stale copy of `island.kdl`, and every tuning commit becomes a drift finding. The real acceptance test is the emulator proof stages plus the owner's sign-off on the S24, not the prose.
* **Rationale & Recommendation**: Fix only the phrases that are truly ambiguous:
  * "not just plugged in" becomes "outside the 3 s plug-in moment";
  * "however late its removal comes" becomes "even if the notification is removed after the lock";
  * make "at once" mean one thing throughout (M-13).

  Delete, rather than define:
  * the pad formula;
  * the elastic percentages and spring values;
  * `HostNode.noExit`, `endExits` and `applyBlur`;
  * `seq` and `replaced`.

  Point to `island.kdl` and `BlobElastic.kt` for the numbers. Label M-11's 0.45 s and 0.28 s as tuning values, not acceptance criteria. This settles Comprehension findings 4 and 5, Critic N-10 and the first zoom-out report's finding 11 together.

### 10. Alternatives: add the one open architecture alternative, not six - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Alternatives (199-218).
* **Discrepancy Description**: Critic M-1 lists six architecture alternatives. Most are already settled elsewhere:
  * a KDL component instead of extending `StatusBarView` is the purpose of ENGINE_SPEC step 2;
  * ranking in KDL or in the engine is an engine-wide policy question;
  * one state at a time is settled by D-098.

  Arguing them again here duplicates ENGINE_SPEC and the plan.
* **Rationale & Recommendation**: Add only the alternative that is actually open: "two rounded boxes morphing out of the pill (the E2.13c geometry, with no smooth union)". Cite DEFAULT_LAYOUT_PLAN's "smooth union only if a later design asks", and add a D-entry saying the owner's request for a fluid look unlocked the union (the first zoom-out report's finding 4, still open). Link the owner decisions behind alternatives A-C (M-2).

### 11. Fixed widths: a clamp is enough; widths that fit the content and right-to-left layouts are out of scope - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** CUJs 2-5; "four fixed-width rows" (171-172).
* **Discrepancy Description**:
  * The first zoom-out report's finding 14 proposes widths that fit the content. That is new behaviour: replacements would resize and the golden images would depend on content.
  * Critic M-5 adds five new proof stages, including right-to-left (RTL) layouts, which the plan's checks for each step do not include.
  * The plan's own checks for each step (rotation, no display cutout, large text) are legitimate.
* **Rationale & Recommendation**:
  * Add one sizing rule: banner width = min(380 dp, screen width − 2 × margin).
  * Say in one sentence what happens in landscape.
  * Cover rotation, no cutout and large text by pointing to the plan's "Verification for every step", not by restating it.
  * Leave widths that fit the content, and RTL, as later candidates.

### 12. Accessibility: defer with a pointer, except redaction of spoken announcements - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** NFR (222-229).
* **Discrepancy Description**: Critic M-10 asks for TalkBack labels, a policy for spoken announcements (live regions) and a contrast check. ENGINE_SPEC makes accessibility an exit test for steps 3, 4 and 5, not step 2. The one part that cannot wait is privacy: if the island announces arrivals, the announcement must follow the same redaction rule as the screen.
* **Rationale & Recommendation**: Add one NFR line covering two points:
  * spoken announcements follow the lock redaction rule;
  * the rest of accessibility is checked at the ENGINE_SPEC step that owns it.

### 13. Slice coverage: narrow the claim instead of restating the plan's checklist - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Overview (19-22); "done" (298-302).
* **Discrepancy Description**: This combines the first zoom-out report's finding 8 and Critic M-9, still open. Both ask for coverage of slices E2.14a-E2.17 and the plan's checks for each step. Restating each slice with its own verification would copy the plan's table into this doc.
* **Rationale & Recommendation**:
  * Add a short list of slices: "closes E2.13a-c, E2.14b-c and the detailed arrival; E2.14d, E2.14e, E2.15 and the E2.17 pulls are deferred to …".
  * Narrow line 19's "finished" to match (M-17).
  * Leave the checks for each slice in the plan.

### 14. Wording and definition fixes are fine as proposed, as long as they stay links or short phrases - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** lines 1, 11-12, 30, 59, 61-62, 228-229, 296, 300-302.
* **Discrepancy Description**: Several requests are fine because they do not pull in scope:
  * defining the drift report, Codex AGREE and gate 3 (Comprehension finding 1);
  * the full `adb` broadcast command (Comprehension finding 2, Critic N-14);
  * using "pill" and "island" consistently (M-12);
  * writing colours as hex with the design name after (M-16 and the first zoom-out report's finding 7);
  * rewording the "no blur" non-goal (the first zoom-out report's finding 10).
* **Rationale & Recommendation**:
  * Apply them as links to `docs/team/PROTOCOL.md` and `docs/DECISIONS.md`, not as copied explanations.
  * Keep the design tokens deferred, with a named step that owns them, rather than binding `sys` tokens now.
  * N-3's split of the CUJs into five more is optional: it adds length without changing scope.

## Overall verdict on altitude and scope

The doc still targets the right problem at roughly the right level: one KDL component replacing the hand-drawn bar, built on engine primitives, behind a developer switch. Nothing in it is a dead end.

If every Comprehension and Critic mitigation is applied as written, the doc gets worse. It would gain:
* two fallback ladders and a second render path for an effect that is off by default;
* handling for each keyguard API, written at code level;
* a full copy of the plan's risks and verification list;
* tuning values copied into the CUJs.

Together these would turn a milestone record into an E3-sized robustness plan that duplicates `island.kdl`, ENGINE_SPEC and DEFAULT_LAYOUT_PLAN.

A simpler order of work:
1. Decide whether detailed mode is in this milestone, or limit it to Android 13+.
2. Replace the lock patches with one structural rule.
3. Record the elastic preset and the 4 s hold as D-entries, with the parameters deferred to E5 and the banner unification to E3.
4. Keep the CUJs about behaviour.
5. Add a short, island-only Risks section that links to the plan.
