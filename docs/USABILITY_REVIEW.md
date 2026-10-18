# Usability review: a day of use (2026-10-18)

Matheesha asked for a full day-to-day usability test: "try to use our app, tweaking each possible type of setting. Note
down the issues and how we can improve the user experience ... think what a senior level app dev, ui and ux designer ...
would analyse". This is that review: how it was done, what was found, what was fixed in this round, and what is left.

## Method

- **Walkthrough of every task a person would do in a day**: open the settings from the drawer and from home's edit menu,
  read every card, open every part's page, change one of each kind of control (chips, steppers, switches, sliders, the
  palette, fonts, a moment's style), apply, discard, reset from Your changes, search for settings by name and by what they
  do, change the wallpaper from home and from the system's picker, open and close apps quickly in every style.
- **States, not just screens**: light and dark; the theme's colours and the wallpaper's (Material You); one theme
  installed and two (developer switch); values that fit and values past what fits at the size chosen; a page with no
  changes, one, several; right after Apply, Discard and Reset all.
- **Heuristics used** (Nielsen's ten, Apple's HIG for grouped settings, WCAG contrast): visibility of state, one way to
  read each value, no control that does nothing, no layout shift under the finger, consistent words, recognition over
  recall, error prevention (what fits is said before it is chosen), and the project's own rule that nothing pops.
- **Checked on the emulator** (Android 17, Medium_Phone) with screenshots looked at for every change, frame recordings
  where something moved. The S24 has not seen this round yet.

## Findings and what was done

Severity: **critical** breaks the app or a primary task; **high** hides state or misleads; **medium** costs effort or
trust; **low** polish and words.

### Fixed earlier in this round (aee286f)

| # | Severity | Finding | Fix |
| --- | --- | --- | --- |
| 1 | high | A closing app reopened (or another opened during its close) always zoomed, whatever opening style was chosen: "it would work but frequently revert back to the default animation" | the chosen style plays from where the card is (`GestureNav.flightBlend`) |
| 2 | medium | Closes of apps with no icon on home ignored slide and fade | they play as chosen |
| 3 | high | After a wallpaper change elsewhere the settings showed the old wallpaper, then replayed the change as a grey smear over the page | a crossfade from the still that matches Android's snapshot |
| 4 | medium | Theme cards kept pictures of home with the old wallpaper | pictures remember their wallpaper; a stale one is replaced, fading in |
| 5 | medium | The test theme (Graphite) was offered to everyone | test themes only with Developer's switch |

### Fixed in this commit

| # | Severity | Finding | Fix |
| --- | --- | --- | --- |
| 6 | high | Every group from the sixth on stayed translucent for good (89 % and 75 %): the rise-in stagger was capped past what the page's motion reaches. A group that composed again on its own took a new, larger stagger number, so any group could turn translucent after an interaction ("Reset all" looked disabled) | the stagger number is taken once; from the fifth on, groups rise together, and every one is whole at rest |
| 7 | high | With the theme's colours in light, the white cards sat on a white sheet (255 against 252): no edge to any card or group | the sheet is iOS's grouped grey (#F2F2F7) under white groups, as in iOS's own Settings; dark unchanged |
| 8 | high | With the wallpaper's colours, every part's colour became the one primary: all cards and stages the same hue, colour no longer told the parts apart | each part keeps its own hue, turned toward the palette's accent by half the way, at most 15° (Material's harmonising: `MaterialYou.harmonize`, `partColor`) |
| 9 | high | Counts past what fits disagreed: Control Center set to 5 showed 4, its card said 5, the stage drew 5 | the card, the stage and the row say what is shown; the row says what happens ("Control Center shows only 4.", "Made smaller to fit."), the note says why |
| 10 | medium | "N changes" at a page's top appeared with the first change and pushed the page down under the finger | it is at the page's foot |
| 11 | medium | "1 change" meant two things (the Apply bar and the changes from the theme) | the bar says "1 change to apply"; the row "1 change from the iOS 27 theme" |
| 12 | medium | A moment's row did not say its style; on its page the style came after the sliders | "Zoom from the icon · smooth, no bounce"; Style first |
| 13 | medium | Layout and Where it lives showed one choice each: rows that looked tappable and did nothing | a caption under the stage says what the part is and how it is reached; the rows come back when there is a choice; search lands on the caption |
| 14 | medium | With one theme installed, every part offered "Follows Look" and "iOS 27": two chips that make the same look (and the settings app's own theme too) | shown only when there is another theme, or the part already has one; search follows |
| 15 | medium | One theme on offer left the Theme group a lone card and empty space | the card with the theme's name and what it is (themes have descriptions now) |
| 16 | medium | The Fonts page showed Look's phone, which says nothing about fonts | the stage turns into a type specimen: "Aa" in the titles' face, each font named in its own face; a font chosen crossfades without moving the layout; Look's card "Aa" follows the titles' font |
| 17 | medium | Look's stage under the wallpaper's colours: dark slate icons on a slate gradient | your wallpaper (dimmed in dark, as home shows it) with the palette's light tones |
| 18 | medium | Change rows repeated the part's name under its heading and wrapped the value onto a line of its own ("... › Grid · 4 / → 5") | the place without the part; "4 → 5" never breaks |
| 19 | medium | Chip rows were cut by a hard line inside the card's padding | they scroll out to the card's edge, their chips inset as before |
| 20 | medium | In dark the swatches' rim vanished, and the Surface swatch with it | the rim takes the separators' colour |
| 21 | low | The type specimen's lines were cut with an ellipsis | names only, set smaller to fit, never cut |
| 22 | low | "Inter · Serif" did not say which font was which | "Inter, titles in Serif" |
| 23 | low | Look's and Motion's cards both said "iOS 27" | "iOS 27 theme", "iOS 27 preset", "iOS 27 style" |
| 24 | low | Apps' caption said how it is reached twice | once |
| 25 | low | A search result lit a caption as a square band running into the padding | lit as a card is (rounded, even margins) |
| 26 | low | "Reset all ... back as it comes" for theme and preset values; copy like "These settings" | per page ("Back to the theme as it comes"), "Settings app" |

Tooling: `tools/build.sh` printed "BUILD FAILED" with no reason when Gradle failed outside the compiler; it prints
Gradle's "What went wrong" now.

A crash found by this review's own test, before any commit: a part's colour with the theme's colours called itself
(a replace inside its own body) and the settings died at the first frame after Reset all. Every settings change is now
checked with the theme's colours and the wallpaper's, in light and dark; `MaterialYou.harmonize` has a unit test.

## Still open (recommended next)

| # | Severity | Finding | Recommendation |
| --- | --- | --- | --- |
| A | medium | After a new wallpaper's reveal, home's dock glass keeps the old wallpaper's tint for about a second | make the dock's frost again with the reveal, not after it |
| B | medium | The App Library at 3 columns (past the room for 2) cuts category names ("Productivity & Fi...") | at a count past what fits: smaller names, two lines, or keep the count at what fits |
| C | medium | "The phone's" palette follows Android's, which Android re-extracts only on its own schedule (on the emulator: a wallpaper set from outside the picker left `isColorExtracted=false` and the old palette) | check on the S24; if it lags there too, make the palette from the wallpaper ourselves until Android's catches up |
| D | low | The look-change reveal over the white settings page reads as a grey smear for a moment | Matheesha's call: keep (it is the designed reveal) or a quieter crossfade inside the settings |
| E | low | Phone's stage always shows a green shield | grey with a pause mark while Shizuku is off, so the stage says the state |
| F | low | Five search results named "Layout" | the layout's name on the second line, or one result per part |
| G | medium | Accessibility: the stage and the cards' scenes are drawn and unlabelled; detail text contrast in light is close to the minimum | labels for TalkBack, a check of secondary text against 4.5:1, larger text sizes through every page |

## Not checked here

The S24 (frame pacing of the new stage drawing, One UI's palette after a wallpaper change), and the token editor's live
preview (V5 step 2), which waits for Matheesha's go.
