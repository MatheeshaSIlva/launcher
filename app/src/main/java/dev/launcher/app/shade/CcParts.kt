package dev.launcher.app.shade

import android.graphics.Canvas
import dev.launcher.app.design.Design

/**
 * Control Center's parts that its module and its expanded form share (step 3 of docs/DESIGN_SYSTEM_PLAN.md), drawn from
 * `comp.cc.*` tokens: the views only lay them out and route touches.
 */
internal object CcSlider {
    /**
     * A slider [w] x [h] at the canvas's origin (corner [radius]; its corner at ([sx], [sy]) on screen, what its glass
     * samples): the level ([value], 0..1) fills from the bottom with the kit's "on" fill (faded by [fillAlpha]), and its
     * symbol [icon], [symbol] px, a square's width up from the bottom, takes [accent] while the level crosses it.
     */
    fun draw(c: Canvas, sf: CcSurfaces, glyphs: Glyphs, w: Float, h: Float, radius: Float, sx: Float, sy: Float,
             value: Float, icon: Int, accent: Int, symbol: Float, alpha: Float, fillAlpha: Float = alpha) {
        val v = value.coerceIn(0f, 1f)
        val fillH = v * h
        if (fillH > 0.5f) {
            c.save()
            c.clipRect(0f, h - fillH, w, h)
            sf.onFill(c, w, h, radius, sx, sy, 1f, fillAlpha)
            c.restore()
        }
        val gy = h - w / 2f
        // How much of the symbol the level covers (0..1), over the part of it the colour change spans.
        val span = Design.num(CcTokens.SLIDER_COVER) * symbol
        val covered = ((fillH - (w / 2f - span / 2f)) / span.coerceAtLeast(0.001f)).coerceIn(0f, 1f)
        glyphs.draw(c, icon, w / 2f, gy, symbol, fade(mix(Design.color(CcTokens.SYMBOL_COLOR), accent, covered), alpha))
    }

    private fun fade(color: Int, k: Float): Int = ((((color ushr 24) and 0xFF) * k.coerceIn(0f, 1f) + 0.5f).toInt() shl 24) or (color and 0xFFFFFF)

    private fun mix(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a
        if (t >= 1f) return b
        fun ch(s: Int) = ((((a shr s) and 0xFF) + ((((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t)) + 0.5f).toInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }
}
