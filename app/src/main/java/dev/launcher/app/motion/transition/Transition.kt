package dev.launcher.app.motion.transition

import dev.launcher.app.motion.BezierMath

/**
 * A transition between two states of a panel, as a Figma prototype connection between two frames (docs/PLAN_PANEL_TRANSITIONS.md,
 * D-062): a [type] with a [direction], a [drive], and the [tracks] every property follows on the way, keyframe by keyframe
 * ("like keyframes in a video editor", the owner). A Figma type fills the tracks in ([TransitionTypes]); tracks of one's
 * own make it [TransitionType.CUSTOM] ([from]: the type it started from). Plain Kotlin, no Android types.
 *
 * The progress t runs 0 (the state it leaves) to 1 (the state it reaches): the finger's pull, or the motion role's curve
 * after a release. How fast it gets there is the role's (a spring or a curve, with the moment's Speed and Bounce); what
 * each property does on the way is the tracks'.
 */
class Transition(
    val type: TransitionType,
    val direction: Direction,
    val drive: Drive,
    tracks: List<Track>,
    /** For a [TransitionType.CUSTOM] one: the Figma type its tracks were first filled in from (the settings say so). */
    val from: TransitionType? = null,
) {
    /** One track per property at most, by [Prop.ordinal]; null where the property keeps its resting value. */
    private val byProp = arrayOfNulls<Track>(Prop.entries.size)

    init {
        for (t in tracks) {
            require(byProp[t.prop.ordinal] == null) { "two tracks for ${t.prop.id}" }
            byProp[t.prop.ordinal] = t
        }
    }

    val tracks: List<Track> get() = byProp.filterNotNull()

    /**
     * What is behind is drawn over the panel (Move out, Slide out: it leaves, uncovering the panel under it); otherwise the
     * panel is over it. A custom one keeps its type's.
     */
    val behindOver: Boolean get() = (if (type == TransitionType.CUSTOM) from else type).let { it == TransitionType.MOVE_OUT || it == TransitionType.SLIDE_OUT }

    fun track(p: Prop): Track? = byProp[p.ordinal]

    /**
     * Every property at [t] into [out] (indexed by [Prop.ordinal], in real units): a keyframe's share of the theme times
     * [theme]'s value for that property (also by ordinal), an absolute keyframe as it is; [Prop.rest] where there is no track.
     * No allocation: called at every frame.
     */
    fun evaluate(t: Float, theme: FloatArray, out: FloatArray) {
        for (p in Prop.entries) {
            val tr = byProp[p.ordinal]
            out[p.ordinal] = tr?.valueAt(t, theme[p.ordinal]) ?: if (p.look) p.rest * theme[p.ordinal] else p.rest
        }
    }

    /** The same way backwards: the close that is the opening reversed (Figma's default for a way back). */
    fun reversed(): Transition = Transition(type, direction.opposite, drive, byProp.filterNotNull().map { it.reversed() }, from)

    /** Its own copy with [tracks] (the user edited one): [TransitionType.CUSTOM], remembering the type it came from. */
    fun withTracks(tracks: List<Track>): Transition =
        Transition(TransitionType.CUSTOM, direction, drive, tracks, if (type == TransitionType.CUSTOM) from else type)

    fun withDrive(d: Drive): Transition = Transition(type, direction, d, byProp.filterNotNull(), from)

    override fun equals(other: Any?) = other is Transition && other.type == type && other.direction == direction &&
        other.drive == drive && other.from == from && other.byProp.contentEquals(byProp)
    override fun hashCode() = ((type.hashCode() * 31 + direction.hashCode()) * 31 + drive.hashCode()) * 31 + byProp.contentHashCode()
    override fun toString() = "Transition(${type.id} ${direction.id} ${drive.id}, ${tracks.size} tracks)"

    companion object {
        /** A track's keyframes at most: what an editor shows on one row and a file reader accepts. */
        const val MAX_KEYS = 16
    }
}

/** Figma's prototype transitions, and one's own. */
enum class TransitionType(val id: String, val label: String) {
    INSTANT("instant", "Instant"),
    DISSOLVE("dissolve", "Dissolve"),
    MOVE_IN("move-in", "Move in"),
    MOVE_OUT("move-out", "Move out"),
    PUSH("push", "Push"),
    SLIDE_IN("slide-in", "Slide in"),
    SLIDE_OUT("slide-out", "Slide out"),
    CUSTOM("custom", "Custom");

    /** It moves something across the screen, so its [Direction] matters. */
    val moves get() = this == MOVE_IN || this == MOVE_OUT || this == PUSH || this == SLIDE_IN || this == SLIDE_OUT

    companion object {
        fun of(id: String): TransitionType = entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("no transition type '$id'")
    }
}

/**
 * Which way things travel (Figma's arrow): [dx], [dy] in screen directions (y down). Move in DOWN: the panel comes from the
 * top moving down; Move out DOWN: what is behind leaves moving down; Push DOWN: both move down.
 */
enum class Direction(val id: String, val dx: Float, val dy: Float) {
    DOWN("down", 0f, 1f), UP("up", 0f, -1f), LEFT("left", -1f, 0f), RIGHT("right", 1f, 0f);

    val opposite: Direction get() = when (this) { DOWN -> UP; UP -> DOWN; LEFT -> RIGHT; RIGHT -> LEFT }

    companion object {
        fun of(id: String): Direction = entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("no direction '$id'")
    }
}

/** What moves the progress: the finger scrubs it (Figma's "On drag"), or it plays by itself once the finger lets go. */
enum class Drive(val id: String) {
    FINGER("finger"), RELEASE("release");

    companion object {
        fun of(id: String): Drive = entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("no drive '$id'")
    }
}

/**
 * What a transition can change (docs/PLAN_PANEL_TRANSITIONS.md, Tracks). [rest]: the value with no track (the panel open,
 * in place). [look]: a look the theme sets (blur, veil, dim, corners): its keyframes are a share of the theme's value unless
 * absolute. Offsets are shares of the screen's width (x) or height (y); [min]..[max] bound every value, an overshoot too.
 */
enum class Prop(val id: String, val rest: Float, val look: Boolean, val min: Float, val max: Float) {
    /** The panel: the destination frame. */
    PANEL_X("panel.x", 0f, false, -2f, 2f),
    PANEL_Y("panel.y", 0f, false, -2f, 2f),
    PANEL_SCALE("panel.scale", 1f, false, 0f, 4f),
    PANEL_OPACITY("panel.opacity", 1f, false, 0f, 1f),
    PANEL_CORNER("panel.corner", 1f, true, 0f, 400f),
    /** How far its moving edge has come (iOS's sheet: the outline's bottom, a share of the screen's height). */
    PANEL_EDGE("panel.edge", 1f, false, 0f, 2f),
    /** What is on it (the clock, the list, the controls), apart from its background. */
    CONTENT_OPACITY("content.opacity", 1f, false, 0f, 1f),
    CONTENT_Y("content.y", 0f, false, -2f, 2f),
    CONTENT_SCALE("content.scale", 1f, false, 0f, 4f),
    /** The background under it: the blur's strength (as it looks: the blur's own log scale, LiveBlur) and the veil. */
    BG_BLUR("bg.blur", 1f, true, 0f, 400f),
    BG_VEIL("bg.veil", 1f, true, 0f, 1f),
    /** What is behind: the source frame (home, or the app's live picture). */
    BEHIND_X("behind.x", 0f, false, -2f, 2f),
    BEHIND_Y("behind.y", 0f, false, -2f, 2f),
    BEHIND_SCALE("behind.scale", 1f, false, 0f, 4f),
    BEHIND_OPACITY("behind.opacity", 1f, false, 0f, 1f),
    BEHIND_DIM("behind.dim", 0f, true, 0f, 1f),
    /**
     * The dim below the panel's moving edge, over what still shows there (iOS's clear slab: a share of the layout's dim
     * token, `comp.nc.glass.dim`).
     */
    DIM_BELOW("dim.below", 0f, true, 0f, 1f),
    /** A still picture of what was behind, inside the panel, giving way to it (iOS: home's icons zooming, an app fading). */
    SNAPSHOT_OPACITY("snapshot.opacity", 0f, false, 0f, 1f),
    SNAPSHOT_SCALE("snapshot.scale", 1f, false, 0f, 4f),
    /** The panel's parts that move on their own (a layout without one ignores its tracks). */
    /**
     * How far above its place the clock is: [CLOCK_DROP] as a share of the layout's drop distance (`comp.nc.clock.drop`:
     * where it waits to drop in from), [CLOCK_LIFT] of its lift distance (`comp.nc.clock.lift`: where it lifts off to).
     */
    CLOCK_DROP("clock.drop", 0f, true, -400f, 4000f),
    CLOCK_LIFT("clock.lift", 0f, true, -400f, 4000f),
    CLOCK_OPACITY("clock.opacity", 1f, false, 0f, 1f),
    DATE_OPACITY("date.opacity", 1f, false, 0f, 1f),
    /** The status bar over the panel. */
    BAR_OPACITY("bar.opacity", 1f, false, 0f, 1f);

    companion object {
        fun of(id: String): Prop = entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("no property '$id'")
    }
}

/**
 * What a track's own progress (its keyframes' 0..1) follows: the connection's ([Progress]: the finger and the panel's spring,
 * or a drive-on-release transition's clock), or a clock of its own ([Clock]) as a video editor's layer has its own timing.
 */
sealed class TrackTiming {
    object Progress : TrackTiming() { override fun toString() = "Progress" }

    /**
     * The connection's progress counted from where the connection started (0 there, 1 at its end): a close that begins
     * before the panel has landed still takes the track's whole way (iOS: the dim below a closing sheet, whole at the
     * close's start and clearing in step with the edge from where it was).
     */
    object Relative : TrackTiming() { override fun toString() = "Relative" }

    /**
     * [role]'s curve (a motion role: a spring or a bezier, with the moment's Speed and Bounce), started [delayMs] after
     * [cue]; 0 until then.
     */
    data class Clock(val role: String, val cue: Cue, val delayMs: Float = 0f) : TrackTiming() {
        init {
            require(role.startsWith("motion.")) { "a clock runs on a motion role (motion.*), not '$role'" }
            require(delayMs in 0f..10_000f) { "a clock's delay is 0 to 10000 ms" }
        }
    }
}

/** What starts a track's own clock. */
enum class Cue(val id: String) {
    /** Its connection begins: the pull (open), the release (release), the close (close). */
    START("start"),
    /** The panel has landed (its moving edge at rest at the end: the layout says when). */
    LANDED("landed"),
    /** The panel is out of sight (closed, or fallen back): what finishes after it (iOS: the bar coming back, the dim clearing). */
    END("end");

    companion object {
        fun of(id: String): Cue = entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("no cue '$id'")
    }
}

/** How a value goes from one keyframe to the next. */
sealed class Easing {
    abstract fun at(u: Float): Float

    /** Evenly. */
    object Linear : Easing() { override fun at(u: Float) = u }

    /** Stays at the keyframe's value, then jumps at the next one (a cut at the end). */
    object Hold : Easing() { override fun at(u: Float) = if (u >= 1f) 1f else 0f }

    /** Jumps to the next keyframe's value at once and stays there (a cut at the start: Instant, a hold played backwards). */
    object Jump : Easing() { override fun at(u: Float) = if (u > 0f) 1f else 0f }

    /** CSS's `cubic-bezier(x1, y1, x2, y2)`: ease in, ease out, ease in and out, and the "back" eases (y beyond 0..1). */
    data class Bezier(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : Easing() {
        init {
            require(x1 in 0f..1f && x2 in 0f..1f) { "a bezier's x1 and x2 are within 0..1" }
            require(y1 in -2f..3f && y2 in -2f..3f) { "a bezier's y1 and y2 are within -2..3" }
        }
        override fun at(u: Float): Float =
            BezierMath.y(BezierMath.solveS(u.toDouble(), x1.toDouble(), x2.toDouble()), y1.toDouble(), y2.toDouble()).toFloat()
    }

    /** u^[exponent] ([out]: 1 - (1 - u)^exponent): a square root (0.5, out) or a quadratic (2) exactly, as code drew them. */
    data class Power(val exponent: Float, val out: Boolean) : Easing() {
        init { require(exponent > 0f && exponent <= 8f) { "a power's exponent is above 0 and at most 8" } }
        override fun at(u: Float): Float {
            val c = u.coerceIn(0f, 1f)
            return if (out) 1f - Math.pow((1f - c).toDouble(), exponent.toDouble()).toFloat() else Math.pow(c.toDouble(), exponent.toDouble()).toFloat()
        }
    }

    companion object {
        /** Figma's named eases, as CSS writes them. */
        val EASE_IN = Bezier(0.42f, 0f, 1f, 1f)
        val EASE_OUT = Bezier(0f, 0f, 0.58f, 1f)
        val EASE_IN_OUT = Bezier(0.42f, 0f, 0.58f, 1f)
        val EASE_IN_BACK = Bezier(0.3f, -0.05f, 0.7f, -0.5f)
        val EASE_OUT_BACK = Bezier(0.45f, 1.45f, 0.8f, 1f)
        val EASE_IN_OUT_BACK = Bezier(0.7f, -0.4f, 0.4f, 1.4f)
    }
}

/**
 * A value at [at] (0..1 along the transition): a share of the theme's value for a [Prop.look] property unless [absolute]
 * (then in the property's own unit: blur and corners in points, veil and dim 0..1). [ease]: how it goes on to the next one.
 */
data class Keyframe(val at: Float, val value: Float, val absolute: Boolean = false, val ease: Easing = Easing.Linear,
                    /**
                     * The first keyframe of a clock's track only: it starts from the value showing when its clock starts
                     * (as an animation starts from where it is), not from [value].
                     */
                    val fromCurrent: Boolean = false)

/**
 * One property's keyframes, in order of [Keyframe.at] (at least one, at most [Transition.MAX_KEYS], none at the same point),
 * on its [timing]: the connection's progress, or a clock of its own.
 */
class Track(val prop: Prop, keys: List<Keyframe>, val timing: TrackTiming = TrackTiming.Progress) {
    val keys: List<Keyframe> = keys.toList()
    private val at = FloatArray(keys.size) { keys[it].at }

    init {
        require(keys.isNotEmpty()) { "track ${prop.id}: no keyframes" }
        require(keys.size <= Transition.MAX_KEYS) { "track ${prop.id}: more than ${Transition.MAX_KEYS} keyframes" }
        for (i in keys.indices) {
            require(keys[i].at in 0f..1f) { "track ${prop.id}: a keyframe at ${keys[i].at}, outside 0..1" }
            require(i == 0 || keys[i].at > keys[i - 1].at) { "track ${prop.id}: keyframes out of order or at the same point" }
            require(!keys[i].absolute || prop.look) { "track ${prop.id}: only a look can be absolute" }
            require(!keys[i].fromCurrent || (i == 0 && timing is TrackTiming.Clock)) { "track ${prop.id}: only a clock's first keyframe starts from the value showing" }
        }
    }

    /** Its first keyframe starts from the value showing when its clock starts. */
    val fromCurrent: Boolean get() = keys[0].fromCurrent

    /** Keyframe [k]'s value in real units, with the theme's value for this property [theme]. */
    private fun real(k: Keyframe, theme: Float, from: Float) =
        if (k.fromCurrent && !from.isNaN()) from else if (prop.look && !k.absolute) k.value * theme else k.value

    /**
     * The value at [t] with the theme's value [theme]. Before the first keyframe and after the last it holds them; beyond
     * 0 or 1 (a spring overshooting) the end segment goes on evenly, so a panel's bounce shows; always within the bounds.
     */
    fun valueAt(t: Float, theme: Float, from: Float = Float.NaN): Float {
        val n = at.size
        val v = when {
            n == 1 -> real(keys[0], theme, from)
            t > 1f && at[n - 1] >= 1f -> extrapolate(n - 2, n - 1, t, theme, from)
            t < 0f && at[0] <= 0f -> extrapolate(0, 1, t, theme, from)
            t <= at[0] -> real(keys[0], theme, from)
            t >= at[n - 1] -> real(keys[n - 1], theme, from)
            else -> {
                var i = 0
                while (at[i + 1] < t) i++
                val a = keys[i]
                val b = keys[i + 1]
                val u = (t - a.at) / (b.at - a.at)
                val va = real(a, theme, from)
                va + (real(b, theme, from) - va) * a.ease.at(u)
            }
        }
        return v.coerceIn(prop.min, prop.max)
    }

    /** Beyond an end: on along the line through keyframes [i] and [j] (a hold does not move on). */
    private fun extrapolate(i: Int, j: Int, t: Float, theme: Float, from: Float): Float {
        val a = keys[i]
        val b = keys[j]
        val end = if (t > 1f) b else a
        if (a.ease == Easing.Hold || a.ease == Easing.Jump) return real(end, theme, from)
        val slope = (real(b, theme, from) - real(a, theme, from)) / (b.at - a.at)
        return real(end, theme, from) + slope * (t - end.at)
    }

    /** The same keyframes backwards in time (each easing turned around, so the way back is the mirror of the way there). */
    fun reversed(): Track {
        val n = keys.size
        return Track(prop, timing = timing, keys = List(n) { i ->
            val k = keys[n - 1 - i]
            // The easing out of a keyframe on the way back is the one into it on the way there, mirrored.
            val into = if (i < n - 1) keys[n - 2 - i].ease.mirrored() else Easing.Linear
            // (A start from the value showing has no meaning at the other end: reversed, every keyframe has its own value.)
            k.copy(at = 1f - k.at, ease = into, fromCurrent = false)
        })
    }

    override fun equals(other: Any?) = other is Track && other.prop == prop && other.keys == keys && other.timing == timing
    override fun hashCode() = (prop.hashCode() * 31 + keys.hashCode()) * 31 + timing.hashCode()
    override fun toString() = "Track(${prop.id}, $timing, $keys)"
}

/** The easing that plays this one backwards: f'(u) = 1 - f(1 - u). */
fun Easing.mirrored(): Easing = when (this) {
    Easing.Linear -> Easing.Linear
    Easing.Hold -> Easing.Jump
    Easing.Jump -> Easing.Hold
    is Easing.Bezier -> Easing.Bezier(1f - x2, 1f - y2, 1f - x1, 1f - y1)
    is Easing.Power -> Easing.Power(exponent, !out)
}
