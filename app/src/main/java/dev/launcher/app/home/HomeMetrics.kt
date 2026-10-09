package dev.launcher.app.home

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Every size and position of the home screen, measured from Apple's own full-resolution press images and scaled to this
 * display. [u] is one iOS point: home values come from the iOS 26 Home Screen on an iPhone 16 Pro (402 x 874 pt, a
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
    /**
     * Android 16's home as a Pixel shows it (`sys.layout.home` = "pixel"), in dp ([density]): 60 dp icons in four columns
     * 11.4 dp from the edges, names in 14 sp, the hotseat row without a platter, page dots above it, the search bar under
     * it (352 x 48 dp, 26 dp above the navigation bar), At a glance at the top. Measured on the Android 17 emulator's
     * Pixel Launcher (tools/shots/pxref/home.xml).
     */
    val pixel: Boolean = false,
    density: Float = 2.625f,
) {
    val u = w / 402f
    fun pt(v: Float) = v * u
    private val d = density
    /** Pixels per dp (Android's sizes, the Pixel profile's unit). */
    val dp = density
    /** The status bar's height (px). */
    val statusTop = topInset.toFloat()
    /** The navigation bar's height (px). */
    val navInset = bottomInset.toFloat()

    // ---- home pages
    val iconSize = if (pixel) (60 * d).roundToInt() else (64 * u).roundToInt()
    private val sideInset = if (pixel) 11.4f * d + ((w - 2 * 11.4f * d) / max(1, cfg.columns) - iconSize) / 2f else 30.1f * u
    val columnPitch = (w - 2 * sideInset - iconSize) / max(1, cfg.columns - 1)
    fun columnCenterX(col: Int) = sideInset + iconSize / 2f + col * columnPitch
    /** Inter at this size has SF 11's cap height (Inter's caps are taller: 0.727 em against SF's 0.705). */
    val labelTextSize = if (pixel) 14f * d else 10.7f * u
    /** From the icon's bottom edge to the label's baseline. */
    val labelBaseline = if (pixel) 23.2f * d else 16.4f * u

    // ---- Pixel: the search bar at the bottom and the hotseat over it
    private val pxSearchBottom = h - bottomInset - 26f * d
    private val pxSearchTop = pxSearchBottom - 48f * d
    private val pxHotseatIconTop = pxSearchTop - 24f * d - iconSize

    // ---- dock (iOS 26: a floating glass platter, concentric with the display's corners; Pixel: the hotseat, no platter)
    val dockInset = if (pixel) 0f else 17.5f * u
    val dockHeight = if (pixel) iconSize + 16f * d else iconSize + 37 * u
    val dockBottom = if (pixel) pxHotseatIconTop + iconSize + 8f * d else h - max(17.5f * u, bottomInset * 0.75f)
    val dockTop = dockBottom - dockHeight
    val dockRadius = if (pixel) 0f else max(deviceRadius - dockInset, dockHeight * 0.385f).coerceAtMost(dockHeight / 2f)
    val dockIconPitch = if (pixel) columnPitch else 89.4f * u

    // ---- Search pill / page indicator (a clear glass capsule above the dock; Pixel: the search bar under the hotseat)
    val indicatorHeight = if (pixel) 48f * d else 29.6f * u
    val indicatorBottom = if (pixel) pxSearchBottom else dockTop - 19.4f * u
    val indicatorTop = indicatorBottom - indicatorHeight
    val dotSize = if (pixel) 6f * d else 7 * u
    val dotGap = if (pixel) 6f * d else 9 * u
    /** Pixel: the page dots' row above the hotseat (its centre line). */
    val pixelDotsY = pxHotseatIconTop - 19f * d
    /** Pixel: At a glance (the date) at the top of the first page. */
    val glanceLeft = 28f * d
    val glanceTop = topInset + 50f * d
    val glanceText = 16f * d

    // ---- grid: icons sit at the top of evenly spaced rows
    // Pixel: rows 99.4 dp apart up from the dots (its five rows start 175 dp down); more rows start higher, never under At a
    // glance (whose date takes the first 100 dp).
    val gridTop = if (pixel) max(topInset + 100f * d, pixelDotsY - 24f * d - cfg.rows * 99.4f * d) else max(89.5f * u, topInset + 16 * u)
    val cellHeight = if (pixel) (pixelDotsY - 24f * d - gridTop) / cfg.rows else (indicatorTop - 13 * u - gridTop) / cfg.rows
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
    val searchPillWidth = if (pixel) w - 2 * 29.7f * d else 78 * u
    val searchPillText = 11.2f * u

    // ---- App Library, in the same unit as everything else (iOS points are absolute: one scale for home, library, lists
    //      and folders, so text and icons keep the same proportions everywhere). Measured on iOS (pt): side margin 23.3, gap
    //      between tiles 18.2, tiles fill the rest (166.6 on this 402 pt width); inside a tile: padding 0.0734, gap 0.088 of
    //      the tile, icons the rest (63.7 here, the home icon's size); tile corners 0.142 of the tile; label SF 13 with its
    //      baseline 17 below the tile; 35.3 from a tile's bottom to the next row; search field 46.7 tall, 13 below the
    //      status bar, tiles 25.6 below it.
    val libMargin = 23.3f * u
    private val libGap = 18.2f * u
    val tileSize = (w - 2 * libMargin - libGap) / 2f
    fun tileLeft(col: Int) = libMargin + col * (tileSize + libGap)
    val tilePad = tileSize * 0.0734f
    val tileIconGap = tileSize * 0.088f
    val tileIcon = (tileSize - 2 * tilePad - tileIconGap) / 2f
    val tileRadius = tileSize * 0.142f
    /** SF 13 cap height in Inter. */
    val tileLabelSize = 12.6f * u
    val tileLabelBaseline = 17f * u
    val tileRowPitch = tileSize + 35.3f * u
    val searchTop = max(topInset.toFloat(), 44 * u) + 13 * u
    val searchHeight = 46.7f * u
    val tilesTop = searchTop + searchHeight + 25.6f * u
    val listRow = 56 * u
    val listIcon = 40 * u
    val listText = 16.5f * u
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
