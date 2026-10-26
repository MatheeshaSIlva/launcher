#!/usr/bin/env bash
# Golden pictures of Android's own shade (MF slice 2, D-038, D-041): the 8 stock captures (4 states x light and dark) of one
# Android build, kept in the repo and used as the reference by every later diff.
#
#   tools/fidelity/golden.sh capture [--root DIR]
#   tools/fidelity/golden.sh verify  [--root DIR] [--build ID] [--against DIR2]
#
# capture   runs `capture.sh --side stock --state all --theme both` (the emulator is put back as before, as for any capture),
#           re-encodes each picture losslessly (pngopt.py: it refuses anything that is not exact) into
#           DIR/<ro.build.id>/<theme>/<state>.png and writes DIR/<ro.build.id>/manifest.json (the build, the fingerprint, the
#           setup of the capture, and per picture its bytes, the md5 of its decoded RGB pixels and the md5 of the screencap it
#           came from). DIR defaults to tools/fidelity/golden. It never overwrites: a build that has a manifest is refused
#           (exit 2; remove the folder on purpose to recapture), and the folder appears only when all 8 are done.
# verify    needs no device: the manifest's 8 files exist and are plain 1080x2400 PNGs, each file's bytes and decoded md5 are
#           the manifest's, the folder holds nothing else, and the total is within 4 MB (over it only a warning: report it,
#           never lower the quality). BUILD defaults to the only folder under DIR. --against DIR2 (another capture of the same
#           build, e.g. a second session's) compares every picture's decoded md5 with it (and fails if its notification set differs from the stored one): equal is "identical"; a different md5
#           passes only if diff.py (defaults) exits 0 and headroom.py is within 0.2 overall and 0.5 worst cell, and is listed.
# Exit 0 done / all as it must be; 1 a capture failed or a check did not hold; 2 usage or a precondition not met (nothing was
# touched). EMULATOR ONLY for capture. [DEVICE=emulator-5554] [ADB=path] [GOLDEN_STAGE=DIR for the raw captures].
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=capture.sh
. "$HERE/capture.sh"       # (functions only: a, ash, pyx, winpath, say; its main runs only when it is executed)
set +e
GOLDEN_DEFAULT="$FID_ROOT/tools/fidelity/golden"
STATES_ALL="quick expanded tile-off tile-on"
THEMES_ALL="light dark"
MAX_BYTES=$((4 * 1024 * 1024))
MAX_OVERALL=0.2
MAX_CELL=0.5

usage() { echo "usage: golden.sh capture [--root DIR] | verify [--root DIR] [--build ID] [--against DIR2]" >&2; exit 2; }
refuse_g() { echo "golden.sh REFUSED: $1. Nothing was touched." >&2; exit 2; }
jnum() { "$(command -v python3 || command -v python)" -c "import sys, json; print(json.loads(sys.argv[1]).get(sys.argv[2], ''))" "$1" "$2" | tr -d '\r'; }
le() { "$(command -v python3 || command -v python)" -c "import sys; sys.exit(0 if float(sys.argv[1]) <= float(sys.argv[2]) else 1)" "$1" "$2"; }
fsize() { wc -c < "$1" | tr -d ' \r\n'; }

# ---------------------------------------------------------------- capture
do_capture() {
  local root="$1" build fp stage out tdir theme state n=0 total=0 first=1 bytes md5 srcmd5 res notifs
  case "$DEVICE" in emulator-*) ;; *) refuse_g "DEVICE=$DEVICE is not an emulator-* serial (goldens are captured on the emulator only)";; esac
  [ "$(a get-state 2>/dev/null | tr -d '\r')" = device ] || refuse_g "$DEVICE is not a device in state 'device'"
  build="$(ash getprop ro.build.id | tr -d '\r\n ')"
  [[ "$build" =~ ^[A-Za-z0-9._-]+$ ]] || refuse_g "ro.build.id is '$build': no usable folder name"
  out="$root/$build"
  if [ -e "$out/manifest.json" ] || [ -e "$out" ]; then refuse_g "$out already exists (a golden is never overwritten: remove the folder on purpose to recapture)"; fi
  stage="${GOLDEN_STAGE:-$FID_ROOT/tools/shots/fidelity/golden-$(date +%Y%m%d-%H%M%S)}"
  say "golden capture of build $build into $out (raw captures in $stage)"
  bash "$HERE/capture.sh" --side stock --state all --theme both --out "$stage"
  res=$?
  if [ "$res" = 2 ]; then echo "golden.sh: capture.sh refused (exit 2): nothing was touched, no golden was written" >&2; exit 2; fi
  if [ "$res" != 0 ]; then echo "golden.sh: capture.sh failed (exit $res): no golden was written (the raw captures stay in $stage)" >&2; exit 1; fi
  fp="$(ash getprop ro.build.fingerprint | tr -d '\r\n')"
  notifs="$(manifest_notifs "$stage/manifest.json")"
  if [ -z "$notifs" ]; then echo "golden.sh: the capture's manifest records no notification set: no golden was written (the raw captures stay in $stage)" >&2; exit 1; fi
  tdir="$out.tmp"; rm -rf "$tdir"; mkdir -p "$tdir" || { echo "golden.sh: cannot make $tdir" >&2; exit 1; }
  {
    printf '{\n "tool": "tools/fidelity/golden.sh",\n "side": "stock",\n "date": "%s",\n "build_id": "%s",\n "fingerprint": "%s",\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$build" "$(json_str "$fp")"
    printf ' "capture": "capture.sh --side stock --state all --theme both (setup and fixture in its manifest.json: kept in %s while it lasts)",\n' "tools/shots/fidelity"
    printf ' "tiles_stock": "%s",\n "notifications": "%s",\n "files": [\n' "$STOCK_TILES" "$(json_str "$notifs")"
    for theme in $THEMES_ALL; do
      mkdir -p "$tdir/$theme"
      for state in $STATES_ALL; do
        res="$("$(command -v python3 || command -v python)" "$HERE/pngopt.py" "$(winpath "$stage/stock/$theme/$state.png")" "$(winpath "$tdir/$theme/$state.png")")" || { echo "golden.sh: $theme/$state could not be stored losslessly" >&2; rm -rf "$tdir"; exit 1; }
        res="$(echo "$res" | tr -d '\r')"; srcmd5="$(md5sum "$stage/stock/$theme/$state.png" | cut -d' ' -f1)"
        bytes="$(echo "$res" | awk '{print $2}')"; md5="$(echo "$res" | awk '{print $3}')"
        total=$((total + bytes)); n=$((n + 1))
        [ -n "$first" ] || printf ',\n'; first=""
        printf '  {"path": "%s/%s.png", "theme": "%s", "state": "%s", "size": [1080, 2400], "bytes": %s, "md5": "%s", "source_md5": "%s"}' "$theme" "$state" "$theme" "$state" "$bytes" "$md5" "$srcmd5"
      done
    done
    printf '\n ],\n "total_bytes": %s\n}\n' "$total"
  } > "$tdir/manifest.json" 2>"$tdir/.err"
  if [ "$n" != 8 ] || [ -s "$tdir/.err" ]; then echo "golden.sh: only $n of 8 pictures stored ($(cat "$tdir/.err" 2>/dev/null)): no golden was written" >&2; rm -rf "$tdir"; exit 1; fi
  rm -f "$tdir/.err"
  mv "$tdir" "$out" || { echo "golden.sh: cannot move $tdir to $out" >&2; exit 1; }
  say "golden written: $out (8 pictures, $total bytes = $(awk -v b="$total" 'BEGIN { printf "%.2f", b / 1048576 }') MB)"
  [ "$total" -le "$MAX_BYTES" ] || say "NOTE: over the 4 MB target by $((total - MAX_BYTES)) bytes: report it, the quality is never lowered"
  exit 0
}

# ---------------------------------------------------------------- verify
mfield() { # MANIFEST PATH-VALUE KEY -> the value of KEY in the file entry with that path
  sed -n "s|.*\"path\": \"$2\".*\"$3\": \"\{0,1\}\([^\",}]*\).*|\1|p" "$1" | head -1
}
do_verify() {
  local root="$1" build="$2" other="$3" dtmp dir mf bad=0 total=0 sum n=0 theme state rel got b m dir2 same=0 near=0 py f o w dx hj gn on
  py="$(command -v python3 || command -v python)"; dtmp="$(mktemp -d)"; trap 'rm -rf "$dtmp"' EXIT
  [ -d "$root" ] || refuse_g "$root is not a folder"
  if [ -z "$build" ]; then
    set -- "$root"/*/; [ "$#" = 1 ] && [ -d "$1" ] || refuse_g "no build given and $root does not hold exactly one build folder"
    build="$(basename "$1")"
  fi
  dir="$root/$build"; mf="$dir/manifest.json"
  [ -f "$mf" ] || refuse_g "$mf is not there"
  for theme in $THEMES_ALL; do
    for state in $STATES_ALL; do
      rel="$theme/$state.png"; n=$((n + 1))
      if [ ! -f "$dir/$rel" ]; then echo "FAIL  $rel: missing"; bad=1; continue; fi
      got="$("$py" "$HERE/pngopt.py" --check "$(winpath "$dir/$rel")" --size 1080x2400 2>&1 | tr -d '\r')" || { echo "FAIL  $rel: $got"; bad=1; continue; }
      b="$(echo "$got" | awk '{print $1}')"; m="$(echo "$got" | awk '{print $2}')"
      [ "$m" = "$(mfield "$mf" "$rel" md5)" ] || { echo "FAIL  $rel: decoded md5 $m, the manifest says '$(mfield "$mf" "$rel" md5)'"; bad=1; continue; }
      [ "$b" = "$(mfield "$mf" "$rel" bytes)" ] || { echo "FAIL  $rel: $b bytes, the manifest says '$(mfield "$mf" "$rel" bytes)'"; bad=1; continue; }
      total=$((total + b))
      echo "ok    $rel: $b bytes, md5 $m"
    done
  done
  sum="$(find "$dir" -type f ! -name manifest.json | wc -l | tr -d ' ')"
  [ "$sum" = 8 ] || { echo "FAIL  the folder holds $sum files besides the manifest, not 8"; bad=1; }
  [ "$(sed -n 's/.*"total_bytes": *\([0-9]*\).*/\1/p' "$mf")" = "$total" ] || { echo "FAIL  total_bytes in the manifest is not $total"; bad=1; }
  gn="$(manifest_notifs "$mf")"
  if [ -z "$gn" ]; then echo "FAIL  the manifest records no notification set (recapture the golden: a notification that was there or not changes the Silent section)"; bad=1
  else echo "notifications at capture: $gn"; fi
  echo "total: $total bytes = $(awk -v b="$total" 'BEGIN { printf "%.2f", b / 1048576 }') MB (target at most 4 MB)"
  [ "$total" -le "$MAX_BYTES" ] || echo "NOTE  over the 4 MB target: report it, never lower the quality"
  if [ -n "$other" ]; then
    dir2="$other/$build"
    [ -f "$dir2/manifest.json" ] || { echo "FAIL  --against: $dir2/manifest.json is not there"; exit 1; }
    on="$(manifest_notifs "$dir2/manifest.json")"
    if [ "$on" != "$gn" ]; then
      echo "FAIL  --against: the notifications at capture differ (a notification that arrives or leaves changes the Silent section, so the pictures are of other states). stored: ${gn:-(none)} other: ${on:-(none)}"; bad=1
    fi
    for theme in $THEMES_ALL; do
      for state in $STATES_ALL; do
        rel="$theme/$state.png"
        m="$("$py" "$HERE/pngopt.py" --md5 "$(winpath "$dir/$rel")" 2>/dev/null | tr -d '\r')"; b="$("$py" "$HERE/pngopt.py" --md5 "$(winpath "$dir2/$rel")" 2>/dev/null | tr -d '\r')"
        if [ -n "$m" ] && [ "$m" = "$b" ]; then same=$((same + 1)); continue; fi
        if [ -z "$m" ] || [ -z "$b" ]; then echo "FAIL  $rel: cannot be read in one of the two folders (not compared)"; bad=1; continue; fi
        w="$("$py" "$HERE/diff.py" "$dir/$rel" "$dir2/$rel" --out "$dtmp/$theme-$state" >/dev/null 2>&1; echo $?)"
        hj="$("$py" "$HERE/headroom.py" "$dir/$rel" "$dir2/$rel" 2>/dev/null)"
        o="$(jnum "$hj" overall_mean)"; f="$(jnum "$hj" worst_cell_mean)"
        if [ "$w" = 0 ] && [ -n "$o" ] && le "$o" "$MAX_OVERALL" && le "$f" "$MAX_CELL"; then
          near=$((near + 1)); echo "near  $rel: decoded md5 differs, diff.py exit 0, overall $o, worst cell $f (within $MAX_OVERALL / $MAX_CELL)"
        else
          echo "FAIL  $rel: differs from $dir2 (diff.py exit $w, overall '$o', worst cell '$f')"; bad=1
        fi
      done
    done
    echo "against $other: $same of 8 identical (decoded md5), $near within noise"
  fi
  [ "$bad" = 0 ] && { echo "golden verify: OK ($build)"; exit 0; }
  echo "golden verify: FAILED ($build)"; exit 1
}

# ---------------------------------------------------------------- main
[ $# -ge 1 ] || usage
mode="$1"; shift
ROOT="$GOLDEN_DEFAULT"; BUILD=""; AGAINST=""
while [ $# -gt 0 ]; do
  case "$1" in
    --root) [ $# -ge 2 ] || usage; ROOT="$2"; shift 2;;
    --build) [ $# -ge 2 ] || usage; BUILD="$2"; shift 2;;
    --against) [ $# -ge 2 ] || usage; AGAINST="$2"; shift 2;;
    *) usage;;
  esac
done
case "$mode" in
  capture) [ -z "$BUILD$AGAINST" ] || usage; do_capture "$ROOT";;
  verify) do_verify "$ROOT" "$BUILD" "$AGAINST";;
  *) usage;;
esac
