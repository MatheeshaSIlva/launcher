package dev.launcher.engine.ui

import dev.launcher.engine.ui.CutoutPlacement.Cutout
import dev.launcher.engine.ui.CutoutPlacement.Place
import org.junit.Assert.assertEquals
import org.junit.Test

/** T-010: the placement on the camera, StatusBarView's rule (`centreLine`, `cameraLeft`). */
class CutoutPlacementTest {
    private val bar = 100f
    private fun at(c: Cutout?, ox: Float = 0f, oy: Float = 0f, w: Float = 1080f) = CutoutPlacement.of(c, bar, ox, oy, w / 2f)

    @Test fun withoutACutoutTheWindowsCentreAndFiftyTwoPercent() = assertEquals(Place(540f, 52f), at(null))

    /** T-012: `place-gap`. A 84 px pill (centre 42) on a hole whose centre is 25 px down: its top would be -17. */
    @Test fun aGapKeepsTheRootOffTheScreensTop() {
        val p = at(Cutout(520f, 5f, 560f, 45f))                       // centre 25, inside the band
        assertEquals("no gap: centred, above the screen", -17f, p.top(42f, null), 0f)
        assertEquals("a gap of 0: at the screen's top", 0f, p.top(42f, 0f), 0f)
        assertEquals("a 21 px gap: 21 below the top", 21f, p.top(42f, 21f), 0f)
        assertEquals("a gap it already clears: centred", 50f - 42f, at(Cutout(520f, 30f, 560f, 70f)).top(42f, 5f), 0f)
        // Seen from a view lower on the screen: the screen's top is above it.
        val q = at(Cutout(520f, 5f, 560f, 45f), oy = 40f)
        assertEquals(-40f + 21f, q.top(42f, 21f), 0f)
    }

    @Test fun aCentredHoleIsTheCentreAndItsLine() = assertEquals(Place(540f, 50f), at(Cutout(520f, 30f, 560f, 70f)))

    @Test fun aCornerCutoutKeepsItsOwnCentre() = assertEquals(Place(60f, 40f), at(Cutout(0f, 0f, 120f, 80f)))

    @Test fun aWideNotchIsItsCentre() = assertEquals(Place(540f, 45f), at(Cutout(340f, 0f, 740f, 90f)))

    @Test fun outsideTheBandTheLineFallsBackButTheCentreStays() {
        assertEquals("below 20 %", Place(540f, 52f), at(Cutout(530f, 0f, 550f, 30f)))      // centre 15
        assertEquals("above 80 %", Place(540f, 52f), at(Cutout(530f, 70f, 550f, 110f)))    // centre 90
    }

    @Test fun theBandIsStrictAtTwentyAndEightyPercent() {
        assertEquals("exactly 20 %", 52f, at(Cutout(530f, 10f, 550f, 30f)).centreY, 0f)     // centre 20
        assertEquals("exactly 80 %", 52f, at(Cutout(530f, 70f, 550f, 90f)).centreY, 0f)     // centre 80
        assertEquals("just inside", 20.5f, at(Cutout(530f, 10.5f, 550f, 30.5f)).centreY, 0f)
    }

    @Test fun aViewNotAtTheScreensOriginTakesItsPlaceOff() {
        // A view 100 px in and 40 down: the band is the screen's; the cutout's centre (540, 50) is (440, 10) in the view.
        assertEquals(Place(440f, 10f), at(Cutout(520f, 30f, 560f, 70f), ox = 100f, oy = 40f))
        assertEquals("the fallback line too", Place(440f, 12f), at(null, ox = 100f, oy = 40f))
    }

    @Test fun aSideCutoutIsNoTopCutoutForTheCaller() =
        // Landscape: the source reports no top cutout (its side cutout is not on the top edge); the fallback holds.
        assertEquals(Place(1170f, 52f), at(null, w = 2340f))
}
