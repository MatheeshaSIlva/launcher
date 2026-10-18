package dev.launcher.app.statusbar

import dev.launcher.app.design.ColorKey

/** The status bar's tokens (`comp.statusbar.*`). */
object StatusBarTokens {
    /** The battery's level below 20 %, in Low Power Mode, while charging (iOS's system colours). */
    val BATTERY_LOW = ColorKey("comp.statusbar.battery-low-color")
    val BATTERY_SAVER = ColorKey("comp.statusbar.battery-saver-color")
    val BATTERY_CHARGING = ColorKey("comp.statusbar.battery-charging-color")
    /** The charging moment's capsule and what is written on it (over One UI's own charging animation, opaque). */
    val CAPSULE = ColorKey("comp.statusbar.capsule-color")
    val CAPSULE_LABEL = ColorKey("comp.statusbar.capsule-label-color")
    /** The capsule the time sits in while the screen is shared or cast (iOS's screen-recording red). */
    val SHARING = ColorKey("comp.statusbar.sharing-color")

    val ALL = listOf(BATTERY_LOW, BATTERY_SAVER, BATTERY_CHARGING, CAPSULE, CAPSULE_LABEL, SHARING).map { it.name }
}
