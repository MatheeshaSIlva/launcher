package dev.launcher.engine.ui

import android.graphics.Canvas
import dev.launcher.app.design.Curve
import dev.launcher.engine.expr.Env
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlParser
import dev.launcher.engine.layout.Constraints
import dev.launcher.engine.layout.Units
import dev.launcher.engine.model.ComponentReader
import dev.launcher.engine.model.ReadResult
import dev.launcher.engine.ui.motion.MotionContext
import dev.launcher.engine.ui.motion.MotionResolver
import org.junit.Assert.*
import org.junit.Test

class MediaHostTest {
    private var on = true
    private var shown = true
    private var reduced = false
    private var now = 0L
    private var wakes = 0
    private val env = object : Env {
        override fun state(name: String) = Value.Bool.of(if (name == "on") on else shown)
        override fun prop(name: String) = Value.Null
        override fun token(key: String) = Value.Null
        override fun source(path: String) = Value.Null
        override fun now() = 0L
    }
    private val none = object : Painter {
        override fun key(node: HostNode) = null
        override fun paint(canvas: Canvas, node: HostNode, width: Int, height: Int) {}
    }
    private fun host(): ComponentHost {
        val def = (ComponentReader.read(KdlParser.parse("""
            component "c" {
                state { on #true; shown #true }
                view { box {
                    box visible="${'$'}shown" exit="fade 220ms" {
                        box width=2 height=4 loop-from=4 loop-to=14 loop-period=1000 loop-run="${'$'}on"
                    }
                } }
            }
        """)) as ReadResult.Ok).component
        return ComponentHost(def, env, Units(1f, 1f), { none }, motionContext = MotionContext(
            MotionResolver { Curve.Ease(0.23f, 1f, 0.32f, 1f, 120f) }, { reduced }, { now }), wake = { wakes++ })
    }

    @Test fun attachmentVisibilityAndDisposalControlClockAndLayoutStaysMaximum() {
        val h = host(); val n = h.root.children[0].children[0]
        assertFalse(h.isMoving)
        val measured = n.arrange(Constraints(0, 100, 0, 100), emptyList()).measured
        assertEquals(14f, measured.exactHeight, 0f)
        n.setDrawingAttached(true); assertTrue(h.isMoving); assertEquals(1, wakes)
        now = 250_000_000; h.step(now); assertEquals(14f, n.loop.height, 0.001f)
        assertEquals(measured, n.arrange(Constraints(0, 100, 0, 100), emptyList()).measured)
        h.setVisible(false); assertFalse(h.isMoving)
        h.setVisible(true); assertTrue(h.isMoving)
        n.setDrawingAttached(false); assertFalse(h.isMoving)
        n.setDrawingAttached(true); assertTrue(h.isMoving)
        h.dispose(); assertFalse(h.isMoving)
        n.setDrawingAttached(true); assertFalse(h.isMoving)
    }

    @Test fun pausedLoopSettlesReducedLoopStopsAndRetainedSubtreeFreezes() {
        val h = host(); val parent = h.root.children[0]; val n = parent.children[0]
        parent.placed(0f, 0f, 0f, 0f)
        n.setDrawingAttached(true)
        now = 250_000_000; h.step(now)
        on = false; h.refresh(); assertEquals(14f, n.loop.height, 0.001f)
        now = 2_000_000_000; h.step(now); assertFalse(h.isMoving); assertEquals(4f, n.loop.height, 0f)
        on = true; h.refresh(); assertTrue(h.isMoving)
        reduced = true; h.snapMotion(); assertFalse(h.isMoving)
        reduced = false; h.refreshMotion(); assertTrue(h.isMoving)
        now += 250_000_000; h.step(now); val height = n.loop.height
        shown = false; h.refresh(); assertTrue(parent.isLeaving)
        assertFalse(n.loop.busy)
        now += 10_000_000; h.step(now); assertEquals(height, n.loop.height, 0f)
        now += 1_000_000_000; h.step(now); assertFalse(h.isMoving); assertEquals(0, h.retainedCount)
    }
}
