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
    /** Material's press ripple (tiles, a tile's box, the footer's buttons): the content colour's share while pressed, the
     *  circle's first size (a part of the target's longer side) and how far past the target's half diagonal it grows. */
    val RIPPLE_ALPHA = NumberKey("comp.qs.ripple.alpha")
    val RIPPLE_START = NumberKey("comp.qs.ripple.start")
    val RIPPLE_EXTRA = NumberKey("comp.qs.ripple.extra")
    /** Words that do not fit: the fade at their end, and the marquee (its speed a second, the wait before it, the rest
     *  between passes, the gap before the words come round, as a part of the width). */
    val LABEL_FADE = NumberKey("comp.qs.tile.label-fade")
    val MARQUEE_SPEED = NumberKey("comp.qs.tile.marquee-speed")
    val MARQUEE_DELAY = NumberKey("comp.qs.tile.marquee-delay")
    val MARQUEE_REST = NumberKey("comp.qs.tile.marquee-rest")
    val MARQUEE_SPACING = NumberKey("comp.qs.tile.marquee-spacing")

    // ---- Under the tiles: the build number, the page dots, the edit button.
    val BUILD = TextKey("comp.qs.build.text")
    val BUILD_COLOR = ColorKey("comp.qs.build.color")
    /** The row's height (Android's FooterHeight 48 dp), right under the grid. */
    val BUILD_ROW = NumberKey("comp.qs.build.row")
    /** A page's dot, and the current page's (a pill). */
    val DOT = NumberKey("comp.qs.dots.size")
    val DOT_ACTIVE = NumberKey("comp.qs.dots.active-width")
    val DOT_GAP = NumberKey("comp.qs.dots.gap")
    val DOT_COLOR = ColorKey("comp.qs.dots.color")
    val DOT_ACTIVE_COLOR = ColorKey("comp.qs.dots.active-color")
    val EDIT_SYMBOL = NumberKey("comp.qs.edit.symbol")
    val EDIT_COLOR = ColorKey("comp.qs.edit.color")

    // ---- The footer: apps at work, the user, settings, power (power lit).
    val FOOTER_BUTTON = NumberKey("comp.qs.footer.button")
    val FOOTER_MATERIAL = MaterialKey("comp.qs.footer.material")
    val FOOTER_SYMBOL = NumberKey("comp.qs.footer.symbol")
    val FOOTER_SYMBOL_COLOR = ColorKey("comp.qs.footer.symbol-color")
    /** The user's button: the default avatar's colour. */
    val FOOTER_USER_COLOR = ColorKey("comp.qs.footer.user-color")
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
    /** The buttons drawn inside the row (its height is their touch), the space between them, and their symbols. */
    val CLEAR_INSET = NumberKey("comp.qs.clear.inset")
    val CLEAR_SPACING = NumberKey("comp.qs.clear.spacing")
    val CLEAR_SYMBOL = NumberKey("comp.qs.clear.symbol")
    /**
     * Inside an opened group: the hairline above each child (its colour, its width), as the gap between children shows what
     * is behind them in Android 17 (0.5 dp; the scrim's colour).
     */
    val DIVIDER = ColorKey("comp.qs.card.divider")
    val DIVIDER_WIDTH = NumberKey("comp.qs.card.divider-width")
    /**
     * A closed group's one-line rows ("Title Text", Android's hybrid view): one row's height, the card's top to the first row,
     * the last row to the card's bottom, and the space between the title and the text.
     */
    val GROUP_LINE = NumberKey("comp.qs.card.group-line")
    val GROUP_LINES_TOP = NumberKey("comp.qs.card.group-lines-top")
    val GROUP_LINES_BOTTOM = NumberKey("comp.qs.card.group-lines-bottom")
    val GROUP_LINE_GAP = NumberKey("comp.qs.card.group-line-gap")
    /** A pressed card's or footer button's ripple (the platform's highlight colour, whole). */
    val CARD_RIPPLE = ColorKey("comp.qs.card.ripple")
    /** How much of its width a swiped card has gone when its content has faded out (its surface stays whole). */
    val CARD_SWIPE_FADE_END = NumberKey("comp.qs.card.swipe-fade-end")

    // ---- The edit page (Android 17's "Edit tiles": the current tiles to choose, rearrange and resize; the tiles to add by
    // category; Reset).
    /** The bar's row (back, undo, Remove): its centre under the status bar, its height (the page scrolls under its bottom). */
    val EDIT_BAR_CENTER = NumberKey("comp.qs.edit-page.bar-center")
    val EDIT_BAR_HEIGHT = NumberKey("comp.qs.edit-page.bar-height")
    /** The back arrow's start inside the page, and the bar's symbols' size. */
    val EDIT_BAR_START = NumberKey("comp.qs.edit-page.bar-start")
    val EDIT_BAR_SYMBOL = NumberKey("comp.qs.edit-page.bar-symbol")
    /** The title ("Edit tiles"): where its words start under the status bar, where it sits in the bar once scrolled away, and the line under it. */
    val EDIT_TITLE = TextKey("comp.qs.edit-page.title")
    val EDIT_TITLE_TOP = NumberKey("comp.qs.edit-page.title-top")
    val EDIT_COLLAPSED_TITLE_START = NumberKey("comp.qs.edit-page.collapsed-title-start")
    val EDIT_SUBTITLE = TextKey("comp.qs.edit-page.subtitle")
    /** The page's words (the title, its line, the categories' names, the tiles' names). */
    val EDIT_TEXT_COLOR = ColorKey("comp.qs.edit-page.text-color")
    /** The current tiles' box: its top under the status bar, its padding and corners, its border and fill. */
    val EDIT_GRID_TOP = NumberKey("comp.qs.edit-page.grid-top")
    val EDIT_GRID_PADDING = NumberKey("comp.qs.edit-page.grid-padding")
    val EDIT_GRID_CORNER = NumberKey("comp.qs.edit-page.grid-corner")
    val EDIT_GRID_STROKE = NumberKey("comp.qs.edit-page.grid-stroke")
    val EDIT_GRID_FILL = MaterialKey("comp.qs.edit-page.grid-fill")
    /** The page's accent (the box's border, a chosen tile's outline and handle, the badges, the buttons) and what is drawn on it. */
    val EDIT_ACCENT = ColorKey("comp.qs.edit-page.accent")
    val EDIT_ON_ACCENT = ColorKey("comp.qs.edit-page.on-accent")
    /** Between tiles on the page. */
    val EDIT_TILE_GAP = NumberKey("comp.qs.edit-page.tile-gap")
    /** A chosen tile: its outline, the resize handle on it at its end (a pill) and the handle's target. */
    val EDIT_SELECTION_STROKE = NumberKey("comp.qs.edit-page.selection-stroke")
    val EDIT_HANDLE_WIDTH = NumberKey("comp.qs.edit-page.handle-width")
    val EDIT_HANDLE_HEIGHT = NumberKey("comp.qs.edit-page.handle-height")
    val EDIT_HANDLE_TARGET = NumberKey("comp.qs.edit-page.handle-target")
    /** Dragging: where the tile will land, the tile left at its place while lifted, and the tile under the finger. */
    val EDIT_SLOT_COLOR = ColorKey("comp.qs.edit-page.slot-color")
    val EDIT_LIFTED_ALPHA = NumberKey("comp.qs.edit-page.lifted-alpha")
    val EDIT_SHADOW_ALPHA = NumberKey("comp.qs.edit-page.shadow-alpha")
    /** The tiles to add, by category: boxes under the current tiles' box, 2 dp apart (the first and last rounded), each with its heading. */
    val EDIT_SECTION_GAP = NumberKey("comp.qs.edit-page.section-gap")
    val EDIT_CATEGORY = MaterialKey("comp.qs.edit-page.category")
    val EDIT_CATEGORY_GAP = NumberKey("comp.qs.edit-page.category-gap")
    val EDIT_CATEGORY_INNER_CORNER = NumberKey("comp.qs.edit-page.category-inner-corner")
    val EDIT_CATEGORY_PADDING = NumberKey("comp.qs.edit-page.category-padding")
    val EDIT_CATEGORY_TEXT = TextKey("comp.qs.edit-page.category-text")
    val EDIT_CATEGORY_SYMBOL = NumberKey("comp.qs.edit-page.category-symbol")
    val EDIT_CATEGORY_SYMBOL_GAP = NumberKey("comp.qs.edit-page.category-symbol-gap")
    val EDIT_CATEGORY_TILES = NumberKey("comp.qs.edit-page.category-tiles-top")
    /** A tile to add: its name under it (two lines at most), the rows' gap, how faint one already added is. */
    val EDIT_CELL_LABEL = TextKey("comp.qs.edit-page.cell-label")
    val EDIT_CELL_LABEL_GAP = NumberKey("comp.qs.edit-page.cell-label-gap")
    val EDIT_CELL_ROW_GAP = NumberKey("comp.qs.edit-page.cell-row-gap")
    val EDIT_ADDED_ALPHA = NumberKey("comp.qs.edit-page.added-alpha")
    /** The add badge on a tile to add: its size, its centre from the tile's top and end, its plus. */
    val EDIT_BADGE = NumberKey("comp.qs.edit-page.badge")
    val EDIT_BADGE_TOP = NumberKey("comp.qs.edit-page.badge-top")
    val EDIT_BADGE_END = NumberKey("comp.qs.edit-page.badge-end")
    val EDIT_BADGE_SYMBOL = NumberKey("comp.qs.edit-page.badge-symbol")
    /** The bar's Remove button (a pill): its words, height and padding; off (no tile chosen) faint. */
    val EDIT_BUTTON_TEXT = TextKey("comp.qs.edit-page.button-text")
    val EDIT_BUTTON_HEIGHT = NumberKey("comp.qs.edit-page.button-height")
    val EDIT_BUTTON_PADDING = NumberKey("comp.qs.edit-page.button-padding")
    val EDIT_BUTTON_OFF_FILL = ColorKey("comp.qs.edit-page.button-off-fill")
    val EDIT_BUTTON_OFF_TEXT = ColorKey("comp.qs.edit-page.button-off-text")
    /** Undo (once something changed): a round button before Remove. */
    val EDIT_UNDO = NumberKey("comp.qs.edit-page.undo")
    val EDIT_UNDO_MATERIAL = MaterialKey("comp.qs.edit-page.undo-material")
    /** Reset, under the categories: its gap above, its height and padding, the room under it to the page's end. */
    val EDIT_RESET_TOP = NumberKey("comp.qs.edit-page.reset-top")
    val EDIT_RESET_HEIGHT = NumberKey("comp.qs.edit-page.reset-height")
    val EDIT_RESET_PADDING = NumberKey("comp.qs.edit-page.reset-padding")
    val EDIT_PAGE_BOTTOM = NumberKey("comp.qs.edit-page.page-bottom")
    /** Reset's question ("Reset all tiles?"): its box over the dimmed page, its words and buttons. */
    val EDIT_DIALOG = MaterialKey("comp.qs.edit-page.dialog")
    val EDIT_DIALOG_CORNER = NumberKey("comp.qs.edit-page.dialog-corner")
    val EDIT_DIALOG_PADDING = NumberKey("comp.qs.edit-page.dialog-padding")
    val EDIT_DIALOG_TITLE = TextKey("comp.qs.edit-page.dialog-title")
    val EDIT_DIALOG_TEXT = TextKey("comp.qs.edit-page.dialog-text")
    val EDIT_DIALOG_TEXT_COLOR = ColorKey("comp.qs.edit-page.dialog-text-color")
    val EDIT_DIALOG_TEXT_GAP = NumberKey("comp.qs.edit-page.dialog-text-gap")
    val EDIT_DIALOG_BUTTONS_GAP = NumberKey("comp.qs.edit-page.dialog-buttons-gap")
    val EDIT_DIALOG_BUTTON_PADDING = NumberKey("comp.qs.edit-page.dialog-button-padding")
    val EDIT_DIALOG_SCRIM = ColorKey("comp.qs.edit-page.dialog-scrim")
    /** Remove's end inside the page; Cancel's padding and its gap before OK; a pressed button's layer; a dialog's growth; a
     *  drag's scrolling near the page's ends (its zone and speed). */
    val EDIT_BAR_END = NumberKey("comp.qs.edit-page.bar-end")
    val EDIT_TEXT_BUTTON_PADDING = NumberKey("comp.qs.edit-page.text-button-padding")
    val EDIT_DIALOG_BUTTON_GAP = NumberKey("comp.qs.edit-page.dialog-button-gap")
    val EDIT_PRESS_LAYER = NumberKey("comp.qs.edit-page.press-layer")
    val EDIT_DIALOG_FROM = NumberKey("comp.qs.edit-page.dialog-from")
    val EDIT_AUTO_SCROLL_ZONE = NumberKey("comp.qs.edit-page.auto-scroll-zone")
    val EDIT_AUTO_SCROLL_SPEED = NumberKey("comp.qs.edit-page.auto-scroll-speed")

    /** Every key, with the kind the code reads it as (the theme test checks each one resolves to it). */
    // ---- The media player (Android 17's remedia: the layout file's `media` block).
    /** The card: its height and corners, its padding; under the quick row at the first stage, under the build number in Quick Settings. */
    val MEDIA_HEIGHT = NumberKey("comp.qs.media.height")
    val MEDIA_CORNER = NumberKey("comp.qs.media.corner")
    val MEDIA_PADDING = NumberKey("comp.qs.media.padding")
    val MEDIA_TOP_GAP = NumberKey("comp.qs.media.top-gap")
    val MEDIA_QS_GAP = NumberKey("comp.qs.media.qs-gap")
    /** The app's symbol at the top left; the output chip at the top right (outlined, with the cast symbol). */
    val MEDIA_APP_SYMBOL = NumberKey("comp.qs.media.app-symbol")
    val MEDIA_CHIP_WIDTH = NumberKey("comp.qs.media.chip-width")
    val MEDIA_CHIP_HEIGHT = NumberKey("comp.qs.media.chip-height")
    val MEDIA_CHIP_STROKE = NumberKey("comp.qs.media.chip-stroke")
    val MEDIA_CHIP_SYMBOL = NumberKey("comp.qs.media.chip-symbol")
    /** The title and the artist, white, centred on the play button. */
    val MEDIA_TITLE = TextKey("comp.qs.media.title")
    val MEDIA_ARTIST = TextKey("comp.qs.media.artist")
    val MEDIA_TEXT_COLOR = ColorKey("comp.qs.media.text-color")
    /** The play button: its size and place (its top under the card's), its corners playing, paused and pressed, its symbol. */
    val MEDIA_PLAY_WIDTH = NumberKey("comp.qs.media.play-width")
    val MEDIA_PLAY_HEIGHT = NumberKey("comp.qs.media.play-height")
    val MEDIA_PLAY_TOP = NumberKey("comp.qs.media.play-top")
    val MEDIA_PLAY_CORNER = NumberKey("comp.qs.media.play-corner")
    val MEDIA_PAUSED_CORNER = NumberKey("comp.qs.media.paused-corner")
    val MEDIA_PRESSED_CORNER = NumberKey("comp.qs.media.pressed-corner")
    val MEDIA_PLAY_SYMBOL = NumberKey("comp.qs.media.play-symbol")
    /** Previous and next (their targets, symbols, the gap to the line, the row's centre under the card's top). */
    val MEDIA_NAV = NumberKey("comp.qs.media.nav")
    val MEDIA_NAV_SYMBOL = NumberKey("comp.qs.media.nav-symbol")
    val MEDIA_NAV_GAP = NumberKey("comp.qs.media.nav-gap")
    val MEDIA_NAV_CENTER = NumberKey("comp.qs.media.nav-centre")
    /** The line: the wave up to the thumb (its length, height, stroke and travel a second), the faint track after it, the thumb. */
    val MEDIA_WAVE_LENGTH = NumberKey("comp.qs.media.wave-length")
    val MEDIA_WAVE_AMPLITUDE = NumberKey("comp.qs.media.wave-amplitude")
    val MEDIA_WAVE_STROKE = NumberKey("comp.qs.media.wave-stroke")
    val MEDIA_WAVE_SPEED = NumberKey("comp.qs.media.wave-speed")
    val MEDIA_TRACK = NumberKey("comp.qs.media.track")
    val MEDIA_TRACK_ALPHA = NumberKey("comp.qs.media.track-alpha")
    val MEDIA_THUMB_WIDTH = NumberKey("comp.qs.media.thumb-width")
    val MEDIA_THUMB_HEIGHT = NumberKey("comp.qs.media.thumb-height")
    /** The colours from the art: the art's strength over the ground; the ground's, the light's (the play button, the app's symbol, the chip) and the play symbol's tones of the art's hue; the hue's least chroma; the colour without art. */
    val MEDIA_ART_ALPHA = NumberKey("comp.qs.media.art-alpha")
    val MEDIA_ART_CENTER_ALPHA = NumberKey("comp.qs.media.art-centre-alpha")
    val MEDIA_GROUND_TONE = NumberKey("comp.qs.media.ground-tone")
    val MEDIA_LIGHT_TONE = NumberKey("comp.qs.media.light-tone")
    val MEDIA_ON_LIGHT_TONE = NumberKey("comp.qs.media.on-light-tone")
    val MEDIA_CHROMA = NumberKey("comp.qs.media.chroma")
    val MEDIA_GROUND_CHROMA = NumberKey("comp.qs.media.ground-chroma")
    val MEDIA_FALLBACK = ColorKey("comp.qs.media.fallback")
    val MEDIA_PRESS_LAYER = NumberKey("comp.qs.media.press-layer")
    /** Swiped aside, the card uncovers a round settings button: its size, the room either side of it, its colours. */
    val MEDIA_REVEAL_BUTTON = NumberKey("comp.qs.media.reveal-button")
    val MEDIA_REVEAL_MARGIN = NumberKey("comp.qs.media.reveal-margin")
    val MEDIA_REVEAL_FILL = ColorKey("comp.qs.media.reveal-fill")
    val MEDIA_REVEAL_SYMBOL = ColorKey("comp.qs.media.reveal-symbol")

    // ---- Turned (the layout's landscape "split", Android's own).
    /** Turned (the layout's landscape "split"): between the two halves; the slider's and the tiles' tops under the status bar; the notifications' width. */
    val LAND_MIDDLE = NumberKey("comp.qs.land.middle")
    val LAND_BRIGHTNESS_TOP = NumberKey("comp.qs.land.brightness-top")
    val LAND_TILES_TOP = NumberKey("comp.qs.land.tiles-top")
    val LAND_CARDS_WIDTH = NumberKey("comp.qs.land.cards-width")
    val MEDIA_COMPACT_HEIGHT = NumberKey("comp.qs.media.compact-height")

    val KEYS: List<dev.launcher.app.design.Key<*>> = listOf(
        LAND_MIDDLE, LAND_BRIGHTNESS_TOP, LAND_TILES_TOP, LAND_CARDS_WIDTH, MEDIA_COMPACT_HEIGHT,
        MEDIA_HEIGHT, MEDIA_CORNER, MEDIA_PADDING, MEDIA_TOP_GAP, MEDIA_QS_GAP, MEDIA_APP_SYMBOL, MEDIA_CHIP_WIDTH,
        MEDIA_CHIP_HEIGHT, MEDIA_CHIP_STROKE, MEDIA_CHIP_SYMBOL, MEDIA_TITLE, MEDIA_ARTIST, MEDIA_TEXT_COLOR,
        MEDIA_PLAY_WIDTH, MEDIA_PLAY_HEIGHT, MEDIA_PLAY_TOP, MEDIA_PLAY_CORNER, MEDIA_PAUSED_CORNER, MEDIA_PRESSED_CORNER,
        MEDIA_PLAY_SYMBOL, MEDIA_NAV, MEDIA_NAV_SYMBOL, MEDIA_NAV_GAP, MEDIA_NAV_CENTER, MEDIA_WAVE_LENGTH,
        MEDIA_WAVE_AMPLITUDE, MEDIA_WAVE_STROKE, MEDIA_WAVE_SPEED, MEDIA_TRACK, MEDIA_TRACK_ALPHA, MEDIA_THUMB_WIDTH,
        MEDIA_THUMB_HEIGHT, MEDIA_ART_ALPHA, MEDIA_ART_CENTER_ALPHA, MEDIA_GROUND_TONE, MEDIA_LIGHT_TONE, MEDIA_ON_LIGHT_TONE, MEDIA_CHROMA,
        MEDIA_GROUND_CHROMA,
        MEDIA_FALLBACK, MEDIA_PRESS_LAYER, MEDIA_REVEAL_BUTTON, MEDIA_REVEAL_MARGIN, MEDIA_REVEAL_FILL, MEDIA_REVEAL_SYMBOL,
        EDIT_BAR_CENTER, EDIT_BAR_HEIGHT, EDIT_BAR_START, EDIT_BAR_SYMBOL, EDIT_TITLE, EDIT_TITLE_TOP,
        EDIT_COLLAPSED_TITLE_START, EDIT_SUBTITLE, EDIT_TEXT_COLOR, EDIT_GRID_TOP, EDIT_GRID_PADDING, EDIT_GRID_CORNER,
        EDIT_GRID_STROKE, EDIT_GRID_FILL, EDIT_ACCENT, EDIT_ON_ACCENT, EDIT_TILE_GAP, EDIT_SELECTION_STROKE,
        EDIT_HANDLE_WIDTH, EDIT_HANDLE_HEIGHT, EDIT_HANDLE_TARGET, EDIT_SLOT_COLOR, EDIT_LIFTED_ALPHA, EDIT_SHADOW_ALPHA,
        EDIT_SECTION_GAP, EDIT_CATEGORY, EDIT_CATEGORY_GAP, EDIT_CATEGORY_INNER_CORNER, EDIT_CATEGORY_PADDING,
        EDIT_CATEGORY_TEXT, EDIT_CATEGORY_SYMBOL, EDIT_CATEGORY_SYMBOL_GAP, EDIT_CATEGORY_TILES, EDIT_CELL_LABEL,
        EDIT_CELL_LABEL_GAP, EDIT_CELL_ROW_GAP, EDIT_ADDED_ALPHA, EDIT_BADGE, EDIT_BADGE_TOP, EDIT_BADGE_END,
        EDIT_BADGE_SYMBOL, EDIT_BUTTON_TEXT, EDIT_BUTTON_HEIGHT, EDIT_BUTTON_PADDING, EDIT_BUTTON_OFF_FILL,
        EDIT_BUTTON_OFF_TEXT, EDIT_UNDO, EDIT_UNDO_MATERIAL, EDIT_RESET_TOP, EDIT_RESET_HEIGHT, EDIT_RESET_PADDING,
        EDIT_PAGE_BOTTOM, EDIT_DIALOG, EDIT_DIALOG_CORNER, EDIT_DIALOG_PADDING, EDIT_DIALOG_TITLE, EDIT_DIALOG_TEXT,
        EDIT_DIALOG_TEXT_COLOR, EDIT_DIALOG_TEXT_GAP, EDIT_DIALOG_BUTTONS_GAP, EDIT_DIALOG_BUTTON_PADDING,
        EDIT_DIALOG_SCRIM, EDIT_BAR_END, EDIT_TEXT_BUTTON_PADDING, EDIT_DIALOG_BUTTON_GAP, EDIT_PRESS_LAYER, EDIT_DIALOG_FROM, EDIT_AUTO_SCROLL_ZONE, EDIT_AUTO_SCROLL_SPEED,
        BACKGROUND, SIDE, SYMBOLS, CLOCK, DATE, CARRIER, HEADER_COLOR, HEADER_HEIGHT, CLOCK_TOP, CLOCK_ROW, DATE_TOP, DATE_ROW,
        CLOCK_MIN_WIDTH, DATE_GAP, COLLAPSED_SHIFT, EXPANDED_SHIFT, CARRIER_END,
        BRIGHTNESS_TOP, BRIGHTNESS_HEIGHT, BRIGHTNESS_TRACK_HEIGHT, BRIGHTNESS_CORNER, BRIGHTNESS_INNER_CORNER, BRIGHTNESS_GAP,
        BRIGHTNESS_THUMB_WIDTH, BRIGHTNESS_ICON, BRIGHTNESS_ICON_PAD, BRIGHTNESS_TRACK, BRIGHTNESS_LEVEL, BRIGHTNESS_THUMB,
        BRIGHTNESS_SYMBOL, BRIGHTNESS_SYMBOL_OFF,
        TILES_TOP, QUICK_TOP, SQUISH, TILE_HEIGHT, TILE_GAP, TILE, TILE_ON, TILE_UNAVAILABLE, TILE_CORNER, TILE_ON_CORNER,
        BOX, BOX_CORNER, BOX_ON_CORNER, BOX_MATERIAL, TILE_INSET, TILE_TEXT_GAP, TILE_END, SYMBOL, LARGE_SYMBOL, SYMBOL_COLOR,
        ON_SYMBOL_COLOR, LABEL, DETAIL, LABEL_COLOR, DETAIL_COLOR, ON_LABEL_COLOR, UNAVAILABLE_COLOR, BOUNCE, ICON_POP, TEXT_POP, RIPPLE_ALPHA, RIPPLE_START, RIPPLE_EXTRA, LABEL_FADE, MARQUEE_SPEED, MARQUEE_DELAY, MARQUEE_REST, MARQUEE_SPACING,
        BUILD, BUILD_COLOR, BUILD_ROW, DOT, DOT_ACTIVE, DOT_GAP, DOT_COLOR, DOT_ACTIVE_COLOR, EDIT_SYMBOL, EDIT_COLOR,
        FOOTER_BUTTON, FOOTER_MATERIAL, FOOTER_SYMBOL, FOOTER_SYMBOL_COLOR, FOOTER_USER_COLOR, POWER_MATERIAL, POWER_SYMBOL_COLOR, FOOTER_BOTTOM,
        FOOTER_GAP, FOOTER_PILL, FOOTER_PILL_TEXT, FOOTER_PILL_PADDING, FOOTER_PILL_GAP,
        CARDS_TOP, SHELF, SHELF_CORNER, SHELF_INSET, ACTION_COLOR, ACTION_TEXT, ACTIONS_HEIGHT, ACTION_INSET, ACTION_PADDING,
        ACTIONS_BOTTOM, TITLE_GAP, TEXT_GAP, CHIP_WIDTH, CHIP_HEIGHT, CARD, CARD_CORNER, JOINED_CORNER, JOINED_GAP,
        CARD_GAP, CARD_PADDING, CARD_ICON, ICON_FILL, ICON_COLOR, CARD_APP, CARD_TITLE, CARD_BODY, CARD_TITLE_COLOR,
        CARD_TEXT_COLOR, CHIP_FILL, CHIP_TEXT, SECTION, SECTION_COLOR, SECTION_HEIGHT, CLEAR_FILL, CLEAR_TEXT, CLEAR_TEXT_COLOR,
        CLEAR_HEIGHT, CLEAR_SIDE, CLEAR_GAP, CLEAR_INSET, CLEAR_SPACING, CLEAR_SYMBOL, CARD_RIPPLE, CARD_SWIPE_FADE_END,
        DIVIDER, DIVIDER_WIDTH, GROUP_LINE, GROUP_LINES_TOP, GROUP_LINES_BOTTOM, GROUP_LINE_GAP,
    )

    val ALL: List<String> = KEYS.map { it.name }
}
