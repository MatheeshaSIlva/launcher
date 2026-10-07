#!/bin/bash
# Runs each scenario of RefUITests on the booted simulator while recording the screen.
#   capture.sh UDID XCTESTRUN OUT [TEST...]
# Per scenario OUT/<test>/: video.mp4 (simctl recordVideo), rec.log (timestamped recorder output: when it started),
# test.log (xcodebuild output with the REFMARK lines), screenshots and accessibility trees the test wrote, result.xcresult.
set -u
UDID=$1; XCTESTRUN=$2; OUT=$3; shift 3
TESTS=("$@")
if [ ${#TESTS[@]} -eq 0 ]; then
  TESTS=($(grep -o 'func test[0-9A-Za-z_]*' "$(dirname "$0")/RefUITests/Scenarios.swift" | sed 's/func //'))
fi
now() { python3 -c 'import time; print("%.3f" % time.time())'; }

for t in "${TESTS[@]}"; do
  d="$OUT/$t"; mkdir -p "$d"
  echo "=== $t"
  # The recorder prints a line once it really records: each line gets the wall time it arrived.
  ( xcrun simctl io "$UDID" recordVideo --codec=h264 --force "$d/video.mp4" 2>&1 | while IFS= read -r l; do echo "$(now) $l"; done > "$d/rec.log" ) &
  sleep 2
  TEST_RUNNER_REF_OUT="$d" xcodebuild test-without-building -xctestrun "$XCTESTRUN" -destination "id=$UDID" \
    -only-testing:"RefUITests/Scenarios/$t" -resultBundlePath "$d/result.xcresult" > "$d/test.log" 2>&1
  echo "xcodebuild exit $?" >> "$d/test.log"
  sleep 1
  pid=$(pgrep -f "recordVideo --codec=h264 --force $d/video.mp4" | head -1)
  [ -n "$pid" ] && kill -INT "$pid"
  for _ in $(seq 1 40); do pgrep -f "recordVideo --codec=h264 --force $d/video.mp4" > /dev/null || break; sleep 0.25; done
  wait
  grep -h "REFMARK" "$d/test.log" | sed 's/.*REFMARK/REFMARK/' | sort -u -k2,2n > "$d/marks.txt" || true
  # Attachments (screenshots, trees) in case the test could not write to the host folder.
  xcrun xcresulttool export attachments --path "$d/result.xcresult" --output-path "$d/attachments" > /dev/null 2>&1 || true
  ls -la "$d" | tail -n +2
done
