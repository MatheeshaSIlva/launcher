# iOS reference capture

iOS 27's own system UI, recorded in the iOS Simulator on GitHub's macOS runner (`xcode-27`), so the launcher's iOS
profile is built from measurements, not from articles and screenshots. Nobody here has a Mac or an iPhone: this is the
only way to see the real thing move.

- `RefUITests/Scenarios.swift`: one scenario per test. Drives SpringBoard (Control Center, Notification Center, banners,
  home) with exact touches (start point, speed, hold), saves screenshots and the accessibility tree (every element's frame
  in points) and prints `REFMARK <unix time> <what>` lines.
- `RefHost/`: a tiny app that posts local notifications on cue (`-post <set> <delay>`), for real banners and stacks.
- `capture.sh`: records the screen around each scenario (`simctl io recordVideo`).
- `.github/workflows/ios-reference.yml`: builds (XcodeGen + xcodebuild) and runs everything on the newest iPhone Pro
  simulator; the results are the run's artifact `ios-reference-<n>`.

Run it: push to the `ios-reference` branch, or `gh workflow run ios-reference.yml --ref ios-reference -f tests="test02_ccOpenClose"`.
Fetch: `gh run download <run id> -D ios-reference/out` (git-ignored). Analysis tools: `tools/ios_ref/`.

Limits: the Simulator is not a phone (no calls, no cellular, no 120 Hz, a CI machine without a real GPU may drop frames:
every frame's own time is used, never a frame count). Motion found here is checked against the recordings' timing.
