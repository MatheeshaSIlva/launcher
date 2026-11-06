# Team protocol: Claude Code (lead) and Codex (teammate)

_Owner's instruction, 2026-11-06 (D-073). Replaces D-070's "this session plus an Opus agent" for all engine work._

## 1. Principles

- **Files are the memory.** Every proposal, objection, split, report and sign-off is written in the task's thread
  (`docs/team/threads/<ID>.md`). Neither agent depends on what the other "remembers". A fresh session of either agent
  must be able to continue from `AGENTS.md`, the board and the thread alone.
- **Two voices, one integrator.** Both agents propose, critique and build. The lead chairs, integrates and commits to
  `engine`. Between the agents the lead has the casting vote; over the owner, nobody does.
- **An objection blocks "done".** A task is DONE only when both agents have signed AGREE in its thread, `scripts/check.sh`
  is green on `engine`, and (for anything on screen) the owner has signed it off on the S24. An agent may not sign for
  the other.
- **Disagree on substance, briefly.** Each discussion entry says what the agent would change and why, with evidence
  (a file and line, a measurement, a spec or decision reference). "Looks good" is not a review; say what was checked.

## 2. The loop, for every task

A task is one slice of a step (E2.1, E2.2, …) or a standalone change. The lead opens a thread from
`docs/team/threads/TEMPLATE.md`, adds a row to the board, and runs:

| # | Phase | Who | Writes | Board status |
| --- | --- | --- | --- | --- |
| 1 | **Propose** | lead | §1 Proposal: goal, approach, files touched, how it will be verified, risks, open questions | PROPOSED |
| 2 | **Discuss** | both, alternating | §2 one entry per round: agree / change / add, with reasons. Codex goes first after the proposal. At most three rounds each. | DISCUSSING |
| 3 | **Agree the approach** | lead writes, Codex confirms | §3 the approach as agreed, with what changed from the proposal and why; Codex's line `codex: AGREE approach` | AGREED |
| 4 | **Split** | lead proposes, Codex may amend | §4 a table: sub-task, owner, the files each owns (no file has two owners), branch, how each is verified | AGREED |
| 5 | **Work** | each on its own branch and worktree | commits on `team/<agent>/<slug>`; nothing touches files the other owns | IN PROGRESS |
| 6 | **Report** | each | §5 what changed (commits), how verified (numbers, marked measured/judged), what is not verified, anything that surprised them | REPORTED |
| 7 | **Cross-check** | each reviews the other's diff and report | §6 findings with file:line; each finding is *fix*, *question* or *note* | REPORTED |
| 8 | **Fix and re-check** | owner of the finding | fixes on their branch; the reviewer confirms in §6 | REPORTED |
| 9 | **Sign-off** | both, then owner if on screen | §7 `claude: AGREE` / `codex: AGREE` with one line each on what they checked; owner line for on-screen work | SIGNED |
| 10 | **Integrate** | lead | rebase both branches onto `engine`, run `scripts/check.sh` (and `tools/dexlist.sh` when code moved), fast-forward `engine`, push, append the decision to `DECISIONS.md` if one was made, report to the owner | DONE |

Small changes (a typo, a doc fix, a one-line test fix) may skip phases 2–4: the lead writes the proposal and the split
in one entry, Codex answers with AGREE or a finding. They still need both sign-offs.

## 3. How the lead talks to Codex

The lead runs Codex non-interactively from the main worktree:

```bash
scripts/team/codex.sh <thread-file> <mode> "<what you want from Codex this round>"
```

Modes: `discuss` (read the thread, answer the open round), `work` (do your sub-tasks from §4 on your branch, then write
your report), `review` (cross-check the lead's diff and report), `signoff` (confirm fixes and sign, or object),
`milestone` (write your blind milestone review). The script runs Codex in its own worktree, gives it this protocol and
the thread, captures Codex's final message and appends it to the thread under a dated `### codex` heading. Codex never
edits the thread file directly; the script does, so the thread stays one linear record.

The lead writes its own entries into the thread by hand, under `### claude`, before calling Codex for the next round.

## 4. Branches, worktrees and ownership

- Main worktree (the repo itself): the lead, on `engine` or `team/claude/<slug>`.
- Codex's worktree: `../launcher-codex`, created and moved to the task's branch with
  `scripts/team/worktree.sh <slug>` (branch `team/codex/<slug>` from the current `engine`).
- The split in §4 names an owner for every file. If an agent finds it must touch a file it does not own, it stops and
  writes a *question* in the thread; the lead amends the split.
- Docs that both agents write (`DECISIONS.md`, `BOARD.md`, threads) are written only by the lead on `engine`. Codex
  proposes their text in its report.
- Integration is rebase plus fast-forward: `engine` stays linear, one commit per slice where possible, both agents in
  the trailers.

## 5. Disagreement and escalation

1. Each side states its position once with evidence. If it can be settled by measuring, measure, and the number decides.
2. After three rounds without agreement, the lead decides and records the decision and Codex's dissent in the thread and
   in `DECISIONS.md`. Codex may sign `AGREE (dissent recorded)`.
3. Escalate to the owner instead (AskUserQuestion, or `docs/QUESTIONS.md` if he is away) when the disagreement is about
   scope, the spec, a dependency, anything user-visible the spec is silent on, anything irreversible, or when the same
   fix has failed twice. These are the owner's calls under `OPERATING_RULES.md`, whatever the agents think.

## 6. Milestone reviews

A milestone review happens at the end of every build step (E2, E3, …), before the public alpha, before the store, and in
any case after every five DONE tasks. It is written in `docs/team/reviews/<milestone>.md` from the template there.

1. **Blind first.** Each agent writes its own review before reading the other's: the lead in its section, Codex through
   `scripts/team/codex.sh … milestone`. Neither sees the other's until both are written. This is what stops one agent
   anchoring the other.
2. **What each review covers.** Spec drift (does the work still match `ENGINE_SPEC.md` and the audit's facts?); the step's
   exit test, re-run, with numbers; tests added and their coverage of what changed; every decision taken without the
   owner, re-read; the security checklist (§7) over everything that touched the attack surface; the polish pass for
   anything on screen; the board's parking lot and risks; what the next step now needs that it did not before.
3. **Reconcile.** The lead merges both into a findings list (each: severity, owner, fix-now or next-step). Fix-now items
   become tasks and go through the loop.
4. **Owner.** The lead sends the owner a summary of 10 lines or fewer and the exit-test numbers. The next step starts
   when the owner says go.

## 7. Security checklist

Fill in for any change touching the shell service, AIDL, the capability registry, the boot/ADB starter, the KDL parser,
expression evaluation or the Lua sandbox. Both agents answer each line in the thread's §6.

- [ ] Every new or changed entry point checks its caller (uid, package, signature) before acting.
- [ ] Inputs from files, packages, intents, binders and the network are bounded (size, depth, count, time) and parsed
      without reflection on attacker-chosen names.
- [ ] No new path from KDL, Lua or a package to shell, `exec`, reflection, file paths outside the package, or another
      app's components.
- [ ] Capabilities: the change cannot run without the capability it needs, and the derivation from `uses` still covers it.
- [ ] Nothing captured from another app is written to disk, logged, or sent off the device.
- [ ] Secrets (ADB pairing key, tokens) stay in app-private storage, never in logs.
- [ ] A test exercises the refusal path, not only the happy path.

## 8. When Codex is unavailable

If Codex is rate-limited or errors, the lead writes `codex: UNAVAILABLE (<reason>, <time>)` in the thread and continues
only with its own sub-tasks. It does not take over Codex's sub-tasks and does not sign for Codex. The task waits at
REPORTED until Codex can sign off. If the wait would block the step for more than a day, the lead asks the owner whether
to reassign.

## 9. The owner's touchpoints

- Approves the start of every build step and every milestone review's next step.
- Answers escalations (§5.3).
- Signs off on-screen work on the S24 (`OPERATING_RULES.md`: "whole elements, not checklists").
- Can read any thread at any time; the board's top line always says what is happening in one sentence.
