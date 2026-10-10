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
import dev.launcher.app.motion.Mover
import dev.launcher.app.motion.mover
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
 * with labels, scrolling when there are many.
 *
 * Nothing here makes the user wait: a tap outside closes the folder from wherever it is, keeping the spring's velocity; an
 * app can be opened while the folder is still growing; a closing folder takes no touches at all, so the tile under it (or
 * any other tile) can be tapped at once, which may open another folder while the first is still shrinking back (both
 * animate, each its own panel), or reopen the same one from where it is.
 */
@SuppressLint("ViewConstructor")
internal class FolderOverlay(ctx: Context, private val lib: AppLibraryView) : View(ctx) {
    private val m = lib.m

    /** One folder panel: the tile it grew out of, its open geometry, and its own spring and scroll state. */
    private inner class Panel(val tile: Tile, val index: Int, tileRect: RectF) {
        val from = RectF(tileRect)      // the tile on screen (where the panel grows from and returns to)
        val panel = RectF()             // the panel when fully open
        val cur = RectF()
        var progress = 0f
        var target = 0f
        var spring: Mover? = null
        var springStart = 0L
        var animating = false
        /** When it was last asked to open (uptimeMillis): a second tap on the tile right after is a double tap, not a tap inside. */
        var openedAt = 0L
        val scroller = IosScroller({ invalidate() }, { lib.settled() })

        init {
            val n = tile.apps.size
            val content = ceil(n / m.folderColumns.toFloat()) * rowPitch
            val w = m.w - 2 * m.folderSide
            val h = min(content + 2 * pad, m.h * 0.6f)
            val top = (m.h - h) / 2f + m.pt(12f)
            panel.set(m.folderSide, top, m.folderSide + w, top + h)
            scroller.setBounds(0f, max(0f, content + 2 * pad - h), h)
            scroller.jumpTo(0f)
        }

        val cellW get() = (panel.width() - 2 * pad) / m.folderColumns

        /** Interactive once mostly open (icons can be tapped and the grid scrolled while the last of the growth plays). */
        val interactive get() = target == 1f && (!animating || progress > 0.6f)

        fun animateTo(to: Float, s: Mover) {
            // From where it is, at the speed it has (in thousandths, so the spring's rest threshold suits a 0..1 value).
            val v = velocity()
            target = to
            s.start(progress * 1000f, v * 1000f, to * 1000f)
            spring = s
            springStart = System.nanoTime()
            animating = true
        }

        fun velocity(): Float {
            val s = spring ?: return 0f
            if (!animating) return 0f
            return s.velocity((System.nanoTime() - springStart) / 1e9) / 1000f
        }

        /** Advances to frame time [now]; true once at rest. */
        fun step(now: Long): Boolean {
            if (!animating) return true
            val s = spring ?: return true
            val t = max(0L, now - springStart) / 1e9
            progress = s.value(t) / 1000f
            if (s.settled(t)) { animating = false; progress = target; return true }
            return false
        }

        /** Where the panel's frame is now (between the tile and the open panel). */
        fun frame(): RectF {
            val p = progress
            cur.set(lerp(from.left, panel.left, p), lerp(from.top, panel.top, p), lerp(from.right, panel.right, p), lerp(from.bottom, panel.bottom, p))
            return cur
        }

        // ---- geometry (panel coordinates when fully open)

        fun iconRect(i: Int, out: RectF): RectF {
            val col = i % m.folderColumns
            val row = i / m.folderColumns
            val l = panel.left + pad + col * cellW + (cellW - m.iconSize) / 2f
            val t = panel.top + pad + row * rowPitch - scroller.position
            return out.apply { set(l, t, l + m.iconSize, t + m.iconSize) }
        }

        /** A point on screen in the open panel's coordinates (the grid is drawn scaled with the panel's frame). */
        fun toPanel(x: Float, y: Float): FloatArray {
            val f = frame()
            if (f.width() < 1f || f.height() < 1f) return floatArrayOf(x, y)
            return floatArrayOf(panel.left + (x - f.left) * panel.width() / f.width(), panel.top + (y - f.top) * panel.height() / f.height())
        }

        fun iconAt(px: Float, py: Float): Int {
            if (!panel.contains(px, py)) return -1
            for (i in tile.apps.indices) {
                iconRect(i, r).inset(-m.pt(10f), -m.pt(8f))
                if (r.contains(px, py)) return i
            }
            return -1
        }
    }

    /** The folder that owns the screen: opening or open. */
    private var active: Panel? = null
    /** Folders on their way back into their tiles (drawn, never touched). */
    private val closing = ArrayList<Panel>()
    private var framePosted = false

    private val clip = Path()
    private val touch = TapOrScroll(ctx)
    private val backdropPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.4f) })
    }
    private val dim = Paint()
    private val title = LabelPainter(m.folderTitleSize, 0xFFFFFFFF.toInt(), Paint.Align.LEFT, dev.launcher.app.theme.Fonts.display(700))
        .toned { dev.launcher.app.theme.Appearance.label }.shadowed(m.pt(4f), 0x40000000)
    private val labels = LabelPainter(m.labelTextSize, 0xFFFFFFFF.toInt(), Paint.Align.CENTER, dev.launcher.app.theme.Fonts.text(450))
        .toned { dev.launcher.app.theme.Appearance.label }.shadowed(m.pt(2f))
    private val r = RectF()
    private val r2 = RectF()
    private val at = RectF()
    private var pressed = -1
    // This touch is the second tap of a double tap on the tile that just opened the folder: it does nothing (it used to land
    // on whatever icon of the growing folder was under the finger and open that app).
    private var ignoring = false

    private val pad get() = m.pt(20f)
    private val rowPitch get() = m.iconSize + m.labelBaseline + m.pt(22f)

    /** Open or opening: it owns the screen (a closing folder does not). */
    val isOpen get() = active != null
    val isIdle get() = active?.let { !it.animating && !it.scroller.isSettling && !it.scroller.isDragging } != false && closing.isEmpty()

    fun open(t: Tile, tileRect: RectF, index: Int) {
        val a = active
        if (a != null && a.index == index) return   // already this folder (a second tap while it grows)
        if (a != null) sendBack(a)                   // another folder was open: it shrinks back while this one grows
        // The same tile tapped while its folder shrinks back: the panel reverses from where it is, keeping its speed.
        val again = closing.firstOrNull { it.index == index }
        val p = if (again != null) { closing.remove(again); again } else Panel(t, index, tileRect)
        if (again == null) { title.clear(); labels.clear() }
        active = p
        pressed = -1
        visibility = View.VISIBLE
        lib.tilesPane.setTileHidden(index, true)   // the panel is the tile from now until it is back in place
        lib.tilesPane.visibility = View.VISIBLE
        lib.host.setBackgroundCovered(false)
        p.openedAt = android.os.SystemClock.uptimeMillis()
        p.animateTo(1f, Motion.profile.folderOpen.mover())
        startFrames()
    }

    fun close() {
        val a = active ?: return
        sendBack(a)
    }

    /** [p] shrinks back into its tile; the screen is free for the next touch at once. */
    private fun sendBack(p: Panel) {
        if (active === p) active = null
        pressed = -1
        lib.tilesPane.visibility = View.VISIBLE
        lib.host.setBackgroundCovered(false)
        closing += p
        p.animateTo(0f, Motion.profile.folderClose.mover())
        startFrames()
    }

    fun closeNow() {
        active?.let { lib.tilesPane.setTileHidden(it.index, false) }
        for (p in closing) lib.tilesPane.setTileHidden(p.index, false)
        active = null
        closing.clear()
        visibility = View.GONE
        lib.tilesPane.visibility = View.VISIBLE
        lib.host.setBackgroundCovered(false)
    }

    private fun startFrames() {
        if (framePosted) return
        framePosted = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            framePosted = false
            var moving = false
            val a = active
            if (a != null) {
                if (a.step(now)) {
                    // Open: the backdrop covers the library completely (when there is one to cover it with), so it is not
                    // drawn for nothing. (No GPU layer on the library: home is recorded into a Picture for gesture nav, and a
                    // view with a GPU layer inside that recording is drawn in software, where the glass shader cannot run.)
                    if (a.target == 1f && closing.isEmpty() && lib.wallpaper != null) {
                        lib.tilesPane.visibility = View.INVISIBLE
                        lib.host.setBackgroundCovered(true)
                    }
                } else moving = true
            }
            val it = closing.iterator()
            while (it.hasNext()) {
                val p = it.next()
                if (p.step(now)) {
                    it.remove()
                    lib.tilesPane.setTileHidden(p.index, false)   // back in place: the tile draws itself again
                } else moving = true
            }
            invalidate()
            if (moving) startFrames()
            else {
                if (active == null) visibility = View.GONE
                lib.settled()
            }
        }
    }

    fun visibleIcons(out: MutableList<IconSpot>) {
        val a = active ?: return
        val t = a.tile
        for (i in t.apps.indices) {
            val rect = a.iconRect(i, RectF())
            if (rect.centerY() > a.panel.top && rect.centerY() < a.panel.bottom) out += IconSpot(t.apps[i].pkg, rect, "folder:${t.title}")
        }
    }

    // ------------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Only a closing folder on screen: it takes no touches, the library under it does (another tile, the same tile).
        val a = active ?: return false
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            ignoring = android.os.SystemClock.uptimeMillis() - a.openedAt < DOUBLE_TAP_MS && a.from.contains(e.x, e.y)
        }
        if (ignoring) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) ignoring = false
            return true
        }
        val dy = touch.onEvent(e) {
            parent?.requestDisallowInterceptTouchEvent(true)
            a.scroller.beginDrag()
            pressed = -1
        }
        val interactive = a.interactive
        val inPanel = { x: Float, y: Float -> a.frame().contains(x, y) }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touch.stoppedMotion = a.scroller.isMovingVisibly()
                a.scroller.stop()
                val p = a.toPanel(e.x, e.y)
                pressed = if (interactive && !touch.stoppedMotion) a.iconAt(p[0], p[1]) else -1
                val i = pressed
                if (i >= 0) touch.armLongPress(this) {
                    pressed = -1
                    invalidate()
                    lib.longPress(a.tile.apps[i], a.iconRect(i, RectF()), "folder:${a.tile.title}")
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (touch.scrolling && interactive && inPanel(touch.downX, touch.downY)) a.scroller.dragBy(-dy)
            MotionEvent.ACTION_UP -> {
                if (touch.longPressed) { /* the menu handles it */ }
                else if (touch.scrolling && interactive && inPanel(touch.downX, touch.downY)) a.scroller.endDrag(-touch.velocityY())
                else if (!touch.scrolling || !inPanel(touch.downX, touch.downY)) {
                    // A tap on an icon opens it (also while the folder is still growing); a tap (or any touch that did not
                    // scroll the folder) outside the panel closes it from wherever it is.
                    val p = a.toPanel(e.x, e.y)
                    val i = if (interactive && !touch.moved) a.iconAt(p[0], p[1]) else -1
                    when {
                        i >= 0 -> lib.launch(a.tile.apps[i], a.iconRect(i, RectF()), "folder:${a.tile.title}")
                        !inPanel(e.x, e.y) || !interactive -> close()
                    }
                }
                pressed = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { if (touch.scrolling) a.scroller.endDrag(0f); pressed = -1; invalidate() }
        }
        return true
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        val a = active
        if (a == null && closing.isEmpty()) return
        // The library sinks completely under the same blurred wallpaper that is its background (nothing of it stays
        // faintly visible behind an open folder), slightly darker: by the most open panel.
        var k = a?.progress ?: 0f
        for (p in closing) k = max(k, p.progress)
        k = k.coerceIn(0f, 1f)
        val w = lib.wallpaper
        if (w != null) {
            backdropPaint.alpha = (255 * k).toInt()
            c.drawBitmap(w.heavy, w.heavyMatrix(m.w, m.h), backdropPaint)
            // The library's own material (the appearance's veil), a touch darker: the folder is a level above it.
            val veil = dev.launcher.app.theme.Appearance.backdropVeil
            dim.color = veil
            dim.alpha = (android.graphics.Color.alpha(veil) * k).toInt()
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
            dim.color = ((0x14 * k).toInt() shl 24)
        } else {
            dim.color = ((0x99 * k).toInt() shl 24)
        }
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        for (p in closing) drawPanel(c, p, pressedIcon = -1)
        if (a != null) drawPanel(c, a, pressed)
    }

    private fun drawPanel(c: Canvas, pn: Panel, pressedIcon: Int) {
        val t = pn.tile
        val p = pn.progress
        val k = p.coerceIn(0f, 1f)
        val cur = pn.frame()
        val panel = pn.panel
        // The panel's frame grows from the tile; its glass is the tile's glass, its corners go from the tile's to the folder's.
        val radius = lerp(m.tileRadius, m.folderRadius, k)
        lib.drawGlass(c, lib.panelGlass, cur, radius, this)

        // The title belongs to the panel: it rides on its frame (scaled with it) into and out of the tile, fading out early,
        // so it never stays above the library's other tiles while the panel shrinks away from under it.
        val titleA = smooth(0.5f, 1f, p)
        if (titleA > 0f) {
            val ts = cur.width() / panel.width()
            c.save()
            c.translate(cur.left, cur.top)
            c.scale(ts, ts)
            title.draw(c, "t:${t.title}", t.title, m.pt(6f), -m.pt(18f) + (1f - k) * m.pt(12f), panel.width(), (255 * titleA).toInt())
            c.restore()
        }

        c.save()
        clip.reset()
        clip.addRoundRect(cur, radius, radius, Path.Direction.CW)
        c.clipPath(clip)
        // iOS: the icons the tile shows (its large ones, the small ones of its fourth slot) fly from their places in the tile
        // to their cells in the folder's grid, growing, as the panel opens (and back as it closes); the apps the tile does
        // not show come in where they belong in the grid, which is scaled with the panel. (The tile's icons and the grid
        // used to crossfade.) The names come once the icons are nearly in place.
        val s = cur.width() / panel.width()
        val restA = smooth(0.25f, 0.8f, p)
        val labelA = (255 * smooth(0.7f, 1f, p)).toInt()
        // The ones coming in first, the flying ones over them (their paths cross the grid).
        for (pass in 0..1) for (i in t.apps.indices) {
            pn.iconRect(i, r)                                    // the cell, the folder fully open
            val from = lib.tilesPane.tileIconRect(t, i, pn.from.left, pn.from.top, 1f, r2)
            if ((from != null) != (pass == 1)) continue
            val a: Int
            if (from != null) {
                // From its place in the tile to its cell, with the panel's own progress (so it keeps its place in the frame).
                at.set(lerp(from.left, r.left, p), lerp(from.top, r.top, p), lerp(from.right, r.right, p), lerp(from.bottom, r.bottom, p))
                a = 255
            } else {
                if (restA <= 0f || r.bottom < panel.top - m.labelBaseline || r.top > panel.bottom) continue
                at.set(cur.left + (r.left - panel.left) * s, cur.top + (r.top - panel.top) * s, 0f, 0f)
                at.right = at.left + r.width() * s
                at.bottom = at.top + r.height() * s
                a = (255 * restA).toInt()
            }
            val e = t.apps[i]
            if (!lib.isHidden(e, at)) lib.iconPainter.draw(c, e, at, dimmed = i == pressedIcon, alpha = a)
            if (labelA > 0) {
                // The name under the icon where it is now, at the icon's scale.
                val f = at.width() / m.iconSize
                c.save()
                c.translate(at.centerX(), at.bottom)
                c.scale(f, f)
                labels.draw(c, e.key, e.label, 0f, m.labelBaseline, pn.cellW - m.pt(4f), minOf(a, labelA))
                c.restore()
            }
        }
        c.restore()
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private companion object {
        /** A tap on the tile within this long after it opened the folder is the second half of a double tap. */
        const val DOUBLE_TAP_MS = 350L
    }
}
