package dev.launcher.app.shade

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import dev.launcher.app.AppLog

/**
 * Takes the window focus while a panel is open (Back closes it), without changing the shade's own window: a change of
 * its flags is a relayout of its thread (16.7 ms on the S24), and the window with the focus controls the system bars,
 * which the shade's window must never do (it asks for the navigation bar hidden: see Shade.updateExclusion).
 *
 * Over the lock screen it is taken during the pull already:
 * One UI draws its fingerprint icon in a system window above every app window, ours included ("FP Iconview"), and hides
 * it only when the lock screen's window loses the focus (measured on the S24: ~120 ms after). The shade's window takes the
 * focus only once a panel rests (a change of its flags is a relayout of its thread, never done while anything moves), so
 * the icon stood over the panel all through the pull and vanished after it landed. This window (1 px, transparent, never
 * touched) on a thread of its own takes the focus while the panel comes in instead (as soon as the window manager can no
 * longer hand the pull to the stock status bar: see Shade.HANDOVER_MS), so the icon goes then, and comes back once the
 * panel has closed. Back and the volume keys reach it, not the shade: [forward] passes them on.
 */
class FocusHolder(private val ctx: Context, private val wm: WindowManager, private val forward: (KeyEvent) -> Unit) {
    private val thread = HandlerThread("LauncherFocus").apply { start() }
    private val handler = Handler(thread.looper)
    private var view: View? = null

    private val wanted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Wanted on screen (any thread; the window follows on this one's). */
    val held get() = wanted.get()

    fun take() { if (wanted.compareAndSet(false, true)) handler.post { add() } }

    fun release() { if (wanted.compareAndSet(true, false)) handler.post { remove() } }

    /** The shade is replaced: the window goes and the thread ends. */
    fun quit() {
        wanted.set(false)
        handler.post { remove(); thread.quitSafely() }
    }

    private fun add() {
        if (!wanted.get() || view != null) return
        val v = object : View(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode !in KEYS) return super.dispatchKeyEvent(event)
                forward(KeyEvent(event))
                return true
            }
        }
        val lp = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Focusable but never the keyboard's target (ALT_FOCUSABLE_IM, as the shade's window when it takes the focus).
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSPARENT).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "LauncherFocus"
        }
        try {
            wm.addView(v, lp)
            view = v
        } catch (t: Throwable) {
            AppLog.log("[shade] focus window refused: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun remove() {
        val v = view ?: return
        view = null
        try { wm.removeView(v) } catch (_: Throwable) { }
    }

    private companion object {
        val KEYS = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)
    }
}
