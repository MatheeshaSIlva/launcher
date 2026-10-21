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
 * panel; its sizes are AOSP's (iOS has no such panel), read from Android 17's SystemUIGoogle (its resources and its compose
 * code) and measured on the emulator's own shade. Lengths are in points.
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
    /** The expanded header's height (Android's ShadeHeader ExpandedHeight 120 dp): Quick Settings start under it. */
    val HEADER_HEIGHT = NumberKey("comp.qs.header.height")
    /** The large clock's row (its top and height: the carrier is centred on it) and the date's row under it. */
    val CLOCK_TOP = NumberKey("comp.qs.header.clock-top")
    val CLOCK_ROW = NumberKey("comp.qs.header.clock-row")
    val DATE_TOP = NumberKey("comp.qs.header.date-top")
    val DATE_ROW = NumberKey("comp.qs.header.date-row")
    /** The first stage's date beside the status bar's time: the time's box at least this wide, the date this far after it. */
    val CLOCK_MIN_WIDTH = NumberKey("comp.qs.header.clock-min-width")
    val DATE_GAP = NumberKey("comp.qs.header.date-gap")
    /** As Quick Settings expand: how far the bar's row goes down as it leaves, how far above their places the expanded rows
     *  start (and the carrier), and the carrier's end inside the side. */
    val COLLAPSED_SHIFT = NumberKey("comp.qs.header.collapsed-shift")
    val EXPANDED_SHIFT = NumberKey("comp.qs.header.expanded-shift")
    val CARRIER_END = NumberKey("comp.qs.header.carrier-end")

    // ---- The brightness slider (Material 3 Expressive's: two tracks either side of a thin handle).
    /** Its row's top and height (the handle's), the tracks' height, their outer and inner corners, the gap either side of the handle and the handle's width. */
    val BRIGHTNESS_TOP = NumberKey("comp.qs.brightness.top")
    val BRIGHTNESS_HEIGHT = NumberKey("comp.qs.brightness.height")
    val BRIGHTNESS_TRACK_HEIGHT = NumberKey("comp.qs.brightness.track-height")
    val BRIGHTNESS_CORNER = NumberKey("comp.qs.brightness.corner")
    val BRIGHTNESS_INNER_CORNER = NumberKey("comp.qs.brightness.inner-corner")
    val BRIGHTNESS_GAP = NumberKey("comp.qs.brightness.gap")
    val BRIGHTNESS_THUMB_WIDTH = NumberKey("comp.qs.brightness.thumb-width")
    /** The symbol's size and its padding from the active track's end. */
    val BRIGHTNESS_ICON = NumberKey("comp.qs.brightness.icon")
    val BRIGHTNESS_ICON_PAD = NumberKey("comp.qs.brightness.icon-padding")
    val BRIGHTNESS_TRACK = MaterialKey("comp.qs.brightness.track")
    val BRIGHTNESS_LEVEL = MaterialKey("comp.qs.brightness.level")
    val BRIGHTNESS_THUMB = ColorKey("comp.qs.brightness.thumb-color")
    /** The symbol on the active track, and on the inactive one (where the active is too short to hold it). */
    val BRIGHTNESS_SYMBOL = ColorKey("comp.qs.brightness.symbol-color")
    val BRIGHTNESS_SYMBOL_OFF = ColorKey("comp.qs.brightness.symbol-off-color")

    // ---- Tiles.
    /** The grid's top at the second stage, and the quick row's below the status bar at the first. */
    val TILES_TOP = NumberKey("comp.qs.tiles.top")
    val QUICK_TOP = NumberKey("comp.qs.tiles.quick-top")
    /** How tall the quick row's tiles are, of their height, when the shade starts to come (they grow to whole as it opens). */
    val SQUISH = NumberKey("comp.qs.tiles.squish")
    val TILE_HEIGHT = NumberKey("comp.qs.tile.height")
    val TILE_GAP = NumberKey("comp.qs.tile.gap")
    /** A tile off (and a two-target tile on: its box lights), on, and one that cannot be used now. */
    val TILE = MaterialKey("comp.qs.tile.material")
    val TILE_ON = MaterialKey("comp.qs.tile.on-material")
    val TILE_UNAVAILABLE = MaterialKey("comp.qs.tile.unavailable-material")
    /** A tile's corner off (a capsule) and on (Android: 24 dp, only a tile lit whole); the change follows the state. */
    val TILE_CORNER = NumberKey("comp.qs.tile.corner")
    val TILE_ON_CORNER = NumberKey("comp.qs.tile.on-corner")
    /** A large tile's symbol box (its toggle), its corners off (a circle) and on, its material off (on: [TILE_ON]). */
    val BOX = NumberKey("comp.qs.tile.box")
    val BOX_CORNER = NumberKey("comp.qs.tile.box-corner")
    val BOX_ON_CORNER = NumberKey("comp.qs.tile.box-on-corner")
    val BOX_MATERIAL = MaterialKey("comp.qs.tile.box-material")
    /** A large tile's inset around its box, the space between the box and the words, and after the words. */
    val TILE_INSET = NumberKey("comp.qs.tile.inset")
    val TILE_TEXT_GAP = NumberKey("comp.qs.tile.text-gap")
    val TILE_END = NumberKey("comp.qs.tile.end-padding")
    /** A small tile's symbol, and a large tile's (in its box). */
    val SYMBOL = NumberKey("comp.qs.tile.symbol")
    val LARGE_SYMBOL = NumberKey("comp.qs.tile.large-symbol")
    val SYMBOL_COLOR = ColorKey("comp.qs.tile.symbol-color")
    val ON_SYMBOL_COLOR = ColorKey("comp.qs.tile.on-symbol-color")
    val LABEL = TextKey("comp.qs.tile.label")
    val DETAIL = TextKey("comp.qs.tile.detail")
    val LABEL_COLOR = ColorKey("comp.qs.tile.label-color")
    val DETAIL_COLOR = ColorKey("comp.qs.tile.detail-color")
    /** A tile's words when the whole tile is on (Android: onPrimary). */
    val ON_LABEL_COLOR = ColorKey("comp.qs.tile.on-label-color")
    /** What a tile that cannot be used now shows (Android: onSurfaceVariant at 38 %). */
    val UNAVAILABLE_COLOR = ColorKey("comp.qs.tile.unavailable-color")
    /** A pressed tile grows this far into its neighbours; a tapped one's symbol (one column) and words (two) pop to these. */
    val BOUNCE = NumberKey("comp.qs.tile.bounce")
    val ICON_POP = NumberKey("comp.qs.tile.icon-pop")
    val TEXT_POP = NumberKey("comp.qs.tile.text-pop")

    // ---- Under the tiles: the build number, the page dots, the edit button.
    val BUILD = TextKey("comp.qs.build.text")
    val BUILD_COLOR = ColorKey("comp.qs.build.color")
    /** The row's height (Android's FooterHeight 48 dp), right under the grid. */
    val BUILD_ROW = NumberKey("comp.qs.build.row")
    /** A page's dot, and the current page's (a pill). */
    val DOT = NumberKey("comp.qs.dots.size")
    val DOT_ACTIVE = NumberKey("comp.qs.dots.active-width")
    val DOT_COLOR = ColorKey("comp.qs.dots.color")
    val DOT_ACTIVE_COLOR = ColorKey("comp.qs.dots.active-color")
    val EDIT_SYMBOL = NumberKey("comp.qs.edit.symbol")
    val EDIT_COLOR = ColorKey("comp.qs.edit.color")

    // ---- The footer: apps at work, the user, settings, power (power lit).
    val FOOTER_BUTTON = NumberKey("comp.qs.footer.button")
    val FOOTER_MATERIAL = MaterialKey("comp.qs.footer.material")
    val FOOTER_SYMBOL = NumberKey("comp.qs.footer.symbol")
    val FOOTER_SYMBOL_COLOR = ColorKey("comp.qs.footer.symbol-color")
    val POWER_MATERIAL = MaterialKey("comp.qs.footer.power-material")
    val POWER_SYMBOL_COLOR = ColorKey("comp.qs.footer.power-symbol-color")
    /** The footer row's centre above the screen's bottom edge, and the space between its buttons. */
    val FOOTER_BOTTOM = NumberKey("comp.qs.footer.bottom")
    val FOOTER_GAP = NumberKey("comp.qs.footer.gap")
    /** "N apps are active": a pill at the footer's start. */
    val FOOTER_PILL = MaterialKey("comp.qs.footer.pill")
    val FOOTER_PILL_TEXT = TextKey("comp.qs.footer.pill-text")
    /** The pill's padding at its ends, and between its symbol and its words. */
    val FOOTER_PILL_PADDING = NumberKey("comp.qs.footer.pill-padding")
    val FOOTER_PILL_GAP = NumberKey("comp.qs.footer.pill-gap")

    // ---- Notifications as cards on the notification scrim.
    /** The first row's top inside the scrim. */
    val CARDS_TOP = NumberKey("comp.qs.cards.top")
    /** What the notifications lie on (Android's notification scrim: across the screen from under the quick row). */
    val SHELF = MaterialKey("comp.qs.cards.shelf")
    val SHELF_CORNER = NumberKey("comp.qs.cards.shelf-corner")
    /** Between the quick row and the scrim's top. */
    val SHELF_INSET = NumberKey("comp.qs.cards.shelf-inset")
    /** A card's actions: words in a row (Android's 2025 template). */
    val ACTION_COLOR = ColorKey("comp.qs.card.action-color")
    val ACTION_TEXT = TextKey("comp.qs.card.action")
    /** The actions' row: its height, how far in and apart they are, their words' padding, and the card's edge under it. */
    val ACTIONS_HEIGHT = NumberKey("comp.qs.card.actions-height")
    val ACTION_INSET = NumberKey("comp.qs.card.action-inset")
    val ACTION_PADDING = NumberKey("comp.qs.card.action-padding")
    val ACTIONS_BOTTOM = NumberKey("comp.qs.card.actions-bottom")
    /** An expanded card: its title under the app's line, its text under the title. */
    val TITLE_GAP = NumberKey("comp.qs.card.title-gap")
    val TEXT_GAP = NumberKey("comp.qs.card.text-gap")
    /** The chevron's pill at a card's top right. */
    val CHIP_WIDTH = NumberKey("comp.qs.card.chip-width")
    val CHIP_HEIGHT = NumberKey("comp.qs.card.chip-height")
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
    /** The row under the notifications: history and settings round, Clear all wide between them (while any can go). */
    val CLEAR_FILL = ColorKey("comp.qs.clear.fill")
    val CLEAR_TEXT = TextKey("comp.qs.clear.text")
    val CLEAR_TEXT_COLOR = ColorKey("comp.qs.clear.text-color")
    val CLEAR_HEIGHT = NumberKey("comp.qs.clear.height")
    /** The round buttons' width beside Clear all, and the row's space under the last card. */
    val CLEAR_SIDE = NumberKey("comp.qs.clear.side-width")
    val CLEAR_GAP = NumberKey("comp.qs.clear.gap")

    /** Every key, with the kind the code reads it as (the theme test checks each one resolves to it). */
    val KEYS: List<dev.launcher.app.design.Key<*>> = listOf(
        BACKGROUND, SIDE, SYMBOLS, CLOCK, DATE, CARRIER, HEADER_COLOR, HEADER_HEIGHT, CLOCK_TOP, CLOCK_ROW, DATE_TOP, DATE_ROW,
        CLOCK_MIN_WIDTH, DATE_GAP, COLLAPSED_SHIFT, EXPANDED_SHIFT, CARRIER_END,
        BRIGHTNESS_TOP, BRIGHTNESS_HEIGHT, BRIGHTNESS_TRACK_HEIGHT, BRIGHTNESS_CORNER, BRIGHTNESS_INNER_CORNER, BRIGHTNESS_GAP,
        BRIGHTNESS_THUMB_WIDTH, BRIGHTNESS_ICON, BRIGHTNESS_ICON_PAD, BRIGHTNESS_TRACK, BRIGHTNESS_LEVEL, BRIGHTNESS_THUMB,
        BRIGHTNESS_SYMBOL, BRIGHTNESS_SYMBOL_OFF,
        TILES_TOP, QUICK_TOP, SQUISH, TILE_HEIGHT, TILE_GAP, TILE, TILE_ON, TILE_UNAVAILABLE, TILE_CORNER, TILE_ON_CORNER,
        BOX, BOX_CORNER, BOX_ON_CORNER, BOX_MATERIAL, TILE_INSET, TILE_TEXT_GAP, TILE_END, SYMBOL, LARGE_SYMBOL, SYMBOL_COLOR,
        ON_SYMBOL_COLOR, LABEL, DETAIL, LABEL_COLOR, DETAIL_COLOR, ON_LABEL_COLOR, UNAVAILABLE_COLOR, BOUNCE, ICON_POP, TEXT_POP,
        BUILD, BUILD_COLOR, BUILD_ROW, DOT, DOT_ACTIVE, DOT_COLOR, DOT_ACTIVE_COLOR, EDIT_SYMBOL, EDIT_COLOR,
        FOOTER_BUTTON, FOOTER_MATERIAL, FOOTER_SYMBOL, FOOTER_SYMBOL_COLOR, POWER_MATERIAL, POWER_SYMBOL_COLOR, FOOTER_BOTTOM,
        FOOTER_GAP, FOOTER_PILL, FOOTER_PILL_TEXT, FOOTER_PILL_PADDING, FOOTER_PILL_GAP,
        CARDS_TOP, SHELF, SHELF_CORNER, SHELF_INSET, ACTION_COLOR, ACTION_TEXT, ACTIONS_HEIGHT, ACTION_INSET, ACTION_PADDING,
        ACTIONS_BOTTOM, TITLE_GAP, TEXT_GAP, CHIP_WIDTH, CHIP_HEIGHT, CARD, CARD_CORNER, JOINED_CORNER, JOINED_GAP,
        CARD_GAP, CARD_PADDING, CARD_ICON, ICON_FILL, ICON_COLOR, CARD_APP, CARD_TITLE, CARD_BODY, CARD_TITLE_COLOR,
        CARD_TEXT_COLOR, CHIP_FILL, CHIP_TEXT, SECTION, SECTION_COLOR, SECTION_HEIGHT, CLEAR_FILL, CLEAR_TEXT, CLEAR_TEXT_COLOR,
        CLEAR_HEIGHT, CLEAR_SIDE, CLEAR_GAP,
    )

    val ALL: List<String> = KEYS.map { it.name }
}
