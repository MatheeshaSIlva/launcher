package dev.launcher.app.switcher

import dev.launcher.app.design.ColorKey
import dev.launcher.app.design.MaterialKey
import dev.launcher.app.design.NumberKey
import dev.launcher.app.design.TextKey

/**
 * The App Switcher's tokens (`comp.switcher.*`): its Clear All button (its app menu is [dev.launcher.app.components.MenuSpec.SWITCHER]).
 * Not in iOS (Android's convenience): judged, on the menus' glass.
 */
object SwitcherTokens {
    val CLEAR_MATERIAL = MaterialKey("comp.switcher.clear.material")
    val CLEAR_HEIGHT = NumberKey("comp.switcher.clear.height")
    val CLEAR_PADDING_X = NumberKey("comp.switcher.clear.padding-x")
    val CLEAR_BOTTOM = NumberKey("comp.switcher.clear.bottom")
    val CLEAR_TYPE = TextKey("comp.switcher.clear.type")
    val CLEAR_LABEL = ColorKey("comp.switcher.clear.label")
    val CLEAR_PRESS = ColorKey("comp.switcher.clear.press")

    /** The switcher's layout (`sys.layout.switcher`): "deck" (iOS) or "carousel" (Android's recents). */
    val LAYOUT = dev.launcher.app.design.ChoiceKey("sys.layout.switcher")

    // Android's recents (the carousel): cards side by side, the app's chip inside each, "Clear all" after the oldest.
    val CAROUSEL_GAP = NumberKey("comp.switcher.carousel.gap")
    val CAROUSEL_CORNER = NumberKey("comp.switcher.carousel.corner")
    val CAROUSEL_DIM = NumberKey("comp.switcher.carousel.dim")
    val CHIP_INSET = NumberKey("comp.switcher.carousel.chip.inset")
    val CHIP_HEIGHT = NumberKey("comp.switcher.carousel.chip.height")
    val CHIP_MIN_WIDTH = NumberKey("comp.switcher.carousel.chip.min-width")
    val CHIP_ICON = NumberKey("comp.switcher.carousel.chip.icon")
    val CHIP_FILL = ColorKey("comp.switcher.carousel.chip.fill")
    val CHIP_TEXT = TextKey("comp.switcher.carousel.chip.type")
    val CHIP_TEXT_COLOR = ColorKey("comp.switcher.carousel.chip.label")
    val CAROUSEL_CLEAR_HEIGHT = NumberKey("comp.switcher.carousel.clear.height")
    val CAROUSEL_CLEAR_PADDING = NumberKey("comp.switcher.carousel.clear.padding-x")
    val CAROUSEL_CLEAR_FILL = ColorKey("comp.switcher.carousel.clear.fill")
    val CAROUSEL_CLEAR_TEXT = TextKey("comp.switcher.carousel.clear.type")
    val CAROUSEL_CLEAR_LABEL = ColorKey("comp.switcher.carousel.clear.label")

    val ALL = listOf(CLEAR_MATERIAL, CLEAR_HEIGHT, CLEAR_PADDING_X, CLEAR_BOTTOM, CLEAR_TYPE, CLEAR_LABEL, CLEAR_PRESS, LAYOUT,
        CAROUSEL_GAP, CAROUSEL_CORNER, CAROUSEL_DIM, CHIP_INSET, CHIP_HEIGHT, CHIP_MIN_WIDTH, CHIP_ICON, CHIP_FILL, CHIP_TEXT,
        CHIP_TEXT_COLOR, CAROUSEL_CLEAR_HEIGHT, CAROUSEL_CLEAR_PADDING, CAROUSEL_CLEAR_FILL, CAROUSEL_CLEAR_TEXT,
        CAROUSEL_CLEAR_LABEL).map { it.name } + dev.launcher.app.components.MenuSpec.SWITCHER.all
}
