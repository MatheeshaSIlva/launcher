---
name: polish-pass
description: The launcher's bar for "done", the premium, system-level, iOS-grade feel Matheesha sells. Run it before calling any change on screen finished, committing or pushing it, or telling Matheesha to test it, and when planning a new screen, element, panel, gesture, animation, layout or theme feature. It covers how every element moves, the frames between states, interruption, seams where stock Android or One UI shows through, tokens and motion roles instead of literals, and what was really verified. Use it for small changes too (a badge, a label, a colour, one spring); the small ones are where polish gets lost. Skip it only for work that changes nothing on screen (docs, build scripts, a pure logic refactor that keeps behaviour).
---

# Polish pass

Matheesha sells this launcher on a feeling: it is part of the phone itself, not an app on top of it. It gives people back
control of their own device, and it moves like iOS does. People notice when that promise breaks, even if they can't name
what broke: a stock panel showing for one frame, a number that jumps, an animation that ignores the finger, a look they
can't change. This pass is how a change gets checked against that promise before Matheesha sees it. He reviews at this
level, so anything this pass misses, he will find.

Use it twice: once while planning (start from the moments, not the end state), and again before saying "done".

## 1. List what can change

Write down every element the change touches or shows, and every way each one can change. Don't stop at the main path:

- the change itself, the way back, and a reversal halfway through
- data that arrives late (icons, names, pictures, notifications, widgets' updates), or changes while the element is on screen
- a theme or palette change, light/dark, the wallpaper's colours, a new wallpaper, Font size, Display size
- rotation, lock and unlock, a cold start, coming back from the settings, a mini player (PiP) up
- a second gesture or tap arriving mid-animation, and the same thing done again within 0.1 s

The list is the work. A state that isn't on it never gets designed, so it ends up popping.

## 2. For each change: how does it move, and what shows in between?

- **Nothing pops, snaps or just appears.** Small things count: a badge count, a label toggle, a list arriving, a minute
  ticking over, an icon arriving after its card, an empty state. Fade, grow, slide or morph, on a role.
- **It follows the finger where there is one**, 1:1, with the release velocity carried into the spring that finishes it.
- **It can be interrupted and retargeted from where it is**, with its current position and velocity: no jump and no
  restart from the beginning. The user never waits for it to end before the next tap works. Never fix a race by blocking
  input (see the fluidity-first memory: the tap must open what was tapped).
- **Handovers look the same on both sides, frame for frame**: card to real icon, picture to live view, one window to
  another, our view to a system one. Check brightness, blur strength, corners, shadows and position across the handover
  frame. Many of CLAUDE.md's pitfalls are one-frame steps like these.
- **No doubled content.** A still picture fading out over something that moves shows everything twice. Fade between live
  copies, or wait until it rests.
- **Big identity changes celebrate** (a theme switch, a new look): an unhurried, shaped reveal of about 1.5-2 s, not a
  crossfade (see the transitions-impressive memory). Small UI feedback stays quick.
- **Every frame in between is a frame someone sees.** For each transition, picture its 25 %, 50 % and 90 % frames: is
  anything clipped, transparent, the wrong colour, missing its glass or text, or in two places at once?

## 3. Seams with the stock system

"Part of the system" means the stock UI never shows through. Ask:

- Can a stock panel, status bar part, heads-up, chip, starting window, snapshot or system transition appear, even for one
  frame? (CLAUDE.md lists the known ones: One UI's charging and cast chips, snapshot starting windows, system open
  animations on cross-app starts, Edge lighting pop-ups.)
- Is it consistent everywhere: one shade, one status bar, one gesture set, over apps, home, the lock screen and the
  settings, upright and turned?
- If the change alters system state, does it restore itself (watchdog, Restore system) and recover without Shizuku?

## 4. The user's control is real

The pitch is control over the device, so everything a person sees should be something they can change:

- Look values are tokens in the element's own namespace (`comp.<element>.*` aliased to `component.*` / `sys.*`), with a
  source noted (kit, measured, judged). No literal colours, sizes, radii or blur in drawing code.
- Motion goes through a role (`Motion.role(MotionTokens.X)`, `.timed(curve)`, `RoleTiming`), named in
  `motion/MotionRoles.kt` and placed in exactly one moment (`motion/Moments.kt`). No literal durations. Check it under
  the `eased` and `material` presets and a slow Speed: code that never saw an overshoot must not be handed one.
- It works under any theme and layout combination (layouts, themes and motion are separate layers), with the theme's
  colours and with the wallpaper's, in light and in dark.
- If it adds a choice, the settings show it where a person would look for it, playing what it does rather than only
  naming it, and search finds it.

## 5. Cheap and correct while it moves

Performance is measured in one full pass at the end (perf-testing-last memory), so don't trace each step. But don't build
the known costly patterns in the first place. Before finishing, scan the diff against CLAUDE.md's pitfalls, especially:
nothing read in composition that moves, no layout-param changes on an overlay mid-gesture, no new layer size every frame,
nothing built in a transition's first frame (build at the tap, start two frames later), no PackageManager reads on a
drawing thread, new GPU pipelines warmed off screen.

## 6. Verify, then report it honestly

- Check it on the emulator (`tools/build.sh`, `./gradlew testDebugUnitTest`, reinstall), and look at the screenshots
  rather than assuming. Step through the moments from step 1 that the emulator can show: start, middle and end of each
  transition, and an interruption (slow motion through the inbox helps).
- Re-run the flows the change could break (no-regressions memory): launch, close, cancel, grab, switch, and the panels.
- Report what you checked and what you didn't. What only the S24 can show (Samsung blur, One UI chips, real frame
  pacing) is unverified until Matheesha confirms it. Give him short, exact steps and a specific question ("does the
  badge count roll or jump when the second notification arrives?").

## Before saying done

Read the step 1 list once more and answer for each line: how does it move, what's on the frame in between, can it be
interrupted, can the user change it? Any line with no answer isn't done yet. Fix it, or name it to Matheesha as open.
