package dev.launcher.engine.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.net.InetAddress

class AdbDiscoveryTest {
    private class Time : AdbDiscovery.Clock {
        data class Task(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        var now = 0L
        val tasks = mutableListOf<Task>()
        override fun nowMs() = now
        override fun after(delayMs: Long, action: () -> Unit): Closeable {
            val task = Task(now + delayMs, action)
            tasks += task
            return Closeable { task.cancelled = true }
        }
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val task = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
                now = task.at
                task.cancelled = true
                task.action()
            }
            now = end
        }
    }
    private class Nsd : AdbDiscovery.NsdAdapter {
        data class Browser(val kind: AdbDiscovery.Kind, val found: (AdbDiscovery.Service) -> Unit,
                           val lost: (AdbDiscovery.Service) -> Unit, var closed: Boolean = false)
        data class Resolve(val service: AdbDiscovery.Service, val callback: (InetAddress?, Int) -> Unit,
                           var closed: Boolean = false)
        val browsers = mutableListOf<Browser>()
        val resolves = mutableListOf<Resolve>()
        override fun browse(kind: AdbDiscovery.Kind, found: (AdbDiscovery.Service) -> Unit,
                            lost: (AdbDiscovery.Service) -> Unit): Closeable {
            val browser = Browser(kind, found, lost)
            browsers += browser
            return Closeable { browser.closed = true }
        }
        override fun resolve(service: AdbDiscovery.Service, result: (InetAddress?, Int) -> Unit): Closeable {
            val resolve = Resolve(service, result)
            resolves += resolve
            return Closeable { resolve.closed = true }
        }
        fun found(name: String, kind: AdbDiscovery.Kind = AdbDiscovery.Kind.PAIRING) =
            browsers.last { it.kind == kind }.found(AdbDiscovery.Service(name, kind))
        fun lost(name: String, kind: AdbDiscovery.Kind = AdbDiscovery.Kind.PAIRING) =
            browsers.last { it.kind == kind }.lost(AdbDiscovery.Service(name, kind))
    }
    private val own = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2))
    private val foreign = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 3))
    private val nsd = Nsd()
    private val time = Time()
    private val discovery = AdbDiscovery(nsd, time) { listOf(own) }

    @Test fun acceptsOnlyOwnAddressBytesAndValidPortsAsNumericLoopback() {
        discovery.start()
        val hosts = listOf(null, foreign, own, own, own, own)
        val ports = listOf(1234, 1234, 0, 65_536, 1, 65_535)
        hosts.indices.forEach { i ->
            nsd.found("service$i")
            nsd.resolves.last().callback(hosts[i], ports[i])
        }
        val results = discovery.snapshot()
        assertEquals(listOf(1, 65_535), results.map { it.endpoint.port })
        results.forEach {
            assertEquals("127.0.0.1", it.endpoint.address.hostAddress)
            assertNull(it.endpoint.network)
        }
    }

    @Test fun comparesIpv6AddressBytesWithoutHostnames() {
        val bytes = ByteArray(16).apply { this[15] = 1 }
        val ipv6 = InetAddress.getByAddress("local", bytes)
        val d = AdbDiscovery(nsd, time) { listOf(ipv6) }
        d.start()
        nsd.found("ipv6")
        nsd.resolves.last().callback(InetAddress.getByAddress("different-name", bytes), 3210)
        assertEquals(3210, d.snapshot().single().endpoint.port)
    }

    @Test fun deduplicatesAndBoundsActivePlusQueueAtEightUnderFlood() {
        discovery.start()
        repeat(1000) { nsd.found("s${it % 100}") }
        assertEquals(1, nsd.resolves.size)
        repeat(8) { i ->
            assertEquals("s$i", nsd.resolves.last().service.name)
            assertEquals(1, nsd.resolves.count { !it.closed })
            nsd.resolves.last().callback(own, 1000 + i)
        }
        assertEquals(8, nsd.resolves.size)
        assertEquals(8, discovery.snapshot().size)
        nsd.found("s0")
        assertEquals(8, nsd.resolves.size)
        nsd.found("new")
        nsd.resolves.last().callback(own, 4321)
        assertEquals(8, discovery.snapshot().size)
        assertFalse(discovery.snapshot().any { it.name == "s0" })
    }

    @Test fun findingPairingDoesNotStopConnectBrowser() {
        discovery.start()
        nsd.found("same")
        nsd.found("same", AdbDiscovery.Kind.CONNECT)
        nsd.resolves[0].callback(own, 1000)
        nsd.resolves[1].callback(own, 2000)
        assertEquals(AdbDiscovery.Kind.entries.toSet(), discovery.snapshot().map { it.kind }.toSet())
        assertTrue(nsd.browsers.none { it.closed })
    }

    @Test fun lossRemovesQueuedActiveAndResolvedIdentitiesAndRefusesLateCallbacks() {
        discovery.start()
        nsd.found("active")
        nsd.found("queued")
        nsd.lost("queued")
        val old = nsd.resolves.single()
        nsd.lost("active")
        assertTrue(old.closed)
        old.callback(own, 1111)
        assertTrue(discovery.snapshot().isEmpty())
        assertEquals(1, nsd.resolves.size)
        nsd.found("active")
        old.callback(own, 2222)
        assertTrue(discovery.snapshot().isEmpty())
        nsd.resolves.last().callback(own, 3333)
        nsd.lost("active")
        assertTrue(discovery.snapshot().isEmpty())
    }

    @Test fun fiveSecondTimeoutCancelsAndAdvancesIgnoringLateOrDuplicateResolution() {
        discovery.start()
        nsd.found("a")
        nsd.found("b")
        val a = nsd.resolves.single()
        time.advance(4999)
        assertEquals(1, nsd.resolves.size)
        time.advance(1)
        assertTrue(a.closed)
        assertEquals(2, nsd.resolves.size)
        a.callback(own, 1111)
        nsd.resolves.last().callback(own, 2222)
        nsd.resolves.last().callback(own, 3333)
        assertEquals(2222, discovery.snapshot().single().endpoint.port)
    }

    @Test fun cancellationStopsEverythingAndCallbacksCannotRestartWork() {
        discovery.start()
        nsd.found("a")
        nsd.found("b")
        discovery.close()
        nsd.resolves.single().callback(own, 1111)
        nsd.found("late")
        time.advance(60_000)
        assertEquals(1, nsd.resolves.size)
        assertTrue(discovery.snapshot().isEmpty())
        assertTrue(nsd.browsers.all { it.closed })
        assertTrue(nsd.resolves.all { it.closed })
        assertTrue(time.tasks.all { it.cancelled })
    }

    @Test fun replacementRejectsOldBrowserResolutionAndDeadlineGenerations() {
        discovery.start()
        nsd.found("a")
        val browser = nsd.browsers.first()
        val old = nsd.resolves.single()
        val deadline = time.tasks.first()
        discovery.start()
        nsd.found("a")
        browser.found(AdbDiscovery.Service("stale", AdbDiscovery.Kind.PAIRING))
        browser.lost(AdbDiscovery.Service("a", AdbDiscovery.Kind.PAIRING))
        old.callback(own, 1111)
        deadline.action()
        nsd.resolves.last().callback(own, 2222)
        assertEquals(2, nsd.resolves.size)
        assertEquals(2222, discovery.snapshot().single().endpoint.port)
    }

    @Test fun deadlineClearsResultsAndCancelsActiveAtSixtySeconds() {
        discovery.start()
        nsd.found("a")
        nsd.resolves.single().callback(own, 1111)
        time.advance(59_999)
        nsd.found("b")
        assertEquals(1, discovery.snapshot().size)
        time.advance(1)
        assertTrue(discovery.snapshot().isEmpty())
        assertTrue(nsd.browsers.all { it.closed })
        assertTrue(nsd.resolves.last().closed)
        nsd.resolves.last().callback(own, 2222)
        assertTrue(discovery.snapshot().isEmpty())
    }

    @Test fun snapshotEnforcesFallbackDeadlineEvenBeforeTimerDelivery() {
        discovery.start(AdbDiscovery.START_FALLBACK_MS)
        nsd.found("a")
        nsd.resolves.single().callback(own, 1111)
        time.now = 20_000
        assertTrue(discovery.snapshot().isEmpty())
        nsd.found("late")
        assertEquals(1, nsd.resolves.size)
        assertTrue(nsd.browsers.all { it.closed })
    }

    @Test fun invalidDurationsAndNamesAreRefused() {
        for (duration in listOf(0L, -1L, 60_001L)) {
            assertThrows(IllegalArgumentException::class.java) { discovery.start(duration) }
        }
        discovery.start()
        nsd.found("")
        nsd.found("x".repeat(256))
        assertTrue(nsd.resolves.isEmpty())
    }

    @Test fun interfaceFailureRefusesEndpoint() {
        val d = AdbDiscovery(nsd, time) { throw SecurityException() }
        d.start()
        nsd.found("a")
        nsd.resolves.single().callback(own, 1111)
        assertTrue(d.snapshot().isEmpty())
    }

    @Test fun immediateResolverFailureDoesNotStrandTheQueue() {
        var submit: ((AdbDiscovery.Service) -> Unit)? = null
        val adapter = object : AdbDiscovery.NsdAdapter {
            override fun browse(kind: AdbDiscovery.Kind, found: (AdbDiscovery.Service) -> Unit,
                                lost: (AdbDiscovery.Service) -> Unit): Closeable {
                if (kind == AdbDiscovery.Kind.PAIRING) submit = found
                return Closeable { }
            }
            override fun resolve(service: AdbDiscovery.Service, result: (InetAddress?, Int) -> Unit): Closeable {
                if (service.name == "fail") throw IllegalArgumentException()
                result(own, 1234)
                return Closeable { }
            }
        }
        val d = AdbDiscovery(adapter, time) { listOf(own) }
        d.start()
        submit!!(AdbDiscovery.Service("fail", AdbDiscovery.Kind.PAIRING))
        submit!!(AdbDiscovery.Service("ok", AdbDiscovery.Kind.PAIRING))
        assertEquals("ok", d.snapshot().single().name)
        assertEquals(1, time.tasks.count { !it.cancelled }) // session deadline only
    }

    @Test fun browserExceptionClosesPartiallyStartedSession() {
        var closed = false
        val adapter = object : AdbDiscovery.NsdAdapter {
            override fun browse(kind: AdbDiscovery.Kind, found: (AdbDiscovery.Service) -> Unit,
                                lost: (AdbDiscovery.Service) -> Unit): Closeable {
                if (kind == AdbDiscovery.Kind.CONNECT) throw SecurityException()
                return Closeable { closed = true }
            }
            override fun resolve(service: AdbDiscovery.Service, result: (InetAddress?, Int) -> Unit): Closeable =
                error("No resolution expected")
        }
        val d = AdbDiscovery(adapter, time) { listOf(own) }
        assertThrows(SecurityException::class.java) { d.start() }
        assertTrue(closed)
        assertTrue(time.tasks.all { it.cancelled })
        assertTrue(d.snapshot().isEmpty())
    }
}
