#!/usr/bin/env bash
# Slow motion FACTOR (1 = off) for our springs (the motion.debug.slow token, as a token edit) and the system's animators;
# restarts the app. DEVICE (default emulator-5554). The token edit replaces the user's other token edits: emulator only.
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
F=${1:-1}
"$ADB" -s "${DEVICE:-emulator-5554}" shell am force-stop dev.launcher.app
if [ "$F" = 1 ]; then MSYS_NO_PATHCONV=1 "$ADB" -s "${DEVICE:-emulator-5554}" shell run-as dev.launcher.app rm -f files/design/user.json
else printf '{"format":1,"name":"user","tokens":{"motion.debug.slow":{"factor":%s,"src":"judged:audit"}}}' "$F" | MSYS_NO_PATHCONV=1 "$ADB" -s "${DEVICE:-emulator-5554}" shell "run-as dev.launcher.app sh -c 'cat > files/design/user.json'"; fi
"$ADB" -s "${DEVICE:-emulator-5554}" shell settings put global animator_duration_scale $F
"$ADB" -s "${DEVICE:-emulator-5554}" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -p dev.launcher.app >/dev/null 2>&1
