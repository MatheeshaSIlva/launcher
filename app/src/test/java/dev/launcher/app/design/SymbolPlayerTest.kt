package dev.launcher.app.design

import dev.launcher.app.design.SymbolPlayer.Turn
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-036 slice 2: the player's state machine. Time never jumps; a turn mid-play reverses the play where it is; a crossfade fades back. */
class SymbolPlayerTest {
    private fun play(ms: Int) = SymbolMotion(JSONObject("""{"ms":$ms,"groups":[],"layers":[{"n":"l","g":-1,"d":"M0 0L1 1"}],"tracks":[]}"""))
    private val on = play(500)
    private val off = play(300)
    private val fade = 100f   // (the caller's: the test names a length, the player has none)

    @Test fun aForwardRunReachesRestOnTheNewLook() {
        val p = SymbolPlayer(on, off)
        assertTrue(p.atRest); assertFalse(p.restOn)
        assertEquals(Turn.PLAY, p.toward(true, fade))
        assertSame(on, p.motion); assertEquals(0f, p.timeMs, 0f); assertFalse(p.atRest)
        var t = 0f
        while (p.advance(8.33f)) { assertTrue(p.timeMs > t); t = p.timeMs }
        assertTrue(p.atRest); assertTrue(p.restOn); assertNull(p.motion)
        assertTrue("ran its length: $t", t > 490f && t < 500f)
        assertEquals(Turn.PLAY, p.toward(false, fade))
        assertSame(off, p.motion)
        while (p.advance(10f)) { }
        assertFalse(p.restOn)
    }

    @Test fun aZeroNegativeOrMissingFrameTimeChangesNothing() {
        val p = SymbolPlayer(on, off)
        p.toward(true, fade)
        assertTrue(p.advance(0f))                      // a play that has not moved is still playing, at its start
        assertFalse(p.atRest); assertEquals(0f, p.timeMs, 0f); assertFalse(p.restOn)
        assertTrue(p.advance(-5f)); assertTrue(p.advance(Float.NaN))
        assertEquals(0f, p.timeMs, 0f); assertFalse(p.atRest)
        p.advance(8f)
        p.toward(false, fade)                          // reversed: it ends only by moving back to 0
        assertEquals(8f, p.timeMs, 0f)
        assertTrue(p.advance(0f)); assertTrue(p.advance(-1f)); assertTrue(p.advance(Float.NaN))
        assertFalse(p.atRest); assertEquals(8f, p.timeMs, 0f)
        assertFalse(p.advance(8f)); assertTrue(p.atRest); assertFalse(p.restOn)
        assertFalse(p.advance(0f))                     // at rest: nothing to do
    }

    @Test fun aTurnRightAfterTheStartEndsOnTheStartLookOnTheNextFrame() {
        val p = SymbolPlayer(on, off)
        p.toward(true, fade)
        p.toward(false, fade)
        assertFalse(p.atRest)
        assertFalse(p.advance(8f)); assertFalse(p.restOn)
    }

    @Test fun aReversalAtFortyPercentIsContinuousAndReturnsToTheStartLook() {
        val p = SymbolPlayer(on, off)
        p.toward(true, fade)
        while (p.timeMs < 200f) p.advance(8f)
        val at = p.timeMs
        assertEquals(Turn.PLAY, p.toward(false, fade))
        assertEquals("time does not move at the turn", at, p.timeMs, 0f)
        assertSame("the same play, backwards", on, p.motion)
        assertEquals(0.4f, p.progress, 1e-3f)
        var last = at
        while (p.advance(8f)) { assertTrue(p.timeMs < last); last = p.timeMs }
        assertTrue(p.atRest); assertFalse("back on the look it started from", p.restOn)
    }

    @Test fun reversedAgainItRunsForwardFromWhereItIs() {
        val p = SymbolPlayer(on, off)
        p.toward(true, fade)
        repeat(30) { p.advance(8f) }     // 240
        p.toward(false, fade)
        repeat(10) { p.advance(8f) }     // 160
        val at = p.timeMs
        assertEquals(160f, at, 1e-3f)
        assertEquals(Turn.PLAY, p.toward(true, fade))
        assertEquals(at, p.timeMs, 0f)
        p.advance(8f)
        assertEquals(168f, p.timeMs, 1e-3f)
        while (p.advance(8f)) { }
        assertTrue(p.restOn)
    }

    @Test fun restOnStaysTheStartLookUntilTheRunEnds() {
        val p = SymbolPlayer(on, off)
        p.toward(true, fade)
        repeat(20) { p.advance(8f) }
        assertFalse(p.restOn); assertTrue(p.headingOn)
        p.toward(false, fade)
        assertFalse(p.restOn); assertFalse(p.headingOn)
    }

    @Test fun askingForWhereItIsHeadingChangesNothing() {
        val p = SymbolPlayer(on, off)
        assertEquals(Turn.NONE, p.toward(false, fade))
        p.toward(true, fade)
        repeat(10) { p.advance(8f) }
        assertEquals(Turn.NONE, p.toward(true, fade))
        assertEquals(80f, p.timeMs, 1e-3f)
        assertTrue(p.headingOn)
    }

    @Test fun anOffPlayReversedMidwayGoesBackToTheOnLook() {
        val p = SymbolPlayer(on, off, startOn = true)
        assertEquals(Turn.PLAY, p.toward(false, fade))
        assertSame(off, p.motion)
        repeat(15) { p.advance(8f) }
        assertFalse(p.headingOn)
        assertEquals(Turn.PLAY, p.toward(true, fade))
        assertTrue(p.headingOn)
        while (p.advance(8f)) { }
        assertTrue(p.restOn)
    }

    @Test fun aLookWithoutAPlayCrossfadesOverTheCallersTime() {
        // Bluetooth: only `.off` plays. Turning on from rest crossfades the two static looks.
        val p = SymbolPlayer(null, off, startOn = false)
        assertEquals(Turn.CROSSFADE, p.toward(true, fade))
        assertFalse(p.atRest); assertNull(p.motion); assertFalse("still the look it started from", p.restOn)
        assertEquals(0f, p.onShare, 0f); assertTrue(p.headingOn); assertEquals(0f, p.progress, 0f)
        assertTrue(p.advance(25f)); assertEquals(0.25f, p.onShare, 1e-4f)
        assertTrue(p.advance(0f)); assertEquals(0.25f, p.onShare, 1e-4f)
        assertTrue(p.advance(50f)); assertEquals(0.75f, p.onShare, 1e-4f)
        assertFalse(p.advance(50f))
        assertTrue(p.atRest); assertTrue(p.restOn); assertEquals(1f, p.onShare, 0f)
    }

    @Test fun theOtherDirectionCrossfadesToo() {
        // The on look has no play to arrive by in reverse: here the off look has none.
        val p = SymbolPlayer(on, null, startOn = true)
        assertEquals(Turn.CROSSFADE, p.toward(false, 200f))
        assertEquals(1f, p.onShare, 0f)
        p.advance(50f); assertEquals(0.75f, p.onShare, 1e-4f)
        assertFalse(p.headingOn)
        assertFalse(p.advance(150f))
        assertTrue(p.atRest); assertFalse(p.restOn); assertEquals(0f, p.onShare, 0f)
    }

    @Test fun aCrossfadeTurnedMidwayFadesBackFromWhereItIsNeverIntoAPlay() {
        // Bluetooth: off -> on crossfades; 60 ms in, off again must fade back (its off play would start at frame 0: a jump).
        val p = SymbolPlayer(null, off, startOn = false)
        p.toward(true, fade)
        p.advance(60f)
        assertEquals(0.6f, p.onShare, 1e-4f)
        assertEquals(Turn.CROSSFADE, p.toward(false, fade))
        assertNull("no play", p.motion); assertFalse(p.atRest)
        assertEquals("continuous at the turn", 0.6f, p.onShare, 1e-4f)
        p.advance(20f); assertEquals(0.4f, p.onShare, 1e-4f)
        // ... and turned on again from there
        assertEquals(Turn.CROSSFADE, p.toward(true, fade))
        assertEquals(0.4f, p.onShare, 1e-4f)
        p.advance(30f); assertEquals(0.7f, p.onShare, 1e-4f)
        assertEquals(Turn.NONE, p.toward(true, fade))
        while (p.advance(10f)) { }
        assertTrue(p.atRest); assertTrue(p.restOn)
    }

    @Test fun aCrossfadeFadedBackEndsOnTheStartLook() {
        val p = SymbolPlayer(null, off, startOn = false)
        p.toward(true, fade)
        p.advance(60f)
        p.toward(false, fade)
        assertFalse(p.advance(60f))
        assertTrue(p.atRest); assertFalse(p.restOn); assertEquals(0f, p.onShare, 0f)
    }

    @Test fun aCrossfadeOfNoLengthChangesTheLookAtOnce() {
        val p = SymbolPlayer(null, off, startOn = false)
        assertEquals(Turn.CROSSFADE, p.toward(true, 0f))
        assertTrue(p.atRest); assertTrue(p.restOn)
    }

    @Test fun anOffPlayReversedMidwayStillReversesWithNoOnPlay() {
        // (Bluetooth again: on mid-way through the off play reverses it; it is a play, not a crossfade.)
        val p = SymbolPlayer(null, off, startOn = true)
        p.toward(false, fade)
        repeat(10) { p.advance(8f) }
        assertEquals(Turn.PLAY, p.toward(true, fade))
        assertSame(off, p.motion)
        while (p.advance(8f)) { }
        assertTrue(p.atRest); assertTrue(p.restOn)
    }

    @Test fun snapRestsAtOnceOnTheLookAskedFor() {
        val p = SymbolPlayer(on, off)
        p.toward(true, fade)
        repeat(10) { p.advance(8f) }
        p.snap(false)
        assertTrue(p.atRest); assertFalse(p.restOn); assertEquals(0f, p.timeMs, 0f); assertNull(p.motion)
        p.snap(true)
        assertTrue(p.restOn)
        assertEquals(Turn.NONE, p.toward(true, fade))
        val b = SymbolPlayer(null, off)
        b.toward(true, fade); b.advance(10f)
        b.snap(false)
        assertTrue(b.atRest); assertEquals(0f, b.onShare, 0f)
    }

    @Test fun aHugeFrameFinishesTheRunWithoutOvershootingRest() {
        val p = SymbolPlayer(on, off)
        p.toward(true, fade)
        assertFalse(p.advance(10_000f))
        assertTrue(p.restOn)
        assertFalse(p.advance(8f))
    }
}
