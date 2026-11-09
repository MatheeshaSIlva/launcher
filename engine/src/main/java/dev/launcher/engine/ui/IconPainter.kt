package dev.launcher.engine.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import dev.launcher.engine.expr.Value
import dev.launcher.engine.layout.Measured
import dev.launcher.engine.layout.Size
import dev.launcher.engine.token.Tokens
import kotlin.math.ceil
import kotlin.math.max

/**
 * A prepared glyph as the engine sees it: its box in the set's units, and how it draws centred on a point with that box
 * [size] px across (`IconSets.Glyph.draw`). Made on the cache's worker; the painter never touches the set's file.
 */
class Symbol(val boxWidth: Float, val boxHeight: Float, val draw: (Canvas, cx: Float, cy: Float, size: Float, color: Int, Paint) -> Unit)

/** Pictures for `icon picture`: only what is published now. [version] (snapshot state) moves when that changes. */
interface PictureSource {
    val version: androidx.compose.runtime.IntState
    fun has(id: String, rev: String): Boolean
    /** A drawable of the caller's own, or null. */
    fun picture(id: String, rev: String): Drawable?
}

/**
 * `icon`'s painter (T-005, slice 9), one per node: symbol (the argument, often a template such as `"wifi.{…}"`) from
 * `set` (default [GlyphCache.FALLBACK]), `size` in points as the larger side of the symbol's box, `color`. It reads only
 * [glyphs]' snapshot. Its size is the box's at `size`, each side rounded up; its centre line is the box's middle,
 * unrounded (`Glyph.scale` scales the drawing, never the space). A glyph not there yet, or not anywhere, takes a `size`
 * square and draws nothing. A value that later names a glyph not prepared before the component showed may therefore
 * change the icon's size when it arrives: the painter asks the cache for it and measures and records again then, even
 * at an unchanged key.
 */
class IconPainter(private val tokens: Tokens, private val glyphs: GlyphCache<Symbol>) : Painter {
    /**
     * `picture` + `rev`: another source of pixels, a picture the component was given, in a `size` square, tinted as a
     * notification's small icon is (`StatusBarView.drawIcon`). Null when the component may not read pictures: nothing draws.
     * Asked again at each of its versions, so a picture that has left stops drawing even if a node still names it.
     */
    internal var pictures: PictureSource? = null
    private var drawable: Drawable? = null
    private var drawableFor: Triple<String, String, Int>? = null

    private fun picture(n: HostNode): Pair<String, String>? {
        val id = (n.value("picture") as? Value.Str)?.value?.takeIf { it.isNotEmpty() } ?: return null
        return id to ((n.value("rev") as? Value.Str)?.value ?: "")
    }

    /** This painter's own drawable of [k] (drawing changes bounds, tint and alpha), kept while the source's version stands. */
    private fun drawableOf(k: Pair<String, String>): Drawable? {
        val source = pictures ?: return null
        if (!source.has(k.first, k.second)) { drawable = null; drawableFor = null; return null }
        val mine = Triple(k.first, k.second, source.version.intValue)
        if (drawableFor != mine || drawable == null) {
            drawableFor = mine
            drawable = source.picture(k.first, k.second)
        }
        return drawable
    }

    override fun release(node: HostNode) { drawable = null; drawableFor = null }

    private var paint: Paint? = null
    /** Bumped when a requested glyph arrives: read in [key] and [measure], so both run again (the content's key moves too). */
    private val arrived = mutableIntStateOf(0)
    /** The key the binding names now (set at every look-up, owning thread): a completion for another is stale. */
    private var current: GlyphCache.Key? = null
    /** Completions that invalidated this icon (the stale-completion test's measure). */
    internal var arrivals = 0; private set
    /** The pending key already asked for, so a frame asks once. */
    private var asked: GlyphCache.Key? = null

    override fun key(node: HostNode): Any {
        picture(node)?.let { k -> return PictureKey(k, size(node), color(node), pictures?.version?.intValue ?: 0, pictures?.has(k.first, k.second) == true) }
        arrived.intValue
        val k = cacheKey(node)
        return Key(k, size(node), color(node), tokens.version, glyphs.resolve(k).tag())
    }

    override fun measure(node: HostNode, maxWidth: Int, maxHeight: Int): Measured {
        if (picture(node) != null) return metrics(null, size(node)).measured()
        arrived.intValue
        return metrics(found(node)?.let { it.boxWidth to it.boxHeight }, size(node)).measured()
    }

    override fun paint(canvas: Canvas, node: HostNode, width: Int, height: Int) {
        picture(node)?.let { k ->
            val d = drawableOf(k) ?: return
            val side = size(node).toInt()
            val col = color(node)
            d.setBounds(0, 0, side, side)
            d.setTint(col or (0xFF shl 24))
            d.alpha = (col ushr 24) and 0xFF
            d.draw(canvas)
            return
        }
        val s = found(node) ?: return
        val size = size(node)
        val m = metrics(s.boxWidth to s.boxHeight, size)
        val p = paint ?: Paint(Paint.ANTI_ALIAS_FLAG).also { paint = it }
        s.draw(canvas, m.boxWidth / 2f, m.boxHeight / 2f, size, color(node), p)
    }

    /** The glyph to draw now; asks the cache once per new key while it is pending. */
    private fun found(node: HostNode): Symbol? {
        val k = cacheKey(node)
        current = k
        return when (val r = glyphs.resolve(k)) {
            is GlyphCache.Found -> r.glyph
            GlyphCache.Absent -> null
            GlyphCache.Pending -> {
                if (asked != k) {
                    asked = k
                    glyphs.prepare(listOf(k), wanted = { current == k }, by = this) { arrivals++; arrived.intValue++; Snapshot.sendApplyNotifications() }
                }
                null
            }
        }
    }

    private fun cacheKey(n: HostNode) = GlyphCache.Key(set(n), symbol(n))

    private fun GlyphCache.Resolved<Symbol>.tag(): Any = when (this) {
        is GlyphCache.Found -> glyph
        GlyphCache.Absent -> "absent"
        GlyphCache.Pending -> "pending"
    }

    private data class Key(val glyph: GlyphCache.Key, val size: Float, val color: Int, val tokens: Int, val state: Any)
    private data class PictureKey(val picture: Pair<String, String>, val size: Float, val color: Int, val version: Int, val available: Boolean)

    /** What an icon measures, in px: the cell (whole pixels) and the box drawn in it (exact). */
    data class Metrics(val width: Int, val height: Int, val centre: Float, val boxWidth: Float, val boxHeight: Float) {
        /** Whole-pixel cell, exact extent: a row of icons adds up their exact widths, as StatusBarView's slots do. */
        fun measured() = Measured(Size(width, height), centre, boxWidth, boxHeight)
    }

    companion object {
        /** `icon` with no `size` (components should always give one). */
        const val DEFAULT_SIZE_PX = 17f
        const val DEFAULT_COLOR = 0xFFFFFFFF.toInt()

        /** [box] (width, height in the set's units; null: no glyph) at [sizePx] across its larger side. */
        fun metrics(box: Pair<Float, Float>?, sizePx: Float): Metrics {
            if (box == null || box.first <= 0f || box.second <= 0f) {
                val side = ceil(sizePx).toInt()
                return Metrics(side, side, sizePx / 2f, sizePx, sizePx)
            }
            val s = sizePx / max(box.first, box.second)
            val w = box.first * s
            val h = box.second * s
            return Metrics(ceil(w).toInt(), ceil(h).toInt(), h / 2f, w, h)
        }

        fun set(n: HostNode) = (n.value("set") as? Value.Str)?.value?.takeIf { it.isNotEmpty() } ?: GlyphCache.FALLBACK
        fun symbol(n: HostNode) = when (val v = n.value("symbol")) {
            is Value.Str -> v.value
            is Value.Null -> ""
            else -> v.toString()
        }
        fun size(n: HostNode) = n.length("size") ?: DEFAULT_SIZE_PX
        fun color(n: HostNode): Int = when (val v = n.value("color")) {
            is Value.Color -> v.argb
            is Value.Str -> runCatching { android.graphics.Color.parseColor(v.value) }.getOrDefault(DEFAULT_COLOR)
            else -> DEFAULT_COLOR
        }
    }
}
