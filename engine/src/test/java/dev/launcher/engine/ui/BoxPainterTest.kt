package dev.launcher.engine.ui

import dev.launcher.engine.expr.Env
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlParser
import dev.launcher.engine.layout.Constraints
import dev.launcher.engine.layout.Units
import dev.launcher.engine.model.ComponentReader
import dev.launcher.engine.model.ReadResult
import org.junit.Assert.*
import org.junit.Test

class BoxPainterTest {
    private var fill: Value = Value.Color(0x80445566.toInt())
    private val env = object : Env {
        override fun state(name: String) = fill
        override fun prop(name: String) = Value.Null
        override fun token(key: String) = Value.Null
        override fun source(path: String) = Value.Null
        override fun now() = 0L
    }
    private fun host() = ComponentHost((ComponentReader.read(KdlParser.parse("""
        component "box" {
          state { fill "#80445566" }
          view { box width=24.6 height=12.6 radius=4.2 stroke=0.5 stroke-color="#FFFFFF" fill="${'$'}fill" reveal=0.5 }
        }
    """.trimIndent())) as ReadResult.Ok).component, env, Units(2.625f, 3f), { BoxPainter() })

    @Test fun boxIsAContainerWithExactBoundsAndAStableDrawingKey() {
        val n = host().root
        assertNull(n.painter.measure(n, 100, 100))
        val a = n.arrange(Constraints(0, 100, 0, 100), emptyList())
        assertEquals(65, a.measured.size.width)
        assertEquals(64.575f, a.measured.exactWidth, 1e-5f)
        assertEquals(33.075f, a.measured.exactHeight, 1e-5f)
        assertEquals(n.painter.key(n), n.painter.key(n))
    }

    @Test fun fillChangesAndNullRekeyWithoutChangingLayoutOrThrowing() {
        val h = host(); val n = h.root
        val before = n.painter.key(n)
        val measure = n.arrange(Constraints(0, 100, 0, 100), emptyList())
        fill = Value.Null; h.refresh()
        assertNotEquals(before, n.painter.key(n))
        assertEquals(measure, n.arrange(Constraints(0, 100, 0, 100), emptyList()))
        assertSame(Value.Null, n.value("fill"))
    }
}
