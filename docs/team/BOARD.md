# Board

**Now:** E1 is done (D-072). E2 has not started; the owner starts it. The first team thread is open for discussion: where
the no-Shizuku work (spec, Decisions, 2026-11-06) goes, since E1 moved `ShizukuLink` as it was.

**Step:** E2 — KDL loader and interpreter core (`docs/ENGINE_SPEC.md`, build order step 2)
**Device:** claude (lead)
**Last milestone review:** none yet (the first is due at the end of E2)

## Tasks

Status: PROPOSED · DISCUSSING · AGREED · IN PROGRESS · REPORTED · SIGNED · DONE · BLOCKED

| ID | Task | Thread | Owners | Branches | Status |
| --- | --- | --- | --- | --- | --- |
| T-001 | Where the no-Shizuku host goes (E1b before E2, inside E2, or after) and its slice plan | `threads/T-001-no-shizuku.md` | claude (lead), codex | — | PROPOSED |

## Parking lot

Things noticed that are not the current task. Each says who noticed it and where it came from.

- The two probes' removal (`LiveTransitionProbe`, AIDL methods 17 and 43) as its own change (D-072, claude).
- `tools/e1_s24.sh` summary shows only the last of three shade passes (D-072, claude).
- The Compose (and Kotlin) version move needed for a Compose `deck` on the nav thread is the owner's call (D-069).
- The spec's `battery-ring` example is not valid KDL (D-069).
- `./gradlew check` on the release variant runs out of memory compiling `GestureNav` (D-070); CI builds debug only.

## Risks being watched

- Step 2's gate: if the Compose-tree / engine-pixels split misses the frame budget, the interpreter is redone on Views.
- Codex usage limits on the owner's plan: §8 of the protocol says what happens when Codex is unavailable.
