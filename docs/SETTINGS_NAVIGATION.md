# Settings navigation: research and proposal (2026-10-19)

Matheesha (2026-10-18): "settings navigation can be overwhelming for the users. Sure, the previews help but we need to find
a way to make it more intuitive and easily understandable without sacrificing functionality. Research methods on how to do
that, study similar applications and check how they've implemented it. I think we have to find a perfect balance between
what are basic settings and what are advanced, divide them and collapse large sections into submenus that can be easily
navigated." Parked until the build was polished; started 2026-10-19.

This document: what the settings hold today (an inventory), where they get heavy and why, what other apps and the platform
guidelines do, the methods for getting the split right, a proposed structure, how to check it with real people, and the
decisions that are Matheesha's. The same as a page, today's pages beside the proposal with folds that open:
https://claude.ai/artifact/Qr5cqasW71c8HVu4ieAsMU (rows as the code builds them at d932fb9).

## In short

- **Most of the settings are already shallow and calm.** Look, the parts' pages and Phone are one or two levels deep, with
  6-15 rows each. The weight is in **Motion**: 23 moments and 133 single animations, reachable from two places, each
  animation's spring or curve editor three levels down, and Motion's own page at up to 28 rows (Android's guideline says
  10-15).
- **Basic and advanced sit side by side with nothing telling them apart**: a part's page puts "how many icons across"
  next to the springs of its animations; Look puts the theme next to the palette's chroma and key colours.
- **Proposal: three layers on every page.** *Essentials* in sight (what most people change); *More* in collapsed sections
  that say what they hold; *Advanced* in a collapsed section at the page's foot, labelled as such (AOSP's rule: only when
  it hides at least three items). Pages never go more than two levels deep (NN/g); expert editors open in place.
- **Motion becomes three steps**: pick a preset, adjust Speed and Bounce, then "By part" collapsed by part, a moment's page
  holding its style and feel up top and *Each animation* (today's Every detail) collapsed under Advanced.
- **Validate with people, not taste**: a short open card sort, a frequency survey to decide what is "essential", then a
  text-only tree test of today's structure against the proposal, with tasks such as "make apps open faster".

## 1. What the settings hold today

Opened from the drawer, home's edit menu ("Home Settings") and the "Launcher Settings" control. The start page: a search
field, "Your changes" (when there are any), and eight tiles. Depth counts taps from the start page.

| Page (depth 1) | What is in sight | One level down (depth 2) | Further |
| --- | --- | --- | --- |
| **Look** | Theme cards; Colours: theme's or wallpaper's, your colour, the palette; Light & dark; Fonts ›; Size ›; the settings app's own theme (with a second theme) | *Adjust colours*: style, intensity, the palette's colours (tap one to set it); *Fonts*: text, titles (font search sheet); *Size*: follow the phone, everything, text | Font search (3) |
| **Motion** | Presets (cards); Feel: Speed, Bounce, Reset all; 23 moments in six groups (by part) | A moment: Style (2 moments, 7 styles each), Speed, Bounce, a character slider (4 moments), Every detail: its animations (2-12) | An animation's editor, opened in place: spring or curve, graph with handles, shapes, sliders (3) |
| **Home** | Caption; Grid: columns, rows, dock; Options: app names, widget names, new apps on home; its theme (with a second theme); Motion: 6 moments | The moments' pages | Their animations (3) |
| **Apps** (App Switcher) | Caption; Motion: 4 moments | Moments | Animations (3) |
| **App Library** | Caption; Where it lives (3 places); Grid: columns; Motion: 2 moments | Moments | Animations (3) |
| **Notifications & Control Center** | Caption; Grid: control columns; Motion: 6 moments | Moments | Animations (3) |
| **Status bar** | Caption; Motion: 3 moments | Moments | Animations (3) |
| **Phone** | Shizuku (status, connect); Updates; Safety (restore, two status lines, other home app); About (build: 7 taps for Developer); Developer (once on) | *Developer tools*: 17 tests and switches; the token editor (679 tokens, its own screen) | |

Across the app: search over every setting (moments and single animations rank lower), "Your changes" with reset, a dot on
every changed row, the Apply bar (changes wait in a draft), and a stage that draws the part as you set it.

**Counts.** About 45 settings outside Motion (Look ~14, the parts ~16, Phone ~8, plus the layout choices); Motion 3 at the
top, 23 moments with 2-5 controls each, 133 animations with 2-5 parameters each; Developer 17 more; the token editor 679.

**Rows in sight per page** (rows and card strips, before anything is opened, every row that shows only sometimes counted:
your colour, a second theme's rows, Connect, Developer, Reset all and the changes rows; Android's guideline: more than 10-15
overwhelms): Look 10, Home 15, App Library 9, Notifications & Control Center 10, Status bar 6, Apps 7, Phone 10, **Motion
28**, Developer tools 17, a moment's page 6 (its Every detail is already folded: 2-12 animations once opened). The changes
row ("N changes from the theme") sits at each page's foot.

## 2. Where it gets heavy, and why

1. **Motion is the size of everything else together**, and its depth is the only place we go three levels down (Motion ›
   moment › Every detail › an animation's editor). NN/g: past two levels people get lost moving between them.
2. **The same moment pages are reached from two places** (Motion lists all 23; each part lists its own). Android's
   guideline allows a setting in several contexts as a link to one dedicated screen, which is what we do; but on Motion's
   page the full list makes it the longest page we have, and a part's page ends in six motion rows most people never need.
3. **No visible line between basic and advanced.** The palette's style, intensity and key colours sit one tap from the
   theme; a moment's springs one tap from its Speed slider; a part's animations right under its grid. NN/g: "placing
   something on the initial display signals that it is important", so every advanced row we show says "you should look at
   this".
4. **New words with no scent.** "Moment", "Feel", "Every detail", "character" ("How far home steps back") are ours; the
   descriptions are good, but the labels do not say *advanced* where it is.
5. **Pages that hold only motion** (Apps, Status bar): the tile promises the part, the page holds a caption and animation
   rows. Fine once the parts have layouts (the element rounds add them), odd until then.
6. **"Phone" is a vague name** (Android's guideline warns against vague titles): it holds the launcher's connection,
   updates, safety and about, none of them "the phone".

What works and stays: the stage (every review so far found the drawings helpful), search with places ("Home › Grid"),
"Your changes" and the dots (KDE's Highlight changed settings, done well), the draft and Apply bar, captions instead of
rows that choose nothing.

## 3. What others do

| Product | How basic and advanced are split | What we take |
| --- | --- | --- |
| **iOS** (Settings, home screen's Customize) | Few settings; grouped lists one or two levels deep; the home screen's look is edited *on the home screen* (long-press › Edit › Customize: a small sheet with size, appearance, tint). Apple's guidance: frequently changed settings in the main UI, occasional ones in a separate view, rare ones in Settings. | Edit in context with a small sheet; keep pages short; frequent first. |
| **Pixel** (Wallpaper & style) | One customisation hub: wallpaper, colours (wallpaper's or "Other colors"), themed icons, grid, lock screen shortcuts, all with previews; nothing expert. | A hub for the look with previews; colours as "from the wallpaper / your own". |
| **One UI + Good Lock** | Settings stay ordinary; power customisation (Home Up, QuickStar, LockStar, NotiStar) lives in a separate app of modules. | Expert tools apart from the everyday path (our Developer and token editor already are). |
| **Nova Launcher** | Many categories (Home screen, App drawer, Dock, Folders, Look & feel, Gestures, Search); settings search since 6.0; experimental features in a hidden "Labs". | Search; a hidden place for what is experimental. Also the cautionary tale: power by sheer breadth. |
| **Lawnchair** | Categories like Nova's, an "Experimental" menu behind the overflow, an early "advanced" preference group. | An Advanced group per page rather than one global place. |
| **Niagara** | Deliberately few toggles; they decline features that add complexity; users still ask for "a cleaner settings menu". | Saying no is part of the design; even a small list needs tidying. |
| **KWGT / KLWP** | Presets first, then a layer editor (items, layers, globals): easy to start, deep to master. | Presets as the first layer, full control behind them (our presets › Feel › moments › animations already is this shape). |
| **GNOME** (Settings vs Tweaks) | Supported options in Settings; hidden ones in a separate, unsupported Tweaks app; each move into Settings asks "could a better default solve it?". | Ask that question of every setting; keep expert tools visibly apart. |
| **KDE Plasma** | Highlight changed settings (since 5.20), reset to defaults. | Already done ("Your changes", dots). |
| **Windows 11** | A Settings home page of cards for what is used most, and "recommended settings" that adapt. | A short essentials strip on our start page. |
| **macOS Ventura+** | Moved to iOS's sidebar and forms; criticised for inconsistency and fit, defended for familiarity. | Consistency of one pattern across pages matters more than which pattern. |

## 4. Methods and principles

**Progressive disclosure** (NN/g): show the few options most people need, move the rest to a secondary level reached on
request. It helps novices (fewer mistakes, focus) and experts (no scanning past rarities). It works only if (1) the split is
right, decided from task analysis, field studies and how often things are used, and (2) the way to the secondary level is
easy to see and its label says what is behind it (information scent). Two levels is the practical limit; with more,
simplify first, or group the advanced features into logical chunks and test. *Staged disclosure* (a sequence, as a wizard)
is the other form: right for first-run setup, wrong for settings people revisit.

**Android's settings guidelines** (AOSP): frequently used settings at the top; more than 10-15 items on a screen is too many
(move some to their own screen); hide rarely used settings with progressive disclosure, labelled "Advanced" only when at
least three items are hidden, its one-line subtext listing what is hidden; a setting needed in several contexts gets one
screen, linked from each; show each setting's state under its title; avoid vague titles; search results show the place.

**Apple's guidance**: offer as few settings as possible; put frequently changed settings in the main interface, occasional
ones in a separate view, rare ones in Settings; do not duplicate system settings.

**For deciding the split, with people** (NN/g's IA methods):
- *Open card sort* (~15 people): each setting on a card, people group and name the groups. Shows the words and groups people
  expect ("is a folder's animation Home or Motion?").
- *Frequency and importance survey*: which settings would you change in your first week, which ever? The answer is the
  Essentials layer.
- *Tree test* (text only, no design; ~50 people for stable numbers, useful from 15): give tasks, see where people look for
  each setting in a structure. Run on today's tree and the proposal; compare success rate, directness, time.
- *First-click test* on the start page: where would you tap to "make icons bigger"?
- *Task-based sessions* on the phone (5 people find most problems): watch, do not help.
- *Later, with consent*: count which settings are opened (on the device only, or opt-in) to move things between layers.

## 5. Proposal

### The three layers

| Layer | What goes there | How it shows |
| --- | --- | --- |
| **Essentials** | What most people change in their first week (the survey decides; a first guess below). | In sight at the top of the page, the stage drawing it. |
| **More** | Finer control most people never need but understand: fonts, sizes, a part's own theme, a moment's speed and bounce, styles. | Collapsed sections ("More for Look: Fonts, Size, Settings app's theme"), a row each, opening in place; or a sub-page where a set is long. |
| **Advanced** | What needs the design system's or the motion system's vocabulary: palette style, intensity, key colours; each animation's spring or curve. | A collapsed section labelled **Advanced** at the page's foot (only with three or more items), its subtext naming them; opened in place, remembered open per page. |

Developer tools and the token editor stay where they are (Phone › Developer after seven taps): that is our Good Lock.

Rules: no page deeper than two levels (start › page › one sub-page); an expert editor opens in place, never as a third
page; ten to fifteen rows in sight at most; every collapsed row says what it holds; one home per setting, other places
link to it.

### Page by page (first guess at Essentials; the survey and card sort correct it)

**Start page.** Search on top (as now). Under it a short **Quick** strip of the most used essentials, set right there:
Light / Dark / Auto, Colours (theme's / wallpaper's), the animation preset. Then "Your changes" (as now). Then the tiles,
in two groups: *Your phone*: Home, App Library, Apps, Notifications & Control Center, Status bar; *Style*: Look, Motion;
and **System** (today's Phone, renamed).

**Look.** Essentials: Theme; Light & dark; Colours (theme's, wallpaper's, your colour). More: Fonts ›, Size ›, Settings
app's own theme. Advanced: Colour style, Intensity, the palette's colours (today's palette row and "Adjust colours"). 7
rows at most (the theme, light and dark, the colours and your colour, More, Advanced, the changes row) instead of 10.

**Motion.** Essentials: Presets; Feel (Speed, Bounce). More: **By part**, six collapsed sections (Apps, Home, App Library,
Notifications & Control Center, Status bar, Everywhere), each naming its moments in its subtext; a moment's row pushes its
page. 11 rows at most (the presets, Speed, Bounce, Reset all, the six collapsed parts, the changes row) instead of 28. A
**moment's page**: Style (where there is one), Speed, Bounce, its character; then
**Advanced: Each animation** (collapsed; today's Every detail), each animation's editor opening in place. Depth to a
spring: 2 pages and an expansion, as today, but labelled.

**A part's page** (Home, App Library, Apps, Notifications & Control Center, Status bar). Essentials: Layout and Where it
lives (when there is a choice), Grid, Options. More: its own theme stays a row (a fold of one row saves nothing); its
**Motion** folds into one row naming its moments ("Motion: Opening and search, Menus and sheets"), leading to the same
moment pages Motion has. Home: 10 rows at most instead of 15.

**System** (today's Phone). Shizuku, Updates, Safety, About as now; Developer once turned on.

**In context** (Apple's and iOS's rule: frequent changes in the main UI). Home's edit menu gets a **Customize** sheet with
home's essentials (grid, names, the look's light/dark and colours) over home itself, and "All settings" to open the app;
a long press on the status bar or Control Center's edit mode open their own pages.

### Before and after

Rows in sight at most (every row that shows only sometimes counted):

| | Today | Proposal |
| --- | --- | --- |
| Rows in sight, Motion | 28 | 11 (presets, Speed, Bounce, Reset all, six collapsed parts, changes) |
| Rows in sight, Look | 10 | 7 (theme, light and dark, colours, your colour, More, Advanced, changes) |
| Rows in sight, Home | 15 | 10 (caption, Grid 3, Options 3, its theme, Motion collapsed, changes) |
| Rows in sight, start page | 10 | 13 (the Quick strip's three choices added) |
| Pages deep to the deepest control | 2 + an editor | 2 + an editor (labelled Advanced) |
| Routes to a moment | 2 full lists | 1 list collapsed by part + links from parts |
| Advanced controls labelled as such | none | all |
| Essentials changeable without opening a page | none | Light/dark, colours, preset (start page); home's from home |

## 6. How we check it

1. **Card sort** (open, ~15 people, online, 20 minutes): ~45 cards (every setting outside Motion's single animations, the
   23 moments as cards). Output: groups and names people expect.
2. **Survey** (same people or more): "which of these would you change in your first week / ever / never?" Output: the
   Essentials list per page.
3. **Tree test** of today's tree and the proposal, text only, 10 tasks each, people split between the two, for example:
   "Make the icons on your home screen bigger"; "Make apps open faster"; "Stop app names showing under icons"; "Use your
   wallpaper's colours everywhere"; "Make folders open without bounce"; "Show more controls across Control Center";
   "Change the font of titles"; "Find the version you are running"; "Make the App Library open by swiping up"; "Undo
   everything you changed in Motion". Pass: 80 % found, most of them directly.
4. **Five sessions on the phone** with the winning structure built, people new to the launcher, tasks as above, think
   aloud.
5. The tree test can be a page of ours (an artifact with a shared database for answers), so friends can take it from a
   link without accounts.

## 7. Decisions for Matheesha

1. **The three layers and the two-level rule**: yes, or a different split?
2. **A global "Show advanced" switch** in addition (off by default, opening every Advanced section)? Recommended **no**: the
   sections already stay open once opened, and a mode hides features from people who do not know the switch exists.
3. **Where moments live**: one list under Motion with links from the parts (proposed), only under the parts, or only under
   Motion?
4. **The Quick strip** on the start page, and which three or four essentials it holds.
5. **Rename Phone to System**.
6. **A Customize sheet on home** (in context), now or with the home element round (D5).
7. **The research with people**: run the card sort, survey and tree test first (about a week with friends), or build the
   proposal and test it on the phone?

## Decided and built (2026-10-19)

Matheesha on the proposal: "looks good". Taken as yes to every recommendation: the three layers and the two-level rule;
no global "Show advanced" switch; moments in one list under Motion folded by part, each part's page folding its own
under one Motion row; the Quick strip with light and dark, the colours' source and the animation preset; Phone renamed
System. The two left open were decided by the defaults: build first and test on the phone (no card sort or tree test
before it), and the Customize sheet on home waits for the home element round (D5).

Built (the code at the commit after b179750):

- **Folds** (`settings/Fold.kt`): a row naming what it holds; its rows unfold in place on the appear role (the chevron
  turning with them), on iOS's quaternary fill at half strength (`comp.settings.fold.fill`; the whole one matched the
  sheet's grey under the group, and a fold's last rows looked outside it), built at the tap and opened two frames later
  (the row lit meanwhile), left open or folded in the next session (`SettingsPrefs.openFolds`), a dot on the fold while
  something in it is changed. The search opens the fold a row waits in (`SearchEntry.fold`) before it scrolls to it.
- **Start page**: the search, the quick choices (`QuickGroup`: one card, three rows of chips under their names; the same
  draft as Look and Motion, waiting for Apply), Your changes, then the tiles under "Your phone" (Home, App Library, Apps,
  Notifications & Control Center, Status bar), "Style" (Look, Motion) and System on its own. `Part`'s order is the
  column's (the tiles' scenes play down it).
- **Look**: Theme, Light & dark, Colours (and your colour) in sight; More (Fonts, Size, the settings app's theme) and
  Advanced (the palette's style, intensity and colours, with the line "tap a colour to set it"; quieter while the theme's
  own colours are chosen). The Adjust colours page is gone (its rows are Advanced's).
- **Motion**: Presets, Feel, then "By part": six folds naming their moments.
- **A part's page**: its moments folded under one Motion row.
- **A moment's page**: Every detail is labelled Advanced ("Its 5 animations, in the order they play, each with its curve").
- **System** (was Phone): the same rows; search places say System.
- Found on the way: the search scrolled toward a row near a page's end even when the page could not move, and waited out
  that scroll before lighting it (up to ~0.7 s); the target is now clamped to how far the page goes.

Rows in sight now (every row that shows only sometimes counted): start page 13, Look 7, Motion 11, Home 10.

Checked on the emulator (light and dark, the theme's colours and the wallpaper's): every page above, folds opening and
closing (in 4x slow motion: rows growing in and fading up, the chevron turning), the quick choices applied from the start
page and back, the search into a folded row (Folders on Home) and into Look's More (Fonts). 129 unit tests. Not yet on
the S24: frame timing of a fold's opening (its rows are built in the frame two after the tap, as Every detail's).

## Sources

- NN/g, [Progressive Disclosure](https://www.nngroup.com/articles/progressive-disclosure/) and
  [video](https://www.nngroup.com/videos/progressive-disclosure/); [Tree testing](https://www.nngroup.com/videos/tree-testing/);
  [IA study guide](https://www.nngroup.com/articles/ia-study-guide/).
- AOSP, [Android settings design guidelines](https://source.android.com/docs/core/settings/settings-guidelines).
- Apple, [Human Interface Guidelines: Settings](https://developer-mdn.apple.com/design/human-interface-guidelines/patterns/settings);
  [Adding a settings interface to your app](https://developer.apple.com/documentation/foundation/adding-a-settings-interface-to-your-app.md).
- iOS 18 home screen customising: [Tom's Guide](https://tomsguide.com/phones/iphones/how-to-customize-your-iphone-home-screen-layout),
  [MacRumors](https://macrumors.com/guide/ios-18-home-screen).
- Pixel: [Google support](https://support.google.com/pixelphone/answer/7289143),
  [Android Authority](https://www.androidauthority.com/pixel-wallpaper-app-revamp-3475404).
- Good Lock: [SamMobile](https://www.sammobile.com/news/samsung-updates-good-locks-quickstar-with-one-ui-8-5-support/),
  [9to5Google](https://9to5google.com/?p=352136).
- Nova: [settings search in 6.0](https://androidcentral.com/nova-launcher-version-6-beta), [Nova FAQ](https://novalauncher.com/faq),
  [TechPP](https://techpp.com/2017/07/31/nova-launcher-settings/).
- Lawnchair: [preferences docs](https://docs.lawnchair.app/developers/concepts/preferences),
  [XDA](https://www.xda-developers.com/hands-on-with-lawnchair-v2-a-customizable-pixel-launcher/).
- Niagara: [missing customization](https://help.niagaralauncher.app/article/77-missing-customization-features),
  [settings feedback](https://niagaralauncher.featurebase.app/p/the-settings-page-needs-a-refined-design-because-it-is).
- KWGT: [Guiding Tech](https://www.guidingtech.com/use-kwgt-make-widgets/), [Androidsis](https://en.androidsis.com/How-to-create-widgets-with-kwgt-from-scratch-and-customize-Android/).
- GNOME: [Tweaks into Settings discussion](https://discourse.gnome.org/t/integration-of-gnome-tweaks-advanced-settings-in-gnome-settings/34883),
  [GitLab issue 124](https://gitlab.gnome.org/GNOME/gnome-tweaks/-/issues/124).
- KDE: [Highlight changed settings](https://pointieststick.com/2020/08/14/this-week-in-kde-highlight-changed-settings-and-much-much-more/).
- Windows 11: [Settings home cards](https://pureinfotech.com/windows-11-settings-new-home-cards/).
- macOS: [MacStories on System Settings](https://www.macstories.net/stories/macos-ventura-the-macstories-review/8),
  [Michael Tsai](https://mjtsai.com/blog/2022/06/08/system-settings/).
- Progressive disclosure, risks and modes: [UX Tigers](https://uxtigers.com/post/progressive-disclosure),
  [Microsoft: progressive disclosure controls](https://learn.microsoft.com/sk-sk/windows/win32/uxguide/ctrl-progressive-disclosure-controls).
