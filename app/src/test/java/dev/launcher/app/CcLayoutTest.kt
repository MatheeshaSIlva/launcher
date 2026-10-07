package dev.launcher.app

import dev.launcher.app.shade.CcItem
import dev.launcher.app.shade.CcLayout
import dev.launcher.app.shade.Control
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CcLayoutTest {
    private fun noOverlaps(l: CcLayout) {
        for (a in l.items) for (b in l.items) if (a !== b) assertFalse("$a overlaps $b", a.overlaps(b.col, b.row, b.w, b.h))
        for (a in l.items) assertTrue("$a inside", a.col >= 0 && a.row >= 0 && a.col + a.w <= l.cols && a.row + a.h <= l.rows)
    }

    @Test fun defaultHasNoOverlaps() = noOverlaps(CcLayout.default(4, 7))

    @Test fun addFillsFirstGap() {
        val l = CcLayout.default(4, 7)
        val i = l.add(Control.DARK_MODE)
        assertNotNull(i)
        // Row 5 has two free cells after Screen Mirroring and Scan Code.
        assertEquals(2, i!!.col); assertEquals(5, i.row)
        noOverlaps(l)
    }

    @Test fun addRefusesWhenFull() {
        val l = CcLayout(2, 1, mutableListOf(CcItem(Control.FLASHLIGHT, 0, 0, 1, 1), CcItem(Control.TIMER, 1, 0, 1, 1)))
        assertNull(l.add(Control.CAMERA))
    }

    @Test fun movingOntoAnotherPushesItOn() {
        val l = CcLayout.default(4, 7)
        val flash = l.items.first { it.control == Control.FLASHLIGHT }
        val timer = l.items.first { it.control == Control.TIMER }
        assertTrue(l.moveTo(flash, 1, 4))
        assertEquals(1, flash.col)
        // The timer moved on to the next free cell after its own: (2,5).
        assertEquals(2, timer.col); assertEquals(5, timer.row)
        noOverlaps(l)
    }

    @Test fun movingIntoAFreeCellMovesNothingElse() {
        val l = CcLayout.default(4, 7)
        val before = l.items.filter { it.control != Control.CAMERA }.map { it.toString() }
        val cam = l.items.first { it.control == Control.CAMERA }
        assertTrue(l.moveTo(cam, 3, 6))
        assertEquals(before, l.items.filter { it.control != Control.CAMERA }.map { it.toString() })
        noOverlaps(l)
    }

    @Test fun resizeDisplacesNeighbours() {
        val l = CcLayout.default(4, 7)
        val focus = l.items.first { it.control == Control.FOCUS }
        val mirror = l.items.first { it.control == Control.MIRRORING }
        assertTrue(l.resize(mirror, 2, 1))
        noOverlaps(l)
        assertEquals(2, mirror.w)
        assertEquals(2, focus.w)
    }

    @Test fun failedMoveChangesNothing() {
        val l = CcLayout(2, 2, mutableListOf(
            CcItem(Control.CONNECTIVITY, 0, 0, 2, 1),
            CcItem(Control.FLASHLIGHT, 0, 1, 1, 1),
            CcItem(Control.TIMER, 1, 1, 1, 1)))
        val before = l.items.map { it.toString() }
        assertFalse(l.resize(l.items[1], 2, 2))
        assertEquals(before, l.items.map { it.toString() })
    }

    @Test fun jsonRoundTrip() {
        val l = CcLayout.default(4, 7)
        l.moveTo(l.items.first { it.control == Control.CAMERA }, 3, 6)
        val back = CcLayout.fromJson(l.toJson(), 4, 7)!!
        assertEquals(l.items.map { it.toString() }, back.items.map { it.toString() })
    }

    @Test fun jsonDropsUnknownAndOverlaps() {
        val json = """[{"c":"NOPE","x":0,"y":0,"w":1,"h":1},{"c":"FLASHLIGHT","x":0,"y":0,"w":1,"h":1},{"c":"TIMER","x":0,"y":0,"w":1,"h":1}]"""
        val l = CcLayout.fromJson(json, 4, 7)!!
        assertEquals(2, l.items.size)
        noOverlaps(l)
    }
}
