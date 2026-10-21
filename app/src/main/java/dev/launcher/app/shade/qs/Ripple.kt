package dev.launcher.app.shade.qs

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import dev.launcher.app.design.Design
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.motion.MotionValue
import kotlin.math.hypot
import kotlin.math.max

/**
 * Material's press ripple, as Compose's `ripple()` draws it on Android's Quick Settings (tiles, a tile's box, the footer's
 * buttons, the edit page's tiles): pressed, a circle of the content's colour at 10 % grows from the finger to cover its
 * target (from 0.3 of the target's longer side to its half diagonal and 10 dp, its middle moving to the target's), fading
 * in over its first third; let go, it fades out once it has grown. A new press starts a new ripple while the last one fades.
 * Each ripple lives in the target's own coordinates, so a target that moves (a tile pressed into its neighbours) keeps it.
 */
class Ripple(private val invalidate: () -> Unit) {

    private inner class Wave(val x: Float, val y: Float) {
        val grow = MotionValue(0f, 1000f, { invalidate() }, { grown = true; if (released) leave() })
        val shown = MotionValue(0f, 1000f, { invalidate() })
        val out = MotionValue(0f, 1000f, { invalidate() }, { waves.remove(this) })
        var grown = false
        var released = false

        fun leave() {
            if (out.target == 1f) return
            out.animateTo(1f, Motion.role(MotionTokens.QS_RIPPLE_OUT))
        }
    }

    private val waves = ArrayList<Wave>(3)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val box = RectF()
    private val eight = FloatArray(8)

    /** A finger down at ([x], [y]) in the target's own coordinates (its top left at 0, 0). */
    fun press(x: Float, y: Float) {
        waves.lastOrNull()?.let { release(it) }
        if (waves.size >= 3) waves.removeAt(0)
        val w = Wave(x, y)
        waves += w
        w.grow.animateTo(1f, Motion.role(MotionTokens.QS_RIPPLE))
        w.shown.animateTo(1f, Motion.role(MotionTokens.QS_RIPPLE_IN))
    }

    /** The finger lifted or the press was cancelled: the ripple fades once it has grown. */
    fun release() { waves.lastOrNull()?.let { release(it) } }

    private fun release(w: Wave) {
        if (w.released) return
        w.released = true
        if (w.grown) w.leave()
    }

    /** At rest at once (the panel closed). */
    fun clear() {
        for (w in waves) { w.grow.stop(); w.shown.stop(); w.out.stop() }
        waves.clear()
    }

    val active get() = waves.isNotEmpty()

    /**
     * The ripples over a target of [w] x [h] with corners of [corner] (or [radii]: top left, top right, bottom right, bottom
     * left), its top left at the canvas's 0, 0, in [color] at [share] of its alpha (the target's content colour at
     * Material's pressed alpha, from the theme; a view's ripple passes its highlight colour whole), faded by [alpha]; [u]
     * the unit (px a pt).
     */
    fun draw(c: Canvas, w: Float, h: Float, corner: Float, color: Int, alpha: Float, u: Float,
             share: Float = Design.num(QsTokens.RIPPLE_ALPHA), radii: FloatArray? = null) {
        if (waves.isEmpty() || alpha <= 0.003f || w <= 0f || h <= 0f) return
        val start = max(w, h) * Design.num(QsTokens.RIPPLE_START)
        val end = hypot(w, h) / 2f + Design.pt(QsTokens.RIPPLE_EXTRA, u)
        val save = c.save()
        clip.rewind()
        box.set(0f, 0f, w, h)
        if (radii != null) {
            for (i in 0 until 4) { eight[2 * i] = radii[i]; eight[2 * i + 1] = radii[i] }
            clip.addRoundRect(box, eight, Path.Direction.CW)
        } else clip.addRoundRect(box, corner, corner, Path.Direction.CW)
        c.clipPath(clip)
        for (wv in waves) {
            val g = wv.grow.value.coerceIn(0f, 1f)
            val k = wv.shown.value.coerceIn(0f, 1f) * (1f - wv.out.value.coerceIn(0f, 1f))
            if (k <= 0.003f) continue
            val a = ((color ushr 24) * share * k * alpha).toInt().coerceIn(0, 255)
            paint.color = (color and 0x00FFFFFF) or (a shl 24)
            val cx = wv.x + (w / 2f - wv.x) * g
            val cy = wv.y + (h / 2f - wv.y) * g
            c.drawCircle(cx, cy, start + (end - start) * g, paint)
        }
        c.restoreToCount(save)
    }
}
