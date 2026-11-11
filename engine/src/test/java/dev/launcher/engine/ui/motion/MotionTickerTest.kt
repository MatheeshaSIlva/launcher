package dev.launcher.engine.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-009, 13a-0: the clock asks for one frame at a time and none at rest. */
class MotionTickerTest {
    private class HandClock : FrameSource {
        val pending = ArrayList<(Long) -> Unit>()
        override fun post(callback: (Long) -> Unit) { pending += callback }
        override fun remove(callback: (Long) -> Unit) { pending.remove(callback) }
        fun frame(nanos: Long) { val now = pending.toList(); pending.clear(); now.forEach { it(nanos) } }
    }

    @Test fun wakesCoalesceIntoOneCallback() {
        val clock = HandClock()
        val t = MotionTicker(clock) { false }
        repeat(5) { t.wake() }
        assertEquals(1, clock.pending.size)
        assertEquals(1, t.registrations)
    }

    @Test fun runsWhileBusyAndStopsAtRest() {
        val clock = HandClock()
        var left = 3
        val times = ArrayList<Long>()
        val t = MotionTicker(clock) { n -> times += n; --left > 0 }
        t.wake()
        for (i in 1..10) clock.frame(i * 8_333_333L)
        assertEquals("three frames, then nothing", 3, times.size)
        assertEquals(3, t.registrations)
        assertFalse(t.running)
        assertTrue(clock.pending.isEmpty())
    }

    @Test fun disposeCancelsWhatIsRegistered() {
        val clock = HandClock()
        var frames = 0
        val t = MotionTicker(clock) { frames++; true }
        t.wake()
        t.dispose()
        assertTrue(clock.pending.isEmpty())
        clock.frame(1L)
        t.wake()
        assertEquals(0, frames)
        assertTrue(clock.pending.isEmpty())
    }

    @Test fun reducedMotionFollowsTheAnimatorScale() {
        assertTrue(ReducedMotion.fromAnimatorScale(0f))
        assertFalse(ReducedMotion.fromAnimatorScale(1f))
        assertFalse(ReducedMotion.fromAnimatorScale(0.5f))
    }
}
