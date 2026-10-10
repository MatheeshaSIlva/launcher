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

### Redesign (2026-10-10, after P1-P2)

Matheesha on the S24 (`d1fd9f0`): "The settings app is laggy and the transition to sub-menus suck. research for some
premium app UIs and find a good inspiration for our app. Try drafting a few design layouts in figma. And also, IOS apps
doesn't follow the exact design from their UI. research and replicate the look IOS has for their apps for our IOS theme
instead. Allow users to mix and match the themes for each element, even though everything is unified by default."

**Why it lags (emulator, framestats of a push, back and swipe, 120 frames):** GPU median 13.8 ms (p90 23.7; the S24's
budget is 8.3): every card is live glass (the lens over the blurred wallpaper) and every page draws its own full-screen
backdrop, both pages during a slide. Opening a page costs 100-150 ms of CPU in its first two frames (the whole page
composed and drawn at once, the shared title's overlay). Every frame redraws everything (glass and pages re-read their
place on screen). Not yet measured on the S24.

**How Apple's apps look (iOS 27 Simulator, `ios-reference` test50):** not like SpringBoard. Settings is a solid grouped
grey (#F2F2F7 light), white groups inset 16 pt with ~26 pt corners, 52 pt rows, 29 pt coloured icon tiles with white
glyphs, hairline separators from the label, a 34 pt bold large title. Liquid Glass only on the navigation layer: a round
glass back button (44 pt at 16, 62, chevron only), glass toolbar buttons, the Search pill floating at the bottom. Run 15
(`ios-reference/out/run15`, light and dark): only the root page has a large title; a sub-page has a small centred title
and opens with a hero card (a big icon, its name in bold, a description: General). Section headers are sentence case
("Notification Badges"), footers small grey text under a group. Scrolled, the large title hands over to the small one and
the content dissolves under a soft edge (no bar). Choices are check rows; switches sit right; Appearance chooses with
phone previews and a radio under each. A swipe back starts anywhere on the page (one from the middle went back); the page
on top stays solid and slides, the one beneath moves in parallax slightly dimmed, its large title riding with it, and the
round back button fades. Push: ~90 % of the way in the first ~80 ms, settled by ~0.3 s (CI frames too sparse to fit a
spring; `motion.sheet.push` stays 0.42 / 1.0). Dark: a black page, #1C1C1E groups.

**Premium apps (research):** restraint and generous spacing; one corner and shadow language; motion that reveals
content and carries meaning (Flighty); Android 16's Material 3 Expressive Settings: every page in containers, a colour
per category, heavier controls, springs.

**Drafts (Figma, "Launcher settings — design directions"):** A Native (the iOS theme as Apple's apps: grouped lists,
glass only on the back button and search; Look with a theme picker like iOS's Appearance, "Mix by element" rows and a
page per element with its live preview); B Gallery (a live hero of home, colour tiles that open into their pages,
themes as cards to swipe, "Use everywhere" or "only for" an element); C Studio (a dark room around a live preview with a
sheet of tabs; tap a part of the phone to theme just that part). Read with the layouts/themes model: A, B and C are
three layouts of the settings app (list, gallery, studio), each restyled by the theme; the iOS theme on the list layout
is Apple's Settings. To decide: which layout comes first (and whether the others become choices).

**Decided (Matheesha):** "I'd like a mix of B and C, that changes with the current theme tokens." Drafted as D in the
Figma file (start, Look, a part picked on the preview; each drawn with iOS 27's and Graphite's values): a live picture of
the phone above a sheet; the sheet holds colourful tiles (Layouts, Look, Motion, Phone) that open into their pages
inside it; themes are cards with "Use everywhere" or "only for" a part; tapping a part of the picture picks that part.
Everything is the active theme's (the settings app is an element: `comp.settings.*`): the page colour, the sheet's
material, corners and inset (iOS 27: a floating glass sheet, Graphite: flush and frosted), the tiles' tint (iOS 27:
tinted with their colour, Graphite: neutral with a coloured symbol), chips, buttons, type and every motion role.

Build steps (each checked on the S24 before the next):

| Step | What |
|---|---|
| S1 | The shell: the picture of home and the sheet (detents that follow the finger and spring on the preset), the tiles, a tile opening into its page inside the sheet and back (swipe or Back), today's pages restyled as grouped lists (solid groups: no glass per card); `comp.settings.*` tokens and motion roles; measured on the S24 |
| S2 | Look: themes as cards (each drawn in its theme), "Use everywhere", "Only for" a part (the parts' own pages), the picture showing the theme being looked at |
| S3 | Tap a part of the picture to pick it (status bar, panels, recents, drawer, home); the rest dims, the sheet shows that part's theme and layout |
| S4 | Motion (presets with a demo on the picture) and Phone |

**Baseline (S24, 2da9d45, Graphite dark):** every push and back lost 60-80 ms of the main thread in its first frame (the
page built and composed then), 3-10 missed refreshes a transition, the swipe back ~10; GPU ~6 ms; scrolling clean.

**S1 built (2026-10-10, emulator):** `settings/Pages.kt` rewritten as the studio: the picture of home (a new one fades in
over the last), a header (the title crossfading to the page's, a round back button growing in), the sheet laid out once
at its open size and moved (`comp.settings.sheet.*`: iOS 27 a floating panel 8 pt in with 38 pt corners, Graphite flush
with 22), chips (what you have now, each opening its page) and four tiles (a colour each, tinted by the theme's
`tile.tint`); a tile grows into the sheet from the size it sank to (its symbol and name riding along, fading), the others
step back, the sheet rises, the picture shrinks, the page's groups rise in a beat apart, all from one spring
(`motion.settings.open`, 0.5 / 0.86); Back, the round button or a swipe right (following the finger) shrink the page into
its tile. Pages are built one a frame after the app comes up and placed only while open. The bars' symbols follow the
page's colour. Not yet: the sheet itself is not draggable (it moves with the page), no search.

**S1 on the S24 (local builds, iOS 27 dark, 16 transitions: 6 opens, 6 Backs... each measured alone):** first build 1-6
missed refreshes a transition (first frames 25-55 ms). A Perfetto trace: opening recomposed the whole app (open/close
lambdas made anew, so all four pages recomposed: 14-19 ms) and placed the page for the first time (8-10 ms); fixed (stable
actions, Back in its own composable, titles and growing shapes composed ahead, presses as Animatables read in drawing,
pages always placed and moved out of sight). Then frames of 12-25 ms every ~110 ms: Compose re-reading its accessibility
tree (our gesture service counts as accessibility; every layer transform counts as a layout change); with the tree left
out while a page moves: 12 of 16 transitions with no missed refresh, the others 1 (a frame at the budget). Baseline was 3-10
a transition. Known: right after a theme change made inside the settings, the picture's clock shows no time until home
has drawn again (its glass has no size while built behind).

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
