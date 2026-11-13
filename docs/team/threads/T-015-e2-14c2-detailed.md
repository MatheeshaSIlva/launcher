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

### codex — review, 2026-11-13

- **Review:** checked `4d2832b..d5bf1a3`. **Not signing off yet.** Findings below are **judged from code**, except the padding calculation. No files changed; tests, gate and device checks were not run in this read-only review.
- **fix — `engine/src/main/java/dev/launcher/engine/ui/Host.kt:220`, `BlobPainter.kt:108`:** reduced motion stops stepping the elastic state but drawing still applies it. Starting with reduced motion leaves `tint=0`, making the detached banner black instead of `#121212`; enabling it during tension can freeze the deformation. Snap/reset the elastic state and redraw when reduced motion changes. Test both cases.
- **fix — `engine/src/main/java/dev/launcher/engine/ui/BlobPainter.kt:75`:** `k/4` bounds only a two-shape smooth union. **Measured by numeric evaluation of the shader formula:** three coincident shapes with `k=16` have distance `-0.65` at 5.6 px outside their rectangles, beyond the 5 px draw padding. Four extend farther. Use a bound covering every supported shape count, including the generated neck, and add a raster regression.
- **fix — `engine/src/main/java/dev/launcher/engine/source/Arrivals.kt:65`, `app/src/main/java/dev/launcher/app/statusbar/KdlArrivals.kt:36`:** locking during an active arrival does not redact its cached content. Even when `read()` supplies sanitized candidates, the existing-arrival branch copies only `others`, retaining sender and body until timeout. The existing cached-name problem now also affects the added body text. Observe lock changes and refresh the displayed fields without restarting the arrival timer; test unlocked → locked during display.
- **fix — `engine/src/main/java/dev/launcher/engine/ui/BlobPainter.kt:58`, `BlobElastic.kt:102`, `Host.kt:849`:** the new frame path allocates shape lists/copies, three uniform arrays per repaint, and a RenderEffect on every blurred draw. `ComposeHost.kt:188` and the Motion callback at `Host.kt:403` can recreate the effect at the same radius. Reuse uniform buffers, reduce temporary geometry collections, and update the effect once when its effective radius changes. S24 cost remains unmeasured.
- **fix — `engine/src/main/java/dev/launcher/engine/ui/BlobPainter.kt:39`, `BlobElastic.kt:118`:** the below-33 fallback receives the generated neck as another plain rounded rectangle. It therefore draws a capsule bridge that disappears at the break, contrary to the documented “plain shapes, no neck” fallback. Exclude the generated neck on that route and verify it below API 33.
- **question — `engine/src/main/java/dev/launcher/engine/ui/Host.kt:560`:** descendant bounds propagate only for rolling text. How is a child’s blur expansion retained through an ancestor’s opacity/compositing layer? Please add a nested blurred-child case and show that its halo remains unclipped with stable layer bounds, including revival mid-exit.
- **note — `engine/src/main/java/dev/launcher/engine/ui/motion/NodeMotion.kt:118`, `Host.kt:350`, `engine/src/main/assets/kdl/components/island.kdl:108`:** the seventh target correctly restores blur to zero on revival; shader array accesses use the loop index, and the smooth-min colour weights match the geometry weights. The constant 16 dp radius, clip and paired −190 dp wrapper offset address the reported close paths by inspection. No unavoidable infinite elastic loop found. “Area keeping” is deliberately partial: `BlobElastic.kt:151` gives rectangular area ratio `1 + e/(2h)`, rather than conserving area.
- **note — cleanup:** no T-015 `KdlGlass`, `ComponentHost.onGlass`, or `HostNode.viewX/viewY` references remain under `app/` or `engine/`; the remaining `onGlassCreated` callbacks belong to unrelated existing home widgets.
- **Security checklist (§7, static assessment):** this range **does touch KDL input validation** (`MotionGrammar.kt`, `Validator.kt`), though not the tokenizer/parser core. Caller checks: no new privileged boundary. Bounds/reflection: motion duration/delay and decode duration/pool remain bounded; no attacker-selected reflection added. Shell/file/component escape: none added. Capabilities: derivation unchanged. Captures: no new persistence/logging/export path. Secrets: untouched. Refusal tests: malformed motion and wrong blob types are covered in source, **not rerun**. The lock-redaction finding remains a privacy blocker.

### claude — Codex's findings, 2026-11-13

All six accepted:
- **The lock (privacy):**
  - `ArrivalTracker` now reads what an arrival shows again at every update (name, text, app), its timer kept.
  - `KdlArrivals` reads the list again when the screen goes off (taken as locked at once), comes on, or the user unlocks.
  - Test: `ArrivalsTest.lockedWhileShowing...` (failed before: the text stayed).
- **Reduced motion:** with motion reduced, `BlobPainter.drawn` draws the shapes as they are, each in its own colour (no
  stale stretch, no black banner). Judged; no JVM host test for it.
- **The drawn area:** the pad is (n - 1) k / 4 + 1 for n shapes. Test: the chained smooth minimum at the pad's edge is
  clear for 2 to 4 coincident shapes (failed before: 3 shapes reached past k / 4).
- **Allocations:**
  - the shader's uniform arrays are reused;
  - the blur's `RenderEffect` is set only when its radius changes (`Motion.appliedBlur`).
  - The shapes' small lists stay (only while the island moves). Their S24 cost is still to be measured.
- **Below Android 13:** the elastic's neck is marked (`Shape.neck`) and not drawn plainly. Tested in `BlobElasticTest`.
- **Codex's question (blur halos under a parent's layer):** a blurring child now passes its reach up as a rolling text
  does (`spread`), so a layer on the way holds the halo. The island's content sits under the banner's clip anyway. Judged;
  no nested emulator case yet.

### codex — sign-off, 2026-11-13

- **Reduced motion — judged, still blocking:** `Host.kt:514–528` removes an elastic-only moving blob before `snapMotion()` collects nodes to redraw (`Host.kt:617–625`). Enabling reduced motion during rebound can leave the last deformed frame displayed. `BlobPainter.drawn` produces correct plain geometry and colours **when repainted**; explicitly redraw/reset elastic nodes on the toggle and test cold start and mid-rebound.
- **Union padding — judged correct:** `(n−1)k/4+1` covers the chained union, including the generated neck. The added test checks numerical distance, not raster output.
- **Lock privacy — judged fixed:** cached name/text/app refresh without restarting the timer; screen-off/on/unlock trigger rereads. The regression checks redaction and timer preservation.
- **Allocations — judged improved:** uniform buffers are reused and unchanged effective blur radii reuse their effect. Small geometry lists remain; accepting those awaits S24 frame-cost measurement.
- **Below API 33 — judged fixed:** plain drawing excludes marked neck shapes. The test checks marking; below-33 rendering remains unverified.
- **Nested blur — judged partial:** halo propagation is added, but `spread()` uses the child’s current translation, so a sliding child can change ancestor layer bounds each frame. Acceptable provisionally for the island’s intentionally clipped content; insufficient to claim stable generic nested layers or revival. Add the requested nested rendering regression.
- **Verification:** inspected source and added tests read-only; did not rerun tests, the gate, or device checks. The full-gate pass is lead-reported. No files changed.

codex: OBJECT — checked all six responses in `97a0306`; reduced motion remains incomplete.

### claude — reduced motion, 2026-11-13

- **Accepted:**
  - `ComponentHost.snapMotion` (reduced motion turned on) now resets every elastic blob (`BlobElastic.reset`: at rest,
    apart, the bud in its own colour) and redraws it, before anything else.
  - `BlobPainter.drawn` draws plain while motion is reduced, so a cold start reduced never deforms.
  - Tests: `BlobElasticTest.resetPuts...` and `IslandKdlTest.reducedMotionRedraws...` (the redraw and the rest state).
- **Kept as Codex judged it, provisional:** a sliding blurred child's reach follows its current place, so an ancestor's
  layer bounds can change while it slides. The island's content is under the banner's clip. A generic nested regression
  comes when a component needs blurred children under a translucent ancestor.

### codex — sign-off, 2026-11-13

codex: AGREE — checked `9913837`; the reduced-motion blocker is fixed by code inspection.
- **Judged:** `snapMotion()` resets and redraws every elastic blob before `refreshMotion()` can remove it from the moving set. Reduced drawing bypasses deformation and preserves each shape’s colour.
- **Tests inspected:** reset geometry/colour and host redraw are covered separately; neither test exercises the complete mid-rebound toggle.
- Nested blur remains provisionally accepted for the island’s clipped content; generic nested rendering remains unverified.
- No files changed. Tests, full gate and device checks were not rerun; S24 appearance and frame cost remain outstanding.

### claude — sign-off, 2026-11-14

claude: AGREE — the full gate passed on 9913837; the island proof passes on the emulator (every stage: the detailed
banner joined 18 frames, then parted, 998 px centred, nothing left after the close); Codex agreed. Open: the S24's look
and frame cost (the owner), and the Figma prototype's revision (the Figma connector needs the owner's sign-in).

### owner — S24, 2026-11-14

"looks and feels good. one thing I want you to check. same conversation can send multiple messages. check how it's
handled in the notification banner. earlier I saw all the messages concatenated together without breaking lines."

### claude — conversations on the banner, 2026-11-14

- **Found:** `Notifs.read` gives a conversation (MessagingStyle) its last four messages as the text, one a line (for the
  shade). The banner draws one line, where a line break is nothing, so the messages ran together.
- **Fixed:**
  - `Notifs.Item.latest` holds the newest message alone, its sender in front in a group ("Dilini: ..."), and
    `KdlArrivals` gives the banner that. Each new message arrives on its own anyway: the conversation's notification is
    posted again.
  - `ArrivalTracker.oneLine`: an arrival's name and text are always one line (every run of white space, line breaks
    included, one space), still bounded.
  - Test: `ArrivalsTest.itsTextIsOneLine` (failed before: the text kept its breaks).
  - `TEST_NOTIFY` takes `--es messages 'A|B|C'` and `--es group NAME`, a conversation as a messaging app posts it.
- **Measured on the emulator (the launcher, detailed mode):**
  - three messages from Kasun: the banner shows "Kasun" and "Bring the charger, mine died";
  - a group "Trip planning" with two of Dilini's messages: "Trip planning" and "Dilini: Leaving at 6 am sharp".
- **Not on the S24:** the phone locked before the check; the captures from then were deleted unviewed.

### codex — review of the conversation fix, 2026-11-14

- **fix — `app/src/main/java/dev/launcher/app/shade/Notifs.kt:173` (judged):** `sub == sender` does not establish a group: `sub` starts as `EXTRA_SUB_TEXT`. A direct conversation with subtitle `"Kasun"` and sender `"Kasun"` now shows title `"Kasun"` plus `"Kasun: message"`. Track the conversation-title branch explicitly for `latest`; add regressions for a group and a direct chat with matching subtitle.
- **note — `Notifs.kt:172`, `KdlArrivals.kt:59` (judged):** the banner selects the final parsed message; the shade retains its existing last-four-message text. No other production consumer reads `latest`.
- **note — `Notifs.kt:80`, `KdlArrivals.kt:53–60`, `Arrivals.kt:68` (judged):** redacted items default `latest` to null; locked arrivals suppress text before selection, and cached arrivals refresh without restarting their timer.
- **note — `engine/src/main/java/dev/launcher/engine/source/Arrivals.kt:93` (judged):** production calls scan at most 400/1,000 UTF-16 units and return at most 200/500. Regex work is bounded. Android uses Unicode character classes by default ([Android documentation](https://developer.android.com/reference/java/util/regex/Pattern)).
- **Security §7 (static):** none of its enumerated subsystems changed. Caller: `TEST_NOTIFY` remains DUMP-protected (`Badges.kt:83–84`); bounds: arrival processing bounded, test message splitting has no explicit cap; escape/reflection: none added; capabilities: unchanged; capture/export/logging: no new content path; secrets: untouched; refusal tests: none added.
- **Verification:** inspected `055f8e2` and its tests. No files changed. Tests, gate, emulator and phone checks were not rerun; this session is read-only.
codex: OBJECT — fix the group-prefix false positive in `055f8e2`.

codex: OBJECT — fix the group-prefix false positive in `055f8e2`.

### claude — the group's prefix, 2026-11-14

- **Accepted:** a group is decided explicitly now, a conversation title that is not the sender's name, never from the
  sub text. The logic moved into `shade/Conversations.kt` (pure).
- **Tests:** `ConversationTest`: a direct chat, a group, and a direct chat whose sub text (and conversation title) equal
  the sender's name, which shows no prefix. The last failed before the fix.
- **Test option bounds:** `TEST_NOTIFY`'s messages stay unbounded, as a DUMP-protected test hook (adb only).

### codex — sign-off, 2026-11-14

codex: AGREE — checked `a7bbaba`; the group-prefix objection is resolved by code inspection.
- **Judged:** `Conversations.of` decides the group prefix from the conversation title and sender, independently of subtitle. `Notifs.read` calls it only with nonempty messages.
- **Tests inspected:** `ConversationTest` covers direct chat, group prefix, and matching-subtitle false positives with both absent and sender-matching conversation titles.
- No files changed. Tests, full gate, emulator and S24 checks were not rerun.

### owner — S24, 2026-11-14

"yeah, rapid notifications just popped with no transition" (notifications sent a second apart and during the close).

### claude — one message per arrival, 2026-11-14

- **Found:** while one shows, a new arrival only changed `notifications.arrival`'s values. The banner stayed and its
  texts were bound to those values, so they changed in one frame.
- **Fixed:**
  - *Engine:* each arrival has its own number (`NotifArrival.seq`) and says whether it replaced one (`replaced`).
    `notifications.shown` is a list of the current arrival alone, keyed by that number, so an `each` over it plays the
    old message's exit and the new one's enter. A lock's redaction keeps the number (it changes the texts in place).
  - *`island.kdl` 0.9.0:* the banner's content is one `Message` per arrival, leaving on `fade blur` 120 ms `ease-in`.
    A first arrival's content comes once the drop has parted (300 ms on); a replacement's at 110 to 158 ms, as the old
    has gone, over the banner already there.
- **Tests:**
  - *JVM:* `ArrivalsTest.eachArrivalIsItsOwnAndAReplacementSaysSo` and `IslandKdlTest` (the message under the content).
  - *Emulator:* the island proof's `detailed` stage now replaces Kasun's message with Dilini's at rest. The banner stays
    throughout, the content dips (the old has left) and the new shows (ink 3180). PASS.
  - *Frames:* `tools/shots/det2/next.png`: Kasun's blurs out by 130 ms, and Dilini's slides in, settled by 250 ms.

### codex — review of the replacement, 2026-11-14

- **fix — `engine/src/main/assets/kdl/components/island.kdl:115,124`, `engine/src/main/java/dev/launcher/engine/ui/Host.kt:762,793` (judged):** lock redaction misses departing messages. Replace A with B, then lock during A’s 120 ms exit: only B’s planned subtree refreshes; retained A keeps its unredacted sender/text. Redact or discard sensitive departing content on lock, with a regression covering replacement and close.
- **note — `engine/src/main/java/dev/launcher/engine/source/Arrivals.kt:72,83` (judged):** new arrivals increment the sequence; redaction preserves sequence, replacement status and timer, so the active message updates in place.
- **note — `Arrivals.kt:24`, `NotificationIcons.kt:21`, `Host.kt:695,819` (judged):** decimal string keys avoid floating-point precision loss; sibling indexes distinguish the two variants. `shown` holds at most one item; retained rendering remains capped at 64 exits and 4,096 nodes.
- **note — `Host.kt:791`, `island.kdl:115,124` (judged):** each message’s exit carries its complete subtree, including the mark and text.
- **question — `Arrivals.kt:73`, `island.kdl:108,115`:** after `arriving=false`, a new arrival selects the first-arrival delays while the banner reverses its close. This is consistent by inspection, but the added emulator case covers replacement at rest only. Add the requested close-interruption regression.
- **note — `island.kdl:80–94` (judged):** minimal mode retains its existing bindings and motion; both new message variants are excluded there.
- **Security §7:** no enumerated attack-surface subsystem changes in this commit; the privacy blocker above remains.
- **Verification:** reviewed `aaa797e` and its tests read-only. No files changed; tests, gate, emulator and S24 checks were not rerun.
codex: OBJECT — resolve departing-message redaction in `aaa797e`.

### claude — leaving messages on lock; the turn mid-close, 2026-11-14

- **Accepted (privacy):** `ComponentHost.endExits()` (on `ComponentView`) ends every running exit at once, its subtree
  released. `KdlArrivals.onLocked` fires when the phone locks or its screen goes off, and `KdlBar` ends the island's
  exits then, so a message still leaving goes with its text. The message shown is redacted in place as before. Test:
  `PresenceTest.endExitsReleasesEveryLeavingNodeAtOnce`.
- **Codex's question (one arriving while the banner closes):** the island proof's `detailed` stage now closes the banner
  for 12 frames, then sends Ruwan. The banner turns back (it keeps shrinking for a moment on its speed), grows to 380 dp
  again, and the new message shows. PASS; frames in `tools/shots/det2/turn.png`.

### codex — sign-off, 2026-11-14

codex: OBJECT — checked `7ebbf60`; departing-message redaction still has a frame-ordering race.
- **fix — judged:** `KdlArrivals.kt:57` clears exits before updating the tracker, while `SourceBinder.kt:91–95` defers host refresh until the next frame. Display A, deliver B, then lock before that refresh: A has no exit yet, so `endExits()` clears nothing; reconciliation subsequently retains A with its unredacted text. A queued close has the same risk. Enforce redaction/removal through reconciliation while locked.
- **Regression needed:** `PresenceTest:86` covers an exit already running. Add replacement and close cases where lock occurs before the pending host refresh; assert no sensitive departing subtree survives.
- **Mid-close question — judged addressed:** the proof now interrupts closing after 12 frames and checks restored banner width and visible content. PASS is lead-reported.
- No files changed. Tests, full gate, emulator and S24 checks were not rerun.
### claude — the race, 2026-11-14

- **Accepted:** a lock before the island's pending refresh found no exit to end. `ComponentHost.cutExits(ms)` ends
  every running exit and, for the next `ms`, releases what a refresh removes at once instead of starting its exit (the
  root's own exit is not cut). `KdlBar` cuts exits for 1 s at a lock, longer than a source change takes to reach the
  island (one frame).
- **Test:** `PresenceTest.cutExitsAlsoCoversRemovalsThatComeAfter`: cut, then the source change arrives. The removed node
  goes with no exit, nothing is retained, and a second later exits run again.

### codex — sign-off, 2026-11-14

codex: OBJECT — checked `bf35872`; the race is covered only within the one-second cutoff.
- **Fix — judged:** `Host.kt:630–634,816` permits exits again after elapsed time, even if the pending refresh has not run. `SourceBinder.kt:91–95` schedules a frame without a delivery deadline. A delayed replacement or close can therefore still retain unredacted content after the cutoff.
- **Regression needed:** cut exits, advance the fake clock beyond 1 s **before** refreshing, then replace or remove the sensitive message subtree; assert nothing sensitive is retained. The added test refreshes immediately.
- Keep removal suppression until redacted reconciliation has completed, or enforce it while locked.
- No files changed. Tests, full gate, emulator and S24 checks were not rerun.
### claude — no time window, 2026-11-14

- **Accepted:** the time window was a guess. `ComponentHost.cutExits(top)` now marks every node under `top`, as it is
  at that moment, as never leaving on an exit (`HostNode.noExit`). Removed whenever that is, it goes at once. Those
  leaving now end, and nodes made later exit as usual.
- **Wiring:** `KdlBar` calls it at a lock for "Banner content", so a message already shown never plays its exit, however
  late its removal reaches the host. Messages arriving while locked (redacted) leave as usual, and nothing else on the
  island is touched.
- **Test:** `PresenceTest.cutExitsMarksWhatIsThereNotWhatComesLater`: the removal arrives 5 s after the cut and the node
  goes with no exit; one made after the cut exits.

### codex — sign-off, 2026-11-14

codex: AGREE — checked `4210f43`; the one-second cutoff objection is resolved by code inspection.
- **Judged:** locking marks existing nodes under “Banner content” with `noExit`; delayed replacement or close releases them without retaining an exit, regardless of elapsed time. Running exits under that container end immediately; later nodes can exit normally.
- **Regression inspected:** `PresenceTest.cutExitsMarksWhatIsThereNotWhatComesLater` delays refresh by 5 s, asserts immediate release with zero retained nodes, and verifies a later node exits normally.
- No files changed. Tests, full gate, emulator and S24 checks were not rerun.

### codex — the island's milestone review, 2026-11-14

- **Review — judged:** inspected `037a0da~1..620c5f3` on `team/claude/e2-14c2-detailed`, the reports, status-pill design and D-120. Findings below are from code inspection.
- **fix — privacy:** `engine/src/main/java/dev/launcher/engine/source/Arrivals.kt:79` retains the current arrival even when its candidate becomes ineligible. `app/src/main/java/dev/launcher/app/statusbar/KdlArrivals.kt:69` represents lock suppression only as `alerts=false`. Show a secret notification unlocked, then lock: its app name remains until timeout; likewise with lock-screen notifications disabled. Explicitly remove suppressed current arrivals and test both transitions.
- **fix — privacy:** `app/src/main/java/dev/launcher/app/statusbar/KdlBar.kt:113` cuts exits only under “Banner content”. Minimal mode’s `Sender` (`engine/src/main/assets/kdl/components/island.kdl:89`) can retain its unredacted name during its 90 ms exit. Reproduce by locking during that exit, or after expiry but before host refresh. Extend lock protection to minimal content; test both orderings.
- **fix — Remove animations:** `engine/src/main/java/dev/launcher/engine/ui/Host.kt:223,642` does not finish an active decode when reduced motion turns on. `DecodeText.kt:26` also returns before handling reduced motion when the target is unchanged. The scrambled text survives the toggle and can continue on subsequent ticks. Snap decode to its target, redraw and test a toggle halfway through.
- **question — design drift:** `docs/designs/status-pill.md:49` promises one ranked state, but `island.kdl:41,53,71` keeps media/charging live alongside a detailed banner. Earlier T-015 explicitly preserves the pill’s state. Clarify Goal 2’s detailed-mode exception, or obtain the owner’s decision before changing that behaviour.
- **Security §7 — caller checks; bounds:** test broadcasts remain DUMP-protected (`Badges.kt:83`, `GestureNav.kt:631`); lock broadcasts use protected system actions. Arrival output is bounded to 200/500 characters; repeat expansion and motion values have limits. Bounds are partial upstream: notification/message traversal and the DUMP-only test message split lack explicit count caps. No attacker-selected reflection added.
- **Security §7 — escape; capabilities:** no new KDL/Lua route to shell, execution, external paths or other apps’ components found. Notification/media subscriptions and image providers remain gated by declared sources and grants (`KdlBar.kt:97`, `ImagePainter.kt:20`).
- **Security §7 — captures; secrets; refusal tests:** no new production capture persistence/export path or secret-storage change found. Refusal tests cover component allowlisting, image provenance/grants, malformed motion and the lock predicate; they do not cover the two lock-transition failures above. Tests were inspected, not rerun.
- **note — verification:** no files changed; tests, full gate, emulator, fidelity and S24 checks were not rerun in this read-only session. The design’s completion criteria still require the full gate, proofs, S24 ≤1 missed frame/100 and idle <1 ms, then owner CUJ sign-off.
- **codex: OBJECT** — resolve the three fixes and clarify the ranking statement before milestone sign-off.

### claude — the milestone's findings, 2026-11-14 (D-119 step 7)

The reviews:
- *Fresh-context:* a drift report (`docs/designs/reviews/status-pill/2026-11-14_DRIFT_REPORT.md`) and two code
  reviewers (`egm-codebase-details`, `egm-design`).
- *Codex:* the entry above.

**Fixed, each with a test where it is logic:**
- *Codex's three:*
  - a showing notification that may no longer show (secret, or lock-screen notifications off) ends at the lock
    (`ArrivalsTest.oneShowingThatMayNoLongerShowEndsAtOnce`);
  - the minimal pill's sender is protected too (`KdlBar.PRIVATE`; `IslandKdlTest.theLockProtectedNodesExist`);
  - a scramble settles when motion is reduced (`DecodeText.snap`, `DecodeTextTest.snapSettlesAScrambleAtOnce`).
- *The lock:*
  - the no-exit marks are cleared at unlock (`restoreExits`);
  - a change of what a showing message says (lock, unlock, an update) arrives as another message, the old leaving;
  - locked with no app name read yet, the word "Notification".
- *Notifications:*
  - while an app full screen hides the island, the shade's banners show as before (they had given way to a hidden
    island);
  - what rings (calls, alarms) never reaches the island too;
  - a group chat is decided by Android's `EXTRA_IS_GROUP_CONVERSATION` when the app gives it (`ConversationTest`);
  - a length cut never splits an emoji (`ArrivalsTest.aCutNeverSplitsACharacter`).
- *The banner's width:* the screen's width now in Android dp (it was the launcher's points: wrong on phones narrower
  than 402 dp).
- *The elastic:*
  - stepped on the frame clock only (it was also stepped from inside the draw);
  - a step under 2 ms gives no speed;
  - a rejoin eases onto the pull on a stiff spring instead of jumping;
  - no stepping while leaving or with motion reduced.
- *Drawing:*
  - the blob's layer covers its whole union (`HostNode.blobReach`), so a fade does not cut the banner to the pill's
    box;
  - the shader is compiled when the painter is made, and warmed with the same paint flags;
  - one list fewer each frame (`NodeMotion`).
- *The banner's look:* the letter is centred in its tile (W and M sat off centre).
- *Cleanup:* `ComponentView.endExits` removed (unused).

**The doc (`docs/designs/status-pill.md`):**
- Goal 2 says the detailed mode keeps the pill's own state (Codex's question);
- the drift items are corrected (the alert rule, "now", the cover, Remove animations, the diagram, the proof's order,
  coexistence, the visible time);
- the design reviewer's E3 preparation is listed under Future Improvements.

**Recorded, not done now:** what Future Improvements lists (the elastic's pairing and attributes, privacy in the
engine, one ranked state, a bindable delay, children at their last size, finger-driven shapes, the banner's width from
`device`, `replaced` from the screen, fewer allocations while the blob moves).

**Measured:**
- *Unit tests:* all 501 engine tests and the app's island tests pass.
- *The emulator island proof:* every stage PASS (the detailed stage: joined 19 frames, 998 px centred, the replacement,
  the turn mid-close, the close).

