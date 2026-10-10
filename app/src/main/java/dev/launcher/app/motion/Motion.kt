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
    val appOpen: Curve get() = curve(MotionTokens.APP_OPEN)
    val appClosePosition: Curve get() = curve(MotionTokens.APP_CLOSE_POSITION)
    val appCloseSize: Curve get() = curve(MotionTokens.APP_CLOSE_SIZE)
    val appCancel: Curve get() = curve(MotionTokens.APP_CANCEL)
    val homeDepthOpen: Curve get() = curve(MotionTokens.HOME_DEPTH_OPEN)
    val homeDepthClose: Curve get() = curve(MotionTokens.HOME_DEPTH_CLOSE)
    val switchCommit: Curve get() = curve(MotionTokens.SWITCH_COMMIT)
    val switchCancel: Curve get() = curve(MotionTokens.SWITCH_CANCEL)
    val pageSnap: Curve get() = curve(MotionTokens.PAGE_SNAP)
    val drawer: Curve get() = curve(MotionTokens.DRAWER)
    val folderOpen: Curve get() = curve(MotionTokens.FOLDER_OPEN)
    val folderClose: Curve get() = curve(MotionTokens.FOLDER_CLOSE)
    val modeCrossfade: Curve get() = curve(MotionTokens.MODE_CROSSFADE)
    val indexScroll: Curve get() = curve(MotionTokens.INDEX_SCROLL)
    val indexBubbleIn: Curve get() = curve(MotionTokens.INDEX_BUBBLE_IN)
    val indexBubbleOut: Curve get() = curve(MotionTokens.INDEX_BUBBLE_OUT)
    val indexFollow: Curve get() = curve(MotionTokens.INDEX_FOLLOW)
    val overscrollReturn: Curve get() = curve(MotionTokens.OVERSCROLL_RETURN)
    val rubberBand: Float get() = Design.num(MotionTokens.RUBBER_BAND)
    val decelerationRate: Float get() = Design.num(MotionTokens.DECELERATION_RATE)
    val pageFlingDp: Float get() = Design.num(MotionTokens.PAGE_FLING_DP)
    val iconPressDim: Float get() = Design.num(MotionTokens.ICON_PRESS_DIM)
    val iconPressIn: Curve get() = curve(MotionTokens.ICON_PRESS_IN)
    val iconPressOut: Curve get() = curve(MotionTokens.ICON_PRESS_OUT)
    val homeContentZoom: Float get() = Design.num(MotionTokens.HOME_CONTENT_ZOOM)
    val homeWallpaperZoom: Float get() = Design.num(MotionTokens.HOME_WALLPAPER_ZOOM)
    val homeDepthBlur: Float get() = Design.num(MotionTokens.HOME_DEPTH_BLUR)
    val menuOpen: Curve get() = curve(MotionTokens.MENU_OPEN)
    val menuClose: Curve get() = curve(MotionTokens.MENU_CLOSE)
    val menuBlur: Float get() = Design.num(MotionTokens.MENU_BLUR)
    val reflow: Curve get() = curve(MotionTokens.REFLOW)
    val dragLift: Curve get() = curve(MotionTokens.DRAG_LIFT)
    val dragSettle: Curve get() = curve(MotionTokens.DRAG_SETTLE)
    val jiggleDegrees: Float get() = Design.num(MotionTokens.JIGGLE_DEGREES)
    val jigglePeriod: Float get() = Design.num(MotionTokens.JIGGLE_PERIOD)
    val sheet: Curve get() = curve(MotionTokens.SHEET)
    val navPush: Curve get() = curve(MotionTokens.NAV_PUSH)
    val widgetResize: Curve get() = curve(MotionTokens.WIDGET_RESIZE)
    val appear: Curve get() = curve(MotionTokens.APPEAR)
    val appearFade: Curve get() = curve(MotionTokens.APPEAR_FADE)
    val disappearFade: Curve get() = curve(MotionTokens.DISAPPEAR_FADE)
    val editBar: Curve get() = curve(MotionTokens.EDIT_BAR)
    val arrival: Curve get() = curve(MotionTokens.ARRIVAL)
    val arrivalWallpaper: Curve get() = curve(MotionTokens.ARRIVAL_WALLPAPER)
    val arrivalZoom: Float get() = Design.num(MotionTokens.ARRIVAL_ZOOM)
    val arrivalWallpaperZoom: Float get() = Design.num(MotionTokens.ARRIVAL_WALLPAPER_ZOOM)
    val clockTick: Curve get() = curve(MotionTokens.CLOCK_TICK)
    val switcher = SwitcherProfile()

    private fun curve(k: CurveKey): Curve = Design.curve(k)
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
    val enter: Curve get() = curve(MotionTokens.SWITCHER_ENTER)
    val scroll: Curve get() = curve(MotionTokens.SWITCHER_SCROLL)
    val flingProjection: Float get() = Design.num(MotionTokens.SWITCHER_FLING_PROJECTION)
    val open: Curve get() = curve(MotionTokens.SWITCHER_OPEN)
    val home: Curve get() = curve(MotionTokens.SWITCHER_HOME)
    val flick: Curve get() = curve(MotionTokens.SWITCHER_FLICK)
    val flickSpeedDp: Float get() = Design.num(MotionTokens.SWITCHER_FLICK_SPEED_DP)
    val reflow: Curve get() = curve(MotionTokens.SWITCHER_REFLOW)

    private fun curve(k: CurveKey): Curve = Design.curve(k)
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
