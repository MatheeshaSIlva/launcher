# E1 inventory: lifting the platform layer into `:engine`

_2026-11-05, branch `engine` at `9984034`. Read-only inventory for review; nothing has moved._

**Method (measured).** Lines are `wc -l`. References come from a token scan of every file under
`app/src/main/java/dev/launcher/app/` (216 files, 71,720 lines): each identifier was matched against the top-level
declarations of every other file, counted when it is visible there (same package, an import, or a fully-qualified
`dev.launcher.app.x.Y` use), then the hits were checked by hand with grep (two false positives removed: `android.R` in
`SafetyNotification` and `PopupStyle`). Class names in strings, reflection and resource ids were grepped separately.
Tests: the same scan over `app/src/test/` (48 files). Not verified: anything that needs a build (resolved dependency
versions, lint on a library module, the merged ART profile of a two-module APK).

## 1. The "Ports as-is" files

Stale names in the audit: `Curves` is a file (`motion/Curves.kt`: `Mover`, `Ease`, `BezierMath`, `CurveTiming`,
`RoleTiming`); `Curve` and `Token` are in `design/Token.kt` (no class `Token`); `Spring` lives at the root, not in
`motion/`; "the resolver half of `Theme`" is `Resolver` (lines 320-359) but nothing in `Theme.kt` blocks moving it whole;
`HomeWidgets` is lines 56-245 of `home/Widgets.kt` (821); "the clock numerals" is `ClockNumerals` + `Edt`, lines 596-906
of `home/HomeViews.kt` (1,096). Cluster totals against the audit: motion 1,027 (audit ~1,700: it counted `Motion`,
`MotionTokens`, `Moments`, `MotionRoles`, 795, which the table does not name), tokens 1,224 (~1,015), glass 2,540
(~2,650), platform 430 (~530); the rest match. All 74 files: 11,777 lines with the two splits, plus 209 lines of AIDL.

Fix: **(a)** the referenced thing moves too, **(b)** an interface or hook the engine defines and the app implements,
**(c)** split the file. "Moves whole" = no reference outside the table. In-table references are not listed.

| Path (under `app/src/main/java/dev/launcher/app/`) | Lines | Unit tests | What stops it moving whole | Fix |
| --- | --- | --- | --- | --- |
| `ShellService.kt` | 687 | none | `LiveTransitionProbe`; AIDL `IShellService`, `ILogWatch`, `IProjectionWatch`, `ITopEdge`, `ITouchStream`, `IWindowWatch` | (a) probe (237, no deps) and the 6 `.aidl` move |
| `ShellCapture.kt` | 106 | none | — | moves whole |
| `TouchStream.kt` | 249 | none | AIDL `ITopEdge`, `ITouchStream` | (a) AIDL |
| `WindowWatch.kt` | 95 | none | AIDL `IWindowWatch` | (a) AIDL |
| `InstantTransitions.kt` | 307 | none | — (string `"dev.launcher.app.HomeActivity"`, l.206: fine while the package stays) | moves whole |
| `TouchInjector.kt` | 43 | none | — | moves whole |
| `Watchdog.kt` (+`WatchdogService`) | 71 | none | `AppLog`; AIDL | (a) |
| `SystemRestore.kt` | 256 | none | `AppLog`; `GestureNav.statusBarShown`, `.ready`, `.update()` (l.154-175) | (a) AppLog, (b) `NavHost` |
| `SafetyNotification.kt` (+2 receivers) | 88 | none | `AppLog` | (a) |
| `SafeSettingsActivity.kt` | 110 | none | `AppLog`; `GestureNav.ownScreenDrawn()` (l.75) | (a), (b) `NavHost` |
| `StockBarSettings.kt` | 94 | none | AIDL | (a) |
| `PopupStyle.kt` (+receiver) | 115 | none | `AppLog`; AIDL | (a) |
| `ShizukuLink.kt` | 191 | none | `AppLog`; `LauncherApp` (type of `init`, `buildStamp()` as the service version, l.42); `Badges.component()` (l.143); `GestureNav.ready` (l.54) | (a), (b) `EngineHost` (stamp, listener component), (b) `NavHost` |
| `NoAnimStarts.kt` | 105 | none | `AppLog`; AIDL | (a) |
| `NavAccessibilityService.kt` | 86 | none | `AppLog`; `GestureNav.attach/detach/onWindowStateChanged/onContentChanged` | (a), (b) `NavHost` |
| `Updater.kt` | 75 | none | `AppLog`; `LauncherApp.buildStamp()` | (a), (b) `EngineHost` |
| `motion/Curves.kt` | 184 | Motion, SymbolTime, ClockDrop, NcClose, NcReveal | `Design.version` (l.179), `Motion.slow()`, `Motion.role()` | (a) |
| `Spring.kt` | 67 | Motion, ClockDrop, NcReveal | `Motion.slow()` | (a) |
| `motion/MotionValue.kt` | 124 | none | `Motion.profile`; app `R.id.spring_translate`, `R.id.drag_held` | (a); ids to engine res (risk 3) |
| `motion/MotionAdjust.kt` | 70 | Moments, Motion | — | moves whole |
| `motion/MotionClock.kt` | 46 | none | — | moves whole |
| `motion/CurveFit.kt` | 181 | Motion | — | moves whole |
| `motion/IosScroller.kt` | 192 | none | `Motion.profile`, `Motion.rubberBand` | (a) |
| `motion/AppStyles.kt` | 135 | Moments | `Design.choice()` | (a) |
| `motion/SymbolTime.kt` | 28 | SymbolTime | — | moves whole |
| `motion/transition/PanelPlayer.kt` | 423 | PanelPlayer, IosNcTracks | — | moves whole |
| `motion/transition/Transition.kt` | 348 | the 5 transition tests | — | moves whole |
| `motion/transition/TransitionJson.kt` | 122 | TransitionJson, PanelPlayer, IosNcTracks | — | moves whole |
| `motion/transition/TransitionTypes.kt` | 71 | 4 transition tests | — | moves whole |
| `design/Token.kt` | 179 | 10 (DesignTheme, ThemeFiles, Motion, TokenParts, Blends...) | — | moves whole |
| `design/Theme.kt` | 409 | 13 (DesignTheme, ThemeFiles, Fonts, Moments...) | — | moves whole (no split needed) |
| `design/TokenParts.kt` | 34 | TokenParts | `layout.Element` | (c) `Element` (13 lines) out of `layout/Layouts.kt` |
| `design/MaterialYou.kt` | 292 | MaterialYou, SystemPalette | — | moves whole |
| `design/SystemPalette.kt` | 310 | SystemPalette | `AppLog`, `Design`; `GestureNav.idle`, `.homeVisible`; `HomeBridge.touching`, `.home`, `.lastTouchAt` (l.137-142) | (a), (b) `NavHost`, (a) `HomeBridge` |
| `design/MaterialPainter.kt` | 1,059 | none | `AppLog`, `Design`, `Appearance`, `Reveal`, `Shaders` | (a) |
| `design/FrostCache.kt` | 101 | none | `AppLog` | (a) |
| `design/SamsungBlur.kt` | 95 | none | `AppLog` | (a) |
| `design/Blur.kt` | 15 | none | — | moves whole |
| `design/Blends.kt` | 62 | Blends | — | moves whole |
| `design/ViewPicture.kt` | 213 | none | `AppLog` | (a) |
| `design/RenderLease.kt` | 26 | RenderLease | — | moves whole |
| `design/GpuWarmUp.kt` | 101 | none | `AppLog` | (a) |
| `design/IdleWorker.kt` | 18 | IdleWorker | — | moves whole |
| `design/Still.kt` | 34 | none | `Design.addListener`, `Motion.role`, `MotionTokens` | (a) |
| `design/RoundedBitmaps.kt` | 52 | none | — | moves whole |
| `design/IconSets.kt` | 247 | none | `AppLog`; **Compose** `PathParser`, `asAndroidPath` (l.10-11) | (a); engine needs `compose.ui:ui-graphics` (Q7) |
| `design/SymbolMotion.kt` | 279 | SymbolMotion, SymbolPlayer, SymbolTime | — | moves whole |
| `design/SymbolDrawer.kt` | 138 | none | — | moves whole |
| `design/SymbolPlayer.kt` | 100 | SymbolPlayer, SymbolTime | — | moves whole |
| `theme/Fonts.kt` | 186 | none | `AppLog`, `Design` | (a) |
| `theme/FontFamily.kt` | 37 | Fonts | — | moves whole |
| `theme/GoogleFonts.kt` | 118 | none | — | moves whole |
| `theme/GoogleFontCatalog.kt` | 217 | GoogleFontSearch | `AppLog`; kotlinx-coroutines (transitive today) | (a); explicit coroutines dep |
| `theme/PaletteTokens.kt` | 23 | DesignTheme | — | moves whole |
| `apps/Apps.kt` | 178 | none | `AppLog`; `HomeActivity::class` (l.144: skip ourselves in the list) | (a), (b) `EngineHost.homeComponent` |
| `apps/Icons.kt` | 208 | none | — | moves whole |
| `apps/AppCategory.kt` | 119 | none | — | moves whole |
| `apps/LaunchStats.kt` | 40 | none | — | moves whole |
| `apps/SplashColors.kt` | 58 | none | — | moves whole |
| `home/Grid.kt` | 254 | Grid, GridFit | — | moves whole |
| `home/HomeModel.kt` | 232 | Grid, GridFit | `AppLog` | (a) |
| `home/HomeMetrics.kt` | 174 | GridFit | `Scale` (in `design/Design.kt`) | (a) |
| `home/HomeConfig.kt` | 87 | none | `layout.Element`, `layout.Setup` (reads every option through it) | (a) all of `layout/` (821) |
| `home/DepthBlur.kt` | 35 | DepthBlur | — | moves whole |
| `home/GlassLabelTone.kt` | 24 | none | `Motion`, `MotionTokens`, `Appearance` | (a) |
| `home/HomeViews.kt` → `ClockNumerals`+`Edt` | ~311 of 1,096 | ClockNumerals, ClockSelect, Edt | file: views (`PageView`, `ClockWidgetView`, `DockView`...). The two objects: `GlassMask`, `ClockShadow` (`GlassDrawable.kt`, on the audit's drop list), `Motion`, `MotionTokens`, `AppLog` | (c), and `GlassMask`/`ClockShadow` move too or the numerals wait (Q6) |
| `switcher/SwitcherHost.kt` | 182 | none | `AppLog` | (a) |
| `switcher/SwitcherActivity.kt` | 114 | none | `AppLog` | (a) |
| `switcher/SwitcherInput.kt` | 127 | none | `AppLog` | (a) |
| `home/Widgets.kt` → `HomeWidgets` | ~190 of 821 | none | the class: `LauncherWidgetHostView` (same file, l.246-465: `Motion`, `CurveTiming`, app `R.layout.widget_blank`), `HomeItem`, `HomeMetrics`, `ShizukuLink`. The file: `GlassView`, `GlassStyle`, `HomeTokens`, `RemoveBadge`, `FadingShadow` | (c) l.42-465 out, after the home model (Q6) |
| `StripForward.kt` | 85 | none | `AppLog` | (a) |
| `TurnCover.kt` | 163 | none | `AppLog`; `HomeBridge.preview` | (a) |
| `Unlock.kt` (+`UnlockActivity`) | 118 | none | `AppLog` | (a) |
| `SystemCalm.kt` | 64 | none | `AppLog` | (a) |

No unit test covers any file of the Shizuku, safety, link, apps, switcher or platform clusters (4,134 lines).

## 2. Transitive closure

Without cuts the closure of the table is **214 of 216 files**: `ShizukuLink`/`SystemRestore` reach `GestureNav`, which
reaches everything. With the eight cuts below the closure is **77 files + `Element`, 13,217 lines** (+209 AIDL), and no
edge leaves it.

| # | Edge into the app | Fix | Edit size (est.) |
| --- | --- | --- | --- |
| 1 | `SystemRestore`, `SafeSettingsActivity`, `ShizukuLink`, `NavAccessibilityService`, `SystemPalette` → `GestureNav` (14 uses) | (b) `NavHost`: `ready`, `statusBarShown`, `idle`, `homeVisible`, `update()`, `ownScreenDrawn()`, `attach/detach(svc)`, `onWindowStateChanged`, `onContentChanged`; `GestureNav` implements it, set first in `LauncherApp.onCreate` | ~50 |
| 2 | `ShizukuLink`, `Updater` → `LauncherApp.buildStamp()`; `ShizukuLink` → `Badges.component()`; `Apps` → `HomeActivity::class` | (b) `EngineHost` (`buildStamp`, listener component, home component) | ~30 |
| 3 | `HomeBridge` → `HomeActivity` (3 casts: `onHomeSwipeUp`, `hasOnTop`, `forwardTouch`, l.277-289) | lift the 3 onto the existing `HomeBridge.Home` interface (only `HomeActivity` implements it); `HomeBridge` (314) then moves (a) | ~10 |
| 4 | `Design` → `DevFiles.run` (l.147; `DevFiles` reaches `QsTileStore`, `Layouts`) | (b) a files hook set by the app; `DevFiles` stays | ~8 |
| 5 | `Design`, `TokenParts` → `layout.Element` | (c) `Element` (13 lines) to its own file `layout/Element.kt` | ~15 |
| 6 | `MotionValue` → app `R.id.*` | ids to `engine/src/main/res/values/ids.xml`, `MotionValue` and `EditMode` (6 lines) to `dev.launcher.engine.R` together | ~12 |
| 7 | `ShellService` → `LiveTransitionProbe` | (a) moves (0 deps); dropping it is a separate change (Q5) | 0 |
| 8 | `TurnCover`, `SystemPalette` → `HomeBridge` | gone with cut 3 | 0 |

**The cycle.** The leaf clusters are not leaves. After the cuts, 27 files form one strongly connected component of
**5,304 lines** that can only move together: `Design` ↔ `Motion` ↔ `Curves` ↔ `Spring` (every `Mover` reads
`Motion.slow()`, which reads `Design`); `Token` → `TransitionSpec` → `Transition` → `BezierMath` (in `Curves`) → `Design`
→ `Token`; `Design` ↔ `Fonts`, `Appearance`, `Moments`/`AppStyles`; and the safety cluster through `Design` →
`SystemPalette.phonesOwn()` (l.735) and `SystemPalette` ↔ `SystemRestore`/`ShizukuLink`. Where to cut, if the reviewer
wants smaller pieces (each an edit, not a move): (i) `phonesOwn` behind a `Design` hook takes the safety cluster (≈1,250
lines) out of the knot; (ii) `BezierMath`, `Ease`, `Mover` split from `RoleTiming`/`CurveTiming` would make the transition
model a true leaf, but `Ease` and `Spring` still read `Motion.slow()`. My recommendation: no cuts inside the engine in
E1; move the knot as one pure `git mv` (it does not matter inside one module), and leave (i)/(ii) to step 2, where the
engine's motion API is designed anyway.

## 3. Shared utilities the port files lean on (not in the table)

| File | Lines | Used by (port files) | Verdict |
| --- | --- | --- | --- |
| `AppLog.kt` | 33 | 29 of the 74 | moves first (no deps; nothing compiles without it) |
| `design/Design.kt` (incl. `Scale`, l.1047) | 1,112 | Curves, AppStyles, SystemPalette, MaterialPainter, Still, Fonts, HomeMetrics | **moves** (audit: Extract); cut 4 and 5 first |
| `motion/Motion.kt` | 123 | Curves, Spring, MotionValue, IosScroller, Still, GlassLabelTone | moves |
| `motion/MotionTokens.kt` | 243 | Still, GlassLabelTone (+ `Design`, `Motion`) | moves |
| `motion/Moments.kt` | 134 | `Design` | moves |
| `theme/Appearance.kt` | 229 | MaterialPainter, GlassLabelTone, `Design` | moves |
| `Wallpaper.kt` | 249 | `Design.onWallpaper` | moves (needs only ViewPicture, AppLog) |
| `HomeBridge.kt` | 314 | TurnCover, SystemPalette | moves after cut 3 |
| `Reveal.kt`, `design/Shaders.kt` | 61, 12 | MaterialPainter | move |
| `LiveTransitionProbe.kt` | 237 | ShellService | moves (Q5) |
| `layout/Layouts.kt` | 213 | Element only (cut 5); all of `layout/` (821) if home moves | split now; rest with home |
| `design/DevFiles.kt` | 87 | `Design` | stays, behind a hook |
| `motion/MotionRoles.kt` | 295 | none (settings, MotionTest, MomentsTest) | stays |
| `design/Components.kt` | 207 | none (ComponentsTest) | stays |
| `theme/FadingShadow.kt`, `theme/SoftShadow.kt` | 25, 70 | HomeViews, Widgets only | stay (views) |
| `IdleWorker`, `Still` | — | — | already in the table (glass) |
| `shade/FocusHolder.kt`, `shade/LiveBlur.kt` | 89, 360 | none | not in the table though the audit's verdict names them; could move whole (AppLog, SamsungBlur only) (Q1) |
| `shade/Notifs.kt`, `Media.kt`, `AppInfo.kt`, `Badges.kt` | 359, 163, 61, 198 | ShizukuLink (Badges) | not in the table, but build-order step 1 names them; a 781-line cycle (`Notifs`↔`Media`↔`Badges`), needs `Unlock`, `PopupStyle` (Q1) |
| `shade/Controls.kt` | 615 | none | step 1 names it; needs `SettingsActivity`, `CcTokens`: a split (Q1) |

No `components/` file is needed by the closure.

## 4. Module shape

`:engine`: `com.android.library` 8.7.3, Kotlin 2.0.21, `compileSdk 35`, `minSdk 29`, Java 17, `buildFeatures.aidl = true`,
no Compose compiler plugin (no `@Composable` among the moving files). Dependencies, all already in today's APK:
`dev.rikka.shizuku:api` 13.1.5 (only `ShizukuLink` imports it; the app keeps `provider` for its manifest entry),
`androidx.compose.ui:ui-graphics` from BOM 2024.10.01 (`IconSets`' `PathParser`), `kotlinx-coroutines-android` (`HomeBridge`,
`GoogleFontCatalog`; version resolved today not read: several in the Gradle cache, 1.6.4/1.7.3/1.9.0). App:
`implementation(project(":engine"))`. Gradle namespace must differ from the app's (`dev.launcher.app` twice would make two
`dev.launcher.app.R`): **`dev.launcher.engine`**, whatever the Kotlin packages are.

**Renaming packages to `dev.launcher.engine.*` would cost (measured):** 113 of the ~138 files left in the app reference
moved code, 1,319 fully-qualified `dev.launcher.app.<pkg>.X` references in them (this codebase writes FQ names inline),
270 (file, package) import pairs, 39 root-package files that use `AppLog`, `ShizukuLink`... with no import today, and 25 test
files / 147 FQ references; 9 manifest entries (`.NavAccessibilityService`, `.WatchdogService`, `.RestoreReceiver`,
`.GestureToggleReceiver`, `.PopupStyleReceiver`, `.UnlockActivity`, `.TurnCover`, `.switcher.SwitcherActivity`,
`.SafeSettingsActivity`) become fully-qualified; AIDL packages and every `IShellService` user change. **And it is a
behaviour change on the phone:** the accessibility service is stored by class name in Secure
`enabled_accessibility_services` (`NavAccessibilityService.component`): renamed, gesture nav is off after the update until
re-enabled; posted notifications' PendingIntents to the three receivers point at old class names; the baseline profile
stops covering moved code (risk 2).

**Recommendation: keep every Kotlin and AIDL package as it is in E1** (files move under `engine/src/main/java/dev/launcher/app/...`;
Kotlin allows split packages across modules, and the manifest's relative names still resolve against the app's namespace).
E1 then changes no class name, no manifest, no stored component, no profile rule: a move is a move. Rename once, in its
own milestone step, when the engine's public API exists (step 2), with the stored-component migration written for it.

## 5. Slice plan (agreed with the reviewer, 2026-11-05)

E1 = the Ports table minus home (home model, `layout/`, `HomeWidgets`, `ClockNumerals` go to step 4), plus `FocusHolder`
and `LiveBlur`. Pure `git mv` slices are cluster-sized; every slice that edits keeps to ~200 changed lines. After every
slice: `scripts/check.sh` and `tools/dexlist.sh [SLICE]`, the APK's classes and per-class method counts against
9984034's, a move slice with an empty diff, an edit slice with exactly what `tools/dexlist/SLICE.expect` declares.
Lint entries move into `engine/lint-baseline.xml` with their files, the app's stale ones pruned in the same slice. The
capability registry and the flow adapters are not in E1: they are written in step 2 for their first consumer (the
reviewer's deviation entry in `DECISIONS.md`).

| # | Slice | Changed lines | Kind | Exit check beyond check.sh + dexlist | Can change phone behaviour? |
| --- | --- | --- | --- | --- | --- |
| S0 | `:engine` scaffold (settings include, root library plugin, `engine/build.gradle.kts`, empty manifest and lint baseline, app dependency); `tools/dexlist.sh` + reference + `S0.expect`; test `SourceRoots` under the three source-scanning tests | ~280 incl. the script | edit | dexlist `S0` (`dev.launcher.engine.R` only) | no |
| S1 | Cuts 1-2: one `EngineHost` with `nav: NavHost`, set first in `LauncherApp.onCreate`, read through an accessor that fails loudly when unset (commented: `ShizukuProvider`'s path runs before `onCreate` and must never reach it); tests: the accessor's failure, and a scan that every engine read goes through it and no shell-cluster file names `EngineHost`/`NavHost` | ~150 (est.) | edit | `--rerun-tasks` once; `smoke_emulator.sh` before and after; log "gesture strip on", "[shizuku] service connected: true" | yes, init order: what the loud accessor and the smoke run are for |
| S2 | Cuts 3-6: `HomeBridge.Home` +3, files hook, `Element` split, view-tag ids into engine res (`MotionValue` + `EditMode` together, gone from app `ids.xml`) | ~45 (est.) | edit | emulator: `DESIGN_RELOAD --es files export` ends with `dev-out/.done`; edit-mode drag; no launcher entry in the app list | yes, small: each cut has its step |
| M1 | Leaves: AppLog, RenderLease, ViewPicture, Wallpaper, SymbolMotion, IconSets, MaterialYou, Element, TokenParts, FontFamily, GoogleFonts, GoogleFontCatalog, IdleWorker, Blur, Shaders, Reveal, MotionClock, SplashColors, AppCategory, FocusHolder; engine deps ui-graphics + coroutines, pinned to the versions the APK has (owner told first; re-planned without IconSets/GoogleFontCatalog if not) | 2,163 | move | ART profile: `tools/device.sh install` (with the `.dm`), `cmd package dump-profiles ... --dump-classes-and-methods` lists a moved class, `pm art dump` says `install-dm` | no |
| M2 | Shell: ShellService, ShellCapture, TouchStream, WindowWatch, InstantTransitions, TouchInjector, LiveTransitionProbe + 6 `.aidl` | 1,933 | move | emulator: service connects; smoke (instant starts, raw-stream pulls via `tools/kswipe.sh`) | no |
| M3 | The 27-file knot (§2) | 5,304 | move | emulator smoke; Restore system from its notification; watchdog kill test (emulator only) | no |
| M4 | Glass: Blends, SamsungBlur, MaterialPainter, FrostCache, GpuWarmUp, RoundedBitmaps, SymbolDrawer, SymbolPlayer, LiveBlur | 2,068 | move | `tools/fidelity/diff.py` on the Pixel shade unchanged | no |
| M5 | Motion rest: CurveFit, IosScroller, MotionValue, SymbolTime, PanelPlayer | 948 | move | — | no |
| M6 | Platform, apps, switcher: NoAnimStarts, StripForward, SystemCalm, TurnCover, Unlock, Updater, Apps, Icons, LaunchStats, Switcher×3 | 1,459 | move | emulator App Switcher + `PipTestActivity` | no |

E1 exit: (a) `tools/dexlist.sh` after every slice as above; (b) `smoke_emulator.sh` passes; (c) the ART profile check
after M1; (d) the engine's own lint baseline, carried per slice; (e) on the S24, owner told first: `tools/device.sh install`
(APK + `.dm`, then the HOME intent, never force-stop) of the E1 build and of a 9984034 build, `scenario_switch.sh` and
`scenario_shade.sh` interleaved between them.

## 6. Risks

1. **Kotlin incremental build** (CLAUDE.md pitfall): S1 adds interfaces implemented in another file;
   `./gradlew compileDebugKotlin --rerun-tasks` once after S1 and S2, and after each move slice the first time.
2. **Baseline profile.** `app/src/main/baseline-prof.txt` is two wildcard rules, `HSPLdev/launcher/app/**;->**(**)**` and
   `Ldev/launcher/app/**;`; the merged profile (`app/build/intermediates/merged_art_profile/debug/.../baseline-prof.txt`,
   1,937 lines) is those two plus the libraries' rules. With packages kept, the rule still names the moved classes; that
   AGP compiles it against a project library's dex (expected) is **not verified**: check after M1 with
   `cmd package dump-profiles dev.launcher.app --dump-classes-and-methods` (a moved class listed) and `pm art dump`
   (`speed-profile [reason=install-dm]`). A rename would silently drop AOT for all of it.
3. **View-tag ids across modules.** `drag_held` is set by `EditMode` (stays) and read by `MotionValue` (moves). With
   `android.nonTransitiveRClass=true`, if the app kept its own `drag_held` the two would be different ints and the build
   would pass: `MotionValue.grow` would stop seeing a held icon. Hence S2: one id, in the engine, deleted from the app.
4. **Lint.** `./gradlew check` will lint `:engine` with no baseline: 91 of the app baseline's 275 entries are in moving
   files, among them `NewApi` 17 and `UnspecifiedRegisterReceiverFlag` 4 (errors). The engine needs those entries carried
   into `engine/lint-baseline.xml` with paths rewritten, in the slice that moves each file; the app's stale entries are
   then pruned. Whether library lint reports anything new is unknown until S0 runs.
   Found in M1: library lint reports `MissingPermission` the app's did not (`Wallpaper.kt`: `SET_WALLPAPER`, now declared in
   the engine's manifest; `getDrawable`, baselined in the engine with a comment to revisit).
5. **ProGuard/R8:** off. `app/build.gradle.kts` defines only `debug` (`isDebuggable = false`), no `isMinifyEnabled`, no
   release type; no keep rules needed. Revisit at the store build (reflection in `ShellService`, Shizuku's
   `UserServiceArgs` by class name).
6. **`profileable`** stays in the app manifest's `<application>`; the engine manifest must be empty (no `<application>`
   attributes to merge). The 9 component entries and their resources (`@string/a11y_label`, `@xml/accessibility_service`,
   `@style/SwitcherWindow`, `@integer/config_*`, `@mipmap/ic_safe`) stay in the app manifest.
7. **Tests stay in `app/src/test`.** 16 test files read `src/main/assets/...` relative to the module and several touch shade
   code; app tests see the engine's public API. Kotlin `internal` is per module: the only crossing found is `Setup`'s 5
   internals used by `SetupTest` (moves in step 4, not E1). **Source scans by path** (`TokenPartsTest`, the B-041
   `RedactionSourceTest`, `CardPressSourceTest`) would silently skip moved files: since S0 they read through the test
   helper `SourceRoots` (both modules' roots, a recorded minimum file count per package, a file in both modules fails).
8. **Paths in docs** (`CLAUDE.md`, `docs/*`) go stale; assets stay in the app, so `tools/design/*.py` and CI are unaffected.
9. **Initialization order (S1):** `NavHost`/`EngineHost` must be set before any service, receiver or activity runs; only
    `Application.onCreate` guarantees that. The shell process (`ShellService`) never touches them.

10. **The watchdog kill test cannot be run with a force-stop on the emulator** (found in M3): Android restarts the app
    within ~0.6 s (bound accessibility and listener services), and the restarted app restores the scales itself ~1.9 s
    after the kill, so the shell loop never sees 4 missed heartbeats. What tests the loop: make its heartbeat file
    read-only (`chmod 444 /data/local/tmp/launcher-wd/hb`), so the service's writes fail; the loop then fired 3.98 s
    after the heartbeat froze (M3 build). Re-arm with Developer's "Test: stop the shell service" afterwards.
11. **App-side smart casts on a moved class's property stop compiling** (Kotlin forbids them across modules): each is
    rewritten to its smallest equivalent (an explicit cast under the guarding `is`, a `let`), quoted in the slice's
    hand-back and counted as a code edit. M3: DesignActivity l.338, MaterialPainter l.42-43.
12. **What the counts-only dex check cannot see** (M6): Kotlin's `internal` members change their mangled name
    (`$app_debug` to `$engine_debug`) and D8 renumbers a moved file's synthetic lambda classes
    (`SwitcherHost$$ExternalSyntheticLambda6` to `...2`); both harmless, both listed per slice from a dexdump of
    member names against the previous slice's APK, which every move slice ran alongside the counts.

## Questions for the reviewer

1. **Which list is E1?** The Ports table (home model, switcher, widgets) and build-order step 1 (adds notifications,
   controls, media, window plumbing: `Notifs`/`Media`/`Badges`/`AppInfo` 781, `Controls` 615, `FocusHolder` 89, `LiveBlur`
   360; omits home) disagree. I suggest the table minus home (§2 set) plus `FocusHolder` and `LiveBlur` (move whole), and
   notifications/controls/media in step 3, where their extraction (projection, `Control` split) is designed.
2. **Accept that `Design` (1,112, audit "Extract") and `Motion`, `MotionTokens`, `Moments`, `Appearance`, `Wallpaper`,
   `HomeBridge` move in E1?** Nothing in the motion, glass, token or font clusters compiles without them.
3. **Keep `dev.launcher.app.*` packages in E1** (namespace `dev.launcher.engine`), rename later in its own step?
4. **Slice size for pure moves**: cluster-sized `git mv` slices (M1-M6) instead of ~66 slices of 200 lines?
5. **`LiveTransitionProbe` and the two probe AIDL methods** (17, 43): move in M2 and drop later, or drop in E1 as its own
   slice (a removed adb dev hook, so a behaviour change)?
6. **Home model, `layout/`, `HomeWidgets`, `ClockNumerals` in E1 or in step 4?** In E1 they need slice H, `Setup`'s
   internals made public, and the numerals need `GlassMask`/`ClockShadow` from `GlassDrawable` (on the drop list).
7. **Compose `ui-graphics` in the engine** for `IconSets`' path parser (already in the APK), or swap to
   `androidx.core.graphics.PathParser` (a different parser: a behaviour risk on every symbol)? I recommend the former.
8. **May I run `./gradlew -q :app:dependencies --configuration debugRuntimeClasspath` once** to pin the coroutines
   version the APK ships today? (Outside the allowed `--dry-run`/`help`.)
9. **One `EngineHost` object or several small hooks** (`NavHost`, stamp, components, files)? I lean to one `EngineHost`
   with a `nav` member, set in `LauncherApp.onCreate`.
10. **E1's exit test** as written at the end of §5 (class list identical, smoke, S24 interleaved scenarios), or more?
