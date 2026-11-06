package dev.launcher.engine.ui

import android.graphics.Canvas
import dev.launcher.engine.expr.Env
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlParser
import dev.launcher.engine.layout.Child
import dev.launcher.engine.layout.Constraints
import dev.launcher.engine.layout.Measured
import dev.launcher.engine.layout.Size
import dev.launcher.engine.layout.Units
import dev.launcher.engine.model.ComponentReader
import dev.launcher.engine.model.ReadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * E2 slice 5b: the host's tree builder and bindings, on the JVM over a fake painter. What needs a device (Compose's
 * measure pass over real measurables, the draw node's RenderNodes, Motion reaching the screen, the cleared semantics)
 * is slice 6's to check there.
 */
class HostTest {
    private val sources = mutableMapOf<String, Value>("battery.level" to Value.Num(50.0))
    private val env = object : Env {
        override fun state(name: String) = if (name == "level") Value.Num(7.0) else Value.Null
        override fun prop(name: String) = if (name == "inset") Value.Num(4.0, "dp") else Value.Null
        override fun token(key: String) = Value.Null
        override fun source(path: String) = sources[path] ?: Value.Null
        override fun now() = 0L
    }
    private val painted = ArrayList<String>()
    private val painter = object : Painter {
        override fun key(node: HostNode) = node.value("content")
        override fun paint(canvas: Canvas, node: HostNode, width: Int, height: Int) {}
        override fun measure(node: HostNode, maxWidth: Int, maxHeight: Int) =
            if (node.view.primitive == "text") Measured(Size(30, 20), 12f) else null
    }
    private val kdl = """
        component "t" {
          props { inset type=dp default=4 }
          state { level 7 }
          uses { source battery }
          view {
            row gap=(pt)2 align=centre-line padding-start="@inset" {
              text "{battery.level}%" weight=600
              spacer flex=1
              box width="{battery.level * 0.1pt}" height=(dp)3 visible="${'$'}level"
            }
          }
        }"""
    private fun host() = ComponentHost((ComponentReader.read(KdlParser.parse(kdl.trimIndent())) as ReadResult.Ok).component,
        env, Units(unitPx = 2f, density = 3f), { v -> painted += v.primitive; painter })

    @Test fun theTreeAndItsBindings() {
        val h = host()
        assertEquals(listOf("box", "row", "spacer", "text"), painted.sorted())   // one painter per primitive
        val row = h.root
        assertEquals(listOf("text", "spacer", "box"), row.children.map { it.view.primitive })
        assertEquals(setOf("gap", "align", "padding-start"), row.bindings.keys)
        assertNull(row.bindings.getValue("gap").compiled)   // literals are values, not expressions
        assertEquals(4f, row.length("gap"))   // 2 pt at 2 px a point
        assertEquals(12f, row.length("padding-start"))   // @inset: 4 dp at 3 px a dp
        val text = row.children[0]
        assertEquals(setOf("content", "weight"), text.bindings.keys)   // the argument under its schema name
        assertEquals(Value.Str("50%"), text.value("content"))
        assertEquals(setOf("battery.level"), text.bindings.getValue("content").deps!!.sources.keys)
        assertEquals(1f, row.children[1].flex)
        val box = row.children[2]
        assertEquals(10f, box.length("width"))   // 50 x 0.1 pt
        assertEquals(9f, box.length("height"))
        assertEquals(Value.Num(7.0), box.value("visible"))
    }

    @Test fun refreshWritesOnlyTheBindingsAChangeReaches() {
        val h = host()
        val text = h.root.children[0].bindings.getValue("content").state
        val width = h.root.children[2].bindings.getValue("width").state
        val visible = h.root.children[2].bindings.getValue("visible").state
        val visibleBefore = visible.value
        sources["battery.level"] = Value.Num(60.0)
        h.refresh { false }
        assertEquals(Value.Str("50%"), text.value)   // nothing picked, nothing evaluated
        h.refresh { "battery.level" in it.sources }
        assertEquals(Value.Str("60%"), text.value)
        assertEquals(Value.Num(6.0, "pt"), width.value)
        assertSame(visibleBefore, visible.value)
        val textValue = text.value
        h.refresh()
        assertSame("an unchanged value is not written again", textValue, text.value)
    }

    @Test fun arrangeRunsTheLayoutMathsOverTheBoundValues() {
        val row = host().root
        class Kid(val w: Int, val h: Int, val c: Float, override val flex: Float = 0f) : Child {
            override fun measure(c: Constraints) = Measured(Size(if (flex > 0f) c.minWidth else w, h), this.c)
        }
        val a = row.arrange(Constraints(0, 100, 0, 50), listOf(Kid(30, 20, 12f), Kid(0, 0, 0f, flex = 1f), Kid(10, 9, 4.5f)))
        assertEquals(listOf(12, 46, 90), a.placements.map { it.x })   // padding 12, gaps 4, the spacer takes 40
        assertEquals(listOf(0, 8), a.placements.map { it.y }.let { listOf(it[0], it[2]) })   // on the text's centre line
        assertEquals(Size(100, 20), a.measured.size)
        val leaf = row.children[0].arrange(Constraints(0, 100, 0, 50), emptyList())
        assertEquals(Measured(Size(30, 20), 12f), leaf.measured)   // a leaf takes its painter's measure
        assertNotNull(row.motion)
    }
}
