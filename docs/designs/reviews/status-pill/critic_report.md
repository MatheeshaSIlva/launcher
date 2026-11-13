# Goldfish Review Report

**Target:** `D:\Launcher\docs\designs\status-pill.md` (`FeatureRange 1..232`)

**Scope note:** the file has 307 lines, not 232. Lines 233-306 are the Detailed Implementation Plan, the Verification Plan and "What done means". `egm-design` and `egm-structural` reviewed those lines too. Their findings there are marked *(beyond stated range)*. `egm-style` kept to lines 1-232.

**Standard:** `feature-spec-standard`, used by `egm-design` and `egm-structural`. `egm-style` had no standard and used its persona's audit checks.

## Reviewer Status

| Reviewer | Status | Verdict | Raw findings |
| --- | --- | --- | --- |
| `egm-design` | completed | Not ready | 1 Blocker, 9 Major, 5 Nit |
| `egm-structural` | completed | Changes needed | 0 Blocker, 3 Major, 11 Nit |
| `egm-style` | completed | Fix the Majors before using the doc as the acceptance test | 0 Blocker, 9 Major, 7 Nit |

**After merging duplicates:** 1 Blocker, 19 Major, 19 Nit.

Where reviewers gave the same finding different severities, this report uses the higher one and says so.

**What the reviewers confirmed in the code:**
* `island.kdl` is at version 0.9.0, with the label "Banner content".
* The widths are 132, 236, 164 and 200 dp, and the banner is 380 x 76 dp.
* The 24 ms stagger and the 120 ms blur exit are in the component.
* The `BlobElastic` constants match the doc.
* `cutExits("Banner content")` is at `KdlBar.kt:100`.
* `MAX_NAME` is 200 and `MAX_TEXT` is 500.

**What did not match:** the 0.45 s arrival time (see M-11 below).

## Prioritized Findings (Blockers -> Major -> Nits)

### B-1 No Risks & Mitigations section - Severity: Blocker
* **Reviewer Lens:** `egm-design` (Blocker), `egm-structural` (Major)
* **Affected Scope:** The whole document. The section belongs between Alternatives Considered (line 199) and Non-Functional Requirements (line 222).
* **Discrepancy Description:** The standard requires a section that lists failure modes, security implications and scalability limits, each with its mitigation. The document has none. Some risks appear only as open questions with no mitigation:
  * the S24 frame cost has not been measured;
  * Android 10-12 has never run on a device.

  Other risks are not covered at all:
  * the AGSL shader fails to compile or warm up;
  * the notification listener disconnects;
  * the lock state and an arriving notification race each other;
  * a burst of notifications arrives at once;
  * what to fall back to if the island fails.

  The risks in `DEFAULT_LAYOUT_PLAN.md` §5 are also missing: banners and heads-up, apps that hide the bar, Samsung chips (`StockBarCover`) and the privacy indicators.
* **Rationale & Recommendation:** Add an H2 "Risks & Mitigations". Give each risk its trigger, its impact and its mitigation or fallback. Link to the existing Open Questions and NFR entries instead of repeating them. M-3 to M-10 below supply most of the entries.

### M-1 Alternatives cover only animation details, with no architecture-level choice - Severity: Major
* **Reviewer Lens:** `egm-design`, `egm-structural`
* **Affected Scope:** Lines 199-218
* **Discrepancy Description:** All three alternatives (A to C) are about how the banner looks or moves. None of the main design choices is weighed against an alternative:
  * a KDL component drawn by the engine, or an extended `StatusBarView`;
  * an AGSL smooth-union blob, or two separately animated nodes, path or metaball geometry, or a separate window;
  * the `BlobElastic` physics, or spring-only tracks and keyframes;
  * privacy enforced in the tracker, or in the component;
  * the ranking in KDL policy, or in the engine;
  * one state at a time, or several states at once.
* **Rationale & Recommendation:** Add at least one or two architecture-level alternatives. Use the same Pros / Cons / Reason format, with concrete costs: complexity, frame cost and which Android versions each reaches.

### M-2 Rejections rest on the owner's word without a link - Severity: Major
* **Reviewer Lens:** `egm-structural`, `egm-design`
* **Affected Scope:** Lines 205, 212, 217-218
* **Discrepancy Description:** The standard's Alternatives Rule forbids crediting a rejection to a maintainer unless the decision is linked.
  * A's only reason is an owner quote from 2026-11-13 (line 205).
  * B cites D-118 as a bare ID, not a link (line 212).
  * C's Cons is "popped (owner, 2026-11-14)", and its "Reason for Rejection" names the replacement design rather than a reason (lines 217-218).
* **Rationale & Recommendation:** State each technical trade-off directly:
  * A: the pill gives no visible response, and the card reads as a separate object.
  * C: swapping text in place cannot be animated, because the node keeps its identity.

  Link each owner decision to its entry in `docs/DECISIONS.md`, or to its task or PR.

### M-3 Lock-screen privacy ignores Android's notification visibility rules - Severity: Major
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Goal 5 (lines 50-51), CUJ 8 (lines 139-145), Privacy (lines 195-197 and 224-225). Code: `app/src/main/java/dev/launcher/app/statusbar/KdlArrivals.kt:56-65`
* **Discrepancy Description:** Redaction is a single yes/no on "locked", and ignores four platform rules:
  * `Notification.visibility`: for VISIBILITY_SECRET the app's name still shows, which reveals that the notification exists;
  * `publicVersion`;
  * the user's lock-screen setting to hide sensitive content or show no notifications;
  * Android 15's redaction of sensitive content such as one-time codes.

  When the phone is unlocked, text shows whatever those settings say. The doc gives no reason for overriding them.
* **Rationale & Recommendation:** Either follow visibility, `publicVersion` and the lock-screen setting (show nothing for SECRET), or record the override as a D-entry and list it as a risk. Add a unit fixture for each visibility level.

### M-4 Lock redaction depends on a timing race and on a label creators can replace - Severity: Major
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 195-197 and 256. Code: `KdlBar.kt:100`
* **Discrepancy Description:**
  * **(a) Lock detection.** The lock is read from the SCREEN_OFF, SCREEN_ON and USER_PRESENT broadcasts. Three cases are not covered:
    * a lock with no screen-off (device-admin lock-now, Smart Lock);
    * "lock after N s", where the screen is back on before the lock lands;
    * Always-On Display.
  * **(b) The no-exit guarantee.** It works only through a string match on the node label "Banner content". A creator's replacement component without that label silently loses the guarantee, and line 32 names creator replacement as an aim.
* **Rationale & Recommendation:**
  * For (a): specify how the lock state is observed, for example `KeyguardManager.KeyguardLockedStateListener` on API 33+ with a fallback below it, and test the three cases.
  * For (b): make "cut in-flight exits on lock" an engine contract that does not depend on labels (sources marked sensitive cut their dependents' exits). Otherwise, make it a requirement on creator components that the validator checks.

### M-5 Fixed widths break on narrow screens, rotation and large text - Severity: Major
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** CUJ 5 (line 117), lines 170-174. Code: `island.kdl` lines 39 and 108-124
* **Discrepancy Description:** The banner is a literal 380 dp wide and the media row 236 dp. Many phones are about 360 dp wide, and in landscape the cutout sits on a side. `DEFAULT_LAYOUT_PLAN.md` §3 requires checks for rotation, a phone without a cutout and large text. The Verification Plan runs only `-e cutout emu01`.
* **Rationale & Recommendation:** Define sizing rules, for example banner width = min(380 dp, screen width - 2 x margin), and the behaviour in landscape and with no cutout. Add emulator proof stages for:
  * no cutout;
  * a 360 dp wide screen;
  * font scale 1.3 or more;
  * rotation;
  * RTL.

### M-6 How the island and the existing banners or heads-up share the screen is not specified - Severity: Major
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Non-Goals (line 58), CUJ 4 and CUJ 5, Goal 2 (lines 43-44). Related: `DEFAULT_LAYOUT_PLAN.md` lines 51 and 104-105
* **Discrepancy Description:** The plan keeps the existing banners until the island proves it handles calls and alarms. The doc does not say:
  * whether an alerting message then shows twice;
  * which surface wins;
  * what the island shows during a call or an alarm.

  Goal 2's ranking also omits call, alarm and volume, which the plan's ranking includes.
* **Rationale & Recommendation:** State the rule for which surface shows while the banners remain, and the island's state during a call or alarm. Bring Goal 2's ranking in line with the plan's.

### M-7 No fallback if the frame budget is missed; Android 10-12 is unverified - Severity: Major
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Goal 6 (lines 52-53), Open Questions (lines 68-72), Performance (lines 226-227)
* **Discrepancy Description:**
  * Three costs add up per frame: the AGSL union, the elastic step and the blur track. None has been measured on the S24.
  * The reference floor device (the Redmi, `ENGINE_SPEC.md:373`) has not been measured at all.
  * Nothing says what happens if the frame bound is missed.
  * Below Android 13 the shape draws "plainly", with no neck. That leaves most of the supported range (Android 10-12) showing a different arrival that no device has run.
  * The blur on presence (`RenderEffect`) needs API 31, and its behaviour on API 29-30 is not stated.
* **Rationale & Recommendation:**
  * Define a fallback order: turn off elastic, then the neck, then the blur, and finally force minimal mode below API 33.
  * Either make a device below Android 13 part of the exit, or drop detailed mode on those versions.
  * Add the Redmi to the frame check, or say why it is left out.

### M-8 Notification bursts and edge cases are not defined - Severity: Major
* **Reviewer Lens:** `egm-design` (with `egm-style` finding 15 on "+N")
* **Affected Scope:** CUJ 6 (lines 123-128), Sources (lines 180-183), line 106
* **Discrepancy Description:**
  * A steady stream of alerts restarts the 4 s timer each time, so the banner can stay up indefinitely.
  * Bursts can pile up exit nodes, and nothing caps how many retained exits accumulate.
  * Nothing says what happens when the app cancels the notification during its 4 s.
  * Nothing says what happens to arrivals while the bar is hidden (CUJ 9).
  * The doc never says what N counts in minimal mode's "+N others".
* **Rationale & Recommendation:** Set a maximum banner lifetime or a rule for merging arrivals, cap the retained exits, and define the behaviour on cancel and in full screen. Define N.

### M-9 Items the plan requires are neither in scope nor excluded - Severity: Major
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Overview, Goals, Non-Goals, "done" (lines 300-302). Related: `DEFAULT_LAYOUT_PLAN.md` lines 72, 74 and 106-109
* **Discrepancy Description:** The exits of plan slices E2.14c and E2.14e require four things:
  * Samsung's charging and cast chips stay covered (`StockBarCover`);
  * Android's privacy indicators stay visible;
  * an S24 run with a real charge and a real cast;
  * the island follows a pull, routed through `BarHost`.

  The doc neither covers these nor excludes them, so "done" could be signed off without them.
* **Rationale & Recommendation:** List each as a goal with a verification step, or as a non-goal deferred to a named slice. Name the E2 slices this document closes (see N-9).

### M-10 Accessibility is not covered - Severity: Major
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** The whole document
* **Discrepancy Description:** The island takes over the status bar's time, battery and notification announcements. The doc says nothing about:
  * TalkBack labels;
  * live-region announcements of arrivals, and how lock redaction applies to them;
  * contrast of `muted` text on #121212.
* **Rationale & Recommendation:** Add accessibility requirements and a check for them. The live-region policy must follow the same redaction rules as the screen.

### M-11 The stated arrival times do not match the component and are never verified - Severity: Major
* **Reviewer Lens:** `egm-style` (Major), `egm-structural` (Nit)
* **Affected Scope:** Goal 4 (lines 47-49), CUJ 5 (line 115), Verification Plan (lines 283-296)
* **Discrepancy Description:**
  * The doc says the arrival takes "about 0.45 s end to end".
  * In `island.kdl`, the banner's size transition runs 480 ms, and the last content settles at about 570 ms.
  * The doc does not say where "end to end" starts and stops, or whether the figure was measured or derived.
  * The same applies to the 0.28 s break time.
  * No step in the Verification Plan measures either figure.
* **Rationale & Recommendation:** Name the start and end points and the source of each figure. Then either add a check for it, or mark it as a tuning value rather than an acceptance criterion.

### M-12 "Pill" and "island" are used inconsistently - Severity: Major
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Line 1, lines 11-12, line 171, CUJ 5 (lines 114-115), lines 187-190
* **Discrepancy Description:** The title and line 11 use "status pill" and "island" for the whole component. Line 171 then calls the pill the island's first box. A reader cannot tell what "the pill widens" refers to.
* **Rationale & Recommendation:** Define each term once at the top:
  * "island": the component;
  * "pill": its top shape;
  * "banner": its second shape.

  Use them that way throughout, and retitle the document to match.

### M-13 "At once" means two different things - Severity: Major
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** CUJ 2 (lines 89 and 93), CUJ 9 (line 154)
* **Discrepancy Description:** In CUJ 2, "at once" means the motion starts without delay. In CUJ 9 it means the change has no animation at all. An acceptance test cannot tell these apart.
* **Rationale & Recommendation:** Write "with no delay" in CUJ 2, and "instantly, without animation" in CUJ 9.

### M-14 Goal 3's "every change moves" conflicts with reduced motion - Severity: Major
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Goal 3 (lines 45-46), line 34; compare CUJ 9 (lines 154-155)
* **Discrepancy Description:** Goal 3 says every change moves, with "never a pop". CUJ 9 makes every change instant when the system's Remove animations setting is on.
* **Rationale & Recommendation:** Add "unless the system's Remove animations setting is on (CUJ 9)" to Goal 3 and to line 34.

### M-15 Vague motion and timing words leave the CUJs untestable - Severity: Major
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 45, 79, 89, 90, 145 and 152
* **Discrepancy Description:** Line 300 makes "every CUJ behaves as written" the acceptance test, but these terms have no measurable value:
  * "a beat apart" (lines 45 and 90);
  * "soft spring" (lines 89 and 152);
  * "scrolling slowly" (line 90);
  * "for a moment" (line 152);
  * "not just plugged in" (line 79);
  * "however late its removal comes" (line 145).
* **Rationale & Recommendation:** Replace each with its value from the KDL or the code:
  * "a beat apart": the 70, 120 and 170 ms delays;
  * "soft spring": its parameters;
  * "scrolling slowly": the title's scroll speed;
  * "for a moment": how long the transient show lasts;
  * "not just plugged in": "outside the 3 s plug-in moment";
  * "however late its removal comes": "even if the notification is removed after the lock".

### M-16 Colour token names appear although the island has no tokens - Severity: Major
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Non-Goals (lines 61-62), CUJ 3 (line 99), CUJ 5 (lines 117-119)
* **Discrepancy Description:** The Non-Goals say the island's colours are literal values for now. The CUJs then use the names `ok`, `surface`, `ink`, `ink-2` and `muted`, and only `surface` is given a hex value.
* **Rationale & Recommendation:** Give the literal value first for every colour, with the design name after it, for example "#F2F2F2 (the design's `ink`)". Say once that these names come from the design, not from engine tokens.

### M-17 "Finished" overstates the status - Severity: Major
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Line 19
* **Discrepancy Description:** Line 19 calls the island the first element of the default layout "to be finished". But the doc's status is In Review, the S24 frame cost is unmeasured, and the owner has not signed it off.
* **Rationale & Recommendation:** Write "the first element of the default layout built on the engine".

### M-18 The evidence for Android 12 and below is unclear - Severity: Major
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 70-71
* **Discrepancy Description:** "Checked on the emulator only through code paths" could mean a code review, a unit test or an emulator run with the fallback forced. The reader cannot tell how much was checked.
* **Rationale & Recommendation:** Name the exact check. For example: "a unit test forces the plain-shape path; no Android 12 or earlier image or device has run it." This also feeds M-7.

### M-19 An Open Question is written as a settled decision - Severity: Major
* **Reviewer Lens:** `egm-style` (Major), `egm-structural` (Nit)
* **Affected Scope:** Line 73
* **Discrepancy Description:** "The minimal mode stays the default. The owner chose this for now" is a decision, not a question.
* **Rationale & Recommendation:** Either move it to Non-Goals or to Rollout, or rewrite it as a question with an owner and a trigger, for example: "does detailed mode become the default? Decided by the owner at sign-off."

### N-1 Section names differ from the standard - Severity: Nit
* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** Lines 28, 38 and 55
* **Discrepancy Description:** The standard names its sections "Problem Statement" and "Goals & Non-Goals". The doc uses "The Problem", and puts Goals and Non-Goals under separate H2s.
* **Rationale & Recommendation:** Rename to match the standard, or accept the split as a house variant.

### N-2 Target Files paths are ambiguous and Create or Modify is not marked per file - Severity: Nit *(beyond stated range)*
* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** Items 4-6 (lines 259, 264 and 271)
* **Discrepancy Description:**
  * In item 6, `shade/...`, `Badges.kt` and `GestureNav.kt` follow a `statusbar/` path, so they read as if they sit under `statusbar/`.
  * In item 4, `ui/motion/NodeMotion.kt` follows `model/` files and has the same problem.
  * Item 6 says "(Modify / Create)" without saying which files are created.
* **Rationale & Recommendation:** Give one full path per file, and mark each file Create or Modify.

### N-3 CUJs bundle several journeys, and their bullet style varies - Severity: Nit
* **Reviewer Lens:** `egm-structural`, `egm-style`
* **Affected Scope:** CUJ 2 (lines 89-93), CUJ 5 (lines 114-121), CUJ 6 (lines 127-128), CUJ 9 (lines 149-155)
* **Discrepancy Description:**
  * Some "Then" lists contain new triggers: the pause, the 4 s close, and an arrival during the close.
  * CUJ 9 joins two unrelated journeys: full screen and reduced motion.
  * Bullets switch between lowercase ending in ";" and capitalised full sentences.
* **Rationale & Recommendation:** Split these into separate CUJs, for example "Music pauses", "Banner closes", "Arrival during close", "Full screen" and "Reduced motion". Use one bullet style throughout.

### N-4 Bullet markers and line wrapping are inconsistent - Severity: Nit
* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** Lines 13-17, 31-34 and 79
* **Discrepancy Description:** Lines 13-17 and 31-34 use `-` bullets, while every other list uses `*`. Line 79 runs past the wrap width the rest of the file keeps to.
* **Rationale & Recommendation:** Use one bullet marker throughout, and wrap line 79.

### N-5 Horizontal rules are used unevenly - Severity: Nit
* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** Lines 7, 220, 231 and 304
* **Discrepancy Description:** Rules separate only some sections, so a reader cannot tell what grouping they mark.
* **Rationale & Recommendation:** Either use rules only to mark the doc's large parts and say so, or remove them.

### N-6 Verification Plan and "done" sit under the implementation plan - Severity: Nit *(beyond stated range)*
* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** Lines 281 and 298
* **Discrepancy Description:** Both are acceptance criteria for the whole feature, but they are H3s under the Detailed Implementation Plan.
* **Rationale & Recommendation:** Make them H2s at the same level as the Detailed Implementation Plan.

### N-7 The architecture diagram leaves data flows unconnected - Severity: Nit
* **Reviewer Lens:** `egm-structural`, `egm-style`
* **Affected Scope:** Lines 159-168
* **Discrepancy Description:**
  * The notifications row never joins `SourceBinder`.
  * `BlobPainter` and `BlobElastic` hang under no column.
  * `KdlArrivals`, which is app code, sits in the Android column.
  * The app column's text wraps across the engine columns.
* **Rationale & Recommendation:** Connect the notifications row to `SourceBinder`, attach the blob painter under ComposeHost, and realign the columns or add a legend.

### N-8 Cross-references are bare IDs, not links - Severity: Nit
* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** Throughout, for example lines 19, 24, 34, 41, 44, 64, 72, 205, 212, 224, 235 and 296
* **Discrepancy Description:** D-, T- and Q- IDs and document names appear as code spans that a reader cannot follow. The E2 slice IDs this doc closes are never named.
* **Rationale & Recommendation:** Link the D- entries and the referenced documents. Name the E2 slices this document closes, such as E2.13c, E2.14a, E2.14e and E2.17.

### N-9 Template labels do not fit a document about a built feature - Severity: Nit *(beyond stated range)*
* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** Lines 235-279
* **Discrepancy Description:** The section describes what was built, but each item is labelled "Proposed Changes".
* **Rationale & Recommendation:** Rename the label to "Changes", or say once that the template labels are kept on purpose.

### N-10 Constants are given without a source - Severity: Nit
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 186-190 and 247
* **Discrepancy Description:** These values have no stated derivation or tuning source:
  * 32 % and 22 %;
  * 4.5 Hz with damping 0.32;
  * the pad formula (n - 1) k / 4 + 1 px.
* **Rationale & Recommendation:** Add one line per constant saying where it comes from.

### N-11 Island settings live in odd files - Severity: Nit
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 271-279
* **Discrepancy Description:** The notification mode switch is in `GestureNav.kt`, and a test option is in `Badges.kt`. Neither file is an obvious owner for island settings.
* **Rationale & Recommendation:** Explain why they sit there, or plan to move them.

### N-12 The developer switch's persistence is not stated - Severity: Nit
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Rollout (lines 228-229)
* **Discrepancy Description:** The doc does not say whether the switch survives a reboot or a process death, or how to turn it off.
* **Rationale & Recommendation:** State all three.

### N-13 Unclear how the pill sits on an off-centre cutout - Severity: Nit
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** CUJ 2 (line 92)
* **Discrepancy Description:** "The camera's slot stays on the pill's centre" is ambiguous on phones whose punch-hole is in a corner.
* **Rationale & Recommendation:** Say whether the pill grows evenly on both sides or is held at the screen edge.

### N-14 The product name and shorthand are not introduced - Severity: Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 30, 52, 205 and 228
* **Discrepancy Description:**
  * "Not a Launcher" reads as a sentence fragment, not a product name.
  * "proven" disable flags is unsupported.
  * "S24" and "concept B" are never introduced.
  * `TEST_STATUS` is project shorthand, not a runnable command.
* **Rationale & Recommendation:**
  * Introduce the product name.
  * Cite the decision behind "proven", or drop the word.
  * Write "Samsung Galaxy S24" once, and define "concept B" or remove it.
  * Give the full broadcast command for `TEST_STATUS`.

### N-15 "Pop" is never defined - Severity: Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 34 and 217
* **Discrepancy Description:** "Pop" is used as a term with a specific meaning, but the doc never defines it.
* **Rationale & Recommendation:** Define it once: "a change that appears in one frame with no motion".

### N-16 Subjective wording - Severity: Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 33, 186, 191, 203 and 210
* **Discrepancy Description:** "without clutter", "small physics model", "machinery", "simple" and "read as heavy" are opinions or loose terms.
* **Rationale & Recommendation:**
  * Replace "without clutter" with the rule itself ("one state at a time", Goal 2).
  * Drop "small".
  * Write `motion/Curves` instead of "machinery".
  * Replace "simple" with the concrete benefit.
  * Attribute "heavy" to the owner.

### N-17 The definition of `notifications.shown` is hard to follow - Severity: Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 180-181
* **Discrepancy Description:** "A list of the current arrival keyed by its own number" is a list of one item, keyed by a number the text never names. `ArrivalTracker` is listed under Sources, but it is not a source.
* **Rationale & Recommendation:** Rewrite as: "A one-element list holding the current arrival, keyed by its sequence number (`seq`), so a replacement plays an exit and an enter." Move `ArrivalTracker` out of Sources.

### N-18 "Leave without an exit" contradicts itself - Severity: Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 196-197
* **Rationale & Recommendation:** Write "are removed without playing their exit animation".

### N-19 Small wording fixes - Severity: Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 13, 41, 107, 127 and 187
* **Discrepancy Description and Recommendation:**
  * Line 13: "the song that plays" becomes "the playing song".
  * Line 107: "the state below returns" becomes "the next-ranked live state returns".
  * Line 127: "the 4 s start again" becomes "the 4 s timer restarts".
  * Line 41: spell out the OLED reason in a full clause.
  * Line 187: "held back by 22 %" should match the code, which says the drop moves up by 22 % of the gap.
