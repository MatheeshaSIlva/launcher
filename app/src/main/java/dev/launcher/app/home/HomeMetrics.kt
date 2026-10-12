package dev.launcher.app.home

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Every size and position of the home screen, measured from Apple's own full-resolution press images and scaled to this
 * display. [u] is one iOS point (the design system's: `Scale.unitPx`, which follows the phone's Display size): home values come from the iOS 26 Home Screen on an iPhone 16 Pro (402 x 874 pt, a
 * 2.17 aspect like the S24's), App Library values from iOS's App Library (measured on an iPhone 11 Pro) in the same points.
 * Recomputed when the size, the insets or the config change. All values in px.
 *
 * Measured (iPhone 16 Pro, pt): icons 64; first column's icon 30.1 from the edge; column pitch 92.5; first row's icon top
 * 89.5 from the top; row pitch 100.6; label cap height 7.8 (SF 11) with its baseline 16.4 below the icon; Search pill
 * 29.6 tall, 19.4 above the dock; dock 101 tall (19 above and below its 64 pt icons), inset 17.5 from the sides and the
 * bottom, corner radius 39 = display corner radius - inset (concentric), dock icons 89.4 apart.
 */
class HomeMetrics(
    val w: Int,
    val h: Int,
    topInset: Int,
    bottomInset: Int,
    deviceRadius: Float,
    val cfg: HomeConfig,
    val u: Float,
) {
    fun pt(v: Float) = v * u

    /** Home's own text grown with the user's own text size (the settings' Size; not the phone's Font size, as on iOS). */
    private val tf = dev.launcher.app.design.Scale.homeText()

    // ---- home pages: the user's columns and rows (Layouts, Home screen; Matheesha: a smaller size frees room for more).
    //      Icons are iOS's 64 pt; the counts the settings offer are those that fit at that size ([room]); counts that no
    //      longer fit (the size grew since) make the icons smaller rather than overlap. The side margins are iOS's while
    //      the columns leave that much room, else as wide as the gaps between the icons.
    private val columns = cfg.columns.coerceAtLeast(2)
    private val rows = cfg.rows.coerceAtLeast(1)
    private val dockSlots = cfg.dockSlots.coerceAtLeast(1)
    val iconSize = minOf(
        ICON * u,
        (w - (columns + 1) * MIN_GAP * u) / columns,
        pageArea(h, topInset, bottomInset, u, ICON * u) / rows - rowExtra(u, cfg.showLabels, tf),
        (w - 2 * DOCK_INSET * u - 2 * DOCK_PAD * u - (dockSlots - 1) * DOCK_GAP * u) / dockSlots,
    ).roundToInt()
    private val evenGap = (w - columns * iconSize) / (columns + 1f)
    private val sideInset = if (evenGap >= 28.5f * u) 30.1f * u else evenGap
    val columnPitch = (w - 2 * sideInset - iconSize) / max(1, columns - 1)
    fun columnCenterX(col: Int) = sideInset + iconSize / 2f + col * columnPitch
    /** Inter at this size has SF 11's cap height (Inter's caps are taller: 0.727 em against SF's 0.705). */
    val labelTextSize = 10.7f * u * tf
    /** From the icon's bottom edge to the label's baseline (8.6 pt of gap, then the cap height). */
    val labelBaseline = (8.6f + 7.8f * tf) * u

    // ---- dock (iOS 26: a floating glass platter, concentric with the display's corners)
    val dockInset = DOCK_INSET * u
    val dockHeight = iconSize + 37 * u
    val dockBottom = h - max(17.5f * u, bottomInset * 0.75f)
    val dockTop = dockBottom - dockHeight
    val dockRadius = max(deviceRadius - dockInset, dockHeight * 0.385f).coerceAtMost(dockHeight / 2f)
    /** From one dock icon to the next with [n] in the dock: iOS's 89.4 pt, closer when more must fit (17.4 pt kept inside). */
    fun dockPitch(n: Int): Float =
        if (n <= 1) 0f else minOf(89.4f * u, (w - 2 * dockInset - 2 * DOCK_PAD * u - iconSize) / (n - 1))

    // ---- Search pill / page indicator (a clear glass capsule above the dock)
    val indicatorHeight = 29.6f * u
    val indicatorBottom = dockTop - 19.4f * u
    val indicatorTop = indicatorBottom - indicatorHeight
    val dotSize = 7 * u
    val dotGap = 9 * u

    // ---- grid: icons sit at the top of evenly spaced rows
    val gridTop = max(89.5f * u, topInset + 16 * u)
    val cellHeight = (indicatorTop - 13 * u - gridTop) / rows
    fun cellLeft(col: Int) = columnCenterX(col) - columnPitch / 2f
    fun cellTop(row: Int) = gridTop + row * cellHeight

    // ---- widgets (iOS): a widget spans its columns' icons plus 4 pt each side, and its rows from the first icon's top to
    //      the last icon's bottom; corners 28 pt (Apple's iOS 27 kit: a small widget is 164.67 pt, radius 28); its name
    //      below like an app label
    val widgetRadius = 28 * u
    fun widgetWidth(spanX: Int) = (spanX - 1) * columnPitch + iconSize + 8 * u
    fun widgetHeight(spanY: Int) = (spanY - 1) * cellHeight + iconSize
    /** Left edge of a widget starting at [col], relative to that column's cell. */
    fun widgetInset(col: Int) = columnCenterX(col) - iconSize / 2f - 4 * u - cellLeft(col)

    // ---- Search pill (iOS 26: "Search" at rest, page dots while the pages move)
    val searchPillWidth = 78 * u
    val searchPillText = 11.2f * u * tf

    // ---- App Library, in the same unit as everything else (iOS points are absolute: one scale for home, library, lists
    //      and folders, so text and icons keep the same proportions everywhere). Measured on iOS (pt): side margin 23.3, gap
    //      between tiles 18.2, tiles fill the rest (166.6 on this 402 pt width); inside a tile: padding 0.0734, gap 0.088 of
    //      the tile, icons the rest (63.7 here, the home icon's size); tile corners 0.142 of the tile; label SF 13 with its
    //      baseline 17 below the tile; 35.3 from a tile's bottom to the next row; search field 46.7 tall, 13 below the
    //      status bar, tiles 25.6 below it.
    //      A tile is 166.6 pt wherever there is more room (a smaller Display size, a tablet: Matheesha saw the tiles grow
    //      while everything else shrank), the grid centred; more columns share the width and shrink the tiles.
    /** The side margin of the library's pages and of the sheets that share its look (Spotlight, the widget gallery). */
    val libMargin = 23.3f * u
    private val libGap = 18.2f * u
    val libColumns = cfg.libraryColumns.coerceAtLeast(1)
    val tileSize = minOf(166.6f * u, (w - 2 * libMargin - (libColumns - 1) * libGap) / libColumns)
    /** The tiles' (and the search field's) side margin: the grid centred. */
    val libSide = (w - libColumns * tileSize - (libColumns - 1) * libGap) / 2f
    fun tileLeft(col: Int) = libSide + col * (tileSize + libGap)
    val tilePad = tileSize * 0.0734f
    val tileIconGap = tileSize * 0.088f
    val tileIcon = (tileSize - 2 * tilePad - tileIconGap) / 2f
    val tileRadius = tileSize * 0.142f
    /** SF 13 cap height in Inter. */
    val tileLabelSize = 12.6f * u * tf
    val tileLabelBaseline = (7.8f + 9.2f * tf) * u
    val tileRowPitch = tileSize + 35.3f * u
    val searchTop = max(topInset.toFloat(), 44 * u) + 13 * u
    val searchHeight = 46.7f * u
    val tilesTop = searchTop + searchHeight + 25.6f * u
    val listRow = 56 * u
    val listIcon = 40 * u
    val listText = 16.5f * u * tf
    val listHeader = 30 * u
    val listHeaderText = 13 * u * tf
    val listSideIndex = 22 * u
    val bottomSafe = bottomInset + 8 * u

    // ---- folder panel (category opened from the App Library)
    val folderSide = 18 * u
    val folderColumns = 4
    val folderRadius = 34 * u
    val folderTitleSize = 28 * u * tf

    /** How many columns, rows and dock icons fit at iOS's icon size. */
    data class Room(val columns: Int, val rows: Int, val dock: Int)

    /** Where home was last laid out (its size and insets, px): the settings measure the room a choice leaves on it. */
    data class Frame(val w: Int, val h: Int, val topInset: Int, val bottomInset: Int)

    companion object {
        /** iOS's home icon (pt). */
        const val ICON = 64f
        /** The least room between two icons on a page (pt): their names need it. */
        const val MIN_GAP = 16f
        /** The dock's inset from the screen's sides, the room kept inside it beside its icons, the least between two (pt). */
        const val DOCK_INSET = 17.5f
        const val DOCK_PAD = 17.4f
        const val DOCK_GAP = 10f

        @Volatile var frame: Frame? = null

        /** The height (px) the page grid has at [u] with icons of [icon] px: from below the status bar to above the Search pill. */
        fun pageArea(h: Int, topInset: Int, bottomInset: Int, u: Float, icon: Float): Float {
            val dockBottom = h - max(17.5f * u, bottomInset * 0.75f)
            val indicatorTop = dockBottom - (icon + 37 * u) - 19.4f * u - 29.6f * u
            return indicatorTop - 13 * u - max(89.5f * u, topInset + 16 * u)
        }

        /** What a row needs under its icon (px): the name and a little room, or only the room without names. */
        fun rowExtra(u: Float, labels: Boolean, tf: Float): Float = if (labels) (8.6f + 7.8f * tf + 3f + 8f) * u else 12f * u

        /** The smallest App Library tile the room counts (pt): its icons three quarters of home's. */
        const val TILE_MIN = 125f

        /** How many App Library tiles fit across [w] px at [u] pixels a point, none smaller than [TILE_MIN]. */
        fun roomTiles(w: Int, u: Float): Int = ((w - 2 * 23.3f * u + 18.2f * u) / ((TILE_MIN + 18.2f) * u)).toInt()

        /** The room on [f] at [u] pixels a point (names shown or not, text at [tf]). */
        fun room(f: Frame, u: Float, labels: Boolean, tf: Float): Room {
            val icon = ICON * u
            return Room(
                columns = ((f.w - MIN_GAP * u) / (icon + MIN_GAP * u)).toInt(),
                rows = (pageArea(f.h, f.topInset, f.bottomInset, u, icon) / (icon + rowExtra(u, labels, tf))).toInt(),
                dock = ((f.w - 2 * DOCK_INSET * u - 2 * DOCK_PAD * u + DOCK_GAP * u) / (icon + DOCK_GAP * u)).toInt(),
            )
        }
    }
}
