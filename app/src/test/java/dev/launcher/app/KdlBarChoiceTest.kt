package dev.launcher.app

import dev.launcher.app.statusbar.KdlBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T-012: the developer switch shows only allowlisted components, each with a file that ships. */
class KdlBarChoiceTest {
    /** D-120: the detailed banner needs Android 13; below it the minimal pill shows. */
    @Test fun detailedModeIsMinimalBelowAndroid13() {
        assertEquals("minimal", KdlBar.shownMode("detailed", 32))
        assertEquals("detailed", KdlBar.shownMode("detailed", 33))
        assertEquals("minimal", KdlBar.shownMode("minimal", 35))
    }

    /** The banner fits the screen: 380 dp, or the screen less 16 dp each side. */
    @Test fun bannerFitsNarrowScreens() {
        assertEquals(380f, KdlBar.bannerWidth(430f), 0f)
        assertEquals("the S24 (411 dp): 16 dp each side", 379f, KdlBar.bannerWidth(411f), 0f)
        assertEquals(328f, KdlBar.bannerWidth(360f), 0f)
        assertEquals(0f, KdlBar.bannerWidth(20f), 0f)
    }

    /** D-120: locked, Android's own rules: nothing for a secret notification or with the lock screen's notifications off. */
    @Test fun lockedArrivalsFollowAndroidsRules() {
        val arrivals = dev.launcher.app.statusbar.KdlArrivals
        assertTrue(arrivals.lockedShows(android.app.Notification.VISIBILITY_PRIVATE, true))
        assertTrue(arrivals.lockedShows(android.app.Notification.VISIBILITY_PUBLIC, true))
        assertEquals(false, arrivals.lockedShows(android.app.Notification.VISIBILITY_SECRET, true))
        assertEquals(false, arrivals.lockedShows(android.app.Notification.VISIBILITY_PRIVATE, false))
    }

    /** The hidden island's dot: the camera's hole and 3 dp round it; none without a hole. */
    @Test fun theDotIsTheCameraAndAMargin() {
        assertEquals(30f, KdlBar.cameraSize(24f), 0f)
        assertEquals(0f, KdlBar.cameraSize(null), 0f)
        assertEquals(48f, KdlBar.cameraSize(200f), 0f)
    }

    /** Sideways the island stays away (no camera at the top), upright it follows the bar. */
    @Test fun theIslandShowsOnlyUpright() {
        assertTrue(KdlBar.islandShown(true, true))
        assertEquals(false, KdlBar.islandShown(true, false))
        assertEquals(false, KdlBar.islandShown(false, true))
    }

    @Test fun noNameIsTheStatusBar() {
        assertEquals(KdlBar.STATUS_BAR, KdlBar.choose(null))
        assertEquals(KdlBar.STATUS_BAR, KdlBar.choose(""))
        assertEquals(KdlBar.STATUS_BAR, KdlBar.choose("  "))
    }

    @Test fun allowedNamesAreThemselves() {
        assertEquals(KdlBar.STATUS_BAR, KdlBar.choose("status-bar"))
        assertEquals(KdlBar.ISLAND, KdlBar.choose("island"))
    }

    @Test fun anythingElseIsRefused() {
        for (n in listOf("Island", "island ", "../island", "kdl/components/island.kdl", "shade", "status_bar")) assertNull(n, KdlBar.choose(n))
    }

    @Test fun everyAllowedComponentShips() {
        val assets = listOf("../engine", "engine").map { File(it, "src/main/assets") }.first { it.exists() }
        for ((name, file) in KdlBar.COMPONENTS) assertTrue("$name: $file", File(assets, file).isFile)
    }
}
