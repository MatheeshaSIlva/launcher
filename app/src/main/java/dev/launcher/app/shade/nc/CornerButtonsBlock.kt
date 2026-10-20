package dev.launcher.app.shade.nc

import android.graphics.Canvas
import android.view.HapticFeedbackConstants
import dev.launcher.app.R
import dev.launcher.app.design.Blend
import dev.launcher.app.design.ColorValue
import dev.launcher.app.design.Design
import dev.launcher.app.design.Fill
import dev.launcher.app.motion.MotionValue
import dev.launcher.app.shade.Glyphs
import dev.launcher.app.shade.NcTokens
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The layout file's `corner-buttons` block: the flashlight and the camera at the bottom corners (iOS's lock screen
 * controls: touch and hold). The kit's clear glass discs with a light grey symbol added to the glass (plus-lighter); the
 * flashlight on is a white disc with a dark symbol. A hold grows the disc and, once held long enough, acts.
 */
class CornerButtonsBlock(private val panel: NcPanel, private val torchOn: () -> Boolean, private val torch: () -> Unit, private val camera: () -> Unit) {
    private val u get() = panel.u
    private val inv: (Float) -> Unit = { panel.view.invalidate() }
    private val glyphs = Glyphs(panel.view.context, NcTokens.SYMBOLS)
    private val torchK = MotionValue(0f, 100f, inv)
    private val holdTorch = MotionValue(0f, 100f, inv)
    private val holdCamera = MotionValue(0f, 100f, inv)

    /** Where the flashlight's (0) or the camera's (1) button is centred, and its radius (the kit's lock screen controls). */
    private fun x(i: Int): Float { val c = Design.pt(NcTokens.BUTTON_INSET_X, u) + r(); return if (i == 0) c else panel.panelWidth - c }
    private fun y(): Float = panel.panelHeight - Design.pt(NcTokens.BUTTON_BOTTOM, u) - r()
    private fun r(): Float = Design.pt(NcTokens.BUTTON_SIZE, u) / 2f

    /** Their top: what lies above them ends there. */
    fun top(): Float = y() - r()

    fun draw(c: Canvas) {
        val on = if (torchOn()) 1f else 0f
        if (torchK.target != on) torchK.animateTo(on, TOGGLE)
        val r = r()
        val y = y()
        val mat = Design.material(NcTokens.BUTTON)
        val onFill = listOf(Fill(ColorValue.Ref(NcTokens.BUTTON_ON.name), 1f, 1f, Blend.NORMAL))
        val symbol = Design.pt(NcTokens.BUTTON_SYMBOL, u)
        val symColor = Design.color(NcTokens.BUTTON_SYMBOL_COLOR)
        val symBlend = Design.blend(NcTokens.BUTTON_SYMBOL_BLEND)
        for (i in 0..1) {
            val x = x(i)
            val hold = if (i == 0) holdTorch.value else holdCamera.value
            val s = 1f + 0.22f * hold
            val t = if (i == 0) torchK.value.coerceIn(0f, 1f) else 0f
            c.save()
            c.scale(s, s, x, y)
            panel.drawGlass(c, mat, x - r, y - r, 2 * r, 2 * r, r, 1f, s, x - r * s, y - r * s, over = onFill, overK = t)
            val res = if (i == 0) R.drawable.sym_flashlight else R.drawable.sym_camera
            // Off: the kit's grey, added to the glass; on: dark on the white disc (they cross-fade).
            glyphs.draw(c, res, x, y, symbol, alpha(symColor, 1f - t), symBlend)
            if (t > 0.003f) glyphs.draw(c, res, x, y, symbol, alpha(Design.color(NcTokens.BUTTON_ON_SYMBOL), t))
            c.restore()
        }
    }

    // ---- touch and hold

    /** The button held (0 the flashlight, 1 the camera), or -1. */
    private var held = -1

    private val fire = Runnable {
        val i = held
        if (i < 0) return@Runnable
        panel.view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (i == 0) torch() else camera()
        letGo()
    }

    /** A finger down at [x], [y] (the sheet's coordinates): true if it is on a button (then held until [up], [cancel]). */
    fun down(x: Float, y: Float): Boolean {
        val by = y()
        for (i in 0..1) {
            if (hypot(x - x(i), y - by) < r() + 5f * u) {
                held = i
                (if (i == 0) holdTorch else holdCamera).animateTo(1f, HOLD_IN)
                panel.view.postDelayed(fire, HOLD_MS)
                return true
            }
        }
        return false
    }

    /** The finger lifted, moved away or was taken: the hold ends without acting. */
    fun up() = letGo()
    fun cancel() = letGo()

    private fun letGo() {
        panel.view.removeCallbacks(fire)
        held = -1
        holdTorch.animateTo(0f, PRESS_OUT); holdCamera.animateTo(0f, PRESS_OUT)
    }

    private fun alpha(color: Int, k: Float): Int {
        val a = (((color ushr 24) and 0xFF) * k.coerceIn(0f, 1f)).roundToInt()
        return (a shl 24) or (color and 0xFFFFFF)
    }

    private companion object {
        const val HOLD_MS = 380L
        val HOLD_IN get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.NC_HOLD)
        val PRESS_OUT get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.PRESS_OUT)
        val TOGGLE get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.NC_TOGGLE)
    }
}
