package dev.launcher.app.shade.nc

/**
 * Where the lock clock is while it drops into place on a commit (B-037 step 2, slice 2b): the numerals enter [dropPx] above
 * their place and fall to it; the date rides the same offset and comes in on its own curve. Pure, so the schedule is tested.
 */
object ClockDrop {
    /** The clock's offset from its place (px, negative: above it) when [enter] of its way is still to go (1: just entered, 0: in place). */
    fun offset(enter: Float, dropPx: Float): Float = -dropPx * enter

    /** The date's opacity: its own arrival [dateIn] (0..1) and the clock's presence [presence] (a long list fades it). */
    fun dateAlpha(dateIn: Float, presence: Float): Float = dateIn.coerceIn(0f, 1f) * presence.coerceIn(0f, 1f)
}
