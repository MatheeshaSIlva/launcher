# T-013: E2.14b, the media source and the island's media state

- **Step:** E2.14b · **Lead:** claude · **Opened:** 2026-11-12 · **Status:** DISCUSSING
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

## 3. Agreed approach

## 4. Split

| Sub-task | Owner | Files owned | Branch | Verified by |
| --- | --- | --- | --- | --- |

## 5. Reports

## 6. Cross-check

## 7. Sign-off
