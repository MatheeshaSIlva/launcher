package dev.launcher.app.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import dev.launcher.app.GlassDrawable
import dev.launcher.app.Spring
import dev.launcher.app.drawer.LabelPainter
import dev.launcher.app.drawer.screenOffset
import dev.launcher.app.theme.Fonts
import kotlin.math.max

/**
 * iOS's long-press menu for a home icon or widget: home blurs and dims behind (the blur is applied by home, [onProgress]),
 * the pressed item lifts above it, sharp, and a glass menu appears beside it: the app's shortcuts, then "Edit Home Screen",
 * "Remove from Home Screen" (in red) and "App Info". A tap on an item runs it, a tap anywhere else closes the menu.
 */
@SuppressLint("ViewConstructor")
class ContextMenuView(ctx: Context, private val m: HomeMetrics, private val onProgress: (Float) -> Unit) : View(ctx) {
    enum class Glyph { GRID, MINUS, INFO }
    class Item(val label: String, val icon: Drawable? = null, val glyph: Glyph? = null, val destructive: Boolean = false, val action: () -> Unit)

    private var items: List<Item> = emptyList()
    private var lifted: Picture? = null
    private val anchor = RectF()      // the lifted item's frame on screen
    private val panel = RectF()
    private var below = true
    var glass: GlassDrawable? = null

    private var k = 0f
    private var target = 0f
    private var spring: Spring? = null
    private var springStart = 0L
    private var animating = false
    private var pressed = -1
    private var liftedFades = false   // a destructive choice: the lifted item shrinks away with the menu

    private val dim = Paint()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
    private val fallback = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC2C2C2E.toInt() }
    private val separator = Paint().apply { color = 0x26FFFFFF; strokeWidth = max(1f, m.pt(0.5f)) }
    private val press = Paint().apply { color = 0x1FFFFFFF }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = m.pt(1.6f); strokeCap = Paint.Cap.ROUND }
    private val labels = LabelPainter(m.pt(17f), Color.WHITE, Paint.Align.LEFT, Fonts.text(400))
    private val offset = FloatArray(2)
    private val r = RectF()

    private val rowH get() = m.pt(44f)
    private val panelW get() = m.pt(250f)
    private val radius get() = m.pt(22f)

    val isShowing get() = visibility == VISIBLE && target > 0f

    /** The menu finished closing (by [dismissNow] only when asked: a drag that takes the item over keeps it hidden). */
    var onClosed: (() -> Unit)? = null

    init { visibility = GONE }

    /** Opens for the item drawn by [picture] at [frame] (screen px). */
    fun show(picture: Picture, frame: RectF, menu: List<Item>) {
        lifted = picture
        liftedFades = false
        anchor.set(frame)
        items = menu
        labels.clear()
        val h = rowH * menu.size
        below = anchor.bottom + m.pt(12f) + h < m.h - m.bottomSafe
        val top = if (below) anchor.bottom + m.pt(12f) else anchor.top - m.pt(12f) - h
        val left = (anchor.centerX() - panelW / 2f).coerceIn(m.libMargin, m.w - m.libMargin - panelW)
        panel.set(left, top, left + panelW, top + h)
        visibility = VISIBLE
        animateTo(1f)
    }

    fun dismiss() { if (target > 0f) animateTo(0f) }

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
        animateTo(0f)
    }

    /** At once (home went out of sight). */
    fun dismissNow(runClosed: Boolean = false) {
        animating = false
        target = 0f
        k = 0f
        onProgress(0f)
        visibility = GONE
        val c = onClosed
        onClosed = null
        if (runClosed) c?.invoke()
    }

    private fun animateTo(to: Float) {
        target = to
        val v = if (animating) (spring?.velocity((System.nanoTime() - springStart) / 1e9) ?: 0f) else 0f
        spring = Spring(0.32f, if (to > 0f) 0.82f else 1f).apply { start(k * 1000f, v, to * 1000f) }
        springStart = System.nanoTime()
        if (!animating) { animating = true; Choreographer.getInstance().postFrameCallback(frame) }
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!animating) return
            val s = spring ?: return
            val t = (now - springStart) / 1e9
            k = s.value(t) / 1000f
            onProgress(k.coerceIn(0f, 1f))
            invalidate()
            if (s.settled(t)) {
                animating = false
                k = target
                onProgress(k)
                if (target == 0f) {
                    visibility = GONE
                    onClosed?.invoke()
                    onClosed = null
                }
            } else Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onDraw(c: Canvas) {
        val kk = k.coerceIn(0f, 1f)
        dim.color = ((0x26 * kk).toInt() shl 24)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        // The pressed item, lifted (slightly bigger), above the blur.
        lifted?.let { p ->
            val s = if (liftedFades) 0.6f + 0.46f * kk else 1f + 0.06f * k
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
        // The menu grows from the side of the item.
        if (items.isEmpty()) return
        val ps = 0.8f + 0.2f * k
        c.save()
        c.translate(panel.centerX(), if (below) panel.top else panel.bottom)
        c.scale(ps, ps)
        c.translate(-panel.centerX(), -(if (below) panel.top else panel.bottom))
        val layer = c.saveLayerAlpha(panel.left - 2, panel.top - 2, panel.right + 2, panel.bottom + 2, (255 * kk).toInt())
        val g = glass
        if (g != null) {
            screenOffset(this, offset)
            g.setRadius(radius)
            g.originX = offset[0] + panel.left
            g.originY = offset[1] + panel.top
            g.setBounds(panel.left.toInt(), panel.top.toInt(), kotlin.math.ceil(panel.right).toInt(), kotlin.math.ceil(panel.bottom).toInt())
            g.draw(c)
            c.drawRoundRect(panel, radius, radius, fill)
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
            if (i < items.size - 1) c.drawLine(panel.left, top + rowH, panel.right, top + rowH, separator)
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
                dismiss()
                if (i >= 0) items[i].action()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }
}
