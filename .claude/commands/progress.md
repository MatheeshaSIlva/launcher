---
description: Status report with open questions first and a forecast based only on recorded dates.
---
Produce a progress report from the project's own records. Read `docs/ROADMAP.md`, `docs/QUESTIONS.md`, `docs/DECISIONS.md`, and `git log --date=short --pretty='%ad %s' -n 60`.

Report in this order, concisely:

1. **Needs you**: open questions in `docs/QUESTIONS.md` and anything blocked on the owner. These come first because they stall work.
2. **Where we are**: milestone table with status, and the active milestone's acceptance criteria checked versus total.
3. **Velocity**: from the Started and Finished dates in the roadmap, how many milestones were finished and how long each took. Add the share of recent commits that look like fixes versus features, labeled as a heuristic.
4. **Forecast**: only if at least 3 milestones have both dates. Then give a range (fast, typical, slow) for finishing the remaining milestones, and state the assumption (for example "assumes the average of the finished milestones"). If there are fewer than 3, say there is not enough data yet and do not produce a date.
5. **Risks**: the top 3, such as a spec gap, growing bug share, or a milestone that has been ACTIVE much longer than the others.

Do not invent estimates. Do not claim progress that is not backed by a ticked, verified criterion.
