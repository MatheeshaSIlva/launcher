package dev.launcher.app.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import dev.launcher.app.design.ChoiceKey
import dev.launcher.app.design.ColorKey
import dev.launcher.app.design.Design
import dev.launcher.app.design.MaterialKey
import dev.launcher.app.design.NumberKey
import dev.launcher.app.design.TextKey
import dev.launcher.app.design.applyTo
import dev.launcher.app.home.WidgetSize
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The Pixel popups' tokens (`comp.pxmenu.*`: Android 16's long-press menus, drawn by [PixelMenu] when a theme picks
 * `sys.layout.menu` = "pixel"). Measured on the Android 17 emulator's Pixel Launcher (tools/shots/pxref/icon_menu*.png,
 * home_menu*.png); lengths in points (dp under the Pixel theme's scale).
 */
object PxMenuTokens {
    val FILL = ColorKey("comp.pxmenu.fill")
    val LABEL = ColorKey("comp.pxmenu.label")
    val PRESS = ColorKey("comp.pxmenu.press")
    val TYPE = TextKey("comp.pxmenu.type")
    val WIDTH = NumberKey("comp.pxmenu.width")
    val ROW = NumberKey("comp.pxmenu.row")
    val CORNER = NumberKey("comp.pxmenu.corner")
    val GAP = NumberKey("comp.pxmenu.gap")
    val ARROW_WIDTH = NumberKey("comp.pxmenu.arrow-width")
    val ARROW_HEIGHT = NumberKey("comp.pxmenu.arrow-height")
    val ARROW_OFFSET = NumberKey("comp.pxmenu.arrow-offset")
    val ICON_GAP = NumberKey("comp.pxmenu.icon-gap")
    val SYMBOL_X = NumberKey("comp.pxmenu.symbol-x")
    val SYMBOL = NumberKey("comp.pxmenu.symbol")
    val LABEL_X = NumberKey("comp.pxmenu.label-x")
    val SHORTCUT_ICON = NumberKey("comp.pxmenu.shortcut-icon")
    val SHORTCUT_X = NumberKey("comp.pxmenu.shortcut-x")
    val SHORTCUT_LABEL_X = NumberKey("comp.pxmenu.shortcut-label-x")
    val GROW_FROM = NumberKey("comp.pxmenu.grow-from")
    val OPTIONS_MATERIAL = MaterialKey("comp.pxmenu.options.material")
    val OPTIONS_FILL = ColorKey("comp.pxmenu.options.fill")
    val OPTIONS_WIDTH = NumberKey("comp.pxmenu.options.width")

    /** The menus' layout (`sys.layout.menu`): "ios" (the item lifts, glass) or "pixel" (these popups). */
    val LAYOUT = ChoiceKey("sys.layout.menu")

    val ALL = listOf(FILL, LABEL, PRESS, TYPE, WIDTH, ROW, CORNER, GAP, ARROW_WIDTH, ARROW_HEIGHT, ARROW_OFFSET, ICON_GAP, SYMBOL_X,
        SYMBOL, LABEL_X, SHORTCUT_ICON, SHORTCUT_X, SHORTCUT_LABEL_X, GROW_FROM, OPTIONS_MATERIAL, OPTIONS_FILL, OPTIONS_WIDTH, LAYOUT).map { it.name }

    fun active(): Boolean = try { Design.choice(LAYOUT) == "pixel" } catch (_: Throwable) { false }
}

/**
 * Android 16's long-press popup as a Pixel shows it: the app's actions in one rounded container and its shortcuts in
 * another (the shortcuts nearest the icon), 2 dp apart, with a small pointer at the icon's centre; above the icon when
 * there is room, its edge 26 dp from the pointer. Rows of 52 dp: a Material symbol, then the name in 14 sp. Home's options
 * popup ([layout] with `options`: a long press on empty space, a button's menu) is one translucent, blurred container
 * without a pointer. It grows out of the pointer (or the touch) as it fades in. Same items as the iOS menu
 * ([MenuPainter.Item]); a row of widget sizes or of choices is drawn as such.
 */
class PixelMenu(private val u: Float) {
    private fun pt(k: NumberKey) = Design.pt(k, u)

    /** The rows in the order shown (group by group); [rowAt] indexes this list. */
    var items: List<MenuPainter.Item> = emptyList()
        private set
    var options = false
        private set
    private val groups = ArrayList<RectF>()
    private var rowTop = FloatArray(0)
    private var rowGroup = IntArray(0)
    private var above = true
    private var hasArrow = false
    private var arrowGroup = 0
    private var tipX = 0f
    private var tipY = 0f
    private val outer = RectF()

    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val clip = Path()
    private val r = RectF()
    private val matrix = Matrix()

    val material get() = Design.material(PxMenuTokens.OPTIONS_MATERIAL)

    /**
     * Lays [menu] out for what it belongs to at [anchor] (its icon's square on top, its name below; host coordinates) within
     * [bounds]; [options]: home's options popup at a touch ([anchor] a small square round it).
     */
    fun layout(menu: List<MenuPainter.Item>, anchor: RectF, bounds: RectF, options: Boolean) {
        this.options = options
        Design.text(PxMenuTokens.TYPE).applyTo(text, u)
        val actions = menu.filter { it.icon == null }
        val shortcuts = menu.filter { it.icon != null }
        val rowH = pt(PxMenuTokens.ROW)
        val gap = pt(PxMenuTokens.GAP)
        val longest = menu.maxOfOrNull { text.measureText(it.label) } ?: 0f
        val w = (if (options) pt(PxMenuTokens.OPTIONS_WIDTH) else max(pt(PxMenuTokens.WIDTH), longest + pt(PxMenuTokens.SHORTCUT_LABEL_X) + 24f * u))
            .coerceAtMost(bounds.width())
        val parts = listOf(actions, shortcuts).filter { it.isNotEmpty() }
        val h = parts.sumOf { it.size } * rowH + (parts.size - 1).coerceAtLeast(0) * gap
        val ordered: List<List<MenuPainter.Item>>
        val top: Float
        val left: Float
        if (options) {
            hasArrow = false
            val g = 8f * u
            above = anchor.bottom + g + h > bounds.bottom
            top = if (above) (anchor.top - g - h).coerceAtLeast(bounds.top) else anchor.bottom + g
            left = (anchor.centerX() - w / 2f).coerceIn(bounds.left, bounds.right - w)
            ordered = parts
            tipX = anchor.centerX().coerceIn(left, left + w)
            tipY = if (above) top + h else top
        } else {
            hasArrow = true
            val arrowH = pt(PxMenuTokens.ARROW_HEIGHT)
            val iconGap = pt(PxMenuTokens.ICON_GAP)
            above = anchor.top - iconGap - arrowH - h >= bounds.top
            // The shortcuts lie nearest the icon.
            ordered = if (above) parts else parts.reversed()
            top = if (above) anchor.top - iconGap - arrowH - h else anchor.bottom + iconGap + arrowH
            val off = pt(PxMenuTokens.ARROW_OFFSET)
            val ax = anchor.centerX()
            left = (if (ax - off + w <= bounds.right) ax - off else ax + off - w).coerceIn(bounds.left, bounds.right - w)
            tipX = ax
            tipY = if (above) top + h + arrowH else top - arrowH
            arrowGroup = if (above) ordered.size - 1 else 0
        }
        items = ordered.flatten()
        groups.clear()
        rowTop = FloatArray(items.size)
        rowGroup = IntArray(items.size)
        var y = top
        var i = 0
        for ((gi, part) in ordered.withIndex()) {
            val gTop = y
            for (item in part) { rowTop[i] = y; rowGroup[i] = gi; y += rowH; i++ }
            groups += RectF(left, gTop, left + w, y)
            y += gap
        }
        outer.set(left, top, left + w, top + h)
    }

    /** Where the popup grows from: the pointer's tip (or the touch). */
    private fun pivotY() = tipY

    /**
     * The popup at presence [k] (its spring's value), [pressed] lit (-1 none), a choice row's highlight at [choiceAt].
     * [surface] draws the options popup's material (the host knows what is behind it); false: a plain fill stands in.
     */
    fun draw(c: Canvas, k: Float, pressed: Int, choiceAt: Float, surface: (Canvas, RectF, Float, Matrix, Float) -> Boolean) {
        if (items.isEmpty()) return
        val a = k.coerceIn(0f, 1f)
        if (a <= 0.003f) return
        val g = Design.num(PxMenuTokens.GROW_FROM)
        val s = g + (1f - g) * k
        matrix.reset()
        matrix.postScale(s, s, tipX, pivotY())
        c.save()
        c.concat(matrix)
        val radius = pt(PxMenuTokens.CORNER)
        val fillColor = Design.color(if (options) PxMenuTokens.OPTIONS_FILL else PxMenuTokens.FILL)
        for ((gi, rect) in groups.withIndex()) {
            val rr = minOf(radius, rect.height() / 2f)
            if (!(options && surface(c, rect, rr, matrix, a))) {
                fill.color = fillColor
                fill.alpha = (Color.alpha(fillColor) * a).roundToInt()
                c.drawRoundRect(rect, rr, rr, fill)
            }
            if (hasArrow && gi == arrowGroup) drawArrow(c, rect, fillColor, a)
        }
        val label = Design.color(PxMenuTokens.LABEL)
        val rowH = pt(PxMenuTokens.ROW)
        for ((i, item) in items.withIndex()) {
            val group = groups[rowGroup[i]]
            val top = rowTop[i]
            val cy = top + rowH / 2f
            if (i == pressed && item.sizes == null && item.choices == null) {
                // A state layer over the row, inside its container's rounded shape.
                c.save()
                clip.reset()
                val rr = minOf(radius, group.height() / 2f)
                clip.addRoundRect(group, rr, rr, Path.Direction.CW)
                c.clipPath(clip)
                fill.color = Design.color(PxMenuTokens.PRESS)
                fill.alpha = (Color.alpha(fill.color) * a).roundToInt()
                c.drawRect(group.left, top, group.right, top + rowH, fill)
                c.restore()
            }
            val sizes = item.sizes
            if (sizes != null) { drawSizes(c, item, sizes, group, cy, label, a); continue }
            val choices = item.choices
            if (choices != null) { drawChoices(c, choices, group, top, rowH, choiceAt, label, a); continue }
            val icon = item.icon
            val textX: Float
            if (icon != null) {
                // An app shortcut: its own icon, round, then its name.
                val size = pt(PxMenuTokens.SHORTCUT_ICON)
                val cx = group.left + pt(PxMenuTokens.SHORTCUT_X)
                val iw = icon.intrinsicWidth
                val ih = icon.intrinsicHeight
                icon.alpha = (255 * a).roundToInt()
                if (iw > 0 && ih > 0) {
                    val kk = size / max(iw, ih)
                    icon.setBounds(0, 0, iw, ih)
                    val save = c.save()
                    c.translate(cx - iw * kk / 2f, cy - ih * kk / 2f)
                    c.scale(kk, kk)
                    icon.draw(c)
                    c.restoreToCount(save)
                } else {
                    icon.setBounds((cx - size / 2f).roundToInt(), (cy - size / 2f).roundToInt(), (cx + size / 2f).roundToInt(), (cy + size / 2f).roundToInt())
                    icon.draw(c)
                }
                textX = group.left + pt(PxMenuTokens.SHORTCUT_LABEL_X)
            } else {
                drawGlyph(c, item.glyph, group.left + pt(PxMenuTokens.SYMBOL_X), cy, label, fillColor, a)
                textX = group.left + pt(PxMenuTokens.LABEL_X)
            }
            text.color = label
            text.alpha = (Color.alpha(label) * a).roundToInt()
            val shown = TextUtils.ellipsize(item.label, text, group.right - 16f * u - textX, TextUtils.TruncateAt.END)
            c.drawText(shown, 0, shown.length, textX, cy - (text.ascent() + text.descent()) / 2f, text)
        }
        c.restore()
    }

    /** The pointer: a small triangle from the container's edge to the icon, its tip rounded. */
    private fun drawArrow(c: Canvas, rect: RectF, color: Int, a: Float) {
        val hw = pt(PxMenuTokens.ARROW_WIDTH) / 2f
        val base = if (above) rect.bottom else rect.top
        val x = tipX.coerceIn(rect.left + hw + pt(PxMenuTokens.CORNER) * 0.5f, rect.right - hw - pt(PxMenuTokens.CORNER) * 0.5f)
        // Its sides go on into the container (hidden there) by a pointer's half width: where the container's corner curves
        // away above the base, they still meet it (a straight base left a sliver of background between the two).
        val h = kotlin.math.abs(tipY - base)
        val e = hw
        val wide = hw * (1f + e / h.coerceAtLeast(1f))
        val inside = base + (if (above) -e else e)
        path.reset()
        path.moveTo(x - wide, inside)
        path.lineTo(x + wide, inside)
        path.lineTo(x, tipY)
        path.close()
        fill.color = color
        fill.alpha = (Color.alpha(color) * a).roundToInt()
        fill.pathEffect = android.graphics.CornerPathEffect(1.5f * u)
        c.drawPath(path, fill)
        fill.pathEffect = null
    }

    /** The row (index into [items]) at [x], [y] (host coordinates), or -1. */
    fun rowAt(x: Float, y: Float): Int {
        val rowH = pt(PxMenuTokens.ROW)
        for (i in items.indices) {
            val g = groups[rowGroup[i]]
            if (x >= g.left && x <= g.right && y >= rowTop[i] && y < rowTop[i] + rowH) return i
        }
        return -1
    }

    /** True if ([x], [y]) is on the popup (a touch there never closes it). */
    fun contains(x: Float, y: Float) = groups.any { it.contains(x, y) }

    fun choiceIndexAt(i: Int, x: Float, n: Int): Int {
        val g = groups.getOrNull(rowGroup.getOrElse(i) { 0 }) ?: return 0
        return ((x - g.left) / g.width() * n).toInt().coerceIn(0, n - 1)
    }

    fun sizeIndexAt(i: Int, x: Float, n: Int): Int {
        val g = groups.getOrNull(rowGroup.getOrElse(i) { 0 }) ?: return 0
        return (0 until n).minByOrNull { kotlin.math.abs(sizeSlot(g, n, it) - x) } ?: 0
    }

    private fun sizeSlot(g: RectF, n: Int, i: Int): Float {
        val inset = pt(PxMenuTokens.SYMBOL_X) + 8f * u
        return if (n <= 1) g.centerX() else g.left + inset + (g.width() - 2 * inset) * i / (n - 1)
    }

    /** A widget's sizes: one shape per size (as its proportions), the current one filled. */
    private fun drawSizes(c: Canvas, item: MenuPainter.Item, sizes: List<WidgetSize>, g: RectF, cy: Float, color: Int, a: Float) {
        val unit = 5f * u
        for ((j, s) in sizes.withIndex()) {
            val w = s.spanX * unit
            val h = s.spanY * unit
            val cx = sizeSlot(g, sizes.size, j)
            r.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            glyph.color = color
            glyph.alpha = (Color.alpha(color) * a).roundToInt()
            glyph.style = if (s == item.current) Paint.Style.FILL else Paint.Style.STROKE
            glyph.strokeWidth = 1.6f * u
            c.drawRoundRect(r, 2.4f * u, 2.4f * u, glyph)
        }
    }

    /** A row of choices: side by side, the chosen one on a lighter capsule that glides to a new choice ([at]). */
    private fun drawChoices(c: Canvas, choices: List<String>, g: RectF, top: Float, rh: Float, at: Float, color: Int, a: Float) {
        val n = choices.size
        val inset = 6f * u
        val segW = (g.width() - 2 * inset) / n
        val pos = at.coerceIn(0f, (n - 1).toFloat())
        r.set(g.left + inset + pos * segW, top + inset, g.left + inset + (pos + 1) * segW, top + rh - inset)
        fill.color = Design.color(PxMenuTokens.PRESS)
        fill.alpha = (Color.alpha(fill.color) * 1.6f * a).roundToInt().coerceAtMost(255)
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill)
        for ((j, label) in choices.withIndex()) {
            text.color = color
            text.alpha = (Color.alpha(color) * a).roundToInt()
            val cx = g.left + inset + (j + 0.5f) * segW
            val w = text.measureText(label)
            c.drawText(label, cx - w / 2f, top + rh / 2f - (text.ascent() + text.descent()) / 2f, text)
        }
    }

    /**
     * Material symbols (filled where Material's are), drawn in a [PxMenuTokens.SYMBOL] box centred on ([cx], [cy]); [bg]
     * is the container's colour (for the parts a filled symbol leaves open).
     */
    private fun drawGlyph(c: Canvas, which: MenuPainter.Glyph?, cx: Float, cy: Float, color: Int, bg: Int, a: Float) {
        val s = pt(PxMenuTokens.SYMBOL) / 20f   // drawn on a 20 unit grid
        glyph.color = color
        glyph.alpha = (Color.alpha(color) * a).roundToInt()
        glyph.strokeWidth = 1.8f * s
        fun cut(): Paint = glyph.apply { this.color = bg; this.alpha = (Color.alpha(bg) * a).roundToInt() }
        fun ink(): Paint = glyph.apply { this.color = color; this.alpha = (Color.alpha(color) * a).roundToInt() }
        when (which) {
            MenuPainter.Glyph.INFO -> {
                glyph.style = Paint.Style.FILL
                c.drawCircle(cx, cy, 8.3f * s, glyph)
                cut()
                c.drawRect(cx - 0.9f * s, cy - 1.2f * s, cx + 0.9f * s, cy + 4.6f * s, glyph)
                c.drawCircle(cx, cy - 3.6f * s, 1.1f * s, glyph)
                ink()
            }
            MenuPainter.Glyph.MINUS, MenuPainter.Glyph.CLOSE -> {
                glyph.style = Paint.Style.STROKE
                val d = 5.8f * s
                c.drawLine(cx - d, cy - d, cx + d, cy + d, glyph)
                c.drawLine(cx + d, cy - d, cx - d, cy + d, glyph)
            }
            MenuPainter.Glyph.TRASH -> {
                glyph.style = Paint.Style.FILL
                r.set(cx - 5.5f * s, cy - 4.5f * s, cx + 5.5f * s, cy + 8f * s)
                c.drawRoundRect(r, 1.4f * s, 1.4f * s, glyph)
                c.drawRect(cx - 7.5f * s, cy - 7.5f * s, cx + 7.5f * s, cy - 5.7f * s, glyph)
                c.drawRect(cx - 2.5f * s, cy - 9f * s, cx + 2.5f * s, cy - 7f * s, glyph)
                cut()
                c.drawRect(cx - 2.6f * s, cy - 2f * s, cx - 1.2f * s, cy + 5.5f * s, glyph)
                c.drawRect(cx + 1.2f * s, cy - 2f * s, cx + 2.6f * s, cy + 5.5f * s, glyph)
                ink()
            }
            MenuPainter.Glyph.PLUS -> {
                glyph.style = Paint.Style.STROKE
                c.drawLine(cx - 7f * s, cy, cx + 7f * s, cy, glyph)
                c.drawLine(cx, cy - 7f * s, cx, cy + 7f * s, glyph)
            }
            MenuPainter.Glyph.WIDGETS -> {
                // Three squares and a fourth turned on its corner (Material's "widgets").
                glyph.style = Paint.Style.FILL
                val q = 6.6f * s
                val gap = 1.6f * s
                c.drawRect(cx - q - gap / 2f, cy - q - gap / 2f + 1f * s, cx - gap / 2f, cy - gap / 2f + 1f * s, glyph)
                c.drawRect(cx - q - gap / 2f, cy + gap / 2f + 1f * s, cx - gap / 2f, cy + q + gap / 2f + 1f * s, glyph)
                c.drawRect(cx + gap / 2f, cy + gap / 2f + 1f * s, cx + q + gap / 2f, cy + q + gap / 2f + 1f * s, glyph)
                val dx = cx + gap / 2f + q / 2f
                val dy = cy - gap / 2f - q / 2f
                val dd = q * 0.68f
                path.reset(); path.moveTo(dx, dy - dd); path.lineTo(dx + dd, dy); path.lineTo(dx, dy + dd); path.lineTo(dx - dd, dy); path.close()
                c.drawPath(path, glyph)
            }
            MenuPainter.Glyph.APPS -> {
                glyph.style = Paint.Style.FILL
                val q = 3.6f * s
                val step = 6f * s
                for (i in -1..1) for (j in -1..1) c.drawRect(cx + i * step - q / 2f, cy + j * step - q / 2f, cx + i * step + q / 2f, cy + j * step + q / 2f, glyph)
            }
            MenuPainter.Glyph.SETTINGS -> {
                // A gear: a disc with eight teeth and a hole.
                glyph.style = Paint.Style.FILL
                c.drawCircle(cx, cy, 6.4f * s, glyph)
                for (t in 0 until 8) {
                    c.save()
                    c.rotate(t * 45f, cx, cy)
                    r.set(cx - 1.7f * s, cy - 9f * s, cx + 1.7f * s, cy - 5f * s)
                    c.drawRoundRect(r, 0.6f * s, 0.6f * s, glyph)
                    c.restore()
                }
                cut(); c.drawCircle(cx, cy, 2.8f * s, glyph); ink()
            }
            MenuPainter.Glyph.WALLPAPER, MenuPainter.Glyph.STYLE -> {
                // A palette: a disc with a notch and four paint dots.
                glyph.style = Paint.Style.FILL
                c.drawCircle(cx, cy, 8.4f * s, glyph)
                cut()
                c.drawCircle(cx + 4.6f * s, cy + 4.6f * s, 2.3f * s, glyph)
                c.drawCircle(cx - 4.4f * s, cy - 0.6f * s, 1.4f * s, glyph)
                c.drawCircle(cx - 2.2f * s, cy - 4.4f * s, 1.4f * s, glyph)
                c.drawCircle(cx + 2.4f * s, cy - 4.4f * s, 1.4f * s, glyph)
                ink()
            }
            MenuPainter.Glyph.GRID -> {
                glyph.style = Paint.Style.STROKE
                val q = 6f * s
                for (i in 0..1) for (j in 0..1) {
                    val l = cx - q - 0.8f * s + i * (q + 1.6f * s)
                    val t = cy - q - 0.8f * s + j * (q + 1.6f * s)
                    c.drawRoundRect(l, t, l + q, t + q, 1.2f * s, 1.2f * s, glyph)
                }
            }
            MenuPainter.Glyph.SLIDERS -> {
                glyph.style = Paint.Style.STROKE
                c.drawLine(cx - 7.5f * s, cy - 3.5f * s, cx + 7.5f * s, cy - 3.5f * s, glyph)
                c.drawLine(cx - 7.5f * s, cy + 3.5f * s, cx + 7.5f * s, cy + 3.5f * s, glyph)
                glyph.style = Paint.Style.FILL
                c.drawCircle(cx + 2.5f * s, cy - 3.5f * s, 2.4f * s, glyph)
                c.drawCircle(cx - 2.5f * s, cy + 3.5f * s, 2.4f * s, glyph)
            }
            MenuPainter.Glyph.LABEL -> {
                glyph.style = Paint.Style.FILL
                c.drawRoundRect(cx - 4.5f * s, cy - 8f * s, cx + 4.5f * s, cy + 1f * s, 2f * s, 2f * s, glyph)
                c.drawRect(cx - 7f * s, cy + 4.5f * s, cx + 7f * s, cy + 6.3f * s, glyph)
            }
            MenuPainter.Glyph.LOCK -> {
                glyph.style = Paint.Style.STROKE
                r.set(cx - 4f * s, cy - 8f * s, cx + 4f * s, cy)
                c.drawArc(r, 180f, 180f, false, glyph)
                c.drawLine(cx - 4f * s, cy - 4f * s, cx - 4f * s, cy - 1f * s, glyph)
                c.drawLine(cx + 4f * s, cy - 4f * s, cx + 4f * s, cy - 1f * s, glyph)
                glyph.style = Paint.Style.FILL
                c.drawRoundRect(cx - 6.5f * s, cy - 1.5f * s, cx + 6.5f * s, cy + 8f * s, 1.6f * s, 1.6f * s, glyph)
            }
            null -> {}
        }
    }
}
