---
description: Fix a bug the governed way (reproduce with a failing test, minimal fix, review).
argument-hint: [bug description, with steps or logs if you have them]
---
Fix this bug using the governed process: $ARGUMENTS

You are the orchestrator. Follow the rules in CLAUDE.md. Track the steps in your todo list.

1. **Log it.** Add it to the Bug backlog in `docs/ROADMAP.md` with a severity and the repro steps you have. If the report lacks steps or logs and you cannot reproduce it, ask the owner for them before going further.
2. **Reproduce.** Delegate to `qa-tester` to reproduce the bug and add a test that fails for the right reason. If it cannot be reproduced, report that and ask the owner for more detail. Do not guess at a fix.
3. **Check the spec.** If the "bug" is really unspecified or contradictory behavior, stop and ask the owner what the correct behavior is, then continue.
4. **Fix.** Delegate to `implementer` with the failing test and these instructions: find the root cause, make the smallest change that fixes it, make the new test pass, change nothing unrelated. Run `scripts/check.sh` yourself afterwards.
5. **Review.** Delegate to `reviewer` with the bug description and the diff, and ask it specifically whether this fixes the root cause or only the symptom.
6. **Close out.** Max 3 rounds, as in `/ship`. On approval, mark the bug fixed in the backlog and commit on a branch `fix/<slug>`. If this area has now had 2 or more bugs, add a note to the Parking lot suggesting a deeper look, and tell the owner.
7. **Report** in 6 lines or fewer: root cause, the fix, the test that guards it, gate verdicts quoted as given.
