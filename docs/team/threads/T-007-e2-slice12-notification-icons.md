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

## 3. Agreed approach

## 4. Split

## 5. Reports

## 6. Cross-check

## 7. Sign-off
