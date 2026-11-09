package dev.launcher.app.statusbar

/**
 * How many notification icons fit before the camera: StatusBarView's left side as arithmetic (T-007). The KDL bar's window
 * passes the answer as `max-icons`. Points are StatusBarView's and the KDL's: the same geometry in all three places until
 * these become tokens (B-114). Room for the overflow dot is always kept, whether it shows or not, as StatusBarView keeps it.
 */
object BarFit {
    const val MAX_ICONS = 3
    private const val SIDE_PT = 34f
    private const val TIME_GAP_PT = 7f
    private const val MOON_PT = 12.4f
    private const val ICON_PT = 14.5f
    private const val ICON_GAP_PT = 4.5f
    private const val DOT_PT = 3.4f
    private const val CAMERA_CLEARANCE_PT = 8f

    fun count(cameraLeftPx: Float, timeWidthPx: Float, moon: Boolean, unitPx: Float): Int {
        val limit = cameraLeftPx - CAMERA_CLEARANCE_PT * unitPx
        var cursor = SIDE_PT * unitPx + timeWidthPx + TIME_GAP_PT * unitPx + if (moon) (MOON_PT + ICON_GAP_PT) * unitPx else 0f
        val icon = ICON_PT * unitPx
        var n = 0
        while (n < MAX_ICONS && cursor + icon <= limit - (DOT_PT + ICON_GAP_PT) * unitPx) {
            cursor += icon + ICON_GAP_PT * unitPx
            n++
        }
        return n
    }
}
