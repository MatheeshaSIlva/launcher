package dev.launcher.app

import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import dev.launcher.engine.shell.BootStartFlow
import dev.launcher.engine.shell.BootStartFlow.Kind

/**
 * Carries out [BootStartFlow]'s commands on Android (T-001 slice 4): the settings, the note, what the phone's state is, and
 * the host's attempts. All on the main thread; it decides nothing itself. The note (`wifi_note`: we turned Wireless debugging
 * on) is written with `commit()` before the setting, and read at every start, so a crash cannot leave a debugging port open
 * unnoticed (D-088). USB debugging is never written (D-089).
 */
internal class OwnHostStarter(private val app: Context, private val main: Handler, private val prefs: android.content.SharedPreferences, private val paired: () -> Boolean, private val ops: Ops) {
    interface Ops {
        /** A start on the cached port, or the one last discovered; [done] once: true when the service is up. */
        fun attempt(done: (Boolean) -> Unit)
        /** Finds the connect port; [done] once: true when it did. */
        fun discover(done: (Boolean) -> Unit)
        fun identityAnswers(done: (Boolean) -> Unit)
        /** Closes the attempt or discovery in flight. */
        fun cancelWork()
        fun changed()
    }

    private val flow = BootStartFlow { SystemClock.elapsedRealtime() }
    private val cr = app.contentResolver
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private var ticking = false

    val state: BootStartFlow.State get() = flow.state

    init {
        val watch = object : ContentObserver(main) { override fun onChange(self: Boolean) = observed() }
        cr.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, watch)
        cr.registerContentObserver(Settings.Global.getUriFor(ADB_WIFI), false, watch)
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { main.post { observed() } }
                    override fun onLost(network: Network) { main.post { observed() } }
                })
        } catch (t: Throwable) { AppLog.log("[shell] own host: no Wi-Fi callback: ${t.javaClass.simpleName}") }
    }

    fun trigger(userAction: Boolean = false) = run(flow.trigger(observation(), userAction))

    /** What the phone's state does for a host with something to do: not paired and no note, an observation starts nothing. */
    private fun observed() {
        if (paired() || prefs.getBoolean(NOTE, false)) run(flow.observe(observation()))
    }
    fun helperDied() = run(flow.helperDied(observation()))
    fun cancel() = run(flow.cancel())

    private fun observation() = BootStartFlow.Observation(
        usbOn = global(Settings.Global.ADB_ENABLED) == 1,
        wifiConnected = wifi(),
        wirelessOn = global(ADB_WIFI) == 1,
        bootCount = global(Settings.Global.BOOT_COUNT),
        note = prefs.getBoolean(NOTE, false),
        suppressedBoot = prefs.getInt(SUPPRESSED_BOOT, -1).takeIf { it >= 0 },
        offBoot = prefs.getInt(OFF_BOOT, -1).takeIf { it >= 0 },
        offAtMillis = prefs.getLong(OFF_AT, -1).takeIf { it >= 0 },
    )

    private fun global(name: String) = try { Settings.Global.getInt(cr, name, 0) } catch (_: Throwable) { 0 }

    @Suppress("DEPRECATION")
    private fun wifi() = try {
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    } catch (_: Throwable) { false }

    private fun run(commands: List<BootStartFlow.Command>) {
        var state = flow.state
        for (c in commands) execute(c)
        ops.changed()
        if (state != flow.state) { state = flow.state; AppLog.log("[shell] own host: $state") }
        if (!ticking && flow.state in ACTIVE) { ticking = true; main.postDelayed(::tick, 1000) }
    }

    private fun tick() {
        ticking = false
        run(flow.tick(observation()))
    }

    private fun execute(c: BootStartFlow.Command) {
        fun done(ok: Boolean) { if (flow.isCurrent(c)) run(flow.complete(c, ok, observation())) }
        when (c.kind) {
            Kind.RECORD_NOTE -> done(prefs.edit().putBoolean(NOTE, true).commit() && prefs.getBoolean(NOTE, false))
            Kind.ENABLE -> {
                AppLog.log("[shell] own host: turning Wireless debugging on")
                write(1)
                // Android takes it back on a network it has not been told to trust: read it after that has had its time.
                main.postDelayed({ done(global(ADB_WIFI) == 1) }, ENABLE_SETTLE_MS)
            }
            Kind.RESTORE -> {
                AppLog.log("[shell] own host: turning Wireless debugging off again")
                write(0)
                main.postDelayed({ done(global(ADB_WIFI) == 0) }, RESTORE_SETTLE_MS)
            }
            Kind.CLEAR_NOTE -> done(prefs.edit().remove(NOTE).putLong(OFF_AT, c.offAtMillis ?: SystemClock.elapsedRealtime()).putInt(OFF_BOOT, c.bootCount).commit())
            Kind.SUPPRESS -> {
                AppLog.log("[shell] own host: the helper died soon after Wireless debugging went off: not starting it again by itself")
                done(prefs.edit().putInt(SUPPRESSED_BOOT, c.bootCount).commit())
            }
            Kind.RESET_GUARD -> done(prefs.edit().remove(SUPPRESSED_BOOT).remove(OFF_AT).remove(OFF_BOOT).commit())
            Kind.CACHED, Kind.FRESH -> ops.attempt(::done)
            Kind.DISCOVER -> ops.discover(::done)
            Kind.IDENTITY -> ops.identityAnswers(::done)
            Kind.CANCEL_WORK -> ops.cancelWork()
        }
    }

    private fun write(value: Int) {
        try { Settings.Global.putInt(cr, ADB_WIFI, value) }
        catch (t: Throwable) { AppLog.log("[shell] own host: cannot write Wireless debugging's setting: ${t.javaClass.simpleName}") }
    }

    companion object {
        const val ADB_WIFI = "adb_wifi_enabled"
        const val NOTE = "wifi_note"
        private const val SUPPRESSED_BOOT = "suppressed_boot"
        private const val OFF_AT = "off_at"
        private const val OFF_BOOT = "off_boot"
        private const val ENABLE_SETTLE_MS = 1_500L
        private const val RESTORE_SETTLE_MS = 500L
        private val ACTIVE = setOf(BootStartFlow.State.STARTING, BootStartFlow.State.WAITING_WIFI, BootStartFlow.State.RESTORING)
    }
}
