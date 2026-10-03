package dev.launcher.app

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
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
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Our bottom-edge gesture navigation and app launch/close animations.
 *
 * Windows (created once, kept while gesture nav is on): a full-display, non-touchable card window (picture of home,
 * previous-app card, current-app card), and above it the touchable gesture strip, so the pill is always visible and the
 * finger always lands on the strip, whatever is animating.
 *
 * Everything is interruptible: a touch during any animation or wait grabs the card where it is ([takeOver]); tapping the
 * icon of the app whose card is on screen reverses it; tapping another icon replaces it. Springs keep their velocity
 * across every hand-over.
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
    private enum class Phase { IDLE, DRAG_HOME, DRAG_SWITCH, ANIM, HOLD }
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

    private fun remember(pkg: String, b: Bitmap) { images[pkg] = b; imagesAt[pkg] = SystemClock.uptimeMillis() }

    /** An earlier image of [pkg], only if recent enough that showing it before the fresh one cannot look stale. */
    private fun recentImage(pkg: String?): Bitmap? {
        pkg ?: return null
        val at = imagesAt[pkg] ?: return null
        return if (SystemClock.uptimeMillis() - at < 10_000) images[pkg] else null
    }

    // Springs
    private var sCx = Spring(0.5f, 0.86f)
    private var sCy = Spring(0.5f, 0.86f)
    private var sW = Spring(0.44f, 0.9f)
    private var sH = Spring(0.44f, 0.9f)
    private var sZoom = Spring(0.5f, 1f)
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

    private const val HOME_ZOOM = 1.08f
    private const val SCALE_RANGE = 0.62f     // smallest card while dragging = 38 % of the screen
    private const val SCALE_LENGTH = 0.28f    // travel (in screen heights) for most of the shrink

    fun init(app: LauncherApp) { this.app = app }

    // ================================================================== public entry points (any thread)

    /** Shows or removes our windows to match the current state. */
    fun update() {
        if (Looper.myLooper() != navThread.looper) { nav.post { update() }; return }
        val want = ready && SystemRestore.gestureFlagsActive && ShizukuLink.service != null && !locked
        if (want && strip == null) { ensureCardWindow(); addStrip() }
        if (!want && strip != null) removeAll()
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

    fun onHomeShown() { homeVisible = true }

    /** From NavAccessibilityService: a window of [pkg] came to the front. */
    fun onWindowStateChanged(pkg: String?) {
        if (pkg == null) return
        if (pkg != lastFrontPkg) AppLog.log("[front] now in front: $pkg")
        lastFrontPkg = pkg
        lastFrontAt = SystemClock.uptimeMillis()
        nav.post {
            val w = waitingFor ?: return@post
            if (w.first == pkg) { waitingFor = null; nav.removeCallbacks(waitTimeout); nav.postDelayed(w.second, 16) }
        }
    }

    /**
     * Opening an app from [iconRect] on home. The card grows out of the icon over our picture of home while the app starts
     * underneath; [start] (which starts the app, on the main thread) runs once our window covers the screen, because with
     * system animations off the app's window appears at full size at once. If that app's card is already on screen (it was
     * just closing), the card reverses from where it is. Returns false if gesture nav cannot animate (caller just starts).
     */
    fun launchApp(pkg: String, iconRect: RectF, icon: Drawable?, start: () -> Unit): Boolean {
        if (!ready) return false
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
        val r = FrameLayout(ctx).apply { visibility = View.INVISIBLE }
        val b = PreviewView(ctx)
        val p = CardView(ctx).apply { visibility = View.GONE }
        val n = CardView(ctx).apply { visibility = View.GONE }
        val c = CardView(ctx)
        r.addView(b, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        r.addView(p, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        r.addView(n, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        r.addView(c, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // Exact full-display size, drawn into the cutout too (the probe saw resizing as a stretch).
        val lp = overlayParams(sw.toInt(), sh.toInt(), touchable = false).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "LauncherCards"
        }
        try {
            wm.addView(r, lp)
            root = r; backdrop = b; prv = p; nxt = n; cur = c
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
        if (sw != oldW || sh != oldH) {
            val lp = r.layoutParams as WindowManager.LayoutParams
            lp.width = sw.toInt()
            lp.height = sh.toInt()
            try { wm?.updateViewLayout(r, lp) } catch (_: Throwable) { }
        }
        return true
    }

    private fun showCards() {
        val r = root ?: return
        r.animate().cancel()
        r.alpha = 1f
        if (r.visibility != View.VISIBLE) {
            r.visibility = View.VISIBLE
            cardVisibleAfter = SystemClock.uptimeMillis() - dragStartedAt
        }
    }

    /** Ends the card session: window invisible (kept), icon back, everything pending forgotten. */
    private fun hideCards() {
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
        root?.let { r ->
            r.animate().cancel()
            r.visibility = View.INVISIBLE
            r.alpha = 1f
            r.setBackgroundColor(0)
        }
        backdrop?.picture = null
        prv?.visibility = View.GONE
        nxt?.visibility = View.GONE
        cur?.snapshot = null
        // A card that ended inside an icon is fully "icon": reused as is, the next switch drew a huge icon over the snapshot.
        cur?.iconMix = 0f
        prv?.iconMix = 0f
        nxt?.iconMix = 0f
        cur?.alpha = 1f
        springFade = false
        cardPkg = null
        homeStarted = false
        hiddenIconPkg?.let { HomeBridge.setIconHidden(it, false) }
        hiddenIconPkg = null
    }

    private fun removeAll() {
        hideCards()
        strip?.let { try { wm?.removeView(it) } catch (_: Throwable) { } }
        root?.let { try { wm?.removeView(it) } catch (_: Throwable) { } }
        strip = null
        root = null; backdrop = null; prv = null; nxt = null; cur = null
        AppLog.log("[nav] gesture strip off")
    }

    private fun systemDimen(name: String): Int {
        val id = app.resources.getIdentifier(name, "dimen", "android")
        return if (id != 0) app.resources.getDimensionPixelSize(id) else 0
    }

    private fun hideIcon(pkg: String?) {
        if (pkg == hiddenIconPkg) return
        hiddenIconPkg?.let { HomeBridge.setIconHidden(it, false) }
        hiddenIconPkg = pkg
        pkg?.let { HomeBridge.setIconHidden(it, true) }
    }

    private fun setCardContent(c: CardView, pkg: String?, bitmap: Bitmap?) {
        c.snapshot = bitmap
        c.icon = pkg?.let { iconFor(it) }
    }

    // ================================================================== touch (nav thread)

    private fun onTouch(e: MotionEvent): Boolean {
        val raw = MotionEvent.obtain(e).apply { setLocation(e.rawX, e.rawY) }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vt?.recycle()
                vt = VelocityTracker.obtain().also { it.addMovement(raw) }
                downX = e.rawX
                downY = e.rawY
                stats.reset()
                if ((phase == Phase.ANIM || phase == Phase.HOLD) && root?.visibility == View.VISIBLE) takeOver(e.rawX, e.rawY)
                else { hideCards(); pendingFresh = true; prefetch(fresh = true) }
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(raw)
                val dx = e.rawX - downX
                val dy = downY - e.rawY
                if (pendingFresh && !homeVisible) {
                    if (dy > dp(10) && dy > abs(dx)) { pendingFresh = false; beginHome() }
                    else if (abs(dx) > dp(14) && abs(dx) > abs(dy) * 1.2f) { pendingFresh = false; beginSwitch() }
                } else if (grabbedFull) {
                    if (dy > dp(8) && dy > abs(dx)) { grabbedFull = false; phase = Phase.DRAG_HOME }
                    else if (abs(dx) > dp(12) && abs(dx) > abs(dy) * 1.2f) { grabbedFull = false; beginSwitch() }
                }
                when (phase) {
                    Phase.DRAG_HOME -> { dragHome(e.rawX, e.rawY); stats.touchEvent(e.eventTime) }
                    Phase.DRAG_SWITCH -> { dragSwitch(e.rawX); stats.touchEvent(e.eventTime) }
                    else -> {}
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(raw)
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                val vy = vt?.yVelocity ?: 0f
                val up = e.actionMasked == MotionEvent.ACTION_UP
                when {
                    phase == Phase.DRAG_HOME -> releaseHome(up, vx, vy)
                    phase == Phase.DRAG_SWITCH -> releaseSwitch(up, vx)
                    grabbedFull -> { grabbedFull = false; releaseHome(false, 0f, 0f) }   // a tap on a full-size card: let it finish
                    pendingFresh && homeVisible && up && abs(e.rawX - downX) > dp(40) -> returnToLastApp()
                }
                pendingFresh = false
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
            c.snapshot = from?.snapshot
            c.icon = from?.icon
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
            backdrop?.zoom = HOME_ZOOM
        }
        if (backdrop?.picture == null) backdrop?.picture = HomeBridge.previewFor(cardPkg)
        val s = c.w / sw
        travel0 = travelForScale(s)
        lastTravel = travel0
        // The card may be smaller, squarer, rounder or more "icon" than the drag model can produce (e.g. grabbed while
        // flying into an icon). Keep it exactly as it is and blend towards the model as the finger pulls it back down.
        grabK = s / homeScale(travel0)
        grabHK = (c.h / c.w) / (sh / sw)
        grabR = c.radius
        grabMix = c.iconMix
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
            }
            if (onHome || tasks.isEmpty()) return@execute
            snapIo.execute {
                if (fresh) {
                    val t = SystemClock.uptimeMillis()
                    val b = try { s.taskSnapshot(tasks[0].id, true) } catch (_: Throwable) { null }
                    val ms = SystemClock.uptimeMillis() - t
                    b?.let { remember(tasks[0].pkg, it) }
                    nav.post { if (id == gestureId) onFgFresh(b, ms) }
                }
                val p = tasks.getOrNull(1) ?: return@execute
                val pb = try { s.taskSnapshot(p.id, false) } catch (_: Throwable) { null }
                pb?.let { remember(p.pkg, it) }
                nav.post { if (id == gestureId) prvSnapshot = pb ?: images[p.pkg] }
            }
        }
    }

    private fun parseTasks(lines: Array<String>) = lines.mapNotNull { line ->
        val parts = line.split(' ')
        parts.getOrNull(0)?.toIntOrNull()?.let { Task(it, parts.getOrElse(1) { "?" }) }
    }

    /** Our own tasks (home, the dev panel) are not apps to switch between or go back to. */
    private fun List<Task>.switchable() = filter { it.pkg != app.packageName }

    private fun iconFor(pkg: String): Drawable? = icons[pkg] ?: try {
        app.packageManager.getApplicationIcon(pkg).also { icons[pkg] = it }
    } catch (_: Throwable) { null }

    /** Tasks known. A fresh gesture's card can show at once if we already have an image of that app. */
    private fun onTasks(fresh: Boolean) {
        val f = fg ?: return
        if (!fresh) return
        if (phase == Phase.DRAG_HOME || phase == Phase.DRAG_SWITCH) {
            cardPkg = f.pkg
            if (fgFresh == null) recentImage(f.pkg)?.let { cur?.let { c -> setCardContent(c, f.pkg, it) } }
            if (phase == Phase.DRAG_HOME) backdrop?.picture = HomeBridge.previewFor(f.pkg)
            maybeShow()
        }
    }

    private fun onFgFresh(b: Bitmap?, ms: Long) {
        fgSnapMs = ms
        fgFresh = b
        fgFreshDone = true
        if (phase == Phase.DRAG_HOME || phase == Phase.DRAG_SWITCH || anim == Anim.HOME_CANCEL || anim == Anim.HOME_COMMIT) {
            cur?.let { c -> if (b != null) c.snapshot = b else if (c.snapshot == null) c.icon = fg?.let { iconFor(it.pkg) } }
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
        backdrop?.zoom = HOME_ZOOM
        travel0 = 0f
        lastTravel = 0f
        resetGrab()
        anchorX = sw / 2 - downX
        anchorBottom = sh - downY
        maybeShow()
        stats.start()
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

    private fun resetGrab() { grabK = 1f; grabHK = 1f; grabR = deviceRadius; grabMix = 0f }

    private fun dragHome(x: Float, y: Float) {
        val c = cur ?: return
        lastTravel = travel0 + (downY - y)
        // 1 at the grab point (and above it), 0 at full size: a grabbed card keeps its look and turns into a plain card
        // only as it is pulled back towards full screen.
        val b = if (travel0 > 1f) (lastTravel / travel0).coerceIn(0f, 1f) else 0f
        val s = homeScale(lastTravel) * (1f + (grabK - 1f) * b)
        val w = sw * s
        val h = sh * s * (1f + (grabHK - 1f) * b)
        // The finger keeps its place on the card as it shrinks, so the card visibly comes away from the top edge.
        c.setFrame(x + anchorX * s, y + anchorBottom * s - h / 2, w, h, deviceRadius + (grabR - deviceRadius) * b)
        c.iconMix = grabMix * b
    }

    private fun releaseHome(up: Boolean, vx: Float, vy: Float) {
        val c = cur ?: run { hideCards(); return }
        val upSpeed = -vy
        val commit = up && ((upSpeed > 350f && lastTravel > dp(30)) || (lastTravel > sh * 0.2f && upSpeed > -250f))
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
            hideIcon(if (target != null) pkg else null)
            cardIconSize = size
            beginCardSprings(toIcon = target != null)
            // Not on the home screen: the card shrinks to the centre and fades out on the way, instead of turning into an icon
            // that would then linger and fade.
            springFade = target == null
            val tx = target?.centerX() ?: (sw / 2)
            val ty = target?.centerY() ?: (sh / 2)
            // Only the part of the fling that points at the target carries over (plus a little): a fast flick up used to throw
            // the card far above the dock before it came back down into the icon.
            sCx = Spring(0.5f, 0.92f).apply { start(c.cx, towards(vx, c.cx, tx), tx) }
            sCy = Spring(0.5f, 0.92f).apply { start(c.cy, towards(vy, c.cy, ty), ty) }
            sW = Spring(0.44f, 0.9f).apply { start(c.w, vW, size) }
            sH = Spring(0.44f, 0.9f).apply { start(c.h, vH, sizeH) }
            sZoom = Spring(0.5f, 1f).apply { start(backdrop?.zoom ?: 1f, 0f, 1f) }
            anim = Anim.HOME_COMMIT
            endLabel = if (target != null) "home (into icon)" else "home (to centre; app not on the home screen)"
            onSettled = {
                phase = Phase.HOLD
                // The picture of home is on top of the real one: swap only once the real home has drawn.
                whenHomeDrawn(g) {
                    if (target != null && pkg != null) {
                        // Real icon back first, cards a couple of frames later: never a frame with neither.
                        HomeBridge.setIconHidden(pkg, false)
                        hiddenIconPkg = null
                        nav.postDelayed({ if (gen == g) hideCards() }, 32)
                    } else hideCards()   // already faded out on the way
                }
            }
        } else {
            cardIconSize = 0f
            beginCardSprings(toIcon = false)
            sCx = Spring(0.38f, 1f).apply { start(c.cx, vx, sw / 2) }
            sCy = Spring(0.38f, 1f).apply { start(c.cy, vy, sh / 2) }
            sW = Spring(0.38f, 1f).apply { start(c.w, vW, sw) }
            sH = Spring(0.38f, 1f).apply { start(c.h, vH, sh) }
            sZoom = Spring(0.38f, 1f).apply { start(backdrop?.zoom ?: 1f, 0f, HOME_ZOOM) }
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
    }

    private fun startHome() {
        homeStarted = true
        homeRequestedAt = SystemClock.uptimeMillis()
        homeVisible = false
        // startActivity is a binder round trip of tens of ms: never on the nav thread, which is drawing the card right now.
        front("home") {
            try {
                app.startActivity(Intent(app, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), noAnimation(app))
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

    /** Release velocity component that moves [from] towards [to], plus a small share of the rest; capped. */
    private fun towards(v: Float, from: Float, to: Float): Float {
        val capped = v.coerceIn(-5000f, 5000f)
        return if ((to - from) * capped > 0f) capped else capped * 0.12f
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
            radius = springR0 + (target * 0.23f - springR0) * q
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
        backdrop?.zoom = sZoom.value(t)
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

    // ================================================================== LAUNCH

    private fun beginLaunch(pkg: String, iconRect: RectF, icon: Drawable?, start: () -> Unit) {
        if (phase == Phase.DRAG_HOME || phase == Phase.DRAG_SWITCH || !prepareCardWindow()) { main.post(start); return }
        val c = cur ?: run { main.post(start); return }
        // Tapping the icon of the app whose card is closing into it reopens it from where the card is (open/close the same
        // app within 0.1 s must stay smooth).
        val reverse = root?.visibility == View.VISIBLE && cardPkg == pkg
        // Where the card starts and how fast it moves: from where it is if this is the same app (reversal), else the icon.
        val m = if (reverse) cardMotion() else floatArrayOf(iconRect.centerX(), iconRect.centerY(), iconRect.width(), iconRect.height(), 0f, 0f, 0f, 0f)
        val zoom0 = if (reverse) backdrop?.zoom ?: 1f else 1f
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
        if (!reverse) {
            c.icon = icon
            c.snapshot = images[pkg]
            c.placeholderColor = icon?.let { averageColor(it) } ?: 0xFF2A2F3A.toInt()
            c.setFrame(m[0], m[1], m[2], m[3], m[2] * 0.23f)
            c.iconMix = 1f
            prv?.visibility = View.GONE
            root?.setBackgroundColor(0)
            backdrop?.picture = picture
            backdrop?.zoom = 1f
        }
        appStarted = false
        pendingStart = Runnable(start)
        hideIcon(pkg)
        cardIconSize = iconRect.width()
        switchAt = 0L   // a launch ends any run of quick switches
        beginCardSprings(toIcon = false)
        sCx = Spring(0.42f, 0.92f).apply { start(m[0], m[4], sw / 2) }
        sCy = Spring(0.42f, 0.92f).apply { start(m[1], m[5], sh / 2) }
        sW = Spring(0.42f, 0.92f).apply { start(m[2], m[6], sw) }
        sH = Spring(0.42f, 0.92f).apply { start(m[3], m[7], sh) }
        sZoom = Spring(0.45f, 1f).apply { start(zoom0, 0f, HOME_ZOOM) }
        endLabel = "launch $pkg${if (reverse) " (reversed a closing card)" else ""}"
        val startApp = { runPendingStart() }
        onSettled = {
            startApp()   // without a picture of home the app is only started now, so it cannot show early
            phase = Phase.HOLD
            if (lastFrontPkg == pkg && lastFrontAt >= since) fadeOutCards(90, g)
            else awaitForeground(pkg, g) {
                if (lastFrontPkg != pkg) {
                    // Not the app that was tapped: record what is there instead, and ask for the tapped one again.
                    AppLog.log("[front] WRONG APP after launching $pkg: $lastFrontPkg is in front; starting $pkg again")
                    bringBack(pkg, null)
                }
                fadeOutCards(90, g)
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
            val b = try { s.taskSnapshot(task.id, false) } catch (_: Throwable) { null } ?: return@execute
            remember(pkg, b)
            nav.post { if (gen == g && cardPkg == pkg) { c.snapshot = b; fg = task } }
        }
    }

    /**
     * "Start this without a system transition": our card is the animation. Per launch, so the system animation scales stay
     * at the user's values and apps keep all their own transitions (with the scales at 0 every app felt choppy).
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
    private var older: Task? = null
    private var newer: Task? = null
    private var switchTarget: Task? = null
    private var switchTargetIsOlder = true
    private const val SWITCH_RUN_MS = 4000L

    private fun beginSwitch() {
        if (!prepareCardWindow()) return
        val fromGrab = root?.visibility == View.VISIBLE
        gen++
        phase = Phase.DRAG_SWITCH
        anim = Anim.NONE
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
        newer = switchList.getOrNull(switchIndex - 1)
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

    /** Cached snapshots of the neighbours (how they look when they come back), if we do not have them yet. */
    private fun loadNeighbourImages() {
        val s = ShizukuLink.service ?: return
        val g = gen
        for ((task, card) in listOf(older to prv, newer to nxt)) {
            task ?: continue
            snapIo.execute {
                val b = try { s.taskSnapshot(task.id, false) } catch (_: Throwable) { null } ?: return@execute
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
            sOff = Spring(0.35f, 1f).apply { start(offset, vx, if (toOlder) sw + gap() else -(sw + gap())) }
            sScale = Spring(0.35f, 1f).apply { start(switchScale, 0f, 1f) }
            anim = Anim.SWITCH_COMMIT
            endLabel = "quick switch to ${if (toOlder) "an older" else "a newer"} app (${target.pkg})"
            onSettled = {
                phase = Phase.HOLD
                switchAt = SystemClock.uptimeMillis()
                if (lastFrontPkg == target.pkg && lastFrontAt >= since) hideCards() else awaitForeground(target.pkg, g) { hideCards() }
            }
        } else {
            sOff = Spring(0.3f, 1f).apply { start(offset, vx, 0f) }
            sScale = Spring(0.3f, 1f).apply { start(switchScale, 0f, 1f) }
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

    private fun fadeOutCards(ms: Long, g: Int) {
        val r = root ?: return
        if (gen != g) return
        r.animate().alpha(0f).setDuration(ms).withEndAction { if (gen == g) hideCards() }.start()
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

    /** Brings [pkg] to the front: its task if known, else its launch intent. */
    private fun bringBack(pkg: String?, task: Task?) {
        val s = ShizukuLink.service
        // Only that app's own task: a task of another app here is how the wrong app came to the front.
        val t = task?.takeIf { pkg == null || it.pkg == pkg }
        if (t != null && s != null) {
            val task = t
            front("switch to ${task.pkg}") {
                val start = SystemClock.uptimeMillis()
                val r = try { s.switchToTaskWithOptions(task.id, noAnimation(app)) } catch (e: Throwable) { "ERROR: ${e.message}" }
                AppLog.log("[nav] switch to ${task.pkg} (task ${task.id}): $r (${SystemClock.uptimeMillis() - start} ms)")
            }
        } else if (pkg != null) {
            front("start $pkg") {
                app.packageManager.getLaunchIntentForPackage(pkg)?.let { i ->
                    try { app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), noAnimation(app)) } catch (_: Throwable) { }
                }
            }
        }
    }

    private fun returnToLastApp() {
        val s = ShizukuLink.service ?: return
        front("last app") {
            val target = fg?.takeIf { it.pkg != app.packageName } ?: parseTasks(s.recentTasks(4)).switchable().firstOrNull()
            if (target == null) { AppLog.log("[nav] no recent app to return to"); return@front }
            AppLog.log("[nav] home swipe -> last app ${target.pkg}: ${try { s.switchToTask(target.id) } catch (e: Throwable) { "ERROR: ${e.message}" }}")
        }
    }

    // ================================================================== views

    /** Our recorded picture of the home screen, drawn behind cards, zoomed about the centre. */
    private class PreviewView(ctx: Context) : View(ctx) {
        var picture: Picture? = null
            set(v) { if (field !== v) { field = v; invalidate() } }
        var zoom = 1f
            set(v) { if (field != v) { field = v; invalidate() } }

        override fun onDraw(canvas: Canvas) {
            val p = picture ?: return
            canvas.save()
            canvas.scale(zoom, zoom, width / 2f, height / 2f)
            canvas.drawPicture(p)
            canvas.restore()
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
                val active = phase == Phase.DRAG_HOME || phase == Phase.DRAG_SWITCH || animating || grabbedFull
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
