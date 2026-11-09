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
    /** T-007: a notification arriving and a prop worked out from it reach readers together, or neither. */
    @org.junit.Test fun aSettledPropArrivesInOneSnapshotWithWhatCausedIt() {
        var max = 1.0
        val list = { n: Int -> Value.List(List(n) { Value.Record(mapOf("id" to Value.Str("a$it"))) }) }
        var icons = list(1)
        val e = object : Env by env {
            override fun prop(name: String) = if (name == "max") Value.Num(max) else Value.Null
            override fun source(path: String) = if (path == "n.icons") icons else Value.Null
        }
        val d = (ComponentReader.read(KdlParser.parse("""
            component "c" version="0.1.0" {
              props { max type=number default=1 }
              uses { source n }
              view { row { spacer each="{take(n.icons, @max)}" as="i" size=1
                           spacer size=2 visible="{length(n.icons) > @max}" } }
            }""".trimIndent())) as ReadResult.Ok).component
        val h = ComponentHost(d, e, dev.launcher.engine.layout.Units(1f, 1f), { painter })
        assertEquals(1, h.root.children.size)
        icons = list(3)
        val seen = ArrayList<Int>()
        h.settled({ true }) {
            max = 3.0
            seen += androidx.compose.runtime.snapshots.Snapshot.global { h.root.children.size }
            true
        }
        assertEquals(listOf(1), seen)                   // nothing published while it settled
        assertEquals(3, h.root.children.size)           // three icons, no dot: never four, never two and a dot
        icons = list(4)
        h.settled({ true }) { false }
        assertEquals(4, h.root.children.size)           // three icons and the dot
    }
    @org.junit.Test fun aNodeNotVisibleIsNotThereAndComesBack() {
        var on = false
        val e = object : Env by env { override fun source(path: String) = Value.Bool.of(on) }
        val d = (ComponentReader.read(KdlParser.parse("""
            component "c" version="0.1.0" {
              uses { source s }
              view { row { spacer size=1 visible="{s.on}" } }
            }""".trimIndent())) as ReadResult.Ok).component
        val h = ComponentHost(d, e, dev.launcher.engine.layout.Units(1f, 1f), { painter })
        assertEquals(0, h.root.children.size)
        on = true; h.refresh()
        assertEquals(1, h.root.children.size)
        on = false; h.refresh()
        assertEquals(0, h.root.children.size)
    }

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

    private fun item(id: String, rev: Int = 0, children: Value = Value.List(emptyList())) = Value.Record(mapOf(
        "id" to Value.Str(id), "rev" to Value.Num(rev.toDouble()), "children" to children))
    private fun repeatHost(body: String = """text "{n.id}:{n.rev}" each="{battery.icons}" as=n""",
                           removed: (HostNode) -> Unit = {}) = ComponentHost(
        (ComponentReader.read(KdlParser.parse("""component "repeat" {
            view { row { $body } }
        }""")) as ReadResult.Ok).component, env, Units(1f, 1f), { painter }, onRemoved = removed)

    @Test fun repeatsRetainIdentityAcrossReorderAndRefreshLocalPathsWithFilteredSources() {
        sources["battery.icons"] = Value.List(listOf(item("a"), item("b")))
        val removed = ArrayList<HostNode>()
        val h = repeatHost(removed = { removed += it })
        val (a, b) = h.root.children
        val binding = a.bindings.getValue("content")
        assertEquals(Value.Str("a:0"), a.value("content"))
        sources["battery.icons"] = Value.List(listOf(item("b"), item("a", 2)))
        h.refresh { "battery.icons" in it.sources }
        assertSame(b, h.root.children[0]); assertSame(a, h.root.children[1])
        assertSame(binding, a.bindings.getValue("content"))
        assertEquals(Value.Str("a:2"), a.value("content"))
        assertSame(a.motion, h.root.children[1].motion)
        sources["battery.icons"] = Value.List(listOf(item("a", 2)))
        h.refresh()
        assertEquals(listOf(b), removed)
        val value = a.value("content")
        h.refresh()
        assertSame(value, a.value("content"))
        h.dispose(); h.dispose()
        assertEquals(3, removed.size) // a, b and the root, each once
        sources["battery.icons"] = Value.List(emptyList()); h.refresh()
        assertEquals(3, removed.size)
    }

    @Test fun repeatFailuresRemoveTheWholeExpansionAndRecoverWithoutBindingGrowth() {
        val removed = ArrayList<HostNode>()
        sources["battery.icons"] = Value.List(listOf(item("a")))
        val h = repeatHost(removed = { removed += it })
        val bad = listOf(Value.Null, Value.Num(1.0), Value.List(listOf(item("a"), item("a"))),
            Value.List(listOf(Value.Record(emptyMap()))), Value.List(listOf(Value.Str("a"))),
            Value.List(List(257) { item("$it") }))
        for (value in bad) {
            sources["battery.icons"] = value; h.refresh()
            assertEquals(0, h.root.children.size); assertEquals(1, h.diagnostics.size)
            sources["battery.icons"] = Value.List(listOf(item("a"))); h.refresh()
            assertEquals(1, h.root.children.size); assertEquals(emptyList<String>(), h.diagnostics)
        }
        repeat(100) {
            sources["battery.icons"] = Value.List(emptyList()); h.refresh()
            sources["battery.icons"] = Value.List(listOf(item("a"))); h.refresh()
        }
        val gone = removed.last()
        val old = gone.value("content")
        sources["battery.icons"] = Value.List(listOf(item("a", 3))); h.refresh()
        assertSame(old, gone.value("content")) // removed bindings are never visited again
        assertEquals(106, removed.size)
    }

    @Test fun nestedKeysIncludeTemplateAndOuterItemAndLimitsPreflightBeforePainters() {
        val kids = Value.List(listOf(item("same")))
        sources["battery.icons"] = Value.List(listOf(item("a", children = kids), item("b", children = kids)))
        val h = repeatHost("""row each="{battery.icons}" as=n {
            text "{n.id}:{c.id}" each="{n.children}" as=c
            text "{c.id}" each="{n.children}" as=c
        }""")
        val a = h.root.children[0]; val b = h.root.children[1]
        org.junit.Assert.assertNotSame(a.children[0], b.children[0])
        org.junit.Assert.assertNotSame(a.children[0], a.children[1])
        assertEquals(Value.Str("a:same"), a.children[0].value("content"))
        sources["battery.icons"] = Value.List(listOf(item("b", children = kids), item("a", children = kids)))
        h.refresh { "battery.icons" in it.sources }
        assertSame(a, h.root.children[1]); assertSame(a.children[0], h.root.children[1].children[0])
        val manyKids = Value.List(List(16) { item("$it") })
        sources["battery.icons"] = Value.List(List(256) { item("$it", children = manyKids) })
        var made = 0
        val def = h.def
        val bounded = ComponentHost(def, env, Units(1f, 1f), { made++; painter })
        assertEquals(1, made); assertEquals(0, bounded.root.children.size)
        assertEquals(1, bounded.diagnostics.size)
        sources["battery.icons"] = Value.List(listOf(item("a", children = kids))); bounded.refresh()
        assertEquals(1, bounded.root.children.size); assertEquals(4, made)
    }

    @Test fun exactLimitsAreAcceptedAndOneMoreNodeTearsDownTheRepeat() {
        sources["battery.icons"] = Value.List(List(256) { item("$it") })
        assertEquals(256, repeatHost().root.children.size)
        // 1 root + 256 repeated rows + (256 * 14) text children + 255 extra rows = 4096.
        val inner = (1..14).joinToString("\n") { "text x" }
        val fixed = (1..255).joinToString("\n") { "row" }
        val h = repeatHost("""$fixed
            row each="{battery.icons}" as=n { $inner }
        """)
        assertEquals(511, h.root.children.size)
        assertEquals(emptyList<String>(), h.diagnostics)
        val tooMany = repeatHost("""$fixed
            row
            row each="{battery.icons}" as=n { $inner }
        """)
        assertEquals(256, tooMany.root.children.size)
        assertEquals(1, tooMany.diagnostics.size)
    }

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
