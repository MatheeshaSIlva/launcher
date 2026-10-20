package dev.launcher.app.shade.qs

import dev.launcher.app.shade.Control
import dev.launcher.app.shade.ControlState

/**
 * Quick Settings' tiles as Android has them (the `tiles` block of a layout's `shade` panel): our controls under Android's
 * names, in Android 17's order, each one column wide (its symbol alone) or two (a box with its symbol, its name and state).
 * A two-column tile whose control turns on and off has two targets, as Android's: its box toggles, the rest opens the
 * control's page. Pure: [ControlState] says what is available and on.
 */
class QsTile(val control: Control, val wide: Boolean, val label: String) {
    /** It turns on and off (a toggle); else a tap opens what it names (Quick Share, the camera...). */
    val toggles: Boolean get() = control.kind == Control.Kind.TOGGLE || control == Control.FOCUS
}

object QsTiles {
    /** Android 17's tiles, in its order (tools/shots/pxref/shade2.png, AOSP's default specs), as far as we have them. */
    val DEFAULT: List<QsTile> = listOf(
        QsTile(Control.WIFI, true, "Wi-Fi"),
        QsTile(Control.BLUETOOTH, true, "Bluetooth"),
        QsTile(Control.CELLULAR, true, "Mobile data"),
        QsTile(Control.FOCUS, true, "Do Not Disturb"),
        QsTile(Control.FLASHLIGHT, false, "Flashlight"),
        QsTile(Control.ROTATION_LOCK, false, "Auto-rotate"),
        QsTile(Control.AIRPLANE, false, "Airplane mode"),
        QsTile(Control.LOW_POWER, false, "Battery Saver"),
        QsTile(Control.DARK_MODE, false, "Dark theme"),
        QsTile(Control.LOCATION, true, "Location"),
        QsTile(Control.HOTSPOT, true, "Hotspot"),
        QsTile(Control.QUICK_SHARE, true, "Quick Share"),
        QsTile(Control.MIRRORING, true, "Screen Cast"),
        QsTile(Control.DATA_SAVER, false, "Data Saver"),
        QsTile(Control.NFC, false, "NFC"),
        QsTile(Control.SCAN_CODE, false, "QR code scanner"),
        QsTile(Control.INVERT, false, "Color inversion"),
        QsTile(Control.EXTRA_DIM, false, "Extra dim"),
        QsTile(Control.LIVE_CAPTIONS, false, "Live Caption"),
        QsTile(Control.ALARM, false, "Alarm"),
        QsTile(Control.CAMERA, false, "Camera"),
    )

    /** How far [t] is on (Auto-rotate is iOS's orientation lock the other way round; Do Not Disturb is Focus). */
    fun isOn(state: ControlState, t: QsTile): Boolean = when (t.control) {
        Control.ROTATION_LOCK -> !state.rotationLock
        Control.FOCUS -> state.dnd
        Control.HOTSPOT -> state.hotspot
        else -> t.toggles && state.isOn(t.control)
    }

    /** The words under a wide tile's name (null: none, as Android's when the name says it all). */
    fun detail(state: ControlState, t: QsTile): String? {
        val on = isOn(state, t)
        return when (t.control) {
            Control.WIFI -> if (on) state.wifiName ?: "Not connected" else "Off"
            Control.CELLULAR, Control.LOCATION, Control.HOTSPOT, Control.FOCUS, Control.BLUETOOTH -> if (on) "On" else "Off"
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
