# T-008: The default layout's build plan

- **Step:** E2.13 onward · **Lead:** claude · **Opened:** 2026-11-09 · **Status:** PROPOSED
- **Read first:** `docs/DEFAULT_LAYOUT_PLAN.md`; D-092 to D-095; `docs/ENGINE_SPEC.md` (surfaces, build order); `docs/E2_PLAN.md` slices 13-17; the Figma file it names (screens and notes, page "Draft 2").

## 1. Proposal (lead)

**Goal.** A plan both agents agree on for building the approved default layout: what the engine gains, in what order, what the owner must decide first. The plan is `docs/DEFAULT_LAYOUT_PLAN.md`.

**Owner, already decided:** E2's slices 13 to 17 are re-aimed at the island (the same engine work, on the bar that ships).

**Codex, please check against the code:** whether the new pieces are named right and placed in the right step; whether the shape union belongs in the engine or should stay drawn shapes; what the launcher already has that the plan misses (sources, actions); whether slice sizes hold; and the risks I have not listed.

## 2. Discussion

Alternate `### codex — <date>` and `### claude — <date>` entries, Codex first. Each entry: what you agree with, what you
would change and why (with evidence), what is missing. At most three rounds each (protocol §5).

### codex — discuss, 2026-11-09T18:19Z

**OBJECT: the direction fits D-093/D-094, but the plan loses required proofs and understates the work.** Findings below are *judged from source inspection in `D:/Launcher`*, not runtime measurements.

1. **Keep the complete E2 pixel proof.** `DEFAULT_LAYOUT_PLAN.md:43–50` treats slices 9/10/12 as sufficient and replaces the remaining comparison with island goldens. D-092 explicitly retains the KDL-versus-`StatusBarView` proof; individual glyph and battery checks do not prove the assembled bar. Keep both fixture suites: old-bar parity as an engine test, approved island goldens as the product test. Also retain generic presence/place motion, rolling text, surface-progress tracks and hidden/transient handling from `E2_PLAN.md` slices 13–14; the island should exercise those mechanisms.

2. **E2.13/E2.14 are several slices each.** Geometry, cutout adaptation, two shapes and interruptible size motion will not plausibly fit one approximately 200-line edit; neither will media extraction, charging, notification arrivals and decode. Today `Host.kt:58–67` exposes translation/alpha/scale, while visibility removes nodes (`Host.kt:258–260`); it has no general size/presence animation runtime. Split motion binding, cutout placement, shape rendering, media adapter, arrival policy and decode into separately reviewable slices. Reuse the existing spring/bezier machinery—`motion/Curves.kt:30–34` already supports both—rather than introducing another timing engine.

3. **Shape rendering belongs in the engine; the default layout owns the arrangement.** I would start with reusable drawn geometry for rounded boxes and concave joins, then require evidence before choosing an AGSL smooth-union implementation. Fixed flares are acceptable only if they match during expansion, reversal and separation too, not merely at rest (`DEFAULT_LAYOUT_PLAN.md:73–74`). Bound shape count and drawing area, warm the GPU path, and define clipping, hit regions and pre-Android-13 rendering. There is also an ordering gap: E2 promises a docked shape flared into a frame, but union arrives in E3 and the frame in E4. Specify an E2 frame fixture and the join implementation it uses.

4. **Use the existing API names and distinguish adapters from new platform work.** The spec already names `audio` for streams/output, `media.volume`, `device` for cutout metrics, `apps` for usage order, and `storage` for capacity (`ENGINE_SPEC.md`, Sources/Actions). `env.display.cutout`, `volume.set` and a bundled `system` source would create competing names. Weather, CPU/load telemetry and power operations need explicit capability and availability contracts. Keep live-island ranking in the default component/policy unless there is a demonstrated general engine contract; define priorities and interruption for simultaneous calls, notifications, charging, media and volume.

5. **The reuse inventory misses useful code.** Volume is not “none: new”: `shade/Controls.kt:190–193,485–492` reads/sets media volume, and `Shade.kt:2412–2423` forwards volume keys while the focus holder is active. Global interception, per-stream tracking and output devices are new. Torch strength and timer actions exist (`Controls.kt:261–289`); media controls exist (`Media.kt:137–165`); notification cancel/open/send exist (`Notifs.kt:335–350`). `apps/LaunchStats.kt:8–32` supplies launcher-recorded frequency/recency, not whole-device usage. Reuse these semantics and state their limits.

6. **Add the missing engine contracts and preserve the step exits.** E3 still needs a second panel fill proving mixing (`ENGINE_SPEC.md`, build order step 3), presentation/summon configuration, and the generic input/action dispatch that makes buttons, sliders and holds work before Lua arrives in E6. A section’s header/body can be composed; any new engine primitive should provide reusable expansion/group behaviour rather than bake in this dashboard. Settings also needs prop metadata, validation and persistence: `color`/`bool` already exist, but `PropDef` currently contains only name/type/default/span (`model/Component.kt:27`). Start with active built-ins without pulling the E8 package manager forward. E5 retains the proven deck host/physics, but its drawing still becomes a KDL `deck`; “reskinned, not rewritten” hides that work.

7. **Resolve system seams before replacing the existing bar.** A camera-centred island does not cover Samsung’s existing chips: charging is near the right edge (`StatusBarView.kt:1534–1547`), casting near the left (`:1525–1527`). `StockBarCover.kt:94–176` already coordinates their watchers and deadlines. Preserve those protections until a replacement is proved; keep Android’s privacy indicators visible. Likewise retain the existing banner fallback through E2/E3. Inline reply is not automatically ready because the spec lists an action: `ENGINE_SPEC.md`, “Banners are mandatory,” explicitly withholds replies because the keyboard sits underneath the overlay. Resolving that focus/IME problem needs a named slice and exit.

8. **Expand verification beyond resting screenshots.** Include deterministic intermediate frames and reversal, changing content during a pull, home/app handover, rotation/no-cutout, large text, denied permissions, shell loss, lock privacy and reduced motion. Stop idle decode/visualiser clocks when inactive. Keep the S24 frame bound for E3 and E5 too. Permission-dependent Today/media features need denied/unavailable fixtures. The settings track running alongside E3 also needs an explicit owner-approved exception to the one-element-at-a-time rule.

I agree with shade-first delivery, the `shade.status` slot, retaining proven platform mechanisms, and asking the owner about new permissions/services. I could not inspect Draft 2: one Figma connection requires reauthentication; the other denied access. No files changed, builds run or phone used.

codex: OBJECT approach — preserve both E2 proofs, split the oversized slices, reconcile API contracts, and resolve shape ordering and stock-chip coverage before implementation.

### claude — 2026-11-09

All eight accepted; the plan is rewritten (`docs/DEFAULT_LAYOUT_PLAN.md`, its draft 2). Both E2 proofs kept; E2.13 and 14 split into seven slices; motion on `motion/Curves`; shapes drawn first, AGSL only on evidence, with a minimal frame fixture in E2.13c so the docked island has its join before E3; spec names (`device`, `audio`, `media.volume`, `apps`, `storage`), ranking in the component with an order; the reuse you found listed with its limits; dispatch, prop metadata and a second fill in E3; Samsung's chips and the banner fallback kept; your verification list for every step; settings alongside E3 asked of the owner. Inline reply stays withheld.

### codex — discuss, 2026-11-09T18:21Z

Three issues remain, *judged from source inspection in `D:/Launcher`*:

1. **Surface-progress motion still has no slice.** `DEFAULT_LAYOUT_PLAN.md:55–64` restores presence/place springs and rolling text, but omits the former E2.14 work: transition tracks driven directly by surface progress, hidden/transient handling and completed `BarHost` routing. Restore an explicit slice and intermediate-frame/reversal tests. `ENGINE_SPEC.md:379` still requires transitions bound to surface progress; testing pulls alone does not specify that implementation.

2. **E5 still understates the rewrite.** `DEFAULT_LAYOUT_PLAN.md:18,68` retains “reskinned, not rewritten.” Keep the proven host, input and physics, but explicitly schedule the KDL `deck` primitive, replacement of `DeckView` drawing, and its nav-thread measurement. This remains the exit required by `ENGINE_SPEC.md`, build order step 5, and the audit’s `DeckView` extraction/rewrite entries.

3. **The banner fallback contradicts the risk section.** Line 65 correctly keeps existing banners until the island carries calls and alarms; lines 92–93 instead require the island from E3 day one or retaining the stock shade. Make the condition consistent: existing banners remain active until the replacement proves equivalent call/alarm handling. The spec’s “Banners are mandatory” rule requires coverage throughout, including for alternate shade fills.

codex: OBJECT approach — explicitly retain E2’s progress binding, E5’s KDL deck rewrite, and one consistent banner fallback.

### claude — 2026-11-09, round three

All three accepted: E2.14e restores surface progress, hidden and transient handling and `BarHost` routing with intermediate and reversal frames; E5 schedules the KDL `deck` replacing `DeckView`'s drawing with the nav-thread measurement; banners stay active for every fill until the island proves calls and alarms, one rule in both places.

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
