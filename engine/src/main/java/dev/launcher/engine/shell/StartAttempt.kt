package dev.launcher.engine.shell

import java.security.MessageDigest
import java.security.SecureRandom

/** One pending local-shell handover. The caller supplies a monotonic millisecond clock. */
class StartAttempt(
    private val nowMillis: () -> Long,
    private val random: SecureRandom = SecureRandom(),
) {
    /** Never stringify a ticket into a log: its capability is deliberately omitted from toString(). */
    class Ticket internal constructor(val generation: Long, val createdAtMillis: Long, val capability: String) {
        val expiresAtMillis: Long = Math.addExact(createdAtMillis, TIMEOUT_MS)
        override fun toString() = "StartAttempt.Ticket(generation=$generation)"
    }

    private var generation = 0L
    private var pending: Ticket? = null

    /** Replaces the old attempt; old timeout callbacks must use clear(old.generation). */
    @Synchronized fun begin(): Ticket {
        val bytes = ByteArray(32).also(random::nextBytes)
        val capability = buildString(64) {
            for (byte in bytes) {
                append(HEX[(byte.toInt() and 0xff) ushr 4])
                append(HEX[byte.toInt() and 15])
            }
        }
        val next = Math.incrementExact(generation)
        val ticket = Ticket(next, nowMillis(), capability)
        generation = next
        pending = ticket
        return ticket
    }

    /**
     * Checks caller and method before touching state. Invalid input never consumes an attempt.
     * A successful claim consumes it BEFORE the provider validates the build/binder or links death.
     */
    @Synchronized fun claim(callingUid: Int, method: String?, capability: String?): Ticket? {
        if (callingUid != SHELL_UID || method != HANDOVER_METHOD) return null
        if (!validCapability(capability)) return null
        val ticket = pending ?: return null
        if (!MessageDigest.isEqual(ticket.capability.toByteArray(Charsets.US_ASCII), capability!!.toByteArray(Charsets.US_ASCII))) return null
        val age = nowMillis() - ticket.createdAtMillis
        if (age !in 0 until TIMEOUT_MS) return null
        pending = null
        return ticket
    }

    /** A cancellation or timeout from an obsolete generation cannot clear its replacement. */
    @Synchronized fun clear(generation: Long): Boolean {
        if (pending?.generation != generation) return false
        pending = null
        return true
    }

    companion object {
        const val TIMEOUT_MS = 15_000L
        const val SHELL_UID = 2000
        const val HANDOVER_METHOD = "shell-binder"
        private const val HEX = "0123456789abcdef"
        internal fun validCapability(value: String?): Boolean =
            value != null && value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
    }
}
