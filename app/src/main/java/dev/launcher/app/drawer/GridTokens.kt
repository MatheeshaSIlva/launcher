package dev.launcher.app.drawer

import dev.launcher.app.design.ChoiceKey
import dev.launcher.app.design.ColorKey
import dev.launcher.app.design.NumberKey
import dev.launcher.app.design.TextKey

/**
 * The "All apps" drawer's tokens (`comp.grid.*`: Android 16's app drawer as a Pixel shows it, drawn by [AppGridView] when
 * a theme picks `sys.layout.drawer` = "grid"). Defaults live in the base theme (any theme may pick this drawer); colours
 * name the system's Material roles. Lengths in points (one dp under the "density" scale policy the Pixel theme uses).
 * Measured on the Android 17 emulator's Pixel Launcher (tools/shots/pxref/drawer.png and .xml, `docs/PIXEL_PROFILE.md`).
 */
object GridTokens {
    // The sheet: from under the status bar, rounded top corners, a handle.
    val SHEET = ColorKey("comp.grid.sheet-fill")
    /** Over the blurred wallpaper behind the sheet (and above it, under the status bar). */
    val SCRIM = ColorKey("comp.grid.scrim-fill")
    val CORNER = NumberKey("comp.grid.corner")
    val HANDLE_TOP = NumberKey("comp.grid.handle.top")
    val HANDLE_WIDTH = NumberKey("comp.grid.handle.width")
    val HANDLE_HEIGHT = NumberKey("comp.grid.handle.height")
    val HANDLE_COLOR = ColorKey("comp.grid.handle-color")

    // The search field.
    val FIELD_TOP = NumberKey("comp.grid.field.top")
    val FIELD_HEIGHT = NumberKey("comp.grid.field.height")
    val FIELD_MARGIN = NumberKey("comp.grid.field.margin")
    val FIELD_GLYPH = NumberKey("comp.grid.field.glyph-inset")
    val FIELD_SHIFT = NumberKey("comp.grid.field.focus-shift")
    val FIELD_FILL = ColorKey("comp.grid.field.fill")
    val FIELD_TEXT = TextKey("comp.grid.field.text")
    val FIELD_TEXT_COLOR = ColorKey("comp.grid.field.text-color")
    val FIELD_HINT_COLOR = ColorKey("comp.grid.field.hint-color")

    // The apps: predictions, "All apps", the grid.
    val MARGIN = NumberKey("comp.grid.margin")
    val CONTENT_TOP = NumberKey("comp.grid.content-top")
    val ROW = NumberKey("comp.grid.row")
    val ICON = NumberKey("comp.grid.icon")
    val ICON_TOP = NumberKey("comp.grid.icon-top")
    val LABEL_BASELINE = NumberKey("comp.grid.label-baseline")
    val LABEL = TextKey("comp.grid.label.text")
    val LABEL_COLOR = ColorKey("comp.grid.label-color")
    val DIVIDER = NumberKey("comp.grid.divider")
    val HEADER = TextKey("comp.grid.header.text")

    // The fast scroller on the right edge and its letter.
    val SCROLLER_TOP = NumberKey("comp.grid.scroller.top")
    val THUMB_WIDTH = NumberKey("comp.grid.scroller.thumb-width")
    val THUMB_HEIGHT = NumberKey("comp.grid.scroller.thumb-height")
    val TRACK_WIDTH = NumberKey("comp.grid.scroller.track-width")
    val THUMB_FILL = ColorKey("comp.grid.scroller.thumb-fill")
    val TRACK_FILL = ColorKey("comp.grid.scroller.track-fill")
    val POPUP = NumberKey("comp.grid.scroller.popup-size")
    val POPUP_FILL = ColorKey("comp.grid.scroller.popup-fill")
    val POPUP_TEXT = TextKey("comp.grid.scroller.letter.text")
    val POPUP_TEXT_COLOR = ColorKey("comp.grid.scroller.letter-color")

    // A pull's timing (fractions of the way open): home fades out by HOME_FADE_END, the scrim and blur come in from
    // SCRIM_FROM to SCRIM_TO, the drawer's content from CONTENT_FROM to CONTENT_TO.
    val HOME_FADE_END = NumberKey("comp.grid.motion.home-fade-end")
    val SCRIM_FROM = NumberKey("comp.grid.motion.scrim-from")
    val SCRIM_TO = NumberKey("comp.grid.motion.scrim-to")
    val CONTENT_FROM = NumberKey("comp.grid.motion.content-from")
    val CONTENT_TO = NumberKey("comp.grid.motion.content-to")

    /** The drawer layout choice (`sys.layout.drawer`): "app-library" or "grid". */
    val LAYOUT = ChoiceKey("sys.layout.drawer")

    val ALL = listOf(SHEET, SCRIM, CORNER, HANDLE_TOP, HANDLE_WIDTH, HANDLE_HEIGHT, HANDLE_COLOR, FIELD_TOP, FIELD_HEIGHT, FIELD_MARGIN,
        FIELD_GLYPH, FIELD_SHIFT, FIELD_FILL, FIELD_TEXT, FIELD_TEXT_COLOR, FIELD_HINT_COLOR, MARGIN, CONTENT_TOP, ROW, ICON, ICON_TOP,
        LABEL_BASELINE, LABEL, LABEL_COLOR, DIVIDER, HEADER, SCROLLER_TOP, THUMB_WIDTH, THUMB_HEIGHT, TRACK_WIDTH, THUMB_FILL,
        TRACK_FILL, POPUP, POPUP_FILL, POPUP_TEXT, POPUP_TEXT_COLOR, HOME_FADE_END, SCRIM_FROM, SCRIM_TO, CONTENT_FROM, CONTENT_TO,
        LAYOUT).map { it.name }
}
