# Board

**Now:** E2 is under way: the interpreter's core is built and tested (KDL parser, model, validator, expressions, layout,
Compose host, tokens, the time/battery/network/dnd sources). Claude is upgrading Compose to 1.11.4 and Kotlin to 2.2
(T-002), which the first visible slice needs; T-001's first slice (our own ADB client) is offered to Codex alongside.

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

Next, in order: 0a/0b (T-002, the upgrade), 6 (the first visible bar in the shade window), 9, 10, 12-17. 73 engine unit
tests. Owner questions still open from the plan: 5 (one expression syntax, `{expr}` with pipes: a spec change) and 6
(the bar as a component of the `shade` surface: spec wording); the code already does both.

## Tasks

Status: PROPOSED · DISCUSSING · AGREED · IN PROGRESS · REPORTED · SIGNED · DONE · BLOCKED

| ID | Task | Thread | Owners | Branches | Status |
| --- | --- | --- | --- | --- | --- |
| T-001 | Our own shell host replacing Shizuku, alongside E2; slice 1 (the ADB client) offered to Codex | `threads/T-001-no-shizuku.md` | claude (lead), codex (slice 1, offered) | `team/codex/t001-adb` | DISCUSSING |
| T-002 | E2.0a: Compose 1.11.4 and Kotlin 2.2, measured before and after | `threads/T-002-compose-kotlin-upgrade.md` | claude; codex cross-checks | `engine` | DISCUSSING |

## Parking lot

Things noticed that are not the current task. Each says who noticed it and where it came from.

- `tools/e1_s24.sh` summary shows only the last of three shade passes (D-072, claude); T-002's comparison script fixes it.
- The spec's `battery-ring` example is not valid KDL (D-069); its corrected text is `KdlBench.SPEC_EXAMPLE_V2` and
  the E2 plan's model tests. Spec edit, the owner's (E2 question 5).
- `./gradlew check` on the release variant runs out of memory compiling `GestureNav` (D-070); CI builds debug only.
- `{@inset + 2pt}` mixes units at run time without a finding: the unit check is static (E2 slice 8, claude).
- No way yet to write a literal `{` in an expression-bearing string (E2 slice 4, claude).
- The draw node allocates a Compose canvas wrapper per primitive per frame and re-records each frame node: measure in
  slice 6 (E2 slice 5, claude).

## Risks being watched

- Step 2's gate: if the Compose-tree / engine-pixels split misses the frame budget, the interpreter is redone on Views.
- Codex usage limits on the owner's plan: §8 of the protocol says what happens when Codex is unavailable.
- T-002 touches every Compose screen at once: the settings are measured on the S24 before and after, interleaved.
