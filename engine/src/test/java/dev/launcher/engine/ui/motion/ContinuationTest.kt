package dev.launcher.engine.ui.motion

import dev.launcher.app.design.Curve
import dev.launcher.app.motion.Ease
import dev.launcher.app.motion.Mover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** T-009, 13a-0: a continued curve keeps the value and the speed it had at the switch (Codex's rounds one to three). */
class ContinuationTest {
    private val easeOut = Curve.Ease(0f, 0f, 0.58f, 1f, 220f)
    private val standard = Curve.Ease(0.2f, 0f, 0f, 1f, 220f)
    private val overshoot = Curve.Ease(0.34f, 1.3f, 0.64f, 1f, 220f)
    private val linear = Curve.Ease(0f, 0f, 1f, 1f, 200f)
    private val degenerate = Curve.Ease(0f, 0f, 0.6f, 1f, 250f)
    private val steep = Curve.Ease(0.95f, 0f, 0.05f, 1f, 300f)
    private val vertical = Curve.Ease(0f, 0.5f, 0.5f, 1f, 250f)
    private val spring = Curve.Spring(0.4f, 0.7f)

    /**
     * The speed at the switch, from the value alone: one-sided differences, Richardson-extrapolated (2·D(h/2) − D(h) cancels
     * the acceleration's first-order term), over shrinking steps converge on [v]. Steps stay above what the value's float
     * and the bezier solver's 1e-7 can resolve.
     */
    private fun assertSpeedAtStart(m: Mover, from: Float, v: Float) {
        assertEquals("value at the switch", from, m.value(0.0), 1e-3f)
        fun d(h: Double) = (m.value(h) - from) / h
        // A curve whose first control point is at the origin approaches its speed at a square-root rate: more halvings.
        val steps = listOf(4e-3, 2e-3, 1e-3, 5e-4, 2.5e-4, 1.25e-4)
        val errors = steps.map { h -> abs(2 * d(h / 2) - d(h) - v) }
        val tolerance = maxOf(abs(v) * 0.01, 2.0)
        // The best estimate over the steps: below them the value's float and the solver's precision add noise.
        assertTrue("finite differences converge on $v: $errors", errors.min() < tolerance)
        assertEquals("reported speed at the switch", v, m.velocity(0.0), maxOf(abs(v) * 0.001f, 0.01f))
    }

    @Test fun continuedEasesKeepTheirSpeed() {
        for (c in listOf(easeOut, standard, overshoot, linear, degenerate, steep)) for (v in listOf(-900f, 0f, 350f, 2400f)) {
            val m = Continuation.continued(c, 40f, v, 160f)
            assertSpeedAtStart(m, 40f, v)
            assertEquals("lands on the target ($c)", 160f, m.value(c.ms / 1000.0), 1e-3f)
            assertTrue(m.settled(c.ms / 1000.0))
        }
    }

    @Test fun equalEndpointsWithSpeedLeaveAndComeBack() {
        val m = Continuation.continued(easeOut, 100f, 1000f, 100f)
        assertSpeedAtStart(m, 100f, 1000f)
        assertTrue("goes past and returns", m.value(0.05) > 100f)
        assertEquals(100f, m.value(0.22), 1e-3f)
    }

    @Test fun theInitialTangentIsTheFirstControlPointOffTheOrigin() {
        assertEquals(1.0, EngineEase.initialSlope(linear)!!, 1e-9)
        assertEquals(1 / 0.6, EngineEase.initialSlope(degenerate)!!, 1e-6)
        assertEquals(0.0, EngineEase.initialSlope(standard)!!, 1e-9)
        assertEquals(1.3 / 0.34, EngineEase.initialSlope(overshoot)!!, 1e-5)
        assertNull("vertical", EngineEase.initialSlope(vertical))
        assertEquals(1.0, EngineEase.initialSlope(Curve.Ease(0f, 0f, 1f, 1f, 100f))!!, 1e-9)
    }

    @Test fun aVerticalTangentContinuesAsItsSpring() {
        val m = Continuation.continued(vertical, 0f, 500f, 80f)
        assertSpeedAtStart(m, 0f, 500f)
        var t = 0.0
        while (!m.settled(t) && t < 5) t += 0.004
        assertEquals("settles on the target", 80f, m.value(t), 0.5f)
        val fits = Continuation.fitCount
        repeat(3) { Continuation.continued(vertical, 5f, 100f, 1f) }
        assertEquals("the fit is kept, not searched again", fits, Continuation.fitCount)
    }

    @Test fun aSpringInterruptedAtItsTurningPointCarriesOn() {
        val first = Continuation.fresh(spring, 0f, 100f)
        var t = 0.01
        while (first.velocity(t) > 0f) t += 0.0005           // the peak of the overshoot: speed crosses zero
        val at = first.value(t)
        val v = first.velocity(t)
        val next = Continuation.continued(spring, at, v, 20f)
        assertSpeedAtStart(next, at, v)
    }

    @Test fun theSpeedIsTheValuesDerivativeEverywhere() {
        for (c in listOf(easeOut, overshoot, linear, degenerate, steep)) {
            val m = Continuation.continued(c, 0f, 300f, 200f)
            val d = c.ms / 1000.0
            for (u in listOf(0.1, 0.3, 0.5, 0.7, 0.9)) {
                val t = u * d
                val h = 1e-4
                val numeric = (m.value(t + h) - m.value(t - h)) / (2 * h)
                val reported = m.velocity(t)
                assertEquals("$c at $u", numeric, reported.toDouble(), maxOf(abs(numeric) * 0.01, 1.0))
            }
        }
    }

    /** Why the engine has its own path: the launcher's ease adds its own initial slope to the carried speed. */
    @Test fun theLaunchersEaseJumpsInSpeedWhenRetargeted() {
        // A curve that does not start flat (the launcher's ease-out has x1 = y1 = 0, a flat start it reads as slope 0).
        val c = overshoot
        val launchers = Ease(c.x1, c.y1, c.x2, c.y2, c.ms / 1000f).apply { start(40f, 350f, 160f) }
        val ours = Continuation.continued(c, 40f, 350f, 160f)
        assertTrue("the launcher's starts faster than it was moving", launchers.velocity(0.0) > 350f * 1.5f)
        assertEquals(350f, ours.velocity(0.0), 0.5f)
    }

    /** Codex, 13a-0 review: x flat inside the curve is a vertical instant, not the end's slope. */
    @Test fun aVerticalInstantInsideTheCurveIsSteep() {
        val c = Curve.Ease(1f, 0f, 0f, 1f, 220f)
        val m = Continuation.continued(c, 0f, 0f, 120f)
        assertTrue("steepest at its middle", m.velocity(0.11) > 10_000f)
    }

    /** Codex, 13a-0 review: no early snap for a curve that ends vertically. */
    @Test fun aVerticalEndLandsWithoutAJump() {
        for (c in listOf(Curve.Ease(0f, 0f, 1f, 0f, 220f), Curve.Ease(0.5f, 0f, 1f, 1f, 220f))) {
            val m = Continuation.fresh(c, 0f, 1000f)
            val d = c.ms / 1000.0
            assertEquals("$c just before its end", 1000f, m.value(d - 1e-9), 0.5f)
            assertTrue(!m.settled(d - 1e-9) && m.settled(d))
        }
    }

    @Test fun aFreshStartIsTheAuthoredCurve() {
        for (c in listOf(easeOut, standard, overshoot)) {
            val ours = Continuation.fresh(c, 10f, 90f)
            val launchers = Ease(c.x1, c.y1, c.x2, c.y2, c.ms / 1000f).apply { start(10f, 0f, 90f) }
            for (i in 0..20) {
                val t = i * c.ms / 1000.0 / 20
                assertEquals("$c at $t", launchers.value(t), ours.value(t), 1e-3f)
            }
        }
    }
}
