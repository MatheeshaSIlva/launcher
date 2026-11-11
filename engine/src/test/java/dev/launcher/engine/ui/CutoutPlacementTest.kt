package dev.launcher.engine.ui

import dev.launcher.engine.ui.CutoutPlacement.Cutout
import dev.launcher.engine.ui.CutoutPlacement.Place
import org.junit.Assert.assertEquals
import org.junit.Test

/** T-010: the placement on the camera, StatusBarView's rule (`centreLine`, `cameraLeft`). */
class CutoutPlacementTest {
    private val bar = 100f
    private fun at(c: Cutout?, ox: Float = 0f, oy: Float = 0f, w: Float = 1080f) = CutoutPlacement.of(c, bar, ox, oy, w)

    @Test fun withoutACutoutTheWindowsCentreAndFiftyTwoPercent() = assertEquals(Place(540f, 52f), at(null))

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
        // A window 100 px in and 40 down: the cutout's centre (540, 50) is (440, 10) in it; 10 is under 20 % of the bar.
        assertEquals(Place(440f, 52f), at(Cutout(520f, 30f, 560f, 70f), ox = 100f, oy = 40f))
        assertEquals(Place(440f, 30f), at(Cutout(520f, 50f, 560f, 90f), ox = 100f, oy = 40f))
    }

    @Test fun aSideCutoutIsNoTopCutoutForTheCaller() =
        // Landscape: the source reports no top cutout (its side cutout is not on the top edge); the fallback holds.
        assertEquals(Place(1170f, 52f), at(null, w = 2340f))
}
