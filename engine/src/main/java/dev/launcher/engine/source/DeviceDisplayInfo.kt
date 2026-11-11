package dev.launcher.engine.source

import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.view.Display
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/**
 * The `device` source's view of the screen (T-010), following the display of the view it is bound to ([bind]): its size
 * in its own logical space, the cutout on its top edge (`DisplayCutout.boundingRectTop`), its safe insets, rotation and
 * density, and the status bar's height whether the bar shows or not (read from the view's window insets, ignoring the
 * bar's visibility: an insets listener of ours on the view is replaced by Compose's own, measured on the emulator). Told
 * of changes by the display listener, configuration changes (posted to [handler]), the view's attachment and every
 * layout of its window (insets arriving or changing lay the window out; nothing is consumed). All on [handler]'s thread.
 * A display removed gives no display (no cutout, nothing measured) until the view is attached again.
 */
class DeviceDisplayInfo(private val context: Context, private val handler: Handler) : DisplayInfo {
    private var view: View? = null
    private var display: Display? = null
    /** Its display went away: nothing until the view is attached again (Codex, T-010). */
    private var removed = false
    private val listeners = ArrayList<() -> Unit>()
    private var last: DisplayState? = null

    private val displays = context.getSystemService(DisplayManager::class.java)
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) { if (id == display?.displayId) { display = null; removed = true; changed() } }
        override fun onDisplayChanged(id: Int) { if (id == display?.displayId) changed() }
    }
    private val attach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            display = v.display; removed = false
            v.viewTreeObserver.addOnGlobalLayoutListener(laidOut)
            changed()
        }
        override fun onViewDetachedFromWindow(v: View) { v.viewTreeObserver.removeOnGlobalLayoutListener(laidOut) }
    }
    /** Insets arriving or changing lay the window out: the status bar's height is read again then. */
    private val laidOut = android.view.ViewTreeObserver.OnGlobalLayoutListener { changed() }
    private val config = object : android.content.ComponentCallbacks {
        // Delivered on the context's main thread; this adapter lives on [handler]'s (Codex, T-010).
        override fun onConfigurationChanged(c: Configuration) { handler.post { changed() } }
        @Deprecated("Deprecated in Java") override fun onLowMemory() {}
    }

    /** Follows [v]'s display from now on (and each time it is attached again). */
    fun bind(v: View) {
        unbind()
        view = v
        v.addOnAttachStateChangeListener(attach)
        if (v.isAttachedToWindow) { display = v.display; removed = false; v.viewTreeObserver.addOnGlobalLayoutListener(laidOut) }
        changed()
    }

    /** Lets its view go (the component disposed, before or after attaching). */
    fun unbind() {
        view?.let { it.removeOnAttachStateChangeListener(attach); it.viewTreeObserver.removeOnGlobalLayoutListener(laidOut) }
        view = null
    }

    override fun current(): DisplayState {
        val d = if (removed) null else display ?: view?.display ?: displays.getDisplay(Display.DEFAULT_DISPLAY)
        val (w, h) = size(d)
        val cut = d?.cutout
        val top = cut?.boundingRectTop?.takeIf { !it.isEmpty }
        return DisplayState(
            width = w, height = h,
            density = d?.let { context.createDisplayContext(it).resources.displayMetrics.density } ?: 0f,
            rotation = when (d?.rotation) { 1 -> 90; 2 -> 180; 3 -> 270; else -> 0 },
            statusHeight = view?.rootWindowInsets?.let(::statusOf) ?: 0f,
            safeLeft = (cut?.safeInsetLeft ?: 0).toFloat(), safeTop = (cut?.safeInsetTop ?: 0).toFloat(),
            safeRight = (cut?.safeInsetRight ?: 0).toFloat(), safeBottom = (cut?.safeInsetBottom ?: 0).toFloat(),
            cutout = top?.let { floatArrayOf(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) },
            model = Build.MODEL ?: "",
        )
    }

    override fun listen(changed: () -> Unit): () -> Unit {
        if (listeners.isEmpty()) {
            displays.registerDisplayListener(displayListener, handler)
            context.registerComponentCallbacks(config)
        }
        listeners += changed
        return {
            listeners -= changed
            if (listeners.isEmpty()) {
                displays.unregisterDisplayListener(displayListener)
                context.unregisterComponentCallbacks(config)
            }
        }
    }

    private fun changed() {
        val now = current()
        if (now == last) return
        last = now
        listeners.toList().forEach { it() }
    }

    /** The display's size in its own logical space (what its cutout is given in). */
    private fun size(d: Display?): Pair<Float, Float> {
        if (d == null) return 0f to 0f
        if (Build.VERSION.SDK_INT >= 30) {
            val b = context.createDisplayContext(d).getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            return b.width().toFloat() to b.height().toFloat()
        }
        val p = android.graphics.Point()
        @Suppress("DEPRECATION") d.getRealSize(p)
        return p.x.toFloat() to p.y.toFloat()
    }

    private fun statusOf(insets: WindowInsets): Float =
        if (Build.VERSION.SDK_INT >= 30) insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top.toFloat()
        else @Suppress("DEPRECATION") insets.stableInsetTop.toFloat()
}
