package dev.launcher.app.home

import android.content.Context
import dev.launcher.app.apps.IconShape

/** Where the app drawer comes from. Any [DrawerStyle] works in any placement. */
enum class DrawerPlacement(val title: String) {
    /** iOS: a page after the last home page (swipe left past it). */
    PAGE_AFTER_LAST("Page after the last (iOS)"),
    /** A page before the first home page (swipe right from it). */
    PAGE_BEFORE_FIRST("Page before the first"),
    /** A sheet that follows a swipe up from anywhere on home. */
    SWIPE_UP("Swipe up");

    val isPage get() = this != SWIPE_UP
}

/** What the drawer shows. More styles (a plain grid, a list) plug in through [dev.launcher.app.drawer.AppDrawer]. */
enum class DrawerStyle(val title: String) {
    APP_LIBRARY("App Library (iOS)"),
}

/**
 * The home screen's layout settings. Defaults are iOS; a theme's layout layer (and advanced settings) will set these.
 * Stored in private preferences.
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
    fun save(ctx: Context) {
        prefs(ctx).edit()
            .putString("drawer_placement", drawerPlacement.name)
            .putString("drawer_style", drawerStyle.name)
            .putBoolean("new_apps_on_home", newAppsOnHome)
            .putBoolean("show_labels", showLabels)
            .putBoolean("show_widget_labels", showWidgetLabels)
            .apply()
    }

    companion object {
        private fun prefs(ctx: Context) = ctx.getSharedPreferences("home_config", Context.MODE_PRIVATE)

        fun load(ctx: Context): HomeConfig {
            val p = prefs(ctx)
            return HomeConfig(
                drawerPlacement = p.getString("drawer_placement", null)?.let { n -> DrawerPlacement.entries.firstOrNull { it.name == n } }
                    ?: DrawerPlacement.PAGE_AFTER_LAST,
                drawerStyle = p.getString("drawer_style", null)?.let { n -> DrawerStyle.entries.firstOrNull { it.name == n } }
                    ?: DrawerStyle.APP_LIBRARY,
                newAppsOnHome = p.getBoolean("new_apps_on_home", true),
                showLabels = p.getBoolean("show_labels", true),
                showWidgetLabels = p.getBoolean("show_widget_labels", true),
            )
        }
    }
}
