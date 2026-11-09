package dev.launcher.engine.shell

import org.junit.Assert.*
import org.junit.Test

class StockHandoverTest {
    private var now = 0L
    private val due = mutableListOf<Pair<Long, () -> Unit>>()
    private var released = 0
    private val handover = StockHandover({ delay, run -> due.add(now + delay to run) }, { released++ })

    private fun advance(ms: Long) {
        now += ms
        due.filter { it.first <= now }.also { due.removeAll(it) }.forEach { it.second() }
    }

    @Test fun oursWaitsForTheStockBarAfterTheFlagsWereSent() {
        handover.begin()
        advance(50)
        handover.flagsSent(true)
        advance(StockHandover.STOCK_EXIT_MS - 1)
        assertEquals(0, released)
        assertTrue(handover.holding)
        advance(1)
        assertEquals(1, released)
        assertFalse(handover.holding)
    }
    @Test fun aFailedCallShowsOursAtOnce() {
        handover.begin()
        handover.flagsSent(false)
        assertEquals(1, released)
    }
    @Test fun aCallThatNeverReturnsCannotKeepOursAway() {
        handover.begin()
        advance(StockHandover.GIVE_UP_MS)
        assertEquals(1, released)
    }
    @Test fun itReleasesOnce() {
        handover.begin()
        handover.flagsSent(true)
        advance(StockHandover.GIVE_UP_MS * 2)
        handover.cancel()
        handover.flagsSent(true)
        advance(StockHandover.GIVE_UP_MS * 2)
        assertEquals(1, released)
    }
    @Test fun aPanelPulledDuringTheWaitShowsOursAndTheOldTimersDoNothing() {
        handover.begin()
        handover.flagsSent(true)
        handover.cancel()
        assertEquals(1, released)
        handover.begin()                        // a second handover, with the first one's timers still due
        advance(StockHandover.STOCK_EXIT_MS)
        assertEquals(1, released)
        assertTrue(handover.holding)
    }
    @Test fun nothingHeldNothingReleased() {
        handover.flagsSent(true)
        handover.cancel()
        advance(StockHandover.GIVE_UP_MS)
        assertEquals(0, released)
    }
}
