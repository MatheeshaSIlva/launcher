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

## Baseline 2 (2026-10-27, branch ship/mf-fonts; PROVISIONAL: the Play Protect notification was missing)

What changed since baseline 1: B-060 (D-042). Every Pixel shade text style now names a font role (`text`, or `display` for the
header clock and the edit page's titles) instead of `google:Google Sans Flex`; `sys.font.text` and `sys.font.display` stay Google
Sans Flex, so ours' own look is unchanged (the 8 `--font off` pictures before and after are pixel-identical, mean 0.0) and
`--font system` now reaches all of its text.

Setup: as baseline 1 (same golden, masks, regions, trees, tile lists, `--font system`), ours = BUILD local of this branch
(pixel.json regenerated by `tools/design/build_pixel_theme.py`). **Not canonical**: the emulator was restarted (it had run 9 h
and was out of memory) and Google Play's "Play Protect is turned on" notification, which the golden holds in its Silent section,
did not arrive in 1 h 25 min of uptime, so ours' set is `pkg=dev.launcher.app id=1;pkg=dev.launcher.app id=1001;` against the
golden's three, and `baseline.sh` stops on that gate (by design). The numbers below come from the same steps run without the
gate (diff.py's defaults, the same masks and regions, `scores.py`), so the quick rows are contaminated: the golden's extra Silent
row shifts the section below the fixture card (the 145938 and 72176 px clusters in the notification area, 10.2 % pixels
differing against baseline 1's 4.8). The expanded and tile states list no notifications and are comparable. Re-run
`tools/fidelity/baseline.sh --out DIR` once the notification is there (a boot, ~25 min in the earlier session) and replace this
section with its output.

| state | theme | cells flagged / total | overall mean | % pixels differing | mask share | worst cell | baseline 1 mean |
|---|---|---|---|---|---|---|---|
| quick | light | 194 / 288 | 7.632 | 10.19 | 4.91 % | col 1, row 12, box 90,1200,90,100, mean 90.835 | 6.939 |
| quick | dark | 287 / 288 | 13.345 | 11.20 | 4.91 % | col 1, row 10, box 90,1000,90,100, mean 76.259 | 12.003 |
| expanded | light | 266 / 281 | 5.108 | 2.18 | 3.01 % | col 2, row 1, box 180,100,90,100, mean 35.809 | 5.783 |
| expanded | dark | 273 / 281 | 10.101 | 7.87 | 3.01 % | col 2, row 1, box 180,100,90,100, mean 37.365 | 10.8 |
| tile-off | light | 268 / 281 | 5.114 | 2.17 | 3.01 % | col 2, row 1, box 180,100,90,100, mean 35.809 | 5.789 |
| tile-off | dark | 273 / 281 | 10.128 | 7.85 | 3.01 % | col 2, row 1, box 180,100,90,100, mean 37.365 | 10.827 |
| tile-on | light | 266 / 281 | 5.108 | 2.18 | 3.01 % | col 2, row 1, box 180,100,90,100, mean 35.809 | 5.783 |
| tile-on | dark | 273 / 281 | 10.101 | 7.87 | 3.01 % | col 2, row 1, box 180,100,90,100, mean 37.365 | 10.8 |

Regions over tolerance are as in baseline 1 for every state. Per-cluster details: `docs/fidelity/baseline-2/scores.md`,
heatmaps `docs/fidelity/baseline-2/<theme>-<state>-worst.png`. Baselines 1 and 2 are not comparable cell by cell: the override
now changes more text, and the quick rows differ in the notification set.

What the font fix did (expanded, light, the clean comparison): the worst cell (both times in the clock's area) fell from 72.7 to
35.8; the tile pager's cluster 24680 -> 19940 differing px (mean 5.97 -> 4.71); the clock's
cluster 7431 px at mean 70.4 -> 3687 px at 35.7; the date's cluster (40,264, 2629 px at 54.5) left the 14 largest; the carrier
text 2388 -> 2694 px (now the words differ, not the glyphs: B-054, "No service" against "T-Mobile"). In dark the pager's cluster
fell 60586 -> 55722 px and the clock's 7485 -> 3747. The glyph-level clusters on tile labels, the date and the section labels are
gone or reduced; what is left on the text is not the family:
- **Weight residue (proposed P3)**: the clock and the carrier text draw at weight 500 in ours and look lighter (about 400) and
  narrower on stock's emulator (side by side: `10:00` and `No service` are heavier and wider in ours). The tile labels (600)
  and notification title, Clear all (600) are still masked (weights from a family name, D-041). The weights were not tuned
  here.
- Positions and content, each already a bug: B-052 (build label and edit button), B-053 (status icons), B-054 (carrier words),
  B-055 (the gesture handle), B-056 (notification rows), B-057 (backdrop), B-059 (the Flashlight tile).
