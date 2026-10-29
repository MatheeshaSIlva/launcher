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

### Slice 2-0 (implementer, 2026-10-28; emulator-5554, Android 17)
- AC0.1: `scripts/check.sh` PASS on the merged branch before 2a2 (CHECK PASSED); `smoke_emulator.sh` SMOKE PASS (iOS and Pixel
  paths); the installed APK's md5 equals the local build's (cc37d829..., then the 2a2 build checked the same way).
- AC0.2 (slowmo 20, `tools/shots/b037s2_ac02.mp4`, frames in `tools/shots/b037s2_ac02/full`): the numerals' first frame shows
  them cut by the screen's top (their bottom ~130 px, rest bottom 375 px: first seen >= 90 pt above rest at 2.69 px/pt), then
  at rest; the bottom corners round in every frame (crops f19-f33); the content still: consecutive frames once whole differ
  by a brightness change only (best vertical shift 0 px; mean diff <= 1.3 grey levels, the list's overlay fading in). The
  emulator records ~4 frames a second, so "every frame" is every recorded frame.
- AC0.3 (unslowed, `tools/shots/b037s2_ac03.pftrace`): after the release, NC's layer is updated in each frame of the drop
  with 2 clears and 4 texture draws (0.8-2.3 ms "flush layers", two of 4.9 and 8.4 ms), no MaterialPainter pass: only the
  clock's and the date's nodes are drawn again. The window's own "Drawing 0 0 1080 2400" is every frame's (the whole window
  is redrawn each frame, as CLAUDE.md notes for the S24); the slice name cannot show the layer's damage rectangle.
- AC0.4: the three follow-ups are present (ClockDropTest reads the files; `LockClockBlock.moved()`'s comment; the drop
  token's src cites pass 11's ~196 pt). No change.

### Slice 2a2 (implementer, 2026-10-28)
- `motion.nc.open` fitted to pass 10 test81 F564-F573 (the edge per frame: 655, 676, 721, 765, 801, 814, 836, 850, 855,
  861 of 874 pt at 0, 35, 62, 93, 120, 150, 178, 188, 222, 232 ms), start velocity 0, critically damped: response 0.33 s
  (rms 4 pt, the release 5 ms after F564). `springfit.py`'s free fit took a release velocity (2032 pt/s, response 0.61,
  damping 0.86) that a release from a standstill does not have, so the fit was constrained (v0 = 0, damping 1).
  From a standstill at 0.75 it covers 90 % of the rest in ~0.20 s, not the 115 +- 30 ms AC2 asks: pass 10's own edge had
  covered 89 % at 188 ms (the "~115 ms" was the bulk of the slide, F566-F570). AC2 as written is not met; `NcRevealTest`
  checks the spring against the frames instead.
- `motion.nc.reveal` 200 ms, bezier (0, 0, 0.8, 1): the app's picture 51 % at 83 ms, 8 % at 170 ms (pass 11: ~52 %, ~10 %).
  `motion.nc.reveal-zoom` 90 ms, bezier (0.6, 0, 0.5, 0.7) with `NcReveal` (scale 1 + (zoom - 1) x 1.5 x k, alpha 1 - k^2):
  1.24x at 35 ms, 2.01x at 62 ms at 45 % opacity, 0 at 90 ms. Material: spatial fast, critically damped (0.222 s).
- AC3 (diff.py defaults, recordings at slowmo 20, the outline's inside rows 0-1660): over the app FAIL narrowly (28 of 288
  cells, overall mean 1.18, 0.0 % of pixels past 12, worst cell mean 2.6 against 2.0: the search field and the coloured
  icons, the snapshot against the live app); over home FAIL (54 cells, overall mean 0.51, 1.0 % of pixels, worst cell 4.4):
  home's recorded picture draws text and the glass numerals' shadows darker than the live home (no offset: best shift 0),
  a one-frame tone step on glyph edges at the commit. Two held frames diff to 0 cells; two frames after the commit to 0.
  `tools/shots/b037s2_ac3/{home,app}`.
- AC4 (`tools/shots/b037s2_home20.mp4`): home's widgets grow about the centre and fade out; the sheet's wallpaper, clock
  and platters from the first frame; the dock below the edge unchanged (mean diff 0.00 in rows 2100-2300) until the edge
  reaches it; `frame_glitch.py` 0 glitches (over the app too: `b037s2_app20.mp4`).
- AC5 (`tools/shots/b037s2_ac5.pftrace`): `drawLayer [nc-reveal]` is rendered in the pull's first frame (flush 17.4 ms with
  NC's own layers, emulator); the commit's frames render only NC's layer (the clock band, 0.7-3 ms). Scratch RenderTarget
  across 11 commits: 46.9-53.8 MB, back to 46.9, no growth.
- AC6: the lock screen (keyguard on, `b037s2_lock20.mp4`): "[nc] commit: the lock screen, whole at once", no One UI lock
  screen inside the sheet, the clock drops. Stale, absent and landscape pictures: covered by `NcReveal.usable` in the unit
  test only, not exercised on the emulator.
- AC7: the dock under the dim is unchanged from the release until the edge covers it (above); the dim clears once landed.
- AC8 (`b037s2_ac8.mp4`, over the app): a close 50 ms (nominal) after the commit rises with the clock, 0 glitches; a second
  pull after the close is a clear slab over the app with the dim (`tools/shots/b037s2_ac8_second.png`). A catch during the
  zoom: not exercised (the zoom runs on its own value, untouched by a pull).
- AC9: `MomentsTest` (Notification Center's moment now 19 roles), the role and token tests pass. Eased and material on the
  device, dark, and the settings' sample: not checked.

### Round 2 (review REVISE, 2026-10-28)
- Fix: `NcGlassProfile.contentWaits(progress, reveal, whole)`: with a picture (or the lock screen) the content is on screen
  from the commit's first frame, not parked until `ncReveal` first moves (a commit in Choreographer's input phase gave one
  frame of home's picture over live home). `revealUsable`'s dead `stale` parameter removed.
- AC3 re-measured (diff.py defaults, rows 0-1660, slowmo 20; `tools/shots/b037s2_r2_ac3/{home,app}`): over the app PASS
  (0 of 288 cells, overall mean 0.33; the next frame the same). Over home 17 of 288 cells: 12 are the sheet's glass clock
  entering at the top (rows 0-140, by design: it arrives at once and drops), with that band masked 5 of 264 cells, overall
  mean 0.14, 0.0 % of pixels past 12, worst 2.5: the Calendar widget's four top corners (home's recorded Picture loses the
  widget's outline clip, CLAUDE.md's "a Picture of a view is drawn in software") and one glass numeral's edge. Edge-only.
  The round 1 tone step on every glyph edge was the doubled frame this round fixed. `frame_glitch.py` 0 glitches on both
  (`b037s2_r2_home20.mp4`, `b037s2_r2_app20.mp4`).
- Orchestrator rulings: AC2 amended to "matches pass 10's landing, rms < 6 pt, 90 % in 170-240 ms" (the plan's 115 ms
  described the bulk of the slide): met (`NcRevealTest`). AC3's remaining edge-only tone difference waived while it stays
  edge-only (D-049). AC6: a landscape app's snapshot is used when the shade is landscape too (same orientation,
  `NcReveal.usable`), the fallback fade otherwise.

### Round 3 (QA FAIL, 2026-10-29)
- P2, the clock gone in the commit's first frame over home: QA's pull was held while home still played the new theme's
  reveal (the dock's icons change look in the held frames). Then the old look is a still picture over home, and the new
  items stay at alpha 0 until the reveal's front reaches them. `recordAsShown` records `fg` alone: home without its big
  clock (date and numerals) and with the small clock fading in. Fix: home marks a recording exact only when it is made at
  rest (`isIdle`, no look or wallpaper reveal), and any later draw of home outdates it (`HomeBridge.shown`, `ShownPicture`;
  `HomeActivity.recorded`, `drawWatch`). The shade takes home's picture only while it is exact, and checks again at the
  commit. Otherwise the content fades in, the fallback the plan already has. `ShownPictureTest`. Emulator, slowmo 5: QA's
  repro (`tools/shots/r3_theme/hi`, f031 held, f032 the first after the commit) shows the same clock in both, with the
  content fading in ("[nc] commit: no picture of what was behind"). The plain release over home still zooms ("home's icons
  zoom and fade"; f036/f037 of `r3_plain`, the clock and date in both). Over an app it fades (`r3_app`, f046/f047).
- P3, the settings' sample of `motion.nc.reveal-zoom`: a sample of its own (`Kind.ZOOM_AWAY`). Notification Center is
  whole underneath, and home's icons over it grow about the centre on `NcReveal.scale` with `comp.nc.glass.reveal-zoom`,
  fading on `NcReveal.alpha` (`tools/shots/r3_sample_stage.png`).
- Follow-up (orchestrator's call, "nothing pops"): since round 2 the content is whole from the commit's first frame, and
  the list's dim over the sheet's wallpaper (`list.dim`, 0.28 black) stepped in with it (r3_plain f036→f037: clock band
  212 → 163). At a commit whose content is whole (`revealWhole`) the dim now comes in from 0 on the reveal's own role
  (`motion.nc.reveal-zoom` over home with a picture, `motion.nc.reveal` otherwise): `NcReveal.dimFrom`,
  `NotificationCenterView.dimAtCommit`, called from `Shade.ncCommit`. A content fade-in is unchanged. Emulator, slowmo 5,
  one notification (a list): over home the held frame and the frames after it change gradually (`r4_home1`: 214.7 at the
  hold, then 202.6, 169.9, rest 166.8; the recorder catches few frames), over the app 2.9-3.7 grey levels a frame at the
  commit (`r4_app2`, settling at 170). Two notifications show as the collapsed stack, which has no dim (`r4_home`).

### Round 4 (QA FAIL on AC6, the owner chose a fourth round for that bug alone, 2026-10-29)
- P2, a pull over an app that had just opened drew its starting window (the gear on a plain colour) inside the sheet at the
  commit: Android reports the starting window as the app in front, so a fresh picture of it was handed over as not stale,
  and the out-of-date check (`contentChangedAt` against the picture's time) caught it only for later requests. Fix: the
  picture comes with a check the shade asks again at the commit (`BackdropSource.App.stillShown`, `App.outdated`,
  `GestureNav.shadePicture`); changed since, the picture is dropped and the content fades in (`Shade.ncCommit`, "[nc]
  commit: the app's picture is no longer what it shows"). `AppPictureTest`. Review: APPROVE. QA: PASS_WITH_MANUAL_CHECKS
  (3 of 3 cold starts of Settings with no splash after the commit, `tools/shots/qa4_bug1..3`; an app open 7 s fades,
  `qa4_app`; home zooms with the clock, `qa4_home`; smoke PASS).
- Left open: B-139 (the held slab shows the starting window through the hold and gives way in one frame at the commit),
  B-140 to B-144 (log cap, the sample's end, how often an app falls back on the S24, the gate's time bound under load,
  slow motion at the landing).
- Gate: `scripts/check.sh` green when run with the machine idle (fidelity_capture_test.sh times out under load: B-143).

### Slice 2c1 (implementer, 2026-10-29)
- Built: the close's four triggers through `Shade.ncCloseBegins()` (Back, a tap, a release that falls back: `close()` ->
  `ncCloses()`; a drag up from rest: the panel's `drag`, only when the finger moves up, decided before the sheet moves). On
  it: the lens takes the sheet's wallpaper at once (`NcGlassEdge.setPicture(atOnce)`), the numerals lift
  (`motion.nc.clock-lift`, bezier 0.4/0/1/1, 340 ms, `comp.nc.clock.lift` 250 pt), the date and the bar fade
  (`motion.nc.date-out`, 200 ms), the wallpaper drifts down by `comp.nc.close.drift` (0.14) of the edge's travel since the
  close began (`NcClose.drift`, `NotificationCenterView.updateEdge()`); below the edge the still wallpaper (`NcSlabWallpaper`
  cover) dimmed by `comp.nc.glass.dim`, out on `motion.nc.close-uncover` (250 ms, the stand-in) once the sheet is gone. Over
  the lock screen (owner) no cover, the dim clears with the sheet (`NcClose.lockDim`, the mode fixed at the close's start).
  A reversal (`ncCommit` -> `ncReopens`): the clock back on `motion.nc.clock-drop` from where it is, the date in, the cover
  out; the drift goes back with the edge until it lands (`NcClose.drifts`). Going under Control Center is no close
  (`ncGoesUnder`: the dim, and a close's cover, out on `motion.nc.pull-undim`).
- Deviations (D-051): the drift moves the wallpaper's node only (`bgNode`, recorded with its top band mirrored above the
  screen; the whole view left a gap at the top and cut the lifting clock), and the platters' frost follows it (the sheet is
  recorded again while the drift moves: a recorded glass keeps the shader it was drawn with, no node property can move what
  it samples); a close from a landed sheet shows the cover and its dim whole at once (faded, the live dock showed under the
  rising edge); the lens bands carry the sheet's own darkness (`wallDarkness()`: a light frame ran round a darkened sheet).
- `motion.nc.close` not refitted: pass 11 test84's closes are drags that hold the edge at ~85-110 pt of 874 before the
  release, and what follows is dropped frames (44.16 -> 45.41 s, 57.36 -> 58.60 s) or 1-2 frames under the app's crossfade:
  no spring can be fitted (2-3 points over ~100 pt). Judged 0.38 s kept. With it the numerals' box stays above the edge (the
  closest ~19 pt at ~240 ms, computed); the glass's shadow below the box (~40 pt) is cut between ~200 and ~300 ms.
- Evidence (emulator-5554, slowmo 20, APK md5 e0412af2... installed and checked; round 1's recordings: `tools/shots/c1b_home`,
  `c1_app`, `c1_lock`, frames in each `full/`):
  - AC10: `NcCloseTest` (lift: the numerals' bottom past the top at ~301 ms; date under 5 % at ~172 ms; drift 0 at the
    close's first frame for any start edge); 354 unit tests pass.
  - AC11: over home and over an app the still wallpaper below the edge, no dock or app, at 0.691 of home's wallpaper
    (median of 57 flat patches, `c1b_home`); rim, round corners and lens at the edge (`c1c_home/edge_1200.png`,
    `edge_500.png`); the cover gives way after the close (`c1_home/tile05.png`). With two notifications fanned out
    (`r2_platters/plat_56.png`, `plat_56b.png`) the wallpaper's features run on into the platters' frost with no step.
  - A close ~50 ms after a commit (`r2_early`, frames 70-78): the wallpaper stays where it was at the close's first frame
    and drifts gradually; below the edge the cover comes in on `pull-dim-home` (the sheet was still landing).
  - A drag up from rest (`tools/kswipe.sh`, `r2_drag/m.png`): only the dimmed still wallpaper below the edge, no dock
    under the corners.
  - Control Center over a closing Notification Center (`r2_cc/m.png`): Control Center over the blurred home, no still
    wallpaper under it (the cover leaves on `pull-undim` as Notification Center goes under).
  - AC12 (`c1_rev`, `r2_rev/m.png`): the edge turns (585 px) and lands with no jump; the numerals come back from where they
    were, the date returns, the wallpaper drifts back, the cover clears.
  - AC13 (`c1_lock/m.png`): no cover; the live lock screen below, dimmed, the dim lightening as the sheet leaves.
  - The clock against the edge (`r2_platters/clock.png`, frames 84-109): the numerals' ink stays above the edge; the
    closest is ~77 px (~29 pt) as they leave the top; only the glass's shadow under them meets the edge.
  - AC14: `smoke_emulator.sh` SMOKE PASS (iOS and Pixel paths).
- Not checked: eased and material presets, dark mode, landscape, an unlock during a lock-screen close, the S24.
- Round 3 (review, one blocking issue): while the drift moved, `updateEdge()` recorded the sheet again but did not rebuild
  its outline (either/or), so after the lift the clip froze and the sheet showed below its rim at a close's tail, in slow
  drags and on a reversal's way back (slowmo 20 hid it). Now both. Unslowed on emulator-5554 (APK md5 5e9699c0...): a
  slow drag up from rest with the finger standing still over 0.5 s mid-way (`tools/kswipe.sh`, `r3_drag/edges.png`, frames
  34-95), a close's last frames (`r3_close/edges.png`, `late.png`, frames 9-22) and a reversal (`r3_rev/edges.png`, frames
  5-11): in every frame the sheet ends at its rim, the dimmed still wallpaper (or home, as a reversal clears the cover)
  below it.
