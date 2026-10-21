---
description: Run the full governed pipeline for one roadmap item or feature (plan, build, check gate, review, QA, report).
argument-hint: [milestone id like M2, or a task description]
---
Run the governed pipeline for this task: $ARGUMENTS

You are the orchestrator. Follow the rules in CLAUDE.md. Track the steps below in your todo list.

## 0. Preflight
- Read `docs/SPEC.md`, `docs/ROADMAP.md`, `docs/DECISIONS.md`, `docs/QUESTIONS.md`.
- If the task is not on the roadmap, or the spec is not LOCKED, stop and ask the owner before doing anything else.
- If this is a git repo: check `git status`. If the tree is dirty, ask the owner whether to commit or stash. Then create a branch `ship/<id>-<short-slug>`.
- Mark the milestone ACTIVE and set Started in the roadmap if it is not already.

## 1. Plan
Delegate to the `architect` agent. Give it the task, the roadmap item, and the doc paths. If the verdict is `DOES_NOT_FIT_SPEC`, stop and tell the owner. If the plan is larger than one focused session, ask the architect to plan only the first slice.

## 2. Clear the questions (once, up front)
Collect every `NEEDS_HUMAN` item from the plan. Ask the owner in one batch with AskUserQuestion, recommended option first. If the owner is unavailable, write the questions to `docs/QUESTIONS.md` and stop (or continue only with parts that do not depend on the answers). Record the answers and any decisions the architect listed in `docs/DECISIONS.md`.

## 3. Build, gate, review, QA (max 3 rounds)
Repeat up to 3 times:
1. Delegate to `implementer` with the plan, acceptance criteria, and (from round 2) only the list of blocking issues to fix.
2. Run `scripts/check.sh` yourself. If red, send the failure back to the implementer. This does not use up a review.
3. Delegate to `reviewer` with the plan, the acceptance criteria and the instruction to review `git diff`. Do not pass the implementer's report or reasoning. `REVISE` means next round. `ESCALATE` means go to step 4 below with the owner.
4. Delegate to `qa-tester` with the plan and acceptance criteria. `FAIL` means next round with its bug list.
5. If the reviewer says APPROVE and QA says PASS or PASS_WITH_MANUAL_CHECKS, exit the loop.

If round 3 still does not converge, stop. Give the owner a short summary of what keeps failing and ask how to proceed. Do not start a fourth round on your own.

## 4. Close out
- Tick only the roadmap criteria that are verified. If the milestone is fully done, set Status DONE and Finished to today's date.
- Append any decisions to `docs/DECISIONS.md`. Log newly found bugs in the Bug backlog and ideas in the Parking lot.
- Commit on the branch with a clear message. Do not push or merge.

## 5. Report to the owner (10 lines or fewer)
- What changed, in plain words
- Gate evidence: check result, reviewer verdict, QA verdict, quoted as given
- Manual checks the owner still needs to do, with exact steps
- Decisions logged, questions open
- Suggested next item

Never describe the work as done if any gate was skipped, red, or not run. Say exactly which.
