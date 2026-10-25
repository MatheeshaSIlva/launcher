package dev.launcher.app.shade

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import dev.launcher.app.design.Blend
import dev.launcher.app.design.ChoiceKey
import dev.launcher.app.design.Design
import dev.launcher.app.design.IconSets
import dev.launcher.app.design.SymbolDrawer
import dev.launcher.app.design.SymbolMotion
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
     * control is on (a set's symbol with an off look crossfades from it to its on look; the drawables have one look). With
     * `on` the symbol player's `onShare` (eased by the caller) this is also the crossfade of a look that has no play to
     * arrive by (B-036).
     */
    fun draw(c: Canvas, res: Int, cx: Float, cy: Float, size: Float, color: Int, on: Float = 1f) {
        if (size <= 0.5f || (color ushr 24) == 0) return
        set()?.let { s ->
            val name = nameOf(res)
            val glyph = s.glyph(name)
            if (glyph != null) {
                val off = if (on < 1f) s.glyph(keyOf(res, true)) else null
                if (off == null) { glyph.draw(c, cx, cy, size, color, setPaint); return }
                val k = on.coerceIn(0f, 1f)
                if (k < 1f) off.draw(c, cx, cy, size, faded(color, 1f - k), setPaint)
                if (k > 0f) glyph.draw(c, cx, cy, size, faded(color, k), setPaint)
                return
            }
        }
        draw(c, cache.getOrPut(res) { ctx.getDrawable(res)!!.mutate() }, cx, cy, size, color)
    }

    private val offNames = HashMap<Int, String>()
    private val drawers = HashMap<SymbolMotion, SymbolDrawer>()

    /** Symbol [res]'s name in the set, or its `.off` look's. */
    private fun keyOf(res: Int, off: Boolean): String = if (!off) nameOf(res) else offNames.getOrPut(res) { nameOf(res) + ".off" }

    /**
     * Symbol [res]'s animation in the set now ([off]: the one to the off look, the `.off` symbol's), null if the set has
     * none (the looks crossfade). Built on its first ask: have [IconSets.preparePlays] run first, so no drawing thread builds one.
     */
    fun motion(res: Int, off: Boolean = false): SymbolMotion? = set()?.motion(keyOf(res, off))

    /** The set's plays are all built (a drawing thread may ask [motion] and [drawPlaying]; before, the looks crossfade). */
    fun playsReady(): Boolean {
        // (Never reads the set's file nor waits for its reading: a set not yet loaded is not ready.)
        if (Design.version == setVersion) return set?.playsReady == true
        return IconSets.peek(try { Design.choice(symbols) } catch (_: Throwable) { null })?.playsReady == true
    }

    /**
     * Symbol [res] ([off]: its `.off` look's frame, the box and scale of the same slot) at [timeMs] into [motion] (the
     * one [motion] gave), as [draw] places and tints it. Nothing for a symbol the set does not carry.
     */
    fun drawPlaying(c: Canvas, res: Int, off: Boolean, motion: SymbolMotion, timeMs: Float, cx: Float, cy: Float, size: Float, color: Int) {
        if (size <= 0.5f || (color ushr 24) == 0) return
        val glyph = set()?.glyph(keyOf(res, off)) ?: return
        drawers.getOrPut(motion) { SymbolDrawer(motion) }.draw(c, glyph, timeMs, cx, cy, size, color, setPaint)
    }

    private val setPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var setVersion = -1
    private var set: IconSets.IconSet? = null

    /** The icon set the element's token names now (looked up again after a design change). */
    private fun set(): IconSets.IconSet? {
        val v = Design.version
        if (v != setVersion) {
            setVersion = v
            val now = IconSets.get(ctx, try { Design.choice(symbols) } catch (_: Throwable) { null })
            // (The drawers belong to the set's motions: a design change that leaves the set as it is keeps them.)
            if (now !== set) { drawers.clear(); set = now }
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
        // (The colour's own alpha tints the vector: a drawable's alpha beside an opaque tint left a fading symbol at full
        // strength, a card's chevron outliving its text.)
        d.setTint(color)
        d.alpha = 255
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
