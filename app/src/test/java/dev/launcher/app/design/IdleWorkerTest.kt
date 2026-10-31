package dev.launcher.app.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class IdleWorkerTest {
    private fun alive(name: String) = Thread.getAllStackTraces().keys.any { it.name == name && it.isAlive }

    @Test fun runsTasksInOrderOnOneThread() {
        val w = IdleWorker.create("idle-worker-order")
        val seen = Collections.synchronizedList(mutableListOf<Int>())
        val threads = Collections.synchronizedSet(mutableSetOf<Thread>())
        val done = CountDownLatch(50)
        for (i in 0 until 50) w.execute { seen += i; threads += Thread.currentThread(); done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals((0 until 50).toList(), seen.toList())
        assertEquals(1, threads.size)
    }

    /** A shade made anew left its workers' threads (and itself through them) behind (code review, 2026-11-01). */
    @Test fun itsThreadEndsWhenIdleAndComesBackForANewTask() {
        val name = "idle-worker-ends"
        val w = IdleWorker.create(name, idleMs = 50)
        val first = CountDownLatch(1)
        w.execute { first.countDown() }
        assertTrue(first.await(5, TimeUnit.SECONDS))
        val until = System.currentTimeMillis() + 3000
        while (alive(name) && System.currentTimeMillis() < until) Thread.sleep(20)
        assertFalse("the thread is still there after it idled", alive(name))
        val again = CountDownLatch(1)
        w.execute { again.countDown() }
        assertTrue("a task after the idle time still runs", again.await(5, TimeUnit.SECONDS))
    }
}
