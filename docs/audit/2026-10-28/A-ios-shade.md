# Audit A: the iOS 27 shade layout (2026-10-28)

**Summary.** Our iOS shade is right about where things are and how they look at rest. The gaps are in motion and
interaction. Notification Center's commit and close are still the stopgap from B-037 step 1: a 70 ms fade, with no
reveal, no clock drop and no lens on the close. Step 2 is parked, unmerged, on `fix/b037-ios-glass-pull`. Control Center
has one page where iOS 18 and later have up to 15. The squash-and-stretch iOS shows (a banner shrinking under a pull,
Liquid Glass flexing when touched) is mostly missing.

Scope: the iOS layout of the shade (`assets/layouts/shade/ios.json`) on `main` at 494dd31. The B-037 branch was read
for what it adds, not judged as shipped. No device was touched.

## Sources and confidence

- **[SIM]**: our own iOS 27.0 Simulator captures (`ios-reference/out/`), the measurements in `docs/IOS27_MOTION.md`,
  `docs/IOS27_KIT.md`, `docs/IOS27_EVALUATION.md` and `docs/plans/B-037.md`. **High** confidence where it was measured;
  the Simulator has no GPU and the recorder drops frames (see IOS27_MOTION "Limits").
- **[SIM, this audit]**: frames I extracted for this audit with `tools/ios_ref/frames.py`, read by eye from contact
  sheets: pass 6 `test14_bannerPullDown` (37.9-43.5 s) and pass 9 `test46_ncHideShow` (the `nc-hidden` still).
  **Medium**: one run each, nothing measured.
- **[KIT]**: Apple's iOS 27 UI kit (Figma), through `docs/IOS27_KIT.md`. High for geometry, but it says nothing about motion.
- **[WWDC25]**: "Meet Liquid Glass" (session 219), Dynamics chapter. It describes the material as having "gel-like
  flexibility" and as "instantly flexing and energizing with light" when touched, "morphing" between controls, and menus
  where "the bubble simply pops open". These are principles, not numbers: **medium** for any one element.
- **[GUIDE]**: Apple's iPhone User Guide (iOS 12-15 pages on notifications) and MacRumors' iOS 18 Control Center guide
  (pages: swipe up and down, icons on the right that can be tapped or scrubbed, up to 15 pages, a page added with the
  circle at the bottom, a new page made when controls overflow). **Medium**: these sources cover iOS 18 and earlier,
  not 27.
- **[WEB-27]**: a June 2026 beta report (allthings.how; borncity.com). It says Notification Center moved to the
  top-left corner, the top centre now opens a Siri "Search or Ask" panel, and alerts "slide in from the left". This
  **contradicts** our pass-6 banner capture (banners come out of the island). **Low** confidence until captured.

Ratings: **ok** (matches), **off** (close, details differ), **wrong** (visibly different), **missing** (not built),
**?** (iOS not known well enough to judge: *to capture in the Simulator*).

---

## 1. Status bar on the shade

| Element | Layout | Look | Motion | Interaction | iOS (source, confidence) | Ours | Fix |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Bar over Notification Center | off | ok | ok | n/a | On commit, "Carrier" shows top left with the levels on the right; over home the bar goes at the first frame of a pull and stays over an app [SIM pass 10/11, high]. | The time fades as NC opens and nothing replaces it (`statusbar/StatusBarView.kt:998-1000`); the bar fades over home on `motion.nc.bar-fade` (an owner choice, D-035/Q2). | Draw the carrier name in the left slot while NC shows (`comp.statusbar.carrier.*`, fading in on `motion.statusbar.appear`). P3. |
| Control Center's status row | off | ok | ok | ok | "Carrier", then signal and Wi-Fi on the left, "100%" and the battery on the right, centred at y 104.2 [SIM, high]. | The bar's icons glide down into the row and the percentage appears (`StatusBarView.kt:1007-1040`); no carrier text (IOS27_EVALUATION "Not changed"). | The same carrier slot, gliding into the row. P3. |

## 2. Notification Center

| Element | Layout | Look | Motion | Interaction | iOS (source, confidence) | Ours | Fix |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **Pull (finger down)** | ok | ok | ok | ok | A clear glass slab with lens edges (~26 pt bottom band, ~15 pt sides, ~52 pt corners, tinted rim); 30 % dim below the edge; the edge follows the finger 1:1 and stays straight [SIM pass 10, high]. | B-037 step 1 on `main`: `shade/nc/NcGlassEdge.kt`, `NcGlassLens.kt`, `NcGlassProfile.kt`; dims on `motion.nc.pull-dim-home/-app`. Not yet checked on the S24 (B-037). | None beyond the S24 check. |
| **Commit (release opens)** | wrong | wrong | **wrong** | ok | The sheet keeps its rounded edge, rim and lens and slides on to the bottom (~115 ms) over still content. The wallpaper, "Carrier" and the bar arrive at once. Over home, the icons inside zoom ~1.24x then ~2x and fade by ~90 ms. Over an app, the app fades instead (~52 % at +83 ms). The glass numerals drop ~160-196 pt from above (~90 % in ~170 ms, a soft tail to ~400-570 ms) with the date riding them [SIM pass 10/11, high]. | `Shade.kt:778-782` (`ncCommit`): the content fades in on `motion.nc.reveal`, **70 ms** (`assets/motion/ios27.json`). Rim and lens fade on `1 - reveal` (`Shade.kt:831`), so the corners go square. The sheet is one layer moved by `translationY` (`NotificationCenterView.kt:207-215`), so the wallpaper slides with it. On the lock screen the content shows at once (`NcGlassProfile.kt:52`). **This is the owner's report 1.** | Ship B-037 step 2 as planned. 2a1: content still under a moving rounded outline, rim and lens until it lands (on the branch, `5b3c6c8`). 2a2: home's picture zooming (`comp.nc.glass.reveal-zoom` 2.0, `motion.nc.reveal-zoom`), or the app fading over an app. 2b: the clock drop (`comp.nc.clock.drop`, `motion.nc.clock-drop` 0.30/1.0, the date on glue `motion.nc.date-in`; on the branch, `4ccfea6`, parked). All in moment `nc`. |
| **Close (swipe up / Back)** | wrong | wrong | **wrong** | ok | An opaque sheet with its wallpaper still inside (drifting ~14 % of the edge's travel), keeping its rounded corners, rim, lens and home indicator. The numerals lift off within ~2 frames and the date and bar go within ~175 ms. Below the sheet is the dimmed wallpaper (~35 %) with no icons, not the live home or app. As the sheet leaves, home plays its unlock arrival (items from ~2-3x, ~270 ms), or the app comes in zoomed ~1.2x and blurred, settling in ~0.4 s. Notifications slide up with the edge [SIM pass 10/11 test83-85, high]. | A committed sheet closes as an opaque layer with a straight square-cornered bottom and no rim or lens (B-037 slice 1b deviation; `NcGlassEdge` drawn at `1 - reveal`). The wallpaper moves with it, and the live home or app shows undimmed below. | B-037 step 2c1 (`comp.nc.clock.lift`, `motion.nc.clock-lift`, glue `motion.nc.date-out`), 2c2 (home's arrival through moment `unlocking`), 2c3 (the app's zoom-blur arrival, through the card machinery and moment `closing`). |
| Overpull past the bottom | ? | n/a | **missing** | ? | Not captured. iOS sheets generally rubber-band (UIScrollView-style) [general iOS behaviour, low for NC]. *To capture: hold NC fully down and pull on.* | Progress is rubber-banded past 1 (`Shade.kt:354-365`), but `sheetY()` multiplies the overshoot by `0.0f` (`NotificationCenterView.kt:243`), so nothing moves: a dead stop. | If iOS stretches: a token `comp.nc.overpull` (share of the overshoot shown), released on `motion.nc.open`. |
| Swipe down on the sheet | n/a | n/a | missing | **missing** | A swipe down from mid-sheet opened **Search** (Siri Suggestions, field and keyboard) [SIM this audit, pass 9 `nc-hidden`, medium: one run, the sheet was empty]. With notifications on the sheet, a fling down hides the list into "● N Notifications" [SIM pass 7, high]. | A vertical drag off a list that cannot scroll becomes a close drag (`shade/nc/NotificationListBlock.kt:1299-1301`); downward it does nothing visible. No hidden-list state (IOS27_EVALUATION "Not built yet"). | Empty sheet: hand the swipe to our Spotlight (home's), growing out of the sheet on `motion.nc.search` (new role, moment `nc`). With notifications: the hidden list with its count (`comp.nc.hidden.*`, `motion.nc.hide`). *Capture the way back (pass 9's show swipe).* |
| Swipe left and right on the sheet | n/a | n/a | missing | missing | iOS 16-18: swipe right for Today View (widgets), left for Camera [GUIDE-era, medium]. *To capture on 27.* | None. | P3. Camera: the card machinery, from the edge. Today View: not in scope until home widgets can show there. |
| Collapsed stack, fan-out | ok | ok | ok | ok | Collapsed at the bottom, the next card peeking, "+N from App"; a tap fans it out [SIM pass 7, high]. | `NotificationListBlock.kt:380-440`, on springs. | None. |
| Platters, list, scroll | ok | ok | ok | ok | Clear glass, white text; the list scrolls over the clock and fades under the bar; rubber band at the ends [SIM, KIT]. | `NotifPainter`, `IosScroller` (rubber band, `motion/IosScroller.kt:173-182`). A short list does not scroll, so it has no rubber band at all (that drag is a close drag). | *Capture whether a short list bounces on iOS 27.* |
| Grouping | off | n/a | n/a | n/a | By app, split by thread [SIM, high]. | By app only (IOS27_EVALUATION). | Group by conversation shortcut or group key. P3. |
| Swipe actions | ok | off | ? | ok | Swipe left reveals Options and Clear; a long swipe clears; iOS 26+ buttons are Liquid Glass capsules that "morph" out of the platter [WWDC25 principle, medium; not captured]. | Buttons grow out of the right edge, and a long swipe stretches Clear across (`NotificationListBlock.kt:878`); swiping right resists (`:1308`). | *Capture the swipe frame by frame*; if the buttons split off the platter like a drop, use a `motion.nc.swipe-morph` role. |
| Long look | ok | off | ? | ok | A solid card (white header) with a glass menu under it and the rest blurred [KIT, high]. How it opens, on a "bubble pops open" principle [WWDC25, medium], was not captured. | The sheet blurs (`lookSigma`), the menu is dark glass on `motion.nc.menu-open` 0.35/0.8. No action icons. | Action icons (P3). *Capture test41 again (pass 7 found no platter).* |
| Clear / hide | ok | ok | ok | missing | "×" becomes "Clear"; the hidden list (above). | "×" becomes Clear on `motion.nc.confirm`; no hidden list. | See "Swipe down on the sheet". |
| Clock (lock-screen style) | ok | ok | wrong at commit | n/a | Glass numerals 205 x 87 at y 115; on commit they drop from above [SIM, KIT]. | `shade/nc/LockClockBlock.kt`: the size and material match; it fades in with the content, with no drop (main). | B-037 2b (the parked `ClockDrop.kt`). |
| Flashlight and camera | ok | ok | ok | ok | 58 pt glass at x 46 / 298, y 766; touch and hold [KIT]. | `CornerButtonsBlock`, `motion.nc.hold`. | None. |
| Landscape | ok | ok | n/a | ok | The clock beside the list [SIM run 21]. | `comp.nc.land.*`. | None. |
| Where the pull starts | ok? | n/a | n/a | ? | The top centre (iOS 26), or the **top left with a Siri panel in the centre** per [WEB-27, low]. | Left of the camera opens NC, right of it CC (`ios.json` `split-top`), already close to the WEB-27 layout. | *Capture where a pull from the centre goes on iOS 27.* |

## 3. Control Center

| Element | Layout | Look | Motion | Interaction | iOS (source, confidence) | Ours | Fix |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Pull / open | ok | ok | off | ok | Blur and dim come in within ~3 frames (~75 ms); controls at full size, pulled down by 100 pt x (1 - e^(-d / 270)); a release settles on 0.44 / 0.62-0.65 (moving) or 0.35-0.38 / 0.70-0.72 (still) [SIM, high]. | The same pull curve (`Shade.kt:741-746`); settle `motion.cc.settle` 0.42 / 0.68 (ok); the panel comes in over `comp.cc.motion.open-travel` on `motion.cc.open` 0.45 / 1, slower than iOS on the owner's request. | Deliberate; keep. |
| Overpull (the grid hanging below) | ok | n/a | ok | ok | Controls hang below their place and keep their size: no stretch between them [SIM, high]. | Matches. | None. |
| Close | ok | ok | **off** | ok | Controls lift a few points and fade; the blur clears faster than the controls; ~0.18 s [SIM, high]. | Controls fold back into the corner they were pulled from, shrinking as they go (`Shade.kt:441-442`, `ControlGridBlock.kt:563-571`, `CcClose`); `motion.cc.close` 0.34 / 1. A deliberate change (PROGRESS: "close felt cheap"), but not iOS. | Owner's call: keep, or make the fold a token (`comp.cc.close.fold` 0 = iOS's lift and fade). P3. |
| **Pages** | **missing** | missing | missing | missing | iOS 18+: several pages, swiped vertically, with a column of page symbols on the right (KIT: x 372, 21 wide, y 290-530: heart, dot, music, home, signal) that can be tapped or scrubbed. Defaults are favourites, music, home and connectivity. Up to 15 pages; edit mode adds one (a circle at the bottom); controls that overflow make a new page; Now Playing and Connectivity can fill a page [KIT high for the column; GUIDE medium for the behaviour]. How the pages move (snap, rubber band at the ends, the column's highlight) is *to capture*. | One page (`CcLayout.kt:23-33`, `ControlGridBlock.kt:183`: rows grow to fit, no scroll, no paging); IOS27_EVALUATION "Pages: Not built". **This is the owner's report 3.** | `CcLayout` becomes a list of pages (saved as `page0..N`, which the prefs key already implies); the grid scrolls vertically and pages snap (`motion.cc.page`, iOS-style paging, rubber band at the ends); a page column block (`comp.cc.pages.*`: x, width, symbol size, the highlight's material) that can be tapped and scrubbed (`motion.cc.page-dot`); in edit mode, an empty page at the end, dragging across pages, overflow making a page; full-page modules (Now Playing, Connectivity). New block `page-index` in `ios.json`; moments `cc` (paging) and `cc-editing` (pages in edit). Capture the pages before building (a new `test86_ccPages`: swipe, scrub, edit, overflow). |
| Modules (connectivity, media, focus, sliders) | ok | ok | ok | ok | KIT geometry; Connectivity, Now Playing and Focus as in the kit [KIT, high]. | `ControlGridBlock`, `CcSurfaces`, `comp.cc.*`. | None. |
| Toggles: press and tap | ok | ok | **off** | ok | Liquid Glass "flexes and energizes with light" on touch: a gel-like swell, then a spring back [WWDC25, medium]; the amount on CC's controls was never measured. | A press scales 1.035 and brightens (`ControlGridBlock.kt:562`), springing back on `motion.cc.press-out` 0.38 / 0.7. Uniform scale: no flex in the direction of the touch, no light that follows the finger. | *Capture a slow press and release on a 1x1 and a 2x1 control.* If iOS deforms toward the finger: `comp.cc.press.swell`, `comp.cc.press.flex` (a non-uniform scale toward the touch) and a highlight following the touch point (MaterialPainter's light). Moment `controls`. |
| Brightness and volume sliders | ok | ok | **?/off** | ok | Past an end the slider stretches (rubber band) [general iOS behaviour, medium]; whether it also narrows (squash) or only lengthens is *to capture* (run 4 `test07_ccSlider` found no slider). | Grows taller by a rubber-banded share of the overshoot, `rubberBand(over, len * 0.6, 0.4)` (`ControlGridBlock.kt:1437-1439`), keeping its width (`:553-555`); back on `motion.cc.stretch-back` 0.38 / 0.7. | Capture first; if iOS narrows: `comp.cc.slider.squash` (width lost per pt of stretch), and the symbol riding the stretched end. |
| Expanded modules | ok | ok | ok | ok | The module itself grows into the large panel [SIM pass 7]. | A morph (`CcExpanded.draw`), checked 2026-10-20. | None. |
| Control menus (Camera held) | ok | ok | ok | ok | Take Photo, Selfie, Video [GUIDE]. | `ControlGridBlock.kt:1257+`. | None. |
| Edit mode and gallery | ok | off | ok | off | The grid moves up to y 86 with delete buttons and empty discs; "Add a Control"; the gallery has a "Search Controls" field [SIM, high]. | Matches, except no gallery search (IOS27_EVALUATION). In edit mode there are no pages (above). | The gallery search field (`comp.cc.gallery.search.*`, `motion.cc.gallery.*`). P3. |
| Power button | ok | ok | ok | off | Touch and hold, then the slider [GUIDE]. | Hold only; **on the S24 the hold brought no power menu** (2026-10-20, logged, unresolved). | Re-test on the S24 with the log lines in IOS27_EVALUATION. P2. |
| Controls' glass while pulled | ok | off | ok | n/a | Exact [SIM]. | Samples the resting place (up to ~60 pt off) for GPU reasons (IOS27_EVALUATION). | Known; P3. |

## 4. Banners

| Element | Layout | Look | Motion | Interaction | iOS (source, confidence) | Ours | Fix |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Arrive | ok | ok | **off** | n/a | Comes out of the Dynamic Island (125 x 37 pill) widening as it comes down to x 8, y 58.7, 386 wide; the centre on 0.64 / 0.61 (drops ~12 pt past), the size on 0.66 / 0.84 (peaks 1.01) [SIM pass 6, high for the springs]. Whether its *aspect* starts as the island's (a non-uniform morph, island-like) is *to capture* frame by frame. [WEB-27, low]: slides in from the left. | One uniform scale 0.3 to 1 about the top centre, from the camera (`shade/BannerView.kt:313-319`): it starts as a small banner-shaped card, not an island-shaped pill. Size and centre are on one spring, `motion.banner.in`. | If iOS morphs: width and height on separate values (`comp.banner.island.w/h` start size, `motion.banner.in-size` 0.66 / 0.84 for the size, the corner radius from the island's 100 to 24). Moment `banners`. |
| Stay / time out / leave | ok | ok | ok | ok | ~7.4 s; up into the island in ~0.15 s [SIM pass 6]. | `SHOW_MS` 7000 (`BannerView.kt:712`), `motion.banner.out` 0.3 / 1. | None. |
| **Pull down** | n/a | n/a | **wrong** | **wrong** | Follows the finger with heavy resistance (~100 pt for a 250 pt pull), **shrinks to ~0.85-0.9x** while pulled down (a squash), and on release springs back up into place, growing back with a slight overshoot past full width; NC does **not** open and no long look opened (that notification had no expanded content) [SIM this audit, pass 6 `test14_bannerPullDown` 40.6-41.3 s, medium]. Older guides: pulling a banner down shows it expanded [GUIDE, medium]. | Rubber band down (`BannerView.kt:665`), no scale; past 40 pt on release it **clears the banners and opens Notification Center** (`:696-700`). | Pulled down: scale `1 - comp.banner.pull-squash x k` (judged ~0.12 at full resistance), back on `motion.banner.back` (0.38 / 0.82 gives the overshoot). Release: back in place, or the long look when the notification has more to show (`motion.banner.expand`, new). Opening NC from a banner is not iOS. Capture a notification with actions to see its expanded form. |
| Swipe up / sideways | ok | ok | ok | off | Swipe up sends it into the island [SIM pass 6]. [WEB-27, low]: swipe right dismisses. | Up or sideways, with the finger's speed (`:701-708`). | *Capture a sideways swipe.* |
| Several banners | ? | ? | ? | ? | Not captured (pass 2, 4 and run 4 `test13_bannerReplace`: "no banner"). | A newer banner pushes the shown one up and away (`BannerView.kt:495`). | *Capture test13 again* (grant notifications first, as pass 10). P3. |
| Live blur | ok | ok | ok | n/a | Glass over what is behind. | Samsung's live view blur (IOS27_EVALUATION "Banners"). | None. |

## 5. Between Notification Center and Control Center

| Element | Layout | Look | Motion | Interaction | iOS (source, confidence) | Ours | Fix |
| --- | --- | --- | --- | --- | --- | --- | --- |
| CC pulled while NC is open | ? | ? | **?/off** | ? | CC opens over the lock screen or cover sheet; closing it goes back to what was under it [general iOS behaviour, medium; not captured]. | NC is let go and closes under CC (`Shade.kt:1100-1112`, `letGo` and `goesUnder`), so closing CC lands on the app or home, not on NC. | *Capture NC open, then a pull from the top right, then a close.* If iOS keeps NC: CC over a held NC (its layer kept, blurred by CC's live blur), and CC's close returns to it. |
| NC pulled while CC is open | ? | ? | ? | ? | Not captured. | CC closes under the NC pull (`ccPanel.goesUnder`). | Capture. |

---

## Squash and stretch, specifically

Where iOS 27 deforms, and whether ours does:

| Where | iOS | Evidence | Ours |
| --- | --- | --- | --- |
| A banner pulled down | Shrinks (~0.85-0.9x) as it resists, springs back with overshoot | SIM pass 6 test14 (this audit), medium | **No**: a plain rubber band, then NC opens |
| A banner arriving | Widens out of the island as it drops, size peaking at 1.01 | SIM pass 6, high (size); the aspect is to capture | Partly: uniform 0.3 to 1.0 scale, overshoot 1.06 x (k - 1); not island-shaped |
| NC's edge while pulled | The lens band stretches the content above it down into the band, mirrored at the very edge; the sides stretch outward | SIM pass 10, high | **Yes** (B-037 step 1) |
| NC's commit | Home's icons zoom 1.24x to 2x and fade; the clock drops with a soft tail | SIM pass 10, high | **No** (a 70 ms fade; the branch has the clock drop, parked) |
| NC's close | The arriving app or home zooms down from ~1.2x (app) or ~2-3x (home) | SIM pass 11, high | **No** |
| NC overpull | Unknown | to capture | Computed, then zeroed (`NotificationCenterView.kt:243`) |
| NC list ends | Rubber band (UIScrollView) | IOS_DESIGN (0.55), high | Yes for a scrolling list; a short list does not bounce |
| A swiped platter | Resists to the right; on the left the buttons appear; morph unknown | partly SIM | Rubber band to the right; buttons grow |
| CC overpull | No deformation: the whole grid hangs lower at its own size | SIM, high | Same (ok) |
| CC sliders past their ends | Stretch (rubber band); a narrowing is to capture | medium | Lengthens only |
| CC controls pressed | A gel-like flex and a light (Liquid Glass) | WWDC25, medium | A uniform 3.5 % swell and brightening |
| CC module to expanded panel | A morph of the module into the panel | SIM pass 7 | Yes (`CcExpanded`) |
| Island-like morphs | A banner out of the island; Live Activities | SIM (banner) | The banner partly; the charging capsule is ours (status bar, out of scope here) |

The owner's report 2 is right for banners, controls under a finger and both ends of Notification Center. The pull
(lens) and Control Center's grid are not missing it.

---

## Ranked gaps (bug proposals)

P1:
- [B-new] P1, shade (iOS layout), Notification Center's commit has no animation: released past 62.5 % the sheet's content fades in over 70 ms (`motion.nc.reveal`), the rim and lens vanish, the corners square, the wallpaper slides with the sheet, no clock drop or zoom reveal (iOS 27: the outline lands over still content, home's icons zoom 2x and fade, the numerals drop ~160 pt). Repro: iOS shade, pull Notification Center halfway over home and let go. Status: open (B-037 step 2 parked on fix/b037-ios-glass-pull; fold into B-037)
- [B-new] P1, shade (iOS layout), Notification Center's close is a square-cut opaque sheet over the live, undimmed screen (iOS 27: rounded rim and lens kept, the wallpaper still inside, the dimmed wallpaper below, then home's unlock arrival or the app's zoomed, blurred arrival). Repro: open NC, swipe up from the bottom slowly. Status: open (B-037 step 2c not built)
- [B-new] P1, shade (iOS layout), Control Center has a single page: no vertical paging, no page column on the right, no adding pages in edit mode, no overflow to a new page, no full-page modules (iOS 18+). Repro: iOS shade, open Control Center, swipe up on the grid. Status: open (capture the pages first)

P2:
- [B-new] P2, shade (iOS layout), a banner pulled down opens Notification Center past 40 pt and never shrinks (iOS 27: it follows with resistance, shrinks to ~0.85-0.9x, springs back with overshoot; NC does not open). Repro: a test banner over home, drag it down 250 pt, hold, release. Status: open
- [B-new] P2, shade (iOS layout), a swipe down on Notification Center does nothing (iOS 27: Search opens from an empty sheet; with notifications, the list hides into "● N Notifications"). Repro: open NC, swipe down from mid-screen. Status: open
- [B-new] P2, shade (iOS layout), Control Center's controls swell uniformly (3.5 %) under a finger, with no Liquid Glass flex or light following the touch (WWDC25: "gel-like flexibility"). Repro: press and hold a control slowly; compare a Simulator capture. Status: open, to capture first
- [B-new] P2, shade (iOS layout), a banner arrives as a uniformly scaled small card, not a shape widening out of the island (iOS 27: size and centre on separate springs; the aspect at the start to capture). Repro: TEST_NOTIFY over home, slowed frames. Status: open, to capture first
- [B-new] P2, shade (iOS layout), pulling Control Center over an open Notification Center closes NC under it, so closing CC lands on the app instead of NC (iOS: unverified). Repro: open NC, pull from the top right, close CC. Status: open, to capture first
- [B-new] P2, shade (iOS layout), Control Center's power button held does nothing on the S24 (emulator fine; logged 2026-10-20, never resolved). Repro: S24, CC, hold power; read "[cc] power held" and "[shade] power menu" lines. Status: needs re-test

P3:
- [B-new] P3, shade (iOS layout), Notification Center pulled past the bottom is a dead stop: the rubber-banded progress is multiplied by 0 in `sheetY` (iOS's overpull to capture). Repro: open NC, keep pulling down from the top. Status: open, to capture first
- [B-new] P3, shade (iOS layout), Control Center's sliders only lengthen past their ends; whether iOS also narrows them is unmeasured. Repro: drag brightness past full. Status: open, to capture first
- [B-new] P3, shade (iOS layout), Control Center's close folds the controls into the corner where iOS 27 lifts and fades them (deliberate, PROGRESS "close felt cheap"); make it a token or confirm with the owner. Repro: close CC. Status: open (owner's call)
- [B-new] P3, status bar (iOS layout), no "Carrier" on Notification Center's bar or in Control Center's status row. Repro: open NC or CC. Status: open
- [B-new] P3, shade (iOS layout), no swipe left (Camera) or right (Today View) on Notification Center (iOS 16-18; iOS 27 to capture). Repro: open NC, swipe sideways on empty space. Status: open
- [B-new] P3, shade (iOS layout), Notification Center groups by app only (iOS: by thread). Repro: two conversations from one app. Status: open
- [B-new] P3, shade (iOS layout), the long look has no action icons and its opening was never compared with iOS 27 (pass 7 found no platter). Repro: hold a notification. Status: open, to capture first
- [B-new] P3, shade (iOS layout), Control Center's gallery has no "Search Controls" field. Repro: CC edit, Add a Control. Status: open
- [B-new] P3, shade (iOS layout), a new banner over a shown one, and sideways dismissal, never captured on iOS 27 (a beta report says alerts slide in from the left and swipe right to dismiss; pass 6 contradicts the slide). Repro: two TEST_NOTIFY 2 s apart. Status: open, to capture first
- [B-new] P3, shade (iOS layout), where a pull from the top centre goes on iOS 27 is unverified (a beta report: a Siri "Search or Ask" panel, NC moved to the top left); ours splits at the camera. Status: open, to capture first

**Count:** P1 3, P2 6, P3 10.

## Captures that would settle the "?" items (one CI pass)

`test86_ccPages` (swipe up and down slowly with holds, scrub the column, edit and add a page, overflow), a banner
pulled down with an actionable notification (its expanded form), a banner arriving at 120 Hz with its island aspect,
`test13_bannerReplace` again, a sideways banner swipe, NC overpull past the bottom, NC with notifications swiped down
(hide) and back, NC then CC then close, a slow press on a 1x1 and a 2x1 control, a brightness slider dragged past both
ends, and a pull from the exact top centre. Grant notifications first (pass 10's setup).
