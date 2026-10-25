#!/usr/bin/env bash
# The self-diff gate (MF slice 1, D-039): measures the harness's own noise before any diff against a reference is believed.
#
#   tools/fidelity/selfdiff.sh [--out DIR] [--side ours|stock|both] [--reuse DIR]
#
# Per side: session A = capture.sh --state all --theme both --repeat 3; session B = --state quick,expanded --theme both, run
# after the other side's session A (a switch away and back: the accessibility list, tiles and design files all changed in
# between, and every capture.sh run puts the device back at its end). Pairs, every one through diff.py's defaults (must
# exit 0) and headroom.py (overall mean <= 0.2, worst grid cell <= 0.5):
#   in-session   A r1-r2 and r2-r3, for every theme and state
#   cross-session  A r1 against B, for quick and expanded in both themes
# Sanity pairs (the gate must SEE a real difference, and only where it is):
#   light vs dark (quick, A r1): diff.py exit 1 and overall mean > 20
#   tile-off vs tile-on (A r1, each theme): diff.py exit 1, flagged cells, every one inside the tile's region (one cell margin)
#   expanded vs tile-on (A r1, each theme): the same state, must pass diff.py and the headroom
#   quick vs expanded (A r1, each theme): two different stages, diff.py exit 1 and overall mean > 10
# Output: one line per pair with the numbers; exit 0 every pair as it must be, 1 not, 2 usage, no device or a capture
# refused (capture.sh exit 2: nothing was touched, nothing more is run); 130 interrupted (it waits for the running capture
# to put the device back, then stops). DIR (default
# tools/shots/fidelity/selfdiff-<time>) holds the captures (A-<side>, B-<side>) and diffs/<pair>/ (diff.py's report, heatmap
# and worst.png). --reuse DIR judges the captures already in DIR without touching the device.
# [DEVICE=emulator-5554] [ADB=path] as capture.sh.

set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
PYX="$(command -v python3 || command -v python)"
MAX_OVERALL=0.2        # headroom: overall mean at most
MAX_CELL=0.5           # headroom: worst grid cell mean at most
LIGHT_DARK_MIN=20      # sanity: light against dark must differ by more than this overall
QUICK_EXPANDED_MIN=10  # sanity: the quick row against Quick Settings expanded (measured 32-46 on both sides)
TILE_OURS="296,608,233,190"      # the Auto-rotate tile in stage 2 (checked against the captures, see the run log)
TILE_STOCK="296,1030,233,190"
THEMES="light dark"
STATES="quick expanded tile-off tile-on"

OUT=""; SIDES="ours stock"; REUSE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="$2"; shift 2;;
    --side) case "$2" in both) SIDES="ours stock";; ours|stock) SIDES="$2";; *) echo "selfdiff.sh: --side ours|stock|both" >&2; exit 2;; esac; shift 2;;
    --reuse) REUSE="$2"; shift 2;;
    -h|--help) sed -n 2,22p "$0"; exit 0;;
    *) echo "selfdiff.sh: unknown argument '$1'" >&2; exit 2;;
  esac
done
if [ -n "$REUSE" ]; then OUT="$REUSE"; [ -d "$OUT" ] || { echo "selfdiff.sh: $OUT is not a folder" >&2; exit 2; }
else [ -n "$OUT" ] || OUT="$ROOT/tools/shots/fidelity/selfdiff-$(date +%Y%m%d-%H%M%S)"; fi
mkdir -p "$OUT/diffs"

FAILS=0; PAIRS=0; CAPFAIL=""
jget() { "$PYX" -c "import sys, json; d = json.loads(sys.argv[1]); v = d.get(sys.argv[2]); print('' if v is None else v)" "$1" "$2" | tr -d '\r'; }
le() { "$PYX" -c "import sys; sys.exit(0 if float(sys.argv[1]) <= float(sys.argv[2]) else 1)" "$1" "$2"; }
gt() { "$PYX" -c "import sys; sys.exit(0 if float(sys.argv[1]) > float(sys.argv[2]) else 1)" "$1" "$2"; }
verdict() { # PASS|FAIL NAME DETAIL
  PAIRS=$((PAIRS + 1)); [ "$1" = FAIL ] && FAILS=$((FAILS + 1))
  printf '%s  %-34s %s\n' "$1" "$2" "$3"
}
measure() { # NAME A B [REGION]: sets DX (diff.py exit), HJ (headroom json); diff.py's report goes to diffs/NAME
  local name="$1" a="$2" b="$3" region="${4:-}"
  if [ ! -f "$a" ] || [ ! -f "$b" ]; then DX=2; HJ=""; return 1; fi
  "$PYX" "$HERE/diff.py" "$a" "$b" --out "$OUT/diffs/$name" > "$OUT/diffs/$name.txt" 2>&1; DX=$?
  if [ -n "$region" ]; then HJ=$("$PYX" "$HERE/headroom.py" "$a" "$b" --region "$region" 2>/dev/null)
  else HJ=$("$PYX" "$HERE/headroom.py" "$a" "$b" 2>/dev/null); fi
  [ -n "$HJ" ]
}
nums() { echo "overall $(jget "$HJ" overall_mean), worst cell $(jget "$HJ" worst_cell_mean) (col $("$PYX" -c "import sys, json; w = json.loads(sys.argv[1]).get('worst_cell') or {}; print(w.get('col', '-'), 'row', w.get('row', '-'))" "$HJ" | tr -d '\r')), flagged $(jget "$HJ" flagged), diff.py exit $DX"; }

noise_pair() { # NAME A B: must pass diff.py and stay in the headroom
  local name="$1"
  if ! measure "$name" "$2" "$3"; then verdict FAIL "$name" "picture missing or not comparable ($2, $3)"; return; fi
  local o w; o=$(jget "$HJ" overall_mean); w=$(jget "$HJ" worst_cell_mean)
  if [ "$DX" = 0 ] && le "$o" "$MAX_OVERALL" && le "$w" "$MAX_CELL"; then verdict PASS "$name" "$(nums)"
  else verdict FAIL "$name" "$(nums)  [limits: exit 0, overall <= $MAX_OVERALL, worst cell <= $MAX_CELL]"; fi
}
differ_pair() { # NAME A B MIN-OVERALL: must fail diff.py with an overall mean above MIN
  local name="$1"
  if ! measure "$name" "$2" "$3"; then verdict FAIL "$name" "picture missing or not comparable"; return; fi
  if [ "$DX" = 1 ] && gt "$(jget "$HJ" overall_mean)" "$4"; then verdict PASS "$name" "$(nums)  [must differ: exit 1, overall > $4]"
  else verdict FAIL "$name" "$(nums)  [must differ: exit 1, overall > $4]"; fi
}
tile_pair() { # NAME A B REGION: must fail diff.py, with flagged cells, every one inside the region
  local name="$1"
  if ! measure "$name" "$2" "$3" "$4"; then verdict FAIL "$name" "picture missing or not comparable"; return; fi
  local f ob; f=$(jget "$HJ" flagged); ob=$(jget "$HJ" flagged_outside)
  if [ "$DX" = 1 ] && [ "${f:-0}" -gt 0 ] && [ "${ob:-1}" = 0 ]; then verdict PASS "$name" "$(nums), outside the tile $ob  [must differ in the tile only]"
  else verdict FAIL "$name" "$(nums), outside the tile ${ob:-?}  [must differ in the tile only: $4]"; fi
}

# ---------------------------------------------------------------- the captures
cap() { # SESSION SIDE ARGS...
  local sess="$1" side="$2"; shift 2
  echo "[selfdiff] capture $sess $side: $*"
  bash "$HERE/capture.sh" --side "$side" --out "$OUT/$sess-$side" "$@"
  local rc=$?
  if [ "$rc" = 2 ]; then echo "[selfdiff] capture $sess $side REFUSED (exit 2): the device was not touched, nothing more is run"; exit 2; fi
  if [ "$rc" != 0 ]; then echo "[selfdiff] capture $sess $side FAILED (exit $rc)"; CAPFAIL="$CAPFAIL $sess-$side"; fi
}
trap 'echo "[selfdiff] interrupted"; exit 130' INT TERM   # (bash runs it once the capture in front has put the device back)
if [ -z "$REUSE" ]; then
  for s in $SIDES; do cap A "$s" --state all --theme both --repeat 3; done
  for s in $SIDES; do cap B "$s" --state quick,expanded --theme both; done   # after the other side's session A (or its own B)
fi

# ---------------------------------------------------------------- the pairs
for side in $SIDES; do
  A="$OUT/A-$side"; B="$OUT/B-$side"
  tile="$TILE_OURS"; [ "$side" = stock ] && tile="$TILE_STOCK"
  echo "---- $side"
  for th in $THEMES; do
    for st in $STATES; do
      noise_pair "$side-$th-$st-A1-A2" "$A/r1/$side/$th/$st.png" "$A/r2/$side/$th/$st.png"
      noise_pair "$side-$th-$st-A2-A3" "$A/r2/$side/$th/$st.png" "$A/r3/$side/$th/$st.png"
    done
    for st in quick expanded; do
      noise_pair "$side-$th-$st-A1-B" "$A/r1/$side/$th/$st.png" "$B/$side/$th/$st.png"
    done
  done
  echo "---- $side sanity"
  differ_pair "$side-light-vs-dark-quick" "$A/r1/$side/light/quick.png" "$A/r1/$side/dark/quick.png" "$LIGHT_DARK_MIN"
  for th in $THEMES; do
    tile_pair "$side-$th-tile-off-vs-on" "$A/r1/$side/$th/tile-off.png" "$A/r1/$side/$th/tile-on.png" "$tile"
    noise_pair "$side-$th-expanded-vs-tile-on" "$A/r1/$side/$th/expanded.png" "$A/r1/$side/$th/tile-on.png"
    differ_pair "$side-$th-quick-vs-expanded" "$A/r1/$side/$th/quick.png" "$A/r1/$side/$th/expanded.png" "$QUICK_EXPANDED_MIN"
  done
done

echo "[selfdiff] $PAIRS pairs, $FAILS not as they must be; captures and diffs in $OUT"
[ -z "$CAPFAIL" ] || echo "[selfdiff] a capture run failed:$CAPFAIL"
[ "$FAILS" = 0 ] && [ -z "$CAPFAIL" ] && exit 0
exit 1
