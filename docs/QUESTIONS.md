# Questions for the owner

Agents add questions here when the owner is unavailable or a question was not answered. The owner answers inline; agents then move the item to Answered and act on it. `/progress` lists open questions first, because they block work.

Format:
`- [Q-001] (blocks: M2 | not blocking) Question? Options: A (recommended), B. Context: why it matters.`

## Open
- [Q-003] (not blocking) On Android 10 only, a clipping box larger than about two-thirds of the screen (or a nest of
  them) cannot get its smooth rounded clip within the memory budget (8 MiB of mask layers at once). Options: A
  (recommended, built) it keeps the rounded clip with rough, aliased edges; B the component refuses to draw such a box
  on Android 10; C allow more memory there. Context: Android 11 and later are unaffected; Codex asked for your decision
  (T-011, D-103).

- [Q-005] (blocks: the island's detailed notifications, T-015) Which motion for the banner that grows out of the island
  as a blob? Options (Figma, page "Concepts: detailed notification", node 55:167, four keyframes each): A "Drop" (a drop
  swells under the island, stretches on a neck, falls away as the banner); B "Grow, then pinch" (the island grows into
  the banner, then pinches its top back off); C "Bead" (a bead buds off and widens into the banner); or a mix. The
  engine part (`blob`, two shapes joined by a liquid neck) is built and proven; the island's state waits for this.
- [Q-006] (not blocking) A short moment on the island when the charger comes out too? Options: A (recommended) no, only
  at plug-in, as built; B ~1.5 s showing the level, without the bolt. Context: your choice mentioned "a short moment
  when it comes out" (T-014, assumption 6).
- [Q-007] (not blocking) Review the assumptions made while you were away (2026-11-13), listed in each thread's
  "Assumptions" section: T-014 (notifications and charging: 10), T-015 (detailed concepts: 4), T-016 (decode text: 3),
  T-017 (full-screen apps: 2).

## Answered
- [Q-004] (blocks: E2.14a) Draft 4's island at rest shows the time and battery in a monospaced typeface; the app only
  bundles Inter. Which font should the island (and draft 4's clock and labels) use? Options: A name the font your Figma
  draft uses, and we bundle it if its licence allows (OFL or Apache); B use Android's built-in monospace for now and
  match it later. Context: a new font file is a new asset with a licence, so it needs your yes (AGENTS.md).
  Answer (2026-11-12): A, "Name the Figma font". Read from Figma by the lead: the island at rest (component 8:175)
  sets the time and battery in JetBrains Mono Regular, 13 px on a 20 px line; the live media state's title is IBM Plex
  Sans Regular, 13 px on 18 px. Both are SIL OFL 1.1: JetBrains Mono is bundled for E2.14a, IBM Plex Sans when a state
  that uses it is built; both go on the licences page.
- [Q-001] Confirm the draft severities of B-006 to B-014 and add any bugs you see on the phone now. Answer (2026-10-24):
  "the bugs I noticed are mentioned above": the Pixel shade's crashes (B-031), expanded notifications (B-034), the
  notification tap highlight (B-035), the tiles' state animations (B-036), iOS's glass pull-down (B-037). The draft
  severities stand.
- [Q-002] Should new lint warnings fail the gate too? Answer (2026-10-24): "just the errors are enough" (option A). M0's
  criterion reworded, B-016 closed as intended (D-023).
