#!/usr/bin/env bash
# MF slice 1 test (D-025 pattern): tools/fidelity/capture.sh against a fake adb, no device.
#
# The fake adb (a bash script on $ADB, state in files under a folder per scenario) answers like the emulator: settings
# (get/put/delete/list per namespace), getprop, wm, dumpsys (window focus and displays, the strip window while our
# accessibility service is listed, notification records, battery), cmd (alarm set-time, statusbar expand-*/collapse/set-tiles,
# uimode night), am (TEST_SHADE, TEST_NOTIFY, SETUP, DESIGN_RELOAD export/import/remove/theme), pm, ps, push/pull on a fake
# sdcard, exec-out screencap (a 1080x2400 PNG header; changing every call in mode "notrest"). Every call that would change the
# device is logged as `MUT ...`; "nothing was touched" means no MUT line. `sleep` is a no-op; a hung command (the SIGTERM and
# kill cases) really sleeps. Each scenario has its own device folder and runs in the background: the cases overlap.
#
# Also the modem (adb emu gsm voice|data: unregistered during a run, as it was after: the emulator's signal bars swing otherwise).
# Cases: preconditions refuse with no mutating call (10 reasons, incl. a `dirty` marker); --dry-run touches nothing; a full
# ours run and a stock run put everything back (other accessibility services kept, user-pixel.json merged and restored byte for
# byte or removed when it was absent, settings, tiles, night mode, clock, battery, setup and theme); not at rest exits 1 with
# "NOT AT REST" and still restores; SIGTERM mid-run restores; a SIGKILL leaves `dirty`, a new run is refused, --restore puts it
# back.   tools/tests/fidelity_capture_test.sh   exit 0 all passed, 1 a case failed (listed)   VERBOSE=1 prints each run
set -u
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
VERBOSE="${VERBOSE:-}"
# the fake's sleep takes no time, so a poll that never succeeds (the strip that never comes) costs a fake-adb call per try:
# its bound is scaled down (capture.sh's POLL_SCALE; every poll still tries at least once, the retries still run)
POLL_SCALE="${POLL_SCALE:-0.1}"
export REAL_SLEEP="$(command -v sleep)"
BIN="$WORK/bin"; mkdir -p "$BIN"

printf '#!/usr/bin/env bash\nexit 0\n' > "$BIN/sleep"

cat > "$BIN/adb" <<'EOF'
#!/usr/bin/env bash
# Fake adb for tools/tests/fidelity_capture_test.sh. Env: FAKE_STATE.
D="$FAKE_STATE/dev"; LOGF="$FAKE_STATE/adb.log"
APP="$D/sdcard/Android/data/dev.launcher.app/files"
HOME_W="dev.launcher.app/dev.launcher.app.HomeActivity"
OURS="dev.launcher.app/dev.launcher.app.NavAccessibilityService"
echo "adb $*" >> "$LOGF"
# every call must name the emulator (`-s emulator-*`): one without would hit whatever device adb picks (the S24 may be plugged in)
if [ "${1:-}" != -s ] || [[ "${2:-}" != emulator-* ]]; then echo "NOSERIAL $*" >> "$LOGF"; fi
while [ $# -gt 0 ]; do case "$1" in -s) shift 2;; *) break;; esac; done
mut() { echo "MUT $*" >> "$LOGF"; }
rd() { read -r "$1" < "$2"; }   # (no subshell: a fork costs ~60 ms here)
sget() { if [ -f "$D/s/$1/$2" ]; then rd _v "$D/s/$1/$2"; echo "$_v"; else echo null; fi; }
sput() { mkdir -p "$D/s/$1"; printf '%s' "$3" > "$D/s/$1/$2"; }
hang_if() {   # a one-shot hang where the scenario asked for it ($D/hang holds the place)
  [ -f "$D/hang" ] || return 0
  rd _h "$D/hang"; [ "$_h" = "$1" ] || return 0
  rm -f "$D/hang"; echo "$BASHPID" > "$D/hang.pid"; : > "$D/hanging"
  exec "$REAL_SLEEP" 120
}
write_setup() { printf '{"version": 1, "elements": {"shade": {"layout": "%s", "placement": "%s", "options": {}}}}' "$1" "$2" > "$APP/setup.json"; }
settings() {
  case "$1" in
    get) sget "$2" "$3";;
    put) mut settings put "$2" "$3" "$4"; sput "$2" "$3" "$4"
         if [ "$2:$3:$4" = global:auto_time:1 ]; then rm -f "$D/clock_ms"; fi;;
    delete) mut settings delete "$2" "$3"; rm -f "$D/s/$2/$3";;
    list) local f; for f in "$D/s/$2"/*; do [ -f "$f" ] && printf '%s=%s\n' "${f##*/}" "$(<"$f")"; done;;
  esac
}
getprop() {
  case "$1" in
    ro.build.version.sdk) rd _v "$D/sdk"; echo "$_v";;
    persist.sys.locale|ro.product.locale) echo en-US;;
    ro.build.fingerprint) echo "fake/android17/user";;
    persist.sys.timezone) echo UTC;;
  esac
}
wm() { case "$1" in size) rd _v "$D/size"; echo "Physical size: $_v";; density) echo "Physical density: 420";; esac; }
pm() { echo "package:dev.launcher.app"; }
ps() { local i n; rd n "$D/shizuku"; for ((i = 0; i < n; i++)); do echo "shell  $((300 + i))  1  0 0 0 0 S shizuku_server"; done; }
date() {
  case "${1:-}" in
    +%z) echo +0000;;
    +%H:%M) if [ -f "$D/clock_ms" ]; then rd _v "$D/clock_ms"; command date -u -d "@$(( _v / 1000 ))" +%H:%M; else echo 09:41; fi;;
    *) command date -u;;
  esac
}
dumpsys() {
  case "$1:${2:-}" in
    window:displays) echo "  mRotation=0";;
    window:windows) [ -f "$D/nostrip" ] && return 0   # (B-018: the service is bound, the strip never comes)
                    [[ "$(sget secure enabled_accessibility_services)" == *"$OURS"* ]] && echo "    Window{1 u0 LauncherGestureStrip}";;
    window:) rd _v "$D/focus"; echo "  mCurrentFocus=Window{1a2b u0 $_v}";;
    notification:*)
      echo "  NotificationRecord(0x1: pkg=dev.launcher.app user=UserHandle{0} id=7 tag=null importance=2 key=x|channel=safety)"
      local f; for f in "$D"/notif_*; do
        [ -f "$f" ] || continue
        echo "  NotificationRecord(0x2: pkg=dev.launcher.app user=UserHandle{0} id=$((1000 + ${f##*notif_})) tag=null importance=3 key=y|channel=test)"
      done;;
    battery:*) mut dumpsys battery "${@:2}"; if [ "${2:-}" = reset ]; then rm -f "$D/battery"; else echo "$*" >> "$D/battery"; fi;;
  esac
}
cmd() {
  case "$1" in
    alarm) mut cmd alarm "$2" "${3:-}"; printf '%s' "$3" > "$D/clock_ms";;
    statusbar)
      case "$2" in
        expand-notifications|expand-settings) hang_if expand; echo "NotificationShade" > "$D/focus";;
        collapse) echo "$HOME_W" > "$D/focus";;
        set-tiles) mut cmd statusbar set-tiles "$3"; sput secure sysui_qs_tiles "$3";;
      esac;;
    uimode)
      if [ $# -ge 3 ]; then mut cmd uimode night "$3"; printf '%s' "$3" > "$D/night"; else rd _v "$D/night"; echo "Night mode: $_v"; fi;;
  esac
}
app_export() { rm -rf "$APP/dev-out"; mkdir -p "$APP/dev-out/design"; cp "$APP/setup.json" "$APP/dev-out/"; cp "$APP"/design/* "$APP/dev-out/design/" 2>/dev/null; echo 2 > "$APP/dev-out/.done"; }
app_import() {
  local f rel
  [ -d "$APP/dev-in" ] || return 0
  while IFS= read -r f; do
    rel="${f#"$APP/dev-in/"}"; mkdir -p "$APP/$(dirname "$rel")"; cp "$f" "$APP/$rel"
    echo "IMPORT $rel $(<"$f")" >> "$LOGF"
  done < <(find "$APP/dev-in" -type f)
  rm -rf "$APP/dev-in"
}
extra() { local v="${line##*"--$2 $1 "}"; echo "${v%% *}"; }
am() {
  case "$1" in
    start) mut am "$@"; echo "$HOME_W" > "$D/focus";;
    broadcast)
      mut am "$@"; line=" $* "
      case "$line" in
        *DESIGN_RELOAD*)
          case "$line" in
            *" files export "*) app_export;;
            *" files import "*) app_import;;
            *" files remove "*) rm -f "$APP/$(extra path es)";;
            *" --es theme "*) printf '%s' "$(extra theme es)" > "$APP/design/theme.txt";;
          esac;;
        *dev.launcher.app.SETUP*) write_setup "$(extra layout es)" "$(extra placement es)";;
        *TEST_SHADE*)
          case "$(extra do es)" in
            open) hang_if open; echo "LauncherFocus" > "$D/focus";;
            close) echo "$HOME_W" > "$D/focus";;
          esac;;
        *TEST_NOTIFY*)
          local id; id="$(extra id ei)"
          if [[ "$line" == *" --ez cancel true "* ]]; then rm -f "$D/notif_$id"; else : > "$D/notif_$id"; fi;;
      esac;;
  esac
}
rm() { mut rm "$@"; command rm "$@"; }
mkdir() { mut mkdir "$@"; command mkdir "$@"; }
shot() {
  cat "$FAKE_STATE/shot.png"
  [ -f "$D/notrest" ] && echo "$RANDOM$RANDOM$RANDOM"
}
case "${1:-}" in
  get-state) echo device;;
  shell) shift; cmdline="${*//\/sdcard\//$D/sdcard/}"; eval "$cmdline"; exit $?;;
  exec-out) shift
         case "$*" in "screencap -p") shot;; *) cmdline="${*//\/sdcard\//$D/sdcard/}"; eval "$cmdline" 2>&1;; esac; exit 0;;
  emu) shift
       case "${1:-} ${2:-}" in
         "gsm status") rd _v "$D/gsm_voice"; rd _w "$D/gsm_data"; echo "gsm voice state: $_v"; echo "gsm data state:  $_w"; echo OK;;
         "gsm voice"|"gsm data") mut emu "$@"; printf '%s' "$3" > "$D/gsm_$2"; echo OK;;
       esac; exit 0;;
  pull) shift; src="${1//\/sdcard\//$D/sdcard/}"; dst="$2"
        if [ -e "$src" ]; then command cp -r "$src" "$dst"; echo "1 file pulled."; exit 0; fi
        echo "adb: error: failed to stat remote object '$1'" >&2; exit 1;;
  push) shift; mut push "$@"; dst="${2//\/sdcard\//$D/sdcard/}"; command mkdir -p "${dst%/*}"; command cp "$1" "$dst"; exit $?;;
  *) exit 0;;
esac
EOF
chmod +x "$BIN"/*

# a PNG header for 1080x2400 (all the harness reads of a screenshot besides comparing two of them byte for byte)
printf '\211PNG\r\n\032\n\000\000\000\rIHDR\000\000\004\070\000\000\011\140\010\006\000\000\000fake-pixels' > "$WORK/shot.png"

# ---------------------------------------------------------------- a scenario's device
ACC_BOTH="com.other.app/com.other.app.Service:dev.launcher.app/dev.launcher.app.NavAccessibilityService"
new_dev() {   # $1 = scenario name, $2 = accessibility list ("" = none set), $3 = user-pixel.json content ("" = no file)
  S="$WORK/$1"; D="$S/dev"; APPF="$D/sdcard/Android/data/dev.launcher.app/files"
  mkdir -p "$D/s/system" "$D/s/secure" "$D/s/global" "$APPF/design"
  cp "$WORK/shot.png" "$S/shot.png"; : > "$S/adb.log"
  printf home > "$D/gsm_voice"; printf home > "$D/gsm_data"
  printf 37 > "$D/sdk"; printf 1080x2400 > "$D/size"; printf 1 > "$D/shizuku"; printf 'no' > "$D/night"
  echo "dev.launcher.app/dev.launcher.app.HomeActivity" > "$D/focus"
  put() { mkdir -p "$D/s/$1"; printf '%s' "$3" > "$D/s/$1/$2"; }
  put system font_scale 1.0; put system screen_brightness 200; put system screen_brightness_mode 1
  put system accelerometer_rotation 1; put system some_other 5
  put global auto_time 1; put global auto_time_zone 1; put global zen_mode 0; put global volume_x 3
  put secure theme_customization_overlay_packages '{"android.theme.customization.system_palette":"D2DCF0","android.theme.customization.theme_style":"TONAL_SPOT"}'
  put secure sysui_qs_tiles "wifi,bt,rotation"
  [ -n "$2" ] && put secure enabled_accessibility_services "$2"
  printf '{"version": 1, "elements": {"shade": {"layout": "ios", "placement": "split-top", "options": {}}}}' > "$APPF/setup.json"
  printf ios27 > "$APPF/design/theme.txt"; printf '{}' > "$APPF/design/elements.json"; printf ios27 > "$APPF/design/motion.txt"
  [ -n "$3" ] && printf '%s' "$3" > "$APPF/design/user-pixel.json"
  return 0
}
snap() { ( cd "$1/dev" && find . -type f ! -path '*/dev-out/*' ! -path '*/dev-in/*' ! -name 'hang*' -print0 | sort -z | xargs -0 md5sum ); }
muts() { grep -c '^MUT ' "$S/adb.log"; }

# capture.sh against the fake: run_cap [ENV=..] -- ARGS...; output in $S/out.txt, exit in RC
run_cap() {
  local envs=()
  while [ "$1" != -- ]; do envs+=("$1"); shift; done; shift
  env -u MSYS_NO_PATHCONV PATH="$BIN:$PATH" ADB="$BIN/adb" DEVICE=emulator-5554 FID_PRIOR="$S/prior" FAKE_STATE="$S" POLL_SCALE="$POLL_SCALE" "${envs[@]}" \
    timeout 300 bash "$REPO/tools/fidelity/capture.sh" "$@" > "$S/out.txt" 2>&1 < /dev/null
  RC=$?
}

# ---------------------------------------------------------------- results (a scenario writes lines to $S/result)
FAILS=0
chk() {   # chk "description" CONDITION-COMMAND...  (records PASS or FAIL in $S/result)
  local d="$1"; shift
  if "$@" > /dev/null 2>&1; then echo "PASS  $NAME: $d" >> "$S/result"; else echo "FAIL  $NAME: $d" >> "$S/result"; fi
}
has() { grep -qE -- "$1" "$2"; }
eq() { [ "$1" = "$2" ]; }
finish() {   # prints the output tail when something failed (or VERBOSE)
  chk "every adb call named the emulator (-s emulator-*)" test "$(grep -c '^NOSERIAL' "$S/adb.log")" = 0
  if grep -q '^FAIL' "$S/result" || [ -n "$VERBOSE" ]; then
    { echo "        --- $NAME: capture.sh output (last 12 lines):"; tail -12 "$S/out.txt" | sed 's/^/        | /'
      echo "        --- $NAME: MUT calls: $(grep -c '^MUT ' "$S/adb.log")"; } >> "$S/result"
  fi
}

# ---------------------------------------------------------------- scenarios
sc_refuse() {   # $1 name, $2 what to break (a function run after new_dev), $3 reason regex, $4 serial
  NAME="refuse-$1"; new_dev "$NAME" "$ACC_BOTH" '{"format":1,"name":"user","tokens":{}}'
  $2
  local before; before="$(snap "$S")"
  run_cap "DEVICE=${4:-emulator-5554}" -- --side ours --state quick --theme light --out "$S/out"
  chk "exit 2" eq "$RC" 2
  chk "says REFUSED and the reason /$3/" has "REFUSED.*($3)" "$S/out.txt"
  chk "says nothing was touched" has "Nothing was touched" "$S/out.txt"
  chk "no mutating call" eq "$(muts)" 0
  chk "the device is as it was" eq "$(snap "$S")" "$before"
  chk "no capture started (no PNG, no prior folder unless it was there)" test ! -e "$S/out/ours"
  finish
}
b_sdk() { printf 36 > "$D/sdk"; }
b_size() { printf 1080x2340 > "$D/size"; }
b_shizuku() { printf 2 > "$D/shizuku"; }
b_focus() { echo "com.other.app/com.other.app.Main" > "$D/focus"; }
b_font() { printf 1.3 > "$D/s/system/font_scale"; }
b_palette() { printf '{"android.theme.customization.system_palette":"AABBCC"}' > "$D/s/secure/theme_customization_overlay_packages"; }
b_dirty() { mkdir -p "$S/prior"; echo "emulator-5554 1" > "$S/prior/dirty"; }
b_manifest() { mkdir -p "$S/out"; echo '{}' > "$S/out/manifest.json"; }
b_notif() { : > "$D/notif_5"; }

sc_dryrun() {
  NAME="dryrun"; new_dev "$NAME" "$ACC_BOTH" ''
  local before; before="$(snap "$S")"
  run_cap -- --side ours --state quick --theme light --out "$S/out" --dry-run
  chk "exit 0" eq "$RC" 0
  chk "says DRY RUN" has "DRY RUN" "$S/out.txt"
  chk "no mutating call" eq "$(muts)" 0
  chk "the device is as it was" eq "$(snap "$S")" "$before"
  chk "no prior folder, no PNG" test ! -e "$S/prior" -a ! -e "$S/out"
  finish
}

sc_ours() {   # $1 = name, $2 = accessibility list, $3 = user-pixel.json content (or none)
  NAME="$1"; new_dev "$NAME" "$2" "$3"
  local before; before="$(snap "$S")"
  run_cap -- --side ours --state quick,tile-off --theme light --out "$S/out"
  chk "exit 0" eq "$RC" 0
  chk "2 PNGs of 1080x2400 and a manifest" test "$(ls "$S"/out/ours/*/*.png | wc -l)" = 2 -a -f "$S/out/manifest.json" -a "$(grep -c '"size": \[1080, 2400\]' "$S/out/manifest.json")" = 2
  chk "the modem was unregistered (signal bars still) and given back" has "MUT emu gsm voice unregistered" "$S/adb.log"
  chk "tile-off turned Auto-rotate off, the others on" has "settings put system accelerometer_rotation 0" "$S/adb.log"
  chk "the override was written with the system colours and font" has "IMPORT design/user-pixel.json .*sys.color.style.*system" "$S/adb.log"
  chk "  and with the system font" has "IMPORT design/user-pixel.json .*sys.font.text.*system.*sys.font.display" "$S/adb.log"
  if [ -n "$3" ]; then chk "  and with what was there kept" has "IMPORT design/user-pixel.json .*keep.me" "$S/adb.log"; fi
  chk "shade set to pixel/together and the theme to pixel" has "SETUP.*--es layout pixel --es placement together" "$S/adb.log"
  case "$2" in *NavAccessibilityService*) ;; *)
    chk "the service was put on (the list holds it)" has "MUT settings put secure enabled_accessibility_services .*NavAccessibilityService" "$S/adb.log";; esac
  case "$2" in *com.other*) chk "the other service stayed in the list" has "MUT settings put secure enabled_accessibility_services com.other.app/com.other.app.Service:dev" "$S/adb.log";; esac
  chk "the device is as before (everything, byte for byte)" eq "$(snap "$S")" "$before"
  chk "  said so" has "the device is as before" "$S/out.txt"
  chk "no dirty marker left" test ! -e "$S/prior/dirty"
  chk "the fixture is cancelled" test ! -e "$D/notif_1"
  chk "the user file is as it was" eq "$([ -f "$APPF/design/user-pixel.json" ] && cat "$APPF/design/user-pixel.json" || echo ABSENT)" "${3:-ABSENT}"
  finish
}

sc_stock() {   # $1 = name, $2 = accessibility list
  NAME="$1"; new_dev "$NAME" "$2" ''
  local before; before="$(snap "$S")"
  run_cap -- --side stock --state quick,tile-on --theme dark --out "$S/out"
  chk "exit 0" eq "$RC" 0
  chk "2 PNGs of 1080x2400 and a manifest" test "$(ls "$S"/out/stock/*/*.png | wc -l)" = 2 -a -f "$S/out/manifest.json"
  chk "the modem was unregistered (signal bars still) and given back" has "MUT emu gsm data unregistered" "$S/adb.log"
  chk "stock's tiles were set" has "MUT cmd statusbar set-tiles wifi,bt,cell,dnd,airplane,rotation,flashlight" "$S/adb.log"
  chk "our service was switched off" has "MUT settings (put secure enabled_accessibility_services|delete secure enabled_accessibility_services)" "$S/adb.log"
  chk "night mode set to dark and given back" has "MUT cmd uimode night no" "$S/adb.log"
  chk "the clock was set and auto time given back" has "MUT cmd alarm set-time 1793613600000" "$S/adb.log"
  chk "no app broadcast touched the design (stock side)" test "$(grep -c 'IMPORT' "$S/adb.log")" = 0
  chk "the device is as before (everything, byte for byte)" eq "$(snap "$S")" "$before"
  chk "no dirty marker left" test ! -e "$S/prior/dirty"
  case "$2" in *com.other*) chk "the other service stayed in the list while ours was off" has "enabled_accessibility_services com.other.app/com.other.app.Service\$" "$S/adb.log";; esac
  finish
}

sc_notrest() {
  NAME="notrest"; new_dev "$NAME" "$ACC_BOTH" ''
  local before; before="$(snap "$S")"
  : > "$D/notrest"
  run_cap -- --side stock --state quick --theme light --out "$S/out"
  chk "exit 1" eq "$RC" 1
  chk "says NOT AT REST" has "NOT AT REST" "$S/out.txt"
  chk "no PNG kept" test "$(ls "$S"/out/stock/light/*.png 2>/dev/null | wc -l)" = 0
  rm -f "$D/notrest"
  chk "the device is put back all the same" eq "$(snap "$S")" "$before"
  finish
}

wait_hang() { local i; for ((i = 0; i < 1200; i++)); do [ -f "$S/dev/hanging" ] && return 0; "$REAL_SLEEP" 0.1; done; return 1; }
run_bg() {   # ARGS: capture.sh in the background -> PID
  : > "$S/out.txt"   # (appended to below: a file opened for append keeps the lines of a trap that runs after a kill)
  env -u MSYS_NO_PATHCONV PATH="$BIN:$PATH" ADB="$BIN/adb" DEVICE=emulator-5554 FID_PRIOR="$S/prior" FAKE_STATE="$S" POLL_SCALE="$POLL_SCALE" \
    bash "$REPO/tools/fidelity/capture.sh" "$@" >> "$S/out.txt" 2>&1 < /dev/null &
  PID=$!
}
sc_sigterm() {
  NAME="sigterm"; new_dev "$NAME" "$ACC_BOTH" ''
  local before; before="$(snap "$S")"
  echo expand > "$D/hang"
  run_bg --side stock --state quick --theme dark --out "$S/out"
  chk "the run reached its first panel" wait_hang
  kill -TERM "$PID" 2>/dev/null; kill -KILL "$(<"$D/hang.pid")" 2>/dev/null
  wait "$PID"; RC=$?
  chk "exit 130" eq "$RC" 130
  # (the handler's own words never reach the file on this MSYS; its effects do: that it ran is what the next lines prove)
  chk "the device is as before" eq "$(snap "$S")" "$before"
  chk "no dirty marker left" test ! -e "$S/prior/dirty"
  finish
}
sc_kill() {
  NAME="kill"; new_dev "$NAME" "$ACC_BOTH" '{"format":1,"name":"user","tokens":{"keep.me":{"choice":"x"}}}'
  local before; before="$(snap "$S")"
  echo open > "$D/hang"
  run_bg --side ours --state quick --theme light --out "$S/out"
  chk "the run reached its first panel" wait_hang
  kill -KILL "$PID" 2>/dev/null; kill -KILL "$(<"$D/hang.pid")" 2>/dev/null
  wait "$PID" 2>/dev/null
  chk "the device is left changed" test "$(snap "$S")" != "$before"
  chk "a dirty marker stays" test -f "$S/prior/dirty"
  local m; m="$(muts)"
  run_cap -- --side stock --state quick --theme light --out "$S/out3"
  chk "a new run is refused with exit 2" eq "$RC" 2
  chk "  naming the dirty marker and --restore" has "REFUSED.*dirty.*--restore" "$S/out.txt"
  chk "  with no mutating call" eq "$(muts)" "$m"
  run_cap -- --restore
  chk "--restore exits 0" eq "$RC" 0
  chk "--restore puts the device back (byte for byte)" eq "$(snap "$S")" "$before"
  chk "  the dirty marker is gone" test ! -e "$S/prior/dirty"
  run_cap -- --restore
  chk "a second --restore has nothing to do: exit 0" eq "$RC" 0
  chk "  and says so" has "nothing to restore" "$S/out.txt"
  finish
}

sc_nostrip() {   # B-018 at the restore: the list is back but the strip never comes: the run must say so and keep the dirty marker
  NAME="nostrip"; new_dev "$NAME" "$ACC_BOTH" ''
  local before; before="$(snap "$S")"
  : > "$D/nostrip"
  run_cap -- --side stock --state quick --theme light --out "$S/out"
  chk "exit 1" eq "$RC" 1
  chk "warns that the strip is not up as before" has "WARNING: our gesture strip is not up" "$S/out.txt"
  chk "tried the service off and on" has "restore: gesture strip not up after 20 s .*try 2" "$S/out.txt"
  chk "did not say the device is as before" test "$(grep -c 'the device is as before' "$S/out.txt")" = 0
  chk "the dirty marker stays" test -f "$S/prior/dirty"
  chk "the accessibility list itself is back" eq "$(cat "$D/s/secure/enabled_accessibility_services")" "$ACC_BOTH"
  rm -f "$D/nostrip"
  run_cap -- --restore
  chk "--restore with the strip up: exit 0" eq "$RC" 0
  chk "  the device is as before" eq "$(snap "$S")" "$before"
  chk "  the dirty marker is gone" test ! -e "$S/prior/dirty"
  finish
}
sc_static() {   # capture.sh itself: adb is called in a(), ap() and emu() only (each of them names the device)
  NAME="static"; S="$WORK/static"; mkdir -p "$S"; : > "$S/adb.log"
  local calls; calls="$(grep -nE '"\$ADB"' "$REPO/tools/fidelity/capture.sh" | grep -vE '^[0-9]+:(a|ap|emu)\(\) |^[0-9]+:ADB=' | grep -v '^[0-9]*:#' || true)"
  chk "\$ADB is used only inside a, ap and emu" test -z "$calls"
  chk "  and those three each pass -s \"\$DEVICE\"" test "$(grep -cE '^(a|ap|emu)\(\) .*"\$ADB" -s "\$DEVICE"' "$REPO/tools/fidelity/capture.sh")" = 3
  finish
}

# ---------------------------------------------------------------- run them all at once
JOBS=()
job() { [ -z "${ONLY:-}" ] || [[ " $* " == *" $ONLY "* ]] || return 0; ( "$@" ) & JOBS+=($!); }
job sc_refuse sdk b_sdk "not Android 17"
job sc_refuse size b_size "display size"
job sc_refuse shizuku b_shizuku "2 shizuku_server"
job sc_refuse focus b_focus "HomeActivity is not in front"
job sc_refuse font b_font "font scale"
job sc_refuse palette b_palette "palette"
job sc_refuse dirty b_dirty "dirty"
job sc_refuse manifest b_manifest "already holds a capture"
job sc_refuse notif b_notif "another test notification"
job sc_refuse serial true "not an emulator" FAKE-SERIAL
job sc_dryrun
job sc_ours ours-merge "com.other.app/com.other.app.Service" '{"format":1,"name":"user","tokens":{"keep.me":{"choice":"x"}}}'
job sc_ours ours-fresh "" ''
job sc_stock stock-other "$ACC_BOTH"
job sc_stock stock-alone "dev.launcher.app/dev.launcher.app.NavAccessibilityService"
job sc_notrest
job sc_sigterm
job sc_kill
job sc_nostrip
job sc_static
for j in "${JOBS[@]}"; do wait "$j"; done

for r in "$WORK"/*/result; do [ -f "$r" ] && cat "$r"; done > "$WORK/all.txt"
cat "$WORK/all.txt"
FAILS=$(grep -c '^FAIL' "$WORK/all.txt"); PASSES=$(grep -c '^PASS' "$WORK/all.txt")
SCEN=$(ls "$WORK"/*/result 2>/dev/null | wc -l)
echo "fidelity_capture_test: $SCEN scenarios, $PASSES checks passed, $FAILS failed"
[ "$FAILS" = 0 ] && { [ -n "${ONLY:-}" ] || [ "$SCEN" = 20 ]; }
