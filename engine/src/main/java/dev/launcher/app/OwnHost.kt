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
import dev.launcher.engine.shell.StartAttempt
import dev.launcher.engine.shell.Starter
import java.io.File
import java.net.InetAddress
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
    private val attempt = StartAttempt({ SystemClock.elapsedRealtime() })
    private lateinit var app: android.app.Application
    private lateinit var sink: ShellHost.Sink
    private var generation = 0
    /** The start in flight: its attempt's generation and connection (main thread). */
    private var starting: Long = 0
    @Volatile private var client: AdbClient? = null
    @Volatile private var bound = false

    override fun init(app: android.app.Application, sink: ShellHost.Sink, generation: Int) {
        this.app = app
        this.sink = sink
        this.generation = generation
        current = this
    }

    override fun state(): ShizukuLink.State = if (starting != 0L) ShizukuLink.State.CONNECTING else ShizukuLink.State.NOT_RUNNING

    override fun connect() {
        if (bound || starting != 0L) return
        val endpoint = endpoint() ?: return AppLog.log("[shell] own host: no connect endpoint (pair first)")
        val ticket = attempt.begin()
        starting = ticket.generation
        sink.changed()
        main.postDelayed({ if (starting == ticket.generation) end(ticket.generation, "start timed out") }, StartAttempt.TIMEOUT_MS)
        val apk = app.applicationInfo.sourceDir
        io.execute {
            val identity = AndroidAdbIdentity.store(app).load()
            val key = (identity as? AdbIdentityResult.Ready)?.key
            if (key == null) { main.post { end(ticket.generation, "identity: ${identity.javaClass.simpleName}") }; return@execute }
            val c = AdbClient(endpoint, key)
            client = c
            try {
                c.connect()
                c.shell("trap '' HUP; exec app_process -Djava.class.path=${quoted(apk)} /system/bin --nice-name=launcher-shell ${Starter::class.java.name}",
                    (ticket.capability + "\n").toByteArray(Charsets.US_ASCII))
            } catch (t: Throwable) {
                // Closed by us once the handover is accepted; anything else ends the attempt.
                if (!bound) main.post { end(ticket.generation, "stream: ${t.javaClass.simpleName}") }
            } finally {
                c.close()
                if (client === c) client = null
            }
        }
    }

    override fun bindQuietly() = connect()

    /** [ShellBinderProvider]'s call, on a binder thread: the reply holds our token, or null for any refusal. */
    fun handover(callingUid: Int, method: String, extras: Bundle?): Bundle? {
        val capability = try { extras?.getString("capability") } catch (_: Throwable) { null }
        val ticket = attempt.claim(callingUid, method, capability) ?: return null.also { AppLog.log("[shell] handover refused") }
        val binder: IBinder = try { extras?.getBinder("binder") } catch (_: Throwable) { null }
            ?: return null.also { main.post { end(ticket.generation, "handover without a binder") } }
        val service = IShellService.Stub.asInterface(binder)
        try { binder.linkToDeath({ main.post { bound = false; sink.lost(generation) } }, 0) }
        catch (_: Throwable) { main.post { end(ticket.generation, "the service died at once") }; return null }
        bound = true
        main.post {
            if (starting == ticket.generation) starting = 0
            client?.close()
            AppLog.log("[shell] own host: service connected")
            sink.connected(generation, service)
        }
        return Bundle().apply { putBinder("token", ShizukuLink.clientToken) }
    }

    private fun end(generation: Long, why: String) {
        if (starting != generation) return
        starting = 0
        attempt.clear(generation)
        client?.close()
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
                        "start" -> ShizukuLink.connect()
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
                if (!f.isFile || f.length() > 256) return "no pairing file (or over 256 bytes)"
                f.readText(Charsets.US_ASCII)
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
