package dev.launcher.engine.ui

import dev.launcher.app.design.Curve
import org.junit.Assert.*
import org.junit.Test

class MediaMotionTest {
    private val curve = Curve.Ease(0.22f, 1f, 0.36f, 1f, 220f)
    private fun ns(seconds: Double) = (seconds * 1e9).toLong()

    @Test fun sineAtExactTimesAndPhases() {
        val s = LoopMotion.Spec(4f, 14f, 1000f, 0f)
        for ((t, expected) in listOf(0.0 to 9f, 0.25 to 14f, 0.5 to 9f, 0.75 to 4f, 1.0 to 9f))
            assertEquals(expected, s.height(ns(t)), 0.0001f)
        assertEquals(14f, s.copy(phase = 0.25f).height(0), 0f)
        assertFalse(s.copy(periodMs = 0f).valid)
        assertFalse(s.copy(from = Float.NaN).valid)
    }

    @Test fun loopStopsFromShownHeightAndFreezesDuringExit() {
        val m = LoopMotion()
        val s = LoopMotion.Spec(4f, 14f, 1000f, 0f)
        m.update(s, true, 4f, true, false, false, curve, 0)
        m.step(ns(0.25)); assertEquals(14f, m.height, 0.001f)
        m.update(s, false, 4f, true, false, false, curve, ns(0.25))
        assertEquals(14f, m.height, 0.001f)
        m.step(ns(2.0)); assertEquals(4f, m.height, 0f); assertFalse(m.busy)
        m.update(s, true, 4f, true, false, false, curve, ns(3.0)); m.step(ns(3.1))
        val shown = m.height
        m.update(s, true, 4f, true, false, true, curve, ns(3.1))
        assertFalse(m.step(ns(5.0))); assertEquals(shown, m.height, 0f)
    }

    @Test fun restAboveEndpointsResumesFromTheShownHeight() {
        val m = LoopMotion(); val s = LoopMotion.Spec(4f, 14f, 1000f, 0f)
        m.update(s, false, 20f, true, false, false, curve, 0)
        assertEquals(20f, m.height, 0f)
        m.update(s, true, 20f, true, false, false, curve, 0)
        assertEquals(20f, m.height, 0f)
        m.step(ns(2.0)); assertEquals(9f, m.height, 0.001f)
        m.update(s, false, 20f, true, false, false, curve, ns(2.0))
        m.step(ns(3.0)); assertEquals(20f, m.height, 0f); assertFalse(m.busy)
    }

    @Test fun pauseResumeWithin100msKeepsTheShownHeight() {
        val m = LoopMotion(); val s = LoopMotion.Spec(4f, 14f, 1000f, 0f)
        m.update(s, true, 4f, true, false, false, curve, 0)
        m.step(ns(0.25)); val beforePause = m.height
        m.update(s, false, 4f, true, false, false, curve, ns(0.25))
        assertEquals(beforePause, m.height, 0f)
        m.step(ns(0.3)); val beforeResume = m.height
        m.update(s, true, 4f, true, false, false, curve, ns(0.3))
        assertEquals(beforeResume, m.height, 0f); assertTrue(m.busy)
    }

    @Test fun reducedAndDetachedLoopsStop() {
        for ((eligible, reduced) in listOf(false to false, true to true)) {
            val m = LoopMotion(); val s = LoopMotion.Spec(4f, 14f, 1000f, 0f)
            m.update(s, true, 4f, true, false, false, curve, 0)
            m.step(ns(0.25))
            m.update(s, true, 4f, eligible, reduced, false, curve, ns(0.25))
            assertFalse(m.busy)
            if (reduced) assertEquals(4f, m.height, 0f)
        }
    }

    private fun title(rtl: Boolean = false): TitleScroll = TitleScroll().apply {
        configure("long", 148f, 100f, rtl, 24f, 0)
        enabled(true, true, false, false, curve, 0)
    }

    @Test fun scrollPausesTravelsAndReturnsInBothDirections() {
        for (rtl in listOf(false, true)) {
            val m = title(rtl); val sign = if (rtl) 1f else -1f
            m.step(ns(2.0)); assertEquals(0f, m.offset, 0f)
            m.step(ns(3.0)); assertEquals(sign * 24f, m.offset, 0.001f)
            m.step(ns(4.0)); assertEquals(sign * 48f, m.offset, 0.001f)
            m.step(ns(5.99)); assertEquals(sign * 48f, m.offset, 0.001f)
            m.step(ns(7.0)); assertEquals(0f, m.offset, 0f); assertTrue(m.busy)
        }
    }

    @Test fun toggleOffMidScrollRetargetsThenClockStops() {
        val m = title(); m.step(ns(3.0))
        m.enabled(false, true, false, false, curve, ns(3.0))
        assertEquals(-24f, m.offset, 0.001f)
        assertTrue(m.busy)
        m.step(ns(4.0)); assertEquals(0f, m.offset, 0f); assertFalse(m.busy)
    }

    @Test fun shortReplacementRetargetsAndShortTitleHasNoClock() {
        val m = title(); m.step(ns(3.0))
        m.configure("short", 20f, 100f, false, 24f, ns(3.0))
        m.enabled(true, true, false, false, curve, ns(3.0))
        assertEquals(-24f, m.offset, 0f)
        m.step(ns(4.0)); assertEquals(0f, m.offset, 0f); assertFalse(m.busy)
        assertFalse(TitleScroll().apply {
            configure("short", 20f, 100f, false, 24f, 0)
            enabled(true, true, false, false, curve, 0)
        }.busy)
    }

    @Test fun directionAndWidthRetargetFromShownPhysicalOffset() {
        val m = title(); m.step(ns(3.0))
        val shownX = m.offset
        m.configure("rtl", 148f, 100f, true, 24f, ns(3.0))
        assertEquals(shownX, -48f + m.offset, 0f)
        m.configure("rtl resized", 160f, 100f, true, 24f, ns(3.0))
        assertEquals(shownX, -60f + m.offset, 0f)
        assertTrue(TitleScroll.rtl("\u05e9\u05dc\u05d5\u05dd music"))
        assertFalse(TitleScroll.rtl("music \u05e9\u05dc\u05d5\u05dd"))
    }

    @Test fun frozenTitleAndReducedMotionStopRecurringWork() {
        for ((eligible, exiting, reduced) in listOf(Triple(false, false, false), Triple(true, true, false), Triple(true, false, true))) {
            val m = title(); m.step(ns(3.0))
            m.enabled(true, eligible, exiting, reduced, curve, ns(3.0))
            assertFalse(m.step(ns(4.0)))
            assertEquals(if (reduced) 0f else -24f, m.offset, 0f)
        }
    }
}
