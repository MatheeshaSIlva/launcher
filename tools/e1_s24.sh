#!/usr/bin/env bash
# E1's exit on the S24 (docs/E1_INVENTORY.md §5, exit (e)): the reference build (9984034, the last commit before :engine)
# and E1's build (3d8bc67) installed in turn, ref, E1, ref, E1, each measured with tools/scenario_switch.sh (N=2) and
# tools/scenario_shade.sh (N=3) from what the screen showed (framestats). Two rounds, so thermal drift shows as a trend
# across rounds, not as a bias for one build. The phone is left on E1's build.
#
#   DEVICE=<the S24's serial> tools/e1_s24.sh
#
# The APKs come from tools/shots/e1-s24/apks/{ref,e1}/app-debug.apk + app-debug.dm (built with -PbuildSha, so each logs
# "BUILD <sha7>" and the Shizuku service of one build is never reused by the other: its version is the stamp's hash), their
# md5s in apks/md5.txt, checked before anything is touched.
#
# Before every install and every scenario (stops at the first that fails, says why, touches nothing more):
#   - the device is online, awake (dumpsys power: mWakefulness=Awake) and not locked (dumpsys window: isKeyguardShowing=false);
#   - nobody is using it: the last user activity is >= 120 s ago, or it is our own last scripted touch (the device's uptime
#     when our last scenario ended): a touch of the owner's after that stops the run;
#   - the focused window is dev.launcher.app's.
# Each install is what tools/device.sh install does (install-multiple -r -d with the .dm, our home set as the default, the
# HOME intent), with the APK's own path (device.sh installs app/build's only); never a force-stop. Then it waits for the new
# process's "BUILD <sha7>", "[shizuku] service connected: true" and "[nav] gesture strip on", checks `pm art dump` says
# speed-profile with reason install-dm, and logs the temperatures (dumpsys thermalservice) and the CPUs' max frequencies.
# Output: tools/shots/e1-s24/run-<time>/ (run.log, each scenario's output, its framestats files, summary.txt: per animation the
# missed refreshes and GPU/CPU p90 for each of the 4 runs side by side, and each build's mean).
# Privacy: no screenshot or recording (the scenarios run without SHOT). scenario_shade.sh pulls Notification Center, which
# shows the phone's own notifications on screen; nothing of it is captured. The banner is our own test notification.
set -u
cd "$(dirname "$0")/.." || exit 2
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"; [ -x "$ADB" ] || ADB=adb
[ -n "${DEVICE:-}" ] || { echo "REFUSED: DEVICE=<the S24's serial> tools/e1_s24.sh"; exit 2; }
case "$DEVICE" in emulator-*) echo "REFUSED: this run is for the S24 (DEVICE=$DEVICE)"; exit 2 ;; esac
export DEVICE ADB
PKG=dev.launcher.app
APKS=tools/shots/e1-s24/apks
RUN=tools/shots/e1-s24/run-$(date +%Y%m%d-%H%M%S)
IDLE_MS=120000
ORDER="ref e1 ref e1"
sha_of() { case "$1" in ref) echo 9984034 ;; e1) echo 3d8bc67 ;; esac; }
mkdir -p "$RUN"
OURS_END=0   # device uptime (ms) when our last scripted touch ended; 0: none yet

a() { "$ADB" -s "$DEVICE" "$@"; }
say() { echo "$(date +%T) $*" | tee -a "$RUN/run.log"; }
installed() { a shell dumpsys package $PKG | tr -d '\r' | sed -n 's/.*versionName=//p' | head -1; }
stop() { say "STOP: $*"; say "the phone is left on BUILD $(installed)"; exit 1; }
uptime_ms() { a shell cat /proc/uptime | tr -d '\r' | awk '{printf "%d", $1 * 1000}'; }

ready() {   # $1 = what comes next
  [ "$(a get-state 2>/dev/null | tr -d '\r')" = device ] || stop "before $1: $DEVICE is not online"
  a shell dumpsys power | tr -d '\r' | grep -q "mWakefulness=Awake" || stop "before $1: the screen is off"
  a shell dumpsys window | tr -d '\r' | grep -q "isKeyguardShowing=false" || stop "before $1: the phone is locked"
  local line at ago now
  line=$(a shell dumpsys power | tr -d '\r' | grep -m1 -E "^ *lastUserActivityTime=")
  at=$(echo "$line" | sed -n 's/.*lastUserActivityTime=\([0-9]*\).*/\1/p')
  ago=$(echo "$line" | sed -n 's/.*(\([0-9]*\) ms ago).*/\1/p')
  [ -n "$at" ] && [ -n "$ago" ] || stop "before $1: could not read the last user activity ($line)"
  if [ "$ago" -lt "$IDLE_MS" ] && [ "$at" -gt $((OURS_END + 1000)) ]; then
    stop "before $1: someone used the phone $((ago / 1000)) s ago (needs ${IDLE_MS} ms untouched, or no touch since ours)"
  fi
  a shell dumpsys window | tr -d '\r' | grep mCurrentFocus | grep -q "$PKG/" \
    || stop "before $1: the focused window is not ours ($(a shell dumpsys window | tr -d '\r' | grep mCurrentFocus | sed 's/.*u0 //'))"
}

conditions() {   # temperatures and CPU limits, before each round
  say "temperatures (dumpsys thermalservice, current from HAL):"
  a shell dumpsys thermalservice | tr -d '\r' | sed -n '/Current temperatures from HAL/,/Current cooling devices/p' \
    | grep "Temperature{" | sed 's/^[[:space:]]*/    /' | tee -a "$RUN/run.log"
  say "CPU max frequencies (kHz): $(a shell 'cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_max_freq' | tr -d '\r' | tr '\n' ' ')"
}

install_build() {   # $1 = ref | e1
  local b=$1 sha pid i log
  sha=$(sha_of "$b")
  ready "installing $b ($sha)"
  say "install $b: $APKS/$b/app-debug.apk + .dm"
  a install-multiple -r -d "$APKS/$b/app-debug.apk" "$APKS/$b/app-debug.dm" 2>&1 | tail -1 | tee -a "$RUN/run.log" | grep -q Success \
    || stop "the install of $b failed"
  a shell cmd package set-home-activity $PKG/.HomeActivity >/dev/null
  a shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -p $PKG >/dev/null
  [ "$(installed)" = "$sha" ] || stop "after installing $b: the phone reports BUILD $(installed), not $sha"
  for i in $(seq 1 90); do
    pid=$(a shell pidof $PKG | tr -d '\r' | awk '{print $1}')
    if [ -n "$pid" ]; then
      log=$(a logcat -d --pid="$pid" | tr -d '\r')
      if echo "$log" | grep -q "FATAL EXCEPTION"; then
        sleep 2; a shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -p $PKG >/dev/null; continue
      fi
      if echo "$log" | grep -q "Launcher: BUILD $sha" && echo "$log" | grep -q "\[shizuku\] service connected: true" \
        && echo "$log" | grep -q "\[nav\] gesture strip on"; then
        echo "$log" | grep -E "Launcher: (BUILD|\[shizuku\] service connected|\[nav\] gesture strip on)" | tee -a "$RUN/run.log"
        break
      fi
    fi
    sleep 1
  done
  [ "$i" -lt 90 ] || stop "after installing $b: no BUILD $sha + Shizuku connected + gesture strip in 90 s"
  a shell pm art dump $PKG | tr -d '\r' | grep -E "status=" | tee -a "$RUN/run.log" \
    | grep -q "\[status=speed-profile\] \[reason=install-dm\]" || stop "after installing $b: not speed-profile from install-dm"
  a shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -p $PKG >/dev/null
  sleep 3
}

scenario() {   # $1 = run label (1-ref...), $2 = switch | shade
  local dir="$RUN/$1" rc
  mkdir -p "$dir"
  ready "scenario $2 of run $1"
  rm -f tools/shots/fs_switch_*.txt tools/shots/fs_s_*.txt
  say "scenario $2 (run $1)"
  if [ "$2" = switch ]; then tools/scenario_switch.sh 2 > "$dir/switch.txt" 2>&1; rc=$?
  else N=3 tools/scenario_shade.sh > "$dir/shade.txt" 2>&1; rc=$?; fi
  OURS_END=$(uptime_ms)
  cp tools/shots/fs_switch_*.txt tools/shots/fs_s_*.txt "$dir/" 2>/dev/null
  [ "$rc" -eq 0 ] || stop "scenario $2 of run $1 ended with exit $rc (see $dir/$2.txt)"
  sleep 3
}

# ------------------------------------------------------------------------------------------------- before any touch
(cd "$APKS" && md5sum -c md5.txt >/dev/null 2>&1) || { echo "REFUSED: the APKs do not match $APKS/md5.txt"; exit 2; }
say "E1 S24 run on $DEVICE: order $ORDER; results in $RUN"
say "phone now on BUILD $(installed)"
n=0
for b in $ORDER; do
  n=$((n + 1)); label="$n-$b"
  say "== run $label ($(sha_of "$b"))"
  conditions
  install_build "$b"
  scenario "$label" switch
  scenario "$label" shade
done
say "done: the phone is on BUILD $(installed)"

# ------------------------------------------------------------------------------------------------- summary
python - "$RUN" "$ORDER" <<'PY' | tee "$RUN/summary.txt"
import os, re, subprocess, sys
run, order = sys.argv[1], sys.argv[2].split()
labels = [f"{i + 1}-{b}" for i, b in enumerate(order)]
def stats(path, window):
    t = subprocess.run([sys.executable, "tools/framestats.py", path, window], capture_output=True, text=True).stdout
    n = re.search(r"shown: (\d+) frames.*?missed refreshes ~(\d+)", t, re.S)
    g = re.search(r"GPU ms: median ([\d.]+)\s+p90 ([\d.]+)\s+max ([\d.]+)", t)
    c = re.search(r"CPU ms: median ([\d.]+)\s+p90 ([\d.]+)\s+max ([\d.]+)", t)
    if not n: return None
    return int(n.group(1)), int(n.group(2)), float(g.group(2)) if g else None, float(c.group(2)) if c else None
steps = {}
for lab in labels:
    d = os.path.join(run, lab)
    for f in sorted(os.listdir(d)) if os.path.isdir(d) else []:
        if not f.startswith("fs_"): continue
        window = "LauncherCards" if f.startswith("fs_switch") else "LauncherStatusBar"
        steps.setdefault(f[3:-4], {})[lab] = stats(os.path.join(d, f), window)
print("E1 on the S24: missed refreshes / GPU p90 ms / CPU p90 ms per animation (framestats, what the screen showed)")
print("runs: " + ", ".join(labels))
print(f"{'animation':22s}" + "".join(f"{l:>22s}" for l in labels) + f"{'ref mean miss':>15s}{'e1 mean miss':>14s}")
fmt = lambda s: "no frames" if not s else f"{s[1]} / {s[2] if s[2] is not None else '?'} / {s[3] if s[3] is not None else '?'}"
for step in sorted(steps):
    row = steps[step]
    mean = lambda b: (lambda v: f"{sum(v) / len(v):.1f}" if v else "-")([row[l][1] for l in labels if l.endswith(b) and row.get(l)])
    print(f"{step:22s}" + "".join(f"{fmt(row.get(l)):>22s}" for l in labels) + f"{mean('ref'):>15s}{mean('e1'):>14s}")
PY
