package dev.launcher.app

import dev.launcher.app.design.Blend
import dev.launcher.app.design.Blends
import org.junit.Assert.assertEquals
import org.junit.Test

/** The CPU blend modes must give what the glass shader's blendOf gives (the system's live blur is tinted from them). */
class BlendsTest {
    private fun eq(want: FloatArray, got: FloatArray) { for (i in 0..2) assertEquals("channel $i", want[i], got[i], 0.002f) }
    private val gray = floatArrayOf(0.5f, 0.5f, 0.5f)
    private val warm = floatArrayOf(0.63f, 0.49f, 0.37f)
    private val dark = floatArrayOf(0.1f, 0.1f, 0.1f)

    @Test fun separableModes() {
        eq(floatArrayOf(0.5f, 0.5f, 0.5f), Blends.blend(gray, gray, Blend.NORMAL))
        eq(floatArrayOf(0.315f, 0.245f, 0.185f), Blends.blend(warm, gray, Blend.MULTIPLY))
        eq(floatArrayOf(0.63f, 0.5f, 0.5f), Blends.blend(warm, gray, Blend.LIGHTEN))
        eq(floatArrayOf(0.5f, 0.49f, 0.37f), Blends.blend(warm, gray, Blend.DARKEN))
        eq(floatArrayOf(1f, 0.99f, 0.87f), Blends.blend(warm, gray, Blend.LINEAR_DODGE))
        eq(floatArrayOf(0.13f, 0f, 0f), Blends.blend(warm, gray, Blend.LINEAR_BURN))
    }

    @Test fun overlayBranchesOnTheBackdropHardLightOnTheSource() {
        // Overlay: backdrop 0.3 (< 0.5): 2 b s; Hard Light with the roles swapped.
        eq(floatArrayOf(0.48f, 0.48f, 0.48f), Blends.blend(floatArrayOf(0.3f, 0.3f, 0.3f), floatArrayOf(0.8f, 0.8f, 0.8f), Blend.OVERLAY))
        eq(floatArrayOf(0.72f, 0.72f, 0.72f), Blends.blend(floatArrayOf(0.3f, 0.3f, 0.3f), floatArrayOf(0.8f, 0.8f, 0.8f), Blend.HARD_LIGHT))
    }

    @Test fun luminosityTakesTheSourcesBrightnessAndKeepsTheBackdropsColour() {
        val r = Blends.blend(warm, dark, Blend.LUMINOSITY)
        val lum = 0.3f * r[0] + 0.59f * r[1] + 0.11f * r[2]
        assertEquals(0.1f, lum, 0.003f)
        // Still warm: red above green above blue.
        assert(r[0] > r[1] && r[1] > r[2])
    }

    @Test fun luminosityOverGreyIsGrey() = eq(floatArrayOf(0.1f, 0.1f, 0.1f), Blends.blend(gray, dark, Blend.LUMINOSITY))
}
