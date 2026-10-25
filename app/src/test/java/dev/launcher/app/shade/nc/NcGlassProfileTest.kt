package dev.launcher.app.shade.nc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-037 slice 1a: the clear slab's edge profile and its release threshold (docs/plans/B-037.md AC1, AC2). */
class NcGlassProfileTest {
    // The tokens' values (comp.nc.glass.band, mirror, stretch-from, mirror-from, side, side-from).
    private val d = 26f
    private val m = 4f
    private val s = 18f
    private val mf = 28f
    private fun bottom(e: Float) = NcGlassProfile.bottomSampleDist(e, d, m, s, mf)
    private fun side(e: Float) = NcGlassProfile.sideSampleDist(e, 15f, 6f)

    @Test fun theBandMeetsTheContentAboveIt() {
        assertEquals(d, bottom(d), 1e-4f)
        assertEquals(d + 5f, bottom(d + 5f), 0f)
        // With slope 1 at the top: no seam with the content above.
        assertEquals(1f, (bottom(d) - bottom(d - 0.01f)) / 0.01f, 0.05f)
    }

    @Test fun theStretchRisesFromTheFoldToTheTop() {
        var prev = bottom(m)
        var e = m + 0.1f
        while (e <= d) { val v = bottom(e); assertTrue("at $e: $v after $prev", v > prev); prev = v; e += 0.1f }
    }

    @Test fun theMirrorFallsFromTheEdgeToTheFold() {
        var prev = bottom(0f)
        var e = 0.1f
        while (e <= m) { val v = bottom(e); assertTrue("at $e: $v after $prev", v < prev); prev = v; e += 0.1f }
    }

    @Test fun theFoldIsContinuousAndSmooth() {
        assertEquals(s, bottom(m), 1e-4f)
        assertEquals(bottom(m - 1e-3f), bottom(m + 1e-3f), 1e-3f)
        // Flat on both sides of it: no kink.
        assertEquals(0f, (bottom(m + 0.01f) - bottom(m)) / 0.01f, 0.1f)
        assertEquals(0f, (bottom(m) - bottom(m - 0.01f)) / 0.01f, 0.1f)
    }

    @Test fun theEdgeReadsTheMirrorAndTheFoldTheStretch() {
        assertTrue("at the edge ${bottom(0f)}", bottom(0f) in 25f..30f)
        assertTrue("at the fold ${bottom(m)}", bottom(m) in 15f..20f)
        assertEquals(mf, bottom(0f), 1e-4f)
    }

    @Test fun theSideStretchesContentOutToTheScreensEdge() {
        assertEquals(15f, side(15f), 1e-4f)
        assertEquals(6f, side(0f), 1e-4f)
        assertEquals(20f, side(20f), 0f)
        var prev = side(0f)
        var e = 0.1f
        while (e <= 15f) { val v = side(e); assertTrue("at $e: $v after $prev", v > prev); prev = v; e += 0.1f }
        assertEquals(1f, (side(15f) - side(14.99f)) / 0.01f, 0.05f)
    }

    @Test fun aStandstillReleaseOpensPastTheMidpointBetweenIosFiftyAndSeventyFive() {
        val fling = 900f
        assertFalse(NcGlassProfile.releaseOpens(0.50f, 0f, fling))
        assertFalse(NcGlassProfile.releaseOpens(0.62f, 0f, fling))
        assertTrue(NcGlassProfile.releaseOpens(0.625f, 0f, fling))
        assertTrue(NcGlassProfile.releaseOpens(0.75f, 0f, fling))
    }

    @Test fun aFlingDecidesByItsDirection() {
        assertTrue(NcGlassProfile.releaseOpens(0.1f, 1200f, 900f))
        assertFalse(NcGlassProfile.releaseOpens(0.95f, -1200f, 900f))
        // Slower than a fling: the position decides.
        assertFalse(NcGlassProfile.releaseOpens(0.3f, 800f, 900f))
        assertTrue(NcGlassProfile.releaseOpens(0.9f, -800f, 900f))
    }
}
