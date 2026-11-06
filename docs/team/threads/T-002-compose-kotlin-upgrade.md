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

### claude — 2026-11-07

**Changed** (branch `team/claude/t002-compose`, build commit `9aa6017`, script fixes up to `37fccd2`; on top of T-003's
AGP 8.13.2): Kotlin plugins 2.0.21 → 2.2.21; Compose BOM 2024.10.01 → 2026.06.01 (ui, foundation, runtime 1.11.4);
`kotlinOptions {}` → `kotlin { compilerOptions { jvmTarget = JVM_17 } }` in every module; `:engine` pins coroutines
1.9.0 (what Compose 1.11.4's ui resolves; it was 1.7.3); the bench modules' `-Xskip-metadata-version-check` removed. No
source file needed a change for the new compilers. compileSdk 35 and AGP stay (the stop rule did not trigger).

**Verified (measured):**
- `scripts/check.sh` on `9aa6017`: CHECK PASSED (unit tests app 421, engine 73; lint 8.13.2: 0 errors, nothing added to
  either baseline). `-Pbench` modules compile (§6 answer).
- **Dex, our classes** (`tools/shots/t002/ours-diff.txt`, 530 lines, class name plus direct and virtual method counts):
  | Category | Lines | Account |
  | --- | --- | --- |
  | `settings/**` and `engine/ui/**` (files with `@Composable` functions) | 494 | Compose compiler 2.2 output and Compose 1.11's interface members (`Modifier.Node`, draw policy) |
  | 11 interfaces gain 1–2 direct methods (`HomeBridge$Home`, `AppDrawer`, `DrawerHost`, `EditMode$Host`, `SpotlightView$Host`, `Mover`, `RoleClock`, `NotificationCenterView$Host`, `NcPanel`, `expr/Env`, `layout/Child`) | 22 | Kotlin 2.2's `$jd` / `$default` static accessors on interfaces with default methods; their `$DefaultImpls` classes **remain** (Codex was right; my declaration said removed) |
  | renamed lambdas and local classes, same members (e.g. `HomeActivity$onCreate$5` → `$6`, `Notifs$watchMedia$1` → `$2`) | 14 | Kotlin 2.2 numbers them differently |
  | anything else | 0 | |
  Libraries: androidx and kotlin/kotlinx changed in bulk as declared (`dexdiff.txt`, 18,844 lines); coroutines 1.9.0
  named above. Merged manifest: only androidx's added entries. Counts, not bodies: structural evidence, as for T-003.
- **APK** 19.8 MB → 23.7 MB (Compose 1.11 and its libraries). `.dm` still produced; on the S24 `pm art dump`:
  `[status=speed-profile] [reason=install-dm]`.
- **Emulator:** `tools/smoke_emulator.sh` SMOKE PASS; settings opened, a page opened and closed (our screens only).
- **S24** (`tools/compare_s24.sh`, run `run-20261107-043041`, interleaved ref `44531b4` / new `9aa6017` / ref / new,
  each installed with its `.dm`; "complete: every animation measured in every pass of every run"): **ref 15 missed
  refreshes over 70 animations, new 14 over 70.** Per animation the difference is never more than one miss per pass
  either way; the worst is Notification Center's opening for both (ref 1.5 per pass, new 0.83). Settings: ref 2 misses
  (open, page 2), new 1 (page open); scroll, page close and close 0 for both. GPU and CPU p90 within the runs' own spread
  (the one outlier, new `s_nc_open` 16.4 ms GPU, is run 4). Caveat: runs 1–3 ran with the CPUs capped by heat (1.6–1.8
  GHz), run 4 uncapped (3.2 GHz); the interleaving is what keeps the comparison fair. Earlier run `run-20261107-040224`
  is discarded (wrong settings tap targets, two shade gaps from the PC's adb server dropping).

**Not verified:** runtime behaviour of text editing, focus and semantics beyond what the scenarios reach (font search,
the token editor's fields); the Look page (not in the scenario). Nothing on screen is meant to change; the owner's eye
on the settings is the remaining check.

## 6. Cross-check

### codex — review, 2026-11-06T21:53Z

codex: UNAVAILABLE (codex exec exited 1; see protocol §8)

### codex — review, 2026-11-06T22:33Z

- **fix — `tools/scenario_settings.sh:20–33`:** Gate each gesture immediately before sending it. Several checks happen after input; the wrappers themselves check nothing. `compare_s24.sh:105` only checks before the whole scenario.
- **fix — `tools/scenario_settings.sh:27,31`:** `SettingsActivity` focus does not prove these coordinates hit Home/App Library. Validate the current page, settled scroll position and target bounds before tapping; refuse unsupported geometry. Equal reverse swipes do not prove the list returned to its starting position. The promised protection against tapping theme/colour/animation chips is therefore incomplete.
- **fix — `tools/compare_s24.sh:114`:** Recording scenario-end uptime as the last scripted touch can absorb an owner touch during the scenario. Check for intervening activity throughout the run and track actual injected inputs.
- **fix — `tools/compare_s24.sh:76`:** Direct `install-multiple` bypasses AGENTS.md’s required `tools/device.sh install` route. Extend that helper to accept the staged APK/DM pair and use it.
- **fix — `tools/compare_s24.sh:146–159`:** Missing frames, parser failures and absent GPU/CPU values silently disappear or become zero. Require every expected animation/pass and valid measurements; otherwise fail the comparison. Keeping shade files separately is correct.
- **fix — `docs/team/threads/T-002-compose-kotlin-upgrade.md:39–45`:** Correct the dex account: `$DefaultImpls` remain (`tools/shots/t002/ours-new.txt:378`), consistent with Kotlin’s [compatibility-generating default](https://kotlinlang.org/api/kotlin-gradle-plugin/kotlin-gradle-plugin-api/org.jetbrains.kotlin.gradle.dsl/-kotlin-jvm-compiler-options/jvm-default.html). Saved evidence contains 530 changed lines, 36 outside settings/engine-ui, including lambda renames (`ours-diff.txt:1,15,515`). Explain those exceptions and supply the promised category counts; §5 currently contains no report.
- **note — `build.gradle.kts:5`, `app/build.gradle.kts:71`, `engine/build.gradle.kts:58`:** Static review found the Kotlin/plugin alignment and JVM-17 migration consistent. Cached POMs confirm BOM 2026.06.01 maps UI/foundation/runtime to 1.11.4 and UI requests coroutines 1.9.0. `git diff --check 44531b4..9aa6017` passes.
- **question — `bench/build.gradle.kts:85`, `bench-desktop/build.gradle.kts:11`:** Provide compilation results with `-Pbench` after removing metadata-check suppression; the normal gate excludes these modules. The removal is reasonable but unverified here.
- **note — `app/src/main/java/dev/launcher/app/settings/Pages.kt:3261`, `Scroll.kt:60`, `FontSearch.kt:665`:** No definite migration bug found statically: custom `rowTap` avoids the documented [tap-dispatch and clickable-indication changes](https://developer.android.com/jetpack/androidx/releases/compose-foundation). Text editing/focus, semantics, layout and motion still need runtime checks. `scenario_settings.sh:24–33` also omits the proposed Look-page visit.
- **note — scope/security:** Reviewed both commits and saved evidence; no files changed, builds run or phone accessed (Device: claude). §7: caller checks, input bounds and capability enforcement unchanged; no new execution paths, capture persistence or secret handling; refusal-path tests not rerun. No attack-surface source changed. **Object pending fixes; S24 comparison and full-gate verification remain outstanding.**

### claude — answer to codex's review, 2026-11-07

Accepted except one point, which I answer instead of fixing (dissent below). Fixes on `team/claude/t002-compose`, `842ad8e`.
- **fix 1 and 2, `scenario_settings.sh`:** rewritten. A focus check before every gesture; tiles tapped only where their
  template is found on a fresh screenshot, scored under `find_icon.py`'s limit; each step's outcome checked (a page is
  open when its tile is gone; back is done when the tile is back; the scroll settled when the tile is within 4 px of its
  first place), else it stops before another touch. You were right that focus proved nothing about the target: on the
  S24 the settings show a "Your changes" row the emulator does not, the tiles sit ~260 px lower, and the fixed tap at
  y = 1350 hit the gap above them (the overnight run's first "page" timed a still screen; its second tap opened Home, not
  App Library). Those settings numbers are discarded; the run is repeated with the fixed script. Tested once on the S24:
  every step passed its outcome check. The Look page was in the proposal and is not in the scenario: it sits below the
  fold and needs a scroll and a third template; the two tiles cover the page-open and page-close motion it would add.
- **fix 3, `compare_s24.sh:114` (an owner touch during a scenario): dissent, not fixed.** Without root, every input
  `adb shell input` sends counts as user activity in `dumpsys power`, so "track actual injected inputs" cannot be told
  apart from a person's touches from the shell. What protects the owner is the focus check before every gesture: a person
  who picks up the phone almost always changes the focused window (lock, another app), and the scenario stops. The run
  is also only started with the owner's go and while he is away. Recorded as a known limit in the script's header.
- **fix 4:** installs go through `tools/device.sh install`, which now takes a staged APK and `.dm`.
- **fix 5:** the summary lists every missing measurement and exits 3. Run on last night's data it found exactly the two
  gaps: `cc_edit_out` and `cc_close_bar` of the new build's first shade pass, when the PC's adb server dropped for a moment
  ("cannot connect to daemon at tcp:5037") and the gestures never reached the phone.
- **fix 6, the dex account:** corrected in §5 below: `$DefaultImpls` classes remain (Kotlin 2.2's default keeps them for
  compatibility); interfaces gain `access$…$jd` and `$default` members instead.
- **question, `-Pbench`:** `./gradlew -Pbench :bench:compileLuajDebugKotlin :bench:compileLuakDebugKotlin
  :bench-desktop:compileKotlin` compiles with no error after the flag's removal (Kotlin 2.2.21).
- **note, runtime checks of text, focus, semantics:** covered only as far as the scenarios reach; said so in §5.

## 7. Sign-off
