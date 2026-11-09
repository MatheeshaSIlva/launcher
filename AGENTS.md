# AGENTS.md — Not a Launcher (shared brief for every agent)

Both agents load this file: Codex reads it automatically, Claude Code imports it from `CLAUDE.md`. It is the one place the
team's shared context starts. Keep it under 32 KB (Codex's default load limit) and keep it current: if something here
is wrong, fix it in the same change that made it wrong.

## The team

| Who | Role |
| --- | --- |
| **Matheesha** (owner) | Decides direction, scope, dependencies, anything user-visible the spec is silent on. Signs off every on-screen element on the S24. Reads short plain reports. |
| **Claude Code** (lead) | Runs each step: writes the proposal, chairs the discussion, splits the work, integrates and commits to `engine`, holds the phone by default, writes the report to the owner. Has the final call between agents, never over the owner. |
| **Codex** (teammate) | Reviews and improves every proposal, owns its share of the split, cross-checks the lead's work, signs off or objects. Equal voice in discussion; its objection blocks "done" until resolved or escalated. |

How the two agents talk, split work and agree: **`docs/team/PROTOCOL.md`**. What is happening now: **`docs/team/BOARD.md`**.
Neither agent relies on chat memory. Everything that matters is written in the repo, so either agent (or a fresh session
of either) can pick up from the files alone.

## What the project is

Matheesha's Android launcher (Kotlin, ~72k lines, Shizuku, no root) is becoming **Not a Launcher**: a component engine
where home, drawer, shade, recents and status bar are KDL components drawn by ~40 engine primitives, with themes, a
creator store (creators sell their own data-only APKs on Play; we sell one Pro tier, subscription or lifetime) and later a
desktop editor. Compose owns the tree; the engine owns the pixels. The launcher as built is the reference implementation:
every hand-drawn view is a checklist of what its replacement must do, and proves the engine can draw it, but its look does not ship: the iOS and Pixel designs are scrapped for a default theme of our own, and settings are what active packages offer (D-092).

Branches: `main` is frozen as the last working launcher (CI and the in-app updater ship from it). All work is on
`engine`. Agent task branches are `team/<agent>/<slug>`, merged into `engine` by the lead.

## Read before any task (in this order)

1. `docs/team/BOARD.md` — the current step, open tasks, who owns what, who holds the phone.
2. The task's thread in `docs/team/threads/` — the discussion so far, the agreed approach, the split.
3. `docs/ENGINE_SPEC.md` — what we are building. **Canonical**; supersedes `docs/SPEC.md` on this branch.
4. `docs/LAUNCHER_AUDIT.md` — per-file verdicts (port / extract / rewrite / drop), the device-proven facts that are not
   open to redesign, and the build order with exit tests.
5. `docs/DECISIONS.md` — append-only log; read every entry from D-067 on, and any older entry the task touches.
6. The step's inventory if it has one (`docs/E1_INVENTORY.md` is the model: read-only inventory first, then decisions).
7. `docs/OPERATING_RULES.md` — how work gets done and verified here. Its two-agent section is this team.
8. `CLAUDE.md` Part 2 — the manual for the launcher as built (about 120 KB). Do not read it whole; search it for the
   files and subsystems the task touches. It is never a source of direction: Part 1 of `CLAUDE.md` says which file wins
   when two disagree, and `docs/SPEC.md`, `docs/REQUIREMENTS.md` and `docs/PLAN_*.md` are history on `engine`. `docs/PROBE_FINDINGS.md` holds the measured platform facts.

## Where things are

- `app/` — the launcher. `engine/` — the `:engine` module (E1 moved the platform layer here under the old
  `dev.launcher.app.*` packages; namespace `dev.launcher.engine`). No package rename until its own step (D-070).
- `scripts/check.sh` — the single quality gate (build, lint, unit tests; shell tests in the full run). Must be green.
- `tools/` — `dexlist.sh` (APK class/method diff, run after every slice that moves code), `device.sh` (install with the
  `.dm`), `e1_s24.sh` and the `scenario_*.sh` frame-stat scripts, `fidelity/` (capture and diff against references).
- `bench/`, `bench-desktop/`, `bench-shared/` — step-0 harnesses, built only with `-Pbench` (`docs/BENCH_STEP0.md`).
- `.claude/skills/polish-pass/SKILL.md` — the bar for anything on screen. Codex: read the file and apply it; it is not a
  Claude-only rule.

## Rules both agents follow

These come from `docs/OPERATING_RULES.md`, `CLAUDE.md` and the decisions. They outrank speed.

**The product rules.** Perfectly smooth (measured from S24 frame stats, never eyeballed; the engine's bound is ≤1 dropped
frame per 100 on a pull at 120 Hz, idle < 1 ms/frame). Fully consistent (one shade, one bar, one gesture set). Every
change on screen animated, interruptible, retargetable; nothing pops.

**Verify with the right tool, and say which.** Every claim in a report is marked *measured* (on what: unit test,
emulator, S24, dex diff, fidelity diff) or *judged*. Logic: a unit test, and a bug fix ships with a test that failed
before it. Moves: `tools/dexlist.sh`. Smoothness: S24 frame stats only. Looks: `tools/fidelity/diff.py`. Never skip,
delete or weaken a test or loosen lint to get green.

**Change discipline.** One concern per slice, about 200 changed lines for slices that edit (pure `git mv` slices may be
cluster-sized). No new dependency, permission, config flag or external service without the owner's yes. Never push to
`main`. Never edit `docs/SPEC.md`. `docs/DECISIONS.md` is append-only; log every trade-off chosen without the owner.

**Security (D-068).** Treat the shell service, the AIDL bridge, the capability registry, the KDL parser and the Lua
sandbox as attack surface. Any change to them gets the security checklist in `docs/team/PROTOCOL.md` §7 filled in by both
agents. The Lua sandbox never exposes `load`, `loadstring`, `string.dump`, `os`, `io`, `debug`, `require` or the Java
bridge. Captured frames of other apps never leave the GPU and never persist.

**Privacy.** Never screenshot or record the owner's private apps. Notification Center and banners may be captured on
the S24 for checks; keep them in `tools/shots/`, never commit or quote them.

**The phone.** One agent drives the device at a time; the holder is named on the board ("Device:"). Default holder is the
lead. Install builds only with `tools/device.sh install`. Check which window has focus right before every capture.

**Reporting to the owner.** Plain words, short, no jargon without a few words of explanation. End-of-task report is 10
lines or fewer: what changed, how we know (numbers), what the owner must check on the phone (exact steps), decisions,
what is next. Never claim something works until a log, a test or the owner's words confirm it.

## Resource use (owner, 2026-11-07)

The owner's machine runs both agents, one emulator and the builds of every worktree at once. Keep the load down without
skipping any check:
- **One full gate at a time.** Before a full `scripts/check.sh`, check no other one is running (`tasklist | grep java`
  shows Gradle at work, or ask on the thread); iterate with `CHECK_FAST=1`, run the full gate once before reporting.
- **Stop your daemons when a task ends:** `./gradlew --stop` in your worktree. `gradle.properties` runs the Kotlin
  compiler inside Gradle (no separate Kotlin daemon) and lets an idle daemon exit after 10 minutes.
- **Remove a worktree once its branch has landed** (`git worktree remove <path>`; the lead does this at integration).
- **One shared emulator,** started only when a check needs it, named on the board with the device (`Device:`), and shut
  down (`adb -s emulator-5554 emu kill`) by its holder when done. Never start a second one.
- None of this changes what is verified: same gate, same tests, same device checks.

## Environment

Owner's dev machine is Windows (JDK 17, Git Bash); on `engine` Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21, Compose BOM
2026.06.01 (ui 1.11.4) (D-075, D-077); `main` stays on Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21. Test
device: Galaxy S24 (One UI, Android 17), the Android 17 emulator for checks before the phone, a Redmi Note 10 Pro when
connected. CI builds the debug variant on every push to `main` only.

## Commit trailers

Every commit lists both agents when both contributed (the lead adds the other's line when integrating):

```
Co-Authored-By: Claude <noreply@anthropic.com>
Co-Authored-By: Codex <noreply@openai.com>
```
