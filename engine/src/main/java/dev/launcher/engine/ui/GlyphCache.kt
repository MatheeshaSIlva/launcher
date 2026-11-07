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
    private val inFlight = HashSet<Key>()
    private val waiting = ArrayList<Waiter>()
    private var disposed = false
    /** Loads started (each key at most once while the cache lives): the test's measure of "never prepared again". */
    @Volatile var loads = 0; private set

    private class Waiter(val keys: Set<Key>, val wanted: () -> Boolean, val done: () -> Unit)

    fun result(key: Key): Result<G>? = snapshot[key]

    /** Lock-free: [key]'s glyph, else [FALLBACK]'s after a confirmed miss, else [Absent] or [Pending]. Any thread. */
    fun resolve(key: Key): Resolved<G> {
        val s = snapshot
        return when (val own = s[key]) {
            is Ready -> Found(own.glyph)
            null -> Pending
            Missing -> if (key.set == FALLBACK) Absent else when (val fb = s[Key(FALLBACK, key.symbol)]) {
                is Ready -> Found(fb.glyph)
                Missing -> Absent
                null -> Pending
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
        waiting += Waiter(keys.toSet(), wanted, done)
        for (k in all) if (snapshot[k] == null && inFlight.add(k)) {
            loads++
            worker(Runnable {
                val r: Result<G> = try { load(k.set, k.symbol)?.let { Ready(it) } ?: Missing } catch (_: Throwable) { Missing }
                synchronized(publish) { snapshot = snapshot + (k to r) }
                owner(Runnable { inFlight -= k; settle() })
            })
        }
        return false
    }

    /** Completions after this run nothing; loads already started still publish (harmless, the cache is dropped). */
    fun dispose() { disposed = true; waiting.clear() }

    private fun settle() {
        if (disposed) return
        val ready = waiting.filter { w -> w.keys.none { resolve(it) == Pending } }
        waiting -= ready.toSet()
        for (w in ready) if (w.wanted()) w.done()
    }

    companion object {
        /** The engine's own status-bar set: the default `set`, and where a symbol missing from another set is looked for. */
        const val FALLBACK = "statusbar"
    }
}
