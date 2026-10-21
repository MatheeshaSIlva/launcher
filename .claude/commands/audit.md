---
description: Run an independent drift audit of the whole project against the spec and roadmap.
argument-hint: [optional focus area]
---
Run a drift audit. Focus (may be empty): $ARGUMENTS

1. Delegate to the `drift-auditor` agent. Tell it the focus area if one was given, and the paths of the docs. Do not summarize the project for it; it should form its own view.
2. Save the full report to `docs/audits/AUDIT-<today's date>.md` (create the folder if needed; get the date with `date +%F`).
3. Tell the owner, in 12 lines or fewer: the overall verdict, the top findings (especially unrequested work and spec rot), and the recommendations. Quote the auditor's verdict as given.
4. Use AskUserQuestion to let the owner pick which recommendations to adopt. For adopted ones, add them to the roadmap Bug backlog or Parking lot as appropriate. Do not add or reorder milestones without the owner's explicit approval.
5. Put any `NEEDS_HUMAN` items to the owner now, or in `docs/QUESTIONS.md` if they are not available.
