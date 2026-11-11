package dev.launcher.engine.ui

import dev.launcher.engine.layout.Measured
import dev.launcher.engine.layout.Size
import org.junit.Assert.*
import org.junit.Test

/** Operation-order proof only: Android rasterisation and RenderNode isolation need the lead's hardware harness. */
class ComposeHostTest {
    private class Recording : FrameCanvas {
        val calls = ArrayList<String>()
        override fun save(): Int { calls += "save"; return 7 }
        override fun translate(x: Float, y: Float) { calls += "translate $x $y" }
        override fun scale(scale: Float, cx: Float, cy: Float) { calls += "scale $scale $cx $cy" }
        override fun layer(left: Float, top: Float, right: Float, bottom: Float, alpha: Int) { calls += "layer $left $top $right $bottom $alpha" }
        override fun clip(n: HostNode) { calls += "clip" }
        override fun restore(count: Int) { calls += "restore $count" }
    }
    @Test fun residualsAddToMotionAndNeverAccumulateAcrossPlacement() {
        var frames = 0
        var appliedX = 0f
        val m = Motion({ appliedX = it.drawX }, { frames++ })
        m.translationX = 5f; m.translationY = 9f
        m.place(-0.325f, 0.25f)
        assertEquals(4.675f, appliedX, 1e-5f)
        assertEquals(9.25f, m.drawY, 0f)
        m.place(-0.325f, 0.25f)
        assertEquals(4.675f, appliedX, 1e-5f)
        m.translationX = 8f
        assertEquals(7.675f, appliedX, 1e-5f)
        assertEquals(3, frames)
    }
    @Test fun softwareMotionAndLayerEncloseOwnPainterAndChildren() {
        val canvas = Recording()
        val m = Motion({}, {})
        m.translationX = 4f; m.translationY = 3f; m.place(0.25f, -0.25f)
        m.scale = 2f; m.alpha = 0.5f
        val measured = Measured(Size(65, 34), 16.5375f, 64.575f, 33.075f)
        softwareFrame(canvas, m, measured, true, { canvas.calls += "clip" }) {
            canvas.calls += "painter"; canvas.calls += "children"
        }
        assertEquals(listOf("save", "translate 4.25 2.75", "scale 2.0 32.5 17.0",
            "layer 0.0 0.0 64.575 33.075 127", "clip", "painter", "children", "restore 7"), canvas.calls)
    }
    @Test fun explicitLayerIsIsolatedEvenWhenOpaqueAndAlwaysRestoresOnFailure() {
        val c = Recording(); val m = Motion({}, {})
        try {
            softwareFrame(c, m, Measured(Size(10, 8)), true, {}) { throw IllegalStateException("painter") }
            fail("failure swallowed")
        } catch (_: IllegalStateException) { /* the layer still restores */ }
        assertTrue(c.calls.contains("layer 0.0 0.0 10.0 8.0 255"))
        assertEquals("restore 7", c.calls.last())
        c.calls.clear()
        softwareFrame(c, m, Measured(Size(10, 8)), false, {}) {}
        assertFalse(c.calls.any { it.startsWith("layer") })
    }

    /** T-009 13a-1: while a size moves, a layer covers the motion's whole envelope, not the layout box it rests in. */
    @Test fun aMovingSizeIsLayeredOverItsEnvelope() {
        val c = Recording(); val m = Motion({}, {})
        m.envelope = floatArrayOf(-6f, 0f, 30f, 8f)
        softwareFrame(c, m, Measured(Size(20, 8)), true, {}) {}
        assertTrue(c.calls.toString(), c.calls.contains("layer -6.0 0.0 30.0 8.0 255"))
        m.envelope = null
        c.calls.clear()
        softwareFrame(c, m, Measured(Size(20, 8)), true, {}) {}
        assertTrue("at rest: its layout box", c.calls.contains("layer 0.0 0.0 20.0 8.0 255"))
    }

    private fun revealOf(attr: String): Float? {
        val env = object : dev.launcher.engine.expr.Env {
            override fun state(name: String) = dev.launcher.engine.expr.Value.Null
            override fun prop(name: String) = dev.launcher.engine.expr.Value.Null
            override fun token(key: String) = dev.launcher.engine.expr.Value.Null
            override fun source(path: String) = dev.launcher.engine.expr.Value.Null
            override fun now() = 0L
        }
        val def = (dev.launcher.engine.model.ComponentReader.read(dev.launcher.engine.kdl.KdlParser.parse("component \"r\" {\n  view { box $attr }\n}"))
            as dev.launcher.engine.model.ReadResult.Ok).component
        val none = object : Painter {
            override fun key(node: HostNode) = null
            override fun paint(canvas: android.graphics.Canvas, node: HostNode, width: Int, height: Int) {}
        }
        return revealFraction(ComponentHost(def, env, dev.launcher.engine.layout.Units(1f, 1f), { none }).root)
    }

    @org.junit.Test fun aBoxNamingRevealClipsAtFullToo() {
        org.junit.Assert.assertEquals(1f, revealOf("reveal=1")!!, 0f)      // StatusBarView's level goes through its clip at 100 % too
        org.junit.Assert.assertEquals(0.5f, revealOf("reveal=0.5")!!, 0f)
        org.junit.Assert.assertEquals(1f, revealOf("reveal=7")!!, 0f)
        org.junit.Assert.assertEquals(null, revealOf("width=10"))          // not named: no clip
    }

    /** Codex, 13a-1 review: a whole-pixel node's own fractional translate counts in where its origin lands. */
    @Test fun wholePixelsIncludeTheNodesOwnRestTranslation() {
        val (offset, residual) = wholePixel(0f, 0.3f, 0.5f)
        assertEquals("origin on a whole pixel of the screen", 0f, 0f + offset + 0.5f, 1e-6f)
        assertEquals("the painter draws the rest", 0.8f, residual, 1e-6f)
        val (o2, r2) = wholePixel(0.25f, 0.5f, 0f)
        assertEquals(0f, 0.25f + o2, 1e-6f); assertEquals(0.75f, r2, 1e-6f)
    }
}
