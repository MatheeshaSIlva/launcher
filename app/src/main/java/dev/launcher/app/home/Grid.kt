package dev.launcher.app.home

/**
 * Free placement on a home page (iOS 18+): every item keeps its own cell, empty cells are allowed. Pure logic on the
 * layout model (unit-tested in GridTest).
 *
 * Moving an item onto icons: within an unbroken run of icons between the item's old cell and the new one, the icons step
 * over by one towards the old cell (the classic reorder); otherwise the icons in the way move on in reading order, one
 * cell each, until an empty cell takes the last one. Icons pushed past the page's last cell are handed back (they continue
 * on the next page). An icon never moves a widget (a target that would cover one is refused); a widget moves widgets in
 * its way as iOS does: one of its size it came from beside swaps into its old place, others go to the first place they fit
 * from where they were on, the icons under them moving on too, and what fits nowhere on the page is handed back with the
 * icons. (Widgets that never moved made a widget impossible to place: added at the top, it went to the bottom or another
 * page; dragged or grown onto another, it was refused: Matheesha, "can't even properly place a widget".)
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

    /**
     * Fits [l] to a grid of [cols] x [rows] with [dockSlots] in the dock (the user's counts): items keep their cells where
     * those still exist; what no longer fits a page (fewer columns or rows) moves on to the next, in reading order, taking
     * its first free cells (a new last page if needed), and dock icons past the count go to the first free cell from the
     * last page on. Returns true if anything moved.
     */
    fun fit(l: HomeLayout, cols: Int, rows: Int, dockSlots: Int): Boolean {
        fun at(it: HomeItem) = (it.col.toLong() shl 32) or (it.row.toLong() and 0xffffffffL)
        val before = java.util.IdentityHashMap<HomeItem, Long>()
        for (p in l.pages) for (it in p) before[it] = at(it)
        var moved = false
        var carry = ArrayList<HomeItem>()
        var i = 0
        while (i < l.pages.size || carry.isNotEmpty()) {
            if (i == l.pages.size) l.pages += mutableListOf<HomeItem>()
            val page = l.pages[i]
            if (carry.isNotEmpty()) {
                // Carried items take this page's first free cells, ahead of its own items that have none yet.
                for (c in carry) { c.col = -1; c.row = -1 }
                val own = page.filter { it.col >= 0 && it.row >= 0 }
                val rest = page.filter { it.col < 0 || it.row < 0 }
                page.clear(); page += own; page += carry; page += rest
                carry = ArrayList()
                moved = true
            }
            val placed = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<HomeItem, Boolean>())
            for (p in place(page, cols, rows)) placed += p.item
            if (placed.size < page.size) {
                carry.addAll(page.filter { it !in placed })
                page.removeAll { it !in placed }
                moved = true
            }
            i++
        }
        while (l.dock.size > dockSlots) {
            val key = l.dock.removeAt(l.dock.size - 1)
            var page = l.pages.last()
            var cell = firstFree(page, 1, 1, cols, rows)
            if (cell == null) { page = mutableListOf(); l.pages += page; cell = intArrayOf(0, 0) }
            page += HomeItem.App(key).apply { col = cell[0]; row = cell[1] }
            moved = true
        }
        if (!moved) moved = l.pages.any { p -> p.any { before[it] != at(it) } }
        return moved
    }

    /** The first free cell (reading order) where an [sx] x [sy] block fits, or null. */
    fun firstFree(items: List<HomeItem>, sx: Int, sy: Int, cols: Int, rows: Int): IntArray? =
        firstFit(occupancy(items, cols, rows), sx, sy, cols, rows)

    fun freeCells(items: List<HomeItem>, cols: Int, rows: Int): Int =
        cols * rows - place(items, cols, rows).sumOf { it.spanX * it.spanY }

    /**
     * True if [item] could be put at ([col], [row]) on [items]: inside the page and, for an icon, not over a widget (a widget
     * moves widgets in its way).
     */
    fun canPut(items: List<HomeItem>, item: HomeItem, col: Int, row: Int, cols: Int, rows: Int): Boolean {
        val (sx, sy) = span(item, cols, rows).let { it[0] to it[1] }
        if (col < 0 || row < 0 || col + sx > cols || row + sy > rows) return false
        if (item is HomeItem.Widget) return true
        for (p in place(items, cols, rows)) {
            if (p.item === item || p.item !is HomeItem.Widget) continue
            if (overlap(col, row, sx, sy, p.col, p.row, p.spanX, p.spanY)) return false
        }
        return true
    }

    /**
     * Puts [item] at ([col], [row]) on [items] (adding it if it is not there). [origin]: the cell index (row * cols + col)
     * it came from on this same page, if it did, so the icons between can step back into it (and a widget of the same size
     * in the way can swap into it). Returns the items pushed off the page (removed from [items]: icons, and widgets moved
     * out of a widget's way that fit nowhere else on it), or null if an icon's target covers a widget (nothing changed).
     */
    fun putAt(items: MutableList<HomeItem>, item: HomeItem, col: Int, row: Int, cols: Int, rows: Int, origin: Int? = null): List<HomeItem>? {
        if (!canPut(items, item, col, row, cols, rows)) return null
        val (sx, sy) = span(item, cols, rows).let { it[0] to it[1] }
        if (items.none { it === item }) items += item
        val others = items.filter { it !== item }
        val placedOthers = place(others, cols, rows)
        val n = cols * rows
        // Cells: widgets (other than the item) and the item's own block are fixed; icons are what can move.
        val fixed = BooleanArray(n)
        val icon = arrayOfNulls<HomeItem>(n)
        val block = ArrayList<Int>()
        for (r in row until row + sy) for (c in col until col + sx) block += r * cols + c
        val blockSet = block.toHashSet()
        // Widgets in a widget's way move (an icon's target never covers one: canPut); the others stay where they are
        // unless a moving widget needs their place.
        fun cellsOf(p: Placed) = (p.row until p.row + p.spanY).flatMap { r -> (p.col until p.col + p.spanX).map { c -> r * cols + c } }
        val staying = arrayOfNulls<Placed>(n)
        val queue = ArrayList<Placed>()
        for (p in placedOthers) {
            if (p.item is HomeItem.Widget) {
                val cells = cellsOf(p)
                if (cells.any { it in blockSet }) queue += p else for (cell in cells) staying[cell] = p
            } else icon[p.row * cols + p.col] = p.item
        }
        val displaced = block.mapNotNull { icon[it] }.toMutableList()
        for (b in block) { icon[b] = null; fixed[b] = true }
        item.col = col; item.row = row
        val target = row * cols + col
        val off = ArrayList<HomeItem>()

        // The widgets in the way, in reading order: one of the item's size it came from beside swaps into its old place;
        // the others are pushed on, each to the first place from where it was that the item and the widgets already moved
        // leave free (a widget staying there is pushed on in turn, the icons there move on with the rest); what is pushed
        // past the page's end goes off it. (Always onward, so it ends.)
        val swapIn = queue.singleOrNull()?.takeIf { origin != null && it.spanX == sx && it.spanY == sy }
        while (queue.isNotEmpty()) {
            val p = queue.minByOrNull { it.row * cols + it.col }!!
            queue.remove(p)
            val w = p.item
            fun fits(at: Int): Boolean {
                val c0 = at % cols
                val r0 = at / cols
                if (c0 + p.spanX > cols || r0 + p.spanY > rows) return false
                for (r in r0 until r0 + p.spanY) for (c in c0 until c0 + p.spanX) if (fixed[r * cols + c]) return false
                return true
            }
            val from = p.row * cols + p.col
            val at = if (swapIn === p && origin != null && fits(origin)) origin else (from until n).firstOrNull { fits(it) }
            if (at == null) { off += w; continue }
            w.col = at % cols; w.row = at / cols
            for (r in w.row until w.row + p.spanY) for (c in w.col until w.col + p.spanX) {
                val cell = r * cols + c
                staying[cell]?.let { q -> for (qc in cellsOf(q)) staying[qc] = null; queue += q }
                icon[cell]?.let { displaced += it }
                icon[cell] = null
                fixed[cell] = true
            }
        }
        // The widgets that stay are fixed where they are.
        for (cell in 0 until n) if (staying[cell] != null) fixed[cell] = true

        // A 1x1 move within an unbroken run of icons towards its old cell: the run steps over by one (classic reorder).
        if (sx == 1 && sy == 1 && origin != null && origin != target && displaced.size == 1 && icon[origin] == null && !fixed[origin]) {
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
