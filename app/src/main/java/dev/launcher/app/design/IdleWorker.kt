package dev.launcher.app.design

import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * One background thread that runs tasks in order and ends itself after [idleMs] with nothing to do (a new task starts it
 * again). For workers owned by something that is made anew (the shade, at each restart of gesture nav's service): nothing
 * has to shut them down, so no task still on its way (a GPU callback) is ever refused, and an idle one keeps nothing alive.
 */
object IdleWorker {
    fun create(name: String, priority: Int = Thread.NORM_PRIORITY, idleMs: Long = 2000): Executor =
        ThreadPoolExecutor(0, 1, idleMs, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) { r ->
            Thread(r, name).apply { this.priority = priority; isDaemon = true }
        }
}
