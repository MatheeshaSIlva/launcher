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

    val ALL = listOf(DOCK, SEARCH, WIDGET, BUTTON, MENU, MENU_CORNER, MENU_WIDTH, MENU_ROW, MENU_PAD_TOP, MENU_PAD_BOTTOM, MENU_SYMBOL_X,
        MENU_LABEL_X, MENU_TYPE, MENU_LABEL, MENU_DESTRUCTIVE, MENU_PRESS).map { it.name }
}
