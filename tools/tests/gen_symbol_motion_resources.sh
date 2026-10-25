#!/usr/bin/env bash
# B-036 slice 2: regenerates app/src/test/resources/icon_play/*.json, the builder's play for each icon-play fixture and its
# reference values at fixed times (tools/icons/build_icon_set.py --play), which SymbolMotionTest compares the Kotlin evaluator
# with: fixture_on.xml (a delay, an overshoot, a motion path, a morph, a group at scale 0, a stroke and a trim) and
# fixture_clip.xml (a clip-path under a non-root group morphed by a delayed track, two sequential tracks on one property).
# Run it after changing a fixture or the builder's format / evaluator. (Not a *_test.sh: the gate does not run it.)
#
#   tools/tests/gen_symbol_motion_resources.sh
set -eu
REPO="$(cd "$(dirname "$0")/../.." && (pwd -W 2>/dev/null || pwd))"
PY="$(command -v python3 || command -v python)"
OUT="$REPO/app/src/test/resources/icon_play"
mkdir -p "$OUT"
"$PY" -I "$REPO/tools/icons/build_icon_set.py" --play "$REPO/tools/tests/fixtures/icon_play/fixture_on.xml" \
  0,50,100,120,160,200,260,330,400,500 > "$OUT/fixture_play.json"
"$PY" -I "$REPO/tools/icons/build_icon_set.py" --play "$REPO/tools/tests/fixtures/icon_play/fixture_clip.xml" \
  0,50,100,150,200,250,300,400 > "$OUT/fixture_clip_play.json"
echo "wrote $OUT/fixture_play.json and fixture_clip_play.json"
