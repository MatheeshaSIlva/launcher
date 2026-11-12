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
