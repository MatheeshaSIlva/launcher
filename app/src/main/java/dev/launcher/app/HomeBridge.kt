package dev.launcher.app

import android.graphics.Picture
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * The home screen as recorded pictures, in two layers so they can zoom by different amounts (iOS depth: icons recede more
 * than the wallpaper). [wallpaper] is null when the system draws the wallpaper (then the content layer is opaque black).
 */
class HomePicture(val wallpaper: Picture?, val content: Picture)

/**
 * What gesture navigation (nav thread) needs from the home screen (main thread): where each visible app icon is, hiding
 * an icon while a card flies into or out of it, recorded pictures of home to draw behind cards, and when home has
 * actually drawn after coming back.
 */
object HomeBridge {
    private val main = Handler(Looper.getMainLooper())

    /** Implemented by HomeActivity while it exists. Called on the main thread. */
    interface Home {
        fun setIconHidden(pkg: String, hidden: Boolean)
        /** Records home with [pkg]'s icon left out (main thread). */
        fun recordWithout(pkg: String): HomePicture?
        /** Runs home's own depth (zoom) on the same spring as gesture nav's, from the same start time (main thread). */
        fun animateDepth(from: Float, to: Float, velocity: Float, response: Float, damping: Float, startNanos: Long)
    }

    @Volatile var home: Home? = null

    /** Home at rest as currently shown (page, drawer, folder), recorded whenever it settles. */
    @Volatile var preview: HomePicture? = null
        private set
    @Volatile private var generation = 0
    private val without = ConcurrentHashMap<String, HomePicture>()
    private val requested = ConcurrentHashMap.newKeySet<String>()

    /** Called on the main thread when a picture without [pkg]'s icon becomes available. */
    @Volatile var onPreviewReady: ((String) -> Unit)? = null

    fun setPreview(p: HomePicture) {
        preview = p
        generation++
        without.clear()
        // Re-record the picture a close is most likely to need right away (main thread, like setPreview's callers), so a
        // gesture that starts after a while in an app does not wait for it (home re-records every minute for the clock).
        val pkg = likelyClosing?.invoke() ?: return
        home?.recordWithout(pkg)?.let { without[pkg] = it }
    }

    /** Set by gesture nav: the app a close would fly into home right now (the app in front), or null. */
    @Volatile var likelyClosing: (() -> String?)? = null

    /** Stores a picture without [pkg]'s icon recorded right now (a launch records it before its card appears). */
    fun putWithout(pkg: String, p: HomePicture?) { if (p != null) without[pkg] = p }

    /**
     * The picture to draw behind [pkg]'s card: without its icon if recorded, else the plain picture while one without it
     * is recorded on the main thread (then [onPreviewReady] fires). Any thread.
     */
    fun previewFor(pkg: String?): HomePicture? {
        if (pkg == null) return preview
        without[pkg]?.let { return it }
        if (requested.add(pkg)) {
            val g = generation
            main.post {
                requested.remove(pkg)
                if (g != generation) return@post
                val p = home?.recordWithout(pkg) ?: return@post
                without[pkg] = p
                onPreviewReady?.invoke(pkg)
            }
        }
        return preview
    }

    /** uptimeMillis of the last frame home committed after a resume. */
    @Volatile var homeDrawnAt = 0L
        private set

    fun onHomeDrawn() { homeDrawnAt = SystemClock.uptimeMillis() }

    // Icons a closing card can fly into right now (screen px), published by home whenever it settles.
    @Volatile private var icons: Map<String, RectF> = emptyMap()

    fun setVisibleIcons(m: Map<String, RectF>) { icons = m }
    fun iconRect(pkg: String): RectF? = icons[pkg]

    fun setIconHidden(pkg: String, hidden: Boolean) = main.post { home?.setIconHidden(pkg, hidden) }

    /**
     * The real home takes over the depth animation from the picture: same spring, same start ([startNanos], System.nanoTime
     * base like Choreographer frame times), so when the picture is dropped nothing jumps. Any thread.
     */
    fun animateDepth(from: Float, to: Float, velocity: Float, response: Float, damping: Float, startNanos: Long) =
        main.post { home?.animateDepth(from, to, velocity, response, damping, startNanos) }

    /** Set by gesture nav: home was touched (a closing card should get out of the way). Called on the main thread. */
    @Volatile var onHomeTouched: (() -> Unit)? = null
}
