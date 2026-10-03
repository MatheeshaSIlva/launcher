package dev.launcher.app.home

/**
 * Free placement on a home page (iOS 18+): every item keeps its own cell, empty cells are allowed. Pure logic on the
 * layout model (unit-tested in GridTest).
 *
 * Moving an item onto icons: within an unbroken run of icons between the item's old cell and the new one, the icons step
 * over by one towards the old cell (the classic reorder); otherwise the icons in the way move on in reading order, one
 * cell each, until an empty cell takes the last one. Icons pushed past the page's last cell are handed back (they continue
 * on the next page). Widgets never move out of the way: a target that would cover one is refused.
 */
object Grid {
    fun span(item: HomeItem, cols: Int, rows: Int): IntArray =
        if (item is HomeItem.Widget) intArrayOf(item.spanX.coerceIn(1, cols), item.spanY.coerceIn(1, rows)) else intArrayOf(1, 1)

    /**
     * Where everything on the page is: items at their own cells when those are valid and free, the rest (new items, items
     * from an older layout without cells, overlaps) at the first free fit in reading order, which is then stored on the
     * item. Items that fit nowhere are left out (callers move them to another page). Result in the items' order.
     */
    fun place(items: List<HomeItem>, cols: Int, rows: Int): List<Placed> {
        val used = Array(rows) { BooleanArray(cols) }
        val at = java.util.IdentityHashMap<HomeItem, Placed>()
        for (item in items) {
            val (sx, sy) = span(item, cols, rows).let { it[0] to it[1] }
            if (item.col < 0 || item.row < 0 || item.col + sx > cols || item.row + sy > rows) continue
            if (!free(used, item.col, item.row, sx, sy)) continue
            mark(used, item.col, item.row, sx, sy)
            at[item] = Placed(item, item.col, item.row, sx, sy)
        }
        for (item in items) {
            if (at.containsKey(item)) continue
            val (sx, sy) = span(item, cols, rows).let { it[0] to it[1] }
            val fit = firstFit(used, sx, sy, cols, rows) ?: continue
            item.col = fit[0]; item.row = fit[1]
            mark(used, fit[0], fit[1], sx, sy)
            at[item] = Placed(item, fit[0], fit[1], sx, sy)
        }
        return items.mapNotNull { at[it] }
    }

    /** The first free cell (reading order) where an [sx] x [sy] block fits, or null. */
    fun firstFree(items: List<HomeItem>, sx: Int, sy: Int, cols: Int, rows: Int): IntArray? =
        firstFit(occupancy(items, cols, rows), sx, sy, cols, rows)

    fun freeCells(items: List<HomeItem>, cols: Int, rows: Int): Int =
        cols * rows - place(items, cols, rows).sumOf { it.spanX * it.spanY }

    /**
     * True if [item] could be put at ([col], [row]) on [items]: inside the page and not over a widget other than itself.
     */
    fun canPut(items: List<HomeItem>, item: HomeItem, col: Int, row: Int, cols: Int, rows: Int): Boolean {
        val (sx, sy) = span(item, cols, rows).let { it[0] to it[1] }
        if (col < 0 || row < 0 || col + sx > cols || row + sy > rows) return false
        for (p in place(items, cols, rows)) {
            if (p.item === item || p.item !is HomeItem.Widget) continue
            if (overlap(col, row, sx, sy, p.col, p.row, p.spanX, p.spanY)) return false
        }
        return true
    }

    /**
     * Puts [item] at ([col], [row]) on [items] (adding it if it is not there). [origin]: the cell index (row * cols + col)
     * it came from on this same page, if it did, so the icons between can step back into it. Returns the icons pushed off
     * the page (removed from [items]), or null if the target covers a widget (nothing changed).
     */
    fun putAt(items: MutableList<HomeItem>, item: HomeItem, col: Int, row: Int, cols: Int, rows: Int, origin: Int? = null): List<HomeItem>? {
        if (!canPut(items, item, col, row, cols, rows)) return null
        val (sx, sy) = span(item, cols, rows).let { it[0] to it[1] }
        if (items.none { it === item }) items += item
        val others = items.filter { it !== item }
        place(others, cols, rows)
        val n = cols * rows
        // Cells: widgets (other than the item) and the item's own block are fixed; icons are what can move.
        val fixed = BooleanArray(n)
        val icon = arrayOfNulls<HomeItem>(n)
        for (p in place(others, cols, rows)) {
            if (p.item is HomeItem.Widget) { for (r in p.row until p.row + p.spanY) for (c in p.col until p.col + p.spanX) fixed[r * cols + c] = true }
            else icon[p.row * cols + p.col] = p.item
        }
        val block = ArrayList<Int>()
        for (r in row until row + sy) for (c in col until col + sx) block += r * cols + c
        val displaced = block.mapNotNull { icon[it] }.toMutableList()
        for (b in block) { icon[b] = null; fixed[b] = true }
        item.col = col; item.row = row
        val target = row * cols + col

        // A 1x1 move within an unbroken run of icons towards its old cell: the run steps over by one (classic reorder).
        if (sx == 1 && sy == 1 && origin != null && origin != target && displaced.size == 1 && icon[origin] == null) {
            val step = if (origin > target) 1 else -1
            val cells = generateSequence(target) { it + step }.takeWhile { it != origin }.filter { !fixed[it] || it == target }.toList()
            val run = cells.drop(1)
            if (run.all { icon[it] != null }) {
                // Every icon from the target to just before the origin moves one cell towards the origin.
                val moving = listOf(displaced[0]) + run.map { icon[it]!! }
                val dest = run + origin
                for ((i, it) in moving.withIndex()) { it.col = dest[i] % cols; it.row = dest[i] / cols }
                return emptyList()
            }
        }

        // Otherwise the icons in the way move on in reading order, keeping their order: each goes to the next cell after the
        // one before it, pushing what is there along, until an empty cell takes the last.
        val off = ArrayList<HomeItem>()
        var cursor = -1
        for (d in displaced.sortedBy { it.row * cols + it.col }) {
            var carry: HomeItem = d
            var pos = maxOf(cursor, d.row * cols + d.col)
            var first = true
            while (true) {
                pos++
                while (pos < n && fixed[pos]) pos++
                if (pos >= n) { off += carry; if (first) cursor = n; break }
                val there = icon[pos]
                icon[pos] = carry
                carry.col = pos % cols; carry.row = pos / cols
                if (first) { cursor = pos; first = false }
                if (there == null) break
                carry = there
            }
        }
        for (o in off) { items.removeAll { it === o }; o.col = -1; o.row = -1 }
        return off
    }

    /** The cell index under page coordinates, or -1. */
    fun cellAt(x: Float, y: Float, m: HomeMetrics): Int {
        val col = ((x - m.cellLeft(0)) / m.columnPitch).toInt()
        val row = ((y - m.gridTop) / m.cellHeight).toInt()
        if (x < m.cellLeft(0) || y < m.gridTop || col !in 0 until m.cfg.columns || row !in 0 until m.cfg.rows) return -1
        return row * m.cfg.columns + col
    }

    private fun occupancy(items: List<HomeItem>, cols: Int, rows: Int): Array<BooleanArray> {
        val used = Array(rows) { BooleanArray(cols) }
        for (p in place(items, cols, rows)) mark(used, p.col, p.row, p.spanX, p.spanY)
        return used
    }

    private fun firstFit(used: Array<BooleanArray>, sx: Int, sy: Int, cols: Int, rows: Int): IntArray? {
        for (r in 0..rows - sy) for (c in 0..cols - sx) if (free(used, c, r, sx, sy)) return intArrayOf(c, r)
        return null
    }

    private fun free(used: Array<BooleanArray>, c: Int, r: Int, sx: Int, sy: Int): Boolean {
        for (rr in r until r + sy) for (cc in c until c + sx) if (used[rr][cc]) return false
        return true
    }

    private fun mark(used: Array<BooleanArray>, c: Int, r: Int, sx: Int, sy: Int) {
        for (rr in r until r + sy) for (cc in c until c + sx) used[rr][cc] = true
    }

    private fun overlap(c1: Int, r1: Int, w1: Int, h1: Int, c2: Int, r2: Int, w2: Int, h2: Int) =
        c1 < c2 + w2 && c2 < c1 + w1 && r1 < r2 + h2 && r2 < r1 + h1
}
