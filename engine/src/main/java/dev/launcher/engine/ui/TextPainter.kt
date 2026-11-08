package dev.launcher.engine.ui

import android.graphics.Canvas
import android.graphics.Paint
import dev.launcher.engine.expr.Value
import dev.launcher.engine.layout.Measured
import dev.launcher.engine.layout.Size
import dev.launcher.engine.token.Tokens
import kotlin.math.ceil

/**
 * `text`'s painter, one per node with its own Paint: the content in the theme's family (`font`, default `"text"`, through
 * [Tokens.typeface] as `Fonts.of` gives it, so `"text"` at 600 is StatusBarView's `Fonts.text(600)`), `size` in points,
 * `weight`, `color` (a `#RRGGBB` literal or a colour value), `tnum` as the font feature. Its centre line is the middle of
 * the capitals, `baseline - CAP * size / 2`, StatusBarView's rule (`timeBaseline`). `blend="knockout"` erases what is
 * under the text instead of painting it, at full strength whatever `color`'s alpha (the validator requires a `layer`
 * ancestor, so it cuts only within that): StatusBarView's battery number.
 */
class TextPainter(private val tokens: Tokens) : Painter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** Paints onto a GPU recording and onto any other canvas (the warm-up's evidence, E2 slice 6). */
    var hardwarePaints = 0; private set
    var softwarePaints = 0; private set

    override fun key(node: HostNode): Any = Key(content(node), node.length("size") ?: DEFAULT_SIZE_PX, weight(node),
        font(node), color(node), tnum(node), align(node), knockout(node), tokens.version)

    override fun measure(node: HostNode, maxWidth: Int, maxHeight: Int): Measured {
        val p = prepared(node)
        val fm = p.fontMetrics
        val baseline = -fm.ascent
        val exact = p.measureText(content(node))
        val h = fm.descent - fm.ascent
        return Measured(Size(ceil(exact).toInt(), ceil(h).toInt()), centreLine(baseline, p.textSize), exact, h)
    }

    override fun paint(canvas: Canvas, node: HostNode, width: Int, height: Int) =
        paint(canvas, node, width.toFloat(), height.toFloat())

    /** At the node's exact width: a centred number lands on the body's exact middle (64.575 px wide, not 65). */
    override fun paint(canvas: Canvas, node: HostNode, width: Float, height: Float) {
        if (canvas.isHardwareAccelerated) hardwarePaints++ else softwarePaints++
        val p = prepared(node)
        val text = content(node)
        val w = p.measureText(text)
        val x = when (align(node)) { "center" -> (width - w) / 2f; "end" -> width - w; else -> 0f }
        canvas.drawText(text, x, -p.fontMetrics.ascent, p)
    }

    private fun prepared(node: HostNode): Paint = paint.apply {
        textSize = node.length("size") ?: DEFAULT_SIZE_PX
        typeface = tokens.typeface(font(node), weight(node), textSize)
        if (knockout(node)) { color = 0xFF000000.toInt(); xfermode = ERASE } else { color = color(node); xfermode = null }
        val features = if (tnum(node)) "'tnum'" else null
        if (fontFeatureSettings != features) fontFeatureSettings = features
    }

    private fun content(n: HostNode) = when (val v = n.value("content")) {
        is Value.Str -> v.value
        is Value.Num -> if (v.value % 1.0 == 0.0) v.value.toLong().toString() else v.value.toString()
        is Value.Null -> ""
        else -> v.toString()
    }
    private fun weight(n: HostNode) = n.number("weight")?.toInt() ?: 400
    private fun font(n: HostNode) = (n.value("font") as? Value.Str)?.value ?: "text"
    private fun tnum(n: HostNode) = (n.value("tnum") as? Value.Bool)?.value == true
    private fun align(n: HostNode) = (n.value("align") as? Value.Str)?.value ?: "start"
    private fun knockout(n: HostNode) = (n.value("blend") as? Value.Str)?.value == "knockout"
    private fun color(n: HostNode): Int = when (val v = n.value("color")) {
        is Value.Color -> v.argb
        is Value.Str -> runCatching { android.graphics.Color.parseColor(v.value) }.getOrDefault(DEFAULT_COLOR)
        else -> DEFAULT_COLOR
    }

    private data class Key(val text: String, val size: Float, val weight: Int, val font: String, val color: Int,
        val tnum: Boolean, val align: String, val knockout: Boolean, val tokens: Int)

    companion object {
        /** The capitals' height as a share of the text size (Inter; StatusBarView's `0.727f`). */
        const val CAP = 0.727f
        /** `text` with no `size` (components should always give one). */
        const val DEFAULT_SIZE_PX = 17f
        const val DEFAULT_COLOR = 0xFFFFFFFF.toInt()
        private val ERASE by lazy { android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }

        /** The centre line of text whose baseline is [baseline] px from its top, at [sizePx]. */
        fun centreLine(baseline: Float, sizePx: Float) = baseline - CAP * sizePx / 2f
    }
}
