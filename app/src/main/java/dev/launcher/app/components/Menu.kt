package dev.launcher.app.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import dev.launcher.app.design.ColorKey
import dev.launcher.app.design.Design
import dev.launcher.app.design.MaterialKey
import dev.launcher.app.design.NumberKey
import dev.launcher.app.design.TextKey
import dev.launcher.app.drawer.LabelPainter
import dev.launcher.app.home.WidgetSize
import dev.launcher.app.theme.Appearance
import dev.launcher.app.theme.Fonts

/**
 * The tokens of one menu's look (step 3 of docs/DESIGN_SYSTEM_PLAN.md): every menu reads its own set, so a theme can give
 * each its own; the iOS 27 theme points them all at the kit's Home Screen Quick Actions (`comp.home.menu.*`).
 */
class MenuSpec(prefix: String) {
    val material = MaterialKey("$prefix.material")
    val corner = NumberKey("$prefix.corner")
    val width = NumberKey("$prefix.width")
    val row = NumberKey("$prefix.row")
    val padTop = NumberKey("$prefix.pad-top")
    val padBottom = NumberKey("$prefix.pad-bottom")
    val symbolX = NumberKey("$prefix.symbol-x")
    val labelX = NumberKey("$prefix.label-x")
    val type = TextKey("$prefix.type")
    val label = ColorKey("$prefix.label")
    val destructive = ColorKey("$prefix.destructive")
    val press = ColorKey("$prefix.press")

    val all get() = listOf(material, corner, width, row, padTop, padBottom, symbolX, labelX, type, label, destructive, press).map { it.name }

    companion object {
        /** Home's long-press and Edit menus (the kit's Home Screen Quick Actions). */
        val HOME = MenuSpec("comp.home.menu")
        /** The App Switcher's menu of an app (its name above its card). */
        val SWITCHER = MenuSpec("comp.switcher.menu")
    }
}

/**
 * A menu as iOS 27 draws one (the kit's Home Screen Quick Actions): rows of a symbol and a label on a glass panel that grows
 * out of the side of what it belongs to. This draws and lays out the panel's content; the panel's glass is the host's
 * ([draw]'s `surface`), since only the host knows what lies behind it (home under its blur, the App Switcher's cards).
 *
 * Laid out as the kit: [MenuSpec.width] wide (wider for a long label), rows of [MenuSpec.row] with the symbol first (a 20 pt
 * column [MenuSpec.symbolX] in) and the label at [MenuSpec.labelX]; no lines between rows; a row of choices (a segmented
 * row) or of widget sizes where an item asks for one.
 */
class MenuPainter(private val spec: MenuSpec, private val u: Float) {
    enum class Glyph { GRID, MINUS, INFO, PLUS, SLIDERS, STYLE, TRASH, LABEL, WALLPAPER, LOCK, CLOSE }

    /**
     * A menu row; with [sizes] it is iOS's row of widget sizes (glyphs shaped like each size, [current] filled) and
     * [onSize] runs for the one tapped.
     */
    class Item(val label: String, val icon: Drawable? = null, val glyph: Glyph? = null, val destructive: Boolean = false,
               val sizes: List<WidgetSize>? = null, val current: WidgetSize? = null, val onSize: ((WidgetSize) -> Unit)? = null,
               /** A segmented row of [choices] ([chosen] highlighted): [onChoice] runs for the one tapped; the menu stays open. */
               val choices: List<String>? = null, var chosen: Int = 0, val onChoice: ((Int) -> Unit)? = null,
               val action: () -> Unit = {})

    private fun pt(v: Float) = v * u

    var items: List<Item> = emptyList()
        private set
    /** Where the panel rests (host coordinates), and whether it lies below what it belongs to. */
    val panel = RectF()
    var below = true
        private set
    private var panelW = 0f
    private var rowTop = FloatArray(0)
    private var rowHeight = FloatArray(0)

    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = pt(1.6f); strokeCap = Paint.Cap.ROUND }
    private val type = Design.text(spec.type)
    private val labels = LabelPainter(pt(type.sizePt), Color.WHITE, Paint.Align.LEFT, Fonts.text(type.weight)).toned { Design.color(spec.label) }
    private val choiceText = LabelPainter(pt(14f), Color.WHITE, Paint.Align.CENTER, Fonts.text(500)).toned { Appearance.label }
    private val choiceFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val press = Paint()
    private val fallback = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sizeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val sizeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt(); style = Paint.Style.STROKE }
    private val r = RectF()
    private val panelMatrix = Matrix()

    private val rowH get() = pt(Design.num(spec.row))
    private val sizesH get() = pt(44f)
    private val padTop get() = pt(Design.num(spec.padTop))
    private val padBottom get() = pt(Design.num(spec.padBottom))
    val radius get() = pt(Design.num(spec.corner))
    private val symbolX get() = pt(Design.num(spec.symbolX))
    private val labelX get() = pt(Design.num(spec.labelX))
    val material get() = Design.material(spec.material)

    /**
     * Lays [menu] out for what it belongs to at [anchor] (host coordinates), within [bounds]: below it if it fits there,
     * else above, [gapPt] away; centred on it, or from its left edge when [fromLeft] and it is on the left half.
     */
    fun layout(menu: List<Item>, anchor: RectF, bounds: RectF, gapPt: Float, fromLeft: Boolean) {
        items = menu
        labels.clear()
        val longest = menu.maxOfOrNull { labels.paint.measureText(it.label) } ?: 0f
        panelW = maxOf(pt(Design.num(spec.width)), longest + labelX + pt(26f)).coerceAtMost(bounds.width())
        rowTop = FloatArray(menu.size)
        rowHeight = FloatArray(menu.size)
        var y = padTop
        for ((i, it) in menu.withIndex()) {
            rowTop[i] = y
            rowHeight[i] = if (it.sizes != null) sizesH else rowH
            y += rowHeight[i]
        }
        val h = y + padBottom
        val gap = pt(gapPt)
        below = anchor.bottom + gap + h < bounds.bottom
        val top = if (below) anchor.bottom + gap else anchor.top - gap - h
        val left = if (fromLeft && anchor.centerX() < bounds.centerX()) anchor.left.coerceIn(bounds.left, bounds.right - panelW)
                   else (anchor.centerX() - panelW / 2f).coerceIn(bounds.left, bounds.right - panelW)
        panel.set(left, top, left + panelW, top + h)
    }

    /** The transform the panel is drawn with at presence [k] (it grows out of the side of what it belongs to). */
    fun matrixAt(k: Float, out: Matrix): Matrix {
        val ps = 0.8f + 0.2f * k
        val px = panel.centerX()
        val py = if (below) panel.top else panel.bottom
        out.reset()
        out.postTranslate(-px, -py)
        out.postScale(ps, ps)
        out.postTranslate(px, py)
        return out
    }

    /**
     * The panel at presence [k] (its spring's value: it may overshoot 1): its glass by [surface] (the canvas, the panel's
     * rectangle, its corner, the panel's transform to the host's coordinates, its opacity; false when it could not, then a
     * plain fill stands in), then the rows, [pressed] lit (-1 none), a choice row's highlight at [choiceAt].
     */
    fun draw(c: Canvas, k: Float, pressed: Int, choiceAt: Float, surface: (Canvas, RectF, Float, Matrix, Float) -> Boolean) {
        if (items.isEmpty()) return
        val kk = k.coerceIn(0f, 1f)
        if (kk <= 0.003f) return
        press.color = Design.color(spec.press)
        fallback.color = Appearance.mix(0xF2F2F2F7.toInt(), 0xD92C2C2E.toInt())
        matrixAt(k, panelMatrix)
        c.save()
        c.concat(panelMatrix)
        // The glass outside the fading layer (an effect's input is cut by a smaller layer).
        val drawn = surface(c, panel, radius, panelMatrix, kk)
        val layer = c.saveLayerAlpha(panel.left - 2, panel.top - 2, panel.right + 2, panel.bottom + 2, (255 * kk).toInt())
        if (!drawn) c.drawRoundRect(panel, radius, radius, fallback)
        for ((i, item) in items.withIndex()) {
            val top = panel.top + rowTop[i]
            val rh = rowHeight[i]
            val choices = item.choices
            if (choices != null) { drawChoices(c, choices, top, rh, choiceAt); continue }
            val sizes = item.sizes
            if (sizes != null) { drawSizes(c, item, sizes, top, rh); continue }
            if (i == pressed) {
                r.set(panel.left + pt(8f), top, panel.right - pt(8f), top + rh)
                c.drawRoundRect(r, pt(14f), pt(14f), press)
            }
            // iOS 27: the symbol first (a 20 pt column 26 pt in), the label 14 pt after it.
            drawGlyph(c, item, panel.left + symbolX, top + rh / 2f)
            labels.draw(c, item.label, item.label, panel.left + labelX, labels.baselineFor(top + rh / 2f), panelW - labelX - pt(26f),
                color = if (item.destructive) Design.color(spec.destructive) else null)
        }
        c.restoreToCount(layer)
        c.restore()
    }

    /** The row at [x], [y] (host coordinates; the nearest one in the padding above or below), or -1 outside the panel. */
    fun rowAt(x: Float, y: Float): Int {
        if (!panel.contains(x, y)) return -1
        val py = y - panel.top
        for (i in rowTop.indices) if (py < rowTop[i] + rowHeight[i]) return i
        return (items.size - 1).coerceAtLeast(0)
    }

    /** Which of [n] choices of a segmented row is at [x]. */
    fun choiceIndexAt(x: Float, n: Int): Int = ((x - panel.left) / panel.width() * n).toInt().coerceIn(0, n - 1)

    /** Which of [n] widget sizes is nearest [x]. */
    fun sizeIndexAt(x: Float, n: Int): Int = (0 until n).minByOrNull { kotlin.math.abs(sizeSlot(n, it) - x) } ?: 0

    /** The widget sizes spread over the row 17 pt in from each side (iOS 27's widget row). */
    private fun sizeSlot(n: Int, i: Int): Float {
        val inset = pt(17f) + pt(19f)
        return if (n <= 1) panel.centerX() else panel.left + inset + (panel.width() - 2 * inset) * i / (n - 1)
    }

    /** A segmented row: the choices side by side, the chosen one on a capsule that glides to a new choice ([at]). */
    private fun drawChoices(c: Canvas, choices: List<String>, top: Float, rh: Float, at: Float) {
        val n = choices.size
        val inset = pt(6f)
        val segW = (panel.width() - 2 * inset) / n
        val pos = at.coerceIn(0f, (n - 1).toFloat())
        r.set(panel.left + inset + pos * segW, top + inset, panel.left + inset + (pos + 1) * segW, top + rh - inset)
        val pf = Appearance.pressFill
        choiceFill.color = pf
        choiceFill.alpha = (Color.alpha(pf) * 2.2f).toInt().coerceAtMost(255)
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, choiceFill)
        for ((j, label) in choices.withIndex()) {
            val cx = panel.left + inset + (j + 0.5f) * segW
            val near = (1f - kotlin.math.abs(pos - j)).coerceIn(0f, 1f)
            choiceText.draw(c, "c$label", label, cx, choiceText.baselineFor(top + rh / 2f), segW - pt(4f), (150 + 105 * near).toInt())
        }
    }

    /** iOS's widget size row: one glyph per size, shaped like it (small square, wide, large square, tall), the current filled. */
    private fun drawSizes(c: Canvas, item: Item, sizes: List<WidgetSize>, top: Float, rh: Float) {
        val unit = pt(5.2f)
        sizeStroke.strokeWidth = pt(1.6f)
        for ((j, s) in sizes.withIndex()) {
            val w = s.spanX * unit
            val h = s.spanY * unit
            val cx = sizeSlot(sizes.size, j)
            val cy = top + rh / 2f
            r.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            val rad = pt(2.4f)
            sizeFill.color = Appearance.label
            sizeStroke.color = Appearance.secondaryLabel
            if (s == item.current) c.drawRoundRect(r, rad, rad, sizeFill)
            else c.drawRoundRect(r, rad, rad, sizeStroke)
        }
    }

    private fun drawGlyph(c: Canvas, item: Item, cx: Float, cy: Float) {
        val icon = item.icon
        if (icon != null) {
            val s = pt(11f)
            icon.setBounds((cx - s).toInt(), (cy - s).toInt(), (cx + s).toInt(), (cy + s).toInt())
            icon.draw(c)
            return
        }
        glyphPaint.color = if (item.destructive) Design.color(spec.destructive) else Design.color(spec.label)
        val g = pt(9f)
        when (item.glyph) {
            Glyph.GRID -> {
                glyphPaint.style = Paint.Style.STROKE
                val q = g * 0.85f
                val gap = pt(2.2f)
                for (dx in 0..1) for (dy in 0..1) {
                    val l = cx - q + dx * (q + gap / 2f) - gap / 4f
                    val t = cy - q + dy * (q + gap / 2f) - gap / 4f
                    c.drawRoundRect(l, t, l + q - gap / 2f, t + q - gap / 2f, pt(1.5f), pt(1.5f), glyphPaint)
                }
            }
            Glyph.MINUS -> {
                glyphPaint.style = Paint.Style.STROKE
                c.drawCircle(cx, cy, g, glyphPaint)
                c.drawLine(cx - g * 0.5f, cy, cx + g * 0.5f, cy, glyphPaint)
            }
            Glyph.INFO -> {
                glyphPaint.style = Paint.Style.STROKE
                c.drawCircle(cx, cy, g, glyphPaint)
                c.drawLine(cx, cy - g * 0.1f, cx, cy + g * 0.5f, glyphPaint)
                glyphPaint.style = Paint.Style.FILL
                c.drawCircle(cx, cy - g * 0.45f, pt(1.1f), glyphPaint)
            }
            Glyph.SLIDERS -> {
                glyphPaint.style = Paint.Style.STROKE
                c.drawLine(cx - g, cy - g * 0.45f, cx + g, cy - g * 0.45f, glyphPaint)
                c.drawLine(cx - g, cy + g * 0.45f, cx + g, cy + g * 0.45f, glyphPaint)
                glyphPaint.style = Paint.Style.FILL
                c.drawCircle(cx + g * 0.35f, cy - g * 0.45f, pt(2.4f), glyphPaint)
                c.drawCircle(cx - g * 0.35f, cy + g * 0.45f, pt(2.4f), glyphPaint)
            }
            Glyph.STYLE -> {
                glyphPaint.style = Paint.Style.STROKE
                c.drawCircle(cx, cy, g, glyphPaint)
                glyphPaint.style = Paint.Style.FILL
                r.set(cx - g, cy - g, cx + g, cy + g)
                c.drawArc(r, 90f, 180f, true, glyphPaint)
            }
            Glyph.TRASH -> {
                glyphPaint.style = Paint.Style.STROKE
                c.drawLine(cx - g, cy - g * 0.62f, cx + g, cy - g * 0.62f, glyphPaint)
                c.drawLine(cx - g * 0.3f, cy - g * 0.95f, cx + g * 0.3f, cy - g * 0.95f, glyphPaint)
                r.set(cx - g * 0.72f, cy - g * 0.62f, cx + g * 0.72f, cy + g)
                c.drawRoundRect(r, pt(2f), pt(2f), glyphPaint)
            }
            Glyph.PLUS -> {
                glyphPaint.style = Paint.Style.STROKE
                c.drawRoundRect(cx - g, cy - g, cx + g, cy + g, pt(3f), pt(3f), glyphPaint)
                c.drawLine(cx - g * 0.5f, cy, cx + g * 0.5f, cy, glyphPaint)
                c.drawLine(cx, cy - g * 0.5f, cx, cy + g * 0.5f, glyphPaint)
            }
            Glyph.WALLPAPER -> {
                // A picture: its frame, a hill and a sun.
                glyphPaint.style = Paint.Style.STROKE
                c.drawRoundRect(cx - g, cy - g * 0.8f, cx + g, cy + g * 0.8f, pt(2.5f), pt(2.5f), glyphPaint)
                c.drawLine(cx - g, cy + g * 0.55f, cx - g * 0.15f, cy - g * 0.1f, glyphPaint)
                c.drawLine(cx - g * 0.15f, cy - g * 0.1f, cx + g, cy + g * 0.7f, glyphPaint)
                glyphPaint.style = Paint.Style.FILL
                c.drawCircle(cx + g * 0.45f, cy - g * 0.35f, pt(1.6f), glyphPaint)
            }
            Glyph.LOCK -> {
                // A padlock: its shackle and body.
                glyphPaint.style = Paint.Style.STROKE
                r.set(cx - g * 0.5f, cy - g, cx + g * 0.5f, cy)
                c.drawArc(r, 180f, 180f, false, glyphPaint)
                c.drawLine(cx - g * 0.5f, cy - g * 0.5f, cx - g * 0.5f, cy - g * 0.1f, glyphPaint)
                c.drawLine(cx + g * 0.5f, cy - g * 0.5f, cx + g * 0.5f, cy - g * 0.1f, glyphPaint)
                glyphPaint.style = Paint.Style.FILL
                c.drawRoundRect(cx - g * 0.8f, cy - g * 0.15f, cx + g * 0.8f, cy + g, pt(2f), pt(2f), glyphPaint)
            }
            Glyph.CLOSE -> {
                // A cross in a circle.
                glyphPaint.style = Paint.Style.STROKE
                c.drawCircle(cx, cy, g, glyphPaint)
                c.drawLine(cx - g * 0.4f, cy - g * 0.4f, cx + g * 0.4f, cy + g * 0.4f, glyphPaint)
                c.drawLine(cx + g * 0.4f, cy - g * 0.4f, cx - g * 0.4f, cy + g * 0.4f, glyphPaint)
            }
            Glyph.LABEL -> {
                // A small icon with a text line under it (names under icons).
                glyphPaint.style = Paint.Style.STROKE
                c.drawRoundRect(cx - g * 0.55f, cy - g, cx + g * 0.55f, cy + g * 0.1f, pt(2.5f), pt(2.5f), glyphPaint)
                c.drawLine(cx - g * 0.8f, cy + g * 0.7f, cx + g * 0.8f, cy + g * 0.7f, glyphPaint)
            }
            null -> {}
        }
    }
}
