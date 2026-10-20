package dev.launcher.app.shade.cc

import dev.launcher.app.shade.CcTokens

/**
 * The layout file's `status-row` block: the row Control Center keeps for the status bar's right group, which comes down
 * into it as the panel opens (the bar draws it: [dev.launcher.app.statusbar.StatusBarView]). iOS 27: its centre 104.2 pt
 * from the top upright; turned, one status line lined up with the grid (6 pt in from its side).
 */
class StatusRowBlock(private val panel: CcPanel) {
    /** The row's centre at rest (px). */
    var y = 0f
        private set

    fun syncTokens() {
        y = panel.host.surfaces.pt(if (panel.landscape) CcTokens.ROW_Y_LANDSCAPE else CcTokens.ROW_Y)
    }

    /** The row's centre now (px): pulled down with the controls. */
    val shownY: Float get() = y + panel.pullOffset

    /** How far in the row runs (px; 0: the bar's own sides): turned, lined up with the grid's side at [gridLeft]. */
    fun inset(gridLeft: Float): Float = if (panel.landscape) gridLeft + 6f * panel.u else 0f
}
