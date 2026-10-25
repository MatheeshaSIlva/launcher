package dev.launcher.app.motion

import dev.launcher.app.design.Curve
import dev.launcher.app.design.SymbolMotion
import dev.launcher.app.design.SymbolPlayer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** B-036 slice 3a: the role `motion.qs.tile-symbol` as the symbol player's clock (stretch by its length, warp by its curve). */
class SymbolTimeTest {
    private fun play(ms: Int) = SymbolMotion(JSONObject("""{"ms":$ms,"groups":[],"layers":[{"n":"l","g":-1,"d":"M0 0L1 1"}],"tracks":[]}"""))
    private val frame = 1000f / 120f

    /**
     * The time each frame of [frame] ms would draw while a [playMs] play runs with the role [curve]; the frame that ends the
     * play (which draws the look at rest) is not in the list, so the play takes `size + 1` frames.
     */
    private fun run(curve: Curve, playMs: Int): List<Float> {
        val timing = CurveTiming(curve)
        val m = play(playMs)
        val p = SymbolPlayer(m, m)
        p.toward(true, 100f)
        val times = ArrayList<Float>()
        while (p.advance(SymbolTime.dt(frame, timing))) times += SymbolTime.at(p.progress, m.ms, timing)
        return times
    }

    private val linear500 = Curve.Ease(0f, 0f, 1f, 1f, 500f)

    private fun shippedRole(): Curve {
        val o = JSONObject(File("src/main/assets/motion/ios27.json").readText()).getJSONObject("tokens").getJSONObject("motion.qs.tile-symbol")
        val b = o.getJSONArray("bezier")
        return Curve.Ease(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat(), o.getDouble("ms").toFloat())
    }

    @Test fun theShippedDefaultIsAStraightLineOver500Ms() {
        val role = shippedRole()
        assertEquals(Curve.Ease(0f, 0f, 1f, 1f, 500f), role)
        assertEquals(500f, SymbolTime.ANDROID_MS, 0f)
    }

    @Test fun theDefaultGivesAndroidsOwnTimings() {
        val timing = CurveTiming(shippedRole())
        assertEquals(500L, timing.durationMs)
        assertEquals("a frame's step is not scaled", frame, SymbolTime.dt(frame, timing), 0f)
        // Flashlight plays 500 ms: 60 frames of 8.333 ms (the last one may fall either side of the end by float rounding).
        val flashlight = run(shippedRole(), 500)
        assertTrue("flashlight: ${flashlight.size + 1} frames", flashlight.size + 1 in 60..61)
        // The time drawn is the player's own time (a straight line warps nothing): frame n draws n * 8.333 ms.
        for ((n, t) in flashlight.withIndex()) assertEquals("frame $n", (n + 1) * frame, t, 1e-2f)
        assertTrue("airplane 650 ms: ${run(shippedRole(), 650).size + 1}", run(shippedRole(), 650).size + 1 in 78..79)
        assertTrue("auto-rotate 800 ms: ${run(shippedRole(), 800).size + 1}", run(shippedRole(), 800).size + 1 in 96..97)
    }

    @Test fun aLongerRoleSlowsEverySymbolInProportion() {
        val timing = CurveTiming(Curve.Ease(0f, 0f, 1f, 1f, 1000f))
        assertEquals(frame / 2, SymbolTime.dt(frame, timing), 1e-6f)
        val slow = run(Curve.Ease(0f, 0f, 1f, 1f, 1000f), 500)
        assertTrue("1000 ms role: ${slow.size + 1} frames", slow.size + 1 in 120..121)
        val fast = run(Curve.Ease(0f, 0f, 1f, 1f, 250f), 500)
        assertTrue("250 ms role: ${fast.size + 1} frames", fast.size + 1 in 30..31)
    }

    @Test fun theCurveReshapesTheProgress() {
        // Ease-in: half way through (time) the play is less than half way through its own animation; always inside the play.
        val timing = CurveTiming(Curve.Ease(0.5f, 0f, 1f, 1f, 500f))
        val mid = SymbolTime.at(0.5f, 800f, timing)
        assertTrue("ease-in at the middle: $mid", mid in 1f..400f)
        assertEquals(0f, SymbolTime.at(0f, 800f, timing), 0f)
        assertEquals(800f, SymbolTime.at(1f, 800f, timing), 0f)
        var last = -1f
        for (i in 0..50) { val t = SymbolTime.at(i / 50f, 800f, timing); assertTrue(t >= last); last = t }
        // Ease-out is ahead of the straight line.
        assertTrue(SymbolTime.at(0.5f, 800f, CurveTiming(Curve.Ease(0f, 0f, 0.5f, 1f, 500f))) > 400f)
    }

    @Test fun aCurveThatOvershootsNeverLeavesThePlay() {
        val timing = CurveTiming(Curve.Ease(0.3f, 1.8f, 0.6f, -0.8f, 500f))
        var min = Float.MAX_VALUE; var max = -Float.MAX_VALUE
        for (i in 0..100) { val t = SymbolTime.at(i / 100f, 800f, timing); min = minOf(min, t); max = maxOf(max, t) }
        assertTrue("within the play: $min .. $max", min >= 0f && max <= 800f)
        assertEquals("progress outside 0..1 is held", SymbolTime.at(1f, 800f, timing), SymbolTime.at(1.7f, 800f, timing), 0f)
        assertEquals(0f, SymbolTime.at(-0.3f, 800f, timing), 0f)
    }

    @Test fun aSpringRoleIsReadAsItsSettleTimeAndNormalisedShape() {
        val spring = Curve.Spring(0.4f, 1f)
        val timing = CurveTiming(spring)
        assertTrue("settle time: ${timing.durationMs}", timing.durationMs in 300L..1500L)
        assertEquals(frame * (500f / timing.durationMs), SymbolTime.dt(frame, timing), 1e-6f)
        assertEquals(800f, SymbolTime.at(1f, 800f, timing), 0f)
        val mid = SymbolTime.at(0.5f, 800f, timing)
        assertEquals(timing.at(0.5f).coerceIn(0f, 1f) * 800f, mid, 1e-3f)
        assertTrue("a spring covers most of its way early: $mid", mid > 400f)
    }

    @Test fun aReversalKeepsTheClockContinuous() {
        // A play turned back runs the same warped path backwards: the time drawn never jumps by more than one frame's step.
        val curve = Curve.Ease(0.5f, 0f, 1f, 1f, 700f)
        val timing = CurveTiming(curve)
        val m = play(500)
        val p = SymbolPlayer(m, m)
        p.toward(true, 100f)
        var prev = 0f
        var biggest = 0f
        for (i in 0 until 400) {
            if (i == 30) p.toward(false, 100f)
            if (!p.advance(SymbolTime.dt(frame, timing))) break
            val t = SymbolTime.at(p.progress, m.ms, timing)
            if (i > 0) biggest = maxOf(biggest, kotlin.math.abs(t - prev))
            prev = t
        }
        assertTrue("largest step $biggest ms", biggest < 25f)
    }
}
