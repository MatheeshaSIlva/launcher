# Plan: B-037 step 2, the rest (Notification Center's commit and close, iOS layout)

Architect (Opus), 2026-10-28, on `ship/b037-step2` (main a4ae8b9 + the parked 2a1 and 2b, merge 79273d4). First in the
owner's order (D-047). Verdict READY. Track: **full pipeline**, not fidelity: iOS has no live reference (D-037) and the
Simulator captures (402 pt, no GPU, dropped frames) cannot be diffed against 1080 px frames; the acceptance criteria are
the numbers measured from them, checked by JVM tests reading the preset files (the `ClockDropTest` pattern) and on slowed
emulator recordings and traces. `diff.py` compares ours against ours only (nothing pops). Render nodes, cross-thread
pictures and the render thread (B-031): reviewer and QA run on Opus. Decisions: D-048.

Owner's answers (2026-10-28): over the lock screen the close shows One UI's lock screen live below the rising sheet, dimmed
by the same 30 % and clearing as the sheet leaves (no cover); 2c1 ships on its own with a short stand-in fade for home's
unlock arrival and the app's arrival (2c2, 2c3 later).

## Slice 2-0: verify the merge, finish 2b (~30 lines)

Main since 5bd170a touched only the qs tiles, `Controls.kt` (torch), the `qs_tiles` hook in `Shade.kt`, `FontsTest` and
the Pixel builder (B-060): nothing in `NotificationCenterView`, `nc/*`, `LockClockBlock`'s fonts, the motion files or
ios27.json. 2b's follow-ups read as present (`ClockDropTest` reads ios27.json's `motion.nc.clock-drop`, `motion.nc.date-in`,
`comp.nc.clock.drop`; `LockClockBlock.moved()`'s comment on `invalidateOutline()`; the drop token's src cites pass 11's
~196 pt). Record each as present or fixed under "Record"; measure what 2a1 and 2b do on the emulator.

- AC0.1 `scripts/check.sh` green; `DEVICE=emulator-5554 tools/smoke_emulator.sh` exits 0 (iOS and Pixel paths); the
  installed APK's md5 is ours.
- AC0.2 Commit over home at slowmo 20: the numerals' top first seen >= 40 pt above rest, reaching rest; no frame shows square
  bottom corners while the edge is above its last 3 radii; the content (the wallpaper's features) moves <= 1 px between
  frames while the edge moves.
- AC0.3 Perfetto (unslowed): after the release, no full-screen "Drawing 0 0 W H" of NC's layer; per-frame redraws only the
  clock's band while the drop runs.
- AC0.4 The three follow-ups recorded present or fixed.

## Slice 2a2: the commit reveal (~200 lines)

iOS: over home (pass 10 test81, F563-F595) home's icons inside the outline zoom about the screen centre, ~1.24x at +35 ms,
~2x and half faded at +62 ms, gone by ~90 ms; the dock below the edge stays live; the lock wallpaper, bar and clock appear at
once. Over an app (pass 11 test83) the app inside fades with no zoom, ~52 % at +83 ms, ~10 % at +170 ms. The sheet slides on
to the bottom in ~115 ms (pass 10).

Ours: the content whole at alpha 1 from the commit's first frame under a new `shade/nc/NcRevealPicture` (shade root, above
`nc`, below `ncEdge`), drawing what was behind clipped to the moving outline (`nc.edgeY`, `nc.edgeRadius`), let go:
- over home: `HomePicture.content` (icons and widgets, no wallpaper) in a fixed screen-size RenderNode, scaling from 1 to
  past `comp.nc.glass.reveal-zoom` (2.0, measured, pass 10) about the centre and fading, on the new `motion.nc.reveal-zoom`;
- over an app: a fresh kept snapshot (not stale), fading only, on `motion.nc.reveal` retimed to pass 11 (~200 ms);
- the lock screen: no picture, content whole at once (as now); no usable picture (stale, other orientation, none): today's
  content fade on `motion.nc.reveal`.

The picture's node is recorded and rendered while the finger holds the pull (off screen, as the content waits at
translationX = width), never in the commit's first frame; let go at the end, on `cleanUp`, and when a newer picture arrives
(keyed by identity). Pure schedule in `nc/NcReveal.kt`: `scale(k, zoom)`, `alpha(k)`, whether a picture may be used. The dim
below the edge holds through the landing (slice 1b's `ncDim.goTo(0, pull-undim)` in `ncCommit` moves to the landing).
`motion.nc.open` fitted to passes 10/11 with `tools/ios_ref/springfit.py` (today 0.44/1.0 judged, ~3x slower than iOS's
~115 ms); eased and material through their builders. Roles: `motion.nc.reveal-zoom` (new, editable, moment `nc`),
`motion.nc.reveal` retimed; `MotionRoles`/`MotionTokens.CURVES`, presets, `MotionSamples`. Token `comp.nc.glass.reveal-zoom`
in `NcTokens.ALL` and ios27.json via `build_ios27_theme.py`.

- AC1 `NcRevealTest` (preset values): over home scale(0) = 1.00, scale(35 ms) 1.15-1.35, scale(62 ms) 1.8-2.2 with alpha
  0.35-0.65, alpha <= 0.05 from 90 ms; over an app alpha(83 ms) 0.42-0.62, alpha(170 ms) 0.05-0.20, never rising.
- AC2 The fitted `motion.nc.open` from a standstill at 0.75 covers >= 90 % of the rest in 115 +- 30 ms (unit test).
- AC3 `diff.py` ours against ours, over home and over a test app: the last held frame against the first frame after
  `release`, inside the outline, within the self-diff tolerance (D-040).
- AC4 Slowmo 20 over home: icons grow about the centre and fade to nothing; the lock wallpaper and clock behind them from the
  first frame; the dock below the edge unchanged until covered; `frame_glitch.py` finds no 1-4 frame flicker in the sheet.
- AC5 Perfetto: the commit's first frame renders no new layer; the reveal node's layer rendered during the hold; "Scratch
  TextureRenderTarget" does not grow across 5 commits.
- AC6 Fallbacks: a stale or absent snapshot fades the content and never shows an old app picture; the lock screen: whole at
  once, no One UI lock screen shows, the clock drops; landscape app: the fallback fade.
- AC7 The dim below the edge unchanged from release until landed, no one-frame step; nothing undimmed after.
- AC8 A catch during the zoom lets it finish with no jump; a close ~50 ms after the commit takes the clock back up with no
  jump; a second pull after the close starts as a clear slab with a fresh picture.
- AC9 Eased and material presets and dark: AC4 holds; the settings show `reveal-zoom` in Notification Center's moment and
  its sample plays; `MomentsTest` and the role/token tests pass.

## Slice 2c1: the close (~200 lines)

iOS (pass 5 test31, pass 10, pass 11 test84): the sheet stays opaque with its corners, rim, lens band and home indicator;
its wallpaper drifts down ~14 % of the edge's travel; the numerals lift off within ~2 frames, off screen by ~300 ms; the
date and bar go within ~175 ms; below the edge the still wallpaper dimmed ~35 %, not the live home or app.

Ours: the close starts on Back, a tap-close, a drag up from rest, or a release that falls back after landing. `NcGlassEdge`
gets its lens picture again (the sheet's own wallpaper, `wall = true`), the edge chrome at the outline as it rises; the clock
lifts on `motion.nc.clock-lift` (new, editable, `nc`) by `comp.nc.clock.lift` (judged from pass 11: at least its rest top
plus its height); the date and bar fade on `motion.nc.date-out` (new, glue); the content layer drifts down by
`comp.nc.close.drift` (0.14 x the edge's travel, a node property). Below the edge over home and apps: the wallpaper cover
(`NcSlabWallpaper` generalised), dimmed by `comp.nc.glass.dim` (one dim token for pull and close), in on
`motion.nc.pull-dim-home`, out once the sheet is gone on `motion.nc.close-uncover` (new, glue): the stand-in until 2c2/2c3.
Over the lock screen (owner): One UI's lock screen live below, dimmed 30 %, clearing as the sheet leaves, no cover. A
reversal drops the clock again from where it is and clears the cover. Hook for B-116: all edge geometry goes through
`NotificationCenterView.updateEdge()`; no second path computes the edge.

- AC10 (unit) Lift offset 0 at t = 0, the numerals' bottom above the screen's top by 300 +- 50 ms; date and bar alpha <=
  0.05 by 175 +- 30 ms; drift = 0.14 x the edge's travel.
- AC11 Slowmo 20, closing over home and over an app: round bottom corners, rim and lens at the edge in every frame; below
  the edge the wallpaper at 65-72 % brightness with no icons or app; the cover fades with no one-frame step.
- AC12 Reversing mid-close brings the clock down from where it was, no jump, and clears the cover.
- AC13 Lock screen: One UI's lock screen below, dimmed 30 %, clearing with the sheet's departure, no pop of its clock.
- AC14 `smoke_emulator.sh` passes; the Pixel layout unchanged.

## Every slice, on the S24 (owner)

AC-S `tools/scenario_shade.sh` NC open and close, interleaved with the previous build: no more missed refreshes (target 0).
Never a capture of Notification Center.

## Out of scope

Squash and stretch (B-116), touch-reactive glass (B-066), Carrier, overpull, swipe-down Search, the hidden list; the app
darkening further below the edge on a commit (a P3 follow-up from pass 11); notifications moving on close (2c1b), the
arrivals after a close (2c2, 2c3); MaterialPainter, the pull itself, the 62.5 % threshold, flings; the Pixel shade; any
capture of Notification Center on the S24.

## Later (planned once 2c1 is on the S24)

2c1b notifications slide up with the edge 1:1 (pass 11 test85; GPU cost to measure); 2c2 home's unlock arrival after a
close over home; 2c3 the app's snapshot arriving at ~1.2x blurred, settling in ~0.4 s, handing over to the live app.

## Record (filled by the slices)
