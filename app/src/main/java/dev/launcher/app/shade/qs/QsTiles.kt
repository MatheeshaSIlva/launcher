package dev.launcher.app.shade.qs

import dev.launcher.app.R
import dev.launcher.app.shade.AppTiles
import dev.launcher.app.shade.Control
import dev.launcher.app.shade.ControlState

/**
 * Quick Settings' tiles as Android has them (the `tiles` block of a layout's `shade` panel): our controls under Android's
 * names, or an app's own tile ([tile]: its component, run by SystemUI through [AppTiles]), each one column wide (its symbol
 * alone) or two (a box with its symbol, its name and state). A two-column tile whose control turns on and off has two
 * targets, as Android's: its box toggles, the rest opens the control's page. Pure: [ControlState] says what is available
 * and on.
 */
class QsTile(val control: Control, val wide: Boolean, val label: String, val tile: String? = null) {
    /** What it is, whatever its size: its control, or the app's tile it runs. */
    val key: String get() = tile?.let { "app:$it" } ?: control.name
    /** It turns on and off (a toggle); else a tap opens what it names (Quick Share, the camera...). */
    val toggles: Boolean get() = control.kind == Control.Kind.TOGGLE || control == Control.FOCUS
    /** A wide toggle: its box and the rest of it are two targets (Android's `handlesToggleClick`). */
    val dualTarget: Boolean get() = wide && toggles

    /** The same tile one column wide or two. */
    fun sized(wide: Boolean) = if (wide == this.wide) this else QsTile(control, wide, label, tile)
}

/** A tile's state as Android draws it (Tile.STATE_*): its colours, its corners. */
enum class TileState { UNAVAILABLE, INACTIVE, ACTIVE }

/** Where Android's edit page lists a tile it can add (TileCategory: its heading and symbol, in this order). */
enum class TileCategory(val title: String, val symbol: Int) {
    CONNECTIVITY("Connectivity", R.drawable.sym_qs_cat_connectivity),
    UTILITIES("Utilities", R.drawable.sym_qs_cat_utilities),
    DISPLAY("Display", R.drawable.sym_qs_cat_display),
    PRIVACY("Privacy", R.drawable.sym_qs_cat_privacy),
    ACCESSIBILITY("Accessibility", R.drawable.sym_qs_cat_accessibility),
    SYSTEM_APPS("From system apps", R.drawable.sym_qs_cat_system_apps),
    APPS("From apps", R.drawable.sym_qs_cat_apps),
}

object QsTiles {
    /**
     * A Pixel's tiles, in its order (Android 17's SystemUIGoogle: Wi-Fi, Bluetooth, Mobile data, Quick Share, Do Not Disturb
     * and Wallet large; Airplane mode, Auto-rotate, Flashlight, Battery Saver, Alarm, Device controls and Cast small), as far
     * as we have them (Screen record has no control of ours yet).
     */
    val DEFAULT: List<QsTile> = listOf(
        QsTile(Control.WIFI, true, "Wi-Fi"),
        QsTile(Control.BLUETOOTH, true, "Bluetooth"),
        QsTile(Control.CELLULAR, true, "Mobile data"),
        QsTile(Control.QUICK_SHARE, true, "Quick Share"),
        QsTile(Control.FOCUS, true, "Modes"),
        QsTile(Control.WALLET, true, "Wallet"),
        QsTile(Control.AIRPLANE, false, "Airplane mode"),
        QsTile(Control.ROTATION_LOCK, false, "Auto-rotate"),
        QsTile(Control.FLASHLIGHT, false, "Flashlight"),
        QsTile(Control.LOW_POWER, false, "Battery Saver"),
        QsTile(Control.ALARM, false, "Alarm"),
        QsTile(Control.HOME, false, "Device controls"),
        QsTile(Control.MIRRORING, false, "Cast"),
    )

    /**
     * Where Android's edit page lists an app's tile: a system app's in the category it declares (Settings' Audio streams in
     * Connectivity), else "From system apps"; any other app's "From apps".
     */
    fun categoryOf(a: AppTiles.Info): TileCategory = if (!a.system) TileCategory.APPS else when (a.category?.substringAfterLast('.')) {
        "CATEGORY_CONNECTIVITY" -> TileCategory.CONNECTIVITY
        "CATEGORY_UTILITIES" -> TileCategory.UTILITIES
        "CATEGORY_DISPLAY" -> TileCategory.DISPLAY
        "CATEGORY_PRIVACY" -> TileCategory.PRIVACY
        "CATEGORY_ACCESSIBILITY" -> TileCategory.ACCESSIBILITY
        else -> TileCategory.SYSTEM_APPS
    }

    /** The name a tile shows in Quick Settings: Do Not Disturb's while it is on, else "Modes" (Android 16's Modes tile). */
    fun shownName(state: ControlState, t: QsTile): String = if (t.control == Control.FOCUS && state.dnd) "Do Not Disturb" else name(t)

    /** One that can be added: a control of ours under Android's name, where Android's edit page lists it. */
    class Entry(val control: Control, val label: String, val category: TileCategory)

    /**
     * Every control of ours that Android has a tile for, under its name and in its category (Android 17's edit page lists
     * each category's tiles by name). Android's tiles we have no control for (Audio streams, Keyboard, Screen record,
     * Camera and Mic access...) are left out, as Android leaves out what the phone cannot do.
     */
    val CATALOG: List<Entry> = listOf(
        Entry(Control.AIRPLANE, "Airplane mode", TileCategory.CONNECTIVITY),
        Entry(Control.BLUETOOTH, "Bluetooth", TileCategory.CONNECTIVITY),
        Entry(Control.MIRRORING, "Cast", TileCategory.CONNECTIVITY),
        Entry(Control.DATA_SAVER, "Data Saver", TileCategory.CONNECTIVITY),
        Entry(Control.HOTSPOT, "Hotspot", TileCategory.CONNECTIVITY),
        Entry(Control.CELLULAR, "Mobile data", TileCategory.CONNECTIVITY),
        Entry(Control.NFC, "NFC", TileCategory.CONNECTIVITY),
        Entry(Control.WIFI, "Wi-Fi", TileCategory.CONNECTIVITY),
        Entry(Control.ALARM, "Alarm", TileCategory.UTILITIES),
        Entry(Control.LOW_POWER, "Battery Saver", TileCategory.UTILITIES),
        Entry(Control.HOME, "Device controls", TileCategory.UTILITIES),
        Entry(Control.FLASHLIGHT, "Flashlight", TileCategory.UTILITIES),
        Entry(Control.FOCUS, "Modes", TileCategory.UTILITIES),
        Entry(Control.SCAN_CODE, "QR code scanner", TileCategory.UTILITIES),
        Entry(Control.WALLET, "Wallet", TileCategory.UTILITIES),
        Entry(Control.ROTATION_LOCK, "Auto-rotate", TileCategory.DISPLAY),
        Entry(Control.DARK_MODE, "Dark theme", TileCategory.DISPLAY),
        Entry(Control.TEXT_SIZE, "Font size", TileCategory.DISPLAY),
        Entry(Control.LOCATION, "Location", TileCategory.PRIVACY),
        Entry(Control.INVERT, "Color inversion", TileCategory.ACCESSIBILITY),
        Entry(Control.LIVE_CAPTIONS, "Live Caption", TileCategory.SYSTEM_APPS),
        Entry(Control.QUICK_SHARE, "Quick Share", TileCategory.SYSTEM_APPS),
    )

    /** [c]'s tile as Android names it (its catalogue entry's; the default list's for Do Not Disturb). */
    fun tileOf(c: Control, wide: Boolean): QsTile =
        QsTile(c, wide, DEFAULT.firstOrNull { it.control == c }?.label ?: CATALOG.firstOrNull { it.control == c }?.label ?: c.title)

    /** An app's tile ([AppTiles]). */
    fun appTile(id: String, wide: Boolean): QsTile = QsTile(Control.APP_TILE, wide, AppTiles.info(id)?.label ?: "", id)

    /** The name a tile shows: an app's tile's own (the app may change it), else Android's for the control. */
    fun name(t: QsTile): String {
        val id = t.tile ?: return t.label
        return AppTiles.state(id)?.label?.takeIf { it.isNotEmpty() } ?: AppTiles.info(id)?.label ?: t.label.ifEmpty { "App" }
    }

    /** Whether [t] is on (Auto-rotate is iOS's orientation lock the other way round; Do Not Disturb is Focus). */
    fun isOn(state: ControlState, t: QsTile): Boolean = when (t.control) {
        Control.ROTATION_LOCK -> !state.rotationLock
        Control.FOCUS -> state.dnd
        Control.HOTSPOT -> state.hotspot
        Control.APP_TILE -> t.tile?.let { AppTiles.state(it)?.state } == android.service.quicksettings.Tile.STATE_ACTIVE
        else -> t.toggles && state.isOn(t.control)
    }

    /** [t]'s state: unavailable where the phone cannot do it now (Airplane mode turns mobile data off), else on or off. */
    fun stateOf(state: ControlState, t: QsTile): TileState = when {
        t.tile != null -> when (AppTiles.state(t.tile)?.state) {
            android.service.quicksettings.Tile.STATE_ACTIVE -> TileState.ACTIVE
            android.service.quicksettings.Tile.STATE_UNAVAILABLE -> TileState.UNAVAILABLE
            else -> TileState.INACTIVE
        }
        !state.available(t.control) -> TileState.UNAVAILABLE
        t.control == Control.CELLULAR && state.airplane -> TileState.UNAVAILABLE
        isOn(state, t) -> TileState.ACTIVE
        else -> TileState.INACTIVE
    }

    /** The words under a wide tile's name (null: none, as Android's when the name says it all). */
    fun detail(state: ControlState, t: QsTile, carrier: String, known: TileState? = null): String? {
        if (t.tile != null) return AppTiles.state(t.tile)?.secondary?.takeIf { it.isNotEmpty() }
        // (Drawing passes the state it already has: working it out asks the package manager for the app-backed tiles.)
        val s = known ?: stateOf(state, t)
        if (s == TileState.UNAVAILABLE) return "Unavailable"
        val on = s == TileState.ACTIVE
        return when (t.control) {
            Control.WIFI -> if (on) state.wifiName ?: "Not connected" else "Off"
            Control.CELLULAR -> if (on) carrier.ifEmpty { null } ?: "On" else "Off"
            Control.FOCUS -> if (on) "On" else "Off"
            Control.BLUETOOTH -> if (on) null else "Off"
            else -> null
        }
    }

    /** Where a tile is: its page, row, column and how many columns it takes. */
    class Place(val page: Int, val row: Int, val col: Int, val span: Int)

    /**
     * [tiles] flowed in order on [columns] columns, [rows] rows a page: a tile that does not fit in what is left of a row
     * starts the next (the gap stays, as Android's grid leaves it).
     */
    fun flow(tiles: List<QsTile>, columns: Int, rows: Int): List<Place> {
        val out = ArrayList<Place>(tiles.size)
        var page = 0
        var row = 0
        var col = 0
        for (t in tiles) {
            val span = if (t.wide) 2.coerceAtMost(columns) else 1
            if (col + span > columns) { row++; col = 0 }
            if (row >= rows) { page++; row = 0; col = 0 }
            out += Place(page, row, col, span)
            col += span
        }
        return out
    }
}
