# T-000: <title>

- **Step:** E? · **Lead:** claude · **Opened:** YYYY-MM-DD · **Status:** PROPOSED
- **Read first:** <the spec sections, decisions, inventory rows and files this task depends on>

## 1. Proposal (lead)

**Goal.** One or two sentences: what will be true when this is done.

**Approach.** The plan, concretely: which files, which order, which mechanism.

**Verification.** How each part will be checked (unit tests, dex diff, emulator, S24 frame stats, fidelity diff), and
the number that means "pass".

**Risks and open questions.** What could go wrong, what the lead is unsure of, what needs the owner.

## 2. Discussion

Alternate `### codex — <date>` and `### claude — <date>` entries, Codex first. Each entry: what you agree with, what you
would change and why (with evidence), what is missing. At most three rounds each (protocol §5).

## 3. Agreed approach

What was agreed, what changed from the proposal and why.

- `codex: AGREE approach — <one line>`

## 4. Split

| Sub-task | Owner | Files owned | Branch | Verified by |
| --- | --- | --- | --- | --- |
| | | | | |

## 5. Reports

### claude — report

- **Commits:**
- **What changed:**
- **Verified (measured / judged):**
- **Not verified:**
- **Surprises:**

### codex — report

(appended by `scripts/team/codex.sh … work`)

## 6. Cross-check

Each agent reviews the other's diff and report. Findings as `fix`, `question` or `note`, with file:line. The owner of a
finding replies inline when it is resolved. Security checklist (protocol §7) answered here when it applies.

## 7. Sign-off

- `claude: AGREE / OBJECT — <what was checked>`
- `codex: AGREE / OBJECT — <what was checked>`
- `owner: <for on-screen work: signed off on the S24, date>`
