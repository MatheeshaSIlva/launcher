package dev.launcher.engine.shell

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StartAttemptTest {
    private var now = 100L
    private val attempts = StartAttempt({ now })
    private fun claim(capability: String?) = attempts.claim(2000, "shell-binder", capability)

    @Test fun generates32RandomBytesAsLowercaseHexWithoutLoggingThem() {
        val first = attempts.begin()
        val second = attempts.begin()
        assertTrue(first.capability.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(first.capability, second.capability)
        assertEquals(100L, first.createdAtMillis)
        assertEquals(15100L, first.expiresAtMillis)
        assertFalse(first.toString().contains(first.capability))
        assertTrue(second.generation > first.generation)
    }

    @Test fun wrongCallerMethodCapabilityAndMalformedInputKeepAttemptAlive() {
        val ticket = attempts.begin()
        assertNull(attempts.claim(10001, "shell-binder", ticket.capability))
        assertNull(attempts.claim(0, "shell-binder", ticket.capability))
        assertNull(attempts.claim(2000, "other", ticket.capability))
        assertNull(attempts.claim(2000, null, ticket.capability))
        for (value in listOf(null, "", "0".repeat(63), "0".repeat(65), "A".repeat(64), "g".repeat(64), "\u0000".repeat(64))) {
            assertNull(claim(value))
        }
        val wrong = (if (ticket.capability[0] == '0') "1" else "0") + ticket.capability.drop(1)
        assertNull(claim(wrong))
        assertSame(ticket, claim(ticket.capability))
        assertNull(claim(ticket.capability)) // Later binder/build failure cannot restore a claimed attempt.
    }

    @Test fun exactExpiryAndClockRegressionRefuseClaim() {
        val ticket = attempts.begin()
        now = ticket.expiresAtMillis
        assertNull(claim(ticket.capability))
        now = 500L
        val fresh = attempts.begin()
        now = 499L
        assertNull(claim(fresh.capability))
        now = fresh.expiresAtMillis - 1
        assertSame(fresh, claim(fresh.capability))
    }

    @Test fun obsoleteCapabilityAndTimeoutCannotConsumeReplacement() {
        assertNull(claim("0".repeat(64)))
        val old = attempts.begin()
        val fresh = attempts.begin()
        assertFalse(attempts.clear(old.generation))
        assertNull(claim(old.capability))
        assertSame(fresh, claim(fresh.capability))
        assertFalse(attempts.clear(fresh.generation))
        val cancelled = attempts.begin()
        assertTrue(attempts.clear(cancelled.generation))
        assertNull(claim(cancelled.capability))
    }

    @Test fun concurrentValidClaimsHaveExactlyOneWinner() {
        val ticket = attempts.begin()
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val claims = List(2) { pool.submit(Callable {
                ready.countDown()
                check(go.await(5, TimeUnit.SECONDS))
                claim(ticket.capability)
            }) }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            assertEquals(1, claims.count { it.get(5, TimeUnit.SECONDS) != null })
        } finally {
            go.countDown()
            pool.shutdownNow()
        }
    }
}
