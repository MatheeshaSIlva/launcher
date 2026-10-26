# MF: Fidelity harness, plan sketch (the architect slices it; D-037)

Idea: capture the same state from the emulator's stock Android 17 shade (the reference) and from ours, diff them with
`tools/fidelity/diff.py`, fix the biggest regions, repeat. The loop needs no human and no judgment by eye.

## State matrix (start here; refine from `docs/SHADE_PIXEL_EVALUATION.md`), each in light and dark
- Quick row at rest (stage 1), Quick Settings expanded (stage 2), brightness at 0, 50 and 100 %
- Tiles: each of Wi-Fi, Bluetooth, Flashlight, Do Not Disturb, Auto-rotate, Extra dim, on and off (B-036, B-046)
- Edit page; media player playing and paused; landscape (split)
- Notifications: one card, a closed group, an opened group, an expanded BigText card, a swiped card mid-way
- Lock screen with private content hidden (B-041)

## Making two captures identical (or the diff measures noise)
- Fresh emulator (B-029: it degrades within ~20 minutes), same AVD, locale, font scale, display size, wallpaper
- Status bar frozen with demo mode: `settings put global sysui_demo_allowed 1`, then `am broadcast -a
  com.android.systemui.demo -e command enter` and `clock -e hhmm 1000`, `battery -e level 100 -e plugged false`, `network
  -e wifi show -e level 4` (verify the commands on Android 17)
- The same notifications on both sides: ours through `TEST_NOTIFY`, stock through `cmd notification post` with the same
  title, text and icon; no real media session
- Self-diff gate: two captures of one state must pass `diff.py`; if not, fix the setup before judging anything else

## Getting "stock"
With our shade off, as `tools/shots/stock` was captured (find the steps in `docs/SHADE_PIXEL_EVALUATION.md`, stages 3 to 8:
a real touch held for each stage, `input motionevent`, 10x slow animations). Goldens are captured once per Android build
and kept (size and storage: a D-entry; recapture if too big for the repo).

## Text and fonts
The emulator has no Google Sans, so words differ on both sides. Decide in a D-entry: mask text boxes (`--mask-file`) or
install the font on the emulator. Compare places, sizes and colours, never word widths.

## Tolerance
`diff.py` defaults (pixel 12 of 255; a cell fails at mean >= 2.0 or >= 2 % of its pixels) are a starting point. Calibrate on
self-diffs and on regions known to match, then record the values in a D-entry.

## Motion
Slow-motion frames of one gesture on both sides, compared at the same progress (pull distance or stage), not the same
time. Our spring physics make time comparisons meaningless; progress comparisons show shape and overshoot.

## Done when
`docs/FIDELITY_BASELINE.md` lists every state's score, every region over tolerance is fixed or a logged bug with its
heatmap, and `/ship`'s fidelity track runs on it. iOS comes after, on a GPU-Mac capture or recordings from a real iPhone.

## Slice 1 plan (architect, 2026-10-26): `tools/fidelity/capture.sh` and the self-diff gate
Verified read-only on emulator-5554: Android 17 (sdk 37), `user` build (no root), 1080x2400 at 420 dpi, font_scale 1.0,
en-US, palette preset D2DCF0 TONAL_SPOT; `cmd statusbar expand-notifications|expand-settings|collapse|set-tiles` exist;
`cmd alarm set-time/set-timezone`, `cmd uimode night` exist; `cmd notification post` has no cancel; SystemUI carries the
demo-mode machinery (its syntax on Android 17 unverified). The 219 files in `tools/shots/stock` have no setup record: not a
golden.
- Scope: `capture.sh` (`--side ours|stock --state quick|expanded|tile-off|tile-on|all --theme light|dark|both --out DIR
  [--repeat N] [--restore] [--dry-run]`; exit 0 / 1 not reached or not at rest / 2 preconditions), `selfdiff.sh` (the gate,
  `diff.py` unchanged), `tools/tests/fidelity_capture_test.sh` (fake adb). Output `tools/shots/fidelity/<run>/<side>/<theme>/
  <state>.png` + `manifest.json` (git-ignored). No app code change.
- Step 1 probes on the emulator, recorded in the script's header: demo mode; `set-time` sticking with auto time off;
  `expand-*` reaching stage 1 and 2 on stock (else held `input motionevent`); `set-tiles` specs on Android 17 and Auto-rotate
  following `accelerometer_rotation` on both sides; adb handles for ours' system colours and fonts; `TEST_NOTIFY` on the
  stock shade; a byte-exact export/import round trip of setup.json and design/. Stop if stage 2 cannot be reached.
- Side switch by the accessibility list (others kept; B-018 retry, never force-stop), the prior state saved to
  `tools/shots/fidelity/.prior/` before the first change, teardown on EXIT/INT/TERM, `--restore` after a kill.
- Fixed setup on both sides: UTC, 24 h, auto time off, clock 2026-11-02 10:00 UTC, battery unplugged at 100 %, brightness
  127, DND off, Auto-rotate on, font scale and locale asserted, palette asserted by seed and style; tiles set on both sides;
  ours on the Pixel layout and theme with the system's colours. Demo mode only if a probe shows it is needed (ours ignores
  it). One notification fixture on both sides through `TEST_NOTIFY`.
- States without touches: collapse, night mode, clock, (quick: the notification, heads-up waited out), open, wait for
  rest (two identical screencaps 400 ms apart, at most 10 s), focus checked; tile-off/on by `accelerometer_rotation`.
- Gate: 3 in-session captures per side, theme and state (pairs 1-2, 2-3) and a cross-session pair (quick, expanded),
  `diff.py` defaults, headroom overall mean <= 0.2 and worst cell <= 0.5; sanity pairs (light vs dark and tile-off vs
  tile-on must fail; the tile's cells only).
- Acceptance criteria AC1-AC9 (preconditions refuse untouched; the device as before after a run, a SIGTERM and --restore;
  emulator usable; 16 PNGs 1080x2400 with a manifest; the self-diffs and cross-session pairs within headroom; the sanity
  pairs; the shell test, check.sh and diff.py's tests; the probes' answers in the header).
- Owner's answers (2026-10-26, D-038): one font on both sides (ours on the system font for captures, restored after; the
  font override is built in slice 1 as a switch); goldens in the repo under `tools/fidelity/golden/<android-build>/`.

## Slice 2 plan (architect, 2026-10-26): ours' tile list over adb, stock goldens (steps 1-4); slice 3: masks and the baseline (steps 5-7)
1. Probes (emulator, recorded in capture.sh's header as i-k): which notification records exist on the stock side besides
   the fixture and which ours shows; stock tile sizes after `set-tiles` (fixes ours' `:w` marks); `uiautomator dump` with
   our service off (works, leaves no difference, strip back).
2. App: `shade/qs/QsTileList` (pure grammar `reset` | `KEY[:w],...`, also used by the existing `qs_tiles` hook),
   `QsTileStore.dump/apply` (prefs read directly: the default and a kept list equal to it told apart), DevFiles export and
   import of a virtual `qs_tiles.txt` (works with the service off); `QsTileListTest`.
3. capture.sh: ours' list set to stock's counterpart (`WIFI,BLUETOOTH,CELLULAR,FOCUS,AIRPLANE,ROTATION_LOCK,FLASHLIGHT`, wide
   marks from stock), restored and verified on every exit; `--dump` (stock only; ours refused untouched); the fake test
   extended; selfdiff.sh live again.
4. `golden.sh capture|verify` and `pngopt.py` (lossless Pillow re-encode, refuses lossy), goldens in
   `tools/fidelity/golden/<ro.build.id>/<theme>/<state>.png` with a manifest (decoded-RGB md5); captured twice live, equal.
Slice 3 (steps 5-7): `maskgen.py` + `mask-rules.json` from stock's dump (family-named-weight text nodes; the stock-only
Silent tail below the fixture card and the "1 app is active" footer, each rule with a why, at most 6 % of a picture),
`scores.py` + `baseline.sh`, the first ours-vs-stock run into `docs/FIDELITY_BASELINE.md`.
Acceptance criteria AC1-AC9 as in the architect's plan (AC1-AC5 and AC9 for slice 2).

## Slice 2 done (2026-10-26); notes for slice 3
- Ours' tile list is set to stock's counterpart (`WIFI:w,BLUETOOTH:w,CELLULAR:w,FOCUS:w,AIRPLANE,ROTATION_LOCK,FLASHLIGHT`)
  and restored exactly; ours hides Flashlight on the emulator (no torch): 6 tiles against stock's 7, a baseline entry.
- Goldens record their notification set (Play Protect, our safety notification, the fixture); capture fails if the set
  changes mid-run. Before the baseline run: have the ours-side notification warning ignore Android's own overlay notice
  (`pkg=android id=0`, present while our service is on, hidden by our shade), or it fires on every run.
- Both sides list "Launcher safety" in the Silent section (probe i): mask rules start from what both show.

## B-060 plan (architect, 2026-10-27): the Pixel shade's text takes its family from the theme (D-042)
Cause: the Pixel theme's builder (`tools/design/build_pixel_theme.py`, `FONT`, `text()`) writes the literal family
`google:Google Sans Flex` into all 31 text tokens; only "text"/"display" resolve through `sys.font.*`. The code already
follows tokens at draw time. Fix: `text()` takes a role ("text"; "display" for the header clock 36, edit page title 28 and
dialog title 24), pixel.json regenerated (only the 31 family lines change), `sys.font.text/display` stay Google Sans Flex
(no default look change); `FontsTest` requires shipped themes' styles to name a role, not a family. AC1 ours `--font off`
captures before/after pixel-identical (8 pairs, diff.py exit 0, headroom 0.0); AC2 baseline 2 with `--font system`, the
glyph clusters on tile labels, clock/date, section labels, notification text gone or reduced, recorded in
docs/FIDELITY_BASELINE.md; AC3 FontsTest fails on the old pixel.json, passes on the new; AC4 a font change reaches all Pixel
shade text without a restart; AC5 scripts/check.sh green.

## B-062 plan (architect, 2026-10-27): other apps' notifications snoozed for the run
- capture.sh, both sides: after the clock is set and before the fixture, snooze every record not ours (`cmd notification
  snooze --for 21600000 KEY`; our overlay notice already ignored), keys saved to `.prior/snoozed.txt` first; sweep again at
  the start of every state; a record arriving during a state still fails it; unsnooze explicitly in teardown and
  `--restore`, verified (dirty kept if not; a record its owner cancelled meanwhile is fine); `--dry-run` lists them. The
  fixed set is {our safety notification, the fixture}.
- golden.sh capture/verify and baseline.sh's gate refuse a set with another app's package.
- Probe l first (a stand-in TEST_NOTIFY record): snooze from the shell, the record leaves the list and both shades, survives
  the clock jump, unsnooze restores it; stop and re-plan if snoozing does not hide it.
- Then (part 2) the golden recaptured without Play Protect (old folder's facts in docs/FIDELITY_BASELINE.md, git history keeps
  the PNGs), stock trees and masks regenerated, baseline 2 run canonically and the provisional section replaced.
- AC1-AC10 as in the architect's plan (fixed set in every manifest; snooze order; restore on every exit incl. kill -9 +
  --restore; refused snooze fails setup; mid-state arrival fails the state; dry-run untouched; gates reject foreign sets;
  live: same set with and without Play Protect; golden 8/8; baseline.sh canonical).
