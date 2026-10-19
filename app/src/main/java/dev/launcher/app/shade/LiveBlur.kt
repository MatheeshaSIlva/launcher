package dev.launcher.app.shade

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import dev.launcher.app.AppLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A live blur of everything under the shade (Control Center's background, as iOS: what is behind keeps moving under the
 * blur). The shade's own window cannot blur what is behind it without blurring itself, so the blur is a window of its own,
 * just under the shade's (added first: windows of one kind stack in the order they were added), empty and untouchable,
 * that blurs what is behind it:
 * - Samsung (One UI): the window's view blurs what is behind it (`View.semSetBlurInfo`, [dev.launcher.app.design.SamsungBlur],
 *   as the banners), its radius set again at every frame (no relayout). One UI 9 (Android 17) no longer gives our window a
 *   dim layer at all, so its dim turned into a blur (`FLAG_DIM_BEHIND` + `SEM_EXTENSION_FLAG_CHANGE_DIM_EFFECT_TO_BLUR`,
 *   strength following `dimAmount`, probe 14g) showed nothing: kept only for a One UI without the view blur. Standard
 *   cross-window blur is off on the S24 and cannot be switched on without root.
 * - Elsewhere, where the system allows cross-window blur (Android 12+): `FLAG_BLUR_BEHIND` with `blurBehindRadius`.
 * - Otherwise [available] is false and Control Center keeps its own blurred picture of what was behind (BackdropView).
 *
 * The window lives on a thread of its own: each change of its parameters is a relayout (10-50 ms of the window's thread),
 * which must never land on the shade's thread while a finger moves. Whenever Control Center is closed it is one empty
 * pixel without blur (nothing to compose), never a GONE view: the window manager waits for every window of ours to draw
 * before it turns the display, and a GONE view never draws (Android 17: every rotation stood still 2 s, its sync timeout,
 * laid out in the new orientation but shown in the old). Changes are coalesced: a frame sends only the latest strength.
 */
class LiveBlur(ctx: Context, private val wm: WindowManager) {
    enum class Mode { SAMSUNG_VIEW, SAMSUNG, STANDARD, NONE }

    private val thread = HandlerThread("live-blur").apply { start() }
    private val h = Handler(thread.looper)
    private val view = View(ctx)
    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "LauncherBlur"
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        dimAmount = 0f
    }

    val mode: Mode = when {
        dev.launcher.app.design.SamsungBlur.available -> Mode.SAMSUNG_VIEW
        samsungFlag() != null -> Mode.SAMSUNG
        Build.VERSION.SDK_INT >= 31 && try { wm.isCrossWindowBlurEnabled } catch (_: Throwable) { false } -> Mode.STANDARD
        else -> Mode.NONE
    }

    @Volatile private var added = false
    /** True while the window is there and its blur can be used. */
    val available get() = added && mode != Mode.NONE && !failed
    @Volatile private var failed = false

    /** Strength wanted (0..1) and whether the window should show; read by the window's thread. */
    @Volatile private var want = 0f
    @Volatile private var wantShown = false
    @Volatile private var pending = false
    private var shownNow = false
    private var strengthNow = -1f
    /** Full strength: Samsung's view blur radius, its dim amount, or the standard blur radius in px. */
    @Volatile var maxBlurPx = 0f

    /** Adds the window (blocking briefly: it must be in place before the shade's window, to sit under it). */
    fun attach() {
        if (mode == Mode.NONE) return
        val done = CountDownLatch(1)
        // On the window's thread, in order with a detach just before (a re-attach adds it again, under the shade).
        h.post {
            try {
                if (added) { try { wm.removeView(view) } catch (_: Throwable) { }; added = false }
                shownNow = false
                strengthNow = -1f
                view.visibility = View.VISIBLE
                lp.width = 1; lp.height = 1
                if (mode == Mode.SAMSUNG) setupSamsung()
                wm.addView(view, lp)
                added = true
                AppLog.log("[blur] live blur window added ($mode)")
            } catch (t: Throwable) {
                failed = true
                AppLog.log("[blur] live blur window FAILED: ${t.javaClass.simpleName}: ${t.message}")
            }
            done.countDown()
        }
        done.await(800, TimeUnit.MILLISECONDS)
        // Samsung's first blur of a boot cost a 125 ms hitch (probe): taken now, with nothing on screen yet.
        if (mode == Mode.SAMSUNG || mode == Mode.SAMSUNG_VIEW) h.postDelayed({ prewarm() }, 1500)
    }

    fun detach() {
        h.post {
            if (added) try { wm.removeView(view) } catch (_: Throwable) { }
            added = false
        }
    }

    /** For good: the window goes and its thread ends. */
    fun release() {
        detach()
        thread.quitSafely()
    }

    /** This frame's strength (0..1) of the blur; [show] false hides the window (the panel closed). */
    fun set(strength: Float, show: Boolean) {
        if (!available) return
        want = strength.coerceIn(0f, 1f)
        wantShown = show
        if (pending) return
        pending = true
        h.postAtFrontOfQueue(apply)
    }

    private val apply = Runnable {
        pending = false
        val show = wantShown
        val k = if (show) want else 0f
        if (show == shownNow && (k == strengthNow || !show)) return@Runnable
        try {
            val resize = show != shownNow
            if (resize) {
                val size = if (show) WindowManager.LayoutParams.MATCH_PARENT else 1
                lp.width = size; lp.height = size
                lp.x = 0; lp.y = 0   // (the prewarm's corner)
                shownNow = show
            }
            if (mode == Mode.SAMSUNG_VIEW) {
                // The view's blur changes without a relayout; the window's size only when the panel comes or goes.
                if (!show) viewBlur(0f)
                if (resize) wm.updateViewLayout(view, lp)
                if (show) viewBlur(k)
            } else {
                writeStrength(k)
                wm.updateViewLayout(view, lp)
            }
            strengthNow = k
        } catch (t: Throwable) {
            failed = true
            AppLog.log("[blur] live blur update FAILED: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Samsung's view blur at strength [k] (its radius: it does not follow the view's alpha). A blur looks about as strong as
     * the logarithm of its radius: a radius that fell in step with Control Center stayed a heavy blur until its last frames
     * and then cleared at once (Matheesha's video: the panel gone, home still blurred for half a second, then sharp in one
     * frame). The radius follows [k] on that scale instead, (1 + max)^k - 1, so the blur clears as the controls fade.
     */
    private fun viewBlur(k: Float) {
        val r = if (k <= 0f) 0 else Math.round(Math.pow(1.0 + maxBlurPx, k.toDouble()) - 1.0).toInt()
        if (r <= 0) dev.launcher.app.design.SamsungBlur.clear(view)
        else if (!dev.launcher.app.design.SamsungBlur.set(view, r, 0f, 0)) throw IllegalStateException("Samsung view blur refused")
        view.invalidate()
    }

    private fun writeStrength(k: Float) {
        when (mode) {
            Mode.SAMSUNG_VIEW -> {}
            Mode.SAMSUNG -> {
                lp.flags = if (k > 0f) lp.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND else lp.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
                lp.dimAmount = (maxBlurPx * k).coerceIn(0f, 1f)
            }
            Mode.STANDARD -> if (Build.VERSION.SDK_INT >= 31) {
                lp.flags = if (k > 0f) lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND else lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                lp.blurBehindRadius = Math.round(maxBlurPx * k)
            }
            Mode.NONE -> {}
        }
    }

    private fun prewarm() {
        if (!added || failed) return
        if (mode == Mode.SAMSUNG_VIEW) { prewarmCorner(); return }
        try {
            lp.width = WindowManager.LayoutParams.MATCH_PARENT; lp.height = WindowManager.LayoutParams.MATCH_PARENT
            writeStrength(0.02f)
            wm.updateViewLayout(view, lp)
            if (mode == Mode.SAMSUNG_VIEW) viewBlur(0.02f)
            h.postDelayed({
                if (shownNow || failed) return@postDelayed
                try {
                    if (mode == Mode.SAMSUNG_VIEW) viewBlur(0f)
                    lp.width = 1; lp.height = 1; writeStrength(0f); wm.updateViewLayout(view, lp)
                } catch (_: Throwable) { }
            }, 250)
        } catch (t: Throwable) {
            AppLog.log("[blur] prewarm failed: ${t.message}")
        }
    }

    /**
     * Samsung's view blur once, for real, where nobody sees it: a small square in the display's bottom-left corner, inside
     * what its rounded corner hides. SurfaceFlinger's first blur of a boot cost its composite 13 ms at Control Center's first
     * opening (Perfetto: "BlurRegion #0, radius=4"); the old prewarm's strength rounded to radius 0, which is no blur at all.
     */
    private fun prewarmCorner() {
        try {
            val display = if (Build.VERSION.SDK_INT >= 30) view.display else null
            val corner = if (Build.VERSION.SDK_INT >= 31) display?.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0 else 0
            // Inside the corner's curve: a square's inner corner (s, s) from the screen's corner lies outside the visible
            // arc while s < r (1 - 1/sqrt 2) = 0.29 r.
            val s = (corner * 0.25f).toInt()
            if (s < 4) return
            val screenH = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.height() else view.resources.displayMetrics.heightPixels
            lp.width = s; lp.height = s; lp.x = 0; lp.y = screenH - s
            wm.updateViewLayout(view, lp)
            if (!dev.launcher.app.design.SamsungBlur.set(view, maxOf(8, (maxBlurPx / 2f).toInt()), 0f, 0)) return
            view.invalidate()
            h.postDelayed({
                if (shownNow || failed) return@postDelayed
                try {
                    dev.launcher.app.design.SamsungBlur.clear(view)
                    lp.width = 1; lp.height = 1; lp.x = 0; lp.y = 0
                    wm.updateViewLayout(view, lp)
                } catch (_: Throwable) { }
            }, PREWARM_MS)
            AppLog.log("[blur] prewarmed in a ${s}px corner")
        } catch (t: Throwable) {
            AppLog.log("[blur] prewarm failed: ${t.message}")
        }
    }

    private companion object {
        /** How long the prewarm's blur stays (a few frames: SurfaceFlinger composes it at least once; ms). */
        const val PREWARM_MS = 300L
    }

    private fun setupSamsung() {
        val flag = samsungFlag() ?: return
        val cls = WindowManager.LayoutParams::class.java
        cls.getDeclaredMethod("semAddExtensionFlags", Int::class.javaPrimitiveType).also { it.isAccessible = true }.invoke(lp, flag)
        // The dim layer's own fade (One UI animates dim changes): off, the strength follows the finger.
        try { cls.getDeclaredMethod("semSetEnterDimDuration", Long::class.javaPrimitiveType).also { it.isAccessible = true }.invoke(lp, 0L) } catch (_: Throwable) { }
        try { cls.getDeclaredMethod("semSetExitDimDuration", Long::class.javaPrimitiveType).also { it.isAccessible = true }.invoke(lp, 0L) } catch (_: Throwable) { }
    }

    private fun samsungFlag(): Int? = try {
        val cls = WindowManager.LayoutParams::class.java
        cls.getDeclaredMethod("semAddExtensionFlags", Int::class.javaPrimitiveType)
        cls.getField("SEM_EXTENSION_FLAG_CHANGE_DIM_EFFECT_TO_BLUR").getInt(null)
    } catch (_: Throwable) { null }
}
