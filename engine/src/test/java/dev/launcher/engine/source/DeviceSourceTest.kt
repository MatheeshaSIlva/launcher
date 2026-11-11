package dev.launcher.engine.source

import dev.launcher.engine.expr.Value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeviceSourceTest {
    private fun state() = DisplayState(1080f, 2400f, 3f, 0, 96f, 1f, 60f, 2f, 3f,
        floatArrayOf(510f, 12f, 570f, 72f), "test model")

    private class Fake(var state: DisplayState) : DisplayInfo {
        val listeners = ArrayList<() -> Unit>()
        var atListen: () -> Unit = { }
        var atRead: () -> Unit = { }
        var removed = 0
        override fun current(): DisplayState { atRead(); return state }
        override fun listen(changed: () -> Unit): () -> Unit {
            listeners += changed
            atListen()
            return { listeners.remove(changed); removed++ }
        }
        fun changed() = listeners.toList().forEach { it() }
    }

    @Test fun allMembersHaveTheirUnitsAndUnknownMembersAreNull() {
        val device = Device(state().copy(rotation = 270))
        val lengths = mapOf("width" to 1080, "height" to 2400, "status-height" to 96,
            "safe-left" to 1, "safe-top" to 60, "safe-right" to 2, "safe-bottom" to 3,
            "cutout-rect-left" to 510, "cutout-rect-top" to 12, "cutout-rect-right" to 570,
            "cutout-rect-bottom" to 72, "cutout-centre-x" to 540, "cutout-centre-y" to 42)
        lengths.forEach { (name, value) -> assertEquals(name, Value.Num(value.toDouble(), "px"), device.member(name)) }
        assertEquals(Value.Num(3.0), device.member("density"))
        assertEquals(Value.Num(270.0), device.member("rotation"))
        assertEquals(Value.Str("test model"), device.member("model"))
        assertSame(Value.Bool.TRUE, device.member("cutout"))
        for (name in listOf("unknown", "fold-state", "dpi", "cutout-left")) assertSame(Value.Null, device.member(name))
    }

    @Test fun noTopCutoutHasNullGeometryAndZeroSafeInsets() {
        val device = Device(state().copy(cutout = null, safeLeft = 0f, safeTop = 0f, safeRight = 0f, safeBottom = 0f))
        assertSame(Value.Bool.FALSE, device.member("cutout"))
        for (name in listOf("left", "top", "right", "bottom")) {
            assertSame(Value.Null, device.member("cutout-rect-$name"))
            assertEquals(Value.Num(0.0, "px"), device.member("safe-$name"))
        }
        for (axis in listOf("x", "y")) assertSame(Value.Null, device.member("cutout-centre-$axis"))
        // Eligibility is placement's concern: a top cutout outside the status band stays readable.
        assertEquals(Value.Num(400.0, "px"), Device(state().copy(cutout = floatArrayOf(0f, 390f, 20f, 410f)))
            .member("cutout-centre-y"))
    }

    @Test fun equalityAndHashUseArrayContents() {
        val a = state()
        val b = state()
        assertNotSame(a.cutout, b.cutout)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(1, setOf(a, b).size)
        assertNotEquals(a, b.copy(cutout = null))
        assertNotEquals(a, b.copy(cutout = floatArrayOf(0f, 0f, 1f, 1f)))
        assertEquals(a.copy(cutout = null), b.copy(cutout = null))
    }

    @Test fun coldInitialUpdatesNoRepeatsAndCancelCleanup() = runBlocking {
        val info = Fake(state())
        val source = DeviceSource.of(info)
        assertEquals("device", source.name)
        assertEquals("none", source.capability)
        assertEquals(Device(state()), source.current())
        assertTrue(info.listeners.isEmpty())
        val seen = ArrayList<Device>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { source.values.collect { seen += it } }
        assertEquals(listOf(Device(state())), seen)
        info.state = state() // same geometry, separate array
        info.changed()
        assertEquals(1, seen.size)
        info.state.cutout!![0] = 500f // adapter may reuse a mutable buffer
        info.changed()
        assertEquals(2, seen.size)
        assertEquals(510f, seen.first().state.cutout!![0])
        info.state = info.state.copy(width = 2400f, height = 1080f, rotation = 90, cutout = null)
        info.changed()
        assertEquals(3, seen.size)
        assertEquals(Device(info.state), seen.last())
        job.cancelAndJoin()
        assertTrue(info.listeners.isEmpty())
        assertEquals(1, info.removed)
        info.changed()
        assertEquals(3, seen.size)
    }

    @Test fun readRegisterRaceAndSynchronousRegistrationCallback() = runBlocking {
        for (callback in listOf(false, true)) {
            val info = Fake(state())
            info.atListen = {
                info.state = state().copy(rotation = 180)
                if (callback) info.changed()
            }
            val seen = ArrayList<Device>()
            val source = DeviceSource.of(info)
            val job = CoroutineScope(Dispatchers.Unconfined).launch { source.values.collect { seen += it } }
            assertEquals(180, seen.last().state.rotation)
            assertTrue(seen.size <= 2)
            job.cancelAndJoin()
            assertEquals(1, info.removed)
        }
    }

    @Test fun cancellationDuringRegistrationStillUnregisters() = runBlocking {
        val info = Fake(state())
        val source = DeviceSource.of(info)
        val job = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { source.values.collect { } }
        info.atListen = { job.cancel() }
        job.start()
        job.join()
        assertEquals(1, info.removed)
        assertTrue(info.listeners.isEmpty())
    }

    @Test fun slowCollectorReceivesLatestGeometry() = runBlocking {
        val info = Fake(state())
        val release = CompletableDeferred<Unit>()
        val seen = ArrayList<Device>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            DeviceSource.of(info).values.collect { seen += it; release.await() }
        }
        for (rotation in listOf(90, 180, 270)) {
            info.state = info.state.copy(rotation = rotation)
            info.changed()
        }
        release.complete(Unit)
        assertEquals(listOf(0, 270), seen.map { it.state.rotation })
        job.cancelAndJoin()
        assertEquals(1, info.removed)
    }

    @Test fun readFailureAfterRegistrationStillUnregisters() = runBlocking {
        val info = Fake(state())
        info.atListen = { info.atRead = { error("read failed") } }
        var failure: Throwable? = null
        try { DeviceSource.of(info).values.collect { } } catch (e: IllegalStateException) { failure = e }
        assertEquals("read failed", failure?.message)
        assertEquals(1, info.removed)
        assertTrue(info.listeners.isEmpty())
    }
}
