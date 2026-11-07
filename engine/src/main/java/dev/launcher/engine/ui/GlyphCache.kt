package dev.launcher.engine.ui

/**
 * The `icon` primitive's prepared glyphs (T-005, slice 9): what [IconPainter] reads while measuring and drawing, never a
 * file or a parse. An immutable snapshot, (set, symbol) to [Result], replaced whole on the worker after each load and read
 * without a lock by any thread (`@Volatile`). Absent means pending; [Missing] is terminal (the set has no such symbol, or
 * the set could not be read or parsed) and is never loaded again.
 *
 * Requests ([prepare]) come from the owning thread and are deduplicated per key while in flight; their completion runs on
 * the owning thread, and is dropped once the cache is disposed or when the requester says it no longer wants it (its
 * binding moved on). A symbol missing from the requested set falls back to [FALLBACK]'s only once the miss is confirmed:
 * [prepare] loads both.
 */
class GlyphCache<G : Any>(
    /** On the worker: the glyph, or null when the set has no such symbol or cannot be read (a throw counts as null). */
    private val load: (set: String, symbol: String) -> G?,
    /** Runs a load off the owning thread. */
    private val worker: (Runnable) -> Unit,
    /** Runs a completion on the owning thread. */
    private val owner: (Runnable) -> Unit,
) {
    data class Key(val set: String, val symbol: String)

    sealed interface Result<out G>
    class Ready<G>(val glyph: G) : Result<G>
    object Missing : Result<Nothing>

    /** What a painter draws for a key: the glyph (maybe the fallback's), nothing for good, or nothing yet. */
    sealed interface Resolved<out G>
    data class Found<G>(val glyph: G) : Resolved<G>
    object Absent : Resolved<Nothing>
    object Pending : Resolved<Nothing>

    @Volatile private var snapshot: Map<Key, Result<G>> = emptyMap()
    private val publish = Any()
    /** Owning thread only. */
    /** Loads under way: written by the owning thread only, read by any (an in-flight key stays [Pending] when [full]). */
    @Volatile private var inFlight: Set<Key> = emptySet()
    private val waiting = ArrayList<Waiter>()
    private var disposed = false
    /** Requesters waiting now (the waiters' bound's test). */
    val waiters: Int get() = waiting.size
    /** Loads started (each key at most once while the cache lives): the test's measure of "never prepared again". */
    @Volatile var loads = 0; private set

    private class Waiter(val keys: Set<Key>, val wanted: () -> Boolean, val done: () -> Unit)

    fun result(key: Key): Result<G>? = snapshot[key]

    /** No new key is loaded once [MAX_KEYS] are known or in flight: an unknown one then resolves [Absent] for good. */
    @Volatile private var full = false

    /**
     * Lock-free: [key]'s glyph, else [FALLBACK]'s after a confirmed miss, else [Absent] or [Pending]. Any thread. A key
     * that is not a valid name ([valid]) is [Absent] and never loaded (a KDL value never reaches a file path unchecked).
     */
    fun resolve(key: Key): Resolved<G> {
        if (!valid(key)) return Absent
        val s = snapshot
        return when (val own = s[key]) {
            is Ready -> Found(own.glyph)
            null -> if (full && key !in inFlight) Absent else Pending
            Missing -> if (key.set == FALLBACK) Absent else when (val fb = s[Key(FALLBACK, key.symbol)]) {
                is Ready -> Found(fb.glyph)
                Missing -> Absent
                null -> if (full && Key(FALLBACK, key.symbol) !in inFlight) Absent else Pending
            }
        }
    }

    /**
     * Owning thread. Loads every key of [keys] (and its fallback) not yet known nor in flight; [done] runs on the owning
     * thread once all of them resolve to something other than [Pending], at once if they already do, never after [dispose]
     * or once [wanted] says false. Returns whether [done] already ran.
     */
    fun prepare(keys: Collection<Key>, wanted: () -> Boolean = { true }, done: () -> Unit): Boolean {
        if (disposed) return false
        val all = keys.flatMapTo(LinkedHashSet()) { k -> if (k.set == FALLBACK) listOf(k) else listOf(k, Key(FALLBACK, k.symbol)) }
        if (keys.none { resolve(it) == Pending }) { done(); return true }
        for (k in all) if (valid(k) && snapshot[k] == null && !inFlight.contains(k)) {
            if (snapshot.size + inFlight.size >= MAX_KEYS) { full = true; break }
            inFlight += k
            loads++
            worker(Runnable {
                val r: Result<G> = try { load(k.set, k.symbol)?.let { Ready(it) } ?: Missing } catch (_: Throwable) { Missing }
                synchronized(publish) { snapshot = snapshot + (k to r) }
                owner(Runnable { inFlight -= k; settle() })
            })
        }
        if (full) settle()   // what could not be loaded is absent now: its waiters hear at once
        // The cache just filled: what this requester asked for is absent for good, so it hears now and never waits.
        if (keys.none { resolve(it) == Pending }) { done(); return true }
        // A requester that moved on no longer waits (a name flipping while its loads are held adds nothing), and the
        // waiters stay bounded: past MAX_WAITERS the oldest is dropped untold (it draws when anything redraws it).
        waiting.removeAll { !it.wanted() }
        while (waiting.size >= MAX_WAITERS) waiting.removeAt(0)
        waiting += Waiter(keys.toSet(), wanted, done)
        return false
    }

    /** Completions after this run nothing; loads already started still publish (harmless, the cache is dropped). */
    fun dispose() { disposed = true; waiting.clear() }

    private fun valid(k: Key) = SET.matches(k.set) && SYMBOL.matches(k.symbol) && ".." !in k.symbol

    private fun settle() {
        if (disposed) return
        val ready = waiting.filter { w -> w.keys.none { resolve(it) == Pending } }
        waiting -= ready.toSet()
        for (w in ready) if (w.wanted()) w.done()
    }

    companion object {
        /** The engine's own status-bar set: the default `set`, and where a symbol missing from another set is looked for. */
        const val FALLBACK = "statusbar"
        /** How many glyphs (found, missing or loading) one component may ever ask for: its icons' names come from data. */
        const val MAX_KEYS = 256
        /** Requesters that may wait at once (a component's icons and its preparation). */
        const val MAX_WAITERS = 256
        /** An icon set's id (a file name under `icons/`, no separator, no dot) and a symbol's name (`wifi.3`, `cell.off`). */
        private val SET = Regex("[a-z0-9][a-z0-9_-]{0,63}")
        private val SYMBOL = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    }
}
