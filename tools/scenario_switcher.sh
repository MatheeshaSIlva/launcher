#!/usr/bin/env bash
# The App Switcher on a phone, measured without looking at it (the deck shows other apps' snapshots: no screenshots).
# Opens Calculator (App Library) then Clock (Spotlight); holds a home swipe in Clock (switcher), taps the focused card
# (Calculator); holds again and taps empty space (home); holds on home and taps the focused card (Calculator). Prints frame logs and what the screen showed for each step.
# Every tap is gated: on the focused window, or on the log saying the switcher opened.
#   DEVICE=<serial> tools/scenario_switcher.sh
cd "$(dirname "$0")/.."
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
D=(-s "$DEVICE")
focus() { "$ADB" "${D[@]}" shell dumpsys window | grep mCurrentFocus; }
stop() { echo "$1: stop"; exit 1; }
reset() { "$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app reset >/dev/null; }
measure() {
  "$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app framestats > "tools/shots/fs_$1.txt"
  echo "== $1"; tools/device.sh log 12 | grep -A2 "\[switcher\]" | grep -E "switcher\]|frames" | tail -4 | sed 's/.*Launcher: *//'
  python tools/framestats.py "tools/shots/fs_$1.txt" LauncherCards | grep -E "GPU ms|shown|frame #"
}
hold() {   # a short swipe up from the bar that rests: the switcher (checked in the log)
  local before; before=$(tools/device.sh log 400 | grep -c "\[switcher\] open")
  "$ADB" "${D[@]}" shell "input motionevent DOWN 540 2330; for y in 2310 2280 2240 2200 2160 2140 2130; do input motionevent MOVE 540 \$y; done; sleep 0.6; input motionevent UP 540 2130"
  sleep 1.2
  [ "$(tools/device.sh log 400 | grep -c "\[switcher\] open")" -gt "$before" ] || stop "the switcher did not open"
}
focus | grep -q HomeActivity || stop "home is not in front"
# Calculator from the App Library, back home, Clock from Spotlight.
tools/device.sh swipe 540 2330 540 1700 150; sleep 1
tools/device.sh swipe 900 1200 150 1200 250; sleep 1
tools/device.sh shot gate >/dev/null
xy=$(python tools/find_icon.py tools/shots/gate.png tools/shots/tpl_calc.png 2>/dev/null) || stop "Calculator icon not found"
tools/device.sh tap $xy; sleep 2.5
focus | grep -q popupcalculator || stop "Calculator not in front"
tools/device.sh swipe 540 2330 540 1500 180; sleep 1.5
focus | grep -q HomeActivity || stop "home is not in front"
tools/device.sh swipe 540 2330 540 1700 150; sleep 1
tools/device.sh tap 540 1931; sleep 1
"$ADB" "${D[@]}" shell dumpsys input_method | grep -q "mInputShown=true" || stop "Spotlight keyboard not up"
MSYS_NO_PATHCONV=1 "$ADB" "${D[@]}" shell input text clock; sleep 0.8
tools/device.sh tap 170 460; sleep 2.5
focus | grep -q clockpackage || stop "Clock not in front"
# 1: the hold opens the switcher.
reset; hold; measure switcher_in
# 2: tap the focused card (the previous app, Calculator).
reset; tools/device.sh tap 610 1205; sleep 2
focus | grep -q popupcalculator || stop "Calculator not in front after tapping its card"
measure switcher_open
# 3: hold again, tap empty space above the cards: home.
reset; hold; reset; tools/device.sh tap 540 260; sleep 1.5
focus | grep -q HomeActivity || stop "home is not in front after tapping empty space"
measure switcher_home
# 4: from home: hold a swipe up on home, tap the focused card (the most recent app, Calculator).
reset; hold; measure switcher_in_home
reset; tools/device.sh tap 610 1205; sleep 2
focus | grep -q popupcalculator || stop "Calculator not in front after tapping its card (from home)"
measure switcher_open_home
tools/device.sh swipe 540 2330 540 1500 180; sleep 1.5
