# T-014: E2.14c-1, arrivals on the island: notifications (minimal) and charging, and the ranking

- **Step:** E2.14c · **Lead:** claude (solo by the owner's choice, D-116; Codex reviews on its return) · **Opened:**
  2026-11-13 · **Status:** IN PROGRESS
- **Read first:**
  - `docs/DEFAULT_LAYOUT_PLAN.md`: §2 "Sources" (the ranking) and §3 E2.14c.
  - Decisions: D-105 to D-107 and D-114 to D-116 (the island).
  - Code: `engine/src/main/assets/kdl/components/island.kdl`; `NotificationIcons.kt` (`notifications`); `SystemSources.kt`
    (`battery`); `app/.../shade/Notifs.kt` (`peeks`, `Item`); `Shade.kt` (`notifsChanged`, the "fresh" rule).
  - Figma draft 4: `Island` `state=charging` (8:195) and `state=notification` (8:203).

## 1. Proposal (lead)

**Goal.** The island shows an arriving notification and a plug-in. A higher-ranked live thing interrupts a lower one,
and the lower one returns after. The ranking is notification > charging > media > idle (calls, alarms and volume come
later). Notifications come in two modes, the user's choice:
- **minimal:** draft 4's dot, sender and +N, built here;
- **detailed:** a banner that grows out of the island and separates from it as a blob, which is E2.14c-2 with a design
  concept first.

**What draft 4 says.**
- *Charging:* 164 x 32. The time (JetBrains Mono 13, ink) sits left of the camera, and "64%" and "↯" in `ok` #7DBE93
  (gap 6) sit right of it.
- *Notification:* 200 x 32. A 6 dp terracotta dot, a 6 gap and the sender (IBM Plex Sans 13, ink) sit left; "+3" in
  `muted` #8A8A8A (JetBrains Mono 13) sits right.

**The owner's answers (2026-11-13).**
- Notifications: two modes, the user decides; the minimal one is the Figma state, for 4 s.
- Charging: a few seconds at plug-in.
- Locked: the sender's name is hidden (the app's name shows instead).
- Only notifications that alert (sound or vibration) arrive.

**Approach.**
1. **`notifications.arrival` (engine):** a record `{id, name, others}` or null, held for 4 s from its arrival. A newer
   arrival replaces it and restarts the time.
   - *Feed (app, `KdlArrivals`):* follows `Notifs` with the shade's "fresh" rule, widened to alerting (importance
     DEFAULT or more, not muted by Do Not Disturb, not ongoing, not a summary, with a title or text). `name` is the
     sender (the title), or the app's name while locked, read from `AppInfo`'s cache, never the PackageManager on a
     drawing thread. `others` counts the other alerting notifications present.
2. **`battery.plugged` (engine):** true for 3 s after charging starts (the plug-in moment), false otherwise.
3. **`island.kdl`:**
   - one state at a time, by rank; the width follows it (200, 164, 236 or 132 dp) on the same spring;
   - every state's pieces in fixed slots, staged as the media state is;
   - a `notifications` prop `mode` (`minimal` default; `detailed` falls back to minimal until E2.14c-2).
4. **Banners:** while the KDL island is on, a non-ringing notification arrives on the island instead of as a banner.
   Ringing ones (calls, alarms) keep their banner until the island carries them.

**Verification.**
- **JVM:** arrival timing (held 4 s, replaced and restarted, cleared); the alerting rule; the plug-in moment; the
  island's widths and slots per state; the ranking (a notification over charging over media, the lower returning).
- **Emulator:** the island proof gains the two states' geometry and frames through notification → media → idle with
  interruptions; a real `TEST_NOTIFY` arrival and `dumpsys battery` plug-in on the launcher.
- **S24:** the owner, with a real message and a real charge; Samsung's charging chip stays covered (StockBarCover),
  privacy dots still visible.

**Open.** "↯" is not in JetBrains Mono, so it falls back to a system font (judged on the phone). Detailed mode needs a
design concept (E2.14c-2).

## 2. Assumptions made while the owner is away (2026-11-13; to review)

The owner, before leaving for 8 hours: "keep working unless you are facing a major decision that requires my say.
otherwise you are free to assume. once I return, let me know about the assumptions you made".

1. **"Alerting":** importance DEFAULT or more (a sound or a vibration), not muted by Do Not Disturb, not ongoing, not a
   group summary, not a media notification, with a title or text. Wider than Android's heads-up rule (HIGH).
2. **"+N":** the other alerting notifications present now, not only those that came in the same burst. Hidden at 0.
3. **The name while locked:** the app's name (from the cache; empty if it was never read). Read once at arrival: a phone
   unlocked during the 4 s keeps the app's name until the next arrival.
4. **Banners:** while the KDL island is on, a non-ringing notification shows only on the island. Calls and alarms keep
   their old banner until the island carries them (E3 or a later 14 slice).
5. **Ranking:** notification > charging > media > idle, as the plan has it. A notification arriving while music plays
   replaces the music state for 4 s, then the music state comes back.
6. **Charging:** 3 s at plug-in, nothing at unplug (the owner's option mentioned "a short moment when it comes out";
   left out for now as a separate small choice). Full on the charger counts as charging.
7. **The bolt "↯":** drawn from the system's fallback font (JetBrains Mono has none). It renders on the emulator; judge
   it on the S24.
8. **The charging and notification states' motion** reuses the media state's choreography: the time out at once, pieces
   in a beat apart (70 / 110-120 / 150 ms), out in 90 ms, the time back after 110 ms.
9. **The slots:** charging 14 | 54 | 28 | 56 | 12 and notification 14 | 72 | 28 | 74 | 12, both centred on the camera.
   The sender is cut by a 10 dp fade at 60 dp, with no scroll (a 4 s glance).
10. **`mode` prop:** "minimal" (default) and "detailed". "detailed" shows as minimal until E2.14c-2. There is no setting
    to change it yet: settings come from package props later.

## 3. Reports

### claude — E2.14c-1, 2026-11-13

- **Engine:**
  - `battery.plugged` (3 s at plug-in, a timer injected, `SourcesTest.pluggedIsThePlugInMoment`);
  - `ArrivalTracker`, `notifications.arrival` and `arriving` (`ArrivalsTest`: start, 4 s, replace and restart, gone
    clears, alert-once, bounds);
  - a source path deeper than its member reads through the record (`SourcesTest.aDeeperPathReadsThroughTheRecord`:
    `notifications.arrival.name` read null before).
- **App:** `KdlArrivals` (Notifs to candidates; lock; the test channel), wired in `KdlBar`; `Shade` leaves non-ringing
  arrivals to the island.
- **`island.kdl` 0.3.0:** charging and notification states in fixed slots, by rank, staged. `IslandKdlTest`: the ranking
  and both states in dp.
- **Emulator, real launcher:** `TEST_NOTIFY` shows "Kasun +1" at 200 dp and returns after 4 s. `dumpsys battery`
  (unplugged, then AC) shows "100% ↯" in green at 164 dp, back after 3 s
  (`tools/shots/media/emu-arrivals.png`).
- **Not done:** frame-by-frame emulator proofs of the two new states (the mechanism is the media state's, proven);
  the S24 (Samsung's charging chip still covered, privacy dots, a real message).
