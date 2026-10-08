package dev.launcher.app.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import dev.launcher.app.drawer.LabelPainter
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.SpringValue
import dev.launcher.app.theme.Appearance
import dev.launcher.app.design.Design
import dev.launcher.app.theme.Fonts

/**
 * iOS's long-press menu for a home icon, widget or button: home blurs and dims behind (the blur is applied by home through
 * [onProgress]), the pressed item lifts above it, sharp, and a menu of liquid glass (refracting the blurred home behind it:
 * [drawBehind]) grows from the item's side. A tap on an item runs it, a tap anywhere else closes the menu. One spring drives
 * the lift, the blur and the menu together (as UIContextMenuInteraction does).
 *
 * Laid out as iOS 27's Home Screen Quick Actions (Apple's UI kit, docs/IOS27_KIT.md): 250 pt wide (wider for a long label),
 * corners 30, 10 pt above the first row and 8 below the last; rows 42 pt with the symbol first (a 20 pt column 26 pt in)
 * and the label 14 pt after it in 17 pt type; no lines between rows; the widget sizes in a row of their own at the bottom.
 * Its material is the kit's Regular glass (`comp.home.menu.*`, drawn by [MaterialPainter] over home as it is behind the
 * menu: [MaterialPainter.drawLive]): home blurred and bent at the edge, under white 70 % "lighten" and grey 10 % "darken"
 * (dark: home's colours at #1a1a1a's brightness), with its deep soft shadow (8 pt down, blur 48, 25 % / 45 %).
 */
@SuppressLint("ViewConstructor")
class ContextMenuView(
    ctx: Context,
    private val m: HomeMetrics,
    private val onProgress: (Float) -> Unit,
    /** Draws home (what the menu floats over) in this view's coordinates, for the glass to blur and bend. */
    private val drawBehind: (Canvas) -> Unit,
) : View(ctx) {
    enum class Glyph { GRID, MINUS, INFO, PLUS, SLIDERS, STYLE, TRASH, LABEL, WALLPAPER }
    /**
     * A menu row; with [sizes] it is iOS's row of widget sizes (glyphs shaped like each size, [current] filled) and
     * [onSize] runs for the one tapped.
     */
    class Item(val label: String, val icon: Drawable? = null, val glyph: Glyph? = null, val destructive: Boolean = false,
               val sizes: List<WidgetSize>? = null, val current: WidgetSize? = null, val onSize: ((WidgetSize) -> Unit)? = null,
               /** A segmented row of [choices] ([chosen] highlighted): [onChoice] runs for the one tapped; the menu stays open. */
               val choices: List<String>? = null, var chosen: Int = 0, val onChoice: ((Int) -> Unit)? = null,
               val action: () -> Unit = {})

    private var items: List<Item> = emptyList()
    private var lifted: Picture? = null
    private val anchor = RectF()      // the lifted item's frame (or the button the menu belongs to)
    private val panel = RectF()
    private var below = true
    private var pressed = -1
    private var liftedFades = false   // a destructive choice: the lifted item shrinks away with the menu

    private val k = SpringValue(0f, 1000f, { onProgress(it.coerceIn(0f, 1f)); invalidate() }, { onRest() })

    private val glass = dev.launcher.app.design.MaterialPainter.create(m.u)
    private val dim = Paint()
    private val fallback = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD92C2C2E.toInt() }
    private val press = Paint()
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = m.pt(1.6f); strokeCap = Paint.Cap.ROUND }
    private val type = Design.text(HomeTokens.MENU_TYPE)
    private val labels = LabelPainter(m.pt(type.sizePt), Color.WHITE, Paint.Align.LEFT, Fonts.text(type.weight)).toned { Design.color(HomeTokens.MENU_LABEL) }
    private val choiceText = LabelPainter(m.pt(14f), Color.WHITE, Paint.Align.CENTER, Fonts.text(500)).toned { Appearance.label }
    private val choiceFill = Paint(Paint.ANTI_ALIAS_FLAG)
    // Where the highlight of a choice row sits (index, fractional while it glides to a newly chosen one).
    private val choiceAt = SpringValue(0f, 100f, { invalidate() })
    private val r = RectF()
    private val panelMatrix = Matrix()
    private val panelTint = Paint().apply { color = 0x4D000000 }
    private val inverse = Matrix()
    private val visible = RectF()

    private val rowH get() = m.pt(Design.num(HomeTokens.MENU_ROW))
    private val sizesH get() = m.pt(44f)
    private val padTop get() = m.pt(Design.num(HomeTokens.MENU_PAD_TOP))
    private val padBottom get() = m.pt(Design.num(HomeTokens.MENU_PAD_BOTTOM))
    // iOS: 250 pt, wider for a long label (up to the screen's margins).
    private var panelW = 0f
    private val radius get() = m.pt(Design.num(HomeTokens.MENU_CORNER))
    private val symbolX get() = m.pt(Design.num(HomeTokens.MENU_SYMBOL_X))
    private val labelX get() = m.pt(Design.num(HomeTokens.MENU_LABEL_X))
    /** Each row's top (from the panel's top) and height (px). */
    private var rowTop = FloatArray(0)
    private var rowHeight = FloatArray(0)

    val isShowing get() = visibility == VISIBLE && k.target > 0f

    /** The menu finished closing (by [dismissNow] only when asked: a drag that takes the item over keeps it hidden). */
    var onClosed: (() -> Unit)? = null

    init { visibility = GONE }

    /** Opens for the item drawn by [picture] at [frame] (this view's coordinates); no picture: a menu for a button at [frame]. */
    fun show(picture: Picture?, frame: RectF, menu: List<Item>) {
        // The previous menu may still be closing: its item shows again now (its "closed" would be replaced and lost, leaving
        // that item invisible on home).
        onClosed?.invoke()
        onClosed = null
        lifted = picture
        liftedFades = false
        anchor.set(frame)
        items = menu
        labels.clear()
        val longest = menu.maxOfOrNull { labels.paint.measureText(it.label) } ?: 0f
        panelW = maxOf(m.pt(Design.num(HomeTokens.MENU_WIDTH)), longest + labelX + m.pt(26f)).coerceAtMost(m.w - 2 * m.libMargin)
        rowTop = FloatArray(menu.size)
        rowHeight = FloatArray(menu.size)
        var y = padTop
        for ((i, it) in menu.withIndex()) {
            rowTop[i] = y
            rowHeight[i] = if (it.sizes != null) sizesH else rowH
            y += rowHeight[i]
        }
        val h = y + padBottom
        val gap = m.pt(if (picture == null) 8f else 12f)
        below = anchor.bottom + gap + h < m.h - m.bottomSafe
        val top = if (below) anchor.bottom + gap else anchor.top - gap - h
        val left = if (picture == null && anchor.centerX() < m.w / 2f) anchor.left.coerceIn(m.libMargin, m.w - m.libMargin - panelW)
                   else (anchor.centerX() - panelW / 2f).coerceIn(m.libMargin, m.w - m.libMargin - panelW)
        panel.set(left, top, left + panelW, top + h)
        menu.firstOrNull { it.choices != null }?.let { choiceAt.snapTo(it.chosen.toFloat()) }
        visibility = VISIBLE
        k.animateTo(1f, Motion.profile.menuOpen)
    }

    fun dismiss() { if (k.target > 0f) k.animateTo(0f, Motion.profile.menuClose) }

    /** The lifted item's look changed while the menu shows (the Edit button in a new appearance). */
    fun replaceLifted(p: Picture?) { if (lifted != null && p != null) { lifted = p; invalidate() } }

    /**
     * A drag takes the item over: the lifted copy and the menu go at once (the drag draws its own copy), the blur and dim
     * behind fade out quickly. The item stays hidden ([onClosed] is dropped).
     */
    fun handOff() {
        if (visibility != VISIBLE) return
        lifted = null
        items = emptyList()
        onClosed = null
        pressed = -1
        k.animateTo(0f, Motion.profile.menuClose)
    }

    /** At once (home went out of sight). */
    fun dismissNow(runClosed: Boolean = false) {
        k.snapTo(0f)
        onProgress(0f)
        visibility = GONE
        val c = onClosed
        onClosed = null
        if (runClosed) c?.invoke()
    }

    private fun onRest() {
        if (k.target != 0f) return
        visibility = GONE
        onClosed?.invoke()
        onClosed = null
    }

    override fun onDraw(c: Canvas) {
        val kv = k.value
        val kk = kv.coerceIn(0f, 1f)
        // Home behind: blurred (by home) under the scrim; the menu's glass sees exactly this, and adds only the dock's tint.
        val sc = Appearance.scrim
        dim.color = sc
        dim.alpha = (android.graphics.Color.alpha(sc) * kk).toInt()
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        panelTint.color = sc
        panelTint.alpha = (android.graphics.Color.alpha(sc) * kk).toInt()
        press.color = Design.color(HomeTokens.MENU_PRESS)
        fallback.color = Appearance.mix(0xF2F2F2F7.toInt(), 0xD92C2C2E.toInt())
        // The pressed item, lifted (slightly bigger), above the blur.
        lifted?.let { p ->
            val s = if (liftedFades) 0.6f + 0.46f * kk else 1f + 0.06f * kv
            c.save()
            c.translate(anchor.centerX(), anchor.centerY())
            c.scale(s, s)
            c.translate(-anchor.width() / 2f, -anchor.height() / 2f)
            if (liftedFades) {
                val l = c.saveLayerAlpha(0f, 0f, anchor.width(), anchor.height(), (255 * kk).toInt())
                c.drawPicture(p)
                c.restoreToCount(l)
            } else c.drawPicture(p)
            c.restore()
        }
        if (items.isEmpty()) return
        // The menu grows from the side of the item.
        val ps = 0.8f + 0.2f * kv
        val px = panel.centerX()
        val py = if (below) panel.top else panel.bottom
        panelMatrix.reset()
        panelMatrix.postTranslate(-px, -py)
        panelMatrix.postScale(ps, ps)
        panelMatrix.postTranslate(px, py)
        c.save()
        c.concat(panelMatrix)
        // The kit's Regular glass over home as it is behind the menu (blurred by home, under the scrim), with its shadow
        // and rims; drawn outside the fading layer (an effect's input is cut by a smaller layer).
        panelMatrix.invert(inverse)
        visible.set(0f, 0f, width.toFloat(), height.toFloat())
        inverse.mapRect(visible)
        val drawn = glass?.drawLive(c, Design.material(HomeTokens.MENU), panel, radius, panelMatrix, visible, kk) { cc ->
            drawBehind(cc)
            cc.drawRect(0f, 0f, m.w.toFloat(), m.h.toFloat(), panelTint)
        } == true
        val layer = c.saveLayerAlpha(panel.left - 2, panel.top - 2, panel.right + 2, panel.bottom + 2, (255 * kk).toInt())
        if (!drawn) c.drawRoundRect(panel, radius, radius, fallback)
        for ((i, item) in items.withIndex()) {
            val top = panel.top + rowTop[i]
            val rh = rowHeight[i]
            val choices = item.choices
            if (choices != null) {
                drawChoices(c, item, choices, top, rh)
                continue
            }
            val sizes = item.sizes
            if (sizes != null) {
                drawSizes(c, item, sizes, top, rh)
                continue
            }
            if (i == pressed) {
                r.set(panel.left + m.pt(8f), top, panel.right - m.pt(8f), top + rh)
                c.drawRoundRect(r, m.pt(14f), m.pt(14f), press)
            }
            // iOS 27: the symbol first (a 20 pt column 26 pt in), the label 14 pt after it.
            drawGlyph(c, item, panel.left + symbolX, top + rh / 2f)
            labels.draw(c, item.label, item.label, panel.left + labelX, labels.baselineFor(top + rh / 2f), panelW - labelX - m.pt(26f),
                color = if (item.destructive) Design.color(HomeTokens.MENU_DESTRUCTIVE) else null)
        }
        c.restoreToCount(layer)
        c.restore()
    }

    private val sizeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val sizeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt(); style = Paint.Style.STROKE }

    /** The widget sizes spread over the row 17 pt in from each side (iOS 27's widget row). */
    private fun sizeSlot(n: Int, i: Int): Float {
        val inset = m.pt(17f) + m.pt(19f)
        return if (n <= 1) panel.centerX() else panel.left + inset + (panel.width() - 2 * inset) * i / (n - 1)
    }

    /** A segmented row: the choices side by side, the chosen one on a capsule that glides to a new choice. */
    private fun drawChoices(c: Canvas, item: Item, choices: List<String>, top: Float, rh: Float) {
        val n = choices.size
        val inset = m.pt(6f)
        val segW = (panel.width() - 2 * inset) / n
        val pos = choiceAt.value.coerceIn(0f, (n - 1).toFloat())
        r.set(panel.left + inset + pos * segW, top + inset, panel.left + inset + (pos + 1) * segW, top + rh - inset)
        val pf = Appearance.pressFill
        choiceFill.color = pf
        choiceFill.alpha = (android.graphics.Color.alpha(pf) * 2.2f).toInt().coerceAtMost(255)
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, choiceFill)
        for ((j, label) in choices.withIndex()) {
            val cx = panel.left + inset + (j + 0.5f) * segW
            val near = (1f - kotlin.math.abs(pos - j)).coerceIn(0f, 1f)
            choiceText.draw(c, "c$label", label, cx, choiceText.baselineFor(top + rh / 2f), segW - m.pt(4f), (150 + 105 * near).toInt())
        }
    }

    /** iOS's widget size row: one glyph per size, shaped like it (small square, wide, large square, tall), the current filled. */
    private fun drawSizes(c: Canvas, item: Item, sizes: List<WidgetSize>, top: Float, rh: Float) {
        val unit = m.pt(5.2f)
        sizeStroke.strokeWidth = m.pt(1.6f)
        for ((j, s) in sizes.withIndex()) {
            val w = s.spanX * unit
            val h = s.spanY * unit
            val cx = sizeSlot(sizes.size, j)
            val cy = top + rh / 2f
            r.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            val rad = m.pt(2.4f)
            sizeFill.color = Appearance.label
            sizeStroke.color = Appearance.secondaryLabel
            if (s == item.current) c.drawRoundRect(r, rad, rad, sizeFill)
            else c.drawRoundRect(r, rad, rad, sizeStroke)
        }
    }

    private fun drawGlyph(c: Canvas, item: Item, cx: Float, cy: Float) {
        val icon = item.icon
        if (icon != null) {
            val s = m.pt(11f)
            icon.setBounds((cx - s).toInt(), (cy - s).toInt(), (cx + s).toInt(), (cy + s).toInt())
            icon.draw(c)
            return
        }
        glyphPaint.color = if (item.destructive) Design.color(HomeTokens.MENU_DESTRUCTIVE) else Design.color(HomeTokens.MENU_LABEL)
        val g = m.pt(9f)
        when (item.glyph) {
            Glyph.GRID -> {
                glyphPaint.style = Paint.Style.STROKE
                val q = g * 0.85f
                val gap = m.pt(2.2f)
                for (dx in 0..1) for (dy in 0..1) {
                    val l = cx - q + dx * (q + gap / 2f) - gap / 4f
                    val t = cy - q + dy * (q + gap / 2f) - gap / 4f
                    c.drawRoundRect(l, t, l + q - gap / 2f, t + q - gap / 2f, m.pt(1.5f), m.pt(1.5f), glyphPaint)
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
                c.drawCircle(cx, cy - g * 0.45f, m.pt(1.1f), glyphPaint)
            }
            Glyph.SLIDERS -> {
                glyphPaint.style = Paint.Style.STROKE
                c.drawLine(cx - g, cy - g * 0.45f, cx + g, cy - g * 0.45f, glyphPaint)
                c.drawLine(cx - g, cy + g * 0.45f, cx + g, cy + g * 0.45f, glyphPaint)
                glyphPaint.style = Paint.Style.FILL
                c.drawCircle(cx + g * 0.35f, cy - g * 0.45f, m.pt(2.4f), glyphPaint)
                c.drawCircle(cx - g * 0.35f, cy + g * 0.45f, m.pt(2.4f), glyphPaint)
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
                c.drawRoundRect(r, m.pt(2f), m.pt(2f), glyphPaint)
            }
            Glyph.PLUS -> {
                glyphPaint.style = Paint.Style.STROKE
                c.drawRoundRect(cx - g, cy - g, cx + g, cy + g, m.pt(3f), m.pt(3f), glyphPaint)
                c.drawLine(cx - g * 0.5f, cy, cx + g * 0.5f, cy, glyphPaint)
                c.drawLine(cx, cy - g * 0.5f, cx, cy + g * 0.5f, glyphPaint)
            }
            Glyph.WALLPAPER -> {
                // A picture: its frame, a hill and a sun.
                glyphPaint.style = Paint.Style.STROKE
                c.drawRoundRect(cx - g, cy - g * 0.8f, cx + g, cy + g * 0.8f, m.pt(2.5f), m.pt(2.5f), glyphPaint)
                c.drawLine(cx - g, cy + g * 0.55f, cx - g * 0.15f, cy - g * 0.1f, glyphPaint)
                c.drawLine(cx - g * 0.15f, cy - g * 0.1f, cx + g, cy + g * 0.7f, glyphPaint)
                glyphPaint.style = Paint.Style.FILL
                c.drawCircle(cx + g * 0.45f, cy - g * 0.35f, m.pt(1.6f), glyphPaint)
            }
            Glyph.LABEL -> {
                // A small icon with a text line under it (names under icons).
                glyphPaint.style = Paint.Style.STROKE
                c.drawRoundRect(cx - g * 0.55f, cy - g, cx + g * 0.55f, cy + g * 0.1f, m.pt(2.5f), m.pt(2.5f), glyphPaint)
                c.drawLine(cx - g * 0.8f, cy + g * 0.7f, cx + g * 0.8f, cy + g * 0.7f, glyphPaint)
            }
            null -> {}
        }
    }

    /** The row at [y] (from the panel's top), the nearest one in the padding above or below. */
    private fun rowAt(y: Float): Int {
        for (i in rowTop.indices) if (y < rowTop[i] + rowHeight[i]) return i
        return (items.size - 1).coerceAtLeast(0)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isShowing) return false
        val i = if (panel.contains(e.x, e.y)) rowAt(e.y - panel.top) else -1
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> if (i != pressed) { pressed = i; invalidate() }
            MotionEvent.ACTION_UP -> {
                pressed = -1
                val chosen = items.getOrNull(i)
                val choices = chosen?.choices
                if (chosen != null && choices != null) {
                    // A choice: applied at once, the menu stays open (the change shows behind it); the highlight glides over.
                    val j = ((e.x - panel.left) / panel.width() * choices.size).toInt().coerceIn(0, choices.size - 1)
                    if (j != chosen.chosen) {
                        chosen.chosen = j
                        choiceAt.animateTo(j.toFloat(), Motion.profile.reflow)
                        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        chosen.onChoice?.invoke(j)
                    }
                    invalidate()
                    return true
                }
                val sizes = chosen?.sizes
                if (chosen != null && sizes != null) {
                    // A size: applied at once and the menu closes onto the widget, which is already growing or shrinking
                    // to it (the lifted copy shows the old size: kept open, the menu would hide the change).
                    val j = (0 until sizes.size).minByOrNull { kotlin.math.abs(sizeSlot(sizes.size, it) - e.x) } ?: 0
                    chosen.onSize?.invoke(sizes[j])
                    dismiss()
                    return true
                }
                if (chosen != null && chosen.destructive) liftedFades = true
                dismiss()
                chosen?.action?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }
}
