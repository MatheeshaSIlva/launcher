# Decision log

Append-only, newest at the bottom. Log any choice a future reader would otherwise have to rediscover: architecture, libraries, data model, trade-offs. Owner decisions are tagged `[owner]`, agent decisions `[agent]`.

Template:

```
### D-000: Title (YYYY-MM-DD) [owner|agent]
Context: what forced the choice
Decision: what we chose
Alternatives considered: what we did not choose, and why
Consequences / revisit when: what this commits us to
```

---

### D-001: Adopt the governed workflow (2026-10-22) [owner]
Context: Iterations were producing more bugs than they were worth, and it was unclear whether the build was still following the original direction.
Decision: All work goes through architect, implementer, check gate, reviewer and QA. `docs/SPEC.md` is the locked direction and `docs/ROADMAP.md` defines done.
Alternatives considered: Continuing with a single agent plus owner review only.
Consequences / revisit when: Revisit if the pipeline overhead is larger than the bugs it prevents. `/progress` and `/audit` should show this.

### D-002: v1 direction set at kickoff (2026-10-22) [owner]
Context: Kickoff of the governed workflow; the direction had to be written down to check work against.
Decision: v1 is for themers and tinkerers; all four journeys (home and app open/close, App Switcher and gestures, shade everywhere with iOS 27 and Pixel layouts, customise look and motion with a full in-app theme builder) are v1. Ship bar: onboarding without adb, 0 missed refreshes on the S24 scenarios, polish pass on every screen, zero P0/P1. After v1: theme gallery and sharing, other phones, a One UI shade layout, Play Store and billing. Local builds and checks move to the Linux machine (Android SDK installed here; CI stays).
Alternatives considered: iOS-feel-for-Galaxy-owners as the primary audience; parking the Pixel shade until after v1.
Consequences / revisit when: Pixel shade and the theme builder are v1 work. Revisit scope if M3/M4 prove much larger than a few days each.

### D-003: Development stays on Windows (2026-10-22) [owner]
Context: Kickoff ran on a Linux machine with no Android SDK; D-002 had moved local builds there.
Decision: Work continues on the Windows dev machine (JDK 17, Git Bash, the existing SDK and emulator). This supersedes D-002's line about the Linux machine.
Alternatives considered: Installing the Android SDK on Linux.
Consequences / revisit when: M0 targets Windows; check.sh must run under Git Bash.

### D-004: M0 built on origin/main (2026-10-22) [owner]
Context: Local main had split from origin/main and carried a checkpoint commit that turned 206 files to CRLF.
Decision: The branch was rebuilt on origin/main (2951e09) with the checkpoint's 3 real changes, the kit and the kickoff re-applied as LF. Old local commits are kept on branch ship/m0-old.
Alternatives considered: Merging the old local main.
Consequences / revisit when: Local main still points at the old commits until fast-forwarded.

### D-005: Worktrees removed (2026-10-22) [owner]
Context: Four stale .claude/worktrees existed.
Decision: All four worktrees were removed and their claude/* branches deleted; their tools/shots were discarded.
Alternatives considered: Keeping them.
Consequences / revisit when: None.

### D-006: Operating rules in their own file (2026-10-22) [owner]
Context: The kit's rules would have replaced the project's CLAUDE.md.
Decision: They live in docs/OPERATING_RULES.md, imported from CLAUDE.md with one line.
Alternatives considered: Merging them into CLAUDE.md.
Consequences / revisit when: Revisit if the import stops loading.

### D-007: Pushing (2026-10-22) [owner]
Context: The kit said never to push; the owner wants the orchestrator to ship.
Decision: The orchestrator pushes to main only after check, review and QA pass and the owner says go for that task.
Alternatives considered: Owner always merges.
Consequences / revisit when: Replaces the kit's "do not push or merge" rule.

### D-008: What the gate runs (2026-10-22) [agent]
Context: `./gradlew check` also runs release lint and tests, doubling the time for nothing shipped.
Decision: The gate runs `assembleDebug lintDebug testDebugUnitTest` with `--continue`, debug variant only.
Alternatives considered: `./gradlew check`.
Consequences / revisit when: Revisit if release-only problems appear.

### D-009: Gate selects JDK 17 itself (2026-10-22) [agent]
Context: Gradle 8.9 cannot run on JDK 25 and the shell may inherit any JAVA_HOME.
Decision: check.local.sh always selects JDK 17 itself (JAVA_HOME_17, else the Adoptium path) and refuses another version.
Alternatives considered: Trusting the inherited JAVA_HOME.
Consequences / revisit when: Revisit when Gradle is upgraded.

### D-010: Lint baseline (2026-10-22) [agent]
Context: lintDebug failed with 68 errors (58 NewApi, 9 UnspecifiedRegisterReceiverFlag, 1 MissingPermission) and 206 warnings.
Decision: Baseline at app/lint-baseline.xml; severities and abort settings unchanged, so only new errors fail. NewApi errors stay baselined until other phones are in scope (after v1).
Alternatives considered: Fixing them now (out of scope for M0); relaxing lint.
Consequences / revisit when: Tracked as B-009, B-010.

### D-011: Line endings and exec bits in git (2026-10-22) [agent]
Context: CRLF scripts and mode-644 gate scripts broke Git Bash and Linux checkouts.
Decision: `* text=auto` in .gitattributes and the executable bit in the index for the gate scripts, so they do not depend on each machine's git config.
Alternatives considered: Per-machine core.autocrlf.
Consequences / revisit when: None.

### D-012: stop-check.sh kept as written (2026-10-22) [agent]
Context: The kit's Stop hook parses `git status` paths with awk.
Decision: Kept as the kit wrote it; it works under Git Bash and its path-parsing quirk errs toward running the check.
Alternatives considered: Rewriting it.
Consequences / revisit when: Revisit if it skips the gate wrongly.

### D-013: check.local.sh may use an inherited JDK 17 as a last resort (2026-10-22) [agent]
Context: Review of M0: a Linux or other checkout without the Adoptium path would always fail the gate, even with a JDK 17 in JAVA_HOME.
Decision: Amends D-009. Order is JAVA_HOME_17, then the Adoptium path, then an inherited JAVA_HOME only if it really is Java 17 (the `version "` line, so JAVA_TOOL_OPTIONS noise does not matter). Any other version is refused.
Alternatives considered: Windows-only selection (D-009 as written).
Consequences / revisit when: Revisit if Gradle moves past 8.9 and JDK 17 is no longer required.

### D-014: M1 split into four slices (2026-10-22) [owner]
Context: M1's criterion 2 (S24 scenario runs) and the timing in criterion 1 need the owner and the phone.
Decision: Slice 1 JOURNEYS.md plus an emulator smoke script; slice 2 the S24 perf runner and summary parser; slice 3 the owner's S24 run and timed journeys run; slice 4 bug entries from the results. Slice 1 first.
Alternatives considered: Slices 1 and 2 together (larger change).
Consequences / revisit when: M1 stays ACTIVE until slice 4.

### D-015: Bug severity guide (2026-10-22) [owner]
Context: Criterion 4 of M1 and M7's "zero P0/P1" need shared severities.
Decision: P0 crash, stuck stock UI, frozen home, blocked gestures; P1 a core journey step broken; P2 a missed refresh, a pop or a stock seam; P3 cosmetic or tooling.
Alternatives considered: Missed refreshes as P1.
Consequences / revisit when: Missed refreshes do not block v1 by severity; M6 still requires 0.

### D-016: Emulator smoke script rules (2026-10-22) [agent]
Context: The smoke installs and taps without looking.
Decision: tools/smoke_emulator.sh refuses any serial that is not emulator-*; exit 0 pass, 1 step failed, 2 preflight or usage. It never force-stops, never starts Shizuku, never enables accessibility (preflight prints the command instead). The app is launched through Spotlight with Enter and closed with the bar swipe, judged by `[nav]` log counts and the focused window. Panels are opened by real touchscreen pulls (kswipe.sh): Control Center and Notification Center under ios, Android's shade under pixel; the shade setup is restored from the `[setup]` line. Not part of scripts/check.sh (needs a running emulator).
Alternatives considered: TEST_SHADE open hooks (skip the gesture path); am start (skips our launch path).
Consequences / revisit when: Revisit if Spotlight's top hit proves unreliable on the emulator.

### D-017: The smoke needs the emulator to itself (2026-10-22) [agent]
Context: Review of M1 slice 1: the smoke clears logcat, installs, and switches the shade setup, which would disturb another session on the same emulator.
Decision: The smoke does not detect other sessions; its header says it needs exclusive use, as CLAUDE.md already asks (agree on the emulator first). It restores the full shade setup (layout, placement and options) on success, failure and interrupt.
Alternatives considered: Refusing to run without a confirmation flag; reading logcat from a start time instead of clearing.
Consequences / revisit when: Revisit if parallel sessions on one emulator become common.

### D-018: A fourth round for M1 slice 1 (2026-10-22) [owner]
Context: Round 3 ended with reviewer REVISE (the panel steps did not check which panel opened) and QA FAIL (J1 step 7 named widget menu items that do not exist).
Decision: The owner allowed a fourth round limited to those two issues and QA's small notes, then review and QA again.
Alternatives considered: Committing as is with the issues logged as bugs.
Consequences / revisit when: One-off exception to the three-round limit; not a precedent.

### D-019: M1 slice 1 committed with QA's FAIL recorded (2026-10-22) [owner]
Context: Round 4 ended with the gate green and reviewer APPROVE, but QA FAIL on AC7 (5 of 9 smoke runs passed on an emulator that was swapping at 150-200 ms a frame; 5 of 5 on a fresh one).
Decision: Commit slice 1 on the branch as is, with QA's verdict recorded as given. B-022 to B-026 stay open, and hardening the smoke becomes its own small task. M1's criterion 3 stays unticked until that task passes QA.
Alternatives considered: Another round to harden the smoke first.
Consequences / revisit when: Until the hardening lands, run the smoke on a freshly booted emulator.

### D-020: SMOKE_FORCE test hook in the smoke (2026-10-22) [owner]
Context: The smoke's recovery paths (a deck opened by a bar swipe, a failure before the strip is up, a crash, an overloaded emulator) only run when the emulator misbehaves, so they cannot be tested on demand.
Decision: Approved by the owner. One env var, `SMOKE_FORCE=deck|deck-fail|early-fail|crash|overload`, forces one path; unset changes nothing; an unknown value is a usage error (exit 2). Documented in the script's header. It is a flag of a test tool, not an app config flag.
Alternatives considered: Separate scripts per repro; waiting for the emulator to fail by itself.
Consequences / revisit when: QA runs each value once. Revisit if the values multiply.

### D-021: The shell service process counts as ours in the end check (2026-10-22) [agent]
Context: The end check counted crash-buffer lines naming dev.launcher.app, which already includes the `dev.launcher.app:shell` process, but a crash of it was not printed or saved.
Decision: The end step fails on any crash-buffer line naming dev.launcher.app, `:shell` included, prints up to 40 lines from the first one and saves the whole buffer to tools/shots/smoke_crash.txt. Reason: that process holds the status bar flags and the watchdog sync, so its crash is a real P0 under D-015. The buffer is still cleared before the install.
Alternatives considered: Ignoring `:shell` (misses a P0).
Consequences / revisit when: Revisit if QA shows install-time crashes of the old service only.

### D-022: Overload is refused, not adapted to; a deck opened by a bar swipe is recovered (2026-10-22) [agent]
Context: QA's 5 of 9 runs failed on an emulator swapping at 150-200 ms a frame (B-026); the deck opens on any stall of 150 ms or more between injected events (B-022).
Decision: After home settles, the median frame time over the two home swipes is read from `dumpsys gfxinfo` (frames since a reset). Above FRAME_LIMIT_MS (80, script constant) the run exits 2 "SMOKE REFUSED ... emulator overloaded" with the restart command, after cleanup; with fewer than 5 frames the check is skipped with a note. Healthy medians measured 2026-10-22 on an emulator up about 2 h (load 0.5 to 2.3, 328 MB free of 3.9 GB): 19, 40, 44, 46, 46, 48 ms (also 22, 40, 44 in forced runs). 3 x 48 = 144, which the plan's 40-80 clamp brings down to 80 (the ceiling comes from that clamp, not from a measurement); QA records fresh-emulator medians and the limit is revisited from them. Refused runs read 85 to 125 ms at load 4 to 5 after many installs. A deck opened by a home bar swipe is closed with a note and the run continues; one over the app fails at `app`. Amends D-016: exit 2 also means "emulator unfit", possibly after the install, with cleanup.
Alternatives considered: Slower swipes or longer settle bounds (the deck opens on any stall of 150 ms, so stretching makes holds likelier and does not remove the cause); swap use as the signal (550/750 MB used at load 0.34, not a reliable sign).
QA on a freshly booted emulator (2026-10-22, 22:40 boot): 18, 19, 19, 19, 22, 25 ms in the first runs, rising to 38-65 ms after 10-17 installs in 20 minutes (swap 495 of 733 MB); every plain run passed, so the limit stays 80.
Consequences / revisit when: A run on a swapping emulator measures the emulator, not the build. Revisit the limit if a fresh emulator's medians come near it, or if healthy runs are refused.

### D-023: New lint warnings do not block the gate (2026-10-24) [owner]
Context: QA found that new lint warnings pass the gate (B-016) while M0's criterion said "only new issues fail" (Q-002).
Decision: Only new lint errors fail the gate; warnings do not. M0's criterion now says "new errors".
Alternatives considered: `warningsAsErrors` (every new warning blocks a stop).
Consequences / revisit when: Warnings can pile up unseen; revisit if a warning class hides real bugs (the drift audit can count them).

### D-024: Scenario scripts read the shade layout through the files export (2026-10-24) [agent]
Context: B-030: the shade scenario scripts tapped blindly under the Pixel layout. They must know the phone's shade layout before any touch.
Decision: A script asks the app for its files (`DESIGN_RELOAD --es files export`, read-only: no design reload), waits for `dev-out/.done` and reads the shade's layout and placement from `dev-out/setup.json`. A script written for one layout refuses (non-zero exit, before any touch) when the phone is on another, or when the layout cannot be read. It never switches the layout itself (that is the owner's setting).
Alternatives considered: The `[setup]` log line (printed only at the app's start; can be gone from the log buffer). A new query broadcast in the app (an app change for a tooling bug). Switching the layout for the run and back (changes the owner's setup; a crash mid-run would leave it switched).
Consequences / revisit when: Every scripted run on the phone first exports the app's files (a few hundred KB written to its external folder). Revisit if a script needs another element's layout too.

### D-025: The gate runs the shell tests under tools/tests (2026-10-24) [owner]
Context: B-030's layout guard keeps the scenario scripts from tapping the owner's phone under the wrong shade layout. Its test (`tools/tests/scenario_layout_guard_test.sh`, fake adb, no device, about 2 min) ran only by hand, so a regression in a privacy guard would go unseen. The reviewer asked whether it belongs in the gate.
Decision: Yes (owner, 2026-10-24: "yes, add it to the check"). `scripts/check.local.sh` runs every `tools/tests/*_test.sh` after Gradle, and the gate fails if any of them fails.
Alternatives considered: Running it by hand when the scripts change; running it only when tools/ changed.
Consequences / revisit when: Every gate run (and so every stop of a Claude session through the Stop hook) takes about 2 minutes longer. Revisit if the shell tests grow past a few minutes.

### D-026: An opened notification group shows at most 8 children, as Android 17 (2026-10-24) [owner]
Context: B-034. Android 17 shows at most 8 children of an opened group (AOSP NotificationChildrenContainer, NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED); the rest are reached by dismissing others or opening the app.
Decision: Copy it (owner, 2026-10-24: "Copy Android: max 8"). The count chip shows the total, and swiping the group's header clears all of them.
Alternatives considered: Showing every child.
Consequences / revisit when: Notifications past the 8th are hidden in the Pixel shade's opened group.

### D-027: The Pixel shade's notification list as a pure row model (2026-10-24) [agent]
Context: B-034: the structure (which rows a card or group makes, what each shows, what a chevron toggles) lived inside the View class CardsBlock, untestable on the JVM, and a child's chevron closed its group.
Decision: The structure is a pure model, `shade/qs/NotifRows` (keys in, rows out, toggle on a state of key sets; no android.* types), tested on the JVM; CardsBlock keeps heights, drawing and motion. Android's counts (2 lines collapsed, 5 for the first card, 8 children opened) are code constants with their AOSP source, not tokens or layout options. Pixel cards keep Notification Center's motion roles (nc.reflow, nc.appear, nc.leave); a card's height and content crossfade move on nc.reflow with the rows' places. Swiping an opened group's header takes the whole group; Clear all flies it out as one card. Whether a child stays expanded after its group is closed and reopened copies the emulator's own shade (forgotten if it cannot be read). Plan: docs/plans/B-034.md, three slices (structure, look, motion), each through the gates; slice 1 may ship alone.
Alternatives considered: Fixing toggle() in place (fixes the chevron, not the missing header or the look, and stays untested).
Consequences / revisit when: Revisit the roles if the Pixel shade gets motion roles of its own.

### D-028: A card's own content glides between its states, it does not crossfade in place (2026-10-24) [agent]
Context: B-034 slice 3a crossfaded a card's collapsed and expanded content at their own places (D-027's "crossfade"), so for a third of every expand or collapse the title showed twice, garbled over the text (tools/shots/s3_child/a.png). CLAUDE.md's polish rules forbid doubled content; the reviewer returned REVISE.
Decision: What both states show (the title, the first line of text) is drawn once, at a place interpolated by the row's openness `k`; only what differs fades (the " • time" tail at 1-k; the app or time line, the rest of the text and the actions at k). Amends D-027's crossfade for a card's own content; a group's one-line rows still fade as the header settles, and children unfolding is slice 3b. Still not Android's TransformState.
Alternatives considered: Keeping the crossfade (fails the polish pass); Android's per-element transforms (more than needed for the same look).
Consequences / revisit when: Ellipsized strings for both widths are kept per item so the glide allocates nothing per frame.

### D-029: A fourth round for B-034 slice 3b-1; it ships only with 3b-2 (2026-10-24) [owner]
Context: Slice 3b-1 (groups unfolding and folding) used its three implement-review-QA rounds; what is left is the "…" of a long right-to-left notification drawn on the wrong side during a group's unfold (drawCut draws it as a separate run). The reviewer also asked that 3b-1 not reach the phone without 3b-2, whose single surface per card removes the corner pops seen while rows separate and rejoin.
Decision: The owner allowed a fourth round for that fix alone (draw the cut text and its "…" as one run). 3b-1 stays unpushed until 3b-2 is done; both ship together.
Alternatives considered: Logging the RTL ellipsis as a P2 bug and moving on; pausing the motion work.
Consequences / revisit when: Slices of this size have needed three rounds each; plan the next ones smaller.

### D-030: A fourth round for B-034 slice 3b-2, limited to the dissolve; motion work in smaller slices (2026-10-24) [owner]
Context: Slice 3b-2 (one surface per card, stock's cut of a swiped child, B-038) used its three rounds; two defects remain, both when a group of two loses a notification: the survivor slides through the removed child's closing slot (scrim or doubled words in the card), and the app's icon shows twice for the first half of the motion.
Decision: The owner allowed a fourth round limited to those two (a leaving child closes where its gap lies among its neighbours; one icon kept at the header's place through the handover), then the gate, a final review and QA; 3b-1 and 3b-2 ship together (D-029). Future motion work is planned in smaller slices: every slice of B-034 needed all its rounds.
Alternatives considered: Shipping with the two logged as P2 bugs; pausing B-034's motion work.
Consequences / revisit when: Revisit the slice size at the next motion item.

### D-031: A fifth, last round for B-034 slice 3b-2 (2026-10-24) [owner]
Context: Round 4 (D-030) fixed the dissolve's slot and icon where header and survivor touch, but its extra corner fix snapped the corners at the first frame of every drag of a group's last child (a regression), and when the upper child of a group of two is swiped away the survivor's top corners round in one frame at the end and the single icon hangs over the scrim.
Decision: One last narrow round: a cut's resting corners decided from structure when the child leaves (whether any row of its old card before or after it stays), never from geometry each frame, so a drag or a return always starts from square; the upper-child dissolve copied from the emulator's stock shade, captured first. QA follows at once. If anything still fails, 3b-1 and 3b-2 ship without round 4's corner change, the rest logged as bugs.
Alternatives considered: Reverting round 4's corner change and shipping with the two logged; pausing.
Consequences / revisit when: No further rounds on 3b-2.

### D-032: Notification cards have no press highlight; a swiped card fades only its content (2026-10-24)
Context: B-035 (a light blue highlight on tap and drag in the Pixel shade). 1d16f45 had given notification cards the platform's press ripple.
Decision: As Android 17's SystemUIGoogle (read from the emulator's APK): a notification's background (`notification_material_bg`) is a layer-list with no ripple, so our cards show no press state; the footer buttons keep a ripple in colorControlHighlight (`notif_footer_btn_background`). A swiped card's surface stays opaque and only its content fades, `1 - clamp(|dx| / card width / 0.6)` (SwipeHelper.getSwipeAlpha via ExpandableNotificationRow.setContentAlpha; `config_fadeDependingOnAmountSwiped` false on the emulator and the S24). The 0.6 is the token `comp.qs.card.swipe-fade-end`.
Alternatives considered: keep a faint card ripple (not what Android draws); keep fading the whole card (the scrim shows through it).
Consequences / revisit when: B-045's squash and the slot closing under a card still flying out show more with an opaque surface; revisit if the owner sees a press state on a real Pixel's shade.

### D-033: Animated tile symbols: Android's choreography in the icon set, timed through a new role (2026-10-24) [owner]
Context: B-036. Android 17 plays each tile icon's AnimatedVectorDrawable on a state change (150-800 ms per icon); ours crossfaded.
Decision: Each animation is exported into the icon set (tracks with cubic or accelerate-decelerate interpolators, normalised at build time) and played
by a pure player. Its timeline is rescaled by a new role, `motion.qs.tile-symbol`, in the Quick Settings moment (owner's
answer B), whose default gives exactly Android's timings. An interrupted symbol reverses from where it is (Android restarts the
other animation from its start: a jump, which rule 3 forbids). Sets without animations keep the crossfade. Pixel tiles only;
Control Center later (Parking lot).
Alternatives considered: timing as the icon set's own data at the preset's speed (no role); every morph on `qs.tile-state`;
SystemUI's own drawables at runtime (One UI has no Pixel SystemUI).
Consequences / revisit when: a role whose curve editor is about timing more than shape; revisit its form if the settings
cannot show it meaningfully.
Addendum (2026-10-25, owner): the role stretches and warps: a curve with a length (default linear 500 ms); length / 500
scales each icon's timeline, the curve reshapes its progress. B-047's static decode is fixed with slice 3.

### D-034: An icon's state change reverses in place, and the player owns the crossfade (2026-10-25)
Context: B-036. Android 17 restarts the other animated vector from its first frame when a tile is toggled mid-animation,
and Bluetooth turning on (no device) starts from an icon that the off icon does not end on: both jump.
Decision: `SymbolPlayer` reverses the running play from where it is; a look with no play to arrive by crossfades, and the
crossfade is the player's too (a turn mid-fade fades back, never starts a play from frame 0). Only Pixel tiles play
(Control Center later); the crossfade eases on `motion.qs.tile-state`.
Alternatives considered: copy Android's restart (a visible jump, against rule 3); the caller owning the crossfade.
Consequences / revisit when: ours differs from a Pixel only in those interrupted or jumping cases.

### D-035: Notification Center's pull is a clear glass slab (2026-10-25) [owner]
Context: B-037. iOS 27's Notification Center, while pulled and held, is clear glass over what is behind, with lens edges and
a 30 % dim below; its own content arrives on commit (capture pass 10).
Decision: In the iOS layout the pull shows a clear slab: the transparent window lets the live screen show through, a new
edge view draws the dim, corners, rim and lens bands with its own shader and an inward sampling profile (MaterialPainter is
unchanged), sampling a sharp picture of what is behind (an app's kept snapshot; home baked sharp; on the lock screen the
wallpaper, drawn inside the sheet too so One UI's lock screen never shows). A standstill release opens past 62.5 %; over
home the status bar fades out and back in on a role; the dim ramps on two roles (home, app). Look values are
`comp.nc.glass.*` tokens; the threshold is a code constant with its source.
Alternatives considered: a lens band over an opaque sliding sheet (not iOS's); MaterialPainter's outward lens (would restyle
every glass); keeping the bar over home; one dim ramp; the old sheet on the lock screen.
Consequences / revisit when: step 2 (the commit reveal, the clock's drop, the close) is planned after the S24 check.

### D-036: A fourth round for B-037 step 1, limited to two smoothness costs (2026-10-25) [owner]
Context: step 1's QA failed AC7 (NC's full-screen layer drawn in the commit's first frame: the content waited off screen,
so its layer was never prepared) and found home's picture for the lens baked on the shade's thread at every pull's start.
Decision: one more implement, review and QA round, limited to preparing the sheet's layer before the commit and baking
home's picture off the shade's thread; measured with a Perfetto trace.
Alternatives considered: push now and fix in step 2; hold.


### D-037: A fidelity harness before more polish; the pipeline gets tracks (2026-10-25) [owner]
Context: The owner keeps finding gaps to Android 17 and iOS 27 on the S24 that review and QA passed (B-046's tile colour;
the active tiles' corners "called the same" in stage 8). The gates compare the work with the plan, and the plan comes from
the agents' own reading, so a wrong reading passes every gate. Four days into the pipeline 1 of 8 milestones is done and
motion slices run to 4-5 rounds (D-030, D-031, D-036).
Decision: (1) New milestone MF, Pixel first, because the emulator's stock shade is a live reference: anything that must
look or move like a reference is accepted by a diff under tolerance (`tools/fidelity/diff.py`), never by eye. (2) M1 is
BLOCKED until MF's baseline is done; B-037 pauses. (3) `/ship` has three tracks (fidelity, quick, full), a 2-round cap, and
architect, reviewer and QA default to Sonnet (Opus by override for crashes, privacy and architecture). (4) Agents may use
the S24 freely (frame stats, scenario scripts, screenshots), checking the focused window before each capture; the owner
says sensitive notifications show no previews. (5) iOS stays on the Simulator reference (`ios-reference/`). A GPU Mac
rented for one run of that suite, or a borrowed iPhone for scripted screen recordings, is the owner's call.
Alternatives considered: more rounds on the same gates (what the last days cost); reverse-engineering iOS (closed, legally
unclear, and the look is GPU tuning, not readable code); Microsoft's remote iOS simulator for Windows (needs a paired Mac).
Consequences / revisit when: if MF's baseline shows the diff cannot tell real gaps from noise (a font the emulator lacks,
blur, antialiasing), settle masks and tolerances before anything else. CLAUDE.md's size (121 KB, loaded by every agent) is
a separate open decision.

### D-038: Fidelity captures use one font on both sides; goldens live in the repo (2026-10-26) [owner]
Context: MF slice 1. The Android 17 emulator has no Google Sans (stock draws Roboto, ours Google Sans Flex), so words would
flood the diff; golden captures need a home (~400 KB a PNG, ~20 MB for the full matrix, recaptured per Android build).
Decision: (1) For captures only, ours draws the system font (`sys.font.text`/`sys.font.display` = `system` through a
temporary design override, restored after); positions, sizes, colours and truncation are compared, and the few styles whose
weight comes from a font-family name are masked. Google Sans Flex itself is judged on a real Pixel or the S24. (2) Goldens
live in the repo: `tools/fidelity/golden/<android-build>/<theme>/<state>.png` with a manifest, PNGs optimised.
Alternatives considered: masking every text box; installing Google Sans (user build, no root); an orphan branch, release
assets, Git LFS.
Consequences / revisit when: history grows ~20 MB per recapture; revisit if recaptures become frequent.

### D-039: How the fidelity harness captures (2026-10-26)
Context: MF slice 1's plan (docs/plans/MF_FIDELITY.md).
Decision: The harness fixes the real device state on both sides (UTC, 24 h, the clock at 2026-11-02 10:00 UTC, battery
unplugged at 100 %, brightness 127, DND off) rather than demo mode, which ours ignores (used only if a probe shows a need);
both sides post the one test notification through `TEST_NOTIFY` (same sender and icon, cancellable); states are reached
with `cmd statusbar expand-*` and our TEST_SHADE hooks at rest, no touches (held touches as the fallback); slice 1's tile is
Auto-rotate (`accelerometer_rotation`; Flashlight is unavailable on the emulator, DND would hide the notification); sides
switch by the accessibility list with the prior state saved and restored on every exit; the gate is 3 in-session captures
plus a cross-session pair with `diff.py`'s defaults and a headroom of overall mean <= 0.2, worst cell <= 0.5.
Alternatives considered: `cmd notification post` on stock (no cancel, another sender); held touches as the first method.
Addendum (2026-10-26, from slice 1's probes): the device's time zone is not changed (setting it posts a system notification
a shell cannot cancel, its age text following the faked clock); the clock is set so the device's own zone reads 10:00 on
2026-11-02. Ours' Quick Settings tile list lives in shared preferences that adb cannot read or restore, so slice 1 leaves it
untouched and sets only stock's (the lists differ until a later slice can drive ours'). Battery `set status 3` is added so
ours shows no charging bolt. Ours' font and colour overrides go in `design/user-<theme>.json` (user edits are per theme).

### D-040: MF slice 1's harness details and a third round (2026-10-26)
Decision: The emulator's modem is set unregistered for a capture run (its signal bars swing on their own every ~90 s; demo
mode does not reach ours), restored after. The test notification is posted at 10:00:00 and the clock held at 10:00:45, so
SystemUI's 30 s "recently alerted" bell is gone in every capture while the time still reads 10:00 and "now". A restore
counts only when our gesture strip is really back (B-018). The fake-device test scales its polls (`POLL_SCALE`, 0.1 in the
test, 1 for real runs). The owner allowed a third round (2026-10-26) limited to the fake test's flakiness under load.

### D-041: MF slice 2's mechanics (2026-10-26)
Decision: (1) Ours' Quick Settings tile list is read and set over adb as a virtual `qs_tiles.txt` in DevFiles' export and
import (works with the accessibility service off; "restored exactly" is a cmp of two exports), with the existing hook's
grammar. (2) Goldens go under `tools/fidelity/golden/<ro.build.id>/`, optimised losslessly with Pillow only, identified by
the md5 of the decoded pixels; a new Android build gets a new folder. (3) The fixture difference that comes from our app
being alive (stock lists our "Launcher safety" foreground notification and the system's overlay notice in its Silent
section, and an "N apps active" footer; ours hides its own overlay notice by design) is not removed: those rows lie below
the fixture card, so slice 3 masks the stock-only Silent tail and the footer by rules from stock's dump, each named in the
baseline, never as a silent mask. (4) `uiautomator dump` runs only on the stock side (our service off). (5) Text whose weight
comes from a font-family name is masked per text node with a reason in `mask-rules.json` (D-038).
Alternatives considered: a new broadcast for the tile list; stopping our foreground service for captures (removes a safety
net); masking whole rows above the fixture.
Addendum to D-041 (2026-10-26, slice 2's probes): on this emulator ours lists our "Launcher safety" notification too, so
the Silent tail is not stock-only; slice 3's mask rules start from what both sides show. Google Play's "Play Protect is
turned on" notification arrives ~25 minutes after a boot and changes the Silent section: a capture fails if the
notification set changes mid-run, and every capture and golden records its notification set, so ours and the golden are
compared only with the same set.

### D-042: The Pixel shade's text takes its font from the theme (2026-10-26) [owner]
Context: MF slice 3's first baseline: the capture's system-font override (D-038) did not reach most of the Pixel shade's
text, whose styles name Google Sans Flex directly, so glyphs and widths differed from stock's Roboto and inflated every
score (B-060).
Decision: The Pixel shade's text styles read their family from the theme's font tokens, so the capture-only system font,
and a user's or theme's font, applies to all of its text. Then baseline 2.
Alternatives considered: masking every text box (over the 6 % cap, blind to text size, colour and truncation); accepting
the noise.

### D-043: The fidelity harness snoozes other apps' notifications for a run (2026-10-27)
Context: B-062. The goldens' notification set held Google Play's "Play Protect is turned on" notification, which arrives
~25 min after some boots and never after others, so the baseline gate could not pass and the quick state was not comparable.
Decision: For a run, both sides snooze every notification record not ours (`cmd notification snooze`, after the clock is
set, 6 h bound) and unsnooze them explicitly afterwards (verified; dirty kept otherwise); a sweep at each state's start; a
record arriving during a state still fails it. Goldens, verify and the baseline gate must hold no other app's package. The
committed golden is replaced by a recapture (the old one stays in git history; its facts in docs/FIDELITY_BASELINE.md).
Supersedes the part of D-041's addendum that recorded Play Protect as part of the set.
Alternatives considered: cancelling (no shell route; Play reposts); recapturing without it (it can still arrive mid-run);
keeping both goldens per build.
Addendum to D-043 (2026-10-27, probe l): `cmd notification unsnooze` from the shell is refused and drops the record, so the
harness never calls it; snoozed records are brought back by moving the faked clock past their `until` (the system reposts
them, same key), before the real clock is restored; snoozes last 1 h; the jump is capped at the faked base + 3 h; a pending
snooze that is not ours makes a run refuse. A snoozed key whose `until` was never read and that is not posted again at
the end is a WARNING that keeps the dirty marker (B-062 round 2): `--restore` repeats it, on purpose, until the
notifications are checked by hand and `.prior/dirty` is removed; a key with a known `until` that is gone after the jump is
a NOTE (its app cancelled it).
Addendum to D-041 (2026-10-27, B-062 part 2): the recaptured golden's stock footer lists the same "1 app is active" pill
ours does (the 2026-10-26 golden had none: stock's pill depends on the emulator's session), so the
`foreground-service-pill` mask rule is off and the pill is compared (masks only where the sides genuinely differ). The rule
stays in mask-rules.json, off, with its why: turn it on again for a golden whose stock footer has no pill. B-058 now covers
how the two pills differ.

### D-044: Back to the multi-agent workflow; models fixed; plain-language updates (2026-10-27) [owner]
The owner tried solo mode for a day and went back: the problem was not the agents but that their work was invisible
(reasoning in files, only code and decisions in the chat). Restored: tracks (fidelity, quick, full), 2-round cap.
Models: architect and implementer on Opus (they decide and build); reviewer and qa-tester on Sonnet (they check a diff
against written criteria), Opus override for crash, privacy and architecture work. This replaces the Sonnet defaults in
D-037. New rule in OPERATING_RULES, "Keep the owner informed": one plain line before and after each stage, a 2-line plain
summary at the top of every agent report, a 10-line end-of-task report, a 5-line answer to "what is happening".

### D-045: Text weight is judged by stem width, and View text by its resource chain (2026-10-27)
Context: B-061. Baseline 2 showed our Pixel header's clock and carrier text looking heavier than Android 17's on the
emulator; by eye, every 500 and 600 in the Pixel theme was in doubt.
Decision: Text weight is judged by the median stem width (`tools/fidelity/stems.py`) of the same box on both captures, not
by eye. Only Compose-drawn text is representative on the emulator (it asks the default family, variable Roboto, for the
weight); View text whose weight comes from a family name falls back to Roboto Regular there (the emulator has no
`variable-*` or Google Sans family), so it is judged from its resource chain in SystemUI's APK: a regular family with no
weight is 400, a medium or emphasized family is 500. Outcome: the clock, date, tile label and tile detail match within
0.11 px; the carrier's chain ends in "variable-body-medium-emphasized" (500 on a Pixel, inferred from the name, not measured on one), so `comp.qs.header.carrier` stays
500 and its ~1 px stem gap is recorded as an emulator artefact (docs/SHADE_PIXEL_EVALUATION.md, "B-061";
docs/FIDELITY_BASELINE.md). `pixel.json` unchanged.
Alternatives considered: lowering the carrier to 400 to match the emulator's picture (wrong on a real Pixel); masking the
carrier (its words still differ, B-054).

### D-046: The harness records the torch per capture and per golden; the baseline compares only equal ones (2026-10-28)
Context: B-063. Ours' Flashlight tile is there or not by whether the emulator's boot reports a camera with a flash unit
(baseline 2's boot: none, the tile hidden; the boot of 2026-10-28: `dumpsys media.camera` gives the front camera a flash,
the back none). Ours takes any camera with a flash (`Controls.kt`, B-065), Android's FlashlightController the back camera
only, so the same app on two boots gave other expanded pictures (+0.21 on the light means, B-061's re-run).
Decision: capture.sh records per capture which camera facings report a flash unit (`torch`, from `dumpsys media.camera`, a
read: `front`, `back,front`, `none`; `unknown` and a change during the run are warnings and are recorded as such); golden.sh
copies it into the golden's manifest and refuses a missing or invalid one; baseline.sh compares ours with the golden only
when both record the same valid torch, with no override (exit 1, no table, remedy named: a cold boot until the dump
matches, or a golden recaptured on this boot). The committed golden (`CP41.260828.004.A7`) is backfilled `none` from
evidence (the same boot as baseline 2's ours, which hid the tile), metadata only: its pictures, md5s and total untouched.
A boot with another value blocks the baseline until a cold boot matches or the golden is recaptured; revisit if that is
frequent (B-065 removes the cause on ours' side).
Alternatives considered: masking the tile (blind to B-059); a golden per torch state; gating on the back camera only
(encodes the apps' code into the harness).

### D-047: The accuracy audit's order, and a moment for switches and sliders (2026-10-28) [owner]
After the 2026-10-28 accuracy audit (docs/audit/2026-10-28/, B-066 to B-138) the owner chose its suggested order: 1 B-037 step 2
(Notification Center's commit and close), 2 the squash-and-stretch primitive (B-116), then glass that reacts to touch (B-066) and
the menu popping out of its icon (B-094), 3 switches and sliders for both themes (B-092 first, then B-083, B-085, B-082), 4 Control
Center's pages (B-067, after a capture), 5 home folders (B-093), 6 gestures (B-107 to B-110) and the status bar's capsules (B-112),
7 the Pixel shade's own motion (B-127 to B-129). The new controls' motion roles go in a new moment, "Switches and sliders" (with
its own Speed and Bounce), used wherever such controls are drawn (the settings, Control Center, Android's shade); not in
"Settings app". B-037 step 2 resumes from its parked branch (D-037 had paused it for the fidelity harness).

### D-048: B-037 step 2's pipeline and choices (2026-10-28)
Full pipeline with measured numbers as acceptance criteria (iOS has no live reference, D-037); `diff.py` compares ours
against ours only; reviewer and QA on Opus (render nodes, cross-thread pictures). On commit what was behind is drawn
inside the sheet as a picture (home's content Picture zoomed, a fresh app snapshot faded, else today's content fade),
rendered during the hold, never in the commit's first frame; `motion.nc.reveal` retimed to pass 11 and `motion.nc.open`
fitted to the measured landing (both judged before); the dim below the edge holds through the landing (reverses slice
1b's clearing on commit); a catch during the zoom lets it finish. The close reuses `comp.nc.glass.dim`; until 2c2/2c3 the
cover fades out on `motion.nc.close-uncover`. Owner (2026-10-28): over the lock screen One UI's lock screen shows live
below the closing sheet, dimmed 30 % and clearing as it leaves; 2c1 ships alone with the stand-in fade.
Plan: docs/plans/B-037-step2.md.

### D-049: B-037 slice 2a2's criteria corrected by the capture (2026-10-28)
AC2: the plan asked `motion.nc.open` to cover 90 % of the landing in 115 +- 30 ms; pass 10's frames (test81, rows 563-572 of
video_times.txt: edge 655 to 861 pt over 0-232 ms) reach 90 % only between 188 and 222 ms (67 % at ~120 ms): the plan's
"~115 ms" described the bulk of the slide. The role is fitted to the frames (critically damped, 0.33 s, from a standstill:
the drag held 1.5 s before lifting; rms ~4-5 pt, re-checked by the reviewer) and AC2 now reads "matches pass 10's landing,
rms < 6 pt, 90 % in 170-240 ms". AC3: the last held frame against the first frame after the commit differs only at
anti-aliased edges and rims (home's recorded content drawn in a transparent layer: worst cell 4.4 grey levels, mean 0.51),
steady and inside a zoom that is half faded by 62 ms; waived while it stays edge-only (re-measured after round 2), because
drawing without the layer would render the Picture again at every frame of the zoom. AC6: an app's snapshot is used when it
has the shade's orientation (a turned app under a turned shade), the fallback fade otherwise. Alternatives: holding the
plan's 115 ms (contradicts the reference); drawing the picture without a layer (a render per frame).

### D-050: B-037 slice 2a2, a third round and two rulings (2026-10-29)

Decided by the orchestrator, the third round by the owner. QA failed round 2 (home's glass clock missing from the reveal's
first frame after a theme change: home's picture had been recorded mid-reveal; the settings sample played the arrival). At
the two-round limit the owner chose a third round (2026-10-29). Rulings in it: (1) home's picture zooms only while it is
exactly what home shows (recorded at rest, nothing drawn since: `HomeBridge.shown`); otherwise the release takes the plan's
fallback, the content fading in (AC6's path), so a minute tick or a widget update during a hold gives a fade rather than a
wrong picture. (2) The list's dim over the sheet's wallpaper fades in with the reveal instead of stepping in at the commit
(49 grey levels in one frame over home with a notification): iOS's capture (pass 10 test81) had an empty list and cannot
say; before round 2 the dim faded in with the content, and "nothing pops" decides. Alternatives: a fresh picture taken at
the commit (a recording in the commit's first frame, the cost the plan forbids); the dim at once (unmeasured, a visible
step).

### D-051: B-037 slice 2c1, the close's deviations and its spring (2026-10-29)

Decided by the orchestrator after review (round 1 REVISE). (1) The close's drift moves the sheet's wallpaper only (`bgNode`,
recorded with its top band mirrored above the screen), not the list or the clock: iOS's capture shows the wallpaper drifting,
and the notifications moving with the edge is slice 2c1b; the platters' frost follows the drift so no seam shows. The drift is
measured from where the edge was when the close began. (2) A close from a landed sheet (or a drag up that started from rest)
shows the cover below the edge and its dim whole at once: at the screen's bottom they lie under the sheet's corners, and a
fade let the live dock show under the rising edge; from a sheet still landing they come in on `motion.nc.pull-dim-home` as
planned. (3) The lens bands carry the sheet's own darkness (dark mode and the list's overlay, `wallDarkness()`), also at the
commit's landing (a light frame ran round a darkened sheet). (4) The status bar returns on `motion.nc.bar-fade` after a close.
(5) Going under Control Center is not a close: Notification Center falls back as before and no cover shows under another
panel. (6) `motion.nc.close` (judged, 0.38 s) stays as it is: pass 11 test84 cannot fit it (the capture drops the frames 
of the rise: at most 2-3 edge points over ~100 pt), and computed with the shipped lift the numerals stay above the edge 
all the way (closest ~19 pt at ~240 ms; only the glass's shadow is clipped between ~200 and ~300 ms; the round-1 
report of a cut at ~150 ms was an estimate). Alternatives: the whole content layer drifting as the plan first said (a gap at the top, the clock
cut); the close's spring as a follow-up (a visible cut shipped).

### D-053: Back to a single session (2026-10-30) [owner]
The owner moved back to one Opus session talking directly with him. Measured over Oct 22-30 against Oct 3-21: product commits
fell from about 16 a day to about 3, review rounds ran past the cap (B-034 five, B-037 slices three to four), and every
agent started by reading about 120 KB of roadmap and decision log. Removed: the subagents and the /ship, /fix, /audit,
/progress and /kickoff commands (moved to .claude/_disabled/, so they can come back). Kept: scripts/check.sh and its Stop
hook, tools/fidelity, "Whole elements, not checklists", and a plain-words update in the chat at each step.
Supersedes D-037's tracks and D-044.

### D-054: The Stop hook runs the fast check and skips while a check runs (2026-10-30) [owner]
The Stop hook ran the full `scripts/check.sh` (now 16-47 min) after every turn that left code changed; its 600 s timeout
ended the hook but not the check, so orphaned checks piled up beside each other and slowed every later one (five at once on
2026-10-30). Now the hook runs `CHECK_FAST=1` (build, lint, unit tests: seconds when up to date, ~5 min after a change) and
skips while another check runs (each check leaves its pid in `$TMPDIR/launcher-check.lock`, a dead one's file is cleared).
The full check, with the shell tests, runs before every push.

### D-055: Notification Center's close over home ends in home's arrival (2026-10-30) [owner: artistic freedom]
The owner (S24): the close "works well but doesn't feel particularly good", and gave artistic freedom within the iOS look.
The flat moment was 2c1's stand-in: the sheet lifted off a dimmed still wallpaper that then faded into a static home. On iOS
the cover sheet going up is how home arrives (as after an unlock). Now a close over home (from a landed sheet, home at rest,
not the lock screen) holds home's arrival's first frame under the cover, and once the edge has risen past
`comp.nc.close.arrival-at` (0.3 of the screen, judged) home plays its unlock arrival (`HomeBridge.PanelArrival`) while the
cover and its dim dissolve on `motion.nc.close-uncover`; a close taken back lets home go back to rest unseen; home busy (a
menu, the App Library, a card) keeps the old fade. Over apps the stand-in stays until 2c3.

### D-056: Control Center unfolds from its corner every time (2026-10-30) [owner]
The owner: the pull "feels a bit weird: sometimes the elements just fade in and glide from the top but other times the proper
animation, elements flowing from the top right, happens". Cause: the corner fold was a closing-only state (`closeK`, on
`motion.cc.close-style`), cleared at once only when a close finished; a pull that caught a closing panel, or a drag that
turned, opened with the fold still on (the corner flow), an ordinary pull without it (fade and glide). The owner calls the
corner the proper motion: the controls now unfold out of the corner Control Center is pulled from as it opens and fold back
into it as it closes, on the panel's own progress (the nearest first in, last out; the pull's glide stays), every time. The
toggle and its role `motion.cc.close-style` ("Closing choreography", which no longer moved anything) are removed from the
code, the presets, Moments and the samples.

### D-057: Notification Center over a live blur (2026-10-30) [owner]
The owner: the background behind Notification Center should be live, blurred as under Control Center (users' own
backgrounds, with a transition, come later with the token editor). A theme chooses it: `comp.nc.background` "live" (iOS 27's
default now) or "wallpaper" (iOS 27's lock-screen wallpaper with B-037's clear slab, lens, picture reveal and cover, kept
whole); `comp.nc.background.material` aliases the shared panel material (Control Center's). Owner's answers: the blur grows
with the pull as under Control Center (the clear slab goes in live mode), and live over the lock screen too. In live mode
the sheet draws no wallpaper; its glass, glass clock and status-bar tone refract what was behind at the pull, blurred and
veiled as the panel's background (the picture Control Center's glass samples, made a `Wallpaper` off the UI threads,
`Wallpaper.of`); the content comes in with the pull, the clock still drops at the commit and lifts at the close, and home's
arrival after a close eases into its first frame (nothing covers home there).

## D-058: Notification Center over a live blur keeps its glass sheet (2026-10-30)

The owner, on D-057's build: "where's the sheet of glass for the notification panel? it should still be there with the
blur". D-057 had dropped the sheet (no clear slab, edge, lens, reveal or drift; the content faded in over a blur growing
with the pull). Now the live background changes only what the sheet shows: the same sheet as with the wallpaper (the clear
slab a finger holds, its edge and lens, the commit's reveal, the close's drift, cover and home's arrival), showing what was
behind at the pull, blurred and veiled as the panel's background, in place of the wallpaper. Once it has landed the live
blur under the shade takes over from that picture (`ncLiveK`, in on `motion.nc.reveal`: the picture fades as the blur
comes), so what is behind moves on; a close brings the picture back over it (`motion.nc.date-out`) and below the rising
edge what is behind is sharp. With no picture ready at the commit, the blur comes in with the content instead (logged).
The wallpaper below the edge (the lock screen's, the close's cover) stays the phone's own. Checked on the emulator: a held
pull, landing, rest and a close mid-way.

## D-059: Notification Center's live sheet: its own blur, live edges, the glass's frost (2026-10-31)

The owner, on D-058's build: the blur too strong, a flicker as the pull ends (the sheet's picture handing over to the live
blur), the edges static while the rest is live, and the wallpaper under a closing sheet ("doesn't fit at all"); find a way
to refract the live blur, and match Control Center's and Notification Center's blur to the liquid glass.
- **Blur strength**: the shared panel material (`component.panel.material`, Control Center's, Notification Center's and
  Android's shade's in iOS 27) takes the liquid glass's frost, 16 pt (`ref.material.liquid-glass.regular`), over the kit's
  black 50 % veil; the kit's 24 pt was too strong next to the glass.
- **One blur, the sheet's own (One UI)**: Samsung's view blur on a view of the shade's own window under the sheet
  (`shade/nc/NcSheetBlur`), cut to the sheet's outline and moved with its edge by translation in the same frame (one window,
  one thread), with the panel's veil drawn there. It comes in with the content at the commit (`motion.nc.reveal`), rises
  with the sheet as it closes, and goes at rest. No picture hands over to a blur (the flicker), no picture zooms or fades in
  the sheet (`ncPic` is not used: two copies of what is behind would show), no wallpaper cover below a closing sheet and no
  home arrival (home is under the sheet all along). Elsewhere (Android's blur behind the shade's window cannot be cut to a
  moving outline) D-058's handover stays, without the cover.
- **Live edges over apps**: while a finger holds the pull over an app, the edge's bands bend the app's screen live: the shell
  captures the display with only that app's layers (`IWindowManager.captureDisplay`, `CaptureArgs.setUid`: none of our
  windows nor the bars), half size, as often as the frames are taken up (`NcLiveBehind`, shell `captureUnder`). Secure
  content ends the stream (the kept picture stays). Over home the bands bend home's exact picture (home is still under a
  pull); after the commit and through the close they bend the sheet's picture, what was behind blurred and veiled.
  Leaving our windows out by their layers is not possible from the app (`ViewRootImpl.getSurfaceControl` and its field are
  blocked hidden APIs on Android 17), hence the uid route, which leaves home out.
Measured: S24, a capture of the app alone 8 ms median (120 in 1.1 s, at 0.25, 0.5 or full size alike); captures every
frame added no missed refreshes to Control Center's opening (2 in 4 runs with, 2 in 4 without, twice); the live stream ran
81 frames a second over the stopwatch; a held pull with it drew 90 frames in 0.9 s with 2 missed refreshes (to look into).
Emulator: the bands follow the running stopwatch; the live sheet's fallback at rest, mid-close (the app below, dimmed, no
wallpaper) and closed. Not seen: the One UI sheet blur itself (Notification Center is never captured on the S24).
Follow-up (2026-10-31): handed to the bands as they came, every live capture stayed alive behind the shader that had sampled
it until a garbage collection (Android does not count GPU images towards one): 3 GB of graphics after five held pulls on the
S24, and lmkd killed the app. Each capture is now copied on the GPU into one of three buffers made once and let go at once;
the bands keep a shader per buffer. Ten held pulls: graphics 154 -> 225 MB, 186 MB once settled. Checked on the S24 with
recordings (the owner allows captures of Notification Center from today): over an app and over home, the clear slab, the
blur coming in under the content, rest, and the close with what is behind sharp below the rising edge, no wallpaper; the
sheet's brightness steady from rest through the close (77.8 -> 74 of 255), no step after the landing. Frame stats
(`scenario_shade.sh`, 3 runs): Notification Center open missed 2, 1, 0 refreshes (GPU p90 ~10 ms), close 0, 0, 0.

## D-060: Notification Center's live sheet: one picture for the bands, the clear glass's blur, no held dim (2026-11-01)

Context: the owner on the S24 (D-059's build): the refracted border stood out during the pull and the close, the blur had
barely changed, the notifications sometimes showed the wallpaper over an app, the dim held until the close ended, and the
stretch and squash could not be seen.
Decision:
- **The bands bend what the sheet shows (One UI)**: the live capture of the app goes on after the commit and comes back for
  the close, each copy blurred (a Gaussian matched to Samsung's radius, `SamsungBlur.RADIUS_PER_SIGMA`) and veiled as the
  sheet is at that frame (`NcLiveBehind.look`); it ends once the sheet has landed. Bending a sharp or a still picture against
  a live blur, the band read as a strip of its own; now only the bend differs, and the stretch and the mirrored copy show.
- **Nothing drifts over a live blur**: the close's wallpaper drift (iOS's, for a wallpaper sheet) is 0 when the blur is the
  sheet's own: the bands read their picture where the wallpaper would have drifted to, a dark strip growing above the edge.
- **The panel blur at the clear glass's frost**: `component.panel.material` frost 6 pt (`ref.material.liquid-glass.clear`,
  the glass of the notifications and controls on it), Control Center's and Notification Center's alike; veil unchanged.
- **The dim below a closing sheet leaves with it over a live blur** (`NcClose.lockDim`, as over the lock screen): below is the
  app or home itself, never held dimmed until the close ends.
- **The app behind is never SystemUI's window** (`shade/AppBehind`): the last window event was the status bar's, no task was
  found, no picture made, and the notifications' glass fell back to the wallpaper.
Measured (S24, over Clock, TEST_SHADE pull and release, an `input swipe` close): the band continues the interior with no
step; frame stats over home (`scenario_shade.sh`, 3 runs) Notification Center open 0, 0, 0, close 0, 0, 0 missed refreshes;
over Clock release 2, 1, 2 and close 1, 1, 1 (the close's miss is always its last frame: the shade's thread waits on the
render thread all our windows share, a window's new surface and the gesture strip's frame; not the stream: capping it at 60
copies a second changed nothing, 2, 0, 1 and 1, 1, 2, and was taken out). The previous build over Clock could not be read
(its shade window was not in gfxinfo after the reinstall), so there is no before/after over an app.
Consequences / revisit when: the close's last frame and the release's first frames over an app still miss a refresh (to
trace); over home the bands still bend the sheet's still picture (home cannot be captured without our own windows).

## D-061: Code review of the live sheet: ten fixes (2026-11-01)

Context: `/code-review` of `fix/nc-live-sheet` (D-057 to D-060) found ten problems; the owner said go for all of them.
Decision:
- **No GPU readback for what is behind**: `Wallpaper.of` turns a hardware picture (BlurBaker's) into pixels with
  `ViewPicture.softCopy` (drawn into a CPU-readable buffer, its end waited for on the worker); `copy(ARGB_8888)` read it back
  on the render thread every window of ours shares, at every live Notification Center opening, mid-pull.
- **The live stream's buffers go exactly once** (`shade/nc/BufferSets`): a stream started again takes up the set it had if
  it fits; a set of another size replaces it and the old one goes once the new stream's first frame is with the bands. A
  stream started again used to make a new set and drop the old one unclosed (3 GPU buffers each time).
- **A copy that outlives its caller's 1 s wait owns its capture** (`RenderLease`), and a buffer is closed only once its last
  copy has ended (5 s, else left open: a leak, never the B-031 abort).
- **A panel leaving under another leaves the live blur and the backdrop to it** (`Shade.blurTakenBy`, `backdropTakenBy`),
  every panel alike: Notification Center's clean-up cleared them under an opening Control Center.
- **What is behind, made after the commit, goes to the edge's bands too** (they bent the sharp picture against the blurred
  sheet); made while the live blur still fades in, it waits for that fade's rest (the sheet's picture would come in part-way
  at once), and it is dropped if the fade rests at 0 (a close).
- **The live sheet without its picture draws no background** (the live blur under the shade is it): the phone's wallpaper
  stood in and faded back in over an app at a close.
- **Frosts made anew only for another picture**, and letting go of what was behind at a close takes nothing up (a frost and
  numeral rebuild at every close and opening before).
- **Home's arrival under a closing panel plays only if it was held**, held at once again (D-055; D-057's easing was for a
  live sheet, which no longer holds it).
- **Workers that end when idle** (`design/IdleWorker`) for the shade's two: a shade made anew at a service restart left its
  threads, and itself through them, behind. Not shut down: a GPU callback on its way must never be refused.
- `ShellCapture.probe` uses the one cached set of reflected calls.
Measured: unit tests (`BufferSetsTest`, `IdleWorkerTest`); emulator and S24 checks in the report of that day.

## D-062: Panel transitions as Figma connections with keyframes, inside MS (2026-11-01) [owner]

Context: the owner found Notification Center's live sheet fading in at the commit, where it had arrived whole as its edge
landed: D-059 changed that for a technical reason that was logged but never put to him. He asked for the choice to be the
user's, modelled on Figma: "home and notification screens are like 2 frames in figma and the user can decide the
transition", and for every property to be keyed between states "kinda like using keyframes in a video editor".
Decision (his answers): panels first (Notification Center, Control Center, Android's shade), then apps and the rest in the
same model; following the finger or playing on release is the user's choice per transition; iOS 27's choreography is one
set of keyframe tracks, not special code; look values a share of the theme's value by default, absolute by a switch per
keyframe; Smart Animate in a later round; the work sits inside milestone MS. Plan: `docs/PLAN_PANEL_TRANSITIONS.md`.
Also: a change to what the owner sees is asked first, never only logged (D-059's fade is what that cost).
Consequences: every panel's hard-coded mapping from progress to look moves into tracks read from the motion preset; the
first build step must reproduce today's frames exactly before anything new is offered.

## D-063: Panel transitions, step 1: the engine's choices (2026-11-01)

Context: step 1 of `docs/PLAN_PANEL_TRANSITIONS.md` (D-062): the engine, pure Kotlin, nothing on screen yet.
Decision:
- **Direction is Figma's arrow** (which way things travel): Move in DOWN comes from the top moving down, Move out DOWN sends
  what is behind down, Push DOWN moves both down. One meaning for every type.
- **A Figma type's tracks are linear in t**; the easing is the motion role's (the finger, or the role's spring or curve after
  a release), as Figma puts the easing on the transition as a whole. Keyframes of one's own carry their own easing to the
  next keyframe: linear, hold (cut at the end), jump (cut at the start), the CSS beziers Figma names (ease in/out/in-out and
  their "back" forms) or one's own, and a power (exact square roots and quadratics, which today's code uses).
- **Slide in and out move the frame that makes way by 30 % of the screen** (`TransitionTypes.SLIDE_PARALLAX`, judged:
  Figma does not publish its number).
- **Beyond 0 or 1** (a spring overshooting) the end segment goes on evenly, so a panel's bounce shows; every property has
  bounds (opacity 0..1), an overshoot included.
- **Out-types draw what is behind over the panel** (`Transition.behindOver`), kept by a custom copy of them.
- **The default close is the opening reversed** (the way it came, with each easing mirrored); Instant stays instant.
- **File format**: `{"transition": {...}}` in a preset (`TransitionJson`): a Figma type by name alone, a custom one with its
  tracks, `{"reverse": true}` for a close that reverses the opening. A look's keyframe is a share of the theme's value
  unless `"abs": true`.
Measured: 28 unit tests (`motion/transition/`), two of which fail when the reversal's mirroring is removed.

## D-064: Panel transitions, step 2a: tracks with clocks of their own, a player per panel (2026-11-01) [owner]

Context: step 2 (Notification Center on tracks, frame for frame as today) found that iOS 27's release starts several motions
on separate clocks at once (the edge's spring from the finger, the content's 200 ms fade, home's icons' 90 ms zoom, the
clock's own spring, the bar's 200 ms fade, the dim that clears 180 ms after the landing): one timeline per transition cannot
reproduce it. The owner chose to extend the engine ("as in a video editor") over swapping the steps or an approximation.
Decision:
- **A track runs on the connection's progress or on a clock of its own** (`TrackTiming.Clock`: a motion role, a delay, a cue:
  the connection's start or the panel's landing). The role keeps the moment's Speed and Bounce.
- **Tracks for the parts that move on their own**: the dim below the edge, the still picture of what was behind (opacity,
  zoom), the clock (position, opacity), the date, the status bar. A layout without a part ignores its tracks.
- **`PanelPlayer`**: open while pulled, release laid over open once let go open (the edge goes on landing on the panel's
  progress), close at 1 - p; a transition driven on release runs on the panel's own spring from the release.
- **Handover without a jump**: a connection taking over mid-way compares itself with the one before at the same moment (the
  last frame's progress and time), keeps the difference per property and lets it go as its own progress there reaches its
  end. Identical to today's motion on uninterrupted paths; interrupted ones start from what showed (their speed may differ
  from today's, where each value kept its velocity).
Measured: 38 unit tests in `motion/transition/` (10 for the player); removing the handover fails two of them.

## D-065: Panel transitions, step 2b: what iOS 27's Notification Center needs of the engine, and the reference (2026-11-01)

Context: reading today's Notification Center channel by channel (step 2b) before moving it onto tracks.
Decision:
- **An exact reference before the switch**: `NcTrace` (what is drawn at every frame), in-app scenarios on frame counts
  (`TEST_SHADE nc-scenario`), motion on a fixed step a frame while tracing (`MotionClock`, the thread under test only; real
  time otherwise). Two runs read the same to 0.0000; noise came from three places, each fixed: wall-clock starts and held
  slow frames (the fixed step), other threads' frames moving the clock (the owner thread), and sampling order within a
  frame (sampled in the commit phase, after the animations).
- **The engine gains** what today's motion does: a clock's first keyframe can start from the value showing
  (`"v": "current"`, as an animation starts from where it is); a track laid over another counts from its clock's start; a
  fallback laid over the opening; a layer after the close (`afterClose`, started when the panel is gone: the bar comes
  back); a cue for the panel's end; `busy` (frames are asked for while a clock runs); relative progress (the close's dim:
  whole at its start, clearing with the edge from where the close began; exempt from the handover, it starts where it
  says). The clock's position is two tracks, a share of each theme distance (`clock.drop`, `clock.lift`).
- **2b moves five channels**: the content's reveal, the dim below the edge, the status bar, the clock's drop and lift, the
  date. The live blur's handover, the wallpaper cover and the picture giving way stay as they are until step 3: they
  depend on whether a picture of what is behind exists yet and on the theme's background, and step 3 builds their renderer.
- **Found in the reference**: a close over a live blur ends with the dim below the edge snapping from ~0 to whole and fading
  over 250 ms (the whole app darkens a moment after the sheet has gone; D-060 meant it to leave with the sheet). Reproduced
  as it is in 2b, to be fixed after it as its own change (in the tracks: start that fade from the value showing).

## D-066: Panel transitions, step 2b: Notification Center on keyframe tracks (2026-11-01)

Decision: Notification Center's content reveal, dim below the edge, status bar, clock drop and lift, and date run on
`PanelPlayer` with iOS 27's tracks written into `assets/motion/ios27.json` (`motion.transition.nc.*`: open by place, release,
fallback, close by background and place, after-close by place; named in `MotionRoles.TRANSITIONS`). The shade picks the most
specific variant the preset has at each opening (logged once: "[nc] transitions (place, background): ..."). Their six
MotionValues and the clock's three are gone; "has it arrived" is a flag (`ncCommitted`). The player's time is the motion
clock's in nanoseconds (whole milliseconds put a 60 ms fade a third of a millisecond out).
Measured (emulator, `tools/nc_trace.sh` against today's reference, tolerance 0.001): over an app, open-close 0.0002 and
fallback 0.0002; over home, fallback 0.0009 and open-close identical in every moved channel. The close that interrupts the
reveal 6 frames after the release differs for ~100 ms (content up to 0.039, date 0.003): today's reveal went on on its own
curve, the tracks start again from where it is at the speed it had (the speed is the clock's own, exact; estimated from the
frames it was 0.068). The live blur over home differed between the reference run and this one: it is not moved (step 3) and
takes one branch or the other by whether the blurred picture of what is behind was ready at the commit.
Fixed on the way, both found by the comparison: before its first frame nothing had shown, and a track starting from what
shows read an empty value (the bar dropped to 0 in one frame at the first pull over home after a start: now everything is
at rest then); the background was read before the panel's opening had read it for this opening (the first pull chose the
wallpaper's close, and its dim rose as the sheet left).
Known: on the wallpaper sheet over home, home's arrival starts what follows the close, so the bar comes back from there
rather than at the close's very end. Unit tests: 52 in `motion/transition/` (iOS 27's tracks from the preset over home and
over an app included).

## D-067: Direction change to the Not a Launcher engine (2026-11-03, owner)

Decision: the launcher becomes the reference implementation for a component engine ("Not a Launcher"): KDL components over
~40 engine primitives, data as subscribed streams behind capabilities, surfaces as mixable slots, three-tier tokens, the
existing keyframe transition engine, Lua workers for scripts, a creator store and a Compose Multiplatform editor. Spec:
`docs/ENGINE_SPEC.md`. Audit of this codebase against it: `docs/LAUNCHER_AUDIT.md`.
Consequences, owner-approved:
- Compose owns the tree (parse, hierarchy, layout, props, state); the engine owns the pixels (primitives draw into
  RenderNodes it controls; gesture-driven motion bypasses Compose state). Reason: the audit found every surface is
  hand-drawn and that per-frame Compose drawing cost frames in four measured ways.
- The frame budget is a bound, not zero: ≤1 dropped frame per 100 on a pull at 120 Hz on the reference device, idle
  < 1 ms/frame; store components < 2 ms render, < 16 MB. Owner: "minor performance cost is fine if it allows better
  versatility". This relaxes rule 1 ("not a single stutter") for the engine; the launcher on `main` keeps the old rule.
- Build order is shade-first. ~11k lines port whole (Shizuku service, safety, window plumbing, motion, transitions, tokens,
  glass renderer, data models), ~5.5k of logic is extracted from views, ~26k of views are rewritten as KDL components,
  ~2.9k dropped. The hand-drawn views are the acceptance tests for the primitives and are deleted only after parity.
- `main` is frozen as the last working launcher; all engine work is on `engine`. `docs/SPEC.md` is superseded on `engine`.
- The glass tiers are the proven four (in-app AGSL; `captureDisplay`+`setUid` live; Samsung/`FLAG_BLUR_BEHIND` window blur;
  static snapshot; opaque floor). The virtual-display mirror is a dead end (D-057-era probe: system crash).
Open, to be settled by measurement before step 2: Lua VM (LuaJ vs Luak/Rembulan), own KDL parser, Compose Multiplatform
glass on desktop, whether the Compose `deck` can render on gesture-nav's `HandlerThread`.

## D-068: Store model, tiers, licence, alpha gate, security and battery budgets (2026-11-03, owner)

Decision: the KWGT model is permanent, not a launch phase. Users buy one tier, Pro, as a subscription or a lifetime
licence through Play Billing; creators publish paid packages as their own data-only APKs on Play and are paid by Google;
the platform takes nothing from creator sales; our store page is a front that deep-links to Play. A package's tier is
computed from its `uses` block (free = no Shizuku, no overlay; Pro = anything touching system state or over other apps);
`optional` capabilities and `env.caps.<name>` let a package degrade. Paid packages are Play-only and verify the installer
and, once open source, the host's signing certificate.
Open source: FSL-1.1-Apache-2.0 for app and engine, MIT for format spec, CLI, validator, fixtures, VS Code extension and
AI skill; trademarks filed before the alpha. Public alpha after step 4 (text packages, no editor, no store), labelled 0.x.
Shizuku is not forked under its name. The thedjchi fork was reviewed 2026-11-03 and is recommended for the alpha (it
re-enables wireless debugging at boot with the WRITE_SECURE_SETTINGS grant, reconnects and starts the service); for 1.0
the same sequence is built into our app on Shizuku's Apache-2.0 ADB client. A one-tap re-pair flow and a paused state for
Pro surfaces either way. Security and battery budgets are exit tests (spec, "Exit tests added by these
decisions"). Ten additions from the tool survey (expression bindings, fixtures, state-keeping reload, rules, hero
transitions, token modes, snippets, lockfile and doctor, export/import, history) are in the spec with their steps.
This removes from the plan: the payout ledger, the 70/15/15 split, holds, withdrawals, payout provider, entity question.

## D-069: Step 0 measured: LuaJ, our own KDL parser, desktop glass yes, Compose deck only from 1.11 (2026-11-04)

The four questions D-067 left "to be settled by measurement before step 2", measured on the S24 and the desktop
(`docs/BENCH_STEP0.md`, harness in `bench/`, `bench-desktop/`, `bench-shared/`, built only with `-Pbench`).
Accepted by the owner (2026-11-04, "Accept all four"); the Compose (and Kotlin) version move and the spec example's fix
remain open:
- **Lua: LuaJ 3.0.1**, behind a one-adapter VM interface. Rembulan cannot run on ART (it defines JVM classes at run time);
  Luak (Kotlin port of LuaJ, alpha) measures the same as LuaJ. An event costs 0.8 µs; a VM ~65 KB.
- **KDL: our own parser**, spans on every node. kdl4j is fast enough (a 4644-line layout in 15 ms on the S24) but keeps no
  positions on nodes, its hybrid parser returned one node for an invalid file without an error, and its v2 parser accepts a
  stray `}`.
- **Glass on the desktop: yes.** The launcher's six shaders compile unchanged in Skiko 0.150.2 (Compose Multiplatform
  1.12.2) and draw as on the S24 (PSNR 55-65 dB; a few edge pixels differ). CPU raster only; the GPU backend unchecked.
- **Compose deck on the nav thread: possible from Compose 1.11.4**, not on the launcher's 1.7.5 (its accessibility check
  measures the view from the main looper while an accessibility service is on: a crash every run). With 1.11.4 no vsync
  was skipped, a blocked main thread did not matter, Compose layers cost ~1 ms a frame more than Views and engine-owned
  RenderNodes inside Compose cost the same as Views; the first composition in a process took 151 ms against 42 (warm it
  ahead). Moving the launcher's Compose (and likely Kotlin) is the owner's call.
Found on the way: the spec's `battery-ring` example is not valid KDL (Lua as children, `false` for `#false`).
Not measured: the Redmi (not connected), the screen's present times for the deck (step 5).

## D-070: E1 starts; two-agent mode; the capability registry leaves step 1 (2026-11-05)

Owner's instruction (2026-11-05): E1 is worked in a two-agent mode, this session deciding and reviewing, an Opus agent
carrying out each step, discussed until both are satisfied. This overrides OPERATING_RULES' "no subagents" for the engine
work; every slice is still reviewed and committed by the deciding session after `scripts/check.sh`.
E1's plan is `docs/E1_INVENTORY.md` (read-only inventory, then the ten decisions recorded there). The ones that change
the build order or the audit:
- **The capability registry and the callback-to-flow adapters are out of E1.** The audit's "the only new code is the
  capability registry around the AIDL" and the spec's step 1 wording are corrected: unused code added for "no behaviour
  change" is dead code with an untested shape. They are written at the start of step 2, where the KDL status bar's
  sources are their first consumer, so use drives the API.
- **No package rename in E1.** Files move into `:engine` under their `dev.launcher.app.*` packages (namespace
  `dev.launcher.engine`). A rename would switch the accessibility service off on the phone after the update (Android
  stores it by class name) and drop the baseline profile's coverage of the moved code. The rename is its own later step
  with the stored-component migration.
- **`Design` and its companions move in E1** (the audit filed `Design` under Extract): nothing in motion, glass, tokens or
  fonts compiles without them; the parser/store split is step 2's design.
- **Home's model, `layout/`, widgets and the clock numerals wait for step 4**; notifications, controls and media for
  step 3, where their projection and the `Control` split are designed. `FocusHolder` and `LiveBlur` join E1.
- **Pure `git mv` slices are cluster-sized**; the ~200-line rule applies to every slice that edits.
- **E1's exit**: the APK's dex class list and per-class method counts identical to 9984034's except what each slice
  declares (`tools/dexlist.sh`, run after every slice), `smoke_emulator.sh`, the ART profile still covering moved code,
  `:engine` with its own lint baseline carried per slice, and on the S24 interleaved `scenario_switch.sh` /
  `scenario_shade.sh` against a 9984034 build, both installed with `tools/device.sh install`.
Pending the owner: declaring `compose.ui:ui-graphics` and `kotlinx-coroutines` in `:engine` for slice M1 (both already in
the APK transitively at the same versions; the dex diff is the evidence).
Seen on the way (S0): `./gradlew check` builds the release variant, which is not in the gate, and its Kotlin compile of
`GestureNav` ran out of memory ("GC overhead limit exceeded"); not checked on 9984034, and CI builds the debug variant only.

## D-071: The engine module compiles with the Compose compiler plugin (2026-11-05)

Found by `tools/dexlist.sh` in E1's first move slice (M1): the app's Compose compiler plugin stamps every class it
compiles with a `$stable` field and `@StabilityInferred`, which the settings' composables read to decide when to
recompose. A class moved into `:engine` (no plugin) lost them: eight moved classes lost their `<clinit>`, all lost the
field, and after M3 `Design`, `Theme`, `Token`, `Curve` and the motion types would have counted as unstable in every
settings composable (with Kotlin 2.0.20's strong skipping, compared by identity: a recomposition change CLAUDE.md
records as costly). Decision: `:engine` applies `org.jetbrains.kotlin.plugin.compose` (already declared at the root)
with `compose.runtime` as `compileOnly` from the app's BOM, so the moved classes carry the same metadata as before and
the APK is unchanged (the dex diff must read empty for M1). Nothing new reaches the phone; the owner is told. Step 2
wants Compose in the engine anyway ("Compose owns the tree").
Also in M1: library lint checks permissions against the library's own manifest, so the engine manifest declares
`SET_WALLPAPER` (the app already does; the merged manifest, now diffed on every `dexlist.sh` run, is identical) and
baselines a `getDrawable` finding the app's lint never raised. `GoogleFonts` becomes public (it was `internal`) so it can
move with `GoogleFontCatalog` in M1b; `ui-graphics` and `kotlinx-coroutines-android` 1.7.3 are declared in `:engine`
(owner, 2026-11-05: "Yes, declare them"). In M3 `dev.rikka.shizuku:api` 13.1.5 joins them on the same grounds (`ShizukuLink`
imports it; the APK already ships it at that version; the inventory's §4 had named it): decided by the reviewing session,
owner told.

## D-072: E1 done: the launcher runs on the engine module (2026-11-06)

Ten slices on `engine` (7d945d7..3d8bc67): `:engine` holds 81 Kotlin files (13,761 lines), the six Shizuku AIDL
interfaces and one resource file; the app keeps 137 files. Measured exit, per `docs/E1_INVENTORY.md`:
- The APK's class list and per-class method counts equal the pre-E1 build's except the two host interfaces S1 and S2
  declared; the merged manifest is identical (`tools/dexlist.sh E1`, run after every slice). Four code edits in all,
  each quoted in its commit: the host interfaces and two smart-cast rewrites Kotlin forces across modules.
- `scripts/check.sh` on every slice (421 unit tests, 4 shell tests); the ART profile still compiles the moved code
  (`speed-profile`, `reason=install-dm`, emulator and S24).
- Emulator, on each slice's build: smoke, Restore system, the gesture toggle, the watchdog loop (3.98 s from a frozen
  heartbeat to the restore plan), instant starts, a raw-touch pull over an app, Control Center's live blur, Notification
  Center, the App Switcher with and without a mini player, a close from a landscape app; the Pixel shade pixel-identical
  before and after the painter moved.
- S24 (`tools/e1_s24.sh`, 2026-11-06 07:10-07:22, builds stamped 9984034 and 3d8bc67, interleaved ref, E1, ref, E1,
  installed with their .dm): missed refreshes over 56 animations per build, ref 17, E1 21 (shade 16 vs 15; switch 1 vs
  6, single 17-25 ms frames on four switches a run), with the S24's thermal cap falling through the run (big cores
  3207 MHz at run 1, 1824 MHz at run 4, an E1 run): any bias is against E1. Within the noise of two rounds; the switch
  difference is the one to rerun with more rounds on a cool phone if anyone wants certainty. The pre-E1 build's own
  misses (nc_open up to 6 in one pass) are the launcher's standing state, not E1's.
- Known flaw in `tools/e1_s24.sh`'s summary: the shade rows show only the last of the three passes (the scenario
  overwrites its framestats file per pass); the per-pass totals above come from the scenario's own output.
Found on the way, recorded in the inventory's risks 10-12: the watchdog's real kill test (freeze the heartbeat file,
not force-stop), the cross-module smart-cast rule, and what a counts-only dex check cannot see (mangled internal names,
D8's renumbered synthetic lambdas). The phone is left on BUILD 3d8bc67 (its UPDATE replaces it).
Next: E2 (step 2: the KDL loader and interpreter core; the capability registry and flow adapters first, now that their
consumer exists), and the two probes' removal (`LiveTransitionProbe`, AIDL methods 17 and 43) as its own change.

## D-073: Two-agent team, Claude Code leading and Codex as teammate (2026-11-06, owner)

Owner's instruction (2026-11-06): engine work is done by Claude Code (lead) and Codex (teammate) "like teammates":
they discuss each step's approach, add insight or correct each other, split the work, report to each other, and both
agree before a task is complete; at milestones they review the work so far before moving on. This replaces D-070's
"this session plus an Opus agent" and OPERATING_RULES' single-session rule on `engine`.
How it works (`docs/team/`): `AGENTS.md` is the brief both agents load (Codex natively, Claude Code by import from
`CLAUDE.md`); `PROTOCOL.md` is the ten-phase loop (propose, discuss, agree, split, work, report, cross-check, fix,
sign-off, integrate), escalation (three rounds, then the lead decides with dissent recorded, or the owner for anything
the Ask list reserves), blind milestone reviews, a security checklist for the attack surface, and what happens when
Codex is unavailable (record it, wait, never sign for it). `BOARD.md` holds the state; one thread per task is the full
record, so neither agent depends on chat memory. `scripts/team/codex.sh` runs Codex non-interactively in its own
worktree (`scripts/team/worktree.sh`, branch `team/codex/<slug>`), read-only for discussion and review, write access in
its worktree for work, and files its answer into the right section of the thread. The lead writes the thread, the board
and DECISIONS; every file in a split has one owner; integration is rebase plus fast-forward with both agents in the
trailers. One agent holds the phone at a time.
Also in this change: `docs/ENGINE_SPEC.md` becomes canonical in the repo (Codex cannot read the Claude Doc); the Doc's
newer sections (the budget, "no Shizuku", step 1's exit) are merged with the repo's step-0 answers. Found while setting
up: E1 moved `ShizukuLink` as it was, so the no-Shizuku host is not yet scheduled; it is the first team thread (T-001),
proposed as E1b before E2.

## D-074: E2's plan and its decisions; the owner's answers on the upgrade and on T-001 (2026-11-06)

E2's plan is `docs/E2_PLAN.md` (written by the Opus agent read-only, reviewed here, D-070's mode). Decided by the
reviewing session (recorded in the plan's last section, logged here as the plan said they would be): StatusBarView stays
the default behind a dev switch until E2's exit run; the capability registry gets its shape and the app-side sources in
E2 and its AIDL entries with their first consumer (E3); full KDL 2 against kdl-org's suite (vendored under its own
CC BY-SA 4.0 folder, never mixed with our sources); tokens for what a theme restyles, props for structure; pixel-identical
where both bars draw the same glyph with the same Paint, diff.py's defaults where a path replaces a Canvas arc; the
charging moment, sharing capsule, lock fade and Pixel header deferred to E3; the bar stays on `nav-ui`; **step 2's
"first twelve primitives" read as the six the bar uses, the other six with their first consumer** (a reading of the
build order's wording); new code in `dev.launcher.engine.*`. Taken while building slices 1-11, each in its commit:
`Value.Color` is a kind of its own; a unit comes from the first operand that has one; mixed known units are refused at
compile time; `ACCESS_NETWORK_STATE` declared in the engine's manifest for library lint (the app already declares it).
Owner, 2026-11-06: **yes** to Compose 1.11.4 with Kotlin 2.2 as its own slice (T-002), measured on the settings, the
shade and the App Switcher on the S24 before and after, the class-list diff declared first, and **stop and ask if AGP or
the compile SDK has to move**. T-001 runs **alongside E2**, not before it; its first slice (the vendored ADB client and
its tests) is offered to Codex while the lead does the upgrade. Still open with the owner: one expression syntax
(`{expr}` with pipes; a spec edit with the invalid example's fix) and the bar's place in the spec (a `shade` component).

## D-075: AGP moves to 8.13 before the Compose upgrade; T-001's native pairing approved, Android 12+ only (2026-11-06, owner)

T-002 (Compose 1.11.4, Kotlin 2.2.21) built with no source change and passed every unit test (app 421, engine 73), and
its dex diff was fully explained by the declared categories plus Kotlin 2.2's code generation; but AGP 8.7.3's lint
crashed on the lint checks Compose 1.11.4's libraries ship (`IncompatibleClassChangeError` in lifecycle 2.9.4's
`NonNullableMutableLiveDataDetector`; with that check off, in Compose's own `ComposableFlowOperatorDetector`, which
applies to us). The owner's stop rule applied. Tested before asking: lint 8.13.2 under AGP 8.7.3 runs clean of crashes
and reports three real old bugs (`setSystemBarsBehavior`, Android 11 API, unguarded with minSdk 29). Owner, asked between
that, moving AGP, or pausing: **move AGP to 8.13**, its own slice before T-002 (T-003: AGP 8.13.2, Gradle 8.13, the three
guards). T-001 slice 1b, asked after Codex read upstream Shizuku v13.6.0: **the native route is approved** (upstream's JNI
bridge to BoringSSL's SPAKE2, with BoringSSL via prefab, libc++ and `bcpkix-jdk15on` 1.70; NDK/CMake in `:engine`), and
**our own pairing supports Android 12+ only** (the public `SSLSockets.exportKeyingMaterial`; no hidden or bundled
Conscrypt; Android 11 keeps Shizuku). Codex's other findings shape slices 1-2: bounded framing (4,096 and 16,384 bytes),
no key or payload logging, a one-time capability on the binder handover.

## D-076: Resource use on the owner's machine (2026-11-07, owner)

The owner's machine runs both agents, one emulator and every worktree's builds at once. AGENTS.md gains "Resource use":
one full `scripts/check.sh` at a time (iterate with `CHECK_FAST=1`), `./gradlew --stop` when a task ends, a worktree
removed once its branch lands, one shared emulator started only when needed, named on the board and shut down by its
holder. `gradle.properties` runs the Kotlin compiler inside Gradle (`kotlin.compiler.execution.strategy=in-process`, no
separate Kotlin daemon per worktree) and lets an idle Gradle daemon exit after 10 minutes. Written by a peer session at the
owner's request; the owner confirmed it to the lead in chat ("Yes, commit and log it"). Nothing verified changes. Watched:
an in-process compile shares Gradle's 2 GB heap (`org.gradle.jvmargs=-Xmx2g`), and compiling `GestureNav` has run out of
memory before in the release variant (D-070); the first gates after this are checked for "GC overhead".

## D-077: Compose 1.11.4 and Kotlin 2.2.21 landed (T-002, 2026-11-07, lead with Codex)

The owner's go (2026-11-06) was "Compose 1.11.4 and Kotlin 2.2 as its own slice". It landed on AGP 8.13.2 (T-003) with
compileSdk 35 unchanged, so the stop rule never triggered. Choices made without the owner:
- the Compose BOM 2026.06.01 rather than pinned artefacts, because it maps ui, foundation and runtime to 1.11.4;
- `:engine` pins kotlinx-coroutines 1.9.0, the version Compose 1.11.4's ui resolves in the APK (1.7.3 before);
- the APK grows from 19.8 MB to 23.7 MB, all of it the libraries;
- the settings scenario covers two tiles' pages and not Look's page, which is below the fold.
S24 comparison, interleaved ref, new, ref, new: 15 against 14 missed refreshes over 70 animations. The GPU account is
limited as Codex required: both builds hit the slow GPU mode equally often, and its cause is not traced (parking lot).
Codex's dissent on the idle check stands as a recorded limit: injected input cannot be told apart from the owner's
without root, so focus checks guard each gesture instead. Not verified: text editing, focus and semantics beyond the
scenarios. The owner's look at the settings is the remaining check.

## D-078: T-001 slice 1b landed: native ADB pairing, Android 12+ (2026-11-07, Codex with the lead)

Within the owner's yes of 2026-11-06 (native route, BoringSSL via prefab, libc++, bcpkix-jdk15on 1.70, Android 12+ only).
Choices made without the owner:
- NDK **29.0.14206865 (r29 stable)** rather than upstream's 29.0.13113456, which Google's repository lists as r29-beta1;
  CMake 3.22.1; both installed from Google's repository on the owner's machine, SHA-1 checked;
- all four production ABIs kept (Codex: dropping 32-bit would be scope the owner's yes did not cover);
- the adb identity is a software RSA-2048 key wrapped by an Android Keystore AES-256-GCM key in an atomic file under
  `noBackupFilesDir`, with an explicit "identity lost, pair again" result instead of upstream's silent regeneration;
- only the TLS (STLS) connection, no plaintext AUTH; every connect, handshake, read and operation is bounded (30 s per
  operation).
Measured: emulator (test APK uid) pairs, runs `shell:id` as uid 2000, reloads the key in a fresh process, refuses a wrong
code; our process logs no code (Android's system server logs every code it shows). APK 23.7 to 32.0 MB universal
(+5.1 to 5.5 MB per single-ABI install). Open for slice 2: nothing authenticates adbd on the connect port, so the
binder handover's one-time capability carries that weight; the launcher's uid and the S24 are not yet tried.

## D-079: E2 slice 6 landed: the first KDL bar, behind a developer switch (T-004, 2026-11-07, lead with Codex)

The engine draws the time in the shade window in StatusBarView's place while `TEST_STATUS --es do kdl --ez on true`
is set (adb, DUMP senders; StatusBarView stays everyone's bar). Choices made without the owner:
- a component in a window it does not own gets **its own Recomposer** on the window's thread (Compose's window recomposer
  reads the root's lifecycle owner, and the shade's root has none);
- a component is disposed **only by its owner**, never on a view detach: Android tears a removed window down only when
  the next is added, and a view added in between was detached with the dying window;
- `Layout.row` with `centre-line` reports the line its highest child really sits on (whole pixels of slack), and the
  fraction left by whole-pixel placement moves by the root node's translation;
- the slice landed as four commits (about 35, 296, 17 and 145 production lines);
- the component's device checks run through the engine test APK's one runner (three lines in the ADB proof's dispatch).
Measured on the emulator: the KDL time is pixel-identical to StatusBarView's (max channel difference 0) in iOS 27's
Inter, Pixel's Google Sans Flex and landscape; 0 frames at rest; one repaint per minute; the switch's broadcast from a uid
without DUMP is not delivered. Not measured: anything on the S24 (pull frame stats, cold and warm first presentation).

## D-080: The instruction files say one direction (2026-11-07, owner)

The owner saw the lead session still reasoning partly from the launcher's pre-2026-11-03 direction. Cause: what Claude
Code loads at start still carried it. `docs/OPERATING_RULES.md` (imported by `CLAUDE.md`) imported `docs/SPEC.md` as
"the locked direction" and named it the source of truth; `CLAUDE.md` below its banner sent readers to
`docs/REQUIREMENTS.md` as "what we are building" and kept an "Architecture (target)", a "Current plan (2026-10-10)" with
first tasks, open items that are decided, and build versions two upgrades old; the banner still said the Shizuku fork
was the alpha's answer; `docs/ROADMAP.md` showed MS as ACTIVE and "single session, no reviewer" in its definition of
done; `AGENTS.md` gave Codex the old versions.
Fixed: `OPERATING_RULES.md` no longer imports `SPEC.md` and names `ENGINE_SPEC.md` and the board as sources of truth;
`CLAUDE.md` Part 1 gains an explicit precedence order (owner's latest word, DECISIONS newest first, ENGINE_SPEC, the
audit, the board and thread, the step's plan; below them and never direction: Part 2, SPEC, REQUIREMENTS, PLAN_*, the
roadmap's table), and Part 2 is headed as the reference manual for the launcher as built; the plan section points to the
build order; decided open items are removed; versions are current; the banner says no Shizuku (T-001). ROADMAP marks MS
as active on `main` only. `REQUIREMENTS.md` and every `PLAN_*.md` carry a history banner. `polish-pass` frames the
bar for the engine. No code changed.
A session reads these files when it starts, so a session open before this commit still has the old text in context:
start a new one after pulling.

## D-081: One expression syntax, `{expr}` with pipes (2026-11-07, owner) [owner]

E2 plan question 5. The owner: "Yes to 5: one expression syntax, {expr} with pipes for formatting. Update the spec,
including the battery-ring example." A reference alone is a whole attribute value (`$name`, `@name`); anything more goes
in braces: text around it (`"{$level}%"`), arithmetic (`"{@thickness * 3}"`), conditions, and formatters as pipes
(`"{$t | date:'EEE d MMM'}"`), pipes binding loosest and chaining left to right. The spec's `{{…}}` formatter form and
bare arithmetic after a reference (`@thickness*3`) are gone. `docs/ENGINE_SPEC.md`: a new **expressions** item under the
component model, the formatter line rewritten, and the battery-ring example as valid KDL 2 (`#false`, `type=dp`, the
script as a raw multi-line string; step 0's correction, D-069) in the braced syntax. The engine already did this since
slice 4 (the validator refuses the unbraced form with a hint); its test copy of the example now follows the spec
(`ModelTexts.BATTERY_RING`: only `ring`, not yet a primitive, is refused) and the old form keeps its own test.

## D-082: The status bar is a component of the shade surface (2026-11-07, owner) [owner]

E2 plan question 6. The owner: "Yes to 6: the status bar is a component of the shade surface; fix the spec's wording."
The spec had `status` as a surface "not available as a replacement" and the status bar out of the engine's scope, while
step 2's exit is a KDL status bar (and the launcher has drawn its own since M2). `docs/ENGINE_SPEC.md` now says: the
status bar's window stays Android's, the engine hides the stock bar's content and draws its own bar as a component of the
`shade` surface, in the shade's window over every app and home. Wording chosen by the lead without the owner, in line
with the spec's existing banner rule and its "install a bar-only port" promise: the shade exposes a `status` slot; a
shade fill supplies a bar or inherits the engine default; a bar-only package fills that slot alone; a fill for "status"
is refused with that hint. Also updated: the scope line, the tier line (the shade, its bar included, is Pro), the porting
gaps (two now: no lock screen, no arbitrary processes) and the Quickshell/AGS/EWW row. How the slot is written in a
layout file is left to the step that builds layouts (E4).

## D-083: Full Wi-Fi glyph within 3 % of the bar's pixels (2026-11-08, owner) [owner]

T-005 (E2 slice 9). The agreed pixel target for the `statusbar` set against StatusBarView's own drawing was
`tools/fidelity/diff.py`'s defaults (mean < 2, < 2 % of pixels over 12) per glyph. After the review's fixes 34 of 36
pairs pass (bolt and airplane pixel-identical, the moon at 1.9 % with arcs as cubics of 15 degrees or less);
`wifi.3` on white and on black stays at 2.7 % (mean 1.05): a sub-pixel disagreement along one band's edges, no offset
(coverage centroid within 0.02 px, total coverage within 1 %), invisible side by side. Two attempts did not bring it
under 2 % (Codex's finer arcs moved it from 2.4 to 2.7 %). The owner: "Accept up to 3%". `wifi.3` is a named exception
at up to 3 % of pixels over 12; every other glyph keeps the defaults. The owner still signs the bar off on the S24 once
it draws these glyphs (slices 10 and 11).

## D-084: The own host's handover carries no build text (2026-11-08, lead and codex)

T-001 slice 2's plan had the starter send the launcher's build text with its handover, to refuse a starter of another
build. Dropped (Codex's review agreed): the starter is always loaded from the running launcher's own `sourceDir`, its
service serves only the launcher's uid (`ShellService.onlyUid`) and dies with the launcher, so the build text would
check compatibility, not authenticate anyone; the one-use capability and the one-use ack authenticate the handover.
Kept: only a service the current attempt published may be destroyed by the host. Also measured and decided in 2b:
`shell,raw:` keeps a PTY on Android 17 but in raw mode, and a disposable stdin line did not come back (no echo); the
starter's refusal-proof modes (`--test=...`) are passed only by the DUMP-gated `TEST_SHELL` route.

## D-085: Knockout, `layer` and `reveal` in the format; six expression functions (2026-11-08, lead and codex)

T-006 (E2 slice 10). The spec is silent on blend modes; the battery needs its number cut out of its body. Added, as narrow
as that needs: `text blend="knockout"` (erases at full strength whatever the colour's alpha), `layer` on every primitive
(the text cuts only within its nearest enabled ancestor), both literal-only so the validator can prove it
(`knockout-needs-layer`); `box reveal` (0..1: box and children drawn at full size, clipped to that share of the width,
at 1 too, as the bar's level goes through its clip); `stack align="centre-line"`. Expressions gain call syntax with a fixed
list of six: `mix` (StatusBarView's `blend` exactly), `opaque`, `withAlpha`, `alphaOf`, `scaleAlpha` (integer division),
`clamp`; known invalid calls are diagnostics, invalid at run time gives `Null`, never a throw or NaN. Limits on an
expression: 16,384 characters, 512 nodes, nesting 64. Exact float extents now run through `row` and `stack` beside the
whole-pixel allocation. `blend` on `box` and `icon` waits for a consumer.

## D-086: On-phone pairing: the code in Android's own shade; our service stops with Shizuku (2026-11-08, owner and lead)

T-001 slice 3. Settings' pairing screen hides every app overlay and its pairing server ends when Settings pauses
(measured), so neither a card of ours nor a page of ours can take the code. The owner: "we haven't activated our shades or
gesture bar before pairing, correct? so we would have to use stock shade right?": the code is typed into a notification's
reply field in Android's own shade, there whenever our shell is not running. An accessibility-overlay card was
refused: it would defeat a platform safeguard. On the S24 the owner found the service outliving Shizuku's server and keeping
that shade away, with neither set of bars working: stopping Shizuku now stops our service (`ShizukuHost`), Pair is refused
while our own service runs, Forget stops it. Discovery may run again within one pairing (a reply can outlive a 60 s session),
each session bounded as agreed. No permission added at target 34 (`ACCESS_LOCAL_NETWORK` applies from target 37). Slice 3 runs
behind the developer switch; Shizuku stays the default until slice 5.

## D-087: `RECEIVE_BOOT_COMPLETED` for T-001 slice 4 (2026-11-08, owner)

The owner, asked whether to add it so the launcher's own helper can restart after a reboot: "Yes, add it". Nothing else was
added with it; any foreground service or further permission the slice turns out to need goes back to the owner first.

## D-088: A leftover note turns Wireless debugging off (2026-11-08, owner)

T-001 slice 4. When the launcher turns Wireless debugging on to start its helper, it keeps a note so it can turn it off again, across a crash. Finding that note at a later start, it cannot tell its own "on" from one the user made since. Asked, the owner chose "Turn it off": a debugging port left open by accident is the worse mistake; the cost is one toggle, in a rare case, said in the log. Wireless debugging the user had on before the launcher began is never touched.

## D-089: Our own host needs USB debugging on (2026-11-08, owner)

T-001 slice 4. Measured on the S24: turning USB debugging off stops adbd at once, and everything under it dies, Wireless debugging on or not; with it on, the helper survives Wireless debugging going off. Asked, the owner chose to ask users to leave it on: pairing refuses while it is off and says why, the settings page says so if it goes off later, and the launcher never changes that setting itself. This narrows the spec's sequence ("disables wireless debugging") to what works, and adds one step to onboarding for the alpha to explain. Rejected: the launcher turning it on (a security setting changed without asking), keeping Wireless debugging on instead (unmeasured, a port open on every network).

## D-090: Slice 4 merged with its handover still a static change (2026-11-09, owner)

T-001 slice 4. Every recovery journey was measured (thread, "device results"), and the owner judged the change from the stock bar to ours "just a static transition ... we could make it a bit more smooth if we tried". Asked, the owner chose to merge and take the smoother handover as its own next slice ("Yes, merge it"). It stays behind the developer switch, so the default setup does not change. Open with it: a process started by the boot receiver alone is not proved (Android started it for a bound service first), and unlock-to-running was captured for one reboot only.

Found on the way, not a choice: the Shizuku fork's own start-on-boot turns Wireless debugging on and restarts adbd about a minute after boot, which kills our helper and leaves port 5555 open. The restart guard held. The owner turned that option off on the S24; a clear message for anyone else goes with slice 5.

## D-091: Our status bar waits 200 ms for the stock one (2026-11-09, owner)

T-001, replacing the fade D-090 named as the next slice. The owner's words: "the problem is they overlap each other for a brief moment. just add a slight delay and it would be nice". Measured on the S24: the stock bar's exit takes about 175 ms, and ours came 69 ms into it. Ours now waits 200 ms from the flags' call returning, then comes in on `motion.statusbar.show` (the lead kept that fade rather than a snap, and told the owner). About 108 ms show neither bar: chosen over a tighter wait because a busy phone starts Android's fade late. The delay is a constant, not a motion role: it is Android's time, not a theme's. Signed off by the owner on the S24: "Looks good, merge".

## D-092: The iOS and Pixel designs are scrapped; a default theme of our own; settings are what creators offer (2026-11-09, owner) [owner]

The owner: "scrap the current ios and pixel designs. we'll keep the blur and colour presets that we can offer to creators that we already made but the current launcher settings is nowhere near our final goal that we have now. the new direction for our launcher requires us to have a completely unique to us default theme, and the settings app carries its own identity. the settings that the users can change is whatever the creators offer them to change based on the themes, layouts, plugins etc. that they have installed and is active."

- **Scrapped as products and as targets:** the iOS 27 and Pixel looks, and the current settings app. No more work or polish on them, and no plan may aim at them. This replaces every "iOS" and "Pixel" target in the build order and the alpha gate.
- **Removal:** each is deleted when its replacement works, so the phone keeps working throughout (owner, asked).
- **E2's exit stands as an engine proof:** the KDL bar still pixel-matches `StatusBarView`, purely to prove the engine draws exactly what it is told. That look does not ship (owner, asked: "Keep it as a test only").
- **Kept, as neutral presets creators may use:** "anything that can be kept" (owner, asked): blur and colour presets, motion presets, transition styles, the panel-pull models, the material renderer, palettes from the wallpaper. iOS and Pixel names come off them. The default theme is not bound to them.
- **Identity:** the owner leads, the lead proposes options; nothing of the new look is built until the owner has chosen.
- **Settings:** the app has its own identity. What a user can change is what the active themes, layouts and plugins offer. The engine's own settings (permissions, the shell host, packages, updates, safety) are its only fixed content; exactly what those are is proposed with the settings design.

Open, for the owner: whether the alpha needs a second complete theme (the audit's store gate says two; the spec's shade step asked for two to prove mixing). The spec now asks only for a second fill to prove panels mix. `docs/LAUNCHER_AUDIT.md` and Part 2 of `CLAUDE.md` still describe the old designs as built; they are the manual, not the plan.

## D-093: The default layout's direction: Caelestia as a phone, in our design language (2026-11-09, owner) [owner]

The owner: "we will basically design a mobile version for these dotfiles: https://github.com/caelestia-dots/shell ... blending the dotfiles theme with our design language." The design language is the owner's Design System artifact (https://claude.ai/artifact/EKCiKtmh9RY4ApW5T2TPEc), used for the app, settings and the default layout. Drafts in Figma: https://www.figma.com/design/7E43AzlCpFNqhUkApcstZA (draft 2 is current).

Settled with the owner:
- No side rail ("as soon as you open an app it gets covered"). One island around the camera, the only part of our frame that stays over apps; tap it or pull down and one panel grows out of it with every other panel collapsed inside, one section open at a time.
- Recents exist (Caelestia has none): swipe up and hold in an app.
- One home, the launcher is where apps live: no home pages.
- Configurable by the layout in settings: what the island shows at rest (default time and battery), and whether long panels scroll inside (default) or grow to full screen.
- Motion as drafted in draft 2 ("Looks right"): a finger moves things exactly, overshoot only on the island's tap, nothing pops, everything catchable, decode only for what just happened. Caelestia's stretching drawers only as a setting, off by default (lead's proposal, not yet answered).

Kept from Caelestia: the frame on home with drawers joined to it by concave flares, the dashboard contents, launcher search with `>` commands. Overruled by the design language: wallpaper colours (monochrome, signal for state), Material shapes, the type, the mascots (Tess). Engine gaps it exposed are listed in the Figma notes: island and live activities, shape unions for the flares, edge drawers beyond the shade, accordion sections, recents as a surface, text rotation, new sources (lyrics, visualiser, temperatures, the app in front), a command registry. The lock screen stays Android's.

## D-094: Draft 2 of the default layout is approved (2026-11-09, owner) [owner]

The owner, on draft 2 (https://www.figma.com/design/7E43AzlCpFNqhUkApcstZA, page "Draft 2"): "everything looks good", after two last changes: the island is black in both themes, and at home it is docked to the top edge, hanging from it and flared into the 6 dp frame, the shape the panel grows from; over apps it floats around the camera. Approved with it: the screens 1 to 17 (home, panel collapsed and with each section open, in an app, recents, launcher idle and typing, `>` commands, settings, power, volume on the island, light theme, the apply moment) and the motion keyframes. The next step is the build plan.

## D-095: Notification icons given as a link stay refused (2026-11-09, owner) [owner]

T-007. The KDL bar's notification icons load an app's small icon only from its resources or a bitmap; an icon given as a URI is refused, because loading it would make the launcher open another app's link with the launcher's own rights. StatusBarView loads them, so a refused app shows no icon on the KDL bar. The owner: "okay then keep them blocked", with the lead's proposal: each refusal is logged with the app's name only, so the phone shows whether it ever happens; if it does, they are loaded with a size bound that applies before the load.

## D-096: The default layout's permissions and services; settings after E3 (2026-11-09, owner) [owner]

T-008, the plan's section 4, asked of the owner:
- **Weather:** Open-Meteo (no account, no key) with the phone's location. A location permission is added for it, asked when the Today section first needs it; denied, the section says so and offers the city setting instead.
- **Calendar:** `READ_CALENDAR` is added, asked when Today first needs it; denied, a row says so.
- **Music bars:** open. The owner asked whether the output can be read instead of the microphone. Android's Visualizer does read the output mix, not the microphone, but Android still requires `RECORD_AUDIO` for it; the other routes are a capture consent each session (MediaProjection) or a privileged permission the shell may or may not hold (to check on the S24, read-only). Until answered, the bars move with playback only.
- **Settings app:** after E3, keeping one element at a time.
Still open from section 4: volume key interception (measured on the S24 first) and power actions from the shell (verified on the S24 first).

## D-097: The island's music bars move with playback only (2026-11-09, owner) [owner]

D-096's open point. The owner: "Playback only". No audio is read: the bars animate while media plays and rest when it pauses, from the media session's state. No `RECORD_AUDIO`, no capture consent. Real levels may come later only through a route with no prompt, if the shell is found to hold one.

## D-098: Draft 4 of the default layout is approved; it replaces draft 2 (2026-11-11, owner) [owner]

The owner reviewed draft 3 and asked for draft 4's changes, then: "Approved" (https://www.figma.com/design/7E43AzlCpFNqhUkApcstZA, page "Draft 4"; drafts 2 and 3 stay in the file for history). What changed from draft 2 (D-094):
- **No frame on home.** The wallpaper runs edge to edge; nothing is docked to an edge. The concave joins and the frame are gone from the design.
- **The island floats everywhere,** home included, a black pill under the top edge. The panel grows out of it and absorbs it while open.
- **The panel is a stack of pages,** not an accordion. Page 1: the time, Controls (four toggles, brightness), the playing song, Notifications (the two newest, no buttons); "Controls ›" and "Notifications ›" each grow into a full panel of their own. Today (weather, events) and System are pages 2 and 3, one sideways swipe away. Swipe rule (lead's, shown in the draft): a sideways swipe that starts on a notification row moves that row and clears it; anywhere else it changes page; the full Notifications panel has no pages; the direction is decided after 12 dp, so a pull that drifts sideways still closes the panel.
- **The bottom is Android's thin gesture pill,** nothing docked. Swipe up and hold: the pill itself stretches into the recents blob; its power button turns the same blob into Power. Up is home, along the bottom switches, as before.
- **Recents, minimal:** app icons with names, swiped sideways, the selected one centred and ringed; the whole screen behind shows the selected app's picture; one line under them, power on the left, Clear all on the right. Tap opens, a flick up closes that app.
- **Power** is reached from the button in recents (owner's choice), floating, not docked: Lock at a tap, Restart and Power off held until the fill completes.
- **Glass** (the design language's, re-read 2026-11-11): plain frosted blur 24 dp, saturation 1.2, tint 76 %, no refraction, on what floats over content: the panel, recents, power, the launcher, the dock. The island and the pill stay solid black (owner's choice). Cards inside glass are translucent fills, never glass on glass; text on glass is `ink` and `ink-2` only; with blur unavailable, the opaque surface colour.
- **Terracotta (`signal`) used more, one meaning:** everything live or selected (levels, the current page, the selected app, the power-off hold, the playing song's button, the next event, a full disk, the notification count, the clock's minutes). Active control tiles stay white; a tap on a tile plays a short terracotta animation (owner, 2026-11-11), designed with the motion work.
- **Kept:** the launcher rising from the dock (owner's choice), its A to Z and `>` commands; simpler panels with no cards, borders or capital labels.

Still open, asked in the draft's notes: a long press on the pill opening Power directly; what recents shows behind it when opened from home (lead's proposal: the most recent app); whether a swipe up from the pill on home opens the launcher. The build plan is updated for draft 4 (T-008).

## D-099: E2.13a-1's runtime choices (2026-11-11, lead and Codex) [team]

Made without the owner while building node motion (T-009, 13a-1); none changes anything a user sees today (no KDL in use declares a transition).
- **A motion declaration is at most 4096 characters** (Codex's bound in the grammar, refused with `motion-limit`).
- **Roles the preset lacks make the channel jump.** `motion.move` and `motion.state`, the defaults when a transition names no curve, are in none of the launcher's presets yet; the default preset's roles come with E2.14a.
- **A box's centred stroke is inside its drawing bounds** at rest and in motion alike (its outer half was cut by a layer before); no layered centred stroke exists in the status bar, so nothing on screen changes.
- **Each moving node asks for its own draw at every step**, and a placement that changes what a node shows asks too (measured on the emulator: the window's invalidation drew nothing again on the GPU path). A node without a Compose layer of its own redraws its window's whole tree (frames re-recorded for every node at each step; painters' recordings only while a size moves). Its cost on the S24 is E2.17's measure (D-067's bound); giving moving nodes a layer of their own is the lever if it is too high.
- **13a-1 went over the slice size** (about 400 production lines with the grammar's 166 apart): the drawing contract did not split without leaving one drawing path wrong in between.

## D-100: E2.13a-2's presence choices (2026-11-11, lead and Codex) [team]

Made without the owner while building `enter` and `exit` (T-009, 13a-2); nothing on screen changes today (no KDL in use
declares presence).
- **The effects' far values:** `scale` arrives from and leaves to 0.85, `rise` is 8 pt below its place, `grow` is 0 along
  its parent's axis (width in a row, else height), `fade` 0. Judged; a theme's presets may name them later.
- **`motion.enter` and `motion.exit` are in no preset yet:** an `enter`/`exit` with no curve of its own and no node spring
  goes at once (as 13a-1's roles, D-099), until E2.14a's default preset.
- **A leaving node draws right after the surviving sibling it followed** (first if none), out of layout, measured with the
  constraints it last had; motions already in flight go on. A node planned again before its exit ends is the same node
  turning back on its enter curve (its exit's, if it has no enter).
- **Bounds:** at most 64 exits at once (the oldest ends at once); what is alive while a plan is built (active, retained and
  the plan's new nodes) never passes 4096: the oldest exits not planned again end first, then removed nodes go at once
  instead of leaving.
- **13a-2 went over the slice size** (runtime and its review fixes about 350 production lines): presence and its bounds
  did not split without one half leaking nodes.

## D-101: E2.13a-3's rolling text (2026-11-12, owner, lead and Codex) [team]

- **A font or size change during a roll shows at once** (owner, 2026-11-12, asked after the same finding needed a third
  try): a change of look is not a change of content; a roll in flight ends and the text shows in the new font, a text
  change with it too. Colour changes leave a roll alone (the roll's key is geometry only). Codex asked for a continuous
  handover; overruled by the owner.
- **Which strings roll by characters, decided by their shaped glyphs on Android 12+** (lead and Codex; changed from T-009
  §3's background pixel check, which left every new string, such as a clock's next minute, pending when it changed): a
  short string of ASCII digits and `: . , % / -` and space, shaped whole and character by character (`TextRunShaper`) to
  the same glyph ids, fonts and positions (exactly: the measured error is 0). Before Android 12 the pixel check stays, in
  the background, at most 16 waiting. On the emulator, 208 fixture strings × 13 font configurations are alike by glyphs
  and by pixels, translucent and knockout included; a ligature is refused by both.
- **The roll's look (judged):** StatusBarView's: glyphs rise 0.55 × the size, fade by distance, up for a larger number
  (times compare their digits; otherwise one signed decimal number; anything else up), unchanged characters still, a
  length change or an unverified string rolling whole; changed characters `motion.text.stagger` apart (no preset has the
  role yet: together); at most 2 leaving per character. Rolling ink is held by every layer on the way up (its own,
  an ancestor's, scaled or mirrored).
- **13a-3 went over the slice size** (eligibility, roll and bounds about 450 production lines): the bounds and the
  review rounds could not land apart from the roll they hold.

## D-102: E2.13b's placement on the camera (2026-11-12, lead and Codex) [team]

Made without the owner (T-010); nothing on screen changes today (the bar keeps `place="window"`; the island, E2.14a, is
the first to use it).
- **StatusBarView's rule is the engine's:** horizontally on a top cutout's centre whatever its band (else the window's
  centre); the line on the cutout's centre strictly inside 20-80 % of the status bar's height (visibility ignored), else
  52 % of it; worked out on the screen, then in the view's px by its resting origin (the untransformed window's place
  plus integer layout places, translations and scales left out).
- **A host moving is no motion:** a change of the resting origin (the host's first layout, an ancestor, a scroll, the
  window) rebases the root's motion so it stays on its anchor and a motion already running goes on; only a change of the
  device (or of the window's width, the fallback's centre) moves it on its place transition.
- **The `device` source** (Codex): screen px of the display's own logical space, the cutout's safe insets and top-edge
  rectangle, density, rotation, status height, model; fold state and dpi later. Its Android adapter reads each
  display's real metrics (the window manager's maximum window metrics gave a second display the main one's size) and
  tells everything on its handler's thread.
- **Not proven:** the S24 (the owner's exit check), live display changes on Android (shown with a simulated display:
  the proof's activity is made again by an overlay or a turn), multi-window, a spring's interruption here (13a-0's).

## D-103: E2.13c's shapes (2026-11-12, lead and Codex; one point to the owner) [team]

Made without the owner (T-011); nothing on screen changes today (no component in use clips or morphs a radius).
- **One shown radius** for a box's fill, its children's clip and its hit region: the declaration at the drawn size
  (`full`: half the shorter side, whichever axis) plus a handover residual continued toward 0 on the radius curve from
  the radius and speed shown at a change (the clamp's speed where it holds); clamped to `[0, half the shorter side]`;
  empty or non-finite geometry draws nothing and clips nothing.
- **The children's rounded clip by release** (read from AOSP's `SkiaCanvas.cpp` per branch): a path clip on the GPU
  from Android 11 (antialiased since android11-release; measured equal to the fill's edge); a two-layer `DST_IN` mask on
  Android 10 and on software canvases (whose path clip is antialiased differently from their fill: 0.11 apart).
- **Android 10's mask budget (lead, after four rounds; Codex dissents; Q-003 to the owner):** at most 8 MiB of mask
  layers held by every masking node on a thread together (device size, the display as the cap), counted before any
  allocation; a mask past it keeps the same rounded clip, aliased. Android 11 and later are unaffected.
- **Acceptance (agreed with Codex):** shapes are judged against each renderer's own `drawRoundRect` of the expected
  geometry (mean edge error ≤ 0.02, every channel ≤ 24); the error against ideal coverage is reported, not judged (GPU
  0.015 mean and 0.028 worst; Skia's software raster 0.07-0.12 off ideal on any rounded fill).
- **A shape clip's GPU work is done before a component shows** (an off-screen frame at its preparation, completion
  observed); its benefit on first use is not measured.
- **A child cannot be larger than its box** (the layout keeps children within their parent's bounds): known, unchanged.

## D-104: The design language's typefaces are bundled (2026-11-12, owner) [owner]

The owner: "yes, use the fonts in figma, as it follows the design language." Read from Figma (draft 4 and the
Components page's text styles): **IBM Plex Sans** for text (display SemiBold 32, title SemiBold 22, heading SemiBold 17,
body Regular 15, body-strong Medium 15, small Regular 13, caption Regular 12) and **JetBrains Mono** for figures and
labels (wordmark Medium 17, numeral Medium 28, value Regular 14, code Regular 13, label Medium 11; the island at rest:
Regular 13 on a 20 px line). Six stray Inter texts on draft 4 are left-overs, not the language.
- Bundled unmodified from Google's fonts repository (google/fonts, `ofl/ibmplexsans`, `ofl/jetbrainsmono`): the upright
  variable files (`IBMPlexSansVariable.ttf` 537 KB, weight 100-700, width kept at 100; `JetBrainsMonoVariable.ttf`
  187 KB, weight 100-800), each with its OFL 1.1 text, in `engine/src/main/assets/fonts/`. Italics not bundled (the
  design uses none). "Plex" is a reserved font name: the file is never modified or subset under that name.
- Font ids `plex-sans` and `jetbrains-mono` (`FontFamily.Bundled`), usable wherever a family id is (themes' text
  styles, KDL `text`'s `font`). Inter stays bundled for the launcher as built until its views go.
- Both go on the licences page (with Inter, AOSP's icons and Material Symbols).

## D-105: E2.14a, the island at rest (2026-11-12, owner and team) [owner + team]

- **Owner:** no camera artwork: on a phone without a cutout the island's middle stays empty. And on a phone whose camera
  sits high (the emulator's hole is centred 12.5 dp from the top; draft 4 assumes about 24 dp) the island keeps draft
  4's 8 dp from the screen's top, the camera then in its upper half ("Keep the 8 dp gap").
- **`place-gap`** (team): a literal length on a root placed on the camera; its top never nearer the screen's top than
  it. Without it D-102 is unchanged. Documented in `KDL_PLACEMENT.md`.
- **The engine's default motion roles** come from the owner's design system, in `ios27.json` (every preset extends it):
  `motion.state` and `motion.text.roll` 120 ms cubic-bezier(0.23, 1, 0.32, 1); `motion.move` 220 ms (0.2, 1.06,
  0.32, 1); `motion.enter` 220 ms (0.23, 1, 0.32, 1); `motion.exit` 220 ms (0.55, 0, 1, 0.45). Closes D-099 and
  D-100's deferred roles.
- **Island text in dp** (does not follow the font size): a stated limitation of this slice, for the settings track.
- **Glyphs are judged against Android's own rendering** of the expected face and size, places checked apart against
  draft 4's numbers (within 1 px); Figma's export is recorded, not judged: `diff.py` FAIL, mean 7.578, 6.97 % over 12
  (Figma rasterises text heavier; the pill's round ends differ by a quarter-pixel crop offset). Agreed with Codex.
- **A pre-existing engine bug fixed:** a node measures the children it composed (the KDL status bar crashed the
  launcher at every start since 13a-2); `component-measure` fails before and passes after.

## D-106: The island is pure black (2026-11-12, owner) [owner]

On the S24 the pill covered the camera but its #0A0A0A, the design language's background, is lit on an OLED screen, so
the camera showed through it (owner: "the background is not fully black, therefore the camera is visible"). The island's
fill is #000000 (pixels off), the one place the design language's near-black gives way; everything else keeps #0A0A0A.
When the default theme supplies the island's tokens, its fill stays true black. Figma's draft 4 still says #0A0A0A.

## D-107: The island sits 4 dp from the top (2026-11-12, owner) [owner]

Draft 4's 8 dp gap put the pill's bottom at 104 px on the S24, a pixel past the 103 px Android reserves for the status
bar, and the camera (centre 51.5 px down) in the pill's upper half. The owner set `place-gap` to 4 dp: the pill spans
about 4-36 dp, its centre at 20 dp on the camera's 19.6 dp, its bottom about 94.5 px, inside the bar. The owner judges it
on the phone. Making Android's bar taller in other apps was looked at: without root only the developer option's fake
cutouts change it (Tall: 48 dp), each drawing a black cutout of its own and moving what Android reports as the camera.

## D-114: A cutout gap on a whole pixel (2026-11-12, lead) [team]

`place-gap` rounds to a whole pixel of the screen (halves up). The island's 4 dp is 10.5 px on the S24: the pill's top
edge was a half-lit row and its text half a pixel low (the island proof: time ink 1.21 px off). Now 11 px. The 4 dp
change (D-107) had been checked with the fast gate only; this proof caught it.

## D-115: The island's media state moves springy and staged (2026-11-12, owner) [owner + lead]

The owner, on the S24: the first version's motion was "basically non existant" and the bars "weren't even moving". The
bars were a bug (the shade fades the KDL bar in with `animate()`, so the engine took it for unseen; visibility now follows
the view's real alpha). The owner chose "springy and staged":
- the pill opens on `spring(0.45, 0.75)`, a slight overshoot;
- the time leaves in 90 ms, then the cover, the title and the bars arrive at 70, 120 and 170 ms;
- closing, the pieces fade together in 100 ms and the time returns after 110 ms.
For it the engine gained a presence `delay(Nms)` (0..1000 ms; a motion still waiting carries no speed; motion reduced
ignores it), and a box that clips keeps wider children whole, centred by its alignment (the 236 dp media row was
squeezed into the 132 dp pill). Owner: "the animations are better". Known: a long title scrolls only a little ("fine for
now").

## D-116: Codex out for the week: merge and work solo (2026-11-12, owner) [owner]

Codex's weekly usage is spent (protocol §8). The owner chose to merge E2.14b into `engine` without Codex's sign-off and
for the lead to carry on alone, each step checked the same way (tests, emulator proofs, the full gate, the owner on the
S24). Codex reviews all of it when it is back; what it finds is fixed then. Codex's sub-tasks of T-013 (presence delay,
the size motion's layout) were done by the lead.
