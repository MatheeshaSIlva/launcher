package dev.launcher.engine.ui.motion

import dev.launcher.app.design.Curve
import kotlin.math.abs

/**
 * A text's roll (T-009 13a-3b), StatusBarView's contract: when the text changes, what changed moves out and the new comes
 * in, each glyph on its own track, so a change in the middle of a roll turns every glyph from where it is.
 * - **By characters** when the old and new strings are both verified ([dev.launcher.engine.ui.RollEligibility]), of one
 *   length, and every character that did not change sits where it was: changed characters roll, staggered in order, the
 *   others stay still.
 * - **Whole** otherwise: everything showing leaves, the new string arrives as one.
 * - Up (out above, in from below) for a larger number, down for a smaller, else up.
 * A glyph's [Glyph.y] is in rises (−1 above, 0 in place, 1 below), its alpha `1 − |y|`; a leaving glyph goes once it
 * settles out. At most [MAX_LEAVING] leaving per slot: an older one still showing ends at once.
 */
class RollMotion {
    class Glyph internal constructor(
        val text: String,
        /** Where it starts in its string, from that string's left edge. */
        val offset: Float,
        /** Its string's width: what its alignment is worked out from. */
        val width: Float,
        /** Its character index (by characters), or −1 for a whole string. */
        val slot: Int,
    ) {
        internal val track = Track()
        internal var leaving = false
        internal var startAt = 0L
        internal var goTo = Float.NaN
        internal var curve: Curve? = null
        val y get() = track.value
        val alpha get() = (1f - abs(track.value)).coerceIn(0f, 1f)
    }

    private val list = ArrayList<Glyph>()
    /** What it shows or rolls to, in drawing order (leaving under arriving). */
    val glyphs: List<Glyph> get() = list
    var text: String? = null; private set
    private var positions = FloatArray(0)
    private var clusters = false

    val moving get() = list.any { it.track.moving || !it.goTo.isNaN() || it.leaving }

    /**
     * Shows [to] laid out at [at] (its characters' starts and its end), rolling from what shows on [curve] (null: at
     * once) when [byCharacters] allows it for both strings; changed characters start [staggerNs] apart.
     */
    fun to(to: String, at: FloatArray, byCharacters: Boolean, curve: Curve?, staggerNs: Long, now: Long) {
        val old = text
        if (to == old) {
            // The same text, its verdict come in since: laid out again at rest (the same picture), never rolled.
            if (byCharacters != clusters && !moving) { list.clear(); list += rest(to, at, byCharacters); positions = at; clusters = byCharacters }
            return
        }
        val width = at.last()
        if (old == null || curve == null) {
            list.clear()
            list += rest(to, at, byCharacters)
            text = to; positions = at; clusters = byCharacters
            return
        }
        val d = direction(old, to)
        val same = byCharacters && clusters && old.length == to.length &&
            to.indices.all { i -> to[i] != old[i] || abs(at[i] - positions[i]) < 0.01f && abs(width - positions.last()) < 0.01f }
        if (same) {
            var k = 0
            for (i in to.indices) {
                if (to[i] == old[i]) continue
                val start = now + k++ * staggerNs
                list.filter { it.slot == i && !it.leaving }.forEach { leave(it, d, curve, start) }
                list += Glyph(to.substring(i, i + 1), at[i], width, i).also { arrive(it, d, curve, start) }
            }
        } else {
            list.filter { !it.leaving }.forEach { leave(it, d, curve, now) }
            if (byCharacters) rest(to, at, true).forEach { arrive(it, d, curve, now); list += it }
            else list += Glyph(to, 0f, width, -1).also { arrive(it, d, curve, now) }
        }
        bound()
        text = to; positions = at; clusters = byCharacters
    }

    /** Every glyph at [now]; false once all have settled and the leaving have gone. [epsilon]: half a pixel, in rises. */
    fun step(now: Long, epsilon: Float): Boolean {
        for (g in list) if (!g.goTo.isNaN() && now >= g.startAt) { g.track.to(g.goTo, g.curve, g.startAt); g.goTo = Float.NaN }
        for (g in list) g.track.step(now, epsilon)
        list.removeAll { it.leaving && !it.track.moving && it.goTo.isNaN() }
        return moving
    }

    /** At the end at once (reduced motion turned on, or disposal): what it rolls to, at rest. */
    fun snap() {
        list.removeAll { it.leaving }
        for (g in list) { g.goTo = Float.NaN; g.track.snap(0f) }
    }

    private fun rest(t: String, at: FloatArray, byCharacters: Boolean): List<Glyph> =
        if (!byCharacters) listOf(Glyph(t, 0f, at.last(), -1).also { it.track.snap(0f) })
        else t.indices.filter { t[it] != ' ' }.map { i -> Glyph(t.substring(i, i + 1), at[i], at.last(), i).also { it.track.snap(0f) } }

    private fun leave(g: Glyph, d: Float, curve: Curve, at: Long) {
        g.leaving = true
        // One that never started arriving was never seen: it goes, rather than crossing the place on its way out.
        if (!g.track.moving && abs(g.track.value) >= 1f) { g.goTo = Float.NaN; return }
        g.curve = curve; g.startAt = at; g.goTo = -d
    }

    private fun arrive(g: Glyph, d: Float, curve: Curve, at: Long) {
        g.track.snap(d); g.curve = curve; g.startAt = at; g.goTo = 0f
    }

    private fun bound() {
        for (slot in list.map { it.slot }.distinct()) {
            val out = list.filter { it.slot == slot && it.leaving }
            if (out.size > MAX_LEAVING) list.removeAll(out.take(out.size - MAX_LEAVING).toSet())
        }
    }

    companion object {
        const val MAX_LEAVING = 2
        /** How far a glyph travels out, as a share of the text size (StatusBarView's `0.55`). */
        const val RISE = 0.55f

        /** +1 (up) when [to]'s number is larger than [from]'s or neither is a number; −1 (down) when it is smaller. */
        fun direction(from: String, to: String): Float {
            val a = from.filter { it.isDigit() }.trimStart('0')
            val b = to.filter { it.isDigit() }.trimStart('0')
            if (from.none { it.isDigit() } || to.none { it.isDigit() }) return 1f
            val c = if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
            return if (c > 0) -1f else 1f
        }
    }
}
