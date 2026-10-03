package dev.launcher.app.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Choreographer
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.TextClock
import dev.launcher.app.AppLog
import dev.launcher.app.HomeBridge
import dev.launcher.app.Spring
import dev.launcher.app.Wallpaper
import dev.launcher.app.apps.AppEntry
import dev.launcher.app.apps.Apps
import dev.launcher.app.apps.Icons
import dev.launcher.app.drawer.AppDrawer
import dev.launcher.app.drawer.DrawerHost
import dev.launcher.app.drawer.Drawers
import dev.launcher.app.motion.Motion
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The home screen: wallpaper, then (in [fg], everything the picture of home records above the wallpaper) the drawer's
 * background, the pages, the dock and page indicator, and the drawer.
 *
 * Pages move as one strip at a fractional page position [pos]. The drawer is placed by [HomeConfig.drawerPlacement]:
 * as a page after the last or before the first (it is then simply another position of the strip), or as a sheet that
 * follows a swipe up. Every movement is a spring that a touch can grab mid-flight, keeping its position.
 */
@SuppressLint("ViewConstructor")
class HomeScreen(ctx: Context, private val listener: Listener) : FrameLayout(ctx), DrawerHost {
    interface Listener {
        fun launch(e: AppEntry, iconOnScreen: RectF, icon: Drawable?)
        /** Everything came to rest: a good moment to record the picture of home. */
        fun onHomeSettled()
    }

    var cfg = HomeConfig()
        private set
    private var m: HomeMetrics? = null
    override val metrics: HomeMetrics get() = m!!
    override val placement: DrawerPlacement get() = cfg.drawerPlacement

    val wallpaperView = WallpaperView(ctx, ctx.resources.displayMetrics.density * REVEAL_CELL_DP)
    /** Everything above the wallpaper; recorded as the content layer of the picture of home. */
    val fg = FrameLayout(ctx)
    private val backdrop = BackdropView(ctx)
    private val pagesLayer = FrameLayout(ctx)
    private var dockShadow: DockShadow? = null
    private var dock: DockView? = null
    private var indicator: PageIndicator? = null
    private var drawer: AppDrawer? = null
    private val pages = ArrayList<PageView>()
    val clocks = ArrayList<TextClock>()

    private var layout: HomeLayout? = null
    private var wallpaper: Wallpaper? = null
    private var topInset = 0
    private var bottomInset = 0
    private var deviceRadius = 0f

    // Strip position (pages, and the drawer when it is a page) and sheet progress (swipe-up drawer).
    private var pos = 0f
    private var sheet = 0f
    private var drawerWasOpen = false

    init {
        addView(wallpaperView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(fg, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        fg.clipChildren = false
    }

    // ================================================================== setup

    fun setConfig(c: HomeConfig) {
        if (c == cfg && m != null) return
        cfg = c
        Icons.shape = c.iconShape
        pos = 0f; sheet = 0f
        build()
    }

    fun setLayout(l: HomeLayout) {
        layout = l
        bindLayout()
    }

    fun layoutForSaving(): HomeLayout? = layout

    fun setWallpaper(w: Wallpaper?) {
        wallpaper = w
        wallpaperView.wallpaper = w
        backdrop.wallpaper = w
        drawer?.setWallpaper(w)
        applyGlassWallpaper()
    }

    /** Glass surfaces (dock, indicator), for the wallpaper reveal to drive frame by frame. */
    fun glassViews(): List<GlassView> = listOfNotNull(dock?.glass, indicator?.glass)

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val top: Int
        val bottom: Int
        var ime = 0
        if (Build.VERSION.SDK_INT >= 30) {
            // Ignoring visibility: a full-screen app hiding the status bar must not change home's layout (it used to rebuild
            // home, losing the App Library's state, while a closing card was flying).
            top = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
            bottom = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom
            ime = insets.getInsets(WindowInsets.Type.ime()).bottom
        } else {
            @Suppress("DEPRECATION") top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION") bottom = insets.systemWindowInsetBottom
        }
        val radius = if (Build.VERSION.SDK_INT >= 31) insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius?.toFloat() ?: 0f else 0f
        val minBottom = (20 * resources.displayMetrics.density).roundToInt()   // our gesture strip is at least this tall
        val b = maxOf(bottom, minBottom)
        if (top != topInset || b != bottomInset || radius != deviceRadius) {
            if (m != null) AppLog.log("[home] insets changed (top $topInset->$top, bottom $bottomInset->$b, corner $deviceRadius->$radius): rebuilding")
            topInset = top; bottomInset = b; deviceRadius = radius
            build()
        }
        drawer?.setImeInset(maxOf(0, ime - bottom))
        return insets
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            if (oldw != 0) AppLog.log("[home] size changed (${oldw}x$oldh -> ${w}x$h): rebuilding")
            post { build() }
        }
    }

    /** (Re)creates everything above the wallpaper for the current size, insets and config. */
    private fun build() {
        if (width == 0 || height == 0) return
        val metrics = HomeMetrics(width, height, topInset, bottomInset, deviceRadius, cfg)
        m = metrics
        Icons.homeSize = metrics.iconSize
        fg.removeAllViews()
        stopAnimations()
        fg.addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        fg.addView(pagesLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val shadow = DockShadow(context).also { dockShadow = it }
        fg.addView(shadow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        shadow.place(metrics.dockInset, metrics.dockTop, metrics.w - metrics.dockInset, metrics.dockBottom, metrics.dockRadius)
        val d = DockView(context, metrics).also { dock = it }
        fg.addView(d, LayoutParams((metrics.w - 2 * metrics.dockInset).roundToInt(), metrics.dockHeight.roundToInt()).apply {
            leftMargin = metrics.dockInset.roundToInt()
            topMargin = metrics.dockTop.roundToInt()
        })
        val ind = PageIndicator(context, metrics).also { indicator = it }
        fg.addView(ind, LayoutParams(ind.widthFor(1), metrics.indicatorHeight.roundToInt()))
        val dr = Drawers.create(cfg.drawerStyle, context, this).also { drawer = it }
        fg.addView(dr.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        dr.setWallpaper(wallpaper)
        applyGlassWallpaper()
        bindLayout()
    }

    private fun applyGlassWallpaper() {
        val metrics = m ?: return
        for (g in glassViews()) g.setWallpaper(wallpaper, metrics.w, metrics.h, resources.displayMetrics.density * REVEAL_CELL_DP)
    }

    private fun bindLayout() {
        val metrics = m ?: return
        val l = layout ?: return
        pagesLayer.removeAllViews()
        pages.clear()
        clocks.clear()
        for (items in l.pages) {
            val p = PageView(context, metrics) { item -> viewFor(item, metrics) }
            p.bind(items)
            pages += p
            pagesLayer.addView(p, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        dock?.bind(l.dock.mapNotNull { key -> Apps[key]?.let { appIcon(it, metrics, label = false) } })
        indicator?.let { ind ->
            ind.setPages(pages.size)
            ind.layoutParams = (ind.layoutParams as LayoutParams).apply {
                width = ind.widthFor(pages.size)
                leftMargin = ((metrics.w - width) / 2f).roundToInt()
                topMargin = metrics.indicatorTop.roundToInt()
            }
            ind.visibility = if (pages.size > 1) View.VISIBLE else View.INVISIBLE
        }
        pos = pos.coerceIn(minPos(), maxPos())
        applyPositions()
        // Icons for what is on screen first, then the rest of the library in the background.
        Icons.preload(l.pages.flatten().filterIsInstance<HomeItem.App>().mapNotNull { Apps[it.key] } + l.dock.mapNotNull { Apps[it] }, metrics.iconSize)
        Icons.preload(Apps.all, metrics.iconSize)
        // Launch screens for the apps on home, so a cold launch has the app's own colour from its first frame.
        dev.launcher.app.apps.SplashColors.warm(context, l.pages.flatten().filterIsInstance<HomeItem.App>().mapNotNull { Apps[it.key]?.pkg } + l.dock.mapNotNull { Apps[it]?.pkg })
        post { publishIcons() }
    }

    private fun viewFor(item: HomeItem, metrics: HomeMetrics): View? = when (item) {
        is HomeItem.App -> Apps[item.key]?.let { appIcon(it, metrics, label = cfg.showLabels) }
        is HomeItem.Widget -> if (item.kind == "clock") ClockWidgetView(context, metrics).also { clocks += it.clocks } else null
        is HomeItem.Folder -> null   // edit mode build
    }

    private fun appIcon(e: AppEntry, metrics: HomeMetrics, label: Boolean) = IconView(context, metrics, label).apply {
        bind(e)
        setOnClickListener { v ->
            val icon = v as IconView
            // This copy is the one its card returns to (an app can be both in the dock and on a page).
            anchors[e.pkg] = icon
            publishIcons()
            val rect = icon.iconOnScreen(RectF())
            listener.launch(e, rect, Icons.cached(e, metrics.iconSize)?.let { BitmapDrawable(resources, it) })
        }
    }

    /** The installed apps changed: prune and add to the layout, refresh the drawer. Returns true if the layout changed. */
    fun appsChanged(): Boolean {
        val l = layout ?: return false
        val changed = HomeModel.sync(l, Apps.all, cfg)
        if (changed) bindLayout()
        drawer?.appsChanged()
        return changed
    }

    // ================================================================== positions

    private val libIndex: Int?
        get() = when (cfg.drawerPlacement) {
            DrawerPlacement.PAGE_AFTER_LAST -> pages.size
            DrawerPlacement.PAGE_BEFORE_FIRST -> -1
            DrawerPlacement.SWIPE_UP -> null
        }

    private fun minPos() = if (libIndex == -1) -1f else 0f
    private fun maxPos() = if (libIndex == pages.size) pages.size.toFloat() else (pages.size - 1).coerceAtLeast(0).toFloat()

    /** How far the drawer is open, whatever its placement (0..1). */
    private fun drawerProgress(): Float {
        val li = libIndex ?: return sheet.coerceIn(0f, 1f)
        return (1f - abs(li - pos)).coerceIn(0f, 1f)
    }

    private fun applyPositions() {
        val metrics = m ?: return
        val w = metrics.w.toFloat()
        val h = metrics.h.toFloat()
        for ((i, p) in pages.withIndex()) {
            p.translationX = (i - pos) * w
            p.visibility = if (abs(i - pos) < 1f) View.VISIBLE else View.INVISIBLE
        }
        val dp = drawerProgress()
        val dv = drawer?.view
        var shift = 0f
        when (cfg.drawerPlacement) {
            DrawerPlacement.PAGE_AFTER_LAST -> {
                dv?.translationX = (pages.size - pos) * w
                shift = -(pos - (pages.size - 1)).coerceIn(0f, 1f) * w
            }
            DrawerPlacement.PAGE_BEFORE_FIRST -> {
                dv?.translationX = (-1 - pos) * w
                shift = (-pos).coerceIn(0f, 1f) * w
            }
            DrawerPlacement.SWIPE_UP -> {
                dv?.translationY = (1f - sheet) * h
                val k = 1f - 0.05f * dp
                for (v in listOf(pagesLayer, dock, dockShadow, indicator)) v?.apply { alpha = 1f - dp; scaleX = k; scaleY = k }
            }
        }
        dv?.visibility = if (dp > 0f) View.VISIBLE else View.INVISIBLE
        backdrop.alpha = dp
        backdrop.visibility = if (dp > 0f) View.VISIBLE else View.INVISIBLE
        for (v in listOf(dock, dockShadow, indicator)) v?.translationX = shift
        dock?.glass?.invalidate()
        indicator?.glass?.invalidate()
        indicator?.setPosition(pos.coerceIn(0f, (pages.size - 1).coerceAtLeast(0).toFloat()))
        drawer?.setOpenProgress(dp)
        if (dp > 0f) drawerWasOpen = true
    }

    // ================================================================== touch

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private enum class Drag { NONE, PAGES, SHEET, IGNORED }
    private var drag = Drag.NONE
    private var downX = 0f
    private var downY = 0f
    private var pos0 = 0f
    private var sheet0 = 0f
    private var startPage = 0
    private var vt: VelocityTracker? = null

    private fun canPage(): Boolean {
        if (pages.size <= 1 && libIndex == null) return false
        if (cfg.drawerPlacement == DrawerPlacement.SWIPE_UP && sheet > 0f) return false
        val d = drawer
        return !(d != null && drawerProgress() > 0.5f && d.capturesGestures())
    }

    /** Decides what a moving finger does; returns true if this view takes the gesture over. */
    private fun decide(e: MotionEvent): Boolean {
        if (m == null) return false
        val dx = e.x - downX
        val dy = e.y - downY
        if (abs(dx) < slop && abs(dy) < slop) return false
        // From here the content follows the finger 1:1 (it does not jump by the slop it took to decide).
        if (abs(dx) > abs(dy) && canPage()) { downX = e.x; downY = e.y; beginPages(); return true }
        if (cfg.drawerPlacement == DrawerPlacement.SWIPE_UP && abs(dy) > abs(dx)) {
            val d = drawer
            if (sheet < 0.5f && dy < 0) { downX = e.x; downY = e.y; beginSheet(); return true }
            if (sheet >= 0.5f && dy > 0 && d != null && !d.canScrollBack() && !d.capturesGestures()) { downX = e.x; downY = e.y; beginSheet(); return true }
        }
        drag = Drag.IGNORED
        return false
    }

    private fun track(e: MotionEvent) {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { vt?.recycle(); vt = VelocityTracker.obtain() }
        vt?.addMovement(e)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        track(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                drag = Drag.NONE
                HomeBridge.onHomeTouched?.invoke()
                // A touch on a moving strip or sheet grabs it where it is (no tap goes through).
                if (pagerAnimating) { pagerAnimating = false; beginPages(); return true }
                if (sheetAnimating) { sheetAnimating = false; beginSheet(); return true }
            }
            MotionEvent.ACTION_MOVE -> if (drag == Drag.NONE) return decide(e)
        }
        return drag == Drag.PAGES || drag == Drag.SHEET
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_DOWN) track(e)
        else if (drag == Drag.NONE) { track(e); downX = e.x; downY = e.y }
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (drag == Drag.NONE) decide(e)
                when (drag) {
                    Drag.PAGES -> dragPages(e.x - downX)
                    Drag.SHEET -> dragSheet(e.y - downY)
                    else -> {}
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                val vy = vt?.yVelocity ?: 0f
                when (drag) {
                    Drag.PAGES -> releasePages(vx)
                    Drag.SHEET -> releaseSheet(vy)
                    else -> {}
                }
                drag = Drag.NONE
            }
        }
        return true
    }

    // ---- pages

    private fun beginPages() {
        drag = Drag.PAGES
        pos0 = pos
        startPage = pos.roundToInt()
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun dragPages(dx: Float) {
        val w = metrics.w.toFloat()
        val raw = pos0 - dx / w
        val lo = minPos()
        val hi = maxPos()
        pos = when {
            raw < lo -> lo + Motion.rubberBand((raw - lo) * w, w) / w
            raw > hi -> hi + Motion.rubberBand((raw - hi) * w, w) / w
            else -> raw
        }
        applyPositions()
    }

    private fun releasePages(vx: Float) {
        val fling = Motion.profile.pageFlingDp * resources.displayMetrics.density
        val target = when {
            vx < -fling -> floor(pos + 0.0001f) + 1f
            vx > fling -> ceil(pos - 0.0001f) - 1f
            else -> Math.round(pos).toFloat()
        }.coerceIn((startPage - 1).toFloat(), (startPage + 1).toFloat()).coerceIn(minPos(), maxPos())
        animatePages(target, -vx)
    }

    fun animatePages(target: Float, velocityPx: Float = 0f) {
        val w = metrics.w.toFloat()
        pagerSpring = Motion.profile.pageSnap.spring().apply { start(pos * w, velocityPx, target * w) }
        pagerTarget = target
        startFrames(pager = true)
    }

    // ---- sheet (swipe-up drawer)

    private fun beginSheet() {
        drag = Drag.SHEET
        sheet0 = sheet
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun dragSheet(dy: Float) {
        val h = metrics.h.toFloat()
        val raw = sheet0 - dy / h
        sheet = when {
            raw < 0f -> Motion.rubberBand(raw * h, h) / h
            raw > 1f -> 1f + Motion.rubberBand((raw - 1f) * h, h) / h
            else -> raw
        }
        applyPositions()
    }

    private fun releaseSheet(vy: Float) {
        val fling = Motion.profile.pageFlingDp * resources.displayMetrics.density
        val target = when { vy < -fling -> 1f; vy > fling -> 0f; else -> if (sheet > 0.5f) 1f else 0f }
        animateSheet(target, -vy)
    }

    private fun animateSheet(target: Float, velocityPx: Float = 0f) {
        val h = metrics.h.toFloat()
        sheetSpring = Motion.profile.drawer.spring().apply { start(sheet * h, velocityPx, target * h) }
        sheetTarget = target
        startFrames(pager = false)
    }

    // ---- animation driver

    private var pagerSpring: Spring? = null
    private var pagerTarget = 0f
    private var pagerAnimating = false
    private var sheetSpring: Spring? = null
    private var sheetTarget = 0f
    private var sheetAnimating = false
    private var pagerStart = 0L
    private var sheetStart = 0L
    private var framePosted = false

    private fun startFrames(pager: Boolean) {
        if (pager) { pagerAnimating = true; pagerStart = System.nanoTime() } else { sheetAnimating = true; sheetStart = System.nanoTime() }
        if (!framePosted) { framePosted = true; Choreographer.getInstance().postFrameCallback(frame) }
    }

    private fun stopAnimations() { pagerAnimating = false; sheetAnimating = false }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            framePosted = false
            val metrics = m ?: return
            var settledNow = false
            if (pagerAnimating) {
                val s = pagerSpring!!
                val t = maxOf(0L, now - pagerStart) / 1e9
                pos = s.value(t) / metrics.w
                if (s.settled(t)) { pos = pagerTarget; pagerAnimating = false; settledNow = true }
            }
            if (sheetAnimating) {
                val s = sheetSpring!!
                val t = maxOf(0L, now - sheetStart) / 1e9
                sheet = s.value(t) / metrics.h
                if (s.settled(t)) { sheet = sheetTarget; sheetAnimating = false; settledNow = true }
            }
            applyPositions()
            if (pagerAnimating || sheetAnimating) { framePosted = true; Choreographer.getInstance().postFrameCallback(this) }
            else if (settledNow) onSettled()
        }
    }

    private fun onSettled() {
        if (drawerProgress() == 0f && drawerWasOpen) {
            drawerWasOpen = false
            drawer?.onClosed()
        }
        publishIcons()
        listener.onHomeSettled()
    }

    // ================================================================== state for the launcher and gesture nav

    val isIdle: Boolean get() = !pagerAnimating && !sheetAnimating && !depthAnimating && (drag == Drag.NONE || drag == Drag.IGNORED) && (drawer?.isIdle ?: true)

    // ---- depth: home receding behind an open app (iOS), run here once the real home is on screen

    private var depthSpring: Spring? = null
    private var depthStart = 0L
    private var depthTarget = 0f
    private var depthAnimating = false

    /** Follows gesture nav's depth spring exactly (same parameters and start time). */
    fun animateDepth(from: Float, to: Float, velocity: Float, response: Float, damping: Float, startNanos: Long) {
        depthSpring = Spring(response, damping).apply { start(from, velocity, to) }
        depthStart = startNanos
        depthTarget = to
        val wasAnimating = depthAnimating
        depthAnimating = true
        applyDepth(from)
        if (!wasAnimating) Choreographer.getInstance().postFrameCallback(depthFrame)
    }

    private val depthFrame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            val s = depthSpring ?: return
            if (!depthAnimating) return
            val t = maxOf(0L, now - depthStart) / 1e9
            if (s.settled(t, 0.001f)) {
                applyDepth(depthTarget)
                depthAnimating = false
                publishIcons()
                listener.onHomeSettled()
            } else {
                applyDepth(s.value(t))
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    private fun applyDepth(d: Float) {
        val mp = Motion.profile
        val wz = 1f + d * (mp.homeWallpaperZoom - 1f)
        val cz = 1f + d * (mp.homeContentZoom - 1f)
        wallpaperView.scaleX = wz; wallpaperView.scaleY = wz
        fg.scaleX = cz; fg.scaleY = cz
    }

    /** Home pressed while home is in front: back to the first page, drawer closed. */
    fun goHome() {
        if (m == null) return
        if (cfg.drawerPlacement == DrawerPlacement.SWIPE_UP && sheet > 0f) animateSheet(0f)
        if (pos != 0f) animatePages(0f)
        if (drawerProgress() == 0f) drawer?.onClosed()
    }

    /** Back pressed: leaves search or a folder, then closes the drawer. */
    fun onBack() {
        val d = drawer ?: return
        if (drawerProgress() < 0.5f) return
        if (d.onBack()) return
        when (cfg.drawerPlacement) {
            DrawerPlacement.SWIPE_UP -> animateSheet(0f)
            DrawerPlacement.PAGE_AFTER_LAST -> animatePages((pages.size - 1).toFloat())
            DrawerPlacement.PAGE_BEFORE_FIRST -> animatePages(0f)
        }
    }

    private var published: Map<String, IconView> = emptyMap()
    private val anchors = HashMap<String, IconView>()
    var hiddenPkg: String? = null
        private set

    /** Tells gesture nav where every icon a closing card could fly into is (at rest: without the depth zoom). */
    fun publishIcons() {
        // Positions at rest: the depth zoom is taken off while measuring (same frame, never drawn like this).
        val sx = fg.scaleX
        val sy = fg.scaleY
        fg.scaleX = 1f; fg.scaleY = 1f
        try { publishAtRest() } finally { fg.scaleX = sx; fg.scaleY = sy }
    }

    private fun publishAtRest() {
        val map = HashMap<String, RectF>()
        val views = HashMap<String, IconView>()
        val dp = drawerProgress()
        if (dp >= 0.999f) drawer?.visibleIcons(map)
        else if (dp <= 0.001f) {
            val page = pages.getOrNull(pos.roundToInt())
            for (v in (dock?.icons() ?: emptyList()) + (page?.icons() ?: emptyList())) {
                val e = v.entry ?: continue
                if (map.containsKey(e.pkg) && anchors[e.pkg] !== v) continue
                map[e.pkg] = v.iconOnScreen(RectF())
                views[e.pkg] = v
            }
        }
        published = views
        HomeBridge.setVisibleIcons(map)
    }

    /** Hides the icon a card flies into or out of (null = show all). Alpha only: a hidden icon stays tappable. */
    fun setHiddenPkg(pkg: String?) {
        if (pkg == hiddenPkg) return
        hiddenPkg?.let { published[it]?.alpha = 1f }
        hiddenPkg = pkg
        pkg?.let { published[it]?.alpha = 0f }
        drawer?.setHiddenPkg(pkg)
    }

    /** Runs [block] with [pkg]'s icon hidden (to record a picture without it), then restores what was hidden. */
    fun <T> withHidden(pkg: String, block: () -> T): T {
        val before = hiddenPkg
        setHiddenPkg(pkg)
        try { return block() } finally { setHiddenPkg(before) }
    }

    // ================================================================== DrawerHost

    override fun launch(e: AppEntry, iconOnScreen: RectF) {
        val metrics = m ?: return
        listener.launch(e, iconOnScreen, Icons.cached(e, metrics.iconSize)?.let { BitmapDrawable(resources, it) })
    }

    override fun onDrawerSettled() {
        publishIcons()
        if (isIdle) listener.onHomeSettled()
    }

    override fun onIconsMoved() = publishIcons()

    companion object {
        /** Sparkle grid and front wobble scale of the wallpaper reveal, shared by wallpaper and glass. */
        const val REVEAL_CELL_DP = 7f
    }
}
