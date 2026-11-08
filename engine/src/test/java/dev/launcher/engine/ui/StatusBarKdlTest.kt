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

private const val LOW = 0xFFFF3B30.toInt()
private const val SAVER = 0xFFFFCC00.toInt()
private const val CHARGING = 0xFF34C759.toInt()

/** E2 slice 6: the built-in `status-bar.kdl` is valid and shows the time as StatusBarView does (H:mm or h:mm, no AM/PM). */
class StatusBarKdlTest {
    private val noGlyphs = GlyphCache<Symbol>({ _, _ -> null }, { it.run() }, { it.run() })
    private val file = File("src/main/assets/kdl/components/status-bar.kdl")
    private val noTokens = object : TokenCheck { override fun exists(key: String) = key.startsWith("comp.statusbar.battery-") }

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
        return host.root.children.first().value("content")
    }

    @Test fun validAsShipped() {
        val c = component()
        assertEquals("status-bar", c.name)
        assertEquals(listOf("time", "battery"), c.uses.sources.map { it.name })
    }

    @Test fun theTimeIn24And12Hours() {
        assertEquals(Value.Str("13:05"), time(hour24 = true))
        assertEquals(Value.Str("1:05"), time(hour24 = false))
    }

    @Test fun theTextIsStatusBarViewsAndItsCentreLineIsTheCapitalsMiddle() {
        val text = ComponentHost(component(), env(true), Units(3f, 3f), { ComponentView.defaultPainter(it, FakeTokens, noGlyphs) }).root.children.first()
        assertEquals(45f, text.length("size"))   // 15 pt at 3 px a point
        assertEquals(600f, text.number("weight"))
        assertEquals(Value.Bool.TRUE, text.value("tnum"))
        assertEquals(34f * 3, ComponentHost(component(), env(true), Units(3f, 3f), { ComponentView.defaultPainter(it, FakeTokens, noGlyphs) }).root.length("padding-start"))
        // StatusBarView: baseline = centre + 0.727 x size / 2, so the centre is the baseline less that.
        assertEquals(40f - 0.727f * 45f / 2f, TextPainter.centreLine(40f, 45f), 1e-4f)
    }

    @Test fun aNewUnitConvertsLengthsAgain() {   // a turn: the launcher's unit follows the display's short side (the re-measure: emulator)
        val units = Units(3f, 3f)
        val text = ComponentHost(component(), env(true), units, { ComponentView.defaultPainter(it, FakeTokens, noGlyphs) }).root.children.first()
        units.unitPx = 2.5f
        assertEquals(37.5f, text.length("size"))
    }

    /** The level's colour as the file computes it, against StatusBarView's `blend` chain (red below 25 %, saver, charging). */
    private fun levelColour(level: Int, saver: Boolean = false, charging: Boolean = false, content: Int = 0xFFFFFFFF.toInt()): Int {
        val env = object : Env {
            override fun state(name: String) = Value.Null
            override fun prop(name: String) = if (name == "color") Value.Color(content) else Value.Null
            override fun token(key: String) = when {
                key.endsWith("battery-low-color") -> Value.Color(LOW)
                key.endsWith("battery-saver-color") -> Value.Color(SAVER)
                key.endsWith("battery-charging-color") -> Value.Color(CHARGING)
                else -> Value.Null
            }
            override fun source(path: String) = when (path) {
                "battery.level" -> Value.Num(level.toDouble())
                "battery.power-save" -> Value.Bool.of(saver)
                "battery.charging" -> Value.Bool.of(charging)
                else -> Value.Null
            }
            override fun now() = 0L
            override val locale: Locale = Locale.US
            override val zone: TimeZone = TimeZone.getTimeZone("UTC")
        }
        val root = ComponentHost(component(), env, Units(1f, 1f), { ComponentView.defaultPainter(it, FakeTokens, noGlyphs) }).root
        val body = root.children.last().children.first()      // the battery's row, its stack
        return (body.children[1].value("fill") as Value.Color).argb
    }

    private fun blend(a: Int, b: Int, t: Float): Int {            // StatusBarView.blend
        if (t <= 0f) return a
        if (t >= 1f) return b
        fun ch(s: Int) = ((((a shr s) and 0xFF) + ((((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t)) + 0.5f).toInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    private fun reference(level: Int, saver: Boolean, charging: Boolean, content: Int): Int {
        var c = blend(content or (0xFF shl 24), LOW, ((25f - level) / 8f).coerceIn(0f, 1f))
        c = blend(c, SAVER, if (saver) 1f else 0f)
        c = blend(c, CHARGING, if (charging) 1f else 0f)
        return (content and 0xFF000000.toInt()) or (c and 0xFFFFFF)
    }

    @Test fun theBatterysColourIsStatusBarViewsAtEveryRestingState() {
        for (content in listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0x80FFFFFF.toInt()))
            for (level in listOf(100, 50, 25, 24, 21, 20, 17, 16, 5, 0))
                for (saver in listOf(false, true)) for (charging in listOf(false, true))
                    assertEquals("level $level saver $saver charging $charging", reference(level, saver, charging, content),
                        levelColour(level, saver, charging, content))
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
