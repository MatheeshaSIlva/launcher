# Setting up the two-agent team (owner, once per machine)

Claude Code leads; Codex is its teammate. They share everything through the repo: `AGENTS.md` (the brief both load),
`docs/team/PROTOCOL.md` (how they work), `docs/team/BOARD.md` (what is happening), one thread per task in
`docs/team/threads/`, milestone reviews in `docs/team/reviews/`.

## Install

1. **Codex CLI:** `npm i -g @openai/codex`, then `codex login` and sign in with the ChatGPT account. On Windows, Codex
   runs natively or in WSL; if it runs in WSL, keep the repo on the Windows side and set `CODEX_WORKTREE` to a path both
   can reach.
2. Check it works from the repo: `codex exec --help` should list `--output-last-message`. If it does not, update Codex;
   `scripts/team/codex.sh` falls back to capturing the output, which is less tidy but works.
3. **Claude Code** is already set up here (`CLAUDE.md`, the `polish-pass` and `team-lead` skills, the stop hook).

## Start a session

Open Claude Code in the repo on `engine` and say, for example:

> Lead the team on T-001 (docs/team/threads/T-001-no-shizuku.md). Start the discussion with Codex.

Claude Code creates Codex's worktree (`../launcher-codex`), runs the discussion rounds, writes the split, runs both
shares of the work, cross-checks, and brings you the result to sign off. You can read the thread at any time; it is the
complete record. The board's first line always says what is happening.

## What you will be asked

- Anything the protocol reserves for you (scope, spec, new dependencies or permissions, irreversible steps, anything
  user-visible the spec is silent on, a disagreement the agents cannot settle).
- Sign-off on the S24 for anything on screen, with exact steps.
- "Go" at the end of each milestone review.

## Limits worth knowing

- Codex's usage limits on your plan: when it runs out, the thread says `codex: UNAVAILABLE` and the lead waits for it
  instead of signing for it. Tasks then take longer; nothing gets approved by one agent alone.
- Only one agent drives the phone at a time (the board's "Device:" line).
- Settings Codex uses: discussion and reviews run read-only; work runs with write access inside its own worktree and
  network on (Gradle needs it). Set `CODEX_MODEL` to pick a model.
