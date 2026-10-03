package dev.launcher.app.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RectF
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.launcher.app.Spring
import dev.launcher.app.theme.Fonts
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/**
 * iOS edit mode ("jiggle mode") for the home screen: every icon and widget wiggles, a "–" badge removes it from home (apps
 * stay in the drawer), "+" adds the clock widget back, "Done" (or a tap on empty space, back, home) leaves.
 *
 * Dragging: the item lifts (a copy follows the finger, slightly enlarged, with a soft shadow) while its real view stays,
 * hidden, in the grid; resting over a cell for a moment moves it there and the rest of the page flows around it on
 * springs. Over the dock an app joins the dock (up to its slots). Resting at the left or right edge turns the page (past
 * the last page a new one appears). On release the copy springs into the item's cell. The layout is saved at once.
 */
internal class EditMode(private val home: HomeScreen, private val host: Host) {
    interface Host {
        val metrics: HomeMetrics
        val layout: HomeLayout?
        val pageViews: List<PageView>
        val dockView: DockView?
        /** Index of the page on screen. */
        fun currentPage(): Int
        fun turnToPage(i: Int)
        /** Appends an empty page (layout and view). */
        fun appendPage()
        /** A dock icon for an app key (dock views are created by home). */
        fun dockIcon(key: String): IconView?
        fun layoutChanged()
        /** Edit mode began or ended (indicator dots, edit bar, publishing). */
        fun editingChanged(active: Boolean)
    }

    private val m get() = host.metrics
    var active = false
        private set

    // ------------------------------------------------------------------ entering and leaving

    fun enter(haptic: Boolean = true) {
        if (active) return
        active = true
        if (haptic) home.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        setBadges(true)
        startJiggle()
        host.editingChanged(true)
    }

    fun exit() {
        if (!active) return
        if (drag != null) drop()
        active = false
        setBadges(false)
        stopJiggle()
        // Empty pages go (the first page always stays).
        val l = host.layout
        if (l != null && l.pages.size > 1 && l.pages.drop(1).any { it.isEmpty() }) {
            val keep = l.pages.filterIndexed { i, p -> i == 0 || p.isNotEmpty() }
            l.pages.clear()
            l.pages.addAll(keep)
            host.layoutChanged()
        }
        host.editingChanged(false)
    }

    private fun allItemViews(): List<View> = host.pageViews.flatMap { it.itemViews() } + (host.dockView?.icons() ?: emptyList())

    private fun setBadges(on: Boolean) {
        for (v in allItemViews()) when (v) {
            is IconView -> v.editing = on
            is ClockWidgetView -> v.editing = on
        }
    }

    /** New views appear while editing (an item moved to another page or into the dock): give them the edit look. */
    fun adopt(v: View) {
        if (!active) return
        when (v) {
            is IconView -> v.editing = true
            is ClockWidgetView -> v.editing = true
        }
    }

    // ------------------------------------------------------------------ wiggle

    private val phases = java.util.WeakHashMap<View, Float>()
    private var jiggling = false

    private fun startJiggle() {
        if (jiggling) return
        jiggling = true
        Choreographer.getInstance().postFrameCallback(jiggleFrame)
    }

    private fun stopJiggle() {
        jiggling = false
        for (v in allItemViews()) v.animate().rotation(0f).setDuration(120).start()
    }

    private val jiggleFrame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!jiggling) return
            val t = now / 1e9
            val visible = host.currentPage()
            for ((i, p) in host.pageViews.withIndex()) {
                if (abs(i - visible) > 1) continue
                for (v in p.itemViews()) wiggle(v, t)
            }
            host.dockView?.icons()?.forEach { wiggle(it, t) }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun wiggle(v: View, t: Double) {
        val phase = phases.getOrPut(v) { (Math.random() * Math.PI * 2).toFloat() }
        // About four wiggles a second, 1.6 degrees each way (widgets less: they are bigger).
        val amp = if (v is ClockWidgetView) 0.6f else 1.6f
        v.rotation = amp * sin(t * 2 * Math.PI / 0.26 + phase).toFloat()
    }

    // ------------------------------------------------------------------ touches while editing

    private val slop = ViewConfiguration.get(home.context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var candidate: View? = null
    private var candidateBadge = false
    private var moved = false

    /** Down on home while editing: true if it landed on an item (edit mode takes the touch). */
    fun onDown(x: Float, y: Float): Boolean {
        downX = x; downY = y; moved = false
        candidate = itemViewAt(x, y)
        candidateBadge = candidate?.let { onBadge(it, x, y) } ?: false
        return candidate != null
    }

    /** The rest of a touch edit mode took. */
    fun onTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (drag != null) { dragTo(e.x, e.y); return }
                if (!moved && hypot(e.x - downX, e.y - downY) > slop) {
                    moved = true
                    candidate?.let { if (!candidateBadge) beginDrag(it, downX, downY) }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (drag != null) { drop(); return }
                val c = candidate
                if (!moved && c != null && candidateBadge && onBadge(c, e.x, e.y)) remove(c)
            }
            MotionEvent.ACTION_CANCEL -> if (drag != null) drop()
        }
    }

    /** Start dragging [v] right away (a long press that kept moving; [fromMenu]: its menu's copy was already lifted). */
    fun beginDragFrom(v: View, x: Float, y: Float, fromMenu: Boolean = false) {
        downX = x; downY = y; moved = true
        candidate = v
        beginDrag(v, x, y, if (fromMenu) 0.75f else 0f)
    }

    /** The item (icon or widget) under ([x], [y]) in home's coordinates, on the page on screen or in the dock. */
    fun itemAt(x: Float, y: Float): View? = itemViewAt(x, y)

    private fun itemViewAt(x: Float, y: Float): View? {
        val r = RectF()
        host.dockView?.let { d ->
            for (v in d.icons()) { screenRect(v, r); r.inset(-m.pt(8f), -m.pt(8f)); if (r.contains(x, y)) return v }
        }
        val page = host.pageViews.getOrNull(host.currentPage()) ?: return null
        for (v in page.itemViews()) { screenRect(v, r); if (r.contains(x, y)) return v }
        return null
    }

    private fun onBadge(v: View, x: Float, y: Float): Boolean {
        val c = when (v) { is IconView -> v.badgeCenter(); is ClockWidgetView -> v.badgeCenter(); else -> return false }
        val loc = screenOrigin(v)
        return hypot(x - (loc[0] + c[0]), y - (loc[1] + c[1])) < RemoveBadge.radius(m) * 1.9f
    }

    /** [v]'s top-left in home's coordinates (where touches arrive and the lifted copy is drawn). */
    private fun screenOrigin(v: View): FloatArray {
        val a = IntArray(2)
        val b = IntArray(2)
        v.getLocationOnScreen(a)
        home.getLocationOnScreen(b)
        return floatArrayOf((a[0] - b[0]).toFloat(), (a[1] - b[1]).toFloat())
    }

    private fun screenRect(v: View, out: RectF): RectF {
        val o = screenOrigin(v)
        return out.apply { set(o[0], o[1], o[0] + v.width, o[1] + v.height) }
    }

    // ------------------------------------------------------------------ removing

    /** From the long-press menu ("Remove from Home Screen" / "Remove Widget"), with or without edit mode. */
    fun removeFromHome(v: View) = remove(v)

    private fun remove(v: View) {
        val l = host.layout ?: return
        val dock = host.dockView
        if (dock != null && v is IconView && dock.icons().any { it === v }) {
            val key = v.entry?.key ?: return
            l.dock.remove(key)
            v.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(180).withEndAction {
                dock.bind(dock.icons().filter { it !== v }, animate = true)
            }.start()
        } else {
            val page = host.pageViews.firstOrNull { it.itemOf(v) != null } ?: return
            val item = page.itemOf(v) ?: return
            val pi = host.pageViews.indexOf(page)
            l.pages[pi].remove(item)
            v.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(180).withEndAction {
                page.setItems(l.pages[pi], animate = true)
            }.start()
        }
        home.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        host.layoutChanged()
    }

    /** "+": the clock widget at the top of the page on screen (the only widget so far). */
    fun addClockWidget() {
        val l = host.layout ?: return
        val pi = host.currentPage().coerceIn(0, l.pages.size - 1)
        if (l.pages[pi].any { it is HomeItem.Widget }) return
        val widget = HomeItem.Widget("clock", m.cfg.columns, 2)
        l.pages[pi].add(0, widget)
        overflowFrom(pi)
        refreshPages()
        host.layoutChanged()
    }

    // ------------------------------------------------------------------ dragging

    private class Drag(val item: HomeItem, var inDock: Boolean, var page: Int, val picture: Picture, val w: Int, val h: Int,
                       val grabX: Float, val grabY: Float)

    private var drag: Drag? = null
    private var fingerX = 0f
    private var fingerY = 0f
    private var pendingTarget: Pair<Boolean, Int>? = null    // (into dock, index) waiting for the finger to rest
    private var pendingPage = -1
    private val ghost = Ghost(home.context)
    private val applyTarget = Runnable { pendingTarget?.let { (dock, index) -> moveTo(dock, index) } }
    private val edgeTurn = Runnable { turnAtEdge() }

    /** The view drawing the lifted copy; home adds it on top of everything. */
    val ghostView: View get() = ghost

    private fun beginDrag(v: View, x: Float, y: Float, startLift: Float = 0f) {
        if (host.layout == null) return
        val dock = host.dockView
        val inDock = dock != null && v is IconView && dock.icons().any { it === v }
        val item: HomeItem = if (inDock) HomeItem.App((v as IconView).entry?.key ?: return) else {
            val page = host.pageViews.firstOrNull { it.itemOf(v) != null } ?: return
            page.itemOf(v) ?: return
        }
        // The lifted copy: the icon alone (no label), or the whole widget.
        val pic = Picture()
        val c = pic.beginRecording(v.width, v.height)
        val rot = v.rotation
        if (v is IconView) v.labelHidden = true
        if (v is IconView) v.editing = false
        if (v is ClockWidgetView) v.editing = false
        v.draw(c)
        if (v is IconView) { v.labelHidden = false; v.editing = true }
        if (v is ClockWidgetView) v.editing = true
        pic.endRecording()
        v.rotation = rot
        val o = screenOrigin(v)
        drag = Drag(item, inDock, if (inDock) -1 else host.pageViews.indexOfFirst { it.itemOf(v) != null }, pic, v.width, v.height, x - o[0], y - o[1])
        if (inDock) {
            // Dock items live in the layout's dock list as keys; while dragged they are tracked as an App item.
            val key = (item as HomeItem.App).key
            dockDragKey = key
        }
        v.alpha = 0f
        fingerX = x; fingerY = y
        ghost.lift(pic, v.width, v.height, o[0], o[1], startLift)
        home.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private var dockDragKey: String? = null

    private fun dragTo(x: Float, y: Float) {
        val d = drag ?: return
        fingerX = x; fingerY = y
        ghost.moveTo(x - d.grabX, y - d.grabY)
        // Resting at an edge turns the page.
        val edge = m.pt(26f)
        val atEdge = x < edge || x > m.w - edge
        if (atEdge) { if (!home.hasCallbacks(edgeTurn)) home.postDelayed(edgeTurn, 550) } else home.removeCallbacks(edgeTurn)
        // Where it would go; applied once the finger rests there briefly (no reshuffle while just passing over).
        val target = targetAt(x, y, d)
        if (target != pendingTarget) {
            pendingTarget = target
            home.removeCallbacks(applyTarget)
            if (target != null) home.postDelayed(applyTarget, 110)
        }
    }

    private fun View.hasCallbacks(r: Runnable) = handler?.hasCallbacks(r) == true

    private fun targetAt(x: Float, y: Float, d: Drag): Pair<Boolean, Int>? {
        val l = host.layout ?: return null
        val dock = host.dockView
        if (dock != null && d.item is HomeItem.App && y > m.dockTop - m.pt(12f)) {
            val inDockAlready = d.inDock
            if (!inDockAlready && l.dock.size >= m.cfg.dockSlots) return null   // the dock is full
            val n = if (inDockAlready) l.dock.size else l.dock.size + 1
            var best = 0
            var bestD = Float.MAX_VALUE
            for (i in 0 until n) {
                val cx = m.dockInset + dock.slotLeft(i, n) + m.iconSize / 2f
                val dd = abs(cx - x)
                if (dd < bestD) { bestD = dd; best = i }
            }
            return true to best
        }
        val pi = host.currentPage()
        val page = host.pageViews.getOrNull(pi) ?: return null
        val idx = page.insertIndexAt(x, y, d.item)
        return false to idx
    }

    /** Moves the dragged item to the dock at [index], or to the page on screen at [index]. */
    private fun moveTo(toDock: Boolean, index: Int) {
        val d = drag ?: return
        val l = host.layout ?: return
        val pi = host.currentPage().coerceIn(0, l.pages.size - 1)
        // Out of where it is now.
        if (d.inDock) l.dock.remove((d.item as HomeItem.App).key) else if (d.page in l.pages.indices) l.pages[d.page].removeAll { it === d.item }
        val fromPage = d.page
        if (toDock) {
            val key = (d.item as HomeItem.App).key
            l.dock.add(index.coerceIn(0, l.dock.size), key)
            d.inDock = true
            d.page = -1
        } else {
            val list = l.pages[pi]
            list.add(index.coerceIn(0, list.size), d.item)
            d.inDock = false
            d.page = pi
            overflowFrom(pi)
        }
        refreshPages(setOf(fromPage, pi))
        refreshDock()
        hideDragged()
    }

    /** A full page pushes its last items onto the next page (a new page if needed). */
    private fun overflowFrom(start: Int) {
        val l = host.layout ?: return
        var i = start
        while (i < l.pages.size) {
            val page = l.pages[i]
            while (HomeModel.capacityLeft(page, m.cfg.columns, m.cfg.rows) < 0 ||
                HomeModel.place(page, m.cfg.columns, m.cfg.rows).size < page.size) {
                val moved = page.lastOrNull { it !== drag?.item } ?: break
                page.remove(moved)
                if (i + 1 >= l.pages.size) host.appendPage()
                l.pages[i + 1].add(0, moved)
            }
            i++
        }
    }

    private fun refreshPages(only: Set<Int>? = null) {
        val l = host.layout ?: return
        for ((i, p) in host.pageViews.withIndex()) {
            if (only != null && i !in only && i != host.currentPage() + 1) continue
            if (i < l.pages.size) {
                p.setItems(l.pages[i], animate = true)
                for (v in p.itemViews()) adopt(v)
            }
        }
    }

    private fun refreshDock() {
        val l = host.layout ?: return
        val dock = host.dockView ?: return
        val views = l.dock.mapNotNull { key -> dock.icons().firstOrNull { it.entry?.key == key } ?: host.dockIcon(key) }
        dock.bind(views, animate = true)
        for (v in views) adopt(v)
    }

    /** The dragged item's real view stays hidden wherever it now is. */
    private fun hideDragged() {
        val d = drag ?: return
        draggedView(d)?.alpha = 0f
    }

    private fun draggedView(d: Drag): View? =
        if (d.inDock) host.dockView?.icons()?.firstOrNull { it.entry?.key == (d.item as HomeItem.App).key }
        else host.pageViews.getOrNull(d.page)?.viewFor(d.item)

    private fun turnAtEdge() {
        if (drag == null) return
        val l = host.layout ?: return
        val pi = host.currentPage()
        val right = fingerX > m.w / 2f
        val next = if (right) pi + 1 else pi - 1
        if (next < 0) return
        if (next >= l.pages.size) host.appendPage()
        host.turnToPage(next)
        home.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        // Keep turning while the finger stays at the edge.
        home.postDelayed(edgeTurn, 900)
    }

    private fun drop() {
        val d = drag ?: return
        home.removeCallbacks(applyTarget)
        home.removeCallbacks(edgeTurn)
        // Apply where it was heading, then the copy springs into the real view's place.
        pendingTarget?.let { (dock, index) -> moveTo(dock, index) }
        pendingTarget = null
        val target = draggedView(d)
        drag = null
        dockDragKey = null
        if (target == null) { ghost.clear(); return }
        target.post {
            // Where the view will rest once its own reflow glide ends (its translation goes to zero).
            val o = screenOrigin(target)
            ghost.settle(o[0] - target.translationX, o[1] - target.translationY) {
                target.alpha = 1f
            }
        }
        host.layoutChanged()
    }

    // ------------------------------------------------------------------ the lifted copy

    private inner class Ghost(ctx: Context) : View(ctx) {
        private var pic: Picture? = null
        private var pw = 0
        private var ph = 0
        private var x = 0f
        private var y = 0f
        private var lift = 0f      // 0 = in place, 1 = lifted (scale and shadow)
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x40000000
            maskFilter = android.graphics.BlurMaskFilter(m.pt(14f), android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        private var settleSpring: Spring? = null
        private var settleStart = 0L
        private var fromX = 0f
        private var fromY = 0f
        private var toX = 0f
        private var toY = 0f
        private var onSettled: (() -> Unit)? = null

        init { setWillNotDraw(false); isClickable = false }

        fun lift(p: Picture, w: Int, h: Int, sx: Float, sy: Float, from: Float) {
            onSettled?.invoke()
            onSettled = null
            pic = p; pw = w; ph = h; x = sx; y = sy
            lift = from
            settleSpring = null
            visibility = VISIBLE
            animateLift(1f)
        }

        fun moveTo(nx: Float, ny: Float) { x = nx; y = ny; invalidate() }

        fun settle(tx: Float, ty: Float, then: () -> Unit) {
            fromX = x; fromY = y; toX = tx; toY = ty
            onSettled = then
            settleSpring = Spring(0.32f, 0.86f).apply { start(0f, 0f, 1000f) }
            settleStart = System.nanoTime()
            Choreographer.getInstance().postFrameCallback(settleFrame)
            animateLift(0f)
        }

        fun clear() { pic = null; visibility = GONE }

        private var liftAnim: android.animation.ValueAnimator? = null

        private fun animateLift(to: Float) {
            liftAnim?.cancel()
            liftAnim = android.animation.ValueAnimator.ofFloat(lift, to).apply {
                duration = 160
                addUpdateListener { lift = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        private val settleFrame = object : Choreographer.FrameCallback {
            override fun doFrame(now: Long) {
                val s = settleSpring ?: return
                val t = (now - settleStart) / 1e9
                val k = s.value(t) / 1000f
                x = fromX + (toX - fromX) * k
                y = fromY + (toY - fromY) * k
                invalidate()
                if (s.settled(t)) {
                    settleSpring = null
                    onSettled?.invoke()
                    onSettled = null
                    clear()
                } else Choreographer.getInstance().postFrameCallback(this)
            }
        }

        override fun onDraw(c: Canvas) {
            val p = pic ?: return
            val scale = 1f + 0.08f * lift
            c.save()
            c.translate(x + pw / 2f, y + ph / 2f)
            c.scale(scale, scale)
            c.translate(-pw / 2f, -ph / 2f)
            if (lift > 0f) {
                shadow.alpha = (0x40 * lift).toInt()
                val s = m.iconSize * 0.9f
                c.drawRoundRect(pw / 2f - s / 2f, m.pt(8f), pw / 2f + s / 2f, m.pt(8f) + s, s * 0.25f, s * 0.25f, shadow)
            }
            c.drawPicture(p)
            c.restore()
        }
    }

    // ------------------------------------------------------------------ the edit bar ("+" and "Done")

    /** Top of the screen while editing: "+" on the left, "Done" on the right, glass capsules. */
    inner class Bar(ctx: Context) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3DFFFFFF }
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF; style = Paint.Style.STROKE }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Fonts.text(600); textAlign = Paint.Align.CENTER }
        private val plus = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
        val done = RectF()
        val add = RectF()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val cy = h / 2f
            val bh = m.pt(34f)
            done.set(w - m.libMargin - m.pt(72f), cy - bh / 2f, w - m.libMargin, cy + bh / 2f)
            add.set(m.libMargin, cy - bh / 2f, m.libMargin + bh, cy + bh / 2f)
        }

        override fun onDraw(c: Canvas) {
            rim.strokeWidth = m.pt(1f)
            for (r in listOf(done, add)) {
                c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill)
                c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, rim)
            }
            text.textSize = m.pt(15f)
            c.drawText("Done", done.centerX(), done.centerY() - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2f, text)
            plus.strokeWidth = m.pt(2.2f)
            val k = m.pt(7f)
            c.drawLine(add.centerX() - k, add.centerY(), add.centerX() + k, add.centerY(), plus)
            c.drawLine(add.centerX(), add.centerY() - k, add.centerX(), add.centerY() + k, plus)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) return done.contains(e.x, e.y) || add.contains(e.x, e.y)
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                if (done.contains(e.x, e.y)) exit() else if (add.contains(e.x, e.y)) addClockWidget()
            }
            return true
        }
    }

    init { ghost.visibility = View.GONE }
}
