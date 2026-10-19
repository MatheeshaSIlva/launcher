package dev.launcher.app

import dev.launcher.app.design.MaterialYou
import dev.launcher.app.design.SystemPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemPaletteTest {
    // One UI's palette as the S24 kept it (Settings.System wallpapertheme_color, its first palette): the seed sits at shade 300.
    private val s24 = "[-1, -197381, -790291, -1646118, -3160649, -4675180, -6189455, -7965606, -9806777, -11516619, -13160667, " +
        "-14738668, -16777216, -1, -197380, -855824]"

    @Test fun oneUisPaletteGivesSamsungsOwnSeed() {
        val t = SystemPalette.oneUiTarget(SystemPalette.parseList(s24)!!, gray = false)!!
        // What Samsung's picker had written into Android's setting on the S24: "ffb8a994", TONAL_SPOT.
        assertEquals("ffb8a994", SystemPalette.hex8(t.seed))
        assertEquals("TONAL_SPOT", t.style)
        assertEquals("MONOCHROMATIC", SystemPalette.oneUiTarget(SystemPalette.parseList(s24)!!, gray = true)!!.style)
    }

    @Test fun listsThatAreNoPaletteAreNone() {
        assertNull(SystemPalette.parseList(null))
        assertNull(SystemPalette.parseList("[-1, -2]"))
        assertNull(SystemPalette.parseList("null"))
    }

    @Test fun stylesHaveAndroidsNames() {
        assertEquals("TONAL_SPOT", SystemPalette.styleOf(MaterialYou.Style.AUTO))
        assertEquals("TONAL_SPOT", SystemPalette.styleOf(MaterialYou.Style.TONAL))
        assertEquals("VIBRANT", SystemPalette.styleOf(MaterialYou.Style.VIBRANT))
        assertEquals("EXPRESSIVE", SystemPalette.styleOf(MaterialYou.Style.EXPRESSIVE))
        assertEquals("SPRITZ", SystemPalette.styleOf(MaterialYou.Style.NEUTRAL))
        assertEquals("MONOCHROMATIC", SystemPalette.styleOf(MaterialYou.Style.MONOCHROME))
    }

    @Test fun aColourIsTheSameInEveryForm() {
        assertEquals("b8a994", SystemPalette.key("ffb8a994"))
        assertEquals("b8a994", SystemPalette.key("#B8A994"))
        assertEquals("4caf50", SystemPalette.key("4CAF50"))
        assertEquals("ff4caf50", SystemPalette.hex8(0xFF4CAF50.toInt()))
    }
}
