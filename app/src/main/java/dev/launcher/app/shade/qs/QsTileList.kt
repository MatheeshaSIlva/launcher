package dev.launcher.app.shade.qs

import dev.launcher.app.shade.Control
import org.json.JSONArray

/**
 * The text form of Android's shade's tile list, for adb: `reset` (a Pixel's own tiles, nothing kept) or `KEY[:w],KEY[:w],...`
 * where KEY is a control's name (`WIFI`) or an app's tile (`app:COMPONENT`, the component as [AppTiles] names it, with its
 * `/`, `.` and `$`) and `:w` makes the tile two columns wide. Whitespace around an entry is ignored; an empty entry, a name that
 * is no control, an app tile with no component and a tile named twice (its first place stays) are skipped; a text with
 * something in it and nothing valid left is [Parsed.NothingValid], not an empty list (only a blank text is empty). Pure: no Android.
 * Used by the `qs_tiles` test hook and by the dev files' virtual `qs_tiles.txt` (DevFiles).
 */
object QsTileList {
    /** One tile of the list: [key] is [QsTile.key] (a control's name, or `app:COMPONENT`). */
    data class Entry(val key: String, val wide: Boolean) {
        val app: String? get() = key.takeIf { it.startsWith(APP) }?.removePrefix(APP)
        val control: Control? get() = if (app != null) Control.APP_TILE else Control.entries.firstOrNull { it.name == key }
    }

    private const val APP = "app:"
    private const val WIDE = ":w"

    private fun valid(key: String): Boolean =
        if (key.startsWith(APP)) key.length > APP.length else key != Control.APP_TILE.name && Control.entries.any { it.name == key }

    /** What [parse] made of a text. */
    sealed interface Parsed {
        /** `reset`: nothing kept (a Pixel's own tiles). */
        data object Reset : Parsed
        /** A list; empty only for an empty (blank) text. */
        data class Tiles(val list: List<Entry>) : Parsed
        /** A text with something in it and no valid entry (a typo): the store changes nothing. */
        data object NothingValid : Parsed
    }

    /** [text] as [Parsed]: `reset`, a list, or [Parsed.NothingValid] (non-blank text, no valid entry: it must not wipe the list). */
    fun parse(text: String): Parsed {
        if (text.trim() == "reset") return Parsed.Reset
        val out = ArrayList<Entry>()
        for (raw in text.split(',')) {
            val e = raw.trim()
            if (e.isEmpty()) continue
            val wide = e.endsWith(WIDE)
            val key = (if (wide) e.removeSuffix(WIDE) else e).trim()
            if (valid(key) && out.none { it.key == key }) out += Entry(key, wide)
        }
        return if (out.isEmpty() && text.isNotBlank()) Parsed.NothingValid else Parsed.Tiles(out)
    }

    /** [list] as text (null: `reset`); [parse] gives it back. */
    fun format(list: List<Entry>?): String =
        if (list == null) "reset" else list.joinToString(",") { it.key + if (it.wide) WIDE else "" }

    /** The list a store's saved JSON (`[{"c": CONTROL, "w": wide, "t": component}, ...]`) holds, as [QsTileStore] reads it. */
    fun fromStored(json: String): List<Entry> {
        val a = JSONArray(json)
        val out = ArrayList<Entry>(a.length())
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val c = Control.entries.firstOrNull { it.name == o.optString("c") } ?: continue
            val wide = o.optBoolean("w", false)
            val key = if (c == Control.APP_TILE) APP + (o.optString("t").takeIf { it.isNotEmpty() } ?: continue) else c.name
            if (out.none { it.key == key }) out += Entry(key, wide)
        }
        return out
    }
}
