package dev.launcher.app.shade.qs

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import dev.launcher.app.R
import dev.launcher.app.design.Design
import dev.launcher.app.design.TextKey
import dev.launcher.app.design.applyTo
import dev.launcher.app.motion.IosScroller
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.motion.MotionValue
import dev.launcher.app.shade.AppTiles
import dev.launcher.app.shade.Control
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Quick Settings' edit page, as Android 17's "Edit tiles" (SystemUIGoogle's EditModeKt and EditTileKt, its sizes measured
 * on the emulator's own): a bar with the way back, undo (once something changed) and Remove; the title and the line under
 * it, scrolling under the bar into it; the current tiles in an outlined box, one of them always chosen (the first, else
 * the one last added: an outline and a handle at its end), to rearrange (a long press lifts a tile: left at half at its
 * place, a dark slot where it will land, the others making way on Android's placement spring; let go outside the box it
 * is taken out) and resize (the handle dragged, or tapped); under it the tiles to add by category (a tap adds one at the
 * end, a long press drags it in), those already there faint; Reset at the end, after a question. Every change is kept at
 * once ([QsTileStore]) and Quick Settings under the page follow it.
 */
internal class QsEditPage(
    private val panel: QsPanel,
    private val grid: () -> TileGridBlock?,
    /** The tiles changed (kept already): Quick Settings lay them out again. */
    private val changed: () -> Unit,
    /** Back: the page goes. */
    private val exit: () -> Unit,
) {
    private val ctx get() = panel.view.context
    private val u get() = panel.u
    private fun inv() = panel.view.invalidate()

    // ------------------------------------------------------------------ the tiles

    /** The current tiles as edited (kept at every change). */
    private var tiles = ArrayList<QsTile>()
    /** What undo goes back to, the newest last. */
    private val history = ArrayList<List<QsTile>>()
    /** The chosen tile (its key): there always is one while there are tiles (Android's AutoSelectTiles). */
    private var selected: String? = null

    /** A current tile's place and looks, each on its spring (positions inside the box, px). */
    private inner class Cell(var tile: QsTile) {
        val x = MotionValue(0f, 10f, { inv() })
        val y = MotionValue(0f, 10f, { inv() })
        val w = MotionValue(0f, 10f, { inv() })
        /** Present (a tile added fades in, one taken out fades out where it was). */
        val shown: MotionValue = MotionValue(0f, 1000f, { inv() }, { if (leaving && shown.value <= 0.001f) cells.remove(tile.key) })
        val sel = MotionValue(0f, 1000f, { inv() })
        var placed = false
        var leaving = false
    }
    private val cells = LinkedHashMap<String, Cell>()

    /** A tile to add: added already (faint, no badge) or not, on its spring. */
    private class Offer(val tile: QsTile, val category: TileCategory, val added: MotionValue)
    private var offers: List<Offer> = emptyList()

    /** The box's rows (its height follows them on the placement spring). */
    private val gridRows = MotionValue(1f, 1000f, { inv(); bounds() })

    // ------------------------------------------------------------------ the page's motion

    private val scroller = IosScroller({ inv() })
    private val undoK = MotionValue(0f, 1000f, { inv() })
    private val removeOn = MotionValue(0f, 1000f, { inv() })
    private val dialogK = MotionValue(0f, 1000f, { inv() })
    private var dialog = false

    /** The tile being dragged: from the box ([fromBox]) or the tiles to add; where the finger is (screen); where it lands. */
    private inner class Drag(val tile: QsTile, val fromBox: Boolean) {
        var fx = 0f
        var fy = 0f
        /** Its slot in the box's tiles without it (-1: out of the box). */
        var slot = -1
        /** It has moved (until then a tile from the box shows lifted at its place). */
        var moved = false
        val k = MotionValue(0f, 1000f, { inv() })
    }
    private var drag: Drag? = null
    /** A tile let go: its picture fading where it was (taken out, or not dropped in). */
    private var dropped: Drag? = null

    /** The handle being dragged: the chosen tile resized by the finger. */
    private var resizeFrom = 0f
    private var resizeWide = false

    // ------------------------------------------------------------------ geometry (px; content y = screen y + scroll)

    private var cols = 4
    private var tileH = 0f
    private var gap = 0f
    private var cellW = 0f
    private var pageL = 0f
    private var pageR = 0f
    private var gridTop = 0f
    private var pad = 0f
    private var navInset = 0f

    private fun barCenter() = panel.barHeight + Design.pt(QsTokens.EDIT_BAR_CENTER, u)
    private fun barBottom() = barCenter() + Design.pt(QsTokens.EDIT_BAR_HEIGHT, u) / 2f
    private fun gridHeight(rows: Float) = rows * (tileH + gap) + 2f * pad
    private fun boxLeft() = pageL + pad
    private fun scroll() = scroller.position

    fun layout() {
        val w = panel.panelWidth.toFloat()
        if (w <= 0f) return
        pageL = panel.side
        pageR = w - panel.side
        tileH = Design.pt(QsTokens.TILE_HEIGHT, u)
        gap = Design.pt(QsTokens.EDIT_TILE_GAP, u)
        pad = Design.pt(QsTokens.EDIT_GRID_PADDING, u)
        cols = grid()?.columns ?: 4
        cellW = (pageR - pageL - 2f * pad - (cols - 1) * gap) / cols
        gridTop = panel.barHeight + Design.pt(QsTokens.EDIT_GRID_TOP, u)
        // (The shade's window hides the navigation bar: its size, as the page keeps clear of it.)
        navInset = panel.view.rootWindowInsets?.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars())?.bottom?.toFloat() ?: 0f
        labels.clear()
        if (open) { reflow(animate = false); bounds() }
    }

    // ------------------------------------------------------------------ opening and closing

    var open = false
        private set

    /** The page comes: the tiles as Quick Settings show them, the first chosen, at the top. */
    fun begin() {
        open = true
        tiles = ArrayList(grid()?.tiles ?: QsTileStore.tiles(ctx))
        history.clear()
        undoK.snapTo(0f)
        dialog = false
        dialogK.snapTo(0f)
        drag = null
        dropped = null
        cells.clear()
        selected = tiles.firstOrNull()?.key
        removeOn.snapTo(if (selected != null) 1f else 0f)
        buildOffers()
        layout()
        scroller.jumpTo(0f)
    }

    /** The page went (Back, the shade closed). */
    fun end() {
        open = false
        drag = null
        dropped = null
        dialog = false
        scroller.stop()
        panel.view.removeCallbacks(longPress)
        panel.view.removeCallbacks(autoScroll)
    }

    /** Back: Reset's question goes first; else the page. */
    fun back(): Boolean {
        if (!open) return false
        if (dialog) { showDialog(false); return true }
        exit()
        return true
    }

    // ------------------------------------------------------------------ changes

    /** The tiles become [next] (kept, undo can bring the old ones back), the box rearranging on its spring. */
    private fun commit(next: List<QsTile>, undoable: Boolean = true, prefer: Int = -1) {
        if (next.size == tiles.size && next.indices.all { next[it].key == tiles[it].key && next[it].wide == tiles[it].wide }) return
        if (undoable) {
            history += ArrayList(tiles)
            undoK.animateTo(1f, Motion.role(MotionTokens.QS_EDIT_APPEAR))
        }
        val before = tiles.map { it.key }.toSet()
        tiles = ArrayList(next)
        for (t in tiles) if (t.tile != null && t.key !in before) AppTiles.added(ctx, t.tile)
        QsTileStore.set(ctx, tiles)
        autoSelect(prefer)
        reflow(animate = true)
        syncOffers()
        changed()
    }

    private fun undo() {
        val last = history.removeLastOrNull() ?: return
        haptic(HapticFeedbackConstants.CLOCK_TICK)
        commit(last, undoable = false)
        if (history.isEmpty()) undoK.animateTo(0f, Motion.role(MotionTokens.QS_EDIT_APPEAR))
    }

    /** The chosen tile stays chosen while it is there; else the one at its place, else the first (Android's). */
    private fun autoSelect(prefer: Int = -1) {
        if (tiles.any { it.key == selected }) { syncSelection(); return }
        selected = (if (prefer >= 0) tiles.getOrNull(prefer.coerceAtMost(tiles.size - 1)) else null)?.key ?: tiles.firstOrNull()?.key
        syncSelection()
    }

    private fun select(key: String?) {
        if (selected == key) return
        selected = key
        syncSelection()
    }

    private fun syncSelection() {
        for (c in cells.values) {
            val t = if (c.tile.key == selected && !c.leaving) 1f else 0f
            if (c.sel.target != t) c.sel.animateTo(t, Motion.role(MotionTokens.QS_TILE_STATE))
        }
        val on = if (selected != null) 1f else 0f
        if (removeOn.target != on) removeOn.animateTo(on, Motion.role(MotionTokens.QS_TILE_STATE))
    }

    private fun remove() {
        val i = tiles.indexOfFirst { it.key == selected }
        if (i < 0) return
        haptic(HapticFeedbackConstants.CONTEXT_CLICK)
        val next = ArrayList(tiles).apply { removeAt(i) }
        selected = null
        commit(next, prefer = i)
    }

    private fun add(t: QsTile, at: Int = tiles.size) {
        if (tiles.any { it.key == t.key }) return
        haptic(HapticFeedbackConstants.CONTEXT_CLICK)
        val next = ArrayList(tiles).apply { add(at.coerceIn(0, size), t) }
        selected = t.key
        commit(next)
        syncSelection()
    }

    private fun resize(key: String, wide: Boolean) {
        val i = tiles.indexOfFirst { it.key == key }
        if (i < 0 || tiles[i].wide == wide) return
        haptic(HapticFeedbackConstants.CLOCK_TICK)
        commit(ArrayList(tiles).apply { set(i, get(i).sized(wide)) })
    }

    private fun reset() {
        haptic(HapticFeedbackConstants.CONTEXT_CLICK)
        commit(QsTiles.DEFAULT.filter { it.control != Control.FLASHLIGHT || panel.host.state.available(it.control) })
        QsTileStore.reset(ctx)
    }

    // ------------------------------------------------------------------ the box's arrangement

    /** The box's tiles as they show now: a dragged one at its slot (or out of the box), the others making way. */
    private fun arrangement(): List<QsTile> {
        val d = drag ?: return tiles
        val base = if (d.fromBox) tiles.filter { it.key != d.tile.key } else tiles
        if (d.slot < 0) return base
        return ArrayList(base).apply { add(d.slot.coerceIn(0, size), d.tile.sized(d.tile.wide)) }
    }

    /** Every tile in the box to its place ([animate]: on the placement spring; a tile new to it fades in there). */
    private fun reflow(animate: Boolean) {
        val order = arrangement()
        val places = QsTiles.flow(order, cols, Int.MAX_VALUE)
        val spec = Motion.role(MotionTokens.QS_EDIT_PLACE)
        val keys = HashSet<String>()
        for ((i, t) in order.withIndex()) {
            val p = places[i]
            keys += t.key
            val c = cells.getOrPut(t.key) { Cell(t) }
            c.tile = t
            val tx = p.col * (cellW + gap)
            val ty = p.row * (tileH + gap)
            val tw = p.span * cellW + (p.span - 1) * gap
            if (!animate || !c.placed) {
                c.x.snapTo(tx); c.y.snapTo(ty); c.w.snapTo(tw)
                c.placed = true
                if (animate && !c.leaving && c.shown.value < 1f) c.shown.animateTo(1f, Motion.role(MotionTokens.QS_EDIT_APPEAR))
                else if (!animate) c.shown.snapTo(1f)
            } else {
                if (c.x.target != tx) c.x.animateTo(tx, spec)
                if (c.y.target != ty) c.y.animateTo(ty, spec)
                if (c.w.target != tw && !(resizing && t.key == selected)) c.w.animateTo(tw, spec)
                if (c.leaving || c.shown.target < 1f) c.shown.animateTo(1f, Motion.role(MotionTokens.QS_EDIT_APPEAR))
            }
            c.leaving = false
        }
        // Tiles no longer in the box: taken out fade where they were; one dragged out of it goes with the finger.
        for (c in cells.values.toList()) {
            if (c.tile.key in keys) continue
            if (drag?.let { it.fromBox && it.tile.key == c.tile.key } == true) continue
            if (!animate || drag?.tile?.key == c.tile.key) { cells.remove(c.tile.key); continue }
            if (!c.leaving) { c.leaving = true; c.shown.animateTo(0f, Motion.role(MotionTokens.QS_EDIT_APPEAR)) }
        }
        val rows = max(1, (places.maxOfOrNull { it.row } ?: 0) + 1).toFloat()
        if (!animate) gridRows.snapTo(rows) else if (gridRows.target != rows) gridRows.animateTo(rows, spec)
        syncSelection()
        inv()
    }

    // ------------------------------------------------------------------ the tiles to add

    private fun buildOffers() {
        val st = panel.host.state
        val list = ArrayList<Offer>()
        for (e in QsTiles.CATALOG) {
            if (!st.available(e.control) && e.control != Control.CELLULAR && e.control != Control.WIFI) continue
            list += Offer(QsTile(e.control, false, e.label), e.category, MotionValue(0f, 1000f, { inv() }))
        }
        // Apps' own tiles where Android lists them; one named as a control of ours is that control (Quick Share, Live Caption).
        val ours = list.map { it.tile.label.lowercase() }.toSet()
        for (a in AppTiles.all) {
            if (a.label.lowercase() in ours) continue
            list += Offer(QsTiles.appTile(a.id, false), QsTiles.categoryOf(a), MotionValue(0f, 1000f, { inv() }))
        }
        // Each category's tiles by name, as Android lists them.
        offers = list.sortedWith(compareBy<Offer>({ it.category.ordinal }).thenBy(String.CASE_INSENSITIVE_ORDER) { QsTiles.name(it.tile) })
        val have = tiles.map { it.key }.toSet()
        for (o in offers) o.added.snapTo(if (o.tile.key in have) 1f else 0f)
    }

    private fun syncOffers() {
        val have = tiles.map { it.key }.toSet()
        for (o in offers) {
            val t = if (o.tile.key in have) 1f else 0f
            if (o.added.target != t) o.added.animateTo(t, Motion.role(MotionTokens.QS_EDIT_APPEAR))
        }
    }

    /** Where each category's box and its tiles are, from [top] (content y): filled as drawn and touched. */
    private class Section(val category: TileCategory, val offers: List<Offer>) {
        val box = RectF()
        val cells = ArrayList<RectF>()
    }
    private var sections: List<Section> = emptyList()
    private var sectionsFor: List<Offer>? = null
    private val resetRect = RectF()
    private var contentEnd = 0f

    /** Lays out the categories under the box as it is now (its height moving with its rows). */
    private fun placeSections() {
        if (sectionsFor !== offers) {
            sectionsFor = offers
            sections = offers.groupBy { it.category }.toSortedMap(compareBy { it.ordinal }).map { (k, v) -> Section(k, v) }
        }
        var top = gridTop + gridHeight(gridRows.value) + Design.pt(QsTokens.EDIT_SECTION_GAP, u)
        val cp = Design.pt(QsTokens.EDIT_CATEGORY_PADDING, u)
        val cw = (pageR - pageL - 2f * cp - (cols - 1) * gap) / cols
        val labelGap = Design.pt(QsTokens.EDIT_CELL_LABEL_GAP, u)
        val rowGap = Design.pt(QsTokens.EDIT_CELL_ROW_GAP, u)
        val lineH = Design.text(QsTokens.EDIT_CELL_LABEL).lineHeightPt * u
        for ((si, s) in sections.withIndex()) {
            if (si > 0) top += Design.pt(QsTokens.EDIT_CATEGORY_GAP, u)
            var y = top + Design.pt(QsTokens.EDIT_CATEGORY_TILES, u)
            while (s.cells.size < s.offers.size) s.cells += RectF()
            var col = 0
            var rowLines = 1
            for ((j, o) in s.offers.withIndex()) {
                if (col == cols) { y += tileH + labelGap + rowLines * lineH + rowGap; col = 0; rowLines = 1 }
                val x = pageL + cp + col * (cw + gap)
                s.cells[j].set(x, y, x + cw, y + tileH)
                rowLines = max(rowLines, label(QsTiles.name(o.tile), cw).lineCount)
                col++
            }
            val bottom = y + tileH + labelGap + rowLines * lineH + cp
            s.box.set(pageL, top, pageR, bottom)
            top = bottom
        }
        val rh = Design.pt(QsTokens.EDIT_RESET_HEIGHT, u)
        val rt = top + Design.pt(QsTokens.EDIT_RESET_TOP, u)
        val rw = buttonText.let { Design.text(QsTokens.EDIT_BUTTON_TEXT).applyTo(it, u); it.measureText(RESET) } + 2f * Design.pt(QsTokens.EDIT_RESET_PADDING, u)
        val cx = (pageL + pageR) / 2f
        resetRect.set(cx - rw / 2f, rt, cx + rw / 2f, rt + rh)
        contentEnd = resetRect.bottom + Design.pt(QsTokens.EDIT_PAGE_BOTTOM, u) + navInset
    }

    private fun bounds() {
        if (!open) return
        placeSections()
        val viewport = panel.panelHeight - barBottom()
        scroller.setBounds(0f, max(0f, contentEnd - panel.panelHeight), viewport)
    }

    // ------------------------------------------------------------------ words

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val categoryPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val cellPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val buttonText = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val dialogTitle = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val dialogText = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** A tile to add's name broken into at most two lines (the last ellipsized), as Android's: made once per name and width. */
    private class Lines(val lines: List<String>, val widths: FloatArray) { val lineCount get() = lines.size }
    private val labels = HashMap<String, Lines>()
    private fun label(s: String, width: Float): Lines = labels.getOrPut(s + "@" + width.toInt()) {
        Design.text(QsTokens.EDIT_CELL_LABEL).applyTo(cellPaint, u)
        val lay = StaticLayout.Builder.obtain(s, 0, s.length, cellPaint, max(1, width.toInt()))
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build()
        val lines = (0 until lay.lineCount).map { l ->
            val line = s.substring(lay.getLineStart(l), lay.getLineEnd(l)).trimEnd()
            if (l == lay.lineCount - 1 && lay.getEllipsisCount(l) > 0) TextUtils.ellipsize(line, cellPaint, width, TextUtils.TruncateAt.END).toString() else line
        }
        Lines(lines, FloatArray(lines.size) { cellPaint.measureText(lines[it]) })
    }

    private fun style(p: TextPaint, k: TextKey, color: Int, a: Float) {
        Design.text(k).applyTo(p, u)
        p.color = faded(color, a)
    }

    // ------------------------------------------------------------------ drawing

    private val r = RectF()
    private val corners = FloatArray(4)
    private val accent get() = Design.color(QsTokens.EDIT_ACCENT)

    /** The page at [a] (its fade as Quick Settings give way to it). */
    fun draw(c: Canvas, a: Float) {
        if (!open || a <= 0.003f) return
        placeSections()
        val s = scroll()
        val top = barBottom()
        val textColor = Design.color(QsTokens.EDIT_TEXT_COLOR)
        // What scrolls goes under the bar's bottom.
        val save = c.save()
        c.clipRect(0f, top, panel.panelWidth.toFloat(), panel.panelHeight.toFloat())
        c.translate(0f, -s)
        drawTitle(c, a, textColor, s)
        drawBox(c, a)
        drawSections(c, a, textColor)
        drawReset(c, a)
        c.restoreToCount(save)
        drawBar(c, a, textColor, s)
        drawDragged(c, a)
        drawDialog(c, a, textColor)
    }

    private fun titleLine() = Design.text(QsTokens.EDIT_TITLE).lineHeightPt * u
    private fun titleTop() = panel.barHeight + Design.pt(QsTokens.EDIT_TITLE_TOP, u)

    /** How far the title has gone under the bar (0 at rest .. 1 gone: then it shows in the bar). */
    private fun collapsed(s: Float): Float {
        val gone = titleTop() + titleLine() - barBottom()
        return if (gone <= 0f) 1f else (s / gone).coerceIn(0f, 1f)
    }

    private fun drawTitle(c: Canvas, a: Float, color: Int, s: Float) {
        val k = 1f - collapsed(s)
        if (k <= 0f) return
        val x = pageL + Design.pt(QsTokens.EDIT_BAR_START, u)
        style(titlePaint, QsTokens.EDIT_TITLE, color, a * k)
        val tl = titleLine()
        c.drawText(TITLE, x, baselineIn(titlePaint, titleTop(), tl), titlePaint)
        style(subtitlePaint, QsTokens.EDIT_SUBTITLE, color, a * k)
        val sl = Design.text(QsTokens.EDIT_SUBTITLE).lineHeightPt * u
        c.drawText(SUBTITLE, x, baselineIn(subtitlePaint, titleTop() + tl, sl), subtitlePaint)
    }

    private fun boxRect(out: RectF): RectF = out.apply { set(pageL, gridTop, pageR, gridTop + gridHeight(gridRows.value)) }

    private fun drawBox(c: Canvas, a: Float) {
        boxRect(r)
        val corner = Design.pt(QsTokens.EDIT_GRID_CORNER, u)
        val boxFill = Design.material(QsTokens.EDIT_GRID_FILL)
        c.save()
        c.translate(r.left, r.top)
        panel.surfaces.draw(c, boxFill, r.width(), r.height(), corner, r.left, r.top - scroll(), 1f, a)
        c.restore()
        val sw = Design.pt(QsTokens.EDIT_GRID_STROKE, u)
        stroke.strokeWidth = sw
        stroke.color = faded(accent, a)
        r.inset(sw / 2f, sw / 2f)
        c.drawRoundRect(r, corner - sw / 2f, corner - sw / 2f, stroke)
        val g = grid() ?: return
        val ox = boxLeft()
        val oy = gridTop + pad
        val d = drag
        for (cell in cells.values) {
            val shown = cell.shown.value.coerceIn(0f, 1f)
            if (shown <= 0.003f) continue
            r.set(ox + cell.x.value, oy + cell.y.value, ox + cell.x.value + cell.w.value, oy + cell.y.value + tileH)
            if (d != null && d.fromBox && cell.tile.key == d.tile.key) {
                // A lifted tile: left at half at its place until it moves; then where it will land, a dark slot.
                if (!d.moved) g.drawPlain(c, cell.tile, r, a * shown * Design.num(QsTokens.EDIT_LIFTED_ALPHA), boxFill.fills)
                else if (d.slot >= 0) drawSlot(c, r, a * shown)
                continue
            }
            if (d != null && !d.fromBox && cell.tile.key == d.tile.key) { drawSlot(c, r, a * shown); continue }
            g.drawPlain(c, cell.tile, r, a * shown, boxFill.fills)
            val sel = cell.sel.value.coerceIn(0f, 1f)
            if (sel > 0.003f && d == null) drawSelection(c, r, a * shown * sel)
        }
    }

    private fun drawSlot(c: Canvas, r: RectF, a: Float) {
        fill.style = Paint.Style.FILL
        fill.color = faded(Design.color(QsTokens.EDIT_SLOT_COLOR), a)
        val corner = min(Design.pt(QsTokens.TILE_CORNER, u), r.height() / 2f)
        c.drawRoundRect(r, corner, corner, fill)
    }

    /** A chosen tile: its outline inside its edge and the resize handle on the outline at its end. */
    private fun drawSelection(c: Canvas, tr: RectF, a: Float) {
        val sw = Design.pt(QsTokens.EDIT_SELECTION_STROKE, u)
        stroke.strokeWidth = sw
        stroke.color = faded(accent, a)
        val corner = min(Design.pt(QsTokens.TILE_CORNER, u), tr.height() / 2f)
        rr.set(tr.left + sw / 2f, tr.top + sw / 2f, tr.right - sw / 2f, tr.bottom - sw / 2f)
        c.drawRoundRect(rr, corner - sw / 2f, corner - sw / 2f, stroke)
        val hw = Design.pt(QsTokens.EDIT_HANDLE_WIDTH, u)
        val hh = Design.pt(QsTokens.EDIT_HANDLE_HEIGHT, u)
        val cx = tr.right - sw / 2f
        rr.set(cx - hw / 2f, tr.centerY() - hh / 2f, cx + hw / 2f, tr.centerY() + hh / 2f)
        fill.style = Paint.Style.FILL
        fill.color = faded(accent, a)
        c.drawRoundRect(rr, hw / 2f, hw / 2f, fill)
    }
    private val rr = RectF()

    private fun drawSections(c: Canvas, a: Float, textColor: Int) {
        val g = grid() ?: return
        val outer = Design.pt(QsTokens.EDIT_GRID_CORNER, u)
        val inner = Design.pt(QsTokens.EDIT_CATEGORY_INNER_CORNER, u)
        val cp = Design.pt(QsTokens.EDIT_CATEGORY_PADDING, u)
        val sym = Design.pt(QsTokens.EDIT_CATEGORY_SYMBOL, u)
        val labelGap = Design.pt(QsTokens.EDIT_CELL_LABEL_GAP, u)
        val lineH = Design.text(QsTokens.EDIT_CELL_LABEL).lineHeightPt * u
        val addedAlpha = Design.num(QsTokens.EDIT_ADDED_ALPHA)
        val s = scroll()
        val visTop = barBottom() + s
        val visBottom = panel.panelHeight + s
        for ((i, sec) in sections.withIndex()) {
            val b = sec.box
            if (b.bottom < visTop || b.top > visBottom) continue
            val first = i == 0
            val last = i == sections.size - 1
            val tc = if (first) outer else inner
            val bc = if (last) outer else inner
            corners[0] = tc; corners[1] = tc; corners[2] = bc; corners[3] = bc
            c.save()
            c.translate(b.left, b.top)
            val category = Design.material(QsTokens.EDIT_CATEGORY)
            panel.surfaces.draw(c, category, b.width(), b.height(), outer, b.left, b.top - s, 1f, a, radii = corners)
            c.restore()
            // Its heading: the category's symbol and name.
            val hy = b.top + cp + sym / 2f
            panel.glyphs.draw(c, sec.category.symbol, b.left + cp + sym / 2f, hy, sym, faded(textColor, a))
            style(categoryPaint, QsTokens.EDIT_CATEGORY_TEXT, textColor, a)
            val ch = Design.text(QsTokens.EDIT_CATEGORY_TEXT).lineHeightPt * u
            c.drawText(sec.category.title, b.left + cp + sym + Design.pt(QsTokens.EDIT_CATEGORY_SYMBOL_GAP, u), baselineIn(categoryPaint, hy - ch / 2f, ch), categoryPaint)
            for ((j, o) in sec.offers.withIndex()) {
                val cr = sec.cells.getOrNull(j) ?: continue
                if (cr.bottom + 2f * lineH < visTop || cr.top > visBottom) continue
                val added = o.added.value.coerceIn(0f, 1f)
                val ca = a * lerp(1f, addedAlpha, added)
                // The tile as a capsule with its symbol (one column's look, whatever it will be), its name under it.
                g.drawPlain(c, o.tile, cr, ca, category.fills)
                val lay = label(QsTiles.name(o.tile), cr.width())
                style(cellPaint, QsTokens.EDIT_CELL_LABEL, textColor, ca)
                for (l in 0 until lay.lineCount) {
                    c.drawText(lay.lines[l], cr.centerX() - lay.widths[l] / 2f, baselineIn(cellPaint, cr.bottom + labelGap + l * lineH, lineH), cellPaint)
                }
                // Not added yet: the add badge.
                val badge = a * (1f - added)
                if (badge > 0.003f) {
                    val bs = Design.pt(QsTokens.EDIT_BADGE, u)
                    val bx = cr.right - Design.pt(QsTokens.EDIT_BADGE_END, u)
                    val by = cr.top + Design.pt(QsTokens.EDIT_BADGE_TOP, u)
                    fill.style = Paint.Style.FILL
                    fill.color = faded(accent, badge)
                    c.drawCircle(bx, by, bs / 2f, fill)
                    panel.glyphs.draw(c, R.drawable.sym_qs_add, bx, by, Design.pt(QsTokens.EDIT_BADGE_SYMBOL, u), faded(Design.color(QsTokens.EDIT_ON_ACCENT), badge))
                }
            }
        }
    }

    private fun drawReset(c: Canvas, a: Float) {
        drawPill(c, resetRect, RESET, accent, Design.color(QsTokens.EDIT_ON_ACCENT), a, pressK(Press.RESET))
    }

    private fun drawPill(c: Canvas, rect: RectF, text: String, bg: Int, fg: Int, a: Float, press: Float) {
        fill.style = Paint.Style.FILL
        fill.color = faded(mix(bg, fg, Design.num(QsTokens.EDIT_PRESS_LAYER) * press), a)
        c.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill)
        style(buttonText, QsTokens.EDIT_BUTTON_TEXT, fg, a)
        val tw = buttonText.measureText(text)
        c.drawText(text, rect.centerX() - tw / 2f, baselineIn(buttonText, rect.top, rect.height()), buttonText)
    }

    // The bar's buttons (screen coordinates).
    private val backRect = RectF()
    private val undoRect = RectF()
    private val removeRect = RectF()

    private fun placeBar() {
        val cy = barCenter()
        val target = Design.pt(QsTokens.EDIT_HANDLE_TARGET, u)
        val bx = pageL + Design.pt(QsTokens.EDIT_BAR_START, u) + Design.pt(QsTokens.EDIT_BAR_SYMBOL, u) / 2f
        backRect.set(bx - target / 2f, cy - target / 2f, bx + target / 2f, cy + target / 2f)
        Design.text(QsTokens.EDIT_BUTTON_TEXT).applyTo(buttonText, u)
        val rw = buttonText.measureText(REMOVE) + 2f * Design.pt(QsTokens.EDIT_BUTTON_PADDING, u)
        val rh = Design.pt(QsTokens.EDIT_BUTTON_HEIGHT, u)
        val right = pageR - Design.pt(QsTokens.EDIT_BAR_END, u)
        removeRect.set(right - rw, cy - rh / 2f, right, cy + rh / 2f)
        val ux = removeRect.left - target / 2f
        undoRect.set(ux - target / 2f, cy - target / 2f, ux + target / 2f, cy + target / 2f)
    }

    private fun drawBar(c: Canvas, a: Float, textColor: Int, s: Float) {
        placeBar()
        val cy = barCenter()
        val sym = Design.pt(QsTokens.EDIT_BAR_SYMBOL, u)
        pressLayer(c, backRect.centerX(), cy, Design.pt(QsTokens.EDIT_UNDO, u) / 2f, textColor, a * pressK(Press.BACK))
        panel.glyphs.draw(c, R.drawable.sym_qs_back, backRect.centerX(), cy, sym, faded(textColor, a))
        // Scrolled away, the title sits in the bar, as large.
        val k = collapsed(s).let { titleIn(it) }
        if (k > 0.003f) {
            style(titlePaint, QsTokens.EDIT_TITLE, textColor, a * k)
            val tl = titleLine()
            c.drawText(TITLE, pageL + Design.pt(QsTokens.EDIT_COLLAPSED_TITLE_START, u), baselineIn(titlePaint, cy - tl / 2f, tl), titlePaint)
        }
        // Undo, once something changed.
        val uk = undoK.value.coerceIn(0f, 1f)
        if (uk > 0.003f) {
            val d = Design.pt(QsTokens.EDIT_UNDO, u)
            c.save()
            c.translate(undoRect.centerX() - d / 2f, cy - d / 2f)
            panel.surfaces.draw(c, Design.material(QsTokens.EDIT_UNDO_MATERIAL), d, d, d / 2f, undoRect.centerX() - d / 2f, cy - d / 2f, 1f, a * uk)
            c.restore()
            pressLayer(c, undoRect.centerX(), cy, d / 2f, textColor, a * uk * pressK(Press.UNDO))
            panel.glyphs.draw(c, R.drawable.sym_qs_undo, undoRect.centerX(), cy, sym, faded(textColor, a * uk))
        }
        // Remove: lit while a tile is chosen.
        val on = removeOn.value.coerceIn(0f, 1f)
        val bg = mix(Design.color(QsTokens.EDIT_BUTTON_OFF_FILL), accent, on)
        val fg = mix(Design.color(QsTokens.EDIT_BUTTON_OFF_TEXT), Design.color(QsTokens.EDIT_ON_ACCENT), on)
        drawPill(c, removeRect, REMOVE, bg, fg, a, pressK(Press.REMOVE))
    }

    /** Material's pressed state: a layer of the content's colour over the button. */
    private fun pressLayer(c: Canvas, cx: Float, cy: Float, radius: Float, color: Int, k: Float) {
        if (k <= 0.003f) return
        fill.style = Paint.Style.FILL
        fill.color = faded(color, Design.num(QsTokens.EDIT_PRESS_LAYER) * k)
        c.drawCircle(cx, cy, radius, fill)
    }

    /** Material's top app bar: the title in the bar comes in late as the large one scrolls away (TopTitleAlphaEasing). */
    private fun titleIn(f: Float): Float {
        val x = f.coerceIn(0f, 1f).toDouble()
        val s = dev.launcher.app.motion.BezierMath.solveS(x, 0.8, 0.8)
        return dev.launcher.app.motion.BezierMath.y(s, 0.0, 0.15).toFloat()
    }

    /** The dragged tile under the finger (Android's drag shadow), or one let go fading where it was. */
    private fun drawDragged(c: Canvas, a: Float) {
        val g = grid() ?: return
        for (d in listOfNotNull(drag, dropped)) {
            val k = d.k.value.coerceIn(0f, 1f)
            if (k <= 0.003f) continue
            if (d === drag && d.fromBox && !d.moved) continue
            val w = if (d.tile.wide) 2f * cellW + gap else cellW
            r.set(d.fx - w / 2f, d.fy - tileH / 2f, d.fx + w / 2f, d.fy + tileH / 2f)
            g.drawPlain(c, d.tile, r, a * k * Design.num(QsTokens.EDIT_SHADOW_ALPHA))
        }
    }

    private val dialogBox = RectF()
    private val okRect = RectF()
    private val cancelRect = RectF()

    private fun drawDialog(c: Canvas, a: Float, textColor: Int) {
        val k = dialogK.value.coerceIn(0f, 1f)
        if (k <= 0.003f) return
        fill.style = Paint.Style.FILL
        fill.color = faded(Design.color(QsTokens.EDIT_DIALOG_SCRIM), a * k)
        c.drawRect(0f, 0f, panel.panelWidth.toFloat(), panel.panelHeight.toFloat(), fill)
        placeDialog()
        val b = dialogBox
        val sc = lerp(Design.num(QsTokens.EDIT_DIALOG_FROM), 1f, k)
        c.save()
        c.scale(sc, sc, b.centerX(), b.centerY())
        c.save()
        c.translate(b.left, b.top)
        panel.surfaces.draw(c, Design.material(QsTokens.EDIT_DIALOG), b.width(), b.height(), Design.pt(QsTokens.EDIT_DIALOG_CORNER, u), b.left, b.top, sc, a * k)
        c.restore()
        val p = Design.pt(QsTokens.EDIT_DIALOG_PADDING, u)
        style(dialogTitle, QsTokens.EDIT_DIALOG_TITLE, textColor, a * k)
        val tl = Design.text(QsTokens.EDIT_DIALOG_TITLE).lineHeightPt * u
        val tw = dialogTitle.measureText(DIALOG_TITLE)
        c.drawText(DIALOG_TITLE, b.centerX() - tw / 2f, baselineIn(dialogTitle, b.top + p, tl), dialogTitle)
        style(dialogText, QsTokens.EDIT_DIALOG_TEXT, Design.color(QsTokens.EDIT_DIALOG_TEXT_COLOR), a * k)
        val lay = dialogLayout(b.width() - 2f * p)
        val ll = Design.text(QsTokens.EDIT_DIALOG_TEXT).lineHeightPt * u
        val ty = b.top + p + tl + Design.pt(QsTokens.EDIT_DIALOG_TEXT_GAP, u)
        for (l in 0 until lay.lineCount) {
            val line = lay.text.subSequence(lay.getLineStart(l), lay.getLineEnd(l)).toString().trim()
            val lw = dialogText.measureText(line)
            c.drawText(line, b.centerX() - lw / 2f, baselineIn(dialogText, ty + l * ll, ll), dialogText)
        }
        drawPill(c, okRect, OK, accent, Design.color(QsTokens.EDIT_ON_ACCENT), a * k, pressK(Press.OK))
        // Cancel: a text button in the accent.
        pressLayerRect(c, cancelRect, accent, a * k * pressK(Press.CANCEL))
        style(buttonText, QsTokens.EDIT_BUTTON_TEXT, accent, a * k)
        val cw = buttonText.measureText(CANCEL)
        c.drawText(CANCEL, cancelRect.centerX() - cw / 2f, baselineIn(buttonText, cancelRect.top, cancelRect.height()), buttonText)
        c.restore()
    }

    private fun pressLayerRect(c: Canvas, rect: RectF, color: Int, k: Float) {
        if (k <= 0.003f) return
        fill.style = Paint.Style.FILL
        fill.color = faded(color, Design.num(QsTokens.EDIT_PRESS_LAYER) * k)
        c.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill)
    }

    private var dialogLay: StaticLayout? = null
    private fun dialogLayout(width: Float): StaticLayout {
        val w = max(1, width.toInt())
        dialogLay?.let { if (it.width == w && it.paint.textSize == dialogText.textSize) return it }
        return StaticLayout.Builder.obtain(DIALOG_TEXT, 0, DIALOG_TEXT.length, dialogText, w)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build().also { dialogLay = it }
    }

    private fun placeDialog() {
        val p = Design.pt(QsTokens.EDIT_DIALOG_PADDING, u)
        Design.text(QsTokens.EDIT_DIALOG_TEXT).applyTo(dialogText, u)
        val w = pageR - pageL
        val lay = dialogLayout(w - 2f * p)
        val ll = Design.text(QsTokens.EDIT_DIALOG_TEXT).lineHeightPt * u
        val bh = Design.pt(QsTokens.EDIT_BUTTON_HEIGHT, u)
        val h = p + Design.text(QsTokens.EDIT_DIALOG_TITLE).lineHeightPt * u + Design.pt(QsTokens.EDIT_DIALOG_TEXT_GAP, u) +
            lay.lineCount * ll + Design.pt(QsTokens.EDIT_DIALOG_BUTTONS_GAP, u) + bh + p
        val top = (panel.barHeight + (panel.panelHeight - navInset)) / 2f - h / 2f
        dialogBox.set(pageL, top, pageR, top + h)
        Design.text(QsTokens.EDIT_BUTTON_TEXT).applyTo(buttonText, u)
        val okW = buttonText.measureText(OK) + 2f * Design.pt(QsTokens.EDIT_DIALOG_BUTTON_PADDING, u)
        val by = dialogBox.bottom - p - bh
        okRect.set(dialogBox.right - p - okW, by, dialogBox.right - p, by + bh)
        // Cancel: its word 12 dp inside a box that ends 8 dp before OK (Material's text button).
        val cw = buttonText.measureText(CANCEL) + 2f * Design.pt(QsTokens.EDIT_TEXT_BUTTON_PADDING, u)
        val cRight = okRect.left - Design.pt(QsTokens.EDIT_DIALOG_BUTTON_GAP, u)
        cancelRect.set(cRight - cw, by, cRight, by + bh)
    }

    private fun showDialog(show: Boolean) {
        dialog = show
        dialogK.animateTo(if (show) 1f else 0f, Motion.role(MotionTokens.QS_EDIT_APPEAR))
    }

    // ------------------------------------------------------------------ touches

    private enum class Press { NONE, BACK, UNDO, REMOVE, RESET, OK, CANCEL }
    private val presses = HashMap<Press, MotionValue>()
    private fun pressK(p: Press) = presses[p]?.value?.coerceIn(0f, 1f) ?: 0f
    private fun pressDown(p: Press, down: Boolean) {
        if (p == Press.NONE) return
        val v = presses.getOrPut(p) { MotionValue(0f, 1000f, { inv() }) }
        v.animateTo(if (down) 1f else 0f, Motion.role(MotionTokens.QS_TILE_STATE))
    }

    private enum class Mode { NONE, BUTTON, TILE, HANDLE, OFFER, SCROLL, DRAG, RESIZE, DIALOG, IGNORE }
    private var mode = Mode.NONE
    private var press = Press.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var decided = false
    private var touchedKey: String? = null
    private var touchedOffer: Offer? = null
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(panel.view.context).scaledTouchSlop.toFloat()
    private val resizing get() = mode == Mode.RESIZE

    private val longPress = Runnable {
        if (decided) return@Runnable
        val t = when (mode) {
            Mode.TILE -> tiles.firstOrNull { it.key == touchedKey }?.let { it to true }
            Mode.OFFER -> touchedOffer?.takeIf { it.added.target < 0.5f }?.let { it.tile to false }
            else -> null
        } ?: return@Runnable
        startDrag(t.first, t.second)
    }

    /** All touches while the page is open. */
    fun touch(e: MotionEvent) {
        val x = e.x
        val y = e.y
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { vt?.recycle(); vt = VelocityTracker.obtain() }
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> down(x, y)
            MotionEvent.ACTION_MOVE -> move(x, y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.computeCurrentVelocity(1000)
                up(x, y, e.actionMasked == MotionEvent.ACTION_UP, vt?.yVelocity ?: 0f)
                vt?.recycle(); vt = null
            }
        }
    }

    private fun down(x: Float, y: Float) {
        downX = x; downY = y; lastY = y
        decided = false
        press = Press.NONE
        touchedKey = null
        touchedOffer = null
        val moving = scroller.isMovingVisibly()
        scroller.stop()
        if (dialog) {
            placeDialog()
            mode = Mode.DIALOG
            press = when {
                okRect.contains(x, y) -> Press.OK
                cancelRect.contains(x, y) -> Press.CANCEL
                else -> Press.NONE
            }
            pressDown(press, true)
            return
        }
        placeBar()
        placeSections()
        val cy = y + scroll()
        press = when {
            backRect.contains(x, y) -> Press.BACK
            undoK.target > 0.5f && undoRect.contains(x, y) -> Press.UNDO
            removeRect.contains(x, y) -> Press.REMOVE
            else -> Press.NONE
        }
        if (press != Press.NONE) { mode = Mode.BUTTON; pressDown(press, true); return }
        if (y < barBottom() || moving) { mode = Mode.NONE; return }
        if (handleAt(x, cy)) { mode = Mode.HANDLE; return }
        tileAt(x, cy)?.let { k ->
            mode = Mode.TILE
            touchedKey = k
            panel.view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            return
        }
        offerAt(x, cy)?.let { o ->
            mode = Mode.OFFER
            touchedOffer = o
            panel.view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            return
        }
        if (resetRect.contains(x, cy)) { mode = Mode.BUTTON; press = Press.RESET; pressDown(press, true); return }
        mode = Mode.NONE
    }

    private fun move(x: Float, y: Float) {
        val dx = x - downX
        val dy = y - downY
        if (!decided && hypot(dx, dy) > slop) {
            decided = true
            panel.view.removeCallbacks(longPress)
            when (mode) {
                Mode.BUTTON -> { pressDown(press, false); press = Press.NONE; mode = Mode.IGNORE }
                Mode.HANDLE -> if (abs(dx) > abs(dy)) beginResize() else beginScroll()
                Mode.DRAG -> {}
                Mode.DIALOG -> { pressDown(press, false); press = Press.NONE }
                else -> beginScroll()
            }
        }
        when (mode) {
            Mode.SCROLL -> scroller.dragBy(lastY - y)
            Mode.DRAG -> dragTo(x, y)
            Mode.RESIZE -> resizeTo(dx)
            else -> {}
        }
        lastY = y
    }

    private fun beginScroll() {
        mode = Mode.SCROLL
        scroller.beginDrag()
    }

    private fun up(x: Float, y: Float, up: Boolean, vy: Float) {
        panel.view.removeCallbacks(longPress)
        val tap = up && !decided
        when (mode) {
            Mode.BUTTON -> {
                pressDown(press, false)
                if (tap) when (press) {
                    Press.BACK -> { haptic(HapticFeedbackConstants.CONTEXT_CLICK); exit() }
                    Press.UNDO -> undo()
                    Press.REMOVE -> if (selected != null) remove()
                    Press.RESET -> { haptic(HapticFeedbackConstants.CONTEXT_CLICK); showDialog(true) }
                    else -> {}
                }
            }
            Mode.DIALOG -> {
                pressDown(press, false)
                if (tap) when {
                    press == Press.OK -> { showDialog(false); reset() }
                    press == Press.CANCEL -> showDialog(false)
                    !dialogBox.contains(x, y) -> showDialog(false)
                }
            }
            Mode.TILE -> if (tap) touchedKey?.let { if (it != selected) haptic(HapticFeedbackConstants.CLOCK_TICK); select(it) }
            Mode.HANDLE -> if (tap) selected?.let { k -> tiles.firstOrNull { it.key == k }?.let { resize(k, !it.wide) } }
            Mode.OFFER -> if (tap) touchedOffer?.let { if (it.added.target < 0.5f) add(it.tile) }
            Mode.SCROLL -> scroller.endDrag(-vy)
            Mode.DRAG -> endDrag()
            Mode.RESIZE -> endResize()
            else -> {}
        }
        mode = Mode.NONE
        press = Press.NONE
    }

    // ---- hit tests (content coordinates)

    private fun cellRect(c: Cell, out: RectF): RectF =
        out.apply { set(boxLeft() + c.x.value, gridTop + pad + c.y.value, boxLeft() + c.x.value + c.w.value, gridTop + pad + c.y.value + tileH) }

    private fun tileAt(x: Float, cy: Float): String? {
        for (c in cells.values) {
            if (c.leaving) continue
            if (cellRect(c, r).contains(x, cy)) return c.tile.key
        }
        return null
    }

    private fun handleAt(x: Float, cy: Float): Boolean {
        val c = cells[selected ?: return false] ?: return false
        if (c.sel.target < 0.5f) return false
        cellRect(c, r)
        val t = Design.pt(QsTokens.EDIT_HANDLE_TARGET, u) / 2f
        return abs(x - (r.right - Design.pt(QsTokens.EDIT_SELECTION_STROKE, u) / 2f)) <= t && abs(cy - r.centerY()) <= t
    }

    private fun offerAt(x: Float, cy: Float): Offer? {
        val lineH = Design.text(QsTokens.EDIT_CELL_LABEL).lineHeightPt * u
        val labelGap = Design.pt(QsTokens.EDIT_CELL_LABEL_GAP, u)
        for (s in sections) {
            if (!s.box.contains(x, cy)) continue
            for ((i, cr) in s.cells.withIndex()) {
                if (x >= cr.left && x <= cr.right && cy >= cr.top && cy <= cr.bottom + labelGap + 2f * lineH) return s.offers.getOrNull(i)
            }
        }
        return null
    }

    // ---- dragging a tile

    private fun startDrag(t: QsTile, fromBox: Boolean) {
        haptic(HapticFeedbackConstants.LONG_PRESS)
        scroller.stop()
        val d = Drag(t, fromBox)
        d.fx = downX; d.fy = downY
        d.slot = if (fromBox) tiles.indexOfFirst { it.key == t.key } else -1
        d.k.animateTo(1f, Motion.role(MotionTokens.QS_TILE_STATE))
        drag = d
        dropped = null
        mode = Mode.DRAG
        decided = true
        if (fromBox) select(t.key)
        reflow(animate = true)
    }

    private fun dragTo(x: Float, y: Float) {
        val d = drag ?: return
        d.fx = x; d.fy = y
        if (!d.moved && hypot(x - downX, y - downY) > slop) d.moved = true
        val slot = slotAt(x, y + scroll(), d)
        if (slot != d.slot) { d.slot = slot; reflow(animate = true) }
        autoScrollIfNear(y)
        inv()
    }

    /** Where the dragged tile would land with the finger at ([x], [cy]): the place of the tile under it, else the end of the box. */
    private fun slotAt(x: Float, cy: Float, d: Drag): Int {
        boxRect(r)
        if (!r.contains(x, cy)) return -1
        val order = arrangement()
        for ((i, t) in order.withIndex()) {
            val c = cells[t.key] ?: continue
            if (t.key == d.tile.key) {
                if (cellRect(c, rr).contains(x, cy)) return d.slot
                continue
            }
            if (cellRect(c, rr).contains(x, cy)) return i
        }
        // Past the last tile: the end; elsewhere in the box (a gap): where it was.
        val lastKey = order.lastOrNull { it.key != d.tile.key }?.key
        val last = lastKey?.let { cells[it] }
        val base = if (d.fromBox) tiles.size - 1 else tiles.size
        if (last == null) return 0
        cellRect(last, rr)
        return if (cy > rr.bottom || (cy >= rr.top && x > rr.right)) base else if (d.slot >= 0) d.slot else base
    }

    private fun endDrag() {
        val d = drag ?: return
        panel.view.removeCallbacks(autoScroll)
        drag = null
        if (d.slot >= 0) {
            val base = if (d.fromBox) tiles.filter { it.key != d.tile.key } else ArrayList(tiles)
            val next = ArrayList(base).apply { add(d.slot.coerceIn(0, size), d.tile) }
            // The tile flies from under the finger into its place.
            if (d.moved) cells[d.tile.key]?.let { c ->
                c.x.snapTo(d.fx - cellW.let { if (d.tile.wide) 2f * it + gap else it } / 2f - boxLeft())
                c.y.snapTo(d.fy + scroll() - tileH / 2f - gridTop - pad)
            }
            selected = d.tile.key
            if (d.fromBox) haptic(HapticFeedbackConstants.CLOCK_TICK)
            commit(next)
            reflow(animate = true)
        } else {
            // Out of the box: a tile from it is taken out; one to add was not added. Its picture fades where it was.
            dropped = d
            d.k.animateTo(0f, Motion.role(MotionTokens.QS_EDIT_APPEAR))
            if (d.fromBox) {
                val i = tiles.indexOfFirst { it.key == d.tile.key }
                cells.remove(d.tile.key)
                haptic(HapticFeedbackConstants.CONTEXT_CLICK)
                selected = null
                commit(tiles.filter { it.key != d.tile.key }, prefer = i)
            } else { cells.remove(d.tile.key); reflow(animate = true) }
        }
    }

    /** While a tile is dragged near the page's top or bottom, the page scrolls toward it. */
    private var autoDir = 0f
    private var autoNs = 0L
    private val autoScroll = object : Runnable {
        override fun run() {
            val d = drag ?: return
            if (autoDir == 0f) return
            val now = System.nanoTime()
            val dt = ((now - autoNs) / 1e9f).coerceIn(0f, 0.05f)
            autoNs = now
            val before = scroll()
            scroller.jumpTo((before + autoDir * Design.pt(QsTokens.EDIT_AUTO_SCROLL_SPEED, u) * dt).coerceIn(0f, scroller.maxPos))
            if (scroll() != before) dragTo(d.fx, d.fy)
            panel.view.postOnAnimation(this)
        }
    }

    /** Android's AutoScrollGrid: the finger within its zone of the page's top or bottom scrolls it, faster the deeper. */
    private fun autoScrollIfNear(y: Float) {
        val zone = Design.pt(QsTokens.EDIT_AUTO_SCROLL_ZONE, u)
        val top = barBottom()
        val bottom = panel.panelHeight - navInset
        val dir = when {
            y < top + zone -> -((top + zone - y) / zone).coerceIn(0f, 1f)
            y > bottom - zone -> ((y - (bottom - zone)) / zone).coerceIn(0f, 1f)
            else -> 0f
        }
        val was = autoDir
        autoDir = dir
        if (dir != 0f && was == 0f) { autoNs = System.nanoTime(); panel.view.postOnAnimation(autoScroll) }
    }

    // ---- resizing the chosen tile by its handle

    private fun beginResize() {
        val c = cells[selected ?: return] ?: return
        mode = Mode.RESIZE
        resizeFrom = c.w.value
        resizeWide = c.tile.wide
    }

    /** The tile's width follows the finger between one column and two; past halfway it becomes the other size. */
    private fun resizeTo(dx: Float) {
        val key = selected ?: return
        val c = cells[key] ?: return
        val small = cellW
        val large = 2f * cellW + gap
        val w = (resizeFrom + dx).coerceIn(small, large)
        c.w.snapTo(w)
        val wide = w > (small + large) / 2f
        val t = tiles.firstOrNull { it.key == key } ?: return
        if (t.wide != wide) {
            haptic(HapticFeedbackConstants.CLOCK_TICK)
            // (The other tiles make way at once; the change is kept, one undo step for the whole drag.)
            tiles = ArrayList(tiles.map { if (it.key == key) it.sized(wide) else it })
            reflow(animate = true)
        }
    }

    private fun endResize() {
        val key = selected ?: return
        val now = tiles.firstOrNull { it.key == key } ?: return
        if (now.wide != resizeWide) {
            // Kept as one change (undo gives the old size back).
            tiles = ArrayList(tiles.map { if (it.key == key) it.sized(resizeWide) else it })
            commit(tiles.map { if (it.key == key) it.sized(now.wide) else it })
        }
        val c = cells[key] ?: return
        c.w.animateTo(if (now.wide) 2f * cellW + gap else cellW, Motion.role(MotionTokens.QS_EDIT_PLACE))
    }

    private fun haptic(kind: Int) { panel.view.performHapticFeedback(kind) }

    private companion object {
        const val TITLE = "Edit tiles"
        const val SUBTITLE = "Select tiles to rearrange and resize"
        const val REMOVE = "Remove"
        const val RESET = "Reset"
        const val OK = "OK"
        const val CANCEL = "Cancel"
        const val DIALOG_TITLE = "Reset all tiles?"
        const val DIALOG_TEXT = "All Quick Settings tiles will reset to the device’s original settings"
    }
}
