# Launcher: Requirements Specification

Status: draft v1, 2026-10-02. Written from the design decisions and probe results of the feasibility phase.
The build order, the "validated vs unsupported" table and the risks list are proposals; the accessibility details are still unwritten.

## Summary

We are building an Android launcher that merges the home screen, recents and the notification and quick-settings shade into one themeable
interface, with finger-tracked animations that never stutter, and it works without root by using Shizuku.

Two rules outrank every feature:

- **Perfectly smooth.** Every open, close, swipe and panel pull follows the finger and holds the display's full refresh rate (120 Hz on the test phone). No visible stutter, flash or double animation.
- **Fully consistent.** One shade, one status bar, one gesture set, everywhere. No stock panel ever appears alongside ours, and nothing is themed on the home screen only.

The goal is "every good feature from the best launchers, working together", with Hyprland-level control over looks and motion through a theme engine
and a theme builder. First target: one phone (Galaxy S24, Android 16, One UI); other phones come after the core is proven. A free base launcher works with no
extra setup; the features that need Shizuku are the paid tier.

## Platform and architecture

Normal launcher app + Shizuku (a helper process with shell rights, uid 2000, through wireless debugging). No root.

- **Launcher app:** home, drawer, recents, shade, status bar and theme engine are our own windows and views.
- **Shizuku helper:** hidden framework APIs by reflection: cached app snapshots, silencing stock system UI, animation scales, app launch.
- **Own overlay windows:** gesture strip and a full-display card window (`TYPE_APPLICATION_OVERLAY`, hardware accelerated). The stock recents animation API does not exist on Android 16, so we draw our own.
- **Watchdog:** a shell-side loop outside the app restores the system if the app dies.

Works without Shizuku, unlocks more with it:

- Without Shizuku the app is a complete themeable launcher; banners explain what Shizuku unlocks.
- After each reboot the app tries to restart Shizuku automatically; we recommend the thedjchi fork (Apache-2.0, v13.7.0). If it cannot restart, the app drops to No-Shizuku mode with a one-tap guide.
- The lock screen and always-on display are left alone.

Out of scope (proven not workable):

- Real thumbnails of secure (screenshot-blocked) apps; recents shows a themed placeholder card. Live capture of the foreground app takes ≈100 ms, so recents uses cached snapshots (1–3 ms).
- Split-screen, pop-up and freeform launching from the shell (windows get stuck as overlays); experimental only.
- Windows created from the Shizuku process itself.
- Standard cross-window blur on Samsung (system property off, not changeable without root); see Motion and glass.
- Phones first; tablet/foldable designed for later; landscape fully supported on phones.

## Core experience

Default look: horizontal card carousel for recents, canvas for home; themes can change either.

**Gestures** (every gesture is mappable, with its own threshold, zone and haptic)
- Flick up from the bar: home. Hold: recents. Swipe sideways on the bar: previous app (configurable distance and stepping).
- The finger can grab an animation midway and take over; every animation is interruptible.

**Recents:** horizontal card carousel by default (other layouts from themes); clear all; split/pop-up actions experimental only; themed placeholder for secure apps.

**Home canvas:** free placement with snapping and per-size-class arrangements; each profile chooses pages or infinite canvas; dock is a themable region;
folders: classic, stacks, auto-groups; system wallpaper with parallax and blur-on-open; per-app badge control; long-press menu with app shortcuts, notifications and our own actions.

**App drawer:** defined by the theme; a full-screen swipe-up drawer and an always-visible list are built in.

**Shade (replaced fully in the first build):** notifications grouped by app and conversation with inline reply; quick settings, media controls and brightness in the same panel.
It is the only shade: the stock one is blocked.

**Own status bar:** stock hidden, ours drawn everywhere. Home-screen-only was rejected.

**Search and widgets:** drawer search and a command palette (each profile picks); standard Android widgets plus our own themed widgets.

**Icons:** third-party icon packs work and switching is free. We ship no icon packs of our own; each shipped theme recommends a pack or an Icon Pack Studio configuration.

## Motion and glass

Smooth motion = drawing the animation ourselves while the system's animations are off; every launch, close and gesture uses that one mechanism.

**Launching an app:** transition and window animation scales = 0 while our cards animate, back to the user's values ≈1 s after (animator scale untouched). Our overlay card grows from the icon in ≈320 ms while the app starts underneath.
Warm and cold launches looked smooth (≈1 dropped frame in 62 at 120 Hz). Launch and close animations are theme-defined, spring-based and interruptible.

**Closing an app:** a cached snapshot becomes a card that shrinks toward its icon while home starts. Tested and working.

**Apps opened from outside the launcher:** notification taps from the stock shade still play SystemUI's own launch animation. Stops mattering once our shade replaces it;
heads-up notifications and in-app links must be re-tested in the real build.

**Blur and glass (three layers)**
1. Baseline, every phone: blur our own captured snapshots with `RenderEffect` (recents cards, drawer over wallpaper, shade over the last app's snapshot).
2. Standard live blur wherever the system reports cross-window blur as enabled (off on the Samsung test phone).
3. Samsung live blur upgrade: full-screen overlay with dim-behind + `SEM_EXTENSION_FLAG_CHANGE_DIM_EFFECT_TO_BLUR`; strength follows dim amount; animates frame by frame.
   Whole screen only; a frosted panel over sharp content still uses the snapshot route. The plain View blur calls only give fog and are not used.

Open: at the strongest dim level the display once fell to 60 Hz and one frame took 33 ms; first use had a 125 ms hitch. Pre-warm a zero-dim blur window early; run a longer test before relying on it.

## Themes and the builder

A theme controls everything visible; the app restyles itself, including its own settings, to the active theme.

Profiles are four swappable layers:

| Layer | Controls |
| --- | --- |
| Motion | Launch and close animations, springs, gaps and offsets, haptics |
| Colour and effects | Palette, blur, glass, tints |
| Shape and spacing | Corner radii, icon masks, grid and margins |
| Layout | Home canvas, dock, drawer, recents and shade arrangement |

- Users pick a profile or mix layers from different themes; everything is editable in advanced settings.
- Colours come from the wallpaper, a schedule, custom values, or one picked colour expanded Material You style.
- A fixed, unthemeable safe-settings screen is always one gesture away.

Theme files: plain text with name, author, version, preview image and licence; editable on the phone or on a PC. Scripting is part of the free tier (Play policy on downloaded scripts still to be checked).

Builder: quick edit on the real screen plus a full builder; undo/redo, revert, version history, auto-revert confirmation (an unconfirmed change is undone after a few seconds).
Theme gallery plan is still open.

## Safety, onboarding and settings

Hiding the stock status bar and turning off system animations means a crash could strand the user, so recovery is a core feature.

**Watchdog (proven):** the app touches a heartbeat file every second; a loop outside the app restores animation scales and the status bar when it is > 4 s old.
After a force-stop it restored everything in ≈4 s and the loop survived the app and its Shizuku service dying. In the real app the heartbeat comes from a foreground service.
A Shizuku-free emergency path is required (persistent notification action + safe-settings screen). A reboot always clears everything.

**USB configuration check (found 2026-10-04):** if Developer options → Default USB configuration enables data functions (e.g. file transfer), Android
switches USB functions on every lock and unlock, which restarts adbd and kills Shizuku and everything started through it. Onboarding must detect this
(`mScreenUnlockedFunctions` in `dumpsys usb`) and walk the user to "No data transfer" / "debugging only". See `docs/PROGRESS.md`, task 3.

**Onboarding:** works immediately without Shizuku; guided wireless-debugging + Shizuku setup recommending the thedjchi fork (review its code first); automatic Shizuku restart after reboot, with No-Shizuku fallback.

**Settings:** basic and advanced views with a toggle and search across both; everything adjustable (gesture thresholds, zones, haptics, springs, gaps, offsets) lives in advanced.

**Accessibility:** first-class from the start; detailed requirements still to be written. **Haptics:** rich and per-gesture; sounds off by default.

## Business and release

| Tier | Includes |
| --- | --- |
| Free | Full no-Shizuku launcher, theme builder with scripting, a few profiles, icon pack switching, backup and restore, a Shizuku trial |
| Paid | Recents and gesture navigation, shade/notification/quick-settings replacement, own status bar, automatic Shizuku restart, cloud sync, schedules, extra profiles |

Release: Play Store listing, public repository and website; source-available licence with a licence check for paid features; the name is a placeholder.
Backup: export/import to a file for everyone; cloud sync through the user's own storage (paid).
To verify before release: Play policy on downloaded theme scripts; Play Billing for the paywall; review risk of self-granting permissions through Shizuku; whether the in-app self-updater may ship in the store build (development tool for now).

## Validated and unsupported

| Capability | Result | What it means for the build |
| --- | --- | --- |
| Own gesture strip and card overlay at 120 Hz | Works | Finger-tracked home and recents are ours |
| Grab an animation mid-flight, sideways quick switch, rotation | Works | Always-interruptible gestures |
| Cached app snapshots | Works, 1–3 ms | Recents cards and close animation |
| Live snapshot of foreground app | Works, ≈100 ms | Too slow for gestures; not used |
| Block stock recents, home, back and shade | Works | One shade, one set of gestures |
| Own status bar, stock bar hidden | Works after a race fix | Consistent bar everywhere |
| System animations off plus our own launch card | Works, smooth | Core launch mechanism |
| Close animation to icon | Works | Core close mechanism |
| Silent install of updates through Shizuku | Works | Dev updater; store use to be checked |
| Watchdog restore after force-stop | Works within ≈4 s | Safety net design is proven |
| RenderEffect blur of our own snapshots | Works | Baseline glass |
| Samsung dim-behind blur flag | Works, smooth in frames | Live blur upgrade on Samsung only |
| Samsung View blur calls | Work but look like fog | Not used |
| Standard cross-window blur | Off on this phone | Used only where the system enables it |
| Stock recents animation API | Absent on Android 16 | We draw our own |
| Freeform and split launching from the shell | Windows get stuck | Experimental only |
| Windows created from the Shizuku process | Fail | Everything visible lives in the app process |
| Hidden-API exemption trick | Fails on Android 16 | Use the global policy fallback only when needed |
| Stock notification launch animation | Still plays | Solved by our own shade; re-test |
| Automatic Shizuku restart after reboot | Not tested by us; the user's Shizuku fork starts itself at boot | Onboarding feature |

## Build order (proposed, no dates)

1. **Foundation** — app shell, Shizuku link, safe settings, watchdog with foreground heartbeat. *Gate: a crashed app is restored in under 5 s.*
2. **Smooth core** — gesture strip, launch and close cards, recents carousel, own status bar. *Gate: no visible stutter, frame logs clean at 120 Hz.* (Decides whether the idea works.)
3. **Home and drawer** — canvas, dock, folders, drawer, widgets, icon packs, long-press menu. *Gate: usable as a daily launcher without Shizuku.*
4. **Shade** — grouped notifications, inline reply, quick settings, media, brightness. *Gate: no stock panel can appear anywhere.*
5. **Themes and builder** — profiles, colour sources, theme files, quick edit and full builder. *Gate: a shipped theme restyles everything, settings included.*
6. **Glass and polish** — blur layers, haptics, search, accessibility, schedules. *Gate: a longer blur stress run holds 120 Hz.*
7. **Release** — paywall, backup and sync, store review checks, website. *Gate: Play policy items cleared.*

## Risks and open questions

| Risk or question | Why it matters | Plan |
| --- | --- | --- |
| Play policy on downloaded or interpreted theme scripts | Could remove scripting from the free tier on the store build | Research before phase 5 |
| Play Billing for a paywall on Shizuku features | Required for in-app purchases on Play | Verify before phase 7 |
| Review risk of self-granting permissions through Shizuku | Could block store approval | Research before release; fallback is direct distribution |
| In-app silent updater in a store build | Play restricts apps that install other APKs | Keep as a development tool until checked |
| Samsung blur at the strongest level fell to 60 Hz once | Would break the smoothness rule on the shade | Longer stress run in phase 6; fall back to snapshot blur |
| Apps opened from heads-up notifications and in-app links | Stock animations may still play | Re-test in phase 4 |
| Other phones, Android versions and vendors | Everything was probed on one S24 | Re-run key probes on other devices after phase 2 |
| Reviewing the thedjchi Shizuku fork before recommending it | We would be telling users to run it | Read its code before onboarding ships |
| Watchdog killed or Shizuku absent when the app crashes | A user could be left without a status bar | Foreground-service heartbeat plus a Shizuku-free emergency restore |
| Secure apps cannot be captured | Recents shows placeholders for them | Themed placeholder card, already decided |
| Theme gallery and sharing plan | Not decided yet | Decide during phase 5 |
| App name | Placeholder only | Decide before release |
| Accessibility requirements | Direction set, details unwritten | Write before phase 2 ends |
