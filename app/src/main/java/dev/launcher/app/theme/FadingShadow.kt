package dev.launcher.app.theme

import android.graphics.Paint
import kotlin.math.roundToInt

/**
 * A soft shadow under text or a glyph that fades with it. Android draws the shadow of a translucent shadow colour at that
 * colour's own alpha, whatever the paint's alpha: text faded through its paint kept its full shadow until it stopped being
 * drawn (Spotlight's names, a closing folder's labels and the App Switcher's titles lingered as dark smudges). Call [apply]
 * right before each draw, after the paint's colour and alpha are set: the shadow is [color]'s alpha times the paint's alpha
 * times [strength]. One instance per paint.
 */
class FadingShadow(var radius: Float, var dx: Float, var dy: Float, var color: Int) {
    private var appliedColor = Int.MIN_VALUE
    private var appliedRadius = Float.NaN

    fun apply(p: Paint, strength: Float = 1f) {
        val a = (((color ushr 24) and 0xFF) * (p.alpha / 255f) * strength.coerceIn(0f, 1f)).roundToInt()
        val c = (a shl 24) or (color and 0xFFFFFF)
        if (c == appliedColor && radius == appliedRadius) return
        appliedColor = c
        appliedRadius = radius
        if (a <= 0) p.clearShadowLayer() else p.setShadowLayer(radius, dx, dy, c)
    }
}
