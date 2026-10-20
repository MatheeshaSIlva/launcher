package dev.launcher.app.shade.qs

import dev.launcher.app.design.ChoiceKey
import dev.launcher.app.design.ColorKey
import dev.launcher.app.design.MaterialKey
import dev.launcher.app.design.NumberKey
import dev.launcher.app.design.TextKey

/**
 * Android's shade's tokens (`comp.qs.*`: the panel that holds Quick Settings over the notifications, the layout file's
 * `shade` panel; step 4 of docs/SHADE_PIXEL_EVALUATION.md). In iOS 27 most are aliases of the shared components
 * (`component.control`, `component.notification`, `component.round-button`...), so any theme that styles those styles this
 * panel; its sizes are AOSP's (iOS has no such panel). Lengths are in points.
 */
object QsTokens {
    // ---- The panel: what is behind it blurred and veiled.
    val BACKGROUND = MaterialKey("comp.qs.background")
    /** Between the screen's sides and the tiles, cards and header. */
    val SIDE = NumberKey("comp.qs.side")
    /** The icon set its symbols are drawn from ([dev.launcher.app.design.IconSets]). */
    val SYMBOLS = ChoiceKey("comp.qs.symbols")

    // ---- The header: the date beside the status bar's time (the first stage); the large clock, the date and the carrier.
    val CLOCK = TextKey("comp.qs.header.clock")
    val DATE = TextKey("comp.qs.header.date")
    val CARRIER = TextKey("comp.qs.header.carrier")
    val HEADER_COLOR = ColorKey("comp.qs.header.color")
    /** The large clock's top, and the date's line under it (from the screen's top). */
    val CLOCK_TOP = NumberKey("comp.qs.header.clock-top")
    val DATE_TOP = NumberKey("comp.qs.header.date-top")
    /** The space between the status bar's time and the date beside it (the first stage). */
    val DATE_GAP = NumberKey("comp.qs.header.date-gap")

    // ---- The brightness slider.
    val BRIGHTNESS_TOP = NumberKey("comp.qs.brightness.top")
    val BRIGHTNESS_HEIGHT = NumberKey("comp.qs.brightness.height")
    val BRIGHTNESS_TRACK = MaterialKey("comp.qs.brightness.track")
    val BRIGHTNESS_LEVEL = MaterialKey("comp.qs.brightness.level")
    val BRIGHTNESS_SYMBOL = ColorKey("comp.qs.brightness.symbol-color")

    // ---- Tiles.
    /** The grid's top at the second stage, and the quick row's below the status bar at the first. */
    val TILES_TOP = NumberKey("comp.qs.tiles.top")
    val QUICK_TOP = NumberKey("comp.qs.tiles.quick-top")
    val TILE_HEIGHT = NumberKey("comp.qs.tile.height")
    val TILE_GAP = NumberKey("comp.qs.tile.gap")
    val TILE = MaterialKey("comp.qs.tile.material")
    val TILE_ON = MaterialKey("comp.qs.tile.on-material")
    /** A tile's corner off (a capsule) and on (Android: 24 dp); the change follows the toggle. */
    val TILE_CORNER = NumberKey("comp.qs.tile.corner")
    val TILE_ON_CORNER = NumberKey("comp.qs.tile.on-corner")
    /** A large tile's symbol box (its toggle), its corners off and on, its material off (on: [TILE_ON]). */
    val BOX = NumberKey("comp.qs.tile.box")
    val BOX_CORNER = NumberKey("comp.qs.tile.box-corner")
    val BOX_ON_CORNER = NumberKey("comp.qs.tile.box-on-corner")
    val BOX_MATERIAL = MaterialKey("comp.qs.tile.box-material")
    /** A large tile's inset around its box, and the space between the box and the words. */
    val TILE_INSET = NumberKey("comp.qs.tile.inset")
    val TILE_TEXT_GAP = NumberKey("comp.qs.tile.text-gap")
    val SYMBOL = NumberKey("comp.qs.tile.symbol")
    val SYMBOL_COLOR = ColorKey("comp.qs.tile.symbol-color")
    val ON_SYMBOL_COLOR = ColorKey("comp.qs.tile.on-symbol-color")
    val LABEL = TextKey("comp.qs.tile.label")
    val DETAIL = TextKey("comp.qs.tile.detail")
    val LABEL_COLOR = ColorKey("comp.qs.tile.label-color")
    val DETAIL_COLOR = ColorKey("comp.qs.tile.detail-color")
    /** A tile's words when the whole tile is on (Android: onPrimary). */
    val ON_LABEL_COLOR = ColorKey("comp.qs.tile.on-label-color")
    /** A tile that cannot be used now (Android: the tile faded). */
    val UNAVAILABLE_ALPHA = NumberKey("comp.qs.tile.unavailable-alpha")

    // ---- Under the tiles: the build number, the page dots.
    val BUILD = TextKey("comp.qs.build.text")
    val BUILD_COLOR = ColorKey("comp.qs.build.color")
    val DOT = NumberKey("comp.qs.dots.size")
    val DOT_COLOR = ColorKey("comp.qs.dots.color")
    val DOT_ACTIVE_COLOR = ColorKey("comp.qs.dots.active-color")

    // ---- The footer: the user, settings, power (power lit).
    val FOOTER_BUTTON = NumberKey("comp.qs.footer.button")
    val FOOTER_MATERIAL = MaterialKey("comp.qs.footer.material")
    val FOOTER_SYMBOL = NumberKey("comp.qs.footer.symbol")
    val FOOTER_SYMBOL_COLOR = ColorKey("comp.qs.footer.symbol-color")
    val POWER_MATERIAL = MaterialKey("comp.qs.footer.power-material")
    val POWER_SYMBOL_COLOR = ColorKey("comp.qs.footer.power-symbol-color")
    /** The footer row's centre above the screen's bottom edge. */
    val FOOTER_BOTTOM = NumberKey("comp.qs.footer.bottom")

    // ---- Notifications as cards.
    /** The first card's top below the quick row (the first stage). */
    val CARDS_TOP = NumberKey("comp.qs.cards.top")
    /** What the notifications lie on (Android's shelf: a lighter layer across the screen from just above the first card). */
    val SHELF = MaterialKey("comp.qs.cards.shelf")
    val SHELF_CORNER = NumberKey("comp.qs.cards.shelf-corner")
    val SHELF_INSET = NumberKey("comp.qs.cards.shelf-inset")
    /** A card's actions: outlined capsules. */
    val ACTION_OUTLINE = ColorKey("comp.qs.card.action-outline")
    val CARD = MaterialKey("comp.qs.card.material")
    val CARD_CORNER = NumberKey("comp.qs.card.corner")
    /** Where cards meet in a group or a section: their corners there, and the space between them. */
    val JOINED_CORNER = NumberKey("comp.qs.card.joined-corner")
    val JOINED_GAP = NumberKey("comp.qs.card.joined-gap")
    /** Between groups and sections. */
    val CARD_GAP = NumberKey("comp.qs.card.gap")
    val CARD_PADDING = NumberKey("comp.qs.card.padding")
    val CARD_ICON = NumberKey("comp.qs.card.icon")
    /** The circle behind the app's small symbol and the symbol's colour. */
    val ICON_FILL = ColorKey("comp.qs.card.icon-fill")
    val ICON_COLOR = ColorKey("comp.qs.card.icon-color")
    val CARD_APP = TextKey("comp.qs.card.app")
    val CARD_TITLE = TextKey("comp.qs.card.title")
    val CARD_BODY = TextKey("comp.qs.card.body")
    val CARD_TITLE_COLOR = ColorKey("comp.qs.card.title-color")
    val CARD_TEXT_COLOR = ColorKey("comp.qs.card.text-color")
    /** A group's count and chevron, and its fill. */
    val CHIP_FILL = ColorKey("comp.qs.card.chip-fill")
    val CHIP_TEXT = TextKey("comp.qs.card.chip")
    /** A section's heading ("Silent") and its clear button. */
    val SECTION = TextKey("comp.qs.section.text")
    val SECTION_COLOR = ColorKey("comp.qs.section.color")
    val SECTION_HEIGHT = NumberKey("comp.qs.section.height")
    /** The row under the notifications: history and settings round, Clear all wide between them. */
    val CLEAR_FILL = ColorKey("comp.qs.clear.fill")
    val CLEAR_TEXT = TextKey("comp.qs.clear.text")
    val CLEAR_TEXT_COLOR = ColorKey("comp.qs.clear.text-color")
    val CLEAR_HEIGHT = NumberKey("comp.qs.clear.height")

    /** Every key, with the kind the code reads it as (the theme test checks each one resolves to it). */
    val KEYS: List<dev.launcher.app.design.Key<*>> = listOf(
        BACKGROUND, SIDE, SYMBOLS, CLOCK, DATE, CARRIER, HEADER_COLOR, CLOCK_TOP, DATE_TOP, DATE_GAP,
        BRIGHTNESS_TOP, BRIGHTNESS_HEIGHT, BRIGHTNESS_TRACK, BRIGHTNESS_LEVEL, BRIGHTNESS_SYMBOL,
        TILES_TOP, QUICK_TOP, TILE_HEIGHT, TILE_GAP, TILE, TILE_ON, TILE_CORNER, TILE_ON_CORNER, BOX, BOX_CORNER, BOX_ON_CORNER,
        BOX_MATERIAL, TILE_INSET, TILE_TEXT_GAP, SYMBOL, SYMBOL_COLOR, ON_SYMBOL_COLOR, LABEL, DETAIL, LABEL_COLOR, DETAIL_COLOR,
        ON_LABEL_COLOR, UNAVAILABLE_ALPHA, BUILD, BUILD_COLOR, DOT, DOT_COLOR, DOT_ACTIVE_COLOR,
        FOOTER_BUTTON, FOOTER_MATERIAL, FOOTER_SYMBOL, FOOTER_SYMBOL_COLOR, POWER_MATERIAL, POWER_SYMBOL_COLOR, FOOTER_BOTTOM,
        CARDS_TOP, SHELF, SHELF_CORNER, SHELF_INSET, ACTION_OUTLINE, CARD, CARD_CORNER, JOINED_CORNER, JOINED_GAP, CARD_GAP, CARD_PADDING, CARD_ICON, ICON_FILL, ICON_COLOR,
        CARD_APP, CARD_TITLE, CARD_BODY, CARD_TITLE_COLOR, CARD_TEXT_COLOR, CHIP_FILL, CHIP_TEXT, SECTION, SECTION_COLOR,
        SECTION_HEIGHT, CLEAR_FILL, CLEAR_TEXT, CLEAR_TEXT_COLOR, CLEAR_HEIGHT,
    )

    val ALL: List<String> = KEYS.map { it.name }
}
