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
        /** A group's count as its chip writes it (made once, not at every frame). */
        val countLabel: String = if (m.count > 0) m.count.toString() else ""
        val key get() = m.key
        val kind get() = m.kind
        val section get() = m.section
        val expanded get() = m.expanded
        /** The notification it shows (a group's header and closed row: the latest). */
        val item: Notifs.Item? = card?.items?.getOrNull(m.index)
        var top = 0f
        var height = 0f
    }

    /**
     * A row's motion: where it is and how tall (content px), how open (0 collapsed or closed, 1 expanded or opened: the
     * crossfade between its two contents and its chevron's turn), how present, how far swiped, its press's ripple.
     */
    private inner class Anim {
        val y = MotionValue(0f, 2f, { panel.view.invalidate() })
        val h = MotionValue(0f, 2f, { panel.view.invalidate() })
        val k = MotionValue(0f, 1000f, { panel.view.invalidate() })
        val alpha = MotionValue(0f, 1000f, { panel.view.invalidate() })
        val swipe = MotionValue(0f, 2f, { panel.view.invalidate() })
        /**
         * How far a child of a group is from the line it is the card form of (0: it is that one-line row of the closed group,
         * 1: a child of its own): it unfolds from its line as the group opens and folds back into it as it closes.
         */
        val fold = MotionValue(1f, 1000f, { panel.view.invalidate() })
        /** How much of the app's icon it shows (a child that becomes a card gains it, a card that becomes a child loses it). */
        val icon = MotionValue(1f, 1000f, { panel.view.invalidate() })
        /** How much of the hairline above it (a child's) it shows. */
        val div = MotionValue(0f, 1000f, { panel.view.invalidate() })
        val ripple = Ripple { panel.view.invalidate() }
        var leaving: Row? = null
        /** A child that leaves: whether it was its card's top (no row before it stays) or bottom (none after it), for its cut's corners. */
        var endTop = false
        var endBottom = false
        /**
         * How far a child swiped by itself is split from its group (0: one of its rows, 1: a card of its own with the big
         * corners all round, and the group above and below it with big corners where it was).
         */
        val split = MotionValue(0f, 1000f, { panel.view.invalidate() })
        /** A child launched to fly with its header (it shares the group's surface all the way). */
        var withHeader = false
        /** The closed group's line this child unfolds from and folds into, or [NotifRows.BELOW]. */
        var line = NotifRows.BELOW
        /** Leaving by folding into its line: it keeps showing (and is drawn over its group) until it is that line. */
        var folding = false
        /** Anything of it still moving. */
        val busy get() = alpha.isAnimating || y.isAnimating || h.isAnimating || k.isAnimating || fold.isAnimating || icon.isAnimating || div.isAnimating || split.isAnimating
    }

    private var cards: List<Card> = emptyList()
    /** [cards] as the row model takes them (kept from the last layout). */
    private var cardIns: List<CardIn> = emptyList()
    private var rows: List<Row> = emptyList()
    /** Some row is on its way out (the list at rest has none: nothing to look through at a frame). */
    private var anyLeaving = false
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
    fun layout() {
        // (What was worked out for the old size and fonts.)
        itemTexts.clear()
        groupCache.clear()
        relayout(animate = false)
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
            // (A group that has fallen to one notification while the panel is open stays a group of one: the header and its child.)
            if (NotifRows.staysGroup(gk, members.size, state)) { seen += gk; out += Card(gk, sectionOf(it), members) } else out += Card(it.key, sectionOf(it), listOf(it))
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
        hairlinePx = hairline()
        // Rows that went fade out where they were (a child of a group that closed folds back into the group's lines); the
        // others move to their places.
        val keys = out.map { it.key }.toHashSet()
        for ((k, a) in anims) {
            if (k in keys || a.leaving != null) continue
            val old = rows.firstOrNull { it.key == k } ?: continue
            a.leaving = old
            a.folding = false
            if (animate) leave(a, old, out) else a.alpha.snapTo(0f)
        }
        val reflow = Motion.role(MotionTokens.NC_REFLOW)
        for (r in out) {
            val a = anims[r.key]
            if (a != null && a.leaving != null) {
                // A key that comes back while it is still going carries on from where it is (never a jump): its place,
                // height, openness and fold on the reflow, its presence on the appear.
                a.leaving = null
                a.folding = false
                a.withHeader = false
                a.endTop = false
                a.endBottom = false
                if (animate) {
                    a.y.animateTo(r.top, reflow)
                    a.h.animateTo(r.height, reflow)
                    a.k.animateTo(openness(r), reflow)
                    a.fold.animateTo(1f, reflow)
                    a.icon.animateTo(iconOf(r), reflow)
                    a.div.animateTo(dividerOf(r), reflow)
                    a.alpha.animateTo(1f, Motion.role(MotionTokens.NC_APPEAR))
                    if (abs(a.swipe.value) > 0.5f) a.swipe.animateTo(0f, Motion.role(MotionTokens.NC_SWIPE_BACK))
                } else {
                    a.y.snapTo(r.top)
                    a.h.snapTo(r.height)
                    a.k.snapTo(openness(r))
                    a.fold.snapTo(1f)
                    a.icon.snapTo(iconOf(r))
                    a.div.snapTo(dividerOf(r))
                    a.alpha.snapTo(1f)
                    a.swipe.snapTo(0f)
                }
            } else if (a == null) {
                val n = Anim().also { anims[r.key] = it }
                n.swipe.snapTo(0f)
                n.icon.snapTo(iconOf(r))
                n.div.snapTo(dividerOf(r))
                val from = if (animate) unfoldFrom(r) else null
                if (from != null) {
                    // A child of a group that opens: it starts where its line was (or at the closed card's bottom) and
                    // grows to its place with the rest; what the line showed is what it shows, until it is its own.
                    n.line = from.line
                    n.y.snapTo(from.y)
                    n.h.snapTo(from.h)
                    n.k.snapTo(0f)
                    n.fold.snapTo(from.fold)
                    n.alpha.snapTo(from.alpha)
                    n.y.animateTo(r.top, reflow)
                    n.h.animateTo(r.height, reflow)
                    if (openness(r) != 0f) n.k.animateTo(openness(r), reflow)
                    if (from.fold != 1f) n.fold.animateTo(1f, reflow)
                    if (from.alpha != 1f) n.alpha.animateTo(1f, Motion.role(MotionTokens.NC_APPEAR))
                } else {
                    n.y.snapTo(r.top)
                    n.h.snapTo(r.height)
                    n.k.snapTo(openness(r))
                    n.fold.snapTo(1f)
                    if (animate) { n.alpha.snapTo(0f); n.alpha.animateTo(1f, Motion.role(MotionTokens.NC_APPEAR)) } else n.alpha.snapTo(1f)
                }
            } else if (animate) {
                // (Place, height and openness on one role in one frame: a row that grows or folds never has one of them
                // ahead of the others. A second change mid-way starts from where each is, with its speed.)
                a.y.animateTo(r.top, reflow)
                a.h.animateTo(r.height, reflow)
                if (a.k.target != openness(r)) a.k.animateTo(openness(r), reflow)
                if (a.icon.target != iconOf(r)) a.icon.animateTo(iconOf(r), reflow)
                if (a.div.target != dividerOf(r)) a.div.animateTo(dividerOf(r), reflow)
            } else {
                a.y.snapTo(r.top)
                a.h.snapTo(r.height)
                a.k.snapTo(openness(r))
                a.icon.snapTo(iconOf(r))
                a.div.snapTo(dividerOf(r))
            }
        }
        // (What is kept for the rows' texts goes with the rows that went.)
        val itemKeys = cards.flatMapTo(HashSet()) { c -> c.items.map { it.key } }
        itemTexts.keys.retainAll(itemKeys)
        groupCache.keys.retainAll(keys)
        anims.entries.removeAll { (k, a) -> k !in keys && a.leaving != null && !a.busy }
        rows = out
        anyLeaving = anims.values.any { it.leaving != null }
        // (A closed group's lines are made here, not in its first frame; an opened group keeps them to fade out.)
        for (r in out) if (r.kind == RowKind.GROUP) r.card?.let { groupLines(it, minOf(r.m.lines, it.items.size)) }
        follow(clearAll, cards.any { it.items.any { i -> i.clearable } }, animate)
        follow(clearSilent, cards.any { it.section == Section.SILENT && it.items.any { i -> i.clearable } }, animate)
        bounds()
        panel.view.invalidate()
    }

    /** How open [r] is meant to be: 1 for an expanded card or child and an opened group's header, 0 otherwise. */
    private fun openness(r: Row) = if (r.expanded) 1f else 0f
    private fun iconOf(r: Row) = if (r.m.icon) 1f else 0f
    private fun dividerOf(r: Row) = if (r.m.divider) 1f else 0f

    /** Which ends of its card child [old] leaves ([NotifRows.endsAt]), as the old rows and the layout [out] say. */
    private fun cardEnds(old: Row, out: List<Row>): Int {
        val idx = rows.indexOfFirst { it.key == old.key }
        if (idx < 0) return 0
        var lo = idx
        var hi = idx
        while (lo > 0 && rows[lo - 1].m.cardKey == old.m.cardKey) lo--
        while (hi + 1 < rows.size && rows[hi + 1].m.cardKey == old.m.cardKey) hi++
        return NotifRows.endsAt(idx, lo, hi, BooleanArray(rows.size) { i -> out.any { it.key == rows[i].key } })
    }

    /** Where child [old] (of the old rows) closes up to in the layout [out] (null: stays). */
    private fun closeTarget(old: Row, out: List<Row>): Float? {
        val idx = rows.indexOfFirst { it.key == old.key }
        if (idx < 0) return null
        var lo = idx
        var hi = idx
        while (lo > 0 && rows[lo - 1].m.cardKey == old.m.cardKey) lo--
        while (hi + 1 < rows.size && rows[hi + 1].m.cardKey == old.m.cardKey) hi++
        val tops = FloatArray(rows.size) { Float.NaN }
        val bottoms = FloatArray(rows.size) { Float.NaN }
        for (i in rows.indices) out.firstOrNull { it.key == rows[i].key }?.let { tops[i] = it.top; bottoms[i] = it.top + it.height }
        val t = NotifRows.closeTarget(idx, lo, hi, tops, bottoms)
        return if (t.isNaN()) null else t
    }

    /** [old] leaves: it fades where it is, or if it was a child of a group that closed, folds back (into its line or below). */
    private fun leave(a: Anim, old: Row, out: List<Row>) {
        val group = if (old.kind == RowKind.CHILD) out.firstOrNull { it.kind == RowKind.GROUP && it.key == old.m.cardKey } else null
        if (group == null) {
            a.alpha.animateTo(0f, Motion.role(MotionTokens.NC_LEAVE))
            // A child of an opened group closes up as it fades (the card's surface is one, and follows the rows' extent); one that
            // flies with its header does not (whole until gone).
            if (old.kind == RowKind.CHILD && !a.withHeader) {
                a.h.animateTo(0f, Motion.role(MotionTokens.NC_REFLOW))
                // (Where its gap lies among its neighbours in the new layout, not where it was.)
                closeTarget(old, out)?.let { a.y.animateTo(it, Motion.role(MotionTokens.NC_REFLOW)) }
                // (Which end of its card it was, for the corners of its cut: set once, when it leaves.)
                val ends = cardEnds(old, out)
                a.endTop = ends and 1 != 0
                a.endBottom = ends and 2 != 0
            }
            // (Its card and the rows round it come back together as it closes up.)
            if (a.split.target != 0f) a.split.animateTo(0f, Motion.role(MotionTokens.NC_REFLOW))
            return
        }
        val line = NotifRows.foldLine(group.card?.items?.indexOfFirst { it.key == old.key } ?: -1, group.m.lines)
        val reflow = Motion.role(MotionTokens.NC_REFLOW)
        a.k.animateTo(0f, reflow)
        a.line = line
        if (line == NotifRows.BELOW) {
            // Under the lines: into the closed card's bottom, fading as the card takes its place.
            a.y.animateTo(group.top + group.height, reflow)
            a.h.animateTo(0f, reflow)
            a.alpha.animateTo(0f, Motion.role(MotionTokens.NC_LEAVE))
        } else {
            // Into its line, which the group shows again once it is there (the same strings in the same place).
            a.folding = true
            a.y.animateTo(NotifRows.foldTop(group.top, Design.pt(QsTokens.GROUP_LINES_TOP, u), Design.pt(QsTokens.GROUP_LINE, u), line), reflow)
            a.h.animateTo(Design.pt(QsTokens.GROUP_LINE, u), reflow)
            a.fold.animateTo(0f, reflow)
        }
    }

    /** Where a child that comes with a group opening starts (and how much it shows of itself there). */
    private class Start(val y: Float, val h: Float, val fold: Float, val line: Int, val alpha: Float)

    private fun unfoldFrom(r: Row): Start? {
        if (r.kind != RowKind.CHILD) return null
        val g = rows.firstOrNull { it.kind == RowKind.GROUP && it.key == r.m.cardKey } ?: return null
        val ha = anims[g.key] ?: return null
        val line = NotifRows.foldLine(g.card?.items?.indexOfFirst { it.key == r.key } ?: -1, g.m.lines)
        if (line == NotifRows.BELOW) return Start(ha.y.value + ha.h.value, 0f, 1f, line, 0f)
        return Start(NotifRows.foldTop(ha.y.value, Design.pt(QsTokens.GROUP_LINES_TOP, u), Design.pt(QsTokens.GROUP_LINE, u), line),
            Design.pt(QsTokens.GROUP_LINE, u), 0f, line, 1f - ha.k.value.coerceIn(0f, 1f))
    }

    /** The hairline above an opened group's child: its token's width, at least one pixel (Android's is 0.5 dp, 1 px). */
    private fun hairline(): Float = max(1f, Math.round(Design.pt(QsTokens.DIVIDER_WIDTH, u)).toFloat())
    /** [hairline], measured once per layout (drawn at every frame of a row that moves). */
    private var hairlinePx = 1f

    /** A collapsed card's text line (none without text). */
    private fun bodyLine(r: Row): Float = if (r.item?.text.isNullOrBlank()) 0f else line(QsTokens.CARD_BODY)

    /**
     * What an expanded card shows of its text: its whole text, up to [MAX_LINES] lines. The item's text already holds the
     * big text where there is one ([Notifs.read]) and is what redaction replaces: the raw notification is never read here,
     * it would show on the lock screen what the item hides.
     */
    private fun fullText(item: Notifs.Item): String? = item.text?.toString()?.takeIf { it.isNotBlank() }

    private val measure = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private fun textLayout(s: String, paint: TextPaint, width: Float, lines: Int): StaticLayout =
        StaticLayout.Builder.obtain(s, 0, s.length, paint, width.toInt().coerceAtLeast(1)).setMaxLines(lines)
            .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 1f).setIncludePad(false).build()

    /**
     * What a card draws of its notification that takes work to make, kept while the notification, the width and the time's
     * label stay the same, so that a row whose two contents are both drawn at every frame of its motion makes nothing at
     * them (a notification is immutable: [item] is the key).
     */
    private class ItemText(
        val item: Notifs.Item, val width: Int, val age: Long, val app: String,
        /** Its time's label, or null when it shows none (showWhen off). */
        val time: String?,
        /** "App • time" (or the app alone). */
        val appLine: String,
        /** " • time" (empty with none), in a collapsed card's title line. */
        val tail: String,
        val name: String,
        /** The text on one line (newlines as spaces), or null. */
        val oneLine: String?,
    ) {
        /** The whole text broken into lines (the last cut with an ellipsis), made on first use. */
        var full: Array<String>? = null
        /** The actions it shows, their words and the words cut to the width they were last drawn at (made on first use). */
        var actions: List<Notification.Action>? = null
        var words: Array<String>? = null
        var wordCuts: Array<Cut>? = null
        /** The title (cut for the collapsed and the expanded width) and the first line of its text, as they glide between. */
        val titleGlide = Glide()
        val textGlide = Glide()
        /** The app's line and a child's first line, cut to the width they were last drawn at. */
        val appCut = Cut()
        val headCut = Cut()
    }

    /**
     * What a card shows of one string in two states that differ only in where it is cut (the collapsed title ends before its
     * " • time", the collapsed text at an ellipsis, the expanded text at a line's end): the part both share ([prefix], drawn
     * once, at one place), and the two endings, which cross-fade. Made when the widths change, never while drawing.
     */
    private class Glide {
        var w0 = -1
        var w1 = -1
        var prefix = ""
        var end0 = ""
        var end1 = ""
        /** Where the endings start (the prefix's width), and where the first whole string ends. */
        var dx = 0f
        var width0 = 0f

        fun set(w0: Int, w1: Int, a: String, b: String, paint: TextPaint) {
            this.w0 = w0
            this.w1 = w1
            // (Where a split is unsafe, 0: both whole, cross-fading; see NotifRows.sharedPrefix.)
            val n = NotifRows.sharedPrefix(a, b)
            prefix = a.substring(0, n)
            end0 = a.substring(n)
            end1 = b.substring(n)
            dx = paint.measureText(prefix)
            width0 = paint.measureText(a)
        }
    }

    /** A string cut to a width, kept while neither changes. */
    private class Cut {
        var w = -1
        var src: String? = null
        var out = ""
    }

    private fun cut(c: Cut, s: String, p: TextPaint, w: Int): String {
        if (c.w == w && c.src === s) return c.out
        c.w = w
        c.src = s
        c.out = ellipsize(s, p, w.toFloat())
        return c.out
    }

    private fun actionsFor(e: ItemText): List<Notification.Action> = e.actions ?: actionsOf(e.item).also { acts ->
        e.actions = acts
        e.words = Array(acts.size) { acts[it].title.toString() }
        e.wordCuts = Array(acts.size) { Cut() }
    }

    private val itemTexts = HashMap<String, ItemText>()

    private fun itemText(item: Notifs.Item): ItemText {
        val w = textWidth().toInt().coerceAtLeast(1)
        val age = (System.currentTimeMillis() - item.postTime) / 60_000L
        val app = AppInfo.label(ctx, item.pkg)
        val old = itemTexts[item.key]
        if (old != null && old.item === item && old.width == w && old.age == age && old.app == app) return old
        val time = if (showsWhen(item)) timeLabel(item.postTime) else null
        val e = ItemText(
            item, w, age, app, time, if (time != null) "$app • $time" else app, if (time != null) " • $time" else "",
            (item.title ?: app).toString(), item.text?.toString()?.replace('\n', ' ')?.takeIf { it.isNotBlank() },
        )
        itemTexts[item.key] = e
        return e
    }

    private val noLines = emptyArray<String>()

    private fun fullLines(e: ItemText): Array<String> {
        e.full?.let { return it }
        val t = fullText(e.item)
        val out = if (t == null) noLines else {
            Design.text(QsTokens.CARD_BODY).applyTo(measure, u)
            val layout = textLayout(t, measure, e.width.toFloat(), MAX_LINES)
            Array(layout.lineCount) { i ->
                if (i == layout.lineCount - 1 && layout.getEllipsisCount(i) > 0)
                    ellipsize(t.substring(layout.getLineStart(i)).replace('\n', ' '), measure, e.width.toFloat())
                else t.substring(layout.getLineStart(i), layout.getLineEnd(i)).trimEnd('\n')
            }
        }
        e.full = out
        return out
    }

    /** A closed group's one line of a notification: its title, then its text from [dx] (null when the title fills the line). */
    private class GroupLine(val name: String?, val text: String?, val dx: Float)

    /** The lines a closed group lists, for the notifications [items] and a text column [width] wide. */
    private class GroupLines(val items: Array<Notifs.Item>, val width: Int, val lines: Array<GroupLine>)

    private val groupCache = HashMap<String, GroupLines>()

    /**
     * [card]'s [n] latest lines as a closed group draws them (Android's hybrid view: the text after the title and
     * [QsTokens.GROUP_LINE_GAP], each cut with an ellipsis at the card's padding; the text is left out when the title alone
     * fills the line).
     */
    private fun groupLines(card: Card, n: Int): GroupLines {
        val w = textWidth().toInt().coerceAtLeast(1)
        val old = groupCache[card.key]
        if (old != null && old.width == w && old.items.size == n) {
            var same = true
            for (i in 0 until n) if (old.items[i] !== card.items[i]) { same = false; break }
            if (same) return old
        }
        val gap = Design.pt(QsTokens.GROUP_LINE_GAP, u)
        val avail = w.toFloat()
        Design.text(QsTokens.CARD_TITLE).applyTo(title, u)
        Design.text(QsTokens.CARD_BODY).applyTo(body, u)
        val lines = Array(n) { i ->
            val it = card.items[i]
            val name = it.title?.toString()?.replace('\n', ' ')?.takeIf { s -> s.isNotBlank() }
            val text = it.text?.toString()?.replace('\n', ' ')?.takeIf { s -> s.isNotBlank() }
            var x = 0f
            var shownName: String? = null
            var cut = false
            if (name != null) {
                val shown = ellipsize(name, title, avail - x)
                shownName = shown
                if (shown != name) cut = true else x += title.measureText(shown) + if (text != null) gap else 0f
            }
            GroupLine(shownName, if (!cut && text != null && x < avail) ellipsize(text, body, avail - x) else null, x)
        }
        return GroupLines(Array(n) { card.items[it] }, w, lines).also { groupCache[card.key] = it }
    }

    /** An expanded card: its app's line, its title, its whole text, and a row of its actions. */
    private fun expandedHeight(r: Row): Float {
        val item = r.item ?: return 0f
        val pad = Design.pt(QsTokens.CARD_PADDING, u)
        val e = itemText(item)
        var h = pad + (if (NotifRows.expandedHead(r.kind, e.time != null) != Head.NONE) line(QsTokens.CARD_APP) + Design.pt(QsTokens.TITLE_GAP, u) else 0f) +
            line(QsTokens.CARD_TITLE) + Design.pt(QsTokens.TEXT_GAP, u)
        h += fullLines(e).size * line(QsTokens.CARD_BODY)
        h += if (actionsFor(e).isNotEmpty() && !panel.host.locked) Design.pt(QsTokens.ACTIONS_HEIGHT, u) + Design.pt(QsTokens.ACTIONS_BOTTOM, u) else pad
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

    /** Row [row]'s rectangle on screen now (its motion, its height's, the scroll, the scrim) into [out]. */
    private fun place(row: Row, a: Anim, out: RectF) {
        val top = listTop + a.y.value - scroller.position
        val dx = swipeOf(row, a)
        out.set(cardsLeft() + dx, top, cardsLeft() + cardsWidth() + dx, top + a.h.value.coerceAtLeast(0f))
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
        // (Rows that fade away lie under the others; a child folding into its group's line lies over the group.)
        // (Only while something is leaving: no iterator is made at a frame of a list at rest. The leavers are gathered once.)
        leaverCount = 0
        if (anyLeaving) {
            val going = anims.values.iterator()
            while (going.hasNext()) {
                val a = going.next()
                if (a.leaving == null) continue
                if (!a.busy) { going.remove(); continue }
                if (leaverCount == leavers.size) leavers += a else leavers[leaverCount] = a
                leaverCount++
            }
            anyLeaving = leaverCount > 0
        }
        curListAlpha = listAlpha
        gatherUnions()
        for (i in 0 until leaverCount) { val a = leavers[i]; val old = a.leaving; if (old != null && !a.folding) drawRow(c, old, a, listAlpha) }
        for (row in rows) drawRow(c, row, anims[row.key] ?: continue, listAlpha)
        for (i in 0 until leaverCount) { val a = leavers[i]; val old = a.leaving; if (old != null && a.folding) drawRow(c, old, a, listAlpha) }
        for (row in rows) if (row.kind == RowKind.GROUP || row.kind == RowKind.HEADER) drawRow(c, row, anims[row.key] ?: continue, listAlpha, iconOnly = true)
        c.restoreToCount(save)
    }

    /** Rows on their way out, gathered once for a frame (pooled). */
    private val leavers = ArrayList<Anim>()
    private var leaverCount = 0
    private var curListAlpha = 1f

    /**
     * An app's group is drawn as one surface, closed or opened or between: from its first row's top to the lowest bottom of
     * its rows (leaving ones too), with the card's own outer corners, so that rows opening and closing never make a seam, a
     * rim or a corner that turns in one frame. A child swiped by itself is cut out of it as Android's own does: it slides out
     * as a card of its own (big corners all round), the rows above and below it are a card each with big corners where it
     * was, and the slot shows what is behind; each corner turns with the child's [Anim.split]. (A group that falls to one
     * item stays a group while the panel is open, as Android's: [NotifRows.staysGroup].) Pooled.
     */
    private class Union {
        var key = ""
        var base: Row? = null
        var baseAnim: Anim? = null
        var dx = 0f
        var top = 0f
        var bottom = 0f
        /** The strongest presence of its rows (a fading header does not fade a card that stays). */
        var alpha = 0f
        var drawn = false
        /** The children cut out of it, in order from the top: where they are and how split. */
        var cuts = 0
        val cutTop = FloatArray(MAX_CUTS)
        val cutBottom = FloatArray(MAX_CUTS)
        val cutSplit = FloatArray(MAX_CUTS)
        /** Whether each is leaving, and from which end of the card (where its cut's corners come to rest). */
        val cutLeaving = BooleanArray(MAX_CUTS)
        val cutEndTop = BooleanArray(MAX_CUTS)
        val cutEndBottom = BooleanArray(MAX_CUTS)
        val pieces = FloatArray(4 * (MAX_CUTS + 1))
    }

    private val unions = ArrayList<Union>()
    private var unionCount = 0
    private val unionRadii = FloatArray(4)

    private fun unionFor(key: String?): Union? {
        if (key == null) return null
        for (i in 0 until unionCount) { val un = unions[i]; if (un.key == key) return un }
        return null
    }

    private fun addUnion(key: String, base: Row, a: Anim) {
        if (unionCount == unions.size) unions += Union()
        val un = unions[unionCount++]
        un.key = key
        un.base = base
        un.baseAnim = a
        un.dx = a.swipe.value
        un.top = a.y.value
        un.bottom = a.y.value + max(a.h.value, 0f)
        un.alpha = a.alpha.value.coerceIn(0f, 1f)
        un.drawn = false
        un.cuts = 0
    }

    private fun isBase(k: RowKind) = k == RowKind.GROUP || k == RowKind.HEADER

    /** The group whose surface [row] is part of: its card's (a plain card has none). */
    private fun unionKeyOf(row: Row): String? = if (row.kind == RowKind.GROUP || row.kind == RowKind.HEADER || row.kind == RowKind.CHILD) row.m.cardKey else null

    private fun gatherUnions() {
        // (Nothing of the last frame stays referenced.)
        for (i in unions.indices) { unions[i].base = null; unions[i].baseAnim = null }
        unionCount = 0
        for (i in rows.indices) {
            val row = rows[i]
            if (isBase(row.kind)) addUnion(row.m.cardKey ?: continue, row, anims[row.key] ?: continue)
        }
        for (i in 0 until leaverCount) {
            val a = leavers[i]
            val old = a.leaving ?: continue
            if (isBase(old.kind)) addUnion(old.m.cardKey ?: continue, old, a)
        }
        for (i in rows.indices) {
            val row = rows[i]
            if (row.kind == RowKind.CHILD) join(row, anims[row.key] ?: continue)
        }
        for (i in 0 until leaverCount) {
            val a = leavers[i]
            val old = a.leaving ?: continue
            if (old.kind == RowKind.CHILD) join(old, a)
        }
    }

    /** Whether [a] (a child of [un]'s group) is cut out of it: swiped by itself, or still turning back into a row. */
    private fun isCut(un: Union, a: Anim) = NotifRows.apart(a.swipe.value, un.dx, a.withHeader) || a.split.value > 0.003f

    private fun join(row: Row, a: Anim) {
        val un = unionFor(unionKeyOf(row)) ?: return
        un.bottom = max(un.bottom, a.y.value + max(a.h.value, 0f))
        // (Its split follows where its swipe is heading: it turns back as a return begins, not when it has settled; one
        // that is leaving has its own, set as it left.)
        if (a.leaving == null) {
            val want = if (NotifRows.apart(a.swipe.target, un.dx, a.withHeader)) 1f else 0f
            if (a.split.target != want) a.split.animateTo(want, Motion.role(MotionTokens.NC_REFLOW))
        }
        if (!isCut(un, a)) { un.alpha = max(un.alpha, a.alpha.value.coerceIn(0f, 1f)); return }
        if (un.cuts == MAX_CUTS) return
        // (In order from the top.)
        var i = un.cuts++
        val t = a.y.value
        while (i > 0 && un.cutTop[i - 1] > t) {
            un.cutTop[i] = un.cutTop[i - 1]; un.cutBottom[i] = un.cutBottom[i - 1]; un.cutSplit[i] = un.cutSplit[i - 1]
            un.cutLeaving[i] = un.cutLeaving[i - 1]; un.cutEndTop[i] = un.cutEndTop[i - 1]; un.cutEndBottom[i] = un.cutEndBottom[i - 1]
            i--
        }
        un.cutLeaving[i] = a.leaving != null
        un.cutEndTop[i] = a.endTop
        un.cutEndBottom[i] = a.endBottom
        un.cutTop[i] = t
        un.cutBottom[i] = t + max(a.h.value, 0f)
        un.cutSplit[i] = a.split.value.coerceIn(0f, 1f)
    }

    private fun swipeFadeOf(dx: Float) = 1f - (abs(dx) / (panel.panelWidth * 0.6f)).coerceIn(0f, 1f)

    /** The group's surface, once for a frame (by the first of its rows to draw), under all of their contents. */
    private fun drawUnion(c: Canvas, un: Union) {
        val base = un.base ?: return
        val left = cardsLeft() + un.dx
        val top = listTop + un.top - scroller.position
        val big = Design.pt(QsTokens.CARD_CORNER, u)
        val joined = Design.pt(QsTokens.JOINED_CORNER, u)
        val outerTop = cornerPx(base.m.outerTop, big, joined)
        val outerBottom = cornerPx(base.m.outerBottom, big, joined)
        val alpha = curListAlpha * un.alpha * swipeFadeOf(un.dx)
        val n = NotifRows.pieces(un.top, max(un.bottom, un.top), un.cutTop, un.cutBottom, un.cuts, un.pieces)
        for (k in 0 until n) {
            val pt = un.pieces[k * 4]
            val pb = un.pieces[k * 4 + 1]
            val above = un.pieces[k * 4 + 2].toInt()
            val below = un.pieces[k * 4 + 3].toInt()
            val cap = (pb - pt) / 2f
            // (The card's own corner at the group's ends; at a cut the big one, turning with the cut's split.)
            // (At a cut the corner turns from where it rests to the big one with the cut's split. It rests square, the rows meeting,
            // except where the cut is a child leaving from that end of its card: then the card's own corner, so that the card
            // is as it was when the child is gone. A cut that is a drag or a return always starts square.)
            val ct = minOf(if (above < 0) outerTop else NotifRows.cutCorner(
                NotifRows.cutRest(un.cutLeaving[above], un.cutEndTop[above], outerTop), big, un.cutSplit[above]), cap)
            val cb = minOf(if (below < 0) outerBottom else NotifRows.cutCorner(
                NotifRows.cutRest(un.cutLeaving[below], un.cutEndBottom[below], outerBottom), big, un.cutSplit[below]), cap)
            unionRadii[0] = ct; unionRadii[1] = ct; unionRadii[2] = cb; unionRadii[3] = cb
            c.save()
            c.translate(left, top + (pt - un.top))
            panel.surfaces.draw(c, Design.material(QsTokens.CARD), cardsWidth(), pb - pt, big, left, top + (pt - un.top), 1f, alpha, radii = unionRadii)
            c.restore()
        }
    }

    /** [iconOnly]: only a group header's icon, drawn after the rows that lie over it. */
    private fun drawRow(c: Canvas, row: Row, a: Anim, listAlpha: Float, iconOnly: Boolean = false) {
        place(row, a, r)
        if (r.bottom < 0f || r.top > panel.panelHeight) return
        // A swiped card fades as it goes.
        val swipeFade = swipeFadeOf(swipeOf(row, a))
        val f = listAlpha * a.alpha.value.coerceIn(0f, 1f) * swipeFade
        if (f <= 0.003f) return
        when (row.kind) {
            RowKind.HEADING -> drawHeading(c, r, f)
            RowKind.FOOTER -> drawFooter(c, r, f)
            else -> if (iconOnly) drawHeaderIcon(c, row, r, f) else drawCard(c, row, a, r, f)
        }
    }

    /**
     * The app's own icon in a circle (Android 16's notifications show it, not the small symbol), except a system app's (Play
     * Protect's shield, measured on the emulator's own): its small symbol on a circle of the notification's colour; without
     * an icon, the small symbol on the palette's colour. (A group's child has none: its text starts where a card's does,
     * under the header's icon.)
     */
    private fun drawAppIcon(c: Canvas, show: Boolean, item: Notifs.Item, r: RectF, pad: Float, icon: Float, alpha: Float) {
        val app = if (item.pkg in systemApps) null else appIcon(item.pkg)
        if (show && app != null) {
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
        } else if (show) {
            val own = if (item.pkg in systemApps && item.color != 0) item.color or (0xFF shl 24) else 0
            fill.color = faded(if (own != 0) own else Design.color(QsTokens.ICON_FILL), alpha)
            c.drawCircle(r.left + pad + icon / 2f, r.top + pad + icon / 2f, icon / 2f, fill)
        }
        if (show && app == null) smallIcon(item)?.let { d ->
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
    }

    private fun drawHeaderIcon(c: Canvas, row: Row, r: RectF, alpha: Float) {
        val item = row.item ?: return
        c.save()
        c.clipRect(r)
        drawAppIcon(c, row.m.icon, item, r, Design.pt(QsTokens.CARD_PADDING, u), Design.pt(QsTokens.CARD_ICON, u), alpha)
        c.restore()
    }

    private fun cornerPx(k: Corner, big: Float, joined: Float) = when (k) { Corner.BIG -> big; Corner.JOINED -> joined; Corner.NONE -> 0f }

    private fun drawCard(c: Canvas, row: Row, a: Anim, r: RectF, alpha: Float) {
        val item = row.item ?: return
        val big = Design.pt(QsTokens.CARD_CORNER, u)
        val joined = Design.pt(QsTokens.JOINED_CORNER, u)
        // (No corner is more than half the row's height: a child that is a line of a group is a few dp tall.)
        val cap = r.height() / 2f
        // (A group's rows share one surface, drawn by the first of them: [drawUnion]. A child swiped by itself slides out
        // on a sheet of its own over its slot: the sheet comes in with the swipe and its corners go from the card's to a
        // small one with the distance, so that none changes in a frame.)
        val un = unionFor(unionKeyOf(row))
        if (un != null && !un.drawn) { un.drawn = true; drawUnion(c, un) }
        val sheet = un != null && row.kind == RowKind.CHILD && isCut(un, a)
        val top = minOf(cornerPx(row.m.top, big, joined), cap)
        val bottom = minOf(cornerPx(row.m.bottom, big, joined), cap)
        radii[0] = top; radii[1] = top; radii[2] = bottom; radii[3] = bottom
        val fold = a.fold.value.coerceIn(0f, 1f)
        val gl = if (fold < 0.999f && a.line != NotifRows.BELOW) lineOf(row, a) else null
        val fo = if (gl != null) fold else 1f
        c.save()
        c.translate(r.left, r.top)
        if (un == null) panel.surfaces.draw(c, Design.material(QsTokens.CARD), r.width(), r.height(), big, r.left, r.top, 1f, alpha, radii = radii)
        else if (sheet) {
            // A card of its own: its corners go to the big one all round as it splits from the group.
            val t = a.split.value.coerceIn(0f, 1f)
            val st = minOf(top + (big - top) * t, cap)
            val sb = minOf(bottom + (big - bottom) * t, cap)
            radii[0] = st; radii[1] = st; radii[2] = sb; radii[3] = sb
            panel.surfaces.draw(c, Design.material(QsTokens.CARD), r.width(), r.height(), big, r.left, r.top, 1f, alpha, radii = radii)
        }
        // (Pressed: the platform's ripple, as a notification's background has, in the theme's highlight colour.)
        if (a.ripple.active) a.ripple.draw(c, r.width(), r.height(), big, Design.color(QsTokens.CARD_RIPPLE), alpha, u, share = 1f, radii = radii)
        c.restore()
        // The content lies within the row's rectangle as it is now (a rect clip: analytic): a row growing or folding shows
        // no more of it than it has room for.
        c.save()
        c.clipRect(r)
        val pad = Design.pt(QsTokens.CARD_PADDING, u)
        val icon = Design.pt(QsTokens.CARD_ICON, u)
        // An opened group's child: a hairline across the card's top (coming in as it unfolds), its content under it.
        val inset = hairlinePx * a.div.value.coerceIn(0f, 1f) * fo
        if (inset > 0f) {
            fill.color = faded(Design.color(QsTokens.DIVIDER), alpha)
            c.drawRect(r.left, r.top, r.right, r.top + inset, fill)
        }
        // (A group's header has its icon drawn last: the children unfolding from its lines lie over it.)
        // (A child that becomes a card fades its icon in, and a card that becomes a child fades it out.)
        val iconK = a.icon.value.coerceIn(0f, 1f)
        if ((row.kind != RowKind.GROUP && row.kind != RowKind.HEADER) || a.leaving != null) drawAppIcon(c, iconK > 0.003f, item, r, pad, icon, alpha * iconK)
        val e = itemText(item)
        // How open it is now: its two contents are drawn in this proportion (the first and last frames of a fold show one).
        val k = a.k.value.coerceIn(0f, 1f)
        val tx = r.left + textX
        // (Its chevron comes in as the child grows out of the line, which has none.)
        val chipW = drawChip(c, row, r, pad, alpha * range(0.35f, 1f, fo), inset, k)
        val maxW = r.right - pad - chipW - tx
        if (row.kind == RowKind.GROUP || row.kind == RowKind.HEADER) {
            drawGroup(c, row, e, r, tx, pad, icon, inset, maxW, alpha, k)
        } else {
            drawCardText(c, row, e, r, tx, pad, inset, maxW, alpha, k, fo, gl)
        }
        c.restore()
    }

    /** The line of its closed group that [row] (a child) unfolds from or folds into, as the group last drew it; null if that is not known. */
    private fun lineOf(row: Row, a: Anim): GroupLine? {
        val g = groupCache[row.m.cardKey ?: return null] ?: return null
        return if (a.line in g.lines.indices && g.items[a.line].key == row.key) g.lines[a.line] else null
    }

    /**
     * A card's (or a child's) text as it goes between collapsed and expanded, [k] of the way: what both states show is drawn
     * once, at a place between theirs (the title, the text's first line; where their strings differ only their endings
     * cross-fade), and what only one shows fades (the " • time" after the collapsed title; the app's or the time's line, the
     * rest of the text and the actions of the expanded card).
     */
    private fun drawCardText(c: Canvas, row: Row, e: ItemText, r: RectF, tx: Float, pad: Float, inset: Float, maxW: Float, alpha: Float, k: Float, fo: Float, gl: GroupLine?) {
        val head = NotifRows.expandedHead(row.kind, e.time != null)
        val al = line(QsTokens.CARD_APP)
        val tl = line(QsTokens.CARD_TITLE)
        val bl = line(QsTokens.CARD_BODY)
        val titleGap = if (head != Head.NONE) al + Design.pt(QsTokens.TITLE_GAP, u) else 0f
        val textGap = Design.pt(QsTokens.TEXT_GAP, u)
        val top = r.top + inset + pad
        Design.text(QsTokens.CARD_APP).applyTo(small, u)
        Design.text(QsTokens.CARD_TITLE).applyTo(title, u)
        Design.text(QsTokens.CARD_BODY).applyTo(body, u)
        val titleColor = Design.color(QsTokens.CARD_TITLE_COLOR)
        val textColor = Design.color(QsTokens.CARD_TEXT_COLOR)
        // Still a line of its group (a child unfolding from it, or folding back): its title and text lie where the line's
        // did, and glide to their places in the card as it grows ([fo]); what the line did not show comes in with it.
        val lineBase = if (gl != null) baselineIn(title, r.top, Design.pt(QsTokens.GROUP_LINE, u)) else 0f
        val titleAlpha = if (gl != null && gl.name == null) alpha * fo else alpha
        // The first line of an expanded card: the app and the time, or a child's time alone. It comes in once the title has
        // moved clear of its place.
        val headAlpha = alpha * range(0.4f, 1f, k)
        if (head != Head.NONE && headAlpha > 0.003f) {
            small.color = faded(textColor, headAlpha)
            val label = if (head == Head.TIME) e.time.orEmpty() else e.appLine
            c.drawText(cut(e.headCut, label, small, maxW.toInt().coerceAtLeast(1)), tx, baselineIn(small, top, al), small)
        }
        // The title: one draw, moving down by what the expanded card puts above it; cut for the collapsed width (its " •
        // time" after it) and for the expanded one (the chip beside it only with no line above).
        val wc = (if (e.time == null) maxW else maxW - small.measureText(e.tail)).toInt().coerceAtLeast(1)
        val we = (if (head != Head.NONE) r.right - pad - tx else maxW).toInt().coerceAtLeast(1)
        val tg = e.titleGlide
        if (tg.w0 != wc || tg.w1 != we) tg.set(wc, we, ellipsize(e.name, title, wc.toFloat()), ellipsize(e.name, title, we.toFloat()), title)
        val cardTitleBase = baselineIn(title, top + k * titleGap, tl)
        val titleBase = if (gl != null) lineBase + fo * (cardTitleBase - lineBase) else cardTitleBase
        var tailX = tx + tg.width0
        title.color = faded(titleColor, titleAlpha)
        if (gl != null) {
            // On its line: the title is cut to the room it has, from the line's (the card's width) to the card's own as it
            // unfolds, so that at fold 0 it is the string the line draws and at fold 1 the one the card does.
            val room = e.width + (wc + (we - wc) * k - e.width) * fo
            tailX = drawCut(c, e.name, tx, titleBase, title, room)
        } else {
            c.drawText(tg.prefix, tx, titleBase, title)
            if (k < 0.997f && tg.end0.isNotEmpty()) {
                title.color = faded(titleColor, titleAlpha * (1f - k))
                c.drawText(tg.end0, tx + tg.dx, titleBase, title)
            }
            if (k > 0.003f && tg.end1.isNotEmpty()) {
                title.color = faded(titleColor, titleAlpha * k)
                c.drawText(tg.end1, tx + tg.dx, titleBase, title)
            }
        }
        val tailAlpha = alpha * (1f - range(0f, 0.5f, k)) * range(0.4f, 1f, fo)
        if (e.time != null && tailAlpha > 0.003f) {
            small.color = faded(textColor, tailAlpha)
            c.drawText(e.tail, tailX, titleBase, small)
        }
        // The text: its first line is the collapsed card's one line, moving down to the expanded card's first.
        val lines = fullLines(e)
        val xg = e.textGlide
        if (xg.w0 < 0) xg.set(e.width, e.width, ellipsize(e.oneLine.orEmpty(), body, e.width.toFloat()), if (lines.isEmpty()) "" else lines[0], body)
        val textTop = top + tl + k * (titleGap + textGap)
        val cardTextBase = baselineIn(body, textTop, bl)
        // (On its line the text follows the title on the same baseline; a line whose title filled it had none.)
        val onLine = gl != null && gl.text != null
        // (First it drops to the line under the title, then slides back under it: a straight glide crosses the title. By
        // construction: the drop is whole (the card's own offset of its text from its title, where the line boxes do not
        // overlap) by fo 0.5, where the slide starts, and until then the text stays right of the title's end and its gap.)
        val textBase = if (onLine) titleBase + (cardTextBase - cardTitleBase) * range(0f, 0.5f, fo) else cardTextBase
        val textLeft = if (onLine) tx + (1f - range(0.5f, 1f, fo)) * gl!!.dx else tx
        val textAlpha = if (gl != null && !onLine) alpha * range(0.4f, 1f, fo) else alpha
        if (gl != null) {
            // On its line: cut to what is left of the card's text room from where it is, never past it (at fold 0 the line's
            // own string, at fold 1 the card's).
            val room = e.width - (textLeft - tx)
            body.color = faded(textColor, textAlpha * (1f - k))
            e.oneLine?.let { drawCut(c, it, textLeft, textBase, body, room) }
            if (k > 0.003f && lines.isNotEmpty()) {
                body.color = faded(textColor, textAlpha * k)
                drawCut(c, lines[0], textLeft, textBase, body, room)
            }
        } else {
            body.color = faded(textColor, textAlpha)
            c.drawText(xg.prefix, textLeft, textBase, body)
            if (k < 0.997f && xg.end0.isNotEmpty()) {
                body.color = faded(textColor, textAlpha * (1f - k))
                c.drawText(xg.end0, textLeft + xg.dx, textBase, body)
            }
            if (k > 0.003f && xg.end1.isNotEmpty()) {
                body.color = faded(textColor, textAlpha * k)
                c.drawText(xg.end1, textLeft + xg.dx, textBase, body)
            }
        }
        if (k <= 0.003f) return
        // The rest of the expanded card travels with the first line (its place now), each line under the one before.
        val ek = alpha * k
        body.color = faded(textColor, ek)
        var y = textTop + bl
        for (i in 1 until lines.size) {
            c.drawText(lines[i], tx, baselineIn(body, y, bl), body)
            y += bl
        }
        if (lines.isEmpty()) y = textTop
        if (actionsFor(e).isNotEmpty() && !panel.host.locked) drawActions(c, e, row.key, r, y, ek, k >= 0.5f)
    }

    /**
     * A group, closed or opened: the app's line (an opened group's, with its icon, in the middle of the header; the line
     * settles there as the group opens), and while closed a line of each of its latest notifications, fading out as it opens.
     */
    private fun drawGroup(c: Canvas, row: Row, e: ItemText, r: RectF, tx: Float, pad: Float, icon: Float, inset: Float, maxW: Float, alpha: Float, k: Float) {
        Design.text(QsTokens.CARD_APP).applyTo(small, u)
        small.color = faded(Design.color(QsTokens.CARD_TEXT_COLOR), alpha)
        val al = line(QsTokens.CARD_APP)
        val lineTop = r.top + inset + pad + k * (icon - al) / 2f
        c.drawText(cut(e.appCut, e.appLine, small, maxW.toInt().coerceAtLeast(1)), tx, baselineIn(small, lineTop, al), small)
        if (k >= 0.997f) return
        val card = row.card ?: return
        // (Opening, the lines it listed are still the ones it has: a header takes them from what the group last drew.)
        val g = (if (row.kind == RowKind.GROUP) groupLines(card, minOf(row.m.lines, card.items.size)) else groupCache[card.key]) ?: return
        val a = alpha * (1f - k)
        Design.text(QsTokens.CARD_TITLE).applyTo(title, u)
        title.color = faded(Design.color(QsTokens.CARD_TITLE_COLOR), a)
        Design.text(QsTokens.CARD_BODY).applyTo(body, u)
        body.color = faded(Design.color(QsTokens.CARD_TEXT_COLOR), a)
        val lineH = Design.pt(QsTokens.GROUP_LINE, u)
        val first = r.top + Design.pt(QsTokens.GROUP_LINES_TOP, u)
        for (i in g.lines.indices) {
            // (A line whose child is unfolding from it or folding into it is drawn by the child, until it is the line again.)
            val own = anims[g.items[i].key]
            if (own != null && own.line == i && own.fold.value > 0.001f) continue
            val l = g.lines[i]
            val base = baselineIn(title, first + i * lineH, lineH)
            l.name?.let { c.drawText(it, tx, base, title) }
            l.text?.let { c.drawText(it, tx + l.dx, base, body) }
        }
    }

    /** Whether [item] shows its time (Android's showWhen). */
    private fun showsWhen(item: Notifs.Item): Boolean =
        try { item.sbn.notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN, true) } catch (_: Throwable) { true }

    /** A group's count and chevron, or a card's chevron, at the top right; returns the room it takes. */
    private fun drawChip(c: Canvas, row: Row, r: RectF, pad: Float, a: Float, inset: Float, k: Float): Float {
        val h = Design.pt(QsTokens.CHIP_HEIGHT, u)
        val cw = Design.pt(QsTokens.CHIP_WIDTH, u)
        Design.text(QsTokens.CHIP_TEXT).applyTo(small, u)
        val label = row.countLabel
        val w = (if (label.isEmpty()) 0f else small.measureText(label) + 4f * u) + cw
        // (A group's chip settles in the middle of its header's icon as the group opens, as its one line does.)
        val icon = Design.pt(QsTokens.CARD_ICON, u)
        val chipTop = r.top + inset + pad + if (row.kind == RowKind.GROUP || row.kind == RowKind.HEADER) k * (icon - h) / 2f else 0f
        val chip = chips.getOrPut(row.key) { RectF() }
        chip.set(r.right - pad - w, chipTop, r.right - pad, chipTop + h)
        fill.color = faded(Design.color(QsTokens.CHIP_FILL), a)
        c.drawRoundRect(chip, h / 2f, h / 2f, fill)
        val col = faded(Design.color(QsTokens.CARD_TITLE_COLOR), a)
        if (label.isNotEmpty()) {
            small.color = col
            small.textAlign = Paint.Align.LEFT
            c.drawText(label, chip.left + 8f * u, baselineIn(small, chip.top, h), small)
        }
        // Down while closed, up while open, turning with the row's opening.
        val cx = chip.right - cw / 2f
        if (row.m.chevron != Chevron.NONE) {
            c.save()
            c.rotate(90f - 180f * k, cx, chip.centerY())
            panel.glyphs.draw(c, R.drawable.sym_chevron, cx, chip.centerY(), 16f * u, col)
            c.restore()
        }
        return w + 8f * u
    }

    private val chips = HashMap<String, RectF>()
    /** An action's place to tap, in the row it belongs to (pooled: nothing is made while drawing). */
    private class Hit {
        var action: Notification.Action? = null
        val rect = RectF()
        var key = ""
    }

    private val hitPool = ArrayList<Hit>()
    private var hitCount = 0

    private fun nextHit(): Hit {
        if (hitCount == hitPool.size) hitPool += Hit()
        return hitPool[hitCount++]
    }

    /**
     * A card's actions: words in a row (Android 16's template), each with room around it to tap. One can be tapped only if
     * [hits] and it lies wholly within its row's rectangle as it is now (a row folding is clipped to it: an action below
     * its edge is not shown, and the row under it is what a tap there means).
     */
    private fun drawActions(c: Canvas, e: ItemText, key: String, r: RectF, top: Float, a: Float, hits: Boolean) {
        val acts = actionsFor(e)
        val words = e.words ?: return
        val cuts = e.wordCuts ?: return
        val h = Design.pt(QsTokens.ACTIONS_HEIGHT, u)
        val padX = Design.pt(QsTokens.ACTION_PADDING, u)
        val inset = Design.pt(QsTokens.ACTION_INSET, u)
        Design.text(QsTokens.ACTION_TEXT).applyTo(small, u)
        small.textAlign = Paint.Align.LEFT
        small.color = faded(Design.color(QsTokens.ACTION_COLOR), a)
        var x = r.left + inset
        val end = r.right - inset
        val reachable = hits && NotifRows.reachable(top, top + h, r.top, r.bottom)
        for (i in acts.indices) {
            val act = acts[i]
            val w = minOf(small.measureText(words[i]) + 2 * padX, end - x)
            if (w <= 2 * padX) break
            // (Cut to a whole pixel more than the room, so that words that just fit are not.)
            c.drawText(cut(cuts[i], words[i], small, (w - 2 * padX).toInt() + 1), x + padX, baselineIn(small, top, h), small)
            if (reachable) {
                val hit = nextHit()
                hit.action = act
                hit.key = key
                hit.rect.set(x, top, x + w, top + h)
            }
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

    /**
     * [s] cut to [w] with an ellipsis (as a line that does not fit is). Cut as [drawCut] cuts, so that a string made here and
     * one drawn there at the same room are the same.
     */
    private fun ellipsize(s: String, p: TextPaint, w: Float): String {
        val room = max(1f, w)
        if (p.breakText(s, true, room, null) >= s.length) return s
        return s.substring(0, cutAt(s, p, room)) + ELLIPSIS
    }

    /** How many characters of [s] stay before the ellipsis when it is cut to [room] (never inside a surrogate pair or before a mark). */
    private fun cutAt(s: String, p: TextPaint, room: Float): Int {
        var m = p.breakText(s, true, max(1f, room - p.measureText(ELLIPSIS)), null)
        while (m > 0 && (Character.isHighSurrogate(s[m - 1]) || s[m - 1] == '\u200D' || (m < s.length && isMark(s[m])))) m--
        return m
    }

    private fun isMark(ch: Char): Boolean = when (Character.getType(ch).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
        else -> ch == '\u200D' || ch == '\u200C' || ch in '\uFE00'..'\uFE0F'
    }

    /**
     * [s] drawn from [x] cut to [room] as [ellipsize] cuts it, made of nothing (a row that moves draws a different room at every
     * frame: its text is cut to what it has, never past it, and with no step from one cut to the next). Returns where it ends.
     */
    private fun drawCut(c: Canvas, s: String, x: Float, y: Float, p: TextPaint, room: Float): Float {
        if (room < 1f || s.isEmpty()) return x
        if (p.breakText(s, true, room, null) >= s.length) {
            c.drawText(s, x, y, p)
            return x + p.measureText(s)
        }
        // One run, the string [ellipsize] makes (the cut text and the ellipsis): drawn apart, the ellipsis of right-to-left text
        // would land beside its first word instead of at the cut end.
        val m = cutAt(s, p, room)
        if (cutBuf.size < m + 1) cutBuf = CharArray(max(m + 1, cutBuf.size * 2))
        s.toCharArray(cutBuf, 0, 0, m)
        cutBuf[m] = '\u2026'
        c.drawText(cutBuf, 0, m + 1, x, y, p)
        return x + p.measureText(cutBuf, 0, m + 1)
    }

    /** The cut string of [drawCut] (grown when a longer one comes; drawing is on one thread). */
    private var cutBuf = CharArray(128)

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
        // (An opened group's children fly with their header, each on its own, so whichever is cancelled last stays
        // where the flight put it.)
        if (row.kind == RowKind.HEADER) for (c in rows) if (c.kind == RowKind.CHILD && c.card?.key == row.key) {
            anims[c.key]?.let { it.withHeader = true; it.swipe.animateTo(target, Motion.role(MotionTokens.NC_SWIPE_OUT), vx) }
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
        // (An action of the row under the finger only: one of another row never lies under it.)
        for (i in 0 until hitCount) {
            val hit = hitPool[i]
            if (hit.key == row.key && hit.rect.contains(x, y)) { panel.host.send(hit.action?.actionIntent); return true }
        }
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
                for (c in along) { c.withHeader = true; c.swipe.animateTo(panel.panelWidth * 1.1f, Motion.role(MotionTokens.NC_SWIPE_OUT)) }
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
        hitCount = 0
    }

    fun beginFrame() { hitCount = 0 }

    private companion object {
        /** The most children cut out of one group's surface at once. */
        const val MAX_CUTS = 16
        val io = Executors.newSingleThreadExecutor()
        /** An expanded card's text at most this many lines (Android's big text in the shade). */
        const val MAX_LINES = 7
        const val ELLIPSIS = "\u2026"
    }
}
