package dev.launcher.app

import android.graphics.Picture
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
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
        fun animateDepth(from: Float, to: Float, velocity: Float, curve: dev.launcher.app.design.Curve, startNanos: Long)
        /** Records home exactly as it shows now (every icon; open folder, scroll, page as they are). Main thread. */
        fun recordAsShown(): HomePicture?
        /**
         * Runs [then] once home has drawn its next frame (a frame later, when it has been queued), or after [timeoutMs];
         * with a [label], a late draw (or none) is logged.
         */
        fun afterNextDraw(then: () -> Unit, timeoutMs: Long = 150, label: String? = null)
        /** Home as it shows, exactly (a GPU bitmap), or null. Main thread. */
        fun pictureNow(): android.graphics.Bitmap?
        /** Home shows one of its pages as they rest (not the App Library, Spotlight, the widget gallery, edit mode or a menu). Main thread. */
        fun showsPage(): Boolean
        /** Nothing on home moves and no finger is on it (main thread). */
        fun isIdle(): Boolean
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
        onPreviewChanged?.invoke(p)
        // Re-record the picture a close is most likely to need right away (main thread, like setPreview's callers), so a
        // gesture that starts after a while in an app does not wait for it (home re-records every minute for the clock).
        val pkg = likelyClosing?.invoke() ?: return
        home?.recordWithout(pkg)?.let { without[pkg] = it }
    }

    /** Set by gesture nav: the app a close would fly into home right now (the app in front), or null. */
    @Volatile var likelyClosing: (() -> String?)? = null

    fun hasWithout(pkg: String) = without.containsKey(pkg)

    /**
     * Home as it showed at the tap of a launch whose card does not come out of its icon (sliding up, fading in:
     * motion/AppStyles), its icon in place; taken by that launch ([takeAtLaunch]).
     */
    fun putAtLaunch(pkg: String, p: HomePicture?) {
        atLaunch = if (p == null) null else Triple(pkg, p, android.os.SystemClock.uptimeMillis())
    }

    /** The picture of home [putAtLaunch] kept for [pkg]'s launch, if it is for that launch (a tap a moment ago). */
    fun takeAtLaunch(pkg: String): HomePicture? {
        val a = atLaunch ?: return null
        atLaunch = null
        return a.second.takeIf { a.first == pkg && android.os.SystemClock.uptimeMillis() - a.third < AT_LAUNCH_MS }
    }

    @Volatile private var atLaunch: Triple<String, HomePicture, Long>? = null
    private const val AT_LAUNCH_MS = 2000L

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
    // Those of them that show a badge (home's pages and dock; the App Library's icons show none).
    @Volatile private var badged: Set<String> = emptySet()

    fun setVisibleIcons(m: Map<String, RectF>, badges: Set<String> = emptySet()) { icons = m; badged = badges }
    fun iconRect(pkg: String): RectF? = icons[pkg]

    /** The dock's middle icon as home shows it (screen px), or null: where the settings' demo opens an app from. */
    fun dockIcon(): RectF? {
        val all = icons.values.filter { it.width() > 0f && it.height() > 0f }
        val bottom = all.maxOfOrNull { it.centerY() } ?: return null
        // (Icons published before home's first layout have no size: none, and the settings' Motion page crashed on them.)
        val row = all.filter { kotlin.math.abs(it.centerY() - bottom) <= it.height() / 2f }
        if (row.isEmpty()) return null
        val mid = (row.minOf { it.left } + row.maxOf { it.right }) / 2f
        return row.minByOrNull { kotlin.math.abs(it.centerX() - mid) }?.let { RectF(it) }
    }

    /** Whether [pkg]'s icon a card flies into shows a badge (the card shows it too as it turns into the icon). */
    fun showsBadge(pkg: String): Boolean = pkg in badged

    /** Whether the icon at [rect] (screen px) a card grows out of is one that shows a badge (not the App Library's). */
    fun showsBadgeAt(pkg: String, rect: RectF): Boolean {
        if (pkg !in badged) return false
        val r = icons[pkg] ?: return false
        return kotlin.math.abs(r.centerX() - rect.centerX()) < 4f && kotlin.math.abs(r.centerY() - rect.centerY()) < 4f
    }

    fun setIconHidden(pkg: String, hidden: Boolean) = main.post { home?.setIconHidden(pkg, hidden) }

    /**
     * Shows [pkg]'s icon again and runs [then] (main thread) once home has drawn a frame with it, or after 600 ms: the
     * card that turned into the icon is taken away only then, so no frame shows neither (the icon "went missing for a
     * moment" when the card went on a timer while home's draw was late; 150 ms was not always enough right after home
     * came back: Matheesha saw the icon missing, then coming back). The card looks exactly like the icon meanwhile.
     */
    fun showIconThen(pkg: String, then: () -> Unit) = main.post {
        val h = home
        if (h == null) { then(); return@post }
        h.setIconHidden(pkg, false)
        h.afterNextDraw(then, timeoutMs = 600, label = "icon handover")
    }

    /** Runs [then] (main thread) once home has drawn its next frame and it has been queued, or after 150 ms. Any thread. */
    fun afterHomeDraw(then: () -> Unit) = main.post { home?.afterNextDraw(then) ?: then() }

    /**
     * The real home takes over the depth animation from the picture: same curve, same start ([startNanos], System.nanoTime
     * base like Choreographer frame times), so when the picture is dropped nothing jumps. Any thread.
     */
    fun animateDepth(from: Float, to: Float, velocity: Float, curve: dev.launcher.app.design.Curve, startNanos: Long) =
        main.post { home?.animateDepth(from, to, velocity, curve, startNanos) }

    /**
     * True while gesture nav's picture of home covers the real home (cards on screen): home then skips its depth blur,
     * which nobody would see (its zoom still runs, so it matches whenever it is uncovered), and holds back a change that
     * would show (a new wallpaper's reveal: [whenUncovered]).
     */
    @Volatile var homeCovered = false
        set(v) {
            val was = field
            field = v
            if (was && !v) {
                val l = synchronized(uncovered) { uncovered.toList().also { uncovered.clear() } }
                if (l.isNotEmpty()) main.post { for (f in l) f() }
            }
        }
    private val uncovered = ArrayList<() -> Unit>()

    /**
     * Runs [f] on the main thread once the real home is no longer covered by gesture nav's picture of it (at once if it is
     * not). Any thread.
     */
    fun whenUncovered(f: () -> Unit) {
        val now = synchronized(uncovered) { if (homeCovered) { uncovered += f; false } else true }
        if (now) main.post(f)
    }

    /** When gesture nav last brought home forward from an app (a close, the App Switcher's home; uptime ms; 0 never). */
    @Volatile var homeStartedAt = 0L

    /**
     * What is under the status bar on home is light (the wallpaper, or the light App Library or Spotlight): our status bar
     * shows black content on home. Gesture nav is told at once ([onStatusDarkChanged]).
     */
    @Volatile var homeStatusDark = false
        set(v) { if (field != v) { field = v; onStatusDarkChanged?.invoke() } }

    /** Set by gesture nav: [homeStatusDark] changed (any thread). */
    @Volatile var onStatusDarkChanged: (() -> Unit)? = null

    /**
     * Set by gesture nav: our status bar as it shows over home, drawn on its own thread; [then] gets it on the main thread
     * (null: no bar of ours).
     */
    @Volatile var statusBarPicture: (((android.graphics.Bitmap?) -> Unit) -> Unit)? = null

    /** Our status bar as it shows over home (see [statusBarPicture]), or null; main thread. */
    suspend fun statusBarPicture(): android.graphics.Bitmap? {
        val ask = statusBarPicture ?: return null
        return kotlinx.coroutines.withTimeoutOrNull(500) {
            kotlinx.coroutines.suspendCancellableCoroutine { k -> ask { b -> if (k.isActive) k.resumeWith(Result.success(b)) } }
        }
    }

    /**
     * A swipe on the bar began on home: home is recorded as it shows right now (the picture "at rest" can be seconds old: a
     * folder opened since, the library scrolled), then [then] runs. Any thread; [then] runs on the main thread.
     */
    fun recordForGesture(then: () -> Unit) = main.post {
        home?.recordAsShown()?.let { if (it !== preview) setPreview(it) }
        then()
    }

    /** Set by gesture nav: a new picture of home (main thread), to render ahead while no card shows. */
    @Volatile var onPreviewChanged: ((HomePicture) -> Unit)? = null

    /** A swipe up on the gesture bar while home is in front (leave edit mode, close Spotlight). Any thread. */
    fun homeSwipeUp() = main.post { (home as? HomeActivity)?.onHomeSwipeUp() }

    /**
     * Something is open on top of home (a menu, the widget gallery, edit mode, Spotlight): a swipe up on the bar closes it
     * (iOS) instead of making home recede. Any thread (a few flags of home's, read as they are).
     */
    fun hasOnTop(): Boolean = (home as? HomeActivity)?.hasOnTop() == true

    /** A touch the card window caught during a close: home handles it as its own (any thread; a copy is posted). */
    fun forwardTouch(e: MotionEvent) {
        val ev = MotionEvent.obtain(e)
        main.post {
            (home as? HomeActivity)?.forwardTouch(ev)
            ev.recycle()
        }
    }

    /** Set by gesture nav: home was touched (home takes over behind a closing card). Called on the main thread. */
    @Volatile var onHomeTouched: (() -> Unit)? = null

    /**
     * A finger is on home now. Work that can wait (recording home, rendering its picture ahead) waits for it to lift: a
     * swipe that starts on a resting finger is a page or the App Library coming, and its first frames must not wait for it.
     */
    @Volatile var touching = false

    /** When the launcher was last touched (a finger down or up on home, the bar or a panel; uptime ms; any thread). */
    @Volatile var lastTouchAt = 0L

    /**
     * Set by gesture nav: a touch on home moved past the touch slop (pages, the App Library, Spotlight: icons move, a closing
     * card gets out of the way). Called on the main thread, once per touch.
     */
    @Volatile var onHomeDragged: (() -> Unit)? = null
}
