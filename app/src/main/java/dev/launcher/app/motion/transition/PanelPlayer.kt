package dev.launcher.app.motion.transition

/** A motion role's curve as a clock: its progress (0..1, beyond for an overshoot) [elapsedMs] after it started. */
fun interface RoleClock {
    fun at(elapsedMs: Float): Float
}

/**
 * A panel's transitions (docs/PLAN_PANEL_TRANSITIONS.md): [open] while it is pulled (Closed to Held, or to Open), [release]
 * over it once the pull is let go open (Held to Open; null for a layout with no held state), and [close]. A transition
 * driven on release runs its progress tracks on [openRole] or [closeRole] (the panel's own springs), started then.
 */
class PanelMotion(
    val open: Transition,
    val release: Transition?,
    close: TransitionSpec,
    val openRole: String,
    val closeRole: String,
) {
    val close: Transition = when (close) {
        is TransitionSpec.Own -> close.transition
        TransitionSpec.ReverseOfOpening -> TransitionTypes.closingFor(open)
    }
}

/**
 * Plays one panel's [PanelMotion] (plain Kotlin, no Android types): the shade tells it what happens (a pull, its release,
 * the landing, a close) and asks every frame for every property ([evaluate]) at the panel's progress p (the finger, or the
 * spring that finishes it) and the time.
 *
 * - **Pulled**: [PanelMotion.open] at p (or, driven on release, still at its start).
 * - **Released open**: [PanelMotion.open] goes on at p (the edge landing on the panel's spring), with
 *   [PanelMotion.release]'s tracks over it, each on its own clock from the release or the landing.
 * - **Closing**: [PanelMotion.close] at 1 - p (or on its clock).
 *
 * A connection that takes over mid-way (a close during the reveal, a pull caught on its way out) starts from what was on
 * screen: each property keeps the difference from the new transition and lets it go as its own progress there goes from
 * where it was to its end, so nothing jumps and everything ends where the new transition ends.
 */
class PanelPlayer(private val clockOf: (String) -> RoleClock) {
    enum class Phase { CLOSED, PULLED, RELEASED, CLOSING }

    var phase = Phase.CLOSED
        private set
    private var motion: PanelMotion? = null

    private var pulledAt = NONE
    private var releasedAt = NONE
    private var closedAt = NONE
    private var landedAt = NONE

    // Each connection's tracks' clocks, by property (null: the track follows progress, or there is none), and the panel's.
    private val n = Prop.entries.size
    private val openClocks = arrayOfNulls<RoleClock>(n)
    private val releaseClocks = arrayOfNulls<RoleClock>(n)
    private val closeClocks = arrayOfNulls<RoleClock>(n)
    private var openRole: RoleClock? = null
    private var closeRole: RoleClock? = null

    private val raw = FloatArray(n)
    private val u = FloatArray(n)
    private val last = FloatArray(n)
    private var hasLast = false
    private val offset = FloatArray(n)
    private val offsetFrom = FloatArray(n)
    private var handover = false

    /** A new opening with [m] (the preset may have changed since the last one): closed, nothing carried over. */
    fun begin(m: PanelMotion) {
        motion = m
        phase = Phase.CLOSED
        pulledAt = NONE; releasedAt = NONE; closedAt = NONE; landedAt = NONE
        hasLast = false
        handover = false
        offset.fill(0f)
        clocks(m.open, openClocks)
        clocks(m.release, releaseClocks)
        clocks(m.close, closeClocks)
        openRole = clockOf(m.openRole)
        closeRole = clockOf(m.closeRole)
    }

    private fun clocks(t: Transition?, into: Array<RoleClock?>) {
        into.fill(null)
        if (t == null) return
        for (tr in t.tracks) (tr.timing as? TrackTiming.Clock)?.let { into[tr.prop.ordinal] = clockOf(it.role) }
    }

    /** A finger takes it from closed (or catches it on its way out): [PanelMotion.open] from here. */
    fun pull(nowMs: Long) {
        if (phase == Phase.PULLED) return
        switch(Phase.PULLED)
        pulledAt = nowMs
        releasedAt = NONE
        landedAt = NONE
    }

    /** The pull is let go open (or a close is taken back): [PanelMotion.release] over [PanelMotion.open] from here. */
    fun release(nowMs: Long) {
        if (phase == Phase.RELEASED) return
        if (pulledAt == NONE) pulledAt = nowMs
        switch(Phase.RELEASED)
        releasedAt = nowMs
        landedAt = NONE
    }

    /** It has landed (the layout's judgement): tracks cued on the landing start. Once per connection. */
    fun landed(nowMs: Long) {
        if (landedAt == NONE) landedAt = nowMs
    }

    /** It starts closing: [PanelMotion.close] from here. */
    fun close(nowMs: Long) {
        if (phase == Phase.CLOSING) return
        switch(Phase.CLOSING)
        closedAt = nowMs
        landedAt = NONE
    }

    /** Out of sight: closed. */
    fun closed() {
        phase = Phase.CLOSED
        hasLast = false
        handover = false
        offset.fill(0f)
    }

    private fun switch(to: Phase) {
        // (What was on screen is where the next connection starts from: kept at its first frame.)
        if (hasLast) handover = true
        phase = to
    }

    /**
     * Every property at the panel's progress [p] and time [nowMs] into [out] (by [Prop.ordinal], real units; [theme]: the
     * theme's value of each look). No allocation.
     */
    fun evaluate(p: Float, nowMs: Long, theme: FloatArray, out: FloatArray) {
        val m = motion
        if (m == null) {
            for (prop in Prop.entries) out[prop.ordinal] = if (prop.look) prop.rest * theme[prop.ordinal] else prop.rest
            return
        }
        if (handover) {
            handover = false
            // The new connection where the last frame was (its progress and time): what differs from that frame is the jump
            // to absorb, not what the finger or the clocks moved since.
            compute(m, lastP, lastNow, theme)
            for (i in 0 until n) {
                val d = last[i] - raw[i]
                offset[i] = if (kotlin.math.abs(d) < 1e-4f) 0f else d
                offsetFrom[i] = u[i]
            }
        }
        compute(m, p, nowMs, theme)
        for (prop in Prop.entries) {
            val i = prop.ordinal
            var v = raw[i]
            val o = offset[i]
            if (o != 0f) {
                val from = offsetFrom[i]
                val left = if (from >= 1f) 0f else 1f - ((u[i] - from) / (1f - from)).coerceIn(0f, 1f)
                v += o * left
                if (left == 0f) offset[i] = 0f
            }
            v = v.coerceIn(prop.min, prop.max)
            out[i] = v
            last[i] = v
        }
        hasLast = true
        lastP = p
        lastNow = nowMs
    }

    private var lastP = 0f
    private var lastNow = 0L

    /** The phase's connections at [p] and [nowMs] into [raw] and [u]. */
    private fun compute(m: PanelMotion, p: Float, nowMs: Long, theme: FloatArray) {
        when (phase) {
            Phase.CLOSED, Phase.PULLED -> connection(m.open, openT(m, p, nowMs), openClocks, pulledAt, NONE, nowMs, theme, over = false)
            Phase.RELEASED -> {
                connection(m.open, openT(m, p, nowMs), openClocks, pulledAt, landedAt, nowMs, theme, over = false)
                m.release?.let {
                    val t = if (it.drive == Drive.FINGER) p else releaseT(nowMs, p)
                    connection(it, t, releaseClocks, releasedAt, landedAt, nowMs, theme, over = true)
                }
            }
            Phase.CLOSING -> connection(m.close, closeT(m, p, nowMs), closeClocks, closedAt, landedAt, nowMs, theme, over = false)
        }
    }

    /** A release driven on release: on the panel's opening spring from the release on (before it: at its start). */
    private fun releaseT(nowMs: Long, p: Float): Float =
        if (releasedAt == NONE || nowMs < releasedAt) 0f else openRole?.at((nowMs - releasedAt).toFloat()) ?: p

    /** The open transition's progress: the panel's, or, driven on release, its own spring from the release on. */
    private fun openT(m: PanelMotion, p: Float, nowMs: Long): Float =
        if (m.open.drive == Drive.FINGER) p
        else if (releasedAt == NONE) 0f
        else openRole?.at((nowMs - releasedAt).toFloat()) ?: p

    /** The close's: 1 - p as the panel goes, or, driven on release, its own spring from the close's start. */
    private fun closeT(m: PanelMotion, p: Float, nowMs: Long): Float =
        if (m.close.drive == Drive.FINGER) 1f - p
        else closeRole?.at((nowMs - closedAt).toFloat()) ?: (1f - p)

    /**
     * [t]'s values into [raw] and each property's own progress into [u]: every property ([over] false: those without a
     * track rest, as the panel open), or only those [t] has a track for ([over]: laid over the connection under it).
     */
    private fun connection(t: Transition, progress: Float, clocks: Array<RoleClock?>, startedAt: Long, landed: Long, nowMs: Long,
                           theme: FloatArray, over: Boolean) {
        for (prop in Prop.entries) {
            val i = prop.ordinal
            val tr = t.track(prop)
            if (tr == null) {
                if (!over) { raw[i] = if (prop.look) prop.rest * theme[i] else prop.rest; u[i] = progress }
                continue
            }
            val own = when (val tm = tr.timing) {
                TrackTiming.Progress -> progress
                is TrackTiming.Clock -> {
                    val cueAt = if (tm.cue == Cue.START) startedAt else landed
                    val since = if (cueAt == NONE) -1f else (nowMs - cueAt).toFloat() - tm.delayMs
                    if (since <= 0f) 0f else clocks[i]?.at(since) ?: 0f
                }
            }
            raw[i] = tr.valueAt(own, theme[i])
            u[i] = own
        }
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
