package dev.launcher.app.shade

/**
 * Which app is behind the shade, for its picture (the panels' glass, Notification Center's sheet): the app a card is bringing,
 * else the last window that came to the front, unless that was a system window (SystemUI's status bar or shade, the
 * system's own), which has no task: then the app in front as gesture nav counts it. A pull over Clock once asked for
 * SystemUI's task, found none, and Notification Center's notifications showed the wallpaper behind their glass. Pure, so
 * it is tested.
 */
object AppBehind {
    fun of(bringing: String?, lastFront: String?, frontNow: String?, own: String): String? {
        if (bringing != null) return bringing
        if (lastFront != null && lastFront !in SYSTEM) return lastFront
        return frontNow?.takeIf { it != own && it !in SYSTEM } ?: lastFront
    }

    private val SYSTEM = setOf("com.android.systemui", "android")
}
