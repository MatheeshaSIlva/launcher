package dev.launcher.engine.ui

/**
 * Where a component placed on the camera (`place="cutout"`, T-010) puts its root, in its window's px: its horizontal centre
 * and its centre line. StatusBarView's rule (`centreLine`, `cameraLeft`), in the engine:
 * - horizontally on a top cutout's centre whatever its band; without one, the window's centre;
 * - its line on the cutout's centre when that lies strictly between 20 % and 80 % of the status bar's height (the bar's,
 *   not the window's), else at 52 % of it.
 * Worked out on the screen (the status band is the screen's), then given in the view's px: less the view's resting place
 * on screen ([originX], [originY]: never where a motion has it this frame).
 */
object CutoutPlacement {
    /** A top cutout's bounding rectangle in screen px. */
    data class Cutout(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val centreX get() = (left + right) / 2f
        val centreY get() = (top + bottom) / 2f
    }

    data class Place(val centreX: Float, val centreY: Float) {
        /** The screen's top edge in the view's px (less its resting place on screen); not part of the place itself. */
        var screenTop = 0f
            internal set

        /**
         * The root's top for a root whose centre line is [rootCentre] below its top, never nearer the screen's top than
         * [gap] px (`place-gap`, T-012): a camera high on the screen leaves the root lower, its centre below the camera's.
         * No gap (null): centred on the camera wherever that puts it (D-102).
         */
        fun top(rootCentre: Float, gap: Float?): Float =
            if (gap == null) centreY - rootCentre
            // The gap on a whole pixel of the screen (D-113): 4 dp at 2.625 is 10.5 px, which left the pill's top edge a
            // half-lit row and its text half a pixel off the pill's centre (the text is placed on whole pixels).
            else maxOf(centreY - rootCentre, screenTop + kotlin.math.floor(gap + 0.5f))
    }

    const val BAND_LOW = 0.2f
    const val BAND_HIGH = 0.8f
    const val FALLBACK_LINE = 0.52f

    /** [windowCentreX]: the window's horizontal centre on screen (the fallback's centre). */
    fun of(cutout: Cutout?, statusHeight: Float, originX: Float, originY: Float, windowCentreX: Float): Place {
        val x = cutout?.centreX ?: windowCentreX
        val cy = cutout?.centreY
        val y = if (cy != null && cy > BAND_LOW * statusHeight && cy < BAND_HIGH * statusHeight) cy else FALLBACK_LINE * statusHeight
        return Place(x - originX, y - originY).also { it.screenTop = 0f - originY }
    }
}
