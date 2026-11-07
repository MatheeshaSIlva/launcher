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

## 3. Agreed approach

## 4. Split

| Sub-task | Owner | Files owned | Branch | Verified by |
| --- | --- | --- | --- | --- |
| | | | | |

## 5. Reports

## 6. Cross-check

## 7. Sign-off
