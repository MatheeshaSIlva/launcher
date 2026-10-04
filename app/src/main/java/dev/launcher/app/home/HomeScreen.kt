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
class HomeScreen(ctx: Context, private val listener: Listener) : FrameLayout(ctx), DrawerHost, SpotlightView.Host {
    interface Listener {
        fun launch(e: AppEntry, iconOnScreen: RectF, icon: Drawable?)
        /** Everything came to rest: a good moment to record the picture of home. */
        fun onHomeSettled()
        /** The layout was edited (moved, removed, added): save it. */
        fun layoutChanged()
    }

    var cfg = HomeConfig()
        private set
    private var m: HomeMetrics? = null
    override val metrics: HomeMetrics get() = m!!
    override val placement: DrawerPlacement get() = cfg.drawerPlacement

    val wallpaperView = WallpaperView(ctx, ctx.resources.displayMetrics.density * REVEAL_CELL_DP)
    /** Everything above the wallpaper; recorded as the content layer of the picture of home. */
    val fg = FrameLayout(ctx)
    /** Wallpaper and [fg]: what blurs as one behind a menu or the widget gallery. */
    private val scene = FrameLayout(ctx)
    /** Above the scene, never blurred or recorded: the long-press menu, the widget gallery, a dragged item's copy. */
    private val overlay = FrameLayout(ctx)
    private var picker: WidgetPicker? = null
    /** Android widgets on home (hosting, binding); set by the activity. */
    var widgets: HomeWidgets? = null
    // An app long-pressed in the App Library or Spotlight: moving on drags it out onto home.
    private var pendingExternal: Pair<AppEntry, RectF>? = null
    private var pendingFromSpotlight = false
    private var externalTouch = false
    private val backdrop = BackdropView(ctx)
    private val pagesLayer = FrameLayout(ctx)
    private var dockShadow: DockShadow? = null
    private var dock: DockView? = null
    private var indicator: PageIndicator? = null
    private var drawer: AppDrawer? = null
    private var spotlight: SpotlightView? = null
    private var imeInset = 0
    private var editMode: EditMode? = null
    private var editBar: View? = null
    private var menu: ContextMenuView? = null
    private var editTouch = false
    private var enteredByPress = false     // this touch's long press started edit mode: its lift does not leave it again
    private var pendingDragView: View? = null
    // A long press on empty space enters edit mode (an item's own long press cancels this).
    private val emptyLongPress = Runnable {
        if ((drag == Drag.NONE || drag == Drag.IGNORED) && editMode?.active == false && drawerProgress() == 0f && spotlight?.isOpen != true) {
            enteredByPress = true
            editMode?.enter()
        }
    }
    private val pages = ArrayList<PageView>()
    val clocks = ArrayList<ClockWidgetView>()

    private var layout: HomeLayout? = null
    private var wallpaper: Wallpaper? = null
    private var topInset = 0
    private var bottomInset = 0
    private var deviceRadius = 0f

    // Strip position (pages, and the drawer when it is a page) and sheet progress (swipe-up drawer).
    private var pos = 0f
    private var sheet = 0f
    private var drawerWasOpen = false

    /** Notification counts changed: every icon on home shows its app's. */
    private val onBadges: () -> Unit = {
        for (v in pages.flatMap { it.icons() } + (dock?.icons() ?: emptyList())) v.badge = v.entry?.let { dev.launcher.app.Badges.count(it.pkg) } ?: 0
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        dev.launcher.app.Badges.addListener(onBadges)
        onBadges()
    }

    override fun onDetachedFromWindow() {
        dev.launcher.app.Badges.removeListener(onBadges)
        super.onDetachedFromWindow()
    }

    init {
        scene.addView(wallpaperView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        scene.addView(fg, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        scene.clipChildren = false
        addView(scene, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        fg.clipChildren = false
        // Search fields ride up with the keyboard frame by frame (not only once it has finished opening).
        if (Build.VERSION.SDK_INT >= 30) {
            setWindowInsetsAnimationCallback(object : android.view.WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(insets: WindowInsets, running: MutableList<android.view.WindowInsetsAnimation>): WindowInsets {
                    applyIme(insets.getInsets(WindowInsets.Type.ime()).bottom)
                    return insets
                }
            })
        }
    }

    private fun applyIme(ime: Int) {
        imeInset = ime
        drawer?.setImeInset(maxOf(0, ime - bottomInset))
        spotlight?.setInsets(bottomInset, ime)
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
        spotlight?.setWallpaper(w)
        applyGlassWallpaper()
    }

    /** Glass on the pages (widgets): redrawn while the pages move, so it keeps refracting what is behind it. */
    private val pageGlass = ArrayList<GlassView>()

    /** Glass surfaces (dock, Search pill, widgets), for the wallpaper reveal to drive frame by frame. */
    fun glassViews(): List<GlassView> = listOfNotNull(dock?.glass, indicator?.glass) + pageGlass + ((editBar as? EditMode.Bar)?.glassViews() ?: emptyList())

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
        applyIme(ime)
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
        // Spotlight covers everything above the wallpaper while open.
        val sp = SpotlightView(context, metrics, this).also { spotlight = it }
        fg.addView(sp, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        sp.setWallpaper(wallpaper)
        sp.setInsets(bottomInset, imeInset)
        // Edit mode: its bar ("Edit", "Done") at the top of the scene; above the scene the long-press menu, the widget gallery
        // and the lifted copy of a dragged item.
        val em = editMode ?: EditMode(this, editHost).also { editMode = it }
        if (em.active) em.exit()
        val bar = em.Bar(context).also { editBar = it; it.visibility = View.GONE }
        fg.addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, (metrics.gridTop - topInset - metrics.pt(6f)).roundToInt().coerceAtLeast(1)).apply { topMargin = topInset })
        overlay.removeAllViews()
        menuK = 0f; pickerK = 0f; applySceneBlur()
        val mv = ContextMenuView(context, metrics, { k -> menuK = k; applySceneBlur() }, { c -> scene.draw(c) }).also { menu = it }
        overlay.addView(mv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val pk = WidgetPicker(context, metrics, pickerHost).also { picker = it }
        overlay.addView(pk, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        (em.ghostView.parent as? android.view.ViewGroup)?.removeView(em.ghostView)
        overlay.addView(em.ghostView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
        pageGlass.clear()
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
            ind.visibility = View.VISIBLE   // the Search pill shows even with a single page (as on iOS)
            ind.setOnClickListener { openLibrarySearch() }
        }
        pos = pos.coerceIn(minPos(), maxPos())
        applyGlassWallpaper()   // widgets' glass was just created
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
        is HomeItem.Widget -> when (item.kind) {
            "clock" -> ClockWidgetView(context, metrics, item.spanX, item.spanY, item.style).also { w ->
                clocks += w
                pageGlass += w.glass
                w.glass.setWallpaper(wallpaper, metrics.w, metrics.h, resources.displayMetrics.density * REVEAL_CELL_DP)
                w.setOnLongClickListener { onWidgetLongPress(w); true }
                editMode?.adopt(w)
            }
            HomeItem.Widget.APP -> {
                val pkg = item.provider?.let { android.content.ComponentName.unflattenFromString(it)?.packageName }
                val label = pkg?.let { p -> Apps.forPkg(p)?.label } ?: ""
                AppWidgetFrame(context, metrics, item.spanX, item.spanY, widgets?.createView(item), label).also { f ->
                    f.setOnLongClickListener { onWidgetLongPress(f); true }
                    editMode?.adopt(f)
                }
            }
            else -> null
        }
        is HomeItem.Folder -> null   // folders: a later build
    }

    private fun appIcon(e: AppEntry, metrics: HomeMetrics, label: Boolean) = IconView(context, metrics, label).apply {
        bind(e)
        badge = dev.launcher.app.Badges.count(e.pkg)
        setOnLongClickListener { v -> onIconLongPress(v as IconView, e); true }
        editMode?.adopt(this)
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
        // Not in the middle of a drag (the layout is rebuilt): again shortly, saved through layoutChanged.
        if (editMode?.dragging == true || externalTouch) {
            postDelayed({ if (appsChanged()) listener.layoutChanged() }, 500)
            return false
        }
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
        // Pulled past its end (the rubber band) the drawer is still fully open: its background keeps its full blur.
        if (li == pages.size && pos >= li) return 1f
        if (li == -1 && pos <= -1f) return 1f
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
        for (g in pageGlass) g.invalidate()
        if (pendingSearch && dp > 0.5f) { pendingSearch = false; drawer?.openSearch() }
        indicator?.setPosition(pos.coerceIn(0f, (pages.size - 1).coerceAtLeast(0).toFloat()))
        drawer?.setOpenProgress(dp)
        if (dp > 0f) drawerWasOpen = true
    }

    // ================================================================== touch

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private enum class Drag { NONE, PAGES, SHEET, SEARCH, IGNORED }
    private var drag = Drag.NONE
    private var downX = 0f
    private var downY = 0f
    private var pos0 = 0f
    private var sheet0 = 0f
    private var startPage = 0
    private var vt: VelocityTracker? = null

    private fun canPage(): Boolean {
        if (spotlight?.isOpen == true) return false
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
        // Pull down on a home page (below the status bar: the top edge is the system's shade): Spotlight follows the finger.
        if (abs(dy) > abs(dx) && dy > 0 && drawerProgress() == 0f && sheet == 0f && downY > metrics.gridTop - metrics.pt(40f) &&
            spotlight?.isOpen != true && editMode?.active != true) {
            downX = e.x; downY = e.y
            drag = Drag.SEARCH
            parent?.requestDisallowInterceptTouchEvent(true)
            spotlight?.beginDrag()
            return true
        }
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
                pendingDragView = null
                pendingExternal = null
                externalTouch = false
                enteredByPress = false
                removeCallbacks(emptyLongPress)
                // The widget gallery handles its own touches.
                if (picker?.isOpen == true) { drag = Drag.IGNORED; return false }
                // While Spotlight or the long-press menu is open, it handles every touch itself.
                if (spotlight?.isOpen == true || menu?.isShowing == true) { drag = Drag.IGNORED; return false }
                // Editing: a touch on an item belongs to edit mode (drag it, or its remove badge).
                val em = editMode
                if (em != null && em.active && em.onDown(e.x, e.y)) { editTouch = true; return true }
                // A long press on empty space starts edit mode (on an item, the item's own long press opens its menu).
                if (em != null && !em.active && drawerProgress() == 0f && sheet == 0f && em.itemAt(e.x, e.y) == null)
                    postDelayed(emptyLongPress, ViewConfiguration.getLongPressTimeout().toLong())
                // A touch on a moving strip or sheet grabs it where it is (no tap goes through).
                if (pagerAnimating) { pagerAnimating = false; beginPages(); return true }
                if (sheetAnimating) { sheetAnimating = false; beginSheet(); return true }
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) removeCallbacks(emptyLongPress)
                // A long-pressed item that starts moving: the menu goes, edit mode starts, the item is dragged.
                val v = pendingDragView
                if (v != null && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                    pendingDragView = null
                    menu?.handOff()
                    editMode?.let { it.enter(haptic = false); it.beginDragFrom(v, e.x, e.y, fromMenu = true) }
                    editTouch = true
                    return true
                }
                // An app long-pressed in the App Library or Spotlight that starts moving: dragged out onto home.
                val ext = pendingExternal
                if (ext != null && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                    pendingExternal = null
                    beginExternalDrag(ext.first, ext.second, e.x, e.y)
                    externalTouch = true
                    return true
                }
                if (drag == Drag.NONE) return decide(e)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { removeCallbacks(emptyLongPress); pendingDragView = null; pendingExternal = null }
        }
        return drag == Drag.PAGES || drag == Drag.SHEET
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (externalTouch) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> editMode?.externalMove(e.x, e.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { editMode?.externalUp(); externalTouch = false }
            }
            return true
        }
        if (editTouch) {
            editMode?.onTouch(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) editTouch = false
            return true
        }
        if (e.actionMasked == MotionEvent.ACTION_MOVE && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) removeCallbacks(emptyLongPress)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) removeCallbacks(emptyLongPress)
        // Editing: a tap on empty space leaves edit mode (as on iOS); not the lift of the long press that started it.
        if (e.actionMasked == MotionEvent.ACTION_UP && enteredByPress) { enteredByPress = false; drag = Drag.NONE; return true }
        if (e.actionMasked == MotionEvent.ACTION_UP && editMode?.active == true && (drag == Drag.NONE || drag == Drag.IGNORED) &&
            abs(e.x - downX) < slop && abs(e.y - downY) < slop) {
            editMode?.exit()
            drag = Drag.NONE
            return true
        }
        if (e.actionMasked != MotionEvent.ACTION_DOWN) track(e)
        else if (drag == Drag.NONE) { track(e); downX = e.x; downY = e.y }
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (drag == Drag.NONE) decide(e)
                when (drag) {
                    Drag.PAGES -> dragPages(e.x - downX)
                    Drag.SHEET -> dragSheet(e.y - downY)
                    Drag.SEARCH -> spotlight?.let { it.dragTo((e.y - downY) / it.pullDistance()) }
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
                    Drag.SEARCH -> spotlight?.release(vy)
                    else -> {}
                }
                drag = Drag.NONE
            }
        }
        return true
    }

    // ---- pages

    private fun beginPages() {
        indicator?.setMoving(true)
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
        if (target != pos) indicator?.setMoving(true)
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
        indicator?.setMoving(false)
        if (drawerProgress() == 0f && drawerWasOpen) {
            drawerWasOpen = false
            drawer?.onClosed()
        }
        publishIcons()
        listener.onHomeSettled()
    }

    // ================================================================== state for the launcher and gesture nav

    val isIdle: Boolean get() = !pagerAnimating && !sheetAnimating && !depthAnimating && (drag == Drag.NONE || drag == Drag.IGNORED) &&
        (drawer?.isIdle ?: true) && (spotlight?.isIdle ?: true) && editMode?.active != true && menu?.isShowing != true &&
        picker?.isOpen != true && !externalTouch

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
        // While the zoom runs, home is drawn from a GPU layer, so each frame only scales a cached image: redrawing all of home
        // at every step of the zoom cost ~2.6 ms of GPU per frame during every close (framestats on the S24), on the same
        // GPU as the closing card, though the picture of home covered it. Back to normal drawing when the zoom settles.
        if (fg.layerType != View.LAYER_TYPE_HARDWARE) fg.setLayerType(View.LAYER_TYPE_HARDWARE, null)
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
                fg.setLayerType(View.LAYER_TYPE_NONE, null)
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
        // The same blur as the picture of home behind the cards (GestureNav), so taking over from it changes nothing; skipped
        // while that picture covers home (the work would be invisible).
        if (Build.VERSION.SDK_INT >= 31) {
            val r = if (HomeBridge.homeCovered) 0f else d.coerceIn(0f, 1f) * mp.homeDepthBlur * resources.displayMetrics.density
            setRenderEffect(if (r < 0.5f) null else android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP))
        }
    }

    private var pendingSearch = false

    /** The Search pill: opens Spotlight with the keyboard up (as on iOS). */
    fun openLibrarySearch() {
        if (m == null || editMode?.active == true) return
        spotlight?.open()
    }

    /** Home pressed while home is in front: back to the first page, drawer closed. */
    fun goHome() {
        if (m == null) return
        menu?.dismiss()
        picker?.close()
        editMode?.exit()
        spotlight?.takeIf { it.isOpen }?.close()
        drawer?.hideKeyboard()   // App Library search ends once the library has slid away (onClosed)
        if (cfg.drawerPlacement == DrawerPlacement.SWIPE_UP && sheet > 0f) animateSheet(0f)
        if (pos != 0f) animatePages(0f)
        if (drawerProgress() == 0f) drawer?.onClosed()
    }

    /** Back pressed: leaves search or a folder, then closes the drawer. */
    fun onBack() {
        if (menu?.isShowing == true) { menu?.dismiss(); return }
        if (picker?.onBack() == true) return
        if (editMode?.active == true) { editMode?.exit(); return }
        if (spotlight?.onBack() == true) return
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

    /**
     * Hides the icon a card flies into or out of (null = show all): only the icon's image, its label stays (as on iOS), and
     * the view stays tappable (reopening an app while its card is still closing).
     */
    fun setHiddenPkg(pkg: String?) {
        if (pkg == hiddenPkg) return
        hiddenPkg?.let { published[it]?.iconHidden = false }
        hiddenPkg = pkg
        pkg?.let { published[it]?.iconHidden = true }
        drawer?.setHiddenPkg(pkg)
    }

    /** Runs [block] with [pkg]'s icon hidden (null: every icon shown), to record a picture, then restores what was hidden. */
    fun <T> withHidden(pkg: String?, block: () -> T): T {
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

    override fun onAppLongPress(e: AppEntry, iconOnScreen: RectF) = onLibraryLongPress(e, toHome(iconOnScreen), fromSpotlight = false)

    // ================================================================== edit mode, menus, widgets

    private val afterEdit = Runnable {
        publishIcons()
        if (isIdle) listener.onHomeSettled()
    }

    /**
     * Home went out of sight (an app or another screen came over it): menus and the gallery close, edit mode ends, and
     * Spotlight and App Library search end, at once and unseen (as on iOS, an app opened from them comes back to home, or
     * to the library, without the search and its keyboard). Returns true if a search ended: home looks different now, so
     * the pictures of it that a close shows behind its card must be recorded again.
     */
    fun onHidden(): Boolean {
        menu?.dismissNow(runClosed = true)
        picker?.closeNow()
        if (editMode?.dragging == true) editMode?.externalUp()
        externalTouch = false
        pendingExternal = null
        editMode?.exit()
        var searchEnded = false
        spotlight?.takeIf { it.visibility == View.VISIBLE }?.let { it.closeNow(); searchEnded = true }
        if (drawer?.endSearchNow() == true) searchEnded = true
        return searchEnded
    }

    /**
     * A swipe up on the gesture bar while home is in front. As on iOS it first closes what is on top (a menu, the widget
     * gallery, edit mode, Spotlight); with nothing on top it does what the Home button does: back to the first page, the
     * App Library and its search closed.
     */
    fun onHomeSwipeUp() {
        val onTop = menu?.isShowing == true || picker?.isOpen == true || editMode?.active == true || spotlight?.isOpen == true
        if (!onTop) { goHome(); return }
        menu?.dismiss()
        picker?.close()
        editMode?.exit()
        spotlight?.takeIf { it.isOpen }?.close()
    }

    // Home's blur behind a menu or the widget gallery: the whole scene (wallpaper included) as one, so nothing behind is
    // sharp and no blurred element shows its own edges.
    private var menuK = 0f
    private var pickerK = 0f

    private fun applySceneBlur() {
        if (Build.VERSION.SDK_INT < 31) return
        editMode?.jigglePaused = maxOf(menuK, pickerK) > 0.01f
        val r = maxOf(menuK, pickerK) * Motion.profile.menuBlur * (m?.u ?: 0f)
        scene.setRenderEffect(if (r < 0.5f) null else android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP))
    }

    /** A picture of [v] as it looks (an icon without its label), and its frame in home's coordinates. */
    private fun liftedCopy(v: View): Pair<android.graphics.Picture, RectF> {
        val pic = android.graphics.Picture()
        val c = pic.beginRecording(maxOf(1, v.width), maxOf(1, v.height))
        if (v is IconView) { v.clearPress(); v.labelHidden = true }
        v.draw(c)
        if (v is IconView) v.labelHidden = false
        pic.endRecording()
        return pic to frameInHome(v)
    }

    private fun frameInHome(v: View): RectF {
        val loc = IntArray(2)
        val me = IntArray(2)
        v.getLocationOnScreen(loc)
        getLocationOnScreen(me)
        val x = (loc[0] - me[0]).toFloat()
        val y = (loc[1] - me[1]).toFloat()
        return RectF(x, y, x + v.width, y + v.height)
    }

    /** The app's own shortcuts (we are the home app, so we may list and start them), at most four. */
    private fun shortcutItems(e: AppEntry, frame: RectF): List<ContextMenuView.Item> {
        val la = context.getSystemService(android.content.pm.LauncherApps::class.java)
        val items = ArrayList<ContextMenuView.Item>()
        try {
            if (la.hasShortcutHostPermission()) {
                val q = android.content.pm.LauncherApps.ShortcutQuery().setPackage(e.pkg).setQueryFlags(
                    android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                        android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
                la.getShortcuts(q, e.user)?.sortedBy { it.rank }?.take(4)?.forEach { sc ->
                    val icon = try { la.getShortcutIconDrawable(sc, resources.displayMetrics.densityDpi) } catch (_: Throwable) { null }
                    items += ContextMenuView.Item((sc.shortLabel ?: sc.longLabel ?: sc.id).toString(), icon = icon) {
                        try { la.startShortcut(sc, android.graphics.Rect().also { frame.roundOut(it) }, null) } catch (t: Throwable) { AppLog.log("[home] shortcut failed: ${t.message}") }
                    }
                }
            }
        } catch (t: Throwable) { AppLog.log("[home] shortcuts unavailable: ${t.message}") }
        return items
    }

    /** "Delete App" (iOS): Android's own uninstall confirmation; system apps cannot be deleted, so they do not get it. */
    private fun deleteItem(e: AppEntry): ContextMenuView.Item? {
        val system = try {
            (context.packageManager.getApplicationInfo(e.pkg, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (_: Throwable) { true }
        if (system || e.pkg == context.packageName) return null
        return ContextMenuView.Item("Delete App", glyph = ContextMenuView.Glyph.TRASH, destructive = true) {
            try {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_DELETE, android.net.Uri.parse("package:${e.pkg}"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (t: Throwable) { AppLog.log("[home] cannot delete ${e.pkg}: ${t.message}") }
        }
    }

    private fun appInfoItem(e: AppEntry, frame: RectF) = ContextMenuView.Item("App Info", glyph = ContextMenuView.Glyph.INFO) {
        val la = context.getSystemService(android.content.pm.LauncherApps::class.java)
        try { la.startAppDetailsActivity(e.component, e.user, android.graphics.Rect().also { frame.roundOut(it) }, null) } catch (t: Throwable) { AppLog.log("[home] app info failed: ${t.message}") }
    }

    private fun onIconLongPress(v: IconView, e: AppEntry) {
        removeCallbacks(emptyLongPress)
        if (editMode?.active == true) return
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val (pic, frame) = liftedCopy(v)
        val items = ArrayList(shortcutItems(e, frame))
        items += ContextMenuView.Item("Edit Home Screen", glyph = ContextMenuView.Glyph.GRID) { editMode?.enter() }
        items += ContextMenuView.Item("Remove from Home Screen", glyph = ContextMenuView.Glyph.MINUS, destructive = true) { editMode?.removeFromHome(v) }
        deleteItem(e)?.let { items += it }
        items += appInfoItem(e, frame)
        showMenu(v, pic, frame, items)
    }

    private fun showMenu(v: View, pic: android.graphics.Picture, frame: RectF, items: List<ContextMenuView.Item>) {
        val mv = menu ?: return
        mv.show(pic, frame, items)
        // The lifted copy draws the item above the blur; the real one (blurred, with its label) hides meanwhile.
        v.alpha = 0f
        mv.onClosed = { v.alpha = 1f }
        pendingDragView = v
    }

    private fun onWidgetLongPress(v: View) {
        removeCallbacks(emptyLongPress)
        if (editMode?.active == true) return
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val (pic, frame) = liftedCopy(v)
        val item = pages.firstNotNullOfOrNull { it.itemOf(v) } as? HomeItem.Widget
        val items = ArrayList<ContextMenuView.Item>()
        if (item != null) {
            // iOS: the sizes this widget comes in, then its own settings.
            val sizes = editHost.widgetSizes(item)
            val current = sizes.firstOrNull { it.spanX == item.spanX && it.spanY == item.spanY }
            if (sizes.size > 1) items += ContextMenuView.Item("Size", sizes = sizes, current = current, onSize = { s -> editMode?.resize(item, s) })
            if (item.kind == HomeItem.Widget.APP && widgets?.isConfigurable(item.id) == true)
                items += ContextMenuView.Item("Edit Widget", glyph = ContextMenuView.Glyph.SLIDERS) { widgets?.reconfigure(item.id) }
            if (item.kind == "clock") {
                val solid = item.style == "solid"
                items += ContextMenuView.Item(if (solid) "Glass Style" else "Solid Style", glyph = ContextMenuView.Glyph.STYLE) {
                    editMode?.restyle(item, if (solid) null else "solid")
                }
            }
        }
        items += ContextMenuView.Item("Edit Home Screen", glyph = ContextMenuView.Glyph.GRID) { editMode?.enter() }
        items += ContextMenuView.Item("Remove Widget", glyph = ContextMenuView.Glyph.MINUS, destructive = true) { editMode?.removeFromHome(v) }
        showMenu(v, pic, frame, items)
    }

    private fun isOnHome(key: String): Boolean {
        val l = layout ?: return false
        return key in l.dock || l.pages.any { p -> p.any { it is HomeItem.App && it.key == key } }
    }

    /**
     * Long press on an app in the App Library or Spotlight ([frame]: its icon, home coordinates): its menu, with "Add to
     * Home Screen" while it is not on home; moving on drags it out onto a home page (iOS).
     */
    private fun onLibraryLongPress(e: AppEntry, frame: RectF, fromSpotlight: Boolean) {
        val metrics = m ?: return
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val pic = android.graphics.Picture()
        val c = pic.beginRecording(maxOf(1, frame.width().toInt()), maxOf(1, frame.height().toInt()))
        Icons.cached(e, metrics.iconSize)?.let { b ->
            c.drawBitmap(b, null, RectF(0f, 0f, frame.width(), frame.height()), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
        pic.endRecording()
        val items = ArrayList(shortcutItems(e, frame))
        val onHome = isOnHome(e.key)
        if (!onHome) items += ContextMenuView.Item("Add to Home Screen", glyph = ContextMenuView.Glyph.PLUS) { addAppToHome(e) }
        deleteItem(e)?.let { items += it }
        items += appInfoItem(e, frame)
        val mv = menu ?: return
        if (!fromSpotlight) drawer?.setHiddenPkg(e.pkg)
        mv.show(pic, frame, items)
        mv.onClosed = { if (!fromSpotlight) drawer?.setHiddenPkg(hiddenPkg) }
        // An app already on home is not dragged out again (iOS keeps one icon per app).
        pendingExternal = if (onHome) null else e to RectF(frame)
        pendingFromSpotlight = fromSpotlight
    }

    /** "Add to Home Screen": at the end of the last page (a new page if it is full). */
    private fun addAppToHome(e: AppEntry) {
        val l = layout ?: return
        val metrics = m ?: return
        if (isOnHome(e.key)) return
        val item = HomeItem.App(e.key)
        if (l.pages.isEmpty() || Grid.firstFree(l.pages.last(), 1, 1, metrics.cfg.columns, metrics.cfg.rows) == null) editHost.appendPage()
        val pi = l.pages.size - 1
        Grid.firstFree(l.pages[pi], 1, 1, metrics.cfg.columns, metrics.cfg.rows)?.let { item.col = it[0]; item.row = it[1] }
        l.pages[pi].add(item)
        pages.getOrNull(pi)?.setItems(l.pages[pi], animate = false)
        editHost.layoutChanged()
        AppLog.log("[home] added ${e.pkg} to page ${pi + 1}")
    }

    /** The drag out of the App Library or Spotlight begins: they get out of the way, edit mode takes the app. */
    private fun beginExternalDrag(e: AppEntry, frame: RectF, x: Float, y: Float) {
        val metrics = m ?: return
        val em = editMode ?: return
        menu?.handOff()
        if (pendingFromSpotlight) spotlight?.closeNow() else closeDrawer()
        em.enter(haptic = false)
        // A home icon for the app, laid out off screen at a cell's size: its copy is what the finger carries.
        val icon = appIcon(e, metrics, label = cfg.showLabels)
        icon.measure(MeasureSpec.makeMeasureSpec(metrics.columnPitch.roundToInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(metrics.cellHeight.roundToInt(), MeasureSpec.EXACTLY))
        icon.layout(0, 0, icon.measuredWidth, icon.measuredHeight)
        em.beginExternalDrag(e.key, icon, frame, x, y)
        drawer?.setHiddenPkg(hiddenPkg)
    }

    private fun closeDrawer() {
        if (drawerProgress() == 0f) return
        when (cfg.drawerPlacement) {
            DrawerPlacement.SWIPE_UP -> animateSheet(0f)
            DrawerPlacement.PAGE_AFTER_LAST -> animatePages((pages.size - 1).coerceAtLeast(0).toFloat())
            DrawerPlacement.PAGE_BEFORE_FIRST -> animatePages(0f)
        }
    }

    private fun showEditMenu(button: RectF) {
        menu?.show((editBar as? EditMode.Bar)?.editButtonPicture(), button, listOf(
            ContextMenuView.Item("Add Widget", glyph = ContextMenuView.Glyph.PLUS) { openWidgetPicker() },
        ))
    }

    fun openWidgetPicker() {
        menu?.dismiss()
        picker?.open()
    }

    private val editHost = object : EditMode.Host {
        override val metrics: HomeMetrics get() = this@HomeScreen.metrics
        override val layout: HomeLayout? get() = this@HomeScreen.layout
        override val pageViews: List<PageView> get() = pages
        override val dockView: DockView? get() = dock
        override fun currentPage(): Int = pos.roundToInt().coerceIn(0, maxOf(0, pages.size - 1))
        override fun turnToPage(i: Int) = animatePages(i.toFloat())
        override fun appendPage() {
            val l = layout ?: return
            val metrics = m ?: return
            l.pages += mutableListOf<HomeItem>()
            val p = PageView(context, metrics) { item -> viewFor(item, metrics) }
            p.bind(emptyList())
            pages += p
            pagesLayer.addView(p, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            indicator?.let { ind ->
                ind.setPages(pages.size)
                ind.layoutParams = (ind.layoutParams as LayoutParams).apply {
                    width = ind.widthFor(pages.size)
                    leftMargin = ((metrics.w - width) / 2f).roundToInt()
                }
            }
            applyPositions()
        }
        override fun dockIcon(key: String): IconView? = m?.let { metrics -> Apps[key]?.let { appIcon(it, metrics, label = false) } }
        override fun layoutChanged() {
            listener.layoutChanged()
            removeCallbacks(afterEdit)
            postDelayed(afterEdit, 450)   // after the reflow glides
        }
        override fun itemRemoved(item: HomeItem) {
            if (item is HomeItem.Widget && item.kind == HomeItem.Widget.APP) widgets?.delete(item.id)
        }
        override fun editingChanged(active: Boolean) {
            editBar?.visibility = if (active) View.VISIBLE else View.GONE
            indicator?.editing = active
            if (!active) {
                // Pages emptied while editing are gone from the layout: match the views to it.
                if (pages.size != (layout?.pages?.size ?: pages.size)) bindLayout()
                publishIcons()
                post { if (isIdle) listener.onHomeSettled() }
            }
        }
        override fun showEditMenu(button: RectF) = this@HomeScreen.showEditMenu(button)
        override fun widgetSizes(widget: HomeItem.Widget): List<WidgetSize> {
            val metrics = m ?: return emptyList()
            return when (widget.kind) {
                "clock" -> listOf(WidgetSize.SMALL, WidgetSize.MEDIUM, WidgetSize.LARGE).filter { it.fits(metrics.cfg) }
                HomeItem.Widget.APP -> widgets?.info(widget.id)?.let { widgets?.sizesFor(it, metrics) } ?: emptyList()
                else -> emptyList()
            }
        }
    }

    // ---- the widget gallery

    private val pickerHost = object : WidgetPicker.Host {
        override fun widgetApps(): List<WidgetApp> = widgets?.apps() ?: emptyList()
        override fun widgetSizes(info: android.appwidget.AppWidgetProviderInfo): List<WidgetSize> = m?.let { widgets?.sizesFor(info, it) } ?: emptyList()
        override fun widgetLabel(info: android.appwidget.AppWidgetProviderInfo): String = widgets?.label(info) ?: "Widget"
        override fun widgetDescription(info: android.appwidget.AppWidgetProviderInfo): String? = widgets?.description(info)
        override fun widgetPreviewImage(info: android.appwidget.AppWidgetProviderInfo): android.graphics.drawable.Drawable? = widgets?.previewImage(info)
        override fun widgetPreviewView(info: android.appwidget.AppWidgetProviderInfo, parent: android.view.ViewGroup): View? = widgets?.previewView(info, parent)
        override fun addClockWidget() {
            val metrics = m ?: return
            editMode?.addWidget(HomeItem.Widget("clock", metrics.cfg.columns.coerceAtMost(4), 2))
        }
        override fun addAppWidget(info: android.appwidget.AppWidgetProviderInfo, size: WidgetSize) {
            val w = widgets ?: return
            w.add(info, size) { item -> if (item != null) editMode?.addWidget(item) }
        }
        override fun pickerProgress(k: Float) { pickerK = k; applySceneBlur() }
        override fun drawBehindSheet(c: android.graphics.Canvas) = scene.draw(c)
    }

    // ================================================================== SpotlightView.Host

    override fun launchFromSpotlight(e: AppEntry, iconOnScreen: RectF) = launch(e, iconOnScreen)

    override fun spotlightSettled() {
        publishIcons()
        if (isIdle) listener.onHomeSettled()
    }

    override fun onSpotlightLongPress(e: AppEntry, iconOnScreen: RectF) = onLibraryLongPress(e, toHome(iconOnScreen), fromSpotlight = true)

    /** A rectangle on screen in home's coordinates. */
    private fun toHome(onScreen: RectF): RectF {
        val me = IntArray(2)
        getLocationOnScreen(me)
        return RectF(onScreen).apply { offset(-me[0].toFloat(), -me[1].toFloat()) }
    }

    companion object {
        /** Sparkle grid and front wobble scale of the wallpaper reveal, shared by wallpaper and glass. */
        const val REVEAL_CELL_DP = 7f
    }
}
