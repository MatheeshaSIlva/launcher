package dev.launcher.app.drawer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import dev.launcher.app.Spring
import dev.launcher.app.motion.IosScroller
import dev.launcher.app.motion.Motion
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A category opened from its App Library tile, as an iOS folder. The tile itself becomes the folder: the same glass panel
 * grows out of the tile on a spring, the tile's icons fade into the folder's grid, and the library behind sinks under the
 * blurred wallpaper. Closing is the exact reverse, so at the end the panel looks exactly like the tile it returns into
 * (the tile is not drawn underneath meanwhile: nothing changes when the folder goes). Title above, four columns of icons
 * with labels, scrolling when there are many. Interruptible: a tap outside closes it from wherever it is, keeping the
 * spring's velocity.
 */
@SuppressLint("ViewConstructor")
internal class FolderOverlay(ctx: Context, private val lib: AppLibraryView) : View(ctx) {
    private val m = lib.m
    private var tile: Tile? = null
    private var tileIndex = -1
    private val from = RectF()          // the tile on screen (where the panel grows from and returns to)
    private val panel = RectF()         // the panel when fully open
    private val cur = RectF()
    private val clip = Path()
    private var progress = 0f
    private var target = 0f
    private var spring: Spring? = null
    private var springStart = 0L
    private var animating = false

    private val scroller = IosScroller({ invalidate() }, { lib.settled() })
    private val touch = TapOrScroll(ctx)
    private val backdropPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.4f) })
    }
    private val dim = Paint()
    private val title = LabelPainter(m.folderTitleSize, 0xFFFFFFFF.toInt(), Paint.Align.LEFT, dev.launcher.app.theme.Fonts.display(700))
    private val labels = LabelPainter(m.labelTextSize, 0xFFFFFFFF.toInt(), Paint.Align.CENTER, dev.launcher.app.theme.Fonts.text(450))
    private val r = RectF()
    private var pressed = -1

    private val pad get() = m.pt(20f)
    private val cellW get() = (panel.width() - 2 * pad) / m.folderColumns
    private val rowPitch get() = m.iconSize + m.labelBaseline + m.pt(22f)

    /** Open, opening, or still closing: it owns the screen. */
    val isOpen get() = visibility == View.VISIBLE && target > 0f
    val isIdle get() = !animating && !scroller.isSettling && !scroller.isDragging

    fun open(t: Tile, tileRect: RectF, index: Int) {
        tile = t
        tileIndex = index
        from.set(tileRect)
        val n = t.apps.size
        val content = ceil(n / m.folderColumns.toFloat()) * rowPitch
        val w = m.w - 2 * m.folderSide
        val h = min(content + 2 * pad, m.h * 0.6f)
        val top = (m.h - h) / 2f + m.pt(12f)
        panel.set(m.folderSide, top, m.folderSide + w, top + h)
        scroller.setBounds(0f, max(0f, content + 2 * pad - h), h)
        scroller.jumpTo(0f)
        title.clear(); labels.clear()
        visibility = View.VISIBLE
        lib.tilesPane.hiddenTile = index   // the panel is the tile from now until it is back in place
        animateTo(1f, Motion.profile.folderOpen.spring())
    }

    fun close() = animateTo(0f, Motion.profile.folderClose.spring())

    fun closeNow() {
        animating = false
        progress = 0f
        target = 0f
        visibility = View.GONE
        lib.tilesPane.hiddenTile = -1
    }

    private fun animateTo(to: Float, s: Spring) {
        // From where it is, at the speed it has (in thousandths, so the spring's rest threshold suits a 0..1 value).
        val v = currentVelocity()
        target = to
        s.start(progress * 1000f, v * 1000f, to * 1000f)
        spring = s
        springStart = System.nanoTime()
        if (!animating) { animating = true; Choreographer.getInstance().postFrameCallback(frame) }
    }

    private fun currentVelocity(): Float {
        val s = spring ?: return 0f
        if (!animating) return 0f
        return s.velocity((System.nanoTime() - springStart) / 1e9) / 1000f
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!animating) return
            val s = spring ?: return
            val t = max(0L, now - springStart) / 1e9
            progress = s.value(t) / 1000f
            invalidate()
            if (s.settled(t)) {
                animating = false
                progress = target
                if (target == 0f) closeNow() else lib.settled()
            } else Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ------------------------------------------------------------------ geometry (panel coordinates when fully open)

    private fun iconRect(i: Int, out: RectF): RectF {
        val col = i % m.folderColumns
        val row = i / m.folderColumns
        val l = panel.left + pad + col * cellW + (cellW - m.iconSize) / 2f
        val t = panel.top + pad + row * rowPitch - scroller.position
        return out.apply { set(l, t, l + m.iconSize, t + m.iconSize) }
    }

    fun visibleIcons(out: MutableList<IconSpot>) {
        val t = tile ?: return
        for (i in t.apps.indices) {
            val rect = iconRect(i, RectF())
            if (rect.centerY() > panel.top && rect.centerY() < panel.bottom) out += IconSpot(t.apps[i].pkg, rect, "folder:${t.title}")
        }
    }

    private fun iconAt(x: Float, y: Float): Int {
        val t = tile ?: return -1
        if (!panel.contains(x, y)) return -1
        for (i in t.apps.indices) {
            iconRect(i, r).inset(-m.pt(10f), -m.pt(8f))
            if (r.contains(x, y)) return i
        }
        return -1
    }

    // ------------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val dy = touch.onEvent(e) {
            parent?.requestDisallowInterceptTouchEvent(true)
            scroller.beginDrag()
            pressed = -1
        }
        val settledOpen = !animating && target == 1f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touch.stoppedMotion = scroller.isSettling
                scroller.stop()
                pressed = if (settledOpen && !touch.stoppedMotion) iconAt(e.x, e.y) else -1
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (touch.scrolling && settledOpen && panel.contains(touch.downX, touch.downY)) scroller.dragBy(-dy)
            MotionEvent.ACTION_UP -> {
                if (touch.scrolling && settledOpen && panel.contains(touch.downX, touch.downY)) scroller.endDrag(-touch.velocityY())
                else if (!touch.scrolling || !panel.contains(touch.downX, touch.downY)) {
                    // A tap (or any touch that did not scroll the folder) outside the panel, or while it is still moving, closes it.
                    val i = if (settledOpen && !touch.moved) iconAt(e.x, e.y) else -1
                    val t = tile
                    when {
                        i >= 0 && t != null -> lib.launch(t.apps[i], iconRect(i, RectF()), "folder:${t.title}")
                        !panel.contains(e.x, e.y) || !settledOpen -> close()
                    }
                }
                pressed = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { if (touch.scrolling) scroller.endDrag(0f); pressed = -1; invalidate() }
        }
        return true
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        val t = tile ?: return
        val p = progress
        val k = p.coerceIn(0f, 1f)

        // The library sinks under the same blurred wallpaper that is its background (as iOS blurs it away), slightly darker.
        val w = lib.wallpaper
        if (w != null) {
            backdropPaint.alpha = (240 * k).toInt()
            c.drawBitmap(w.heavy, w.heavyMatrix(m.w, m.h), backdropPaint)
            dim.color = ((0x22 * k).toInt() shl 24)
        } else {
            dim.color = ((0x99 * k).toInt() shl 24)
        }
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)

        // The panel's frame grows from the tile; its glass is the tile's glass, its corners go from the tile's to the folder's.
        cur.set(lerp(from.left, panel.left, p), lerp(from.top, panel.top, p), lerp(from.right, panel.right, p), lerp(from.bottom, panel.bottom, p))
        val radius = lerp(m.tileRadius, m.folderRadius, k)
        lib.drawGlass(c, lib.panelGlass, cur, radius, this)

        val titleY = panel.top - m.pt(18f) + (1f - k) * m.pt(24f)
        title.draw(c, "title", t.title, panel.left + m.pt(6f), titleY, panel.width(), (255 * k).toInt())

        c.save()
        clip.reset()
        clip.addRoundRect(cur, radius, radius, Path.Direction.CW)
        c.clipPath(clip)
        // The tile's own icons, at the panel's scale, fading out as the folder opens (and back in as it closes).
        val tileAlpha = 1f - smooth(0f, 0.45f, p)
        if (tileAlpha > 0f) {
            val s = cur.width() / from.width()
            lib.tilesPane.drawTileIcons(c, t, cur.left, cur.top, s, (255 * tileAlpha).toInt(), skipHidden = false)
        }
        // The folder's grid, drawn at full size and scaled uniformly with the panel, fading in.
        val gridAlpha = smooth(0.3f, 0.85f, p)
        if (gridAlpha > 0f) {
            val s = cur.width() / panel.width()
            c.save()
            c.translate(cur.left, cur.top)
            c.scale(s, s)
            c.translate(-panel.left, -panel.top)
            val a = (255 * gridAlpha).toInt()
            for (i in t.apps.indices) {
                iconRect(i, r)
                if (r.bottom < panel.top - m.labelBaseline || r.top > panel.bottom) continue
                val e = t.apps[i]
                if (!lib.isHidden(e, r)) lib.iconPainter.draw(c, e, r, dimmed = i == pressed, alpha = a)
                labels.draw(c, e.key, e.label, r.centerX(), r.bottom + m.labelBaseline, cellW - m.pt(4f), a)
            }
            c.restore()
        }
        c.restore()
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
