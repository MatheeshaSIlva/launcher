package dev.launcher.app.settings

import dev.launcher.app.layout.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings' structure B (docs/PLAN_SETTINGS_V2.md): what the tiles hold, and the tiles' scroll. */
class PartsTest {
    @Test fun everyPartOfThePhoneHasOneTile() {
        // The settings themselves are no part of the phone: their look and motion are under Look and Motion.
        for (e in Element.entries) {
            assertEquals("$e", if (e == Element.SETTINGS) 0 else 1, Part.entries.count { it.element == e })
        }
    }

    @Test fun theWholePhoneFirstPhoneLast() {
        assertEquals(listOf(Part.LOOK, Part.MOTION), Part.entries.take(2))
        assertEquals(Part.PHONE, Part.entries.last())
        for (p in listOf(Part.LOOK, Part.MOTION, Part.PHONE)) assertEquals("$p", null, p.element)
    }

    @Test fun subPagesHangUnderPagesThatPushThem() {
        // Only Look's and Phone's pages push pages of their own (SubPager); Motion's groups are its own.
        for (s in Sub.entries) assertTrue("$s", s.part == Part.LOOK || s.part == Part.PHONE)
    }

    @Test fun theTilesScrollLiftsTheSheetFirst() {
        val lift = 170f
        // Within the lift the content stays (the sheet moves), past it the content scrolls by the rest.
        assertEquals(0f, contentOffset(0f, lift), 0f)
        assertEquals(0f, contentOffset(120f, lift), 0f)
        assertEquals(0f, contentOffset(lift, lift), 0f)
        assertEquals(30f, contentOffset(lift + 30f, lift), 0f)
        // Pulled down past the top, the content follows the finger.
        assertEquals(-12f, contentOffset(-12f, lift), 0f)
        // No lift (the Apply bar took the room, or none to take): the content scrolls at once.
        assertEquals(40f, contentOffset(40f, 0f), 0f)
    }
}
