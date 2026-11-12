package dev.launcher.engine.source

import android.content.Intent
import android.os.BatteryManager
import android.os.PowerManager
import dev.launcher.engine.expr.Deps
import dev.launcher.engine.expr.Env
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlParser
import dev.launcher.engine.layout.Units
import dev.launcher.engine.model.ComponentReader
import dev.launcher.engine.model.ReadResult
import dev.launcher.engine.ui.ComponentHost
import dev.launcher.engine.ui.HostNode
import dev.launcher.engine.ui.Painter
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** E2 slice 7: sources over fake broadcasts, the registry, and the binder's one refresh per frame. */
class SourcesTest {
    private data class Plain(val n: Int) : SourceValue { override fun member(name: String): Value = Value.Num(n.toDouble()) }

    /** T-007: a source needing a capability, counting every read and every subscription. */
    private class Guarded {
        var reads = 0
        var subscribed = 0
        val source = Source("secret", "notifications.read", { reads++; Plain(1) },
            kotlinx.coroutines.flow.flow { subscribed++; emit(Plain(2)) })
    }
    private fun binderOver(g: Guarded, granted: Set<String>, denied: (String, String) -> Unit = { _, _ -> }) =
        SourceBinder(Sources(listOf(g.source)), { }, Dispatchers.Unconfined, granted, denied)

    @Test fun aSourceNotGrantedIsNeitherReadNorSubscribedTo() {
        val g = Guarded()
        val refused = ArrayList<String>()
        val binder = binderOver(g, emptySet()) { s, c -> refused += "$s:$c" }
        binder.start(listOf("secret")) { }
        assertEquals(0, g.reads)
        assertEquals(0, g.subscribed)
        assertEquals(listOf("secret:notifications.read"), refused)
        binder.stop()
    }
    @Test fun aSourceGrantedAndDeclaredIsRead() {
        val g = Guarded()
        val binder = binderOver(g, setOf("notifications.read"))
        binder.start(listOf("secret")) { }
        assertEquals(1, g.reads)
        assertEquals(1, g.subscribed)
        binder.stop()
    }
    @Test fun aGrantWithoutADeclarationReadsNothing() {
        val g = Guarded()
        val binder = binderOver(g, setOf("notifications.read"))
        binder.start(emptyList()) { }
        assertEquals(0, g.reads)
        assertEquals(0, g.subscribed)
        binder.stop()
    }
    @Test fun stopLeavesNothingItRead() {
        val g = Guarded()
        val binder = binderOver(g, setOf("notifications.read"))
        val base = object : Env {
            override fun prop(name: String): Value = Value.Null
            override fun source(path: String): Value = Value.Null
            override fun state(name: String): Value = Value.Null
            override fun token(key: String): Value = Value.Null
            override fun now() = 0L
            override val zone: TimeZone = TimeZone.getTimeZone("UTC")
            override val locale: Locale = Locale.ROOT
        }
        val env = binder.env(base)
        binder.start(listOf("secret")) { }
        assertEquals(Value.Num(2.0), env.source("secret.x"))
        binder.stop()
        assertEquals(Value.Null, env.source("secret.x"))
    }

    /** Broadcasts by hand: [send] delivers to every registration of its action. */
    private class FakeReceivers : Receivers {
        val registered = ArrayList<Pair<List<String>, (Broadcast) -> Unit>>()
        val stickies = HashMap<String, Map<String, Int>>()
        override fun register(actions: List<String>, deliver: (Broadcast) -> Unit): () -> Unit {
            val r = actions to deliver
            registered += r
            actions.forEach { a -> stickies[a]?.let { deliver(broadcast(a, it)) } }
            return { registered.remove(r) }
        }
        override fun sticky(action: String) = stickies[action]?.let { broadcast(action, it) }
        fun send(action: String, ints: Map<String, Int> = emptyMap()) =
            registered.toList().filter { action in it.first }.forEach { it.second(broadcast(action, ints)) }
        private fun broadcast(action: String, ints: Map<String, Int>) = Broadcast(action) { k, d -> ints[k] ?: d }
    }

    /** Frames by hand: [frame] runs what waits for the next one. */
    private class FakeClock : FrameClock {
        val waiting = ArrayList<() -> Unit>()
        override fun next(frame: () -> Unit) { waiting += frame }
        fun frame() = waiting.toList().also { waiting.clear() }.forEach { it() }
    }

    private fun battery(level: Int, status: Int = BatteryManager.BATTERY_STATUS_DISCHARGING, scale: Int = 100) =
        mapOf(BatteryManager.EXTRA_LEVEL to level, BatteryManager.EXTRA_SCALE to scale, BatteryManager.EXTRA_STATUS to status)

    private val receivers = FakeReceivers().apply { stickies[Intent.ACTION_BATTERY_CHANGED] = battery(40, BatteryManager.BATTERY_STATUS_CHARGING) }
    private var powerSave = false
    private var clockMs = 60_000L
    private val sources = Sources(listOf(SystemSources.time(receivers, { clockMs }, { "UTC" }), SystemSources.battery(receivers) { powerSave }))
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test fun batteryReadsAsStatusBarViewDoes() {
        val b = sources["battery"]!!
        assertEquals(Battery(40, true, false), b.current())   // the sticky broadcast, without registering
        assertEquals(0, receivers.registered.size)
        val seen = ArrayList<Any>()
        val job = scope.launch { b.values.collect { seen += it } }
        assertEquals(1, receivers.registered.size)   // cold: collecting registers
        receivers.send(Intent.ACTION_BATTERY_CHANGED, battery(41))
        receivers.send(Intent.ACTION_BATTERY_CHANGED, battery(41))   // the same again: no delivery
        receivers.send(Intent.ACTION_BATTERY_CHANGED, battery(-1, BatteryManager.BATTERY_STATUS_FULL))   // no level: kept
        receivers.send(Intent.ACTION_BATTERY_CHANGED, battery(50, scale = 200))
        powerSave = true
        receivers.send(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        // Full on the charger again is a plug-in (T-014): `plugged` while it lasts (this source has no timer: it stays).
        assertEquals(listOf(Battery(40, true, false), Battery(41, false, false), Battery(41, true, false, plugged = true),
            Battery(25, false, false), Battery(25, false, true)), seen)
        job.cancel()
        assertEquals(0, receivers.registered.size)   // and cancelling unregisters
        assertEquals(Value.Num(25.0), Battery(25, false, true).member("level"))
        assertSame(Value.Bool.TRUE, Battery(25, false, true).member("power-save"))
    }

    /** T-014: `plugged` is the plug-in moment: on when charging starts, off after PLUGGED_MS or when it stops. */
    @Test fun pluggedIsThePlugInMoment() {
        val timers = ArrayList<Pair<Long, () -> Unit>>()
        var cancelled = 0
        val r = FakeReceivers().apply { stickies[Intent.ACTION_BATTERY_CHANGED] = battery(40) }
        val b = SystemSources.battery(r, { ms, f -> timers += ms to f; { cancelled++ } }) { false }
        val seen = ArrayList<Battery>()
        val job = scope.launch { b.values.collect { seen += it } }
        r.send(Intent.ACTION_BATTERY_CHANGED, battery(40, BatteryManager.BATTERY_STATUS_CHARGING))
        assertEquals(Battery(40, true, false, plugged = true), seen.last())
        assertEquals(SystemSources.PLUGGED_MS, timers.single().first)
        r.send(Intent.ACTION_BATTERY_CHANGED, battery(41, BatteryManager.BATTERY_STATUS_CHARGING))
        assertEquals("still the moment", Battery(41, true, false, plugged = true), seen.last())
        timers.single().second()
        assertEquals(Battery(41, true, false, plugged = false), seen.last())
        // Out and in again: off at once, then a new moment.
        r.send(Intent.ACTION_BATTERY_CHANGED, battery(41))
        assertEquals(Battery(41, false, false), seen.last())
        r.send(Intent.ACTION_BATTERY_CHANGED, battery(41, BatteryManager.BATTERY_STATUS_CHARGING))
        assertTrue(seen.last().plugged)
        r.send(Intent.ACTION_BATTERY_CHANGED, battery(41))
        assertFalse("unplugged inside the moment ends it", seen.last().plugged)
        job.cancel()
        assertEquals(Value.Bool.TRUE, Battery(1, true, false, true).member("plugged"))
    }

    @Test fun timeTicksWithTheMinute() {
        val seen = ArrayList<Any>()
        val job = scope.launch { sources["time"]!!.values.collect { seen += it } }
        receivers.send(Intent.ACTION_TIME_TICK)
        receivers.send(Intent.ACTION_TIME_TICK)   // the same minute twice: once
        clockMs = 120_000L
        receivers.send(Intent.ACTION_TIMEZONE_CHANGED)
        assertEquals(listOf(Time(60_000L, "UTC"), Time(120_000L, "UTC")), seen)
        job.cancel()
        assertEquals(Value.Num(120_000.0), Time(120_000L, "UTC").member("now"))
    }

    @Test fun threeChangesInOneFrameGiveOneRefresh() {
        val clock = FakeClock()
        val binder = SourceBinder(sources, clock, Dispatchers.Unconfined)
        val refreshes = ArrayList<(Deps) -> Boolean>()
        binder.start(listOf("battery", "time")) { refreshes += it }
        receivers.send(Intent.ACTION_BATTERY_CHANGED, battery(41))
        receivers.send(Intent.ACTION_BATTERY_CHANGED, battery(42))
        receivers.send(Intent.ACTION_BATTERY_CHANGED, battery(43))
        assertEquals(1, clock.waiting.size)
        assertEquals(0, refreshes.size)
        clock.frame()
        assertEquals(1, refreshes.size)
        val reads = refreshes.single()
        assertTrue(reads(Deps(emptyMap(), emptyMap(), emptyMap(), mapOf("battery.level" to 1), false)))
        assertTrue(!reads(Deps(emptyMap(), emptyMap(), emptyMap(), mapOf("network.wifi.level" to 1), true)))
        assertEquals(Value.Num(43.0), binder.env(base).source("battery.level"))
        binder.stop()
        receivers.send(Intent.ACTION_BATTERY_CHANGED, battery(44))
        assertEquals(0, clock.waiting.size)
    }

    private val base = object : Env {
        override fun state(name: String) = Value.Null
        override fun prop(name: String) = Value.Null
        override fun token(key: String) = Value.Null
        override fun source(path: String) = Value.Null
        override fun now() = 0L
        override val locale: Locale = Locale.US
    }

    @Test fun theBinderRefreshesOnlyTheBindingsThatReadTheChangedSource() {
        val kdl = """
            component "t" {
              uses { source battery; source time }
              view { row { text "{battery.level}%"; text "{now | date:'HH:mm'}"; text "{1 + 1}" } }
            }"""
        val def = (ComponentReader.read(KdlParser.parse(kdl.trimIndent())) as ReadResult.Ok).component
        val clock = FakeClock()
        val binder = SourceBinder(sources, clock, Dispatchers.Unconfined)
        val env = binder.env(base)
        val painter = object : Painter {
            override fun key(node: HostNode) = null
            override fun paint(canvas: android.graphics.Canvas, node: HostNode, width: Int, height: Int) {}
        }
        lateinit var host: ComponentHost
        binder.start(def.uses.sources.map { it.name }) { host.refresh(it) }   // first: the values the first frame reads
        host = ComponentHost(def, env, Units(1f, 1f), { painter })
        val (level, time, constant) = host.root.children.map { it.bindings.getValue("content").state }
        assertEquals(Value.Str("40%"), level.value)
        assertEquals(Value.Str("00:01"), time.value)
        val timeBefore = time.value
        receivers.send(Intent.ACTION_BATTERY_CHANGED, battery(41))
        clock.frame()
        assertEquals(Value.Str("41%"), level.value)
        assertSame(timeBefore, time.value)
        clockMs = 180_000L
        receivers.send(Intent.ACTION_TIME_TICK)
        clock.frame()
        assertEquals(Value.Str("00:03"), time.value)
        assertEquals(Value.Num(2.0), constant.value)
        assertEquals(TimeZone.getTimeZone("UTC"), env.zone)
        binder.stop()
    }

    @Test fun fixturesHoldOneValueEach() {
        val fixed = Sources.fixture(mapOf("battery" to Battery(17, false, true)))
        assertEquals(Battery(17, false, true), fixed["battery"]!!.current())
        val binder = SourceBinder(fixed, FakeClock(), Dispatchers.Unconfined)
        binder.start(listOf("battery", "missing")) {}
        assertEquals(Value.Num(17.0), binder.env(base).source("battery.level"))
        assertEquals(setOf("battery"), fixed.names)
        scope.cancel()
    }
}
