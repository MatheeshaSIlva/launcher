---
description: Run the governed pipeline for one roadmap item or feature. Picks a track: fidelity (diff loop), quick (implement plus gate) or full.
argument-hint: [milestone id like MF, or a task description]
---
Run the governed pipeline for this task: $ARGUMENTS

You are the orchestrator. Follow `docs/OPERATING_RULES.md` (CLAUDE.md imports it), including Tracks. Track the steps in your todo list.
Keep the owner informed as OPERATING_RULES says: one plain line before and after each stage, and ask every agent to open
its report with a 2-line plain summary.

## 0. Preflight
- Read `docs/SPEC.md`, `docs/ROADMAP.md`, `docs/DECISIONS.md`, `docs/QUESTIONS.md`.
- If the task is not on the roadmap, or the spec is not LOCKED, stop and ask the owner before doing anything else.
- If this is a git repo: check `git status`. If the tree is dirty, ask the owner whether to commit or stash. Then create a branch `ship/<id>-<short-slug>`.
- Mark the milestone ACTIVE and set Started in the roadmap if it is not already.
- Say which track this is, in one line: **fidelity** (it must look or move like a reference), **quick** (a small, clear fix, no new behaviour) or **full** (crash, privacy, architecture, or new behaviour with no reference).

## 1. Plan (fidelity and full; skip for quick)
Delegate to `architect` (Opus). Give it the task, the roadmap item and the doc paths. If the verdict is `DOES_NOT_FIT_SPEC`, stop and tell the owner. If the plan is larger than one focused session, ask for the first slice only. For the fidelity track the plan names the states, the golden captures and the tolerance.

## 2. Clear the questions (once, up front)
Collect every `NEEDS_HUMAN` item from the plan. Ask the owner in one batch with AskUserQuestion, recommended option first. If the owner is unavailable, write the questions to `docs/QUESTIONS.md` and stop (or continue only with parts that do not depend on the answers). Record the answers and any decisions the architect listed in `docs/DECISIONS.md`.

## 3a. Fidelity track
1. If the golden is missing, capture it first (`tools/fidelity/capture.sh`, stock side).
2. Delegate to `implementer` once, for the whole loop: capture ours, run `python tools/fidelity/diff.py GOLDEN OURS --out DIR`, read `worst.png` and `report.json`, fix the biggest regions, rebuild, repeat. It stops at exit 0, or after 6 passes with no fall in the flagged cells, and reports the regions left.
3. Run the diff yourself and show the scores; never trust a claim. Then `scripts/check.sh`.
4. One `reviewer` pass on the code, not the look, when the diff is more than a few lines.
5. Run the S24 scenario for the area (frame stats; screenshots if useful, focus checked first).

## 3b. Quick track
Delegate to `implementer` with the bug and its repro. Run `scripts/check.sh` yourself. Report. If anything crashes, touches privacy or threading, switch to the full pipeline.

## 3c. Full pipeline (max 2 rounds)
Repeat up to 2 times:
1. Delegate to `implementer` with the plan, acceptance criteria and (round 2) only the blocking issues to fix.
2. Run `scripts/check.sh` yourself. If red, send the failure back to the implementer; this does not use up a round.
3. Delegate to `reviewer` (Sonnet; `model: opus` for crash, privacy or architecture work) with the plan, the acceptance criteria and the instruction to review `git diff`. Do not pass the implementer's report. `REVISE` means next round; `ESCALATE` goes to the owner.
4. Delegate to `qa-tester` (Sonnet; same override) with the plan and acceptance criteria. `FAIL` means next round with its bug list.
5. Reviewer APPROVE and QA PASS (or PASS_WITH_MANUAL_CHECKS) end the loop.

If round 2 still does not converge, stop and ask the owner. Do not start a third round on your own.

## 4. Close out
- Tick only the roadmap criteria that are verified. If the milestone is fully done, set Status DONE and Finished to today's date.
- Append any decisions to `docs/DECISIONS.md`. Log newly found bugs in the Bug backlog and ideas in the Parking lot.
- Commit on the branch with a clear message. Push to main only after the checks pass AND the owner says go for this task.

## 5. Report to the owner (10 lines or fewer)
- What changed, in plain words
- Evidence: the check result, and for fidelity the diff scores before and after with the regions left; for the full pipeline the reviewer and QA verdicts, quoted as given
- Manual checks the owner still needs to do, with exact steps
- Decisions logged, questions open
- Suggested next item

Never describe the work as done if any gate was skipped, red, or not run. Say exactly which.
