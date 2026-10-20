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

/**
 * Connectivity's circles where Apple's kit has them (2570:20605: 57 pt circles at 14 and 84.67 pt, 25.67 pt ones at 84.67
 * and 116 pt of the 155 pt module), as fractions of the module, and whether each is a large one: Control Center draws them
 * so, and its expanded module grows out of them ([CcExpanded]). The fourth small circle is NFC where there is NFC, else Dark
 * Mode.
 */
internal object CcConnectivity {
    class Circle(val control: Control, val fx: Float, val fy: Float, val big: Boolean) {
        /** Its radius as a fraction of the module (the touch's reach is measured from it). */
        val rf get() = if (big) BIG else SMALL
    }

    fun circles(fourth: Control): List<Circle> = if (fourth == Control.NFC) withNfc else withDarkMode

    private const val BIG = 28.5f / 155f
    private const val SMALL = 12.835f / 155f
    private fun make(fourth: Control) = listOf(
        Circle(Control.AIRPLANE, (14f + 28.5f) / 155f, (13f + 28.5f) / 155f, true),
        Circle(Control.HOTSPOT, (84.67f + 28.5f) / 155f, (13f + 28.5f) / 155f, true),
        Circle(Control.WIFI, (14f + 28.5f) / 155f, (84f + 28.5f) / 155f, true),
        Circle(Control.CELLULAR, (84.67f + 12.835f) / 155f, (84f + 12.835f) / 155f, false),
        Circle(Control.BLUETOOTH, (116f + 12.835f) / 155f, (84f + 12.835f) / 155f, false),
        Circle(Control.LOCATION, (84.67f + 12.835f) / 155f, (115.33f + 12.835f) / 155f, false),
        Circle(fourth, (116f + 12.835f) / 155f, (115.33f + 12.835f) / 155f, false),
    )
    private val withNfc = make(Control.NFC)
    private val withDarkMode = make(Control.DARK_MODE)
}
