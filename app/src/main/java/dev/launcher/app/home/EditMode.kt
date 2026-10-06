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
import android.widget.FrameLayout
import dev.launcher.app.GlassStyle
import dev.launcher.app.drawer.LabelPainter
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.SpringValue
import dev.launcher.app.theme.Fonts
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/**
 * iOS edit mode ("jiggle mode") for the home screen: every icon and widget wiggles, a "–" badge removes it from home (apps
 * stay in the App Library), "Edit" opens the edit menu (Add Widget), "Done" (or a tap on empty space, back, home) leaves.
 *
 * Dragging: the item lifts (a copy follows the finger, slightly enlarged, with a soft shadow) while its real view stays,
 * hidden, in the grid; resting over a cell for a moment moves it there and the rest of the page flows around it on
 * springs. Over the dock an app joins the dock (up to its slots). Resting at the left or right edge turns the page (past
 * the last page a new one appears). On release the copy springs into the item's cell. The layout is saved at once.
 * An app dragged out of the App Library or Spotlight arrives the same way, as a new item.
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
        /** An item left home (an Android widget's id must be given back). */
        fun itemRemoved(item: HomeItem)
        /** Edit mode began or ended (indicator dots, edit bar, publishing). */
        fun editingChanged(active: Boolean)
        /** "Edit" in the edit bar: the edit menu, anchored at [button] (home coordinates). */
        fun showEditMenu(button: RectF)
        /** The iOS sizes [widget] can take. */
        fun widgetSizes(widget: HomeItem.Widget): List<WidgetSize>
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
            is HomeWidgetView -> v.editing = on
        }
    }

    /** New views appear while editing (an item moved to another page or into the dock): give them the edit look. */
    fun adopt(v: View) {
        if (!active) return
        when (v) {
            is IconView -> v.editing = true
            is HomeWidgetView -> v.editing = true
        }
    }

    // ------------------------------------------------------------------ wiggle

    private val phases = java.util.WeakHashMap<View, Float>()
    private var jiggling = false
    /** Home is blurred behind a menu or the widget gallery: the wiggle holds still (it would re-blur home every frame). */
    var jigglePaused = false

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
            if (jigglePaused) { Choreographer.getInstance().postFrameCallback(this); return }
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
        // About four wiggles a second (widgets less: they are bigger).
        val mp = Motion.profile
        val amp = if (v is HomeWidgetView) mp.jiggleDegrees * 0.375f else mp.jiggleDegrees
        v.rotation = amp * sin(t * 2 * Math.PI / mp.jigglePeriod + phase).toFloat()
    }

    // ------------------------------------------------------------------ touches while editing

    private val slop = ViewConfiguration.get(home.context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var candidate: View? = null
    private var candidateBadge = false
    private var moved = false
    private var resizing: HomeItem.Widget? = null   // the widget whose resize handle the finger holds

    /** Down on home while editing: true if it landed on an item (edit mode takes the touch). */
    fun onDown(x: Float, y: Float): Boolean {
        downX = x; downY = y; moved = false
        candidate = itemViewAt(x, y)
        candidateBadge = candidate?.let { onBadge(it, x, y) } ?: false
        resizing = candidate?.takeIf { !candidateBadge && onHandle(it, x, y) }?.let { v ->
            host.pageViews.firstNotNullOfOrNull { it.itemOf(v) } as? HomeItem.Widget
        }
        return candidate != null
    }

    private fun onHandle(v: View, x: Float, y: Float): Boolean {
        if (v !is HomeWidgetView) return false
        val c = v.handleCenter()
        val o = screenOrigin(v)
        return hypot(x - (o[0] + c[0]), y - (o[1] + c[1])) < m.pt(30f)
    }

    /** The handle follows the finger: the widget takes the allowed size nearest to it. */
    private fun resizeTowards(x: Float, y: Float) {
        val w = resizing ?: return
        val l = host.layout ?: return
        val pi = l.pages.indexOfFirst { p -> p.any { it === w } }
        if (pi < 0) return
        val v = host.pageViews.getOrNull(pi)?.viewFor(w) ?: return
        val o = screenOrigin(v)
        val wantX = (x - o[0]) / m.columnPitch
        val wantY = (y - o[1]) / m.cellHeight
        val best = host.widgetSizes(w).minByOrNull { s -> (s.spanX - wantX) * (s.spanX - wantX) + (s.spanY - wantY) * (s.spanY - wantY) } ?: return
        if (best.spanX == w.spanX && best.spanY == w.spanY) return
        resize(w, best)?.let { resizing = it }
    }

    /**
     * [widget] at [size], in its cell (moved left/up as needed to stay on the page); icons in the way move on, another widget
     * in the way sends it to the first place it fits on the page. The widget's card springs to the new size while its
     * content crossfades (the view stays: the item changes in place). Returns the widget, or null if it fits nowhere.
     */
    fun resize(widget: HomeItem.Widget, size: WidgetSize): HomeItem.Widget? {
        if (size.spanX == widget.spanX && size.spanY == widget.spanY) return widget
        val l = host.layout ?: return null
        val pi = l.pages.indexOfFirst { p -> p.any { it === widget } }
        if (pi < 0) return null
        val page = l.pages[pi]
        val cols = m.cfg.columns
        val rows = m.cfg.rows
        val oldSx = widget.spanX
        val oldSy = widget.spanY
        val oldCol = widget.col
        val oldRow = widget.row
        val col = widget.col.coerceIn(0, cols - size.spanX.coerceAtMost(cols))
        val row = widget.row.coerceIn(0, rows - size.spanY.coerceAtMost(rows))
        page.removeAll { it === widget }
        widget.spanX = size.spanX
        widget.spanY = size.spanY
        val off = Grid.putAt(page, widget, col, row, cols, rows)
        if (off == null) {
            val fit = Grid.firstFree(page, size.spanX, size.spanY, cols, rows)
            if (fit == null) {
                // Fits nowhere at this size: back as it was.
                widget.spanX = oldSx; widget.spanY = oldSy; widget.col = oldCol; widget.row = oldRow
                page += widget
                return null
            }
            widget.col = fit[0]; widget.row = fit[1]
            page += widget
        } else if (off.isNotEmpty()) pushOff(pi + 1, off)
        (host.pageViews.getOrNull(pi)?.viewFor(widget) as? HomeWidgetView)?.setSpan(size.spanX, size.spanY, animate = true)
        refreshPages(setOf(pi, pi + 1))
        host.layoutChanged()
        home.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        return widget
    }

    /** A widget's style (the clock: glass or solid; an Android widget: a glass platter behind it or none), in place. */
    fun restyle(widget: HomeItem.Widget, style: String?) {
        val l = host.layout ?: return
        val pi = l.pages.indexOfFirst { p -> p.any { it === widget } }
        if (pi < 0) return
        widget.style = style
        val page = host.pageViews.getOrNull(pi)
        val v = page?.viewFor(widget)
        if (v is AppWidgetFrame) v.setGlassBacking(style == HomeItem.Widget.GLASS)
        else page?.rebuildItem(widget)
        host.layoutChanged()
    }

    /** The rest of a touch edit mode took. */
    fun onTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (drag != null) { dragTo(e.x, e.y); return }
                if (resizing != null) { if (hypot(e.x - downX, e.y - downY) > slop) { moved = true; resizeTowards(e.x, e.y) }; return }
                if (!moved && hypot(e.x - downX, e.y - downY) > slop) {
                    moved = true
                    candidate?.let { if (!candidateBadge) beginDrag(it, downX, downY) }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (drag != null) { drop(); return }
                if (resizing != null) { resizing = null; return }
                val c = candidate
                if (!moved && c != null && candidateBadge && onBadge(c, e.x, e.y)) remove(c)
            }
            MotionEvent.ACTION_CANCEL -> { resizing = null; if (drag != null) drop() }
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
        val c = when (v) { is IconView -> v.badgeCenter(); is HomeWidgetView -> v.badgeCenter(); else -> return false }
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

    // ------------------------------------------------------------------ removing and adding

    /** From the long-press menu ("Remove from Home Screen" / "Remove Widget"), with or without edit mode. */
    fun removeFromHome(v: View) = remove(v)

    private fun remove(v: View) {
        val l = host.layout ?: return
        val dock = host.dockView
        // It shrinks away where it is (the same leave as everywhere on home) while the others close the gap in the dock.
        if (dock != null && v is IconView && dock.icons().any { it === v }) {
            val key = v.entry?.key ?: return
            l.dock.remove(key)
            dock.bind(dock.icons().filter { it !== v }, animate = true)
        } else {
            val page = host.pageViews.firstOrNull { it.itemOf(v) != null } ?: return
            val item = page.itemOf(v) ?: return
            val pi = host.pageViews.indexOf(page)
            l.pages[pi].removeAll { it === item }   // its cell stays empty (iOS 18: nothing slides over)
            host.itemRemoved(item)
            page.setItems(l.pages[pi], animate = true)
        }
        home.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        host.layoutChanged()
    }

    /**
     * A new widget (from the gallery) at the top of the page on screen, icons in its way moving on (iOS); if a widget is
     * there already, the first place it fits on this page or a later one.
     */
    fun addWidget(widget: HomeItem.Widget) {
        val l = host.layout ?: return
        val pi = host.currentPage().coerceIn(0, l.pages.size - 1)
        val cols = m.cfg.columns
        val rows = m.cfg.rows
        val off = Grid.putAt(l.pages[pi], widget, 0, 0, cols, rows)
        var landed = pi
        if (off != null) pushOff(pi + 1, off)
        else {
            var p = pi
            while (true) {
                if (p >= l.pages.size) host.appendPage()
                val fit = Grid.firstFree(l.pages[p], widget.spanX, widget.spanY, cols, rows)
                if (fit != null) { widget.col = fit[0]; widget.row = fit[1]; l.pages[p] += widget; break }
                p++
            }
            landed = p
        }
        refreshPages()
        host.layoutChanged()
        // It went to another page (a widget was in the way here): show it.
        if (landed != pi) host.turnToPage(landed)
    }

    /** Icons pushed off a page go to the first free cell from page [start] on (a new page at the end if needed). */
    private fun pushOff(start: Int, items: List<HomeItem>) {
        val l = host.layout ?: return
        for (item in items) {
            var p = start
            while (true) {
                if (p >= l.pages.size) host.appendPage()
                val fit = Grid.firstFree(l.pages[p], 1, 1, m.cfg.columns, m.cfg.rows)
                if (fit != null) { item.col = fit[0]; item.row = fit[1]; l.pages[p] += item; break }
                p++
            }
        }
    }

    // ------------------------------------------------------------------ dragging

    private class Drag(val item: HomeItem, var inDock: Boolean, var page: Int, val grabX: Float, val grabY: Float)

    private var drag: Drag? = null
    private var fingerX = 0f
    private var fingerY = 0f
    /** Where a dragged item would go: a dock slot, or a cell (index row * columns + col) of the page on screen. */
    private data class Target(val dock: Boolean, val index: Int)
    private var pendingTarget: Target? = null    // waiting for the finger to rest
    private val ghost = Ghost(home.context)
    private val applyTarget = Runnable { pendingTarget?.let { moveTo(it) } }
    private val edgeTurn = Runnable { turnAtEdge() }

    /** The view drawing the lifted copy; home adds it on top of everything. */
    val ghostView: View get() = ghost

    /** A drag is under way. */
    val dragging get() = drag != null

    /** The lifted copy of [v]: the icon alone (no label, no badge), or the whole widget. */
    private fun recordLift(v: View): Picture {
        val pic = Picture()
        val c = pic.beginRecording(maxOf(1, v.width), maxOf(1, v.height))
        if (v is IconView) { v.labelHidden = true; v.editBadgeHidden = true }
        if (v is HomeWidgetView) v.editBadgeHidden = true
        v.draw(c)
        if (v is IconView) { v.labelHidden = false; v.editBadgeHidden = false }
        if (v is HomeWidgetView) v.editBadgeHidden = false
        pic.endRecording()
        return pic
    }

    /** The point the copy scales about: an icon's centre (its label is not part of the copy), a widget's centre. */
    private fun pivotOf(v: View): FloatArray {
        if (v is IconView) { val r = v.iconBoundsInView(RectF()); return floatArrayOf(r.centerX(), r.centerY()) }
        return floatArrayOf(v.width / 2f, v.height / 2f)
    }

    private fun beginDrag(v: View, x: Float, y: Float, startLift: Float = 0f) {
        if (host.layout == null) return
        val dock = host.dockView
        val inDock = dock != null && v is IconView && dock.icons().any { it === v }
        val item: HomeItem = if (inDock) HomeItem.App((v as IconView).entry?.key ?: return) else {
            val page = host.pageViews.firstOrNull { it.itemOf(v) != null } ?: return
            page.itemOf(v) ?: return
        }
        val pic = recordLift(v)
        val o = screenOrigin(v)
        drag = Drag(item, inDock, if (inDock) -1 else host.pageViews.indexOfFirst { it.itemOf(v) != null }, x - o[0], y - o[1])
        v.alpha = 0f
        fingerX = x; fingerY = y
        ghost.lift(pic, o[0], o[1], pivotOf(v), startLift, 1f)
        home.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /**
     * An app from the App Library or Spotlight becomes a new home item under the finger: [icon] is a home icon for it laid
     * out off screen (its copy is what is dragged), [from] where the app's icon was (home coordinates), so the copy starts
     * there at that size and grows to a home icon.
     */
    fun beginExternalDrag(key: String, icon: IconView, from: RectF, x: Float, y: Float) {
        if (host.layout == null || drag != null) return
        val pic = recordLift(icon)
        val iconRect = icon.iconBoundsInView(RectF())
        val scale0 = if (iconRect.width() > 0f) from.width() / iconRect.width() else 1f
        // The copy's top-left such that its icon sits on [from].
        val sx = from.centerX() - iconRect.centerX()
        val sy = from.centerY() - iconRect.centerY()
        drag = Drag(HomeItem.App(key), false, -1, x - sx, y - sy)
        fingerX = x; fingerY = y
        ghost.lift(pic, sx, sy, floatArrayOf(iconRect.centerX(), iconRect.centerY()), 0f, scale0)
        home.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** The finger moved during an external drag that home (not edit mode's own touch) is following. */
    fun externalMove(x: Float, y: Float) = dragTo(x, y)

    /** The finger lifted (or the touch was taken) during an external drag. */
    fun externalUp() { if (drag != null) drop() }

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

    private fun targetAt(x: Float, y: Float, d: Drag): Target? {
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
            return Target(true, best)
        }
        val pi = host.currentPage()
        if (pi !in l.pages.indices) return null
        val cols = m.cfg.columns
        val rows = m.cfg.rows
        val sp = Grid.span(d.item, cols, rows)
        val col: Int
        val row: Int
        if (sp[0] == 1 && sp[1] == 1) {
            // An icon goes to the cell under the finger.
            val cell = Grid.cellAt(x, y, m)
            if (cell < 0) return null
            col = cell % cols; row = cell / cols
        } else {
            // A widget goes where its lifted copy's top-left corner is nearest a cell's.
            col = kotlin.math.round((x - d.grabX - m.cellLeft(0)) / m.columnPitch).toInt().coerceIn(0, cols - sp[0])
            row = kotlin.math.round((y - d.grabY - m.gridTop) / m.cellHeight).toInt().coerceIn(0, rows - sp[1])
        }
        // Where it is already: nothing to do.
        if (!d.inDock && d.page == pi && d.item.col == col && d.item.row == row) return null
        if (!Grid.canPut(l.pages[pi], d.item, col, row, cols, rows)) return null
        return Target(false, row * cols + col)
    }

    /** Moves the dragged item to a dock slot, or to a cell of the page on screen (icons in the way move on, [Grid.putAt]). */
    private fun moveTo(t: Target) {
        val d = drag ?: return
        val l = host.layout ?: return
        val pi = host.currentPage().coerceIn(0, l.pages.size - 1)
        val cols = m.cfg.columns
        val rows = m.cfg.rows
        if (!t.dock && !Grid.canPut(l.pages[pi], d.item, t.index % cols, t.index / cols, cols, rows)) return
        // The cell it leaves on this page (icons between can step back into it).
        val origin = if (!t.dock && !d.inDock && d.page == pi && d.item.col >= 0) d.item.row * cols + d.item.col else null
        // Out of where it is now (an item from outside home is nowhere yet).
        if (d.inDock) l.dock.remove((d.item as HomeItem.App).key) else if (d.page in l.pages.indices) l.pages[d.page].removeAll { it === d.item }
        val fromPage = d.page
        if (t.dock) {
            val key = (d.item as HomeItem.App).key
            l.dock.add(t.index.coerceIn(0, l.dock.size), key)
            d.item.col = -1; d.item.row = -1
            d.inDock = true
            d.page = -1
        } else {
            val off = Grid.putAt(l.pages[pi], d.item, t.index % cols, t.index / cols, cols, rows, origin) ?: emptyList()
            d.inDock = false
            d.page = pi
            if (off.isNotEmpty()) pushOff(pi + 1, off)
        }
        refreshPages(setOf(fromPage, pi, pi + 1))
        refreshDock()
        hideDragged()
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
        // Apply where it was heading; an item from outside that never found a place takes the first free cell of the page on
        // screen (or of a later page).
        pendingTarget?.let { moveTo(it) }
        pendingTarget = null
        if (!d.inDock && d.page < 0) {
            val l = host.layout
            if (l != null) {
                val pi = host.currentPage().coerceIn(0, l.pages.size - 1)
                val fit = Grid.firstFree(l.pages[pi], 1, 1, m.cfg.columns, m.cfg.rows)
                if (fit != null) moveTo(Target(false, fit[1] * m.cfg.columns + fit[0]))
                else {
                    pushOff(pi + 1, listOf(d.item))
                    d.page = l.pages.indexOfFirst { p -> p.any { it === d.item } }
                    refreshPages()
                }
            }
        }
        val target = draggedView(d)
        drag = null
        if (target == null) { ghost.clear(); return }
        target.post {
            // Where the view will rest once its own reflow glide ends (its translation goes to zero); the copy's pivot (the
            // icon's centre) lands on the view's.
            val o = screenOrigin(target)
            val pv = pivotOf(target)
            ghost.settle(o[0] - target.translationX + pv[0], o[1] - target.translationY + pv[1]) {
                target.alpha = 1f
            }
        }
        host.layoutChanged()
    }

    // ------------------------------------------------------------------ the lifted copy

    private inner class Ghost(ctx: Context) : View(ctx) {
        private var pic: Picture? = null
        private var x = 0f
        private var y = 0f
        private var pivotX0 = 0f
        private var pivotY0 = 0f
        private var size0 = 1f     // scale the copy starts at (an App Library icon is smaller), reaching 1 as it lifts
        // 0 = in place, 1 = lifted (scale and shadow).
        private val liftK = SpringValue(0f, 100f, { invalidate() })
        private val settleK = SpringValue(0f, 1000f, { k -> place(k) }, { onSettleEnd() })
        private var fromX = 0f
        private var fromY = 0f
        private var toX = 0f
        private var toY = 0f
        private var onSettled: (() -> Unit)? = null
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x40000000
            maskFilter = android.graphics.BlurMaskFilter(m.pt(14f), android.graphics.BlurMaskFilter.Blur.NORMAL)
        }

        init { setWillNotDraw(false); isClickable = false }

        fun lift(p: Picture, sx: Float, sy: Float, pivot: FloatArray, from: Float, startScale: Float) {
            // A copy still settling from the last drop lands at once (its view must show again).
            onSettled?.invoke()
            onSettled = null
            settleK.stop()
            pic = p; x = sx; y = sy
            pivotX0 = pivot[0]; pivotY0 = pivot[1]
            size0 = startScale
            liftK.snapTo(from)
            visibility = VISIBLE
            liftK.animateTo(1f, Motion.profile.dragLift)
        }

        fun moveTo(nx: Float, ny: Float) { x = nx; y = ny; invalidate() }

        /** Springs the copy so that its pivot lands on ([px], [py]). */
        fun settle(px: Float, py: Float, then: () -> Unit) {
            fromX = x; fromY = y; toX = px - pivotX0; toY = py - pivotY0
            size0 = 1f
            onSettled = then
            settleK.snapTo(0f)
            settleK.animateTo(1f, Motion.profile.dragSettle)
            liftK.animateTo(0f, Motion.profile.dragSettle)
        }

        private fun place(k: Float) {
            x = fromX + (toX - fromX) * k
            y = fromY + (toY - fromY) * k
            invalidate()
        }

        private fun onSettleEnd() {
            if (settleK.value < 1f) return
            onSettled?.invoke()
            onSettled = null
            clear()
        }

        fun clear() { pic = null; liftK.stop(); visibility = GONE }

        override fun onDraw(c: Canvas) {
            val p = pic ?: return
            val lift = liftK.value
            val grow = size0 + (1f - size0) * lift.coerceIn(0f, 1f)
            val scale = grow * (1f + 0.08f * lift)
            c.save()
            c.translate(x + pivotX0, y + pivotY0)
            c.scale(scale, scale)
            c.translate(-pivotX0, -pivotY0)
            if (lift > 0f) {
                shadow.alpha = (0x40 * lift.coerceIn(0f, 1f)).toInt()
                val s = m.iconSize * 0.9f
                c.drawRoundRect(pivotX0 - s / 2f, pivotY0 - s / 2f + m.pt(8f), pivotX0 + s / 2f, pivotY0 + s / 2f + m.pt(8f), s * 0.25f, s * 0.25f, shadow)
            }
            c.drawPicture(p)
            c.restore()
        }
    }

    // ------------------------------------------------------------------ the edit bar ("Edit" and "Done")

    /** Top of the screen while editing: "Edit" on the left (its menu adds widgets), "Done" on the right; liquid glass capsules. */
    inner class Bar(ctx: Context) : FrameLayout(ctx) {
        private val bh = m.pt(36f)
        val editGlass = GlassView(ctx, GlassStyle.IOS, m.u).apply { radius = bh / 2f }
        val doneGlass = GlassView(ctx, GlassStyle.IOS, m.u).apply { radius = bh / 2f }
        // A soft shadow keeps the white labels readable over a light wallpaper (the glass itself is clear).
        private val text = LabelPainter(m.pt(16f), Color.WHITE, Paint.Align.CENTER, Fonts.text(600)).apply {
            paint.setShadowLayer(m.pt(3f), 0f, m.pt(0.5f), 0x66000000)
        }
        private var pressedEdit = false
        private var pressedDone = false
        // Presses dim the label and the capsule a little, in and out on a spring (not at once).
        private val editPress = SpringValue(0f, 100f, { invalidate() })
        private val donePress = SpringValue(0f, 100f, { invalidate() })
        // The capsules are clear glass: over a light wallpaper the white labels need a slightly darker capsule to sit on.
        private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val tintRect = RectF()

        init {
            setWillNotDraw(false)
            clipChildren = false
            addView(editGlass, LayoutParams(m.pt(66f).toInt(), bh.toInt()))
            addView(doneGlass, LayoutParams(m.pt(70f).toInt(), bh.toInt()))
        }

        fun glassViews(): List<GlassView> = listOf(editGlass, doneGlass)

        /** The "Edit" capsule as it looks now (it stays sharp above its menu while home blurs). */
        fun editButtonPicture(): Picture {
            val p = Picture()
            val c = p.beginRecording(maxOf(1, editGlass.width), maxOf(1, editGlass.height))
            editGlass.draw(c)
            tint.alpha = 0x2E
            c.drawRoundRect(0f, 0f, editGlass.width.toFloat(), editGlass.height.toFloat(), bh / 2f, bh / 2f, tint)
            text.draw(c, "Edit", "Edit", editGlass.width / 2f, text.baselineFor(editGlass.height / 2f), editGlass.width.toFloat())
            p.endRecording()
            return p
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val top = ((h - bh) / 2f).toInt()
            editGlass.layoutParams = (editGlass.layoutParams as LayoutParams).apply { leftMargin = m.libMargin.toInt(); topMargin = top }
            doneGlass.layoutParams = (doneGlass.layoutParams as LayoutParams).apply { leftMargin = (w - m.libMargin - m.pt(70f)).toInt(); topMargin = top }
        }

        // 0 = away (above the screen's edge, faded), 1 = in place. The capsules slide down into place as editing begins
        // and back up as it ends, on a spring that a quick Done can reverse midway.
        private val shown: SpringValue = SpringValue(0f, 1000f, { k -> place(k) }, { if (alpha <= 0.001f) visibility = View.GONE })

        private fun place(k: Float) {
            val kk = k.coerceIn(0f, 1.2f)
            translationY = -(1f - kk) * (bh + m.pt(24f))
            alpha = kk.coerceIn(0f, 1f)
            editGlass.invalidate(); doneGlass.invalidate()   // the glass refracts what is under it where it is now
        }

        fun show() {
            if (visibility != View.VISIBLE) { visibility = View.VISIBLE; place(shown.value) }
            shown.animateTo(1f, Motion.profile.editBar)
        }

        fun hide() {
            if (visibility != View.VISIBLE) return
            shown.animateTo(0f, Motion.profile.editBar)
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            drawLabel(canvas, editGlass, "Edit", editPress.value)
            drawLabel(canvas, doneGlass, "Done", donePress.value)
        }

        private fun drawLabel(canvas: Canvas, g: View, label: String, press: Float) {
            if (g.width == 0) return
            val k = press.coerceIn(0f, 1f)
            tintRect.set(g.left.toFloat(), g.top.toFloat(), g.right.toFloat(), g.bottom.toFloat())
            tint.alpha = (0x2E + 0x1A * k).toInt()
            canvas.drawRoundRect(tintRect, bh / 2f, bh / 2f, tint)
            text.draw(canvas, label, label, g.left + g.width / 2f, text.baselineFor(g.top + g.height / 2f), g.width.toFloat(), (255 - 0x60 * k).toInt())
        }

        private fun setPressed(edit: Boolean, done: Boolean) {
            pressedEdit = edit; pressedDone = done
            editPress.animateTo(if (edit) 1f else 0f, if (edit) PRESS_IN else PRESS_OUT)
            donePress.animateTo(if (done) 1f else 0f, if (done) PRESS_IN else PRESS_OUT)
        }

        private fun hit(g: View, e: MotionEvent): Boolean {
            val s = m.pt(6f)
            return e.x >= g.left - s && e.x <= g.right + s && e.y >= g.top - s && e.y <= g.bottom + s
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    setPressed(hit(editGlass, e), hit(doneGlass, e))
                    return pressedEdit || pressedDone
                }
                MotionEvent.ACTION_UP -> {
                    val edit = pressedEdit && hit(editGlass, e)
                    val done = pressedDone && hit(doneGlass, e)
                    setPressed(edit = false, done = false)
                    if (done) exit()
                    if (edit) host.showEditMenu(RectF(left + editGlass.left.toFloat(), top + editGlass.top.toFloat(),
                        left + editGlass.right.toFloat(), top + editGlass.bottom.toFloat()))
                }
                MotionEvent.ACTION_CANCEL -> setPressed(edit = false, done = false)
            }
            return true
        }
    }

    init { ghost.visibility = View.GONE }

    private companion object {
        val PRESS_IN = dev.launcher.app.motion.SpringSpec(0.12f, 1f)
        val PRESS_OUT = dev.launcher.app.motion.SpringSpec(0.3f, 1f)
    }
}
