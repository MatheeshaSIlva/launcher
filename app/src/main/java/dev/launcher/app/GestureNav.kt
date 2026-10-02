package dev.launcher.app

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
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
import kotlin.math.max
import kotlin.math.min

/**
 * Our bottom-edge gesture navigation. A touchable strip over the system gesture area takes the finger; a full-display,
 * non-touchable card window draws app cards ([CardView]) that follow it.
 *
 * - Up: HOME mode. The app becomes a card that shrinks with the finger; the real home screen is brought up behind it as
 *   soon as the card can be shown. On release, springs carry the finger's velocity into the app's icon on home (or the
 *   screen centre), crossfading to the icon. Released early or pulled back: the card springs back and the app returns.
 * - Sideways: SWITCH mode, horizontal axis only. The previous app's card slides in beside the current one; on release the
 *   real switch happens behind the cards, which go once the system reports the app in front.
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
    private enum class Mode { NONE, HOME, SWITCH }

    // Windows (nav thread)
    private var strip: View? = null
    private var root: FrameLayout? = null
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
    private var homeLaunched = false
    private var hiddenIconPkg: String? = null

    // HOME drag model: travel since the card was full size, finger anchor within the card (scaled with it)
    private var travel0 = 0f
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
    private var fgState = "none"            // waiting | shown | unavailable
    private var fgSnapMs = -1L
    private var prevSnapMs = -1L
    private var dragStartedAt = 0L
    private var cardVisibleAfter = -1L
    private val icons = ConcurrentHashMap<String, Drawable>()

    // Springs (nav thread)
    private var sCx = Spring(0.42f, 0.9f)
    private var sCy = Spring(0.42f, 0.9f)
    private var sW = Spring(0.42f, 0.9f)
    private var sH = Spring(0.42f, 0.9f)
    private var sOff = Spring(0.35f, 1f)
    private var sScale = Spring(0.35f, 1f)
    private var springStartNs = 0L
    private var onSettled: (() -> Unit)? = null
    private var endLabel = ""

    private val stats by lazy { FrameStats() }   // Choreographer of the nav thread: first used there
    private val choreographer by lazy { Choreographer.getInstance() }

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
        cur = null
        prv = null
        mode = Mode.NONE
        hiddenIconPkg?.let { HomeBridge.setIconHidden(it, false) }
        hiddenIconPkg = null
    }

    @Suppress("DEPRECATION")
    private fun addCardWindow(): Boolean {
        val ctx = a11y ?: return false
        val wm = wm ?: return false
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
                if (animating) {
                    if (mode == Mode.HOME) grab(e.rawX, e.rawY) else { raw.recycle(); return true }
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
                if (!dragging && !animating && !homeVisible) {
                    val dx = e.rawX - downX
                    val dy = downY - e.rawY
                    if (dy > dp(10) && dy > abs(dx)) beginHome()
                    else if (abs(dx) > dp(14) && abs(dx) > abs(dy) * 1.2f) beginSwitch()
                }
                if (dragging) {
                    if (mode == Mode.HOME) dragHome(e.rawX, e.rawY) else dragSwitch(e.rawX)
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
                    if (mode == Mode.HOME) releaseHome(up, vx, vy) else releaseSwitch(up, vx)
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
                val tasks = s.recentTasks(3).mapNotNull { line ->
                    val parts = line.split(' ')
                    parts.getOrNull(0)?.toIntOrNull()?.let { Task(it, parts.getOrElse(1) { "?" }) }
                }
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

    private fun iconFor(pkg: String): Drawable? = icons[pkg] ?: try {
        app.packageManager.getApplicationIcon(pkg).also { icons[pkg] = it }
    } catch (_: Throwable) { null }

    private fun onFgSnapshot(b: Bitmap?, ms: Long) {
        fgSnapMs = ms
        fgSnapshot = b
        fgState = if (b != null) "shown" else "unavailable"   // unavailable: secure app etc. -> plain card
        cur?.let { c -> c.snapshot = b; c.icon = fg?.let { icons[it.pkg] } }
        if (mode != Mode.NONE) revealCards()
    }

    private fun onPrevSnapshot(b: Bitmap?, ms: Long) {
        prevSnapMs = ms
        prv?.snapshot = b
        prvSnapshot = b
    }
    private var prvSnapshot: Bitmap? = null

    /** The card window stays invisible until the card has something real to show, so no placeholder ever flashes. */
    private fun revealCards() {
        val r = root ?: return
        if (fgState == "waiting" || r.alpha == 1f) return
        r.alpha = 1f
        cardVisibleAfter = SystemClock.uptimeMillis() - dragStartedAt
        // The card now covers the app: bring the real home screen up behind it (HOME), so it is there when the card goes.
        if (mode == Mode.HOME && !homeLaunched) launchHomeBehind()
    }

    // ------------------------------------------------------------------ HOME mode

    private fun beginHome() {
        if (!addCardWindow()) return
        mode = Mode.HOME
        dragging = true
        homeLaunched = false
        dragStartedAt = SystemClock.uptimeMillis()
        cardVisibleAfter = -1
        cur?.let { c -> c.snapshot = fgSnapshot; c.icon = fg?.let { icons[it.pkg] } }
        travel0 = 0f
        lastTravel = 0f
        anchorX = sw / 2 - downX
        anchorBottom = sh - downY
        revealCards()
        stats.start()
    }

    /** Card scale for upward travel: linear at first, then a soft limit (rubber band) so it never vanishes under the finger. */
    private fun homeScale(travel: Float): Float {
        val p = max(0f, travel) / (sh * 0.6f)
        return if (p <= 1f) 1f - 0.55f * p else 0.45f - 0.2f * (1f - 1f / (1f + (p - 1f) * 2f))
    }

    private fun travelForScale(s: Float): Float =
        if (s >= 0.45f) (1f - s) / 0.55f * sh * 0.6f else sh * 0.6f

    private var lastTravel = 0f

    private fun dragHome(x: Float, y: Float) {
        val c = cur ?: return
        lastTravel = travel0 + (downY - y)
        val s = homeScale(lastTravel)
        val w = sw * s
        val h = sh * s
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
        anchorX = (c.cx - x) / s
        anchorBottom = (c.cy + c.h / 2 - y) / s
        downX = x
        downY = y
        dragging = true
        mode = Mode.HOME
        stats.reset()
        stats.start()
        AppLog.log("[nav] grabbed mid-animation at scale ${"%.2f".format(s)}")
    }

    private fun releaseHome(up: Boolean, vx: Float, vy: Float) {
        val c = cur ?: run { removeCards(); return }
        val travel = lastTravel
        val upSpeed = -vy
        val commit = up && ((upSpeed > 350f && travel > dp(30)) || (travel > sh * 0.22f && upSpeed > -250f))
        // Card velocities from the finger: the centre moves with it, the size changes with upward travel.
        val s = c.w / sw
        val dsdTravel = if (s > 0.45f) -0.55f / (sh * 0.6f) else 0f
        val vW = sw * dsdTravel * upSpeed
        val vH = sh * dsdTravel * upSpeed
        if (commit) {
            if (!homeLaunched) launchHomeBehind()
            val pkg = fg?.pkg
            val target = pkg?.let { HomeBridge.iconRect(it) }
            val size = target?.width() ?: dp(64)
            val tx = target?.centerX() ?: sw / 2
            val ty = target?.centerY() ?: sh / 2
            if (target != null && pkg != null) { HomeBridge.setIconHidden(pkg, true); hiddenIconPkg = pkg }
            HomeBridge.animateReturn()
            sCx = Spring(0.42f, 0.9f).apply { start(c.cx, vx, tx) }
            sCy = Spring(0.42f, 0.9f).apply { start(c.cy, vy, ty) }
            sW = Spring(0.42f, 0.9f).apply { start(c.w, vW, size) }
            sH = Spring(0.42f, 0.9f).apply { start(c.h, vH, size) }
            endLabel = if (target != null) "home (into icon)" else "home (to centre; app not on the home screen)"
            onSettled = {
                if (target != null) removeCards() else fadeOutCards(120)
            }
        } else {
            sCx = Spring(0.35f, 1f).apply { start(c.cx, vx, sw / 2) }
            sCy = Spring(0.35f, 1f).apply { start(c.cy, vy, sh / 2) }
            sW = Spring(0.35f, 1f).apply { start(c.w, vW, sw) }
            sH = Spring(0.35f, 1f).apply { start(c.h, vH, sh) }
            endLabel = "back to the app"
            onSettled = {
                if (homeLaunched) {
                    // Home is in front behind the card: put the app back, then let the card go once it is there.
                    HomeBridge.cancelReturn()
                    fg?.let { t -> switchTo(t) { removeCards() } } ?: removeCards()
                } else removeCards()
            }
        }
        startSprings()
    }

    private fun applyHomeSprings(t: Double): Boolean {
        val c = cur ?: return true
        val w = sW.value(t)
        val h = sH.value(t)
        val target = sW.target
        // Corners go from the display's to the icon's; the snapshot crossfades into the icon over the last stretch.
        val k = ((w - target) / (sw * 0.6f - target)).coerceIn(0f, 1f)
        val iconRadius = target * 0.23f
        c.setFrame(sCx.value(t), sCy.value(t), w, h, iconRadius + (deviceRadius - iconRadius) * k)
        c.iconMix = if (target < sw * 0.5f) 1f - ((w - target) / (target * 1.5f)).coerceIn(0f, 1f) else 0f
        return sCx.settled(t) && sCy.settled(t) && sW.settled(t) && sH.settled(t)
    }

    private fun launchHomeBehind() {
        homeLaunched = true
        homeVisible = false
        HomeBridge.returnPending = true
        try {
            app.startActivity(Intent(app, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            AppLog.log("[nav] going home from the app failed (${t.javaClass.simpleName}); using the shell")
            ShizukuLink.service?.let { s -> io.execute { s.runDetached("am start -a android.intent.action.MAIN -c android.intent.category.HOME") } }
        }
    }

    // ------------------------------------------------------------------ SWITCH mode

    private fun beginSwitch() {
        if (prev == null && fg == null) return
        if (!addCardWindow()) return
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
            switchTo(p, waitForIt = false) {}
            sOff = Spring(0.35f, 1f).apply { start(offset, vx, sw + gap()) }
            sScale = Spring(0.35f, 1f).apply { start(switchScale, 0f, 1f) }
            endLabel = "quick switch"
            onSettled = { awaitForeground(p.pkg) { removeCards() } }
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
            val done = if (mode == Mode.SWITCH) applySwitchSprings(t) else applyHomeSprings(t)
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
    private val waitTimeout = Runnable { waitingFor?.second?.run(); waitingFor = null }

    /** Runs [then] once a window of [pkg] is in front (accessibility event), or after 400 ms at the latest. */
    private fun awaitForeground(pkg: String, then: () -> Unit) {
        waitingFor = pkg to Runnable(then)
        nav.removeCallbacks(waitTimeout)
        nav.postDelayed(waitTimeout, 400)
    }

    private fun switchTo(t: Task, waitForIt: Boolean = true, then: () -> Unit) {
        val s = ShizukuLink.service ?: run { then(); return }
        if (waitForIt) awaitForeground(t.pkg, then)
        io.execute {
            val start = SystemClock.uptimeMillis()
            val r = try { s.switchToTask(t.id) } catch (e: Throwable) { "ERROR: ${e.message}" }
            AppLog.log("[nav] switch to ${t.pkg} (task ${t.id}): $r (${SystemClock.uptimeMillis() - start} ms)")
        }
        if (!waitForIt) then()
    }

    private fun returnToLastApp() {
        val s = ShizukuLink.service ?: return
        io.execute {
            val target = fg ?: s.recentTasks(1).firstOrNull()?.split(' ')?.let { p -> p[0].toIntOrNull()?.let { Task(it, p.getOrElse(1) { "?" }) } }
            if (target == null) { AppLog.log("[nav] no recent app to return to"); return@execute }
            AppLog.log("[nav] home swipe -> last app ${target.pkg}: ${try { s.switchToTask(target.id) } catch (e: Throwable) { "ERROR: ${e.message}" }}")
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
