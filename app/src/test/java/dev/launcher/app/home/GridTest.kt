package dev.launcher.app.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Free placement on a 4 x 6 page ([Grid]). Cells are written as row * 4 + col. */
class GridTest {
    private val cols = 4
    private val rows = 6

    private fun app(k: String, cell: Int = -1) = HomeItem.App(k).apply { if (cell >= 0) { col = cell % cols; row = cell / cols } }
    private fun cellOf(i: HomeItem) = i.row * cols + i.col

    @Test fun oldLayoutsWithoutCellsFlowInOrder() {
        val page = mutableListOf<HomeItem>(HomeItem.Widget("clock", 4, 2), app("a"), app("b"))
        Grid.place(page, cols, rows)
        assertEquals(0, cellOf(page[0]))
        assertEquals(8, cellOf(page[1]))
        assertEquals(9, cellOf(page[2]))
    }

    @Test fun iconsKeepEmptyCells() {
        val page = mutableListOf<HomeItem>(app("a", 0), app("b", 7))
        val placed = Grid.place(page, cols, rows)
        assertEquals(listOf(0, 7), placed.map { it.row * cols + it.col })
    }

    @Test fun dropOnEmptyCellLandsExactly() {
        val a = app("a", 0)
        val page = mutableListOf<HomeItem>(a, app("b", 1))
        page.remove(a)
        val off = Grid.putAt(page, a, 2, 3, cols, rows, origin = 0)
        assertNotNull(off)
        assertEquals(14, cellOf(a))
        assertEquals(1, cellOf(page.first { (it as HomeItem.App).key == "b" }))
    }

    @Test fun moveRightWithinARunReorders() {
        // a b c d in row 0; a moves onto c: b and c step back, d stays.
        val a = app("a", 0); val b = app("b", 1); val c = app("c", 2); val d = app("d", 3)
        val page = mutableListOf<HomeItem>(a, b, c, d)
        page.remove(a)
        Grid.putAt(page, a, 2, 0, cols, rows, origin = 0)
        assertEquals(listOf(0, 1, 2, 3), listOf(b, c, a, d).map(::cellOf))
    }

    @Test fun moveLeftWithinARunReorders() {
        val a = app("a", 0); val b = app("b", 1); val c = app("c", 2); val d = app("d", 3)
        val page = mutableListOf<HomeItem>(a, b, c, d)
        page.remove(d)
        Grid.putAt(page, d, 1, 0, cols, rows, origin = 3)
        assertEquals(listOf(0, 1, 2, 3), listOf(a, d, b, c).map(::cellOf))
    }

    @Test fun iconsInTheWayMoveOnUntilAGap() {
        // x comes from another page onto b: b moves to c's cell, c into the gap at 3; nothing else moves.
        val a = app("a", 0); val b = app("b", 1); val c = app("c", 2); val e = app("e", 5)
        val page = mutableListOf<HomeItem>(a, b, c, e)
        val x = app("x")
        val off = Grid.putAt(page, x, 1, 0, cols, rows)
        assertEquals(emptyList<HomeItem>(), off)
        assertEquals(listOf(0, 1, 2, 3, 5), listOf(a, x, b, c, e).map(::cellOf))
    }

    @Test fun aFullPagePushesTheLastIconOff() {
        val page = (0 until 24).map { app("i$it", it) }.toMutableList<HomeItem>()
        val x = app("x")
        val off = Grid.putAt(page, x, 0, 0, cols, rows)!!
        assertEquals(1, off.size)
        assertEquals("i23", (off[0] as HomeItem.App).key)
        assertEquals(24, page.size)
        assertTrue(page.none { it === off[0] })
    }

    @Test fun widgetsBlockAndAreNotPushed() {
        val w = HomeItem.Widget("clock", 4, 2).apply { col = 0; row = 0 }
        val page = mutableListOf<HomeItem>(w, app("a", 8))
        assertNull(Grid.putAt(page, app("x"), 1, 1, cols, rows))
        assertEquals(0, cellOf(w))
    }

    @Test fun aWidgetPushesIconsOutOfItsBlock() {
        val a = app("a", 0); val b = app("b", 5)
        val page = mutableListOf<HomeItem>(a, b)
        val w = HomeItem.Widget("clock", 2, 2)
        val off = Grid.putAt(page, w, 0, 0, cols, rows)
        assertEquals(emptyList<HomeItem>(), off)
        val block = setOf(0, 1, 4, 5)
        assertTrue(cellOf(a) !in block && cellOf(b) !in block)
        assertEquals(2, cellOf(a))
        assertEquals(6, cellOf(b))
    }

    @Test fun aGrowingWidgetKeepsTheOrderOfTheIconsItMoves() {
        // A 4x2 widget on rows 0-1, icons a b c d on row 2 and e on row 3; the widget grows to 4x4 (rows 0-3).
        val w = HomeItem.Widget("clock", 4, 2).apply { col = 0; row = 0 }
        val a = app("a", 8); val b = app("b", 9); val c = app("c", 10); val d = app("d", 11); val e = app("e", 12)
        val page = mutableListOf<HomeItem>(w, a, b, c, d, e)
        page.remove(w)
        val big = w.with(spanX = 4, spanY = 4)
        val off = Grid.putAt(page, big, 0, 0, cols, rows)
        assertEquals(emptyList<HomeItem>(), off)
        assertEquals(listOf(16, 17, 18, 19, 20), listOf(a, b, c, d, e).map(::cellOf))
    }

    @Test fun firstFreeSkipsTakenCells() {
        val page = mutableListOf<HomeItem>(app("a", 0), app("b", 1))
        assertEquals(listOf(2, 0), Grid.firstFree(page, 1, 1, cols, rows)!!.toList())
        assertEquals(listOf(0, 1), Grid.firstFree(page, 4, 2, cols, rows)!!.toList())
    }
}
