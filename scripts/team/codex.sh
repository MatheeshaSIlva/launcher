#!/usr/bin/env bash
# Ask Codex for one round of a team thread and append its answer to the thread (docs/team/PROTOCOL.md §3).
#
#   scripts/team/codex.sh <thread-or-review-file> <mode> "<what you want this round>"
#
# Modes: discuss | review | milestone   (read-only sandbox: Codex reads and answers, changes nothing)
#        work | signoff                 (workspace-write in Codex's worktree, network on for Gradle)
#
# Env: CODEX_WORKTREE (default ../launcher-codex), CODEX_MODEL (optional), CODEX_EXTRA (extra args for codex exec).
set -euo pipefail

[ $# -ge 3 ] || { sed -n '2,10p' "$0"; exit 2; }
root=$(git rev-parse --show-toplevel)
file=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
mode=$2
ask=$3
wt=${CODEX_WORKTREE:-"$root/../launcher-codex"}

[ -f "$file" ] || { echo "no such file: $file" >&2; exit 2; }
command -v codex >/dev/null || { echo "codex CLI not found (npm i -g @openai/codex; codex login)" >&2; exit 2; }
[ -d "$wt" ] || { echo "no Codex worktree at $wt: run scripts/team/worktree.sh <slug> first" >&2; exit 2; }

case "$mode" in
  discuss|review|milestone) sandbox=(-s read-only) ;;
  work|signoff) sandbox=(-s workspace-write -c sandbox_workspace_write.network_access=true) ;;
  *) echo "unknown mode: $mode" >&2; exit 2 ;;
esac

branch=$(git -C "$wt" rev-parse --abbrev-ref HEAD)

case "$mode" in
  discuss)   job="Read the whole thread. Answer the open discussion round as a teammate: say what you agree with, what you would change and why (cite files, lines, measurements, spec sections or decisions), and what is missing. If the approach is ready, end with the line 'codex: AGREE approach — <one line>'. Do not write code." ;;
  review)    job="Cross-check the lead's work named in the thread: read its report and its diff (git log/diff of the commits it lists, from the main repo at $root). Give findings as 'fix', 'question' or 'note' with file:line. Answer the security checklist (PROTOCOL.md §7) if the change touches the attack surface. Do not change files." ;;
  milestone) job="Write your blind milestone review following the template sections in the file. Do NOT read or rely on the lead's section of this file; write yours from the code, the docs and the measurements. Do not change files." ;;
  work)      job="Do the sub-tasks the split (§4) assigns to codex, only in the files it assigns you, on branch $branch in your worktree. Run scripts/check.sh (CHECK_FAST=1 is fine while iterating, the full run before you report). Commit with clear messages and the trailer 'Co-Authored-By: Codex <noreply@openai.com>'. Do not push. Then write your report in the template's format: commits, what changed, verified (measured/judged, with numbers), not verified, surprises." ;;
  signoff)   job="Check whether the findings addressed to you are fixed (fix them on $branch if they are yours) and whether the lead fixed the ones addressed to it. Then sign: 'codex: AGREE — <what you checked>' or 'codex: OBJECT — <what is still wrong>'." ;;
esac

prompt=$(cat <<EOF
You are Codex, the teammate on a two-agent team with Claude Code as lead, working for the owner, Matheesha.
AGENTS.md in this worktree is the shared brief; follow it. The team protocol is docs/team/PROTOCOL.md: read it.

This round:
- File: $file (it lives in the lead's main worktree at $root; read it there, it is newer than your copy).
- Mode: $mode. Your worktree: $wt, branch $branch.
- What the lead asks: $ask

Your job in this mode: $job

Hard rules: never edit the thread, the board, DECISIONS.md or anything in the lead's worktree; the script records your
answer. Never push. Never touch files the split does not give you. Never use the phone unless the board says
"Device: codex". If you cannot do something, say so plainly and why.

Your final message is recorded verbatim in the thread under a dated "### codex" heading. Write it as that entry:
markdown, no preamble, no sign-off pleasantries.
EOF
)

out=$(mktemp)
model=(); [ -n "${CODEX_MODEL:-}" ] && model=(-m "$CODEX_MODEL")
extra=(); [ -n "${CODEX_EXTRA:-}" ] && read -r -a extra <<<"$CODEX_EXTRA"

if codex exec --help 2>/dev/null | grep -q -- '--output-last-message'; then
  codex exec -C "$wt" "${sandbox[@]}" ${model[@]+"${model[@]}"} ${extra[@]+"${extra[@]}"} --output-last-message "$out" "$prompt" >&2 || rc=$?
else
  codex exec -C "$wt" "${sandbox[@]}" ${model[@]+"${model[@]}"} ${extra[@]+"${extra[@]}"} "$prompt" | tee "$out" >&2 || rc=$?
fi
rc=${rc:-0}

stamp=$(date -u +%Y-%m-%dT%H:%MZ)
entry=$(mktemp)
trap 'rm -f "$out" "$entry"' EXIT
if [ "$rc" -ne 0 ] || [ ! -s "$out" ]; then
  printf '### codex — %s, %s\n\ncodex: UNAVAILABLE (codex exec exited %s; see protocol §8)\n' "$mode" "$stamp" "$rc" >"$entry"
else
  { printf '### codex — %s, %s\n\n' "$mode" "$stamp"; cat "$out"; printf '\n'; } >"$entry"
fi

# Put the entry at the end of the section its mode belongs to (thread template §2/§5/§6/§7, review template's
# "codex (blind)"); append at the end of the file if that heading is missing.
case "$mode" in
  discuss) hdr='## 2.' ;; work) hdr='## 5.' ;; review) hdr='## 6.' ;; signoff) hdr='## 7.' ;; milestone) hdr='## codex' ;;
esac
# The LAST such heading: a thread with rounds (# Round 2) repeats the sections, and the open round is the last one.
last=$(grep -n "^$hdr" "$file" | tail -1 | cut -d: -f1)
awk -v start="${last:-0}" -v ef="$entry" '
  BEGIN { while ((getline l < ef) > 0) e = e l "\n" }
  start > 0 && NR > start && /^#{1,2} / && !done { printf "%s\n", e; done = 1 }
  { print }
  END { if (!done) printf "\n%s", e }
' "$file" >"$file.tmp" && mv "$file.tmp" "$file"

if [ "$rc" -ne 0 ] || [ ! -s "$out" ]; then
  echo "Codex did not answer (exit $rc). Recorded as UNAVAILABLE in $file." >&2
  exit 1
fi
cat "$out"
