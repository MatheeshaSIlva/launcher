#!/usr/bin/env bash
# Install the fresh debug build on DEVICE and restart home in it (a clean process each time).
cd "$(dirname "$0")/.."
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
D=${DEVICE:+-s $DEVICE}
tools/device.sh install >/dev/null
"$ADB" $D shell am force-stop dev.launcher.app
"$ADB" $D shell am start -W -n dev.launcher.app/.HomeActivity >/dev/null
sleep "${1:-5}"
