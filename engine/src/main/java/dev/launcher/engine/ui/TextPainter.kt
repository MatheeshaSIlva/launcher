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
    private var directionText: String? = null
    private var directionRtl = false
    private data class FadeKey(val width: Float, val length: Float, val color: Int, val rtl: Boolean, val start: Int)
    private var fadeKey: FadeKey? = null
    private var fadeShader: android.graphics.Shader? = null
    /** Paints onto a GPU recording and onto any other canvas (the warm-up's evidence, E2 slice 6). */
    var hardwarePaints = 0; private set
    var softwarePaints = 0; private set

    override fun key(node: HostNode): Any {
        scrolling(node, prepared(node), node.drawnWidth)
        decoding(node)
        return listOf(node.scroll.offset, if (node.hasDecode) node.decode.text else null, node.value("overflow"), node.value("fade"), Key(content(node), node.length("size") ?: DEFAULT_SIZE_PX, weight(node),
        font(node), color(node), tnum(node), align(node), knockout(node), tokens.version,
        node.nodeMotion?.roll?.takeIf { it.moving }?.glyphs?.map { Triple(it.text, it.y, it.offset) }))
    }

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
        decoding(node)
        // What it shows now: a decoding text's scramble (its layout keeps the real text's width).
        val text = if (node.hasDecode) node.decode.text else content(node)
        scrolling(node, p, width)
        val fade = node.value("overflow") == Value.Str("fade") && p.measureText(text) > width
        val rtl = rtl(text)
        val saved = canvas.save()
        if (fade || node.value("scroll") == Value.Bool.TRUE || node.scroll.offset != 0f)
            canvas.clipRect(0f, 0f, width, height)
        if (fade) {
            val length = (node.length("fade")?.takeIf { it.isFinite() && it >= 0f } ?: node.dp(12.0)).coerceIn(0f, width)
            if (length > 0f) {
                val color = p.color or 0xFF000000.toInt()
                // Scrolled, the start edge fades too, as far as the title has moved a fade's length (T-015: a scrolled
                // title was cut hard at its start); at rest it is crisp, so nothing pops as the scroll starts or ends.
                val start = ((kotlin.math.abs(node.scroll.offset) / length).coerceIn(0f, 1f) * 64f).toInt()
                val key = FadeKey(width, length, color, rtl, start)
                if (fadeKey != key) {
                    fadeKey = key
                    val clear = color and 0x00FFFFFF
                    val edge = (color and 0x00FFFFFF) or ((255 * (1f - start / 64f)).toInt() shl 24)
                    val f = (length / width).coerceIn(0f, 0.5f)
                    val (a, b) = if (rtl) clear to edge else edge to clear
                    fadeShader = android.graphics.LinearGradient(0f, 0f, width, 0f, intArrayOf(a, color, color, b),
                        floatArrayOf(0f, f, 1f - f, 1f), android.graphics.Shader.TileMode.CLAMP)
                }
                p.shader = fadeShader
            }
        }
        try {
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
            val textWidth = p.measureText(text)
            val base = if ((fade || node.hasScroll) && textWidth > width) { if (rtl) width - textWidth else 0f } else x(node, width, textWidth)
            canvas.drawText(text, base + node.scroll.offset, -p.fontMetrics.ascent, p)
        } finally { p.shader = null; canvas.restoreToCount(saved) }
    }

    private fun rtl(text: String): Boolean {
        if (directionText != text) { directionText = text; directionRtl = TitleScroll.rtl(text) }
        return directionRtl
    }
    /** E2.14d: its decode told the content now; a change starts the host's clock for it. */
    private fun decoding(node: HostNode) {
        if (!node.hasDecode) return
        val ms = (node.value("decode") as? Value.Num)?.value?.toFloat() ?: 0f
        val glyphs = (node.value("decode-glyphs") as? Value.Str)?.value ?: ""
        if (node.decode.configure(content(node), ms, glyphs, node.motionContext.reduced(), node.motionContext.now()) && node.decode.busy)
            node.recurringChanged()
    }

    private fun scrolling(node: HostNode, p: Paint, width: Float) {
        if ("scroll" !in node.bindings) return
        val changed = node.scroll.configure(content(node), p.measureText(content(node)), width.coerceAtLeast(0f), rtl(content(node)),
            node.dp(24.0), node.motionContext.now())
        if (changed) node.recurringChanged()
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
            RollEligibility.Config.of(p, 0))   // its geometry only: a colour's crossfade moves the tokens' version, not a glyph
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
