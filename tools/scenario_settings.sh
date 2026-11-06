#!/usr/bin/env bash
# The settings app's own motion, from what the screen showed (framestats of the window SettingsActivity): opened, a tile's
# page grown out of its card and closed with its back button (two tiles), the tile list scrolled down and back, closed into
# home with the home gesture. Written for T-002 (the Compose upgrade), run by tools/compare_s24.sh.
#   DEVICE=<serial> tools/scenario_settings.sh
# Our own screens only (screenshots of the settings, kept in tools/shots/, never anything else). Gated as Codex asked in
# T-002's cross-check: every gesture is preceded by a focus check; a tile is tapped only where its template is found on a
# fresh screenshot (tools/find_icon.py, scored under its limit), never at a fixed place (the S24's settings hold a "Your
# changes" row the emulator's do not, which moved the tiles: a fixed tap hit the gap above them); the outcome of each step
# is checked on a fresh screenshot (a page opened: its tile is gone; back: the tile is where it was; the scroll settled:
# the tile is at its first place within 4 px). Anything else stops (exit 1) before another touch. The theme, colour and
# animation chips are never targets, and a tap only ever lands on a found tile or on the page's back button (top left,
# 100,135, tapped only once a page is shown open). Templates: tools/shots/tpl_set_home.png and tpl_set_library.png, the
# tiles' titles cut from the S24's own settings (2026-11-07, dark); another look (light) is not found, and the run stops.
cd "$(dirname "$0")/.."
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
D=(-s "$DEVICE")
SHOT=tools/shots/gate_settings.png
focus() { "$ADB" "${D[@]}" shell dumpsys window | grep mCurrentFocus; }
stop() { echo "STOP at $1: $(focus | sed 's/.*u0 //')"; exit 1; }
need() { focus | grep -q "$1" || stop "$2"; }
reset() { "$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app reset >/dev/null; }
measure() {   # $1 = step name
  "$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app framestats > "tools/shots/fs_set_$1.txt"
  echo "$1: $(python tools/framestats.py "tools/shots/fs_set_$1.txt" SettingsActivity | grep -E 'shown|GPU ms' | tr '\n' ' ')"
}
shot() { need SettingsActivity "$1 (before the screenshot)"; "$ADB" "${D[@]}" exec-out screencap -p > "$SHOT"; }
find() { python tools/find_icon.py "$SHOT" "tools/shots/$1" 2>/dev/null | tail -1; }   # prints "X Y", fails if not found
found() { python tools/find_icon.py "$SHOT" "tools/shots/$1" >/dev/null 2>&1; }
tap() { "$ADB" "${D[@]}" shell input tap "$1" "$2"; }
swipe() { "$ADB" "${D[@]}" shell input swipe "$@"; }

open_tile() {   # $1 = template, $2 = step: taps the tile where it is found, then checks its page is open
  local xy
  shot "$2"; xy=$(python tools/find_icon.py "$SHOT" "tools/shots/$1" 2>/dev/null) || stop "$2 ($1 not on the tile list)"
  xy=$(echo "$xy" | tail -1)
  need SettingsActivity "$2 (before the tap)"
  reset; tap $xy; sleep 1.6
  shot "$2 (after)"; found "$1" && stop "$2 (the page did not open: $1 still shown)"
  measure "$2"
}
back_to_list() {   # $1 = template that must be back, $2 = step
  need SettingsActivity "$2 (before the back button)"
  reset; tap 100 135; sleep 1.6
  shot "$2 (after)"; found "$1" || stop "$2 (the tile list did not come back)"
  measure "$2"
}
place() { python tools/find_icon.py "$SHOT" "tools/shots/$1" 2>/dev/null | tail -1; }

need HomeActivity "start (home must be in front)"
reset; "$ADB" "${D[@]}" shell am start -n dev.launcher.app/.settings.SettingsActivity >/dev/null; sleep 2.5
need SettingsActivity "settings open"; measure open
open_tile tpl_set_home.png page_open
back_to_list tpl_set_home.png page_close
shot "scroll (before)"; found tpl_set_home.png || stop "scroll (Home tile not found)"; start=$(place tpl_set_home.png)
need SettingsActivity "scroll (before the swipe)"
reset; swipe 540 1900 540 700 250; sleep 1.2
need SettingsActivity "scroll (before the swipe back)"
swipe 540 700 540 1900 250; sleep 1.6
shot "scroll (after)"; found tpl_set_home.png || stop "scroll (Home tile not back)"; end=$(place tpl_set_home.png)
dy=$(( ${end#* } - ${start#* } )); [ ${dy#-} -le 4 ] || stop "scroll (the list settled ${dy} px from where it started)"
measure scroll
open_tile tpl_set_library.png page2_open
back_to_list tpl_set_library.png page2_close
need SettingsActivity "close (before the home gesture)"
reset; swipe 540 2330 540 1700 150; sleep 2.2; need HomeActivity "closed into home"; measure close
echo done
