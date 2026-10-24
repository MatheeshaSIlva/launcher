package dev.launcher.app.shade.qs

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.StaticLayout
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

/**
 * The notifications as Android 17's cards (the `notification-cards` block, and `notification-footer` under them), on the
 * notification scrim: three sections (ongoing, alerting, silent with its heading), each a column of cards joined with small
 * corners; an app's group one card with its count, opened by its chip into one card (a header naming the app, then each
 * notification without an icon, a chevron of its own opening only it; the header's closes the group); a card swiped either way goes. The first card shows
 * expanded (Android's config_autoExpandFirstNotification: its app's line, title, whole text and its actions as words), the
 * others one line of each, a chevron opening them. Under the last: the history, Clear all (while any can go) and the
 * notification settings. Rows move to their new places when the list changes, new ones fade in, gone ones fade out:
 * nothing pops. The scrim comes down from the top edge with the shade, the cards on it fading in over the first half.
 */
internal class CardsBlock(private val panel: QsPanel, private val ctx: Context, private val withFooter: Boolean) {
    private val u get() = panel.u

    /** A card of the list: a notification, or an app's group of them. */
    private class Card(val key: String, val section: Section, val items: List<Notifs.Item>) {
        val lead get() = items.first()
        val group get() = items.size > 1
    }

    /** One row of the list: its model (what it shows; [key] stays the same across rebuilds), the card it belongs to, its place. */
    private class Row(val m: RowModel, val card: Card?) {
        val key get() = m.key
        val kind get() = m.kind
        val section get() = m.section
        val expanded get() = m.expanded
        /** The notification it shows (a group's header and closed row: the latest). */
        val item: Notifs.Item? = card?.items?.getOrNull(m.index)
        var top = 0f
        var height = 0f
    }

    /** A row's motion: where it is (content px), how present, how far swiped, its press's ripple. */
    private inner class Anim {
        val y = MotionValue(0f, 2f, { panel.view.invalidate() })
        val alpha = MotionValue(0f, 1000f, { panel.view.invalidate() })
        val swipe = MotionValue(0f, 2f, { panel.view.invalidate() })
        val ripple = Ripple { panel.view.invalidate() }
        var height = 0f
        var leaving: Row? = null
    }

    private var cards: List<Card> = emptyList()
    /** [cards] as the row model takes them (kept from the last layout). */
    private var cardIns: List<CardIn> = emptyList()
    private var rows: List<Row> = emptyList()
    private val anims = HashMap<String, Anim>()
    /** What the user opened: groups, cards (or closed: the first), children (keys). */
    private val state = ExpandState()
    private var contentHeight = 0f

    private val scroller = IosScroller({ panel.view.invalidate() })

    // ------------------------------------------------------------------ the list

    /** The notifications changed (or the panel opens: [animate] false lays them out where they are). */
    fun setItems(items: List<Notifs.Item>, animate: Boolean) {
        // (A media notification is the media player's while it shows one, as Android's.)
        val playing = panel.host.media.active
        cards = cardsOf(items.filter { (!it.media || !playing) && (!it.summary || items.none { o -> o !== it && o.groupKey == it.groupKey }) })
        loadIcons()
        relayout(animate)
    }

    /** The panel's size or the design changed: the rows measured again. */
    fun layout() = relayout(animate = false)

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

    private val textX get() = Design.pt(QsTokens.CARD_PADDING, u) * 2f + Design.pt(QsTokens.CARD_ICON, u)
    private fun textWidth() = cardsWidth() - textX - Design.pt(QsTokens.CARD_PADDING, u)

    /** The cards' width (turned and split, Android's narrower column, centred) and where they start. */
    private fun cardsWidth(): Float = (panel.panelWidth - 2f * panel.side).let { if (panel.split) kotlin.math.min(it, Design.pt(QsTokens.LAND_CARDS_WIDTH, u)) else it }
    private fun cardsLeft(): Float = (panel.panelWidth - cardsWidth()) / 2f

    /** Lays the rows out again: each to its new place ([animate]: on the reflow curve, new ones fading in). */
    private fun relayout(animate: Boolean) {
        if (panel.panelWidth == 0) return
        val pad = Design.pt(QsTokens.CARD_PADDING, u)
        val icon = Design.pt(QsTokens.CARD_ICON, u)
        val byKey = cards.associateBy { it.key }
        cardIns = cards.map { c -> CardIn(c.key, c.section, c.items.map { it.key }, c.items.filter { !showsWhen(it) }.map { it.key }.toSet()) }
        val model = NotifRows.build(cardIns, state, withFooter)
        val out = model.map { m -> Row(m, m.cardKey?.let { byKey[it] }) }
        // Heights and places, in the content's own coordinates.
        var y = 0f
        for (r in out) {
            r.height = when (r.kind) {
                RowKind.HEADING -> Design.pt(QsTokens.SECTION_HEIGHT, u)
                RowKind.FOOTER -> Design.pt(QsTokens.CLEAR_HEIGHT, u)
                // (A closed group: its app's line, then one line of each of its latest notifications, as Android's.)
                RowKind.GROUP -> max(pad * 2 + icon, Design.pt(QsTokens.GROUP_LINES_TOP, u) +
                    r.m.lines * Design.pt(QsTokens.GROUP_LINE, u) + Design.pt(QsTokens.GROUP_LINES_BOTTOM, u))
                RowKind.HEADER -> pad * 2 + icon
                // (A child is a card without its icon's column: its height is its text's alone, and the hairline above it.)
                else -> (if (r.expanded) expandedHeight(r) else pad * 2 + max(if (r.m.icon) icon else 0f, line(QsTokens.CARD_TITLE) + bodyLine(r))) +
                    (if (r.m.divider) hairline() else 0f)
            }
            y += when (r.m.gapBefore) {
                Gap.NONE -> 0f
                Gap.JOINED -> Design.pt(QsTokens.JOINED_GAP, u)
                Gap.SECTION -> Design.pt(QsTokens.CARD_GAP, u)
                Gap.FOOTER -> Design.pt(QsTokens.CLEAR_GAP, u)
            }
            r.top = y
            y += r.height
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
        follow(clearAll, cards.any { it.items.any { i -> i.clearable } }, animate)
        follow(clearSilent, cards.any { it.section == Section.SILENT && it.items.any { i -> i.clearable } }, animate)
        bounds()
        panel.view.invalidate()
    }

    /** The hairline above an opened group's child: its token's width, at least one pixel (Android's is 0.5 dp, 1 px). */
    private fun hairline(): Float = max(1f, Math.round(Design.pt(QsTokens.DIVIDER_WIDTH, u)).toFloat())

    /** A collapsed card's text line (none without text). */
    private fun bodyLine(r: Row): Float = if (r.item?.text.isNullOrBlank()) 0f else line(QsTokens.CARD_BODY)

    /** What an expanded card shows of its text: its whole text (its big text where it has one), up to [MAX_LINES] lines. */
    private fun fullText(item: Notifs.Item): String? {
        val big = try { item.sbn.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) } catch (_: Throwable) { null }
        return (big ?: item.text)?.toString()?.takeIf { it.isNotBlank() }
    }

    private val measure = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private fun textLayout(s: String, paint: TextPaint, width: Float, lines: Int): StaticLayout =
        StaticLayout.Builder.obtain(s, 0, s.length, paint, width.toInt().coerceAtLeast(1)).setMaxLines(lines)
            .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 1f).setIncludePad(false).build()

    /** An expanded card: its app's line, its title, its whole text, and a row of its actions. */
    private fun expandedHeight(r: Row): Float {
        val item = r.item ?: return 0f
        val pad = Design.pt(QsTokens.CARD_PADDING, u)
        var h = pad + (if (r.m.head != Head.NONE) line(QsTokens.CARD_APP) + Design.pt(QsTokens.TITLE_GAP, u) else 0f) +
            line(QsTokens.CARD_TITLE) + Design.pt(QsTokens.TEXT_GAP, u)
        fullText(item)?.let { t ->
            Design.text(QsTokens.CARD_BODY).applyTo(measure, u)
            h += textLayout(t, measure, textWidth(), MAX_LINES).lineCount * line(QsTokens.CARD_BODY)
        }
        h += if (actionsOf(item).isNotEmpty() && !panel.host.locked) Design.pt(QsTokens.ACTIONS_HEIGHT, u) + Design.pt(QsTokens.ACTIONS_BOTTOM, u) else pad
        // (A card is at least as tall as its icon; a child has none.)
        return if (r.m.icon) max(h, pad * 2 + Design.pt(QsTokens.CARD_ICON, u)) else h
    }

    private fun actionsOf(item: Notifs.Item): List<Notification.Action> =
        item.actions.filter { it.actionIntent != null && !it.title.isNullOrBlank() && it.remoteInputs.isNullOrEmpty() }.take(3)

    // ------------------------------------------------------------------ where the list is

    /** The first row's top now: inside the notification scrim, which the panel moves. */
    private val listTop get() = panel.scrimTop + Design.pt(QsTokens.CARDS_TOP, u)

    /**
     * The list's viewport: under the scrim where it rests at the first stage (not where it is now: laid out while the shade
     * was still closed, the list had its whole height and could not scroll to its end; the media player lowers the scrim).
     */
    private fun visibleHeight() = panel.panelHeight - (panel.scrimRestTop + Design.pt(QsTokens.CARDS_TOP, u)) - bottomInset()
    private var boundsFor = Float.NaN

    private fun bottomInset() = 24f * u

    private fun bounds() {
        boundsFor = panel.scrimRestTop
        scroller.setBounds(0f, max(0f, contentHeight - visibleHeight()), visibleHeight())
    }

    private val r = RectF()

    /** Row [row]'s rectangle on screen now (its motion, the scroll, the scrim) into [out]. */
    private fun place(row: Row, a: Anim, out: RectF) {
        val top = listTop + a.y.value - scroller.position
        val dx = swipeOf(row, a)
        out.set(cardsLeft() + dx, top, cardsLeft() + cardsWidth() + dx, top + row.height)
    }

    /**
     * How far [row] is swiped: its own, and for a child its header's if that is further (an opened group swipes by its
     * header; a child that turns into a card mid-removal keeps where the flight put it).
     */
    private fun swipeOf(row: Row, a: Anim): Float {
        val own = a.swipe.value
        if (row.kind != RowKind.CHILD) return own
        val card = row.card ?: return own
        val header = anims[card.key] ?: return own
        val h = header.swipe.value
        return if (abs(h) > abs(own)) h else own
    }

    // ------------------------------------------------------------------ drawing

    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val radii = FloatArray(4)
    private val clip = Path()
    private val clipRadii = FloatArray(8)
    /** Clear all (and the Silent heading's clear button) while anything there can go: each comes and goes on its fade. */
    private val clearAll = MotionValue(0f, 1000f, { panel.view.invalidate() })
    private val clearSilent = MotionValue(0f, 1000f, { panel.view.invalidate() })

    private fun follow(v: MotionValue, on: Boolean, animate: Boolean) {
        val t = if (on) 1f else 0f
        if (v.target == t && (v.isAnimating || v.value == t)) return
        if (animate) v.animateTo(t, Motion.role(if (on) MotionTokens.APPEAR_FADE else MotionTokens.DISAPPEAR_FADE)) else v.snapTo(t)
    }

    fun draw(c: Canvas) {
        // (The scrim's place of rest moved: the media player came or went, the quick row's rows changed.)
        if (panel.scrimRestTop != boundsFor) bounds()
        val top = panel.scrimTop
        if (top >= panel.panelHeight || rows.isEmpty() && !withFooter) return
        // The scrim the notifications lie on: across the screen from its top, its top corners round.
        val corner = Design.pt(QsTokens.SHELF_CORNER, u)
        radii[0] = corner; radii[1] = corner; radii[2] = 0f; radii[3] = 0f
        // (It comes in with the shade, as its background does: measured on Android 17's own, both in step with the pull.)
        val p = panel.progress.coerceIn(0f, 1f)
        c.save()
        c.translate(0f, top)
        panel.surfaces.draw(c, Design.material(QsTokens.SHELF), panel.panelWidth.toFloat(), panel.panelHeight - top + corner, corner,
            0f, top, 1f, p, radii = radii)
        c.restore()
        // The cards come over the pull's later part (Android 17's own, measured: from a quarter of the way, whole by 88 %).
        val listAlpha = range(0.25f, 0.88f, p)
        if (listAlpha <= 0.003f) return
        val save = c.save()
        // (Every corner round, the bottom ones below the screen: a clip of one radius is analytic on the GPU; with the top
        // corners alone round it went through the stencil at every frame.)
        clip.rewind()
        clip.addRoundRect(0f, top, panel.panelWidth.toFloat(), panel.panelHeight + 2f * corner, corner, corner, Path.Direction.CW)
        c.clipPath(clip)
        for ((_, a) in anims) a.leaving?.let { drawRow(c, it, a, listAlpha) }
        for (row in rows) drawRow(c, row, anims[row.key] ?: continue, listAlpha)
        c.restoreToCount(save)
    }

    private fun drawRow(c: Canvas, row: Row, a: Anim, listAlpha: Float) {
        place(row, a, r)
        if (r.bottom < 0f || r.top > panel.panelHeight) return
        // A swiped card fades as it goes.
        val swipeFade = 1f - (abs(swipeOf(row, a)) / (panel.panelWidth * 0.6f)).coerceIn(0f, 1f)
        val f = listAlpha * a.alpha.value.coerceIn(0f, 1f) * swipeFade
        if (f <= 0.003f) return
        when (row.kind) {
            RowKind.HEADING -> drawHeading(c, r, f)
            RowKind.FOOTER -> drawFooter(c, r, f)
            else -> drawCard(c, row, a, r, f)
        }
    }

    private fun cornerPx(k: Corner, big: Float, joined: Float) = when (k) { Corner.BIG -> big; Corner.JOINED -> joined; Corner.NONE -> 0f }

    private fun drawCard(c: Canvas, row: Row, a: Anim, r: RectF, alpha: Float) {
        val item = row.item ?: return
        val big = Design.pt(QsTokens.CARD_CORNER, u)
        val joined = Design.pt(QsTokens.JOINED_CORNER, u)
        val top = cornerPx(row.m.top, big, joined)
        val bottom = cornerPx(row.m.bottom, big, joined)
        radii[0] = top; radii[1] = top; radii[2] = bottom; radii[3] = bottom
        c.save()
        c.translate(r.left, r.top)
        panel.surfaces.draw(c, Design.material(QsTokens.CARD), r.width(), r.height(), big, r.left, r.top, 1f, alpha, radii = radii)
        // (Pressed: the platform's ripple, as a notification's background has, in the theme's highlight colour.)
        if (a.ripple.active) a.ripple.draw(c, r.width(), r.height(), big, Design.color(QsTokens.CARD_RIPPLE), alpha, u, share = 1f, radii = radii)
        c.restore()
        val pad = Design.pt(QsTokens.CARD_PADDING, u)
        val icon = Design.pt(QsTokens.CARD_ICON, u)
        // An opened group's child: a hairline across the card's top, its content under it.
        val inset = if (row.m.divider) hairline() else 0f
        if (inset > 0f) {
            fill.color = faded(Design.color(QsTokens.DIVIDER), alpha)
            c.drawRect(r.left, r.top, r.right, r.top + inset, fill)
        }
        // The app's own icon in a circle (Android 16's notifications show it, not the small symbol), except a system app's (Play
        // Protect's shield, measured on the emulator's own): its small symbol on a circle of the notification's colour; without
        // an icon, the small symbol on the palette's colour.
        val app = if (item.pkg in systemApps) null else appIcon(item.pkg)
        // (A group's child has none: its text starts where a card's does, under the header's icon.)
        if (row.m.icon && app != null) {
            val cx = r.left + pad + icon / 2f
            val cy = r.top + pad + icon / 2f
            circle.rewind()
            circle.addCircle(cx, cy, icon / 2f, Path.Direction.CW)
            c.save()
            c.clipPath(circle)
            app.setBounds((cx - icon / 2f).toInt(), (cy - icon / 2f).toInt(), (cx + icon / 2f).toInt(), (cy + icon / 2f).toInt())
            app.alpha = (255 * alpha).toInt().coerceIn(0, 255)
            app.draw(c)
            c.restore()
        } else if (row.m.icon) {
            val own = if (item.pkg in systemApps && item.color != 0) item.color or (0xFF shl 24) else 0
            fill.color = faded(if (own != 0) own else Design.color(QsTokens.ICON_FILL), alpha)
            c.drawCircle(r.left + pad + icon / 2f, r.top + pad + icon / 2f, icon / 2f, fill)
        }
        if (row.m.icon && app == null) smallIcon(item)?.let { d ->
            val s = (icon * 0.55f).toInt().coerceAtLeast(1)
            d.setBounds(0, 0, s, s)
            val col = faded(Design.color(QsTokens.ICON_COLOR), alpha)
            d.setTint(col or (0xFF shl 24))
            d.alpha = (col ushr 24) and 0xFF
            c.save()
            c.translate(r.left + pad + (icon - s) / 2f, r.top + pad + (icon - s) / 2f)
            d.draw(c)
            c.restore()
        }
        val tx = r.left + textX
        val chipW = drawChip(c, row, r, pad, alpha, inset)
        val maxW = r.right - pad - chipW - tx
        val titleColor = faded(Design.color(QsTokens.CARD_TITLE_COLOR), alpha)
        val textColor = faded(Design.color(QsTokens.CARD_TEXT_COLOR), alpha)
        var y = r.top + inset + pad
        val time = if (showsWhen(item)) timeLabel(item.postTime) else null
        Design.text(QsTokens.CARD_APP).applyTo(small, u)
        small.color = textColor
        if (row.m.head != Head.NONE) {
            // The first line: the app and the time, or the time alone (a child's: the app is its header's), then the title.
            val al = line(QsTokens.CARD_APP)
            val label = if (row.m.head == Head.TIME) time.orEmpty() else {
                val appName = AppInfo.label(ctx, item.pkg)
                if (time != null) "$appName • $time" else appName
            }
            // (A header is that line alone, in the middle of its icon.)
            val lineTop = if (row.kind == RowKind.HEADER) y + (icon - al) / 2f else y
            c.drawText(ellipsize(label, small, maxW), tx, baselineIn(small, lineTop, al), small)
            if (row.kind == RowKind.HEADER) return
            // A closed group lists its latest notifications one line each under it.
            if (row.kind == RowKind.GROUP) { drawGroupLines(c, row, r, tx, pad, titleColor, textColor); return }
            y += al + if (row.expanded) Design.pt(QsTokens.TITLE_GAP, u) else 0f
        }
        Design.text(QsTokens.CARD_TITLE).applyTo(title, u)
        title.color = titleColor
        val tl = line(QsTokens.CARD_TITLE)
        val name = (item.title ?: AppInfo.label(ctx, item.pkg)).toString()
        if (row.m.head != Head.NONE || time == null) {
            c.drawText(ellipsize(name, title, if (row.expanded) r.right - pad - tx else maxW), tx, baselineIn(title, y, tl), title)
        } else {
            // A collapsed card: the title, then " • time" in the app line's type.
            val tail = " • $time"
            val shown = ellipsize(name, title, maxW - small.measureText(tail))
            val base = baselineIn(title, y, tl)
            c.drawText(shown, tx, base, title)
            c.drawText(tail, tx + title.measureText(shown), base, small)
        }
        y += tl + if (row.expanded) Design.pt(QsTokens.TEXT_GAP, u) else 0f
        Design.text(QsTokens.CARD_BODY).applyTo(body, u)
        body.color = textColor
        val bl = line(QsTokens.CARD_BODY)
        if (row.expanded) {
            fullText(item)?.let { t ->
                val layout = textLayout(t, body, textWidth(), MAX_LINES)
                for (i in 0 until layout.lineCount) {
                    val s = t.substring(layout.getLineStart(i), layout.getLineEnd(i)).trimEnd('\n')
                    val shown = if (i == layout.lineCount - 1 && layout.getEllipsisCount(i) > 0)
                        ellipsize(t.substring(layout.getLineStart(i)).replace('\n', ' '), body, textWidth()) else s
                    c.drawText(shown, tx, baselineIn(body, y, bl), body)
                    y += bl
                }
            }
            if (actionsOf(item).isNotEmpty() && !panel.host.locked) drawActions(c, item, r, y, alpha)
        } else {
            val text = item.text?.toString()?.replace('\n', ' ')
            // (Collapsed, the text runs under the chevron's column to the card's padding, as Android's.)
            if (!text.isNullOrBlank()) c.drawText(ellipsize(text, body, r.right - pad - tx), tx, baselineIn(body, y, bl), body)
        }
    }

    /**
     * A closed group's rows: the title and the text of each of its latest [RowModel.lines] notifications on one line
     * ("Podcast New episode available", Android's hybrid view: the text after the title and [QsTokens.GROUP_LINE_GAP]),
     * each cut with an ellipsis at the card's padding; the text is left out when the title alone fills the line.
     */
    private fun drawGroupLines(c: Canvas, row: Row, r: RectF, tx: Float, pad: Float, titleColor: Int, textColor: Int) {
        val items = row.card?.items ?: return
        val lineH = Design.pt(QsTokens.GROUP_LINE, u)
        val gap = Design.pt(QsTokens.GROUP_LINE_GAP, u)
        val right = r.right - pad
        Design.text(QsTokens.CARD_TITLE).applyTo(title, u)
        title.color = titleColor
        Design.text(QsTokens.CARD_BODY).applyTo(body, u)
        body.color = textColor
        for (i in 0 until minOf(row.m.lines, items.size)) {
            val it = items[i]
            val base = baselineIn(title, r.top + Design.pt(QsTokens.GROUP_LINES_TOP, u) + i * lineH, lineH)
            val name = it.title?.toString()?.replace('\n', ' ')?.takeIf { s -> s.isNotBlank() }
            val text = it.text?.toString()?.replace('\n', ' ')?.takeIf { s -> s.isNotBlank() }
            var x = tx
            if (name != null) {
                val shown = ellipsize(name, title, right - x)
                c.drawText(shown, x, base, title)
                if (shown != name) continue
                x += title.measureText(shown) + if (text != null) gap else 0f
            }
            if (text != null && x < right) c.drawText(ellipsize(text, body, right - x), x, base, body)
        }
    }

    /** Whether [item] shows its time (Android's showWhen). */
    private fun showsWhen(item: Notifs.Item): Boolean =
        try { item.sbn.notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN, true) } catch (_: Throwable) { true }

    /** A group's count and chevron, or a card's chevron, at the top right; returns the room it takes. */
    private fun drawChip(c: Canvas, row: Row, r: RectF, pad: Float, a: Float, inset: Float): Float {
        val h = Design.pt(QsTokens.CHIP_HEIGHT, u)
        val cw = Design.pt(QsTokens.CHIP_WIDTH, u)
        val count = row.m.count
        Design.text(QsTokens.CHIP_TEXT).applyTo(small, u)
        val label = if (count > 0) "$count" else ""
        val w = (if (label.isEmpty()) 0f else small.measureText(label) + 4f * u) + cw
        // (A header's chip is in the middle of its row, as its one line is.)
        val chipTop = if (row.kind == RowKind.HEADER) r.top + (r.height() - h) / 2f else r.top + inset + pad
        val chip = RectF(r.right - pad - w, chipTop, r.right - pad, chipTop + h)
        chips[row.key] = chip
        fill.color = faded(Design.color(QsTokens.CHIP_FILL), a)
        c.drawRoundRect(chip, h / 2f, h / 2f, fill)
        val col = faded(Design.color(QsTokens.CARD_TITLE_COLOR), a)
        if (label.isNotEmpty()) {
            small.color = col
            small.textAlign = Paint.Align.LEFT
            c.drawText(label, chip.left + 8f * u, baselineIn(small, chip.top, h), small)
        }
        // Down while closed, up while open.
        val cx = chip.right - cw / 2f
        if (row.m.chevron != Chevron.NONE) {
            c.save()
            c.rotate(if (row.m.chevron == Chevron.UP) -90f else 90f, cx, chip.centerY())
            panel.glyphs.draw(c, R.drawable.sym_chevron, cx, chip.centerY(), 16f * u, col)
            c.restore()
        }
        return w + 8f * u
    }

    private val chips = HashMap<String, RectF>()
    private val actionHits = ArrayList<Pair<Notification.Action, RectF>>()

    /** A card's actions: words in a row (Android 16's template), each with room around it to tap. */
    private fun drawActions(c: Canvas, item: Notifs.Item, r: RectF, top: Float, a: Float) {
        val acts = actionsOf(item)
        val h = Design.pt(QsTokens.ACTIONS_HEIGHT, u)
        val padX = Design.pt(QsTokens.ACTION_PADDING, u)
        val inset = Design.pt(QsTokens.ACTION_INSET, u)
        Design.text(QsTokens.ACTION_TEXT).applyTo(small, u)
        small.textAlign = Paint.Align.LEFT
        small.color = faded(Design.color(QsTokens.ACTION_COLOR), a)
        var x = r.left + inset
        val end = r.right - inset
        for (act in acts) {
            val words = act.title.toString()
            val w = minOf(small.measureText(words) + 2 * padX, end - x)
            if (w <= 2 * padX) break
            val b = RectF(x, top, x + w, top + h)
            c.drawText(ellipsize(words, small, w - 2 * padX), x + padX, baselineIn(small, top, h), small)
            actionHits += act to b
            x += w + inset
        }
    }

    private val headingClear = RectF()

    private fun drawHeading(c: Canvas, r: RectF, a: Float) {
        Design.text(QsTokens.SECTION).applyTo(small, u)
        small.color = faded(Design.color(QsTokens.SECTION_COLOR), a)
        small.textAlign = Paint.Align.LEFT
        val pad = Design.pt(QsTokens.CARD_PADDING, u)
        c.drawText("Silent", r.left + pad, baselineIn(small, r.top, r.height()), small)
        // Its clear button, while any of its notifications can go.
        headingClear.setEmpty()
        val k = clearSilent.value.coerceIn(0f, 1f)
        if (k <= 0.003f) return
        val s = 20f * u
        if (k > 0.5f) headingClear.set(r.right - pad - 40f * u, r.top, r.right, r.bottom)
        panel.glyphs.draw(c, R.drawable.sym_close, r.right - pad - s / 2f, r.centerY(), s * (0.6f + 0.4f * k), faded(Design.color(QsTokens.SECTION_COLOR), a * k))
    }

    private val footerHistory = RectF()
    private val footerClear = RectF()
    private val footerSettings = RectF()

    private fun drawFooter(c: Canvas, r: RectF, a: Float) {
        // (The row is Android's 48 dp of touch; its buttons are drawn inside it, inset top and bottom.)
        val inset = Design.pt(QsTokens.CLEAR_INSET, u)
        val top = r.top + inset
        val bottom = r.bottom - inset
        val h = bottom - top
        val side = Design.pt(QsTokens.CLEAR_SIDE, u)
        val gap = Design.pt(QsTokens.CLEAR_SPACING, u)
        footerHistory.set(r.left, top, r.left + side, bottom)
        footerSettings.set(r.right - side, top, r.right, bottom)
        // Clear all between them while anything can go, growing from its middle as it comes.
        val k = clearAll.value.coerceIn(0f, 1f)
        val cx = (footerHistory.right + footerSettings.left) / 2f
        val half = (footerSettings.left - footerHistory.right) / 2f - gap
        if (k > 0.003f) footerClear.set(cx - half * (0.7f + 0.3f * k), top, cx + half * (0.7f + 0.3f * k), bottom) else footerClear.setEmpty()
        fill.color = faded(Design.color(QsTokens.CLEAR_FILL), a)
        c.drawRoundRect(footerHistory, h / 2f, h / 2f, fill)
        c.drawRoundRect(footerSettings, h / 2f, h / 2f, fill)
        if (!footerClear.isEmpty) {
            fill.color = faded(Design.color(QsTokens.CLEAR_FILL), a * k)
            c.drawRoundRect(footerClear, h / 2f, h / 2f, fill)
        }
        // Pressed: the platform's ripple over the button.
        val rc = Design.color(QsTokens.CARD_RIPPLE)
        for (b in FooterButton.entries) {
            val rp = footerRipples.getValue(b)
            val rect = footerRect(b)
            if (!rp.active || rect.isEmpty) continue
            c.save()
            c.translate(rect.left, rect.top)
            rp.draw(c, rect.width(), rect.height(), h / 2f, rc, a, u, share = 1f)
            c.restore()
        }
        val col = faded(Design.color(QsTokens.CLEAR_TEXT_COLOR), a)
        val s = Design.pt(QsTokens.CLEAR_SYMBOL, u)
        panel.glyphs.draw(c, R.drawable.sym_history, footerHistory.centerX(), footerHistory.centerY(), s, col)
        panel.glyphs.draw(c, R.drawable.sym_notification_settings, footerSettings.centerX(), footerSettings.centerY(), s, col)
        if (footerClear.isEmpty) return
        Design.text(QsTokens.CLEAR_TEXT).applyTo(small, u)
        small.color = faded(col, k)
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
    private val appIcons = HashMap<String, Drawable?>()
    private val circle = Path()

    /** [pkg]'s launcher icon (read before the notifications are published: [dev.launcher.app.shade.AppInfo]), kept per app. */
    private fun appIcon(pkg: String): Drawable? {
        if (appIcons.containsKey(pkg)) return appIcons[pkg]
        val d = AppInfo.iconIfRead(ctx, pkg)?.mutate() ?: return null
        appIcons[pkg] = d
        return d
    }

    /** Apps that came with the phone (their notifications keep the small symbol, as Android's). */
    private val systemApps = HashSet<String>()

    private fun smallIcon(item: Notifs.Item): Drawable? = icons[item.key + "|" + item.postTime]

    private fun loadIcons() {
        val want = cards.flatMap { it.items }.filter { (it.key + "|" + it.postTime) !in icons }
        if (want.isEmpty()) return
        for (it in want) icons[it.key + "|" + it.postTime] = null
        val h = panel.host.thread()
        io.execute {
            val got = want.associate { it.key + "|" + it.postTime to (try { it.smallIcon?.loadDrawable(ctx)?.mutate() } catch (_: Throwable) { null }) }
            // Which apps came with the phone (asked here, off the drawing thread: a call into the package manager).
            val system = want.map { it.pkg }.distinct().filter { p ->
                try { (ctx.packageManager.getApplicationInfo(p, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 } catch (_: Throwable) { false }
            }
            h.post {
                systemApps.addAll(system)
                icons.putAll(got)
                if (icons.size > 200) icons.keys.retainAll(cards.flatMap { c -> c.items.map { it.key + "|" + it.postTime } }.toSet())
                panel.view.invalidate()
            }
        }
    }

    // ------------------------------------------------------------------ touches

    /** The row under ([x], [y]) (the list at the first stage), or null. */
    private fun rowAt(x: Float, y: Float): Row? {
        if (panel.expandK > 0.5f || y < panel.scrimTop) return null
        for (row in rows) {
            val a = anims[row.key] ?: continue
            place(row, a, r)
            if (r.contains(x, y)) return row
        }
        return null
    }

    fun contains(x: Float, y: Float) = rowAt(x, y) != null

    private var pressed: Row? = null

    private enum class FooterButton { HISTORY, CLEAR, SETTINGS }
    private val footerRipples = FooterButton.entries.associateWith { Ripple { panel.view.invalidate() } }
    private var pressedFooter: FooterButton? = null

    private fun footerRect(b: FooterButton) = when (b) {
        FooterButton.HISTORY -> footerHistory
        FooterButton.CLEAR -> footerClear
        FooterButton.SETTINGS -> footerSettings
    }

    /** A finger down on a card: it darkens a little while held (Android's ripple, as the iOS platters press). */
    /** A finger down at ([x], [y]): the platform's ripple spreads from it over the card or footer button under it. */
    fun press(x: Float, y: Float) {
        val row = rowAt(x, y)?.takeIf { it.kind != RowKind.HEADING } ?: return
        if (row.kind == RowKind.FOOTER) {
            val b = when {
                !footerClear.isEmpty && clearAll.target > 0.5f && footerClear.contains(x, y) -> FooterButton.CLEAR
                footerHistory.contains(x, y) -> FooterButton.HISTORY
                footerSettings.contains(x, y) -> FooterButton.SETTINGS
                else -> return
            }
            val rect = footerRect(b)
            pressedFooter = b
            footerRipples.getValue(b).press(x - rect.left, y - rect.top)
            return
        }
        val a = anims[row.key] ?: return
        pressed = row
        place(row, a, r)
        a.ripple.press(x - r.left, y - r.top)
    }

    fun release() {
        pressedFooter?.let { footerRipples.getValue(it).release() }
        pressedFooter = null
        val row = pressed ?: return
        pressed = null
        anims[row.key]?.ripple?.release()
    }

    /** The list has been scrolled (a drag down scrolls it back before it expands Quick Settings). */
    val scrolled: Boolean get() = scroller.position > 0.5f
    /** The list has more below (a drag up scrolls it before it closes the shade). */
    val canScrollDown: Boolean get() = contentHeight > visibleHeight() && scroller.position < contentHeight - visibleHeight() - 0.5f

    fun beginScroll() { bounds(); scroller.beginDrag() }
    fun scrollBy(dy: Float) = scroller.dragBy(dy)
    fun endScroll(vy: Float) = scroller.endDrag(vy)

    private var swiping: Row? = null

    /** A sideways drag on the row under ([x], [y]) begins; false if there is nothing to swipe there. */
    fun beginSwipe(x: Float, y: Float): Boolean {
        val row = rowAt(x, y) ?: return false
        if (row.kind == RowKind.HEADING || row.kind == RowKind.FOOTER) return false
        swiping = row
        anims[row.key]?.swipe?.stop()
        return true
    }

    fun swipeBy(dx: Float) {
        val row = swiping ?: return
        val a = anims[row.key] ?: return
        val clearable = itemsOf(row).all { it.clearable }
        // One that cannot go resists.
        a.swipe.snapTo(if (clearable) dx else Motion.rubberBand(dx, panel.panelWidth * 0.2f))
    }

    fun endSwipe(vx: Float) {
        val row = swiping ?: return
        swiping = null
        val a = anims[row.key] ?: return
        val clearable = itemsOf(row).all { it.clearable }
        val far = abs(a.swipe.value) > panel.panelWidth * 0.35f || abs(vx) > 1200f * u / 2.6f
        if (!clearable || !far) { a.swipe.animateTo(0f, Motion.role(MotionTokens.NC_SWIPE_BACK)); return }
        val dir = if (a.swipe.value != 0f) kotlin.math.sign(a.swipe.value) else kotlin.math.sign(vx)
        val target = dir * panel.panelWidth * 1.1f
        a.swipe.animateTo(target, Motion.role(MotionTokens.NC_SWIPE_OUT), vx)
        // (An opened group's children fly with their header, each on its own: one left after the others are cancelled
        // becomes a card of its own, which must stay where the flight put it.)
        if (row.kind == RowKind.HEADER) for (c in rows) if (c.kind == RowKind.CHILD && c.card?.key == row.key) {
            anims[c.key]?.swipe?.animateTo(target, Motion.role(MotionTokens.NC_SWIPE_OUT), vx)
        }
        panel.view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        for (it in itemsOf(row)) Notifs.cancel(it)
    }

    /** What swiping [row] clears: a group's (closed or its header's, past the eight shown too) every notification. */
    private fun itemsOf(row: Row): List<Notifs.Item> =
        if (row.kind == RowKind.GROUP || row.kind == RowKind.HEADER) row.card?.items.orEmpty() else listOfNotNull(row.item)

    /** A tap at ([x], [y]): its chip, an action, the footer's buttons, or the card itself. */
    fun tap(x: Float, y: Float): Boolean {
        val row = rowAt(x, y) ?: return false
        when (row.kind) {
            RowKind.HEADING -> {
                if (!headingClear.isEmpty && x >= headingClear.left) clear(cards.filter { it.section == Section.SILENT }.flatMap { it.items })
                return true
            }
            RowKind.FOOTER -> {
                when {
                    !footerClear.isEmpty && clearAll.target > 0.5f && footerClear.contains(x, y) -> clear(cards.flatMap { it.items })
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
        // A group's body opens it, an opened group's header closes it; a card (or a child) opens its app.
        if (row.kind == RowKind.GROUP || row.kind == RowKind.HEADER) { toggle(row); return true }
        val item = row.item ?: return true
        place(row, anims.getValue(row.key), r)
        panel.host.open(item, RectF(r))
        return true
    }

    /** A group opens into its notifications (its header closes it); a card or a child opens to its whole text and actions (or closes). */
    private fun toggle(row: Row) {
        panel.view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        NotifRows.toggle(row.m, state)
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
        // (A group goes as one card only if all of it goes; an opened group's header takes its children along, each
        // flying on its own anim so that one left over stays where the flight put it.)
        for (m in NotifRows.flying(rows.map { it.m }, cardIns, keys)) {
            val a = anims[m.key] ?: continue
            val delay = (k++ * step).coerceAtMost(step * 6)
            val along = if (m.kind == RowKind.HEADER) rows.filter { it.kind == RowKind.CHILD && it.card?.key == m.key }.mapNotNull { anims[it.key] } else emptyList()
            panel.view.postDelayed({
                a.swipe.animateTo(panel.panelWidth * 1.1f, Motion.role(MotionTokens.NC_SWIPE_OUT))
                for (c in along) c.swipe.animateTo(panel.panelWidth * 1.1f, Motion.role(MotionTokens.NC_SWIPE_OUT))
            }, delay)
        }
        panel.view.postDelayed({ for (it in gone) Notifs.cancel(it) }, (k * step).coerceAtMost(step * 6) + step * 3)
    }

    /** The panel closed: the list back at its top, opened cards closed. */
    fun closed() {
        scroller.jumpTo(0f)
        state.clear()
        swiping = null
        pressed = null
        actionHits.clear()
    }

    fun beginFrame() = actionHits.clear()

    private companion object {
        val io = Executors.newSingleThreadExecutor()
        /** An expanded card's text at most this many lines (Android's big text in the shade). */
        const val MAX_LINES = 7
    }
}
