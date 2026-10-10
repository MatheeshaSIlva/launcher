package dev.launcher.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Damped spring (mass 1), solved analytically so any frame time gives the exact position and velocity: no integration
 * error and no dependence on frame pacing. Parameterised like SwiftUI/UIKit springs:
 * [response] = period of the undamped oscillation in seconds, [damping] = damping fraction (1 = critically damped).
 */
class Spring(private val response: Float, private val damping: Float) : dev.launcher.app.motion.Mover {
    // Natural angular frequency; slow motion (a debug token, 1 = normal) stretches every spring's period alike.
    private val omega = 2.0 * PI / (response * dev.launcher.app.motion.Motion.slow())
    private var x0 = 0.0                                    // start offset from target
    private var v0 = 0.0                                    // start velocity (units per second)
    override var target = 0f
        private set

    /** Starts from [from] moving at [velocity] (units/s) towards [to]. */
    override fun start(from: Float, velocity: Float, to: Float) {
        target = to
        x0 = (from - to).toDouble()
        v0 = velocity.toDouble()
    }

    /** Offset from target and velocity after [t] seconds. */
    private fun state(t: Double): Pair<Double, Double> {
        val z = damping.toDouble()
        return if (z < 1.0) {
            val wd = omega * sqrt(1 - z * z)
            val a = x0
            val b = (v0 + z * omega * x0) / wd
            val e = exp(-z * omega * t)
            val x = e * (a * cos(wd * t) + b * sin(wd * t))
            val v = e * ((-z * omega) * (a * cos(wd * t) + b * sin(wd * t)) + (-a * wd * sin(wd * t) + b * wd * cos(wd * t)))
            x to v
        } else {
            val e = exp(-omega * t)
            val x = (x0 + (v0 + omega * x0) * t) * e
            val v = (v0 - omega * (v0 + omega * x0) * t) * e
            x to v
        }
    }

    override fun value(t: Double): Float = (target + state(t).first).toFloat()
    override fun velocity(t: Double): Float = state(t).second.toFloat()

    /** At rest when both offset and speed are below what a pixel can show. */
    override fun settled(t: Double, epsilon: Float): Boolean {
        val (x, v) = state(t)
        return abs(x) < epsilon && abs(v) < epsilon * 10
    }
}
