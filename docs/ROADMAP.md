# Roadmap

Rules:
- One active milestone at a time.
- Only the owner adds, removes or reorders milestones.
- Agents update Status, Started, Finished and tick a checkbox only when that criterion is verified.
- Dates are ISO (YYYY-MM-DD). `/progress` computes velocity from them, so keep them honest.
- Lines marked `ASSUMPTION` are drafts for the owner to confirm or strike.

## Definition of done (applies to every milestone)
- Every acceptance criterion is met, each with an automated test or a recorded manual verification (on-screen
  criteria: emulator screenshots looked at, then the owner's S24 log or words)
- `scripts/check.sh` is green
- reviewer verdict is APPROVE and qa-tester verdict is PASS (or PASS_WITH_MANUAL_CHECKS that the owner completed)
- The polish pass was run for anything that changes the screen
- No known regressions; any open bugs are logged in the Bug backlog
- `docs/DECISIONS.md` updated if a design choice was made

## Milestones

| ID | Name | Status | Started | Finished |
|----|------|--------|---------|----------|
| M0 | Safety net | DONE | 2026-10-22 | 2026-10-22 |
| M1 | Regression and smoothness baseline | ACTIVE | 2026-10-22 | |
| M2 | Pixel shade layout signed off | TODO | | |
| M3 | Theme builder: look | TODO | | |
| M4 | Theme builder: motion and layouts | TODO | | |
| M5 | Onboarding without adb | TODO | | |
| M6 | Smoothness to zero | TODO | | |
| M7 | v1 release candidate | TODO | | |

Status values: TODO, ACTIVE, DONE, BLOCKED.

### M0: Safety net
Goal: make it cheap to know whether a change broke something, on the Windows dev machine.
Acceptance criteria:
- [x] `scripts/check.sh` (via `scripts/check.local.sh`) runs the debug build, Android lint and `testDebugUnitTest` with
      JDK 17 from Git Bash, and passes on the current `main`. Lint uses a committed baseline (`lint-baseline.xml`) so only
      new issues fail
- [x] The Stop hook (`.claude/hooks/stop-check.sh`) runs `scripts/check.sh` correctly from Claude Code on Windows
      (verified 2026-10-22: a deliberately failing unit test turned the gate red and the hook blocked the stop)
- [x] Stale worktrees are pruned, the empty `lc/` is removed, and `CLAUDE.md.kit-new` is merged into CLAUDE.md (or
      dropped) with the owner's OK
- [x] Known existing bugs are listed in the Bug backlog below, with repro steps where known

### M1: Regression and smoothness baseline
Goal: know where each core journey stands, so a regression shows up as a number or a failed step.
Acceptance criteria:
- [ ] `docs/JOURNEYS.md` lists a short manual regression script per core journey (steps plus the expected result), so the
      owner can run it in 10 minutes after any build
      Status 2026-10-22: `docs/JOURNEYS.md` written (slice 1, reviewer APPROVE, QA AC1/AC2 pass); waits for the owner's timed run
- [ ] Each existing S24 scenario script (`scenario_*.sh`) is run on one build, and its missed refreshes per animation are
      recorded in `docs/PERF_BASELINE.md` with the build sha, the date and the phone's temperature
- [ ] An emulator smoke script installs the build, checks "gesture strip on" in the log, opens and closes an app, and opens
      and closes each panel. It exits non-zero on failure
      Status 2026-10-22: `tools/smoke_emulator.sh` built (slice 1). Reviewer APPROVE; QA round 4 FAIL as given (5 of 9
      runs on an overloaded emulator; 13 of 13 on fresh ones). Not ticked until the hardening task (B-022 to B-026) passes QA
- [ ] Every journey step that fails or misses refreshes becomes a Bug backlog entry with a severity

### M2: Pixel shade layout signed off
Goal: the Pixel (Android 17) shade is a finished v1 layout, not a work in progress.
`ASSUMPTION`: step 4 stages 1-8 of `docs/SHADE_PIXEL_EVALUATION.md` are done, and what is left is the S24 check plus fixes.
Acceptance criteria:
- [ ] Every remaining item in `docs/SHADE_PIXEL_EVALUATION.md` step 4 is either done or moved to the Parking lot by the owner
- [ ] On the S24, the Pixel shade opens, both stages, edit page, media and notifications work over home and over apps
      (owner confirms), and switching between the iOS and Pixel layouts in the settings needs no restart
- [ ] The shade scenario (`scenario_shade.sh`) under the Pixel layout shows no more missed refreshes than under iOS 27
- [ ] Polish pass run on every Pixel shade element, with its findings fixed or logged

### M3: Theme builder: look
Goal: a themer makes a new theme in the app, without editing JSON.
`ASSUMPTION`: the builder lives in the settings app, and a new theme `extends` an existing one, holding only what differs.
Acceptance criteria:
- [ ] Create a theme from any existing theme, name it, and it appears in Look's theme list
- [ ] Edit colours (including Material You references), materials/glass, shapes and fonts per element, with a live
      preview, then save. The file lands in `files/themes/<id>.json` and passes the theme check at load
- [ ] Rename, duplicate, delete, and export or import a theme file (local file, no sharing service)
- [ ] Unit tests: a theme the builder wrote round-trips (load, save, load) with no change, and an invalid edit is refused
      with a message

### M4: Theme builder: motion and layouts
Goal: the builder also covers motion presets and each element's layout options.
Acceptance criteria:
- [ ] Save the current motion edits as a new named preset (`files/motion/<id>.json`), which appears beside the bundled presets
- [ ] Pick the transition styles (opening, closing) and the per-moment speed and bounce inside the builder, seen on the samples
- [ ] Layout choice and options per element (status bar, shade, recents, drawer, home) are reachable from the builder, and
      layout choices stay in `setup.json`, never in a theme
- [ ] Unit test: a preset the builder wrote passes the motion roles check (every role present, curves valid)

### M5: Onboarding without adb
Goal: a fresh user goes from install to the full experience on their own.
Acceptance criteria:
- [ ] The first start walks through: set as home, notification access, accessibility service, Shizuku (install, start
      through wireless debugging, grant), and each step is checked and shows its state
- [ ] Without Shizuku the app works as a plain launcher, and the paid features say what they need
- [ ] On a factory-fresh emulator, following only the screens (no adb) reaches gesture nav and our shade
- [ ] Every system change made during onboarding is undone by Restore system

### M6: Smoothness to zero
Goal: meet the v1 smoothness bar.
Acceptance criteria:
- [ ] Every scenario in `docs/PERF_BASELINE.md` shows 0 missed refreshes on the S24 (warm, interleaved runs, temperature noted)
- [ ] The App Switcher's 6-8 ms SurfaceFlinger composite (B-004) is addressed or shown not to cause missed refreshes
- [ ] The first open after an UPDATE of each surface shows no frame over 16 ms (warm-ups cover it)

### M7: v1 release candidate
Goal: the ship bar in `docs/SPEC.md`, verified.
Acceptance criteria:
- [ ] Polish pass recorded for every screen and element in `docs/JOURNEYS.md`
- [ ] Zero open P0/P1 bugs in the backlog
- [ ] The owner runs all journeys from `docs/JOURNEYS.md` on a CI build and signs off
- [ ] Licences page in the app (Inter, AOSP icons, Material Symbols)

## Bug backlog
Format: `- [B-001] severity (P0 to P3), area, one-line symptom. Repro: steps. Status: open/fixed`
Seeded from CLAUDE.md's open items. Severities are drafts (`ASSUMPTION`); the owner adds what they see on the phone now.
- [B-001] P2, shade, replying to a notification in place: the keyboard sits under our overlay. Repro: reply from a banner/NC. Status: open
- [B-002] P2, shade, the stock status bar's chips (call timer, privacy chip, charging pill) show under our bar on the Android 17 emulator; no flag hides them. Repro: emulator, a call or plug-in. Status: open
- [B-003] P2, status bar, no call capsule of our own (iOS's green). Repro: an ongoing call. Status: open
- [B-004] P2, switcher, SurfaceFlinger composite takes 6-8 ms on the S24 while the App Switcher is open, so one late composite repeats a frame. Repro: `scenario_switcher.sh`. Status: open
- [B-005] P3, links, in-app links still use the stock animations. Repro: open a link from an app. Status: open
- [B-006] P2 (ASSUMPTION), tooling, tools/reinstall.sh, scenario_library.sh and scenario_switcher.sh were CRLF on disk (likely carriage-return errors under Git Bash). Repro: `git ls-files --eol | grep 'w/crlf.*eol=lf'`. Status: fixed in M0
- [B-007] P2 (ASSUMPTION), tooling, scripts/check.sh and stop-check.sh were mode 644 in git: on a Linux/cloud checkout the Stop hook skipped the gate silently. Repro: `git ls-files -s scripts .claude/hooks`. Status: fixed in M0
- [B-008] P1 (ASSUMPTION), repo, the unpushed local main held a checkpoint commit (c4fa8da) that turned 206 files to CRLF, and had split from origin/main by 3 commits. Repro: `git diff --stat 1d16f45 c4fa8da` vs `--ignore-cr-at-eol`. Status: fixed in M0 (branch rebuilt on origin/main; local main still points at the old commits until fast-forwarded)
- [B-009] P3 (ASSUMPTION), compatibility, lint reports 58 NewApi errors (minSdk 29): possible crashes on Android 10-13; v1 targets only the S24. Repro: lintDebug. Status: open, baselined
- [B-010] P3 (ASSUMPTION), shade, QsHeaderBlock.kt reads dataNetworkType without a permission check (caught by try/catch: the network type may be empty). Repro: lint MissingPermission. Status: open
- [B-011] P2 (ASSUMPTION), shade, launching an app from a notification uses the app's own transition, no card from the platter. Repro: tap a notification in NC or a banner over home. Status: open
- [B-012] P2 (ASSUMPTION), shade, the stock SystemUI notification launch animation may still play with animation scales at 0 (CLAUDE.md says re-test). Repro: tap a notification with our shade on, S24. Status: needs re-test
- [B-013] P3 (ASSUMPTION), blur, a Samsung dim-blur stress run dropped to 60 Hz at the strongest level, first use had a 125 ms hitch; may be obsolete (One UI 9 uses the view blur). Repro: needs a fresh S24 check. Status: needs re-test
- [B-014] P3 (ASSUMPTION), status bar (emulator only), our charging capsule (57 px) would not cover Android 17's full-height charging chip; related to B-002. Repro: emulator, plug-in. Status: open
- [B-015] P3 (ASSUMPTION), tooling, the Stop hook's 40-line tail shows lint's generic explanation and Gradle's "Try:" block, not the failing test or the lint file:line. Repro: add a new lint error and a failing test, run the hook. Status: open; on 2026-10-22 a failing test alone did show its name and file:line in the tail (the lint half is unchecked)
- [B-016] P3 (ASSUMPTION), tooling, new lint warnings pass the gate silently (only new errors fail, per D-010); the roadmap's M0 wording says "only new issues fail". Repro: add `Build.VERSION.SDK_INT >= 21` in a new file, run the gate. Status: open (see Q-002)
- [B-017] P3 (ASSUMPTION), tooling, the Stop hook skips the gate when a source file is renamed into docs/ (awk keeps only the new path of `R old -> new`). Repro: `git mv` a .kt file to docs/X.md, run the hook. Status: open
- [B-018] P0 (ASSUMPTION: seen on the emulator only), gestures, after an install the gesture strip sometimes never comes back: the log shows `[a11y] service connected`, `[flags] ... stock gestures blocked`, then 3 ms later `stock gestures on`, and no `gesture strip on`; the service stays bound and nothing recovers until the accessibility setting is toggled. Looks like the attach/detach race GestureNav.detach comments on. Repro: `tools/smoke_emulator.sh` repeatedly (2 of ~12 runs on 2026-10-22 failed at `strip`). Status: open
- [B-019] P2 (ASSUMPTION: emulator, scripted swipe), gestures, a 150 ms bar swipe to 72 % of the height after a launch was read as a cancelled close (`[nav] back to the app`) in 1 of 6 manual and 2 of 9 smoke runs; longer swipes always closed. May be a close-threshold sensitivity issue or only the emulator's coarse injected events. Repro: `input swipe W/2 H-12 W/2 H*0.72 150` right after opening Clock. Status: open (the smoke uses a 220 ms swipe to 45 %)
- [B-020] P2 (ASSUMPTION), gestures, Back from the side edges is Android's own gesture with its stock arrow: a stock seam next to our gesture set (SPEC: one gesture set everywhere). Repro: swipe from a side edge in any app. Status: open
- [B-021] P2 (ASSUMPTION: emulator, seen in screenshots), home widgets, an app widget that is not set up (Calendar's "Sign in") sits on a white square larger than its rounded card, and the square shows through Notification Center's sheet while the rest of home does not. Repro: emulator home with the Calendar widget, then open Notification Center (tools/shots/qa_home_after_run1.png, smoke_nc.png). Status: needs a look
- [B-022] P3 (proposed), tooling, tools/smoke_emulator.sh prints `ok home` while a bar swipe has opened the App Switcher (an overlay, so HomeActivity keeps the focus); the next step then acts on the deck and fails at the wrong step. Repro: smoke on a loaded emulator (QA round 4, runs 2-3). Fix: fail or recover on a new `[switcher] open from home` line after the home and cleanup swipes. Status: open
- [B-023] P3 (proposed), tooling, the smoke's failure cleanup swipes blindly: it left the deck open (run 1), and right after an install, before the strip was back, a swipe launched Chrome from the dock. Repro: QA round 4, the forced md5 mismatch. Status: open
- [B-024] P3 (proposed), tooling, the smoke's preflight refuses our accessibility service in Android's short form (`dev.launcher.app/.NavAccessibilityService`, which Android writes itself when it rewrites the list) and its hint then appends a duplicate. Status: open
- [B-025] P3 (proposed), tooling, the smoke's end step fails on any crash-buffer line naming dev.launcher.app (including the :shell process) without printing them, so the evidence is lost at the next run's clear. Repro: QA round 4, run 4 (cause unknown). Status: open
- [B-026] P3 (proposed), tooling, the smoke is reliable only on a healthy emulator: 5 of 9 passes on one up ~1 h, swapping, 150-200 ms frames, a vendor uwb service crash-looping; 13 of 13 on fresh ones (round 3 QA 8/8, orchestrator 5/5 on 2026-10-22). The fixed 220 ms bar swipe and 40 s settle bound are fragile under load. Status: open

## Parking lot
Ideas that are not in scope. The owner decides if and when they become milestones.
- Online theme gallery and sharing (non-goal for v1)
- After v1: phones other than the S24 (re-run the key probes on other devices)
- After v1: a One UI shade layout (spec in `docs/PLAN_LAYOUTS_THEMES.md` D)
- After v1: Play Store release, Play Billing paywall, store policy review (theme scripts, self-granted permissions, silent updater)
- Layout builder for the shade's layout files (step 5 of `docs/SHADE_PIXEL_EVALUATION.md`), unless M4 should include it
- Accessibility requirements (TalkBack labels for custom-drawn views)
- App name
