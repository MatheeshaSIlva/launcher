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
    }

    override fun state(): ShizukuLink.State = if (gate.isStarting) ShizukuLink.State.CONNECTING else ShizukuLink.State.NOT_RUNNING

    override fun connect() {
        if (gate.connectedGeneration != 0L || gate.isStarting) return
        val endpoint = endpoint() ?: return AppLog.log("[shell] own host: no connect endpoint (pair first)")
        val ticket = gate.begin()
        val gen = ticket.generation
        sink.changed()
        main.postDelayed({ if (gate.expire(gen)) end(gen, "start timed out") }, StartAttempt.TIMEOUT_MS)
        val apk = app.applicationInfo.sourceDir
        io.execute {
            val identity = AndroidAdbIdentity.store(app).load()
            val key = (identity as? AdbIdentityResult.Ready)?.key
            if (key == null) { main.post { if (gate.expire(gen)) end(gen, "identity: ${identity.javaClass.simpleName}") }; return@execute }
            val c = AdbClient(endpoint, key)
            clients[gen] = c
            try {
                c.connect()
                c.shell("trap '' HUP; exec app_process -Djava.class.path=${quoted(apk)} /system/bin --nice-name=launcher-shell ${Starter::class.java.name}",
                    (ticket.capability + "\n").toByteArray(Charsets.US_ASCII))
            } catch (t: Throwable) {
                // Closed by us once the start is acknowledged; anything else ends this attempt (and only this one).
                main.post { if (gate.expire(gen)) end(gen, "stream: ${t.javaClass.simpleName}") }
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
            // Its death may have been handled first (both posted from binder threads): publish only what is still live.
            if (!gate.isConnected(gen)) return@post
            clients.remove(gen)?.close()
            AppLog.log("[shell] own host: service connected")
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

    private fun refused(): Bundle? = null.also { AppLog.log("[shell] handover refused") }

    /** [gen]'s start is over (the gate already said so): its connection closed, nobody else's. */
    private fun end(gen: Long, why: String) {
        claimed.remove(gen)
        clients.remove(gen)?.close()
        AppLog.log("[shell] own host: $why")
        sink.changed()
    }

    private fun endpoint(): AdbEndpoint? {
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = p.getString("host", null) ?: return null
        val port = p.getInt("connect_port", 0).takeIf { it in 1..65535 } ?: return null
        return try { AdbEndpoint(InetAddress.getByName(host), port, null) } catch (_: Throwable) { null }
    }

    private fun quoted(s: String) = "'" + s.replace("'", "'\\''") + "'"

    companion object {
        private const val PREFS = "shell_host"
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
                        "start" -> { AppLog.log("[shell] test start: ${ShizukuLink.state()}, starting ${current?.gate?.isStarting}, connected ${current?.gate?.connectedGeneration}"); ShizukuLink.connect() }
                        "identity" -> Thread({ AppLog.log("[shell] identity: ${try { ShizukuLink.service?.identity() } catch (t: Throwable) { t.javaClass.simpleName }}") }).start()
                        else -> AppLog.log("[shell] test: do pair|use-own|use-shizuku|start|identity")
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
