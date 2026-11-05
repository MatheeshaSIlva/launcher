package dev.launcher.app.layout

/**
 * The main elements the user arranges (docs/PLAN_LAYOUTS_THEMES.md). Each has layouts to choose from, places it can live
 * and options; which ones are chosen is the user's [Setup]. Layouts are independent of the theme (how things look) and of
 * the animations (how they move): a theme never chooses a layout.
 */
enum class Element(val id: String, val title: String) {
    STATUS_BAR("status-bar", "Status bar"),
    SHADE("shade", "Notifications and controls"),
    RECENTS("recents", "Recent apps"),
    DRAWER("drawer", "App drawer"),
    HOME("home", "Home screen"),
    /** The launcher's own settings app: themed and laid out like the rest. */
    SETTINGS("settings", "Settings");

    companion object {
        fun of(id: String?): Element? = entries.firstOrNull { it.id == id }
    }
}
