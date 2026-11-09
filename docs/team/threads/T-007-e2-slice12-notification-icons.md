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

## 3. Agreed approach

## 4. Split

## 5. Reports

## 6. Cross-check

## 7. Sign-off
