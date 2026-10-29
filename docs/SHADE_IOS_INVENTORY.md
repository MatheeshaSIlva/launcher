# iOS 27 shade inventory (milestone MS)

Made 2026-10-30 by four read-only passes (architect, Opus), one per element, against the iOS 27 Simulator captures
(`ios-reference/out/`), `docs/IOS27_KIT.md`, `docs/IOS27_MOTION.md`, `docs/IOS27_EVALUATION.md` and the 2026-10-28 audit.
Verdicts: matches / off / missing / unknown. "Measured" = a number on both sides; "judged" = read by eye, from code or from a
document. **Nothing here has yet been compared with iOS frame by frame, and none of B-037 has been checked on the S24**: every
"matches" is provisional until it has.

## The state, in one view

| Element | Parts | Matches (measured) | Matches (judged) | Off | Missing | No usable reference |
|---|---|---|---|---|---|---|
| Notification Center | 47 | 5 | 13 | 16 | 13 | 7 |
| Control Center | 43 | ~9 | ~3 | ~12 | ~5 | ~14 |
| Banners | ~40 | 3 | ~6 | ~12 | 4 | ~12 |
| Status bar | ~47 | ~4 | ~6 | ~16 | 3 | ~15 |

Two findings cut across all four:
1. **No capture has frames extracted for banners and Control Center** (`full/` is missing; only `video.mp4`), so no
   side-by-side contact sheet exists for them. Extracting frames (`tools/ios_ref/frames.py`) is the first step.
2. **Many references do not exist at all.** The Simulator's Control Center starts nearly empty (no sliders, media, radios,
   torch), it cannot ring, charge or run Live Activities without a host app, and several captures ran before notifications
   were granted. A capture pass (Simulator) plus, for some rows, a real iPhone on iOS 27 recorded at 120 Hz is needed.

## Top gaps by visibility (all elements)

1. Notification Center: no arrival after a close (home's unlock arrival, the app settling from ~1.2x blurred); ours just
   uncovers (B-037 2c2, 2c3).
2. Control Center: no pages or page column (B-067).
3. Banners: a pull down opens Notification Center instead of squashing (~0.85-0.9x) and springing back (B-068).
4. Banners and status bar: nothing grows out of or goes back into the island: banners arrive as a scaled card and leave
   flying off the screen (B-070); no capsules for a timer, media, navigation or call (B-112, B-003).
5. Control Center: the close folds into the corner instead of lifting and fading (B-074).
6. Glass does not react to touch anywhere (B-066); Control Center's controls sample their glass at the resting place while
   pulled (a video frozen inside a control: new).
7. Status bar: it does not ride inside the app's card on open and close (new); the tone over apps can be 2.5 s late (B-115).
8. Notification Center: the close is 0.38 s against iOS's ~0.25 s; notifications do not slide with the edge (2c1b); no
   hidden list or Search on a swipe down (B-069).
9. Notification Center: the commit falls back to a plain fade whenever its picture is stale (B-142); the slab shows a
   starting window over a just-opened app (B-139).
10. Pops (rule 3): a banner's update and a light/dark change while a banner shows swap in one frame; the charging battery's
    bolt is outside the body (iOS: inside a green body); no "Carrier" on lock, NC and CC (B-075).
11. Smoothness on the S24: NC fan-out 1-2 and scroll 0-1 missed refreshes, CC edit 1 (B-033); nothing of B-037 measured.

## Captures needed before the rows they decide can be judged

Simulator (RefHost, notifications granted, Control Center populated as `test00_setupControls` does):
- extract `full/` frames for every existing banner and Control Center video (pass2/4/5/6/7, run16, run18);
- NC: overpull past the bottom; a pull from the exact top centre; swipe actions (slow, long, right); the long look; tap to
  open an app; an arrival and a removal while open; a short list dragged; a minute change; a catch mid-landing and
  mid-fall-back; NC then CC and CC then NC; sideways swipes; the hidden list's way back (pass9 test46 has frames, unread);
  lock-screen privacy; a clean standstill close (to fit `motion.nc.close`); grouping by thread;
- CC: pages (`test86_ccPages`); toggles held; edit-mode motion and the gallery's search; a close drag, catch and
  down-up-down reversal; light mode; landscape scroll and edit (run16 shots exist, unviewed);
- banners: arrival and leave frame by frame (the island's aspect); stacking (two and three); sideways swipes; pull down on
  an actionable notification and a long press; tap to open; an update of the same thread; cancelled while shown; dark;
  landscape;
- status bar: Live Activities (timer, media) through ActivityKit in RefHost; screen recording in the island; a minute
  change; a landscape app; `simctl status_bar override` stills (low battery, Low Power, No Service, Focus); time-matched
  sheets of the top 100 pt for app open/close and the NC/CC row transitions (frames exist).

A real iPhone on iOS 27 (120 Hz screen recording) for what the Simulator cannot do: brightness and volume sliders (drag,
overdrag, volume keys, expanded ends), media playing (module, expanded, scrub), the radios' expanded module, the torch,
haptics, charging on plug-in, a ringing call and an alarm banner.

## Notification Center

| Part | State/transition | iOS 27 reference | Ours | Verdict | Measured / judged | Gap | Backlog |
|---|---|---|---|---|---|---|---|
| Where the pull starts | Top band, left of the camera | none from the exact centre (AUD §2: Siri, low confidence) | `split-top`: left NC, right CC | unknown | judged | Where a centre pull goes on iOS 27 | B-081 |
| Pull | Clear slab, lens bands, rim | pass10 test81/82 | `nc/NcGlassEdge`, `NcGlassLens`, `NcGlassProfile`; edge 1:1 (`Shade.kt:360`) | matches | measured (emulator) | S24 outstanding | B-037 |
| Pull | Dim below the edge | pass10/11 | `ncPullBegins`, `motion.nc.pull-dim-home/-app` | matches | judged | — | — |
| Pull | Bar over home goes at once | pass10/11 | fades on `motion.nc.bar-fade` (owner, D-035) | off (deliberate) | judged | a fade where iOS cuts | — |
| Pull | Slab over a just-opened app | live app | shows the starting window all through the hold | off | judged (emulator) | stale picture in the glass | B-139 |
| Pull | Slab after a design reload | — | black below the edge until a redraw | off | judged | black area | B-050 |
| Pull | Overpull past the bottom | not captured | dead stop | missing | judged | — | B-073 |
| Pull | Banner pulled down into NC | pass6 test14: squash and spring back, NC does not open | past 40 pt NC opens | off | judged | wrong behaviour | B-068 |
| Commit | Release threshold, flings | 50 % falls back, 75 % opens | 62.5 % | matches | measured (bracket) | exact iOS value unknown | — |
| Commit | Sheet lands | pass10 F563-F595 (89 % by 188 ms) | `motion.nc.open` (90 % ~0.20 s) | matches | measured (unit) | — | B-037 |
| Commit | Content under the moving outline | pass10 | `NotificationCenterView.kt:273-334` | matches | judged (emulator) | no contact sheet | B-037 |
| Commit | Over home: icons zoom and fade | pass10 test81 | `NcRevealPicture`, `motion.nc.reveal-zoom` | matches | measured (unit) | S24; falls back when home redrew | B-037 |
| Commit | Over an app: app fades | pass11 test83 | `motion.nc.reveal` | matches | measured (unit); diff.py over the app 28/288 off | — | B-037 |
| Commit | Stale picture fallback | iOS needs none | content fades | off | judged | how often on the S24 | B-142 |
| Commit | Numerals drop, date rides them | pass10/11 | `LockClockBlock.drops`, `motion.nc.clock-drop` | matches | judged | not frame by frame | B-037 |
| Commit | Bar and "Carrier" at once | pass10/11 | bar fades back, no Carrier | off | judged | no Carrier | B-075 |
| Commit | Dim holds until landed | pass10 | `dimAtCommit` | matches | judged | — | — |
| Commit | Slow motion at the landing | — | seemed not to slow | off? | judged | unconfirmed | B-144 |
| Landed | Sheet at rest | KIT; pass9 test45 | `onDraw` | matches | measured (KIT) | — | — |
| Clock | Numerals and date | KIT | `LockClockBlock` | matches | measured (KIT) | font Inter for SF | — |
| Clock | Minute change | none | crossfade | unknown | judged | — | — |
| Clock | Fades as the list scrolls | not captured | fades by scroll | matches? | judged | — | — |
| List | Collapsed stack at open | pass7 test40; pass9 test45 | `stacks()`, `drawPeek` | matches | measured (KIT) | — | — |
| List | Tap fans out | pass7, pass9 | `expandList`, `motion.nc.reflow` | matches (look), off (smooth) | S24 1-2 missed | drops frames | B-033 |
| List | Scroll, rubber band, fade under the bar | pass9 test45 | `IosScroller`, `topFade` | matches | S24 0-1 missed | — | B-033 |
| List | Short list dragged | none | becomes a close drag | unknown | judged | — | — |
| List | Swipe down hides into "● N Notifications" | pass7; pass9 test46 | none | missing | — | — | B-069 |
| List | Swipe down on an empty sheet: Search | pass9 test46 | none | missing | judged | — | B-069 |
| List | Grouping by thread | EVAL | by app only | off | judged | — | B-077 |
| List | Arrival while open | none | `motion.nc.appear` | unknown | judged | — | — |
| List | Removal reflow | none | `motion.nc.reflow/leave` | unknown | judged | — | — |
| Platters | Look, light and dark | KIT | `NotificationListBlock.kt:665-860` | matches | measured (KIT) | — | — |
| Platters | Frost follows the close's drift | pass11 | re-recorded during the drift (D-051) | matches | judged (emulator) | S24 cost | — |
| Platters | Tap opens the app out of the platter | none (test42 found no platter) | `openFrom` card | unknown | judged | — | B-011, B-124 |
| Platters | Long look | KIT (static) | `openMenu`, `longLook` | off | judged | no action icons | B-078 |
| Platters | Reply in place | iOS long look | keyboard under our overlay | off | judged | unusable | B-001 |
| Swipe actions | Swipe left: Options, Clear | not captured | grow from the edge | off? | judged | maybe no glass morph | B-116, B-066 |
| Swipe actions | Swipe right resists | partly | rubber band | matches? | judged | — | — |
| Swipe actions | Options menu | KIT | `openSettings` | matches? | judged | items not compared | — |
| Clear | "×" to "Clear" | EVAL | `drawClearButton` | matches | judged | — | — |
| Corner buttons | Flashlight, Camera, hold | KIT | `CornerButtonsBlock` | matches | measured (KIT) | the hold's swell, haptic | B-066 |
| Sideways | Today View, Camera | not captured on 27 | none | missing | — | — | B-076 |
| Close | Triggers | pass5 test31; pass11 test84 | `ncCloseBegins` | matches | judged | — | B-037 |
| Close | Sheet rises, ~0.25 s | pass5 (0.23-0.54 s); pass10/11 | `motion.nc.close` 0.38 s | off (timing) | measured (bracket) | slower than iOS | B-037 |
| Close | Wallpaper drifts 14 % | pass11 | `NcClose`, `comp.nc.close.drift` | matches | measured (unit) | — | — |
| Close | Numerals lift, date and bar go | pass11 | `lifts()`, `clock-lift`, `date-out` | matches | measured (unit) | — | — |
| Close | Notifications slide with the edge | pass11 test85 | not built | missing | — | — | B-037 (2c1b) |
| Close | Below the edge: still wallpaper dimmed | pass11 | `NcSlabWallpaper` cover | matches | measured (emulator) | a stand-in | B-037 |
| Close | Landscape: edge meets the numerals' shadow | — | within ~30-40 px | off | judged | — | B-145 |
| After close | Home's unlock arrival | pass11 test84/85 | not built (cover fades) | missing | — | — | B-037 (2c2) |
| After close | App settles from ~1.2x blurred | pass11 test84 | not built | missing | — | — | B-037 (2c3) |
| Catch | Close reversed mid-way | none | `ncReopens` | matches | judged | — | — |
| Catch | During the landing or zoom | none | `caughtProgress` | unknown | judged | — | — |
| Catch | During a fall-back | none | `ncFallBack` | unknown | judged | — | — |
| NC and CC | CC over an open NC, then closed | not captured | NC let go, lands on the app | off? | judged | should return to NC | B-071 |
| NC and CC | NC over an open CC | not captured | CC goes under | unknown | judged | — | — |
| Lock screen | Content whole, clock drops | Sim cover sheet | no reveal picture (owner) | off (deliberate) | judged | — | — |
| Lock screen | Close: lock screen dimmed, clearing | owner's choice | `lockDim` | matches (owner) | judged (emulator) | unlock during the close untested | — |
| Lock screen | Private content hidden | not captured | `setLocked`, `readLockPrivacy` | matches? | judged | — | — |
| Light / dark | Both | pass7/9 dark; KIT | tokens | matches | judged | close not checked in dark | — |
| Landscape | iOS layout | run20/21 test62; KIT | `comp.nc.land.*` | matches | measured (KIT) | commit is a fade; B-145 | B-145 |
| Smoothness | Open, close, fan-out, scroll (S24 warm) | — | `scenario_shade.sh` | off | measured (fan-out 1-2, scroll 0-1, open 1) | not 0 | B-033 |

## Control Center

| Part | State/transition | iOS 27 reference | Ours | Verdict | Measured / judged | Gap | Backlog |
|---|---|---|---|---|---|---|---|
| Panel | Pull | IOS27_MOTION 1-3; pass2/4/5 test02/03 | `Shade.kt:418-419,752` | matches | measured | — | — |
| Panel | Overpull, grid hangs | IOS27_MOTION; pass2 stills | whole grid offset | matches | measured | — | — |
| Panel | Release, settle | 0.44/0.62-0.65 moving, 0.35-0.38/0.70-0.72 still | CC_SETTLE 0.42/0.68 | matches | measured | a still release overshoots a little more | — |
| Panel | Open completes | ~75 ms / 110 pt | `motion.cc.open` 0.45/1 (owner) | off (deliberate) | measured | slower by choice | — |
| Panel | Close | lift a few pt and fade, ~0.18 s; test04 | fold into the corner with stagger | off | measured / code | not iOS | B-074 |
| Panel | Close drag | none | follows dy | unknown | judged | — | — |
| Panel | Catch mid-close | none | reversed spring | unknown | judged | — | — |
| Panel | Reversal mid-drag | none | branch per move | unknown | judged | may step | — |
| Panel | Controls outlast the blur on close | IOS27_MOTION | presence = sqrt(progress) | matches | judged | — | — |
| Live blur | In | IOS27_MOTION; stills | `LiveBlur` log scale; S24 view blur 0.15 | matches | measured (S24, 0 missed) | strength tuned by eye | B-013 |
| Live blur | Controls' glass while moving | exact in the Sim | sampled at the resting place | off | judged | up to ~60 pt wrong; frozen video | new |
| Live blur | Crash while opening | — | BlurBaker / drawLater | off (P0) | measured (S24 log) | prove RenderLease fixed it | B-031 |
| Status row | Icons glide in, % appears | KIT y 104.2, "Carrier" | `StatusRowBlock` | off | measured (geometry) | no Carrier | B-075 |
| Top buttons | "+", power | run18 test71 shots (uncompared) | `TopButtonsBlock` | off | judged | no power menu on the S24 | B-072 |
| Grid | Geometry | KIT; pass2 | `ControlGridBlock.kt:161-190` | matches | measured (numbers) | no golden | — |
| Grid | Pages | KIT column; pass2 test05; run16 | one page | missing | judged | — | B-067 |
| Toggle | Tap | pass2/4 test07 (unusable) | `motion.cc.toggle` | unknown | judged | — | — |
| Toggle | Press and hold (flex, light) | WWDC25 only | ×1.035 and brighten | off | judged | no flex, no light | B-066 |
| Toggle | Haptic | none | none on tap | unknown | judged | device | — |
| Sliders | Drag | none (Sim has none) | fill from the bottom | unknown | judged | — | — |
| Sliders | Overdrag ends | general iOS | lengthens only, literals | off | judged | no squash | B-086, B-116 |
| Sliders | Follow volume keys | none | `motion.cc.level` | unknown | judged | — | — |
| Sliders, expanded | Large drag and ends | none | clamps | missing | judged | no stretch, no haptic | B-087 |
| Media | At rest, transport, scrubber | KIT; Sim has no media | progress redrawn every 250 ms | off | judged | steps 4 px | new |
| Media, expanded | Grow, scrub, bars | none | no scrub, literals | missing | judged | — | B-088 |
| Connectivity | At rest, sub-toggles | KIT | `ControlGridBlock.kt:843-863` | matches (look) | measured | motion unreferenced | — |
| Connectivity, expanded | Morph | run18 test71 (unusable) | `CcExpanded` | unknown | judged | — | — |
| Connectivity | Tap disconnects vs off | EVAL | turns the radio off | off | judged | no Android API (accepted) | — |
| Focus | Tap, hold | pass7 test44 | DND only | off | judged | thinner list | — |
| Flashlight | Tap; hold strength | none | torch and strength | unknown | judged | — | B-065 |
| Camera | Hold menu | guide only | menu | unknown | judged | — | — |
| Other buttons | Tap / launch | none | `drawTile`, AppTiles | unknown | judged | empty slot under Pixel | B-049 |
| Expanded modules | Way in and back | pass2/4 test06 (black); pass7 test44 | `CcExpanded` | unknown | judged | no usable frames | — |
| Edit mode | Enter, badges, discs | pass2/4 test05; KIT | `ControlGridBlock.kt:315-331` | matches (look), missing (pages) | measured | 1 missed on enter (S24) | B-033, B-067 |
| Edit mode | Drag, reflow, resize, remove, add | none | lift/drop | unknown | judged | — | — |
| Gallery | Open, sections, scroll | pass7 test43; run18 test70 | `CcGallery` | off | judged | no Search Controls | B-079 |
| Gallery | Adding a control | run18 test70 | appear 0.55→1 | unknown | judged | — | — |
| Between panels | CC over NC; NC over CC | none | NC closes under CC | off? | judged | lands on the app | B-071 |
| Appearance | Light vs dark | KIT; dark stills only | blended | unknown | judged | no light capture | — |
| Landscape | Layout | run16 test60; cc_land_1/2 | `CcLayout.flowed` | matches | measured | — | — |
| Landscape | Scroll; edit | run16 test60 shots (unviewed) | neither | off? | judged | iOS seems to do both | new |
| Smoothness | Open, close, edit (S24) | — | scenario_shade.sh | off | measured | edit-in 1 missed | B-033 |

## Banners

| Part | State/transition | iOS 27 reference | Ours | Verdict | Measured / judged | Gap | Backlog |
|---|---|---|---|---|---|---|---|
| Platter | Rest place and size | KIT; pass6 marks (8, 58.67, 386, 78.67) | `BannerView.kt:480-481` | matches | measured (KIT) | top is a formula | — |
| Platter | Content layout | KIT | NotifPainter | matches | judged | not diffed | — |
| Arrival | Path of the centre | IOS27_MOTION:34 (0.64/0.61) | `motion.banner.in` | matches | measured | — | — |
| Arrival | Island pill widening into the banner | audit A §4 (aspect to capture) | one uniform scale 0.3→1 | off | judged | not a pill; one spring for size and place | B-070 |
| Arrival | Fade-in | pass6 video (unread) | alpha = k*2.5 | off? | judged | unknown if iOS fades | B-070 |
| Arrival | Waits for its glass backdrop | — | `BACKDROP_WAIT_MS` | matches | judged | — | — |
| Short look | Dwell | ~7.4 s; test11 also 4.22 s | 7000 ms, restarted on touch | matches | measured | the 4.22 s run unexplained | — |
| Leave | Timeout, back into the island | ~0.15 s | `motion.banner.out` 0.3 s, a shrinking card | off | measured / judged | not a pill; ~2x slower | B-070 |
| Swipe up | Fling | pass6 test12 (3 runs) | flies off the top | off | judged | iOS goes into the island | — |
| Swipe up | Slow drag, held, released | pass5/6 test32 | 1:1, springs back under 30 % | off? | judged | not measured | — |
| Pull down | Resistance | pass6 test14 (~100 pt per 250 pt) | `rubberBand(dy, height*0.4)` | off | judged | not fitted | B-068 |
| Pull down | Squash ~0.85-0.9x | pass6 test14 | none | missing | judged | — | B-068, B-116 |
| Pull down | Release springs back past full width | pass6 test14 | past 40 pt opens NC | off (wrong) | judged | — | B-068 |
| Pull down | Expandable: long look | never captured | none | missing | judged | — | new |
| Long press | Long look with actions | pass7 test41 (from NC) | press only | missing | judged | — | new |
| Press | Finger down | pass5 test32 | `1-0.025*press` | off? | judged | depth unmeasured | — |
| Tap | Opens the app out of the banner | none | app's own transition | off | judged | no card from the platter | B-011 |
| Swipe sideways | Left / right | none | flies sideways | unknown | judged | — | B-080 |
| Stacking | New over shown | none | old flies up, new from the island | unknown | judged | — | B-080 |
| Stacking | Ringing, then an ordinary one | none | ordinary waits in NC | unknown | judged | — | — |
| Update | Same key alerts again | none | height springs, text swaps at once | off | judged | a pop | new |
| Update | Silent change or removed | none | flies up | unknown | judged | — | — |
| Ringing call | Compact call banner | none (general knowledge) | round buttons, `comp.banner.call.*` | unknown | judged | unverified | — |
| Ringing call | Tap / pull opens the full screen | none | yes | unknown | judged | — | — |
| Ringing call | Swiped away stays away | none | `host.dismissed` | unknown | judged | — | — |
| Ringing call | Over an open panel | none | kept over CC/NC | unknown | judged | — | — |
| Alarm / timer | Actions | none | up to 3 capsules | unknown | judged | — | B-001 |
| Actions | Reply in place | iOS long look | remote inputs dropped | missing | judged | — | B-001 |
| Glass | Material | EVAL; kit's regular glass | the dock's glass (owner, 2026-10-11) | off (deliberate) | judged | write down as a deviation | — |
| Live blur (S24) | Under the plate | EVAL (radius 19, region, fit) | `SamsungBlur` plate | matches | measured | lens cannot refract it (accepted) | — |
| Live blur | Fades with the card | none | radius linear in alpha | off? | judged | log-scale pitfall | new |
| Backdrop | What is behind changes | — | `motion.banner.app-fade` | matches | judged | — | — |
| Over apps / home | Shown, frost | iOS: yes | yes | matches | judged | — | — |
| Lock screen | Arrival while locked | lands in the list (not captured) | `if (locked) return` | matches in principle | judged | NC's part | B-042 |
| Light / dark | Each appearance | no dark banner capture | `restyle()` | unknown | judged | — | — |
| Light / dark | Change while shown | none | redrawn at once | off | judged | a pop | new |
| Landscape | Width and place | none | full width minus margins | unknown, likely off | judged | iOS narrower, centred | new |
| Smoothness | Arrive, leave, drag (S24) | 0 missed | 0 missed over home, GPU 2.1 ms (once) | matches (home only) | measured (once) | over apps, ringing, drag unmeasured | — |

## Status bar

| Part | State/transition | iOS 27 reference | Ours | Verdict | Measured / judged | Gap | Backlog |
|---|---|---|---|---|---|---|---|
| Time | At rest | KIT 17 pt Semibold centred in the ear (x 70.75) | 15 pt, side margin 34 pt (owner, 2026-10-06) | off | measured | smaller, left-aligned; not a token | B-114 |
| Time | Minute change | none | digits roll up | judged | judged | roll or crossfade unknown | — |
| Time | Lock screen: "Carrier" | pass11 test83 f0300 | time fades, nothing in its place | off | judged (frame) | no Carrier | B-075 |
| Time | NC pulled | pass11 test83; pass2/4 test08/09 | left group fades with the pull | off | judged | no Carrier; timing not compared | B-075 |
| Time | Screen sharing | iOS shows it in the island (audit 7.8) | red capsule round the time | off | judged | not iOS on an island phone | — |
| Notification icons | Left group | iOS shows none (audit 7.5) | Android's icons on springs | off | judged | the owner's call | — |
| Do Not Disturb | Moon | not captured | slot | judged | judged | glyph and place | — |
| Cellular | Bars | KIT 19.33 x 12.33 | ~16 pt wide, literals | off (size) | measured | — | B-114 |
| Cellular | No SIM / No Service / airplane | none | text and glyph | judged | judged | — | B-053 |
| Network type | 5G/LTE | none | label, rolls | judged | judged | — | — |
| Wi-Fi | Arcs | KIT 16.67 x 12.33 | close, literals | off (minor) | measured | — | B-114 |
| VPN badge | Shown | none | badge | judged | judged | — | — |
| Levels group | Spacing, right edge | KIT (7 pt, x 362.4) | 6.8 pt, ~6 pt further in | off | measured | not tokens | B-114 |
| Battery | At rest | KIT 27.33 x 13 | 27 x 12.6, literals | matches (shape) | judged | — | B-114 |
| Battery | Low (red) | none | below ~25 % | judged | judged | — | — |
| Battery | Low Power (yellow) | none | `BATTERY_SAVER` | judged | judged | — | — |
| Battery | Charging (steady) | pass4 test02 f0060; pass11 test83 f0080 | bolt beside the battery | off | judged (frames) | iOS: bolt inside a green body | new |
| Battery | Plug-in moment | none (Sim cannot charge) | capsule led out of the battery | off | judged (our frames) | no elastic morph; at the battery, not the island | B-117 |
| Battery | Unplugged mid-moment | none | keeps the cover while One UI plays | judged | judged | — | — |
| Island | Timer, media, navigation, call | none (needs a Live Activity) | none; One UI's chips hidden | missing | judged | no sign at all | B-112, B-003 |
| Island | Banners in and out | IOS27_MOTION:36,55 | scaled card | off | measured (spring) | not widening out | B-070 |
| Call capsule | Ongoing call | none | none | missing | judged | — | B-003, B-002 |
| Tone | Home | pass4 test02 f0060 | `homeStatusDark`, crossfade | matches | judged | — | — |
| Tone | Over an app | pass11 test83 f0340 | polled, up to 2.5 s late | off | measured | — | B-115 |
| Tone | App open: bar inside the card | pass11 test83 f0120 | overlay bar recolours in place | off | judged (frame) | iOS's bar moves with the card | new |
| Tone | App close | pass2/4/5 test16 | home's tone at the start | off | judged | same, reversed | new |
| Tone | Over NC / CC | test08, test02 | blended by the pulls | judged | judged | not diffed | — |
| NC pull | Bar fade over home | pass2/4 test08/09 | `ncBar`, `motion.nc.bar-fade` | judged | judged | not fitted | — |
| NC pull | Catch and reverse | test09; pass5 test31 | follows `ncP` | judged | judged | — | — |
| CC pull | Levels glide into the row, "100%" | pass4 test02 f0090-f0120; KIT | glide and roll | off | judged | no Carrier; not compared | B-075 |
| CC pull | "+" and power, time fades | test02 f0120 | left group fades | judged | judged | — | — |
| CC | Edit: row goes | pass2/4 test05 | `setRowAlpha` | judged | judged | — | — |
| CC | Charging moment while CC opens | none | folds by (1-cc) | judged | judged | — | — |
| Over apps | App hides the bar | iOS hides it | fades and slides up | matches | measured (STATUS_BAR.md) | — | — |
| Over apps | Swipe from the top in a full-screen app | shown briefly | 2.5 s | judged | judged (emulator) | S24 untried | — |
| Over apps | Panel over a full-screen app | lock / NC frames | `panelOpen` | judged | judged | — | — |
| Lock screen | Bar | pass11 test83 f0300 | time fades | off | judged | no Carrier | B-075 |
| Landscape | Bar in a landscape app | none (Sim stayed portrait) | shown full width | likely off | judged | iOS hides it | — |
| Landscape | CC status line | cc_land_1/2 | `CcLayout.flowed` | matches | judged | not diffed | — |
| Landscape | Home from a landscape app | test61 | waits for the turn | matches | measured (S24) | — | — |
| One UI cover | Cast / share chip | — | capsule over the chip | matches (cover) | measured (S24) | not iOS's look | — |
| One UI cover | Charging chip | — | covered | matches | measured | — | — |
| One UI cover | Live chips, icons | — | hidden by settings | matches (hidden) | measured | leaves no sign of an activity | B-112 |
| Emulator | Stock chips | — | under our bar | off | measured | — | B-002, B-014 |
| Tokens | Geometry | — | 6 colour tokens, sizes literal | off | measured | no theme can restyle it | B-114 |
| Slots | Arrive, leave, move | audit 7.4 | springs | judged | judged | no capture | — |

## Owner's calls this inventory raises

- Keep or drop Android's notification icons on the left (iOS shows none).
- The time at 15 pt left-aligned (your 2026-10-06 choice) or iOS's 17 pt centred.
- The bar in a landscape app: hidden as on iOS, or shown.
- The banner's glass: the dock's glass (your 2026-10-11 choice) stays, written as a deviation.
- Which rows a real iPhone capture is worth getting for, and whether one is available.
- The order of the fixes.
