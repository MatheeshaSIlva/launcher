#!/usr/bin/env bash
# Install the fresh debug build on DEVICE and restart home in it (a clean process each time).
cd "$(dirname "$0")/.."
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
D=(); [ -n "$DEVICE" ] && D=(-s "$DEVICE")
tools/device.sh install >/dev/null
"$ADB" "${D[@]}" shell am force-stop dev.launcher.app
# As home (MAIN + HOME, our package, no component): started by component it lands in an ordinary task, not the home task.
# The package picks us even when another launcher is the default.
"$ADB" "${D[@]}" shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -p dev.launcher.app >/dev/null
sleep "${1:-5}"
