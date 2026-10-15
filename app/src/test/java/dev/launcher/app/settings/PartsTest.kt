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
        val tiles = Part.entries.filter { it.tile }
        assertEquals(listOf(Part.LOOK, Part.MOTION), tiles.take(2))
        assertEquals(Part.PHONE, tiles.last())
        for (p in listOf(Part.LOOK, Part.MOTION, Part.PHONE)) assertEquals("$p", null, p.element)
        // Your changes is a page with no tile.
        assertEquals(listOf(Part.CHANGES), Part.entries.filter { !it.tile })
    }

    @Test fun theSearchFindsByNameAndByWhatItDoes() {
        val index = listOf(
            SearchEntry("Columns", "Home › Grid", "icons across a page grid", Part.HOME),
            SearchEntry("Bounce", "Motion › Feel", "bounce spring overshoot", Part.MOTION),
            SearchEntry("Light & dark", "Look", "dark mode night appearance", Part.LOOK),
            SearchEntry("Dock", "Home › Grid", "dock icons bottom", Part.HOME),
        )
        assertEquals(listOf("Columns", "Dock"), searchSettings(index, "icons").map { it.title })
        assertEquals(listOf("Light & dark"), searchSettings(index, "DARK").map { it.title })
        assertEquals(listOf("Light & dark"), searchSettings(index, "light and dark").map { it.title })
        // Every word must be found; a name that starts with the words comes first.
        assertEquals(listOf("Dock"), searchSettings(index, "dock icons").map { it.title })
        assertEquals(listOf("Bounce"), searchSettings(index, "bou").map { it.title })
        assertEquals(emptyList<String>(), searchSettings(index, "  ").map { it.title })
        assertEquals(emptyList<String>(), searchSettings(index, "wallpaperzzz").map { it.title })
        // Settings before single animations, whatever the words.
        val withRole = index + SearchEntry("Depth: icons", "Apps › Opening apps", "animation", Part.APPS, rank = 2)
        assertEquals(listOf("Columns", "Dock", "Depth: icons"), searchSettings(withRole, "icons").map { it.title })
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
