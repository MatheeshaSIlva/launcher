# Comprehension report: status-pill.md

Reviewer: `egm-comprehension` (fresh context; read the target only). FeatureRange 1..232, DetailedRange 233..306.

## What this proposes

The launcher hides Android's own status bar and puts a single black "pill" (the island) over the camera cutout instead. At rest it shows the time and battery. It changes shape, always with interruptible animation, to show one state at a time in rank order: notification, then charging, then media, then idle. A notification shows in one of two modes. In minimal mode the pill widens to show the sender's name. In detailed mode a banner pulls away from the pill like a liquid drop, shows one line of the message, and merges back. On the lock screen a notification shows only the app's name. The island is a KDL component drawn by the engine (`blob` primitive, AGSL shader, `BlobElastic` physics). It ships behind a developer switch until the S24 frame budget, the emulator proof, unit tests, a drift report, Codex sign-off and the owner's on-phone sign-off are all done.

## How the existing system works, per this document

The launcher currently draws its own bar (`StatusBarView`) and hides Android's bar using already-proven disable flags. There is an engine where KDL components describe the UI. Jetpack Compose owns the tree, the engine draws the pixels through RenderNodes, and named data sources (battery, media, time, device, notifications) feed values into components through `SourceBinder` and `ComponentHost`. Notifications come from the launcher's own shade (`Notifs`). `KdlArrivals` and `ArrivalTracker` decide what counts as an arrival, how long it is held and how it is redacted. Motion runs on the launcher's existing spring and bezier machinery (`NodeMotion`). Decisions are recorded as numbered D-entries, and an owner plus a reviewer called Codex sign off on work.

The paraphrase holds together end to end, so no section is a Blocker.

## Findings

1. **Major. "What done means" (lines 300-302), plus line 296.** "The drift report", "Codex has signed AGREE" and "D-119, gate 3" are undefined; the exit criteria cannot be judged. Define each in a line or link `docs/team/PROTOCOL.md` / `docs/DECISIONS.md`.
2. **Major. Rollout and verification assume an unstated workflow (lines 59, 73, 228-229, 278).** The developer switch is argument fragments; never says it is an `adb shell am broadcast` to the app's package. "Set over adb" likewise; `TEST_NOTIFY` unexplained.
3. **Major. Colour names contradict a Non-Goal (lines 99, 117-118 vs 61-62).** `ok`, `surface`, `ink`, `ink-2`, `muted` look like tokens while tokens are a non-goal; only `surface` has a value.
4. **Major. BlobElastic description (lines 187-190).** "The gap", "the shown gap" and "the union's reach" are undefined.
5. **Major. Pad formula (line 247).** `n` and `k` undefined; what is padded is not said.
6. **Major. CUJ 8, "however late its removal comes" (line 145).** "Removal" and why it could be late are unexplained; `cutExits("Banner content")` (line 197) vs `cutExits(top)` (line 256) not reconciled.
7. **Nit.** Decision and task IDs without links; Q-003 and E3 not glossed.
8. **Nit. Line 30.** "Not a Launcher hides it" reads garbled; "proven disable flags" unexplained.
9. **Nit. Line 34.** "the three rules" paraphrased as one list.
10. **Nit.** Undefined names: the owner, draft 4 (no link), `SessionMedia`, `Message`, the shade, `ShapeWarmUp`, "the camera's slot", "+N others".
11. **Nit. Line 180.** `arrival` vs `arriving` not explained.
12. **Nit. Lines 288-292.** Proof stages and `emu01` not explained except `detailed`.
13. **Nit. Line 279.** Why the mode switch lives in `GestureNav`.
