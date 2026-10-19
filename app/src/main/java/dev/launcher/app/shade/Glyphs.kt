package dev.launcher.app.shade

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import dev.launcher.app.design.Blend
import dev.launcher.app.design.ChoiceKey
import dev.launcher.app.design.Design
import dev.launcher.app.design.IconSets
import dev.launcher.app.design.toBlendMode

/**
 * The shade's symbols (vector drawables, white, tinted when drawn), or a theme's icon set in their place ([symbols]: the
 * element's own token naming the set, [IconSets]; a name the set leaves out is drawn from its drawable). One cached
 * drawable per symbol and per instance of this class: use one instance per thread.
 */
class Glyphs(private val ctx: Context, private val symbols: ChoiceKey = CcTokens.SYMBOLS) {
    private val cache = HashMap<Int, Drawable>()

    /**
     * Draws symbol [res] centred on [cx],[cy], [size] px square, in [color] (ARGB: its alpha fades it); [on]: how far its
     * control is on (a set's symbol with an off look crossfades from it to its on look; the drawables have one look).
     */
    fun draw(c: Canvas, res: Int, cx: Float, cy: Float, size: Float, color: Int, on: Float = 1f) {
        if (size <= 0.5f || (color ushr 24) == 0) return
        set()?.let { s ->
            val name = nameOf(res)
            val glyph = s.glyph(name)
            if (glyph != null) {
                val off = if (on < 1f) s.glyph("$name.off") else null
                if (off == null) { glyph.draw(c, cx, cy, size, color, setPaint); return }
                val k = on.coerceIn(0f, 1f)
                if (k < 1f) off.draw(c, cx, cy, size, faded(color, 1f - k), setPaint)
                if (k > 0f) glyph.draw(c, cx, cy, size, faded(color, k), setPaint)
                return
            }
        }
        draw(c, cache.getOrPut(res) { ctx.getDrawable(res)!!.mutate() }, cx, cy, size, color)
    }

    private val setPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var setVersion = -1
    private var set: IconSets.IconSet? = null

    /** The icon set the element's token names now (looked up again after a design change). */
    private fun set(): IconSets.IconSet? {
        val v = Design.version
        if (v != setVersion) {
            setVersion = v
            set = IconSets.get(ctx, try { Design.choice(symbols) } catch (_: Throwable) { null })
        }
        return set
    }

    private val names = HashMap<Int, String>()

    /** A symbol's name in the icon sets: its drawable's, without `sym_`. */
    private fun nameOf(res: Int): String = names.getOrPut(res) {
        try { ctx.resources.getResourceEntryName(res).removePrefix("sym_") } catch (_: Throwable) { "" }
    }

    private fun faded(color: Int, k: Float): Int = (Math.round((color ushr 24) * k) shl 24) or (color and 0xFFFFFF)

    private val tiles = HashMap<String, Drawable>()

    /** An app's tile's own symbol ([AppTiles]; a generic one while it is not known, or if it has none), as [draw]. */
    fun drawTile(c: Canvas, id: String, cx: Float, cy: Float, size: Float, color: Int) {
        if (size <= 0.5f || (color ushr 24) == 0) return
        val d = tiles[id] ?: AppTiles.info(id)?.icon?.newDrawable()?.mutate()?.also { tiles[id] = it }
        if (d == null) { draw(c, dev.launcher.app.R.drawable.sym_more, cx, cy, size, color); return }
        draw(c, d, cx, cy, size, color)
    }

    private fun draw(c: Canvas, d: Drawable, cx: Float, cy: Float, size: Float, color: Int) {
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
