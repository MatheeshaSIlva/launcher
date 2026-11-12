package dev.launcher.engine.ui

import android.graphics.Canvas
import dev.launcher.engine.expr.Env
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlParser
import dev.launcher.engine.layout.Units
import dev.launcher.engine.model.CurveSpec
import dev.launcher.engine.model.ReadResult
import dev.launcher.engine.model.TokenCheck
import dev.launcher.engine.model.Validator
import java.io.File
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shipped island's declarations and bindings; real ink, baseline and clearance belong to IslandProof. */
class IslandKdlTest {
    // The island has no token references yet: refusing all tokens also detects an accidental dependency.
    private val tokens = object : TokenCheck { override fun exists(key: String) = false }
    private fun component() = (Validator(tokens).load(KdlParser.parse(
        File("src/main/assets/kdl/components/island.kdl").readText()
    )) as ReadResult.Ok).component
    private val painter = object : Painter {
        override fun key(node: HostNode): Any? = null
        override fun paint(canvas: Canvas, node: HostNode, width: Int, height: Int) {}
    }

    private fun host(hour24: Boolean = true, hour: Int = 13, minute: Int = 5, level: Int = 64,
        units: Units = Units(1f, 1f)): ComponentHost {
        val env = object : Env {
            override fun state(name: String) = Value.Null
            override fun prop(name: String) = if (name == "hour24") Value.Bool.of(hour24) else Value.Null
            override fun token(key: String) = Value.Null
            override fun source(path: String) = if (path == "battery.level") Value.Num(level.toDouble()) else Value.Null
            override fun now() = hour * 3_600_000L + minute * 60_000L
            override val locale: Locale = Locale.US
            override val zone: TimeZone = TimeZone.getTimeZone("UTC")
        }
        return ComponentHost(component(), env, units, { painter })
    }

    @Test fun validAsShippedWithOnlyTheDeclaredSourcesAndNoCameraArtwork() {
        val c = component()
        assertEquals("island", c.name)
        assertEquals(listOf("device", "time", "battery"), c.uses.sources.map { it.name })
        assertEquals(listOf("hour24"), c.props.map { it.name })
        val root = host().root
        assertEquals("box", root.view.primitive)
        assertEquals(Value.Str("cutout"), root.value("place"))
        assertEquals(Value.Str("full"), root.value("radius"))
        assertEquals(Value.Str("#000000"), root.value("fill"))
        assertEquals(Value.Str("centre-line"), root.value("align"))
        val row = root.children.single()
        assertEquals("row", row.view.primitive)
        assertEquals(Value.Str("centre-line"), row.value("align"))
        assertEquals(listOf("text", "spacer", "text"), row.children.map { it.view.primitive })
        assertEquals(1f, row.children[1].flex)
    }

    @Test fun clockAndBatteryBindingsCoverWidthChangingValues() {
        for ((hour, minute, formatted) in listOf(Triple(9, 59, "9:59"), Triple(10, 0, "10:00"),
            Triple(23, 59, "23:59"), Triple(0, 0, "0:00"))) {
            assertEquals(Value.Str(formatted), host(hour = hour, minute = minute).root.children.single().children.first().value("content"))
        }
        assertEquals(Value.Str("13:05"), host().root.children.single().children.first().value("content"))
        assertEquals(Value.Str("1:05"), host(hour24 = false).root.children.single().children.first().value("content"))
        assertEquals(Value.Str("12:00"), host(hour24 = false, hour = 0, minute = 0).root.children.single().children.first().value("content"))
        for (level in listOf(9, 10, 64, 99, 100))
            assertEquals(Value.Num(level.toDouble()), host(level = level).root.children.single().children.last().value("content"))
    }

    @Test fun geometryAndTypographyStayInDpAcrossDensitiesAndLauncherScales() {
        for (density in listOf(1f, 2.625f)) {
            val units = Units(3f, density)
            val root = host(units = units).root
            for (scale in listOf(3f, 4.5f)) {
                units.unitPx = scale
                assertEquals(132f * density, root.length("width"))
                assertEquals(32f * density, root.length("height"))
                val row = root.children.single()
                assertEquals(14f * density, row.length("padding-start"))
                assertEquals(12f * density, row.length("padding-end"))
                val texts = row.children.filter { it.view.primitive == "text" }
                assertEquals(2, texts.size)
                for (text in texts) {
                    assertEquals(Value.Str("jetbrains-mono"), text.value("font"))
                    assertEquals(13f * density, text.length("size"))
                    assertEquals(400f, text.number("weight"))
                    assertEquals(Value.Str("#F2F2F2"), text.value("color"))
                    assertTrue(text.view.motion?.roll != null)
                    assertEquals(CurveSpec.Role("motion.text.roll"), text.view.motion?.rollCurve())
                }
            }
        }
    }
}
