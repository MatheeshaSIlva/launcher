# iOS 27 consistency evaluation (2026-10-07)

Matheesha's report on the S24 (build f014eac) and a side-by-side of the shade and the clock against iOS 27: our build on
the Android 17 emulator (screenshots and recordings), the iOS 27.0 Simulator (iPhone 18 Pro; `ios-reference/` passes 7
and 8 were made for this: a long Notification Center list, the long look, opening an app from a notification, hiding the
list, the "Add a Control" gallery, modules held; light and dark) and Apple's iOS 27 UI kit (Figma). Notification Center
and banners were never screenshotted on the S24 (they show his messages).

**wrong** = visibly different from iOS; **off** = close, details differ; **ok** = matches. "Fixed" = changed in this round
and checked on the emulator only; nothing here has been on the S24 yet.

## What Matheesha reported

| Report | Cause | Now |
| --- | --- | --- |
| Notification backgrounds flicker while scrolling | Each platter (glass and text) was cached as a layer drawn at its resting place; while the list moved only 3 were redrawn a frame, the rest showed the wallpaper of another place (tan glass over sky) and jumped when redrawn | Fixed: every platter's glass is drawn where it is, each frame; only icons and text are cached. Cost on the S24 to be measured (`tools/scenario_nc_scroll.sh`) |
| The clock's reserved space cuts the notifications | The list was hard-clipped 40 pt above its area (192 pt from the top) | Fixed: nothing cuts the list; scrolled up it passes over the clock (which fades as it is covered) and each notification fades out under the status bar, and near the flashlight and camera buttons at the bottom |
| The long-press menu does not blur what is under it | Only a 40 % black dim | Fixed: the whole sheet blurs (22 pt) and dims as the long look opens; the menu is dark glass (kit) |
| Opening an app from a notification uses the system's animation | The PendingIntent was sent with the default options | Fixed: the platter grows into a full-screen card (the app's picture or launch screen) while the app starts with no system transition (our instant remote transition, now also for PendingIntents: `IShellService.sendNoAnim`); the card fades into the app once it is in front |
| The clock widget does not feel accurate | It was the dock's clear glass shaped as digits, sampling the sharp wallpaper: faint, blue-grey, flat | Fixed: iOS's lock-screen clock material (kit: background blur 80, `#404040` linear dodge, glass refraction 0.5, light 0.4): frosted, lifted, with lit edges; Inter at weight 700 (was 640). Same for Notification Center's clock |
| Control Center's background must be live | It was a blurred picture of what was behind, taken when the panel opened | Fixed: a blur window under the shade blurs what is behind live: One UI's dim-to-blur on the S24, Android's cross-window blur elsewhere (checked on the emulator: a running stopwatch keeps changing under the blur); the picture is the fallback. One UI's strength set to 0.15 on the S24 (see the measurements) |
| Not all controls are available | 27 controls | 17 more (below), the app-backed ones only where such an app is installed |
| Controls not implemented as iOS | A long press opened Android's settings | Fixed for the modules iOS expands: see "Control Center" |

## Notification Center

| Element | iOS 27 | Ours before | Now |
| --- | --- | --- | --- |
| Presentation on open | **Collapsed stack** at the bottom: the newest notification, the next one peeking out on one line (11 pt narrower each side), "+N from App" in a capsule (22 pt narrower), 32 pt of each showing; wallpaper not dimmed. A tap fans it out into the list | Always the full list | Fixed (wrong -> ok): the collapsed stack, tap to fan out (the list rises from the bottom on springs, the wallpaper dims) |
| Title row | None (iOS 16+) | "Notification Center" and "×" (iOS 15) | Fixed: removed (groups are still cleared from their swipe and their header) |
| A stack's count | A white badge with the number on the icon's top-right corner | "N more notifications" line under the text | Fixed |
| Platter material | Clear glass, white text, in light and dark; the wallpaper under the list dimmed (kit: black 25 % + 5 % linear burn) | Light frosted platters with black text in light mode | Fixed: clear glass over the dimmed wallpaper, white text, the kit's faint lift |
| Glass while scrolling | Exact | Stale glass, flicker | Fixed (see above) |
| Flashlight and camera | 58 pt clear glass at 75 pt from the sides, 79 pt from the bottom | 50 pt dark discs at 71 pt | Fixed |
| Hidden list ("● 12 Notifications") | A swipe down on the list hides it into a count at the bottom (seen in pass 7; how it comes back not captured) | None | Not built yet |
| Scrolling the list | Scrolls up over the clock, fading under the status bar (iOS 16+ lock screen; not captured: pass 8's notifications were never authorised in the Simulator, see below) | Hard clip under the clock | Fixed as described above |
| Long look | Kit: a solid card (white header, the app's content) and a dark glass menu of actions under it, the rest out of focus | Dim only; clear menu | Fixed: the sheet blurs and dims, the menu is dark glass; action icons are not shown yet (Android gives them) |
| Opening an app | The app comes out of the notification (not captured, see below) | System animation | Fixed: a card grows out of the platter |
| Grouping | By app, split by thread (an app's conversations are separate stacks) | By app only | Not changed yet: Android's equivalent is the conversation (shortcut id) or the group key |
| Status bar | "Carrier" on the left, levels on the right | Levels only | Not changed |
| Clock | Frosted glass numerals, 205 x 87 pt at y 115 | Clear lens (faint) | Fixed (above); size and place were already right |

## Control Center

| Element | iOS 27 | Ours before | Now |
| --- | --- | --- | --- |
| Background | Live blur of what is behind | A picture taken at open, blurred | Fixed (live where the system allows; picture fallback). The controls' own glass still samples that picture (a frame behind a playing video stays still inside a control) |
| Module expansion | Held, a module grows into a large panel in the middle and the others fade: Connectivity (large toggles with names and states), Brightness (large slider; Dark Mode, Night Shift, True Tone), Volume, Now Playing (large player), Focus (its modes), Flashlight (strength), Timer (durations) | Long press opened Android's settings | Fixed: all seven, Android's counterparts (Night Light / Eye comfort shield, Auto-Brightness in place of Night Shift and True Tone; Do Not Disturb as the Focus); Wi-Fi shows its network |
| Focus tap | Opens the Focus list | Toggled Do Not Disturb | Fixed |
| Power button | Touch and hold until the phone vibrates, then the slider (Apple's iPhone User Guide, iOS 27) | A tap opened the power menu | Fixed (2026-10-14): held only, a tap does nothing |
| Camera held | A menu: take a photo, take a selfie, record a video (the guide) | Opened the camera | Fixed (2026-10-14): Take Photo, Take Selfie, Record Video grow out of the control (Control Center's glass), the held control lifts, the others fade back; slide onto a row and lift, or lift and tap; a tap outside or Back closes it. Rows only for what the camera app takes |
| Now Playing tapped | Grows into the large player | Opened the media app | Fixed (2026-10-14): grows into the large player (its artwork there opens the app); the transport buttons and the output switcher act at once |
| Wi-Fi held (expanded module) | Shows its networks | Opened Wi-Fi settings | Fixed (2026-10-14): Android's Internet panel (its networks over what is behind); the others their settings |
| Wi-Fi, Bluetooth tapped | Disconnect (stay on, back on by themselves later) | Turn off | Not changed: Android has no "disconnect for now" an app can ask for |
| Controls | iOS 18-27's gallery: connectivity (Airplane, AirDrop, Wi-Fi, Bluetooth, Cellular, Hotspot, VPN, Satellite), Camera (Camera, Selfie, Video, Portrait, Code Scanner), Clock (Alarm, Timer, Stopwatch), Accessibility (Invert, Color Filters, Reduce White Point, Live Captions, Magnifier, Text Size, Accessibility Shortcut, ...), Shazam, Translate, Voice Memo, Wallet, Home, Screen Recording, Low Power, Orientation Lock, Silent Mode, Dark Mode, Notes, Calculator, Flashlight, Remote, Shortcuts ("Open App"), apps' own controls | 27 | 44: added Quick Share (AirDrop), VPN, Data Saver, Video, Selfie, Voice Memo, Recognize Music, Translate, Magnifier, Wallet, Home, Text Size, Invert Colors, Color Filters, Reduce White Point (Extra Dim), Live Captions, Accessibility Shortcut. Missing: Screen Recording (no app-side way found yet), Satellite, Remote, "Open App" (needs an app picker), apps' own controls (Android: quick settings tiles; needs SystemUI to click them) |
| Gallery | Sections, a "Search Controls" field | 4 sections, no search | 7 sections in iOS's order (Connectivity, Media & Sound, Camera, Clock, Display & Focus, Accessibility, Utilities); no search yet |
| Pages | Up to 5 pages, dots on the right (favourites, music, home, connectivity) | One page | Not built |
| Status row | "Carrier" next to the signal | Signal and Wi-Fi only | Not changed |
| Landscape | The page flowed across the width (8 columns at 874 pt, first row at 45.7 pt), one status line, no + nor power | The portrait column, cut off | Fixed (2026-10-13): the same flow, columns as fit, the status row lined up with the grid; edit mode portrait-only as iOS |
| Controls' glass while the grid is pulled | Exact | Samples where the control rests (up to ~60 pt off while pulled; under the blur it hardly shows) | Not changed (drawing every control's glass anew each frame cost the S24 8-10 ms of GPU) |

## Banners

iOS's are glass showing what is behind. Ours (2026-10-11): the dock's glass (Matheesha: the frosted glass read as frost,
not Liquid Glass; banners take the dock's background) drawn whole over the blurred picture of what is behind. What is
behind changes as a card starts (a launch or a quick switch: the app's kept picture, else its own launch colour; a close
committed: home), not when the app's window reports (a banner showed the wallpaper over an app opening, a white plate
over a dark app); the status bar takes the app's tone (the one it last asked for, else its launch colour's) the same way.
Over home the picture is the wallpaper, over an app its latest picture from the task snapshot; when what is behind
changes the glass fades to the new picture (`motion.banner.app-fade`). On One UI the blur is live (2026-10-12; Matheesha:
"I need it to be actually live, just like the qs panel"): the system blurs what is behind each banner (`SemBlurInfo` on a
plate under it) with the dock material's colour treatment, fitted on the S24 (brightness 0.672 x + 0.061 against our
glass's 0.671 x + 0.062, colour kept 1.00 against 1.02), and the glass adds its rims, light and shadow. The lens cannot
bend what the system draws (Matheesha, 2026-10-13: no refraction showed). Tried: along the edge, where the lens moves what
is behind by more than ~2 pt, the whole glass over our picture of what is behind, fading into the live blur inward; taken
out the same day (Matheesha: it broke the whole live look). SurfaceFlinger shows the banner's blur region (1038 x 178 px, radius 19); a test banner over
home: 0 missed refreshes, GPU 2.1 ms a frame. Other phones keep the glass over a picture. Size, place and motion match iOS's.

## Home (seen in passing)

Going home from an app in landscape (2026-10-13, `test61_homeFromLandscape`): iOS keeps home upright (turned with the
phone), the card turns into the upright icon, nothing spins. Ours matches (docs/STATUS_BAR.md, "Found during the walk").

Home's glass clock now has iOS's lock-screen material (above). iOS's home screen has no lock-screen clock at all (it shows
widgets); the clock widget is ours, styled after the lock screen.

## iOS captures for this round

Pass 7 (`ios-reference/out/pass7`) found that Notification Center opens collapsed and that a fling down hides the list
into a count which then lasts; the long look and the tap-to-open tests of pass 7 found no platter on screen because of it.
Pass 8 posted again but the Simulator never showed the permission prompt in time, so no notification arrived (an empty
Notification Center). A pass 9 should first grant notifications (a setup test that launches the host app and waits for the
prompt) and then repeat `test45_ncList` / `test46_ncHideShow`.

## Measured on the S24 (local build, frame stats only)

- Control Center opening with the live blur: 0 missed refreshes (2 runs). The blur's strength: One UI's dim-to-blur at
  0.15 (at 0.35 and 0.6 home turned into a flat brown; at 0.15 its icons show through as coloured shapes, as under iOS's).
- Notification Center (12 test notifications plus the phone's own, every stack fanned out by an adb hook, never a tap):
  scrolling 0-1 missed refreshes per step, GPU median 7-7.6 ms (8-15 ms before the platters lost their invisible shadow
  and their dispersion and the dim moved into the wallpaper's layer); the collapsed stack fanning out 0-2 missed (7 on the
  first run after an install: first-use costs). Still close to the 8.3 ms a frame has: the next thing to make cheaper.

## Not verified (S24 only)

The notification launch card with real apps, Night Light through the shell on One UI (`blue_light_filter`), the torch's
strength levels, the clock's new look on the S24's wallpaper, the expanded modules on the phone.
