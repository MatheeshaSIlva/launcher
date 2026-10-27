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
#
# B-062 (D-043, revision 2): the snooze model. Another app's record is a file foreign_NAME (its key userId|pkg|id|tag|uid) in the device
# folder: it is in `cmd notification list` and the dump's records; `cmd notification snooze --for MS KEY` moves it to snoozed_NAME
# (KEY<TAB>UNTIL, until = the fake clock + MS) and the dump then shows it only under "Snoozed notifications:" and " Pending snoozed
# notifications" (`    key: KEY until: N`, keys with spaces); `cmd alarm set-time X` reposts every record whose until <= X (same key);
# `cmd notification unsnooze` logs `MUT UNSNOOZE` and DROPS the record, as the real one does (every scenario asserts it was never called).
# Switches are files in the scenario folder: sw_norepost, sw_nudge (the second jump past the until reposts), sw_snoozerefused,
# sw_snoozeextra (ms added to every until), sw_ownercancel (a repost finds the record cancelled), sw_listhide (the list hides what the
# dump shows). Scenarios: snooze-stock/-ours (two records, one with spaces; snoozed after the clock and before the fixture, keys saved
# first, back by the clock before the real one), the sweep and a mid-state arrival (foreign), sigterm and kill with a record snoozed,
# --restore with the clock still faked and back at real time (kill, kill-real), no repost / the nudge / the 3 h cap / a refused snooze /
# an owner's cancel (a NOTE) / a record the list hides (snooze-*), a quote in a key (apostrophe), a pending snooze that is not ours
# (refuse-pending), --dry-run listing them, golden.sh and baseline.sh refusing another app's package (golden*, baseline-gate).
set -u
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap '[ -n "${KEEP:-}" ] || rm -rf "$WORK"' EXIT
VERBOSE="${VERBOSE:-}"
# the fake's sleep takes no time, so a poll that never succeeds (the strip that never comes) costs a fake-adb call per try:
# its bound is scaled down (capture.sh's POLL_SCALE; every poll still tries at least once, the retries still run)
POLL_SCALE="${POLL_SCALE:-0.1}"
# Every fake adb call is a bash fork (~60-80 ms on Windows), so the scenarios run in a pool of JOBS at a time, not all at once
# (30 at once starved each other past their time bounds). CAP_TIMEOUT bounds one capture.sh/golden.sh run, HANG_WAIT the wait for a
# hung run to reach its hang (seconds).
JOBS_MAX="${JOBS_MAX:-6}"
CAP_TIMEOUT="${CAP_TIMEOUT:-600}"
HANG_WAIT="${HANG_WAIT:-120}"
export REAL_SLEEP="$(command -v sleep)"
BIN="$WORK/bin"; mkdir -p "$BIN"

# (shebangs name /bin/bash, not `env bash`: one exec less per call, ~35 ms of the ~110 a fake call cost; and `sleep` is a bash function
# that capture.sh's own bash inherits: no process at all. The file is for anything that is not bash)
printf '#!/bin/bash\nexit 0\n' > "$BIN/sleep"
sleep() { return 0; }; export -f sleep

cat > "$BIN/adb" <<'EOF'
#!/bin/bash
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
      # Android's own overlay notice, present while our accessibility service is on (the shade hides it by design): no record a shade lists
      [[ "$(sget secure enabled_accessibility_services)" == *"$OURS"* ]] && echo "  NotificationRecord(0x4: pkg=android user=UserHandle{0} id=0 tag=com.android.server.wm.AlertWindowNotification - dev.launcher.app importance=1 key=0|android|0|com.android.server.wm.AlertWindowNotification - dev.launcher.app|1000: Notification(channel=com.android.server.wm.AlertWindowNotification - dev.launcher.app))"
      local f k u p i t; for f in "$D"/notif_*; do
        [ -f "$f" ] || continue
        echo "  NotificationRecord(0x2: pkg=dev.launcher.app user=UserHandle{0} id=$((1000 + ${f##*notif_})) tag=null importance=3 key=y|channel=test)"
      done
      # another app's records (foreign_NAME: the key userId|pkg|id|tag|uid): live ones are records; snoozed ones (snoozed_NAME: KEY<TAB>UNTIL)
      # are only in the snoozed sections, as the real dumpsys has them (probe l)
      for f in "$D"/foreign_*; do
        [ -f "$f" ] || continue
        rd k "$f"; IFS='|' read -r _u p i t _w <<< "$k"
        echo "  NotificationRecord(0x3: pkg=$p user=UserHandle{0} id=$i tag=$t importance=2 key=$k: Notification(channel=x))"
      done
      echo "  Snoozed notifications:"
      for f in "$D"/snoozed_*; do [ -f "$f" ] || continue; IFS=$'\t' read -r k u < "$f"; echo "    key: $k"; done
      echo; echo " Pending snoozed notifications"
      for f in "$D"/snoozed_*; do
        [ -f "$f" ] || continue; IFS=$'\t' read -r k u < "$f"
        _fmt=""; [ -f "$FAKE_STATE/sw_pendfmt" ] && rd _fmt "$FAKE_STATE/sw_pendfmt"   # QA: a dump in another form (nountil: no until; eq: key=K until=N; none: no lines)
        [ -f "$FAKE_STATE/sw_nountil_${f##*snoozed_}" ] && _fmt=nountil   # QA2: the form without an until for this one record only
        case "$_fmt" in nountil) echo "    key: $k";; eq) echo "    key=$k until=$u";; none) ;; *) echo "    key: $k until: $u";; esac
      done;;
    battery:*) mut dumpsys battery "${@:2}"; if [ "${2:-}" = reset ]; then rm -f "$D/battery"; else echo "$*" >> "$D/battery"; fi;;
  esac
}
fake_now() { if [ -f "$D/clock_ms" ]; then rd _now "$D/clock_ms"; else _now=1793000000000; fi; }
repost_expired() {   # cmd alarm set-time X: the system's alarm brings back the snoozed records whose `until` has passed (same key), or drops those
  # their app cancelled meanwhile (sw_ownercancel). Switches (files in $FAKE_STATE, outside the device folder): sw_norepost never reposts,
  # sw_nudge reposts at the second set-time that passes an until (the first one is "too early")
  local f k u need=1 n=0 el=()
  for f in "$D"/snoozed_*; do [ -f "$f" ] || continue; IFS=$'\t' read -r k u < "$f"; [ "$u" -le "$1" ] && el+=("$f"); done
  [ "${#el[@]}" -gt 0 ] || return 0
  [ -f "$FAKE_STATE/jumps" ] && rd n "$FAKE_STATE/jumps"
  n=$((n + 1)); echo "$n" > "$FAKE_STATE/jumps"
  [ -f "$FAKE_STATE/sw_nudge" ] && need=2
  [ -f "$FAKE_STATE/sw_norepost" ] && need=999
  [ "$n" -ge "$need" ] || return 0
  for f in "${el[@]}"; do
    if [ ! -f "$FAKE_STATE/sw_ownercancel" ]; then IFS=$'\t' read -r k u < "$f"; printf '%s' "$k" > "$D/foreign_${f##*snoozed_}"; fi
    command rm -f "$f"
  done
}
notif_cmd() {   # cmd notification list | snooze --for MS KEY | unsnooze KEY (the real one is refused for the shell AND drops the record: probe l)
  local f k u
  case "$1" in
    list)
      echo "0|dev.launcher.app|7|null|10232"
      [[ "$(sget secure enabled_accessibility_services)" == *"$OURS"* ]] && echo "0|android|0|com.android.server.wm.AlertWindowNotification - dev.launcher.app|1000"
      for f in "$D"/notif_*; do [ -f "$f" ] && echo "0|dev.launcher.app|$((1000 + ${f##*notif_}))|null|10232"; done
      if [ ! -f "$FAKE_STATE/sw_listhide" ]; then for f in "$D"/foreign_*; do [ -f "$f" ] && { rd k "$f"; echo "$k"; }; done; fi
      # QA: a record that arrives right after the first list that follows the fixture (between the sweep and the recorded set)
      if [ -f "$D/arrive_after_list" ] && [ -f "$D/notif_1" ]; then rd _k "$D/arrive_after_list"; command rm -f "$D/arrive_after_list"; printf '%s' "$_k" > "$D/foreign_arrived"; fi;;
    snooze)
      mut cmd notification snooze "$2" "$3" "$4"
      if [ -f "$FAKE_STATE/sw_snoozerefused" ]; then echo "Error occurred. Check logcat for details."; return 1; fi
      for f in "$D"/foreign_*; do
        [ -f "$f" ] || continue; rd k "$f"; [ "$k" = "$4" ] || continue
        fake_now; u=$((_now + $3)); [ -f "$FAKE_STATE/sw_snoozeextra" ] && { rd _x "$FAKE_STATE/sw_snoozeextra"; u=$((u + _x)); }
        [ -f "$FAKE_STATE/sw_far_${f##*foreign_}" ] && u=$((u + 604800000))   # QA2: this record's until is a week away (the clock's jump to the others' does not bring it back)
        printf '%s\t%s' "$k" "$u" > "$D/snoozed_${f##*foreign_}"; command rm -f "$f"
        echo "snoozing <$k> until time: (fake)"; return 0
      done
      echo "snoozing <$4>: no such notification";;
    unsnooze)
      mut UNSNOOZE "$2"
      for f in "$D"/snoozed_*; do [ -f "$f" ] || continue; IFS=$'\t' read -r k u < "$f"; [ "$k" = "$2" ] && command rm -f "$f"; done   # (the record is dropped)
      echo "Error occurred. Check logcat for details. Permission Denial: getIntentSender() from pid=1, uid=2000 is not allowed to send as package android"; return 1;;
  esac
}
cmd() {
  case "$1" in
    notification) shift; notif_cmd "$@";;
    alarm) mut cmd alarm "$2" "${3:-}"; printf '%s' "$3" > "$D/clock_ms"; repost_expired "$3";;
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
  if [ -f "$D/foreignonce" ]; then rd _k "$D/foreignonce"; command rm -f "$D/foreignonce"; printf '%s' "$_k" > "$D/foreign_arrived"; fi   # another app's notification (its key is in the file) arrives while a state is taken
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
# other apps' notification records (the key is userId|pkg|id|tag|uid; a tag may hold spaces)
KEY_VEND='0|com.android.vending|951340667|null|10050'
KEY_SPACE='0|com.example.chat|42|chat thread - alice|10101'
add_foreign() { printf '%s' "$2" > "$D/foreign_$1"; }   # NAME KEY
hasF() { grep -qF -- "$1" "$2"; }
lineno() { grep -nF -- "$1" "$S/adb.log" | head -1 | cut -d: -f1; }          # the first adb.log line holding the text
lastlineno() { grep -nF -- "$1" "$S/adb.log" | tail -1 | cut -d: -f1; }
snap() { ( cd "$1/dev" && find . -type f ! -path '*/dev-out/*' ! -path '*/dev-in/*' ! -name 'hang*' -print0 | sort -z | xargs -0 md5sum ); }
muts() { grep -c '^MUT ' "$S/adb.log"; }

# capture.sh against the fake: run_cap [ENV=..] -- ARGS...; output in $S/out.txt, exit in RC
run_cap() {
  local envs=() T0=$SECONDS
  while [ "$1" != -- ]; do envs+=("$1"); shift; done; shift
  env -u MSYS_NO_PATHCONV PATH="$BIN:$PATH" ADB="$BIN/adb" DEVICE=emulator-5554 FID_PRIOR="$S/prior" FAKE_STATE="$S" POLL_SCALE="$POLL_SCALE" "${envs[@]}" \
    timeout "$CAP_TIMEOUT" bash "$REPO/tools/fidelity/capture.sh" "$@" > "$S/out.txt" 2>&1 < /dev/null
  RC=$?
  timed_out capture.sh
}

# ---------------------------------------------------------------- results (a scenario writes lines to $S/result)
FAILS=0
# A run killed by its time bound (timeout exits 124) is told apart from a failed check: "TIMED OUT after N s". The bounds are
# generous multiples of the measured times of a scenario's longest run (see JOBS below), never a part of what a check asserts.
timed_out() { { echo "=== run: $1 (exit $RC)"; cat "$S/out.txt"; } >> "$S/allout.txt"; echo "$((SECONDS - T0)) s  $NAME: $1" >> "$WORK/runs.txt"; [ "$RC" = 124 ] && echo "FAIL  $NAME: TIMED OUT after $CAP_TIMEOUT s ($1 killed by its time bound: a slow machine or too many jobs, not a logic failure)" >> "$S/result"; return 0; }
chk() {   # chk "description" CONDITION-COMMAND...  (records PASS or FAIL in $S/result)
  local d="$1"; shift
  if "$@" > /dev/null 2>&1; then echo "PASS  $NAME: $d" >> "$S/result"; else echo "FAIL  $NAME: $d" >> "$S/result"; fi
}
has() { grep -qE -- "$1" "$2"; }
eq() { [ "$1" = "$2" ]; }
rc_out() { [ "$RC" = "$1" ] && grep -qE -- "$2" "$S/out.txt"; }   # the last run exited with $1 and its output has /$2/
finish() {   # prints the output tail when something failed (or VERBOSE)
  chk "every adb call named the emulator (-s emulator-*)" test "$(grep -c '^NOSERIAL' "$S/adb.log")" = 0
  chk "never cmd notification unsnooze (the shell is refused AND the record is dropped: probe l)" test "$(grep -ci 'unsnooze' "$S/adb.log")" = 0
  if grep -q '^FAIL' "$S/result" || [ -n "$VERBOSE" ]; then
    { echo "        --- $NAME: capture.sh output (last 12 lines):"; tail -12 "$S/out.txt" | sed 's/^/        | /'
      echo "        --- $NAME: MUT calls: $(grep -c '^MUT ' "$S/adb.log")"
      echo "        --- $NAME: WARNING / fork lines of every run, with the run's header (a spurious one under load: fork failures show here):"
      grep -hE '^=== run|WARNING|fork|Resource temporarily|retry' "$S/allout.txt" 2>/dev/null | cut -c1-240 | sed 's/^/        | /'; } >> "$S/result"
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
  add_foreign vend "$KEY_VEND"; add_foreign chat "$KEY_SPACE"
  local before; before="$(snap "$S")"
  run_cap -- --side ours --state quick --theme light --out "$S/out" --dry-run
  chk "exit 0" eq "$RC" 0
  chk "says DRY RUN" has "DRY RUN" "$S/out.txt"
  chk "  and lists the records it would snooze (both keys, the spaced one whole)" test "$(grep -cF -e "$KEY_VEND" -e "$KEY_SPACE" "$S/out.txt")" -ge 1 -a "$(grep -c 'would snooze every notification of another app' "$S/out.txt")" = 1
  chk "  the key with spaces is whole in the line" hasF "$KEY_SPACE" "$S/out.txt"
  chk "  nothing was snoozed" test "$(grep -c 'notification snooze' "$S/adb.log")" = 0
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
sc_foreign() {   # a notification that arrives mid-run (Play Protect's) makes that state fail, never a silent difference; the next state's sweep snoozes it
  NAME="foreign"; new_dev "$NAME" "$ACC_BOTH" ''
  local before; before="$(snap "$S")"
  printf '%s' "$KEY_VEND" > "$D/foreignonce"
  run_cap -- --side stock --state quick,expanded --theme light --out "$S/out"
  chk "exit 1" eq "$RC" 1
  chk "says a notification arrived, naming the package" has "FAIL light/quick r1: a notification arrived.*com.android.vending" "$S/out.txt"
  chk "the next state's sweep snoozed it (its key, 1 h)" hasF "MUT cmd notification snooze --for 3600000 $KEY_VEND" "$S/adb.log"
  chk "  and that state was taken (exit 1 is the first one's)" test "$(grep -c '"state": "expanded"' "$S/out/manifest.json")" = 1 -a "$(grep -c '"state": "quick"' "$S/out/manifest.json")" = 0
  chk "  the arrival is in snoozed.txt for the restore" hasF "$KEY_VEND" "$S/prior/snoozed.txt"
  chk "  and is back at the end (the clock moved past its until)" test -f "$D/foreign_arrived" -a "$(ls "$D"/snoozed_* 2>/dev/null | wc -l)" = 0
  rm -f "$D/foreign_arrived"
  chk "the device is put back all the same" eq "$(snap "$S")" "$before"
  finish
}
sc_snooze() {   # $1 = side: other apps' records (one with spaces in its tag) are snoozed after the clock is set and before the fixture, saved first, brought back by the clock
  NAME="snooze-$1"; new_dev "$NAME" "$ACC_BOTH" ''
  add_foreign vend "$KEY_VEND"; add_foreign chat "$KEY_SPACE"
  local before; before="$(snap "$S")"
  run_cap -- --side "$1" --state quick --theme light --out "$S/out"
  chk "exit 0" eq "$RC" 0
  chk "both records were snoozed for 1 h, the key with spaces whole" test "$(grep -cF -e "MUT cmd notification snooze --for 3600000 $KEY_VEND" -e "MUT cmd notification snooze --for 3600000 $KEY_SPACE" "$S/adb.log")" = 2
  chk "  after the clock was set and before the fixture was posted" test "$(lineno 'MUT cmd alarm set-time 1793613600000')" -lt "$(lineno 'MUT cmd notification snooze')" -a "$(lineno 'MUT cmd notification snooze')" -lt "$(lineno 'Fidelity fixture')"
  chk "  both keys are in snoozed.txt with the until the system reported (the clock then 10:00 + 1 h)" test "$(awk -F'\t' '$2 == 1793617200000' "$S/prior/snoozed.txt" | wc -l)" = 2 -a "$(awk -F'\t' '$2 == ""' "$S/prior/snoozed.txt" | wc -l)" = 2
  chk "  the capture's notification set is the fixed one (no other app)" has '"notifications": "pkg=dev.launcher.app id=1001;pkg=dev.launcher.app id=7;"' "$S/out/manifest.json"
  chk "they were brought back by the clock: past the latest until + 5 s, before the real clock came back" test "$(lineno 'MUT cmd alarm set-time 1793617205000')" -gt 0 -a "$(lastlineno 'MUT cmd alarm set-time 1793617205000')" -lt "$(lastlineno 'MUT settings put global auto_time 1')"
  chk "  both are live again, same keys, none left snoozed" test -f "$D/foreign_vend" -a -f "$D/foreign_chat" -a "$(ls "$D"/snoozed_* 2>/dev/null | wc -l)" = 0
  chk "the device is as before (everything, byte for byte)" eq "$(snap "$S")" "$before"
  chk "  said so, and the snoozed ones are back" has "the snoozed notifications are back" "$S/out.txt"
  chk "no dirty marker left" test ! -e "$S/prior/dirty"
  finish
}
sc_snooze_fail() {   # $1 = what goes wrong with the snoozed records at the restore (a switch of the fake), the rest of the case follows it
  NAME="snooze-$1"; new_dev "$NAME" "$ACC_BOTH" ''
  add_foreign vend "$KEY_VEND"
  local before; before="$(snap "$S")"
  case "$1" in
    norepost)   # the clock passes the until and the system never reposts: both nudges are tried, a warning, the dirty marker stays; --restore finishes it
      : > "$S/sw_norepost"
      run_cap -- --side stock --state quick --theme light --out "$S/out"
      chk "exit 1" eq "$RC" 1
      chk "the clock was moved past the until + 5 s, then 5 s more (the nudge)" test "$(grep -c '^MUT cmd alarm set-time 1793617205000$' "$S/adb.log")" = 1 -a "$(grep -c '^MUT cmd alarm set-time 1793617210000$' "$S/adb.log")" = 1
      chk "  says it moved the clock on once more" has "moving the clock on once more" "$S/out.txt"
      chk "  and warns that the record is still snoozed, naming its key" has "WARNING: still snoozed after the clock passed their until: 0.com.android.vending" "$S/out.txt"
      chk "the dirty marker stays" test -f "$S/prior/dirty"
      chk "the record is still snoozed (not dropped)" test -f "$D/snoozed_vend" -a ! -f "$D/foreign_vend"
      rm -f "$S/sw_norepost"
      run_cap -- --restore
      chk "--restore (the real clock back, the system working again): exit 0" eq "$RC" 0
      chk "  the record is live again and the device is as before" eq "$(snap "$S")" "$before"
      chk "  the dirty marker is gone" test ! -e "$S/prior/dirty";;
    nudge)   # the first jump is too early for the system; the second one brings the record back: no warning
      : > "$S/sw_nudge"
      run_cap -- --side stock --state quick --theme light --out "$S/out"
      chk "exit 0" eq "$RC" 0
      chk "the clock was moved on once more (until + 10 s)" test "$(grep -c '^MUT cmd alarm set-time 1793617205000$' "$S/adb.log")" = 1 -a "$(grep -c '^MUT cmd alarm set-time 1793617210000$' "$S/adb.log")" = 1
      chk "  said so, no warning" test "$(grep -c 'moving the clock on once more' "$S/out.txt")" = 1 -a "$(grep -c WARNING "$S/out.txt")" = 0
      chk "the device is as before" eq "$(snap "$S")" "$before"
      chk "no dirty marker left" test ! -e "$S/prior/dirty";;
    cap)   # an until more than 3 h past the faked base (the system's clock was moved by someone else): no jump, a warning, the dirty marker stays
      printf 14400000 > "$S/sw_snoozeextra"
      run_cap -- --side stock --state quick --theme light --out "$S/out"
      chk "exit 1" eq "$RC" 1
      chk "a warning names the 3 h cap" has "WARNING: the snoozed notifications' until .* more than 3 h past the faked clock's base" "$S/out.txt"
      chk "the clock was not moved past the base + 3 h (no set-time beyond 10:00 + 3 h)" test "$(grep '^MUT cmd alarm set-time' "$S/adb.log" | awk '{ if ($5 > 1793613600000 + 10800000) n++ } END { print n + 0 }')" = 0
      chk "the dirty marker stays and the record is still snoozed" test -f "$S/prior/dirty" -a -f "$D/snoozed_vend"
      chk "  with its key on file" hasF "$KEY_VEND" "$S/prior/snoozed.txt";;
    refused)   # the system refuses the snooze (or it hides nothing): the setup fails naming the key, nothing was snoozed, no jump, the device is put back
      : > "$S/sw_snoozerefused"
      run_cap -- --side stock --state quick --theme light --out "$S/out"
      chk "exit 1" eq "$RC" 1
      chk "says the snooze of the key was refused" hasF "FAIL setup: the snooze of $KEY_VEND was refused" "$S/out.txt"
      chk "no picture was taken" test ! -e "$S/out/stock"
      chk "no clock jump was needed (nothing was pending)" test "$(grep -c '^MUT cmd alarm set-time 17936172' "$S/adb.log")" = 0
      chk "the device is as before" eq "$(snap "$S")" "$before"
      chk "no dirty marker left" test ! -e "$S/prior/dirty";;
    ownercancel)   # the app cancelled the notification while it was snoozed: it is not reposted; a note, no failure
      : > "$S/sw_ownercancel"
      run_cap -- --side stock --state quick --theme light --out "$S/out"
      chk "exit 0" eq "$RC" 0
      chk "a NOTE names the key that is gone" hasF "NOTE: gone since before the run" "$S/out.txt"
      chk "  with the key under it" hasF "$KEY_VEND" "$S/out.txt"
      chk "no warning, the device says it is as before" test "$(grep -c WARNING "$S/out.txt")" = 0 -a "$(grep -c 'the device is as before' "$S/out.txt")" = 1
      chk "the record is not back (cancelled), nothing else differs" eq "$(snap "$S")" "$(echo "$before" | grep -v foreign_vend)"
      chk "no dirty marker left" test ! -e "$S/prior/dirty";;
    listhide)   # a record the list does not show (so it cannot be snoozed) is posted by the dump: the set check at the fixture fails the setup
      : > "$S/sw_listhide"
      run_cap -- --side stock --state quick --theme light --out "$S/out"
      chk "exit 1" eq "$RC" 1
      chk "says another app's notification is still posted after the snooze, naming the package" has "FAIL setup: another app's notification is still posted after the snooze \(pkg=com.android.vending id=951340667\)" "$S/out.txt"
      chk "the device is as before" eq "$(snap "$S")" "$before";;
  esac
  finish
}
sc_apostrophe() {   # a key with a quote is not trusted through two shells: the setup fails naming it, before anything is snoozed
  NAME="apostrophe"; new_dev "$NAME" "$ACC_BOTH" ''
  add_foreign vend "$KEY_VEND"; add_foreign q "0|com.example.chat|7|it's a tag|10101"
  local before; before="$(snap "$S")"
  run_cap -- --side stock --state quick --theme light --out "$S/out"
  chk "exit 1" eq "$RC" 1
  chk "says the key holds a quote, naming it" hasF "FAIL setup: the notification key 0|com.example.chat|7|it's a tag|10101 holds a quote" "$S/out.txt"
  chk "nothing was snoozed (the other record's neither)" test "$(grep -c 'notification snooze' "$S/adb.log")" = 0
  chk "the device is as before" eq "$(snap "$S")" "$before"
  chk "no dirty marker left" test ! -e "$S/prior/dirty"
  finish
}
b_pending() { printf '%s\t%s' "$KEY_VEND" 1793000000000 > "$D/snoozed_x"; }   # someone else's snooze, pending
sc_baseline_gate() {   # baseline.sh (no device: --ours-from) refuses a notification set with another app's package, on either side
  NAME="baseline-gate"; S="$WORK/$NAME"; mkdir -p "$S/gold/FAKE.260101.001" "$S/cap/ours" "$S/capbad/ours" "$S/trees"; : > "$S/adb.log"
  echo '{}' > "$S/masks.json"; echo '{}' > "$S/regions.json"
  local clean='pkg=dev.launcher.app id=1001;pkg=dev.launcher.app id=7;' bad='pkg=com.android.vending id=951340667;pkg=dev.launcher.app id=1001;pkg=dev.launcher.app id=7;'
  printf '{\n "notifications": "%s",\n "files": []\n}\n' "$clean" > "$S/gold/FAKE.260101.001/manifest.json"
  printf '{"setup": {"notifications": "%s"}}\n' "$clean" > "$S/cap/manifest.json"
  printf '{"setup": {"notifications": "%s"}}\n' "$bad" > "$S/capbad/manifest.json"
  baseline() { env -u MSYS_NO_PATHCONV PATH="$BIN:$PATH" ADB="$BIN/adb" DEVICE=emulator-5554 FAKE_STATE="$S" bash "$REPO/tools/fidelity/baseline.sh" --out "$S/out$1" --golden "$S/gold/FAKE.260101.001" --masks "$S/masks.json" --regions "$S/regions.json" --trees "$S/trees" --ours-from "$S/$2" --build FAKE.260101.001 > "$S/out.txt" 2>&1 < /dev/null; RC=$?; }
  baseline 1 cap
  chk "clean sets pass the gate (it stops later: no pictures)" rc_out 1 "ours/light/quick.png is missing"
  baseline 2 capbad
  chk "ours' set with another app's package: exit 1, says so and names it" rc_out 1 "ours' notification set holds another app's notification \(pkg=com.android.vending id=951340667\)"
  printf '{\n "notifications": "%s",\n "files": []\n}\n' "$bad" > "$S/gold/FAKE.260101.001/manifest.json"
  baseline 3 cap
  chk "a golden that holds another app's package: exit 1, says recapture" rc_out 1 "the golden's notification set holds another app's notification.*recapture"
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

wait_hang() { local i; for ((i = 0; i < HANG_WAIT * 10; i++)); do [ -f "$S/dev/hanging" ] && return 0; "$REAL_SLEEP" 0.1; done; echo "FAIL  $NAME: TIMED OUT after $HANG_WAIT s waiting for the run to reach its hang (a kill or a stall, not a logic failure)" >> "$S/result"; return 1; }
run_bg() {   # ARGS: capture.sh in the background -> PID
  : > "$S/out.txt"   # (appended to below: a file opened for append keeps the lines of a trap that runs after a kill)
  env -u MSYS_NO_PATHCONV PATH="$BIN:$PATH" ADB="$BIN/adb" DEVICE=emulator-5554 FID_PRIOR="$S/prior" FAKE_STATE="$S" POLL_SCALE="$POLL_SCALE" \
    bash "$REPO/tools/fidelity/capture.sh" "$@" >> "$S/out.txt" 2>&1 < /dev/null &
  PID=$!
}
sc_sigterm() {   # $1 = side (stock: hangs in the first panel's expand; ours: in its open, with a kept list to put back)
  NAME="sigterm-$1"; new_dev "$NAME" "$ACC_BOTH" '' "$([ "$1" = ours ] && echo "$DEFAULT_LIST")"
  add_foreign chat "$KEY_SPACE"
  local before; before="$(snap "$S")"
  [ "$1" = ours ] && echo open > "$D/hang" || echo expand > "$D/hang"
  run_bg --side "$1" --state quick --theme dark --out "$S/out"
  chk "the run reached its first panel" wait_hang
  kill -TERM "$PID" 2>/dev/null; kill -KILL "$(<"$D/hang.pid")" 2>/dev/null
  wait "$PID"; RC=$?
  chk "exit 130" eq "$RC" 130
  # (the handler's own words never reach the file on this MSYS; its effects do: that it ran is what the next lines prove)
  chk "the other app's record, snoozed for the run, is live again (the clock moved past its until)" test -f "$D/foreign_chat" -a "$(ls "$D"/snoozed_* 2>/dev/null | wc -l)" = 0
  chk "the device is as before" eq "$(snap "$S")" "$before"
  chk "no dirty marker left" test ! -e "$S/prior/dirty"
  finish
}
sc_kill() {   # $1 = "real": the clock was put back to real time before --restore (a person's or auto_time's doing): restore_snoozed moves it forward again
  NAME="kill${1:+-$1}"; new_dev "$NAME" "$ACC_BOTH" '{"format":1,"name":"user","tokens":{"keep.me":{"choice":"x"}}}' "$DEFAULT_LIST"
  add_foreign chat "$KEY_SPACE"
  local before; before="$(snap "$S")"
  echo open > "$D/hang"
  run_bg --side ours --state quick --theme light --out "$S/out"
  chk "the run reached its first panel" wait_hang
  kill -KILL "$PID" 2>/dev/null; kill -KILL "$(<"$D/hang.pid")" 2>/dev/null
  wait "$PID" 2>/dev/null
  chk "the device is left changed (ours' list too)" test "$(cat "$D/qs_tiles")" = "$OURS_WANT"
  chk "the device is left changed" test "$(snap "$S")" != "$before"
  chk "a dirty marker stays" test -f "$S/prior/dirty"
  chk "the other app's record is left snoozed" test -f "$D/snoozed_chat" -a ! -f "$D/foreign_chat"
  chk "  and its key is in snoozed.txt" hasF "$KEY_SPACE" "$S/prior/snoozed.txt"
  if [ -n "${1:-}" ]; then command rm -f "$D/clock_ms"; printf 1 > "$D/s/global/auto_time"; fi   # the real clock is back
  local m; m="$(muts)"
  run_cap -- --side stock --state quick --theme light --out "$S/out3"
  chk "a new run is refused with exit 2" eq "$RC" 2
  chk "  naming the dirty marker and --restore" has "REFUSED.*dirty.*--restore" "$S/out.txt"
  chk "  with no mutating call" eq "$(muts)" "$m"
  run_cap -- --restore
  chk "--restore exits 0" eq "$RC" 0
  chk "--restore brought the record back by moving the clock past its until (once, from the file and dumpsys's pending list)" test "$(grep -c 'MUT cmd alarm set-time 1793617205000' "$S/adb.log")" = 1
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
  local T0=$SECONDS
  env -u MSYS_NO_PATHCONV PATH="$BIN:$PATH" ADB="$BIN/adb" DEVICE="${GDEVICE:-emulator-5554}" FID_PRIOR="$S/prior" FAKE_STATE="$S" POLL_SCALE="$POLL_SCALE" \
    GOLDEN_STAGE="$S/stage" timeout "$CAP_TIMEOUT" bash "$REPO/tools/fidelity/golden.sh" "$@" > "$S/out.txt" 2>&1 < /dev/null
  RC=$?
  timed_out golden.sh
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
  finish
}
sc_golden_against() {   # the middle third of golden's cases (a recapture with another notification set), a scenario of its own so that they run side by side (the longest scenario bounds the run)
  NAME="golden-against"; new_dev "$NAME" "$ACC_BOTH" ''
  real_png "$WORK/golden_shot2.png"; cp "$WORK/golden_shot2.png" "$S/shot.png"
  run_golden capture --root "$S/g2"
  chk "setup: the capture to compare against: exit 0" eq "$RC" 0
  # a golden whose manifest holds another app's notification (one captured before B-062, Play Protect's): verify fails, says recapture; --against too
  mkdir -p "$S/g5"; cp -r "$S/g2/FAKE.260101.001" "$S/g5/"; sed -i 's/"notifications": "/"notifications": "pkg=com.android.vending id=951340667;/' "$S/g5/FAKE.260101.001/manifest.json"
  chk "(a manifest with another app's notification, as an old golden had)" has "com.android.vending id=951340667" "$S/g5/FAKE.260101.001/manifest.json"
  run_golden verify --root "$S/g5"
  chk "verify of a golden holding another app's package fails (exit 1), names it and says recapture" rc_out 1 "FAIL  the manifest records another app's notification \(pkg=com.android.vending id=951340667\).*recapture"
  run_golden verify --root "$S/g2" --against "$S/g5"
  chk "--against a capture with another notification set fails (exit 1) and says so" rc_out 1 "FAIL  --against: the notifications at capture differ.*stored: pkg=dev.launcher.app.*other: .*com.android.vending"
  chk "  and that the other capture's manifest holds another app's notification" has "FAIL  --against: the other capture's manifest records another app's notification" "$S/out.txt"
  run_golden verify --root "$S/g5" --against "$S/g2"
  chk "  the other way round too (exit 1)" rc_out 1 "FAIL  --against: the notifications at capture differ"
  # a capture while another app's record cannot be snoozed away (the fake's list hides it from the harness, the dump shows it): capture.sh fails, no golden
  add_foreign vend "$KEY_VEND"; : > "$S/sw_listhide"; rm -rf "$S/stage"; run_golden capture --root "$S/g6"
  chk "a capture that cannot get rid of another app's notification writes no golden (exit 1)" rc_out 1 "no golden was written"
  chk "  and no folder" test ! -e "$S/g6/FAKE.260101.001"
  rm -f "$S/sw_listhide" "$D/foreign_vend"
  finish
}
sc_golden_diff() {   # the last third of golden's cases (a recapture compared, a changed file, a missing one), again a scenario of its own
  NAME="golden-diff"; new_dev "$NAME" "$ACC_BOTH" ''
  real_png "$WORK/golden_shot3.png"; cp "$WORK/golden_shot3.png" "$S/shot.png"
  run_golden capture --root "$S/g2"
  chk "setup: the capture to compare against: exit 0" eq "$RC" 0
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
  chk "  Android's own overlay notice (pkg=android id=0, present while ours is on) is no record: not in the set, so no warning" test "$(grep -c 'pkg=android' "$S/out1/manifest.json")" = 0
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
  chk "capture.sh never runs cmd notification unsnooze (a shell call to it is nowhere)" test "$(grep -cE '(a|ap|ash) +shell.*notification +unsnooze' "$REPO/tools/fidelity/capture.sh")" = 0
  local fi1 fi2
  fi1="$(bash -c ". '$REPO/tools/fidelity/capture.sh'; r=\"\$(foreign_in 'pkg=dev.launcher.app id=7;pkg=com.x.y id=2;pkg=dev.launcher.app id=1001;')\"; echo \"\$r|rc=\$?\"" 2>&1)"
  fi2="$(bash -c ". '$REPO/tools/fidelity/capture.sh'; r=\"\$(foreign_in 'pkg=dev.launcher.app id=7;pkg=dev.launcher.app id=1001;')\"; echo \"\$r|rc=\$?\"" 2>&1)"
  chk "foreign_in names the other app's entry (and is true)" eq "$fi1" "pkg=com.x.y id=2|rc=0"
  chk "  and is false for ours alone" eq "$fi2" "|rc=1"
  finish
}

# ---------------------------------------------------------------- QA additions (B-062 part 1): attacks on the snooze model
not() { ! "$@"; }
sc_qa_pendfmt() {   # $1 = how the dump writes its pending list (nountil | eq | none): no `until` can be read. The record then stays snoozed
  # until the faked date (days away): the run must not end exit 0 and "as before" with the record silently gone.
  NAME="qa-pendfmt-$1"; new_dev "$NAME" "$ACC_BOTH" ''
  add_foreign vend "$KEY_VEND"
  printf '%s' "$1" > "$S/sw_pendfmt"
  run_cap -- --side stock --state quick --theme light --out "$S/out"
  chk "the key reached snoozed.txt (saved before the snooze)" hasF "$KEY_VEND" "$S/prior/snoozed.txt"
  chk "no silent loss: the record is live again, OR the run warned/failed and kept the dirty marker" \
      bash -c '[ -f "$0/dev/foreign_vend" ] || { [ "$1" != 0 ] && grep -q "WARNING" "$0/out.txt" && [ -f "$0/prior/dirty" ]; }' "$S" "$RC"
  chk "it did not say 'the device is as before' while the record is still snoozed" \
      bash -c '! { [ -f "$0/dev/snoozed_vend" ] && grep -q "the device is as before" "$0/out.txt"; }' "$S"
  finish
}
sc_qa_kill() {   # $1 = keyonly (killed before the until was saved) | neversnoozed (killed after the key was saved, before the snooze)
  NAME="qa-kill-$1"; new_dev "$NAME" "$ACC_BOTH" ''
  add_foreign chat "$KEY_SPACE"
  local before; before="$(snap "$S")"
  echo expand > "$D/hang"
  run_bg --side stock --state quick --theme light --out "$S/out"
  chk "the run reached its first panel" wait_hang
  kill -KILL "$PID" 2>/dev/null; kill -KILL "$(<"$D/hang.pid")" 2>/dev/null
  wait "$PID" 2>/dev/null
  chk "the record is snoozed, the run left it so" test -f "$D/snoozed_chat"
  case "$1" in
    keyonly) printf '%s\t\n' "$KEY_SPACE" > "$S/prior/snoozed.txt";;
    neversnoozed) IFS=$'	' read -r _k _u < "$D/snoozed_chat"; printf '%s' "$_k" > "$D/foreign_chat"; rm -f "$D/snoozed_chat"; before="$(snap "$S")"; before="$(echo "$before" | grep -v 'prior' )";;
  esac
  [ "$1" = neversnoozed ] && before="$(snap "$S")"
  run_cap -- --restore
  chk "--restore exits 0" eq "$RC" 0
  chk "the record is live, nothing left snoozed" test -f "$D/foreign_chat" -a "$(ls "$D"/snoozed_* 2>/dev/null | wc -l)" = 0
  chk "no dirty marker" test ! -e "$S/prior/dirty"
  run_cap -- --restore
  chk "a second --restore: exit 0, nothing to do" eq "$RC" 0
  finish
}
sc_qa_cap() {   # $1 = edge | over | edge-norepost: the until sits exactly at / 1 ms past the 3 h cap (until + 5 s = base + 3 h)
  NAME="qa-cap-$1"; new_dev "$NAME" "$ACC_BOTH" ''
  add_foreign vend "$KEY_VEND"
  case "$1" in over) printf 7195001 > "$S/sw_snoozeextra";; *) printf 7195000 > "$S/sw_snoozeextra";; esac
  [ "$1" = edge-norepost ] && : > "$S/sw_norepost"
  run_cap -- --side stock --state quick --theme light --out "$S/out"
  local cap=$((1793613600000 + 10800000))
  case "$1" in
    edge) chk "exit 0, the record is back (the cap is inclusive)" eq "$RC" 0; chk "  the clock went to exactly base + 3 h" test "$(grep -c "^MUT cmd alarm set-time $cap\$" "$S/adb.log")" = 1;;
    over) chk "exit 1 with the cap warning" rc_out 1 "more than 3 h past";;
    edge-norepost) chk "the clock never went past base + 3 h, not even for the nudge" test "$(grep '^MUT cmd alarm set-time' "$S/adb.log" | awk -v c=$cap '$5 > c { n++ } END { print n + 0 }')" = 0;;
  esac
  finish
}
sc_qa_arrive() {   # a record arriving after the last sweep and before the set is recorded: the setup fails, nothing is recorded, the device is put back
  NAME="qa-arrive"; new_dev "$NAME" "$ACC_BOTH" ''
  local before; before="$(snap "$S")"
  printf '%s' "$KEY_VEND" > "$D/arrive_after_list"
  run_cap -- --side stock --state quick --theme light --out "$S/out"
  chk "exit 1" eq "$RC" 1
  chk "FAIL setup names the package" has "FAIL setup: another app's notification is still posted after the snooze.*com.android.vending" "$S/out.txt"
  chk "no picture" test ! -e "$S/out/stock/light/quick.png"
  rm -f "$D/foreign_arrived"
  chk "the device is as before" eq "$(snap "$S")" "$before"
  finish
}
sc_qa_keys() {   # keys with '|' in the tag, shell metacharacters, a backslash, a quote-free but nasty tag
  NAME="qa-keys"; new_dev "$NAME" "$ACC_BOTH" ''
  add_foreign pipe '0|com.example.p|1|a|b|c|10101'
  add_foreign meta '0|com.example.q|2|$HOME `id` "q" * ; &|10101'
  add_foreign bsl '0|com.example.r|3|C:\dir\new until: 5|10101'
  local before; before="$(snap "$S")"
  run_cap -- --side ours --state quick --theme light --out "$S/out"
  chk "exit 0" eq "$RC" 0
  chk "three snoozes" eq "$(grep -c '^MUT cmd notification snooze' "$S/adb.log")" 3
  chk "all three keys are in snoozed.txt with an until" test "$(awk -F'\t' '$2 ~ /^[0-9]+$/' "$S/prior/snoozed.txt" | wc -l)" = 3
  chk "all three are live again, nothing snoozed" test -f "$D/foreign_pipe" -a -f "$D/foreign_meta" -a -f "$D/foreign_bsl" -a "$(ls "$D"/snoozed_* 2>/dev/null | wc -l)" = 0
  chk "the device is as before" eq "$(snap "$S")" "$before"
  finish
}
sc_qa_dry_pending() {   # --dry-run with a pending snooze that is not ours: refused, nothing touched
  NAME="qa-dry-pending"; new_dev "$NAME" "$ACC_BOTH" ''
  b_pending
  local before; before="$(snap "$S")"
  run_cap -- --side ours --state quick --theme light --out "$S/out" --dry-run
  chk "exit 2 and REFUSED" rc_out 2 "REFUSED.*pending snooze"
  chk "no mutating call" eq "$(muts)" 0
  chk "the device is as before" eq "$(snap "$S")" "$before"
  finish
}
sc_qa_rerun() {   # two clean runs one after the other (different records): the second starts from an empty snoozed.txt and restores only its own
  NAME="qa-rerun"; new_dev "$NAME" "$ACC_BOTH" ''
  add_foreign vend "$KEY_VEND"
  run_cap -- --side stock --state quick --theme light --out "$S/out"
  chk "run 1 exit 0" eq "$RC" 0
  rm -f "$D/foreign_vend"; add_foreign chat "$KEY_SPACE"
  local before; before="$(snap "$S")"
  run_cap -- --side stock --state quick --theme light --out "$S/out2"
  chk "run 2 exit 0" eq "$RC" 0
  chk "snoozed.txt holds only run 2's key" test "$(grep -c vending "$S/prior/snoozed.txt")" = 0 -a "$(grep -c example.chat "$S/prior/snoozed.txt")" -ge 1
  chk "the device is as before run 2" eq "$(snap "$S")" "$before"
  finish
}

# ---------------------------------------------------------------- QA round 2 (B-062 part 1, revision 2): attacks on the three fixes
# Per-record switches of the fake (files in the scenario folder, named by the record's file name): sw_nountil_NAME (the pending list
# shows that one record without an until), sw_far_NAME (its until is a week away: the clock's jump to the others' does not bring it back).
after_teardown() { sed -n '/putting the device back/,$p' "$1"; }   # the lines of a run from its teardown on
sc_qa2_mix() {   # $1 = posted | far: vend has a readable until, chat (spaces in its key) has none
  NAME="qa2-mix-$1"; new_dev "$NAME" "$ACC_BOTH" ''
  add_foreign vend "$KEY_VEND"; add_foreign chat "$KEY_SPACE"
  local before; before="$(snap "$S")"
  : > "$S/sw_nountil_chat"; [ "$1" = far ] && : > "$S/sw_far_chat"
  run_cap -- --side stock --state quick --theme light --out "$S/out"
  chk "both keys are in snoozed.txt; vend with its until, chat without" test "$(grep -cxF "$KEY_VEND$(printf '\t')1793617200000" "$S/prior/snoozed.txt")" = 1 -a "$(awk -F"	" -v k="$KEY_SPACE" '$1 == k && $2 == ""' "$S/prior/snoozed.txt" | wc -l)" = 1
  chk "a warning at the snooze: no until was read, naming the key" has "WARNING: no until was read for the snoozed $(printf '%s' "$KEY_SPACE" | sed 's/[|]/[|]/g')" "$S/out.txt"
  chk "exactly one such warning (vend's until was read)" test "$(grep -c 'WARNING: no until was read' "$S/out.txt")" = 1
  case "$1" in
    posted)   # the jump for vend's until brings chat back too: nothing is lost, so no warning at the end, exit 0
      chk "exit 0" eq "$RC" 0
      chk "no warning from the teardown on" test "$(after_teardown "$S/out.txt" | grep -c WARNING)" = 0
      chk "both live, nothing snoozed, the device as before" test -f "$D/foreign_vend" -a -f "$D/foreign_chat" -a "$(ls "$D"/snoozed_* 2>/dev/null | wc -l)" = 0
      chk "  byte for byte" eq "$(snap "$S")" "$before"
      chk "no dirty marker" test ! -e "$S/prior/dirty";;
    far)   # only vend comes back; chat stays snoozed for a week, unreadable: a warning naming chat alone, the dirty marker kept
      chk "exit 1" eq "$RC" 1
      chk "the end warns about chat's key" hasF "    $KEY_SPACE" "$S/out.txt"
      chk "  and not about vend's" test "$(after_teardown "$S/out.txt" | grep -cF "$KEY_VEND")" = 0
      chk "  with the warning text" test "$(after_teardown "$S/out.txt" | grep -c 'WARNING: snoozed by this run with no until read')" = 1
      chk "did not say the device is as before" test "$(grep -c 'the device is as before' "$S/out.txt")" = 0
      chk "vend is live again, chat still snoozed (not dropped)" test -f "$D/foreign_vend" -a -f "$D/snoozed_chat" -a ! -f "$D/foreign_chat"
      chk "the dirty marker stays" test -f "$S/prior/dirty"
      run_cap -- --restore
      chk "--restore (still unreadable): exit 1, the same warning, naming chat" rc_out 1 "snoozed by this run with no until read"
      chk "  the key is in it" hasF "    $KEY_SPACE" "$S/out.txt"
      chk "  the dirty marker stays" test -f "$S/prior/dirty"
      run_cap -- --restore
      chk "a second --restore: the same (exit 1, warning, dirty)" rc_out 1 "snoozed by this run with no until read"
      chk "  dirty still there" test -f "$S/prior/dirty"
      rm -f "$D/snoozed_chat"; printf "%s" "$KEY_SPACE" > "$D/foreign_chat"   # the app posted it again
      run_cap -- --restore
      chk "--restore after chat is posted again: exit 0, no warning" eq "$RC" 0
      chk "  dirty marker gone, device as before" test ! -e "$S/prior/dirty" -a "$(snap "$S")" = "$before";;
  esac
  finish
}
sc_qa2_mid() {   # $1 = alone | with-known: a record arrives mid-run and is snoozed by the sweep before the next state, with no until readable
  NAME="qa2-mid-$1"; new_dev "$NAME" "$ACC_BOTH" ''
  [ "$1" = with-known ] && add_foreign vend "$KEY_VEND"
  : > "$S/sw_nountil_arrived"
  printf '%s' "$KEY_SPACE" > "$D/foreignonce"
  run_cap -- --side stock --state quick,expanded --theme light --out "$S/out"
  chk "exit 1 (the arrival fails its state anyway)" eq "$RC" 1
  chk "the sweep snoozed it" hasF "MUT cmd notification snooze --for 3600000 $KEY_SPACE" "$S/adb.log"
  chk "a snooze-time warning for it" hasF "WARNING: no until was read for the snoozed $KEY_SPACE" "$S/out.txt"
  chk "the end warns it is not known to be back (its key under the warning)" test "$(after_teardown "$S/out.txt" | grep -cF "    $KEY_SPACE")" = 1
  chk "the dirty marker stays" test -f "$S/prior/dirty"
  chk "no silent loss: the record is live or the warning stands" bash -c '[ -f "$0/dev/foreign_arrived" ] || [ -f "$0/prior/dirty" ]' "$S"
  [ "$1" = with-known ] && chk "the known one is back (the jump), not in the warning" test -f "$D/foreign_vend" -a "$(after_teardown "$S/out.txt" | grep -cF "$KEY_VEND")" = 0
  finish
}
sc_qa2_restore() {   # $1 = none | nountil | eq: killed with a record snoozed whose until could not be read; --restore three times, then the list is readable
  NAME="qa2-restore-$1"; new_dev "$NAME" "$ACC_BOTH" ''
  add_foreign chat "$KEY_SPACE"
  local before; before="$(snap "$S")"
  printf '%s' "$1" > "$S/sw_pendfmt"
  echo expand > "$D/hang"
  run_bg --side stock --state quick --theme light --out "$S/out"
  chk "the run reached its first panel" wait_hang
  kill -KILL "$PID" 2>/dev/null; kill -KILL "$(<"$D/hang.pid")" 2>/dev/null
  wait "$PID" 2>/dev/null
  chk "chat's key is on file without an until" test "$(awk -F"	" -v k="$KEY_SPACE" '$1 == k && $2 == ""' "$S/prior/snoozed.txt" | wc -l)" = 1
  local i
  for i in 1 2; do
    run_cap -- --restore
    chk "--restore $i: exit 1 with the warning naming the key" rc_out 1 "snoozed by this run with no until read"
    chk "  key listed, dirty kept, not 'as before', record untouched" test "$(grep -cF "    $KEY_SPACE" "$S/out.txt")" = 1 -a -f "$S/prior/dirty" -a "$(grep -c 'the device is as before' "$S/out.txt")" = 0 -a -f "$D/snoozed_chat"
  done
  rm -f "$S/sw_pendfmt"   # the list is readable again
  run_cap -- --restore
  chk "--restore with a readable list: exit 0, the record is back" eq "$RC" 0
  chk "  dirty gone, device as before" test ! -e "$S/prior/dirty" -a "$(snap "$S")" = "$before"
  finish
}
sc_qa2_known_unreadable() {   # the until WAS read at the snooze; at the restore the pending list is unreadable (dumpsys failing, another form): no jump is made
  NAME="qa2-known-unreadable"; new_dev "$NAME" "$ACC_BOTH" ''
  add_foreign chat "$KEY_SPACE"
  echo expand > "$D/hang"
  run_bg --side stock --state quick --theme light --out "$S/out"
  chk "the run reached its first panel" wait_hang
  kill -KILL "$PID" 2>/dev/null; kill -KILL "$(<"$D/hang.pid")" 2>/dev/null
  wait "$PID" 2>/dev/null
  chk "the until was read at the snooze" test "$(grep -cxF "$KEY_SPACE$(printf '\t')1793617200000" "$S/prior/snoozed.txt")" = 1
  printf none > "$S/sw_pendfmt"
  run_cap -- --restore
  chk "no silent loss: the record is live again, or the run warned (exit 1) and kept the dirty marker" \
      bash -c '[ -f "$0/dev/foreign_chat" ] || { [ "$1" != 0 ] && [ -f "$0/prior/dirty" ]; }' "$S" "$RC"
  chk "  not 'the device is as before' with the record still snoozed" bash -c '! { [ -f "$0/dev/snoozed_chat" ] && grep -q "the device is as before" "$0/out.txt"; }' "$S"
  finish
}
sc_qa2_cap() {   # the first jump is already at base + 3 h and the system does not repost: the nudge stays inside the cap too; --restore finishes it
  NAME="qa2-cap-edge"; new_dev "$NAME" "$ACC_BOTH" ''
  add_foreign vend "$KEY_VEND"
  printf 7195000 > "$S/sw_snoozeextra"; : > "$S/sw_norepost"
  local cap=$((1793613600000 + 10800000)) before; before="$(snap "$S")"
  run_cap -- --side stock --state quick --theme light --out "$S/out"
  chk "exit 1, the still-snoozed warning, dirty kept" rc_out 1 "WARNING: still snoozed after the clock passed their until"
  chk "  dirty" test -f "$S/prior/dirty"
  chk "no set-time past base + 3 h in the run" test "$(grep '^MUT cmd alarm set-time' "$S/adb.log" | awk -v c=$cap '$5 > c { n++ } END { print n + 0 }')" = 0
  chk "  the clock did go to exactly the cap" test "$(grep -c "^MUT cmd alarm set-time $cap\$" "$S/adb.log")" -ge 1
  rm -f "$S/sw_norepost"
  run_cap -- --restore
  chk "--restore: exit 0, the record is back, within the cap" eq "$RC" 0
  chk "  no set-time past the cap in any run" test "$(grep '^MUT cmd alarm set-time' "$S/adb.log" | awk -v c=$cap '$5 > c { n++ } END { print n + 0 }')" = 0
  chk "  as before" eq "$(snap "$S")" "$before"
  finish
}
sc_qa2_keys() {   # a key with backslash escapes, spaces, extra pipes and a fake "until:" in its tag: its until reaches snoozed.txt whole and it comes back
  NAME="qa2-keys"; new_dev "$NAME" "$ACC_BOTH" ''
  local K='0|com.example.s|9|a\tb\n c\\d \x41 \0 \c \"|z | e until: 7 \|10101'
  add_foreign nasty "$K"
  local before; before="$(snap "$S")"
  run_cap -- --side ours --state quick --theme light --out "$S/out"
  chk "exit 0" eq "$RC" 0
  chk "snoozed.txt holds the key byte for byte with the until the system gave" test "$(grep -cxF "$K$(printf '\t')1793617200000" "$S/prior/snoozed.txt")" = 1
  chk "no no-until warning" test "$(grep -c 'no until was read' "$S/out.txt")" = 0
  chk "the record is live again, nothing snoozed, as before" test -f "$D/foreign_nasty" -a "$(ls "$D"/snoozed_* 2>/dev/null | wc -l)" = 0 -a "$(snap "$S")" = "$before"
  chk "no warning at all" test "$(grep -c WARNING "$S/out.txt")" = 0
  finish
}

# ---------------------------------------------------------------- QA round 3 (B-062 part 1, revision 3): the clock-jump condition of the "cancelled by its app" note
sc_qa3_edge() {   # $1 = eq | below | above | overcap | gone-alone | unreadable-then-readable
  # vend and chat are snoozed (same until U) and the run is killed; chat's record is then gone from the device (its app cancelled it, no
  # longer pending) and snoozed.txt names a known until for it: restore moves the clock to T = U + 5 s for vend, and chat is explained
  # (a NOTE) only when its until <= T; above T, or when no jump is made, it is a warning with the dirty marker kept.
  NAME="qa3-$1"; new_dev "$NAME" "$ACC_BOTH" ''
  add_foreign vend "$KEY_VEND"; add_foreign chat "$KEY_SPACE"
  local before; before="$(snap "$S")"
  echo expand > "$D/hang"
  run_bg --side stock --state quick --theme light --out "$S/out"
  chk "the run reached its first panel" wait_hang
  kill -KILL "$PID" 2>/dev/null; kill -KILL "$(<"$D/hang.pid")" 2>/dev/null
  wait "$PID" 2>/dev/null
  local U=1793617200000 T=$((1793617200000 + 5000)) cap=$((1793613600000 + 10800000)) uB
  chk "both untils were read at the snooze (U)" test "$(grep -c "$(printf '\t')$U\$" "$S/prior/snoozed.txt")" = 2
  case "$1" in
    eq) uB=$T;; below) uB=$((T - 1));; above) uB=$((T + 1));; overcap) uB=$((cap + 1));; *) uB=$U;;
  esac
  if [ "$1" != unreadable-then-readable ]; then
    printf '%s\t%s\n' "$KEY_SPACE" "$uB" >> "$S/prior/snoozed.txt"   # (the last until of a key wins)
    rm -f "$D/snoozed_chat"   # its app cancelled it while snoozed: no longer pending, never posted again
  fi
  [ "$1" = gone-alone ] && { rm -f "$D/snoozed_vend"; printf '%s' "$KEY_VEND" > "$D/foreign_vend"; }   # vend is back already: nothing is pending, no jump will be made
  [ "$1" = unreadable-then-readable ] && printf none > "$S/sw_pendfmt"
  run_cap -- --restore
  case "$1" in
    eq|below)
      chk "exit 0 (the clock passed chat's until: cancelled by its app, a NOTE)" eq "$RC" 0
      chk "  a NOTE names it" hasF "NOTE: gone since before the run" "$S/out.txt"
      chk "  with its key" hasF "$KEY_SPACE" "$S/out.txt"
      chk "  no warning, 'as before' said, no dirty marker" test "$(grep -c WARNING "$S/out.txt")" = 0 -a "$(grep -c 'the device is as before' "$S/out.txt")" = 1 -a ! -e "$S/prior/dirty"
      chk "  vend is live again, chat gone" test -f "$D/foreign_vend" -a ! -e "$D/foreign_chat" -a "$(ls "$D"/snoozed_* 2>/dev/null | wc -l)" = 0
      chk "  the clock went to exactly U + 5 s" test "$(grep -c "^MUT cmd alarm set-time $T\$" "$S/adb.log")" -ge 1;;
    above|overcap)
      chk "exit 1 (chat's until was not passed by the clock: not known to be back)" eq "$RC" 1
      chk "  the warning names chat's key" hasF "    $KEY_SPACE" "$S/out.txt"
      chk "  with the 'until not passed' text" has "WARNING: snoozed by this run with no until read \(or its until not passed by the clock in this restore\)" "$S/out.txt"
      chk "  vend is not in it (it came back)" test "$(after_teardown "$S/out.txt" | grep -cF "$KEY_VEND")" = 0
      chk "  not 'as before', dirty kept" test "$(grep -c 'the device is as before' "$S/out.txt")" = 0 -a -f "$S/prior/dirty"
      run_cap -- --restore
      chk "a second --restore: still exit 1, still the warning, dirty kept (no jump this time either)" rc_out 1 "or its until not passed by the clock"
      chk "  dirty" test -f "$S/prior/dirty"
      printf '%s' "$KEY_SPACE" > "$D/foreign_chat"   # its app posted it again
      run_cap -- --restore
      chk "--restore once chat is posted: exit 0, dirty gone, as before" eq "$RC" 0
      chk "  dirty gone" test ! -e "$S/prior/dirty"
      chk "  device as before" eq "$(snap "$S")" "$before";;
    gone-alone)   # nothing pending at all (both gone/back): no jump is made, chat's known until cannot be told from a cancel: a warning (conservative), never silent
      chk "exit 1 and the dirty marker kept, chat named" bash -c '[ "$1" = 1 ] && [ -f "$0/prior/dirty" ] && grep -qF -- "    $2" "$0/out.txt"' "$S" "$RC" "$KEY_SPACE"
      chk "  no clock jump was made" test "$(grep -c '^MUT cmd alarm set-time 17936172' "$S/adb.log")" = 0;;
    unreadable-then-readable)
      chk "unreadable list, nothing moved: exit 1, warned, dirty kept, not 'as before'" bash -c '[ "$1" = 1 ] && [ -f "$0/prior/dirty" ] && ! grep -q "the device is as before" "$0/out.txt" && grep -q "or its until not passed" "$0/out.txt"' "$S" "$RC"
      chk "  both records still snoozed, none dropped" test -f "$D/snoozed_vend" -a -f "$D/snoozed_chat"
      rm -f "$S/sw_pendfmt"
      run_cap -- --restore
      chk "a second --restore with the list readable: exit 0, both back, no dirty marker" bash -c '[ "$1" = 0 ] && [ ! -e "$0/prior/dirty" ] && [ -f "$0/dev/foreign_vend" ] && [ -f "$0/dev/foreign_chat" ]' "$S" "$RC"
      chk "  device as before" eq "$(snap "$S")" "$before";;
  esac
  finish
}

# ---------------------------------------------------------------- run them, JOBS_MAX at a time (the slowest first)
JOBS=()
timed() { local t0=$SECONDS; "$@"; echo "$((SECONDS - t0)) s  $NAME" >> "$WORK/times.txt"; }
job() {
  [ -z "${ONLY:-}" ] || [[ " $* " == *" $ONLY "* ]] || return 0
  while [ "$(jobs -rp | wc -l)" -ge "$JOBS_MAX" ]; do wait -n 2>/dev/null || true; done
  ( timed "$@" ) & JOBS+=($!)
}
job sc_golden   # (the longest ones first, golden in three scenarios so that its parts overlap: the longest bounds the run)
job sc_golden_against
job sc_golden_diff
job sc_notifwarn
job sc_slowclock
job sc_refuse sdk b_sdk "not Android 17"
job sc_refuse size b_size "display size"
job sc_refuse shizuku b_shizuku "2 shizuku_server"
job sc_refuse focus b_focus "HomeActivity is not in front"
job sc_refuse font b_font "font scale"
job sc_refuse palette b_palette "palette"
job sc_refuse dirty b_dirty "dirty"
job sc_refuse manifest b_manifest "already holds a capture"
job sc_refuse notif b_notif "another test notification"
job sc_refuse pending b_pending "pending snooze"
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
job sc_foreign
job sc_snooze stock
job sc_snooze ours
job sc_snooze_fail norepost
job sc_snooze_fail nudge
job sc_snooze_fail cap
job sc_snooze_fail refused
job sc_snooze_fail ownercancel
job sc_snooze_fail listhide
job sc_apostrophe
job sc_baseline_gate
job sc_sigterm stock
job sc_sigterm ours
job sc_kill
job sc_kill real
job sc_nostrip
job sc_static
job sc_qa_pendfmt nountil
job sc_qa_pendfmt eq
job sc_qa_pendfmt none
job sc_qa_kill keyonly
job sc_qa_kill neversnoozed
job sc_qa_cap edge
job sc_qa_cap over
job sc_qa_cap edge-norepost
job sc_qa_arrive
job sc_qa_keys
job sc_qa_dry_pending
job sc_qa_rerun
job sc_qa2_mix posted
job sc_qa2_mix far
job sc_qa2_mid alone
job sc_qa2_mid with-known
job sc_qa2_restore none
job sc_qa2_restore nountil
job sc_qa2_restore eq
job sc_qa2_known_unreadable
job sc_qa2_cap
job sc_qa2_keys
job sc_qa3_edge eq
job sc_qa3_edge below
job sc_qa3_edge above
job sc_qa3_edge overcap
job sc_qa3_edge gone-alone
job sc_qa3_edge unreadable-then-readable
for j in "${JOBS[@]}"; do wait "$j"; done

[ -z "$VERBOSE" ] || { sort -rn "$WORK/times.txt" 2>/dev/null | sed 's/^/time  /'; sort -rn "$WORK/runs.txt" 2>/dev/null | head -5 | sed 's/^/run   /'; }
for r in "$WORK"/*/result; do [ -f "$r" ] && cat "$r"; done > "$WORK/all.txt"
cat "$WORK/all.txt"
FAILS=$(grep -c '^FAIL' "$WORK/all.txt"); PASSES=$(grep -c '^PASS' "$WORK/all.txt")
SCEN=$(ls "$WORK"/*/result 2>/dev/null | wc -l)
echo "fidelity_capture_test: $SCEN scenarios, $PASSES checks passed, $FAILS failed"
[ "$FAILS" = 0 ] && { [ -n "${ONLY:-}" ] || [ "$SCEN" = 72 ]; }
