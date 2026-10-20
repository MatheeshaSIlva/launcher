package dev.launcher.app.shade.qs

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.provider.Settings
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import dev.launcher.app.R
import dev.launcher.app.design.Design
import dev.launcher.app.design.applyTo
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.motion.MotionValue

/**
 * Quick Settings' footer (the `qs-footer` block), at the second stage: under the tiles the build number and the page dots;
 * at the foot of the panel, the settings button and the power button (lit), as Android's.
 */
internal class QsFooterBlock(private val panel: QsPanel, private val tiles: TileGridBlock?) {
    private val u get() = panel.u
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val build = "${Build.VERSION.RELEASE} (${Build.ID})"

    private enum class Button(val symbol: Int) { SETTINGS(R.drawable.sym_settings), POWER(R.drawable.sym_power) }
    private val presses = Button.entries.associateWith { MotionValue(0f, 1000f, { panel.view.invalidate() }) }

    /** The build row's top (under the tiles) and height. */
    private fun rowTop() = (tiles?.fullBottom ?: Design.pt(QsTokens.TILES_TOP, u)) + Design.pt(QsTokens.TILE_GAP, u)
    private val rowHeight get() = 48f * u

    /** Where the panel's content ends at the second stage (px): the cards go below it as Quick Settings expand. */
    val bottom: Float get() = rowTop() + rowHeight

    /** Button [b]'s centre ([out]: its circle) and its fade. */
    private fun place(b: Button, out: RectF): Float {
        val size = Design.pt(QsTokens.FOOTER_BUTTON, u)
        val pitch = maxOf(size, 48f * u)
        val k = Button.entries.size - 1 - b.ordinal
        val cx = panel.panelWidth - panel.side - pitch / 2f - k * pitch
        val cy = panel.panelHeight - Design.pt(QsTokens.FOOTER_BOTTOM, u) + (1f - panel.expandK) * 24f * u
        out.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        return smooth(0.5f, 1f, panel.expandK)
    }

    private fun buttonAt(x: Float, y: Float): Button? {
        val slack = 6f * u
        return Button.entries.firstOrNull { b -> place(b, r) > 0.5f && x >= r.left - slack && x <= r.right + slack && y >= r.top - slack && y <= r.bottom + slack }
    }

    fun contains(x: Float, y: Float): Boolean = buttonAt(x, y) != null

    private var pressed: Button? = null

    fun press(x: Float, y: Float) {
        pressed = buttonAt(x, y)
        pressed?.let { presses.getValue(it).animateTo(1f, Motion.role(MotionTokens.PRESS_IN)) }
    }

    fun release(tap: Boolean) {
        val b = pressed ?: return
        pressed = null
        presses.getValue(b).animateTo(0f, Motion.role(MotionTokens.PRESS_OUT))
        if (!tap) return
        panel.view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        when (b) {
            Button.SETTINGS -> panel.host.launch(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Button.POWER -> panel.host.powerMenu()
        }
    }

    fun draw(c: Canvas, alpha: Float) {
        val e = panel.expandK
        // Under the tiles: the build number and the page dots.
        val row = alpha * smooth(0.5f, 1f, e)
        if (row > 0.003f) {
            val top = rowTop()
            Design.text(QsTokens.BUILD).applyTo(text, u)
            text.color = faded(Design.color(QsTokens.BUILD_COLOR), row)
            text.textAlign = Paint.Align.LEFT
            c.drawText(build, panel.side, baselineIn(text, top, rowHeight), text)
            val pages = tiles?.pages ?: 1
            if (pages > 1) {
                val d = Design.pt(QsTokens.DOT, u)
                val total = pages * d + (pages - 1) * d + d
                var x = panel.panelWidth / 2f - total / 2f
                val cy = top + rowHeight / 2f
                for (i in 0 until pages) {
                    // The current page's dot is a pill twice as long, growing and shrinking as the pages slide.
                    val k = (1f - kotlin.math.abs(panel.page - i)).coerceIn(0f, 1f)
                    val w = d + d * k
                    dot.color = faded(mix(Design.color(QsTokens.DOT_COLOR), Design.color(QsTokens.DOT_ACTIVE_COLOR), k), row)
                    r.set(x, cy - d / 2f, x + w, cy + d / 2f)
                    c.drawRoundRect(r, d / 2f, d / 2f, dot)
                    x += w + d
                }
            }
        }
        // At the foot: settings, and power lit.
        for (b in Button.entries) {
            val a = place(b, r) * alpha
            if (a <= 0.003f) continue
            val press = presses.getValue(b).value
            val power = b == Button.POWER
            val s = 1f - 0.06f * press
            c.save()
            c.scale(s, s, r.centerX(), r.centerY())
            c.translate(r.left, r.top)
            panel.surfaces.draw(c, Design.material(if (power) QsTokens.POWER_MATERIAL else QsTokens.FOOTER_MATERIAL), r.width(), r.height(),
                r.width() / 2f, r.left, r.top, s, a, press)
            c.restore()
            panel.glyphs.draw(c, b.symbol, r.centerX(), r.centerY(), Design.pt(QsTokens.FOOTER_SYMBOL, u) * s,
                faded(Design.color(if (power) QsTokens.POWER_SYMBOL_COLOR else QsTokens.FOOTER_SYMBOL_COLOR), a))
        }
    }
}
