package dev.launcher.engine.ui.motion

import android.view.Choreographer

/** Where frame callbacks come from: the thread's [Choreographer] on a device, a hand-driven clock in tests. */
interface FrameSource {
    fun post(callback: (frameTimeNanos: Long) -> Unit)
    fun remove(callback: (frameTimeNanos: Long) -> Unit)
}

/** The calling thread's [Choreographer] (the component's window thread, gesture nav's `nav-ui` in the shade). */
class ChoreographerSource : FrameSource {
    private val choreographer = Choreographer.getInstance()
    private val wrapped = HashMap<(Long) -> Unit, Choreographer.FrameCallback>()

    override fun post(callback: (Long) -> Unit) {
        choreographer.postFrameCallback(wrapped.getOrPut(callback) { Choreographer.FrameCallback(callback) })
    }

    override fun remove(callback: (Long) -> Unit) {
        wrapped[callback]?.let(choreographer::removeFrameCallback)
    }
}

/**
 * One component's motion clock (T-009, 13a-0): at most one frame callback registered at a time, however often it is
 * woken; each frame runs [step] with the frame's time, which says whether anything still moves. Nothing moving, it
 * registers nothing: a component at rest costs no callback and no frame. [dispose] cancels what is registered.
 */
class MotionTicker(private val source: FrameSource, private val step: (frameTimeNanos: Long) -> Boolean) {
    private var posted = false
    private var disposed = false

    /** Callbacks registered so far (tests: proves coalescing and that a settled clock stops). */
    var registrations = 0
        private set

    /** True while a frame callback is registered. */
    val running get() = posted

    private val frame: (Long) -> Unit = { nanos ->
        posted = false
        if (!disposed && step(nanos)) wake()
    }

    /** Asks for the next frame, once, whatever the number of calls before it comes. */
    fun wake() {
        if (posted || disposed) return
        posted = true
        registrations++
        source.post(frame)
    }

    fun dispose() {
        disposed = true
        if (posted) source.remove(frame)
        posted = false
    }
}
