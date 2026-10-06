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

Built in round 2 (`home/EditMode.kt`, `home/ContextMenuView.kt`), differences from the plan above:
- Menu items: up to 4 app shortcuts (LauncherApps, we are the home app), "Edit Home Screen", "Remove from Home Screen" (red;
  the app stays in the App Library), "App Info". Widgets: "Edit Home Screen", "Remove Widget". Home blurs (18 pt) and dims
  behind; the item lifts 1.06× above the blur, its label hidden; the glass panel grows from the item's side.
- Moving the finger after the menu opened hands over to a drag: the menu goes, the blur fades out, the copy keeps its lift.
- Lift 1.08× (not 1.1), wiggle 1.6° at ~4 Hz (widgets 0.6°). A move is applied after the finger rests 110 ms on a spot (not
  while just passing over). Page turn after 550 ms at an edge, then every 900 ms.
- "+" (top left) adds the clock widget to the page on screen (the only widget so far); full pages push their last items onto
  the next page. Empty pages are dropped when edit mode ends.
- Leave: "Done", a tap on empty space, back, home pressed, a swipe up on the gesture bar, or home going out of sight.
- Round 3: "Edit" (menu: Add Widget) replaced "+"; real Android widgets through the gallery sheet; dragging out of the App
  Library and Spotlight (long press, then move). Not yet: folders (hover to make one), resizing a placed widget (iOS 27's
  corner handle). Shortcuts, App Info and widget setup screens open with the stock animation.

- Round 16: widgets resize in place with an animated card (handle or Size row); "Glass Background" for Android widgets;
  Hide/Show Widget Names and Hide/Show App Names (saved, fading); the edit bar slides in and out; new items grow into
  their cells, removed ones shrink away. The gallery: grabber, search field, featured glass clock, rows with widget counts,
  carousel with dimmer neighbours, animated presses. A closing App Library folder takes no touches (another can open at
  once); an icon can be opened while its folder still grows; the A-Z index animates (bubble, magnified letters, list on
  a spring). Home arrives animated after unlock and on a cold start. Floating windows (PiP, pop-ups) stay visible
  through holes in the card window.

## 3. App Switcher (hold during a home swipe) — built (round 8)

- Trigger: during a home swipe, the finger stays within 12 dp for 150 ms once it is at least 56 dp above where it started
  (close to the bar; it was "card below 72 %", ~150 dp, which Matheesha found too far): haptic tick, the deck opens around
  the held card (`switcher/DeckView.kt`, geometry and springs in `MotionProfile.switcher`).
- From the home screen too: a swipe up that rests the same way opens the deck with the most recent app focused (its card
  rises into place from below, the others slide in); lifting without a rest is still the Home button (page 1, Spotlight
  closed). Home stays the window in front until an app is chosen; "home" from this deck just brings the picture forward.
  As on iOS (round 10): while the swipe up on home goes on, home recedes with the finger (the picture of home, zoom and
  blur, up to depth 0.6); a rest slides the whole deck in from the left as one (the recent apps lie to the left of home:
  a swipe right on the bar from home brings the last app in from the left; "app cards will appear from the left of the
  display", iPhone Life), home receding the rest of the way and dimming; a release without a rest springs home back, then
  does what the Home button does. Going home from any deck slides it back out to the left while home comes forward.
  (Round 9's rise-in cascade was replaced: Matheesha did not like it.)
- Up-to-date pictures (round 9): the app in front's picture is taken again ~0.4 s after its screen last changed
  (accessibility "content changed"/"scrolled" events, only the package is read; at most every 1.2 s, never later than the
  regular 6 s refresh); a gesture that starts within that window takes a fresh one at the touch (the card shows the latest
  picture at once and the fresh one replaces it ~10-20 ms later); the open deck re-takes the card of the app behind it if
  that app changes its screen; an app that goes to the background counts as changed, so home's deck fetches the system's
  snapshot taken as it left.
- Layout from Apple's iOS 27 App Switcher illustration: cards at 68 % of the screen, vertical centre at 51.5 %; the focused
  card's left edge at 22.5 % of the width; newer cards spread to the right (step 1.0 card widths), older ones stack tightly
  to the left (step 0.08), joined by a smooth curve so cards change speed without a jump as they pass the focus. Newer cards
  lie on top and cast a soft shadow onto the card below. Each card has its icon above its left edge; the focused card also
  its name. Home behind: the picture of home, receded, blurred and darkened (28 %).
- While the opening swipe is still down the deck stays focused on the held card and follows the finger sideways; on release
  the focus moves to the previous app (iOS), or a flick up goes home.
- Open deck: sideways drag scrolls (rubber band at the ends), a throw is projected 0.22 s ahead and comes to rest on a card;
  tap a card to open it (it grows to full screen, its app comes to the front meanwhile; the app the swipe started in is still
  there underneath, so it returns at once); flick a card up to close its app (IActivityTaskManager.removeTask through the
  shell), the cards beside it close the gap on springs; tap empty space or swipe up from the bottom edge to go home.
  A card lifted or flicked away keeps its place in the stack: every newer card stays in front of it, also while it flies
  off (it used to jump on top when let go). Going home, the cards on screen slide out to the left by just enough to clear
  the edge; cards off screen stay out of sight (a fixed distance made the newer cards of a deck scrolled to older apps slide
  in from the right and stop mid-screen).
- Touches: a full-screen invisible input window, added on its own thread only while the deck is open (adding windows or
  changing their flags re-lays them out, 6-80 ms, never on the thread that draws the cards; an idle window would still be a
  layer to compose every frame). It lies over the bar, so the bottom edge is handled there.
- Every recent app has a card (up to 50, the system's own limit): the gesture's quick lookup gets the newest 8, the full
  list follows on the same worker and joins the deck (older apps, on the left).
- Cards show the system's snapshots. At the touch only the first 3 are fetched; the rest as each card comes into view: full
  size for the focused card and its neighbours, the system's reduced copy for the stacked slivers on the left, and cards far
  off screen let theirs go (each full-size picture is ~10 MB; it is the native-memory count that set off GC pauses). Kept
  pictures between gestures: at most 16. Only the visible strip of each covered card is drawn.
- Not yet: the iOS "slow release mid-screen" trigger.
