package dev.launcher.engine.token

import android.graphics.Typeface
import dev.launcher.app.design.NumUnit
import dev.launcher.app.design.TextStyle
import dev.launcher.engine.expr.Compiled
import dev.launcher.engine.expr.Env
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlParser
import dev.launcher.engine.model.ReadResult
import dev.launcher.engine.model.Validator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.launcher.app.design.Value as DesignValue

/** E2 slice 8: tokens over a fake of the launcher's resolver (the real one is checked on the emulator in slice 6). */
class TokensTest {
    private class FakeDesign : DesignSource {
        var dark = 0f
        var changed: () -> Unit = {}
        val values = mutableMapOf<String, DesignValue>(
            "comp.statusbar.battery-low-color" to DesignValue.Color(0xFFFF3B30.toInt(), 0xFFFF453A.toInt()),
            "comp.statusbar.time-size" to DesignValue.Number(15f, NumUnit.PT),
            "comp.statusbar.fade" to DesignValue.Number(0.4f, NumUnit.FRACTION),
            "motion.statusbar.hold" to DesignValue.Number(250f, NumUnit.MS),
            "sys.font.text" to DesignValue.Choice("inter"),
            "comp.statusbar.layout" to DesignValue.Choice("iphone"),
            "comp.statusbar.time" to DesignValue.Text(TextStyle("text", 600, 15f, 20f, 0f)),
        )
        override fun resolved(key: String) = values[key]
        /** Light and dark blended at [dark], as Appearance.mix does channel by channel (here the green one: they differ there). */
        override fun color(key: String): Int {
            val c = values[key] as DesignValue.Color
            val g = { v: Int -> (v shr 8) and 0xFF }
            return (c.light and 0xFFFF00FF.toInt()) or ((g(c.light) + ((g(c.dark) - g(c.light)) * dark + 0.5f).toInt()) shl 8)
        }
        override fun textStyle(key: String) = (values[key] as DesignValue.Text).style.let { it.copy(sizePt = it.sizePt * 1.1f) }
        override fun typeface(family: String, weight: Int, sizePt: Float): Typeface = throw UnsupportedOperationException()
        override fun listen(changed: () -> Unit) { this.changed = changed }
    }

    private val design = FakeDesign()
    private val tokens = DesignTokens(design)

    @Test fun kinds() {
        assertEquals(TokenKind.COLOR, tokens.kind("comp.statusbar.battery-low-color"))
        assertEquals(TokenKind.LENGTH, tokens.kind("comp.statusbar.time-size"))
        assertEquals(TokenKind.NUMBER, tokens.kind("comp.statusbar.fade"))
        assertEquals(TokenKind.FONT, tokens.kind("sys.font.text"))
        assertEquals(TokenKind.STRING, tokens.kind("comp.statusbar.layout"))
        assertEquals(TokenKind.TEXT_STYLE, tokens.kind("comp.statusbar.time"))
        assertNull(tokens.kind("comp.nope"))
    }

    @Test fun valuesAsExpressionsReadThem() {
        assertEquals(Value.Color(0xFFFF3B30.toInt()), tokens.token("comp.statusbar.battery-low-color"))
        assertEquals(Value.Num(15.0, "pt"), tokens.token("comp.statusbar.time-size"))
        assertEquals(Value.Num(250.0, "ms"), tokens.token("motion.statusbar.hold"))
        assertEquals(0.4, (tokens.token("comp.statusbar.fade") as Value.Num).value, 1e-6)
        assertNull((tokens.token("comp.statusbar.fade") as Value.Num).unit)
        assertEquals(Value.Str("inter"), tokens.token("sys.font.text"))
        assertEquals(Value.Null, tokens.token("comp.statusbar.time"))   // a text style is the painter's: textStyle
        assertEquals(Value.Null, tokens.token("comp.nope"))
        assertEquals(16.5f, tokens.textStyle("comp.statusbar.time")!!.sizePt, 1e-4f)   // as Design.text scales it
        assertNull(tokens.textStyle("comp.statusbar.time-size"))
    }

    @Test fun aChangeBumpsTheVersionAndValuesAreReadAgain() {
        val heard = ArrayList<Int>()
        tokens.onChange { heard += tokens.version }
        design.dark = 0.5f
        design.changed()
        assertEquals(1, tokens.version)
        assertEquals(listOf(1), heard)
        assertEquals(Value.Color(0xFFFF4030.toInt()), tokens.token("comp.statusbar.battery-low-color"))   // halfway to dark
        design.values["comp.statusbar.time-size"] = DesignValue.Number(17f, NumUnit.PT)   // a theme change
        design.changed()
        assertEquals(Value.Num(17.0, "pt"), tokens.token("comp.statusbar.time-size"))
        assertEquals(2, tokens.version)
    }

    @Test fun aRemovedListenerHearsNothing() {   // a detached host's (E2 slice 6: the tokens live as long as the process)
        var current = 0; var gone = 0
        val goneListener: () -> Unit = { gone++ }
        tokens.onChange(goneListener)
        tokens.onChange { current += 1 }
        design.changed()
        tokens.removeOnChange(goneListener)
        design.changed()
        assertEquals(2, current)
        assertEquals(1, gone)
    }

    @Test fun theValidatorsTokenCheckAndAnExpression() {
        val kdl = "component \"c\" {\n  view { text x color=\"@comp.statusbar.battery-low-color\" size=\"@comp.statusbar.size\" }\n}"
        val r = Validator(tokens).load(KdlParser.parse(kdl)) as ReadResult.Invalid
        assertEquals(listOf("unknown-token"), r.diagnostics.map { it.code })
        assertTrue(r.diagnostics.single().message.contains("comp.statusbar.size"))
        val env = object : Env {
            override fun state(name: String) = Value.Null
            override fun prop(name: String) = Value.Null
            override fun token(key: String) = tokens.token(key)
            override fun source(path: String) = Value.Null
            override fun now() = 0L
        }
        assertEquals(Value.Num(30.0, "pt"), Compiled.of("{@comp.statusbar.time-size * 2}", emptySet()).value(env))
        assertEquals(Value.Str("#FFFF3B30 low"), Compiled.of("{@comp.statusbar.battery-low-color} low", emptySet()).value(env))
    }
}
