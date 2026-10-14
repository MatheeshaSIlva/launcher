package dev.launcher.app.home

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Home's depth blur, as the picture of home behind cards draws it (GestureNav's PreviewView) and as home draws itself when
 * it shows in the middle of a flight (a drag during a close takes over from the picture). The picture blurs a quarter-size
 * copy and scales it up, fading the copy in over its first [MIN_LOW_BLUR_DP]: at the same radius that looks about twice
 * as blurred as a full-size blur, and home turned visibly sharper where it took over. [homeRadius] is the full-size radius
 * that looks as the picture does.
 */
object DepthBlur {
    /** Below this blur (dp, at full size) the picture fades its blurred copy, blurred at this radius, in over the sharp one. */
    const val MIN_LOW_BLUR_DP = 3f
    /** The size of the picture's blurred copy. */
    const val LOW_SCALE = 0.25f

    // Android's blur radius as a Gaussian's sigma, and back (HWUI: sigma = 0.57735 x radius + 0.5).
    private fun sigma(r: Float) = 0.57735f * r + 0.5f
    private fun radius(sigma: Float) = max(0f, (sigma - 0.5f) / 0.57735f)

    /** The full-size blur radius (px) that looks like the picture's at [r] (px; [density]: px per dp). */
    fun homeRadius(r: Float, density: Float): Float {
        if (r <= 0f) return 0f
        val m = MIN_LOW_BLUR_DP * density
        val rr = max(r, m)
        // The copy's blur at its own size, seen at full size, and the softness of drawing it at a quarter and scaling it up
        // (an area average down, a bilinear tent up: about 4 px² of variance at full size).
        val s = sigma(rr * LOW_SCALE) / LOW_SCALE
        val look = sqrt(s * s + 1f / (LOW_SCALE * LOW_SCALE) / 4f)
        // Under its first few pixels the picture fades the copy in: about that share of its blur.
        return radius(look) * (r / m).coerceIn(0f, 1f)
    }
}
