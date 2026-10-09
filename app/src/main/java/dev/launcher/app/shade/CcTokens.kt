package dev.launcher.app.shade

import dev.launcher.app.design.ChoiceKey
import dev.launcher.app.design.ColorKey
import dev.launcher.app.design.MaterialKey
import dev.launcher.app.design.NumberKey
import dev.launcher.app.design.TextKey

/**
 * Control Center's tokens (`comp.cc.*` in the theme; values and their sources in `assets/themes/ios27.json`, the kit's own
 * values in `docs/tokens/ios27-kit.json` "controlCenter"). Lengths are in points.
 */
object CcTokens {
    // What is behind: the blurred, dimmed picture of the app or home (the kit's Overlay: background blur 24, black 50 %).
    val BACKGROUND = MaterialKey("comp.cc.background.material")
    val SAMSUNG_STRENGTH = NumberKey("comp.cc.background.samsung-strength")

    // Modules and their parts.
    val MODULE = MaterialKey("comp.cc.module.material")
    val ON = MaterialKey("comp.cc.module.on-material")
    val WELL = MaterialKey("comp.cc.well.material")
    val CORNER = NumberKey("comp.cc.module.corner")
    val SLIDER_CORNER = NumberKey("comp.cc.slider.corner")
    /** A symbol on a lit (white) control. */
    val SYMBOL_ON = ColorKey("comp.cc.symbol-on-color")
    val CHEVRON_COLOR = ColorKey("comp.cc.chevron-color")
    val ADD_FILL = ColorKey("comp.cc.add.fill-color")
    val MEDIA_PLACEHOLDER = ColorKey("comp.cc.media.placeholder-color")
    val MEDIA_TRACK = ColorKey("comp.cc.media.track-color")
    val MEDIA_PROGRESS = ColorKey("comp.cc.media.progress-color")
    val MEDIA_LEVEL = ColorKey("comp.cc.media.level-color")
    val MEDIA_SECONDARY = ColorKey("comp.cc.media.secondary-color")
    val EDIT_BADGE = ColorKey("comp.cc.edit.badge-color")
    val EDIT_BADGE_MINUS = ColorKey("comp.cc.edit.badge-minus-color")
    val EDIT_HANDLE = ColorKey("comp.cc.edit.handle-color")
    val EDIT_HANDLE_SHADOW = ColorKey("comp.cc.edit.handle-shadow-color")
    val GALLERY_GRABBER = ColorKey("comp.cc.gallery.grabber-color")
    val GALLERY_HEADING = ColorKey("comp.cc.gallery.heading-color")
    val GALLERY_SECTION = ColorKey("comp.cc.gallery.section-color")
    val GALLERY_NAME = ColorKey("comp.cc.gallery.name-color")
    /** The part of a slider's symbol over which it takes the slider's colour as the level crosses it (a fraction). */
    val SLIDER_COVER = NumberKey("comp.cc.slider.cover")
    // The expanded slider (brightness, volume, flashlight, timer).
    val EXPANDED_SLIDER_WIDTH = NumberKey("comp.cc.expanded.slider-width")
    val EXPANDED_SLIDER_HEIGHT = NumberKey("comp.cc.expanded.slider-height")
    val EXPANDED_SLIDER_CORNER = NumberKey("comp.cc.expanded.slider-corner")
    val EXPANDED_SLIDER_SYMBOL = NumberKey("comp.cc.expanded.slider-symbol")
    val SYMBOL = NumberKey("comp.cc.symbol")
    val SYMBOL_COLOR = ColorKey("comp.cc.symbol-color")
    val WIDE_PADDING = NumberKey("comp.cc.wide.padding")
    val WIDE_GAP = NumberKey("comp.cc.wide.gap")
    val WELL_SIZE = NumberKey("comp.cc.well.size")
    val WELL_SYMBOL = NumberKey("comp.cc.well.symbol")
    val TITLE = TextKey("comp.cc.module.title")
    val TITLE_LARGE = TextKey("comp.cc.module.title-large")
    val DETAIL = TextKey("comp.cc.module.detail")
    val LABEL_COLOR = ColorKey("comp.cc.module.label-color")
    val DETAIL_COLOR = ColorKey("comp.cc.module.detail-color")
    val DETAIL_BLEND = ChoiceKey("comp.cc.module.detail-blend")

    // The grid and the status row.
    val CELL = NumberKey("comp.cc.grid.cell")
    val GAP = NumberKey("comp.cc.grid.gap")
    val GRID_TOP = NumberKey("comp.cc.grid.top")
    val GRID_TOP_EDIT = NumberKey("comp.cc.grid.top-edit")
    val ROW_Y = NumberKey("comp.cc.status.row-y")

    // "+" and power.
    val BUTTON = MaterialKey("comp.cc.button.material")
    val BUTTON_SIZE = NumberKey("comp.cc.button.size")
    val BUTTON_INSET_X = NumberKey("comp.cc.button.inset-x")
    val BUTTON_TOP = NumberKey("comp.cc.button.top")
    val BUTTON_SYMBOL = NumberKey("comp.cc.button.symbol")
    val BUTTON_SYMBOL_COLOR = ColorKey("comp.cc.button.symbol-color")

    // Connectivity.
    val CONN_BIG = NumberKey("comp.cc.connectivity.big")
    val CONN_SMALL = NumberKey("comp.cc.connectivity.small")
    val CONN_SYMBOL_BIG = NumberKey("comp.cc.connectivity.symbol-big")
    val CONN_SYMBOL_SMALL = NumberKey("comp.cc.connectivity.symbol-small")

    // Now Playing.
    val ART = NumberKey("comp.cc.media.art")
    val ART_CORNER = NumberKey("comp.cc.media.art-corner")
    val ART_X = NumberKey("comp.cc.media.art-x")
    val ART_Y = NumberKey("comp.cc.media.art-y")
    val OUTPUT = NumberKey("comp.cc.media.output")
    val MEDIA_TITLE = TextKey("comp.cc.media.title")
    val MEDIA_TEXT_COLOR = ColorKey("comp.cc.media.text-color")
    val MEDIA_TEXT_BLEND = ChoiceKey("comp.cc.media.text-blend")
    val TRANSPORT = NumberKey("comp.cc.media.transport")
    val PLAY = NumberKey("comp.cc.media.play")

    // The gallery ("Add a Control"): a dark sheet with flat circles (iOS 27's; the kit has none).
    val GALLERY_SHEET = MaterialKey("comp.cc.gallery.sheet")
    val GALLERY_ENTRY = MaterialKey("comp.cc.gallery.entry")
    val GALLERY_DIM = ColorKey("comp.cc.gallery.dim")
    val GALLERY_CORNER = NumberKey("comp.cc.gallery.corner")

    /** A control's colour (its toggled-on fill or glyph): `comp.cc.accent.<control>`. */
    fun accent(c: Control) = ColorKey("comp.cc.accent." + c.name.lowercase().replace('_', '-'))

    // How it comes in: over how much of a pull (pt), and the spring it finishes on once the finger lets go.
    val OPEN_TRAVEL = NumberKey("comp.cc.motion.open-travel")
    val OPEN = dev.launcher.app.design.SpringKey("comp.cc.motion.open")

    val ALL = listOf(OPEN_TRAVEL, OPEN, BACKGROUND, SAMSUNG_STRENGTH, MODULE, ON, WELL, CORNER, SLIDER_CORNER, SYMBOL, SYMBOL_COLOR, WIDE_PADDING,
        WIDE_GAP, WELL_SIZE, WELL_SYMBOL, TITLE, TITLE_LARGE, DETAIL, LABEL_COLOR, DETAIL_COLOR, DETAIL_BLEND, CELL, GAP,
        GRID_TOP, GRID_TOP_EDIT, ROW_Y, BUTTON, BUTTON_SIZE, BUTTON_INSET_X, BUTTON_TOP, BUTTON_SYMBOL, BUTTON_SYMBOL_COLOR,
        CONN_BIG, CONN_SMALL, CONN_SYMBOL_BIG, CONN_SYMBOL_SMALL, ART, ART_CORNER, ART_X, ART_Y, OUTPUT, MEDIA_TITLE,
        MEDIA_TEXT_COLOR, MEDIA_TEXT_BLEND, TRANSPORT, PLAY, GALLERY_SHEET, GALLERY_ENTRY, GALLERY_DIM, GALLERY_CORNER,
        SLIDER_COVER, EXPANDED_SLIDER_WIDTH, EXPANDED_SLIDER_HEIGHT, EXPANDED_SLIDER_CORNER, EXPANDED_SLIDER_SYMBOL,
        SYMBOL_ON, CHEVRON_COLOR, ADD_FILL, MEDIA_PLACEHOLDER, MEDIA_TRACK, MEDIA_PROGRESS, MEDIA_LEVEL, MEDIA_SECONDARY,
        EDIT_BADGE, EDIT_BADGE_MINUS, EDIT_HANDLE, EDIT_HANDLE_SHADOW, GALLERY_GRABBER, GALLERY_HEADING, GALLERY_SECTION,
        GALLERY_NAME).map { it.name }
}
