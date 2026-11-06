package dev.launcher.app.motion

import dev.launcher.app.design.Curve
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The motion editor's arithmetic (docs/PLAN_MOTION.md, C4b): a curve run once from rest (0 to 1) as the graph draws it,
 * SwiftUI's bounce for a spring's damping, where a spring's handle sits on the graph and the spring a handle moved there
 * makes, how long a curve runs, and one kind of curve fitted to the other (switching Spring | Curve keeps the feel).
 * Pure, and free of slow motion (the graph shows the curve itself).
 */
object CurveFit {
    /** SwiftUI's bounce (0: none; up to 1: further past; below 0: gentler than none) for a damping fraction, and back. */
    fun bounceOf(damping: Float): Float = if (damping <= 1f) 1f - damping else 1f / damping - 1f
    fun dampingOf(bounce: Float): Float = if (bounce >= 0f) 1f - bounce else 1f / (1f + bounce)

    /** How far [c] has gone (0 to 1; past 1 while it overshoots) [t] s after it started from rest. */
    fun at(c: Curve, t: Float): Float = when (c) {
        is Curve.Ease -> {
            val d = c.ms / 1000f
            if (t <= 0f) 0f else if (t >= d) 1f
            else BezierMath.y(BezierMath.solveS((t / d).toDouble(), c.x1.toDouble(), c.x2.toDouble()), c.y1.toDouble(), c.y2.toDouble()).toFloat()
        }
        is Curve.Spring -> if (t <= 0f) 0f else (1.0 + offset(c.response, c.damping, t.toDouble())).toFloat()
    }

    /** A spring started 1 short of its place at rest: how far from it it is [t] s later (negative short of it). */
    private fun offset(response: Float, damping: Float, t: Double): Double {
        val w = 2.0 * PI / response
        val z = damping.toDouble()
        return when {
            z < 1.0 -> {
                val wd = w * sqrt(1 - z * z)
                exp(-z * w * t) * (-cos(wd * t) - z * w / wd * sin(wd * t))
            }
            z > 1.0 + 1e-6 -> {
                val s = w * sqrt(z * z - 1)
                val r1 = -z * w + s
                val r2 = -z * w - s
                val c2 = r1 / (r2 - r1)
                (-1 - c2) * exp(r1 * t) + c2 * exp(r2 * t)
            }
            else -> (-1 - w * t) * exp(-w * t)
        }
    }

    /** How long [c] runs from rest until it stays within [eps] of its place (s); a bezier curve its length. */
    fun settleTime(c: Curve, eps: Float = 0.002f): Float = when (c) {
        is Curve.Ease -> c.ms / 1000f
        is Curve.Spring -> {
            val w = 2.0 * PI / c.response
            val z = c.damping.toDouble()
            if (z < 1.0) {
                // Its envelope under eps: from there on it never strays further.
                (ln(1.0 / sqrt(1 - z * z) / eps) / (z * w)).toFloat()
            } else {
                // It only approaches: the first time it is that close.
                var lo = 0.0
                var hi = 60.0 / w
                repeat(50) { val mid = (lo + hi) / 2; if (-offset(c.response, c.damping, mid) > eps) lo = mid else hi = mid }
                hi.toFloat()
            }
        }
    }

    /** Whether [s] goes far enough past its place for the graph to show it (its handle then sits on its peak). */
    fun bouncesVisibly(s: Curve.Spring): Boolean = s.damping < 1f && MotionAdjust.overshoot(s.damping) >= VISIBLE_OVERSHOOT

    /**
     * Where a spring's handle sits on the graph (s, value): its first peak when it goes visibly past, else where it reaches
     * 98 % (a small bounce, SwiftUI's under about 15 %, goes too little past to show, and peaks only after it has settled:
     * iOS 27's app opening's handle sat past the graph's end).
     */
    fun handleOf(s: Curve.Spring): Pair<Float, Float> {
        if (bouncesVisibly(s)) return s.response / (2f * sqrt(1f - s.damping * s.damping)) to (1f + MotionAdjust.overshoot(s.damping).toFloat())
        var lo = 0f
        var hi = settleTime(s, 0.0005f)
        // (Below the line it rises through 98 % once: what bounce it has stays under the graph's sight.)
        repeat(40) { val mid = (lo + hi) / 2f; if (at(s, mid) < HANDLE_REST) lo = mid else hi = mid }
        return hi to HANDLE_REST
    }

    /**
     * The spring whose handle is at ([time] s, [value]): visibly above the line of rest its first peak (how far past sets the
     * bounce, when the duration); else reaching 98 % then, with no bounce, or the small one it had kept (a handle grabbed and
     * not moved changes nothing; one brought down from a peak to the line loses its bounce).
     */
    fun springFromHandle(time: Float, value: Float, was: Curve.Spring): Curve.Spring {
        val t = time.coerceAtLeast(0.02f)
        if (value >= 1f + VISIBLE_OVERSHOOT) {
            val d = MotionAdjust.dampingFor((value - 1f).toDouble().coerceAtMost(0.6)).coerceIn(0.05f, 0.999f)
            return Curve.Spring((2f * t * sqrt(1f - d * d)).coerceIn(MIN_RESPONSE, MAX_RESPONSE), d)
        }
        val d = if (bouncesVisibly(was)) 1f else was.damping
        // The time it takes to reach 98 % grows in step with the response: scale to put it at [t].
        val unit = handleOf(Curve.Spring(1f, d)).first
        return Curve.Spring((t / unit).coerceIn(MIN_RESPONSE, MAX_RESPONSE), d)
    }

    /** The bezier curve that runs most like spring [s], over the time it takes to settle. */
    fun easeLike(s: Curve.Spring): Curve.Ease {
        val dur = settleTime(s, 0.005f).coerceIn(0.05f, 20f)
        // No bounce in, none out (its handles kept within the line of rest).
        val yMin = if (s.damping >= 1f) 0.0 else -1.0
        val yMax = if (s.damping >= 1f) 1.0 else 2.5
        val us = DoubleArray(SAMPLES) { (it + 1).toDouble() / (SAMPLES + 1) }
        val want = DoubleArray(SAMPLES) { at(s, (us[it] * dur).toFloat()).toDouble() }
        fun err(p: DoubleArray): Double {
            val x1 = p[0].coerceIn(0.0, 1.0); val x2 = p[2].coerceIn(0.0, 1.0)
            val y1 = p[1].coerceIn(yMin, yMax); val y2 = p[3].coerceIn(yMin, yMax)
            var e = 0.0
            for (i in us.indices) { val d = BezierMath.y(BezierMath.solveS(us[i], x1, x2), y1, y2) - want[i]; e += d * d }
            // Out of bounds costs: the fit stays a valid curve.
            return e + (abs(p[0] - x1) + abs(p[2] - x2) + abs(p[1] - y1) + abs(p[3] - y2)) * 10.0
        }
        val starts = listOf(doubleArrayOf(0.2, 0.0, 0.0, 1.0), doubleArrayOf(0.25, 0.6, 0.3, 1.0), doubleArrayOf(0.3, 1.25, 0.55, 1.0))
        val best = starts.map { nelderMead(::err, it, doubleArrayOf(0.1, 0.2, 0.1, 0.2)) }.minByOrNull(::err)!!
        return Curve.Ease(best[0].coerceIn(0.0, 1.0).toFloat(), best[1].coerceIn(yMin, yMax).toFloat(),
            best[2].coerceIn(0.0, 1.0).toFloat(), best[3].coerceIn(yMin, yMax).toFloat(), dur * 1000f)
    }

    /** The spring that runs most like bezier curve [e] (over its length and a little after). */
    fun springLike(e: Curve.Ease): Curve.Spring {
        val dur = e.ms / 1000f
        val minDamping = if (e.y1 in 0f..1f && e.y2 in 0f..1f) 1.0 else 0.1
        val ts = FloatArray(SAMPLES) { (it + 1) * dur * 1.3f / (SAMPLES + 1) }
        val want = FloatArray(SAMPLES) { at(e, ts[it]) }
        fun err(p: DoubleArray): Double {
            val r = p[0].coerceIn(MIN_RESPONSE.toDouble(), MAX_RESPONSE.toDouble()).toFloat()
            val d = p[1].coerceIn(minDamping, 2.0).toFloat()
            var sum = 0.0
            for (i in ts.indices) { val x = at(Curve.Spring(r, d), ts[i]) - want[i]; sum += x * x }
            return sum + (abs(p[0] - r) + abs(p[1] - d)) * 10.0
        }
        val starts = listOf(doubleArrayOf(dur * 0.75, 1.0), doubleArrayOf(dur * 0.6, 0.75), doubleArrayOf(dur.toDouble(), 1.2))
        val best = starts.map { nelderMead(::err, it, doubleArrayOf(dur * 0.2, 0.15)) }.minByOrNull(::err)!!
        return Curve.Spring(best[0].coerceIn(MIN_RESPONSE.toDouble(), MAX_RESPONSE.toDouble()).toFloat(), best[1].coerceIn(minDamping, 2.0).toFloat())
    }

    /** Nelder and Mead's simplex search for the least of [f] near [start]. */
    private fun nelderMead(f: (DoubleArray) -> Double, start: DoubleArray, step: DoubleArray, iterations: Int = 400): DoubleArray {
        val n = start.size
        val pts = Array(n + 1) { i -> start.copyOf().also { if (i > 0) it[i - 1] += step[i - 1] } }
        val vals = DoubleArray(n + 1) { f(pts[it]) }
        repeat(iterations) {
            val order = (0..n).sortedBy { vals[it] }
            val best = order.first(); val worst = order.last(); val second = order[n - 1]
            if (abs(vals[worst] - vals[best]) < 1e-12) return pts[best]
            val centroid = DoubleArray(n) { d -> order.dropLast(1).sumOf { pts[it][d] } / n }
            fun toward(k: Double) = DoubleArray(n) { d -> centroid[d] + k * (pts[worst][d] - centroid[d]) }
            val refl = toward(-1.0); val fr = f(refl)
            when {
                fr < vals[best] -> { val exp = toward(-2.0); val fe = f(exp); if (fe < fr) { pts[worst] = exp; vals[worst] = fe } else { pts[worst] = refl; vals[worst] = fr } }
                fr < vals[second] -> { pts[worst] = refl; vals[worst] = fr }
                else -> {
                    val con = toward(0.5); val fc = f(con)
                    if (fc < vals[worst]) { pts[worst] = con; vals[worst] = fc }
                    else for (i in 0..n) if (i != best) {
                        for (d in 0 until n) pts[i][d] = pts[best][d] + 0.5 * (pts[i][d] - pts[best][d])
                        vals[i] = f(pts[i])
                    }
                }
            }
        }
        return pts[(0..n).minByOrNull { vals[it] }!!]
    }

    /** Where a spring with no bounce has its handle: 98 % of the way. */
    private const val HANDLE_REST = 0.98f
    /** How far past its place (of the way) a spring must go for the graph to show its peak: half a percent. */
    const val VISIBLE_OVERSHOOT = 0.005f
    private const val SAMPLES = 40
    const val MIN_RESPONSE = 0.05f
    const val MAX_RESPONSE = 2f
}
