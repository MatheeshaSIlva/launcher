package dev.launcher.engine.shell

import dev.launcher.engine.shell.PairingFlow.BadCode
import dev.launcher.engine.shell.PairingFlow.Busy
import dev.launcher.engine.shell.PairingFlow.Claim
import dev.launcher.engine.shell.PairingFlow.NotCurrent
import dev.launcher.engine.shell.PairingFlow.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-001 slice 3: one pairing session at a time, and nothing late or stale changes it. */
class PairingFlowTest {
    private var now = 1_000L
    private val flow = PairingFlow({ now })

    @Test fun aReplyForTheCurrentSessionIsClaimedOnceAndPairs() {
        val s = flow.begin()
        val c = flow.claim(s.id, "012345") as Claim
        assertEquals("012345", c.code)                              // leading zero kept
        assertEquals(State.PAIRING, flow.state)
        assertSame(Busy, flow.claim(s.id, "012345"))               // a duplicate while pairing runs
        assertTrue(flow.finish(c, paired = true))
        assertEquals(State.PAIRED, flow.state)
        assertSame(NotCurrent, flow.claim(s.id, "012345"))         // the session ended with success
    }

    @Test fun aWrongCodeLeavesTheSessionOpenForAnotherTry() {
        val s = flow.begin()
        val c = flow.claim(s.id, "111111") as Claim
        assertTrue(flow.finish(c, paired = false))
        assertEquals(State.WAITING, flow.state)
        assertTrue(flow.claim(s.id, "222222") is Claim)
    }

    @Test fun aMalformedCodeIsRefusedWithoutConsumingAnything() {
        val s = flow.begin()
        for (bad in listOf(null, "", "12345", "1234567", "12 456", "١٢٣٤٥٦", "x".repeat(10_000))) assertSame(BadCode, flow.claim(s.id, bad))
        assertTrue(flow.claim(s.id, "123456") is Claim)
    }

    @Test fun repliesForAnotherOrNoSessionAreIgnored() {
        assertSame(NotCurrent, flow.claim("whatever", "123456"))   // nothing begun
        val old = flow.begin()
        val new = flow.begin()                                     // replaced
        assertSame(NotCurrent, flow.claim(old.id, "123456"))
        assertSame(NotCurrent, flow.claim(null, "123456"))
        assertTrue(flow.claim(new.id, "123456") is Claim)
    }

    @Test fun anExpiredSessionTakesNoReply() {
        val s = flow.begin()
        now += PairingFlow.SESSION_MS
        assertSame(NotCurrent, flow.claim(s.id, "123456"))
        assertEquals(State.EXPIRED, flow.state)
    }

    @Test fun aCancelMakesLateOutcomesChangeNothing() {
        val s = flow.begin()
        val c = flow.claim(s.id, "123456") as Claim
        flow.cancel()
        assertFalse(flow.isCurrent(c.generation))
        assertFalse(flow.finish(c, paired = true))                 // the pairing came back after the cancel
        assertEquals(State.CANCELLED, flow.state)
        assertSame(NotCurrent, flow.claim(s.id, "123456"))
    }

    @Test fun aLateOutcomeFromAReplacedSessionChangesNothing() {
        val first = flow.begin()
        val c = flow.claim(first.id, "123456") as Claim
        val second = flow.begin()
        assertFalse(flow.finish(c, paired = true))
        assertEquals(State.WAITING, flow.state)
        assertTrue(flow.claim(second.id, "654321") is Claim)
    }

    @Test fun nothingPrintsTheCodeOrTheSessionId() {
        val s = flow.begin()
        val c = flow.claim(s.id, "123456") as Claim
        assertFalse(s.toString().contains(s.id))
        assertFalse(c.toString().contains("123456"))
    }
}
