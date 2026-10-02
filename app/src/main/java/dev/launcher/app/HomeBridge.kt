package dev.launcher.app

import android.graphics.Picture
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * What gesture navigation (nav thread) needs from the home screen (main thread): where each app's icon is, hiding an
 * icon while a card flies into or out of it, a recorded picture of the home screen to draw behind closing cards, and
 * when home has actually drawn after coming back.
 */
object HomeBridge {
    private val main = Handler(Looper.getMainLooper())
    private val iconRects = ConcurrentHashMap<String, RectF>()

    /** Implemented by HomeActivity while it exists. Called on the main thread. */
    interface Home {
        fun setIconHidden(pkg: String, hidden: Boolean)
    }

    @Volatile var home: Home? = null

    /**
     * The home screen as drawn at rest (wallpaper, clock, dock), recorded by HomeActivity while it is idle. Gesture nav
     * draws it behind a closing card, so the app stays in front (and running) until the gesture commits.
     */
    @Volatile var preview: Picture? = null

    /** The same picture with one app's icon left out: used while that app's card flies into or out of its icon. */
    val previewWithout = ConcurrentHashMap<String, Picture>()

    fun previewFor(pkg: String?): Picture? = pkg?.let { previewWithout[it] } ?: preview

    /** uptimeMillis of the last frame home committed after a resume. */
    @Volatile var homeDrawnAt = 0L
        private set

    fun onHomeDrawn() { homeDrawnAt = SystemClock.uptimeMillis() }

    fun setIconRect(pkg: String, r: RectF) { iconRects[pkg] = r }
    fun iconRect(pkg: String): RectF? = iconRects[pkg]

    fun setIconHidden(pkg: String, hidden: Boolean) = main.post { home?.setIconHidden(pkg, hidden) }
}
