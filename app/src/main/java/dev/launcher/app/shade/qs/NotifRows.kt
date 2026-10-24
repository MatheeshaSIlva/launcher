package dev.launcher.app.shade.qs

// The Pixel shade's notification list as rows (B-034, D-027): which rows a card or an app's group makes, what each shows, and
// what a chevron toggles. Pure: keys in, rows out (no android.* types), so it is tested on the JVM; CardsBlock keeps heights,
// drawing and motion.

internal enum class Section { ONGOING, ALERTING, SILENT }

/** A card; a group is a [GROUP] while closed, a [HEADER] and its [CHILD] rows while opened. */
internal enum class RowKind { CARD, GROUP, HEADER, CHILD, HEADING, FOOTER }

/** A corner of a row's surface: the card's own, the small one where cards meet, or none inside an opened group. */
internal enum class Corner { BIG, JOINED, NONE }

/** The space above a row: none (inside a card), between cards of a section, before a section, before the footer. */
internal enum class Gap { NONE, JOINED, SECTION, FOOTER }

internal enum class Chevron { NONE, UP, DOWN }

/** What a row's first line names: the app and the time, the time alone, or nothing (the title line carries " • time"). */
internal enum class Head { APP_TIME, TIME, NONE }

/**
 * A card of the list: a notification, or an app's group of them. [itemKeys]: its notifications, latest first. [untimed]:
 * those that show no time (their notification's showWhen is off).
 */
internal class CardIn(val key: String, val section: Section, val itemKeys: List<String>, val untimed: Set<String> = emptySet())

/**
 * One row of the list. [key] stays the same across rebuilds (a group's header takes its card's key, the same as the closed
 * group's row; a child its notification's key, which is also the key of the plain card a group shrinks to). [index]: into the
 * card's items. [lines]: how many one-line children a closed group lists. [count]: the children's total, on a group's chip.
 */
internal class RowModel(
    val key: String,
    val kind: RowKind,
    val section: Section,
    val cardKey: String?,
    val index: Int,
    val expanded: Boolean,
    val lines: Int,
    val count: Int,
    val icon: Boolean,
    val head: Head,
    val chevron: Chevron,
    val divider: Boolean,
) {
    // Worked out once the whole list is known (by [NotifRows.build]).
    var top = Corner.NONE
        internal set
    var bottom = Corner.NONE
        internal set
    var gapBefore = Gap.NONE
        internal set
}

/** What the user opened (keys): groups, cards opened by their chevron, children, and first cards closed by theirs. */
internal class ExpandState {
    val opened = HashSet<String>()
    val expandedChildren = HashSet<String>()
    val closedFirst = HashSet<String>()

    fun clear() {
        opened.clear()
        expandedChildren.clear()
        closedFirst.clear()
    }
}

internal object NotifRows {
    // Android's own (AOSP NotificationChildrenContainer): the lines a closed group lists (NUMBER_OF_CHILDREN_WHEN_COLLAPSED,
    // and the first notification's 5 of NUMBER_OF_CHILDREN_WHEN_SYSTEM_EXPANDED), the children an opened group shows
    // (NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED; the owner's answer, D-026).
    const val LINES_COLLAPSED = 2
    const val LINES_FIRST = 5
    const val MAX_CHILDREN = 8

    fun build(cards: List<CardIn>, state: ExpandState, footer: Boolean): List<RowModel> {
        val out = ArrayList<RowModel>()
        var firstCard = true
        for (s in Section.entries) {
            val cs = cards.filter { it.section == s }
            if (cs.isEmpty()) continue
            if (s == Section.SILENT) out += simple("heading:silent", RowKind.HEADING, s)
            val start = out.size
            for (c in cs) {
                val n = c.itemKeys.size
                val first = firstCard
                firstCard = false
                if (n <= 1) {
                    val open = (first && c.key !in state.closedFirst) || c.key in state.opened
                    out += row(c.key, RowKind.CARD, s, c.key, 0, open, 0, 0, true, if (open) Head.APP_TIME else Head.NONE,
                        if (open) Chevron.UP else Chevron.DOWN)
                } else if (c.key !in state.opened) {
                    out += row(c.key, RowKind.GROUP, s, c.key, 0, false, minOf(n, if (first) LINES_FIRST else LINES_COLLAPSED), n,
                        true, Head.APP_TIME, Chevron.DOWN)
                } else {
                    out += row(c.key, RowKind.HEADER, s, c.key, 0, true, 0, n, true, Head.APP_TIME, Chevron.UP)
                    for (i in 0 until minOf(n, MAX_CHILDREN)) {
                        val k = c.itemKeys[i]
                        val open = k in state.expandedChildren
                        // (Expanded, its first line is its time alone: with no time there is no line.)
                        out += row(k, RowKind.CHILD, s, c.key, i, open, 0, 0, false, if (open && k !in c.untimed) Head.TIME else Head.NONE,
                            if (open) Chevron.UP else Chevron.DOWN, divider = true)
                    }
                }
            }
            // The section's column: big corners at its ends, small where cards meet, none inside an opened group.
            for (i in start until out.size) {
                val r = out[i]
                r.top = if (r.kind == RowKind.CHILD) Corner.NONE else if (i == start) Corner.BIG else Corner.JOINED
                r.bottom = if (i + 1 < out.size && out[i + 1].kind == RowKind.CHILD) Corner.NONE
                    else if (i == out.size - 1) Corner.BIG else Corner.JOINED
            }
        }
        if (footer && cards.isNotEmpty()) out += simple("footer", RowKind.FOOTER, Section.SILENT)
        // The space above each: none inside a card (and above the first), joined between cards (also under a heading, whose
        // own height is the space), a section's before its heading or its first card, the footer's before the footer.
        for ((i, r) in out.withIndex()) {
            val prev = out.getOrNull(i - 1)
            r.gapBefore = when {
                prev == null || r.kind == RowKind.CHILD -> Gap.NONE
                r.kind == RowKind.FOOTER -> Gap.FOOTER
                r.kind == RowKind.HEADING || (prev.kind != RowKind.HEADING && prev.section != r.section) -> Gap.SECTION
                else -> Gap.JOINED
            }
        }
        return out
    }

    /** What a tap on [row]'s chevron (or a group's body) does to [state]. */
    fun toggle(row: RowModel, state: ExpandState) {
        val key = row.cardKey ?: return
        when (row.kind) {
            // A card opens to its whole text and actions, or closes (the first card, shown open, is closed by its own set).
            RowKind.CARD -> when {
                row.expanded && row.key !in state.opened -> state.closedFirst += row.key
                row.key in state.closedFirst -> state.closedFirst -= row.key
                else -> if (!state.opened.remove(row.key)) state.opened += row.key
            }
            RowKind.GROUP -> state.opened += key
            // A child that was expanded is still expanded when the group opens again (Android 17's own shade, step 0 of
            // B-034: the emulator's), so its state is not touched here.
            RowKind.HEADER -> state.opened -= key
            RowKind.CHILD -> if (!state.expandedChildren.remove(row.key)) state.expandedChildren += row.key
            RowKind.HEADING, RowKind.FOOTER -> {}
        }
    }

    /**
     * The rows that fly out when [gone] (the keys of the notifications being cleared) go, the bottom one first. A closed or
     * opened group goes as one card only when all of its notifications go (an opened group's header takes its children
     * along); otherwise it stays, and an opened group's cleared children fly one by one.
     */
    fun flying(rows: List<RowModel>, cards: List<CardIn>, gone: Set<String>): List<RowModel> {
        val items = cards.associate { it.key to it.itemKeys }
        fun whole(card: String?) = items[card]?.all { it in gone } == true
        val out = ArrayList<RowModel>()
        for (r in rows.asReversed()) {
            val fly = when (r.kind) {
                RowKind.HEADING, RowKind.FOOTER -> false
                RowKind.GROUP, RowKind.HEADER -> whole(r.cardKey)
                RowKind.CHILD -> !whole(r.cardKey) && r.key in gone
                RowKind.CARD -> r.key in gone
            }
            if (fly) out += r
        }
        return out
    }

    private fun simple(key: String, kind: RowKind, s: Section) =
        row(key, kind, s, null, 0, false, 0, 0, false, Head.NONE, Chevron.NONE)

    private fun row(
        key: String, kind: RowKind, s: Section, cardKey: String?, index: Int, expanded: Boolean, lines: Int, count: Int,
        icon: Boolean, head: Head, chevron: Chevron, divider: Boolean = false,
    ) = RowModel(key, kind, s, cardKey, index, expanded, lines, count, icon, head, chevron, divider)
}
