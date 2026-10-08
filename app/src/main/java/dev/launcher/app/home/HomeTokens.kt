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

    val ALL = listOf(DOCK, SEARCH, WIDGET, BUTTON).map { it.name }
}
