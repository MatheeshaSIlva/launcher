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
# MF slice 2: ours' tile list is the fake's virtual qs_tiles.txt (state file $D/qs_tiles, absent = reset: the app's export says
# `reset` or the list, its import applies it), `uiautomator dump` (stock's tree for --dump), ro.build.id; golden.sh runs on it too.
# Notification set (D-041 addendum): the manifests record it, golden verify fails on a recapture with another one, ours warns against the golden's.
# Cases: preconditions refuse with no mutating call (11 reasons, incl. a `dirty` marker and an app that cannot export its tile list; --dump with
# ours); --dry-run touches nothing; a full
# ours run and a stock run put everything back (other accessibility services kept, user-pixel.json merged and restored byte for
# byte or removed when it was absent, settings, tiles, ours' tile list (reset, or a kept list that equals the default, told
# apart), night mode, clock, battery, setup and theme); not at rest exits 1 with "NOT AT REST" and still restores; SIGTERM mid-run
# restores (both sides); a SIGKILL leaves `dirty`, a new run is refused, --restore puts it back (ours' list too); --dump saves a
# tree per state and leaves nothing on the device; golden.sh: refuses (not an emulator, a build that already has a golden), stores
# 8 pictures with a manifest, verifies them, notices a changed file and a second session's capture, and puts the device back.   tools/tests/fidelity_capture_test.sh   exit 0 all passed, 1 a case failed (listed)   VERBOSE=1 prints each run
set -u
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap '[ -n "${KEEP:-}" ] || rm -rf "$WORK"' EXIT
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
    ro.build.id) echo FAKE.260101.001;;
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
      [ -f "$D/foreign" ] && echo "  NotificationRecord(0x3: pkg=com.android.vending user=UserHandle{0} id=951340667 tag=null importance=2 key=z|channel=account)"
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
app_export() {
  rm -rf "$APP/dev-out"; mkdir -p "$APP/dev-out/design"; cp "$APP/setup.json" "$APP/dev-out/"; cp "$APP"/design/* "$APP/dev-out/design/" 2>/dev/null
  if [ ! -f "$D/noqs" ]; then if [ -f "$D/qs_tiles" ]; then printf '%s\n' "$(<"$D/qs_tiles")" > "$APP/dev-out/qs_tiles.txt"; else echo reset > "$APP/dev-out/qs_tiles.txt"; fi; fi
  echo 2 > "$APP/dev-out/.done"
}
uiautomator() { # the stock side's tree (--dump): a file on the fake sdcard, as the real one
  [ "$1" = dump ] || return 1
  [[ "$(sget secure enabled_accessibility_services)" == *"$OURS"* ]] && echo "UNBOUND $*" >> "$LOGF"   # (what a dump with our service on would do)
  echo "UI hierchary dumped to: $2"; printf '<?xml version="1.0"?><hierarchy rotation="0"><node text="Fidelity fixture"/></hierarchy>' > "$2"   # ($2 is already the fake sdcard path: the shell branch replaced /sdcard/)
}
app_import() {
  local f rel
  [ -d "$APP/dev-in" ] || return 0
  while IFS= read -r f; do
    rel="${f#"$APP/dev-in/"}"
    echo "IMPORT $rel $(<"$f")" >> "$LOGF"
    if [ "$rel" = qs_tiles.txt ]; then   # the virtual file: the list is applied, not stored as a file
      _t="$(<"$f")"; _t="${_t%%$'\r'}"; if [ "${_t//[$' \t\n']/}" = reset ]; then command rm -f "$D/qs_tiles"; else printf '%s' "$_t" > "$D/qs_tiles"; fi
      continue
    fi
    mkdir -p "$APP/$(dirname "$rel")"; cp "$f" "$APP/$rel"
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
  if [ -f "$D/foreignonce" ]; then command rm -f "$D/foreignonce"; : > "$D/foreign"; fi   # a system notification arrives while a state is taken
  # a slow state: the clock runs 70 s ahead while the picture is taken (once, or every time)
  if [ -f "$D/slowonce" ]; then command rm -f "$D/slowonce"; rd _c "$D/clock_ms"; printf '%s' $((_c + 70000)) > "$D/clock_ms"
  elif [ -f "$D/slowalways" ]; then rd _c "$D/clock_ms"; printf '%s' $((_c + 70000)) > "$D/clock_ms"; fi
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
new_dev() {   # $1 = scenario name, $2 = accessibility list ("" = none set), $3 = user-pixel.json content ("" = no file), $4 = ours' kept tile list ("" = none kept: reset)
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
  [ -n "${4:-}" ] && printf '%s' "$4" > "$D/qs_tiles"
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
rc_out() { [ "$RC" = "$1" ] && grep -qE -- "$2" "$S/out.txt"; }   # the last run exited with $1 and its output has /$2/
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
b_noqs() { : > "$D/noqs"; }

sc_refuse_noqs() {   # an app older than slice 2: its export has no qs_tiles.txt. The refusal comes after the prior's first export,
  # which is a read: no setting, no clock, no modem, no notification, no list is touched, and no dirty marker is left.
  NAME="refuse-noqs"; new_dev "$NAME" "$ACC_BOTH" ''
  b_noqs
  local before; before="$(snap "$S")"
  run_cap -- --side ours --state quick --theme light --out "$S/out"
  chk "exit 2" eq "$RC" 2
  chk "says REFUSED and names qs_tiles.txt" has "REFUSED.*qs_tiles.txt" "$S/out.txt"
  chk "no setting, command, modem or notification was changed" test "$(grep '^MUT ' "$S/adb.log" | grep -cE 'settings (put|delete)|MUT cmd|MUT emu|MUT dumpsys|TEST_NOTIFY|SETUP|IMPORT|set-time')" = 0
  chk "the device is as it was" eq "$(snap "$S")" "$before"
  chk "no prior folder, no dirty marker, no PNG" test ! -e "$S/prior" -a ! -e "$S/out/ours"
  finish
}
sc_refuse_dump_ours() {
  NAME="refuse-dump-ours"; new_dev "$NAME" "$ACC_BOTH" ''
  local before; before="$(snap "$S")"
  run_cap -- --side ours --state quick --theme light --out "$S/out" --dump
  chk "exit 2" eq "$RC" 2
  chk "says --dump is for the stock side" has "dump is for --side stock only" "$S/out.txt"
  chk "no mutating call" eq "$(muts)" 0
  chk "the device is as it was" eq "$(snap "$S")" "$before"
  finish
}

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

OURS_WANT="WIFI:w,BLUETOOTH:w,CELLULAR:w,FOCUS:w,AIRPLANE,ROTATION_LOCK,FLASHLIGHT"
DEFAULT_LIST="WIFI:w,BLUETOOTH:w,CELLULAR:w,QUICK_SHARE:w,FOCUS:w,WALLET:w,AIRPLANE,ROTATION_LOCK,FLASHLIGHT,LOW_POWER,ALARM,HOME,MIRRORING"
sc_ours() {   # $1 = name, $2 = accessibility list, $3 = user-pixel.json content (or none), $4 = ours' kept tile list (or none: reset)
  NAME="$1"; new_dev "$NAME" "$2" "$3" "${4:-}"
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
  chk "stock's tiles were set on the ours side too" has "MUT cmd statusbar set-tiles wifi,bt,cell,dnd,airplane,rotation,flashlight" "$S/adb.log"
  chk "ours' tile list was set to stock's counterpart" has "IMPORT qs_tiles.txt $OURS_WANT\$" "$S/adb.log"
  chk "ours' tile list is as it was: $([ -n "${4:-}" ] && echo "the kept list" || echo reset)" eq "$([ -f "$D/qs_tiles" ] && cat "$D/qs_tiles" || echo RESET)" "${4:-RESET}"
  chk "  and was put back with the prior's own text ($([ -n "${4:-}" ] && echo "not reset" || echo reset))" has "IMPORT qs_tiles.txt ${4:-reset}\$" "$S/adb.log"
  chk "  the manifest names both lists" has '"tiles_ours": "'"$OURS_WANT"'"' "$S/out/manifest.json"
  chk "  and the notification set at capture (the fixture and the safety one)" has '"notifications": "pkg=dev.launcher.app id=1001;pkg=dev.launcher.app id=7;"' "$S/out/manifest.json"
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
  chk "ours' tile list was not touched (stock side)" test "$(grep -c 'qs_tiles[.]txt' "$S/adb.log")" = 0
  chk "the device is as before (everything, byte for byte)" eq "$(snap "$S")" "$before"
  chk "no dirty marker left" test ! -e "$S/prior/dirty"
  chk "the manifest names the notification set at capture" has '"notifications": "pkg=dev.launcher.app id=1001;pkg=dev.launcher.app id=7;"' "$S/out/manifest.json"
  case "$2" in *com.other*) chk "the other service stayed in the list while ours was off" has "enabled_accessibility_services com.other.app/com.other.app.Service\$" "$S/adb.log";; esac
  finish
}

sc_dump() {   # --dump: stock's tree beside each picture, only with our service off, nothing left on the device
  NAME="dump"; new_dev "$NAME" "$ACC_BOTH" ''
  local before; before="$(snap "$S")"
  run_cap -- --side stock --state quick,expanded --theme light,dark --out "$S/out" --dump
  chk "exit 0" eq "$RC" 0
  chk "4 pictures and 4 trees" test "$(ls "$S"/out/stock/*/*.png | wc -l)" = 4 -a "$(ls "$S"/out/stock/*/*.xml | wc -l)" = 4
  chk "the trees are uiautomator's" has "<hierarchy" "$S/out/stock/light/quick.xml"
  chk "the manifest names each tree" test "$(grep -c '"dump": "stock/' "$S/out/manifest.json")" = 4
  chk "no dump ran with our service on" test "$(grep -c '^UNBOUND' "$S/adb.log")" = 0
  chk "nothing of the dump is left on the device" test ! -e "$D/sdcard/fid_dump.xml"
  chk "the device is as before (byte for byte)" eq "$(snap "$S")" "$before"
  finish
}
sc_slowclock() {   # the clock guard: a slow state is taken again once; one that is slow every time fails (exit 1), the device is put back both ways
  NAME="slowclock"; new_dev "$NAME" "$ACC_BOTH" ''
  local before; before="$(snap "$S")"
  : > "$D/slowonce"
  run_cap -- --side stock --state quick --theme light --out "$S/out"
  chk "slow once: exit 0" eq "$RC" 0
  chk "  says it retried" has "retrying light/quick" "$S/out.txt"
  chk "  the picture and its manifest line are there" test -f "$S/out/stock/light/quick.png" -a "$(grep -c '"state": "quick"' "$S/out/manifest.json")" = 1
  chk "  the clock was set again after the panel was reached (at least 7 set-time calls in all)" test "$(grep -c '^MUT cmd alarm set-time' "$S/adb.log")" -ge 7
  chk "  the device is as before" eq "$(snap "$S")" "$before"
  : > "$D/slowalways"
  run_cap -- --side stock --state quick --theme light --out "$S/out2"
  chk "slow every time: exit 1" eq "$RC" 1
  chk "  names the clock" has "FAIL light/quick r1: the clock left 10:00" "$S/out.txt"
  chk "  no picture kept in the manifest" test "$(grep -c '"state": "quick"' "$S/out2/manifest.json")" = 0
  rm -f "$D/slowalways"
  chk "  the device is put back all the same" eq "$(snap "$S")" "$before"
  finish
}
sc_foreign() {   # a notification that arrives mid-run (Play Protect's) makes the state fail, never a silent difference
  NAME="foreign"; new_dev "$NAME" "$ACC_BOTH" ''
  local before; before="$(snap "$S")"
  : > "$D/foreignonce"
  run_cap -- --side stock --state quick --theme light --out "$S/out"
  chk "exit 1" eq "$RC" 1
  chk "says a notification arrived, naming the package" has "FAIL light/quick r1: a notification arrived.*com.android.vending" "$S/out.txt"
  rm -f "$D/foreign"
  chk "the device is put back all the same" eq "$(snap "$S")" "$before"
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
sc_sigterm() {   # $1 = side (stock: hangs in the first panel's expand; ours: in its open, with a kept list to put back)
  NAME="sigterm-$1"; new_dev "$NAME" "$ACC_BOTH" '' "$([ "$1" = ours ] && echo "$DEFAULT_LIST")"
  local before; before="$(snap "$S")"
  [ "$1" = ours ] && echo open > "$D/hang" || echo expand > "$D/hang"
  run_bg --side "$1" --state quick --theme dark --out "$S/out"
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
  NAME="kill"; new_dev "$NAME" "$ACC_BOTH" '{"format":1,"name":"user","tokens":{"keep.me":{"choice":"x"}}}' "$DEFAULT_LIST"
  local before; before="$(snap "$S")"
  echo open > "$D/hang"
  run_bg --side ours --state quick --theme light --out "$S/out"
  chk "the run reached its first panel" wait_hang
  kill -KILL "$PID" 2>/dev/null; kill -KILL "$(<"$D/hang.pid")" 2>/dev/null
  wait "$PID" 2>/dev/null
  chk "the device is left changed (ours' list too)" test "$(cat "$D/qs_tiles")" = "$OURS_WANT"
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
  chk "  ours' kept list is back as a list, not reset" eq "$(cat "$D/qs_tiles" 2>/dev/null || echo RESET)" "$DEFAULT_LIST"
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
# golden.sh against the fake (golden.sh capture/verify; a real 1080x2400 picture so that pngopt.py takes it)
real_png() { python -c "
import sys
from PIL import Image, ImageDraw
im = Image.new('RGBA', (1080, 2400), (20, 24, 32, 255))
d = ImageDraw.Draw(im)
d.rectangle([60, 300, 1000, 900], fill=(60, 120, 200, 255))
im.save(sys.argv[1])" "$1"; }
run_golden() {   # run_golden ARGS...: golden.sh on the fake; output in $S/out.txt, exit in RC
  env -u MSYS_NO_PATHCONV PATH="$BIN:$PATH" ADB="$BIN/adb" DEVICE="${GDEVICE:-emulator-5554}" FID_PRIOR="$S/prior" FAKE_STATE="$S" POLL_SCALE="$POLL_SCALE" \
    GOLDEN_STAGE="$S/stage" timeout 300 bash "$REPO/tools/fidelity/golden.sh" "$@" > "$S/out.txt" 2>&1 < /dev/null
  RC=$?
}
sc_golden() {
  NAME="golden"; new_dev "$NAME" "$ACC_BOTH" ''
  real_png "$WORK/golden_shot.png"; cp "$WORK/golden_shot.png" "$S/shot.png"
  local before; before="$(snap "$S")"
  # refusals first: not an emulator, and a build that already has a golden (nothing touched, nothing written)
  GDEVICE=FAKE-SERIAL run_golden capture --root "$S/g0"
  chk "a non-emulator serial is refused (exit 2, nothing touched)" eq "$RC" 2
  chk "  says so" has "REFUSED.*not an emulator" "$S/out.txt"
  chk "  no mutating call, no folder" test "$(muts)" = 0 -a ! -e "$S/g0"
  mkdir -p "$S/g1/FAKE.260101.001"; echo '{}' > "$S/g1/FAKE.260101.001/manifest.json"
  run_golden capture --root "$S/g1"
  chk "a build that already has a golden is refused (exit 2)" eq "$RC" 2
  chk "  says it is never overwritten" has "REFUSED.*already exists" "$S/out.txt"
  chk "  no mutating call" eq "$(muts)" 0
  chk "  the existing manifest is untouched" eq "$(cat "$S/g1/FAKE.260101.001/manifest.json")" "{}"
  # a capture that works: 8 pictures, a manifest, the device back as before
  run_golden capture --root "$S/g2"
  chk "capture: exit 0" eq "$RC" 0
  chk "  8 PNGs under <build>/<theme>/<state>.png" test "$(ls "$S"/g2/FAKE.260101.001/*/*.png | wc -l)" = 8
  chk "  a manifest with 8 files and the total" test "$(grep -c '"size": \[1080, 2400\]' "$S/g2/FAKE.260101.001/manifest.json")" = 8 -a "$(grep -c '"total_bytes"' "$S/g2/FAKE.260101.001/manifest.json")" = 1
  chk "  no staging folder left" test ! -e "$S/g2/FAKE.260101.001.tmp"
  chk "  the manifest records the notification set (read from the capture's manifest)" has '"notifications": "pkg=dev.launcher.app id=1001;pkg=dev.launcher.app id=7;",' "$S/g2/FAKE.260101.001/manifest.json"
  chk "  the device is as before" eq "$(snap "$S")" "$before"
  run_golden verify --root "$S/g2"
  chk "verify: exit 0" eq "$RC" 0
  chk "  says OK and the total" has "golden verify: OK.*" "$S/out.txt"
  chk "  prints the notification set at capture" has "notifications at capture: pkg=dev.launcher.app id=1001;pkg=dev.launcher.app id=7;" "$S/out.txt"
  # a manifest that records no notification set (a golden of before) fails
  mkdir -p "$S/g4"; cp -r "$S/g2/FAKE.260101.001" "$S/g4/"; sed -i '/"notifications"/d' "$S/g4/FAKE.260101.001/manifest.json"
  run_golden verify --root "$S/g4"
  chk "a golden with no notification set fails verify (exit 1, says recapture)" rc_out 1 "FAIL  the manifest records no notification set.*recapture"
  # a recapture with another notification set (Play Protect's arrived): identical pictures, but verify --against fails and names why
  : > "$D/foreign"; rm -rf "$S/stage"; run_golden capture --root "$S/g5"; rm -f "$D/foreign"
  chk "a capture with a foreign notification stores it in its manifest" has "com.android.vending id=951340667" "$S/g5/FAKE.260101.001/manifest.json"
  run_golden verify --root "$S/g2" --against "$S/g5"
  chk "--against a recapture with another notification set fails (exit 1) and says so" rc_out 1 "FAIL  --against: the notifications at capture differ.*stored: pkg=dev.launcher.app.*other: .*com.android.vending"
  run_golden verify --root "$S/g5" --against "$S/g2"
  chk "  the other way round too (exit 1)" rc_out 1 "FAIL  --against: the notifications at capture differ"
  # a second session's capture of the same build (the fake gives the same picture: identical), and a changed file
  rm -rf "$S/stage"; run_golden capture --root "$S/g3"
  run_golden verify --root "$S/g2" --against "$S/g3"
  chk "against a second capture: exit 0, 8 of 8 identical" rc_out 0 "8 of 8 identical"
  python -c "
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert('RGB'); im.putpixel((5, 5), (255, 0, 0)); im.save(sys.argv[1])" "$S/g3/FAKE.260101.001/dark/quick.png"
  run_golden verify --root "$S/g3"
  chk "a changed picture is noticed (exit 1, its md5)" rc_out 1 "FAIL  dark/quick.png: decoded md5"
  rm "$S/g3/FAKE.260101.001/light/expanded.png"
  run_golden verify --root "$S/g3"
  chk "a missing picture is noticed (exit 1)" rc_out 1 "FAIL  light/expanded.png: missing"
  run_golden verify --root "$S/g2" --against "$S/g3"
  chk "--against: a picture missing in the other folder is a failure, not a crash" rc_out 1 "FAIL  light/expanded.png: cannot be read"
  cp "$S/g2/FAKE.260101.001/light/expanded.png" "$S/g3/FAKE.260101.001/light/expanded.png"
  run_golden verify --root "$S/g2" --against "$S/g3"
  chk "--against: one changed pixel is listed as within noise (exit 0)" rc_out 0 "near  dark/quick.png"
  python -c "
import sys
from PIL import Image, ImageDraw
im = Image.open(sys.argv[1]).convert('RGB'); ImageDraw.Draw(im).rectangle([100, 700, 900, 1300], fill=(250, 250, 250)); im.save(sys.argv[1])" "$S/g3/FAKE.260101.001/light/tile-on.png"
  run_golden verify --root "$S/g2" --against "$S/g3"
  chk "--against: a real difference fails (exit 1, lists the file)" rc_out 1 "FAIL  light/tile-on.png: differs"
  finish
}
sc_notifwarn() {   # ours side: a warning when its notification set differs from the golden's of the build (never a failure)
  NAME="notifwarn"; new_dev "$NAME" "$ACC_BOTH" ''
  local before; before="$(snap "$S")"
  mkdir -p "$S/gold/FAKE.260101.001"
  printf '{
 "notifications": "pkg=dev.launcher.app id=1001;pkg=dev.launcher.app id=7;",
 "files": []
}
' > "$S/gold/FAKE.260101.001/manifest.json"
  run_cap GOLDEN_ROOT="$S/gold" -- --side ours --state quick --theme light --out "$S/out1"
  chk "the same set as the golden's: exit 0, no warning" test "$RC" = 0 -a "$(grep -c WARNING "$S/out.txt")" = 0
  printf '{
 "notifications": "pkg=dev.launcher.app id=7;",
 "files": []
}
' > "$S/gold/FAKE.260101.001/manifest.json"
  run_cap GOLDEN_ROOT="$S/gold" -- --side ours --state quick --theme light --out "$S/out2"
  chk "another set: a warning that names both sets, the run still exits 0" rc_out 0 "WARNING: this run's notifications differ from the golden's.*golden: pkg=dev.launcher.app id=7;.*now: pkg=dev.launcher.app id=1001;"
  printf '{
 "files": []
}
' > "$S/gold/FAKE.260101.001/manifest.json"
  run_cap GOLDEN_ROOT="$S/gold" -- --side ours --state quick --theme light --out "$S/out3"
  chk "a golden with no record of it: a warning, exit 0" rc_out 0 "WARNING: the golden of build FAKE.260101.001 .*records no notification set"
  run_cap GOLDEN_ROOT="$S/nogold" -- --side ours --state quick --theme light --out "$S/out4"
  chk "no golden for the build: no warning" test "$RC" = 0 -a "$(grep -c WARNING "$S/out.txt")" = 0
  run_cap GOLDEN_ROOT="$S/gold" -- --side stock --state quick --theme light --out "$S/out5"
  chk "the stock side does not look at the golden" test "$RC" = 0 -a "$(grep -c WARNING "$S/out.txt")" = 0
  chk "the device is as before" eq "$(snap "$S")" "$before"
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
job sc_refuse_noqs
job sc_refuse_dump_ours
job sc_refuse serial true "not an emulator" FAKE-SERIAL
job sc_dryrun
job sc_ours ours-merge "com.other.app/com.other.app.Service" '{"format":1,"name":"user","tokens":{"keep.me":{"choice":"x"}}}'
job sc_ours ours-fresh "" ''
job sc_ours ours-kept-default "" '' "$DEFAULT_LIST"
job sc_ours ours-kept-other "com.other.app/com.other.app.Service" '' "LOCATION,ROTATION_LOCK,HOTSPOT:w,FLASHLIGHT"
job sc_stock stock-other "$ACC_BOTH"
job sc_stock stock-alone "dev.launcher.app/dev.launcher.app.NavAccessibilityService"
job sc_dump
job sc_notrest
job sc_slowclock
job sc_foreign
job sc_notifwarn
job sc_sigterm stock
job sc_sigterm ours
job sc_kill
job sc_nostrip
job sc_golden
job sc_static
for j in "${JOBS[@]}"; do wait "$j"; done

for r in "$WORK"/*/result; do [ -f "$r" ] && cat "$r"; done > "$WORK/all.txt"
cat "$WORK/all.txt"
FAILS=$(grep -c '^FAIL' "$WORK/all.txt"); PASSES=$(grep -c '^PASS' "$WORK/all.txt")
SCEN=$(ls "$WORK"/*/result 2>/dev/null | wc -l)
echo "fidelity_capture_test: $SCEN scenarios, $PASSES checks passed, $FAILS failed"
[ "$FAILS" = 0 ] && { [ -n "${ONLY:-}" ] || [ "$SCEN" = 30 ]; }
