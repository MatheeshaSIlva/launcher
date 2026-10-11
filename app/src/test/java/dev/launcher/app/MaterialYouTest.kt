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
}
