package dev.launcher.app.motion.transition

/** A motion role's curve as a clock: its progress (0..1, beyond for an overshoot) [elapsedMs] after it started, and its length. */
interface RoleClock {
    fun at(elapsedMs: Float): Float
    val durationMs: Float

    /**
     * Where a start at 1 unit/s with nowhere to go has taken the value [elapsedMs] after it (the role's own way of letting a
     * speed run out): what a value already moving when its clock starts adds to its way, as an animation started from
     * where it is keeps its speed.
     */
    fun carry(elapsedMs: Float): Float = 0f

    companion object {
        /** A straight line over [ms] (tests, and anything that wants an even clock); [carryOf]: a speed's run-out, if any. */
        fun linear(ms: Float, carryOf: ((Float) -> Float)? = null): RoleClock = object : RoleClock {
            override fun at(elapsedMs: Float) = (elapsedMs / ms).coerceIn(0f, 1f)
            override val durationMs = ms
            override fun carry(elapsedMs: Float) = carryOf?.invoke(elapsedMs) ?: 0f
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

    /**
     * The panel opens as a sheet whose edge moves ([Prop.PANEL_EDGE]: iOS's, with its clear slab, its cover and what gives
     * way inside it), not as a frame that moves, scales or fades whole (Figma's types).
     */
    val sheetEdge: Boolean = open.tracks.any { it.prop == Prop.PANEL_EDGE }
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
class PanelPlayer(private val unitsPerMs: Long = 1L, private val clockOf: (String) -> RoleClock) {
    /** A span of the caller's time units in milliseconds (the shade gives nanoseconds: whole milliseconds put a 60 ms fade a third of a millisecond out). */
    private fun ms(d: Long): Float = d.toFloat() / unitsPerMs

    enum class Phase { CLOSED, PULLED, RELEASED, FALLING, CLOSING }

    var phase = Phase.CLOSED
        private set
    /** What it plays since the last [begin] (null before the first). */
    var motion: PanelMotion? = null
        private set

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
    /** The speed (units/s) each such property had then, carried into its clock ([RoleClock.carry]). */
    private val fromV = Array(2) { FloatArray(n) }
    // The two frames before the last (values and times): a value's speed at the last frame, from three of them.
    private val prev = FloatArray(n)
    private val prev2 = FloatArray(n)
    /** Each value's speed (units/s) where its clock gives it exactly (NaN: from the frames), now and at the last frame. */
    private val vel = FloatArray(n)
    private val velLast = FloatArray(n) { Float.NaN }
    private var prevNow = NONE
    private var prev2Now = NONE
    /** Not while comparing the new connection with the old one: nothing is taken from what shows then. */
    private var capturing = true

    /** A new opening with [m] (the preset may have changed since the last one): closed, nothing carried over. */
    fun begin(m: PanelMotion) {
        motion = m
        phase = Phase.CLOSED
        pulledAt = NONE; overAt = NONE; closedAt = NONE; landedAt = NONE; endedAt = NONE
        hasLast = false
        restSet = false
        handover = false
        offset.fill(0f)
        clearFrom()
        prevNow = NONE; prev2Now = NONE
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

    /** The panel's end was told ([ended]): once nothing is [busy], it may be [closed]. */
    val isEnded: Boolean get() = endedAt != NONE

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
                val since = ms(nowMs - at) - c.delayMs
                if (since < (clocks[tr.prop.ordinal]?.durationMs ?: 0f)) return true
            }
            return false
        }
        val roleRunning = when (phase) {
            Phase.RELEASED -> m.open.drive == Drive.RELEASE && overAt != NONE && ms(nowMs - overAt) < (openRole?.durationMs ?: 0f)
            Phase.CLOSING -> m.close.drive == Drive.RELEASE && ms(nowMs - closedAt) < (closeRole?.durationMs ?: 0f)
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
        // (Before its first frame nothing of it has shown: what shows is everything at rest, the bar there, no dim.)
        if (!hasLast && !restSet) {
            for (prop in Prop.entries) last[prop.ordinal] = if (prop.look) prop.rest * theme[prop.ordinal] else prop.rest
            velLast.fill(0f)
            restSet = true
        }
        // (A new frame: the last two move back, for the speed of what starts from where it is.)
        if (hasLast && nowMs != lastNow) {
            System.arraycopy(prev, 0, prev2, 0, n); prev2Now = prevNow
            System.arraycopy(last, 0, prev, 0, n); prevNow = lastNow
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
            velLast[i] = vel[i]
        }
        hasLast = true
        lastP = p
        lastNow = nowMs
    }

    private var lastP = 0f
    private var lastNow = 0L
    private var restSet = false

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
        if (overAt == NONE || nowMs < overAt) 0f else openRole?.at(ms(nowMs - overAt)) ?: p

    /** The close's: 1 - p as the panel goes, or, driven on release, its own spring from the close's start. */
    private fun closeT(m: PanelMotion, p: Float, nowMs: Long): Float =
        if (m.close.drive == Drive.FINGER) 1f - p
        else if (nowMs < closedAt) 0f
        else closeRole?.at(ms(nowMs - closedAt)) ?: (1f - p)

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
                if (!over) { raw[i] = if (prop.look) prop.rest * theme[i] else prop.rest; u[i] = progress; ownStart[i] = false; vel[i] = 0f }
                continue
            }
            var start = Float.NaN
            var carried = 0f
            val own = when (val tm = tr.timing) {
                TrackTiming.Progress -> progress
                TrackTiming.Relative -> if (t0 >= 1f) 1f else (progress - t0) / (1f - t0)
                is TrackTiming.Clock -> {
                    val cueAt = cueAt(tm.cue, startedAt)
                    val since = if (cueAt == NONE) -1f else ms(nowMs - cueAt) - tm.delayMs
                    // (Over another, a track counts from its clock's start: until then what is under it shows.)
                    if (since < 0f && over) continue
                    if (tr.fromCurrent) {
                        start = when {
                            since >= 0f && fromSet[layer][i] -> from[layer][i]
                            // Its clock starts: from what showed and at the speed it had (as an animation starts from where it is).
                            since >= 0f && capturing -> last[i].also {
                                from[layer][i] = it; fromSet[layer][i] = true; fromV[layer][i] = speedAtLast(i)
                            }
                            // Not started (or the comparison of a handover): it holds what shows.
                            else -> last[i]
                        }
                        if (since > 0f && fromSet[layer][i]) carried = fromV[layer][i] * (clocks[i]?.carry(since) ?: 0f)
                    }
                    if (since <= 0f) 0f else clocks[i]?.at(since) ?: 0f
                }
            }
            raw[i] = tr.valueAt(own, theme[i], start) + carried
            u[i] = own
            vel[i] = Float.NaN
            val tm = tr.timing
            if (tm is TrackTiming.Clock) {
                val c = clocks[i]
                val cueAt = cueAt(tm.cue, startedAt)
                val since = if (cueAt == NONE) -1f else ms(nowMs - cueAt) - tm.delayMs
                if (c != null && since > SPEED_MS) {
                    val before = since - SPEED_MS
                    val carriedBefore = if (tr.fromCurrent && fromSet[layer][i]) fromV[layer][i] * c.carry(before) else 0f
                    vel[i] = (raw[i] - (tr.valueAt(c.at(before), theme[i], start) + carriedBefore)) / (SPEED_MS / 1000f)
                } else if (since <= 0f) vel[i] = 0f
            }
            ownStart[i] = tr.timing == TrackTiming.Relative
        }
    }

    /** Property [i]'s speed (units/s) at the last frame: from the last three frames (a second-order backward difference), else two. */
    private fun speedAtLast(i: Int): Float {
        // (Exactly, where the last frame's value came from a clock: as an animation knows its own speed.)
        if (!velLast[i].isNaN()) return velLast[i]
        if (!hasLast || prevNow == NONE || lastNow == prevNow) return 0f
        val dt = ms(lastNow - prevNow) / 1000f
        if (prev2Now == NONE || prevNow == prev2Now || kotlin.math.abs(ms(prevNow - prev2Now) / 1000f - dt) > 1e-3f) return (last[i] - prev[i]) / dt
        return (3f * last[i] - 4f * prev[i] + prev2[i]) / (2f * dt)
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
        /** The step a clock-driven value's speed is measured over (ms). */
        const val SPEED_MS = 0.5f
    }
}
