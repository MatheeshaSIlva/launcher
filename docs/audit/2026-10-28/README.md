# Accuracy audit, 2026-10-28: every element against iOS 27 and Android 17

Asked by the owner after using a real iPhone: the Notification Center pull has no animation after the release, iOS's
squash and stretch is missing, Control Center has pages, the sliders and switches do not match iOS (nor Pixel), "there
might be more". Five read-only research passes, one per area, each comparing layout, look, motion and interaction with the
reference (the repo's own iOS 27 Simulator and Android 17 emulator captures first, then Apple's and Google's documents and
AOSP's source), each rated ok / off / wrong / missing with file:line evidence:

| Area | File | Gaps filed |
|---|---|---|
| A. iOS shade: Notification Center, Control Center, banners | [A-ios-shade.md](A-ios-shade.md) | 19 proposed (2 folded into B-037) |
| B. Controls: switches, sliders, segmented, steppers, both themes, and the engine's tokens and flags | [B-controls.md](B-controls.md) | 11 |
| C. Home and drawer (iOS): grid, dock, folders, widgets, menus, App Library, Spotlight | [C-home-drawer.md](C-home-drawer.md) | 15 |
| D. Gestures, app open/close, App Switcher, status bar, settings app | [D-gestures-apps-statusbar.md](D-gestures-apps-statusbar.md) | 22 |
| E. Pixel (Android 17) across elements | [E-pixel.md](E-pixel.md) | 14 |

81 proposals, de-duplicated into **73 backlog bugs, B-066 to B-138** (3 P1, 28 P2, 42 P3; all "(proposed)": severity is
the agents' call until the owner confirms). Merged: A1 and A2 (Notification Center's commit and close) are B-037 step 2;
A6, C3 and D17 (glass that does not react to touch) are one bug, B-066; A11 into B-086, D7 into B-083 and B-085, E7 into
B-089; E14 (Pixel QS pages "missing") is not a bug: the Pixel pager is built, the owner's "pages" is iOS Control Center's
(B-067).

## What is accurate

- The iOS shade at rest: layout, look and materials of Notification Center, Control Center and banners (measured against
  the kit and the Simulator; `docs/IOS27_EVALUATION.md`).
- Rubber-banding wherever iOS has it (home's pages, lists, the home gesture's card), on UIScrollView's curve.
- The Pixel shade: layout and Material 3 Expressive's own squash and shape morphs (the quick row's squish, tiles pushing
  their neighbours, corner morphs, the tap pop, the play button) and its tile pager with dots.
- Text weights in the Pixel shade (B-061, measured).

## What is missing, by theme

1. **Notification Center's open and close (the owner's first report)**: B-037 step 2. Released past 62.5 %, the content
   fades in over 70 ms with no clock drop or zoom; the close is a flat sheet. Step 1 (the clear glass pull) is built on
   `fix/b037-ios-glass-pull`, never merged (awaiting the owner's S24 check).
2. **Squash and stretch** (the owner's second report): nothing deforms with velocity. The foundation is one shared
   primitive (B-116: `motion/Stretch.kt`, `motion.stretch.*`), then its uses: banners pulled (B-068) and arriving out of the
   island (B-070), glass that flexes and lights under the finger (B-066), the long-press menu popping out of the icon
   (B-094), the app launch's width before height (B-107), the thrown close (B-108), sliders squashing at their ends (B-086,
   B-087), Notification Center's overpull (B-073).
3. **Control Center pages** (the owner's third report): B-067 (P1). Capture iOS 27's pages first.
4. **Switches and sliders** (the owner's fourth report): B-082, B-083, B-085, B-086 to B-092. B-controls.md proposes the
   engine's tokens and flags for both themes (`component.toggle.style` "ios-glass" / "m3", `thumb-lens`, `stretch`,
   `component.slider.style` "ios-glass" / "m3-expressive", `thumb-pressed-width`, `stop-size`, `component.level.*` ...)
   with each value's source, and a 10-step outline; the engine needs a magnifying lens first (B-092).
5. **Home**: no folders (B-093, P1), no widget stacks (B-096), the Edit menu's Customize and Edit Pages (B-095), the Today
   view (B-099, needs the owner's plan), badges in the App Library (B-097).
6. **Gestures**: the App Switcher's stop detection (B-109), the arc swipe (B-110), the gesture pill's colour (B-111).
7. **Status bar**: no island capsules for timers, media, calls (B-112); geometry not in tokens (B-114); tone late (B-115).
8. **Pixel**: its motion borrows iOS's (B-127, B-128); the magnetic swipe (B-129); tile details as dialogs (B-130); its own
   heads-up (B-131). Not built for Pixel at all (scope, not bugs): status bar, home, drawer, recents, menus, a real Pixel
   motion preset (E-pixel.md).
9. **Never measured** (judged values, captures exist): App Library and Spotlight pulls (B-098), home's depth and unlock
   (B-105), the App Switcher's springs (B-126), the page snap (B-102).

## Suggested order (for the owner to confirm; the owner sets milestones)

1. B-037 step 1 merged after the owner's S24 check, then step 2 (Notification Center's commit and close).
2. B-116 (the stretch primitive), then B-066 (glass press) and B-094 (menu pop): the squash and stretch the owner sees most.
3. B-092 then the controls' tokens and flags (B-083, B-085, B-082) for both themes.
4. B-067 (Control Center pages) after an iOS 27 capture.
5. B-093 (folders).
6. B-107 to B-110 (gestures), B-112 (status bar capsules).
7. The Pixel motion items (B-127, B-128, B-129).

Open questions for the owner: (a) the new controls' motion roles in a new "Switches and sliders" moment or in "Settings
app" (B-controls.md); (b) the Today view on home's first page (B-099); (c) settings swipe-back that follows the finger
again (B-113: removed on 2026-10-11 because it fired during scrolls).
