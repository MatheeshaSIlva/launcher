package dev.launcher.engine.ui

/**
 * Where a component placed on the camera (`place="cutout"`, T-010) puts its root, in its window's px: its horizontal centre
 * and its centre line. StatusBarView's rule (`centreLine`, `cameraLeft`), in the engine:
 * - horizontally on a top cutout's centre whatever its band; without one, the window's centre;
 * - its line on the cutout's centre when that lies strictly between 20 % and 80 % of the status bar's height (the bar's,
 *   not the window's), else at 52 % of it.
 * Screen coordinates become the window's by the view's resting place on screen ([originX], [originY]: its place less its
 * own translation, never where a motion has it this frame).
 */
object CutoutPlacement {
    /** A top cutout's bounding rectangle in screen px. */
    data class Cutout(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val centreX get() = (left + right) / 2f
        val centreY get() = (top + bottom) / 2f
    }

    data class Place(val centreX: Float, val centreY: Float)

    const val BAND_LOW = 0.2f
    const val BAND_HIGH = 0.8f
    const val FALLBACK_LINE = 0.52f

    fun of(cutout: Cutout?, statusHeight: Float, originX: Float, originY: Float, windowWidth: Float): Place {
        val x = if (cutout != null) cutout.centreX - originX else windowWidth / 2f
        val cy = cutout?.let { it.centreY - originY }
        val y = if (cy != null && cy > BAND_LOW * statusHeight && cy < BAND_HIGH * statusHeight) cy else FALLBACK_LINE * statusHeight
        return Place(x, y)
    }
}
