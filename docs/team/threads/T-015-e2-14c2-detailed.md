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
