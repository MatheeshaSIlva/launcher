package dev.launcher.app.shade.nc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BufferSetsTest {
    private val closed = mutableListOf<String>()
    private val sets = BufferSets<String> { closed += it }

    /** The leak (code review, 2026-11-01): a stream started again made a new set and the one before was never closed. */
    @Test fun aReplacedSetIsClosedOnceTheNewOneDrew() {
        sets.adopt("a")
        sets.adopt("b")
        assertEquals("the bands may still draw a buffer of the set replaced", emptyList<String>(), closed)
        sets.delivered()
        assertEquals(listOf("a"), closed)
        assertSame("b", sets.current)
    }

    @Test fun stopClosesEverySetMadeEvenBeforeAFrameCame() {
        sets.adopt("a")
        sets.adopt("b")
        sets.stop()
        assertEquals(setOf("a", "b"), closed.toSet())
        assertEquals(2, closed.size)
        assertNull(sets.current)
    }

    @Test fun threeSetsWithNoFrameBetweenLeaveNone() {
        sets.adopt("a")
        sets.adopt("b")
        sets.adopt("c")
        assertEquals("a set two back is drawn by nobody", listOf("a"), closed)
        sets.stop()
        assertEquals(listOf("a", "c", "b"), closed)
    }

    @Test fun theSameSetTakenUpAgainIsNotClosed() {
        sets.adopt("a")
        sets.adopt("a")
        sets.delivered()
        assertEquals(emptyList<String>(), closed)
        sets.stop()
        assertEquals(listOf("a"), closed)
    }

    @Test fun eachSetIsClosedOnceOnly() {
        sets.adopt("a")
        sets.adopt("b")
        sets.delivered()
        sets.delivered()
        sets.stop()
        sets.stop()
        assertEquals(listOf("a", "b"), closed)
    }
}
