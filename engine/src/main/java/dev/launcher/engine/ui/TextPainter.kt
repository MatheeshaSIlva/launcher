package dev.launcher.engine.ui

import android.graphics.Canvas
import android.graphics.Paint
import dev.launcher.engine.expr.Value
import dev.launcher.engine.layout.Measured
import dev.launcher.engine.layout.Size
import dev.launcher.engine.token.Tokens
import dev.launcher.engine.ui.motion.RollMotion
import kotlin.math.ceil

/**
 * `text`'s painter, one per node with its own Paint: the content in the theme's family (`font`, default `"text"`, through
 * [Tokens.typeface] as `Fonts.of` gives it, so `"text"` at 600 is StatusBarView's `Fonts.text(600)`), `size` in points,
 * `weight`, `color` (a `#RRGGBB` literal or a colour value), `tnum` as the font feature. Its centre line is the middle of
 * the capitals, `baseline - CAP * size / 2`, StatusBarView's rule (`timeBaseline`). `blend="knockout"` erases what is
 * under the text instead of painting it, at full strength whatever `color`'s alpha (the validator requires a `layer`
 * ancestor, so it cuts only within that): StatusBarView's battery number. With `roll`, a change of content rolls
 * ([RollMotion]): by characters where [RollEligibility] has verified both strings, else whole; at rest it draws whole.
 */
class TextPainter(private val tokens: Tokens) : Painter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** Paints onto a GPU recording and onto any other canvas (the warm-up's evidence, E2 slice 6). */
    var hardwarePaints = 0; private set
    var softwarePaints = 0; private set

    override fun key(node: HostNode): Any = Key(content(node), node.length("size") ?: DEFAULT_SIZE_PX, weight(node),
        font(node), color(node), tnum(node), align(node), knockout(node), tokens.version,
        node.nodeMotion?.roll?.takeIf { it.moving }?.glyphs?.map { Triple(it.text, it.y, it.offset) })

    override fun measure(node: HostNode, maxWidth: Int, maxHeight: Int): Measured {
        val p = prepared(node)
        val fm = p.fontMetrics
        val baseline = -fm.ascent
        val exact = p.measureText(content(node))
        roll(node, p)
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
        val r = node.nodeMotion?.roll
        if (r != null && r.moving) {
            val baseline = -p.fontMetrics.ascent
            val rise = RollMotion.RISE * p.textSize
            val alpha = p.alpha
            for (g in r.glyphs) {
                val a = g.alpha
                if (a <= 0f) continue
                p.alpha = (alpha * a).toInt()
                canvas.drawText(g.text, x(node, width, g.width) + g.offset, baseline + g.y * rise, p)
            }
            p.alpha = alpha
            return
        }
        // At rest the whole string: the same picture as its characters where they were verified.
        if (r != null && r.text == text) roll(node, p)
        canvas.drawText(text, x(node, width, p.measureText(text)), -p.fontMetrics.ascent, p)
    }

    private fun x(node: HostNode, width: Float, w: Float) = when (align(node)) { "center" -> (width - w) / 2f; "end" -> width - w; else -> 0f }

    /** Its roll told what it shows now: rolling from the last text when it changed (a measure sees every change). */
    private fun roll(node: HostNode, p: Paint) {
        val m = node.nodeMotion ?: return
        val r = m.roll ?: return
        val text = content(node)
        val byCharacters = ELIGIBILITY.verdict(text, p, tokens.version) == RollEligibility.Verdict.CLUSTERS &&
            (r.text == null || r.text == text || ELIGIBILITY.verdict(r.text!!, p, tokens.version) == RollEligibility.Verdict.CLUSTERS)
        m.rollEpsilon = 0.5f / (RollMotion.RISE * p.textSize).coerceAtLeast(1f)
        val wasMoving = r.moving
        r.to(text, RollEligibility.positions(text, p), byCharacters, m.rollCurve(), m.stagger(), m.now(),
            RollEligibility.Config.of(p, tokens.version))
        if (r.moving && !wasMoving) node.onMoving(node)
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
        val tnum: Boolean, val align: String, val knockout: Boolean, val tokens: Int, val roll: Any?)

    companion object {
        /** The capitals' height as a share of the text size (Inter; StatusBarView's `0.727f`). */
        const val CAP = 0.727f
        /** `text` with no `size` (components should always give one). */
        const val DEFAULT_SIZE_PX = 17f
        const val DEFAULT_COLOR = 0xFFFFFFFF.toInt()
        /** Verdicts on which strings roll by characters, for every text: one worker, bounded. */
        private val ELIGIBILITY by lazy { RollEligibility() }
        private val ERASE by lazy { android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }

        /** The centre line of text whose baseline is [baseline] px from its top, at [sizePx]. */
        fun centreLine(baseline: Float, sizePx: Float) = baseline - CAP * sizePx / 2f
    }
}
