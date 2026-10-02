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
import android.graphics.drawable.BitmapDrawable
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
 * Our bottom-edge gesture navigation and app launch/close animations. A touchable strip over the system gesture area
 * takes the finger; a full-display, non-touchable card window draws app cards ([CardView]) that follow it.
 *
 * - Up: HOME mode. The app becomes a card that shrinks with the finger (ease-out). Behind it we draw our recorded picture
 *   of the home screen ([HomeBridge.preview]), slightly zoomed, so the app itself stays in front and running until the
 *   gesture commits. Commit: the real home starts behind the picture, springs carry the finger's velocity into the app's
 *   icon while the picture's zoom settles, and the picture gives way to the real home once that has drawn. Cancel: the
 *   card springs back; the app never left, so nothing is relaunched.
 * - Sideways: SWITCH mode, horizontal axis only. The previous app's card slides in beside the current one; on release the
 *   real switch happens behind the cards, which go once the system reports the app in front.
 * - [launchApp]: LAUNCH mode. A card grows out of the tapped icon while the app starts underneath.
 * - A HOME animation in flight can be grabbed and continued with the finger.
 *
 * Windows are TYPE_ACCESSIBILITY_OVERLAY (through [NavAccessibilityService]): Settings and setHideOverlayWindows cannot
 * hide them. Stock gestures are only blocked while that service is connected ([ready]) and our flags are held by the live
 * shell service. Everything here runs on its own UI thread ([nav]), so input, animation and frames never wait for the
 * home screen. Public entry points may be called from any thread.
 */
object GestureNav {
    private lateinit var app: LauncherApp
    private val navThread = HandlerThread("nav-ui", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val nav = Handler(navThread.looper)
    private val io = Executors.newSingleThreadExecutor()
    private val density get() = app.resources.displayMetrics.density
    private fun dp(v: Int) = v * density

    private var a11y: NavAccessibilityService? = null
    private var wm: WindowManager? = null

    /** Our windows can be shown (accessibility service connected). Gesture flags are only set while this is true. */
    @Volatile var ready = false
        private set
    @Volatile private var locked = false

    /** Set by HomeActivity. */
    @Volatile var homeVisible = false

    private class Task(val id: Int, val pkg: String)
    private enum class Mode { NONE, HOME, SWITCH, LAUNCH }

    // Windows (nav thread)
    private var strip: View? = null
    private var root: FrameLayout? = null
    private var backdrop: PreviewView? = null
    private var cur: CardView? = null
    private var prv: CardView? = null
    private var sw = 0f
    private var sh = 0f
    private var deviceRadius = 0f

    // Gesture (nav thread)
    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var animating = false
    private var vt: VelocityTracker? = null
    private var homeRequestedAt = 0L
    private var hiddenIconPkg: String? = null

    // HOME drag model: travel since the card was full size, finger anchor within the card (scaled with it)
    private var travel0 = 0f
    private var lastTravel = 0f
    private var anchorX = 0f
    private var anchorBottom = 0f
    // SWITCH drag model
    private var offset = 0f
    private var switchScale = 1f

    // Data for the gesture in progress, loaded on ACTION_DOWN (io) and handed to nav
    @Volatile private var gestureId = 0
    @Volatile private var fg: Task? = null
    @Volatile private var prev: Task? = null
    private var fgSnapshot: Bitmap? = null
    private var prvSnapshot: Bitmap? = null
    private var fgState = "none"            // waiting | shown | unavailable
    private var fgSnapMs = -1L
    private var prevSnapMs = -1L
    private var dragStartedAt = 0L
    private var cardVisibleAfter = -1L
    private val icons = ConcurrentHashMap<String, Drawable>()

    // Springs (nav thread)
    private var sCx = Spring(0.5f, 0.86f)
    private var sCy = Spring(0.5f, 0.86f)
    private var sW = Spring(0.44f, 0.9f)
    private var sH = Spring(0.44f, 0.9f)
    private var sZoom = Spring(0.5f, 1f)
    private var sOff = Spring(0.35f, 1f)
    private var sScale = Spring(0.35f, 1f)
    private var springStartNs = 0L
    private var onSettled: (() -> Unit)? = null
    private var endLabel = ""
    /** Size the HOME/LAUNCH card is heading for (icon size when closing into an icon, full width when opening/cancelling). */
    private var cardTargetW = 0f
    private var cardIconSize = 0f

    private val stats by lazy { FrameStats() }   // Choreographer of the nav thread: first used there
    private val choreographer by lazy { Choreographer.getInstance() }

    // Last app window the system reported in front (accessibility events)
    @Volatile private var lastFrontPkg: String? = null
    @Volatile private var lastFrontAt = 0L

    private const val HOME_ZOOM = 1.08f

    fun init(app: LauncherApp) { this.app = app }

    /** Shows or removes the strip to match the current state. Any thread. */
    fun update() {
        if (Looper.myLooper() != navThread.looper) { nav.post { update() }; return }
        val want = ready && SystemRestore.gestureFlagsActive && ShizukuLink.service != null && !locked
        if (want && strip == null) addStrip()
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
        lastFrontPkg = pkg
        lastFrontAt = SystemClock.uptimeMillis()
        nav.post {
            val w = waitingFor ?: return@post
            if (w.first == pkg) { waitingFor = null; nav.removeCallbacks(waitTimeout); nav.postDelayed(w.second, 16) }
        }
    }

    private fun reapplyFlags() {
        val s = ShizukuLink.service ?: return
        io.execute { SystemRestore.applyFlags(app, s) }
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

    // ------------------------------------------------------------------ windows (nav thread)

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
        stripView.setOnTouchListener { _, e -> onTouch(e) }
        val lp = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, h, touchable = true).apply {
            gravity = Gravity.BOTTOM
            title = "LauncherGestureStrip"
        }
        try {
            wm.addView(stripView, lp)
            strip = stripView
            AppLog.log("[nav] gesture strip on (${h}px high, own UI thread)")
        } catch (t: Throwable) {
            AppLog.log("[nav] strip addView FAILED: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun removeAll() {
        animating = false
        dragging = false
        removeCards()
        strip?.let { try { wm?.removeView(it) } catch (_: Throwable) { } }
        strip = null
        AppLog.log("[nav] gesture strip off")
    }

    private fun removeCards() {
        root?.let { try { wm?.removeView(it) } catch (_: Throwable) { } }
        root = null
        backdrop = null
        cur = null
        prv = null
        mode = Mode.NONE
        hiddenIconPkg?.let { HomeBridge.setIconHidden(it, false) }
        hiddenIconPkg = null
    }

    @Suppress("DEPRECATION")
    private fun addCardWindow(withPreview: Boolean): Boolean {
        val ctx = a11y ?: return false
        val wm = wm ?: return false
        if (root != null) removeCards()
        // Re-read the display every time so rotation never leaves the cards at the old size.
        val dm = android.util.DisplayMetrics()
        val display = wm.defaultDisplay
        display.getRealMetrics(dm)
        sw = dm.widthPixels.toFloat()
        sh = dm.heightPixels.toFloat()
        deviceRadius = if (Build.VERSION.SDK_INT >= 31) {
            display.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: dp(32)
        } else dp(32)
        val r = FrameLayout(ctx).apply { alpha = 0f }
        val picture = if (withPreview) HomeBridge.preview else null
        if (picture != null) {
            backdrop = PreviewView(ctx, picture).also { r.addView(it, FrameLayout.LayoutParams(sw.toInt(), sh.toInt())) }
        }
        val c = CardView(ctx)
        r.addView(c, FrameLayout.LayoutParams(sw.toInt(), sh.toInt()))
        c.setFrame(sw / 2, sh / 2, sw, sh, deviceRadius)
        // Exact full-display size from the first frame, drawn into the cutout too (the probe saw resizing as a stretch).
        val lp = overlayParams(sw.toInt(), sh.toInt(), touchable = false).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "LauncherCards"
        }
        return try {
            wm.addView(r, lp)
            root = r
            cur = c
            true
        } catch (t: Throwable) {
            AppLog.log("[nav] card addView FAILED: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun systemDimen(name: String): Int {
        val id = app.resources.getIdentifier(name, "dimen", "android")
        return if (id != 0) app.resources.getDimensionPixelSize(id) else 0
    }

    // ------------------------------------------------------------------ touch (nav thread)

    private fun onTouch(e: MotionEvent): Boolean {
        val raw = MotionEvent.obtain(e).apply { setLocation(e.rawX, e.rawY) }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vt?.recycle()
                vt = VelocityTracker.obtain().also { it.addMovement(raw) }
                if (animating || mode == Mode.LAUNCH) {
                    if (mode == Mode.HOME && animating) grab(e.rawX, e.rawY) else { raw.recycle(); return true }
                } else {
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                    mode = Mode.NONE
                    stats.reset()
                    prefetch()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(raw)
                if (!dragging && !animating && mode == Mode.NONE && !homeVisible) {
                    val dx = e.rawX - downX
                    val dy = downY - e.rawY
                    if (dy > dp(10) && dy > abs(dx)) beginHome()
                    else if (abs(dx) > dp(14) && abs(dx) > abs(dy) * 1.2f) beginSwitch()
                }
                if (dragging) {
                    if (mode == Mode.HOME) dragHome(e.rawX, e.rawY) else if (mode == Mode.SWITCH) dragSwitch(e.rawX)
                    stats.touchEvent(e.eventTime)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(raw)
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                val vy = vt?.yVelocity ?: 0f
                val up = e.actionMasked == MotionEvent.ACTION_UP
                if (dragging) {
                    dragging = false
                    if (mode == Mode.HOME) releaseHome(up, vx, vy) else if (mode == Mode.SWITCH) releaseSwitch(up, vx)
                } else if (homeVisible && up && abs(e.rawX - downX) > dp(40)) {
                    // On home a sideways swipe returns to the last app (recents comes later).
                    returnToLastApp()
                }
            }
        }
        raw.recycle()
        return true
    }

    /** Foreground and previous task, their icons, a fresh snapshot of the foreground app and a cached one of the previous. */
    private fun prefetch() {
        val s = ShizukuLink.service ?: return
        val id = ++gestureId
        val onHome = homeVisible
        fg = null
        prev = null
        fgSnapshot = null
        prvSnapshot = null
        fgState = if (onHome) "none" else "waiting"
        fgSnapMs = -1
        prevSnapMs = -1
        io.execute {
            try {
                val tasks = parseTasks(s.recentTasks(3))
                if (id != gestureId || tasks.isEmpty()) return@execute
                fg = tasks[0]
                prev = tasks.getOrNull(1)
                tasks.take(2).forEach { iconFor(it.pkg) }
                if (onHome) return@execute
                var t = SystemClock.uptimeMillis()
                val fresh = s.taskSnapshot(tasks[0].id, true)
                val fMs = SystemClock.uptimeMillis() - t
                nav.post { if (id == gestureId) onFgSnapshot(fresh, fMs) }
                val p = tasks.getOrNull(1) ?: return@execute
                t = SystemClock.uptimeMillis()
                val cached = s.taskSnapshot(p.id, false)
                val pMs = SystemClock.uptimeMillis() - t
                nav.post { if (id == gestureId) onPrevSnapshot(cached, pMs) }
            } catch (t: Throwable) {
                AppLog.log("[nav] prefetch failed: ${t.javaClass.simpleName}: ${t.message}")
                nav.post { if (id == gestureId) onFgSnapshot(null, -1) }
            }
        }
    }

    private fun parseTasks(lines: Array<String>) = lines.mapNotNull { line ->
        val parts = line.split(' ')
        parts.getOrNull(0)?.toIntOrNull()?.let { Task(it, parts.getOrElse(1) { "?" }) }
    }

    private fun iconFor(pkg: String): Drawable? = icons[pkg] ?: try {
        app.packageManager.getApplicationIcon(pkg).also { icons[pkg] = it }
    } catch (_: Throwable) { null }

    private fun onFgSnapshot(b: Bitmap?, ms: Long) {
        fgSnapMs = ms
        fgSnapshot = b
        fgState = if (b != null) "shown" else "unavailable"   // unavailable: secure app etc. -> plain card
        if (mode == Mode.HOME || mode == Mode.SWITCH) {
            cur?.let { c -> c.snapshot = b; c.icon = fg?.let { icons[it.pkg] } }
            revealCards()
        }
    }

    private fun onPrevSnapshot(b: Bitmap?, ms: Long) {
        prevSnapMs = ms
        prvSnapshot = b
        prv?.snapshot = b
    }

    /** The card window stays invisible until the card has something real to show, so no placeholder ever flashes. */
    private fun revealCards() {
        val r = root ?: return
        if (fgState == "waiting" || r.alpha == 1f) return
        r.alpha = 1f
        cardVisibleAfter = SystemClock.uptimeMillis() - dragStartedAt
    }

    // ------------------------------------------------------------------ HOME mode

    private fun beginHome() {
        if (!addCardWindow(withPreview = true)) return
        mode = Mode.HOME
        dragging = true
        homeRequestedAt = 0L
        dragStartedAt = SystemClock.uptimeMillis()
        cardVisibleAfter = -1
        cur?.let { c -> c.snapshot = fgSnapshot; c.icon = fg?.let { icons[it.pkg] } }
        backdrop?.zoom = HOME_ZOOM
        if (backdrop == null) root?.setBackgroundColor(0xFF101418.toInt())   // no picture of home yet
        travel0 = 0f
        lastTravel = 0f
        anchorX = sw / 2 - downX
        anchorBottom = sh - downY
        revealCards()
        stats.start()
    }

    // Card scale for upward travel: ease-out (shrinks quickly first, then slower), never below MIN_SCALE.
    private fun homeScale(travel: Float): Float = 1f - SCALE_RANGE * (1f - exp(-max(0f, travel) / (sh * SCALE_LENGTH)))

    private fun travelForScale(s: Float): Float {
        val f = ((1f - s) / SCALE_RANGE).coerceIn(0f, 0.98f)
        return -ln(1f - f) * sh * SCALE_LENGTH
    }

    private fun dScaleDTravel(travel: Float): Float = -SCALE_RANGE / (sh * SCALE_LENGTH) * exp(-max(0f, travel) / (sh * SCALE_LENGTH))

    private const val SCALE_RANGE = 0.62f     // smallest card while dragging = 38 % of the screen
    private const val SCALE_LENGTH = 0.28f    // travel (in screen heights) for most of the shrink

    private fun dragHome(x: Float, y: Float) {
        val c = cur ?: return
        lastTravel = travel0 + (downY - y)
        val s = homeScale(lastTravel)
        val w = sw * s
        val h = sh * s
        // The finger keeps its place on the card as it shrinks, so the card visibly comes away from the top edge.
        val cx = x + anchorX * s
        val cy = y + anchorBottom * s - h / 2
        c.setFrame(cx, cy, w, h, deviceRadius)
        c.iconMix = 0f
    }

    /** Finger lands on a HOME card in flight: continue from where the card is, no jump. */
    private fun grab(x: Float, y: Float) {
        val c = cur ?: return
        animating = false
        val s = c.w / sw
        travel0 = travelForScale(s)
        lastTravel = travel0
        anchorX = (c.cx - x) / s
        anchorBottom = (c.cy + c.h / 2 - y) / s
        downX = x
        downY = y
        dragging = true
        mode = Mode.HOME
        backdrop?.zoom = HOME_ZOOM
        stats.reset()
        stats.start()
        AppLog.log("[nav] grabbed mid-animation at scale ${"%.2f".format(s)}")
    }

    private fun releaseHome(up: Boolean, vx: Float, vy: Float) {
        val c = cur ?: run { removeCards(); return }
        val upSpeed = -vy
        val commit = up && ((upSpeed > 350f && lastTravel > dp(30)) || (lastTravel > sh * 0.2f && upSpeed > -250f))
        // Card velocities from the finger: the centre moves with it, the size changes with upward travel.
        val ds = dScaleDTravel(lastTravel) * upSpeed
        val vW = sw * ds
        val vH = sh * ds
        if (commit) {
            if (homeRequestedAt == 0L) startHome()
            val pkg = fg?.pkg
            val target = pkg?.let { HomeBridge.iconRect(it) }
            val size = target?.width() ?: dp(64)
            val tx = target?.centerX() ?: sw / 2
            val ty = target?.centerY() ?: sh / 2
            if (target != null && pkg != null) { HomeBridge.setIconHidden(pkg, true); hiddenIconPkg = pkg }
            cardTargetW = size
            cardIconSize = size
            sCx = Spring(0.5f, 0.86f).apply { start(c.cx, vx, tx) }
            sCy = Spring(0.5f, 0.86f).apply { start(c.cy, vy, ty) }
            sW = Spring(0.44f, 0.9f).apply { start(c.w, vW, size) }
            sH = Spring(0.44f, 0.9f).apply { start(c.h, vH, size) }
            sZoom = Spring(0.5f, 1f).apply { start(backdrop?.zoom ?: 1f, 0f, 1f) }
            endLabel = if (target != null) "home (into icon)" else "home (to centre; app not on the home screen)"
            onSettled = {
                // The picture of home is on top of the real one: swap only once the real home has drawn.
                whenHomeDrawn { if (target != null) removeCards() else fadeOutCards(120) }
            }
        } else {
            cardTargetW = sw
            cardIconSize = 0f
            sCx = Spring(0.38f, 1f).apply { start(c.cx, vx, sw / 2) }
            sCy = Spring(0.38f, 1f).apply { start(c.cy, vy, sh / 2) }
            sW = Spring(0.38f, 1f).apply { start(c.w, vW, sw) }
            sH = Spring(0.38f, 1f).apply { start(c.h, vH, sh) }
            sZoom = Spring(0.38f, 1f).apply { start(backdrop?.zoom ?: 1f, 0f, HOME_ZOOM) }
            endLabel = "back to the app"
            // The app is still in front (home was never started), so the card can simply go.
            onSettled = { removeCards() }
        }
        startSprings()
    }

    private fun startHome() {
        homeRequestedAt = SystemClock.uptimeMillis()
        homeVisible = false
        try {
            app.startActivity(Intent(app, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            AppLog.log("[nav] going home from the app failed (${t.javaClass.simpleName}); using the shell")
            ShizukuLink.service?.let { s -> io.execute { s.runDetached("am start -a android.intent.action.MAIN -c android.intent.category.HOME") } }
        }
    }

    /** Runs [then] once home has drawn a frame after it was requested (or after 700 ms). */
    private fun whenHomeDrawn(then: () -> Unit) {
        val deadline = SystemClock.uptimeMillis() + 700
        fun check() {
            if (root == null) return
            if (HomeBridge.homeDrawnAt >= homeRequestedAt || SystemClock.uptimeMillis() > deadline) {
                if (HomeBridge.homeDrawnAt < homeRequestedAt) AppLog.log("[nav] home did not report a drawn frame within 700 ms")
                then()
            } else nav.postDelayed({ check() }, 8)
        }
        check()
    }

    /** Shared by HOME and LAUNCH: frame, corner radius and icon crossfade from the four springs. */
    private fun applyCardSprings(t: Double): Boolean {
        val c = cur ?: return true
        val w = sW.value(t)
        val h = sH.value(t)
        val radius: Float
        if (cardIconSize > 0f) {
            // Towards or away from an icon: corners go between the display's and the icon's, snapshot <-> icon crossfade.
            val icon = cardIconSize
            val k = ((w - icon) / (sw * 0.6f - icon)).coerceIn(0f, 1f)
            val iconRadius = icon * 0.23f
            radius = iconRadius + (deviceRadius - iconRadius) * k
            c.iconMix = 1f - ((w - icon) / (icon * 1.5f)).coerceIn(0f, 1f)
        } else {
            radius = deviceRadius
            c.iconMix = 0f
        }
        c.setFrame(sCx.value(t), sCy.value(t), w, h, radius)
        backdrop?.zoom = sZoom.value(t)
        return sCx.settled(t) && sCy.settled(t) && sW.settled(t) && sH.settled(t)
    }

    // ------------------------------------------------------------------ LAUNCH mode

    /**
     * Opening an app from [iconRect] on home: a card grows out of the icon to full screen while the app starts underneath
     * (the caller starts it). The card shows the app's last snapshot if it has one, else its icon on a plain card, and goes
     * once the app's window is in front. Main thread; returns at once.
     */
    fun launchApp(pkg: String, iconRect: RectF, icon: Drawable?) {
        if (!ready) return
        val since = SystemClock.uptimeMillis()
        val iconCopy = icon?.constantState?.newDrawable()?.mutate() ?: icon
        nav.post {
            if (dragging || animating) return@post
            if (!addCardWindow(withPreview = false)) return@post
            val c = cur ?: return@post
            mode = Mode.LAUNCH
            dragStartedAt = since
            c.icon = iconCopy
            c.placeholderColor = iconCopy?.let { averageColor(it) } ?: 0xFF2A2F3A.toInt()
            c.setFrame(iconRect.centerX(), iconRect.centerY(), iconRect.width(), iconRect.height(), iconRect.width() * 0.23f)
            c.iconMix = 1f
            root?.alpha = 1f
            HomeBridge.setIconHidden(pkg, true)
            hiddenIconPkg = pkg
            cardTargetW = sw
            cardIconSize = iconRect.width()
            sCx = Spring(0.42f, 0.92f).apply { start(iconRect.centerX(), 0f, sw / 2) }
            sCy = Spring(0.42f, 0.92f).apply { start(iconRect.centerY(), 0f, sh / 2) }
            sW = Spring(0.42f, 0.92f).apply { start(iconRect.width(), 0f, sw) }
            sH = Spring(0.42f, 0.92f).apply { start(iconRect.height(), 0f, sh) }
            endLabel = "launch $pkg"
            onSettled = {
                if (lastFrontPkg == pkg && lastFrontAt >= since) fadeOutCards(90)
                else awaitForeground(pkg) { fadeOutCards(90) }
            }
            stats.reset()
            startSprings()
            stats.start()
            // A warm app has a snapshot of how it will look: show it as the card grows.
            val s = ShizukuLink.service ?: return@post
            io.execute {
                val task = try { parseTasks(s.recentTasks(12)).firstOrNull { it.pkg == pkg } } catch (_: Throwable) { null } ?: return@execute
                val b = try { s.taskSnapshot(task.id, false) } catch (_: Throwable) { null } ?: return@execute
                nav.post { if (mode == Mode.LAUNCH && cur === c) c.snapshot = b }
            }
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

    // ------------------------------------------------------------------ SWITCH mode

    private fun beginSwitch() {
        if (prev == null && fg == null) return
        if (!addCardWindow(withPreview = false)) return
        val ctx = a11y ?: return
        mode = Mode.SWITCH
        dragging = true
        dragStartedAt = SystemClock.uptimeMillis()
        cardVisibleAfter = -1
        offset = 0f
        switchScale = 1f
        root?.setBackgroundColor(0xFF000000.toInt())   // what is revealed beside the cards
        cur?.let { c -> c.snapshot = fgSnapshot; c.icon = fg?.let { icons[it.pkg] } }
        prev?.let { p ->
            prv = CardView(ctx).also { pc ->
                pc.snapshot = prvSnapshot
                pc.icon = icons[p.pkg]
                root?.addView(pc, 0, FrameLayout.LayoutParams(sw.toInt(), sh.toInt()))
            }
        }
        layoutSwitch(0f, 1f)
        revealCards()
        stats.start()
    }

    private fun dragSwitch(x: Float) {
        val dx = x - downX
        // Only towards the previous app (finger moving right); the other way is a rubber band.
        offset = if (prev != null && dx > 0) dx else dx * 0.25f
        switchScale = 1f - 0.06f * (abs(offset) / dp(60)).coerceIn(0f, 1f)
        layoutSwitch(offset, switchScale)
    }

    private fun gap() = dp(16)

    private fun layoutSwitch(off: Float, s: Float) {
        val w = sw * s
        val h = sh * s
        val cx = sw / 2 + off
        cur?.setFrame(cx, sh / 2, w, h, deviceRadius)
        prv?.setFrame(cx - w - gap(), sh / 2, w, h, deviceRadius)
    }

    private fun releaseSwitch(up: Boolean, vx: Float) {
        val p = prev
        val commit = up && p != null && ((offset > sw * 0.3f && vx > -300f) || (vx > 600f && offset > dp(20)))
        if (commit && p != null) {
            switchTo(p)
            val since = SystemClock.uptimeMillis()
            sOff = Spring(0.35f, 1f).apply { start(offset, vx, sw + gap()) }
            sScale = Spring(0.35f, 1f).apply { start(switchScale, 0f, 1f) }
            endLabel = "quick switch"
            onSettled = {
                if (lastFrontPkg == p.pkg && lastFrontAt >= since) removeCards() else awaitForeground(p.pkg) { removeCards() }
            }
        } else {
            sOff = Spring(0.3f, 1f).apply { start(offset, vx, 0f) }
            sScale = Spring(0.3f, 1f).apply { start(switchScale, 0f, 1f) }
            endLabel = "switch cancelled"
            onSettled = { removeCards() }
        }
        startSprings()
    }

    private fun applySwitchSprings(t: Double): Boolean {
        layoutSwitch(sOff.value(t), sScale.value(t))
        return sOff.settled(t) && sScale.settled(t, 0.001f)
    }

    // ------------------------------------------------------------------ animation driver (nav thread)

    private fun startSprings() {
        animating = true
        springStartNs = System.nanoTime()
        choreographer.postFrameCallback(springFrame)
    }

    private val springFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!animating) return
            val t = max(0L, frameTimeNanos - springStartNs) / 1e9
            val done = if (mode == Mode.SWITCH) applySwitchSprings(t) else applyCardSprings(t)
            if (done) {
                animating = false
                AppLog.log(
                    stats.report("[nav] $endLabel") +
                        "\n  snapshot ${fgState} after ${fgSnapMs} ms (previous app: ${prevSnapMs} ms); " +
                        "card visible ${cardVisibleAfter} ms after the drag began; animation ${"%.0f".format(t * 1000)} ms"
                )
                val then = onSettled
                onSettled = null
                then?.invoke()
            } else {
                choreographer.postFrameCallback(this)
            }
        }
    }

    private fun fadeOutCards(ms: Long) {
        val r = root ?: return
        r.animate().alpha(0f).setDuration(ms).withEndAction { if (root === r) removeCards() }.start()
    }

    // ------------------------------------------------------------------ task switching

    private var waitingFor: Pair<String, Runnable>? = null
    private val waitTimeout = Runnable { val w = waitingFor; waitingFor = null; w?.second?.run() }

    /** Runs [then] once a window of [pkg] is in front (accessibility event), or after 500 ms at the latest. */
    private fun awaitForeground(pkg: String, then: () -> Unit) {
        waitingFor = pkg to Runnable(then)
        nav.removeCallbacks(waitTimeout)
        nav.postDelayed(waitTimeout, 500)
    }

    private fun switchTo(t: Task) {
        val s = ShizukuLink.service ?: return
        io.execute {
            val start = SystemClock.uptimeMillis()
            val r = try { s.switchToTask(t.id) } catch (e: Throwable) { "ERROR: ${e.message}" }
            AppLog.log("[nav] switch to ${t.pkg} (task ${t.id}): $r (${SystemClock.uptimeMillis() - start} ms)")
        }
    }

    private fun returnToLastApp() {
        val s = ShizukuLink.service ?: return
        io.execute {
            val target = fg ?: parseTasks(s.recentTasks(1)).firstOrNull()
            if (target == null) { AppLog.log("[nav] no recent app to return to"); return@execute }
            AppLog.log("[nav] home swipe -> last app ${target.pkg}: ${try { s.switchToTask(target.id) } catch (e: Throwable) { "ERROR: ${e.message}" }}")
        }
    }

    /** Our recorded picture of the home screen, drawn behind a closing card, zoomed about the centre. */
    private class PreviewView(ctx: Context, private val picture: Picture) : View(ctx) {
        var zoom = 1f
            set(v) { if (field != v) { field = v; invalidate() } }

        override fun onDraw(canvas: Canvas) {
            canvas.save()
            canvas.scale(zoom, zoom, width / 2f, height / 2f)
            canvas.drawPicture(picture)
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
                if (running && (dragging || animating)) choreographer.postFrameCallback(this) else running = false
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
