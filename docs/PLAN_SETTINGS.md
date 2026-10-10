# The settings app: the plan (phase C)

Status: **draft for Matheesha's review** (2026-10-10). Phase C of `docs/PLAN_LAYOUTS_THEMES.md`; builds on phase B (layouts,
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

The walkthrough comes late on purpose: what it walks through (layouts, themes) is still growing in phases D and E.

## Open questions

1. Where the settings open from (an icon in the drawer, home's long press on empty space, a Control Center control).
2. Applying: at once with undo (proposed), or preview first and an Apply button.
3. The settings app's own layout: cards with live miniatures at the top, grouped lists below (proposed), or one style
   throughout (iOS Settings-like lists).
4. Google Fonts search needs the list of families (about 1,700 names; no API key): a list bundled with the app from the
   public google/fonts repository (Apache-2.0 / OFL metadata), refreshed with updates.
