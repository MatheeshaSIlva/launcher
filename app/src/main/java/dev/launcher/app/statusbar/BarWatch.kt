package dev.launcher.app.statusbar

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import dev.launcher.app.AppLog

/**
 * Whether the status bar shows, told by the window manager as it changes (not asked for every few seconds). Our overlay
 * windows lie above the status bar and are never told it shows; an application overlay lies under it, as apps do, and is
 * told what any app is: the bar hidden by the app in front (immersive), shown again, its height in this rotation. One
 * invisible pixel, never touched, never focused. Needs "display over other apps" (granted through Shizuku).
 */
class BarWatch(private val ctx: Context, private val wm: WindowManager,
               private val changed: (visible: Boolean, heightPx: Int) -> Unit) {
    private var view: View? = null
    private var last: Triple<Boolean, Int, Int>? = null

    val attached get() = view != null
    private var missLogged = false

    fun attach(): Boolean {
        if (view != null) return true
        if (Build.VERSION.SDK_INT < 30) return false
        if (!android.provider.Settings.canDrawOverlays(ctx)) {
            if (!missLogged) { missLogged = true; AppLog.log("[barwatch] no overlay permission (yet: Shizuku grants it)") }
            return false
        }
        val v = View(ctx)
        v.setOnApplyWindowInsetsListener { _, insets ->
            val visible = insets.isVisible(WindowInsets.Type.statusBars())
            val h = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
            // Told again after a rotation (the top edge is another side of the touchscreen then), even at the same height.
            val rotation = v.display?.rotation ?: 0
            val now = Triple(visible, h, rotation)
            if (now != last) {
                last = now
                AppLog.log("[barwatch] status bar ${if (visible) "visible" else "hidden"}, ${h}px, rotation $rotation")
                changed(visible, h)
            }
            insets
        }
        val lp = WindowManager.LayoutParams(1, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "LauncherBarWatch"
            // Fully transparent: never counted as covering what is under it (Android blocks touches through an app's
            // overlay that is more than 80 % opaque).
            alpha = 0f
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        return try {
            wm.addView(v, lp)
            view = v
            AppLog.log("[barwatch] on")
            true
        } catch (t: Throwable) {
            AppLog.log("[barwatch] not added: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    fun detach() {
        view?.let { try { wm.removeView(it) } catch (_: Throwable) { } }
        view = null
        last = null
    }
}
