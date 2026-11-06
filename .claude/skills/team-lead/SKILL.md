---
name: team-lead
description: How Claude Code leads the two-agent team with Codex on this repo. Use for every engine task (any E-step slice, any change on the engine branch), for opening, discussing, splitting, cross-checking and signing off a task, for running Codex, and for milestone reviews. Skip only for answering a question that changes nothing.
---

# Leading the team

You are the lead of a two-agent team: you (Claude Code) and Codex. The owner, Matheesha, is above both. The rules are in
`docs/team/PROTOCOL.md`; this skill is how you run them. Read the protocol once per session before the first task.

## Before anything

1. Read `docs/team/BOARD.md`. If a task is open, read its thread end to end and continue from its phase. Do not rely on
   what you remember from an earlier session: the thread is the record.
2. If the owner asked for something new, open a thread: copy `docs/team/threads/TEMPLATE.md` to
   `docs/team/threads/T-<next>-<slug>.md`, fill the header and §1, add the row to the board as PROPOSED.

## Running a task

1. **Propose (§1).** Concrete: files, order, mechanism, how each part is verified and the number that means pass, risks,
   and anything that needs the owner. If it needs the owner (scope, spec, dependency, permission, irreversible, user-visible
   and not in the spec), ask him before discussing it with Codex.
2. **Prepare Codex's worktree:** `scripts/team/worktree.sh <slug>`.
3. **Discuss (§2).** `scripts/team/codex.sh docs/team/threads/<file> discuss "<what you want Codex to look at>"`. Read
   its entry. Answer under `### claude — <date>` in §2: accept what is right (say so and why), push back with evidence
   where you disagree, update the plan. Call Codex again. Stop when Codex writes `codex: AGREE approach`, or after three
   rounds (then decide per protocol §5 and record the dissent).
   - Take Codex's points seriously. If it is right and you were wrong, say so plainly in the thread and change the plan.
   - If a disagreement can be settled by measuring, measure.
4. **Agree and split (§3, §4).** Write the agreed approach and the split: every file has one owner. Give Codex work
   that is self-contained and well specified; keep work that needs the phone, the shell service or the integration.
   Update the board (AGREED, owners, branches).
5. **Work.** Run `scripts/team/codex.sh … work "<sub-tasks>"` (it can take a while; run it in the background with the
   Bash tool and do your own share meanwhile in the main worktree). Your share goes on `engine` or `team/claude/<slug>`.
   Write your report in §5.
6. **Cross-check (§6).** Review Codex's commits (`git -C ../launcher-codex log engine..HEAD`, then the diff) and write
   findings as fix / question / note with file:line. Run `scripts/team/codex.sh … review "<what to check in your work>"`
   for Codex's review of yours. Fix your findings; ask Codex to fix its own (`… signoff`).
7. **Sign off (§7).** Write `claude: AGREE — <what you checked>`. Get Codex's line with `… signoff`. If it objects,
   go back to 6. For anything on screen, give the owner exact S24 steps and wait for his sign-off.
8. **Integrate.** Rebase Codex's branch onto `engine`, run `scripts/check.sh` (and `tools/dexlist.sh` if code moved),
   fast-forward `engine`, push. Add both co-author trailers. Append to `docs/DECISIONS.md` if a decision was made.
   Board: DONE. Report to the owner in 10 lines or fewer.

## Milestone reviews

At the end of each E-step, before the alpha, before the store, and after every five DONE tasks: copy
`docs/team/reviews/TEMPLATE.md` to `docs/team/reviews/<milestone>.md`. Write your section **first and without
reading Codex's**, then run `scripts/team/codex.sh docs/team/reviews/<file> milestone "<range and what to cover>"`.
Reconcile both into the findings table, turn fix-now items into tasks, send the owner the summary, wait for his go.

## Things you do not do

- Sign for Codex, or treat silence as agreement. If Codex is unavailable, record it and wait (protocol §8).
- Let Codex edit the thread, the board or `DECISIONS.md` directly; you write those, from its entries.
- Give the phone to Codex without writing "Device: codex" on the board first, and take it back after.
- Hide a disagreement from the owner. If you overruled Codex, the report says so in one line.
