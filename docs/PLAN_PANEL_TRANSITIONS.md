# Plan: panel transitions as Figma connections with keyframes (proposal, 2026-11-01)

Status: **approved by the owner 2026-11-01** (D-062), part of milestone MS. Nothing here is built yet.

## What the owner asked for (2026-11-01)

> "home and notification screens are like 2 frames in figma and the user can decide the transition. We must give the
> flexibility and options figma offers."
> "the user can change the tokens between states too ... start of the animation, the blur level is one value and the dim is
> also another value and at the end they both change and the user can select different values between states and also
> customize how they change - kinda like using keyframes in a video editor."

Answers to the clarifying questions: panels first (Notification Center, Control Center, Android's shade), then apps and the
other transitions in the same model; following the finger or playing on release is **the user's choice per transition**;
the iOS 27 choreography must come out of the same keyframes, not special code; Smart Animate in a later round.

## The model

**Frames and connections.** Each panel has states: *Closed* (home or an app shows), *Held* (a finger holds the pull; only
layouts that want one, as iOS 27's clear slab) and *Open*. A connection joins two states: **open** (Closed to Open, through
Held if there is one), **release** (Held to Open), **close** (Open to Closed). Each connection has its own transition;
close defaults to "the opening reversed".

**A transition** is:
- a **type**, Figma's: Instant, Dissolve, Move In, Move Out, Push, Slide In, Slide Out (Smart Animate later), with a
  **direction** (from the top, bottom, left, right) for the moving types;
- a **drive**: *follow the finger* (the pull scrubs it, as Figma's "On drag"; the release finishes it on its easing from
  where it is, with the finger's speed) or *play on release* (the pull only decides open or close; it then plays timed);
- **timing**: the motion role it runs on (spring, curve or bezier, as every role today: Gentle/Quick/Bouncy springs,
  ease in/out, back, custom), so Speed and Bounce keep working;
- **tracks**: the keyframes. Picking a type fills them in; editing any keyframe turns the type into "Custom (from Push)".

**Tracks** (one per property; a property with no keyframes keeps its resting value):

| Layer | Properties |
| --- | --- |
| The panel (the destination frame) | offset x/y (share of the screen), scale, opacity, corner radius, reveal edge (the outline's moving edge, iOS's sheet) |
| Its content (clock, list, controls) | opacity, offset y, scale (separate from the panel's background, so content can arrive after the sheet) |
| The background under it | blur, veil/dim |
| What is behind (the source frame: home or the app) | offset x/y, scale, opacity, dim |

A keyframe is a point in the transition (0 to 1), a value, and the easing *to the next keyframe* (linear, ease in, ease
out, ease in-out, back, a bezier; drawn and edited with today's curve editor).

**Values that are looks stay the theme's by default** (layouts, themes and motion remain three layers): blur, veil, dim and
corner radius are keyed as a **share of the theme's value** (blur 0 % at the start, 100 % of the panel material's frost at
the end), so a theme still decides how blurred "fully open" is and the motion preset decides how it gets there. **A switch
per keyframe makes it absolute** (owner, 2026-11-01: "Both"): a number of its own (blur in points, dim in %) that ignores the
theme. Geometry (offsets, scale) and opacity are plain numbers.

**iOS 27 as keyframes.** The iOS 27 preset's Notification Center: *open* follows the finger with the Held state (panel
reveal edge = the finger, background blur 0 %, dim below the edge 30 %, content opacity 0); *release* plays timed (content
opacity 0 to 1 or whole at once, blur 0 to 100 %, what is behind giving way); *close* reversed with its own clock lift.
Control Center: content scale/opacity on the square-root curve it uses today, blur on the log scale (LiveBlur). The first
milestone of the build is that these reproduce **today's frames exactly** (frame-by-frame comparison), before anything new
is offered.

## What the phone allows (constraints, said now so no type surprises later)

- **Moving what is behind.** Our panels are windows over another app's window, which we cannot move. A type that moves
  the source frame (Push, Move Out, Slide's source) shows a **picture** of the app: the live capture we already stream for
  the glass edge (S24: ~8 ms a capture, up to ~80 a second), so it stays live. Secure apps (banking, DRM video) cannot be
  captured: there it falls back to a still picture taken before (and logs it). Home is ours: it moves live.
- **Blur under a moving panel.** On One UI, Samsung's view blur in our window follows a moving outline (Notification
  Center's sheet does it today). On other phones Android's blur behind the window is the whole screen: the background blur
  track still works, but cannot be cut to a panel moving sideways.
- **Smoothness.** Every track is evaluated per frame in plain arithmetic (no allocation, no layout); pictures move by
  translation only. Each step is measured on the S24 (0 missed refreshes) before the next.

## In the settings

On each panel's moment page (Notifications & Control Center): the stage plays the transition on the picture of the phone,
with a scrubber under it (drag to scrub). Under it: the **type** as cards that play themselves, the **direction**, **follow
the finger / play on release**, and Speed and Bounce as today. **Every detail** (the Advanced fold) holds the **timeline**:
a row per property that has keyframes, diamonds on a 0 to 1 track, the scrubber across all rows; tap a diamond for its value
and its easing to the next one; "+ keyframe" at the scrubber; "+ property" for a track not used yet. Open, release and close
are three tabs over the timeline. Search finds each property.

## Build order (each step checked on the emulator, then the owner on the S24)

1. **Engine** (pure Kotlin, unit-tested): tracks, keyframes, easing, evaluation at t; the preset file format
   (`motion.transition.<panel>.<connection>`); the Figma types as generators of tracks.
   **Done 2026-11-01** (D-063): `motion/transition/` (`Transition`, `TransitionTypes`, `TransitionJson`), the `transition`
   value kind (`Value.Trans`, `TransitionKey`, `Design.transition`); 28 unit tests. Nothing reads it yet.
2. **Notification Center on tracks**, in two parts (owner, 2026-11-01, D-064):
   - **2a, the engine for it** (done 2026-11-01): tracks on clocks of their own (a role, a delay, a cue: the connection's
     start or the landing), tracks for the parts (dim below the edge, the picture of what was behind, clock, date, status
     bar), and `PanelPlayer` (open, release over it, close; the handover without a jump). 38 unit tests.
   - **2b**: its hard-coded mappings replaced by the iOS 27 tracks; today's look and motion unchanged, frame for frame (the
     checkpoint before anything new).
3. **Notification Center's types**: Instant, Dissolve, Move In/Out, Push, Slide In/Out with directions; follow the finger or
   play on release; the source frame's live picture.
4. **Control Center, then Android's shade**, the same way (2, then 3 for each).
5. **Settings, part 1**: type cards, direction, drive, on the playing stage.
6. **Settings, part 2**: the timeline editor.
7. Later rounds: Smart Animate; apps' open and close (today's seven styles become tracks); folders, the App Library, the
   App Switcher.

## The owner's answers (2026-11-01)

- Inside milestone MS: MS stays open until this works for the three panels and the owner signs it off on the S24.
- Look values: a share of the theme's value by default, absolute by a switch per keyframe.
