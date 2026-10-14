#!/usr/bin/env bash
# Records a Perfetto trace on the device for SECS seconds while you (or a script) act on it, then pulls it to
# tools/shots/NAME.pftrace. Starts in the background and returns at once: run the steps, then `tools/trace.sh pull NAME`.
#   DEVICE=<serial> tools/trace.sh start NAME [SECS]
#   DEVICE=<serial> tools/trace.sh pull NAME          (waits for the trace to finish)
#   python tools/trace_slices.py tools/shots/NAME.pftrace [MIN_MS]
cd "$(dirname "$0")/.."
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
D=(-s "$DEVICE")
cmd=$1; name=$2
remote="/data/misc/perfetto-traces/$name.pftrace"
case "$cmd" in
  start)
    secs=${3:-10}
    { cat tools/perfetto_launcher.cfg; echo "duration_ms: $((secs * 1000))"; } > "tools/shots/.perfetto_$name.cfg"
    MSYS_NO_PATHCONV=1 "$ADB" "${D[@]}" push "$(cygpath -m "tools/shots/.perfetto_$name.cfg")" /data/local/tmp/perfetto_launcher.cfg >/dev/null
    MSYS_NO_PATHCONV=1 "$ADB" "${D[@]}" shell "rm -f $remote; cat /data/local/tmp/perfetto_launcher.cfg | perfetto --background --txt -c - -o $remote" >/dev/null
    sleep 1 ;;
  pull)
    while [ -n "$("$ADB" "${D[@]}" shell pidof perfetto | tr -d '\r')" ]; do sleep 0.5; done
    MSYS_NO_PATHCONV=1 "$ADB" "${D[@]}" pull "$remote" "$(cygpath -m "tools/shots/$name.pftrace")" >/dev/null && echo "tools/shots/$name.pftrace" ;;
  *) sed -n 2,7p "$0" ;;
esac
