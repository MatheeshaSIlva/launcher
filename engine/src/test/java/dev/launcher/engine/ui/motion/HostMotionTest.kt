package dev.launcher.engine.ui.motion

import android.graphics.Canvas
import dev.launcher.engine.expr.Env
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlParser
import dev.launcher.engine.layout.Units
import dev.launcher.engine.model.ComponentReader
import dev.launcher.engine.model.ReadResult
import dev.launcher.engine.ui.ComponentHost
import dev.launcher.engine.ui.HostNode
import dev.launcher.engine.ui.Painter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.launcher.engine.layout.Measured
import dev.launcher.engine.layout.Size

/** T-009 13a-1: a component's moving nodes, its frame clock's registrations, and bound transforms as targets. */
class HostMotionTest {
    private val none = object : Painter {
        override fun key(node: HostNode) = null
        override fun paint(canvas: Canvas, node: HostNode, width: Int, height: Int) {}
    }
    private var opacity = 1.0
    private val env = object : Env {
        override fun state(name: String) = Value.Null
        override fun prop(name: String) = if (name == "o") Value.Num(opacity) else Value.Null
        override fun token(key: String) = Value.Null
        override fun source(path: String) = Value.Null
        override fun now() = 0L
    }
    private fun host(view: String, reduced: () -> Boolean = { false }, wake: () -> Unit = {}): ComponentHost {
        val d = (ComponentReader.read(KdlParser.parse("component \"m\" {\n  props { o type=number default=1 }\n  view { $view }\n}"))
            as ReadResult.Ok).component
        return ComponentHost(d, env, Units(1f, 1f), { none }, {}, {}, MotionContext(reduced = reduced, now = { 0L }), wake)
    }

    @Test fun movingNodesAreSteppedUntilTheySettleThenTheClockStops() {
        var wakes = 0
        val h = host("row { box width=10 height=10 transition=\"place 200ms\" }", wake = { wakes++ })
        val box = h.root.children[0]
        box.placed(0f, 0f, 0f, 0.5f)
        assertFalse(h.isMoving)
        box.placed(30f, 0f, 0f, 0.5f)
        box.placed(30f, 0f, 0f, 0.5f)                         // placed again: still one moving node, one wake
        assertTrue(h.isMoving); assertEquals(1, wakes)
        var t = 0L
        while (h.step(t)) t += 8_333_333
        assertFalse(h.isMoving)
        assertTrue("about its duration (200 ms)", t in 190_000_000L..210_000_000L)
        assertFalse("nothing to step: no frame asked", h.step(t + 8_333_333))
    }

    @Test fun boundTransformsApplyAtOnceWithoutATransition() {
        opacity = 0.25
        val h = host("box width=10 height=10 opacity=\"{@o}\" scale=2 translate-x=4")
        val n = h.root
        n.placed(0f, 0f, 0.5f, 0.5f)
        assertNull(n.nodeMotion)
        assertEquals(0.25f, n.motion.alpha, 0f); assertEquals(2f, n.motion.scale, 0f)
        assertFalse(h.isMoving)
    }

    @Test fun anOpacityTransitionFollowsItsBinding() {
        opacity = 1.0
        val h = host("box width=10 height=10 opacity=\"{@o}\" transition=\"opacity 120ms\"")
        val n = h.root
        n.placed(0f, 0f, 0.5f, 0.5f)
        opacity = 0.0
        h.refresh()
        n.placed(0f, 0f, 0.5f, 0.5f)
        assertTrue(h.isMoving)
        h.step(60_000_000)
        assertTrue("on its way", n.motion.alpha in 0.01f..0.99f)
        while (h.step(200_000_000)) Unit
        assertEquals(0f, n.motion.alpha, 0f)
    }

    @Test fun reducedMotionSnapsWhatMovesAndDisposalForgetsIt() {
        val h = host("row { box width=10 height=10 transition=\"place 200ms\" }")
        val box = h.root.children[0]
        box.placed(0f, 0f, 0f, 0.5f); box.placed(30f, 0f, 0f, 0.5f)
        h.snapMotion()
        assertFalse(h.isMoving)
        assertEquals(0f, box.motion.drawX, 0f)
        box.placed(60f, 0f, 0f, 0.5f)
        assertTrue(h.isMoving)
        h.dispose()
        assertFalse(h.isMoving)
        box.placed(90f, 0f, 0f, 0.5f)
        assertFalse("a disposed component asks for nothing", h.isMoving)
    }

    /** Codex, 13a-1 review: width settling before height kept the layer's bounds; one envelope until both have. */
    @Test fun theEnvelopeHoldsUntilEverySizeTrackSettles() {
        val h = host("box width=10 height=10 transition=\"size spring(0.4, 0.6)\"")
        val n = h.root
        n.measured = Measured(Size(20, 10)); n.placed(0f, 0f, 0.5f, 0.5f)
        n.measured = Measured(Size(120, 12)); n.placed(0f, 0f, 0.5f, 0.5f)
        val m = n.nodeMotion!!
        val first = n.motion.envelope!!.copyOf()
        var t = 0L
        var checked = false
        while (h.step(t)) {
            val e = n.motion.envelope!!
            assertTrue("never smaller: ${e.toList()} vs ${first.toList()}", e[0] <= first[0] && e[1] <= first[1] && e[2] >= first[2] && e[3] >= first[3])
            if (!m.height.moving && m.width.moving) checked = true
            t += 8_333_333
        }
        assertTrue("height settled first, while width still moved", checked)
        assertNull("dropped at rest", n.motion.envelope)
    }

    /** Codex, 13a-1 review: a centred stroke's outer half is inside the drawn bounds at rest and in motion alike. */
    @Test fun aCentredStrokeIsInsideTheBoundsAtRestAndInMotion() {
        val h = host("box width=10 height=10 stroke=2 stroke-color=\"#ffffff\" stroke-align=\"center\" transition=\"size 200ms\"")
        val n = h.root
        n.measured = Measured(Size(40, 10)); n.placed(0f, 0f, 0.5f, 0.5f)
        assertEquals(listOf(-1f, -1f, 41f, 11f), n.drawBounds().toList())
        n.measured = Measured(Size(20, 10)); n.placed(0f, 0f, 0.5f, 0.5f)
        val b = n.drawBounds()
        assertTrue("the shrinking surface and its stroke: ${b.toList()}", b[0] <= -11f && b[2] >= 31f)   // 40 wide centred in its new 20 px box, out by the stroke
    }

    /** Codex, 13a-1 review: the root's real place in its window goes through its place transition. */
    @Test fun theRootsWindowPlaceMovesOnItsTransition() {
        val h = host("box width=10 height=10 transition=\"place 200ms\"")
        val n = h.root
        n.placed(0f, 12f, 0.5f, 0.5f)
        n.placed(0f, 15f, 0.5f, 0.5f)
        assertTrue(h.isMoving)
        assertEquals("shown where it was", -3f, n.motion.drawY, 1e-4f)
    }

    /** 13a-1b (measured on the emulator): placement draws nothing again by itself; a node whose shown values change asks. */
    @Test fun aPlacementThatChangesWhatIsShownAsksForADraw() {
        opacity = 1.0
        val h = host("box width=10 height=10 opacity=\"{@o}\"")
        val n = h.root
        var draws = 0
        n.redraw = { draws++ }
        n.placed(0f, 0f, 0.5f, 0.5f)
        val first = draws
        n.placed(0f, 0f, 0.5f, 0.5f)
        assertEquals("nothing changed: no draw", first, draws)
        opacity = 0.5
        h.refresh()
        n.placed(0f, 0f, 0.5f, 0.5f)
        assertEquals(first + 1, draws)
    }
}
