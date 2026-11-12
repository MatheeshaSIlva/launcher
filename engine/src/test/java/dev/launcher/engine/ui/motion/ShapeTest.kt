package dev.launcher.engine.ui.motion

import android.graphics.Canvas
import dev.launcher.engine.expr.Env
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlParser
import dev.launcher.engine.layout.Measured
import dev.launcher.engine.layout.Size
import dev.launcher.engine.layout.Units
import dev.launcher.engine.model.ComponentReader
import dev.launcher.engine.model.ReadResult
import dev.launcher.engine.ui.ComponentHost
import dev.launcher.engine.ui.HostNode
import dev.launcher.engine.ui.NodeShape
import dev.launcher.engine.ui.Painter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

/** T-011: a box's shown radius (`full`, handovers, the clamp) and its shape as a hit region. */
class ShapeTest {
    private val none = object : Painter {
        override fun key(node: HostNode) = null
        override fun paint(canvas: Canvas, node: HostNode, width: Int, height: Int) {}
    }
    private var w = 40.0; private var h = 120.0
    private var r: Value = Value.Str("full")
    private var clock = 0L
    private val env = object : Env {
        override fun state(name: String) = Value.Null
        override fun prop(name: String) = when (name) { "w" -> Value.Num(w); "h" -> Value.Num(h); "r" -> r; else -> Value.Null }
        override fun token(key: String) = Value.Null
        override fun source(path: String) = Value.Null
        override fun now() = 0L
    }
    private var reduced = false
    private fun host(transition: String): ComponentHost {
        val d = (ComponentReader.read(KdlParser.parse("component \"s\" {\n  props { w type=number default=40\n h type=number default=120\n r type=string default=\"full\" }\n" +
            "  view { box width=\"{@w}\" height=\"{@h}\" radius=\"{@r}\" transition=\"$transition\" }\n}")) as ReadResult.Ok).component
        return ComponentHost(d, env, Units(1f, 1f), { none }, {}, {}, MotionContext(reduced = { reduced }, now = { clock }), {})
    }
    /** Lays the box out at its bound size, as Compose does. */
    private fun ComponentHost.place() { root.measured = Measured(Size(w.toInt(), h.toInt())); root.placed(0f, 0f, 0.5f, 0.5f) }
    private fun ComponentHost.at(t: Long): Boolean { clock = t; return step(t) }

    @Test fun fullFollowsTheDrawnShorterSideThroughAnAxisCrossing() {
        val hst = host("size 200ms")
        hst.place()
        assertEquals(20f, hst.root.shownRadius(), 0f)
        w = 120.0; h = 40.0; hst.refresh(); hst.place()
        var t = 0L
        var crossed = false
        while (hst.at(t)) {
            val n = hst.root
            assertEquals("half the shorter side at every frame", min(n.drawnWidth, n.drawnHeight) / 2f, n.shownRadius(), 1e-4f)
            if (abs(n.drawnWidth - n.drawnHeight) < 4f) { crossed = true; assertTrue("about 40 when square", n.shownRadius() > 37f) }
            t += 8_333_333
        }
        assertTrue(crossed)
        assertEquals(20f, hst.root.shownRadius(), 0f)
    }

    @Test fun aHandoverKeepsTheRadiusAndItsSpeedContinuous() {
        w = 200.0; h = 100.0; r = Value.Num(8.0, "px")
        val hst = host("size radius 200ms")
        hst.place()
        assertEquals(8f, hst.root.shownRadius(), 0f)
        r = Value.Str("full"); hst.refresh(); hst.place()
        assertEquals("from where it was", 8f, hst.root.shownRadius(), 1e-3f)
        val samples = ArrayList<Float>()
        var t = 0L
        while (hst.at(t) && t < 60_000_000) { samples += hst.root.shownRadius(); t += 4_000_000 }
        // Interrupted mid-way back to a length equal to where it is now, while it moves: the value and its speed carry on.
        val before = samples.takeLast(2)
        val shown = hst.root.shownRadius()
        r = Value.Num(shown.toDouble(), "px"); hst.refresh(); hst.place()
        assertEquals("the value kept", shown, hst.root.shownRadius(), 1e-3f)
        hst.at(t + 4_000_000)
        val after = hst.root.shownRadius()
        val speedBefore = before[1] - before[0]
        val speedAfter = after - shown
        assertTrue("speed carried (before $speedBefore, after $speedAfter)", abs(speedAfter - speedBefore) < 0.35f * abs(speedBefore) + 0.05f)
        while (hst.at(t)) t += 4_000_000
        assertEquals(shown, hst.root.shownRadius(), 0.01f)
    }

    @Test fun withoutARadiusCurveAChangeJumpsAndItIsAlwaysClamped() {
        w = 100.0; h = 60.0; r = Value.Num(8.0, "px")
        val hst = host("size 200ms")
        hst.place()
        r = Value.Num(500.0, "px"); hst.refresh(); hst.place()
        assertEquals("clamped to half the shorter side", 30f, hst.root.shownRadius(), 0f)
        r = Value.Num(-4.0, "px"); hst.refresh(); hst.place()
        assertEquals(0f, hst.root.shownRadius(), 0f)
        r = Value.Str("nonsense"); hst.refresh(); hst.place()
        assertEquals(0f, hst.root.shownRadius(), 0f)
    }

    @Test fun theShapeIsTheHitRegion() {
        val s = NodeShape(10f, 20f, 110f, 60f, 20f)
        assertTrue(s.contains(60f, 40f)); assertTrue("an edge counts", s.contains(10f, 40f))
        assertFalse("outside a rounded corner", s.contains(11f, 21f))
        assertTrue("inside a corner's circle", s.contains(20f, 30f))
        assertFalse(s.contains(9f, 40f))
        assertFalse("empty", NodeShape(0f, 0f, 0f, 10f, 0f).contains(0f, 5f))
        assertFalse("not finite", NodeShape(0f, 0f, Float.NaN, 10f, 0f).contains(0f, 5f))
        w = 100.0; h = 60.0; r = Value.Num(12.0, "px")
        val hst = host("size 200ms")
        hst.place()
        val shape = hst.root.shape()
        assertEquals(listOf(0f, 0f, 100f, 60f, 12f), listOf(shape.left, shape.top, shape.right, shape.bottom, shape.radius))
    }

    @Test fun withMotionReducedARadiusChangeLandsAtOnce() {
        reduced = true
        w = 200.0; h = 100.0; r = Value.Num(8.0, "px")
        val hst = host("size radius 200ms")
        hst.place()
        r = Value.Str("full"); hst.refresh(); hst.place()
        assertEquals(50f, hst.root.shownRadius(), 0f)
        assertFalse(hst.isMoving)
        reduced = false
    }

    @Test fun aHandoverFromAClampedRadiusStartsFromTheShownSpeed() {
        // Height 100: the radius heads for 500 and is held at 50; retargeted to 20 there, it starts from rest (Codex).
        w = 200.0; h = 100.0; r = Value.Num(8.0, "px")
        val hst = host("size radius 400ms")
        hst.place()
        assertEquals("drawn once at 8 (the painter reads it)", 8f, hst.root.shownRadius(), 0f)
        r = Value.Num(500.0, "px"); hst.refresh(); hst.place()
        var t = 0L
        while (hst.root.shownRadius() < 50f && t < 400_000_000) { t += 8_000_000; hst.at(t) }
        t += 40_000_000; hst.at(t)
        assertEquals("held by the clamp", 50f, hst.root.shownRadius(), 0f)
        r = Value.Num(20.0, "px"); hst.refresh(); hst.place()
        assertEquals(50f, hst.root.shownRadius(), 1e-3f)
        hst.at(t + 16_000_000)
        val after = hst.root.shownRadius()
        assertTrue("it leaves the clamp at once, slowly, not pushed further by the hidden speed ($after)", after < 50f && after > 45f)
    }
}
