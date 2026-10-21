package dev.launcher.app.shade.qs

import dev.launcher.app.shade.Control
import dev.launcher.app.shade.ControlState

/**
 * Quick Settings' tiles as Android has them (the `tiles` block of a layout's `shade` panel): our controls under Android's
 * names, in a Pixel's default order (SystemUIGoogle's `quick_settings_tiles_default`, its large ones
 * `quick_settings_large_tiles_default`), each one column wide (its symbol alone) or two (a box with its symbol, its name and
 * state). A two-column tile whose control turns on and off has two targets, as Android's: its box toggles, the rest opens
 * the control's page. Pure: [ControlState] says what is available and on.
 */
class QsTile(val control: Control, val wide: Boolean, val label: String) {
    /** It turns on and off (a toggle); else a tap opens what it names (Quick Share, the camera...). */
    val toggles: Boolean get() = control.kind == Control.Kind.TOGGLE || control == Control.FOCUS
    /** A wide toggle: its box and the rest of it are two targets (Android's `handlesToggleClick`). */
    val dualTarget: Boolean get() = wide && toggles
}

/** A tile's state as Android draws it (Tile.STATE_*): its colours, its corners. */
enum class TileState { UNAVAILABLE, INACTIVE, ACTIVE }

object QsTiles {
    /**
     * A Pixel's tiles, in its order (Android 17's SystemUIGoogle: Wi-Fi, Bluetooth, Mobile data, Quick Share, Do Not Disturb
     * and Wallet large; Airplane mode, Auto-rotate, Flashlight, Battery Saver, Alarm, Device controls and Screen Cast small),
     * as far as we have them (Screen record has no control of ours yet).
     */
    val DEFAULT: List<QsTile> = listOf(
        QsTile(Control.WIFI, true, "Wi-Fi"),
        QsTile(Control.BLUETOOTH, true, "Bluetooth"),
        QsTile(Control.CELLULAR, true, "Mobile data"),
        QsTile(Control.QUICK_SHARE, true, "Quick Share"),
        QsTile(Control.FOCUS, true, "Do Not Disturb"),
        QsTile(Control.WALLET, true, "Wallet"),
        QsTile(Control.AIRPLANE, false, "Airplane mode"),
        QsTile(Control.ROTATION_LOCK, false, "Auto-rotate"),
        QsTile(Control.FLASHLIGHT, false, "Flashlight"),
        QsTile(Control.LOW_POWER, false, "Battery Saver"),
        QsTile(Control.ALARM, false, "Alarm"),
        QsTile(Control.HOME, false, "Device controls"),
        QsTile(Control.MIRRORING, false, "Screen Cast"),
    )

    /** Whether [t] is on (Auto-rotate is iOS's orientation lock the other way round; Do Not Disturb is Focus). */
    fun isOn(state: ControlState, t: QsTile): Boolean = when (t.control) {
        Control.ROTATION_LOCK -> !state.rotationLock
        Control.FOCUS -> state.dnd
        Control.HOTSPOT -> state.hotspot
        else -> t.toggles && state.isOn(t.control)
    }

    /** [t]'s state: unavailable where the phone cannot do it now (Airplane mode turns mobile data off), else on or off. */
    fun stateOf(state: ControlState, t: QsTile): TileState = when {
        !state.available(t.control) -> TileState.UNAVAILABLE
        t.control == Control.CELLULAR && state.airplane -> TileState.UNAVAILABLE
        isOn(state, t) -> TileState.ACTIVE
        else -> TileState.INACTIVE
    }

    /** The words under a wide tile's name (null: none, as Android's when the name says it all). */
    fun detail(state: ControlState, t: QsTile, carrier: String): String? {
        val s = stateOf(state, t)
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
