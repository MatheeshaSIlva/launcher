package dev.launcher.engine.ui

import dev.launcher.app.design.Curve
import dev.launcher.engine.ui.motion.Track

/** A bounded marquee: pause, travel at 24 dp/s, pause, return on motion.move. Times are absolute ns. */
class TitleScroll {
    private data class Geometry(val text: String, val width: Float, val slot: Float, val rtl: Boolean, val speed: Float)
    private var geometry: Geometry? = null
    private var allowed = false
    private var frozen = false
    private var curve: Curve? = null
    private var stage = Stage.IDLE
    private var started = 0L
    private var origin = 0f
    private val back = Track()
    var offset = 0f; private set
    val busy get() = !frozen && stage != Stage.IDLE
    private enum class Stage { IDLE, PAUSE_START, FORWARD, PAUSE_END, BACK }
    private val distance get() = geometry?.let { (it.width - it.slot).coerceAtLeast(0f) } ?: 0f
    private val end get() = if (geometry?.rtl == true) distance else -distance

    fun configure(text: String, width: Float, slot: Float, rtl: Boolean, speed: Float, now: Long): Boolean {
        val next = Geometry(text, width, slot, rtl, speed)
        if (next == geometry) return false
        val speedBefore = velocity(now)
        val old = geometry
        geometry = next
        if (old != null) offset += base(old) - base(next)
        // Keep the shown offset, including a change of direction or a shorter replacement title.
        returning(now, speedBefore)
        return true
    }

    fun enabled(run: Boolean, eligible: Boolean, exiting: Boolean, reduced: Boolean, motion: Curve?, now: Long) {
        curve = motion
        if (exiting || !eligible) { frozen = true; stage = Stage.IDLE; back.snap(offset); allowed = false; return }
        frozen = false
        if (reduced) { offset = 0f; back.snap(0f); stage = Stage.IDLE; allowed = false; return }
        val on = run && distance > 0 && geometry?.speed?.let { it.isFinite() && it > 0 } == true
        if (on != allowed) {
            allowed = on
            returning(now)
        }
    }

    private fun base(g: Geometry) = if (g.rtl) -(g.width - g.slot).coerceAtLeast(0f) else 0f

    private fun velocity(now: Long): Float = when (stage) {
        Stage.FORWARD -> (geometry?.speed ?: 0f) * if (end < 0) -1 else 1
        Stage.BACK -> back.velocity(now)
        else -> 0f
    }

    private fun returning(now: Long, speed: Float = velocity(now)) {
        if (offset != 0f && curve != null) {
            back.continueFrom(offset, speed, 0f, curve!!, now)
            stage = Stage.BACK
        } else { offset = 0f; begin(now) }
    }

    private fun begin(now: Long) {
        started = now
        stage = if (allowed && distance > 0) Stage.PAUSE_START else Stage.IDLE
    }

    fun step(now: Long): Boolean {
        if (frozen) return false
        // Consume elapsed stages so exact-time tests and a delayed frame produce the same position.
        repeat(4) {
            val seconds = ((now - started) / 1e9).coerceAtLeast(0.0)
            when (stage) {
                Stage.IDLE -> return false
                Stage.PAUSE_START -> if (seconds < 2) return true else { started += PAUSE; origin = offset; stage = Stage.FORWARD }
                Stage.FORWARD -> {
                    val speed = geometry!!.speed
                    val duration = kotlin.math.abs(end - origin) / speed
                    if (seconds < duration) { offset = origin + (end - origin) * (seconds / duration).toFloat(); return true }
                    offset = end; started += (duration * 1e9).toLong(); stage = Stage.PAUSE_END
                }
                Stage.PAUSE_END -> if (seconds < 2) return true else { started += PAUSE; returning(started) }
                Stage.BACK -> {
                    if (back.step(now, 0.01f)) { offset = back.value; return true }
                    offset = 0f; begin(now); return busy
                }
            }
        }
        return busy
    }
    companion object {
        private const val PAUSE = 2_000_000_000L
        internal fun rtl(text: String) = !java.text.Bidi(text, java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight()
    }
}
