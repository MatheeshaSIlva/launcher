package dev.launcher.app

import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap

/**
 * What gesture navigation (nav thread) needs from the home screen (main thread): where each app's icon is on screen,
 * hiding an icon while a card flies into it, and the home zoom that goes with closing an app.
 */
object HomeBridge {
    private val main = Handler(Looper.getMainLooper())
    private val iconRects = ConcurrentHashMap<String, RectF>()

    /** Implemented by HomeActivity while it exists. Called on the main thread. */
    interface Home {
        fun setIconHidden(pkg: String, hidden: Boolean)
        /** The app is going home for good: settle the zoom. */
        fun animateReturn()
        /** The close was cancelled: back to normal without animation. */
        fun cancelReturn()
    }

    @Volatile var home: Home? = null

    /**
     * Set before home is brought up behind a closing app; HomeActivity then starts slightly zoomed in (as if the user is
     * still "inside" the app) until [animateReturn] or [cancelReturn].
     */
    @Volatile var returnPending = false

    fun setIconRect(pkg: String, r: RectF) { iconRects[pkg] = r }
    fun clearIconRects() = iconRects.clear()
    fun iconRect(pkg: String): RectF? = iconRects[pkg]

    fun setIconHidden(pkg: String, hidden: Boolean) = main.post { home?.setIconHidden(pkg, hidden) }
    fun animateReturn() = main.post { home?.animateReturn() }
    fun cancelReturn() = main.post { home?.cancelReturn() }
}
