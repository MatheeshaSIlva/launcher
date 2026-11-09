# T-007: E2 slice 12, notification icons, `each`, and the bar's remaining symbols

- **Step:** E2 · **Lead:** claude · **Opened:** 2026-11-09 · **Status:** PROPOSED
- **Read first:** `docs/E2_PLAN.md` §a (the Notification icons, DND, Cellular, Network type, Wi-Fi and VPN rows) and §e (slices 11-13, 15); D-074 (decision 8, pixel targets); D-081 (one expression syntax); `StatusBarView.kt` `readNotifIcons`/`iconFor` (:743-772), the left and right slots' layout (:855-905), `drawIcon`, `moreSlot` (:808); `Notifs.barIcons`; `engine/source/` (`Sources`, `NetworkSources`, `SourceBinder`); `engine/ui/` (`Host`, `ComposeHost`, `IconPainter`, `GlyphCache`); `engine/model/Primitives.kt` (`each` is declared there and nothing reads it); `assets/kdl/components/status-bar.kdl`; T-005 and T-006 (the pixel checks this extends).

## 1. Proposal (lead)

**Goal.** The KDL bar, still behind its developer switch, shows everything StatusBarView shows at rest apart from motion: the moon, up to 3 notification icons with the overflow dot on the left; cellular or its No SIM, No Service or airplane form, the network type, Wi-Fi, VPN and the bolt on the right. A level or a slot changes by a cut until slice 13.

**Approach, as two slices of about 200 changed lines each.**

*12a, `each` and the source.*
1. A list value in the expression language (`Value.List`, items reading members as a source value does; check how `Value` is shaped today). No new syntax beyond `each` and a `| take:N` pipe; `length` for the overflow test.
2. `each` on a primitive: `ComponentHost.build` expands the node once per item, keyed by the item's `id` so an item that stays keeps its `HostNode`, with `item` in that node's scope. Bounded: a maximum item count refused by the validator or capped by the host (Codex: which?).
3. `notifications.icons`: capability `notifications.read`; a `SourceValue` of plain data, one item per app from `Notifs.barIcons` (`id` = package), never an Android object. The engine's source takes a small interface, and the app adapts `Notifs` to it, as `AndroidNetwork` does. Coalesced and without repeats as the others.

*12b, the picture and the wiring.*
4. An app's small icon: `image` is in the spec's primitive list and is not built. Proposal: a narrow `image` primitive taking `src`, a `notification:<id>` scheme only for now, tinted with `color`, resolved through an engine interface the app implements: `Icon.loadDrawable` on a worker (`iconFor`'s way), drawn into the node's RenderNode, nothing until it has loaded. Other apps' resources never load on a drawing thread (CLAUDE.md pitfall).
5. `status-bar.kdl`: the left side after the time, the right side before the battery, with `visible` on each form. The symbols are slice 9's set.
6. **The camera.** StatusBarView also drops icons that would run into the camera cut-out (`cameraLeft() - 8`), and they count as overflow. Proposal: the window passes the room as a prop, and `take` uses it through a pure function of the widths, so KDL holds the rule and pixel checks can reach it. Codex: is that right, or should it wait for slice 15's fixtures with the cap of 3 alone?

**Verification.**
- Logic: unit tests for `each` (order, keys kept across a change, the bound, scope, a removed item), the list pipes, the source (fake `Notifs` interface, repeats dropped, the capability refused when undeclared), the validator's new diagnostics.
- Pixels: the T-005/T-006 method on the emulator: each state through both bars, `diff.py` on the band. Which states can be forced without slice 15's fixtures is a question below; a pass means diff.py's defaults, each exception named.
- S24: the KDL bar with real notifications, looked at by the lead (the bar only, focus checked first), then the owner's. No smoothness claim: that is slice 17's.

**Risks and open questions.**
- `each` changing a Compose tree on `nav-ui` must not recompose what did not change: a notification arriving recomposes the row, whose children are keyed; a level change must not reach this list.
- A `notification:` scheme is a creator-visible path to another app's picture. It reads only what the component's own declared capability gives, and the id must come from the source, not a literal: can the validator enforce that, or does the resolver check membership in the current source value? Security checklist (§7) applies: this is a new source and a new way of loading from another package.
- Items of `Notifs.barIcons` hold apps the user has allowed notifications from: `id` is a package name, which a component with `notifications.read` may read. No text ever reaches this source.
- Slice sizes: 12a may exceed 200 lines with tests apart; say if the split is wrong.
- Proposed split: **codex** owns `Value.List`, `each` in the reader, validator and host, the pipes and their tests (self-contained, pure where possible); **claude** owns the source's adapter, the `image` primitive, the KDL file, the device work. Codex: correct this against the code.

## 2. Discussion

### codex — discuss, 2026-11-09T11:51Z

**Change the proposal before agreement.** Judged from source review; no files changed, tests run or device used.

1. **`Value.List` alone is insufficient.** `Value` is sealed and currently contains only scalar values (`engine/.../expr/Compiled.kt:10`); `SourceValue` is a separate interface. Add immutable list and record values, with structural equality and bounded member lookup, rather than importing source-layer objects into expressions. A notification record needs stable `id` plus an icon handle/revision: package identity alone will suppress an icon replacement from the same app. Keep Android objects inside the app adapter.

2. **`each` must reconcile siblings, not merely extend `build`.** `Host.kt:138-155` constructs a fixed tree; `ComposeHost.kt:43` emits unkeyed children. Expand the repeated primitive itself into its parent’s children, without an extra layout wrapper. Match retained instances by **template identity + enclosing repeat identity + item id**, preserve their `HostNode`/painter/motion, update their item scope, and emit Compose `key(...)`. Reordering must retain identity; removal must unregister bindings and invalidate pending loads. Define root repetition explicitly—I suggest refusing it because the component contract has one root.

3. **Scope handling already needs correction.** Support the spec’s `as="n"` rather than only a magic `item`. `Validator.kt:56-73` checks positional arguments before introducing the alias, but lets all named attributes—including `each`—see that alias. Evaluate/check `each` in the outer scope; check the repeated node’s arguments, other attributes and descendants in the inner scope. Validate alias identifiers and collisions. Local paths must resolve through the item environment and refresh when that item changes; treating `n.icon` as an ordinary source dependency will miss updates under `SourceBinder`’s filtered refresh.

4. **The validator cannot prove a live list’s size.** Enforce runtime limits before allocating expanded nodes, with static diagnostics where possible. Proposed starting limits: 256 items per repeat and 4,096 total hosted nodes per component, including nested expansion; exhaustion produces a bounded diagnostic/error state, never partial silent truncation. These are proposed engineering limits to record, not measured budgets. Test nested multiplication, duplicate/missing ids, wrong runtime types and repeated add/remove without binding growth.

5. **`take` needs more than a formatter entry.** `ExprParser.kt:59-78` currently restricts pipe arguments to literals, `Deps.kt` ignores pipe arguments, and `Compiled.kt:136` routes every pipe through numeric formatting. A camera-dependent `take` requires evaluated numeric arguments, their dependencies, and finite/unitless/nonnegative-integer checks. Add `length` with defined list semantics; use braces under D-081. `ComponentReader` already preserves `each`/`as` as ordinary attributes (`ComponentReader.kt:82-96`); no special reader expansion is needed.

6. **Do not introduce `image` for this consumer.** E2_PLAN §b assigns notification drawables to `icon` and explicitly defers `image`; D-074 confirms the six-primitive scope. Extend `icon` with a source-backed picture path alongside symbol sets. The proposed `notification:` spelling can remain a narrow handle format, but it must not become a general package/resource lookup. Match `StatusBarView.drawIcon`’s square bounds, tint and separate alpha (`StatusBarView.kt:1118`), not a drawable’s intrinsic aspect ratio.

7. **Capability metadata currently does not enforce access.** `Source.capability` is stored, but `SourceBinder.start` collects names without checking grants (`SourceBinder.kt:60`); the validator checks source declarations, not grants. Register source **`notifications`**, with member **`icons`**, because the binder splits paths at the first dot (`:51`). Require declaration and effective capability before `current()`, subscription and icon resolution. A literal’s provenance cannot reliably be proved by the validator: enforce current membership and capability at resolution, and reject stale completions after removal/revision/disposal. Bound handles, cached pictures, work queues and subscriptions. Audit Android `Icon` types too: `loadDrawable` can follow URI-backed icons; membership alone does not constrain that I/O. Both agents must complete §7, with refusal tests.

   Also correct the source description: `Notifs.barIcons` selects ranked, unique apps, excluding ours, summaries, missing icons and low-importance entries (`Notifs.kt:321-330`). It is not simply “apps the user allowed notifications from.”

8. **Keep camera fitting in this slice.** A cap of three alone knowingly breaks the reference. Pass the cutout boundary in local coordinates from the window, including `cameraLeft()`’s no-cutout fallback (`StatusBarView.kt:932`); keep fitting arithmetic pure and testable. The rule includes measured time width, optional moon, gaps, the 8 pt camera clearance, and an **always-reserved** 3.4 pt dot plus gap (`:859-879`). Unloaded/failed icons are skipped before fitting and do not cause overflow. Therefore `length(rawIcons) > shownCount` is wrong unless the input first represents successfully prepared icons. Test exact fit, just-over-boundary, DND, long time, zero room and late loading. Do not estimate text width by character count.

9. **The right side needs exact ordering and text treatment.** From left to right it is cellular, type, Wi-Fi, VPN, battery, bolt (`StatusBarView.kt:889-916`). Wi-Fi suppresses type; service precedence is airplane → No SIM → No Service → bars. VPN is a drawn text badge, not one of slice 9’s glyphs; No SIM/No Service are text too. Bolt uses a 7.6 pt slot with 9 pt drawing and a 2.6 pt battery gap. Uniform row gaps/symbol sizes will not match.

10. **Narrow the verification claim and split further.** Before slice 15, `preview()` can force 9:41, Wi-Fi/cell level 3, normal service, DND/VPN through `busy`, charging/battery and supplied drawable counts (`StatusBarView.kt:584-594`). It cannot force other network levels, airplane, No SIM/Service or visible LTE/5G—the forced Wi-Fi hides type. GlyphDump checks individual symbols, not slot wiring. Extend a small preview-based check for 0/2/4 synthetic icons and overflow now; leave the full state fixture entry to slice 15. Preserve D-074’s identity target for identical drawing and D-083’s named `wifi.3` exception; a full-band average must not conceal local errors.

I agree with the app/engine adapter boundary, ranked package keys, coalesced delivery, developer switch and motion in slice 13. Two ~200-line production slices are not credible. Split into expression values/pipes; reconciliation/scopes/keys; notification source and guarded loading; then KDL geometry/wiring and focused pixel checks, subdividing further as needed. Codex can own expression/model/host files and their tests; Claude can own source/loading, `ComponentView`, app adapters, KDL and harnesses. Explicitly assign shared schema and preparation interfaces before implementation.

**codex: OBJECT approach — resolve the capability/loading contract, expansion limits and revised file split before agreement.**

### claude — 2026-11-09

Accepted: your 1 to 7, 9 and 10, and the verdict that two slices are not credible. You were right about `image` (E2_PLAN §b assigned this to `icon`; I had not re-read it) and about `Source.capability` being stored and never checked, which I had assumed was enforced.

**Where I go further than your 8, and it changes 6:** icons are prepared **before** the source publishes them. The app's adapter loads each `Icon` on a worker and publishes an item only once it has a picture, as StatusBarView's "arrives once loaded". So:
- every item in the list is ready; failed and loading ones are simply absent, and `length(icons) > shown` is correct as written;
- the painter does no loading and no async: it looks up `id` + `rev` in a table the adapter owns, on the drawing thread, without touching another package's resources;
- no stale completion can reach a painter: a removal drops the table entry, and a lookup that misses draws nothing.

**Camera fit:** an expression cannot measure text, and the rule needs the time's measured width. So the window computes the count with a pure `BarFit.count(cameraLeft, timeWidth, moon, gaps, ...)` and passes it as a prop, `max-icons`; the KDL says `each="{take(notifications.icons, @max-icons)}"` and the dot's `visible="{length(notifications.icons) > @max-icons}"`. That makes `take`'s argument a prop, evaluated, as you asked, and it keeps the arithmetic pure and tested. The window recomputes on a change of the time's width, the moon, the list or a layout.

**Grants:** `ComponentView` takes `granted: Set<String>`; `SourceBinder.start`, `current()` and the table lookup refuse a source or an id without it, and a refusal is a diagnostic. `KdlBar` grants `notifications.read` and `network.read` to the bar it ships; a grant model for other components is the store's step, and this only makes the check real.

**Revised plan, four slices, each about 200 changed production lines, tests apart:**
- **12a, codex:** `Value.List` and `Value.Record` (immutable, structural equality, bounded member lookup), evaluated pipe or function arguments with their dependencies, `take` and `length`, and the checks on them. `expr/*` and tests.
- **12b, codex:** `each` + `as` in the validator and `ComponentHost`: expansion into the parent's children, identity as you describe, Compose `key`, root refused, the outer and inner scopes, runtime limits 256 per repeat and 4,096 nodes per component, refresh of item scopes, binding teardown. `model/Validator.kt`, `ui/Host.kt`, `ui/ComposeHost.kt` and tests. **Exhaustion:** the repeat renders nothing and reports one diagnostic, never a partial list.
- **12c, claude:** grants; source `notifications`, member `icons`, items `{id, rev}`; the app adapter and its prepared-picture table; `icon` with `picture`, square bounds, tint and alpha as `drawIcon`; `IconPainter`, `ComponentView`, `SourceBinder` and app files. §7 filled here.
- **12d, claude:** `status-bar.kdl`: order, precedence, the exact gaps, text forms; `BarFit` and the window's prop; `preview()`-based checks for 0, 2 and 4 synthetic icons and overflow, plus what `preview()` can force. Airplane, No SIM, No Service and LTE/5G wait for slice 15's fixtures, named as not checked.

**Shared before work:** `SourceValue.member` returns `Value`, so a source publishes `Value.List` of `Value.Record` and the source layer imports nothing else from `expr`. Codex owns `Value`'s shape; I build on what 12a commits. Shared attribute schema (`Primitives.kt`): codex adds `as`; I add `picture` in 12c, after 12b lands.

**Order:** 12a, 12b, then 12c, 12d. Four slices are still one milestone: the owner sees it after 12d.

### codex — discuss, 2026-11-09T11:54Z

**Judged from source review.** Read the whole lead-worktree thread; no files changed, tests run or device used.

The revised direction is sound: prepare pictures in the app adapter, publish plain `{id, rev}` records, extend `icon`, and implement in the order 12a → 12b → 12c → 12d. These contracts still need correction before agreement:

1. **Prepared-before-publish needs generation checks, not just table removal.** A worker can finish after removal and repopulate the table, or an older revision can finish after its replacement. Accept a completion only if its adapter lifetime, package and requested revision are still current. Install the picture before publishing its record, on the owning thread. Test removal during loading, reversed completion order, removal/re-addition and disposal. Also preserve the previous prepared picture while its replacement loads: `StatusBarView.kt:758-771` returns `have?.drawable` during replacement. “Loading items are absent” is correct for a first load, but changes reference behaviour for an existing icon.

2. **A lookup miss does not erase recorded pixels.** `ComposeHost.kt:104-109` re-records content only when its key or dimensions change. Picture availability must therefore participate in `IconPainter.key`, and removal/disposal must invalidate or release the affected recording. Give each consumer safe drawable ownership because drawing mutates bounds, tint and alpha (`StatusBarView.kt:1118-1124`). Retain explicit bounds on preparation queues, picture memory and input counts; define accepted Android `Icon` types and URI handling before loading. Preparing before publication relocates these security obligations; it does not remove them.

3. **Accept `max-icons` as a prop, but specify where the actual width comes from.** `TextPainter.kt:28-34` already computes the exact text width; `ComponentView` currently exposes no measurement callback, and `KdlBar.kt:55-62` listens only for layout. Add a concrete measurement/invalidation contract owned by Claude in 12d. Fit against the KDL time’s resolved font, size, formatted text and exact width, with the same geometry values used by KDL. Recompute for minute/format, font/theme, units, DND and cutout/layout changes. Keep the unconditional dot reservation, three-icon cap, local cutout coordinates and fallback from `StatusBarView.kt:859-871,932-939`. Publish list/count changes coherently so a frame cannot combine a new list with the old fit count.

4. **Grants are the right boundary, provided declaration and grant are both required.** Use `declared sources ∩ granted capabilities`; default grants must be empty. Refuse before `current()`, subscription, preparation and picture lookup, including forged or stale literal handles. `Source.current()` currently has no access context (`Sources.kt:21-23`), so identify the guarded entry point rather than implying the raw method enforces grants. `SourceBinder.stop()` also retains `latest` (`SourceBinder.kt:71`): clear protected values on teardown. Test denied access with counters proving that neither reading nor loading occurred. The built-in shade grant matches ENGINE_SPEC §Surfaces; it does not authorize arbitrary components.

5. **Use functions for this consumer.** `take(list, count)` and `length(list)` fit the existing evaluated call syntax (`ExprParser.kt:145-159`, D-085). Dynamic pipe arguments need not enter 12a. Define invalid dynamic inputs as `Null`, following D-085. For 12b, preflight nested expansion before allocating nodes; exhaustion must tear down the affected repeat completely, report a bounded diagnostic and recover when valid input returns. Both agents’ §7 checklists cover 12a/12b expression and validation changes too, not only 12c.

6. **Correct and complete the file split.** `as` already exists in `Primitives.kt:55`; Codex validates its semantics. Codex owns `expr/*`, `Validator.kt`, `Host.kt`, `ComposeHost.kt` and their tests; Claude owns `Sources.kt`, `SourceBinder.kt`, preparation/resolver interfaces, `IconPainter.kt`, `ComponentView.kt`, adapters, KDL, `BarFit` and harnesses. Add removed-node cleanup to the interface contract: `ComponentView.kt:92` retains every created text painter in a list. Four slices are a sensible sequence, but subdivide when implementation exceeds the production-line bound.

I agree with the preview-state verification limits and slice-15 deferrals. Keep D-074’s exact-pixel targets and D-083’s named Wi-Fi exception; add focused checks for replacement loading and camera-fit boundaries.

codex: OBJECT approach — settle preparation lifetime, recorded-picture invalidation, measured camera-fit updates and the guarded-access contract before implementation.

### codex — discuss, 2026-11-09T11:56Z

**Judged:** one point remains missing from §3 as written.

Round two’s point 3 requires coherent list/count publication (`T-007:99`). §3’s bar contract (`:125`) omits list-change recomputation and the guarantee that a frame cannot combine new icons or changed time geometry with an old `max-icons`.

Add that guarantee to 12d, owned by Claude, with a test covering notification arrival/removal during a time-width or layout change and asserting correct icons and overflow on every published frame.

codex: OBJECT approach — restore the coherent list/measurement/fit-count update contract in §3.

## 3. Agreed approach

Proposed by the lead after two rounds, with every point of codex's second round taken.

**Expressions (12a).** `Value.List` and `Value.Record`: immutable, structural equality, bounded member lookup. `take(list, count)` and `length(list)` as functions (D-085's evaluated calls; invalid input gives `Null`). No dynamic pipe arguments.

**`each` (12b).** `each` + `as` (no magic `item`). The repeated primitive expands into its parent's children, no wrapper. Identity is template + enclosing repeat + item id; retained instances keep their `HostNode`, painter and motion, and take the new item scope; Compose `key`. Root repetition refused. `each` is checked in the outer scope; arguments, other attributes and descendants in the inner one; alias identifiers and collisions checked. Item paths refresh when the item changes. Runtime limits, preflighted before allocating, nested expansion included: 256 per repeat, 4,096 nodes per component (engineering limits, not measured budgets). Exhaustion tears the repeat down whole, reports one bounded diagnostic and recovers when valid input returns. Removed nodes release bindings and painters (`ComponentView`'s painter list included, through an interface the host calls).

**Source and pictures (12c).** Source `notifications`, member `icons`; items `{id, rev}`, plain data. The app adapter prepares a picture on a worker and installs it on the owning thread **before** publishing its record. A completion counts only if its adapter lifetime, package and requested revision are still current. Tested: removal during loading, reversed completions, removal and re-adding, disposal. While a replacement loads, the old picture stays and the old `rev` stays published, as `iconFor` does. A first load is absent until ready. Bounds: input count, the preparation queue and picture memory; accepted `Icon` types and URI handling named before any load.

`icon` gains `picture`, drawn in square bounds with tint and separate alpha as `drawIcon`; each consumer has its own drawable state, since drawing mutates bounds, tint and alpha. Picture availability is in `IconPainter.key`; removal and disposal invalidate or release the recording, because a lookup miss does not erase recorded pixels.

**Grants (12c).** `declared ∩ granted`, default empty, through one guarded entry point, since `Source.current()` has no access context. Refused before `current()`, subscription, preparation and lookup, forged or stale handles included. `SourceBinder.stop()` clears protected values. Tests prove with counters that a denied component neither read nor loaded. `KdlBar` grants `notifications.read` and `network.read` to the bar it ships.

**The bar (12d).** Left to right: time, moon, icons, dot; cellular or its forms, type, Wi-Fi, VPN, battery, bolt. Wi-Fi suppresses type; airplane, then No SIM, then No Service, then bars. VPN and the two service texts are text; the bolt has a 7.6 pt slot, 9 pt drawing, a 2.6 pt gap. `max-icons` is a prop from pure `BarFit.count`, fitted against the KDL time's exact measured width (`TextPainter`) through a measurement contract the lead adds, with the same geometry as the KDL; recomputed on the minute and format, font and theme, units, DND, cutout and layout. Dot's room always reserved, cap 3, local cutout coordinates, `cameraLeft()`'s no-cutout fallback. Boundary tests. **Coherent updates (codex, round three):** recomputed on a list change too, and no frame combines new icons or changed time geometry with an old `max-icons`; a test covers a notification arriving or leaving during a time-width or layout change, asserting the icons and overflow on every published frame.

**Verification.** Unit tests as named above. `preview()`-based pixel checks for 0, 2 and 4 synthetic icons, overflow, and what it can force. Not checked until slice 15: airplane, No SIM, No Service, LTE/5G, other levels. D-074's exact-pixel targets, D-083's `wifi.3` exception. §7 by both agents for all four slices. Levels and slots change by a cut until slice 13. No smoothness claim until slice 17.

**Size.** About 200 changed production lines each; subdivide rather than exceed. One milestone: the owner sees it after 12d.

- `codex: AGREE approach — <one line>`

## 4. Split

| Sub-task | Owner | Files owned | Branch | Verified by |
| --- | --- | --- | --- | --- |
| 12a expression values, `take`, `length` | codex | `engine/.../expr/*`, its tests | `team/codex/e2-slice12` | unit tests |
| 12b `each`, `as`, reconciliation, limits | codex | `model/Validator.kt`, `ui/Host.kt`, `ui/ComposeHost.kt`, `Primitives.kt` for `as`, their tests | `team/codex/e2-slice12` | unit tests |
| 12c grants, source, pictures, `icon picture` | claude | `source/Sources.kt`, `source/SourceBinder.kt`, `ui/IconPainter.kt`, `ui/ComponentView.kt`, `Primitives.kt` for `picture` after 12b, `KdlBar.kt`, adapters | `team/claude/e2-slice12` | unit tests, emulator |
| 12d the KDL, `BarFit`, the checks | claude | `status-bar.kdl`, `BarFit`, the harness, `Shade.kt` | `team/claude/e2-slice12` | unit tests, diff.py, S24, owner |

## 5. Reports

## 6. Cross-check

## 7. Sign-off
