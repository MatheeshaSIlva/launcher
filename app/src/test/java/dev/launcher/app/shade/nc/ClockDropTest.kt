package dev.launcher.app.shade.nc

import dev.launcher.app.Spring
import dev.launcher.app.design.Curve
import dev.launcher.app.design.Theme
import dev.launcher.app.design.Value
import dev.launcher.app.motion.Ease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** B-037 slice 2b: the glass clock dropping on a commit and the date coming in after it (docs/plans/B-037.md, AC8, AC10). */
class ClockDropTest {
    // The preset's own numbers (assets/motion/ios27.json: motion.nc.clock-drop, motion.nc.date-in).
    private val entries = Theme.parse(File("src/main/assets/motion/ios27.json").readText()).entries
    private val theme = Theme.parse(File("src/main/assets/themes/ios27.json").readText()).entries

    // Built from the files, so a change to the preset cannot leave these timing tests green.
    private fun curve(key: String) = (entries.getValue(key).value as Value.CurveV).curve
    private fun drop(): Spring = (curve("motion.nc.clock-drop") as Curve.Spring).let { Spring(it.response, it.damping) }
    private fun date(): Ease = (curve("motion.nc.date-in") as Curve.Ease).let { Ease(it.x1, it.y1, it.x2, it.y2, it.ms / 1000f) }
    private val dropPt = (theme.getValue("comp.nc.clock.drop").value as Value.Number).v

    @Test fun thePresetHoldsTheseCurves() {
        assertTrue("motion.nc.clock-drop" in entries.keys)
        assertTrue("motion.nc.date-in" in entries.keys)
        assertTrue("comp.nc.clock.drop" in theme.keys)
    }

    @Test fun theOffsetIsTheDropTimesWhatIsLeft() {
        assertEquals(160f, dropPt, 0f)
        assertEquals(-dropPt, ClockDrop.offset(1f, dropPt), 0f)
        assertEquals(0f, ClockDrop.offset(0f, dropPt), 0f)
        assertEquals(-dropPt / 2f, ClockDrop.offset(0.5f, dropPt), 1e-4f)
    }

    @Test fun theNumeralsDropMostOfItInAbout170ms() {
        val s = drop().apply { start(1f, 0f, 0f) }
        val done = { ms: Int -> 1f - s.value(ms / 1000.0) }
        assertTrue("at 170 ms: ${done(170)}", done(170) in 0.85f..0.93f)
        assertTrue("monotone, no overshoot", (0..600 step 10).all { s.value(it / 1000.0) in 0f..1f })
        assertTrue("a soft tail: still moving at 300 ms", abs(s.value(0.3) - 0f) > 1e-3f)
        assertTrue("rests within half a pixel of 450 by 570 ms", s.value(0.57) * 450f < 0.5f)
    }

    @Test fun theDateStaysUnderATenthForNinetyMsThenArrives() {
        val e = date().apply { start(0f, 0f, 1f) }
        val a = { ms: Int -> ClockDrop.dateAlpha(e.value(ms / 1000.0), 1f) }
        for (ms in 0..90) assertTrue("at $ms ms: ${a(ms)}", a(ms) < 0.1f)
        assertTrue("under way by 125 ms: ${a(125)}", a(125) in 0.5f..0.9f)
        assertEquals(1f, a(150), 1e-4f)
        // It never goes back down once it comes.
        var prev = 0f
        for (ms in 0..150) { val v = a(ms); assertTrue("at $ms ms", v >= prev - 1e-4f); prev = v }
    }

    @Test fun theDateFollowsTheClocksPresenceAndNeverLeavesZeroToOne() {
        assertEquals(0f, ClockDrop.dateAlpha(-0.4f, 1f), 0f)
        assertEquals(1f, ClockDrop.dateAlpha(1.2f, 1f), 0f)
        assertEquals(0.5f, ClockDrop.dateAlpha(1f, 0.5f), 0f)
    }

    @Test fun aCloseMidDropTakesTheClockOnWithoutAJump() {
        // Retargeted at 80 ms (towards the same rest, or back above the sheet): the offset starts exactly where it was and
        // moves on at the speed it had.
        val first = drop().apply { start(1f, 0f, 0f) }
        val t = 0.08
        val at = first.value(t)
        val v = first.velocity(t)
        for (to in listOf(0f, 1f)) {
            val next = drop().apply { start(at, v, to) }
            assertEquals("position at the retarget", at, next.value(0.0), 1e-5f)
            assertEquals("speed at the retarget", v, next.velocity(0.0), 1e-3f)
            // One 120 Hz frame later: the step is the speed's, within what the spring's pull adds.
            val step = abs(next.value(1 / 120.0) - at) * 450f
            assertTrue("a step of $step px", step < abs(v) * 450f / 120f * 1.5f + 1f)
        }
    }
}
