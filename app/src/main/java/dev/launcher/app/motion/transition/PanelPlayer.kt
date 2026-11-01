package dev.launcher.app.motion.transition

/** A motion role's curve as a clock: its progress (0..1, beyond for an overshoot) [elapsedMs] after it started, and its length. */
interface RoleClock {
    fun at(elapsedMs: Float): Float
    val durationMs: Float

    companion object {
        /** A straight line over [ms] (tests, and anything that wants an even clock). */
        fun linear(ms: Float): RoleClock = object : RoleClock {
            override fun at(elapsedMs: Float) = (elapsedMs / ms).coerceIn(0f, 1f)
            override val durationMs = ms
        }
    }
}

/**
 * A panel's transitions (docs/PLAN_PANEL_TRANSITIONS.md): [open] while it is pulled (Closed to Held, or to Open), [release]
 * laid over it once the pull is let go open (Held to Open), [fallback] laid over it when the pull is let go and falls back,
 * [close], and [afterClose] laid over the close once the panel is gone (what finishes after it: iOS's bar coming back). A transition driven on release runs its progress tracks on [openRole] or [closeRole] (the panel's own
 * springs), started then.
 */
class PanelMotion(
    val open: Transition,
    val release: Transition?,
    close: TransitionSpec,
    val openRole: String,
    val closeRole: String,
    val fallback: Transition? = null,
    val afterClose: Transition? = null,
) {
    val close: Transition = when (close) {
        is TransitionSpec.Own -> close.transition
        TransitionSpec.ReverseOfOpening -> TransitionTypes.closingFor(open)
    }
}

/**
 * Plays one panel's [PanelMotion] (plain Kotlin, no Android types): the shade tells it what happens (a pull, its release or
 * fall back, the landing, a close, the panel gone) and asks every frame for every property ([evaluate]) at the panel's
 * progress p (the finger, or the spring that finishes it) and the time.
 *
 * - **Pulled**: [PanelMotion.open] at p (or, driven on release, still at its start).
 * - **Released open**: [PanelMotion.open] goes on at p (the edge landing on the panel's spring), [PanelMotion.release]'s
 *   tracks over it, each on its own clock from the release or the landing.
 * - **Falling back**: [PanelMotion.open] at p, [PanelMotion.fallback]'s tracks over it.
 * - **Closing**: [PanelMotion.close] at 1 - p (or on its clock); after the panel is gone ([ended]) its clocks finish.
 *
 * A track laid over another counts from its clock's start; until then the value under it shows. A clock's track may start
 * from the value showing when its clock starts ([Keyframe.fromCurrent]), as an animation starts from where it is.
 *
 * A connection that takes over mid-way (a close during the reveal, a pull caught on its way out) starts from what was on
 * screen: compared with the one before at the same moment, each property keeps the difference and lets it go as its own
 * progress there goes from where it was to its end, so nothing jumps and everything ends where the new transition ends.
 */
class PanelPlayer(private val clockOf: (String) -> RoleClock) {
    enum class Phase { CLOSED, PULLED, RELEASED, FALLING, CLOSING }

    var phase = Phase.CLOSED
        private set
    private var motion: PanelMotion? = null

    private var pulledAt = NONE
    private var overAt = NONE     // the release's or the fallback's start
    private var closedAt = NONE
    private var landedAt = NONE
    private var endedAt = NONE

    private val n = Prop.entries.size
    private val openClocks = arrayOfNulls<RoleClock>(n)
    private val releaseClocks = arrayOfNulls<RoleClock>(n)
    private val fallbackClocks = arrayOfNulls<RoleClock>(n)
    private val closeClocks = arrayOfNulls<RoleClock>(n)
    private val afterCloseClocks = arrayOfNulls<RoleClock>(n)
    private var openRole: RoleClock? = null
    private var closeRole: RoleClock? = null

    private val raw = FloatArray(n)
    private val u = FloatArray(n)
    /** The value comes from a [TrackTiming.Relative] track: it starts where it says, never handed over from what showed. */
    private val ownStart = BooleanArray(n)
    private val last = FloatArray(n)
    private var hasLast = false
    private val offset = FloatArray(n)
    private val offsetFrom = FloatArray(n)
    private var handover = false
    /** Where each property's started clock took it from ([Keyframe.fromCurrent]), by connection layer: 0 under, 1 over. */
    private val from = Array(2) { FloatArray(n) }
    private val fromSet = Array(2) { BooleanArray(n) }
    /** Not while comparing the new connection with the old one: nothing is taken from what shows then. */
    private var capturing = true

    /** A new opening with [m] (the preset may have changed since the last one): closed, nothing carried over. */
    fun begin(m: PanelMotion) {
        motion = m
        phase = Phase.CLOSED
        pulledAt = NONE; overAt = NONE; closedAt = NONE; landedAt = NONE; endedAt = NONE
        hasLast = false
        handover = false
        offset.fill(0f)
        clearFrom()
        clocks(m.open, openClocks)
        clocks(m.release, releaseClocks)
        clocks(m.fallback, fallbackClocks)
        clocks(m.close, closeClocks)
        clocks(m.afterClose, afterCloseClocks)
        openRole = clockOf(m.openRole)
        closeRole = clockOf(m.closeRole)
    }

    private fun clocks(t: Transition?, into: Array<RoleClock?>) {
        into.fill(null)
        if (t == null) return
        for (tr in t.tracks) (tr.timing as? TrackTiming.Clock)?.let { into[tr.prop.ordinal] = clockOf(it.role) }
    }

    private fun clearFrom() { fromSet[0].fill(false); fromSet[1].fill(false); startSet[0] = false; startSet[1] = false }

    /** Each layer's connection's progress where it started (for [TrackTiming.Relative]), once its first frame has taken it. */
    private val startT = FloatArray(2)
    private val startSet = BooleanArray(2)

    /** A finger takes it from closed (or catches it on its way out): [PanelMotion.open] from here. */
    fun pull(nowMs: Long) {
        if (phase == Phase.PULLED) return
        switch(Phase.PULLED, under = true)
        pulledAt = nowMs
        overAt = NONE; landedAt = NONE; endedAt = NONE
    }

    /** The pull is let go open (or a close is taken back): [PanelMotion.release] over [PanelMotion.open] from here. */
    fun release(nowMs: Long) {
        if (phase == Phase.RELEASED) return
        if (pulledAt == NONE) pulledAt = nowMs
        switch(Phase.RELEASED, under = phase == Phase.CLOSING)
        overAt = nowMs
        landedAt = NONE; endedAt = NONE
    }

    /** The pull is let go and falls back: [PanelMotion.fallback] over [PanelMotion.open] from here. */
    fun fallBack(nowMs: Long) {
        if (phase == Phase.FALLING) return
        if (pulledAt == NONE) pulledAt = nowMs
        switch(Phase.FALLING, under = false)
        overAt = nowMs
        endedAt = NONE
    }

    /** It has landed (the layout's judgement): tracks cued on the landing start. Once per connection. */
    fun landed(nowMs: Long) {
        if (landedAt == NONE) landedAt = nowMs
    }

    /** It starts closing: [PanelMotion.close] from here. */
    fun close(nowMs: Long) {
        if (phase == Phase.CLOSING) return
        switch(Phase.CLOSING, under = true)
        closedAt = nowMs
        landedAt = NONE; endedAt = NONE
    }

    /** The panel is out of sight (closed, or fallen back): tracks cued on its end start, and the rest finish. */
    fun ended(nowMs: Long) {
        if (endedAt != NONE) return
        endedAt = nowMs
        // (The layer over the close starts afresh.)
        fromSet[1].fill(false)
        startSet[1] = false
    }

    /** Nothing more to play: back to closed (a new pull starts from nothing). */
    fun closed() {
        phase = Phase.CLOSED
        hasLast = false
        handover = false
        offset.fill(0f)
        clearFrom()
        endedAt = NONE
    }

    /** [under]: the connection under changes too (a pull, a close; a release or a fall back lays one over the opening). */
    private fun switch(to: Phase, under: Boolean) {
        // (What was on screen is where the next connection starts from: kept at its first frame.)
        if (hasLast) handover = true
        phase = to
        fromSet[1].fill(false)
        startSet[1] = false
        if (under) { fromSet[0].fill(false); startSet[0] = false }
    }

    /**
     * A clock of the current connections is still running at [nowMs] (started and not yet at its end): the caller keeps
     * asking for frames, also after the panel's own spring has come to rest (a reveal after the landing, a fade after the
     * close).
     */
    fun busy(nowMs: Long): Boolean {
        val m = motion ?: return false
        fun running(t: Transition?, clocks: Array<RoleClock?>, startedAt: Long): Boolean {
            if (t == null) return false
            for (tr in t.tracks) {
                val c = tr.timing as? TrackTiming.Clock ?: continue
                val at = cueAt(c.cue, startedAt)
                if (at == NONE) continue
                val since = (nowMs - at).toFloat() - c.delayMs
                if (since < (clocks[tr.prop.ordinal]?.durationMs ?: 0f)) return true
            }
            return false
        }
        val roleRunning = when (phase) {
            Phase.RELEASED -> m.open.drive == Drive.RELEASE && overAt != NONE && (nowMs - overAt) < (openRole?.durationMs ?: 0f)
            Phase.CLOSING -> m.close.drive == Drive.RELEASE && (nowMs - closedAt) < (closeRole?.durationMs ?: 0f)
            else -> false
        }
        return roleRunning || when (phase) {
            Phase.CLOSED -> false
            Phase.PULLED -> running(m.open, openClocks, pulledAt)
            Phase.RELEASED -> running(m.open, openClocks, pulledAt) || running(m.release, releaseClocks, overAt)
            Phase.FALLING -> running(m.open, openClocks, pulledAt) || running(m.fallback, fallbackClocks, overAt)
            Phase.CLOSING -> running(m.close, closeClocks, closedAt) || (endedAt != NONE && running(m.afterClose, afterCloseClocks, endedAt))
        }
    }

    private fun cueAt(c: Cue, startedAt: Long): Long = when (c) {
        Cue.START -> startedAt
        Cue.LANDED -> landedAt
        Cue.END -> endedAt
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
            capturing = false
            compute(m, lastP, lastNow, theme)
            capturing = true
            for (i in 0 until n) {
                val d = last[i] - raw[i]
                offset[i] = if (kotlin.math.abs(d) < 1e-4f || ownStart[i]) 0f else d
                offsetFrom[i] = u[i]
            }
        }
        compute(m, p, nowMs, theme)
        for (prop in Prop.entries) {
            val i = prop.ordinal
            var v = raw[i]
            val o = offset[i]
            if (o != 0f) {
                val f = offsetFrom[i]
                val left = if (f >= 1f) 0f else 1f - ((u[i] - f) / (1f - f)).coerceIn(0f, 1f)
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
            Phase.CLOSED, Phase.PULLED -> connection(m.open, openT(m, p, nowMs), openClocks, pulledAt, nowMs, theme, layer = 0)
            Phase.RELEASED -> {
                connection(m.open, openT(m, p, nowMs), openClocks, pulledAt, nowMs, theme, layer = 0)
                m.release?.let { connection(it, if (it.drive == Drive.FINGER) p else overT(nowMs, p), releaseClocks, overAt, nowMs, theme, layer = 1) }
            }
            Phase.FALLING -> {
                connection(m.open, openT(m, p, nowMs), openClocks, pulledAt, nowMs, theme, layer = 0)
                m.fallback?.let { connection(it, p, fallbackClocks, overAt, nowMs, theme, layer = 1) }
            }
            Phase.CLOSING -> {
                connection(m.close, closeT(m, p, nowMs), closeClocks, closedAt, nowMs, theme, layer = 0)
                if (endedAt != NONE && nowMs >= endedAt) m.afterClose?.let { connection(it, 1f - p, afterCloseClocks, endedAt, nowMs, theme, layer = 1) }
            }
        }
    }

    /** The open transition's progress: the panel's, or, driven on release, its own spring from the release on. */
    private fun openT(m: PanelMotion, p: Float, nowMs: Long): Float =
        if (m.open.drive == Drive.FINGER) p else if (phase != Phase.RELEASED) 0f else overT(nowMs, p)

    /** A release driven on release: on the panel's opening spring from the release on (before it: at its start). */
    private fun overT(nowMs: Long, p: Float): Float =
        if (overAt == NONE || nowMs < overAt) 0f else openRole?.at((nowMs - overAt).toFloat()) ?: p

    /** The close's: 1 - p as the panel goes, or, driven on release, its own spring from the close's start. */
    private fun closeT(m: PanelMotion, p: Float, nowMs: Long): Float =
        if (m.close.drive == Drive.FINGER) 1f - p
        else if (nowMs < closedAt) 0f
        else closeRole?.at((nowMs - closedAt).toFloat()) ?: (1f - p)

    /**
     * [t]'s values into [raw] and each property's own progress into [u] at [progress]: every property under ([layer] 0:
     * those without a track rest, as the panel open), or only those [t] has a track for over it ([layer] 1), each from its
     * clock's start.
     */
    private fun connection(t: Transition, progress: Float, clocks: Array<RoleClock?>, startedAt: Long, nowMs: Long,
                           theme: FloatArray, layer: Int) {
        val over = layer == 1
        // (Where this connection started: its first frame's progress, not taken while comparing a handover.)
        if (!startSet[layer] && capturing) { startT[layer] = progress; startSet[layer] = true }
        val t0 = if (startSet[layer]) startT[layer] else progress
        for (prop in Prop.entries) {
            val i = prop.ordinal
            val tr = t.track(prop)
            if (tr == null) {
                if (!over) { raw[i] = if (prop.look) prop.rest * theme[i] else prop.rest; u[i] = progress; ownStart[i] = false }
                continue
            }
            var start = Float.NaN
            val own = when (val tm = tr.timing) {
                TrackTiming.Progress -> progress
                TrackTiming.Relative -> if (t0 >= 1f) 1f else (progress - t0) / (1f - t0)
                is TrackTiming.Clock -> {
                    val cueAt = cueAt(tm.cue, startedAt)
                    val since = if (cueAt == NONE) -1f else (nowMs - cueAt).toFloat() - tm.delayMs
                    // (Over another, a track counts from its clock's start: until then what is under it shows.)
                    if (since < 0f && over) continue
                    if (tr.fromCurrent) {
                        start = when {
                            since >= 0f && fromSet[layer][i] -> from[layer][i]
                            // Its clock starts: from what showed (as an animation starts from where it is).
                            since >= 0f && capturing -> (if (hasLast) last[i] else raw[i]).also { from[layer][i] = it; fromSet[layer][i] = true }
                            // Not started (or the comparison of a handover): it holds what shows.
                            else -> if (hasLast) last[i] else raw[i]
                        }
                    }
                    if (since <= 0f) 0f else clocks[i]?.at(since) ?: 0f
                }
            }
            raw[i] = tr.valueAt(own, theme[i], start)
            u[i] = own
            ownStart[i] = tr.timing == TrackTiming.Relative
        }
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
