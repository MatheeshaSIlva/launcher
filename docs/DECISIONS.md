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
