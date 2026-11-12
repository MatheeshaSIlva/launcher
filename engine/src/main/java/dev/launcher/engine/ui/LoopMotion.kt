package dev.launcher.engine.ui

import dev.launcher.app.design.Curve
import dev.launcher.engine.ui.motion.Track
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Drawing height only. The layout reserves [Spec.maximum]; no per-frame layout state is written. */
class LoopMotion {
    data class Spec(val from: Float, val to: Float, val periodMs: Float, val phase: Float) {
        val maximum get() = maxOf(from, to)
        val valid get() = from.isFinite() && to.isFinite() && from >= 0 && to >= 0 &&
            periodMs.isFinite() && periodMs in 200f..10000f && phase.isFinite() && phase in 0f..1f
        fun height(elapsed: Long): Float = (from + (to - from) *
            (0.5 + 0.5 * sin(2 * PI * (elapsed / (periodMs * 1e6) + phase)))).toFloat()
        fun speed(elapsed: Long): Float = ((to - from) * PI * 1000 / periodMs *
            cos(2 * PI * (elapsed / (periodMs * 1e6) + phase))).toFloat()
    }
    private var spec: Spec? = null
    private var started = 0L
    private var running = false
    private val settle = Track()
    private val residual = Track()
    var height = Float.NaN; private set
    private var velocity = 0f
    private var maximum = 0f
    val busy get() = running || settle.moving || residual.moving

    /** Freeze is used for a retained exit, including every descendant whose bindings remain frozen. */
    fun update(next: Spec?, run: Boolean, rest: Float, eligible: Boolean, reduced: Boolean,
               freeze: Boolean, curve: Curve?, now: Long) {
        if (freeze || !eligible) { running = false; velocity = 0f; settle.snap(height); residual.snap(0f); return }
        val valid = next?.takeIf { it.valid }
        if (reduced || valid == null) {
            spec = valid; running = false; velocity = 0f; height = rest; settle.snap(rest); residual.snap(0f); return
        }
        maximum = maxOf(rest, valid.maximum)
        val was = running
        val changed = spec != valid
        val speed = velocity
        if (run && (!was || changed)) {
            val previous = height
            spec = valid; started = now; running = true; settle.snap(rest)
            val wave = valid.height(0)
            if (previous.isFinite() && curve != null)
                residual.continueFrom(previous - wave, speed - valid.speed(0), 0f, curve, now)
            else residual.snap(0f)
        } else if (!run) {
            spec = valid; running = false
            if (was && height.isFinite() && curve != null) settle.continueFrom(height, speed, rest, curve, now)
            else settle.to(rest, curve, now)
        }
        step(now)
    }

    fun step(now: Long): Boolean {
        if (running) {
            residual.step(now, 0.01f)
            val elapsed = (now - started).coerceAtLeast(0)
            val raw = spec!!.height(elapsed) + residual.value
            height = raw.coerceIn(0f, maximum)
            velocity = if (raw != height) 0f else spec!!.speed(elapsed) + residual.velocity(now)
        } else if (settle.moving) {
            settle.step(now, 0.01f); height = settle.value.coerceAtLeast(0f)
            velocity = if (height == settle.value) settle.velocity(now) else 0f
        } else if (settle.value.isFinite()) { height = settle.value; velocity = 0f }
        return busy
    }
}
