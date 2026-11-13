# Goldfish Annotated Review Report

**Target:** `docs/designs/status-pill.md` (full file, 306 lines)
**Stages:** 5 Zoom Out (`zoom_out_report.md`), 6 Comprehension (`comprehension_report.md`), 7 Critic (`critic_report.md`),
8 Intermediate Zoom Out (`intermediate_zoom_out_report.md`), 9 Implementation Readiness
(`implementation_readiness_report.md`). Every reviewer completed.
**Raw count:** about 85 findings (3 Blockers as rated, about 50 Major, about 35 Nit), merged below into 24 issues.
**Status:** resolved. O-1: detailed on Android 13+ only (owner). O-2: the app keeps the rule until E3 (owner). F-1 to F-16 applied to the doc (and F-6, the Android rules and the 13+ fallback in code, tested); C-1 to C-3 contested; Nits declined as listed. Recorded as D-120.

Categories: **OWNER** (a real trade-off; the owner decides, one question at a time), **PROPOSE FIX** (valid; the lead
fixes it), **CONTEST** (the reviewer misread; evidence given), **DECLINE** (a Nit not worth it, recorded).

## Owner decisions

### O-1 Detailed mode's scope in this milestone - Severity: Major (Zoom Out 9, Intermediate 1)
*   **Finding:** the detailed banner carries most of the complexity (shader, elastic, keyed messages, the lock race) but
    is off by default; below Android 13 its fluid parting does not exist. The reviewers ask the owner to set its scope.
*   ***AI Suggestion:*** OWNER. Three defensible answers (see the gate).

### O-2 Where the lock-screen privacy rule lives - Severity: Major (Zoom Out 1, Critic M-3/M-4, Readiness M2, Intermediate 5, 6)
*   **Finding:** privacy is five local patches in app code (screen-off broadcasts, `cutExits("Banner content")` by label,
    redaction in `KdlArrivals`). A creator's replacement island gets none of it, and Android's own lock-screen
    visibility rules (`VISIBILITY_SECRET`, `publicVersion`, the user's setting) are ignored.
*   ***AI Suggestion:*** OWNER. An architecture choice (engine-level rule now, or the app's rule now and the engine's at
    E3 with the shade).

## Fixes the lead makes (PROPOSE FIX)

| # | Issue | From | Fix |
| --- | --- | --- | --- |
| F-1 | No Risks section | Critic B-1 (Blocker), Intermediate 4 (Major) | A short Risks & Mitigations section: about five rows (frame cost, below 13, lock race, burst length, small screens), linking the plan's §5 |
| F-2 | Undefined terms and workflow | Comprehension 1, 2, 4, 5, 6, 10-13; Critic N-14, N-15; Readiness M11, M12 | Define on first use or link: drift report, Codex, AGREE, gate 3 (D-119); the developer switch as full `adb shell am broadcast` commands; `TEST_NOTIFY`; gap, shown gap, reach; the pad's n and k; "removal"; the owner; draft 4 (Figma link); "+N"; proof stages and `emu01` |
| F-3 | Colour names read as tokens | Comprehension 3; Critic M-16; Zoom Out 7 | Say they are the design language's names with their hex values; tokens stay a non-goal (literals until the theme supplies them) |
| F-4 | Wrong or incomplete file list | Readiness B1, B2, M3, M4 | Full repo-relative paths, Create/Modify per file, the missing files (`IslandProof.kt` and others), the island's two message variants (`Message`, `Next message` on `replaced`), the true branch and task range |
| F-5 | Verification not runnable | Readiness M5-M8; Critic M-11 | The full emulator command (`adb shell am instrument -w ...`), what PASS means, test cases by name, the S24 frame-stats procedure (`tools/framestats.py`, the scenario), the measured arrival times (banner full 0.48 s, content settled about 0.57 s) instead of "about 0.45 s" |
| F-6 | Fixed widths | Critic M-5; Zoom Out 14; Intermediate nit | Clamp the banner to the screen's width less 16 dp each side (code and doc); other states keep their fixed widths, with rotation and large text as stated limits |
| F-7 | Coexistence with banners and heads-up | Critic M-6; Zoom Out 3; Intermediate 7 | One paragraph: with the island on, the shade's banners give way except calls and alarms (T-014); merging them is E3 |
| F-8 | Bursts | Critic M-8 | State the rule: each new arrival restarts the 4 s; a cancelled notification ends its arrival at once; full screen hides the island |
| F-9 | Frame-budget fallback | Zoom Out 6; Critic M-7; Intermediate 2 | One fallback: if the S24 misses the bound, the blob draws plain shapes (no neck, no elastic); a Redmi check line |
| F-10 | Smooth union vs the plan's "Shapes" clause; `blob` missing from the spec's table | Zoom Out 4; Readiness M10 | Cite D-098 ("only if a later design asks") and D-117 (the design asked); propose `blob` for the spec's primitive table (a spec change: the owner's) |
| F-11 | Elastic constants hidden behind `elastic=#true` | Zoom Out 2; Intermediate 8 | A D-entry recording the deviation from the spec's Precision rule until the constants are exposed; the doc points to `BlobElastic.kt` |
| F-12 | Wording | Critic M-12 to M-15, M-17 to M-19 | "The island" throughout (the pill is its first shape); "at once" split into "in one frame" and "without waiting"; Goal 3 excepts reduced motion; timing words get values; Status "In Review", "built" not "finished"; the Android 12-and-below evidence stated plainly; the mode Open Question moved to a decision |
| F-13 | Plan exits missing from "done" | Zoom Out 8; Readiness M9; Critic M-9 | List which of the plan's E2.14-E2.17 exits this milestone covers and which stay with later steps (`StockBarCover`, goldens and parity with `StatusBarView`, the pull, both themes) |
| F-14 | Accessibility | Critic M-10; Intermediate nit | A stated limit: accessibility comes with input dispatch (E3); spoken announcements, when they come, follow the redaction rule |
| F-15 | Alternatives | Critic M-1, M-2 | Add the open architecture alternative (two boxes, no union); link the owner's rejections to D-117, D-118 and the thread |
| F-16 | CUJs carry tuning numbers and code names | Zoom Out 11; Intermediate 9 | Keep the CUJs about behaviour; numbers move to the Technical Plan |

## Contested (CONTEST)

| # | Finding | Evidence |
| --- | --- | --- |
| C-1 | The content blur contradicts "no blur" (Zoom Out 10) | D-118 scraps glass and backdrop blur; the content's enter blur is a motion effect (`PresenceEffect.BLUR`, `NodeMotion.kt`). The doc's non-goal will say "no glass or backdrop blur" so it cannot be misread |
| C-2 | The ranking repeated in about 15 nodes (Zoom Out 12) | Valid as a cleanup, but KDL has no `let` or derived props yet (`Primitives.kt`); recorded for when it does |
| C-3 | A one-item list for keyed exits (Zoom Out 13) | `each` is the engine's only keyed presence (`Host.kt` `planNode`); a keyed `visible` is not in the schema |

## Declined (Nits, recorded)

Section names against the template (Critic N-1), bullet and rule style (N-4, N-5), diagram arrows (N-7, fixed in passing
where the doc is rewritten), the proof's out-of-date comments (Readiness N3, fixed in passing), right-to-left layouts.

## Traceability

| Raw finding | Issue |
| --- | --- |
| Zoom Out 1, Critic M-3, M-4, N-18, Readiness M2, Intermediate 5, 6 | O-2 |
| Zoom Out 9, Intermediate 1, 3 | O-1 |
| Critic B-1, Intermediate 4 | F-1 |
| Comprehension 1, 2, 4-6, 10-13, Critic N-12, N-14, N-15, N-17, Readiness M11, M12, N1, N2 | F-2 |
| Comprehension 3, Critic M-16, Zoom Out 7 | F-3 |
| Readiness B1, B2, M3, M4, Critic N-2, N-9, N-11 | F-4 |
| Readiness M5-M8, N4, N6, Critic M-11, N-6 | F-5 |
| Critic M-5, N-13, Zoom Out 14 | F-6 |
| Critic M-6, Zoom Out 3, Intermediate 7 | F-7 |
| Critic M-8 | F-8 |
| Zoom Out 5, 6, Critic M-7, M-18, Intermediate 2, 3 | F-9 |
| Zoom Out 4, Readiness M10 | F-10 |
| Zoom Out 2, Intermediate 8, Critic N-10 | F-11 |
| Critic M-12 to M-15, M-17, M-19, N-3, N-16, N-19, Comprehension 7-9, Readiness N5, N7 | F-12 |
| Zoom Out 8, Readiness M9, Critic M-9 | F-13 |
| Critic M-10 | F-14 |
| Critic M-1, M-2, N-8 | F-15 |
| Zoom Out 11, Intermediate 9 | F-16 |
| Zoom Out 10, 12, 13 | C-1, C-2, C-3 |
