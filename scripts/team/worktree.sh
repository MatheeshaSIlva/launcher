#!/usr/bin/env bash
# Give Codex its worktree on the task's branch (docs/team/PROTOCOL.md §4).
#
#   scripts/team/worktree.sh <slug>          -> ../launcher-codex on team/codex/<slug>, branched from engine
#   BASE=<branch> scripts/team/worktree.sh <slug>
#
# Reuses an existing branch with that name (never resets it). Refuses if Codex's worktree has uncommitted changes.
set -euo pipefail

[ $# -eq 1 ] || { sed -n '2,8p' "$0"; exit 2; }
slug=$1
root=$(git rev-parse --show-toplevel)
wt=${CODEX_WORKTREE:-"$root/../launcher-codex"}
base=${BASE:-engine}
branch="team/codex/$slug"

git -C "$root" fetch -q origin "$base" 2>/dev/null || true

if [ ! -d "$wt" ]; then
  if git -C "$root" show-ref -q --verify "refs/heads/$branch"; then
    git -C "$root" worktree add "$wt" "$branch"
  else
    git -C "$root" worktree add -b "$branch" "$wt" "$base"
  fi
else
  [ -z "$(git -C "$wt" status --porcelain)" ] || { echo "Codex's worktree has uncommitted changes: $wt" >&2; exit 1; }
  if git -C "$root" show-ref -q --verify "refs/heads/$branch"; then
    git -C "$wt" checkout -q "$branch"
  else
    git -C "$wt" checkout -q -b "$branch" "$base"
  fi
fi

# Untracked local files the build needs (the SDK path); never committed.
[ -f "$root/local.properties" ] && [ ! -f "$wt/local.properties" ] && cp "$root/local.properties" "$wt/"

echo "Codex worktree: $wt on $(git -C "$wt" rev-parse --abbrev-ref HEAD) at $(git -C "$wt" rev-parse --short HEAD)"
