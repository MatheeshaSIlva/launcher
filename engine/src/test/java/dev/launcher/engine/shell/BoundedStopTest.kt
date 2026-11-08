package dev.launcher.engine.shell

import dev.launcher.engine.shell.BoundedStop.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

/** T-001 slice 3: the outcome of stopping the shell service is known in time, whatever the service does. */
class BoundedStopTest {
    private class Dead : RuntimeException()

    @Test fun aDeadBinderIsTheSuccess() =
        assertEquals(Outcome.STOPPED, BoundedStop.run({ throw Dead() }, { it is Dead }))

    @Test fun aNormalReturnIsASuccess() =
        assertEquals(Outcome.STOPPED, BoundedStop.run({ }, { false }))

    @Test fun anyOtherExceptionIsAFailure() =
        assertEquals(Outcome.FAILED, BoundedStop.run({ error("refused") }, { it is Dead }))

    @Test fun aBlockedStopTimesOutInsteadOfHanging() {
        val never = CountDownLatch(1)
        val t0 = System.nanoTime()
        assertEquals(Outcome.TIMED_OUT, BoundedStop.run({ never.await() }, { false }, deadlineMs = 200))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3_000)
        never.countDown()
    }
}
