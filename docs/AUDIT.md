# Element audit (2026-10-03, against iOS 27)

Every element built so far, what still feels minimal next to iOS 27, and what is next. Checked on the emulator with
screenshots (`tools/device.sh`), status as of the build after 3390d7b.

Legend: **done** = built and checked on the emulator in this round; **phone** = needs Matheesha's S24 to confirm;
**P1–P3** = what is still missing, by priority.

| Element | State | Still missing (priority) |
| --- | --- | --- |
| Long-press menus | done: iOS 27 Quick Actions layout from Apple's kit (2026-10-07: leading symbols, 42 pt rows, radius 30, Regular glass fills, sizes as the last row), real glass over blurred home, Delete App | menu "morph" out of the icon (P3) |
| Home grid | done: free placement (empty cells allowed, iOS 18+), 11 unit tests | drag several icons at once (P3) |
| Home folders | — | make a folder by dropping an icon on another, open/close animation, rename (P1) |
| Notification badges | done: iOS red counts on home and dock icons; access granted through Shizuku | badges in the App Library and Spotlight (P2) |
| Widgets | done: Android widgets, gallery sheet, sizes in the long-press menu, corner resize handle in edit mode, Edit Widget (setup screen) | gallery search (P2); Smart Stack (P3); our own widgets beyond the clock: weather, battery, calendar (P2); animated resize (P3) |
| Clock widget | done: lock-screen glass numerals in their own proportions, date "Sat 3", Glass/Solid style, three sizes | iOS's font and colour choices (P3) |
| Edit mode | done: glass Edit/Done (readable on light wallpapers), Edit menu with Add Widget, Edit button stays sharp over its menu | Edit menu "Customize" (dark/tinted icons, large icons) and "Edit Pages" (P2) |
| Status bar | done: iOS layout on the camera line, equal margins, 15 pt time; notification icons (2026-10-07); battery percentage inside the battery, green + bolt charging, yellow Low Power, red low; airplane, No SIM / No Service, Focus moon, VPN; moves onto Control Center's status row | make room for the system's privacy chip and stock call chips (P1, found on the emulator); location arrow, call / recording pills of our own (P2) |
| App Library | looks right; long press → menu, Add to Home Screen, drag out onto a page (done) | badges (P2) |
| Spotlight | suggestions, Top Hit, glass field; long press → menu, drag out (done) | results beyond apps: web search, settings, contacts (P2) |
| Widget gallery | done: app list, per-app pager with sizes, Add Widget; darker sheet glass for readability | search (P2) |
| Dock | fine | folders in the dock (with home folders, P1) |
| App open/close | works (checked on the emulator: our own transitions 2/2) | — |
| App switcher (hold during a home swipe) | done (round 8): iOS 27 deck, from apps and from home (rest 56 dp up), scroll, open, flick to close, home; phone measured by frame stats only (it shows other apps' snapshots) | slow-release trigger (P3) |
| Shade (notifications, controls) | built (phase 4 slice, 2026-10-07): Control Center with edit mode and gallery, Notification Center with stacks, swipe actions and menus, banners incl. calls and alarms; S24 frame stats 0-2 missed | **phone**: pulls over apps with a finger, real calls/alarms/messages; inline reply (P1); multi-page Control Center and expanded modules (P2); launch card from a notification (P2) |
| Launcher settings | dev panel only | an iOS-style settings page: icon shape, labels, glass clear↔tinted, wallpaper dimming (P1) |
| Accessibility | — | TalkBack labels for the custom-drawn views (P2) |

## Verification workflow (from this round)

- `tools/build.sh`: local build (JDK 17), errors only. `./gradlew testDebugUnitTest`: logic tests (layout grid).
- `tools/device.sh` / `tools/reinstall.sh`: install on the emulator (or a phone over adb), tap/drag/long-press scripted, screenshot,
  read our log, frame stats. Emulator: `Medium_Phone` (Android 17), GPU on; Shizuku can be started there over adb, so gesture
  nav, the status bar and open/close run on it too.
- `PreviewActivity`: components on their own (status bar in every state, icons with badges), for checks without the full stack.
- Avoid `uiautomator dump` while gesture nav runs: it unbinds accessibility services (that left the emulator with a black home once).
- `tools/device.sh rec NAME` / `recpull NAME`: screen recording of the phone at 120 fps, turned into contact sheets with frame
  times; how the launch flicker and the stale Spotlight picture (round 6) were found and their fixes confirmed.
