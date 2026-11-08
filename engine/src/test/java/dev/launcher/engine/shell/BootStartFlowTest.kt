package dev.launcher.engine.shell

import dev.launcher.engine.shell.BootStartFlow.Kind
import dev.launcher.engine.shell.BootStartFlow.State
import org.junit.Assert.*
import org.junit.Test

class BootStartFlowTest {
    private var now = 1_000L
    private var value = BootStartFlow.Observation(true, true, false, 7)
    private var flow = BootStartFlow { now }
    private val writes = mutableListOf<Kind>()
    private fun only(commands: List<BootStartFlow.Command>, kind: Kind): BootStartFlow.Command {
        assertEquals(kind, commands.last().kind)
        return commands.last()
    }
    private fun done(command: BootStartFlow.Command, ok: Boolean = true): List<BootStartFlow.Command> {
        val current = flow.isCurrent(command)
        if (ok && current) when (command.kind) {
            Kind.RECORD_NOTE -> value = value.copy(note = true)
            Kind.ENABLE -> value = value.copy(wirelessOn = true)
            Kind.RESTORE -> value = value.copy(wirelessOn = false)
            Kind.CLEAR_NOTE -> value = value.copy(note = false, offBoot = value.bootCount, offAtMillis = now)
            Kind.SUPPRESS -> value = value.copy(suppressedBoot = value.bootCount)
            Kind.RESET_GUARD -> value = value.copy(suppressedBoot = null, offBoot = null, offAtMillis = null)
            else -> Unit
        }
        if (current) writes.add(command.kind)
        return flow.complete(command, ok, value)
    }
    private fun cached(): BootStartFlow.Command {
        var next = flow.trigger(value)
        if (!value.wirelessOn) {
            next = done(only(next, Kind.RECORD_NOTE))
            next = done(only(next, Kind.ENABLE))
        }
        return only(next, Kind.CACHED)
    }
    private fun running() {
        var next = done(cached())
        if (value.note) {
            next = done(only(next, Kind.RESTORE))
            next = done(only(next, Kind.CLEAR_NOTE))
        }
        done(only(next, Kind.IDENTITY))
        assertEquals(State.RUNNING, flow.state)
    }

    @Test fun successRestoresOnceBeforeIdentityAndRunning() {
        running()
        assertEquals(1, writes.count { it == Kind.RESTORE })
        assertTrue(writes.indexOf(Kind.CLEAR_NOTE) < writes.indexOf(Kind.IDENTITY))
    }
    @Test fun userOnIsUntouchedOnSuccessFailureAndCancel() {
        for (outcome in 0..2) {
            flow = BootStartFlow { now }; writes.clear(); value = value.copy(wirelessOn = true)
            val command = cached()
            when (outcome) {
                0 -> done(only(done(command), Kind.IDENTITY))
                1 -> done(only(done(only(done(command, false), Kind.DISCOVER)), Kind.FRESH), false)
                else -> flow.cancel()
            }
            assertFalse(writes.any { it in listOf(Kind.RECORD_NOTE, Kind.ENABLE, Kind.RESTORE, Kind.CLEAR_NOTE) })
        }
    }
    @Test fun cachedFailureHasOneDiscoveryAndFreshAttemptInSameGeneration() {
        val c = cached()
        val d = only(done(c, false), Kind.DISCOVER)
        val f = only(done(d), Kind.FRESH)
        assertEquals(c.generation, d.generation); assertEquals(c.generation, f.generation)
        val r = only(done(f, false), Kind.RESTORE)
        done(only(done(r), Kind.CLEAR_NOTE))
        assertEquals(State.FAILED, flow.state)
        assertEquals(1, writes.count { it == Kind.RESTORE })
    }
    @Test fun freshSuccessRestoresAndChecksIdentity() {
        val d = only(done(cached(), false), Kind.DISCOVER)
        val r = only(done(only(done(d), Kind.FRESH)), Kind.RESTORE)
        done(only(done(only(done(r), Kind.CLEAR_NOTE)), Kind.IDENTITY))
        assertEquals(State.RUNNING, flow.state)
    }
    @Test fun crashAfterNoteBeforeEnableCleansBeforeUsbPrerequisite() {
        done(only(flow.trigger(value), Kind.RECORD_NOTE)) // ENABLE was returned but never executed
        value = value.copy(usbOn = false)
        flow = BootStartFlow { now }
        val r = only(flow.trigger(value), Kind.RESTORE)
        done(only(done(r), Kind.CLEAR_NOTE))
        assertEquals(State.USB_OFF, flow.state)
        assertFalse(value.note)
    }
    @Test fun crashAfterEnableAlsoRestoresWithoutAdoptingTheLeftoverOne() {
        cached()
        flow = BootStartFlow { now }
        val r = only(flow.trigger(value), Kind.RESTORE)
        val next = done(only(done(r), Kind.CLEAR_NOTE))
        assertEquals(Kind.RECORD_NOTE, next.single().kind)
    }
    @Test fun cleanupFailureWithUsbOffKeepsNoteAndBlocksUntilRetry() {
        value = value.copy(note = true, wirelessOn = true, usbOn = false)
        val r = only(flow.trigger(value), Kind.RESTORE)
        assertTrue(done(r, false).isEmpty())
        assertEquals(State.RESTORE_FAILED, flow.state); assertTrue(value.note)
        assertTrue(flow.observe(value).isEmpty())
        done(only(done(only(flow.trigger(value), Kind.RESTORE)), Kind.CLEAR_NOTE))
        assertEquals(State.USB_OFF, flow.state)
    }
    @Test fun usbOffMidAttemptCancelsWorkAndRestoresEvenOnFailure() {
        val c = cached()
        value = value.copy(usbOn = false)
        val commands = flow.observe(value)
        assertEquals(Kind.CANCEL_WORK, commands.first().kind)
        val r = only(commands, Kind.RESTORE)
        assertTrue(done(c).isEmpty())
        done(r, false)
        assertEquals(State.RESTORE_FAILED, flow.state)
        done(only(done(only(flow.trigger(value), Kind.RESTORE)), Kind.CLEAR_NOTE))
        assertEquals(State.USB_OFF, flow.state)
    }
    @Test fun cancelNewStartAndLateCleanupCannotOverlap() {
        val c = cached()
        val r = only(flow.cancel(), Kind.RESTORE)
        assertFalse(flow.isCurrent(c))
        assertTrue(flow.trigger(value).isEmpty())
        val clear = only(done(r), Kind.CLEAR_NOTE)
        assertTrue(flow.trigger(value).isEmpty())
        done(clear)
        assertEquals(State.CANCELLED, flow.state)
        val next = only(flow.trigger(value), Kind.RECORD_NOTE)
        assertTrue(done(r).isEmpty()); assertTrue(done(clear).isEmpty())
        assertTrue(flow.isCurrent(next))
        assertEquals(1, writes.count { it == Kind.RESTORE })
    }
    @Test fun cancelDuringNoteCommitWaitsForItsOutcomeThenRestores() {
        val record = only(flow.trigger(value), Kind.RECORD_NOTE)
        assertTrue(flow.cancel().isEmpty())
        val r = only(done(record), Kind.RESTORE)
        done(only(done(r), Kind.CLEAR_NOTE))
        assertEquals(State.CANCELLED, flow.state)
        assertFalse(writes.contains(Kind.ENABLE))
    }
    @Test fun cancelDuringEnableWaitsThenRestores() {
        val enable = only(done(only(flow.trigger(value), Kind.RECORD_NOTE)), Kind.ENABLE)
        assertTrue(flow.cancel().isEmpty())
        done(only(done(only(done(enable), Kind.RESTORE)), Kind.CLEAR_NOTE))
        assertEquals(State.CANCELLED, flow.state)
    }
    @Test fun noteCommitFailureNeverEnables() {
        assertTrue(done(only(flow.trigger(value), Kind.RECORD_NOTE), false).isEmpty())
        assertEquals(State.FAILED, flow.state)
        assertEquals(listOf(Kind.RECORD_NOTE), writes)
    }
    @Test fun refusedEnableNeverWritesOneAgain() {
        val enable = only(done(only(flow.trigger(value), Kind.RECORD_NOTE)), Kind.ENABLE)
        val r = only(done(enable, false), Kind.RESTORE)
        done(only(done(r), Kind.CLEAR_NOTE))
        assertEquals(State.FAILED, flow.state)
        assertEquals(1, writes.count { it == Kind.ENABLE })
    }
    @Test fun refusalAfterEnableStopsCachedAndDoesNotDiscoverOrReenable() {
        val c = cached()
        value = value.copy(wirelessOn = false)
        val r = only(flow.observe(value), Kind.RESTORE)
        assertTrue(done(c, false).isEmpty())
        done(only(done(r), Kind.CLEAR_NOTE))
        assertEquals(State.FAILED, flow.state)
    }
    @Test fun clearCommitFailureRetriesClearWithoutAnotherOffWrite() {
        val r = only(flow.cancelAfterCached(), Kind.RESTORE)
        val clear = only(done(r), Kind.CLEAR_NOTE)
        done(clear, false)
        assertEquals(State.RESTORE_FAILED, flow.state)
        assertEquals(Kind.CLEAR_NOTE, flow.trigger(value).single().kind)
    }
    private fun BootStartFlow.cancelAfterCached(): List<BootStartFlow.Command> { cached(); return cancel() }

    @Test fun deathWithUsbOnPersistsSuppressionAcrossProcessRecreationAndQueuedTriggers() {
        running()
        done(only(flow.helperDied(value), Kind.SUPPRESS))
        assertEquals(State.SUPPRESSED, flow.state)
        flow = BootStartFlow { now }
        assertTrue(flow.trigger(value).isEmpty())
        assertTrue(flow.trigger(value).isEmpty())
        assertEquals(State.SUPPRESSED, flow.state)
    }
    @Test fun explicitUserActionAndRebootClearSuppression() {
        value = value.copy(suppressedBoot = 7, offBoot = 7, offAtMillis = now)
        flow.trigger(value)
        val reset = only(flow.trigger(value, userAction = true), Kind.RESET_GUARD)
        assertEquals(Kind.RECORD_NOTE, done(reset).single().kind)
        flow = BootStartFlow { now }
        value = value.copy(suppressedBoot = 7, bootCount = 8)
        assertEquals(Kind.RECORD_NOTE, flow.trigger(value).single().kind)
    }
    @Test fun offDeathOnInsideWindowAllowsOneAttemptWithDuplicateObserversJoining() {
        running()
        value = value.copy(usbOn = false)
        assertTrue(flow.observe(value).isEmpty())
        assertTrue(flow.helperDied(value).isEmpty())
        assertEquals(State.USB_OFF, flow.state)
        value = value.copy(usbOn = true)
        val record = only(flow.observe(value), Kind.RECORD_NOTE)
        assertTrue(flow.observe(value).isEmpty()); assertTrue(flow.trigger(value).isEmpty())
        assertTrue(flow.isCurrent(record))
    }
    @Test fun deathOutsideWindowStartsOnceAndDuplicatesJoin() {
        running(); now += BootStartFlow.RESTART_MS
        val record = only(flow.helperDied(value), Kind.RECORD_NOTE)
        assertTrue(flow.helperDied(value).isEmpty())
        assertTrue(flow.isCurrent(record))
    }
    @Test fun restoredTimestampSurvivesProcessRecreation() {
        running(); flow = BootStartFlow { now }
        done(only(flow.helperDied(value), Kind.SUPPRESS))
        assertEquals(State.SUPPRESSED, flow.state)
    }
    @Test fun usbOffNeverStartsOrWritesAnything() {
        value = value.copy(usbOn = false)
        assertTrue(flow.trigger(value).isEmpty())
        assertEquals(State.USB_OFF, flow.state)
    }
    @Test fun wifiIsWaitedForBeforeRecordingAndTimeoutNeedsAnotherTrigger() {
        value = value.copy(wifiConnected = false)
        assertTrue(flow.trigger(value).isEmpty()); assertEquals(State.WAITING_WIFI, flow.state)
        now += BootStartFlow.WIFI_MS
        assertTrue(flow.tick(value).isEmpty()); assertEquals(State.FAILED, flow.state)
        value = value.copy(wifiConnected = true)
        assertEquals(Kind.RECORD_NOTE, flow.observe(value).single().kind)
    }
    @Test fun wifiArrivalBeforeTimeoutStartsAndDuplicatesJoin() {
        value = value.copy(wifiConnected = false); flow.trigger(value)
        value = value.copy(wifiConnected = true)
        only(flow.observe(value), Kind.RECORD_NOTE)
        assertTrue(flow.observe(value).isEmpty())
    }
    @Test fun cachedTimeoutFallsBackAndLateCachedSuccessCannotWin() {
        val c = cached(); now = c.deadlineMillis!!
        val d = only(flow.tick(value), Kind.DISCOVER)
        assertTrue(done(c).isEmpty()); assertTrue(flow.isCurrent(d))
        now = d.deadlineMillis!!
        only(flow.tick(value), Kind.RESTORE)
    }
    @Test fun finalFreshTimeoutAndIdentityFailureNeverReportRunning() {
        val d = only(done(cached(), false), Kind.DISCOVER)
        val f = only(done(d), Kind.FRESH)
        now = f.deadlineMillis!!
        val r = only(flow.tick(value), Kind.RESTORE)
        done(only(done(r), Kind.CLEAR_NOTE)); assertEquals(State.FAILED, flow.state)
        flow = BootStartFlow { now }
        val r2 = only(done(cached()), Kind.RESTORE)
        val id = only(done(only(done(r2), Kind.CLEAR_NOTE)), Kind.IDENTITY)
        done(id, false); assertEquals(State.FAILED, flow.state)
    }
    @Test fun restoreRequiresReadBackZero() {
        val r = only(flow.cancelAfterCached(), Kind.RESTORE)
        flow.complete(r, true, value) // write reported success, read-back is still 1
        assertEquals(State.RESTORE_FAILED, flow.state); assertTrue(value.note)
    }
    @Test fun usbOffDuringCleanupFinishesRestorationWithoutCheckingIdentity() {
        val r = only(done(cached()), Kind.RESTORE)
        value = value.copy(usbOn = false)
        assertTrue(flow.observe(value).isEmpty())
        assertTrue(done(only(done(r), Kind.CLEAR_NOTE)).isEmpty())
        assertEquals(State.USB_OFF, flow.state)
    }
    @Test fun deathAfterOffBeforeNoteClearPersistsGuardBeforeAnyRestart() {
        val r = only(done(cached()), Kind.RESTORE)
        val clear = only(done(r), Kind.CLEAR_NOTE)
        assertTrue(flow.helperDied(value).isEmpty())
        done(only(done(clear), Kind.SUPPRESS))
        assertEquals(State.SUPPRESSED, flow.state)
        assertTrue(flow.trigger(value).isEmpty())
    }
    @Test fun failedGuardCommitIsRetriedByNextTrigger() {
        running()
        done(only(flow.helperDied(value), Kind.SUPPRESS), false)
        assertEquals(State.SUPPRESSED, flow.state)
        done(only(flow.trigger(value), Kind.SUPPRESS))
        assertEquals(value.bootCount, value.suppressedBoot)
    }
    @Test fun overallBudgetIncludesSlowNoteCommitAndBoundsEveryNetworkCommand() {
        val record = only(flow.trigger(value), Kind.RECORD_NOTE)
        now += 25_000
        val c = only(done(only(done(record), Kind.ENABLE)), Kind.CACHED)
        now = c.deadlineMillis!!
        val d = only(flow.tick(value), Kind.DISCOVER)
        now += 19_999
        val f = only(done(d), Kind.FRESH)
        assertEquals(61_000L, f.deadlineMillis)
        now = f.deadlineMillis!!
        only(flow.tick(value), Kind.RESTORE)
    }
    @Test fun noteCommitPastOverallDeadlineCleansWithoutEnabling() {
        val record = only(flow.trigger(value), Kind.RECORD_NOTE)
        now += BootStartFlow.OVERALL_MS
        val r = only(done(record), Kind.RESTORE)
        done(only(done(r), Kind.CLEAR_NOTE))
        assertEquals(State.FAILED, flow.state)
        assertFalse(writes.contains(Kind.ENABLE))
    }    @Test fun explicitUserActionWhileRunningClearsPreviousOffTimestamp() {
        running()
        val reset = only(flow.trigger(value, userAction = true), Kind.RESET_GUARD)
        done(only(done(reset), Kind.IDENTITY))
        assertEquals(Kind.RECORD_NOTE, flow.helperDied(value).single().kind)
    }
    @Test fun lateDiscoveryAndFreshCallbacksCannotChangeCancelledCleanup() {
        for (fresh in listOf(false, true)) {
            flow = BootStartFlow { now }; value = value.copy(note = false, wirelessOn = false)
            val d = only(done(cached(), false), Kind.DISCOVER)
            val old = if (fresh) only(done(d), Kind.FRESH) else d
            val r = only(flow.cancel(), Kind.RESTORE)
            assertTrue(done(old).isEmpty()); assertTrue(flow.isCurrent(r))
            done(only(done(r), Kind.CLEAR_NOTE))
            assertEquals(State.CANCELLED, flow.state)
        }
    }}
