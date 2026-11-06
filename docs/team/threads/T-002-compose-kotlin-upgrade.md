# T-002: Compose 1.11.4 and Kotlin 2.2 (E2 slice 0a)

- **Step:** E2 · **Lead:** claude · **Opened:** 2026-11-06 · **Status:** DISCUSSING
- **Read first:** `docs/E2_PLAN.md` §b ("Hosting inside the shade's window") and its question 1; `docs/BENCH_STEP0.md` §4
  (why 1.7.5 cannot draw Compose off the main thread while an accessibility service is on); D-069, D-071 (the engine
  compiles with the Compose plugin; `$stable` metadata), D-074; CLAUDE.md's Compose pitfalls (search "Compose").
- **Owner's go (2026-11-06):** "upgrade to Compose 1.11.4 and Kotlin 2.2 as its own slice. Measure the settings before
  and after, plus the S24 shade and switch scenarios. Declare the expected class-list diff first. If AGP or the compile
  SDK has to move as well, stop and ask me before going further."

## 1. Proposal (lead)

**Goal.** The app and `:engine` build on Kotlin 2.2.21 with Compose 1.11.4, so a `ComposeView` can live on gesture nav's
`nav-ui` thread (E2 slice 6) without Compose's accessibility checker measuring it from the main thread. Nothing the
owner sees changes; the settings app, the shade and the App Switcher measure the same on the S24.

**Approach.** One slice, build files first, then whatever the new compiler refuses:

1. Root `build.gradle.kts`: `org.jetbrains.kotlin.android`, `.jvm` and `.plugin.compose` 2.0.21 → 2.2.21. AGP stays
   8.7.3, Gradle 8.9, compileSdk 35 (KGP 2.2.21 supports AGP 7.3.1-8.11 and Gradle 7.6.3-8.14; Compose 1.11.4's AAR
   metadata asks compileSdk ≥ 35 and AGP ≥ 8.6.0, measured in step 0). **If the build says otherwise, stop and ask.**
2. Compose: the BOM 2024.10.01 → the BOM that maps `ui` to 1.11.4 if one exists, else `ui`, `foundation`,
   `animation`, `runtime` pinned to 1.11.4 in the app and `:engine`. `activity-compose` stays 1.9.3 unless 1.11.4
   requires newer (the step-0 bench built with 1.9.3).
3. Kotlin 2.2 refuses `kotlinOptions {}` (deprecated to error): every module moves to `kotlin { compilerOptions {
   jvmTarget.set(JvmTarget.JVM_17) } }`. The bench modules' `-Xskip-metadata-version-check` goes.
4. Source fixes the new compilers force, each one quoted in the report.
5. `AndroidComposeUiFlags.isViewBasedSemanticsHandlerEnabled` stays at its default (true in 1.11.4), checked in the
   dex, not assumed.

**Declared class-list diff, written before building** (`tools/dexlist.sh` against the E2-11 state). Categories:

| Category | Expected | Why |
| --- | --- | --- |
| `Landroidx/compose/**` | changed, added and removed in bulk | 1.7.5 → 1.11.4 |
| other `Landroidx/**` the new Compose pulls (lifecycle, core, collection, graphics-path, emoji2, profileinstaller...) | changed and added | transitive versions move |
| `Lkotlin/**`, `Lkotlinx/**` | changed and added | stdlib 2.0.21 → 2.2.x; coroutines stays 1.7.3 unless Compose pulls newer (then it is named) |
| `Ldev/launcher/**` classes with `@Composable` functions (settings, `engine/ui`) | method counts change | the Compose compiler 2.2 generates differently (default-parameter and group methods) |
| `Ldev/launcher/**` interfaces with default methods and their `$DefaultImpls` | `$DefaultImpls` classes removed, interfaces gain methods | Kotlin 2.2's default `-jvm-default=enable` |
| `Ldev/launcher/**` other classes | **no change** | anything else in our classes stops the slice until explained |
| merged manifest | only `uses-sdk`/`<meta-data>` entries the new androidx libraries add | everything else stops the slice |

How it is checked: the full diff is written to `tools/shots/t002/dexdiff.txt`, a small filter sorts every line into
the categories above, and the report quotes the count per category and every `Ldev/launcher` line outside the first two
of ours verbatim.

**Verification.**
- `scripts/check.sh` green (all unit tests: app 421, engine 73).
- The dex diff sorted as above, with no unexplained `Ldev/launcher` change.
- Emulator: `tools/smoke_emulator.sh` passes; the settings open, a page opens and closes, and Look's theme reveal plays
  (screenshots of our own screens only).
- S24 (owner's go above), interleaved ref, new, ref, new as in E1's exit (`tools/e1_s24.sh`'s pattern, both builds
  stamped with their commit and installed with their `.dm`): the settings (a new `tools/scenario_settings.sh`: open from
  home, a tile's page opens and closes, the tile list scrolls, Look opens, back to home: our own screens only),
  `scenario_shade.sh`, `scenario_switch.sh`. Pass: no animation worse by more than the two rounds' own spread, and no
  new missed refresh pattern in the settings; the summary shows every pass, not only the last (the e1_s24 flaw fixed).
- APK size before and after, and `pm art dump` still `speed-profile [reason=install-dm]` (the libraries' baseline
  profiles change with their versions).

**Risks and open questions.**
- Kotlin 2.2 may refuse code 2.0.21 accepted (new errors for deprecations, smart-cast or nullability changes); each fix
  is listed, none changes behaviour without saying so.
- Compose 1.11 behaviour changes in what the settings use (text layout, focus, `Modifier.Node` APIs, pointer input):
  the S24 run is what catches a visible one; any found goes to the owner before the slice is signed.
- 1.11.4 may require a newer `activity-compose`, `lifecycle` or `core` that asks compileSdk 36: that is the stop rule.
- The app's baseline profile `.dm` comes from the libraries' rules: it changes; the ART check confirms it still compiles.

**Split.** claude owns every file (the build files, every source fix, the scenario script, the measurements, the
phone). Codex: discussion now, then the cross-check of the diff and the report (§6). Nothing for Codex to build here: the
slice is one coherent change that only compiles as a whole.

## 2. Discussion

## 3. Agreed approach

## 4. Split

| Sub-task | Owner | Files owned | Branch | Verified by |
| --- | --- | --- | --- | --- |

## 5. Reports

## 6. Cross-check

## 7. Sign-off
