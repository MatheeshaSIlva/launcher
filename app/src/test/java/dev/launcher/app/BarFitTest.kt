package dev.launcher.app

import dev.launcher.app.statusbar.BarFit
import org.junit.Assert.assertEquals
import org.junit.Test

/** T-007: StatusBarView's rule for its left side. At unit 1: 34 + time + 7 is where the icons start; 3.4 + 4.5 stays free. */
class BarFitTest {
    private val start = 34f + 40f + 7f
    private fun roomFor(icons: Int) = start + icons * 14.5f + (icons - 1) * 4.5f + 4.5f + 3.4f + 4.5f - 4.5f + 8f

    @Test fun oneMoreIconIsNotOffered() {
        val camera = start + 14.5f + 3.4f + 4.5f + 8f
        assertEquals(1, BarFit.count(camera, 40f, moon = false, unitPx = 1f))
        assertEquals(0, BarFit.count(camera - 0.01f, 40f, moon = false, unitPx = 1f))
    }
    @Test fun neverMoreThanThree() = assertEquals(3, BarFit.count(10_000f, 40f, moon = false, unitPx = 1f))
    @Test fun noRoomNoIcons() = assertEquals(0, BarFit.count(0f, 40f, moon = false, unitPx = 1f))
    @Test fun aWiderTimeTakesAnIconAway() {
        val camera = start + 2 * 14.5f + 4.5f + 4.5f + 3.4f + 4.5f + 8f - 4.5f + 4.5f
        val n = BarFit.count(camera, 40f, moon = false, unitPx = 1f)
        assertEquals(n - 1, BarFit.count(camera, 40f + 19f, moon = false, unitPx = 1f))
    }
    @Test fun theMoonTakesItsRoom() {
        val camera = start + 14.5f + 3.4f + 4.5f + 8f
        assertEquals(0, BarFit.count(camera, 40f, moon = true, unitPx = 1f))
        assertEquals(1, BarFit.count(camera + 12.4f + 4.5f, 40f, moon = true, unitPx = 1f))
    }
    @Test fun itScalesWithTheUnit() {
        val camera = start + 14.5f + 3.4f + 4.5f + 8f
        assertEquals(1, BarFit.count(camera * 3f, 40f * 3f, moon = false, unitPx = 3f))
    }
}
