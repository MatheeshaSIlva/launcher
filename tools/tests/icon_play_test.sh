#!/usr/bin/env bash
# B-036 slice 1: tools/icons/build_icon_set.py exports an animated vector's animation as a "play" block (icon set format 2).
# Checked on a small committed fixture (tools/tests/fixtures/icon_play/fixture_on.xml: a rotation with an overshoot and a
# motion path on one group, a morph, a stroke and trim, a fade on path-data easing), on variants of it that must be refused
# (an interpolator of two cubics, a repeating animation, a non-cubic interpolator, paths that cannot morph, a reference
# nobody inlined...), and on the committed assets/icons/pixel.json (every play ends on its symbol's static look).
# No device, no APK: Python 3 only. The checks are in tools/tests/icon_play_check.py.
#
#   tools/tests/icon_play_test.sh        exit 0: every check passed; 1: one failed (listed)
set -u
REPO="$(cd "$(dirname "$0")/../.." && (pwd -W 2>/dev/null || pwd))"   # (pwd -W: a Windows path for Windows' Python)
PY="$(command -v python3 || command -v python || true)"
[ -n "$PY" ] || { echo "icon_play_test: no python on PATH"; exit 1; }
"$PY" -I "$REPO/tools/tests/icon_play_check.py" "$REPO"
