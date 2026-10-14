package dev.launcher.app.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthBlurTest {
    private val density = 2.625f   // the S24's

    @Test
    fun noBlurStaysNone() {
        assertEquals(0f, DepthBlur.homeRadius(0f, density), 0f)
    }

    @Test
    fun homeBlursMoreThanThePictureRadius() {
        // The picture's quarter-size copy looks blurrier than a full-size blur of the same radius: home's must be wider.
        for (r in listOf(8f, 15f, 36.75f)) assertTrue("radius $r", DepthBlur.homeRadius(r, density) > r)
    }

    @Test
    fun growsWithDepthWithoutJumps() {
        var last = 0f
        var r = 0f
        while (r <= 40f) {
            val h = DepthBlur.homeRadius(r, density)
            assertTrue("monotonic at $r", h >= last)
            assertTrue("no jump at $r", h - last < 3f)
            last = h
            r += 0.25f
        }
    }
}
