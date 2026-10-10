package dev.launcher.app

import dev.launcher.app.design.Curve
import dev.launcher.app.design.Provenance
import dev.launcher.app.design.Resolver
import dev.launcher.app.design.Theme
import dev.launcher.app.design.ThemeCheck
import dev.launcher.app.design.Value
import dev.launcher.app.motion.BezierMath
import dev.launcher.app.motion.Ease
import dev.launcher.app.motion.mover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** The animation layer (B3): presets apart from themes, and curves (springs and beziers) that every role can take. */
class MotionTest {
    private val presets = File("src/main/assets/motion")
    private val themes = File("src/main/assets/themes")
    private fun preset(id: String) = Theme.parse(File(presets, "$id.json").readText())

    @Test fun themesHoldNoMotionAndPresetsOnlyMotion() {
        for (f in themes.listFiles()!!.filter { it.name.endsWith(".json") }) {
            val t = Theme.parse(f.readText())
            assertTrue("${f.name}: motion in a theme ${t.entries.keys.filter { it.startsWith("motion.") }}", t.entries.keys.none { it.startsWith("motion.") })
            assertTrue("${f.name}: motion in its Material You section", t.materialYou.keys.none { it.startsWith("motion.") })
        }
        val ids = presets.listFiles()!!.filter { it.name.endsWith(".json") }.map { it.name.removeSuffix(".json") }
        assertTrue("the iOS 27 preset ships", "ios27" in ids)
        for (id in ids) {
            val p = preset(id)
            assertTrue("$id holds tokens that are not motion", p.entries.keys.all { it.startsWith("motion.") })
            val unsourced = p.entries.filter { (_, e) -> e.src is Provenance.Judged && (e.src as Provenance.Judged).why == "no source given" }
            assertTrue("$id: tokens without a source: ${unsourced.keys}", unsourced.isEmpty())
        }
    }

    @Test fun everyPresetLoadsAgainstIos27() {
        val base = preset("ios27").entries
        for (f in presets.listFiles()!!.filter { it.name.endsWith(".json") }) {
            val layers = ArrayList<Map<String, dev.launcher.app.design.Entry>>()
            var cur: String? = f.name.removeSuffix(".json")
            while (cur != null) { val t = preset(cur); layers.add(0, t.entries); cur = t.extends }
            assertNull(f.name, ThemeCheck.against(base, layers))
        }
    }

    @Test fun aRoleMayBeASpringOrABezier() {
        val t = Theme.parse("""{"name": "t", "tokens": {
            "motion.a": {"spring": [0.4, 0.9], "src": "judged:test"},
            "motion.b": {"bezier": [0.2, 0, 0, 1], "ms": 300, "src": "judged:test"}}}""")
        val r = Resolver(listOf(t.entries))
        assertEquals(Curve.Spring(0.4f, 0.9f), (r.resolve("motion.a") as Value.CurveV).curve)
        assertEquals(Curve.Ease(0.2f, 0f, 0f, 1f, 300f), (r.resolve("motion.b") as Value.CurveV).curve)
        // Both are the same kind for the check: a preset may give a spring role a bezier.
        assertNull(ThemeCheck.against(mapOf("motion.a" to t.entries.getValue("motion.a")), listOf(mapOf("motion.a" to t.entries.getValue("motion.b")))))
        // Written back as read.
        val back = Theme.parse(Theme.write("t", t.entries))
        assertEquals(t.entries.mapValues { it.value.value }, back.entries.mapValues { it.value.value })
    }

    @Test fun aBadCurveIsRefused() {
        for (bad in listOf(
            """{"bezier": [1.2, 0, 0, 1], "ms": 300}""",   // x1 outside 0..1: not a function of time
            """{"bezier": [0.2, 0, 0, 1]}""",               // no duration
            """{"bezier": [0.2, 0, 0], "ms": 300}""",
            """{"bezier": [0.2, 0, 0, 1], "ms": 0}""",
            """{"spring": [0, 1]}""",
        )) assertTrue(bad, runCatching { Theme.parse("""{"name": "t", "tokens": {"motion.x": $bad}}""") }.isFailure)
    }

    @Test fun bezierMathMatchesCss() {
        // CSS's `ease` (0.25, 0.1, 0.25, 1) is ~80% along at half its time; `linear` is exactly half.
        val e = Ease(0.25f, 0.1f, 0.25f, 1f, 1f)
        assertEquals(0.8024, e.progress(0.5), 0.002)
        assertEquals(0.5, Ease(0f, 0f, 1f, 1f, 1f).progress(0.5), 1e-6)
        for (u in listOf(0.0, 0.1, 0.37, 0.5, 0.9, 1.0)) {
            val s = BezierMath.solveS(u, 0.42, 0.58)
            assertEquals(u, BezierMath.x(s, 0.42, 0.58), 1e-6)
        }
    }

    @Test fun anEaseEndsOnItsTargetAndKeepsTheSpeedItWasGiven() {
        val e = Curve.Ease(0.42f, 0f, 1f, 1f, 400f).mover()   // ease-in: starts with no speed of its own
        e.start(100f, 0f, 500f)
        assertEquals(100f, e.value(0.0), 1e-3f)
        assertEquals(500f, e.value(0.4), 1e-3f)
        assertTrue(e.settled(0.4))
        assertTrue(!e.settled(0.39))
        // From rest it only moves forward.
        var last = 100f
        for (i in 1..40) { val v = e.value(i * 0.01); assertTrue("step $i", v >= last - 1e-3f); last = v }
        // Started mid-motion: the speed it had carries on (no jump in speed for a curve that starts slowly) ...
        e.start(100f, 2000f, 500f)
        assertEquals(2000f, e.velocity(0.0), 1f)
        // ... and position never jumps.
        assertEquals(100f, e.value(0.0), 1e-3f)
        assertEquals(500f, e.value(0.4), 1e-3f)
        // Continuous: tiny steps make tiny moves.
        for (i in 0 until 400) assertTrue(abs(e.value(i * 0.001 + 0.001) - e.value(i * 0.001)) < 5f)
    }

    @Test fun aSpringRoleMovesAsBefore() {
        val m = Curve.Spring(0.5f, 1f).mover()
        val s = Spring(0.5f, 1f)
        m.start(0f, 0f, 100f); s.start(0f, 0f, 100f)
        for (t in listOf(0.0, 0.1, 0.3, 0.6)) assertEquals(s.value(t), m.value(t), 1e-4f)
    }
}
