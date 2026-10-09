package dev.launcher.app.home

import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.motion.SpringValue
import dev.launcher.app.theme.Appearance

/**
 * A label on glass straight on the wallpaper (Edit and Done, the Search pill): white or dark for what is behind it
 * ([Appearance.glassLabelTone]), moving to a new tone on a spring (a new wallpaper, the appearance changing) instead of
 * snapping. [redraw] draws the label again (each frame of the spring).
 */
class GlassLabelTone(redraw: () -> Unit) {
    private val tone = SpringValue(0f, 100f, { redraw() })
    private var known = false

    /** The colour for a wallpaper of luminance [lum] behind the label; [snap]: no spring (the label is not shown yet). */
    fun color(lum: Float, snap: Boolean = false): Int {
        val t = Appearance.glassLabelTone(lum, if (known) tone.target else Float.NaN)
        if (!known || snap) { tone.snapTo(t); known = true }
        else if (kotlin.math.abs(tone.target - t) > 0.02f) tone.animateTo(t, Motion.role(MotionTokens.HOME_LABEL_TONE))
        return Appearance.glassLabelColor(tone.value)
    }
}
