package dev.launcher.app.shade.nc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-037 slice 1a: the clear slab's edge profile and its release threshold (docs/plans/B-037.md AC1, AC2). */
class NcGlassProfileTest {
    // The tokens' values (comp.nc.glass.band, mirror, stretch-from, mirror-from, side, side-from).
    private val d = 26f
    private val m = 5f
    private val s = 18f
    private val mf = 36f
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
        assertTrue("at the edge ${bottom(0f)}", bottom(0f) in 22f..40f)
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

    @Test fun theContentWaitsOffScreenWhileClearAndFadesInOnReveal() {
        assertEquals(0f, NcGlassProfile.contentAlpha(0f, 0f), 0f)
        assertEquals(0f, NcGlassProfile.contentAlpha(0.001f, 1f), 0f)
        assertFalse(NcGlassProfile.contentWaits(0f, 0f))
        assertEquals(1f, NcGlassProfile.contentAlpha(0.5f, 0f), 0f)
        assertTrue(NcGlassProfile.contentWaits(0.5f, 0f))
        assertEquals(0.3f, NcGlassProfile.contentAlpha(0.5f, 0.3f), 0f)
        assertFalse(NcGlassProfile.contentWaits(0.5f, 0.3f))
        assertEquals(1f, NcGlassProfile.contentAlpha(1f, 1f), 0f)
        // (Over the lock screen too, since step 2: the slab's wallpaper lies still under it, the same picture.)
        assertEquals(0.05f, NcGlassProfile.contentAlpha(0.5f, 0.05f), 0f)
        assertEquals(0f, NcGlassProfile.contentAlpha(0f, 0.5f), 0f)
        assertTrue(NcGlassProfile.contentWaits(0.5f, 0f))
    }

    @Test fun withAPictureOrOnTheLockScreenTheContentIsWholeFromTheCommit() {
        // (B-037 step 2, slice 2a2: what was behind gives way over it instead.)
        assertEquals(1f, NcGlassProfile.contentAlpha(0.76f, 0.01f, whole = true), 0f)
        assertEquals(1f, NcGlassProfile.contentAlpha(0.5f, 0f, whole = true), 0f)
        assertEquals(0f, NcGlassProfile.contentAlpha(0f, 0.5f, whole = true), 0f)
        assertEquals(0.01f, NcGlassProfile.contentAlpha(0.76f, 0.01f, whole = false), 0f)
        // On screen from the commit's first frame, before the reveal has moved (round 2's review).
        assertFalse(NcGlassProfile.contentWaits(0.76f, 0f, whole = true))
        assertTrue(NcGlassProfile.contentWaits(0.76f, 0f, whole = false))
    }

    // B-037 step 2 (docs/plans/B-037.md slice 2a1): the content is still under a moving outline.
    private val tok = 135f
    private val disp = 120f
    private val h = 2340f

    @Test fun theContentIsStillOnceTheSheetShowsAndParkedWhileClosed() {
        assertFalse(NcGlassProfile.contentStill(0f))
        assertFalse(NcGlassProfile.contentStill(0.002f))
        assertTrue(NcGlassProfile.contentStill(0.003f))
        assertTrue(NcGlassProfile.contentStill(0.75f))
        assertTrue(NcGlassProfile.contentStill(1.3f))
    }

    @Test fun theOutlineKeepsTheTokensRadiusInTheAirAndMeetsTheDisplaysAtTheBottom() {
        assertEquals(tok, NcGlassProfile.outlineRadius(0f, h, tok, disp), 1e-3f)
        assertEquals(tok, NcGlassProfile.outlineRadius(h * 0.5f, h, tok, disp), 1e-3f)
        assertEquals(disp, NcGlassProfile.outlineRadius(h, h, tok, disp), 1e-3f)
        // (Past the bottom, as the rubber band's edge is clamped, it stays the display's.)
        assertEquals(disp, NcGlassProfile.outlineRadius(h + 50f, h, tok, disp), 1e-3f)
    }

    @Test fun theRadiusEasesMonotonicallyToTheDisplaysBothWays() {
        for ((t, d) in listOf(135f to 120f, 135f to 190f, 135f to 0f)) {
            var prev = NcGlassProfile.outlineRadius(0f, h, t, d)
            var y = 0f
            while (y <= h) {
                val r = NcGlassProfile.outlineRadius(y, h, t, d)
                if (d >= t) assertTrue("at $y: $r after $prev", r >= prev - 1e-3f) else assertTrue("at $y: $r after $prev", r <= prev + 1e-3f)
                assertTrue("never negative at $y: $r", r >= 0f)
                prev = r
                y += 5f
            }
            assertEquals(d, prev, 1e-3f)
        }
    }

    @Test fun theEdgesLookFallsAwayAsTheSheetLandsAndOnlyThen() {
        assertEquals(1f, NcGlassProfile.chrome(0f, h, tok), 0f)
        assertEquals(1f, NcGlassProfile.chrome(h - 2f * tok, h, tok), 0f)
        assertEquals(0f, NcGlassProfile.chrome(h, h, tok), 0f)
        assertEquals(0.5f, NcGlassProfile.chrome(h - tok, h, tok), 1e-4f)
        var prev = 1f
        var y = 0f
        while (y <= h) { val c = NcGlassProfile.chrome(y, h, tok); assertTrue("at $y", c <= prev + 1e-6f); prev = c; y += 5f }
    }

    @Test fun theLensPictureGoesOnlyOnceTheSheetHasCommittedAndLanded() {
        // Held at the screen's bottom (chrome 0) and moved back up: the picture is kept.
        assertFalse(NcGlassProfile.releasesPicture(0f, committed = false))
        assertFalse(NcGlassProfile.releasesPicture(0.5f, committed = true))
        assertTrue(NcGlassProfile.releasesPicture(0f, committed = true))
        assertTrue(NcGlassProfile.releasesPicture(0.001f, committed = true))
    }

    @Test fun anEdgeAtTheBottomHasLandedAndNothingElseHas() {
        assertTrue(NcGlassProfile.landed(h, h))
        assertTrue(NcGlassProfile.landed(h - 0.4f, h))
        assertFalse(NcGlassProfile.landed(h - 1f, h))
        assertFalse(NcGlassProfile.landed(0f, h))
    }

    @Test fun aSideBandOfNoWidthReadsItself() {
        assertEquals(0f, NcGlassProfile.sideSampleDist(0f, 0f, 6f), 0f)
        assertEquals(3f, NcGlassProfile.sideSampleDist(3f, 0f, 6f), 0f)
        assertEquals(3f, NcGlassProfile.sideSampleDist(3f, -1f, 6f), 0f)
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

    @Test fun aStretchPastTheLimitIsClampedSoTheBandStaysMonotone() {
        val limit = NcGlassProfile.stretchLimit(d, m)
        assertTrue(s < limit)
        // A token past the limit, clamped by the caller (NcGlassEdge): still rising from the fold to the top.
        val from = minOf(24f, limit)
        var prev = NcGlassProfile.bottomSampleDist(m, d, m, from, mf)
        var e = m + 0.1f
        while (e <= d) { val v = NcGlassProfile.bottomSampleDist(e, d, m, from, mf); assertTrue("at $e: $v after $prev", v > prev); prev = v; e += 0.1f }
    }
}
