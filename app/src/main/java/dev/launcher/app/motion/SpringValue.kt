package dev.launcher.app.motion

import android.view.Choreographer
import android.view.View
import dev.launcher.app.Spring

/**
 * One animated number on a spring, frame-exact ([Spring] is solved analytically). Retargeting mid-flight keeps the current
 * velocity, so every animation built on it can be redirected or grabbed without a jump (iOS behaviour). Main thread only.
 * Values are scaled by [scale] for the spring's rest threshold (use the px size of the motion for 0..1 values).
 */
class SpringValue(
    initial: Float = 0f,
    private val scale: Float = 1f,
    private val onChange: (Float) -> Unit,
    private val onRest: () -> Unit = {},
) {
    var value = initial
        private set
    var target = initial
        private set
    var isAnimating = false
        private set
    private var spring: Spring? = null
    private var startNs = 0L
    private var posted = false

    /** Current velocity in value units per second. */
    val velocity: Float
        get() = if (!isAnimating) 0f else (spring?.velocity((System.nanoTime() - startNs) / 1e9) ?: 0f) / scale

    fun snapTo(v: Float) {
        isAnimating = false
        target = v
        if (v != value) { value = v; onChange(v) }
    }

    /** Springs to [to]; [velocity] (units/s) defaults to the current motion's. */
    fun animateTo(to: Float, spec: SpringSpec, velocity: Float = this.velocity) {
        target = to
        spring = spec.spring().apply { start(value * scale, velocity * scale, to * scale) }
        startNs = System.nanoTime()
        isAnimating = true
        if (!posted) { posted = true; Choreographer.getInstance().postFrameCallback(frame) }
    }

    fun stop() { isAnimating = false }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            posted = false
            if (!isAnimating) return
            val s = spring ?: return
            val t = maxOf(0L, now - startNs) / 1e9
            if (s.settled(t)) {
                isAnimating = false
                value = target
                onChange(value)
                onRest()
            } else {
                value = s.value(t) / scale
                onChange(value)
                posted = true
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }
}

/** Slides [view] from where it appears now back to its layout position on [spec] (translation to 0), interruptible. */
class SpringTranslate(private val view: View) {
    private val x = SpringValue(0f, 1f, { view.translationX = it })
    private val y = SpringValue(0f, 1f, { view.translationY = it })

    /** The view's layout moved: it is shown offset by ([tx], [ty]) from its new place (keeping any motion it had) and springs home. */
    fun springFrom(tx: Float, ty: Float, spec: SpringSpec) {
        val vx = x.velocity
        val vy = y.velocity
        x.snapTo(tx); y.snapTo(ty)
        x.animateTo(0f, spec, vx); y.animateTo(0f, spec, vy)
    }

    companion object {
        fun of(v: View): SpringTranslate = (v.getTag(dev.launcher.app.R.id.spring_translate) as? SpringTranslate)
            ?: SpringTranslate(v).also { v.setTag(dev.launcher.app.R.id.spring_translate, it) }
    }
}

/** Something entering or leaving home: a new item grows into its place on a spring, a removed one shrinks away. */
object Appear {
    /** [v] grows from [from] of its size to full size (a little overshoot) while it fades in. */
    fun grow(v: View, spec: SpringSpec = Motion.profile.appear, from: Float = 0.7f, fadeMs: Long = Motion.profile.appearMs) {
        v.scaleX = from; v.scaleY = from
        v.alpha = 0f
        SpringValue(from, 100f, { k -> v.scaleX = k; v.scaleY = k }).animateTo(1f, spec)
        v.animate().alpha(1f).setDuration(fadeMs).start()
    }

    /** [v] shrinks and fades away, then [then] runs (removing it). */
    fun vanish(v: View, ms: Long = Motion.profile.disappearMs, then: () -> Unit) {
        v.animate().scaleX(0.6f).scaleY(0.6f).alpha(0f).setDuration(ms).withEndAction(then).start()
    }
}
