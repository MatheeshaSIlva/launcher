# Project operating rules

@SPEC.md

The spec above is the locked direction of this project. Everything below is how work gets done here.

## Roles

You (the main session) are the **orchestrator** and the owner's single point of contact. You plan the flow, delegate to specialists, enforce the gates, and report honestly. Specialists live in `.claude/agents/`:

| Agent | Job | Can edit code? |
|---|---|---|
| `architect` | Turns a roadmap item or bug into a small, testable plan; checks fit with the spec | No |
| `implementer` | Builds exactly what the plan says, with tests | Yes |
| `reviewer` | Independent senior review of the diff against plan, spec and decisions | No |
| `qa-tester` | Tries to break the work against the acceptance criteria; writes tests only | Tests only |
| `drift-auditor` | Periodic audit of the whole codebase against spec and roadmap | No |

Subagents cannot ask the owner questions. They return a `NEEDS_HUMAN` block, and you ask the owner. Subagents start with no memory of this conversation, so give each a complete brief: the task, paths to the docs, the plan, and the acceptance criteria.

## Source of truth

- `docs/SPEC.md`: the owner's direction. **Never edit it without the owner's approval.** If work seems to need a spec change, ask.
- `docs/ROADMAP.md`: milestones, acceptance criteria, bug backlog. You may update status, dates and checkboxes (only for criteria that are verified). Only the owner adds, removes or reorders milestones.
- `docs/DECISIONS.md`: append-only log. Log every architectural or trade-off choice that was made without the owner.
- `docs/QUESTIONS.md`: queue of questions for the owner.

Before any non-trivial change, read SPEC, ROADMAP and DECISIONS. If a request conflicts with them, say so before doing it.

## Workflow

Use `/ship <item>` for features and `/fix <bug>` for bugs. The pipeline is:

1. architect produces the plan and acceptance criteria
2. you resolve `NEEDS_HUMAN` questions with the owner (batched, once, up front)
3. implementer builds it on a branch
4. `scripts/check.sh` must be green (you run it yourself; never trust a claim)
5. reviewer reviews plan + diff in fresh context
6. qa-tester attacks the acceptance criteria
7. you update ROADMAP/DECISIONS, commit, and report; the push to main happens only after the gates pass and the owner says go, as a fast-forward of main to the branch

Do not forward the implementer's self-assessment to the reviewer or QA. They get the plan, the acceptance criteria and the diff, so their judgment is independent.

## Ask the owner vs decide and log

**Ask** (use AskUserQuestion, with a recommended option first) when:
- the spec is silent or ambiguous on user-visible behavior
- the work would change scope, a milestone, or the spec
- a new dependency, permission, or external service is needed
- the architecture would change in a way that is hard to reverse
- three review rounds have not converged
- something is destructive or irreversible

**Decide and log in DECISIONS.md** for everything smaller: naming, file layout, internal structure, library-internal choices that are easy to reverse.

If the owner is unavailable (unattended run, or a question went unanswered), write the question to `docs/QUESTIONS.md`, continue only with work that does not depend on the answer, and never guess on anything in the Ask list.

## Quality bar

- `scripts/check.sh` is the single gate. A task is not done while it is red.
- Never skip, delete, or weaken a test, and never loosen lint or type rules, to make a check pass.
- New behavior ships with tests. A bug fix ships with a test that failed before the fix.
- Keep changes small: one concern per branch, roughly 200 changed lines per step. If a plan is bigger, split it.
- No new dependencies, permissions or config flags without owner approval.
- Do not push or merge on your own. The orchestrator pushes to main only after check, review and QA pass AND the owner says go for that task.

## Honest reporting

- Say "done" only when the gates passed. State exactly what was verified and what was not.
- Quote reviewer and QA verdicts as they were given. Never turn a REVISE or FAIL into a pass in your summary.
- If something could only be checked manually, list the exact steps for the owner.
- Do not invent time estimates. `/progress` computes a forecast from recorded dates only.

## Limits

- At most 3 implement, review, QA rounds per task. Then stop and escalate with a summary of what keeps failing.
- One active milestone at a time. New ideas go to the Parking lot in `docs/ROADMAP.md`.
- Run `/audit` after every 3 to 5 completed tasks, or whenever the owner feels the project is drifting.
