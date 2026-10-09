package dev.launcher.app.home

import dev.launcher.app.design.MaterialKey

/**
 * Home's tokens (`comp.home.*`, step 2d of docs/DESIGN_SYSTEM_PLAN.md): what each of home's glass surfaces is made of.
 * The iOS 27 theme gives all of them the kit's dock material (`sys.material.glass.dock`); a theme may give each its own.
 */
object HomeTokens {
    /** The dock's platter (kit 558:50551). */
    val DOCK = MaterialKey("comp.home.dock.material")
    /** The Search pill above the dock, and the page dots it turns into (kit 5593:10801). */
    val SEARCH = MaterialKey("comp.home.search.material")
    /** The glass behind a widget. */
    val WIDGET = MaterialKey("comp.home.widget.material")
    /** Edit mode's Edit and Done buttons. */
    val BUTTON = MaterialKey("comp.home.button.material")

    // The long-press menu (the kit's Home Screen Quick Actions).
    val MENU = MaterialKey("comp.home.menu.material")
    val MENU_CORNER = dev.launcher.app.design.NumberKey("comp.home.menu.corner")
    val MENU_WIDTH = dev.launcher.app.design.NumberKey("comp.home.menu.width")
    val MENU_ROW = dev.launcher.app.design.NumberKey("comp.home.menu.row")
    val MENU_PAD_TOP = dev.launcher.app.design.NumberKey("comp.home.menu.pad-top")
    val MENU_PAD_BOTTOM = dev.launcher.app.design.NumberKey("comp.home.menu.pad-bottom")
    val MENU_SYMBOL_X = dev.launcher.app.design.NumberKey("comp.home.menu.symbol-x")
    val MENU_LABEL_X = dev.launcher.app.design.NumberKey("comp.home.menu.label-x")
    val MENU_TYPE = dev.launcher.app.design.TextKey("comp.home.menu.type")
    val MENU_LABEL = dev.launcher.app.design.ColorKey("comp.home.menu.label")
    val MENU_DESTRUCTIVE = dev.launcher.app.design.ColorKey("comp.home.menu.destructive")
    val MENU_PRESS = dev.launcher.app.design.ColorKey("comp.home.menu.press")

    /** Search fields (App Library, Spotlight, the widget gallery), over the content scrolling under them. */
    val FIELD = MaterialKey("comp.home.field.material")
    /** The App Library's tiles, folder panel and search bar; Spotlight's card. */
    val LIBRARY_TILE = MaterialKey("comp.library.tile.material")
    val SPOTLIGHT_CARD = MaterialKey("comp.spotlight.card.material")
    /** The widget gallery: its sheet (over home), its buttons and its widgets' cards. */
    val WIDGETS_SHEET = MaterialKey("comp.widgets.sheet.material")
    val WIDGETS_BUTTON = MaterialKey("comp.widgets.button.material")
    val WIDGETS_CARD = MaterialKey("comp.widgets.card.material")

    /** The widget gallery's sheet without the glass renderer, and its grabber. */
    val WIDGETS_FALLBACK = dev.launcher.app.design.ColorKey("comp.widgets.fallback-color")
    val WIDGETS_GRABBER = dev.launcher.app.design.ColorKey("comp.widgets.grabber-color")
    /** A search field's clear button and its cross. */
    val FIELD_CLEAR = dev.launcher.app.design.ColorKey("comp.home.field.clear-color")
    val FIELD_CLEAR_SYMBOL = dev.launcher.app.design.ColorKey("comp.home.field.clear-symbol-color")

    /** Names under icons and widgets: white, or dark over a bright wallpaper (see [LabelTone]). */
    val LABEL_LIGHT = dev.launcher.app.design.ColorKey("comp.home.label.light")
    val LABEL_DARK = dev.launcher.app.design.ColorKey("comp.home.label.dark")
    val LABEL_SHADOW = dev.launcher.app.design.ColorKey("comp.home.label.shadow")
    val LABEL_DARK_FROM = dev.launcher.app.design.NumberKey("comp.home.label.dark-from")
    val LABEL_DARK_FULL = dev.launcher.app.design.NumberKey("comp.home.label.dark-full")
    /** The glass clock's tint, light or dark by the wallpaper under it ([LabelTone.clockTint]). */
    val CLOCK_TINT_LIGHT = dev.launcher.app.design.ColorKey("comp.home.clock.tint-light")
    val CLOCK_TINT_DARK = dev.launcher.app.design.ColorKey("comp.home.clock.tint-dark")

    val ALL = listOf(DOCK, SEARCH, WIDGET, BUTTON, FIELD, LIBRARY_TILE, SPOTLIGHT_CARD, WIDGETS_SHEET, WIDGETS_BUTTON, WIDGETS_CARD, MENU, MENU_CORNER, MENU_WIDTH, MENU_ROW, MENU_PAD_TOP, MENU_PAD_BOTTOM, MENU_SYMBOL_X,
        MENU_LABEL_X, MENU_TYPE, MENU_LABEL, MENU_DESTRUCTIVE, MENU_PRESS, LABEL_LIGHT, LABEL_DARK, LABEL_SHADOW, LABEL_DARK_FROM, LABEL_DARK_FULL,
        CLOCK_TINT_LIGHT, CLOCK_TINT_DARK, WIDGETS_FALLBACK, WIDGETS_GRABBER, FIELD_CLEAR, FIELD_CLEAR_SYMBOL).map { it.name }
}

/**
 * How home's names over the wallpaper look (Android's convenience on iOS's look): white with a soft shadow, as iOS, turning
 * dark over a bright wallpaper, as Android launchers do (white names vanished over a light one). [of] is the blend for a
 * wallpaper luminance under the name, dark mode's dim counted; the views move to a new one on a spring. The glass clock
 * (its date and numerals) takes the same tone ([clockTint]).
 */
object LabelTone {
    fun of(wallpaperLum: Float): Float {
        val l = wallpaperLum * (1f - dev.launcher.app.theme.Appearance.wallpaperDim)
        val a = dev.launcher.app.design.Design.num(HomeTokens.LABEL_DARK_FROM)
        val b = dev.launcher.app.design.Design.num(HomeTokens.LABEL_DARK_FULL)
        val t = ((l - a) / (b - a).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun color(tone: Float): Int =
        mix(dev.launcher.app.design.Design.color(HomeTokens.LABEL_LIGHT), dev.launcher.app.design.Design.color(HomeTokens.LABEL_DARK), tone)

    /** The glass clock's tint (ARGB, alpha = amount) for [tone]: light glass, or dark glass over a bright wallpaper. */
    fun clockTint(tone: Float): Int =
        mix(dev.launcher.app.design.Design.color(HomeTokens.CLOCK_TINT_LIGHT), dev.launcher.app.design.Design.color(HomeTokens.CLOCK_TINT_DARK), tone)

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = ((((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t) + 0.5f).toInt().coerceIn(0, 255) shl shift
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    /** The shadow under a name of [tone]: the soft dark one under white names, none under dark ones. */
    fun shadow(tone: Float): Int {
        val s = dev.launcher.app.design.Design.color(HomeTokens.LABEL_SHADOW)
        val a = (((s ushr 24) and 0xFF) * (1f - tone)).toInt().coerceIn(0, 255)
        return (a shl 24) or (s and 0xFFFFFF)
    }
}

/** A view drawing a name over the wallpaper: home sets its tone (see [LabelTone]). */
interface TonedLabel {
    /** The name's area in the view (px). */
    fun labelArea(out: android.graphics.RectF)
    fun setLabelTone(tone: Float, animate: Boolean)
}
