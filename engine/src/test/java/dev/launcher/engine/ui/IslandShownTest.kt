package dev.launcher.engine.ui

import android.graphics.Canvas
import dev.launcher.engine.expr.Env
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlParser
import dev.launcher.engine.layout.Units
import dev.launcher.engine.model.ReadResult
import dev.launcher.engine.model.TokenCheck
import dev.launcher.engine.model.Validator
import dev.launcher.engine.ui.motion.MotionContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.TimeZone

/** The owner, 2026-11-14: after a full-screen game the island never came back. Hidden, then shown again. */
class IslandShownTest {
    private var shown = true
    private var clock = 0L
    private val env = object : Env {
        override fun state(name: String) = Value.Null
        override fun prop(name: String) = when (name) {
            "shown" -> Value.Bool.of(shown); "hour24" -> Value.Bool.TRUE; "mode" -> Value.Str("minimal")
            "banner-width" -> Value.Num(380.0, "dp"); "camera-size" -> Value.Num(30.0, "dp"); else -> Value.Null
        }
        override fun token(key: String) = Value.Null
        override fun source(path: String) = when (path) {
            "battery.level" -> Value.Num(64.0); "notifications.shown" -> Value.List(emptyList()); else -> Value.Null
        }
        override fun now() = 13 * 3_600_000L
        override val locale: Locale = Locale.US
        override val zone: TimeZone = TimeZone.getTimeZone("UTC")
    }
    private val tokens = object : TokenCheck { override fun exists(key: String) = false }
    private val painter = object : Painter {
        override fun key(node: HostNode): Any? = null
        override fun paint(canvas: Canvas, node: HostNode, width: Int, height: Int) {}
    }

    private fun ComponentHost.run(ms: Long) { val end = clock + ms * 1_000_000L; while (clock < end) { clock += 8_333_333; step(clock) } }

    @Test fun hiddenThenShownTheIslandComesBack() {
        val def = (Validator(tokens).load(KdlParser.parse(File("src/main/assets/kdl/components/island.kdl").readText())) as ReadResult.Ok).component
        val h = ComponentHost(def, env, Units(1f, 1f), { painter }, {}, {}, MotionContext(now = { clock }), {})
        h.root.placed(0f, 0f, 0.5f, 0.5f)
        h.run(500)
        shown = false; h.refresh(); h.root.placed(0f, 0f, 0.5f, 0.5f)
        h.run(1000)
        val dot = h.root.children.first { it.value("label") == Value.Str("Pill") }
        assertEquals("hidden: a dot the camera's size", 30f, dot.length("width")!!, 0.01f)
        assertEquals(30f, dot.length("height")!!, 0.01f)
        assertEquals("its time left", false, generateSequence(listOf(dot)) { l -> l.flatMap { it.children }.takeIf { it.isNotEmpty() } }.flatten().any { it.value("label") == Value.Str("Time") })
        shown = true; h.refresh(); h.root.placed(0f, 0f, 0.5f, 0.5f)
        h.run(1000)
        assertFalse("never hidden: it shrinks into the camera instead", h.root.hidden.value)
        val pill = h.root.children.first { it.value("label") == Value.Str("Pill") }
        assertEquals("back to its full width", 132f, pill.length("width")!!, 0.01f)
        assertEquals(32f, pill.length("height")!!, 0.01f)
    }
}
