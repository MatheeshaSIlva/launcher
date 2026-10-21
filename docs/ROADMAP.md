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
| M0 | Safety net | TODO | | |
| M1 | Regression and smoothness baseline | TODO | | |
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
- [ ] `scripts/check.sh` (via `scripts/check.local.sh`) runs the debug build, Android lint and `testDebugUnitTest` with
      JDK 17 from Git Bash, and passes on the current `main`. Lint uses a committed baseline (`lint-baseline.xml`) so only
      new issues fail
- [ ] The Stop hook (`.claude/hooks/stop-check.sh`) runs `scripts/check.sh` correctly from Claude Code on Windows
- [ ] Stale worktrees are pruned, the empty `lc/` is removed, and `CLAUDE.md.kit-new` is merged into CLAUDE.md (or
      dropped) with the owner's OK
- [ ] Known existing bugs are listed in the Bug backlog below, with repro steps where known

### M1: Regression and smoothness baseline
Goal: know where each core journey stands, so a regression shows up as a number or a failed step.
Acceptance criteria:
- [ ] `docs/JOURNEYS.md` lists a short manual regression script per core journey (steps plus the expected result), so the
      owner can run it in 10 minutes after any build
- [ ] Each existing S24 scenario script (`scenario_*.sh`) is run on one build, and its missed refreshes per animation are
      recorded in `docs/PERF_BASELINE.md` with the build sha, the date and the phone's temperature
- [ ] An emulator smoke script installs the build, checks "gesture strip on" in the log, opens and closes an app, and opens
      and closes each panel. It exits non-zero on failure
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

## Parking lot
Ideas that are not in scope. The owner decides if and when they become milestones.
- Online theme gallery and sharing (non-goal for v1)
- After v1: phones other than the S24 (re-run the key probes on other devices)
- After v1: a One UI shade layout (spec in `docs/PLAN_LAYOUTS_THEMES.md` D)
- After v1: Play Store release, Play Billing paywall, store policy review (theme scripts, self-granted permissions, silent updater)
- Layout builder for the shade's layout files (step 5 of `docs/SHADE_PIXEL_EVALUATION.md`), unless M4 should include it
- Accessibility requirements (TalkBack labels for custom-drawn views)
- App name
