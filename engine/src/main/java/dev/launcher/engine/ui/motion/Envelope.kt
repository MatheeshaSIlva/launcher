package dev.launcher.engine.ui.motion

import dev.launcher.app.Spring
import dev.launcher.app.design.Curve
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.Mover
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The extent a channel's value can reach over its whole trajectory, from its closed form (T-009 §3, Codex's round two):
 * conservative by construction, no sampling and no time horizon. Recording and layer bounds are sized from it, so nothing
 * the motion draws is cut and no layer changes size between interruptions.
 */
object Envelope {
    /** The lowest and highest value [mover], started at [from] moving at [velocity] towards [to] on [curve], can show. */
    fun range(curve: Curve, mover: Mover, from: Float, velocity: Float, to: Float): ClosedFloatingPointRange<Float> = when {
        mover is EngineEase -> ease(mover, from, to)
        curve is Curve.Spring -> spring(curve, from - to, velocity).let { (to - it)..(to + it) }
        curve is Curve.Ease -> spring(Continuation.springFor(curve), from - to, velocity).let { (to - it)..(to + it) }
        else -> minOf(from, to)..maxOf(from, to)
    }.let { r -> minOf(r.start, from, to)..maxOf(r.endInclusive, from, to) }

    /**
     * `from + Δ·p(u) + c·t·(1 − u)²`: p within the hull of its y controls; the carried term peaks at `|c|·4d/27` (t = d/3),
     * on its own sign's side.
     */
    private fun ease(m: EngineEase, from: Float, to: Float): ClosedFloatingPointRange<Float> {
        val (lo, hi) = m.hull
        val d = (to - from).toDouble()
        val a = from + d * lo
        val b = from + d * hi
        val c = abs(m.carry) * 4 * m.duration / 27
        return (minOf(a, b) - c).toFloat()..(maxOf(a, b) + c).toFloat()
    }

    /**
     * The largest offset from the target a spring started at offset [x0] with speed [v0] reaches, from its closed form
     * ([Spring]'s three regimes, slow motion included). Underdamped: `e^(−ζωt)(A cos ω_d t + B sin ω_d t)` is within
     * `√(A² + B²)`, and within `|A| + |v0 + ζωx0|/(eζω)` (sin ω_d t / ω_d ≤ t); overdamped: within `|C1| + |C2|`, and within
     * `|x0| + |v0 + ζωx0|/(e(ζω − β))` (sinh βt / β ≤ t·e^(βt)); critical: `|A| + |B|/(eω)`. The smaller of each pair: the
     * first grows without bound near critical damping.
     */
    fun spring(c: Curve.Spring, x0: Float, v0: Float): Float {
        val w = 2.0 * PI / (c.response * Motion.slow())
        val z = c.damping.toDouble()
        val x = x0.toDouble()
        val v = v0.toDouble()
        val tail = abs(v + z * w * x)
        val bound = if (z < 1.0) {
            val wd = w * sqrt(1 - z * z)
            minOf(sqrt(x * x + (tail / wd).let { it * it }), abs(x) + tail / (E * z * w))
        } else if (z > 1.0 + 1e-6) {
            val s = w * sqrt(z * z - 1)
            val r1 = -z * w + s
            val r2 = -z * w - s
            val c2 = (v - r1 * x) / (r2 - r1)
            minOf(abs(x - c2) + abs(c2), abs(x) + tail / (E * (z * w - s)))
        } else abs(x) + abs(v + w * x) / (E * w)
        return bound.toFloat()
    }
}
