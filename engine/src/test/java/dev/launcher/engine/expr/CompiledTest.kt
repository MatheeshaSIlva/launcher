package dev.launcher.engine.expr

import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** E2 slice 4b: dependency sets; compiled expressions evaluated against a fake environment; formatters; what allocates. */
class CompiledTest {
    private class FakeEnv(
        val states: Map<String, Value> = emptyMap(),
        val sources: Map<String, Value> = emptyMap(),
        override val locale: Locale = Locale.US,
    ) : Env {
        val reads = ArrayList<String>()
        var recording = true
        private inline fun read(what: () -> String) { if (recording) reads += what() }
        override fun state(name: String) = (states[name] ?: Value.Null).also { read { "state $name" } }
        override fun prop(name: String) = (when (name) { "warn" -> WARN; "inset" -> INSET; else -> Value.Null }).also { read { "prop $name" } }
        override fun token(key: String) = Value.Str("token:$key").also { read { "token $key" } }
        override fun source(path: String) = (sources[path] ?: Value.Null).also { read { path } }
        override fun now() = 1_700_000_000_000L   // 2023-11-14 22:13:20 UTC, a Tuesday
        override val zone: TimeZone = TimeZone.getTimeZone("UTC")
    }

    private val env = FakeEnv(mapOf("level" to Value.Num(15.0), "on" to Value.Bool.TRUE, "name" to Value.Str("Wi-Fi")),
        mapOf("battery.level" to Value.Num(87.0), "battery.charging" to Value.Bool.FALSE))
    private fun eval(text: String) = Compiled.of(text, setOf("warn")).value(env)

    @Test fun arithmeticComparisonsAndLogic() {
        assertEquals(Value.Num(7.0), eval("{1 + 2 * 3}"))
        assertEquals(Value.Num(21.75, "pt"), eval("{battery.level * 0.25pt}"))
        assertEquals(Value.Num(1.0), eval("{battery.level % 2}"))
        assertEquals(Value.Num(-15.0), eval("{-\$level}"))
        assertSame(Value.Bool.TRUE, eval("{\$level <= @warn && !battery.charging}"))
        assertSame(Value.Bool.FALSE, eval("{\$level > @warn || battery.charging}"))
        assertSame(Value.Bool.TRUE, eval("{\$name == 'Wi-Fi'}"))
        assertSame(Value.Bool.TRUE, eval("{battery.level != 86}"))
        assertSame(Value.Bool.TRUE, eval("{\$missing == #null}"))
        assertEquals(Value.Str("token:comp.signal"), eval("{\$level <= @warn ? @comp.signal : @ink}"))
        assertEquals(Value.Num(87.0), eval("{\$on ? battery.level : 0}"))
        // A prop's (or a token's) unit is the value's: arithmetic over it keeps it (slice 8).
        assertEquals(Value.Num(8.0, "dp"), Compiled.of("{@inset * 2}", setOf("inset")).value(env))
        assertEquals(Value.Num(-4.0, "dp"), Compiled.of("{-@inset}", setOf("inset")).value(env))
        assertEquals(Value.Num(6.0, "pt"), Compiled.of("{@inset + 2pt}", setOf("inset")).value(env))   // a literal's wins
    }

    private companion object {
        val WARN = Value.Num(20.0)
        val INSET = Value.Num(4.0, "dp")
    }

    @Test fun textAndWholeValues() {
        assertEquals(Value.Str("87%"), eval("{battery.level}%"))
        assertEquals(Value.Str("Wi-Fi at 87, 0.5 and "), eval("{\$name} at {battery.level}, {1 / 2} and {#null}"))
        assertEquals(Value.Str("plain"), eval("plain"))
        assertEquals(0.87, Compiled.of("{battery.level / 100}", emptySet()).number(env), 1e-12)
        assertTrue(Compiled.of("{battery.level > 50}", emptySet()).bool(env))
    }

    @Test fun mixedUnitsAreRefusedWhereBothAreKnown() {
        fun refused(text: String) = try { Compiled.of(text, emptySet()); null } catch (e: ExprException) { e }!!
        assertEquals(listOf("Mixed units: pt + dp", "5", "unit-mismatch", "write both in one unit"),
            refused("{1pt + 1dp}").let { listOf(it.message, it.at.toString(), it.code, it.hint) })
        assertEquals("Mixed units: pt < px", refused("{2pt < 3px}").message)
        assertEquals("Mixed units: pt - dp", refused("{battery.level * 1pt - 2dp}").message)
        assertEquals(Value.Num(3.0, "pt"), Compiled.of("{1pt + 2}", emptySet()).value(env))   // a plain number fits any unit
        assertEquals(Value.Num(88.0), Compiled.of("{battery.level + 1}", emptySet()).value(env))
        assertSame(Value.Bool.TRUE, Compiled.of("{battery.level > 2dp}", emptySet()).value(env))   // a source's unit is unknown
    }

    @Test fun formatters() {
        assertEquals(Value.Str("22:13"), eval("{now | date:'HH:mm'}"))
        assertEquals(Value.Str("Tue 14 Nov"), eval("{now | date:'EEE d MMM'}"))
        assertEquals(Value.Str("87%"), eval("{battery.level / 100 | percent}"))
        assertEquals(Value.Str("015"), eval("{\$level | pad:3}"))
        assertEquals(Value.Str("1,234.5"), eval("{1234.5 | number:1}"))
        assertEquals(Value.Str("1.234,50"), Compiled.of("{1234.5 | number:2}", emptySet()).value(FakeEnv(locale = Locale.GERMANY)))
    }

    @Test fun dependencySetsWithTheirOffsets() {
        val t = ExprParser.template("{\$level > @warn ? @comp.signal : @ink} {battery.level | percent} {now | date:'H:mm'} {battery.level}")
        val d = Deps.of(t, props = setOf("warn"))
        assertEquals(mapOf("level" to 1), d.states)
        assertEquals(mapOf("warn" to 10), d.props)
        assertEquals(mapOf("comp.signal" to 18, "ink" to 33), d.tokens)
        assertEquals(mapOf("battery.level" to 40), d.sources)
        assertEquals(true, d.clock)
        assertEquals(false, Deps.of(ExprParser.template("{1 + 2}"), emptySet()).clock)
    }

    @Test fun dependencySetsTellWhichBindingsAChangeReaches() {
        val c = Compiled.of("{\$level <= @warn ? @comp.signal : @ink} {battery.level | percent} {now | date:'H'}", setOf("warn"))
        assertEquals(setOf("level"), c.deps.states.keys)
        assertEquals(setOf("warn"), c.deps.props.keys)
        assertEquals(setOf("comp.signal", "ink"), c.deps.tokens.keys)
        assertEquals(setOf("battery.level"), c.deps.sources.keys)
        assertTrue(c.deps.clock)
        env.reads.clear()
        c.value(env)   // reads only what the set names (and of the branches, the one taken)
        assertEquals(listOf("state level", "prop warn", "token comp.signal", "battery.level"), env.reads)
    }

    @Test fun numbersAndBooleansDoNotAllocatePerEvaluation() {
        val n = Compiled.of("{(battery.level * 0.27 + \$level) / 2 - 1}", emptySet())
        val b = Compiled.of("{battery.level > 50 && \$level < 20 || !\$on}", emptySet())
        env.recording = false
        n.number(env); b.bool(env)   // warm
        // The JVM's own allocation counter (java.lang.management is not in android.jar, so by reflection).
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val allocated = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.java)
        val thread = Thread.currentThread().id
        val before = allocated.invoke(bean, thread) as Long
        var sum = 0.0
        repeat(100_000) { sum += n.number(env); if (b.bool(env)) sum += 1 }
        val perEvaluation = ((allocated.invoke(bean, thread) as Long) - before) / 100_000.0
        assertEquals(1_924_500.0, sum, 1.0)
        assertTrue("allocated $perEvaluation bytes per evaluation", perEvaluation < 1.0)
    }
}
