# Home features: design (iOS theme)

Agreed scope (2026-10-03): edit mode (moving icons and widgets), overview on hold, pull-down search from any page, a better
A–Z search list, and an iOS status bar (the bar only, not the shade). Built in three rounds so each can be tested before the
next touches the gesture core:

1. Search (pull-down Spotlight + the shared search list) and the status bar. Also: the App Library keeps its full blur when it
   is pulled past its end.
2. Edit mode.
3. Overview on hold.

## 1a. Search list (shared by Spotlight and the App Library)

One custom-drawn list (`SearchList`), so both places look and move the same.
- Colours: everything in the glass palette: section letters and the index white at 60 %, the touched index letter white at 100 %
  with a small glass bubble showing it; no accent blue (was #0A84FF, out of place next to the glass).
- Filtering animates: rows that stay glide to their new place on a spring, new rows fade and rise in, rows that go fade out where
  they were; section headers leave/return the same way when a query starts/ends. Nothing jumps per keystroke.
- Matching: starts-with, then word-starts-with, then contains (as now).

## 1b. Spotlight (pull down on any home page)

- Gesture: a downward drag that starts on a home page (below the status bar; the top edge belongs to the system shade) opens it
  with the finger: the background blurs in (the library's material), content slides down slightly and fades in. Release past
  ~40 % or with a downward fling opens it fully and raises the keyboard; otherwise it springs closed. Interruptible.
- Layout (iOS 26): app suggestions at the top (4 icons with labels, from launch history), results (the shared list) below,
  the search field as a glass capsule at the bottom, above the keyboard.
- Leaving: back, a tap on empty space, or swiping it up; launching a result closes it under the launch card.
- The home Search pill opens Spotlight (no longer the App Library's search).

## 1c. Status bar (iOS)

Measured on the iOS 26 press image (iPhone 16 Pro, pt): time SF 17 semibold, centred 73.7 from the left, vertically on the
Dynamic Island's centre (32 of 62); right group right-aligned 35.4 from the edge: signal 19 x 11.7 (4 bars), Wi-Fi 17 x 11.2
(3 arcs), battery 27 x 12.6 (body + nub), 7.5 apart. Scaled with the layout unit, centred in the S24's status bar.
- Our own overlay (accessibility overlay, like the gesture strip), non-touchable; the stock clock and icons are hidden with the
  service-held disable flags (as tested in task 2), so the system clears them if our service dies.
- Content: time, cellular bars, Wi-Fi bars, battery (with charging bolt). No notification icons (iOS shows none).
- Colour: white or black like iOS, following the app's own light/dark status bar request (read through the shell from the
  window manager), and on home from our wallpaper's brightness under the bar.
- Hidden when the app hides the status bar (immersive), on the lock screen, and while gesture nav is off.

## 2. Edit mode (moving icons and widgets)

- Enter: long-press an icon (after 0.5 s the iOS context menu: app shortcuts + "Edit Home Screen" + "Remove App"; keep holding
  and move = start dragging directly), or long-press empty space. Haptic on entry.
- In edit mode: every icon and widget wiggles (small rotation, random phase), a "–" badge removes from home (app stays in the
  App Library), "Done" at the top right; the Search pill shows the page dots.
- Dragging: the item lifts (scale 1.1, shadow) and follows the finger 1:1; the grid reflows around it on springs; hovering
  over another app for ~0.6 s makes a folder; holding at a page edge for ~0.6 s turns the page (a new page at the end); the dock
  takes up to 4. Widgets move as blocks and push icons aside. Drop: springs into its cell. Saved immediately.
- From the App Library: long-press a tile icon → drag out onto a home page.

## 3. Overview (hold during a home swipe)

- Trigger: during a home drag, the finger rests (speed < ~120 dp/s) for 150 ms after the card has shrunk below ~70 %: haptic,
  the other recent apps' cards slide in from the left (cached snapshots, 1–3 ms each).
- Layout (iOS): cards at about 62 % size in a horizontal deck, overlapping, the most recent on the right; horizontal scroll with
  iOS physics; the card under the finger keeps following it until release.
- In overview: tap a card to open it (it grows to full screen), flick a card up to close the app (removes the task), tap empty
  space or swipe up from the bar to go home. The card window becomes touchable only while overview is open.
