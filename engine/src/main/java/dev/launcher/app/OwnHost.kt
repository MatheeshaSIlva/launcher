package dev.launcher.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import dev.launcher.engine.adb.AdbClient
import dev.launcher.engine.adb.AdbDiscovery
import dev.launcher.engine.adb.AndroidNsdAdapter
import dev.launcher.engine.adb.AdbEndpoint
import dev.launcher.engine.adb.AdbIdentityResult
import dev.launcher.engine.adb.AdbPairingClient
import dev.launcher.engine.adb.AndroidAdbIdentity
import dev.launcher.engine.shell.HandoverGate
import dev.launcher.engine.shell.StartAttempt
import dev.launcher.engine.shell.Starter
import java.io.File
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Our own shell process as the host (T-001 slice 2b): over the paired wireless-debugging connection (slice 1b's client
 * and identity) it runs [Starter] with `exec app_process`, writes the attempt's capability to the stream's stdin, and
 * takes the binder [ShellBinderProvider] hands it once [StartAttempt.claim] accepts the call. Then it closes the
 * connection itself (adbd keeps the stream open while its child lives; the child survives the close: measured, thread
 * T-001 round 3). Behind a developer switch; Shizuku stays the default until slice 5. The connect endpoint is set by the
 * test route until slice 3 discovers it.
 */
class OwnHost : ShellHost {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "own-shell-host").apply { isDaemon = true } }
    /** Every step's decision, by attempt generation ([HandoverGate]): only a still-current, acknowledged start publishes. */
    private val gate = HandoverGate({ SystemClock.elapsedRealtime() })
    private lateinit var app: android.app.Application
    private lateinit var sink: ShellHost.Sink
    private var generation = 0
    /** Each attempt's own connection, closed only by that attempt's end (a newer start never loses its own). */
    private val clients = ConcurrentHashMap<Long, AdbClient>()
    /** A claimed handover's service, published only when its acknowledgement comes ([ready]). */
    private val claimed = ConcurrentHashMap<Long, IBinder>()

    override fun init(app: android.app.Application, sink: ShellHost.Sink, generation: Int) {
        this.app = app
        this.sink = sink
        this.generation = generation
        current = this
        // Shizuku's path binds by itself when its binder comes; ours starts here once paired (the service dies with the
        // launcher, so each start of the launcher starts it again). Not after a reboot by itself: slice 4.
        main.post { if (paired(app)) connect() }
    }

    override fun state(): ShizukuLink.State =
        if (gate.isStarting || discovery != null) ShizukuLink.State.CONNECTING else ShizukuLink.State.NOT_RUNNING

    override fun connect() = start(null)

    /** Ports an attempt was started with that are not cached yet: written once that attempt is ready (main thread). */
    private val trying = HashMap<Long, Int>()
    private var discovery: AdbDiscovery? = null
    /** Why the last start ended without a service (null: it has not failed since the last try). */
    @Volatile private var lastFailure: String? = null

    /**
     * A start on this phone's adbd (`127.0.0.1`): the cached connect port, or [port] (just discovered, cached once the
     * service is ready). When a cached port does not answer (it changes each time Wireless debugging is turned on), one
     * bounded discovery (20 s) finds the new one and a fresh attempt follows ([fallback]: no second discovery).
     * [test]: a refusal proof's mode for the starter (only from the DUMP-gated route), else null.
     */
    private fun start(test: String?, port: Int? = null, fallback: Boolean = false) {
        if (gate.connectedGeneration != 0L || gate.isStarting) return
        val endpoint = endpoint(port)
        if (endpoint == null) {
            // Paired but no port yet (paired under Shizuku, or its port forgotten): find it, as for a port that changed.
            if (paired(app) && !fallback && test == null) rediscover() else AppLog.log("[shell] own host: no connect port (pair first)")
            return
        }
        lastFailure = null
        val ticket = gate.begin()
        val gen = ticket.generation
        if (port != null) trying[gen] = port
        sink.changed()
        main.postDelayed({
            if (gate.expire(gen)) {
                end(gen, "start timed out")
                if (test == null && !fallback) rediscover()
            }
        }, StartAttempt.TIMEOUT_MS)
        val apk = app.applicationInfo.sourceDir
        io.execute {
            // Forgotten or replaced before this ran: no key is made, no connection opened.
            if (gate.startingGeneration != gen) return@execute
            val identity = AndroidAdbIdentity.store(app).load()
            val key = (identity as? AdbIdentityResult.Ready)?.key
            if (key == null) { main.post { if (gate.expire(gen)) end(gen, "identity: ${identity.javaClass.simpleName}") }; return@execute }
            val c = AdbClient(endpoint, key)
            clients[gen] = c
            // (An end between the check above and this line could not close it.)
            if (gate.startingGeneration != gen) { clients.remove(gen, c); c.close(); return@execute }
            try {
                c.connect()
                c.shell("trap '' HUP; exec app_process -Djava.class.path=${quoted(apk)} /system/bin --nice-name=launcher-shell ${Starter::class.java.name}" + (test?.let { " --test=$it" } ?: ""),
                    (ticket.capability + "\n").toByteArray(Charsets.US_ASCII))
            } catch (t: Throwable) {
                // Closed by us once the start is acknowledged; anything else ends this attempt (and only this one).
                main.post {
                    if (gate.expire(gen)) {
                        end(gen, "stream: ${t.javaClass.simpleName}")
                        if (test == null && !fallback && t !is SecurityException) rediscover()
                    }
                }
            } finally {
                c.close()
                clients.remove(gen, c)
            }
        }
    }

    override fun bindQuietly() = connect()

    /**
     * [ShellBinderProvider]'s call, on a binder thread: the caller and method are checked before the extras are read.
     * `shell-binder` takes the starter's service and answers with our token and an ack; `shell-ready` (the starter has
     * linked the token) publishes it. Null for any refusal.
     */
    fun handover(callingUid: Int, method: String, extras: Bundle?): Bundle? {
        if (method == LEND_METHOD) return lend(callingUid)
        if (callingUid != StartAttempt.SHELL_UID) return refused()
        return when (method) {
            StartAttempt.HANDOVER_METHOD -> claim(callingUid, method, extras)
            HandoverGate.READY_METHOD -> ready(callingUid, method, extras)
            else -> refused()
        }
    }

    private fun claim(uid: Int, method: String, extras: Bundle?): Bundle? {
        val capability = try { extras?.getString("capability") } catch (_: Throwable) { null }
        val binder = try { extras?.getBinder("binder") } catch (_: Throwable) { null } ?: return refused()
        val accepted = gate.claim(uid, method, capability) ?: return refused()
        val gen = accepted.generation
        try { binder.linkToDeath({ main.post { died(gen) } }, 0) }
        catch (_: Throwable) { main.post { if (gate.expire(gen)) end(gen, "the service died at once") }; return null }
        claimed[gen] = binder
        return Bundle().apply { putBinder("token", ShizukuLink.clientToken); putString("ack", accepted.ack) }
    }

    private fun ready(uid: Int, method: String, extras: Bundle?): Bundle? {
        val ack = try { extras?.getString("ack") } catch (_: Throwable) { null }
        val gen = gate.ready(uid, method, ack) ?: return refused()
        val binder = claimed.remove(gen) ?: return refused()
        main.post {
            // Its death may have been handled first (both posted from binder threads), or Forget ran since the ack: publish
            // only what is still live, and a helper accepted but no longer wanted stops here.
            if (!gate.isConnected(gen)) {
                ShellHost.stop(IShellService.Stub.asInterface(binder), "not wanted any more")
                return@post
            }
            clients.remove(gen)?.close()
            AppLog.log("[shell] own host: service connected")
            trying.remove(gen)?.let { cache(it) }
            published = binder
            sink.connected(generation, IShellService.Stub.asInterface(binder))
        }
        return Bundle()
    }

    private fun died(gen: Long) {
        claimed.remove(gen)
        when (gate.lost(gen)) {
            HandoverGate.Ended.CONNECTION -> { AppLog.log("[shell] own host: service died"); sink.lost(generation) }
            HandoverGate.Ended.START -> end(gen, "the service died before it was ready")
            HandoverGate.Ended.NOTHING -> {}
        }
    }

    /** The published service's binder, for [lend] only. */
    @Volatile private var published: IBinder? = null
    /** Until when (elapsed realtime) one [LEND_METHOD] call is answered: armed only by the DUMP-gated route. */
    @Volatile private var lendUntil = 0L

    /**
     * The forwarded-binder proof: once, within 10 s of the route arming it, any caller gets the service's binder, which
     * must then refuse that caller (`ShellService.onlyUid`). Logged with the caller's uid.
     */
    private fun lend(uid: Int): Bundle? = synchronized(this) {
        val b = published
        if (b == null || SystemClock.elapsedRealtime() >= lendUntil) return refused()
        lendUntil = 0
        AppLog.log("[shell] test: service binder lent to uid $uid")
        Bundle().apply { putBinder("binder", b) }
    }

    /**
     * Whether the stream's PTY echoes what we write (Codex's review): a disposable 64-hex line on stdin, a shell that
     * reads it and prints a marker; reported is only whether the output holds the line, and its length (never the output).
     */
    private fun echoCheck(): String {
        val endpoint = endpoint() ?: return "no endpoint"
        val key = (AndroidAdbIdentity.store(app).load() as? AdbIdentityResult.Ready)?.key ?: return "no identity"
        val r = java.security.SecureRandom()
        val probe = buildString { repeat(64) { append("0123456789abcdef"[r.nextInt(16)]) } }
        return try {
            AdbClient(endpoint, key).use { c ->
                c.connect()
                val out = c.shell("head -c 65 >/dev/null; echo echo-check-end", (probe + "\n").toByteArray(Charsets.US_ASCII)).toString(Charsets.US_ASCII)
                "output ${out.length} chars, marker ${out.contains("echo-check-end")}, disposable line echoed ${out.contains(probe)}"
            }
        } catch (t: Throwable) { "failed: ${t.javaClass.simpleName}" }
    }

    private fun refused(): Bundle? = null.also { AppLog.log("[shell] handover refused") }

    /** [gen]'s start is over (the gate already said so): its connection closed, nobody else's. */
    private fun end(gen: Long, why: String) {
        claimed.remove(gen)
        trying.remove(gen)
        clients.remove(gen)?.close()
        lastFailure = why
        AppLog.log("[shell] own host: $why")
        sink.changed()
    }

    /** Always this phone's own adbd, numeric loopback, no network binding; [port] or the cached one. */
    private fun endpoint(port: Int? = null): AdbEndpoint? {
        val p = port ?: app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("connect_port", 0)
        if (p !in 1..65535) return null
        return AdbEndpoint(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), p, null)
    }

    private fun cache(port: Int) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("connect_port", port).apply()

    /** The cached port did not answer: Wireless debugging's connect port found again (at most 20 s), then one fresh start. */
    private fun rediscover() {
        discovery?.close()
        val d = AdbDiscovery(AndroidNsdAdapter(app.getSystemService(android.net.nsd.NsdManager::class.java)))
        discovery = d
        try { d.start(FALLBACK_MS) } catch (t: Throwable) { discovery = null; return AppLog.log("[shell] own host: discovery: ${t.javaClass.simpleName}") }
        lastFailure = null
        sink.changed()
        AppLog.log("[shell] own host: looking for Wireless debugging's port")
        val until = SystemClock.elapsedRealtime() + FALLBACK_MS
        fun look() {
            if (discovery !== d) return
            val found = d.snapshot().firstOrNull { it.kind == AdbDiscovery.Kind.CONNECT }?.endpoint?.port
            when {
                found != null -> { d.close(); discovery = null; start(null, found, fallback = true) }
                SystemClock.elapsedRealtime() >= until -> { d.close(); discovery = null; lastFailure = "no port"; AppLog.log("[shell] own host: Wireless debugging's port not found (is it on?)"); sink.changed() }
                else -> main.postDelayed({ look() }, 250)
            }
        }
        look()
    }

    private fun quoted(s: String) = "'" + s.replace("'", "'\\''") + "'"

    companion object {
        private const val PREFS = "shell_host"
        private const val FALLBACK_MS = 20_000L

        /** The developer switch: which host the next start of the launcher selects. */
        fun select(ctx: Context, own: Boolean) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("host_kind", if (own) "own" else "shizuku").apply()

        /** A connect port is cached: a start has worked since the last Forget (Android may still have dropped the key). */
        fun paired(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("paired", false)

        /** A pairing succeeded: kept, so a start can find the port even if the host was not yet the launcher's own. */
        fun markPaired(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("paired", true).apply()

        /** This process's host is our own (the switch applies at a process's start). */
        val active: Boolean get() = current != null

        /** Why the last start failed, if it did. */
        val failure: String? get() = current?.lastFailure

        /** [f] after whatever the host's worker is doing ("Forget pairing": nothing can make the key again behind it). */
        fun onWorker(f: () -> Unit) { current?.io?.execute(f) ?: f() }

        /** Just paired ([PairingSession]): start on the connect port discovery found; cached once the service is ready. */
        fun startPaired(ctx: Context, port: Int) {
            val h = current ?: return AppLog.log("[shell] own host: not selected (the developer switch picks Shizuku)")
            h.start(null, port, fallback = true)
        }

        /** "Forget pairing": the cached port goes with the identity. */
        fun forgetEndpoint(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("connect_port").remove("host").remove("paired").apply()
            val h = current ?: return
            h.discovery?.close(); h.discovery = null
            // The start in flight ends here: it cannot publish, nor write the port back (a publication already posted
            // finds no port to cache). A service already running stops: pairing again needs Android's shade, which it
            // keeps away.
            h.trying.clear()
            val gen = h.gate.endAll()
            if (gen != 0L) h.end(gen, "forgotten")
            h.published?.let { b ->
                h.published = null
                ShellHost.stop(IShellService.Stub.asInterface(b), "forgotten")
                h.sink.lost(h.generation)
            }
        }
        const val LEND_METHOD = "test-lend"
        private val TEST_MODES = setOf("hold", "block", "late", "nolink", "double", "replay")
        @Volatile var current: OwnHost? = null; private set

        /** Which host [ShizukuLink] selects at start: our own only when the developer switch says so. */
        fun selected(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("host_kind", "shizuku") == "own"

        /**
         * The developer route (DUMP senders only, as TEST_STATUS):
         * `am broadcast -a dev.launcher.app.TEST_SHELL -p dev.launcher.app --es do pair|use-own|use-shizuku|start|identity`.
         * `pair` reads `dev-in/adb-pair.txt` in the app's external folder (`host=`, `pair_port=`, `code=`, `connect_port=`,
         * at most 256 bytes), deletes it whatever happens, pairs with the launcher's own identity and keeps host and port.
         * A host change applies at the next start of the app.
         */
        fun registerTestRoute(app: android.app.Application) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    when (i.getStringExtra("do")) {
                        "pair" -> Thread({ AppLog.log("[shell] pair: ${pair(app)}") }, "own-shell-pair").start()
                        "use-own", "use-shizuku" -> {
                            prefs.edit().putString("host_kind", i.getStringExtra("do")!!.removePrefix("use-")).apply()
                            AppLog.log("[shell] host at next start: ${prefs.getString("host_kind", "?")}")
                        }
                        "start" -> {
                            val test = i.getStringExtra("test")?.takeIf { it in TEST_MODES }
                            AppLog.log("[shell] test start${test?.let { " ($it)" } ?: ""}: ${ShizukuLink.state()}")
                            if (ShizukuLink.service == null) current?.start(test)
                        }
                        "stop" -> Thread({ AppLog.log("[shell] test stop: ${try { ShizukuLink.service?.destroy(); "sent" } catch (t: Throwable) { t.javaClass.simpleName }}") }).start()
                        "lend" -> current?.let { it.lendUntil = SystemClock.elapsedRealtime() + 10_000; AppLog.log("[shell] test: lend armed for 10 s") }
                        "echo-check" -> Thread({ AppLog.log("[shell] echo-check: ${current?.echoCheck()}") }, "own-shell-echo").start()
                        // T-001 slice 3's journey, as the settings page's rows do it.
                        "pair-begin" -> AppLog.log("[shell] pair-begin: ${PairingSession.begin(app)}")
                        "forget" -> PairingSession.forget(app)
                        "identity" -> Thread({ AppLog.log("[shell] identity: ${try { ShizukuLink.service?.identity() } catch (t: Throwable) { t.javaClass.simpleName }}") }).start()
                        else -> AppLog.log("[shell] test: do pair|use-own|use-shizuku|start [test MODE]|stop|lend|echo-check|pair-begin|forget|identity")
                    }
                }
            }
            val f = IntentFilter("dev.launcher.app.TEST_SHELL")
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(r, f, android.Manifest.permission.DUMP, null)
        }

        private fun pair(app: android.app.Application): String {
            val f = File(app.getExternalFilesDir(null), "dev-in/adb-pair.txt")
            val text = try {
                if (!f.isFile) return "no pairing file"
                // Bounded whatever the file does meanwhile: at most 257 bytes are read, and 257 is refused.
                val bytes = f.inputStream().use { input -> ByteArray(257).let { buf -> var n = 0
                    while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                    buf.copyOf(n) } }
                if (bytes.size > 256) return "pairing file over 256 bytes"
                String(bytes, Charsets.US_ASCII)
            } finally { f.delete() }
            val v = text.lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
            val host = v["host"] ?: return "no host"
            val pairPort = v["pair_port"]?.toIntOrNull() ?: return "no pair_port"
            val connectPort = v["connect_port"]?.toIntOrNull() ?: return "no connect_port"
            val code = v["code"] ?: return "no code"
            if (Build.VERSION.SDK_INT < 31) return "pairing needs Android 12"
            val identity = AndroidAdbIdentity.store(app).load() as? AdbIdentityResult.Ready ?: return "identity unavailable"
            return try {
                AdbPairingClient(AdbEndpoint(InetAddress.getByName(host), pairPort, null), identity.key).use { it.pair(code) }
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("host", host).putInt("connect_port", connectPort).apply()
                "paired (identity ${if (identity.created) "new" else "kept"})"
            } catch (t: Throwable) { "failed: ${t.javaClass.simpleName}" }
        }
    }
}
