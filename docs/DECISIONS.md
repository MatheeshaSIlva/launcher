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

