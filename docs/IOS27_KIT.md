# iOS 27 design system: values from Apple's own UI kit

Source: Apple Design Resources, "iOS and iPadOS 27" Figma kit (Figma Community 1651309003795292092), read through the
Figma API from Matheesha's copy (examples page: Control Center, Home Screen, Home Screen Quick Actions, Notifications,
Lock Screen; file `FMJfykzw8P6XMRcyhLfXOv`). Device frame: iPhone 17 Pro, 402 x 874 pt. Values are exact (the kit's own
layer geometry and styles), not measured from pictures. Raw SVG exports with the effect filters: `ios-reference/figma/`.
Motion is not in the kit: see `docs/IOS27_MOTION.md` (measured in the iOS 27 Simulator).

## Liquid Glass, as the full kit defines it (Figma's native glass effect; file `4nYOmJ9zUW4i9WFYY8ZGuP`)

Every glass surface is two layers: **Fill + Shadow** (fills with blend modes, drop shadows) and **Glass Effect** (Figma's
`GLASS` effect plus inner shadows). The glass effect's own parameters are the closest thing to Apple's real values:

| Surface | GLASS effect (frost / refraction / depth / dispersion / light / splay) | Fill |
| --- | --- | --- |
| Clear glass (Control Center controls, notifications, lock screen buttons, clear menus) | **6 / 0.7 / 30 / 0.2 / 0.4 / 0.2**, light angle 0 | `#101010` linear-dodge (add) + white 4 % luminosity |
| Small controls (toggles, 48 pt buttons) | 6 / 0.7 / 30 / 0.2 / **0.25** / 0.2 | light: `#747480` 8 %; dark: `#767680` 12 %; active: white 94 % + tint (`#0088ff`) linear-burn |
| Dock | **3** / 0.7 / 30 / 0.2 / **0.2** / 0.2 | light: `#999999` 33 % luminosity; dark: `#333333` 33 % luminosity |
| Regular glass (context menus, Quick Actions) | **16** (no lens values given) | white 70 % lighten + `#bfbfbf` 10 % darken; glass layer white multiply |

Inner shadows on the glass layer (all surfaces): `0 +40` spread -40 blur 30 `#e6e6e6` linear-burn (the lower part
darkens a little; not on the dock or menus), `0 -40` and `0 +40` spread -40 blur 10 `#282828` (dark `#1a1a1a`)
linear-dodge (a soft light along the top and bottom edges); the dock adds `0 ±1.25` blur 0.25 `#282828` linear-dodge
(a hairline glint on its top and bottom edges).
Drop shadows (all): `0 8` blur 15 black 2 % (4 % on dark small controls); the rim: spread 0.5 `#cccccc` linear-burn
(`#a6a6a6` on dark small controls, `#dbdbdb` on menus) and `±1.25 0` spread -0.75 `#d0d0d0` linear-burn (left and right
inner edges). Menus: `0 8` blur 48 black 25 %.
On (white) Control Center control: over the fill, `#6f6f6f` color-dodge + white 65 % screen.

Classic blur materials (`_Materials`, background blur 100 = Gaussian sigma 50): Ultrathin white 7 % + white 3 %
color-dodge; Thin white 5 % + 40 % color-dodge; Regular white 25 % linear-dodge + 60 % color-dodge; Thick 34 % + 84 %;
Chrome white 75 % hard-light, blur 50.
Overlays: sheet black 20 %; context menu dimming black 23 %; lock screen / Notification Center overlay black 25 % + black
5 % linear-burn.

## The material (as first read from the examples)

The kit draws Liquid Glass as layers ("Fill + Shadow", then "Glass Effect"):
- **Fill, dark style (Control Center controls, notifications)**: `#101010` with blend **plus-lighter**, then white 4 %
  with blend **luminosity**. (Plus-lighter of a near-black adds a faint lift; luminosity white 4 % lifts brightness only.)
- **Fill, dock**: `rgba(153,153,153,0.33)` with blend **luminosity**.
- **Fill, light menus (Quick Actions)**: white 70 % **lighten**, then `rgba(191,191,191,0.1)` **darken**; glass layer white
  **multiply**; radius 30.
- **Rim and shadow** (box shadows, the same on every surface):
  - `0 0 0 0.5px #ccc`: a 0.5 pt outline (blend plus-darker in the SVG export: it darkens by 20 %);
  - `1.25px 0 0 -0.75px #d0d0d0` and `-1.25px 0 0 -0.75px #d0d0d0`: thin side edges (left and right) just inside;
  - `0 8px 15px 0 rgba(0,0,0,0.02)`: a soft drop shadow (8 down, blur 15; Gaussian sigma 7.5), very faint;
  - menus: `0 8px 48px 0 rgba(0,0,0,0.25)` (a much deeper shadow) with `#dbdbdb` edges.
- **Glass effect** (inner shadows, SVG export of a 1x1 control): inset dy +40 spread 40 blur sigma 15, grey 0.90,
  plus-darker; inset dy -40 blur sigma 5, grey 0.157, plus-lighter (top light); inset dy +40 blur sigma 5, grey 0.157,
  plus-lighter (bottom light). Menus: `inset 0 40px 10px -40px #282828` and `inset 0 -40px 10px -40px #282828`.
- **Toggled on (white) control**: over the fill, `#6f6f6f` **color-dodge** then white 65 % **screen** ("Extra Fill").
- **Symbol well inside a control** (Focus's round icon, 40 pt): `rgba(17,17,17,0.6)` luminosity, `#808080` color-dodge,
  `#222` plus-lighter.
- Vibrant label colours (dark): primary `#FFFFFF`, secondary `#999999`, tertiary `#404040`; vibrant fill primary `#333333`.
  Notification time text `#4d4d4d` with plus-lighter. Light menus: label `#1a1a1a`, destructive `#ff383c`.

## Status bar (iPhone 17 Pro)

- 62 tall; padding 9 left and right, 2.33 top. Three parts: the time centred in the left half (right padding 6), the
  Dynamic Island 125 x 37 (radius 100), the levels centred in the right half (right padding 1).
- Time: SF Pro **Semibold 17**, line height 22.
- Levels group 85.33 x 13: cellular 19.33 x 12.33, Wi-Fi 16.67 x 12.33 at +26.33, battery 27.33 x 13 at +50 (7 apart).
  On the lock screen the groups are 128 wide from x 9: levels start at 285.8, end at 363.2 (right margin 38.8).

## Control Center (iPhone)

**The running system (iOS 27.0 Simulator, accessibility frames) differs from the kit in places; it wins:** controls 70 on
a **uniform 85.33 pitch** from x 38 (15.33 apart; the kit's 15 / 17 pairs are not used), rows from y **132.3** (85.33
apart), the status row centred at y **104.2** (cellular icon 97.7-110.7, "Carrier" text 95-113, "100%" at x 284.3, the
battery 328.7-359), "+" and power 29 at (38, 23) and (335, 23). Edit mode: the grid moves up to y **86**, each control
gets a 26 pt delete button centred 8.4 pt in from its top-left corner, empty cells show as discs in a 4 x 8 grid,
"Add a Control" is a 142.3 x 34.3 capsule centred at y 800.5. The gallery: a sheet with "Search Controls" at the top.
The Simulator's Control Center starts empty (only third-party and a few system controls can be added there).

Kit values:

- Plus and Power: 28.67 round, at x 38 and 335.33, y 23 (centres 52.3 / 349.7, 37.3). Plus symbol 8.67 x 18.
- Status row ("Signal and Battery"): frame from y 60.67; icons at y +24.5 (85.2 to 98.2, centre 91.7): cellular + Wi-Fi
  from x 42.67; "100%" (text 42 wide) then, 6 apart, the battery 27.33 x 13.67, ending at 359.3.
- Grid: 1x1 = **70**, 2x1 = 155 x 70, 1x2 = 70 x 155, 2x2 = 155. Columns 37 / 122 | 209 / 294 (15 apart inside a pair,
  17 between the pairs); rows 132 / 303 / 389 / 475 / 560 (gaps 16, 16, 16, 15). Right margin 38.
- Corner radius: 1x1 and 2x1 fully round (100); sliders 34 (on a 70 width); 2x2 modules **30**.
- Plus and Power buttons: `#121212` with plus-lighter, round, padding 10. Active connectivity button (Wi-Fi on): solid
  `#0088ff` (iOS 27 blue), round.
- Page dots column at x 372 (21 wide), y 290 to 530: heart, dot, music, home, signal symbols.
- Connectivity 2x2: big buttons 57 at (14,13), (84.67,13), (14,84); small ones 25.67 at (84.67,84), (116,84),
  (84.67,115.33), (116,115.33).
- Media 2x2: artwork 53.67 at (12.67,13.33), AirPlay 40 at (100,13); title at y 74, artist at y 91 (18 lines, x 14);
  transport symbols centred at y ~130 (previous at x 24, play at 65 (24 x 30), next at 111).
- Sliders 70 x 155: brightness fill from the bottom (here 112 of 155), sun 27 at (21,107); volume speaker at (20,109).
- 2x1 (Focus): padding 14, gap 8; symbol well 40 round; title SF Pro Medium 15 (-0.2 tracking), white; detail Medium 14
  white 33 %, line 18.
- 1x1 symbol: SF Pro Bold 19 (-0.2), e.g. silent mode on: red `#ff383c` at 90 %.

## Notifications (lock screen list)

- List inset 14 from the screen sides (platters 374 wide); 8 between platters.
- Platter: radius 24 (glass layer 23); padding 14; min height 64 (single: 66.33); icon **38.33** at the padding; 10 gap;
  text from x 62.33.
- Title SF Pro **Semibold 15**, line 17, tracking -0.23, white; message **Regular 15**, line 18; time Regular 15, line 17,
  `#4d4d4d` plus-lighter, right aligned; image (if any) 32 square radius 6 below the time (5.5 gap).
- Stacks: the top platter's padding becomes 12 top / 27 bottom; each card behind shows 8 pt below the one in front, inset
  10 pt per side (second) and 20 pt (third); stack of 2 = 71.33 tall, 3 = 77.33.
- Lock screen: date SF Pro Medium 22 at y 76 (width 215, centred); time glyphs 205 x 87.4 at (98,115); widgets row y 214
  (72 tall: a 169-wide rectangular, two 72 circular); inline widget y 82; flashlight / camera 58 round at x 46 and 298,
  y 766 (the same clear glass as Control Center's controls; symbol SF Pro Bold 21, `#d9d9d9` plus-lighter at 90 %);
  home indicator 144 x 34 (shown) at y 840.

## Banners (running system)

- `BannerNotification` at x 8, y 58.7, 386 wide (8 from the sides); 66 tall with a title and one line, 96.7 with two;
  title from x 72 (64 from the banner's edge), body line 18 below it. Same platter as Notification Center's.

## App Library (running system)

- Category tiles 168.3 wide (with their name; 191 tall) at x 24.3 and 209.3, first row at y 168; inside, icons 68 (with
  their hit area) 76 apart, the last cell a 2 x 2 group ("System folder") that opens the category. Search field above
  (magnifier at y 103).

## Home screen

- Icons **64** at x 30, column pitch **92.67**; first row top 90 (status bar 62 + 28), row pitch **100.33**.
- Search pill 77 x 30 at (162.5, 704), fully round, padding 12 x 7, 2 between the magnifier and "Search" (40 x 16).
- Dock: outer padding 17 sides, 17 bottom, 20 top; dock itself padding 20 top, 19 bottom, 19 sides; radius **38**;
  icons 64; fill and rim as above (luminosity grey).
- Quick Actions menu: 250 wide, radius 30, padding 10 top / 8 bottom; rows 42 tall (2 top padding), 26 sides, symbol 20
  wide, 14 gap, SF Pro Regular 17 (line 22, tracking -0.43); a row of five 38 pt widget-size symbols (17 side padding)
  at the bottom. Light material (see above); menu over the icon whose 78 pt highlight sits below it.

## More from the full kit

- **Status bar (iPhone 17 Pro)**: 62 tall, padding 9 / 2.33 top; the time group 129.5 wide (right padding 6) with the
  time (17 pt, 37 x 22) centred at x **70.75**; the Dynamic Island 125 x 37 at (138.5, 13.67); the levels group 129.5 wide
  (right padding 1) holding cellular 19.33 x 12.33, Wi-Fi 16.67 x 12.33, battery 27.33 x 13, **7 apart**, from x 285.1 to
  **362.4**.
- **Notification, expanded (long look)**: content 370 wide (16 from the sides), radius **26**, header 66.33 (padding 14,
  gap 12, icon 38.33), the app's content below; a clear-glass menu under it (250 wide, radius 26, rows of 22 pt with 20
  between, padding 26 x 20, 17 pt symbol and label, 14 apart).
- **Context menu**: 250 wide, radius **34**, Regular glass (above), padding 10 top and bottom; an optional row of three
  actions (72.67 x 56, radius 20, `#ededed` linear-burn, 13 pt symbol, 12 pt Medium label, 6 apart, 10 from the sides);
  items 40 tall (16 from the sides, 6-8 inner padding, 8 between symbol and label), separators 21 tall (1 pt `#e6e6e6`
  linear-burn, 8 in); a submenu chevron 15 pt Bold. Behind it: black 23 %.
- **Home Screen widgets (iPhone)**: Small 164.67 square, **radius 28**, padding guide 129 (about 18 in); Medium, Large and
  XL follow the grid. Lock Screen widgets: circular 72 (58 circle), rectangular 162 x 72, inline 327 x 26.
- **App Library groups ("Launch Pad")**: 68 square, radius 12, four 23 pt icons (radius 5.25) 4.5 apart.
- **Sheets**: grabber component; dimming overlay black 20 %.

## Carried over (2026-10-07)

Control Center geometry and motion, notification platters, banners, folder springs, widget radius 28, glass lens /
dispersion / light, the long-press menu (Quick Actions layout and Regular glass), the long look. See `docs/PROGRESS.md`.
