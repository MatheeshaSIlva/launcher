# Probe findings (Galaxy S24, Android 16, One UI)

Every result below was produced by `LauncherProbe` builds run by Matheesha on the phone. Source:
https://github.com/MatheeshaSIlva/requirementchecks (public). Numbers are from his pasted logs.

## Snapshots and recents
- `IActivityTaskManager.getTaskSnapshot(taskId, false)` returns cached snapshots of background tasks in **1–3 ms**. Return them as
  hardware bitmaps across Binder (copying to a software bitmap inside the shell process crashed the service).
- `IWindowManager.snapshotTaskForRecents` works only for the visible task, about **100 ms**: too slow for gestures.
- Secure (screenshot-blocked) apps give no usable snapshot: show a themed placeholder card.
- The stock recents-animation API (`startRecentsActivity` / `IRecentsAnimationRunner`) does **not exist on Android 16**.
  The shell user holds `CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS` and `MANAGE_ACTIVITY_TASKS`, but finger tracking has to be our own overlay.

## Gestures
- Own overlay strip at the bottom plus a card window, 120 Hz, 0 dropped frames plain; 1 dropped frame in 49 with six blurred layers.
- Stock home/recents/back/shade blocked with `cmd statusbar send-disable-flag …` (auto-restore timers must not outlive their flags).
- Works: flick up to home; grab the card mid-animation (finger takes over); sideways swipe to the previous app (quick switch);
  rotation (card size re-read). Spring-back stretch was fixed by making the card window exactly display-sized (gravity TOP|START,
  cutout ALWAYS, `setFitInsetsTypes(0)`).

## Launch animation
- Set `transition_animation_scale` and `window_animation_scale` to 0 (leave `animator_duration_scale`). Show our own expanding card
  (tile → full screen, ≈320 ms) while the app starts underneath; reveal when the app is on top.
- Warm and cold launches both looked smooth. Frame stats example: 62 frames, median 8.33 ms, worst 16.65 ms, ≈1 dropped.
- Without our card the app simply popped up with no animation (system animations off). With system animations on you would see a double animation.

## Close animation (v7, 13c)
- Settings opened, snapshot taken, overlay card shrinks toward the bottom-center icon spot while HOME starts. Worked.

## Apps opened from outside the launcher (13d)
- Taps on the **stock notification shade** still play SystemUI's own launch animation even with window/transition scales at 0.
  Plan: our shade replaces it, so taps go through us. Heads-up notifications and in-app links must be re-tested.

## Own status bar
- Stock bar hidden with `clock system-icons notification-icons` disable flags and our overlay bar drawn on top. Initially flaky
  (stock bar still visible on top): caused by a race between restore-on-start and new flags, plus stale detached restore timers.
  Fixed in v6.3 (Matheesha: "the stock one is gone now").

## Windowing and shell-created windows
- Freeform (mode 5) / multi-window (mode 6) launches from the shell: window opens, then after ≈5 s becomes a stuck overlay without drag. Out of scope.
- Windows created from the Shizuku process fail ("Unknown pid=… uid=2000"). Test 11k did nothing visible.

## Blur
- Standard cross-window blur disabled: `isCrossWindowBlurEnabled=false`, `ro.surface_flinger.supports_background_blur` empty (read-only, no root).
- `RenderEffect` blur on our own content works (12b) provided the window has `FLAG_HARDWARE_ACCELERATED`.
- Samsung `View.semSetBlurEnabled/Radius/BackgroundBlurColor/CornerRadius` work from a third-party overlay without changing the hidden-API policy, but give only **fog**.
  `SemBlurInfo` (Builder with presets and radius) gives tints that work.
- **Window-level route is the good one:** `FLAG_DIM_BEHIND` + `lp.semAddExtensionFlags(SEM_EXTENSION_FLAG_CHANGE_DIM_EFFECT_TO_BLUR)`; blur strength follows `dimAmount`;
  it blurs the whole screen behind the window (never a rectangle). Driven per frame via `updateViewLayout`: 0 failures. Frame stats at 120 Hz:
  runs of 215 frames with 0 dropped, 207 frames with ≈8 dropped (one 33 ms spike); second run had a 125 ms first-use hitch, one run fell to 60 Hz at dim 1.0.
- Matheesha's verdict on visual quality: window-level route "looks like actual blur"; View-level route "looks like fog".

## Watchdog and safety
- Status bar flags live on the shell command's token: `cmd statusbar send-disable-flag none` clears them; a reboot also clears them.
- First watchdog design (name-based process check via `pidof`/`ps`) never fired. Also `pkill -f wd.sh` and `pgrep -f wd.sh` matched their own shell
  (`[exit 143]`, false "loop still running"), so a "disarm" never ran its restore steps.
- Final design: the app touches `/data/local/tmp/wd.hb` every second; a `setsid nohup` shell loop restores animation scales + status bar when the file is ≥ 4 s old.
  Result: armed 11:39:01, force-stop, restored 11:39:39 ("heartbeat stale 4s"); the loop survived the app and its Shizuku service dying.
- Real app: heartbeat from a foreground service; add a Shizuku-free emergency restore (notification action + safe-settings screen).

## Updater (dev tool)
- App → GitHub release `latest` → shell-side download into `/data/local/tmp` → `pm install -r -d` → `am start`. Works (v7.11).
- Early failures: wrong "already newest" check (stamp saved before install), and the shell could not read the app's external folder.

## Shizuku
- Matheesha runs the **thedjchi/Shizuku** fork (Apache-2.0, v13.7.0): starts at boot and after crashes. Recommend it in onboarding after reviewing its code.
- Hidden-API exemption via `VMRuntime` fails on Android 16. Fallback exists (`settings put global hidden_api_policy 1` + app restart) but wasn't needed for the Samsung sem* calls.

## Probe version history (condensed)
v5.x snapshots + strip prototype → v6 launch lab, windowing, own status bar, Shizuku boot → v6.2/6.3 status bar race fix, blur diagnosis →
v7 close animation, external-open watcher, watchdog, Samsung blur discovery → v7.1–7.4 Samsung blur labs (14a–14g) → v7.5–7.11 updater and watchdog fixes (current).

## Live mirror for app cards (launcher build 2575259, 2026-10-04)
- Shell-side `IWindowManager.mirrorDisplay(0, SurfaceControl)` handed to the app and reparented into an accessibility-overlay
  window: **crashed the whole system** (launcher and system restarted). Dead end; the probe and its AIDL codes (14, 15) are
  removed and must not be reused. Live card content needs another route (to research before any code).
