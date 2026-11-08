package dev.launcher.engine.shell

import dev.launcher.engine.shell.BootStartFlow.State
import dev.launcher.engine.shell.OwnHostStatus.Line
import org.junit.Assert.*
import org.junit.Test

class OwnHostStatusTest {
    private fun line(connected: Boolean, state: State?) = OwnHostStatus.line(connected, false, state, keyOk = true, usbOn = true, paired = true, failed = false)

    @Test fun connectedIsRunningOnlyOnceTheFlowHasFinished() {
        assertEquals(Line.RUNNING, line(true, State.RUNNING))
        assertEquals(Line.STARTING, line(true, State.STARTING))
    }
    @Test fun aFailedRestoreShowsOverAConnectedService() = assertEquals(Line.RESTORE_FAILED, line(true, State.RESTORE_FAILED))
    @Test fun restoringShowsOverAConnectedService() = assertEquals(Line.TURNING_OFF, line(true, State.RESTORING))
    @Test fun aFailedIdentityIsNotRunning() = assertEquals(Line.NOT_ANSWERING, line(true, State.FAILED))
    @Test fun aFailedRestoreShowsOverALostKeyAndUsbOff() =
        assertEquals(Line.RESTORE_FAILED, OwnHostStatus.line(false, false, State.RESTORE_FAILED, keyOk = false, usbOn = false, paired = true, failed = true))
    @Test fun startNowIsOfferedUntilRunning() {
        assertTrue(OwnHostStatus.offerStart(true, State.RESTORE_FAILED))
        assertTrue(OwnHostStatus.offerStart(true, State.FAILED))
        assertFalse(OwnHostStatus.offerStart(true, State.RUNNING))
        assertFalse(OwnHostStatus.offerStart(false, State.FAILED))
    }
    @Test fun restoredNeedsTheWriteAndAReadOfZero() {
        assertTrue(OwnHostStatus.restored(true, 0))
        assertFalse(OwnHostStatus.restored(false, 0))
        assertFalse(OwnHostStatus.restored(true, null))
        assertFalse(OwnHostStatus.restored(false, null))
        assertFalse(OwnHostStatus.restored(true, 1))
    }
    @Test fun onlyAPairedHostStarts() {
        assertEquals(OwnHostStatus.Entry.START, OwnHostStatus.entry(true, false))
        assertEquals(OwnHostStatus.Entry.START, OwnHostStatus.entry(true, true))
        assertEquals(OwnHostStatus.Entry.SETTLE_ONLY, OwnHostStatus.entry(false, true))
        assertEquals(OwnHostStatus.Entry.NOTHING, OwnHostStatus.entry(false, false))
    }
}
