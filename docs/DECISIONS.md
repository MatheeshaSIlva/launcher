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
