package dev.launcher.engine.ui

import dev.launcher.engine.expr.Env
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlParser
import dev.launcher.engine.layout.Size
import dev.launcher.engine.layout.Units
import dev.launcher.engine.model.ComponentReader
import dev.launcher.engine.model.ReadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** T-005 (slice 9): what an `icon` measures and keys on, over hand-run glyph loads (drawing is the emulator diff's). */
class IconPainterTest {
    private val worker = ArrayDeque<Runnable>()
    private val owner = ArrayDeque<Runnable>()
    /** Boxes in the set's units, as statusbar.json has them. */
    private val boxes = mapOf("wifi.2" to (17f to 11.2f), "wifi.3" to (17f to 11.2f), "cell.4" to (18.6f to 11.7f), "dot" to (1f to 1f))
    private val cache = GlyphCache<Symbol>({ _, symbol -> boxes[symbol]?.let { (w, h) -> Symbol(w, h) { _, _, _, _, _, _ -> } } },
        { worker.add(it) }, { owner.add(it) })
    private fun loadAll() { while (worker.isNotEmpty() || owner.isNotEmpty()) { while (worker.isNotEmpty()) worker.removeFirst().run(); while (owner.isNotEmpty()) owner.removeFirst().run() } }

    private var level = 2
    private val env = object : Env {
        override fun state(name: String) = if (name == "level") Value.Num(level.toDouble()) else Value.Null
        override fun prop(name: String) = Value.Null
        override fun token(key: String) = Value.Null
        override fun source(path: String) = Value.Null
        override fun now() = 0L
    }
    private val tokens = object : dev.launcher.engine.token.Tokens {
        override fun exists(key: String) = false
        override fun kind(key: String) = null
        override fun token(key: String) = Value.Null
        override fun textStyle(key: String) = null
        override fun typeface(family: String, weight: Int, sizePt: Float) = throw UnsupportedOperationException()
        override val version = 0
        override fun onChange(l: () -> Unit) {}
        override fun removeOnChange(l: () -> Unit) {}
    }

    private fun host(icon: String) = ComponentHost((ComponentReader.read(KdlParser.parse("""
        component "i" {
          state { level 2 }
          view { $icon }
        }""".trimIndent())) as ReadResult.Ok).component, env, Units(1f, 1f), { v -> ComponentView.defaultPainter(v, tokens, cache) })

    @Test fun aBoxMeasuresAtItsLargerSideRoundedUpWithAnExactCentre() {
        val wifi = IconPainter.metrics(17f to 11.2f, 17f)
        assertEquals(IconPainter.Metrics(17, 12, 5.6f, 17f, 11.2f), wifi)
        val cell = IconPainter.metrics(18.6f to 11.7f, 18.6f)                  // a fractional size
        assertEquals(19, cell.width); assertEquals(12, cell.height); assertEquals(5.85f, cell.centre, 1e-5f)
        val tall = IconPainter.metrics(5.4f to 9f, 14.5f)                      // portrait: height is the size
        assertEquals(15, tall.height); assertEquals(9, tall.width); assertEquals(7.25f, tall.centre, 1e-5f)
    }

    @Test fun noGlyphMeasuresAsASquareOfItsSize() {
        assertEquals(IconPainter.Metrics(15, 15, 7.25f, 14.5f, 14.5f), IconPainter.metrics(null, 14.5f))
    }

    @Test fun theKeyIsStableAndTheTemplateReKeysIt() {
        val h = host("""icon "wifi.{${'$'}level}" size=(px)17""")
        val n = h.root
        n.painter.measure(n, 100, 100)
        loadAll()
        val k = n.painter.key(n)
        assertEquals(k, n.painter.key(n))
        assertEquals("wifi.2", n.value("symbol").let { (it as Value.Str).value })
        level = 3
        h.refresh()
        assertNotEquals(k, n.painter.key(n))
        assertEquals("wifi.3", (n.value("symbol") as Value.Str).value)
    }

    @Test fun aGlyphArrivingRemeasuresAndReRecordsEvenAtTheSameSize() {
        val n = host("""icon "dot" size=(px)17""").root                       // a 1 x 1 box: the same 17 x 17 as the placeholder
        val pending = n.painter.measure(n, 100, 100)!!
        val before = n.painter.key(n)
        assertEquals(1, cache.loads)
        loadAll()
        val ready = n.painter.measure(n, 100, 100)!!
        assertEquals(pending.size, ready.size)
        assertNotEquals(before, n.painter.key(n))                               // so ComposeHost records the content again
    }

    @Test fun aLaterGlyphMayChangeTheIconsSize() {
        val n = host("""icon "cell.4" size=(px)18.6""").root
        assertEquals(Size(19, 19), n.painter.measure(n, 100, 100)!!.size)       // pending: the square
        loadAll()
        assertEquals(Size(19, 12), n.painter.measure(n, 100, 100)!!.size)
    }

    @Test fun aSymbolNowhereStaysASquareAndIsNotAskedForAgain() {
        val n = host("""icon "nope" size=(px)10""").root
        n.painter.measure(n, 100, 100); loadAll()
        val loads = cache.loads
        assertEquals(Size(10, 10), n.painter.measure(n, 100, 100)!!.size)
        n.painter.key(n); n.painter.measure(n, 100, 100)
        assertEquals(loads, cache.loads)
    }
}
