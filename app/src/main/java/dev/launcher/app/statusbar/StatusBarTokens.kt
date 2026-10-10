package dev.launcher.app.statusbar

import dev.launcher.app.design.ColorKey

/** The status bar's tokens (`comp.statusbar.*`). */
object StatusBarTokens {
    /** The battery's level below 20 %, in Low Power Mode, while charging (iOS's system colours). */
    val BATTERY_LOW = ColorKey("comp.statusbar.battery-low-color")
    val BATTERY_SAVER = ColorKey("comp.statusbar.battery-saver-color")
    val BATTERY_CHARGING = ColorKey("comp.statusbar.battery-charging-color")

    val ALL = listOf(BATTERY_LOW, BATTERY_SAVER, BATTERY_CHARGING).map { it.name }
}
