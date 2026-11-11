package dev.launcher.engine.ui.motion

import dev.launcher.app.design.Curve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** T-009 13a-3b: the roll's glyphs, by characters and whole, staggered, retargeted mid-roll, bounded. */
class RollMotionTest {
    private val ease = Curve.Ease(0f, 0f, 0.58f, 1f, 200f)
    private val frame = 8_333_333L
    private val eps = 0.01f
    /** Tabular positions: every character 10 px. */
    private fun at(s: String) = FloatArray(s.length + 1) { it * 10f }

    private fun RollMotion.settle(from: Long): Long { var t = from; while (step(t, eps)) t += frame; return t }
    private fun RollMotion.resting() = glyphs.filter { !it.track.moving && it.y == 0f }.map { it.text }

    @Test fun onlyTheChangedCharactersRollTheOthersStayStill() {
        val r = RollMotion()
        r.to("09:58", at("09:58"), true, ease, 0, 0)
        r.to("09:59", at("09:59"), true, ease, 0, 0)
        r.step(frame * 6, eps)
        val moving = r.glyphs.filter { it.track.moving }
        assertEquals(listOf("8", "9"), moving.map { it.text })
        assertEquals("the out glyph goes up for a larger number", true, moving[0].y < 0f && moving[1].y > 0f)
        assertEquals(listOf("0", "9", ":", "5"), r.resting())
        r.settle(frame * 6)
        assertEquals(listOf("0", "9", ":", "5", "9"), r.glyphs.map { it.text })
        assertTrue(r.glyphs.all { it.y == 0f && it.alpha == 1f })
    }

    @Test fun changedCharactersStartAStaggerApartInOrder() {
        val r = RollMotion()
        r.to("09:59", at("09:59"), true, ease, 0, 0)
        r.to("10:00", at("10:00"), true, ease, 24_000_000, 0)
        r.step(30_000_000, eps)
        val arriving = r.glyphs.filter { !it.leaving && it.slot >= 0 && it.text != ":" }.sortedBy { it.slot }
        assertEquals(listOf(0, 1, 3, 4), arriving.map { it.slot })
        // At 30 ms: the first two have started (0, 24 ms), the last two not yet (48, 72 ms).
        assertTrue(arriving[0].y < arriving[1].y)
        assertEquals(1f, arriving[2].y, 0f); assertEquals(1f, arriving[3].y, 0f)
    }

    @Test fun aSmallerNumberRollsDownAndALengthChangeRollsWhole() {
        val r = RollMotion()
        r.to("23:59", at("23:59"), true, ease, 0, 0)
        r.to("0:00", at("0:00"), true, ease, 0, 0)
        r.step(frame * 4, eps)
        val (out, `in`) = r.glyphs.partition { it.leaving }
        assertEquals("everything showing leaves", 5, out.size)
        assertTrue(out.all { it.y > 0f }); assertTrue(`in`.all { it.y < 0f })
        assertEquals(-1f, RollMotion.direction("23:59", "0:00"), 0f)
        assertEquals(1f, RollMotion.direction("9", "10"), 0f)
        assertEquals(1f, RollMotion.direction("on", "off"), 0f)
    }

    @Test fun anUnverifiedStringOrAMovedCharacterRollsWhole() {
        val r = RollMotion()
        r.to("10%", at("10%"), true, ease, 0, 0)
        r.to("11%", at("11%"), false, ease, 0, 0)
        assertEquals(listOf("11%"), r.glyphs.filter { !it.leaving }.map { it.text })
        val s = RollMotion()
        s.to("10", floatArrayOf(0f, 10f, 20f), true, ease, 0, 0)
        s.to("11", floatArrayOf(0f, 6f, 12f), true, ease, 0, 0)       // the unchanged 1 would move: whole
        assertEquals(2, s.glyphs.count { it.leaving })
        assertEquals(listOf(0, 1), s.glyphs.filter { !it.leaving }.map { it.slot })
    }

    @Test fun aChangeMidRollTurnsEachGlyphFromWhereItIsAndKeepsWhatLeaves() {
        val r = RollMotion()
        r.to("1", at("1"), true, ease, 0, 0)
        r.to("2", at("2"), true, ease, 0, 0)
        val t = frame * 8
        r.step(t, eps)
        val arriving = r.glyphs.single { !it.leaving }
        val before = arriving.y
        r.to("3", at("3"), true, ease, 0, t)
        r.step(t, eps)
        assertTrue("it turns from where it was", abs(arriving.y - before) < 1e-4f)
        assertTrue(arriving.leaving)
        assertEquals("the first one still leaving, the second turned out, the third arriving", 3, r.glyphs.size)
        r.step(t + frame, eps)
        assertTrue("on its way out, not jumped", abs(arriving.y - before) < 0.2f)
        // Many changes in a row: at most two leave per slot.
        for (k in 4..9) r.to("$k", at("$k"), true, ease, 0, t + k)
        assertTrue(r.glyphs.count { it.leaving } <= RollMotion.MAX_LEAVING)
        r.settle(t + 10)
        assertEquals(listOf("9"), r.glyphs.map { it.text })
        assertFalse(r.moving)
    }

    @Test fun aGlyphThatNeverStartedArrivingIsNotRolledOut() {
        val r = RollMotion()
        r.to("10", at("10"), true, ease, 0, 0)
        r.to("21", at("21"), true, ease, 100_000_000, 0)   // the second character waits 100 ms
        r.step(frame, eps)
        r.to("32", at("32"), true, ease, 0, frame)          // before it started
        r.step(frame * 2, eps)
        assertTrue("the unseen '1' went at once", r.glyphs.none { it.text == "1" && it.leaving && it.slot == 1 && it.y < 1f && it.y > -1f && it.track.moving })
    }

    @Test fun snapEndsTheRollAtRest() {
        val r = RollMotion()
        r.to("1", at("1"), true, ease, 0, 0)
        r.to("2", at("2"), true, ease, 0, 0)
        r.step(frame * 3, eps)
        r.snap()
        assertEquals(listOf("2"), r.glyphs.map { it.text })
        assertFalse(r.moving)
        assertEquals(0f, r.glyphs[0].y, 0f)
    }

    @Test fun withoutACurveItChangesAtOnce() {
        val r = RollMotion()
        r.to("1", at("1"), true, ease, 0, 0)
        r.to("2", at("2"), true, null, 0, 0)
        assertEquals(listOf("2"), r.glyphs.map { it.text }); assertFalse(r.moving)
    }
}
