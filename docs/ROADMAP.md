# Roadmap

Rules:
- One active milestone at a time.
- Only the owner adds, removes or reorders milestones.
- Agents update Status, Started, Finished and tick a checkbox only when that criterion is verified.
- Dates are ISO (YYYY-MM-DD). `/progress` computes velocity from them, so keep them honest.

## Definition of done (applies to every milestone)
- Every acceptance criterion is met, each with an automated test or a recorded manual verification
- `scripts/check.sh` is green
- reviewer verdict is APPROVE and qa-tester verdict is PASS (or PASS_WITH_MANUAL_CHECKS that the owner completed)
- No known regressions; any open bugs are logged in the Bug backlog
- `docs/DECISIONS.md` updated if a design choice was made

## Milestones

| ID | Name | Status | Started | Finished |
|----|------|--------|---------|----------|
| M0 | Safety net | TODO | | |
| M1 | TODO: name | TODO | | |
| M2 | TODO: name | TODO | | |

Status values: TODO, ACTIVE, DONE, BLOCKED.

### M0: Safety net
Goal: make it cheap to know whether a change broke something.
Acceptance criteria:
- [ ] `scripts/check.sh` runs real checks for this stack (build, lint or type check, tests) and passes on the current code
- [ ] A baseline of tests covers the core journeys in `docs/SPEC.md` at least at smoke-test level
- [ ] Known existing bugs are listed in the Bug backlog below, with repro steps where known

### M1: TODO
Goal:
Acceptance criteria:
- [ ] TODO: 3 to 6 testable, observable criteria

### M2: TODO
Goal:
Acceptance criteria:
- [ ] TODO

## Bug backlog
Format: `- [B-001] severity (P0 to P3), area, one-line symptom. Repro: steps. Status: open/fixed`
- (none logged yet)

## Parking lot
Ideas that are not in scope. The owner decides if and when they become milestones.
- (empty)
