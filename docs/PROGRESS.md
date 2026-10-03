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

Device results (8e7a4fa), Matheesha: wallpaper reveal "works great". Problems:
- Sometimes the wrong app opened from the dock (Chrome → Settings, Calculator → TikTok). Likely causes: a takeover resolved the
  card's task as "top of recents" (the previous app while the launched one was still starting), and since app starts moved
  to a worker, a late switch/bring-back could land after the tapped app. Fix: the card's task is looked up by package;
  bring-back only uses a task of that package; every start/switch/home goes through one ordered queue where a request is
  dropped if a newer one exists (`[front]` log lines).
- Switch sometimes showed enlarged icons instead of snapshots: a card that ended inside an icon kept iconMix = 1 and was
  reused by the next switch. Fix: icon blend reset per session.
- Clock in the home picture out of date: TextClock stops updating in the background and the picture was only re-recorded
  while home was resumed. Fix: minute tick for the activity's whole life, clocks forced to refresh before recording.
- Dock still not blurred ("are you using the Samsung blur API?"): no. Samsung's dim-behind blur covers the whole screen behind
  a window, not a rect inside our window; its View blur gave only fog. The dock uses our own blurred copy, but frost reached
  full strength only past the 30 dp bevel, so most of the short dock stayed clear. Fix: full frost from a thin rim inward,
  stronger blur (box r5 ×3 at 1/8), bevel 18 dp, refraction 24 dp, dispersion 0.45 → 0.22.

Device results (ac44135), Matheesha: dock "worse": far too much blur and the edges not blurred; the wrong app still opens
sometimes ("make sure you can't click on the closing card again once you swipe up and let go"); closing an app that is not
on the home screen leaves its icon lingering; a fast swipe throws the card far up before it comes down ("reduce the elastiness").
Changes (next build):
- Glass: even frost (0.9) everywhere including the rim (the rim still refracts, now frosted); blur back to ≈20 px.
- A closing card can no longer be reopened: reversal removed; home ignores taps on the closing app's icon until it lands.
  The foreground app of a new gesture now comes from the accessibility "window in front" report when it is in the recents.
- No icon on home: the card fades out while shrinking to the centre (no icon crossfade, no linger).
- Release velocity: only the component towards the target carries over (12 % of the rest), capped at 5000 px/s; position
  damping 0.86 → 0.92.

Matheesha on a0a99fe: blocking taps on a closing app's icon contradicts the 0.1 s open/close requirement: tapping an icon must
simply open that app. Reversal restored. "Nice job fixing the elasticity". Asked: lower handle, half the dock blur, and for
apps not on home fade the app preview (not a white card).
- Wrong app still unexplained from code alone. Added evidence: `[home] tap <pkg>` per dock tap, `[front] now in front: <pkg>`
  from accessibility window events, and after a launch lands, if another app is in front: `[front] WRONG APP …` plus one
  more start of the tapped app.
- Handle 6 dp lower. Blur ≈10 px (blurred copy at 1/4 size, box r3 ×2). No icon on home: the card keeps the app's shape and
  shrinks to 30 % in the centre with scaled corners while fading (the square crop showed mostly white app background).

Device result (3de9fbf), Matheesha: taps on the dock during a close animation were held back until it ended, and sometimes
opened the neighbouring app. **Cause confirmed:** with Window and Transition animation scales set to Off in Developer options, both
problems were gone. It started when 8e7a4fa set the scales back to 1 (the first wrong-app report came on that build). One UI
plays its own transition when home comes to the front even though we pass `makeCustomAnimation(0, 0)`. While that transition
runs, taps on home are held back or hit-tested against home's moving surface, so they land on the icon next to it.
Fix (next build): the fallback planned in d0a0f9a. Transition and window scales go to 0 at the first touch on the strip and on
every dock tap, on the same queue as app starts, so they are always off before anything is started. They return to the user's
values 1 s after the last card session ended (no finger down, no card), so fast open/close runs do not toggle them. Each off
cycle records the user's values and arms the watchdog with them first (`SystemRestore.scalesOffForCards`); the gesture strip
going away restores them at once. Log lines: `[nav] system transitions off while cards animate` / `system animations restored`.
- Matheesha: the wallpaper reveal stopped playing. Cause (ac44135): the time-tick receiver, which also handles
  ACTION_WALLPAPER_CHANGED, was moved from resumed-only to the activity's whole life, so a change made in Settings loaded the
  new wallpaper while home was in the background and `applyWallpaper` swapped it in without the reveal; on resume nothing
  was left to reveal. Fix: like the colours listener, the receiver only marks the wallpaper dirty while home is not resumed;
  it is loaded (with the reveal) on the next resume.
- Matheesha: closing an app after waiting for it to load fades in the centre instead of flying into its dock icon. That path
  runs when the card's app is unknown or has no home icon. Two suspects for a fresh swipe (one that starts after the launch
  animation ended): (1) the accessibility "window in front" report (a0a99fe) also fires for our own windows, and our home task can
  be listed in recents while in front, so the card could be resolved to `dev.launcher.app`; (2) the task lookup answers after
  release, when the card has already chosen "no icon". Next build: our own package no longer counts as the app in front
  (fixes 1, and stops false `WRONG APP` retries), and the close logs which case happened:
  `home (into the icon of <pkg>)` / `app NOT KNOWN yet at release` / `<pkg> has no icon on home`, plus the front report and top task.

Device results (6c55b4a), Matheesha: "everything works great now". Confirmed: taps during a close open the tapped app (open/close
at 3+ per second), no wrong app, the wallpaper reveal plays again, and closing after the app has loaded flies into its dock icon.
Step 1 of task 4 (gesture strip, launch and close cards, quick switch) is done. Still open in phase 2: recents carousel (hold
gesture), own status bar, the scripted frame-log run that turns the phase gate into a number, and the accessibility requirements.

## Home experience, iOS profile (phase 3 pulled forward)

Matheesha now uses the launcher as his real home and could not open apps that were not in the test dock. Decision (2026-10-03):
finish the home experience first, iOS-styled but built so every part can be swapped (drawer style and placement, icon shape,
motion, glass). Scope chosen: App Library, home pages with edit mode (wiggle, drag, folders), Spotlight, long-press menus, plus
dock, glass, icons and motion. Delivered in three builds:
1. App Library, iOS pages and dock, squircle icons, two-layer home depth, drawer placement switch (this build).
2. Long-press menus and edit mode (wiggle, drag between pages and into the dock, drag from the App Library, folders).
3. Spotlight (swipe down) and the Search pill above the dock; motion polish from device feedback.

Architecture (new packages under `dev.launcher.app`):
- `apps/`: `Apps` (every launchable activity for all profiles via LauncherApps, live on install/remove; stable keys
  `pkg/class@userSerial`), `AppCategory` (iOS App Library categories from a known-app table, the manifest category and package
  keywords), `LaunchStats` (Suggestions), `Icons` (renders each icon once into the theme's `IconShape`, iOS superellipse by
  default; LRU cache, mip-mapped; also feeds gesture nav so a closing card turns into exactly the icon home shows).
- `motion/`: `MotionProfile` holds every animation by role (app open/close/cancel, home depth, page snap, drawer, folder,
  rubber band, deceleration, icon press); `Motion.IOS` keeps the card springs tuned on the S24. `IosScroller` = UIScrollView
  physics (0.998/ms deceleration, rubber band 0.55, critically damped bounce), analytic per frame.
- `drawer/`: `AppDrawer` (style) × `DrawerPlacement` (page after the last = iOS, page before the first, swipe-up sheet).
  `AppLibraryView`: tiles (custom-drawn), category folder that grows out of its tile with the library blurred behind,
  search field, A–Z list with index scrubber; pull-down on tiles opens the list.
- `home/`: `HomeConfig` (grid, dock slots, drawer style/placement, icon shape, labels, new apps on home), `HomeMetrics` (all
  sizes from iOS proportions: 1 unit = 1/393 of the width), `HomeModel` (pages flowed in order like iOS, dock, folders and
  widgets in the format already; JSON, atomic writes; seeded once: phone/messages/browser/camera in the dock, clock + system apps
  and the dev panel on page one, user apps on the next pages), `HomeScreen` (strip of pages, dock, indicator, drawer; every
  movement a grabbable spring), `GlassView` (glass that tracks its own screen position while it moves).
- Glass: `GlassStyle` per surface (`IOS_DOCK`, `IOS_CAPSULE`). New: an iOS 26 specular rim (thin edge highlight facing the
  light at the top left, 40 % echo opposite), deliberately faint (0.22) since an earlier highlight was rejected.
- Pictures of home are two layers (`HomePicture`: wallpaper + content) so the content recedes more than the wallpaper behind an
  open app (iOS depth: content ×1.12, wallpaper ×1.04; was ×1.08 for both). Pictures without one icon are recorded on demand
  for any icon (launch: synchronously at the tap; close: on the main thread at gesture start, swapped in when ready) instead of
  four precomputed dock pictures. Home publishes the screen rect of every icon a card can fly into (current page + dock, or the
  App Library's visible tiles/list/folder), so closing returns into the App Library when it was opened from there (as iOS).
- Icon press = dim (iOS) instead of the 0.88 shrink. Dev panel: "Drawer: …" cycles the placement, "Reset home layout".

Not yet: TalkBack for the custom-drawn App Library (needs virtual nodes), SF-like font (SF Pro is not licensable for Android;
system sans for now, Inter is an option), live blur behind the library (it uses a heavy blur of our wallpaper copy).

Matheesha on 0de908f: icons must keep the system shape unless changed in settings; an app shown twice in the App Library
(Suggestions + Creativity) closed into the wrong copy; the launch card is a grey block when the app has no snapshot ("draw the
live app itself"); swiping home pages during a close broke the page transition; spacing and glass blur not iOS enough; SF?
Changes (next build):
- `IconShape.SYSTEM` is the default (icons as Android draws them); cards morph into the system mask's corner, measured from the
  mask's area. Other shapes stay for the settings screen.
- The tapped copy of an icon is remembered (App Library and dock/page), so the card returns to the copy it came from.
- Metrics redone from measurements of Apple's press images (`docs/IOS_DESIGN.md`): 64 pt icons, iOS grid and row pitch,
  labels with SF 11's cap height, dock 101 pt inset 17.5 concentric, App Library tiles, padding and search field.
- Glass from the same measurements: dock blur sigma about 6.5 pt (was about 4), saturation 1.7, clear capsule; App Library
  background saturated and barely darkened.
- Inter for all text (SF Pro is licensed for Apple platforms only).
- Swiping during a close: the real home now runs the depth spring itself (same spring, same start time) and the picture of
  home is dropped as soon as home has drawn, so home is live and interactive behind the flying card; touching home during the
  flight fades the card out where it is.
- Live app in the cards: the shell holds CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS (probe round 1), so the real app window can
  in principle be animated through a remote transition (what Pixel's launcher does). DEV → "Probe: live cards" tests it: opens
  Settings with its real window growing from a card, then goes home with the live window shrinking slowly (try swiping pages
  during it). The report lists what the system hands over and the frame pacing. If it works, launch and close move to live
  windows (no more grey cards or pictures of home).

Device results (a74caa2), Matheesha: "literally nothing was fixed … it made everything worse". Close animation flickers the app
full screen; App Library duplicate icon unchanged; first sideways swipe has no animation; cold launches still look bad; the
glass looks frosted, not liquid; the live-card probe showed no animation at all (app popped up and closed).
Causes found in the code (not yet confirmed on the phone):
- Flicker: a74caa2 dropped the picture of home as soon as home had *drawn*. Drawn is not on screen: the app was still showing
  for a few frames, and the card window's backdrop was gone. Reverted; the picture now goes only when home is touched (touches
  reach home only once it really is in front), so swiping during a close still works.
- Duplicate icon: likely home being rebuilt when a full-screen app hid the status bar (insets were read with visibility, the
  rebuild recreated the App Library and lost which copy was tapped). Insets are now read ignoring visibility, and every rebuild
  is logged (`[home] insets changed …` / `size changed`). The tapped copy is also remembered by its tile, not its position
  (position broke if the Suggestions tile re-sorted). Logs: `[library] open <pkg> from tile:<name> at x,y` and the close's
  `home (into the icon of <pkg> at x,y)`.
- First sideways swipe: on home, a sideways swipe used to switch with no animation at all (`returnToLastApp`); in an app, the
  cards waited for a fresh snapshot (~100 ms), so a quick flick ended before they appeared. Now: on home, home itself slides away
  as a card while the last app comes in; in an app, the system's cached snapshot shows at once and the switch always animates.
- Glass: misread measurement. Under the iOS dock a thin wallpaper line keeps its width and only loses contrast: that is tint,
  not blur. Back to clear glass: light blur (about 1.5 pt), stronger lensing rim, brighter thin rim, light sheen.
- Cold launch: the card now becomes the app's own launch screen (splash colour from the app's theme,
  windowSplashScreenBackground / windowBackground, with the icon at launch-screen size), so it hands over to the app's splash
  without a colour jump. Colours are resolved in advance for home's apps.
- Probe: the report is needed to tell whether the system never called us or our surface changes failed; it now also logs the
  options it sent and the leash it animates.

Device results (4c8eec4), Matheesha: launch and close animations better. Open: closing after a minute or more in an app is less
smooth; the flick's speed should not change the close's speed; some launches stutter (camera); App Library: opening a folder
also "expands" the search field, closing a folder jumps in opacity at the end, library glass inconsistent with the dock, an
unblurred strip at the right edge; wallpaper reveal: dock and indicator update only afterwards; inconsistent scaling of fonts
and elements. New standing rule: never break what works; double-check every touched path before finishing.
Causes and changes (next build, not yet confirmed on the phone):
- Unblurred strip: the small blurred wallpaper copies are rounded down in size (width / 16) but were mapped back by the nominal
  factor, leaving up to 15 source pixels uncovered at the right and bottom. Now mapped by their true ratio.
- Reveal: since glass is drawn by GlassView (not as a background), the drawable's invalidateSelf() reached nothing, so the glass
  repainted only when something else did. GlassView is now the drawable's callback.
- Folder: the search field was blurred with a RenderEffect, whose blur spreads past the shape ("expanding"). RenderEffect is gone;
  the library sinks under the same blurred wallpaper it sits on. The tile is now the folder: the source tile is not drawn while
  the folder is open, the panel uses the tile's glass and corner, and the tile's own icons fade into the folder grid (and back),
  so the closing panel is identical to the tile when it lands. Taps outside close it at any point of the animation.
- Library glass: tiles, search field and folder panel use the dock's liquid glass (`GlassStyle.IOS_LIBRARY`), refracting the
  blurred wallpaper behind the library (`GlassDrawable.Source.BACKDROP`); redrawn while the library slides.
- Scaling: the App Library used its own unit (width / 375) while home used width / 402, so library text and shapes were 7 %
  larger. One unit everywhere now; library tiles fill the width between iOS's 23.3 pt margins (their icons come out at the home
  icon's size).
- Close speed: a close keeps at most 650 px/s of the finger's motion towards the icon (none away from it), so fast and slow
  flicks look and last the same.
- Per-frame cost: the picture of home behind cards is rendered once into two GPU layers; depth only scales them (was a full
  re-render of the picture, glass shader included, every frame). Meant to help both the camera launch and longer sessions.
- After a minute in an app: home re-records its picture every minute (clock), which threw away the picture without the open
  app's icon; the close then had to record it on gesture start. Home now re-records that one immediately for the app in front.

Device results (c20b685), Matheesha: closes after a while in an app still stale ("probably the animation scale changes");
behind an expanded App Library folder the other apps stay faintly visible; glass still inconsistent with the dock; asked for
the whole theme to follow the iOS design language (references: iOS 26 App Library, glass widgets and dock).
Changes (next build, not yet confirmed on the phone):
- No more switching system animation scales around gestures. Every start our cards cover (launch, home on close, switches,
  bring-backs) goes through the shell with a remote transition of ours that ends at once (`InstantTransitions`,
  `NoAnimStarts`): the system plays no animation of its own and nothing global is written during a gesture. Basis: the
  live-card probe's starts showed no system animation at all. Verified on the fly (`[nav] own transitions: requests=… invoked=…`);
  the old scale switching stays as a safety net until the first confirmation, and comes back for good if the system never
  hands the transitions over.
- One glass material for everything (`GlassStyle.IOS`), designed offline against the references with the shader's own maths
  (`docs/design/glass_proto.py`, `docs/design/glass-material-v4.png`): frosted body (about 9 pt blur), light tint, soft light
  band inside the edge, thin rim lit from the top left. Dock, widgets, Search pill, App Library tiles, search field and folder
  panels all use it (library surfaces refract the library's blurred background).
- Behind an open folder the library is now fully covered by its own blurred background (was 94 %: the faint icons).
- iOS widget: the clock is a medium (4 x 2) glass widget with day, time and date, sized like iOS widgets, its name below.
- Search pill (iOS 26) replaces the page dots at rest; dots while pages move; a tap opens App Library search for now.

Device results (5202ed6), Matheesha: everything looks worse: a white tint on all glass and more blur; iOS glass "only has a
slight light rim around, consistent across light and dark modes"; c20b685 looked better. Closing after a while still not smooth.
Changes (next build, not yet confirmed on the phone):
- Glass back to clear (c20b685's lens and light blur) without any tint or inner glow, with only a slight rim, one style for
  every surface (`docs/IOS_DESIGN.md`). Lens narrower on small shapes so the Search pill is not all lens.
- Close after a while: with no recent image of the app (older than 10 s) the card waits for a fresh snapshot, measured at
  54–309 ms; meanwhile the app does not move, then the card popped in under the finger. Also, since 4c8eec4 a cached-snapshot
  call (itself up to ~240 ms) ran *before* the fresh one, adding to that wait. Now: the cached call runs on its own worker in
  parallel; a card that appears late starts exactly where the app is (full screen) and glides to the finger in 120 ms; a close
  released before its card could show waits for it (max 350 ms, home is not started meanwhile) and then plays from full size.
  Log: `card visible N ms after the start` and `home released before the card could show`.

Device results (a0b173e), Matheesha: "looks great"; glass a bit too saturated. Asked for iOS's blur during app open/close.
Close glitch: at the end the icon has a larger corner radius and no label for a moment, then the real icon with its label appears.
Changes (next build, not yet confirmed on the phone):
- Glass saturation 1.4 → 1.22.
- Close glitch, two causes: the card's clip at icon size used the system mask's *average* corner (from its area), which cuts the
  One UI squircle's corners rounder than the real icon; and the hidden home icon was hidden with alpha, label included. Now the
  icon-sized clip sits well inside the shape (0.6 of that corner: the rendered icon carries its exact mask), and hiding an icon
  leaves its label (`IconView.iconHidden`), as on iOS. The picture of home without the icon keeps the label too.
- Depth blur (iOS): home blurs as it recedes behind an opening app and sharpens as the app closes (`MotionProfile.homeDepthBlur`,
  14 pt at full depth). In the picture of home it is one blur of the cached layers' composite per frame; the real home blurs on
  the same spring only once it is uncovered (`HomeBridge.homeCovered`), so the work is never done twice.
- While a card is dragged home, home now comes forward with it (depth 1 → 0.5 as the card shrinks: less zoom, less blur), then
  the release springs on from there; a grabbed card keeps the depth home had when it was grabbed.
- Matheesha: a closing card must not be grabbable mid-close. Touches on the bar that start during a close (flying, settling, or
  waiting for a late card) are now ignored until the finger lifts. Still possible during a close: tapping the app's icon on
  home (reopens it) and touching home (moves the card aside). Launching cards and cancelled closes can still be grabbed.

New features, round 1 of 3 (design: `docs/HOME_FEATURES.md`), next build, not yet confirmed on the phone:
- App Library pulled past its end keeps its full blur (the rubber band counted as "partly closed" and faded the background).
- Shared `SearchList` (App Library list and Spotlight): glass palette only (white letters, a glass bubble while scrubbing the
  index; the accent blue is gone); filtering animates (rows glide to new places, new rows fade/rise in, leaving rows fade out).
- Spotlight: pull down on any home page (below the status bar) opens it with the finger; suggestions at the top, results once
  typing, glass search field at the bottom riding up with the keyboard frame by frame; tap a result to launch from its icon;
  tap empty space / swipe up / back to close. The Search pill opens it.
- iOS status bar (`StatusBarView`, metrics in `docs/HOME_FEATURES.md`): time, cellular bars, Wi-Fi or network type, battery;
  an overlay above strip and cards, hidden for immersive apps and on the lock screen. The stock clock and icons are hidden
  only while ours is on screen. White/black content: home from the wallpaper's top band; apps from the window manager
  (`dumpsys window displays`, appearance lines; the first three readings are logged as `[statusbar] appearance …` because the
  exact format on One UI is not known yet). On by default with gesture nav (dev panel: "Status bar: …").
  Note: "Test: break system" now hides the stock bar only while our own is shown.
