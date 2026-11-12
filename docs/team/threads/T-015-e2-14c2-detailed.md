# T-015: E2.14c-2, detailed notifications: a banner that grows out of the island as a blob

- **Step:** E2.14c · **Lead:** claude (solo by the owner's choice, D-116; Codex reviews on its return) · **Opened:**
  2026-11-13 · **Status:** IN PROGRESS (the engine primitive); the choreography waits for the owner's concept
- **Read first:** T-014 (the minimal mode, the arrival source); D-098 ("a smooth union (AGSL) only if a later design asks
  for it"; it now does); `docs/DEFAULT_LAYOUT_PLAN.md` §2 Shapes; `engine/src/main/java/dev/launcher/engine/ui/BoxPainter.kt`,
  `NodeShape.kt`, `ComposeHost.kt` (shapes, clips).

## 1. Proposal (lead)

**Goal.** The owner, 2026-11-13: notifications have two modes, the user's choice. In the detailed one, "the
notification banner should grow and separate as a blob from the status pill". This proposal covers the engine piece
any version of that needs, and three concepts of the motion for the owner to choose from.

**Concepts (Figma, page "Concepts: detailed notification", node 55:167), four keyframes each.** All three end with a
380 x 76 dp black card under the island (app mark, sender, one line, time).
- **A, Drop:** a drop swells under the island, stretches on a neck and falls away as the banner. The neck snaps and both
  ends round off.
- **B, Grow, then pinch:** the island itself grows into the banner (content inside), then its top pinches off and goes
  back to being the island.
- **C, Bead:** a small bead buds off the island's underside and grows sideways into the banner, the thread thinning out.

**The engine piece: `blob`.**
- **The primitive:** a container that draws the smooth union of its direct `box` children's shapes (each where its
  layout and motion have it this frame, at its shown radius) in one fill (`fill`), joined within `smooth` (a length,
  the neck's reach).
- **Children:** its boxes draw no fill of their own (they are the shapes); its other children draw on top as usual.
- **Rendering:**
  - Android 13 and later: an AGSL shader. The signed distance of each rounded rectangle and a polynomial smooth
    minimum, antialiased on the distance, over the blob's bounds; at most 4 shapes.
  - Below 13: the shapes drawn plainly, so no neck.
  - A shape moving redraws its blob.
- **Bounds and cost:** the shader runs over the blob's drawn area only. It is warmed off screen like the shapes'
  pipeline (D-103). It is measured on the S24 before it ships.

**Verification.**
- **JVM:** the schema; the shapes the blob reads from its children (places, motion, radius).
- **Emulator:** a test component with two boxes moving apart, frames at exact steps (the neck thins and breaks, no
  seam where the shapes meet, the antialiased edge matching a plain rounded rectangle's within a level).
- **Below 13:** the plain fallback.
- **S24:** the cost per frame.

**Owner's choice needed.** Which concept, A, B or C (or a mix). The island's detailed state and its choreography are
built after that.

## 2. Assumptions made while the owner is away (2026-11-13; to review)

1. **Concepts, not a choice:** three motion concepts drawn in Figma (A Drop, B Grow then pinch, C Bead). None is built
   into the island until the owner picks one (or a mix): the choreography and the banner's layout depend on it.
2. **The banner's content** in the concepts: an app mark (36 dp circle), the sender (IBM Plex Sans Medium 14), one
   line of text (Regular 13, #B5B5B5), "now" (JetBrains Mono 11, muted), on a 380 x 76 dp black card radius 26, 10 dp
   under the island. All of it is a first proposal.
3. **The engine piece is built anyway** (`blob`): every concept needs two shapes joined by a liquid neck, and D-098
   held this back only until a design asked for it. Up to 4 shapes, AGSL on Android 13+, plain shapes below 13 (no
   neck).
4. **The detailed mode's privacy:** like the minimal one, while locked the banner would show the app's name and no text
   (not built yet; to confirm with the concept).

## 3. Reports

### claude — the blob primitive, 2026-11-13

- **`blob` (`Primitives.kt`, `Host.kt` layout as a box, `BlobPainter.kt`):** the smooth union of its `box` children's
  shapes, each where its layout and motion have it, in one `fill`, within `smooth`. The boxes inside draw no fill of
  their own (`BoxPainter`). A shape moving redraws its blob (`ComposeHost` `DrawNode.redraw`).
- **The shader:** each rounded rectangle's signed distance, a polynomial smooth minimum, antialiased over a pixel. It
  is drawn over the shapes' bounds plus k / 4, and warmed off screen with the shapes' clip (`ShapeWarmUp`).
- **Measured on the emulator (`component-blob`):**
  - a 132 x 32 pill and a 200 x 60 card at gaps 0 to 40 px, `smooth` 24: the middle between them filled at gaps 0, 4
    and 8, empty at 16 and more (the expected reach, g < k / 2);
  - the neck 146, then 130 px wide, gone at 16;
  - away from the neck, the card's far corners identical to a plain rounded rectangle (0 levels);
  - moving on a 1200 ms place transition, the card's top drawn at 45, 51, 57, 64, 71, 74, 77 px (redrawn every
    step), ending where the still one is.
  `component-shape`, `component-island`, `media-sessions` and `component-measure` still PASS.
- **JVM:** `BlobSchemaTest`.
- **Not done:** the island's detailed state (it waits for the concept), the S24's cost per frame, and below Android 13
  on a device.
