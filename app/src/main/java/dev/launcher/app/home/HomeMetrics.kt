package dev.launcher.app.home

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Every size and position of the home screen, from iOS's proportions scaled to this display: [u] is one iOS point
 * (the reference iPhone is 393 pt wide), so the layout keeps iOS's look on any width. Recomputed when the size, the
 * insets or the config change. All values in px.
 */
class HomeMetrics(
    val w: Int,
    val h: Int,
    topInset: Int,
    bottomInset: Int,
    deviceRadius: Float,
    val cfg: HomeConfig,
) {
    val u = w / 393f
    fun pt(v: Float) = v * u

    // ---- home pages
    val iconSize = (60 * u).roundToInt()
    private val sideInset = 27 * u
    val columnPitch = (w - 2 * sideInset - iconSize) / max(1, cfg.columns - 1)
    fun columnCenterX(col: Int) = sideInset + iconSize / 2f + col * columnPitch
    val labelTextSize = 12 * u
    val labelGap = 5 * u
    val labelLine = labelTextSize * 1.3f

    // ---- dock (iOS 26: a floating glass platter, concentric with the display's corners)
    val dockInset = 12 * u
    val dockHeight = iconSize + 32 * u
    val dockBottom = h - max(bottomInset + 4 * u, 16 * u)
    val dockTop = dockBottom - dockHeight
    val dockRadius = (deviceRadius - dockInset).coerceIn(dockHeight * 0.34f, dockHeight * 0.5f)

    // ---- page indicator (a glass capsule above the dock)
    val indicatorHeight = 26 * u
    val indicatorBottom = dockTop - 10 * u
    val indicatorTop = indicatorBottom - indicatorHeight
    val dotSize = 7 * u
    val dotGap = 9 * u

    // ---- grid
    val gridTop = max(topInset.toFloat(), 44 * u) + 14 * u
    val gridBottom = indicatorTop - 6 * u
    val cellHeight = (gridBottom - gridTop) / cfg.rows
    fun cellLeft(col: Int) = columnCenterX(col) - columnPitch / 2f
    fun cellTop(row: Int) = gridTop + row * cellHeight

    // ---- App Library
    val libMargin = 20 * u
    private val libGap = 21 * u
    val tileSize = (w - 2 * libMargin - libGap) / 2f
    fun tileLeft(col: Int) = libMargin + col * (tileSize + libGap)
    val tilePad = tileSize * 0.095f
    val tileIcon = (tileSize - 3 * tilePad) / 2f
    val tileRadius = tileSize * 0.16f
    val tileLabelGap = 6 * u
    val tileLabelSize = 12.5f * u
    val tileRowPitch = tileSize + tileLabelGap + tileLabelSize * 1.3f + 16 * u
    val searchTop = max(topInset.toFloat(), 44 * u) + 6 * u
    val searchHeight = 40 * u
    val tilesTop = searchTop + searchHeight + 18 * u
    val listRow = 56 * u
    val listIcon = 40 * u
    val listText = 17 * u
    val listHeader = 30 * u
    val listHeaderText = 13 * u
    val listSideIndex = 22 * u
    val bottomSafe = bottomInset + 8 * u

    // ---- folder panel (category opened from the App Library)
    val folderSide = 18 * u
    val folderColumns = 4
    val folderRadius = 34 * u
    val folderTitleSize = 28 * u
}
