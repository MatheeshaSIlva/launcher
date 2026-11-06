package dev.launcher.engine.source

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import dev.launcher.engine.expr.Deps
import dev.launcher.engine.expr.Env
import dev.launcher.engine.expr.Value
import java.util.TimeZone
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Calls back once at the next frame. */
fun interface FrameClock {
    fun next(frame: () -> Unit)
}

/** The display's frames, on the calling thread's Choreographer (the binder's Looper). */
object ChoreographerClock : FrameClock {
    override fun next(frame: () -> Unit) = Choreographer.getInstance().postFrameCallback { frame() }
}

/**
 * Feeds a component's sources into its bindings: collects each source in `uses` on [dispatcher], keeps the latest
 * values (read by [env]), and coalesces deliveries to one refresh per frame of [clock], naming only the sources that
 * changed, so only the bindings that read them are evaluated again.
 */
class SourceBinder(
    private val sources: Sources,
    private val clock: FrameClock,
    private val dispatcher: CoroutineDispatcher,
) {
    private val latest = HashMap<String, SourceValue>()
    private val changed = HashSet<String>()
    private var scheduled = false
    private var scope: CoroutineScope? = null
    private var zoneFor: Pair<String, TimeZone>? = null

    companion object {
        /** A binder collecting on [looper]'s thread, framed by its Choreographer. */
        fun onLooper(sources: Sources, looper: Looper) = SourceBinder(sources, ChoreographerClock, Handler(looper).asCoroutineDispatcher())
    }

    /** [base] with sources, `now` and the zone read from the latest values (`battery.level`: source `battery`, `level`). */
    fun env(base: Env): Env = object : Env by base {
        override fun source(path: String): Value =
            latest[path.substringBefore('.')]?.member(path.substringAfter('.', "")) ?: base.source(path)
        override fun now(): Long = (latest["time"] as? Time)?.epochMs ?: base.now()
        override val zone: TimeZone get() = (latest["time"] as? Time)?.let { t ->
            zoneFor?.takeIf { it.first == t.zoneId }?.second ?: TimeZone.getTimeZone(t.zoneId).also { zoneFor = t.zoneId to it }
        } ?: base.zone
    }

    /** Starts collecting [names] (unknown ones are the validator's to refuse); [refresh] is `ComponentHost.refresh`. */
    fun start(names: Collection<String>, refresh: ((Deps) -> Boolean) -> Unit): Job {
        val job = SupervisorJob()
        scope = CoroutineScope(dispatcher + job)
        for (name in names) {
            val source = sources[name] ?: continue
            latest[name] = source.current()
            scope!!.launch { source.values.collect { v -> deliver(name, v, refresh) } }
        }
        return job
    }

    fun stop() { scope?.cancel(); scope = null; changed.clear(); scheduled = false }

    private fun deliver(name: String, v: SourceValue, refresh: ((Deps) -> Boolean) -> Unit) {
        if (latest[name] == v) return
        latest[name] = v
        changed += name
        if (scheduled) return
        scheduled = true
        clock.next {
            scheduled = false
            if (scope == null) return@next
            val names = HashSet(changed).also { changed.clear() }
            refresh { deps -> (deps.clock && "time" in names) || deps.sources.keys.any { it.substringBefore('.') in names } }
        }
    }
}
