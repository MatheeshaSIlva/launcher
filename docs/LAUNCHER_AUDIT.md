# Not a Launcher — Launcher Audit

_2026-11-03 · written with Claude; source of truth is the Claude Doc in the "Android Launcher" project, mirrored here._

Audit of `MatheeshaSIlva/launcher` at commit 7137eb7 against the engine spec.

## Verdict

The launcher is 71,700 lines of Kotlin at commit `7137eb7`. Almost none of it is Compose: every surface (home, drawer, switcher, shade, status bar; settings aside) is a custom `View` drawing with Canvas, RenderNodes and AGSL. That is the headline, and it decides the shape of the port.

| Verdict | Lines | Share |
| --- | --- | --- |
| Port as-is (thin adapter at most) | \~11,300 | 16% |
| Extract: keep the logic, discard the view | \~15,000 in files, \~5,500 worth keeping | 21% |
| Rewrite as KDL components over engine primitives | \~26,000 | 36% |
| Drop (probes, dead glass paths, token key files, dev activities) | \~2,900 | 4% |
| Settings (Compose, 11,400): replaced by the editor and the store | \~11,400 | 16% |
| Unaudited remainder (tests, resource glue, misc) | \~5,000 | 7% |

Three conclusions:

1. **The platform layer is finished and proven.** The Shizuku service, the safety net, the window plumbing (accessibility overlay, focus holder, 1×1 parked windows, raw `/dev/input` handover), the motion and transition engine, the three-tier token resolver, the glass renderer and the app, widget, notification and control data models all port whole. This is the part no competitor has, and the part that cost months of device probing. Nothing in the spec asks it to change; several things in the spec have to change to match it (see *Facts the spec must respect*).
2. **The UI layer is the cost, not the asset.** Twenty-six thousand lines of hand-drawn views exist because there was no component model. Once the engine's \~40 primitives exist, each view becomes a KDL file of a few hundred lines plus the logic extracted from it. The views are not wasted: they are the reference implementation and the acceptance test for the primitives. If a KDL rebuild of `NotificationListBlock` cannot reproduce `NotificationListBlock`, the primitive set is incomplete.
3. **The existing layout JSON is a slot manifest, not a component format.** It picks blocks from a closed vocabulary of 13 and sets a few parameters. KDL replaces it entirely. What must survive is the metadata around it: placements (which top-edge zone summons which surface), the typed options schema, the one-notifications-panel-per-place rule, landscape variants, and strict load-time validation with reasons.

The practical consequence: the engine's first milestone is not "render a home screen", it is "render the existing shade from KDL on the existing platform layer". That exercises data sources (notifications, controls, media), surfaces (summon, pull maths, presentation), glass, transitions and the token model in one go, against a reference that already works on the S24.

## Ports as-is

Roughly 9,000 lines carry over unchanged or with a thin adapter. This is the hard, device-proven work, and it is already shaped the way the spec wants it.

| Cluster | Lines | What it is | How it lands in the engine |
| --- | --- | --- | --- |
| Shizuku service: `ShellService`, AIDL, `ShellCapture`, `TouchStream`, `WindowWatch`, `InstantTransitions`, `TouchInjector` | \~1,700 | Task list and snapshots as `HardwareBuffer` (Android 17 route via `ITaskSnapshotManager`), `captureUnder` (one app's layers in 8 ms), status-bar disable flags on a binder token, raw `/dev/input` edge and touch streams, brightness, instant no-animation starts, window-frame watch at every refresh, touch injection | Each AIDL method is one entry in the capability registry; the callback watches become `callbackFlow`s. Drop the probe methods and `LiveTransitionProbe` (237). |
| Safety: `Watchdog`, `SystemRestore`, `SafetyNotification`, `SafeSettingsActivity`, `StockBarSettings`, `PopupStyle` | 734 | Shell-side loop counting missed heartbeats (suspend-safe), restore plan armed before any system change, recovery that works without Shizuku | Unchanged. The rule "arm before you change" becomes an engine invariant. |
| `ShizukuLink`, `NoAnimStarts`, `NavAccessibilityService`, `Updater` | 457 | Binding, self-granted permissions, service replacement per build, the accessibility overlay host, silent install | The registry's Shizuku and accessibility sources; the updater stays as is |
| Motion: `Curves`, `Spring`, `MotionValue`, `MotionAdjust`, `MotionClock`, `CurveFit`, `IosScroller`, `AppStyles`, `SymbolTime` | \~1,700 | Analytic springs and beziers behind one retargetable `Mover`; SwiftUI-style `(response, damping)`; speed and bounce adjustments; UIScrollView physics; seven open/close choreographies | The engine's motion vocabulary. Needs one adapter from `Curve` to a Compose `AnimationSpec`. |
| Transitions: `motion/transition/` | 964 | Figma-style panel transitions as keyframe tracks on 25 props, per-keyframe easings, tracks on per-role clocks, jump-free handover mid-gesture; 52 unit tests | The engine's surface-transition model, verbatim. The JSON becomes a KDL block. |
| Tokens: `Token`, the resolver half of `Theme`, `TokenParts`, `MaterialYou`, `SystemPalette` | \~1,015 | Typed token kinds with provenance; theme chains up to 6 deep; light/dark blended continuously; `@palette` specs resolved at draw time; own tonal palettes without a library | The token layer. The spec's flat token list is replaced by this three-tier `ref → sys → comp` model. |
| Glass and drawing: `MaterialPainter`, `FrostCache`, `SamsungBlur`, `Blur`, `Blends`, `ViewPicture`, `RenderLease`, `GpuWarmUp`, `IdleWorker`, `Still`, `RoundedBitmaps`, `IconSets`, `SymbolMotion`, `SymbolDrawer`, `SymbolPlayer` | \~2,650 | AGSL liquid glass with measured refraction, depth, dispersion and frost; superellipse; Samsung window-blur route; pipeline warm-up; animated symbol sets | Wrapped as the engine's `glass`, `shader`, `mask` and `icon` primitives. The one caveat: `drawLive` relies on View RenderNodes and cannot sit under a clip or alpha. |
| Fonts: `Fonts`, `FontFamily`, `GoogleFonts`, `GoogleFontCatalog`, `PaletteTokens` | 581 | Google Fonts by family name, catalogue, caching | The `type` group's font source |
| `apps/`: `Apps`, `Icons`, `AppCategory`, `LaunchStats`, `SplashColors` | 603 | App list over all profiles with coalesced reloads, shaped icon cache with GPU pre-upload, categories, decayed launch frequency, splash colours | The `apps` source and `apps.launch` action, nearly verbatim |
| `home/`: `Grid`, `HomeModel`, `HomeMetrics`, `HomeConfig`, `DepthBlur`, `GlassLabelTone`, the clock numerals | \~1,100 | Free-placement grid with widget push rules (tested), item model with atomic persistence, geometry, SDF glyphs for the glass clock | Grid becomes the `grid` primitive's placement engine; the model becomes `kv`-backed home state |
| `switcher/`: `SwitcherHost`, `SwitcherActivity`, `SwitcherInput` | 423 | A spare `SurfaceControlViewHost` kept warm on gesture-nav's thread, the translucent software-drawn activity that hosts it, the touch window with PiP holes | Exactly the mechanism the spec names for remote components and the switcher; keep whole |
| `home/Widgets`: `HomeWidgets` | \~185 | `AppWidgetHost` lifecycle, provider cache, size buckets, binding via Shizuku `appwidget grantbind` | The `widgets` source and host |
| Platform workarounds: `StripForward`, `TurnCover`, `Unlock`, `SystemCalm` | \~530 | Re-injecting taps that land on the strip, an activity stand-in during rotation, unlock handling | Keep; each encodes a lesson that cost days |

Tests that protect what ports: `GridTest`, `GridFitTest`, `DepthBlurTest`, the clock tests, `MotionTest`, and the 52 transition tests. There are none for `Apps`, the search ranking, the deck layout or the picture policy; those get written during extraction.

## Extract

These files mix logic the engine needs with drawing it does not. The logic comes out as a data source, an action, a pure model or an engine policy; the view half is discarded once its KDL replacement matches it.

### Root and gesture

| File | Lines | Keep | Becomes |
| --- | --- | --- | --- |
| `GestureNav` | 5,566 | \~1,800: edge detection, the 32 dp strip, task-snapshot backdrop, card physics, `cardBrings`, `quietStarts`, PiP holes, nav `HandlerThread` ownership | The `nav` source (`gesture`, `backdrop`, `task` streams) and the `app.launch` / `app.switch` actions; card physics becomes a surface-transition preset |
| `HomeScreen` | 2,503 | \~700: pager maths, sheet arrival, depth math, the free-placement commit path | Engine `pager` and `sheet` behaviour; depth as a transition track |
| `EditMode` | 1,271 | \~400: the state machine (enter, pick, drag, resize, push, commit, cancel) | On-device edit mode, driven by `grid` placement results |
| `DeckView` | 1,045 | \~350: deck layout, gesture thresholds, picture policy (which snapshot, when to refresh) | The `deck` primitive's layout policy and the `task.picture` stream |
| `Shade` | 2,418 | \~900: window attach, touchable region and exclusion, `onTouch` / `pull` / raw stream / release, the `Panel` contract, banner triggers (`onNotifs`, `ownScreenInFront`, `showRinging`), `openFrom`, lock privacy, key handling | The shade surface host: summon config, pull maths, presentation model, banner policy. Drop lines \~800–1600 (NC choreography, `applyProgress`) |
| `StatusBarView` | 1,588 | \~200 (lines \~580–730): battery, time, power-save, Wi-Fi RSSI, VPN, telephony, SIM state, network type readers | The `system.battery`, `system.network`, `system.cellular` sources |
| `Controls` | 615 | `ControlState` + `Brightness` whole; the 44-entry `Control` enum split into data definitions | The `controls` source (state streams) and `controls.toggle` action; brightness keeps its temporary/commit semantics |
| `Backdrop` | 255 | `BackdropSource` + `BlurBaker` | The static-screencap glass tier's bake path |
| `Design`, `Theme` (parser half), `Appearance`, `AppLog`, `Badges`, `Wallpaper`, `Option` / `Setup` | \~250 | Store, theme-file parser, badge listener, wallpaper reader, option validation | Token loader, `badges` source, `wallpaper` source, the KDL props validator |

### Pure models already separable

`CcLayout` 191 (grid model with tests), `QsTiles` 187 (catalogue, state map, `flow()`), `NotifRows` 412 (AOSP-style sectioning), `NcSheetBlur` 64 (becomes an engine material), `SearchList` ranking, `AppLibraryView` rebuild logic, `TapOrScroll`, `Menu` spec and layout. Together \~1,300 lines that move as they are into source transforms or primitive policies.

### Pieces to pull out of files otherwise rewritten

- `BannerView`: queue, `SHOW_MS` 7 s, ringing persistence, allowed-action filter.
- `NotificationListBlock.readNotifs` (lines 333–354): filtering and grouping. `CardsBlock.cardsOf` (\~110–160): sections. Both become `notifications` source transforms so no component re-implements grouping.
- `MediaBlock.look()` (\~96–160): art-derived colour scheme, becomes a `palette.fromImage` helper.
- `CcExpanded`: timer and torch-level semantics.
- `NcGlassLens`: the AGSL edge-refraction shader, kept as an engine effect.
- `LauncherWidgetHostView` via `AndroidView`: the widget host bridge.
- `SpotlightView` and `WidgetPicker`: search ranking and the provider catalogue, not the views.

### Two projections the extraction has to add

- `Notifs.Item` carries Android objects (`StatusBarNotification`, `Notification.Action`, `PendingIntent`, `Icon`, `MediaSession.Token`). Kotlin components can bind to it directly; Lua needs a plain-value projection plus opaque action handles. Write the projection once, in the source, not per component.
- The `Control` enum's look fields (icon, iOS grid size, style, accent token) are rendering concerns. They move into the default control-centre component as KDL defaults; the enum keeps only identity, kind and capability.

## Rewrite as components

Around 26,000 lines of Canvas-drawn views become KDL components over engine primitives. Each is listed with the primitives it proves. The order matters: the first ones exercise the most of the engine.

| Today | Lines | Primitives it needs | Notes |
| --- | --- | --- | --- |
| `shade/nc/*` views, `NotificationCenterView`, `NotifPainter` | \~3,400 | `list` (grouped, swipe actions, stacked/list modes), `glass` (sheet + edge lens), `text`, `icon`, `image`, `transition` | The hardest single component; the acceptance test for `list` and glass |
| `shade/cc/*`, `ControlCenterView`, `CcExpanded`, `CcGallery` | \~3,300 | `grid` (edit mode, drag, resize), `glass`, `slider`, `toggle`, `transition` | `ControlGridBlock` has a RenderNode per control; the `grid` primitive must offer per-cell layers |
| `shade/qs/*` (Pixel shade) | \~5,300 | `list`, `grid`, `slider`, `pager` (edit page), `image` (media art) | Second theme of the same surface: proves surfaces mix across creators |
| `BannerView` | 750 | `stack` of `glass` cards, `transition`, swipe | One GPU layer per card today; the engine's layer policy must match |
| `StatusBarView` (drawing half) | \~1,400 | `row`, `text` with rolling digits, `icon`, springs per slot | Rolling digits become a `text` prop (`roll: true`) |
| `ShadePanelView`, `CcSurfaces`, `CcParts`, `Glyphs` | \~1,100 | `glass`, `mask`, `path` | Mostly glass plumbing the engine will own |
| `HomeViews`, `IconView`, `HomeScreen` (view half) | \~2,800 | `grid` (free placement), `pager`, `icon` (shaped, labelled, badged), `widget`, `sheet` | Home is simpler than the shade once `grid` exists |
| `SpotlightView`, `WidgetPicker` | \~1,900 | `search` field, `list`, `grid` of previews |  |
| `ContextMenuView`, `AlertView`, `Menu` | \~900 | `menu`, `sheet`, `glass`, `transition` |  |
| Drawer views, `AppLibraryView` | \~1,500 | `list`, `grid`, `search`, section index |  |
| `DeckView` (drawing half), switcher cards | \~700 | `deck`, `image` from `HardwareBuffer`, `transition` | Must render on the nav `HandlerThread` (see facts) |
| `LockClockBlock`, clock numerals | \~600 | `text` with `material: glass` (SDF glyphs) | The glass clock is a text material, not a widget |
| `DesignActivity`, `HomeActivity`, `LauncherApp` glue | \~900 | — | Become the engine host; nothing to port |
| `LayoutFile`, `ShadeStructure`, `LayoutSpecs`, the two shade JSONs | \~680 | — | Replaced by the KDL loader. Carry over: placements, options schema, panel semantics, landscape variants, strict validation with reasons, `LayoutFilesTest` |

### What the rewrite must not lose

- **Placements.** Which zone of the top edge (`top`, `left-of-camera`, `right-of-camera`, measured from the cutout) opens which surface. This is surface summon config, declared in the surface's KDL, not in a component.
- **The panel presentation model.** NC progress = y/height with rubber band past 1, opens from standstill past 62.5%; CC progress = dy / `open-travel` with the `100u·(1−e^(−d/270u))` offset spring; Pixel two-stage with 56 dp / 125 dp/s commit and two-finger full expand; fling 900 px/s; release opens past 0.4, drag closes below 0.75; a leaving panel can be caught. These become engine `presentation` types with knobs, so a creator tunes them without re-deriving them.
- **Banners are mandatory.** The disable flags that hide the stock shade also suppress heads-up and full-screen intents. Any shade theme must supply a banner component or inherit the default one.
- **Z-order of the shade window.** The status bar, banners, panels and the platter-to-app card share one window today. The engine's shade surface either hosts all four or reproduces the order (card window under the shade window).
- **The adb test receiver** (`TEST_SHADE`: open/close/pull, tile frames, trace, expand frames). Recreate as engine dev hooks; the editor's device preview will want them anyway.
- **`SHADE_IOS_INVENTORY.md`.** NC 16 off / 13 missing of 47 parts, CC \~12 off / \~5 missing of 43, banners \~12 off / 4 missing, status bar \~16 off / 3 missing. The rewrite inherits this list as its parity checklist; nothing has been compared frame by frame yet.

## Drop

About 2,900 lines go. Each is a probe whose answer is now a fact, a glass path the probes ruled out, or a declaration the KDL format replaces.

| Files | Lines | Why |
| --- | --- | --- |
| `LiveTransitionProbe`, `CaptureProbe`, `SemBlurProbe`, `NcTrace` | 612 | Probes. Their findings are recorded in *Facts the spec must respect* and `PROBE_FINDINGS.md`; the code has no further use |
| `GlassDrawable`, `GlassView`, `BackdropGlass`, `FieldGlass`, the `Painters` set, `BackdropView` | \~1,100 | Earlier glass routes superseded by `MaterialPainter` + `FrostCache` + the Samsung window path. Keep only `NcGlassLens` (extracted) |
| `CcTokens`, `NcTokens`, `QsTokens`, `StatusBarTokens` | 730 | Token *key* declarations. In the engine a component declares its tokens in its KDL `uses` block; the values in `themes/ios27.json` survive as the default theme |
| Dev and test activities | 479 | Replaced by the editor's device preview and the dev hooks |

Not dropped, though it looks like it could be: `StripForward`, `TurnCover`, `Unlock`, `SystemCalm`, the 1×1 parked windows, `FocusHolder`, the dim 1/1024 trick. Each is a workaround for a platform bug that is still present; removing any of them re-opens a bug that took days to find.

## Facts the spec must respect

Proven on devices (S24, Redmi, Android 17 emulator); not open to design. Where the Engine Spec currently says otherwise, the spec is wrong and gets corrected.

**Glass and capture**

1. Nothing is visible from the shell except through capture. `captureDisplay` with `CaptureArgs.setUid` returns one app's own layers in \~8 ms on the S24 (Android 14+): never home, never our windows; secure content ends the stream. This is the live tier. `mirrorDisplay` onto a virtual display crashes the system: dead end, do not revisit.
2. Task snapshots arrive as `HardwareBuffer` (Android 17 via `ITaskSnapshotManager`, earlier via the `mSnapshot` field). They are the static tier and the switcher's pictures.
3. Samsung `semSetBlurInfo(BLUR_MODE_WINDOW)` is real per-view live blur; radius = 4.7 × sigma. `FLAG_BLUR_BEHIND` needs `dimAmount` = 1/1024 or SurfaceFlinger skips the visibility check. Capture frames must be copied into a fixed buffer set made once; allocating per frame leaked 3 GB.
4. `drawLive` relies on View RenderNodes and cannot sit under a clip or an alpha layer. The `glass` primitive must be a surface-level material, not a composable modifier.
5. Windows made from the Shizuku process fail. Every window is created in the app process; the shade is a `TYPE_ACCESSIBILITY_OVERLAY` tied to the accessibility service's lifetime and must be recreated when the service restarts.

**Rendering budget**

6. 120 Hz, one render thread, 121 MB GPU cache, pipeline warm-up required (`GpuWarmUp`), and no overlay relayout mid-gesture (each costs \~17 ms). This is why `FocusHolder` exists instead of flag changes, and why idle windows park off-screen at 1×1.
7. Compose costs that showed up on device: accessibility re-reads on every recomposition, `SnapshotStateMap` churn, alpha layers per item, seeking a spring from Compose. The interpreter must render primitives into RenderNodes it owns and use Compose for the tree, not for per-frame drawing.
8. The deck renders on the nav `HandlerThread`, with a spare `SurfaceControlViewHost` kept warm there. The Compose switcher in the spec either renders there too or accepts a visible first-frame stall; this is an open risk, not a solved one.

**Input and the edge**

9. After 24 dp (or 500 ms) the window manager hands a top-edge pull to SystemUI and cancels our stream. The shade survives by following the shell's raw `/dev/input` touch stream, rotation-mapped, aligned with the last real event, with its own 80 ms velocity estimate. Without this no custom shade can be pulled over an app. The 32 dp strip and `StripForward` re-injection are the same fact from the home side.
10. The shade window must never take focus (it holds `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` and nav-bar hide for the full exclusion budget). Lock-screen focus goes through `FocusHolder` only after `HANDOVER_MS` = 600.

**System state**

11. Disable flags are held on a binder token through `IShellService.setDisableFlags`, never through `cmd`, so they vanish if the service dies. Heads-up and full-screen intents vanish with them, which makes banners mandatory.
12. Hidden secure settings throw on direct read since Android 12; read them through the shell. Third-party tiles run through SystemUI (`cmd statusbar add-tile` / `click-tile`) with state scraped from `dumpsys QSTileHost`.
13. PendingIntents are sent from the app process with `MODE_BACKGROUND_ACTIVITY_START_ALLOWED`, never through the shell; the shell's version lands behind. Transitions finish only after the window commit; `TurnCover` stands in during rotation; never force-stop an app.
14. The watchdog's invariants: arm `SystemRestore` before any system change; heartbeat counting is suspend-safe; recovery works without Shizuku.

**Design model (decided by the code; matches the spec)**

15. Three independent layers: layout (structure), theme (tokens), motion (transitions). Named roles, no literals. The token resolver is three-tier `ref → sys → comp`, chains to 6 deep, light/dark blended continuously, `@palette` resolved at draw time.
16. Transitions are Figma-style keyframe tracks on 25 props with per-keyframe easings, per-role clocks and jump-free handover mid-gesture. The spec's transition section adopts this model as is.

**Unfinished in the launcher, inherited as open work**

Home folders, slow-release trigger, stock transitions, widget gallery, seed layout, inline reply (keyboard under the overlay), CC pages (B-067), banner squash (B-068), Live Activities and capsules (B-112, B-003), status-bar tone still polled from `dumpsys window` every 2.5 s (B-115), stock chips on the Android 17 emulator, platter-to-app card only from iOS NC.

## Revised build order

The spec's build order started from home. The audit moves the shade first, because it touches every engine subsystem and already has a working reference.

1. **Lift the platform layer into an engine module.** Shizuku service, safety, window plumbing, motion, transitions, tokens, glass renderer, fonts, apps, widgets, notifications, controls, media. No behaviour change; the only new code is the capability registry around the AIDL and the `callbackFlow` adapters. Exit: the existing launcher still runs on the module.
2. **KDL loader and the interpreter core.** Parser (own; see open questions), props/uses/state validation with reasons, token binding, the first twelve primitives (`box`, `row`, `column`, `stack`, `text`, `icon`, `image`, `glass`, `list`, `grid`, `slider`, `toggle`), transitions bound to surface progress. Exit: a KDL status bar renders inside the existing shade window, pixel-matched against `StatusBarView`, at 120 Hz on the Redmi.
3. **Shade from KDL.** Surface summon config (placements), presentation types (the NC / CC / Pixel pull maths as knobs), banners, the iOS NC and CC, then the Pixel shade as a second theme. Exit: `SHADE_IOS_INVENTORY.md` parity equal or better; both shades mixable per panel.
4. **Home and drawer from KDL.** `pager`, `sheet`, `search`, `menu`, `widget`, free-placement `grid` with `EditMode` extracted. Exit: the current home layout reproduced; folders and the seed layout, which the launcher never finished, done here.
5. **Switcher.** `deck` primitive on the nav thread, `SurfaceControlViewHost` kept; settle the Compose-on-nav-thread risk with a measurement, not a guess.
6. **Lua workers and the script-facing data API** (projection of `Notifs.Item`, action handles), then the media primitives (web, video, map, lottie).
7. **Editor (Compose Multiplatform) and device preview**, reusing the `TEST_SHADE` hooks as the preview protocol. Remote components via `SurfaceControlViewHost` after the editor, as the spec says.
8. **Store wrapper** (data-APK, Play Billing) once two complete themes exist to sell.

### Risks, in order

- **Interpreter frame cost.** Fact 7 can sink the whole idea: if KDL-over-Compose cannot hold 120 Hz on the shade pull, nothing after step 3 matters. Step 2's exit test is therefore a frame-time benchmark on the Redmi, not a screenshot.
- **Glass as a material, not a modifier** (fact 4). The primitive API has to be designed around this from day one; retrofitting it is a rewrite.
- **Switcher on the nav thread** (fact 8). Unmeasured. Budget a week for the measurement before committing to Compose there.
- **Lua projection of Android objects.** Cheap to do once, expensive to do per component; one projection in the source layer.
- **Parity drift.** The hand-drawn views are the reference; delete each only after its KDL replacement passes the inventory for that surface, and keep the unit tests (`GridTest`, `MotionTest`, the 52 transition tests, `LayoutFilesTest`) running against the engine.

### Spec corrections this audit requires

Glass tiers (live tier = `captureDisplay` + `setUid`; mirror dead end; Samsung window-blur tier; opaque tint floor). Token model → three-tier `ref / sys / comp` plus base16. Transition section → the surface-transition model verbatim. Switcher section → nav-thread risk noted. Spec build order → this one; media primitives in step 6, remote components after the editor. A new *Surface presentation* section carrying the pull maths as knobs. A *Banners are mandatory* rule under Surfaces.
