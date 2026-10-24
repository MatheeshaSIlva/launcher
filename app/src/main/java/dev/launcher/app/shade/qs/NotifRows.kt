package dev.launcher.app.shade.qs

import java.text.BreakIterator
import java.text.Bidi
import kotlin.math.abs

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
    // The corners of the whole card the row belongs to (an opened group is one surface: its header's top, its last child's
    // bottom); a row that is a card by itself, its own.
    var outerTop = Corner.NONE
        internal set
    var outerBottom = Corner.NONE
        internal set
}

/**
 * What the user opened (keys): groups, cards opened by their chevron, children, and the first card (a card or a group) closed
 * by the user (Android's hasUserChangedExpansion: it then lists what any other card does).
 */
internal class ExpandState {
    val opened = HashSet<String>()
    val expandedChildren = HashSet<String>()
    val closedFirst = HashSet<String>()
    /** The groups (keys) the list has had two or more of while the panel was open: they stay groups when one is left. */
    val groups = HashSet<String>()

    fun clear() {
        opened.clear()
        expandedChildren.clear()
        closedFirst.clear()
        groups.clear()
    }
}

internal object NotifRows {
    // Android's own (AOSP NotificationChildrenContainer): the lines a closed group lists (NUMBER_OF_CHILDREN_WHEN_COLLAPSED,
    // and the first notification's 5 of NUMBER_OF_CHILDREN_WHEN_SYSTEM_EXPANDED), the children an opened group shows
    // (NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED; the owner's answer, D-026). The 5 is the row's own expanded state, not its
    // place: getMaxAllowedVisibleChildren reads isExpanded() (the first row is system-expanded until the user changes its
    // expansion), so a first group the user opened and closed again lists 2 (read on the emulator's own shade, 2026-10-24,
    // B-034 slice 2). A silent group is no different (its notifications are not "minimized": that is importance MIN).
    const val LINES_COLLAPSED = 2
    const val LINES_FIRST = 5
    const val MAX_CHILDREN = 8

    /**
     * Whether a card of [count] notifications under [key] (an app's group key) is a group: two or more, or one that was a group
     * while the panel has been open (Android's own: the header and its last notification stay as one card, the count gone
     * from the chip, until the next opening).
     */
    fun staysGroup(key: String, count: Int, state: ExpandState): Boolean = count > 1 || key in state.groups

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
                if (n > 1) state.groups += c.key
                if (!staysGroup(c.key, n, state)) {
                    val open = (first && c.key !in state.closedFirst) || c.key in state.opened
                    out += row(c.key, RowKind.CARD, s, c.key, 0, open, 0, 0, true,
                        if (open) expandedHead(RowKind.CARD, true) else Head.NONE, if (open) Chevron.UP else Chevron.DOWN)
                } else if (c.key !in state.opened) {
                    out += row(c.key, RowKind.GROUP, s, c.key, 0, false, minOf(n, if (first && c.key !in state.closedFirst) LINES_FIRST else LINES_COLLAPSED), shown(n),
                        true, Head.APP_TIME, Chevron.DOWN)
                } else {
                    out += row(c.key, RowKind.HEADER, s, c.key, 0, true, 0, shown(n), true, Head.APP_TIME, Chevron.UP)
                    for (i in 0 until minOf(n, MAX_CHILDREN)) {
                        val k = c.itemKeys[i]
                        val open = k in state.expandedChildren
                        // (Expanded, its first line is its time alone: with no time there is no line.)
                        out += row(k, RowKind.CHILD, s, c.key, i, open, 0, 0, false,
                            if (open) expandedHead(RowKind.CHILD, k !in c.untimed) else Head.NONE,
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
        var i = 0
        while (i < out.size) {
            var j = i
            if (out[i].kind == RowKind.HEADER) while (j + 1 < out.size && out[j + 1].kind == RowKind.CHILD && out[j + 1].cardKey == out[i].cardKey) j++
            for (n in i..j) {
                out[n].outerTop = out[i].top
                out[n].outerBottom = out[j].bottom
            }
            i = j + 1
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

    /**
     * What the first line of a [kind] row's expanded content names, whether the row is expanded or on its way (the motion draws
     * the expanded content while a row folds): a card the app and the time, a child the time alone ([timed]; with no time
     * there is no line), anything else nothing.
     */
    fun expandedHead(kind: RowKind, timed: Boolean): Head = when (kind) {
        RowKind.CARD -> Head.APP_TIME
        RowKind.CHILD -> if (timed) Head.TIME else Head.NONE
        else -> Head.NONE
    }

    /**
     * Whether an action spanning [top]..[bottom] can be tapped in a row now spanning [rowTop]..[rowBottom]: only if it lies
     * wholly within it (half a pixel's slack). A row that is folding or growing clips its content to its rectangle, and an
     * action below its edge is not shown, and over the row beneath it, which a tap there is meant for.
     */
    fun reachable(top: Float, bottom: Float, rowTop: Float, rowBottom: Float): Boolean =
        top >= rowTop - 0.5f && bottom <= rowBottom + 0.5f

    /**
     * How many leading characters of [a] and [b] (one string cut two ways) can be drawn once, apart from their endings, which
     * are drawn at the prefix's width after it: the common start, drawn back to where both are cut cleanly. 0 (draw both
     * whole, cross-fading) where a split is unsafe: either has right-to-left text (an ending would land on the wrong side)
     * or a script whose letters join (a split loses their joined forms), and anywhere inside a grapheme (a surrogate pair,
     * a combining mark, a joiner or variation selector, a skin-tone modifier, half a flag, an Indic conjunct: a virama
     * before the letter it joins). Made when the strings change, never while drawing.
     */
    fun sharedPrefix(a: String, b: String): Int {
        var n = 0
        val m = minOf(a.length, b.length)
        while (n < m && a[n] == b[n]) n++
        if (n == 0 || needsBidi(a) || needsBidi(b) || joins(a) || joins(b)) return 0
        val ba = BreakIterator.getCharacterInstance().apply { setText(a) }
        val bb = BreakIterator.getCharacterInstance().apply { setText(b) }
        while (n > 0 && !(ba.isBoundary(n) && bb.isBoundary(n) && cleanSplit(a, b, n))) n--
        return n
    }

    private fun needsBidi(s: String) = Bidi.requiresBidi(s.toCharArray(), 0, s.length)

    // (The right-to-left joining scripts newer than API 29's, Adlam and Manichaean, are caught by needsBidi.)
    private fun joins(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.ARABIC, Character.UnicodeScript.SYRIAC, Character.UnicodeScript.NKO,
                Character.UnicodeScript.MONGOLIAN, Character.UnicodeScript.PHAGS_PA, Character.UnicodeScript.MANDAIC -> return true
                else -> {}
            }
            i += Character.charCount(cp)
        }
        return false
    }

    /** A letter's extenders: what must stay with the letter before it. */
    private fun extends(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
        else -> cp == 0x200D || cp == 0x200C || cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF ||
            cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F
    }

    private fun inScript(cp: Int, script: Character.UnicodeScript) = cp >= 0 && Character.isLetter(cp) && Character.UnicodeScript.of(cp) == script

    private fun regional(cp: Int) = cp in 0x1F1E6..0x1F1FF

    /** Whether cutting both at [n] (a boundary of each) is clean: not mid-pair, not before an extender, not after a joiner. */
    private fun cleanSplit(a: String, b: String, n: Int): Boolean {
        if (n >= a.length && n >= b.length) return true
        if (n > 0 && Character.isHighSurrogate(a[n - 1]) && (a.getOrNull(n)?.let { Character.isLowSurrogate(it) } == true ||
                b.getOrNull(n)?.let { Character.isLowSurrogate(it) } == true)) return false
        val nextA = if (n < a.length) a.codePointAt(n) else -1
        val nextB = if (n < b.length) b.codePointAt(n) else -1
        if ((nextA >= 0 && extends(nextA)) || (nextB >= 0 && extends(nextB))) return false
        if (n == 0) return true
        val prev = a.codePointBefore(n)
        if (prev == 0x200D) return false
        // A virama (a nonspacing mark of an Indic script: Java cannot tell it from a vowel sign, which is as well kept with the
        // next letter of its script) before a letter of its own script joins it into a conjunct.
        if (Character.getType(prev) == Character.NON_SPACING_MARK.toInt()) {
            val script = Character.UnicodeScript.of(prev)
            if (script != Character.UnicodeScript.INHERITED && script != Character.UnicodeScript.COMMON &&
                (inScript(nextA, script) || inScript(nextB, script))) return false
        }
        // Half a flag: a regional indicator that is the odd one of its run, with another to follow.
        if ((nextA >= 0 && regional(nextA)) || (nextB >= 0 && regional(nextB))) {
            var run = 0
            var i = n
            while (i > 0 && regional(a.codePointBefore(i))) { run++; i -= Character.charCount(a.codePointBefore(i)) }
            if (run % 2 == 1) return false
        }
        return true
    }

    /** [foldLine]'s answer for a child that has no line of the closed group to fold into. */
    const val BELOW = -1

    /**
     * Where a child of an opened group comes from when the group opens, and goes back to when it closes: the closed group's
     * line [index] of [lines] it listed (the notification's place among the group's latest), or [BELOW] for a child past them
     * (it comes from the closed card's bottom, and goes there).
     */
    fun foldLine(index: Int, lines: Int): Int = if (index in 0 until lines) index else BELOW

    /** The top of the closed group's line [line]: [headerTop] plus the space above the first line, then one [lineHeight] each. */
    fun foldTop(headerTop: Float, linesTop: Float, lineHeight: Float, line: Int): Float = headerTop + linesTop + line * lineHeight

    /**
     * Whether a child of an opened group is swiped by itself, so that it slides out of its card as a sheet of its own: it moves
     * ([own]) and neither is it flying with its header ([together]: launched with it) nor is the header ([header]) moving.
     * Children that fly with their header, even one that has overtaken it (an underdamped spring), share the group's surface.
     */
    fun apart(own: Float, header: Float, together: Boolean): Boolean = !together && abs(own) > 0.5f && abs(header) < 0.5f

    /**
     * The pieces an opened group's surface splits into around the children swiped by themselves (as Android's own: the rows above
     * one are a card, the rows below another, the child a third, and the slot between shows what is behind). [top]..[bottom]:
     * the group's extent; [cutTop]/[cutBottom] the first [n] cut children's, in order from the top. Writes each piece as four
     * numbers into [out]: its top and bottom, then the index of the cut above it and the one below it (-1: the group's own
     * end, with the card's own corner; any other: a cut, with the big corner, turning with that cut's split). Pieces under
     * half a pixel are left out. Returns how many pieces; [out] holds 4 * (n + 1).
     */
    fun pieces(top: Float, bottom: Float, cutTop: FloatArray, cutBottom: FloatArray, n: Int, out: FloatArray): Int {
        var cursor = top
        var above = -1
        var k = 0
        for (i in 0 until n) {
            if (cutTop[i] - cursor > 0.5f) {
                out[k * 4] = cursor; out[k * 4 + 1] = cutTop[i]; out[k * 4 + 2] = above.toFloat(); out[k * 4 + 3] = i.toFloat()
                k++
            }
            cursor = maxOf(cursor, cutBottom[i])
            above = i
        }
        if (bottom - cursor > 0.5f) {
            out[k * 4] = cursor; out[k * 4 + 1] = bottom; out[k * 4 + 2] = above.toFloat(); out[k * 4 + 3] = -1f
            k++
        }
        return k
    }

    /**
     * Where a child that closes up as it leaves ([index] among the old rows, its card's old rows [lo]..[hi]) closes to, so
     * that its slot always lies between its moving neighbours and the hole closes with them: the new top of the first row of
     * its card after it that stays, or if it was the card's last, the new bottom of the last one before it that stays.
     * [tops]/[bottoms]: each old row's place in the new layout, NaN for a row that is gone. NaN if none stays.
     */
    fun closeTarget(index: Int, lo: Int, hi: Int, tops: FloatArray, bottoms: FloatArray): Float {
        for (j in index + 1..hi) if (!tops[j].isNaN()) return tops[j]
        for (i in index - 1 downTo lo) if (!bottoms[i].isNaN()) return bottoms[i]
        return Float.NaN
    }

    /** The count a group's chip writes: none for a group of one (only its chevron stays). */
    fun shown(n: Int): Int = if (n > 1) n else 0

    /**
     * Which ends of its card a child that closes up as it leaves ([index] among the old rows, its card's old rows [lo]..[hi])
     * leaves: 1 if no row of the card before it stays (it is the top: the card's own top corner), 2 if none after it stays
     * (the bottom). [stays]: whether each old row is in the new layout. A child that leaves from the middle is 0.
     */
    fun endsAt(index: Int, lo: Int, hi: Int, stays: BooleanArray): Int {
        var before = false
        for (i in lo until index) if (stays[i]) before = true
        var after = false
        for (j in index + 1..hi) if (stays[j]) after = true
        return (if (before) 0 else 1) or (if (after) 0 else 2)
    }

    /**
     * Where the corner of the piece of a group's surface beside a cut starts when the cut is not split (0, the rows meet) :
     * the card's own corner [outer] only for a cut that is leaving from that end of the card ([atEnd]), so that the card is
     * as it was when the child is gone; a cut that is a drag or a return always starts square, so that nothing changes at
     * the first pixel.
     */
    fun cutRest(leaving: Boolean, atEnd: Boolean, outer: Float): Float = if (leaving && atEnd) outer else 0f

    /** The alpha of a card's surface: its list's times its own presence. A swipe never takes part (Android 17 fades a swiped row's content only). */
    fun surfaceAlpha(list: Float, presence: Float): Float = list * presence

    /** The alpha of a card's content (texts, icon, chip, hairline): its [surface] alpha times what a swipe leaves ([swipeFade]). */
    fun contentAlpha(surface: Float, swipeFade: Float): Float = surface * swipeFade

    /**
     * How much of a swiped card's content is left (Android 17's SwipeHelper: progress = the swipe over the card's width, alpha
     * = 1 - progress / [end]): 1 at rest, 0 once it has gone [end] of its width. Its surface is not faded by it.
     */
    fun swipeFade(dx: Float, width: Float, end: Float): Float {
        if (width <= 0f || end <= 0f) return 1f
        return 1f - (kotlin.math.abs(dx) / width / end).coerceIn(0f, 1f)
    }

    /** A cut's corner, from [rest] to the [big] one as the cut's [split] goes 0 to 1. */
    fun cutCorner(rest: Float, big: Float, split: Float): Float = rest + (big - rest) * split

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
            // B-034: the emulator's), so its state is not touched here. Closing it is the user changing its expansion: the
            // first group then lists 2 lines, not 5 (and stays so: nothing takes it out of closedFirst).
            RowKind.HEADER -> { state.opened -= key; state.closedFirst += key }
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
