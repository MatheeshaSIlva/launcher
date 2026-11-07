#!/usr/bin/env bash
# Deterministic status-bar set and analytic geometry; no device or downloads.
set -eu
REPO="$(cd "$(dirname "$0")/../.." && (pwd -W 2>/dev/null || pwd))"
PY="$(command -v python3 || command -v python || true)"
[ -n "$PY" ] || { echo "statusbar_set_test: no python on PATH"; exit 1; }
cd "$REPO"
"$PY" -m unittest discover -s tools/icons -p test_statusbar_set.py -v
