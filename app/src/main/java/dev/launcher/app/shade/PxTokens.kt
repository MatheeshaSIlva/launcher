package dev.launcher.app.shade

import dev.launcher.app.design.ChoiceKey
import dev.launcher.app.design.ColorKey
import dev.launcher.app.design.MaterialKey
import dev.launcher.app.design.NumberKey
import dev.launcher.app.design.TextKey

/**
 * The Pixel shade's tokens (`comp.px.*`: Android 16's notification shade and Quick Settings, drawn by
 * [PixelShadeView] when a theme picks `sys.layout.shade` = "pixel"). Their defaults live in the base theme (any theme
 * may pick this shade); colours name the system's Material roles (`@primary`...). Lengths are in points (one dp under
 * the "density" scale policy the Pixel theme uses). Measured on the Android 17 emulator's own SystemUI
 * (tools/shots/pxref, `docs/PIXEL_PROFILE.md`).
 */
object PxTokens {
    /** What is behind the shade: blurred and dimmed (its frost and fills). */
    val BACKGROUND = MaterialKey("comp.px.shade.background")
    /** The panel's veil over the blurred backdrop. */
    val SCRIM = ColorKey("comp.px.shade.scrim")
    val MARGIN = NumberKey("comp.px.shade.margin")

    // The status row of the first pull (the date beside the bar's time) and the expanded header.
    val DATE = TextKey("comp.px.header.date")
    val CLOCK = TextKey("comp.px.header.clock")
    val HEADER_TEXT = ColorKey("comp.px.header.text-color")
    val HEADER_HEIGHT = NumberKey("comp.px.header.height")

    // Tiles.
    val TILE_HEIGHT = NumberKey("comp.px.tile.height")
    val TILE_GAP = NumberKey("comp.px.tile.gap")
    val TILE_CORNER = NumberKey("comp.px.tile.corner")
    val TILE_ICON_BOX = NumberKey("comp.px.tile.icon-box")
    val TILE_ICON_CORNER = NumberKey("comp.px.tile.icon-corner")
    val TILE_ICON = NumberKey("comp.px.tile.icon")
    val TILE_FILL = ColorKey("comp.px.tile.fill")
    val TILE_ON = ColorKey("comp.px.tile.on-fill")
    val TILE_ICON_COLOR = ColorKey("comp.px.tile.icon-color")
    val TILE_ON_ICON = ColorKey("comp.px.tile.on-icon-color")
    val TILE_LABEL = ColorKey("comp.px.tile.label-color")
    val TILE_SECONDARY = ColorKey("comp.px.tile.secondary-color")
    val TILE_TITLE = TextKey("comp.px.tile.title")
    val TILE_SUBTITLE = TextKey("comp.px.tile.subtitle")
    val QQS_TOP = NumberKey("comp.px.qqs.top")
    val QS_ROWS = NumberKey("comp.px.qs.rows")

    // Brightness.
    val BRIGHTNESS_HEIGHT = NumberKey("comp.px.brightness.height")
    val BRIGHTNESS_TRACK = ColorKey("comp.px.brightness.track-color")
    val BRIGHTNESS_FILL = ColorKey("comp.px.brightness.fill-color")
    val BRIGHTNESS_ICON = ColorKey("comp.px.brightness.icon-color")

    // The footer of the expanded panel (user, settings, power) and its page row.
    val FOOTER_BUTTON = NumberKey("comp.px.footer.button")
    val FOOTER_FILL = ColorKey("comp.px.footer.fill")
    val FOOTER_ICON = ColorKey("comp.px.footer.icon-color")
    val POWER_FILL = ColorKey("comp.px.footer.power-fill")
    val POWER_ICON = ColorKey("comp.px.footer.power-icon-color")

    // Notifications.
    val NOTIF_FILL = ColorKey("comp.px.notif.fill")
    val NOTIF_CORNER = NumberKey("comp.px.notif.corner")
    val NOTIF_INNER_CORNER = NumberKey("comp.px.notif.inner-corner")
    val NOTIF_GAP = NumberKey("comp.px.notif.gap")
    val NOTIF_PAD = NumberKey("comp.px.notif.padding")
    val NOTIF_ICON = NumberKey("comp.px.notif.icon")
    val NOTIF_ICON_FILL = ColorKey("comp.px.notif.icon-fill")
    val NOTIF_ICON_COLOR = ColorKey("comp.px.notif.icon-color")
    val NOTIF_APP = TextKey("comp.px.notif.app")
    val NOTIF_TITLE = TextKey("comp.px.notif.title")
    val NOTIF_TEXT = TextKey("comp.px.notif.text")
    val NOTIF_TITLE_COLOR = ColorKey("comp.px.notif.title-color")
    val NOTIF_TEXT_COLOR = ColorKey("comp.px.notif.text-color")
    val NOTIF_CHIP = ColorKey("comp.px.notif.chip-fill")
    val NOTIF_SECTION = TextKey("comp.px.notif.section")
    val STACK_FILL = ColorKey("comp.px.notif.stack-fill")
    val CLEAR_FILL = ColorKey("comp.px.notif.clear-fill")
    val CLEAR_TEXT = ColorKey("comp.px.notif.clear-text-color")
    val CLEAR_HEIGHT = NumberKey("comp.px.notif.clear-height")
    val ACTION_OUTLINE = ColorKey("comp.px.notif.action-outline")
    val ACTION_TEXT = ColorKey("comp.px.notif.action-text-color")

    // Motion: the panel's two stages.
    val EXPAND = dev.launcher.app.design.SpringKey("motion.px.expand")

    /** The shade layout choice (`sys.layout.shade`): "ios" or "pixel". */
    val LAYOUT = ChoiceKey("sys.layout.shade")

    val ALL = listOf(BACKGROUND, SCRIM, MARGIN, DATE, CLOCK, HEADER_TEXT, HEADER_HEIGHT, TILE_HEIGHT, TILE_GAP, TILE_CORNER, TILE_ICON_BOX,
        TILE_ICON_CORNER, TILE_ICON, TILE_FILL, TILE_ON, TILE_ICON_COLOR, TILE_ON_ICON, TILE_LABEL, TILE_SECONDARY, TILE_TITLE,
        TILE_SUBTITLE, QQS_TOP, QS_ROWS, BRIGHTNESS_HEIGHT, BRIGHTNESS_TRACK, BRIGHTNESS_FILL, BRIGHTNESS_ICON, FOOTER_BUTTON,
        FOOTER_FILL, FOOTER_ICON, POWER_FILL, POWER_ICON, NOTIF_FILL, NOTIF_CORNER, NOTIF_INNER_CORNER, NOTIF_GAP, NOTIF_PAD,
        NOTIF_ICON, NOTIF_ICON_FILL, NOTIF_ICON_COLOR, NOTIF_APP, NOTIF_TITLE, NOTIF_TEXT, NOTIF_TITLE_COLOR, NOTIF_TEXT_COLOR,
        NOTIF_CHIP, NOTIF_SECTION, STACK_FILL, CLEAR_FILL, CLEAR_TEXT, CLEAR_HEIGHT, ACTION_OUTLINE, ACTION_TEXT, EXPAND, LAYOUT).map { it.name }
}
