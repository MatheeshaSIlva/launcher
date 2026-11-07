package dev.launcher.engine.ui

import dev.launcher.engine.ui.GlyphCache.Absent
import dev.launcher.engine.ui.GlyphCache.Found
import dev.launcher.engine.ui.GlyphCache.Key
import dev.launcher.engine.ui.GlyphCache.Pending
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-005 (slice 9): the icon glyphs' prepared snapshot, its fallback and its completions, over hand-run queues. */
class GlyphCacheTest {
    private val worker = ArrayDeque<Runnable>()
    private val owner = ArrayDeque<Runnable>()
    private val files = mapOf("statusbar/wifi.3" to "W3", "statusbar/moon" to "M", "pixel/moon" to "PM")
    private val loaded = ArrayList<String>()
    private val cache = GlyphCache<String>({ set, symbol ->
        loaded += "$set/$symbol"
        if (symbol == "boom") error("a set that cannot be parsed")
        files["$set/$symbol"]
    }, { worker.add(it) }, { owner.add(it) })

    private fun work() { while (worker.isNotEmpty()) worker.removeFirst().run() }
    private fun deliver() { while (owner.isNotEmpty()) owner.removeFirst().run() }
    private fun all() { while (worker.isNotEmpty() || owner.isNotEmpty()) { work(); deliver() } }

    @Test fun pendingThenReadyCompletesOnTheOwningThreadOnly() {
        val k = Key("statusbar", "wifi.3")
        var done = 0
        assertEquals(Pending, cache.resolve(k))
        assertFalse(cache.prepare(listOf(k)) { done++ })
        assertEquals(Pending, cache.resolve(k))
        work()
        assertEquals(Found("W3"), cache.resolve(k))   // published on the worker, readable at once
        assertEquals(0, done)                          // the completion waits for the owning thread
        deliver()
        assertEquals(1, done)
    }

    @Test fun aConfirmedMissFallsBackToTheStatusBarSet() {
        val k = Key("pixel", "wifi.3")
        var done = 0
        cache.prepare(listOf(k)) { done++ }
        worker.removeFirst().run()                     // pixel/wifi.3: missing; the fallback not loaded yet
        assertEquals(Pending, cache.resolve(k))
        all()
        assertEquals(Found("W3"), cache.resolve(k))
        assertEquals(1, done)
        assertEquals(Found("PM"), cache.resolve(Key("pixel", "moon").also { cache.prepare(listOf(it)) {}; all() }))   // its own wins
    }

    @Test fun absenceIsTerminalAndNeverLoadedAgain() {
        val k = Key("statusbar", "nope")
        cache.prepare(listOf(k)) {}
        all()
        assertEquals(Absent, cache.resolve(k))
        val loads = cache.loads
        var done = 0
        assertTrue(cache.prepare(listOf(k)) { done++ })   // known: done at once, nothing loaded
        assertEquals(1, done)
        assertEquals(loads, cache.loads)
        assertEquals(listOf("statusbar/nope"), loaded)
    }

    @Test fun aSetThatThrowsIsAConfirmedMiss() {
        val k = Key("statusbar", "boom")
        cache.prepare(listOf(k)) {}
        all()
        assertEquals(Absent, cache.resolve(k))
    }

    @Test fun requestsInFlightAreDeduplicated() {
        val k = Key("statusbar", "moon")
        var done = 0
        cache.prepare(listOf(k)) { done++ }
        cache.prepare(listOf(k)) { done++ }
        all()
        assertEquals(listOf("statusbar/moon"), loaded)
        assertEquals(2, done)                           // both requesters hear of it
    }

    @Test fun aStaleCompletionIsDropped() {
        var asked = Key("statusbar", "wifi.3")
        val first = asked
        var done = 0
        cache.prepare(listOf(first), wanted = { asked == first }) { done++ }
        asked = Key("statusbar", "moon")                // the binding moved on before the load came back
        all()
        assertEquals(0, done)
        assertEquals(Found("W3"), cache.resolve(first))  // the glyph itself is still kept
    }

    @Test fun namesThatAreNoPlainNameAreAbsentAndNeverLoaded() {
        val bad = listOf(Key("../x", "wifi.3"), Key("statusbar", "../../etc/passwd"), Key("/data/x", "moon"),
            Key("a/b", "moon"), Key("statusbar", "a/b"), Key("", "moon"), Key("statusbar", ""), Key("Pixel", "moon"),
            Key("statusbar", "x".repeat(65)))
        var done = 0
        assertTrue(cache.prepare(bad) { done++ })
        assertEquals(1, done)
        for (k in bad) assertEquals(Absent, cache.resolve(k))
        assertEquals(0, cache.loads)
        assertEquals(emptyList<String>(), loaded)
    }

    @Test fun aComponentAsksForAtMostMaxKeysGlyphs() {
        var done = 0
        for (i in 0 until GlyphCache.MAX_KEYS + 40) cache.prepare(listOf(Key("statusbar", "s$i"))) { done++ }
        all()
        assertEquals(GlyphCache.MAX_KEYS, cache.loads)
        assertEquals(GlyphCache.MAX_KEYS + 40, done)                    // every requester heard (the rest as absent)
        assertEquals(Absent, cache.resolve(Key("statusbar", "s${GlyphCache.MAX_KEYS + 1}")))
        cache.prepare(listOf(Key("statusbar", "another"))) {}
        all()
        assertEquals(GlyphCache.MAX_KEYS, cache.loads)                  // never prepared again
    }

    @Test fun nothingCompletesAfterDisposal() {
        var done = 0
        cache.prepare(listOf(Key("statusbar", "moon"))) { done++ }
        work()
        cache.dispose()
        deliver()
        assertEquals(0, done)
        assertFalse(cache.prepare(listOf(Key("statusbar", "wifi.3"))) { done++ })
        all()
        assertEquals(0, done)
    }
}
