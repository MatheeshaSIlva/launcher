#!/usr/bin/env bash
# Slow motion FACTOR (1 = off) for our springs (the motion.debug.slow token, as an edit of the motion preset PRESET,
# default ios27: files/design/motion-edits-PRESET.json, where motion edits live; theme edits in user.json do not reach
# motion) and the system's animators; restarts the app. DEVICE (default emulator-5554). Replaces the preset's other
# motion edits: emulator only.
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
F=${1:-1}
FILE="files/design/motion-edits-${PRESET:-ios27}.json"
"$ADB" -s "${DEVICE:-emulator-5554}" shell am force-stop dev.launcher.app
if [ "$F" = 1 ]; then MSYS_NO_PATHCONV=1 "$ADB" -s "${DEVICE:-emulator-5554}" shell run-as dev.launcher.app rm -f "$FILE"
else printf '{"format":1,"name":"user","tokens":{"motion.debug.slow":{"factor":%s,"src":"judged:audit"}}}' "$F" | MSYS_NO_PATHCONV=1 "$ADB" -s "${DEVICE:-emulator-5554}" shell "run-as dev.launcher.app sh -c 'cat > $FILE'"; fi
"$ADB" -s "${DEVICE:-emulator-5554}" shell settings put global animator_duration_scale $F
"$ADB" -s "${DEVICE:-emulator-5554}" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -p dev.launcher.app >/dev/null 2>&1
