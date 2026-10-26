#!/usr/bin/env bash
# Fidelity capture (MF slice 1, D-037/D-038/D-039): puts the Android 17 emulator in a named state of Android's shade, once
# with OUR shade and once with STOCK's, and saves a 1080x2400 screenshot of each, on a fixed setup, so that two captures of
# one state can be diffed (tools/fidelity/diff.py) and the harness's own noise measured (tools/fidelity/selfdiff.sh).
#
#   tools/fidelity/capture.sh --side ours|stock --state quick|expanded|tile-off|tile-on|all --theme light|dark|both
#       --out DIR [--repeat N] [--font system|off] [--dump] [--dry-run]
#   tools/fidelity/capture.sh --restore         puts the device back from tools/shots/fidelity/.prior/ (after a kill)
#   --state and --theme also take a comma list (quick,expanded). Output: DIR/<side>/<theme>/<state>.png (with --repeat N > 1:
#   DIR/r<K>/<side>/<theme>/<state>.png) and DIR/manifest.json. Exit 0 done; 1 a state not reached or not at rest (the device
#   is still put back); 2 usage or a precondition not met (nothing was touched). EMULATOR ONLY (refuses other serials).
#   --dump (stock side only; with ours it is refused, exit 2): also saves stock's uiautomator tree of each state next to its
#   picture (DIR/stock/<theme>/<state>.xml, the manifest says so): slice 3's mask rules read it. Our service is off on that side
#   (nothing to unbind: a dump with it on unbinds every accessibility service, CLAUDE.md), checked before each dump.
#   [DEVICE=emulator-5554] [ADB=path]. Sourceable: functions only, main runs when executed.
#
# States (the shade is Android's, Pixel layout; light and dark by `cmd uimode night`):
#   quick      stage 1: the quick row over the notifications (ours: TEST_SHADE do=open panel=shade; stock: cmd statusbar
#              expand-notifications)            expanded   stage 2: Quick Settings expanded (ours: + do=qs_expand; stock:
#              expand-settings)     tile-on / tile-off   stage 2 with the Auto-rotate tile on / off (Settings.System
#              accelerometer_rotation 1 / 0, which both sides follow)
# Fixed setup, both sides (all put back at the end): 24 h, auto time and zone off, the clock at 2026-11-02 10:00 in the device's
#   own zone (D-039 said UTC: changing the zone makes Android post "Your time zone changed", which a shell cannot take back; reset
#   before every state: it keeps running, and the notification says "now"), battery unplugged at 100 % (status discharging),
#   brightness 127 (manual), DND off, Auto-rotate on, portrait, one notification (TEST_NOTIFY id 1 "Fidelity fixture", with the
#   app's own "Launcher safety" one: the whole set; every record of another app is snoozed for the run, B-062, below), stock's tiles wifi,bt,cell,dnd,airplane,rotation,flashlight (on both sides: STOCK_TILES) and,
#   on the ours side, ours' list the counterpart of it (OURS_TILES, below). Asserted, never set: 1080x2400
#   at 420 dpi, font scale 1.0, en-US, palette seed D2DCF0 TONAL_SPOT, the emulator is Android 17. Ours: shade pixel/together,
#   theme pixel, the system's colours (sys.color.style = system) and, with --font system (default for ours, D-038), the system font
#   (sys.font.text and sys.font.display = system), both through design/user-<theme>.json (merged with what is there, put back
#   byte for byte). Ours' tile list is the app's own saved choice (shared preferences; MF slice 2): read and set over adb as
#   the virtual file qs_tiles.txt of the app's export and import (`reset`, or `KEY[:w],...`: shade/qs/QsTileList), so it is saved
#   in the prior export and put back from it (a kept list that equals the default stays kept: "reset" and the list are told apart).
# The prior state is saved once, before the first change, to tools/shots/fidelity/.prior/ (settings lists, a byte-exact
#   export of the app's files incl. qs_tiles.txt, the values below) with a `dirty` marker; a new run refuses while it exists
#   (use --restore) and refuses an app too old to export qs_tiles.txt (exit 2).
#
# The emulator's modem swings its signal bars all the time (NR ssRsrp -44 .. -102 dBm in a sawtooth of ~90 s, so 1 to 4 bars; found by
#   the first self-diff: both sides' status bar and quick settings header differed in the signal icon, cells col 9 rows 0 and 2).
#   No console command holds it (`gsm signal-profile 0..4` and `gsm signal R B` tried: it keeps cycling), and ours ignores demo
#   mode, so both sides are captured with the voice and data states `unregistered` (the status bar says "No Service": stable
#   on both sides); their prior values are read from `adb emu gsm status` and put back (`home`).
#
# Probes (MF slice 1 step 1, emulator-5554, Android 17 CP41.260828.004.A7, user build, 2026-10-26; "verified" = seen working):
#  a. Demo mode (settings global sysui_demo_allowed 1 + `am broadcast -a com.android.systemui.demo -e command enter|clock|
#     battery|network|status|exit`): works on stock Android 17, but its clock is read in another zone (4:30 for hhmm 1000) and
#     OUR shade ignores it. NOT USED: the real device state is fixed instead (b), which both sides read. Verified.
#  b. `settings put global auto_time 0` + `cmd alarm set-time MS`: sticks (the clock then runs on in real time: reset before each
#     state); auto_time 1 gives the host's real time back at once. `cmd alarm set-timezone` works too but Android then posts a
#     system notification "Your time zone changed" that stays (and its age changes with the faked clock): the zone is left alone. Time 24 h:
#     Settings.System time_12_24 24 (prior null: deleted). Verified. `dumpsys battery unplug` + `set level 100` shows stock's
#     battery without a bolt; ours drew a bolt until `set status 3` was added (status is read there). Verified for stock.
#  c. Stock reaches stage 1 and stage 2 with no touch: `cmd statusbar expand-notifications` / `expand-settings` (focus
#     NotificationShade), `collapse` (focus back on HomeActivity). Verified. Two identical screencaps 400 ms apart = at rest.
#  d. `cmd statusbar set-tiles 'LIST'` sets stock's tiles (specs with parentheses need the quotes) and puts the old list back;
#     `settings put secure sysui_qs_tiles` is ignored. The Auto-rotate tile follows system accelerometer_rotation on stock and on
#     ours (read when the shade opens). Verified both sides. Flashlight is unavailable on the emulator, DND would hide the fixture.
#  e. Ours: `TEST_SHADE --es do open --es panel shade` (focus LauncherFocus), `qs_expand`, `close`; the system colours and the font
#     are tokens: files/design/user-<theme>.json ({"format":1,"name":"user","tokens":{"sys.font.text":{"choice":"system"},...}}),
#     `DESIGN_RELOAD --es files import` logs "[design] reloaded: N edited" (the file for the ACTIVE theme: user.json belongs to
#     iOS 27 only). With the font tokens set, all of the Pixel shade's text draws the system font (its styles name the role
#     "text" or "display", not a family: B-060, D-042); only text whose weight comes from a family name (600) is masked. Verified.
#  f. `TEST_NOTIFY` posts also while our accessibility service is off (stock's shade shows it; id N is notification 1000+N,
#     default N 0); the shell's argument list must be ONE string for adb shell, or words split (title "Fidelity", text "One"). A
#     notification's "now" becomes "in 3m" if the clock is set back: post after the clock is set. Verified.
#  g. `DESIGN_RELOAD --es files export` then `adb pull` then `push` to dev-in and `import` is byte-exact for setup.json and
#     design/*.json; `--es files remove --es path P` deletes a file the harness added. Verified. (Ours' tile list and every
#     other shared preference are not in the export.)
#  h. `adb emu gsm voice|data unregistered|home` and `gsm status` (it prints "gsm voice state: home"): the modem's registration, read
#     and put back. With it unregistered the bars no longer swing. Verified (both sides, three runs: the noise is gone).
#
# The recently-alerted bell (found by the second live self-diff: four stock "quick" pairs differed in one bell icon beside the
#   fixture's "now", x 474-505, y 593-624, present in some captures and not in others). SystemUI's RowAlertTimeCoordinator
#   (SystemUIGoogle, read with dexdump) runs at every render pass of a row: alerted recently = currentTimeMillis - the ranking's
#   lastAudiblyAlertedMillis < 30000, and then it posts a callback that clears the bell after (30000 - that) ms of UPTIME. Our
#   fixture (TEST_NOTIFY's channel is IMPORTANCE_HIGH) alerts at its posting, and the harness' clock was reset to 10:00:10 before
#   every state, so any render pass (a theme change, a rebind, the shade opening) found the alert 8 s old and put the bell back
#   for the next ~22 s: whether a capture had it depended on how long ago the last pass was. Now the fixture is posted at
#   10:00:00 and the clock is held at 10:00:45 for everything after (45 s old: any pass says "not recent"), and post_fixture
#   waits 33 s so the bell the posting itself drew is gone by its own timer. Both sides do the same (ours has no bell yet, which
#   is for a later slice to fill: the golden is the state after 30 s). The fixture stays a high-importance, alerting one (the
#   alerting section, not "Silent"), the look we compare against later. The minute stays 10:00 and the age text "now" (< 60 s)
#   as long as a state is taken within ~14 s of its clock being set: checked after each capture. The first open after a boot can be
#   slower than that (the first live self-diff after a restart: 10:01:01 on two ours states), so the clock is set again once the panel is
#   reached, right before the rest check, and a state whose guard still fails is retried once from its first clock set.
#
# Probes of MF slice 2 (emulator-5554, Android 17 CP41.260828.004.A7, 2026-10-26):
#  i. Notification records on the stock side (service off): ours' two only, the fixture (id 1001, channel test, importance 3: the
#     alerting section) and "Launcher safety" (id 1, foreground service, channel safety, importance 2: the Silent section, one
#     collapsed card with an Expand button), and no other package's: stock shows a Silent tail under the fixture (and, below it,
#     the History / Clear all / Settings row); ours (checked on its quick PNG) lists the same two, the fixture and a
#     Silent "Launcher safety" card with the same History / Clear all / Settings row, and no overlay notice: so the Silent tail is not
#     stock-only on this emulator (D-041 (3) expected it to be; slice 3 decides its mask rule from the dump and the pictures).
#  j. Stock after `set-tiles 'wifi,bt,cell,dnd,airplane,rotation,flashlight'` (stage 2, uiautomator bounds): Wi-Fi, Bluetooth, Mobile
#     data and Modes (dnd) are LARGE (two columns, 2 a row: x 63-509 and 572-1017), Airplane mode, Auto-rotate and Flashlight small
#     (3 a row, 233 wide: the Auto-rotate tile [297,1030][530,1219]). So ours' counterpart is OURS_TILES, with those four wide.
#  k. `uiautomator dump` with our service off: works (a 45-50 KB tree of SystemUI's shade with text, content-desc and bounds of every
#     node), the settings (system, secure, global) are the same before and after, the strip stays down, nothing is left in /sdcard.
#
# The notification set present once the fixture is posted (NOTIFS: package and id of every record, sorted) is recorded in the
#   manifest's setup ("notifications") and kept by golden.sh in the golden's manifest: a notification that arrives later (Google
#   Play's "Play Protect is turned on", ~25 min after a boot) adds a card to the Silent section, so a diff against a golden taken
#   without it differs for that reason. The ours side warns when its set differs from the golden's of the build (GOLDEN_ROOT,
#   default tools/fidelity/golden); golden.sh verify --against fails on a different set.
#
# Other apps' notifications are snoozed for the run (B-062, D-043; probes l-n below). The fixed set is {our safety notification, the
#   fixture}: right after the clock is set (apply_setup), every record not ours is snoozed for 1 h (`cmd notification snooze --for
#   3600000 KEY`, snooze_foreign; Android's overlay notice is left out, as in NOTIFS), and the sweep runs again at the start of every
#   state and before the set is recorded; a record that arrives during a state still fails that state (the set check at its end).
#   Each key goes to PRIOR/snoozed.txt BEFORE it is snoozed (KEY<TAB>, then KEY<TAB>UNTIL from dumpsys's pending list), so a kill
#   leaves them known. They come back in teardown and in --restore by MOVING THE FAKED CLOCK past the latest `until` (restore_snoozed:
#   auto_time 0, set-time to max(until) + 5 s, poll <= 8 s that none is pending, one more nudge of 5 s, capped at CLOCK_BASE + 3 h:
#   past that a WARNING, no jump, dirty kept), BEFORE the real clock is restored; `cmd notification unsnooze` is NEVER called (probe l:
#   refused for the shell, and it drops the record). A record its owner cancelled while it was snoozed is gone: a NOTE. A key whose
#   until could not be read (dumpsys's pending list in another form, or absent: it reads as "none pending") is a WARNING at the snooze,
#   and at the end unless it is posted again (dirty kept: without the list a lost record cannot be told from a cancelled one). A pending
#   snooze that was not made by this tool makes a run refuse (exit 2): it would come back in the middle of the clock changes.
#
# Probes of B-062 (emulator-5554, Android 17 CP41.260828.004.A7, 2026-10-27; the stand-in was TEST_NOTIFY --ei id 9, key
#   0|dev.launcher.app|1009|null|10232: a key is userId|pkg|id|tag|uid, and a tag may hold spaces, e.g. the overlay notice's
#   0|android|0|com.android.server.wm.AlertWindowNotification - dev.launcher.app|1000: quote a key, read keys line by line):
#  l. (a) `cmd notification snooze --for MS 'KEY'` works from the shell ("snoozing <KEY> until time: ..."). (b) The record leaves
#     `cmd notification list` and dumpsys's "Notification List" (no NotificationRecord( line remains anywhere else: notif_set needs no
#     section restriction); dumpsys shows `  Snoozed notifications:` / `    key: KEY` and ` Pending snoozed notifications` /
#     `    key: KEY until: EPOCH_MS` (`until` is wall-clock time). (c) Neither our shade nor stock's lists it (screencaps looked at).
#     (d) Backward clock jumps (-6 days), steps of +-45 s and the return to real time leave it snoozed; a FORWARD jump past `until`
#     (+6 days with a 6 h snooze, +7 h) reposts it at once: so snooze after the clock is set, never before. (e) `cmd notification
#     unsnooze 'KEY'` FAILS: "Permission Denial: getIntentSender() from pid=.., uid=2000 is not allowed to send as package android"
#     (SnoozeHelper.repost -> createPendingIntent), and the record is DROPPED (gone from the list and the snoozed list, a second
#     unsnooze says "no snoozed otification"): never call it. (f) A cancel by the owner while snoozed leaves the key in the snoozed
#     list; at its expiry nothing is reposted (no zombie); an update of the same id stays hidden. (g) A silent ongoing record is
#     snoozable too (the overlay notice, ONGOING_EVENT; our own FGS "Launcher safety", probe only) and both came back, new records
#     with the same key, after a forward jump.
#  m. A record reposted by the expiry alerts again: its mInterruptionTimeMs is set ~0.35 s after the repost (as at its first post), the
#     vibrate key points at it, and stock's shade shows its "recently alerted" bell. It happens at teardown, the only place it is
#     reposted, and nothing is captured then.
#  n. A forward `cmd alarm set-time` past `until` reposts within ~1 s with auto_time 0 (962 ms) and with auto_time 1 (1191 ms); with
#     auto_time 1 the clock then keeps the faked time until auto_time is switched off and on again (so restore_snoozed sets
#     auto_time 0 first, and restore_settings gives the real time back). `until:` lines read as `^ *key: (.*) until: ([0-9]+)$`,
#     greedy so a spaced key is whole. A key with a quote in its tag (`cmd notification post -t T "it's a tag" TEXT`: key
#     0|com.android.shell|2020|it's a tag|2000) snoozes when written '...it'\''s a tag...', but the harness refuses it (fails
#     setup naming the key: nothing is snoozed) rather than trust a quote through two shells.
#
# Timings are sleeps and bounded polls only. Never `am force-stop` (CLAUDE.md); the accessibility service is switched through
# the setting's list (others kept), B-018's missed strip is retried by switching it off and on.

FID_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEVICE="${DEVICE:-emulator-5554}"
ADB="${ADB:-${LOCALAPPDATA:-}/Android/Sdk/platform-tools/adb.exe}"; [ -x "$ADB" ] || ADB=adb
PKG=dev.launcher.app
SVC="$PKG/$PKG.NavAccessibilityService"
APPFILES=/sdcard/Android/data/$PKG/files
PRIOR_DIR="${FID_PRIOR:-$FID_ROOT/tools/shots/fidelity/.prior}"
CLOCK_MS=1793613600000          # 2026-11-02 10:00:00 UTC
CLOCK_SHOW_S=45                 # the clock while a state is captured: 10:00:45 (see "The recently-alerted bell" in the header)
STOCK_TILES="wifi,bt,cell,dnd,airplane,rotation,flashlight"
OURS_TILES="WIFI:w,BLUETOOTH:w,CELLULAR:w,FOCUS:w,AIRPLANE,ROTATION_LOCK,FLASHLIGHT"   # stock's, as ours names them; the wide marks are probe j's
FIXTURE_ID=1
SNOOZE_MS=3600000               # other apps' notifications are snoozed for 1 h of the faked clock (B-062: the clock stays within minutes of CLOCK_BASE)
SNOOZE_CAP_MS=10800000          # restore_snoozed never moves the clock past CLOCK_BASE + 3 h
ALL_STATES="quick expanded tile-off tile-on"
# settings the run changes (ns key); their prior values are kept in prior.tsv and put back
MANAGED="global:auto_time global:auto_time_zone global:zen_mode system:time_12_24 system:screen_brightness system:screen_brightness_mode system:accelerometer_rotation"
# keys that differ after any run for reasons of their own (a boot, the clock): left out of the before/after comparison
VOLATILE='zen_mode_config_etag|boot_count|network_watchlist_last_report_time|_last_|wifi_scan|time_zone_|ntp|^bluetooth_address|last_update'

# ---------------------------------------------------------------- small helpers
say() { echo "[fidelity] $*"; }
a()   { "$ADB" -s "$DEVICE" "$@" < /dev/null; }       # (adb would eat the stdin of a loop that calls it)
ap()  { MSYS_NO_PATHCONV=1 "$ADB" -s "$DEVICE" "$@" < /dev/null; }          # a call with a device path (Git Bash would convert it)
ash() { a shell "$@" | tr -d '\r'; }
emu() { "$ADB" -s "$DEVICE" emu "$@" < /dev/null | tr -d '\r'; }       # the emulator's console (adb emu: the host side, no shell)
gsm_state() { emu gsm status | sed -n "s/^gsm $1 state: *//p" | head -1; }   # voice|data -> home, unregistered, ...
setting_get() { ash settings get "$1" "$2"; }
winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }
pyx() { local p; p="$(command -v python3 || command -v python)"; "$p" "$@"; }
# poll SECONDS CMD...: true as soon as CMD succeeds (4 tries a second), false after the time
# POLL_SCALE (default 1) multiplies every poll's bound (at least one try): the test against a fake adb, whose sleep takes no
# time, sets it low so that a poll that can never succeed does not cost 80 fake-adb calls per 20 s.
POLL_PERMILLE=1000
if [[ "${POLL_SCALE:-1}" =~ ^([0-9]+)(\.([0-9]{1,3}))?$ ]]; then
  _frac="${BASH_REMATCH[3]:-0}000"; POLL_PERMILLE=$(( 10#${BASH_REMATCH[1]} * 1000 + 10#${_frac:0:3} )); unset _frac
fi
poll() { local n=$(( $1 * 4 * POLL_PERMILLE / 1000 )) i; shift; [ "$n" -ge 1 ] || n=1; for ((i = 0; i < n; i++)); do "$@" && return 0; sleep 0.25; done; return 1; }
focus() { ash dumpsys window | grep mCurrentFocus; }
focus_has() { focus | grep -qE "$1"; }
has_win() { a shell dumpsys window windows | tr -d '\r' | grep -q "u0 $1}"; }
a11y_list() { setting_get secure enabled_accessibility_services; }
has_svc() { echo "$1" | tr ':' '\n' | grep -qxE 'dev\.launcher\.app/(dev\.launcher\.app)?\.NavAccessibilityService'; }
png_size() { # FILE -> "W H" from the PNG header, or nothing
  local f="$1" n
  [ -s "$f" ] || return 1
  [ "$(head -c 4 "$f" | od -An -c | tr -d ' ')" = '211PNG' ] || return 1
  n=($(od -An -tu1 -j16 -N8 "$f"))
  echo "$(( n[0]*16777216 + n[1]*65536 + n[2]*256 + n[3] )) $(( n[4]*16777216 + n[5]*65536 + n[6]*256 + n[7] ))"
}
json_str() { local s="${1//\\/\\\\}"; s="${s//\"/\\\"}"; printf '%s' "$s"; }

# ---------------------------------------------------------------- arguments
usage_err() { echo "capture.sh: $1" >&2; echo "usage: capture.sh --side ours|stock --state quick|expanded|tile-off|tile-on|all --theme light|dark|both --out DIR [--repeat N] [--font system|off] [--dump] [--dry-run] | --restore" >&2; exit 2; }
parse_args() {
  SIDE=""; STATES=""; THEMES=""; OUT=""; REPEAT=1; FONT=""; DRY=""; RESTORE=""; DUMP=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --side) [ $# -ge 2 ] || usage_err "--side needs a value"; SIDE="$2"; shift 2;;
      --state) [ $# -ge 2 ] || usage_err "--state needs a value"; STATES="$2"; shift 2;;
      --theme) [ $# -ge 2 ] || usage_err "--theme needs a value"; THEMES="$2"; shift 2;;
      --out) [ $# -ge 2 ] || usage_err "--out needs a value"; OUT="$2"; shift 2;;
      --repeat) [ $# -ge 2 ] || usage_err "--repeat needs a value"; REPEAT="$2"; shift 2;;
      --font) [ $# -ge 2 ] || usage_err "--font needs a value"; FONT="$2"; shift 2;;
      --dump) DUMP=1; shift;;
      --dry-run) DRY=1; shift;;
      --restore) RESTORE=1; shift;;
      -h|--help) usage_err "help";;
      *) usage_err "unknown argument '$1'";;
    esac
  done
  [ -n "$RESTORE" ] && return 0
  case "$SIDE" in ours|stock) ;; *) usage_err "--side must be ours or stock";; esac
  [ -n "$OUT" ] || usage_err "--out DIR is required"
  [ -z "$DUMP" ] || [ "$SIDE" = stock ] || usage_err "--dump is for --side stock only (with our service on, a uiautomator dump would unbind it)"
  case "$REPEAT" in ''|*[!0-9]*|0) usage_err "--repeat must be a positive number";; esac
  [ "$REPEAT" -le 20 ] || usage_err "--repeat at most 20"
  case "$THEMES" in both|"") THEMES="light dark";; *) THEMES="${THEMES//,/ }";; esac
  for t in $THEMES; do case "$t" in light|dark) ;; *) usage_err "--theme: '$t' is not light, dark or both";; esac; done
  case "$STATES" in all|"") STATES="$ALL_STATES";; *) STATES="${STATES//,/ }";; esac
  for s in $STATES; do case " $ALL_STATES " in *" $s "*) ;; *) usage_err "--state: '$s' is not one of $ALL_STATES, all";; esac; done
  [ -n "$FONT" ] || { [ "$SIDE" = ours ] && FONT=system || FONT=off; }
  case "$FONT" in system|off) ;; *) usage_err "--font must be system or off";; esac
}

# ---------------------------------------------------------------- preconditions: reads only, exit 2, nothing touched
refuse() { echo "capture.sh REFUSED: $1. Nothing was touched." >&2; rm -rf "${TMPD:-}"; exit 2; }
preconditions() {
  case "$DEVICE" in emulator-*) ;; *) refuse "DEVICE=$DEVICE is not an emulator-* serial (the harness is emulator-only)";; esac
  [ "$(a get-state 2>/dev/null | tr -d '\r')" = device ] || refuse "$DEVICE is not a device in state 'device'"
  [ "$(ash getprop ro.build.version.sdk)" = 37 ] || refuse "not Android 17 (sdk $(ash getprop ro.build.version.sdk)): the goldens are per Android build"
  local size dens fs loc pal n
  size=$(ash wm size | grep -v Override | tail -1 | awk '{print $NF}'); [ "$size" = 1080x2400 ] || refuse "display size is '$size', the harness needs 1080x2400"
  ash wm size | grep -q Override && refuse "a display size override is set (wm size reset first)"
  dens=$(ash wm density | grep -v Override | tail -1 | awk '{print $NF}'); [ "$dens" = 420 ] || refuse "display density is '$dens', the harness needs 420"
  fs=$(setting_get system font_scale); case "$fs" in 1.0|1|null) ;; *) refuse "font scale is '$fs', the harness needs 1.0";; esac
  loc="$(ash getprop persist.sys.locale)$(ash getprop ro.product.locale)"; case "$loc" in en-US|en_US|en-USen-US) ;; *) refuse "locale is '$loc', the harness needs en-US";; esac
  pal=$(setting_get secure theme_customization_overlay_packages)
  echo "$pal" | grep -q 'D2DCF0' && echo "$pal" | grep -q 'TONAL_SPOT' || refuse "the palette is not seed D2DCF0 in TONAL_SPOT (Android's Wallpaper & style)"
  ash dumpsys window displays | grep -qE 'mRotation=0|ROTATION_0' || refuse "the display is not upright (portrait)"
  n=$(ash ps -A | grep -c '[s]hizuku_server'); [ "$n" = 1 ] || refuse "$n shizuku_server processes (need exactly 1; start it once, never a second)"
  ash pm list packages | grep -qx "package:$PKG" || refuse "$PKG is not installed"
  focus_has HomeActivity || refuse "HomeActivity is not in front ($(focus))"
  [ -f "$PRIOR_DIR/dirty" ] && refuse "an earlier run left the device changed ($PRIOR_DIR/dirty): run capture.sh --restore first"
  # a pending snooze that this tool did not make comes back when the clock moves (B-062): the run would find a foreign record in the middle of it
  local pk; pk="$(pending_until | head -1)"
  [ -z "$pk" ] || refuse "a notification is snoozed already (pending snooze: ${pk%%$'\t'*} until ${pk#*$'\t'}, the device clock's ms): the system would repost it in the middle of the run when the clock moves; let the snooze run out or clear that notification (its app, the shade's Snoozed list; never cmd notification unsnooze: it drops the record), then run again"
  # no other notification of ours' test channel than the fixture (id 1000 + FIXTURE_ID)
  if ash dumpsys notification --noredact | grep 'NotificationRecord(' | grep "pkg=$PKG" | grep 'channel=test' | grep -v 'g:Aggregate' | grep -vqE "id=$((1000 + FIXTURE_ID)) "; then
    refuse "another test notification of ours is posted (TEST_NOTIFY --ez cancel true --ei id N removes it)"
  fi
  if [ -e "$OUT/manifest.json" ]; then refuse "$OUT already holds a capture (manifest.json): choose another --out"; fi
}

# ---------------------------------------------------------------- the app's files over the inbox (export, import, remove)
export_files() { # DEST: a fresh export, pulled
  local dest="$1" i
  ap shell rm -f "$APPFILES/dev-out/.done" >/dev/null 2>&1
  a shell am broadcast -a dev.launcher.app.DESIGN_RELOAD -p $PKG --es files export >/dev/null 2>&1
  for i in $(seq 1 40); do
    [[ "$(ap exec-out "cat $APPFILES/dev-out/.done 2>/dev/null" | tr -d '\r\n ')" =~ ^[0-9]+$ ]] && break
    sleep 0.25
  done
  [ "$i" -lt 40 ] || return 1
  rm -rf "$dest"; mkdir -p "$(dirname "$dest")"
  ap pull "$APPFILES/dev-out" "$(winpath "$dest")" >/dev/null 2>&1 || return 1
  [ -f "$dest/setup.json" ]
}
import_file() { # LOCAL REL: pushes LOCAL to dev-in/REL and imports it
  local f="$1" rel="$2"
  ap shell mkdir -p "$APPFILES/dev-in/$(dirname "$rel")" >/dev/null 2>&1
  ap push "$(winpath "$f")" "$APPFILES/dev-in/$rel" >/dev/null 2>&1 || return 1
  a shell am broadcast -a dev.launcher.app.DESIGN_RELOAD -p $PKG --es files import >/dev/null 2>&1
}
remove_file() { a shell am broadcast -a dev.launcher.app.DESIGN_RELOAD -p $PKG --es files remove --es path "$1" >/dev/null 2>&1; }
setup_of() { # EXPORT-DIR ELEMENT -> "layout placement"
  pyx -c "
import sys, json
try:
    s = json.load(open(sys.argv[1] + '/setup.json'))['elements'][sys.argv[2]]
    print(s['layout'], s['placement'])
except Exception:
    pass" "$1" "$2" | tr -d '\r'
}
theme_of() { local t; t=$(tr -d '\r\n ' < "$1/design/theme.txt" 2>/dev/null); echo "${t:-ios27}"; }
merge_user() { # IN-OR-"" OUT KEY=VALUE...: user-<theme>.json with each KEY's {"choice": VALUE} set (what was there kept)
  local in="$1" out="$2"; shift 2
  pyx -c "
import sys, json
src, dst = sys.argv[1], sys.argv[2]
try:
    d = json.load(open(src, encoding='utf-8')) if src else {}
except Exception:
    d = {}
d.setdefault('format', 1); d.setdefault('name', 'user'); d.setdefault('tokens', {})
for kv in sys.argv[3:]:
    k, v = kv.split('=', 1)
    d['tokens'][k] = {'choice': v}
json.dump(d, open(dst, 'w', encoding='utf-8'))" "$in" "$out" "$@"
}

# ---------------------------------------------------------------- the prior state
save_prior() {
  local ns kv key f
  rm -rf "$PRIOR_DIR"; mkdir -p "$PRIOR_DIR"
  for ns in system secure global; do ash settings list $ns | sort > "$PRIOR_DIR/settings-$ns.txt"; done
  : > "$PRIOR_DIR/prior.tsv"
  for kv in $MANAGED secure:enabled_accessibility_services; do
    ns=${kv%%:*}; key=${kv#*:}
    printf '%s\t%s\t%s\n' "$ns" "$key" "$(setting_get "$ns" "$key")" >> "$PRIOR_DIR/prior.tsv"
  done
  ash getprop persist.sys.timezone > "$PRIOR_DIR/timezone.txt"
  ash cmd uimode night | sed -E 's/^Night mode: *//' > "$PRIOR_DIR/night.txt"
  setting_get secure sysui_qs_tiles > "$PRIOR_DIR/tiles.txt"
  notif_keys > "$PRIOR_DIR/notifs-before.txt"; : > "$PRIOR_DIR/snoozed.txt"   # (what the list holds now, and the keys this run snoozes)
  gsm_state voice > "$PRIOR_DIR/gsm-voice.txt"; gsm_state data > "$PRIOR_DIR/gsm-data.txt"
  export_files "$PRIOR_DIR/files" || { rm -rf "$PRIOR_DIR"; return 1; }
  [ -s "$PRIOR_DIR/files/qs_tiles.txt" ] || { rm -rf "$PRIOR_DIR"; return 3; }   # an app that cannot say (or set) ours' tile list
  echo "$DEVICE $(date +%s)" > "$PRIOR_DIR/dirty"
}
restore_settings() { # puts the managed settings back from prior.tsv
  local ns key val
  while IFS=$'\t' read -r ns key val; do
    [ -n "$key" ] || continue
    val=$(printf '%s' "$val" | tr -d '\r')
    [ "$key" = enabled_accessibility_services ] && continue   # last, with the side switch
    if [ "$val" = null ]; then a shell settings delete "$ns" "$key" >/dev/null 2>&1
    else a shell "settings put $ns $key '$val'" >/dev/null 2>&1; fi
  done < "$PRIOR_DIR/prior.tsv"
}
prior_value() { awk -F'\t' -v k="$1" '$2 == k { print $3 }' "$PRIOR_DIR/prior.tsv" | tr -d '\r'; }

# ---------------------------------------------------------------- the accessibility service (the side switch)
strip_up() { has_win LauncherGestureStrip; }
strip_down() { ! has_win LauncherGestureStrip; }
set_a11y() { a shell "settings put secure enabled_accessibility_services '$1'" >/dev/null 2>&1; }
clear_a11y() { a shell settings delete secure enabled_accessibility_services >/dev/null 2>&1; }
with_ours() { # LIST -> LIST with ours in it
  if has_svc "$1"; then echo "$1"; else case "$1" in ''|null) echo "$SVC";; *) echo "$1:$SVC";; esac; fi
}
without_ours() { # LIST -> LIST without ours ('' when nothing is left)
  echo "$1" | tr ':' '\n' | grep -vxE 'dev\.launcher\.app/(dev\.launcher\.app)?\.NavAccessibilityService|null|' | paste -sd: -
}
ours_on() { # B-018: the strip sometimes never comes after the service is bound: switch off and on again, twice
  local cur try new
  cur=$(a11y_list); new=$(with_ours "$cur")
  [ "$new" = "$cur" ] && strip_up && return 0
  for try in 1 2 3; do
    set_a11y "$new"
    poll 20 strip_up && return 0
    say "gesture strip not up after 20 s (B-018), switching the service off and on (try $try)"
    cur=$(without_ours "$new"); if [ -n "$cur" ]; then set_a11y "$cur"; else clear_a11y; fi
    poll 6 strip_down; sleep 1
  done
  return 1
}
ours_off() {
  local cur new; cur=$(a11y_list)
  has_svc "$cur" || { poll 4 strip_down; return 0; }
  new=$(without_ours "$cur"); if [ -n "$new" ]; then set_a11y "$new"; else clear_a11y; fi
  poll 10 strip_down
}

# ---------------------------------------------------------------- states
tile_value() { case "$1" in tile-off) echo 0;; *) echo 1;; esac; }
to_home() { # closes whatever panel is open and waits for home
  case "$SIDE" in
    ours) a shell am broadcast -a dev.launcher.app.TEST_SHADE -p $PKG --es do close >/dev/null 2>&1;;
    stock) a shell cmd statusbar collapse >/dev/null 2>&1;;
  esac
  poll 8 focus_has HomeActivity
}
clock_base() { # CLOCK_BASE: the instant at which the device's own zone reads 2026-11-02 10:00 (its zone is not changed: Android posts "Your time zone changed", which a shell cannot take back)
  local z; z=$(ash date +%z); [[ "$z" =~ ^([+-])([0-9][0-9])([0-9][0-9])$ ]] || return 1
  CLOCK_BASE=$(( CLOCK_MS - ${BASH_REMATCH[1]}$(( 10#${BASH_REMATCH[2]} * 3600 + 10#${BASH_REMATCH[3]} * 60 )) * 1000 ))
}
set_clock() { a shell cmd alarm set-time $((CLOCK_BASE + CLOCK_SHOW_S * 1000)) >/dev/null 2>&1; }
reach() { # STATE: opens the panel for the state; true when it is up
  local panel_focus
  case "$SIDE" in
    ours)
      a shell am broadcast -a dev.launcher.app.TEST_SHADE -p $PKG --es do open --es panel shade >/dev/null 2>&1
      panel_focus='LauncherFocus|LauncherStatusBar'
      poll 10 focus_has "$panel_focus" || return 1
      sleep 0.8
      if [ "$1" != quick ]; then
        a shell am broadcast -a dev.launcher.app.TEST_SHADE -p $PKG --es do qs_expand >/dev/null 2>&1
      fi;;
    stock)
      panel_focus=NotificationShade
      if [ "$1" = quick ]; then a shell cmd statusbar expand-notifications >/dev/null 2>&1
      else a shell cmd statusbar expand-settings >/dev/null 2>&1; fi
      poll 10 focus_has "$panel_focus" || return 1;;
  esac
  sleep 1.2
  return 0
}
dump_ui() { # FILE: stock's uiautomator tree (--dump), pulled and removed; only while our service is off (it would unbind it)
  local f="$1" dev=/sdcard/fid_dump.xml
  strip_down && ! has_svc "$(a11y_list)" || return 1
  ap shell uiautomator dump $dev >/dev/null 2>&1
  ap pull $dev "$(winpath "$f")" >/dev/null 2>&1
  ap shell rm -f $dev >/dev/null 2>&1
  [ -s "$f" ] && grep -q '<hierarchy' "$f"
}
cap_png() { a exec-out screencap -p > "$1" 2>/dev/null; }
wait_rest() { # OUTFILE: two identical screencaps 400 ms apart (at most 25 tries); the second is kept
  local prev="$TMPD/rest_a.png" cur="$TMPD/rest_b.png" i
  cap_png "$prev"
  for ((i = 0; i < 25; i++)); do
    sleep 0.4; cap_png "$cur"
    if cmp -s "$prev" "$cur" && png_size "$cur" >/dev/null; then cp "$cur" "$1"; return 0; fi
    mv "$cur" "$prev"
  done
  return 1
}

capture_state() { # REPEAT-INDEX THEME STATE: a state whose clock guard failed (the first open after a boot can take a minute) is taken once more from the clock set
  local rc
  try_state "$@"; rc=$?
  if [ "$rc" = 3 ]; then say "retrying $2/$3 r$1 once from the clock set (the first attempt was too slow for 10:00)"; try_state "$@"; rc=$?; fi
  [ "$rc" = 0 ] && return 0 || return 1
}
try_state() { # REPEAT-INDEX THEME STATE: 0 taken, 1 failed, 3 the clock left 10:00 while it was taken (to_home done, the caller may retry)
  local r="$1" theme="$2" state="$3" dir rel file wh
  [ "$REPEAT" -gt 1 ] && rel="r$r/$SIDE/$theme" || rel="$SIDE/$theme"
  dir="$OUT/$rel"; file="$dir/$state.png"; mkdir -p "$dir"
  a shell settings put system accelerometer_rotation "$(tile_value "$state")" >/dev/null 2>&1
  to_home || { say "FAIL $theme/$state r$r: home did not come back ($(focus))"; return 1; }
  snooze_foreign || { say "FAIL $theme/$state r$r: another app's notification could not be snoozed"; return 1; }   # the sweep: whatever arrived since the last state
  set_clock; sleep 1.2
  reach "$state" || { say "FAIL $theme/$state r$r: the panel did not open (focus: $(focus))"; to_home; return 1; }
  set_clock   # again, now that the slow part (a cold first open, the tile list) is behind: 10:00:45 at the start of the rest check (the alert stays > 30 s old, the minute 10:00)
  if ! wait_rest "$file"; then say "FAIL $theme/$state r$r: NOT AT REST (two screencaps 400 ms apart never matched in 25 tries)"; to_home; return 1; fi
  wh=$(png_size "$file"); if [ "$wh" != "1080 2400" ]; then say "FAIL $theme/$state r$r: the picture is '$wh', not 1080 2400"; to_home; return 1; fi
  if [ -n "$DUMP" ] && ! dump_ui "$dir/$state.xml"; then say "FAIL $theme/$state r$r: the uiautomator tree could not be saved"; to_home; return 1; fi
  [ "$(notif_set)" = "$NOTIFS" ] || { say "FAIL $theme/$state r$r: a notification arrived or left while the run was going (was: $NOTIFS now: $(notif_set)): the picture is of another state"; to_home; return 1; }
  [ "$(ash date +%H:%M)" = 10:00 ] || { say "FAIL $theme/$state r$r: the clock left 10:00 while the state was taken ($(ash date))"; to_home; return 3; }
  to_home || { say "FAIL $theme/$state r$r: the panel did not close ($(focus))"; return 1; }
  MANIFEST_FILES+=("$rel/$state.png|$r|$theme|$state|$(md5sum "$file" | cut -d' ' -f1)|$([ -n "$DUMP" ] && echo "$rel/$state.xml")")
  say "ok $rel/$state.png"
  return 0
}

write_manifest() {
  local f="$OUT/manifest.json" first=1 e
  {
    printf '{\n "tool": "tools/fidelity/capture.sh",\n "date": "%s",\n "device": "%s",\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$DEVICE"
    printf ' "android": "%s",\n "uptime_s": "%s",\n' "$(json_str "$(ash getprop ro.build.fingerprint)")" "$(ash cat /proc/uptime | cut -d' ' -f1)"
    printf ' "side": "%s", "font": "%s", "repeat": %s,\n' "$SIDE" "$FONT" "$REPEAT"
    printf ' "setup": {"timezone": "unchanged (the device zone)", "clock": "2026-11-02 10:00:45 local (fixture posted at 10:00:00)", "battery": "unplugged 100", "brightness": 127, "dnd": "off", "tiles_stock": "%s", "tiles_ours": "%s", "fixture": "TEST_NOTIFY id %s", "ours": "shade pixel/together, theme pixel, system colours", "modem": "voice and data unregistered", "notifications": "%s"},\n' "$STOCK_TILES" "$OURS_TILES" "$FIXTURE_ID" "$(json_str "$NOTIFS")"
    printf ' "files": [\n'
    for e in "${MANIFEST_FILES[@]}"; do
      IFS='|' read -r p r t s m d <<< "$e"
      [ -n "$first" ] || printf ',\n'; first=""
      printf '  {"path": "%s", "repeat": %s, "theme": "%s", "state": "%s", "size": [1080, 2400], "md5": "%s"%s}' "$p" "$r" "$t" "$s" "$m" "${d:+, \"dump\": \"$d\"}"
    done
    printf '\n ]\n}\n'
  } > "$f"
}

# ---------------------------------------------------------------- setup and teardown
NOTIFS=""; CHANGED=""; TD_DONE=""; ACTIVE_THEME=""; OVERRIDE_REL=""; OURS_TILES_CHANGED=""
apply_setup() {
  local kv ns key
  CHANGED=1
  a shell settings put global auto_time 0 >/dev/null 2>&1
  a shell settings put global auto_time_zone 0 >/dev/null 2>&1
  a shell settings put system time_12_24 24 >/dev/null 2>&1
  a shell settings put system screen_brightness_mode 0 >/dev/null 2>&1
  a shell settings put system screen_brightness 127 >/dev/null 2>&1
  a shell settings put global zen_mode 0 >/dev/null 2>&1
  a shell settings put system accelerometer_rotation 1 >/dev/null 2>&1
  a shell dumpsys battery unplug >/dev/null 2>&1
  a shell dumpsys battery set level 100 >/dev/null 2>&1
  a shell dumpsys battery set status 3 >/dev/null 2>&1
  emu gsm voice unregistered >/dev/null 2>&1; emu gsm data unregistered >/dev/null 2>&1   # the bars stop swinging (header)
  [ "$(gsm_state voice)" = unregistered ] || { say "FAIL setup: the modem did not take 'unregistered' (adb emu gsm status says '$(gsm_state voice)')"; return 1; }
  sleep 3
  clock_base || { say "FAIL setup: the device's zone offset could not be read ($(ash date +%z))"; return 1; }
  a shell cmd alarm set-time "$CLOCK_BASE" >/dev/null 2>&1
  [ "$(ash date +%H:%M)" = 10:00 ] || { say "FAIL setup: the clock did not take 10:00 ($(ash date))"; return 1; }
  snooze_foreign || return 1   # B-062: after the clock is set (a snooze's `until` is wall-clock time: a later jump forward would repost it), before the fixture
  a shell "cmd statusbar set-tiles '$STOCK_TILES'" >/dev/null 2>&1   # both sides: ours' list is its counterpart (setup_ours)
  [ "$(setting_get secure sysui_qs_tiles)" = "$STOCK_TILES" ] || { say "FAIL setup: stock's tiles were not set"; return 1; }
  if [ "$SIDE" = stock ]; then
    ours_off || { say "FAIL setup: our strip did not go away (the service is still bound)"; return 1; }
  else
    setup_ours || return 1
  fi
  sleep 1
  return 0
}
setup_ours() {
  local cur tt lay expdir="$TMPD/exp_setup" want_font=()
  export_files "$expdir" || { say "FAIL setup: the app's files could not be exported"; return 1; }
  cur=$(setup_of "$expdir" shade)
  if [ "$cur" != "pixel together" ]; then
    a shell am broadcast -a dev.launcher.app.SETUP -p $PKG --es element shade --es layout pixel --es placement together >/dev/null 2>&1; sleep 2
  fi
  tt=$(theme_of "$expdir")
  if [ "$tt" != pixel ]; then
    a shell am broadcast -a dev.launcher.app.DESIGN_RELOAD -p $PKG --es theme pixel >/dev/null 2>&1; sleep 3
  fi
  ACTIVE_THEME=pixel
  OVERRIDE_REL="design/user-$ACTIVE_THEME.json"
  OURS_TILES_CHANGED=1
  printf '%s\n' "$OURS_TILES" > "$TMPD/qs_tiles.txt"
  import_file "$TMPD/qs_tiles.txt" qs_tiles.txt || { say "FAIL setup: ours' tile list could not be pushed"; return 1; }
  [ "$FONT" = system ] && want_font=("sys.font.text=system" "sys.font.display=system")
  merge_user "$expdir/$OVERRIDE_REL" "$TMPD/override.json" "sys.color.style=system" "${want_font[@]}" || { say "FAIL setup: the design override could not be made"; return 1; }
  import_file "$TMPD/override.json" "$OVERRIDE_REL" || { say "FAIL setup: the design override could not be pushed"; return 1; }
  sleep 3
  ours_on || { say "FAIL setup: our gesture strip did not come up (B-018) after three tries"; return 1; }
  sleep 4
  export_files "$expdir" || { say "FAIL setup: the app's files could not be exported again"; return 1; }
  lay=$(setup_of "$expdir" shade)
  [ "$lay" = "pixel together" ] || { say "FAIL setup: the shade is '$lay', not pixel together"; return 1; }
  [ "$(theme_of "$expdir")" = pixel ] || { say "FAIL setup: the theme is not pixel"; return 1; }
  cmp -s "$expdir/$OVERRIDE_REL" "$TMPD/override.json" || { say "FAIL setup: the design override did not arrive as written"; return 1; }
  [ "$(tr -d '\r\n' < "$expdir/qs_tiles.txt" 2>/dev/null)" = "$OURS_TILES" ] || { say "FAIL setup: ours' tile list is '$(tr -d '\r\n' < "$expdir/qs_tiles.txt" 2>/dev/null)', not $OURS_TILES"; return 1; }
  return 0
}
post_fixture() {
  a shell am broadcast -a dev.launcher.app.TEST_NOTIFY -p $PKG --ei id 0 --ez cancel true >/dev/null 2>&1
  a shell am broadcast -a dev.launcher.app.TEST_NOTIFY -p $PKG --ei id $FIXTURE_ID --ez cancel true >/dev/null 2>&1
  a shell cmd alarm set-time "$CLOCK_BASE" >/dev/null 2>&1   # posted (and alerted) at 10:00:00 ...
  a shell "am broadcast -a dev.launcher.app.TEST_NOTIFY -p $PKG --es title 'Fidelity fixture' --es text 'One test notification' --ei id $FIXTURE_ID" >/dev/null 2>&1
  poll 6 fixture_posted || { say "FAIL setup: the fixture notification was not posted"; return 1; }
  sleep 2
  set_clock   # ... and from here on the clock reads 10:00:45: its alert is older than 30 s, so no render of the row shows the bell
  sleep 33    # the bell the posting's own render showed leaves with its timer (30 s of real time); a heads-up / banner plays out too
  snooze_foreign || return 1   # one more sweep: a record that arrived during the 35 s above is snoozed, not recorded
  NOTIFS="$(notif_set)"   # what the shade lists from here on: the fixture and our own safety notification, nothing of another app (B-062)
  local fk; fk="$(foreign_in "$NOTIFS")" && { say "FAIL setup: another app's notification is still posted after the snooze ($fk): the fixed set is the fixture and ours"; return 1; }
  return 0
}
foreign_keys() { # the keys of the records in `cmd notification list` that are not ours (userId|pkg|id|tag|uid), one per line; Android's overlay notice is left out (as in notif_set)
  ash cmd notification list | grep -E '^[0-9]+[|]' | grep -vE '^[0-9]+[|]android[|]0[|]com[.]android[.]server[.]wm[.]AlertWindowNotification' | awk -F'|' -v p="$PKG" '$2 != p'
}
notif_keys() { # every key in the list, sorted, without the overlay notice and our own test notifications (the fixture is cancelled at the end): what verify_restored compares
  ash cmd notification list | grep -E '^[0-9]+[|]' | grep -vE '^[0-9]+[|]android[|]0[|]com[.]android[.]server[.]wm[.]AlertWindowNotification' | grep -vE "^[0-9]+[|]$PKG[|](1000|$((1000 + FIXTURE_ID)))[|]" | sort
}
foreign_in() { # SET (notif_set's form, "pkg=P id=N;...") -> the entries of another app's package, one per line; true when there is one
  local f; f="$(printf '%s' "$1" | tr ';' '\n' | grep -E '^pkg=' | grep -vE "^pkg=$PKG( |$)")"
  [ -n "$f" ] || return 1
  printf '%s\n' "$f" | tr '\n' ' ' | sed 's/ $//'; return 0
}
pending_until() { # "KEY<TAB>UNTIL" for every pending snooze (dumpsys: "    key: KEY until: EPOCH_MS"; the key may hold spaces: anchored on the last " until: ")
  ash dumpsys notification --noredact | sed -nE 's/^ *key: (.*) until: ([0-9]+) *$/\1\t\2/p'
}
pending_ours() { # the pending snoozes (KEY<TAB>UNTIL) of the keys this run snoozed (PRIOR/snoozed.txt)
  [ -s "$PRIOR_DIR/snoozed.txt" ] || return 0
  pending_until | awk -F'\t' 'NR == FNR { sub(/\r$/, ""); split($0, a, "\t"); k[a[1]] = 1; next } ($1 in k)' "$PRIOR_DIR/snoozed.txt" -
}
snoozed_untils() { # "KEY<TAB>UNTIL" once for every key this run snoozed (PRIOR/snoozed.txt; UNTIL empty when none was read at the snooze)
  [ -s "$PRIOR_DIR/snoozed.txt" ] || return 0
  awk -F'\t' '{ sub(/\r$/, "") } $1 == "" { next } !($1 in u) { o[++n] = $1; u[$1] = "" } $2 != "" { u[$1] = $2 } END { for (i = 1; i <= n; i++) printf "%s\t%s\n", o[i], u[o[i]] }' "$PRIOR_DIR/snoozed.txt"
}
key_hidden() { ! foreign_keys | grep -qxF -- "$1"; }
snooze_foreign() { # snoozes every record that is not ours for 1 h (never unsnoozed by us: restore_snoozed moves the clock past it); keys saved first; 1 on a failure (says which)
  local keys=() k line u
  mapfile -t keys < <(foreign_keys)
  [ "${#keys[@]}" -gt 0 ] || return 0
  for k in "${keys[@]}"; do   # all of them before any is touched: a key with a quote is not trusted through two shells
    case "$k" in *"'"*) say "FAIL setup: the notification key $k holds a quote: the harness does not snooze it (nothing was snoozed for it; clear that notification)"; return 1;; esac
  done
  for k in "${keys[@]}"; do
    printf '%s\t\n' "$k" >> "$PRIOR_DIR/snoozed.txt"   # on disk BEFORE the snooze: a kill leaves the key known
    a shell "cmd notification snooze --for $SNOOZE_MS '$k'" >/dev/null 2>&1
    poll 3 key_hidden "$k" || { say "FAIL setup: the snooze of $k was refused or did not hide it"; return 1; }   # (gone: its app cancelled it meanwhile: not listed, fine)
  done
  line="$(pending_until)"
  for k in "${keys[@]}"; do   # (the key through ENVIRON: awk -v would read a backslash in a tag as an escape)
    u="$(printf '%s\n' "$line" | K="$k" awk -F'\t' '$1 == ENVIRON["K"] { u = $2 } END { print u }')"
    if [ -n "$u" ]; then printf '%s\t%s\n' "$k" "$u" >> "$PRIOR_DIR/snoozed.txt"
    else say "WARNING: no until was read for the snoozed $k (dumpsys's pending snooze list is not in the form this tool reads): the clock cannot bring it back at the end: unless it is posted again by then, the run ends with a warning and keeps the dirty marker"; fi
  done
  say "snoozed ${#keys[@]} notification(s) of other apps for 1 h: $(printf '%s ' "${keys[@]}")"
  return 0
}
notif_set() { # the notification records posted now (package, id, tag), sorted: what a capture shows besides the fixture
  # Android's own "Launcher is displaying over other apps" notice (pkg=android id=0, tag AlertWindowNotification) is left out: the
  # system posts it while our accessibility service is on (overlay windows) and our shade hides it by design; stock's side never has it
  # (service off), so it is no record a shade lists (MF slice 3, from slice 2's notes).
  ash dumpsys notification --noredact | grep 'NotificationRecord(' | grep -v 'pkg=android user=[^ ]* id=0 tag=com[.]android[.]server[.]wm[.]AlertWindowNotification' | sed -E 's/^ *NotificationRecord\([^:]*: //; s/ user=[^ ]*//; s/ (tag|importance|key)=.*//' | sort | tr '\n' ';'
}
manifest_notifs() { # MANIFEST -> the notification set it records (capture.sh's setup block, or a golden's own line); nothing when it has none
  pyx -c "
import sys, json
try:
    d = json.load(open(sys.argv[1], encoding='utf-8'))
    v = d.get('notifications', d.get('setup', {}).get('notifications'))
    if isinstance(v, str):
        sys.stdout.write(v)
except Exception:
    pass" "$(winpath "$1")"
}
warn_golden_notifs() { # ours side: this run's notification set against the golden's for this build (a different one scores Silent-section differences that are no regression)
  local b mf g fk
  b="$(ash getprop ro.build.id | tr -d '\r\n ')"; mf="${GOLDEN_ROOT:-$FID_ROOT/tools/fidelity/golden}/$b/manifest.json"
  [ -f "$mf" ] || return 0
  g="$(manifest_notifs "$mf")"
  if fk="$(foreign_in "$g")"; then say "WARNING: the golden of build $b ($mf) holds another app's notification ($fk): it was captured before other apps' notifications were snoozed (B-062); this run's set cannot match it: recapture the golden (golden.sh capture)"
  elif [ -z "$g" ]; then say "WARNING: the golden of build $b ($mf) records no notification set: a Silent-section difference may come from a notification that was not there when it was taken"
  elif [ "$g" != "$NOTIFS" ]; then say "WARNING: this run's notifications differ from the golden's for build $b (a Silent-section difference may come from that, not from a regression: the fixed set is the fixture and ours, other apps' notifications being snoozed). golden: $g now: $NOTIFS"; fi
  return 0
}
fixture_posted() { ash dumpsys notification --noredact | grep -q "id=$((1000 + FIXTURE_ID)) "; }
set_theme_mode() { # light|dark
  a shell cmd uimode night "$([ "$1" = dark ] && echo yes || echo no)" >/dev/null 2>&1
  sleep 3
}

teardown() {
  [ -z "$TD_DONE" ] || return 0
  TD_DONE=1
  [ -n "$CHANGED" ] || return 0
  say "putting the device back"
  local prior_list night tz tiles f theme_now exp="$TMPD/exp_after" prior_theme prior_shade gv
  to_home >/dev/null 2>&1
  a shell am broadcast -a dev.launcher.app.TEST_NOTIFY -p $PKG --ei id 0 --ez cancel true >/dev/null 2>&1
  a shell am broadcast -a dev.launcher.app.TEST_NOTIFY -p $PKG --ei id $FIXTURE_ID --ez cancel true >/dev/null 2>&1
  # ours' design files, setup and theme
  if [ -n "$OVERRIDE_REL" ]; then
    if [ -f "$PRIOR_DIR/files/$OVERRIDE_REL" ]; then import_file "$PRIOR_DIR/files/$OVERRIDE_REL" "$OVERRIDE_REL"; else remove_file "$OVERRIDE_REL"; fi
    prior_theme=$(theme_of "$PRIOR_DIR/files")
    [ "$prior_theme" = pixel ] || { a shell am broadcast -a dev.launcher.app.DESIGN_RELOAD -p $PKG --es theme "$prior_theme" >/dev/null 2>&1; sleep 2; }
    prior_shade=$(setup_of "$PRIOR_DIR/files" shade)
    if [ -n "$prior_shade" ] && [ "$prior_shade" != "pixel together" ]; then
      a shell am broadcast -a dev.launcher.app.SETUP -p $PKG --es element shade --es layout "${prior_shade% *}" --es placement "${prior_shade#* }" >/dev/null 2>&1; sleep 2
    fi
  fi
  # ours' tile list as it was (reset, or the kept list: the prior export says which; works with the service off)
  if [ -n "$OURS_TILES_CHANGED" ] && [ -s "$PRIOR_DIR/files/qs_tiles.txt" ]; then import_file "$PRIOR_DIR/files/qs_tiles.txt" qs_tiles.txt; fi
  a shell dumpsys battery reset >/dev/null 2>&1
  for f in voice data; do   # the modem as it was
    gv=$(tr -d '\r\n ' < "$PRIOR_DIR/gsm-$f.txt" 2>/dev/null)
    [ -z "$gv" ] || emu gsm $f "$gv" >/dev/null 2>&1
  done
  night=$(cat "$PRIOR_DIR/night.txt" 2>/dev/null | tr -d '\r\n ')
  case "$night" in yes|no|auto) a shell cmd uimode night "$night" >/dev/null 2>&1;; esac
  tiles=$(cat "$PRIOR_DIR/tiles.txt" 2>/dev/null | tr -d '\r')
  if [ -n "$tiles" ] && [ "$tiles" != null ]; then a shell "cmd statusbar set-tiles '$tiles'" >/dev/null 2>&1; fi
  restore_snoozed   # (before the real clock comes back: they are reposted by the faked clock passing their until)
  restore_settings
  # the service as it was (others kept); whether its strip is as it was is verify_restored's to check (strip_as_before)
  prior_list=$(prior_value enabled_accessibility_services)
  if [ "$prior_list" = null ] || [ -z "$prior_list" ]; then clear_a11y; else set_a11y "$prior_list"; fi
  focus_has HomeActivity || { a shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -p $PKG >/dev/null 2>&1; poll 8 focus_has HomeActivity; }
  verify_restored
  [ "$RESTORE_RC" = 0 ] && rm -f "$PRIOR_DIR/dirty" || say "the prior state stays in $PRIOR_DIR (dirty): capture.sh --restore tries again"
}
strip_as_before() { # the gesture strip as the prior list says: up when ours is in it (B-018: switched off and on, twice), else down
  local prior_list cur try; prior_list=$(prior_value enabled_accessibility_services)
  if has_svc "$prior_list"; then
    poll 20 strip_up && return 0
    for try in 1 2; do
      say "restore: gesture strip not up after 20 s (B-018), switching the service off and on (try $try)"
      cur=$(without_ours "$prior_list"); if [ -n "$cur" ]; then set_a11y "$cur"; else clear_a11y; fi
      poll 6 strip_down; sleep 1; set_a11y "$prior_list"
      poll 20 strip_up && return 0
    done
    return 1
  fi
  poll 10 strip_down
}
snoozed_back() { [ -z "$(pending_ours)" ]; }
restore_snoozed() { # B-062: brings our snoozed records back by moving the faked clock past their `until` (the system reposts them, same key); never `cmd notification unsnooze`
  local pend max T cap
  [ -s "$PRIOR_DIR/snoozed.txt" ] || return 0
  pend="$(pending_ours)"
  [ -n "$pend" ] || return 0   # none is pending any more (back already, or gone; or the pending list could not be read: verify_restored checks every key)
  max="$(printf '%s\n' "$pend" | awk -F'\t' '$2 > m { m = $2 } END { printf "%.0f", m }')"
  T=$((max + 5000))
  [ -n "${CLOCK_BASE:-}" ] || clock_base
  if [ -z "${CLOCK_BASE:-}" ] || [ "$T" -gt $((CLOCK_BASE + SNOOZE_CAP_MS)) ]; then
    say "WARNING: the snoozed notifications' until ($max) is more than 3 h past the faked clock's base (${CLOCK_BASE:-unknown}): the clock is not moved that far; they stay snoozed until then (keys in $PRIOR_DIR/snoozed.txt). Check them by hand, then remove $PRIOR_DIR/dirty"
    RESTORE_RC=1; return 1
  fi
  cap=$((CLOCK_BASE + SNOOZE_CAP_MS))   # (every set-time stays within it, the nudge too)
  a shell settings put global auto_time 0 >/dev/null 2>&1
  a shell cmd alarm set-time "$T" >/dev/null 2>&1
  if ! poll 8 snoozed_back; then
    T=$((T + 5000)); [ "$T" -le "$cap" ] || T=$cap; say "restore: the snoozed notifications are still pending after 8 s, moving the clock on once more"
    a shell cmd alarm set-time "$T" >/dev/null 2>&1
    poll 8 snoozed_back || { say "WARNING: still snoozed after the clock passed their until: $(pending_ours | cut -f1 | tr '\n' ' ')"; RESTORE_RC=1; return 1; }
  fi
  say "the snoozed notifications are back"
  return 0
}
verify_restored() { # the settings and the app's files against the prior; says what differs (warnings), sets RESTORE_RC
  local ns f d k u bad=""
  strip_as_before || { say "WARNING: our gesture strip is not $(has_svc "$(prior_value enabled_accessibility_services)" && echo up || echo down) as it was before (the accessibility list is back)"; bad=1; }
  for ns in system secure global; do
    ash settings list $ns | sort > "$TMPD/after-$ns.txt"
    d=$(diff "$PRIOR_DIR/settings-$ns.txt" "$TMPD/after-$ns.txt" | grep '^[<>]' | grep -vE "$VOLATILE" | grep -v 'accessibility_enabled')
    [ -z "$d" ] || { say "WARNING: $ns settings differ from before:"; echo "$d" | cut -c1-160 | sed 's/^/    /'; bad=1; }
  done
  if export_files "$TMPD/exp_final"; then
    for f in setup.json design/theme.txt design/elements.json design/motion.txt qs_tiles.txt; do
      [ -f "$PRIOR_DIR/files/$f" ] || [ -f "$TMPD/exp_final/$f" ] || continue   # absent before and after: nothing to compare
      cmp -s "$PRIOR_DIR/files/$f" "$TMPD/exp_final/$f" || { say "WARNING: $f differs from before"; bad=1; }
    done
    for f in "$PRIOR_DIR"/files/design/user*.json "$TMPD"/exp_final/design/user*.json; do
      [ -f "$f" ] || continue
      f=$(basename "$f")
      if [ -f "$PRIOR_DIR/files/design/$f" ]; then cmp -s "$PRIOR_DIR/files/design/$f" "$TMPD/exp_final/design/$f" || { say "WARNING: design/$f differs from before"; bad=1; }
      else [ ! -f "$TMPD/exp_final/design/$f" ] || { say "WARNING: design/$f was left behind"; bad=1; }; fi
    done
  else say "WARNING: the app's files could not be exported to check them"; bad=1; fi
  d="$(pending_ours | cut -f1)"
  [ -z "$d" ] || { say "WARNING: still snoozed by this run (they come back when the clock passes their until; capture.sh --restore tries again):"; echo "$d" | cut -c1-160 | sed 's/^/    /'; bad=1; }
  # every key this run snoozed is posted again, or explained: one with a known until, gone after the clock passed it, was
  # cancelled by its app (the note below, when it was listed before the run); one with no until read is not known to be back
  # (dumpsys's pending list in another form, or absent, reads as "none pending": it may still be snoozed until a date days ahead),
  # and a cancelled one cannot be told from it: a warning
  : > "$TMPD/snooze-lost.txt"
  if [ -s "$PRIOR_DIR/snoozed.txt" ]; then
    ash cmd notification list > "$TMPD/posted.txt"; printf '%s\n' "$d" > "$TMPD/pending.txt"
    while IFS=$'\t' read -r k u; do
      [ -n "$u" ] || grep -qxF -- "$k" "$TMPD/posted.txt" || grep -qxF -- "$k" "$TMPD/pending.txt" || printf '%s\n' "$k" >> "$TMPD/snooze-lost.txt"
    done < <(snoozed_untils)
    [ ! -s "$TMPD/snooze-lost.txt" ] || { say "WARNING: snoozed by this run with no until read, and not posted again (it may still be snoozed, until a date this tool cannot know, or its app cancelled it): check them by hand in the shade, then remove $PRIOR_DIR/dirty:"; cut -c1-160 "$TMPD/snooze-lost.txt" | sed 's/^/    /'; bad=1; }
  fi
  if [ -f "$PRIOR_DIR/notifs-before.txt" ]; then   # the list as it was: a record gone meanwhile (cancelled by its app while snoozed) or new is a note, never a failure (the keys warned about above left out)
    notif_keys > "$TMPD/notifs-after.txt"; sort "$PRIOR_DIR/notifs-before.txt" > "$TMPD/notifs-before.txt"
    d="$(comm -23 "$TMPD/notifs-before.txt" "$TMPD/notifs-after.txt" | grep -vxFf "$TMPD/snooze-lost.txt")"; [ -z "$d" ] || { say "NOTE: gone since before the run (its app cancelled it, or the user did):"; echo "$d" | cut -c1-160 | sed 's/^/    /'; }
    d="$(comm -13 "$TMPD/notifs-before.txt" "$TMPD/notifs-after.txt")"; [ -z "$d" ] || { say "NOTE: new since before the run:"; echo "$d" | cut -c1-160 | sed 's/^/    /'; }
  fi
  for f in voice data; do
    [ -s "$PRIOR_DIR/gsm-$f.txt" ] || continue
    [ "$(gsm_state $f)" = "$(tr -d '\r\n ' < "$PRIOR_DIR/gsm-$f.txt")" ] || { say "WARNING: the modem's $f state differs from before"; bad=1; }
  done
  [ -z "$bad" ] && say "the device is as before" || RESTORE_RC=1
}
RESTORE_RC=0

# ---------------------------------------------------------------- main
main() {
  parse_args "$@"
  TMPD="$(mktemp -d)"
  if [ -n "$RESTORE" ]; then
    case "$DEVICE" in emulator-*) ;; *) echo "capture.sh REFUSED: DEVICE=$DEVICE is not an emulator-*" >&2; rm -rf "$TMPD"; exit 2;; esac
    [ -f "$PRIOR_DIR/dirty" ] || { echo "capture.sh: nothing to restore ($PRIOR_DIR/dirty is not there)"; rm -rf "$TMPD"; exit 0; }
    SIDE=stock; CHANGED=1; OVERRIDE_REL=""; OURS_TILES_CHANGED=1   # (what the killed run changed is not known: ours' list too is put back)
    # which design file this run changed is not known: restore_overrides puts back the one this tool writes (user-pixel.json)
    restore_overrides
    clock_base   # (restore_snoozed's cap)
    teardown; local rc=$RESTORE_RC; rm -rf "$TMPD"; exit $rc
  fi
  preconditions
  MANIFEST_FILES=()
  if [ -n "$DRY" ]; then
    say "DRY RUN on $DEVICE ($(ash getprop ro.build.fingerprint)): nothing is changed"
    say "would save the prior state to $PRIOR_DIR, then set: 24 h, clock 2026-11-02 10:00 in the device's zone, battery unplugged at 100, brightness 127, DND off, Auto-rotate, the modem unregistered"
    say "would set stock's tiles to $STOCK_TILES"
    [ "$SIDE" = stock ] && say "would switch our accessibility service off (others kept)$([ -n "$DUMP" ] && echo ", and save stock's uiautomator tree of each state (--dump)")"
    [ "$SIDE" = ours ] && say "would set ours to shade pixel/together, theme pixel, system colours, font $FONT (design/user-pixel.json), tiles $OURS_TILES, our service on"
    local fk; fk="$(foreign_keys | tr '\n' ' ')"
    say "would snooze every notification of another app for 1 h once the clock is set (now: ${fk:-none}) and bring them back at the end by moving the clock past their until (never cmd notification unsnooze)"
    say "would post the fixture notification (TEST_NOTIFY id $FIXTURE_ID) and capture, $REPEAT time(s): themes [$THEMES] states [$STATES] into $OUT/$SIDE/<theme>/<state>.png"
    say "would put everything back at the end"
    rm -rf "$TMPD"; exit 0
  fi
  mkdir -p "$OUT" || { echo "capture.sh REFUSED: cannot make $OUT" >&2; rm -rf "$TMPD"; exit 2; }
  save_prior; local sp=$?
  [ "$sp" != 3 ] || { echo "capture.sh REFUSED: the app's export has no qs_tiles.txt (a build older than MF slice 2: install a newer one). Nothing was changed." >&2; rm -rf "$TMPD"; exit 2; }
  [ "$sp" = 0 ] || { echo "capture.sh REFUSED: the prior state could not be saved (the app's files export failed: is the app running?)" >&2; rm -rf "$TMPD"; exit 2; }
  trap 'teardown; rm -rf "$TMPD"' EXIT
  trap 'say "interrupted"; teardown; rm -rf "$TMPD"; trap - EXIT; exit 130' INT TERM HUP
  local rc=0 r theme state
  apply_setup && post_fixture || rc=1
  if [ "$rc" = 0 ] && [ "$SIDE" = ours ]; then warn_golden_notifs; fi
  if [ "$rc" = 0 ]; then
    for ((r = 1; r <= REPEAT; r++)); do
      for theme in $THEMES; do
        set_theme_mode "$theme"
        for state in $STATES; do
          capture_state "$r" "$theme" "$state" || rc=1
        done
      done
    done
  fi
  write_manifest
  teardown
  [ "$RESTORE_RC" = 0 ] || rc=1
  trap - EXIT
  rm -rf "$TMPD"
  [ "$rc" = 0 ] && say "done: $OUT" || say "FINISHED WITH FAILURES (exit 1): $OUT"
  exit "$rc"
}
restore_overrides() { # --restore: ours' override files this tool may have written (user-pixel.json), as they were
  local rel="design/user-pixel.json"
  if [ -f "$PRIOR_DIR/files/$rel" ]; then import_file "$PRIOR_DIR/files/$rel" "$rel"; else remove_file "$rel"; fi
  OVERRIDE_REL=""
  local pt ps
  pt=$(theme_of "$PRIOR_DIR/files")
  a shell am broadcast -a dev.launcher.app.DESIGN_RELOAD -p $PKG --es theme "$pt" >/dev/null 2>&1
  ps=$(setup_of "$PRIOR_DIR/files" shade)
  [ -z "$ps" ] || a shell am broadcast -a dev.launcher.app.SETUP -p $PKG --es element shade --es layout "${ps% *}" --es placement "${ps#* }" >/dev/null 2>&1
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then main "$@"; fi
