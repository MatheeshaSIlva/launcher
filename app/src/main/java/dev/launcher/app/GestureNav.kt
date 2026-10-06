package dev.launcher.app

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

    private class Task(val id: Int, val pkg: String)
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
    private var sw = 0f
    private var sh = 0f
    private var deviceRadius = 0f

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
                    if (keepFreshLogs < 3) { keepFreshLogs++; AppLog.log("[nav] kept a recent picture of $pkg (${SystemClock.uptimeMillis() - t0} ms, ${b?.config})") }
                    if (b != null) nav.post { if (lastFrontPkg == pkg) { remember(pkg, b, t0); if (phase == Phase.IDLE) warm?.bitmap = b } }
                }
            }
            nav.postDelayed(this, KEEP_FRESH_MS)
        }
    }

    // Springs
    private var sCx = Spring(0.5f, 0.86f)
    private var sCy = Spring(0.5f, 0.86f)
    private var sW = Spring(0.44f, 0.9f)
    private var sH = Spring(0.44f, 0.9f)
    private var sDepth = Spring(0.5f, 1f)   // home behind the card: 0 = at rest, 1 = receded (an app is open)
    private var sOff = Spring(0.35f, 1f)
    private var sScale = Spring(0.35f, 1f)
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
        // A picture of home without the card's icon arrives a moment after a gesture starts: use it as soon as it does.
        HomeBridge.onPreviewReady = { pkg ->
            nav.post { if (cardPkg == pkg && backdrop?.picture != null && !pictureDropped) backdrop?.picture = HomeBridge.previewFor(pkg) }
        }
        HomeBridge.onHomeTouched = { nav.post { homeTouchedDuringClose(); readyForLaunch() } }
        // Home settled into a new look: rendered into the layers now, while nothing moves, not at the next gesture's start.
        HomeBridge.onPreviewChanged = { p ->
            nav.post { if (phase == Phase.IDLE && root?.visibility != View.VISIBLE && homeVisible) backdrop?.prewarm(p) }
        }
        // The app in front is the one a close will fly into home: home keeps a picture without its icon ready.
        HomeBridge.likelyClosing = { if (homeVisible) null else lastFrontPkg }
    }

    // ================================================================== public entry points (any thread)

    /** Shows or removes our windows to match the current state. */
    fun update() {
        if (Looper.myLooper() != navThread.looper) { nav.post { update() }; return }
        val want = ready && SystemRestore.gestureFlagsActive && ShizukuLink.service != null && !locked
        if (want && strip == null) { ensureCardWindow(); addStrip() }
        if (!want && strip != null) removeAll()
        // Our iOS status bar rides on the same overlay windows (above the strip and cards).
        val wantBar = want && strip != null && SystemRestore.statusBarWanted(app)
        if (wantBar && statusBar == null) addStatusBar()
        if (!wantBar && statusBar != null) removeStatusBar()
    }

    // ================================================================== status bar (nav thread)

    /** Our status bar is on screen: only then are the stock clock and icons hidden (SystemRestore.applyFlags). */
    @Volatile var statusBarShown = false
        private set
    private var statusBar: dev.launcher.app.statusbar.StatusBarView? = null
    private val appearanceIo = Executors.newSingleThreadExecutor()
    private var appearanceLogs = 0

    private fun addStatusBar() {
        val ctx = a11y ?: return
        val wm = wm ?: return
        val h = max(systemDimen("status_bar_height"), dp(24).toInt())
        val v = dev.launcher.app.statusbar.StatusBarView(ctx)
        val lp = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, h, touchable = false).apply {
            gravity = Gravity.TOP
            title = "LauncherStatusBar"
        }
        try {
            wm.addView(v, lp)
            statusBar = v
            statusBarShown = true
            AppLog.log("[statusbar] on (${h}px)")
            reapplyFlags()   // now the stock clock and icons can go
            refreshAppearance()
        } catch (t: Throwable) {
            AppLog.log("[statusbar] addView FAILED: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun removeStatusBar() {
        statusBar?.let { try { wm?.removeView(it) } catch (_: Throwable) { } }
        statusBar = null
        nav.removeCallbacks(appearanceTick)
        if (statusBarShown) { statusBarShown = false; reapplyFlags() }   // stock clock and icons back
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
        if (homeVisible) { bar.setDark(HomeBridge.homeStatusDark); bar.setHiddenByApp(false); return }
        val s = ShizukuLink.service ?: return
        appearanceIo.execute {
            val out = try { s.windowAppearance() } catch (t: Throwable) { "ERROR: ${t.message}" }
            val dark = parseLightStatusBar(out)
            val hidden = parseStatusBarHidden(out)
            if (appearanceLogs < 3) { appearanceLogs++; AppLog.log("[statusbar] appearance (dark ${dark ?: "unknown"}, hidden ${hidden ?: "unknown"}) from: ${out.take(700)}") }
            nav.post {
                val b = statusBar ?: return@post
                if (homeVisible) return@post
                if (dark != null) b.setDark(dark)
                if (hidden != null) b.setHiddenByApp(hidden)
            }
        }
    }

    /** True if the status bar's insets source is not visible (an immersive app hid it); null if not found. */
    private fun parseStatusBarHidden(out: String): Boolean? {
        val line = out.lines().firstOrNull { it.contains("mType=statusBars") } ?: return null
        return when {
            line.contains("mVisible=false") -> true
            line.contains("mVisible=true") -> false
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
            if (strip != null) removeAll()
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
        ready = false
        reapplyFlags()
        nav.post {
            if (a11y !== service) return@post
            removeAll()
            try { app.unregisterReceiver(screenReceiver) } catch (_: Throwable) { }
            a11y = null
            wm = null
        }
    }

    fun onHomeShown() {
        homeVisible = true
        // The app that was in front has just left it: the system's snapshot of it (taken as it left) is newer than ours.
        lastFrontPkg?.let { leftFrontAt[it] = SystemClock.uptimeMillis() }
        nav.post { refreshAppearance() }
    }

    /** From NavAccessibilityService: a window of [pkg] came to the front. */
    fun onWindowStateChanged(pkg: String?, className: String? = null) {
        if (pkg == null) return
        // The keyboard is not the app in front: taken for it, a close aimed at the keyboard's package and a launch that showed
        // the keyboard looked like the wrong app had come up (seen on the S24 after App Library search).
        if (isKeyboard(pkg, className)) return
        if (pkg != lastFrontPkg) AppLog.log("[front] now in front: $pkg")
        nav.post { refreshAppearance() }   // a different window may ask for a different status bar
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
            !className.endsWith(".HomeActivity") && '$' !in className

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
        val h = max(dp(20).toInt(), systemDimen("navigation_bar_gesture_height").takeIf { it > 0 } ?: systemDimen("navigation_bar_height"))
        val stripView = FrameLayout(ctx)
        val pill = View(ctx).apply {
            background = GradientDrawable().apply { setColor(0x99FFFFFF.toInt()); cornerRadius = dp(2) }
        }
        stripView.addView(pill, FrameLayout.LayoutParams(dp(108).toInt(), dp(4).toInt(), Gravity.CENTER))
        pill.translationY = dp(6)   // a little lower than centre, closer to the bottom edge
        stripView.setOnTouchListener { _, e -> onTouch(e) }
        val lp = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, h, touchable = true).apply {
            gravity = Gravity.BOTTOM
            title = "LauncherGestureStrip"
        }
        try {
            wm.addView(stripView, lp)   // added after the card window, so it stays above it
            strip = stripView
            AppLog.log("[nav] gesture strip on (${h}px high, own UI thread, persistent card window)")
        } catch (t: Throwable) {
            AppLog.log("[nav] strip addView FAILED: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun readDisplay() {
        val wm = wm ?: return
        val dm = android.util.DisplayMetrics()
        val display = wm.defaultDisplay
        display.getRealMetrics(dm)
        sw = dm.widthPixels.toFloat()
        sh = dm.heightPixels.toFloat()
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
        // The picture of home sits under the cards but outside their container, always in the drawing tree (at alpha 0 while
        // no card shows): a view that leaves the tree loses its GPU layers, and re-allocating them cost ~12-20 ms of the
        // first frame of every gesture (traced on the S24). The container mirrors its visibility and fade onto it.
        b.alpha = 0f
        host.addView(b, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        host.addView(r, 1, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        r.mirror = b
        r.addView(p, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        r.addView(n, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        r.addView(c, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
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
            root = r; backdrop = b; prv = p; nxt = n; cur = c; deck = d
            host.holeRadius = dp(16)
            pollFloating()
            d.setScreen(sw, sh, deviceRadius)
            input = dev.launcher.app.switcher.SwitcherInput { e -> nav.post { onSwitcherTouch(e); e.recycle() } }
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
        }
        deck?.setScreen(sw, sh, deviceRadius)
        return true
    }

    // The card window (always added) and whether it is at window alpha 1.
    private var cardWindow: CardHost? = null
    private var warm: WarmView? = null

    /**
     * The card window's content view. Floating windows (picture-in-picture, pop-up windows) lie above every app but under
     * our overlay: whatever we draw over them hides them, so a video vanished for the length of every launch and close.
     * The window leaves [holes] where they are (nothing drawn there: the floating window shows through, live), with their
     * rounded corners.
     */
    private class CardHost(ctx: Context) : FrameLayout(ctx) {
        var holes: List<RectF> = emptyList()
            set(v) { if (field != v) { field = v; invalidate() } }
        var holeRadius = 0f
        private val path = android.graphics.Path()

        override fun dispatchDraw(canvas: Canvas) {
            if (holes.isEmpty()) { super.dispatchDraw(canvas); return }
            canvas.save()
            for (h in holes) {
                path.reset()
                path.addRoundRect(h, holeRadius, holeRadius, android.graphics.Path.Direction.CW)
                canvas.clipOutPath(path)
            }
            super.dispatchDraw(canvas)
            canvas.restore()
        }
    }

    // ---- floating windows (nav thread): where they are, asked of the shell every FLOATING_POLL_MS (a few ms of a worker's
    // time), at the start of every card session, and every FLOATING_BUSY_MS while cards show (a PiP window can be dragged).
    private var floating: List<RectF> = emptyList()
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
            val rects = lines.mapNotNull { line ->
                val p = line.split(' ')
                if (p.size < 7) return@mapNotNull null
                val l = p[3].toFloatOrNull() ?: return@mapNotNull null
                val t = p[4].toFloatOrNull() ?: return@mapNotNull null
                val r = p[5].toFloatOrNull() ?: return@mapNotNull null
                val b = p[6].toFloatOrNull() ?: return@mapNotNull null
                // A pop-up window the size of the screen is not floating over anything.
                if (r - l >= sw - 1f && b - t >= sh - 1f) return@mapNotNull null
                RectF(l, t, r, b)
            }
            nav.post {
                if (rects != floating) {
                    floating = rects
                    val note = lines.joinToString("; ")
                    if (note != floatingLogged) { floatingLogged = note; AppLog.log("[nav] floating windows: ${note.ifEmpty { "none" }}") }
                    applyFloating()
                }
            }
        }
    }

    /** The holes and the touchable region (during a close) leave the floating windows alone. */
    private fun applyFloating() {
        cardWindow?.holes = floating
        if (catching) cardWindow?.rootSurfaceControl?.setTouchableRegion(fullRegion())
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

    private const val CATCH_UP_MS = 120.0
    // A picture of the app in front younger than this is shown at once by a home gesture; kept that young in the background.
    private const val RECENT_MS = 10_000L
    private const val KEEP_FRESH_MS = 6_000L
    private const val KEEP_FRESH_SETTLE_MS = 400L
    private const val SWITCHER_REFRESH_SETTLE_MS = 150L
    private const val KEEP_FRESH_MIN_GAP_MS = 1_200L
    private var catchUpAt = 0L          // nanoTime when a late card appeared during a drag (0 = not catching up)
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

    /** Ends the card session: window invisible (kept), icon back, everything pending forgotten. */
    private fun hideCards() {
        catchTouchesForHome(false)
        gen++
        animating = false
        onSettled = null
        waitingFor = null
        nav.removeCallbacks(waitTimeout)
        phase = Phase.IDLE
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
        }
        backdrop?.picture = null
        HomeBridge.homeCovered = false
        pictureDropped = false
        closeDepthFrom = -1f
        prv?.visibility = View.GONE
        nxt?.visibility = View.GONE
        cur?.snapshot = null
        cur?.homePicture = null
        switchFromHome = false
        // A card that ended inside an icon is fully "icon": reused as is, the next switch drew a huge icon over the snapshot.
        cur?.iconMix = 0f
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
        hideCards()
        nav.removeCallbacks(scalesBack)
        ShizukuLink.service?.let { s -> frontIo.execute { restoreScales(s) } }
        nav.removeCallbacks(floatingPoll)
        strip?.let { try { wm?.removeView(it) } catch (_: Throwable) { } }
        cardWindow?.let { try { wm?.removeView(it) } catch (_: Throwable) { } }
        input?.quit()
        strip = null
        cardWindow = null; windowShown = false; warm = null
        root = null; backdrop = null; prv = null; nxt = null; cur = null; deck = null; input = null
        AppLog.log("[nav] gesture strip off")
    }

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
        c.badge = pkg?.let { Badges.count(it) } ?: 0
        c.unitPx = sw / 402f
        c.minIconSize = Icons.homeSize.toFloat()
        // Without a snapshot the card shows the app's launch screen: its splash colour behind its icon.
        if (pkg != null) {
            c.placeholderColor = SplashColors.cached(pkg) ?: DEFAULT_SPLASH
            if (SplashColors.cached(pkg) == null) SplashColors.resolve(app, pkg) { col -> nav.post { if (cardPkgFor(c) == pkg) c.fadePlaceholderTo(col) } }
        }
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
                    // On home: it is recorded as it shows right now and rendered ahead, so a swipe up can show it receding from
                    // its first frame (the picture from when home last came to rest can be out of date: a folder opened since).
                    if (homeVisible) {
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
        val s = c.w / sw
        // A launch card that already covers the screen: home behind it is shown as it is now, not as it was at the tap
        // (opened from Spotlight, the search has ended behind the app since: the close showed Spotlight, then home snapped
        // to the first page). Unseen while the card covers everything.
        if (wasAnim == Anim.LAUNCH && s > 0.97f) HomeBridge.previewFor(cardPkg)?.let { backdrop?.picture = it }
        if (backdrop?.picture == null) backdrop?.picture = HomeBridge.previewFor(cardPkg)
        pictureDropped = false
        travel0 = travelForScale(s)
        lastTravel = travel0
        // The card may be smaller, squarer, rounder or more "icon" than the drag model can produce (e.g. grabbed while
        // flying into an icon). Keep it exactly as it is and blend towards the model as the finger pulls it back down.
        grabK = s / homeScale(travel0)
        grabHK = (c.h / c.w) / (sh / sw)
        grabR = c.radius
        grabMix = c.iconMix
        grabDepth = backdrop?.depth ?: 1f
        anchorX = (c.cx - x) / s
        anchorBottom = (c.cy + c.h / 2 - y) / s
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
        parts.getOrNull(0)?.toIntOrNull()?.let { Task(it, parts.getOrElse(1) { "?" }) }
    }

    /** Our own tasks (home, the dev panel) are not apps to switch between or go back to. */
    private fun List<Task>.switchable() = filter { it.pkg != app.packageName }

    // The home screen's shaped icon when it has one (the card must turn into exactly what home shows), else the system's.
    private fun iconFor(pkg: String): Drawable? = Icons.drawableFor(pkg) ?: icons[pkg] ?: try {
        app.packageManager.getApplicationIcon(pkg).also { icons[pkg] = it }
    } catch (_: Throwable) { null }

    /** Tasks known. A fresh gesture's card can show at once if we already have an image of that app. */
    private fun onTasks(fresh: Boolean) {
        val f = fg ?: return
        if (!fresh || switchFromHome) return   // a switch from home keeps home as its card
        if (phase == Phase.DRAG_HOME || phase == Phase.DRAG_SWITCH) {
            cardPkg = f.pkg
            // The latest picture at once, even if the app changed since: the card follows the finger from the first frame and
            // the fresh picture replaces it as soon as it arrives (onFgFresh). Waiting for it left the card ~80 ms late.
            if (fgFresh == null) recentImage(f.pkg)?.let { cur?.let { c -> setCardContent(c, f.pkg, it) } }
            if (phase == Phase.DRAG_HOME) backdrop?.picture = HomeBridge.previewFor(f.pkg)
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
                if (b != null && (!showsKept || keptStale)) c.snapshot = b
                else if (b == null && c.snapshot == null) {
                    val kept = recentImage(pkg)
                    if (kept != null) c.snapshot = kept else c.icon = fg?.let { iconFor(it.pkg) }
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
        setCardContent(c, f?.pkg, fgFresh ?: recentImage(f?.pkg))
        c.iconMix = 0f
        c.setFrame(sw / 2, sh / 2, sw, sh, deviceRadius)
        prv?.visibility = View.GONE
        root?.setBackgroundColor(if (HomeBridge.previewFor(f?.pkg) == null) 0xFF101418.toInt() else 0)
        backdrop?.picture = HomeBridge.previewFor(f?.pkg)
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
            val p = ((System.nanoTime() - catchUpAt) / 1e6 / CATCH_UP_MS).coerceIn(0.0, 1.0).toFloat()
            val k = 1f - (1f - p) * (1f - p) * (1f - p)
            cx = sw / 2 + (cx - sw / 2) * k
            cy = sh / 2 + (cy - sh / 2) * k
            fw = sw + (fw - sw) * k
            fh = sh + (fh - sh) * k
            if (p >= 1f) catchUpAt = 0L
        }
        c.setFrame(cx, cy, fw, fh, deviceRadius + (grabR - deviceRadius) * b)
        c.iconMix = grabMix * b
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
            if (homeVisible && !appStarted) homeRequestedAt = 0L else startHome()
            val pkg = cardPkg
            val target = pkg?.let { HomeBridge.iconRect(it) }
            val size = target?.width() ?: (sw * 0.3f)
            val sizeH = target?.height() ?: (sh * 0.3f)
            switchAt = 0L   // going home ends any run of quick switches
            c.badge = pkg?.let { Badges.count(it) } ?: 0   // as it will show on home (it may have changed while the app was open)
            hideIcon(if (target != null) pkg else null)
            cardIconSize = size
            beginCardSprings(toIcon = target != null)
            // Not on the home screen: the card shrinks to the centre and fades out on the way, instead of turning into an icon
            // that would then linger and fade.
            springFade = target == null
            val tx = target?.centerX() ?: (sw / 2)
            val ty = target?.centerY() ?: (sh / 2)
            // The close looks and lasts the same however fast the flick was: only a little of the finger's motion towards the
            // target carries over ([towards]), so the card neither stops dead at the release nor gets thrown.
            val mp = Motion.profile
            sCx = mp.appClosePosition.spring().apply { start(c.cx, towards(vx, c.cx, tx), tx) }
            sCy = mp.appClosePosition.spring().apply { start(c.cy, towards(vy, c.cy, ty), ty) }
            sW = mp.appCloseSize.spring().apply { start(c.w, towards(vW, c.w, size), size) }
            sH = mp.appCloseSize.spring().apply { start(c.h, towards(vH, c.h, sizeH), sizeH) }
            val depth0 = depthNow()
            sDepth = mp.homeDepthClose.spring().apply { start(depth0, 0f, 0f) }
            closeDepthFrom = depth0
            anim = Anim.HOME_COMMIT
            catchTouchesForHome(true)
            endLabel = when {
                target != null -> "home (into the icon of $pkg at ${target.centerX().toInt()},${target.centerY().toInt()})"
                pkg == null -> "home (to centre; app NOT KNOWN yet at release: task lookup still running)"
                else -> "home (to centre; $pkg has no icon on home)"
            } + "; front report ${lastFrontPkg ?: "-"}, top task ${recentList.firstOrNull()?.pkg ?: "-"}"
            onSettled = {
                phase = Phase.HOLD
                // The picture of home is on top of the real one: swap only once the real home has drawn.
                whenHomeDrawn(g) {
                    if (target != null && pkg != null) {
                        // The real icon back first; the card (now the icon) goes only once home has drawn a frame with it:
                        // never a frame with neither (a fixed delay left a gap whenever home's draw came late).
                        hiddenIconPkg = null
                        HomeBridge.showIconThen(pkg) { nav.post { if (gen == g) hideCards() } }
                    } else hideCards()   // already faded out on the way
                }
            }
        } else {
            cardIconSize = 0f
            beginCardSprings(toIcon = false)
            val sp = Motion.profile.appCancel
            sCx = sp.spring().apply { start(c.cx, vx, sw / 2) }
            sCy = sp.spring().apply { start(c.cy, vy, sh / 2) }
            sW = sp.spring().apply { start(c.w, vW, sw) }
            sH = sp.spring().apply { start(c.h, vH, sh) }
            sDepth = sp.spring().apply { start(depthNow(), 0f, 1f) }
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
            HomeBridge.animateDepth(closeDepthFrom, 0f, 0f, spec.response, spec.damping, springStartNs)
        }
    }

    // Depth the close started from (the real home follows it); -1 when not closing.
    private var closeDepthFrom = -1f
    // The picture of home was taken away during a close: the real home (interactive, live) is what shows behind the card.
    private var pictureDropped = false

    /**
     * Home was touched while a closing card is still flying: the touch belongs to home (swiping pages, opening another
     * app), so the card gets out of the way at once (fades where it is) and its icon is back.
     */
    private fun homeTouchedDuringClose() {
        if (anim != Anim.HOME_COMMIT || root?.visibility != View.VISIBLE) return
        // Touches only reach home once it is the window in front: the real home (running the same depth spring) can show.
        backdrop?.picture = null
        HomeBridge.homeCovered = false
        pictureDropped = true
        hiddenIconPkg?.let { HomeBridge.setIconHidden(it, false) }
        hiddenIconPkg = null
        AppLog.log("[nav] home touched during the close: the card fades where it is")
        fadeOutCards(110, gen, overHome = true)
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

    // Card fades out as it shrinks (closing an app that has no icon on the home screen).
    private var springFade = false

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

    private fun beginCardSprings(toIcon: Boolean) {
        val c = cur ?: return
        springW0 = c.w
        springR0 = c.radius
        springMix0 = c.iconMix
        springToIcon = toIcon
    }

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
            // To full screen (opening, or springing back): corners to the display's, any icon fades as the card grows.
            radius = springR0 + (deviceRadius - springR0) * q
            c.iconMix = springMix0 * (1f - ((w - springW0) / (max(springW0, dp(40)) * 1.5f)).coerceIn(0f, 1f))
        }
        var r = radius
        if (springFade) {
            // The app itself, smaller, with the display's corner shape scaled down, fading away.
            c.iconMix = 0f
            c.alpha = 1f - ((q - 0.25f) / 0.6f).coerceIn(0f, 1f)
            r = deviceRadius * (w / sw)
        }
        c.setFrame(sCx.value(t), sCy.value(t), w, h, r)
        backdrop?.depth = sDepth.value(t)
        return sCx.settled(t) && sCy.settled(t) && sW.settled(t) && sH.settled(t)
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

    private fun beginLaunch(pkg: String, iconRect: RectF, icon: Drawable?, start: () -> Unit) {
        if (phase == Phase.DRAG_HOME || phase == Phase.DRAG_SWITCH || !prepareCardWindow()) { main.post(start); return }
        val c = cur ?: run { main.post(start); return }
        // Tapping the icon of the app whose card is closing into it reopens it from where the card is (open/close the same
        // app within 0.1 s must stay smooth).
        val reverse = root?.visibility == View.VISIBLE && cardPkg == pkg
        // Where the card starts and how fast it moves: from where it is if this is the same app (reversal), else the icon.
        val m = if (reverse) cardMotion() else floatArrayOf(iconRect.centerX(), iconRect.centerY(), iconRect.width(), iconRect.height(), 0f, 0f, 0f, 0f)
        val depth0 = if (reverse) depthNow() else 0f
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
        val picture = HomeBridge.previewFor(pkg)
        // Reopening a closing app whose picture of home was already dropped: cover home again (the app starts at once).
        if (reverse && backdrop?.picture == null) backdrop?.picture = picture
        pictureDropped = false
        if (!reverse) {
            c.homePicture = null
            c.icon = icon
            c.badge = Badges.count(pkg)
            c.unitPx = sw / 402f
            c.snapshot = images[pkg]
            c.minIconSize = iconRect.width()
            // The app's own launch-screen colour (resolved ahead of time for home's apps), else the icon's colour.
            c.placeholderColor = SplashColors.cached(pkg) ?: icon?.let { averageColor(it) } ?: DEFAULT_SPLASH
            if (SplashColors.cached(pkg) == null) SplashColors.resolve(app, pkg) { col -> nav.post { if (cardPkg == pkg) c.fadePlaceholderTo(col) } }
            c.setFrame(m[0], m[1], m[2], m[3], m[2] * Icons.shape.clipFraction())
            c.iconMix = 1f
            prv?.visibility = View.GONE
            root?.setBackgroundColor(0)
            backdrop?.picture = picture
            backdrop?.depth = 0f
        }
        appStarted = false
        pendingStart = Runnable(start)
        // The icon on home goes only once the card (drawing that icon) is on screen: hidden at once, home's next frame came a
        // refresh before the card window's first one and the icon blinked out (seen in folder launches on the S24).
        if (reverse) hideIcon(pkg) else afterCardFrame(g) { if (cardPkg == pkg) hideIcon(pkg) }
        cardIconSize = iconRect.width()
        switchAt = 0L   // a launch ends any run of quick switches
        beginCardSprings(toIcon = false)
        val open = Motion.profile.appOpen
        sCx = open.spring().apply { start(m[0], m[4], sw / 2) }
        sCy = open.spring().apply { start(m[1], m[5], sh / 2) }
        sW = open.spring().apply { start(m[2], m[6], sw) }
        sH = open.spring().apply { start(m[3], m[7], sh) }
        sDepth = Motion.profile.homeDepthOpen.spring().apply { start(depth0, 0f, 1f) }
        endLabel = "launch $pkg${if (reverse) " (reversed a closing card)" else ""}"
        val startApp = { runPendingStart() }
        onSettled = {
            startApp()   // without a picture of home the app is only started now, so it cannot show early
            phase = Phase.HOLD
            if (lastFrontPkg == pkg && lastFrontAt >= since) fadeOutCards(90, g)
            else awaitForeground(pkg, g) {
                // Our own screens (the developer panel, safe settings) say when they have drawn (ownScreenDrawn).
                if (lastFrontPkg == pkg || pkg == app.packageName) { fadeOutCards(90, g); return@awaitForeground }
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
                        }
                        fadeOutCards(90, g)
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
            nav.post { if (gen == g && cardPkg == pkg) { c.snapshot = b; fg = task } }
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
        val runGoesOn = now - switchAt < SWITCH_RUN_MS && switchList.getOrNull(switchIndex)?.pkg == cardPkg
        if (!runGoesOn) {
            val list = recentList.switchable().toMutableList()
            val f = fg
            if (f != null && list.firstOrNull()?.pkg != f.pkg) { list.removeAll { it.pkg == f.pkg }; list.add(0, f) }
            switchList = list
            switchIndex = 0
        }
        older = switchList.getOrNull(switchIndex + 1)
        // Sideways only ever goes through apps, never back to home (a run that started on home keeps it out).
        newer = switchList.getOrNull(switchIndex - 1)?.takeIf { it !== homeTask }
        cur?.iconMix = 0f
        offset = 0f
        switchScale = 1f
        backdrop?.picture = null
        root?.setBackgroundColor(0xFF000000.toInt())   // what is revealed beside the cards
        prv?.let { pc -> setCardContent(pc, older?.pkg, older?.let { images[it.pkg] }); pc.visibility = if (older != null) View.VISIBLE else View.GONE }
        nxt?.let { nc -> setCardContent(nc, newer?.pkg, newer?.let { images[it.pkg] }); nc.visibility = if (newer != null) View.VISIBLE else View.GONE }
        loadNeighbourImages()
        layoutSwitch(0f, 1f)
        maybeShow()
        stats.start()
    }

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
            sOff = Motion.profile.switchCommit.spring().apply { start(offset, vx, if (toOlder) sw + gap() else -(sw + gap())) }
            sScale = Motion.profile.switchCommit.spring().apply { start(switchScale, 0f, 1f) }
            anim = Anim.SWITCH_COMMIT
            endLabel = "quick switch to ${if (toOlder) "an older" else "a newer"} app (${target.pkg})"
            onSettled = {
                phase = Phase.HOLD
                switchAt = SystemClock.uptimeMillis()
                if (lastFrontPkg == target.pkg && lastFrontAt >= since) hideCards() else awaitForeground(target.pkg, g) { hideCards() }
            }
        } else {
            sOff = Motion.profile.switchCancel.spring().apply { start(offset, vx, 0f) }
            sScale = Motion.profile.switchCancel.spring().apply { start(switchScale, 0f, 1f) }
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
     * Fades the cards out over [ms]. [overHome]: the card is small over the picture of home, which fades with it (the real
     * home under it looks the same). Otherwise (the end of a launch) the card covers the whole screen and the picture goes at
     * once: fading it with the card let it show through the half-transparent card, a grey flash at the end of every launch
     * (screen-recorded on the S24 at 120 fps).
     */
    private fun fadeOutCards(ms: Long, g: Int, overHome: Boolean = false) {
        val r = root ?: return
        if (gen != g) return
        if (!overHome) backdrop?.alpha = 0f
        // Set (or cleared) every time: a view's animator keeps its update listener from one animation to the next.
        r.animate().alpha(0f).setDuration(ms)
            .setUpdateListener(if (overHome) android.animation.ValueAnimator.AnimatorUpdateListener { backdrop?.alpha = r.alpha } else null)
            .withEndAction { if (gen == g) hideCards() }.start()
    }

    // ================================================================== task switching

    private var waitingFor: Pair<String, Runnable>? = null
    private val waitTimeout = Runnable { val w = waitingFor; waitingFor = null; w?.second?.run() }

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
    private var openedAt = 0L
    private var depthAtSwitcher = 1f

    // Home behind the deck: from the drag's depth to fully receded and dimmed (0..1).
    private var switcherGround = 0   // opaque ground behind the deck when there is no picture of home (else 0)
    private val switcherBg = dev.launcher.app.motion.SpringValue(0f, 300f, onChange = { k ->
        backdrop?.depth = depthAtSwitcher + (1f - depthAtSwitcher) * k
        root?.setBackgroundColor(if (switcherGround != 0) switcherGround else ((SWITCHER_DIM * k * 255).toInt().coerceIn(0, 255)) shl 24)
    })

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
        if (phase == Phase.DRAG_HOME) { if (root?.visibility != View.VISIBLE || catchUpAt != 0L) return }
        else if (phase != Phase.HOME_PULL) return
        if (swipeTravel() < Motion.profile.switcher.holdMinTravelDp * density) return   // re-armed by the next move
        val left = holdSince + Motion.profile.switcher.holdMs - SystemClock.uptimeMillis()
        if (left > 0L) { nav.postDelayed(holdCheck, left); return }
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
        val first = if (fromHome) null else tasks.firstOrNull { it.pkg == cardPkg } ?: fg?.takeIf { it.pkg == cardPkg }
        val list = (listOfNotNull(first) + tasks.filter { it.id != first?.id }).take(SWITCHER_MAX_CARDS)
        if (list.isEmpty()) return
        if (fromHome) { enterSwitcherFromHome(list); return }
        phase = Phase.SWITCHER
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
        switcherBg.snapTo(0f)
        switcherBg.animateTo(1f, Motion.profile.switcher.enter, 0f)
        // Pictures for the cards without an up-to-date one come as each card comes into view (deckListener.onWantPicture).
        refreshSwitcherCard()
        stats.reset(); stats.start()
        AppLog.log("[switcher] open: ${cards.size} apps${listNote()} (${cards.take(8).joinToString { it.pkg.substringAfterLast('.') }}); ${pictureAges(cards)}")
    }

    // ---- a swipe up on home: home recedes with the finger (iOS); a rest opens the switcher, a release springs home back

    private var pulling = false                // the picture of home is shown receding (a swipe up on home)
    private var pullPicture: HomePicture? = null   // home as it showed when this swipe touched the bar
    private val pullBack = dev.launcher.app.motion.SpringValue(0f, 300f, onChange = { backdrop?.depth = it })

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
        HomeBridge.animateDepth(d, 0f, 0f, spec.response, spec.damping, System.nanoTime())
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
                    else { d.heldRelease(vt?.xVelocity ?: 0f); input?.show() }
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

    private val deckListener = object : dev.launcher.app.switcher.DeckView.Listener {
        override fun onOpenStart(card: dev.launcher.app.switcher.DeckView.Card) {
            // The input window stays until the cards are gone (hideCards): removing it now made the window manager and the
            // compositor change windows in the middle of the animation.
            openedAt = SystemClock.uptimeMillis()
            // The app the swipe started in is still in front underneath unless home was asked for or it was closed.
            val stillInFront = !homeVisible && card.taskId == recentList.switchable().firstOrNull()?.id && !homeStarted && lastFrontPkg == card.pkg
            cardPkg = card.pkg
            if (!stillInFront) bringBack(card.pkg, Task(card.taskId, card.pkg))
            stats.reset(); stats.start()
            AppLog.log("[switcher] open ${card.pkg}${if (stillInFront) " (still in front)" else ""}")
        }

        override fun onOpened(card: dev.launcher.app.switcher.DeckView.Card) {
            AppLog.log(stats.report("[switcher] ${card.pkg} fills the screen"))
            val g = gen
            if (lastFrontPkg == card.pkg) hideCards() else awaitForeground(card.pkg, g) { hideCards() }
        }

        override fun onHomeStart() {
            if (switcherHomeAsked) return
            switcherHomeAsked = true
            // Home with every icon now that it will sharpen (only the small blurred copy shows yet: cheap to re-render).
            HomeBridge.preview?.let { backdrop?.picture = it }
            depthAtSwitcher = 0f
            if (!homeVisible) {
                startHome()
                // The real home runs the same depth spring underneath, so it matches the picture when that goes.
                val spec = Motion.profile.switcher.home
                HomeBridge.animateDepth(backdrop?.depth ?: 1f, 0f, 0f, spec.response, spec.damping, System.nanoTime())
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
            whenHomeDrawn(g) { hideCards() }
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
    private const val HOME_PULL_DEPTH = 0.6f   // how far home recedes at most while a swipe up on it goes on
    private const val SWITCHER_DIM = 0.28f              // home behind the deck: darkened by this much
    private const val SWITCHER_MAX_CARDS = 50   // every recent app (the system keeps about this many)
    // A window's touchable region can change without a re-layout from Android 14 (AttachedSurfaceControl).
    private val CAN_CATCH_TOUCHES = Build.VERSION.SDK_INT >= 34
    // "Nowhere": one pixel just off screen. An empty region is never sent to the window manager (it equals the initial
    // "previous" region), which leaves the whole window touchable.
    private val NO_TOUCH = android.graphics.Region(-2, -2, -1, -1)
    private const val REMOVE_AFTER_MS = 650L    // a flicked card's app is closed once the deck is still
    private const val FLOATING_POLL_MS = 2500L  // floating windows (PiP, pop-ups) are looked up this often while idle
    private const val FLOATING_BUSY_MS = 300L   // ... and this often while cards show (a PiP window can be dragged)
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
        private class Layer(ctx: Context, private val scale: () -> Float = { 1f }) : View(ctx) {
            init { setLayerType(LAYER_TYPE_HARDWARE, null) }

            var pic: Picture? = null
                set(v) {
                    if (field === v) return
                    field = v
                    invalidate()
                }

            override fun onDraw(canvas: Canvas) {
                val p = pic ?: return
                val s = scale()
                if (s != 1f) canvas.scale(s, s)
                canvas.drawPicture(p)
            }
        }

        private val wallpaperLayer = Layer(ctx)
        private val contentLayer = Layer(ctx)

        // The blur is done on a quarter-size copy of the picture and scaled up: blurring the full-size composite every frame
        // (the radius follows the depth) cost the GPU ~4-8 ms per frame at 120 Hz on the S24, plus a full-size offscreen
        // allocated at each gesture's first frame. A blur looks the same from a quarter-size image, at 1/16 of the pixels.
        // [low] keeps the blurred result in its own (small) layer, so the blur is evaluated at that size and not at the
        // scaled-up size; it fades over the sharp layers for the first few pixels of blur, where the low resolution would show.
        private var lowScale = 0.25f
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
        private val applyPending = Runnable { applySharp(); applyLow() }

        private fun applySharp() { pendingSharp?.let { wallpaperLayer.pic = it.wallpaper; contentLayer.pic = it.content }; pendingSharp = null }
        private fun applyLow() { pendingLow?.let { lowWallpaper.pic = it.wallpaper; lowContent.pic = it.content }; pendingLow = null }

        private fun setPics(p: HomePicture, staggered: Boolean) {
            removeCallbacks(applyPending)
            pendingSharp = p; pendingLow = p
            if (!staggered) { applySharp(); applyLow(); return }
            postOnAnimation(applyPending)
            invalidate()
        }

        override fun dispatchDraw(canvas: Canvas) {
            if (pendingSharp != null && wallpaperLayer.alpha > 0f) applySharp()
            if (pendingLow != null && low.alpha > 0f) applyLow()
            super.dispatchDraw(canvas)
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

        private fun applyDepth() {
            val v = depth
            val mp = Motion.profile
            val wz = 1f + v * (mp.homeWallpaperZoom - 1f)
            val cz = 1f + v * (mp.homeContentZoom - 1f)
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
        }

        private companion object {
            // Below this blur (dp, at full size) the quarter-size copy would look soft rather than blurred: up to it the
            // blurred copy (at this radius) fades in over the sharp picture.
            const val MIN_LOW_BLUR_DP = 3f
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
