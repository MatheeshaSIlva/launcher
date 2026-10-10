package dev.launcher.app.motion

import dev.launcher.app.design.Curve
import dev.launcher.app.design.CurveKey
import dev.launcher.app.design.Design
import kotlin.math.abs
import kotlin.math.sign

/**
 * Every animation of the launcher by its role: read from the animation preset's `motion.*` tokens ([MotionTokens];
 * presets are a layer of their own, apart from the theme: docs/PLAN_LAYOUTS_THEMES.md, B3), so a preset (or the token
 * editor) retimes any of them, as a spring or a bezier curve ([Curve]). Code asks for a role, never for numbers. Curves
 * are read when an animation starts, so an edit applies to the next one.
 */
class MotionProfile internal constructor() {
    val name get() = Design.motionName
    val appOpen: Curve get() = spring(MotionTokens.APP_OPEN)
    val appClosePosition: Curve get() = spring(MotionTokens.APP_CLOSE_POSITION)
    val appCloseSize: Curve get() = spring(MotionTokens.APP_CLOSE_SIZE)
    val appCancel: Curve get() = spring(MotionTokens.APP_CANCEL)
    val homeDepthOpen: Curve get() = spring(MotionTokens.HOME_DEPTH_OPEN)
    val homeDepthClose: Curve get() = spring(MotionTokens.HOME_DEPTH_CLOSE)
    val switchCommit: Curve get() = spring(MotionTokens.SWITCH_COMMIT)
    val switchCancel: Curve get() = spring(MotionTokens.SWITCH_CANCEL)
    val pageSnap: Curve get() = spring(MotionTokens.PAGE_SNAP)
    val drawer: Curve get() = spring(MotionTokens.DRAWER)
    val folderOpen: Curve get() = spring(MotionTokens.FOLDER_OPEN)
    val folderClose: Curve get() = spring(MotionTokens.FOLDER_CLOSE)
    val modeCrossfadeMs: Long get() = Design.num(MotionTokens.MODE_CROSSFADE_MS).toLong()
    val indexScroll: Curve get() = spring(MotionTokens.INDEX_SCROLL)
    val indexBubbleIn: Curve get() = spring(MotionTokens.INDEX_BUBBLE_IN)
    val indexBubbleOut: Curve get() = spring(MotionTokens.INDEX_BUBBLE_OUT)
    val indexFollow: Curve get() = spring(MotionTokens.INDEX_FOLLOW)
    val overscrollReturn: Curve get() = spring(MotionTokens.OVERSCROLL_RETURN)
    val rubberBand: Float get() = Design.num(MotionTokens.RUBBER_BAND)
    val decelerationRate: Float get() = Design.num(MotionTokens.DECELERATION_RATE)
    val pageFlingDp: Float get() = Design.num(MotionTokens.PAGE_FLING_DP)
    val iconPressDim: Float get() = Design.num(MotionTokens.ICON_PRESS_DIM)
    val iconPressInMs: Long get() = Design.num(MotionTokens.ICON_PRESS_IN_MS).toLong()
    val iconPressOutMs: Long get() = Design.num(MotionTokens.ICON_PRESS_OUT_MS).toLong()
    val homeContentZoom: Float get() = Design.num(MotionTokens.HOME_CONTENT_ZOOM)
    val homeWallpaperZoom: Float get() = Design.num(MotionTokens.HOME_WALLPAPER_ZOOM)
    val homeDepthBlur: Float get() = Design.num(MotionTokens.HOME_DEPTH_BLUR)
    val menuOpen: Curve get() = spring(MotionTokens.MENU_OPEN)
    val menuClose: Curve get() = spring(MotionTokens.MENU_CLOSE)
    val menuBlur: Float get() = Design.num(MotionTokens.MENU_BLUR)
    val reflow: Curve get() = spring(MotionTokens.REFLOW)
    val dragLift: Curve get() = spring(MotionTokens.DRAG_LIFT)
    val dragSettle: Curve get() = spring(MotionTokens.DRAG_SETTLE)
    val jiggleDegrees: Float get() = Design.num(MotionTokens.JIGGLE_DEGREES)
    val jigglePeriod: Float get() = Design.num(MotionTokens.JIGGLE_PERIOD)
    val sheet: Curve get() = spring(MotionTokens.SHEET)
    val navPush: Curve get() = spring(MotionTokens.NAV_PUSH)
    val widgetResize: Curve get() = spring(MotionTokens.WIDGET_RESIZE)
    val appear: Curve get() = spring(MotionTokens.APPEAR)
    val appearMs: Long get() = Design.num(MotionTokens.APPEAR_MS).toLong()
    val disappearMs: Long get() = Design.num(MotionTokens.DISAPPEAR_MS).toLong()
    val editBar: Curve get() = spring(MotionTokens.EDIT_BAR)
    val arrival: Curve get() = spring(MotionTokens.ARRIVAL)
    val arrivalWallpaper: Curve get() = spring(MotionTokens.ARRIVAL_WALLPAPER)
    val arrivalZoom: Float get() = Design.num(MotionTokens.ARRIVAL_ZOOM)
    val arrivalWallpaperZoom: Float get() = Design.num(MotionTokens.ARRIVAL_WALLPAPER_ZOOM)
    val clockTickMs: Long get() = Design.num(MotionTokens.CLOCK_TICK_MS).toLong()
    val switcher = SwitcherProfile()

    private fun spring(k: CurveKey): Curve = Design.curve(k)
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
    val enter: Curve get() = spring(MotionTokens.SWITCHER_ENTER)
    val scroll: Curve get() = spring(MotionTokens.SWITCHER_SCROLL)
    val flingProjection: Float get() = Design.num(MotionTokens.SWITCHER_FLING_PROJECTION)
    val open: Curve get() = spring(MotionTokens.SWITCHER_OPEN)
    val home: Curve get() = spring(MotionTokens.SWITCHER_HOME)
    val flick: Curve get() = spring(MotionTokens.SWITCHER_FLICK)
    val flickSpeedDp: Float get() = Design.num(MotionTokens.SWITCHER_FLICK_SPEED_DP)
    val reflow: Curve get() = spring(MotionTokens.SWITCHER_REFLOW)

    private fun spring(k: CurveKey): Curve = Design.curve(k)
}

object Motion {
    /**
     * Slow motion for checking animations frame by frame (`motion.debug.slow`, 1 = normal, as iOS's Slow Animations): every
     * spring's period is stretched by it. 1 when the theme cannot be read (unit tests).
     */
    fun slow(): Float = try { Design.num(MotionTokens.DEBUG_SLOW).coerceIn(1f, 20f) } catch (_: Throwable) { 1f }

    /** A role's curve from the animation preset ([MotionTokens]); read when an animation starts. */
    fun role(k: CurveKey): Curve = Design.curve(k)

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
