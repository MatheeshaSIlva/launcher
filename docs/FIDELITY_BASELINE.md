# Fidelity baseline (MF)

What our Pixel shade scores against stock Android 17's on the emulator, state by state. Numbers come from
`tools/fidelity/baseline.sh` (diff.py's defaults: a cell fails at mean >= 2.0 or >= 2 % of its pixels over 12 of 255);
nothing here is judged by eye (D-037).

## Baseline 1 (2026-10-26, branch ship/mf-fidelity-3; re-scored after review round 2 on the same capture)

Setup:
- Emulator `Medium_Phone`, Android 17 `CP41.260828.004.A7`, 1080x2400 at 420 dpi; ours: BUILD local of 1d65620 + slice 3.
- Golden: `tools/fidelity/golden/CP41.260828.004.A7/` (stock, captured with `golden.sh`, slice 2).
- Fixed setup per D-039/D-040 (clock 10:00, battery 100 % unplugged, modem unregistered, brightness 127, DND off); both sides
  show the same 7 tiles (`wifi,bt,cell,dnd,airplane,rotation,flashlight`; D-041).
- Notification set on both sides: `pkg=com.android.vending id=951340667;pkg=dev.launcher.app id=1;pkg=dev.launcher.app id=1001;`
  (Play Protect, our safety notification, the test notification). Android's own overlay notice is left out (ours hides it).
- Ours with `--font system` (D-038). **Most of the Pixel shade's text still draws Google Sans Flex**: its text styles name
  the family directly, so the override does not reach them (B-060; owner, 2026-10-26: make it reach, D-042). Text glyphs and
  widths therefore differ on both sides and inflate these scores; baseline 2 follows that fix.
- Masks (`tools/fidelity/masks/pixel-system-font.json`, from `mask-rules.json` and stock's trees in
  `tools/fidelity/masks/stock-trees/<build>/<theme>/`, light and dark): the notification title and the Clear all words
  (weights from a family name), the "1 app is active" pill ours shows and stock does not (B-058); quick 4.91 % of the
  picture, the expanded states 3.01 %. A title's mask stops at the left edge of the row's time label (and its divider) and
  at the top of the body text below it, so a time label that a wider title pushes aside stays in the comparison.
  The Silent section is compared (both sides list the same rows).
- Command: `tools/fidelity/baseline.sh --out DIR`; per-cluster details in `docs/fidelity/baseline-1/scores.md`, heatmaps
  `docs/fidelity/baseline-1/<theme>-<state>-worst.png`.

| state | theme | cells flagged / total | overall mean | % pixels differing | mask share | worst cell | regions over tolerance |
|---|---|---|---|---|---|---|---|
| quick | light | 195 / 288 | 6.939 | 4.83 | 4.91 % | col 1, row 12, box 90,1200,90,100, mean 46.201 | first-notification-card, footer, header, tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes |
| quick | dark | 288 / 288 | 12.003 | 5.84 | 4.91 % | col 3, row 6, box 270,600,90,100, mean 49.845 | first-notification-card, footer, header, tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes |
| expanded | light | 266 / 281 | 5.783 | 2.55 | 3.01 % | col 1, row 1, box 90,100,90,100, mean 72.732 | footer, header, tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| expanded | dark | 273 / 281 | 10.8 | 8.24 | 3.01 % | col 1, row 1, box 90,100,90,100, mean 73.466 | footer, header, tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| tile-off | light | 268 / 281 | 5.789 | 2.53 | 3.01 % | col 1, row 1, box 90,100,90,100, mean 72.732 | footer, header, tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| tile-off | dark | 273 / 281 | 10.827 | 8.23 | 3.01 % | col 1, row 1, box 90,100,90,100, mean 73.466 | footer, header, tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| tile-on | light | 266 / 281 | 5.783 | 2.55 | 3.01 % | col 1, row 1, box 90,100,90,100, mean 72.732 | footer, header, tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| tile-on | dark | 273 / 281 | 10.8 | 8.24 | 3.01 % | col 1, row 1, box 90,100,90,100, mean 73.466 | footer, header, tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |

All 8 are over tolerance, as a first baseline is expected to be. `tile-on` equals `expanded` (Auto-rotate on in both).

### Gaps found (each logged; none fixed in MF slice 3)
- B-052: expanded Quick Settings: our build label and edit button sit ~210 px lower than stock's (y ~1488 against 1277).
- B-053: status bar and header row: stock shows vibrate, signal (with "!") and Wi-Fi icons; ours writes "No Service" and
  shows no vibrate or signal icon; our items start ~40 px further in; the battery glyph differs (header mean 30.4 quick light).
- B-054: carrier text: ours shows "T-Mobile" / "T-Mobile, 5G" while the modem is unregistered; stock says "No service"
  (carrier text and the Mobile data tile's subtitle).
- B-055: the gesture handle (x 392-688, y 2360-2376) is drawn under stock's shade and missing under ours (mean 85.5 light,
  128.3 dark).
- B-056: notification area: our Silent header, cards and footer buttons sit 5-6 px higher; the app-icon circle differs in
  size and place; Play Protect's icon is a brighter green in ours; the "Launcher" header text is offset.
- B-057: backdrop tone: dark is ~5-10 levels lighter and bluer in ours, and the blurred wallpaper's blotches differ (tone-only
  flagged cells: quick 68 light / 130 dark, expanded 145 light / 92 dark).
- B-058: ours shows a "1 app is active" pill in the footer that stock does not show for the same foreground service (masked
  and named here).
- B-059: ours hides the Flashlight tile on a device without a torch (6 tiles); stock shows it, unavailable (tile mean 8.06).
- B-060: the Pixel shade's text styles name Google Sans Flex directly: a theme's or the user's font does not reach them (and the
  capture override cannot): tile labels, clock, date, section labels. Fixed 2026-10-27 (D-042): see baseline 2.

## Baseline 2 (2026-10-27, branch ship/b062-foreign-notifs; canonical)

What changed since baseline 1: B-060 (D-042). Every Pixel shade text style now names a font role (`text`, or `display` for the
header clock and the edit page's titles) instead of `google:Google Sans Flex`; `sys.font.text` and `sys.font.display` stay Google
Sans Flex, so ours' own look is unchanged (the 8 `--font off` pictures before and after are pixel-identical, mean 0.0) and
`--font system` now reaches all of its text. And B-062 (D-043): the harness snoozes other apps' notifications for a run, so
both sides show the fixed set only, and the golden was recaptured without Google Play Protect (see Golden history).

Setup: as baseline 1 (same tile lists, `--font system`), with:
- Golden: `tools/fidelity/golden/CP41.260828.004.A7/` captured 2026-10-27 (`golden.sh capture`, verified; a second capture
  made with a shell notification present, snoozed for the run, gave the same 8 pictures, decoded md5 for md5).
- Notification set on both sides: `pkg=dev.launcher.app id=1;pkg=dev.launcher.app id=1001;` (our safety notification, the test
  notification). A shell notification (`cmd notification post`, a stand-in for another app's) was up during the baseline's
  capture: snoozed for the run and back at its end.
- Stock trees regenerated from that second capture (`--dump`), masks with `maskgen.py generate`: quick 3 boxes (3.64 %: the
  fixture's and the Silent row's titles, Clear all), the expanded states none. Stock's footer now lists the same "1 app is
  active" pill ours does (session-dependent: the 2026-10-26 golden had none), so the `foreground-service-pill` rule is off and
  the pill is compared (D-041 addendum); B-058 is not a gap on this golden.
- Ours: BUILD local of e6eef47 (B-060), the APK's md5 checked on the emulator.
- Torch (B-063, D-046): `none`, no camera reporting a flash unit (not read at the time: the golden's manifest was backfilled
  from evidence, since the golden and this ours capture are the same emulator boot and ours hid the Flashlight tile).
- Command: `tools/fidelity/baseline.sh --out DIR` (all gates passed: same notification set, no capture warning). Per-cluster
  details in `docs/fidelity/baseline-2/scores.md`, heatmaps `docs/fidelity/baseline-2/<theme>-<state>-worst.png`.

| state | theme | cells flagged / total | overall mean | % pixels differing | mask share | worst cell | baseline 1 mean |
|---|---|---|---|---|---|---|---|
| quick | light | 173 / 288 | 5.082 | 2.95 | 3.64 % | col 3, row 6, box 270,600,90,100, mean 42.194 | 6.939 |
| quick | dark | 287 / 288 | 10.598 | 3.92 | 3.64 % | col 3, row 6, box 270,600,90,100, mean 47.009 | 12.003 |
| expanded | light | 273 / 288 | 5.259 | 2.35 | 0.00 % | col 2, row 1, box 180,100,90,100, mean 35.809 | 5.783 |
| expanded | dark | 280 / 288 | 10.175 | 7.90 | 0.00 % | col 2, row 1, box 180,100,90,100, mean 37.365 | 10.8 |
| tile-off | light | 275 / 288 | 5.265 | 2.34 | 0.00 % | col 2, row 1, box 180,100,90,100, mean 35.809 | 5.789 |
| tile-off | dark | 280 / 288 | 10.201 | 7.89 | 0.00 % | col 2, row 1, box 180,100,90,100, mean 37.365 | 10.827 |
| tile-on | light | 273 / 288 | 5.259 | 2.35 | 0.00 % | col 2, row 1, box 180,100,90,100, mean 35.809 | 5.783 |
| tile-on | dark | 280 / 288 | 10.175 | 7.90 | 0.00 % | col 2, row 1, box 180,100,90,100, mean 37.365 | 10.8 |

All 8 are over tolerance, as expected. Baselines 1 and 2 are not comparable cell by cell: the override now changes more text,
the quick state lost the Play Protect row on both sides (its notification area, B-056's row, is now one Silent row), and the
expanded states compare the footer's pill (its cluster: 11044 px at mean 7.09 light, 17112 px at 10.87 dark), which baseline 1
masked; the expanded states' total cells rise from 281 to 288 for the same reason.

What the font fix did (expanded, light, the clean comparison): the worst cell (both times in the clock's area) fell from 72.7 to
35.8; the tile pager's cluster 24680 -> 19940 differing px (mean 5.97 -> 4.71); the clock's
cluster 7431 px at mean 70.4 -> 3687 px at 35.7; the date's cluster (40,264) 2629 px at 54.5 -> 1515 px at 25.4; the carrier
text 2388 -> 2694 px (now the words differ, not the glyphs: B-054, "No service" against "T-Mobile"). In dark the pager's cluster
fell 60586 -> 55722 px and the clock's 7485 -> 3747. The glyph-level clusters on tile labels, the date and the section labels are
gone or reduced; what is left on the text is not the family:
- **Weight residue (B-061)**: the clock and the carrier text draw at weight 500 in ours and look lighter (about 400) and
  narrower on stock's emulator (side by side: `10:00` and `No service` are heavier and wider in ours). The tile labels (600)
  and notification title, Clear all (600) are still masked (weights from a family name, D-041). The weights were not tuned
  here. *Corrected by B-061 (below): measured, only the carrier is heavier (an emulator artefact); the clock, date and tile
  labels match stock's weight. And baseline 2 never masked the tile labels: its masks are the quick state's 3 boxes (the
  fixture's and the Silent row's titles, Clear all); the expanded states have none.*
- Positions and content, each already a bug: B-052 (build label and edit button), B-053 (status icons), B-054 (carrier words),
  B-055 (the gesture handle), B-056 (notification rows), B-057 (backdrop), B-059 (the Flashlight tile).

## Baseline 2, B-061 re-run (2026-10-27, branch ship/b061-pixel-weights)

B-061 asked whether the Pixel theme's text weights match Android 17's. Weight is measured, not judged by eye (D-045):
`tools/fidelity/stems.py STOCK OURS --box NAME=x0,y0,x1,y1` prints the median stem width (px) of the text in each box.

Stems, stock / ours in px (the same in expanded, tile-off and tile-on; this re-run's ours equals baseline 2's ours here):

| text (box x0,y0,x1,y1) | ours' token | light | dark | verdict |
|---|---|---|---|---|
| header clock (30,84,330,215) | `comp.qs.header.clock` 500 | 11.03 / 11.14 | 11.41 / 11.42 | same weight (+0.11, +0.01) |
| date (30,255,330,312) | `comp.qs.header.date` 500 | 4.47 / 4.48 | 4.81 / 4.77 | same (+0.01, -0.04) |
| Wi-Fi label (220,660,515,710) | `comp.qs.tile.label` 600 | 4.61 / 4.66 | 5.12 / 5.10 | same (+0.05, -0.02) |
| Mobile data detail (220,915,515,958) | `comp.qs.tile.detail` 500 | 3.72 / 3.74 | 4.23 / 4.19 | same (+0.02, -0.04) |
| carrier (700,110,1040,185) | `comp.qs.header.carrier` 500 | 3.45 / 4.43 | 3.75 / 4.66 | ours ~1 px heavier: an emulator artefact |

**The carrier's ~1 px gap is an emulator artefact, not a theme error.** Its weight comes from a family name: SystemUIGoogle's
`shade_carrier_new.xml` (`carrier_combo`, theme `Theme.SystemUI.QuickSettings.Header`) > `mobile_carrier_text` >
`TextAppearance.QS.Status` (14 sp, fontFamily "variable-body-medium-emphasized", letterSpacing 0.01) > `TextAppearance.QS`
(textStyle normal) > `TextAppearance`; no textFontWeight anywhere. The emulator has no such family (nor Google Sans) and
draws Roboto Regular (400); a Pixel draws Body Medium emphasized, 500 (inferred from the family's name, not measured on a
Pixel; the whole chain:
`docs/SHADE_PIXEL_EVALUATION.md`, "B-061"). The token stays at 500 and `pixel.json` is unchanged. The clock, date and tile
texts are Compose text, which the emulator draws at the asked weight: they match. The clock is ~1.8 % larger in ours (a
size, not a weight; not changed here). The carrier's stems compare different words (stock "No service", ours "T-Mobile": B-054); the
~0.95 px gap is a 400 against 500 step, where the same words elsewhere agree within 0.11 px. The quick row's time (ours:
our status bar's; median stem 4.36 / 4.96 px light, 4.71 / 5.20 dark in the box (30,0,330,70) of the quick state; wider) belongs to the status bar element, B-053.

Re-run: ours BUILD local of c8ca9a2 (no app change; the APK's md5 `d5688f1d82d7197d4af5c580f9f7d4da` checked on the
emulator), `tools/fidelity/baseline.sh --out DIR`, every gate passed, the golden and masks of baseline 2.

| state | theme | cells flagged / total | overall mean | % pixels differing | worst cell | baseline 2 mean |
|---|---|---|---|---|---|---|
| quick | light | 173 / 288 | 5.082 | 2.95 | col 3, row 6, box 270,600,90,100, mean 42.194 | 5.082 |
| quick | dark | 287 / 288 | 10.598 | 3.92 | col 3, row 6, box 270,600,90,100, mean 47.009 | 10.598 |
| expanded | light | 273 / 288 | 5.469 | 3.69 | col 2, row 1, box 180,100,90,100, mean 35.809 | 5.259 |
| expanded | dark | 280 / 288 | 10.15 | 6.59 | col 2, row 1, box 180,100,90,100, mean 37.365 | 10.175 |
| tile-off | light | 275 / 288 | 5.474 | 3.68 | col 2, row 1, box 180,100,90,100, mean 35.809 | 5.265 |
| tile-off | dark | 280 / 288 | 10.176 | 6.57 | col 2, row 1, box 180,100,90,100, mean 37.365 | 10.201 |
| tile-on | light | 273 / 288 | 5.469 | 3.69 | col 2, row 1, box 180,100,90,100, mean 35.809 | 5.259 |
| tile-on | dark | 280 / 288 | 10.15 | 6.59 | col 2, row 1, box 180,100,90,100, mean 37.365 | 10.175 |

Ours' 8 pictures are pixel-identical to baseline 2's ours except inside x 550-784, y 1029-1218 in the 6 expanded pictures:
**the Flashlight tile**, which baseline 2's ours hid (B-059: no torch found) and this boot's ours shows, off (the emulator,
rebooted since, now reports a camera with a flash unit: `dumpsys media.camera` "Has a flash unit: true"). That tile alone
moves the light expanded states' means by +0.21 and the dark ones' by -0.025, and the tile pager's cluster (expanded light
19940 -> 54597 px; dark 55722 -> 21712 px, where ours' tile now covers stock's unavailable one). Every named cluster outside it is as in baseline 2: the clock 3687 / 3747 px, the date 1515,
the carrier 2694 / 2719. With `--font off` (the default look) the 8 pictures before and after are pixel-identical.

This re-run holds torch `front` against the golden's `none` and would not pass baseline.sh's torch gate since B-063 (D-046):
the cause in one line, ours takes any camera with a flash unit for the Flashlight tile (B-065) and this boot's front camera
reports one, while stock takes the back camera only.

## Golden history

- `CP41.260828.004.A7`, 2026-10-26 (MF slice 2; used by baseline 1 and the provisional baseline 2): its quick state's Silent
  section held Google Play's "Play Protect is turned on" (`pkg=com.android.vending id=951340667`), which some boots never get
  (1 h 25 min without it on 2026-10-27), and its stock footer showed no "1 app is active" pill. Replaced 2026-10-27 (B-062);
  its pictures and manifest are in git history (the commit before B-062 part 2, `tools/fidelity/golden/CP41.260828.004.A7/`;
  light quick md5 `5fc9dbce3f433cf996ae7539cf383cbf`).
- `CP41.260828.004.A7`, 2026-10-27 (B-062, current): the fixed set only (ours and the fixture), the footer pill shown;
  8 pictures, 1.55 MB; light quick md5 `6384b6bea196724201811f4dd83b3f38`. Torch `none` backfilled 2026-10-28 (B-063,
  D-046; metadata only). A stock recapture on the boot of 2026-10-28 (torch `front`), checked with `golden.sh verify
  --against`: the torch FAILs (`front` against `none`, as it must) and all 8 pictures are identical (decoded md5): stock's
  shade ignores the front camera's flash.
