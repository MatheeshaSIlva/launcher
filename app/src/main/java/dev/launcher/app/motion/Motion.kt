package dev.launcher.app.motion

import dev.launcher.app.Spring
import kotlin.math.abs
import kotlin.math.sign

/** A spring by its feel, as in SwiftUI: [response] = period in seconds, [damping] = fraction (1 = no overshoot). */
data class SpringSpec(val response: Float, val damping: Float) {
    fun spring() = Spring(response, damping)
}

/**
 * Every animation of the launcher by its role. Code asks for a role, never for numbers, so a theme's motion layer can swap
 * the whole profile (or single roles) without touching the code that animates.
 */
data class MotionProfile(
    val name: String,
    // App launch: the card grows out of the icon over the picture of home.
    val appOpen: SpringSpec,
    // Going home: the card flies into its icon (position and size have their own springs, as in iOS).
    val appClosePosition: SpringSpec,
    val appCloseSize: SpringSpec,
    // A home drag that does not commit: back to the app.
    val appCancel: SpringSpec,
    // Home recedes behind an opening app and comes forward again on close (see [homeContentZoom]).
    val homeDepthOpen: SpringSpec,
    val homeDepthClose: SpringSpec,
    val switchCommit: SpringSpec,
    val switchCancel: SpringSpec,
    // Home pages and the drawer.
    val pageSnap: SpringSpec,
    val drawer: SpringSpec,
    val folderOpen: SpringSpec,
    val folderClose: SpringSpec,
    val modeCrossfadeMs: Long,
    // Scrolling: back from past the end, and how a fling slows down.
    val overscrollReturn: SpringSpec,
    val rubberBand: Float,
    val decelerationRate: Float,
    // A fling faster than this (dp/s) turns the page even when it has moved only a little.
    val pageFlingDp: Float,
    // Touching an icon dims it (iOS), instead of shrinking it.
    val iconPressDim: Float,
    val iconPressInMs: Long,
    val iconPressOutMs: Long,
    // Home behind an open app: icons zoom more than the wallpaper, which gives the iOS sense of depth.
    val homeContentZoom: Float,
    val homeWallpaperZoom: Float,
    /** Blur of home (pt) when it has fully receded behind an open app; scales with depth (iOS blurs home as an app opens). */
    val homeDepthBlur: Float,
    // Long-press menu: the item lifts, home blurs and the menu grows on one spring (UIContextMenuInteraction); closing is
    // quicker and does not bounce. [menuBlur]: home's blur behind it (pt).
    val menuOpen: SpringSpec,
    val menuClose: SpringSpec,
    val menuBlur: Float,
    // Edit mode: icons making room for a dragged one, the dragged one lifting and dropping into its place.
    val reflow: SpringSpec,
    val dragLift: SpringSpec,
    val dragSettle: SpringSpec,
    /** Wiggle: amplitude (degrees, widgets get less) and period (s). */
    val jiggleDegrees: Float,
    val jigglePeriod: Float,
    // Sheets (widget gallery): presenting and dismissing, and pushing a page inside one.
    val sheet: SpringSpec,
    val navPush: SpringSpec,
)

object Motion {
    /**
     * iOS 26. Card springs are the values tuned on the S24 (launch and close "really smooth", close elasticity reduced on
     * request); the rest follow UIKit: paging and bounces critically damped, UIScrollView rubber band 0.55 and normal
     * deceleration 0.998 per ms.
     */
    val IOS = MotionProfile(
        name = "ios",
        appOpen = SpringSpec(0.42f, 0.92f),
        appClosePosition = SpringSpec(0.5f, 0.92f),
        appCloseSize = SpringSpec(0.44f, 0.9f),
        appCancel = SpringSpec(0.38f, 1f),
        homeDepthOpen = SpringSpec(0.45f, 1f),
        homeDepthClose = SpringSpec(0.5f, 1f),
        switchCommit = SpringSpec(0.35f, 1f),
        switchCancel = SpringSpec(0.3f, 1f),
        pageSnap = SpringSpec(0.38f, 1f),
        drawer = SpringSpec(0.4f, 1f),
        folderOpen = SpringSpec(0.42f, 0.86f),
        folderClose = SpringSpec(0.36f, 1f),
        modeCrossfadeMs = 220,
        overscrollReturn = SpringSpec(0.42f, 1f),
        rubberBand = 0.55f,
        decelerationRate = 0.998f,
        pageFlingDp = 320f,
        iconPressDim = 0.32f,
        iconPressInMs = 70,
        iconPressOutMs = 220,
        homeContentZoom = 1.12f,
        homeWallpaperZoom = 1.04f,
        homeDepthBlur = 14f,
        menuOpen = SpringSpec(0.35f, 0.8f),
        menuClose = SpringSpec(0.3f, 1f),
        menuBlur = 18f,
        reflow = SpringSpec(0.36f, 1f),
        dragLift = SpringSpec(0.26f, 0.8f),
        dragSettle = SpringSpec(0.34f, 0.86f),
        jiggleDegrees = 1.6f,
        jigglePeriod = 0.26f,
        sheet = SpringSpec(0.45f, 1f),
        navPush = SpringSpec(0.42f, 1f),
    )

    @Volatile var profile: MotionProfile = IOS

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
