package dev.launcher.app

import dev.launcher.app.motion.AppStyle
import dev.launcher.app.motion.AppStyles
import dev.launcher.app.motion.Box

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.VelocityTracker
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import dev.launcher.app.apps.Icons
import dev.launcher.app.apps.SplashColors
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.Mover
import dev.launcher.app.motion.mover
import dev.launcher.app.motion.timed

/**
 * Our bottom-edge gesture navigation and app launch/close animations.
 *
 * Windows (created once, kept while gesture nav is on): a full-display, non-touchable card window (picture of home,
 * previous-app card, current-app card), and above it the touchable gesture strip, so the pill is always visible and the
 * finger always lands on the strip, whatever is animating.
 *
 * Interruptible: a touch on the bar during a launch, a switch or a cancelled close grabs the card where it is ([takeOver]);
 * a closing card is not grabbed (Matheesha's choice: bar touches during a close are ignored). Tapping the icon of the app
 * whose card is on screen reverses it; tapping another icon replaces it; touching home moves a closing card aside.
 * Springs keep their velocity across every hand-over.
 *
 * - Up: HOME. The app's card shrinks with the finger (ease-out) over our recorded picture of home; the app itself stays in
 *   front and running until the gesture commits. Commit: real home starts under the picture, springs carry the finger's
 *   velocity into the app's icon, the picture gives way to the real home once that has drawn. Cancel: springs back.
 * - Sideways: SWITCH, horizontal only; the previous app's card slides in beside the current one, the real switch happens
 *   behind the cards on release.
 * - [launchApp]: LAUNCH. A card grows out of the icon over the picture while the app starts underneath.
 *
 * Windows are TYPE_ACCESSIBILITY_OVERLAY (through [NavAccessibilityService]): Settings and setHideOverlayWindows cannot
 * hide them. Stock gestures are only blocked while that service is connected ([ready]) and our flags are held by the live
 * shell service. All of this runs on its own UI thread ([nav]); binder work runs on two worker threads (task list and
 * snapshots separately, so a slow snapshot never delays the next gesture's task lookup).
 */
object GestureNav {
    private lateinit var app: LauncherApp
    private val navThread = HandlerThread("nav-ui", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val nav = Handler(navThread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val tasksIo = Executors.newSingleThreadExecutor()
    private val snapIo = Executors.newSingleThreadExecutor()
    private val cachedIo = Executors.newSingleThreadExecutor()
    private val deckIo = Executors.newSingleThreadExecutor()   // the App Switcher's pictures, as its cards come into view
    private val density get() = app.resources.displayMetrics.density
    private fun dp(v: Int) = v * density

    private var a11y: NavAccessibilityService? = null
    private var wm: WindowManager? = null

    /** Our windows can be shown (accessibility service connected). Gesture flags are only set while this is true. */
    @Volatile var ready = false
        private set
    @Volatile private var locked = false

    /** Set by HomeActivity (resumed = in front). */
    @Volatile var homeVisible = false
    /**
     * The app a card is bringing up now (a launch, a quick switch) until its window comes to the front, and a close into home
     * committed until home shows: what is behind the banners and the status bar changes with the card, not seconds later
     * when the window reports (the emulator showed a banner's wallpaper glass over a white app opening, and the bar's white
     * time over its white launch screen).
     */
    @Volatile private var bringing: String? = null
    @Volatile private var closingHome = false
    /** Each app's status bar tone as it last asked for it (true: black content), for its card as it opens. */
    private val appDark = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private class Task(val id: Int, val pkg: String, val cls: String? = null)
    // HOME_PULL: a swipe up on the home screen (no card); a hold opens the App Switcher, a release is the Home button.
    private enum class Phase { IDLE, DRAG_HOME, DRAG_SWITCH, ANIM, HOLD, SWITCHER, HOME_PULL }
    private enum class Anim { NONE, HOME_COMMIT, HOME_CANCEL, LAUNCH, SWITCH_COMMIT, SWITCH_CANCEL }

    // ------------------------------------------------------------------ windows (nav thread)
    private var strip: View? = null
    private var root: FrameLayout? = null
    private var backdrop: PreviewView? = null
    private var cur: CardView? = null
    private var prv: CardView? = null   // older app (left) during a switch
    private var nxt: CardView? = null   // newer app (right) during a switch
    private var land: CardView? = null  // a closing card still landing in its icon while another app opens ([handToLander])
    private var sw = 0f
    private var sh = 0f
    private var deviceRadius = 0f
    /** The display's rotation when it was last read (Surface.ROTATION_*): 1 and 3 are landscape. */
    private var displayRotation = 0

    // ------------------------------------------------------------------ state (nav thread)
    private var phase = Phase.IDLE
    private var anim = Anim.NONE
    /** Bumped on every hand-over; callbacks from an earlier phase compare it and do nothing. */
    private var gen = 0
    private var cardPkg: String? = null          // the app the current card shows
    private var homeStarted = false              // real home was brought to the front during this card's life
    private var homeRequestedAt = 0L
    private var hiddenIconPkg: String? = null
    private var pendingFresh = false             // touched down with no card yet; waiting to decide the direction
    private var grabbedFull = false              // took over a full-size card: direction still open
    private var pendingStart: Runnable? = null   // a launch whose app has not been started yet
    private var appStarted = false               // an app was started during this card session (home is not in front)

    // Finger
    private var downX = 0f
    private var downY = 0f
    private var vt: VelocityTracker? = null

    // HOME drag model: travel since the card was full size, finger anchor within the card (scaled with it)
    private var travel0 = 0f
    private var lastTravel = 0f
    private var anchorX = 0f
    private var anchorBottom = 0f
    // SWITCH drag model
    private var offset = 0f
    private var switchScale = 1f

    // Data for the gesture in progress, loaded on ACTION_DOWN by the workers and handed to nav
    @Volatile private var gestureId = 0
    @Volatile private var fg: Task? = null
    @Volatile private var prev: Task? = null
    private var fgFresh: Bitmap? = null
    private var fgFreshDone = false
    private var prvSnapshot: Bitmap? = null
    private var fgSnapMs = -1L
    private var dragStartedAt = 0L
    private var cardVisibleAfter = -1L
    private val icons = ConcurrentHashMap<String, Drawable>()
    /** Last image we have of each app (fresh or cached snapshot): lets a card appear at once, before a fresh one arrives. */
    private val images = ConcurrentHashMap<String, Bitmap>()
    private val imagesAt = ConcurrentHashMap<String, Long>()
    // When each app last stopped being the app in front, and when we last got the system's snapshot of it: the system takes
    // one as an app goes to the background, so one fetched after that is as good as it gets until the app is in front again.
    private val leftFrontAt = ConcurrentHashMap<String, Long>()
    private val systemPictureAt = ConcurrentHashMap<String, Long>()

    private fun remember(pkg: String, b: Bitmap, takenAt: Long = SystemClock.uptimeMillis()) {
        images[pkg] = b; imagesAt[pkg] = takenAt
        // Bounded (each picture is ~10 MB): the App Switcher reaches every recent app. The app in front keeps its own.
        while (images.size > IMAGES_MAX) {
            val oldest = imagesAt.entries.filter { it.key != lastFrontPkg && it.key != pkg }.minByOrNull { it.value }?.key ?: break
            images.remove(oldest); imagesAt.remove(oldest)
        }
    }

    // The task of the app in front, once looked up (a gesture's fresh picture can then start at the touch).
    @Volatile private var frontTask: Task? = null

    // When the app in front last changed what it shows (accessibility events): a picture taken before that is out of date.
    private val contentChangedAt = ConcurrentHashMap<String, Long>()

    /** Our latest picture of [pkg] was taken before its screen last changed (it would show the app as it was). */
    private fun keptIsStale(pkg: String?): Boolean = pkg != null && (contentChangedAt[pkg] ?: 0L) > (imagesAt[pkg] ?: 0L)

    /** From NavAccessibilityService: the window of [pkg] changed its content or scrolled. */
    fun onContentChanged(pkg: String?) {
        if (pkg == null || pkg != lastFrontPkg) return   // only the app in front (not our windows, not the keyboard)
        contentChangedAt[pkg] = SystemClock.uptimeMillis()
        nav.post { if (phase == Phase.SWITCHER) refreshSwitcherCard() else refreshSoon() }
    }

    /**
     * The App Switcher is open over the app the swipe started in, and that app changed its screen after its card's picture
     * was taken (often the last tap before the swipe, reported a moment late): a new picture for its card, once it settles.
     */
    private fun refreshSwitcherCard() {
        val t = fg ?: return
        if (homeStarted || !keptIsStale(t.pkg)) return
        nav.removeCallbacks(switcherRefresh)
        nav.postDelayed(switcherRefresh, SWITCHER_REFRESH_SETTLE_MS)
    }

    private val switcherRefresh = Runnable {
        val t = fg ?: return@Runnable
        val s = ShizukuLink.service ?: return@Runnable
        if (phase != Phase.SWITCHER) return@Runnable
        val g = gen
        snapIo.execute {
            val t0 = SystemClock.uptimeMillis()
            val b = try { snapshot(s, t.id, true) } catch (_: Throwable) { null } ?: return@execute
            nav.post {
                if (gen != g || phase != Phase.SWITCHER) return@post
                remember(t.pkg, b, t0)
                deck?.updateSnapshot(t.id, b)
                AppLog.log("[switcher] ${t.pkg.substringAfterLast('.')} changed its screen: its card was taken again")
            }
        }
    }

    // A new picture of the app in front once its screen has settled (KEEP_FRESH_SETTLE_MS after the last change), at most
    // every KEEP_FRESH_MIN_GAP_MS: a home swipe or the App Switcher then shows the app as it is, not as it was seconds ago.
    private var keptAt = 0L

    private fun refreshSoon() {
        if (phase != Phase.IDLE || homeVisible) return
        val now = SystemClock.uptimeMillis()
        // Never later than the regular refresh: a screen that keeps changing (a running timer) would otherwise put it off
        // for good.
        val delay = min(max(KEEP_FRESH_SETTLE_MS, keptAt + KEEP_FRESH_MIN_GAP_MS - now), max(0L, keptAt + KEEP_FRESH_MS - now))
        nav.removeCallbacks(keepFresh)
        nav.postDelayed(keepFresh, delay)
    }

    /**
     * A task's snapshot as a hardware bitmap, wrapped here around the buffer the shell sends: a Bitmap sent over Binder
     * arrived as a 10 MB software copy (read back from the GPU in the shell), which had to be uploaded to the GPU at its first
     * draw (5+ ms inside a gesture's first frames, traced on the S24). The old call is the fallback for a shell service from
     * another build.
     */
    private fun snapshot(s: IShellService, taskId: Int, fresh: Boolean): Bitmap? {
        val hb = try { s.taskSnapshotBuffer(taskId, fresh) } catch (_: Throwable) { null }
        if (hb != null) {
            return try { Bitmap.wrapHardwareBuffer(hb, null) } catch (_: Throwable) { null } finally { hb.close() }
        }
        return try { s.taskSnapshot(taskId, fresh) } catch (_: Throwable) { null }
    }

    /**
     * The system's reduced copy of a task's last picture (half size when it has to be read from storage; the full one when
     * the system still holds it), for the cards stacked in the App Switcher. Falls back to the full one (older service).
     */
    private fun snapshotLow(s: IShellService, taskId: Int): Bitmap? {
        val hb = try { s.taskSnapshotBufferLow(taskId) } catch (_: Throwable) { null } ?: return snapshot(s, taskId, false)
        return try { Bitmap.wrapHardwareBuffer(hb, null) } catch (_: Throwable) { null } finally { hb.close() }
    }

    /** An earlier image of [pkg], only if recent enough that showing it before the fresh one cannot look stale. */
    private fun recentImage(pkg: String?): Bitmap? {
        pkg ?: return null
        val at = imagesAt[pkg] ?: return null
        return if (SystemClock.uptimeMillis() - at < RECENT_MS) images[pkg] else null
    }

    /**
     * Keeps a picture of the app in front that is never older than [RECENT_MS], so a home gesture's card shows at once even
     * after the app has been open a while. Measured on the S24: a fresh capture at the start of a gesture takes ~30 ms right
     * after a launch but 70-130 ms after a minute in the app, and the card waited for it (~4-10 dropped frames). The fresh
     * capture still runs at every gesture and replaces this one when it arrives. Not while home is in front, the screen is
     * off, or cards are moving.
     */
    private var keepFreshLogs = 0

    // How long these captures take, from asking to having the picture (ms): summed up in the log every [KEPT_STATS_EVERY].
    private val keptTimes = ArrayList<Long>()

    private fun noteKeptCapture(ms: Long) = synchronized(keptTimes) {
        keptTimes += ms
        if (keptTimes.size < KEPT_STATS_EVERY) return@synchronized
        val t = keptTimes.sorted()
        AppLog.log("[nav] kept pictures, last ${t.size}: median ${t[t.size / 2]} ms, 90% within ${t[t.size * 9 / 10]} ms, longest ${t.last()} ms")
        keptTimes.clear()
    }

    private val keepFresh = object : Runnable {
        override fun run() {
            nav.removeCallbacks(this)
            val s = ShizukuLink.service
            val pkg = lastFrontPkg
            if (!ready || locked || homeVisible || s == null || pkg == null) return
            keptAt = SystemClock.uptimeMillis()
            // An app is in front: the card window stays ready for a close (see readyForLaunch), with home's picture for it rendered.
            if (phase == Phase.IDLE) { setWindowShown(true); backdrop?.prewarm(HomeBridge.previewFor(pkg)) }
            if (phase == Phase.IDLE) tasksIo.execute {
                val tasks = try { parseTasks(s.recentTasks(3)) } catch (_: Throwable) { emptyList() }
                val task = tasks.firstOrNull { it.pkg == pkg } ?: return@execute
                frontTask = task
                val prevPkg = tasks.switchable().firstOrNull { it.pkg != pkg }?.pkg
                nav.post { if (phase == Phase.IDLE) warm?.second = prevPkg?.let { images[it] } }
                snapIo.execute {
                    val t0 = SystemClock.uptimeMillis()
                    val b = try { snapshot(s, task.id, true) } catch (_: Throwable) { null }
                    val ms = SystemClock.uptimeMillis() - t0
                    if (keepFreshLogs < 3) { keepFreshLogs++; AppLog.log("[nav] kept a recent picture of $pkg ($ms ms, ${b?.config})") }
                    if (b != null) noteKeptCapture(ms)
                    if (b != null) nav.post { if (lastFrontPkg == pkg) { remember(pkg, b, t0); if (phase == Phase.IDLE) warm?.bitmap = b } }
                }
            }
            nav.postDelayed(this, KEEP_FRESH_MS)
        }
    }

    // Springs
    private var sCx: Mover = Spring(0.5f, 0.86f)
    private var sCy: Mover = Spring(0.5f, 0.86f)
    private var sW: Mover = Spring(0.44f, 0.9f)
    private var sH: Mover = Spring(0.44f, 0.9f)
    private var sDepth: Mover = Spring(0.5f, 1f)   // home behind the card: 0 = at rest, 1 = receded (an app is open)
    private var sOff: Mover = Spring(0.35f, 1f)
    private var sScale: Mover = Spring(0.35f, 1f)
    private var springStartNs = 0L
    private var animating = false
    private var onSettled: (() -> Unit)? = null
    private var endLabel = ""
    private var cardIconSize = 0f                // > 0 while the card travels to or from an icon of that size

    private val stats by lazy { FrameStats() }
    private val choreographer by lazy { Choreographer.getInstance() }

    // Last app window the system reported in front (accessibility events)
    @Volatile private var lastFrontPkg: String? = null
    @Volatile private var lastFrontAt = 0L

    private const val DEFAULT_SPLASH = 0xFF1C1C1E.toInt()
    private const val RELEASE_CARRY = 650f   // px/s of the finger's speed a close keeps at most
    private const val DRAG_DEPTH_RANGE = 0.5f  // how far home comes forward while a card is dragged down to its smallest
    private const val SCALE_RANGE = 0.62f     // smallest card while dragging = 38 % of the screen
    private const val SCALE_LENGTH = 0.28f    // travel (in screen heights) for most of the shrink

    fun init(app: LauncherApp) {
        this.app = app
        NoAnimStarts.init(app)
        dev.launcher.app.switcher.SwitcherHost.thread = nav
        try { app.getSystemService(android.hardware.display.DisplayManager::class.java).registerDisplayListener(displayTurned, main) } catch (_: Throwable) { }
        // A picture of home without the card's icon arrives a moment after a gesture starts: use it as soon as it does.
        HomeBridge.onPreviewReady = { pkg ->
            // (Only behind a card that goes into the icon or comes out of it: sliding and fading keep the icon in the picture.)
            nav.post {
                val leaves = if (anim == Anim.LAUNCH) !flightStyle.fromIcon
                    else if (anim == Anim.HOME_COMMIT) !springToIcon else !AppStyles.closing().fromIcon
                if (cardPkg == pkg && backdrop?.picture != null && !pictureDropped && !leaves) backdrop?.picture = HomeBridge.previewFor(pkg)
            }
        }
        HomeBridge.onHomeTouched = { nav.post { homeTouchedDuringClose(); readyForLaunch() } }
        HomeBridge.onHomeDragged = { nav.post { homeDraggedDuringClose() } }
        // Home settled into a new look: rendered into the layers now, while nothing moves, not at the next gesture's start.
        HomeBridge.onPreviewChanged = { p -> nav.post { prewarmHome(p) } }
        // What is under the status bar on home changed (the App Library opened, the appearance changed): follow at once.
        HomeBridge.onStatusDarkChanged = { nav.post { if (homeVisible) refreshAppearance() } }
        // The settings' picture of home has our bar on top, as it shows there.
        HomeBridge.statusBarPicture = { then ->
            nav.post {
                val b = try { statusBar?.pictureOnHome(HomeBridge.homeStatusDark) } catch (t: Throwable) { AppLog.log("[nav] no picture of the bar: ${t.message}"); null }
                main.post { then(b) }
            }
        }
        // The app in front is the one a close will fly into home: home keeps a picture without its icon ready.
        HomeBridge.likelyClosing = { if (homeVisible) null else lastFrontPkg }
    }

    // ================================================================== public entry points (any thread)

    /** Shows or removes our windows to match the current state. */
    fun update() {
        if (Looper.myLooper() != navThread.looper) { nav.post { update() }; return }
        val base = ready && SystemRestore.gestureFlagsActive && ShizukuLink.service != null
        // Gestures (the strip, the cards): not on the lock screen (it has its own swipe up to unlock).
        val wantNav = base && !locked
        if (wantNav && strip == null) {
            // The card window must stay under the shade's (our status bar and banners are in it): windows of one type stack
            // in the order they were added. One added while the shade is up would be over it, so the shade is added again
            // after it (Matheesha: the status bar, and banners, vanished during every app open and close once the card
            // window had been made again over it).
            val restack = root == null && statusBar != null
            if (restack) removeStatusBar()
            ensureCardWindow()
            addStrip()
            if (restack) addStatusBar()
            pollFloating()
        }
        // On the lock screen only the strip goes: the card window stays (empty, untouchable) where it is, under the shade.
        if (!wantNav && strip != null) { if (base) removeStrip() else removeNav() }
        // Our iOS status bar and shade: on the lock screen too (one shade everywhere; the stock one stays blocked).
        val wantBar = base && SystemRestore.statusBarWanted(app) && (strip != null || locked)
        if (wantBar && statusBar == null) addStatusBar()
        if (!wantBar && statusBar != null) removeStatusBar()
        shade?.setLocked(locked)
    }

    // ================================================================== status bar (nav thread)

    /**
     * Our status bar is on screen: only then are the stock clock and icons hidden, and the stock shade blocked
     * (SystemRestore.applyFlags). The bar lives in the shade's window (shade/Shade.kt) with Notification Center and
     * Control Center.
     */
    @Volatile var statusBarShown = false
        private set
    private var shade: dev.launcher.app.shade.Shade? = null
    private var shadeService: Any? = null
    private val statusBar: dev.launcher.app.statusbar.StatusBarView? get() = shade?.takeIf { statusBarShown }?.bar
    private val appearanceIo = Executors.newSingleThreadExecutor()
    private var appearanceLogs = 0
    private var stripHeight = 0
    private var shadeLogs = 0

    private fun addStatusBar() {
        val ctx = a11y ?: return
        val wm = wm ?: return
        val h = max(systemDimen("status_bar_height"), dp(24).toInt())
        // The shade's windows belong to the accessibility service that made it: after the service restarted (turned off and
        // on, rebound by the system) its token is gone and every window it adds is refused, so a new service gets a new shade.
        if (shade != null && shadeService !== ctx) { shade?.release(); shade = null }
        val s = shade ?: dev.launcher.app.shade.Shade(ctx, wm, nav, shadeLink).also { shade = it; shadeService = ctx }
        if (s.attach(h)) {
            statusBarShown = true
            AppLog.log("[statusbar] on (${h}px), with our shade")
            reapplyFlags()   // now the stock clock and icons can go, and the stock shade
            refreshAppearance()
            main.post { barWatch.attach() }
        }
    }

    /**
     * What the window manager says of the status bar, the moment it changes (BarWatch): whether the app in front hides it
     * (it was asked of dumpsys on window changes and every 2.5 s: a video going full screen kept our bar for up to 2.5 s, and
     * on Android 16+ the answer was never read: the bar never hid), and its height in this rotation.
     */
    private val barWatch by lazy {
        dev.launcher.app.statusbar.BarWatch(app, app.getSystemService(WindowManager::class.java)) { visible, h ->
            nav.post {
                barVisible = visible
                shade?.setBarHeight(h)
                applyBarVisible()
            }
        }
    }

    /** The window manager's word on the status bar (null: BarWatch is not on; dumpsys answers, see [refreshAppearance]). */
    private var barVisible: Boolean? = null

    /** Our bar hides with the app's: not on home (home never hides it), nor while a card is on its way (its app decides). */
    private fun applyBarVisible() {
        val bar = statusBar ?: return
        // Home (upright only) shown turned under a landscape display: no bar until the display has turned too.
        val displayTurned = try { wm?.defaultDisplay?.rotation?.let { it == android.view.Surface.ROTATION_90 || it == android.view.Surface.ROTATION_270 } } catch (_: Throwable) { null } == true
        bar.setSuppressed(displayTurned && (homeVisible || closingHome))
        val v = barVisible ?: return
        if (homeVisible || closingHome || bringing != null) { bar.setHiddenByApp(false); watchEdge(false); return }
        bar.setHiddenByApp(!v)
        watchEdge(!v)
    }

    /**
     * While an app hides the status bar: a swipe from the top edge shows ours for a moment (the system shows its own bars
     * for a moment then: ours stand in for them), from which the shade can be pulled. Seen in the touchscreen's raw events by
     * the shell (it never takes the touch: taps at the top of a game or a video still reach it).
     */
    private fun watchEdge(on: Boolean) {
        val s = ShizukuLink.service
        val r = try { wm?.defaultDisplay?.rotation ?: 0 } catch (_: Throwable) { 0 }
        val key = if (on && s != null) r else -1
        if (key == edgeWatching) return
        edgeWatching = key
        AppLog.log("[statusbar] top edge ${if (key < 0) "no longer watched" else "watched (rotation $r)"}${if (on && s == null) ": no shell service" else ""}")
        frontIo.execute {
            try {
                if (key < 0) s?.stopEdge() else {
                    // The screen's height in this rotation.
                    val h = (try { wm?.maximumWindowMetrics?.bounds?.height() } catch (_: Throwable) { null } ?: sh.toInt()).coerceAtLeast(1).toFloat()
                    val band = (max(shade?.barHeight ?: 0, dp(24).toInt()) / h).coerceAtMost(0.1f)
                    s!!.watchEdge(edgeListener, r, band, EDGE_TRAVEL_DP * density / h)
                }
            } catch (t: Throwable) { AppLog.log("[statusbar] top edge watch failed: ${t.javaClass.simpleName}: ${t.message}") }
        }
    }

    /** The rotation the top edge is watched in (-1: not watched). */
    @Volatile private var edgeWatching = -1

    // The shell service came or went (a restart ends its watch): watched again from scratch where it should be.
    private val shellChanged: () -> Unit = { nav.post { edgeWatching = -1; applyBarVisible() } }
    init { ShizukuLink.addListener(shellChanged) }

    // The display turned (an app in landscape, home back upright): the bar's hold and the top edge's side follow at once
    // (the window manager tells BarWatch's window only once it has redrawn for the turn, a second later on the emulator).
    private val displayTurned = object : android.hardware.display.DisplayManager.DisplayListener {
        private var last = -1
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            if (id != android.view.Display.DEFAULT_DISPLAY) return
            val r = try { app.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(id)?.rotation } catch (_: Throwable) { null } ?: return
            if (r == last) return
            last = r
            nav.post { applyBarVisible() }
        }
    }

    private val edgeListener = object : dev.launcher.app.ITopEdge.Stub() {
        override fun onEdgeSwipe(x: Float) {
            nav.post {
                val bar = statusBar ?: return@post
                if (!bar.hiddenByApp) return@post
                bar.showTransient(TRANSIENT_BAR_MS)
            }
        }
    }

    private fun removeStatusBar() {
        main.post { barWatch.detach() }
        barVisible = null
        watchEdge(false)
        shade?.detach()
        nav.removeCallbacks(appearanceTick)
        if (statusBarShown) { statusBarShown = false; reapplyFlags() }   // stock clock, icons and shade back
    }

    /** True while our shade has a panel open (nav thread). */
    val shadeOpen get() = shade?.isOpen == true

    private val shadeLink = object : dev.launcher.app.shade.Shade.NavLink {
        override fun backdrop(then: (dev.launcher.app.shade.BackdropSource?) -> Unit) = shadeBackdrop(then)
        override fun stripHeight(): Int = if (stripHeight > 0) stripHeight else dp(20).toInt()
        override fun shadeChanged(open: Boolean) {
            nav.post { stripUnderShade(open) }
            if (!open) refreshAppearance()
        }
        override fun frontPackage(): String? = frontNow
        override fun frontClass(): String? = frontNowClass
        override fun homeShown(): Boolean = homeVisible
        override fun bringing(): String? = bringing
        override fun closingHome(): Boolean = closingHome
        override fun appColor(pkg: String): Int? = SplashColors.cached(pkg)
        override fun frontSince(): Long = frontNowAt
        override fun snapshotFor(pkg: String): Bitmap? = images[pkg]
        override fun topTaskPackage(): String? = ShizukuLink.service?.let { parseTasks(it.recentTasks(1)).firstOrNull()?.pkg }
        override fun quietStarts(on: Boolean) {
            if (!on) { nav.post { nav.removeCallbacks(scalesBack); nav.postDelayed(scalesBack, QUIET_BACK_MS) }; return }
            nav.removeCallbacks(scalesBack)
            val s = ShizukuLink.service ?: return
            val wasOn = try { android.provider.Settings.Global.getFloat(app.contentResolver, android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) != 0f } catch (_: Throwable) { true }
            try { SystemRestore.scalesOffForCards(app, s) } catch (t: Throwable) { AppLog.log("[nav] transitions off failed: ${t.javaClass.simpleName}: ${t.message}") }
            // Switched just now: SystemUI takes the setting up a moment later (a warm app's transition can start before).
            if (wasOn) try { Thread.sleep(QUIET_SETTLE_MS) } catch (_: InterruptedException) { }
        }
    }

    /**
     * The package whose window came to the front last and when (uptime ms), home included (ours); SystemUI's and the
     * system's own windows (the volume panel a ringing call is silenced with, the "Viewing full screen" hint a call's screen
     * brings) and keyboards are not the front. The shade's ringing banners ask it.
     */
    @Volatile private var frontNow: String? = null
    @Volatile private var frontNowClass: String? = null
    @Volatile private var frontNowAt = 0L

    /**
     * What is behind the shade: on home, home as it shows (recorded now: the picture at rest can be out of date); over an
     * app, our latest picture of it at once, then a fresh one if that was stale (its screen changed since, or none).
     */
    private fun shadeBackdrop(then: (dev.launcher.app.shade.BackdropSource?) -> Unit) {
        // Over the lock screen: its wallpaper (the front window there is the lock screen's or the fingerprint icon's,
        // which have no task to take a picture of; Control Center's glass showed its plain fallback).
        if (locked) { then(Wallpaper.current?.let { dev.launcher.app.shade.BackdropSource.Wall(it) }); return }
        if ((homeVisible || closingHome) && bringing == null) {
            HomeBridge.preview?.let { then(dev.launcher.app.shade.BackdropSource.Home(it)) }
            HomeBridge.recordForGesture {
                val p = HomeBridge.preview ?: return@recordForGesture
                nav.post { then(dev.launcher.app.shade.BackdropSource.Home(p)) }
            }
            return
        }
        val pkg = bringing ?: lastFrontPkg
        val kept = pkg?.let { images[it] }
        if (shadeLogs < 6) { shadeLogs++; AppLog.log("[shade] behind: $pkg, kept picture ${if (kept != null) "${SystemClock.uptimeMillis() - (imagesAt[pkg] ?: 0L)} ms old" else "none"}${if (pkg != null && keptIsStale(pkg)) " (stale)" else ""}") }
        if (kept != null) then(dev.launcher.app.shade.BackdropSource.App(kept))
        val fresh = kept == null || keptIsStale(pkg) || SystemClock.uptimeMillis() - (imagesAt[pkg] ?: 0L) > 2_000L
        val s = ShizukuLink.service ?: run { if (kept == null) then(null); return }
        if (!fresh) return
        tasksIo.execute {
            // The app in front by its package, or (not reported yet: just after a restart) the most recent task.
            val tasks = try { parseTasks(s.recentTasks(3)) } catch (_: Throwable) { emptyList() }
            val task = if (pkg != null) frontTask?.takeIf { it.pkg == pkg } ?: tasks.firstOrNull { it.pkg == pkg } else tasks.firstOrNull()
            if (task == null) { AppLog.log("[shade] no task for $pkg"); if (kept == null) nav.post { then(null) }; return@execute }
            snapIo.execute {
                val t0 = SystemClock.uptimeMillis()
                val b = try { snapshot(s, task.id, true) } catch (_: Throwable) { null }
                if (shadeLogs < 12) { shadeLogs++; AppLog.log("[shade] fresh picture of $pkg: ${if (b != null) "${SystemClock.uptimeMillis() - t0} ms" else "none"}") }
                nav.post {
                    if (b != null) { remember(task.pkg, b, t0); then(dev.launcher.app.shade.BackdropSource.App(b)) }
                    else if (kept == null) then(null)
                }
            }
        }
    }

    private val appearanceTick = Runnable { refreshAppearance() }

    /**
     * White or black content, as the stock bar would show: on home from the wallpaper under the bar, in apps from what the
     * app asks the window manager for (light status bar = black content). Again on every window change and every 2.5 s.
     */
    private fun refreshAppearance() {
        val bar = statusBar ?: return
        nav.removeCallbacks(appearanceTick)
        nav.postDelayed(appearanceTick, 2500)
        // Not on yet (the overlay permission came later, through Shizuku): again now.
        if (!barWatch.attached) main.post { if (statusBarShown && !barWatch.attached) barWatch.attach() }
        // A card on its way: its app's tone (or home's) was set as it started; the window in charge may still be home's.
        bringing?.let { return }
        // The deck in the switcher's own window (which covers home or the app): the bar keeps the tone it had as the switcher
        // opened (the window in charge is ours now; asked, it recoloured the bar the moment the deck moved there).
        if (win == Win.HOLDS) return
        if (closingHome || homeVisible) { bar.setDark(HomeBridge.homeStatusDark); bar.setHiddenByApp(false); watchEdge(false); return }
        applyBarVisible()
        val s = ShizukuLink.service ?: return
        val askedFor = lastFrontPkg
        val askedAt = SystemClock.uptimeMillis()
        appearanceIo.execute {
            val out = try { s.windowAppearance() } catch (t: Throwable) { "ERROR: ${t.message}" }
            val dark = parseLightStatusBar(out)
            val hidden = parseStatusBarHidden(out)
            if (appearanceLogs < 3) { appearanceLogs++; AppLog.log("[statusbar] appearance (dark ${dark ?: "unknown"}, hidden ${hidden ?: "unknown"}) from: ${out.take(700)}") }
            nav.post {
                val b = statusBar ?: return@post
                if (homeVisible || bringing != null || closingHome) return@post
                // (Not over a tone one of our screens set itself since: read before it, the window's flags were the old ones;
                // nor while it holds the tone, its window's flags waiting.)
                val held = askedFor == app.packageName && SystemClock.uptimeMillis() < ownToneUntil
                if (dark != null && askedAt >= ownToneAt && !held) { b.setDark(dark); askedFor?.let { appDark[it] = dark } }
                // Only when the window manager does not tell us itself (BarWatch off: no overlay permission).
                if (hidden != null && barVisible == null) { b.setHiddenByApp(hidden); watchEdge(hidden) }
            }
        }
    }

    /**
     * True if the status bar's insets source is not visible (an immersive app hid it); null if not found. Android 16+
     * writes it "InsetsSource id=... type=statusBars ... visible=false" (older: "mType=statusBars ... mVisible=false"); a
     * bar shown for a moment (a swipe) is "mShowingTransientTypes=statusBars".
     */
    private fun parseStatusBarHidden(out: String): Boolean? {
        val lines = out.lines()
        val line = lines.firstOrNull { it.contains("InsetsSource ") && it.contains("type=statusBars") }
            ?: lines.firstOrNull { it.contains("mType=statusBars") } ?: return null
        return when {
            line.contains("visible=false") || line.contains("mVisible=false") -> true
            line.contains("visible=true") || line.contains("mVisible=true") -> false
            else -> null
        }
    }

    /** True if the window in charge of the status bar asks for a light one (black content); null if not found. */
    private fun parseLightStatusBar(out: String): Boolean? {
        val lines = out.lines().filter { it.contains("ppearance") }
        val line = lines.firstOrNull { it.contains("mLastAppearance") } ?: lines.firstOrNull() ?: return null
        if (line.contains("LIGHT_STATUS_BARS")) return true
        Regex("""ppearance=0x([0-9a-fA-F]+)""").find(line)?.let { return (it.groupValues[1].toLong(16) and 0x8L) != 0L }
        return false
    }

    /** NavAccessibilityService connected: windows can be added. Re-applies flags, which then shows the strip. */
    fun attach(service: NavAccessibilityService) {
        nav.post {
            if (a11y === service) return@post
            // Unbound before this ran (connected and unbound at once, seen on the S24 after an install): never attach it.
            if (service === unboundService) return@post
            if (strip != null || statusBarShown) removeAll()
            a11y = service
            wm = service.getSystemService(WindowManager::class.java)
            locked = app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            app.registerReceiver(screenReceiver, f, null, nav)
            ready = true
            reapplyFlags()
        }
    }

    /** Service gone: our windows are invalid and stock gestures must come back at once. */
    fun detach(service: NavAccessibilityService) {
        if (a11y !== service && a11y != null) return
        unboundService = service
        ready = false
        reapplyFlags()
        nav.post {
            if (a11y !== service) return@post
            removeAll()
            try { app.unregisterReceiver(screenReceiver) } catch (_: Throwable) { }
            a11y = null
            wm = null
            // An attach queued before this detach ran in between and said ready: stock gestures back again.
            if (ready) { ready = false; reapplyFlags() }
        }
    }

    /**
     * The service last unbound: an attach of it still queued on the nav thread must not run. (Connected and unbound at once,
     * the queued attach set [ready] after the detach had cleared it: stock gestures stayed blocked with no strip of ours,
     * no way home on the S24 until the app restarted.)
     */
    @Volatile private var unboundService: NavAccessibilityService? = null

    fun onHomeShown() {
        homeVisible = true
        closingHome = false
        bringing = null   // a launch that never came up (home is back)
        // The app that was in front has just left it: the system's snapshot of it (taken as it left) is newer than ours.
        lastFrontPkg?.let { leftFrontAt[it] = SystemClock.uptimeMillis() }
        nav.post { refreshAppearance() }
    }

    /** When one of our own screens last set the status bar's tone itself (uptime ms; see [ownScreenTone]). */
    @Volatile private var ownToneAt = 0L
    /** Until when (uptime ms) readings of our own windows' flags are not taken ([ownScreenTone]'s hold). */
    @Volatile private var ownToneUntil = 0L

    /**
     * One of our own screens says what is under the status bar now (dark: dark content, for something light), as the
     * settings slide from or into home: the bar follows at once (the window's own flags are read only when windows change,
     * and a reading asked before this is not taken). For [holdMs] no reading of our own app's window is taken at all: its
     * flags wait for the slide's end (a change of them is a relayout of the window, a missed frame mid-slide), and read
     * meanwhile (the 2.5 s tick, a window event) they recoloured the bar. Any thread.
     */
    fun ownScreenTone(dark: Boolean, holdMs: Long = 0L) {
        val now = SystemClock.uptimeMillis()
        ownToneAt = now
        ownToneUntil = now + holdMs
        nav.post {
            if (homeVisible || bringing != null || closingHome || win == Win.HOLDS) return@post
            statusBar?.setDark(dark)
        }
    }

    /**
     * One of our own screens (the settings) has slid back into home and shows [picture] (home as it shows, our status bar
     * on it) over the whole screen. The card window holds the same picture over everything while [atRest] puts that screen
     * back at rest underneath (its last frame is what the system keeps of it: its card in the App Switcher, and how it shows
     * when it comes back; left showing home, its card was a picture of home), home comes to the front without an animation,
     * and once home has drawn the picture goes. [atRest] runs on the main thread and calls its argument once that frame is
     * drawn. Any thread.
     */
    fun ownScreenToHome(picture: android.graphics.Bitmap, atRest: (() -> Unit) -> Unit) = nav.post {
        val host = cardWindow
        if (host == null || !prepareCardWindow()) {
            // The screen keeps showing home's picture (its last frame) until home covers it.
            AppLog.log("[nav] no card window for home's picture: home comes at once")
            frontIo.execute { startHomeQuietly() }
            return@post
        }
        val g = ++coverGen
        setWindowShown(true)
        // Made in this card window: one made in a window gesture nav had before (it is made again when its service restarts)
        // is in no window and never showed; the screen then stayed on home's picture taking no touch (Matheesha: "home
        // freezes completely" after Home Settings and Back).
        val cover = homeCover?.takeIf { it.parent === host } ?: CoverView(host.context).also { c ->
            host.addView(c, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            homeCover = c
        }
        cover.picture = picture
        cover.drawnAt = 0L
        cover.visibility = View.VISIBLE
        coverAt = SystemClock.uptimeMillis()
        coverUp = false
        homeAsked = false
        // Over our screen once a frame that drew it is committed (a frame of the window without it, one that produced no
        // buffer, does not count), and a refresh later on screen: the screen under it may change from then on.
        fun whenCommitted() {
            host.viewTreeObserver.registerFrameCommitCallback {
                if (g != coverGen) return@registerFrameCommitCallback
                if (cover.drawnAt < coverAt) { whenCommitted(); cover.invalidate(); return@registerFrameCommitCallback }
                nav.postDelayed({
                    if (g != coverGen || coverUp) return@postDelayed
                    coverUp = true
                    AppLog.log("[nav] home's picture is over our screen (${SystemClock.uptimeMillis() - coverAt} ms): it goes back at rest under it")
                    main.post { atRest { nav.post { if (g == coverGen) coverHome(g) } } }
                }, COVER_SHOWN_MS)
            }
        }
        whenCommitted()
        cover.invalidate()
        // Not on screen in time: home comes anyway (the screen shows home's picture meanwhile, the settings' last frame) and
        // the settings stay as they are underneath, their card in the App Switcher showing home's picture until they show.
        nav.postDelayed({
            if (g != coverGen || coverUp) return@postDelayed
            coverUp = true
            AppLog.log("[nav] home's picture did not show over our screen in ${SystemClock.uptimeMillis() - coverAt} ms: home comes at once")
            coverHome(g)
        }, COVER_SHOW_WAIT_MS)
        // Never left up: whatever happens, it goes.
        nav.postDelayed({ if (g == coverGen) uncover("timed out") }, COVER_MAX_MS)
    }

    // ---- home's picture over one of our screens going back into home (ownScreenToHome)

    private var homeCover: CoverView? = null
    private var coverGen = 0
    private var coverAt = 0L
    /** The cover counted as on screen (or given up on): what follows runs once. */
    private var coverUp = false
    /** Home was asked to come for the current cover. */
    private var homeAsked = false

    /** A picture over the whole card window (home's, as one of our screens hands back to it). */
    private class CoverView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dst = RectF()
        var picture: android.graphics.Bitmap? = null
            set(v) { if (field !== v) { field = v; invalidate() } }
        /** When it last drew its picture (uptime ms). */
        var drawnAt = 0L

        override fun onDraw(c: Canvas) {
            val b = picture ?: return
            dst.set(0f, 0f, width.toFloat(), height.toFloat())
            c.drawBitmap(b, null, dst, paint)
            drawnAt = SystemClock.uptimeMillis()
        }
    }

    /** The screen under the cover is at rest: home comes to the front; the cover goes a refresh after home has drawn. */
    private fun coverHome(g: Int) {
        val asked = SystemClock.uptimeMillis()
        AppLog.log("[nav] our screen is at rest under home's picture (${asked - coverAt} ms): home comes")
        homeAsked = true
        frontIo.execute { startHomeQuietly() }
        fun check() {
            if (g != coverGen) return
            val drawn = homeVisible && HomeBridge.homeDrawnAt >= asked
            if (drawn || SystemClock.uptimeMillis() - asked > COVER_HOME_WAIT_MS) {
                nav.postDelayed({ if (g == coverGen) uncover(if (drawn) "home drew ${HomeBridge.homeDrawnAt - asked} ms after it was asked for" else "home did not draw in time") }, COVER_SHOWN_MS)
            } else nav.postDelayed({ check() }, 8)
        }
        check()
    }

    private fun uncover(why: String) {
        val g = coverGen++
        val c = homeCover ?: return
        if (c.visibility != View.VISIBLE) return
        c.visibility = View.GONE
        c.picture = null
        AppLog.log("[nav] home's picture over our screen goes after ${SystemClock.uptimeMillis() - coverAt} ms ($why)")
        // Timed out before home was asked for (the settings never said they were at rest): home comes now, never leaving
        // the settings on home's picture.
        if (!homeAsked && why == "timed out") { homeAsked = true; AppLog.log("[nav] home comes after the cover timed out (cover $g)"); frontIo.execute { startHomeQuietly() } }
    }

    /** Home to the front with no animation and nothing of a gesture's state touched (blocking: the front queue). */
    private fun startHomeQuietly() {
        try {
            val home = Intent(app, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!NoAnimStarts.start(home, Process.myUserHandle().hashCode())) app.startActivity(home, noAnimation(app))
        } catch (t: Throwable) {
            AppLog.log("[nav] home could not be brought back (${t.javaClass.simpleName}); using the shell")
            ShizukuLink.service?.runDetached("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
        }
    }

    /** From NavAccessibilityService: a window of [pkg] came to the front. */
    fun onWindowStateChanged(pkg: String?, className: String? = null) {
        if (pkg == null) return
        // The App Switcher's own window (under a mini player): the switcher, not an app in front.
        if (className == dev.launcher.app.switcher.SwitcherActivity::class.java.name) return
        // An app back in front from its mini player while the App Switcher is open (its expand button): the deck makes way.
        // (Our own windows, the switcher's among them, come with our package: ours floats only as its test app.)
        if (pkg != app.packageName || className == PipTestActivity::class.java.name) {
            nav.post { if (phase == Phase.SWITCHER && pkg in switcherFloating) switcherAppCameForward(pkg) }
        }
        // The keyboard is not the app in front: taken for it, a close aimed at the keyboard's package and a launch that showed
        // the keyboard looked like the wrong app had come up (seen on the S24 after App Library search).
        if (isKeyboard(pkg, className)) return
        if (pkg != lastFrontPkg) AppLog.log("[front] now in front: $pkg ($className)")
        if (pkg == bringing) bringing = null
        nav.post { refreshAppearance() }   // a different window may ask for a different status bar
        if (pkg != "com.android.systemui" && pkg != "android" && (pkg != app.packageName || className?.endsWith(".HomeActivity") == true || isOwnScreen(className))) {
            frontNow = pkg
            frontNowClass = className
            frontNowAt = SystemClock.uptimeMillis()
            nav.post { shade?.frontChanged() }
        }
        // Our own windows (home, cards, strip) are never the app a gesture closes or a launch waits for; our own screens
        // (developer panel, safe settings) are: else closing one flew the previous app's card to the centre.
        if (pkg == app.packageName && !isOwnScreen(className)) return
        lastFrontPkg?.takeIf { it != pkg }?.let { leftFrontAt[it] = SystemClock.uptimeMillis() }
        lastFrontPkg = pkg
        lastFrontAt = SystemClock.uptimeMillis()
        // A first picture once the app has drawn, then one every few seconds while it stays in front.
        nav.removeCallbacks(keepFresh)
        nav.postDelayed(keepFresh, 1500)
        nav.post {
            val w = waitingFor ?: return@post
            if (w.first == pkg) { waitingFor = null; nav.removeCallbacks(waitTimeout); nav.postDelayed(w.second, 16) }
        }
    }

    /** One of our own full-screen activities other than home (an accessibility window event's class name). */
    private fun isOwnScreen(className: String?): Boolean =
        className != null && className.startsWith(app.packageName + ".") && className.endsWith("Activity") &&
            !className.endsWith(".HomeActivity") && !className.endsWith(".SwitcherActivity") && '$' !in className

    private var keyboards: Set<String> = emptySet()
    private var keyboardsAt = 0L

    private fun isKeyboard(pkg: String, className: String?): Boolean {
        if (className?.startsWith("android.inputmethodservice.") == true) return true
        val now = SystemClock.uptimeMillis()
        if (keyboardsAt == 0L || now - keyboardsAt > 60_000) {
            keyboardsAt = now
            keyboards = try {
                app.getSystemService(android.view.inputmethod.InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }.toSet()
            } catch (_: Throwable) { keyboards }
        }
        return pkg in keyboards
    }

    /**
     * Opening an app from [iconRect] on home. The card grows out of the icon over our picture of home while the app starts
     * underneath; [start] (which starts the app, on the main thread) runs once our window covers the screen, because with
     * system animations off the app's window appears at full size at once. If that app's card is already on screen (it was
     * just closing), the card reverses from where it is. Returns false if gesture nav cannot animate (caller just starts).
     */
    fun launchApp(pkg: String, iconRect: RectF, icon: Drawable?, start: () -> Unit): Boolean {
        if (!ready) return false
        holdScalesOff()   // queued before the start, so no system transition plays under the card
        val iconCopy = icon?.constantState?.newDrawable()?.mutate() ?: icon
        nav.post { beginLaunch(pkg, RectF(iconRect), iconCopy, start) }
        return true
    }

    private fun reapplyFlags() {
        val s = ShizukuLink.service ?: return
        tasksIo.execute { SystemRestore.applyFlags(app, s) }
    }

    // The lock screen is left alone: no strip (it would sit on top of the unlock area) while the keyguard is up.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            locked = when (intent.action) {
                Intent.ACTION_USER_PRESENT -> false
                Intent.ACTION_SCREEN_OFF -> true
                else -> app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            }
            update()
            if (intent.action == Intent.ACTION_SCREEN_OFF) shade?.onScreenOff()
        }
    }

    // ================================================================== windows (nav thread)

    private fun overlayParams(w: Int, h: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
    }

    private fun addStrip() {
        val ctx = a11y ?: return
        val wm = wm ?: return
        // Android's gesture height (One UI: 32 dp). An overlay takes every touch in its band and cannot let an app's drag
        // through as Android's own gestures do (StripForward: taps reach the app), so it lies over the bottom of some apps'
        // content (TikTok's seek bar); at 20 dp swipes home were missed half the time (Matheesha), so it stays this tall.
        val h = max(dp(20).toInt(), systemDimen("navigation_bar_gesture_height").takeIf { it > 0 } ?: systemDimen("navigation_bar_height"))
        val stripView = FrameLayout(ctx)
        val pill = View(ctx).apply {
            background = GradientDrawable().apply { setColor(0x99FFFFFF.toInt()); cornerRadius = dp(2) }
        }
        // Its centre 10 dp above the bottom edge.
        stripView.addView(pill, FrameLayout.LayoutParams(dp(108).toInt(), dp(4).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(8).toInt() })
        stripView.setOnTouchListener { _, e -> onTouch(e) }
        val lp = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, h, touchable = true).apply {
            gravity = Gravity.BOTTOM
            title = "LauncherGestureStrip"
        }
        try {
            wm.addView(stripView, lp)   // added after the card window, so it stays above it
            strip = stripView
            stripHeight = h
            stripHidden = false
            if (shadeOpen) stripView.post { stripUnderShade(true) }
            AppLog.log("[nav] gesture strip on (${h}px high, own UI thread, persistent card window)")
        } catch (t: Throwable) {
            AppLog.log("[nav] strip addView FAILED: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private var stripHidden = false

    /**
     * The pill is away while a close over a turned display hands over to home: Android hides the strip's window as home
     * starts and shows it again only once the display has turned (it went in one frame), so the pill fades out with the
     * close and back in, at the bottom of the upright screen, as the session ends.
     */
    private var pillAwayForTurn = false
        set(v) { if (field != v) { field = v; applyPill() } }

    private fun applyPill() {
        val hidden = stripHidden || pillAwayForTurn
        (strip as? android.view.ViewGroup)?.getChildAt(0)?.animate()?.alpha(if (hidden) 0f else 1f)
            ?.timed(Motion.role(if (hidden) dev.launcher.app.motion.MotionTokens.STRIP_HIDE else dev.launcher.app.motion.MotionTokens.STRIP_SHOW))?.start()
    }

    /**
     * While a panel of the shade is open the gesture bar is not there (Matheesha: it got in the way of the panels): its
     * pill fades out and the strip takes no touches (a swipe up from the bottom closes the panel, the shade's own). A
     * touchable region, not a window change: no relayout.
     */
    private fun stripUnderShade(hidden: Boolean) {
        if (strip == null || hidden == stripHidden) return
        stripHidden = hidden
        applyPill()
        applyStripRegion()
    }

    /** The strip lets touches through to the app under it while a tap is handed over ([StripForward]). */
    private var stripLetThrough = false

    private fun applyStripRegion() {
        val s = strip ?: return
        // Back to all of it with an explicit region: null does not reset the region the window manager has.
        if (Build.VERSION.SDK_INT >= 34) s.rootSurfaceControl?.setTouchableRegion(
            if (stripHidden || stripLetThrough) android.graphics.Region(-2, -2, -1, -1)
            else android.graphics.Region(0, 0, s.width.coerceAtLeast(1) * 4, s.height.coerceAtLeast(1) * 4))
    }

    private val forward by lazy { StripForward(nav) { on -> if (on != stripLetThrough) { stripLetThrough = on; applyStripRegion() } } }

    /**
     * A tap on the strip over an app is the app's: handed to it once the finger has lifted ([StripForward]). Only to another
     * app whose uid is known (the injection is aimed at its windows alone), and where the strip can let touches through
     * (Android 14+).
     */
    private fun tapToApp(up: MotionEvent) {
        if (Build.VERSION.SDK_INT < 34) return
        val pkg = lastFrontPkg ?: return
        if (pkg == app.packageName) return
        val uid = uids.getOrPut(pkg) { try { app.packageManager.getApplicationInfo(pkg, 0).uid } catch (_: Throwable) { -1 } }
        if (uid < 0) return
        forward.tap(up, uid)
    }

    private val uids = HashMap<String, Int>()

    @Suppress("DEPRECATION")
    private fun readDisplay() {
        val wm = wm ?: return
        val dm = android.util.DisplayMetrics()
        val display = wm.defaultDisplay
        display.getRealMetrics(dm)
        sw = dm.widthPixels.toFloat()
        sh = dm.heightPixels.toFloat()
        displayRotation = display.rotation
        deviceRadius = if (Build.VERSION.SDK_INT >= 31) {
            display.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: dp(32)
        } else dp(32)
    }

    /** The card window: created once and kept (INVISIBLE when idle), so no gesture ever waits for a window to be added. */
    private fun ensureCardWindow() {
        val ctx = a11y ?: return
        val wm = wm ?: return
        if (root != null) return
        readDisplay()
        // The window itself stays shown for good (window alpha 1 from its first use; see readyForLaunch): showing or hiding
        // it made the window manager re-lay it out and Android rebuild its buffers, measured at 50-80 ms on the nav thread on
        // the S24 at the start of every gesture (the card's first frames came late). Cards show and hide inside it ([root]).
        val host = CardHost(ctx)
        val r = Stage(ctx).apply { visibility = View.INVISIBLE }
        warm = WarmView(ctx).also { host.addView(it, FrameLayout.LayoutParams(1, 1)) }
        val b = PreviewView(ctx)
        val p = CardView(ctx).apply { visibility = View.GONE }
        val n = CardView(ctx).apply { visibility = View.GONE }
        val c = CardView(ctx)
        val l = CardView(ctx).apply { visibility = View.GONE }
        // The picture of home sits under the cards but outside their container, always in the drawing tree (at alpha 0 while
        // no card shows): a view that leaves the tree loses its GPU layers, and re-allocating them cost ~12-20 ms of the
        // first frame of every gesture (traced on the S24). The container mirrors its visibility and fade onto it.
        b.alpha = 0f
        host.addView(b, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        host.addView(r, 1, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        r.mirror = b
        r.addView(p, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        r.addView(n, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // Under the current card: a closing card that keeps landing while the next app grows over it (the lander), and its
        // badge.
        r.addView(l, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        r.addView(CardBadgeView(ctx, l), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        r.addView(c, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // The current card's notification badge, above it and unclipped (it reaches past the icon's corner).
        r.addView(CardBadgeView(ctx, c), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // The App Switcher's deck: above the cards (it takes over the current one when it opens).
        val d = dev.launcher.app.switcher.DeckView(ctx, deckListener).apply { visibility = View.GONE }
        r.addView(d, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // Exact full-display size, drawn into the cutout too (the probe saw resizing as a stretch).
        val lp = overlayParams(sw.toInt(), sh.toInt(), touchable = CAN_CATCH_TOUCHES).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "LauncherCards"
            alpha = 0f
            // Touches outside its touchable region (empty unless a close is under way) go to the windows below.
            if (CAN_CATCH_TOUCHES) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
        if (CAN_CATCH_TOUCHES) {
            host.setOnTouchListener { _, e -> onCardWindowTouch(e); true }
            // Its window exists only from its first layout pass (attach runs early in it, before the touchable area is sent):
            // set right after addView, the region was dropped and the whole window took touches.
            host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { v.rootSurfaceControl?.setTouchableRegion(if (catching) fullRegion() else NO_TOUCH) }
                override fun onViewDetachedFromWindow(v: View) {}
            })
        }
        try {
            wm.addView(host, lp)
            cardWindow = host; windowShown = false
            root = r; backdrop = b; prv = p; nxt = n; cur = c; land = l; deck = d
            host.holeRadius = pipCornerRadius()
            setUpSwitcherWindow(ctx)
            pollFloating()
            d.setScreen(sw, sh, deviceRadius)
            input = dev.launcher.app.switcher.SwitcherInput({ e -> nav.post { onSwitcherTouch(e); e.recycle() } }, { nav.post { floatingPressed() } })
                .also { it.attach(ctx, wm, sw.toInt(), sh.toInt()) }
        } catch (t: Throwable) {
            AppLog.log("[nav] card window FAILED: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Re-reads the display before a card session; resizes the card window after a rotation. */
    private fun prepareCardWindow(): Boolean {
        val r = root ?: run { ensureCardWindow(); root } ?: return false
        val oldW = sw
        val oldH = sh
        readDisplay()
        val host = cardWindow ?: return true
        if (sw != oldW || sh != oldH) {
            val lp = host.layoutParams as WindowManager.LayoutParams
            lp.width = sw.toInt()
            lp.height = sh.toInt()
            try { wm?.updateViewLayout(host, lp) } catch (_: Throwable) { }
            input?.resize(sw.toInt(), sh.toInt())
            a11y?.let { setUpSwitcherWindow(it) }
        }
        deck?.setScreen(sw, sh, deviceRadius)
        orientBackdrop()
        return true
    }

    /** The display is turned from the phone's natural way up (an app in landscape): home is not (it is upright only). */
    private val turned get() = displayRotation == android.view.Surface.ROTATION_90 || displayRotation == android.view.Surface.ROTATION_270

    /** How far home is turned on this display (degrees, about the screen's centre): what Android shows it at (fixed rotation). */
    private val homeTurn get() = when (displayRotation) {
        android.view.Surface.ROTATION_90 -> -90f
        android.view.Surface.ROTATION_270 -> 90f
        else -> 0f
    }

    /**
     * Home is upright only: over an app in landscape the picture of home is laid out at home's size and turned to the
     * phone's natural way up, as Android shows the real home when it comes (fixed rotation), so the two line up when it
     * takes over (it was drawn unturned in the landscape screen: home's left part upright, the app beside it).
     */
    private fun orientBackdrop(view: View? = backdrop) {
        val b = view ?: return
        val lp = b.layoutParams as? FrameLayout.LayoutParams ?: return
        val w = if (turned) sh.toInt() else FrameLayout.LayoutParams.MATCH_PARENT
        val h = if (turned) sw.toInt() else FrameLayout.LayoutParams.MATCH_PARENT
        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; b.layoutParams = lp }
        b.rotation = homeTurn
        b.translationX = if (turned) (sw - sh) / 2f else 0f
        b.translationY = if (turned) (sh - sw) / 2f else 0f
    }

    /** [r] on home (its own, upright coordinates) where it shows on this display (turned with home in landscape). */
    private fun homeToScreen(r: android.graphics.RectF): android.graphics.RectF = when (displayRotation) {
        // Home's width is the landscape screen's height: (x, y) shows at (y, sh - x) turned one way, at (sw - y, x) the other.
        android.view.Surface.ROTATION_90 -> android.graphics.RectF(r.top, sh - r.right, r.bottom, sh - r.left)
        android.view.Surface.ROTATION_270 -> android.graphics.RectF(sw - r.bottom, r.left, sw - r.top, r.right)
        else -> r
    }

    // The card window (always added) and whether it is at window alpha 1.
    private var cardWindow: CardHost? = null
    private var warm: WarmView? = null

    /**
     * The card window's content view. Floating windows (picture-in-picture, pop-up windows) lie above every app but under
     * our overlay: whatever we draw over them hides them, so a video vanished for the length of every launch and close.
     * The window leaves [holes] where they are (cleared: the floating window shows through, live), with their rounded
     * corners. Each hole is a small view on top of everything that clears what is under it ([Punch]): moved, only where it
     * was and where it is are drawn again. (A clip of the whole window around it drew all of it every frame: a mini player
     * dragged in the App Switcher cost the S24's GPU 10.9 ms a frame, the deck scrolling 6.1.)
     */
    private class CardHost(ctx: Context) : FrameLayout(ctx) {
        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
            super.onConfigurationChanged(newConfig)
            holeRadius = pipCornerRadius()   // (the display's size setting changes it with the density)
            onCardWindowTurned()
        }

        var holes: List<RectF> = emptyList()
            set(v) { if (field != v) { field = v; placeHoles() } }
        var holeRadius = 0f
            set(v) { field = v; for (p in punches) p.invalidate() }
        private val punches = ArrayList<Punch>()

        private fun placeHoles() {
            while (punches.size < holes.size) Punch(context, this).also { punches += it; addView(it, LayoutParams(0, 0)) }
            for (i in punches.indices) {
                val p = punches[i]
                val h = holes.getOrNull(i)
                if (h == null) { if (p.visibility != GONE) { p.visibility = GONE; p.forget() }; continue }
                if (p.visibility != VISIBLE) p.visibility = VISIBLE
                p.place(h)
            }
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            placeHoles()   // a layout pass puts them at their layout size (0 x 0): back where they are
        }

        // The holes stay above every other child.
        override fun onViewAdded(child: View) {
            super.onViewAdded(child)
            if (child !is Punch && punches.isNotEmpty()) post { for (p in punches) p.bringToFront() }
        }
    }

    /**
     * One hole: clears its rounded rectangle. Moved by translation (a property of its own render node) on whole pixels, the
     * shape exact inside: a change of a child's bounds makes Android record its parent again, and the whole window counted
     * as changed (traced on the S24: every frame drawn whole while a mini player moved). Its bounds only ever grow, to the
     * largest hole it has held (a hole drawn inside a moving window shrinks and grows every frame).
     */
    private class Punch(ctx: Context, private val host: CardHost) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        }
        private val shape = RectF()
        private val next = RectF()

        fun place(h: RectF) {
            val x = kotlin.math.floor(h.left)
            val y = kotlin.math.floor(h.top)
            val w = kotlin.math.ceil(h.right - x).toInt()
            val hh = kotlin.math.ceil(h.bottom - y).toInt()
            if (w > right - left || hh > bottom - top || left != 0 || top != 0) setLeftTopRightBottom(0, 0, max(w, right - left), max(hh, bottom - top))
            translationX = x
            translationY = y
            next.set(h.left - x, h.top - y, h.right - x, h.bottom - y)
            if (next != shape) { shape.set(next); invalidate() }
        }

        /** No hole now: its bounds may shrink to the next one's. */
        fun forget() = setLeftTopRightBottom(0, 0, 0, 0)

        override fun onDraw(c: Canvas) {
            c.drawRoundRect(shape, host.holeRadius, host.holeRadius, paint)
        }
    }

    // ---- floating windows (nav thread): where they are, asked of the shell every FLOATING_POLL_MS (a few ms of a worker's
    // time), at the start of every card session, and every FLOATING_BUSY_MS while cards show (a PiP window can be dragged).
    private var floating: List<RectF> = emptyList()
    /** The app of each of [floating], and its windowing mode (picture-in-picture: [WINDOWING_MODE_PINNED]). */
    private var floatingPkgs: List<String> = emptyList()
    private var floatingModes: List<Int> = emptyList()
    private var floatingLogged = ""

    private val floatingPoll = object : Runnable {
        override fun run() {
            nav.removeCallbacks(this)
            if (cardWindow == null || !ready) return
            if (!locked) refreshFloating()   // nothing to look up under the lock screen
            nav.postDelayed(this, if (root?.visibility == View.VISIBLE) FLOATING_BUSY_MS else FLOATING_POLL_MS)
        }
    }

    private fun pollFloating() { nav.removeCallbacks(floatingPoll); nav.post(floatingPoll) }

    private fun refreshFloating() {
        val s = ShizukuLink.service ?: return
        tasksIo.execute {
            val lines = try { s.floatingWindows() } catch (_: Throwable) { emptyArray() }
            val pkgs = ArrayList<String>()
            val modes = ArrayList<Int>()
            val rects = lines.mapNotNull { line ->
                val p = line.split(' ')
                if (p.size < 7) return@mapNotNull null
                val l = p[3].toFloatOrNull() ?: return@mapNotNull null
                val t = p[4].toFloatOrNull() ?: return@mapNotNull null
                val r = p[5].toFloatOrNull() ?: return@mapNotNull null
                val b = p[6].toFloatOrNull() ?: return@mapNotNull null
                // A pop-up window the size of the screen is not floating over anything.
                if (r - l >= sw - 1f && b - t >= sh - 1f) return@mapNotNull null
                pkgs += p[1]
                modes += p[2].toIntOrNull() ?: 0
                RectF(l, t, r, b)
            }
            nav.post {
                floatingPkgs = pkgs
                floatingModes = modes
                updateSwitcherWarmth()
                if (rects != floating) {
                    floating = rects
                    val note = lines.joinToString("; ")
                    if (note != floatingLogged) { floatingLogged = note; AppLog.log("[nav] floating windows: ${note.ifEmpty { "none" }}") }
                    applyFloating()
                }
            }
        }
    }

    /**
     * The holes (the card window draws nothing there: the floating windows under it show) and the touchable regions (during a
     * close, in the App Switcher) leave the floating windows alone, where they really are ([live]) or else where the poll
     * last saw them.
     */
    private fun applyFloating() {
        updateWatch()
        val holes = ArrayList<RectF>(floating.size)
        val touchHoles = ArrayList<RectF>(floating.size)
        for (i in floating.indices) {
            val pkg = floatingPkgs.getOrNull(i)
            // The app being closed went into picture-in-picture: the card flies into it, and its window stays covered (no
            // hole) until the card is there and it has settled.
            if (pkg != null && pkg == pipCoverPkg) { flyIntoFloating(floating[i]); continue }
            val at = pkg?.let { live[it] }
            touchHoles += at?.let { RectF(it) } ?: floating[i]
            holes += at?.let { r -> RectF(r).apply { liveInset[pkg]?.let { inset(it[0], it[1]) } } } ?: floating[i]
        }
        cardWindow?.holes = if (win == Win.HOLDS) emptyList() else holes
        // A window moved by the finger keeps that touch whatever the region says: the region follows once it is still.
        pendingInputHoles = touchHoles
        nav.removeCallbacks(inputHolesNow)
        nav.postDelayed(inputHolesNow, INPUT_HOLES_MS)
        if (catching) cardWindow?.rootSurfaceControl?.setTouchableRegion(fullRegion())
    }

    private var pendingInputHoles: List<RectF> = emptyList()
    private val inputHolesNow = Runnable { input?.setHoles(pendingInputHoles) }

    // ---- where the floating windows really are (the shell's window watch)

    /**
     * The live frames (display px) of the watched apps' windows: SurfaceFlinger's window information through the shell
     * ([WindowWatch]), which follows a mini player wherever SystemUI moves it (dragged, snapped, flung, resized, expanding).
     * Its task's bounds, which the poll reads, change only once it has settled (Matheesha: the mini player was "absolutely
     * broken" in the App Switcher: its hole was guessed from the finger and lagged, overshot and jumped).
     */
    private val live = HashMap<String, RectF>()
    /** The apps watched now (the order the shell numbers them in). */
    @Volatile private var watched: List<String> = emptyList()
    /** The shell the watch was asked of. */
    private var watchShell: IShellService? = null
    /** When the closing app's live frame last changed (uptime ms). */
    private var pipMovedAt = 0L
    private var watchLogs = 0

    /**
     * Reports reach the nav thread later than they come when it is busy (the emulator: 57 ms on average, up to 225, queued
     * one behind another; the hole trailed the mini player by 300-500 px): only the newest waiting one is taken.
     */
    private class LiveReport(val names: List<String>, val which: IntArray, val frames: FloatArray, val at: Long)
    private val liveInbox = java.util.concurrent.atomic.AtomicReference<LiveReport?>(null)

    private val windowWatch = object : dev.launcher.app.IWindowWatch.Stub() {
        override fun onWindows(which: IntArray, frames: FloatArray, at: Long) {
            if (liveInbox.getAndSet(LiveReport(watched, which, frames, at)) == null) nav.post(takeLive)
        }
    }

    private val takeLive = Runnable {
        val r = liveInbox.getAndSet(null) ?: return@Runnable
        if (r.names === watched) onLive(r.which, r.frames, r.at)
    }

    /** Watches the floating windows' apps (and the app being closed into one) while the App Switcher or a close shows them. */
    private fun updateWatch() {
        if (phase == Phase.SWITCHER) switcherFloating.addAll(floatingPkgs)
        val want = if (phase == Phase.SWITCHER || pipCoverPkg != null) (floatingPkgs + switcherFloating + listOfNotNull(pipCoverPkg)).distinct() else emptyList()
        // Registered again with a new shell (one that restarted lost its watch), or once there is one.
        val s = ShizukuLink.service
        if (want == watched && (want.isEmpty() || watchShell === s)) return
        watched = want
        watchShell = s
        live.clear()
        liveExact.clear()
        liveV.clear()
        liveInset.clear()
        liveTrail.clear()
        livePressAt = 0L
        s ?: return
        tasksIo.execute {
            try {
                if (want.isEmpty()) s.stopWindows()
                else {
                    // Activity windows are named "<id> <package>/<activity>" (ours: only the mini player test app floats).
                    val r = s.watchWindows(windowWatch, want.map { if (it == app.packageName) "$it/${PipTestActivity::class.java.name}" else "$it/" }.toTypedArray())
                    if (r != "ok" && watchLogs++ < 3) AppLog.log("[nav] window watch failed: $r (the poll's places stand in)")
                }
            } catch (_: Throwable) { }
        }
    }

    private fun onLive(which: IntArray, frames: FloatArray, at: Long) {
        noteLive(at)
        val now = HashMap<String, RectF>()
        for (i in which.indices) {
            val pkg = watched.getOrNull(which[i]) ?: continue
            val r = RectF(frames[i * 4], frames[i * 4 + 1], frames[i * 4 + 2], frames[i * 4 + 3])
            now[pkg] = now[pkg]?.apply { union(r) } ?: r
        }
        val cp = pipCoverPkg
        if (cp != null && now[cp] != liveExact[cp]) pipMovedAt = SystemClock.uptimeMillis()
        // How fast each moves (px/ms): its hole is drawn where it will be when our frame shows ([placeLive]). Taken over the
        // reports of the last [LIVE_TRAIL_MS], timed by when they reached the shell (not us) and counted in whole refreshes:
        // SystemUI moves a dragged mini player unevenly (a refresh without a step, then two), and a speed from one report
        // to the next swung with it.
        val f = frameMs()
        val gap = at - liveAt
        for ((pkg, r) in now) {
            val was = liveExact[pkg]
            val v = liveV.getOrPut(pkg) { FloatArray(2) }
            val trail = liveTrail.getOrPut(pkg) { ArrayDeque() }
            // Setting off from rest, the first step says nothing of the speed: SystemUI starts a drag with a jump of its touch
            // slop (taken as a speed, the hole shot 30-60 px ahead of the mini player). The speed counts from there.
            if (was == null || gap > LIVE_SETOFF_MS || was.width() != r.width() || was.height() != r.height()) trail.clear()
            trail.addLast(LiveSample(at, r.centerX(), r.centerY()))
            while (trail.size > 2 && at - trail.first().at > LIVE_TRAIL_MS) trail.removeFirst()
            val o = trail.first()
            if (trail.size >= 2) {
                val dt = max(1, Math.round((at - o.at) / f)) * f
                v[0] = (r.centerX() - o.x) / dt
                v[1] = (r.centerY() - o.y) / dt
            } else { v[0] = 0f; v[1] = 0f }
            if (livePressAt != 0L && was != null && (r.centerX() != was.centerX() || r.centerY() != was.centerY())) livePressMoved = true
        }
        if (liveAt != 0L && gap in 0..LIVE_EVERY_MAX_MS) liveEvery = if (liveEvery == 0f) gap.toFloat() else liveEvery * 0.8f + gap * 0.2f
        liveAt = at
        liveExact.clear()
        liveExact.putAll(now)
        // In the App Switcher, a mini player grown to the whole screen is its app coming back (its expand button): the deck
        // makes way.
        if (phase == Phase.SWITCHER) for ((pkg, r) in now) if (pkg in switcherFloating && fullScreen(r)) switcherAppCameForward(pkg)
        // The holes follow in the next frame (placed for the moment it is drawn).
        startLiveFrames()
    }

    /** The live frames as reported, their speeds (px/ms) and when the last report came; [live] is where they show when we draw. */
    private val liveExact = HashMap<String, RectF>()
    private val liveV = HashMap<String, FloatArray>()
    private class LiveSample(val at: Long, val x: Float, val y: Float)
    private val liveTrail = HashMap<String, ArrayDeque<LiveSample>>()
    /** When the newest report reached the shell (uptime ms), and how far apart they come (ms, smoothed). */
    private var liveAt = 0L
    private var liveEvery = 0f

    /**
     * How far each floating window's hole is drawn inside it (px, across and down) while it moves. The hole is drawn where
     * the window should be by the time our frame shows; measured on the S24 (a mini player dragged in the App Switcher at
     * ~1,100 px/s, the hole against the video frame by frame), that guess is right to within about a refresh either way, and
     * a hole outside the window showed a strip of home behind it (4-20 px), while one a little inside is never seen: the
     * deck's edge covers the window's for a moment instead. Follows the speed at once, eases back to nothing as it slows.
     */
    private val liveInset = HashMap<String, FloatArray>()
    /**
     * When a finger came down on a floating window in the App Switcher (uptime ms; 0: none). SystemUI starts a drag with a
     * jump of its touch slop (~22 px on the S24) that the reports show only two or three refreshes later: from the press the
     * hole is drawn [LIVE_PRESS_INSET_DP] inside (eased in, like a pressed button), until the window has moved and stopped
     * (or for [LIVE_PRESS_MS] if it never moves: a tap opens its controls).
     */
    private var livePressAt = 0L
    /** The pressed window has moved since (a drag): the press lasts until it is still. */
    private var livePressMoved = false
    private var liveFrameAt = 0L
    private var liveFramesOn = false

    private val liveFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val more = stepLive(frameTimeNanos)
            applyFloating()
            if (more) choreographer.postFrameCallback(this) else { liveFramesOn = false; liveFrameAt = 0L; logLive() }
        }
    }

    // How the reports came while a window moved (logged once it is still): how often, and how long from the shell to us.
    private var liveN = 0
    private var liveFirstAt = 0L
    private var liveLastAt = 0L
    private var liveGapMax = 0L
    private var liveLateSum = 0L
    private var liveLateMax = 0L

    private fun noteLive(at: Long) {
        val late = SystemClock.uptimeMillis() - at
        if (liveN == 0) liveFirstAt = at else liveGapMax = max(liveGapMax, at - liveLastAt)
        liveLastAt = at
        liveN++
        liveLateSum += late
        liveLateMax = max(liveLateMax, late)
    }

    private fun logLive() {
        if (liveN >= 4) {
            val span = liveLastAt - liveFirstAt
            AppLog.log("[nav] live frames: $liveN reports over $span ms (every ${span / (liveN - 1)} ms, longest gap " +
                "$liveGapMax ms), ${liveLateSum / liveN} ms from the shell to us (most $liveLateMax)")
        }
        liveN = 0; liveGapMax = 0L; liveLateSum = 0L; liveLateMax = 0L
    }

    private fun floatingPressed() {
        if (phase != Phase.SWITCHER || liveExact.isEmpty()) return
        livePressAt = SystemClock.uptimeMillis()
        livePressMoved = false
        startLiveFrames()
    }

    private fun startLiveFrames() {
        if (liveFramesOn) return
        liveFramesOn = true
        choreographer.postFrameCallback(liveFrame)
    }

    /**
     * One frame of the holes' motion: a window whose reports stopped ([LIVE_QUIET_FRAMES] refreshes without one, or two and
     * a half of the usual gaps between two) has stopped: its lead runs out within a few refreshes instead of carrying the
     * hole on past it; each inset follows its window's speed. True while anything still moves.
     */
    private fun stepLive(frameTimeNanos: Long): Boolean {
        val f = frameMs()
        val dt = if (liveFrameAt == 0L) f else ((frameTimeNanos - liveFrameAt) / 1e6f).coerceIn(1f, 50f)
        liveFrameAt = frameTimeNanos
        val nowMs = frameTimeNanos / 1_000_000L
        val quiet = nowMs - liveAt > max(LIVE_QUIET_FRAMES * f, 2.5f * liveEvery)
        val stop = exp(-dt / LIVE_STOP_MS)
        val ease = 1f - exp(-dt / LIVE_INSET_EASE_MS)
        val easeIn = 1f - exp(-dt / LIVE_PRESS_IN_MS)
        val most = LIVE_INSET_MAX_DP * density
        if (livePressAt != 0L && (if (livePressMoved) quiet else nowMs - livePressAt > LIVE_PRESS_MS)) livePressAt = 0L
        val press = if (livePressAt != 0L) LIVE_PRESS_INSET_DP * density else 0f
        var moving = livePressAt != 0L
        for ((pkg, v) in liveV) {
            if (quiet) for (k in 0..1) { v[k] *= stop; if (abs(v[k]) < LIVE_STILL_PX_MS) v[k] = 0f }
            val ins = liveInset.getOrPut(pkg) { FloatArray(2) }
            for (k in 0..1) {
                // Motion takes the inset at once (the guess is off from that frame on), a press eases it in, both ease out.
                val speed = min(abs(v[k]) * f * LIVE_INSET_FRAMES, most)
                val want = max(speed, press)
                var next = ins[k]
                if (press > next) next += (press - next) * easeIn
                next = max(next, speed)
                if (next > want) next += (want - next) * ease
                if (abs(next - want) < 0.3f) next = want
                ins[k] = next
                if (v[k] != 0f || next != 0f) moving = true
            }
        }
        placeLive(nowMs)
        return moving
    }

    /**
     * Where each watched window shows when the frame drawn for [nowMs] (its vsync, uptime ms) does: on from the place
     * reported at its speed, for as long as the report is old by then plus [LIVE_PIPE_FRAMES] refreshes (a mini player
     * flung in the App Switcher left a strip of the wallpaper behind it and cut its front edge).
     */
    private fun placeLive(nowMs: Long) {
        // A report that is late by more than two of the usual gaps is not carried further (the window is stopping).
        val f = frameMs()
        val age = min((nowMs - liveAt).toFloat(), 2f * max(f, liveEvery))
        val ahead = (age + LIVE_PIPE_FRAMES * f).coerceIn(0f, LIVE_AHEAD_MAX_MS)
        live.clear()
        for ((pkg, r) in liveExact) {
            val v = liveV[pkg]
            live[pkg] = if (v == null) RectF(r) else RectF(r).apply { offset(v[0] * ahead, v[1] * ahead) }
        }
    }

    /** One refresh of the display (ms). */
    private fun frameMs(): Float =
        1000f / ((try { wm?.defaultDisplay?.refreshRate } catch (_: Throwable) { null } ?: 60f).coerceAtLeast(30f))

    /** The apps that were floating while this App Switcher was open (an app back from its mini player is one of them). */
    private val switcherFloating = HashSet<String>()

    private fun fullScreen(r: RectF) = r.width() >= sw * 0.9f && r.height() >= sh * 0.9f

    /** An app came back to the screen from its mini player while the App Switcher was open: the deck fades away over it. */
    private var switcherLeaving = false

    private fun switcherAppCameForward(pkg: String) {
        if (phase != Phase.SWITCHER || switcherLeaving) return
        switcherLeaving = true
        // The deck in the switcher's own window: the app grows over it (Android's expand) and the switcher goes once it is
        // covered (onWindowLost); gone sooner, what lies under the window showed beside the growing app.
        if (win == Win.HOLDS) { AppLog.log("[switcher] $pkg came back from its mini player: the switcher goes once covered"); return }
        AppLog.log("[switcher] $pkg came back from its mini player: the switcher makes way")
        val g = gen
        root?.animate()?.alpha(0f)?.timed(Motion.role(dev.launcher.app.motion.MotionTokens.CARD_FADE_APP))?.withEndAction { if (gen == g) hideCards() }?.start()
    }

    // ---- the app being closed goes into picture-in-picture (the mini player)

    /**
     * The app being closed home, while its card is on the way: if it goes into picture-in-picture (YouTube playing does as
     * home or the cover comes), the card flies into the mini player instead of fading out in mid-air (iOS: the video goes
     * into its window), and the mini player stays covered meanwhile: its entry (black at first, the video sliding in) is
     * not seen. Cleared once it has settled under the card ([releasePipCover]).
     */
    private var pipCoverPkg: String? = null
    /** The card is on its way into the mini player. */
    private var pipFlight = false
    /** The app whose card is under the finger goes into picture-in-picture by itself (asked as its drag began), else null. */
    @Volatile private var autoPipPkg: String? = null
    /** Where its video is (its PiP source rect hint, display px), if it said. */
    @Volatile private var autoPipHint: RectF? = null

    /** Asks the shell, off the nav thread, whether [pkg] will go into picture-in-picture as it is left, and where its video is. */
    private fun askAutoPip(pkg: String?) {
        autoPipPkg = null
        autoPipHint = null
        pkg ?: return
        val s = ShizukuLink.service ?: return
        tasksIo.execute {
            val a = (try { s.pipHint(pkg) } catch (_: Throwable) { "" }).split(' ')
            if (a.firstOrNull() != "auto") return@execute
            if (a.size >= 5) autoPipHint = RectF(a[1].toFloat(), a[2].toFloat(), a[3].toFloat(), a[4].toFloat())
            autoPipPkg = pkg
        }
    }
    // The card's corners at the end of a flight into a frame other than the screen's (the mini player's), and its alpha
    // when it set off (a fade already begun goes back up on the way).
    private var springRadiusEnd = 0f
    private var springAlpha0 = 1f

    private fun flyIntoFloating(r: RectF) {
        if (pipFlight || !animating || anim != Anim.HOME_COMMIT) return
        val c = cur ?: return
        pipFlight = true
        val t = max(0L, System.nanoTime() - springStartNs) / 1e9
        val m = floatArrayOf(sCx.value(t), sCy.value(t), sW.value(t), sH.value(t), sCx.velocity(t), sCy.velocity(t), sW.velocity(t), sH.velocity(t))
        val d0 = sDepth.value(t)
        val dv = sDepth.velocity(t)
        val dTo = sDepth.target
        val mp = Motion.profile
        // From where it is and as fast as it goes: a new target, no jump.
        sCx = mp.appClosePosition.mover().apply { start(m[0], m[4], r.centerX()) }
        sCy = mp.appClosePosition.mover().apply { start(m[1], m[5], r.centerY()) }
        sW = mp.appCloseSize.mover().apply { start(m[2], m[6], r.width()) }
        sH = mp.appCloseSize.mover().apply { start(m[3], m[7], r.height()) }
        sDepth = mp.homeDepthClose.mover().apply { start(d0, dv, dTo) }
        springStartNs = System.nanoTime()
        beginCardSprings(toIcon = false)
        springRadiusEnd = cardWindow?.holeRadius ?: pipCornerRadius()
        springAlpha0 = c.alpha
        springFade = false
        // The card closes in on the video, as Android's own entry does (the app's hint), else on the middle of the app.
        c.focus = autoPipHint?.takeIf { cardPkg == autoPipPkg } ?: RectF(0f, 0f, sw, sh)
        c.focusMix = 0f
        // It was headed into its icon: the icon is shown again (home shows it; the card no longer becomes it).
        hiddenIconPkg?.let { HomeBridge.setIconHidden(it, false) }
        hiddenIconPkg = null
        c.badge = 0
        endLabel = "home (into the mini player of $cardPkg at ${r.centerX().toInt()},${r.centerY().toInt()})"
        AppLog.log("[nav] $cardPkg went into picture-in-picture: its card flies into it")
    }

    /** The mini player under the card has settled: its window shows (the card, there too, is cut out with it). */
    private fun releasePipCover() {
        if (pipCoverPkg == null) return
        pipCoverPkg = null
        applyFloating()
    }

    /**
     * Runs [then] once the app being closed has stopped moving on the screen: its mini player at rest in its corner (its live
     * frame still for [PIP_STILL_MS]), or no mini player coming (its window still full-screen, or gone, for an app that does
     * not go into one by itself); at the latest after [maxMs]. Android hides our windows as home starts over a turned display:
     * started during the mini player's entry, that showed the video full size, turning and shrinking.
     */
    private fun whenPipSettled(g: Int, maxMs: Long, then: () -> Unit) {
        val deadline = SystemClock.uptimeMillis() + maxMs
        val waitsForPip = pipCoverPkg != null && pipCoverPkg == autoPipPkg
        fun check() {
            if (gen != g) return
            val now = SystemClock.uptimeMillis()
            val r = pipCoverPkg?.let { liveExact[it] }
            val inPip = r != null && !fullScreen(r)
            val settled = if (inPip) now - pipMovedAt >= PIP_STILL_MS else !waitsForPip
            if (settled || now >= deadline) {
                if (!settled) AppLog.log("[nav] the mini player had not settled in ${maxMs} ms: going on")
                then()
                return
            }
            nav.postDelayed({ check() }, 8)
        }
        check()
    }

    /**
     * Draws the latest kept picture of the app in front into one pixel, while idle: the first draw of a new snapshot imports
     * it into the GPU, which a trace on the S24 measured at ~17 ms (allocation + upload) inside the card's first frame.
     */
    /** The cards' container: its visibility and alpha also apply to the picture of home under it ([mirror]). */
    private class Stage(ctx: Context) : FrameLayout(ctx) {
        var mirror: View? = null
            set(v) { field = v; sync() }

        private fun sync() { mirror?.alpha = if (visibility == VISIBLE) alpha else 0f }

        override fun setVisibility(visibility: Int) { super.setVisibility(visibility); sync() }
        override fun setAlpha(alpha: Float) { super.setAlpha(alpha); sync() }
    }

    private class WarmView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dst = android.graphics.RectF(0f, 0f, 1f, 1f)
        var bitmap: Bitmap? = null
            set(v) { if (field !== v) { field = v; invalidate() } }
        /** The previous app's last picture (a sideways switch shows it). */
        var second: Bitmap? = null
            set(v) { if (field !== v) { field = v; invalidate() } }

        override fun onDraw(canvas: Canvas) {
            bitmap?.let { canvas.drawBitmap(it, null, dst, paint) }
            second?.let { canvas.drawBitmap(it, null, dst, paint) }
        }
    }
    private var windowShown = false

    /** Window alpha 1 (cards about to show) or 0 (idle: the compositor skips the window). */
    private fun setWindowShown(shown: Boolean) {
        if (shown == windowShown) return
        val host = cardWindow ?: return
        val lp = host.layoutParams as? WindowManager.LayoutParams ?: return
        lp.alpha = if (shown) 1f else 0f
        try { wm?.updateViewLayout(host, lp); windowShown = shown } catch (_: Throwable) { }
    }

    // The window stays at alpha 1 once shown, on home too (it always did while an app is in front): every change costs the
    // window manager a re-layout of 6-50 ms on the nav thread (traced on the S24), and going back to 0 on home after a pause
    // put the next change at the touch that starts a gesture (folder closed by the bar, the pull on home, a launch: their
    // first frames came late). A transparent window at alpha 1 costs the compositor one more plain layer.

    /** Home was touched: a launch may follow, so the card window must be ready (it stays so). */
    private fun readyForLaunch() = setWindowShown(true)

    /**
     * Home settled into a new look ([p]): rendered into the layers now, while nothing moves, not at the next gesture's start.
     * Not while a finger is on home (rendering home takes the render thread all our windows share for a frame or two, and a
     * swipe starting there would wait): once it has lifted, if [p] is still the latest picture.
     */
    private fun prewarmHome(p: HomePicture) {
        if (phase != Phase.IDLE || root?.visibility == View.VISIBLE || !homeVisible || HomeBridge.preview !== p) return
        if (HomeBridge.touching) { nav.postDelayed({ prewarmHome(p) }, PREWARM_RETRY_MS); return }
        backdrop?.prewarm(p)
    }

    private fun showCards() {
        val r = root ?: return
        if (backdrop?.picture != null) HomeBridge.homeCovered = true
        r.animate().cancel()
        r.alpha = 1f
        setWindowShown(true)
        if (r.visibility != View.VISIBLE) {
            r.visibility = View.VISIBLE
            cardVisibleAfter = SystemClock.uptimeMillis() - dragStartedAt
            pollFloating()   // the holes for floating windows: the kept answer shows at once, a fresh one follows
            onCardShown()
        }
    }

    // ---- a home card that appears late: its snapshot takes 50-300 ms; until then the app itself is what the user sees

    // A picture of the app in front younger than this is shown at once by a home gesture; kept that young in the background.
    private const val RECENT_MS = 10_000L
    private const val KEEP_FRESH_MS = 6_000L
    /** The periodic captures' times are summed up in the log after this many ([noteKeptCapture]). */
    private const val KEPT_STATS_EVERY = 20
    private const val KEEP_FRESH_SETTLE_MS = 400L
    private const val SWITCHER_REFRESH_SETTLE_MS = 150L
    private const val KEEP_FRESH_MIN_GAP_MS = 1_200L
    private var catchUpAt = 0L          // nanoTime when a late card appeared during a drag (0 = not catching up)
    private var catchUpTiming: dev.launcher.app.motion.CurveTiming? = null   // its curve (`motion.card.catch-up`)
    private var lastDragX = 0f
    private var lastDragY = 0f
    private var deferredRelease: FloatArray? = null   // [vx, vy] of a close released before its card could show
    private val releaseTimeout = Runnable { if (deferredRelease != null) showCards() }   // never wait longer: show the stand-in

    private fun onCardShown() {
        if (phase != Phase.DRAG_HOME) return
        val v = deferredRelease
        if (v != null) {
            // Released before the card could show: close now, from full size (exactly where the app is).
            deferredRelease = null
            nav.removeCallbacks(releaseTimeout)
            cur?.setFrame(sw / 2, sh / 2, sw, sh, deviceRadius)
            releaseHome(true, v[0], v[1])
            return
        }
        // Only a card that is late (the finger has already travelled): one that shows at the start just follows the finger.
        if (fingerDown && travel0 <= 1f && lastTravel > dp(24)) {
            catchUpAt = System.nanoTime()
            catchUpTiming = dev.launcher.app.motion.CurveTiming(Motion.role(dev.launcher.app.motion.MotionTokens.CARD_CATCH_UP))
            dragHome(lastDragX, lastDragY)   // this very frame: full screen, exactly where the app is
            choreographer.postFrameCallback(catchUpFrame)
        }
    }

    private val catchUpFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (catchUpAt == 0L || phase != Phase.DRAG_HOME) { catchUpAt = 0L; return }
            dragHome(lastDragX, lastDragY)   // advances the blend even while the finger rests
            if (catchUpAt != 0L) choreographer.postFrameCallback(this)
        }
    }

    /**
     * Home's picture behind [pkg]'s card going home: without its icon when the card goes into it, with it when the closing
     * style leaves it on home (sliding down, fading: the icon was missing from the dock until the real home showed, then
     * appeared at once).
     */
    private fun closePicture(pkg: String?): HomePicture? =
        if (turned || AppStyles.closing().fromIcon) HomeBridge.previewFor(pkg) else HomeBridge.preview ?: HomeBridge.previewFor(pkg)

    /** Only the current card draws in the cards' container, one picture at a time: a fade of the container can apply to its draws. */
    private fun drawsAlone(r: android.view.ViewGroup): Boolean {
        val c = cur ?: return false
        for (i in 0 until r.childCount) {
            val v = r.getChildAt(i)
            if (v === c || v is CardBadgeView || v.visibility != View.VISIBLE) continue
            return false
        }
        return c.visibility == View.VISIBLE && !c.hasOverlappingRendering()
    }

    /** Ends the card session: window invisible (kept), icon back, everything pending forgotten. */
    private fun hideCards() {
        if (win != Win.NONE) endSwitcherWindow()
        catchTouchesForHome(false)
        pipFlight = false
        switcherLeaving = false
        switcherFloating.clear()
        pipCoverPkg = null
        // A cover asked for a close that never started home has nothing to hand over to.
        if (coverAsked && !homeStarted) TurnCover.dismiss()
        coverAsked = false
        pillAwayForTurn = false
        gen++
        animating = false
        onSettled = null
        waitingFor = null
        nav.removeCallbacks(waitTimeout)
        phase = Phase.IDLE
        updateWatch()
        anim = Anim.NONE
        pendingFresh = false
        grabbedFull = false
        pendingStart = null
        appStarted = false
        catchUpAt = 0L
        deferredRelease = null
        nav.removeCallbacks(releaseTimeout)
        root?.let { r ->
            r.animate().cancel()
            r.visibility = View.INVISIBLE
            r.alpha = 1f
            r.setBackgroundColor(0)
            r.forceHasOverlappingRendering(true)   // (as a container is: see fadeOutCards)
        }
        backdrop?.picture = null
        HomeBridge.homeCovered = false
        pictureDropped = false
        closeDepthFrom = -1f
        closeFading = false
        softPicture = null
        cur?.flatFade = false
        endLander()   // (after the picture went: its patch is then taken out without rendering home again for nothing)
        prv?.visibility = View.GONE
        nxt?.visibility = View.GONE
        cur?.snapshot = null
        cur?.homePicture = null
        cur?.iconTurn = 0f
        cur?.focus = null
        cur?.focusMix = 0f
        switchFromHome = false
        // A card that ended inside an icon is fully "icon": reused as is, the next switch drew a huge icon over the snapshot.
        cur?.iconMix = 0f
        cur?.clearStyle()
        flightStyle = AppStyle.ZOOM
        grabDev.clear()
        grabSettle = null
        styleDev0.clear()
        prv?.iconMix = 0f
        nxt?.iconMix = 0f
        cur?.alpha = 1f
        springFade = false
        cardPkg = null
        homeStarted = false
        nav.removeCallbacks(holdCheck)
        switcherHeld = false
        switcherBg.snapTo(0f)
        if (pendingRemovals.isNotEmpty()) { nav.removeCallbacks(removeNow); removeNow.run() }   // the switcher is gone
        pullBack.stop()
        pulling = false
        deck?.clear()
        input?.hide()
        hiddenIconPkg?.let { HomeBridge.setIconHidden(it, false) }
        hiddenIconPkg = null
        releaseScalesLater()
    }

    private fun removeAll() {
        removeStatusBar()
        removeNav()
    }

    /** The lock screen came: the strip goes, the cards are put away; the card window and its input stay (see [update]). */
    private fun removeStrip() {
        hideCards()
        nav.removeCallbacks(scalesBack)
        ShizukuLink.service?.let { s -> frontIo.execute { restoreScales(s) } }
        nav.removeCallbacks(floatingPoll)
        strip?.let { try { wm?.removeView(it) } catch (_: Throwable) { } }
        strip = null
        AppLog.log("[nav] gesture strip off (the card window stays, under the shade)")
    }

    /** The gesture strip and the card window go (gestures off; the shade goes too, see [removeAll]). */
    private fun removeNav() {
        hideCards()
        nav.removeCallbacks(scalesBack)
        ShizukuLink.service?.let { s -> frontIo.execute { restoreScales(s) } }
        nav.removeCallbacks(floatingPoll)
        strip?.let { try { wm?.removeView(it) } catch (_: Throwable) { } }
        cardWindow?.let { try { wm?.removeView(it) } catch (_: Throwable) { } }
        input?.quit()
        strip = null
        // (The cover was in that window: a new one is made in the next, and any hand-back under way gives way.)
        coverGen++; homeCover = null
        cardWindow = null; windowShown = false; warm = null
        root = null; backdrop = null; prv = null; nxt = null; cur = null; land = null; deck = null; input = null
        AppLog.log("[nav] gesture strip off")
    }

    /**
     * The radius Android gives a mini player's corners (px): SystemUI's own `pip_corner_radius` (One UI 8: 12 dp, AOSP 16
     * dp). The hole the card window leaves for it and the card flying into it take it: at 16 dp on the S24 its corners
     * changed whenever the App Switcher's deck moved between the card window and its own window (Matheesha).
     */
    private fun pipCornerRadius(): Float = try {
        val res = app.packageManager.getResourcesForApplication("com.android.systemui")
        val id = res.getIdentifier("pip_corner_radius", "dimen", "com.android.systemui")
        if (id != 0) res.getDimension(id) else dp(16)
    } catch (_: Throwable) { dp(16) }

    private fun systemDimen(name: String): Int {
        val id = app.resources.getIdentifier(name, "dimen", "android")
        return if (id != 0) app.resources.getDimensionPixelSize(id) else 0
    }

    /** Runs [then] (nav thread) once the card window has drawn a frame and that frame has been queued, unless the session moved on. */
    private fun afterCardFrame(g: Int, then: () -> Unit) {
        val r = root ?: return then()
        var done = false
        val run = { if (!done) { done = true; if (gen == g) then() } }
        r.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                nav.post { r.viewTreeObserver.removeOnDrawListener(this) }
                choreographer.postFrameCallback { run() }
            }
        })
        r.invalidate()
        nav.postDelayed({ run() }, 100)   // never later than this
    }

    private fun hideIcon(pkg: String?) {
        if (pkg == hiddenIconPkg) return
        hiddenIconPkg?.let { HomeBridge.setIconHidden(it, false) }
        hiddenIconPkg = pkg
        pkg?.let { HomeBridge.setIconHidden(it, true) }
    }

    private fun setCardContent(c: CardView, pkg: String?, bitmap: Bitmap?) {
        c.homePicture = null
        c.snapshot = bitmap
        c.icon = pkg?.let { iconFor(it) }
        c.badge = pkg?.let { if (HomeBridge.showsBadge(it)) Badges.count(it) else 0 } ?: 0
        c.unitPx = dev.launcher.app.design.Scale.unitPx(app)
        c.minIconSize = Icons.homeSize.toFloat()
        // Without a snapshot the card shows the app's launch screen: its splash colour behind its icon.
        if (pkg != null) {
            c.placeholderColor = SplashColors.cached(pkg) ?: DEFAULT_SPLASH
            if (SplashColors.cached(pkg) == null) SplashColors.resolve(app, pkg) { col -> nav.post { if (cardPkgFor(c) == pkg) c.fadePlaceholderTo(col) } }
        }
    }

    /**
     * The card's app is known now, after its picture (a fresh one taken from the task already known comes before the
     * recent-tasks lookup): its icon and launch-screen colour, if the card began without them. Without its icon a close
     * crossfaded into nothing, and the real icon appeared a moment later, once home had drawn.
     */
    private fun cardKnows(c: CardView, pkg: String) {
        if (c.icon != null) return
        c.icon = iconFor(pkg)
        SplashColors.cached(pkg)?.let { c.placeholderColor = it }
    }

    /** Which app a card currently shows (to drop late splash colours that arrive after the card moved on). */
    private fun cardPkgFor(c: CardView): String? = when (c) {
        cur -> cardPkg
        prv -> older?.pkg
        nxt -> newer?.pkg
        else -> null
    }

    // ================================================================== touch (nav thread)

    // A touch on the bar that landed during a close: ignored until the finger lifts (a closing card is not grabbed).
    private var ignoringTouch = false

    /** A close is under way (flying into home, settling, or waiting for its late card): the bar does not take it over. */
    private fun closing() = (anim == Anim.HOME_COMMIT && root?.visibility == View.VISIBLE) || deferredRelease != null

    private fun onTouch(e: MotionEvent): Boolean {
        if (phase == Phase.SWITCHER) return onTouchInSwitcher(e)
        if (e.actionMasked == MotionEvent.ACTION_DOWN) ignoringTouch = closing()
        if (ignoringTouch) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) ignoringTouch = false
            return true
        }
        val raw = MotionEvent.obtain(e).apply { setLocation(e.rawX, e.rawY) }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (homeCover?.visibility == View.VISIBLE) uncover("a touch on the bar")   // a gesture's cards are under it
                vt?.recycle()
                vt = VelocityTracker.obtain().also { it.addMovement(raw) }
                downX = e.rawX
                downY = e.rawY
                fingerDown = true
                stats.reset()
                if ((phase == Phase.ANIM || phase == Phase.HOLD) && root?.visibility == View.VISIBLE && !(switchFromHome && anim == Anim.SWITCH_CANCEL)) takeOver(e.rawX, e.rawY)
                else {
                    finishHomePull()
                    hideCards(); pendingFresh = true; prefetch(fresh = true)
                    // Over an app the touch may be a tap for the app (see tapToApp): kept until it is known whose it is.
                    if (!homeVisible) forward.reset(e)
                    // On home: it is recorded as it shows right now and rendered ahead, so a swipe up can show it receding from
                    // its first frame (the picture from when home last came to rest can be out of date: a folder opened since).
                    // Something open on top of home (the widget gallery, a menu, edit mode, Spotlight): the swipe closes it
                    // with its own motion (the picture of home lacked the gallery: it vanished at the touch, then slid away
                    // again under the picture springing back).
                    closesOnTop = homeVisible && HomeBridge.hasOnTop()
                    if (homeVisible && closesOnTop) pullPicture = null
                    if (homeVisible && !closesOnTop) {
                        val id = gestureId
                        pullPicture = null
                        HomeBridge.recordForGesture {
                            val pic = HomeBridge.preview
                            nav.post {
                                if (id != gestureId) return@post
                                pullPicture = pic
                                if (phase == Phase.HOME_PULL && !pulling) showHomePull() else if (root?.visibility != View.VISIBLE) backdrop?.prewarm(pic)
                            }
                        }
                    }
                }
                // The card window becomes visible to the compositor now, while the finger is still starting its swipe: the
                // window manager takes ~32 ms for it on the S24, which used to land on the card's first frame.
                setWindowShown(true)
                // At the first touch, so they are off well before the gesture commits and home or another app starts.
                holdScalesOff()
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(raw)
                val dx = e.rawX - downX
                val dy = downY - e.rawY
                if (pendingFresh && !homeVisible) forward.hold(e)
                if (pendingFresh && !homeVisible) {
                    if (dy > dp(10) && dy > abs(dx)) { pendingFresh = false; beginHome() }
                    else if (abs(dx) > dp(14) && abs(dx) > abs(dy) * 1.2f) { pendingFresh = false; beginSwitch() }
                } else if (pendingFresh && homeVisible && dy > dp(10) && dy > abs(dx)) {
                    // On home, a swipe up: a hold opens the App Switcher; lifting does what the Home button does (leaves edit
                    // mode, closes Spotlight, first page), as on iOS.
                    pendingFresh = false
                    beginHomePull()
                } else if (pendingFresh && homeVisible && abs(dx) > dp(14) && abs(dx) > abs(dy) * 1.2f) {
                    // On home, sideways brings the last app in beside home, with the finger (home slides away as a card).
                    pendingFresh = false
                    beginSwitch(fromHome = true)
                } else if (grabbedFull) {
                    if (dy > dp(8) && dy > abs(dx)) { grabbedFull = false; phase = Phase.DRAG_HOME }
                    else if (abs(dx) > dp(12) && abs(dx) > abs(dy) * 1.2f) { grabbedFull = false; beginSwitch() }
                }
                when (phase) {
                    Phase.DRAG_HOME -> { dragHome(e.rawX, e.rawY); stats.touchEvent(e.eventTime); watchForHold() }
                    Phase.DRAG_SWITCH -> { dragSwitch(e.rawX); stats.touchEvent(e.eventTime) }
                    Phase.HOME_PULL -> { lastDragX = e.rawX; lastDragY = e.rawY; pullHome(); watchForHold() }
                    else -> {}
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(raw)
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                val vy = vt?.yVelocity ?: 0f
                val up = e.actionMasked == MotionEvent.ACTION_UP
                nav.removeCallbacks(holdCheck)
                // A touch over an app that started nothing of ours (a tap) is the app's.
                if (pendingFresh && !homeVisible && up) tapToApp(e)
                when {
                    phase == Phase.HOME_PULL -> releaseHomePull(up)
                    phase == Phase.DRAG_HOME -> releaseHome(up, vx, vy)
                    phase == Phase.DRAG_SWITCH -> releaseSwitch(up, vx)
                    grabbedFull -> { grabbedFull = false; releaseHome(false, 0f, 0f) }   // a tap on a full-size card: let it finish
                }
                pendingFresh = false
                fingerDown = false
                releaseScalesLater()   // a touch that started no card session; otherwise the session's end reschedules it
            }
        }
        raw.recycle()
        return true
    }

    /**
     * A touch while cards are animating or waiting: the card stops exactly where it is and the finger owns it. A switch in
     * flight is completed in place first (its app is already coming to the front), so the finger holds that app's card.
     */
    private fun takeOver(x: Float, y: Float) {
        gen++
        val wasAnim = anim
        animating = false
        onSettled = null
        waitingFor = null
        nav.removeCallbacks(waitTimeout)
        runPendingStart()   // the finger took over a launching card: the user still opened that app
        // What its style draws stays with the card while the finger holds it (its fade only in a fading style: a soft start's
        // goes at once, as before). A style that draws a frame of its own (a circle reveal's window, a genie): the finger takes
        // the plain card the flight is built on, the style kept on it.
        val flight = wasAnim == Anim.LAUNCH || wasAnim == Anim.HOME_COMMIT || wasAnim == Anim.HOME_CANCEL
        cur?.let { if (flight) grabDev.grab(it, plainFrame, plainR, sw, sh) else grabDev.of(it) } ?: grabDev.clear()
        if (flightStyle != AppStyle.FADE) grabDev.alpha = 0f
        flightStyle = AppStyle.ZOOM
        springFade = false
        cur?.alpha = 1f
        root?.animate()?.cancel()
        root?.alpha = 1f
        val c = cur ?: return
        if (wasAnim == Anim.SWITCH_COMMIT) {
            // The app being switched to is the one coming to the front: its card becomes the current card at full size.
            val from = if (switchTargetIsOlder) prv else nxt
            c.homePicture = null
            c.snapshot = from?.snapshot
            c.icon = from?.icon
            c.placeholderColor = from?.placeholderColor ?: DEFAULT_SPLASH
            switchFromHome = false
            cardPkg = switchTarget?.pkg
            fg = switchTarget
            prev = null
        }
        if (wasAnim == Anim.SWITCH_COMMIT || wasAnim == Anim.SWITCH_CANCEL) {
            prv?.visibility = View.GONE
            nxt?.visibility = View.GONE
            root?.setBackgroundColor(0)
            c.setFrame(sw / 2, sh / 2, sw, sh, deviceRadius)
            backdrop?.picture = HomeBridge.previewFor(cardPkg)
            backdrop?.depth = 1f
        }
        // The card the finger holds: as drawn, or the plain one under a style's own frame.
        val held = if (grabDev.framed) RectF(plainFrame) else RectF(c.cx - c.w / 2, c.cy - c.h / 2, c.cx + c.w / 2, c.cy + c.h / 2)
        val s = held.width() / sw
        // A launch card that already covers the screen: home behind it is shown as it is now, not as it was at the tap
        // (opened from Spotlight, the search has ended behind the app since: the close showed Spotlight, then home snapped
        // to the first page). Unseen while the card covers everything.
        if (wasAnim == Anim.LAUNCH && s > 0.97f) closePicture(cardPkg)?.let { backdrop?.picture = it }
        if (backdrop?.picture == null) backdrop?.picture = closePicture(cardPkg)
        pictureDropped = false
        travel0 = travelForScale(s)
        lastTravel = travel0
        // The card may be smaller, squarer, rounder or more "icon" than the drag model can produce (e.g. grabbed while
        // flying into an icon). Keep it exactly as it is and blend towards the model as the finger pulls it back down.
        grabK = s / homeScale(travel0)
        grabHK = (held.height() / held.width()) / (sh / sw)
        grabR = if (grabDev.framed) plainR else c.radius
        grabMix = c.iconMix
        grabDepth = backdrop?.depth ?: 1f
        anchorX = (held.centerX() - x) / s
        anchorBottom = (held.bottom - y) / s
        heldFrame.set(held)
        heldR = grabR
        lastDragX = x
        lastDragY = y
        startGrabSettle()
        dragStartedAt = SystemClock.uptimeMillis()
        // Tasks may have changed (an app was just launched or switched to): look them up again, keep the card's image.
        prefetch(fresh = false)
        if (s > 0.97f) { grabbedFull = true; phase = Phase.IDLE }   // direction still open: up = home, sideways = switch
        else phase = Phase.DRAG_HOME
        stats.start()
        AppLog.log("[nav] took over a ${wasAnim.name.lowercase()} card at scale ${"%.2f".format(s)}")
    }

    /**
     * Looks up the foreground and previous task, then (on the snapshot worker) a fresh snapshot of the foreground app
     * (only if [fresh]) and a cached one of the previous app.
     */
    private fun prefetch(fresh: Boolean) {
        val s = ShizukuLink.service ?: return
        val id = ++gestureId
        val onHome = homeVisible
        fg = if (fresh) null else fg
        prev = null
        if (fresh) { fgFresh = null; fgFreshDone = false; fgSnapMs = -1 }
        prvSnapshot = null
        // The app in front changed its screen since our latest picture of it: its fresh picture is taken right now, from the
        // task we already know, instead of after the recent-tasks lookup (~50 ms later).
        val known = frontTask?.takeIf { fresh && !onHome && it.pkg == lastFrontPkg && keptIsStale(it.pkg) }
        if (known != null) snapIo.execute {
            val t = SystemClock.uptimeMillis()
            val b = try { snapshot(s, known.id, true) } catch (_: Throwable) { null }
            val ms = SystemClock.uptimeMillis() - t
            nav.post { if (id == gestureId) onFgFresh(known.pkg, b, ms) else b?.let { remember(known.pkg, it, t) } }
        }
        tasksIo.execute {
            val tasks = try { parseTasks(s.recentTasks(8)) } catch (_: Throwable) { emptyList() }
            if (id != gestureId) return@execute
            tasks.take(2).forEach { iconFor(it.pkg) }
            nav.post {
                if (id != gestureId) return@post
                fg = if (fresh) (lastFrontPkg?.let { p -> tasks.firstOrNull { it.pkg == p } } ?: tasks.getOrNull(0))
                     else cardPkg?.let { p -> tasks.firstOrNull { it.pkg == p } } ?: fg?.takeIf { it.pkg == cardPkg }
                prev = tasks.switchable().firstOrNull { it.pkg != fg?.pkg && it.id != fg?.id }
                recentList = tasks
                onTasks(fresh)
                if (phase == Phase.DRAG_SWITCH && switchFromHome) refreshHomeSwitch()
            }
            // The App Switcher's cards (a hold may follow, also on home): the system's snapshots of the recent apps beyond
            // the two fetched below, only for apps used since we last fetched theirs (see havePrev), so a long run of
            // gestures fetches nothing new.
            if (fresh) snapIo.execute {
                for (t in tasks.switchable().drop(if (onHome) 0 else 2).take(SWITCHER_PREFETCH)) {
                    if (id != gestureId) return@execute
                    if (images[t.pkg] != null && (systemPictureAt[t.pkg] ?: 0L) > (leftFrontAt[t.pkg] ?: Long.MAX_VALUE)) continue
                    val b = try { snapshot(s, t.id, false) } catch (_: Throwable) { null } ?: continue
                    remember(t.pkg, b); systemPictureAt[t.pkg] = SystemClock.uptimeMillis()
                    val tid = t.id
                    nav.post { if (phase == Phase.SWITCHER) deck?.updateSnapshot(tid, b) }
                }
            }
            if (onHome || tasks.isEmpty()) return@execute
            // Only pictures we do not have yet. Every snapshot counts as ~10 MB of native memory to the runtime: fetching
            // three per gesture (a fresh one of the app in front though the card keeps its recent one, the system's copy of
            // it, the previous app's again) set off garbage collections that paused the whole process for ~5 ms in the middle
            // of closes (S24 log: "NativeAlloc concurrent mark compact GC ... paused 5.3ms"). A fresh capture also kept the
            // GPU busy (the system renders it) during the gesture's first frames.
            val fgPkg = tasks[0].pkg
            frontTask = tasks[0]
            val haveFg = known?.pkg == fgPkg || (recentImage(fgPkg) != null && !keptIsStale(fgPkg))
            val p = tasks.getOrNull(1)
            val havePrev = p != null && (systemPictureAt[p.pkg] ?: 0L) > (leftFrontAt[p.pkg] ?: Long.MAX_VALUE)
            snapIo.execute {
                if (fresh) when {
                    known?.pkg == fgPkg -> {}   // its fresh picture is already on its way (started at the touch)
                    haveFg -> nav.post { if (id == gestureId) onFgFresh(fgPkg, null, 0L) }
                    else -> {
                        val t = SystemClock.uptimeMillis()
                        val b = try { snapshot(s, tasks[0].id, true) } catch (_: Throwable) { null }
                        val ms = SystemClock.uptimeMillis() - t
                        nav.post { if (id == gestureId) onFgFresh(fgPkg, b, ms) else b?.let { remember(fgPkg, it, t) } }
                    }
                }
                p ?: return@execute
                val pb = if (havePrev) null else try { snapshot(s, p.id, false) } catch (_: Throwable) { null }
                if (pb != null) { remember(p.pkg, pb); systemPictureAt[p.pkg] = SystemClock.uptimeMillis() }
                nav.post { if (id == gestureId) prvSnapshot = pb ?: images[p.pkg] }
            }
            // The system's last snapshot of the app in front (a stand-in for a sideways switch until the fresh one arrives),
            // on its own worker: it can take a few hundred ms and must delay neither the fresh snapshot nor the next lookup.
            if (fresh && !haveFg) cachedIo.execute {
                val cached = try { snapshot(s, tasks[0].id, false) } catch (_: Throwable) { null }
                if (cached != null) nav.post { if (id == gestureId) onFgCached(tasks[0].pkg, cached) }
            }
        }
        // Every recent app, for the App Switcher (a hold may follow): after the quick lookup above, on the same worker (the
        // gesture itself needs only the first few).
        if (fresh) tasksIo.execute {
            if (id != gestureId) return@execute
            val t0 = SystemClock.uptimeMillis()
            val all = try { parseTasks(s.recentTasks(SWITCHER_MAX_CARDS)) } catch (_: Throwable) { emptyList() }
            val ms = SystemClock.uptimeMillis() - t0
            nav.post {
                if (all.isEmpty()) return@post
                recentAll = all
                recentAllMs = ms
                if (phase == Phase.SWITCHER) addOlderCards()
            }
        }
    }

    private fun parseTasks(lines: Array<String>) = lines.mapNotNull { line ->
        val parts = line.split(' ')
        parts.getOrNull(0)?.toIntOrNull()?.let { Task(it, parts.getOrElse(1) { "?" }, parts.getOrNull(2)?.takeIf { c -> c != "?" }) }
    }

    /**
     * The apps to switch between or go back to: not our own tasks (home's, the test and expert screens'), except the
     * launcher's settings, an app of its own (Matheesha: it belongs in the App Switcher).
     */
    private fun List<Task>.switchable() = filter { it.pkg != app.packageName || it.cls == SETTINGS_CLASS }

    // The home screen's shaped icon when it has one (the card must turn into exactly what home shows), else the system's.
    private fun iconFor(pkg: String): Drawable? = Icons.drawableFor(pkg) ?: icons[pkg] ?: try {
        app.packageManager.getApplicationIcon(pkg).also { icons[pkg] = it }
    } catch (_: Throwable) { null }

    /** Tasks known. A fresh gesture's card can show at once if we already have an image of that app. */
    private fun onTasks(fresh: Boolean) {
        val f = fg ?: return
        if (!fresh || switchFromHome) return   // a switch from home keeps home as its card
        if (phase == Phase.DRAG_SWITCH && runAwaitsLookup) {
            // The run started before this lookup: built again from it, the neighbours put right while the finger still drags.
            runAwaitsLookup = false
            val o = older?.pkg
            newRun(f)
            if (switchList.getOrNull(1)?.pkg != o) { showNeighbours(); layoutSwitch(offset, switchScale) }
        }
        if (phase == Phase.DRAG_HOME || phase == Phase.DRAG_SWITCH) {
            cardPkg = f.pkg
            // The latest picture at once, even if the app changed since: the card follows the finger from the first frame and
            // the fresh picture replaces it as soon as it arrives (onFgFresh). Waiting for it left the card ~80 ms late.
            if (fgFresh == null) recentImage(f.pkg)?.let { cur?.let { c -> setCardContent(c, f.pkg, it) } }
            cur?.let { cardKnows(it, f.pkg) }
            if (phase == Phase.DRAG_HOME) backdrop?.picture = closePicture(f.pkg)
            maybeShow()
        }
    }

    /** The system's last snapshot of the app in front: a stand-in until the fresh one arrives (only for a sideways switch). */
    private fun onFgCached(pkg: String, b: Bitmap) {
        if (fgFresh != null || phase != Phase.DRAG_SWITCH || cardPkg != pkg) return
        cur?.let { c -> if (c.snapshot == null) { c.snapshot = b; maybeShow() } }
    }

    private fun onFgFresh(pkg: String, b: Bitmap?, ms: Long) {
        fgSnapMs = ms
        fgFresh = b
        fgFreshDone = true
        // Whether the card shows the kept picture, decided before the fresh one replaces it as the app's latest (that order
        // was reversed: the check always failed and every card swapped pictures mid-motion).
        // [b] null with [ms] 0: no capture was taken because a recent picture is kept (see prefetch).
        val showsKept = cur?.snapshot?.let { it === recentImage(pkg) } == true
        val keptStale = keptIsStale(pkg)
        b?.let { remember(pkg, it, SystemClock.uptimeMillis() - ms) }
        if (phase == Phase.SWITCHER && b != null) fg?.let { t -> deck?.updateSnapshot(t.id, b) }
        if (phase == Phase.DRAG_HOME || phase == Phase.DRAG_SWITCH || anim == Anim.HOME_CANCEL || anim == Anim.HOME_COMMIT) {
            cur?.let { c ->
                // A card already showing an up-to-date picture of this app keeps it (a new picture's first draw costs a GPU
                // import in the middle of the motion); an out-of-date one is replaced.
                if (b != null && (!showsKept || keptStale)) { c.fadeToSnapshot(b); if (cardPkg == null || cardPkg == pkg) cardKnows(c, pkg) }
                else if (b == null && c.snapshot == null) {
                    val kept = recentImage(pkg)
                    if (kept != null) c.fadeToSnapshot(kept) else c.icon = fg?.let { iconFor(it.pkg) }
                }
            }
            maybeShow()
        }
    }

    /** Shows the cards once the current card has real content (fresh snapshot, an earlier image, or "none available"). */
    private fun maybeShow() {
        val c = cur ?: return
        if (c.snapshot != null || fgFreshDone) showCards()
    }

    // ================================================================== HOME

    private fun beginHome() {
        if (!prepareCardWindow()) return
        gen++
        phase = Phase.DRAG_HOME
        anim = Anim.NONE
        homeStarted = false
        homeRequestedAt = 0L
        dragStartedAt = SystemClock.uptimeMillis()
        cardVisibleAfter = -1
        val c = cur ?: return
        val f = fg
        cardPkg = f?.pkg
        // Whether it will go into picture-in-picture as it is left (its close then flies into the mini player).
        askAutoPip(f?.pkg ?: lastFrontPkg)
        setCardContent(c, f?.pkg, fgFresh ?: recentImage(f?.pkg))
        c.iconMix = 0f
        c.clearStyle()
        grabDev.clear()
        grabSettle = null
        flightStyle = AppStyle.ZOOM
        c.setFrame(sw / 2, sh / 2, sw, sh, deviceRadius)
        prv?.visibility = View.GONE
        root?.setBackgroundColor(if (HomeBridge.previewFor(f?.pkg) == null) 0xFF101418.toInt() else 0)
        // Going home in a dolly zoom: home's picture flies back out of the icon the card goes into (the middle if unknown).
        val dollyAt = f?.pkg?.let { HomeBridge.iconRect(it) }?.let { homeToScreen(it) }
        backdrop?.setDolly(AppStyles.closing() == AppStyle.DOLLY, dollyAt?.centerX() ?: (sw / 2), dollyAt?.centerY() ?: (sh / 2))
        backdrop?.picture = closePicture(f?.pkg)
        backdrop?.depth = 1f
        travel0 = 0f
        lastTravel = 0f
        resetGrab()
        anchorX = sw / 2 - downX
        anchorBottom = sh - downY
        maybeShow()
        stats.start()
        holdSince = 0L
        nav.removeCallbacks(holdCheck)
    }

    // Card scale for upward travel: ease-out (shrinks quickly first, then slower).
    private fun homeScale(travel: Float): Float = 1f - SCALE_RANGE * (1f - exp(-max(0f, travel) / (sh * SCALE_LENGTH)))

    private fun travelForScale(s: Float): Float {
        val f = ((1f - s) / SCALE_RANGE).coerceIn(0f, 0.98f)
        return -ln(1f - f) * sh * SCALE_LENGTH
    }

    private fun dScaleDTravel(travel: Float): Float = -SCALE_RANGE / (sh * SCALE_LENGTH) * exp(-max(0f, travel) / (sh * SCALE_LENGTH))

    // A grabbed card's departure from the drag model (1 = none): scale factor, aspect factor, corner radius, icon blend.
    private var grabK = 1f
    private var grabHK = 1f
    private var grabR = 0f
    private var grabMix = 0f
    private var grabDepth = 1f

    private fun resetGrab() { grabK = 1f; grabHK = 1f; grabR = deviceRadius; grabMix = 0f; grabDepth = 1f }

    private fun dragHome(x: Float, y: Float) {
        val c = cur ?: return
        lastDragX = x
        lastDragY = y
        lastTravel = travel0 + (downY - y)
        // 1 at the grab point (and above it), 0 at full size: a grabbed card keeps its look and turns into a plain card
        // only as it is pulled back towards full screen.
        val b = if (travel0 > 1f) (lastTravel / travel0).coerceIn(0f, 1f) else 0f
        val s = homeScale(lastTravel) * (1f + (grabK - 1f) * b)
        val w = sw * s
        val h = sh * s * (1f + (grabHK - 1f) * b)
        // The finger keeps its place on the card as it shrinks, so the card visibly comes away from the top edge.
        var cx = x + anchorX * s
        var cy = y + anchorBottom * s - h / 2
        var fw = w
        var fh = h
        if (catchUpAt != 0L) {
            // A late card glides from full screen (where the app is) to the finger.
            val ct = catchUpTiming
            val e = ((System.nanoTime() - catchUpAt) / 1e6).toFloat()
            val k = ct?.atMs(e)?.coerceIn(0f, 1f) ?: 1f
            cx = sw / 2 + (cx - sw / 2) * k
            cy = sh / 2 + (cy - sh / 2) * k
            fw = sw + (fw - sw) * k
            fh = sh + (fh - sh) * k
            if (ct == null || e >= ct.durationMs) catchUpAt = 0L
        }
        val fr = deviceRadius + (grabR - deviceRadius) * b
        c.setFrame(cx, cy, fw, fh, fr)
        c.iconMix = grabMix * b
        if (grabDev.any) applyGrab(c, grabDev, b * grabStyleK(), cx, cy, fw, fh, fr)
        // Home comes forward as the card shrinks (less zoom, less blur), as on iOS; release springs on from here. A grabbed card
        // keeps the depth home had when it was grabbed and blends into this as it is pulled back (like its size and corners).
        val dragDepth = 1f - DRAG_DEPTH_RANGE * ((1f - homeScale(lastTravel)) / SCALE_RANGE).coerceIn(0f, 1f)
        backdrop?.depth = dragDepth + (grabDepth - dragDepth) * b
    }

    private fun releaseHome(up: Boolean, vx: Float, vy: Float) {
        val c = cur ?: run { hideCards(); return }
        val upSpeed = -vy
        val commit = up && ((upSpeed > 350f && lastTravel > dp(30)) || (lastTravel > sh * 0.2f && upSpeed > -250f))
        catchUpAt = 0L
        if (phase == Phase.DRAG_HOME && root?.visibility != View.VISIBLE && travel0 <= 1f) {
            if (!commit) { hideCards(); return }
            deferredRelease = floatArrayOf(vx, vy)
            nav.removeCallbacks(releaseTimeout)
            nav.postDelayed(releaseTimeout, 350)
            AppLog.log("[nav] home released before the card could show: closing as soon as it is there")
            return
        }
        val ds = dScaleDTravel(lastTravel) * upSpeed
        val vW = sw * ds
        val vH = sh * ds
        val g = gen
        if (commit) {
            // Home is upright only. Started now over a display turned for an app in landscape, Android would turn the display
            // as soon as home is up, in the middle of the card's flight (the rest of the close drawn sideways): over a turned
            // display home starts as the card lands, and the display turns once home has taken over (a cut: by then home,
            // shown turned to the phone's natural way up as our picture of it was, already looks right; iOS turns nothing).
            val deferHome = turned
            if (!deferHome) { if (homeVisible && !appStarted) homeRequestedAt = 0L else startHome() }
            // Android hides our windows from home's start until the display has turned: something under the card must
            // look like home by then (TurnCover).
            else { showTurnCover(); pillAwayForTurn = true }
            val pkg = cardPkg
            // If it goes into picture-in-picture on the way, the card flies there (see flyIntoFloating): looked for soon.
            pipCoverPkg = pkg
            pipFlight = false
            pipMovedAt = 0L
            updateWatch()
            for (d in longArrayOf(90, 160, 240, 330, 450)) nav.postDelayed({ if (gen == g && pipCoverPkg != null) refreshFloating() }, d)
            val iconAt = pkg?.let { HomeBridge.iconRect(it) }?.let { homeToScreen(it) }
            // How it goes (motion/AppStyles): over a turned display, into its icon as always. Sliding down and fading do not
            // go into the icon (it is back on home at once), so they play also for an app with no icon on home (opened from
            // the App Library, Spotlight, a folder): the styles that go into it shrink to the middle and fade there.
            val chosen = AppStyles.closing()
            val style = if (deferHome || (iconAt == null && chosen.fromIcon)) AppStyle.ZOOM else chosen
            val intoIcon = style.fromIcon
            val target = iconAt?.takeIf { intoIcon }
            // Home's picture as this close needs it (it was chosen by the closing style; a turned display goes into the icon).
            if (iconAt != null && intoIcon != AppStyles.closing().fromIcon && !pictureDropped) {
                (if (intoIcon) HomeBridge.previewFor(pkg) else HomeBridge.preview)?.let { backdrop?.picture = it }
            }
            val size = target?.width() ?: (sw * 0.3f)
            val sizeH = target?.height() ?: (sh * 0.3f)
            switchAt = 0L   // going home ends any run of quick switches
            // As it will show on home (it may have changed while the app was open); none for an icon without badges (the
            // App Library's: the count appeared on the card and went as the card turned into the icon).
            c.badge = pkg?.let { if (target != null && HomeBridge.showsBadge(it)) Badges.count(it) else 0 } ?: 0
            // Home is shown turned under a landscape app: the icon the card becomes is upright on home (iOS).
            c.iconTurn = homeTurn
            hideIcon(if (target != null) pkg else null)
            cardIconSize = size
            beginCardSprings(toIcon = target != null, style, opening = false, icon = iconAt)
            // Not on the home screen: the card shrinks to the centre and fades out on the way, instead of turning into an icon
            // that would then linger and fade. (Sliding down and fading in place have their own way out.)
            springFade = target == null && intoIcon
            springFadeFrom = if (pkg != null && pkg == autoPipPkg) 0.7f else 0.25f
            val tx = target?.centerX() ?: (sw / 2)
            val ty = target?.centerY() ?: (sh / 2)
            // The close looks and lasts the same however fast the flick was: only a little of the finger's motion towards the
            // target carries over ([towards]), so the card neither stops dead at the release nor gets thrown.
            val mp = Motion.profile
            when (style) {
                AppStyle.SLIDE -> {
                    // Down off the screen from where it is, at the size it has.
                    val down = sh + c.h / 2 + sh * AppStyles.SLIDE_BELOW
                    sCx = mp.appClosePosition.mover().apply { start(c.cx, 0f, c.cx) }
                    sCy = mp.appClosePosition.mover().apply { start(c.cy, towards(vy, c.cy, down), down) }
                    sW = mp.appCloseSize.mover().apply { start(c.w, 0f, c.w) }
                    sH = mp.appCloseSize.mover().apply { start(c.h, 0f, c.h) }
                }
                AppStyle.FADE -> {
                    // In place, a little smaller, fading.
                    sCx = mp.appClosePosition.mover().apply { start(c.cx, 0f, c.cx) }
                    sCy = mp.appClosePosition.mover().apply { start(c.cy, 0f, c.cy) }
                    sW = mp.appCloseSize.mover().apply { start(c.w, 0f, c.w * AppStyles.FADE_FROM) }
                    sH = mp.appCloseSize.mover().apply { start(c.h, 0f, c.h * AppStyles.FADE_FROM) }
                }
                else -> {
                    sCx = mp.appClosePosition.mover().apply { start(c.cx, towards(vx, c.cx, tx), tx) }
                    sCy = mp.appClosePosition.mover().apply { start(c.cy, towards(vy, c.cy, ty), ty) }
                    sW = mp.appCloseSize.mover().apply { start(c.w, towards(vW, c.w, size), size) }
                    sH = mp.appCloseSize.mover().apply { start(c.h, towards(vH, c.h, sizeH), sizeH) }
                }
            }
            val depth0 = depthNow()
            sDepth = mp.homeDepthClose.mover().apply { start(depth0, 0f, 0f) }
            closeDepthFrom = depth0
            closeFading = false
            anim = Anim.HOME_COMMIT
            // (Not over a turned display: home is laid out upright, the touches would land beside what they were aimed at.)
            if (!deferHome) catchTouchesForHome(true)
            cardGoesHome()
            endLabel = when {
                !intoIcon -> "home (${style.id}: $pkg's icon on home at once)"
                target != null -> "home (into the icon of $pkg at ${target.centerX().toInt()},${target.centerY().toInt()})" +
                    if (style != AppStyle.ZOOM) " (${style.id})" else ""
                pkg == null -> "home (to centre; app NOT KNOWN yet at release: task lookup still running)"
                else -> "home (to centre; $pkg has no icon on home)"
            } + "; front report ${lastFrontPkg ?: "-"}, top task ${recentList.firstOrNull()?.pkg ?: "-"}"
            onSettled = {
                phase = Phase.HOLD
                if (deferHome) {
                    // The real icon is back now (under the card: unseen), so home already shows it when it comes; the card
                    // window is hidden by Android as home starts (the cover shows the same under it) and the cards go with
                    // the turn (see onCardWindowTurned).
                    hiddenIconPkg?.let { HomeBridge.setIconHidden(it, false) }
                    hiddenIconPkg = null
                    whenCoverShown(g) { shown ->
                        if (!shown) AppLog.log("[nav] no cover under the card in time: home starts anyway (the app may show while the display turns)")
                        // The app going into picture-in-picture (YouTube playing, as soon as the cover covered it) may still
                        // be moving into its corner under our card: Android hides our windows as home starts, and the app's
                        // video showed full size, turning and shrinking. Home starts once it is still.
                        whenPipSettled(g, PIP_SETTLE_MAX_MS) {
                            // The mini player takes over from the card where it is (before Android hides our windows).
                            releasePipCover()
                            holdingForTurn = g
                            nav.postDelayed({ if (holdingForTurn == g) { holdingForTurn = -1; AppLog.log("[nav] the display did not turn upright in time: the picture goes anyway"); if (gen == g) hideCards() } }, HOME_SHOWN_TIMEOUT_MS)
                            TurnCover.handedOver()
                            startHome()
                        }
                    }
                } else if (pipFlight) whenPipSettled(g, PIP_SETTLE_MAX_MS) {
                    // Into the mini player: it takes over from the card once it has settled, and the picture of home goes
                    // once home has drawn a current frame.
                    releasePipCover()
                    HomeBridge.afterHomeDraw { nav.post { if (gen == g) hideCards() } }
                } else whenHomeDrawn(g) {
                    // The picture of home is on top of the real one: swap only once the real home has drawn.
                    if (target != null) {
                        // The real icon back first; the card (now the icon) goes only once home has drawn a frame with it:
                        // never a frame with neither (a fixed delay left a gap whenever home's draw came late).
                        hiddenIconPkg = null
                        HomeBridge.showIconThen(pkg) { nav.post { if (gen == g) hideCards() } }
                    } else {
                        // Already faded out on the way; the picture of home goes once home has drawn a current frame.
                        HomeBridge.afterHomeDraw { nav.post { if (gen == g) hideCards() } }
                    }
                }
            }
        } else {
            cardIconSize = 0f
            beginCardSprings(toIcon = false)
            val sp = Motion.profile.appCancel
            sCx = sp.mover().apply { start(c.cx, vx, sw / 2) }
            sCy = sp.mover().apply { start(c.cy, vy, sh / 2) }
            sW = sp.mover().apply { start(c.w, vW, sw) }
            sH = sp.mover().apply { start(c.h, vH, sh) }
            sDepth = sp.mover().apply { start(depthNow(), 0f, 1f) }
            closeDepthFrom = -1f
            anim = Anim.HOME_CANCEL
            endLabel = "back to the app"
            onSettled = {
                if (homeStarted) {
                    // Home came to the front earlier in this card's life (e.g. grabbed while closing): put the app back.
                    phase = Phase.HOLD
                    bringBack(cardPkg, fg)
                    cardPkg?.let { p -> awaitForeground(p, g) { hideCards() } } ?: hideCards()
                } else if (appStarted && cardPkg != null && lastFrontPkg != cardPkg) {
                    phase = Phase.HOLD
                    awaitForeground(cardPkg!!, g) { hideCards() }
                } else hideCards()
            }
        }
        startSprings()
        if (closeDepthFrom >= 0f) {
            // The real home runs the same depth spring underneath, so it matches the picture whenever it takes over (at the end,
            // or when home is touched during the flight).
            val spec = Motion.profile.homeDepthClose
            HomeBridge.animateDepth(closeDepthFrom, 0f, 0f, spec, springStartNs)
        }
    }

    // Depth the close started from (the real home follows it); -1 when not closing.
    private var closeDepthFrom = -1f
    // The picture of home was taken away during a close: the real home (interactive, live) is what shows behind the card.
    private var pictureDropped = false

    /**
     * Home was touched while a closing card is still flying (the touch goes to home, the window in front). Nothing on screen
     * changes for it: the card keeps landing over the picture of home. A tap that opens another app hands the card to the
     * lander ([landingFor]) and puts that app's picture of home in; a drag moves home's icons, and the card then gets out of
     * the way ([homeDraggedDuringClose]). (Home used to take over from the picture at the touch: the picture blurs a
     * quarter-size copy, which looks about twice as blurred as home's own blur at the same radius, so home turned sharp for
     * a frame or two before the next app's picture blurred it again: Matheesha's full-screen flicker, opening apps quickly.)
     */
    private fun homeTouchedDuringClose() {
        if (anim != Anim.HOME_COMMIT || root?.visibility != View.VISIBLE) return
        AppLog.log("[nav] home touched during the close: the card keeps landing")
    }

    /**
     * A touch on home became a drag (pages, the App Library, Spotlight) while a closing card is still on screen: the icon it
     * flies into moves with home, so the card fades where it is and its icon is back, and the picture of home fades with it
     * into the real home (blurring as the picture looks, [HomeScreen]'s depth; cut at once, home's blur snapped).
     */
    private fun homeDraggedDuringClose() {
        if (anim != Anim.HOME_COMMIT || root?.visibility != View.VISIBLE || closeFading) return
        closeFading = true
        HomeBridge.homeCovered = false
        pictureDropped = true
        hiddenIconPkg?.let { HomeBridge.setIconHidden(it, false) }
        hiddenIconPkg = null
        AppLog.log("[nav] home dragged during the close: the card fades where it is")
        fadeOutCards(Motion.role(dev.launcher.app.motion.MotionTokens.CARD_FADE_HOME), gen, overHome = true)
    }

    // The closing card is fading where it is ([homeDraggedDuringClose]).
    private var closeFading = false
    /** How often a picture of home waits for a finger to leave home before it is rendered ahead ([prewarmHome]; ms). */
    private const val PREWARM_RETRY_MS = 250L
    /** The longest a launch card waits for its app's window to be on screen once the app is in front ([whenAppShown]; ms). */
    private const val APP_SHOWN_MAX_MS = 600L

    // ---- the lander: a closing card that keeps landing in its icon while another app opens
    //
    // Opening apps one after another quickly (close one, tap the next before its card has landed) used to cut the closing
    // card mid-air and start the picture of home sharp (its blur snapped away, then came back). The closing card now goes
    // on into its icon on [land], under the new card, with springs of its own that continue its flight (same roles, from
    // where it is and as fast as it goes). Home recedes again behind the new app meanwhile, so the card is drawn zoomed
    // with home's picture and lands where the icon shows; the icon stays left out of that picture (a patch from the picture
    // without it, [PreviewView.setPatch]) until it has landed. Over a blurred home the landed card then fades into its icon.

    private var landPkg: String? = null
    private var lCx: Mover = Spring(0.5f, 0.86f)
    private var lCy: Mover = Spring(0.5f, 0.86f)
    private var lW: Mover = Spring(0.44f, 0.9f)
    private var lH: Mover = Spring(0.44f, 0.9f)
    private var landStartNs = 0L
    // As [springW0], [springR0], [springMix0] of the close it continues, and its fade ([springFade]) when it has no icon.
    private var landW0 = 0f
    private var landR0 = 0f
    private var landMix0 = 0f
    private var landFade = false
    private var landFadeFrom = 0.25f
    private var landSettled = false
    private var landGen = 0

    /** Home's picture zooms its content by this at [depth] (about the middle of the screen). */
    private fun contentZoom(depth: Float) = 1f + depth * (Motion.profile.homeContentZoom - 1f)

    /** Home's depth in the frame at [frameNs]: from the card springs (the same value they give the picture), else as shown. */
    private fun depthAt(frameNs: Long): Float =
        if (animating && anim != Anim.SWITCH_COMMIT && anim != Anim.SWITCH_CANCEL) sDepth.value(max(0L, frameNs - springStartNs) / 1e9)
        else backdrop?.depth ?: 0f

    /** A closing card that can go on landing: [start] starts it ([keep]: where the new app's icon is on home). */
    private class Landing(val without: HomePicture?, val start: (keep: RectF?) -> Unit)

    /**
     * When the card closing now can go on landing while [pkg] opens: what starts it (once the launch has set home's
     * picture) and the picture of home without its icon (none for a card fading on its way to the middle), else null.
     * Reads the close as it is: call before [hideCards].
     */
    private fun landingFor(pkg: String): Landing? {
        val c = cur ?: return null
        val l = land ?: return null
        val from = cardPkg ?: return null
        if (root?.visibility != View.VISIBLE || anim != Anim.HOME_COMMIT || !animating || pipFlight || closeFading || from == pkg) return null
        // Home is laid out upright: over a turned display the card flies in other coordinates (none of this).
        if (turned || homeTurn != 0f) return null
        // A close in another style than the zoom: its card fades where it is as the new app opens (its own flight would be
        // drawn over home's picture zooming another way).
        if (flightStyle != AppStyle.ZOOM || styleDev0.any) return fadingLanding(c, from)
        // Into its icon (left out of the new picture of home with one recorded without it), or fading on its way to the
        // middle (no icon on home).
        val icon = if (springToIcon) HomeBridge.iconRect(from) else null
        val without = if (springToIcon && HomeBridge.hasWithout(from)) HomeBridge.previewFor(from) else null
        if (springToIcon && (icon == null || without == null || hiddenIconPkg != from)) return null
        if (!springToIcon && !springFade) return null
        val now = System.nanoTime()
        val t = max(0L, now - springStartNs) / 1e9
        // Already in its icon (a tap as it lands): the new picture of home shows the icon right there, nothing to land.
        fun at(m: Mover) = abs(m.value(t) - m.target) < 1f && abs(m.velocity(t)) < 60f
        if (springToIcon && c.iconMix >= 0.99f && at(sCx) && at(sCy) && at(sW) && at(sH)) return null

        val z = contentZoom(depthNow())
        val ox = sw / 2f
        val oy = sh / 2f
        // Its frame and speed in home's own (unzoomed) coordinates: drawn zoomed with home from here on, it starts exactly
        // where it is.
        val mp = Motion.profile
        val x = mp.appClosePosition.mover().apply { start(ox + (sCx.value(t) - ox) / z, sCx.velocity(t) / z, sCx.target) }
        val y = mp.appClosePosition.mover().apply { start(oy + (sCy.value(t) - oy) / z, sCy.velocity(t) / z, sCy.target) }
        val w = mp.appCloseSize.mover().apply { start(sW.value(t) / z, sW.velocity(t) / z, sW.target) }
        val h = mp.appCloseSize.mover().apply { start(sH.value(t) / z, sH.velocity(t) / z, sH.target) }
        val w0 = springW0; val r0 = springR0; val mix0 = springMix0
        val fade = springFade; val fadeFrom = springFadeFrom
        val snapshot = c.snapshot; val icn = c.icon; val color = c.placeholderColor; val minIcon = c.minIconSize
        val unit = c.unitPx; val badge = c.badge; val alpha = c.alpha; val mix = c.iconMix
        val cx = c.cx; val cy = c.cy; val cw = c.w; val ch = c.h; val cr = c.radius
        // The new card's session must not show the icon again (hideCards would): it stays hidden on the real home until
        // the new app's icon is hidden in its place, once the picture of home covers home again.
        if (springToIcon) hiddenIconPkg = null
        return Landing(without) { keep ->
            endLander()
            landPkg = from
            lCx = x; lCy = y; lW = w; lH = h
            landStartNs = now
            landW0 = w0; landR0 = r0; landMix0 = mix0
            landFade = fade; landFadeFrom = fadeFrom
            landSettled = false
            l.animate().cancel()
            l.homePicture = null
            l.snapshot = snapshot
            l.icon = icn
            l.placeholderColor = color
            l.minIconSize = minIcon
            l.unitPx = unit
            l.badge = badge
            l.iconTurn = 0f
            l.focus = null
            l.focusMix = 0f
            l.alpha = alpha
            l.iconMix = mix
            l.setFrame(cx, cy, cw, ch, cr)
            l.visibility = View.VISIBLE
            if (icon != null && without != null) {
                // Its icon and badge (which reaches past the icon's corner) left out of home's picture.
                val m = icon.width() * 0.2f
                landPatchFrom = without
                landPatchRect = RectF(icon.left - m, icon.top - m, icon.right + m, icon.bottom + m)
                landPatchKeep = keep?.let { k -> val km = k.width() * 0.2f; RectF(k.left - km, k.top - km, k.right + km, k.bottom + km) }
                // (A launch that comes in over its blurred icon shows the picture without this icon first: the patch
                // goes on with the new app's picture, [softSwap].)
                if (softPicture == null) applyLandPatch()
            }
            choreographer.postFrameCallback(landFrame)
            AppLog.log("[nav] $from keeps landing while ${cardPkg ?: "the next app"} opens")
        }
    }

    /** The closing card stays where it is, as it looks, and fades as the new app opens (a close in another style). */
    private fun fadingLanding(c: CardView, from: String): Landing {
        val snapshot = c.snapshot; val icn = c.icon; val color = c.placeholderColor; val minIcon = c.minIconSize
        val unit = c.unitPx; val alpha = c.alpha; val mix = c.iconMix
        val cx = c.cx; val cy = c.cy; val cw = c.w; val ch = c.h; val cr = c.radius
        val look = StyleDev().also { it.of(c) }
        if (springToIcon) hiddenIconPkg = null
        return Landing(null) { _ ->
            val l = land ?: return@Landing
            endLander()
            landPkg = from
            landSettled = true
            l.animate().cancel()
            l.homePicture = null
            l.snapshot = snapshot
            l.icon = icn
            l.placeholderColor = color
            l.minIconSize = minIcon
            l.unitPx = unit
            l.badge = 0
            l.iconTurn = 0f
            l.focus = null
            l.focusMix = 0f
            l.iconMix = mix
            l.setFrame(cx, cy, cw, ch, cr)
            applyDev(l, look, 1f, alpha = false)
            l.alpha = alpha
            l.visibility = View.VISIBLE
            val g = landGen
            l.animate().alpha(0f).timed(Motion.role(dev.launcher.app.motion.MotionTokens.CARD_FADE_HOME))
                .setUpdateListener(null)
                .withEndAction { if (landGen == g) endLander() }.start()
            AppLog.log("[nav] $from fades where it is while ${cardPkg ?: "the next app"} opens")
        }
    }

    // The picture without the landing card's icon, where the icon is (with its badge), and where the new app's icon is
    // (left alone): the patch on the new app's picture of home.
    private var landPatchFrom: HomePicture? = null
    private var landPatchRect: RectF? = null
    private var landPatchKeep: RectF? = null
    // The lander reached its icon before the new app's picture of home went on ([softSwap]): it waits there, as the icon.
    private var landWaiting = false

    private fun applyLandPatch() {
        val from = landPatchFrom ?: return
        if (landPkg == null || landSettled) return
        backdrop?.setPatch(from.content, landPatchRect, landPatchKeep)
    }

    private val landFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (landPkg == null) return
            val settled = applyLander(frameTimeNanos)
            if (landFade) { if (settled || (land?.alpha ?: 0f) <= 0f) endLander() }
            else if (!landSettled && landerArrived(frameTimeNanos)) { landSettled = true; landed(frameTimeNanos) }
            if (landPkg != null) choreographer.postFrameCallback(this)
        }
    }

    /** Home's blur (px) in the frame at [frameNs], as the picture of it draws it. */
    private fun homeBlurAt(frameNs: Long) =
        depthAt(frameNs).coerceIn(0f, 1f) * Motion.profile.homeDepthBlur * app.resources.displayMetrics.density

    /**
     * The lander shows where its icon is. Over a sharp home: within a pixel and nearly still (it then simply goes, looking
     * exactly like the icon). Over a home blurring again behind the new app: within a sixth of the icon, so it does not
     * stay sharp in a blurred home while its springs settle; it dissolves into its blurred icon over its last pixels.
     */
    private fun landerArrived(frameNs: Long): Boolean {
        val t = max(0L, frameNs - landStartNs) / 1e9
        val z = contentZoom(depthAt(frameNs))
        val blurred = homeBlurAt(frameNs) >= 0.5f
        val tol = if (blurred) max(1.5f, lW.target * z / 6f) else 1f
        fun near(m: Mover) = abs(m.value(t) - m.target) * z < tol && (blurred || abs(m.velocity(t)) * z < 60f)
        return near(lCx) && near(lCy) && near(lW) && near(lH)
    }

    /** The lander's frame at [frameNs] (zoomed with home's picture); true once its springs have settled. */
    private fun applyLander(frameNs: Long): Boolean {
        val l = land ?: return true
        val t = max(0L, frameNs - landStartNs) / 1e9
        val w = lW.value(t)
        val h = lH.value(t)
        val target = lW.target
        val q = if (abs(target - landW0) > 1f) ((w - landW0) / (target - landW0)).coerceIn(0f, 1f) else 1f
        val r: Float
        if (landFade) {
            l.iconMix = 0f
            l.alpha = 1f - ((q - landFadeFrom) / (0.85f - landFadeFrom)).coerceIn(0f, 1f)
            r = deviceRadius * (w / sw)
        } else {
            // As [applyCardSprings] into an icon: corners to the icon's, the picture crossfading into the icon at the end.
            r = landR0 + (target * Icons.shape.clipFraction() - landR0) * q
            l.iconMix = max(landMix0 * (1f - q), 1f - ((w - target) / (target * 1.5f)).coerceIn(0f, 1f))
        }
        val z = contentZoom(depthAt(frameNs))
        val ox = sw / 2f
        val oy = sh / 2f
        l.setFrame(ox + (lCx.value(t) - ox) * z, oy + (lCy.value(t) - oy) * z, w * z, h * z, r * z)
        return lCx.settled(t) && lCy.settled(t) && lW.settled(t) && lH.settled(t)
    }

    /**
     * The lander is at its icon ([landerArrived]): the icon is back in home's picture under it, and the card goes. Over a
     * home that is already blurring again behind the new app it fades into its (blurred) icon, still flying its last
     * pixels and following home's zoom; over a sharp home it simply goes (it looks exactly like the icon).
     */
    private fun landed(frameNs: Long) {
        val l = land ?: return endLander()
        // Home's picture still shows the one without its icon (the new app's comes in over its blurred icon first): it
        // stays as the icon until that picture goes ([softSwap]).
        if (softPicture != null) { landWaiting = true; return }
        backdrop?.setPatch(null, null, null)
        if (l.alpha <= 0f || homeBlurAt(frameNs) < 0.5f) { endLander(); return }
        val g = landGen
        l.animate().alpha(0f).timed(Motion.role(dev.launcher.app.motion.MotionTokens.CARD_FADE_HOME))
            .setUpdateListener(null)
            .withEndAction { if (landGen == g) endLander() }.start()
    }

    /** The lander goes (if any): hidden, its icon back in home's picture and on home. */
    private fun endLander() {
        val from = landPkg ?: return
        landGen++
        landPkg = null
        landPatchFrom = null; landPatchRect = null; landPatchKeep = null
        landWaiting = false
        choreographer.removeFrameCallback(landFrame)
        // Normally shown again on home already, when the new app's icon was hidden in its place (one hidden icon at a time);
        // never left hidden if that did not happen (home ignores this unless that very icon is the hidden one).
        if (hiddenIconPkg != from) HomeBridge.setIconHidden(from, false)
        land?.let { l ->
            l.animate().cancel()
            l.visibility = View.GONE
            l.alpha = 1f
            l.snapshot = null
            l.icon = null
            l.iconMix = 0f
            l.clearStyle()
            l.badge = 0
        }
        backdrop?.setPatch(null, null, null)
    }

    // ---- touches during a close
    //
    // For ~80 ms after a close is released home is not yet the window in front (the system starts it, it draws, the
    // transition commits: traced on the S24), and a gesture that starts then belongs to the closing app for its whole
    // length: invisible under the card, a drag on the App Library right after a close did nothing at all (and a tap could
    // press something in the app). While a close is under way the card window takes every touch instead and hands it to
    // home, which runs in this process. Its touchable region (no window re-layout) is the whole screen only then.

    private var catching = false     // the card window's touchable region is the whole screen
    private var forwarding = false   // the gesture under way started while catching: all of it goes to home

    private fun catchTouchesForHome(on: Boolean) {
        if (!CAN_CATCH_TOUCHES || catching == on || !cardTouchable) return
        val host = cardWindow ?: return
        catching = on
        host.rootSurfaceControl?.setTouchableRegion(if (on) fullRegion() else NO_TOUCH)
    }

    /** The whole screen except the floating windows (their touches stay theirs). */
    private fun fullRegion() = android.graphics.Region(0, 0, sw.toInt(), sh.toInt()).apply {
        for (h in floating) op(h.left.toInt(), h.top.toInt(), h.right.toInt(), h.bottom.toInt(), android.graphics.Region.Op.DIFFERENCE)
    }

    private fun onCardWindowTouch(e: MotionEvent) {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            forwarding = catching
            // A touch outside a close: the empty touchable region did not hold. Never eat touches: untouchable for good.
            if (!catching) { makeCardWindowUntouchable(); return }
        }
        if (forwarding) HomeBridge.forwardTouch(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) forwarding = false
    }

    private var cardTouchable = true

    private fun makeCardWindowUntouchable() {
        if (!cardTouchable) return
        cardTouchable = false
        catching = false
        AppLog.log("[nav] the card window took a touch outside a close: it is untouchable from now on")
        val host = cardWindow ?: return
        val lp = host.layoutParams as? WindowManager.LayoutParams ?: return
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { wm?.updateViewLayout(host, lp) } catch (_: Throwable) { }
    }

    private fun startHome() {
        homeStarted = true
        homeRequestedAt = SystemClock.uptimeMillis()
        HomeBridge.homeStartedAt = homeRequestedAt
        homeVisible = false
        // startActivity is a binder round trip of tens of ms: never on the nav thread, which is drawing the card right now.
        front("home") {
            try {
                // Our home activity by name (singleTask: the system brings back the task it lives in, which must be the home
                // task: the updater and the scripts start home with a home intent, never by component).
                val home = Intent(app, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!NoAnimStarts.start(home, Process.myUserHandle().hashCode())) app.startActivity(home, noAnimation(app))
            } catch (t: Throwable) {
                AppLog.log("[nav] going home from the app failed (${t.javaClass.simpleName}); using the shell")
                ShizukuLink.service?.runDetached("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
            }
        }
    }

    /** Runs [then] once home has drawn a frame after it was requested (or after 700 ms), unless the session moved on. */
    private fun whenHomeDrawn(g: Int, then: () -> Unit) {
        val deadline = SystemClock.uptimeMillis() + 700
        fun check() {
            if (gen != g) return
            if (HomeBridge.homeDrawnAt >= homeRequestedAt || SystemClock.uptimeMillis() > deadline) then()
            else nav.postDelayed({ check() }, 8)
        }
        check()
    }

    /** The card session (gen) whose close into home waits for the display to turn upright (-1: none). */
    private var holdingForTurn = -1

    /** Whether this card session asked for a [TurnCover], when, and whether its start failed. */
    private var coverAsked = false
    private var coverAskedAt = 0L
    @Volatile private var coverFailed = false

    /**
     * Starts the cover that stands in for home while a close over a turned display hands over (it lies under the card window,
     * over the app; the shell's own start, so no system animation and nothing waiting on one).
     */
    private fun showTurnCover() {
        if (Build.VERSION.SDK_INT < 30) return
        coverAsked = true
        coverAskedAt = SystemClock.uptimeMillis()
        coverFailed = false
        TurnCover.expect()
        val hideBars = barVisible == false
        frontIo.execute {
            val ok = try { NoAnimStarts.start(TurnCover.intent(app, hideBars), Process.myUserHandle().hashCode()) } catch (_: Throwable) { false }
            if (!ok) { AppLog.log("[nav] the cover for a close over a turned display could not be started"); coverFailed = true; TurnCover.handedOver() }
        }
    }

    /**
     * Runs [then] (nav thread) with true once the cover is on screen: the shell saw the start transaction of our transition
     * for it committed (its first frame drawn is not enough: the transition was ready up to 300 ms later on the emulator,
     * and home started in between showed the app for a frame). False after [COVER_TIMEOUT_MS], or at once without a cover.
     */
    private fun whenCoverShown(g: Int, then: (Boolean) -> Unit) {
        val since = coverAskedAt
        val deadline = SystemClock.uptimeMillis() + COVER_TIMEOUT_MS
        val component = android.content.ComponentName(app, TurnCover::class.java).flattenToString()
        fun check() {
            if (gen != g) return
            if (!coverAsked || coverFailed || TurnCover.failedAt >= since) { then(false); return }
            frontIo.execute {
                val at = try { ShizukuLink.service?.transitionShownAt(component) } catch (_: Throwable) { null }
                nav.post {
                    if (gen != g) return@post
                    when {
                        at == null -> then(false)
                        // On screen (the shell saw its transition committed) with its own first frame drawn a refresh ago.
                        at >= since && TurnCover.drawnAt >= since && SystemClock.uptimeMillis() - TurnCover.drawnAt >= COVER_FRAME_MS -> {
                            AppLog.log("[nav] cover on screen ${at - since} ms after it was asked for (drawn after ${TurnCover.drawnAt - since} ms)")
                            then(true)
                        }
                        SystemClock.uptimeMillis() > deadline -> then(false)
                        else -> nav.postDelayed({ check() }, 8)
                    }
                }
            }
        }
        check()
    }

    /**
     * The card window was given the display's new orientation (home in front, upright): a close that landed over a turned
     * display ends here. Android has kept the window hidden since home started (the cover and then home showed the same
     * picture) and shows it again once it has drawn for the turn: by then it is empty.
     */
    private fun onCardWindowTurned() {
        val g = holdingForTurn
        if (g < 0) return
        holdingForTurn = -1
        root?.visibility = View.INVISIBLE
        if (gen == g) hideCards()
    }

    // Card fades out as it shrinks (closing an app that has no icon on the home screen), from this far into the shrink.
    private var springFade = false
    private var springFadeFrom = 0.25f

    /** The part of a release velocity that a close keeps: towards the target only, at most [RELEASE_CARRY]. */
    private fun towards(v: Float, from: Float, to: Float): Float {
        // The close takes the same time however fast the flick was: only a little of the finger's motion towards the target
        // carries over (enough that the card does not stop dead at the release), none of the motion away from it.
        val capped = v.coerceIn(-RELEASE_CARRY, RELEASE_CARRY)
        return if ((to - from) * capped > 0f) capped else 0f
    }

    // Where corners and icon blend start when springs begin: they interpolate from there, so no release ever snaps them.
    private var springW0 = 0f
    private var springR0 = 0f
    private var springMix0 = 0f
    private var springToIcon = false

    /**
     * The springs about to start take the card from where it is: as it is drawn, or ([from], [fromR]: a flight reversed) the
     * plain card the last flight's style was drawn on, where they then start.
     */
    private fun beginCardSprings(toIcon: Boolean, style: AppStyle = AppStyle.ZOOM, opening: Boolean = false, icon: RectF? = null,
                                 fresh: Boolean = false, from: RectF? = null, fromR: Float = Float.NaN) {
        val c = cur ?: return
        val f0 = from ?: RectF(c.cx - c.w / 2, c.cy - c.h / 2, c.cx + c.w / 2, c.cy + c.h / 2)
        val r0 = if (from != null && !fromR.isNaN()) fromR else c.radius
        springW0 = f0.width()
        springR0 = r0
        springMix0 = c.iconMix
        springToIcon = toIcon
        springRadiusEnd = deviceRadius
        // A style the card still shows (a flight interrupted, a grab) stays on the plain card as it moves and fades out in this
        // one's first half, as its corners do. (A new launch's card is set up by its style: nothing left over.)
        if (fresh) styleDev0.clear() else styleDev0.grab(c, f0, r0, sw, sh)
        flightStyle = style
        flightOpening = opening
        flightBlend = opening && !fresh
        if (icon != null) flightIcon.set(icon) else flightIcon.setEmpty()
        flightFrom.set(f0)
        plainFrame.set(f0)
        plainR = r0
        springCy0 = f0.centerY()
        // A fading card is faded draw by draw: through a layer of its size it cost the GPU ~10 ms a frame on the S24.
        if (style == AppStyle.FADE) c.flatFade = true
    }

    // The start's style ([styleDev0]) on the flight's plain card, as drawn this frame.
    private var startMesh = FloatArray(0)

    // ---- transition styles (motion/AppStyles): how the flight the springs drive is drawn. At ZOOM with nothing left over
    // from an earlier style, the card draws exactly as before them.

    private var flightStyle = AppStyle.ZOOM
    private var flightOpening = false
    /**
     * A closing card reopened ([beginLaunch]): its style is taken up where the card is on its way from the icon, and comes in
     * over the first half of the flight as what the card showed goes (frames, the genie's mesh, the fade-through: one blend).
     */
    private var flightBlend = false
    /** How far into the genie's way home its picture starts turning into the icon. */
    private const val GENIE_ICON_FROM = 0.82f
    // The icon the flight comes out of or goes into, and the card's frame as it began (screen px).
    private val flightIcon = RectF()
    private val flightFrom = RectF()
    private var springCy0 = 0f
    private val genieMesh = FloatArray((AppStyles.GENIE_COLS + 1) * (AppStyles.GENIE_ROWS + 1) * 2)
    private val genieA = Box()
    private val genieB = Box()

    /**
     * What a card draws apart from its frame (its style's own drawing), as departures from a plain card: what a flight
     * starts from ([styleDev0], fading out in its first half) and what a grabbed card keeps while the finger holds it
     * ([grabDev], giving way to a plain card on its own and as it is pulled back towards full screen).
     */
    private class StyleDev {
        var content = 0f; var icon = 0f; var box = 0f; var full = 0f; var genie = 0f; var alpha = 0f
        var mesh: FloatArray? = null
        /** Where the app is drawn at [full] (screen px; empty: the screen), and its icon ([CardView.iconFrame]). */
        val fullFrame = RectF()
        val iconFrame = RectF()
        // A grab's ([grab]): the style's own frame (a circle reveal's window, a genie's bounds), where the app is drawn and the
        // mesh, relative to the plain card the style is drawn on (offsets from its centre and sizes, in its widths).
        var framed = false
        var fx = 0f; var fy = 0f; var fw = 1f; var fh = 1f; var fr = 0f
        var gx = 0f; var gy = 0f; var gw = 1f; var gh = 1f
        var ix = 0f; var iy = 0f; var iw = 0f
        val any get() = content != 0f || icon != 0f || box != 0f || full != 0f || genie != 0f || alpha != 0f || framed

        fun of(c: CardView) {
            content = c.contentK - 1f; icon = c.iconK - 1f; box = c.boxK; full = c.contentFullK; alpha = c.alpha - 1f
            fullFrame.set(c.contentFrame)
            iconFrame.set(c.iconFrame)
            framed = false
            val m = c.genieMesh
            genie = if (m != null) c.genieK else 0f
            mesh = if (m != null) (mesh?.takeIf { it.size == m.size } ?: FloatArray(m.size)).also { System.arraycopy(m, 0, it, 0, m.size) } else null
        }

        /**
         * As [of], kept relative to [plain] (the card the flight's style was drawn on, [plainR] its corners): a grabbed card's
         * style moves and scales with the card under the finger ([applyGrab]).
         */
        fun grab(c: CardView, plain: RectF, plainR: Float, sw: Float, sh: Float) {
            of(c)
            val pw = max(1f, plain.width())
            val px = plain.centerX()
            val py = plain.centerY()
            framed = abs(c.cx - px) > 0.5f || abs(c.cy - py) > 0.5f || abs(c.w - plain.width()) > 0.5f ||
                abs(c.h - plain.height()) > 0.5f || abs(c.radius - plainR) > 0.5f
            fx = (c.cx - px) / pw; fy = (c.cy - py) / pw; fw = c.w / pw; fh = c.h / pw; fr = c.radius / pw
            val g = if (fullFrame.isEmpty) RectF(0f, 0f, sw, sh) else fullFrame
            gx = (g.centerX() - px) / pw; gy = (g.centerY() - py) / pw; gw = g.width() / pw; gh = g.height() / pw
            ix = (iconFrame.centerX() - px) / pw; iy = (iconFrame.centerY() - py) / pw; iw = iconFrame.width() / pw
            mesh?.let { m -> var i = 0; while (i < m.size) { m[i] = (m[i] - px) / pw; m[i + 1] = (m[i + 1] - py) / pw; i += 2 } }
        }

        fun clear() { content = 0f; icon = 0f; box = 0f; full = 0f; genie = 0f; alpha = 0f; framed = false; fullFrame.setEmpty(); iconFrame.setEmpty() }
    }

    private val styleDev0 = StyleDev()
    private val grabDev = StyleDev()

    /** A card's style drawing as [dev] at [k] (1: as it was, 0: a plain card); [alpha]: the card's alpha too. */
    private fun applyDev(c: CardView, dev: StyleDev, k: Float, alpha: Boolean) {
        c.contentK = 1f + dev.content * k
        c.iconK = 1f + dev.icon * k
        c.boxK = dev.box * k
        c.contentFullK = dev.full * k
        c.contentFrame.set(dev.fullFrame)
        c.iconFrame.set(dev.iconFrame)
        val m = dev.mesh
        if (m != null && dev.genie * k > 0.001f) { c.genieMesh = m; c.genieK = dev.genie * k } else c.genieMesh = null
        if (alpha && dev.alpha != 0f) c.alpha = (1f + dev.alpha * k).coerceIn(0f, 1f)
    }

    private var grabMesh = FloatArray(0)

    // A grabbed card's style gives way to the plain card under the finger on its own (a circle reveal's window opens, a
    // genie straightens, a fade-through ends) on the cancel's curve: whatever the finger does next (the App Switcher, whose
    // deck draws plain cards, a switch, a release) starts from a plain card. [heldFrame]: the card taken over, while the
    // finger has not chosen a direction yet.
    private var grabSettle: Mover? = null
    private var grabSettleNs = 0L
    private val heldFrame = RectF()
    private var heldR = 0f
    private var switcherAfterSettle = false

    private fun grabStyleK(): Float {
        val m = grabSettle ?: return 0f
        return m.value((System.nanoTime() - grabSettleNs) / 1e9).coerceIn(0f, 1f)
    }

    private fun grabStyleSettled(): Boolean {
        val m = grabSettle ?: return true
        return m.settled((System.nanoTime() - grabSettleNs) / 1e9, 0.002f)
    }

    private fun startGrabSettle() {
        grabSettle = if (grabDev.any) Motion.profile.appCancel.mover().apply { start(1f, 0f, 0f) } else null
        grabSettleNs = System.nanoTime()
        switcherAfterSettle = false
        if (grabSettle != null) choreographer.postFrameCallback(grabSettleFrame)
    }

    private val grabSettleFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (grabSettle == null) return
            val c = cur ?: run { grabSettle = null; return }
            val done = grabStyleSettled()
            when {
                phase == Phase.DRAG_HOME -> dragHome(lastDragX, lastDragY)   // (advances it while the finger rests)
                grabbedFull -> applyGrab(c, grabDev, grabStyleK(), heldFrame.centerX(), heldFrame.centerY(), heldFrame.width(), heldFrame.height(), heldR)
                else -> { grabSettle = null; return }
            }
            if (!done) { choreographer.postFrameCallback(this); return }
            grabSettle = null
            grabDev.clear()
            c.clearStyle()
            if (grabbedFull) c.setFrame(heldFrame.centerX(), heldFrame.centerY(), heldFrame.width(), heldFrame.height(), heldR)
            if (switcherAfterSettle) { switcherAfterSettle = false; maybeEnterSwitcher() }
        }
    }

    /**
     * A grabbed card's style ([StyleDev.grab]) on the plain card the finger holds (centre [cx], [cy], [w] x [h], corners
     * [r]) at [k] (1: as it was grabbed, 0: a plain card): its own frame, the app's place and its mesh move and scale with
     * the card, and give way to the plain card as it is pulled back towards full screen.
     */
    private fun applyGrab(c: CardView, dev: StyleDev, k: Float, cx: Float, cy: Float, w: Float, h: Float, r: Float) {
        c.contentK = 1f + dev.content * k
        c.iconK = 1f + dev.icon * k
        c.boxK = dev.box * k
        c.contentFullK = dev.full * k
        if (dev.full != 0f) c.contentFrame.set(cx + (dev.gx - dev.gw / 2f) * w, cy + (dev.gy - dev.gh / 2f) * w,
            cx + (dev.gx + dev.gw / 2f) * w, cy + (dev.gy + dev.gh / 2f) * w)
        if (dev.iconFrame.isEmpty) c.iconFrame.setEmpty() else {
            // Its icon where it was on the card, on its way to filling the card (as drawn without a place of its own).
            val fill = min(w, h)
            val ix = cx + dev.ix * w * k
            val iy = cy + dev.iy * w * k
            val size = fill + (dev.iw * w - fill) * k
            c.iconFrame.set(ix - size / 2f, iy - size / 2f, ix + size / 2f, iy + size / 2f)
        }
        val m = dev.mesh
        if (m != null && dev.genie * k > 0.001f) {
            if (grabMesh.size != m.size) grabMesh = FloatArray(m.size)
            var i = 0
            while (i < m.size) { grabMesh[i] = cx + m[i] * w; grabMesh[i + 1] = cy + m[i + 1] * w; i += 2 }
            c.genieMesh = grabMesh
            c.genieK = dev.genie * k
            c.invalidate()   // (the same array, moved)
        } else c.genieMesh = null
        if (dev.alpha != 0f) c.alpha = (1f + dev.alpha * k).coerceIn(0f, 1f)
        if (dev.framed) {
            fun mix(a: Float, b: Float) = a + (b - a) * k
            c.setFrame(mix(cx, cx + dev.fx * w), mix(cy, cy + dev.fy * w), mix(w, dev.fw * w), mix(h, dev.fh * w), mix(r, dev.fr * w))
        }
    }

    /**
     * The flight's style at [qs] (0..1 along its way) on a flight [q] along: the card's own drawing (on top of what the
     * start's style left, fading out in the first half, while a reopened card's style comes in: [flightBlend]) and, for the
     * styles whose card is not a plain frame, its frame ([styleFrame], [styleRadius]).
     */
    private fun applyStyle(c: CardView, q: Float, qs: Float, w: Float, h: Float, cx: Float, cy: Float, radius: Float) {
        styleFrame.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        styleRadius = radius
        val keep = 1f - AppStyles.step(0f, 0.5f, q)
        // How much of this flight's own style shows: all of it, or (a reopened card) as what the card showed goes.
        val inK = if (flightBlend) 1f - keep else 1f
        val d = styleDev0
        c.iconFrame.setEmpty()
        if (flightStyle == AppStyle.ZOOM && !d.any) return
        // What the start's style left ([StyleDev.grab]): on the plain card as it moves, fading out in the first half.
        var content = d.content * keep
        var icon = d.icon * keep
        var box = d.box * keep
        var full = d.full * keep
        var ownFull = false
        var alpha = d.alpha * keep
        var genieK = d.genie * keep
        var mesh: FloatArray? = null
        val dm = d.mesh
        if (dm != null && genieK > 0.001f) {
            if (startMesh.size != dm.size) startMesh = FloatArray(dm.size)
            var i = 0
            while (i < dm.size) { startMesh[i] = cx + dm[i] * w; startMesh[i + 1] = cy + dm[i + 1] * w; i += 2 }
            mesh = startMesh
        }
        if (d.framed && keep > 0f) {
            fun mix(a: Float, b: Float) = a + (b - a) * keep
            val fx = mix(cx, cx + d.fx * w)
            val fy = mix(cy, cy + d.fy * w)
            val fw = mix(w, d.fw * w)
            val fh = mix(h, d.fh * w)
            styleFrame.set(fx - fw / 2, fy - fh / 2, fx + fw / 2, fy + fh / 2)
            styleRadius = mix(radius, d.fr * w)
        }
        if (!d.iconFrame.isEmpty && keep > 0f) {
            // Its icon where it was on the card, on its way to filling the card.
            val fill = min(w, h)
            val ix = cx + d.ix * w * keep
            val iy = cy + d.iy * w * keep
            val size = fill + (d.iw * w - fill) * keep
            c.iconFrame.set(ix - size / 2f, iy - size / 2f, ix + size / 2f, iy + size / 2f)
        }
        // Toward the app (0: the icon's end, 1: the app's).
        val k = if (flightOpening) qs else 1f - qs
        when (flightStyle) {
            AppStyle.CONTAINER -> {
                content += (AppStyles.containerContent(k) - 1f) * inK
                icon += (AppStyles.containerIcon(k) - 1f) * inK
                box += AppStyles.step(0.1f, 0.3f, k) * (1f - AppStyles.step(0.8f, 0.95f, k)) * inK
            }
            AppStyle.CIRCLE -> if (flightOpening && !flightIcon.isEmpty) {
                // A window onto the app as it is on the screen, growing from its icon; at the end the plain full card.
                if (qs < 0.999f) {
                    val ix = flightIcon.centerX()
                    val iy = flightIcon.centerY()
                    val r = AppStyles.revealRadius(qs, ix, iy, flightIcon.width(), sw, sh)
                    // (A reopened card's frame turns into the window as what it showed goes.)
                    styleFrame.set(styleFrame.left + (ix - r - styleFrame.left) * inK, styleFrame.top + (iy - r - styleFrame.top) * inK,
                        styleFrame.right + (ix + r - styleFrame.right) * inK, styleFrame.bottom + (iy + r - styleFrame.bottom) * inK)
                    styleRadius += (r - styleRadius) * inK
                    full += inK
                    ownFull = true
                    // The icon stays where it is, its own size, fading as the circle grows around it.
                    c.iconFrame.set(flightIcon)
                }
            } else {
                // Going home: the card rounds into a bubble (a circle: its long side drawn in to its short one) on its way,
                // and takes the icon's corners as it lands.
                val round = AppStyles.step(0f, 0.5f, q)
                val side = min(styleFrame.width(), styleFrame.height())
                val bw = styleFrame.width() + (side - styleFrame.width()) * round
                val bh = styleFrame.height() + (side - styleFrame.height()) * round
                val bx = styleFrame.centerX()
                val by = styleFrame.centerY()
                styleFrame.set(bx - bw / 2, by - bh / 2, bx + bw / 2, by + bh / 2)
                var r = styleRadius + (min(bw, bh) / 2f - styleRadius) * round
                r += (bw * Icons.shape.clipFraction() - r) * AppStyles.step(0.85f, 1f, q)
                styleRadius = r
            }
            AppStyle.FADE -> alpha += ((if (flightOpening) qs else 1f - qs).coerceIn(0f, 1f) - 1f) * inK
            AppStyle.GENIE -> if (!flightIcon.isEmpty) {
                // The app (the whole screen opening, the card as it began going home) and its icon; opening pours it out.
                if (flightOpening) genieA.set(0f, 0f, sw, sh)
                else genieA.set(flightFrom.left, flightFrom.top, flightFrom.right, flightFrom.bottom)
                genieB.set(flightIcon.left, flightIcon.top, flightIcon.right, flightIcon.bottom)
                AppStyles.genie(genieMesh, AppStyles.GENIE_COLS, AppStyles.GENIE_ROWS, genieA, genieB, if (flightOpening) 1f - qs else qs)
                val left = mesh
                if (left != null && left.size == genieMesh.size && inK < 0.999f) {
                    // A genie going home, reopened: its own mesh bends into this one as it goes (its first frame is that
                    // mesh as it was: replaced at once, the funnel snapped flat for a frame).
                    var j = 0
                    while (j < genieMesh.size) { genieMesh[j] = left[j] + (genieMesh[j] - left[j]) * inK; j++ }
                    genieK = 1f
                } else {
                    // (A reopened card's plain grid bends into the mesh as what it showed goes: the card blends a mesh into
                    // its frame's grid by this, and its outline rounds the frame's corners meanwhile.)
                    genieK = inK
                }
                mesh = genieMesh
                // Its frame: the mesh's bounds, with the card's corners (its far corners are the bounds' own).
                var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var rr = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
                var i = 0
                while (i < genieMesh.size) {
                    val x = genieMesh[i]; val y = genieMesh[i + 1]
                    if (x < l) l = x; if (x > rr) rr = x; if (y < t) t = y; if (y > b) b = y
                    i += 2
                }
                styleFrame.set(styleFrame.left + (l - styleFrame.left) * inK, styleFrame.top + (t - styleFrame.top) * inK,
                    styleFrame.right + (rr - styleFrame.right) * inK, styleFrame.bottom + (b - styleFrame.bottom) * inK)
                styleRadius = radius
                c.iconFrame.set(flightIcon)
                // Poured back, it turns into the icon once it is in (by the plain card's size, it faded while its last rows
                // were still pouring).
                if (!flightOpening) c.iconMix = max(springMix0 * (1f - q), AppStyles.step(GENIE_ICON_FROM, 1f, q))
            }
            else -> Unit
        }
        c.contentK = 1f + content
        c.iconK = 1f + icon
        c.boxK = box.coerceIn(0f, 1f)
        c.contentFullK = full.coerceIn(0f, 1f)
        when {
            ownFull -> c.contentFrame.setEmpty()
            d.full != 0f -> c.contentFrame.set(cx + (d.gx - d.gw / 2f) * w, cy + (d.gy - d.gh / 2f) * w,
                cx + (d.gx + d.gw / 2f) * w, cy + (d.gy + d.gh / 2f) * w)
            else -> c.contentFrame.setEmpty()
        }
        if (mesh != null && genieK > 0.001f) {
            c.genieMesh = mesh
            c.genieK = genieK
            c.invalidate()   // (the same array, moved)
        } else c.genieMesh = null
        if (flightStyle == AppStyle.FADE || d.alpha != 0f) c.alpha = (1f + alpha).coerceIn(0f, 1f)
    }

    // The frame and corners a style draws the flight's card with (see [applyStyle]), and the plain card it is drawn on (the
    // springs'; what a finger grabbing the card takes).
    private val styleFrame = RectF()
    private var styleRadius = 0f
    private val plainFrame = RectF()
    private var plainR = 0f

    /** Shared by HOME and LAUNCH: frame, corners and icon crossfade from the springs. */
    private fun applyCardSprings(t: Double): Boolean {
        val c = cur ?: return true
        val w = sW.value(t)
        val h = sH.value(t)
        val target = sW.target
        // Progress of the size from where the springs started to their target.
        val q = if (abs(target - springW0) > 1f) ((w - springW0) / (target - springW0)).coerceIn(0f, 1f) else 1f
        val radius: Float
        if (springToIcon) {
            // Into an icon: corners to the icon's, snapshot crossfades into the icon over the last stretch.
            radius = springR0 + (target * Icons.shape.clipFraction() - springR0) * q
            c.iconMix = max(springMix0 * (1f - q), 1f - ((w - target) / (target * 1.5f)).coerceIn(0f, 1f))
        } else {
            // To full screen (opening, or springing back): corners to the display's, any icon fades as the card grows. (Into
            // the mini player: to its corners.)
            radius = springR0 + (springRadiusEnd - springR0) * q
            if (pipFlight) { c.alpha = springAlpha0 + (1f - springAlpha0) * q; c.focusMix = q }
            c.iconMix = springMix0 * (1f - ((w - springW0) / (max(springW0, dp(40)) * 1.5f)).coerceIn(0f, 1f))
            if (softPicture != null) softStartStep(c, w)
        }
        var r = radius
        if (springFade) {
            // The app itself, smaller, with the display's corner shape scaled down, fading away (late for an app going into
            // picture-in-picture: its mini player is told of ~150-300 ms in and the card flies into it, never half faded).
            c.iconMix = 0f
            c.alpha = 1f - ((q - springFadeFrom) / (0.85f - springFadeFrom)).coerceIn(0f, 1f)
            r = deviceRadius * (w / sw)
        }
        // Its style (motion/AppStyles): sliding moves along its height (its size does not change); a reopened card's style is
        // where the card is on the whole way from its icon (taken up part way, not from its start).
        val iw = flightIcon.width()
        val sq = when {
            flightStyle == AppStyle.SLIDE && abs(sCy.target - springCy0) > 1f -> ((sCy.value(t) - springCy0) / (sCy.target - springCy0)).coerceIn(0f, 1f)
            flightBlend && !flightIcon.isEmpty && sw - iw > 1f -> ((w - iw) / (sw - iw)).coerceIn(0f, 1f)
            else -> q
        }
        val px = sCx.value(t)
        val py = sCy.value(t)
        plainFrame.set(px - w / 2, py - h / 2, px + w / 2, py + h / 2)
        plainR = r
        applyStyle(c, q, sq, w, h, px, py, r)
        c.setFrame(styleFrame.centerX(), styleFrame.centerY(), styleFrame.width(), styleFrame.height(), styleRadius)
        backdrop?.depth = sDepth.value(t)
        return sCx.settled(t) && sCy.settled(t) && sW.settled(t) && sH.settled(t)
    }

    // A launch from a home still blurred ([beginLaunch]): the picture of home without the tapped icon, put on once the card
    // covers that icon (null: none waiting).
    private var softPicture: HomePicture? = null
    /** Home's blur (px) from which a launch comes in over its blurred icon; below it the icon looks sharp anyway. */
    private const val SOFT_START_BLUR_PX = 1.5f
    /** The soft start's card is opaque, and covers its icon's blur, once it has grown by this much. */
    private const val SOFT_FADE_GROWTH = 0.4f

    /** The soft start's card comes in over its blurred icon as it grows (into focus), and covers it by [SOFT_FADE_GROWTH]. */
    private fun softStartStep(c: CardView, w: Float) {
        val grown = (w - springW0) / max(1f, springW0)
        c.alpha = (grown / SOFT_FADE_GROWTH).coerceIn(0f, 1f)
        if (grown >= SOFT_FADE_GROWTH) softSwap()
    }

    /**
     * The card covers its icon: home's picture without that icon goes on (the icon was showing, blurred, under the card
     * coming in), with the patch of a card still landing, or that card, already in its icon, goes now.
     */
    private fun softSwap() {
        val p = softPicture ?: return
        softPicture = null
        cur?.let { it.alpha = 1f; it.flatFade = false }
        backdrop?.picture = p
        if (landWaiting) { landWaiting = false; landed(System.nanoTime()) } else applyLandPatch()
    }

    /** Current card frame and velocity: from the springs if they are running, else at rest. */
    private fun cardMotion(): FloatArray {
        val c = cur ?: return floatArrayOf(sw / 2, sh / 2, sw, sh, 0f, 0f, 0f, 0f)
        if (animating && anim != Anim.SWITCH_COMMIT && anim != Anim.SWITCH_CANCEL) {
            val t = max(0L, System.nanoTime() - springStartNs) / 1e9
            return floatArrayOf(sCx.value(t), sCy.value(t), sW.value(t), sH.value(t), sCx.velocity(t), sCy.velocity(t), sW.velocity(t), sH.velocity(t))
        }
        return floatArrayOf(c.cx, c.cy, c.w, c.h, 0f, 0f, 0f, 0f)
    }

    /** Where home's depth is now: from the spring while cards animate, else what the backdrop shows. */
    private fun depthNow(): Float {
        if (animating && anim != Anim.SWITCH_COMMIT && anim != Anim.SWITCH_CANCEL) {
            return sDepth.value(max(0L, System.nanoTime() - springStartNs) / 1e9)
        }
        return backdrop?.depth ?: 0f
    }

    // ================================================================== LAUNCH

    /**
     * A card is bringing [pkg] up (a launch, a quick switch): the banners take it up as what is behind them, the status bar
     * its tone (the one it last asked for, else the one its launch colour [color] wants), as the card starts.
     */
    private fun cardBrings(pkg: String, color: Int?) {
        bringing = pkg
        closingHome = false
        val dark = appDark[pkg] ?: color?.let { luminance(it) > 0.6f } ?: HomeBridge.homeStatusDark
        statusBar?.setDark(dark)
        shade?.behindChanged()
    }

    /** A close into home is committed: the banners take up home, the status bar home's tone, as the card starts to go. */
    private fun cardGoesHome() {
        bringing = null
        closingHome = true
        applyBarVisible()
        statusBar?.setDark(HomeBridge.homeStatusDark)
        shade?.behindChanged()
    }

    private fun luminance(c: Int): Float =
        (0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)) / 255f

    private fun beginLaunch(pkg: String, iconRect: RectF, icon: Drawable?, start: () -> Unit) {
        if (phase == Phase.DRAG_HOME || phase == Phase.DRAG_SWITCH || !prepareCardWindow()) { main.post(start); return }
        val c = cur ?: run { main.post(start); return }
        // Tapping the icon of the app whose card is closing into it reopens it from where the card is (open/close the same
        // app within 0.1 s must stay smooth).
        val reverse = root?.visibility == View.VISIBLE && cardPkg == pkg
        if (reverse) softSwap()   // (a card still coming in over its icon: as it would be once over it)
        // Where the card starts and how fast it moves: from where it is if this is the same app (reversal), else the icon.
        val m = if (reverse) cardMotion() else floatArrayOf(iconRect.centerX(), iconRect.centerY(), iconRect.width(), iconRect.height(), 0f, 0f, 0f, 0f)
        // (Taken from the flight's springs: its plain card's corners too, whatever its style drew.)
        val mR = if (reverse && animating && anim != Anim.SWITCH_COMMIT && anim != Anim.SWITCH_CANCEL) plainR else Float.NaN
        // Another app's card still closing goes on landing (read before hideCards ends its session).
        val landing = if (!reverse) landingFor(pkg) else null
        // Home recedes from where it is: a close under way has it part way (it started sharp, and the blur snapped away).
        val depth0 = if (reverse || (root?.visibility == View.VISIBLE && anim == Anim.HOME_COMMIT)) depthNow() else 0f
        if (!reverse) hideCards()
        gen++
        val g = gen
        animating = false
        onSettled = null
        waitingFor = null
        val since = SystemClock.uptimeMillis()
        phase = Phase.ANIM
        anim = Anim.LAUNCH
        dragStartedAt = since
        cardPkg = pkg
        fg = null
        homeStarted = false
        // How it opens (motion/AppStyles). Reopening a closing card plays it too, taken up from where the card is (Matheesha:
        // a launch during a close "would revert back to the default animation"); sliding up and fading in have no way from
        // a card part way down to its icon: it grows back as it is.
        val chosen = AppStyles.opening()
        val style = if (reverse && !chosen.fromIcon) AppStyle.ZOOM else chosen
        // Sliding up and fading in do not come out of the icon: it stays on home, and in home's picture (recorded at the tap).
        val fromIcon = style.fromIcon
        val atTap = HomeBridge.takeAtLaunch(pkg)
        val picture = if (fromIcon) HomeBridge.previewFor(pkg) else atTap ?: HomeBridge.preview
        // Reopening a closing app whose picture of home was already dropped: cover home again (the app starts at once).
        if (reverse && backdrop?.picture == null) backdrop?.picture = picture
        pictureDropped = false
        if (!reverse) {
            c.homePicture = null
            c.icon = icon
            c.badge = if (HomeBridge.showsBadgeAt(pkg, iconRect)) Badges.count(pkg) else 0   // as the icon it grows out of
            c.unitPx = dev.launcher.app.design.Scale.unitPx(app)
            c.snapshot = images[pkg]
            c.minIconSize = iconRect.width()
            // The app's own launch-screen colour (resolved ahead of time for home's apps), else the icon's colour.
            c.placeholderColor = SplashColors.cached(pkg) ?: icon?.let { averageColor(it) } ?: DEFAULT_SPLASH
            if (SplashColors.cached(pkg) == null) SplashColors.resolve(app, pkg) { col -> nav.post { if (cardPkg == pkg) c.fadePlaceholderTo(col) } }
            c.clearStyle()
            c.setFrame(m[0], m[1], m[2], m[3], m[2] * Icons.shape.clipFraction())
            c.iconMix = 1f
            when (style) {
                // Up from below the screen, the app at full size.
                AppStyle.SLIDE -> { c.setFrame(sw / 2, sh * (1.5f + AppStyles.SLIDE_BELOW), sw, sh, deviceRadius); c.iconMix = 0f }
                // In place, a little smaller, faded out.
                AppStyle.FADE -> { c.setFrame(sw / 2, sh / 2, sw * AppStyles.FADE_FROM, sh * AppStyles.FADE_FROM, deviceRadius * AppStyles.FADE_FROM); c.iconMix = 0f; c.alpha = 0f }
                else -> Unit
            }
            prv?.visibility = View.GONE
            root?.setBackgroundColor(0)
            // Home's picture zooms toward the icon for the dolly zoom (around the middle otherwise): not under a card still landing,
            // which flies with home's picture as it is.
            if (landing == null) backdrop?.setDolly(style == AppStyle.DOLLY, iconRect.centerX(), iconRect.centerY())
            // From a home still blurred (another app's card was closing): the tapped icon stays in the picture, blurred like
            // home, and the card comes in over it as it grows (into focus, [softStartStep]); the picture without the icon
            // goes on once the card covers it. (Started sharp over a blurred home, the tapped icon snapped into focus.)
            val blur0 = depth0.coerceIn(0f, 1f) * Motion.profile.homeDepthBlur * app.resources.displayMetrics.density
            val first = if (picture == null || blur0 < SOFT_START_BLUR_PX || style != AppStyle.ZOOM) null
                else if (landing != null) landing.without ?: HomeBridge.preview else HomeBridge.preview
            softPicture = if (first != null) picture else null
            c.flatFade = first != null
            c.alpha = if (first != null || style == AppStyle.FADE) 0f else 1f
            backdrop?.picture = first ?: picture
            backdrop?.depth = depth0
            // (Where this app's icon is on home, at rest: the landing card's patch leaves it alone.)
            landing?.start?.invoke(HomeBridge.iconRect(pkg) ?: RectF(iconRect).also { r ->
                val z = contentZoom(depth0)
                r.set(sw / 2 + (r.left - sw / 2) / z, sh / 2 + (r.top - sh / 2) / z, sw / 2 + (r.right - sw / 2) / z, sh / 2 + (r.bottom - sh / 2) / z)
            })
        }
        appStarted = false
        pendingStart = Runnable(start)
        // The icon on home goes only once the card (drawing that icon) is on screen: hidden at once, home's next frame came a
        // refresh before the card window's first one and the icon blinked out (seen in folder launches on the S24).
        if (reverse) hideIcon(pkg) else if (fromIcon) afterCardFrame(g) { if (cardPkg == pkg) hideIcon(pkg) }
        cardIconSize = iconRect.width()
        switchAt = 0L   // a launch ends any run of quick switches
        beginCardSprings(toIcon = false, style, opening = true, icon = iconRect, fresh = !reverse,
            from = if (reverse) RectF(m[0] - m[2] / 2, m[1] - m[3] / 2, m[0] + m[2] / 2, m[1] + m[3] / 2) else null,
            fromR = mR)
        val open = Motion.profile.appOpen
        // Where the card starts: the icon (or, reversing, where it is); sliding, below the screen; fading, in place.
        val f0 = when {
            reverse || style == AppStyle.ZOOM || fromIcon -> m
            style == AppStyle.SLIDE -> floatArrayOf(sw / 2, sh * (1.5f + AppStyles.SLIDE_BELOW), sw, sh, 0f, 0f, 0f, 0f)
            else -> floatArrayOf(sw / 2, sh / 2, sw * AppStyles.FADE_FROM, sh * AppStyles.FADE_FROM, 0f, 0f, 0f, 0f)
        }
        sCx = open.mover().apply { start(f0[0], f0[4], sw / 2) }
        sCy = open.mover().apply { start(f0[1], f0[5], sh / 2) }
        sW = open.mover().apply { start(f0[2], f0[6], sw) }
        sH = open.mover().apply { start(f0[3], f0[7], sh) }
        // Home steps back behind the app (the container transform leaves it where it is).
        sDepth = Motion.profile.homeDepthOpen.mover().apply { start(depth0, 0f, if (style == AppStyle.CONTAINER) 0f else 1f) }
        endLabel = "launch $pkg${if (reverse) " (reversed a closing card)" else ""}${if (style != AppStyle.ZOOM) " (${style.id})" else ""}"
        cardBrings(pkg, c.placeholderColor)
        val startApp = { runPendingStart() }
        // The card goes once the app's window is on screen, not only reported in front (see [whenAppShown]).
        val fadeOut = { whenAppShown(pkg, since, g) { fadeOutCards(Motion.role(dev.launcher.app.motion.MotionTokens.CARD_FADE_APP), g) } }
        onSettled = {
            startApp()   // without a picture of home the app is only started now, so it cannot show early
            phase = Phase.HOLD
            if (lastFrontPkg == pkg && lastFrontAt >= since) fadeOut()
            else awaitForeground(pkg, g) {
                // Our own screens (the developer panel, safe settings) say when they have drawn (ownScreenDrawn).
                if (lastFrontPkg == pkg || pkg == app.packageName) { fadeOut(); return@awaitForeground }
                // A window of another package can belong to the tapped app's own task (Settings showing Samsung's wallpaper
                // picker, seen on the S24): only another task in front is the wrong app.
                val s = ShizukuLink.service
                val other = lastFrontPkg
                tasksIo.execute {
                    val top = try { s?.let { parseTasks(it.recentTasks(1)).firstOrNull()?.pkg } } catch (_: Throwable) { null }
                    nav.post {
                        if (gen != g) return@post
                        if (top != pkg) {
                            // Not the app that was tapped: ask for the tapped one again.
                            AppLog.log("[front] WRONG APP after launching $pkg: $other is in front (top task $top); starting $pkg again")
                            bringBack(pkg, null)
                            fadeOutCards(Motion.role(dev.launcher.app.motion.MotionTokens.CARD_FADE_APP), g)
                        } else fadeOut()
                    }
                }
            }
        }
        showCards()
        if (picture != null) {
            if (reverse) startApp()   // the window is already on screen
            else {
                // Start the app once our window (picture + icon card) has been drawn, so it covers the app from its first frame.
                val r = root
                r?.viewTreeObserver?.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
                    override fun onDraw() {
                        nav.post { r.viewTreeObserver.removeOnDrawListener(this) }
                        choreographer.postFrameCallback { startApp() }
                    }
                })
                nav.postDelayed({ startApp() }, 100)   // never wait longer than this
            }
        }
        stats.reset()
        startSprings()
        stats.start()
        // A warm app has a snapshot of how it will look: show it as the card grows.
        val s = ShizukuLink.service ?: return
        snapIo.execute {
            val task = try { parseTasks(s.recentTasks(12)).firstOrNull { it.pkg == pkg } } catch (_: Throwable) { null } ?: return@execute
            val b = try { snapshot(s, task.id, false) } catch (_: Throwable) { null } ?: return@execute
            remember(pkg, b)
            nav.post { if (gen == g && cardPkg == pkg) { c.fadeToSnapshot(b); fg = task } }
        }
    }

    /**
     * "Start this without a system transition" as an ActivityOptions bundle: only the fallback when our own instant
     * transitions ([NoAnimStarts]) are not available. One UI ignores it for home coming to the front, which is why the
     * fallback also switches the animation scales off around gestures ([holdScalesOff]).
     */
    fun noAnimation(ctx: Context): android.os.Bundle = android.app.ActivityOptions.makeCustomAnimation(ctx, 0, 0).toBundle()

    /** Starts the app of the current launch, once. */
    private fun runPendingStart() {
        val r = pendingStart ?: return
        pendingStart = null
        appStarted = true
        front("launch $cardPkg") { r.run() }
    }

    // Every "bring something to the front" goes through one ordered queue. Each request gets a number; when it is its turn
    // and a newer request exists, it is dropped. So a late switch or home start can never land on top of the app the user
    // asked for after it (that is how a tap on one icon could open another app).
    private val frontIo = Executors.newSingleThreadExecutor()
    private val frontSeq = java.util.concurrent.atomic.AtomicInteger()

    private fun front(label: String, op: () -> Unit) {
        val seq = frontSeq.incrementAndGet()
        frontIo.execute {
            if (seq != frontSeq.get()) { AppLog.log("[front] dropped '$label' (a newer request came in)"); return@execute }
            AppLog.log("[front] $label")
            try { op() } catch (t: Throwable) { AppLog.log("[front] $label failed: ${t.javaClass.simpleName}: ${t.message}") }
        }
    }

    // System transitions (animation scales) are off from the first touch of a gesture or a dock tap until a second after the
    // last card session ended: with them on, home's own system transition held back taps or sent them to the neighbouring
    // icon. The second keeps fast open/close runs from toggling them; after it, apps have their own transitions again.
    // On the start queue, never dropped, so they are always off before anything is started.
    private const val SCALES_BACK_MS = 1000L
    /** After a start our card covered (quietStarts): the system's transition starts once the app has drawn, after our card. */
    private const val QUIET_BACK_MS = 1500L
    private const val QUIET_SETTLE_MS = 100L
    /** The gesture bar's pill fading out as a panel opens, and back in once it has closed. */
    private var fingerDown = false

    private fun holdScalesOff() {
        nav.removeCallbacks(scalesBack)
        // Once our own instant transitions are confirmed to work (NoAnimStarts), starts skip the system animation by
        // themselves: nothing global changes during a gesture. Until then the scales are still switched as a safety net.
        if (NoAnimStarts.confirmed) return
        val s = ShizukuLink.service ?: return
        frontIo.execute {
            try { SystemRestore.scalesOffForCards(app, s) } catch (t: Throwable) { AppLog.log("[nav] transitions off failed: ${t.javaClass.simpleName}: ${t.message}") }
        }
    }

    private fun releaseScalesLater() {
        nav.removeCallbacks(scalesBack)
        nav.postDelayed(scalesBack, SCALES_BACK_MS)
    }

    private val scalesBack = Runnable {
        if (fingerDown || phase != Phase.IDLE) return@Runnable   // still in a session: its end schedules this again
        val s = ShizukuLink.service ?: return@Runnable
        frontIo.execute { restoreScales(s) }
    }

    private fun restoreScales(s: IShellService) {
        try { SystemRestore.restoreScalesIfChanged(app, s) } catch (t: Throwable) { AppLog.log("[nav] transitions back failed: ${t.javaClass.simpleName}: ${t.message}") }
    }

    /** A colour for the card behind an icon (like a splash screen): the icon's average colour. */
    private fun averageColor(d: Drawable): Int = try {
        val b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val old = d.bounds
        d.setBounds(0, 0, 8, 8)
        d.draw(cv)
        d.bounds = old
        var r = 0; var g = 0; var bl = 0; var n = 0
        for (x in 0 until 8) for (y in 0 until 8) {
            val p = b.getPixel(x, y)
            if ((p ushr 24) < 128) continue
            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; bl += p and 0xFF; n++
        }
        if (n == 0) 0xFF2A2F3A.toInt() else (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (bl / n)
    } catch (_: Throwable) { 0xFF2A2F3A.toInt() }

    // ================================================================== SWITCH

    // A run of quick switches keeps its own order of apps (the system reorders recents after every switch, which would
    // otherwise always give "the app I just left"): swipe right = one step older, swipe left = one step back newer.
    private var switchList: List<Task> = emptyList()
    private var switchIndex = 0
    private var switchAt = 0L                  // last switch of the run; the run ends after a pause, a launch or going home
    private var recentList: List<Task> = emptyList()
    private var recentAll: List<Task> = emptyList()   // every recent app (fetched after the quick lookup of the first few)
    private var recentAllMs = -1L
    private var picturesAsked = 0   // the open switcher's picture requests (for the log)
    private var picturesFull = 0
    private var picturesGot = 0

    /** The recent apps for the App Switcher: the quick lookup's (newest), then the older ones from the full list. */
    private fun switcherRecents(): List<Task> {
        val ids = recentList.mapTo(HashSet()) { it.id }
        return recentList + recentAll.filter { it.id !in ids }
    }
    private var older: Task? = null
    private var newer: Task? = null
    private var switchTarget: Task? = null
    private var switchTargetIsOlder = true
    private const val SWITCH_RUN_MS = 4000L

    // A switch that started on the home screen: home itself is the current card, the recent apps are to its left.
    private var switchFromHome = false
    private val homeTask by lazy { Task(-1, app.packageName) }

    private fun beginSwitch(fromHome: Boolean = false) {
        if (!prepareCardWindow()) return
        val fromGrab = root?.visibility == View.VISIBLE
        gen++
        phase = Phase.DRAG_SWITCH
        anim = Anim.NONE
        switchFromHome = fromHome
        if (fromHome) {
            dragStartedAt = SystemClock.uptimeMillis()
            cardVisibleAfter = -1
            cardPkg = null
            fg = null
            cur?.let { c -> c.snapshot = null; c.icon = null; c.homePicture = HomeBridge.preview }
            refreshHomeSwitch()
            cur?.iconMix = 0f
            offset = 0f
            switchScale = 1f
            backdrop?.picture = null
            root?.setBackgroundColor(0xFF000000.toInt())
            layoutSwitch(0f, 1f)
            if (HomeBridge.preview != null) showCards()
            stats.start()
            return
        }
        if (!fromGrab) {
            dragStartedAt = SystemClock.uptimeMillis()
            cardVisibleAfter = -1
            val f = fg
            cardPkg = f?.pkg
            cur?.let { c -> setCardContent(c, f?.pkg, fgFresh ?: recentImage(f?.pkg)) }
        }
        // Continue the run if it is recent and we are on the app it left us in; else start a new run from the recents.
        val now = SystemClock.uptimeMillis()
        val runGoesOn = now - switchAt < SWITCH_RUN_MS && switchList.getOrNull(switchIndex)?.pkg == (cardPkg ?: lastFrontPkg)
        // A quick flick turns sideways before the task lookup is back (the app in front not known yet): the new run is built
        // from the previous gesture's order with the app the window events put in front, and again when the lookup comes
        // (onTasks). Built from the old order alone, the app in front came second and "one older" switched to itself (S24).
        runAwaitsLookup = !runGoesOn && fg == null
        if (!runGoesOn) newRun(fg ?: lastFrontPkg?.let { p -> recentList.firstOrNull { it.pkg == p } })
        cur?.iconMix = 0f
        // (A launch grabbed at full size and pulled sideways: what was left of its style goes; the switch lays the cards out.)
        cur?.clearStyle()
        grabDev.clear()
        grabSettle = null
        offset = 0f
        switchScale = 1f
        backdrop?.picture = null
        root?.setBackgroundColor(0xFF000000.toInt())   // what is revealed beside the cards
        showNeighbours()
        layoutSwitch(0f, 1f)
        maybeShow()
        stats.start()
    }

    /** The run's apps from the newest recent, [front] (the app in front) first. */
    private fun newRun(front: Task?) {
        val list = recentList.switchable().toMutableList()
        // The app in front by its package; our own tasks by their id (the settings are not the token editor in front).
        val same: (Task) -> Boolean = { t -> t.pkg == front?.pkg && (t.pkg != app.packageName || t.id == front.id) }
        if (front != null && list.firstOrNull()?.let(same) != true) { list.removeAll(same); list.add(0, front) }
        switchList = list
        switchIndex = 0
    }

    /** The cards beside the current one: the run's next older and newer apps. */
    private fun showNeighbours() {
        older = switchList.getOrNull(switchIndex + 1)
        // Sideways only ever goes through apps, never back to home (a run that started on home keeps it out).
        newer = switchList.getOrNull(switchIndex - 1)?.takeIf { it !== homeTask }
        prv?.let { pc -> setCardContent(pc, older?.pkg, older?.let { images[it.pkg] }); pc.visibility = if (older != null) View.VISIBLE else View.GONE }
        nxt?.let { nc -> setCardContent(nc, newer?.pkg, newer?.let { images[it.pkg] }); nc.visibility = if (newer != null) View.VISIBLE else View.GONE }
        loadNeighbourImages()
    }

    /** The current run was started before the task lookup of this gesture was back. */
    private var runAwaitsLookup = false

    /** Home switch: the run is home followed by the recent apps (known once the task list has arrived). */
    private fun refreshHomeSwitch() {
        switchList = listOf(homeTask) + recentList.switchable()
        switchIndex = 0
        older = switchList.getOrNull(1)
        newer = null
        prv?.let { pc -> setCardContent(pc, older?.pkg, older?.let { images[it.pkg] }); pc.visibility = if (older != null) View.VISIBLE else View.GONE }
        nxt?.visibility = View.GONE
        loadNeighbourImages()
        layoutSwitch(offset, switchScale)
    }

    /** Cached snapshots of the neighbours (how they look when they come back), if we do not have them yet. */
    private fun loadNeighbourImages() {
        val s = ShizukuLink.service ?: return
        val g = gen
        for ((task, card) in listOf(older to prv, newer to nxt)) {
            task ?: continue
            snapIo.execute {
                val b = try { snapshot(s, task.id, false) } catch (_: Throwable) { null } ?: return@execute
                remember(task.pkg, b)
                nav.post { if (gen == g && phase == Phase.DRAG_SWITCH) card?.snapshot = b }
            }
        }
    }

    private fun dragSwitch(x: Float) {
        val dx = x - downX
        // Towards a neighbour that exists: 1:1 with the finger. Towards nothing: a rubber band.
        offset = if ((dx > 0 && older != null) || (dx < 0 && newer != null)) dx else dx * 0.25f
        switchScale = 1f - 0.06f * (abs(offset) / dp(60)).coerceIn(0f, 1f)
        layoutSwitch(offset, switchScale)
    }

    private fun gap() = dp(16)

    private fun layoutSwitch(off: Float, s: Float) {
        val w = sw * s
        val h = sh * s
        val cx = sw / 2 + off
        cur?.setFrame(cx, sh / 2, w, h, deviceRadius)
        prv?.setFrame(cx - w - gap(), sh / 2, w, h, deviceRadius)   // older app, on the left
        nxt?.setFrame(cx + w + gap(), sh / 2, w, h, deviceRadius)   // newer app, on the right
    }

    private fun releaseSwitch(up: Boolean, vx: Float) {
        val g = gen
        // A quick flick can end before the current app's image arrived: animate anyway (its launch screen stands in).
        showCards()
        val toOlder = up && older != null && ((offset > sw * 0.3f && vx > -300f) || (vx > 600f && offset > dp(20)))
        val toNewer = up && newer != null && ((offset < -sw * 0.3f && vx < 300f) || (vx < -600f && offset < -dp(20)))
        val target = if (toOlder) older else if (toNewer) newer else null
        if (target != null) {
            bringBack(target.pkg, target)
            switchTarget = target
            switchTargetIsOlder = toOlder
            switchIndex += if (toOlder) 1 else -1
            switchAt = SystemClock.uptimeMillis()
            val since = switchAt
            sOff = Motion.profile.switchCommit.mover().apply { start(offset, vx, if (toOlder) sw + gap() else -(sw + gap())) }
            sScale = Motion.profile.switchCommit.mover().apply { start(switchScale, 0f, 1f) }
            anim = Anim.SWITCH_COMMIT
            endLabel = "quick switch to ${if (toOlder) "an older" else "a newer"} app (${target.pkg})"
            cardBrings(target.pkg, SplashColors.cached(target.pkg))
            onSettled = {
                phase = Phase.HOLD
                switchAt = SystemClock.uptimeMillis()
                if (lastFrontPkg == target.pkg && lastFrontAt >= since) hideCards() else awaitForeground(target.pkg, g) { hideCards() }
            }
        } else {
            sOff = Motion.profile.switchCancel.mover().apply { start(offset, vx, 0f) }
            sScale = Motion.profile.switchCancel.mover().apply { start(switchScale, 0f, 1f) }
            anim = Anim.SWITCH_CANCEL
            endLabel = "switch cancelled"
            onSettled = { hideCards() }
        }
        startSprings()
    }

    private fun applySwitchSprings(t: Double): Boolean {
        layoutSwitch(sOff.value(t), sScale.value(t))
        return sOff.settled(t) && sScale.settled(t, 0.001f)
    }

    // ================================================================== animation driver (nav thread)

    private fun startSprings() {
        phase = Phase.ANIM
        animating = true
        springStartNs = System.nanoTime()
        choreographer.postFrameCallback(springFrame)
    }

    private val springFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!animating) return
            val t = max(0L, frameTimeNanos - springStartNs) / 1e9
            val switching = anim == Anim.SWITCH_COMMIT || anim == Anim.SWITCH_CANCEL
            val done = if (switching) applySwitchSprings(t) else applyCardSprings(t)
            if (done) {
                animating = false
                AppLog.log(
                    stats.report("[nav] $endLabel") +
                        "\n  fresh snapshot ${if (fgFreshDone) "${fgSnapMs} ms" else "-"}; card visible ${cardVisibleAfter} ms after the start; " +
                        "animation ${"%.0f".format(t * 1000)} ms"
                )
                val then = onSettled
                onSettled = null
                then?.invoke()
            } else {
                choreographer.postFrameCallback(this)
            }
        }
    }

    /**
     * Fades the cards out on [curve]. [overHome]: the card is small over the picture of home, which fades with it (the real
     * home under it looks the same). Otherwise (the end of a launch) the card covers the whole screen and the picture goes at
     * once: fading it with the card let it show through the half-transparent card, a grey flash at the end of every launch
     * (screen-recorded on the S24 at 120 fps).
     */
    private fun fadeOutCards(curve: dev.launcher.app.design.Curve, g: Int, overHome: Boolean = false) {
        val r = root ?: return
        if (gen != g) return
        if (!overHome) backdrop?.alpha = 0f
        // One card drawing one picture (the end of a launch, a card fading where it is over home): the fade applies to its
        // draws, not through a layer the size of the screen allocated in the fade's first frame (the handover frame of every
        // launch took 7-9 ms of the S24's GPU; a circle reveal's tipped over into a missed refresh).
        r.forceHasOverlappingRendering(!drawsAlone(r))
        r.invalidate()
        // Set (or cleared) every time: a view's animator keeps its update listener from one animation to the next.
        r.animate().alpha(0f).timed(curve)
            .setUpdateListener(if (overHome) android.animation.ValueAnimator.AnimatorUpdateListener { backdrop?.alpha = r.alpha } else null)
            .withEndAction { if (gen == g) hideCards() }.start()
    }

    // ================================================================== task switching

    private var waitingFor: Pair<String, Runnable>? = null
    private val waitTimeout = Runnable { val w = waitingFor; waitingFor = null; w?.second?.run() }

    private val shownIo = Executors.newSingleThreadExecutor()

    /**
     * Runs [then] (nav thread) once [pkg]'s window is on screen: the start transaction of our own transition for it
     * committed after [since] (the system plays a transition only once the window coming has drawn), at the latest
     * [APP_SHOWN_MAX_MS] from now, unless the session moved on. The window events say an app is in front when its window
     * is added, before it has drawn: on a slow phone the launch card faded away over nothing (home showed for a moment
     * between the card and the app's launch screen, seen on the emulator with a cold start). Without our transitions (or an
     * older shell service) at once, as before.
     */
    private fun whenAppShown(pkg: String, since: Long, g: Int, then: () -> Unit) {
        val s = ShizukuLink.service
        // (Only a start of ours since the launch began reports when it shows: a start the system animated does not.)
        if (s == null || !NoAnimStarts.confirmed || pkg == app.packageName || NoAnimStarts.startedAt(pkg) < since) { then(); return }
        val asked = SystemClock.uptimeMillis()
        val deadline = asked + APP_SHOWN_MAX_MS
        fun check() {
            if (gen != g) return
            shownIo.execute {
                val at = try { s.packageShownAt(pkg) } catch (_: Throwable) { -1L }
                nav.post {
                    if (gen != g) return@post
                    when {
                        at < 0L || at >= since -> {
                            val waited = SystemClock.uptimeMillis() - asked
                            if (waited > 20) AppLog.log("[nav] $pkg on screen $waited ms after it was reported in front: the card waited for it")
                            then()
                        }
                        SystemClock.uptimeMillis() > deadline -> {
                            AppLog.log("[nav] $pkg not seen on screen ${APP_SHOWN_MAX_MS} ms after it was reported in front: the card goes anyway")
                            then()
                        }
                        else -> nav.postDelayed({ check() }, 8)
                    }
                }
            }
        }
        check()
    }

    /** Runs [then] once a window of [pkg] is in front (accessibility event), or after 500 ms, unless the session moved on. */
    private fun awaitForeground(pkg: String, g: Int, then: () -> Unit) {
        waitingFor = pkg to Runnable { if (gen == g) then() }
        nav.removeCallbacks(waitTimeout)
        nav.postDelayed(waitTimeout, 500)
    }

    /**
     * One of our own screens (developer panel, safe settings) has drawn its first frame: a launch card waiting for it can
     * go (accessibility events of our own package are not "an app in front"). Any thread.
     */
    fun ownScreenDrawn() = nav.post {
        val w = waitingFor ?: return@post
        if (w.first != app.packageName) return@post
        waitingFor = null
        nav.removeCallbacks(waitTimeout)
        w.second.run()
    }

    /** Brings [pkg] to the front: its task if known, else its launch intent. */
    private fun bringBack(pkg: String?, task: Task?) {
        val s = ShizukuLink.service
        // Only that app's own task: a task of another app here is how the wrong app came to the front.
        val t = task?.takeIf { pkg == null || it.pkg == pkg }
        if (t != null && s != null) {
            val task = t
            front("switch to ${task.pkg}") {
                val start = SystemClock.uptimeMillis()
                val r = if (NoAnimStarts.switchToTask(task.id)) "ok (own instant transition)"
                    else try { s.switchToTaskWithOptions(task.id, noAnimation(app)) } catch (e: Throwable) { "ERROR: ${e.message}" }
                AppLog.log("[nav] switch to ${task.pkg} (task ${task.id}): $r (${SystemClock.uptimeMillis() - start} ms)")
            }
        } else if (pkg != null) {
            front("start $pkg") {
                app.packageManager.getLaunchIntentForPackage(pkg)?.let { i ->
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (!NoAnimStarts.start(i, Process.myUserHandle().hashCode())) {
                        try { app.startActivity(i, noAnimation(app)) } catch (_: Throwable) { }
                    }
                }
            }
        }
    }

    // ================================================================== the App Switcher (hold during a home swipe)

    private var deck: dev.launcher.app.switcher.DeckView? = null
    private var input: dev.launcher.app.switcher.SwitcherInput? = null
    private var switcherHeld = false          // the swipe that opened the switcher is still down
    private var switcherDownY = 0f
    private var switcherHomeAsked = false
    /**
     * This switcher was opened over home (a swipe up on home that rested), not over an app. Kept apart from [homeVisible]:
     * the switcher's own window pauses home under it (opened from home, a tap on the app last used was taken for "still in
     * front" and home came back instead).
     */
    private var switcherOverHome = false
    private var openedAt = 0L
    private var depthAtSwitcher = 1f

    // Home behind the deck: from the drag's depth to fully receded and dimmed (0..1).
    private var switcherGround = 0   // opaque ground behind the deck when there is no picture of home (else 0)
    private val switcherBg = dev.launcher.app.motion.MotionValue(0f, 300f, onChange = { k ->
        val depth = depthAtSwitcher + (1f - depthAtSwitcher) * k
        val ground = if (switcherGround != 0) switcherGround else ((SWITCHER_DIM * k * 255).toInt().coerceIn(0, 255)) shl 24
        // A dolly zoom's close held into the App Switcher: home behind the deck steps back about the middle as it always
        // does (zoomed into the icon it was 1.55 times, the deck over a blur of a few icons), the dolly giving way as it opens.
        if (switcherDollyFrom > 0f) {
            switcherDollyK = max(switcherDollyK, k.coerceIn(0f, 1f))
            backdrop?.fadeDolly(switcherDollyFrom * (1f - switcherDollyK))
        }
        backdrop?.depth = depth
        root?.setBackgroundColor(ground)
        // The switcher's own window shows the same while the deck is there (until then it stays as it is, under the card window:
        // drawing it every frame cost both threads 2-3 ms a frame of the opening; the handover brings it up to date).
        if (win == Win.HOLDS) { backdrop?.let { back2?.followDolly(it) }; back2?.depth = depth; dim2?.setBackgroundColor(ground) }
    })
    // The dolly zoom on home's picture as the App Switcher opened, and how far it has given way since.
    private var switcherDollyFrom = 0f
    private var switcherDollyK = 0f

    private val holdCheck = Runnable { maybeEnterSwitcher() }

    // The hold: the finger stays within HOLD_RADIUS of where it rested for holdMs, with the card already small. By distance,
    // not by a velocity estimate (that reads 0 between sparse touch events, and slow drifts count as resting, as on iOS).
    private var holdAnchorX = 0f
    private var holdAnchorY = 0f
    private var holdSince = 0L

    /** How far the swipe has come up from where it started (px). */
    private fun swipeTravel() = if (phase == Phase.HOME_PULL) downY - lastDragY else lastTravel

    private fun watchForHold() {
        val now = SystemClock.uptimeMillis()
        val moved = hypot(lastDragX - holdAnchorX, lastDragY - holdAnchorY) > HOLD_RADIUS_DP * density
        if (moved || holdSince == 0L || swipeTravel() < Motion.profile.switcher.holdMinTravelDp * density) {
            holdAnchorX = lastDragX; holdAnchorY = lastDragY; holdSince = now
        }
        nav.removeCallbacks(holdCheck)
        nav.postDelayed(holdCheck, max(0L, holdSince + Motion.profile.switcher.holdMs - now))
    }

    private fun maybeEnterSwitcher() {
        if (!fingerDown) return
        if (phase == Phase.HOME_PULL && closesOnTop) return
        if (phase == Phase.DRAG_HOME) { if (root?.visibility != View.VISIBLE || catchUpAt != 0L) return }
        else if (phase != Phase.HOME_PULL) return
        if (swipeTravel() < Motion.profile.switcher.holdMinTravelDp * density) return   // re-armed by the next move
        val left = holdSince + Motion.profile.switcher.holdMs - SystemClock.uptimeMillis()
        if (left > 0L) { nav.postDelayed(holdCheck, left); return }
        // (A grabbed card's style still giving way: the deck takes the card once it is plain.)
        if (grabSettle != null) { switcherAfterSettle = true; return }
        enterSwitcher()
    }

    /**
     * The finger rested during a home swipe: the App Switcher opens around the card it holds (iOS). Recent apps, newest
     * first; card 0 is the app the swipe started in, which stays in front underneath until another app or home is chosen.
     */
    private fun enterSwitcher() {
        val c = cur ?: return
        val d = deck ?: return
        if (!prepareCardWindow()) return
        val fromHome = phase == Phase.HOME_PULL
        val tasks = switcherRecents().switchable()
        // (Our own tasks by their id: with the token editor in front, the settings' task is not the app the swipe began in.)
        val first = if (fromHome) null else tasks.firstOrNull { it.pkg == cardPkg && (it.pkg != app.packageName || it.id == fg?.id) }
            ?: fg?.takeIf { it.pkg == cardPkg }
        val list = (listOfNotNull(first) + tasks.filter { it.id != first?.id }).take(SWITCHER_MAX_CARDS)
        if (list.isEmpty()) return
        switcherOverHome = fromHome
        if (fromHome) { enterSwitcherFromHome(list); return }
        phase = Phase.SWITCHER
        updateWatch()
        switcherHeld = true
        switcherHomeAsked = false
        nav.removeCallbacks(holdCheck)
        strip?.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else android.view.HapticFeedbackConstants.CONTEXT_CLICK)
        picturesAsked = 0; picturesFull = 0; picturesGot = 0
        val cards = list.map { t -> if (t === first) newCard(t).apply { snapshot = c.snapshot; have = 2 } else newCard(t) }
        d.setScreen(sw, sh, deviceRadius)   // unchanged size: nothing to do (the card window set it up when it was created)
        d.enter(cards, android.graphics.RectF(c.cx - c.w / 2, c.cy - c.h / 2, c.cx + c.w / 2, c.cy + c.h / 2), c.radius)
        d.heldStart(lastDragX)
        c.alpha = 0f   // the deck draws this card from now on
        // Home behind the deck keeps the swipe's picture (without this app's icon, unseen under the blur): changing it here
        // re-rendered home's layers on the deck's first frame (4.4 ms, traced on the S24). It changes when home is chosen.
        // A swipe that started before home had ever been recorded has none: one now, else a plain dark ground (never the
        // app showing through the dimmed background).
        if (backdrop?.picture == null) (HomeBridge.previewFor(cardPkg) ?: HomeBridge.preview)?.let { backdrop?.picture = it }
        switcherGround = if (backdrop?.picture == null) 0xFF101418.toInt() else 0
        hideIcon(null)
        depthAtSwitcher = backdrop?.depth ?: 1f
        switcherDollyFrom = backdrop?.dollyAmount ?: 0f
        switcherDollyK = 0f
        switcherBg.snapTo(0f)
        switcherBg.animateTo(1f, Motion.profile.switcher.enter, 0f)
        maybeAskSwitcherWindow()
        // Pictures for the cards without an up-to-date one come as each card comes into view (deckListener.onWantPicture).
        refreshSwitcherCard()
        stats.reset(); stats.start()
        AppLog.log("[switcher] open: ${cards.size} apps${listNote()} (${cards.take(8).joinToString { it.pkg.substringAfterLast('.') }}); ${pictureAges(cards)}")
    }

    // ---- a swipe up on home: home recedes with the finger (iOS); a rest opens the switcher, a release springs home back

    private var pulling = false                // the picture of home is shown receding (a swipe up on home)
    private var closesOnTop = false            // this swipe on home closes what is open on top of it instead
    private var pullPicture: HomePicture? = null   // home as it showed when this swipe touched the bar
    private val pullBack = dev.launcher.app.motion.MotionValue(0f, 300f, onChange = { backdrop?.depth = it })

    private fun beginHomePull() {
        phase = Phase.HOME_PULL
        holdSince = 0L
        lastDragY = downY; lastDragX = downX
        if (pullPicture != null) showHomePull()   // else as soon as it is recorded (a frame or two)
    }

    private fun showHomePull() {
        val pic = pullPicture ?: return
        if (!prepareCardWindow()) return
        pulling = true
        dragStartedAt = SystemClock.uptimeMillis()
        cur?.alpha = 0f
        prv?.visibility = View.GONE
        nxt?.visibility = View.GONE
        root?.setBackgroundColor(0)
        backdrop?.picture = pic   // the one rendered at the touch
        // Shown late (the picture came after the swipe began): already as far back as the finger has pulled it.
        pullBack.snapTo(if (phase == Phase.HOME_PULL && lastDragY < downY) pullDepth(downY - lastDragY) else 0f)
        showCards()
        stats.reset(); stats.start()
    }

    /** How far home has receded for a swipe up of [travel] px (gives way quickly, then slower). */
    private fun pullDepth(travel: Float) = HOME_PULL_DEPTH * (1f - exp(-max(0f, travel) / (sh * 0.25f)))

    private fun pullHome() {
        if (pulling) pullBack.snapTo(pullDepth(downY - lastDragY))
    }

    /**
     * The swipe up on home ended without a rest: it does what the Home button does at once (closes a folder, Spotlight,
     * goes to the first page). The live home takes over the depth the picture had (same zoom and blur, same spring back),
     * and the picture goes as soon as home has drawn that frame: one home on screen at every moment. (Fading the picture
     * into the live home showed two of everything for a moment, e.g. the A-Z list in place and sliding away; waiting for
     * the picture to spring back first left an open folder popping back, then sliding away with the page.)
     */
    private fun releaseHomePull(up: Boolean) {
        if (up) HomeBridge.homeSwipeUp()
        if (!pulling) { finishHomePull(); return }
        val d = pullBack.value
        pullBack.stop()
        val g = gen
        val spec = Motion.profile.appCancel
        HomeBridge.homeCovered = false   // home blurs with its depth from now on: it is about to be what shows
        HomeBridge.animateDepth(d, 0f, 0f, spec, System.nanoTime())
        HomeBridge.afterHomeDraw { nav.post { if (gen == g && phase == Phase.HOME_PULL) finishHomePull() } }
    }

    /** A pull still showing when a new touch arrives: gone at once. */
    private fun finishHomePull() {
        if (phase != Phase.HOME_PULL) return
        pullBack.stop()
        pulling = false
        phase = Phase.IDLE
        hideCards()
    }

    /**
     * The App Switcher from the home screen (a swipe up that rests): no card under the finger; the recent apps rise in over
     * the picture of home, which recedes, blurs and dims behind them. Home stays the window in front until an app is chosen.
     */
    private fun enterSwitcherFromHome(list: List<Task>) {
        val d = deck ?: return
        gen++
        phase = Phase.SWITCHER
        updateWatch()
        switcherHeld = true
        switcherHomeAsked = false
        nav.removeCallbacks(holdCheck)
        strip?.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else android.view.HapticFeedbackConstants.CONTEXT_CLICK)
        cardPkg = null
        fg = null
        dragStartedAt = SystemClock.uptimeMillis()
        picturesAsked = 0; picturesFull = 0; picturesGot = 0
        val cards = list.map { newCard(it) }
        cur?.alpha = 0f   // no app card in this session
        prv?.visibility = View.GONE
        nxt?.visibility = View.GONE
        // Home keeps receding from where the swipe had it.
        pullBack.stop()
        pulling = false
        if (backdrop?.picture == null) { backdrop?.picture = pullPicture ?: HomeBridge.preview; backdrop?.depth = 0f }
        switcherGround = if (backdrop?.picture == null) 0xFF101418.toInt() else 0
        depthAtSwitcher = backdrop?.depth ?: 0f
        d.setScreen(sw, sh, deviceRadius)
        d.enterFromHome(cards)
        d.heldStart(lastDragX)
        showCards()
        switcherBg.snapTo(0f)
        switcherBg.animateTo(1f, Motion.profile.switcher.enter, 0f)
        maybeAskSwitcherWindow()
        stats.reset(); stats.start()
        AppLog.log("[switcher] open from home: ${cards.size} apps${listNote()} (${cards.take(8).joinToString { it.pkg.substringAfterLast('.') }}); ${pictureAges(cards)}")
    }

    /**
     * A card for task [t], with the picture we keep of its app: counted as up to date only if fetched after the app last
     * left the front (else the deck asks for the system's, showing ours meanwhile).
     */
    private fun newCard(t: Task) =
        dev.launcher.app.switcher.DeckView.Card(t.id, t.pkg, labelFor(t.pkg), iconFor(t.pkg), SplashColors.cached(t.pkg) ?: DEFAULT_SPLASH).apply {
            snapshot = images[t.pkg]
            have = if (snapshot != null && (systemPictureAt[t.pkg] ?: 0L) > (leftFrontAt[t.pkg] ?: Long.MAX_VALUE)) 2 else 0
        }

    /** The full list arrived while the switcher is open: its older apps join the deck (on the left, mostly off screen). */
    private fun addOlderCards() {
        val d = deck ?: return
        if (d.count == 0) return
        val more = recentAll.switchable().filter { !d.has(it.id) }.take((SWITCHER_MAX_CARDS - d.count).coerceAtLeast(0))
        if (more.isEmpty()) return
        d.append(more.map { newCard(it) })
        AppLog.log("[switcher] ${more.size} older apps added (${d.count} in all; full list in $recentAllMs ms)")
    }

    private fun listNote() = if (recentAllMs >= 0) " of ${recentAll.switchable().size} recent (full list in $recentAllMs ms)" else ""

    /** For the log: how old the pictures of the first two cards are, and whether their app changed its screen since. */
    private fun pictureAges(cards: List<dev.launcher.app.switcher.DeckView.Card>): String {
        val now = SystemClock.uptimeMillis()
        return cards.take(2).joinToString { c ->
            val at = imagesAt[c.pkg]
            val age = if (c.snapshot == null || at == null) "no picture yet" else "${"%.1f".format((now - at) / 1000.0)} s old"
            "${c.pkg.substringAfterLast('.')} $age${if (keptIsStale(c.pkg)) ", OUT OF DATE" else ""}"
        }
    }

    private fun labelFor(pkg: String): String =
        dev.launcher.app.apps.Apps.all.firstOrNull { it.pkg == pkg }?.label
            ?: try { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { "" }

    /** The bar while the switcher is open: the opening swipe still going on, or a new swipe up (home). */
    private fun onTouchInSwitcher(e: MotionEvent): Boolean {
        val d = deck ?: return true
        val raw = MotionEvent.obtain(e).apply { setLocation(e.rawX, e.rawY) }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                switcherHeld = false
                vt?.recycle(); vt = VelocityTracker.obtain().also { it.addMovement(raw) }
                switcherDownY = e.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(raw)
                if (switcherHeld) d.heldMove(e.rawX)
                else if (switcherDownY - e.rawY > dp(10) && d.interactive) d.goHome()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(raw)
                vt?.computeCurrentVelocity(1000)
                fingerDown = false
                if (switcherHeld) {
                    switcherHeld = false
                    val vy = vt?.yVelocity ?: 0f
                    // A flick up after the hold goes home (iOS); otherwise the deck stays, focused on the previous app.
                    if (e.actionMasked == MotionEvent.ACTION_UP && -vy > SWITCHER_HOME_FLICK_DP * density) d.goHome()
                    else { d.heldRelease(vt?.xVelocity ?: 0f); if (win != Win.HOLDS) input?.show() }
                }
            }
        }
        raw.recycle()
        return true
    }

    private var barTouch = false   // a touch on the open deck that started on the bar (the input window covers it)

    /** Touches on the open deck (the input window, which lies over the bar too: a swipe up from the bottom edge is home). */
    private fun onSwitcherTouch(e: MotionEvent) {
        val d = deck ?: return
        if (phase != Phase.SWITCHER || switcherHeld) return
        if (handOverWhenFree && (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)) nav.post(handOverIfFree)
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!d.interactive) return
            barTouch = e.rawY > sh - (strip?.height ?: dp(24).toInt())
            switcherDownY = e.rawY
            stats.reset(); stats.start()
        }
        if (barTouch) {
            if (e.actionMasked == MotionEvent.ACTION_MOVE && switcherDownY - e.rawY > dp(10) && d.interactive) d.goHome()
            return
        }
        d.onTouch(e)
    }

    // ---- the App Switcher's own window (switcher/SwitcherActivity, SwitcherHost): the deck there while a mini player is up

    /**
     * What that window shows: home behind the deck ([back2], following [backdrop]), the dim over it ([dim2], following the
     * card window root's background), the deck while it is there. The dim is a view of its own above the picture, as in the
     * card window (there the root, above the picture): as this container's background it lay under the picture, unseen, and
     * the switcher's background went bright at the handover (S24 recording).
     */
    private var stage2: FrameLayout? = null
    private var back2: PreviewView? = null
    private var dim2: View? = null
    /** The next session's window content is drawn ahead ([updateSwitcherWarmth]), with this picture of home in its layers. */
    private var winWarm = false
    private var warmPic: HomePicture? = null
    /** NONE: no window. ASKED: started, the deck still in the card window. HOLDS: the deck is in it. BACK: the deck left it. */
    private enum class Win { NONE, ASKED, HOLDS, BACK }
    private var win = Win.NONE
    private var winSession = 0
    private var deckIndex = -1
    private var windowTouchLogs = 0
    /** The window showed while a finger was on the deck: the deck moves there when it lifts. */
    private var handOverWhenFree = false
    private val handOverIfFree = Runnable {
        if (handOverWhenFree && win == Win.ASKED && phase == Phase.SWITCHER && deck?.interactive == true && deck?.fingerOn != true) {
            handOverWhenFree = false
            handOverToWindow()
        }
    }
    private val deckInWindow get() = deck?.parent != null && deck?.parent === stage2

    /** The window's content: made once with the card window, laid out at its size (again after a size change). */
    private fun setUpSwitcherWindow(ctx: Context) {
        if (Build.VERSION.SDK_INT < 34) return
        val st = stage2 ?: FrameLayout(ctx).also { s ->
            val b2 = PreviewView(ctx).apply { lazySharp = true }
            s.addView(b2, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val dm = View(ctx)
            s.addView(dm, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            // Hidden between sessions: a spare host whose content draws nothing holds no screen-sized buffers.
            s.visibility = View.INVISIBLE
            back2 = b2
            dim2 = dm
            stage2 = s
        }
        dev.launcher.app.switcher.SwitcherHost.listener = switcherWindowListener
        val dsp = (app.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return
        dev.launcher.app.switcher.SwitcherHost.setUp(ctx, dsp, st, sw.toInt(), sh.toInt())
    }

    /**
     * While a mini player is up and nothing moves, the next session's window content is laid out and drawn once ahead, with
     * the picture of home the next gesture will show (the one the card window's backdrop is kept ready with): done when the
     * switcher asked for it, its relayout cost gesture nav's thread 4 ms and its first frame (buffers, the blurred picture)
     * the render thread 11 ms inside the switcher's opening (a dropped frame, S24 trace). Let go when no mini player is left
     * (it holds a few screen-sized buffers). Called with every look at the floating windows and once a spare host is made.
     */
    private fun updateSwitcherWarmth() {
        if (Build.VERSION.SDK_INT < 34 || win != Win.NONE) return
        val st = stage2 ?: return
        val b2 = back2 ?: return
        val want = floatingModes.any { it == WINDOWING_MODE_PINNED } && dev.launcher.app.switcher.SwitcherHost.ready &&
            NoAnimStarts.usable
        if (!want) {
            if (winWarm) { st.visibility = View.INVISIBLE; winWarm = false; warmPic = null }
            return
        }
        if (phase != Phase.IDLE || fingerDown) return   // the next look, once nothing moves
        if (!winWarm) {
            st.visibility = View.VISIBLE
            b2.alpha = 1f
            b2.depth = 1f
            dim2?.setBackgroundColor(0)
            winWarm = true
            AppLog.log("[switcher] a mini player is up: its window's content is drawn ahead")
        }
        val pic = if (homeVisible) HomeBridge.preview else HomeBridge.previewFor(lastFrontPkg)
        if (pic != null && pic !== warmPic) { b2.prewarm(pic); warmPic = pic }
    }

    /**
     * A mini player is on screen: the switcher asks for its own window, under the mini player (see SwitcherHost), and the deck
     * moves there as soon as it shows (any moment: the move is one frame of both windows). Until then, and on Android 13 and
     * older, the deck stays in the card window, the mini player showing through a hole.
     */
    private fun maybeAskSwitcherWindow() {
        if (win != Win.NONE || !NoAnimStarts.usable) return
        if (floatingModes.none { it == WINDOWING_MODE_PINNED }) return
        // No host made yet (the switcher opened again soon after its last session): made once the deck rests, and asked then.
        if (!dev.launcher.app.switcher.SwitcherHost.ready) {
            AppLog.log("[switcher] a mini player is up but its window's host is not made yet: the deck moves there once it rests")
            nav.removeCallbacks(spareLater)
            nav.postDelayed(spareLater, SPARE_SWITCHER_POLL_MS)
            return
        }
        val st = stage2 ?: return
        val b2 = back2 ?: return
        val s = gen
        // What it shows is the card window's backdrop and dim (brought up to date again when the deck moves in). Drawn ahead
        // while the mini player was up (updateSwitcherWarmth): the same picture is already in its layers.
        st.visibility = View.VISIBLE
        orientBackdrop(b2)   // turned as the card window's is, over an app in landscape
        b2.alpha = 1f
        b2.picture = backdrop?.picture
        backdrop?.let { b2.followDolly(it) }   // (a dolly zoom's close turned into the App Switcher)
        b2.depth = backdrop?.depth ?: 1f
        dim2?.setBackgroundColor((root?.background as? android.graphics.drawable.ColorDrawable)?.color ?: 0)
        if (!dev.launcher.app.switcher.SwitcherHost.begin(s)) return
        if (!winWarm) AppLog.log("[switcher] its window was not drawn ahead")
        winWarm = false
        warmPic = null
        win = Win.ASKED
        winSession = s
        val i = dev.launcher.app.switcher.SwitcherHost.intent(app, s)
        frontIo.execute {
            if (!NoAnimStarts.start(i, Process.myUserHandle().hashCode())) nav.post {
                if (winSession == s && win == Win.ASKED) { AppLog.log("[switcher] its window could not be started"); endSwitcherWindow() }
            }
        }
        AppLog.log("[switcher] a mini player is up: the deck moves to the switcher's own window once it shows")
    }

    private val switcherWindowListener = object : dev.launcher.app.switcher.SwitcherHost.Listener {
        override fun onWindowShown(s: Int) {
            if (s != winSession || win != Win.ASKED) return
            // Leaving already (an app chosen, home): the deck finishes where it is.
            if (phase != Phase.SWITCHER || deck?.interactive != true) return
            // A finger on the deck (through the card window's input window): once it lifts (moved mid-touch, the touch
            // was cancelled under the finger).
            if (deck?.fingerOn == true) { handOverWhenFree = true; return }
            handOverToWindow()
        }

        override fun onWindowLost(s: Int) {
            if (s != winSession) return
            when (win) {
                // Something came over it (the mini player's app expanded, a launch from the shade, the lock screen...): the
                // switcher is gone with it, nothing animates (it is covered).
                Win.HOLDS -> { AppLog.log("[switcher] something came over its window: the switcher closes"); hideCards() }
                // It never held the deck: the switcher goes on in the card window.
                Win.ASKED -> endSwitcherWindow()
                else -> {}
            }
        }

        override fun onWindowBack(s: Int) {
            if (s == winSession && win == Win.HOLDS) deck?.back()
        }

        override fun onWindowTouch(e: MotionEvent) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN && windowTouchLogs++ < 3) AppLog.log("[switcher] its window: touch at ${e.x.toInt()},${e.y.toInt()} (raw ${e.rawX.toInt()},${e.rawY.toInt()})")
            if (win == Win.HOLDS && phase == Phase.SWITCHER) onSwitcherTouch(e)
            e.recycle()
        }
    }

    /**
     * The deck moves into the switcher's own window, in the same frame as the card window goes clear (one transaction of both
     * windows' frames): nothing moves on screen but the mini player's shadow, which shows from then on.
     */
    private fun handOverToWindow() {
        if (Build.VERSION.SDK_INT < 34) return
        val d = deck ?: return
        val r = root ?: return
        val st = stage2 ?: return
        val cardRoot = cardWindow?.rootSurfaceControl ?: return
        val winRoot = st.rootSurfaceControl ?: return
        val sync = android.window.SurfaceSyncGroup("switcher-in")
        sync.add(cardRoot, null)
        sync.add(winRoot) {
            backdrop?.let { back2?.followDolly(it) }
            back2?.depth = backdrop?.depth ?: 1f
            dim2?.setBackgroundColor((r.background as? android.graphics.drawable.ColorDrawable)?.color ?: 0)
            deckIndex = r.indexOfChild(d)
            r.removeView(d)
            st.addView(d, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            r.alpha = 0f   // the card window's backdrop and dim go with it (Stage mirrors its fade onto the backdrop)
            win = Win.HOLDS
            applyFloating()   // no holes in the card window now
        }
        sync.markSyncReady()
        input?.hide()
        AppLog.log("[switcher] the deck is in its own window, under the mini player")
    }

    /**
     * The switcher is leaving (an app opens, home comes): the card window takes the deck back in the same frame (one
     * transaction of both windows' frames), with its backdrop, dim and the mini player's hole, and the leaving runs there
     * as always; the switcher's window is left showing nothing (it goes with the session, and may linger a frame over what
     * comes next: clear, it is never seen).
     */
    private fun leaveSwitcherWindow() {
        when (win) {
            Win.HOLDS -> deckBackToCardWindow(shown = true)
            Win.ASKED -> { clearSwitcherWindow(); win = Win.BACK }
            else -> {}
        }
    }

    private fun clearSwitcherWindow() {
        back2?.alpha = 0f
        dim2?.setBackgroundColor(0)
    }

    private fun deckBackToCardWindow(shown: Boolean) {
        val d = deck ?: return
        val r = root ?: return
        val st = stage2 ?: return
        val move = {
            if (d.parent === st) {
                st.removeView(d)
                r.addView(d, deckIndex.coerceIn(0, r.childCount),
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
            if (shown) r.alpha = 1f
            clearSwitcherWindow()
            win = Win.BACK
            applyFloating()   // the mini player's hole, where it is now (the card window covers it again)
        }
        val cardRoot = cardWindow?.rootSurfaceControl
        val winRoot = st.rootSurfaceControl
        if (shown && Build.VERSION.SDK_INT >= 34 && cardRoot != null && winRoot != null) {
            val sync = android.window.SurfaceSyncGroup("switcher-out")
            sync.add(cardRoot, null)
            sync.add(winRoot) { move() }
            sync.markSyncReady()
            AppLog.log("[switcher] the deck is back in the card window")
        } else move()
    }

    /** The session's window goes (the deck back in the card window first); a new spare host is made once nothing moves. */
    private fun endSwitcherWindow() {
        val s = winSession
        if (win == Win.HOLDS || deckInWindow) deckBackToCardWindow(shown = false)
        win = Win.NONE
        winSession = 0
        handOverWhenFree = false
        back2?.picture = null
        stage2?.visibility = View.INVISIBLE
        winWarm = false
        warmPic = null
        dev.launcher.app.switcher.SwitcherHost.end(s)
        nav.post { refreshAppearance() }
        nav.removeCallbacks(spareLater)
        nav.postDelayed(spareLater, SPARE_AFTER_MS)
    }

    /**
     * Makes the next session's host once nothing moves. Also while the App Switcher is open without one (it opened again
     * within a second of the last session, before its host was made): once the deck rests, then the deck moves under the
     * mini player mid-session. That session had kept the deck in the card window throughout, and a mini player dragged fast
     * left the hole chasing it: home showed through (Matheesha, S24 log 12:29:43).
     */
    private val spareLater: Runnable = object : Runnable {
        private var tries = 0
        override fun run() {
            nav.removeCallbacks(this)
            val d = deck
            val resting = phase == Phase.SWITCHER && win == Win.NONE && d != null && d.interactive && !d.moving && !d.fingerOn
            if (!resting && (phase != Phase.IDLE || fingerDown)) {
                nav.postDelayed(this, if (phase == Phase.SWITCHER && win == Win.NONE) SPARE_SWITCHER_POLL_MS else SPARE_AFTER_MS)
                return
            }
            dev.launcher.app.switcher.SwitcherHost.makeSpare()
            if (!dev.launcher.app.switcher.SwitcherHost.ready) {
                // The last session's host still holds what the window shows (it is released a moment after it ended).
                if (tries++ < SPARE_TRIES) nav.postDelayed(this, SPARE_SWITCHER_POLL_MS)
                else { tries = 0; AppLog.log("[switcher] its window's host could not be made") }
                return
            }
            tries = 0
            if (resting) maybeAskSwitcherWindow() else updateSwitcherWarmth()
        }
    }

    private val pendingRemovals = ArrayList<dev.launcher.app.switcher.DeckView.Card>()

    /** Closes the apps flicked away (see deckListener.onRemove); at once when the switcher goes away. */
    private val removeNow: Runnable = object : Runnable {
        override fun run() {
            if (pendingRemovals.isEmpty()) return
            if (deck?.moving == true && phase == Phase.SWITCHER) { nav.postDelayed(this, 150); return }
            val list = ArrayList(pendingRemovals)
            pendingRemovals.clear()
            val s = ShizukuLink.service ?: return
            tasksIo.execute {
                for (card in list) {
                    val r = try { s.removeTask(card.taskId) } catch (t: Throwable) { "ERROR: ${t.message}" }
                    AppLog.log("[switcher] closed ${card.pkg} (task ${card.taskId}): $r")
                }
            }
        }
    }

    /** Apps kept open from the App Switcher's menu: Clear All leaves them (persisted). */
    private val keepOpen: MutableSet<String> by lazy { HashSet(app.getSharedPreferences(SWITCHER_PREFS, 0).getStringSet(KEY_KEEP, emptySet()) ?: emptySet()) }

    /** The package showing an app's details (App Info): what an App Info opening waits for in front. */
    private fun detailsPkg(pkg: String): String = try {
        detailsIntent(pkg).resolveActivity(app.packageManager)?.packageName
    } catch (_: Throwable) { null } ?: "com.android.settings"

    private fun detailsIntent(pkg: String) = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", pkg, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private val deckListener = object : dev.launcher.app.switcher.DeckView.Listener {
        override fun onOpenStart(card: dev.launcher.app.switcher.DeckView.Card) {
            leaveSwitcherWindow()
            // The input window stays until the cards are gone (hideCards): removing it now made the window manager and the
            // compositor change windows in the middle of the animation.
            openedAt = SystemClock.uptimeMillis()
            if (card.details) {
                // App Info (the app's menu): its page starts through our own transition while the card grows into its launch
                // screen (DeckView.drawDetailsCard); the app itself stays where it is.
                val target = detailsPkg(card.pkg)
                cardPkg = target
                val i = detailsIntent(card.pkg)
                frontIo.execute {
                    if (!NoAnimStarts.start(i, android.os.Process.myUserHandle().hashCode())) main.post {
                        try { app.startActivity(i, noAnimation(app)) } catch (t: Throwable) { AppLog.log("[switcher] app info failed: ${t.message}") }
                    }
                }
                stats.reset(); stats.start()
                AppLog.log("[switcher] app info of ${card.pkg} ($target)")
                return
            }
            // The app the swipe started in is still in front underneath unless home was asked for or it was closed.
            val stillInFront = !switcherOverHome && !homeVisible && card.taskId == recentList.switchable().firstOrNull()?.id &&
                !homeStarted && lastFrontPkg == card.pkg
            cardPkg = card.pkg
            if (!stillInFront) bringBack(card.pkg, Task(card.taskId, card.pkg))
            stats.reset(); stats.start()
            AppLog.log("[switcher] open ${card.pkg}${if (stillInFront) " (still in front)" else ""}")
        }

        override fun onOpened(card: dev.launcher.app.switcher.DeckView.Card) {
            AppLog.log(stats.report("[switcher] ${card.pkg} fills the screen"))
            val g = gen
            val pkg = if (card.details) detailsPkg(card.pkg) else card.pkg
            if (lastFrontPkg == pkg) hideCards() else awaitForeground(pkg, g) { hideCards() }
        }

        override fun drawBehind(c: Canvas) {
            // The copy in the window the deck is in (a view of the other window's tree drawn here would share its nodes).
            (if (deckInWindow) back2 else backdrop)?.draw(c)
            val bg = (root?.background as? android.graphics.drawable.ColorDrawable)?.color ?: 0
            if (bg != 0) c.drawColor(bg)
        }

        override fun behindKey(): Long {
            val bg = (root?.background as? android.graphics.drawable.ColorDrawable)?.color ?: 0
            return ((System.identityHashCode(backdrop?.picture).toLong() * 31 + ((backdrop?.depth ?: 0f) * 1000f).toLong()) * 31 + bg) * 2 +
                (if (deckInWindow) 1 else 0)
        }

        override fun haptic(constant: Int) {
            strip?.performHapticFeedback(constant)
        }

        override fun isKept(pkg: String): Boolean = pkg in keepOpen

        override fun onKeep(card: dev.launcher.app.switcher.DeckView.Card, keep: Boolean) {
            if (keep) keepOpen += card.pkg else keepOpen -= card.pkg
            app.getSharedPreferences(SWITCHER_PREFS, 0).edit().putStringSet(KEY_KEEP, HashSet(keepOpen)).apply()
            AppLog.log("[switcher] ${card.pkg} ${if (keep) "kept open" else "no longer kept open"}")
        }

        override fun detailsLook(): Pair<Int, Drawable?> {
            val pkg = detailsPkg("android")
            val icon = try { app.packageManager.getApplicationIcon(pkg) } catch (_: Throwable) { null }
            val colour = SplashColors.cached(pkg) ?: run {
                SplashColors.resolve(app, pkg) { }
                if (dev.launcher.app.theme.Appearance.dark > 0.5f) 0xFF0E0E10.toInt() else 0xFFF4F4F6.toInt()
            }
            return colour to icon
        }

        override fun onHomeStart() {
            leaveSwitcherWindow()
            if (switcherHomeAsked) return
            switcherHomeAsked = true
            // Home with every icon now that it will sharpen (only the small blurred copy shows yet: cheap to re-render).
            HomeBridge.preview?.let { backdrop?.picture = it }
            depthAtSwitcher = 0f
            if (!homeVisible) {
                startHome()
                // The real home runs the same depth spring underneath, so it matches the picture when that goes.
                val spec = Motion.profile.switcher.home
                HomeBridge.animateDepth(backdrop?.depth ?: 1f, 0f, 0f, spec, System.nanoTime())
            }   // (opened from home: home is in front at rest already; the picture of it just comes forward again)
            stats.reset(); stats.start()
            AppLog.log("[switcher] home")
        }

        override fun onHomeProgress(k: Float) {
            backdrop?.depth = 1f - k
            if (switcherGround == 0) root?.setBackgroundColor(((SWITCHER_DIM * (1f - k) * 255).toInt().coerceIn(0, 255)) shl 24)
        }

        override fun onHomeDone() {
            AppLog.log(stats.report("[switcher] home reached"))
            val g = gen
            whenHomeDrawn(g) { HomeBridge.afterHomeDraw { nav.post { if (gen == g) hideCards() } } }
        }

        override fun onRemove(card: dev.launcher.app.switcher.DeckView.Card) {
            recentList = recentList.filter { it.id != card.taskId }
            recentAll = recentAll.filter { it.id != card.taskId }
            images.remove(card.pkg)
            // The app is closed once the deck is still (the card has flown off, the gap has closed): closing it at the flick
            // held the screen for ~110 ms in the middle of the card's flight on the S24 (our frame on time, shown late).
            pendingRemovals.add(card)
            nav.removeCallbacks(removeNow)
            nav.postDelayed(removeNow, REMOVE_AFTER_MS)
        }

        override fun onSettled(what: String) {
            if (phase != Phase.SWITCHER) return
            AppLog.log(stats.report("[switcher] $what") + "; pictures asked $picturesAsked ($picturesFull full), got $picturesGot")
        }

        override fun onWantPicture(card: dev.launcher.app.switcher.DeckView.Card, full: Boolean) {
            val s = ShizukuLink.service ?: return   // without the service, launch screens stand in
            val g = gen
            val level = if (full) 2 else 1
            picturesAsked++
            if (full) picturesFull++
            deckIo.execute {
                val wanted = gen == g && card.want >= level
                val b = if (!wanted) null else if (full) snapshot(s, card.taskId, false) else snapshotLow(s, card.taskId)
                nav.post {
                    if (gen != g) return@post
                    if (b != null) picturesGot++
                    if (full && b != null) { remember(card.pkg, b); systemPictureAt[card.pkg] = SystemClock.uptimeMillis() }
                    deck?.setPicture(card, b, full, fetched = wanted)
                }
            }
        }
    }

    private const val HOLD_RADIUS_DP = 12f
    /** WindowConfiguration's picture-in-picture windowing mode (a hidden constant). */
    private const val WINDOWING_MODE_PINNED = 2
    /** A cover counts as on screen this long after its frame was committed (ms: a refresh at 60 Hz). */
    private const val COVER_SHOWN_MS = 17L
    /** A cover not on screen this long after it was asked for is given up on: home comes at once (ms). */
    private const val COVER_SHOW_WAIT_MS = 350L
    /** Home's picture over one of our screens stays this long at most (ms). */
    private const val COVER_MAX_MS = 1500L
    /** How long the cover waits for home to draw once it was asked to come (ms). */
    private const val COVER_HOME_WAIT_MS = 700L
    /** The launcher's settings (the one task of ours that is an app in the App Switcher). */
    private val SETTINGS_CLASS = dev.launcher.app.settings.SettingsActivity::class.java.name
    /** How long after a switcher session the next spare host for its window is made (ms; once nothing moves). */
    private const val SPARE_AFTER_MS = 800L
    /** While the App Switcher is open without its window's host: how often it looks for the deck at rest (ms). */
    private const val SPARE_SWITCHER_POLL_MS = 100L
    /** How many times it waits for the last session's host to let go before giving up (each [SPARE_SWITCHER_POLL_MS]). */
    private const val SPARE_TRIES = 20
    /** How far a finger from the top edge moves down before the hidden status bar shows for a moment (dp). */
    private const val EDGE_TRAVEL_DP = 20f
    /**
     * How long a close over a turned display waits, once its card has landed, for the cover to be on screen (ms): the first
     * start after an install took 2 s on the emulator; home started without it showed the app until home came.
     */
    private const val COVER_TIMEOUT_MS = 1200L
    /** How long after the cover's first frame was drawn it counts as shown (ms): about a refresh at 60 Hz. */
    private const val COVER_FRAME_MS = 17L
    /**
     * How many refreshes after its vsync a frame of ours shows, counted against a window SurfaceFlinger moved as it reported
     * it (the S24, a mini player flung in the App Switcher and measured against the recording: 3.3).
     */
    private const val LIVE_PIPE_FRAMES = 3.3f
    /** A window's speed is taken over its reports of this long (ms): about four refreshes at 120 Hz. */
    private const val LIVE_TRAIL_MS = 34L
    /** The furthest ahead a hole is ever drawn (ms of the window's motion). */
    private const val LIVE_AHEAD_MAX_MS = 120f
    /** The gaps between reports that count towards the usual one (ms; longer ones are pauses). */
    private const val LIVE_EVERY_MAX_MS = 60L
    /** A report after this long without one (ms) is a window setting off from rest (SystemUI skips up to ~30 ms mid-motion). */
    private const val LIVE_SETOFF_MS = 50L
    /** No report for this many refreshes (or two and a half of the usual gaps): the window has stopped, its lead runs out. */
    private const val LIVE_QUIET_FRAMES = 3f
    /** How fast a stopped window's lead runs out (ms, time constant). */
    private const val LIVE_STOP_MS = 12f
    /** Below this speed (px/ms) a window is still. */
    private const val LIVE_STILL_PX_MS = 0.02f
    /** How far a moving window's hole is drawn inside it: its motion over this many refreshes, at most [LIVE_INSET_MAX_DP]. */
    private const val LIVE_INSET_FRAMES = 1.25f
    private const val LIVE_INSET_MAX_DP = 8f
    /** How quickly the inset goes once the window slows (ms, time constant). */
    private const val LIVE_INSET_EASE_MS = 60f
    /** A floating window pressed in the App Switcher: its hole this far inside it (dp), eased in (ms, time constant) ... */
    private const val LIVE_PRESS_INSET_DP = 8f
    private const val LIVE_PRESS_IN_MS = 30f
    /** ... until it has moved and stopped, or for this long if it never moves (ms). */
    private const val LIVE_PRESS_MS = 250L
    /** How long the mini player's live frame stays still before it counts as settled (ms). */
    private const val PIP_STILL_MS = 60L
    /** The longest a close waits for the mini player to settle (ms): Android's entry ran ~450-490 ms on the S24 (YouTube). */
    private const val PIP_SETTLE_MAX_MS = 1000L
    /** How long a close into home, started as its card landed, waits for the display to turn upright (ms). */
    private const val HOME_SHOWN_TIMEOUT_MS = 3000L
    /** How long the hidden status bar shows after a swipe from the top (ms; about as long as Android shows its bars). */
    private const val TRANSIENT_BAR_MS = 2500L
    private const val HOME_PULL_DEPTH = 0.6f   // how far home recedes at most while a swipe up on it goes on
    private const val SWITCHER_DIM = 0.28f              // home behind the deck: darkened by this much
    private const val SWITCHER_PREFS = "switcher"
    private const val KEY_KEEP = "keep"
    private const val SWITCHER_MAX_CARDS = 50   // every recent app (the system keeps about this many)
    // A window's touchable region can change without a re-layout from Android 14 (AttachedSurfaceControl).
    private val CAN_CATCH_TOUCHES = Build.VERSION.SDK_INT >= 34
    // "Nowhere": one pixel just off screen. An empty region is never sent to the window manager (it equals the initial
    // "previous" region), which leaves the whole window touchable.
    private val NO_TOUCH = android.graphics.Region(-2, -2, -1, -1)
    private const val REMOVE_AFTER_MS = 650L    // a flicked card's app is closed once the deck is still
    private const val FLOATING_POLL_MS = 2500L  // floating windows (PiP, pop-ups) are looked up this often while idle
    private const val FLOATING_BUSY_MS = 300L   // ... and this often while cards show (a PiP window can be dragged)
    /** How long after the floating windows last changed the App Switcher's input window leaves them out where they are (ms). */
    private const val INPUT_HOLES_MS = 80L
    private const val SWITCHER_PREFETCH = 3     // pictures fetched at the touch (the rest as the deck shows their cards)
    private const val IMAGES_MAX = 16          // pictures kept between gestures
    private const val SWITCHER_HOME_FLICK_DP = 900f     // a flick up faster than this after the hold goes home

    // ================================================================== views

    /**
     * Our recorded picture of the home screen, drawn behind cards. [depth] 0 = at rest, 1 = receded behind an open app:
     * the content zooms by MotionProfile.homeContentZoom and the wallpaper by the smaller homeWallpaperZoom, about the
     * centre (iOS depth), and the whole picture blurs by up to MotionProfile.homeDepthBlur.
     */
    private class PreviewView(ctx: Context) : FrameLayout(ctx) {
        // Each layer is rendered once into a GPU layer when its picture changes; the depth zoom only scales the layers
        // (a transform, no re-render), so a closing or opening card costs almost nothing per frame even while a heavy app
        // starts underneath. The GPU layers are kept for good, and so is the last picture while no card shows: a trace on
        // the S24 showed every gesture re-allocating both full-screen layers and re-rendering home into them (~25 ms of the
        // first frame), though home had usually not changed.
        private class Layer(ctx: Context, cached: Boolean = true, private val scale: () -> Float = { 1f }) : View(ctx) {
            init { if (cached) setLayerType(LAYER_TYPE_HARDWARE, null) }

            var pic: Picture? = null
                set(v) {
                    if (field === v) return
                    field = v
                    stale = false
                    invalidate()
                }

            // Inside [patchArea], another picture is drawn instead of [pic] (see [PreviewView.setPatch]).
            private var patch: Picture? = null
            private var patchArea: android.graphics.Path? = null
            // The patch changed while the layer did not show: rendered again only once it shows ([freshen]). A hidden GPU
            // layer is still rendered when it changes (a whole screen of home, for nothing).
            private var stale = false

            fun setPatch(p: Picture?, area: android.graphics.Path?, showing: Boolean) {
                if (patch === p && patchArea === area) return
                patch = p
                patchArea = area
                if (showing) { stale = false; invalidate() } else stale = true
            }

            fun freshen() { if (stale) { stale = false; invalidate() } }

            override fun onDraw(canvas: Canvas) {
                val p = pic ?: return
                val s = scale()
                if (s != 1f) canvas.scale(s, s)
                val pp = patch
                val area = patchArea
                if (pp == null || area == null) { canvas.drawPicture(p); return }
                // Whole pixels both ways (the area is on the quarter-size grid): no seam where the two pictures meet.
                canvas.save(); canvas.clipOutPath(area); canvas.drawPicture(p); canvas.restore()
                canvas.save(); canvas.clipPath(area); canvas.drawPicture(pp); canvas.restore()
            }
        }

        // The wallpaper's picture is one bitmap (and dark mode's dim): drawn as it is each frame, it costs what drawing a layer
        // of it costs, and the bitmap is the texture home already holds. Its own screen-sized layer took 10 MB of the GPU
        // cache's budget (S24: 121 MB, all but full), which every gesture's new layers and blurs then had to free first.
        private val wallpaperLayer = Layer(ctx, cached = false)
        private val contentLayer = Layer(ctx)

        // The blur is done on a quarter-size copy of the picture and scaled up: blurring the full-size composite every frame
        // (the radius follows the depth) cost the GPU ~4-8 ms per frame at 120 Hz on the S24, plus a full-size offscreen
        // allocated at each gesture's first frame. A blur looks the same from a quarter-size image, at 1/16 of the pixels.
        // [low] keeps the blurred result in its own (small) layer, so the blur is evaluated at that size and not at the
        // scaled-up size; it fades over the sharp layers for the first few pixels of blur, where the low resolution would show.
        private var lowScale = dev.launcher.app.home.DepthBlur.LOW_SCALE
        private val lowWallpaper = Layer(ctx) { lowScale }
        private val lowContent = Layer(ctx) { lowScale }
        private val lowBlur = FrameLayout(ctx)
        private val low = FrameLayout(ctx).apply { setLayerType(LAYER_TYPE_HARDWARE, null); pivotX = 0f; pivotY = 0f; alpha = 0f }
        private var shown = false

        init {
            addView(wallpaperLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(contentLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            lowBlur.addView(lowWallpaper, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            lowBlur.addView(lowContent, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            low.addView(lowBlur, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(low, LayoutParams(1, 1))
        }

        // The quarter-size copy is measured here, in the same pass as everything else (its size follows ours; set from
        // onSizeChanged instead, it stayed 1 x 1 because that runs during layout).
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val w = measuredWidth
            val h = measuredHeight
            if (w == 0 || h == 0) return
            val lw = (w + 3) / 4
            val lh = (h + 3) / 4
            low.measure(MeasureSpec.makeMeasureSpec(lw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(lh, MeasureSpec.EXACTLY))
            val s = lw.toFloat() / w
            if (s != lowScale) { lowScale = s; lowWallpaper.invalidate(); lowContent.invalidate() }
            low.scaleX = w.toFloat() / lw
            low.scaleY = h.toFloat() / lh
        }

        /**
         * While no card shows: renders [p] into the layers ahead of time (the next close will show it), so the gesture's
         * first frame does not have to.
         */
        fun prewarm(p: HomePicture?) {
            if (p == null || picture != null) return
            setPics(p, staggered = false)
        }

        // A new picture is rendered into the layers that show on this frame (decided when it is drawn) and into the others a
        // frame later: both at once made a launch's first frame render home four times (~10 ms on the render thread, traced
        // on the S24). A launch starts sharp and a close fully blurred, so only half of them show at first.
        private var pendingSharp: HomePicture? = null
        private var pendingLow: HomePicture? = null
        private val applyPending = Runnable { if (!lazySharp) applySharp(); applyLow() }

        /**
         * Shown blurred only (the App Switcher's own window): the sharp layers take a picture only once they show (drawn),
         * never ahead: two screen-sized layers rendered for nothing.
         */
        var lazySharp = false

        private fun applySharp() { pendingSharp?.let { wallpaperLayer.pic = it.wallpaper; contentLayer.pic = it.content }; pendingSharp = null }
        private fun applyLow() { pendingLow?.let { lowWallpaper.pic = it.wallpaper; lowContent.pic = it.content }; pendingLow = null }

        private fun setPics(p: HomePicture, staggered: Boolean) {
            removeCallbacks(applyPending)
            pendingSharp = p; pendingLow = p
            if (!staggered) { if (!lazySharp) applySharp(); applyLow(); return }
            postOnAnimation(applyPending)
            invalidate()
        }

        override fun dispatchDraw(canvas: Canvas) {
            if (pendingSharp != null && wallpaperLayer.alpha > 0f) applySharp()
            if (pendingLow != null && low.alpha > 0f) applyLow()
            super.dispatchDraw(canvas)
        }

        /**
         * Draws [src] (a picture of home's content) instead of the picture inside [rect] (home's own coordinates), except
         * inside [keep]: a closing card still landing in its icon while another app opens leaves that icon out (the
         * picture shown is the new app's, without that app's icon, which [keep] is). Null: none.
         */
        fun setPatch(src: Picture?, rect: RectF?, keep: RectF?) {
            val area = if (src == null || rect == null) null else android.graphics.Path().apply {
                // Out to whole pixels of the quarter-size copy, so both layers cut it on pixel edges.
                fun grid(r: RectF) = RectF(
                    Math.floor(r.left / 4.0).toFloat() * 4f, Math.floor(r.top / 4.0).toFloat() * 4f,
                    Math.ceil(r.right / 4.0).toFloat() * 4f, Math.ceil(r.bottom / 4.0).toFloat() * 4f
                )
                addRect(grid(rect), android.graphics.Path.Direction.CW)
                if (keep != null) op(android.graphics.Path().apply { addRect(grid(keep), android.graphics.Path.Direction.CW) }, android.graphics.Path.Op.DIFFERENCE)
            }
            val visible = shown && alpha > 0f
            contentLayer.setPatch(src, area, visible && contentLayer.alpha > 0f)
            lowContent.setPatch(src, area, visible && low.alpha > 0f)
        }

        // A patch set while a layer did not show is rendered once it does.
        private fun freshen() {
            if (!shown || alpha <= 0f) return
            if (contentLayer.alpha > 0f) contentLayer.freshen()
            if (low.alpha > 0f) lowContent.freshen()
        }

        override fun setAlpha(alpha: Float) {
            super.setAlpha(alpha)
            freshen()
        }

        /** What home looked like (null: nothing shows here). The layers keep the last picture while hidden. */
        var picture: HomePicture? = null
            set(v) {
                if (field === v) return
                field = v
                // Hidden by alpha, not visibility: an invisible view leaves the drawing tree and loses its GPU layer.
                shown = v != null
                if (v != null) setPics(v, staggered = true)
                applyDepth()
            }

        var depth = 0f
            set(v) {
                if (field == v) return
                field = v
                applyDepth()
            }

        /**
         * The dolly zoom (motion/AppStyles): with it on, depth flies home toward [x], [y] (the icon an app comes out of or
         * goes into) instead of stepping it back about the middle. Set only while home's picture shows nothing.
         */
        fun setDolly(on: Boolean, x: Float, y: Float) {
            if (picture != null && shown && alpha > 0f) return
            pivotOn(if (on) 1f else 0f, x, y)
        }

        /** How much of a dolly zoom is on (1: all, 0: none). */
        val dollyAmount get() = dolly

        /**
         * The dolly zoom giving way, as far as [k] of it is left, to the plain step back about the middle (the App Switcher
         * opening during a dolly zoom's close: home behind the deck as it always is). Its point stays: no jump.
         */
        fun fadeDolly(k: Float) {
            val a = k.coerceIn(0f, 1f)
            if (a == dolly) return
            pivotOn(a, dollyX, dollyY)
        }

        /** As [other]'s dolly zoom: the App Switcher's own window showing home as the card window did. */
        fun followDolly(other: PreviewView) {
            if (dolly == other.dolly && (dolly == 0f || (dollyX == other.dollyX && dollyY == other.dollyY))) return
            pivotOn(other.dolly, other.dollyX, other.dollyY)
        }

        private fun pivotOn(k: Float, x: Float, y: Float) {
            dolly = k
            dollyX = x
            dollyY = y
            if (k <= 0f) {
                for (l in arrayOf(wallpaperLayer, contentLayer, lowWallpaper, lowContent)) l.resetPivot()
            } else {
                // From the middle to the icon as much of it as is on.
                val px = width / 2f + (x - width / 2f) * k
                val py = height / 2f + (y - height / 2f) * k
                for (l in arrayOf(wallpaperLayer, contentLayer)) { l.pivotX = px; l.pivotY = py }
                for (l in arrayOf(lowWallpaper, lowContent)) { l.pivotX = px * lowScale; l.pivotY = py * lowScale }
            }
            applyDepth()
        }

        private var dolly = 0f
        private var dollyX = 0f
        private var dollyY = 0f

        private fun applyDepth() {
            val v = depth
            val mp = Motion.profile
            // Stepping back about the middle, or (dolly zoom, as much of it as is on) flying toward the icon, the wallpaper
            // less than the icons.
            val d = dolly
            val wFull = mp.homeWallpaperZoom + (1f + (AppStyles.DOLLY_ZOOM - 1f) * 0.6f - mp.homeWallpaperZoom) * d
            val cFull = mp.homeContentZoom + (AppStyles.DOLLY_ZOOM - mp.homeContentZoom) * d
            val wz = 1f + v * (wFull - 1f)
            val cz = 1f + v * (cFull - 1f)
            wallpaperLayer.scaleX = wz; wallpaperLayer.scaleY = wz
            contentLayer.scaleX = cz; contentLayer.scaleY = cz
            lowWallpaper.scaleX = wz; lowWallpaper.scaleY = wz
            lowContent.scaleX = cz; lowContent.scaleY = cz
            // iOS: home blurs as it recedes behind an opening app and sharpens as the app closes into it.
            val r = v.coerceIn(0f, 1f) * mp.homeDepthBlur * resources.displayMetrics.density
            val minBlur = MIN_LOW_BLUR_DP * resources.displayMetrics.density
            val mix = if (Build.VERSION.SDK_INT >= 31) (r / minBlur).coerceIn(0f, 1f) else 0f
            if (Build.VERSION.SDK_INT >= 31 && mix > 0f) {
                val lr = max(r, minBlur) * lowScale
                lowBlur.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(lr, lr, android.graphics.Shader.TileMode.CLAMP))
            }
            low.alpha = if (shown) mix else 0f
            // Under a fully opaque blurred copy the sharp layers are not drawn at all.
            val sharp = if (shown && mix < 1f) 1f else 0f
            wallpaperLayer.alpha = sharp
            contentLayer.alpha = sharp
            freshen()
        }

        private companion object {
            // Below this blur (dp, at full size) the quarter-size copy would look soft rather than blurred: up to it the
            // blurred copy (at this radius) fades in over the sharp picture. Shared with home's own blur, which matches
            // this picture's look (DepthBlur).
            const val MIN_LOW_BLUR_DP = dev.launcher.app.home.DepthBlur.MIN_LOW_BLUR_DP
        }
    }

    /** Frame pacing (nav thread Choreographer) and touch-to-frame latency for one gesture. */
    private class FrameStats {
        private val deltas = ArrayList<Double>()
        private val latencies = ArrayList<Double>()
        private var lastNs = 0L
        private var running = false
        private val choreographer = Choreographer.getInstance()
        private val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (lastNs != 0L) deltas += (frameTimeNanos - lastNs) / 1e6
                lastNs = frameTimeNanos
                val active = phase == Phase.DRAG_HOME || phase == Phase.DRAG_SWITCH || animating || grabbedFull ||
                    (phase == Phase.SWITCHER && (switcherHeld || deck?.moving == true)) || (phase == Phase.HOME_PULL && pulling)
                if (running && active) choreographer.postFrameCallback(this) else running = false
            }
        }

        fun reset() { deltas.clear(); latencies.clear(); lastNs = 0L }

        fun start() {
            lastNs = 0L
            if (!running) { running = true; choreographer.postFrameCallback(callback) }
        }

        fun touchEvent(eventTimeMs: Long) {
            choreographer.postFrameCallback { ns -> latencies += ns / 1e6 - eventTimeMs }
        }

        fun report(label: String): String {
            val d = if (deltas.size > 3) deltas.drop(1) else deltas
            if (d.isEmpty()) return "$label: too short to measure"
            val sorted = d.sorted()
            val median = sorted[sorted.size / 2]
            fun p(q: Double, l: List<Double>) = l[min(l.size - 1, (l.size * q).toInt())]
            val dropped = d.sumOf { max(0, Math.round(it / median).toInt() - 1) }
            val lat = latencies.sorted()
            val latLine = if (lat.isEmpty()) "n/a" else "median ${"%.1f".format(lat[lat.size / 2])} ms, p95 ${"%.1f".format(p(0.95, lat))} ms"
            return "$label\n" +
                "  frames ${d.size}: median ${"%.2f".format(median)} ms, p95 ${"%.2f".format(p(0.95, sorted))} ms, " +
                "worst ${"%.2f".format(sorted.last())} ms, dropped ~$dropped\n" +
                "  touch -> frame latency: $latLine"
        }
    }
}
