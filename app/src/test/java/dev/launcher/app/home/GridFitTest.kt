package dev.launcher.app.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The user's columns, rows and dock icons changed: the layout fits the new grid ([Grid.fit]). */
class GridFitTest {
    private fun app(k: String, col: Int, row: Int) = HomeItem.App(k).apply { this.col = col; this.row = row }
    private fun keys(page: List<HomeItem>) = page.map { (it as HomeItem.App).key }.toSet()
    private fun layout(vararg pages: MutableList<HomeItem>, dock: List<String> = emptyList()) =
        HomeLayout(pages.toMutableList(), dock.toMutableList(), mutableSetOf())

    @Test fun aGridThatStillFitsChangesNothing() {
        val l = layout(mutableListOf(app("a", 0, 0), app("b", 3, 5)), dock = listOf("d1", "d2"))
        assertFalse(Grid.fit(l, 4, 6, 4))
        assertFalse(Grid.fit(l, 5, 7, 5))   // more room: everything stays where it is
        assertEquals(3, l.pages[0][1].col)
    }

    @Test fun fewerColumnsMoveTheLastColumnIntoFreeCells() {
        val l = layout(mutableListOf(app("a", 0, 0), app("e", 4, 0)))
        assertTrue(Grid.fit(l, 4, 6, 4))
        assertEquals(1, l.pages.size)
        val e = l.pages[0].first { (it as HomeItem.App).key == "e" }
        assertEquals(1, e.col); assertEquals(0, e.row)   // the first free cell in reading order
    }

    @Test fun whatNoLongerFitsAFullPageGoesFirstOnTheNext() {
        val full = MutableList<HomeItem>(20) { app("p$it", it % 5, it / 5) }   // 5 x 4, full
        val next = mutableListOf<HomeItem>(app("n", 0, 0))
        val l = layout(full, next)
        assertTrue(Grid.fit(l, 4, 4, 4))   // 16 cells: the fifth column's 4 move on
        assertEquals(16, l.pages[0].size)
        assertEquals(setOf("n", "p4", "p9", "p14", "p19"), keys(l.pages[1]))
        val n = l.pages[1].first { (it as HomeItem.App).key == "n" }
        assertEquals(0, n.col); assertEquals(0, n.row)   // kept its own cell
    }

    @Test fun fewerRowsOnTheLastPageMakeANewPage() {
        val l = layout(MutableList<HomeItem>(24) { app("p$it", it % 4, it / 4) })
        assertTrue(Grid.fit(l, 4, 5, 4))
        assertEquals(2, l.pages.size)
        assertEquals(setOf("p20", "p21", "p22", "p23"), keys(l.pages[1]))
    }

    @Test fun aSmallerDockSendsItsLastIconsToHome() {
        val l = layout(mutableListOf(app("a", 0, 0)), dock = listOf("d1", "d2", "d3", "d4", "d5"))
        assertTrue(Grid.fit(l, 4, 6, 4))
        assertEquals(listOf("d1", "d2", "d3", "d4"), l.dock)
        assertEquals(setOf("a", "d5"), keys(l.pages[0]))
    }

    @Test fun roomAtIphoneSizeIsIos() {
        // The S24 at iPhone size (2.687 px a point), status bar 110 px, our gesture strip 84 px.
        val r = HomeMetrics.room(HomeMetrics.Frame(1080, 2340, 110, 84), 1080 / 402f, labels = true, tf = 1f)
        assertEquals(HomeMetrics.Room(4, 6, 4), r)
        // Following its Display size (12.5 % smaller): a column, a row and a dock icon more.
        val s = HomeMetrics.room(HomeMetrics.Frame(1080, 2340, 110, 84), 1080 / 402f * 0.875f, labels = true, tf = 1f)
        assertEquals(HomeMetrics.Room(5, 7, 5), s)
    }
}
