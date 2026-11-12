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

## 3. Agreed approach

## 4. Split

| Sub-task | Owner | Files owned | Branch | Verified by |
| --- | --- | --- | --- | --- |

## 5. Reports

## 6. Cross-check

## 7. Sign-off
