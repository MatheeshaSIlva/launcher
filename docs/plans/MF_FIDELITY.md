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
