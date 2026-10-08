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

    val ALL = listOf(CLEAR_MATERIAL, CLEAR_HEIGHT, CLEAR_PADDING_X, CLEAR_BOTTOM, CLEAR_TYPE, CLEAR_LABEL, CLEAR_PRESS).map { it.name } +
        dev.launcher.app.components.MenuSpec.SWITCHER.all
}
