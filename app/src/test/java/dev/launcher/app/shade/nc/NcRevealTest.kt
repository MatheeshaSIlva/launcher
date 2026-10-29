package dev.launcher.app.shade.nc

import dev.launcher.app.Spring
import dev.launcher.app.design.Curve
import dev.launcher.app.design.Theme
import dev.launcher.app.design.Value
import dev.launcher.app.motion.Ease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** B-037 step 2, slice 2a2: the commit's reveal and the sheet's landing (docs/plans/B-037-step2.md, AC1, AC2). */
class NcRevealTest {
    // The preset's and the theme's own numbers, so a change to them cannot leave these timing tests green.
    private val entries = Theme.parse(File("src/main/assets/motion/ios27.json").readText()).entries
    private val theme = Theme.parse(File("src/main/assets/themes/ios27.json").readText()).entries
    private fun curve(key: String) = (entries.getValue(key).value as Value.CurveV).curve
    private fun ease(key: String): Ease = (curve(key) as Curve.Ease).let { Ease(it.x1, it.y1, it.x2, it.y2, it.ms / 1000f) }
    private val zoom = (theme.getValue("comp.nc.glass.reveal-zoom").value as Value.Number).v

    /** The reveal's progress [ms] after the commit on [key] (0..1). */
    private fun k(key: String, ms: Int): Float = ease(key).apply { start(0f, 0f, 1f) }.value(ms / 1000.0)

    @Test fun theTokenIsPassTensZoom() = assertEquals(2f, zoom, 0f)

    @Test fun overHomeTheIconsZoomAboutTheCentreAndAreGoneBy90ms() {
        val key = "motion.nc.reveal-zoom"
        val scale = { ms: Int -> NcReveal.scale(k(key, ms), zoom) }
        val alpha = { ms: Int -> NcReveal.alpha(k(key, ms), zooms = true) }
        assertEquals(1f, scale(0), 1e-3f)
        assertEquals(1f, alpha(0), 1e-3f)
        assertTrue("scale at 35 ms: ${scale(35)}", scale(35) in 1.15f..1.35f)
        assertTrue("scale at 62 ms: ${scale(62)}", scale(62) in 1.8f..2.2f)
        assertTrue("alpha at 62 ms: ${alpha(62)}", alpha(62) in 0.35f..0.65f)
        for (ms in 90..400 step 5) assertTrue("alpha at $ms ms: ${alpha(ms)}", alpha(ms) <= 0.05f)
        var s = 0f; var a = 2f
        for (ms in 0..120) {
            assertTrue("growing at $ms ms", scale(ms) >= s - 1e-4f); s = scale(ms)
            assertTrue("never rising at $ms ms", alpha(ms) <= a + 1e-4f); a = alpha(ms)
        }
    }

    @Test fun overAnAppThePictureFadesWithNoZoom() {
        val key = "motion.nc.reveal"
        val alpha = { ms: Int -> NcReveal.alpha(k(key, ms), zooms = false) }
        assertEquals(1f, alpha(0), 1e-3f)
        assertTrue("alpha at 83 ms: ${alpha(83)}", alpha(83) in 0.42f..0.62f)
        assertTrue("alpha at 170 ms: ${alpha(170)}", alpha(170) in 0.05f..0.20f)
        var a = 2f
        for (ms in 0..300) { assertTrue("never rising at $ms ms", alpha(ms) <= a + 1e-4f); a = alpha(ms) }
        assertEquals(0f, alpha(300), 1e-4f)
    }

    @Test fun theListsDimComesInWithTheRevealOnlyWhenTheContentIsWhole() {
        assertEquals("whole at once: from nothing", 0f, NcReveal.dimFrom(whole = true, now = 1f), 0f)
        assertEquals("fading in: where it is", 1f, NcReveal.dimFrom(whole = false, now = 1f), 0f)
        assertEquals(0.4f, NcReveal.dimFrom(whole = false, now = 0.4f), 0f)
    }

    @Test fun theScheduleStaysInItsRange() {
        assertEquals(1f, NcReveal.scale(-1f, zoom), 0f)
        assertEquals(1f + (zoom - 1f) * NcReveal.REACH, NcReveal.scale(2f, zoom), 1e-5f)
        assertEquals(0f, NcReveal.alpha(1.5f, true), 0f)
        assertEquals(0f, NcReveal.alpha(1.5f, false), 0f)
        assertEquals(1f, NcReveal.alpha(-0.5f, true), 0f)
    }

    @Test fun aPictureIsUsedOnlyFreshAndUpright() {
        assertTrue(NcReveal.usable(1080, 2400, 1080, 2400, stale = false))
        assertTrue("a smaller picture of the same shape", NcReveal.usable(540, 1200, 1080, 2400, stale = false))
        assertFalse("stale", NcReveal.usable(1080, 2400, 1080, 2400, stale = true))
        assertFalse("an app turned", NcReveal.usable(2400, 1080, 1080, 2400, stale = false))
        assertFalse("none", NcReveal.usable(0, 0, 1080, 2400, stale = false))
    }

    /**
     * Pass 10 test81 (home-deep, released from a standstill at 655 of 874 pt): the sheet's edge per frame, seconds after the
     * last held frame F564. The preset's `motion.nc.open` is fitted to them (start velocity 0, critically damped).
     */
    private val pass10Edge = listOf(0.0 to 655f, 0.035 to 676f, 0.0617 to 721f, 0.0933 to 765f, 0.12 to 801f, 0.15 to 814f,
        0.1783 to 836f, 0.1883 to 850f, 0.2217 to 855f, 0.2317 to 861f)

    @Test fun theOpeningSpringFollowsPassTensLanding() {
        val c = curve("motion.nc.open") as Curve.Spring
        val s = Spring(c.response, c.damping).apply { start(655f / 874f, 0f, 1f) }
        // (The release lies between F564 and F565, frames the recorder dropped: the fit's start is 5 ms after F564.)
        val release = 0.005
        var sq = 0.0
        for ((t, y) in pass10Edge) {
            val d = (s.value(maxOf(0.0, t - release)) * 874f - y).toDouble()
            sq += d * d
            assertTrue("at ${(t * 1000).toInt()} ms: ${s.value(t) * 874f} pt against $y", abs(d) < 15.0)
        }
        assertTrue("rms ${Math.sqrt(sq / pass10Edge.size)} pt", Math.sqrt(sq / pass10Edge.size) < 6.0)
        // From a standstill at 0.75 it covers 90 % of the rest by this time (ms): pass 10's edge had covered ~89 % at 188 ms.
        // (The plan's AC2 asked 115 +- 30 ms; the frames say ~0.2 s: docs/plans/B-037-step2.md, Record.)
        val s75 = Spring(c.response, c.damping).apply { start(0.75f, 0f, 1f) }
        val n75 = (0..600).first { s75.value(it / 1000.0) >= 0.975f || it == 600 }
        assertTrue("90 % of the rest at $n75 ms", n75 in 170..240)
        assertTrue("no overshoot", (0..800).all { s75.value(it / 1000.0) <= 1f + 1e-4f })
    }
}
