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
    /** Text on glass straight on the wallpaper: dark from this brightness behind the tinted glass, fully dark at the second. */
    val GLASS_LABEL_DARK_FROM = NumberKey("sys.glass.label.dark-from")
    val GLASS_LABEL_DARK_FULL = NumberKey("sys.glass.label.dark-full")

    val COLORS = listOf(LABEL, SECONDARY_LABEL, TERTIARY_LABEL, SEPARATOR, PRESS, DESTRUCTIVE, GLASS_TINT).map { it.name }
    val NUMBERS = listOf(WALLPAPER_DIM, GLASS_LABEL_DARK_FROM, GLASS_LABEL_DARK_FULL).map { it.name }
}
