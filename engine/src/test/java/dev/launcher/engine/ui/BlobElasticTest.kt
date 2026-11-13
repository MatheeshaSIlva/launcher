package dev.launcher.engine.ui

import dev.launcher.engine.ui.BlobPainter.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-015: the mitosis: tension while joined, a late break, squash after it, rest. */
class BlobElasticTest {
    private val pill = Shape(0f, 0f, 132f, 32f, 16f)
    private fun bud(top: Float) = Shape(-124f, top, 256f, top + 76f, 26f)
    private val frame = 8_333_333L

    /** Moves the bud's top from [from] to [to] (px below the pill's top) over [frames], stepping at 120 Hz. */
    private fun run(e: BlobElastic, from: Float, to: Float, frames: Int, start: Long, log: MutableList<Triple<Float, Float, Boolean>>): Long {
        var t = start
        for (i in 0..frames) {
            val y = from + (to - from) * i / frames
            e.step(pill, bud(y), 16f, t)
            log += Triple(y - 32f, e.parent, e.joined)
            t += frame
        }
        return t
    }

    @Test fun pulledApartItStretchesThenBreaksLateThenSquashesAndSettles() {
        val e = BlobElastic()
        val log = ArrayList<Triple<Float, Float, Boolean>>()
        // From inside the pill to 40 px under it over half a second.
        var t = run(e, 12f, 72f, 60, 0, log)
        val stretched = log.filter { it.third }.maxOf { it.second }
        assertTrue("the pill stretched toward the bud while joined ($stretched px)", stretched > 2f)
        val breakGap = log.zipWithNext().first { (a, b) -> a.third && !b.third }.second.first
        assertTrue("it broke late, the gap well past half of smooth (8 px): $breakGap", breakGap > 16f)
        // Held there: it springs back past its rest (squash) and settles.
        var least = 0f
        var settled = -1
        for (i in 0 until 240) {
            e.step(pill, bud(72f), 16f, t); t += frame
            least = minOf(least, e.parent)
            if (settled < 0 && !e.step(pill, bud(72f), 16f, t)) settled = i
        }
        assertTrue("squashed after the break ($least px)", least < -0.5f)
        assertTrue("settled within two seconds", settled in 0..239)
        assertEquals(0f, e.parent, 0.06f)
        assertFalse(e.joined)
    }

    @Test fun deformKeepsTheCentreAndStretchesTheFacingEdges() {
        val e = BlobElastic()
        var t = 0L
        e.step(pill, bud(20f), 16f, t); t += frame                       // overlapping: joined
        repeat(60) { e.step(pill, bud(40f), 16f, t); t += frame }     // then drawn 8 px apart: pulled, still joined
        val d = e.deform(listOf(pill, bud(40f)))
        assertEquals("the pill's top stays", 0f, d[0].top, 0f)
        assertTrue("its bottom moves down", d[0].bottom > 32f)
        assertEquals("its centre stays", 66f, (d[0].left + d[0].right) / 2f, 0.01f)
        assertTrue("it narrows", d[0].right - d[0].left < 132f)
        assertTrue("the bud is held back: its top moves up", d[1].top < 40f)
        assertTrue("and its bottom too (held as one piece)", d[1].bottom < 116f)
        assertTrue("a neck joins them while joined across the gap", d.size == 3)
    }

    @Test fun deformKeepsEachShapesColour() {
        val e = BlobElastic()
        var t = 0L
        val a = pill.copy(color = 0xFF000000.toInt()); val b = bud(20f).copy(color = 0xC2121212.toInt())
        e.step(a, b, 16f, t); t += frame
        repeat(30) { e.step(a, bud(44f).copy(color = b.color), 16f, t); t += frame }
        val d = e.deform(listOf(a, bud(44f).copy(color = b.color)))
        assertEquals(a.color, d[0].color)
        assertEquals(b.color, d[1].color)
    }

    @Test fun withNoBudNothingMoves() {
        val e = BlobElastic()
        assertFalse(e.step(pill, null, 16f, 0))
        assertFalse(e.step(pill, null, 16f, frame))
        assertEquals(listOf(pill), e.deform(listOf(pill)))
    }
}
