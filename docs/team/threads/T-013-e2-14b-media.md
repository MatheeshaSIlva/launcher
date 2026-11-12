# T-013: E2.14b, the media source and the island's media state

- **Step:** E2.14b · **Lead:** claude · **Opened:** 2026-11-12 · **Status:** IN PROGRESS
- **Read first:** `docs/DEFAULT_LAYOUT_PLAN.md` (§1 Island row, §2 Sources, §3 E2.14b); `docs/ENGINE_SPEC.md` (Sources:
  `media`, capability `media.read`; Actions: `media.*`); D-098 (draft 4), D-101 (rolling text), D-103 (shapes), D-105 to
  D-107 (the island); `engine/src/main/java/dev/launcher/engine/source/` (`Sources.kt`, `NotificationIcons.kt`:
  `PreparedPictures`); `app/src/main/java/dev/launcher/app/shade/Media.kt` (the launcher's reading of sessions);
  `engine/src/main/assets/kdl/components/island.kdl`; Figma draft 4, `Island` in "02 In an app" (node 41:276).

## 1. Proposal (lead)

**Goal.** While something plays, the island widens into draft 4's media state, with the cover, the title and five
terracotta bars, and goes back to the time and battery the moment it stops. It reads a new engine source, `media`,
which is plain data. The ranking against other live states (notification, charging, call) is 14c and out of scope here.

**What draft 4 says (Figma, node 41:276, 2026-11-12).**
- **The pill:** 236 × 32, radius 16, padding 14 start and 12 end, space-between.
- **Left:** a 20 × 20 cover with radius 4, then a 6 dp gap, then the title in IBM Plex Sans Regular 13 on an 18 line,
  #F2F2F2.
- **Middle:** the 28 dp camera slot.
- **Right:** five bars, 2 dp wide, radius 1, 2 dp apart, `signal` #D9865F, heights 6 / 12 / 8 / 14 / 10, centred.

**The owner's answers (2026-11-12, asked before the discussion).**
- *Bars:* "Loop while playing". A made-up rhythm with no permission (real levels would need RECORD_AUDIO); they settle
  flat when playback pauses.
- *Paused:* "Go back at once". A pause returns the island to the time and battery. The launcher's 10-minute linger
  (`Media.IDLE_MS`) does not apply to the island.

**Approach (slices, each about 200 lines).**
1. *`media` source* (engine): `MediaState` (plain data: `playing` bool, `title`, `artist`, `app` package, `art` record
   `{id, rev}` or null). Capability `media.read`. It is fed through an interface (`MediaFeed`, as `NotifIconFeed`); the
   app implements it over `shade/Media` (the active-session choice it already makes). "Playing" is
   `PlaybackState.STATE_PLAYING` (buffering counts as playing, so a track change does not flicker). The art is prepared
   off the drawing thread before it is published, reusing `PreparedPictures` (one entry, rev = a hash of the metadata),
   so a painter only looks it up. Fixtures: `Sources.fixture` with media states.
2. *`image` primitive* (engine): draws a prepared picture by `{id, rev}` from a host-provided table, with `size`,
   `radius`, and `fit="cover"`. A picture not yet ready draws nothing; when one arrives or changes, it crossfades on
   `motion.state`. The spec's `image src=…` for package assets and the network comes later; this slice only takes
   prepared pictures by id. A capability check rejects an id from a source the component did not declare.
3. *Bars:* a looping height. Option A: a generic `loop` motion on `box` heights (`loop-height=(dp)4..(dp)14`,
   `loop-period`, `loop-phase`, running only while `{media.playing}`; the clock stops when nothing loops, like decode
   text's). Option B: a dedicated `bars` primitive. I propose A: it is reusable and keeps the primitive count down.
   Settling flat runs on `motion.state`.
4. *`island.kdl`:*
   - Two states in one root, with `width` bound to `{media.playing ? 236dp : 132dp}` on `motion.move` (13a's size
     spring).
   - The idle row and the media row are present by `{!media.playing}` and `{media.playing}`, on presence motion
     (`motion.enter` / `motion.exit`).
   - The title is clipped to its slot (no ellipsis in draft 4: a fade at the end edge, judged; or ellipsis, see the
     question below).
   - The island declares `source media`; the app grants `media.read` to the built-in island.

**Verification.**
- **Unit tests:** the source's mapping (playing, paused, buffering, stopped, no session, a session ending, metadata
  without art, art changing); `PreparedPictures` with one entry; the validator for `image` and `loop-*`.
- **Emulator proofs (instrumented):**
  - The media state at rest against draft 4's numbers: pill 236 × 32, cover 20 dp at 14 dp, title place within 1 px,
    bars' places and heights at phase 0.
  - Frames at exact steps through idle → media → idle, with a reversal mid-way.
  - The loop's clock stops when paused.
  - State fixtures: a long title, no art, an empty title, an RTL title.
- **Device:** the emulator plays a real session. On the S24, the owner plays music.
- **Fidelity:** `tools/fidelity/diff.py` against Figma's export of 41:276, recorded as in T-012, with glyphs judged
  against Android's rendering.

**Risks and open questions.**
- A long title: draft 4 shows "Halftone", which fits. A longer one either ellipsises or fades at the slot's end. I lean
  to a fade (calmer, and the design language avoids "…"); it changes nothing the owner approved, so I will show both on
  the phone.
- Several sessions (Spotify playing, YouTube paused): the island follows `Media`'s choice (playing first, else none).
- The bars' rhythm is ours to judge; I will show it to the owner before it is called done.
- Art arrives after the title: the cover crossfades in and the title does not move (the cover's slot is reserved while
  playing).

## 2. Discussion

### codex — discuss, 2026-11-12T11:19Z

**Change before agreement.** I agree with a plain-data `media` feed, a prepared-picture `image`, and generic looping motion rather than a dedicated `bars` primitive. I would not build the proposal as written yet.

1. **Define the media projection and selection precisely.** The spec’s Sources table promises active sessions with position and state; the proposed selected-session subset is reasonable for 14b, but document it as partial delivery, with explicit empty/denied values. `Media.pick()` ranks only `STATE_PLAYING` (`app/.../shade/Media.kt:77–84`), while `read()` includes buffering (`:97`) and `isPlaying()` includes more transitional states (`:143`). A buffering session behind a paused one can therefore disappear. Choose one predicate for selection and publication, and test it. Playback callbacks currently reread the selected controller without reranking the others; test a second session starting while the selected one pauses. Also resolve subscription removal (`addListener` has no counterpart, `:38`) and clearing stale values on stop/access loss (`:64–71`). The island must follow playback, never `active`’s ten-minute linger.

2. **Reuse `PreparedPictures` as preparation machinery, with a separate media table.** Its worker/owner-thread publication and stale-completion checks fit (`engine/.../source/NotificationIcons.kt:32–92`). It does not provide image crossfading or a one-entry limit by itself. Use session-scoped identity and an explicit art revision that changes for art-only updates; “hash of metadata” is insufficiently specified. Publish title/playing immediately, publish only a ready art record, and reserve its slot. Define whether the previous track’s art remains while replacement loads—the table currently keeps it. On replacement, `picture(oldId, oldRev)` becomes unavailable, so a crossfade must hold a bounded outgoing picture independently. Cover A→B→C interruption, null art, release, and reduced motion. Bound retained bytes and preparation dimensions, including outgoing pictures and pending jobs.

3. **Prefer A, but specify its contract before agreeing.** A bounded oscillator on a box’s drawn height is reusable; five ordinary boxes already express this design. `loop-height=(dp)4..(dp)14` is not existing KDL syntax: use separately typed endpoints or a defined literal grammar. Specify waveform, period units/bounds, phase units, deterministic initial heights, and the boolean run attribute. Reserve the maximum layout height and centre the animated drawing inside it; do not remeasure Compose every frame. Define how looping hands height back to `motion.state`, without competing with the size track. Crucially, removed media rows retain their subtree and existing motion during exit (D-100; `Host.kt:395–398, 677–684`): a retained bar must stop looping even if its frozen binding still says playing. Reduced motion, hidden components, removal and disposal must also stop recurring work.

4. **Write the switch using 13a’s actual attributes.** The root can retain its current placement, black fill and shape:
   ```kdl
   width="{media.playing ? 236dp : 132dp}"
   transition="size @motion.move, place @motion.move"
   ```
   Its two overlaid child rows use:
   ```kdl
   visible="{!media.playing}" enter="fade @motion.enter" exit="fade @motion.exit"
   visible="{media.playing}"  enter="fade @motion.enter" exit="fade @motion.exit"
   ```
   These are attribute fragments, not additional nodes. A box already overlays children; two rows in a parent row would consume layout space. Size motion lays children out at the target and reveals them through the moving bounds (`ComposeHost.kt`, `surface()`); include `place` so the camera-centred root’s changing origin moves continuously. Keep `place-gap=(dp)4` and `#000000` (D-106/107). Reserve explicit left/title, camera and right slots: sequential row measurement can otherwise let a long title consume the camera/bars’ space (`layout/Layout.kt:133` onward). `TextPainter.kt:34–70` neither ellipsises nor fades overflow. Either treatment is additional work and an owner choice under protocol §5.3; draft 4’s short title does not approve a fade.

5. **Authorize pictures at lookup, not through an id’s spelling.** `ComponentView.kt:58` currently gates its picture provider specifically on declared **and granted** notifications access. Extend this with source-owned providers; granting `media.read` must not expose notification pictures, or vice versa. A literal/forged id must never select a source, file, URI or loader. Validator dependency checks alone cannot establish where a dynamically computed id came from. Keep URI art refused under D-095, bound untrusted metadata, and complete both agents’ §7 checklist for the capability/validator changes.

6. **Add proofs that expose those failures.** Beyond the proposed fixtures: denied and undeclared capability tests using real capability-bearing sources (`Sources.fixture()` makes every source capability `none`, `Sources.kt:45`); crossed media/notification ids; delayed obsolete art completion after session replacement/disposal; art-only revision changes; pause/resume within 100 ms; retained-exit clock shutdown; reduced motion toggled mid-loop; and long/RTL titles proving the camera and bars remain unobscured. Check untinted landscape/portrait art cropping and rounded edges on GPU and software paths. Exact-time frames should cover width, presence and art together, including reversal. Preserve D-105’s separate geometry/glyph assessment and record the Figma diff with D-106/107’s intentional deviations. Measure active-loop cost on the S24 before calling its performance proven.

**Judged from source inspection; no builds, tests or device measurements run this round. Agreement remains pending the contracts above.**

### claude — 2026-11-12

All six points accepted; you are right that the proposal left the contracts open. Settled below, and one owner answer
since the proposal.

**Owner, 2026-11-12, on a long title:** "fade+scroll, with a toggle for the user to stop the scroll". The title is clipped
to its slot with a soft fade at the end edge, scrolls slowly to show the rest while it plays, and a component prop
switches the scroll off (`title-scroll type=bool default=#true`; the fade stays either way).

1. **Projection and selection.** One predicate, `Media.isPlaying`'s set (PLAYING, BUFFERING, CONNECTING, SKIPPING_TO_*),
   used for both choosing and publishing. Every playback callback ranks all sessions again (a second one starting while
   the selected one pauses takes over). `removeListener` is added. Values are cleared on a stop, on a session's end and
   on listener access loss. `media` is documented as a partial delivery of the spec's row: the chosen session only, with
   `playing`, `title`, `artist`, `app` and `art`. Position and the session list come with E3's player. Denied or
   undeclared gives `MediaState.NONE` (playing false, every member null). The island reads `playing` only, never `active`'s
   ten-minute linger.
2. **Art.**
   - **Machinery:** a separate media table on `PreparedPictures`' machinery, with one wanted entry.
   - **Identity:** id = the session (a counter per session token, never the app's name or the title); rev = a counter that
     moves when the art changes (the bitmap's identity or generation id and size, so art-only updates count).
   - **Publishing:** title and `playing` publish at once. Art is published only once ready, and a track change withdraws
     the old art at once (no old cover beside a new title). The cover's slot is reserved while playing.
   - **Bounds:** art is prepared at no more than 2× the slot (about 128 px square on the S24), and the table holds the
     current picture plus at most one outgoing one, held by the painter for its crossfade. Pending jobs are bounded as the
     notification table's are.
   - **Interruption:** A→B→C, null art, release and reduced motion (no fade) are all tested.
3. **Loop contract (option A).** On `box`:
   - **Attributes:** `loop-from` and `loop-to` (lengths), `loop-period` (ms, 200..10000), `loop-phase` (0..1 of a period),
     `loop-run` (bool, bindable). The waveform is a sine between the two heights, so heights are deterministic from
     (time − start, phase).
   - **Layout:** reserves the larger of `height`, `loop-from` and `loop-to`; the drawn bar is centred in it, so nothing
     measures again per frame.
   - **Stopping:** when `loop-run` goes false, the drawn height eases from where it is to `height` on `motion.state`. Size
     tracks are separate (the loop changes the drawing only).
   - **The clock:** one host frame callback runs while any running loop is attached, visible, not exiting, and reduced
     motion is off. A retained exiting subtree freezes its loops whatever its frozen binding says, and hiding, removal and
     disposal stop it.
4. **The switch.**
   - **KDL:** your fragments, as written (`width` with `transition="size @motion.move, place @motion.move"`, and two
     overlaid rows in the root box with `visible`/`enter`/`exit`). `place-gap=(dp)4` and `#000000` are kept.
   - **Slots:** fixed so the camera stays centred whatever the title — left group 90 dp from the start padding to the
     camera slot (cover 20 + gap 6 + title 64), camera 28, right group the same width with the bars at its end.
   - **The title:** needs two engine additions, a `clip` with an end fade (`overflow="fade"`, fade 12 dp) and a
     `marquee`-style scroll (`scroll="{@title-scroll && media.playing}"`; speed 24 dp/s, pause 2 s at each end, back on
     `motion.move`; RTL scrolls the other way).
5. **Pictures by provenance.** A new opaque value, `Value.Picture(source, id, rev)`. Only a source can make one, an
   expression can pass it but never build it, and there is no literal. `image picture="{media.art}"` looks it up in that
   source's provider only, and only if the component declared and was granted that source. A string never selects
   anything. `media.read` exposes no notification pictures and `notifications.read` no media ones (tested both ways).
   URI art stays refused (D-095), and title and artist are bounded to 200 characters. Both §7 checklists are filled in.
6. **Proofs.** All of yours join the list.
   - **Unit:** denied and undeclared with real capability-bearing sources; crossed ids; an obsolete art completion after
     a replacement and after disposal; art-only revisions; pause and resume within 100 ms; a retained exit's clock
     stopping; reduced motion switched mid-loop.
   - **Emulator, instrumented:** long and RTL titles leave the camera slot and the bars clear; art crop in landscape and
     portrait, round edges on GPU and software; exact-time frames of width + presence + art with a reversal.
   - **Records:** the Figma diff is recorded with D-106/107 as intended deviations (D-105's split: geometry and glyphs
     apart).
   - **S24:** the loop's cost is measured before performance is claimed.

**Proposed split.**
- **Codex:** slice 3, the loop (attributes, validator, host clock, tests and its proof), and slice 4b, the title's clip,
  fade and scroll.
- **Claude:** slice 1, the source and the app's feed, plus slice 2, `Value.Picture`, the media table and `image`; then
  4a, `island.kdl`'s states and slots; and the integration, the device proofs and the S24.

### codex — discuss, 2026-11-12T11:23Z

**The revised direction is sound; three details still need closing before agreement.**

I agree with the selected-session projection as explicitly partial delivery of `ENGINE_SPEC.md`’s Sources row; separate, bounded media-art preparation; opaque `Value.Picture` with authorization at lookup; the drawing-only sine loop; the overlaid state switch; and the expanded proofs. The owner’s answer resolves fade versus ellipsis: fade plus scrolling, with a user toggle. D-106/107’s black fill and 4 dp gap remain intact.

1. **Observe unselected sessions too.** Reranking on every playback callback is insufficient while only the selected controller registers a callback (`app/src/main/java/dev/launcher/app/shade/Media.kt:51,77–84`). If A is selected and paused, then B starts, B currently cannot notify us. Specify callbacks for the tracked active sessions, with a bounded count and cleanup on removal, access loss and stop. Add the exact test: **A remains paused; B starts without an active-session-list change; B becomes selected.** Use the actual `Media.isPlaying` predicate, including FAST_FORWARDING and REWINDING (`:143`), rather than the abbreviated state list.

2. **Correct the slot arithmetic.** The proposed widths total **234 dp**, not 236: `14 + 90 + 28 + 90 + 12`. To preserve the 90 dp left group, centred 28 dp camera slot and 12 dp end padding, the right group must be **92 dp**. That places the camera at x=104..132, centred at 118. Record those coordinates and test them, including during the width transition; the existing design’s asymmetric padding cannot produce equal group widths.

3. **Turn the split into file ownership.** I accept responsibility for the loop and title overflow/scrolling. However, both assignments need `model/Primitives.kt`, `model/Validator.kt` and likely `ui/ComponentView.kt`; topic ownership alone does not satisfy protocol §§2/4. Before work, name one owner per shared file and an integration order or explicit handoff for the other slice’s changes. Keep production edits split into approximately 200-line slices.

For my title work, I propose these implementation rules: scroll only when text overflows; share the host’s motion scheduling; stop recurring work when disabled, hidden, exiting, disposed or under reduced motion. Retarget from the shown offset when the toggle, text, width or direction changes. Include toggle-off mid-scroll, short-title clock shutdown and mixed-direction text in the proofs. This follows the polish-pass interruption rule and D-100’s retained-exit lifecycle.

**Judged from repository inspection and the complete lead-worktree thread. No files changed; no builds, tests or device measurements run. Agreement pending the three items above.**

### claude — 2026-11-12, round 2

All three accepted.

1. **Every session watched.** `KdlMedia` (slice 1, written while you reviewed) registers a callback on each active session
   (at most 8, in the system's order), drops removed ones, and clears everything on dispose. A list it cannot read
   (access lost) publishes `NONE`. Every callback ranks them all again with `MediaPick`, whose set is exactly
   `Media.isPlaying`'s, FAST_FORWARDING and REWINDING included. Your test, "A stays paused, B starts with no change to the
   list, B is chosen", becomes an instrumented test with two real `MediaSession`s in the test app (the JVM test covers
   the rule alone).
2. **Slots corrected.** Left 90 dp at x 14..104, camera 28 dp at x 104..132 (centre 118 = the pill's 236 / 2), right
   92 dp at x 132..224, then 12 dp of padding. The left group holds the cover 20 + gap 6 + title 64; the bars sit at the
   right group's end. The proofs check these at rest and at every step of the width transition (the camera slot's centre
   stays on the pill's centre).
3. **Files.**
   - **Shared files:** Codex owns `model/Primitives.kt` and `model/Validator.kt` for this task and adds `image`'s
     definition to them as specified below. Claude owns `ui/ComponentView.kt`, `expr/Compiled.kt` (`Value.Picture`) and
     `ui/ComposeHost.kt`. Each asks the other for an edit in the other's files through the thread (one-line requests).
   - **Codex's files:** `ui/Host.kt` (the loop's and the scroll's clock, the retained-exit freeze), `ui/TextPainter.kt`,
     the box painter, and new files for the loop and the scroll.
   - **Claude's files:** the media table and `image` painter (new files), `island.kdl`, and the app's files.
   - **Order:** Codex's first commit is the schema (loop attributes, title overflow and scroll, `image`) and lands before
     I wire `image`.
   - **`image`'s schema:** `picture` (bindable; must evaluate to a `Value.Picture`; a literal refused,
     `picture-not-literal`), `size` (length), `radius` (length), `fit` (`cover` only for now, default `cover`); no
     children.

### codex — discuss, 2026-11-12T11:33Z

**Round 3: the three blockers are resolved.** I accept §3 and the file ownership and integration order in §4.

- **Selection:** `KdlMedia.kt:53–60` watches up to eight sessions; callbacks rerank them (`:30–33,64–69`). `MediaSource.kt:52–60` uses the same predicate for selection and publication, including fast-forwarding and rewinding. The two-session instrumented proof remains required.
- **Geometry:** `14 | 90 | 28 | 92 | 12` totals 236 dp and centres the camera slot at 118 dp. Intermediate-frame proofs must establish that centring throughout the transition.
- **Ownership:** I accept schema first, then loop and title work in my assigned files; changes needed in Claude’s files go through explicit requests, per protocol §4.

No further approach changes requested. Implementation sign-off still requires the agreed refusal tests, lifecycle and interruption proofs, both security checklists, fidelity record and S24 measurements.

**Judged from source inspection**, including slice 1 (`ac641f3`); no files changed, builds, tests or device measurements run.

codex: AGREE approach — contracts and ownership are settled; proceed schema first with the agreed proofs required before sign-off.

## 3. Agreed approach

Proposal §1 as amended by claude's two replies in §2 (selection over every session with `Media.isPlaying`'s predicate;
a separate bounded media art table with provenance-carrying `Value.Picture`; the drawing-only sine loop; the overlaid
state switch with fixed slots 14 | 90 | 28 | 92 | 12; the title's end fade and slow scroll with a user toggle (owner);
the expanded proofs), and Codex's rules for the title: scroll only when it overflows, share the host's motion
scheduling, stop when disabled, hidden, exiting, disposed or under reduced motion, retarget from the shown offset.

- `codex: AGREE approach — contracts and ownership are settled; proceed schema first with the agreed proofs required before sign-off.` (round 3)

## 4. Split

| Sub-task | Owner | Files owned | Branch | Verified by |
| --- | --- | --- | --- | --- |
| 1. `media` source and the app's feed | claude | `source/MediaSource.kt`, `app/.../statusbar/KdlMedia.kt`, `KdlBar.kt` | `team/claude/e2-14b-media` | JVM tests; instrumented two-session test |
| 2. `Value.Picture`, media art table, `image` painter and host | claude | `expr/Compiled.kt`, `ui/ComponentView.kt`, `ui/ComposeHost.kt`, new image/table files | `team/claude/e2-14b-media` | JVM tests (provenance, crossed ids, stale completions); emulator crop and corners |
| 3. Schema: loop, title overflow and scroll, `image` | codex | `model/Primitives.kt`, `model/Validator.kt` | `team/codex/e2-14b-media` | validator tests |
| 4. The loop | codex | `ui/Host.kt`, box painter, new loop files | `team/codex/e2-14b-media` | JVM clock tests; frames at exact steps |
| 5. The title's fade and scroll | codex | `ui/TextPainter.kt`, new scroll files | `team/codex/e2-14b-media` | JVM tests; frames, toggle mid-scroll, RTL |
| 6. `island.kdl` media state; proofs; S24 | claude | `island.kdl`, `IslandProof.kt`, new proofs | `team/claude/e2-14b-media` | emulator proofs, fidelity record, owner on the S24 |


## 5. Reports

### codex — work, 2026-11-12T12:54Z

- **Commits:** blocked: Git cannot create `D:/Launcher/.git/worktrees/launcher-codex/index.lock` under current permissions. Nothing pushed.
- **Handoff:** four verified patches in `.gradle/media-01-schema.patch` through `.gradle/media-04-title.patch`; apply in order.
- **Changed:** image/loop/title schema and refusals; sine loops with reserved layout height and exit freezing; end fade, scrolling, RTL and continuous retargeting.
- **Verified — measured:** full gate passed all six shell scripts, including 74 capture scenarios/746 checks. Final loop correction then passed the fast gate: **446 engine + 442 app tests**, including 15 new tests. Gradle daemon stopped.
- **Required lead edit — `ComposeHost.kt`:** call `setDrawingAttached(true/false)` on draw-node attach/detach; transfer attachment when `DrawElement.update` changes node identity.
- **Required lead edit — `ComponentView.kt`:** initially call `host.setVisible(false)`; forward effective show/window visibility to `setVisible`; call `snapMotion()` when motion becomes reduced and `refreshMotion()` when restored.
- **Security §7 — judged:** caller checks N/A (no new IPC); numeric bounds checked; no new unsafe execution/file/app paths; source declarations checked, runtime picture authorization awaits Claude; no captured data or secret changes; refusal paths tested.
- **Not verified:** integrated drawing, emulator frames, fidelity, S24 performance or owner approval. No device used.
- **Surprises:** preserved common `radius="full"` behavior for images. Added a regression proven failing before and passing after the rest-height handover fix.

### claude — integration, 2026-11-12

Your share is merged onto `team/claude/e2-14b-media` (`6607896`, committed by me from your worktree) with your two
requests in my files (`ComposeHost.kt` attach/detach and node changes; `ComponentView.kt` visibility = shown and attached,
reduced motion snapping and refreshing). Mine since: the session watcher moved into the engine (`SessionMedia`, so a test
can drive real sessions) and `media-sessions` passes on the emulator (B chosen with no list change, cover 128 px, an
art-only change moves the revision and retires the old one, a track change withdraws the cover, pause and resume, A takes
over); `image`'s painter with pictures by source and grant (`ImageAccess`, JVM-tested both ways); `island.kdl`'s media
state; and a fix of mine from T-012: the 4 dp gap is 10.5 px on the S24, so the gap now rounds to a whole pixel (the
island proof caught it: time ink 1.21 px off).

The island proof's new `media` stage (frames at 120 Hz on the real preset) found a defect: during the grow the leaving
idle row is laid out again from the pill's left edge (its "64" lands in the camera slot), and nothing is clipped to the
pill (the time shows outside it). Measured: centre within 1 px at every frame, width 132 to 236 dp monotonic, reversal
at frame 6 clean, the clock stops when paused; the camera slot is hit in grow frames 1-3.

**Request (your files, `Primitives.kt` and `Host.kt`):** `box align="centre"`: children centred on the box both ways,
the vertical on their centre lines as `centre-line` does (`Layout.stack(frame(), c, 0.5f, 0.5f, kids, centreLine =
true)`), and `childAnchor()` 0.5 to 0.5 for it. With it the island's root becomes `align="centre" clip=#true`, the idle
row `width=(dp)132` and the media row `width=(dp)236`: both stay centred on the camera while the pill's bounds move, and
the pill reveals them. A validator test and a layout test, please.

### codex — work, 2026-11-12T13:48Z

- **Commits:** none; changes left uncommitted as requested. Nothing pushed.
- **Changed:** `box align="centre"` centres children horizontally and vertically on their centre lines; `childAnchor()` returns 0.5/0.5. Four assigned files changed.
- **Verified — measured:** both new regression tests failed before the fix; all **453 engine unit tests passed** afterward, zero failures/skips. `git diff --check` passed. Gradle daemon stopped.
- **Not verified:** full gate, integrated animation/clipping, emulator fidelity or S24; this round used the specifically requested engine unit check. No device used.
- **Surprises:** none.

### claude — 2026-11-12, after `align="centre"`

Applied (`island.kdl`: root `align="centre" clip=#true`, idle row 132 dp, media row 236 dp). The island proof's media stage
still fails the same way; frames saved by the proof (`island/grow-1|3|6|12.png`, crops 260 dp wide centred on the camera)
show:
1. **The leaving idle row keeps its old place relative to the box's origin**, and the origin is now the target layout's
   (236 dp, so 52 dp further left): its "21:40" sits at the target's left padding and its "64" lands on the camera slot
   (grow frames 1-12). With `align="centre"` its place in the new layout is centred, so a leaving child should be placed
   by its parent's alignment in the parent's current layout (or kept where it was on screen), not at its old offset.
2. **The leaving row draws outside the pill**: "21:" shows on the background left of the pill's moving edge, so the
   retained subtree is not under the box's `clip` (the clip itself follows the drawn shape: `children()` in
   `ComposeHost.kt` uses `shape()`).

**Request:** diagnose and fix both, with a host test each that fails before. For this fix you may edit `ComposeHost.kt`
too (ownership handed over for this change; I touch neither file meanwhile). Then I rerun the emulator proof.

### codex — work, 2026-11-12T14:45Z

- **Commits:** none; left uncommitted as requested. Nothing pushed.
- **Changed:** `Host.kt`, `ComposeHost.kt`, `HostTest.kt`: retained box/stack children follow current parent alignment and padding while keeping their size, fade and pixel fractions.
- **Verified — measured:** placement regression failed before the fix; **456 engine + 442 app tests passed**, zero failures/skips. Full gate passed all six shell scripts, including **74 capture scenarios / 746 checks**. Diff check clean; Gradle stopped.
- **Surprises — judged:** supplied crops already clip the displaced clock at the pill edge. No separate clipping defect reproduced; clip renderer unchanged. Added a retained-subtree ancestry/clip-geometry test, but no failing-before clipping regression.
- **Not verified:** updated emulator frames, fidelity or S24. No device used.
- **Next:** lead reruns the media proof through grow, shrink and reversal.

### claude — report, 2026-11-12

- **Commits:** `ac641f3` (source, feed), `79a7213` (`Value.Picture`), `44c18e4` (`image`, `ImageAccess`, the cover table),
  `2186e90` (`SessionMedia` in the engine, `media-sessions` proof), `d9d417e` (your lifecycle requests), `f6811c7`
  (`island.kdl` media state; the gap on a whole pixel), `a891134` (the island proof's `media` stage), `c6bbb01` (centred,
  clipped), `3455e5e` (leaving state on `motion.state`); yours applied as `6607896`, `8cd167d`, `418b88d`.
- **What changed:** `media` (playing, title, artist, app, art) over every active session (at most 8) with one predicate;
  covers prepared at most 128 px, published only when ready, an art-only change moving the revision (compared by pixels:
  every metadata read hands a new copy of the bitmap, so identity changed at every read and the cover never settled);
  `image` draws a picture only from its own source's provider and only when declared and granted; the island widens to
  236 dp while something plays.
- **Verified (measured):**
  - *Emulator:* `media-sessions` PASS (B chosen with no list change; cover 128 x 128; revision 1 to 2 on an art-only
    change with the old one retired; a track change withdraws the cover; pause and resume; A takes over when B ends).
    `component-island` PASS with the media stage at 120 Hz on the real preset: width 132 to 236 dp monotonic over 40
    frames, the pill's centre within 1 px of the camera's at every frame, nothing drawn in the 28 dp camera slot,
    reversal at frame 6 peaks at frame 13 and comes back to 346 px, the clock stops once paused, cover 2632 px, title
    1080 px, bars 494 px at rest; the idle places unchanged from T-012 (time 0.50, 0.71, 0.50, -0.29 px).
    `component-measure` PASS.
  - *Launcher on the emulator:* with the island on and a held session playing, the media state as drawn
    (`tools/shots/media/emu-playing-top.png`).
  - *JVM:* `MediaSourceTest`, `ImageAccessTest`, `IslandKdlTest` (media slots 14 | 90 | 28 | 92 | 12 in dp at two
    densities).
- **Judged:** the in-between frames. On `motion.exit` (an ease-in, 220 ms) the time and the title overlapped for about
  100 ms; on `motion.state` (ease-out, 120 ms) the time is faint by frame 6 and gone by 12. Shown to the owner on the
  phone.
- **Not verified:** the S24 (the owner plays music), frame stats there, the Figma diff of the media state, the title's
  scroll on a long title as drawn, the cover's crossfade frames.
- **Decisions to log once `DECISIONS.md` is free** (another session holds uncommitted D-108..D-113 in it): D-114, the
  gap rounds to a whole pixel (4 dp is 10.5 px at 2.625: a half-lit top edge, the text half a pixel low; my lapse in
  T-012's change, caught here); D-115, a leaving state fades on `motion.state`.

### codex — work, 2026-11-12T16:27Z

codex: UNAVAILABLE (codex exec exited 1; see protocol §8)

## 6. Cross-check

### codex — review, 2026-11-12T14:57Z

**Cross-check: changes required before sign-off.** Findings below are **judged from source and commit-diff inspection** in `D:/Launcher` through `0a801fd`. No files changed; no Gradle or device commands run. **Measured:** `git diff ac641f3^ HEAD --check` passed.

Paths below are relative to `engine/src/`.

- **fix — `main/java/dev/launcher/engine/ui/ImagePainter.kt:120`:** Artwork arriving into an empty slot appears immediately at full opacity. `follow()` starts progress at zero, but `paint()` ignores progress when `outgoing == null`. Apply the fade to arrivals too. Add exact-time empty→picture and picture→empty assertions.

- **fix — `main/java/dev/launcher/engine/ui/ImagePainter.kt:78`:** Interrupted crossfades jump. During A→B, replacing B with C sets `outgoing = B`, discards A and resets progress; B suddenly becomes fully visible. Clearing the picture mid-fade has the same problem. Retarget from the currently displayed blend, with regression frames for replacement and withdrawal within 100 ms.

- **fix — `main/java/dev/launcher/engine/ui/ImagePainter.kt:90`:** The image runs its own Choreographer callback and wall clock, bypassing the host’s injected clock, visibility, retained-exit freeze and disposal. `release()` at line 102 cannot cancel the anonymous callback, so it continues writing snapshot state after release. Also, `key()` at line 105 starts animation and writes snapshot state during drawing. Move animation updates into the host’s lifecycle-controlled scheduler and make `key()` observational. Test hide, detach, exit, disposal and reduced motion mid-fade.

- **fix — `main/java/dev/launcher/engine/source/SessionMedia.kt:117`:** Full-resolution `Bitmap.sameAs()` runs on the window’s handler thread on every read, including playback callbacks and preparation completion. The 128 px output bound does not bound this comparison or the full-size hardware-bitmap copy at line 130. Bound accepted input dimensions/bytes before additional copying, and perform pixel comparison/preparation on the worker with stale-completion protection.

- **fix — `main/java/dev/launcher/engine/source/SessionMedia.kt:96`:** When selection becomes empty, `wantArt()` is skipped, leaving the previous picture installed and pending preparation wanted. An old `Value.Picture` can still resolve after its session disappears. Clear the table and comparison state when no session is selected; clear `artBitmap` and session bookkeeping during disposal too. Test session removal/access-loss clearing and a pending completion arriving afterward.

- **note — `main/java/dev/launcher/engine/ui/ImagePainter.kt:33`, `main/java/dev/launcher/engine/expr/Compiled.kt:54`:** The authorization design is sound: opaque picture values, source-specific providers, declaration plus grant filtering, and refusal of strings/records/stale revisions. `MediaSource.kt:56` also uses one complete playing predicate for selection and publication. The island’s `14 | 90 | 28 | 92 | 12` geometry is consistent.

- **fix — `androidTest/java/dev/launcher/engine/ui/IslandProof.kt:477,568`:** The proof uses one square cover and checks only its injected host clock. Consequently, its PASS cannot establish image-clock shutdown, artwork crossfade timing, landscape/portrait crop or rounded-edge correctness. Complete the agreed image proofs and long/RTL/mixed-direction title checks, including toggle-off mid-scroll. `MediaSessionProof.kt:112` waits for pause before resuming, so it also does not establish the agreed pause/resume-within-100-ms case. The reported Figma record and S24 measurements remain open.

**Security checklist — PROTOCOL §7**

- [x] **Caller checks:** no new exported IPC entry point; Android session access remains permission-controlled, and `KdlBar` gates feed construction by declaration and grant.
- [ ] **Bounded inputs:** eight watched sessions, 200-character text and bounded prepared outputs are present; full-size bitmap comparison/copying remains unresolved above. No attacker-selected reflection found.
- [x] **Execution boundaries:** no new KDL/package route to shell, exec, reflection, arbitrary paths or other-app component invocation; URI artwork is not opened.
- [x] **Capabilities:** `uses source media` drives subscription; `media.read` gates feed construction, binding and picture-provider access.
- [x] **Captured content:** no new other-app frame capture, persistence or transmission found; artwork stays in memory.
- [x] **Secrets:** no pairing-key/token storage or logging changes found.
- [x] **Refusal tests:** source denial/undeclared access and picture provenance/string/stale-revision refusals are covered in test source; session-removal clearing and bitmap-bound refusals still need tests.

**Sign-off withheld pending the fixes and outstanding agreed proofs.**

### codex — review of claude's share, 2026-11-12 (written to its output; copied here by the lead)

**Cross-check: changes required before sign-off.** Findings below are **judged from source and commit-diff inspection** in `D:/Launcher` through `0a801fd`. No files changed; no Gradle or device commands run. **Measured:** `git diff ac641f3^ HEAD --check` passed.

Paths below are relative to `engine/src/`.

- **fix — `main/java/dev/launcher/engine/ui/ImagePainter.kt:120`:** Artwork arriving into an empty slot appears immediately at full opacity. `follow()` starts progress at zero, but `paint()` ignores progress when `outgoing == null`. Apply the fade to arrivals too. Add exact-time empty→picture and picture→empty assertions.

- **fix — `main/java/dev/launcher/engine/ui/ImagePainter.kt:78`:** Interrupted crossfades jump. During A→B, replacing B with C sets `outgoing = B`, discards A and resets progress; B suddenly becomes fully visible. Clearing the picture mid-fade has the same problem. Retarget from the currently displayed blend, with regression frames for replacement and withdrawal within 100 ms.

- **fix — `main/java/dev/launcher/engine/ui/ImagePainter.kt:90`:** The image runs its own Choreographer callback and wall clock, bypassing the host’s injected clock, visibility, retained-exit freeze and disposal. `release()` at line 102 cannot cancel the anonymous callback, so it continues writing snapshot state after release. Also, `key()` at line 105 starts animation and writes snapshot state during drawing. Move animation updates into the host’s lifecycle-controlled scheduler and make `key()` observational. Test hide, detach, exit, disposal and reduced motion mid-fade.

- **fix — `main/java/dev/launcher/engine/source/SessionMedia.kt:117`:** Full-resolution `Bitmap.sameAs()` runs on the window’s handler thread on every read, including playback callbacks and preparation completion. The 128 px output bound does not bound this comparison or the full-size hardware-bitmap copy at line 130. Bound accepted input dimensions/bytes before additional copying, and perform pixel comparison/preparation on the worker with stale-completion protection.

- **fix — `main/java/dev/launcher/engine/source/SessionMedia.kt:96`:** When selection becomes empty, `wantArt()` is skipped, leaving the previous picture installed and pending preparation wanted. An old `Value.Picture` can still resolve after its session disappears. Clear the table and comparison state when no session is selected; clear `artBitmap` and session bookkeeping during disposal too. Test session removal/access-loss clearing and a pending completion arriving afterward.

- **note — `main/java/dev/launcher/engine/ui/ImagePainter.kt:33`, `main/java/dev/launcher/engine/expr/Compiled.kt:54`:** The authorization design is sound: opaque picture values, source-specific providers, declaration plus grant filtering, and refusal of strings/records/stale revisions. `MediaSource.kt:56` also uses one complete playing predicate for selection and publication. The island’s `14 | 90 | 28 | 92 | 12` geometry is consistent.

- **fix — `androidTest/java/dev/launcher/engine/ui/IslandProof.kt:477,568`:** The proof uses one square cover and checks only its injected host clock. Consequently, its PASS cannot establish image-clock shutdown, artwork crossfade timing, landscape/portrait crop or rounded-edge correctness. Complete the agreed image proofs and long/RTL/mixed-direction title checks, including toggle-off mid-scroll. `MediaSessionProof.kt:112` waits for pause before resuming, so it also does not establish the agreed pause/resume-within-100-ms case. The reported Figma record and S24 measurements remain open.

**Security checklist — PROTOCOL §7**

- [x] **Caller checks:** no new exported IPC entry point; Android session access remains permission-controlled, and `KdlBar` gates feed construction by declaration and grant.
- [ ] **Bounded inputs:** eight watched sessions, 200-character text and bounded prepared outputs are present; full-size bitmap comparison/copying remains unresolved above. No attacker-selected reflection found.
- [x] **Execution boundaries:** no new KDL/package route to shell, exec, reflection, arbitrary paths or other-app component invocation; URI artwork is not opened.
- [x] **Capabilities:** `uses source media` drives subscription; `media.read` gates feed construction, binding and picture-provider access.
- [x] **Captured content:** no new other-app frame capture, persistence or transmission found; artwork stays in memory.
- [x] **Secrets:** no pairing-key/token storage or logging changes found.
- [x] **Refusal tests:** source denial/undeclared access and picture provenance/string/stale-revision refusals are covered in test source; session-removal clearing and bitmap-bound refusals still need tests.

**Sign-off withheld pending the fixes and outstanding agreed proofs.**

### claude — reply to the review, 2026-11-12

All six taken.
- **Arrival, interrupted crossfades, the image's own clock (fix, `ImagePainter.kt`):** the painter has no motion any
  more. The cover sits in a fixed 20 dp slot (`box ... label="Cover slot"`), and the image comes and goes on the host's
  presence motion (`visible="{media.has-art}" enter/exit="fade @motion.state"`). That motion is interruptible, follows
  reduced motion, and stops when hidden, detached, exiting or disposed. `key()` only observes; no Choreographer, no
  snapshot writes. `media.has-art` was added (a picture used as a condition is refused by the validator, and `visible`
  hides only on an exact false).
- **Full-size comparison and copy (fix, `SessionMedia.kt`):** covers past 4096 px a side or 16 MB are refused before
  any copy or comparison. A change is told by a print (size and an 8 x 8 grid of pixels), bounded work on the thread
  whatever the size. A hardware bitmap, unreadable there, is compared by size alone (noted).
- **Clearing (fix):** no session chosen, or no cover: the table wants nothing (a pending completion counts for nothing)
  and the comparison forgets. Dispose clears the session ids and the print too.
- **Proofs (fix):** the island proof's new `media-details` stage, all measured on the emulator:
  - wide and tall covers are cropped centred (green 1369 px, red 0) with round corners (corner pixel black);
  - a cover arriving after the title fades in over 9 frames, its level only rising;
  - a long title is faded at its end (start ink 168, last 4 dp 0), stays in its slot, scrolls within 2.5 s, and stops
    when `title-scroll` goes off mid-scroll;
  - a right-to-left title is faded at its left end (start ink 237, end 0).
  `media-sessions` adds a 40 ms pause and resume, which ends playing. All PASS.
- Still open, as you say: the Figma record of the media state, and the S24 (frame stats with the bars looping, the
  owner's look).

### claude — the Figma record and the gate, 2026-11-12

- **Fidelity (recorded, not judged, as T-012's):** Figma's `Island` in "02 In an app" (41:276) exported at 2.625
  (`tools/fidelity/golden/island/media-x2.625.png`, 620 x 84) against the proof's crop at rest: **FAIL, mean 24.90, 12.6 %
  of pixels, all 288 cells over.** Expected deviations, all intended:
  - the pill is #000000, not #0A0A0A (D-106): every pixel of the fill;
  - the proof's cover is not Figma's picture;
  - the bars are caught mid-loop, not at Figma's still heights;
  - the round ends sit on the proof's blue, where Figma's are transparent.
  Places and sizes are proven apart, in `IslandKdlTest` (dp) and the proof (edges, slot, centre).
- **Gate:** full `scripts/check.sh` PASSED at `e144fd1`.

### claude — the owner on the S24, 2026-11-12

Owner, on the S24: "there animations are basically non existant, it was all just static transitions. the music bars
weren't even moving." Measured since:
- **The bars (a bug, fixed in `ComponentView.kt`):** the shade fades the KDL bar in with `view.animate().alpha(1f)` after
  its handover, never calling `show(true)`, so the host was told "not visible" and the loop never ran. Visibility now
  follows the view's real alpha before each draw. Emulator recording of the real launcher: the bars change on every
  recorded frame.
- **The widening:** it runs (emulator recording: 345, 393, 496, 617 px over ~100 ms), but on the design language's quick
  timings it reads as a cut (`motion.move` 220 ms; fades 120 ms).

Owner's choice (2026-11-12): **"Springy and staged"**. The pill opens on a soft spring (~450 ms, a slight overshoot);
then the cover grows in from small, the title comes in and the bars rise from flat, each a beat apart. Closing plays
the same in reverse, quicker. One install after the motion work.

**Request (your files: the motion grammar, `MotionSpec`/`Validator`, and `NodeMotion`/`Host.kt` as needed):** a
`delay` on presence clauses, e.g. `enter="fade scale spring(0.5, 0.8) delay(80ms)"` (or the syntax you judge better),
0..1000 ms, literal only.
- **Mid-delay changes:** a node that becomes absent again during its delay leaves at once without a frame of its
  arrival.
- **Retargeting:** a reversal mid-delay or mid-motion retargets from what is shown.
- **Reduced motion:** ignores the delay.
- **Tests and docs:** validator and host tests at exact times (before, at and after the delay; a reversal inside it);
  `docs/KDL_MOTION.md` updated.
I then write the island's choreography on it and prove it frame by frame on the emulator.

### claude — the staged choreography, 2026-11-12 (Codex unavailable)

`island.kdl` now opens on `spring(0.45, 0.75)` for size and place, and its pieces come in on their own springs from
fixed slots: the cover (`fade scale`), the title (`fade rise`), then the bars (`fade scale`). No delay is used (that is
Codex's sub-task, waiting).

The proof, lengthened to 120 frames for the springs, measures: peak 627 px at frame 41, settling at 619. Its frames
show two engine faults during a size motion:
1. **A fixed-width child is squeezed to its parent's width:** the 236 dp media row is measured inside the box's
   132 dp (`Layout.stack` caps children at `inner.maxWidth`). Closing, the cover and title jump about 52 dp to centre in
   the narrowing pill.
2. **Entering pieces are not clipped:** "Halftone" is drawn left of the pill in grow frames 2-5. The root's size is
   anchored at its left (`anchorX` 0) and stays centred only because its place and size springs match, while its
   children sit at the target layout.
Both are in the host's motion and layout core (Codex's area in this task). The owner is asked whether to wait for
Codex or let the lead fix them.

## 7. Sign-off

### codex — signoff, 2026-11-12T16:09Z

codex: UNAVAILABLE (codex exec exited 1; see protocol §8)

- `claude: AGREE — source, the media-sessions and island proofs (media and media-details stages) on the emulator, JVM tests, the full gate at e144fd1; the Figma record (FAIL, intended deviations); the S24 open.`
