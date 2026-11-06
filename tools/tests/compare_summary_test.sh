#!/usr/bin/env bash
# T-002 regression test (Codex's cross-check): tools/compare_summary.py must refuse (exit 3) unless every animation the
# chosen scenarios measure is there in every pass of every run, readable, and nothing unexpected is. It once took its
# expectations from the files it found, so an empty run printed "complete". No device: fake run folders, and a stub in
# place of tools/framestats.py (FRAMESTATS) that answers like it, or with nothing for a file holding "BAD".
#   tools/tests/compare_summary_test.sh    exit 0: every case passed; 1: a case failed (listed)
set -u
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cat > "$WORK/stub.py" <<'EOF'
import sys
if "BAD" in open(sys.argv[1]).read(): sys.exit(0)
print("  GPU ms: median 2.00 p90 4.00 max 6.00\n  CPU ms: median 1.00 p90 3.00 max 5.00\n  shown: 72 frames, refresh 8.30 ms, late 1 (missed refreshes ~1)")
EOF
export FRAMESTATS="$WORK/stub.py"
SWITCH="switch_fwd_1 switch_fwd_2 switch_back_1 switch_back_2"
SHADE="s_banner s_cc_open s_cc_close_tap s_cc_edit_in s_cc_edit_out s_cc_close_bar s_nc_open s_nc_close_bar"
SETTINGS="set_open set_page_open set_page_close set_scroll set_page2_open set_page2_close set_close"
full() {   # $1 = run folder: two runs, every scenario, shade 3 passes
  for r in 1-ref 2-new; do
    mkdir -p "$1/$r"
    for s in $SWITCH $SETTINGS; do echo ok > "$1/$r/fs_$s@1.txt"; done
    for s in $SHADE; do for p in 1 2 3; do echo ok > "$1/$r/fs_$s@$p.txt"; done; done
  done
}
failed=""
case_() {   # $1 = name, $2 = expected exit, $3 = run folder, $4 = scenarios
  out=$(cd "$REPO" && python tools/compare_summary.py "$3" "ref new" "$4" 3 2>&1); rc=$?
  if [ "$rc" -ne "$2" ]; then failed="$failed $1"; echo "FAIL $1: exit $rc, wanted $2"; echo "$out" | tail -5
  else echo "ok   $1"; fi
}
full "$WORK/a"; case_ complete 0 "$WORK/a" "switch shade settings"
mkdir -p "$WORK/b"; case_ empty-run 3 "$WORK/b" "switch shade settings"
full "$WORK/c"; rm "$WORK/c/2-new/fs_s_cc_close_bar@2.txt"; case_ one-pass-missing 3 "$WORK/c" "switch shade settings"
full "$WORK/d"; rm "$WORK/d/1-ref/fs_set_scroll@1.txt"; case_ one-step-missing 3 "$WORK/d" "switch shade settings"
full "$WORK/e"; rm "$WORK/e/1-ref/"fs_s_cc_open@*.txt "$WORK/e/2-new/"fs_s_cc_open@*.txt; case_ same-step-missing-everywhere 3 "$WORK/e" "switch shade settings"
full "$WORK/f"; echo BAD > "$WORK/f/2-new/fs_switch_fwd_2@1.txt"; case_ unreadable 3 "$WORK/f" "switch shade settings"
full "$WORK/g"; echo ok > "$WORK/g/1-ref/fs_s_cc_open@4.txt"; case_ extra-pass 3 "$WORK/g" "switch shade settings"
full "$WORK/h"; case_ step-of-unchosen-scenario 3 "$WORK/h" "switch shade"
full "$WORK/i"; case_ unknown-scenario 3 "$WORK/i" "switch shade settings look"
full "$WORK/j"; case_ no-scenario 3 "$WORK/j" ""
full "$WORK/k"; rm -r "$WORK/k/2-new"; case_ run-folder-missing 3 "$WORK/k" "switch shade settings"
[ -z "$failed" ] && { echo "compare_summary_test: all passed"; exit 0; }
echo "compare_summary_test: failed:$failed"; exit 1
