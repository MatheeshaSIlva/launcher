package dev.launcher.app

import dev.launcher.app.design.MaterialYou
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MaterialYouTest {
    private val orange = 0xFFD9822B.toInt()

    @Test fun lchRoundTrips() {
        for (c in listOf(orange, 0xFF3366CC.toInt(), 0xFF2E7D32.toInt(), 0xFF808080.toInt())) {
            val (l, ch, h) = MaterialYou.toLch(c)
            val back = MaterialYou.lch(l, ch, h)
            for (shift in listOf(16, 8, 0)) assertTrue("channel off by more than 1", abs(((c shr shift) and 0xFF) - ((back shr shift) and 0xFF)) <= 1)
        }
    }

    @Test fun tonesAreTheirLightness() {
        val s = MaterialYou.scheme(orange, MaterialYou.Style.TONAL, 1f)
        for (t in listOf(10.0, 40.0, 80.0, 95.0)) assertEquals(t, MaterialYou.toLch(s.primary.tone(t)).first, 1.0)
        assertEquals(0xFFFFFFFF.toInt(), s.neutral.tone(100.0))
        assertEquals(0xFF000000.toInt(), s.neutral.tone(0.0))
    }

    @Test fun monochromeAndNoIntensityAreGrey() {
        for ((style, k) in listOf(MaterialYou.Style.MONOCHROME to 1f, MaterialYou.Style.VIBRANT to 0f)) {
            val s = MaterialYou.scheme(orange, style, k)
            assertTrue(MaterialYou.toLch(s.primary.tone(50.0)).second < 1.5)
        }
    }

    @Test fun intensityMakesItMoreColourful() {
        val less = MaterialYou.toLch(MaterialYou.scheme(orange, MaterialYou.Style.TONAL, 0.5f).neutral.tone(94.0)).second
        val more = MaterialYou.toLch(MaterialYou.scheme(orange, MaterialYou.Style.TONAL, 2f).neutral.tone(94.0)).second
        assertTrue("$less < $more", less < more)
    }

    @Test fun aKeyColourMakesOnlyItsPalette() {
        val blue = 0xFF3478F6.toInt()
        val plain = MaterialYou.scheme(orange, MaterialYou.Style.TONAL, 1f)
        val keyed = MaterialYou.scheme(orange, MaterialYou.Style.TONAL, 1f, mapOf('T' to blue))
        assertEquals(MaterialYou.toLch(blue).third, keyed.tertiary.hue, 0.5)
        assertEquals(MaterialYou.toLch(blue).second, keyed.tertiary.chroma, 0.5)
        assertEquals(plain.primary.hue, keyed.primary.hue, 0.001)
        assertEquals(plain.neutral.chroma, keyed.neutral.chroma, 0.001)
    }

    @Test fun harmonizingTurnsTheHueHalfWayAtMost15Degrees() {
        fun hue(c: Int) = MaterialYou.toLch(c).third
        fun turn(from: Double, to: Double) = ((to - from + 540.0) % 360.0) - 180.0
        val blue = 0xFF3366CC.toInt()
        val purple = MaterialYou.lch(50.0, 40.0, hue(blue) + 20.0)
        // Far apart (orange and blue): 15 degrees toward the palette's hue, lightness and chroma as they were.
        val o = MaterialYou.harmonize(orange, blue)
        assertEquals(15.0, abs(turn(hue(orange), hue(o))), 1.0)
        assertTrue(abs(turn(hue(o), hue(blue))) < abs(turn(hue(orange), hue(blue))))
        assertEquals(MaterialYou.toLch(orange).first, MaterialYou.toLch(o).first, 1.0)
        // Close (20 degrees apart): half the way.
        assertEquals(10.0, abs(turn(hue(purple), hue(MaterialYou.harmonize(purple, blue)))), 1.0)
        // The same hue: unchanged.
        assertEquals(blue, MaterialYou.harmonize(blue, blue))
    }

    @Test fun namesKnowTheirPalette() {
        assertEquals('P', MaterialYou.paletteOf("system_accent1_500"))
        assertEquals('V', MaterialYou.paletteOf("system_neutral2_100"))
        assertEquals('T', MaterialYou.paletteOf("system_tertiary_container_dark"))
        assertEquals('N', MaterialYou.paletteOf("system_surface_container_highest_light"))
        assertEquals(null, MaterialYou.paletteOf("system_something_else"))
    }

    @Test fun namesResolve() {
        val s = MaterialYou.scheme(orange, MaterialYou.Style.EXPRESSIVE, 1f)
        assertNotNull(MaterialYou.color(s, "system_accent1_500"))
        assertNotNull(MaterialYou.color(s, "system_surface_container_high_dark"))
        assertNotNull(MaterialYou.color(s, "system_on_surface_variant_light"))
        assertEquals(50.0, MaterialYou.toLch(MaterialYou.color(s, "system_accent2_500")!!).first, 1.0)
        assertEquals(null, MaterialYou.color(s, "system_something_else"))
    }

    // One UI's Color palette off (or a basic colour) leaves the phone's palette blue under any wallpaper: Auto must notice.
    @Test fun phonePaletteFollowsTheWallpaperOnlyWhenItsHueIsThere() {
        val green = 0xFF3E8E41.toInt()
        val moss = 0xFF6B7F3A.toInt()
        val blue = 0xFF3478F6.toInt()
        val greenKey = 0xFF4C8A50.toInt()
        assertEquals(false, MaterialYou.followsWallpaper(blue, listOf(green, moss)))
        assertEquals(true, MaterialYou.followsWallpaper(greenKey, listOf(green, moss)))
        // A palette made from the wallpaper's second colour follows it too.
        assertEquals(true, MaterialYou.followsWallpaper(orange, listOf(green, orange)))
        // A grey wallpaper (Android's palette is then its fallback blue), or a grey palette: nothing to compare.
        assertEquals(true, MaterialYou.followsWallpaper(blue, listOf(0xFF808080.toInt(), 0xFF202020.toInt())))
        assertEquals(true, MaterialYou.followsWallpaper(0xFF777777.toInt(), listOf(green)))
        assertEquals(true, MaterialYou.followsWallpaper(blue, emptyList()))
    }

    @Test fun autoIsTheDefaultStyle() {
        assertEquals(MaterialYou.Style.AUTO, MaterialYou.Style.of(null))
        assertEquals(MaterialYou.Style.AUTO, MaterialYou.Style.of("auto"))
        assertEquals(MaterialYou.Style.SYSTEM, MaterialYou.Style.of("system"))
    }
}
