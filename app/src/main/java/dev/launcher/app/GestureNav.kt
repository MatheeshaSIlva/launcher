package dev.launcher.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Our bottom-edge gesture navigation (ported from the probe's GestureStrip). A touchable overlay strip over the system
 * gesture area takes the finger; a full-display, non-touchable card window draws the app as a card that follows it.
 * Flick up = home, sideways = previous app, otherwise the card springs back. Any animation can be grabbed mid-flight.
 *
 * Windows are TYPE_ACCESSIBILITY_OVERLAY (through [NavAccessibilityService]), which Settings and setHideOverlayWindows
 * cannot hide. Stock gestures are only blocked while that service is connected ([ready]).
 * Shown only while the stock home/recents gestures are blocked by flags held by our live service
 * ([SystemRestore.gestureFlagsActive]); if the service dies, the system restores stock gestures and the strip goes away.
 * Main thread only, except [update].
 */
object GestureNav {
    private lateinit var app: LauncherApp
    /** The accessibility service's WindowManager: only it can add TYPE_ACCESSIBILITY_OVERLAY windows. */
    private var a11y: NavAccessibilityService? = null
    private var wm: WindowManager? = null

    /** Our windows can be shown (accessibility service connected). Gesture flags are only set while this is true. */
    @Volatile var ready = false
        private set
    private var locked = false
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val interp = PathInterpolator(0.2f, 0f, 0f, 1f)   // emphasized decelerate
    private val density get() = app.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    /** Set by HomeActivity: a swipe on the home screen has no app card to move. */
    @Volatile var homeVisible = false
    private var homeShownAt = 0L

    private var strip: View? = null
    private var cardRoot: FrameLayout? = null
    private var card: ImageView? = null
    private var screenW = 0
    private var screenH = 0
    private var radius = 0f

    // Gesture state
    private var startX = 0f
    private var startY = 0f
    private var lastDx = 0f
    private var lastDy = 0f
    private var lastT = 0L
    private var vy = 0f                  // px per ms, upward positive
    private var dragging = false
    private var sideMode = false
    private var animating = false
    private var anim: ValueAnimator? = null

    // Tasks and snapshots for the gesture in progress (filled on ACTION_DOWN, off the main thread)
    @Volatile private var gestureId = 0
    @Volatile private var fgTask = 0
    @Volatile private var prevTask = 0
    private var cachedMs = -1L
    private var freshMs = -1L
    private var shownSnapshot = "none"

    private val stats by lazy { FrameStats() }   // Choreographer: first used on the main thread

    fun init(app: LauncherApp) { this.app = app }

    /** Shows or removes the strip to match the current state. Safe from any thread. */
    fun update() {
        if (Looper.myLooper() != Looper.getMainLooper()) { ui.post { update() }; return }
        val want = ready && SystemRestore.gestureFlagsActive && ShizukuLink.service != null && !locked
        if (want && strip == null) addStrip()
        if (!want && strip != null) removeAll()
    }

    /** NavAccessibilityService connected: windows can be added. Re-applies flags, which then shows the strip. */
    fun attach(service: NavAccessibilityService) {
        if (a11y === service) return
        if (strip != null) removeAll()
        a11y = service
        wm = service.getSystemService(WindowManager::class.java)
        ready = true
        locked = app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        app.registerReceiver(screenReceiver, f)
        reapplyFlags()
    }

    /** Service gone: our windows are invalid and stock gestures must come back at once. */
    fun detach(service: NavAccessibilityService) {
        if (a11y !== service) return
        ready = false
        removeAll()
        try { app.unregisterReceiver(screenReceiver) } catch (_: Throwable) { }
        a11y = null
        wm = null
        reapplyFlags()
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

    /** Called from HomeActivity.onResume: home is on screen, so a card covering it can go. */
    fun onHomeShown() {
        homeVisible = true
        homeShownAt = SystemClock.uptimeMillis()
    }

    // ------------------------------------------------------------------ windows

    private fun addStrip() {
        val ctx = a11y ?: return
        val wm = wm ?: return
        val h = max(dp(20), systemDimen("navigation_bar_gesture_height").takeIf { it > 0 } ?: systemDimen("navigation_bar_height"))
        val root = FrameLayout(ctx)
        val pill = View(ctx).apply {
            background = GradientDrawable().apply { setColor(0x99FFFFFF.toInt()); cornerRadius = dp(2).toFloat() }
        }
        root.addView(pill, FrameLayout.LayoutParams(dp(108), dp(4), Gravity.CENTER))
        root.setOnTouchListener { _, e -> onTouch(e) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.BOTTOM
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        if (Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0)
        lp.title = "LauncherGestureStrip"
        try {
            wm.addView(root, lp)
            strip = root
            AppLog.log("[nav] gesture strip on (${h}px high)")
        } catch (t: Throwable) {
            AppLog.log("[nav] strip addView FAILED: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun removeAll() {
        anim?.cancel()
        anim = null
        dragging = false
        animating = false
        removeCard()
        strip?.let { try { wm?.removeView(it) } catch (_: Throwable) { } }
        strip = null
        AppLog.log("[nav] gesture strip off")
    }

    private fun removeCard() {
        cardRoot?.let { try { wm?.removeView(it) } catch (_: Throwable) { } }
        cardRoot = null
        card = null
    }

    private fun systemDimen(name: String): Int {
        val id = app.resources.getIdentifier(name, "dimen", "android")
        return if (id != 0) app.resources.getDimensionPixelSize(id) else 0
    }

    // ------------------------------------------------------------------ touch

    private fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (animating) { grab(e); return true }
                startX = e.rawX
                startY = e.rawY
                lastDx = 0f
                lastDy = 0f
                lastT = e.eventTime
                vy = 0f
                dragging = false
                sideMode = false
                stats.reset()
                prefetch()
            }
            MotionEvent.ACTION_MOVE -> {
                if (animating) return true
                val dy = startY - e.rawY
                val dx = e.rawX - startX
                lastDx = dx
                if (!dragging && !homeVisible) {
                    if (dy > dp(10)) { sideMode = false; beginDrag() }
                    else if (abs(dx) > dp(14) && abs(dx) > abs(dy) * 1.2f) { sideMode = true; beginDrag() }
                }
                if (dragging) {
                    updateCard(dx, dy)
                    stats.touchEvent(e.eventTime)
                }
                val dt = max(1L, e.eventTime - lastT)
                vy = 0.8f * vy + 0.2f * ((dy - lastDy) / dt)
                lastDy = dy
                lastT = e.eventTime
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) endDrag(e.actionMasked == MotionEvent.ACTION_UP, startY - e.rawY)
                // On home there is no app card yet (recents comes later): a swipe up returns to the last app.
                else if (homeVisible && e.actionMasked == MotionEvent.ACTION_UP && startY - e.rawY > dp(40)) returnToLastApp()
            }
        }
        return true
    }

    /** Finds the foreground and previous task, then loads the cached snapshot (fast) followed by a fresh one. */
    private fun prefetch() {
        val s = ShizukuLink.service ?: return
        val id = ++gestureId
        val onHome = homeVisible
        fgTask = 0
        prevTask = 0
        cachedMs = -1
        freshMs = -1
        shownSnapshot = "none"
        pendingBitmap = null
        io.execute {
            try {
                val ids = s.recentTaskIds(2)
                if (id != gestureId || ids.isEmpty()) return@execute
                fgTask = ids[0]
                prevTask = ids.getOrElse(1) { 0 }
                if (onHome) return@execute
                var t = SystemClock.uptimeMillis()
                val cached = s.taskSnapshot(ids[0], false)
                val c = SystemClock.uptimeMillis() - t
                if (cached != null) ui.post { if (id == gestureId) { cachedMs = c; showSnapshot(cached, "cached") } }
                t = SystemClock.uptimeMillis()
                val fresh = s.taskSnapshot(ids[0], true)
                val f = SystemClock.uptimeMillis() - t
                if (fresh != null) ui.post { if (id == gestureId) { freshMs = f; showSnapshot(fresh, "fresh") } }
            } catch (t: Throwable) {
                AppLog.log("[nav] snapshot fetch failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private var pendingBitmap: Bitmap? = null

    private fun showSnapshot(b: Bitmap, kind: String) {
        // A fresh snapshot replaces a cached one, never the other way round.
        if (kind == "cached" && shownSnapshot == "fresh") return
        shownSnapshot = kind
        val c = card
        if (c == null) pendingBitmap = b else c.setImageBitmap(b)
    }

    @Suppress("DEPRECATION")
    private fun beginDrag() {
        val ctx = a11y ?: return
        val wm = wm ?: return
        // Re-read the display every time so rotation never leaves the card at the old size.
        val dm = android.util.DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        val root = FrameLayout(ctx)
        val c = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(0xFF2A2F3A.toInt())   // secure apps and missing snapshots: plain card
            pendingBitmap?.let { setImageBitmap(it) }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            pivotX = screenW / 2f
            pivotY = screenH / 2f
        }
        pendingBitmap = null
        root.addView(c, FrameLayout.LayoutParams(screenW, screenH))
        // Exact full-display size from the first frame, drawn into the cutout too, so the card is never laid out below the
        // status bar and then resized (the probe saw that as a stretch).
        val lp = WindowManager.LayoutParams(
            screenW, screenH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        if (Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0)
        lp.title = "LauncherCard"
        try {
            wm.addView(root, lp)
        } catch (t: Throwable) {
            AppLog.log("[nav] card addView FAILED: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        cardRoot = root
        card = c
        dragging = true
        stats.start()
    }

    private fun updateCard(dx: Float, dy: Float) {
        val c = card ?: return
        val p = min(1f, max(0f, dy / (screenH * 0.40f)))
        val s = 1f - 0.5f * p
        c.scaleX = s
        c.scaleY = s
        c.translationY = -dy * 0.35f
        c.translationX = dx * 0.9f
        radius = dp(36) * p
        c.invalidateOutline()
        cardRoot?.setBackgroundColor((min(255, (p * 4f * 255f).toInt()) shl 24) or BACKDROP)
    }

    /** Finger lands on a card that is still animating: take over from where the card is, no restart. */
    private fun grab(e: MotionEvent) {
        val c = card ?: return
        anim?.removeAllListeners()
        anim?.removeAllUpdateListeners()
        anim?.cancel()
        anim = null
        animating = false
        val p = min(1f, max(0f, (1f - c.scaleX) / 0.5f))
        val dyCur = p * screenH * 0.40f
        val dxCur = c.translationX / 0.9f
        startY = e.rawY + dyCur
        startX = e.rawX - dxCur
        lastDy = dyCur
        lastDx = dxCur
        lastT = e.eventTime
        vy = 0f
        sideMode = false
        c.alpha = 1f
        cardRoot?.alpha = 1f
        dragging = true
        stats.reset()
        stats.start()
        AppLog.log("[nav] grabbed mid-animation at scale ${"%.2f".format(c.scaleX)}")
    }

    private fun endDrag(up: Boolean, dy: Float) {
        val c = card
        val root = cardRoot
        dragging = false
        if (c == null || root == null) { removeCard(); return }
        val goesHome = !sideMode && up && (dy > screenH * 0.18f || vy > 0.9f)
        val switched = sideMode && up && abs(lastDx) > screenW * 0.18f && prevTask != 0
        val dirSign = if (lastDx >= 0f) 1f else -1f
        animating = true

        val s0 = c.scaleX
        val ty0 = c.translationY
        val tx0 = c.translationX
        val r0 = radius
        val s1 = if (goesHome) 0.14f else if (switched) 0.85f else 1f
        val ty1 = if (goesHome) screenH * 0.32f else 0f
        val tx1 = if (switched) dirSign * screenW else 0f
        val r1 = if (goesHome) dp(36).toFloat() else 0f
        val homeRequestedAt = SystemClock.uptimeMillis()
        if (goesHome) goHome()          // home loads underneath while the card flies to the icon
        if (switched) quickSwitch()     // the previous app loads underneath while the card flies off sideways

        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = if (goesHome) 280L else 220L
        a.addUpdateListener { va ->
            val f = interp.getInterpolation(va.animatedValue as Float)
            val s = s0 + (s1 - s0) * f
            c.scaleX = s
            c.scaleY = s
            c.translationY = ty0 + (ty1 - ty0) * f
            c.translationX = tx0 + (tx1 - tx0) * f
            radius = r0 + (r1 - r0) * f
            c.invalidateOutline()
            if (goesHome || switched) c.alpha = 1f - max(0f, (f - 0.7f) / 0.3f)
            val bg = if (goesHome) 255 else ((1f - f) * 255f).toInt()
            root.setBackgroundColor((bg shl 24) or BACKDROP)
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                animating = false
                anim = null
                val label = if (goesHome) "home" else if (switched) "quick switch" else "spring back"
                AppLog.log(stats.report("[nav] $label", dy / density) + "\n  snapshot: $shownSnapshot (cached ${cachedMs} ms, fresh ${freshMs} ms; -1 = none)")
                if (goesHome) revealHome(homeRequestedAt) else if (switched) fadeOutCard(60) else removeCard()
            }
        })
        anim = a
        a.start()
    }

    private fun goHome() {
        homeVisible = false
        try {
            app.startActivity(Intent(app, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            AppLog.log("[nav] going home from the app failed (${t.javaClass.simpleName}); using the shell")
            ShizukuLink.service?.let { s -> io.execute { s.runDetached("am start -a android.intent.action.MAIN -c android.intent.category.HOME") } }
        }
    }

    private fun returnToLastApp() {
        val s = ShizukuLink.service ?: return
        io.execute {
            // On home the newest recent task is the app we came from.
            val target = fgTask.takeIf { it != 0 } ?: s.recentTaskIds(1).firstOrNull() ?: 0
            if (target == 0) { AppLog.log("[nav] no recent app to return to"); return@execute }
            AppLog.log("[nav] home swipe up -> last app (task $target): ${try { s.switchToTask(target) } catch (e: Throwable) { "ERROR: ${e.message}" }}")
        }
    }

    private fun quickSwitch() {
        val s = ShizukuLink.service ?: return
        val target = prevTask
        io.execute {
            val t = SystemClock.uptimeMillis()
            val r = try { s.switchToTask(target) } catch (e: Throwable) { "ERROR: ${e.message}" }
            AppLog.log("[nav] quick switch to task $target: $r (${SystemClock.uptimeMillis() - t} ms)")
        }
    }

    /** Keeps the opaque backdrop until home has resumed (plus a frame to draw), then fades the card window out. */
    private fun revealHome(requestedAt: Long) {
        val deadline = requestedAt + 1000
        fun check() {
            if (cardRoot == null) return
            val ready = homeVisible && homeShownAt >= requestedAt
            if (ready || SystemClock.uptimeMillis() > deadline) {
                if (!ready) AppLog.log("[nav] home did not report within 1 s; removing the card anyway")
                fadeOutCard(120)
            } else {
                ui.postDelayed({ check() }, 16)
            }
        }
        // One extra frame after onResume so home has drawn before the backdrop goes.
        ui.postDelayed({ check() }, 16)
    }

    private fun fadeOutCard(ms: Long) {
        val root = cardRoot ?: return
        root.animate().alpha(0f).setDuration(ms).withEndAction { if (cardRoot === root) removeCard() }.start()
    }

    private const val BACKDROP = 0x101820

    /** Frame pacing (Choreographer) and touch-to-frame latency for one gesture, as in the probe. */
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

        fun report(label: String, travelDp: Float): String {
            val d = if (deltas.size > 3) deltas.drop(1) else deltas
            if (d.isEmpty()) return "$label: too short to measure"
            val sorted = d.sorted()
            val median = sorted[sorted.size / 2]
            fun p(q: Double, l: List<Double>) = l[min(l.size - 1, (l.size * q).toInt())]
            val dropped = d.sumOf { max(0, Math.round(it / median).toInt() - 1) }
            val lat = latencies.sorted()
            val latLine = if (lat.isEmpty()) "n/a" else "median ${"%.1f".format(lat[lat.size / 2])} ms, p95 ${"%.1f".format(p(0.95, lat))} ms"
            return "$label, travel ${"%.0f".format(travelDp)} dp\n" +
                "  frames ${d.size}: median ${"%.2f".format(median)} ms, p95 ${"%.2f".format(p(0.95, sorted))} ms, " +
                "worst ${"%.2f".format(sorted.last())} ms, dropped ~$dropped\n" +
                "  touch -> frame latency: $latLine"
        }
    }
}
