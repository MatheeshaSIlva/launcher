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

    /**
     * The bar's charging moment began: it ends when One UI's charging animation has (its "Resetting animation state"), or
     * `motion.statusbar.charge-hold` after the plug-in when that animation never starts within [START_WAIT_MS] (a moment
     * that covers nothing still plays out as one: it grows, shows and shrinks back).
     */
    fun chargeMoment(s: IShellService?) {
        val t0 = android.os.SystemClock.uptimeMillis()
        fun end(why: String, at: Long) {
            AppLog.log("[statusbar] charging moment ends: $why")
            handler.postAtTime({ bar()?.endChargeMoment() }, at)
        }
        if (s == null) { end("no shell (One UI's animation's usual length)", t0 + ONE_UI_ANIMATION_MS); return }
        io.execute {
            val started = try { s.awaitLog(TAG, "animationStarted|skipping|cancelling", START_WAIT_MS) } catch (_: Throwable) { "" }
            if (!started.contains("animationStarted")) {
                end(if (started.isEmpty()) "One UI's charging animation did not start" else "One UI's charging animation skipped",
                    t0 + Motion.ms(MotionTokens.STATUSBAR_CHARGE_HOLD))
                return@execute
            }
            val done = try { s.awaitLog(TAG, "Resetting animation state|cleaning up|cancelling", END_WAIT_MS) } catch (_: Throwable) { "" }
            end(if (done.isEmpty()) "One UI's charging animation never said it ended" else "One UI's charging animation ended",
                android.os.SystemClock.uptimeMillis() + AFTER_END_MS)
        }
    }

    private companion object {
        const val TAG = "ChargingVI"
        const val START_WAIT_MS = 1500
        const val END_WAIT_MS = 7000
        /** How long One UI's charging animation plays (S24, One UI 9: 3.9-4.0 s from the plug-in to its reset). */
        const val ONE_UI_ANIMATION_MS = 4200L
        /** After One UI's animation reset itself: its last frame is gone. */
        const val AFTER_END_MS = 60L
    }
}
