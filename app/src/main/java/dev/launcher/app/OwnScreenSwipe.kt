package dev.launcher.app

/**
 * The home gesture over one of our own screens that shows as a panel over home (the settings): the panel follows the
 * finger up and closes into home, as Back on the settings' start page does, instead of shrinking into a card as an app's
 * window does (Matheesha: "home gesture in the settings panel should just close it instead of behaving like a normal app
 * close"). Like iOS's panels over home (Notification Center), a hold does not open the App Switcher from it.
 *
 * Gesture nav (its own thread) hands a swipe up to [panel] when one is set as it starts; the calls arrive on the main thread.
 */
object OwnScreenSwipe {
    interface Panel {
        /** The finger started a swipe up over it. Main thread. */
        fun begin()
        /** How far up the finger has gone since (px, 0 or more). Main thread. */
        fun move(travelPx: Float)
        /** The finger lifted: [commit] closes into home, else back to rest; [speedPx] its speed upward (px/s). Main thread. */
        fun end(commit: Boolean, speedPx: Float)
    }

    /** The panel in front that takes swipes now: set while it can (shown, not already closing). Any thread. */
    @Volatile var panel: Panel? = null
}
