package dev.launcher.engine.ui.motion

import dev.launcher.app.Spring
import dev.launcher.app.design.Curve
import dev.launcher.app.motion.BezierMath
import dev.launcher.app.motion.CurveFit
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.Mover
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * How a node's motion channel starts (T-009, 13a-0): [fresh] for a channel that had settled, [continued] for every
 * interruption, a turning point at zero speed included. A continued curve keeps the value and the speed it had at the
 * switch: the launcher's `Ease.start` adds the curve's own initial slope to the carried speed, which is a jump in speed
 * for any curve that does not start flat. The launcher's motion keeps its own path; only the engine's nodes use this.
 */
object Continuation {
    private val fits = ConcurrentHashMap<Curve.Ease, Curve.Spring>()

    /** A channel at rest at [from] starting towards [to]: the authored curve from rest. */
    fun fresh(curve: Curve, from: Float, to: Float): Mover = when (curve) {
        is Curve.Spring -> Spring(curve.response, curve.damping).apply { start(from, 0f, to) }
        is Curve.Ease -> EngineEase(curve).apply { start(from, 0f, to) }
    }

    /** A channel interrupted at [from], moving at [velocity] (units/s), now going to [to]. */
    fun continued(curve: Curve, from: Float, velocity: Float, to: Float): Mover = when (curve) {
        is Curve.Spring -> Spring(curve.response, curve.damping).apply { start(from, velocity, to) }
        is Curve.Ease -> {
            val slope = EngineEase.initialSlope(curve)
            if (slope == null) {
                // A vertical initial tangent cannot be met by a finite correction: the spring that runs most like it.
                val s = fits.getOrPut(curve) { CurveFit.springLike(curve) }
                Spring(s.response, s.damping).apply { start(from, velocity, to) }
            } else EngineEase(curve).apply { continueFrom(from, velocity, to, slope) }
        }
    }
}

/**
 * A cubic bezier over its duration for the engine's nodes: the launcher's `Ease` shape, with the value's exact derivative
 * as its speed (no slope clamp) and a continuation that carries the speed at the switch exactly. The carried term
 * `c·t·(1 − u)²` ends at zero at the end, so the value still lands on the target at the duration.
 */
class EngineEase(curve: Curve.Ease) : Mover {
    private val x1 = curve.x1.toDouble()
    private val y1 = curve.y1.toDouble()
    private val x2 = curve.x2.toDouble()
    private val y2 = curve.y2.toDouble()
    private val start0 = initialSlope(curve)
    private val end0 = endSlope(curve)
    /** The duration, s (slow motion included). */
    val duration = (curve.ms / 1000.0 * Motion.slow()).coerceAtLeast(0.001)
    private val end = duration - 1e-6
    private var from = 0.0
    private var carried = 0.0
    override var target = 0f
        private set

    /** The carried term's coefficient (units/s), for the envelope. */
    val carry get() = carried

    override fun start(from: Float, velocity: Float, to: Float) {
        this.from = from.toDouble()
        carried = velocity.toDouble()
        target = to
    }

    /** Continues from [from] at [velocity]: the carried term is what the curve's own initial speed leaves over. */
    fun continueFrom(from: Float, velocity: Float, to: Float, slope: Double) {
        this.from = from.toDouble()
        target = to
        carried = velocity - (to - from) * slope / duration
    }

    override fun value(t: Double): Float {
        if (t >= end) return target
        val u = (t / duration).coerceAtLeast(0.0)
        val k = 1 - u
        val p = BezierMath.y(BezierMath.solveS(u, x1, x2), y1, y2)
        return (from + (target - from) * p + carried * t * k * k).toFloat()
    }

    override fun velocity(t: Double): Float {
        if (t >= end) return 0f
        val u = (t / duration).coerceAtLeast(0.0)
        return ((target - from) * slope(u) / duration + carried * (1 - u) * (1 - 3 * u)).toFloat()
    }

    override fun settled(t: Double, epsilon: Float): Boolean = t >= end

    /** dp/du at [u]: dy/ds over dx/ds, the tangent's limit where both vanish (degenerate controls at an end). */
    private fun slope(u: Double): Double {
        val s = BezierMath.solveS(u, x1, x2)
        val dx = BezierMath.dx(s, x1, x2)
        if (abs(dx) > 1e-9) return BezierMath.dy(s, y1, y2) / dx
        val limit = if (s < 0.5) start0 else end0
        return limit ?: (VERTICAL * Math.signum(BezierMath.dy(s, y1, y2)))
    }

    companion object {
        /** Stands in for an infinite slope at an exactly vertical end (progress per unit of time). */
        private const val VERTICAL = 1e6

        /**
         * The slope of the curve's initial tangent: the direction to the first control point that is not at the origin
         * (P1, else P2, else the end). Null when that tangent is vertical.
         */
        fun initialSlope(c: Curve.Ease): Double? {
            val pts = listOf(c.x1.toDouble() to c.y1.toDouble(), c.x2.toDouble() to c.y2.toDouble(), 1.0 to 1.0)
            val (x, y) = pts.first { abs(it.first) > 1e-9 || abs(it.second) > 1e-9 }
            return if (abs(x) <= 1e-9) null else y / x
        }

        /** The slope of the final tangent: from the last control point that is not at the end (P2, else P1, else the start). */
        fun endSlope(c: Curve.Ease): Double? {
            val pts = listOf(c.x2.toDouble() to c.y2.toDouble(), c.x1.toDouble() to c.y1.toDouble(), 0.0 to 0.0)
            val (x, y) = pts.first { abs(1 - it.first) > 1e-9 || abs(1 - it.second) > 1e-9 }
            return if (abs(1 - x) <= 1e-9) null else (1 - y) / (1 - x)
        }
    }
}
