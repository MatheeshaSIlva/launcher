package dev.launcher.app.switcher

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Region
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import dev.launcher.app.AppLog
import kotlin.math.ceil

/**
 * A full-screen, invisible window that takes the App Switcher's touches (taps, scrolls, flicks) while the deck is open, and
 * exists only then: an idle full-screen window would still be one more layer for the compositor to combine on every frame.
 * The card window itself never takes touches, because changing a window (adding it, or its flags) makes the window manager
 * re-lay it out (6-80 ms on the S24), which must never happen on the thread that draws the cards: this window is added and
 * removed on its own thread. Events go to [onEvent] (copies; the receiver recycles them).
 *
 * Floating windows (picture-in-picture: the mini player) are left out of it ([setHoles]): they lie under our windows, and
 * the card window leaves holes where they are, so they show and take their own touches (Matheesha: the mini player could
 * not be moved in the App Switcher). A touch that lands there is reported to [onOutside] (it starts the mini player's drag).
 */
class SwitcherInput(private val onEvent: (MotionEvent) -> Unit, private val onOutside: () -> Unit = {}) {
    private val thread = HandlerThread("switcher-input").apply { start() }
    private val handler = Handler(thread.looper)
    private var view: View? = null
    private var ctx: Context? = null
    private var wm: WindowManager? = null
    private var w = 0
    private var h = 0
    private var holes: List<android.graphics.RectF> = emptyList()
    @Volatile var shown = false
        private set

    fun attach(context: Context, windowManager: WindowManager, width: Int, height: Int) {
        handler.post { ctx = context; wm = windowManager; w = width; h = height }
    }

    /** Takes touches from now on (the window is added on its own thread; it lies above every window added before). */
    fun show() {
        if (shown) return
        shown = true
        handler.post {
            if (view != null) return@post
            val c = ctx ?: return@post
            val m = wm ?: return@post
            try {
                val v = View(c).apply {
                    setOnTouchListener { _, e ->
                        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) onOutside() else onEvent(MotionEvent.obtain(e))
                        true
                    }
                    // The region is taken only once the view is attached (set before, it is dropped).
                    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) = applyRegion()
                        override fun onViewDetachedFromWindow(v: View) {}
                    })
                }
                val lp = WindowManager.LayoutParams(
                    w, h,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSPARENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    title = "LauncherSwitcherInput"
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
                }
                m.addView(v, lp)
                view = v
            } catch (t: Throwable) {
                AppLog.log("[switcher] input window FAILED: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /** The floating windows' places (screen px): left to them. */
    fun setHoles(rects: List<android.graphics.RectF>) {
        val copy = rects.map { android.graphics.RectF(it) }
        handler.post { holes = copy; applyRegion() }
    }

    private fun applyRegion() {
        val v = view ?: return
        if (Build.VERSION.SDK_INT < 34) return
        val r = Region(0, 0, w, h)
        for (o in holes) r.op(o.left.toInt(), o.top.toInt(), ceil(o.right).toInt(), ceil(o.bottom).toInt(), Region.Op.DIFFERENCE)
        v.rootSurfaceControl?.setTouchableRegion(r)
    }

    /** Lets every touch through again (the window goes). */
    fun hide() {
        if (!shown) return
        shown = false
        handler.post {
            view?.let { v -> try { wm?.removeView(v) } catch (_: Throwable) { } }
            view = null
        }
    }

    fun resize(width: Int, height: Int) = handler.post { w = width; h = height; applyRegion() }

    fun quit() {
        hide()
        handler.post { thread.quitSafely() }
    }
}
