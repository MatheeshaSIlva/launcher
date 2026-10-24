package dev.launcher.app.shade.qs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Pixel shade's notification rows (B-034, slice 1): what a card or a group makes, what a chevron toggles. */
class NotifRowsTest {
    private fun group(key: String, n: Int, section: Section = Section.ALERTING) =
        CardIn(key, section, (1..n).map { "$key/item$it" })

    private fun single(key: String, section: Section = Section.ALERTING) = CardIn(key, section, listOf(key))

    private fun build(cards: List<CardIn>, state: ExpandState = ExpandState(), footer: Boolean = false) =
        NotifRows.build(cards, state, footer)

    private fun List<RowModel>.of(key: String) = first { it.key == key }

    // ------------------------------------------------------------------ AC1: a header

    @Test fun anOpenedGroupStartsWithItsHeaderUnderTheCardsKey() {
        val s = ExpandState().also { it.opened += "app|g" }
        val rows = build(listOf(group("app|g", 3)), s)
        val h = rows.first()
        assertEquals(RowKind.HEADER, h.kind)
        assertEquals("app|g", h.key)
        assertEquals("app|g", h.cardKey)
        assertEquals(0, h.index)
        assertTrue(h.icon)
        assertEquals(Head.APP_TIME, h.head)
        assertEquals(3, h.count)
        assertEquals(Chevron.UP, h.chevron)
        assertEquals(listOf(RowKind.HEADER, RowKind.CHILD, RowKind.CHILD, RowKind.CHILD), rows.map { it.kind })
    }

    @Test fun aCollapsedGroupIsOneRowUnderTheSameKeyAsItsHeader() {
        val closed = build(listOf(group("app|g", 3)))
        assertEquals(listOf(RowKind.GROUP), closed.map { it.kind })
        val s = ExpandState().also { it.opened += "app|g" }
        assertEquals(closed.first().key, build(listOf(group("app|g", 3)), s).first().key)
        assertEquals(Chevron.DOWN, closed.first().chevron)
        assertEquals(3, closed.first().count)
        assertTrue(closed.first().icon)
        assertEquals(Head.APP_TIME, closed.first().head)
    }

    // ------------------------------------------------------------------ AC2, AC3: a child's chevron

    @Test fun aChildsChevronExpandsThatChildOnlyAndTheGroupStaysOpen() {
        val cards = listOf(group("app|g", 3))
        val s = ExpandState().also { it.opened += "app|g" }
        val child = build(cards, s).of("app|g/item2")
        NotifRows.toggle(child, s)
        assertTrue("app|g" in s.opened)
        assertEquals(setOf("app|g/item2"), s.expandedChildren)
        val rows = build(cards, s)
        assertEquals(listOf(false, true, false), rows.filter { it.kind == RowKind.CHILD }.map { it.expanded })
        assertEquals(RowKind.HEADER, rows.first().kind)
    }

    @Test fun aSecondChevronCollapsesOnlyThatChildAndLeavesTheOthers() {
        val cards = listOf(group("app|g", 3))
        val s = ExpandState().also { it.opened += "app|g" }
        NotifRows.toggle(build(cards, s).of("app|g/item1"), s)
        NotifRows.toggle(build(cards, s).of("app|g/item3"), s)
        NotifRows.toggle(build(cards, s).of("app|g/item1"), s)
        assertTrue("app|g" in s.opened)
        assertEquals(setOf("app|g/item3"), s.expandedChildren)
        assertEquals(listOf(false, false, true), build(cards, s).filter { it.kind == RowKind.CHILD }.map { it.expanded })
    }

    // ------------------------------------------------------------------ AC4: the header closes the group

    @Test fun theHeaderClosesTheGroupIntoItsCollapsedCard() {
        val cards = listOf(group("app|g", 3))
        val s = ExpandState().also { it.opened += "app|g" }
        NotifRows.toggle(build(cards, s).first(), s)
        assertFalse("app|g" in s.opened)
        assertEquals(listOf(RowKind.GROUP), build(cards, s).map { it.kind })
    }

    @Test fun aChildExpandedBeforeTheGroupClosedIsStillExpandedWhenItOpensAgain() {
        // Step 0 (2026-10-24, emulator, Android 17's own shade): a child expanded, its group closed by the header's chevron
        // and opened again: the child was still expanded. Copied (docs/SHADE_PIXEL_EVALUATION.md).
        val cards = listOf(group("app|g", 3))
        val s = ExpandState().also { it.opened += "app|g" }
        NotifRows.toggle(build(cards, s).of("app|g/item2"), s)
        NotifRows.toggle(build(cards, s).first(), s)
        NotifRows.toggle(build(cards, s).first(), s)
        assertEquals(RowKind.HEADER, build(cards, s).first().kind)
        assertEquals(listOf(false, true, false), build(cards, s).filter { it.kind == RowKind.CHILD }.map { it.expanded })
    }

    @Test fun aGroupOpensWithItsChip() {
        val cards = listOf(group("app|g", 2))
        val s = ExpandState()
        NotifRows.toggle(build(cards, s).first(), s)
        assertEquals(setOf("app|g"), s.opened)
        assertTrue(s.expandedChildren.isEmpty())
    }

    // ------------------------------------------------------------------ AC5: what each row shows

    @Test fun childrenHaveNoIconAndNameTheirTimeOnlyWhenExpanded() {
        val cards = listOf(group("app|g", 2))
        val s = ExpandState().also { it.opened += "app|g"; it.expandedChildren += "app|g/item2" }
        val rows = build(cards, s)
        val c1 = rows.of("app|g/item1")
        val c2 = rows.of("app|g/item2")
        assertFalse(c1.icon); assertFalse(c2.icon)
        // Collapsed: "Title • time" (the title line carries the time), a down chevron.
        assertEquals(Head.NONE, c1.head); assertEquals(Chevron.DOWN, c1.chevron); assertFalse(c1.expanded)
        // Expanded: the time alone on the first line, an up chevron.
        assertEquals(Head.TIME, c2.head); assertEquals(Chevron.UP, c2.chevron); assertTrue(c2.expanded)
        assertEquals(0, c1.count)
        assertEquals("app|g", c1.cardKey)
        assertEquals(0, c1.index); assertEquals(1, c2.index)
    }

    @Test fun aSingleCardShowsItsIconAndTheAppNameOnlyWhenExpanded() {
        val rows = build(listOf(single("a"), single("b")))
        val a = rows.of("a"); val b = rows.of("b")
        assertTrue(a.icon && b.icon)
        assertEquals(Head.APP_TIME, a.head); assertEquals(Chevron.UP, a.chevron)
        assertEquals(Head.NONE, b.head); assertEquals(Chevron.DOWN, b.chevron)
        assertEquals(0, a.count)
    }

    // ------------------------------------------------------------------ AC6: one card, its corners and gaps

    @Test fun anOpenedGroupIsOneCardWithoutGapsOrCornersInside() {
        val s = ExpandState().also { it.opened += "app|g" }
        val rows = build(listOf(group("app|g", 3)), s)
        assertEquals(listOf(Gap.NONE, Gap.NONE, Gap.NONE, Gap.NONE), rows.map { it.gapBefore })
        assertEquals(Corner.BIG, rows[0].top); assertEquals(Corner.NONE, rows[0].bottom)
        for (i in 1..2) { assertEquals(Corner.NONE, rows[i].top); assertEquals(Corner.NONE, rows[i].bottom) }
        assertEquals(Corner.NONE, rows[3].top); assertEquals(Corner.BIG, rows[3].bottom)
        assertEquals(listOf(false, true, true, true), rows.map { it.divider })
    }

    @Test fun anOpenedGroupBetweenCardsJoinsThemWithSmallCornersAtItsEnds() {
        val s = ExpandState().also { it.opened += "app|g" }
        val rows = build(listOf(single("a"), group("app|g", 2), single("z")), s)
        assertEquals(listOf("a", "app|g", "app|g/item1", "app|g/item2", "z"), rows.map { it.key })
        assertEquals(listOf(Gap.NONE, Gap.JOINED, Gap.NONE, Gap.NONE, Gap.JOINED), rows.map { it.gapBefore })
        assertEquals(Corner.BIG, rows[0].top); assertEquals(Corner.JOINED, rows[0].bottom)
        assertEquals(Corner.JOINED, rows[1].top); assertEquals(Corner.NONE, rows[1].bottom)
        assertEquals(Corner.NONE, rows[2].top); assertEquals(Corner.NONE, rows[2].bottom)
        assertEquals(Corner.NONE, rows[3].top); assertEquals(Corner.JOINED, rows[3].bottom)
        assertEquals(Corner.JOINED, rows[4].top); assertEquals(Corner.BIG, rows[4].bottom)
    }

    @Test fun cornersAreBigAtTheEndsOfEachSectionAndGapsMarkTheSections() {
        val cards = listOf(
            single("on", Section.ONGOING), single("a1"), single("a2"), single("s1", Section.SILENT), single("s2", Section.SILENT),
        )
        val rows = build(cards, footer = true)
        assertEquals(listOf("on", "a1", "a2", "heading:silent", "s1", "s2", "footer"), rows.map { it.key })
        assertEquals(listOf(Gap.NONE, Gap.SECTION, Gap.JOINED, Gap.SECTION, Gap.JOINED, Gap.JOINED, Gap.FOOTER), rows.map { it.gapBefore })
        assertEquals(Pair(Corner.BIG, Corner.BIG), rows.of("on").top to rows.of("on").bottom)
        assertEquals(Pair(Corner.BIG, Corner.JOINED), rows.of("a1").top to rows.of("a1").bottom)
        assertEquals(Pair(Corner.JOINED, Corner.BIG), rows.of("a2").top to rows.of("a2").bottom)
        assertEquals(Pair(Corner.BIG, Corner.JOINED), rows.of("s1").top to rows.of("s1").bottom)
        assertEquals(Pair(Corner.JOINED, Corner.BIG), rows.of("s2").top to rows.of("s2").bottom)
    }

    @Test fun aLoneCardInASectionHasBigCornersAllRound() {
        val r = build(listOf(single("a"))).first()
        assertEquals(Corner.BIG, r.top); assertEquals(Corner.BIG, r.bottom)
    }

    // ------------------------------------------------------------------ AC7: the collapsed group's lines

    @Test fun aCollapsedGroupListsTwoLinesAndTheFirstCardFive() {
        val rows = build(listOf(group("g1", 9), group("g2", 9), group("g3", 2)))
        assertEquals(5, rows.of("g1").lines)
        assertEquals(2, rows.of("g2").lines)
        assertEquals(2, rows.of("g3").lines)
        assertEquals(9, rows.of("g1").count)
    }

    // Read on the emulator's own shade (2026-10-24): the first group lists 5 until the user closes it by its header, then 2;
    // the second group (and a silent one) lists 2 whatever was done to the first.
    @Test fun aFirstGroupTheUserClosedAgainListsTwoLinesLikeStock() {
        val cards = listOf(group("g1", 9), group("g2", 9))
        val s = ExpandState()
        NotifRows.toggle(build(cards, s).of("g1"), s)
        assertEquals(RowKind.HEADER, build(cards, s).of("g1").kind)
        NotifRows.toggle(build(cards, s).of("g1"), s)
        val rows = build(cards, s)
        assertEquals(RowKind.GROUP, rows.of("g1").kind)
        assertEquals(2, rows.of("g1").lines)
        assertEquals(2, rows.of("g2").lines)
        // (It stays so when the group is opened and closed once more.)
        NotifRows.toggle(rows.of("g1"), s)
        NotifRows.toggle(build(cards, s).of("g1"), s)
        assertEquals(2, build(cards, s).of("g1").lines)
    }

    @Test fun closingTheStateAtThePanelsCloseGivesTheFirstGroupItsFiveLinesBack() {
        val s = ExpandState()
        NotifRows.toggle(build(listOf(group("g1", 9)), s).of("g1"), s)
        NotifRows.toggle(build(listOf(group("g1", 9)), s).of("g1"), s)
        s.clear()
        assertEquals(5, build(listOf(group("g1", 9)), s).of("g1").lines)
    }

    @Test fun theLinesNeverExceedTheChildren() {
        assertEquals(3, build(listOf(group("g", 3))).first().lines)
        assertEquals(2, build(listOf(single("x"), group("g", 2))).of("g").lines)
    }

    @Test fun theFirstCardIsTheFirstOfTheWholeListNotOfItsSection() {
        val rows = build(listOf(group("on", 8, Section.ONGOING), group("al", 8)))
        assertEquals(5, rows.of("on").lines)
        assertEquals(2, rows.of("al").lines)
    }

    // ------------------------------------------------------------------ AC8: at most eight children

    @Test fun anOpenedGroupShowsEightChildrenWhileItsCountStaysTheTotal() {
        val s = ExpandState().also { it.opened += "g" }
        val rows = build(listOf(group("g", 12)), s)
        assertEquals(1 + 8, rows.size)
        assertEquals(12, rows.first().count)
        assertEquals((1..8).map { "g/item$it" }, rows.drop(1).map { it.key })
        assertEquals(Corner.BIG, rows.last().bottom)
    }

    @Test fun aGroupOfEightOrFewerShowsAllItsChildren() {
        val s = ExpandState().also { it.opened += "g" }
        assertEquals(9, build(listOf(group("g", 8)), s).size)
        assertEquals(8, build(listOf(group("g", 7)), s).size)
    }

    // ------------------------------------------------------------------ AC9: single cards as today

    @Test fun theFirstCardIsExpandedUntilItsChevronClosesIt() {
        val cards = listOf(single("a"), single("b"))
        val s = ExpandState()
        assertTrue(build(cards, s).of("a").expanded)
        assertFalse(build(cards, s).of("b").expanded)
        NotifRows.toggle(build(cards, s).of("a"), s)
        assertEquals(setOf("a"), s.closedFirst)
        assertTrue(s.opened.isEmpty())
        assertFalse(build(cards, s).of("a").expanded)
        NotifRows.toggle(build(cards, s).of("a"), s)
        assertTrue(s.closedFirst.isEmpty())
        assertTrue(build(cards, s).of("a").expanded)
    }

    @Test fun anotherCardOpensAndClosesByItsChevron() {
        val cards = listOf(single("a"), single("b"))
        val s = ExpandState()
        NotifRows.toggle(build(cards, s).of("b"), s)
        assertEquals(setOf("b"), s.opened)
        assertTrue(build(cards, s).of("b").expanded)
        assertEquals(Chevron.UP, build(cards, s).of("b").chevron)
        NotifRows.toggle(build(cards, s).of("b"), s)
        assertTrue(s.opened.isEmpty())
        assertFalse(build(cards, s).of("b").expanded)
    }

    @Test fun sectionsComeInOrderWithTheSilentHeadingAndTheFooterLast() {
        val cards = listOf(single("s", Section.SILENT), single("a"), single("o", Section.ONGOING))
        val rows = build(cards, footer = true)
        assertEquals(listOf("o", "a", "heading:silent", "s", "footer"), rows.map { it.key })
        assertEquals(listOf(RowKind.CARD, RowKind.CARD, RowKind.HEADING, RowKind.CARD, RowKind.FOOTER), rows.map { it.kind })
        val heading = rows.of("heading:silent")
        assertNull(heading.cardKey); assertFalse(heading.icon)
        assertEquals(Chevron.NONE, heading.chevron); assertEquals(Head.NONE, heading.head)
        assertEquals(Gap.SECTION, heading.gapBefore)
        assertEquals(Gap.FOOTER, rows.of("footer").gapBefore)
    }

    @Test fun noFooterWithoutNotificationsOrWithoutAFooterBlock() {
        assertTrue(build(emptyList(), footer = true).isEmpty())
        assertTrue(build(listOf(single("a")), footer = false).none { it.kind == RowKind.FOOTER })
    }

    @Test fun headingAndFooterToggleNothing() {
        val cards = listOf(single("s", Section.SILENT))
        val s = ExpandState()
        val rows = build(cards, s, footer = true)
        NotifRows.toggle(rows.of("heading:silent"), s)
        NotifRows.toggle(rows.of("footer"), s)
        assertTrue(s.opened.isEmpty() && s.expandedChildren.isEmpty() && s.closedFirst.isEmpty())
    }

    @Test fun clearingForgetsEveryOpenedState() {
        val s = ExpandState().also { it.opened += "a"; it.expandedChildren += "b"; it.closedFirst += "c" }
        s.clear()
        assertTrue(s.opened.isEmpty() && s.expandedChildren.isEmpty() && s.closedFirst.isEmpty())
    }

    // ------------------------------------------------------------------ key stability

    @Test fun aGroupFallingToOneItemTurnsItsLastChildIntoAPlainCardWithoutChangingKey() {
        val s = ExpandState().also { it.opened += "app|g" }
        val before = build(listOf(CardIn("app|g", Section.ALERTING, listOf("n1", "n2"))), s)
        assertEquals(listOf("app|g", "n1", "n2"), before.map { it.key })
        // The group's one remaining item: the card's key is the item's, as the list's own rule makes it.
        val after = build(listOf(CardIn("n1", Section.ALERTING, listOf("n1"))), s)
        assertEquals(listOf("n1"), after.map { it.key })
        assertEquals(RowKind.CARD, after.first().kind)
        assertTrue(after.first().key in before.map { it.key })
    }

    @Test fun keysAreTheSameAcrossRebuildsAndUniqueInAList() {
        val s = ExpandState().also { it.opened += "g" }
        val cards = listOf(single("a"), group("g", 4), single("z", Section.SILENT))
        val one = build(cards, s, footer = true).map { it.key }
        assertEquals(one, build(cards, s, footer = true).map { it.key })
        assertEquals(one.size, one.toSet().size)
    }

    // ------------------------------------------------------------------ what flies out when notifications are cleared

    private fun flying(cards: List<CardIn>, state: ExpandState, gone: Set<String>) =
        NotifRows.flying(build(cards, state, footer = true), cards, gone).map { it.key }

    @Test fun anOpenedGroupClearedWholeFliesAsItsHeaderAloneAndTheChildrenAreNotSentAgain() {
        val cards = listOf(single("a"), group("g", 3))
        val s = ExpandState().also { it.opened += "g" }
        // (Bottom first, as Clear all sends them.)
        assertEquals(listOf("g", "a"), flying(cards, s, setOf("a", "g/item1", "g/item2", "g/item3")))
    }

    @Test fun aClosedGroupClearedWholeFliesAsOneCard() {
        assertEquals(listOf("g"), flying(listOf(group("g", 3)), ExpandState(), setOf("g/item1", "g/item2", "g/item3")))
    }

    @Test fun anOpenedGroupWithItemsThatStayLetsOnlyItsClearedChildrenFly() {
        // The third cannot be cleared (an ongoing notification): its group stays, the others fly one by one.
        val s = ExpandState().also { it.opened += "g" }
        assertEquals(listOf("g/item2", "g/item1"), flying(listOf(group("g", 3)), s, setOf("g/item1", "g/item2")))
    }

    @Test fun aClosedGroupWithItemsThatStayDoesNotFlyAtAll() {
        assertTrue(flying(listOf(group("g", 3)), ExpandState(), setOf("g/item1", "g/item2")).isEmpty())
    }

    @Test fun nothingFliesForNotificationsNotShownOrForHeadingsAndFooters() {
        val s = ExpandState().also { it.opened += "g" }
        // (A ninth child is not a row; the group still has items that stay.)
        assertTrue(flying(listOf(group("g", 9)), s, setOf("g/item9")).isEmpty())
        val cards = listOf(single("s", Section.SILENT))
        assertEquals(listOf("s"), flying(cards, ExpandState(), setOf("s")))
    }

    // ------------------------------------------------------------------ a child with no time

    @Test fun anExpandedChildWithoutATimeHasNoFirstLine() {
        val cards = listOf(CardIn("g", Section.ALERTING, listOf("g/a", "g/b"), untimed = setOf("g/a")))
        val s = ExpandState().also { it.opened += "g"; it.expandedChildren += "g/a"; it.expandedChildren += "g/b" }
        val rows = build(cards, s)
        assertEquals(Head.NONE, rows.of("g/a").head)
        assertEquals(Head.TIME, rows.of("g/b").head)
        assertTrue(rows.of("g/a").expanded)
    }

    // ------------------------------------------------------------------ the motion draws the expanded content of a folding row

    @Test fun theExpandedFirstLineIsWhatTheRowShowsWhenOpenWhateverItsStateNow() {
        // (A row folding is collapsed in the model but still draws its expanded content: the head must not come from the
        // model's own `head`, which is NONE then.)
        val cards = listOf(single("a"), CardIn("g", Section.ALERTING, listOf("g/a", "g/b"), untimed = setOf("g/a")))
        val s = ExpandState().also { it.closedFirst += "a"; it.opened += "g" }
        val rows = build(cards, s)
        assertEquals(Head.NONE, rows.of("a").head)
        assertEquals(Head.APP_TIME, NotifRows.expandedHead(RowKind.CARD, true))
        assertEquals(Head.APP_TIME, NotifRows.expandedHead(RowKind.CARD, false))
        assertEquals(Head.NONE, rows.of("g/b").head)
        assertEquals(Head.TIME, NotifRows.expandedHead(RowKind.CHILD, true))
        assertEquals(Head.NONE, NotifRows.expandedHead(RowKind.CHILD, false))
        assertEquals(Head.NONE, NotifRows.expandedHead(RowKind.GROUP, true))
        // Once open, the model agrees.
        s.expandedChildren += "g/b"; s.closedFirst -= "a"
        val open = build(cards, s)
        assertEquals(NotifRows.expandedHead(RowKind.CHILD, true), open.of("g/b").head)
        assertEquals(NotifRows.expandedHead(RowKind.CARD, true), open.of("a").head)
    }

    // ------------------------------------------------------------------ actions of a row that is folding or growing

    @Test fun anActionBelowTheRowsCurrentEdgeCannotBeTapped() {
        // (An expanded card's actions lie at 200..248; the row is mid-fold, 0..150 tall: they are not on it.)
        assertTrue(NotifRows.reachable(200f, 248f, 0f, 260f))
        assertFalse(NotifRows.reachable(200f, 248f, 0f, 150f))
        assertFalse(NotifRows.reachable(200f, 248f, 0f, 220f))
        assertTrue(NotifRows.reachable(200f, 248f, 0f, 247.6f))
        assertFalse(NotifRows.reachable(200f, 248f, 0f, 247f))
        assertFalse(NotifRows.reachable(-30f, 18f, 0f, 260f))
    }

    // ------------------------------------------------------------------ the part two cuts of a title share, drawn once

    private fun shared(a: String, b: String) = NotifRows.sharedPrefix(a, b)

    @Test fun plainTextKeepsItsWholeCommonStart() {
        assertEquals(9, shared("Hello wor\u2026", "Hello world"))
        assertEquals(5, shared("Hello", "Hello world"))
        assertEquals(0, shared("abc", "xyz"))
    }

    @Test fun neverBetweenTheHalvesOfASurrogatePair() {
        // (Both 😀 and 😁 start with the same high surrogate.)
        assertEquals(1, shared("a\uD83D\uDE00", "a\uD83D\uDE01"))
        assertEquals(3, shared("a\uD83D\uDE00b", "a\uD83D\uDE00c"))
    }

    @Test fun aCombiningMarkStaysWithItsLetter() {
        assertEquals(3, shared("ae\u0301", "ae\u0301x"))
        // (The longer one has a mark the shorter lacks: not after the bare letter.)
        assertEquals(1, shared("ae", "ae\u0301"))
    }

    @Test fun aJoinedEmojiSequenceIsNotSplit() {
        val man = "\uD83D\uDC68"
        val woman = "\uD83D\uDC69"
        val girl = "\uD83D\uDC67"
        assertEquals(1, shared("x$man\u200D$woman", "x$man\u200D$girl"))
        // (Whole, with something else after it: a clean split.)
        assertEquals(1 + 2 + 1 + 2, shared("x$man\u200D$woman!", "x$man\u200D$woman?"))
    }

    @Test fun aSkinToneModifierStaysWithItsEmoji() {
        val thumb = "\uD83D\uDC4D"
        assertEquals(1, shared("x$thumb\uD83C\uDFFD", "x$thumb\uD83C\uDFFF"))
        assertEquals(1, shared("x$thumb", "x$thumb\uD83C\uDFFD"))
    }

    @Test fun aFlagIsNotSplitInHalf() {
        val u = "\uD83C\uDDFA"
        assertEquals(1, shared("x$u\uD83C\uDDF8", "x$u\uD83C\uDDE6"))
        assertEquals(5, shared("x$u\uD83C\uDDF8!", "x$u\uD83C\uDDF8?"))
    }

    @Test fun rightToLeftTextIsNeverSplit() {
        assertEquals(0, shared("\u05E9\u05DC\u05D5\u05DD\u2026", "\u05E9\u05DC\u05D5\u05DD \u05E2\u05D5\u05DC\u05DD"))
        // (An LTR title holding an RTL name.)
        assertEquals(0, shared("Title \u05E9\u05DC\u05D5\u05DD\u2026", "Title \u05E9\u05DC\u05D5\u05DD x"))
        // (The cut one is LTR, the other has an RTL ending.)
        assertEquals(0, shared("Title \u2026", "Title \u05E9"))
    }

    @Test fun lettersThatJoinAreNeverSplit() {
        assertEquals(0, shared("\u0645\u0631\u062D\u0628\u0627\u2026", "\u0645\u0631\u062D\u0628\u0627 \u0628\u0643"))
        // (Mongolian: joining, though written left to right.)
        assertEquals(0, shared("\u182E\u1823\u1829\u2026", "\u182E\u1823\u1829\u182D"))
    }

    @Test fun anIndicConjunctIsNotSplit() {
        // Sinhala "shri": SHA, AL-LAKUNA, ZWJ, RA, II-PILLA; "kssa": KA, AL-LAKUNA, ZWJ, SSA.
        val shri = "\u0DC1\u0DCA\u200D\u0DBB\u0DD3"
        val kssa = "\u0D9A\u0DCA\u200D\u0DC2"
        assertEquals(5, shared(shri, shri + "x"))
        assertEquals(0, shared("\u0DC1\u0DCA\u200D\u0DBB\u0DCF", shri))
        assertEquals(4, shared(kssa + "x", kssa + "y"))
        assertEquals(0, shared("\u0D9A\u0DCA\u200D", kssa))
        // (A virama joining the next letter without a joiner: Devanagari "ksha".)
        assertEquals(0, shared("\u0915\u094D\u2026", "\u0915\u094D\u0937"))
        assertEquals(3, shared("\u0915\u094D\u0937a", "\u0915\u094D\u0937b"))
    }

    // ------------------------------------------------------------------ slice 3b-1: children unfolding out of a group's lines

    @Test fun aChildOnOneOfTheListedLinesFoldsToThatLine() {
        for (i in 0 until 2) assertEquals(i, NotifRows.foldLine(i, 2))
        for (i in 0 until 5) assertEquals(i, NotifRows.foldLine(i, 5))
    }

    @Test fun aChildPastTheListedLinesFoldsBelow() {
        assertEquals(NotifRows.BELOW, NotifRows.foldLine(2, 2))
        assertEquals(NotifRows.BELOW, NotifRows.foldLine(7, 5))
        assertEquals(NotifRows.BELOW, NotifRows.foldLine(0, 0))
    }

    @Test fun anUnknownChildFoldsBelow() {
        assertEquals(NotifRows.BELOW, NotifRows.foldLine(-1, 2))
    }

    @Test fun theLinesPlacesFollowTheHeadersTopOneLineEach() {
        assertEquals(110f, NotifRows.foldTop(100f, 10f, 20f, 0), 0f)
        assertEquals(130f, NotifRows.foldTop(100f, 10f, 20f, 1), 0f)
        assertEquals(210f, NotifRows.foldTop(100f, 10f, 20f, 5), 0f)
    }

    @Test fun theLinesOfTheFirstGroupAreTheFoldLinesOfItsChildren() {
        // The model's own count (5 for the first group, 2 for others) is what a child folds into.
        val closed = build(listOf(group("a|g", 6)))
        val lines = closed.first().lines
        assertEquals(NotifRows.LINES_FIRST, lines)
        assertEquals(4, NotifRows.foldLine(4, lines))
        assertEquals(NotifRows.BELOW, NotifRows.foldLine(5, lines))
    }
}
