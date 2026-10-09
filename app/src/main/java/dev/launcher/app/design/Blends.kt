package dev.launcher.app.design

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The blend modes of [Blend] on the CPU, exactly as the glass shader's `blendOf` (MaterialPainter): for working out what a
 * material does to a backdrop outside the shader (the tint the system lays over its own live blur).
 */
object Blends {
    /** [s] laid on [b] (RGB 0..1) with [mode], at full strength. */
    fun blend(b: FloatArray, s: FloatArray, mode: Blend): FloatArray = when (mode) {
        Blend.NORMAL -> s.copyOf()
        Blend.MULTIPLY -> FloatArray(3) { b[it] * s[it] }
        Blend.SCREEN -> FloatArray(3) { b[it] + s[it] - b[it] * s[it] }
        Blend.OVERLAY -> FloatArray(3) { ov(b[it], s[it]) }
        Blend.DARKEN -> FloatArray(3) { min(b[it], s[it]) }
        Blend.LIGHTEN -> FloatArray(3) { max(b[it], s[it]) }
        Blend.COLOR_DODGE -> FloatArray(3) { if (b[it] <= 0f) 0f else if (s[it] >= 1f) 1f else min(1f, b[it] / (1f - s[it])) }
        Blend.COLOR_BURN -> FloatArray(3) { if (b[it] >= 1f) 1f else if (s[it] <= 0f) 0f else 1f - min(1f, (1f - b[it]) / s[it]) }
        Blend.LINEAR_DODGE -> FloatArray(3) { min(b[it] + s[it], 1f) }
        Blend.LINEAR_BURN -> FloatArray(3) { max(b[it] + s[it] - 1f, 0f) }
        Blend.HARD_LIGHT -> FloatArray(3) { ov(s[it], b[it]) }
        Blend.SOFT_LIGHT -> FloatArray(3) { soft(b[it], s[it]) }
        Blend.LUMINOSITY -> setLum(b, lum(s))
        Blend.COLOR -> setLum(s, lum(b))
        Blend.HUE -> setLum(setSat(s, sat(b)), lum(b))
        Blend.SATURATION -> setLum(setSat(b, sat(s)), lum(b))
    }

    // The shader's overlayOf(x, y), one channel (it branches on x): Overlay is ov(b, s), Hard Light ov(s, b).
    private fun ov(x: Float, y: Float) = if (x < 0.5f) 2f * x * y else 1f - 2f * (1f - x) * (1f - y)

    private fun soft(b: Float, s: Float): Float {
        if (s <= 0.5f) return b - (1f - 2f * s) * b * (1f - b)
        val d = if (b <= 0.25f) ((16f * b - 12f) * b + 4f) * b else sqrt(b)
        return b + (2f * s - 1f) * (d - b)
    }

    private fun lum(c: FloatArray) = 0.3f * c[0] + 0.59f * c[1] + 0.11f * c[2]
    private fun sat(c: FloatArray) = max(max(c[0], c[1]), c[2]) - min(min(c[0], c[1]), c[2])

    private fun clip(c: FloatArray): FloatArray {
        val l = lum(c)
        val n = min(min(c[0], c[1]), c[2])
        val x = max(max(c[0], c[1]), c[2])
        var r = c
        if (n < 0f) r = FloatArray(3) { l + (r[it] - l) * l / (l - n + 0.0001f) }
        if (x > 1f) r = FloatArray(3) { l + (r[it] - l) * (1f - l) / (x - l + 0.0001f) }
        return r
    }

    private fun setLum(c: FloatArray, l: Float): FloatArray { val d = l - lum(c); return clip(FloatArray(3) { c[it] + d }) }

    private fun setSat(c: FloatArray, s: Float): FloatArray {
        val mx = max(max(c[0], c[1]), c[2])
        val mn = min(min(c[0], c[1]), c[2])
        if (mx <= mn) return FloatArray(3)
        return FloatArray(3) { (c[it] - mn) * s / (mx - mn) }
    }
}
