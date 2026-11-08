package dev.launcher.engine.adb

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.Closeable
import java.net.InetAddress
import java.net.NetworkInterface

/** Bounded, replaceable discovery session. Snapshots are invalid immediately on loss/cancel/deadline.
 * The loopback restriction is not authentication of adbd. Callers still authenticate the handover.
 * Adapter and clock callbacks may arrive on any thread; they must not block waiting for this object.
 */
class AdbDiscovery(
    private val nsd: NsdAdapter,
    private val clock: Clock = AndroidClock(),
    private val localAddresses: () -> List<InetAddress> = {
        NetworkInterface.getNetworkInterfaces()?.toList()?.flatMap { it.inetAddresses.toList() }.orEmpty()
    },
) : Closeable {
    enum class Kind(val serviceType: String) {
        PAIRING("_adb-tls-pairing._tcp."), CONNECT("_adb-tls-connect._tcp.")
    }
    // token belongs to the adapter; identity never includes framework object equality.
    class Service(val name: String, val kind: Kind, val token: Any? = null)
    data class Result(val name: String, val kind: Kind, val endpoint: AdbEndpoint)
    interface NsdAdapter {
        fun browse(kind: Kind, found: (Service) -> Unit, lost: (Service) -> Unit): Closeable
        fun resolve(service: Service, result: (InetAddress?, Int) -> Unit): Closeable
    }
    interface Clock {
        fun nowMs(): Long
        /** Must enqueue, never invoke inline. */
        fun after(delayMs: Long, action: () -> Unit): Closeable
    }
    private data class Key(val name: String, val kind: Kind)
    private class Attempt(val key: Key) {
        var cancel: Closeable? = null
        var timeout: Closeable? = null
    }
    private var generation = 0L
    private var until = 0L
    private var running = false
    private val browsers = mutableListOf<Closeable>()
    private var deadline: Closeable? = null
    private val queue = linkedMapOf<Key, Service>()
    private val results = linkedMapOf<Key, Result>()
    private var active: Attempt? = null

    /** Pairing uses 60 s; cached-start fallback passes 20 s. Replacement invalidates all old results. */
    @Synchronized fun start(timeoutMs: Long = SESSION_MS) {
        require(timeoutMs in 1..SESSION_MS)
        close()
        running = true
        until = clock.nowMs() + timeoutMs
        val epoch = generation
        deadline = clock.after(timeoutMs) { synchronized(this) { if (epoch == generation) close() } }
        try {
            for (kind in Kind.entries) {
                if (!live(epoch)) break
                val browser = nsd.browse(kind, { found(epoch, it) }, { lost(epoch, it) })
                if (live(epoch)) browsers += browser else browser.close()
            }
        } catch (failure: RuntimeException) {
            close()
            throw failure
        }
    }

    @Synchronized fun snapshot(): List<Result> {
        live(generation)
        return results.values.toList()
    }

    private fun live(epoch: Long): Boolean {
        if (running && clock.nowMs() >= until) close()
        return running && epoch == generation
    }

    @Synchronized private fun found(epoch: Long, service: Service) {
        if (!live(epoch) || service.name.length !in 1..255) return
        val key = Key(service.name, service.kind)
        if (key == active?.key || key in queue || key in results) return
        if (queue.size + (if (active == null) 0 else 1) >= MAX_SERVICES) return
        queue[key] = service
        pump(epoch)
    }

    @Synchronized private fun lost(epoch: Long, service: Service) {
        if (!live(epoch)) return
        val key = Key(service.name, service.kind)
        queue.remove(key)
        results.remove(key)
        if (active?.key == key) finish(epoch, checkNotNull(active), null, 0)
    }

    private fun pump(epoch: Long) {
        if (!live(epoch) || active != null || queue.isEmpty()) return
        val (key, service) = queue.entries.first().let { it.key to it.value }
        queue.remove(key)
        val attempt = Attempt(key)
        active = attempt
        attempt.timeout = clock.after(RESOLVE_MS) { finish(epoch, attempt, null, 0) }
        try {
            val cancel = nsd.resolve(service) { host, port -> finish(epoch, attempt, host, port) }
            if (active === attempt && live(epoch)) attempt.cancel = cancel else cancel.close()
        } catch (_: RuntimeException) {
            finish(epoch, attempt, null, 0)
        }
    }

    @Synchronized private fun finish(epoch: Long, attempt: Attempt, host: InetAddress?, port: Int) {
        if (!live(epoch) || active !== attempt) return
        active = null
        attempt.timeout?.close()
        attempt.cancel?.close()
        val own = host != null && port in 1..65_535 && try {
            localAddresses().any { it.address.contentEquals(host.address) }
        } catch (_: Exception) { false }
        if (own) {
            if (results.size == MAX_RESULTS) results.remove(results.keys.first())
            results[attempt.key] = Result(attempt.key.name, attempt.key.kind,
                AdbEndpoint(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port, null))
        }
        pump(epoch)
    }

    @Synchronized override fun close() {
        running = false
        generation++
        deadline?.close()
        deadline = null
        val old = active
        active = null
        queue.clear()
        results.clear()
        old?.timeout?.close()
        old?.cancel?.close()
        val oldBrowsers = browsers.toList()
        browsers.clear()
        oldBrowsers.forEach { it.close() }
    }

    companion object {
        const val RESOLVE_MS = 5_000L
        const val SESSION_MS = 60_000L
        const val START_FALLBACK_MS = 20_000L
        const val MAX_SERVICES = 8
        const val MAX_RESULTS = 8
    }
}

/** Framework-only seam: no permission, receiver, multicast lock or network destination added. */
class AndroidNsdAdapter(private val manager: NsdManager) : AdbDiscovery.NsdAdapter {
    override fun browse(kind: AdbDiscovery.Kind, found: (AdbDiscovery.Service) -> Unit,
                        lost: (AdbDiscovery.Service) -> Unit): Closeable {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) = Unit
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.trimEnd('.') == kind.serviceType.trimEnd('.'))
                    found(AdbDiscovery.Service(info.serviceName, kind, info))
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                lost(AdbDiscovery.Service(info.serviceName, kind, info))
            }
        }
        try { manager.discoverServices(kind.serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (_: RuntimeException) { }
        return Closeable { try { manager.stopServiceDiscovery(listener) } catch (_: RuntimeException) { } }
    }

    @Suppress("DEPRECATION")
    override fun resolve(service: AdbDiscovery.Service, result: (InetAddress?, Int) -> Unit): Closeable {
        val info = service.token as NsdServiceInfo
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) = result(null, 0)
            override fun onServiceResolved(info: NsdServiceInfo) = result(info.host, info.port)
        }
        manager.resolveService(info, listener)
        return Closeable {
            // Older frameworks cannot cancel resolution; policy still rejects every obsolete callback.
            if (Build.VERSION.SDK_INT >= 34) {
                try { manager.stopServiceResolution(listener) } catch (_: RuntimeException) { }
            }
        }
    }
}

class AndroidClock : AdbDiscovery.Clock {
    private val handler = Handler(Looper.getMainLooper())
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
    override fun after(delayMs: Long, action: () -> Unit): Closeable {
        val task = Runnable(action)
        handler.postDelayed(task, delayMs)
        return Closeable { handler.removeCallbacks(task) }
    }
}
