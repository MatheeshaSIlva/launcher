#!/usr/bin/env bash
# Stop hook: stop Claude from declaring "done" while the quality gate is red.
# Exit 2 sends the failure output back to Claude so it keeps working.
# Bypass for one session: CLAUDE_SKIP_STOP_CHECK=1 claude

input=$(cat)

# Loop guard: if we already blocked this stop once, let it through.
if printf '%s' "$input" | grep -Eq '"stop_hook_active"[[:space:]]*:[[:space:]]*true'; then
  exit 0
fi

[ "${CLAUDE_SKIP_STOP_CHECK:-0}" = "1" ] && exit 0

cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
[ -x scripts/check.sh ] || exit 0
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || exit 0

# Only gate when something other than docs/config changed.
changed=$(git status --porcelain | awk '{print $NF}' | grep -Ev '^(docs/|\.claude/)|\.md$' || true)
[ -n "$changed" ] || exit 0

# Skip while another check runs (scripts/check.local.sh leaves its pid in the lock folder): a second one beside it only slows
# both. The hook runs the fast check (build, lint, unit tests); the full one, with the shell tests, runs before every push.
lockdir="${TMPDIR:-/tmp}/launcher-check.lock"
for f in "$lockdir"/*; do
  [ -e "$f" ] || continue
  if kill -0 "${f##*/}" 2>/dev/null; then echo "Stop hook: a check is already running (pid ${f##*/}); skipped." >&2; exit 0; fi
  rm -f "$f"   # (a check that died without cleaning up)
done

out=$(CHECK_FAST=1 scripts/check.sh 2>&1)
rc=$?
if [ "$rc" -ne 0 ]; then
  {
    echo "Quality gate FAILED (scripts/check.sh exited $rc). Fix this before declaring the work done. Last output:"
    printf '%s\n' "$out" | tail -n 40
  } >&2
  exit 2
fi
exit 0
