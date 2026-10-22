# Questions for the owner

Agents add questions here when the owner is unavailable or a question was not answered. The owner answers inline; agents then move the item to Answered and act on it. `/progress` lists open questions first, because they block work.

Format:
`- [Q-001] (blocks: M2 | not blocking) Question? Options: A (recommended), B. Context: why it matters.`

## Open
- [Q-001] (not blocking) Confirm the draft severities of B-006 to B-014 and add any bugs you see on the phone now. Context: M0 seeded the backlog from CLAUDE.md and lint.
- [Q-002] (not blocking) Should new lint warnings fail the gate too? Options: A (recommended) no, only new errors fail; reword M0's criterion to "only new errors fail"; B set `warningsAsErrors` (every new warning blocks a stop). Context: QA found B-016; the criterion's wording says "issues".

## Answered
- (none)
