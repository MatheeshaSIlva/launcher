package dev.launcher.app.shade.qs

import android.graphics.Canvas
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import dev.launcher.app.R
import dev.launcher.app.design.Design
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.motion.MotionValue

/**
 * The brightness slider (the `brightness` block): across the panel over the tiles at the second stage, a capsule whose
 * level fills from the left with its symbol at the level's end, as Android's. A drag sets the level under the finger
 * (the screen's brightness follows at once); a tap jumps there.
 */
internal class BrightnessBlock(private val panel: QsPanel, private val tiles: TileGridBlock?) {
    private val u get() = panel.u
    private val rect = RectF()

    /** The level shown (0..1, the slider's own scale): the finger's while it holds it, else the phone's on a spring. */
    private val level = MotionValue(0.5f, 1000f, { panel.view.invalidate() })
    private var dragging = false

    /**
     * Where the slider is now and its fade. It comes in at its own place once the tiles' first row has moved down past it
     * (they travel through its band on their way from the quick row to the grid, and the date comes down above it: it
     * crosses neither), settling a few points.
     */
    private fun place(out: RectF): Float {
        val rest = Design.pt(QsTokens.BRIGHTNESS_TOP, u)
        val h = Design.pt(QsTokens.BRIGHTNESS_HEIGHT, u)
        // Where the expand has taken the first row below the slider's band (the whole way, without tiles).
        val clear = if (tiles == null) 0.4f else {
            val travel = (tiles.fullTop - tiles.quickTop).coerceAtLeast(1f)
            ((rest + h - tiles.quickTop) / travel).coerceIn(0f, 0.95f)
        }
        val k = smooth(clear, 1f, panel.expandK)
        val top = rest - (1f - k) * 8f * u
        out.set(panel.side, top, panel.panelWidth - panel.side, top + h)
        return k
    }

    /** The phone's brightness changed (or the panel opens): the level follows, unless a finger holds it. */
    fun sync(animate: Boolean = true) {
        if (dragging) return
        val v = panel.host.state.brightness.coerceIn(0f, 1f)
        if (animate) level.animateTo(v, Motion.role(MotionTokens.CC_LEVEL)) else level.snapTo(v)
    }

    fun contains(x: Float, y: Float): Boolean = place(rect) > 0.5f && rect.contains(x, y)

    /** The level for a finger at [x] (the symbol's half capsule at each end is not travel). */
    private fun levelAt(x: Float): Float {
        place(rect)
        val h = rect.height()
        return ((x - rect.left - h / 2f) / (rect.width() - h)).coerceIn(0f, 1f)
    }

    private var lastEnd = -1

    /** A drag (or a tap) on the slider: [phase] 0 down, 1 move, 2 up. */
    fun drag(phase: Int, x: Float) {
        val v = levelAt(x)
        when (phase) {
            0 -> { dragging = true; level.animateTo(v, Motion.role(MotionTokens.CC_LEVEL)) }
            1 -> level.snapTo(v)
            else -> { level.snapTo(v); dragging = false }
        }
        panel.host.state.setBrightness(v, final = phase == 2)
        // A tick at either end, once.
        val end = if (v <= 0f) 0 else if (v >= 1f) 1 else -1
        if (end >= 0 && end != lastEnd) panel.view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        lastEnd = end
    }

    fun draw(c: Canvas, alpha: Float) {
        val a = place(rect) * alpha
        if (a <= 0.003f) return
        val sf = panel.surfaces
        val h = rect.height()
        val r = h / 2f
        c.save()
        c.translate(rect.left, rect.top)
        sf.draw(c, Design.material(QsTokens.BRIGHTNESS_TRACK), rect.width(), h, r, rect.left, rect.top, alpha = a)
        // The level: a capsule from the left at least as wide as it is tall (the symbol always sits on it).
        val lw = h + (rect.width() - h) * level.value.coerceIn(0f, 1f)
        sf.draw(c, Design.material(QsTokens.BRIGHTNESS_LEVEL), lw, h, r, rect.left, rect.top, alpha = a)
        c.restore()
        panel.glyphs.draw(c, R.drawable.sym_sun, rect.left + lw - r, rect.centerY(), Design.pt(QsTokens.SYMBOL, u),
            faded(Design.color(QsTokens.BRIGHTNESS_SYMBOL), a))
    }
}
