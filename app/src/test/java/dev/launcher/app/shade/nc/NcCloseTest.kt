package dev.launcher.app.shade.nc

import dev.launcher.app.design.Curve
import dev.launcher.app.design.Theme
import dev.launcher.app.design.Value
import dev.launcher.app.motion.Ease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** B-037 step 2, slice 2c1: Notification Center's close (docs/plans/B-037-step2.md, AC10), read from the preset and theme files. */
class NcCloseTest {
    private val motion = Theme.parse(File("src/main/assets/motion/ios27.json").readText()).entries
    private val theme = Theme.parse(File("src/main/assets/themes/ios27.json").readText()).entries

    private fun ease(key: String): Ease = ((motion.getValue(key).value as Value.CurveV).curve as Curve.Ease)
        .let { Ease(it.x1, it.y1, it.x2, it.y2, it.ms / 1000f) }
    private fun num(key: String) = (theme.getValue(key).value as Value.Number).v

    /** The numerals' box bottom at rest across the top (pt): [LockClockBlock.box]'s. */
    private val restBottomPt = LockClockBlock.NUMERALS_BOTTOM_PT

    @Test fun theFilesHoldTheCloseRolesAndTokens() {
        for (k in listOf("motion.nc.clock-lift", "motion.nc.date-out", "motion.nc.close-uncover")) assertTrue(k, k in motion.keys)
        for (k in listOf("comp.nc.clock.lift", "comp.nc.close.drift")) assertTrue(k, k in theme.keys)
        // At least the numerals' rest top plus their height (110 + 96 pt): they leave through the screen's top.
        assertTrue(num("comp.nc.clock.lift") >= 206f)
    }

    @Test fun theNumeralsLiftAtOnceAndAreAboveTheTopBy300ms() {
        val lift = num("comp.nc.clock.lift")
        val e = ease("motion.nc.clock-lift").apply { start(0f, 0f, 1f) }
        val bottom = { ms: Int -> restBottomPt + ClockDrop.lifted(e.value(ms / 1000.0), lift) }
        assertEquals(0f, ClockDrop.lifted(e.value(0.0), lift), 0f)
        // Off within ~2 frames (60 Hz): moving by 33 ms.
        assertTrue("moving by 33 ms: ${bottom(33)}", bottom(33) < restBottomPt - 1f)
        val gone = (0..600).first { bottom(it) < 0f }
        assertTrue("the numerals' bottom passes the screen's top at $gone ms", gone in 250..350)
        // Never back down on the way.
        var prev = restBottomPt
        for (ms in 0..400) { val b = bottom(ms); assertTrue("at $ms ms", b <= prev + 1e-3f); prev = b }
    }

    @Test fun theDateAndTheBarAreGoneBy175ms() {
        val e = ease("motion.nc.date-out").apply { start(1f, 0f, 0f) }
        val a = { ms: Int -> ClockDrop.dateAlpha(e.value(ms / 1000.0), 1f) }
        val gone = (0..400).first { a(it) <= 0.05f }
        assertTrue("under 5 % at $gone ms", gone in 145..205)
        assertTrue("still there at 50 ms: ${a(50)}", a(50) > 0.5f)
    }

    @Test fun theWallpaperDriftsByTheTokenTimesTheEdgesTravel() {
        val k = num("comp.nc.close.drift")
        assertEquals(0.14f, k, 1e-6f)
        val h = 2400f
        assertEquals(0f, NcClose.drift(h, h, k), 0f)
        assertEquals(0.14f * 1000f, NcClose.drift(h - 1000f, h, k), 1e-3f)
        assertEquals(0.14f * h, NcClose.drift(0f, h, k), 1e-3f)
        // An edge below where the close took it from (a reversal landing further down) never drifts it upwards.
        assertEquals(0f, NcClose.drift(h + 50f, h, k), 0f)
    }

    @Test fun theDriftIsNothingAtTheClosesFirstFrameWhereverTheEdgeWas() {
        val k = num("comp.nc.close.drift")
        // Counted from the edge at the close's start: a close ~50 ms after a commit (the edge still landing) never jumps.
        for (from in listOf(2400f, 2000f, 1920f, 1200f, 300f)) {
            assertEquals("from $from", 0f, NcClose.drift(from, from, k), 0f)
            assertEquals("from $from", k * 100f, NcClose.drift(from - 100f, from, k), 1e-3f)
        }
    }

    @Test fun theDriftLastsFromTheCloseUntilTheSheetIsBackDown() {
        assertTrue(NcClose.drifts(closing = true, was = false, landed = true))
        // Reversed mid-way: it goes on (no jump) until the sheet has landed again.
        assertTrue(NcClose.drifts(closing = false, was = true, landed = false))
        assertFalse(NcClose.drifts(closing = false, was = true, landed = true))
        assertFalse(NcClose.drifts(closing = false, was = false, landed = false))
    }

    @Test fun overTheLockScreenTheDimClearsWithTheSheet() {
        val h = 2400f
        assertEquals(1f, NcClose.lockDim(1f, h, h), 0f)
        assertEquals(0.5f, NcClose.lockDim(1f, h / 2f, h), 1e-6f)
        assertEquals(0f, NcClose.lockDim(1f, 0f, h), 0f)
        // Counted from where the close took the edge: whole at its first frame, whatever that edge was.
        assertEquals(1f, NcClose.lockDim(1f, 1900f, 1900f), 0f)
        // A reversal landing below that start stays whole (no step as the drift ends).
        assertEquals(1f, NcClose.lockDim(1f, h, 1900f), 0f)
    }
}
