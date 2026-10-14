# Transition styles: what each animation could be (research, 2026-10-14)

Matheesha (2026-10-14): "currently we have many motion profiles but the same animations. our app should introduce
different kind of (transition) animations too." Today a motion preset only changes **curves** (how fast, how bouncy).
This is a survey of **choreographies** (what moves, where from, in what order) for each animation the launcher draws,
gathered from iOS, Android/Material, One UI, HyperOS/MIUI, ColorOS/OxygenOS, OriginOS, Nothing OS, macOS, webOS and the
launchers (Nova, Lawnchair, Launcher Pro, Good Lock), with how each would be built here. Nothing is decided: the open
questions are at the end.

## The idea: a style is a choice, like a layout

A **transition style** is a named choreography for one moment (opening an app, a folder...), with roles of its own for
its curves. A motion preset then names a style per moment as well as its curves, so presets really differ:

| Preset | Opening an app | Going home | Switching apps | Folders | Banners | Pages |
| --- | --- | --- | --- | --- | --- | --- |
| iOS 27 (today) | Zoom from icon | Shrink into icon | Slide | Zoom into folder | Drop in | Slide |
| Material | Container transform | Container transform back | Shared axis | Dolly zoom | Slide down | Slide |
| (new) Playful | Genie | Genie | Cube | Pop out | Island morph | Cube |
| (new) Calm | Fade and scale | Fade and scale | Crossfade | Fade and scale | Fade | Crossfade |

The user can keep a preset and swap the style of one moment (as curves can be changed one by one now), and every style
has a **reduced** form (crossfades: iOS's Reduce Motion) behind one switch. Styles are motion, not look: they live in the
preset files (`motion.style.<moment>`), never in a theme, and work with any layout.

## The moments, and the styles for each

Effort is ours to build, judged against what the code already draws: **S** a variation of what exists, **M** a new
choreography on existing surfaces, **L** new drawing (meshes, shaders, 3D) or a risk to fluidity to measure first.

### 1. Opening an app (from an icon, the dock, the App Library, Spotlight, a notification)

Our card window draws the whole launch (the app's window appears only at the handover), so every style here is ours to
draw.

| Style | What you see | Seen in | Effort |
| --- | --- | --- | --- |
| **Zoom from icon** (today) | The app grows out of its icon, the icon fading into it; home steps back | iOS, Pixel, One UI | — |
| **Container transform** | The icon's rounded box morphs into the window; the icon fades out early, the app fades in late (a fade-through inside a moving box); home stays put | Material 3 | S |
| **Slide up** | The app slides up from the bottom edge, home dims | Android 5 "L", Nova | S |
| **Circle reveal** | The app is uncovered by a circle growing from the icon | Nova, Android 5 ripple, Telegram's theme switch | S |
| **Fade and scale** | The app fades in, growing from 92 % in place; home fades back | Windows 11, "Calm" | S |
| **Dolly zoom** | Home rushes toward the icon (zooming past it, slightly blurring) as the app grows: a camera flying into the icon | Pixel's 2025 folders ("Hitchcock zoom") | M |
| **Blur to sharp** | The app grows out of a blur into focus | HyperOS feel | M (a full-screen blur per frame: measure) |
| **Genie** | The app pours out of its icon like a genie from a bottle, the shape bending | macOS minimize | L (a mesh warp of the card: `drawBitmapMesh`, or an AGSL distortion) |
| **Liquid** | The icon stretches toward the screen, squash-and-stretch, then fills it | OriginOS "Atomic animation" | L |

### 2. Going home (swipe up, back from an app's first screen)

The card already follows the finger; the styles differ in where it goes and how home comes back.

| Style | What you see | Seen in | Effort |
| --- | --- | --- | --- |
| **Shrink into icon** (today) | The card flies into its icon; icons settle | iOS, Pixel | — |
| **Container transform back** | The window morphs back into the icon's box, contents fading through | Material | S |
| **Fade and scale** | The card fades while shrinking a little in place; icons fade in | Windows 11, "Calm" | S |
| **Slide down** | The card falls away under the finger | Android 5 | S |
| **Genie / suck** | The window is poured back into its icon (or sucked in corner-first) | macOS (genie, hidden "suck") | L |

Home's arrival can be a style of its own (today: icons settle with a depth zoom): **wave** (icons rise in rings from the
closed app's icon), **cascade** (row by row), **rise** (from below). S-M.

### 3. Switching apps (sideways along the bar)

| Style | What you see | Seen in | Effort |
| --- | --- | --- | --- |
| **Slide** (today) | Apps side by side, following the finger | iOS, Pixel | — |
| **Shared axis** | The leaving app slides and fades, the coming one slides in fading up (overlapping) | Material | S |
| **Deck** | The next app comes from behind the current one, which slides off | webOS cards | M |
| **Cube** | The two apps are faces of a turning cube | Launcher Pro, MIUI page effects | M (3D on the card's RenderNode: `rotationY` + camera distance) |
| **Crossfade** | One dissolves into the other | Reduce Motion | S |

### 4. The App Switcher (entering, opening a card, clearing)

How the deck is *laid out* (carousel, stack, grid, list) is a **layout** (the Layouts page: Good Lock's Task Changer offers
List, Grid, Stack, Vertical list, Slim list), not motion. Motion styles are how it comes and goes:

| Moment | Styles | Seen in | Effort |
| --- | --- | --- | --- |
| Entering | Shrink into a card (today) · Tilt back in 3D (a rolodex stack) · Zoom out to a grid | iOS · Android 5 · One UI | M |
| Opening a card | Zoom to full (today) · Container transform · Slide up | iOS · Material | S |
| Clear All | Fly up one after another (today) · Swept away in a wave · Dissolve into dust | iOS · Telegram's deleted messages | M · L (particles) |
| Dismissing one | Throw up (today) · Shrink and fade · Fold away | webOS ("throw") | S |

### 5. Home pages (swiping between pages)

These are the classic launcher "scroll effects" (Launcher Pro: Scale, Rotate, Flip, Cube; MIUI: Sliding, Crossfade,
Tumble, Page, Cascade, Rotate, Cube; Nova's scroll effects).

| Style | What you see | Effort |
| --- | --- | --- |
| **Slide** (today) | Pages side by side | — |
| **Parallax** | Icons, widgets and wallpaper at different depths, sliding at different speeds | S |
| **Cube** (outside or inside) | Pages are faces of a cube | M |
| **Stack** | The next page comes from under the current one, which slides off and shrinks | M |
| **Carousel / wheel** | Pages turn around a vertical axis behind | M |
| **Tumble / cascade** | Icons leave and arrive one after another (row by row, or each tumbling) | M |
| **Crossfade** | Pages dissolve | S |

Risk: home's glass (dock, widgets, clock) records where it is drawn; under 3D transforms it must be redrawn each frame
(see CLAUDE.md, "Glass and transforms"). Measure before shipping any 3D page style.

### 6. Folders

| Style | What you see | Seen in | Effort |
| --- | --- | --- | --- |
| **Zoom into folder** (today) | Home zooms toward the folder, which opens around its apps | iOS | — |
| **Dolly zoom** | The folder grows while home shrinks back, opposite directions | Pixel (Android 16 QPR3) | S |
| **Pop out in place** | A panel grows out of the folder icon over a dimmed home | One UI, older Pixel | S |
| **Sheet** | The folder rises as a sheet from the bottom | App Library style | S |
| **Closing bounce** | On close, the folder icon jiggles as its apps land back | Pixel (Android 16 QPR3) | S (a detail for any style) |

### 7. App Library, Spotlight, search

| Moment | Styles | Seen in | Effort |
| --- | --- | --- | --- |
| App Library | Next page (today) · Sheet rising from the dock · Circle reveal from where you swiped · Zoom (home recedes) · Fade | iOS · Pixel · Nova (circle, slide up, zoom, fade) | S each |
| Spotlight | Pull down with blur (today) · Rise with the keyboard · Grow out of the Search pill (a container transform) | iOS · Pixel · Material | S-M |

### 8. Long-press menus

| Style | What you see | Seen in | Effort |
| --- | --- | --- | --- |
| **Lift and blur** (today) | The icon lifts, home blurs, the menu grows from it | iOS | — |
| **Popup** | A menu scales out of the icon's edge, no blur | Pixel, Material | S |
| **Flip** | The icon flips over, its actions on the back | (playful) | M |
| **Radial** | Actions fan out in a circle around the finger | pie menus | M (also a layout question) |

### 9. Control Center and Notification Center

| Moment | Styles | Seen in | Effort |
| --- | --- | --- | --- |
| Control Center | Modules zoom in from the top with the blur (today) · Drop from the top as one sheet · Cascade (rows arrive one after another) · Unfold from the finger (grows out where you pulled) | iOS 27 · Material, HyperOS · OriginOS-like | S-M |
| Notification Center | Sheet follows the finger (today) · Waterfall (notifications drop in one after another) · Unroll from the top | iOS · Material 3 Expressive (neighbours react to a drag) | S-M |
| A notification dismissed | Swipe out (today) · Neighbours spring into the gap · Shrink into a dot | Material 3 Expressive | S |

### 10. Banners (notifications popping up)

| Style | What you see | Seen in | Effort |
| --- | --- | --- | --- |
| **Drop in** (today) | The banner drops from the top past its place and back | iOS | — |
| **Island morph** | The banner grows out of the camera hole: a pill stretches sideways, then down into the banner, and shrinks back into the hole | Dynamic Island | M (the cutout's shape from `DisplayCutout`; the S24's hole is centred: a natural fit) |
| **Slide down** | Slides down from behind the status bar, no bounce | Android heads-up | S |
| **Pop** | Fades in growing from 90 % in place | (calm) | S |
| **Side** | Slides in from the side, as a toast | (desktop) | S |

### 11. Small things: the status bar, the clock, counts

Already springs for every slot; styles: **roll** (digits roll like an odometer: today's minute change), **flip** (split-
flap digits), **fade**. Badge counts: **pop** (scale bounce), **roll**. S each.

### 12. Unlocking (home arriving)

| Style | What you see | Effort |
| --- | --- | --- |
| **Zoom out** (today) | Wallpaper settles from a zoom, icons fly in from beyond the screen | — |
| **Ripple** | Icons rise in rings from the fingerprint sensor (where the finger was) | S-M |
| **Cascade** | Icons arrive row by row from the top | S |
| **Focus** | Home comes out of a blur into focus | M (blur cost) |

### 13. A new look (theme change)

Matheesha chose the reveal wave (2026-10-10: unhurried, ~1.8 s, celebrated). Other reveals, each **M** on `ThemeReveal`'s
shader: **circle** (from the tap, as Telegram), **wipe** (a soft edge sweeping across), **ink** (a liquid blob spreading
with a wobbly edge), **dissolve** (pixels), **page peel** (the old look peels away from a corner).

### 14. Editing home, widgets, the settings app

Edit mode: **jiggle** (today) · **lift** (icons rise with a shadow, no jiggle: Pixel) · **grid** (cell dots fade in: One UI).
The settings app's pages: **grow from the tile** (today) · **push** (iOS) · **shared axis** (Material). S each.

## What it takes in the code

- **One renderer per moment, several choreographies.** Today each moment has one choreography written into its code
  (e.g. the launch card's zoom in `CardView`/`GestureNav`). A style becomes an interface per moment (`AppOpenStyle.draw(
  card, progress)`, etc.), with the current behaviour as the first implementation; the choice is read from the preset
  (`motion.style.app-open`), like a curve.
- **Interruptible like everything else** (rule 3): a style is a function of one or a few progress values driven by springs
  (the existing `MotionValue`s), so a grab mid-flight or a reversal works for every style as it does now.
- **New drawing primitives**, used by several styles: a circle/shape clip that follows a progress (circle reveal, island
  morph); a mesh warp of the card's picture (genie, liquid); 3D transforms of RenderNodes with a camera distance (cube,
  stack, rolodex); a particle field in AGSL (dust).
- **Measured on the S24 like the rest** (missed refreshes per animation); a style that cannot hold 120 Hz is not offered.
- **Previewed on the settings' picture of home** (the C4b samples), so a style is chosen by watching it, never by name.

## Suggested order (one moment per round, agreed first)

1. **Opening and closing apps**: seen hundreds of times a day, drawn entirely by us; 4 styles first (zoom (today),
   container transform, slide up, circle reveal; fade and scale as the reduced one).
2. **Banners**: the island morph is the most distinctive thing on a phone with a centred camera hole.
3. **Folders**: dolly zoom and pop-out are small and visible.
4. **Home pages**: parallax first; 3D styles only after measuring glass under 3D.
5. The rest as the presets need them.

## Open questions (Matheesha)

1. Start with opening and closing apps (recommended), or another moment?
2. Which of the styles above are worth building at all (genie and liquid are the most work)?
3. New presets that bundle styles (Playful, Calm, Cinematic...), or styles only as per-moment choices?

## Sources

- Material motion system (container transform, shared axis, fade through, fade): https://material.io/design/motion/the-motion-system.html
- Material 3 Expressive (springs, the shade, notifications reacting to drags): https://9to5google.com/2025/05/13/android-16-material-3-expressive-redesign/
- iOS 18 zoom transition (WWDC24 "Enhance your UI animations and transitions"): https://wwdcnotes.com/documentation/wwdc24-10145-enhance-your-ui-animations-and-transitions/
- iOS Reduce Motion (zoom and slide replaced by dissolves): https://support.apple.com/en-us/HT202655
- macOS genie, scale and the hidden "suck" minimize effects: https://eclecticlight.co/2026/04/27/the-minimise-easter-egg-lives-on/
- Nova Launcher's transitions (circle reveal, slide up, zoom, fade; "L" app animation): https://www.droid-life.com/2014/08/22/nova-launcher-beta-updated-with-new-drawer-animations-app-drawer-search-additions/ , https://www.droid-life.com/2014/10/21/nova-launcher-beta-updated-in-google-play-brings-lollipop-animations/
- Launcher Pro's page effects (scale, rotate, flip, cube): https://www.androidpolice.com/2011/01/20/launcher-pro-updated-with-homescreen-transition-effects-video/
- MIUI's page transition effects (sliding, crossfade, tumble, page, cascade, rotate, cube): https://www.techbone.net/xiaomi/smartphone/transition-effect?from=mi-4s
- Good Lock Home Up's Task Changer layouts (list, grid, stack, vertical list, slim list): https://www.sammobile.com/news/home-up-update-two-new-styles-for-recent-apps/
- Pixel's 2025 folder animation (dolly zoom, closing bounce): https://9to5google.com/2025/12/17/android-16-qpr3-new-folder-animations/ , https://www.androidauthority.com/android-16-qpr3-bouncy-folders-3646260/
- Telegram's circular theme reveal (snapshot plus circular reveal): https://nutrient.io/blog/change-android-themes-with-circular-reveal-animation
- Android predictive back (back to home, cross-activity, cross-task): https://developer.android.com/develop/ui/compose/system/predictive-back-setup
- webOS cards ("throw" to close): https://www.laptopmag.com/reviews/smartphones/palm-pre-sprint?page=6
- OriginOS atomic animation (deform, separate, merge, rebound): https://gadgets.beebom.com/news/vivos-originos-7-update-official-with-liquid-glass-atomic-workbench-new-ai-features
- ColorOS / OxygenOS parallel animations: https://www.oppo.com/en/newsroom/stories/oppo-coloros-15-launch-smooth/
- Dynamic Island (alerts morph out of the cutout): https://www.cultofmac.com/?p=790437
