# Decision log

Append-only, newest at the bottom. Log any choice a future reader would otherwise have to rediscover: architecture, libraries, data model, trade-offs. Owner decisions are tagged `[owner]`, agent decisions `[agent]`.

Template:

```
### D-000: Title (YYYY-MM-DD) [owner|agent]
Context: what forced the choice
Decision: what we chose
Alternatives considered: what we did not choose, and why
Consequences / revisit when: what this commits us to
```

---

### D-001: Adopt the governed workflow (2026-10-22) [owner]
Context: Iterations were producing more bugs than they were worth, and it was unclear whether the build was still following the original direction.
Decision: All work goes through architect, implementer, check gate, reviewer and QA. `docs/SPEC.md` is the locked direction and `docs/ROADMAP.md` defines done.
Alternatives considered: Continuing with a single agent plus owner review only.
Consequences / revisit when: Revisit if the pipeline overhead is larger than the bugs it prevents. `/progress` and `/audit` should show this.

### D-002: v1 direction set at kickoff (2026-10-22) [owner]
Context: Kickoff of the governed workflow; the direction had to be written down to check work against.
Decision: v1 is for themers and tinkerers; all four journeys (home and app open/close, App Switcher and gestures, shade everywhere with iOS 27 and Pixel layouts, customise look and motion with a full in-app theme builder) are v1. Ship bar: onboarding without adb, 0 missed refreshes on the S24 scenarios, polish pass on every screen, zero P0/P1. After v1: theme gallery and sharing, other phones, a One UI shade layout, Play Store and billing. Local builds and checks move to the Linux machine (Android SDK installed here; CI stays).
Alternatives considered: iOS-feel-for-Galaxy-owners as the primary audience; parking the Pixel shade until after v1.
Consequences / revisit when: Pixel shade and the theme builder are v1 work. Revisit scope if M3/M4 prove much larger than a few days each.

### D-003: Development stays on Windows (2026-10-22) [owner]
Context: Kickoff ran on a Linux machine with no Android SDK; D-002 had moved local builds there.
Decision: Work continues on the Windows dev machine (JDK 17, Git Bash, the existing SDK and emulator). This supersedes D-002's line about the Linux machine.
Alternatives considered: Installing the Android SDK on Linux.
Consequences / revisit when: M0 targets Windows; check.sh must run under Git Bash.

### D-004: M0 built on origin/main (2026-10-22) [owner]
Context: Local main had split from origin/main and carried a checkpoint commit that turned 206 files to CRLF.
Decision: The branch was rebuilt on origin/main (2951e09) with the checkpoint's 3 real changes, the kit and the kickoff re-applied as LF. Old local commits are kept on branch ship/m0-old.
Alternatives considered: Merging the old local main.
Consequences / revisit when: Local main still points at the old commits until fast-forwarded.

### D-005: Worktrees removed (2026-10-22) [owner]
Context: Four stale .claude/worktrees existed.
Decision: All four worktrees were removed and their claude/* branches deleted; their tools/shots were discarded.
Alternatives considered: Keeping them.
Consequences / revisit when: None.

### D-006: Operating rules in their own file (2026-10-22) [owner]
Context: The kit's rules would have replaced the project's CLAUDE.md.
Decision: They live in docs/OPERATING_RULES.md, imported from CLAUDE.md with one line.
Alternatives considered: Merging them into CLAUDE.md.
Consequences / revisit when: Revisit if the import stops loading.

### D-007: Pushing (2026-10-22) [owner]
Context: The kit said never to push; the owner wants the orchestrator to ship.
Decision: The orchestrator pushes to main only after check, review and QA pass and the owner says go for that task.
Alternatives considered: Owner always merges.
Consequences / revisit when: Replaces the kit's "do not push or merge" rule.

### D-008: What the gate runs (2026-10-22) [agent]
Context: `./gradlew check` also runs release lint and tests, doubling the time for nothing shipped.
Decision: The gate runs `assembleDebug lintDebug testDebugUnitTest` with `--continue`, debug variant only.
Alternatives considered: `./gradlew check`.
Consequences / revisit when: Revisit if release-only problems appear.

### D-009: Gate selects JDK 17 itself (2026-10-22) [agent]
Context: Gradle 8.9 cannot run on JDK 25 and the shell may inherit any JAVA_HOME.
Decision: check.local.sh always selects JDK 17 itself (JAVA_HOME_17, else the Adoptium path) and refuses another version.
Alternatives considered: Trusting the inherited JAVA_HOME.
Consequences / revisit when: Revisit when Gradle is upgraded.

### D-010: Lint baseline (2026-10-22) [agent]
Context: lintDebug failed with 68 errors (58 NewApi, 9 UnspecifiedRegisterReceiverFlag, 1 MissingPermission) and 206 warnings.
Decision: Baseline at app/lint-baseline.xml; severities and abort settings unchanged, so only new errors fail. NewApi errors stay baselined until other phones are in scope (after v1).
Alternatives considered: Fixing them now (out of scope for M0); relaxing lint.
Consequences / revisit when: Tracked as B-009, B-010.

### D-011: Line endings and exec bits in git (2026-10-22) [agent]
Context: CRLF scripts and mode-644 gate scripts broke Git Bash and Linux checkouts.
Decision: `* text=auto` in .gitattributes and the executable bit in the index for the gate scripts, so they do not depend on each machine's git config.
Alternatives considered: Per-machine core.autocrlf.
Consequences / revisit when: None.

### D-012: stop-check.sh kept as written (2026-10-22) [agent]
Context: The kit's Stop hook parses `git status` paths with awk.
Decision: Kept as the kit wrote it; it works under Git Bash and its path-parsing quirk errs toward running the check.
Alternatives considered: Rewriting it.
Consequences / revisit when: Revisit if it skips the gate wrongly.

### D-013: check.local.sh may use an inherited JDK 17 as a last resort (2026-10-22) [agent]
Context: Review of M0: a Linux or other checkout without the Adoptium path would always fail the gate, even with a JDK 17 in JAVA_HOME.
Decision: Amends D-009. Order is JAVA_HOME_17, then the Adoptium path, then an inherited JAVA_HOME only if it really is Java 17 (the `version "` line, so JAVA_TOOL_OPTIONS noise does not matter). Any other version is refused.
Alternatives considered: Windows-only selection (D-009 as written).
Consequences / revisit when: Revisit if Gradle moves past 8.9 and JDK 17 is no longer required.
