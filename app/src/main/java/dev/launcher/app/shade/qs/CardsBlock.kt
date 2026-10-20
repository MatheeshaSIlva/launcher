package dev.launcher.app.shade.qs

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import dev.launcher.app.R
import dev.launcher.app.design.Design
import dev.launcher.app.design.applyTo
import dev.launcher.app.motion.IosScroller
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.motion.MotionValue
import dev.launcher.app.shade.AppInfo
import dev.launcher.app.shade.Notifs
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The notifications as Android's cards (the `notification-cards` block, and `notification-footer` under them): three
 * sections (ongoing, alerting, silent with its heading), each a column of cards joined with small corners; an app's group
 * one card with its count, opened into its notifications by its chip; a card swiped either way goes. Under the last, the
 * history, Clear all and the notification settings. Rows move to their new places when the list changes, new ones fade in,
 * gone ones fade out: nothing pops.
 */
internal class CardsBlock(private val panel: QsPanel, private val ctx: Context, private val withFooter: Boolean) {
    private val u get() = panel.u

    private enum class Section { ONGOING, ALERTING, SILENT }

    /** A card of the list: a notification, or an app's group of them. */
    private class Card(val key: String, val section: Section, val items: List<Notifs.Item>) {
        val lead get() = items.first()
        val group get() = items.size > 1
    }

    private enum class Kind { CARD, GROUP, CHILD, HEADING, FOOTER }

    /** One row of the list: what it shows, the card it belongs to, its section; [key] stays the same across rebuilds. */
    private class Row(val key: String, val kind: Kind, val section: Section, val card: Card?, val item: Notifs.Item?) {
        var top = 0f
        var height = 0f
        // Its corners: rounded where it starts or ends its section's column, small where it meets a neighbour.
        var first = true
        var last = true
    }

    /** A row's motion: where it is (content px), how present, how far swiped. */
    private inner class Anim {
        val y = MotionValue(0f, 2f, { panel.view.invalidate() })
        val alpha = MotionValue(0f, 1000f, { panel.view.invalidate() })
        val swipe = MotionValue(0f, 2f, { panel.view.invalidate() })
        var height = 0f
        var leaving: Row? = null
    }

    private var cards: List<Card> = emptyList()
    private var rows: List<Row> = emptyList()
    private val anims = HashMap<String, Anim>()
    /** Groups opened by their chip, cards opened by their chevron (keys). */
    private val opened = HashSet<String>()
    private var contentHeight = 0f

    private val scroller = IosScroller({ panel.view.invalidate() })

    // ------------------------------------------------------------------ the list

    /** The notifications changed (or the panel opens: [animate] false lays them out where they are). */
    fun setItems(items: List<Notifs.Item>, animate: Boolean) {
        cards = cardsOf(items.filter { !it.summary || items.none { o -> o !== it && o.groupKey == it.groupKey } })
        loadIcons()
        relayout(animate)
    }

    private fun sectionOf(it: Notifs.Item): Section = when {
        it.ongoing && it.importance >= android.app.NotificationManager.IMPORTANCE_DEFAULT -> Section.ONGOING
        it.importance >= android.app.NotificationManager.IMPORTANCE_DEFAULT -> Section.ALERTING
        else -> Section.SILENT
    }

    /** The items as cards: a group of one app's notifications (two or more sharing its group key) is one card. */
    private fun cardsOf(items: List<Notifs.Item>): List<Card> {
        val out = ArrayList<Card>()
        val seen = HashSet<String>()
        for (it in items.sortedByDescending { it.postTime }) {
            if (it.summary) continue
            val gk = "${it.pkg}|${it.groupKey}"
            if (gk in seen) continue
            val members = items.filter { o -> !o.summary && "${o.pkg}|${o.groupKey}" == gk }.sortedByDescending { o -> o.postTime }
            if (members.size > 1) { seen += gk; out += Card(gk, sectionOf(it), members) } else out += Card(it.key, sectionOf(it), listOf(it))
        }
        return Section.entries.flatMap { s -> out.filter { it.section == s } }
    }

    private fun line(k: dev.launcher.app.design.TextKey) = Design.text(k).lineHeightPt * u

    /** Lays the rows out again: each to its new place ([animate]: on the reflow curve, new ones fading in). */
    private fun relayout(animate: Boolean) {
        val pad = Design.pt(QsTokens.CARD_PADDING, u)
        val icon = Design.pt(QsTokens.CARD_ICON, u)
        val out = ArrayList<Row>()
        for (s in Section.entries) {
            val cs = cards.filter { it.section == s }
            if (cs.isEmpty()) continue
            if (s == Section.SILENT) out += Row("heading:silent", Kind.HEADING, s, null, null)
            val start = out.size
            for (c in cs) {
                if (!c.group) out += Row(c.key, Kind.CARD, s, c, c.lead)
                else if (c.key !in opened) out += Row(c.key, Kind.GROUP, s, c, c.lead)
                else for (it in c.items) out += Row(it.key, Kind.CHILD, s, c, it)
            }
            for (i in start until out.size) {
                out[i].first = i == start
                out[i].last = i == out.size - 1
            }
        }
        if (withFooter && cards.isNotEmpty()) out += Row("footer", Kind.FOOTER, Section.SILENT, null, null)
        // Heights and places, in the content's own coordinates.
        var y = 0f
        var prev: Row? = null
        for (r in out) {
            r.height = when (r.kind) {
                Kind.HEADING -> Design.pt(QsTokens.SECTION_HEIGHT, u)
                Kind.FOOTER -> Design.pt(QsTokens.CLEAR_HEIGHT, u)
                Kind.GROUP -> pad * 2 + max(icon, line(QsTokens.CARD_APP) + line(QsTokens.CARD_TITLE) + line(QsTokens.CARD_BODY))
                else -> pad * 2 + max(icon, line(QsTokens.CARD_TITLE) + bodyLines(r) * line(QsTokens.CARD_BODY)) + actionsHeight(r)
            }
            if (prev != null) y += when {
                r.kind == Kind.FOOTER || r.kind == Kind.HEADING || prev.section != r.section -> Design.pt(QsTokens.CARD_GAP, u)
                prev.kind == Kind.HEADING -> 0f
                else -> Design.pt(QsTokens.JOINED_GAP, u)
            }
            r.top = y
            y += r.height
            prev = r
        }
        contentHeight = y
        // Rows that went fade out where they were; the others move to their places.
        val keys = out.map { it.key }.toHashSet()
        for ((k, a) in anims) {
            if (k in keys || a.leaving != null) continue
            val old = rows.firstOrNull { it.key == k } ?: continue
            a.leaving = old
            if (animate) a.alpha.animateTo(0f, Motion.role(MotionTokens.NC_LEAVE)) else a.alpha.snapTo(0f)
        }
        for (r in out) {
            val a = anims[r.key]
            if (a == null || a.leaving != null) {
                val n = a ?: Anim().also { anims[r.key] = it }
                n.leaving = null
                n.y.snapTo(r.top)
                n.swipe.snapTo(0f)
                if (animate) { n.alpha.snapTo(0f); n.alpha.animateTo(1f, Motion.role(MotionTokens.NC_APPEAR)) } else n.alpha.snapTo(1f)
            } else if (animate) a.y.animateTo(r.top, Motion.role(MotionTokens.NC_REFLOW)) else a.y.snapTo(r.top)
            anims.getValue(r.key).height = r.height
        }
        anims.entries.removeAll { (k, a) -> k !in keys && a.leaving != null && !a.alpha.isAnimating }
        rows = out
        bounds()
        panel.view.invalidate()
    }

    /** A card's text lines: one, or up to three when opened by its chevron. */
    private fun bodyLines(r: Row): Int {
        val text = r.item?.text
        if (text.isNullOrBlank()) return 0
        return if (r.key in opened) 3 else 1
    }

    private fun actionsOf(item: Notifs.Item): List<Notification.Action> =
        item.actions.filter { it.actionIntent != null && !it.title.isNullOrBlank() && it.remoteInputs.isNullOrEmpty() }.take(3)

    /** An ongoing card (a stopwatch, a call) and a card opened by its chevron show their actions. */
    private fun showsActions(r: Row): Boolean {
        val item = r.item ?: return false
        if (r.kind == Kind.GROUP || panel.host.locked) return false
        return (item.ongoing || r.key in opened) && actionsOf(item).isNotEmpty()
    }

    private fun actionsHeight(r: Row) = if (showsActions(r)) Design.pt(QsTokens.CLEAR_HEIGHT, u) + 12f * u else 0f

    // ------------------------------------------------------------------ where the list is

    /** The list's top at the first stage (px), set by the panel: under the quick row. */
    var listTop = 0f
        set(v) { if (field != v) { field = v; bounds() } }

    private fun visibleHeight() = panel.panelHeight - listTop - bottomInset()

    private fun bottomInset() = 24f * u

    private fun bounds() = scroller.setBounds(0f, max(0f, contentHeight - visibleHeight()), visibleHeight())

    /** The list's shift now: at the second stage it goes below Quick Settings' content ([qsBottom]) and fades. */
    private fun shift(qsBottom: Float): Float = panel.expandK * max(0f, qsBottom - listTop + 32f * u)

    private fun alphaNow(): Float = 1f - smooth(0f, 0.5f, panel.expandK)

    private val r = RectF()

    /** Row [row]'s rectangle on screen now (its motion, the scroll, the stage) into [out]. */
    private fun place(row: Row, a: Anim, qsBottom: Float, out: RectF) {
        val top = listTop + a.y.value - scroller.position + shift(qsBottom)
        out.set(panel.side + a.swipe.value, top, panel.panelWidth - panel.side + a.swipe.value, top + row.height)
    }

    // ------------------------------------------------------------------ drawing

    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val radii = FloatArray(4)

    fun draw(c: Canvas, alpha: Float, qsBottom: Float) {
        val listAlpha = alpha * alphaNow()
        if (listAlpha <= 0.003f) return
        // The shelf the cards lie on: across the screen from just above the list to below its bottom edge.
        if (rows.isNotEmpty()) {
            val corner = Design.pt(QsTokens.SHELF_CORNER, u)
            val top = listTop - Design.pt(QsTokens.SHELF_INSET, u) + shift(qsBottom)
            radii[0] = corner; radii[1] = corner; radii[2] = 0f; radii[3] = 0f
            val h = panel.panelHeight - top + corner
            c.save()
            c.translate(0f, top)
            panel.surfaces.draw(c, Design.material(QsTokens.SHELF), panel.panelWidth.toFloat(), h, corner, 0f, top, 1f, listAlpha, radii = radii)
            c.restore()
        }
        val save = c.save()
        // Cut where the list starts (under the quick row) as it scrolls up under it.
        c.clipRect(0f, listTop - 2f * u + shift(qsBottom), panel.panelWidth.toFloat(), panel.panelHeight.toFloat())
        for ((_, a) in anims) a.leaving?.let { drawRow(c, it, a, listAlpha, qsBottom) }
        for (row in rows) drawRow(c, row, anims[row.key] ?: continue, listAlpha, qsBottom)
        c.restoreToCount(save)
    }

    private fun drawRow(c: Canvas, row: Row, a: Anim, listAlpha: Float, qsBottom: Float) {
        place(row, a, qsBottom, r)
        if (r.bottom < 0f || r.top > panel.panelHeight) return
        // A swiped card fades as it goes.
        val swipeFade = 1f - (abs(a.swipe.value) / (panel.panelWidth * 0.6f)).coerceIn(0f, 1f)
        val f = listAlpha * a.alpha.value.coerceIn(0f, 1f) * swipeFade
        if (f <= 0.003f) return
        when (row.kind) {
            Kind.HEADING -> drawHeading(c, r, f)
            Kind.FOOTER -> drawFooter(c, r, f)
            else -> drawCard(c, row, r, f)
        }
    }

    private fun drawCard(c: Canvas, row: Row, r: RectF, a: Float) {
        val item = row.item ?: return
        val big = Design.pt(QsTokens.CARD_CORNER, u)
        val joined = Design.pt(QsTokens.JOINED_CORNER, u)
        val top = if (row.first) big else joined
        val bottom = if (row.last) big else joined
        radii[0] = top; radii[1] = top; radii[2] = bottom; radii[3] = bottom
        c.save()
        c.translate(r.left, r.top)
        panel.surfaces.draw(c, Design.material(QsTokens.CARD), r.width(), r.height(), big, r.left, r.top, 1f, a, radii = radii)
        c.restore()
        val pad = Design.pt(QsTokens.CARD_PADDING, u)
        val icon = Design.pt(QsTokens.CARD_ICON, u)
        // The app's small symbol on a circle.
        fill.color = faded(Design.color(QsTokens.ICON_FILL), a)
        c.drawCircle(r.left + pad + icon / 2f, r.top + pad + icon / 2f, icon / 2f, fill)
        smallIcon(item)?.let { d ->
            val s = (icon * 0.55f).toInt().coerceAtLeast(1)
            d.setBounds(0, 0, s, s)
            val col = faded(Design.color(QsTokens.ICON_COLOR), a)
            d.setTint(col or (0xFF shl 24))
            d.alpha = (col ushr 24) and 0xFF
            c.save()
            c.translate(r.left + pad + (icon - s) / 2f, r.top + pad + (icon - s) / 2f)
            d.draw(c)
            c.restore()
        }
        val tx = r.left + pad + icon + 16f * u
        val chipW = drawChip(c, row, r, pad, a)
        val maxW = r.right - pad - chipW - tx
        val titleColor = faded(Design.color(QsTokens.CARD_TITLE_COLOR), a)
        val textColor = faded(Design.color(QsTokens.CARD_TEXT_COLOR), a)
        var y = r.top + pad
        val time = timeLabel(item.postTime)
        if (row.kind == Kind.GROUP) {
            // The app's name and the time; then the newest notification's title and text.
            Design.text(QsTokens.CARD_APP).applyTo(small, u)
            small.color = textColor
            val al = line(QsTokens.CARD_APP)
            c.drawText(ellipsize("${AppInfo.label(ctx, item.pkg)} · $time", small, maxW), tx, baselineIn(small, y, al), small)
            y += al
        }
        Design.text(QsTokens.CARD_TITLE).applyTo(title, u)
        title.color = titleColor
        val tl = line(QsTokens.CARD_TITLE)
        val name = (item.title ?: AppInfo.label(ctx, item.pkg)).toString()
        if (row.kind == Kind.GROUP) {
            c.drawText(ellipsize(name, title, maxW), tx, baselineIn(title, y, tl), title)
        } else {
            // The title, then " · time" in the app line's type.
            Design.text(QsTokens.CARD_APP).applyTo(small, u)
            small.color = textColor
            val tail = " · $time"
            val shown = ellipsize(name, title, maxW - small.measureText(tail))
            val base = baselineIn(title, y, tl)
            c.drawText(shown, tx, base, title)
            c.drawText(tail, tx + title.measureText(shown), base, small)
        }
        y += tl
        val text = item.text?.toString()?.replace('\n', ' ')
        if (!text.isNullOrBlank()) {
            Design.text(QsTokens.CARD_BODY).applyTo(body, u)
            body.color = textColor
            val bl = line(QsTokens.CARD_BODY)
            val lines = if (row.kind == Kind.GROUP) 1 else bodyLines(row)
            if (lines <= 1) c.drawText(ellipsize(text, body, maxW), tx, baselineIn(body, y, bl), body)
            else {
                val layout = android.text.StaticLayout.Builder.obtain(text, 0, text.length, body, maxW.toInt().coerceAtLeast(1))
                    .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build()
                c.save()
                c.translate(tx, y + (bl - (body.fontMetrics.descent - body.fontMetrics.ascent)) / 2f)
                layout.draw(c)
                c.restore()
            }
        }
        if (showsActions(row)) drawActions(c, item, r, pad, a)
    }

    /** A group's count and chevron, or a card's chevron, at the top right; returns the room it takes. */
    private fun drawChip(c: Canvas, row: Row, r: RectF, pad: Float, a: Float): Float {
        val h = 28f * u
        val count = if (row.kind == Kind.GROUP) row.card?.items?.size ?: 0 else 0
        Design.text(QsTokens.CHIP_TEXT).applyTo(small, u)
        val label = if (count > 0) "$count" else ""
        val w = (if (label.isEmpty()) 0f else small.measureText(label) + 6f * u) + 34f * u
        val chip = RectF(r.right - pad - w, r.top + pad - 2f * u, r.right - pad, r.top + pad - 2f * u + h)
        chips[row.key] = chip
        fill.color = faded(Design.color(QsTokens.CHIP_FILL), a)
        c.drawRoundRect(chip, h / 2f, h / 2f, fill)
        val col = faded(Design.color(QsTokens.CARD_TITLE_COLOR), a)
        if (label.isNotEmpty()) {
            small.color = col
            small.textAlign = Paint.Align.LEFT
            c.drawText(label, chip.left + 12f * u, baselineIn(small, chip.top, h), small)
        }
        // Down while closed, up while open.
        val open = row.key in opened || (row.kind == Kind.CHILD && row.first)
        c.save()
        c.rotate(if (open) -90f else 90f, chip.right - 17f * u, chip.centerY())
        panel.glyphs.draw(c, R.drawable.sym_chevron, chip.right - 17f * u, chip.centerY(), 14f * u, col)
        c.restore()
        return w + 8f * u
    }

    private val chips = HashMap<String, RectF>()
    private val actionHits = ArrayList<Pair<Notification.Action, RectF>>()

    private fun drawActions(c: Canvas, item: Notifs.Item, r: RectF, pad: Float, a: Float) {
        val acts = actionsOf(item)
        val h = Design.pt(QsTokens.CLEAR_HEIGHT, u)
        val gap = 8f * u
        val top = r.bottom - pad - h
        val w = (r.width() - 2 * pad - (acts.size - 1) * gap) / acts.size
        Design.text(QsTokens.CLEAR_TEXT).applyTo(small, u)
        small.textAlign = Paint.Align.CENTER
        // Outlined capsules, as Android's.
        stroke.strokeWidth = maxOf(1f, u)
        stroke.color = faded(Design.color(QsTokens.ACTION_OUTLINE), a)
        for ((i, act) in acts.withIndex()) {
            val b = RectF(r.left + pad + i * (w + gap), top, r.left + pad + i * (w + gap) + w, top + h)
            val inset = stroke.strokeWidth / 2f
            c.drawRoundRect(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset, h / 2f, h / 2f, stroke)
            small.color = faded(Design.color(QsTokens.CARD_TITLE_COLOR), a)
            c.drawText(ellipsize(act.title.toString(), small, w - 16f * u), b.centerX(), baselineIn(small, top, h), small)
            actionHits += act to b
        }
        small.textAlign = Paint.Align.LEFT
    }

    private val headingClear = RectF()

    private fun drawHeading(c: Canvas, r: RectF, a: Float) {
        Design.text(QsTokens.SECTION).applyTo(small, u)
        small.color = faded(Design.color(QsTokens.SECTION_COLOR), a)
        small.textAlign = Paint.Align.LEFT
        val pad = Design.pt(QsTokens.CARD_PADDING, u)
        c.drawText("Silent", r.left + pad, baselineIn(small, r.top, r.height()), small)
        // Its clear button: an X at the right.
        val s = 20f * u
        headingClear.set(r.right - pad - 40f * u, r.top, r.right, r.bottom)
        panel.glyphs.draw(c, R.drawable.sym_close, r.right - pad - s / 2f, r.centerY(), s, faded(Design.color(QsTokens.SECTION_COLOR), a))
    }

    private val footerHistory = RectF()
    private val footerClear = RectF()
    private val footerSettings = RectF()

    private fun drawFooter(c: Canvas, r: RectF, a: Float) {
        val h = r.height()
        val side = 59f * u
        val gap = 8f * u
        footerHistory.set(r.left, r.top, r.left + side, r.bottom)
        footerSettings.set(r.right - side, r.top, r.right, r.bottom)
        footerClear.set(footerHistory.right + gap, r.top, footerSettings.left - gap, r.bottom)
        fill.color = faded(Design.color(QsTokens.CLEAR_FILL), a)
        for (b in listOf(footerHistory, footerClear, footerSettings)) c.drawRoundRect(b, h / 2f, h / 2f, fill)
        val col = faded(Design.color(QsTokens.CLEAR_TEXT_COLOR), a)
        val s = 22f * u
        panel.glyphs.draw(c, R.drawable.sym_history, footerHistory.centerX(), footerHistory.centerY(), s, col)
        panel.glyphs.draw(c, R.drawable.sym_notification_settings, footerSettings.centerX(), footerSettings.centerY(), s, col)
        Design.text(QsTokens.CLEAR_TEXT).applyTo(small, u)
        small.color = col
        small.textAlign = Paint.Align.CENTER
        c.drawText("Clear all", footerClear.centerX(), baselineIn(small, footerClear.top, h), small)
        small.textAlign = Paint.Align.LEFT
    }

    private fun ellipsize(s: String, p: TextPaint, w: Float) = TextUtils.ellipsize(s, p, max(1f, w), TextUtils.TruncateAt.END).toString()

    private fun timeLabel(t: Long): String {
        val d = System.currentTimeMillis() - t
        return when {
            d < 60_000 -> "now"
            d < 3_600_000 -> "${d / 60_000}m"
            d < 86_400_000 -> "${d / 3_600_000}h"
            else -> "${d / 86_400_000}d"
        }
    }

    // ------------------------------------------------------------------ the apps' small symbols, read off the drawing thread

    private val icons = HashMap<String, Drawable?>()

    private fun smallIcon(item: Notifs.Item): Drawable? = icons[item.key + "|" + item.postTime]

    private fun loadIcons() {
        val want = cards.flatMap { it.items }.filter { (it.key + "|" + it.postTime) !in icons }
        if (want.isEmpty()) return
        for (it in want) icons[it.key + "|" + it.postTime] = null
        val h = panel.host.thread()
        io.execute {
            val got = want.associate { it.key + "|" + it.postTime to (try { it.smallIcon?.loadDrawable(ctx)?.mutate() } catch (_: Throwable) { null }) }
            h.post {
                icons.putAll(got)
                if (icons.size > 200) icons.keys.retainAll(cards.flatMap { c -> c.items.map { it.key + "|" + it.postTime } }.toSet())
                panel.view.invalidate()
            }
        }
    }

    // ------------------------------------------------------------------ touches

    /** The row under ([x], [y]) (the list at the first stage), or null. */
    private fun rowAt(x: Float, y: Float, qsBottom: Float): Row? {
        if (panel.expandK > 0.5f || y < listTop) return null
        for (row in rows) {
            val a = anims[row.key] ?: continue
            place(row, a, qsBottom, r)
            if (r.contains(x, y)) return row
        }
        return null
    }

    fun contains(x: Float, y: Float, qsBottom: Float) = rowAt(x, y, qsBottom) != null

    /** The list can scroll up from where it is (a drag down scrolls it back before it expands Quick Settings). */
    val scrolled: Boolean get() = scroller.position > 0.5f
    val canScroll: Boolean get() = contentHeight > visibleHeight()

    fun beginScroll() = scroller.beginDrag()
    fun scrollBy(dy: Float) = scroller.dragBy(dy)
    fun endScroll(vy: Float) = scroller.endDrag(vy)

    private var swiping: Row? = null

    /** A sideways drag on the row under ([x], [y]) begins; false if there is nothing to swipe there. */
    fun beginSwipe(x: Float, y: Float, qsBottom: Float): Boolean {
        val row = rowAt(x, y, qsBottom) ?: return false
        if (row.kind == Kind.HEADING || row.kind == Kind.FOOTER) return false
        swiping = row
        anims[row.key]?.swipe?.stop()
        return true
    }

    fun swipeBy(dx: Float) {
        val row = swiping ?: return
        val a = anims[row.key] ?: return
        val clearable = itemsOf(row).all { it.clearable }
        // One that cannot go resists.
        a.swipe.snapTo(if (clearable) dx else dev.launcher.app.motion.Motion.rubberBand(dx, panel.panelWidth * 0.2f))
    }

    fun endSwipe(vx: Float) {
        val row = swiping ?: return
        swiping = null
        val a = anims[row.key] ?: return
        val clearable = itemsOf(row).all { it.clearable }
        val far = abs(a.swipe.value) > panel.panelWidth * 0.35f || abs(vx) > 1200f * u / 2.6f
        if (!clearable || !far) { a.swipe.animateTo(0f, Motion.role(MotionTokens.NC_SWIPE_BACK)); return }
        val dir = if (a.swipe.value != 0f) kotlin.math.sign(a.swipe.value) else kotlin.math.sign(vx)
        a.swipe.animateTo(dir * panel.panelWidth * 1.1f, Motion.role(MotionTokens.NC_SWIPE_OUT), vx)
        panel.view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        for (it in itemsOf(row)) Notifs.cancel(it)
    }

    private fun itemsOf(row: Row): List<Notifs.Item> = if (row.kind == Kind.GROUP) row.card?.items.orEmpty() else listOfNotNull(row.item)

    /** A tap at ([x], [y]): its chip, an action, the footer's buttons, or the card itself. */
    fun tap(x: Float, y: Float, qsBottom: Float): Boolean {
        val row = rowAt(x, y, qsBottom) ?: return false
        when (row.kind) {
            Kind.HEADING -> {
                if (x >= headingClear.left) clear(cards.filter { it.section == Section.SILENT }.flatMap { it.items })
                return true
            }
            Kind.FOOTER -> {
                when {
                    footerClear.contains(x, y) -> clear(cards.flatMap { it.items })
                    footerHistory.contains(x, y) -> panel.host.launch(Intent("android.settings.NOTIFICATION_HISTORY").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    footerSettings.contains(x, y) -> panel.host.launch(Intent(android.provider.Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                return true
            }
            else -> {}
        }
        chips[row.key]?.let { chip ->
            if (x >= chip.left - 8f * u && y <= chip.bottom + 8f * u) { toggle(row); return true }
        }
        for ((act, b) in actionHits) if (b.contains(x, y)) { panel.host.send(act.actionIntent); return true }
        // A group's body opens it; a card opens its app.
        if (row.kind == Kind.GROUP) { toggle(row); return true }
        val item = row.item ?: return true
        place(row, anims.getValue(row.key), qsBottom, r)
        panel.host.open(item, RectF(r))
        return true
    }

    /** A group opens into its notifications (or closes back); a card opens to its whole text and actions. */
    private fun toggle(row: Row) {
        panel.view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        val key = if (row.kind == Kind.CHILD) row.card?.key ?: row.key else row.key
        if (!opened.remove(key)) opened += key
        relayout(animate = true)
    }

    /** Clears [items] (those that can be cleared), the cards going one after another from the bottom. */
    private fun clear(items: List<Notifs.Item>) {
        val gone = items.filter { it.clearable }
        if (gone.isEmpty()) return
        panel.view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        val keys = gone.map { it.key }.toSet()
        // (Clear All's rhythm, as the App Switcher's.)
        val step = Motion.ms(MotionTokens.SWITCHER_CLEAR_STAGGER)
        var k = 0
        for (row in rows.reversed()) {
            if (row.kind == Kind.HEADING || row.kind == Kind.FOOTER) continue
            if (itemsOf(row).none { it.key in keys }) continue
            val a = anims[row.key] ?: continue
            val delay = (k++ * step).coerceAtMost(step * 6)
            panel.view.postDelayed({ a.swipe.animateTo(panel.panelWidth * 1.1f, Motion.role(MotionTokens.NC_SWIPE_OUT)) }, delay)
        }
        panel.view.postDelayed({ for (it in gone) Notifs.cancel(it) }, (k * step).coerceAtMost(step * 6) + step * 3)
    }

    /** The panel closed: the list back at its top, opened cards closed. */
    fun closed() {
        scroller.jumpTo(0f)
        opened.clear()
        swiping = null
        actionHits.clear()
    }

    fun beginFrame() = actionHits.clear()

    private companion object {
        val io = Executors.newSingleThreadExecutor()
    }
}
