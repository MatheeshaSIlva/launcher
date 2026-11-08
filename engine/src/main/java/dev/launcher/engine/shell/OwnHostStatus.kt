package dev.launcher.engine.shell

/** The own host's small judgements (T-001 slice 4), apart from Android so they can be tested. */
object OwnHostStatus {
    enum class Entry { NOTHING, SETTLE_ONLY, START }
    enum class Line { RESTORE_FAILED, TURNING_OFF, RUNNING, NOT_ANSWERING, KEY_LOST, USB_OFF, WAITING_WIFI, KEEPS_STOPPING, STARTING, COULD_NOT_CONNECT, PAIRED, NOT_PAIRED }

    /** Only a paired host starts; a note without a pairing (forgotten, killed before it settled) is only settled. */
    fun entry(paired: Boolean, note: Boolean) = when {
        paired -> Entry.START
        note -> Entry.SETTLE_ONLY
        else -> Entry.NOTHING
    }

    /** Wireless debugging counts as off only after a write that worked and a read-back of 0. */
    fun restored(written: Boolean, readBack: Int?) = written && readBack == 0

    fun offerStart(paired: Boolean, state: BootStartFlow.State?) = paired && state != BootStartFlow.State.RUNNING

    /** What the page says: a debugging port we could not close first, and "running" only once the flow has finished. */
    fun line(connected: Boolean, connecting: Boolean, state: BootStartFlow.State?, keyOk: Boolean, usbOn: Boolean, paired: Boolean, failed: Boolean) = when {
        state == BootStartFlow.State.RESTORE_FAILED -> Line.RESTORE_FAILED
        state == BootStartFlow.State.RESTORING -> Line.TURNING_OFF
        !usbOn -> Line.USB_OFF
        connected && state == BootStartFlow.State.RUNNING -> Line.RUNNING
        connected && state == BootStartFlow.State.FAILED -> Line.NOT_ANSWERING
        connected -> Line.STARTING
        !keyOk -> Line.KEY_LOST
        state == BootStartFlow.State.WAITING_WIFI -> Line.WAITING_WIFI
        state == BootStartFlow.State.SUPPRESSED -> Line.KEEPS_STOPPING
        connecting || state == BootStartFlow.State.STARTING -> Line.STARTING
        failed -> Line.COULD_NOT_CONNECT
        paired -> Line.PAIRED
        else -> Line.NOT_PAIRED
    }
}
