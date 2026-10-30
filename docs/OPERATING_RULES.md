# Project operating rules

@SPEC.md

The spec above is the locked direction of this project. Everything below is how work gets done here.

## How we work (single session, owner 2026-10-30)

One session does the work, in conversation with the owner: plan briefly, build, verify, report. No subagents and no
`/ship` pipeline: do not delegate to `.claude/agents` and do not run `/ship`, `/fix`, `/audit`, `/progress` or `/kickoff`
(they are kept in `.claude/_disabled/`). A fresh-eyes review by a subagent only if the owner asks for one (for example on
a crash, privacy or threading change). The main session runs on Opus.

1. Read what the task touches: SPEC, the active milestone in ROADMAP, the relevant D-entries, and the code. Read only what
   the task needs. If the request conflicts with the spec or a decision, say so first.
2. Say the plan in a few lines before a large change. Ask the owner only where the Ask list below says so.
3. Build in small steps: one concern per branch, roughly 200 changed lines.
4. Verify with the tool that fits the claim (next section), never by feel.
5. Update ROADMAP and DECISIONS, commit, report.

## Keep the owner in the loop

The owner follows the work from the chat, in plain words and short. No jargon without a few words of explanation.
- Say what you are about to do and why, in one or two lines, before each larger step.
- Say what you found or decided as it happens, with the reason in one sentence. Do not leave reasoning only in files.
- The end-of-task report is 10 lines or fewer: what changed, how we know it works (the numbers), what the owner still needs
  to check on the phone with exact steps, decisions made, what is next.
- If the owner asks "what is happening", answer in 5 lines or fewer.

## Verify with the right tool

- **Looks or moves like a reference** (Android 17's shade; iOS 27 once it has captures): `tools/fidelity/diff.py` against
  the golden captures (`tools/fidelity/capture.sh`; scores in `docs/FIDELITY_BASELINE.md`). The criterion is met when the
  diff passes. "Measured and called the same" by eye does not count (stage 8, B-046). Loop: capture, diff, read
  `worst.png`, fix the biggest region, repeat; after 6 passes with no gain, stop and report the regions left.
- **Smoothness**: frame stats and the scenario scripts on the S24 (what the screen showed), never our own frame log alone.
- **Logic**: unit tests; a bug fix ships with a test that failed before it.
- **Every change**: `scripts/check.sh` green. Never skip, delete or weaken a test, or loosen lint, to get there.
- **The S24 is free to use** (owner, 2026-10-25): frame stats, scenario scripts and screenshots. Check which window has the
  focus right before every capture (the phone can lock between two steps); the owner says sensitive notifications show no
  previews.

## Whole elements, not checklists (owner, 2026-10-30)

Passing a task's checklist is not "done". Work is judged by whether the whole element is finished as the owner sees it.
- Every element is worked as a milestone with an inventory first: every part, state and transition against its reference,
  with a verdict. Bugs and slices are steps inside it, never the plan itself.
- Only one element is open at a time. New findings in other elements go to the backlog; nothing there starts until the
  open element is signed off by the owner on the S24.
- Every report says, per claim, measured (on what: emulator, S24, reference capture) or judged. Emulator recordings judge
  pictures, not motion; smoothness is the S24's frame stats only.
- When a gate passes but the element is still visibly short of its reference, stop and tell the owner plainly; do not
  file the gap as a follow-up and move on. "Push when done" means done as the element, not as the task.

## Source of truth

- `docs/SPEC.md`: the owner's direction. **Never edit it without the owner's approval.**
- `docs/ROADMAP.md`: milestones, acceptance criteria, bug backlog. Update status, dates and checkboxes (only for criteria
  that are verified). Only the owner adds, removes or reorders milestones.
- `docs/DECISIONS.md`: append-only log. Log every architectural or trade-off choice made without the owner.
- `docs/QUESTIONS.md`: questions for the owner when they are away.

## Ask the owner vs decide and log

**Ask** (AskUserQuestion, recommended option first) when: the spec is silent or ambiguous on user-visible behavior; the
work would change scope, a milestone or the spec; a new dependency, permission or external service is needed; the
architecture would change in a way that is hard to reverse; the same fix has failed twice; something is destructive or
irreversible. **Decide and log in DECISIONS.md** for everything smaller. If the owner is away, write the question to
`docs/QUESTIONS.md` and carry on only with work that does not depend on it.

## Honest reporting

- Say "done" only when the checks passed. State exactly what was verified and what was not.
- If something could only be checked on the phone, list the exact steps for the owner.
- No new dependencies, permissions or config flags without the owner's approval.
- Do not push or merge on your own: push to main only after the checks pass AND the owner says go for that task.
- Do not invent time estimates; derive them from the dates in ROADMAP.

## Limits

- One active milestone at a time. New ideas go to the Parking lot in `docs/ROADMAP.md`.
- If the same fix fails twice, stop and report what keeps failing before a third try.
- Every 3 to 5 tasks, or when the owner asks, compare the work with SPEC and ROADMAP and say whether it is still on
  direction (unrequested features, spec rot).
