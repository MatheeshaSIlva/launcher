package dev.launcher.app.theme

import dev.launcher.app.design.ColorKey
import dev.launcher.app.design.NumberKey

/** The appearance's palette in the theme ([Appearance] reads it): text and lines on the materials, the glass's tint. */
object PaletteTokens {
    val LABEL = ColorKey("sys.color.material.label")
    val SECONDARY_LABEL = ColorKey("sys.color.material.secondary-label")
    val TERTIARY_LABEL = ColorKey("sys.color.material.tertiary-label")
    val SEPARATOR = ColorKey("sys.color.material.separator")
    val PRESS = ColorKey("sys.color.press")
    val DESTRUCTIVE = ColorKey("sys.color.material.destructive")
    val GLASS_TINT = ColorKey("sys.glass.tint")
    /** How much dark mode dims the wallpaper. */
    val WALLPAPER_DIM = NumberKey("sys.appearance.wallpaper-dim")

    val COLORS = listOf(LABEL, SECONDARY_LABEL, TERTIARY_LABEL, SEPARATOR, PRESS, DESTRUCTIVE, GLASS_TINT).map { it.name }
    val NUMBERS = listOf(WALLPAPER_DIM).map { it.name }
}
