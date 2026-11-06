package dev.launcher.app.switcher

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.SurfaceControlViewHost
import android.view.View
import dev.launcher.app.AppLog

/**
 * The App Switcher's window among the apps ([SwitcherActivity]) and what gesture nav draws into it. A picture-in-picture
 * window lies above every app but under every overlay of ours: in the card window (an overlay) the deck covered the mini
 * player and showed it through a hole that had to chase it. While the switcher is open and a mini player is around, the
 * deck is drawn in this activity's window instead, under the mini player, as on any app.
 *
 * The deck stays on gesture nav's thread ([thread]): its view hierarchy is hosted by a [SurfaceControlViewHost] made on that
 * thread and shown in the activity's [SurfaceView] (home's main thread never draws it). A host is embedded once only (lent
 * to a second window it took no touches, emulator), so each switcher session takes the spare host made ahead while nothing
 * moved (making one costs that thread 6-110 ms) and the next spare is made once the session is over. The session's
 * surface package is handed to the activity as the session begins, so it is embedded before the window's first frame.
 *
 * Main thread: the activity's callbacks. Gesture nav's thread: everything else, and every [Listener] call.
 */
object SwitcherHost {
    interface Listener {
        /** Session [s]'s window shows what the host draws (the deck may move in). */
        fun onWindowShown(s: Int)
        /** Session [s]'s window has been covered (it goes): by what the switcher chose, or something else that came over it. */
        fun onWindowLost(s: Int)
        /** Back was pressed in session [s]'s window. */
        fun onWindowBack(s: Int)
        /** A touch on what the host draws (screen coordinates). The receiver recycles [e] (a copy). */
        fun onWindowTouch(e: MotionEvent)
    }

    /** Gesture nav's thread (set at its start). */
    @Volatile var thread: Handler? = null
    @Volatile var listener: Listener? = null
    private val main = Handler(Looper.getMainLooper())

    // ---- gesture nav's thread
    private var spare: SurfaceControlViewHost? = null
    private var live: SurfaceControlViewHost? = null
    private var root: View? = null
    private var ctx: Context? = null
    private var display: Display? = null
    private var w = 0
    private var h = 0
    /** The session whose window is wanted (0: none); read on the main thread too. */
    @Volatile var session = 0
        private set

    /** A spare host is ready (a session can start now). */
    val ready get() = Build.VERSION.SDK_INT >= 34 && spare != null

    /**
     * Sets up [r] (gesture nav's view, its thread) as what every session's window shows, at [width] x [height], and makes the
     * first spare host. Again on a display size change (a spare is laid out anew).
     */
    fun setUp(context: Context, d: Display, r: View, width: Int, height: Int) {
        if (Build.VERSION.SDK_INT < 34) return
        ctx = context; display = d; root = r
        if (width != w || height != h) {
            w = width; h = height
            try { spare?.relayout(w, h) } catch (_: Throwable) { }
        }
        makeSpare()
    }

    /** The next session's host, if there is none (call while nothing moves: it costs this thread a few ms). */
    fun makeSpare() {
        if (Build.VERSION.SDK_INT < 34 || spare != null || live != null) return
        val c = ctx ?: return
        val d = display ?: return
        val r = root ?: return
        if (r.parent != null || w <= 0 || h <= 0) return
        val t0 = SystemClock.uptimeMillis()
        try {
            val s = SurfaceControlViewHost(c, d, null as android.os.IBinder?)
            s.setView(r, w, h)
            spare = s
            r.setOnTouchListener { _, e -> listener?.onWindowTouch(MotionEvent.obtain(e)); true }
            AppLog.log("[switcher] its window's host made ready in ${SystemClock.uptimeMillis() - t0} ms")
        } catch (t: Throwable) {
            AppLog.log("[switcher] its window's host failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Session [s] wants its window: the spare host becomes the session's (the caller starts the activity: [intent]). */
    fun begin(s: Int): Boolean {
        val sp = spare ?: return false
        val pkg = try { sp.surfacePackage } catch (_: Throwable) { null } ?: return false
        spare = null
        live = sp
        session = s
        handed = s to pkg
        askedAt = SystemClock.uptimeMillis()
        embeddedAt = 0L
        return true
    }

    /** Ends session [s]: its window goes (at once, nothing animated: it is covered by then), its host a moment after. */
    fun end(s: Int) {
        if (session != s || s == 0) return
        session = 0
        handed = null
        val l = live
        live = null
        main.post { current?.takeIf { it.session == s }?.dismiss() }
        // The host goes once its window has (the caller has moved the deck out of it first); the next spare is the caller's
        // to make, when nothing moves (makeSpare).
        thread?.postDelayed({ try { l?.release() } catch (_: Throwable) { } }, RELEASE_AFTER_MS)
    }

    fun intent(c: Context, s: Int): Intent = Intent(c, SwitcherActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION or
            Intent.FLAG_ACTIVITY_NO_ANIMATION)
        .putExtra(SwitcherActivity.EXTRA_SESSION, s)

    // ---- the activity (main thread)

    @Volatile private var current: SwitcherActivity? = null
    /** The session's surface package, for its activity (taken on the main thread). */
    @Volatile private var handed: Pair<Int, SurfaceControlViewHost.SurfacePackage>? = null
    // When the session asked for its window and when the deck's surface went into it (uptime ms; for the log).
    @Volatile private var askedAt = 0L
    @Volatile private var embeddedAt = 0L

    internal fun wanted(s: Int) = s != 0 && s == session

    internal fun onCreated(a: SwitcherActivity) { current = a }

    /** The activity's surface was made (main thread, inside its window's first traversal): the deck's surface goes in now. */
    internal fun onSurfaceReady(a: SwitcherActivity) {
        val s = a.session
        val pkg = handed?.takeIf { it.first == s }?.second
        if (!wanted(s) || current !== a || pkg == null) { a.dismiss(); return }
        if (!a.embed(pkg)) return
        embeddedAt = SystemClock.uptimeMillis()
        if (a.entered) shown(a)
        else main.postDelayed({ if (wanted(s) && current === a && !a.entered) AppLog.log("[switcher] its window has not been reported shown after ${NOT_SHOWN_LOG_MS} ms") }, NOT_SHOWN_LOG_MS)
    }

    /** The activity's opening transition has finished (main thread): its window is on screen. */
    internal fun onEntered(a: SwitcherActivity) {
        if (current === a && a.embedded) shown(a)
    }

    // On screen with the deck's surface in it (embedded before its first frame): the deck may move in a refresh later.
    private fun shown(a: SwitcherActivity) {
        val s = a.session
        val now = SystemClock.uptimeMillis()
        AppLog.log("[switcher] its window is on screen ${now - askedAt} ms after it was asked for (the deck's surface in it after ${embeddedAt - askedAt} ms)")
        thread?.postDelayed({ if (wanted(s)) listener?.onWindowShown(s) }, SHOWN_MARGIN_MS)
    }

    internal fun onStopped(a: SwitcherActivity) {
        val s = a.session
        if (current === a) thread?.post { if (wanted(s)) listener?.onWindowLost(s) }
    }

    internal fun onBack(a: SwitcherActivity) {
        val s = a.session
        thread?.post { if (wanted(s)) listener?.onWindowBack(s) else main.post { a.dismiss() } }
    }

    internal fun onGone(a: SwitcherActivity) {
        if (current === a) current = null
    }

    /** How long a finished session's host is kept (ms): its window has gone by then. */
    private const val RELEASE_AFTER_MS = 400L
    /** After the window's opening transition has finished, it counts as shown this much later (ms: a refresh at 60 Hz). */
    private const val SHOWN_MARGIN_MS = 17L
    /** A window not reported shown this long after its surface was made is logged (ms; the switcher goes on in the card window). */
    private const val NOT_SHOWN_LOG_MS = 1000L
}
