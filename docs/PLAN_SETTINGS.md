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
3. **Changes wait for Apply.** (Changed 2026-10-11, Matheesha: "every change is instant, so that transition animation
   plays a lot of times if we make a lot of changes".) Choices go into a draft the rows show at once; a bar rises with the
   first one (how many, Discard, Apply); Apply applies them all as one (one reveal), Discard forgets them. Back with changes
   waiting first says so; a second Back discards and leaves. Changes that could leave the phone hard to use (gestures, the
   shade) will still ask for a confirmation and undo themselves after 10 s without it.
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
presets); Phone (Shizuku's status, updates, safety and the developer tools, in the page since S5); Expert (the token editor). A theme
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
| S3 | Tap a part of the picture to pick it (status bar, panels, recents, drawer, home); the rest dims, the sheet shows that part's theme and layout (replaced in S5: the picture focuses on the part a page is about, and takes no touches) |
| S4 | Motion (presets with a demo on the picture) and Phone |

**2026-10-11 (Matheesha on S1: "the layouts section is a bit laggy. Swiping right goes back to the main screen - remove it -
it is sometimes triggered just trying to scroll down. ... let's add an apply/discard changes system"):** the swipe back is
gone (Back and the round button remain). The Layouts lag: scrolling missed 3-10 refreshes a scroll on the S24, frames of
12-17 ms every ~117 ms: Compose's accessibility re-read again (scrolling counts as a layout change too); a page now leaves
the tree while it scrolls (`IosScrollState.moving`, until the scroller settles). Apply and Discard: `Draft` in
`settings/Pages.kt` (theme, colours, appearance, preset, each element's theme, layouts, placements, options), the bar
(`ApplyBar`: rises with the sheet making room above it, a rolling count, Back's warning), `Design.batch` (listeners hear
once: one reveal). Checked on the emulator: 2 changes applied as one design change and one home rebuild; Discard; Back
twice. S2 started with it: Look shows the themes as cards (home as it looked in each, kept whenever the settings take its
picture: `HomePictures`; one not seen yet shows the wallpaper under that theme's sheet colour and accent,
`Design.previewColor`), a theme for each part (Follow or a theme, chips), the wallpaper's colours, light and dark; the
picture above shows the chosen theme's home when known.

**On the S24 (2026-10-11):** scrolling Layouts, 3 scrolls each way: 3-10 missed refreshes a scroll before; 3-8 with only
the page out of the accessibility tree (the rest of the tree still cost 4-5 ms a check); 0-2 with the whole app out of it
while a page scrolls (the misses left are frames of 3-4 ms: presentation, not our work). Opening and closing pages:
12 of 13 without a missed refresh, one with 1. The picture of home after the app restarted showed the wallpaper alone (no
home exists until it shows): home's picture in each theme is now kept on disk (`files/design/home-pictures/<theme>.webp`,
half size) and shown until a fresh one is taken; a cold start's held arrival is pictured at rest.

**Scrolling interruptible (2026-10-11, Matheesha: "if the app is scrolling down and I want to scroll up I have to wait until
the scrolling animation ends"):** a touch on a moving list consumed its own touch-down (so no row is tapped) and then, seeing
that touch-down consumed in its next pass, gave the gesture up: the finger stopped the list but could not drag it until it
lifted. And a flick whose whole travel arrived with the lift (one batch) was taken for no drag. `iosScroll` now reads every
position itself: its own touch-down is skipped, a flick past the slop counts with its speed. Emulator: a drag started during
a fling turns the list around. On the S24 after it: Layouts scrolls 0-1 missed refreshes.

**S3 (2026-10-11, emulator):** a part tapped on the picture of home: the status bar's band, or home below it (the panels,
recents and the drawer are not on a picture of home: their rows on Look are the way to them). The rest of the picture dims,
the part is outlined in the accent with its name in a tag beside the picture, all on one spring (`Studio.pick`, the appear
role); Look opens from the tiles, glides to the part's row (`Studio.rowY`, the page's own scroller) and lights it; the same
part again, or closing the page, lets it go.

**S4 (2026-10-11, emulator):** Motion shows the animation presets as cards, each drawing the curve its apps open on (sampled
from the preset's own `motion.app.open`, overshoot and all: `Design.previewCurve` reads any preset without applying it);
while Motion is open the picture of home plays an app opening from an icon and closing into it, again and again, on the
chosen preset's `app.open` and `app.close-size` (home dims behind it); a choice waits for Apply. "Each animation" leads to
Expert for now. Phone shows the build. With this S1-S4 stand; what is left of the settings plan: C2's live previews of
each layout, C3's fonts and properties by component, C5's Shizuku and permissions, C6 walkthrough, C7 history.

**S5 (2026-10-11; Matheesha on S1-S4: "the layout page still feels a bit laggy - definitely not 120fps. I would also like
if the preview screen zoomed in to focus on different elements. the preview itself does not have to be clickable - the
elements are too small in the preview it's bad for UX. Also, now that we have a settings app, unite the dev screen and safe
settings into it"):**
- Scrolling: a page's content is a GPU layer of its own, moved by the layer (`iosScroll`: `placeRelativeWithLayer`, the
  offset as its translation); placed by position, every row of a long page was drawn again into the page's list every
  frame. The fade under the sheet's top edge no longer takes the whole page through an offscreen buffer every frame: the
  content is drawn clipped below the band, and only the 18 pt band goes through a layer (the content's list drawn twice).
  Not yet measured on the S24.
- The picture focuses instead of being tapped (S3's picking is gone): on Layouts it follows the section being read (the
  element whose section is at the top of what shows); the frame takes that part's shape and the picture zooms into it, one
  spring (`motion.settings.focus`): the status bar a wide strip at about its real size, the shade's pull the top of the
  screen, recents the bottom edge, the drawer as a sheet the lower half; home, the settings and the drawer as a page the
  whole phone. Drawn in one pass (no layer moves). The picture of home now has our status bar on top (it is a window of its
  own, so home's picture had none: the bar draws itself as over home, on its thread: `StatusBarView.pictureOnHome`,
  `HomeBridge.statusBarPicture`), and home is pictured at rest even mid-arrival.
- Phone holds what were two apps: Shizuku (status, Connect), Updates (the build, check, install again), Safety (the state
  of the status bar, animation scales, restore without Shizuku, the notification; Restore system with its report; another
  home app; animation scales), Developer (gestures and our status bar as switches, frame report, watchdog, identity, the
  restart diagnosis, the live-card probe, the two recovery tests, reset home layout on a second tap) and the log (its
  newest lines, live while the page is open; Copy). `DevTools` holds the actions; `DevActivity` and home's "Launcher Dev"
  entry are gone; the plain safe settings screen stays, without a drawer entry, for the Restore notification (it opens
  even if the themes cannot load).

**S6 (2026-10-11; Matheesha on S5: "don't clip the phone preview into a small box. Instead, make it fade towards below,
behind the menu. The switching of the zoomed in place with scrolling is a bit odd ... I don't like the multicoloured cards
for the settings app overview for the IOS theme. make it follow the liquid glass theme instead"):**
- The picture: whole, it is the phone in its frame; focused, it zooms to the full width (the status bar at ~92 % of its
  real size) with the part in the room above the sheet, and fades away from it (below, behind the sheet; above, for a part
  at the phone's bottom edge, Recents), no frame and no box; the frame's shadow and rim go as it zooms. One spring for the
  band and the zoom (`motion.settings.focus`); the fade is a layer the size of the room, only while focused.
- Layouts: the elements in a picker at the top (it stays while the page scrolls), the chosen one's layouts, place and
  options below; another element slides in from its side as the last slides away (each its own spring, `motion.sheet.push`,
  interruptible), and the picture moves to it. The focus follows the choice, not the scroll.
- iOS 27's overview is Liquid Glass (`comp.settings.glass` 1): the page is the wallpaper blurred under the appearance's veil
  (the App Library's), the tiles and the chips glass (`comp.settings.tile.material`, `chip.material`), monochrome symbols
  and names in the labels' colours; there is no sheet at rest, it turns solid under a page as it opens (iOS 26's sheets turn
  opaque when expanded); the growing tile is glass giving way to it. Glass that moves with the sheet reads its place again
  every frame (`movingGlass`). Graphite keeps its coloured tiles on a solid sheet (`glass` 0).
- The picture of home is never taken before home has drawn at rest in this process (an all-black one was taken after a
  restart and kept as home's look).

**C2, where it lives (2026-10-11):** on Layouts the picture shows how the chosen element is reached, again and again: a
finger (a soft white dot with a trail, on the picture and zoomed with it) pulls down the top's left then its right (the
shade), swipes up from the bottom edge and rests (recents), swipes from the edge or up (the drawer, by its placement);
it follows the draft (a placement chosen before Apply shows at once). Each stroke on `motion.settings.hint`. The layout
gallery (a card per layout with its own preview) waits for a second layout per element.

**C3, colours and fonts (2026-10-11):** Look's Colours shows the wallpaper's palette (Material You's primary, secondary,
tertiary, surface and neutral in this appearance, `Design.systemPalette`), lit while the wallpaper's colours are chosen;
Fonts chooses the text and the titles' family (`sys.font.text`, `sys.font.display`): Inter, the phone's, serif, mono and
four from Google Fonts (downloaded the first time), each chip written in its own font; both wait for Apply (`Draft.fonts`).
Still to come in C3: customising by component with previews.

**C3, Google Fonts search (2026-10-11):** each font row has "More Fonts" on its title line (a chip at the end of the
sideways row was out of sight). It opens a sheet over the settings: a search field, Google's categories as chips (All,
Sans serif, Serif, Display, Handwriting, Mono), and the 60 best matches (the name, then names starting with it, spaces
ignored, then a word starting with it, then containing it; the more popular first; no query: the most popular), each name
in its own face. The list is Google Fonts' public one (`fonts.google.com/metadata/fonts`, 2.7 MB read as a stream, 1950
families), kept as a small table (`files/fonts/google/families.tsv`, read again from Google when two weeks old, fetched
only when the search opens; `theme/GoogleFontCatalog`). Previews are the regular cut through Play services' provider into
the cache (`FontPreviews`, three at a time, one family at a time: two rows asking together wrote one file twice), only for
rows on screen, and change nothing else (a theme's font arriving rebuilds every surface). A family chosen there waits for
Apply and becomes a chip on both rows (kept: the last six). Motion: the sheet rises and follows a drag on its top
(`motion.sheet`), the page beneath dims with it; rows glide to their new places as letters are typed, new ones fade and
rise in, gone ones fade (all interruptible); a row's name crossfades into its face when it arrives; a new chip grows in and
the row keeps the chosen chip in sight while it settles. The sheet is built after the pages and kept out of sight (built
in its opening's first frame, the emulator showed nothing for 220 ms and then the sheet already up); only the rows near
the visible part are built. Also: home's picture waits for a clock built again (a font change) to have its numerals.

**S7 (2026-10-11; Matheesha on S6: "don't remove the panel overlay you had earlier. I just wanted the buttons to be glass
themed. In the layout menu, the tabs feel more like buttons to filter results ... something like in the example c you made.
Also, during the animations, the fades become sharp and then goes back to fade ... If I come back to settings after a
wallpaper change, the transition in the settings is static"):**
- The panel is back at rest; the tiles and the chips on it are clear glass over it (they sample the panel's fill over the
  blurred wallpaper: `Look.drawGlass(under = ...)`); the card glass made for the wallpaper read grey on the panel.
- Layouts' picker is a segmented control (draft C): a capsule track, a thumb that slides to the chosen element (its name
  turning semibold as the thumb passes), tap or drag along it (the thumb follows the finger, settles where it is let go).
- The picture's fade is continuous: how much each end fades grows with how far the part is from the phone's edge there,
  and the fade never runs out of room (it went sharp for a moment when the part's band reached the end of the room).
- A look or wallpaper change while the settings are away is held and revealed when they show again
  (`SettingsActivity.deferred`): it played unseen and coming back showed the new look already still.

**S8, measured and looked at on the S24 (2026-10-11, recordings at 120 fps, frame stats, Perfetto):**
- Artefacts found and fixed: the segmented control showed whole from the first frame of the opening, over the tiles, and
  went at the end of the closing (it now rises and falls with the page); a dark box behind the picture wherever it faded
  (its shadow was cast by a shape in the page's colour and its rim filled under it: now an outward blur and a ring, inside
  the faded layer, so they fade with it; drawn outside it they also ran up the zoomed phone's sides to the top of the
  screen); the fade cut hard at about half strength while the picture pulled back (an end now fades as far as the phone
  goes past the room); two elements' pages at half strength over each other mid-switch (each now shows only in the upper
  part of its way); a long pan (the top to the bottom edge) slid through the whole phone at full zoom (it now pulls back a
  little on the way); Motion's demo opened a black square from the middle of home (now the dock's middle icon grows into an
  app's page, the icon fading into it).
- Smoothness: a page opening composed the whole Phone page (its log read which page was open: 14 ms), every page's groups
  (its rise value was made again: up to 27 ms), the picture and Layouts on an element switch (11 ms); a tap on Look or
  Motion read and parsed every theme file (`Design.themes()`, now cached until a reload: 20 ms); the accessibility tree was
  taken away and given back at every scroll's start and end (9-15 ms; now there only for a screen reader); a gradient
  made every frame (the picture's fade, the hint's trail, the page fade) set off native-memory garbage collections. After:
  every action of the bench (segment taps and drags, scrolls of Layouts, Look and Phone, every page opened and closed)
  0-2 missed refreshes, most 0-1; the late frames left cost us 2-9 ms of CPU and 5-7 ms of GPU (not our frame's work).
  App Library folder: 2 missed refreshes, the icons in order.

**C3, Material You's styles and intensity (2026-10-11; Matheesha: "allow the users to control the intensity ... aren't
there different profiles for material you - expressive, tonal etc.?"):** Look's Colours has a Style (the phone's own
palette, or one made here from the wallpaper's main colour in Android's styles: Tonal, Vibrant, Expressive, Neutral,
Monochrome) and an Intensity slider (0 grey, 100 % the style's own, up to 200 %), both waiting for Apply, the swatches
showing the choice live. `design/MaterialYou` makes the palettes (tonal palettes in CIELAB lightness, the styles' hues
and chromas after Material's scheme definitions; Android's names `system_accent1_500` and the 14 roles) and scales "the
phone's" palette's chroma for the intensity; tokens `sys.color.style`, `sys.color.intensity`. A settings slider
(`SettingsSlider`: a drag that starts sideways is its, one that starts up or down the page's scroll).

**Layouts' picker B (2026-10-11; Matheesha: "the tab control is a little crammed ... what are the alternatives?", chose
B):** every element is its symbol (status bar, bell, recents, apps, home, gear), the chosen one wider on the thumb with its
name beside the symbol (`SymbolSegmented`). One spring moves it all: the thumb slides and takes the new one's width, the
old name fades as its segment narrows, the new one's comes as it widens; a drag along it carries the thumb under the
finger (places spread evenly) and chooses where it is let go. Also fixed: after a cold start with the settings in front,
home read its wallpaper behind them and began its fade-in there; the settings pictured it half faded (a black home with
tinted glass, kept on disk as home's look) and without the bar (its picture had not come back in time). The wallpaper
now fades in only when home can be seen, home's picture shows it at rest, the settings picture home again when its
wallpaper arrives, and ask for the bar again if it did not come.

**Sizes and the font search's speed, measured on the S24 (2026-10-11; Matheesha: "the tabs in the layout is way to small.
Please follow proper UI and UX rules. The more fonts popup is a bit laggy"):**
- Every control's touch area is at least 44 x 44 pt (`comp.settings.target`, Apple's rule; Android's is 48 dp): chips
  are drawn 34 pt high in a 44 pt touch strip, More Fonts and the sheet's close the target's height, the slider and the
  Apply bar's buttons 44 pt. Layouts' picker: option B again (Matheesha: "only show the label of the active tab, just
  like the example B"; a round with a name under every symbol came first), at the rules' sizes: 48 pt high, symbols
  22 pt, the chosen one's name 13 pt semibold beside its symbol; the others share what the chosen one leaves once the
  longest name ("Status bar") fits it, each at least a 44 pt target (47 x 48 pt on the S24); a phone too narrow for that
  sets the names smaller.
- The font search, before -> after (frame stats per action, S24): a letter typed 2-8 missed refreshes (main-thread frames
  of 50-117 ms) -> 0-2; a scroll of the results 4-11 -> 1-6; the opening's first frame 51 -> 27 ms; the GPU per frame
  under the open sheet 7.5 -> 5 ms. What it was: every `look.text()` made a new font family (Compose compares them by
  the wrapper's identity: every text that composed again was measured again, all over the settings); the search ran in
  composition (25 ms a letter in the debug build); the field's words were the sheet's state (a letter composed the whole
  sheet); rows were keyed inside an `if` in their loop (a row whose place changed was built anew: eleven in one frame);
  every row of the list was built at once and measured with the group's animated height (every row measured again each
  frame). Now: styles and families made once per look; the search off the main thread; the field keeps its own words
  (`TextFieldState`); rows built two a frame near the visible part only (a cascade), measured once; the settings under
  the sheet drawn only above it.

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
2. Changes wait for Apply (a draft, one reveal for all of them; replaced "at once, with undo" on 2026-10-11).
3. Cards with live miniatures at the top, grouped lists below; the theme reskins both.
4. The Google Fonts family list is bundled with the app, fetched once from the public google/fonts repository.
5. The settings' feel is expressive throughout (polish steps P1-P4 above), on the preset's motion roles.
