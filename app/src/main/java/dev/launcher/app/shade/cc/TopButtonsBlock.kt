package dev.launcher.app.shade.cc

import android.graphics.Canvas
import android.view.HapticFeedbackConstants
import dev.launcher.app.AppLog
import dev.launcher.app.R
import dev.launcher.app.design.Design
import dev.launcher.app.motion.MotionValue
import dev.launcher.app.shade.CcTokens
import dev.launcher.app.shade.Glyphs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The layout file's `top-buttons` block: "+" (edit mode) and the power button at the top corners of Control Center (the
 * kit: 28.67 pt at (38, 23) and (335.33, 23)): its near-black added to what is behind (plus-lighter), a light symbol. They
 * stay where they are while the controls are pulled, fold back into the corner with them as the panel closes, and give way
 * to edit mode, an expanded module and a control's menu. "+" acts on a tap; power is held until the phone vibrates, as
 * iOS's (a tap does nothing: the power menu is not a tap away). Not in landscape (iOS 27: one status line there).
 */
class TopButtonsBlock(private val panel: CcPanel, private val onPlus: () -> Unit) {
    private val u get() = panel.u
    private val inv: (Float) -> Unit = { panel.view.invalidate() }
    private val glyphs = Glyphs(panel.view.context)
    private val plusPress = MotionValue(0f, 100f, inv)
    private val powerPress = MotionValue(0f, 100f, inv)

    private var y = 0f
    private var r = 0f

    /** Their size and place from the tokens. */
    fun syncTokens() {
        val sf = panel.host.surfaces
        r = sf.pt(CcTokens.BUTTON_SIZE) / 2f
        y = sf.pt(CcTokens.BUTTON_TOP) + r
    }

    /** Where "+" (0) and the power button (1) are centred horizontally (the kit: 38 pt from the sides). */
    private fun x(i: Int): Float { val c = panel.host.surfaces.pt(CcTokens.BUTTON_INSET_X) + r; return if (i == 0) c else panel.panelWidth - c }

    /** Draws them at [base] of their presence (what gives way to edit mode, an expanded module, a menu). */
    fun draw(c: Canvas, base: Float) {
        if (base <= 0.003f) return
        val sf = panel.host.surfaces
        val mat = Design.material(CcTokens.BUTTON)
        val symbol = sf.pt(CcTokens.BUTTON_SYMBOL)
        val col = Design.color(CcTokens.BUTTON_SYMBOL_COLOR)
        val cs = panel.closingK.coerceIn(0f, 1f)
        val k0 = panel.presence()
        val corner = panel.cornerX
        for (i in 0..1) {
            var x = x(i)
            var y = y
            val p = if (i == 0) plusPress.value else powerPress.value
            var s = 1f + 0.08f * p
            var k = k0
            if (cs > 0.001f) {
                // As the controls: back into the corner (see CcPanel.closingK).
                val e = panel.leaving(x, y)
                x += (corner - x) * CcClose.PULL * e * cs
                y += -y * CcClose.PULL * e * cs
                s *= 1f - CcClose.SHRINK * e * cs
                k += ((1f - e) - k) * cs
            }
            k *= base
            if (k <= 0.003f) continue
            val d = r * 2f
            c.save()
            c.translate(x - r * s, y - r * s)
            c.scale(s, s)
            sf.draw(c, mat, d, d, r, x - r * s, y - r * s, s, k, p)
            glyphs.draw(c, if (i == 0) R.drawable.sym_plus else R.drawable.sym_power, r, r, symbol, alpha(col, k))
            c.restore()
        }
    }

    // ---- touch

    /** The button pressed (0 "+", 1 power), or -1. */
    private var pressed = -1

    private val hold = Runnable {
        if (pressed != 1) return@Runnable
        AppLog.log("[cc] power held: the power menu asked for")
        panel.view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        powerPress.animateTo(0f, PRESS_OUT)
        pressed = -1
        panel.host.powerMenu()
    }

    /** A finger down at [x], [y]: true if it is on a button (then the gesture is theirs until [up] or [cancel]). */
    fun down(x: Float, y: Float): Boolean {
        for (i in 0..1) {
            if (hypot(x - x(i), y - this.y) < r + 10f * u) {
                pressed = i
                (if (i == 0) plusPress else powerPress).animateTo(1f, PRESS_IN)
                if (i == 1) panel.view.postDelayed(hold, LONG_MS)
                return true
            }
        }
        return false
    }

    /** Lifted: "+" acts (power acted already if it was held long enough). */
    fun up() {
        val was = pressed
        letGo()
        if (was == 0) onPlus()
    }

    /** Moved away or taken: nothing happens. */
    fun cancel() = letGo()

    private fun letGo() {
        panel.view.removeCallbacks(hold)
        pressed = -1
        plusPress.animateTo(0f, PRESS_OUT); powerPress.animateTo(0f, PRESS_OUT)
    }

    private fun alpha(color: Int, k: Float): Int {
        val a = (((color ushr 24) and 0xFF) * k.coerceIn(0f, 1f)).roundToInt()
        return (a shl 24) or (color and 0xFFFFFF)
    }

    private companion object {
        const val LONG_MS = 450L
        val PRESS_IN get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.CC_PRESS_IN)
        val PRESS_OUT get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.CC_PRESS_OUT)
    }
}
