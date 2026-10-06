package dev.launcher.app.drawer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import dev.launcher.app.apps.AppCategory
import dev.launcher.app.apps.AppEntry
import dev.launcher.app.apps.Apps
import dev.launcher.app.apps.LaunchStats
import dev.launcher.app.motion.Motion
import kotlin.math.roundToInt

/** One App Library tile: a title and its apps (best first). Category tiles with more than four apps open as a folder. */
internal class Tile(val title: String, val apps: List<AppEntry>, val expandable: Boolean)

/** One drawn copy of an app's icon: where it is and which part of the library shows it ("tile:Social", "list", ...). */
internal class IconSpot(val pkg: String, val rect: RectF, val source: String)

/**
 * The iOS App Library: a search field on top; category tiles in two columns (Suggestions and Recently Added first, then
 * categories by iOS's order). A tile shows up to four large icons, which open their app; with more apps, the fourth slot
 * is a cluster of small icons that opens the whole category as a folder. Tapping the search field (or pulling the tiles
 * down) switches to the alphabetical list, which filters as you type.
 */
class AppLibraryView(ctx: Context, val host: DrawerHost) : FrameLayout(ctx), AppDrawer {
    val m get() = host.metrics
    internal val iconPainter = IconPainter(m.iconSize) { invalidateAll() }

    internal val tilesPane = TilesPane(ctx, this)
    internal val listPane = SearchList(ctx, m, iconPainter, { e, r -> launch(e, r, "list") }, { e, r -> isHidden(e, r) }, { haptic() }, { settled() }).apply {
        onLongPress = { e, r -> longPress(e, r, "list") }
        drawGlass = { c, rect, radius -> drawGlass(c, tileGlass, rect, radius, this) }
    }
    internal val searchBar = SearchBar(ctx, this)
    internal val folder = FolderOverlay(ctx, this)
    private val cancel = TextView(ctx)

    internal var tiles: List<Tile> = emptyList()
        private set
    private var listMode = false
    private var crossfading = false

    // The one icon hidden while a card flies into or out of it (by package and by where it is drawn).
    private var hiddenPkg: String? = null
    private val published = HashMap<String, RectF>()
    // Where the tapped copy of an icon lives (its tile, the list, a folder): an app shown twice (Suggestions and its
    // category) closes back into the copy it was opened from, even if tiles re-sorted meanwhile.
    private var anchor: Pair<String, String>? = null

    override val view: View get() = this

    init {
        clipChildren = false
        addView(tilesPane, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(listPane, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        listPane.visibility = View.GONE
        listPane.alpha = 0f
        addView(searchBar, LayoutParams((m.w - 2 * m.libMargin).roundToInt(), m.searchHeight.roundToInt()).apply {
            leftMargin = m.libMargin.roundToInt()
            topMargin = m.searchTop.roundToInt()
        })
        cancel.apply {
            text = "Cancel"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, m.pt(17f))
            typeface = dev.launcher.app.theme.Fonts.text(400)
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { leaveList() }
        }
        addView(cancel, LayoutParams(m.pt(76f).roundToInt(), m.searchHeight.roundToInt()).apply {
            gravity = Gravity.TOP or Gravity.END
            rightMargin = m.libMargin.roundToInt()
            topMargin = m.searchTop.roundToInt()
        })
        addView(folder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        folder.visibility = View.GONE
        rebuild()
    }

    // ------------------------------------------------------------------ data

    private fun rebuild() {
        val all = Apps.all
        val scores = LaunchStats.suggestions(all.size).withIndex().associate { (i, e) -> e.key to i }
        // Most used first; unused apps keep their alphabetical order (the sort is stable).
        fun byUse(list: List<AppEntry>) = list.sortedBy { scores[it.key] ?: Int.MAX_VALUE }
        val out = ArrayList<Tile>()
        val suggested = LaunchStats.suggestions(4).toMutableList()
        if (suggested.size < 4) {
            for (e in all) {
                if (suggested.size == 4) break
                if (!e.system && !e.internal && suggested.none { it.key == e.key }) suggested += e
            }
        }
        if (suggested.isNotEmpty()) out += Tile("Suggestions", suggested, false)
        val recent = all.filter { !it.system && !it.internal && it.installedAt > 0 }.sortedByDescending { it.installedAt }.take(4)
        if (recent.isNotEmpty()) out += Tile("Recently Added", recent, false)
        val groups = all.groupBy { it.category }
        for (cat in AppCategory.entries) {
            val apps = groups[cat] ?: continue
            out += Tile(cat.title, byUse(apps), apps.size > 4)
        }
        tiles = out
        tilesPane.dataChanged()
        listPane.setApps(all)
    }

    internal fun invalidateAll() {
        tilesPane.invalidate()
        listPane.invalidate()
        folder.invalidate()
    }

    // ------------------------------------------------------------------ modes

    /** To the alphabetical list (from the search field, or a pull-down on the tiles). */
    internal fun enterList(focusSearch: Boolean) {
        if (!listMode) {
            listMode = true
            crossfade(toList = true)
        }
        searchBar.setEditable(true)
        if (focusSearch) searchBar.focusAndShowKeyboard()
    }

    private fun leaveList() {
        if (!listMode) return
        listMode = false
        searchBar.clear()
        searchBar.setEditable(false)
        crossfade(toList = false)
    }

    private fun crossfade(toList: Boolean) {
        val d = Motion.profile.modeCrossfadeMs
        crossfading = true
        val showing = if (toList) listPane else tilesPane
        val hiding = if (toList) tilesPane else listPane
        showing.visibility = View.VISIBLE
        showing.animate().alpha(1f).setDuration(d).start()
        // The pane coming in takes touches from its first frame; the one fading out takes none (a tap on a tile right
        // after "Cancel" must open it, not land on the vanishing list).
        tilesPane.acceptsTouches = !toList
        listPane.acceptsTouches = toList
        hiding.animate().alpha(0f).setDuration(d).withEndAction {
            hiding.visibility = View.GONE
            crossfading = false
            host.onDrawerSettled()
        }.start()
        cancel.visibility = View.VISIBLE
        cancel.animate().alpha(if (toList) 1f else 0f).setDuration(d).withEndAction { if (!toList) cancel.visibility = View.GONE }.start()
        searchBar.animateCancelSpace(if (toList) 1f else 0f, d)
        if (toList) listPane.scroller.jumpTo(0f)
    }

    internal fun onQuery(q: String) {
        listPane.setQuery(q)
        host.onIconsMoved()   // results moved: a closing card must find their icons where they are now
    }

    internal fun launchFirstResult() {
        val e = listPane.firstResult() ?: return
        launch(e, listPane.iconRectOf(e) ?: RectF(), "list")
    }

    // ------------------------------------------------------------------ actions from the panes

    internal fun launch(e: AppEntry, rectInView: RectF, source: String) {
        anchor = e.pkg to source
        dev.launcher.app.AppLog.log("[library] open ${e.pkg} from $source at ${rectInView.centerX().toInt()},${rectInView.centerY().toInt()}")
        host.onIconsMoved()   // publish this copy before the launch hides it and records home without it
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        host.launch(e, RectF(rectInView).apply { offset(loc[0].toFloat(), loc[1].toFloat()) })
    }

    /** An app's icon was long-pressed at [rectInView] (this view's coordinates): home shows its menu. */
    internal fun longPress(e: AppEntry, rectInView: RectF, source: String) {
        anchor = e.pkg to source
        host.onIconsMoved()   // this copy is the one to hide while the menu shows
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        host.onAppLongPress(e, RectF(rectInView).apply { offset(loc[0].toFloat(), loc[1].toFloat()) })
    }

    internal fun openFolder(tile: Tile, tileRect: RectF, index: Int) {
        folder.open(tile, tileRect, index)
    }

    // ------------------------------------------------------------------ glass (the dock's liquid glass, over the library's
    // blurred wallpaper): one drawable per shape, origin set per draw so each surface refracts what is behind it

    internal var wallpaper: dev.launcher.app.Wallpaper? = null
        private set
    internal var tileGlass: dev.launcher.app.GlassDrawable? = null
        private set
    internal var searchGlass: dev.launcher.app.GlassDrawable? = null
        private set
    internal var panelGlass: dev.launcher.app.GlassDrawable? = null
        private set
    private val glassFallback = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF }
    private val offset = FloatArray(2)

    private fun makeGlass(w: dev.launcher.app.Wallpaper, radius: Float): dev.launcher.app.GlassDrawable? {
        if (Build.VERSION.SDK_INT < 33) return null
        return try {
            val d = resources.displayMetrics.density
            dev.launcher.app.GlassDrawable(w, m.w, m.h, radius, m.u, d * 7f, dev.launcher.app.GlassStyle.IOS_LIBRARY, dev.launcher.app.GlassDrawable.Source.BACKDROP)
        } catch (t: Throwable) {
            dev.launcher.app.AppLog.log("[library] glass shader failed, plain tiles instead: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** Draws a glass surface at [rect] (in [onView]'s coordinates); without our wallpaper copy, a plain translucent fill. */
    internal fun drawGlass(c: Canvas, g: dev.launcher.app.GlassDrawable?, rect: RectF, radius: Float, onView: View) {
        if (g == null) { c.drawRoundRect(rect, radius, radius, glassFallback); return }
        screenOffset(onView, offset)
        g.setRadius(radius)
        g.originX = offset[0] + rect.left
        g.originY = offset[1] + rect.top
        g.setBounds(rect.left.toInt(), rect.top.toInt(), kotlin.math.ceil(rect.right).toInt(), kotlin.math.ceil(rect.bottom).toInt())
        g.draw(c)
    }

    internal fun settled() = host.onDrawerSettled()

    internal fun haptic() { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }

    /** True if the icon of [e] drawn at [rectInView] is the one a card is flying into (skip drawing it). */
    internal fun isHidden(e: AppEntry, rectInView: RectF): Boolean {
        if (e.pkg != hiddenPkg) return false
        val p = published[e.pkg] ?: return true
        return kotlin.math.abs(p.centerX() - rectInView.centerX()) < 2f && kotlin.math.abs(p.centerY() - rectInView.centerY()) < 2f
    }

    // ------------------------------------------------------------------ AppDrawer

    private var lastProgress = -1f
    private var lastShift = Float.NaN

    override fun setOpenProgress(p: Float) {
        // While the library slides in or out the glass must keep refracting what is behind it where it is now (a translation
        // alone does not redraw the tiles). Also when the progress stays at 1 but the view still moves: the rubber band past
        // the last page slid the tiles while their glass kept showing the old spot.
        val shift = translationX + translationY
        if ((p != lastProgress || shift != lastShift) && wallpaper != null) {
            tilesPane.invalidate(); searchBar.invalidate()
            if (folder.visibility == View.VISIBLE) folder.invalidate()
            if (listPane.visibility == View.VISIBLE) listPane.invalidate()
        }
        lastProgress = p
        lastShift = shift
    }

    override fun onClosed() {
        anchor = null
        folder.closeNow()
        endSearchNow()
        tilesPane.scroller.jumpTo(0f)
        rebuild()   // suggestions follow what was just used
    }

    override fun endSearchNow(): Boolean {
        if (!listMode) return false
        listMode = false
        searchBar.clear()
        searchBar.setEditable(false)
        listPane.animate().cancel(); tilesPane.animate().cancel(); cancel.animate().cancel()
        listPane.alpha = 0f; listPane.visibility = View.GONE
        tilesPane.alpha = 1f; tilesPane.visibility = View.VISIBLE
        tilesPane.acceptsTouches = true; listPane.acceptsTouches = false
        cancel.alpha = 0f; cancel.visibility = View.GONE
        searchBar.animateCancelSpace(0f, 0)
        crossfading = false
        return true
    }

    override fun hideKeyboard() = searchBar.hideKeyboard()

    override fun closeTop(): Boolean {
        if (!folder.isOpen) return false
        folder.close()
        return true
    }

    override fun capturesGestures() = listMode || folder.isOpen

    override fun canScrollBack() = tilesPane.scroller.position > 0.5f

    override fun onBack(): Boolean {
        if (folder.isOpen) { folder.close(); return true }
        if (listMode) { leaveList(); return true }
        return false
    }

    override fun visibleIcons(out: MutableMap<String, RectF>) {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val all = ArrayList<IconSpot>()
        when {
            folder.isOpen -> folder.visibleIcons(all)
            listMode -> listPane.visibleIcons(all)
            else -> tilesPane.visibleIcons(all)
        }
        // One copy per app: the one in the part of the library it was opened from, if visible; else the first.
        val chosen = LinkedHashMap<String, RectF>()
        val a = anchor
        for (spot in all) {
            val isAnchor = a != null && a.first == spot.pkg && a.second == spot.source
            if (isAnchor || !chosen.containsKey(spot.pkg)) chosen[spot.pkg] = spot.rect
        }
        published.clear()
        published.putAll(chosen)
        for ((pkg, r) in chosen) out.putIfAbsent(pkg, RectF(r).apply { offset(loc[0].toFloat(), loc[1].toFloat()) })
    }

    override fun setHiddenPkg(pkg: String?) {
        if (hiddenPkg == pkg) return
        hiddenPkg = pkg
        invalidateAll()
    }

    override val isIdle: Boolean
        get() = !crossfading && tilesPane.isIdle && listPane.isIdle && folder.isIdle

    override fun appsChanged() = rebuild()

    override fun setImeInset(px: Int) { listPane.bottomSpace = px.toFloat() }

    override fun openSearch() = enterList(focusSearch = true)

    override fun setWallpaper(w: dev.launcher.app.Wallpaper?) {
        if (w === wallpaper) return
        wallpaper = w
        tileGlass = w?.let { makeGlass(it, m.tileRadius) }
        searchGlass = w?.let { makeGlass(it, m.searchHeight / 2f) }
        panelGlass = w?.let { makeGlass(it, m.folderRadius) }
        invalidateAll()
        searchBar.invalidate()
    }
}

/** The search field: a capsule with a magnifier; editable only in list mode (a tap in tile mode switches to the list). */
internal class SearchBar(ctx: Context, private val lib: AppLibraryView) : FrameLayout(ctx) {
    private val m = lib.m

    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = m.pt(1.8f); strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private val lens = Path()
    private var cancelSpace = 0f
    private var editable = false
    val edit = EditText(ctx)

    init {
        setWillNotDraw(false)
        edit.apply {
            background = null
            hint = "App Library"
            setHintTextColor(0x99FFFFFF.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, m.pt(17f))
            typeface = dev.launcher.app.theme.Fonts.text(400)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
            isFocusableInTouchMode = false
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { lib.onQuery(s?.toString().orEmpty()) }
            })
            setOnEditorActionListener { _, _, _ -> lib.launchFirstResult(); true }
        }
        addView(edit, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            leftMargin = m.pt(38f).roundToInt()
            rightMargin = m.pt(12f).roundToInt()
        })
    }

    fun setEditable(on: Boolean) {
        editable = on
        edit.isFocusable = on
        edit.isFocusableInTouchMode = on
        if (!on) {
            edit.clearFocus()
            context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
        }
    }

    fun hideKeyboard() {
        edit.clearFocus()
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
    }

    fun focusAndShowKeyboard() {
        edit.requestFocus()
        edit.post { context.getSystemService(InputMethodManager::class.java).showSoftInput(edit, 0) }
    }

    fun clear() { edit.setText("") }

    /** Makes room for "Cancel" on the right (0 = none, 1 = full). */
    fun animateCancelSpace(to: Float, ms: Long) {
        val from = cancelSpace
        if (ms <= 0) { cancelSpace = to; applySpace(); return }
        android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            addUpdateListener { cancelSpace = it.animatedValue as Float; applySpace() }
            start()
        }
    }

    private fun applySpace() {
        (edit.layoutParams as LayoutParams).rightMargin = (m.pt(12f) + cancelSpace * m.pt(84f)).roundToInt()
        edit.requestLayout()
        invalidate()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent) = !editable

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editable && event.actionMasked == MotionEvent.ACTION_UP) lib.enterList(focusSearch = true)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val right = width - cancelSpace * m.pt(84f)
        rect.set(0f, 0f, right, height.toFloat())
        // The same glass as the tiles and the dock (a capsule).
        lib.drawGlass(canvas, lib.searchGlass, rect, height / 2f, this)
        // Magnifier glyph.
        val cx = m.pt(20f)
        val cy = height / 2f - m.pt(1f)
        val lr = m.pt(6.5f)
        lens.reset()
        lens.addCircle(cx, cy, lr, Path.Direction.CW)
        canvas.drawPath(lens, glyph)
        canvas.drawLine(cx + lr * 0.72f, cy + lr * 0.72f, cx + lr * 1.45f, cy + lr * 1.45f, glyph)
    }
}
