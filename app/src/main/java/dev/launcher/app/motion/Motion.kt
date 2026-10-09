package dev.launcher.app.motion

import dev.launcher.app.Spring
import dev.launcher.app.design.Design
import dev.launcher.app.design.SpringKey
import kotlin.math.abs
import kotlin.math.sign

/** A spring by its feel, as in SwiftUI: [response] = period in seconds, [damping] = fraction (1 = no overshoot). */
data class SpringSpec(val response: Float, val damping: Float) {
    fun spring() = Spring(response, damping)
}

/**
 * Every animation of the launcher by its role (step 4 of docs/DESIGN_SYSTEM_PLAN.md): read from the theme's `motion.*`
 * tokens ([MotionTokens]), so a theme (or the token editor) retimes any of them. Code asks for a role, never for numbers.
 * Spring values are read when an animation starts, so an edit applies to the next one.
 */
class MotionProfile internal constructor() {
    val name get() = Design.themeName
    val appOpen: SpringSpec get() = spring(MotionTokens.APP_OPEN)
    val appClosePosition: SpringSpec get() = spring(MotionTokens.APP_CLOSE_POSITION)
    val appCloseSize: SpringSpec get() = spring(MotionTokens.APP_CLOSE_SIZE)
    val appCancel: SpringSpec get() = spring(MotionTokens.APP_CANCEL)
    val homeDepthOpen: SpringSpec get() = spring(MotionTokens.HOME_DEPTH_OPEN)
    val homeDepthClose: SpringSpec get() = spring(MotionTokens.HOME_DEPTH_CLOSE)
    val switchCommit: SpringSpec get() = spring(MotionTokens.SWITCH_COMMIT)
    val switchCancel: SpringSpec get() = spring(MotionTokens.SWITCH_CANCEL)
    val pageSnap: SpringSpec get() = spring(MotionTokens.PAGE_SNAP)
    val drawer: SpringSpec get() = spring(MotionTokens.DRAWER)
    val folderOpen: SpringSpec get() = spring(MotionTokens.FOLDER_OPEN)
    val folderClose: SpringSpec get() = spring(MotionTokens.FOLDER_CLOSE)
    val modeCrossfadeMs: Long get() = Design.num(MotionTokens.MODE_CROSSFADE_MS).toLong()
    val indexScroll: SpringSpec get() = spring(MotionTokens.INDEX_SCROLL)
    val indexBubbleIn: SpringSpec get() = spring(MotionTokens.INDEX_BUBBLE_IN)
    val indexBubbleOut: SpringSpec get() = spring(MotionTokens.INDEX_BUBBLE_OUT)
    val indexFollow: SpringSpec get() = spring(MotionTokens.INDEX_FOLLOW)
    val overscrollReturn: SpringSpec get() = spring(MotionTokens.OVERSCROLL_RETURN)
    val rubberBand: Float get() = Design.num(MotionTokens.RUBBER_BAND)
    val decelerationRate: Float get() = Design.num(MotionTokens.DECELERATION_RATE)
    val pageFlingDp: Float get() = Design.num(MotionTokens.PAGE_FLING_DP)
    val iconPressDim: Float get() = Design.num(MotionTokens.ICON_PRESS_DIM)
    val iconPressInMs: Long get() = Design.num(MotionTokens.ICON_PRESS_IN_MS).toLong()
    val iconPressOutMs: Long get() = Design.num(MotionTokens.ICON_PRESS_OUT_MS).toLong()
    val homeContentZoom: Float get() = Design.num(MotionTokens.HOME_CONTENT_ZOOM)
    val homeWallpaperZoom: Float get() = Design.num(MotionTokens.HOME_WALLPAPER_ZOOM)
    val homeDepthBlur: Float get() = Design.num(MotionTokens.HOME_DEPTH_BLUR)
    val menuOpen: SpringSpec get() = spring(MotionTokens.MENU_OPEN)
    val menuClose: SpringSpec get() = spring(MotionTokens.MENU_CLOSE)
    val menuBlur: Float get() = Design.num(MotionTokens.MENU_BLUR)
    val reflow: SpringSpec get() = spring(MotionTokens.REFLOW)
    val dragLift: SpringSpec get() = spring(MotionTokens.DRAG_LIFT)
    val dragSettle: SpringSpec get() = spring(MotionTokens.DRAG_SETTLE)
    val jiggleDegrees: Float get() = Design.num(MotionTokens.JIGGLE_DEGREES)
    val jigglePeriod: Float get() = Design.num(MotionTokens.JIGGLE_PERIOD)
    val sheet: SpringSpec get() = spring(MotionTokens.SHEET)
    val navPush: SpringSpec get() = spring(MotionTokens.NAV_PUSH)
    val widgetResize: SpringSpec get() = spring(MotionTokens.WIDGET_RESIZE)
    val appear: SpringSpec get() = spring(MotionTokens.APPEAR)
    val appearMs: Long get() = Design.num(MotionTokens.APPEAR_MS).toLong()
    val disappearMs: Long get() = Design.num(MotionTokens.DISAPPEAR_MS).toLong()
    val editBar: SpringSpec get() = spring(MotionTokens.EDIT_BAR)
    val arrival: SpringSpec get() = spring(MotionTokens.ARRIVAL)
    val arrivalWallpaper: SpringSpec get() = spring(MotionTokens.ARRIVAL_WALLPAPER)
    val arrivalZoom: Float get() = Design.num(MotionTokens.ARRIVAL_ZOOM)
    val arrivalWallpaperZoom: Float get() = Design.num(MotionTokens.ARRIVAL_WALLPAPER_ZOOM)
    val clockTickMs: Long get() = Design.num(MotionTokens.CLOCK_TICK_MS).toLong()
    val switcher = SwitcherProfile()

    private fun spring(k: SpringKey): SpringSpec = Design.spring(k).let { SpringSpec(it.response, it.damping) }
}

/**
 * The App Switcher (hold during a home swipe): the deck's geometry and motion. Geometry as fractions of the screen, from
 * Apple's illustration of the iOS 27 App Switcher.
 */
class SwitcherProfile internal constructor() {
    val cardScale: Float get() = Design.num(MotionTokens.SWITCHER_CARD_SCALE)
    val cardCenterY: Float get() = Design.num(MotionTokens.SWITCHER_CARD_CENTER_Y)
    val focusLeft: Float get() = Design.num(MotionTokens.SWITCHER_FOCUS_LEFT)
    val holdMs: Long get() = Design.num(MotionTokens.SWITCHER_HOLD_MS).toLong()
    val holdMinTravelDp: Float get() = Design.num(MotionTokens.SWITCHER_HOLD_MIN_TRAVEL_DP)
    val enter: SpringSpec get() = spring(MotionTokens.SWITCHER_ENTER)
    val scroll: SpringSpec get() = spring(MotionTokens.SWITCHER_SCROLL)
    val flingProjection: Float get() = Design.num(MotionTokens.SWITCHER_FLING_PROJECTION)
    val open: SpringSpec get() = spring(MotionTokens.SWITCHER_OPEN)
    val home: SpringSpec get() = spring(MotionTokens.SWITCHER_HOME)
    val flick: SpringSpec get() = spring(MotionTokens.SWITCHER_FLICK)
    val flickSpeedDp: Float get() = Design.num(MotionTokens.SWITCHER_FLICK_SPEED_DP)
    val reflow: SpringSpec get() = spring(MotionTokens.SWITCHER_REFLOW)

    private fun spring(k: SpringKey): SpringSpec = Design.spring(k).let { SpringSpec(it.response, it.damping) }
}

object Motion {
    /**
     * Slow motion for checking animations frame by frame (`motion.debug.slow`, 1 = normal, as iOS's Slow Animations): every
     * spring's period is stretched by it. 1 when the theme cannot be read (unit tests).
     */
    fun slow(): Float = try { Design.num(MotionTokens.DEBUG_SLOW).coerceIn(1f, 20f) } catch (_: Throwable) { 1f }

    /** A role's spring from the theme ([MotionTokens]); read when an animation starts. */
    fun role(k: SpringKey): SpringSpec = Design.spring(k).let { SpringSpec(it.response, it.damping) }

    @Volatile var profile: MotionProfile = MotionProfile()

    /**
     * UIScrollView's rubber band: how far content moves when dragged [overshoot] px past its end, in a viewport of
     * [dimension] px. Starts 1:1-ish and approaches [dimension] asymptotically.
     */
    fun rubberBand(overshoot: Float, dimension: Float, c: Float = profile.rubberBand): Float {
        if (dimension <= 0f) return 0f
        val x = abs(overshoot)
        return sign(overshoot) * (1f - 1f / (x * c / dimension + 1f)) * dimension
    }
}
