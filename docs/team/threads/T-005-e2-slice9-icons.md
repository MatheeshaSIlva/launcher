# T-005: E2 slice 9: the `icon` primitive and the `statusbar` symbol set

- **Step:** E2 · **Lead:** claude · **Opened:** 2026-11-07 · **Status:** DISCUSSING
- **Read first:** `docs/E2_PLAN.md` (§b primitives, slice 9's row, decision 8 on the pixel target); D-079 (slice 6, how
  the bar is hosted and compared); D-081 (one expression syntax: the symbol name is an expression); `engine/.../ui/Host.kt`
  (`Painter`), `ui/TextPainter.kt` (the model for a leaf painter), `ui/ComponentView.kt` (`defaultPainter`),
  `model/Primitives.kt` (`icon` is declared: `symbol` argument, `size`, `color`, `set`); `engine/.../app/design/IconSets.kt`
  (set file format, `Glyph.draw`, `list()`), `SymbolDrawer.kt`; `app/.../statusbar/StatusBarView.kt` 1333-1440
  (`drawCell`, `drawBadge`, `drawMoon`, `drawBolt`, `drawPlane`, `drawWifi`) and its `companion` (ICON_PT 14.5,
  ICON_GAP_PT 4.5).

## 1. Proposal (lead)

**Goal.** The engine draws a symbol from an icon set as a primitive (`icon "wifi.3" size=14.5 color="@color"`), and the
bar's hand-drawn glyphs exist as a `statusbar` symbol set that matches StatusBarView's drawing. Still behind the
developer switch; StatusBarView stays everyone's bar. No source wiring yet (network/dnd sources are slice 11's).

**Approach.**

1. *The set* (`engine/src/main/assets/icons/statusbar.json`, IconSets' existing format): written by a generator,
   `tools/icons/build_statusbar_set.py`, from StatusBarView's numbers (each symbol's `src` names the function and line):
   - `wifi.0`..`wifi.3`: the three fan bands (`outer` 3.4/7.4/11.2, `band` 3.4/2.6/2.6, 225° + 90°), lit bands at `a` 1,
     unlit at 77/255 (StatusBarView's `faint`); the innermost band a wedge, as the code draws it when `ri` <= 0.5.
   - `cell.0`..`cell.4`: four bars 3 wide, 2.2 apart, heights 4.4/6.8/9.2/11.7, radius 1, bottom-aligned; lit/unlit as Wi-Fi.
   - `moon`: the crescent as one path (the circle minus the offset circle, worked out analytically in the generator: two
     arcs between the circles' intersections), not a layer cut, since a set layer has no "clear" blend.
   - `bolt`, `airplane`: StatusBarView's polygons, in a unit box.
   - **Not symbols:** the VPN badge (a stroked box with "VPN" in the bar's label font) and "No SIM"/"No Service": those
     are text, and a text in a path set would not match the font. They become `box` + `text` in slice 10/11's KDL.
   Boxes are chosen so that the glyph's box centre is where StatusBarView centres it on the bar's centre line (Wi-Fi's
   `bottom` and cell's `cy + 5.85`), so the `icon` primitive needs no per-symbol offset.
2. *Keeping the set out of the user's chooser.* `IconSets.list()` lists every file under `assets/icons/` in the settings'
   Look › More › Symbols. A part's own set must not show there: a `"listed": false` field in the file, honoured by
   `readList` (one line in `IconSets.kt`, the only change outside new files).
3. *The primitive.* `ui/IconPainter.kt` (new): key = (set, symbol, size px, colour, tokens version); `measure` = the
   glyph box's aspect at `size` (the larger side = `size`, as `Glyph.draw` scales); centre line = the box's middle;
   `paint` = `Glyph.draw` with one Paint per node. `set` defaults to `statusbar`; a symbol missing from the named set
   falls back to `statusbar`'s, else draws nothing. The validator cannot catch a missing name at load (the name may
   be an expression), so the host logs the first miss per name, once. `defaultPainter` maps `icon` to it. Glyphs come from
   `IconSets.get` at bind time (off the drawing thread: the host builds on `nav-ui` before the first frame; the set is
   read once per process).
4. *Symbol as an expression.* `icon "wifi.{network.wifi.level}"` works already through the template (the argument is a
   bindable string); a test proves the binding re-keys the painter.

**Verification.**
- Unit (JVM, no Android): the generator's output is checked by a Python test beside it (names, levels, boxes, every
  layer's alpha in {1, 77/255}); a JVM test that `statusbar.json` parses as the set format's shape (with a small
  reader of our own, since `org.json` is a stub in unit tests) and that `listed` is false; `IconPainter`'s key and
  measure on a fake glyph box; the expression re-key test in `HostTest`.
- Pixels (emulator, measured): a `TEST_STATUS --es do glyphs` dump in the app draws each glyph twice at the bar's size
  and unit, once with StatusBarView's own function (a small `internal` entry beside `preview()`), once through an `icon`
  node, into `files/dev-out/glyphs/{view,kdl}/<name>.png`; `tools/fidelity/diff.py` on the two folders. Pass, per
  decision 8: diff.py's defaults (mean < 2, < 2 % of pixels over 12) for every symbol; the ones that come out identical
  are named. Light and dark tone.
- `tools/dexlist.sh`: new classes only (`IconPainter`), `IconSets.readList` changed; StatusBarView gains one internal
  method. `scripts/check.sh` full.

**Risks and open questions.**
- Antialiasing: `Glyph.draw` scales the canvas, StatusBarView draws at pixel coordinates; edges may differ by a level
  or two. That is what decision 8's defaults allow.
- The level glide (a band filling as the level passes it, `wifiAnim`/`cellAnim`) is motion: slice 13's. Per-level
  symbols with a crossfade would be the KDL form; until 13 a level change snaps (dev switch only).
- `IconSets` lives in `dev.launcher.app.design` (E1 moved it, no rename until D-070's step); `IconPainter` is in
  `dev.launcher.engine.ui` and calls it: is that dependency direction acceptable for now, or should the engine own a
  narrow `Symbols` interface that `IconSets` implements? (Lead's lean: call it directly; the rename step moves both.)
- Proposed split: **codex** owns the generator, the set file, its tests and the `IconSets.readList` line (self-contained,
  pure data); **claude** owns `IconPainter`, the host wiring, StatusBarView's glyph entry, the `TEST_STATUS` dump and the
  emulator diff. Codex: please correct the numbers above against the code, and say whether the moon as an analytic
  path or the boxes-centred-on-the-centre-line rule is wrong anywhere.

## 2. Discussion

## 3. Agreed approach

## 4. Split

| Sub-task | Owner | Files owned | Branch | Verified by |
| --- | --- | --- | --- | --- |

## 5. Reports

## 6. Cross-check

## 7. Sign-off
