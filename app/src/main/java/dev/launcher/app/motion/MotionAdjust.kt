package dev.launcher.app.motion

import dev.launcher.app.design.Curve
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * A preset's two overall adjustments (docs/PLAN_MOTION.md, C4a), kept with the preset (`motion.adjust.speed`,
 * `motion.adjust.bounce`): Speed divides every curve's time (a spring's duration, a bezier's length); Bounce scales how far
 * a curve goes past its place (a spring's overshoot, a bezier's handles beyond its ends), so a motion that does not
 * overshoot keeps not overshooting (code that never saw an overshoot is never handed one). Pure: the design system applies
 * it where a curve is read, the settings to show a choice before it is applied.
 */
object MotionAdjust {
    const val SPEED_MIN = 0.5f
    const val SPEED_MAX = 2f
    const val BOUNCE_MAX = 2f
    /** The most a spring is let overshoot (of its travel), however far Bounce is turned up. */
    private const val MAX_OVERSHOOT = 0.6

    /** [c] at [speed] (1: as it is; 2: in half the time) and [bounce] (1: as it is; 0: no overshoot; 2: twice as far past). */
    fun apply(c: Curve, speed: Float, bounce: Float): Curve {
        if (speed == 1f && bounce == 1f) return c
        val s = speed.coerceIn(0.1f, 10f)
        val b = bounce.coerceAtLeast(0f)
        return when (c) {
            is Curve.Spring -> Curve.Spring((c.response / s).coerceAtLeast(0.01f), if (b == 1f) c.damping else damping(c.damping, b))
            is Curve.Ease -> Curve.Ease(c.x1, if (b == 1f) c.y1 else handle(c.y1, b), c.x2, if (b == 1f) c.y2 else handle(c.y2, b),
                (c.ms / s).coerceAtLeast(1f))
        }
    }

    /** How far a spring of [damping] released from rest goes past its target, of its travel (0 at 1 and above). */
    fun overshoot(damping: Float): Double =
        if (damping >= 1f) 0.0 else exp(-damping * PI / sqrt(1.0 - damping.toDouble() * damping))

    /** The damping that goes [overshoot] past (0 < overshoot < 1). */
    fun dampingFor(overshoot: Double): Float {
        val l = -ln(overshoot)
        return (l / sqrt(PI * PI + l * l)).toFloat()
    }

    private fun damping(d: Float, b: Float): Float {
        if (d >= 1f) return d
        val os = overshoot(d) * b
        if (os < 1e-4) return 1f
        return dampingFor(os.coerceAtMost(MAX_OVERSHOOT)).coerceIn(0.05f, 1f)
    }

    /** A bezier's handle: past the end (above 1) or before the start (below 0) by [b] times as much; within, as it is. */
    private fun handle(y: Float, b: Float): Float = when {
        y > 1f -> (1f + (y - 1f) * b).coerceAtMost(3f)
        y < 0f -> (y * b).coerceAtLeast(-2f)
        else -> y
    }
}
