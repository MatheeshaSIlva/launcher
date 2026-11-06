package dev.launcher.app

import android.content.Intent
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Starts that our cards cover, without a system animation and without touching the system animation scales: they go
 * through the shell service's [InstantTransitions] (a remote transition of ours that ends at once).
 *
 * Verified on the fly: shortly after the first starts the service is asked whether the system really handed those
 * transitions to us. If it never did, [usable] turns false and callers go back to the old way (per-launch "no animation"
 * options with the scales switched off around gestures, see GestureNav.holdScalesOff). Blocking binder calls: call from the
 * front queue, never the main or nav thread.
 */
object NoAnimStarts {
    @Volatile var usable = true
        private set
    /** The system was seen calling our transition: from then on nothing global needs to change around gestures. */
    @Volatile var confirmed = false
        private set
    private var prefs: android.content.SharedPreferences? = null
    private var refusedLogs = 0

    /**
     * Remembers across processes that the system hands our transitions over on this phone: a start whose transition another
     * handler takes (a close into picture-in-picture: Android's PiP handler plays it) never calls ours, and the first two
     * starts after an update being such closes turned instant starts off for the whole session (the landscape close's cover
     * could not start).
     */
    fun init(ctx: android.content.Context) {
        val p = ctx.getSharedPreferences("instant_transitions", android.content.Context.MODE_PRIVATE)
        prefs = p
        if (p.getBoolean(KEY_CONFIRMED, false)) confirmed = true
    }
    private val checker = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var checkPending = false

    fun start(intent: Intent, userId: Int): Boolean {
        val ok = call("start ${intent.component?.packageName}") { it.startNoAnim(intent, userId) }
        if (ok) intent.component?.packageName?.let { started[it] = android.os.SystemClock.uptimeMillis() }
        return ok
    }

    // When each package was last started through our transition (uptime ms): a launch waits for its app to show only then.
    private val started = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** When [pkg] was last started through our transition (uptime ms), else 0. */
    fun startedAt(pkg: String): Long = started[pkg] ?: 0L

    fun switchToTask(taskId: Int): Boolean = call("switch to task $taskId") { it.switchToTaskNoAnim(taskId) }

    /** A notification's tap (its PendingIntent) without a system animation. */
    fun send(pi: android.app.PendingIntent): Boolean = call("send ${pi.creatorPackage}") { it.sendNoAnim(pi) }

    private inline fun call(what: String, op: (IShellService) -> String): Boolean {
        if (!usable) { if (refusedLogs++ < 3) AppLog.log("[nav] $what: our own transitions are off for this session"); return false }
        val s = ShizukuLink.service ?: return false
        val r = try { op(s) } catch (t: Throwable) { "ERROR: ${t.javaClass.simpleName}: ${t.message}" }
        if (r.startsWith("ERROR")) {
            AppLog.log("[nav] $what without system animation failed ($r); using the old way for this one")
            return false
        }
        // Home's task brought back without a starting window (InstantTransitions.start): said in the log the first times.
        if (r.contains("home's task") && homeLogs++ < 3) AppLog.log("[nav] home brought back from its task, no starting window: $r")
        verifySoon(s)
        return true
    }

    private var homeLogs = 0

    private var checks = 0

    /**
     * After a start, check that the system called our transition. The first few checks are logged in full (requests vs.
     * invoked shows whether some kinds of start, e.g. task switches, are not handed over); if none ever was, give up.
     */
    private fun verifySoon(s: IShellService) {
        if (checkPending || checks >= 6) return
        checkPending = true
        checker.schedule({
            checkPending = false
            checks++
            val stats = try { s.noAnimStats() } catch (t: Throwable) { "ERROR: ${t.message}" }
            val requests = Regex("""requests=(\d+)""").find(stats)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val invoked = Regex("""invoked=(\d+)""").find(stats)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            when {
                invoked > 0 -> {
                    if (!confirmed) AppLog.log("[nav] starts skip the system animation through our own transition; system animation scales stay as they are")
                    confirmed = true
                    prefs?.edit()?.putBoolean(KEY_CONFIRMED, true)?.apply()
                    AppLog.log("[nav] own transitions: $stats")
                }
                // Never seen called after a good number of starts (some are taken by other handlers: picture-in-picture),
                // and never on this phone before.
                requests >= 6 && !confirmed -> {
                    usable = false
                    AppLog.log("[nav] the system did not hand our transitions over ($stats): back to switching system animations off around gestures")
                }
            }
        }, 1500, TimeUnit.MILLISECONDS)
    }

    private const val KEY_CONFIRMED = "confirmed"
}
