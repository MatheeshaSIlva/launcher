package dev.launcher.app.shade.nc

import android.view.Choreographer
import dev.launcher.app.motion.MotionClock

/**
 * Notification Center's choreography as numbers, frame by frame (docs/PLAN_PANEL_TRANSITIONS.md, step 2b): what is drawn
 * (the edge, the content's opacity and whether it waits, the dim below the edge, the cover, the picture giving way, the
 * status bar, the live blur, the clock's offset, the date's opacity) at every frame while it is on, with the events that
 * started each part. Moving it onto keyframe tracks must reproduce these curves; `tools/nc_trace_compare.py` compares two.
 *
 * While it records, motion runs on a fixed step a frame ([MotionClock], [STEP_NS]): two runs on a busy emulator then move
 * alike whatever the frames took, and times are that clock's. A test hook only (`TEST_SHADE --es do nc-trace --ez on
 * true|false`): written to `files/dev-out/nc-trace.csv`.
 */
class NcTrace(private val sample: () -> FloatArray) {
    private val rows = StringBuilder()
    var on = false
        private set

    private var poster: android.os.Handler? = null

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!on) return
            val t = MotionClock.frame(frameTimeNanos)
            // Sampled once every motion has had this frame: in the frame's last phase (its commit callbacks, after the
            // animations and the layout). Sampled among the animations, a value whose callback came later was a frame old
            // (which came first depended on what moved when it started); as a message after the frame, a late frame's next
            // frame ran first (frames jump the queue).
            val sampleNow = Runnable {
                if (!on) return@Runnable
                val v = sample()
                rows.append(fmt(t)).append(",frame")
                for (x in v) rows.append(',').append(String.format(java.util.Locale.ROOT, "%.5f", x))
                rows.append('\n')
            }
            val committed = try { postCommit?.invoke(Choreographer.getInstance(), CALLBACK_COMMIT, sampleNow, null); postCommit != null } catch (_: Throwable) { false }
            if (!committed) poster?.post(sampleNow)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** `Choreographer.postCallback(type, action, token)` (hidden): for the commit phase. A test hook only. */
    private val postCommit: java.lang.reflect.Method? = try {
        Choreographer::class.java.getMethod("postCallback", Int::class.javaPrimitiveType, Runnable::class.java, Any::class.java)
    } catch (_: Throwable) { null }

    /** Starts recording (on the shade's thread: its Choreographer is the one its motion runs on). */
    fun start() {
        rows.setLength(0)
        rows.append("ms,event,").append(COLUMNS.joinToString(",")).append('\n')
        MotionClock.fixed(STEP_NS)
        poster = android.os.Handler(android.os.Looper.myLooper()!!)
        on = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    /** An event (pull, commit, close, landed, closed), at the time it happened. */
    fun mark(event: String) {
        if (on) rows.append(fmt(MotionClock.now())).append(',').append(event).append('\n')
    }

    /** Stops (motion back on real time) and writes the trace to [file]. */
    fun stop(file: java.io.File) {
        on = false
        MotionClock.fixed(0L)
        file.parentFile?.mkdirs()
        file.writeText(rows.toString())
    }

    private fun fmt(ns: Long) = String.format(java.util.Locale.ROOT, "%.2f", ns / 1e6)

    companion object {
        /** One frame of motion while recording: 60 Hz, the emulator's refresh. */
        const val STEP_NS = 16_666_667L
        /** `Choreographer.CALLBACK_COMMIT`: the frame's last phase. */
        private const val CALLBACK_COMMIT = 4
        /** What [sample] returns, in this order. */
        val COLUMNS = listOf("progress", "edge", "content", "waits", "dim", "cover", "picture", "zoom", "bar", "live", "clockDy", "date")
    }
}
