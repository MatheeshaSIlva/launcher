package dev.launcher.app.shade

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import dev.launcher.app.design.Blend
import dev.launcher.app.design.toBlendMode

/**
 * The shade's symbols (vector drawables, white, tinted when drawn). One cached drawable per symbol and per instance of
 * this class: use one instance per thread.
 */
class Glyphs(private val ctx: Context) {
    private val cache = HashMap<Int, Drawable>()

    /** Draws symbol [res] centred on [cx],[cy], [size] px square, in [color] (ARGB: its alpha fades it). */
    fun draw(c: Canvas, res: Int, cx: Float, cy: Float, size: Float, color: Int) {
        if (size <= 0.5f || (color ushr 24) == 0) return
        val d = cache.getOrPut(res) { ctx.getDrawable(res)!!.mutate() }
        val s = size.toInt().coerceAtLeast(1)
        d.setBounds(0, 0, s, s)
        d.setTint(color or (0xFF shl 24))
        d.alpha = (color ushr 24) and 0xFF
        // Placed by translation (sub-pixel), drawn at whole-pixel bounds (its cache keeps its size while it moves).
        val save = c.save()
        c.translate(cx - s / 2f, cy - s / 2f)
        d.draw(c)
        c.restoreToCount(save)
    }

    private val layer = Paint()

    /**
     * [res] laid on what is drawn with [blend] (a design kit's symbol blend: plus-lighter, ...), through a small layer (a
     * tinted drawable cannot blend by itself). Normal, or a blend the canvas has no mode for, draws it plainly.
     */
    fun draw(c: Canvas, res: Int, cx: Float, cy: Float, size: Float, color: Int, blend: Blend) {
        val mode = blend.toBlendMode()
        if (blend == Blend.NORMAL || mode == null) { draw(c, res, cx, cy, size, color); return }
        if (size <= 0.5f || (color ushr 24) == 0) return
        layer.blendMode = mode
        val save = c.saveLayer(cx - size, cy - size, cx + size, cy + size, layer)
        draw(c, res, cx, cy, size, color)
        c.restoreToCount(save)
    }
}
