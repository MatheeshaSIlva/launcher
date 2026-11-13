package dev.launcher.engine.ui

/**
 * `decode` (E2.14d, ENGINE_SPEC's "decode as a text effect with its own duration and glyph pool"): when a text changes,
 * every character that changed scrambles through glyphs from a pool, then settles on its new letter, one after another
 * from the start, the last at the effect's duration. Characters that did not change, and spaces, stay as they are.
 * A new change mid-way decodes from what is shown. Motion reduced (or a duration of 0) changes the text at once.
 * Pure: the host gives it the time (ns); [text] is what to draw now; [busy] while anything still scrambles.
 */
class DecodeText {
    var text = ""; private set
    private var target = ""
    private var from = ""
    private var started = 0L
    private var durationNs = 0L
    private var pool = DEFAULT_POOL
    private var initial = true
    /** Changed positions and when each settles (ns after [started]). */
    private var settles = LongArray(0)
    var busy = false; private set

    /** Told the content now; true when it changed (a new decode began or the text was set). */
    fun configure(content: String, durationMs: Float, glyphs: String, reduced: Boolean, now: Long): Boolean {
        if (glyphs.isNotEmpty()) pool = glyphs.take(MAX_POOL)
        if (initial) { initial = false; target = content; text = content; return true }
        if (content == target) return false
        from = text
        target = content
        durationNs = if (reduced || !(durationMs > 0f) || !durationMs.isFinite()) 0L else (durationMs.coerceAtMost(MAX_MS) * 1e6).toLong()
        if (durationNs == 0L) { text = content; busy = false; return true }
        started = now
        // The changed positions settle in order across the duration: the first at 35 %, the last at 100 %.
        val changed = content.indices.filter { i -> content[i] != ' ' && from.getOrNull(i) != content[i] }
        settles = LongArray(content.length) { -1 }
        changed.forEachIndexed { k, i ->
            val f = if (changed.size == 1) 1f else 0.35f + 0.65f * k / (changed.size - 1)
            settles[i] = (durationNs * f).toLong()
        }
        busy = changed.isNotEmpty()
        if (!busy) text = content else step(now)
        return true
    }

    /** Settled on its target at once (motion reduced mid-way: Codex, the island's milestone). */
    fun snap() { if (busy) { text = target; busy = false } }

    /** The text at [now]; false once it has settled. */
    fun step(now: Long): Boolean {
        if (!busy) return false
        val t = (now - started).coerceAtLeast(0L)
        val tick = t / CYCLE_NS
        val sb = StringBuilder(target.length)
        var waiting = false
        for (i in target.indices) {
            val s = settles.getOrElse(i) { -1L }
            if (s < 0 || t >= s) sb.append(target[i])
            else { waiting = true; sb.append(pool[glyph(i, tick)]) }
        }
        text = sb.toString()
        if (!waiting) { text = target; busy = false }
        return busy
    }

    /** A glyph of the pool for position [i] at [tick]: the same at the same moment, a new one each [CYCLE_NS]. */
    private fun glyph(i: Int, tick: Long): Int {
        var h = (i.toLong() * 0x9E3779B97F4A7C15uL.toLong()) xor (tick * 0xBF58476D1CE4E5B9uL.toLong())
        h = (h xor (h ushr 31)) * 0x94D049BB133111EBuL.toLong()
        return ((h ushr 33) % pool.length).toInt()
    }

    companion object {
        /** How often a scrambling character changes: 50 ms (readable as letters changing, not as noise). */
        const val CYCLE_NS = 50_000_000L
        const val MAX_MS = 3000f
        const val MAX_POOL = 256
        const val DEFAULT_POOL = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#%&*+=<>"
    }
}
