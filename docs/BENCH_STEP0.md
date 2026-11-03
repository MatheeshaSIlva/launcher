# Step 0: the measurements before step 2

The engine spec's open questions that D-067 says are "settled by measurement before step 2": which Lua VM, whether
to write our own KDL parser, whether the glass shaders work in Compose Multiplatform on the desktop, and whether a
Compose `deck` can render on gesture nav's thread. Measured 2026-11-03/04 on the S24 (SM-S921B, One UI 9, Android 17,
AP 37-45 °C, not throttled) and on the Windows dev machine (HotSpot 17). **Not on the Redmi**: it was not connected; the
spec asks for the Lua numbers there, so those runs are still owed.

How to run it again: `./gradlew -Pbench :bench:assembleLuajDebug :bench:assembleLuakDebug`, `adb install -r` both APKs,
`DEVICE=<serial> tools/bench/run_phone.sh [lua lua-luak kdl glass deck]` (results in `tools/shots/bench/`), then
`./gradlew -Pbench :bench-desktop:run --args="glass tools/shots/bench/glass"` (and `lua`, `kdl`). The shaders are the
app's own, resolved by `tools/bench/extract_shaders.py`. The modules exist only with `-Pbench`; CI, `scripts/check.sh` and
the launcher's APK never see them or their libraries (owner's go for bench-only libraries, 2026-11-03).

## 1. Lua VM: LuaJ 3.0.1

Same workload on each VM: start a VM with the standard libraries and the sandbox, compile a 331-line script, recursion,
tables, strings, and an event's delivery (a table built on the host side, then a call), median of 9-15 runs after warm-up.

| S24, ART | LuaJ 3.0.1 | Luak 4.0.0-alpha-2 | Rembulan 1.0.3 |
| --- | --- | --- | --- |
| first VM in a process | 13.0 ms | 22.9 ms | **fails**: "can't load this type of class file" |
| open (stdlib + sandbox + script) | 1.79 ms | 2.00 ms | |
| compile 331 lines | 1.97 ms | 1.20 ms | |
| fib(24), ~75k calls | 10.2 ms | 11.5 ms | |
| 2000 tables built and sorted | 5.5 ms | 5.7 ms | |
| 5000 `string.format` + concat | 6.6 ms | 4.6 ms | |
| one event delivered | 0.82 µs | 0.86 µs | |
| heap per VM | ~65 KB | ~67 KB | |
| sandbox holds | yes | yes | |

- **Rembulan cannot run on Android**: it compiles Lua into JVM bytecode and defines the classes at run time, which ART
  refuses. On HotSpot it works but is slower than LuaJ anyway (open 32 ms against 1.2, fib 24 ms against 11). Out.
- **LuaJ and Luak measure the same**: Luak is korlibs' Kotlin port of LuaJ (same packages, same API, so they cannot share an
  APK). LuaJ is the released, proven one; Luak is an alpha. The engine keeps one small VM interface (`LuaVm` in the bench
  is the shape), so a later switch (to Luak, or to Cobalt, the maintained LuaJ fork, not measured here) costs one adapter.
- The sandbox the spec names (no `load`, `loadstring`, `dofile`, `loadfile`, `require`, `package`, `os`, `io`, `debug`,
  the Java bridge, `string.dump`) is a few lines on either and holds against the obvious escapes; the written escape suite
  is step 6's.
- An event costs under a microsecond, so a `pull` at 120 Hz is ~0.01 % of a frame: the handlers' own work is the budget,
  as the spec already says (8 ms logged, 50 ms killed).

## 2. KDL: write our own parser

kdl4j 1.0.1 (the JVM library; JitPack only, Java 17) on the same files:

| | S24, ART | desktop |
| --- | --- | --- |
| first parse in a process | 4.5 ms | 49 ms |
| status bar component, 99 lines | 0.52 ms | 1.4 ms |
| whole-phone layout, 4644 lines, 3202 nodes | 14.9 ms (11 MB/s) | 24 ms |

Fast enough. It loses on what the spec wants a parser for (precise errors):
- **No positions on parsed nodes** (`KdlNode` is name, type, arguments, properties, children). Syntax errors carry a line
  and column, but the engine's own checks (an unknown prop, a missing `uses`, a type error) could not say where.
- **The hybrid parser silently returns garbage**: the spec's example as printed (invalid, below) came back as one node,
  `color`, with no error. The strict v1 and v2 parsers reject it.
- **The v2 parser accepts a stray `}`** (`box` then `}` on the next line) and drops it.
- Its syntax errors are otherwise good and say line and column (ten broken files, `bench-shared/kdl`): "Missing closing
  brace at the end of children list at 3:1", "Invalid escaped character 'q' at 1:9". That wording is the bar for ours.

Recommendation: our own KDL 2 parser (the grammar is small), every node and value carrying its span, checked against the
official test suite (kdl-org/kdl's `tests/`), with kdl4j's messages as the floor.

**The spec's own example is not valid KDL** (`battery-ring` under "Component model"): `script lang="lua" { on(...) }`
puts Lua where KDL expects nodes, and KDL 2 writes booleans `#true`/`#false` (`charging false` is refused). The fix is
`script lang=lua #"""` ... `"""#` and `#false`; `KdlBench.SPEC_EXAMPLE_V2` is the corrected text. A spec change, so the
owner's.

## 3. Glass on the desktop: yes

All six of the launcher's shaders (`material`, 365 lines, with its fills, shadows, rims, lens, dispersion and the reveal;
`edge-clamp`, `nc-glass-lens`, `front-cover`, `theme-reveal`, `wallpaper-transition`) compile **unchanged** in Skiko
0.150.2, the Skia that Compose Multiplatform 1.12.2 draws with. `RenderEffect`'s parts exist there:
`ImageFilter.makeRuntimeShader` (a shader over a node's content) and `ImageFilter.makeBlur` (with Android's radius turned
into a sigma as hwui does, 0.57735 r + 0.5).

| compile | S24 | desktop |
| --- | --- | --- |
| material (365 lines) | 1.29 ms | 1.44 ms |
| the five others | 0.02-0.29 ms | 0.02-0.33 ms |

Five cases drawn on both, the same uniforms over the same generated pictures, compared pixel by pixel (premultiplied
8-bit):

| case | max difference | pixels off by more than 2 | PSNR |
| --- | --- | --- | --- |
| glass (lens, dispersion, 3 fills, shadows, stroke) | 23/255 | 0.022 % | 64.3 dB |
| reveal mid-way (noise front, old and new pictures) | 50/255 | 0.019 % | 64.1 dB |
| pressed, no corner smoothing | 1/255 | 0 | 65.3 dB |
| as an effect over blurred content (blur, then the shader) | 3/255 | 0.004 % | 55.6 dB |
| edge clamp | 1/255 | 0 | 55.6 dB |

The larger differences are 4-6 single pixels on the shape's anti-aliased edge, where the lens sits on a threshold and the
phone's half precision tips it the other way; nothing visible. Not covered: the desktop side was Skia's CPU raster, not
the GPU backend a Compose Desktop window uses (Direct3D on Windows, Metal on macOS); the editor's GPU path is still worth
one look when it exists. On the phone each case drew in ~2.4 ms including a renderer made for it.

## 4. A Compose deck on gesture nav's thread

The spike (`bench/.../DeckSpike.kt`): eight cards in a `SurfaceControlViewHost` made on a `HandlerThread` at
`THREAD_PRIORITY_DISPLAY`, as `switcher/SwitcherHost` does, drawn three ways: `view` (card Views moved by their
properties, the launcher's way), `compose` (composables moved in `graphicsLayer`, the motion read in drawing only),
`nodes` (Compose holding one draw node that draws engine-owned RenderNodes moved directly: the spec's "Compose owns the
tree, the engine owns the pixels"). Each: built and shown cold and warm, shown from a warm hidden spare, and 2.4 s of
scrolling driven by the nav thread's Choreographer, once with the main thread idle and once blocked 50 ms in every 150.

**Compose 1.7.5 (the launcher's) cannot do it while any accessibility service is on, and ours always is.** Its
accessibility delegate posts a semantics check to a handler made on the main looper
(`AndroidComposeViewAccessibilityDelegateCompat`: `Handler(Looper.getMainLooper())`), and that check measures the view
(`view.measureAndLayout()`) on the main thread while the nav thread draws it: "Detected multithreaded access to
SnapshotStateObserver" on one thread or the other, every run (S24, twice). `clearAndSetSemantics` does not stop it.
Compose's own comment there: "access from bg thread [is] not supported by semantics (b/298159434)".

**Compose 1.11.4 is the first release with the fix**, on by default (`AndroidComposeUiFlags.isViewBasedSemanticsHandlerEnabled`:
the view's own handler), and it still builds on AGP 8.7.3 and compile SDK 35 (1.12 needs AGP 9.1 and SDK 37). 1.12.1 also
runs the check only under touch exploration (TalkBack), not for any service. The bench uses 1.11.4; the launcher keeps
1.7.5.

On 1.11.4 all three ran with no crash (S24, 2026-11-04):

| | view | compose | nodes |
| --- | --- | --- | --- |
| build, first in process | 0.9 ms | 12.5 ms | 2.1 ms |
| build + host + first frame, first in process | 42 ms | **151 ms** (236 on 1.7.5) | 54 ms |
| the same, warm (median of 5) | 45 ms | 44 ms | 47 ms |
| warm hidden spare made visible, to its first frame | 31 ms | 30 ms | 26 ms |
| scroll, main idle: vsyncs skipped / vsync-to-commit p50, p99 | 0 / 13.9, 17.1 ms | 0 / 11.6, 16.3 ms | 0 / 3.1, 7.3 ms |
| scroll, main blocked 50 ms in 150: skipped / p50, p99, max | 0 / 2.9, 4.9, 5.6 ms | 0 / 4.1, 7.8, 13.5 ms | 0 / 3.1, 4.2, 6.8 ms |

How to read it:
- **The nav thread never skipped a vsync** in any mode, and a blocked main thread did not touch any of them. One
  condition: a state written on the nav thread must be followed by `Snapshot.sendApplyNotifications()` there, or the change
  waits for the main thread (`GlobalSnapshotManager` sends it from `AndroidUiDispatcher.Main`).
- **Per frame, Compose costs about a millisecond more than the View deck** and has the longer tail (p99 7.8 against 4.9,
  max 13.5); `nodes` is the View deck's equal. The two "main idle" runs of `view` and `compose` came first after the show
  tests and read ~11 ms higher for all their frames: the CPUs still at a low clock (the busy runs keep them up), not the
  deck. Compare modes within one row.
- **The first frame is the cost the spec feared**: the first composition in a process took 151 ms against 42. Warm it is
  the same as the View deck. So a Compose deck needs its spare composition made and drawn once while nothing moves, as
  `SwitcherHost` already keeps a spare host.
- Showing a hidden spare took 26-31 ms in every mode: that is the host's relayout and first buffers (the switcher's
  pitfall: 4 ms relayout, 11 ms first frame), the same for all three, not Compose's.
- Measured: the nav thread's vsyncs and when each frame was committed (`registerFrameCommitCallback`). Not measured: what
  the screen showed (SurfaceFlinger's present times); the ≤1 dropped per 100 bound is step 5's check with framestats.

## What this settles, and what it does not

- Lua: LuaJ 3.0.1 behind a one-adapter interface. Rembulan is out.
- KDL: our own parser, spans on every node.
- Glass: the editor can run the launcher's shaders as they are.
- Deck: a Compose deck can render on the nav thread, with Compose 1.11.4 or later, its first composition warmed ahead,
  and its motion drawn as engine-owned RenderNodes (`nodes`: the View deck's frame cost; Compose layers cost ~1 ms more).
  It needs the launcher's Compose moved from 1.7.5 (the bench builds 1.11.4 on Kotlin 2.0.21 with
  `-Xskip-metadata-version-check`; a Kotlin upgrade is the clean way): the owner's call.
- Still owed: the Lua and deck numbers on the Redmi (the floor device), and the editor's GPU path.
- For the owner: the spec's example fix (above), and the Compose version the engine starts on (1.11.4 at least).
