package dev.launcher.engine.layout

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Size bounds in whole pixels; [INF] is unbounded. */
data class Constraints(val minWidth: Int, val maxWidth: Int, val minHeight: Int, val maxHeight: Int) {
    fun loose() = copy(minWidth = 0, minHeight = 0)
    companion object {
        const val INF = Int.MAX_VALUE
        fun fixed(width: Int, height: Int) = Constraints(width, width, height, height)
    }
}

data class Size(val width: Int, val height: Int)

/** A measured size, and its centre line in pixels from its top (text: the middle of its capitals; else the middle). */
data class Measured(val size: Size, val centre: Float = size.height / 2f,
    val exactWidth: Float = size.width.toFloat(), val exactHeight: Float = size.height.toFloat())

data class Placement(val x: Int, val y: Int, val residualX: Float = 0f, val residualY: Float = 0f) {
    val exactX get() = x + residualX
    val exactY get() = y + residualY
    companion object {
        fun exact(x: Float, y: Float) = Placement(x.roundToInt(), y.roundToInt(), x - x.roundToInt(), y - y.roundToInt())
    }
}

/** What a policy decided: its own measure, and each child's place and measure, in the order the children came. */
data class Arranged(val measured: Measured, val placements: List<Placement>, val children: List<Measured>)

/** A child as its parent's policy sees it: measured on demand; [flex] above 0 shares a row's leftover (a spacer). */
interface Child {
    val flex: Float get() = 0f
    fun measure(c: Constraints): Measured
}

/** A leaf's own size (text, icon), given by the host, which knows fonts and symbols. */
fun interface Measurer {
    fun measure(maxWidth: Int, maxHeight: Int): Measured
}

/**
 * Lengths to pixels: `pt` (the default) by the engine's unit (`Scale.unitPx`), `dp` by the density, `px` as they are.
 * [unitPx] changes with the display (the launcher's unit follows its short side, smaller in landscape) and the settings'
 * size: the host sets it and measures again.
 */
class Units(unitPx: Float, private val density: Float) {
    var unitPx: Float = unitPx
        internal set
    fun px(value: Double, unit: String?): Float = when (unit) {
        null, "pt" -> value * unitPx
        "dp" -> value * density
        "px" -> value
        else -> throw IllegalArgumentException("'$unit' is not a length unit")
    }.toFloat()
}

/** The common layout attributes in pixels: a fixed size, bounds, padding. */
data class Frame(
    val width: Float? = null, val height: Float? = null,
    val minWidth: Float = 0f, val maxWidth: Float = Float.POSITIVE_INFINITY,
    val minHeight: Float = 0f, val maxHeight: Float = Float.POSITIVE_INFINITY,
    val padStart: Float = 0f, val padTop: Float = 0f, val padEnd: Float = 0f, val padBottom: Float = 0f,
)

enum class Justify { START, CENTER, END, SPACE_BETWEEN }
enum class Align { TOP, CENTER, BOTTOM, CENTRE_LINE }

/**
 * The layout of rows, stacks, boxes, spacers and leaves, as pure functions of the children's measures. Places are
 * worked out in exact (fractional) pixels from the start and rounded once each, so whole-pixel places never add up
 * rounding: the error of any place is under half a pixel however many children came before it. Sizes stay what the
 * children measured; a flex spacer takes the pixels between its rounded start and end, so spacers and their
 * neighbours tile the row exactly.
 */
object Layout {
    private fun px(v: Float) = if (v.isInfinite()) Constraints.INF else v.roundToInt()
    private fun minus(bound: Int, by: Int) = if (bound == Constraints.INF) bound else max(0, bound - by)

    /** [c] narrowed by the frame's fixed size and bounds. */
    fun bound(f: Frame, c: Constraints): Constraints {
        fun side(lo: Int, hi: Int, fixed: Float?, fMin: Float, fMax: Float): Pair<Int, Int> {
            fixed?.let { val v = ceil(it).toInt().coerceIn(lo, hi); return v to v }
            val mn = px(fMin).coerceIn(lo, hi)
            return mn to max(mn, min(hi, px(fMax)))
        }
        val (w0, w1) = side(c.minWidth, c.maxWidth, f.width, f.minWidth, f.maxWidth)
        val (h0, h1) = side(c.minHeight, c.maxHeight, f.height, f.minHeight, f.maxHeight)
        return Constraints(w0, w1, h0, h1)
    }

    /** The frame around [content], which lays out inside the padding. */
    fun framed(f: Frame, c: Constraints, content: (Constraints) -> Arranged): Arranged {
        val outer = bound(f, c)
        val (ps, pt) = px(f.padStart) to px(f.padTop)
        val (ph, pv) = ps + px(f.padEnd) to pt + px(f.padBottom)
        val a = content(Constraints(max(0, outer.minWidth - ph), minus(outer.maxWidth, ph), max(0, outer.minHeight - pv), minus(outer.maxHeight, pv)))
        val w = (a.measured.size.width + ph).coerceIn(outer.minWidth, outer.maxWidth)
        val h = (a.measured.size.height + pv).coerceIn(outer.minHeight, outer.maxHeight)
        val ew = (f.width ?: (a.measured.exactWidth + f.padStart + f.padEnd)).coerceIn(c.minWidth.toFloat(), c.maxWidth.toFloat())
        val eh = (f.height ?: (a.measured.exactHeight + f.padTop + f.padBottom)).coerceIn(c.minHeight.toFloat(), c.maxHeight.toFloat())
        return Arranged(Measured(Size(w, h), a.measured.centre + f.padTop, ew, eh),
            a.placements.map { Placement.exact(it.exactX + f.padStart, it.exactY + f.padTop) }, a.children)
    }

    fun leaf(f: Frame, c: Constraints, measurer: Measurer): Arranged = framed(f, c) { inner ->
        val m = measurer.measure(inner.maxWidth, inner.maxHeight)
        val size = Size(m.size.width.coerceIn(inner.minWidth, inner.maxWidth), m.size.height.coerceIn(inner.minHeight, inner.maxHeight))
        val ew = m.exactWidth.coerceIn(inner.minWidth.toFloat(), inner.maxWidth.toFloat())
        val eh = m.exactHeight.coerceIn(inner.minHeight.toFloat(), inner.maxHeight.toFloat())
        Arranged(Measured(size, m.centre + (eh - m.exactHeight) / 2f, ew, eh), emptyList(), emptyList())
    }

    /** A spacer's own measure: its [size] along the row, as wide as its constraints allow (a flex spacer's are fixed). */
    fun spacer(size: Float, c: Constraints) = Measured(Size(px(size).coerceIn(c.minWidth, c.maxWidth), c.minHeight))

    fun row(f: Frame, c: Constraints, gap: Float, justify: Justify, align: Align, children: List<Child>): Arranged = framed(f, c) { inner ->
        val n = children.size
        val measured = arrayOfNulls<Measured>(n)
        var used = gap * max(0, n - 1)
        for (k in 0 until n) if (children[k].flex <= 0f) {
            val m = children[k].measure(Constraints(0, minus(inner.maxWidth, floor(used).toInt()), 0, inner.maxHeight))
            measured[k] = m
            used += m.exactWidth
        }
        val flex = children.sumOf { it.flex.toDouble() }.toFloat()
        val bounded = inner.maxWidth != Constraints.INF
        val leftover = if (bounded) max(0f, inner.maxWidth - used) else 0f
        val exact = FloatArray(n) { k -> measured[k]?.exactWidth ?: if (flex > 0f) leftover * children[k].flex / flex else 0f }
        val content = exact.sum() + gap * max(0, n - 1)
        val width = max(inner.minWidth, if (flex > 0f && bounded) inner.maxWidth else ceil(content).toInt()).coerceAtMost(inner.maxWidth)
        val exactWidth = (f.width?.let { it - f.padStart - f.padEnd } ?: max(inner.minWidth.toFloat(), content)).coerceIn(0f, width.toFloat())
        val free = max(0f, exactWidth - content)
        var at = when (justify) { Justify.CENTER -> free / 2; Justify.END -> free; else -> 0f }
        val between = if (justify == Justify.SPACE_BETWEEN && n > 1) free / (n - 1) else 0f
        val xs = FloatArray(n)
        for (k in 0 until n) {
            xs[k] = at
            val end = (at + exact[k]).roundToInt()
            if (measured[k] == null) measured[k] = children[k].measure(Constraints(end - xs[k].roundToInt(), end - xs[k].roundToInt(), 0, inner.maxHeight))
            at += exact[k] + gap + between
        }
        val ms = measured.map { it!! }
        val above = ms.maxOfOrNull { it.centre } ?: 0f
        val below = ms.maxOfOrNull { it.exactHeight - it.centre } ?: 0f
        val tallest = ms.maxOfOrNull { it.exactHeight } ?: 0f
        val naturalExact = if (align == Align.CENTRE_LINE) above + below else tallest
        val natural = ceil(naturalExact).toInt()
        val height = natural.coerceIn(inner.minHeight, inner.maxHeight)
        // Alignment uses exact extents; the residual follows the child through its frame node.
        val exactHeight = (f.height?.let { it - f.padTop - f.padBottom } ?: max(inner.minHeight.toFloat(), naturalExact)).coerceIn(0f, inner.maxHeight.toFloat())
        val line = above + (exactHeight - (above + below)) / 2
        val ys = ms.map { m ->
            when (align) {
                Align.TOP -> 0f
                Align.CENTER -> (exactHeight - m.exactHeight) / 2f
                Align.BOTTOM -> exactHeight - m.exactHeight
                Align.CENTRE_LINE -> line - m.centre
            }
        }
        Arranged(Measured(Size(width, height), if (align == Align.CENTRE_LINE) line else exactHeight / 2f,
            exactWidth, exactHeight),
            xs.indices.map { Placement.exact(xs[it], ys[it]) }, ms)
    }

    /** Children over each other, each placed at the fractions [ax], [ay] of the room left (0 start, 0.5 middle, 1 end). */
    fun stack(f: Frame, c: Constraints, ax: Float, ay: Float, children: List<Child>, centreLine: Boolean = false): Arranged = framed(f, c) { inner ->
        val ms = children.map { it.measure(inner.loose()) }
        val w = max(inner.minWidth, ms.maxOfOrNull { it.size.width } ?: 0).coerceAtMost(inner.maxWidth)
        val h = max(inner.minHeight, ms.maxOfOrNull { it.size.height } ?: 0).coerceAtMost(inner.maxHeight)
        val ew = (f.width?.let { max(0f, it - f.padStart - f.padEnd) } ?: max(inner.minWidth.toFloat(), ms.maxOfOrNull { it.exactWidth } ?: 0f)).coerceAtMost(inner.maxWidth.toFloat())
        val eh = (f.height?.let { max(0f, it - f.padTop - f.padBottom) } ?: max(inner.minHeight.toFloat(), ms.maxOfOrNull { it.exactHeight } ?: 0f)).coerceAtMost(inner.maxHeight.toFloat())
        Arranged(Measured(Size(w, h), eh / 2f, ew, eh), ms.map {
            Placement.exact((ew - it.exactWidth) * ax, if (centreLine) eh / 2f - it.centre else (eh - it.exactHeight) * ay)
        }, ms)
    }

    /** A box holds its children at its top start, inside its padding. */
    fun box(f: Frame, c: Constraints, children: List<Child>) = stack(f, c, 0f, 0f, children)

    /** A stack's `align` as the fractions [stack] takes. */
    fun stackAlign(name: String): Pair<Float, Float> {
        val y = when { name.startsWith("top") -> 0f; name.startsWith("bottom") -> 1f; else -> 0.5f }
        val x = when { name.endsWith("start") -> 0f; name.endsWith("end") -> 1f; else -> 0.5f }
        return x to y
    }
}
