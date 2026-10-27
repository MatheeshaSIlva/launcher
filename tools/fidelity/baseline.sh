#!/usr/bin/env bash
# The fidelity baseline (MF slice 3): ours' Pixel shade against the stock goldens, all 8 pictures (4 states x light and dark),
# with the masks and regions generated from stock's uiautomator trees, scored in one table.
#
#   tools/fidelity/baseline.sh --out DIR [--golden DIR] [--masks FILE] [--regions FILE] [--trees DIR]
#   tools/fidelity/baseline.sh --out DIR --ours-from CAPTURE_DIR --build ID     (no device: scores a capture made earlier)
#
# What it does: capture.sh --side ours --state all --theme both --font system (ours on the system font, the tile list as
# slice 2 sets it; the device is put back as for any capture) into DIR/capture; checks that ours' notification set is the
# golden's (a different set changes the Silent section: no baseline then) and that its torch (the cameras with a flash unit,
# B-063) is the golden's (else no baseline: ours' Flashlight tile follows it); runs diff.py (defaults) on each of the 8 pairs with
# the state's masks (maskgen.py pick of masks/pixel-system-font.json) and regions (masks/pixel-regions.json) into
# DIR/reports/<theme>/<state>/ (report.json, heatmap.png, worst.png when a cell flags); then scores.py writes DIR/scores.md and
# DIR/scores.json and prints the table with every flagged cluster named by the tightest stock node around it.
# Defaults: GOLDEN = tools/fidelity/golden/<ro.build.id>, TREES = tools/fidelity/masks/stock-trees/<build>.
# Exit 0 the run completed (a diff over tolerance is the EXPECTED result of a baseline and is printed as such); 1 a capture or a
# comparison failed (no table); 2 usage or a precondition not met (nothing was touched). EMULATOR ONLY unless --ours-from.
# [DEVICE=emulator-5554] [ADB=path] [GOLDEN_STAGE ignored]. Sourceable functions come from capture.sh.
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=capture.sh
. "$HERE/capture.sh"
set +e
STATES_ALL="quick expanded tile-off tile-on"
THEMES_ALL="light dark"

usage() { echo "usage: baseline.sh --out DIR [--golden DIR] [--masks FILE] [--regions FILE] [--trees DIR] [--ours-from CAPTURE_DIR --build ID]" >&2; exit 2; }
refuse_b() { echo "baseline.sh REFUSED: $1. Nothing was touched." >&2; exit 2; }
fail_b() { echo "baseline.sh FAILED: $1" >&2; exit 1; }

OUT=""; GOLDEN=""; MASKS="$HERE/masks/pixel-system-font.json"; REGIONS="$HERE/masks/pixel-regions.json"; TREES=""; FROM=""; BUILD=""
while [ $# -gt 0 ]; do
  case "$1" in
    --out) [ $# -ge 2 ] || usage; OUT="$2"; shift 2;;
    --golden) [ $# -ge 2 ] || usage; GOLDEN="$2"; shift 2;;
    --masks) [ $# -ge 2 ] || usage; MASKS="$2"; shift 2;;
    --regions) [ $# -ge 2 ] || usage; REGIONS="$2"; shift 2;;
    --trees) [ $# -ge 2 ] || usage; TREES="$2"; shift 2;;
    --ours-from) [ $# -ge 2 ] || usage; FROM="$2"; shift 2;;
    --build) [ $# -ge 2 ] || usage; BUILD="$2"; shift 2;;
    *) usage;;
  esac
done
[ -n "$OUT" ] || usage
PY="$(command -v python3 || command -v python)"; [ -n "$PY" ] || refuse_b "no python on PATH"
"$PY" -c "import PIL" 2>/dev/null || refuse_b "Pillow is not installed (pip install pillow)"
if [ -z "$FROM" ]; then
  case "$DEVICE" in emulator-*) ;; *) refuse_b "DEVICE=$DEVICE is not an emulator-* serial (the baseline is emulator-only)";; esac
  [ "$(a get-state 2>/dev/null | tr -d '\r')" = device ] || refuse_b "$DEVICE is not a device in state 'device'"
  [ -n "$BUILD" ] || BUILD="$(ash getprop ro.build.id | tr -d '\r\n ')"
else
  [ -d "$FROM/ours" ] || refuse_b "$FROM/ours is not there (a capture.sh --side ours output)"
  [ -n "$BUILD" ] || refuse_b "--ours-from needs --build ID (which golden to compare with)"
fi
[ -n "$GOLDEN" ] || GOLDEN="$FID_ROOT/tools/fidelity/golden/$BUILD"
[ -n "$TREES" ] || TREES="$HERE/masks/stock-trees/$BUILD"
[ -f "$GOLDEN/manifest.json" ] || refuse_b "no golden for build $BUILD ($GOLDEN/manifest.json): tools/fidelity/golden.sh capture"
for f in "$MASKS" "$REGIONS"; do [ -f "$f" ] || refuse_b "$f is not there (maskgen.py generate)"; done
[ -d "$TREES" ] || refuse_b "no stock trees for build $BUILD ($TREES)"
[ -z "$FROM" ] && [ -e "$OUT/capture/manifest.json" ] && refuse_b "$OUT already holds a capture"
mkdir -p "$OUT" || refuse_b "cannot make $OUT"

# ---------------------------------------------------------------- 1. the capture (ours)
if [ -z "$FROM" ]; then
  CAP="$OUT/capture"
  bash "$HERE/capture.sh" --side ours --state all --theme both --font system --out "$CAP" 2>&1 | tee "$OUT/capture.log"
  rc="${PIPESTATUS[0]}"
  [ "$rc" != 2 ] || { echo "baseline.sh: capture.sh refused (exit 2): nothing was touched" >&2; exit 2; }
  [ "$rc" = 0 ] || fail_b "capture.sh exited $rc (see $OUT/capture.log): no table"
else
  CAP="$FROM"
fi
# the notification set: ours' against the golden's (capture.sh warns; here it is a stop)
gn="$(manifest_notifs "$GOLDEN/manifest.json")"; on="$(manifest_notifs "$CAP/manifest.json")"
[ -n "$gn" ] || fail_b "the golden records no notification set"
if fk="$(foreign_in "$gn")"; then fail_b "the golden's notification set holds another app's notification ($fk): recapture it (golden.sh capture; other apps' notifications are snoozed, B-062)"; fi
if fk="$(foreign_in "$on")"; then fail_b "ours' notification set holds another app's notification ($fk): no baseline (capture.sh snoozes other apps' notifications since B-062: a capture with one is of another state)"; fi
[ "$gn" = "$on" ] || fail_b "ours' notification set differs from the golden's: a Silent-section difference would come from that. golden: $gn ours: $on"
# the torch (B-063, D-046): which cameras report a flash unit decides whether ours shows the Flashlight tile; compared only when equal
gt="$(manifest_torch "$GOLDEN/manifest.json")"; ot="$(manifest_torch "$CAP/manifest.json")"
[ -n "$gt" ] || fail_b "the golden records no torch (the cameras with a flash unit at its capture, B-063): golden.sh capture writes it; recapture the golden"
torch_valid "$gt" || fail_b "the golden's torch is '$gt' (not none or a list of facings): recapture the golden"
[ -n "$ot" ] || fail_b "ours' capture records no torch: made before B-063: recapture"
torch_valid "$ot" || fail_b "ours' torch is '$ot' (not none or a list of facings: unread, or it changed during the capture): no baseline; recapture"
[ "$gt" = "$ot" ] || fail_b "the flash units differ (dumpsys media.camera; ours' Flashlight tile is there or not by them, so the expanded states would differ for that reason): golden: $gt ours: $ot. Cold-boot the emulator until dumpsys media.camera matches the golden's, or recapture the golden on this boot"
if [ -f "$OUT/capture.log" ] && grep -q 'WARNING' "$OUT/capture.log"; then fail_b "capture.sh warned (see $OUT/capture.log): no table"; fi
for t in $THEMES_ALL; do for s in $STATES_ALL; do
  [ -f "$CAP/ours/$t/$s.png" ] || fail_b "$CAP/ours/$t/$s.png is missing"
  [ -f "$GOLDEN/$t/$s.png" ] || fail_b "$GOLDEN/$t/$s.png is missing"
done; done

# ---------------------------------------------------------------- 2. the diffs
TMPB="$(mktemp -d)"; trap 'rm -rf "$TMPB"' EXIT
over=0; : > "$OUT/diff.log"
for t in $THEMES_ALL; do for s in $STATES_ALL; do
  "$PY" "$HERE/maskgen.py" pick "$(winpath "$MASKS")" "$s.png" > "$TMPB/m.json" || fail_b "no masks for $s.png in $MASKS"
  "$PY" "$HERE/maskgen.py" pick "$(winpath "$REGIONS")" "$s.png" > "$TMPB/r.json" || fail_b "no regions for $s.png in $REGIONS"
  "$PY" "$HERE/diff.py" "$(winpath "$GOLDEN/$t/$s.png")" "$(winpath "$CAP/ours/$t/$s.png")" --mask-file "$(winpath "$TMPB/m.json")" --regions "$(winpath "$TMPB/r.json")" \
      --out "$(winpath "$OUT/reports/$t/$s")" 2>&1 | tr -d '\r' | sed "s|^|$t/$s: |" | tee -a "$OUT/diff.log"
  drc="${PIPESTATUS[0]}"
  case "$drc" in 0) ;; 1) over=$((over + 1));; *) fail_b "diff.py could not compare $t/$s (exit $drc)";; esac
done; done

# ---------------------------------------------------------------- 3. the scores
"$PY" "$HERE/scores.py" "$(winpath "$OUT/reports")" --golden "$(winpath "$GOLDEN")" --ours "$(winpath "$CAP/ours")" --masks "$(winpath "$MASKS")" \
    --trees "$(winpath "$TREES")" --md "$(winpath "$OUT/scores.md")" --json "$(winpath "$OUT/scores.json")" || fail_b "scores.py failed (see above)"
echo
echo "[baseline] done: $over of 8 pictures are over tolerance (the expected baseline result: each flagged cluster above is a finding). Reports: $OUT/reports, table: $OUT/scores.md"
exit 0
