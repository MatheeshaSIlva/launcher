# iOS 27 motion, measured

How iOS 27's own system UI moves, measured in the iOS 27.0 Simulator (iPhone 18 Pro, 402 x 874 pt) on GitHub's
`xcode-27` runner, so the launcher's iOS profile can copy it instead of guessing. Layout and materials come from Apple's
UI kit (`docs/IOS27_KIT.md`); where the running system differs from the kit, the running system wins (noted).

## Method (and how far to trust it)

- `ios-reference/` drives SpringBoard with UI-test touches (start point, speed, hold) and records the screen
  (`simctl io recordVideo`); every frame keeps its own time. Tools in `tools/ios_ref/`: `frames.py` (frames + times,
  contact sheets), `track.py` (an element's centre and scale per frame by correlation), `bbox.py` (a bright card's box),
  `springfit.py` (SwiftUI spring: response, damping fraction; single run or one spring over several runs).
- **Checked end to end**: a calibration screen (RefHost) moves a square on three SwiftUI springs with known values; the
  chain gave them back: 0.5 / 0.8 -> 0.500 / 0.823, 0.35 / 1.0 -> 0.340 / 1.027, 0.6 / 0.6 -> 0.599 / 0.615 (within 3 %).
- **Limits**: the CI machine has no GPU: while SpringBoard animates heavy layers (blur) the recorder sometimes misses
  frames for 50-200 ms. Frames it does catch carry the right time, so fits use what is there and repeated runs fill the
  gaps. Synthesized touches begin ~1 s after the test asks: gesture times come from the video, never from the test.
  The Simulator's Slow Animations switch (`cadebug.swift`) turned on but slowed neither SwiftUI nor SpringBoard on iOS 27.
- A test's finger lifts while still moving (XCUITest), so a release spring's start speed is unknown; the cleanest
  measurements are releases after the finger has rested (pass 5) and motions with no finger (taps, banners).

## Results

| Motion | Measured (response s / damping) | Ours now | Evidence |
| --- | --- | --- | --- |
| Control Center: controls settle after the pull is let go | **0.44 / 0.62-0.65** after a moving release, overshoot 8-9 pt on a ~70 pt return (every run); **0.35-0.38 / 0.70-0.72** released from a standstill (status row, 2 runs) | was 0.48 / 0.84; now **0.42 / 0.68** | 11 + 2 runs, 4 passes |
| Control Center: how far the finger pulls the controls down | 62 pt with the finger 262 pt down, 73-90 pt at 480 pt: fits 100 pt x (1 - e^(-d / 270 pt)) | now the same | pass 2, 4, 5 |
| Control Center: blur and controls coming in | fully in after ~70-110 pt of pull (75 ms of a 1500 pt/s pull) | was a third of the screen; now 110 pt | frames |
| Home folder opens (its icons grow from the folder icon) | **0.49 / 0.92** (scale) | folderOpen 0.42 / 0.86 | 3 of 4 runs agree (0.448-0.494 / 0.913-0.933) |
| Home folder closes | ~**0.37 / 0.97** (position; scale unreliable once the icons are tiny) | folderClose 0.36 / 1 | 3 of 4 runs |
| Notification Center: sheet goes up after a close swipe | ~0.25 s from release (standstill) to off screen; no clean spring fit (5 runs: 0.23-0.54 s, damping ~1) | close 0.38 / 1 (kept) | pass 5 |
| App launch (card from the icon to full screen) | not settled: the best-sampled launch fits 0.21 / 1.02, the rest scatter (the card is clipped by the screen at the end, the early frames are often missing) | appOpen 0.42 / 0.92 | 14 launches; needs another measure |
| Home page snap, released from a standstill near half way | **~0.37-0.40 / 0.86** (best run 0.374 / 0.862; it went back to the page it came from at 47 %) | pageSnap 0.38 / 1 (kept: the difference cannot be seen) | pass 6, 3 runs |
| Banner arrives | centre: **0.64 / 0.61** (drops ~12 pt past its place, settles); size: 0.66 / 0.84 (peaks at 1.01) | was 0.5 / 0.78 from the top-left corner; now 0.64 / 0.61 out of the camera | pass 6, 3 runs, almost identical |
| Banner stays | **~7.4 s** from its first frame to leaving (3 runs: 7.37, 7.48, 7.69) | was 5 s; now 7 s | pass 6 |
| Banner leaves (timed out) | up into the island in ~0.15 s | now 0.3 / 1 back into the camera | pass 6 |

## Choreography (what moves, in what order)

- **Control Center open** (iOS 27): the home screen behind blurs and dims in about 75 ms while the controls and the status
  row appear already in their final size; they follow the pull and hang **below** their place while the finger pulls on
  (rubber band: about 90 pt low with the finger 480 pt down); on release they rise and settle on the spring above,
  overshooting slightly upwards. No module scale-in, no stagger seen.
- **Control Center close**: the controls lift a few points and fade; the blur and dim clear *faster* than the controls,
  so for ~0.1 s faint control outlines sit over an already sharp home screen. The whole close takes ~0.18 s.
- **Notification Center open**: not a panel over home: the lock screen ("cover sheet") slides down as a solid sheet, the
  wallpaper inside it staying still (a window moving over it), the clock and date riding down with the sheet's bottom
  part; the bottom edge is a liquid-glass rim that **bends towards the finger** (lower under the touch), with the home
  indicator on it. Home underneath is neither blurred nor scaled.
- **Folder open**: the folder's icons grow out of the folder icon (scale 0.19 to 1) while moving to the panel's place;
  the panel (clear glass) grows with them; the title ("Utilities", large, white) fades in above; home blurs behind.
  Nothing overshoots visibly. Close: the reverse, faster.
- **Banner**: grows out of the Dynamic Island (small, at the top centre), widening as it comes down to its place
  (x 8, y 58.7, 386 wide), drops ~12 pt past it and settles; leaves back up into the island. (Our implementation
  swooped from the top-left corner, from articles; now from the camera.)
- **App open**: a white card (the app's launch screen) grows from the icon to the full screen; ~0.25 s to fill it
  (whether it overshoots cannot be seen: the screen's edge hides it; its corner radius is the tell, to measure).
