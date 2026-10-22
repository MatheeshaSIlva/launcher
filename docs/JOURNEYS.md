# Core journeys: manual regression script

One short script per core journey in `docs/SPEC.md`. Run it after any build you want to trust; all four take about
10 minutes together on the S24 (the target; the first timed run is M1 slice 3).

**Which build.** Use a CI build installed with the in-app UPDATE button. Before step 1, open the log (System: tap the
build seven times to turn the developer tools on, then Developer's log) and check that its first line `BUILD <sha7>`
matches the commit you mean to test. A `BUILD local` build is for the emulator only. For a quick emulator check first,
run `tools/smoke_emulator.sh` (install, gesture strip, one app open and close, each panel); it does not replace these
steps.

**Privacy.** Never screenshot or record Notification Center, banners or private apps (messaging). Judge those by eye, or by
frame stats only. Delete any capture that caught one without viewing it.

**Severity** (D-015, owner-approved):
- P0: a crash, stuck stock UI, a frozen home, blocked gestures.
- P1: a core journey step is broken.
- P2: a missed refresh, a pop or a stock seam.
- P3: cosmetic or tooling.

A severity is a proposal until the owner confirms it; where unsure, write the lower one down as "proposed" and ask.

Every failed step becomes a Bug backlog entry in `docs/ROADMAP.md` with a severity. "Ok" means the Expected line held,
with nothing popping, no stock panel showing and no visible stutter.

## Run log

| Date | Build sha | Journey | Step | ok / fail | Note |
| --- | --- | --- | --- | --- | --- |
| | | | | | |

## J1 Home and app open/close

1. **Unlock arrival.** Lock the phone, wake it, unlock. Expected: home arrives in one motion (icons come in, the status bar
   and dock settle); nothing pops, and no stock launcher or stock bar shows for a frame.
2. **Pages and App Library.** Swipe through the home pages, then on to the App Library, and keep pulling at both ends.
   Expected: pages follow the finger; the App Library comes after the last page; each end rubber-bands and springs back with
   no hard stop.
3. **Launch from an App Library folder, close back into the icon.** In the App Library tap a category tile that holds more
   than four apps (its cluster of small icons) to open it as a folder, tap an app in it, then swipe up on the bar.
   Expected: the app grows out of its icon inside the open folder; the close shrinks into the same icon, in the folder,
   which is still open. **Note: folders on the home pages are not built yet** (`HomeScreen.kt`: "folders: a later build"),
   so this step covers the App Library's only; the home-page folder step is added when they exist.
4. **Launch from the dock, then a cancelled close.** Tap a dock icon; once the app is up, start the swipe up on the bar,
   but let go low. Expected: the card follows the finger and springs back to the app with no jump; the app is still usable.
5. **A launch grabbed mid-flight.** Tap an icon and touch the bar while the card is still growing. Expected: the card stops
   under the finger where it is and can be closed from there, with no jump.
6. **Spotlight.** Pull down on a home page, type the name of an app, press Enter, then come home. Expected: the keyboard
   comes up with the field; Enter launches the app once (not twice); the close returns to home with Spotlight gone.
7. **Edit mode and a widget menu.** Long-press home, move an icon to another slot, tap Done. Then long-press a widget.
   Expected: icons wiggle and the others make room as you drag; Done settles them; the widget's menu (Show or Hide Widget Names,
   Edit Home Screen, Remove Widget, and Size when the widget comes in more than one; Edit Widget for a configurable app
   widget; Glass or Solid Style on the clock; Glass Background or No Background on app widgets) opens out of the widget
   with home blurring behind it. Edit Home Screen enters edit mode; Remove Widget asks before it removes.
8. **App Library: search, launch, close onto the result.** In the App Library tap Search, type, launch an app, then close it.
   Expected: the results filter as you type; the app opens from its result; the close lands on that result's icon in the
   list.

## J2 App Switcher and gestures

1. **Hold for the deck.** From an app, swipe up from the bar and hold. Expected: the App Switcher opens with the cards
   following the finger; no stock recents shows.
2. **Scroll and tap a card.** Scroll the deck sideways, then tap a card. Expected: the deck scrolls with iOS-style physics;
   the tapped card grows into its app.
3. **Flick a card away.** Open the deck and flick a card up. Expected: the card leaves with the finger's speed and the
   others close the gap; Clear All, when it shows, empties the deck.
4. **Tap empty space to go home.** In the deck tap the empty space around the cards. Expected: the deck closes into home.
5. **Quick switch, both ways.** Swipe sideways along the bar to the previous app and back, then do it several times
   quickly. Expected: each switch follows the finger and lands on the right app; repeated flicks never switch to the app
   already in front.
6. **Back from both edges.** In an app swipe in from the left edge, then from the right edge. Expected: Back works from
   both edges; nothing else opens. **Known seam:** the back gesture is Android's own and shows its stock arrow; this is
   logged as a bug, not a failure of this step.
7. **Picture-in-picture, the close.** Play a video in YouTube and swipe up on the bar. Expected: the card flies into the
   mini player and settles with no flash of the app.
8. **Picture-in-picture, the switcher.** With the mini player up, open the App Switcher. Expected: the deck opens under
   the mini player without a repeated frame; the mini player stays where it was and can still be dragged.

## J3 Shade everywhere

1. **Control Center over home.** Pull down from the top right. Toggle Wi-Fi, drag the brightness slider, then tap the
   empty space below the controls (outside edit mode a tap there closes the panel; in edit mode it leaves edit mode
   instead: `ControlGridBlock`, `Mode.EMPTY`). Expected: the panel follows the finger over a live blur; the toggle and
   slider react at once; the tap closes the panel with no stock shade at any point.
2. **Notification Center.** Pull down from the top left, fan the stack out, scroll, then swipe up from the bar. Expected:
   the stack opens collapsed and fans out on a tap; the list scrolls; the swipe up closes the panel.
3. **Both pulls over an app.** Open an app, then pull each panel down. Expected: our panels follow the finger over the app;
   no stock shade and no stock status bar shows next to ours.
4. **A banner.** Post a test notification (`am broadcast -a dev.launcher.app.TEST_NOTIFY -p dev.launcher.app --es title T
   --es text X --ei id 9`) and swipe the banner away. Expected: the banner comes in with a spring and goes with the finger;
   it is ours, not the stock heads-up.
5. **An app opened from a notification.** Pull Notification Center and tap a notification. Expected: its app opens from the
   panel; the panel closes behind it.
6. **Notification Center over the lock screen.** Lock the phone, wake it and pull from the top left. Expected: our panel
   opens over the lock screen; the stock shade does not appear.
7. **The charging moment.** Plug in the charger with the screen on. Expected: our capsule grows out from behind the battery
   and covers One UI's charging chip; it folds back when the animation ends.
8. **Pixel layout and back, no restart.** In the settings open Notifications & Control Center, choose the Pixel layout and
   Apply, then pull from the top: first pull brings the quick row over the notifications, a second swipe expands Quick
   Settings. Switch back to iOS the same way. Expected: no restart is needed either way; each layout's panels open from
   its own places.

## J4 Customise look and motion

1. **Settings from the drawer and from Home Settings.** Open the settings from the drawer, close them, then long-press home,
   tap Edit and choose Home Settings. Expected: from Home Settings home steps back, blurs and fades into the page as the
   sheet rises; Back plays it the other way.
2. **Light and dark, colour source, Apply.** In Look pick Dark under Light & dark and another colour source (Colours: the
   wallpaper's), then tap Apply. Expected: nothing changes until Apply; then the new look is revealed from the tap in one
   motion.
3. **A theme change and back.** In Look choose the Pixel theme, Apply, then choose the first theme and
   Apply. Expected: both changes reveal from the tap; home, the shade and the settings follow the theme.
4. **A motion preset on a launch.** In Motion choose the Playful preset under Presets, Apply, then open and close an app.
   Expected: the launch and the close play the preset's styles (a genie); then choose iOS 27 again and Apply and see the
   zoom return.
5. **A moment's Speed.** Under Motion open Opening apps, set its Speed higher, Apply and open an app. Expected: the open is
   visibly faster, other moments are unchanged; the value reads in words first.
6. **Home columns glide.** Open Home and change Columns. Expected: icons glide to their new places on the stage and, after
   Apply, on home; nothing jumps.
7. **Search lands on its row.** Type a setting's name in the settings' Search and tap the result. Expected: the page grows
   out of the row, scrolls the setting into sight and lights it.
8. **The home gesture closes the settings.** In the settings swipe up on the bar. Expected: the finger plays the slide that
   Back plays and a release goes home (no App Switcher from the settings); letting go early springs the sheet back.

**Theme builder: not built yet (M3/M4).** Listed so the gap stays visible: a themer cannot yet make a theme in the app
without editing JSON. Nothing to run until M3 and M4 ship; then it becomes steps of this journey.
