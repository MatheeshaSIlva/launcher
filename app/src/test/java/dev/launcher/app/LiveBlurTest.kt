package dev.launcher.app

import dev.launcher.app.shade.LiveBlur
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live blur's radius follows its strength on the scale a blur is seen by, Samsung's view blur and Android's alike. */
class LiveBlurTest {
    @Test fun noneAtZeroWholeAtOne() {
        assertEquals(0, LiveBlur.radiusAt(0f, 54f))
        assertEquals(54, LiveBlur.radiusAt(1f, 54f))
        assertEquals(0, LiveBlur.radiusAt(0.5f, 0f))
    }

    @Test fun halfAsStrongIsTheSquareRootOfTheWhole() {
        // log(1 + r) = k log(1 + max): at a half, 1 + r = sqrt(1 + max).
        assertEquals(Math.round(Math.sqrt(55.0) - 1.0).toInt(), LiveBlur.radiusAt(0.5f, 54f))
    }

    @Test fun aQuarterLeftIsALightBlur() {
        // In step with the strength it was 13 of 54, a heavy blur with the controls nearly gone.
        assertTrue(LiveBlur.radiusAt(0.25f, 54f) <= 2)
    }

    @Test fun strengthOfRadiusIsItsInverse() {
        for (max in listOf(8f, 54f, 150f)) for (i in 0..20) {
            val f = i / 20f
            assertEquals("max $max, fraction $f", max * f, LiveBlur.radiusAt(LiveBlur.strengthOfRadius(f, max), max).toFloat(), 0.501f)
        }
        assertEquals(0f, LiveBlur.strengthOfRadius(0f, 54f), 0f)
        assertEquals(1f, LiveBlur.strengthOfRadius(1f, 54f), 1e-6f)
    }
}
