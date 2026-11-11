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
import dev.launcher.engine.ui.Painter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-009 13a-2: arriving and leaving nodes: drawn out of layout until their exit settles, revived, bounded, released once. */
class PresenceTest {
    private val none = object : Painter {
        override fun key(node: HostNode) = null
        override fun paint(canvas: Canvas, node: HostNode, width: Int, height: Int) {}
    }
    private var items = listOf("a", "b", "c")
    private var shown = true
    private var reduced = false
    /** The frame clock: what `now` reads is the last frame stepped, as on a device. */
    private var clock = 0L
    private val env = object : Env {
        override fun state(name: String) = Value.Null
        override fun prop(name: String) = if (name == "on") Value.Bool.of(shown) else Value.Null
        override fun token(key: String) = Value.Null
        override fun source(path: String) =
            if (path == "s.items") Value.List(items.map { Value.Record(mapOf("id" to Value.Str(it))) }) else Value.Null
        override fun now() = 0L
    }
    private val released = ArrayList<HostNode>()
    private fun host(view: String): ComponentHost {
        val d = (ComponentReader.read(KdlParser.parse(
            "component \"p\" {\n  props { on type=bool default=#true }\n  uses { source s }\n  view { $view }\n}")) as ReadResult.Ok).component
        return ComponentHost(d, env, Units(1f, 1f), { none }, {}, { released += it },
            MotionContext(reduced = { reduced }, now = { clock }), {})
    }
    private val row = "row { box each=\"{s.items}\" as=\"i\" width=10 height=10 exit=\"fade 120ms\" enter=\"fade 120ms\" }"

    /** Places every laid-out child once, as Compose does, so tracks have their targets. */
    private fun ComponentHost.place() {
        root.placed(0f, 0f, 0.5f, 0.5f)
        root.children.forEachIndexed { i, c -> c.measured = Measured(Size(10, 10)); c.placed(i * 10f, 0f, 0f, 0.5f) }
    }
    private fun ComponentHost.settle(): Long { var t = clock; while (at(t)) t += 8_333_333; return t }
    private fun ComponentHost.at(t: Long): Boolean { clock = t; return step(t) }

    @Test fun aLeavingNodeDrawsAtItsPlaceOutOfLayoutUntilItsExitEnds() {
        val h = host(row); h.place()
        val b = h.root.children[1]
        items = listOf("a", "c"); h.refresh(); h.place()
        assertEquals(listOf("a", "c"), h.root.children.map { it.key.last() })
        assertEquals("drawn at its old index", listOf("a", "b", "c"), h.root.drawn.map { it.key.last() })
        assertTrue(b.isLeaving); assertTrue(released.isEmpty())
        h.at(60_000_000)
        assertTrue("fading", b.motion.alpha in 0.01f..0.99f)
        val t = h.settle()
        assertTrue("about its 120 ms", t in 110_000_000L..140_000_000L)
        assertEquals(listOf(b), released)
        assertEquals(listOf("a", "c"), h.root.drawn.map { it.key.last() })
        assertEquals(0, h.retainedCount)
    }

    @Test fun aNodeThatComesBackBeforeItsExitEndsIsTheSameNodeTurningBack() {
        val h = host(row); h.place()
        val b = h.root.children[1]
        items = listOf("a", "c"); h.refresh(); h.place()
        h.at(60_000_000)
        val faded = b.motion.alpha
        items = listOf("a", "b", "c"); h.refresh(); h.place()
        assertSame(b, h.root.children[1])
        assertFalse(b.isLeaving)
        assertEquals("from where it was", faded, b.motion.alpha, 1e-4f)
        h.settle()
        assertEquals(1f, b.motion.alpha, 0f)
        assertTrue(released.isEmpty()); assertEquals(0, h.retainedCount)
    }

    @Test fun anAncestorsExitCarriesItsSubtree() {
        val h = host("row { box visible=\"{@on}\" width=20 height=10 exit=\"fade 120ms\" { box width=5 height=5 exit=\"scale 120ms\" } }")
        h.place()
        val box = h.root.children[0]
        val inner = box.children[0]
        shown = false; h.refresh()
        assertTrue(box.isLeaving); assertFalse("its own exit does not start", inner.isLeaving)
        assertEquals(2, h.retainedCount)
        h.settle()
        assertEquals(setOf(box, inner), released.toSet()); assertEquals(2, released.size)
    }

    @Test fun withoutAnExitOrWithReducedMotionANodeGoesAtOnce() {
        val h = host("row { box each=\"{s.items}\" as=\"i\" width=10 height=10 }"); h.place()
        items = listOf("a"); h.refresh()
        assertEquals(2, released.size); assertEquals(1, h.root.drawn.size)
        released.clear(); items = listOf("a", "b", "c")
        val r = host(row); r.place()
        reduced = true
        items = listOf("a"); r.refresh()
        assertEquals(2, released.size); assertEquals(0, r.retainedCount)
    }

    @Test fun reducedMotionTurnedOnMidExitEndsIt() {
        val h = host(row); h.place()
        items = listOf("a"); h.refresh(); h.place()
        h.at(30_000_000)
        h.snapMotion()
        assertEquals(2, released.size); assertEquals(0, h.retainedCount)
        assertEquals(1, h.root.drawn.size)
    }

    @Test fun disposalMidExitReleasesEveryNodeOnce() {
        val h = host(row); h.place()
        items = listOf("a"); h.refresh(); h.place()
        h.at(30_000_000)
        h.dispose()
        assertEquals("every node once: root, row's three items", released.size, released.toSet().size)
        assertEquals(4, released.size)
    }

    @Test fun atMost64ExitsRunTheOldestEndFirst() {
        items = (0 until 100).map { "i$it" }
        val h = host(row); h.place()
        items = emptyList(); h.refresh()
        assertEquals(64, h.retainedCount)
        assertEquals("36 ended at once to make room", 36, released.size)
    }

    @Test fun aNewNodeArrivesFromItsEnterValues() {
        items = listOf("a")
        val h = host(row); h.place()
        assertEquals("the first build does not arrive", 1f, h.root.children[0].motion.alpha, 0f)
        items = listOf("a", "b"); h.refresh(); h.place()
        val b = h.root.children[1]
        assertEquals(0f, b.motion.alpha, 0f)
        assertTrue(h.isMoving)
        h.settle()
        assertEquals(1f, b.motion.alpha, 0f)
    }

    @Test fun theRootLeavesOnItsExitThenHides() {
        val h = host("row visible=\"{@on}\" exit=\"fade 120ms\" { box width=10 height=10 }"); h.place()
        shown = false; h.refresh()
        assertFalse("still drawn while it leaves", h.root.hidden.value)
        assertEquals(1, h.root.children.size)
        h.settle()
        assertTrue(h.root.hidden.value)
        shown = true; h.refresh()
        assertFalse(h.root.hidden.value)
    }
}
