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
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import dev.launcher.app.apps.AppEntry
import dev.launcher.app.home.HomeMetrics
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.SpringValue
import dev.launcher.app.theme.Fonts
import kotlin.math.abs
import kotlin.math.max

/**
 * The searchable app list shared by the App Library and Spotlight: section letters, a row per app, and an index on the right
 * edge to jump between letters. Scrubbing the index is animated as on iOS: the column lights up under the finger (a capsule
 * behind it, the letters near the finger magnified), a glass bubble with the letter pops in and glides along with the
 * finger, and the list springs to each letter's section carrying its motion from the last one (never a jump). With a query
 * it shows only the matches, best first, without sections.
 *
 * Changes animate: rows that stay glide to their new place, new rows fade and rise in, rows that leave fade out where they
 * were (section headers too), so typing never makes the list jump. Everything is in the glass palette (white at various
 * strengths), no accent colour. Rows are drawn directly (no child views) and scroll with iOS physics.
 *
 * [contentTop] is where the first row sits (when not scrolled), [fadeTop] where the scroll-edge fade starts above it, and
 * [bottomSpace] what is reserved at the bottom (a search field, the keyboard).
 */
@SuppressLint("ViewConstructor")
internal class SearchList(
    ctx: Context,
    private val m: HomeMetrics,
    private val icons: IconPainter,
    private val onLaunch: (AppEntry, RectF) -> Unit,
    private val isHidden: (AppEntry, RectF) -> Boolean,
    private val haptic: () -> Unit,
    private val settled: () -> Unit,
) : View(ctx) {
    var contentTop = m.tilesTop
        set(v) { field = v; updateBounds(); updateFade(); invalidate() }
    var fadeTop = m.searchTop + m.searchHeight * 0.6f
        set(v) { field = v; updateFade(); invalidate() }
    var bottomSpace = 0f
        set(v) { field = v; updateBounds(); invalidate() }
    /** Extra room after the last row (rows scroll up from under something drawn over the list, e.g. a search field). */
    var bottomPadding = 0f
        set(v) { field = v; updateBounds() }
    /**
     * Spotlight's presentation of results: the best match highlighted in a card ("Top Hit"), the other apps under an "Apps"
     * title; no sections or index. The App Library uses the plain A–Z list.
     */
    var spotlight = false
    /** Draws a glass card (the Top Hit's) at a rect in this view's coordinates. */
    var drawCard: ((Canvas, RectF) -> Unit)? = null
    /** Called after every draw (something mirroring the list, e.g. a frosted field over it, redraws too). */
    var onDrawn: (() -> Unit)? = null

    val scroller = dev.launcher.app.motion.IosScroller({ invalidate() }, { settled() })
    private val touch = TapOrScroll(ctx)

    private sealed class Row {
        abstract val key: String
        data class Header(val letter: Char) : Row() { override val key get() = "h:$letter" }
        data class Item(val e: AppEntry) : Row() { override val key get() = e.key }
        data class Title(val text: String) : Row() { override val key get() = "t:$text" }
        data class TopHit(val e: AppEntry) : Row() { override val key get() = "top:${e.key}" }
    }

    private fun heightOf(row: Row) = when (row) {
        is Row.Header -> m.listHeader
        is Row.Item -> m.listRow
        is Row.Title -> m.pt(44f)
        is Row.TopHit -> m.pt(96f)
    }

    /** A row on screen: glides from (y0, a0) to (y1, a1) starting at [start] (content coordinates, px). */
    private class Shown(val row: Row, var y0: Float, var y1: Float, var a0: Float, var a1: Float, var start: Long) {
        fun y(now: Long): Float = y0 + (y1 - y0) * ease(now - start, Y_NS)
        fun a(now: Long): Float = a0 + (a1 - a0) * ease(now - start, A_NS)
        fun done(now: Long) = now - start >= Y_NS
    }

    private var apps: List<AppEntry> = emptyList()
    private var query = ""
    private var rows: List<Shown> = emptyList()
    private val ghosts = ArrayList<Shown>()
    private var sections: List<Pair<Char, Float>> = emptyList()
    private var contentHeight = 0f
    private var animating = false

    private val labels = LabelPainter(m.listText, 0xFFFFFFFF.toInt(), Paint.Align.LEFT)
    private val headers = LabelPainter(m.listHeaderText, 0x99FFFFFF.toInt(), Paint.Align.LEFT, Fonts.text(600))
    private val index = LabelPainter(m.pt(11f), 0x99FFFFFF.toInt(), Paint.Align.CENTER, Fonts.text(600))
    private val bubbleText = LabelPainter(m.pt(24f), 0xFFFFFFFF.toInt(), Paint.Align.CENTER, Fonts.display(600))
    private val separator = Paint().apply { color = 0x1FFFFFFF; strokeWidth = max(1f, m.pt(0.5f)) }
    private val bubbleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val bubbleRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4DFFFFFF; style = Paint.Style.STROKE; strokeWidth = m.pt(1f) }
    private val fade = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val r = RectF()
    private var pressed: AppEntry? = null
    private var scrubbing = false
    private var scrubLetter = ' '
    // The index while scrubbed: how lit it is (capsule, magnified letters), the bubble's presence and where it glides.
    private val indexK = SpringValue(0f, 1000f, { invalidate() })
    private val bubbleK: SpringValue = SpringValue(0f, 1000f, { invalidate() }, { if (!scrubbing) settled() })
    private val bubbleY = SpringValue(0f, 1f, { invalidate() })
    private val bubbleShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40000000
        maskFilter = android.graphics.BlurMaskFilter(m.pt(10f), android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val indexCapsule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24FFFFFF }
    private val indexCapsuleRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x30FFFFFF; style = Paint.Style.STROKE; strokeWidth = max(1f, m.pt(0.7f)) }

    val isIdle get() = !scroller.isSettling && !scroller.isDragging && !animating && !scrubbing && !bubbleK.isAnimating && !indexK.isAnimating

    fun setApps(list: List<AppEntry>) { apps = list; rebuild(animate = false) }

    fun setQuery(q: String) {
        val t = q.trim()
        if (t == query) return
        query = t
        scroller.jumpTo(0f)
        rebuild(animate = true)
    }

    fun firstResult(): AppEntry? = rows.firstNotNullOfOrNull { (it.row as? Row.TopHit)?.e ?: (it.row as? Row.Item)?.e }

    // ------------------------------------------------------------------ data

    private fun rebuild(animate: Boolean) {
        val out = ArrayList<Row>()
        val secs = ArrayList<Pair<Char, Float>>()
        if (query.isEmpty()) {
            var current = '\u0000'
            for (e in apps) {
                val c = sectionOf(e.label)
                if (c != current) { current = c; out += Row.Header(c) }
                out += Row.Item(e)
            }
        } else {
            val q = query.lowercase()
            val found = apps.mapNotNull { e -> rank(e.label.lowercase(), q)?.let { e to it } }.sortedBy { it.second }.map { it.first }
            if (spotlight && found.isNotEmpty()) {
                out += Row.Title("Top Hit")
                out += Row.TopHit(found[0])
                if (found.size > 1) {
                    out += Row.Title("Apps")
                    found.drop(1).forEach { out += Row.Item(it) }
                }
            } else found.forEach { out += Row.Item(it) }
        }
        val now = System.nanoTime()
        val old = rows.associateBy { it.row.key }
        val next = ArrayList<Shown>(out.size)
        var y = 0f
        for (row in out) {
            if (row is Row.Header) secs += row.letter to y
            val prev = old[row.key]
            next += when {
                !animate -> Shown(row, y, y, 1f, 1f, now - Y_NS)
                prev != null -> Shown(row, prev.y(now), y, prev.a(now), 1f, now)    // glides to its new place
                else -> Shown(row, y + m.pt(12f), y, 0f, 1f, now)                    // fades and rises in
            }
            y += heightOf(row)
        }
        ghosts.clear()
        if (animate) {
            val keep = out.mapTo(HashSet()) { it.key }
            for ((k, s) in old) if (k !in keep) ghosts += Shown(s.row, s.y(now), s.y(now), s.a(now), 0f, now)   // fades out in place
        }
        rows = next
        sections = secs
        contentHeight = y
        labels.clear()
        updateBounds()
        if (animate) startFrames() else invalidate()
    }

    private fun startFrames() {
        if (animating) return
        animating = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            invalidate()
            val now = System.nanoTime()
            ghosts.removeAll { it.done(now) }
            if (ghosts.isEmpty() && rows.all { it.done(now) }) { animating = false; settled() }
            else Choreographer.getInstance().postFrameCallback(this)
        }
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

    private fun updateBounds() {
        if (height == 0) return
        val visible = height - bottomSpace - contentTop
        scroller.setBounds(0f, max(0f, contentHeight + m.bottomSafe + bottomPadding - visible), height.toFloat())
    }

    private fun updateFade() {
        fade.shader = LinearGradient(0f, fadeTop, 0f, contentTop - m.pt(2f), 0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { updateBounds(); updateFade() }

    // ------------------------------------------------------------------ geometry (screen y of a row = contentTop + y - scroll)

    private fun screenY(s: Shown, now: Long) = contentTop + s.y(now) - scroller.position

    private fun iconRect(top: Float, out: RectF): RectF {
        val t = top + (m.listRow - m.listIcon) / 2f
        return out.apply { set(m.libMargin, t, m.libMargin + m.listIcon, t + m.listIcon) }
    }

    // The Top Hit card and its (home-sized) icon.
    private fun cardRect(top: Float, out: RectF) = out.apply { set(m.libMargin - m.pt(6f), top + m.pt(4f), m.w - m.libMargin + m.pt(6f), top + m.pt(88f)) }
    private fun topIconRect(top: Float, out: RectF): RectF {
        val card = cardRect(top, RectF())
        val l = card.left + m.pt(14f)
        val t = card.centerY() - m.iconSize / 2f
        return out.apply { set(l, t, l + m.iconSize, t + m.iconSize) }
    }

    private fun onScreen(top: Float) = top + m.listRow > contentTop - m.listRow && top < height - bottomSpace

    fun iconRectOf(e: AppEntry): RectF? {
        val now = System.nanoTime()
        for (s in rows) {
            val row = s.row
            if (row is Row.TopHit && row.e.key == e.key) return topIconRect(screenY(s, now), RectF())
            if (row is Row.Item && row.e.key == e.key) return iconRect(screenY(s, now), RectF())
        }
        return null
    }

    fun visibleIcons(out: MutableList<IconSpot>) {
        val now = System.nanoTime()
        for (s in rows) {
            val row = s.row
            val rect = when (row) {
                is Row.Item -> iconRect(screenY(s, now), RectF())
                is Row.TopHit -> topIconRect(screenY(s, now), RectF())
                else -> continue
            }
            val e = (row as? Row.Item)?.e ?: (row as Row.TopHit).e
            if (rect.centerY() > contentTop && rect.centerY() < height - bottomSpace) out += IconSpot(e.pkg, rect, "list")
        }
    }

    private fun itemAt(y: Float): Pair<AppEntry, RectF>? {
        if (y < contentTop || y > height - bottomSpace) return null
        val now = System.nanoTime()
        for (s in rows) {
            val t = screenY(s, now)
            when (val row = s.row) {
                is Row.Item -> if (y >= t && y < t + m.listRow) return row.e to iconRect(t, RectF())
                is Row.TopHit -> if (y >= t && y < t + heightOf(row)) return row.e to topIconRect(t, RectF())
                else -> {}
            }
        }
        return null
    }

    // ------------------------------------------------------------------ touch

    private fun indexTop() = contentTop
    private fun indexBottom() = height - bottomSpace - m.bottomSafe
    private fun inIndex(x: Float) = query.isEmpty() && sections.isNotEmpty() && x > width - m.listSideIndex * 1.6f

    /** Centre y of [letter]'s tick in the index. */
    private fun letterY(letter: Char) = indexTop() + (indexBottom() - indexTop()) * ((LETTERS.indexOf(letter) + 0.5f) / LETTERS.size)

    private fun scrubTo(y: Float, first: Boolean) {
        val k = ((y - indexTop()) / (indexBottom() - indexTop())).coerceIn(0f, 0.999f)
        val letter = LETTERS[(k * LETTERS.size).toInt()]
        // The bubble glides to the letter under the finger (it is there at once when the finger lands).
        if (first) bubbleY.snapTo(letterY(letter)) else bubbleY.animateTo(letterY(letter), Motion.profile.indexFollow)
        if (letter == scrubLetter) return
        scrubLetter = letter
        // '#' (apps starting with a digit or symbol) sits at the end of the index; its section may not exist.
        val target = if (letter == '#') sections.firstOrNull { it.first == '#' } ?: sections.last()
                     else sections.firstOrNull { it.first >= letter && it.first != '#' } ?: sections.last()
        // The list springs to the section, keeping the motion it has: scrubbing across letters flows instead of jumping.
        scroller.animateTo(target.second, Motion.profile.indexScroll)
        haptic()
    }

    private fun beginScrub(y: Float) {
        scrubbing = true
        scrubLetter = ' '
        scroller.stop()
        setPressed(null)
        indexK.animateTo(1f, Motion.profile.indexBubbleIn)
        bubbleK.animateTo(1f, Motion.profile.indexBubbleIn)
        scrubTo(y, first = true)
    }

    private fun endScrub() {
        scrubbing = false
        indexK.animateTo(0f, Motion.profile.indexBubbleOut)
        bubbleK.animateTo(0f, Motion.profile.indexBubbleOut)   // settled() once it is gone
        invalidate()
    }

    /** False while this list is fading out behind another pane: touches go to what is underneath. */
    var acceptsTouches = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && !acceptsTouches) return false
        if (e.actionMasked == MotionEvent.ACTION_DOWN && inIndex(e.x)) {
            parent?.requestDisallowInterceptTouchEvent(true)
            beginScrub(e.y)
            return true
        }
        if (scrubbing) {
            scrubTo(e.y, first = false)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) endScrub()
            return true
        }
        val dy = touch.onEvent(e) {
            parent?.requestDisallowInterceptTouchEvent(true)
            scroller.beginDrag()
            setPressed(null)
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touch.stoppedMotion = scroller.isMovingVisibly()
                scroller.stop()
                if (!touch.stoppedMotion) {
                    val hit = itemAt(e.y)
                    setPressed(hit?.first)
                    val lp = onLongPress
                    if (hit != null && lp != null) touch.armLongPress(this) { setPressed(null); lp(hit.first, hit.second) }
                }
            }
            MotionEvent.ACTION_MOVE -> if (touch.scrolling) scroller.dragBy(-dy) else if (touch.moved) setPressed(null)
            MotionEvent.ACTION_UP -> {
                if (touch.scrolling) scroller.endDrag(-touch.velocityY())
                else if (touch.isTap) itemAt(e.y)?.let { (entry, rect) -> onLaunch(entry, rect) }
                setPressed(null)
            }
            MotionEvent.ACTION_CANCEL -> { if (touch.scrolling) scroller.endDrag(0f); setPressed(null) }
        }
        return true
    }

    /** An app row was long-pressed ([AppEntry], its icon in this view's coordinates); null: long presses do nothing. */
    var onLongPress: ((AppEntry, RectF) -> Unit)? = null

    private fun setPressed(e: AppEntry?) { if (pressed?.key != e?.key) { pressed = e; invalidate() } }

    private val titles = LabelPainter(m.pt(15f), 0xD9FFFFFF.toInt(), Paint.Align.LEFT, Fonts.text(600))
    private val hitName = LabelPainter(m.pt(17f), 0xFFFFFFFF.toInt(), Paint.Align.LEFT, Fonts.text(600))
    private val hitSub = LabelPainter(m.pt(14.5f), 0x99FFFFFF.toInt(), Paint.Align.LEFT)

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        // Scroll edge: rows fade out above the content (a layer over that band only).
        val band = c.saveLayer(0f, 0f, width.toFloat(), contentTop, null)
        drawRows(c, now)
        c.drawRect(0f, 0f, width.toFloat(), contentTop, fade)
        c.restoreToCount(band)
        c.save()
        c.clipRect(0f, contentTop, width.toFloat(), height - bottomSpace)
        drawRows(c, now)
        c.restore()
        drawIndex(c)
        onDrawn?.invoke()
    }

    private fun drawRows(c: Canvas, now: Long) {
        val textX = m.libMargin + m.listIcon + m.pt(14f)
        val rightEdge = width - m.libMargin - (if (query.isEmpty()) m.listSideIndex else 0f)
        for (s in ghosts) drawRow(c, s, now, textX, rightEdge, null)
        for ((i, s) in rows.withIndex()) drawRow(c, s, now, textX, rightEdge, rows.getOrNull(i + 1))
    }

    private fun drawRow(c: Canvas, s: Shown, now: Long, textX: Float, rightEdge: Float, next: Shown?) {
        val t = screenY(s, now)
        if (t + heightOf(s.row) < contentTop - m.listRow || t > height - bottomSpace) return
        val alpha = (255 * s.a(now)).toInt().coerceIn(0, 255)
        if (alpha == 0) return
        when (val row = s.row) {
            is Row.Header -> headers.draw(c, "h${row.letter}", row.letter.toString(), m.libMargin, headers.baselineFor(t + m.listHeader * 0.62f), m.w.toFloat(), (alpha * 0.6f).toInt())
            is Row.Title -> {
                titles.draw(c, row.key, row.text, m.libMargin, t + m.pt(28f), m.w.toFloat(), alpha)
                separator.alpha = (0x2E * s.a(now)).toInt()
                c.drawLine(m.libMargin, t + m.pt(40f), m.w - m.libMargin, t + m.pt(40f), separator)
            }
            is Row.TopHit -> {
                // The best match, highlighted in a glass card (iOS Spotlight's Top Hit).
                cardRect(t, r)
                val save = c.saveLayerAlpha(r.left - 1, r.top - 1, r.right + 1, r.bottom + 1, alpha)
                drawCard?.invoke(c, RectF(r)) ?: c.drawRoundRect(r, m.pt(26f), m.pt(26f), PRESS)
                if (pressed?.key == row.e.key) { PRESS.alpha = 0x1A; c.drawRoundRect(r, m.pt(26f), m.pt(26f), PRESS) }
                val icon = topIconRect(t, RectF())
                if (!isHidden(row.e, icon)) icons.draw(c, row.e, icon)
                val tx = icon.right + m.pt(14f)
                val maxW = r.right - m.pt(14f) - tx
                hitName.draw(c, "n" + row.e.key, row.e.label, tx, icon.centerY() - m.pt(3f), maxW)
                hitSub.draw(c, "s" + row.e.key, row.e.category.title, tx, icon.centerY() + m.pt(17f), maxW)
                c.restoreToCount(save)
            }
            is Row.Item -> {
                if (pressed?.key == row.e.key) { r.set(0f, t, width.toFloat(), t + m.listRow); PRESS.alpha = (0x1A * s.a(now)).toInt(); c.drawRect(r, PRESS) }
                iconRect(t, r)
                if (!isHidden(row.e, r)) icons.draw(c, row.e, r, alpha = alpha)
                labels.draw(c, row.e.key, row.e.label, textX, labels.baselineFor(t + m.listRow / 2f), rightEdge - textX, alpha)
                if (next?.row is Row.Item) {
                    separator.alpha = (0x1F * s.a(now)).toInt()
                    c.drawLine(textX, t + m.listRow, rightEdge, t + m.listRow, separator)
                }
            }
        }
    }

    private fun drawIndex(c: Canvas) {
        if (query.isNotEmpty() || sections.isEmpty()) return
        val top = indexTop()
        val step = (indexBottom() - top) / LETTERS.size
        val x = width - m.listSideIndex / 2f - m.pt(4f)
        val lit = indexK.value.coerceIn(0f, 1f)
        val fingerY = bubbleY.value
        if (lit > 0f) {
            // A capsule lights up behind the column while it is touched.
            val pad = m.pt(6f)
            r.set(x - m.listSideIndex / 2f, top - pad, x + m.listSideIndex / 2f, indexBottom() + pad)
            indexCapsule.alpha = (0x24 * lit).toInt()
            indexCapsuleRim.alpha = (0x30 * lit).toInt()
            c.drawRoundRect(r, r.width() / 2f, r.width() / 2f, indexCapsule)
            c.drawRoundRect(r, r.width() / 2f, r.width() / 2f, indexCapsuleRim)
        }
        for ((k, l) in LETTERS.withIndex()) {
            val cy = top + step * (k + 0.5f)
            // Letters near the finger grow and brighten (a fisheye over about three letters), fading with the capsule.
            val near = if (lit > 0f) (1f - abs(cy - fingerY) / (step * 3f)).coerceIn(0f, 1f) else 0f
            val bump = near * near * lit
            val scale = 1f + 0.5f * bump
            val alpha = (153 + (255 - 153) * maxOf(bump, if (scrubbing && l == scrubLetter) 1f else 0f)).toInt().coerceIn(0, 255)
            if (scale != 1f) {
                c.save()
                c.scale(scale, scale, x - m.pt(2f) * bump, cy)
                index.draw(c, "i$l", l.toString(), x, index.baselineFor(cy), m.listSideIndex, alpha)
                c.restore()
            } else index.draw(c, "i$l", l.toString(), x, index.baselineFor(cy), m.listSideIndex, alpha)
        }
        val b = bubbleK.value
        if (b > 0.005f && scrubLetter != ' ') {
            // A glass bubble beside the index with the letter under the finger: pops in (a little overshoot), glides along
            // with the finger, and pops away when it lifts.
            val bx = width - m.listSideIndex * 1.6f - m.pt(34f) - m.pt(6f) * (1f - b.coerceIn(0f, 1f))
            val rad = m.pt(26f)
            val a = b.coerceIn(0f, 1f)
            c.save()
            c.scale(0.55f + 0.45f * b, 0.55f + 0.45f * b, bx, fingerY)
            bubbleShadow.alpha = (0x40 * a).toInt()
            c.drawCircle(bx, fingerY + m.pt(4f), rad, bubbleShadow)
            bubbleFill.alpha = (0x3D * a).toInt()
            bubbleRim.alpha = (0x59 * a).toInt()
            c.drawCircle(bx, fingerY, rad, bubbleFill)
            c.drawCircle(bx, fingerY, rad, bubbleRim)
            bubbleText.draw(c, "b$scrubLetter", scrubLetter.toString(), bx, bubbleText.baselineFor(fingerY), rad * 2, (255 * a).toInt())
            c.restore()
        }
    }

    private companion object {
        const val Y_NS = 300_000_000L
        const val A_NS = 200_000_000L
        val LETTERS = ('A'..'Z').toList() + '#'
        val PRESS = Paint().apply { color = 0x1AFFFFFF }

        /** Ease-out cubic over [total] ns. */
        fun ease(elapsed: Long, total: Long): Float {
            val p = (elapsed.toFloat() / total).coerceIn(0f, 1f)
            val q = 1f - p
            return 1f - q * q * q
        }
    }
}
