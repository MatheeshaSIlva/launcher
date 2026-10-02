# Progress and device results

Results from the real app on the Galaxy S24 (Android 16). Only what a device log or Matheesha confirmed counts as passed.

## Task 1: project skeleton (commit 9695df8), passed 2026-10-04

- CI built and published the rolling `latest` release; phone ran `BUILD 9695df8` (matches the commit).
- Shizuku: binder received, permission dialog, granted, user service connected; `identity` = `uid=2000 pid=13210 android=16 sdk=36 device=samsung SM-S921B`.
- Updater version check: correctly reported "already on the newest build".
- Silent install via UPDATE (9695df8 → 088f152): worked.

## Task 2: safe settings + Restore notification (088f152), passed 2026-10-04

- Test A (Shizuku running): "Test: break system" hid the stock clock/icons; notification **Restore system** brought them back. As expected.
- Test B (Shizuku stopped): Restore brought animations back, status bar stayed hidden and the notification said so. After restarting Shizuku, Restore cleared it. As expected.
- Bug found: the safe-settings line always said "status bar needs restart" (it was fixed text about capability, not state). Fixed: the app records
  that it hid the bar together with `Settings.Global.BOOT_COUNT`; the line shows "hidden by the launcher" only while that record is current
  (cleared by a successful restore or by a reboot). Every future code path that sets disable flags must call `SystemRestore.markStatusBarHidden`.
- Fix confirmed on the phone (66c0995).

## Task 3: watchdog with foreground-service heartbeat, passed 2026-10-04 (b7198ce)

Ported from the probe with three deliberate changes:
- **Suspend-safe staleness.** The probe compared wall-clock times (`date +%s` vs. heartbeat mtime). After the phone sleeps, the wall clock jumps but
  neither side ran, so it would have fired on a live app. Now the service writes a new value each beat and the loop counts consecutive unchanged checks
  (4 = about 4 s of real running time).
- **Restore plan instead of fixed values.** The app hands the loop the exact commands that undo its changes (`SystemRestore.restorePlan`), so it gives back
  the user's own animation scales and does nothing when nothing is changed. Rule: call `Watchdog.sync` *before* changing system state.
- **No process-name checks and no per-beat fork.** Loop identity = its pid file confirmed through `/proc/<pid>/cmdline`; heartbeat is an in-process file
  write in the shell service (oneway binder call), not a `touch` process every second.

Pieces: loop files in `/data/local/tmp/launcher-wd/` (`wd.sh`, `hb`, `restore`, `pid`, `log`, `fired`). Heartbeat thread lives as long as the app process;
`WatchdogService` (foreground, `specialUse`) keeps the process alive and unfrozen, its notification is the safety notification.
After the loop fires, the next arm reports `FIRED` and the app clears its records.

Device results (9d5cb83):
- Kill test passed: break system → force-stop → stock bar and animations back by themselves in ≈5 s (loop fired 00:57:27); on reopen the app
  reported the fire and re-armed with an empty plan.
- **New finding: Shizuku itself restarted twice** (binder dead + received at 00:58:18/20 and 01:00:49/51, during the screen-off test). Each reconnect
  had to start a new loop and no `FIRED` was reported, so **the Shizuku restart killed our loop without running its plan**. The probe only tested our
  user service dying, never the Shizuku server. Risk: if Shizuku dies and does not come back, nothing restores the bar until a reboot.
- Diagnostics added (next build): the loop traps TERM/HUP/INT and runs its plan, logs are kept across loops, and a pid file left behind by a
  dead loop is reported as `VANISHED` (= uncatchable SIGKILL).
- bf8a984: both Shizuku restarts (screen off 01:09:18, screen on 01:11:38) → `VANISHED`: the loop got an uncatchable SIGKILL. Matheesha: Shizuku
  stops and restarts itself sometimes (One UI suspected; the thedjchi fork's own watchdog restarts it). Bar stayed hidden through screen off/on.
- Hypothesis (unproven): wireless-debugging Shizuku descends from `adbd`, so our loop sits in adbd's cgroup; when adbd stops, init SIGKILLs the whole
  cgroup (`setsid` changes session, not cgroup). Next build logs the loop's cgroup and tries to move itself into its own cgroup.
- 8e3184a: **cause confirmed.** The service's cgroup is `/system/uid_0/pid_<adbd pid>`; adbd's pid changes on every screen off and screen on
  (5602 → 10444 → 11733), so **adbd restarts on screen off/on** and init SIGKILLs its cgroup, taking Shizuku, our service and the loop with it.
  Escape is impossible: `mkdir /sys/fs/cgroup/system/uid_0/pid_*` → Permission denied (root-owned). The cgroup-move attempt stays in as a harmless no-op.
- Consequences: (1) a watchdog started through Shizuku cannot outlive a Shizuku restart; (2) **bigger:** Shizuku is gone for ≈2 s right after every
  screen-on, exactly when recents/snapshots/switching would be used (phase 2 risk). Next: find what restarts adbd (diagnostic button pulls logcat).
- Option for the status bar: probe round 1 showed binder `StatusBarManager.disable` flags set with a token from our service process are cleared by the
  system when that process dies (`cmd` flags persist). That is a system-enforced dead-man switch, at the cost of the stock bar showing until reconnect
  after each Shizuku restart. Decision pending the adbd diagnosis.
- 0791ddb: **root cause found.** On lock/unlock, `UsbDeviceManager` switches to the "screen unlocked functions" (`mtp,conn_gadget`), calling
  `trySetEnabledFunctions(forceRestart=true)` → `setUsbConfig(none)` → adbd restarts → Shizuku, our service and the loop die; the thedjchi fork's
  `AdbStartWorker` then reconnects on tcp:5555 and restarts Shizuku (≈1–2 s). Happens with no cable plugged in; Auto Blocker was off.
- **Fix (user setting):** Developer options → Default USB configuration → "debugging only" (Matheesha's choice; "No data transfer" should also work).
  After that, adbd kept pid 23277 and loop 23342 survived lock/unlock (01:35:33 → 01:37:45). Confirmed.
  → **Onboarding requirement:** detect a default USB configuration with data functions (e.g. `dumpsys usb` → `mScreenUnlockedFunctions`) and
  explain/offer the fix, otherwise Shizuku dies on every lock and unlock. Recorded in REQUIREMENTS.md.
- Sleep test passed: break system → screen off ≥ 2 min → bar still hidden on wake (watchdog stayed quiet).
- Screen-off evidence so far: loop 28960 lived from 00:58:20 to ≈01:00:49 (screen off) without firing, so suspend did not trigger it. Repeat cleanly.

**Decision (2026-10-04, Matheesha): status bar via binder flags (option A).** The shell service hides the stock bar with
`IStatusBarService.disable/disable2` and a token owned by its own process (same flags as `cmd … clock notification-icons system-icons`).
The system drops them when that process dies; the service also links to a token from the app and clears them if the app dies. So no crash,
force-stop or Shizuku death can strand the stock bar. Cost accepted: after a Shizuku restart the stock bar shows until we reconnect and re-apply
(≈1–2 s; rare now that the USB setting is fixed). The watchdog loop now only restores animation scales. `cmd` disable flags are no longer used
(restore still sends `cmd … none` once to clear leftovers from older builds).

Device results (b7198ce), **task 3 passed 2026-10-04:**
- Binder flags work on One UI 16: `disable: ok via disable; disable2: ok via disable2`, stock clock/icons hidden.
- Test: kill service → stock bar back immediately, hidden again ≈1 s later after the automatic rebind.
- Force-stop → stock bar back immediately; animations back ≈5 s later (loop fired at 01:51:12, reported on reopen).
- Reopen → wish re-applied (bar hidden); Restore system → bar and animations back.

Open: `specialUse` foreground service needs a Play justification; heartbeat writes a tiny file every second (fine for now, revisit for battery/flash).

Design:
- Restore = animation scales back to the values saved before we changed them (default 1.0) + `cmd statusbar send-disable-flag none`.
- When Shizuku connects, the app self-grants `WRITE_SECURE_SETTINGS` and `POST_NOTIFICATIONS` (`pm grant`). After that, animation scales can be
  restored with no Shizuku at all. Status bar flags can only be cleared by the shell: without Shizuku the answer is a reboot (or the watchdog, task 3).
- Persistent safety notification with **Restore system** and **Safe settings** actions; the safe-settings screen is a fixed stock-widget screen with its own
  app-drawer entry ("Launcher safe settings") and own task.

## Task 4 (phase 2 slice), step 1: gesture strip + card window (in test)

Port of the probe's `GestureStrip.kt` into `GestureNav.kt`, plus frame measurement (task 5 pulled forward):
- Stock home/recents gestures blocked with `DISABLE_HOME | DISABLE_RECENT` through the same service-owned binder flags as the status bar
  (`setDisableFlags`), so a dead service brings stock gestures back by itself. The strip is only shown while those flags are confirmed active.
- Strip = overlay over the system gesture area (`navigation_bar_gesture_height`, min 20 dp) with our own handle pill.
- Snapshots fetched on ACTION_DOWN (no polling): cached `getTaskSnapshot` first, then `takeTaskSnapshot` (fresh) replaces it; timings logged.
- Home is started by the app (overlay visible → background start allowed), shell `am start` as fallback. The card's opaque backdrop stays
  until HomeActivity has resumed (+1 frame), then the card window fades out (120 ms).
- Per gesture: Choreographer frame pacing + touch-to-frame latency (as in the probe). "Frame report" = `dumpsys gfxinfo` for all our windows.
- Not yet: launch/close cards, recents, own status bar; strip over the keyboard; landscape gesture area.

Device results (e765e6a): **failed, user got stuck.**
- On our home screen the strip ignored swipes by design, but our home has no app list → with stock home/recents blocked there was no way out
  (escape: shade → Restore system, which works). Fix: on home, swipe up returns to the last app.
- **The strip disappeared when Settings was in front** (opened from QS), leaving stock gestures blocked → stuck in Settings. Cause (expected):
  Settings hides non-system overlays (`HIDE_NON_SYSTEM_OVERLAY_WINDOWS`); since Android 12 any app may do the same (`setHideOverlayWindows`,
  e.g. banking apps). So a `TYPE_APPLICATION_OVERLAY` strip cannot guarantee "one gesture set everywhere". Logging added to confirm.
- Test aid: the safety notification has a "Gesture nav on/off" action.

Device results (8a93d7c):
- In Chrome everything worked: flick up → home, sideways → previous app, spring back. Drags: 0 dropped frames at 120 Hz
  (median 8.33 ms, worst 8.44 ms), touch → frame 5.5 ms median. End animations dropped frames: home ≈3 (worst 25 ms),
  first quick switch ≈6 (worst 33 ms). gfxinfo over the run: 5.2 % janky, p99 53 ms (includes our home screen's log redraws).
- Cached snapshot of the foreground task is null (`cached -1`); fresh `takeTaskSnapshot` 54–309 ms → the card starts as a plain colour.
- Hidden in Settings confirmed; `onWindowVisibilityChanged` is NOT called when the system force-hides an overlay, so the app cannot even detect it.
- Matheesha: "it's only an overlay until the gesture is completed, the app keeps running behind" → intended (like stock: the app stays live until the
  gesture commits). Later the card should sit over the real home/wallpaper instead of a dark backdrop.

**Decision (2026-10-04, Matheesha): strip and cards become accessibility overlays** (`TYPE_ACCESSIBILITY_OVERLAY` via `NavAccessibilityService`,
enabled by the app through WRITE_SECURE_SETTINGS). Stock gestures are blocked only while that service is connected; disconnect → flags dropped.
Strip removed while the keyguard is up. Gesture nav also turns system transition/window animations off (watchdog plan first).
**Open (Play):** accessibility API use for a non-accessibility purpose needs a policy justification or a fallback in the store build.

Device results (8d11dea): accessibility overlay works (strip visible and working in Settings; no dialog when the service is enabled).
- Holding/dragging smooth (mostly 0–2 dropped). Matheesha: "the app close and switch animations suck"; a brief flash of a plain colour over the card.
- Home end animation: ≈4–6 dropped, worst 33–42 ms, touch→frame p95 ≈38 ms, even with system animations off.
- Flash cause: the card window showed its placeholder colour until the snapshot arrived (cached call 58–244 ms ran before the fresh one).
- Stutter cause (likely): card animation shared the main thread with our home screen, whose first frame after resume lays out the 500-line log.
- Changes (next build): GestureNav runs on its own UI thread (`HandlerThread` at display priority: own Looper, Choreographer, input and
  animation); fresh snapshot only; the card window stays invisible until the snapshot is there (time logged as "card visible … after").

Device results (5f36928): flash gone; strip hidden on the lock screen and back after unlock (confirmed). Drags 0 dropped.
Matheesha: flick home "a bit stuttery because it's too fast, didn't feel like 120 Hz" → wants the iOS motion, not an approximation.
Quick switch "horrible": it still tracked the finger vertically (unclear whether home or switch), and only our overlay moved over the
current app; the previous app just popped in at the end.

Rework (next build):
- `Spring.kt`: analytic damped spring (response/damping like UIKit), velocity carried from the finger, re-targetable.
- `CardView.kt`: card = hardware rounded-outline clip on a full-display view (no relayout per frame), snapshot scaled to cover,
  crossfade into the app icon; corners from the display's `RoundedCorner` radius to the icon's.
- HOME: card shrinks with upward travel (rubber band), the finger anchor scales with it. As soon as the card is visible the real home
  screen is started *behind* it (zoomed 1.08, settles to 1 on commit). Release: springs (0.42 s, 0.9) into the app's icon on home
  (icon hidden meanwhile), else to the centre and fade. Cancel: spring back, then the app is brought back and the card goes once the
  accessibility event says it is in front.
- SWITCH: horizontal axis only; the previous app's card (cached snapshot) slides in beside the current one over black; the real
  switch is issued on release and the cards go when the app is in front.
- New home screen: wallpaper, clock/date, dock (Settings, Chrome, TikTok, Samsung Calculator); dev panel moved to `DevActivity`.

Device results (a3a98d1), Matheesha: closing at 120 fps but "still feels linear"; wants app opening animations; wants the app
to stay live during the hold instead of being paused; a split second of unzoomed home before the zoom; corner radius jumped
up when letting go early (spring back); the card stuck to the top of the screen; asked for a liquid-glass dock.
Causes and changes (next build):
- Radius jump: the "icon" radius was computed from the target width, which is the full screen when springing back. Fixed.
- Stuck to the top / linear: scale was linear in travel with the card's bottom on the finger, so its top edge barely moved.
  Now ease-out (`1 - 0.62·(1 - e^(-travel/0.28H))`) with the finger anchored in the card; softer springs (position 0.5 s/0.86).
- Paused app + zoom flash: home was brought to the front at drag start (pausing the app; home's stale unzoomed frame showed).
  Now HomeActivity records a `Picture` of itself at rest; the card window draws it behind the card (zoom 1.08). The real home
  starts only on commit, under the picture; the picture's zoom springs to 1, and it is swapped for the real home only after
  home reports a drawn frame. Cancel = spring back, no relaunch (the app never left).
- Live card content is NOT done: needs a live mirror of the app (candidate: `IWindowManager.mirrorDisplay`, as the magnifier
  uses; shell may hold READ_FRAME_BUFFER). To be probed separately.
- Launch: card grows from the icon (icon colour, warm apps' cached snapshot) while the app starts; home zooms to 1.08.
- Glass dock: home draws the wallpaper itself (needs MANAGE_EXTERNAL_STORAGE via appops to read it; fallback: system
  wallpaper window, plain dock). AGSL shader: frosted body, convex-bevel refraction at the rim, per-channel dispersion,
  rim highlight. Open (Play): all-files access only for the wallpaper.

Device results (e4807b6), Matheesha: closing "good now". Wallpaper unreadable on the first try, read automatically on the next
(the retry works). Glass: "blur too high to see any bending". Opening "absolutely sucks": the app appeared instantly, then our card
animated on top of it. Holding during a close: TikTok's sound kept playing but the card is a still image.
Changes (next build):
- Opening: the card window now draws the home picture behind the growing card (zooming 1 → 1.08), and the app is started only
  after that window has drawn (fallback 100 ms; without a picture, only once the card is full screen). Pictures of home are
  also recorded with each dock icon left out, so no duplicate icon shows under a launching/closing card; on close the real
  icon is shown again before the card window goes.
- Glass: mostly clear body (frost 0.22, none at the rim), lens magnification 6 %, stronger rim refraction (34 dp) and
  dispersion (0.45).
- Live card content: probe added (DEV → "Probe: live mirror"): shell `IWindowManager.mirrorDisplay(0)` → SurfaceControl in a
  Bundle → reparented into a half-size overlay window for 8 s. Questions: allowed for the shell? live? recursive?

Device results (2575259), Matheesha: launch animation fails when opening several apps quickly; the gesture pill vanishes during
open/close animations and gestures are sometimes delayed. Requirement: gestures must work at all times, including in the middle
of any animation; opening and closing the same app at 0.1 s intervals must stay smooth. "Fluidity is something I will NOT
sacrifice." Dock "a bit better, can still be improved".
Causes: the card window was added/removed per gesture (costs frames, and it covered the strip because it was added later);
touches were ignored during animations and the "wait for home / app" holds; one worker thread served both the task lookup and
slow snapshots.
Rewrite (next build):
- One persistent card window (INVISIBLE when idle) created before the strip, so the strip is always on top and touchable.
- Every phase can be interrupted: a touch takes over the card where it is (velocity continuity through the springs); a switch
  in flight is completed in place and its app's card is grabbed; tapping the icon of the app whose card is on screen reverses
  the card from its current position and velocity; tapping another icon replaces the card. Generation counter invalidates
  callbacks of interrupted phases.
- Separate workers for task lookup and snapshots; recent images per app (<10 s) let a card appear immediately.
- Dock: native elevation shadow under the glass.

Device results (7a540ff), Matheesha: "the app launch and close animations are now really smooth". Remaining problems:
- Opening the same app very fast: eventually the card went out of frame. Cause: grabbing a card smaller than the drag model's
  minimum (icon-sized) snapped the scale to the model's minimum while the finger anchor used the real scale. Fix: the grab
  keeps the card's real scale, aspect, corner radius and icon blend and blends them into the model as the card is pulled
  back to full size; springs now interpolate corners and icon blend from wherever they start (no snap on release).
- Sometimes could not reopen until the close finished. Cause: the hidden icon was View.INVISIBLE, which takes no taps.
  Fix: hidden icons use alpha 0.
- Dock shadow missing during animations (elevation shadows are not recorded into the Picture). Fix: drawn shadow view.
- Sideways switching only went right, between two apps. Now runs of quick switches keep their own order: right = older,
  left = newer, cards on both sides; a run ends after 4 s, a launch or going home.
- Live mirror probe crashed the system (see PROBE_FINDINGS); removed.

Device results (901ee2a), Matheesha: "the animations and everything looks great now". New: the launcher did not follow a
wallpaper change. Cause: home only listened for ACTION_WALLPAPER_CHANGED while resumed, but the change happens in Settings.
Fix: on every resume compare `WallpaperManager.getWallpaperId(FLAG_SYSTEM)` with the loaded one, plus an
OnColorsChangedListener (fires in the background too). Requested: a "disintegrate, glowy dot matrix, fluid" transition:
`WallpaperTransition.kt`, one AGSL pass over both images (rising ragged front; mosaic → glowing dots on dark; grid carried by
a curl-noise swirl; colour swap at the peak; dots grow back into the new image), 1.8 s ease-in-out; crossfade fallback.
- Matheesha on d32ee43: "that animation sucked"; it must transition between old and new wallpaper, last long enough, and
  look like Google Photos' reveal of an AI-edited image. Rebuilt (from memory of that effect, to be corrected by him): the old
  image stays; a soft glowing front expands from slightly above the centre with a wobbling edge, revealing the new image
  behind it; a light outward ripple at the front; a fine twinkling dot field (7 dp grid) in a wide zone around the front; the
  new image settles from a 3 % zoom; 2.8 s, gentle start.
- Matheesha on 3293e4b: reveal "looks way better"; wants it in the wallpaper's colours and a little more subtle → bloom and
  sparkles now lift the colour underneath instead of adding white; ripple, sparkle opacity and halos reduced.
- Matheesha: apps feel choppier because system animations are disabled. Cause: gesture nav set transition/window animation
  scales to 0 globally, removing every in-app transition. Change (to verify on the phone): scales stay at the user's values;
  every start our cards cover passes `ActivityOptions.makeCustomAnimation(ctx, 0, 0)` (home on close, dock launches,
  switches via new `switchToTaskWithOptions`, bring-back). Scales left at 0 by older builds are restored on connect.
  If One UI ignores per-launch "no animation", fallback = zero the scales only during our own transitions.

Device results (d0a0f9a), Matheesha: in-app transitions (Samsung Settings) still absent; dock highlight "ugly and too light",
blur missing; the wallpaper reveal is "amazing" but the dock background changed late, breaking it; sideways swiping must cycle
recent apps only, never home. Log: no "system animations restored" line (no record of changed scales), so their real values
were unknown; home commit dropped 2–4 frames at release (startActivity on the nav thread); `dev.launcher.app` tasks (our home
started explicitly, plus DEV) appeared in the switch list.
Changes (next build):
- Logs all three animation scales on connect; if gesture nav is on and transition/window scale is 0 with no record of ours,
  sets them to 1 (left over from older builds).
- Glass: highlight, shade and edge line removed; frost 0.92 in the body (rim stays clear and refracting); 3 % lift only.
- Shared reveal front (`Reveal.kt`) used by both the wallpaper and the glass shader: the dock now changes on exactly the same
  frame and place as the wallpaper behind it; at the end the glass keeps the new wallpaper (no rebuild).
- HomeActivity `excludeFromRecents`; our own tasks are left out of switching and "last app" (but still the card when DEV is
  in front).
- startHome / bring-back startActivity moved off the nav thread.
