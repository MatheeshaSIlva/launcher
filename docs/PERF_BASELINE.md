# Performance baseline (S24)

What the screen showed for each animation, per `docs/ROADMAP.md` M1 criterion 2. Numbers are from `tools/framestats.py`
(present times): **missed** = refreshes the screen repeated a frame. Raw outputs: `tools/shots/baseline/<sha>/` (git-ignored).

## Run 1: build 2438a9f, 2026-10-22 (partial)

- Phone: Galaxy S24 (SM-S921B), One UI 9 / Android 17. Build `2438a9f` (app code identical to main `1d1c3f8`: no app change since `b0fd6b2`).
- Conditions: on USB power (charging, then full); battery 32.2-33.0 °C; thermal status 0; CPU max frequencies uncapped
  (1.96 / 2.59 / 2.90 / 3.21 GHz) from 23:46 to 23:51.
- Shade layout switched from the owner's `pixel (together)` to `ios (split-top)` for the run (the scripts are written for
  iOS's panels, B-030), restored afterwards.
- **The launcher crashed during `scenario_shade.sh` run 3** (native abort in `BlurBaker.bake`, B-031). Android restarted it;
  run 3's `cc_open` has no frames because of it.
- Six of eight scripts stopped before measuring, safely (no wrong taps): stale icon templates and Spotlight's keyboard
  not coming up (B-032). Only `scenario_shade.sh` and `scenario_nc_scroll.sh` ran in full; `scenario_more.sh` and
  `scenario_sweep.sh` measured home's own motion before stopping.

### Missed refreshes per animation (3 runs unless noted)

| Script | Animation | Window | Missed per run | GPU p90 ms (range) |
|---|---|---|---|---|
| shade | Control Center open | LauncherStatusBar | 0, 0, (crash) | 2.7-3.3 |
| shade | Control Center close (tap) | LauncherStatusBar | 0, 0, 0 | 1.4-7.1 |
| shade | Control Center edit in | LauncherStatusBar | 0, 0, **1** | 4.2-9.6 |
| shade | Control Center edit out | LauncherStatusBar | 0, 0, 0 | 3.9-5.2 |
| shade | Control Center close (bar) | LauncherStatusBar | 0, 0, 0 | 7.7-14.2 |
| shade | Notification Center open | LauncherStatusBar | 0, **1**, 0 | 3.4-5.3 |
| shade | Notification Center close (bar) | LauncherStatusBar | 0, 0, 0 | 2.9-8.1 |
| shade | Banner in and out | LauncherStatusBar | 0, 0, 0 | 2.0-2.4 |
| nc_scroll | Notification Center open | LauncherStatusBar | 0, 0, 0 | 3.2-9.3 |
| nc_scroll | Stack fans out | LauncherStatusBar | **2, 1, 1** | 12.8-13.3 |
| nc_scroll | Scroll up | LauncherStatusBar | 0, 0, 0 | 8.6-13.7 |
| nc_scroll | Scroll down | LauncherStatusBar | **1, 1**, 0 | 8.1-12.6 |
| nc_scroll | Scroll up twice | LauncherStatusBar | **1, 1**, 0 | 8.4-12.4 |
| nc_scroll | Notification Center close | LauncherStatusBar | 0, 0, 0 | 3.6-8.0 |
| more (1 run) | Page to App Library | HomeActivity | **1** | 10.1 |
| more (1 run) | App Library scroll | HomeActivity | 0 | 13.7 |
| more (1 run) | App Library list open | HomeActivity | 0 | 9.9 |
| more (1 run) | App Library list close | HomeActivity / LauncherCards | 0 / 0 | 13.3 / 8.2 |
| sweep (1 run) | Page to App Library | HomeActivity / LauncherStatusBar | **1 / 1** | 10.3 / 6.0 |
| sweep (1 run) | App Library scroll | HomeActivity | 0 | 13.6 |
| sweep (1 run) | App Library to page (swipe) | HomeActivity / LauncherStatusBar | **1 / 1** | 10.0 / 5.7 |
| sweep (1 run) | App Library to page (bar) | HomeActivity / LauncherStatusBar / LauncherCards | **1 / 2** / 0 | 11.1 / 4.8 / 8.3 |

Not measured in this run: app launch and close (Spotlight, App Library, dock, folder), quick switch, App Switcher,
grabs and cancels, the settings. They wait for B-032.
