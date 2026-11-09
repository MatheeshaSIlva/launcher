package dev.launcher.engine.shell

/**
 * Our status bar waits for the stock one to leave (T-001; owner, 2026-11-09: "they overlap each other for a brief moment").
 * Android's bar leaves on its own fade after the flags reach it, so ours comes [STOCK_EXIT_MS] after they did. It always
 * comes: a call that failed releases at once, and one that never returns is outlived by [GIVE_UP_MS]. Main thread only.
 */
class StockHandover(private val post: (delayMs: Long, run: () -> Unit) -> Unit, private val release: () -> Unit) {
    private var attempt = 0L
    /** The bar is being held back for this handover. */
    var holding = false
        private set

    fun begin() {
        val mine = ++attempt
        holding = true
        post(GIVE_UP_MS) { end(mine) }
    }

    /** The flags' call has returned: [ok] false, the stock bar is not leaving, and nothing is gained by waiting. */
    fun flagsSent(ok: Boolean) {
        val mine = attempt
        if (!holding) return
        if (ok) post(STOCK_EXIT_MS) { end(mine) } else end(mine)
    }

    /** The bar is going away, or must show now (a panel is pulled over it). */
    fun cancel() = end(attempt)

    private fun end(which: Long) {
        if (which != attempt || !holding) return
        holding = false
        release()
    }

    companion object {
        /** The bar's pixels: a wait holds back only them, never what [showing] gives the shade (the touches on the bar). */
        fun drawn(showing: Boolean, waiting: Boolean, panelOpen: Boolean) = showing && (!waiting || panelOpen)

        /** The stock bar's whole exit took 175 ms on the S24 (recording, 2026-11-09); not a theme's motion: it is Android's. */
        const val STOCK_EXIT_MS = 200L
        const val GIVE_UP_MS = 2_000L
    }
}
