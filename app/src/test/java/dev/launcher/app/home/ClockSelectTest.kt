package dev.launcher.app.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** The clock's percentile by selection gives what a sort gives (ClockNumerals.build: the lens's half width). */
class ClockSelectTest {
    @Test fun matchesSort() {
        val r = java.util.Random(11)
        repeat(200) {
            val count = 1 + r.nextInt(400)
            // Many equal values too (distances inside a stroke repeat).
            val v = FloatArray(count + r.nextInt(20)) { if (r.nextBoolean()) r.nextInt(30).toFloat() else r.nextFloat() * 50f }
            val sorted = v.copyOf(count).sorted()
            val k = (count * 0.95f).toInt().coerceAtMost(count - 1)
            assertEquals(sorted[k], ClockNumerals.select(v, count, k), 0f)
        }
    }
}
