# Goldfish Review Report

**Target:** `D:\Launcher\docs\designs\status-pill.md` (`233..306`, "Detailed Implementation Plan")
**Stage:** Step 9, Implementation Readiness Test (uninterrupted preset)
**Standard:** `implementation-readiness-standard` (for `egm-implementation-readiness` and `egm-codebase-details`)

## Reviewer Status

| Reviewer | Status |
| --- | --- |
| `egm-implementation-readiness` | completed (0 Blocker, 8 Major, 5 Nit as reported) |
| `egm-codebase-details` | completed (2 Blocker, 6 Major, 4 Nit as reported) |
| `egm-style` | completed (0 Blocker, 6 Major, 8 Nit as reported) |

After deduplication: **2 Blockers, 11 Majors, 7 Nits.**

**What the reviewers confirmed is accurate.** Every file and unit test the section names exists. The following match the code:
- `endExits`, `cutExits`, `HostNode.noExit`, `snapMotion`, and `applyBlur` acting only on a change (`Host.kt:147, 619, 630, 642-645, 881-888`);
- `BlobElastic.reset` (`BlobElastic.kt:77`);
- `seq` and `replaced` (`Arrivals.kt:11-13`), `notifications.shown` (`NotificationIcons.kt:21`) and `battery.plugged` (`SystemSources.kt:32`);
- `ShapeWarmUp`, and the Android 13 floor (`BlobPainter.kt:48`);
- the proof mode `component-island`, the cutout `emu01`, and all seven named stages (`AdbProofInstrumentation.kt:71`, `IslandProof.kt:96-102`);
- the scope of the `detailed` stage (`IslandProof.kt:791-885`).

The problems are precision, completeness and testability, not wrong code.

## Prioritized Findings (Blockers -> Major -> Nits)

### B1. Target file paths are wrong or incomplete and cannot serve as a write allowlist - Severity: Blocker
* **Reviewer Lens:** `egm-codebase-details` (Blocker), `egm-implementation-readiness` (Nit), `egm-style` (Nit)
* **Affected Scope:** Lines 244, 250, 259, 264, 271
* **Discrepancy Description**:
  * Item 6 starts with the prefix `app/src/main/java/dev/launcher/app/statusbar/`. Read against that prefix, three of its paths are wrong:
    * `shade/Notifs.kt`, `shade/Conversations.kt` and `shade/Shade.kt` point to `statusbar/shade/`, which does not exist. The files are in `app/src/main/java/dev/launcher/app/shade/`.
    * `Badges.kt` and `GestureNav.kt` are in `app/src/main/java/dev/launcher/app/`, not in `statusbar/`.
  * Item 4 has the same problem: `ui/motion/NodeMotion.kt` comes after `.../model/MotionSpec.kt`, so it reads as `model/ui/motion/`.
  * Several entries are bare filenames after a comma (`BlobElastic.kt`, `ComposeHost.kt`, `NotificationIcons.kt` and others), so the reader has to guess their directory.
* **Rationale & Recommendation**: The standard requires exact repository-relative paths. Give one full repo-relative path per line, each with its own Create or Modify label.

### B2. The island.kdl entry has no structure and misdescribes the messages as one repeater - Severity: Blocker
* **Reviewer Lens:** `egm-codebase-details` (Blocker), `egm-implementation-readiness` (M1), `egm-style` (Major)
* **Affected Scope:** Line 242 (and the Technical Plan claim at lines 173-174 that it points to)
* **Discrepancy Description**:
  * "as described above" names no section and leaves nothing to check.
  * The Technical Plan says the banner has "one `Message` per arrival". The code has **two** `each` repeaters over `notifications.shown`:
    * `Message`, shown when `!a.replaced`, with enter delays of 300, 324 and 348 ms (`island.kdl:115-122`);
    * `Next message`, shown when `a.replaced`, with enter delays of 110, 134 and 158 ms (`island.kdl:124-131`).
  * Someone following the plan would build one repeater and lose the faster entry for replacements (CUJ 6).
  * The document does not say that `Banner content` sits at translate-x -190 dp when closed (`island.kdl:111`). It also does not say that this label is the one the app's `cutExits` call relies on.
* **Rationale & Recommendation**: This breaks the zero-decision rule, and the drift check cannot use it. Replace "as described above" with the node tree:
  * the version (0.9.0);
  * the `blob` with its two boxes;
  * the four state rows;
  * both repeaters, with their visibility conditions and delay sets;
  * the `Banner content` label and its closed offset.

### M1. Host, blob and source entries are one-line summaries, not a contract anyone can check - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-codebase-details`, `egm-style`
* **Affected Scope:** Lines 247-248, 254-257, 262, 268-269, 275-279
* **Discrepancy Description**: The section names behaviours without giving their rules. Missing details:
  * **Blob schema** (`Primitives.kt:94-97`):
    * `smooth` (a length) and `fill` (a colour);
    * `align`, one of `top-start` or `top`, default `top-start`;
    * `elastic`, a boolean, default false, not bindable;
    * `container = true`.
  * **BlobElastic constants and thresholds** (`BlobElastic.kt:50-58, 174-182`):
    * PULL 0.32 / 0.22, NECK 0.5 / 0.3, 4.5 Hz, damping 0.32, TINT 160 ms;
    * the shapes join when the gap is under 0.5 × reach and break when it is over reach, where reach = smooth / 2.
  * **Blob painter**: `MAX_SHAPES` = 4 (`BlobPainter.kt:87`).
  * **Host steps**:
    * a moving box child redraws its parent blob (`ComposeHost.kt:141-142`);
    * draw bounds are computed when there is blur or blur reach (`ComposeHost.kt:180, 188`);
    * the elastic steps through `elasticSaw` and is skipped when motion is reduced (`BlobPainter.kt:37, 116-118`);
    * `recurringNodes` (`Host.kt:645`).
  * **Arrivals**:
    * a `shown` item's `id` is the arrival's `seq`, which is what makes the `each` exit and enter work (`Arrivals.kt:23-26`);
    * `shown` holds zero or one item;
    * `mark` is the name's first letter or digit, in upper case (`Arrivals.kt:28`);
    * the first list after a start produces no arrival (`Arrivals.kt:67`);
    * HOLD_MS is 4000, MAX_NAME is 200 and MAX_TEXT is 500 (`Arrivals.kt:99-102`).
  * **"What alerts" in KdlArrivals** (`KdlArrivals.kt:50-53`). A notification alerts when:
    * its importance is DEFAULT or higher;
    * it is not suppressPeek, ongoing, a group summary or media;
    * it has a title or text;
    * if it is the launcher's own, it is on `Badges.TEST_CHANNEL`.
  * **The lock watch** (`KdlArrivals.kt:41-47, 56`):
    * it listens for SCREEN_OFF, SCREEN_ON and USER_PRESENT;
    * a screen that goes off counts as locked;
    * a keyguard exception counts as locked.
  * **Unclear wording**:
    * "Members:" does not say what kind of members;
    * "a group decided by its title" can mean either "the title decides it is a group" or "the title decides which group";
    * "the mode switch" and "`TEST_NOTIFY`'s conversation option" do not say what they do.
* **Rationale & Recommendation**: "Done" depends on the drift report (a comparison of this document against the code), and summaries give it nothing to compare. For each file, list the concrete symbols, signatures, constants and rules above, with a short gloss for each internal name.

### M2. `cutExits` names two different APIs, and the label it relies on is not stated or tested - Severity: Major
* **Reviewer Lens:** `egm-codebase-details`, `egm-implementation-readiness`
* **Affected Scope:** Line 256 (and line 196; `KdlBar.kt:100`, `island.kdl:111`)
* **Discrepancy Description**:
  * `cutExits` is two APIs:
    * `ComponentHost.cutExits(top: HostNode)` (`Host.kt:630`) marks the subtree `noExit` and ends the exits running under it;
    * `ComponentView.cutExits(label: String)` (`ComponentView.kt:259-263`) finds nodes by their label and calls the host. KdlBar calls this one, with the literal `"Banner content"`.
  * `ComponentView.endExits()` (`ComponentView.kt:256`) is not mentioned.
  * If someone renames the label in `island.kdl`, the privacy cut stops working without any error, and CUJ 8 regresses. `PresenceTest.kt:100` tests only the `HostNode` form.
* **Rationale & Recommendation**:
  * Name both signatures and say which file owns each.
  * State the label contract.
  * Add a test that loads the shipped `island.kdl`, calls `cutExits("Banner content")`, and asserts that the message nodes are marked `noExit`.

### M3. The file list leaves out files changed for the island - Severity: Major
* **Reviewer Lens:** `egm-codebase-details`, `egm-implementation-readiness`
* **Affected Scope:** Lines 237-279
* **Discrepancy Description**: These files carry T-012 to T-017 tags or are needed by the island, but are not listed.
  * **Engine:**
    * `ui/BoxPainter.kt:22-23` (a box inside a blob draws no fill);
    * `ui/ShapeWarmUp.kt:55-57` (line 227 relies on it);
    * `ui/CutoutPlacement.kt` and `model/Validator.kt:55` (`place-gap`);
    * `layout/Layout.kt:178-182` (the overflow and overhang);
    * `ui/TextPainter.kt:70` (the fade on a scrolled title);
    * `ui/ImagePainter.kt`, `source/MediaSource.kt` and `SessionMedia.kt`;
    * `source/SourceBinder.kt:56` (deep member reads);
    * `expr/Compiled.kt:49`.
  * **App:**
    * `statusbar/KdlNotifIcons.kt`;
    * `statusbar/StatusBarView.kt:205` (`onShown` and `shownForApps`, used at `KdlBar.kt:111-113`).
  * **Proofs:** `engine/src/androidTest/.../ui/IslandProof.kt`, `BlobProof.kt` and `source/MediaSessionProof.kt`.
* **Rationale & Recommendation**: List every file, or narrow the scope claim and say plainly which dependencies are out of scope.

### M4. The provenance, task range and Create/Modify baseline are inaccurate - Severity: Major
* **Reviewer Lens:** `egm-codebase-details`, `egm-style`
* **Affected Scope:** Lines 235, 244, 250, 259, 264, 271
* **Discrepancy Description**:
  * Line 235 says the work was built on `team/claude/e2-14c2-detailed` (T-012 to T-017). That branch was created from `engine` and holds only T-015 commits. T-016 and T-017 landed earlier, through `team/claude/e2-14c2-blob`.
  * T-016 is decode text, which `island.kdl` does not use.
  * `Arrivals.kt` is labelled Modify, but its header says T-014 introduced it.
  * Item 6 says "Modify / Create" without saying which file is which. `KdlArrivals.kt` and `Conversations.kt` are probably the new ones.
  * Bare "T-" and "D-" ranges are not explained. "D-104 to D-119" includes decisions that have nothing to do with the island.
  * The branch is still moving, so naming it does not fix what was described.
* **Rationale & Recommendation**:
  * Name a baseline commit (for example `engine` before T-012) and the commit described (for example `ce63895`).
  * Label each file Create or Modify against that baseline.
  * Correct the task range.
  * Explain T- (team board tasks) and D- (`docs/DECISIONS.md`) once, or list only the decisions that matter here.

### M5. The end-to-end command and its pass rule are wrong or incomplete - Severity: Major
* **Reviewer Lens:** `egm-codebase-details`, `egm-implementation-readiness`, `egm-style`
* **Affected Scope:** Lines 288-292
* **Discrepancy Description**:
  * The command has no `adb shell` and no `-w`. Without `-w`, `am instrument` returns at once and prints no result. The form given in the code is:
    `adb shell am instrument -w -e mode component-island dev.launcher.engine.test/dev.launcher.engine.adb.AdbProofInstrumentation` (`IslandProof.kt:61`).
  * "every stage PASS" does not match the proof:
    * no stage prints PASS;
    * each stage writes a descriptive result string;
    * failures collect in one `errors` list, and the run fails as a whole through `check(errors.isEmpty())` (`IslandProof.kt:93-112`).
  * The `preset` and `typeface` checks are left out.
  * The stages are not listed in the order they run.
  * There is no build or install step.
  * The emulator needs are not stated: API 33 or later to see the neck, and the `display.cutout.emulation` overlay for `emu01` (`IslandProof.kt:888-898`).
  * The command is split across lines inside inline code, so it cannot be copied as is.
* **Rationale & Recommendation**:
  * Put the exact command in a fenced block, with the steps that build and install the APKs.
  * Define success as "instrumentation status OK and no `errors` key".
  * List all checks in the order they run, and state the emulator setup.

### M6. The unit-test plan names classes only, and relevant tests are left out - Severity: Major
* **Reviewer Lens:** `egm-codebase-details`, `egm-implementation-readiness`
* **Affected Scope:** Lines 284-287
* **Discrepancy Description**:
  * The plan gives no cases, no modules and no command. `ConversationTest` is in `:app`; the others are in `:engine`.
  * Existing tests are left out: `BlobSchemaTest`, `CutoutPlacementTest`, `MediaSourceTest`, `KdlBarChoiceTest`, `DecodeSchemaTest` and `LayoutTest`.
  * There are no tests for the `BlobPainter` pad or plain fallback, or for `KdlBar.setMode`.
* **Rationale & Recommendation**: For each class, list the behaviours it pins. At minimum:
  * the lock redaction and the `cutExits` path;
  * the `oneLine` whitespace collapse (`Arrivals.kt:108`);
  * `seq` and `replaced` on a replacement;
  * the group and direct-chat rules in `Conversations.of` (`Conversations.kt:14-20`);
  * the elastic join, break and reset.

### M7. Several CUJs have no test or proof stage - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** Lines 281-296 (against CUJs 5, 8 and 9 and the Open Questions at line 70)
* **Discrepancy Description**:
  * **CUJ 8 (locked):**
    * no app-level test covers `KdlArrivals`: its lock watch, the `onLocked` transition (lines 56-58) or the redaction (lines 61-64);
    * `ArrivalsTest.kt:36` covers only the tracker's refresh;
    * no proof stage locks the phone.
  * **CUJ 9 (full screen):** no test or stage drives the `shown` property.
  * **CUJ 5 timing:** the break at about 0.28 s, about 0.45 s end to end, and the 24 ms stagger are not asserted. The `detailed` stage checks only that the neck joined in one frame, the 380 dp width, the centring and the ink.
  * **Below Android 13:** the path has no verification step.
* **Rationale & Recommendation**: Add tests, or say plainly for each of these that it is the owner's judgement on the S24.

### M8. The S24 frame-stats procedure is not specified - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** Lines 293-295
* **Discrepancy Description**: The section gives no tool, no number of runs and no way to start each scenario. The pieces exist but are not cited:
  * `tools/framestats.py` (no `tools/scenario_*.sh` covers the island);
  * the `TEST_NOTIFY` broadcast (`Badges.kt:39-44`);
  * the mode switch (`GestureNav.kt:579-593`).
* **Rationale & Recommendation**: Give the exact command sequence for each scenario (a detailed arrival, a replacement, the close, media opening), the measuring command, and the number of runs.

### M9. Milestone exit checks from DEFAULT_LAYOUT_PLAN are missing - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** Lines 281-302 (against `docs/DEFAULT_LAYOUT_PLAN.md:70, 72, 75`)
* **Discrepancy Description**: The milestone plan sets these exit checks:
  * E2.14a: screenshots in both themes;
  * E2.14c: Samsung's charging and cast chips still covered (`StockBarCover`), privacy indicators still visible, checked on the S24 with a real charge and a real cast;
  * E2.15: goldens checked with `diff.py`. `IslandProof.kt:55` already crops `island/idle.png` for this.

  None of them is in the Verification Plan or in "done".
* **Rationale & Recommendation**: Add each one as a verification item, and refer to them from "done".

### M10. Documentation changes for the new public surface are not listed - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** Lines 235-279
* **Discrepancy Description**:
  * The plan adds new public KDL surface:
    * the `blob` primitive;
    * the presence effects `slide` and `blur`;
    * the source members `notifications.shown` and `battery.plugged`.
  * `docs/ENGINE_SPEC.md` does not list `blob`, either in its primitive list (line 119) or in its step-2 list (line 379).
* **Rationale & Recommendation**: List the spec updates as target files, with the exact edits. Spec changes are the owner's, so propose them.

### M11. "Done" relies on terms the Verification Plan never defines or lists - Severity: Major
* **Reviewer Lens:** `egm-style`, `egm-implementation-readiness`
* **Affected Scope:** Lines 296, 298-302
* **Discrepancy Description**:
  * "gate 3" (line 296) is defined only in `.claude/skills/design-first/SKILL.md:66`.
  * "the drift report" has no named producer, file or format.
  * "Codex has signed AGREE" is not explained. Codex's review, which D-119 requires, is not in the Verification Plan.
  * "the full check" is a second name for the `scripts/check.sh` run at line 283.
* **Rationale & Recommendation**:
  * Add Verification Plan items for the drift report and for Codex's review, each with a few words on what it is.
  * Spell out gate 3, or drop the term.
  * Use one name for the check throughout.

### M12. The pad formula and the Android floor are stated with undefined symbols - Severity: Major
* **Reviewer Lens:** `egm-style` (Major), `egm-codebase-details` (Nit)
* **Affected Scope:** Lines 247-248
* **Discrepancy Description**:
  * In "(n − 1) k / 4 + 1 px", neither `n` nor `k` is defined.
  * The code clamps the term: `(count - 1).coerceAtLeast(1) * k / 4f + 1f` (`BlobPainter.kt:90`).
  * "below 13" does not say "Android 13" (API 33).
* **Rationale & Recommendation**: Write "(n − 1) × k / 4 + 1 px, where n is the number of shapes (at least 2 here) and k is the `smooth` radius", and "below Android 13 (API 33)".

### N1. The blur's platform floor is not stated - Severity: Nit
* **Reviewer Lens:** `egm-codebase-details`
* **Affected Scope:** Lines 255, 262
* **Discrepancy Description**:
  * `applyBlur` does nothing below API 31 (`Host.kt:882`).
  * A radius under 0.1 px counts as no blur (`NodeMotion.kt:276`).
  * So the `blur` effect is absent on Android 10 and 11. The Open Questions cover only the API 33 blob fallback.
* **Rationale & Recommendation**: State the floor beside the API 33 note.

### N2. "Refreshed in place when locked" is narrower than the code - Severity: Nit
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** Line 268
* **Discrepancy Description**: The tracker refreshes the arrival on screen at every update, locked or not (`Arrivals.kt:78-85`). Locking only changes what the candidates carry.
* **Rationale & Recommendation**: Reword the line to say that.

### N3. The proof's own comments are out of date - Severity: Nit
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** Outside the range: `IslandProof.kt:788, 802` (relevant to lines 288-292)
* **Discrepancy Description**:
  * The comments say the banner is 28 dp below the pill and is a "glass banner". The design says 20 dp and an opaque banner (D-118).
  * The proof does not assert the gap.
* **Rationale & Recommendation**: Note this for the drift report, or assert the gap in the proof.

### N4. There are no phases, and the full check mode is not stated - Severity: Nit
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-codebase-details`
* **Affected Scope:** Lines 233-235, 283
* **Discrepancy Description**:
  * The standard asks for buildable phases with checkpoints, and this as-built record has none.
  * `scripts/check.sh` hands off to `scripts/check.local.sh`. The section does not say that the full run is required rather than `CHECK_FAST`.
* **Rationale & Recommendation**:
  * Say explicitly that phases are waived for an as-built record.
  * Say "run `scripts/check.sh` in full mode once at the milestone".

### N5. Headings and labels are not parallel, and the template wording does not fit - Severity: Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 235-279
* **Discrepancy Description**:
  * "Each file's role:" leads into nothing; the heading after it already does that job.
  * "Proposed Changes" sits under "Target Files (as built)", so it describes built code as a proposal.
  * The Type / Scope values vary in case and article ("KDL component", "the host", "the app's wiring").
  * Item 6's heading is very long.
* **Rationale & Recommendation**:
  * Drop the lead-in.
  * Rename "Proposed Changes" to "Changes".
  * Use lower-case noun phrases without an article for every Type / Scope.
  * Put one file per line.

### N6. The Verification Plan labels are mixed - Severity: Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 283-296
* **Discrepancy Description**:
  * Some labels are categories ("Unit Tests", "E2E") and others are a device ("S24") or a person ("The owner").
  * "E2E" is not explained.
  * "once at the milestone" reads as a fragment.
* **Rationale & Recommendation**: Use parallel labels: "End-to-end (emulator)", "Device frame stats (S24)" and "Owner sign-off".

### N7. "What done means" is one 60-word sentence, and the footer is not explained - Severity: Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 298-306
* **Discrepancy Description**:
  * Seven conditions sit in one sentence, against the short-sentences rule in AGENTS.md.
  * "Drafted with EGM" is not explained.
* **Rationale & Recommendation**:
  * Make "done" a checklist of bullets, each pointing to its verification item.
  * Spell out the footer, or drop it.
