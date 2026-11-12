# T-012: E2.14a, the island at rest

- **Step:** E2.14a · **Lead:** claude · **Opened:** 2026-11-12 · **Status:** AGREED
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

### codex — discuss, 2026-11-12T05:27Z

**Agree with the revised approach and split.** The owner’s “Leave it empty” resolves the camera question. The reply also retains the clearance, typography, host lifecycle and fidelity checks requested in my first round.

- **Motion:** all five other bundled presets extend `ios27` (`app/src/main/assets/motion/*.json`), so adding the five defaults there closes D-099/D-100’s deferred wiring. Store the supplied cubic-bezier controls explicitly: `KDL_MOTION.md`’s built-in `ease-out` means different controls. Test exact durations/controls, inherited resolution and registration once (`MotionTest.kt:115–123`); retain the real-preset JetBrains Mono and reduced-motion proof.
- **Ownership:** I accept the five roles in `ios27.json`, `MotionRoles.kt` and their tests, plus `island.kdl` and `IslandKdlTest`. Claude owns the host, switch, emulator proofs, fidelity work and S24 checks. Please name the exact test/proof paths when filling §4, as `PROTOCOL.md` §4 requires.
- **Acceptance:** fixed dp text remains a stated limitation; clearance failures go to the owner without changing the approved geometry. Placement follows D-102; local typography follows the export. These remain separate measurements.

**Judged:** ready to implement; no further approach changes required. This round reviewed sources and the lead’s recorded design-system values; no independent Figma inspection, rendering measurements, builds, edits or device use.

codex: AGREE approach — revised island, motion defaults, verification and proposed split accepted.

## 3. Agreed approach

§1 as revised by the lead's reply (2026-11-12): no camera stand-in anywhere (owner: on a phone without a cutout the
middle stays empty); dp typed literals; 132 dp kept, clearance measured and reported, never fixed by changing the
approved geometry; the row centred in the pill and the glyphs checked against the Figma export, apart from D-102's
placement; the design language's five roles in `ios27.json` (explicit cubic-bezier controls: `motion.state` and
`motion.text.roll` 120 ms (0.23, 1, 0.32, 1); `motion.move` 220 ms (0.2, 1.06, 0.32, 1); `motion.enter` 220 ms
(0.23, 1, 0.32, 1); `motion.exit` 220 ms (0.55, 0, 1, 0.45)); the host keeps StatusBarView's handover, allowlists the
component; fidelity against the Figma export (`tools/fidelity/golden/island/idle-x2.625.png`, exported at the
emulator's 2.625 from `state=idle`, camera stand-in masked), font identity from the asset with Inter and the system's
sans as negative controls. Fixed dp text is a stated limitation.

- `codex: AGREE approach — revised island, motion defaults, verification and proposed split accepted.` (05:27Z)

## 4. Split

| Sub-task | Owner | Files owned | Branch | Verified by |
| --- | --- | --- | --- | --- |
| The five motion roles | codex | `app/src/main/assets/motion/ios27.json`, `app/src/main/java/dev/launcher/app/motion/MotionRoles.kt`, `app/src/test/java/dev/launcher/app/MotionTest.kt` (or a new `EngineRolesTest.kt`) | `team/codex/e2-14a-island` | JVM: exact durations and controls, inherited by every preset, registered once |
| `island.kdl` | codex | `engine/src/main/assets/kdl/components/island.kdl`, `engine/src/test/java/dev/launcher/engine/ui/IslandKdlTest.kt` | `team/codex/e2-14a-island` | JVM: validates, H:mm / h:mm, `place="cutout"`, `jetbrains-mono`, dp sizes, `roll` on both texts |
| Host and switch | claude | `app/src/main/java/dev/launcher/app/statusbar/KdlBar.kt`, `app/src/main/java/dev/launcher/app/GestureNav.kt` (the switch), a JVM test of the allowlist | `team/claude/e2-14a-island` | JVM; emulator: switch on, back, recreation, a failed load |
| Emulator proof and looks | claude | `engine/src/androidTest/java/dev/launcher/engine/adb/IslandProof.kt` (new mode `component-island`), `tools/fidelity/golden/island/` | `team/claude/e2-14a-island` | emulator: size and placement, clearance, font identity, roll frames, `diff.py`; screenshots light/dark over home and apps |

## 5. Reports

### claude — report (`055cbfe`, `7998f74`, `c381e60`; Codex's `090918d` committed for it after its run timed out)

- **Changed:** `KdlBar` shows an allowlisted component (`COMPONENTS`: `status-bar`, `island`; none named = the status
  bar, anything else refused with a log and nothing changed), the island without StatusBarView's colour or
  `max-icons`, StatusBarView still handing over (`kdlInPlace` a no-op for the island); `GestureNav`'s switch takes
  `--es component`. `KdlBarChoiceTest` (allowlist, refusals, every allowed file ships).
- **Owner, 2026-11-12, second question:** the emulator's centred punch hole has its centre 12.5 dp from the top; a
  32 dp pill centred there stood 9 px above the screen. Asked: "Keep the 8 dp gap". Built as a root attribute,
  `place-gap` (a literal length, only beside `place="cutout"` on a root; refused otherwise with spans:
  `place-gap-needs-cutout`, `place-root-only`, `not-bindable`): the root's top never nearer the screen's top than it;
  without it D-102 is unchanged. `island.kdl` takes `place-gap=(dp)8`. **Codex: this is grammar in your files
  (`Primitives`, `Validator`, `PlaceTest`); please review it as yours.**
- **A pre-existing engine bug found and fixed:** the KDL status bar crashed the launcher at every start in the app
  (`IndexOutOfBoundsException` in `Policy.measure`, since 13a-2's drawn list): a node's children changed between its
  composition and its measure. A node now measures the children it composed. New proof `component-measure` (a prop set
  again from a frame callback after the recomposer's, 40 frames): crashes the process with the old `ComposeHost.kt`
  (same exception, read from the log), passes with the fix. `component-motion`, `component-shape`,
  `component-placement` pass with it.
- **Measured, emulator (`component-island`, emu01, 2.625):** pill 346.49 × 84.00 px (132 × 32 dp: 346.5 × 84.0),
  centre off D-102's point with the 8 dp gap by (0.000, 0.000) px; clearance against draft 4's 28 dp camera slot on
  the pill's centre (the emulator's cutouts are a notch wider than the pill or a corner hole, not a centred punch
  hole): the time's ink ends 1 px before it, the battery's starts 67 px after, and every frame of 9:59→10:00,
  23:59→0:00, 12:59→1:00 (12-hour), 9→10 and 99→100 keeps 1 px or more (15 frames each at 120 Hz, the role's 120 ms);
  typeface: the time's ink against the asset's JetBrains Mono 0.0005, the system's monospace 0.2716, sans 0.2864;
  `Fonts.of("jetbrains-mono")` is the asset (0.0000); the face rolls by character (API 37); reduced motion lands at once.
- **Looks, `diff.py` against the Figma export (camera slot masked):** FAIL at the defaults, overall mean 7.58, 7.0 %
  of pixels over 12; regions time, battery and outline over. Read: the pill's outline and the texts' places agree
  within 1-2 px (ink time x 39-135 Figma, 39-134 ours; battery 275-310, 277-311; ours about 1 px higher), and side by
  side they read the same; Figma rasterises the glyphs heavier and about 1 px taller (best one-pixel alignment still
  leaves 13 % of the time's pixels over 12). Our text is Android's own rendering of the bundled face (0.0005 above). I
  propose, as for 13c's shapes (D-103), judging glyphs against the device's own rendering of the expected face, size and
  place, with `diff.py`'s numbers against Figma reported, not judged. **Codex: please agree or not.**
- **Screenshots (emulator):** home and an app (Settings), light and dark: the pill around the camera, time left,
  battery right (`tools/shots/island/tops.png`, not committed). Switching status-bar ↔ island, a refused name, off and
  on: no crash, each load logged.
- **Every device proof run on this tip** (emulator): component-lifecycle, -refusal, -icons, -icon-reveal, -layer,
  -motion, -roll, -placement, -shape, -measure, -island, device-display: PASS. Two were failing before this task for
  reasons of their own, fixed in `ComponentProof.kt`: `component-lifecycle` validated the status bar with every token
  existing, which slice 3b's `prop-shadows-token` refuses (now only `comp.*` exists); `component-icons`' "no icon" case
  used the status bar, which has had icons since slice 9 (now a component with none). The icons proof also returns its
  own failure's words.
- **Not verified:** the S24 (its hole's place; frame stats); a phone without a cutout on the device (JVM and the
  placement proof's fallback only); large font scale (the text is dp, a stated limitation).

## 6. Cross-check

### codex — review, 2026-11-12T07:07Z

**Judged: changes requested before sign-off.** Reviewed all four commits in `D:/Launcher`; inspected the saved fidelity report, side-by-side image and `worst.png`. No files changed, builds run or device used: this session is read-only, and Device belongs to claude.

- **fix — `app/src/main/java/dev/launcher/app/GestureNav.kt:581`:** omitted `component` preserves the previous choice, whereas §3 and §5 say omitted means `status-bar`. Call `setComponent` for omission too, and test the sequence island → omitted selection. `KdlBarChoiceTest` tests `choose(null)`, bypassing this handler.
- **fix — `engine/src/androidTest/java/dev/launcher/engine/ui/MeasureProof.kt:75`:** `read("n")` reads a source, not the prop; accepting `Value.Null` makes this final-state assertion ineffective. Assert the final measured/drawn child count and row width independently. The no-crash regression remains useful, and `ComposeHost.kt:49–59` correctly captures the composed list for measurement.
- **fix — `engine/src/androidTest/java/dev/launcher/engine/ui/IslandProof.kt:69`:** replacing `roll=#true` with an explicit curve bypasses the agreed real-preset proof. Keep this deterministic geometry proof, but add evidence using the unchanged shipped component and actual preset resolver, including reduced motion and a live 12/24-hour change.
- **fix — `engine/src/androidTest/java/dev/launcher/engine/ui/IslandProof.kt:178`:** the font comparison crops each rendering to its ink and aligns their top-left corners; it cannot establish the glyphs’ original position. Add absolute baseline/ink-bound checks for **both** texts against independently specified local geometry. Include the agreed bundled Inter negative control alongside system sans.
- **fix — `docs/KDL_PLACEMENT.md:3`:** document `place-gap`, its units, root/cutout restrictions, screen-relative minimum top and no-cutout behavior. The new grammar is absent from its owning documentation.
- **note — `engine/src/main/java/dev/launcher/engine/ui/CutoutPlacement.kt:32`:** the gap calculation correctly accounts for the resting screen origin and preserves D-102 when absent. Validator restrictions and span tests are consistent. `IslandProof.kt:318` checks a synthetic 28 dp slot; actual camera clearance remains unverified until the S24 check.
- **note — `engine/src/androidTest/java/dev/launcher/engine/ui/ComponentProof.kt:42` and `:141`:** both stale-fixture corrections preserve their original assertions: token-listener disposal and immediate preparation without icons.

**Glyph decision — agree conditionally.** Judge rasterization against Android’s rendering of the independently loaded expected face, weight and size, with absolute placement checked separately. Keep the Figma result explicitly **FAIL: mean 7.578; 6.97% over 12**. This does not waive baseline, padding or outline differences. `tools/shots/island/regions.json:1` defines “outline” as the entire crop, so its score cannot isolate the pill’s edge; use an edge-only region before attributing the failure entirely to glyph rasterization. Owner approval on the S24 remains required.

**Security checklist (§7), source-reviewed:**

- Caller checks: existing DUMP permission protects the changed broadcast handler (`GestureNav.kt:627`).
- Bounded inputs: component selection uses a fixed allowlist; grammar adds one literal scalar. Overall KDL size/depth/time bounds were not established by this review.
- Escape paths: no new KDL/package route to shell, reflection, arbitrary paths or other apps’ components.
- Capabilities: cutout placement still requires declared `device`; source-access enforcement is unchanged.
- Captures: the new proof captures its own test window; no new persistence of another app’s frames.
- Secrets: no changed secret handling or logging.
- Refusals: `PlaceTest.kt:27` covers invalid gap placement/binding; `KdlBarChoiceTest.kt:23` covers rejected names and paths. The sender-refusal proof still requires its documented log check.

### codex — review, 2026-11-12 (written to its output; copied here by the lead)

**Judged: changes requested before sign-off.** Reviewed all four commits in `D:/Launcher`; inspected the saved fidelity report, side-by-side image and `worst.png`. No files changed, builds run or device used: this session is read-only, and Device belongs to claude.

- **fix — `app/src/main/java/dev/launcher/app/GestureNav.kt:581`:** omitted `component` preserves the previous choice, whereas §3 and §5 say omitted means `status-bar`. Call `setComponent` for omission too, and test the sequence island → omitted selection. `KdlBarChoiceTest` tests `choose(null)`, bypassing this handler.
- **fix — `engine/src/androidTest/java/dev/launcher/engine/ui/MeasureProof.kt:75`:** `read("n")` reads a source, not the prop; accepting `Value.Null` makes this final-state assertion ineffective. Assert the final measured/drawn child count and row width independently. The no-crash regression remains useful, and `ComposeHost.kt:49–59` correctly captures the composed list for measurement.
- **fix — `engine/src/androidTest/java/dev/launcher/engine/ui/IslandProof.kt:69`:** replacing `roll=#true` with an explicit curve bypasses the agreed real-preset proof. Keep this deterministic geometry proof, but add evidence using the unchanged shipped component and actual preset resolver, including reduced motion and a live 12/24-hour change.
- **fix — `engine/src/androidTest/java/dev/launcher/engine/ui/IslandProof.kt:178`:** the font comparison crops each rendering to its ink and aligns their top-left corners; it cannot establish the glyphs’ original position. Add absolute baseline/ink-bound checks for **both** texts against independently specified local geometry. Include the agreed bundled Inter negative control alongside system sans.
- **fix — `docs/KDL_PLACEMENT.md:3`:** document `place-gap`, its units, root/cutout restrictions, screen-relative minimum top and no-cutout behavior. The new grammar is absent from its owning documentation.
- **note — `engine/src/main/java/dev/launcher/engine/ui/CutoutPlacement.kt:32`:** the gap calculation correctly accounts for the resting screen origin and preserves D-102 when absent. Validator restrictions and span tests are consistent. `IslandProof.kt:318` checks a synthetic 28 dp slot; actual camera clearance remains unverified until the S24 check.
- **note — `engine/src/androidTest/java/dev/launcher/engine/ui/ComponentProof.kt:42` and `:141`:** both stale-fixture corrections preserve their original assertions: token-listener disposal and immediate preparation without icons.

**Glyph decision — agree conditionally.** Judge rasterization against Android’s rendering of the independently loaded expected face, weight and size, with absolute placement checked separately. Keep the Figma result explicitly **FAIL: mean 7.578; 6.97% over 12**. This does not waive baseline, padding or outline differences. `tools/shots/island/regions.json:1` defines “outline” as the entire crop, so its score cannot isolate the pill’s edge; use an edge-only region before attributing the failure entirely to glyph rasterization. Owner approval on the S24 remains required.

**Security checklist (§7), source-reviewed:**

- Caller checks: existing DUMP permission protects the changed broadcast handler (`GestureNav.kt:627`).
- Bounded inputs: component selection uses a fixed allowlist; grammar adds one literal scalar. Overall KDL size/depth/time bounds were not established by this review.
- Escape paths: no new KDL/package route to shell, reflection, arbitrary paths or other apps’ components.
- Capabilities: cutout placement still requires declared `device`; source-access enforcement is unchanged.
- Captures: the new proof captures its own test window; no new persistence of another app’s frames.
- Secrets: no changed secret handling or logging.
- Refusals: `PlaceTest.kt:27` covers invalid gap placement/binding; `KdlBarChoiceTest.kt:23` covers rejected names and paths. The sender-refusal proof still requires its documented log check.

### claude — reply to the review, 2026-11-12

All five fixes taken; both notes and the conditional agreement on glyphs accepted. Measured on the emulator after them:
`component-measure` and `component-island` PASS; the app's switch checked on the emulator.

- **Omitted component (fixed):** the switch always goes through `setComponent`; none named is the status bar. Emulator:
  island, then `--ez on true` with no name: "KDL status-bar on" and the status bar loaded; `bogus` refused, unchanged.
- **MeasureProof's last state (fixed):** the final check now counts the boxes drawn (a software draw, red columns):
  40 px for n = 4, as expected; the old `read("n")` is gone.
- **The real preset (fixed):** the engine's test package carries the app's assets (`engine/build.gradle.kts`,
  `androidTest` only), so the proof loads `Design` and runs the shipped `island.kdl` unchanged: `motion.text.roll` in
  preset "iOS 27" resolves to `Ease(0.23, 1, 0.32, 1, 120 ms)`; every roll (15 frames each) runs on it; a live 24- to
  12-hour change (13:05 to 1:05, the whole string) rolls in 15 frames; reduced motion lands at once.
- **Absolute places (fixed):** each text's ink box against draft 4's numbers from the face's own metrics (line box 20 dp
  at the pill's top + 6 dp, text centred in it; 14 dp left, 12 dp right): time off by (0.50, 0.71, 0.50, -0.29) px,
  battery (0.25, -0.29, 0.25, -0.29) px (left, top, right, bottom), all within 1 px. Inter from the asset joins the
  controls: JetBrains Mono 0.0005, Inter 0.2658, system monospace 0.2716, sans 0.2864.
- **`KDL_PLACEMENT.md` (fixed):** `place-gap` documented: units, root and cutout only, measured from the screen, the
  no-cutout fallback, refusal codes.
- **Glyphs (agreed as you put it):** judged against Android's rendering of the independently loaded face; absolute
  places checked apart (above). Figma recorded as **FAIL: mean 7.578, 6.97 % over 12**. The outline by its edges
  alone: top 0.10 mean / 0.4 %, bottom 0.07 / 0.2 % (pass); the round ends 1.53 / 3.9 % and 1.83 / 4.4 % (over by
  share, max 81). Read: our crop starts 0.25 px right of the pill's left edge (346.5 px in 347, the pill at 366.75,
  the crop at 367) where Figma's starts at it; a quarter pixel moves every arc pixel's coverage by about that much (~64
  levels); the pill's geometry measured 0.000 px off its point. Reported, not judged; the owner's S24 sign-off stands.

### codex — sign-off round, 2026-11-12 (copied by the lead)

codex: OBJECT — one requested assertion remains missing in `50315b6`.

- **fix — `engine/src/androidTest/java/dev/launcher/engine/ui/MeasureProof.kt:78–81`:** counting 40 red columns establishes total box coverage, but does not independently assert the final row width or spacing. Four contiguous boxes would also pass. Assert four separate 10 px runs, three 4 px gaps and a 52 px span, alongside the measured row width.

**Judged, source review:** the omitted-component handler, unchanged island on the real preset, live 12/24-hour change, absolute placement of both texts, Inter control and `place-gap` documentation address the other findings. Glyph agreement stands with Figma explicitly **FAIL: mean 7.578; 6.97% over 12** and edge differences reported.

No files changed, builds run or device used. The remaining proof belongs to Claude; S24 clearance, frame statistics and owner approval remain outstanding.

### claude — 2026-11-12

- **MeasureProof (fixed):** four separate runs of red columns, each 10 px, gaps of 4 px, 52 px end to end, checked
  apart: emulator PASS, "4 boxes of [10, 10, 10, 10] px, gaps [4, 4, 4], span 52 px (n = 4)".

### codex — sign-off round 2, 2026-11-12 (copied by the lead)

codex: OBJECT — `d630a4d` fixes the drawn runs, gaps and span, but leaves the requested measured row width unchecked.

- **fix — `engine/src/androidTest/java/dev/launcher/engine/ui/MeasureProof.kt:85–89`:** all assertions derive from rendered pixels. Also assert the root row’s measured width is 52 px; an oversized measured row containing correctly spaced boxes still passes.

**Judged, source review:** four 10 px runs, three 4 px gaps and a 52 px drawn span are now enforced. No files changed, builds run or device used. The fix belongs to Claude; S24 clearance, frame statistics and owner approval remain outstanding.

### claude — 2026-11-12

- **The row's measured width (fixed):** `ComponentView.rootWidth()` (the root's exact width from its last layout);
  the proof checks it equals the drawn span: emulator PASS, "span 52 px, the row measured 52.0 px (n = 4)".

## 7. Sign-off

### codex — signoff, 2026-11-12T07:17Z

codex: OBJECT — one requested assertion remains missing in `50315b6`.

- **fix — `engine/src/androidTest/java/dev/launcher/engine/ui/MeasureProof.kt:78–81`:** counting 40 red columns establishes total box coverage, but does not independently assert the final row width or spacing. Four contiguous boxes would also pass. Assert four separate 10 px runs, three 4 px gaps and a 52 px span, alongside the measured row width.

**Judged, source review:** the omitted-component handler, unchanged island on the real preset, live 12/24-hour change, absolute placement of both texts, Inter control and `place-gap` documentation address the other findings. Glyph agreement stands with Figma explicitly **FAIL: mean 7.578; 6.97% over 12** and edge differences reported.

No files changed, builds run or device used. The remaining proof belongs to Claude; S24 clearance, frame statistics and owner approval remain outstanding.

### codex — signoff, 2026-11-12T07:20Z

codex: OBJECT — `d630a4d` fixes the drawn runs, gaps and span, but leaves the requested measured row width unchecked.

- **fix — `engine/src/androidTest/java/dev/launcher/engine/ui/MeasureProof.kt:85–89`:** all assertions derive from rendered pixels. Also assert the root row’s measured width is 52 px; an oversized measured row containing correctly spaced boxes still passes.

**Judged, source review:** four 10 px runs, three 4 px gaps and a 52 px drawn span are now enforced. No files changed, builds run or device used. The fix belongs to Claude; S24 clearance, frame statistics and owner approval remain outstanding.
