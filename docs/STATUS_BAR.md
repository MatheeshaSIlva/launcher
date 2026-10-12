# The status bar in every state

Matheesha (2026-10-12): "the status bar doesn't function properly with immersive view apps and horizontal orientations ...
Please thoroughly analyse every state the status bar can be in and optimize it properly." Checked with a test app that puts
the bars in each state an app can ask for (`BarsTestActivity`: shown, status bar hidden, all hidden, swipe shows them for a
moment or for good, the old full-screen flag, light/dark, portrait/landscape/reverse, every camera cut-out mode), on the
emulator (Android 17) and the S24 (One UI, Android 16).

## How the bar knows

- **Hidden or shown, and its height**: `statusbar/BarWatch`, an invisible one-pixel application overlay. Our own windows lie
  above the status bar and are never told whether it shows; an application overlay lies under it and is told what any app
  is, the moment it changes (the window manager's insets): the app in front hiding it, showing it again, its height in this
  rotation. Before: `dumpsys window` on every window change and every 2.5 s, so a video going full screen inside an app kept
  our bar up to 2.5 s; and Android 16+ writes the state as `InsetsSource ... type=statusBars ... visible=false` where the
  parser read `mType=statusBars ... mVisible=`: **the bar never hid at all** (both devices). dumpsys stays as the fallback
  (parser fixed) when the overlay permission is missing, and for the bar's tone (light or dark content), which only it says.
- Side effect: Android posts "Launcher is displaying over other apps" (channel `com.android.server.wm.AlertWindowNotification
  - <pkg>`, importance minimum) for any app overlay, also with our foreground service running. It is about our own window:
  `Notifs` leaves it out of Notification Center, the bar's icons and the banners.
- **A swipe from the top while an app hides the bar** (Android shows its bars for a moment then): seen by the shell in the
  touchscreen's raw events (`TouchStream.watchEdge`, `IShellService.watchEdge`): a finger that comes down within the bar's
  height of the screen's top edge (screen coordinates for the display's rotation) and moves 20 dp down. It never takes the
  touch (taps at the top of a game or a video still reach it); one thread asleep in poll() between touches, running only while
  an app hides the bar. The kernel reports an axis only when it changes (a straight swipe down never says x): only the axis
  that runs down the screen is needed. Restarted when the shell service restarts.

## The states

| State | Android | Ours before | Ours now | Checked |
| --- | --- | --- | --- | --- |
| Home | shown, tone from the wallpaper | shown | shown | both |
| App, bar shown, dark or light content | shown, the app's tone | shown, tone polled (window change, every 2.5 s) | same | both |
| App hides the status bar (immersive, sticky or not; status only or all bars; the old FLAG_FULLSCREEN) | hidden | **never hid** (dumpsys format), else up to 2.5 s late | hidden at once (fade and slide up) | both |
| Swipe from the top in such an app (sticky) | bars shown for a moment, a second swipe opens the shade | an empty stock strip; **no way to open the shade in a full-screen app** | our bar shows for 2.5 s (over Android's translucent strip), a second swipe pulls Notification Center or Control Center | emulator (raw touch events); S24 to try |
| Swipe in a non-sticky app | bars come back for good | as above | shown for good (the window manager says so) | emulator |
| Shade pulled from the bar shown for a moment | shade over the app | (none) | panel opens; the bar stays while it is open and hides again after (the app still full screen) | emulator |
| Landscape, bar shown | full width, its landscape height | portrait height kept | full width at the window manager's height (One UI: 79 px against 103 in portrait) | both |
| Landscape, hidden / reverse landscape / cut-out modes | as the app asks | never hid | hidden at once; both landscape sides, the camera at either end | both |
| Home with auto-rotate on, phone sideways | (a phone's home stays upright on iOS) | **home rotated into a broken landscape layout** (labels without icons, an empty dock) | home stays portrait | emulator |
| A card launching or closing | the arriving app's state | tone at the card's start | tone at the card's start; hidden state applied when the app arrives (shown during the card) | emulator |
| Lock screen, a panel open, banners | | shown | shown (a panel open keeps it shown over a full-screen app) | emulator |

## Found during the walk, fixed after (2026-10-13; iOS 27 captured first, `test60_ccLandscape`, `test61_homeFromLandscape`)

- **Control Center in landscape** showed its portrait column, cut off below. iOS 27 flows the same page across the width:
  the controls in reading order, each at the first free place, as many columns as fit within the sides (8 at 874 pt), the
  first row at 45.7 pt under one status line (Carrier, signal, battery lined up with the grid; no + nor power). Ours does
  the same (`CcLayout.flowed`, `comp.cc.grid.*-landscape`); the page keeps its portrait places, and edit mode stays
  portrait-only (iOS shows no + in landscape).
- **Going home from a landscape app**: home was drawn upright inside the landscape screen under the card, the app showed
  again, then the display turned with a spin. iOS 27: home upright behind (turned with the phone's natural way up), the
  card not turned, turning into the upright icon, no rotation animation. Ours now: the picture of home is laid out turned
  as Android will show the upright-only home, the card flies to the icon's turned place and turns into it upright, home
  starts only as the card lands (started earlier, Android turned the display mid-flight), the display turns with a jump
  cut (HomeActivity's `ROTATION_ANIMATION_JUMPCUT`), the status bar waits for the turn. Android hides every overlay window
  of ours from home's start until the turn (AsyncRotationController): a cover activity (`TurnCover`, started by the shell
  as the close begins) draws the same picture under the card, and home starts once the shell has seen it on screen. On the
  emulator no frame of the app shows any more (recorded frame by frame); the gesture pill fades out with the close and
  back in at the bottom of the upright screen.
- Stock status bar chips that ignore the disable flags draw under our time on the emulator (a stopwatch, a call); One UI's
  not seen yet. Our bar does not make room for them.
- The tone over an app is still polled (dumpsys every 2.5 s and on window changes): an app that turns its bar from light to
  dark while scrolling is followed up to 2.5 s late.
