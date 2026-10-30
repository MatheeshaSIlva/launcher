---
description: One-time setup. Adopt this existing project into the governed workflow by writing SPEC and ROADMAP together with the owner.
argument-hint: [optional one-line description of the app]
---
You are running the kickoff for an app that is already partly built. The goal is to capture the owner's original direction in writing so everything after this can be checked against it. Do not write product code in this command.

Owner's note (may be empty): $ARGUMENTS

Steps:

1. **Survey the repo.** Use an Explore agent (or look yourself) to map the stack, structure, which features exist, how it is built and run, what tests and lint exist, and what the git history says about how the work has gone. Keep your own context small; you only need conclusions.
2. **Show the owner a short summary** of what you found: what the app currently does, the stack, and the state of tests and checks. Flag anything that looks like it was built but may not have been intended.
3. **Interview the owner** with AskUserQuestion, up to 4 questions per call and at most 2 rounds, to capture: what the product is and who it is for, the 3 to 5 core journeys, explicit non-goals, hard constraints, what "polished v1" means, which existing features are on-direction versus experiments, and what feels most off right now. Offer concrete options with a recommendation, and rely on the free-text "Other" for anything open-ended.
4. **Draft `docs/SPEC.md`** (one page) and **`docs/ROADMAP.md`**:
   - M0 first: make `scripts/check.sh` meaningful for this stack (build, lint or type check, tests) and baseline the current code. Record existing known bugs in the Bug backlog.
   - Then milestones to v1, each with 3 to 6 testable acceptance criteria, sized for a few days of work at most.
   - Put anything that came up but is not clearly in scope into the Parking lot.
5. **Show both drafts and ask the owner to approve or edit.** Only after approval, change the SPEC status line to LOCKED and tell the owner the next step is `/ship M0`.

Do not invent requirements the owner did not state. Where you had to assume, mark it `ASSUMPTION` in the draft so the owner can confirm or strike it.
