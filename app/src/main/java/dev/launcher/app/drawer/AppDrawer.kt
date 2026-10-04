package dev.launcher.app.drawer

import android.content.Context
import android.graphics.RectF
import android.view.View
import dev.launcher.app.apps.AppEntry
import dev.launcher.app.home.DrawerPlacement
import dev.launcher.app.home.DrawerStyle
import dev.launcher.app.home.HomeMetrics

/** What a drawer may ask of the home screen that hosts it (whatever its placement). */
interface DrawerHost {
    val metrics: HomeMetrics
    val placement: DrawerPlacement
    /** Open [e]; [iconOnScreen] is where its icon is drawn now (the launch card grows out of it). */
    fun launch(e: AppEntry, iconOnScreen: RectF)
    /** The drawer's content came to rest (scroll, mode or folder change): icon positions may have changed. */
    fun onDrawerSettled()

    /** Icon positions changed (or which copy of an icon a card belongs to): tell gesture nav, without recording anything. */
    fun onIconsMoved()

    /** [e] was long-pressed at [iconOnScreen]: its menu, and a drag onto home if the finger moves on. */
    fun onAppLongPress(e: AppEntry, iconOnScreen: RectF)
}

/**
 * An app drawer style. The host owns the placement (a page beside the home pages, or a sheet from the bottom), moves the
 * drawer's view and its background, and reports how far it is open; the drawer owns its content and its own gestures.
 */
interface AppDrawer {
    val view: View

    /** 0 = closed, 1 = fully open; follows the finger or the host's spring. */
    fun setOpenProgress(p: Float)

    /** Fully closed: back to the initial state (search left, folders closed, scrolled to the top). */
    fun onClosed()

    /** True while the drawer needs every touch itself (search, an open folder): the host neither pages nor closes it. */
    fun capturesGestures(): Boolean

    /** True if a pull-down would scroll the content back (so a swipe-up drawer must not close on it). */
    fun canScrollBack(): Boolean

    /** Back pressed while open: true if the drawer handled it (left search, closed a folder). */
    fun onBack(): Boolean

    /** Package -> icon square on screen, for every icon a closing app's card could fly into right now. */
    fun visibleIcons(out: MutableMap<String, RectF>)

    /** Hide the icon a card is flying into or out of ([pkg] null = show all). */
    fun setHiddenPkg(pkg: String?)

    /** Nothing is moving (safe to record the picture of home). */
    val isIdle: Boolean

    /** The installed apps (or their usage) changed. */
    fun appsChanged()

    /** Height of the on-screen keyboard (0 when hidden), so lists end above it. */
    fun setImeInset(px: Int)

    /** Our copy of the wallpaper (null: the system draws it): glass surfaces refract what is behind them. */
    fun setWallpaper(w: dev.launcher.app.Wallpaper?)

    /** Open with search focused and the keyboard up (the home Search pill). */
    fun openSearch()

    /** Leaves search at once (an app opened from it is in front now): back to the tiles, keyboard down. True if it was searching. */
    fun endSearchNow(): Boolean

    /** Keyboard down at once (the drawer is being left; search itself ends in [onClosed]). */
    fun hideKeyboard()

    /** Closes what lies on top of the drawer (an open folder), animated. True if there was something to close. */
    fun closeTop(): Boolean
}

/** Where [v]'s top-left corner is on screen from layout positions and translations (scale ignored), cheap enough per frame. */
internal fun screenOffset(v: View, out: FloatArray): FloatArray {
    var x = 0f
    var y = 0f
    var cur: View? = v
    while (cur != null) {
        x += cur.left + cur.translationX
        y += cur.top + cur.translationY
        cur = cur.parent as? View
    }
    out[0] = x; out[1] = y
    return out
}

object Drawers {
    fun create(style: DrawerStyle, ctx: Context, host: DrawerHost): AppDrawer = when (style) {
        DrawerStyle.APP_LIBRARY -> AppLibraryView(ctx, host)
    }
}
