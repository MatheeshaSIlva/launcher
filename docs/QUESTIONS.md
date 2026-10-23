# Questions for the owner

Agents add questions here when the owner is unavailable or a question was not answered. The owner answers inline; agents then move the item to Answered and act on it. `/progress` lists open questions first, because they block work.

Format:
`- [Q-001] (blocks: M2 | not blocking) Question? Options: A (recommended), B. Context: why it matters.`

## Open
- (none)

## Answered
- [Q-001] Confirm the draft severities of B-006 to B-014 and add any bugs you see on the phone now. Answer (2026-10-24):
  "the bugs I noticed are mentioned above": the Pixel shade's crashes (B-031), expanded notifications (B-034), the
  notification tap highlight (B-035), the tiles' state animations (B-036), iOS's glass pull-down (B-037). The draft
  severities stand.
- [Q-002] Should new lint warnings fail the gate too? Answer (2026-10-24): "just the errors are enough" (option A). M0's
  criterion reworded, B-016 closed as intended (D-023).
