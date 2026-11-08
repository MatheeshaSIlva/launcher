package dev.launcher.app

/**
 * What gives the launcher its shell-uid [IShellService] (T-001 slice 2): Shizuku's user service ([ShizukuHost], the
 * default until slice 5) or our own process ([OwnHost], behind a developer switch). [ShizukuLink] keeps the app's one
 * face over whichever is selected and runs the one connect path (grants, watchdog, flags) for both; a host only finds
 * the service and reports it through [Sink].
 */
interface ShellHost {
    companion object {
        /**
         * Stops [service] off this thread, so the system drops the flags it holds; what happened is logged within
         * [dev.launcher.engine.shell.BoundedStop]'s deadline. If it did not stop, the flags stay: the log says what clears them.
         */
        fun stop(service: IShellService, why: String) {
            Thread({
                val outcome = dev.launcher.engine.shell.BoundedStop.run({ service.destroy() }, { it is android.os.DeadObjectException })
                AppLog.log("[shell] $why: our shell service " + when (outcome) {
                    dev.launcher.engine.shell.BoundedStop.Outcome.STOPPED -> "stopped"
                    else -> "did NOT stop ($outcome): tap Restore system in the launcher's notification, or restart the phone"
                })
            }, "shell-stop").apply { isDaemon = true; start() }
        }
    }

    /** What a host reports to [ShizukuLink]. [generation] is the host's when it was selected: stale reports are ignored. */
    interface Sink {
        fun connected(generation: Int, service: IShellService)
        fun lost(generation: Int)
        fun changed()
    }

    /** Called once, on the main thread, when the host is selected; its callbacks report under [generation]. */
    fun init(app: android.app.Application, sink: Sink, generation: Int)

    /** Its state while no service is connected (`CONNECTED` is [ShizukuLink]'s, from the service it holds). */
    fun state(): ShizukuLink.State

    /** Asks for whatever the host needs (permission, a start), then binds. Safe to call repeatedly: joins one in flight. */
    fun connect()

    /** Binds if it can without asking the user (a process start's `awaitService`); main thread. */
    fun bindQuietly()
}
