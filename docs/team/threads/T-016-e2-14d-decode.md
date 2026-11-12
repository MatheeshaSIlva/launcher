# T-016: E2.14d, decode text

- **Step:** E2.14d · **Lead:** claude (solo, D-116; Codex reviews on its return) · **Opened:** 2026-11-13 · **Status:**
  DONE pending the gate
- **Read first:** `docs/DEFAULT_LAYOUT_PLAN.md` §2 ("Decode text"); `docs/ENGINE_SPEC.md` (Type: "`decode` as a text
  effect with its own duration and glyph pool"; the theme's `decode` token).

## 1. What was built

- **`DecodeText`:** a changed character scrambles through a pool (a new glyph each 50 ms, the same within a cycle,
  deterministic) and settles in order across the duration (the first at 35 %, the last at 100 %).
  - Unchanged characters and spaces stay.
  - A change mid-way decodes from what is shown.
  - Motion reduced, or a duration of 0, lands at once.
- **The host:** steps it as recurring work (the loop's and the scroll's mechanism). A leaving or unseen text lands at
  once. The clock stops when it settles.
- **`TextPainter`:** draws the scramble while measuring the new text, so nothing around it moves.
- **Schema and docs:** `decode` (ms, 0..3000, literal) and `decode-glyphs` (literal) on `text`. `docs/KDL_MOTION.md`.

## 2. Verified

- **JVM:** `DecodeTextTest` (order and times, cycles, spaces, a longer text, mid-way changes, reduced motion, an
  unchanged text) and `DecodeSchemaTest`.
- **Emulator:** `component-decode`. Mid-way the picture is neither the old nor the new text; it settles and holds
  still; without `decode`, at once.

## 3. Assumptions (owner away; to review)

1. **No use on the island yet:** draft 4 does not ask for it; it is an engine capability a theme or component turns on.
   The theme token `decode` (ENGINE_SPEC) that switches it on for every changing text comes with the theme system.
2. **Timing:** a glyph every 50 ms, settling across 35 % to 100 % of the duration, left to right (right to left text
   follows its characters' order, not its visual order).
3. **The default pool:** A-Z, 0-9 and `# % & * + = < >`. Proportional fonts change width while scrambling; the layout
   holds the new text's width, so only the glyphs move.
