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
 * - Samsung (One UI): its dim layer turned into a blur (`FLAG_DIM_BEHIND` + `SEM_EXTENSION_FLAG_CHANGE_DIM_EFFECT_TO_BLUR`),
 *   strength following `dimAmount` (probe 14g: driven every frame without failures). Standard cross-window blur is off on
 *   the S24 and cannot be switched on without root.
 * - Elsewhere, where the system allows cross-window blur (Android 12+): `FLAG_BLUR_BEHIND` with `blurBehindRadius`.
 * - Otherwise [available] is false and Control Center keeps its own blurred picture of what was behind (BackdropView).
 *
 * The window lives on a thread of its own: each change of its parameters is a relayout (10-50 ms of the window's thread),
 * which must never land on the shade's thread while a finger moves. It is hidden (no layer, nothing to compose) whenever
 * Control Center is closed. Changes are coalesced: a frame sends only the latest strength.
 */
class LiveBlur(ctx: Context, private val wm: WindowManager) {
    enum class Mode { SAMSUNG, STANDARD, NONE }

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
    /** Full strength: Samsung's dim amount, or the standard blur radius in px. */
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
                view.visibility = View.GONE
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
        if (mode == Mode.SAMSUNG) h.postDelayed({ prewarm() }, 1500)
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
            if (show != shownNow) {
                view.visibility = if (show) View.VISIBLE else View.GONE
                shownNow = show
            }
            writeStrength(k)
            strengthNow = k
            wm.updateViewLayout(view, lp)
        } catch (t: Throwable) {
            failed = true
            AppLog.log("[blur] live blur update FAILED: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun writeStrength(k: Float) {
        when (mode) {
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
        try {
            view.visibility = View.VISIBLE
            writeStrength(0.02f)
            wm.updateViewLayout(view, lp)
            h.postDelayed({
                if (shownNow || failed) return@postDelayed
                try { view.visibility = View.GONE; writeStrength(0f); wm.updateViewLayout(view, lp) } catch (_: Throwable) { }
            }, 250)
        } catch (t: Throwable) {
            AppLog.log("[blur] prewarm failed: ${t.message}")
        }
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
