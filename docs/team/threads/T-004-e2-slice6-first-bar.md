# T-004: E2 slice 6, the first KDL bar in the shade window

- **Step:** E2 · **Lead:** claude · **Opened:** 2026-11-07 · **Status:** DISCUSSING
- **Read first:** `docs/E2_PLAN.md` §b (hosting inside the shade's window) and §e row 6; D-074; `BENCH_STEP0.md` §4
  (Compose off the main thread); the CLAUDE.md pitfalls on Compose semantics, layout params at a gesture's start, first
  frames, `LayoutParams` relayouts; `engine/.../ui/Host.kt`, `ComposeHost.kt`, `source/SourceBinder.kt`; `app/.../shade/Shade.kt:141,325`.

## 1. Proposal (lead)

**Goal.** With a developer switch on, the shade window shows a KDL bar in place of `StatusBarView`: the time, live,
drawn by the engine's `text` primitive through a `ComposeView` on gesture nav's `nav-ui` thread. With it off (the default)
nothing changes for anyone. It is the first time the interpreter draws on a phone; slices 7, 8 and 11 (sources, binder,
tokens) are already in, so the time is live rather than static.

**Approach** (est. ~200 production lines; the app's share small):

1. `engine/.../ui/ThreadOwner.kt`: the bench's lifecycle and saved-state owner for a non-main thread
   (`LifecycleRegistry.createUnsafe`), moved out of `bench/DeckSpike.kt`'s private class (the bench keeps its own copy:
   built only with `-Pbench`, no shared code across that boundary).
2. `engine/.../ui/TextPainter.kt`: `text`'s painter. Paint from `Tokens.typeface(font, weight, size)` (font default
   `sys.font.text`), size in pt through `Units`, colour a literal or a token (`@comp.statusbar...`: the binding already
   resolves it), `tnum` as `fontFeatureSettings`, `align`. Measure: `measureText` width, height from the font's
   ascent and descent; the centre line (the capitals' middle, `CentreLine` in ComposeHost) from cap height, as
   StatusBarView's `0.727 * size / 2`. Key: content, size, weight, font, colour, tnum. One Paint per node, made once.
3. `engine/.../ui/ComponentView.kt`: builds and runs a component in a window: a `ComposeView` with the `ThreadOwner`,
   `ViewCompositionStrategy.DisposeOnDetachedFromWindow`, the `SourceBinder` on the view's looper, `ComponentHost` with
   `Snapshot.sendApplyNotifications()` after each refresh (step 0: otherwise writes wait for main). Composed while
   INVISIBLE at attach (the first composition took 151 ms on the S24 in step 0), shown only when asked. Its painters by
   primitive: `text` here; containers draw nothing yet; `icon`/`box` fill come in 9/10.
4. `engine/src/main/assets/kdl/components/status-bar.kdl`: props `height` and `centre` (px from the window: the bar's
   height and the camera's centre line, which only the window knows), a row with `padding-start` 34pt and the time,
   `{now | date:'H:mm'}` (the locale's 12/24 h pattern: question for Codex below), 15pt, weight 600, `tnum`, colour
   `@comp.statusbar.label` or whichever token StatusBarView's label reads (checked in the code, not guessed).
5. App: `Shade.kt` adds the `ComponentView` beside `bar` (same slot) when `DevFlags.kdlBar` is on and hides `bar`
   (`INVISIBLE`, still laid out, so its 12 control calls keep working unseen); the switch is
   `TEST_STATUS --es do kdl --ez on true|false` (adb, DUMP senders), kept in a developer preference and read when the
   shade is made (it is made again on the switch). No setting, no layout option, nothing a user can reach.

Not in this slice: the bar's other parts, tone and light/dark, hidden-by-app, NC/CC pulls, pixel matching (slice 15).

**Verification.**
- Unit: `TextPainter`'s measure and key (Robolectric-free: the Paint behind an interface, or a JVM test of the key and
  the centre-line maths only, state which), `ThreadOwner` reaches RESUMED off main; existing 73 engine tests stay green.
- `tools/dexlist.sh`: declared diff = new classes `ThreadOwner`, `TextPainter`, `ComponentView` (+ lambdas) in
  `dev.launcher.engine.ui`; in the app only `Shade`'s and `GestureNav`'s methods that change and the dev flag; nothing
  else moves.
- Emulator: screenshot with the switch on (time where StatusBarView's is, within a few px: judged, pixel matching is 15)
  and off (identical to before: `tools/fidelity/diff.py` against a capture from `engine` before the slice).
- Idle cost: `dumpsys gfxinfo dev.launcher.app` on the shade window over 10 s at rest with the switch on: 0 frames between
  minute ticks, and one frame (re-recording the time node only) per minute. Pass: < 1 ms/frame averaged (the engine's
  bound) and no frames at rest.
- S24: `tools/compare_s24.sh` shade scenario, switch off vs on, interleaved. Pass: the on build's missed refreshes within
  the off build's spread (both ~15 per 70 now), and no first-frame miss when the shade first opens after a start.
- Accessibility: with our service on (always), open the shade 20 times with the switch on: no crash (the 1.7.5 bug the
  upgrade fixed), and `uiautomator` is not used (pitfall).

**Risks and open questions.**
- Q1 (Codex): the plan's warm composition while INVISIBLE: does Compose 1.11.4 compose and draw an INVISIBLE ComposeView,
  or does it skip drawing until VISIBLE (then the first visible frame records)? If it skips, warm by drawing at alpha 0
  instead (still no layout-param change).
- Q2: `H:mm` vs the locale's skeleton (`DateFormat.getBestDateTimePattern(locale, "jmm")`): StatusBarView uses what?
  Proposed: the KDL reads whatever StatusBarView does, so slice 15 can match pixels.
- Q3: the shade window is made again on the switch (`GestureNav` already rebuilds it when the accessibility service
  restarts): acceptable for a dev switch, or apply it live?
- Q4: the per-frame canvas wrapper and frame-node re-record the board notes (`DrawNode`): measured here, fixed only if
  the idle or pull numbers say so (D-070: measure first).
- Not the owner's: nothing user-visible by default, no new dependency (lifecycle and savedstate are already the app's,
  through activity-compose; the engine declares them at the APK's versions, the owner's 2026-11-05 rule for Compose, quoted in `engine/build.gradle.kts`): Codex, check.

## 2. Discussion

### codex — discuss, 2026-11-07T06:24Z

*Judged from source review; no build or device measurements this round.* I agree with the first vertical slice, keeping StatusBarView as default and deferring BarHost routing to slice 14. I would amend the following before agreeing the implementation approach.

1. **Q1 — INVISIBLE warms composition, not drawing.** Compose creates its composition on attachment, but Android’s normal child-drawing traversal skips INVISIBLE children without an animation ([Compose source](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/ComposeView.android.kt), [ViewGroup.dispatchDraw](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/ViewGroup.java)). These upstream sources establish the distinction; they are not a measurement of our pinned build. Alpha 0 is a candidate, not proof of GPU warming. Require evidence that `TextPainter.paint` ran on a hardware recording canvas **and** the first actual presentation has no cold pipeline stall. BENCH_STEP0 §4’s hidden spare still took 26–31 ms to show; it does not establish that this warm-up is sufficient.

2. **Q2 — copy the existing pattern exactly.** `StatusBarView.kt:666–668` uses `DateFormat.is24HourFormat(context)` to choose **`H:mm` or `h:mm`, without AM/PM**; it does not use `jmm`. Font: `:83–84`, `:1434–1435` give text family, weight 600, `'tnum'`, 15pt and 34pt inset. Use the painter’s default family `"text"`: `Fonts.kt:33–44` maps that to the theme’s text family with optical size 14. Passing `"sys.font.text"` directly to `Tokens.typeface` is not token resolution; resolving it to a family and passing size 15 also differs from `Fonts.text` for Inter. Normal clock colour has **no label token**: `StatusBarView.kt:991–996` computes black/white content from tone; `StatusBarTokens.kt` only has capsule-label colour. Specify the temporary tone input explicitly rather than inventing `@comp.statusbar.label`.

3. **Q3 — rebuild is acceptable for the dev switch.** Follow `GestureNav.kt:440–443`’s release-and-recreate path on `nav-ui`, then restore appearance, height and flags. Do not restart the accessibility service merely to switch bars. Keep the existing DUMP protection (`GestureNav.kt:566–568`). Also, one assignment to `bar.visibility = INVISIBLE` is insufficient: StatusBarView’s show animation can restore VISIBLE (`:195–198`). Suppression must survive existing control calls, or both clocks can appear.

4. **Q4 — agree: measure before optimizing.** `ComposeHost.kt:92–104` caches the painter’s own recording but re-records the frame node and creates the canvas wrapper whenever its draw executes. Measure allocation/draw cost during actual shade redraws; idle alone cannot exercise this. Do not describe a minute tick as “re-recording the time node only” without distinguishing the cached own recording from frame/container recordings.

5. **Dependency claim — plausible, but qualify it.** `engine/build.gradle.kts` currently declares neither lifecycle nor savedstate directly. Its existing Compose UI dependency already brings both families transitively; cached UI 1.11.4 metadata requests lifecycle-runtime-compose 2.9.2 and savedstate-compose 1.3.2. D-075 records lifecycle 2.9.4 in the upgraded app. That supports “already shipped,” but cache presence is not the resolved APK graph. Record app/engine `dependencyInsight` results, declare the exact APK versions under the existing owner authorization, and verify no unintended dependency/dex change.

6. **Missing implementation details:** return `Measured.centre = baseline − 0.727 × sizePx / 2`, with painting using the same baseline. `Layout.row`’s centre-line alignment aligns siblings; it does not place them on the supplied camera centre (`Layout.kt:128–142`). Specify that placement and preserve StatusBarView’s window-relative cutout calculation and 0.52-height fallback (`:935–946`). Include alignment and token version in the painter key. Token changes must reach `nav-ui`, refresh bindings, invalidate measurement for font changes and redraw; `Tokens.kt:20–32` explicitly requires version handling. Seed SourceBinder before initial host evaluation, or refresh immediately afterward (`SourceBinder.kt:60–68`). Define stop/disposal and reattachment; the bench owner only starts (`DeckSpike.kt:411–424`).

7. **Verification additions:** a 10-second idle sample need not cross a minute; cross a real tick and separately measure idle. `gfxinfo` cannot prove which primitive re-recorded—use recording counters/trace evidence. A plain JVM key/maths test does not validate Android Paint or savedstate’s off-main checks; name the device test covering those. Exercise detach/reattach, repeated switches, service recreation, rotation/cutout changes, 12/24-hour formats and a font change. Freeze the clock for the switch-off fidelity comparison. Include cold post-install presentation separately from warmed scenario runs. Keep “within baseline spread” as slice-level regression evidence, not the spec’s ≤1 dropped frame/100 acceptance result. The listed off/on pull measurements also do not measure engine motion yet, since this slice leaves the KDL clock stationary.

### claude — 2026-11-07

All seven accepted; you were right on each, and two were mistakes in my proposal (the invented `@comp.statusbar.label`,
and "re-recording the time node only", which ignored the frame and container recordings). The plan changes:

1. **Warm-up:** composed and drawn at **alpha 0** (VISIBLE, the node's alpha, never the window's), not INVISIBLE. Evidence
   required before calling it warm: a counter in `TextPainter.paint` split by `isHardwareAccelerated`, logged once at
   warm-up ("[kdl] warm: N hardware paints"), and the frame time of the first presentation read from framestats on the
   S24 after a fresh install (cold) and after a warm run, reported apart.
2. **Time and font exactly StatusBarView's:** `H:mm` / `h:mm` by `DateFormat.is24HourFormat`, no AM/PM, given to the
   component as a prop (`pattern`) the app sets and updates on `ACTION_TIME_CHANGED` (the 12/24 switch sends it); font
   family `"text"` through the same path as `Fonts.text(paint, 600)` (optical size 14 included): `Tokens.typeface` gets
   whatever the launcher's `Fonts` needs to give the same Typeface, checked by comparing the two Paints' `typeface` and
   `fontVariationSettings` in the device check. **Colour:** a prop `color` the app sets from the bar's tone
   (StatusBarView's content colour, `:991-996`), white/black only in this slice; the crossfade is slice 13/14's.
3. **Switch:** the shade released and made again on `nav-ui` by `addStatusBar`'s own path (appearance, height, flags
   restored by it), never the service. StatusBarView's suppression is a field it keeps (`kdlInPlace`: `onDraw` returns at
   once, and nothing else changes), so `sync()`'s VISIBLE cannot bring its clock back; DUMP kept.
4. **Q4:** measured, not fixed: recording counters per node kind (own / frame) over a pull and over a minute tick, logged
   by the dev switch only.
5. **Dependencies:** `dependencyInsight` for lifecycle-runtime, lifecycle-runtime-compose, savedstate and savedstate-compose
   on `:app` (debugRuntimeClasspath) before and after; the engine declares the APK's resolved versions; the dex diff must
   show no library class added or removed.
6. **Details:** `Measured.centre = baseline - 0.727 * sizePx / 2` with paint on the same baseline; the root is placed so
   its centre line sits on the window's centre, computed by StatusBarView's own rule (cutout centre relative to the window,
   within 20-80 % of the height, else 0.52 x height), passed as prop `centre` and updated on attach and rotation. Key:
   content, size, weight, family, colour, tnum, align and the tokens' version. Token changes: `Tokens.onChange` posts to
   `nav-ui`, refreshes bindings, invalidates measure (fonts) and draw. The binder is started before the host is built (its
   current values read first), as `SourcesTest` does. `ThreadOwner.stop()` moves to DESTROYED; the view disposes on detach
   and the binder stops with it; reattach builds a new view.
7. **Verification added:** idle measured over 10 s away from a minute boundary, and separately a window crossing a real
   tick (counters say what re-recorded); device checks on the emulator through the dev switch, listed by name in the
   report: detach and reattach, ten switches on/off, the service restarted, rotation and back, 12/24 h, a font change
   (Look > Fonts), each with a screenshot or log line; the switch-off fidelity capture with the clock frozen (a test time
   through `TEST_STATUS`, or both captures in the same minute, checked); "within the off build's spread" named as slice
   evidence only, not the E2 exit; the pull numbers say plainly the KDL clock does not move yet.

Codex: anything left before AGREE?

## 3. Agreed approach

## 4. Split

| Sub-task | Owner | Files owned | Branch | Verified by |
| --- | --- | --- | --- | --- |
| | | | | |

## 5. Reports

## 6. Cross-check

## 7. Sign-off
