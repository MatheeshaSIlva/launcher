package dev.launcher.app.drawer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import dev.launcher.app.apps.AppEntry
import kotlin.math.max
import kotlin.math.min

/**
 * The App Library's alphabetical list: section letters, a row per app, and an index on the right edge to jump between
 * letters (with a tick per letter). With a query it shows only the matches, best first, without sections.
 */
@SuppressLint("ViewConstructor")
internal class ListPane(ctx: Context, private val lib: AppLibraryView) : View(ctx) {
    private val m = lib.m
    val scroller = dev.launcher.app.motion.IosScroller({ invalidate() }, { lib.settled() })
    private val touch = TapOrScroll(ctx)

    private sealed class Row { data class Header(val letter: Char) : Row(); data class Item(val e: AppEntry) : Row() }
    private var apps: List<AppEntry> = emptyList()
    private var query = ""
    private var rows: List<Row> = emptyList()
    private var tops = FloatArray(0)
    private var sections: List<Pair<Char, Int>> = emptyList()
    private var imeInset = 0

    private val labels = LabelPainter(m.listText, 0xFFFFFFFF.toInt(), Paint.Align.LEFT)
    private val headers = LabelPainter(m.listHeaderText, 0x99FFFFFF.toInt(), Paint.Align.LEFT, dev.launcher.app.theme.Fonts.text(600))
    private val index = LabelPainter(m.pt(11f), 0xFF0A84FF.toInt(), Paint.Align.CENTER, dev.launcher.app.theme.Fonts.text(600))
    private val separator = Paint().apply { color = 0x1FFFFFFF; strokeWidth = max(1f, m.pt(0.5f)) }
    private val fade = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val r = RectF()
    private var pressedRow = -1
    private var scrubbing = false
    private var lastLetter = ' '

    val isIdle get() = !scroller.isSettling && !scroller.isDragging

    fun setApps(list: List<AppEntry>) { apps = list; rebuild() }

    fun setQuery(q: String) {
        val t = q.trim()
        if (t == query) return
        query = t
        rebuild()
        scroller.jumpTo(0f)
    }

    fun setImeInset(px: Int) { imeInset = px; updateBounds(); invalidate() }

    fun firstResult(): AppEntry? = rows.firstNotNullOfOrNull { (it as? Row.Item)?.e }

    private fun rebuild() {
        val out = ArrayList<Row>()
        val secs = ArrayList<Pair<Char, Int>>()
        if (query.isEmpty()) {
            var current = '\u0000'
            for (e in apps) {
                val c = sectionOf(e.label)
                if (c != current) { current = c; secs += c to out.size; out += Row.Header(c) }
                out += Row.Item(e)
            }
        } else {
            val q = query.lowercase()
            apps.mapNotNull { e -> rank(e.label.lowercase(), q)?.let { e to it } }
                .sortedBy { it.second }
                .forEach { out += Row.Item(it.first) }
        }
        rows = out
        sections = secs
        tops = FloatArray(out.size)
        var y = 0f
        for ((i, row) in out.withIndex()) { tops[i] = y; y += if (row is Row.Header) m.listHeader else m.listRow }
        labels.clear()
        updateBounds()
        invalidate()
    }

    private fun sectionOf(label: String): Char {
        val c = label.firstOrNull()?.uppercaseChar() ?: '#'
        return if (c in 'A'..'Z') c else '#'
    }

    /** 0 = starts with the query, 1 = a word starts with it, 2 = contains it; null = no match. */
    private fun rank(label: String, q: String): Int? = when {
        label.startsWith(q) -> 0
        label.split(' ', '-', '.').any { it.startsWith(q) } -> 1
        label.contains(q) -> 2
        else -> null
    }

    private fun contentHeight() = m.tilesTop + (if (tops.isEmpty()) 0f else tops.last() + m.listRow) + m.bottomSafe + imeInset

    private fun updateBounds() {
        if (height == 0) return
        scroller.setBounds(0f, max(0f, contentHeight() - height), height.toFloat())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateBounds()
        fade.shader = LinearGradient(0f, m.searchTop + m.searchHeight * 0.6f, 0f, m.tilesTop - m.pt(2f),
            0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
    }

    private fun rowTop(i: Int) = m.tilesTop + tops[i] - scroller.position

    private fun visibleRows(): IntRange {
        if (rows.isEmpty()) return IntRange.EMPTY
        var lo = 0
        var hi = rows.size - 1
        val y = scroller.position - m.listRow
        while (lo < hi) { val mid = (lo + hi) / 2; if (tops[mid] < y) lo = mid + 1 else hi = mid }
        var last = lo
        while (last < rows.size - 1 && rowTop(last + 1) < height) last++
        return lo..last
    }

    private fun iconRect(i: Int, out: RectF): RectF {
        val t = rowTop(i) + (m.listRow - m.listIcon) / 2f
        return out.apply { set(m.libMargin, t, m.libMargin + m.listIcon, t + m.listIcon) }
    }

    fun iconRectOf(e: AppEntry): RectF? {
        val i = rows.indexOfFirst { it is Row.Item && it.e.key == e.key }
        return if (i < 0) null else iconRect(i, RectF())
    }

    fun visibleIcons(out: MutableList<Pair<String, RectF>>) {
        for (i in visibleRows()) {
            val row = rows[i] as? Row.Item ?: continue
            val rect = iconRect(i, RectF())
            if (rect.centerY() > m.tilesTop && rect.centerY() < height - imeInset) out += row.e.pkg to rect
        }
    }

    // ------------------------------------------------------------------ touch

    private fun inIndex(x: Float) = query.isEmpty() && sections.isNotEmpty() && x > width - m.listSideIndex * 1.6f

    private fun scrubTo(y: Float) {
        val top = m.tilesTop
        val bottom = height - m.bottomSafe - imeInset
        val k = ((y - top) / (bottom - top)).coerceIn(0f, 0.999f)
        val letter = LETTERS[(k * LETTERS.size).toInt()]
        if (letter == lastLetter) return
        lastLetter = letter
        val target = sections.firstOrNull { it.first >= letter } ?: sections.last()
        scroller.jumpTo(tops[target.second])
        lib.haptic()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && inIndex(e.x)) {
            scrubbing = true
            lastLetter = ' '
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (scrubbing) {
            scrubTo(e.y)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) { scrubbing = false; lib.settled() }
            return true
        }
        val dy = touch.onEvent(e) {
            parent?.requestDisallowInterceptTouchEvent(true)
            scroller.beginDrag()
            setPressed(-1)
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touch.stoppedMotion = scroller.isSettling
                scroller.stop()
                if (!touch.stoppedMotion) setPressed(rowAt(e.y))
            }
            MotionEvent.ACTION_MOVE -> if (touch.scrolling) scroller.dragBy(-dy) else if (touch.moved) setPressed(-1)
            MotionEvent.ACTION_UP -> {
                if (touch.scrolling) scroller.endDrag(-touch.velocityY())
                else if (touch.isTap) {
                    val i = rowAt(e.y)
                    (rows.getOrNull(i) as? Row.Item)?.let { lib.launch(it.e, iconRect(i, RectF())) }
                }
                setPressed(-1)
            }
            MotionEvent.ACTION_CANCEL -> { if (touch.scrolling) scroller.endDrag(0f); setPressed(-1) }
        }
        return true
    }

    private fun rowAt(y: Float): Int {
        if (y < m.tilesTop) return -1
        for (i in visibleRows()) {
            val t = rowTop(i)
            val h = if (rows[i] is Row.Header) m.listHeader else m.listRow
            if (y >= t && y < t + h) return if (rows[i] is Row.Item) i else -1
        }
        return -1
    }

    private fun setPressed(i: Int) { if (pressedRow != i) { pressedRow = i; invalidate() } }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        // Scroll edge, as in the tiles: a layer over the band under the search field only.
        val band = c.saveLayer(0f, 0f, width.toFloat(), m.tilesTop, null)
        drawRows(c)
        c.drawRect(0f, 0f, width.toFloat(), m.tilesTop, fade)
        c.restoreToCount(band)
        c.save()
        c.clipRect(0f, m.tilesTop, width.toFloat(), height.toFloat())
        drawRows(c)
        c.restore()
        drawIndex(c)
    }

    private fun drawRows(c: Canvas) {
        val textX = m.libMargin + m.listIcon + m.pt(14f)
        val rightEdge = width - m.libMargin - (if (query.isEmpty()) m.listSideIndex else 0f)
        for (i in visibleRows()) {
            val t = rowTop(i)
            when (val row = rows[i]) {
                is Row.Header -> headers.draw(c, "h${row.letter}", row.letter.toString(), m.libMargin, headers.baselineFor(t + m.listHeader * 0.62f), m.w.toFloat())
                is Row.Item -> {
                    if (i == pressedRow) { r.set(0f, t, width.toFloat(), t + m.listRow); c.drawRect(r, PRESS) }
                    iconRect(i, r)
                    if (!lib.isHidden(row.e, r)) lib.iconPainter.draw(c, row.e, r)
                    labels.draw(c, row.e.key, row.e.label, textX, labels.baselineFor(t + m.listRow / 2f), rightEdge - textX)
                    if (rows.getOrNull(i + 1) is Row.Item) c.drawLine(textX, t + m.listRow, rightEdge, t + m.listRow, separator)
                }
            }
        }
    }

    private fun drawIndex(c: Canvas) {
        if (query.isEmpty() && sections.isNotEmpty()) {
            val top = m.tilesTop
            val bottom = height - m.bottomSafe - imeInset
            val step = (bottom - top) / LETTERS.size
            val x = width - m.listSideIndex / 2f - m.pt(4f)
            for ((k, l) in LETTERS.withIndex()) {
                val cy = top + step * (k + 0.5f)
                index.draw(c, "i$l", l.toString(), x, index.baselineFor(cy), m.listSideIndex)
            }
        }
    }

    private companion object {
        val LETTERS = ('A'..'Z').toList() + '#'
        val PRESS = Paint().apply { color = 0x1AFFFFFF }
    }
}
