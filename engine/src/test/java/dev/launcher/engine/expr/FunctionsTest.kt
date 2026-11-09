package dev.launcher.engine.expr

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class FunctionsTest {
    @Test fun listCallsAreTypedBoundedAndReadDynamicArguments() {
        val fields = mutableMapOf<String, Value>("id" to Value.Str("a"))
        val record = Value.Record(fields)
        val items = mutableListOf<Value>(record, Value.Record(mapOf("id" to Value.Str("b"))))
        input = Value.List(items)
        fields.clear(); items.clear()
        assertEquals(Value.Str("a"), record.member("id"))
        assertSame(Value.Null, record.member("id.missing"))
        assertSame(Value.Null, record.member("x.".repeat(65)))
        assertEquals(Value.Num(2.0), eval("length(@c)"))
        assertEquals(Value.List(listOf(record)), eval("take(@c, 1)"))
        assertEquals(input, eval("take(@c, 999999)"))
        assertEquals(Value.Num(0.0), eval("length(take(@c, 0))"))
        for (count in listOf("-1", "1.5", "1pt", "1 / 0")) refused("take(@c, $count)")
        refused("length(1)"); refused("take(@c)")
        for (v in listOf(Value.Null, Value.Str("x"), Value.Num(-1.0), Value.Num(0.5),
            Value.Num(Double.NaN), Value.Num(2.0, "pt"))) {
            input = v
            assertSame(Value.Null, eval("length(@c)"))
            assertSame(Value.Null, eval("take(@c, 1)"))
            assertSame(Value.Null, eval("take(take(@c, 1), @c)"))
        }
        val deps = Compiled.of("{take(notifications.icons, @max-icons)}", setOf("max-icons")).deps
        assertEquals(setOf("notifications.icons"), deps.sources.keys)
        assertEquals(setOf("max-icons"), deps.props.keys)
        val list = Value.List(listOf(record))
        val take = Compiled.of("{take(@list, @count)}", setOf("list", "count"))
        val counted = object : Env by env {
            override fun prop(name: String) = if (name == "list") list else input
        }
        for (bad in listOf(Value.Null, Value.Str("1"), Value.Num(-1.0), Value.Num(1.5),
            Value.Num(Double.POSITIVE_INFINITY), Value.Num(1.0, "pt"))) {
            input = bad
            assertSame(Value.Null, take.value(counted))
        }
        input = Value.Num(1.0)
        assertEquals(list, take.value(counted))
        try { Value.List(List(4097) { Value.Null }); fail("unbounded list") } catch (_: IllegalArgumentException) { }
        try { Value.Record((0..256).associate { "$it" to Value.Null }); fail("unbounded record") } catch (_: IllegalArgumentException) { }
        var nested: Value = Value.Null
        repeat(64) { nested = Value.List(listOf(nested)) }
        try { Value.List(listOf(nested)); fail("unbounded depth") } catch (_: IllegalArgumentException) { }
    }
    private var input: Value = Value.Null
    private val env = object : Env {
        override fun state(name: String) = input
        override fun prop(name: String) = input
        override fun token(key: String) = when (key) {
            "a" -> Value.Color(0x80402010.toInt())
            "b" -> Value.Color(0x20708090)
            else -> input
        }
        override fun source(path: String) = if (path == "battery.power-save") Value.Bool.TRUE else input
        override fun now() = 0L
    }
    private fun eval(s: String) = Compiled.of("{$s}", emptySet()).value(env)
    private fun refused(s: String) {
        try { Compiled.of("{$s}", emptySet()); fail("accepted $s") }
        catch (e: ExprException) { assertEquals("bad-call", e.code) }
    }

    @Test fun callsNestAndRetainTypesAndUnits() {
        assertEquals(Value.Color(0xFF402010.toInt()), eval("opaque(@a)"))
        assertEquals(Value.Num(128.0), eval("alphaOf(@a)"))
        assertEquals(Value.Color(0x11402010), eval("withAlpha(@a, 17)"))
        assertEquals(Value.Color(0xFF402010.toInt()), eval("withAlpha(@a, alphaOf(opaque(@b)))"))
        assertEquals(Value.Num(2.0, "pt"), eval("clamp(3pt, 1pt, 2pt)"))
        assertEquals(Value.Num(1.0), eval("clamp(2, 0, 1)"))
        assertEquals(Value.Str("x128"), Compiled.of("x{alphaOf(@a)}", emptySet()).value(env))
    }

    @Test fun scaleAlphaTruncatesForEveryContentAlpha() {
        for (alpha in 0..255) {
            input = Value.Color((alpha shl 24) or 0x123456)
            for (n in listOf(89, 102)) assertEquals(Value.Color(((alpha * n / 255) shl 24) or 0x123456),
                eval("scaleAlpha(@c, $n, 255)"))
        }
        assertEquals(Value.Color(0xFF123456.toInt()), eval("scaleAlpha(@c, 2147483647, 1)"))
    }

    @Test fun mixMatchesReferenceFloatChannelArithmeticIncludingTranslucentColors() {
        val r = Random(123)
        repeat(1000) {
            val a = r.nextInt(); val b = r.nextInt(); val t = r.nextDouble(-0.5, 1.5)
            var expected = 0
            for (shift in listOf(0, 8, 16, 24)) {
                val x = a ushr shift and 255; val y = b ushr shift and 255
                val channel = if (t <= 0) x else if (t >= 1) y else (x + (y - x) * t.toFloat() + 0.5f).toInt()
                expected = expected or (channel shl shift)
            }
            assertEquals(Value.Color(expected), Functions.apply("mix", listOf(Value.Color(a), Value.Color(b), Value.Num(t))))
        }
        assertEquals(eval("@a"), eval("mix(@a, @b, -1)"))
        assertEquals(eval("@b"), eval("mix(@a, @b, 2)"))
    }

    @Test fun dependenciesWalkEveryArgumentAndKeepHyphenatedSourceMembers() {
        val c = Compiled.of("{mix(@a, opaque(@b), clamp(battery.power-save ? ${'$'}factor : @prop, 0, 1))}", setOf("prop"))
        assertEquals(setOf("a", "b"), c.deps.tokens.keys)
        assertEquals(setOf("factor"), c.deps.states.keys)
        assertEquals(setOf("prop"), c.deps.props.keys)
        assertEquals(setOf("battery.power-save"), c.deps.sources.keys)
        assertSame(Value.Bool.TRUE, eval("battery.power-save"))
    }

    @Test fun staticInvalidCallsAreDiagnostics() {
        for (s in listOf("exec(1)", "mix(@a, @b)", "opaque(1)", "alphaOf('bad')", "mix(@a, @b, 1pt)",
            "withAlpha(@a, 1.5)", "withAlpha(@a, 256)", "withAlpha(@a, -1)", "scaleAlpha(@a, 1, 0)",
            "scaleAlpha(@a, 1.5, 1)", "clamp(1, 2, 0)", "clamp(1pt, 0dp, 2pt)", "clamp(1 / 0, 0, 1)",
            "mix(@a, @b, 0 / 0)", "clamp(1, 0pt, 2pt)", "clamp(@c, 1 + 2, 1)", "mix(@a, @b, !@c)")) refused(s)
    }

    @Test fun invalidDynamicInputsReturnNullWithoutThrowing() {
        for (v in listOf(Value.Null, Value.Str("bad"), Value.Bool.TRUE, Value.Num(Double.NaN),
            Value.Num(Double.POSITIVE_INFINITY), Value.Num(0.5, "pt"))) {
            input = v
            for (call in listOf("mix(@a, @b, @c)", "withAlpha(@a, @c)", "scaleAlpha(@a, @c, 1)", "clamp(@c, 0, 1)"))
                assertSame("$call with $v", Value.Null, eval(call))
        }
        input = Value.Num(0.0)
        assertSame(Value.Null, eval("scaleAlpha(@a, 1, @c)"))
        input = Value.Num(1e100)
        assertSame(Value.Null, eval("scaleAlpha(@a, @c, 1)"))
        input = Value.Num(0.5)
        assertSame(Value.Null, eval("withAlpha(@a, @c)"))
        input = Value.Num(-1.0)
        assertSame(Value.Null, eval("clamp(0, 0, @c)"))
        input = Value.Num(Double.NaN)
        assertSame(Value.Null, eval("clamp(0, @c, 1)"))
        input = Value.Bool.TRUE
        assertSame(Value.Null, eval("opaque(@c)"))
        assertSame(Value.Null, eval("alphaOf(@c)"))
    }
    @Test fun excessiveInputIsRefusedBeforeRecursiveCompilation() {
        for (text in listOf("opaque(".repeat(100) + "@a" + ")".repeat(100),
            "1 + ".repeat(600) + "1", "x".repeat(16385), "mix(" + "1,".repeat(1000) + "1)")) {
            try { Compiled.of("{$text}", emptySet()); fail("unbounded expression accepted") }
            catch (_: ExprException) { /* bounded refusal, no stack overflow */ }
        }
    }

    @Test fun dynamicCompatibleLengthsAndOverflowRefusals() {
        input = Value.Num(4.0, "pt")
        assertEquals(Value.Num(2.0, "pt"), eval("clamp(@c, 1pt, 2pt)"))
        assertEquals(Value.Num(2.0, "pt"), eval("clamp(@c + 1pt, 1pt, 2pt)"))
        input = Value.Num(1.0, "dp")
        assertSame(Value.Null, eval("clamp(@c, 1pt, 2pt)"))
        input = Value.Num(1e18)
        assertSame(Value.Null, eval("scaleAlpha(@a, @c, 1)"))
        input = Value.Num(2147483648.0)
        assertEquals(Value.Color(0xFF402010.toInt()), eval("scaleAlpha(@a, @c, 1)"))
    }

}
