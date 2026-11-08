package dev.launcher.engine.shell

/**
 * Stopping the shell service without trusting it to answer (T-001 slice 3): [stop] runs on its own daemon thread, and the
 * outcome is known within [deadlineMs] whatever it does. A service that exits inside the call answers with a dead binder,
 * which is the success ([isDeadBinder]); one that throws something else, or never returns, did not stop. The flags it holds
 * then stay until the phone's Restore notification (`SystemRestore`) or a reboot clears them: the caller says so.
 */
object BoundedStop {
    const val DEADLINE_MS = 5_000L

    enum class Outcome { STOPPED, FAILED, TIMED_OUT }

    fun run(stop: () -> Unit, isDeadBinder: (Throwable) -> Boolean, deadlineMs: Long = DEADLINE_MS): Outcome {
        var outcome: Outcome? = null
        val call = Thread({
            val o = try { stop(); Outcome.STOPPED } catch (t: Throwable) { if (isDeadBinder(t)) Outcome.STOPPED else Outcome.FAILED }
            synchronized(this) { outcome = o }
        }, "shell-stop-call").apply { isDaemon = true; start() }
        call.join(deadlineMs)
        return synchronized(this) { outcome } ?: Outcome.TIMED_OUT
    }
}
