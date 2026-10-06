package dev.launcher.app.home

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

/** The clock's distance transform against brute force (every pixel to every target). */
class EdtTest {
    private fun brute(w: Int, h: Int, target: BooleanArray): FloatArray = FloatArray(w * h) { i ->
        var best = Float.MAX_VALUE
        for (j in 0 until w * h) if (target[j]) {
            val dx = (i % w - j % w).toFloat(); val dy = (i / w - j / w).toFloat()
            best = minOf(best, sqrt(dx * dx + dy * dy))
        }
        best
    }

    private fun check(w: Int, h: Int, target: BooleanArray) {
        val fast = Edt.distances(w, h) { target[it] }
        val slow = brute(w, h, target)
        for (i in 0 until w * h) assertEquals("pixel ${i % w},${i / w}", slow[i], fast[i], 1e-3f)
    }

    @Test fun randomShapes() {
        val r = java.util.Random(7)
        repeat(20) {
            val w = 5 + r.nextInt(40); val h = 5 + r.nextInt(30)
            val t = BooleanArray(w * h) { r.nextFloat() < 0.08f }
            t[r.nextInt(w * h)] = true
            check(w, h, t)
        }
    }

    @Test fun singlePointAndStroke() {
        val w = 31; val h = 17
        check(w, h, BooleanArray(w * h) { it == 8 * w + 15 })
        check(w, h, BooleanArray(w * h) { it % w in 10..13 })   // a vertical stroke
        check(w, h, BooleanArray(w * h) { it / w == 0 || it % w == w - 1 })
    }
}
