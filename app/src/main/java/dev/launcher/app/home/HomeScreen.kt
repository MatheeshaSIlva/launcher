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
import dev.launcher.app.design.Curve
import dev.launcher.app.motion.Mover
import dev.launcher.app.motion.mover
import dev.launcher.app.Wallpaper
import dev.launcher.app.apps.AppEntry
import dev.launcher.app.apps.Apps
import dev.launcher.app.apps.Icons
import dev.launcher.app.drawer.AppDrawer
import dev.launcher.app.drawer.DrawerHost
import dev.launcher.app.drawer.Drawers
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.timed
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
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
        /** The system's wallpaper picker, opening out of [from] (screen px). */
        fun openWallpaperPicker(from: RectF)
        /** The launcher's settings, home sliding into their picture of it (home shows at rest now). */
        fun openSettings()
        /** Everything came to rest: a good moment to record the picture of home. */
        fun onHomeSettled()
        /** The layout was edited (moved, removed, added): save it. */
        fun layoutChanged()
        /** Home was built again while not on screen (laid out, icons published): its picture is recorded again. */
        fun onRebuilt()
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
    // Home blurred behind a menu or the widget gallery (a small copy over the scene; see SceneBlurView).
    private val sceneBlur = SceneBlurView(ctx, scene)
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
    private var imeAnimating = false
    private var editMode: EditMode? = null
    private var editBar: View? = null
    private var menu: ContextMenuView? = null

    /**
     * Live glass (MaterialPainter.drawLive: the App Library's and Spotlight's search fields, the long-press menus, the widget
     * gallery) samples what is behind it through a render effect, and an effect drawn under a clip loses what lies outside
     * the clip (CLAUDE.md). One UI 9 renders with Vulkan and redraws only what changed: a blinking cursor redrew only the
     * field's text box, and the glass inside it came out cut, a dark rectangle in the App Library's search field until
     * something redrew the whole field. While such glass shows, any change in this window marks the whole window as
     * changed (home's own display list is recorded again: a few child references; the children keep theirs).
     */
    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        if (isAttachedToWindow && liveGlassShows()) invalidate()
    }

    private fun liveGlassShows(): Boolean =
        drawerProgress() > 0f || spotlight?.visibility == View.VISIBLE || menu?.visibility == View.VISIBLE ||
            picker?.visibility == View.VISIBLE || alert?.visibility == View.VISIBLE
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

    /**
     * The appearance changed (every frame of its crossfade): everything redraws in the new colours (text fields take them),
     * and once it has settled home is recorded again for gesture nav's picture of it.
     */
    private val onAppearance: () -> Unit = {
        drawer?.onAppearance()
        spotlight?.onAppearance()
        updateStatusDark()
        updateLabelTones(animate = false)   // every frame of the crossfade: the names follow it
        indicator?.redrawLabel(); editBar?.invalidate()   // labels on glass too
        // The Edit button lifted above its menu is a picture: taken again in the new colours.
        if (menu?.isShowing == true && editMode?.active == true) (editBar as? EditMode.Bar)?.let { menu?.replaceLifted(it.editButtonNode()) }
        invalidateTree(this)
        if (!dev.launcher.app.theme.Appearance.changing) post { if (isIdle) listener.onHomeSettled() }
    }

    private fun invalidateTree(v: View) {
        v.invalidate()
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) invalidateTree(v.getChildAt(i))
    }

    /** After any layout (a page laid out, an icon moved in edit mode): the names take the tone of the wallpaper under them. */
    private val toneAfterLayout = android.view.ViewTreeObserver.OnGlobalLayoutListener { updateLabelTones(animate = true) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        dev.launcher.app.design.Design.addListener(onDesign)
        dev.launcher.app.design.Design.addPhoneListener(onPhonePalette)
        dev.launcher.app.layout.Setup.addListener(onSetup)
        viewTreeObserver.addOnGlobalLayoutListener(toneAfterLayout)
        dev.launcher.app.Badges.addListener(onBadges)
        dev.launcher.app.theme.Appearance.addListener(onAppearance)
        onBadges()
    }

    override fun onDetachedFromWindow() {
        dev.launcher.app.design.Design.removeListener(onDesign)
        dev.launcher.app.design.Design.removePhoneListener(onPhonePalette)
        dev.launcher.app.layout.Setup.removeListener(onSetup)
        viewTreeObserver.removeOnGlobalLayoutListener(toneAfterLayout)
        dev.launcher.app.Badges.removeListener(onBadges)
        dev.launcher.app.theme.Appearance.removeListener(onAppearance)
        super.onDetachedFromWindow()
    }

    init {
        scene.addView(wallpaperView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        scene.addView(fg, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        scene.clipChildren = false
        addView(scene, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(sceneBlur, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        fg.clipChildren = false
        // Home's depth zoom scales everything above the wallpaper as one (through a GPU layer): glass inside it keeps sampling
        // where it is laid out (see GlassView.placement).
        fg.setTag(dev.launcher.app.R.id.glass_root, true)
        // Search fields ride up with the keyboard frame by frame (not only once it has finished opening).
        if (Build.VERSION.SDK_INT >= 30) {
            setWindowInsetsAnimationCallback(object : android.view.WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                // While the keyboard animates, its final height arrives first (as ordinary insets, before the first step):
                // applied, the search field jumped to its end position for a frame and dropped back. Only the steps count then.
                override fun onPrepare(animation: android.view.WindowInsetsAnimation) {
                    if (animation.typeMask and WindowInsets.Type.ime() != 0) imeAnimating = true
                }

                override fun onProgress(insets: WindowInsets, running: MutableList<android.view.WindowInsetsAnimation>): WindowInsets {
                    if (running.any { it.typeMask and WindowInsets.Type.ime() != 0 }) applyIme(insets.getInsets(WindowInsets.Type.ime()).bottom)
                    return insets
                }

                override fun onEnd(animation: android.view.WindowInsetsAnimation) {
                    if (animation.typeMask and WindowInsets.Type.ime() == 0) return
                    imeAnimating = false
                    rootWindowInsets?.let { applyIme(it.getInsets(WindowInsets.Type.ime()).bottom) }
                }
            })
        }
    }

    private fun applyIme(ime: Int) {
        imeInset = ime
        drawer?.setImeInset(maxOf(0, ime - bottomInset))
        spotlight?.setInsets(bottomInset, ime)
        picker?.setImeInset(ime)
    }

    // ================================================================== setup

    fun setConfig(c: HomeConfig) {
        if (c == cfg && m != null) return
        cfg = c
        Icons.shape = c.iconShape
        pos = 0f; sheet = 0f
        if (m == null) build() else rebuild("the setup changed")
    }

    // ================================================================== building again (B4, docs/PLAN_LAYOUTS_THEMES.md)

    /** The design's structure the current build was made from ([dev.launcher.app.design.Design.structure]). */
    private var builtStructure = -1

    /**
     * Another theme, an edit of it, a font that arrived: what home built once from the design is built again. On screen, home
     * first covers itself with what it shows now, rendered at once from the last frame's display lists: from here on any of
     * its views may draw again in the new values (the dock's glass did, frames before a screen copy arrived), and the new
     * look must come from the old one exactly as it was.
     */
    private val onDesign: () -> Unit = {
        if (m != null && dev.launcher.app.design.Design.structure != builtStructure) {
            if (shownElsewhere) rebuildUnder("the colours changed with the wallpaper")
            // A new wallpaper's reveal under way (the phone's palette following it a moment later): the new look's own
            // reveal follows it, not at once (two fronts crossed each other; a still of home would have stopped it).
            else if (wallpaperView.transitioning && shownNow()) lookAfterWallpaper = true
            else if (shownNow() && rebuildFrozen == null) {
                val t0 = android.os.SystemClock.uptimeMillis()
                renderNow()?.let { freeze(it) }
                frozenAt = android.os.SystemClock.uptimeMillis()
                AppLog.log("[home] the old look held (${frozenAt - t0} ms)")
            }
            if (!shownElsewhere && !lookAfterWallpaper) requestRebuild("the theme changed")
        }
    }

    /** A change of the look arrived during a new wallpaper's reveal: shown once that is over ([wallpaperRevealed]). */
    private var lookAfterWallpaper = false

    /** A new wallpaper's reveal is over: a change of the look that waited for it is shown now. */
    fun wallpaperRevealed() {
        if (!lookAfterWallpaper) return
        lookAfterWallpaper = false
        onDesign()
    }

    /** A new look is being revealed (or held for it): a new wallpaper's reveal waits for it ([afterLook]). */
    val revealingLook: Boolean get() = rebuildFrozen != null || rebuildPosted || rebuildCover != null

    private val afterLookRuns = ArrayList<Runnable>()

    /** Runs [r] once no new look is being revealed (at once if none is). */
    fun afterLook(r: Runnable) { if (!revealingLook) r.run() else afterLookRuns += r }

    private fun lookRevealed() {
        if (revealingLook) return
        val runs = afterLookRuns.toList()
        afterLookRuns.clear()
        for (r in runs) r.run()
    }

    /** The phone's own colours may have changed: apps' widgets, drawn in them, follow ([LauncherWidgetHostView.refreshColours]). */
    private val onPhonePalette: () -> Unit = { widgetsFollowPhone() }

    /**
     * Apps' widgets inflated again in the phone's palette, light or dark as it is now (each fading from its old look), once
     * the system is done with the change ([dev.launcher.app.SystemCalm]). Such a change reaches every app at once and keeps
     * the system busy a while (~1.4 s on the emulator); a widget's views going and coming register and unregister receivers
     * with it (a clock's TextClock), and each such call waited for it on the main thread, inside the change's own callback
     * (116 ms on the emulator, several in a row: home froze a moment after Other apps gave Android a palette). Then one widget
     * a frame ([nextWidget]), while home is still and untouched.
     */
    fun widgetsFollowPhone() {
        val gen = ++LauncherWidgetHostView.phoneGen
        dev.launcher.app.SystemCalm.after(context, WIDGETS_SETTLE_MS) {
            if (gen != LauncherWidgetHostView.phoneGen) return@after
            widgetsSince = android.os.SystemClock.uptimeMillis()
            nextWidget()
        }
    }

    /** When the widgets began to follow the phone's palette (uptime ms). */
    private var widgetsSince = 0L
    private val widgetStep = Runnable { nextWidget() }

    /**
     * The next widget not yet inflated in the phone's palette, inflated again (~5-20 ms of the main thread each, its app's
     * resources loaded): not while home moves or a finger is on it, nor over the settings (on this thread too), unless it has
     * waited [WIDGETS_MAX_WAIT_MS]; out of sight at once. The one after it a frame later.
     */
    private fun nextWidget() {
        removeCallbacks(widgetStep)
        val gen = LauncherWidgetHostView.phoneGen
        val stale = widgetViews().firstNotNullOfOrNull { w -> (w as? AppWidgetFrame)?.hostView?.takeIf { it.inflatedIn != gen } } ?: return
        val now = android.os.SystemClock.uptimeMillis()
        val busy = dev.launcher.app.design.SystemPalette.settingsInFront ||
            (dev.launcher.app.GestureNav.homeVisible && (!isIdle || dev.launcher.app.HomeBridge.touching || now - dev.launcher.app.HomeBridge.lastTouchAt < WIDGETS_UNTOUCHED_MS))
        if (busy && now - widgetsSince < WIDGETS_MAX_WAIT_MS) { postDelayed(widgetStep, WIDGETS_RETRY_MS); return }
        stale.refreshColours()
        postOnAnimation { post(widgetStep) }
    }

    /** A change of the design under way is shown by its caller ([changeShownBy]), not by home's own reveal. */
    private var shownElsewhere = false

    /**
     * [block] changes the design, and its caller shows the change itself, over the whole screen (a new wallpaper whose
     * colours the palette follows: the wallpaper's reveal shows both, [coverAlongFront]): home is built again at once,
     * under it, with no reveal of its own.
     */
    fun changeShownBy(block: () -> Unit) {
        shownElsewhere = true
        try { block() } finally { shownElsewhere = false }
    }

    /** Built again at once, nothing of its own shown (see [changeShownBy]). */
    private fun rebuildUnder(why: String) {
        if (width == 0 || height == 0) return
        coldHoldWanted = false
        removeCallbacks(coldRetry)
        if (arrivalAnimating || arrivalHeld) { arrivalHeld = false; finishArrival() }
        rebuildFrozen?.let { b -> rebuildFrozen = null; rebuildCover?.let { removeView(it) }; rebuildCover = null; b.recycle() }
        AppLog.log("[home] $why: building home again (under that change's own reveal)")
        buildBehind()
    }

    /** Home's content as it shows now (the wallpaper left out, transparent there), or null. Main thread. */
    fun contentPicture(): android.graphics.Bitmap? = dev.launcher.app.design.ViewPicture.renderClear(fg)

    private var frontCover: View? = null

    /**
     * [old] (home's content as it looked: [contentPicture]) laid over the new look, right above the content, giving way along
     * the wallpaper reveal's front ([FrontCover]). Returns what moves it on each of the reveal's frames (its progress and
     * time), or null where it cannot be drawn (the new look then shows at once). [endFrontCover] takes it away.
     */
    fun coverAlongFront(old: android.graphics.Bitmap): ((Float, Float) -> Unit)? {
        if (Build.VERSION.SDK_INT < 33) { old.recycle(); return null }
        val cover = try {
            FrontCover(old, width.toFloat(), height.toFloat(), resources.displayMetrics.density * REVEAL_CELL_DP)
        } catch (t: Throwable) {
            AppLog.log("[home] the old look cannot give way along the front (${t.javaClass.simpleName}: ${t.message})")
            old.recycle()
            return null
        }
        var progress = 0f
        var time = 0f
        val v = object : View(context) {
            override fun onDraw(c: android.graphics.Canvas) { if (!old.isRecycled) cover.draw(c, progress, time) }
        }
        v.tag = old
        endFrontCover()
        frontCover = v
        // In the scene, right above the content (under menus and sheets; blurred with the rest behind one).
        scene.addView(v, scene.indexOfChild(fg) + 1, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        return { p, t -> progress = p; time = t; v.invalidate() }
    }

    fun endFrontCover() {
        val v = frontCover ?: return
        frontCover = null
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        (v.tag as? android.graphics.Bitmap)?.recycle()
    }

    private fun shownNow() = windowVisibility == View.VISIBLE && dev.launcher.app.GestureNav.homeVisible

    /** The picture of the old look covering home until it is built again (null: none), and since when. */
    private var rebuildFrozen: android.graphics.Bitmap? = null
    private var frozenAt = 0L

    private fun freeze(b: android.graphics.Bitmap) {
        rebuildAnim?.cancel()
        rebuildCover?.let { it.animate().cancel(); removeView(it) }
        rebuildFrozen = b
        val v = object : View(context) {
            override fun onDraw(c: android.graphics.Canvas) { if (!b.isRecycled) c.drawBitmap(b, 0f, 0f, null) }
        }
        rebuildCover = v
        addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Home as it shows now (or would, behind another screen), exactly (the settings app's "Your setup"). Main thread. */
    fun pictureNow(): android.graphics.Bitmap? = if (declinesPicture()) null else pictured { renderNow() }

    /**
     * As [pictureNow], without waiting for the GPU ([dev.launcher.app.design.ViewPicture.renderLater]): false if home
     * declines now (where [pictureNow] gives null), else [then] gets the picture on the main thread. Main thread.
     */
    fun pictureLater(then: (android.graphics.Bitmap?) -> Unit): Boolean {
        if (declinesPicture()) return false
        pictured { dev.launcher.app.design.ViewPicture.renderLater(this, then) }
        return true
    }

    private fun declinesPicture(): Boolean {
        // Not shown yet in this process (its cold arrival still waits for the first time it is seen), or no wallpaper read
        // yet: home has never drawn at rest, and its picture was black (the settings kept it as home's look).
        if (coldHoldWanted || m == null || pages.isEmpty() || width == 0 || wallpaperView.wallpaper == null) return true
        // A clock built again behind (a new theme while the settings were open) has not set its time yet: it would show none.
        for (c in clocks) c.refresh(animate = false)
        // Its numerals' shape still being built off the main thread, or fading in (home built again for a new font): not
        // yet (the settings pictured a clock with a date and no time; they ask again).
        return clocks.any { it.animating }
    }

    /** Runs [take] (a picture of home, taken in it) with home as it rests, then puts back what moves. */
    private fun <T> pictured(take: () -> T): T {
        // The wallpaper fading in (read just now): pictured as it will rest (the settings kept a black home with tinted glass).
        val wallA = wallpaperView.alpha
        if (wallA < 1f) wallpaperView.alpha = 1f
        try { return atRest(take) } finally { if (wallA < 1f) wallpaperView.alpha = wallA }
    }

    private fun <T> atRest(take: () -> T): T {
        // After a cold start home holds its arrival's first frame (its icons out) until it shows: pictured at rest instead
        // (the settings showed the wallpaper alone), then held again.
        if (arrivalHeld && !arrivalAnimating) {
            applyArrival(60.0)
            try { return take() } finally { applyArrival(0.0) }
        }
        // Mid-arrival (the settings opened over home as it came up): at rest too, then back where the arrival is.
        if (arrivalAnimating) {
            applyArrival(60.0)
            try { return take() } finally { applyArrival(arrivalT) }
        }
        return take()
    }

    /**
     * Home as it shows now (the last frame's display lists, drawn again on the GPU: glass and blur exact), or null. A view
     * already marked to draw again is recorded anew in it, so this is taken before anything changes.
     */
    private fun renderNow(): android.graphics.Bitmap? = dev.launcher.app.design.ViewPicture.render(this)

    /** The user's setup changed (the drawer's placement, home's options): applied at once, not when home next shows. */
    private val onSetup: () -> Unit = { setConfig(HomeConfig.load(context)) }

    private var rebuildPosted = false

    /**
     * Builds home again once the current change is complete (several notifications in a row make one rebuild): next on the
     * main thread, before frames or work queued meanwhile (the old look holds still until then).
     */
    private fun requestRebuild(why: String) {
        if (rebuildPosted) return
        rebuildPosted = true
        val run = Runnable {
            rebuildPosted = false
            // Built for this design already (under the wallpaper's reveal, meanwhile): nothing left to reveal.
            if (dev.launcher.app.design.Design.structure != builtStructure || rebuildFrozen != null) rebuild(why)
            // (Built behind, or nothing to do: whatever waited for the new look goes on.)
            lookRevealed()
        }
        val h = handler
        if (h == null) post(run) else h.postAtFrontOfQueue(run)
    }

    /**
     * Everything above the wallpaper built again. On screen, the old look fades into the new over it (a picture of the
     * screen as it was, taken first). Not on screen, home is laid out at once, so where its icons are (where gesture nav
     * closes an app into) and its picture are current before it is seen: a home built behind an app and never laid out
     * once sent the closing card into the screen's corner (the Pixel run).
     */
    private fun rebuild(why: String) {
        if (m == null || width == 0 || height == 0) return
        // Not an arrival: a cold start's hold still waiting, or an arrival under way, ends at rest (built again, a pending hold
        // took the new build's first frame and home came up black).
        coldHoldWanted = false
        removeCallbacks(coldRetry)
        if (arrivalAnimating || arrivalHeld) { arrivalHeld = false; finishArrival() }
        val shown = shownNow()
        AppLog.log("[home] $why: building home again" + if (shown) " (revealing)" else " (behind)")
        val frozen = rebuildFrozen
        rebuildFrozen = null
        if (!shown) {
            if (frozen != null) { rebuildCover?.let { removeView(it) }; rebuildCover = null; frozen.recycle() }
            buildBehind()
            return
        }
        val old = frozen ?: renderNow()
        if (old != null) {
            val t0 = android.os.SystemClock.uptimeMillis()
            build()
            AppLog.log("[home] built in ${android.os.SystemClock.uptimeMillis() - t0} ms" + if (frozen != null) ", ${t0 - frozenAt} ms after the old look was held" else "")
            coverWith(old)
            return
        }
        snapshot { copy ->
            build()
            if (copy != null) coverWith(copy)
        }
    }

    private fun buildBehind() {
        build()
        measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        layout(left, top, right, bottom)
        updateLabelTones(animate = false)
        publishIcons()
        listener.onRebuilt()
    }

    /** The screen as it shows now (our window: wallpaper, home, menus), or null if it cannot be copied. */
    private fun snapshot(then: (android.graphics.Bitmap?) -> Unit) {
        val win = (context as? android.app.Activity)?.window
        if (win == null) { then(null); return }
        val at = IntArray(2)
        getLocationInWindow(at)
        val bmp = try { android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888) } catch (_: Throwable) { then(null); return }
        try {
            android.view.PixelCopy.request(win, android.graphics.Rect(at[0], at[1], at[0] + width, at[1] + height), bmp, { r ->
                if (r != android.view.PixelCopy.SUCCESS) { bmp.recycle(); then(null); return@request }
                // On the GPU before it is shown: a software bitmap is uploaded inside its first frame (5+ ms).
                val hw = try { bmp.copy(android.graphics.Bitmap.Config.HARDWARE, false) } catch (_: Throwable) { null }
                if (hw != null) bmp.recycle()
                then(hw ?: bmp)
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (t: Throwable) {
            AppLog.log("[home] no picture of the screen to crossfade from: ${t.message}")
            bmp.recycle()
            then(null)
        }
    }

    private var rebuildCover: View? = null
    private var rebuildAnim: android.animation.ValueAnimator? = null

    /**
     * [old] over the new home, giving way to it: the new look spreads from the bottom centre (where a change comes from:
     * the gesture bar, the settings closing) on `motion.home.rebuild` ([ThemeReveal]), each item springing into place as
     * the front passes it (`motion.home.rebuild-settle`). A crossfade where shaders cannot run. Touches go to the new home
     * meanwhile.
     */
    private fun coverWith(old: android.graphics.Bitmap) {
        rebuildAnim?.cancel()
        // (The still picture it replaces is this same image: recycled with the reveal at its end.)
        rebuildCover?.let { it.animate().cancel(); removeView(it) }
        if (Build.VERSION.SDK_INT < 33) { fadeCover(old); return }
        val reveal = try {
            ThemeReveal(old, width.toFloat(), height.toFloat(), width / 2f, height.toFloat(), resources.displayMetrics.density * REVEAL_CELL_DP)
        } catch (t: Throwable) {
            AppLog.log("[home] the new look's reveal cannot be drawn (${t.javaClass.simpleName}: ${t.message}): crossfading")
            fadeCover(old); return
        }
        var progress = 0f
        var time = 0f
        val v = object : View(context) {
            override fun onDraw(c: android.graphics.Canvas) { if (!old.isRecycled) reveal.draw(c, progress, time) }
        }
        rebuildCover = v
        addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val curve = Motion.role(dev.launcher.app.motion.MotionTokens.HOME_REBUILD)
        settleAsRevealed(reveal, dev.launcher.app.motion.CurveTiming(curve))
        rebuildAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).timed(curve).apply {
            addUpdateListener { a -> progress = a.animatedValue as Float; time = a.currentPlayTime / 1000f; v.invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    removeView(v)
                    if (rebuildCover === v) rebuildCover = null
                    if (rebuildAnim === animation) rebuildAnim = null
                    old.recycle()
                    listener.onHomeSettled()
                    lookRevealed()
                }
            })
            start()
        }
    }

    /**
     * The new home's items (the page shown, the dock's icons, the Search pill) start a little small and spring into place
     * when the front reaches their centre (known after the next layout). Until then they are not drawn at all (alpha 0: the
     * renderer skips them): under the old look they would cost the GPU every frame for nothing (iOS 27's glass widgets
     * took the reveal to 12 ms frames on the S24).
     */
    private fun settleAsRevealed(reveal: ThemeReveal, timing: dev.launcher.app.motion.CurveTiming) {
        val items = ArrayList<View>()
        pages.getOrNull(pos.roundToInt())?.let { p -> for (c in 0 until p.childCount) items += p.getChildAt(c) }
        dock?.icons()?.let { items += it }
        indicator?.let { items += it }
        for (item in items) { item.scaleX = SETTLE_FROM; item.scaleY = SETTLE_FROM; item.alpha = 0f }
        viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                viewTreeObserver.removeOnPreDrawListener(this)
                val me = IntArray(2)
                val at = IntArray(2)
                getLocationOnScreen(me)
                val settle = Motion.role(dev.launcher.app.motion.MotionTokens.HOME_REBUILD_SETTLE)
                for (item in items) {
                    item.getLocationOnScreen(at)
                    val cx = at[0] - me[0] + item.width * item.scaleX / 2f
                    val cy = at[1] - me[1] + item.height * item.scaleY / 2f
                    val delay = timing.timeOf(reveal.progressAt(kotlin.math.hypot(cx - reveal.originX, cy - reveal.originY)))
                    val k = dev.launcher.app.motion.MotionValue(SETTLE_FROM, 1000f, { s -> item.scaleX = s; item.scaleY = s })
                    item.postDelayed({ item.alpha = 1f; k.animateTo(1f, settle) }, delay)
                }
                return true
            }
        })
    }

    /** [old] over the new home, fading out on `motion.home.rebuild` (where the reveal cannot be drawn). */
    private fun fadeCover(old: android.graphics.Bitmap) {
        val v = object : View(context) {
            override fun onDraw(c: android.graphics.Canvas) { if (!old.isRecycled) c.drawBitmap(old, 0f, 0f, null) }
        }
        rebuildCover = v
        addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        v.animate().alpha(0f).timed(Motion.role(dev.launcher.app.motion.MotionTokens.HOME_REBUILD)).withEndAction {
            removeView(v)
            if (rebuildCover === v) rebuildCover = null
            old.recycle()
            listener.onHomeSettled()
            lookRevealed()
        }.start()
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
        editBar?.invalidate(); indicator?.redrawLabel()   // labels on glass take the new wallpaper's tone
        post { updateLabelTones(animate = true) }
    }

    /** Every widget on the pages. */
    private fun widgetViews(): List<HomeWidgetView> = pages.flatMap { p -> p.itemViews().filterIsInstance<HomeWidgetView>() }

    /** Glass on the pages (widgets): redrawn while the pages move, so it keeps refracting what is behind it. */
    private fun pageGlass(): List<GlassView> = widgetViews().flatMap { it.glassViews() }

    private val toneAt = IntArray(2)
    private val tonePage = IntArray(2)
    private val toneRect = RectF()

    /**
     * Every name on the pages takes the tone of the wallpaper under it ([LabelTone]: white, or dark over a bright one), as
     * it shows when its page is in front. After a layout, a new wallpaper (on a spring: [animate]) or a frame of the
     * appearance's crossfade (its dim changes how light the wallpaper is).
     */
    fun updateLabelTones(animate: Boolean) {
        if (wallpaper == null || width == 0) return
        for (p in pages) {
            p.getLocationOnScreen(tonePage)
            for (i in 0 until p.childCount) {
                val v = p.getChildAt(i) as? TonedLabel ?: continue
                (v as View).getLocationOnScreen(toneAt)
                v.labelArea(toneRect)
                // Its place with its page in front: the pages lie side by side, each the screen's width.
                toneRect.offset((toneAt[0] - tonePage[0]).toFloat(), toneAt[1].toFloat())
                v.setLabelTone(LabelTone.of(wallpaperLuminanceUnder(toneRect)), animate)
            }
        }
    }

    /** How light the wallpaper is under [rectOnScreen] (0..1; 0.5 without our copy of it). */
    fun wallpaperLuminanceUnder(rectOnScreen: RectF): Float {
        val w = wallpaper ?: return 0.5f
        return w.luminanceUnder(rectOnScreen, width, height)
    }

    /**
     * Home is about to be uncovered (gesture nav's picture goes once home has drawn its next frame): glass that skipped
     * redrawing while covered is brought up to date in that frame (a stale dock showed the wallpaper from the wrong place
     * for one frame at the end of a close, seen on the S24).
     */
    fun refreshGlass() { for (g in glassViews()) g.refreshIfStale() }

    /** Glass surfaces (dock, Search pill, widgets), for the wallpaper reveal to drive frame by frame. */
    fun glassViews(): List<GlassView> = listOfNotNull(dock?.glass, indicator?.glass) + pageGlass() + ((editBar as? EditMode.Bar)?.glassViews() ?: emptyList())

    private fun giveWallpaper(g: GlassView) {
        val metrics = m ?: return
        g.setWallpaper(wallpaper, metrics.w, metrics.h, resources.displayMetrics.density * REVEAL_CELL_DP)
    }

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
        // A pixel or two is no change: shown turned under an app in landscape, Android gives the status bar a pixel less, and
        // back once the display has turned; home rebuilt twice in every close from landscape (its pictures, still on screen
        // then, lost the clock's numerals with the clock they drew).
        if (abs(top - topInset) > INSET_SLOP_PX || abs(b - bottomInset) > INSET_SLOP_PX || radius != deviceRadius) {
            if (m != null) AppLog.log("[home] insets changed (top $topInset->$top, bottom $bottomInset->$b, corner $deviceRadius->$radius): rebuilding")
            topInset = top; bottomInset = b; deviceRadius = radius
            build()
        }
        if (!imeAnimating) applyIme(ime)
        return insets
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == oldw && h == oldh) return
        // Home is upright only (it handles orientation changes itself, never relaunched for them). Given a sideways size for
        // a moment (its configuration followed an app's landscape while it was in the background, until it comes back
        // turned), it keeps its upright layout, and coming back to the size it was built for is no change: a rebuild in the
        // middle of a close from a landscape app showed it black and played its arrival.
        if (m != null && (w > h || (w == builtW && h == builtH))) return
        if (oldw != 0) AppLog.log("[home] size changed (${oldw}x$oldh -> ${w}x$h): rebuilding")
        post { build() }
    }

    // The size home was last built for.
    private var builtW = 0
    private var builtH = 0

    /** (Re)creates everything above the wallpaper for the current size, insets and config. */
    private fun build() {
        if (width == 0 || height == 0) return
        // Never laid out sideways once it has an upright layout (see onSizeChanged): it keeps that one.
        if (m != null && width > height) return
        val metrics = HomeMetrics(width, height, topInset, bottomInset, deviceRadius, cfg, dev.launcher.app.design.Scale.unitPx(context, minOf(width, height)))
        m = metrics
        builtW = width
        builtH = height
        HomeMetrics.frame = HomeMetrics.Frame(width, height, topInset, bottomInset)
        builtStructure = dev.launcher.app.design.Design.structure
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
        val pillTone = GlassLabelTone { ind.redrawLabel() }
        ind.labelColor = {
            val o = IntArray(2)
            ind.getLocationOnScreen(o)
            pillTone.color(wallpaperLuminanceUnder(RectF(o[0].toFloat(), o[1].toFloat(), o[0] + ind.width.toFloat(), o[1] + ind.height.toFloat())))
        }
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
        // Editing goes on in the new build (a scheduled dark mode or new colours arriving meanwhile): it ended, unseen under
        // the old look's picture, and the next drag on a widget's handle pulled Spotlight down instead.
        val editing = em.active
        if (editing) em.exit(now = true)
        val bar = em.Bar(context).also { editBar = it; it.visibility = View.GONE }
        // The Edit button lifted above its menu is a picture: taken again while its label's tone springs to a new look (it
        // kept the tone of the change's last frame: a dark label on the dark capsule after Dark was chosen in the menu).
        bar.onLabelTone = { if (menu?.isShowing == true && editMode?.active == true) menu?.replaceLifted(bar.editButtonNode()) }
        fg.addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, (metrics.gridTop - topInset - metrics.pt(6f)).roundToInt().coerceAtLeast(1)).apply { topMargin = topInset })
        overlay.removeAllViews()
        menuK = 0f; pickerK = 0f; applySceneBlur()
        val mv = ContextMenuView(context, metrics, { k ->
            menuK = k; applySceneBlur()
            // The item the menu is for fades out under the lifted copy as the menu opens (its name went at once); closing,
            // it stays hidden until the copy has settled back into its place (then onClosed shows it again).
            menuItem?.let { v -> if (menu?.opening == true) v.alpha = 1f - k }
        }, { c -> scene.draw(c) }).also { menu = it }
        overlay.addView(mv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val pk = WidgetPicker(context, metrics, pickerHost).also { picker = it }
        overlay.addView(pk, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        warmTries = 0
        removeCallbacks(warmPicker)
        postDelayed(warmPicker, WARM_UP_DELAY_MS)
        // The App Library as built, once home is idle again (a new look's reveal over): after a new look its first showing
        // recorded every tile and uploaded every icon inside one frame (59 ms on the S24; the swipe to it froze: Matheesha).
        libraryTries = 0
        removeCallbacks(warmLibraryWhenIdle)
        postDelayed(warmLibraryWhenIdle, LIBRARY_WARM_MS)
        (em.ghostView.parent as? android.view.ViewGroup)?.removeView(em.ghostView)
        overlay.addView(em.ghostView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val av = AlertView(context, metrics) { c -> scene.draw(c) }.also { alert = it }
        overlay.addView(av, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applyGlassWallpaper()
        bindLayout()
        if (editing) em.enter(haptic = false, now = true)
    }

    /**
     * Black or white content for our status bar on home, as what is under it now: the App Library's or Spotlight's material
     * once it mostly covers home (light in light mode, dark in dark mode), else the wallpaper at the top (dimmed in dark mode).
     */
    fun updateStatusDark() {
        val a = dev.launcher.app.theme.Appearance
        val material = maxOf(drawerProgress(), spotlight?.progress ?: 0f)
        val w = wallpaper
        HomeBridge.homeStatusDark = if (material > 0.5f) a.dark < 0.5f
            else w != null && w.topLuminance * (1f - a.wallpaperDim) > 0.62f
    }

    private fun applyGlassWallpaper() {
        val metrics = m ?: return
        for (g in glassViews()) g.setWallpaper(wallpaper, metrics.w, metrics.h, resources.displayMetrics.density * REVEAL_CELL_DP)
    }

    private fun bindLayout() {
        val metrics = m ?: return
        val l = layout ?: return
        // Fewer columns, rows or dock icons than the layout was made for: what no longer fits moves on (and is kept so).
        if (HomeModel.fit(l, metrics.cfg)) listener.layoutChanged()
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
            ind.visibility = View.VISIBLE   // the Search pill shows even with a single page (as on iOS)
            ind.setOnClickListener { openLibrarySearch() }
        }
        pos = pos.coerceIn(minPos(), maxPos())
        applyGlassWallpaper()   // widgets' glass was just created
        applyPositions()
        // Icons for what is on screen first, then the rest of the library in the background.
        Icons.preload(l.pages.flatten().filterIsInstance<HomeItem.App>().mapNotNull { Apps[it.key] } + l.dock.mapNotNull { Apps[it] }, metrics.iconSize)
        Icons.preload(Apps.all, metrics.iconSize)
        // Launch screens, so a cold launch has the app's own colour from its first frame: the apps on home first, then every
        // other app (the App Library, its folders and Spotlight launch them too; a late colour switched mid-launch).
        dev.launcher.app.apps.SplashColors.warm(context, l.pages.flatten().filterIsInstance<HomeItem.App>().mapNotNull { Apps[it.key]?.pkg } + l.dock.mapNotNull { Apps[it]?.pkg })
        dev.launcher.app.apps.SplashColors.warm(context, Apps.all.map { it.pkg }.distinct())
        post { publishIcons() }
        if (pendingArrival != null) post { pendingArrival?.let { cold -> pendingArrival = null; playArrival(cold) } }
        else if (coldHoldWanted) post { holdCold() }
    }

    private fun viewFor(item: HomeItem, metrics: HomeMetrics): View? = when (item) {
        is HomeItem.App -> Apps[item.key]?.let { appIcon(it, metrics, label = true) }
        is HomeItem.Widget -> when (item.kind) {
            "clock" -> ClockWidgetView(context, metrics, item.spanX, item.spanY, item.style).also { w ->
                clocks += w
                w.glassViews().forEach { giveWallpaper(it) }
                w.onSettled = { if (isIdle) listener.onHomeSettled() }
                w.setLabelShown(cfg.showWidgetLabels, animate = false)
                w.setOnLongClickListener { onWidgetLongPress(w); true }
                w.isHapticFeedbackEnabled = false   // home plays the long press's haptic itself (see appIcon)
                editMode?.adopt(w)
            }
            HomeItem.Widget.APP -> {
                val pkg = item.provider?.let { android.content.ComponentName.unflattenFromString(it)?.packageName }
                val label = pkg?.let { p -> Apps.forPkg(p)?.label } ?: ""
                AppWidgetFrame(context, metrics, item.spanX, item.spanY, widgets?.createView(item), label, glassBacking = item.style == HomeItem.Widget.GLASS).also { f ->
                    f.onGlassCreated = { g -> giveWallpaper(g) }
                    f.glassViews().forEach { giveWallpaper(it) }
                    f.setLabelShown(cfg.showWidgetLabels, animate = false)
                    f.setOnLongClickListener { onWidgetLongPress(f); true }
                    f.isHapticFeedbackEnabled = false   // home plays the long press's haptic itself (see appIcon)
                    editMode?.adopt(f)
                }
            }
            else -> null
        }
        is HomeItem.Folder -> null   // folders: a later build
    }

    private fun appIcon(e: AppEntry, metrics: HomeMetrics, label: Boolean) = IconView(context, metrics, label).apply {
        bind(e)
        setLabelShown(cfg.showLabels, animate = false)
        badge = dev.launcher.app.Badges.count(e.pkg)
        setOnLongClickListener { v -> onIconLongPress(v as IconView, e); true }
        // Home plays the long press's haptic itself (onIconLongPress): a long click handled by its listener also played
        // the view's own, after the menu was prepared (5-110 ms later on the S24: felt twice).
        isHapticFeedbackEnabled = false
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
        if (changed) {
            // The pages are kept when there are still as many: new icons grow into their cells, gone ones shrink away,
            // the rest glide; a different number of pages (one emptied, one added) rebuilds them.
            if (l.pages.size == pages.size && isAttachedToWindow) {
                for ((i, p) in pages.withIndex()) p.setItems(l.pages[i], animate = true)
                dock?.bind(l.dock.mapNotNull { key -> dock?.icons()?.firstOrNull { it.entry?.key == key } ?: Apps[key]?.let { appIcon(it, metrics, label = false) } }, animate = true)
                removeCallbacks(afterEdit)
                postDelayed(afterEdit, 450)
            } else bindLayout()
        }
        drawer?.appsChanged()
        widgets?.appsChanged()
        dev.launcher.app.apps.SplashColors.warm(context, Apps.all.map { it.pkg }.distinct())   // new installs
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
        // No pages yet (a cold start before the app list is read): the strip's position 0 would be the App Library, which
        // showed, empty, for over a second before home's pages arrived. Home shows its wallpaper and dock meanwhile.
        if (pages.isEmpty()) return 0f
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
        backdrop.visibility = if (dp > 0f && !(backgroundCovered && dp >= 1f)) View.VISIBLE else View.INVISIBLE
        stripShift = shift
        for (v in listOf(dock, dockShadow, indicator, editBar)) v?.translationX = shift
        // The arrival's zoom moves the dock and pill too: kept on top of the strip's shift (a swipe during it never jumps).
        if (arriving.isNotEmpty()) applyArrivalZoom(arrivalZ)
        dock?.glass?.invalidate()
        indicator?.glass?.invalidate()
        (editBar as? EditMode.Bar)?.glassViews()?.forEach { it.invalidate() }
        for (g in pageGlass()) g.invalidate()
        if (pendingSearch && dp > 0.5f) { pendingSearch = false; drawer?.openSearch() }
        indicator?.setPosition(pos.coerceIn(0f, (pages.size - 1).coerceAtLeast(0).toFloat()))
        drawer?.setOpenProgress(dp)
        if (dp > 0f) drawerWasOpen = true
        updateStatusDark()
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

    /** When home was last touched (uptime ms: a finger down anywhere on it, menus included). */
    private var touchedAt = 0L

    // Gesture nav is told once per touch that it moved past the slop, whoever takes it (pages, the App Library's list,
    // Spotlight): a card closing into an icon then gets out of the way, since its icon moves.
    private var movedX = 0f
    private var movedY = 0f
    private var moved = false

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                movedX = e.x; movedY = e.y; moved = false; HomeBridge.touching = true; HomeBridge.lastTouchAt = android.os.SystemClock.uptimeMillis()
                // A finger on the page beside the App Library may slide it in: its icons back on the GPU now, before the
                // slide's first frame (the touch slop gives them a frame or two).
                val li = libIndex
                if (li != null && drawerProgress() == 0f && abs(li - pos) <= 1f) (drawer?.view as? dev.launcher.app.drawer.AppLibraryView)?.tilesPane?.prepareIcons()
            }
            MotionEvent.ACTION_MOVE -> if (!moved && (abs(e.x - movedX) > slop || abs(e.y - movedY) > slop)) {
                moved = true
                HomeBridge.onHomeDragged?.invoke()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { HomeBridge.touching = false; HomeBridge.lastTouchAt = android.os.SystemClock.uptimeMillis() }
        }
        return super.dispatchTouchEvent(e)
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
                touchedAt = e.eventTime
                drag = Drag.NONE
                HomeBridge.onHomeTouched?.invoke()
                pendingDragView = null
                pendingExternal = null
                externalTouch = false
                enteredByPress = false
                removeCallbacks(emptyLongPress)
                // The widget gallery handles its own touches.
                if (picker?.isOpen == true) { drag = Drag.IGNORED; return false }
                // While Spotlight, the long-press menu or an alert is open, it handles every touch itself (an alert over edit
                // mode: its buttons lie over items, which edit mode took the touch for).
                if (spotlight?.isOpen == true || menu?.isShowing == true || alert?.isShowing == true) { drag = Drag.IGNORED; return false }
                // Editing: a touch on an item belongs to edit mode (drag it, or its remove badge).
                val em = editMode
                if (em != null && em.active && em.onDown(e.x, e.y)) { editTouch = true; return true }
                // A long press on empty space starts edit mode (on an item, the item's own long press opens its menu).
                if (em != null && !em.active && drawerProgress() == 0f && sheet == 0f && em.itemAt(e.x, e.y) == null)
                    postDelayed(emptyLongPress, ViewConfiguration.getLongPressTimeout().toLong())
                // A touch on a moving strip or sheet grabs it where it is (no tap goes through). On the last, invisible part of
                // a settle (under a pixel from rest) the motion just ends and the touch is an ordinary tap: a swipe to the
                // App Library followed at once by a tap on a tile must open it, not grab the page.
                if (pagerAnimating) {
                    if (abs(pos - pagerTarget) * metrics.w < 1f) { pagerAnimating = false; pos = pagerTarget; applyPositions(); if (!sheetAnimating) onSettled() }
                    else { pagerAnimating = false; beginPages(); return true }
                }
                if (sheetAnimating) {
                    if (abs(sheet - sheetTarget) * metrics.h < 1f) { sheetAnimating = false; sheet = sheetTarget; applyPositions(); onSettled() }
                    else { sheetAnimating = false; beginSheet(); return true }
                }
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
        pagerSpring = Motion.profile.pageSnap.mover().apply { start(pos * w, velocityPx, target * w) }
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
        sheetSpring = Motion.profile.drawer.mover().apply { start(sheet * h, velocityPx, target * h) }
        sheetTarget = target
        startFrames(pager = false)
    }

    // ---- animation driver

    private var pagerSpring: Mover? = null
    private var pagerTarget = 0f
    private var pagerAnimating = false
    private var sheetSpring: Mover? = null
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
        // Reaching the App Library ends edit mode (as on iOS); its bar slid away with the pages on the way.
        if (drawerProgress() >= 0.999f && editMode?.active == true) editMode?.exit()
        publishIcons()
        listener.onHomeSettled()
    }

    // ================================================================== state for the launcher and gesture nav

    val isIdle: Boolean get() = !pagerAnimating && !sheetAnimating && !depthAnimating && !arrivalAnimating && !arrivalHeld && (drag == Drag.NONE || drag == Drag.IGNORED) &&
        (drawer?.isIdle ?: true) && (spotlight?.isIdle ?: true) && editMode?.active != true && menu?.isShowing != true && alert?.isShowing != true &&
        picker?.isOpen != true && !externalTouch && clocks.none { it.animating } && widgetViews().none { it.resizing } &&
        !dev.launcher.app.theme.Appearance.changing && rebuildAnim == null

    // ---- arrival: home after unlock, and after a cold start (boot, update, crash)
    //
    // iOS's unlock: home zooms out into place. Every element (icons, widgets, the Search pill, the dock) starts as if the
    // whole home were seen a little closer, larger and spread out from the screen's centre, and they settle back together
    // as one camera pulling back. No fade: the elements are there from the first frame (on a cold start they come up with
    // the wallpaper from black). Nothing waits for it: the items are tappable from their first frame.
    private var arrivalAnimating = false
    private var arrivalStart = 0L
    private var arrivalT = 0.0
    private var arrivalLast = 0L
    private var pendingArrival: Boolean? = null
    /** An element and where its pivot is in home's coordinates (it is scaled about the screen's centre through it). */
    private class Arriving(val v: View, val px: Float, val py: Float)
    private val arriving = ArrayList<Arriving>()
    private var arrivalSpring: Mover? = null
    private var arrivalWallpaper: Mover? = null
    private var arrivalCold = false
    private var arrivalHeld = false

    /**
     * Puts home into the arrival's first frame (zoomed in) without playing it: from the moment the screen starts going off,
     * so whatever frame of home the unlock reveals first is already the arrival's. [animate]: eased in on `motion.arrival.hold` (the
     * screen is fading out: home zooms in as it goes instead of jumping); else at once.
     */
    fun holdArrival(animate: Boolean = false) {
        if (arrivalAnimating || arrivalHeld) return
        if (!prepareArrival(cold = false, log = false)) return
        arrivalHeld = true
        if (animate) {
            holdStart = System.nanoTime()
            holdTiming = dev.launcher.app.motion.CurveTiming(dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.ARRIVAL_HOLD))
            Choreographer.getInstance().postFrameCallback(holdFrame)
        } else applyArrival(0.0)
        AppLog.log("[home] arrival held until home is seen (${if (animate) "the screen is going off" else "screen off or lock screen up"})")
    }

    private var holdStart = 0L
    private var holdTiming: dev.launcher.app.motion.CurveTiming? = null

    /** The hold eased in: the zoom from rest to the arrival's first frame, the light with it. */
    private val holdFrame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!arrivalHeld || arrivalAnimating) return
            val s = arrivalSpring ?: return
            val ht = holdTiming
            val elapsed = ((now - holdStart) / 1e6).toFloat()
            val e = ht?.atMs(elapsed)?.coerceIn(0f, 1f) ?: 1f
            applyArrivalZoom(1f + (s.value(0.0) - 1f) * e)
            for (g in glassViews()) g.setLightAngle(225f - 60f * e)
            if (ht != null && elapsed < ht.durationMs) Choreographer.getInstance().postFrameCallback(this) else applyArrival(0.0)
        }
    }

    // A cold start: home takes the arrival's first frame (black, the items zoomed in and hidden) from its first layout, so it
    // never shows itself at rest first (it did, over black, while the wallpaper was read); the arrival waits for the
    // clock's numerals too (they are built in the background), 1 s at most.
    private var coldHoldWanted = false
    private var coldWaitSince = 0L
    private val coldRetry = Runnable { playArrival(cold = true) }

    /** From a cold start's first moment: hold the arrival's first frame until [playArrival] (now, or once laid out). */
    fun holdColdArrival() {
        coldHoldWanted = true
        holdCold()
        // Asked before home's first layout (onResume: no size yet, no pages), the hold waited for the next try: it is taken
        // right before the first frame that can have it, so no frame of a new process shows home at rest.
        if (!arrivalHeld && coldHoldWanted) viewTreeObserver.addOnPreDrawListener(coldHoldBeforeDraw)
    }

    private val coldHoldBeforeDraw = object : android.view.ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            if (!coldHoldWanted || arrivalHeld || arrivalAnimating) { post { viewTreeObserver.removeOnPreDrawListener(this) }; return true }
            holdCold()
            if (arrivalHeld) post { viewTreeObserver.removeOnPreDrawListener(this) }
            return true
        }
    }

    private fun holdCold() {
        if (!coldHoldWanted || arrivalHeld || arrivalAnimating || m == null || pages.isEmpty() || width == 0) return
        if (!prepareArrival(cold = true, log = false)) return
        arrivalHeld = true
        applyArrival(0.0)
    }

    /** Lets a held arrival go without playing it (home is being left before the unlock came). */
    fun releaseArrival() {
        if (!arrivalHeld) return
        arrivalHeld = false
        finishArrival()
    }

    /**
     * Plays the arrival now (from the held frame if there is one), or as soon as home has a layout ([cold]: from black).
     * False if home cannot play one right now (the reason is logged).
     */
    fun playArrival(cold: Boolean): Boolean {
        if (cold && m != null && pages.isNotEmpty()) {
            val now = android.os.SystemClock.uptimeMillis()
            if (coldWaitSince == 0L) coldWaitSince = now
            if (clocks.any { !it.numeralsReady } && now - coldWaitSince < 1000) {
                holdCold()
                removeCallbacks(coldRetry)
                postDelayed(coldRetry, 30)
                return true
            }
        }
        if (cold) { coldHoldWanted = false; removeCallbacks(coldRetry) }
        if (!arrivalHeld || cold) {
            if (arrivalAnimating) finishArrival()
            if (!prepareArrival(cold)) {
                if (cold && pendingArrival == null) coldBusy()
                return pendingArrival != null
            }
        }
        coldBusyTries = 0
        arrivalHeld = false
        arrivalStart = System.nanoTime()
        arrivalT = 0.0
        arrivalLast = arrivalStart
        arrivalAnimating = true
        applyArrival(0.0)
        Choreographer.getInstance().postFrameCallback(arrivalFrame)
        arrivalFrames = 0
        AppLog.log("[home] arrival (${if (cold) "cold start" else "unlock"}): ${arriving.size} items")
        return true
    }

    private var arrivalFrames = 0
    private var coldBusyTries = 0

    /**
     * A cold start's arrival found home busy: its held frame (black, the items hidden) must never stay. A card closing onto
     * home (the process started under an app, home's first showing is that close): the close is the way in, home is let go
     * at rest at once (it is under the card's picture of home, nobody sees it change). Anything else (its zoom settling): again
     * shortly, let go after a second. (It returned once, holding the frame for good: an empty home after an update.)
     */
    private fun coldBusy() {
        if (HomeBridge.homeCovered || ++coldBusyTries > 20) {
            coldBusyTries = 0
            if (arrivalHeld) { arrivalHeld = false; finishArrival() }
            AppLog.log("[home] arrival (cold start) dropped: home busy, at rest")
            return
        }
        removeCallbacks(coldRetry)
        postDelayed(coldRetry, 50)
    }

    /** Collects what arrives and sets up the springs; false if home cannot play one now (deferred when it has no layout). */
    private fun prepareArrival(cold: Boolean, log: Boolean = true): Boolean {
        val metrics = m
        if (metrics == null || pages.isEmpty() || width == 0) { pendingArrival = cold; return false }
        val busy = when {
            drawerProgress() > 0f -> "the App Library is open"
            spotlight?.isOpen == true -> "Spotlight is open"
            menu?.isShowing == true -> "a menu is open"
            picker?.isOpen == true -> "the widget gallery is open"
            editMode?.active == true -> "editing"
            depthAnimating -> "home's zoom is running"
            HomeBridge.homeCovered -> "a card covers home"
            else -> null
        }
        if (busy != null) { if (log) AppLog.log("[home] arrival not now: $busy"); return false }
        val page = pages.getOrNull(pos.roundToInt()) ?: return false
        val mp = Motion.profile
        arriving.clear()
        // Pivots in home's coordinates (fg's: pages, dock and pill are its children; a page's items are the page's).
        for (v in page.itemViews()) arriving += Arriving(v, page.translationX + v.left + v.pivotX, page.top + v.top + v.pivotY)
        for (v in listOfNotNull(indicator, dock, dockShadow)) arriving += Arriving(v, v.left + v.pivotX, v.top + v.pivotY)
        arrivalCold = cold
        // One zoom for everything, from a little closer to at rest; it starts moving on its first frame.
        arrivalSpring = mp.arrival.mover().apply { start(mp.arrivalZoom, -0.4f, 1f) }
        // The wallpaper settles from a zoom only on a cold start: after an unlock the system has already shown it at rest
        // on the lock screen, and zooming it again played the zoom twice.
        arrivalWallpaper = if (cold) mp.arrivalWallpaper.mover().apply { start(mp.arrivalWallpaperZoom, 0f, 1f) } else null
        return true
    }

    private val arrivalFrame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!arrivalAnimating) return
            // The arrival's own time: real time, but at most [MAX_STEP_S] a frame. A display waking from the always-on display
            // (an unlock straight from screen-off) shows its first frames ~50 ms apart: on real time the zoom moved on unseen and
            // jumped (the S24's recordings); now it stretches a little there instead, and runs on real time at 120 Hz.
            arrivalT += (maxOf(0L, now - arrivalLast) / 1e9).coerceAtMost(MAX_STEP_S)
            arrivalLast = maxOf(arrivalLast, now)
            val t = arrivalT
            arrivalFrames++
            applyArrival(t)
            val s = arrivalSpring ?: return
            if (s.settled(t, 0.0005f) && (arrivalWallpaper?.settled(t, 0.0005f) != false)) finishArrival()
            else Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // The strip's horizontal shift of the dock and pill (applyPositions), and the arrival's current zoom.
    private var stripShift = 0f
    private var arrivalZ = 1f

    /** Each element scaled by [z] about the screen's centre: about its own pivot, moved out by (pivot - centre) x (z - 1). */
    private fun applyArrivalZoom(z: Float) {
        arrivalZ = z
        val ox = width / 2f
        val oy = height / 2f
        for (a in arriving) {
            val v = a.v
            val onStrip = v === dock || v === dockShadow || v === indicator
            v.scaleX = z; v.scaleY = z
            v.translationX = (a.px - ox) * (z - 1f) + (if (onStrip) stripShift else 0f)
            v.translationY = (a.py - oy) * (z - 1f)
        }
    }

    private fun applyArrival(t: Double) {
        val s = arrivalSpring ?: return
        applyArrivalZoom(s.value(t))
        val fade = if (arrivalCold) (t / 0.35).coerceIn(0.0, 1.0).toFloat() else 1f
        for (a in arriving) a.v.alpha = fade
        val wz = arrivalWallpaper?.value(t) ?: 1f
        wallpaperView.scaleX = wz; wallpaperView.scaleY = wz
        if (arrivalCold) wallpaperView.alpha = (t / 0.35).coerceIn(0.0, 1.0).toFloat()
        // The light travels around the glass (clock numerals, dock, pill) from the left to its resting top-left, as Apple's
        // material does on unlock, over the first 0.7 s (eased).
        val lk = (t / 0.7).coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }.toFloat()
        val angle = 165f + 60f * lk
        for (g in glassViews()) g.setLightAngle(angle)
    }

    private fun finishArrival() {
        if (arrivalAnimating) AppLog.log("[home] arrival ended after $arrivalFrames frames (${(System.nanoTime() - arrivalStart) / 1_000_000} ms)")
        for (a in arriving) { a.v.scaleX = 1f; a.v.scaleY = 1f; a.v.alpha = 1f; a.v.translationX = 0f; a.v.translationY = 0f }
        arriving.clear()
        arrivalZ = 1f
        wallpaperView.scaleX = 1f; wallpaperView.scaleY = 1f; wallpaperView.alpha = 1f
        for (g in glassViews()) g.setLightAngle(225f)
        arrivalAnimating = false
        arrivalHeld = false
        // The dock's and pill's positions on the strip (they follow the drawer's slide): applied again.
        applyPositions()
        publishIcons()
        listener.onHomeSettled()
    }

    // ---- depth: home receding behind an open app (iOS), run here once the real home is on screen

    private var depthSpring: Mover? = null
    private var depthStart = 0L
    private var depthTarget = 0f
    private var depthAnimating = false

    /** Follows gesture nav's depth curve exactly (same curve and start time). */
    fun animateDepth(from: Float, to: Float, velocity: Float, curve: Curve, startNanos: Long) {
        depthSpring = curve.mover().apply { start(from, velocity, to) }
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
        // The glass on the wallpaper (dock, Search pill, widgets) keeps showing exactly what is behind it while home zooms:
        // the wallpaper zooms less than the glass. Only those small views are redrawn (inside home's GPU layer, a redraw of a
        // child only re-renders its own area).
        val k = cz / wz
        if (k != dev.launcher.app.GlassDepth.k) {
            dev.launcher.app.GlassDepth.k = k
            dev.launcher.app.GlassDepth.cx = width / 2f
            dev.launcher.app.GlassDepth.cy = height / 2f
            if (!HomeBridge.homeCovered) for (g in glassViews()) g.invalidate()
        }
        // The blur the picture of home behind the cards shows at this depth (GestureNav: a blurred quarter-size copy, which
        // looks blurrier than this blur at the same radius: DepthBlur), so taking over from it changes nothing; skipped while
        // that picture covers home (the work would be invisible).
        if (Build.VERSION.SDK_INT >= 31) {
            val density = resources.displayMetrics.density
            val r = if (HomeBridge.homeCovered) 0f else DepthBlur.homeRadius(d.coerceIn(0f, 1f) * mp.homeDepthBlur * density, density)
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
        if (alert?.isShowing == true) { alert?.cancel(); return }
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
    // The very view that was hidden: the published copy for a package can change meanwhile (a page turned, the dock), and
    // unhiding "the icon of pkg" then left the hidden one hidden until something else rebound it.
    private var hiddenView: IconView? = null

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
        // Home's pages and dock show badges; the App Library's icons do not (a card from or into one shows none).
        HomeBridge.setVisibleIcons(map, if (dp <= 0.001f) views.keys.toSet() else emptySet())
    }

    /**
     * Hides the icon a card flies into or out of (null = show all): only the icon's image, its label stays (as on iOS), and
     * the view stays tappable (reopening an app while its card is still closing).
     */
    fun setHiddenPkg(pkg: String?) {
        if (pkg == hiddenPkg) return
        hiddenView?.iconHidden = false
        hiddenView = null
        hiddenPkg = pkg
        pkg?.let { p -> published[p]?.let { v -> v.iconHidden = true; hiddenView = v } }
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

    /**
     * The App Library's content changed: drawn ahead once more when home is idle. The first warm-up after a start ran before
     * its apps and icons had arrived (0.9 s after the start), and the first swipe to it recorded and measured everything
     * inside its first frame (11 ms of the main thread, S24).
     */
    override fun onDrawerContentChanged() {
        if (drawerProgress() > 0f) return
        libraryTries = 0
        removeCallbacks(warmLibraryWhenIdle)
        postDelayed(warmLibraryWhenIdle, LIBRARY_WARM_MS)
    }

    private var backgroundCovered = false

    override fun setBackgroundCovered(covered: Boolean) {
        if (covered == backgroundCovered) return
        backgroundCovered = covered
        applyPositions()
    }

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
        // Whether home looks different now: the pictures of it a close shows behind its card are then recorded again (taken
        // with a menu or edit mode on, the close ended on a home that no longer showed them: Matheesha, Change Wallpaper).
        var changed = menu?.visibility == View.VISIBLE || picker?.isOpen == true || alert?.visibility == View.VISIBLE
        alert?.dismissNow()
        menu?.dismissNow(runClosed = true)
        picker?.closeNow()
        if (editMode?.dragging == true) editMode?.externalUp()
        externalTouch = false
        pendingExternal = null
        // A widget's setup screen (adding it from the gallery) is part of editing: home comes back still editing, as iOS.
        if (widgets?.busy != true && editMode?.active == true) { editMode?.exit(now = true); changed = true }
        spotlight?.takeIf { it.visibility == View.VISIBLE }?.let { it.closeNow(); changed = true }
        if (drawer?.endSearchNow() == true) changed = true
        return changed
    }

    /**
     * A swipe up on the gesture bar while home is in front. As on iOS it first closes what is on top (a menu, the widget
     * gallery, edit mode, Spotlight); with nothing on top it does what the Home button does: back to the first page, the
     * App Library and its search closed.
     */
    /** A menu, the widget gallery, edit mode or Spotlight is open (see HomeBridge.hasOnTop; read from gesture nav's thread). */
    fun hasOnTop(): Boolean = menu?.isShowing == true || picker?.isOpen == true || editMode?.active == true || spotlight?.isOpen == true

    /** One of home's pages shows as it rests: no App Library or drawer, nothing on top. */
    fun showsPage(): Boolean = m != null && pages.isNotEmpty() && drawerProgress() == 0f && sheet == 0f && !hasOnTop()

    fun onHomeSwipeUp() {
        // An open App Library folder is on top too: the swipe closes it, as iOS closes an expanded category.
        if (drawerProgress() > 0.5f && drawer?.closeTop() == true) return
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

    // The gallery opened from a menu: home stays as blurred as the menu had it until the rising sheet blurs it more (the
    // menu's blur used to fall faster than the sheet's rose: blurred, sharp, blurred again).
    private var blurHold = 0f

    /** How blurred home is now behind a menu or the gallery (0..1 of the menu blur). */
    private fun sceneBlurK() = maxOf(menuK, pickerK, blurHold)

    private fun applySceneBlur() {
        if (Build.VERSION.SDK_INT < 31) return
        if (blurHold > 0f && pickerK >= blurHold) blurHold = 0f
        editMode?.jigglePaused = sceneBlurK() > 0.01f
        val k = sceneBlurK()
        sceneBlur.set(k, k * Motion.profile.menuBlur * (m?.u ?: 0f))
    }

    // The widget gallery drawn once off screen while home is idle after it was built ([WidgetPicker.warmUp]): never while
    // anything moves or home is not in front (the GPU's render thread is busy with it for a moment).
    private var warmTries = 0
    private val warmPicker = object : Runnable {
        override fun run() {
            val pk = picker ?: return
            val w = widgets ?: return
            if (!isIdle || !hasWindowFocus() || HomeBridge.homeCovered) { if (++warmTries < 10) postDelayed(this, 2000L); return }
            Thread {
                val list = try { w.apps() } catch (_: Throwable) { emptyList() }
                post {
                    if (picker === pk && isIdle && hasWindowFocus() && !HomeBridge.homeCovered) {
                        pk.warmUp(list)
                        // The new look's reveal too (its first showing compiled its shader inside a frame: 117 ms on the S24).
                        if (Build.VERSION.SDK_INT >= 33) ThemeReveal.warmUp(resources.displayMetrics.density * REVEAL_CELL_DP)
                        // And the settings' curve graphs (their first opening: 99 ms of compiling inside its frame).
                        dev.launcher.app.settings.MotionWarmUp.warmUp(m?.u ?: 0f)
                        // And the cards' transition styles (the first bubble after an update: a 76 ms frame).
                        dev.launcher.app.CardStyleWarmUp.warmUp()
                        // And home's menus (the first edit menu after an update: 46 ms of compiling in its first frame), and
                        // blurs at every radius a menu's or a close's ramps through.
                        warmMenu()
                        dev.launcher.app.design.GpuWarmUp.blurLadder((m?.u ?: 3f) * 80f)
                    } else if (++warmTries < 10) postDelayed(this, 2000L)
                }
            }.start()
        }
    }

    /**
     * The App Library and the backdrop under it drawn once off screen (while closed they are laid out, not drawn): their
     * display lists recorded and what they draw (icons, glass) on the GPU before the first swipe to them.
     */
    private var libraryTries = 0

    private val warmLibraryWhenIdle = object : Runnable {
        override fun run() {
            // (Waiting as long as it takes, more slowly after a while: home may stay behind the settings for minutes.)
            if (!isIdle || !hasWindowFocus() || HomeBridge.homeCovered || !dev.launcher.app.GestureNav.idle) {
                postDelayed(this, if (++libraryTries < 20) LIBRARY_WARM_MS else 4 * LIBRARY_WARM_MS)
                return
            }
            warmLibrary()
        }
    }

    /**
     * The apps' icons back on the GPU before they are next seen (home came back to the front): the renderer lets textures go
     * that its frames have not used for a while, and after an app had been open the first swipe to the App Library uploaded
     * ~80 icons inside its first frame (prepareTree 6-10 ms on the S24). [android.graphics.Bitmap.prepareToDraw] uploads the
     * ones that left, between frames; the others cost nothing. Once home is idle.
     */
    fun warmIconTextures() {
        iconWarmTries = 0
        removeCallbacks(iconTextures)
        postDelayed(iconTextures, ICON_WARM_MS)
    }

    private var iconWarmTries = 0

    private val iconTextures: Runnable = object : Runnable {
        override fun run() {
            if (m == null) return
            if (!isIdle || !hasWindowFocus() || HomeBridge.homeCovered || !dev.launcher.app.GestureNav.idle) {
                if (++iconWarmTries < 20) postDelayed(this, ICON_WARM_MS)
                return
            }
            iconWarmNext = 0
            postOnAnimation(iconTexturesStep)
        }
    }

    private var iconWarmNext = 0

    // A dozen a frame (each is a task of the render thread, an upload if it left the GPU: all at once, a gesture starting
    // then waited behind them); stops if home is touched or moves.
    private val iconTexturesStep: Runnable = object : Runnable {
        override fun run() {
            val metrics = m ?: return
            if (HomeBridge.touching || !isIdle) return
            val all = Apps.all
            val end = minOf(all.size, iconWarmNext + ICONS_PER_FRAME)
            for (i in iconWarmNext until end) Icons.cached(all[i], metrics.iconSize)?.prepareToDraw()
            iconWarmNext = end
            if (end < all.size) postOnAnimation(this)
        }
    }

    init {
        // Off-screen renders wait while home is touched or a gesture runs (they hold the render thread for a moment).
        dev.launcher.app.design.GpuWarmUp.quiet = { !HomeBridge.touching && dev.launcher.app.GestureNav.idle }
    }

    /** A menu made only to be drawn once off screen, open ([ContextMenuView.warmUpNode]). */
    private fun warmMenu() {
        val metrics = m ?: return
        if (width == 0 || height == 0) return
        val mv = ContextMenuView(context, metrics, {}, { c -> scene.draw(c) })
        mv.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        mv.layout(0, 0, width, height)
        val items = listOf(
            dev.launcher.app.components.MenuPainter.Item("Edit Home Screen", glyph = dev.launcher.app.components.MenuPainter.Glyph.GRID),
            dev.launcher.app.components.MenuPainter.Item("App Info", glyph = dev.launcher.app.components.MenuPainter.Glyph.INFO),
            dev.launcher.app.components.MenuPainter.Item("Remove App", glyph = dev.launcher.app.components.MenuPainter.Glyph.MINUS, destructive = true))
        val node = mv.warmUpNode(items) ?: return
        dev.launcher.app.design.GpuWarmUp.render(node, width, height, "home's menus")
    }

    private fun warmLibrary() {
        val dv = drawer?.view ?: return
        if (dv.width == 0 || dv.height == 0 || drawerProgress() > 0f) return
        val node = android.graphics.RenderNode("library-warm-up").apply {
            setPosition(0, 0, dv.width, dv.height)
            val c = beginRecording()
            try { backdrop.draw(c); dv.draw(c); (dv as? dev.launcher.app.drawer.AppLibraryView)?.drawListForWarmUp(c) } finally { endRecording() }
        }
        dev.launcher.app.design.GpuWarmUp.render(node, dv.width, dv.height, "the App Library")
        // And recorded once in the window too, where it lies out of sight (beside the page): drawn into the warm-up's own
        // canvas, its views still recorded their window's display lists inside the first swipe to it (9 ms, S24). One frame
        // shown off the screen (nothing of it is on screen to draw), then hidden again; the lists stay.
        if (dv.visibility != View.VISIBLE && kotlin.math.abs(dv.translationX) >= width.toFloat()) {
            dv.visibility = View.VISIBLE
            // (The next frame draws it; hidden at the one after: an animation callback runs before its own frame's drawing.
            // Unless a swipe to it began meanwhile: then it shows by its own progress.)
            postOnAnimation {
                postOnAnimation {
                    if (drawerProgress() == 0f && kotlin.math.abs(dv.translationX) >= width.toFloat()) dv.visibility = View.INVISIBLE
                }
            }
        }
    }

    /**
     * A picture of [v] as it looks (an icon without its label), and its frame in home's coordinates; for an icon also as it
     * looks pressed ([pressedCopy]: the lifted icon turns from that into the clear one as the menu opens, never at once).
     */
    private fun liftedCopy(v: View): Pair<android.graphics.RenderNode, RectF> {
        pressedCopy = null
        if (v is IconView) {
            v.labelHidden = true
            pressedCopy = ContextMenuView.record(v.width, v.height) { c -> v.draw(c) }
            v.clearPress()
        }
        val node = ContextMenuView.record(v.width, v.height) { c -> if (v is WidgetFrameView) v.drawCard(c) else v.draw(c) }
        if (v is IconView) v.labelHidden = false
        return node to frameInHome(v)
    }

    /** The last [liftedCopy]'s icon as it looked pressed (null for a widget). */
    private var pressedCopy: android.graphics.RenderNode? = null

    /** The item a menu is open for: it fades out (its name with it) as the menu opens over it. */
    private var menuItem: View? = null

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
    private fun shortcutItems(e: AppEntry, frame: RectF): List<dev.launcher.app.components.MenuPainter.Item> {
        val la = context.getSystemService(android.content.pm.LauncherApps::class.java)
        val items = ArrayList<dev.launcher.app.components.MenuPainter.Item>()
        try {
            if (la.hasShortcutHostPermission()) {
                val q = android.content.pm.LauncherApps.ShortcutQuery().setPackage(e.pkg).setQueryFlags(
                    android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                        android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
                la.getShortcuts(q, e.user)?.sortedBy { it.rank }?.take(4)?.forEach { sc ->
                    val icon = try { la.getShortcutIconDrawable(sc, resources.displayMetrics.densityDpi) } catch (_: Throwable) { null }
                    items += dev.launcher.app.components.MenuPainter.Item((sc.shortLabel ?: sc.longLabel ?: sc.id).toString(), icon = icon) {
                        try { la.startShortcut(sc, android.graphics.Rect().also { frame.roundOut(it) }, null) } catch (t: Throwable) { AppLog.log("[home] shortcut failed: ${t.message}") }
                    }
                }
            }
        } catch (t: Throwable) { AppLog.log("[home] shortcuts unavailable: ${t.message}") }
        return items
    }

    /** Whether [e] can be deleted (iOS's "Delete App"): not a system app (Android cannot uninstall those), not this launcher. */
    private fun canDelete(e: AppEntry): Boolean {
        val system = try {
            (context.packageManager.getApplicationInfo(e.pkg, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (_: Throwable) { true }
        return !system && e.pkg != context.packageName
    }

    /** "Delete App": Android's own uninstall confirmation (deleted, the app leaves home with the layout's next reconcile). */
    private fun delete(e: AppEntry) {
        try {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_DELETE, android.net.Uri.parse("package:${e.pkg}"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) { AppLog.log("[home] cannot delete ${e.pkg}: ${t.message}") }
    }

    /** The App Library's "Delete App" (straight to Android's own confirmation); none for an app that cannot be deleted. */
    private fun deleteItem(e: AppEntry): dev.launcher.app.components.MenuPainter.Item? =
        if (!canDelete(e)) null
        else dev.launcher.app.components.MenuPainter.Item("Delete App", glyph = dev.launcher.app.components.MenuPainter.Glyph.TRASH, destructive = true) { delete(e) }

    private var alert: AlertView? = null

    /**
     * Asks before [v] leaves home, as iOS does (edit mode's minus, the menu's Remove App and Remove Widget): an app's icon
     * "Remove “Maps”?" with Delete App (where it can be deleted), Remove from Home Screen (it stays in the App
     * Library) and Cancel; a widget "Remove “Calendar”?" with Remove and Cancel. What it cannot ask about leaves at once.
     */
    fun askToRemove(v: View) {
        val a = alert
        val remove = { editMode?.removeFromHome(v); Unit }
        if (a == null) { remove(); return }
        val e = (v as? IconView)?.entry
        if (e != null) {
            val buttons = ArrayList<AlertView.Button>()
            if (canDelete(e)) buttons += AlertView.Button("Delete App", AlertView.Role.DESTRUCTIVE, afterClose = true) { delete(e) }
            buttons += AlertView.Button("Remove from Home Screen") { remove() }
            buttons += AlertView.Button("Cancel", AlertView.Role.CANCEL)
            a.show("Remove \u201c${e.label}\u201d?", "Removing from Home Screen will keep the app in your App Library.", buttons)
            return
        }
        val widget = pages.firstNotNullOfOrNull { it.itemOf(v) } as? HomeItem.Widget
        if (widget != null) {
            a.show("Remove \u201c${widgetName(widget)}\u201d?", "Removing this widget will not delete any data.", listOf(
                AlertView.Button("Remove", AlertView.Role.DESTRUCTIVE) { remove() },
                AlertView.Button("Cancel", AlertView.Role.CANCEL)))
            return
        }
        remove()
    }

    /** What a widget is called where it is asked about: its app's name, or the clock's. */
    private fun widgetName(w: HomeItem.Widget): String = when (w.kind) {
        HomeItem.Widget.APP -> w.provider?.let { android.content.ComponentName.unflattenFromString(it)?.packageName }?.let { Apps.forPkg(it)?.label } ?: "Widget"
        "clock" -> "Clock"
        else -> "Widget"
    }

    private fun appInfoItem(e: AppEntry, frame: RectF) = dev.launcher.app.components.MenuPainter.Item("App Info", glyph = dev.launcher.app.components.MenuPainter.Glyph.INFO) {
        val la = context.getSystemService(android.content.pm.LauncherApps::class.java)
        try { la.startAppDetailsActivity(e.component, e.user, android.graphics.Rect().also { frame.roundOut(it) }, null) } catch (t: Throwable) { AppLog.log("[home] app info failed: ${t.message}") }
    }

    private fun onIconLongPress(v: IconView, e: AppEntry) {
        removeCallbacks(emptyLongPress)
        if (editMode?.active == true) return
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val (pic, frame) = liftedCopy(v)
        val items = ArrayList(shortcutItems(e, frame))
        items += dev.launcher.app.components.MenuPainter.Item("Edit Home Screen", glyph = dev.launcher.app.components.MenuPainter.Glyph.GRID) { editMode?.enter() }
        items += dev.launcher.app.components.MenuPainter.Item("Remove App", glyph = dev.launcher.app.components.MenuPainter.Glyph.MINUS, destructive = true, asks = true) {
            askToRemove(v)
        }
        items += appInfoItem(e, frame)
        showMenu(v, pic, frame, items)
    }

    private fun showMenu(v: View, pic: android.graphics.RenderNode, frame: RectF, items: List<dev.launcher.app.components.MenuPainter.Item>) {
        val mv = menu ?: return
        // The lifted copy draws the item above the blur; the real one (blurred, with its label) fades out meanwhile (with the
        // menu's progress), and its name comes back fading in once the copy has settled into its place.
        menuItem = v
        mv.show(pic, frame, items, pressedCopy)
        val icon = v as? IconView
        val widget = v as? WidgetFrameView
        var shown = false
        // The item back in view, its name fading in (at the end of the close, or early when the item changes in place).
        val showItem = {
            if (!shown) {
                shown = true
                if (menuItem === v) menuItem = null
                v.alpha = 1f
                if (icon != null && cfg.showLabels) { icon.setLabelShown(false, animate = false); icon.setLabelShown(true, animate = true) }
                if (widget != null && cfg.showWidgetLabels) { widget.setLabelShown(false, animate = false); widget.setLabelShown(true, animate = true) }
            }
        }
        // Nothing is changed while the menu is open: a drag that takes the item over drops these callbacks.
        mv.onHandBack = { showItem() }
        mv.onClosed = { showItem() }
        // A widget given a new size or style here: the copy fading into it shows it as it is at each frame (a new view for a
        // new style: the still copy then, the old style fading out over the new).
        if (widget != null) {
            val size = android.graphics.PointF()
            mv.liveLifted = ContextMenuView.LiveCopy({ area, pivot ->
                if (!widget.isAttachedToWindow || widget.width == 0) false else {
                    val at = frameInHome(widget)
                    widget.drawnSize(size)
                    area.set(at.left, at.top, at.left + size.x, at.top + size.y)
                    widget.shownCenter(pivot)
                    pivot.offset(at.left, at.top)
                    true
                }
            }) { c -> widget.drawCard(c) }
        }
        pendingDragView = v
    }

    private fun onWidgetLongPress(v: View) {
        removeCallbacks(emptyLongPress)
        if (editMode?.active == true) return
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val (pic, frame) = liftedCopy(v)
        val item = pages.firstNotNullOfOrNull { it.itemOf(v) } as? HomeItem.Widget
        val items = ArrayList<dev.launcher.app.components.MenuPainter.Item>()
        var sizeRow: dev.launcher.app.components.MenuPainter.Item? = null
        if (item != null) {
            // iOS 27: its own settings first, the sizes it comes in as the menu's last row.
            val sizes = editHost.widgetSizes(item)
            val current = sizes.firstOrNull { it.spanX == item.spanX && it.spanY == item.spanY }
            if (sizes.size > 1) sizeRow = dev.launcher.app.components.MenuPainter.Item("Size", sizes = sizes, current = current, onSize = { s -> editMode?.resize(item, s) })
            if (item.kind == HomeItem.Widget.APP && widgets?.isConfigurable(item.id) == true)
                items += dev.launcher.app.components.MenuPainter.Item("Edit Widget", glyph = dev.launcher.app.components.MenuPainter.Glyph.SLIDERS) { widgets?.reconfigure(item.id) }
            if (item.kind == "clock") {
                val solid = item.style == "solid"
                items += dev.launcher.app.components.MenuPainter.Item(if (solid) "Glass Style" else "Solid Style", glyph = dev.launcher.app.components.MenuPainter.Glyph.STYLE) {
                    menu?.handBack()
                    editMode?.restyle(item, if (solid) null else "solid")
                }
            }
            if (item.kind == HomeItem.Widget.APP) {
                // A platter of the theme's glass behind a widget that comes without a background of its own.
                val glass = item.style == HomeItem.Widget.GLASS
                items += dev.launcher.app.components.MenuPainter.Item(if (glass) "No Background" else "Glass Background", glyph = dev.launcher.app.components.MenuPainter.Glyph.STYLE) {
                    menu?.handBack()
                    editMode?.restyle(item, if (glass) null else HomeItem.Widget.GLASS)
                }
            }
        }
        items += dev.launcher.app.components.MenuPainter.Item(if (cfg.showWidgetLabels) "Hide Widget Names" else "Show Widget Names", glyph = dev.launcher.app.components.MenuPainter.Glyph.LABEL) {
            setWidgetLabelsShown(!cfg.showWidgetLabels)
        }
        items += dev.launcher.app.components.MenuPainter.Item("Edit Home Screen", glyph = dev.launcher.app.components.MenuPainter.Glyph.GRID) { editMode?.enter() }
        items += dev.launcher.app.components.MenuPainter.Item("Remove Widget", glyph = dev.launcher.app.components.MenuPainter.Glyph.MINUS, destructive = true, asks = true) {
            askToRemove(v)
        }
        sizeRow?.let { items += it }
        showMenu(v, pic, frame, items)
    }

    /** Widget names under the widgets, on or off (saved; the names fade). */
    fun setWidgetLabelsShown(shown: Boolean) {
        if (cfg.showWidgetLabels == shown) return
        cfg = cfg.copy(showWidgetLabels = shown)
        cfg.save(context)
        for (w in widgetViews()) w.setLabelShown(shown, animate = true)
        postDelayed(afterEdit, 400)
    }

    /** App names under the icons on the pages, on or off (saved; the names fade). iOS 18's large-icon look when off. */
    fun setAppLabelsShown(shown: Boolean) {
        if (cfg.showLabels == shown) return
        cfg = cfg.copy(showLabels = shown)
        cfg.save(context)
        for (p in pages) for (v in p.icons()) v.setLabelShown(shown, animate = true)
        postDelayed(afterEdit, 400)
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
        val pic = ContextMenuView.record(frame.width().toInt(), frame.height().toInt()) { c ->
            Icons.cached(e, metrics.iconSize)?.let { b ->
                c.drawBitmap(b, null, RectF(0f, 0f, frame.width(), frame.height()), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            }
        }
        val items = ArrayList(shortcutItems(e, frame))
        val onHome = isOnHome(e.key)
        if (!onHome) items += dev.launcher.app.components.MenuPainter.Item("Add to Home Screen", glyph = dev.launcher.app.components.MenuPainter.Glyph.PLUS) { addAppToHome(e) }
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
        val icon = appIcon(e, metrics, label = true)
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
        val items = arrayListOf(
            dev.launcher.app.components.MenuPainter.Item("Add Widget", glyph = dev.launcher.app.components.MenuPainter.Glyph.PLUS) { openWidgetPicker() },
            // Android's convenience (iOS changes it from the lock screen): the system's own picker, out of the Edit button at
            // once, over the menu as it is (Matheesha: it need not wait for the menu's close); home out of sight closes it.
            dev.launcher.app.components.MenuPainter.Item("Change Wallpaper", glyph = dev.launcher.app.components.MenuPainter.Glyph.WALLPAPER,
                keepsOpen = true) { listener.openWallpaperPicker(RectF(button)) },
            dev.launcher.app.components.MenuPainter.Item(if (cfg.showLabels) "Hide App Names" else "Show App Names", glyph = dev.launcher.app.components.MenuPainter.Glyph.LABEL) { setAppLabelsShown(!cfg.showLabels) },
        )
        items += dev.launcher.app.components.MenuPainter.Item(if (cfg.showWidgetLabels) "Hide Widget Names" else "Show Widget Names", glyph = dev.launcher.app.components.MenuPainter.Glyph.LABEL) { setWidgetLabelsShown(!cfg.showWidgetLabels) }
        // Light or dark appearance (iOS: Customize); the menu stays open and everything crossfades behind it.
        val modes = dev.launcher.app.theme.Appearance.Mode.entries
        items += dev.launcher.app.components.MenuPainter.Item("Appearance", choices = modes.map { it.title }, chosen = modes.indexOf(dev.launcher.app.theme.Appearance.mode),
            onChoice = { i -> dev.launcher.app.theme.Appearance.setMode(context, modes[i]) })
        // The launcher's own settings (docs/PLAN_SETTINGS.md): editing ends with the menu; once home is at rest it slides into
        // the settings' picture of it (Matheesha: not a separate app opening).
        items += dev.launcher.app.components.MenuPainter.Item("Home Settings", glyph = dev.launcher.app.components.MenuPainter.Glyph.SLIDERS,
            afterClose = { whenAtRest { listener.openSettings() } }) { editMode?.exit() }
        menu?.show((editBar as? EditMode.Bar)?.editButtonNode(), button, items)
        menu?.liftedFollows = editBar   // editing may end with the menu (Home Settings): the button's copy leaves with the bar
    }

    /**
     * Runs [then] (after the frame) once home shows at rest: no menu, edit mode's bar gone, no icon still wiggling, nothing
     * moving; checked every frame, and after [maxMs] whatever it shows. A picture of home is taken next (it must show home
     * as it will stay).
     */
    private fun whenAtRest(maxMs: Long = 700L, then: () -> Unit) {
        val asked = android.os.SystemClock.uptimeMillis()
        val deadline = asked + maxMs
        val check = object : Runnable {
            override fun run() {
                when {
                    atRest() -> post(then)
                    android.os.SystemClock.uptimeMillis() <= deadline -> postOnAnimation(this)
                    // Touched again meanwhile and still not at rest (a swipe under way, edit mode again): the finger's way wins.
                    touchedAt > asked -> dev.launcher.app.AppLog.log("[home] not opened: home was touched meanwhile and is not at rest")
                    else -> post(then)
                }
            }
        }
        postOnAnimation(check)
    }

    private fun atRest(): Boolean {
        if (menu?.closedEnough == false || editMode?.active == true || sceneBlurK() > 0.005f || !isIdle) return false
        // A clock changing the minute (crossfading its numerals): home pictures itself only once it has.
        if (clocks.any { it.animating }) return false
        // The edit bar fading up and away (its last few per cent are not seen), the icons coming to rest from their wiggle.
        val bar = editBar
        if (bar != null && bar.visibility == View.VISIBLE && bar.alpha > 0.01f) return false
        val page = pages.getOrNull(pos.roundToInt())
        return page?.itemViews()?.all { it.rotation == 0f } != false && dock?.icons()?.all { it.rotation == 0f } != false
    }

    fun openWidgetPicker() {
        blurHold = menuK
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
        override fun editingChanged(active: Boolean, now: Boolean) {
            if (active) widgets?.prewarmApps()   // "Add Widget" is a tap away
            (editBar as? EditMode.Bar)?.let { if (active) it.show(now) else it.hide(now) }
            if (now) indicator?.setEditingNow(active) else indicator?.editing = active
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
        override fun addClockWidget(size: WidgetSize) {
            val metrics = m ?: return
            editMode?.addWidget(HomeItem.Widget("clock", size.spanX.coerceAtMost(metrics.cfg.columns), size.spanY.coerceAtMost(metrics.cfg.rows)))
        }
        override fun addAppWidget(info: android.appwidget.AppWidgetProviderInfo, size: WidgetSize) {
            val w = widgets ?: return
            w.add(info, size) { item -> if (item != null) editMode?.addWidget(item) }
        }
        override fun pickerProgress(k: Float) {
            // Closing: the hold lets go, home sharpens with the sheet. (Not on a 0 that opening reports before the sheet
            // starts rising: that cleared the hold at once.)
            if (k < pickerK) blurHold = 0f
            pickerK = k
            applySceneBlur()
        }
        override fun sceneBlur(): Float = sceneBlurK()
        override fun drawBehindSheet(c: android.graphics.Canvas) = scene.draw(c)
        override fun wallpaper(): Wallpaper? = this@HomeScreen.wallpaper
    }

    // ================================================================== SpotlightView.Host

    override fun launchFromSpotlight(e: AppEntry, iconOnScreen: RectF) = launch(e, iconOnScreen)

    override fun spotlightMoved() = updateStatusDark()

    override fun spotlightSettled() {
        updateStatusDark()
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
        /** Inset changes up to this (px) leave home as it is (see onApplyWindowInsets). */
        private const val INSET_SLOP_PX = 2
        /** How small an item starts as the new look reaches it, before it springs into place. */
        const val SETTLE_FROM = 0.9f
        /** The most the arrival advances in one frame (s): a 60 Hz frame. */
        const val MAX_STEP_S = 1.0 / 60.0
        /** Sparkle grid and front wobble scale of the wallpaper reveal, shared by wallpaper and glass. */
        const val REVEAL_CELL_DP = 7f
        /** How long after home is built the widget gallery is drawn off screen once, if home is idle then (ms). */
        const val WARM_UP_DELAY_MS = 6000L
        /** How soon after a build, and how often again, home is asked whether it is idle to warm the App Library (ms). */
        const val LIBRARY_WARM_MS = 500L
        /** How long after home comes back its icons are put back on the GPU (and how often it asks again while not idle; ms). */
        const val ICON_WARM_MS = 600L
        const val ICONS_PER_FRAME = 12
        /** After a change of the phone's palette, how soon the system is asked whether it is done with it (ms). */
        const val WIDGETS_SETTLE_MS = 400L
        /** Apps' widgets follow the phone's palette on a home left untouched this long (ms)... */
        const val WIDGETS_UNTOUCHED_MS = 800L
        /** ...asked again this often (ms)... */
        const val WIDGETS_RETRY_MS = 300L
        /** ...and after this long anyway (ms). */
        const val WIDGETS_MAX_WAIT_MS = 10_000L
    }
}
