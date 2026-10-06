# CLAUDE.md — Launcher (working name)

Read this first. It is the briefing for everything built so far and everything planned. Deeper detail lives in
`docs/REQUIREMENTS.md` (what we are building) and `docs/PROBE_FINDINGS.md` (what the feasibility probes proved, with numbers).

## What this project is

An Android launcher that merges the **home screen, recents and the notification / quick-settings shade** into one
themeable interface, with a theme engine and theme builder ("Hyprland-level" control over looks and motion).
It works **without root**, using **Shizuku** (a helper process with shell rights, uid 2000, started through wireless
debugging). The app also works without Shizuku as a plain launcher; Shizuku unlocks the advanced features (and those are the paid tier).

Owner: Matheesha (CS student, strong Linux/sysadmin background). Test device: **Galaxy S24, Android 16, One UI**.

## The three rules that outrank every feature

1. **Perfectly smooth.** Open, close, swipe and panel pulls follow the finger and hold the display's refresh rate
   (120 Hz). Matheesha's words: "not a single stutter". Measure it (frame logs), do not eyeball it.
2. **Fully consistent.** One shade, one status bar, one gesture set, everywhere. No stock panel may appear next to ours.
   Showing our status bar "on the home screen only" was explicitly rejected.
3. **Attention to detail, always.** Every single change on screen is animated, fluidly and cleverly: nothing pops, snaps,
   jumps or "just appears". That includes the small things (a badge count, a label toggle, a list arriving, a minute
   change, a bar sliding in) and the states between states (home after unlock or a cold start, a handover from a card to
   the real icon, a folder closing while another opens). Every animation is a spring or an ease that follows the finger
   where there is one, can be interrupted and retargeted without a jump, and never makes the user wait for it to finish
   before the next thing can be tapped. Before calling a screen done, go through every element on it and every way it can
   change, and ask "how does this move, and what shows on the frame in between?". Matheesha reviews at this level.

## How we work with Matheesha

- He tests on the phone and pastes logs or describes what he saw. Give him **short, exact steps** and ask for specific
  results ("paste the log", "did it look like X or Y?"). Do not bury the ask.
- He prefers direct, honest feedback; say plainly when something failed or when a result is inconclusive.
- Do not claim something works until a device log or his words confirm it. Several earlier "it works" claims were withdrawn
  (blur tests that silently drew nothing; a watchdog that never fired). Verify, then state.
- Builds: GitHub Actions builds every push to `main`; the in-app **UPDATE** button installs the newest build silently
  through Shizuku. The first line of the app log shows `BUILD <sha7>` — check it matches the commit before trusting a test.
- Write results and decisions into `docs/` as we go; the spec is a living document.

## Architecture (target)

- **Launcher app** (our process): home canvas, drawer, recents, shade, status bar, theme engine, builder — all our own views/windows.
- **Shizuku user service** (shell uid): calls hidden framework APIs by reflection (task snapshots, task switching, statusbar
  disable flags, animation scales, app launch, silent install). Windows created *from* this process do not work, so
  everything visible lives in the app process.
- **Overlay windows** (`TYPE_APPLICATION_OVERLAY`, always `FLAG_HARDWARE_ACCELERATED`): gesture strip + full-display card window.
- **Watchdog** (shell-side loop): restores animation scales and status bar if the app's heartbeat goes stale. Must exist
  before any feature that hides stock UI.
- **Home (iOS profile, swappable)**: `apps/` (app list, categories, shaped icons), `motion/` (every animation by role, iOS scroll
  physics), `drawer/` (drawer style × placement; App Library), `home/` (config, iOS-proportioned metrics, layout model, home screen).
  Design notes in `docs/PROGRESS.md` ("Home experience, iOS profile").
- **Glass/blur**: three layers — own snapshot blur (baseline, all phones) → standard cross-window blur where the system enables it
  → Samsung dim-behind blur upgrade.

## Proven mechanisms (reference implementations are in the probe repo)

Probe repo: https://github.com/MatheeshaSIlva/requirementchecks (public). Clone it next to this project for reference; do not
copy it wholesale — port the working pieces cleanly. File map:

| Mechanism | Probe file | Notes |
| --- | --- | --- |
| Shizuku user service, hidden-API reflection | `ProbeService.kt`, `IProbeService.aidl` | explicit AIDL transaction codes; `runShell`, `runDetached`, `downloadFile` |
| Gesture strip + card that follows the finger, grab mid-animation, sideways quick switch | `GestureStrip.kt` | card window = exact full-display size, gravity TOP\|START, cutout ALWAYS, `setFitInsetsTypes(0)` |
| Launch animation (system anims off + own expanding card) | `V6Lab.kt` (`launchLab`, `runLaunch`) | animation scale 0 for transition + window only; animator scale untouched |
| Close animation (snapshot card shrinks to icon while home starts) | `V7Lab.kt` (`closeLab`, `runClose`) | cached snapshots 1–3 ms |
| Own status bar, stock bar hidden | `V6Lab.kt` (`statusBarLab`), `ProbeService.statusBarCmd` | `cmd statusbar send-disable-flag clock system-icons notification-icons` |
| Watchdog with heartbeat | `V7Lab.kt` (`armWatchdog`) | app touches `/data/local/tmp/wd.hb` every second; loop restores after 4 s stale |
| Blur: snapshot blur | `BlurLab.kt` | `RenderEffect` on our own content |
| Blur: Samsung dim-behind | `SemBlurLab.kt` (`animated`, `strength(2)`) | see pitfalls |
| In-app updater | `Updater.kt`, `ProbeService.downloadFile`, `.github/workflows/build.yml` | rolling GitHub release `latest` |
| Boot / Shizuku-after-reboot logging | `BootReceiver.kt`, `V6Lab.kt` | thedjchi fork recommended |

## Pitfalls we already paid for (do not repeat)

- **Overlay windows without `FLAG_HARDWARE_ACCELERATED`** silently draw no blur/RenderEffect.
- **`pkill -f X` / `pgrep -f X` match their own shell** (the pattern is in the command line) and kill it, skipping the rest of the
  command. Use the bracket form: `pkill -f 'wd[.]sh'`.
- **Name-based process checks (`pidof`, `ps | grep`) are unreliable** for watchdogs. Use a heartbeat file.
- **Stale detached "restore" timers** (`sleep N; cmd statusbar send-disable-flag none`) later clear newer flags. Kill them before setting flags.
- **The shell cannot read the app's own external folder**; download the update APK *inside the shell process* to `/data/local/tmp`.
- **Never copy a hardware snapshot bitmap to a software bitmap inside the shell process** (crashed the service). And a hardware
  Bitmap sent over Binder is *read back into a 10 MB software copy* by Binder itself: send the `HardwareBuffer`
  (`taskSnapshotBuffer`) and wrap it in the app. The software copies cost a 5+ ms GPU upload at first draw and native-memory
  GCs (5 ms pauses of the whole process) mid-gesture.
- **Our own frame log ("frames N ... dropped ~K") times the drawing thread, not the screen.** A frame the GPU finishes late is
  shown a refresh later and the log does not see it. What the screen showed: `tools/framestats.py` (present times).
- **Windows created from the Shizuku process fail** ("Unknown pid=… uid=2000").
- **Freeform / split-screen launches from the shell** become stuck overlays after ~5 s. Out of scope (experimental only).
- **Stock recents animation API does not exist on Android 16**; we draw our own.
- **Standard cross-window blur is off on this Samsung** (`ro.surface_flinger.supports_background_blur` empty, not changeable without root).
  Samsung's plain `View.semSetBlurRadius` gives fog. What works: window with `FLAG_DIM_BEHIND` + `semAddExtensionFlags(SEM_EXTENSION_FLAG_CHANGE_DIM_EFFECT_TO_BLUR)`;
  strength follows `dimAmount`; whole-screen only (never a rectangle). Reflection on `sem*` worked without changing the hidden-API policy.
- **Stock notification launch animation** (SystemUI) still plays even with animation scales at 0; solved by replacing the shade so taps go through us (re-test).
- **Status bar race**: a restore-on-start can clear freshly set flags; set flags only after restore has finished.
- **Never change an overlay window's layout params at the start of a gesture** (alpha, flags, size): each change is a
  10-50 ms window manager re-layout on that window's thread. The card window stays at alpha 1.
- **Recording home into a Picture costs ~20 ms of main thread**: never inside an animation frame (settle callbacks run inside
  the last frame); reuse the last picture when home has not drawn since. **No GPU layer on any view inside home** (only on
  the recorded root): a layered child is drawn in software inside the recording and the glass shader crashes the app.
- **`LiveGlass` must never be drawn inside a clip or a smaller layer** (clipToOutline, a parent's clip, view alpha < 1):
  the effect's input is cut there and the edges break. Use `GlassDrawable` (samples our wallpaper copy) for anything clipped.
- **Text-shaped glass needs a distance field, not a blurred mask**: a blur wide enough for a lens turns a whole thin stroke
  into "edge". Judge glass offline only with the app's own pipeline at the device's real size (`docs/design/clock_proto.py`).
- **Glass and transforms**: a glass surface's uniforms are recorded into its display list; a parent moving it does not redraw
  it. GlassView re-checks its real place before every frame. Never count a transform of a view drawn through a GPU layer
  (home's depth zoom of `fg`, tagged `glass_root`): the layer scales the glass again (e9401f1 showed the wrong wallpaper in the
  dock after every close). Contact sheets at 1/3 size hide thin glass rims: judge glass at full resolution.
- **Touchable region of an overlay** (`rootSurfaceControl.setTouchableRegion`, API 34): set it from the view's attach
  callback (before that it is dropped), and never as an empty Region (not sent: the whole window stays touchable); use an
  off-screen pixel for "nowhere".
- **Our remote transition must not finish before its start transaction is committed.** SystemUI applies the finish
  transaction from its own process; transactions from two processes are not ordered, so an early finish let home end up
  inside a removed transition container (black home, "no focused window" ANR, survives reinstalls). Finish from
  `addTransactionCommittedListener` (`InstantTransitions`).
- **adbd restarts kill everything started through Shizuku** (SIGKILL of adbd's cgroup; escaping it is denied). On the S24 this happened on every
  lock/unlock because Default USB configuration had data functions; fixed by "debugging only"/"No data transfer". Hence: status bar flags are set
  through the binder API with a token owned by our service (system drops them when it dies), never with `cmd` (those persist until reboot).
- **Everything that changes system state must auto-restore and be recoverable without Shizuku** (notification action + safe-settings screen). A reboot always clears these in-memory flags.
- **Keystore**: debug builds are signed with a committed keystore so CI builds install over each other. Keep that pattern (new key file for this app).
- **CI is the build machine**: the cloud sandbox cannot reach Google Maven. If a local Android setup exists, prefer local builds; keep CI as a backup.

## Build, release, update pipeline (to recreate in this repo)

1. GitHub Actions on push to `main`: `./gradlew assembleDebug -PbuildSha=${GITHUB_SHA}`; `versionName` = first 7 chars of the sha.
2. Publish a rolling prerelease tagged `latest` with the APK (`gh release delete latest --cleanup-tag -y; gh release create latest …`), release notes = full sha.
3. App shows `BUILD <sha7>` in its first log line; **UPDATE** compares that to the release, downloads inside the shell process, runs
   `pm install -r -d /data/local/tmp/update.apk`, then `am start`; if still alive after ~9 s it prints the installer's output.
4. Publish build logs to an orphan `ci-logs` branch so a cloud session can read failures without log paste.
5. The repo must be public for the updater (no auth on the phone).

## Checking a build before Matheesha does (required)

Every change is checked on the emulator before it is pushed, with screenshots looked at, not assumed:
- `tools/build.sh` (errors only), `./gradlew testDebugUnitTest` (logic tests), `DEVICE=emulator-5554 tools/reinstall.sh`.
- `tools/device.sh shot|tap|long|drag|swipe|log|gfx` drives the device; screenshots land in `tools/shots/` (git-ignored).
- Emulator `Medium_Phone` (Android 17): start with `emulator -avd Medium_Phone -no-window -gpu host`. Shizuku is installed:
  start it with `adb shell <shizuku apk dir>/lib/x86_64/libshizuku.so`; enable our accessibility service with
  `settings put secure enabled_accessibility_services dev.launcher.app/dev.launcher.app.NavAccessibilityService`.
- `PreviewActivity` (`am start -n dev.launcher.app/.PreviewActivity`) shows components on their own.
- Do not run `uiautomator dump` while gesture nav is on (it unbinds accessibility services).
- What only the S24 can show (Samsung blur, One UI quirks, real frame pacing) is reported to Matheesha as unverified.
- Smoothness on the S24 is measured from what the screen showed: `tools/scenario_spotlight_close.sh`, `scenario_library.sh`,
  `scenario_switch.sh` run launches/closes/switches N times and print per-animation missed refreshes and GPU/CPU per frame
  (`tools/framestats.py` on `dumpsys gfxinfo framestats`; frames whose present time is before their own vsync are not real
  and are dropped). `tools/scenario_more.sh` covers every other animation (home's own motion, folders, Spotlight, dock, grab,
  cancel, long deck, flick). For a late frame, record a Perfetto trace on the phone and list our long slices with touch
  markers: `tools/trace_slices.py TRACE [MIN_MS]`. Compare against the previous build (`git stash`, build, install,
  run, `git stash pop`) before calling a change an improvement. `tools/find_icon.py` finds an icon on a screenshot
  (template in `tools/shots/`) so a script never taps a guessed position. The App Switcher (`scenario_switcher.sh`) is
  measured on the phone by frame stats only: it shows other apps' snapshots, so it is looked at on the emulator.
- When the S24 is connected (USB or wireless adb, `DEVICE=<serial>`): animations are checked frame by frame with
  `tools/device.sh rec NAME [SECS]` … `recpull NAME [WAIT]` (screenrecord captures every composed frame, 120 fps; contact
  sheets and frame times land in `tools/shots/NAME/`; needs `pip install imageio-ffmpeg`). Frame logs give the numbers.
- On the phone, every scripted tap is gated on a checked state (focused window, keyboard shown, or a screenshot looked at):
  the Home key is blocked while our gestures run, and a tap on the wrong screen once opened a private app. Never record or
  screenshot inside private apps (messaging); delete any recording that caught one without viewing it.

## Local build (Windows dev machine)

- Package / applicationId: `dev.launcher.app` (placeholder, like the name). Source in `app/src/main/java/dev/launcher/app/`.
- Gradle 8.9 + AGP 8.7.3 + Kotlin 2.0.21 (same as the probe, proven on CI). Gradle 8.9 cannot run on JDK 25 (Android Studio's JBR), so build with JDK 17:
  `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"; .\gradlew.bat assembleDebug`
- Keystore: `launcher-debug.keystore` (alias `launcher`, password `android`), committed on purpose. Local and CI builds install over each other.
- `local.properties` (SDK path) is git-ignored; local builds are stamped `BUILD local`, so UPDATE always replaces them with the CI build.

## Plan

Build order (details and gates in `docs/REQUIREMENTS.md`): 1 Foundation → **2 Smooth core** → 3 Home and drawer → 4 Shade → 5 Themes and builder → 6 Glass and polish → 7 Release.
Phase 2 decides whether the whole idea works.

**First tasks, in order**
1. Project skeleton: Gradle, CI + keystore + rolling release + updater, `BUILD` stamp, Shizuku link, `HOME` launcher intent filter.
2. Safe-settings screen + notification "Restore system" action (no Shizuku needed).
3. Watchdog with heartbeat from a **foreground service**; kill-test it (force-stop the app, expect restore in ≈4 s).
4. Phase 2 slice: gesture strip, launch and close cards, recents carousel from cached snapshots, own status bar.
5. Frame-log test screen: a scripted open/close/gesture run that prints dropped frames, so "no visible stutter" is a number.

## Open items (not decided or not verified)

- Play Store policy: downloaded/interpreted theme scripts; Play Billing for the paywall; review risk of self-granting permissions via Shizuku; whether the silent updater may ship in the store build.
- Longer stress test of Samsung dim-blur (one run dropped to 60 Hz at the strongest level; first use had a 125 ms hitch → pre-warm a zero-dim blur window).
- Heads-up notifications and in-app links still play stock animations; re-test once our shade exists.
- Review the thedjchi Shizuku fork's code before recommending it in onboarding.
- Accessibility requirements need writing. Theme gallery/sharing plan, app name: undecided (placeholder "Launcher").
- Everything was probed on one S24; re-run the key probes on other devices after phase 2.
