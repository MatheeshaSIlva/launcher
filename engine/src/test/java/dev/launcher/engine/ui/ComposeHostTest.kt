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
        override fun layer(width: Float, height: Float, alpha: Int) { calls += "layer $width $height $alpha" }
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
            "layer 64.575 33.075 127", "clip", "painter", "children", "restore 7"), canvas.calls)
    }
    @Test fun explicitLayerIsIsolatedEvenWhenOpaqueAndAlwaysRestoresOnFailure() {
        val c = Recording(); val m = Motion({}, {})
        try {
            softwareFrame(c, m, Measured(Size(10, 8)), true, {}) { throw IllegalStateException("painter") }
            fail("failure swallowed")
        } catch (_: IllegalStateException) { /* the layer still restores */ }
        assertTrue(c.calls.contains("layer 10.0 8.0 255"))
        assertEquals("restore 7", c.calls.last())
        c.calls.clear()
        softwareFrame(c, m, Measured(Size(10, 8)), false, {}) {}
        assertFalse(c.calls.any { it.startsWith("layer") })
    }
}
