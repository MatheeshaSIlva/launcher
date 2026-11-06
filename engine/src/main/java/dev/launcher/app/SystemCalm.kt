package dev.launcher.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Whether the system has finished with a change that reaches every app at once (a new palette, light and dark). While it
 * hands one out, it answers late every call that needs its activity manager (a receiver registered or dropped: 116 ms on
 * the emulator after Other apps gave Android a palette, several in a row on our main thread as a clock widget's views went
 * and came: home froze a moment). [after] runs once such a call is answered at once again: a receiver of our own registered
 * and dropped from a worker thread, timed, until two in a row take under [CALM_MS].
 */
object SystemCalm {
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "system-calm").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    private val probe = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) {} }

    /** Runs [then] on the main thread once the system answers at once again, [minMs] from now at the earliest ([MAX_MS] at most). */
    fun after(ctx: Context, minMs: Long, then: () -> Unit) {
        val app = ctx.applicationContext
        val start = SystemClock.uptimeMillis()
        var calm = 0
        var slowest = 0L
        lateinit var step: Runnable
        step = Runnable {
            val took = timeOne(app)
            slowest = maxOf(slowest, took)
            calm = if (took < CALM_MS) calm + 1 else 0
            val waited = SystemClock.uptimeMillis() - start
            if (calm >= 2 || waited >= MAX_MS) {
                if (slowest >= CALM_MS) AppLog.log("[app] the system was busy for ${waited - minMs} ms after a change for every app (slowest call $slowest ms)" + if (calm < 2) ": went on anyway" else "")
                main.post(then)
            } else worker.schedule(step, PROBE_MS, TimeUnit.MILLISECONDS)
        }
        worker.schedule(step, minMs, TimeUnit.MILLISECONDS)
    }

    /** One call that needs the activity manager, timed (ms). */
    private fun timeOne(c: Context): Long {
        val t0 = SystemClock.uptimeMillis()
        try {
            val filter = IntentFilter(PROBE_ACTION)
            if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(probe, filter, Context.RECEIVER_NOT_EXPORTED) else c.registerReceiver(probe, filter)
            c.unregisterReceiver(probe)
        } catch (_: Throwable) { }
        return SystemClock.uptimeMillis() - t0
    }

    private const val PROBE_ACTION = "dev.launcher.app.CALM_PROBE"
    /** A call answered within this is the system at rest (ms; ~1 ms when it is). */
    private const val CALM_MS = 12L
    /** Between two probes (ms). */
    private const val PROBE_MS = 120L
    /** Never waits longer than this (ms). */
    private const val MAX_MS = 8000L
}
