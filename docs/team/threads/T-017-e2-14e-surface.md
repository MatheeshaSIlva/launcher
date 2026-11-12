# T-017: E2.14e, the island with the system: full-screen apps, the transient show, the pull

- **Step:** E2.14e · **Lead:** claude (solo, D-116; Codex reviews on its return) · **Opened:** 2026-11-13 · **Status:**
  part 1 DONE (hidden by an app, the transient show); the pull and BarHost routing wait for E3's panel
- **Read first:** `docs/DEFAULT_LAYOUT_PLAN.md` §3 E2.14e; `app/.../statusbar/StatusBarView.kt` (`setHiddenByApp`,
  `showTransient`, `sync`); `docs/STATUS_BAR.md`.

## 1. Part 1: the island comes and goes with the bar

**Found:** `StatusBarView` fades itself when the app in front goes full screen. The KDL island is a separate view in the
shade's window and never heard of it, so it would have stayed over a full-screen video or game.

**Built:**
- `StatusBarView.onShown` is told at every change of whether the bar shows (the full-screen app, the transient show
  after a swipe from the top, a panel open, home turned).
- `KdlBar` sets the island's new prop `shown` from it.
- `island.kdl` 0.4.0's root: `visible="{@shown}"`, `enter="fade scale spring(0.4, 0.8)"`, `exit="fade scale 160ms"`.

**Measured (emulator, the real launcher):** `BarsTestActivity` with the bar shown, the island over it; with `bars
status` (full screen), "[statusbar] hidden" and no island.

**Not verified on the emulator:** the transient show. The emulator's SystemUI shows its own bar transiently over an
immersive app (it ignores the flags that hide its content), and while it shows ours waits for it (the existing
handover), so the island stays away there. On One UI those flags hold, so the S24 is the check: a swipe from the top
over a full-screen video should bring the island for a moment.

## 2. Waiting

- **The island following a pull and `BarHost` routing the shade's calls to the KDL component:** these are the panel
  growing out of the island (E3). The pull's progress has nothing to drive until the panel exists.

## 3. Assumptions (owner away; to review)

1. **The motion:** leaving, a quick fade and a slight shrink (160 ms); coming back, a soft spring (0.4, 0.8). This is
   the island's own presence; the stock bar's hide and show roles are not used for it.
2. **Panels:** when a panel is open the bar shows whatever the app asks (as before); the island follows the same
   rule.
