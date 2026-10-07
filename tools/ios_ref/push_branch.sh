#!/usr/bin/env bash
# Commits the iOS reference capture files onto the ios-reference branch and pushes it (which starts the capture run),
# without touching the working tree, the index or main: the branch is origin/main plus these files.
#   tools/ios_ref/push_branch.sh "message"
set -e
cd "$(dirname "$0")/../.."
msg=${1:-"iOS reference capture: update"}
git fetch -q origin
idx="${TMPDIR:-${TEMP:-/tmp}}/iosref.idx"
rm -f "$idx"
parent=$(git rev-parse -q --verify origin/ios-reference || git rev-parse origin/main)
GIT_INDEX_FILE="$idx" git read-tree "$parent"
GIT_INDEX_FILE="$idx" git add .gitignore .github/workflows/ios-reference.yml ios-reference tools/ios_ref
GIT_INDEX_FILE="$idx" git update-index --chmod=+x ios-reference/capture.sh tools/ios_ref/push_branch.sh
tree=$(GIT_INDEX_FILE="$idx" git write-tree)
if [ "$tree" = "$(git rev-parse "$parent^{tree}")" ]; then echo "nothing changed"; exit 0; fi
commit=$(printf '%s\n\nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>\n' "$msg" | git commit-tree "$tree" -p "$parent")
git push -q origin "$commit:refs/heads/ios-reference"
echo "pushed $commit"
