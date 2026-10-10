package dev.launcher.app.motion

import dev.launcher.app.Spring
import dev.launcher.app.design.Curve
import kotlin.math.abs

/**
 * One motion of a value towards a target, solved for any time since its start (docs/PLAN_LAYOUTS_THEMES.md, B3): a
 * spring ([Spring]) or a bezier curve ([Ease]). Every animation of the launcher runs on one, so any role can take either
 * kind of curve. Started from where the value is and how fast it moves, so a new target never makes it jump.
 */
interface Mover {
    val target: Float

    /** Starts from [from] moving at [velocity] (units/s) towards [to]. */
    fun start(from: Float, velocity: Float, to: Float)

    /** The value [t] seconds after the start. */
    fun value(t: Double): Float

    /** The speed (units/s) [t] seconds after the start. */
    fun velocity(t: Double): Float

    /** At rest [t] seconds after the start ([epsilon]: what a pixel can show, in the units it was started in). */
    fun settled(t: Double, epsilon: Float = 0.5f): Boolean
}

/** A role's curve as something that moves: a spring, or a bezier over its duration. */
fun Curve.mover(): Mover = when (this) {
    is Curve.Spring -> Spring(response, damping)
    is Curve.Ease -> Ease(x1, y1, x2, y2, ms / 1000f)
}

/**
 * A cubic bezier timing curve (CSS's `cubic-bezier`) over [durationS] seconds (stretched by slow motion), from the value
 * and speed it is started with. The curve takes the value from where it was to the target; the speed it had carries on
 * and fades out by the end (`v0 · t · (1 − t/d)²`), so a curve started mid-motion (a new target, a finger let go) goes
 * on smoothly instead of stopping dead, and ends exactly on the target at the end of its duration.
 */
class Ease(private val x1: Float, private val y1: Float, private val x2: Float, private val y2: Float, durationS: Float) : Mover {
    // The end, a microsecond early: a duration given in float seconds is a hair longer as a double.
    private val d = (durationS * Motion.slow()).toDouble().coerceAtLeast(0.001)
    private val end = d - 1e-6
    private var from = 0.0
    private var v0 = 0.0
    override var target = 0f
        private set

    override fun start(from: Float, velocity: Float, to: Float) {
        this.from = from.toDouble()
        v0 = velocity.toDouble()
        target = to
    }

    override fun value(t: Double): Float {
        if (t >= end) return target
        val u = (t / d).coerceAtLeast(0.0)
        val k = 1 - u
        return (from + (target - from) * progress(u) + v0 * t * k * k).toFloat()
    }

    override fun velocity(t: Double): Float {
        if (t >= end) return 0f
        val u = (t / d).coerceAtLeast(0.0)
        return ((target - from) * slope(u) / d + v0 * (1 - u) * (1 - 3 * u)).toFloat()
    }

    override fun settled(t: Double, epsilon: Float): Boolean = t >= end

    /** How far along the curve is when [u] of its time has passed (0..1, may overshoot for y outside 0..1). */
    fun progress(u: Double): Double = BezierMath.y(BezierMath.solveS(u, x1.toDouble(), x2.toDouble()), y1.toDouble(), y2.toDouble())

    /** The curve's steepness at [u] (progress per unit of time), bounded where it starts or ends vertically. */
    private fun slope(u: Double): Double {
        val s = BezierMath.solveS(u, x1.toDouble(), x2.toDouble())
        val dx = BezierMath.dx(s, x1.toDouble(), x2.toDouble())
        val dy = BezierMath.dy(s, y1.toDouble(), y2.toDouble())
        return if (abs(dx) < 1e-6) MAX_SLOPE * Math.signum(dy) else (dy / dx).coerceIn(-MAX_SLOPE, MAX_SLOPE)
    }

    private companion object {
        const val MAX_SLOPE = 20.0
    }
}

/** A unit cubic bezier from (0, 0) to (1, 1) with control points (x1, y1) and (x2, y2), as CSS defines it. Pure. */
object BezierMath {
    /** One coordinate at parameter [s]: 3(1−s)²s·a + 3(1−s)s²·b + s³. */
    private fun at(s: Double, a: Double, b: Double): Double = ((1 - 3 * b + 3 * a) * s + (3 * b - 6 * a)) * s * s + 3 * a * s
    private fun dAt(s: Double, a: Double, b: Double): Double = (3 * (1 - 3 * b + 3 * a) * s + 2 * (3 * b - 6 * a)) * s + 3 * a

    fun x(s: Double, x1: Double, x2: Double) = at(s, x1, x2)
    fun y(s: Double, y1: Double, y2: Double) = at(s, y1, y2)
    fun dx(s: Double, x1: Double, x2: Double) = dAt(s, x1, x2)
    fun dy(s: Double, y1: Double, y2: Double) = dAt(s, y1, y2)

    /** The parameter where x = [u] (x is monotonic for x1, x2 in 0..1): Newton's method, bisection where it fails. */
    fun solveS(u: Double, x1: Double, x2: Double): Double {
        if (u <= 0.0) return 0.0
        if (u >= 1.0) return 1.0
        var s = u
        repeat(8) {
            val e = at(s, x1, x2) - u
            if (abs(e) < 1e-7 && s in 0.0..1.0) return s
            val d = dAt(s, x1, x2)
            if (abs(d) < 1e-6) return@repeat
            s -= e / d
        }
        var lo = 0.0
        var hi = 1.0
        s = u
        repeat(40) {
            val xs = at(s, x1, x2)
            if (abs(xs - u) < 1e-7) return s
            if (xs < u) lo = s else hi = s
            s = (lo + hi) / 2
        }
        return s
    }
}
