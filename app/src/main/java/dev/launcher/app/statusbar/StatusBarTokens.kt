package dev.launcher.app.statusbar

import dev.launcher.app.design.ColorKey

/** The status bar's tokens (`comp.statusbar.*`). */
object StatusBarTokens {
    /** The battery's level below 20 %, in Low Power Mode, while charging (iOS's system colours). */
    val BATTERY_LOW = ColorKey("comp.statusbar.battery-low-color")
    val BATTERY_SAVER = ColorKey("comp.statusbar.battery-saver-color")
    val BATTERY_CHARGING = ColorKey("comp.statusbar.battery-charging-color")

    /** The bar's layout (`sys.layout.statusbar`): "ios" or "pixel" (Android 16's, measured on the emulator's SystemUI). */
    val LAYOUT = dev.launcher.app.design.ChoiceKey("sys.layout.statusbar")
    // Android's bar (points: dp under the Pixel theme's scale).
    val PX_SIDE_START = dev.launcher.app.design.NumberKey("comp.statusbar.pixel.side-start")
    val PX_SIDE_END = dev.launcher.app.design.NumberKey("comp.statusbar.pixel.side-end")
    val PX_TIME = dev.launcher.app.design.NumberKey("comp.statusbar.pixel.time")
    val PX_ICON = dev.launcher.app.design.NumberKey("comp.statusbar.pixel.icon")
    val PX_ICON_GAP = dev.launcher.app.design.NumberKey("comp.statusbar.pixel.icon-gap")
    val PX_GAP = dev.launcher.app.design.NumberKey("comp.statusbar.pixel.gap")

    val ALL = listOf(BATTERY_LOW, BATTERY_SAVER, BATTERY_CHARGING, LAYOUT, PX_SIDE_START, PX_SIDE_END, PX_TIME, PX_ICON, PX_ICON_GAP, PX_GAP).map { it.name }
}
