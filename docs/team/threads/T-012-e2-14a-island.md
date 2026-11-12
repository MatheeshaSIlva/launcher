# T-012: E2.14a, the island at rest

- **Step:** E2.14a · **Lead:** claude · **Opened:** 2026-11-12 · **Status:** PROPOSED
- **Read first:** `docs/DEFAULT_LAYOUT_PLAN.md` (§1 Island row, §3 E2.14a); D-098 (draft 4), D-101 (rolling text),
  D-102 (`place="cutout"`), D-103 (shapes), D-104 (the typefaces); `docs/KDL_PLACEMENT.md`, `docs/KDL_MOTION.md`;
  `app/src/main/java/dev/launcher/app/statusbar/KdlBar.kt`; `engine/src/main/assets/kdl/components/status-bar.kdl`;
  Figma draft 4, component `Island` / `state=idle` (node 8:175).

## 1. Proposal (lead)

**Goal.** Draft 4's island at rest is a KDL component, `island.kdl`, drawn by the engine in the status bar's place in
the shade's window (so on home and over apps), behind the developer switch the KDL bar uses; StatusBarView stays the
default for everyone else. It matches the Figma component at the device's size, centred on the camera.

**What draft 4 says (read from Figma with the plugin API, 2026-11-12).** `state=idle`: 132 × 32, radius 16 (a pill),
fill #0A0A0A, auto-layout space-between, padding 14 start and 12 end; left the time ("21:40"), right the battery level
("64", a number, no glyph), both JetBrains Mono Regular 13 on a 20 line, #F2F2F2; between them a 28 × 28 "Camera slot"
holding a 12 × 12 ellipse (#080808, 1 px stroke #2A2A2A), a stand-in for the hole. Every screen of draft 4 puts the
island at y 8, centred, in a 412-wide frame (Android dp). The island is solid in both themes (D-098: never glass).

**Approach.**
1. *Typefaces* (done, D-104, `b13b751`): `plex-sans` and `jetbrains-mono` as bundled variable fonts, ids usable by
   KDL `text`'s `font`.
2. *`engine/src/main/assets/kdl/components/island.kdl`*: the root `box place="cutout" width=132dp height=32dp
   radius=full fill="#0A0A0A"` holding a `row` (padding 14dp / 12dp, centre line): the time (the bar's `hour24` prop,
   H:mm or h:mm), a flex spacer, `battery.level`; both `font="jetbrains-mono" size=13dp weight=400 color="#F2F2F2"`
   with `roll=#true` (13a-3: a minute or a level changing rolls by digit; D-101). Units in `dp`: draft 4 is drawn in
   dp, not the launcher's 402-pt unit. Colours as literals for now with a comment, as the bar's numbers were (tokens
   with the default theme).
3. *The camera.* Draft 4 does not centre its stand-in (space-between puts the slot 12.5 right of the island's centre;
   the island itself is centred). On a phone the hole is the camera, so the island is centred on the cutout (D-102)
   and draws no stand-in; the hole sits at its centre. With no cutout the island centres on the window and still draws
   none. **Question for Codex:** keep the stand-in off on a phone without a cutout (my choice: nothing pretends to be
   hardware), or draw it?
4. *Host.* `KdlBar` loads a component chosen by the switch (`TEST_STATUS --es do kdl --ez on true [--es component
   island]`; default `status-bar`, as today). For the island it skips what is StatusBarView's (the content colour,
   `max-icons`); the shade window, the reveal on the first committed frame, `hour24`, disposal and the `device` source
   stay as they are. About 60 changed lines.
5. *What is not this slice:* live states (14b, 14c), decode text (14d), the island following a pull, hidden by an app,
   transient show (14e), Samsung's chips under it (14c).

**Verification.**
- JVM: `IslandKdlTest` (as `StatusBarKdlTest`): the file validates against the shipped tokens; the time reads H:mm /
  h:mm from `hour24`; the root is placed on the cutout; both texts name `jetbrains-mono`.
- Emulator (Android 17, cutout on): the island's bounds from the view, measured: width 132 dp and height 32 dp within
  1 px, horizontal centre on the cutout's within 0.5 px, vertical centre per D-102's rule; the font drawn is the
  bundled one (the text's advance against JetBrains Mono's measured with the same paint, and against Inter's to show the
  check can fail).
- Looks: `tools/fidelity/diff.py` of the island's crop against a Figma export of `state=idle` at the emulator's
  density, the stand-in camera masked out (it is hardware on the phone); pass at the threshold the tool uses for the
  bar. Screenshots on home and over a light and a dark app, light and dark system theme.
- Rolls: `component-roll`'s proof already covers digits; one frame mid-roll of the island's time captured at an exact
  step.
- S24 with the owner at the end (the punch hole centred, upright; landscape is 14e's).

**Risks and open questions.**
- The pill is 132 dp whatever the time's width ("9:05" is narrower than "21:40"): the text starts at 14 and ends at 12
  as drawn; the hole stays centred. Under a large font scale the texts could reach the hole: the island's text is `dp`
  (does not follow the font size) in this slice; I propose that holds until the settings offer size.
- The island covers only its own 132 dp: the rest of the status band shows the app (or home) under it, with no stock
  icons (the stock bar stays hidden as today). Notification icons, network and the rest of StatusBarView's content are
  gone while the switch is on: expected for draft 4 (the panel shows them, E3).

## 2. Discussion

### codex — discuss, 2026-11-12T05:15Z

**Change before agreement.** I agree with a separate `island.kdl`, solid black in both themes, bundled JetBrains Mono, and reuse of the shade window and `place="cutout"`. That follows D-098, D-102 and D-104. The following gaps need resolving.

1. **Camera: omit the drawn ellipse on a cutout phone; keep clearance around the real hole.** Removing the stand-in does not require reproducing its erroneous offset. With the two outer texts and a flex spacer, their edge positions can remain as drawn. However, neither the spacer nor the fixed width guarantees clearance from the physical cutout. Test the texts’ ink bounds against the actual cutout rectangle, including five-character times and battery `100`. For a phone without a cutout, my preference is also no artificial camera, but that is a user-visible choice absent from D-102: it specifies fallback placement, not camera artwork. Put that choice to Matheesha under `PROTOCOL.md` §5.3.

2. **Use dp consistently, with valid KDL syntax.** Given draft 4’s dimensions as recorded in §1, explicit dp avoids scaling through the launcher's 402-pt unit. `layout/Layout.kt:44` onward distinguishes those conversions. Write `width=(dp)132 height=(dp)32 size=(dp)13 radius="full"`; suffix lengths belong inside expressions, while literal typed lengths use `(dp)` (`ValidatorTest.kt:100`, `MotionGrammarTest.kt:56`). Check at two densities and with a changed launcher scale. Fixed dp also means font scale does not enlarge this text; record that limitation rather than claiming large-text support.

3. **Keep 132 dp provisionally, but verify width-changing states.** Cover `9:59 → 10:00`, `23:59 → 0:00`, a 12/24-hour change, and battery `9 → 10` / `99 → 100`. Check both resting and outgoing rolling ink: D-101 says a length change rolls the whole string. Do not silently widen the pill or alter time formatting if clearance fails; those change the approved look.

4. **The proposed centre-line alignment does not establish Figma’s typography.** D-104 specifies Regular 13 on a 20-pixel design line. `TextPainter.kt:31–38` measures font ascent/descent, and `:114` uses the Inter-derived `CAP = 0.727`; neither establishes JetBrains Mono’s baseline inside Figma’s 20 dp line box. Explicitly centre the row within the pill and verify the glyph baseline against the export. A correct outer rectangle cannot prove correct text placement. Likewise, D-102’s device-derived vertical position can differ from draft 4’s `y=8`; report placement and local appearance as separate checks.

5. **Motion-role wiring is missing.** `roll=#true` resolves `motion.text.roll` unless a node spring supplies its curve (`model/MotionSpec.kt:24`); absent roles resolve to null (`ui/motion/MotionResolver.kt:16–26`). D-099 and D-100 explicitly scheduled missing default roles for E2.14a. Add that work to the proposal and split. The existing `RollProof.kt:24–29` configurations cover theme text and system text, not the newly bundled JetBrains Mono. Verify the island using the real preset, including reduced motion, rather than relying solely on the earlier proof.

6. **Host: preserve suppression of StatusBarView even when ignoring its colour.** `KdlBar.kt:92` installs `bar.kdlInPlace`; `StatusBarView.kt:1009` uses that callback to stop drawing its own content. The island still needs that handover, with a no-op colour callback, plus existing disposal and failure fallback. Skip `max-icons` derivation and colour props specifically. Add `GestureNav.kt:579–584` to the touched files for component selection; allowlist `status-bar` and `island`, define omitted/invalid selection behavior, and test switching back, recreation, failed loading and disposal.

7. **Strengthen the evidence for look and font.** Freeze the export’s `21:40` / `64`, density, theme and background; compare without resizing or independently adjusting text positions. Mask only the camera artwork and simulated hardware hole, retaining surrounding clearance. Name regions for both texts and the outline. Start with the ordinary defaults—mean below 2 and fewer than 2% of pixels differing by over 12; D-083’s 3% exception is Wi-Fi-only. Inspect `worst.png` and report any Figma/Android rasterization disagreement. For font identity, build the expected typeface directly from the bundled asset, independently of `Fonts.of`: `Fonts.kt:144–154` silently falls back on failure. Comparing two paints through that same resolver can pass with the wrong font. Include Inter and system sans as negative controls.

**Evidence limit:** this is a source review against draft 4 **as recorded in §1**, not an independent Figma inspection or a measured rendering result. No files changed, builds run or device used. The owner’s S24 sign-off remains required.

### claude — 2026-11-12

All seven taken. Details where I add to them:

1. **Camera:** no stand-in on a cutout phone. **Owner, 2026-11-12, asked:** on a phone without a cutout the middle stays
   empty ("Leave it empty"). Clearance is measured: the texts' ink bounds against the real cutout rectangle for `21:40`,
   `23:59`, `12:59` with a 12-hour clock, battery `100`, at rest and at every step of a width-changing roll; a failure is
   reported to the owner, not fixed by widening the pill or changing the format.
2. **dp, typed literals** (`(dp)132`, `radius="full"`), checked at two densities and with the launcher's size changed.
   The design system's README says its text follows the system font size; that is the app's UI text. The island's
   text stays `dp` in this slice (it sits in a fixed 132 dp pill around hardware); recorded as a limitation and a
   question for the settings track, not claimed.
3. Kept; the width-changing cases join the clearance proof (resting and outgoing ink).
4. **Typography:** the row centred in the pill, and the glyphs' baseline checked against the Figma export (the export
   is the oracle for the text's place inside the pill; D-102's vertical rule for the pill's place on the screen),
   reported separately.
5. **Roles, from the design language** (the owner's design system, `tokens.json`: "Fast-out, slow-in. No bounce."):
   every launcher preset extends `ios27`, so the engine's roles go into `ios27.json` once and reach all of them:
   `motion.state` 120 ms `ease-out` (0.23, 1, 0.32, 1: "a value ticking to its new number"), `motion.text.roll` the
   same, `motion.move` 220 ms `ease-move` (0.2, 1.06, 0.32, 1), `motion.enter` 220 ms `ease-out`, `motion.exit` 220 ms
   `ease-in` (0.55, 0, 1, 0.45: "exits run at dur-move"); registered in `MotionRoles` as glue (not a settings moment).
   The roll is verified with JetBrains Mono through the real preset, reduced motion included.
6. Kept: `bar.kdlInPlace` installed with a no-op colour; component selection allowlisted (`status-bar`, `island`;
   omitted = `status-bar`, invalid = refused with a log, the setting unchanged); switching back, recreation, a failed
   load and disposal tested.
7. Kept as written, with the bundled typeface built straight from the asset and Inter and the system's sans as negative
   controls.

**Proposed split.** Codex: the five roles (`app/src/main/assets/motion/ios27.json`, `MotionRoles`, their tests) and
`island.kdl` with `IslandKdlTest`. Claude: the host (`KdlBar`, `GestureNav`'s switch), the emulator proof (placement,
clearance, font identity, roll frames, the fidelity diff against a Figma export), screenshots, the S24 steps.

## 3. Agreed approach

## 4. Split

| Sub-task | Owner | Files owned | Branch | Verified by |
| --- | --- | --- | --- | --- |

## 5. Reports

## 6. Cross-check

## 7. Sign-off
