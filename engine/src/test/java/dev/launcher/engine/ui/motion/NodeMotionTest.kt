package dev.launcher.engine.ui.motion

import dev.launcher.app.design.Curve
import dev.launcher.engine.kdl.Span
import dev.launcher.engine.model.CurveSpec
import dev.launcher.engine.model.MotionChannel
import dev.launcher.engine.model.NodeMotionSpec
import dev.launcher.engine.model.Transition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** T-009 13a-1: a node's place, size, opacity and scale tracks, and the envelope of what they can show. */
class NodeMotionTest {
    private val ease = Curve.Ease(0.34f, 1.3f, 0.64f, 1f, 220f)
    private val spring = Curve.Spring(0.4f, 0.7f)
    private val span = Span(0, 0, 1, 1)
    private val ms = 1_000_000L

    private fun motion(curve: Curve, vararg channels: MotionChannel, reduced: () -> Boolean = { false }) =
        NodeMotion(NodeMotionSpec(listOf(Transition(channels.toSet(), CurveSpec.Literal(curve), span)), null),
            MotionContext(reduced = reduced, now = { 0L }))

    private fun NodeMotion.at(x: Float, w: Float = 10f, now: Long) = update(x, 0f, 0f, 0f, w, 10f, 1f, 1f, now)

    /** Steps every frame (120 Hz) from [from] until it settles; the frame times and the track's values. */
    private fun NodeMotion.run(track: Track, from: Long, until: Long = 5_000 * ms): List<Pair<Long, Float>> {
        val out = ArrayList<Pair<Long, Float>>()
        var t = from
        while (t < until) { val busy = step(t); out += t to track.value; if (!busy) break; t += 8_333_333 }
        return out
    }

    @Test fun aLayoutMoveStartsWhereItWasShownAndEndsExactlyAtZero() {
        val m = motion(ease, MotionChannel.PLACE)
        assertFalse(m.at(0f, now = 0))                                   // first placement: shown there at once
        assertTrue(m.at(40f, now = 100 * ms))                            // layout moved it 40 px right
        assertEquals("shown where it was: 0 = 40 + (-40)", -40f, m.x.value, 0f)
        val frames = m.run(m.x, 100 * ms)
        assertEquals("ends exactly on its place", 0f, frames.last().second, 0f)
        assertFalse(m.moving)
    }

    @Test fun placingAgainWhereItIsDoesNotRestartTheMotion() {
        val m = motion(spring, MotionChannel.PLACE)
        m.at(0f, now = 0); m.at(40f, now = 0)
        m.step(50 * ms)
        val before = m.x.value
        m.at(40f, now = 50 * ms)                                        // a layout pass with nothing changed
        m.step(50 * ms)
        assertEquals(before, m.x.value, 0f)
        m.step(60 * ms)
        val reference = motion(spring, MotionChannel.PLACE).apply { at(0f, now = 0); at(40f, now = 0); step(60 * ms) }
        assertEquals("the same trajectory", reference.x.value, m.x.value, 1e-4f)
    }

    @Test fun aSecondMoveMidMotionKeepsTheShownPlaceAndSpeed() {
        for (curve in listOf<Curve>(ease, spring)) {
            val m = motion(curve, MotionChannel.PLACE)
            m.at(0f, now = 0); m.at(40f, now = 0)
            val t = 60 * ms
            m.step(t)
            val shown = 40f + m.x.value                                   // layout place + contribution
            val h = 100_000L                                              // 0.1 ms
            m.step(t - h); val a = 40f + m.x.value
            m.step(t); val speed = (shown - a) / 1e-4f
            m.at(90f, now = t)                                            // layout moves it again, 50 px further
            assertEquals("$curve: no jump in place", shown, 90f + m.x.value, 1e-3f)
            m.step(t + h)
            val after = ((90f + m.x.value) - shown) / 1e-4f
            assertEquals("$curve: no jump in speed", speed, after, maxOf(abs(speed) * 0.02f, 3f))
        }
    }

    @Test fun aSizeTrackEndsOnItsTargetAndItsEnvelopeHoldsEveryFrame() {
        val m = motion(spring, MotionChannel.SIZE)
        m.at(0f, w = 40f, now = 0); m.at(0f, w = 120f, now = 0)
        val frames = m.run(m.width, 0)
        assertEquals(120f, frames.last().second, 0f)
        assertTrue("overshoots (damping 0.7)", frames.maxOf { it.second } > 120f)
        val m2 = motion(spring, MotionChannel.SIZE)
        m2.at(0f, w = 40f, now = 0); m2.at(0f, w = 120f, now = 0)
        val high = m2.width.high
        assertTrue("the envelope holds the overshoot", frames.all { it.second <= high + 1e-3f && it.second >= m2.width.low - 1e-3f })
    }

    @Test fun theEnvelopeOnlyGrowsAtAnInterruptionAndIsTheTargetAtRest() {
        val m = motion(ease, MotionChannel.SIZE)
        m.at(0f, w = 100f, now = 0); m.at(0f, w = 20f, now = 0)
        val (lo, hi) = m.width.low to m.width.high
        m.step(80 * ms)
        assertEquals("unchanged by a frame", lo, m.width.low, 0f)
        m.at(0f, w = 60f, now = 80 * ms)
        assertTrue("never shrinks mid-motion", m.width.low <= lo && m.width.high >= hi)
        m.run(m.width, 80 * ms)
        assertEquals(60f, m.width.low, 0f); assertEquals(60f, m.width.high, 0f)
    }

    @Test fun reducedMotionJumps() {
        var reduced = true
        val m = motion(spring, MotionChannel.PLACE, MotionChannel.SIZE, reduced = { reduced })
        m.at(0f, now = 0)
        assertFalse(m.at(40f, w = 30f, now = 0))
        assertEquals(0f, m.x.value, 0f); assertEquals(30f, m.width.value, 0f)
        reduced = false
        assertTrue(m.at(80f, w = 30f, now = 0))
        m.snap()
        assertFalse(m.moving); assertEquals(0f, m.x.value, 0f)
    }

    @Test fun aChannelNotNamedJumps() {
        val m = motion(spring, MotionChannel.SIZE)
        m.at(0f, now = 0)
        m.update(40f, 0f, 5f, 0f, 10f, 10f, 0.5f, 2f, 0)
        assertFalse(m.moving)
        assertEquals("declared translate at once", 5f, m.x.value, 0f)
        assertEquals(0.5f, m.alpha.value, 0f); assertEquals(2f, m.scale.value, 0f)
    }

    /** Codex's two figures (round two), now exact bounds; and every regime against its dense trajectory. */
    @Test fun envelopesAreAnalyticAndHoldTheDenseTrajectory() {
        val e = Continuation.continued(Curve.Ease(0f, 0f, 0.58f, 1f, 200f), 0f, 1000f, 0f)
        val r = Envelope.range(Curve.Ease(0f, 0f, 0.58f, 1f, 200f), e, 0f, 1000f, 0f)
        assertEquals(29.63f, r.endInclusive, 0.01f)
        assertEquals(23.42f, Envelope.spring(Curve.Spring(0.4f, 1f), 0f, 1000f), 0.01f)
        val curves = listOf(ease, Curve.Ease(1f, 0f, 0f, 1f, 220f), Curve.Ease(0f, 0.5f, 0.5f, 1f, 250f),
            Curve.Spring(0.4f, 0.3f), Curve.Spring(0.4f, 0.999f), Curve.Spring(0.4f, 1f), Curve.Spring(0.4f, 1.001f),
            Curve.Spring(0.5f, 1.8f))
        for (c in curves) for (v in listOf(-2000f, 0f, 700f)) for ((from, to) in listOf(0f to 100f, 50f to 50f, 80f to -20f)) {
            val mover = Continuation.continued(c, from, v, to)
            val range = Envelope.range(c, mover, from, v, to)
            var t = 0.0
            while (t < 4.0) {
                val x = mover.value(t)
                assertTrue("$c v=$v $from→$to at $t: $x outside $range", x >= range.start - 1e-3f && x <= range.endInclusive + 1e-3f)
                t += 0.0002
            }
            if (c is Curve.Spring && c.damping in 0.99f..1.01f) assertTrue("near critical stays bounded: $range",
                range.endInclusive - range.start < 400f)
        }
    }

    /** Codex, 13a-1 review: a centred box growing in a centred slot keeps its left edge continuous (no double shift). */
    @Test fun aCentredGrowthIsNotCompensatedTwice() {
        val m = motion(ease, MotionChannel.PLACE, MotionChannel.SIZE)
        fun left(layoutX: Float, layoutW: Float) = layoutX + m.x.value + (layoutW - m.width.value) * 0.5f
        m.update(100f, 0f, 0f, 0f, 100f, 10f, 1f, 1f, 0, anchorX = 0.5f)
        m.update(50f, 0f, 0f, 0f, 200f, 10f, 1f, 1f, 0, anchorX = 0.5f)     // a 300 px stack: grows 100 → 200, x 100 → 50
        assertEquals("drawn where it was", 100f, left(50f, 200f), 1e-3f)
        m.step(80 * ms)
        val mid = left(50f, 200f)
        m.update(100f, 0f, 0f, 0f, 100f, 10f, 1f, 1f, 80 * ms, anchorX = 0.5f)  // reversed: back to 100 wide
        assertEquals("no jump at the reversal", mid, left(100f, 100f), 1e-3f)
        m.run(m.width, 80 * ms)
        assertEquals(100f, left(100f, 100f), 1e-3f)
    }

    /** Codex, 13a-1 sign-off: with place alone, a size that jumps leaves the old left edge to hold (no anchor). */
    @Test fun aPlaceOnlyTransitionHoldsTheOldEdgeWhenTheSizeJumps() {
        for ((from, to) in listOf(100f to 200f, 200f to 100f)) {
            val m = motion(ease, MotionChannel.PLACE)
            val x0 = (300f - from) / 2; val x1 = (300f - to) / 2          // centred in a 300 px stack
            m.update(x0, 0f, 0f, 0f, from, 10f, 1f, 1f, 0, anchorX = 0.5f)
            m.update(x1, 0f, 0f, 0f, to, 10f, 1f, 1f, 0, anchorX = 0.5f)
            assertEquals("$from → $to: its left starts where it was", x0, x1 + m.x.value, 1e-3f)
            m.run(m.x, 0)
            assertEquals(x1, x1 + m.x.value, 0f)
        }
    }

    /** Codex, 13a-1 sign-off: a fractional place change (the root's baseline) is carried too, across a whole pixel. */
    @Test fun fractionalPlaceChangesAreCarried() {
        for ((a, b) in listOf(12.25f to 12.75f, 12.75f to 13.25f)) {
            val m = motion(ease, MotionChannel.PLACE)
            m.update(0f, a, 0f, 0f, 10f, 10f, 1f, 1f, 0)
            assertTrue(m.update(0f, b, 0f, 0f, 10f, 10f, 1f, 1f, 0))
            assertEquals("shown at $a", a, b + m.y.value, 1e-4f)
        }
    }
}
