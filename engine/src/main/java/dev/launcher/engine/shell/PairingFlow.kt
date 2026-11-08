package dev.launcher.engine.shell

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * One pairing with Wireless debugging at a time (T-001 slice 3), every decision under one lock: [begin] opens a session
 * (a random id the notification's reply carries back, valid for [SESSION_MS]); [claim] takes a reply for it (the
 * current session only, one at a time, the code parsed by [PairingCode] before anything is consumed); [finish] reports
 * the pairing's outcome for that claim (a wrong code leaves the session open for another try; success ends it); [cancel]
 * ends it. A claim, finish or cancel of an older session changes nothing, so late work never revives a cancelled or
 * replaced pairing. Never logs or keeps a code.
 */
class PairingFlow(private val nowMillis: () -> Long, private val random: SecureRandom = SecureRandom()) {
    enum class State { IDLE, WAITING, PAIRING, PAIRED, CANCELLED, EXPIRED }

    /** The notification's session: its [id] goes into the reply's PendingIntent (never the endpoint or identity). */
    class Session internal constructor(val id: String, val expiresAtMillis: Long) {
        override fun toString() = "PairingFlow.Session(expires=$expiresAtMillis)"
    }

    /** What [claim] gives: a [Claim] or a [Refusal]. */
    sealed interface Outcome

    /** A reply taken: the code to pair with, under the session's [generation]. */
    class Claim internal constructor(val generation: Long, val code: String) : Outcome {
        override fun toString() = "PairingFlow.Claim(generation=$generation)"
    }

    sealed interface Refusal : Outcome
    /** Not the current session (stale, replaced, cancelled or expired): the reply is ignored. */
    object NotCurrent : Refusal
    /** A pairing with this session is already running. */
    object Busy : Refusal
    /** Not six digits: the session stays open for another try. */
    object BadCode : Refusal

    private var session: Session? = null
    private var generation = 0L
    private var busy = false
    private var open: java.io.Closeable? = null
    private var current: State = State.IDLE
    val state: State get() = synchronized(this) { current }

    @Synchronized fun begin(): Session {
        generation++
        busy = false
        closeOpen()
        val id = buildString(32) { for (b in ByteArray(16).also(random::nextBytes)) append("%02x".format(b.toInt() and 0xff)) }
        current = State.WAITING
        return Session(id, nowMillis() + SESSION_MS).also { session = it }
    }

    /** A reply for session [id] with [input] (read bounded by [PairingCode]): the claim, or why it is refused. */
    @Synchronized fun claim(id: String?, input: CharSequence?): Outcome {
        val s = session ?: return NotCurrent
        if (id == null || !MessageDigest.isEqual(id.toByteArray(Charsets.US_ASCII), s.id.toByteArray(Charsets.US_ASCII))) return NotCurrent
        if (nowMillis() >= s.expiresAtMillis) { expire(); return NotCurrent }
        if (busy) return Busy
        val code = PairingCode.parse(input) ?: return BadCode
        busy = true
        current = State.PAIRING
        return Claim(generation, code)
    }

    /** [claim]'s pairing ended: true when it still counts (its session is current). A wrong code reopens the session. */
    @Synchronized fun finish(claim: Claim, paired: Boolean): Boolean {
        if (claim.generation != generation || session == null) return false
        if (nowMillis() >= session!!.expiresAtMillis) { expire(); return false }
        busy = false
        open = null
        if (paired) { session = null; current = State.PAIRED } else current = State.WAITING
        return true
    }

    /** Ends the session in flight, if any: its later replies and outcomes change nothing. */
    @Synchronized fun cancel() {
        if (session == null && !busy && current != State.PAIRED) return
        generation++
        session = null
        busy = false
        closeOpen()
        current = State.CANCELLED
    }

    /**
     * The claim's connection, made only while it is current and registered in one step with [cancel]: a cancel before this
     * makes nothing ([make] is not called), a cancel after it closes what was made. Null: the claim is obsolete.
     */
    @Synchronized fun <T : java.io.Closeable> admit(claim: Claim, make: () -> T): T? {
        if (!isCurrent(claim.generation)) return null
        return make().also { open = it }
    }

    /** [session]'s time is up (its timer): ends it if it is still the current one. True when it did. */
    @Synchronized fun expire(session: Session): Boolean {
        if (this.session !== session) return false
        expire()
        return true
    }

    /** True while [generation] is the current claim's (work started under it may still write its results). */
    @Synchronized fun isCurrent(generation: Long): Boolean {
        val s = session ?: return false
        return generation == this.generation && nowMillis() < s.expiresAtMillis
    }

    private fun expire() { generation++; session = null; busy = false; closeOpen(); current = State.EXPIRED }

    private fun closeOpen() { try { open?.close() } catch (_: Throwable) {}; open = null }

    companion object {
        /** How long a pairing notification's reply is taken: Settings' dialog stays up about as long as the user needs. */
        const val SESSION_MS = 5 * 60_000L
    }
}
