package dev.launcher.app.statusbar

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.view.Display
import dev.launcher.app.AppLog
import dev.launcher.app.IProjectionWatch
import dev.launcher.app.IShellService
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionTokens
import java.util.concurrent.Executors

/**
 * When our bar covers the parts of One UI's own bar that no disable flag hides (the rest: StockBarSettings):
 * - sharing: a screen capture is running (the shell watches MediaProjection) or a presentation display is on (Smart View,
 *   an external display): One UI then shows its call and cast chips whatever our flags say, and our bar puts the time in its
 *   sharing capsule over them;
 * - the charging moment: One UI's charging animation logs when it started and ended (read by the shell), and the moment
 *   ends with it, or soon after the plug-in when it never starts (a panel open, the lock screen).
 * Calls into the bar arrive on [bar]'s thread ([handler]).
 */
class StockBarCover(private val ctx: Context, private val handler: Handler, private val bar: () -> StatusBarView?) {
    private val io = Executors.newSingleThreadExecutor()
    /** The charging moments' waits on One UI's log, each on a thread of its own (a newer one never waits behind an older). */
    private val watchers = Executors.newCachedThreadPool()
    private var capturing = ""
    private var presenting = false

    private val projection = object : IProjectionWatch.Stub() {
        override fun onProjection(pkg: String) {
            handler.post {
                if (pkg == capturing) return@post
                AppLog.log("[statusbar] screen capture ${if (pkg.isEmpty()) "ended" else "by $pkg"}")
                capturing = pkg
                push()
            }
        }
    }

    private val displays = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = readDisplays()
        override fun onDisplayRemoved(id: Int) = readDisplays()
        override fun onDisplayChanged(id: Int) {}
    }

    private val dm get() = ctx.getSystemService(DisplayManager::class.java)

    init {
        try { dm.registerDisplayListener(displays, handler) } catch (_: Throwable) { }
        handler.post { readDisplays() }
    }

    private fun readDisplays() {
        val now = try {
            dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).any { it.displayId != Display.DEFAULT_DISPLAY }
        } catch (_: Throwable) { false }
        if (now == presenting) return
        AppLog.log("[statusbar] presentation display ${if (now) "on" else "off"}")
        presenting = now
        push()
    }

    /** The shell came ([s]) or went (null): the capture watch starts again with it. */
    fun shellChanged(s: IShellService?) {
        if (s == null) { handler.post { capturing = ""; push() }; return }
        io.execute {
            val r = try { s.watchProjection(projection) } catch (t: Throwable) { "ERROR: ${t.javaClass.simpleName}: ${t.message}" }
            if (r != "ok") AppLog.log("[statusbar] screen capture watch: $r")
        }
    }

    /** The bar's state now (after it was made again, or a change). On [bar]'s thread. */
    fun push() { bar()?.setSharing(capturing.isNotEmpty() || presenting) }

    /** One UI's charging animation may be playing now (from a plug-in until its log says it ended, or our waits ran out). */
    @Volatile var oneUiActive = false
        private set
    /** Which plug-in's wait is the current one (a plug-in again while one waits starts another: only the newest ends it). */
    @Volatile private var generation = 0

    /**
     * The bar's charging moment began (or a plug-in came again while it lasted): it ends when One UI's charging animation
     * has (its "Resetting animation state"), or `motion.statusbar.charge-hold` after the plug-in when that animation never
     * starts within [START_WAIT_MS] (a moment that covers nothing still plays out as one: it grows, shows and shrinks back).
     * After a plug-in again One UI may reset the animation playing and start another: then the moment waits
     * [RESTART_GRACE_MS] after a reset for that.
     * Lines from before the plug-in are not taken (the shell reads its log from half a second back). On the bar's thread.
     */
    fun chargeMoment(s: IShellService?) {
        val t0 = android.os.SystemClock.uptimeMillis()
        val from = System.currentTimeMillis() - PLUG_SLACK_MS
        val mine = ++generation
        // (A plug-in again while One UI's animation may still play: that one may end first, and none start.)
        val again = oneUiActive
        oneUiActive = true
        fun end(why: String, at: Long) {
            if (mine != generation) return
            AppLog.log("[statusbar] charging moment ends: $why")
            handler.postAtTime({
                if (mine != generation) return@postAtTime
                oneUiActive = false
                bar()?.endChargeMoment()
            }, at)
        }
        if (s == null) { end("no shell (One UI's animation's usual length)", t0 + ONE_UI_ANIMATION_MS); return }
        watchers.execute {
            var first = next(s, "$STARTED|skipping|$ENDED", from, if (again) END_WAIT_MS else START_WAIT_MS)
            if (first.isEmpty() || first.contains("skipping")) {
                end(when {
                    first.contains("skipping") -> "One UI's charging animation skipped"
                    again -> "One UI's charging animation said nothing more"
                    else -> "One UI's charging animation did not start"
                }, if (again) android.os.SystemClock.uptimeMillis() else t0 + Motion.ms(MotionTokens.STATUSBAR_CHARGE_HOLD))
                return@execute
            }
            // (A reset first: One UI ended one playing as ours began, and may start another.)
            val grace = again || Regex(ENDED).containsMatchIn(first)
            while (mine == generation) {
                // Playing (or, after a plug-in again, the earlier one ending): its end, then a moment for a start again.
                val ended = if (Regex(ENDED).containsMatchIn(first)) first else next(s, ENDED, epochOf(first), END_WAIT_MS)
                if (ended.isEmpty()) { end("One UI's charging animation never said it ended", android.os.SystemClock.uptimeMillis()); return@execute }
                // (Only after a plug-in again: One UI may reset the animation playing and start another. A plug-in on its own
                // starts one, which ends once: the moment ends with it.)
                val restart = if (grace) next(s, STARTED, epochOf(ended), RESTART_GRACE_MS) else ""
                if (restart.isEmpty()) {
                    end("One UI's charging animation ended", android.os.SystemClock.uptimeMillis() + AFTER_END_MS)
                    return@execute
                }
                AppLog.log("[statusbar] One UI's charging animation started again: the capsule stays")
                first = restart
            }
        }
    }

    /**
     * The next ChargingVI line matching [regex] logged at or after [notBefore] (epoch ms), within [timeoutMs]; "" if none.
     * A line from before is let go: once it is out of the shell's half second back the wait is asked again.
     */
    private fun next(s: IShellService, regex: String, notBefore: Long, timeoutMs: Int): String {
        val until = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = until - System.currentTimeMillis()
            if (left <= 0) return ""
            val line = try { s.awaitLog(TAG, regex, left.toInt()) } catch (_: Throwable) { return "" }
            if (line.isEmpty() || line.startsWith("ERROR")) return ""
            val at = epochOf(line)
            if (at < 0 || at >= notBefore) return line
            val wait = (at + LOOKBACK_MS - System.currentTimeMillis()).coerceAtMost(until - System.currentTimeMillis())
            if (wait > 0) try { Thread.sleep(wait) } catch (_: InterruptedException) { return "" }
        }
    }

    /** When a line was logged (epoch ms; the shell's log is read with `-v epoch`), or -1. */
    private fun epochOf(line: String): Long = line.trim().substringBefore(' ').toDoubleOrNull()?.let { (it * 1000).toLong() } ?: -1L

    private companion object {
        const val TAG = "ChargingVI"
        const val STARTED = "animationStarted"
        const val ENDED = "Resetting animation state|cleaning up|cancelling"
        const val START_WAIT_MS = 1500
        const val END_WAIT_MS = 7000
        /** How long One UI's charging animation plays (S24, One UI 9: 3.9-4.0 s from the plug-in to its reset). */
        const val ONE_UI_ANIMATION_MS = 4200L
        /** After One UI's animation reset itself: its last frame is gone. */
        const val AFTER_END_MS = 60L
        /** How long after a reset One UI is given to start again (a plug-in again restarts its animation at once). */
        const val RESTART_GRACE_MS = 300
        /** Lines this much before our plug-in still count (our broadcast and One UI's come about together). */
        const val PLUG_SLACK_MS = 100L
        /** The shell reads its log from this far back (ShellService.awaitLog), and a little more. */
        const val LOOKBACK_MS = 520L
    }
}
