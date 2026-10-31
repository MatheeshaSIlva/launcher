package dev.launcher.app.shade.nc

/**
 * The buffer sets of [NcLiveBehind]'s streams, and when each may go (plain Kotlin, no Android types). A stream started
 * again (paused at the commit, back for the close) takes the [current] set up again when it fits; a set of another size
 * replaces it, and the one replaced goes only once the new stream's first frame is with the bands ([delivered]): until then
 * they still draw one of its buffers. Every set made goes exactly once, at the latest at [stop]: a stream started again once
 * made a new set and dropped the one before unclosed (3 GPU buffers each time; code review, 2026-11-01).
 * [adopt], [delivered] and [stop] on one thread (the shade's); [current] read from any.
 */
class BufferSets<T : Any>(private val close: (T) -> Unit) {
    @Volatile var current: T? = null
        private set
    private var retired: T? = null

    /** [made] is the set the bands are handed from now. */
    fun adopt(made: T) {
        val old = current
        current = made
        if (old != null && old !== made) {
            retired?.let(close)
            retired = old
        }
    }

    /** The bands drew a frame of the [current] set: a set it replaced can go. */
    fun delivered() {
        retired?.let { retired = null; close(it) }
    }

    /** Every set goes. */
    fun stop() {
        current?.let(close)
        retired?.let(close)
        current = null
        retired = null
    }
}
