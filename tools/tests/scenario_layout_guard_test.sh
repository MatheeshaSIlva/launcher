#!/usr/bin/env bash
# B-030 regression test: the shade scenario scripts must read the phone's shade layout and refuse, before any touch,
# unless it is the iOS layout with Notifications on the left (`ios` / `split-top`), the one their fixed positions assume.
# Under the Pixel layout scenario_shade.sh opened Android's shade and its "empty space" tap opened a real notification.
#
# No device is used. Each script runs on a copy of tools/ in a temporary folder, against a fake adb (a bash script on
# $ADB, also first on PATH as `adb`) that answers like the phone: `adb shell CMD` runs CMD with bash, its /sdcard paths
# moved into a fake storage folder, and with device commands of its own (wm, dumpsys, am, input, pidof, logcat,
# screencap, rm). `am broadcast ... DESIGN_RELOAD --es files export` writes dev-out/setup.json and dev-out/.done there as
# the app does (design/DevFiles.kt), in Setup.toJson's shape. Every touch the device would get (input tap, swipe,
# motionevent, draganddrop, roll, press; adb emu event) is logged; keyevents are not touches.
# Faithful to Git Bash: a lone argument starting with "/" handed to the real adb.exe is turned into a Windows path
# unless MSYS_NO_PATHCONV is set, so the fake does the same (such a path is then not found); `adb shell` output carries
# "\r\n" as through a pty. `adb exec-out` has no shell protocol, so as on a real device the device command's stderr
# comes back on the host's stdout, mixed into its output, and adb exits 0 whatever the command's exit code (seen on the
# S24: `exec-out cat` of a missing file printed "cat: ...: No such file or directory" on stdout, rc 0, nothing on
# stderr); `adb shell` (shell protocol v2) keeps stdout, stderr and the exit code apart. `sleep` is a no-op. Scripts'
# output and files stay in the temporary folder.
#
#   tools/tests/scenario_layout_guard_test.sh            exit 0: every case passed; 1: a case failed (listed)
#   VERBOSE=1 tools/tests/scenario_layout_guard_test.sh  also prints each run's output and the fake adb's call log
set -u
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
VERBOSE="${VERBOSE:-}"

# ---------------------------------------------------------------- sandbox: a copy of tools/ (no shots, no tests)
mkdir -p "$WORK/repo"
tar -C "$REPO" -cf - --exclude=tools/shots --exclude=tools/tests --exclude=__pycache__ tools | tar -C "$WORK/repo" -xf -
mkdir -p "$WORK/repo/tools/shots"

# ---------------------------------------------------------------- host-side shims: adb and sleep
BIN="$WORK/bin"; DEVBIN="$WORK/devbin"
mkdir -p "$BIN" "$DEVBIN"

cat > "$BIN/sleep" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF

# python: the scripts' frame-stat readers (tools/framestats.py and its "missed refreshes" summary) do nothing here;
# anything else (a guard reading JSON, say) goes to the real python.
REAL_PYTHON="$(command -v python || command -v python3 || true)"
{
  echo '#!/usr/bin/env bash'
  echo 'case "$1" in */framestats.py|framestats.py) exit 0 ;; esac'
  echo 'if [ "$1" = -c ] && [[ "$2" == *"missed refreshes"* ]]; then cat > /dev/null; exit 0; fi'
  echo "real='$REAL_PYTHON'"
  # (the runs point LOCALAPPDATA away from the real adb; Windows' Python manager looks for its runtimes there)
  echo "export LOCALAPPDATA='${LOCALAPPDATA:-}'"
  echo '[ -n "$real" ] || { echo "python: not found" >&2; exit 127; }'
  echo 'exec "$real" "$@"'
} > "$BIN/python"

cat > "$BIN/adb" <<'EOF'
#!/usr/bin/env bash
# Fake adb for tools/tests/scenario_layout_guard_test.sh. Env: FAKE_LOG, FAKE_STATE, FAKE_MODE, FAKE_DEVBIN.
printf 'adb %s\n' "$*" >> "$FAKE_LOG"
args=("$@")
# Git Bash turns a lone POSIX path argument into a Windows one for a native adb.exe unless told not to.
if [ -z "${MSYS_NO_PATHCONV:-}" ] && [ "${MSYS2_ARG_CONV_EXCL:-}" != "*" ]; then
  for k in "${!args[@]}"; do
    case "${args[$k]}" in
      //*) ;;
      /*) args[$k]="C:/Program Files/Git${args[$k]}" ;;
    esac
  done
fi
set -- "${args[@]}"
while [ $# -gt 0 ]; do                 # global options
  case "$1" in
    -s|-t|-H|-P|-L) shift 2 ;;
    -d|-e) shift ;;
    *) break ;;
  esac
done
storage() {   # device path -> fake storage
  local s="$1"
  s="${s//\/storage\/emulated\/0\//\/sdcard\/}"
  s="${s//\/storage\/self\/primary\//\/sdcard\/}"
  # (a path Git Bash turned into C:/Program Files/Git/sdcard/... keeps its prefix: not found, as on the phone)
  printf '%s' "$s" | sed "s#\(^\|[^A-Za-z0-9_/]\)/sdcard/#\1$FAKE_STATE/sdcard/#g"
}
run_device() {   # $1 = exec-out | shell; rest = the remote command line
  local how="$1"; shift
  local cmd="$*"
  [ -z "$cmd" ] && return 0
  case "$cmd" in *sdcard*|*/storage/*) cmd="$(storage "$cmd")" ;; esac
  local o rc w
  device() {   # the device command; for exec-out its stderr joins its output, as with no shell protocol
    if [ "$how" = exec-out ]; then "$@" 2>&1; else "$@"; fi
  }
  if [[ "$cmd" =~ [\;\|\&\<\>\$\`\(\)\'\"] ]]; then   # a real shell line: run it with bash
    o="$(PATH="$FAKE_DEVBIN:$PATH" device bash -c "$cmd")"; rc=$?
  else   # one plain command (most calls): the device command directly (forks are slow on Windows)
    read -ra w <<< "$cmd"
    if [ -x "$FAKE_DEVBIN/${w[0]}" ]; then o="$(device "$FAKE_DEVBIN/${w[0]}" "${w[@]:1}")"; rc=$?
    else o="$(PATH="$FAKE_DEVBIN:$PATH" device bash -c "$cmd")"; rc=$?; fi
  fi
  if [ -n "$o" ]; then
    if [ "$how" = shell ]; then printf '%s\r\n' "${o//$'\n'/$'\r\n'}"; else printf '%s\n' "$o"; fi
  fi
  [ "$how" = exec-out ] && return 0   # exec-out cannot carry the device's exit code
  return $rc
}
cmd="${1:-}"; shift || true
case "$cmd" in
  shell|exec-out)
    # adb's own `shell -x/-T/-t/-n` options
    while [ $# -gt 0 ]; do case "$1" in -x|-T|-t|-tt|-n) shift ;; *) break ;; esac; done
    run_device "$cmd" "$@"; exit $? ;;
  logcat) PATH="$FAKE_DEVBIN:$PATH" logcat "$@"; exit $? ;;
  pull)
    src=""; dst=""
    for a in "$@"; do case "$a" in -*) ;; *) if [ -z "$src" ]; then src="$a"; else dst="$a"; fi ;; esac; done
    local_src="$(storage "$src")"
    if [ -n "$src" ] && [ -e "$local_src" ]; then
      [ -z "$dst" ] && dst="."
      cp -r "$local_src" "$dst" && echo "$src: 1 file pulled." && exit 0
      exit 1
    fi
    echo "adb: error: failed to stat remote object '$src': No such file or directory" >&2; exit 1 ;;
  push) exit 0 ;;
  emu) echo "TOUCH emu $*" >> "$FAKE_LOG"; echo OK; exit 0 ;;
  devices) printf 'List of devices attached\nFAKE-SERIAL\tdevice\n\n'; exit 0 ;;
  get-state) echo device; exit 0 ;;
  *) exit 0 ;;
esac
EOF

# ---------------------------------------------------------------- device-side commands (run by the fake's `shell`)
cat > "$DEVBIN/input" <<'EOF'
#!/usr/bin/env bash
# A touch is anything but a key or text.
case " $* " in
  *" keyevent "*|*" text "*) echo "KEY input $*" >> "$FAKE_LOG" ;;
  *) echo "TOUCH input $*" >> "$FAKE_LOG" ;;
esac
exit 0
EOF
cat > "$DEVBIN/wm" <<'EOF'
#!/usr/bin/env bash
case "$1" in size) echo "Physical size: 1080x2340" ;; density) echo "Physical density: 480" ;; esac
exit 0
EOF
cat > "$DEVBIN/dumpsys" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  window) echo "  mCurrentFocus=Window{1a2b3c u0 dev.launcher.app/dev.launcher.app.HomeActivity}"
          echo "  mFocusedApp=ActivityRecord{4d5e6f u0 dev.launcher.app/.HomeActivity t12}" ;;
esac
exit 0
EOF
cat > "$DEVBIN/pidof" <<'EOF'
#!/usr/bin/env bash
[ "$1" = dev.launcher.app ] && echo 12345
exit 0
EOF
# rm: as the phone's, except under mode "stuck" an old export's marker cannot be deleted (a file the app's folder
# holds that the shell may not remove), so it is still there after `rm -f`.
{
  echo '#!/usr/bin/env bash'
  echo "real='$(command -v rm)'"
  cat <<'EOF'
if [ "$FAKE_MODE" = stuck ]; then
  for a in "$@"; do
    case "$a" in */dev-out/.done)
      echo "RM-REFUSED $a" >> "$FAKE_LOG"
      echo "rm: $a: Permission denied" >&2; exit 1 ;;
    esac
  done
fi
exec "$real" "$@"
EOF
} > "$DEVBIN/rm"
cat > "$DEVBIN/screencap" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
cat > "$DEVBIN/screenrecord" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
cat > "$DEVBIN/logcat" <<'EOF'
#!/usr/bin/env bash
# The app's own log: the [setup] line it writes at start and on a change (Setup.describe), in the layout in force now.
case "$FAKE_MODE" in
  pixel|stale|stuck) s="status-bar ios (top); shade pixel (together); recents deck (hold); drawer app-library (page-after-last) {columns=2}; home ios (home); settings studio (app)" ;;
  ios) s="status-bar ios (top); shade ios (split-top); recents deck (hold); drawer app-library (page-after-last) {columns=2}; home ios (home); settings studio (app)" ;;
  mirrored) s="status-bar ios (top); shade ios (split-top-mirrored); recents deck (hold); drawer app-library (page-after-last) {columns=2}; home ios (home); settings studio (app)" ;;
  *) exit 0 ;;
esac
echo "10-24 09:00:00.000 12345 12345 I Launcher: BUILD local"
echo "10-24 09:00:00.100 12345 12345 I Launcher: [setup] $s"
exit 0
EOF
cat > "$DEVBIN/am" <<'EOF'
#!/usr/bin/env bash
# Only `am broadcast ... DESIGN_RELOAD ... --es files export` does anything: DevFiles.run("export").
echo "Broadcasting: Intent { flg=0x400000 pkg=dev.launcher.app (has extras) }"
line=" $* "
if [ "$1" = broadcast ] && [[ "$line" == *"dev.launcher.app.DESIGN_RELOAD"* ]] && [[ "$line" == *" files export "* ]]; then
  echo "EXPORT $*" >> "$FAKE_LOG"
  out="$FAKE_STATE/sdcard/Android/data/dev.launcher.app/files/dev-out"
  case "$FAKE_MODE" in
    missing|stale|stuck) ;;   # the app never answers (not running, receiver not registered): nothing is written
    *)
      rm -rf "$out"; mkdir -p "$out/themes"
      echo '{}' > "$out/themes/ios27.json"
      n=1
      if [ "$FAKE_MODE" != nosetup ]; then
        cp "$FAKE_STATE/setup-$FAKE_MODE.json" "$out/setup.json"; n=2
      fi
      echo "$n" > "$out/.done" ;;
  esac
fi
echo "Broadcast completed: result=0"
exit 0
EOF
chmod +x "$BIN"/* "$DEVBIN"/*

# Setup.toJson's shape (JSONObject.toString(2), elements in Element order). Other elements are "ios" too, and the
# mirrored placement contains "split-top": a guard must read the shade's own entry.
setup_json() {   # $1 = shade layout, $2 = its placement
  cat <<EOF
{
  "version": 1,
  "elements": {
    "status-bar": {
      "layout": "ios",
      "placement": "top",
      "options": {}
    },
    "shade": {
      "layout": "$1",
      "placement": "$2",
      "options": {}
    },
    "recents": {
      "layout": "deck",
      "placement": "hold",
      "options": {}
    },
    "drawer": {
      "layout": "app-library",
      "placement": "page-after-last",
      "options": {
        "columns": 2
      }
    },
    "home": {
      "layout": "ios",
      "placement": "home",
      "options": {}
    },
    "settings": {
      "layout": "studio",
      "placement": "app",
      "options": {}
    }
  }
}
EOF
}

# ---------------------------------------------------------------- running a script against the fake
CASE_N=0
new_state() {   # $1 = mode -> sets STATE, LOG
  CASE_N=$((CASE_N + 1))
  STATE="$WORK/state$CASE_N"; LOG="$STATE/adb.log"
  mkdir -p "$STATE/sdcard/Android/data/dev.launcher.app/files"
  : > "$LOG"
  setup_json pixel together > "$STATE/setup-pixel.json"
  setup_json ios split-top > "$STATE/setup-ios.json"
  setup_json ios split-top-mirrored > "$STATE/setup-mirrored.json"
  if [ "$1" = stale ] || [ "$1" = stuck ]; then   # an export from earlier, while the shade was still iOS's
    local out="$STATE/sdcard/Android/data/dev.launcher.app/files/dev-out"
    mkdir -p "$out"; cp "$STATE/setup-ios.json" "$out/setup.json"; echo 2 > "$out/.done"
  fi
}
run_script() {   # $1 = mode, $2 = script under tools/, rest = its arguments -> RC, OUT, LOG
  local mode="$1" script="$2"; shift 2
  new_state "$mode"
  OUT="$STATE/out.txt"
  ( cd "$WORK" && env -u MSYS_NO_PATHCONV -u MSYS2_ARG_CONV_EXCL -u ANDROID_SERIAL \
      PATH="$BIN:$PATH" ADB="$BIN/adb" DEVICE=FAKE-SERIAL N=1 SHOT= SWEEP_CSV= \
      LOCALAPPDATA="$WORK/no-localappdata" \
      FAKE_LOG="$LOG" FAKE_STATE="$STATE" FAKE_MODE="$mode" FAKE_DEVBIN="$DEVBIN" \
      timeout 120 bash "$WORK/repo/tools/$script" "$@" ) > "$OUT" 2>&1 < /dev/null
  RC=$?
}
touches() { grep -c '^TOUCH ' "$LOG"; }

FAILS=0
report() {   # $1 = PASS/FAIL, $2 = case name, rest = detail lines
  local r="$1" name="$2"; shift 2
  echo "$r  $name"
  for l in "$@"; do echo "        $l"; done
  if [ "$r" = FAIL ] || [ -n "$VERBOSE" ]; then
    echo "        --- script output (last 8 lines):"; tail -8 "$OUT" | sed 's/^/        | /'
    echo "        --- fake adb log (first 12 lines):"; head -12 "$LOG" | sed 's/^/        | /'
  fi
  [ "$r" = FAIL ] && FAILS=$((FAILS + 1))
  return 0
}

# Must refuse: non-zero exit, no touch at all, a message about the shade or its layout that gives this case's reason
# ($REASON, an extended regex, case-insensitive): a guard that refuses everything for one wrong reason must not pass.
expect_refused() {   # $1 = case name, $2 = mode, $3 = script, rest = arguments; env REASON
  local name="$1" mode="$2" script="$3"; shift 3
  run_script "$mode" "$script" "$@"
  local t; t=$(touches)
  local how="layout read via: $(grep -q '^EXPORT ' "$LOG" && echo export || echo 'no export')$(grep -q '^adb .*logcat' "$LOG" && echo ', logcat' || true)"
  if [ "$RC" -eq 124 ]; then
    report FAIL "$name" "timed out (120 s) under the fake"
  elif [ "$t" -gt 0 ]; then
    report FAIL "$name" "sent $t touch(es) under shade mode '$mode' (exit $RC); first touches:" \
      "$(grep '^TOUCH ' "$LOG" | head -3 | tr '\n' '|')"
  elif [ "$RC" -eq 0 ]; then
    report FAIL "$name" "no touch, but exited 0 (must refuse with a non-zero exit)"
  elif ! grep -qiE 'shade|layout' "$OUT"; then
    report FAIL "$name" "refused (exit $RC, no touch) but its message names neither the shade nor its layout"
  elif [ -n "${REASON:-}" ] && ! grep -qiE "$REASON" "$OUT"; then
    report FAIL "$name" "refused (exit $RC, no touch) for another reason than this case's (wanted /$REASON/); says:" \
      "$(grep -iE 'shade|layout' "$OUT" | head -1)"
  else
    report PASS "$name" "exit $RC, 0 touches, $how; says: $(grep -iE 'shade|layout' "$OUT" | head -1)"
  fi
}

# Must not be blocked: reaches at least one touch.
expect_touches() {   # $1 = case name, $2 = mode, $3 = script, rest = arguments
  local name="$1" mode="$2" script="$3"; shift 3
  run_script "$mode" "$script" "$@"
  local t; t=$(touches)
  if [ "$RC" -eq 124 ]; then
    report FAIL "$name" "timed out (120 s) under the fake"
  elif [ "$t" -eq 0 ]; then
    report FAIL "$name" "sent no touch under ios/split-top (exit $RC): the guard blocks the right layout"
  else
    report PASS "$name" "$t touches (exit $RC); first: $(grep -m1 '^TOUCH ' "$LOG")"
  fi
}

# ---------------------------------------------------------------- the fake itself must be what the scripts call
new_state ios
( env -u MSYS_NO_PATHCONV PATH="$BIN:$PATH" ADB="$BIN/adb" DEVICE=FAKE-SERIAL LOCALAPPDATA="$WORK/no-localappdata" \
    FAKE_LOG="$LOG" FAKE_STATE="$STATE" FAKE_MODE=ios FAKE_DEVBIN="$DEVBIN" \
    bash "$WORK/repo/tools/device.sh" tap 11 22 ) > /dev/null 2>&1 < /dev/null
if ! grep -q '^TOUCH input tap 11 22$' "$LOG"; then
  echo "BROKEN TEST: tools/device.sh did not reach the fake adb (its \$ADB fallback?). Log:"; cat "$LOG"; exit 2
fi
size=$(env PATH="$BIN:$PATH" FAKE_LOG="$LOG" FAKE_STATE="$STATE" FAKE_MODE=ios FAKE_DEVBIN="$DEVBIN" \
  "$BIN/adb" -s X shell wm size | tail -1 | awk '{print $NF}' | tr -d '\r')
[ "$size" = 1080x2340 ] || { echo "BROKEN TEST: fake wm size gave '$size'"; exit 2; }
export_check=$(env -u MSYS_NO_PATHCONV PATH="$BIN:$PATH" FAKE_LOG="$LOG" FAKE_STATE="$STATE" FAKE_MODE=ios FAKE_DEVBIN="$DEVBIN" \
  bash -c '"$0" -s X shell am broadcast -a dev.launcher.app.DESIGN_RELOAD -p dev.launcher.app --es files export >/dev/null
           MSYS_NO_PATHCONV=1 "$0" -s X shell cat /sdcard/Android/data/dev.launcher.app/files/dev-out/.done' "$BIN/adb" | tr -d '\r')
[ "$export_check" = 2 ] || { echo "BROKEN TEST: fake export gave '.done' = '$export_check'"; exit 2; }
# exec-out as a real device: the device's stderr on stdout, exit code 0 (no shell protocol); shell keeps them apart.
xo=$(env PATH="$BIN:$PATH" FAKE_LOG="$LOG" FAKE_STATE="$STATE" FAKE_MODE=ios FAKE_DEVBIN="$DEVBIN" MSYS_NO_PATHCONV=1 \
  "$BIN/adb" -s X exec-out cat /sdcard/no-such-file 2>/dev/null); xrc=$?
case "$xo" in *"No such file"*) ;; *) echo "BROKEN TEST: fake exec-out kept the device's stderr off stdout ('$xo')"; exit 2 ;; esac
[ "$xrc" = 0 ] || { echo "BROKEN TEST: fake exec-out returned the device's exit code ($xrc); a real one gives 0"; exit 2; }
so=$(env PATH="$BIN:$PATH" FAKE_LOG="$LOG" FAKE_STATE="$STATE" FAKE_MODE=ios FAKE_DEVBIN="$DEVBIN" MSYS_NO_PATHCONV=1 \
  "$BIN/adb" -s X shell cat /sdcard/no-such-file 2>/dev/null); src=$?
{ [ -z "$so" ] && [ "$src" != 0 ]; } || {
  echo "BROKEN TEST: fake shell mixed stderr into stdout ('$so') or lost the exit code ($src)"; exit 2; }

echo "B-030: shade scenario scripts must check the shade layout before touching (fake adb, no device)"

# ---------------------------------------------------------------- cases
# The bug: the phone's shade is Pixel's.
REASON='pixel' expect_refused "scenario_shade.sh under pixel/together"          pixel    scenario_shade.sh
REASON='pixel' expect_refused "scenario_nc_scroll.sh under pixel/together"      pixel    scenario_nc_scroll.sh
REASON='pixel' expect_refused "scenario_sweep.sh shade under pixel/together"    pixel    scenario_sweep.sh shade
# iOS's layout, but Notifications on the right: the scripts' sides are swapped.
REASON='mirrored' expect_refused "scenario_shade.sh under ios/split-top-mirrored"  mirrored scenario_shade.sh
REASON='mirrored' expect_refused "scenario_nc_scroll.sh under ios/split-top-mirrored" mirrored scenario_nc_scroll.sh
REASON='mirrored' expect_refused "scenario_sweep.sh shade under ios/split-top-mirrored" mirrored scenario_sweep.sh shade
# The layout cannot be read: the export never comes (app not answering).
REASON='did not|not answering|not running|never|no (new|fresh) export' expect_refused "scenario_shade.sh, export never completes"       missing  scenario_shade.sh
REASON='did not|not answering|not running|never|no (new|fresh) export' expect_refused "scenario_nc_scroll.sh, export never completes"   missing  scenario_nc_scroll.sh
REASON='did not|not answering|not running|never|no (new|fresh) export' expect_refused "scenario_sweep.sh shade, export never completes" missing  scenario_sweep.sh shade
# The export completes but holds no setup.json.
REASON='setup.json|could not be read|unreadable' expect_refused "scenario_shade.sh, export without setup.json"    nosetup  scenario_shade.sh
# An old export (from when the shade was iOS's) is still on the phone and the new one never comes: not proof.
REASON='did not|not answering|not running|never|no (new|fresh) export' expect_refused "scenario_shade.sh, only a stale iOS export"      stale    scenario_shade.sh
# An old iOS export whose marker cannot be deleted (the phone's rm refuses it): a new export could not be told from it.
REASON='old export|could not be removed|cannot be removed|not (be )?deleted' expect_refused "scenario_shade.sh, old export cannot be removed" stuck    scenario_shade.sh
if [ "$(touches)" -eq 0 ] && [ "$RC" -ne 0 ] && ! grep -q '^RM-REFUSED ' "$LOG"; then
  report FAIL "scenario_shade.sh, old export cannot be removed: the old marker's removal was tried" \
    "the script refused, but never tried to delete the old dev-out/.done (it must, before trusting a new one)"
fi
# The right layout must not be blocked.
expect_touches "scenario_shade.sh under ios/split-top touches"   ios      scenario_shade.sh
expect_touches "scenario_nc_scroll.sh under ios/split-top touches" ios    scenario_nc_scroll.sh
expect_touches "scenario_sweep.sh shade under ios/split-top touches" ios  scenario_sweep.sh shade

echo
if [ "$FAILS" -gt 0 ]; then echo "FAILED: $FAILS case(s)"; exit 1; fi
echo "PASSED"
