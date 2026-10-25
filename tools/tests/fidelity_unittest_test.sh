#!/usr/bin/env bash
# MF slice 1: the unit tests of the fidelity tools (tools/fidelity/test_diff.py, test_headroom.py) run by the gate.
# No device. Python 3 only.   tools/tests/fidelity_unittest_test.sh   exit 0: every test passed; 1: one failed
set -u
REPO="$(cd "$(dirname "$0")/../.." && (pwd -W 2>/dev/null || pwd))"   # (pwd -W: a Windows path for Windows' Python)
PY="$(command -v python3 || command -v python || true)"
[ -n "$PY" ] || { echo "fidelity_unittest_test: no python on PATH"; exit 1; }
cd "$REPO" || exit 1
"$PY" -m unittest discover -s tools/fidelity
