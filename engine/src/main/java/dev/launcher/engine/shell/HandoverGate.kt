package dev.launcher.engine.shell

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Every decision of the own host's start under one lock, by attempt generation (T-001 2b, Codex's review): a start
 * ([begin]), the starter's handover ([claim], one use of the capability), its acknowledgement once it has linked the
 * launcher's token ([ready], one use of the ack the claim returned), a timeout ([expire]) and a binder's death ([lost]).
 * Only [ready] makes a service publishable, and only for the attempt still in flight: an expired, superseded or replayed
 * step changes nothing, and the death of an obsolete binder cannot end a newer connection.
 */
class HandoverGate(private val nowMillis: () -> Long, private val random: SecureRandom = SecureRandom()) {
    private val attempt = StartAttempt(nowMillis, random)
    private var starting: StartAttempt.Ticket? = null
    private var acking: Acking? = null
    private var connected = 0L

    /** A claim accepted: the ack the starter must send back once it has linked the token. Never logged. */
    class Accepted internal constructor(val generation: Long, val ack: String) {
        override fun toString() = "HandoverGate.Accepted(generation=$generation)"
    }

    private class Acking(val generation: Long, val ack: String, val expiresAtMillis: Long)

    /** A start in flight, claimed or not. */
    val isStarting: Boolean @Synchronized get() = starting != null
    /** The generation whose service is published (0: none). */
    val connectedGeneration: Long @Synchronized get() = connected

    /** The start in flight, if any (0: none). */
    val startingGeneration: Long @Synchronized get() = starting?.generation ?: 0L

    /** A new attempt; it replaces one in flight (whose later steps are then refused). */
    @Synchronized fun begin(): StartAttempt.Ticket = attempt.begin().also { starting = it; acking = null }

    /** The starter's handover: caller, method and capability as [StartAttempt.claim] checks them, for the attempt in flight. */
    @Synchronized fun claim(callingUid: Int, method: String?, capability: String?): Accepted? {
        val t = attempt.claim(callingUid, method, capability) ?: return null
        if (starting?.generation != t.generation || acking != null) return null
        return Accepted(t.generation, token()).also { acking = Acking(it.generation, it.ack, t.expiresAtMillis) }
    }

    /** The starter's acknowledgement: the generation now connected, or null (wrong caller, ack, attempt or too late). */
    @Synchronized fun ready(callingUid: Int, method: String?, ack: String?): Long? {
        if (callingUid != StartAttempt.SHELL_UID || method != READY_METHOD || !StartAttempt.validCapability(ack)) return null
        val a = acking ?: return null
        if (!MessageDigest.isEqual(a.ack.toByteArray(Charsets.US_ASCII), ack!!.toByteArray(Charsets.US_ASCII))) return null
        if (starting?.generation != a.generation || nowMillis() >= a.expiresAtMillis) return null
        starting = null
        acking = null
        connected = a.generation
        return a.generation
    }

    /** The attempt's timeout: true when it ended [generation]'s start (a newer one's is untouched). */
    @Synchronized fun expire(generation: Long): Boolean {
        if (starting?.generation != generation) return false
        attempt.clear(generation)
        starting = null
        acking = null
        return true
    }

    /**
     * "Forget pairing": in one step, whatever is in flight or connected is over. Returns the start it ended (0: none); a
     * helper whose acknowledgement was already taken is no longer connected, so its queued publication finds that.
     */
    @Synchronized fun endAll(): Long {
        val gen = starting?.generation ?: 0L
        if (gen != 0L) attempt.clear(gen)
        starting = null
        acking = null
        connected = 0
        return gen
    }

    /** A binder of [generation] died: what that ended (nothing for an obsolete generation). */
    @Synchronized fun lost(generation: Long): Ended = when {
        connected == generation && generation != 0L -> { connected = 0; Ended.CONNECTION }
        acking?.generation == generation -> { starting = null; acking = null; Ended.START }
        else -> Ended.NOTHING
    }

    /** Is [generation]'s service still the published one (a publication posted before its death was handled)? */
    @Synchronized fun isConnected(generation: Long) = generation != 0L && connected == generation

    enum class Ended { CONNECTION, START, NOTHING }

    private fun token() = buildString(64) {
        for (b in ByteArray(32).also(random::nextBytes)) { append(HEX[(b.toInt() and 0xff) ushr 4]); append(HEX[b.toInt() and 15]) }
    }

    companion object {
        const val READY_METHOD = "shell-ready"
        private const val HEX = "0123456789abcdef"
    }
}
