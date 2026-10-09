package dev.launcher.app.shade

import dev.launcher.app.design.ChoiceKey
import dev.launcher.app.design.ColorKey
import dev.launcher.app.design.MaterialKey
import dev.launcher.app.design.NumberKey
import dev.launcher.app.design.TextKey

/**
 * Notification Center's tokens (`comp.nc.*` in the theme; values and their sources in `assets/themes/ios27.json`, the
 * kit's measurements in `docs/tokens/ios27-kit.json` "notifications" and "lockScreen"). Lengths are in points.
 */
object NcTokens {
    // A notification's platter (shared with the banners: they are the same component).
    val PLATTER = MaterialKey("comp.nc.platter.material")
    val CORNER = NumberKey("comp.nc.platter.corner")
    val PADDING = NumberKey("comp.nc.platter.padding")
    val ICON = NumberKey("comp.nc.platter.icon")
    val TEXT_X = NumberKey("comp.nc.platter.text-x")
    val TEXT_TOP = NumberKey("comp.nc.platter.text-top")
    val MIN_HEIGHT = NumberKey("comp.nc.platter.min-height")
    val TITLE = TextKey("comp.nc.platter.title")
    val BODY = TextKey("comp.nc.platter.body")
    val TIME = TextKey("comp.nc.platter.time")
    val LABEL = ColorKey("comp.nc.platter.label")
    val TIME_COLOR = ColorKey("comp.nc.platter.time-color")
    val TIME_BLEND = ChoiceKey("comp.nc.platter.time-blend")

    // The list.
    val MARGIN = NumberKey("comp.nc.list.margin")
    val GAP = NumberKey("comp.nc.list.gap")
    val OVERLAY = MaterialKey("comp.nc.overlay.material")
    val FADE_TOP = NumberKey("comp.nc.fade.top")
    val FADE_BOTTOM = NumberKey("comp.nc.fade.bottom")
    val FADE_LENGTH = NumberKey("comp.nc.fade.length")

    // A group's stack: the shelves under its front platter.
    val SHELF_HEIGHT = NumberKey("comp.nc.stack.shelf-height")
    val SHELF_SHOW = NumberKey("comp.nc.stack.shelf-show")
    val SHELF_INSET1 = NumberKey("comp.nc.stack.inset1")
    val SHELF_INSET2 = NumberKey("comp.nc.stack.inset2")
    val STACK_TEXT_TOP = NumberKey("comp.nc.stack.front-text-top")
    val STACK_MIN_HEIGHT = NumberKey("comp.nc.stack.front-min-height")

    // The collapsed stack's parts under the front notification.
    val PEEK_SHOW = NumberKey("comp.nc.peek.show")
    val PEEK_HEIGHT = NumberKey("comp.nc.peek.height")
    val PEEK_INSET = NumberKey("comp.nc.peek.inset")
    val MORE_INSET = NumberKey("comp.nc.peek.more-inset")
    val PEEK_CORNER = NumberKey("comp.nc.peek.corner")
    val PEEK_ICON = NumberKey("comp.nc.peek.icon")

    // The flashlight and camera buttons.
    val BUTTON = MaterialKey("comp.nc.button.material")
    val BUTTON_SIZE = NumberKey("comp.nc.button.size")
    val BUTTON_INSET_X = NumberKey("comp.nc.button.inset-x")
    val BUTTON_BOTTOM = NumberKey("comp.nc.button.bottom")
    val BUTTON_SYMBOL = NumberKey("comp.nc.button.symbol")
    val BUTTON_SYMBOL_COLOR = ColorKey("comp.nc.button.symbol-color")
    val BUTTON_SYMBOL_BLEND = ChoiceKey("comp.nc.button.symbol-blend")
    val BUTTON_ON = ColorKey("comp.nc.button.on-color")
    val MEDIA_PLACEHOLDER = ColorKey("comp.nc.media.placeholder-color")
    val MEDIA_TRACK = ColorKey("comp.nc.media.track-color")
    /** An app's name over its expanded group (on the wallpaper). */
    val HEADER_COLOR = ColorKey("comp.nc.header-color")
    /** The soft shadow under the date and the group names. */
    val TEXT_SHADOW = ColorKey("comp.nc.text-shadow-color")
    /** The symbol on a lit button (the torch on). */
    val BUTTON_ON_SYMBOL = ColorKey("comp.nc.button.on-symbol-color")

    // The long look (a notification held).
    val LOOK_CARD = ColorKey("comp.nc.look.card-color")
    val LOOK_CORNER = NumberKey("comp.nc.look.card-corner")
    val LOOK_INSET = NumberKey("comp.nc.look.inset")
    val LOOK_LABEL = ColorKey("comp.nc.look.label")
    /** Between the long look's card and its menu (the menu itself: [dev.launcher.app.components.MenuSpec.NC]). */
    val MENU_GAP = NumberKey("comp.nc.look.menu-gap")
    val LOOK_DIM = ColorKey("comp.nc.look.dim")
    val LOOK_BLUR = NumberKey("comp.nc.look.blur")

    val ALL = listOf(PLATTER, CORNER, PADDING, ICON, TEXT_X, TEXT_TOP, MIN_HEIGHT, TITLE, BODY, TIME, LABEL, TIME_COLOR, TIME_BLEND,
        MARGIN, GAP, OVERLAY, FADE_TOP, FADE_BOTTOM, FADE_LENGTH, SHELF_HEIGHT, SHELF_SHOW, SHELF_INSET1, SHELF_INSET2, STACK_TEXT_TOP, STACK_MIN_HEIGHT,
        PEEK_SHOW, PEEK_HEIGHT, PEEK_INSET, MORE_INSET, PEEK_CORNER, PEEK_ICON, BUTTON, BUTTON_SIZE, BUTTON_INSET_X,
        BUTTON_BOTTOM, BUTTON_SYMBOL, BUTTON_SYMBOL_COLOR, BUTTON_SYMBOL_BLEND, BUTTON_ON, BUTTON_ON_SYMBOL, MEDIA_PLACEHOLDER, MEDIA_TRACK, HEADER_COLOR, TEXT_SHADOW, LOOK_CARD, LOOK_CORNER, LOOK_INSET, LOOK_LABEL,
        MENU_GAP, LOOK_DIM, LOOK_BLUR).map { it.name } + dev.launcher.app.components.MenuSpec.NC.all
}
