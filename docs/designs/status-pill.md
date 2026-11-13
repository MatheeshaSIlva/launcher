# The island (the status pill)

*   **Author:** MatheeshaSIlva (the owner); written by the lead, Claude Code, from what is built
*   **Status:** In Review (built; this milestone's checks and the owner's sign-off are still to come)
*   **Date:** 2026-11-14
*   **Decisions:** [`docs/DECISIONS.md`](../DECISIONS.md) (D-067 onwards). **Task threads:**
    [`T-015`](../team/threads/T-015-e2-14c2-detailed.md) and its neighbours in [`docs/team/threads/`](../team/threads/).
    **Design:** draft 4 in Figma (https://www.figma.com/design/7E43AzlCpFNqhUkApcstZA).

--------------------------------------------------------------------------------

## Overview

Not a Launcher (the product; this repository) hides Android's status bar and draws its own. In the default layout that
bar is the **island**: one black shape on the camera at the top of the screen. Its first shape is the **pill**. At rest
the pill shows the time and the battery level. It changes shape to show what is live:
- a song playing;
- the charger just plugged in;
- a notification arriving, in one of two modes the user chooses:
  - *minimal:* the pill widens with the sender's name;
  - *detailed* (Android 13 and later): a second shape, the **banner**, parts from the pill like a drop of liquid.

The island is a KDL component, [`island.kdl`](../../engine/src/main/assets/kdl/components/island.kdl), drawn by the
engine (`docs/ENGINE_SPEC.md`: Compose owns the tree, the engine draws the pixels). Until this milestone is signed off
it replaces the launcher's hand-drawn `StatusBarView` only behind a developer switch (Rollout).

**This document comes after the code.** The island was built first (tasks T-012 to T-017). Under the owner's process
(D-119) the island is the first milestone, and its document records what the island does, why, how it is checked, and
what "done" means.

## The Problem

Android's status bar is a strip of icons a launcher cannot restyle. The launcher already hides it with Android's
status bar disable flags, through its shell helper (proven since the launcher's probes, `docs/PROBE_FINDINGS.md`). It
needs a bar of its own that:
- belongs to the layout, so a creator can replace it like any other component;
- shows what is live without clutter;
- follows the product's three rules (`CLAUDE.md`):
  - perfectly smooth;
  - fully consistent;
  - every change animated, interruptible and retargetable, with nothing appearing or vanishing in one frame.

The default layout's design (draft 4, D-098) answers with one floating shape on the camera.

## Goals

1.  **One shape on the camera.** Centred on the camera cutout (on the screen's centre without one), 4 dp from the top
    (D-107), pure black (#000000: on OLED a lit near-black shows the camera through it, D-105), at rest 132 x 32 dp.
2.  **One state at a time, by rank.** A notification arriving outranks charging, which outranks media, which outranks
    idle (T-014). When a higher state ends, the next lower live state returns.
3.  **Every change moves**, except with the phone's Remove animations on (CUJ 9). Each state change resizes the pill
    on a spring, and its pieces leave and arrive a short beat apart. A motion interrupted mid-way continues from where
    and how fast it was, with no jump.
4.  **The detailed notification parts like a fluid** (owner, 2026-11-13 and 14; D-117):
    - the pill swells as one body;
    - a neck holds under tension and breaks;
    - both halves squash and settle;
    - the banner grows the rest of the way, and its message slides in out of a blur.
5.  **Private when locked.** While the phone is locked, an arriving notification shows only its app's name. If its
    app marks it secret, or the phone's setting shows no notifications on the lock screen, nothing arrives (D-120). A
    message already on the banner when the phone locks never plays its exit with its text.
6.  **Within the frame budget** (D-067). On the S24 at 120 Hz:
    - while the island moves, at most 1 dropped frame per 100;
    - at idle, under 1 ms of drawing a frame.

## Non-Goals

*   Pulling the panel out of the island, and taps or swipes on the banner: E3, with input dispatch.
*   Calls, alarms and volume on the island: the shade's own banners keep these (Coexistence) until the island handles
    them, E3.
*   A settings screen for the mode: it is set over adb for now (Rollout); settings come from packages later (D-092).
*   Theme tokens: the island's colours and sizes are literal values in `island.kdl` until the default theme supplies
    tokens. Where this document names a colour (`ink`, `muted`, `ok`, ...), it means the design language's colour of
    that name, written into the component as its hex value.
*   The app's icon on the banner: the banner shows the sender's first letter (an icon needs a picture provider of its
    own).
*   Glass, backdrop blur and shadows: the default theme is opaque (D-118). The banner's content still blurs as it
    arrives; that is a motion effect, not glass.
*   Accessibility: spoken descriptions and focus come with input dispatch (E3). When they come, what they announce
    follows the lock-screen rule of Goal 5.
*   Layouts for right-to-left languages.

## Open Questions

*   **The S24's frame cost** of a detailed arrival (the blob's shader and the elastic step) is not measured yet. It is
    measured at this milestone (Verification Plan).
*   **The `blob` primitive in the spec.** `docs/ENGINE_SPEC.md`'s table of primitives does not list `blob` yet. Adding
    it is a spec change, the owner's to make.

## Critical User Journeys (CUJs)

Timings are the component's own values; "at once" means in the next frame.

### CUJ 1: At rest

*   **Given**: the phone unlocked, the status bar showing, nothing playing, the charger not just plugged in.
*   **When**: a minute passes, or the battery level changes.
*   **Then**: the pill shows the time (24 h or 12 h, as the phone is set) and the level; a changed digit rolls to its
    new value; the pill keeps its size.

### CUJ 2: Music plays

*   **Given**: CUJ 1.
*   **When**: a media session starts playing.
*   **Then**:
    *   the pill widens on a soft spring, and the time leaves at once;
    *   the cover, the title and five terracotta bars arrive in that order, 50 ms apart;
    *   a long title fades at its end and scrolls slowly;
    *   the pieces stay in fixed slots, so the camera stays clear.
    *   A pause returns to CUJ 1: the pieces leave together, then the time returns.

### CUJ 3: Plugged in

*   **Given**: CUJ 1 or 2.
*   **When**: the charger is connected.
*   **Then**: for 3 s the pill shows the time and the level with a bolt in `ok` green, then returns to what it showed.

### CUJ 4: A notification, minimal mode

*   **Given**: any state; minimal mode (or detailed on Android 12 and below); the phone unlocked.
*   **When**: a notification that alerts arrives. "Alerts" means its importance makes a sound or a vibration, Do Not
    Disturb lets it through, and it is not ongoing, a group summary or media.
*   **Then**: for 4 s the pill shows a terracotta dot and the sender's name, and "+N" in `muted` grey for the other
    alerting notifications still in the shade; then the state below returns.

### CUJ 5: A notification, detailed mode

*   **Given**: any state; detailed mode on Android 13 or later; the phone unlocked.
*   **When**: a notification that alerts arrives.
*   **Then**:
    *   the pill swells downward as one black body, then a drop sinks from it;
    *   the pill stretches toward the drop while a neck between them thins; the neck breaks and both squash and settle;
    *   the drop turns to `surface` (#121212) and grows into the banner, 20 dp under the pill;
    *   the letter, the sender, one line of the message and "now" slide in out of a blur, one after another.
    *   After 4 s the message fades out; the banner shrinks, its corners staying round and its content staying in
        place (cut off by the shrinking edge); it merges back into the pill.

### CUJ 6: Several notifications in a row

*   **Given**: CUJ 5's banner showing.
*   **When**: another notification alerts (another app, or another message in the same conversation).
*   **Then**: the banner stays; the old message blurs out over 120 ms and the new one slides in; the 4 s start again.
    *   Arriving while the banner closes, the banner turns back and grows again, with no jump.
    *   A stream of alerts keeps the banner up, 4 s after the last.
    *   A notification removed from the shade while it shows ends its arrival at once.

### CUJ 7: Conversations

*   **Given**: a messaging app's notification holding several messages.
*   **When**: it arrives.
*   **Then**:
    *   the banner shows only the newest message, on one line;
    *   in a group chat the group's name is the title, and the message reads "Sender: message";
    *   line breaks in any notification's text become spaces.

### CUJ 8: Locked

*   **Given**: the phone locked, or locking while a notification shows.
*   **When**: a notification arrives or is showing.
*   **Then**: Goal 5:
    *   app name only;
    *   nothing for a secret notification, or with the phone's lock-screen notifications off;
    *   a message already on the banner when the phone locks disappears without playing its exit, whenever its removal
        reaches the island.

### CUJ 9: Full screen, and Remove animations

*   **Given**: an app goes full screen; or the phone's Remove animations is on.
*   **When**: the status bar hides or shows; or any state changes.
*   **Then**:
    *   *Full screen:* the island fades and shrinks away; a swipe from the top shows it for a moment on a soft spring.
    *   *Remove animations:* every change happens at once, and the banner and pill draw as plain shapes, nothing
        stretched.

## Technical Plan (Architecture)

```text
 Android                    app (:app)                         engine (:engine)
 -------                    ----------                         ----------------
 BatteryManager ─────────────────────────────────────────► battery ─┐
 MediaSession ──► SessionMedia ──────────────────────────► media ───┤
 clock ──────────────────────────────────────────────────► time ────┼─► SourceBinder ─► ComponentHost ─► ComposeHost
 cutout, insets ─► DeviceDisplayInfo ────────────────────► device ──┤    (values)      (plan, reconcile,  (RenderNodes,
 notifications ─► Notifs ─► KdlArrivals ─► ArrivalTracker ► notifications ┘               presence,       painters:
                  (shade)   (alerts, lock)  (hold, number)                                motion)         BlobPainter +
 KdlBar.make: sources, mode, shown, banner-width, 12/24 h, lock hook ──► ComponentView ◄─┘                BlobElastic)
```

*   **The component.** `island.kdl` 0.10.0 is a `blob`: it draws the smooth union of its box children as one shape. It
    is placed on the cutout.
    *   *Its first box is the pill.* Inside it are four fixed-width rows, one per state (idle, media, charging,
        notification). Each piece shows on a condition of the ranked state, with `enter` and `exit` presence and
        delays for the staging.
    *   *Its second box is the banner's shape,* with no size at rest. It holds a content box, and in it one message per
        arrival (`each` over `notifications.shown`). The repeat comes twice: `Message` for an arrival that comes alone
        (it arrives after the drop has parted, 300 ms in), and `Next message` for one that replaces another (it
        arrives as the old has gone, 110 ms in).
    *   *Widths:* the banner is `banner-width` wide (380 dp, or the screen less 16 dp each side, set by the app) and the
        content follows it. The pill's states keep fixed widths (132, 200, 164, 236 dp).
*   **Sources.**
    *   `battery`: the level, whether it charges, and `plugged` (true for the 3 s after the charger connects).
    *   `media`: playing, the title and the art (`SessionMedia`, the launcher's media session reader).
    *   `time`.
    *   `device`: the cutout and the bar's height.
    *   `notifications`:
        - `arrival`: the notification that just arrived, a record of its name, text, app, letter and "others";
        - `arriving`: whether there is one;
        - `shown`: a list holding the current arrival alone, keyed by its own number, so a replacement plays one exit
          and one enter.
    *   `ArrivalTracker` decides what arrives (alerting, new or posted again, and not "alert once"). It holds the
        arrival 4 s, restarting for each new one, and makes its text one line of at most 500 characters (names 200).
*   **The blob.** Each shape is a rounded rectangle.
    *   *Android 13 and later:* `BlobPainter` draws the union with an AGSL shader. It takes each shape's signed
        distance, joins them with a polynomial smooth minimum over `smooth` (16 dp), and blends the colours by the same
        weights. The shader runs over the shapes' bounds widened by a margin for the union's swell.
    *   *`BlobElastic`:* a small physics model, stepped each frame while anything moves. The **gap** is the space
        between the pill's bottom and the banner's top. The **reach** is half of `smooth` (8 dp), the distance within
        which the union still joins two shapes.
        - *Joined:* the pill's bottom follows part of the gap and the banner is held back by part of it. A thin neck
          shape joins them, narrowing as the gap left between them nears the reach, and the banner takes the pill's
          colour.
        - *The break:* past the reach the pull ends. A spring overshoots into the squash and settles, and the banner
          turns to its own colour.
        - The numbers are constants in `BlobElastic.kt`, not attributes (a recorded deviation, D-120).
*   **Motion.**
    *   *Tracks:* every size, place, opacity, scale and blur is a track in `NodeMotion` (the launcher's springs and
        Bézier curves). Retargeted mid-way, it continues from its value and speed.
    *   *Presence:* `enter` and `exit` effects are fade, scale, rise, grow, slide and blur, each with an optional
        `delay`.
    *   *Text:* digits roll; the bars loop; a long title scrolls.
*   **Privacy.** For now the rule lives in the app, and moves into the engine in E3, when the shade needs the same rule
    (D-120).
    *   `KdlArrivals` reads the lock state when the screen goes off (counted as locked at once), comes on, or is
        unlocked.
    *   Locked, it gives the app's name and no text, and nothing for the cases of Goal 5.
    *   At the lock, `KdlBar` marks every node then under the banner's content box as never playing an exit
        (`ComponentView.cutExits`).
*   **Below Android 13** the detailed mode shows as minimal (D-120): the app passes `minimal` to the component.
*   **Coexistence.** With the island on, the shade's own notification banners give way to the island, except calls and
    alarms, which ring as banners (T-014). Merging them into the island is E3.

## Alternatives Considered

### Alternative A: Two plain boxes, no smooth union

*   **Pros**: no shader; the same on every Android version; cheaper per frame.
*   **Cons**: no neck, no swell: the banner would appear under the pill rather than part from it.
*   **Reason for Rejection**: Rejected by the owner: the design asked for a liquid parting (concept B, then "it should
    work exactly like a fluid would", [T-015](../team/threads/T-015-e2-14c2-detailed.md), D-117). D-098 had held the
    smooth union back "only if a later design asks for it"; this design asked.

### Alternative B: The banner grows to full size, then separates

*   **Pros**: the banner is readable earlier.
*   **Cons**: read as a card growing behind the pill and sliding down; the pill did not react.
*   **Reason for Rejection**: Rejected by the owner on the S24, 2026-11-13 (T-015; D-117).

### Alternative C: A glass banner with live blur behind it

*   **Pros**: matched the design language's glass.
*   **Cons**: the shadow read as heavy, and the live blur works only on One UI.
*   **Reason for Rejection**: Rejected by the owner, 2026-11-13: the default theme is opaque (D-118).

### Alternative D: Swap a replacement's text in place

*   **Pros**: no extra nodes.
*   **Cons**: the text changed in one frame.
*   **Reason for Rejection**: the owner saw it pop on the S24 (2026-11-14, T-015); one message per arrival replaced it.

--------------------------------------------------------------------------------

## Risks & Mitigations

| Risk | Mitigation |
| --- | --- |
| A detailed arrival misses the S24's frame bound (shader, elastic) | Measured at this milestone. If it misses, the blob draws plain shapes (no neck, no elastic) and the owner is told; nothing switches at run time |
| The lock is seen late (the screen-off broadcast) or a removal reaches the island after the lock | Screen-off counts as locked at once; shown messages are marked never to exit (`cutExits`), with no time limit (Codex, T-015) |
| A stream of alerts keeps the banner up | Accepted: each alert restarts the 4 s, as the owner tested (CUJ 6); a removed notification ends at once |
| Narrow screens, rotation, large text | The banner fits the screen (16 dp gutters); the pill's widths are fixed and its text does not follow the font size (a known limit until tokens) |
| Android 12 and below: no shader | The detailed mode shows as minimal there (D-120); the minimal pill needs no shader |

The plan's own risks are in [`DEFAULT_LAYOUT_PLAN.md` §5](../DEFAULT_LAYOUT_PLAN.md).

## Non-Functional Requirements

*   **Privacy.** Goal 5. Other apps' text is bounded (D-068) and never logged. Captures of notifications on the S24 stay
    in `tools/shots/` (git-ignored), never committed or quoted.
*   **Performance.** Goal 6. The shader runs only over the shapes' bounds; its uniforms are reused; a blur effect is
    made again only when its radius changes; the shader is compiled off screen before first use (`ShapeWarmUp`).
*   **Rollout.** Behind the developer switch, which survives restarts (stored in the app's preferences):
    *   on: `adb shell am broadcast -a dev.launcher.app.TEST_STATUS -p dev.launcher.app --es do kdl --ez on true --es
        component island`;
    *   the mode: add `--es mode detailed` or `--es mode minimal`;
    *   off: `--ez on false`.
    *   The broadcast is handled by the launcher's gesture service (`GestureNav`), which owns the status bar's window.
    *   `StatusBarView` stays the bar for everyone else until this milestone is signed off.

--------------------------------------------------------------------------------

## Detailed Implementation Plan

As built on `team/claude/e2-14c2-detailed` (tasks T-012 to T-017; decisions D-104 to D-120). Earlier island work
(T-012 to T-014, T-016, T-017) was merged into `engine` before this branch; this branch adds T-015.

### Target Files (as built)

**Engine, the component and its primitive**

1.  `engine/src/main/assets/kdl/components/island.kdl` (Modify): the states, the rank, the staging, the blob, the banner,
    `banner-width`, and the two message repeats.
2.  `engine/src/main/java/dev/launcher/engine/ui/BlobPainter.kt` (Create): the union shader, per-shape colours. The
    margin is (n − 1) × k / 4 + 1 px, where n is the number of shapes and k is `smooth` in px. Plain shapes below
    Android 13, the elastic's neck left out.
3.  `engine/src/main/java/dev/launcher/engine/ui/BlobElastic.kt` (Create): stretch, neck, break, squash, colour turn,
    `reset`.
4.  `engine/src/main/java/dev/launcher/engine/model/Primitives.kt`, `Validator.kt` (Modify): the `blob` primitive
    (`smooth`, `align`, `elastic`).
5.  `engine/src/main/java/dev/launcher/engine/model/MotionSpec.kt`, `MotionGrammar.kt` (Modify): presence effects
    `slide` and `blur`.

**Engine, the host**

6.  `engine/src/main/java/dev/launcher/engine/ui/Host.kt` (Modify):
    *   the blob's layout and stepping;
    *   the blur's reach in the draw bounds, carried through parents' layers;
    *   `applyBlur`;
    *   `endExits`, `cutExits(top)` and `HostNode.noExit`;
    *   `snapMotion` resets elastic blobs.
7.  `engine/src/main/java/dev/launcher/engine/ui/ComposeHost.kt`, `BoxPainter.kt`, `ShapeWarmUp.kt`, `TextPainter.kt`,
    `motion/NodeMotion.kt` (Modify): drawing the blur and the union's boxes, warming the shader, the blur track.
8.  `engine/src/main/java/dev/launcher/engine/ui/ComponentView.kt` (Modify): `endExits`, `cutExits(label)`.

**Engine, sources**

9.  `engine/src/main/java/dev/launcher/engine/source/Arrivals.kt` (Modify): an arrival's text and app, one line and
    bounded; its number (`seq`) and `replaced`; refreshed in place without restarting its time.
10. `engine/src/main/java/dev/launcher/engine/source/NotificationIcons.kt` (Modify): `notifications.shown`.

**App**

11. `app/src/main/java/dev/launcher/app/statusbar/KdlBar.kt` (Modify): the island's sources, `mode` (`shownMode`: minimal
    below Android 13), `banner-width` (`bannerWidth`), `shown`, and the lock hook.
12. `app/src/main/java/dev/launcher/app/statusbar/KdlArrivals.kt` (Modify): what alerts; locked, the app's name only;
    `lockedShows` (Android's secret and lock-screen settings); the screen and unlock receiver.
13. `app/src/main/java/dev/launcher/app/shade/Notifs.kt` (Modify), `app/src/main/java/dev/launcher/app/shade/Conversations.kt`
    (Create): a conversation's newest message; a group decided by its title.
14. `app/src/main/java/dev/launcher/app/Badges.kt` (Modify): `TEST_NOTIFY` (an adb broadcast that posts a test
    notification; `--es messages 'A|B'` for a conversation, `--es group NAME`).
15. `app/src/main/java/dev/launcher/app/GestureNav.kt`, `statusbar/StatusBarView.kt` (Modify): the developer switch's
    mode; the bar's show and hide reaching the island.

**Tests and proofs**

16. `engine/src/androidTest/java/dev/launcher/engine/ui/IslandProof.kt`, `BlobProof.kt` (Modify, Create),
    `engine/src/androidTest/java/dev/launcher/engine/adb/AdbProofInstrumentation.kt` (Modify): the emulator proofs.
17. Unit tests (Create or Modify):
    *   `engine/src/test/java/dev/launcher/engine/ui/BlobElasticTest.kt`, `IslandKdlTest.kt`,
        `motion/PresenceTest.kt`, `motion/NodeMotionTest.kt`;
    *   `engine/src/test/java/dev/launcher/engine/model/BlobSchemaTest.kt`, `MotionGrammarTest.kt`;
    *   `engine/src/test/java/dev/launcher/engine/source/ArrivalsTest.kt`;
    *   `app/src/test/java/dev/launcher/app/shade/ConversationTest.kt`, `app/src/test/java/dev/launcher/app/KdlBarChoiceTest.kt`.

### Verification Plan

*   **The full check**, once at the milestone: `scripts/check.sh` (build, lint, every unit test, the shell tests).
*   **Unit tests**, by what they hold:
    *   *the elastic:* stretch, late break, squash and rest; colours and the neck; the union's margin for 2 to 4
        shapes; `reset` (`BlobElasticTest`);
    *   *the island:* its sources, props and states; the detailed banner's tree; Remove animations redraws the blob at
        rest (`IslandKdlTest`);
    *   *arrivals:* holding 4 s; replacement; lock redaction keeping the time; one line; the arrival's number
        (`ArrivalsTest`);
    *   *exits:* ended at once, and cut for what is shown at a lock however late its removal comes (`PresenceTest`);
    *   *app rules:* conversations and groups (`ConversationTest`); minimal below 13, the banner's width, and Android's
        lock rules (`KdlBarChoiceTest`);
    *   *motion:* slide and blur arrivals (`NodeMotionTest`), the grammar (`MotionGrammarTest`).
*   **The emulator proof:** `adb shell am instrument -w -e mode component-island -e cutout emu01
    dev.launcher.engine.test/dev.launcher.engine.adb.AdbProofInstrumentation`. `emu01` is the emulator's simulated
    camera cutout. It passes when it prints `result=PASS`. Its stages:
    *   `rest`: size, place, and the camera clear;
    *   `rolls`: digits rolling;
    *   `media` and `media-details`: the media state and its staging;
    *   `arrivals`: the notification and charging states and their rank;
    *   `reduced`: Remove animations;
    *   `detailed`: the arrival and break, the banner's width and centre, a replacement, a close turned back by a new
        arrival, and the close.
*   **The S24, frame stats:** the island's window is `LauncherStatusBar`.
    1. Reset with `adb shell dumpsys gfxinfo dev.launcher.app reset`.
    2. Post a detailed arrival with `TEST_NOTIFY`, then a replacement 1 s later.
    3. Within a second, read `adb shell dumpsys gfxinfo dev.launcher.app framestats`.
    4. Run `python tools/framestats.py` on it: missed refreshes per 100 frames.
    5. Repeat 10 times.
    6. The same for the close, and for media starting.
    7. Idle: frame time with nothing moving.
    *   *The bound:* at most 1 missed per 100, idle under 1 ms.
    *   The Redmi Note 10 Pro (the slower phone) gets the same run when connected; if it is not, the report says so.
*   **The owner** tries every CUJ on the S24.

### What "done" means for this milestone

All of these (D-119's milestone, in order):
1.  **The drift report agrees.** A fresh reader compares this document with the code; each difference is fixed or
    decided by the owner.
2.  **The code review is answered.** Fresh-context reviewers and Codex (the team's second agent,
    [`docs/team/PROTOCOL.md`](../team/PROTOCOL.md)) review the code since the last milestone. Every finding is fixed or
    answered, and Codex writes `codex: AGREE` in the task's thread.
3.  **The checks pass:** the full check, every unit test and every proof stage, and the S24's frame stats within the
    bound.
4.  **The owner signs off** after trying every CUJ on the S24 (gate 3 of D-119).

The plan's other exits for the status bar stay with their own steps, outside this milestone:
- the cover over One UI's charging and sharing chips (`StockBarCover`);
- comparison against reference captures and parity with `StatusBarView`;
- both light and dark themes;
- the pull that follows the finger (E3).

--------------------------------------------------------------------------------

*Drafted with EGM (Elephant-Goldfish Model), the review process of D-119.*
