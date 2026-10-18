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

Device results (42699ab), Matheesha: stock status bar gone but ours not visible; Spotlight not like iOS (reference: Top Hit in a
highlighted glass card, section titles with dividers, a glass search field that blurs what is behind it).
Fixes (next build, not yet confirmed on the phone):
- Status bar: it hid itself from its own window's insets; an overlay above the status bar is apparently never told the bar is
  visible, so it stayed hidden. Now immersive apps are detected through the shell (the status bar insets source in
  `dumpsys window displays`), and the own-insets reading is only logged once.
- Spotlight: results show the best match as "Top Hit" in a glass card (home-sized icon, name, category), the others under
  "Apps", titles with dividers; the search field is frosted glass that blurs the results scrolling under it and the background
  (a blurred mirror of what is behind, darkened, slight rim) and has a clear button.

New features, round 2 of 3: edit mode (design and what differs: `docs/HOME_FEATURES.md` §2), next build, not yet confirmed on
the phone:
- Long-press an icon: iOS context menu (shortcuts, Edit Home Screen, Remove from Home Screen, App Info) over blurred home; keep
  holding and move to drag it straight away. Long-press empty space: edit mode.
- Edit mode: icons and widgets wiggle with "–" badges; drag to reorder (the page flows around on glides), into or out of the
  dock (up to its slots), across pages (rest at an edge; a new page at the end); "+" adds the clock widget; "Done" / tap on
  empty space / back / home / swipe up on the bar leaves. Every change is saved at once.
- While editing, the Search pill shows the page dots and does not open Spotlight; pulling down does not open Spotlight either.


Device results (d286fe3), Matheesha: holding an icon blurred only home's elements, not the wallpaper (the blurred dock and
Search pill showed hard rectangular edges); the menu looked like the old frosted iOS menu; the edit-mode buttons had no glass.
Asked for: adding widgets, adding apps from the App Library to home, an animation check against iOS, the iOS 27 look, and a
lock-screen style glass clock widget.

Round 3 (next build, not yet confirmed on the phone):
- Long-press blur: home is now one "scene" (wallpaper + content) that blurs as a whole behind menus and the widget gallery;
  the menu, the gallery and a dragged item's copy live above it (never blurred, never in the picture of home).
- Menu glass: the dock's glass over what is really behind it (`LiveGlass`), blurred and dimmed like home around it. Menus can
  also belong to a button (the edit bar's "Edit").
- iOS 27 glass (research and values: `docs/IOS_DESIGN.md`): darkened edge, brighter corner highlights.
- Edit bar: "Edit" (menu: Add Widget) and "Done" as glass capsules (iOS 26/27 layout).
- Widgets: real Android widgets. Gallery sheet (Edit > Add Widget): ours (the clock) first, then every app with widgets; an
  app's page swipes through its widgets at each iOS size with page dots and "Add Widget". New widgets go to the top of the
  current page. Binding: Shizuku grants us the bind permission once (`appwidget grantbind`), else Android asks; a widget's
  setup screen runs if it has one. Widgets keep working (buttons, lists); a long press anywhere on them gives home's menu.
- Clock widget: the lock screen's glass clock (`docs/IOS_DESIGN.md`).
- App Library and Spotlight: long press an app for its menu (shortcuts, "Add to Home Screen" if it is not on home, App Info);
  keep holding and move to drag it out: the library or Spotlight gets out of the way and edit mode takes the app.
- Motion: every edit-mode animation and the menu come from the motion profile; reflow and drag lift are interruptible springs.
- Wiggle pauses while home is blurred behind a menu or the gallery (it would re-blur all of home every frame).

Device results (3390d7b), Matheesha: pop-up menus broken (dark ring, orange fringe); clock stretched and unlike the other
glass; icons cannot be placed freely; no widget options, no widget resizing; status bar missing/odd. Asked for an audit of
every element and for ways to raise productivity and accuracy.

Round 4 (next build; everything below checked on the emulator with screenshots unless marked):
- Productivity: an emulator (Android 17, GPU, Shizuku started over adb) and scripts (`tools/`) let Claude install, drive and
  screenshot every change before pushing; `PreviewActivity` shows components on their own; unit tests for the layout grid.
- Menus: the glass's input was cut to the fading layer it was drawn in, so the lens saw nothing past the panel (dark ring).
  The glass now draws outside that layer and its samples stay inside what was recorded. Darkened slightly for readable white
  text; wider for long labels.
- Clock: the font's own proportions (slightly narrowed, not stretched), glass matching the dock's (bright edges, light body),
  date "Sat 3"; Glass/Solid style; Small/Medium/Large.
- Free placement (iOS 18+): items keep their cell; empty cells stay empty; moving within a run reorders, otherwise icons in
  the way move on until a gap; widgets never move aside (`home/Grid.kt`, `GridTest`). Old layouts keep their order.
- Widgets: long-press menu with a sizes row, Edit Widget (setup screen again) for Android widgets, Glass/Solid for the clock;
  edit mode shows iOS 27's corner handle, drag it to resize. A new widget that cannot go on the current page shows its page.
- Status bar: centred on the camera line; percentage in the battery, bolt + green when charging, yellow Low Power, red low;
  airplane, No SIM / No Service, Focus moon, VPN. Logs "[statusbar] hidden/shown". **Phone**: why it did not show on the S24.
- Badges: iOS red counts on home and dock icons (notification access granted through Shizuku).
- Menus: Delete App (Android's uninstall dialog; not for system apps).
- Audit of every element and the plan: `docs/AUDIT.md`.

Round 5: smoothness measured on the S24 itself (wireless adb from the dev PC; frame logs, gfxinfo, Perfetto traces).
Before → after (dropped frames per animation, worst frame):
- Close after a minute in an app: ~4-10 dropped, card 58-125 ms late → 0-1 dropped, card visible after 1 ms.
- Close into a home icon (Telegram), right away and after 8 s: 0-3 → 0 (worst 8.7 ms at 120 Hz).
- Launch: 0-2 → 0. Sideways switch: 1-3 → 0.
- Close of an app without a home icon (fades to the centre): 5-8 at 16.7 ms each → 1-4. Still open: the GPU needs ~8 ms
  per frame there (the full-screen depth blur behind a fading card), right at the 8.3 ms budget.
Causes found in the traces and what changed (GestureNav, CardView, Wallpaper):
- A picture of the app in front was reused only if under 10 s old, so after a while every close waited for a fresh capture
  (70-130 ms then): one is now kept in the background (every 6 s while an app is in front, ~50-130 ms of shell time each,
  off our threads) and imported into the GPU while idle (a 1-pixel view), so the card shows it at once.
- Showing/hiding the card window made the window manager re-lay it out (50-80 ms, buffers rebuilt): the window now stays
  added; cards show inside it; only its window alpha changes (6-50 ms), and only when home is in front and idle, never at
  the start of a gesture (made ready at the first touch on home or on the bar, kept ready while an app is in front).
- The picture-of-home layers were destroyed whenever the cards hid (a view leaving the drawing tree loses its GPU layer)
  and re-allocated/re-rendered at every gesture: they now stay in the tree at alpha 0, and home's picture for the app in
  front is rendered into them ahead of time.
- A fresh snapshot arriving mid-animation replaced a recent one (another GPU import, a dropped frame): no longer.
- A fading card was drawn through a full-screen offscreen layer every frame: no longer (single image, no overlap).
- The wallpaper and its blurred copies are hardware bitmaps (never re-uploaded after home was in the background).
- The clock's glass adapts to a light wallpaper (darker body and edge, deeper shadow) so it stays readable.


Round 6: bugs Matheesha reported on c00a9f4, each reproduced and checked on the S24 with screen recordings
(`tools/device.sh rec` / `recpull`: every composed frame at 120 fps, as contact sheets).
- A flicker at the end of every launch: the picture of home under the card faded out together with the card, so it showed
  through the half-transparent card (a grey wash over ~10 frames). A launch now hides the picture at once when its card
  starts fading (the card covers it entirely); only a close interrupted on home still fades both.
- Back home from an app opened in Spotlight, the close showed Spotlight (then home snapped back without it); from App Library
  search, the keyboard popped up after the close. The pictures a close shows were recorded at the launch, with the search open.
  Now Spotlight and App Library search end when home goes behind the app (out of sight, so nothing can show early; as on
  iOS, you come back to home or to the library without the search), and the pictures are recorded again right then.
  Checked: Spotlight → Calculator → home and library search → Calculator → library close onto the right screen, no keyboard.
- A swipe up did nothing in App Library search. With nothing on top (menu, gallery, edit mode, Spotlight), a swipe up on home
  now does what the Home button does: back to the first page, keyboard down, library and search closed.
- Keyboard windows were taken for the app in front (seen in the log after App Library search); they are ignored now.
- Library-search launches after the change: 5 runs, 0 dropped frames; their closes 0-2.
- Not fixed: at the start of a launch from a search, the keyboard disappears at once (our picture of home cannot contain it).

Round 7: smoothness measured from what the screen showed (present times in `dumpsys gfxinfo framestats`, `tools/framestats.py`;
scenario scripts in `tools/`). Our own frame log had been reporting 0 dropped for closes that the screen showed 1-2 refreshes
late. Missed refreshes per animation on the S24, previous build (54a326f) → this build, same scripts, same session:
- Launch from an App Library tile: 3, 3, 3 → 1, 1, 2 (card GPU per frame: median ~11 ms → 2.5-5 ms).
- Close into its icon: 2, 2, 2 → 1, 1, 1 (one run of the round before: 0, 0, 1).
- Close to the centre (app opened from Spotlight): 0-13 (two runs with 80 ms stalls) → 0, 0, 0, 1.
- Sideways switch: 0, 3, 4, 8, 5 → 2, 1, 2, 5 / 0, 2, 1, 1.
Causes found (Perfetto with scheduler data) and changes:
- Every app snapshot arrived as a 10 MB *software* bitmap: Binder reads a hardware Bitmap back when it is sent. Its first draw
  uploaded it to the GPU (5.3 ms in a gesture's second frame) and scanned it on the drawing thread; each one also counted as
  10 MB of native memory, and the runtime collected garbage mid-gesture ("NativeAlloc ... GC paused 5.3ms"). The shell now
  sends the graphics buffer (`taskSnapshotBuffer`), wrapped into a hardware bitmap in the app (log: "kept a recent picture
  ... HARDWARE"); capture time 27-80 → 23 ms.
- A gesture fetched three snapshots at its start: a fresh one of the app in front (which the card did not even use, see
  next), the system's copy of it, and the previous app's again. Now only missing ones: none when a recent picture is kept
  and the previous app's was fetched after it left the front.
- The card meant to keep its recent picture instead of swapping in the fresh one mid-motion, but the check ran after the
  fresh picture had replaced the kept one, so it always swapped. Fixed.
- Home's picture behind the cards was blurred at full size every frame (GPU ~4-8 ms at 120 Hz) with a full-size offscreen
  allocated at each gesture start. It is now blurred from a quarter-size copy (1/16 of the pixels) kept in its own small
  layer, faded over the sharp picture for the first few pixels of blur. Checked on screen recordings: same look.
- Home itself redrew everything at every step of its zoom during a close, under our picture (GPU ~2.6 ms per frame): it is
  drawn from a GPU layer while the zoom runs (~1.45 ms).
- The wallpaper's picture was re-recorded at every tap (new picture → its layers re-rendered at every launch): reused until
  the wallpaper changes. A new picture is rendered into the layers that show first and into the others a frame later.
- Launch "wrong app" check: a window of another package in the tapped app's own task (Settings showing Samsung's wallpaper
  picker) no longer counts as the wrong app (it started Settings again).
Found but not fixable from the app: at a gesture's start our render thread runs on a small core at ~1.1 GHz for the first
~100 ms (the touch boost goes to the app in front); the public performance-hint API cannot ask for a boost. Remaining late
frames are mostly at the start of a gesture or of an app's own start (GPU busy with the app).

Round 8: the App Switcher (hold during a home swipe), design and behaviour in HOME_FEATURES.md §3. Checked on the emulator with
screenshots (layout, scroll direction, flick to close: "closed ... (task N): ok", tap to open, tap empty / swipe up from the
bottom to go home) and on the S24 by frame stats only (`tools/scenario_switcher.sh`; no screenshots there, the deck shows the
snapshots of other apps, messaging included). Missed refreshes on the S24, three runs: opening the deck 0/0/0 (worst frame
16.7 ms), opening an app from it 1-2/1/0 (while the app starts), going home 0/0/0.
Found and fixed while measuring:
- The hold timer only re-armed on fast finger motion, and the velocity estimate reads 0 between sparse touch events: an early
  check (card still large) failed and never came back. The hold is now "within 12 dp for 150 ms".
- Swapping the picture of home behind the deck on its first frame re-rendered home's layers (4.4 ms): the swipe's picture
  stays; the full one (every icon) only when home is chosen, when only the small blurred copy is visible.
- Removing the input window while home came to the front stalled the compositor ~90 ms: it now goes after the animation.
- Every card was drawn whole though older ones are mostly covered: now only the visible strip.
- The card shadow was rebuilt (CPU blur + GPU copy) each time the deck opened: a 175 ms first frame. Built once per size.
- A swipe that started before home had ever been recorded showed the app through the dimmed deck background: a picture is
  taken when the deck opens, else a plain dark ground.
- framestats.py counted gaps where we drew nothing (sparse injected touches, pauses between animations) as missed refreshes;
  a miss now needs frames drawn for consecutive refreshes. Earlier rounds' numbers re-checked: unchanged.
Round 8b (Matheesha: "can't use the recents menu from home", "the trigger is too far from the gesture bar"): the switcher now
also opens from the home screen (a swipe up that rests; the most recent app focused, its card rising from below), and the
hold counts once the finger is 56 dp above where it started instead of "card below 72 %" (~150 dp). Checked on the emulator
with screenshots: from home (short swipe, Contacts focused), tap to open, short hold inside an app, tap empty space = home,
plain swipe up on home = no switcher. S24 frame stats (two runs, missed refreshes): short hold in an app 1/0, opening an app
from it 0/1, home from it 0/0; short hold on home 0/0, opening an app from that deck 0/0.

Round 9 (Matheesha: the deck from home moved two ways; previews out of date, "especially the most recent one"):
- From home every card rises the same way (cascade, newest first). Checked on the emulator (recording).
- Out-of-date previews had three causes: (1) going from an app to home never marked it as having left the front, so its
  old picture counted as current and the system's newer snapshot was never fetched; (2) our kept picture refreshed only
  every 6 s; (3) a change just before the swipe. Now: content-change events refresh the kept picture once the screen settles;
  a stale picture makes the gesture take a fresh one at the touch (from the known task, ~10-20 ms, replacing the shown one);
  the open deck re-takes a card whose app changed. Verified on the S24 through the log ("[switcher] open ... N s old, OUT OF
  DATE" / "... its card was taken again"): home deck after leaving Calculator 0.4 s old and current; deck 2 s after typing
  1.9 s old and current; typing then swiping at once: out of date at open, re-taken moments later. Recorded (Calculator):
  typed "9", closed at once: the card shows "9" from its first frame.
- First attempt waited for the fresh picture before showing the card: card 80 ms late (fluidity rule): reverted to "show at
  once, replace when it arrives"; with the capture starting at the touch the card shows in 0-2 ms.
- S24 frame stats: switcher in/open/home/from home/open from home 0/0/0/0/1; type-and-close 0/0/2; launches 0,1,0 / 1,0,0;
  closes 2,1,4 / 0,2,0 (the recurring flagged frame #2 has an intended time before the gesture: the card window's last
  frame of the previous session, not the swipe).

Round 10 (Matheesha: "I don't like the swipe from home to recents animation", replicate iOS): home recedes with the finger
during a swipe up on it; a rest slides the deck in from the left as one; going home from a deck slides it out to the left
while home comes forward; a released swipe springs home back. Checked on the emulator (recording) and on the S24 by frame
stats: switcher from an app in/open/home 1/1/0, from home in/open 0/0, released swipes on home 0/0/0 missed refreshes.

Round 11 (Matheesha: the switcher from the App Library "is weird at times, especially from an expanded folder"; going home
from an expanded folder). Found and fixed, checked on the emulator (the S24 was not connected):
- Home's picture behind the pull and the deck was the one recorded when home last came to rest, so it could be seconds old
  (a folder opened since, the library scrolled). Now home is recorded as it shows at the touch (`HomeBridge.recordForGesture`).
- A released swipe up on an open folder closes the folder at once (as iOS closes an expanded category); the receding picture
  fades into the live home doing it (it used to spring back first, then the folder popped back and slid away with the page).
  A second swipe goes to page 1.
- **Black home after a close (real bug, likely behind the "at times").** Our instant transition applied the system's start
  transaction from the shell process and called the finish callback straight away. SystemUI applies the finish transaction
  (windows back to their parents, the transition's container removed) from its own process, and transactions from two
  processes are not ordered: when ours waited on a frame not yet drawn (home redrawing behind the blurred folder), theirs
  landed first and ours then moved home into the removed container. Home stayed off screen (SurfaceFlinger: the home task
  under an offscreen "Transition Root"; input: "no focused window" ANR) until another transition, even across a reinstall.
  Now the finish is called when our transaction is committed (`addTransactionCommittedListener`, 1 s fallback). Emulator:
  the old timing went black on the first folder close; the fix 10/10 clean (folder icon and switcher card alternating),
  commit 8-31 ms. This was also the cause of the earlier "black home on the emulator" (not the task type).
- Checked on the emulator: switcher from the open folder (tap empty space or swipe up: back to the open folder), from the
  scrolled library (pixel-identical before/after), card taps, closes, sideways switch. The emulator returns no task
  snapshots, so its cards show placeholders. Not measured yet: frame stats on the S24.

Round 12 (Matheesha: a card swiped up clips with the card in front of it; only a limited number of cards, "should there be
all the apps that are open?"):
- A lifted card is drawn above its neighbours, and a card dropped back settles under the newer card with a fade (no jump).
- The switcher used the gesture's quick lookup of 8 recent tasks: at most ~7 apps. Now the full list (up to 50) is fetched
  after the quick one and every app gets a card; pictures load as cards come into view (full size near the focus, the
  system's reduced copy for the stacked slivers, let go far off screen). New shell call `taskSnapshotBufferLow` (code 24).
- Emulator, 20 recent apps: all 20 in the deck (full list 7-14 ms), lift / drop back / flick checked on screenshots and a
  recording, scrolling to the oldest apps asked for pictures as cards came into view. Not measured yet on the S24: frame
  stats while scrolling a long deck, and the full list's time ("full list in N ms" in the switcher log).

Round 13 (Matheesha: a swiped-up card should stay behind the cards in front of it; deep in the deck, tapping outside made the
cards slide, get stuck and vanish):
- Round 12's "lifted card on top" reverted: a lifted or flying card keeps its place in the stack (drawn between its older
  and newer neighbours until it is gone).
- Going home slid every card by one fixed distance, right only when the newest card is focused. Scrolled to older apps, the
  newer cards waiting off screen to the right slid in across the screen and stopped mid-screen until home was reached.
  Now the distance is what clears the cards on screen, and cards off screen at that moment are not drawn. Emulator
  recordings: deep in the deck (scroll 9), from home and from an app, by tap and by bar swipe.
- Seen on the emulator only, right after a reinstall: the first switcher use had no picture of home yet, and choosing home
  rendered it then (a ~230 ms stall). Not seen otherwise; to watch on the phone after an UPDATE.

Round 14: smoothness audit of everything built so far (S24, phase 2 gate). New `tools/scenario_more.sh` covers what the other
scenarios did not (page <-> library, library scroll, A-Z list, folder open/close/launch/close, Spotlight open/close, the pull on
home, dock launch/close, a launch grabbed midway, a cancelled close, sideways from home, a long switcher deck, a flick);
`tools/trace_slices.py` lists long slices in a Perfetto trace with touch markers. Found and fixed:
- `framestats.py` counted frames whose present time was not filled in yet (seconds before their own vsync) as misses: a
  switcher flick read 14-19 missed refreshes that were never on screen. Such frames are dropped now (earlier numbers in this
  file may include a few of these).
- The card window went to window alpha 0 after 1.5 s idle on home and back to 1 at the next touch: a 10-15 ms window manager
  re-layout on the nav thread at the start of the folder-close, the pull and launches. It stays at 1 now (it always did while
  an app is in front).
- Every bar touch on home recorded a new picture of home (since round 11), so the card window re-rendered its layers of home
  at the start of the pull (11-15 ms GPU frames). Home's picture is reused unless home drew since it was recorded, and a
  new one is rendered into the layers while idle.
- Home recorded its picture (~20 ms of main thread) inside the last frame of a settling animation and at the minute tick:
  now 120 ms later, when nothing moves.
- Icons are uploaded to the GPU as they are made (`prepareToDraw`): a folder's first frame uploaded 6 icons with the main
  thread waiting, Spotlight's opening 50.
- Glass shader: the wallpaper-change front (two noise functions per pixel) is skipped when no change runs, and the library's
  glass samples its backdrop once instead of twice (its sharp and frosted images are the same). Folder open GPU 15.9 -> ~8 ms
  per frame; library tiles pixel-identical (compared on screenshots).
- A folder keeps the library behind it in a GPU layer while it animates, and does not draw it once covered.
- Apps flicked away in the switcher are closed once the deck is still (not the cause of the flick "misses" after all, but
  closing a task mid-animation is work the screen does not need then).
Results after (missed refreshes per step, two runs): every step 0-1 (folder open 0/0 in the quick re-test, flick 0, switcher
0/0/0/0/0, launches/closes 0-2, sideways 0-1). GPU times vary with the phone's temperature (SoC 52 C after an hour of runs).
Seen on recordings and not fixed yet: our own screens (the dev panel; a future settings page) open with the stock slide, not
our card; Spotlight's keyboard rises after the search has opened (two steps), not with it.

Round 15 (Matheesha: swiping home from an expanded App Library folder crashed the app; swiping home right after an app
opened from Spotlight was wrong; touches did nothing during closes from the App Library):
- Crash (round 14's own regression): the library behind an opening folder had a GPU layer; recording home into a Picture
  drew that view in software, where the glass shader cannot run ("Software rendering doesn't support RuntimeShader").
  The layer is gone (the shader fix was the real saving), and a failed recording now keeps the last picture instead of
  crashing. S24: open folder + two quick swipes home x3, no crash.
- Spotlight: a swipe that took over the launch card closed onto the picture recorded at the tap (Spotlight open), though
  the search had ended behind the app; home then snapped to the first page. A full-size launch card now takes home's
  current picture. Recorded on the S24: the close shows page 1 from its first frame.
- Touches during a close: for ~80 ms after the release home is not the window in front yet (traced: start, resume, first
  frame, transition committed at +77 ms), and a gesture begun then belonged to the closing app for its whole length (a
  drag on the library did nothing; a tap could press something in the hidden app). The card window now takes touches while
  a close runs and hands them to home in-process; its touchable region (AttachedSurfaceControl.setTouchableRegion, no
  re-layout) is "nowhere" otherwise, with a failsafe that makes it untouchable if it ever takes a touch outside a close.
  S24: a drag right after a close scrolls the library (it did nothing before); in-app taps, library scrolls, launches and
  closes unaffected; no failsafe line in any run. Launches unchanged: touches during an opening animation go to the app.

Round 16 (Matheesha: six things; none of this is confirmed on a phone yet, every change was type-checked against the
Android framework classes and the clock's shader was rendered offline, the sandbox has no emulator):
1. **A-Z index.** Scrubbing the index was a jump (`scroller.jumpTo`) with a static bubble. Now: the list springs to each
   letter's section carrying its motion from the last one (`IosScroller.animateTo(target, spec)`, new `velocity`), the
   column lights up under the finger (a capsule behind it, the letters near the finger magnified like a fisheye), the
   glass bubble pops in with a little overshoot, glides along with the finger on its own spring and pops away on release.
   Roles in the motion profile: `indexScroll`, `indexBubbleIn/Out`, `indexFollow`.
2. **The App Library never makes you wait.** `FolderOverlay` is now a set of panels: a closing folder takes no touches, so
   the tile under it (or any other) can be tapped at once: another folder grows while the first still shrinks back, and the
   same tile reopens its folder from wherever it is, keeping its speed; an icon can be opened while its folder is still
   growing (hit-testing goes through the panel's current transform). The pane fading out behind "Cancel" no longer eats
   taps (`acceptsTouches`); a touch on the last, invisible part of a page snap or scroll settle is an ordinary tap instead
   of a grab (`IosScroller.isMovingVisibly`, HomeScreen's intercept).
3. **Arrival and state changes.** Home arrives animated after unlock (waits for USER_PRESENT when there is a lock screen)
   and on every cold start (boot, an UPDATE, a crash): icons, widgets, dock and Search pill bloom from 0.8 and fade in,
   staggered outward from the centre, the wallpaper settles from a 6 % zoom (a cold start first brings the wallpaper up from
   black, once it is read or after 500 ms). Nothing waits for it. Also animated now: the edit bar slides in and out; new
   icons/widgets grow into their cells and removed ones shrink away (installs, uninstalls, Add Widget, Remove); the clock's
   minute change crossfades the numerals (two glass layers take turns); a wallpaper read after home is up fades in.
   Home's picture for gesture nav is never recorded mid-animation (`isIdle` covers all of these).
4. **Clock.** `GlassStyle.IOS_CLOCK` was a lit, white-tinted variant (tint 0.12, inner glow 0.22, a bright rim all round):
   the "weird white highlights". It is now the dock's own material shaped like the digits (no tint, no glow, the darkened
   edge and corner-gathered highlights of `IOS`), with a deeper lens (refraction 26, a 7 %-of-size bevel instead of 3 %)
   so the strokes read as thick glass, and a slight thickness shade. `docs/design/clock_proto.py` renders old and new
   side by side (`clock-glass-old-vs-new.png`); a numpy port of the mask shader, not the GPU: to be judged on the S24.
5. **Widgets.** Resizing is animated: the item changes in place (`HomeItem.Widget` spans are mutable; `WidgetFrameView`),
   the card springs to the new size while the old look crossfades into the new content laid out at its final size
   (nothing stretches), from the handle and from the Size row. Options: Hide/Show Widget Names and Hide/Show App Names
   (iOS 18's large icons; both saved in `HomeConfig`, names fade; in the widget menu and the Edit menu), and a "Glass
   Background" platter for Android widgets that come without one (`style = "glass"`). The gallery (`WidgetPicker`) was
   rebuilt: grabber, title, close button, a working search field, the clock featured with its real glass numerals, app
   rows with icon/name/"N widgets"/chevron settling in one after another, the app page's carousel with smaller dimmer
   neighbours and shadows, previews fading in, animated presses on rows and buttons, size under the title.
6. **Floating windows.** Picture-in-picture and pop-up (freeform) windows sit above every app but under our overlay, so
   every launch, close, switch and the App Switcher hid them for their whole length. The card window now leaves holes
   where they are (`CardHost.holes`, rounded; the window shows through, live) and never takes their touches during a
   close. Their bounds come from the shell (`floatingWindows`, code 25: `IActivityTaskManager.getTasks`, windowing modes
   pinned/freeform), looked up every 1.5 s while idle, at every card session's start and every 300 ms while cards show.
   The log line `[nav] floating windows: …` says what was found. Needs the new service build (UPDATE restarts it).
To check on the S24: the arrival after unlock and after UPDATE; the clock over the real wallpaper (light and dark); a PiP
video during a launch/close and in the switcher; resizing a widget from the handle; the gallery's search field with the
keyboard; `tools/scenario_more.sh` for the library's folder and index numbers.

Round 17 (Matheesha on the branch build ee24807; fixed from the code, no phone reachable from this session):
- Clock barely visible: the clear body had too little presence over a real wallpaper. The numerals are now thick frosted
  glass: a new `GlassDrawable.Source.FROSTED` sees the sharp wallpaper at the rim and the App Library's heavy blur as the
  body (frost 0.82, a touch of tint, deeper lens 30, stronger thickness shade, darker edge 0.32, deeper shadow). The dock's
  edge and highlights stay; no inner glow. `docs/design/clock-glass-old-vs-new.png` re-rendered.
- Add Widget: pushing an app's page left the search field standing still while the list slid away (the field is a real
  text view over the drawn capsule and was only placed on sheet moves). It now slides and fades with the list.
- A-Z index: the bubble springed to each letter's centre, which read as lag and as choppiness when letters changed
  quickly. The bubble and the magnified letters now sit exactly under the finger on every event; only the pop in and out
  are springs, and the list still springs to the section.
- Icon missing at the end of a close: two causes. The hidden icon was unhidden by package through the published map, so
  when that map had changed meanwhile (a page turned, the dock copy chosen) the hidden view stayed hidden until something
  rebound it; home now unhides the very view it hid. And the card was taken away 32 ms after asking home to show the icon,
  a fixed delay that lost when home's draw came late: `HomeBridge.showIconThen` takes the card away only once home has
  drawn a frame with the icon (150 ms fallback).
- Badges: the card never drew the app's badge, so the count popped in with the real icon. The card now draws the badge on
  the icon as it turns into it (`CardView.badge`), and `IconView` animates every badge change (a new one pops in, a
  cleared one shrinks away, a changed count bounces) so a count arriving while home shows never just appears.
- Rule 3 added to CLAUDE.md and REQUIREMENTS.md: attention to detail, every change on screen animated.

Round 18 (Matheesha on 23f0999; fixed from the code, offline render for the clock):
- Clock, researched against Apple's own description of Liquid Glass and the lock screen time (WWDC25 "Meet Liquid Glass":
  lensing defines the material; it concentrates light; its tint is a range of tones mapped to the brightness behind;
  highlights respond to a light that moves on unlock; large elements have deeper shadows and more pronounced lensing;
  the iOS 26 "Glass" clock has a clear-to-frosted slider, default in between, and glints that travel on tilt): the
  numerals are now semi-frosted (sharp wallpaper and its heavy blur mixed 40/60), lifted towards white over dark
  wallpaper and darkened over light ones, with a deep lens along the strokes, a slight light concentration just inside
  the edge, thickness shade, the dock's edge and brighter corner highlights, a wider bevel and a soft shadow; the digits
  are narrowed less (0.95). The light sweeps around every glass (numerals, dock, pill) as home arrives
  (`GlassDrawable.setLightAngle`), as Apple's material does on unlock. `docs/design/clock-glass-old-vs-new.png`.
- Add Widget push: the pages are glass, not opaque, so the list kept 30 % and vanished the moment the push ended. The list
  now fades fully out under the incoming page and the page's content fades in.
- Edit mode: the Edit/Done bar now slides away with the pages (it was fixed on screen over the App Library), and reaching
  the library ends edit mode, as on iOS.
- Unlock: the wallpaper no longer zooms on unlock (the system had already shown it at rest on the lock screen, so the zoom
  played twice) and the bloom starts with motion on its first frame. Under the lock screen home takes the arrival's first
  frame at the resume (`holdArrival`), so what the unlock reveals is already it, and it plays at USER_PRESENT (1.5 s
  fallback). A cold start keeps its zoom from black.
- App Library rubber band past its end: the tiles slid but their glass kept showing the old spot (the open progress
  stayed at 1, so nothing redrew). The library's glass now follows its own translation.
- Glass consistency: everything over the heavily blurred backdrop (library tiles, search field, folders, Spotlight's Top
  Hit card) uses `GlassStyle.IOS_LIBRARY`, the dock's light with a wider, stronger lens so the bend shows in a blur; the
  A-Z index's capsule and bubble, and the widget gallery's search capsule, buttons, featured card and widget cards are
  that glass now instead of flat fills; Spotlight's search field is the dock's lensed glass over what is behind it
  (`LiveGlass`) instead of a plain blur.

Round 19 (Matheesha on 511d8e6: "the widget looks like absolute trash"; Spotlight's search bar has glitched edges).
- Clock, root cause: the numerals' bevel came from a blurred copy of the text (a height field). A digit's stem is 50 px
  wide on the S24 (Inter 640 at 377 px), and the last rounds blurred by up to ~34 px to deepen the lens, so the whole
  stroke became "edge": the rim highlight and edge darkening covered every stroke end to end. The offline renders that
  looked fine used another font at another scale, so they hid it. Now the numerals are built like the dock: an exact
  distance transform of the text (`Edt`, Felzenszwalb; `EdtTest` checks it against brute force) gives the distance to
  the edge, and the shader uses the dock's own lens law and light on it (bevel = 0.9 x the strokes' half width, 19 px;
  bend scaled as the dock's), a thin rim, a frosted body (sharp wallpaper and its heavy blur half and half) lifted
  towards white over dark wallpaper and darkened over light, and a soft shadow 2 pt below. The shapes are built off the
  main thread (tens of ms) and fade in; the minute change crossfades once the next shape is ready.
  `docs/design/clock_proto.py` is now a faithful port (same steps, Inter, S24 size) and `clock-glass.png` its render.
- Spotlight's field, root cause: round 18 drew it with `LiveGlass` inside a view clipped to its rounded outline; the
  clip cuts the effect's input, the same "dark ring" bug the menus had in round 4. It is now the App Library field's
  glass (`GlassDrawable`, backdrop source), so both search fields are one design; results no longer show through it.
- `IOS_LIBRARY` lens toned down slightly (refraction 60, dispersion 0.35) against colour fringes on small capsules.


Round 20 (full audit on the S24 of the branch build b38770e, then fixes; branch merged into main). Measured and recorded on
the S24 (screen recordings frame by frame, `tools/scenario_more.sh` frame stats), local builds installed over adb:
- Launches: the tapped icon blinked out one frame before the card drew it (it is now hidden once the card window has drawn);
  a card started with the icon's average colour and switched to the app's launch-screen colour in one frame (every app's
  colour is resolved in the background now, a late one blends over 180 ms); a quick double tap on a library folder opened
  the app under the finger in the growing folder (the second tap on the tile is ignored).
- Swipe home from the A-Z list or a folder showed two of them (the picture fading in place over the live library sliding
  away): the live home now takes over the picture's depth (same spring) and the picture goes once home has drawn.
- Spotlight: the search field jumped to its final height for a frame before riding up with the keyboard (the final IME
  inset arrives before the animation; only its steps are applied now); the keyboard is asked for early in the pull; the
  results crossfade reverses from where it is; the clear button pops in and out.
- Widget gallery: home's blur went blurred-sharp-blurred when opened from a menu (the menu's blur is held until the sheet's
  takes over, and the sheet's glass blurs as much); the list took 2+ s to fill ("Looking for widgets…"): built in the
  background and kept (343 ms, ahead of time); the clock preview shows today and now (not "Mon 9", 9:41); search results
  glide and fade instead of popping; two packages of one app (Samsung Calendar) are one row.
- Edit mode: "–" badges, resize handles and Edit/Done presses animate (spring); the frameless clock gets a faint outline in
  edit mode so its controls sit on something; dock icons leave and arrive like page items (shrink away, grow in); "Widget
  Names" only where a widget has a name. Badge count changes push from their size (no jump to 130 %).
- Readability over light wallpapers: Edit/Done capsules slightly tinted; App Library field hint, magnifier and "Cancel",
  A-Z letters and section headers, folder labels and title, tile labels, Spotlight texts all with a soft shadow and
  stronger white.
- Own screens: the dev panel and safe settings open and close with our card (they used the stock slide; closing the dev
  panel flew the previous app's card to the centre). Real icons for Launcher, Launcher Dev and safe settings (loaded from our
  resources: One UI's icon theme replaced them with the Android placeholder).
- Logic: the clock's "building" could stick on a failed build (home never idle, stale picture); a menu shown while the
  last one closed left that item invisible; the unlock arrival could play on a later plain return home (only when home
  itself went to sleep in front now); the library field's Cancel animation laid out every frame (once now); a cold start
  showed the empty App Library for ~1.5 s until the app list arrived (home shows its wallpaper and dock meanwhile); our
  status bar fades for full-screen apps; glass knows its on-screen scale (refraction no longer drifts during the arrival
  bloom, the wiggle or home's depth zoom).
- GPU: glass shaders sample only what contributes (one sample in the flat body, no sharp image for fully frosted glass,
  analytic normals); tiles and list rows are drawn a second time only where they reach into the scroll-edge fade.
Frame stats after (missed refreshes): folder open 0-1 / close by bar 0 (was 4+1) / library to page 1 0 / Spotlight open 0
(was 2) / close 0 / list close 0 (was 2+2) / folder, dock launch and close 0 / sideways 1 / switcher 0. GPU per frame on
home with an open folder is still 9-12 ms (over budget but pipelined, no misses); the released swipe on home now blurs the
live home for ~0.4 s (16 ms GPU frames, no misses). Not checked on the phone: the unlock arrival (needs an unlock), PiP
holes, widget resizing. The Samsung keyboard still starts ~0.1 s after a fast pull opens Spotlight (its own start-up).

Round 21 (Matheesha on e9401f1: light and dark mode; unlock-to-home was static; the blur behind the dock broken; badges
clipped during opens and closes; the widget names toggle gone; "a thorough sweep once again"). On the S24 and the emulator:
- **Light and dark mode** (`theme/Appearance.kt`). Follows the system (or Automatic / Light / Dark from Edit > Appearance, a
  segmented row that keeps the menu open while everything crossfades behind it, 450 ms, every colour blended per frame).
  As iOS: the materials (App Library, folders, A-Z list, Spotlight, menus, widget gallery) are light with dark text or dark
  with white text; glass over them sees the same veil as the material around it; in dark mode the wallpaper is dimmed
  14 % and glass on home is tinted darker; edit-mode remove badges light/dark; status bar content follows what is under it
  (the light library gives black). Home is not recreated on a system dark-mode switch (configChanges uiMode, and an
  app-level callback so home has already crossfaded when it is next seen); its recorded pictures are taken again.
- **Unlock arrival**: One UI resumes home for a moment while the screen goes off; the arrival played then, in the dark, and
  the unlock showed home static. Now "due" is used up only when home is seen (screen on, keyguard gone; SCREEN_ON,
  USER_PRESENT, or a 250 ms check while the lock screen is up); home holds the arrival's first frame from the moment it goes
  to sleep. Checked on the emulator (held at sleep, played at wake); the S24's unlock is to be checked by Matheesha.
- **Dock glass**: e9401f1 counted home's depth zoom twice in the glass (its new on-screen scale included the zoom of home's
  GPU layer, which scales the glass again): after every close the dock showed the wrong part of the wallpaper until the zoom
  ended, then snapped. Transforms of the zoomed root are left out now; the glass redraws itself whenever its real place,
  home's depth or the appearance changes (pre-draw check), and during home's zoom it samples the wallpaper where it really
  is behind it (the wallpaper zooms less than home: `GlassDepth`, k = content zoom / wallpaper zoom).
- **Badges** are drawn by a view above the card, unclipped (they reach past the icon's corner): checked on the S24, launch
  and close. A closing card of our own screens took the icon of whichever of our entries rendered last (Icons by package).
- **Widget names**: Show/Hide Widget Names is back in the widget menu and the Edit menu.
- Sweep: widget resize crossfades (the new content was at full strength under the fading old look); a widget's setup screen
  keeps edit mode; the Search pill's text has the labels' shadow; the library's background is not drawn under a fully open
  folder; the wallpaper picture cache is keyed by the appearance; a Galaxy duplicate-Calendar merge from round 20 reverted
  (Google's and Samsung's Calendar are two apps). Debug hooks for checks (adb only, senders need DUMP):
  `am broadcast -a dev.launcher.app.TEST_BADGE -p dev.launcher.app --es pkg <pkg> --ei n <n>` and `... TEST_RECORD`
  (writes the picture of home gesture nav would show to files/home_picture.png).
S24 frame stats after (missed refreshes): folder open/close 0/0, library to page 1 0, Spotlight open/close 0/0, folder and
dock launch/close 0 (dock launch 0-1 over three runs), grab 0, cancel 0, sideways 1, switcher 0/0/0/0.

Round 22 (Matheesha on c6f343c: still no unlock animation; the dock background flickers and breaks sometimes; light/dark
consistent with iOS? the dock the same in both; light too light; the clock low quality, cramped in Add Widget; the Add
Widget panel darker than the rest). On the S24:
- **Unlock**: the log of Matheesha's unlocks showed the arrival starting ~100 ms after "held", while the screen was going off:
  One UI resumes home then and still reports it on and unlocked. Now the arrival needs a wake-up after it went due
  (SCREEN_ON / USER_PRESENT), the screen on, the keyguard gone and home's window focused; until then the first frame is held.
  Every step is logged ("arrival due", "woke", "home is seen, playing", "arrival not now: <why>", "ended after N frames").
  Emulator: held at sleep, played 140 ms after the wake, 19 frames. The S24 unlock itself needs Matheesha (secure lock).
- **Dock flicker, found**: Matheesha's log showed a launch from the dock grabbed straight back ("took over a launch card").
  Reproduced with the dev panel and `tools/frame_glitch.py` (finds 1-4 frame bursts in a screen region of a recording): one
  frame at the end of the close showed the dock's glass sampling the wallpaper from the wrong place. Home's glass skips
  redrawing while gesture nav's picture covers it (GPU saved during launches and closes), and nothing brought it up to date
  before the picture went. Now home refreshes stale glass in the frame gesture nav uncovers it on (`afterNextDraw`), and
  every close and the switcher's "home" take the picture away only after that frame. Five grab-and-close cycles: 0 glitches
  (before: 1 in 3). The dock is also no longer clear in both modes: it has iOS's light or dark glass body.
- **Light and dark like iOS**: one family of materials. The App Library, its folders and Spotlight are one material; menus and
  the widget gallery the same material, stronger (the gallery used its own, much darker veil: why it looked darker). The
  veils adapt to the wallpaper's brightness so each material lands on the same luminance whatever is behind it (light:
  library background 0.73 -> 0.57 on Matheesha's wallpaper; dark 0.33 -> 0.23). Glass on home (dock, Search pill, widget
  platters, edit buttons) is light glass in light mode and dark glass in dark mode; home's labels, the clock and badges stay
  as on the wallpaper (iOS does the same). Not matched: the system keyboard follows the system's dark mode, not our choice.
- **Clock**: rebuilt as lit 3D glass. The distance field is now full-size and exact, the strokes are rounded over at their
  edges (flat, clear middle) and the surface normals are computed in full precision on the worker (an 8-bit height in the
  shader came out streaky); the shader lights it (refraction towards the edges, gloss, Fresnel, shading of slopes, a crisp
  rim on the lit side, darker far rim, soft shadow), milky over a dark backdrop. One texture read per pixel for the shape
  instead of five. In Add Widget the preview is the widget itself scaled into the card with margins (18 x 16 pt), on the
  card's material, with today's date and time; the clock's own page uses the same.
- Tests run on my side: an accidental second clock widget on Matheesha's home (my scripted dock tap landed on the gallery's
  Add Widget button: home was "in front" with the gallery open) was removed again; scripts now start from two swipes home.
  `tools/appearance_tour.sh PREFIX` takes every surface in one appearance for side-by-side checks.
Frame stats after: every step 0-1 missed refreshes (folder, library, Spotlight, pull, launches, closes, grab, cancel,
sideways, switcher). `docs/design/clock_proto.py` still renders the previous clock pipeline (not updated).

Round 23 (Matheesha on 88a667e: the clock looks worse, "just embossed with some shadows": give it the dock's material and
refraction; the edit buttons and menus lost their liquid glass and look frosted; carry the dock's opacity, colour and blur
to every element, the clock included). Unlock: his unlocks on 88a667e played the arrival (64 frames), no longer reported.
- **One glass** (`GlassStyle.IOS`, the dock's): the clock, the Search pill, widget platters, the edit buttons, menus, the
  widget gallery and its controls, App Library tiles, folders, search fields and Spotlight's card. `IOS_CLOCK` and
  `IOS_LIBRARY` are now `IOS`. One tint for all (`Appearance.glassTint`: the dock's, light or dark), nothing laid over it
  (the edit capsules' extra tint, the sheet's control tint and the menus' and sheet's heavy veils inside the glass are gone).
- What made menus and the sheet look frosted was a strong veil drawn into what their glass saw. Now home behind a menu or a
  sheet is blurred under one adaptive scrim (`Appearance.scrim`, the same inside and outside the glass) and the glass on it is
  clear with the dock's tint, so it bends what is behind it and catches the light exactly as the dock does.
- **Clock**: the dock's shader with the digits' distance field in place of the rounded rectangle: the dock's 20 pt bevel and
  30 pt bend (the strokes are narrower than the bevel, so each stroke is lens, as thick glass digits are), the dock's edge,
  rim light and tint, and the dock's soft shadow (18 dp blur, 9 dp down; faded out towards its drawable's edges, which first
  cut it into a faint box). The lens direction fades to zero along the middle of a stroke instead of flipping (no crease).
  The 3D height, Fresnel and gloss of round 22 are gone.
- Labels on glass straight on the wallpaper (Edit/Done, the Search pill) take dark or white for what is behind them, as
  iOS's glass controls (`Appearance.labelOnGlass`, from the wallpaper's luminance under the capsule, one colour for the pair).
- Add Widget: the clock's card is a window onto the wallpaper where the card is (dimmed as home is in dark mode), so the
  preview is exactly the widget as it will look on home.
- Debug (adb, DUMP): TEST_RECORD also writes the clock's last field and mask (files/clock_field.png, clock_mask.png).
Frame stats: every step 0-1 missed refreshes. Folder open GPU 12-16 ms median (pipelined, no misses).

Round 24 (Matheesha on 75d1ea7: shadow bugs across the launcher: Spotlight's label shadows take an extra second to go; an
expanded App Library folder's labels go behind the other tiles, rest, then vanish; the App Switcher's card shadows; the
clock's shadow is massive).
- **Text shadows** (root cause of the first two): Android draws the shadow of a translucent shadow colour at that colour's
  own alpha, whatever the paint's alpha. Every label faded through its paint (Spotlight's suggestions, a folder's names,
  the A-Z list's rows, the switcher's titles, the Search pill's text giving way to the dots, icon and widget names switched
  off) kept a full-strength shadow until it stopped being drawn. `theme/FadingShadow` sets the shadow at its colour's alpha
  times the paint's alpha before every draw; LabelPainter, IconView, the widget frame, the Search pill, the clock's solid
  style, the edit buttons and the switcher's titles use it. Recorded on the S24: Spotlight's names fade with their icons.
- **Folder title**: it stayed where the open folder was while the panel shrank back to its tile, fading on top of the other
  tiles. It now rides on the panel's frame (scaled with it) and is gone by halfway. Recorded on the S24.
- **Switcher card shadows**: only a left strip was drawn (above and below each card the shadow ended at a hard vertical edge;
  a lifted card had none on its right) and flicked cards had none. Now four strips all round (the area under the card is
  never filled); a see-through card (flying away, fading in) gets its exact shape cut out instead. Checked on the emulator
  (the phone's switcher shows other apps, not recorded). S24 flick: 0 missed, max 9-11 ms GPU (one 82 ms frame on the first
  flick after the install: a one-time shader compile of the path clip).
- **Clock shadow**: the dock's 18 dp shadow around thin strokes read as a dark haze. Now a small contact shadow
  (`ClockShadow`: 5 dp blur, 1.5 dp down, 14 %).
Frame stats: every step 0-1 missed except the scripted cancelled close (2: its injected touches come every ~50 ms, the
card only draws on them; framestats then misreads the refresh as 50 ms; that path did not change).

Round 25 (Matheesha on 1a78d76: during the unlock animation the whole home showed for a single frame, then the elements
faded in; wants the elements zooming out instead of fading and zooming in; search fields translucent with the content
scrolling behind them; Spotlight showed the App Library's A-Z index for a few frames after erasing a query).
- **Unlock flash**, from his unlock logs: home took the arrival's first frame only once it was back in front (or at the
  screen-off broadcast only as "due"), so the last frame it had drawn before the screen went off (home at rest) was what the
  unlock showed first. Now the first frame is taken the moment the screen goes off (broadcast and onPause) and again at
  wake-up; a pause with the screen on (One UI's biometric screen) no longer lets it go; home no longer gives the system a
  snapshot of itself (`setRecentsScreenshotEnabled(false)`: a stale one could stand in for it). It plays as soon as the lock
  screen is gone (no wait for focus); if home is first seen more than 4 s after the unlock (the unlock went to an app) it is
  dropped, before home shows.
- **Zoom out**: every element (icons, widgets, Search pill, dock) starts as if home were seen 18 % closer, spread out from
  the screen's centre, and they settle as one camera pulling back (critically damped, 0.62 s). No fade on unlock; a cold
  start fades in with the wallpaper from black. A swipe during it keeps the dock and pill on the strip (no jump).
- Cold start: home showed itself at rest over black while the wallpaper was read, then hid and faded in; it now holds the
  arrival's first frame from its first layout, and waits up to 1 s for the clock's numerals (they arrived ~0.7 s late).
- **Search fields over the content** (App Library tiles and A-Z list, Spotlight's results, the widget gallery's list): the
  content scrolls on behind the field and only fades out above it (scroll edge); the field is the dock's glass over what is
  really behind it (`drawer/FieldGlass`: LiveGlass with the backdrop and the content drawn behind, the dock's 1.5 pt blur).
  The gallery's field sees the sheet's own material (home blurred under the scrim, saturated and tinted as the sheet's
  glass) under the list. Spotlight's field fades through its own drawing, never the view's alpha (a cut live glass), and
  is not clipped. Library scroll 0 missed (GPU 7.7 ms median); Spotlight open/close 0-1 over four runs.
- **Spotlight index**: Spotlight has no A-Z list or index now (as iOS); a cleared query keeps the last results while they
  fade out, and an empty query has no rows (typing makes results rise in).
Not checked on the phone by me: the unlock itself (secure lock screen). Emulator (swipe lock screen): held at sleep, the
unlock shows home zoomed in, then it zooms out; no frame of home at rest.

Round 26 (Matheesha: Spotlight's magnifier was dark in dark mode and light in light mode).
- The glyph faded with Spotlight's opening by multiplying its paint's alpha by the open fraction, read back from the same
  paint: it compounded every frame of the opening until the glyph was gone and only its shadow (dark, unscaled) was left.
  Now its colour comes from the appearance and the alpha is set from that at each draw; the shadow is a `FadingShadow`.
  On the S24: light grey in dark mode, dark grey in light mode, the same tone as the "Search" placeholder.

## Phase 4 slice: status bar, Control Center, Notification Center, banners (2026-10-07, in test)

Matheesha (2026-10-06): finish the status bar (the clock looked big next to the icons, the left gap was bigger than the
right, no notification icons), then recreate iOS 27's editable, polished, animated Control Center and Notification Center.

**Status bar** (`statusbar/StatusBarView.kt`, rewritten)
- Equal side margins (34 pt of the 402 pt layout, both groups); time 15 pt with tabular digits, the icons' visual weight.
- Notification icons after the time (one per app, from notifications that would alert; never ours, never a group summary),
  14.5 pt, up to 5, then a dot; never into the camera cutout. Every slot sits on springs: an icon arriving slides the
  others over, levels (battery, signal, Wi-Fi) glide, digits roll (minute changes).
- With Control Center open the right group moves down onto Control Center's status row ("NN%" beside the battery, network
  icons glide left); Notification Center takes the bar's colour for its light or dark content.

**Shade** (`shade/`): one full-screen accessibility overlay (`Shade`) that holds the bar, Control Center, Notification
Center, the Control Center gallery and banners. Pull down from the left of the camera = Notification Center, right of it =
Control Center (iOS); the panel follows the finger (rubber band past open), a fling or 40 % decides, a swipe up from the
bottom bar or Back closes it. Disable flags now also block the stock shade (DISABLE_EXPAND, disable2 QS + shade).
- **Pulls over apps**: the window manager hands a pull from the top edge to SystemUI after 24 dp (`transferTouch`), which
  cancels ours. Home now shows its bars transiently (no transfer there); over apps the shell service streams the
  touchscreen's raw positions (`TouchStream`, `/dev/input`, only while a pull lasts) and the panel follows those after the
  cancel. On the emulator only with real touchscreen events (`tools/kswipe.sh`); **not yet confirmed with a finger on the S24.**
- **Control Center** (`ControlCenterView`, `Controls`, `CcLayout`, `CcGallery`, `Media`): iOS 27 grid (68.5 pt cells, 17 pt
  gaps), connectivity module (Wi-Fi, Bluetooth, cellular, airplane, hotspot, location...), media module, Focus, brightness
  and volume sliders (stretch past their ends), flashlight, timer, calculator, camera, rotation lock, ringer, dark mode,
  screen mirroring, QR. Toggles act at once (own APIs or shell commands); long press opens the setting. Edit mode (+ at the
  top left or a long press on empty space): controls show remove badges and resize handles, drag to
  move (the others make room), the gallery sheet adds controls; the layout is saved. Glass: one baked, blurred picture of
  what is behind (home's or the app's last snapshot) shared by every control, each control in its own GPU layer.
- **Notification Center** (`NotificationCenterView`, `NotifPainter`, `Notifs`): the lock screen's date and glass clock over
  the wallpaper, the player when media plays, notifications grouped into stacks per app (tap to expand, "N more"), swipe left
  for Options / Clear (a long swipe clears), Clear All with an X → Clear confirm, a long press lifts a notification with its
  menu, flashlight and camera buttons at the bottom (hold to use). Taps open the notification's own intent.
- **Banners** (`BannerView`), replacing the heads-up that blocking the stock shade suppresses: swoop in from the top-left
  corner, 5 s, swipe up or sideways away, pull down for Notification Center, tap to open.
- **Calls and alarms** (found while testing: with the heads-up suppressed, SystemUI does not open a call's full screen
  either while the phone is unlocked, so a call would ring with nothing on screen): a ringing notification's banner stays
  until it stops ringing, also over an open panel. Calls look like iOS's compact call (caller, red Decline, green Answer, from
  the call's own intents); alarms show their actions (Snooze, Stop). A tap or pull down opens the full screen; swiped away it
  stays in Notification Center while it rings. It steps aside while the call's own screen is in front and comes back when
  that screen goes. Emulator (Google Phone, `adb emu gsm call`; Clock alarm): Decline, Answer, Stop, tap to full screen,
  over Control Center, swipe away, the Phone app open when the call comes, an app restart while ringing: all as described.

**Smoothness** (S24, `tools/scenario_shade.sh`, framestats, last three runs): every step 0-2 missed refreshes (one close-by-tap
run 4); GPU medians 2-9 ms, p90 under 10 ms except one edit-mode exit at 15 ms. What made it: Notification Center as one
GPU layer moved by translation (was 7-9 ms GPU), every notification, control and banner in its own layer recorded at rest,
at most three layers re-recorded per frame when only their place changed, the shade made focusable (for Back) only 300 ms
after a panel rests (the window change cost a 16.7 ms frame), the blurred backdrop baked at quarter size off the UI thread
(6-13 ms after the first, 82 ms the first time).

**Not verified on the S24** (ask Matheesha): a pull over apps with a finger (the raw stream); real heads-up replacement
(messages); an incoming call and an alarm with the phone unlocked (One UI's call screen class names are logged as
"[shade] in front while ringing"); opening apps from notifications; media controls with real playback.

**Found, not solved**: on the Android 17 emulator SystemUI's status bar chips ignore the disable flags (an incoming call's
chip and a call in progress's timer draw under our time; the mic privacy chip over our battery). One UI may differ.
A stock heads-up that appears while our flags are briefly off (the app starting or updating while a call rings) stays.

## iOS 27 reference: measured instead of guessed (2026-10-07)

Matheesha: motion matters most; nobody here has a Mac or an iPhone; use the iOS 27 Simulator and Apple's UI kit, derive
the design system and the motion, then carry them over.

- **Design system**: Apple's iOS 27 Figma kit read through the Figma API (his copy of the kit's examples: Control Center,
  home, Quick Actions, notifications, lock screen) and the running system's own accessibility frames:
  `docs/IOS27_KIT.md`. The kit's raw SVG exports carry the materials' exact recipes (fills with blend modes, the 0.5 pt
  rim, inner highlights, the 8 pt drop shadow); `ios-reference/figma/`.
- **Motion**: `ios-reference/` (an XcodeGen project, UI tests that drive SpringBoard with exact touches, a host app that
  posts notifications and plays calibration springs) runs on GitHub's `xcode-27` runner (free for this public repo) from
  the `ios-reference` branch; `tools/ios_ref/` turns the recordings into frames, tracks elements and fits springs.
  The chain was checked on known springs (within 3 %). Results and choreography: `docs/IOS27_MOTION.md`.
- **Found** (not in any article): Control Center's controls are not dropped in: they fade in at their own size, are
  pulled down with the finger (an ease-out of its travel) and spring back up past their place (0.42 / 0.68); "+" and
  power stay put; closing, the blur clears before the controls finish fading. Notification Center is the lock screen's
  cover sheet sliding down over a still wallpaper with a liquid edge that bends toward the finger. Folders: 0.49 / 0.92.
- **Carried over** (working tree, not yet pushed): Control Center geometry (70 pt controls on an 85.33 pitch, rows from 132.3, edit
  mode 86, status row 104.2, buttons at 38 / 335) and its pull, settle and close motion; notification platters (kit:
  14 pt margins, radius 24, 38.33 pt icon at 14, text at 62.33, 66.33 minimum, 8 pt stack shelves inset 10 / 20); banner
  radius 24; folder springs; banners out of the camera (0.64 / 0.61, 7 s, back on 0.3 / 1). Checked on the emulator (held pull: controls 57 pt low with the finger 258 pt down, as the
  formula; rest positions as iOS 27). Not yet on the S24.
- **Not settled yet**: the app launch spring (the CI recorder misses the fast first frames and the screen's edge clips
  the card), Notification Center's open (the recorder stalls while the cover sheet moves; its liquid edge is not built),
  the long-press menu (pass 6's clean run is still to be measured), banner swipe-away speeds.
- **Full kit** (Matheesha's copy of all 46 pages, read through the Figma plugin API, which loads every page): Liquid
  Glass is Figma's native glass effect with Apple's own parameters (clear glass: frost 6, refraction 0.7, depth 30,
  dispersion 0.2, light 0.4; dock: frost 3, light 0.2; menus: frost 16 on a white lighten fill), the classic materials'
  exact recipes, context menus (250 wide, radius 34, 40 pt rows, symbols on the leading side, 23 % dim), the long look,
  sheets, status bar groups, widgets (radius **28**, now ours too). All in `docs/IOS27_KIT.md`.
- Then, at Matheesha's word, three more carried over (checked on the emulator, not yet on the S24):
  - **Glass to the kit's numbers**: the lens 30 pt deep (was 20), dispersion 0.2 (was 0.25); the dock family's light at
    the kit's dock value (rim 0.20 / 0.09, half of before) and a clear-glass style at the kit's 0.4 for the shade's
    controls and notifications (`GlassStyle.IOS_CLEAR`). The frost already matched (the kit's dock frost 3 = sigma 1.5 pt).
  - **Long-press menu as iOS 27's Quick Actions**: symbol first (20 pt column, 26 in), label 14 after, 42 pt rows, 10 / 8
    padding, radius 30, no lines between rows, the widget sizes as the last row; the kit's Regular glass fills by their
    blend modes over the bent, blurred home (light: white 70 % lighten + grey 10 % darken; dark: luminosity #1a1a1a) and
    its deep shadow.
  - **The long look in Notification Center**: held, a notification grows into a solid card (16 pt from the sides, radius
    26, its whole text under the same header) with a 250 pt clear-glass menu under it (17 pt symbol and label rows, 20
    apart); a tap on the card opens it.

## iOS 27 consistency round (2026-10-07, after f014eac)

Matheesha on f014eac: Notification Center "BAD and glitchy" (backgrounds flicker while scrolling, the clock's space cuts
the notifications, the long-press menu does not blur what is under it, opening an app from a notification uses the
system's animation), the clock widget "doesn't feel accurate", Control Center's background "has to be live", not all
controls available and not implemented as iOS. Full evaluation, item by item: `docs/IOS27_EVALUATION.md`. Two more iOS
Simulator passes (7, 8) and Apple's kit (Control Center, lock screen clock, expanded notification).

- **Notification Center**: platters' glass drawn where they are every frame (the cached layers showed another place's
  wallpaper and jumped); opens **collapsed** as iOS 27 does (newest in front, the next peeking on one line, "+N from App";
  a tap fans it out; the list's anchor glides on a spring); no title row; a stack's count as a badge on the icon; clear
  glass with white text over a dimmed wallpaper; 58 pt clear glass flashlight and camera; nothing cuts the list (it scrolls
  over the clock, which fades, and fades out under the status bar and near the buttons); the long look blurs the sheet
  and has a dark menu; an app opens out of its platter (a card in the shade's window, the app started through our instant
  transition: `IShellService.sendNoAnim` sends PendingIntents with it; the system's animation does not play).
- **Clock** (home widget and Notification Center): iOS's lock-screen material (heavy frost, lifted, clear glass's light,
  weight 700).
- **Control Center**: live background (`LiveBlur`: Samsung dim-to-blur at 0.15 on the S24, where 0.35-0.6 left home a
  flat brown; Android's blur-behind on the emulator); expanded modules (Connectivity, Brightness, Volume, Now Playing,
  Focus, Flashlight, Timer: `CcExpanded`); 17 more controls (44); the gallery in iOS's sections.
- **Fixed on the way**: an unguarded read of a hidden setting crashed the app (Android 12+ throws); a restarted
  accessibility service left the shade unable to add its windows (it keeps the old token): a new shade now.
- **S24, measured** (local build, framestats; the scenarios never screenshot Notification Center): Control Center open
  0 missed refreshes (2 runs) with the live blur; Notification Center scrolling 0-1 missed per step, GPU median 7-7.6 ms
  (after dropping the platters' invisible shadow, their dispersion, and folding the dim into the wallpaper's layer: it
  was 8-15 ms); the collapsed stack fanning out 0-2 missed (7 on the first run after an install).
- Not done: hiding the list into a count, Control Center pages, gallery search, apps' own controls (quick settings
  tiles), Screen Recording, the notification count/grouping by conversation, "Carrier" in the status rows.

## Design system, step 1: the core (2026-10-07)

`docs/DESIGN_SYSTEM_PLAN.md`. Apple's iOS 27 kit read as its own tokens (Figma variables and styles, the Materials page's
components layer by layer): `docs/tokens/ios27-kit.json` (63 colours light/dark, the Liquid Glass globals, dimensions,
Dynamic Type, 34 text styles, every material's recipe with node ids). From it the iOS 27 theme file
(`assets/themes/ios27.json`), loaded at start (`[design] theme 'iOS 27' 162 tokens` in the log). The token editor lists
every token with its source and edits it (checked on the emulator: a colour edit saved, followed through its aliases,
reset). Found on the way: iOS's scroll edge is a progressive 10 pt blur under a gradient (kit), not a fade.


## Design system, step 2a: Notification Center on the one material renderer (2026-10-07)

Every surface of Notification Center (platters, a group's shelves, the collapsed stack's peek and "+N" pill, swipe
actions, the header's buttons, the flashlight and camera, the long look's menu) and the wallpaper's dim are drawn by
`design/MaterialPainter`: a material token drawn the way Apple's kit defines it, layer by layer in one shader pass
(backdrop blurred to the material's frost, the lens, fills with all 16 of the kit's blend modes, inner shadows, the rim
light, the drop-shadow rims; red/blue split only where it is a third of a pixel or more). The backdrop is the wallpaper
blurred to each frost by `design/FrostCache` (GPU, off the UI threads, made when the wallpaper loads: 94-154 ms on the
emulator). The list's dim is the kit's lock-screen overlay material (black 25 % + 5 % linear burn), which a canvas cannot
draw, so the wallpaper itself goes through the renderer. Sizes, type, colours and materials are 57 `comp.nc.*` tokens
(`shade/NcTokens.kt`; each with its kit node or why it is judged); `NotifPainter` (shared with the banners) lays text out
as Figma does (line boxes, half leading from the font's metrics, the kit's 15.67 pt padding above and below), the time in
the kit's plus-lighter grey on glass. The old `PanelGlass` is no longer used here (Control Center and banners still use
it until 2b and 2c).

Checked on the emulator against the kit itself, over the kit's own wallpaper (a test hook: a picture in the app's files
folder replaces the wallpaper, see `Wallpaper.load`; the iOS 27 Simulator's lock screen uses the same picture):

- The flashlight and camera buttons, ours next to Figma's 3x render of the kit's lock screen: the same size and place,
  the same clear glass (rim, lit edges, tint). The kit's symbols are 31 pt tall (flashlight) and 33 pt wide (camera);
  ours were 17 pt: now a measured 38.5 pt box.
- Collapsed stack, expanded list, a stacked group (the kit's Stack=3: a 63 pt front with 12 pt padding, two shelves 10
  and 20 pt in, 8 pt of each showing; the front's height eases between 63 and 66.33 as the group fans out), the long look
  (white card; clear glass menu over the blurred sheet, its glass now sees the wallpaper blurred as much as the sheet, not
  the sharp wallpaper), light and dark (dark: the kit's wallpaper dim; the clear glass is the same in both, as in the kit).
- A token changed in the token editor (button size 58 -> 80) redraws Notification Center at once and survives a restart;
  that showed the list's bottom was a fixed 118 pt: it now follows the buttons' tokens.
- Fixed on the way: a long list ran under the flashlight and camera buttons (text visible through them); the bottom fade
  now ends at the buttons' top (a judged token until the kit's progressive scroll edge is built).

Measured on the S24 (2026-10-08, `tools/scenario_nc_scroll.sh`, 3 runs each, frame stats only), 2e85c35 against the
build before it (e524c87, installed for the comparison, then the CI build put back):

| step | 2e85c35: missed / GPU median ms | e524c87: missed / GPU median ms |
| --- | --- | --- |
| open | 0-1 / 2.8-3.0 | 0-1 / 4.5-8.0 |
| stack fans out | 0-2 / 11.2-11.7 | 0-8 / 13.8-16.2 |
| scroll up | 0 / 7.2-8.3 | 0-1 / 7.9-12.2 |
| scroll down | 0-1 / 7.9-12.0 | 0 / 7.0-11.6 |
| scroll up twice | 1 / 6.2-11.0 | 1 / 7.3-7.8 |
| close | 0 / 2.3-8.4 | 0-1 / 2.1-6.8 |

No regression: the one renderer costs no more than the four it replaced, and the fan-out is lighter (it no longer draws
the dim as a second full-screen pass). Still not good enough for rule 1, in both builds: the fan-out's GPU time is over
the 8.3 ms a frame has at 120 Hz, and one frame of a scroll is late now and then. The same step swings between ~7.5 and
~12 ms from run to run (GPU clock, not our work): a Perfetto trace should say which frames are late and why.
The lens (refraction x depth = 21 pt at the very edge, our reading of Figma's unitless refraction) bends what is above a
platter into its top edge; the button comparison says it matches the kit, platters over a busy wallpaper should be looked
at on the phone. iOS Simulator pass 9 again showed no notifications (the posting test failed), so there is still no
iOS 27 screenshot of a long list.

## Design system, step 2b: Control Center on the one material renderer (2026-10-08)

Control Center, its expanded modules and the controls gallery are drawn by `design/MaterialPainter` through
`shade/CcSurfaces`, from 91 `comp.cc.*` tokens (`shade/CcTokens.kt`). Read from Apple's kit (Examples/Control Center,
iPhone, 12740:33932; recorded in `docs/tokens/ios27-kit.json` "controlCenter") and measured on its own render at 3x:

- Background: the kit's overlay, a background blur of 24 (sigma 12 pt) and black 50 % (was a judged 26 pt "radius" and
  36 %). One UI's live dim-to-blur keeps its judged strength (`comp.cc.background.samsung-strength`, 0.15).
- Modules: the kit's clear glass. A white control when on (Silent Mode in the kit) and a slider's level: the kit's "on"
  fill (`#6f6f6f` colour dodge + white 65 % screen) over the glass, not flat white. A coloured control when on: its
  accent. Every control's colour is a token (`comp.cc.accent.<control>`, aliased to the kit's accents: Wi-Fi `#0088ff`,
  Cellular `#34c759`...).
- Round symbol wells (the 2x1 controls' and Focus's 40 pt well, Connectivity's circles, the player's artwork and
  output, the expanded modules' circles and rows): the kit's well (`#111111` 60 % luminosity, `#777777` colour dodge,
  `#222222` plus-lighter) over the module's glass; it brings out the colour of what is behind, as in the kit.
- "+" and power: `#121212` plus-lighter, `#f5f5f5` symbols, 28.67 pt at 38 pt from the sides.
- Type: titles SF Medium 15 (-0.2), details Medium 14/18 in white 33 % plus-lighter, the player's track and artist
  Medium 15 `#afafaf` plus-lighter; lines placed in their line boxes as Figma does.
- Symbols, measured on the kit's render (their larger side): 1x1 29 pt, Connectivity's 23.5 and ~14, wells' 19,
  transport 20 and 22; ours were ~20 % smaller. Sliders rounded 34 (kit), 2x2 modules 30.
- The gallery (not in the kit): iOS 27's near-solid `#272925` sheet with flat white-21 % circles (measured, pass 5).

Also fixed: `FrostCache` and the long look passed a Gaussian sigma to `RenderEffect.createBlurEffect`, which takes its
own "radius" (sigma = 0.577 x radius + 0.5 px): those blurs were about a quarter too weak (`design/Blur`).

Checked on the emulator over the kit's wallpaper next to the kit's own render (light and dark), the expanded
Connectivity and Brightness modules, edit mode, the gallery; Notification Center again after the blur fix. Not measured
for smoothness (decided: one performance pass at the end).

## Fixes reported on the S24 (2026-10-08)

- **The keyboard popped over a panel, then hid.** Once a panel rests, the shade's window becomes focusable (Back closes
  it); a focusable window becomes the keyboard's target, and the system lifts the keyboard above its target. Now the
  window takes `FLAG_ALT_FOCUSABLE_IM` together with focus (never without: on a window that cannot take focus it means
  the opposite). Checked on the emulator, frame by frame over Settings' search: before, the keyboard showed bright over
  Control Center for 4 frames and then hid; now it stays dimmed under the panel, and is still there when it closes.
- **The clock sometimes missing in Notification Center after a light/dark switch** (not reproduced on the emulator).
  A new wallpaper (One UI may reload it with dark mode) threw the glass numerals away at once, and a build that went out
  of date while it ran was dropped without another. Now the numerals on screen stay until the new ones are made and then
  cross-fade; a stale build is followed by another; every failure is logged (`[shade] clock: ...`).
- **Light/dark felt delayed, most in the panels.** The panels did not follow the cross-fade at all (home did): they
  caught up later in one step. Now they redraw on every frame of it. And Control Center's Dark Mode starts our
  cross-fade as it is tapped, not when the system's new configuration arrives (the shell command and the system's own
  change take a second or more); if the system does not confirm within 5 s, we go back to what it says.
- Floating windows (picture in picture, a call's bubble) are covered by the panels: as under the stock shade and on
  iOS. Kept.

## Design system, step 2c: banners on the one material renderer (2026-10-08)

Banners are drawn by `design/MaterialPainter` from 14 `comp.banner.*` tokens (`BannerView.ALL`); the old `PanelGlass` is
gone (nothing used it any more). iOS 27's banner over a white app (Simulator run 4, the only banner capture) measured
#fafafa inside with a slightly darker rim: exactly the kit's regular glass over white (its 10 % `#bfbfbf` darken leaves
249). So banners are the kit's regular glass with its deep soft shadow (8 down, blur 48; the banner's layer now has room
for it). What a banner sees: over home, the wallpaper blurred to the glass's frost (made when the wallpaper loads); over
an app, which we cannot see, an app's usual background in this appearance (`comp.banner.behind`: white / black). Text
in the label colours; a call's buttons the kit's red and green; action capsules the kit's tertiary fill. Checked on the
emulator over home and over an app, in light and dark. The long look moved in step 2a.

## Fixes reported on the S24 (2026-10-08, second round)

- **No app opened from a notification** (the card grew, froze, went). Notification taps were sent from the Shizuku shell
  (it can run our own instant transition). Since Android 15 a PendingIntent's sender must itself be allowed to start
  activities from the background, and the shell is not a visible app: the system started the activity but left it
  behind (logged: "Without Android 15 BAL hardening this activity would be moved to the foreground ... the sender does
  not allow BAL"); the card waited 2.5 s for the app and went. Now our own process sends them (the shade's window is on
  screen; logged as BAL_ALLOW_HOME_APP on the emulator, BAL_ALLOW_ALLOWLISTED_COMPONENT on the S24) with "no animation"
  options; the card covers the start. The test notification is now made like a current app's (its creator does not lend
  the right), and `TEST_SHADE --es do nc_open --es title T` opens one of ours without a tap. Checked on the emulator
  (recorded) and on the S24 (Settings in front 0.4 s after the tap; log only).
- **Clearing was not animated properly.** After Clear (or a long swipe) the platter flew off but its Options / Clear
  buttons stayed, stretched across the row; the next notification slid up under them, and they vanished in one frame;
  the gap only closed when the system confirmed, a moment later. Now the buttons leave with the platter (sliding and
  fading) and the list closes up at once; a clear the system does not confirm within 3 s comes back. Checked on the
  emulator frame by frame. (The emulator's synthetic swipes report no speed: only a swipe past 62 % of the width clears
  there; a real flick clears from the button's width.)

## Our shade on the lock screen (2026-10-08)

Rule 2 (one shade everywhere) now holds on the lock screen too. Before, locking handed everything back to the stock UI
(our status bar and shade removed, the stock panels free). Now, while the keyguard shows:

- The gesture strip and the cards stay off (the lock screen has its own swipe up to unlock); our status bar and shade
  stay, and the stock panels stay blocked (checked on the emulator: a pull anywhere on the lock screen opens nothing of
  the stock UI; One UI not yet checked). The bar's time fades out (the lock screen's clock shows it, as on iOS).
- Notification Center shows what the lock screen's settings allow (`lock_screen_show_notifications`,
  `lock_screen_allow_private_notifications`, read through the shell when the screen goes off; unknown counts as hide):
  none, or each notification by its own lock-screen visibility (the channel's override, else its own): secret ones not at
  all, private ones as "App / Notification" (or their public version) without sender, picture or actions. Unlocking
  brings the full list in, animated. On the S24 the settings are "hide notifications": our Notification Center over the
  lock screen shows the clock and the buttons only.
- Anything that opens an app (a notification, a control, a setting, the player, an action that starts an activity)
  waits for the user to unlock (`Unlock.kt`): the panel closes, the system's unlock prompt comes up (`wm dismiss-keyguard`
  through the shell), and it runs when the user is present; given up on (screen off, a minute) it is dropped. Broadcast
  actions (media controls, a call's buttons, "mark as read") work without unlocking, as on the stock lock screen. The
  camera opens the secure camera over the lock screen. Control Center's edit mode asks to unlock, then opens again in
  edit mode. No banners over the lock screen.
- Checked on the emulator with a PIN: the unlock prompt came up for a notification, Settings opened after the PIN; the
  secure camera opened from the lock screen with the keyguard still up; content hidden with "hide content" set.
- An invisible activity over the lock screen cannot ask for the unlock prompt itself (a translucent activity does not
  cover the lock screen; the system cancelled the request): `UnlockActivity` stays only as the fallback without the
  shell.

Round 27 (Matheesha on 168c503: our panels work on the lock screen; One UI's fingerprint icon draws over both while
pulled, then vanishes suddenly; the lock screen to home animation still glitches).
- **Fingerprint icon**: One UI draws it in a system window above every app window, ours too (`FP Iconview`, type 2619,
  `com.samsung.android.biometrics.app.setting`), and hides it ~120 ms after the lock screen's window loses the focus
  (measured with window dumps, no screenshots). Our shade's window took the focus only once a panel had rested (+300 ms),
  so the icon stood over the pull and vanished after. Now a 1 px untouchable window on its own thread (`shade/FocusHolder`)
  takes the focus while the panel comes in: at the finger's lift, or 600 ms into a slower pull. Not at once: with the lock
  screen focused the window manager never hands a pull from the top to the stock status bar, with ours it does within the
  swipe's first 500 ms (the S24 did at 116 px). Measured on the S24 (window dumps): a 300 ms pull hid the icon ~0.4 s after
  the touch, a 1.2 s pull at ~0.7 s; no handover; back closes the panel (the window passes back and volume on). It comes
  back as the panel finishes closing, and at once when an unlock is asked for (the bouncer needs the focus).
- **Unlock arrival**: home dropped the arrival when it was first seen more than 4 s after the *wake-up*, so a longer look
  at the lock screen (now with our panels on it) dropped it and let the held zoomed-in frame go with home already showing:
  a jump to rest. The S24 log had two such drops ("home was seen 4842 ms after the unlock"). Now the 4 s count from
  USER_PRESENT (home often sees the keyguard gone just before it arrives: that is the unlock itself). Emulator: 7.5 s on the
  lock screen, then the PIN: the arrival played.
- **Control Center's glass on the lock screen** had no picture of what is behind (the front window there is the lock
  screen's or the fingerprint icon's; no task): its controls showed the plain fallback. Over the lock screen the backdrop is
  now the wallpaper (`BackdropSource.Wall`); the S24 has one wallpaper for both. A separate lock screen wallpaper is not
  read yet (Notification Center draws the home one there too): open item.

## Control Center: controls from apps (2026-10-08)

Matheesha asked how Control Center finds controls ("a custom control from an app, e.g. Shazam"): it had a fixed list.
Now apps' Quick Settings tiles are controls too, as iOS 18+ lists apps' controls (`shade/AppTiles.kt`):
- Found through the package manager (TileService, `BIND_QUICK_SETTINGS_TILE`), again on every app install / update /
  removal: the S24 has 45 tiles from 30 apps (0.76 s in the background), all with a symbol to tint. A symbol that is a
  full-colour icon falls back to a generic one; a theme-coloured vector gets its app's theme.
- The gallery ("Add a Control") lists them after the built-in sections, one section per app, with the tile's own name and
  symbol. On the page a tile is `Control.APP_TILE` with its component (`CcItem.tile`, saved as `"t"`), one cell or two
  wide (then its name and subtitle: SystemUI's, e.g. "Quick Share / No one", else On / Off for a switch).
- Only SystemUI may bind a tile, so SystemUI runs it: a tile put on Control Center is added to SystemUI's own tiles
  (`cmd statusbar add-tile`; the stock panel stays blocked, it never shows) and removed again when it leaves, if we added
  it. A tap is `cmd statusbar click-tile` (shell); its state (label, subtitle, on / off / unavailable) is read from
  `dumpsys activity service com.android.systemui/.SystemUIService QSTileHost` (0.23 s) when Control Center opens and
  after a tap. A tile that opens something (a window of another app comes to the front within 2 s) closes the panel; a
  switch keeps it open and animates on. Long press: the tile's settings (`ACTION_QS_TILE_PREFERENCES`), else the app. On
  the lock screen a tap waits for the unlock. An app removed: its tile leaves the page.
- Emulator: the gallery showed 8 tiles from 7 apps; Digital Wellbeing's Focus tile added, tapped: its screen opened and
  Control Center closed; wide tiles show "Focus / Off", "Quick Share / Off". Not tried on the S24 (a tap changes real
  settings): to be checked by Matheesha.

## Design system step 2d, part 1: home's glass on the material renderer (2026-10-08)

- Apple's kit has the dock's own material (System page, `_Dock Material` 10486:20740): frost 3, the clear glass's lens with
  light 0.2, a luminosity fill (#999999 at 33 % light, #333333 dark), lit top and bottom edges (inner shadows) and the thin
  rims. It is now `ref.material.liquid-glass.dock` / `sys.material.glass.dock` (kit data in `docs/tokens/ios27-kit.json`), and
  home's surfaces each have a token (`home/HomeTokens`, `comp.home.*`): the dock and the Search pill (the kit makes both of
  it), the widgets' glass and edit mode's Edit / Done (ours, judged: the same).
- `GlassView` given a material draws it with `MaterialPainter` over the wallpaper blurred to the material's frost
  (`FrostCache`), as Notification Center does; its placement tracking is unchanged. The renderer learnt what home's glass
  needs: home's depth zoom (`setDepth`, the same maths as before), a wallpaper change (`setReveal`: the old wallpaper ahead of
  the same front as the wallpaper's own reveal, so the glass changes on the same frames), and the light turning as home
  arrives (`lightTurn`); the dark appearance's dim of the wallpaper is an under fill. The glass clock's numerals stay on
  `GlassDrawable` (a text shape the renderer does not have yet).
- Emulator, over the kit's own wallpaper: the dock and the Search pill side by side with the kit's render match in tone,
  lift, rims and light (light and dark). A wallpaper change: the dock keeps the old wallpaper until the front reaches it,
  then the new one. Control Center, edit mode's buttons, an app's close back to home: drawn as expected.
- Differences from the kit left as they were (not materials): the Search pill is a little larger than the kit's 77 x 30 pt,
  and its label turns dark over a light wallpaper (the kit's stays white). Next: the long press menu, App Library,
  Spotlight and the search fields, the widget gallery's sheet.

Round 28 (Matheesha on 171f94f: the fingerprint icon fixed; apps' controls all there; the dock and Search pill look
great; Control Center comes in too fast to see; the unlock animation still bad).
- **Control Center coming in** is now two tokens he can tune in "Launcher design": `comp.cc.motion.open-travel` (the pull
  over which it comes in: 240 pt, was iOS's measured 110 pt, ~75 ms of a quick pull) and `comp.cc.motion.open` (the spring
  it finishes on: 0.45 / 1, was 0.3 / 1). A flick's speed is divided by that travel, so the short one also made the
  release spring finish in a few frames. Emulator: it comes in over about a quarter second instead of at once.
- **Unlock**: his five unlocks on 171f94f all played the arrival (94 frames, 0.78 s, none dropped). On the emulator the
  arrival runs while the lock screen is still going away: the first frame of home that shows is already at rest. Recording
  the unlock on the S24 is the next step (the PIN screen records black; our status bar shows).
- **Unlock, measured on the S24** (screen recordings of his fingerprint unlocks, started from a volume press so they span
  the lock; the lock screen itself was recorded with his consent). Every unlock showed home *at rest* for 1-4 frames (once
  with a two-minute-old clock), then the arrival's zoomed first frame, then (after 80-160 ms standing still) the zoom-out.
  Causes: (1) One UI takes a picture of home ~0.4 s after the power key (SurfaceFlinger "Capture layer list" at
  screenTurningOff) and shows it at the unlock until home has drawn again; home learnt of the sleep only at onPause, ~50 ms
  after that picture, and its window was stopped (surface destroyed) before the held frame rendered. (2) The arrival
  waited for the keyguard's state. (3) Straight from screen-off, the display wakes from the always-on display with frames
  ~50 ms apart, and the zoom, on real time, jumped across them. Now: home watches for the screen going off while in front
  (`isInteractive` every 50 ms) and eases into the held frame at once (140 ms), well before One UI's picture; the arrival
  starts when home's window is shown again (before the keyguard's exit even starts); its time advances at most 1/60 s a
  frame. Recorded after (1) and (2): lock screen, then the zoomed first frame, then the zoom-out, no frame at rest; (3) is
  made, not yet recorded.
- Matheesha on 505513b: from screen-off smooth; woken with the power key first, "instant and glitchy". His log: woken with
  the power key, One UI shows home's window behind the lock screen at once, and "window shown" had started the arrival
  there, over before the unlock; a late SCREEN_OFF broadcast (after a quick wake-up) also re-armed an arrival that had just
  played. Now home is seen when its window is shown *and* it is resumed (an activity behind the lock screen never is), and
  SCREEN_OFF counts only while the screen is still off. On the S24: a quick lock and wake leaves the arrival held.
- Still "the same" for him: woken within ~0.6 s of the screen going off, One UI resumes home ~40 ms *before* it puts the
  lock screen up (it had not been shown yet), with the keyguard's state still clear: nothing said it was coming. Home is now
  seen only once the lock screen has been seen up since the arrival went due and is gone again (a real unlock: up at the
  resume, gone ~40 ms later; a quick wake-up: clear first, up after); with no lock at all, after 300 ms resumed. While home
  is resumed behind the lock screen the state is read every frame (for 2 s), so the arrival starts ~1 frame after the lock
  screen goes. Matheesha: "works now" (all three: from screen-off, power key first, a quick re-wake); his log: every
  arrival 93-94 frames at 120 Hz.

## Design system step 2d, part 2: the rest of home's glass (2026-10-08)

- The renderer draws over live content too (`MaterialPainter.drawLive`: what is behind recorded into a render node, blurred
  to the material's frost, the one shader run on it as a render effect), so `LiveGlass` is gone.
- **Long-press menu**: the kit's Home Screen Quick Actions (System page 5626:51776) is the Regular glass (frost 16, white
  70 % lighten / grey 10 % darken; dark: #1a1a1a luminosity), corner 30, 250 pt, rows 42, Body 17 #1a1a1a, red #ff383c
  (`comp.home.menu.*`). Compared with the kit's render: the same frosted white, rims and soft shadow (home behind ours is
  blurred as well as dimmed, as iOS does; the kit's mock only dims it).
- **Search fields** (App Library, Spotlight, the widget gallery): the kit's search field (Toolbars, `_Search - 48pt`) is
  exactly the small glass, active (`comp.home.field.material`), drawn live over the content scrolling under it.
- **App Library** tiles, folder panel and search bar; **Spotlight**'s card: the dock's glass as before (the kit has no App
  Library: judged), now the kit's dock material, over the same blurred wallpaper and veil as their background
  (`drawer/BackdropGlass`, `comp.library.tile.material`, `comp.spotlight.card.material`).
- **Widget gallery**: the sheet on the Regular glass (the kit's medium sheet; its large one is opaque white: judged for
  the gallery's tall glass sheet), drawn live over home; its buttons on the kit's sheet-button material (the small glass,
  active), its widgets' cards on the dock's glass (`comp.widgets.*`).
- Soft drop shadows were composited as colour x coverage (a shadow at opacity a came out at about a squared: the kit's
  25 % black at ~6 %); now the colour that over the backdrop gives it. Banners and the menu show the kit's soft shadow.
- Emulator, light and dark: the menu, App Library (tiles, search), Spotlight (Top Hit, field), the widget gallery (list,
  an app's page): all on their materials. Left on `GlassDrawable`: the glass clock's numerals (a text shape; the renderer
  draws rounded rectangles), on home, in Notification Center and in the gallery's clock preview.

## Fixes reported on the S24 (2026-10-08, third round)

- **Notification Center's clock showed an old time** (and then cross-faded to the right one in front of the user). Its
  glass numerals were made only while Notification Center was open, so it slid in with the time it was last open at
  (emulator recording: 4:35 at 5:13). They are kept current while it is closed now (each minute while the screen is on,
  and as the screen comes on). Recorded on the emulator after the minute turned: the right time on every frame.
- **A stack fanning out showed every notification's text through the others.** Notifications were drawn in the order
  they were made, so the ones coming out of the stack were drawn over the one in front, and their half-clear glass (fading
  in) showed the text under them. Now lower in the list is further back, and a notification's glass is whole early (at
  40 % of its presence) while its content fades in after (from 35 %): cards slide out from behind the front one, solid,
  then show their text (iOS). Recorded on the emulator.
- **Android's back gesture took our gestures.** The system watches a swipe in from a side edge over every window: a pull
  from a top corner that moves sideways first (a thumb's pull for Control Center) was taken (the panel never opened;
  over an app the app got Back), and a swipe from the right edge over a notification closed Notification Center instead
  of showing its actions (both reproduced on the emulator). The shade's window now keeps the back gesture off the bar's
  two ends and off Notification Center's right edge beside the list (system gesture exclusion). A window gets only 200 dp
  of each edge for that, counted from the bottom up, unless it asks for the navigation bar hidden with the transient
  behaviour: the shade's window asks so, and never takes the focus (the focus window, `FocusHolder`, takes it for Back
  now, unlocked too, so the shade's window never controls the bars: the navigation bar stays). Emulator: the corner pull
  opens Control Center, the right-edge swipe leaves Notification Center open, a swipe from the left edge and the Back
  key still close it, the navigation bar stays visible. Making the shade's window focusable is gone with it (a relayout
  of 16.7 ms of its thread on the S24).
- **The widget gallery lagged.** Every frame of a scroll (and of its rows arriving) recorded all of home twice (behind the
  sheet and behind the search field) and blurred both again, and drew the featured clock card (the wallpaper through a
  rounded clip and the glass numerals) up to four times. Now the sheet's glass and the field's backdrop are drawn again
  only when what is behind them changes (`MaterialPainter.drawLiveAgain`: same render node, same effect, so the renderer
  keeps its blurred result), and the featured card is drawn once into a layer of its own and moved (it shows the
  wallpaper as it is behind the card with the list at its top). Emulator frame stats while scrolling (GPU per frame,
  median): 78 ms before, 55 ms after the first change; the emulator is too noisy to measure the card's layer (and far
  slower than the phone): the S24 decides.
- **A thin black outline around glass.** The kit's hairline rims are drop shadows without blur in linear burn (#cccccc,
  dark #a6a6a6): burn subtracts, so over anything darker than a mid grey they come out black, a 1 px outline the phone
  shows plainly. New token `sys.glass.rim` (factor, judged 0.4; 1 = the kit's) scales every blur-0 drop shadow; the soft
  shadow is unchanged. Emulator, Control Center over home, zoomed: the black line gone, a faint darker edge left under the
  light rim. Tunable in "Launcher design".

## Fixes reported on the S24 (2026-10-08, fourth round)

- **Opening an app from a notification played Android's own open animation after our card.** The tap went out with
  "custom animation 0, 0", which the system's transitions read as "none given" and replace with their default (the log:
  `DefaultTransitionHandler`, ~0.55 s, starting as our card faded). Real "do nothing" animation resources were tried too:
  the system takes no animation from a cross-app tap's options. The tap must come from our process (Android 15: only a
  sender with a visible window brings an app forward), and our own transition needs the shell, so the system's transition
  animations are switched off (shell, `SystemRestore.scalesOffForCards`, restored by the watchdog if we die) just before
  the tap and back 1.5 s after the launch ended. Emulator: the system's transition took 70 ms (was 550), scales back to
  1.0 after. The card itself is unchanged.
- **Options (and every page or app the shade opens) had the system's animation too.** They now open out of where they
  were tapped, on the same card as a notification, through our own transition (the shell's start: `animated by
  firstHandler`); Settings' panels (sheets with their own entrance) open as before. Emulator: Options grows into
  Settings' notification page.
- **The gesture bar under the panels.** While Notification Center or Control Center is open the gesture bar's pill fades
  out and the strip takes no touches (a touchable region; `null` does not reset one: the full region is set back
  explicitly). A swipe up from the bottom closes the panel, the shade's own.
- **Control Center's close felt cheap** (iOS 27's: a small lift and a fade). The controls now fold back into the corner
  Control Center is pulled from: the farthest leave first, each shrinking and drifting toward the corner as it fades; the
  blur clears with them; a little slower (0.34 s spring). Blended in on a spring when closing starts, so a panel grabbed
  back mid-close never jumps; the open is unchanged.
- **Home swipe with the widget gallery open** showed the gallery vanish in one frame (the picture of home a home swipe
  recedes did not contain it), home sharp, then blurred, then the gallery sliding away again under the picture. With
  something open on top of home (the gallery, a menu, edit mode, Spotlight) the swipe now just closes it with its own
  motion, as iOS (no receding picture, no App Switcher on a hold). Recorded on the emulator: the sheet slides down, home
  un-blurs, edit mode ends.
- **The widget gallery's first moments lagged.** While the sheet rose, every frame recorded all of home twice (main
  thread) and blurred it again for the sheet's glass. Now home is recorded once per opening and drawn by reference, and
  the renderer's live form keeps what is behind blurred in a node of its own when the caller gives a content key
  (`MaterialPainter.drawLive(contentKey)`): the shader runs on the kept blur; the scrim is a fill in the shader. Same look
  (compared on the emulator); the phone decides the smoothness.
- **Status bar notification icons**: at most 3 (was 5), a dot for the rest.

## Fixes reported on the S24 (2026-10-08, fifth round)

- **Notification Center sometimes opened darker and without its clock** (Matheesha's screenshots: the whole picture at
  0.71 of the right brightness, exactly the list's overlay, and no date or clock; a touch or a new notification fixed
  it). Reproduced once on the emulator: the list fanned out and flung, closed while still moving, reopened. The sheet
  showed the last session's dimmed wallpaper and faded clock (kept in layers of their own) over the new, collapsed list.
  Not reproduced again with logging on (timing). Made robust from both ends: on closing, Notification Center goes back to
  how it opens at once (collapsed, at the top, a running fling stopped, the wallpaper undimmed), and on opening the
  wallpaper's and the clock's layers are always made anew.
- **The widget gallery still lagged at first** (Matheesha: while widgets load). An app's page made the preview layouts of
  its widgets without a preview image on the main thread, all at once while the page slid in: now one a frame once the
  page rests, each fading in. The list's rows draw home's own shaped icons (bitmaps) instead of composing each app's
  adaptive icon on the main thread as the rows arrive.
- **A notification once opened with the system's animation again**: the animation switch-off goes through a setting
  SystemUI takes up a moment later, and a warm app can be ready sooner. Now it is switched off when a finger lands on a
  notification (back by itself 1.5 s later if nothing opens), and when it is switched at the tap itself the tap waits
  100 ms for it (the card covers that). Emulator: the system's part 7 ms, the scales back to 1 after.
- **A notification flickered as its long look closed** (back into glass). The long look stopped drawing it a little
  before the list took it back (two thresholds, 0.3 % and 0.1 % of the close): for a frame or two it was not drawn at all.
  One handover point now (`LOOK_HANDOVER`). And the platter faded into the card at its own place in the list while the
  card moved: two copies of its text, apart; it now rides with the morphing card. Recorded on the emulator.

## Step 3+: Change Wallpaper in the Edit menu (2026-10-08)

- Edit mode's Edit menu has **Change Wallpaper** (second, after Add Widget; a picture glyph). It opens the system's own
  picker on the launch card, out of the Edit button, through our own transition: Samsung's "Wallpaper and style"
  (`com.samsung.intent.action.WALLPAPER_SETTING`, checked on the S24 without opening it), else the system app answering
  "set wallpaper" (Google's picker before Android's: on the emulator Android's crashed at once, denied Google's provider),
  else Android's chooser. Edit mode ends as home goes out of sight (as for any app opened from it); the new wallpaper
  arrives with its reveal. Emulator: recorded, the menu closes, the card grows into the picker.

## Step 3: the menu component; Step 3+: the App Switcher's Clear All and app menu (2026-10-08)

- **Menu component** (`components/Menu.kt`): `MenuSpec` (one set of tokens per menu: `comp.<menu>.*`) and `MenuPainter`
  (layout beside what it belongs to, rows with symbols and labels, destructive, pressed, a segmented row, widget sizes,
  the growing transform). The panel's glass is the host's (it knows its backdrop). Home's long-press and Edit menus are
  on it (`ContextMenuView` keeps the lifted item, the dim and the touches): no visible change (compared on the emulator).
- **App Switcher menu** (Android's convenience; iOS has none): a tap on the focused card's name opens the menu below it,
  the kit's menu glass over the card (`comp.switcher.menu.*`, the home menu's values): **App Info** (the card grows as
  when opened, its picture giving way to Settings' launch look, while App Info starts through our own transition: the
  hand-over matches), **Keep Open** (a lock after the name; Clear All leaves the app; persisted) and **Close** (the card
  flies off as when flicked).
- **Clear All**: a capsule of the menus' glass at the bottom of the deck (`comp.switcher.clear.*`; it sits below the cards,
  so its glass keeps its blur between frames). The cards on screen lift off the top one after another, the front one
  first, each from its place in the stack (the ones behind rise from behind); kept apps stay; home follows. (A first try
  took the cards out of the deck to fly them: hidden ones jumped in front of the kept card. And Clear All came back for a
  moment as the deck slid home.) Emulator: recorded; the closed apps' tasks are gone, the kept one stays in recents.
- The emulator cannot take task pictures (launch screens stand in) and its recorder stalls when the windows change: the
  App Info hand-over is checked by logs and the final screen; the S24 shows the motion.
- **Kotlin's incremental build missed a changed interface**: `tools/build.sh` succeeded while GestureNav did not implement
  DeckView.Listener's new methods; `compileDebugKotlin --rerun-tasks` showed the error. After an interface change, run a
  full compile before believing a build.
- **Banners over an app on real glass** (step 3+): over an app a banner's glass sees the app's latest picture (gesture
  nav's, the one Control Center's glass uses), baked at a quarter size and blurred to the banner glass's frost off the UI
  threads (`BlurBaker`); until it comes (a moment) the app's flat colour stands in and the glass fades from it (180 ms).
  Over home, the wallpaper as before. Not checked on the emulator (it keeps no app pictures: the banner stays flat
  there, as before); on the S24 only Matheesha may look at banners.

## Step 3: the look audit, first part (2026-10-08)

Every surface screenshotted on the emulator over a light, a mid and a dark test wallpaper (pushed as
`files/wallpaper.png`), in light and dark mode, and compared side by side (contact sheets per surface). The glass
surfaces (dock, Search pill, widgets, menus, the widget gallery, banners, Notification Center's platters) read as one
material in all six cases. Found and fixed:

- **An orange-red line along the dock's right end.** The frosted backdrops (`FrostCache`, and `BlurBaker` for the
  shade) were drawn into a rounded-up size at one scale: the last column and row were only partly covered (half
  transparent, dark), and the lens, bending its samples outward at the screen's edge, showed that edge with red and blue
  apart. Both now scale to cover the image exactly.
- **Names under icons and widgets vanished over a bright wallpaper** (white on white). They now take a tone from the
  wallpaper under each name (`LabelTone`, tokens `comp.home.label.*`): white with the soft shadow, as iOS, turning dark
  (no shadow) over a bright one, as Android launchers do; dark mode's dim is counted. A new wallpaper moves them on a
  spring, the appearance's crossfade frame by frame.
- **The glass clock**: white glass vanished over a bright wallpaper in light mode, and dark mode's dark glass was muddy
  over mid and dark ones. Its tint now follows the wallpaper under it instead of the appearance, with the names'
  thresholds (`comp.home.clock.tint-light` / `-dark`): light glass and a white date over dark and mid wallpapers, the
  same glass slightly dimmed and a dark date over a bright one, in both modes. (First with the dark mode's tint, 32 %
  black: confirmed on the S24, but it read as grey; Matheesha: keep it close to the dock's glass, only dim slightly.
  Now 16 %, `#00000029`.) The same for Notification Center's clock
  and the widget gallery's clock preview. Emulator: all six cases on home and in Notification Center look readable.
- Then Control Center, the App Library, Spotlight, the Edit menu, the App Switcher's Clear All and its app menu over
  the three wallpapers in both modes: consistent, nothing to fix. Control Center looks the same in both modes (as iOS);
  an "on" round control is pure white over a light backdrop and a light grey over a dark one: that is the kit's own
  material (`ref.material.control-center.on`: colour dodge, then white in screen mode), kept.
- **Two ANRs on the emulator, not from the app's code**: after ten hours up, the emulator was out of memory and swapping
  (3.9 of 4 GB, swap nearly full, 92 % of CPU in the kernel); a frost bake took 2-3 s there, and the main thread, which
  needs the render thread to draw or to add a window, waited on it past the system's limits (the gesture strip's input,
  the watchdog's foreground service). The emulator was restarted (swap empty again). Noted for the performance pass: all of the app's
  hardware renderers share one render thread, so a bake delays every window's next frame while it runs.

## Step 3+: One UI's Brief pop-ups (2026-10-09)

- With One UI's notification pop-up style "Brief" (`edge_lighting` 1), pop-ups come from the system server's edge
  lighting and show beside our banners (the flags stop only SystemUI's heads-up). When our shade takes over, `PopupStyle`
  reads the setting through the shell once per process; if it is Brief, a notification of ours (channel `setup`, which
  shows as one of our banners, 3 s after the shade took over so home has arrived) offers "Use Detailed pop-ups?". A tap
  switches it to Detailed (0) and keeps the user's value; Restore system puts it back and lets the offer come again.
  Asked once (swiping it away is a no). Phones without the setting ("null") are left alone.
- Emulator (the setting set to 1 for the test, removed after): the offer's banner arrived after home, the tap switched
  to 0 ("switched to Detailed (was 1)"), Restore system put back 1. The S24 is already on Detailed: nothing shows there.
- Emulator pitfall: Shizuku's app starts its own server at boot; starting another by hand left two, our service attached
  to the other one ("unable to find token") and never connected. Check `ps -A | grep shizuku_server` first.

## Step 3: Notification Center's long-look menu on the menu component (2026-10-09)

- The long look's menu is drawn by `MenuPainter` with its own token set (`MenuSpec.NC`, `comp.nc.menu.*`: the kit's clear
  glass and white labels; the old `comp.nc.look.menu-*` tokens went, except the gap under the card). The kit's
  measures turned out to be home's menu geometry exactly (42 pt rows, symbols centred 36 pt in, labels at 60 pt, 17 pt
  body), so only the glass, the label colour, the press colour and how far it grows (`grow-from`, new for every menu:
  home 0.8, here 0.6) differ. Menu items take a tinted drawable symbol (`symbol`, sized by the spec).
- Before/after on the emulator (the same test notification held): pixel for pixel the same, once two differences were
  fixed: the component drew labels without the type's tracking (the kit's -0.43 pt: home's and the switcher's menus
  now get it too, slightly tighter, as the kit), and the symbols' bounds were rounded instead of placed by translation.
  Tapping "View Settings" opened the app's notification settings (rows map to touches as before).

## Step 3: the badge component (2026-10-09)

- `components/Badge.kt`: the count badge (icons, the dock, launch and close cards) and edit mode's remove badge read
  `comp.badge.*` (the values they had, marked judged: the kit's data has no badge). `Appearance.removeDisc` /
  `removeMinus` went into the tokens. Emulator: home with the dock's count pixel for pixel the same; edit mode's
  remove badges the same (the icons' wiggle differs between captures).

## Fix: app shortcut icons in home's menu drew without their foreground (2026-10-09)

- Gmail's "Compose" shortcut showed as a plain red circle. Its icon (`drawable/compose_launcher_shortcut_icon`, no bitmap;
  `aapt2 dump xmltree`) is a `layer-list`: a red oval, then the white pencil (`ic_pencil_wht_24dp`, a 24 dp vector with a
  literal `#ffffffff` fill, no theme attribute) inset **16 dp on each side**: 56 dp in all (147 px on the emulator).
  `MenuPainter` gave it bounds of 22 pt (59 px); a layer's insets are in dp, not fractions of the bounds, so the pencil's
  bounds came out inverted (`Rect(42, 42 - 17, 17)`, logged on the emulator) and a vector with no room draws nothing.
  Not the theme (the colour is literal) and not the layer (the same happened rendered to a plain bitmap).
- Fix (`MenuPainter.drawGlyph`, the `icon` branch): the drawable is laid out at its intrinsic size and the canvas scales it
  into the 22 pt box (centred, aspect kept), as a launcher draws a shortcut at its own icon size. Adaptive and bitmap icons
  scale with their bounds, so they look as before (sub-pixel placed now); only drawables without an intrinsic size still get
  the box as bounds.
- Emulator (Android 17): rendered to bitmaps before/after (bare circle / circle with the pencil), and Gmail's long-press menu
  shows the white pencil on the red circle, crisp at full resolution. Maps' (Home, Work) and Chrome's (New Incognito tab,
  New tab) adaptive shortcut icons draw as before, masks and glyphs clean at full resolution.
## Step 3: the slider part, the stack count, the shade's colours (2026-10-09)

- `shade/CcParts.kt` `CcSlider`: Control Center's slider module and its expanded form are one part (the level in the
  kit's "on" fill, the symbol taking the control's colour as the level crosses it). The expanded slider's sizes are
  tokens (`comp.cc.expanded.slider-*`); the colour change now spans the same 80 % of the symbol in both
  (`comp.cc.slider.cover`; the expanded one had used 100 %).
- The badge component takes a spec (`BadgeSpec`): an app's count (`comp.badge.*`) and a stack's count in Notification
  Center (`comp.nc.count.*`, its own painter before) are one painter.
- The shade's colours that were in code are tokens: Control Center's (a symbol on a lit control, the media bars and
  placeholder, edit mode's marks, "Add a Control", the gallery's text), Notification Center's (the player's placeholder
  and track, group names and the text shadow, a lit button's symbol), the banners' (the plain platter, a call's buttons)
  and the battery's colours (`comp.statusbar.*`).
- Checked against a build of b7ce563 on the emulator (each APK's hash checked on the device: a second session had
  installed its own build on the same emulator mid-test, which voided one earlier comparison): Control Center, its
  expanded brightness slider, Notification Center fanned out and with a stacked group (its count badge): pixel for
  pixel the same, but for the emulator's signal indicator.

## Step 3: home's palette from the theme (2026-10-09)

- `Appearance`'s colours for text and lines on the materials (label, secondary and tertiary label, separator, press
  fill, destructive), the glass's tint and dark mode's wallpaper dim are theme tokens (`sys.color.material.*`,
  `sys.color.press`, `sys.glass.tint`, `sys.appearance.wallpaper-dim`; keys in `theme/PaletteTokens`), with the values
  they had. The theme now loads before the appearance. Also tokens: a menu's fallback (`MenuSpec.fallback`), the widget
  gallery's fallback and grabber, a search field's clear button.
- Emulator, against the previous build, light and dark mode: App Library, Spotlight, the Edit menu and the widget
  gallery pixel for pixel the same (but for the text cursor and the clock's minute).
- A mistake of mine: one commit went out with the theme test failing (the command committed whatever the test said);
  fixed in the next commit. Commits now go through a script that commits only when the tests pass.

## Step 4: the motion registry (2026-10-09)

- Every animation's spring and timing is a theme token by role (`motion.*`, keys in `motion/MotionTokens`): home's
  profile (`MotionProfile` keeps its property names and reads the tokens, so no call site changed) and the springs that
  were constants in the shade, Control Center, its expanded modules and gallery, the banners, the status bar, edit mode
  and the switcher. Equal press springs became shared roles (`motion.press-*` for Notification Center and the banners,
  `motion.cc.press-*` for Control Center). Same values as before; the token editor can retime any of them.
- Emulator smoke test: launch and close an app, App Library, edit mode, Notification Center, Control Center, a banner:
  as before, no errors.
- Found while testing: Control Center closed by itself after scripted pulls (3 in 4). `adb input swipe` on the loaded
  emulator (its UWB service crash-loops every 5 s) delivered the lift where the app had last seen the finger, a quarter
  of the way down, standing still: the pull was let go short and closed. With real touchscreen events
  (`tools/kswipe.sh`) it stayed open 4 in 4. Kept from this: a pull now takes the lift's own position before it decides
  (a real finger's last pixels arrive with the lift).

## Fixes reported on the S24 (2026-10-09)

- **An icon missing for a moment after a close.** The card that had turned into the icon went once home drew a frame
  with the icon, or after 150 ms: right after home came back its draw can be later (the emulator logged 327 ms), and the
  window still held a frame without the icon. The handover now waits up to 600 ms (the card looks exactly like the icon
  meanwhile) and logs a late draw ("[home] icon handover: home drew N ms after it was asked").
- **Badges on cards from and into the App Library.** A launch or close card always took the app's count; the App
  Library's icons show none, so a count appeared and went with the animation. Home now tells gesture nav which icons
  show badges (its pages and dock); a card shows one only for those.
- **The App Library's search field looked different during launches.** In the recorded picture of home behind the
  card (no live glass there) it fell back to the tiles' glass; it uses the field's own now.
- **Edit and Done hard to read.** Their labels were redrawn only with the bar itself: a new wallpaper or appearance
  left the old colour. They now redraw then, move to a new colour on a spring, and are white (with their shadow) or dark,
  never the grey the old blend gave over mid-grey glass: dark only over clearly light glass (`sys.glass.label.*`, with a
  band in which a label keeps its colour). The Search pill on home the same (`home/GlassLabelTone`).
- **Some surfaces barely translucent.** Menus, the widget gallery's sheet, the switcher's menu, Clear All and banners
  were on the kit's Regular glass (white at 70 % in light mode, dark at 90 % in dark). Now `sys.material.glass.frosted`
  (the dock's glass, frosted as Regular, a light veil) for what sits over home's blurred backdrop, and
  `sys.material.glass.frosted-legible` (Regular with half its fills) for what floats over any app (banners, the
  switcher's menu over an app's picture). Compared on the emulator in three strengths, light and dark.
- **Banners over home drawn opaque in dark mode** (found while checking): over home they need home's blurred wallpaper,
  and "over home" came from the accessibility window events; after Home came back with a FrameLayout event the last
  event still named the app. Now from home's own resumed state.

## Step 4: slow motion for the animation audit (2026-10-09)

- `motion.debug.slow` (a token, 1 = normal): every spring's period is stretched by it (`Spring`), as iOS's Slow
  Animations; with the system's animator scale set alike, the few timed fades follow. On the emulator
  (`slowmo.sh FACTOR` writes the token edit and the scale) its recorder catches ~12 frames a second: at 8x that is about
  every real frame of our own animations. It cannot help where the recorder itself stalls (windows changing, an app
  starting: 200-600 ms gaps during a launch): those are checked on the S24's 120 fps recorder.

## Step 4: the animation audit, first round (2026-10-09, emulator, 8x slow motion)

Each animation recorded at 8x (`tools/slowmo.sh 8`), its frames checked with `tools/pops.py` (a 24 x 12 grid: a tile
whose change is far above its own change in the frames around it) and by eye at full size.

| Animation | Result |
| --- | --- |
| Launch from home, close into the icon (the card's handover to the real icon) | Clean; the icon settles with no missing frame. |
| Long-press menu on an icon: open, close | **Two defects, fixed:** the real item went at once under the lifted copy (now fades out on the menu's progress; the copy turns from pressed to clear as it lifts), and its name came back at once after the close (now fades in). |
| Home pages, home to App Library and back | Clean (finger-driven). |
| App Library folder: open, close | Clean. The tile's icons crossfade into the folder's grid over a short part of the spring (~40 ms at real speed): by design; iOS moves the big icons into their cells instead (a possible improvement). |
| Control Center: open, close | **Defect, fixed:** closing, the background blur cleared in one frame while the controls still showed (blur linear in the progress, controls on its square root): both on the same curve now. The opening's first frames cannot be judged on the emulator (its recorder stalls while the blur window appears): S24. |
| Notification Center: open, close | Clean. |
| Edit mode (from the menu), the Edit menu, the widget gallery: open, close | Clean. |
| App Switcher: open from an app, deck to home | Clean (dark cards: the emulator gives no task pictures). |
| Banner: in, out | Clean. |

Not yet audited: an app's close grabbed mid-flight, quick switches, folder to folder, Spotlight, the clock's minute,
unlock and cold-start arrival, Control Center's expanded modules and edit mode, Notification Center's swipes and stacks,
the S24's own blur. Tools kept in the repo: `tools/slowmo.sh`, `tools/pops.py`.

## Step 4: the animation audit, second round (2026-10-09, emulator)

| Animation | Result |
| --- | --- |
| Spotlight: open, close | Clean (the keyboard is the system's own motion). |
| A close touched on the bar while it flies | Not grabbed, by design (bar touches during a close are ignored); clean. |
| Quick switch sideways on the bar | Clean (the emulator has no app pictures: launch screens, then the app). |
| Control Center's expanded brightness slider: in, out | Clean. |
| Control Center's edit mode: in, out | Clean. |
| Notification Center: a stack fanning out, a swipe revealing Options | Clean. |
| The glass clock's minute (home and Notification Center) | Clean (crossfade). |
| Notification times ("now" -> "1m ago") | **Defect, fixed:** the label changed at once, and up to a minute late (its platter's cached content was redrawn on the wall clock's minute, not when its own label changed). Now the label is part of the cache key and crossfades from the old one (300 ms), on platters and on a stack's second line; a reopened Notification Center shows the current labels at once. |

`tools/sheet.py` makes contact sheets of chosen frames (with times) from a recording `tools/pops.py` has unpacked.
Still to audit: folder to folder, unlock and cold-start arrival, the S24's own blur and pacing.
- Cold start (8x, emulator): the arrival plays from black as designed. Before it, the killed process's last frame of
  home stays on screen (Android keeps its window, EXITING, until the new process draws): after an UPDATE that shows as
  home at rest, then black, then the arrival. That is the system's; ours now also takes the arrival's held first frame
  right before home's first draw (it was asked in onResume, before home had a size, and waited for a retry).
- Reopening an App Library folder right after it closed: clean. (Folder to folder could not be tried: on the emulator only
  one category has enough apps to be a folder.)
- Unlock (8x, emulator, swipe lock screen): clean; the lock screen fades as home arrives zoomed and settles. (A first
  try showed home's wallpaper black, then fading in: the emulator was locked seconds after a reinstall, before the
  wallpaper had loaded: a test artefact.)
- Audit status: every animation listed in the plan has had a pass on the emulator. Left for the S24 (its own recorder at
  120 fps): Control Center's first frames with Samsung's blur, the icon handover after closes on the phone, real frame
  pacing (the final performance pass).

## S24 test session (2026-10-09): Matheesha's four checks

Tested on the S24 itself (local builds installed with `adb install -r -d`, never `am force-stop`: see CLAUDE.md).

| Check | Result |
| --- | --- |
| 3. Holding an app vibrates twice | **Confirmed, fixed.** The vibrator's history showed two LONG_PRESS effects per hold: `View.performLongClick` plays its own haptic when the listener returns true, and ours played as well. Icons and widgets have the view's own haptic off; after the fix one effect per hold (three icon holds, one widget hold). |
| 1. Several apps opened quickly one after another (dock: Calculator, Clock, Launcher Dev, Settings; each closed at once, the next tapped during the close) | Recorded at 120 fps, checked frame by frame. **One pop, fixed:** a warm app's snapshot arriving while its launch card was already growing replaced the launch screen in one frame (Launcher Dev). Now it fades in over 160 ms (`CardView.fadeToSnapshot`; also for a fresher picture replacing a stale one mid-gesture). Everything else clean: a close touched by the next tap fades its card where it is while its icon glides into its slot (one icon, no jump). |
| 2. Settings opened from Control Center | **Three defects, fixed.** (a) The card stayed full screen, Settings untouchable under it, until the 2.5 s timeout: the shade's focus window held the focus, and Settings' existing task brought back sends no window event at all (also not from the dock). The launch now lets the focus go, does not count home taking it, and once the card is full asks the system for the task in front (every 80 ms): the card went 458 ms after the tap. (b) On the tap, Control Center's live background turned black in one frame (the app came up behind the panel and the system's blur showed it): our still picture of what was behind now covers the live blur (120 ms fade) before the app is started. (c) The status bar jumped from Control Center's row to the app's bar when the panel closed under the card: it now follows the card's growth (as a swipe close). |
| 4. The widget gallery's opening lags | **Partly fixed, not done.** Traces: the render thread spent 4-12 ms a frame allocating GPU images. The renderer's GPU cache budget on the S24 is 121 MB (`dumpsys gfxinfo`: "Max resource usage"); home at rest already held ~105 MB of render targets, and the open gallery needed more (122.9 MB): every frame freed and allocated images. Changes: the search field reuses the sheet's kept blur of home instead of its own full-screen blurred copy (it was also re-blurred every frame of the rise); kept content is blurred at a quarter of the resolution (the frost is wide: nothing lost, checked on the emulator for the sheet, the field and the App Switcher's Clear All); the sheet's node keeps its size (a node around the growing sheet was a new layer every frame) and the glass shader returns at once beyond its shadows. Gallery open: 113 MB. Interleaved A/B against 4b7c7f8 (3 rounds x 2 openings): GPU time per frame 25 % lower (median 15.6 vs 21 ms), missed refreshes the same (~11 both) — but the phone was hot (thermal status 1-2, GPU medians twice those measured cool), so the timings are not conclusive. Still allocating ~30 images a frame (offscreen layers of the effects re-applied as the sheet moves): next step. |

Found on the way:
- **Empty home after an update while an app was in front** (seen here, reproducible): the new process started under the
  app, the first way home was a close, the cold-start arrival held its first frame (items hidden) and, finding a card over
  home, never played or let go. Now a cold start whose first showing comes from gesture nav (`HomeBridge.homeStartedAt`,
  within 3 s) has no arrival, and a cold arrival that finds home busy retries for a second, then lets go at rest.
- Gesture nav lost after a force-stop: `am force-stop` disables the accessibility service; a race between its unbind and
  a queued attach left stock gestures blocked with no strip. The attach of an unbound service is now dropped.
- `DESIGN_RELOAD` broadcast (`am broadcast -a dev.launcher.app.DESIGN_RELOAD -p dev.launcher.app`): the app rereads its
  token edits (`files/design/user.json`) in place, for experiments on the phone without a reinstall.

## Second round of Matheesha's reports (2026-10-09)

| Report | Result |
| --- | --- |
| A widget's corners turn sharp for a moment when it is resized | **Reproduced on the S24, fixed.** The copy lifted above the widget's menu was recorded into a software `Picture`, which ignores the card's rounded outline (and drew the glass clock without its numerals, and a One UI widget lighter than it is). Menu copies are now GPU render nodes (`ContextMenuView.record`); the drag copy in edit mode clips itself to the card (`WidgetFrameView.drawCard`). And choosing a size from the menu used to resize the widget unseen under the old-size copy, which then settled back and the widget jumped to its new size: now the copy fades into the widget as the menu closes and the widget springs to its size in view (`ContextMenuView.handBack`, also for a style change). The widget's app answers a resize with its new layout late (here up to seconds): that layout now crossfades in (220 ms) instead of replacing the content in a frame. |
| (found with it) A stall right after an animation starts made it jump | The size tap stalls the main thread for 15-110 ms; the menu's close spring ran on the wall clock and its first frame after the stall showed menu and blur 80 % gone. `SpringValue` now has its own clock, advanced at most 34 ms a frame: after a stall the motion goes on from where it was. |
| Are banners live blur? Sometimes they are opaque | Not live: One UI blurs only whole screens behind a window. Over home the banner's glass is the wallpaper blurred ahead of time; over an app, gesture nav's latest picture of it, blurred when the banner comes. Measured on the S24 (log only, our own test banners): the picture is ready 9-29 ms after the banner starts, so every banner over an app came in on the plain opaque fallback and faded to glass (180 ms). Banners now wait for their glass (at most 150 ms) and come in as glass; over home the same for the wallpaper's blur (not ready just after a start or a new wallpaper). Still opaque where no picture of the app can be had (apps that block screenshots). |
| App Library folders: a more fluid opening than the fade | Done: the icons the tile shows (its large ones and the small ones of its fourth slot) fly from their places in the tile to their cells and grow with the panel; the other apps come in where they belong; names come at the end; closing is the same backwards (checked in slow motion on the emulator). |
| The widget gallery still lags at its first opening after a start | Traced on the S24 after an update: its first frame compiled GPU pipelines for 141 ms (one path draw alone 89 ms; Android empties the pipeline cache with every update), and the first frames recorded 6-7 ms each (icons and text met for the first time). Home now draws the gallery's header and list once off screen while idle after a start (`WidgetPicker.warmUp`, `design/GpuWarmUp`: 163 ms off the UI thread on the emulator). Not yet measured on the S24. |

## Third round (2026-10-09 evening)

| Report | Result |
| --- | --- |
| The widget gallery's first opening after an update still lags | Measured on the S24 after an install (CI build 291e6f7): the warm-up removed the 141 ms frame, but every frame of the rise still took 17-30 ms (also the second opening): home's own blur (a RenderEffect on the whole scene, re-blurred at every frame of its ramp) plus the gallery's layers. Home's blur behind a menu or the gallery is now a quarter-size copy of home, blurred at that size and faded in over it (`home/SceneBlurView`); looks the same (emulator, side by side). S24: first opening 4 missed refreshes, later ones 3-7 (were 6-14), GPU median 10 ms (budget 8.3: the glass itself, for the performance pass). |
| Banners must have a live blur | Done on One UI: `SemBlurInfo` in window mode on a plate behind each banner (the system blurs what is behind, live), its colour curve set from the material (brightness map and the colour a luminosity fill keeps), the glass drawing only its own layers over it. Checked on a probe card over home next to the old glass (not on a banner: they are not captured on the phone): brightness 70 vs 61, saturation 0.51 vs 0.57. A test banner (log and frame stats only): over home 0 missed refreshes, GPU 1.3 ms a frame; over an app 2 missed while at rest (to look at). Where the system has no such blur, banners keep the glass over a blurred picture. |
| Dragging icons to and from the dock | Checked on the emulator (4x slow motion). **Two defects, fixed:** dragged from the dock onto a page, the icon's new view faded in under the dragged copy (two icons for a few frames): a view the drag holds is tagged and skips its arrival; and the remove badge appeared at once when the copy landed: it grows back in. Into the dock: the others make room smoothly, clean. |
| Home folders | Not built yet: the layout model has folders, nothing makes or shows them. |
- Widget corners while resizing by dragging (Matheesha): **reproduced on the S24 and fixed.** The resize crossfades from a
  recording of the widget's old look, made as a software `Picture`, which ignores the card's rounded outline: the old card
  faded out with square corners. It is now clipped to the card's corners as it is recorded (frame-by-frame before/after on
  the S24: square, then rounded). Found with it: a 4-wide widget's resize handle lies in the S24's right-edge back-gesture
  band; dragging it became Back (edit mode ended, the resize stopped half-way). Edit mode keeps its handles out of the back
  gesture now (checked: 4x2 to 2x2 from that handle on the S24).

## Step 5: the theme file (2026-10-09)

Themes are files: the app's own in `assets/themes/`, and `files/themes/<id>.json` (pushed over adb with run-as, or later
made by the builder), which wins over an app theme of the same id. A theme can be built on another (`"extends": "ios27"`)
and hold only what it changes. Every theme is checked as it loads: every token resolves, and every token iOS 27 has (the
ones the code reads) is there with the same kind; a theme that fails is refused (the reason logged) and the current one
stays, so a bad file never breaks drawing. The user's token edits are kept per theme; the active theme is remembered.
Switching: the token editor's Theme button, or `am broadcast -a dev.launcher.app.DESIGN_RELOAD -p dev.launcher.app --es
theme ID` (without `theme`: the files are read again, for iterating on a pushed theme).

The test theme `graphite.json` (20 tokens over iOS 27): solid graphite surfaces instead of glass, a green accent (iOS's
blue overridden at the `ref` level, so every control and link that is blue follows), tighter corners, dark symbols and text
on its light surfaces in light mode. Checked on the emulator, switched live, in dark and light: Notification Center,
Control Center, a banner, home's dock, Search pill, menus, Spotlight's field, edit mode's buttons, the App Library. Things
the theme had to set that a theme author would not guess: Control Center's symbols and names are white in both modes in
iOS 27 (white on dark glass), and the player's text is added to the glass (plus-lighter): on a light surface both need a
theme's own values (`comp.cc.symbol-color`, `comp.cc.module.label-color`, `comp.cc.media.text-blend`).
Not themable yet: the glass clock's numerals (GlassDrawable), layouts (step 6 and phase 5).
Checked on the S24 too (Control Center over Samsung's live blur, home): solid modules over the blurred background, the
accent green. A first version turned the "on" plate green too: a toggle's own colour (rotation lock's red) then sat on
green; the theme keeps iOS's white plate.

## The mini player, round 5, and a performance pass (2026-10-13, S24)

Matheesha: the mini player's move from landscape to portrait is not animated; the mini player in the App Switcher is
"absolutely broken"; fix every other issue found, test everything again, look for performance issues.

- **The mini player in the App Switcher.** Its hole follows SurfaceFlinger's window information (`WindowWatch`; the
  finger-following of round 3 guessed and jumped). On the S24 a report comes every refresh (every 8-9 ms, 0-4 ms to our
  thread). Measured against a recording of the test app's fixed picture, frame by frame (`holelag`): the hole's centre is
  2-3 px from the mini player's while it moves (spread 7-10 px at 500-2,000 px/s), drawn a little inside it while it
  moves, so no strip of home shows beside it; from the touch-down the hole eases inside (it hides the touch slop's jump).
  A recording halves the report rate; the start of a fling that is not held first still trails for 2-3 refreshes.
- **Its cost.** Moving the hole made the card window draw whole every frame (GPU 10.5-10.9 ms median a frame, 110 of 120
  frames over 8.3 ms): the hole is now a small view moved by translation, only the area it leaves and enters is drawn again
  (7.1 ms median, 1 frame of 120 over 8.3 ms, none late). Clear All's glass ran its shader over a screen-sized node every
  frame the deck moved: now a node around the button (652 x 423 px) drawn again unchanged while nothing about it changes.
- **The move at the display's turn cannot be animated by us**: the mini player is Android's window, and from the moment
  home starts until the display has turned Android hides every window of ours; One UI's crossfade is the spin, a seamless
  rotation moved it the same way. It jumps at the cut (One UI's own home not compared).
- **Found and fixed: quick switch to the app already in front.** A quick flick sideways can come before the gesture's task
  lookup (~50 ms): the run was built from the previous gesture's order and "one older" was the app in front (the switch
  scenario stopped on its 4th round). Now 16 switches in a row land right, 0-2 missed refreshes each.
- **Test tools.** `PipTestActivity` (Phone, Developer, "Mini player test"): an app with a mini player showing a fixed
  bright picture, entered when left (as YouTube) or at once (`--ez pip true`), portrait or landscape. Android refuses an
  app's own entry while another app's mini player is up. The scenario scripts' icon templates (tools/shots, not in git)
  had to be cut again (the App Library and dock look changed); `scenario_nc_scroll.sh` accepts the focus window.

Performance pass (S24, frame stats of what the screen showed; missed refreshes per step, GPU median ms):

| scenario | result |
| --- | --- |
| quick switch (16) | 0-2 missed each; GPU 2-3 (one run 8) |
| App Library launch / close (3+3) | launches 0; closes 0-1 |
| Spotlight close (3) | 0-1; GPU 2-2.6 |
| home's motion | page to App Library 2-3; library scroll, list open/close 0-1; folder open 0 (GPU 12-16); folder close 0-1; App Library to page 1 by the bar 1-6; by a swipe 0; Spotlight 0; pull on home 0 (GPU 12) |
| launches and closes | folder, dock, grabbed launch, sideways from home: 0-1 |
| App Switcher | open over an app 2-5 (GPU 10-11, one 27-34 ms frame); a card opened 0-2 (GPU 12-16); home 0; open over home 0 (GPU 6-7); long deck scroll 1; flick away 0 |
| shade (3 runs) | Control Center open/close/edit 0-1 (edit out GPU 13-14); Notification Center open/close 0; banner 0-1 |
| Notification Center, long list (3 runs) | open 0; stack fanning out **2-18** (GPU 10-16); scroll 0; close 0 |

Not fixed, next for the performance work: the stack fanning out (worse than on 10-08: 0-2), the App Switcher opening over
an app and a card opening from it (GPU over the 8.3 ms a frame has; its first frames import the cards' pictures, ~8-12 ms
on the render thread), page to App Library (2-3). Thermal status 0 throughout (skin 37-38 C).

## The App Switcher in its own window under the mini player (2026-10-14, S24 and emulator)

Matheesha: the mini player over the App Switcher was "not good at all"; is there really no way to bring it in front of the
deck instead of cutting a hole for it? There is: every overlay of ours lies above a mini player, an activity's window
under it (as Android's own recents do). "Quality is a high priority: even the slightest mistake or gap breaks the
immersion."

- **What it does.** While the switcher is open with a mini player up (Android 14+), the deck moves from the card window
  into the window of a translucent activity of its own task (`switcher/SwitcherActivity`), which shows a
  `SurfaceControlViewHost` drawn by gesture nav's thread (`switcher/SwitcherHost`): the mini player floats over the deck
  as over any app, with its shadow, its controls, its dismiss target and Android's own expand. The move is one frame of
  both windows (`SurfaceSyncGroup`), any time, mid-animation; the deck goes back the same way when an app or home is
  chosen, and the leaving runs in the card window as before. The window is started with "no user action" and stays
  translucent: the app under it keeps playing and does not go into a mini player of its own (YouTube, checked earlier).
  Back in that window works as the bar's swipe (the app last used, or home). The app expanding from its mini player
  grows over the deck; the switcher closes once it is covered. Until the window shows (about 0.1 s) and on Android 13
  and older the deck stays in the card window with the hole, as before.
- **Found on the S24 and fixed.** (1) The background's dim snapped off at the handover (brightness 150 to 183 of 255 in
  one frame, then undimmed): the dim was the window container's background, under the picture of home it holds; it is a
  view above the picture now, as in the card window. (2) The deck's last frame at rest jumped a pixel onto its place
  (its spring rested within 0.005 of a card, ~2.5 px): thousandths of a card now. (3) A frame repeated at the handover
  in 3 of 4 openings from home: the activity's empty window allocated a screen-sized buffer on the render thread for
  each of its first frames (3.8 ms; a window holding a SurfaceView does not allocate ahead), and the deck's frame waited
  behind it. The activity is drawn in software now (its frames never reach the render thread), the deck's surface is in
  it before its first frame, and "on screen" is its finished opening transition. (4) Asking for the window cost the
  opening a dropped frame (a 4 ms relayout on gesture nav's thread and an 11 ms first frame on the render thread): the
  window's content is drawn ahead while a mini player is up and nothing moves, its copy of home blurred only.
- **Measured on the S24** (test app's mini player; harmless recents only: Clock, Calculator, Settings, Galaxy Store,
  Galaxy Wearable; nothing captured of other apps). Window on screen 103-164 ms after the switcher opened. Openings over
  an app and from home, 10 runs: 0 dropped frames in 6 (both unrecorded runs among them), 1-2 in 3 (recorded, two of
  them also traced), and once a 94 ms freeze (the first recorded run after installing; not seen again). Before: 1-3 in
  most. No repeated frame at the handover from home in 2 recorded runs (before: 3 of 4); background brightness
  continuous through both handovers. Unrecorded, the whole run (open, scroll, mini player dragged over the deck, a card
  opened): 0 missed refreshes, card window GPU median 5.3 ms. SurfaceFlinger's composite after the handover: median 2.2
  ms (with the hardware-drawn window: 7.6 ms). The plain switcher (no mini player, `scenario_switcher.sh`): 0-1 missed
  per step, as before. Screen recording adds SurfaceFlinger work (composites of 9-12 ms before the handover): judge
  timing from unrecorded runs.
- **Not on the S24**: Clear All in that window and the screen turned off with it open (they would close Matheesha's apps
  and lock a secure phone): both checked on the emulator. YouTube's own mini player with this build: to be checked.
- **Known**: SurfaceFlinger's composite takes 6-8 ms on the S24 whenever the App Switcher is open in the card window
  (several screen-sized layers, our full-screen status bar window among them); while a mini player is up, the window's
  content holds a few screen-sized buffers.

## Home Settings slides home into the settings; the settings in the App Switcher; the edit menu closes first (2026-10-14, emulator)

Matheesha: add Launcher Settings to the App Switcher; "Home Settings" in home's edit menu should slide home into the
settings' preview instead of opening a separate app; and the edit menu closed at once, without its animation, for
"Change Wallpaper" and "Home Settings" ("it is noticeable").

- **The edit menu closes first.** A menu item may open what covers home only once the menu has closed
  (`MenuPainter.Item.afterClose`, run when the menu is within 1 % of closed); opened at the tap, home went out of sight
  mid-close and the menu was gone at once. "Change Wallpaper": the menu closes into the Edit button, then the picker's
  card grows out of it. "Home Settings": editing ends with the menu (the lifted Edit button leaves with the bar,
  `ContextMenuView.liftedFollows`), and once home is at rest (no menu, the bar gone, no icon wiggling, nothing moving)
  the settings open. A new touch that leaves home moving at the deadline (700 ms: a swipe, edit mode again) cancels it.
- **Home slides into the settings** (`settings/SettingsEntry`, `EntryState`, `SettingsActivity.beginEntry`). Home
  pictures itself as it shows, our status bar on it (drawn together on the GPU: `ViewPicture.draw`; the old CPU copy read
  the picture back from the GPU, 200-570 ms on the emulator), before the settings start. Their first frame waits for
  it and shows it over the whole screen, cut to the screen's own corners and without the preview's rim and shadow
  (which fade in over the first 30 % of the slide; at full size they drew a dark frame round the screen). Once the shell
  saw the opening committed (or the opening transition finished, or 400 ms after that first frame was drawn) one spring
  (`motion.settings.open`) shrinks it into the picture above the sheet as the sheet rises from below the screen and the
  title comes in. The status bar keeps home's tone while the picture is under it: our bar is told mid-slide
  (`GestureNav.ownScreenTone`) and the window's own flags change at the slide's end (a change of them is a relayout:
  a frame of the slide); readings of our window meanwhile are not taken.
- **Back on the tiles slides back** into a fresh picture of home (its clock as it is now). At the end the card window
  holds that picture over everything (`GestureNav.ownScreenToHome`), the settings go back at rest underneath (that frame
  is their App Switcher card), home comes to the front without an animation, and once it has drawn the picture goes.
  Back during the slide in turns it round from where it is and how fast it moves. Left half-way (a swipe home) nothing
  is covered later; a new opening from home stops a slide still playing (`EntryState.jump`).
- **The settings in the App Switcher**: the shell's recent tasks name each task's activity now; our own tasks stay out
  of the switcher and the quick switch, except the settings' (matched by class, and by task id where the package is ours).
- **Checked on the emulator** (the S24 was not connected; the host was busy, so emulator timing says little: the slide
  drew 6-12 frames). Recorded frame by frame: the settings' first frame matches home (only the emulator's stock status
  bar chip differs), the slide back's last frame, the cover and home itself line up to the pixel, no dark frame at
  either end. Back during the slide in, Back from the settings restored from their card, the settings opened plainly
  (from the drawer: Back finishes them, no slide), Change Wallpaper (menu closes in ~0.3 s, then the card). Logs per
  opening: "[settings] their first frame ... drawn", "home slides in (why) N ms after they opened", "home slid in/out:
  N frames in M ms, longest ...".
- **To check on the S24**: the slide's smoothness (frame stats of the settings window), the delay from the tap to the
  slide (the emulator: 0.5 s warm, 1.0-1.4 s after a start, most of it the settings' first composition), the
  switcher card's picture (the emulator has no task snapshots for the shell), Samsung's wallpaper picker after the
  menu's close. Seen on the emulator only: Pixel's wallpaper picker starts a second activity and shows 2 s late, and our
  launch card gives up before it (home shows in between); the existing launch path, not this change.

## Matheesha's round of 2026-10-14: the freeze after Home Settings, Material You in Control Center, Change Wallpaper

- **Home froze after Home Settings and a swipe back** (S24 log 02:38). Two causes. (1) The card window is made again when
  gesture nav's service restarts (the S24's did at 02:33:50); the cover that holds home's picture as the settings hand
  back was made once and stayed in the old window: it never showed, the hand-back waited for its frame, timed out and
  never brought home, and the settings sat on their last frame (home's picture) taking no touch. The cover is now made in
  the card window it is shown in (and dropped with the window); it counts as on screen only once a frame that drew it is
  committed; if it is not on screen within 350 ms home comes anyway, a timeout brings home if nothing else did, and the
  settings slide back in if home has not come 3 s after the slide (never stuck on home's picture). Reproduced on the
  emulator (gesture nav restarted, then Home Settings and Back) and fixed. (2) At 02:38:01 the minute changed: home declines
  to picture itself while its clock crossfades, the settings were told "no picture" and set themselves at rest, and the
  jump to home's end asked for at the start of that opening was played after it: they stayed on a full-screen picture of
  home. Home's picture is now asked again every frame for up to 0.9 s (home also waits for a clock's change before it
  counts as at rest), and opening at rest is a jump too (the last one asked for wins). A settings screen made again (a
  configuration change, the process restored) no longer takes its first intent for a new opening from home (it held its
  first frame 700 ms for a picture that never came).
- **Material You in Control Center**: controls that were on looked off, their symbols dim, those off bright (dark mode
  worst). A surface takes at most N fill layers; the palette's tint adds two to every glass, so a lit well (its module's
  four, its own five, the accent) lost the accent: every "on" well drew as off while its symbol turned to the "on"
  colour. The renderer takes 12 now (and says once in the log if a surface ever has more). And Control Center keeps one
  look in both appearances, as iOS's does: in the wallpaper's colours its accents are tone 50 of the palette's primary (a
  white symbol on them; red stays the light-mode error colour), its lit controls stay light with dark symbols, and its
  glass takes the dark wash in both appearances (the light wash paled the wells under their white symbols). Checked on
  the emulator in light and dark, wallpaper colours and the theme's.
- **Change Wallpaper** opens the picker at once out of the Edit button, the menu kept as it is under the growing card
  (`MenuPainter.Item.keepsOpen`); behind the card, home exactly as it shows, the menu and its blur included (a GPU render:
  the recording of home leaves the menu and blur out, and the card's first frame showed home without them). Home closes its
  menus, edit mode and searches only once it is out of sight (onStop; at onPause a launch still shows home under its card:
  the menu vanished as the card began), edit mode at once there, and if that changed how home looks its pictures are taken
  again, so the close back from the picker shows home as it will be (no menu, no edit mode). Recorded on the emulator,
  opening and closing.
- **The mini player's corners in the App Switcher** changed whenever the deck moved between the card window and its
  own window: the card window's hole for it was cut at 16 dp (AOSP's `pip_corner_radius`), One UI 8's is 12 dp
  (`cmd overlay lookup com.android.systemui com.android.systemui:dimen/pip_corner_radius`; 31.5 px at the S24's display
  size against our 42). The hole and the card flying into a mini player take SystemUI's own value now (read again when
  the display's size setting changes). Not yet seen on the S24.
- **The settings are upright only** (as home, and iOS's Settings on an iPhone): turned to landscape their picture of
  home was squeezed into a wide phone and Back slid that into home.

## iOS's tap and hold, control by control; pictures in Notification Center (2026-10-14, emulator)

Matheesha: "check how each button behaves in the qs panel of ios - one tap, long press etc. and make sure ours align".
iOS 27's Control Center was captured in the Simulator (`test70_ccEveryControl`, `test71_ccParts`: a fresh simulator's
Control Center is empty and its gallery has no system controls, so most of it came from Apple's iPhone User Guide for
iOS 27). Differences found and fixed (table in docs/IOS27_EVALUATION.md, "Control Center"):
- **Power**: held until the phone vibrates (a tap did open the power menu).
- **Camera held**: iOS's menu (Take Photo, Take Selfie, Record Video), the menu component (`MenuSpec.CC`: the modules'
  glass, so Material You's tint follows it; `comp.cc.menu-gap` 10 pt) growing out of the held control, which lifts while
  the others and the status row fade back. With the finger still down a row is chosen by sliding onto it (a tick on each)
  and lifting; lifted elsewhere the menu stays for a tap; a tap outside or Back closes it. Rows only for what the camera
  app takes. Checked on the emulator: hold, slide to Take Selfie (the camera opened), tap outside, Back.
- **Now Playing tapped** grows into the large player (it opened the app).
- **Wi-Fi held in the expanded connectivity module**: Android's Internet panel (iOS shows its networks there).
- Not changed: Wi-Fi and Bluetooth turn off when tapped (iOS only disconnects them for a while; Android has no such call
  for an app).

**Pictures in Notification Center**: a notification with a picture (a sender's photo) showed the app's icon in its
platter while its banner showed the photo. A picture loads after the platter's first draw, and the platter's recorded
content was not drawn again when it arrived. It is now (the recording's key includes whether the picture has loaded).
The adb test notification takes `--ez avatar true` (a 4:3 gradient, to check the crop and the rounding).

**Pictures drawn rounded** (artwork, photos: `design/RoundedBitmaps`) keep one shader per picture with only the scale for
its size in its matrix, the canvas moved to the place: a new shader and matrix per draw were native allocations every
frame for every photo while a list scrolled.

**Search fields with a hardware keyboard**: Enter in Spotlight launched its top hit twice (a hardware Enter calls a
field's editor action on its key down and again on its key up; the second start reversed the first's card). Spotlight,
the App Library's search and the widget gallery's take one action per Go or Enter now (`components/onGo`). Checked on
the emulator: one launch.

**Home's gestures beside the pages, against iOS 27** (Simulator capture `test72_homeGestures`): holding the Search pill
enters edit mode with the pill turned into page dots (no scrubbing), a swipe across the pill turns the page, a tap opens
Spotlight; ours does the same (checked on the emulator). A swipe right on the first page opens iOS's Today View: we have
none (a page of its own, to plan with Matheesha).

**Later the same morning (emulator)**: the shade's app icons follow home's (the system's icon was kept for the whole
session when the shade drew before home had rendered the app's; the platter is recorded again when its icon changes);
the theme cards keep only pictures of one of home's pages (opened from the App Library, a card showed the library); no
rectangles made per frame in Spotlight's top hit, the App Library's tiles, the resize handle, Control Center's expanded
module or the widget gallery; two always-true checks removed (no compiler warnings left but Android's deprecations).
Walked through on the emulator, tap by tap: home (icon and dock menus, edit mode and its menu, Spotlight, App Library,
search, launches and closes), Control Center (every control's tap and hold above, edit mode, the gallery, add and remove),
Notification Center (fan out, swipe to its buttons, clear, long look, open, a group's two-step Clear), banners (tap,
swipe away), the App Switcher (open from an app and from home, scroll, a card's menu and Close, flick away, Clear All),
the settings (every tile, Back, a theme applied and back, opened from the App Library), Home Settings and Back (key, edge
swipe, after gesture nav's service restarted).

**The mini player turning upright mid-swipe from a landscape app** (Matheesha: One UI's own home does it): not possible
for us (see CLAUDE.md: One UI's launcher moves the app's own window through an interface only the system's recents
launcher gets; Android hides our windows while the display turns, ~180 ms on the S24 between home's start and the turn
in round 5's recording, the mini player still in its landscape corner then). Flying our card upright would leave the
real mini player showing in its old corner for those frames.

## The mini player in the App Switcher, once more (2026-10-14, S24)

Matheesha: dragged fast, the mini player still showed home around it in the App Switcher. His log: the App Switcher
opened from home at 12:29:40 moved its deck into its own window under the mini player (as built on 10-14), but opened again
1.2 s after that session ended (12:29:43) it never asked for the window: the window's host for the next session is made
0.8 s after a session ends and only when nothing moves, and it was not there yet. That whole session kept the deck in the
card window, the mini player showing through the hole that chases it, and a fast drag outran the hole. Now a session
that opens without a host has it made once the deck rests (14 ms on the S24) and moves the deck under the mini player
then, mid-session (the last session's host is waited for if it has not let go yet). S24, local build: opened again 0.9 s
after the last session, the deck was in its own window 123 ms after the host was made; two fast drags of the test app's
mini player across the Clock card, recorded: only the deck around it in every frame, nothing of home.

## Performance pass (2026-10-14, S24, build 48f4c2d + local)

Missed refreshes per step (what the screen showed, `tools/framestats.py`), against round 5 (2026-10-13):

| scenario | now | round 5 |
| --- | --- | --- |
| quick switch (10 + 10) | 0-1 each | 0-2 |
| App Library launch / close (4 + 4) | 0-1 | launches 0, closes 0-1 |
| Spotlight launch / close (3 + 3) | 0-1 | 0-1 |
| page to App Library | 3 | 2-3 |
| library scroll, list open / close | 0, 0, 1 | 0-1 |
| folder open / close by the bar (5 runs) | 0 / 0-2 (one run 6) | 0 / 0-1 |
| App Library to page 1 by the bar / by a swipe | 3 / 0 | 1-6 / 0 |
| Spotlight open / close, pull on home | 1 / 0, 0 | 0 |
| launches and closes (folder, dock, grabbed), cancel, sideways | 0-1 | 0-1 |
| App Switcher: over an app / a card opened / home / from home | 2 (one 32 ms UI frame, as before) / 0 / 0 / 1 | 2-5 / 0-2 / 0 / 0 |
| long deck scroll, flick away | 3, 0 | 1, 0 |
| Control Center open, close, edit in / out, Notification Center open / close, banner (3 runs) | 0-1 each | 0-1 |
| Notification Center's stack fanning out | before: 17, 0, 0 (and 23, 13, 4 in other batches); after the change below: 0, 1, 0, 0, 0 (the first after new notifications: 10) | 2-18 |

**The stack fanning out**: a trace showed its first frame drawing every notification the stack gathers (39 platters' glass
and content at once: 25-92 ms on the shade's thread; the platters' glass itself was not the cost: with a bare material
the GPU time and the misses were the same). Two changes: a notification lying wholly behind an opaque one in front is not
drawn (`NotificationCenterView.hidden`, a rounded-rectangle containment test, conservative at the corners), and while
Notification Center rests open and collapsed the gathered notifications have their content recorded ahead, three a frame
("[nc] recorded N notifications ahead in M ms": 7-14 of them in 17-34 ms; 58 with the phone's own list). The first frame
of the fan-out took 16.9-18.5 ms on the shade's thread after this (traced). Still open: the first fan-out after an update
(the app's code and fonts read from disk, `folio_wait_bit_common` in the trace) and framestats' "GPU ms" near 16 ms in
every frame of it (it counts from the frame's issue to the GPU's fence, buffer waits included: the same with a bare
material, so not the glass).


## Motion: presets and fine-tuning (C4a, 2026-10-14, emulator)

The settings' Motion page (plan: `docs/PLAN_MOTION.md`): preset cards playing the curve a banner drops in on, the
picture's demo with a banner after the app, Speed and Bounce for each preset (applied everywhere a curve is read:
`MotionAdjust`), Reset all, and a second real preset, Material (Android 16's springs, bouncing only where iOS 27 does).
Developer gained "Test animation presets" and "Slow animations". Checked on the emulator (details in the plan), 96 unit
tests. On the S24 (fd7c240): Motion at rest a steady 120 fps, its controls 0 missed refreshes, Speed 200 % shortening the
settings' own slide from 73-78 frames to 43-47, Material's shade and launches 0-1 missed; the settings' page slides miss
more at 200 % in their first frames (layout and recording every frame on every page: open, for C4b). The Apply label
under wallpaper colours in dark mode is now the palette's on-primary.

## Motion: every animation (C4b, 2026-10-14, emulator and S24)

Motion's groups as pages pushed inside the sheet (the picture focusing where the group plays), each animation with its
editor in place: Spring | Curve, a graph with handles that move by the finger, Duration and Bounce, shapes, Slow, Reset,
Done; numbers with their own sliders; the Apps group's samples on home's picture, stand-ins for the rest. Details and
measurements in `docs/PLAN_MOTION.md` (C4b). On the S24 (debug build): pushes and pops 0 missed refreshes once a page is
built (its first push builds it: one 80-96 ms frame before the slide), an editor builds in one 70 ms frame before it
opens, drags run at 60-80 fps (each step 13-16 ms; 24 before per-token state in the draft). The same code without
debugging builds pages and editors in less than half the time (42 and 33 ms): a question for Matheesha. Fixed on the way:
the demo's crash on icons of no size after a start, the settings' picture of home cut off after a cold start (`ViewPicture`
now waits for the GPU's fence), a small bounce's handle past the graph's end, and a pipeline compiled inside the first
editor's frame after every update (warmed while home is idle). 100 unit tests.

## Motion C4b on the S24, and the editor's drags (2026-10-14)

The CI build f3eb85a on the S24, then fixes measured there (details in `docs/PLAN_MOTION.md`, C4b): the editor reads its
curve where it shows (drawn values and knobs, layers for the graph and sliders), so slider drags went from 60-80 fps to
~110 (9-12 missed refreshes a second; the handle 1; 0-1 not debuggable); a row is one drawn node, so a group's page
builds in ~90 ms instead of 139; the pages fade draw by draw with their pipelines warmed while home is idle, so the
slide's first frame no longer allocates buffers (24 ms of the render thread before, 8-14 now) and nothing compiles inside
a frame after an update; the header's titles hand over without crossing. The phone was warm (CPUs capped at 2.0 GHz).

## The build is not debuggable; One UI 9 on the S24 (2026-10-14)

- **Not debuggable** (bccbcaa, Matheesha's call): CI ships `assembleDebug` with `isDebuggable = false` and the libraries'
  baseline profile as `Launcher.dm`; UPDATE installs both in one session. Checked on the S24: 531dda1 came in through
  UPDATE as `speed-profile [reason=install-dm]`. The app's files go in and out through its external folder (no run-as):
  `DESIGN_RELOAD --es files import|export|remove`.
- **One UI 9 (Android 17) broke two things** (Matheesha's phone updated the same day; c1e6372): app pictures (every App
  Switcher card, close card and warm launch showed the app's launch screen: Android 17 moved task snapshots into
  `ITaskSnapshotManager` and deprecated `TaskSnapshot.getHardwareBuffer()`, the buffer now read from its field) and Control
  Center's live blur (our overlay window gets no dim layer any more, so the dim turned into a blur showed nothing; Samsung's
  view blur on a full-screen view now). Checked on the S24 (recordings of Calculator's launch and close, SurfaceFlinger's
  blur region with Control Center open, the App Switcher's 27 pictures in the log) and on the Android 17 emulator (the deck
  shows real pictures there too). Every other hidden call we make still exists in One UI 9's framework. Not reproduced yet:
  the App Library's search field after a pull of the shade (pulls of both panels and from the middle over the App Library
  left it as it was). Not checked: a pull over an app (needs a real finger), picture-in-picture.
- **Smoothness on One UI 9, not debuggable** (c1e6372 installed through UPDATE, phone cool: AP 47 °C, CPUs uncapped;
  missed refreshes per animation, from what the screen showed): App Library launches of Calculator 0, 2, 0, 0 and closes
  into its icon 0, 1, 0, 0; Spotlight launches closed to the centre 1, 0, 0; quick switches Clock and Calculator 0, 1, 1, 0,
  1, 1; App Switcher in 1, a card opened 0, home 0, in from home 3 (one frame of 22.6 ms GPU), opened from home 0; shade
  (two runs) Control Center open 0/0, closed by a tap 1/0, edit in 1/1, out 0/0, closed from the bar 0/0, Notification
  Center open 0/0, closed 0/0, a banner 1/2; the settings app: Motion opening 0, the Speed slider dragged 0, a scroll 0,
  Control Center's group pushed the first time 0 (its build's longest frame 11.8 ms of CPU; one 80-96 ms frame on the debug
  build), popped 0, pushed again 2, an editor opening 0, its Duration dragged 1 in 114 frames (9-12 a second on the debug
  build), back to home 0. The update itself: the APK (18 MB) and its profile (7 KB) installed in one session.

## Opening apps one after another: the landing card (2026-10-15, emulator; S24 not checked yet)

Matheesha: "try opening apps one after the other really fast and you can see there are duplicate icons sometimes and it
doesn't feel 120 fps". Two causes, both in a tap on home while a closing card was still flying:
- The touch made the card fade where it was and showed its icon at once: two of the same icon until the next app's card
  cut the fading one off. The next launch then started home's picture sharp, so the blur of the close snapped away and
  came back.
- Now a touch only hands home over from the picture to the real home (once home has drawn a frame of its own, never on a
  short timer: a home busy coming to the front shows its last frame, at rest and sharp). The card keeps landing; only a
  drag (pages, the App Library's list, Spotlight: its icon moves) makes it fade where it is, as before.
- A tap on another app hands the closing card to a second card, the lander (`GestureNav.landingFor`): its own springs
  continue the flight (same roles, from where it is and as fast as it goes), drawn zoomed with home's picture as home
  recedes again behind the new app, so it lands where its icon shows. Its icon (and badge) stays out of the new picture
  through a patch from the picture without it (`PreviewView.setPatch`: a piece of another picture inside the icon's
  rectangle, on the quarter-size grid so the two meet on pixel edges, rendered only into layers that show). Over a sharp
  home it hands over within a pixel; over a home already blurring again it dissolves into its blurred icon over its last
  pixels (110 ms). A tap once the card is in its icon needs none of this (the new picture shows the icon there).
- The new launch starts home from the depth it has (no snap), and from a home still blurred the tapped icon stays in the
  picture, blurred like home, while the new card comes in over it as it grows (opaque at 1.4 times the icon); the picture
  without the icon goes on once the card covers it. Before, the tapped icon snapped into focus in one frame.
- Checked on the Android 17 emulator in slow motion (4x and 10x: there home takes over a second to come to the front, so
  at full speed every tap arrives after the card has landed): a hand-over early in the flight, one late, one after the
  landing; a page swipe during a close (the card fades where it is); reopening the closing app (it reverses, as before;
  the previous build faded the card out and popped it back). One icon of the closing app in every frame. Not checked: the
  S24 (locked), frame timing.

## Less work behind quick use (2026-10-15, emulator; S24 not checked yet)

Matheesha: "going to and back from the app library rapidly also feels less than 60hz ... imagine how the performance would
be on a mid range phone". Found by reading what runs between gestures (996f425 and the next commit):
- Home recorded its picture 120 ms after every settle (~20 ms of its main thread; a finger resting on home still counts as
  idle), and gesture nav then rendered it into the card window's layers on the render thread every window of ours shares:
  quick swipes to the App Library and back kept landing on both. Now after 350 ms of rest with no finger on home, and the
  render only once the finger is up. A launch records its own picture at the tap, as before.
- The status bar was a full-screen view in the shade's window: each step of its animations (the tone as the App Library
  comes, a minute, a level) redrew the whole window (S24 trace: 256 full-window frames in a few App Library swipes). It is
  as tall as the bar while no panel shows. The emulator redraws whole windows anyway: the gain shows only on the S24.
- The card window's picture of the wallpaper had a screen-sized GPU layer of its own (10 MB of a 121 MB budget that was
  at 112): it is drawn directly (one bitmap, home's texture).
- A launch's card faded as soon as the window events said the app was in front, which is when its window is added, not
  drawn: on the emulator home showed for a frame between our card and a cold app's launch screen (the window manager still
  had home on top). The card now waits until the app's window is on screen (the shell's own transition for that start
  committed: `packageShownAt`, by the task's top, base and real activities; a permission dialog over Contacts is another
  package's top), at most 600 ms, and only for starts that went through our transition. Emulator: a warm Clock at once, a
  cold Photos after 73 ms, Contacts after 298-593 ms.
- Still to measure on the S24: GPU memory (Control Center, Notification Center and their backdrop keep screen-sized layers
  while closed), the frame pacing of quick App Library swipes with a real finger, the rapid launches.
- The picture of the app in front stays as it is (Matheesha, 2026-10-15): taken every 6 s and 0.4 s after its screen
  settles (at most every 1.2 s), on top of the one taken at a touch on the bar. Capturing only at the touch was weighed:
  the card must show 15-40 ms after the touch, and a capture took 14-24 ms on One UI 9 (three samples) but 70-130 ms
  after a minute in an app on One UI 8. The log sums the periodic captures up every 20 (`kept pictures, last 20`).

## The flicker when opening apps during a close (2026-10-15, S24)

Matheesha (on 2c276d5): no more duplicate icons, everything smooth, but "a full screen flicker" when opening an app while
another's close is still playing. Recorded on the S24 at 120 fps with temporary logs: at the tap, home took over from the
picture of it behind the card (the old reason: touches go to home, the window in front), and the next app's launch put a
picture back one or two frames later. The picture blurs a quarter-size copy and scales it up, which looks about twice as
blurred as home's own blur at the same radius (home's log: radius 6-7.5 px at the touch, as the picture's): the whole
screen sharpened for a frame or two and blurred again. Now:
- A touch during a close changes nothing on screen: the card keeps landing over the picture, and a tap that opens an app
  hands it to the lander with that app's picture (no frame of the real home).
- A drag during a close (pages, the App Library: its icon moves) fades the picture out with the card instead of cutting it,
  and home blurs as the picture looks (`home/DepthBlur`: the copy's blur at its own size seen at full size, with the
  softness of the quarter-size copy; shared constants with GestureNav's PreviewView).
- Checked on the S24: three opens during a close, recorded frame by frame: home's blur eases and builds again with no sharp
  frame between (before: frames 153-155 of the recording visibly sharper than those around them).

## Settings v2, step V1: the whole phone, its parts, Phone (2026-10-15, emulator; S24 not checked yet)

Structure B of `docs/PLAN_SETTINGS_V2.md` (Matheesha's choice): the start page holds eight tiles, two across: Look and
Motion (the whole phone), Home, Apps, App Library, Notifications & Control Center, Status bar, and Phone. "Layouts" is no
longer a room, and "Your setup"'s chips are gone (the Look and Motion tiles say the same).
- **The tiles scroll, the sheet first.** At rest the sheet shows two and a half rows. A scroll up lifts the sheet to where
  a page puts it (the picture of home shrinks above it, as when a page opens), then scrolls the tiles in it; one offset
  moves both, so a fling carries on from one into the other, and the sheet rises only as far as the tiles need
  (`liftScroll`, `Studio.lifted`). Once the sheet has risen, all eight show on the emulator. The tiles' glass is drawn
  again as they move. Every tile is as tall as the tallest needs (`TileGrid`: "Notifications & Control Center" takes two
  lines; at least the theme's 116 pt). A tile opens from where it is now: its bounds, its name's and its detail line's
  are read at the tap, and the growing tile draws all three where the tile had them (the detail line used to vanish in
  the growth's first frame).
- **A part's page** (`PartPage`): its layout (one line while it has one: "Icon grid · iOS 27"; "iOS 27" once where the
  name is the design's), where it lives (choices for the App Library's three places; the finger on the picture shows how
  it is reached, noted only where there is a finger), its counts and switches (as Layouts had them, with the room note),
  and its own theme ("Follows Look" or any theme: what Look's "Only for one part" was). The picture zooms to the part as
  on Layouts.
- **Look, calmer:** Theme cards; Colours as one choice (The theme's, The wallpaper's, Your own) with the palette under it
  and "Adjust colours" a row away (style, intensity, each palette's own colour); Light & dark; Fonts and Size as rows
  with what they hold now, each a page; the settings' own theme last ("These settings").
- **Pages one level down** (`SubPager`, `Sub`) slide in as Motion's groups do (built at the tap, the row lit meanwhile,
  sliding two frames later; the header's title gives way to theirs; Back and the round button go back first).
- **Phone, calmer:** Shizuku (Connect only while not connected), Updates, Safety, About. Seven taps on the build turn the
  developer tools on (a line counts down from the third tap, Android's way); they show on Phone as "Developer tools" (the
  tests, slow animations, what the launcher did to the phone, the log, "Install the newest again") and the token editor,
  until switched off on their page (`SettingsPrefs.developer`, kept). Test presets show in Motion only with them on.
- **Home sliding in from the edit menu** (and back out) takes the lift into account: the sheet drops from wherever it
  rests (it stayed half in sight while home grew back over the screen from a lifted sheet, then vanished), the Apply bar
  with it, and the status bar's tone switches where the picture really is.
- The search field of V1 waits for V3, where it searches (a field that did nothing would be the one dead control).
- New colours for the parts' tiles (`comp.settings.color.home/apps/library/shade/statusbar`; Layouts' blue is Home's).
- Checked on the emulator, screenshots and recordings: the start page at rest and lifted; Home, Apps, App Library,
  Notifications & Control Center and Status bar pages with their focus and fingers; Look with each sub-page and the
  colours' three choices; Phone, the seven taps, Developer on and off; Motion and a group page; the edit menu's Home
  Settings in and Back out from a lifted sheet. 107 unit tests.

## The start page's tiles: solid, with motion graphics (2026-10-15, emulator; S24 not checked yet)

Matheesha: "instead of liquid glass tiles, unify the look with the rest of the app and add motion graphics instead of
icons". The tiles are the groups' solid cards now (`comp.settings.tile.fill` and `.corner` alias the groups'; no tint; names
in the label colour); pressed, one lights as a row does and sinks. The page behind the sheet stays the blurred wallpaper
(`comp.settings.glass`), the only glass left on the start page. Gone with the glass: the tiles' and chips' materials and the
symbol tokens.
- Each tile's top is a small scene (`settings/TileArt.kt`, 52 pt, `comp.settings.tile.art`): Look's three colours turning
  round each other (the palette the draft would make), "Aa" in the theme's font and a light/dark switch; Motion's opening
  as a curve with a dot running it and an app growing (its bounce shows); Home's icons arriving in a wave, wiggling and
  leaving, the dock under them; Apps' deck turning a card at a time; the App Library's apps flying into their folders;
  a banner dropping in over Notification Center while two controls light; the status bar's signal and Wi-Fi coming up,
  the battery charging and the minute rolling on; Phone's shield checking itself with a pulse. Every move is the
  preset's own spring as the draft would apply it (a preset, speed or bounce chosen in Motion shows on the tiles), a
  scene that starts again fades out and back in, and the scenes' clock runs only while the tiles are in sight (paused
  under an open page, with the settings away or home's picture over them; a long frame counts as a short one). Each
  scene is drawn in a layer of its own; the growing tile draws its scene too, riding along with its name.
- Dark: the groups were the sheet's own grey (`#1C1C1E` on `#1C1C1E`), so tiles and every page's groups had no edge.
  They take iOS's elevated cell colour now (`#2C2C2E`, a sheet is an elevated surface); light is unchanged.

## Settings v2, step V2: motion as moments (2026-10-15, emulator; S24 not checked yet)

Motion's 11 groups of 142 rows are 22 moments now (`motion/Moments.kt`, the plan's table): Apps (Opening apps, Going
home, Switching apps, App Switcher), Home (Pages, Folders, Unlocking, Editing, Little things, A new look), App Library
(Opening and search, Menus and sheets), Notifications & Control Center (Control Center, Controls, Editing controls,
Notification Center, Lock screen, Banners), Status bar (Status icons, Hiding and showing), and Scrolling and These
settings for everywhere. Every editable role is in exactly one moment (`MomentsTest`); 13 glue roles (the launch colour,
a late picture fading in, the card catching up, the banner's glass...) follow their moment's feel and are not listed
(the token editor keeps them).
- **A moment's own feel**: `motion.adjust.<moment>.speed` and `.bounce` in the preset (1 = as the preset), applied on
  top of the preset's wherever a curve is read. Checked on the emulator: an app's opening took 1353 ms with Opening apps
  at 50 % and 692-694 ms at the default (the card animation's own log line).
- **Character sliders** move a moment's numbers together from where it shows none of it: How far home steps back (the
  icons' and wallpaper's zoom, the blur), How far home zooms in (unlocking), Wiggle, Clear All's rhythm.
- **Pages**: Motion lists the moments by part, each with its feel in words ("Quick, a touch of bounce"); each part's page
  lists its own; a moment's page (pushed as Look's pages are, `SubPager`) has Speed and Bounce with their ends in words
  (Relaxed ... Snappy, Still ... Lively), its character, Every detail (its roles in the order they play, folded until
  asked for, each with today's editor) and Back to the preset's once changed. The picture zooms to where the moment
  plays and plays it, again and again (one sample loop for the whole settings).
- **Presets as small app openings**: each card plays an app opening out of its icon and closing, on that preset's curves
  at its speed and bounce, all cards on one beat (a quicker preset settles sooner).
- Fine-tune is Feel (Speed, Bounce, their ends in words); values read in words first, the number beside them.

## Settings v2, step V3: search and what you changed (2026-10-15, emulator; S24 not checked yet)

- **Search** (`settings/Search.kt`): a field over the tiles. Tapped, the sheet rises as a page would (its own spring:
  the tiles' scroll stays where it was), Cancel opens beside the field, and the tiles cross-fade into the results above
  the keyboard; Cancel or Back put it all back. Every setting is indexed with its place ("Home › Grid") and words it
  answers to ("icons" finds the grid, "bounce" every Bounce, "dark" Light & dark); settings come before moments, moments
  before single animations. A result grows its part's page out of its row (not its tile), pushes the page it is on (a
  sub-page, a moment), opens a moment's Every detail, waits for the page to settle, scrolls the row into sight and lights
  it (an accent wash that fades). Checked: "icons" to Home's Rows, "wiggle" to an animation inside Editing's Every detail.
  Two bugs found on the way: the scroll stopped short while Every detail was still unfolding, and a wait on the
  scroller (no snapshot state) never ended, so the row was never lit.
- **What you changed** (`settings/Changes.kt`): every difference from the theme, the preset or a layout's defaults,
  with where it is, before and after ("App Library › Grid · 2 → 3") and Reset (into the draft, waiting for Apply).
  "Your changes" over the tiles while there are any (all of them by part), "N changes" at the top of every page (that
  part's), a dot before each changed row; rows fold in and out as changes come and go (`RevealList`).
- Seen on the emulator, not from this change: right after a reinstall, the picture of home was half drawn (its lower
  half grey) until home had shown again (home captured mid-start).

## Settings v2, step V4: how apps open and go home (2026-10-15, emulator; S24 not checked yet)

- **Seven styles** (`motion/AppStyles`, `docs/MOTION_STYLES.md`): Zoom from the icon / Shrink into the icon (iOS, as
  before), Container transform (Material 3: the icon's box becomes the app, the icon fading out early and the app in
  late; home stays where it is), Slide up / Slide down, Circle reveal (a circle growing from the icon uncovers the app as
  it is on the screen, the icon fading where it is; going home, the card rounds into a bubble that lands in the icon),
  Fade and scale, Dolly zoom (home flies toward the icon as the app grows, and back), Genie (the app pours out of its icon
  through a mesh, the far rows first, and is poured back in, turning into the icon once it is in). All are drawn from the
  card's springs (frame, corners, icon blend), so grabs, reversals and an app opened mid-close work for every style.
- **Presets bundle styles**: iOS 27 zoom, Material container, and three new presets: Playful (genie), Calm (fade,
  nothing bounces), Cinematic (dolly). Tokens `motion.style.opening`/`.closing`; `tools/design/build_style_presets.py`.
- **Interrupting**: a grabbed card keeps its style's look and gives way to a plain card under the finger on the cancel's
  curve; the App Switcher waits for that (its deck draws plain cards: the held circle once jumped to a big disc with the
  app drawn larger). A circle reveal or a genie is grabbed by the plain card it is drawn on, its window or mesh kept on
  it (first grabbed as the circle's square: "scale 2.80", the card sat still and then shrank). Tapping the icon of a
  closing app zooms it back out; another app opening during a styled close fades the closing card where it is.
- **The settings**: Opening apps and Going home have a Style group: a card per style, each playing it with the other half
  as you have it (an opening style's app goes home your way); the picture of home plays the chosen one; the presets'
  cards open and close in their own styles. Search finds every style by name ("genie", "circle", "bubble") and lands on
  the group; Your changes lists a changed style by name ("Zoom from the icon → Genie") with Reset.
- **Found on the way, older than the styles**:
  - A close whose fresh picture arrived before the task lookup (the app had changed since its kept picture) had no icon:
    the card crossfaded into nothing and the real icon popped in once home drew (seen as a gap of ~4 frames at full
    speed). The card takes its icon as soon as its app is known (`GestureNav.cardKnows`).
  - Home's idle check ignored the theme reveal: the picture recorded 400 ms after onResume, mid-reveal, missed the items
    not yet revealed, and showed behind the next close until home settled. The reveal counts as busy now.
  - Slide and fade (no icon to grow out of) record home at the tap with the icon in (`HomeBridge.atLaunch`), as the zoom
    records it without; a close into the icon then records its picture once home is out of sight.
- Checked on the emulator in slow motion (x4), recorded frame by frame: every style opening and going home, a circle
  reveal and a genie grabbed mid-launch, an app opened during a genie close, a reopening after a bubble close, the
  settings' style cards, samples, search and Your changes. Smoothness is not judged from the emulator (its recordings
  drop frames). Not yet on the S24. 118 unit tests.

## V4 on the S24: mixing the styles, and the settings in one column (2026-10-15)

Every style opened and closed on the S24 (Calculator from the dock, the phone's own speed kept), measured from what the
screen showed (frame stats, unrecorded) and recorded for looking at (recordings dropped whenever a notification came);
six mixed pairs (genie in, bubble out; circle in, genie out; slide in, dolly out; dolly in, slide out; fade in, container
out; container in, fade out); a genie launch grabbed, a circle reveal grabbed, a genie close reversed, another app opened
during a slide close; a dolly close held into the App Switcher (emulator: it shows other apps).

**What looked wrong, fixed:**
- **The genie** was a straight wedge leaning at the icon. It is macOS's now (`AppStyles.genie`): a funnel narrowing to
  the icon from both sides (an icon mid-screen draws both halves in), the app's near edge dropping in first and the rest
  following down it; opening runs it backwards. Its frame keeps the card's corners (they squared off at the start).
- **A genie close reversed** (its icon tapped mid-pour) started from the mesh's bounds as if they were the card: the card
  was twice the size for a frame. A flight reversed now starts from the plain card the style was drawn on, the style's
  shape kept on it and giving way over the first half (`StyleDev.grab` for a flight's start too).
- **The bubble** was a rounded rectangle (a pill); it rounds into a circle now, its long side drawn in to its short one.
- **Slide and fade closes** showed home's picture without the app's icon (recorded for the zoom), and the icon appeared
  at once at the end: the picture is home with its icon for a closing style that leaves it there (`closePicture`).
- **A dolly close held into the App Switcher** left home behind the deck zoomed 1.55 times into the icon (a blur of a
  few icons): the dolly is a fraction that gives way as the switcher opens (`PreviewView.fadeDolly`), one way.
- **The first bubble close after an update** compiled its circle clip inside a frame (76 ms, 9 missed refreshes): the
  styles' drawing (circle and round-rect clips, faded draws, the genie's mesh and path, the container's box) is warmed
  off screen while home is idle (`CardStyleWarmUp`, 110 ms once): the first bubble close then peaked at 9.9 ms.
- **Every launch's last frames**: the cards' container fading into the app went through a layer the size of the screen,
  allocated in that frame (7.4 ms of GPU for the zoom, ~9 for a circle reveal: 1-2 missed refreshes in 5 of 7 circle
  openings). With only one card drawing, its fade applies to its draws: circle openings 0 missed in 4 runs, zoom 0, the
  frame 5-7 ms. A fading card in the fade style is faded draw by draw too (`flatFade`).
- Also: a launch's picture of home taken at the tap now belongs to that app's launch only (a stale one could be used by
  a later launch), and a turned display's close (always into the icon) takes the picture without the icon from the start.

**Numbers after the fixes** (S24, missed refreshes): openings: zoom 0 (5 runs), circle reveal 0 (4), genie 0 (2), fade
0 (2), slide 0 (2), container 0, dolly 0; closes: zoom 0, container 0, slide 0 (2), fade 0 (2), dolly 0, genie 0 (2), the
bubble 1 in the first two runs after the install, then 0 in six. GPU per frame varies 3-10 ms between runs of the same
style with no missed refresh (frame stats count waiting for buffers as GPU time).

**The settings in one column** (Matheesha: "make it one column and design each beautifully with the motion graphics"):
each tile as wide as the sheet, its scene across the top (76 pt, was 52) laid out for its width: Look's colours and "Aa"
with a small screen turning dark and light as its switch goes; Motion's opening curve beside a small phone where an app
opens and goes home in the preset's own styles (a Playful preset pours it); Home's grid across the width over its dock;
the App Switcher's deck in the middle; the App Library's search field over its folders; Notification Center with Control
Center and its brightness slider beside it; the status bar end to end with a notification's icon arriving after the
time; Phone's shield beside its three rows. On the S24 at rest the scenes cost 0 missed refreshes (GPU 5.1 ms a frame),
scrolling 0. A tile opening misses 1-2 refreshes ~200 ms in (Look: 0, 1, 1); the previous build, two across, measured
0, 2, 2 the same way: older than this change, not looked into yet.

## The cards, polished (2026-10-15, emulator; S24 not checked yet)

Matheesha, after the one-column cards: "I just wanted the cards to feel more polished and well thought out. current design
doesn't really deliver that" (a Pinterest video of a food app's menu as the example of the card look: a bold name on one
side, a large picture on the other, every card the same; not its colours, not its transitions).

- **One structure for every card**: the part's name (Title 3, emphasized) and what it holds (Subheadline) on the left,
  centred on the card's height; on the right a window onto the part in its colour, inset evenly (10 pt) with corners
  concentric to the card's (26 less 10), as iOS 27 nests shapes. The window is the part's colour under the theme's veil
  (`comp.settings.tile.visual-veil`: a pastel in light, a deep tint in dark), its panels the theme's paper
  (`comp.settings.tile.paper`). A first try ran the window off the card's right and bottom edges, as the video's pictures
  run off theirs: at full size its left edge met the card's bottom in a hard corner and its top ran into the card's corner
  arc (a rectangle cut by a corner looks like a mistake; the video's pictures are round plates).
- **Every scene rests on a whole picture of its part and plays one act a cycle** (6 s), the acts running down the column
  0.75 s apart, so a card or two moves at a time and the page comes alive card by card as it appears. Before, all eight
  moved at once and some passed through empty moments (the App Library's folders emptied, the status bar faded out, Home's
  icons left). The acts: Look's switch turns the little screen dark and the icons to the next colours, and back; Motion
  opens an app in the preset's style, a moment, and closes it in its closing style; Home wiggles and two icons change
  places (back again next time); the App Switcher's deck turns a card; the App Library's apps turn round their folders,
  folder after folder; a banner drops onto Notification Center as two controls turn on and the brightness rises; the
  status bar's minute rolls on and a notification's icon arrives and goes; Phone's shield sends out a pulse that its
  ticks answer.
- The scenes are laid out for the window they have (hundredths of its height); words in them (the time, "Aa") scale with
  a taller card.
- Checked on the emulator in light and dark, recorded; a card growing into its page and back carries its window and words
  with it. Not yet measured on the S24 (it was locked). 118 unit tests.
- Seen again, not from this change: right after a reinstall and an appearance switch, the settings' picture of home was
  drawn only in its upper half until home had shown again.

**Open question (Matheesha): the live picture of home on the start page.** Recommended: take it off the start page and
show it only where a change is judged on your own home. On the start page it repeats what the cards now show, takes the
top half of the screen (two cards in sight at rest instead of about six), is the heaviest thing on the page (home recorded
into a picture each time the settings come up) and the most fragile (the half-drawn picture above). Where it earns its
place is a part's page while you change its layout, grid, placement or look: there it would grow out of the card's
window as the page opens (the card's scene turning into the real thing), zoomed to that part. Motion's moments would play
on a stage like the cards' (as the opening and closing styles already do), Phone needs none. To redesign with it: the
slide from home's edit menu into the settings (home shrinking into the picture), perhaps into the Home card's window.

## The cards' scenes on the card itself (2026-10-16, emulator; S24 not checked yet)

Matheesha: "instead of having a separate box for the motion graphic, make it blend with the card itself. that's the only
visual change I want you to make."

- No box: each scene is drawn on the card, in the same frame (inset from the card's top, right and bottom), its edges the
  card's own. What leaves the frame (the banner coming down, the deck's front card leaving) is cut by the card's outline,
  never by an invisible line, and nothing reaches the card's words.
- What the box used to hide or hold had to follow it: the scenes' panels were white (on a white card they vanished), they
  now take the part's pastel in light (`comp.settings.tile.paper` transparent over it; dark unchanged); everything that ran
  off the box's edges ends inside the frame (the App Library shows three whole folders, slightly smaller, where it showed
  two and a cut third); the banner drops in from past the card's top edge; Look's little screen turning dark is gone (it
  was a box), its act is the switch and the icons' colours; Motion's stage has no ground and no dimming of its own, its
  faint icons only where they are whole, and its app opens to the frame (corners concentric with the card's).
- Found on the way: the act cycle was a fixed 6 s while the acts follow the preset's speed; at a slow Speed or in slow
  motion an act could run past the cycle and jump at the wrap. The cycle now stretches to the longest act and a rest
  (`ArtMoves.cycle`); nothing changes at the presets' own speeds.
- Checked on the emulator in light and dark, Motion's act recorded in slow motion. Not measured on the S24.

## V5 step 1: the settings without the live picture of home (2026-10-16, emulator; S24 not checked yet)

Matheesha: "we will keep the live preview only where it's needed. it would be especially useful for the token editor,
where the user can select each element to customize it. Otherwise, it would be better to use a motion graphic instead of
a live preview where it is not crucial" (`docs/PLAN_SETTINGS_V2.md`, V5).

- **The start page is the cards.** The sheet sits under the title, at rest and with a page open (tokens
  `comp.settings.sheet.gap`; `sheet.rest` and `sheet.open` gone); the tiles' scroll no longer lifts it. Six cards in
  sight on the emulator where two were.
- **A part's page opens with its card grown into a stage** (`settings/Stage.kt`; `comp.settings.stage.height` 0.21 of the
  screen, `stage.inset`): the card moves and grows to the top of the page over the groups rising in under it, its scene,
  name and line giving way to a phone drawn as a motion graphic (`settings/PhoneScene.kt`) in the part's colours: a
  wallpaper of the colour, its icons the colour's pastels (Look: its palette), the status bar, the Search pill and the
  dock. It is home as the draft lays it out (`HomeMetrics` from the draft's columns, rows, dock, names and size): Rows +
  glides the icons up and grows the new row in (recorded); Control Center's columns resize on the same curve. The stage
  looks at the part's band of the phone (the status bar large; the shade's upper half; the App Library's page or sheet;
  the whole phone otherwise) and plays how the part is reached: the finger's strokes and what they open, following it,
  then settling open on the draft's own curve and closing (Notification Center with the date, the clock and two
  notifications; Control Center; the app shrinking into the deck as older cards come in; the App Library's page from the
  side or its sheet from below). Its own acts: home's icons wiggle and two change places, the minute rolls on and a
  notification's icon comes and goes, Look's icons turn through its colours and its phone follows the draft's light or
  dark, Phone's shield pulses, Motion's phone opens and closes an app in the draft's styles and drops a banner. A page
  pushed below (a moment's) keeps the stage and plays its samples on the same phone, focused where they play
  (`MotionSamples` on the scene: home, its icon and the app's page come from it). Back shrinks the stage into its card.
  Search results grow it out of their row; Your changes still grows its row into the sheet (under its page).
- **From home's edit menu** home's picture steps back (8 %), dims, blurs and fades into the page as the sheet rises over
  it (`settings/HomeSlide.kt`); Back on the tiles plays it the other way. The slide back into home was recorded on the
  emulator; the slide in could not be: home gave no picture of itself there ("home gave no picture of itself in 900 ms",
  the same before this change), so the settings opened at rest. Not yet on the S24, where it worked before.
- Look's theme cards keep their pictures of home in each theme (taken once a look, a beat after the settings come up,
  never in a motion).
- Checked on the emulator in light and dark, in slow motion: a card opening and closing, Home's grid change, a moment's
  page, Motion's page, a search result. Not measured on the S24. 118 unit tests.

## Styles during fast use, and the wallpaper in the settings (2026-10-18, emulator)

Matheesha: "when I switched the app launch animation, it would work but frequently revert back to the default animation.
I noticed that it happens when I open and close apps too fast"; "there's also issues with how the wallpaper change
affects our settings app".

- **A closing app reopened always zoomed** (`beginLaunch`: a reversal took `AppStyle.ZOOM` whatever the preset said), so
  every launch during a close, or right as the card landed in its icon, played the default. It now plays the chosen
  opening style from where the card is: the style's look is read at the card's place on the whole way from its icon (its
  size), and it comes in over the first half of the flight as what the card showed goes (frames, the fade-through, the
  circle's window; a genie's own mesh bends into the opening one: replaced at once, the funnel snapped flat for a
  frame). Slide up and fade in have no way from a card part way to its icon: it grows back as it is. The first frame is
  exactly the closing card's (the blend starts from it). Another app opened during a close already played its style
  (the closing card fades where it is). Checked on the emulator at 8x slow motion: Clock closed in the genie and reopened
  as it landed poured back out of its icon ("launch ... (reversed a closing card) (genie)"); Maps opened during Clock's
  close in the genie.
- **A close of an app with no icon on home always zoomed to the middle**, slide and fade too (they never go into the
  icon). They now play as chosen; the styles that go into an icon still shrink to the middle and fade there.
- **The wallpaper changed while the settings were away** (home's Change Wallpaper, the system's picker): coming back,
  Android showed them on their task's snapshot (the old wallpaper), then the theme's reveal replayed the change over the
  whole page, its band a grey smear warping the words. It is a crossfade now (`motion.wallpaper.crossfade`), from the
  still that matches the snapshot to the new look; the reveal stays for a look applied in the settings, from the tap.
- **Theme cards kept pictures of home with the old wallpaper** (Graphite's card showed the old one beside iOS 27's new
  one). A picture keeps the wallpaper it was taken with (`HomePictures.Shot`, saved as `<theme>~<wallpaper>.webp`); one
  taken with another wallpaper shows the drawn stand-in on the current wallpaper until a new picture comes, which fades in.
- **The test theme showed to everyone** ("Graphite (test)" beside iOS 27). Themes can be marked `"test": true` as presets
  are; Graphite is, shown only with Developer's "Test themes and presets" switch (or while in use).
- Seen on home, not fixed yet: after a new wallpaper's reveal, the dock's glass keeps the old wallpaper's tint for about
  a second (its frost is made again after the reveal).

## A day of use: the settings reviewed (2026-10-18, emulator)

Matheesha: "do a full day to day usability testing - try to use our app, tweaking each possible type of setting. Note
down the issues and how we can improve the user experience". The review, its method and every finding:
`docs/USABILITY_REVIEW.md`. Fixed in this round, checked on the emulator in light and dark, with the theme's colours and
the wallpaper's:

- **Groups that never became whole**: the groups' rise-in stagger was capped at the sixth, past what the page's motion
  reaches (the sixth stayed at 89 %, the seventh on at 75 %), and a group that composed again alone took a new stagger
  number (any group could turn translucent after an interaction). The number is taken once; from the fifth on they rise
  together.
- **White on white**: with the theme's colours in light the sheet was white at 80 % under white cards (255 against 252):
  it is iOS's grouped grey now (#F2F2F7), as in iOS's own Settings.
- **One hue for every part** under the wallpaper's colours: a part keeps its hue, turned half way toward the palette's
  accent, at most 15° (`MaterialYou.harmonize`, unit-tested).
- **What fits**: Control Center at 5 columns where 4 fit showed 4 while its card and the stage said 5; all say 4 now, the
  row says what happens and the note why.
- **The page's structure**: the changes row at each page's foot (at the top it pushed the page down under the finger as
  the first change came); "N changes to apply" on the Apply bar; a caption under the stage in place of Layout and Where it
  lives rows with one choice (search lands on it, lit as a card); a part's own theme only when there is another theme;
  one theme shown with its description; a moment's row says its style and its page puts Style first.
- **Stages that show the setting**: Look's phone on your wallpaper (dimmed in dark) in the palette's light tones; the
  Fonts page a type specimen (each font named in its own face, the layout fixed so a font chosen only changes the
  letters); Look's card "Aa" in the titles' font.
- **Details**: chip rows scroll out to the card's edge; swatch rims visible in dark; change rows without the part's name
  under its heading and with "4 → 5" kept on one line; "Inter, titles in Serif"; cards say "iOS 27 theme / preset /
  style"; `tools/build.sh` shows Gradle's reason when it fails.
- Found by the review's own reset test before any commit: a part's colour with the theme's colours called itself (a
  replace inside its own body) and the settings crashed at the first frame after Reset all. Fixed; see the new checking
  rule in CLAUDE.md.
- Open (in the review): the dock's glass tint lagging a new wallpaper; App Library names cut at 3 columns; Android's
  palette lagging a wallpaper set outside its picker (emulator; check the S24); the look-change reveal over the settings
  page; Phone's stage not showing Shizuku's state; accessibility labels for the drawn stages.

## Round 2 of Matheesha's findings (2026-10-18, emulator; S24 log read)

- **Wallpaper change, one-frame flicker.** The S24's log showed it: the wallpaper set in the system's picker loaded as
  home was brought forward under a closing card, so its reveal ran under the card and the card's picture of the old
  wallpaper handed over to a home mid-reveal. Home now holds the reveal until the card is gone
  (`HomeBridge.whenUncovered`). Reproduced on the emulator at 4x slow motion with a test hook that sets the wallpaper from
  a file while an app is open (`TEST_WALLPAPER`): before, the reveal started 0.2 s before the card landed; after, 40 ms
  after it, and `tools/frame_glitch.py` finds no flicker over the whole screen.
- **The home gesture over the settings closes them** instead of shrinking them into a card: they follow the finger up
  and close into home as Back does, or spring back (`OwnScreenSwipe`, `EntryState.upward`). Checked on the emulator: a
  long swipe closes them into home (home's picture comes forward, sharpening, as the sheet leaves), a short one springs
  them back, Back on the tiles still slides down into home. No App Switcher from them (a hold does nothing).
- **Cinematic's dolly zoom kept its first direction** when apps were opened rapidly: an app opened while another's card
  still landed left home's zoom aimed at the first icon (`setDolly` is ignored while a picture shows), and so did the close
  of a launch grabbed mid-flight. It is re-aimed with the depth now (`PreviewView.aimDolly`). Checked on the emulator in
  slow motion: Clock closed, Maps opened as it landed: home zooms toward Maps.

- **Widgets** ("they work but they suck"; checked on the emulator in 4x slow motion, frame by frame):
  - *Placement.* A widget now moves widgets in its way, as iOS does (`Grid.putAt`): one of its size it came from beside
    swaps into its old place, others go to the first place they fit from where they were on, the icons under them move on,
    and what fits nowhere on the page goes to the next (an icon still never moves a widget). Before, a widget added at the
    top went to the bottom or another page, and one dragged or grown onto another was refused. A drag and a held resize
    handle are worked out from the layout as it was when they began (`EditMode.Snapshot`): what one place or size moved
    out of the way comes back when the finger goes on (items drifted further with every step and never returned).
  - *The clock's sizes.* Small and Large set the hours over the minutes (Apple Watch's X-Large face,
    `ClockNumerals.stacks`): one line in a square card left most of it empty and Large showed Medium's numerals over an
    empty card. The clock sits in the middle of its card with an 8 pt inset (the date was against the top edge), the date
    glides with it. The gallery offers the clock in all three sizes (each preview set as on home) and its clocks follow
    the minute (they kept the minute the gallery opened at).
  - *Resizing.* The glass clock's old numerals stay and grow or shrink with the card toward the size they would have in
    it while the new ones, built for the new size, crossfade in over them, both keeping to the card's middle (both were
    hidden until the new shape was built: no time for a moment). An Android widget is laid out at the card's size as shown,
    frame by frame: its content reflows with the card's edge and always fills it with its own corners (the picture of
    the old look crossfading over the content laid out at the new size was what made the corners change while the handle
    was held). A layout switch mid-way (Android 12's sized layouts) keeps the old layout live over the new one as it fades;
    what the app sends meanwhile waits until the card is at rest (a picture of it stayed put as the card moved on, every
    button twice).
  - *From the menu.* A size or style chosen in a widget's menu: the copy lifted above the blur is recorded from the widget
    at every frame as the menu closes (`ContextMenuView.LiveCopy`), whole until the blur behind it has gone: the still copy
    of the old size faded out over the widget resizing under it (the date and buttons twice), and the widget showed
    through it still blurred.
  - *Edit mode survives home being built again* (a scheduled dark mode or new colours meanwhile): it ended unseen under
    the old look's picture, and the next drag on a widget's handle pulled Spotlight down instead.
