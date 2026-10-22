#!/usr/bin/env bash
# Emulator smoke (M1, D-016): installs the local APK, checks the gesture strip, opens and closes an app through
# Spotlight, and opens and closes each panel with real touchscreen pulls. Exit 0 pass, 1 a step failed, 2 preflight/usage.
# Emulator only (refuses any serial that is not emulator-*). It never stops the app by force, never starts Shizuku, never enables
# accessibility (preflight prints the command instead). Not part of scripts/check.sh (needs a running emulator).
#   [DEVICE=emulator-5554] [APP_QUERY=Clock APP_PKG=com.google.android.deskclock] tools/smoke_emulator.sh
# Default app: Clock on Medium_Phone (Android 17): "Clock" has one unambiguous Spotlight top hit and opens without a
# permission dialog (tried 2026-10-22). Every wait below is a bounded poll.
# EXCLUSIVE USE: it clears logcat (all buffers), reinstalls the app (tools/device.sh install makes our app the emulator's
# default home) and changes the shade setup (put back at the end, also on a failure or an interrupt), so nothing else may
# drive the emulator meanwhile (CLAUDE.md: agree on it first).
cd "$(dirname "$0")/.." || exit 2
DEVICE="${DEVICE:-emulator-5554}"
APP_QUERY="${APP_QUERY:-Clock}"
APP_PKG="${APP_PKG:-com.google.android.deskclock}"
export DEVICE
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"; [ -x "$ADB" ] || ADB=adb
export ADB
PKG=dev.launcher.app
APK=app/build/outputs/apk/debug/app-debug.apk
W=1080; H=2400   # replaced by the real size once the device is known
SHADE_CHANGED=""; SHADE_PRIOR=""; STARTED=""   # STARTED: set once the run begins to change the emulator (cleanup acts only then)

a() { "$ADB" -s "$DEVICE" "$@"; }
focus() { a shell dumpsys window | grep mCurrentFocus | tr -d '\r'; }
logs() { a logcat -d -s Launcher:I | tr -d '\r'; }
cnt() { logs | grep -cE "$1"; }       # lines of our log matching a pattern
bar_up() { tools/device.sh swipe $((W/2)) $((H-12)) $((W/2)) $((H*45/100)) 220 >/dev/null; }   # long enough to count as a release past the threshold on a coarse emulator swipe
# poll SECONDS CMD...: true as soon as CMD succeeds, false after the time (4 tries a second)
poll() { local n=$(( $1 * 4 )) i; shift; for ((i = 0; i < n; i++)); do "$@" && return 0; sleep 0.25; done; return 1; }
focus_has() { focus | grep -q "$1"; }
focus_has_any() { focus | grep -qE "$1"; }
# (Reads the last "[setup]" line from logcat: the log was cleared just before the install and the app logs its setup at start
# and after each change. If the buffer ever rotated that line out, a step reads nothing and the run FAILs; it can never PASS
# on a stale line, since a PASS needs a fresh line after each change.)
# The shade setup as the log last said it, in Setup.describe's form, options sorted: "layout (placement) {k=v, k=v}"
shade_now() {
  local raw head opts
  raw=$(logs | grep '\[setup\]' | grep -oE 'shade:? [a-z0-9-]+ \([a-z0-9-]+\)( \{[^}]*\})?' | tail -1 | sed -E 's/^shade:? //')
  [ -n "$raw" ] || return 0
  head=${raw%% \{*}
  case "$raw" in *" {"*) opts=${raw#* \{}; opts=${opts%\}}; opts=$(echo "$opts" | tr ',' '\n' | sed 's/^ *//' | sort | paste -sd, - | sed 's/,/, /g'); echo "$head {$opts}";; *) echo "$head";; esac
}
shade_head() { echo "${1%% \{*}"; }
shade_is() { [ "$(shade_head "$(shade_now)")" = "$1 ($2)" ]; }   # LAYOUT PLACEMENT; reads the log on every try
shade_back() { [ "$(shade_now)" = "$SHADE_PRIOR" ]; }
set_shade() { # LAYOUT PLACEMENT: sends the broadcast and waits for the log to say so
  a shell am broadcast -a dev.launcher.app.SETUP -p $PKG --es element shade --es layout "$1" --es placement "$2" >/dev/null
  poll 6 shade_is "$1" "$2"
}
restore_shade() { # puts the whole saved shade setup back: layout, placement, then each option
  [ -n "$SHADE_CHANGED" ] && [ -n "$SHADE_PRIOR" ] || return 0
  local head=$(shade_head "$SHADE_PRIOR") lay pl opts kv
  lay=${head%% *}; pl=${head#*(}; pl=${pl%)}
  set_shade "$lay" "$pl"
  case "$SHADE_PRIOR" in *" {"*) opts=${SHADE_PRIOR#* \{}; opts=${opts%\}}
    while IFS= read -r kv; do
      [ -n "$kv" ] && a shell am broadcast -a dev.launcher.app.SETUP -p $PKG --es element shade --es option "$kv" >/dev/null
    done < <(echo "$opts" | tr ',' '\n' | sed 's/^ *//');; esac
  poll 6 shade_back
}
cleanup() { # closes any open panel, then restores the shade setup; warns when the setup does not match
  [ -n "$STARTED" ] || return 0   # nothing touched yet (preflight)
  bar_up; sleep 1
  restore_shade >/dev/null 2>&1
  if [ -n "$SHADE_CHANGED" ] && [ "$(shade_now)" != "$SHADE_PRIOR" ]; then
    echo "WARNING: shade setup left as '$(shade_now)', was '$SHADE_PRIOR'"
  fi
  bar_up; sleep 0.8
}

fail() { echo "SMOKE FAIL at $1: $2"; cleanup; exit 1; }
pre()  { echo "SMOKE FAIL at $1: $2"; exit 2; }

trap 'echo "SMOKE INTERRUPTED"; cleanup; exit 130' INT TERM

# ---- 1 preflight (before any adb call, the serial)
case "$APP_QUERY" in ''|*[!A-Za-z0-9\ ._-]*) pre preflight "APP_QUERY '$APP_QUERY' must be non-empty and only letters, digits, space, '.', '-' and '_'";; esac
case "$DEVICE" in emulator-*) ;; *) pre preflight "DEVICE=$DEVICE is not an emulator-* serial (this smoke is emulator-only)";; esac
[ "$(a get-state 2>/dev/null | tr -d '\r')" = device ] || pre preflight "$DEVICE is not a device in state 'device' (adb get-state)"
[ -f "$APK" ] || pre preflight "no APK at $APK: run tools/build.sh"
n=$(a shell ps -A | grep -c '[s]hizuku_server')
if [ "$n" != 1 ]; then
  pre preflight "$n shizuku_server processes (need exactly 1). Start with: adb -s $DEVICE shell <shizuku apk dir>/lib/x86_64/libshizuku.so ; with two, kill the older one first"
fi
svc_now=$(a shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$svc_now" in *"$PKG/$PKG.NavAccessibilityService"*) ;; *)
  case "$svc_now" in ''|null) svc_new="$PKG/$PKG.NavAccessibilityService";; *) svc_new="$svc_now:$PKG/$PKG.NavAccessibilityService";; esac
  pre preflight "our accessibility service is not enabled. Run (keeps the services already on): adb -s $DEVICE shell settings put secure enabled_accessibility_services '$svc_new'";;
esac
a shell pm list packages | tr -d '\r' | grep -qx "package:$APP_PKG" || pre preflight "APP_PKG $APP_PKG is not installed"
W=$(a shell wm size | tail -1 | tr -d '\r' | awk '{print $NF}'); H=${W#*x}; W=${W%x*}
case "$W$H" in ''|*[!0-9]*) pre preflight "cannot read the display size from wm size";; esac
echo "ok preflight (${W}x${H})"

# ---- 2 install
STARTED=1
a logcat -b all -c   # logs and the crash buffer
out=$(tools/device.sh install 2>&1)
echo "$out" | grep -q Success || fail install "adb install did not report Success: $out"
base=$(a shell pm path $PKG | tr -d '\r' | head -1 | sed 's/^package://')
remote=$(MSYS_NO_PATHCONV=1 a shell md5sum "$base" | awk '{print $1}')
local_md5=$(md5sum "$APK" | awk '{print $1}')
[ -n "$remote" ] && [ "$remote" = "$local_md5" ] || fail install "installed APK md5 '$remote' differs from the local APK's '$local_md5'"
echo "ok install"

# ---- 3 strip
strip_up() { logs | grep -q 'gesture strip on'; }
poll 40 strip_up || fail strip "no 'gesture strip on' in the log within 40 s"
logs | grep -q 'BUILD ' || fail strip "no BUILD line in the log"
PID=$(a shell pidof $PKG | tr -d '\r')
[ -n "$PID" ] || fail strip "no process $PKG"
echo "ok strip (pid $PID, $(logs | grep -m1 'BUILD ' | sed 's/.*Launcher: //'))"

# ---- 4 home
# A local install lets the phone's other home app (Nexus Launcher) take the screen while ours restarts (CLAUDE.md pitfall):
# ask for our home explicitly (only when it is not already in front), as the pitfall says, then check the focus.
poll 3 focus_has HomeActivity || a shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -p $PKG >/dev/null 2>&1
poll 10 focus_has HomeActivity || fail home "HomeActivity is not focused: $(focus)"
# Home is settled once its arrival is over (played, dropped, or none on a cold start from an app) and its last warm-up has run
# (the arrival needs home resumed, the warm-up needs its window focus: so only now). Before that the first pulls are missed.
settled() { logs | grep -qE '\[home\] arrival( \(cold start\))? (ended|dropped)|\[home\] cold start: .*no arrival' && logs | grep -q 'warmed up blurs'; }
poll 40 settled || fail home "home did not settle (no arrival ended/dropped and 'warmed up blurs' line within 40 s)"
SHADE_PRIOR=$(shade_now)
[ -n "$SHADE_PRIOR" ] || fail home "no shade setup in the [setup] log line"
bar_up; sleep 0.8; bar_up
poll 5 focus_has HomeActivity || fail home "HomeActivity lost the focus after the bar swipes: $(focus)"
echo "ok home (shade setup: $SHADE_PRIOR)"

# Home is on its first page here: the two bar swipes up above are home's Home gesture (GestureNav.releaseHomePull: "goes to the
# first page"), so the Spotlight pull below always starts from page 1.
# ---- 5 app: Spotlight, query, Enter; then the bar swipe closes it
L0=$(cnt '\[nav\] launch '); H0=$(cnt '\[nav\] home \(')
tools/device.sh swipe $((W/2)) $((H*42/100)) $((W/2)) $((H*71/100)) 250 >/dev/null     # a pull down on page 1 opens Spotlight
kb() { a shell dumpsys input_method | grep -q 'mInputShown=true'; }
poll 8 kb || fail app "Spotlight's keyboard did not come up"
a shell input text "${APP_QUERY// /%s}"
sleep 1
a shell input keyevent KEYCODE_ENTER
poll 10 focus_has "$APP_PKG" || fail app "$APP_PKG did not take the focus after '$APP_QUERY' + Enter: $(focus)"
launched() { [ "$(cnt '\[nav\] launch ')" -gt "$L0" ]; }
poll 5 launched || fail app "no new '[nav] launch' line in the log"
sleep 1
bar_up
homed() { [ "$(cnt '\[nav\] home \(')" -gt "$H0" ]; }
poll 10 homed || fail app "no new '[nav] home (' line after the bar swipe"
poll 10 focus_has HomeActivity || fail app "home did not come back: $(focus)"
echo "ok app ($APP_PKG)"

# ---- 6 panels
open_panel() { # STEP NAME LOGNAME X: a real pull from the top at X; opens when a new '[shade] LOGNAME opening' line (that panel's own) and the focus window are there
  local step=$1 name=$2 logname=$3 x=$4 o0 c0
  o0=$(cnt "\[shade\] $logname opening\$"); c0=$(cnt '\[shade\] closed')
  tools/kswipe.sh "$x" 8 "$x" $((H*60/100)) 14 3 >/dev/null
  opened() { [ "$(cnt "\[shade\] $logname opening\$")" -gt "$o0" ]; }
  poll 4 opened || fail "$step" "$name: no new '[shade] $logname opening' line"
  poll 4 focus_has_any 'LauncherFocus|LauncherStatusBar' || fail "$step" "$name: neither LauncherFocus nor LauncherStatusBar focused: $(focus)"
  sleep 0.8
  tools/device.sh shot "smoke_$step" >/dev/null
  bar_up
  closed() { [ "$(cnt '\[shade\] closed')" -gt "$c0" ]; }
  poll 6 closed || fail "$step" "$name: no new '[shade] closed' line after the bar swipe"
  poll 6 focus_has HomeActivity || fail "$step" "$name: HomeActivity not focused after closing: $(focus)"
  sleep 1
  echo "ok $step"
}
SHADE_CHANGED=1
set_shade ios split-top || fail cc "the shade setup did not become 'ios (split-top)' (is: '$(shade_now)')"
open_panel cc "Control Center" "Control Center" $((W*85/100))
open_panel nc "Notification Center" "Notification Center" $((W*15/100))
set_shade pixel together || fail pixel "the shade setup did not become 'pixel (together)' (is: '$(shade_now)')"
open_panel pixel "Android's shade" "the shade" $((W/2))
restore_shade || fail panels "the shade setup did not return to '$SHADE_PRIOR' (is: '$(shade_now)')"
SHADE_CHANGED=""

# ---- 7 end
[ "$(a shell pidof $PKG | tr -d '\r')" = "$PID" ] || fail end "the app's pid changed from $PID: it restarted or crashed"
crash=$(a logcat -d -b crash | grep -c "$PKG")
[ "$crash" = 0 ] || fail end "$crash lines naming $PKG in the crash buffer"
poll 5 focus_has HomeActivity || fail end "HomeActivity is not in front: $(focus)"
echo "ok end"
echo "SMOKE PASS"
exit 0
