package dev.launcher.app.shade

import org.json.JSONArray
import org.json.JSONObject

/**
 * What can be put on the page: a built-in control, or an app's Quick Settings tile ([Control.APP_TILE] with [tile], its
 * component flattened: see AppTiles).
 */
data class ControlId(val control: Control, val tile: String? = null) {
    /** Saved and compared by this: one of each on the page. */
    val key: String get() = if (tile == null) control.name else "${control.name}:$tile"
}

/** One control on Control Center's grid: its place (column, row) and size (columns x rows); [tile]: see [ControlId]. */
class CcItem(val control: Control, var col: Int, var row: Int, var w: Int, var h: Int, val tile: String? = null) {
    val id get() = ControlId(control, tile)
    fun overlaps(c: Int, r: Int, cw: Int, ch: Int) = col < c + cw && c < col + w && row < r + ch && r < row + h
    override fun toString() = "${id.key}@$col,$row ${w}x$h"
}

/**
 * Control Center's page: free placement on a grid of [cols] columns and [rows] rows (iOS 18+): every control keeps its
 * own cells, empty cells are allowed. Moving or resizing a control onto others moves those on to the nearest free place
 * after their own (reading order), as iOS's Control Center does. Pure logic (CcLayoutTest).
 */
class CcLayout(val cols: Int, var rows: Int, val items: MutableList<CcItem>) {

    fun at(col: Int, row: Int): CcItem? = items.firstOrNull { it.overlaps(col, row, 1, 1) }

    /** True if [c],[r] with size [w] x [h] is inside the page and free of everything but [except]. */
    fun free(c: Int, r: Int, w: Int, h: Int, except: CcItem? = null): Boolean {
        if (c < 0 || r < 0 || c + w > cols || r + h > rows) return false
        return items.none { it !== except && it.overlaps(c, r, w, h) }
    }

    /** The first free place (reading order, from [fromCol],[fromRow]) for a [w] x [h] block, or null. */
    fun firstFree(w: Int, h: Int, fromCol: Int = 0, fromRow: Int = 0, except: CcItem? = null): IntArray? {
        val start = fromRow * cols + fromCol
        for (i in 0 until cols * rows) {
            val cell = (start + i) % (cols * rows)
            val c = cell % cols
            val r = cell / cols
            if (free(c, r, w, h, except)) return intArrayOf(c, r)
        }
        return null
    }

    /** Adds [id] at its default size at the first free place. Returns the new item, or null if the page is full. */
    fun add(id: ControlId): CcItem? {
        for (s in id.control.sizes) {
            val at = firstFree(s.w, s.h) ?: continue
            return CcItem(id.control, at[0], at[1], s.w, s.h, id.tile).also { items += it }
        }
        return null
    }

    fun add(control: Control): CcItem? = add(ControlId(control))

    /**
     * Puts [item] at [col],[row] (kept inside the columns). Controls it covers move on to the first free place after their
     * own, in reading order. Returns false (and changes nothing) if they cannot all be placed.
     */
    fun moveTo(item: CcItem, col: Int, row: Int): Boolean {
        val c = col.coerceIn(0, cols - item.w)
        val r = row.coerceIn(0, rows - item.h)
        if (c == item.col && r == item.row) return true
        return rearrange(item, c, r, item.w, item.h)
    }

    /** Gives [item] the size [w] x [h], keeping its top-left (moved left if it would leave the page). */
    fun resize(item: CcItem, w: Int, h: Int): Boolean {
        val c = item.col.coerceAtMost(cols - w).coerceAtLeast(0)
        val r = item.row.coerceAtMost(rows - h).coerceAtLeast(0)
        return rearrange(item, c, r, w, h)
    }

    private fun rearrange(item: CcItem, c: Int, r: Int, w: Int, h: Int): Boolean {
        if (c < 0 || r < 0 || c + w > cols || r + h > rows) return false
        val saved = items.map { intArrayOf(it.col, it.row, it.w, it.h) }
        item.col = c; item.row = r; item.w = w; item.h = h
        val displaced = items.filter { it !== item && it.overlaps(c, r, w, h) }.sortedBy { it.row * cols + it.col }
        // Take the displaced off the grid, then place each at the first free place from its own cell on.
        val parked = displaced.map { it to intArrayOf(it.col, it.row) }
        for (d in displaced) { d.col = -100; d.row = -100 }
        for ((d, from) in parked) {
            val at = firstFree(d.w, d.h, from[0], from[1], except = d)
            if (at == null) {
                for ((i, it) in items.withIndex()) { it.col = saved[i][0]; it.row = saved[i][1]; it.w = saved[i][2]; it.h = saved[i][3] }
                return false
            }
            d.col = at[0]; d.row = at[1]
        }
        return true
    }

    fun remove(item: CcItem) { items.remove(item) }

    /** The lowest row any control reaches (exclusive). */
    fun usedRows(): Int = items.maxOfOrNull { it.row + it.h } ?: 0

    fun toJson(): String = JSONArray().also { a ->
        for (it in items) a.put(JSONObject().put("c", it.control.name).put("x", it.col).put("y", it.row).put("w", it.w).put("h", it.h)
            .also { o -> it.tile?.let { t -> o.put("t", t) } })
    }.toString()

    companion object {
        /** iOS 26's first page (Apple's illustration of Control Center), without the controls Android has no use for. */
        fun default(cols: Int, rows: Int) = CcLayout(cols, rows, mutableListOf(
            CcItem(Control.CONNECTIVITY, 0, 0, 2, 2),
            CcItem(Control.MEDIA, 2, 0, 2, 2),
            CcItem(Control.ROTATION_LOCK, 0, 2, 1, 1),
            CcItem(Control.SILENT, 1, 2, 1, 1),
            CcItem(Control.BRIGHTNESS, 2, 2, 1, 2),
            CcItem(Control.VOLUME, 3, 2, 1, 2),
            CcItem(Control.FOCUS, 0, 3, 2, 1),
            CcItem(Control.FLASHLIGHT, 0, 4, 1, 1),
            CcItem(Control.TIMER, 1, 4, 1, 1),
            CcItem(Control.CALCULATOR, 2, 4, 1, 1),
            CcItem(Control.CAMERA, 3, 4, 1, 1),
            CcItem(Control.MIRRORING, 0, 5, 1, 1),
            CcItem(Control.SCAN_CODE, 1, 5, 1, 1),
        ))

        /** A saved page; unknown controls (an app's tile without its name) and overlaps are dropped, sizes the control no longer has are reset. */
        fun fromJson(json: String?, cols: Int, rows: Int): CcLayout? {
            json ?: return null
            return try {
                val a = JSONArray(json)
                val l = CcLayout(cols, rows, mutableListOf())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val control = Control.entries.firstOrNull { it.name == o.getString("c") } ?: continue
                    val tile = if (control == Control.APP_TILE) o.optString("t").takeIf { it.isNotEmpty() } ?: continue else null
                    var w = o.optInt("w", control.defaultSize.w)
                    var h = o.optInt("h", control.defaultSize.h)
                    if (control.sizes.none { it.w == w && it.h == h }) { w = control.defaultSize.w; h = control.defaultSize.h }
                    val x = o.optInt("x", 0)
                    val y = o.optInt("y", 0)
                    val id = ControlId(control, tile)
                    if (l.items.any { it.id == id }) continue
                    if (l.free(x, y, w, h)) l.items += CcItem(control, x, y, w, h, tile)
                    else l.firstFree(w, h)?.let { at -> l.items += CcItem(control, at[0], at[1], w, h, tile) }
                }
                l
            } catch (_: Throwable) { null }
        }
    }
}
