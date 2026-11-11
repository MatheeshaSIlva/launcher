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
    private val fitted = java.util.concurrent.atomic.AtomicInteger()

    /** Spring fits searched so far (tests: one per curve, however often it is continued). */
    internal val fitCount get() = fitted.get()

    /** The spring an interrupted [curve] with a vertical initial tangent continues as (fitted once per curve). */
    fun springFor(curve: Curve.Ease): Curve.Spring =
        fits.computeIfAbsent(curve) { fitted.incrementAndGet(); CurveFit.springLike(it) }

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
                val s = springFor(curve)
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
    private var from = 0.0
    private var carried = 0.0
    override var target = 0f
        private set

    /** The carried term's coefficient (units/s), for the envelope. */
    val carry get() = carried

    /** Where the curve's progress can go: the hull of its y controls, 0 and 1 (a bezier lies inside its control hull). */
    val hull get() = minOf(0.0, y1, y2) to maxOf(1.0, y1, y2)

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
        // The exact end: a curve with a vertical end tangent still moves visibly in its last microsecond.
        if (t >= duration) return target
        val u = (t / duration).coerceAtLeast(0.0)
        val k = 1 - u
        val p = BezierMath.y(BezierMath.solveS(u, x1, x2), y1, y2)
        return (from + (target - from) * p + carried * t * k * k).toFloat()
    }

    override fun velocity(t: Double): Float {
        if (t >= duration) return 0f
        val u = (t / duration).coerceAtLeast(0.0)
        return ((target - from) * slope(u) / duration + carried * (1 - u) * (1 - 3 * u)).toFloat()
    }

    override fun settled(t: Double, epsilon: Float): Boolean = t >= duration

    /**
     * dp/du at [u]: dy/ds over dx/ds. Where dx vanishes at an end, the end tangent's limit (degenerate controls); where it
     * vanishes inside the curve (x flat for an instant, as in `cubic-bezier(1, 0, 0, 1)` at its middle), the curve is
     * vertical there.
     */
    private fun slope(u: Double): Double {
        val s = BezierMath.solveS(u, x1, x2)
        val dx = BezierMath.dx(s, x1, x2)
        val dy = BezierMath.dy(s, y1, y2)
        if (abs(dx) > 1e-9) return dy / dx
        return when {
            s < 1e-6 -> start0 ?: VERTICAL * Math.signum(dy)
            s > 1 - 1e-6 -> end0 ?: VERTICAL * Math.signum(dy)
            abs(dy) > 1e-9 -> VERTICAL * Math.signum(dy)
            else -> interiorLimit(s)
        }
    }

    /**
     * Both dx and dy vanish inside the curve (`cubic-bezier(1, 1, 0, 0)` at its middle): the ratio's limit is the ratio of
     * the first derivatives that do not (L'Hôpital), second then third.
     */
    private fun interiorLimit(s: Double): Double {
        fun d2(a: Double, b: Double) = 6 * (1 - 3 * b + 3 * a) * s + 2 * (3 * b - 6 * a)
        fun d3(a: Double, b: Double) = 6 * (1 - 3 * b + 3 * a)
        for ((dx, dy) in listOf(d2(x1, x2) to d2(y1, y2), d3(x1, x2) to d3(y1, y2))) {
            if (abs(dx) > 1e-9) return dy / dx
            if (abs(dy) > 1e-9) return VERTICAL * Math.signum(dy)
        }
        return 1.0
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
