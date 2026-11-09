# Board

**Now:** direction changed (D-092): iOS and Pixel scrapped, a default theme of our own, settings are what creators offer. The owner leads the identity; the lead proposes concepts next, nothing built until chosen. T-007 (E2 slice 12) waits on `team/claude/e2-slice12`: gate passed, Codex agreed, owner's look open; then live pills are proposed (after slice 13, before T-001 slice 5; not yet agreed). T-001's slice 4 and the handover are merged, behind the developer switch (D-090, D-091). T-001 next: slice 5, the flip and removing Shizuku. E2 next: slice 12 (`notifications.icons`, `each`, overflow), which also wires the bar's Wi-Fi, cell, moon and bolt from slice 9 over slice 11's sources, not yet in the KDL.

**Step:** E2 — KDL loader and interpreter core (`docs/ENGINE_SPEC.md`, build order step 2; plan `docs/E2_PLAN.md`)
**Device:** claude (lead)
**Last milestone review:** none yet (the first is due at the end of E2)

## E2 progress

Plan: `docs/E2_PLAN.md` (19 slices; decisions on its questions in its last section and D-074). Done before the team
existed (D-070's mode: this session reviewing, an Opus agent building), each gated by `scripts/check.sh` and a declared
`tools/dexlist.sh` diff:

| Slice | What | Commits |
| --- | --- | --- |
| plan | `docs/E2_PLAN.md` with the decisions | `95dc445` |
| 1, 2 | KDL 2 parser, spans on everything; kdl-org's suite 338/338 | `5eaaa37`..`ad60c71` |
| 3 | component model, validator with reasons | `d500f3b`, `4f1c542` |
| 4 | `{expr}` expressions, compiled, with dependency sets | `f1be4ac`, `9891f12` |
| 5 | layout maths; the Compose host (Compose owns the tree, primitives own RenderNodes) | `163d703`, `289a4bf` |
| 7, 8, 11 | sources (time, battery, network, dnd) and the binder; tokens from the launcher's resolver | `aa44a81`, `af29b91`, `6ec3433` |
| P1 | the probes removed (LiveTransitionProbe, CaptureProbe, AIDL 17 and 43) | `d130011` |

0, 0a: AGP 8.13.2 (T-003) and Compose 1.11.4 + Kotlin 2.2 (T-002), both signed by both agents.

6: the first visible bar, behind a developer switch (T-004, `3c3cf56`..`e118b32`). 9, 10: T-005, T-006. Next, in order: 12-17. 115 engine unit
tests. The plan's owner questions are all answered: 5 and 6 on 2026-11-07 (D-081, D-082, the spec updated).

## Tasks

Status: PROPOSED · DISCUSSING · AGREED · IN PROGRESS · REPORTED · SIGNED · DONE · BLOCKED

| ID | Task | Thread | Owners | Branches | Status |
| --- | --- | --- | --- | --- | --- |
| T-007 | E2 slice 12: notification icons, `each`, the bar's remaining symbols | `threads/T-007-e2-slice12-notification-icons.md` | claude (lead), codex | `team/claude/e2-slice12` | PROPOSED |
| T-001 | Our own shell host replacing Shizuku, alongside E2; 1a by Codex now, 1b (native pairing, Android 12+) after T-002 | `threads/T-001-no-shizuku.md` | claude (lead), codex (1a, 1b) | `team/codex/t001-adb` | IN PROGRESS (1a, 1b, 2, 3, 4 DONE: pairing, start and coming back by itself on the phone, behind the developer switch; and the handover to the stock bar; next slice 5, the flip) |
| T-006 | E2 slice 10: the battery as `box` + fill + text with expressions; the first battery pixel harness | `threads/T-006-e2-slice10-battery.md` | claude (text knockout, KDL, harness), codex (box, layer, geometry, expressions, schema) | `engine` | DONE (2026-11-08; 36 of 36 battery states pixel-identical on the emulator; S24 at E2's exit) |
| T-005 | E2 slice 9: the `icon` primitive and the `statusbar` symbol set | `threads/T-005-e2-slice9-icons.md` | claude (painter, cache, harness), codex (generator, set, listing) | `engine` | DONE (2026-11-08; wifi.3 within 3 %, D-083) |
| T-004 | E2 slice 6: the first KDL bar in the shade window (time, dev switch) | `threads/T-004-e2-slice6-first-bar.md` | claude; codex cross-checks | `engine` | DONE (S24 measurements pending the phone) |
| T-003 | E2.0: AGP 8.13.2 and Gradle 8.13; every Android 11 call lint 8.13 found guarded (5 sites) | `threads/T-003-agp-8-13.md` | claude; codex cross-checked | `team/claude/t003-agp` | DONE |
| T-002 | E2.0a: Compose 1.11.4 and Kotlin 2.2, measured before and after | `threads/T-002-compose-kotlin-upgrade.md` | claude; codex cross-checks | `team/claude/t002-compose` | DONE (the owner's eye on the settings remains) |

## Parking lot

Things noticed that are not the current task. Each says who noticed it and where it came from.

- `tools/e1_s24.sh` summary shows only the last of three shade passes (D-072, claude); `tools/compare_s24.sh` (T-002)
  replaces it for comparisons.
- The S24's shade passes run in one of two GPU modes for a whole pass (GPU median ~2-4 ms or ~7.5-9 ms; about half the
  passes, both builds alike); cause not traced (T-002, claude and codex).
- `./gradlew check` on the release variant runs out of memory compiling `GestureNav` (D-070); CI builds debug only.
- `{@inset + 2pt}` mixes units at run time without a finding: the unit check is static (E2 slice 8, claude).
- No way yet to write a literal `{` in an expression-bearing string (E2 slice 4, claude).
- The draw node allocates a Compose canvas wrapper per primitive per frame and re-records each frame node: measure in
  slice 6 (E2 slice 5, claude).
- `tools/dexlist.sh` compares against the pre-engine reference `9984034` and its manifest check now fails on every
  build: give it a reference per integration (T-001 2b compared against engine's tip by hand; claude).
- The System tile and rows say "Shizuku not running" and "Restore without Shizuku" whichever host is selected: slice 5's
  wording (T-001 slice 3, claude).
- The own host's `nolink` and `block` proofs inject the failure in the starter, not inside Binder (T-001 2b, codex).

## Risks being watched

- Step 2's gate: if the Compose-tree / engine-pixels split misses the frame budget, the interpreter is redone on Views.
- Codex usage limits on the owner's plan: §8 of the protocol says what happens when Codex is unavailable.
