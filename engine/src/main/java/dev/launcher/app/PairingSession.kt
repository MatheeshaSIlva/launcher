package dev.launcher.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.os.Handler
import android.os.Looper
import dev.launcher.engine.adb.AdbDiscovery
import dev.launcher.engine.adb.AdbEndpoint
import dev.launcher.engine.adb.AdbIdentityResult
import dev.launcher.engine.adb.AdbPairingClient
import dev.launcher.engine.adb.AdbPairingRejectedException
import dev.launcher.engine.adb.AndroidAdbIdentity
import dev.launcher.engine.adb.AndroidNsdAdapter
import dev.launcher.engine.shell.PairingFlow
import java.util.concurrent.Executors

/**
 * Pairing the launcher with Wireless debugging on the phone (T-001 slice 3, thread round 4). The user opens Settings'
 * "Pair device with pairing code"; a notification of ours asks for the code; its reply field (Android's own shade, the
 * one there is before our shell runs) brings the code to [PairingReplyReceiver], then to [reply] here. Discovery finds
 * the pairing and connect ports on this phone only ([AdbDiscovery]); [PairingFlow] decides which reply counts. The reply
 * PendingIntent is the session's own and is cancelled with it; the code is never logged, kept or echoed.
 *
 * Main thread, except the pairing itself (one worker).
 */
object PairingSession {
    private const val CHANNEL = "shell_host"
    private const val NOTIFICATION = 0x5e11
    const val KEY_CODE = "code"
    const val ACTION_REPLY = "dev.launcher.app.PAIRING_REPLY"
    /** How long one reply or start waits for a port: the fallback's bound (thread T-001 round 4, §3). */
    private const val FIND_MS = 20_000L

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "own-shell-pairing").apply { isDaemon = true } }
    private val flow = PairingFlow({ android.os.SystemClock.elapsedRealtime() })
    private var discovery: AdbDiscovery? = null
    private var replyIntent: PendingIntent? = null
    private var expiry: Runnable? = null
    /** "Forget pairing" is deleting the key: no pairing begins until it has. */
    private var forgetting = false

    enum class Begin { STARTED, NOTIFICATIONS_OFF, SHIZUKU_RUNNING, UNSUPPORTED, BUSY }

    /** Pairing as the settings page shows it. */
    val state: PairingFlow.State get() = flow.state

    /** A new pairing (replacing one in flight): checks what it needs, starts discovery, posts the notification. */
    fun begin(app: Context): Begin {
        val ctx = app.applicationContext
        if (!supported) return Begin.UNSUPPORTED
        if (forgetting) return Begin.BUSY
        if (!notificationsOn(ctx)) return Begin.NOTIFICATIONS_OFF
        if (shizukuRunning()) return Begin.SHIZUKU_RUNNING
        cancel(ctx)
        val session = flow.begin()
        expiry = Runnable {
            if (flow.expire(session)) { release(ctx); AppLog.log("[shell] pairing: expired") }
        }.also { main.postDelayed(it, PairingFlow.SESSION_MS) }
        discovery = AdbDiscovery(AndroidNsdAdapter(ctx.getSystemService(NsdManager::class.java))).also { it.start() }
        // The session's own token: its id in the data (never an endpoint or identity), a request code of its own.
        replyIntent = PendingIntent.getBroadcast(ctx, session.id.hashCode(),
            Intent(ACTION_REPLY).setClass(ctx, PairingReplyReceiver::class.java).setData(Uri.parse("launcher-pairing:" + session.id)),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        notify(ctx, "Open Pair device with pairing code in Wireless debugging, then reply here with its six digits.", reply = true)
        AppLog.log("[shell] pairing: waiting for the code")
        return Begin.STARTED
    }

    /** The receiver's reply: [id] from its data, [input] its RemoteInput text. Ignored unless it is the current session's. */
    fun reply(ctx: Context, id: String?, input: CharSequence?) {
        when (val o = flow.claim(id, input)) {
            PairingFlow.NotCurrent -> AppLog.log("[shell] pairing: a reply for no current session ignored")
            PairingFlow.Busy -> {}
            PairingFlow.BadCode -> notify(ctx, "That was not six digits. Reply with the code again.", reply = true)
            is PairingFlow.Claim -> {
                notify(ctx, "Pairing…", reply = false)
                find(ctx, AdbDiscovery.Kind.PAIRING, wanted = { flow.isCurrent(o.generation) }) { endpoint ->
                    if (endpoint == null) {
                        if (flow.finish(o, paired = false)) notify(ctx, "Open Pair device with pairing code first, then reply with its code.", reply = true)
                    } else if (supported) pair(ctx, o, endpoint)
                }
            }
        }
    }

    @androidx.annotation.RequiresApi(31)
    private fun pair(ctx: Context, o: PairingFlow.Claim, endpoint: AdbEndpoint) = io.execute {
        // Nothing starts for a claim already cancelled: no key is made, no connection opened (Forget's delete runs after
        // this task on this same worker).
        if (!flow.isCurrent(o.generation)) return@execute
        val outcome = try {
            val key = (AndroidAdbIdentity.store(ctx).load() as? AdbIdentityResult.Ready)?.key ?: error("identity")
            val client = flow.admit(o) { AdbPairingClient(endpoint, key) } ?: return@execute
            client.use { it.pair(o.code) }
            "paired"
        } catch (_: AdbPairingRejectedException) { "wrong" } catch (t: Throwable) { "failed: ${t.javaClass.simpleName}" }
        main.post {
            if (!flow.finish(o, paired = outcome == "paired")) return@post   // cancelled or replaced meanwhile
            AppLog.log("[shell] pairing: $outcome")
            when (outcome) {
                "paired" -> afterPairing(ctx)
                "wrong" -> notify(ctx, "Wrong code. Reply with the code Settings shows now.", reply = true)
                else -> notify(ctx, "Pairing did not work. Open Pair device with pairing code again and reply.", reply = true)
            }
        }
    }

    /**
     * The endpoint of [kind] on this phone, for [then] on the main thread (null: not found within [FIND_MS]). A pairing
     * can take longer than a discovery session lives (the user finding the page, typing the code), so an expired
     * session is started again here, bounded as ever; nothing starts once [wanted] says the work is obsolete.
     */
    private fun find(ctx: Context, kind: AdbDiscovery.Kind, wanted: () -> Boolean, then: (AdbEndpoint?) -> Unit) {
        val known = discovery?.snapshot()?.firstOrNull { it.kind == kind }?.endpoint
        if (known != null) return then(known)
        val d = AdbDiscovery(AndroidNsdAdapter(ctx.getSystemService(NsdManager::class.java)))
        discovery?.close()
        discovery = d
        try { d.start(FIND_MS) } catch (_: RuntimeException) { return then(null) }
        val until = android.os.SystemClock.elapsedRealtime() + FIND_MS
        fun look() {
            if (discovery !== d || !wanted()) return
            val found = d.snapshot().firstOrNull { it.kind == kind }?.endpoint
            when {
                found != null -> then(found)
                android.os.SystemClock.elapsedRealtime() >= until -> then(null)
                else -> main.postDelayed({ look() }, 250)
            }
        }
        look()
    }

    /** Paired: the connect port from discovery, then our host's start (its port cached only once it is ready). */
    private fun afterPairing(ctx: Context) {
        replyIntent?.cancel(); replyIntent = null
        expiry?.let { main.removeCallbacks(it) }; expiry = null
        OwnHost.markPaired(ctx)
        if (!OwnHost.active) {
            discovery?.close(); discovery = null
            notify(ctx, "Paired. The launcher's own shell starts from the launcher's next start.", reply = false)
            dismissLater(ctx)
            return
        }
        notify(ctx, "Paired. Starting the launcher's shell…", reply = false)
        find(ctx, AdbDiscovery.Kind.CONNECT, wanted = { flow.state == PairingFlow.State.PAIRED }) { endpoint ->
            discovery?.close(); discovery = null
            if (endpoint != null) { OwnHost.startPaired(ctx, endpoint.port); dismissLater(ctx) }
            else notify(ctx, "Paired, but Wireless debugging's port was not found. Keep it on and start from the settings.", reply = false)
        }
    }

    /** Ends the pairing in flight: its token, discovery and socket go; late outcomes change nothing. */
    fun cancel(ctx: Context) {
        flow.cancel()
        release(ctx)
    }

    private fun release(ctx: Context) {
        expiry?.let { main.removeCallbacks(it) }; expiry = null
        replyIntent?.cancel(); replyIntent = null
        discovery?.close(); discovery = null
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
    }

    /** Pairing needs Android 12's keying-material export ([AdbPairingClient]); below it Shizuku stays (D-075). */
    val supported: Boolean get() = android.os.Build.VERSION.SDK_INT >= 31

    /** "Forget pairing": the pairing in flight ends and the launcher's identity is deleted (Android keeps its entry). */
    fun forget(ctx: Context) {
        cancel(ctx)
        OwnHost.forgetEndpoint(ctx)   // its start in flight ends now, before the key goes
        forgetting = true
        val app = ctx.applicationContext
        // After whatever either worker is in the middle of, so neither can make the key again behind the delete.
        io.execute { OwnHost.onWorker { AndroidAdbIdentity.forget(app); main.post { forgetting = false } } }
        AppLog.log("[shell] pairing: forgotten (Android's paired-device entry stays until removed in Settings)")
    }

    private fun dismissLater(ctx: Context) = main.postDelayed({
        if (flow.state == PairingFlow.State.PAIRED) ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
    }, 4_000)

    private fun notify(ctx: Context, text: String, reply: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Pair the launcher")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(reply)
        val pi = replyIntent
        if (reply && pi != null) {
            val input = RemoteInput.Builder(KEY_CODE).setLabel("Pairing code").build()
            b.addAction(Notification.Action.Builder(null, "Enter code", pi).addRemoteInput(input).build())
        }
        nm.notify(NOTIFICATION, b.build())
    }

    /** Notifications reach the user: allowed for the app and the channel not turned off. Makes the channel if needed. */
    fun notificationsOn(ctx: Context): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Shell host", NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) })
        }
        return nm.areNotificationsEnabled() && nm.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun shizukuRunning() = try { rikka.shizuku.Shizuku.pingBinder() } catch (_: Throwable) { false }
}
