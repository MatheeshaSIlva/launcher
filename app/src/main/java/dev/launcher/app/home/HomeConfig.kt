package dev.launcher.app.home

import android.content.Context
import dev.launcher.app.apps.IconShape
import dev.launcher.app.layout.Element
import dev.launcher.app.layout.Setup

/** Where the app drawer comes from. Any [DrawerStyle] works in any placement. [id]: its id in the [dev.launcher.app.layout.Setup]. */
enum class DrawerPlacement(val id: String, val title: String) {
    /** iOS: a page after the last home page (swipe left past it). */
    PAGE_AFTER_LAST("page-after-last", "Page after the last (iOS)"),
    /** A page before the first home page (swipe right from it). */
    PAGE_BEFORE_FIRST("page-before-first", "Page before the first"),
    /** A sheet that follows a swipe up from anywhere on home. */
    SWIPE_UP("swipe-up", "Swipe up");

    val isPage get() = this != SWIPE_UP

    companion object {
        fun of(id: String?): DrawerPlacement = entries.firstOrNull { it.id == id } ?: PAGE_AFTER_LAST
    }
}

/**
 * What the drawer shows. More styles (a plain grid, a list) plug in through [dev.launcher.app.drawer.AppDrawer]. [id]: its
 * layout id in the [dev.launcher.app.layout.Setup] (`dev.launcher.app.layout.Layouts`).
 */
enum class DrawerStyle(val id: String, val title: String) {
    APP_LIBRARY("app-library", "App Library (iOS)");

    companion object {
        fun of(id: String?): DrawerStyle = entries.firstOrNull { it.id == id } ?: APP_LIBRARY
    }
}

/**
 * The home screen's layout settings, as home reads them. Defaults are iOS. The drawer's style and placement and the home
 * options are kept in the user's [dev.launcher.app.layout.Setup] (the home and drawer elements); the rest is fixed for
 * now (the home layout round, D5, makes them options).
 */
data class HomeConfig(
    val columns: Int = 4,
    val rows: Int = 6,
    val dockSlots: Int = 4,
    val drawerStyle: DrawerStyle = DrawerStyle.APP_LIBRARY,
    val drawerPlacement: DrawerPlacement = DrawerPlacement.PAGE_AFTER_LAST,
    /** System (icons as Android draws them) until a shape is picked in settings. */
    val iconShape: IconShape = IconShape.SYSTEM,
    /** App names under the icons on the pages (off = iOS 18's "large icons" look). */
    val showLabels: Boolean = true,
    /** Widget names under the widgets. */
    val showWidgetLabels: Boolean = true,
    /** iOS "Add to Home Screen" for newly installed apps (else they only appear in the drawer). */
    val newAppsOnHome: Boolean = true,
) {
    /** Into the user's setup (saved there). */
    @Suppress("UNUSED_PARAMETER")
    fun save(ctx: Context) {
        Setup.set(Element.DRAWER, layout = drawerStyle.id, placement = drawerPlacement.id)
        Setup.set(Element.HOME, options = mapOf(
            "show-labels" to showLabels,
            "show-widget-labels" to showWidgetLabels,
            "new-apps-on-home" to newAppsOnHome,
        ))
    }

    companion object {
        /** From the user's setup. */
        @Suppress("UNUSED_PARAMETER")
        fun load(ctx: Context): HomeConfig = HomeConfig(
            drawerPlacement = DrawerPlacement.of(Setup.placement(Element.DRAWER).id),
            drawerStyle = DrawerStyle.of(Setup.layout(Element.DRAWER).id),
            newAppsOnHome = Setup.bool(Element.HOME, "new-apps-on-home", true),
            showLabels = Setup.bool(Element.HOME, "show-labels", true),
            showWidgetLabels = Setup.bool(Element.HOME, "show-widget-labels", true),
        )
    }
}
