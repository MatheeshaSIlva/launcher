# The settings app: the plan (phase C)

Status: **agreed 2026-10-10** (answers below, under "Decided"). Phase C of `docs/PLAN_LAYOUTS_THEMES.md`; builds on phase B (layouts,
themes, animations and switching between them).

## What it must be (Matheesha's words, and the requirements)

- "Really nice, filled with motion graphics, nice animations, a walkthrough on first setup, previews": the place where the
  three layers (layouts, look, motion) are chosen and edited.
- It changes with the selected theme (it is drawn with the theme's tokens, as everything else).
- Previews that show **exactly which value changes**; the "Launcher design" token editor was "vague and hard to understand".
- From the requirements: basic and advanced views with search across both; undo and redo, revert, history; an unconfirmed
  risky change undoes itself after a few seconds; the safe-settings screen stays separate and never themed.

## Principles

1. **Plain words first.** Every setting is named and described as the catalogues already do (`Components`, `MotionRoles`,
   `Layouts`); token keys only in the expert view.
2. **See it, then it is set.** Every control has a live preview next to it, made of the real component drawn small (the
   real notification card, control, menu, dock), with the part a property changes highlighted while it is touched.
3. **Changes apply at once and can always be undone.** No Apply buttons: a theme tapped is the theme (home takes it with
   the reveal, the settings app too); one undo bar, and a history of the session. Changes that could leave the phone hard to
   use (gestures, the shade) ask for a confirmation and undo themselves after 10 s without it.
4. **Everything moves.** Screens, cards and values move on the animation preset's own roles (springs and curves), so the
   settings app moves the way the launcher does, and a motion preset changes it too.
5. **Basic by default, everything in reach.** The basic view shows choices (layouts, themes, presets, a few options);
   "Show more" opens every property; search finds any of them, in either view.

## Structure

```
Settings
├─ (search)
├─ Your setup ............ a live miniature of home, the shade and recents as they are now
├─ Layouts ............... the five elements
│   └─ Status bar / Shade / Recents / Drawer / Home
│        ├─ layout gallery (cards with an animated preview of each)
│        ├─ where it lives (placement, shown on a phone outline)
│        └─ options (toggles, sliders, each previewed)
├─ Look .................. themes
│   ├─ theme gallery (each card drawn in its own theme)
│   ├─ colours: the theme's, or the wallpaper's (Material You), with the palette shown
│   ├─ fonts: text and titles; the phone's, bundled, Google Fonts (search, download)
│   └─ customise: by component (notification card, control, menu, dock...) or by style
│        (colours, materials, shapes, type); each property with its preview
├─ Motion ................ animation presets
│   ├─ preset gallery (each with a short animated demo)
│   └─ roles by group (MotionRoles): spring or bezier, a curve editor, the role played on a sample
├─ Phone ................. Shizuku and permissions (status, setup, guides), safety (restore system, the watchdog),
│                          updates, backup, about
└─ Expert ................ today's token editor, kept for theme authors
```

## How it is built

- **Jetpack Compose** (as agreed), its own activity in the same APK. The app has no AndroidX libraries yet: Compose
  brings them (about +4 MB in a release build after shrinking, more in debug builds).
- **No Material look**: Compose's layout, state, text input and accessibility, but every surface drawn by our renderer
  (`MaterialPainter` through `drawBehind`), every text style, colour and corner from the active theme. A bridge
  (`LauncherTheme`) gives Compose the tokens as state, so the settings app redraws when the design changes.
- **Motion from the preset**: a role's curve becomes Compose's animation spec (a spring by response and damping, a bezier
  as a cubic easing).
- **Previews of other themes**: the theme gallery draws each card in its own theme, so `Design` gets a way to resolve a
  theme other than the active one for a drawing (a preview scope). Everything else previews the active theme.
- **Previews are the real components**, drawn small with sample data (the notification card painter, Control Center's
  modules, the menu painter, a picture of home), never look-alike drawings.

## Steps (each checked on the S24 before the next)

| Step | What |
|---|---|
| C1 | Foundation: Compose added, the token bridge, the shell (navigation with shared-element motion, search, basic/advanced), the start screen with "Your setup" |
| C2 | Layouts: the five element pages (today: one layout each; the drawer's placements and home's options) |
| C3 | Look: theme gallery (preview scope), Material You with the palette, fonts with Google Fonts search, customise by component with previews |
| C4 | Motion: preset gallery with demos, the curve editor, roles by group |
| C5 | Phone: Shizuku and permissions with status, safety, updates, backup |
| C6 | First-run walkthrough and guides with motion graphics |
| C7 | Undo and history, auto-revert for risky changes; the token editor moves under Expert |

**C1 done (2026-10-10):** Compose added (the app's first AndroidX libraries: the debug APK went from 8 to 21 MB); the
bridge (`settings/Ui.kt`: `SettingsTheme` gives Compose the theme as a `Look` made again on every design, appearance or
setup change; text styles with the theme's fonts, colours, glass drawn by the launcher's renderer over the blurred
wallpaper, motion roles as Compose springs and easings); `SettingsActivity` (own task, "Launcher settings" in the drawer)
with its pages (`settings/Pages.kt`): the start page with "Your setup" (an exact picture of home, `HomePicture`-free: home's
display lists drawn on the GPU, `design/ViewPicture`) and the sections; Layouts made from the layout registry (each
element's layouts, where it lives, its options as toggles); Look (the themes, the wallpaper's colours); Motion (the
presets); Phone (Shizuku's status, safe settings, the developer panel with the updater); Expert (the token editor). A theme
chosen here applies at once: the settings app holds its old look and reveals the new one from the row tapped (home is built
again behind it). New shared components: card corner, list row, toggle, section heading, page margin (`component.*`). Entry
points: the drawer, home's edit menu ("Home Settings"), a Control Center control ("Launcher Settings", Utilities). Search and
the basic/advanced split move to C3, where there are enough properties to search. Known: content scrolls under the status
bar without a fade.

The walkthrough comes late on purpose: what it walks through (layouts, themes) is still growing in phases D and E.

### Polish (between C1 and C2)

Matheesha after C1: "pretty basic and barebones, which is fine for a first build. we need to turn this into a super
polished feeling app with motion, effects and attention to detail. we'll build it step by step and polish it along the
way." The feel he chose: **expressive throughout** (bolder motion everywhere, parallax, playful springs, items that bounce
in), on the preset's roles so a calmer preset calms the settings too.

| Step | What |
|---|---|
| P1 | Scrolling and the top of a page: home's iOS scroll physics, a large title that stretches when pulled and slides under the bar, the compact title rising into the bar, content dissolving into the backdrop under it (no hard bar) |
| P2 | Moving between pages: titles that fly, solid pages sliding over each other, a swipe back that follows the finger, groups rising in |
| P3 | Rows: symbol tiles, press and haptics, a Liquid Glass switch, a drawn check, values that roll when they change |
| P4 | "Your setup": the miniature crossfades when home changes, tilts with the finger, opens to full size |

**P1 and P2 done (2026-10-10, checked on the emulator):** pages scroll on `IosScroller` (`settings/Scroll.kt`, the
preset's rubber band and deceleration) and keep their place while in the stack. A row's name flies into the next page's
large title and the page's title shrinks into the way back (shared elements; on taps and Back). Pages are solid sheets:
each draws the blurred wallpaper fixed to the screen (`pageSurface`), the newer one slides over the older one both ways
with a shadow from its left edge while the older moves a third of the way in parallax and dims (`component.page.
beneath-dim`, `edge-shadow`, `edge-shadow-width`). A swipe right anywhere on a page pulls it back under the finger; let go
past half way or thrown right it goes, else it springs back, on the push role from the finger's speed. Groups rise in one
after another the first time a page opens. Found on the way: the backdrop stopped above the navigation bar (display
metrics leave it out: the window's size is used), a wallpaper read late popped in (it fades in now, `motion.wallpaper.
appear`), switching light/dark restarted the app at its start page (it keeps the page and crossfades), and a drag across a
row counted as a tap (an iOS tap now: let go within the touch slop). Known: halfway through a light/dark crossfade, text
blended from white to black is grey and faint for a moment (the shared `Appearance` blend, home too).

## Decided (2026-10-10)

1. The settings open from an icon in the drawer ("Launcher settings"), from home's long press on empty space (the edit
   menu's "Home Settings"), and from a Control Center control.
2. Changes apply at once, with undo (a session history; risky changes undo themselves after 10 s unless confirmed).
3. Cards with live miniatures at the top, grouped lists below; the theme reskins both.
4. The Google Fonts family list is bundled with the app, fetched once from the public google/fonts repository.
5. The settings' feel is expressive throughout (polish steps P1-P4 above), on the preset's motion roles.
