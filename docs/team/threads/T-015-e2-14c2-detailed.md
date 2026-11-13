# T-015: E2.14c-2, detailed notifications: a banner that grows out of the island as a blob

- **Step:** E2.14c · **Lead:** claude (solo by the owner's choice, D-116; Codex reviews on its return) · **Opened:**
  2026-11-13 · **Status:** DONE pending the gate and the owner's S24 look
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

### owner — 2026-11-13

"B, don't wait till it's fully grown. grow, and then start detaching when it gets \"heavy\"".

### claude — the detailed state, 2026-11-13

- **The island is a `blob`** of two shapes (`island.kdl` 0.5.0):
  - *The pill:* the island as it was, with all its states.
  - *The banner's shape:* rests at no size inside the pill, 12 dp down (an empty shape takes no part in the union; two
    shapes in one place swell it by a quarter of `smooth`, measured: 367 x 105 px for 346.5 x 84).
- **On a detailed arrival:**
  - the banner grows to 380 x 76 dp on `spring(0.38, 0.85)`: the island swells out of its underside;
  - meanwhile it sinks to 44 dp on `cubic-bezier(0.7, 0, 0.3, 1) 650ms`, slow at first (heavy), then letting go;
  - the blob (`smooth` 16 dp) keeps them joined by a neck until they are 8 dp apart;
  - the mark, sender, text and "now" come at 380 to 470 ms.
  - Going back plays it the other way, the content first.
  The pill keeps its own state (the time, music, charging) throughout.
- **Engine:** `blob align="top"` centres its shapes across it, and each keeps its own size. The arrival carries `text`,
  `app` and `mark`. Mode switch for tests: `TEST_STATUS --es do kdl --ez on true --es component island --es mode
  detailed|minimal` (settings come from packages later).
- **Measured:**
  - *The island proof's `detailed` stage (120 frames at 120 Hz):* joined for 45 frames, then apart; the banner 996 px
    (380 dp), centred on the camera; content ink 3570; after it goes, nothing under the pill; the pill 132 dp again.
    Every other stage still passes.
  - *The launcher on the emulator with `TEST_NOTIFY`:* the banner under the island, then back after 4 s.
  - *JVM:* `IslandKdlTest` and `ArrivalsTest`.
- **Assumptions (to review):**
  1. The default mode stays minimal.
  2. The banner is 12 dp under the pill, 380 x 76 dp, radius 26.
  3. The mark is the sender's first letter, not the app's icon (an app icon needs a picture provider of its own).
  4. A tap or a swipe on the banner does nothing yet (actions come with input dispatch, E3).
- **Not verified:** the S24 (the look, and the shader's cost per frame).

### owner — S24, 2026-11-13

"it should feel like mitosis from the status pill. that means the status pill should also react to the notification.
have the island stretch and squash when the notification is breaking apart from it. there should be a bit of tension
before breaking off and squash and stretch when it does. right now the notification just grows behind the island and
moves down. fix it. Also, the notifications should have transparency too, just like the panels. read the design
language for guidance. there should be a gradient of opacity between the status pill and the notification during the
mitosis transition."

### claude — mitosis, 2026-11-13

- **The design language (the owner's artifact, re-read):**
  - glass is a backdrop blur of 24 dp with saturation 1.2, and the `glass` tint over it (`rgba(18,18,18,0.76)` in dark);
  - `shadow-float` under it (0 12 32 at 55 % black, and a 1 px hairline at 6 % white);
  - text on it in `ink` and `ink-2` only;
  - opaque `surface` as the fallback.
- **`blob elastic` (`BlobElastic`):** the first shape is the parent (the pill), the second the bud (the banner).
  - *Joined:* while the two are joined across a gap they are one body. The pill stretches down by 36 % of the gap, its
    bottom edge pulled and its width narrowed (half of what keeps its area, so it reads as jelly). The banner is held
    back by 30 % of it as one piece. A third shape, the neck, bridges them and thins from half the pill's width to a
    thread as the neck that shows nears the union's reach. That is the tension.
  - *The break:* it comes past about 24 dp. Then the spring (3 Hz, damping 0.26) takes both back past their rest: the
    pill squashes short and wide, the banner drops and squashes. Both settle.
  - *Found and fixed:* a spring lagging behind the pull let the neck break before anything had stretched (measured on
    the emulator: under 1 px). While joined, the stretch now follows the pull at once, and the spring takes over at the
    break from that speed.
- **Colours and glass (`BlobPainter`):**
  - every shape is filled with its own box's fill, and the shader blends colours by the same weights that join the
    shapes: the solid pill and the glass banner meet in a gradient across the neck, and the neck itself is halfway;
  - a translucent shape gets `shadow-float` and the hairline in the shader;
  - the banner is `#C2121212` (the glass tint), its text `#F2F2F2` and `#C4C4C4` (`ink`, `ink-2`), "now" in `ink-2`, the
    mark a 12 % white fill (no glass on glass).
- **Measured on the emulator:**
  - the island proof's `detailed` stage at 120 Hz: joined for 52 frames, then apart; the pill back at 132 dp and the
    banner at 380 dp (998 px), centred; the content shows; nothing left under the pill after it goes;
  - frames 34 to 60 (`tools/shots/det/break.png`): the pill elongates and the neck thins, then the pill squashes after
    the break and settles;
  - JVM `BlobElasticTest`: stretch, a late break, squash, rest, colours kept, the neck.
- **Not yet:** the backdrop blur behind the banner (Samsung's view blur under it, E3's glass work). The glass is the
  tint without blur for now; the frost comes next.

### owner — 2026-11-13

"I changed my mind about the way the notification banner separates. the banner should not grow first, then separate. it
should work exactly like a fluid would. adding more to a droplet will grow it until tension breaks and separates into two
droplets. the status pill itself should grow until it becomes too heavy, break into two and the banner part grows a bit
more and then the notification appears with a slide and blur."

### claude — the fluid drop, 2026-11-13 (D-117)

- **The drop is the pill while joined (`BlobElastic.tint`):** joined, the second shape takes the pill's solid colour, so
  the union reads as the pill itself swelling. After the break it turns to the glass tint over 280 ms (and back when it
  joins again). This also keeps the glass shadow away until the drop has parted (it showed under the pill before).
- **The timing (`island.kdl` 0.7.0):**
  - *Size:* grows from the first frame on `cubic-bezier(0.35, 0.3, 0.45, 1) 950ms`.
  - *Sinking:* on `cubic-bezier(0.8, 0, 0.25, 1) 700ms`, barely moving at first.
  - *The sequence:* the pill swells wider and longer, gets heavy, sinks; the neck holds and thins, and it breaks at about
    0.45 s with the drop three quarters grown. Both squash, and the drop grows the rest of the way into the banner.
- **`slide` and `blur` presence effects (engine):**
  - `slide` comes in from the start side by 16 pt.
  - `blur` comes out of an 8 pt radius (`RenderEffect` on the node's RenderNode, Android 12 and later), its layer held
    at one size through the motion.
  - The mark, the sender, the text and "now" enter with `fade slide blur` ("now" without the slide) at 620 to 690 ms,
    and leave with `fade blur 120ms`.
- **Measured on the emulator:**
  - *The island proof's `detailed` stage:* joined for 26 frames (about 260 to 470 ms), then parted. The banner ends at
    998 px, centred; the content shows; nothing is left under the pill after it goes. Every other stage still passes.
  - *Frames:* `tools/shots/det2/fluid.png`, 50 ms apart.
  - *JVM:* `NodeMotionTest` (slide and blur arrive and end sharp on their place), `MotionGrammarTest`, and
    `BlobElasticTest` (joined, the drop takes the pill's colour; parted, its own).
- **Judged (to review):** the curves, the 280 ms colour turn, and the slide's side and distance.
- **Not yet:** the S24 look and frame cost, and the backdrop blur under the glass (E3).

### owner — S24, 2026-11-13

"there's no blur behind the notification banner (bg blur); there's a weird big shadow to it, which I did not ask you to
do; the notification banner could be a bit closer to the pill. it seems just a bit too far"

### claude — blur, no shadow, closer, 2026-11-13

- **No shadow:** the blob's shader draws no shadow any more. The glass keeps its 1 dp hairline at 6 % white.
- **Closer:** the banner rests 20 dp under the pill (it was 28; `translate-y` 52 dp). To keep the break, the pull is
  softer (the pill 32 % of the gap, the banner 22 %). It still breaks, at about 0.52 s. Measured on the emulator: joined
  for 33 frames, then parted.
- **The live blur behind the banner:**
  - *Engine:* `ComponentHost.onGlass` reports, at every frame they change, where the blobs show glass (each shape whose
    fill is translucent, and how much: none while the drop is still the pill's colour).
  - *Shade (`KdlGlass`):* a view that draws nothing, just under the KDL bar. It is kept on that shape: its size set in
    place, moved by translation. It asks One UI's view blur (`SamsungBlur`) for the design language's glass, a 24 dp
    blur with 1.2 x saturation and the shape's radius, its strength following the glass amount.
  - *Elsewhere:* where a phone has no view blur, nothing changes (the other phones' live blur is E3's glass work).
- **Found on the S24 and fixed:** the first build reported the glass without the island's own place in the view (the
  root is placed by Compose): the blur region read x -324..673. With the root's place (`HostNode.viewX/Y`), it reads:
- **Measured on the S24:**
  - `dumpsys SurfaceFlinger`'s blur region is x 41..1039, y 147..347 (998 x 200 px, centred on the camera, 20 dp under
    the pill), blur 283, corners 68, on `LauncherStatusBar`;
  - a screenshot over the wallpaper shows the grain smoothed under the glass, edges aligned, no shadow.
- **Not measured yet:** the frame cost on the S24.

### owner — S24, 2026-11-13

"the close animation is broken. the notification banner becomes squared and the text goes off. also, speed up the
animation to better match the design language. the blur doesn't work either. we'll scrap the blur idea and go back to
opaque background for the whole theme. revise the theme and also do a little bit of a touch up to make it more consistent
with the design language." (Then: the theme to revise is the Figma prototype, not the design-language artifact.)

### claude — the close, speed, opaque, 2026-11-13 (D-118)

- **The close, found from frames at exact steps on the emulator (`detailed-back-*`):**
  - *Square corners:* the banner's radius sprang to 0 at once while its size took 0.95 s. The radius is now a steady
    16 dp (`radius-lg`); the shape clamps it to half its height while small, so the drop stays round.
  - *The text going off:* closed, the banner is laid out at no size on the camera, so its left edge moved to the
    middle in the close's first frame and the content with it (190 dp right). The content now sits in a box held back by
    half the banner while closed. The banner also clips it as it shrinks.
- **Faster:**
  - *The drop:* size 480 ms, sinking 380 ms.
  - *The elastic:* 4.5 Hz, damping 0.32; the drop's colour turn 160 ms.
  - *The content:* in on `dur-move` (220 ms) and `ease-out`, `dur-stagger` (24 ms) apart, from 300 ms; out on
    `dur-state` (120 ms) and `ease-in`.
  - *Measured on the emulator:* the pill swells by 0.15 s, breaks at about 0.28 s (joined 18 frames), and the content is
    in by about 0.45 s. The close merges into the pill by about 0.3 s.
- **Opaque:**
  - the banner is `surface` (#121212); its mark is a `surface-2` tile at `radius-sm`;
  - the text is `body-strong` 15 in `ink`, `small` 13 in `ink-2`, and the time `caption` 12 in `muted` (Plex, no longer
    mono);
  - the live blur (`KdlGlass`, `ComponentHost.onGlass`) and the blob's glass edge are taken out;
  - `DEFAULT_LAYOUT_PLAN.md`: opaque surfaces, no glass, no shadows, for the whole default layout.
- **Tests:**
  - *JVM:* `IslandKdlTest` (the content's hold-back), and `BlobElasticTest` (the squash counted from the break: the
    quicker spring squashes while the bud still moves).
  - *Emulator:* the island proof PASSES, every stage.
- **Not done:** the Figma prototype's revision (the Figma connector needs the owner to sign in again).

