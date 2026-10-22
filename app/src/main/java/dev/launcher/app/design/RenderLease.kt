package dev.launcher.app.design

/**
 * Lets a renderer go only when both the thread that issued its draw is back from it ([issued]) and the draw's callback is
 * done with the result ([completed]): [release] runs once, on the thread of whichever came last. B-031: the callback closed
 * a `HardwareBufferRenderer` while the issuing thread was still inside `RenderRequest.draw`, waiting on a mutex that closing
 * deleted. Plain Kotlin, no Android types.
 */
class RenderLease(private val release: () -> Unit) {
    private var issued = false
    private var completed = false
    @Volatile var released = false
        private set

    fun issued() = arrive { issued = true }

    fun completed() = arrive { completed = true }

    private fun arrive(mark: () -> Unit) {
        val last = synchronized(this) {
            mark()
            if (issued && completed && !released) { released = true; true } else false
        }
        if (last) release()
    }
}
