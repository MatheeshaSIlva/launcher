package dev.launcher.engine.ui

import dev.launcher.engine.expr.Env
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlParser
import dev.launcher.engine.layout.Units
import dev.launcher.engine.model.ReadResult
import dev.launcher.engine.model.TokenCheck
import dev.launcher.engine.model.Validator
import java.io.File
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/** E2 slice 6: the built-in `status-bar.kdl` is valid and shows the time as StatusBarView does (H:mm or h:mm, no AM/PM). */
class StatusBarKdlTest {
    private val noGlyphs = GlyphCache<Symbol>({ _, _ -> null }, { it.run() }, { it.run() })
    private val file = File("src/main/assets/kdl/components/status-bar.kdl")
    private val noTokens = object : TokenCheck { override fun exists(key: String) = false }

    private fun component() = (Validator(noTokens).load(KdlParser.parse(file.readText())) as ReadResult.Ok).component

    private fun env(hour24: Boolean) = object : Env {
        override fun state(name: String) = Value.Null
        override fun prop(name: String) = when (name) { "hour24" -> Value.Bool.of(hour24); else -> Value.Null }
        override fun token(key: String) = Value.Null
        override fun source(path: String) = Value.Null
        override fun now() = 13L * 3_600_000 + 5 * 60_000   // 13:05 UTC
        override val locale: Locale = Locale.US
        override val zone: TimeZone = TimeZone.getTimeZone("UTC")
    }

    private fun time(hour24: Boolean): Value {
        val host = ComponentHost(component(), env(hour24), Units(1f, 1f), { ComponentView.defaultPainter(it, FakeTokens, noGlyphs) })
        return host.root.children.single().value("content")
    }

    @Test fun validAsShipped() {
        val c = component()
        assertEquals("status-bar", c.name)
        assertEquals(listOf("time"), c.uses.sources.map { it.name })
    }

    @Test fun theTimeIn24And12Hours() {
        assertEquals(Value.Str("13:05"), time(hour24 = true))
        assertEquals(Value.Str("1:05"), time(hour24 = false))
    }

    @Test fun theTextIsStatusBarViewsAndItsCentreLineIsTheCapitalsMiddle() {
        val text = ComponentHost(component(), env(true), Units(3f, 3f), { ComponentView.defaultPainter(it, FakeTokens, noGlyphs) }).root.children.single()
        assertEquals(45f, text.length("size"))   // 15 pt at 3 px a point
        assertEquals(600f, text.number("weight"))
        assertEquals(Value.Bool.TRUE, text.value("tnum"))
        assertEquals(34f * 3, ComponentHost(component(), env(true), Units(3f, 3f), { ComponentView.defaultPainter(it, FakeTokens, noGlyphs) }).root.length("padding-start"))
        // StatusBarView: baseline = centre + 0.727 x size / 2, so the centre is the baseline less that.
        assertEquals(40f - 0.727f * 45f / 2f, TextPainter.centreLine(40f, 45f), 1e-4f)
    }

    @Test fun aNewUnitConvertsLengthsAgain() {   // a turn: the launcher's unit follows the display's short side (the re-measure: emulator)
        val units = Units(3f, 3f)
        val text = ComponentHost(component(), env(true), units, { ComponentView.defaultPainter(it, FakeTokens, noGlyphs) }).root.children.single()
        units.unitPx = 2.5f
        assertEquals(37.5f, text.length("size"))
    }

    private object FakeTokens : dev.launcher.engine.token.Tokens {
        override fun exists(key: String) = false
        override fun kind(key: String) = null
        override fun token(key: String) = Value.Null
        override fun textStyle(key: String) = null
        override fun typeface(family: String, weight: Int, sizePt: Float) = throw UnsupportedOperationException()
        override val version = 0
        override fun onChange(l: () -> Unit) {}
        override fun removeOnChange(l: () -> Unit) {}
    }
}
