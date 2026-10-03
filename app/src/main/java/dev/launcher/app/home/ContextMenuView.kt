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
import dev.launcher.app.GlassStyle
import dev.launcher.app.LiveGlass
import dev.launcher.app.drawer.LabelPainter
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.SpringValue
import dev.launcher.app.theme.Fonts
import kotlin.math.max

/**
 * iOS's long-press menu for a home icon, widget or button: home blurs and dims behind (the blur is applied by home through
 * [onProgress]), the pressed item lifts above it, sharp, and a menu of the theme's liquid glass (the dock's material,
 * refracting the blurred home behind it: [drawBehind]) grows from the item's side. A tap on an item runs it, a tap anywhere
 * else closes the menu. One spring drives the lift, the blur and the menu together (as UIContextMenuInteraction does).
 */
@SuppressLint("ViewConstructor")
class ContextMenuView(
    ctx: Context,
    private val m: HomeMetrics,
    private val onProgress: (Float) -> Unit,
    /** Draws home (what the menu floats over) in this view's coordinates, for the glass to blur and bend. */
    private val drawBehind: (Canvas) -> Unit,
) : View(ctx) {
    enum class Glyph { GRID, MINUS, INFO, PLUS }
    class Item(val label: String, val icon: Drawable? = null, val glyph: Glyph? = null, val destructive: Boolean = false, val action: () -> Unit)

    private var items: List<Item> = emptyList()
    private var lifted: Picture? = null
    private val anchor = RectF()      // the lifted item's frame (or the button the menu belongs to)
    private val panel = RectF()
    private var below = true
    private var pressed = -1
    private var liftedFades = false   // a destructive choice: the lifted item shrinks away with the menu

    private val k = SpringValue(0f, 1000f, { onProgress(it.coerceIn(0f, 1f)); invalidate() }, { onRest() })

    private val glass = LiveGlass.create(GlassStyle.IOS, m.u)
    private val dim = Paint()
    private val fallback = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD92C2C2E.toInt() }
    private val separator = Paint().apply { color = 0x26FFFFFF; strokeWidth = max(1f, m.pt(0.5f)) }
    private val press = Paint().apply { color = 0x1FFFFFFF }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = m.pt(1.6f); strokeCap = Paint.Cap.ROUND }
    private val labels = LabelPainter(m.pt(17f), Color.WHITE, Paint.Align.LEFT, Fonts.text(400))
    private val r = RectF()
    private val panelMatrix = Matrix()

    private val rowH get() = m.pt(44f)
    private val panelW get() = m.pt(250f)
    private val radius get() = m.pt(22f)

    val isShowing get() = visibility == VISIBLE && k.target > 0f

    /** The menu finished closing (by [dismissNow] only when asked: a drag that takes the item over keeps it hidden). */
    var onClosed: (() -> Unit)? = null

    init { visibility = GONE }

    /** Opens for the item drawn by [picture] at [frame] (this view's coordinates); no picture: a menu for a button at [frame]. */
    fun show(picture: Picture?, frame: RectF, menu: List<Item>) {
        lifted = picture
        liftedFades = false
        anchor.set(frame)
        items = menu
        labels.clear()
        val h = rowH * menu.size
        val gap = m.pt(if (picture == null) 8f else 12f)
        below = anchor.bottom + gap + h < m.h - m.bottomSafe
        val top = if (below) anchor.bottom + gap else anchor.top - gap - h
        val left = if (picture == null && anchor.centerX() < m.w / 2f) anchor.left.coerceIn(m.libMargin, m.w - m.libMargin - panelW)
                   else (anchor.centerX() - panelW / 2f).coerceIn(m.libMargin, m.w - m.libMargin - panelW)
        panel.set(left, top, left + panelW, top + h)
        visibility = VISIBLE
        k.animateTo(1f, Motion.profile.menuOpen)
    }

    fun dismiss() { if (k.target > 0f) k.animateTo(0f, Motion.profile.menuClose) }

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
        dim.color = ((0x26 * kk).toInt() shl 24)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
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
        val layer = c.saveLayerAlpha(panel.left - 2, panel.top - 2, panel.right + 2, panel.bottom + 2, (255 * kk).toInt())
        val g = glass
        if (g != null && c.isHardwareAccelerated) {
            // The very glass of the dock, bending home as it is behind the menu: blurred and dimmed by the same amounts.
            g.draw(c, panel, radius, kk * Motion.profile.menuBlur * m.u, panelMatrix) { cc ->
                drawBehind(cc)
                cc.drawRect(0f, 0f, m.w.toFloat(), m.h.toFloat(), dim)
            }
        } else c.drawRoundRect(panel, radius, radius, fallback)
        for ((i, item) in items.withIndex()) {
            val top = panel.top + i * rowH
            if (i == pressed) {
                c.save()
                r.set(panel.left, top, panel.right, top + rowH)
                c.clipRect(r)
                c.drawRoundRect(panel, radius, radius, press)
                c.restore()
            }
            labels.paint.color = if (item.destructive) 0xFFFF453A.toInt() else Color.WHITE
            labels.draw(c, item.label, item.label, panel.left + m.pt(16f), labels.baselineFor(top + rowH / 2f), panelW - m.pt(60f))
            drawGlyph(c, item, panel.right - m.pt(26f), top + rowH / 2f)
            if (i < items.size - 1) c.drawLine(panel.left + m.pt(16f), top + rowH, panel.right, top + rowH, separator)
        }
        c.restoreToCount(layer)
        c.restore()
    }

    private fun drawGlyph(c: Canvas, item: Item, cx: Float, cy: Float) {
        val icon = item.icon
        if (icon != null) {
            val s = m.pt(11f)
            icon.setBounds((cx - s).toInt(), (cy - s).toInt(), (cx + s).toInt(), (cy + s).toInt())
            icon.draw(c)
            return
        }
        glyphPaint.color = if (item.destructive) 0xFFFF453A.toInt() else Color.WHITE
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
            Glyph.PLUS -> {
                glyphPaint.style = Paint.Style.STROKE
                c.drawRoundRect(cx - g, cy - g, cx + g, cy + g, m.pt(3f), m.pt(3f), glyphPaint)
                c.drawLine(cx - g * 0.5f, cy, cx + g * 0.5f, cy, glyphPaint)
                c.drawLine(cx, cy - g * 0.5f, cx, cy + g * 0.5f, glyphPaint)
            }
            null -> {}
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isShowing) return false
        val i = if (panel.contains(e.x, e.y)) ((e.y - panel.top) / rowH).toInt().coerceIn(0, items.size - 1) else -1
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> if (i != pressed) { pressed = i; invalidate() }
            MotionEvent.ACTION_UP -> {
                pressed = -1
                if (i >= 0 && items[i].destructive) liftedFades = true
                val chosen = items.getOrNull(i)
                dismiss()
                chosen?.action?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }
}
