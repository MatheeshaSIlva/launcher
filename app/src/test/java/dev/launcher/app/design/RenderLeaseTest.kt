package dev.launcher.app.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * B-031: `ViewPicture.drawLater` closed its `HardwareBufferRenderer` inside the draw callback (on its `worker` thread),
 * while the thread that called `RenderRequest.draw` could still be parked in hwui's `DrawFrameTask::postAndWait`. Closing
 * the renderer deletes its RenderProxy and with it the DrawFrameTask's mutex; the issuing thread then woke up in
 * `pthread_cond_wait` on a destroyed mutex: "FORTIFY: pthread_mutex_lock called on a destroyed mutex" (S24 tombstone,
 * tools/shots/baseline/2438a9f/b031_tombstone.txt, from `BlurBaker.bake` on its pool thread).
 *
 * The fix's seam: [RenderLease], a plain-Kotlin guard that lets the renderer (and its buffer) go only once BOTH the
 * issuing thread is back from `draw()` ([RenderLease.issued]) AND the callback is done with the result
 * ([RenderLease.completed]), exactly once, on whichever thread came last. No Android types: testable on the JVM.
 */
class RenderLeaseTest {

    /** The crash's order: the callback ran (and used to close the renderer) before `draw()` had returned. */
    @Test fun callbackFirstDoesNotReleaseWhileDrawIsStillRunning() {
        val released = AtomicInteger()
        val lease = RenderLease { released.incrementAndGet() }
        lease.completed()   // the worker: fence waited, bitmap wrapped
        assertEquals("the renderer was let go while the issuing thread was still inside draw()", 0, released.get())
        assertFalse(lease.released)
        lease.issued()      // the issuing thread: back from RenderRequest.draw
        assertEquals(1, released.get())
        assertTrue(lease.released)
    }

    @Test fun drawReturnsFirstReleasesWhenTheCallbackIsDone() {
        val released = AtomicInteger()
        val lease = RenderLease { released.incrementAndGet() }
        lease.issued()
        assertEquals("the renderer was let go before the GPU's result had been used", 0, released.get())
        lease.completed()
        assertEquals(1, released.get())
    }

    @Test fun releasedOnceOnly() {
        val released = AtomicInteger()
        val lease = RenderLease { released.incrementAndGet() }
        lease.completed(); lease.issued(); lease.issued(); lease.completed()
        assertEquals(1, released.get())
    }

    /**
     * The B-031 sequence with threads: hwui keeps the issuing thread parked until after the frame was drawn (when
     * `syncFrameState` could not unblock it: `DrawFrameTask::run` unblocks only at its end then), and the callback is
     * dispatched to the executor from inside that draw. So the callback can run, and finish, while `draw()` has not
     * returned. Whatever the release does must happen after the issuing thread has left `draw()`, on that thread.
     */
    @Test fun neverReleasedWhileTheIssuingThreadIsInsideDraw() {
        val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "view-picture") }
        try {
            repeat(200) {
                val insideDraw = AtomicBoolean(false)
                val releasedInsideDraw = AtomicBoolean(false)
                val releasedOn = arrayOfNulls<Thread>(1)
                val done = CountDownLatch(1)
                val lease = RenderLease {
                    if (insideDraw.get()) releasedInsideDraw.set(true)
                    releasedOn[0] = Thread.currentThread()
                    done.countDown()
                }
                // A stand-in for RenderRequest.draw(worker, callback) as hwui plays it in the crash's case: the callback
                // is handed to the executor and runs to its end before the caller's postAndWait wakes up.
                val callbackRan = CountDownLatch(1)
                insideDraw.set(true)
                worker.execute { lease.completed(); callbackRan.countDown() }
                assertTrue(callbackRan.await(5, TimeUnit.SECONDS))
                insideDraw.set(false)   // postAndWait has re-taken DrawFrameTask's mutex and returned
                lease.issued()
                assertTrue("never released", done.await(5, TimeUnit.SECONDS))
                assertFalse("released while the issuing thread was still inside draw() (B-031)", releasedInsideDraw.get())
                assertSame("the last to arrive (here the issuing thread) lets the renderer go", Thread.currentThread(), releasedOn[0])
            }
        } finally { worker.shutdownNow() }
    }

    /** Both sides arriving at once from two threads: released exactly once, never zero times, never twice. */
    @Test fun raceOfBothSidesReleasesExactlyOnce() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(2000) {
                val released = AtomicInteger()
                val lease = RenderLease { released.incrementAndGet() }
                val start = CyclicBarrier(2)
                val a = pool.submit { start.await(); lease.issued() }
                val b = pool.submit { start.await(); lease.completed() }
                a.get(5, TimeUnit.SECONDS); b.get(5, TimeUnit.SECONDS)
                assertEquals(1, released.get())
            }
        } finally { pool.shutdownNow() }
    }
}
