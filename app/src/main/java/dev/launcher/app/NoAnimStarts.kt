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
    private val checker = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var checkPending = false

    fun start(intent: Intent, userId: Int): Boolean = call("start ${intent.component?.packageName}") { it.startNoAnim(intent, userId) }

    fun switchToTask(taskId: Int): Boolean = call("switch to task $taskId") { it.switchToTaskNoAnim(taskId) }

    /** A notification's tap (its PendingIntent) without a system animation. */
    fun send(pi: android.app.PendingIntent): Boolean = call("send ${pi.creatorPackage}") { it.sendNoAnim(pi) }

    private inline fun call(what: String, op: (IShellService) -> String): Boolean {
        if (!usable) return false
        val s = ShizukuLink.service ?: return false
        val r = try { op(s) } catch (t: Throwable) { "ERROR: ${t.javaClass.simpleName}: ${t.message}" }
        if (r.startsWith("ERROR")) {
            AppLog.log("[nav] $what without system animation failed ($r); using the old way for this one")
            return false
        }
        verifySoon(s)
        return true
    }

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
                    AppLog.log("[nav] own transitions: $stats")
                }
                requests >= 2 -> {
                    usable = false
                    AppLog.log("[nav] the system did not hand our transitions over ($stats): back to switching system animations off around gestures")
                }
            }
        }, 1500, TimeUnit.MILLISECONDS)
    }
}
