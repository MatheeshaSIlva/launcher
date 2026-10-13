package dev.launcher.app.settings

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.launcher.app.AppLog
import dev.launcher.app.HomeBridge
import java.util.concurrent.Executors

/**
 * Home handing itself over to the settings as they open from its edit menu ("Home Settings"; docs/PLAN_SETTINGS.md),
 * Matheesha: home slides into the settings' picture of it, not a separate app opening. Home pictures itself at rest with
 * our status bar across its top and offers it ([offer]); the settings' first frame shows exactly that over the whole
 * screen (their window comes up as home itself), then it shrinks into the picture of home above the sheet as the sheet
 * rises ([EntryState]). Back on the tiles plays it the other way and home comes back ([SettingsActivity]). Main thread.
 */
object SettingsEntry {
    /** The intent extra: opened from home's edit menu. */
    const val FROM_HOME = "dev.launcher.app.settings.FROM_HOME"

    /** Home's picture with our status bar on it, and whether the bar's content is dark over it (a light home). */
    class Handed(val picture: Bitmap, val barDark: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val baker = Executors.newSingleThreadExecutor { r -> Thread(r, "settings-picture") }
    private val lock = Any()
    private var handed: Handed? = null
    private var offered = false
    private var offeredAt = 0L
    private var waiting: ((Handed?) -> Unit)? = null

    /**
     * Home (main thread) opening the settings: it pictures itself as it shows (with our status bar's picture, drawn on
     * gesture nav's thread meanwhile) and [start]s them once its own picture is taken: their creation, on the main thread
     * too, comes after it (started first, home's picture waited behind them: 1.6 s on the emulator). The picture is
     * offered once the bar is on it.
     */
    fun offerHome(start: () -> Unit) {
        synchronized(lock) { handed = null; offered = false }
        val t0 = SystemClock.uptimeMillis()
        val dark = HomeBridge.homeStatusDark
        val ask = HomeBridge.statusBarPicture
        var home: Bitmap? = null
        var homeKnown = false
        var bar: Bitmap? = null
        var barKnown = ask == null
        var homeMs = 0L
        var barMs = 0L
        fun ready() {
            if (!homeKnown || !barKnown) return
            val h = home
            val b = bar
            if (h == null) { offer(null); return }
            baker.execute {
                val pic = if (b == null) h else withBar(h, b)
                AppLog.log("[settings] home's picture ready ${SystemClock.uptimeMillis() - t0} ms after home was asked (home pictured in " +
                    "$homeMs ms, ${if (b == null) "no bar" else "the bar's picture after $barMs ms"})")
                offer(Handed(pic, dark))
            }
        }
        if (ask != null) {
            var done = false
            val timeout = Runnable { if (!done) { done = true; barKnown = true; barMs = SystemClock.uptimeMillis() - t0; ready() } }
            main.postDelayed(timeout, BAR_TIMEOUT_MS)
            ask { b ->
                if (!done) { done = true; main.removeCallbacks(timeout); bar = b; barKnown = true; barMs = SystemClock.uptimeMillis() - t0; ready() }
            }
        }
        val first = HomeBridge.home?.pictureNow()
        start()
        // Home may not picture itself this moment (its clock changing the minute): again on the next frames.
        homePictureSoon(first) { h ->
            home = h
            homeMs = SystemClock.uptimeMillis() - t0
            homeKnown = true
            ready()
        }
    }

    /**
     * Home's picture: [first] if it was taken, else asked again every frame (home declines while its clock changes the
     * minute, or before it has shown once) for [maxMs] at most; null if never. Main thread.
     */
    private fun homePictureSoon(first: Bitmap?, maxMs: Long = PICTURE_RETRY_MS, then: (Bitmap?) -> Unit) {
        if (first != null) { then(first); return }
        val deadline = SystemClock.uptimeMillis() + maxMs
        val choreographer = android.view.Choreographer.getInstance()
        val retry = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val p = HomeBridge.home?.pictureNow()
                when {
                    p != null -> then(p)
                    SystemClock.uptimeMillis() > deadline -> { AppLog.log("[settings] home gave no picture of itself in $maxMs ms"); then(null) }
                    else -> choreographer.postFrameCallback(this)
                }
            }
        }
        choreographer.postFrameCallback(retry)
    }

    /** Home's picture for the settings opening now (null: none could be taken; they open at rest). Any thread. */
    fun offer(h: Handed?) {
        val w = synchronized(lock) {
            val w = waiting
            if (w != null) waiting = null
            else { handed = h; offered = true; offeredAt = SystemClock.uptimeMillis() }
            w
        }
        if (w != null) main.post { w(h) }
    }

    /**
     * The settings opening from home (main thread): [then] gets home's picture once it is offered (at once if it already
     * was), or null after [timeoutMs] (they open at rest, without the slide).
     */
    fun take(timeoutMs: Long, then: (Handed?) -> Unit) {
        var now = false
        var got: Handed? = null
        synchronized(lock) {
            if (offered) {
                now = true
                got = handed.takeIf { SystemClock.uptimeMillis() - offeredAt < STALE_MS }
                handed = null
                offered = false
            } else waiting = then
        }
        if (now) { then(got); return }
        main.postDelayed({
            val late = synchronized(lock) { if (waiting === then) { waiting = null; true } else false }
            if (late) { AppLog.log("[settings] home's picture did not come: opening at rest"); then(null) }
        }, timeoutMs)
    }

    /**
     * Home as it shows now with our status bar across its top ([then] gets it on the main thread; null: home gave no picture
     * of itself, asked again for a moment). Main thread: home renders itself (~10-20 ms), the bar is drawn on its own thread,
     * the two are put together on the GPU off the main thread.
     */
    fun homePicture(then: (Bitmap?) -> Unit) = homePictureSoon(HomeBridge.home?.pictureNow()) { h ->
        if (h == null) then(null) else withBarOn(h, then)
    }

    private fun withBarOn(home: Bitmap, then: (Bitmap?) -> Unit) {
        val ask = HomeBridge.statusBarPicture ?: return then(home)
        var done = false
        val timeout = Runnable { if (!done) { done = true; then(home) } }
        main.postDelayed(timeout, BAR_TIMEOUT_MS)
        ask { bar ->
            if (done) return@ask
            done = true
            main.removeCallbacks(timeout)
            if (bar == null) { then(home); return@ask }
            baker.execute {
                val b = withBar(home, bar)
                main.post { then(b) }
            }
        }
    }

    /** Home's picture [home] with the status bar's [bar] across its top, drawn together on the GPU (any thread but main). */
    fun withBar(home: Bitmap, bar: Bitmap): Bitmap {
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        return dev.launcher.app.design.ViewPicture.draw(home.width, home.height, "home with the bar") { c ->
            c.drawBitmap(home, 0f, 0f, null)
            val k = home.width.toFloat() / bar.width
            c.scale(k, k)
            c.drawBitmap(bar, 0f, 0f, paint)
        } ?: home
    }

    private val watcher = Executors.newSingleThreadExecutor { r -> Thread(r, "settings-shown") }

    /**
     * [then] (main thread) once [component]'s window is on screen: the shell saw the opening transition that brought it
     * committed at or after [since] (uptime ms), and a refresh has passed. Nothing after [timeoutMs] or without the shell.
     */
    fun whenShown(component: String, since: Long, timeoutMs: Long, then: () -> Unit) {
        val s = dev.launcher.app.ShizukuLink.service ?: return
        watcher.execute {
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            var at = 0L
            while (SystemClock.uptimeMillis() < deadline) {
                at = try { s.transitionShownAt(component) } catch (t: Throwable) { AppLog.log("[settings] shell: ${t.message}"); return@execute }
                if (at >= since) { main.postDelayed(then, SHOWN_MARGIN_MS); return@execute }
                Thread.sleep(8)
            }
            AppLog.log("[settings] the shell never saw them come on screen (last ${if (at == 0L) "never" else "${since - at} ms before they were asked for"})")
        }
    }

    /** After the opening transition is committed, its window shows this much later (ms: a refresh at 60 Hz). */
    private const val SHOWN_MARGIN_MS = 17L
    /** An offer older than this is not the settings opening now's (ms). */
    private const val STALE_MS = 3000L
    /** Home declining to picture itself is asked again for this long (ms; a clock's minute change takes ~0.5 s). */
    private const val PICTURE_RETRY_MS = 900L
    /** The bar's picture is waited for this long at most (ms; its thread busy with a frame). */
    private const val BAR_TIMEOUT_MS = 250L
}

/**
 * The slide between home and the settings, for the settings' drawing: home's picture over the whole screen ([enter] 0),
 * shrinking into its place above the sheet as the sheet rises (1: at rest). [enter] is read in drawing and layers only.
 */
@Stable
class EntryState {
    /** 0: home, full screen; 1: the settings at rest. */
    val enter = mutableFloatStateOf(1f)
    /** Home's picture handed over as it slides in: shown at once, until the preview has taken it in (then null). */
    var picture by mutableStateOf<Bitmap?>(null)
    /** A newer picture of home for the preview to take in with its fade (before sliding back into home). */
    var fresh by mutableStateOf<Bitmap?>(null)
    /** Opened from home's edit menu: Back on the tiles goes back into home the same way. */
    var fromHome by mutableStateOf(false)
    /** A slide asked for ([request]), counted for the effect that plays it. */
    var runs by mutableIntStateOf(0)
        private set
    /** The slide is playing. */
    var moving by mutableStateOf(false)
    /** How fast [enter] moves now (per second): a slide that takes another's place goes on from it. */
    var velocity = 0f
    /** Where the slide asked for goes, and what runs when it is there (not if another one took its place). */
    var target = 1f
        private set
    var done: (() -> Unit)? = null

    /** Back at the tiles at once, a page left open closed unseen (set by the settings' layout). */
    var reset: (() -> Unit)? = null
    /** Where the picture covers the status bar no more (0..1 of [enter]); set by the settings' layout. */
    var toneAt = 0.4f
    /** The bar's content over the slide: home's picture under it (true: home), else the page (false). */
    var onTone: ((home: Boolean) -> Unit)? = null
    /** A slide came to rest (any: in, out, or one that took another's place); [moving] is false by then. */
    var onRest: (() -> Unit)? = null

    /** Asks for a slide to [to]; [then] runs once it is there (not if another one takes its place). Main thread. */
    fun request(to: Float, then: (() -> Unit)?) {
        target = to
        done = then
        instant = false
        runs++
    }

    /** At [to] at once: a slide still playing stops, and what was to run at its end does not (a new opening from home). */
    fun jump(to: Float) {
        target = to
        done = null
        instant = true
        enter.floatValue = to
        velocity = 0f
        runs++
    }

    /** The slide asked for last is a [jump]. */
    var instant = false
        private set
}
