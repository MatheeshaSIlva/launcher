# Audit D: system motion and chrome, iOS 27 profile (2026-10-28)

Scope: app open and close (from home, the dock, Spotlight, a notification), the home gesture and the card under the
finger, the App Switcher (deck, card look, swipe to dismiss, Clear All), quick switch along the bottom edge,
picture-in-picture, the gesture handle, the status bar (layout, slots, the charging moment, capsules in the Dynamic
Island's role, behaviour over apps, light and dark), lock and unlock arrival, and the settings app (studio, tiles, pages,
its own motion). Read-only audit of `main` at 494dd31; no device was used. The only new measurements are offline, from
iOS 27 Simulator recordings already in the repo (below).

Ratings: **ok** (matches the reference as far as it is known), **off** (close, details differ), **wrong** (visibly
different), **missing** (not built). Severity follows the backlog: P1 a core-journey gap the owner will see at once, P2 a
visible gap, P3 polish. Confidence of the iOS claim: **H** measured or Apple's own words, **M** reliable secondary or
an older iOS, **L** inferred. "To capture" = not verified on iOS 27; capture it in the Simulator (`ios-reference/`)
before building.

## Sources and how far to trust them

| Source | Used for | Confidence |
| --- | --- | --- |
| Our iOS 27.0 Simulator captures (`ios-reference/out/pass2`, `pass4`: `test16_appOpenClose`, `test22_appSwitcher`), measured in `docs/IOS27_MOTION.md` and again for this audit (below) | Launch card shape and pace, the card under a slow drag | H for frames caught (the CI Simulator has no GPU and drops frames early in a launch) |
| Apple's iOS 27 UI kit (`docs/IOS27_KIT.md`) | Status bar geometry, Dynamic Island size, home indicator | H |
| WWDC18 "Designing Fluid Interfaces" (session 803, [developer.apple.com/videos/play/wwdc2018/803](https://developer.apple.com/videos/play/wwdc2018/803/)) | Gesture physics: rubber banding, momentum projection, when to bounce, "motion stretching" on launch and close, the multitasking pause detected from the finger's deceleration, two-axis home gesture | H for iOS 12-era SpringBoard; M that it still holds in iOS 27 |
| WWDC25 "Meet Liquid Glass" (session 219, [developer.apple.com/videos/play/wwdc2025/219](https://developer.apple.com/videos/play/wwdc2025/219/)) | How glass reacts to touch | H for the principle, no numbers |
| Macworld on the iOS 26 glass toggle ([macworld.com](https://www.macworld.com/article/2891233/this-liquid-glass-toggle-is-a-window-into-apples-broken-design-process.html)); Apple Developer Forums on `.glassEffect(.interactive())` stretching while dragged ([thread 848646](https://developer.apple.com/forums/thread/848646)) | Toggle and slider knobs that lift into glass and stretch | M |
| iPhone Life on the Dynamic Island ([iphonelife.com](https://iphonelife.com/content/how-to-use-dynamic-island)) | The island's charging alert (level shown on plug-in, green battery, brief) | M |
| 9to5Mac on iOS 26 beta 5 ([9to5mac.com](https://9to5mac.com/2025/08/05/ios-26-beta-5-changes/)) | A bouncier lock screen | L-M (beta) |

### New evidence from this audit: the launch card's shape (iOS 27, offline)

`tools/ios_ref/measure_app.py` over `pass2` and `pass4` `test16_appOpenClose` (10 launches of Settings, 402 x 874 pt
frames; script and output in the session scratchpad):

- **The box keeps no stretch you could see in the second half of a launch.** From about half size on, the white card's
  width and height progress stay in step (height minus width progress between -0.04 and +0.02 in every frame of all ten
  launches). Counted from the 64 pt icon, though, the height lags: at 202 x 324 pt the width is 41 % of the way and the
  height 32 %; at 346 x 705, 83 % and 79 %. So **the card widens before it lengthens** (a mild squash early in the
  flight), and the joint fits agree: width 0.136 / 1.34, left 0.135 / 1.33, against height 0.180 / 1.12, top 0.205 / 1.03
  (response s / damping; rms 3 pt horizontally, 8-12 pt vertically; noisy, as `IOS27_MOTION.md` says). The first frames
  (icon up to ~200 pt) are never caught.
- **Pace**: half size to full in 0.25-0.33 s; ours is one spring of 0.42 / 0.92 for both axes (`ios27.json`
  `motion.app.open`; `GestureNav.kt:3854-3855`). 9to5Mac reported iOS 26 beta 6 launches ~150 ms quicker, which fits.
- **The card under a slow drag** (`pass2`, 41.9-42.6 s): the aspect stays 2.17-2.18 throughout (uniform scale, no
  stretch), 0.657 of the screen 218 pt up. Ours gives 0.634 there (`GestureNav.kt:2581`, `SCALE_RANGE` 0.62,
  `SCALE_LENGTH` 0.28): close.
- WWDC18's "motion stretching" (the icon "elastically stretches down to become the app", "up" on close) is a stretch of
  the *content* per frame along the motion, a 60 Hz-era trick; a bounding box cannot show it. To capture: a GPU recording
  at 120 fps (a Mac's Simulator, not CI) and a look at the content inside the card.

## Squash and stretch: where iOS has it and where we do

The owner's report ("iOS has a lot of squash and stretch animations, which is missing") is right for this area, but it is
not one thing. What iOS stretches, by source:

| Where | iOS (source, confidence) | Ours | Verdict |
| --- | --- | --- | --- |
| Content past an end (lists, pages, the deck, panels) | Rubber band: content follows ever less, springs back (WWDC18, H) | Everywhere through `Motion.rubberBand` (`IosScroller.kt:173-182`, `DeckView.kt:967-970`, `Shade.kt:355-364`), on `motion.scroll.rubber-band` 0.55 | **ok** (translation only, as iOS's lists) |
| App launch and close | The card widens before it lengthens (measured above, M); content "motion stretching" along the flight (WWDC18, H then, to capture now) | One spring for width and height: the shape changes in lockstep, nothing stretches (`GestureNav.kt:3854-3855`, `2724-2725`) | **off** |
| A dismiss thrown with momentum | "A little bit of bounce and squish" at 80 % damping when the gesture carries momentum (WWDC18, H) | A close keeps at most 650 px/s of the throw and lands at the same pace however hard it was flicked (`RELEASE_CARRY`, `GestureNav.kt:344`, `3270-3276`); the deck's flick 0.35 / 1 | **off** |
| Liquid Glass you touch (toggles, sliders, buttons, the island) | "Gel-like flexibility", "flexing and energizing with light"; elements "lift up into Liquid Glass" on touch; Reduced Motion "disables any elastic properties" (WWDC25, H); a toggle's knob grows into a glass lens and stretches as it slides (Macworld, M); interactive glass stretches while dragged (forums, M) | Settings: a tap-only toggle whose knob slides on Control Center's toggle role, a slider knob that never lifts (`settings/Pages.kt:3238-3256`, `2028-2080`); no glass reacts to a touch with light | **missing** |
| Sliders pulled past an end | The slider grows longer and thinner (to capture; area A covers Control Center) | Control Center's sliders grow taller, never thinner (`ControlGridBlock.kt:1437-1439`) | off (area A) |
| The Dynamic Island's morphs (compact to expanded, charging, alerts, a banner leaving into it) | Elastic morphs that overshoot and settle (common knowledge, M; to capture: a Live Activity started by the RefHost app with ActivityKit) | No island; the charging capsule opens critically damped (`motion.statusbar.charge-open` 0.28 / 1); banners grow out of the camera on a bouncy spring (`motion.banner.in` 0.64 / 0.61, measured) | **off** / missing |

Conclusion: we have rubber banding but almost no *deformation*. The only shape that changes with motion today is
Control Center's slider height, the genie style (not iOS) and Notification Center's lens (optical). A single primitive
would cover most rows: `motion/Stretch.kt`, a velocity-to-(sx, sy) map that keeps area, capped, on new tokens
`motion.stretch.amount` (factor) and `motion.stretch.limit` (factor), with a moment-wide character slider ("Squish: none
to jelly") and switched off under Android's Remove animations (iOS's Reduced Motion does the same).

## Element table

Columns: L = Layout, K = Look, M = Motion, I = Interaction.

### 1. App open and close

| # | Element | L | K | M | I | What iOS does (source, conf.) | Ours (where) | Fix: tokens and motion roles |
|---|---|---|---|---|---|---|---|---|
| 1.1 | Launch from a home icon | ok | ok | **off** P2 | ok | Card grows from the icon, width ahead of height (measured, M); half size to full in 0.25-0.33 s (measured, H); WWDC18 content stretch (to capture) | `beginLaunch`, `GestureNav.kt:3751`; one spring both axes, `motion.app.open` 0.42 / 0.92 (owner-tuned on the S24, "really smooth") | Split the size spring by axis: `motion.app.open` for width and position x, new `motion.app.open-height` for height and y (moment Opening apps); seed from the fits (width ~0.14-0.21 / 1, height ~0.18-0.21 / 1) and tune with the owner, as the open's present value was. Optional content stretch on `motion.stretch.*` |
| 1.2 | Launch card's corners | ok | **off** P3 | ok | ok | Continuous (squircle) corners on every card (kit, H) | `CardView.kt:151-153`: `Outline.setRoundRect` with the frame rounded to whole pixels (a plain arc; a slow flight steps a pixel at a time) | Outline path with `sys.shape.corner-smoothing` (Outline.setPath clips from API 33); keep float frames |
| 1.3 | Launch from the dock, from Spotlight | ok | ok | as 1.1 | ok | Same as from home (H) | Same path; a card grabbed mid-launch keeps its shape (`takeOver`, `GestureNav.kt:2248`) | As 1.1 |
| 1.4 | Launch from a notification or banner | ok | ok | to capture | ok | The app comes out of the platter (not captured: `IOS27_EVALUATION.md`) | `IOS27_EVALUATION.md` says fixed (platter grows into a card, `Shade.openFrom`); `ROADMAP.md` B-011 still says "the app's own transition, no card" | Re-test on the S24 and close one of the two records; capture iOS (`test42_ncTapOpen` found no platter in pass 7) |
| 1.5 | Close into the icon | ok | ok | **off** P2 | **off** P2 | A thrown close keeps the throw: faster flicks land sooner and squish a little (WWDC18, H); content stretches up (WWDC18, to capture) | `releaseHome`, `GestureNav.kt:2636`; position 0.5 / 0.92 and size 0.44 / 0.9 springs, the throw capped at 650 px/s and the landing pace fixed by design (`towards`, `3270-3276`) | Carry the full velocity into `motion.app.close-position`; cap only the size spring; the cap as a token (`motion.app.release-carry`, moment Going home) instead of `RELEASE_CARRY`; stretch along the flight on `motion.stretch.*` |
| 1.6 | Close of an app with no icon on home | ok | ok | ok | ok | Shrinks to the middle and fades (M) | `springFade`, `GestureNav.kt:3655-3662` | - |
| 1.7 | Icon landing and home coming forward | ok | ok | to capture | ok | Whether the landed icon or home overshoots: unknown | `motion.home.depth-close` 0.5 / 1, no overshoot | Capture `test16` closes with a GPU recorder (the CI one drops them) |

### 2. Home gesture and the card under the finger

| # | Element | L | K | M | I | What iOS does (source, conf.) | Ours (where) | Fix |
|---|---|---|---|---|---|---|---|---|
| 2.1 | Card size under the finger | ok | ok | ok | ok | Uniform scale, 0.657 of the screen 218 pt up (measured above, H) | `homeScale`, `GestureNav.kt:2581`: 0.634 there | Only tokens (2.6) |
| 2.2 | Release: home or back to the app | ok | ok | ok | **off** P3 | Decided from the projected end point (momentum projection with the scroll deceleration, WWDC18, H) | Fixed thresholds: > 350 px/s and > 30 dp, or > 20 % of the height (`GestureNav.kt:2639`, the same at `2537`) | Project `travel + v * rate / (1 - rate)` with `motion.scroll.deceleration` (as the deck's `motion.switcher.fling-projection` does) and commit past a token `motion.home-gesture.commit` (fraction of the height) |
| 2.3 | Dragged below where it started | ok | ok | ok | **off** P3 | Resists (rubber band) (L, to capture) | `homeScale` clamps travel at 0 and the full-size card follows the finger down 1:1 (`GestureNav.kt:2581`, `2613-2614`) | `Motion.rubberBand` on negative travel |
| 2.4 | The pause that opens the App Switcher | ok | ok | ok | **off** P2 | Detected from the spike in the finger's deceleration, "the faster you stop, the faster we can detect it", no timer (WWDC18, H) | A timer: the finger within 12 dp for 150 ms once 56 dp up (`watchForHold`, `GestureNav.kt:4376-4387`; `HOLD_RADIUS_DP` `5111`; `motion.switcher.hold` 150 ms) | Watch the deceleration of the touch samples (speed falling from > X to < Y within Z ms) and open at once; keep the 150 ms rest as the fallback for slow drifts; new tokens `motion.switcher.hold-decel` (dp/s²), moment App Switcher |
| 2.5 | Both axes at once (arc swipe) | ok | ok | ok | **missing** P2 | "Go horizontally between apps and vertically to go home", the two mixed: on the way home you can peek and switch (WWDC18, H) | The axis is chosen once at the start (`GestureNav.kt:2196-2209`); a home drag follows x but its release never switches app; a quick switch ignores y (`dragSwitch`, `4132-4138`) | In `releaseHome`, a sideways throw whose projected x passes a third of the width switches to the neighbour on the switch springs; in `DRAG_SWITCH`, upward travel past the home commit hands over to the home card (scale from the switch card) |
| 2.6 | Its numbers | - | - | **off** P3 | - | - | `SCALE_RANGE`, `SCALE_LENGTH`, `DRAG_DEPTH_RANGE`, `RELEASE_CARRY` are literals (`GestureNav.kt:344-347`) | Tokens `motion.home-gesture.min-scale`, `.scale-length`, `.depth-range` in moment Going home |

### 3. App Switcher

| # | Element | L | K | M | I | What iOS does (source, conf.) | Ours (where) | Fix |
|---|---|---|---|---|---|---|---|---|
| 3.1 | Deck layout | ok | ok | ok | ok | Apple's iOS 27 illustration: cards 0.68 of the screen, centre 51.5 %, focus left 22.5 % (M; `test22_appSwitcher` has a screenshot never measured) | `DeckView.kt`, `motion.switcher.card-scale`, `.card-center-y`, `.focus-left` | Measure `test22` screenshots against the illustration |
| 3.2 | Card chrome: icon, name, shadow | **off** P3 | ok | ok | ok | Icon above the card's left edge, name beside it (kit/illustration, M) | Literals: icon 28 dp, 14 dp in, 12 dp above, name 15 dp (`DeckView.kt:447-462`), shadow 26 dp at 45 % (`1033-1034`) | `comp.switcher.card.icon-size`, `.icon-inset`, `.icon-gap`, `.label.type`, `.shadow.*` (only Clear All is tokenised, `SwitcherTokens.kt`) |
| 3.3 | Entry, scroll, reflow | ok | ok | to capture | ok | Not measured (`test22` drags exist) | Judged: `motion.switcher.enter` 0.38 / 0.9, `.scroll` 0.4 / 1, `.reflow` 0.38 / 0.92 | Fit them from `test22` (`springfit.py`) |
| 3.4 | Past the ends | ok | ok | to capture | ok | Whether the stack spreads (spacing stretches) or translates as one | The deck translates on a rubber band (`DeckView.kt:967-970`) | Capture a pull past the newest card |
| 3.5 | Swipe a card away | ok | ok | ok | ok | Card follows up 1:1, resists down, flies off keeping the flick (WWDC18, H) | `DeckView.kt:738` (rubber band down), `releaseLift` `985-989`, `motion.switcher.flick` 0.35 / 1 | A little stretch along the flick on `motion.stretch.*` (to capture first) |
| 3.6 | Clear All, the app menu | ok | ok | ok | ok | Not in iOS (Android's convenience, deliberate) | `comp.switcher.clear.*` | - |
| 3.7 | Opening by a slow release mid-screen | - | - | - | **missing** P3 | A slow release half way up opens the switcher (M) | "Not yet" (`docs/HOME_FEATURES.md` §3) | Comes with 2.4: a release whose projected end is mid-screen and slow opens the deck |

### 4. Quick switch along the bottom edge

| # | Element | L | K | M | I | What iOS does (source, conf.) | Ours (where) | Fix |
|---|---|---|---|---|---|---|---|---|
| 4.1 | Cards side by side | **off** P3 | ok | ok | ok | Slight shrink, a gap, the cards also follow the finger up and down (to capture: no capture of a bottom-edge swipe exists) | Gap 16 dp, shrink 6 % over 60 dp, literals (`GestureNav.kt:4136-4140`); y fixed at the centre | Tokens `motion.switch.gap`, `motion.switch.shrink`; follow y (2.5); add a capture `test_quickSwitch` |
| 4.2 | Swiping toward no app | ok | ok | ok | **off** P3 | Rubber band (WWDC18, H) | Linear 0.25 of the finger, unbounded (`GestureNav.kt:4135`) | `Motion.rubberBand(dx, sw)` |
| 4.3 | Commit | ok | ok | to capture | **off** P3 | Projection (WWDC18, H) | 30 % of the width or 600 px/s past 20 dp (`GestureNav.kt:4155-4156`); `motion.switch.commit` 0.35 / 1 judged | Projected x, token `motion.switch.commit-at` |

### 5. Picture-in-picture

| # | Element | L | K | M | I | What iOS does (source, conf.) | Ours (where) | Fix |
|---|---|---|---|---|---|---|---|---|
| 5.1 | Closing an app into its mini player | ok | ok | ok | ok | The app shrinks into the player (H) | Card flies into the player, cropping on the video (`flyIntoFloating`, `GestureNav.kt:1751`; S24 0-2 dropped, `docs/STATUS_BAR.md`) | - |
| 5.2 | Moving the mini player: drag, throw, corners, stash, pinch | ok | **off** P3 | **off** P3 | **off** P3 | Thrown to the corner its momentum projects to, response ~0.4 critically damped (WWDC18, H); stashed at an edge with a tab | Android's (SystemUI): its own fling, stash and corner radius; windows from the shell fail, so it cannot be ours | Accepted seam: say so in the polish pass; match its corners where we draw the hole (`pipCornerRadius`) |
| 5.3 | Turning upright from landscape | - | - | **off** P3 | - | Turns with the card (H) | Android's jump at the turn (pitfall: cannot be animated by us) | Accepted |
| 5.4 | Mini player over the App Switcher | ok | ok | ok | ok | Floats over the deck | `SwitcherActivity` / `SwitcherHost` handover | - |

### 6. Gesture handle (home indicator)

| # | Element | L | K | M | I | What iOS does (source, conf.) | Ours (where) | Fix |
|---|---|---|---|---|---|---|---|---|
| 6.1 | Its colour | ok | **wrong** P2 | ok | ok | Dark over light content, light over dark, changing with what is behind (H) | Always white at 60 % (`GestureNav.kt:1078`): faint over white apps | Tokens `comp.gesture.pill.light` / `.dark`; the tone from the source the status bar uses (`refreshAppearance`, and the card's arriving app), crossfaded on `motion.statusbar.tone` |
| 6.2 | Where it shows | ok | **wrong** P2 | ok | ok | Not on the home screen (iPhone X and later; H); on the lock screen at y 840 (kit) | Shown on home too (only a shade panel or a turn hides it, `applyPill`, `GestureNav.kt:1109-1113`) | Fade it out while `homeVisible` and no card is up (`strip.hide` / `strip.show`); the strip still takes the swipe |
| 6.3 | Size and place | **off** P3 | ok | ok | ok | 134-144 x 5 pt, ~8 pt above the bottom (kit, H) | 108 x 4 dp, 8 dp above (`GestureNav.kt:1081`), literals | `comp.gesture.pill.width`, `.height`, `.bottom` |
| 6.4 | During the gesture | ok | ok | to capture | ok | It belongs to the app's window: it moves and fades with the card (M) | Stays fixed at the bottom over the card | Fade it with the card's scale once 2.x lands; capture first |
| 6.5 | Auto-hide in full-screen video | - | - | - | **missing** P3 | Fades after a few seconds when the app asks (`prefersHomeIndicatorAutoHidden`, H) | Never hides there | Hide after `motion.strip.idle` (ms) while BarWatch says the app hides its bars; back on a touch |

### 7. Status bar

| # | Element | L | K | M | I | What iOS does (source, conf.) | Ours (where) | Fix |
|---|---|---|---|---|---|---|---|---|
| 7.1 | Time | **off** P3 (owner's call) | ok | ok | ok | SF Pro Semibold 17, centred in the left ear (kit, H) | 15 pt, weight 600, equal margins of 34 pt (Matheesha, 2026-10-06; `StatusBarView.kt:84`, `1434-1435`) | Keep; make it a token (7.3) |
| 7.2 | Levels group | **off** P3 | ok | ok | ok | Cellular, Wi-Fi, battery 7 pt apart, ending at x 362.4 (kit, H) | Gap 4.5 pt, icon 14.5 pt (`StatusBarView.kt:1437-1438`) | Check the gap against the kit; tokens |
| 7.3 | Its tokens | **off** P2 | **off** P2 | - | - | - | Only 6 colours are tokens (`StatusBarTokens.kt`); every size, gap, the capsule's 96 x 28 dp and the sharing capsule are literals (`StatusBarView.kt:1434-1492`) | `comp.statusbar.time.size`, `.time.weight`, `.side`, `.icon.size`, `.icon.gap`, `.capsule.width/height/radius`, `.sharing.*`; Pixel needs them too (B-053) |
| 7.4 | Slots arriving, leaving, moving; digits; levels | ok | ok | ok | ok | Icons appear and the rest close up (M) | Each slot on springs (`motion.statusbar.appear` 0.38 / 0.78 etc.), digits roll | Whether iOS rolls the minute: to capture |
| 7.5 | Notification icons on the left | **off** P3 | ok | ok | ok | iOS shows none (H) | Android's icons (deliberate, class comment) | Owner's call; an option `comp.statusbar.notification-icons` |
| 7.6 | Charging moment | **off** P3 | ok | **off** P3 | ok | On an island iPhone the island grows to show the level and a green battery for a moment (iPhone Life, M), elastically (to capture on a real iPhone: the Simulator cannot charge) | A capsule grows out of the battery on the right (it must cover One UI's chip by 70 ms), `motion.statusbar.charge-open` 0.28 / 1 (no overshoot), content 0.52 / 0.8, close 0.42 / 1 | Keep the place (One UI's chip); let the left (leading) end overshoot and settle on a bouncier content role while the right end keeps its cover deadline; Bounce of the Charging moment then has something to scale |
| 7.7 | Capsules in the island's place (timer, media, navigation, call, recording) | **missing** P2 | - | - | **missing** P2 | Live Activities in the island: compact leading and trailing views, minimal when two, expanded on a long press, morphing elastically between them (Apple HIG Live Activities, M; to capture with a Live Activity in RefHost) | None; we also hide One UI's live chips (`quickstar_indicator_hide_ongoing_chip` = 1, `StockBarSettings`), so a running timer or navigation has no sign in the bar; a call has none either (B-003) | A capsule around the S24's punch hole fed by ongoing notifications (`Notifs`: chronometer, media session, call style, navigation): `comp.statusbar.island.*`, roles `motion.statusbar.island-open`, `.island-morph`, `.island-close` in a new moment "Live activities"; one plan with B-003 |
| 7.8 | Screen sharing | ok | **off** P3 | ok | ok | On island iPhones recording shows in the island (L, to capture); the red time capsule is the older iPhones' | Red capsule around the time (`setSharing`) | Fold into 7.7 once the island exists |
| 7.9 | Over apps: hiding and tone | ok | ok | ok | **off** P2 | The tone follows the app at once (H) | Hidden at once (BarWatch), but the tone is polled: up to 2.5 s late (`docs/STATUS_BAR.md`, last item) | Read the tone again on the content-changed events the service already gets (throttled), and at a card's start (done) |
| 7.10 | Light and dark | ok | ok | ok | ok | Crossfade (M) | `motion.statusbar.tone` 220 ms | - |
| 7.11 | Banners into and out of the camera | ok | ok | ok | ok | Grow out of the island, bounce, leave into it in ~0.15 s (measured, H) | `motion.banner.in` 0.64 / 0.61 (measured), out 0.3 / 1 | Whether the banner and island merge as one blob on the way (to capture) |

### 8. Lock and unlock

| # | Element | L | K | M | I | What iOS does (source, conf.) | Ours (where) | Fix |
|---|---|---|---|---|---|---|---|---|
| 8.1 | The lock screen itself (clock, unlock prompt, always-on) | **wrong** P2 | **wrong** P2 | **wrong** P2 | **wrong** P2 | The cover sheet: our Notification Center's look, swiped up to unlock (H) | One UI's keyguard (no root: it cannot be replaced); our Notification Center comes over it only when pulled (`Unlock.kt` asks One UI's prompt) | Accepted platform seam; write it down as such in the spec's polish pass, keep our cover sheet one pull away |
| 8.2 | Unlock: following the finger | - | - | - | **wrong** P2 | Home is revealed under the sheet as the finger lifts it (H) | One UI's unlock plays, then home's arrival at `USER_PRESENT` (non-interactive) | Same seam |
| 8.3 | Arrival | ok | ok | to capture | ok | Home's items come in from larger (H); iOS 26 made the lock screen bouncier (9to5Mac, L-M) | Home zooms from 1.18 and items bloom from 0.8 outward on `motion.arrival` 0.62 / 1 (`HomeScreen.kt:1460-1490`, moment Unlocking) | Capture (Simulator: lock, swipe up) and fit; audit C proposes the same |

### 9. Settings app

| # | Element | L | K | M | I | What iOS does (source, conf.) | Ours (where) | Fix |
|---|---|---|---|---|---|---|---|---|
| 9.1 | Studio: tiles, cards growing into the stage | ok | ok | ok | ok | Our own design (Figma draft D, V5), not iOS Settings | `settings/Stage.kt`, `motion.settings.open` 0.5 / 0.86, `.focus` 0.55 / 0.9 | - |
| 9.2 | Going back with a finger | - | - | - | **missing** P2 | Every pushed page follows a swipe back from the edge (UIKit, H; iOS 26 also from the content, M) | Back only plays the pop; the swipe back was removed (2026-10-11, the owner: it fired while scrolling, `docs/PLAN_SETTINGS.md`) and Android's predictive back does not reach Compose here (pitfall) | Edge-only (left 20 pt) interactive pop on `motion.sheet.push`, which avoids the scroll conflict; ask the owner first |
| 9.3 | Switches | ok | **off** P2 | **missing** P2 | **off** P2 | The knob grows into a larger glass lens while touched and stretches as it slides; it can be dragged (Macworld, forums, M; WWDC25 "lift up into Liquid Glass", H) | Tap only; the knob slides on Control Center's `motion.cc.toggle`; knob 0.6 of the width and its shadow literal (`settings/Pages.kt:3238-3256`) | `comp.settings.toggle.knob-lift` (scale), `.knob-glass` (material), stretch on `motion.stretch.*`; roles `motion.settings.toggle`, `.knob-lift` in moment Settings app; drag to toggle |
| 9.4 | Sliders | ok | **off** P2 | **missing** P2 | **off** P3 | Thumb turns into a clear lens on touch, stretches with speed; resists past the ends (M) | Knob 13 pt, track 4 pt literals; clamps at the ends (`coerceIn`); no lift (`settings/Pages.kt:2028-2080`) | `comp.settings.slider.*`; rubber band past the ends; lift and stretch as 9.3 |
| 9.5 | Press on glass buttons | ok | ok | **missing** P3 | ok | Light from under the finger spreads through the glass and onto glass nearby (WWDC25, H) | Press scales in drawing; no touch light anywhere (`MaterialPainter`) | A touch light in `MaterialPainter` (touch point and energy uniforms) on new role `motion.press.glow`; then every glass (dock, Control Center, settings) |
| 9.6 | Pulled past the top | ok | ok | **off** P3 | ok | A large title grows a little when pulled down (UIKit, H) | Pages rubber-band (`settings/Scroll.kt`); no title grows | Scale the page title with the overscroll (`motion.scroll.rubber-band`) |
| 9.7 | Home gesture over the settings | ok | ok | ok | ok | A panel's close (owner's choice) | `OwnScreenSwipe`, `GestureNav.kt:2508-2540` | - |

## Gap counts

Counting each element row once at its worst rating (rows that are only "to capture" are listed separately):

- **P1**: 0
- **P2**: 13 (1.1 launch shape and pace, 1.5 close ignores the throw, 2.4 timer pause, 2.5 no arc swipe, 6.1 pill
  colour, 6.2 pill on home, 7.3 bar not tokenised, 7.7 no island capsules, 7.9 tone polled, 8.1/8.2 One UI lock screen
  and unlock (accepted seam), 9.2 no swipe back, 9.3 switches, 9.4 sliders)
- **P3**: 19 (1.2 card corners, 2.2 thresholds, 2.3 drag below start, 2.6 gesture literals, 3.2 deck chrome literals,
  3.7 slow-release trigger, 4.1 quick-switch layout, 4.2 its rubber band, 4.3 its commit, 5.2 Android's mini player,
  5.3 its turn, 6.3 pill size, 6.5 pill auto-hide, 7.1/7.2 bar geometry (owner's call), 7.5 notification icons,
  7.6 charging bounce, 7.8 sharing capsule, 9.5 touch light, 9.6 title stretch)
- **To capture in the Simulator** (captures exist for the first three): app close landing (`test16`, needs a GPU
  recorder), App Switcher entry, scroll and ends (`test22`), launch content stretch (`test16` at 120 fps on a Mac),
  quick switch (new test), unlock arrival (new), a Live Activity's island morphs (new, RefHost with ActivityKit), a
  notification opening its app (`test42`), whether the minute rolls.

## Proposed backlog items (ranked)

- [B-new] P2, gestures (iOS), app launch: width and height grow on one spring, where iOS 27's card widens before it lengthens and fills from half size in 0.25-0.33 s (ours 0.42 / 0.92; fits width ~0.14 / 1.3, height ~0.18 / 1.1 from `pass2`/`pass4` `test16_appOpenClose`). Repro: open Settings from home with `motion.debug.slow` 4, compare the card's box per frame with the capture. Status: open
- [B-new] P2, gestures (iOS), a close lands at the same pace however hard it is flicked (the throw capped at 650 px/s, `RELEASE_CARRY`); iOS keeps the throw's momentum and gives a thrown dismiss "a little bounce and squish". Repro: flick home slowly, then hard; compare landing times in the frame log. Status: open
- [B-new] P2, gestures (iOS), the App Switcher opens only after the finger rests 150 ms within 12 dp; iOS detects the stop from the finger's deceleration, at once for a sharp stop. Repro: swipe up and stop dead; time from the stop to the haptic. Status: open
- [B-new] P2, gestures (iOS), no arc swipe: the axis is chosen at the touch, a home drag thrown sideways never switches app and a quick switch ignores the finger going up (WWDC18: the two axes mix). Repro: swipe up from the bar and curve right, release. Status: open
- [B-new] P2, gesture handle (iOS), the pill is always white at 60 % (faint over white apps) and shows on home, where iOS's adapts to what is behind and is absent on the home screen. Repro: open a white app (Settings, light), then go home. Status: open
- [B-new] P2, status bar (iOS), no capsule for ongoing activities (timer, media, navigation, call) while One UI's live chips are hidden by us, so a running timer has no sign in the bar; iOS shows them in the Dynamic Island with elastic morphs. Repro: start a timer in Clock, go home. Status: open (plan together with B-003)
- [B-new] P2, settings (iOS), switches and sliders never lift into glass nor stretch (iOS 26+: the knob becomes a glass lens and stretches as it moves); switches are tap-only and sliders clamp at their ends. Repro: Settings, any switch or slider, press and drag. Status: open
- [B-new] P2, settings (iOS), no swipe back that follows the finger (removed 2026-10-11 for firing during scrolls); Back only plays the pop. Repro: push a page, swipe from the left edge. Status: open (owner's decision needed: edge-only)
- [B-new] P2, status bar, the bar's geometry is not tokens (time size and weight, margins, icon size and gap, the capsules: `StatusBarView.kt:1434-1492`); only 6 colours are, so no theme or layout can restyle it (B-053). Repro: read `StatusBarTokens.kt`. Status: open
- [B-new] P2, status bar, the tone over an app is polled and follows a change up to 2.5 s late (`docs/STATUS_BAR.md`). Repro: scroll an app whose bar turns from light to dark content. Status: open
- [B-new] P3, motion (iOS), no squash-and-stretch primitive: nothing on screen deforms with velocity (only Control Center's slider height, area A); add `motion/Stretch.kt` on `motion.stretch.amount`/`.limit`, off under Remove animations. Repro: n/a (design gap). Status: open
- [B-new] P3, status bar, the charging capsule opens critically damped (`motion.statusbar.charge-open` 0.28 / 1); the island's morph overshoots, so the Charging moment's Bounce changes nothing on the opening. Repro: plug in on the S24, `TEST_STATUS --es do charge-frames`. Status: open
- [B-new] P3, gestures, the home gesture decides by fixed thresholds (350 px/s and 30 dp, or 20 % of the height) instead of the projected end point, and a drag below its start moves the full-size card 1:1 (no rubber band). Repro: drag the bar down; flick short and fast. Status: open
- [B-new] P3, gestures, quick switch: toward no app the card follows linearly at 0.25 (no rubber band, no limit); gap, shrink and commit thresholds are literals (`GestureNav.kt:4135-4156`). Repro: swipe right on the bar from the newest app. Status: open
- [B-new] P3, design system, the home gesture's and the deck's numbers are literals: `SCALE_RANGE`, `SCALE_LENGTH`, `DRAG_DEPTH_RANGE`, `RELEASE_CARRY` (`GestureNav.kt:344-347`), the deck card's icon, name and shadow (`DeckView.kt:447-462`, `1033-1034`). Status: open
- [B-new] P3, gestures, the card's corners are a plain round rect with the frame rounded to whole pixels (`CardView.kt:151-153`): no continuous corners, a pixel step in slow flights. Repro: slow motion 4, close an app, zoom a recording on a corner. Status: open
- [B-new] P3, glass, nothing lights under the finger (WWDC25: light "starting right under your fingertips" spreading through the glass and onto glass nearby). Repro: press the dock, a Control Center control, a settings glass button. Status: open
- [B-new] P3, App Switcher, no opening by a slow release mid-screen (`docs/HOME_FEATURES.md` §3, "Not yet"). Status: open
- [B-new] P3, gesture handle, never auto-hides in full-screen video as iOS's does when the app asks. Repro: full-screen YouTube, wait 5 s. Status: open
- [B-new] P3, docs, B-011 (notification launches use the app's own transition) contradicts `docs/IOS27_EVALUATION.md` (fixed: the platter grows into a card); re-test on the S24 and close one. Status: open
- [B-new] P3, settings, a page's title does not grow when the page is pulled past its top (UIKit's large title does). Status: open
- [B-new] P3, motion (iOS), App Switcher springs (enter, scroll, reflow, flick) and its ends are judged, never fitted though `test22_appSwitcher` exists; the close's landing, quick switch and Live Activity morphs have no capture. Status: open, to capture

Accepted seams to name in the polish pass, not bugs: One UI's lock screen and unlock (8.1-8.2), Android's mini player
physics and its turn (5.2-5.3).
