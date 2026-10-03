package dev.launcher.app.motion

import android.view.Choreographer
import dev.launcher.app.Spring
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * One scroll axis with UIScrollView physics, solved analytically per frame (exact at any frame time, no drift):
 * a drag past either end is rubber-banded; a fling decelerates by [MotionProfile.decelerationRate] per millisecond; a fling
 * that runs past an end, or a release while past it, comes back on a critically damped spring carrying its velocity.
 * Positions are content offsets in px ([minPos] = start, [maxPos] = end). Main thread only.
 */
class IosScroller(private val onScroll: (Float) -> Unit, private val onSettle: () -> Unit = {}) {
    private val choreographer = Choreographer.getInstance()

    var position = 0f
        private set
    var minPos = 0f
        private set
    var maxPos = 0f
        private set
    private var viewport = 1f

    private enum class Mode { IDLE, DRAG, FLING, SPRING }
    private var mode = Mode.IDLE
    private var startNs = 0L
    private var p0 = 0f
    private var v0 = 0f
    private var spring: Spring? = null
    private var raw = 0f
    private var posted = false

    /** Scrolling on its own (fling or bounce); a touch should stop it rather than tap through. */
    val isSettling get() = mode == Mode.FLING || mode == Mode.SPRING
    val isDragging get() = mode == Mode.DRAG

    fun setBounds(min: Float, max: Float, viewportSize: Float) {
        minPos = min
        maxPos = maxOf(min, max)
        viewport = maxOf(1f, viewportSize)
        if (mode == Mode.IDLE && (position < minPos || position > maxPos)) set(position.coerceIn(minPos, maxPos))
    }

    /** Jumps without animation (e.g. back to the top when a drawer closes). */
    fun jumpTo(p: Float) {
        mode = Mode.IDLE
        set(p.coerceIn(minPos, maxPos))
    }

    fun beginDrag() {
        mode = Mode.DRAG
        raw = rawFor(position)
    }

    /** The finger moved the content by [delta] px (positive = towards [maxPos]). */
    fun dragBy(delta: Float) {
        if (mode != Mode.DRAG) beginDrag()
        raw += delta
        set(displayFor(raw))
    }

    /** Finger lifted with [velocity] px/s (positive = towards [maxPos]). */
    fun endDrag(velocity: Float) {
        when {
            position < minPos -> springTo(minPos, velocity)
            position > maxPos -> springTo(maxPos, velocity)
            abs(velocity) > 40f -> fling(velocity)
            else -> { mode = Mode.IDLE; onSettle() }
        }
    }

    /** Animates to [target] (clamped) on the overscroll spring. */
    fun animateTo(target: Float, velocity: Float = 0f) = springTo(target.coerceIn(minPos, maxPos), velocity)

    fun stop() { mode = Mode.IDLE }

    private fun fling(velocity: Float) {
        mode = Mode.FLING
        p0 = position
        v0 = velocity
        startNs = System.nanoTime()
        post()
    }

    private fun springTo(target: Float, velocity: Float) {
        mode = Mode.SPRING
        spring = Motion.profile.overscrollReturn.spring().apply { start(position, velocity, target) }
        startNs = System.nanoTime()
        post()
    }

    private fun post() {
        if (!posted) { posted = true; choreographer.postFrameCallback(frame) }
    }

    private val frame = Choreographer.FrameCallback { now ->
        posted = false
        val t = maxOf(0L, now - startNs) / 1e9
        when (mode) {
            Mode.FLING -> {
                val r = Motion.profile.decelerationRate.toDouble()
                val ms = t * 1000.0
                val decay = r.pow(ms)
                val p = p0 + (v0 / 1000.0) * (decay - 1.0) / ln(r)
                val v = (v0 * decay).toFloat()
                set(p.toFloat())
                when {
                    position < minPos || position > maxPos -> {
                        // Ran past an end: the spring takes over with the fling's velocity and brings it back.
                        val target = if (position < minPos) minPos else maxPos
                        spring = Motion.profile.overscrollReturn.spring().apply { start(position, v, target) }
                        mode = Mode.SPRING
                        startNs = now
                        post()
                    }
                    abs(v) < 12f -> { mode = Mode.IDLE; onSettle() }
                    else -> post()
                }
            }
            Mode.SPRING -> {
                val s = spring
                if (s == null) { mode = Mode.IDLE; onSettle() } else {
                    set(s.value(t))
                    if (s.settled(t)) { set(s.target); mode = Mode.IDLE; onSettle() } else post()
                }
            }
            else -> {}
        }
    }

    private fun set(p: Float) {
        if (p == position) return
        position = p
        onScroll(p)
    }

    // Rubber band: raw finger travel past an end -> displayed offset, and back (so a drag can start from an overscroll).
    private fun displayFor(r: Float): Float = when {
        r < minPos -> minPos + Motion.rubberBand(r - minPos, viewport)
        r > maxPos -> maxPos + Motion.rubberBand(r - maxPos, viewport)
        else -> r
    }

    private fun rawFor(d: Float): Float {
        fun inverse(y: Float): Float {
            val c = Motion.profile.rubberBand
            val k = (abs(y) / viewport).coerceAtMost(0.95f)
            return kotlin.math.sign(y) * (viewport / c) * (1f / (1f - k) - 1f)
        }
        return when {
            d < minPos -> minPos + inverse(d - minPos)
            d > maxPos -> maxPos + inverse(d - maxPos)
            else -> d
        }
    }
}
