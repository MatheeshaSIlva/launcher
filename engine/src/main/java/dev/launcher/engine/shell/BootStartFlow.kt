package dev.launcher.engine.shell

/**
 * Pure boot/start policy. Call on one serialized owner thread. No Android, settings or storage I/O lives here.
 * Inject a monotonic, boot-relative clock (elapsedRealtime). Supply fresh observations to every entry; execute returned commands in order and acknowledge each with [complete].
 * RESET_GUARD clears suppression AND the stored off timestamp. CACHED/FRESH success means handover completed.
 * Storage commands require synchronous, checked commit; RESTORE requires write-0 AND read-back-0.
 * CANCEL_WORK closes outstanding network/start work before subsequent commands execute. Never execute an obsolete
 * command ([isCurrent]); once a setting/storage command has begun, acknowledge it even after cancel.
 */
class BootStartFlow(private val nowMillis: () -> Long) {
    enum class State { IDLE, USB_OFF, WAITING_WIFI, STARTING, RESTORING, RESTORE_FAILED, SUPPRESSED, RUNNING, FAILED, CANCELLED }
    enum class Kind { RECORD_NOTE, ENABLE, CACHED, DISCOVER, FRESH, IDENTITY, RESTORE, CLEAR_NOTE, SUPPRESS, RESET_GUARD, CANCEL_WORK }
    data class Observation(
        val usbOn: Boolean,
        val wifiConnected: Boolean,
        val wirelessOn: Boolean,
        val bootCount: Int,
        val note: Boolean = false,
        val suppressedBoot: Int? = null,
        val offBoot: Int? = null,
        val offAtMillis: Long? = null,
    )
    /** Persist bootCount for guard commands; CLEAR_NOTE atomically clears note and stores offAtMillis + bootCount. */
    class Command internal constructor(
        val kind: Kind, val generation: Long, val deadlineMillis: Long?,
        val bootCount: Int, val offAtMillis: Long? = null,
    )
    var state = State.IDLE
        private set
    private var generation = 0L
    private var pending: Command? = null
    private var observed: Observation? = null
    private var note = false
    private var loaded = false
    private var suppressed = false
    private var guardDirty = false
    private var offAt: Long? = null
    private var waitingUntil = 0L
    private var overallUntil = 0L
    private var ending: State? = null
    private var resumeAfterCleanup = false
    private var resetAfterCleanup = false
    private var offWritten = false
    private var verifyAfterReset = false
    private var output = mutableListOf<Command>()

    /** One launcher/boot/network/USB trigger. Duplicates join; only a Developer-page action clears suppression. */
    fun trigger(value: Observation, userAction: Boolean = false): List<Command> = collect {
        observeInternal(value)
        if (userAction) resetAfterCleanup = true
        if (pending != null || state == State.WAITING_WIFI) return@collect
        if (note) {
            resumeAfterCleanup = true
            ending = null
            restore()
        } else if (state != State.RUNNING) begin()
        else if (userAction) { verifyAfterReset = true; issue(Kind.RESET_GUARD) }
    }

    /** Setting/network observation, without treating duplicate notifications as a fresh retry after failure. */
    fun observe(value: Observation): List<Command> = collect {
        val old = observed
        observeInternal(value)
        if (pending == null && !note &&
            ((old?.usbOn == false && value.usbOn) || (old?.wifiConnected == false && value.wifiConnected))) {
            if (state != State.RUNNING) begin()
        }
    }

    /** A helper death is delivered once; duplicates in USB_OFF or an active operation simply join. */
    fun helperDied(value: Observation): List<Command> = collect {
        observeInternal(value)
        if (offWritten && note && value.usbOn) {
            suppressed = true
            guardDirty = true
            ending = State.SUPPRESSED
            resumeAfterCleanup = false
            return@collect
        }
        if (pending != null || state == State.WAITING_WIFI || state == State.USB_OFF || state == State.SUPPRESSED) return@collect
        if (note) { resumeAfterCleanup = true; ending = null; restore(); return@collect }
        if (!value.usbOn) { state = State.USB_OFF; return@collect }
        val off = offAt
        if (off != null && nowMillis() >= off && nowMillis() - off < RESTART_MS) {
            suppressed = true
            guardDirty = true
            state = State.SUPPRESSED
            issue(Kind.SUPPRESS)
        } else begin()
    }

    /** Cancel and Forget share cleanup policy. Neither queued triggers nor late callbacks can bypass restoration. */
    fun cancel(): List<Command> = collect {
        resumeAfterCleanup = false
        resetAfterCleanup = false
        end(State.CANCELLED)
    }

    fun tick(value: Observation): List<Command> = collect {
        observeInternal(value)
        val deadline = pending?.deadlineMillis
        if (deadline != null && nowMillis() >= deadline) {
            when (pending?.kind) {
                Kind.CACHED -> fallback()
                Kind.DISCOVER, Kind.FRESH, Kind.IDENTITY -> end(State.FAILED)
                else -> Unit
            }
        } else if (state == State.WAITING_WIFI && nowMillis() >= waitingUntil) state = State.FAILED
    }

    /** ok means committed/read-back verified, connected/handover succeeded, endpoint found, or identity answered. */
    fun complete(command: Command, ok: Boolean, value: Observation): List<Command> = collect {
        if (pending !== command) return@collect
        observeInternal(value)
        if (pending !== command) return@collect
        pending = null
        when (command.kind) {
            Kind.RECORD_NOTE -> {
                if (!ok) { state = ending ?: State.FAILED; ending = null }
                else {
                    note = true
                    if (ending != null || nowMillis() >= overallUntil) end(ending ?: State.FAILED) else issue(Kind.ENABLE)
                }
            }
            Kind.ENABLE -> {
                if (!ok || !value.wirelessOn) end(ending ?: State.FAILED)
                else if (ending != null) restore() else issue(Kind.CACHED, ATTEMPT_MS)
            }
            Kind.RESTORE -> {
                if (!ok || value.wirelessOn) state = State.RESTORE_FAILED
                else { offAt = nowMillis(); offWritten = true; issue(Kind.CLEAR_NOTE) }
            }
            Kind.CLEAR_NOTE -> {
                if (!ok) state = State.RESTORE_FAILED
                else { note = false; offWritten = false; settled() }
            }
            Kind.SUPPRESS -> { guardDirty = !ok; state = State.SUPPRESSED }
            Kind.RESET_GUARD -> {
                if (!ok) { state = State.SUPPRESSED; resetAfterCleanup = false }
                else {
                    suppressed = false; guardDirty = false; offAt = null; resetAfterCleanup = false
                    if (verifyAfterReset) {
                        verifyAfterReset = false; state = State.STARTING; issue(Kind.IDENTITY, ATTEMPT_MS)
                    } else begin()
                }
            }
            Kind.CACHED, Kind.FRESH -> {
                if (nowMillis() >= (command.deadlineMillis ?: Long.MAX_VALUE)) {
                    if (command.kind == Kind.CACHED) fallback() else end(State.FAILED)
                } else if (ok) end(State.RUNNING)
                else if (command.kind == Kind.CACHED) fallback() else end(State.FAILED)
            }
            Kind.DISCOVER -> if (ok && nowMillis() < command.deadlineMillis!!) issue(Kind.FRESH, ATTEMPT_MS) else end(State.FAILED)
            Kind.IDENTITY -> state = if (ok && nowMillis() < command.deadlineMillis!!) State.RUNNING else State.FAILED
            Kind.CANCEL_WORK -> Unit
        }
    }

    fun isCurrent(command: Command): Boolean = pending === command

    private fun observeInternal(value: Observation) {
        if (!loaded) {
            note = value.note
            suppressed = value.suppressedBoot == value.bootCount
            offAt = value.offAtMillis.takeIf { value.offBoot == value.bootCount }
            loaded = true
        }
        observed = value
        if (!value.usbOn && (state == State.STARTING || state == State.WAITING_WIFI || state == State.RUNNING || state == State.RESTORING)) end(State.USB_OFF)
        // Once enabled, an observed return to 0 is refusal: never re-enable on this operation.
        else if (!value.wirelessOn && note && state == State.STARTING &&
            pending?.kind in listOf(Kind.CACHED, Kind.DISCOVER, Kind.FRESH)) end(State.FAILED)
        else if (state == State.WAITING_WIFI && value.wifiConnected && nowMillis() < waitingUntil) prepare()
    }

    private fun begin() {
        if (note || pending != null) return
        if (resetAfterCleanup) { issue(Kind.RESET_GUARD); return }
        val value = observed ?: return
        when {
            suppressed -> { state = State.SUPPRESSED; if (guardDirty) issue(Kind.SUPPRESS) }
            !value.usbOn -> state = State.USB_OFF
            else -> {
                generation++
                ending = null
                if (value.wifiConnected) prepare()
                else { state = State.WAITING_WIFI; waitingUntil = nowMillis() + WIFI_MS }
            }
        }
    }

    private fun prepare() {
        state = State.STARTING
        overallUntil = nowMillis() + OVERALL_MS
        if (observed!!.wirelessOn) issue(Kind.CACHED, ATTEMPT_MS) else issue(Kind.RECORD_NOTE)
    }

    private fun fallback() {
        abortWork()
        if (nowMillis() >= overallUntil) end(State.FAILED) else issue(Kind.DISCOVER, DISCOVERY_MS)
    }

    private fun end(result: State) {
        ending = result
        // A write already issued must settle before cleanup; cancellation cannot race a commit or write-1.
        if (pending?.kind in listOf(Kind.RECORD_NOTE, Kind.ENABLE, Kind.RESTORE, Kind.CLEAR_NOTE)) return
        abortWork()
        if (note) restore() else settled()
    }

    private fun restore() {
        state = State.RESTORING
        issue(if (offWritten) Kind.CLEAR_NOTE else Kind.RESTORE)
    }

    private fun settled() {
        val result = ending
        ending = null
        if (resetAfterCleanup && result == State.RUNNING) {
            verifyAfterReset = true; state = State.STARTING; issue(Kind.RESET_GUARD)
        } else if (guardDirty) { state = State.SUPPRESSED; issue(Kind.SUPPRESS) }
        else if (resumeAfterCleanup) { resumeAfterCleanup = false; begin() }
        else if (result == State.RUNNING) {
            state = State.STARTING
            issue(Kind.IDENTITY, ATTEMPT_MS)
        } else state = result ?: State.IDLE
    }

    private fun abortWork() {
        val old = pending
        pending = null
        if (old != null) output.add(Command(Kind.CANCEL_WORK, generation, null, observed!!.bootCount))
    }

    private fun issue(kind: Kind, timeout: Long? = null) {
        val deadline = timeout?.let {
            val until = nowMillis() + it
            if (kind in listOf(Kind.CACHED, Kind.DISCOVER, Kind.FRESH)) minOf(until, overallUntil) else until
        }
        val command = Command(kind, generation, deadline, observed!!.bootCount, offAt)
        pending = command
        output.add(command)
    }

    private inline fun collect(block: () -> Unit): List<Command> {
        output = mutableListOf()
        block()
        return output.toList()
    }

    companion object {
        const val WIFI_MS = 120_000L
        const val DISCOVERY_MS = 20_000L
        const val ATTEMPT_MS = 15_000L
        const val OVERALL_MS = 60_000L
        const val RESTART_MS = 60_000L
    }
}
