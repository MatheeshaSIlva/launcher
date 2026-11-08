package dev.launcher.engine.shell

import dev.launcher.engine.shell.HandoverGate.Ended
import dev.launcher.engine.shell.StartAttempt.Companion.HANDOVER_METHOD
import dev.launcher.engine.shell.StartAttempt.Companion.SHELL_UID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-001 2b, Codex's review: the races between a handover, its acknowledgement, a timeout, a newer start and a death. */
class HandoverGateTest {
    private var now = 1_000L
    private val gate = HandoverGate({ now })
    private val ready = HandoverGate.READY_METHOD

    private fun claim(t: StartAttempt.Ticket, uid: Int = SHELL_UID) = gate.claim(uid, HANDOVER_METHOD, t.capability)

    @Test fun claimThenReadyConnectsThatGenerationOnce() {
        val t = gate.begin()
        val a = claim(t)!!
        assertTrue(gate.isStarting)
        assertEquals(t.generation, gate.ready(SHELL_UID, ready, a.ack))
        assertFalse(gate.isStarting)
        assertTrue(gate.isConnected(t.generation))
        assertNull(gate.ready(SHELL_UID, ready, a.ack))           // replayed ack
        assertNull(claim(t))                                       // replayed capability
    }

    @Test fun nothingIsPublishedBeforeTheAck() {
        val t = gate.begin()
        claim(t)!!
        assertEquals(0L, gate.connectedGeneration)
    }

    @Test fun aTimeoutBetweenClaimAndAckRefusesTheAck() {
        val t = gate.begin()
        val a = claim(t)!!
        assertTrue(gate.expire(t.generation))
        assertNull(gate.ready(SHELL_UID, ready, a.ack))
        assertEquals(0L, gate.connectedGeneration)
    }

    @Test fun anAckPastTheAttemptsDeadlineIsRefusedEvenBeforeTheTimeoutRuns() {
        val t = gate.begin()
        val a = claim(t)!!
        now += StartAttempt.TIMEOUT_MS
        assertNull(gate.ready(SHELL_UID, ready, a.ack))
    }

    @Test fun aNewerStartSupersedesAClaimedOne() {
        val old = gate.begin()
        val a = claim(old)!!
        val new = gate.begin()
        assertNull(gate.ready(SHELL_UID, ready, a.ack))           // the old handover can no longer publish
        assertFalse(gate.expire(old.generation))                   // nor its timeout end the new start
        assertTrue(gate.isStarting)
        val b = claim(new)!!
        assertEquals(new.generation, gate.ready(SHELL_UID, ready, b.ack))
    }

    @Test fun anObsoleteDeathCannotEndANewerConnection() {
        val old = gate.begin()
        val a = claim(old)!!
        gate.begin().let { claim(it)!!.let { b -> gate.ready(SHELL_UID, ready, b.ack) } }
        val current = gate.connectedGeneration
        assertEquals(Ended.NOTHING, gate.lost(old.generation))
        assertTrue(gate.isConnected(current))
        assertNull(gate.ready(SHELL_UID, ready, a.ack))
        assertEquals(Ended.CONNECTION, gate.lost(current))
        assertFalse(gate.isConnected(current))
    }

    @Test fun aDeathBeforeTheAckEndsTheStart() {
        val t = gate.begin()
        val a = claim(t)!!
        assertEquals(Ended.START, gate.lost(t.generation))
        assertFalse(gate.isStarting)
        assertNull(gate.ready(SHELL_UID, ready, a.ack))
    }

    @Test fun aSecondClaimOfTheSameAttemptIsRefused() {
        val t = gate.begin()
        assertNotNull(claim(t))
        assertNull(claim(t))                                       // two valid handovers: only the first is taken
    }

    @Test fun wrongCallersAndMethodsChangeNothing() {
        val t = gate.begin()
        assertNull(claim(t, uid = 10_123))
        assertNull(gate.claim(SHELL_UID, ready, t.capability))     // the ack's method with the capability
        val a = claim(t)!!                                          // the attempt survived the refusals
        assertNull(gate.ready(10_123, ready, a.ack))
        assertNull(gate.ready(SHELL_UID, HANDOVER_METHOD, a.ack))
        assertNull(gate.ready(SHELL_UID, ready, "0".repeat(64)))
        assertNull(gate.ready(SHELL_UID, ready, null))
        assertEquals(t.generation, gate.ready(SHELL_UID, ready, a.ack))
    }

    @Test fun endingTheStartInFlightRefusesItsLaterSteps() {   // Forget pairing: nothing can publish after it
        val t = gate.begin()
        assertEquals(t.generation, gate.startingGeneration)
        val a = claim(t)!!
        assertTrue(gate.expire(gate.startingGeneration))
        assertEquals(0L, gate.startingGeneration)
        assertNull(gate.ready(SHELL_UID, ready, a.ack))
        assertEquals(0L, gate.connectedGeneration)
    }

    @Test fun forgettingBetweenTheAckAndItsPublicationLeavesNothingToPublish() {
        val t = gate.begin()
        val a = claim(t)!!
        assertEquals(t.generation, gate.ready(SHELL_UID, ready, a.ack))   // accepted; its publication is queued on the main thread
        assertEquals(0L, gate.endAll())                                   // Forget runs first
        assertFalse(gate.isConnected(t.generation))                       // the queued publication sees this and stops the helper
        assertEquals(0L, gate.connectedGeneration)
    }

    @Test fun forgettingEndsAStartInFlightAndARunningConnection() {
        val t = gate.begin()
        val a = claim(t)!!
        assertEquals(t.generation, gate.endAll())
        assertNull(gate.ready(SHELL_UID, ready, a.ack))
        val u = gate.begin()
        gate.ready(SHELL_UID, ready, claim(u)!!.ack)
        assertEquals(0L, gate.endAll())
        assertFalse(gate.isConnected(u.generation))
        assertEquals(Ended.NOTHING, gate.lost(u.generation))              // its death afterwards ends nothing more
    }

    @Test fun ticketsAndAcceptancesNeverPrintTheirSecrets() {
        val t = gate.begin()
        val a = claim(t)!!
        assertFalse(a.toString().contains(a.ack))
        assertFalse(t.toString().contains(t.capability))
    }
}
