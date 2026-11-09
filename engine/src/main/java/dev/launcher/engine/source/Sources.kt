package dev.launcher.engine.source

import dev.launcher.engine.expr.Value
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/** A source's value: plain data (never an Android object), read by expressions member by member (`battery.level`). */
interface SourceValue {
    fun member(name: String): Value
}

/**
 * A named source of values (docs/E2_PLAN.md §c): [values] is cold (collecting it registers the Android callback, and
 * cancelling unregisters it), conflated and without repeats; [current] is the value for the first frame, before the first
 * delivery. [capability] is what reading it needs (`none`, `network.read`, `notifications.read`).
 */
class Source<T : SourceValue>(val name: String, val capability: String, private val now: () -> T, values: Flow<T>) {
    val values: Flow<T> = values.conflate().distinctUntilChanged()
    fun current(): T = now()
}

/** What a component may reach: declared and granted. The one rule, asked before anything is read, made or loaded. */
object Access {
    fun allowed(declared: Collection<String>, source: String, capability: String, granted: Set<String>) =
        source in declared && (capability == "none" || capability in granted)

    /** [make], only if [allowed]; otherwise null, and nothing is made. */
    inline fun <T> ifAllowed(declared: Collection<String>, source: String, capability: String, granted: Set<String>, make: () -> T): T? =
        if (allowed(declared, source, capability, granted)) make() else null
}

/** The sources by name. */
class Sources(all: List<Source<*>>) {
    private val byName = all.associateBy { it.name }
    operator fun get(name: String): Source<*>? = byName[name]
    val names: Set<String> get() = byName.keys
    operator fun plus(more: Source<*>) = Sources(byName.values + more)

    companion object {
        /** Fixed values (tests, and later the pixel harness's states): each source holds its one value. */
        fun fixture(values: Map<String, SourceValue>) = Sources(values.map { (name, v) -> Source(name, "none", { v }, flowOf(v)) })
    }
}

/** A system broadcast as a source sees it: its action and its whole-number extras. */
class Broadcast(val action: String, private val ints: (String, Int) -> Int) {
    fun int(name: String, default: Int) = ints(name, default)
}

/** Registers for broadcasts ([AndroidReceivers] in the app, a fake in tests). */
interface Receivers {
    /** Delivers each broadcast of [actions] (a sticky one's last at once) until the returned function is called. */
    fun register(actions: List<String>, deliver: (Broadcast) -> Unit): () -> Unit
    /** The last sticky broadcast of [action], without registering, or null. */
    fun sticky(action: String): Broadcast?
}

/** [read] applied to every broadcast of [actions], while collected; a null from it delivers nothing. */
fun <T> Receivers.flow(actions: List<String>, read: (Broadcast) -> T?): Flow<T> = callbackFlow {
    val off = register(actions) { b -> read(b)?.let { trySend(it) } }
    awaitClose(off)
}
