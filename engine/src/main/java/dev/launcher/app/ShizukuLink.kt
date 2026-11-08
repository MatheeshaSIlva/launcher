package dev.launcher.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import java.util.concurrent.Executors

/**
 * App-wide connection to our shell-uid service, through the selected [ShellHost] (Shizuku's user service, or our own
 * process from T-001 slice 2). The launcher works without it; [service] is null whenever the host is missing, denied or
 * not yet bound. Every caller goes through this object; the host only finds the service.
 */
object ShizukuLink {
    enum class State { NOT_RUNNING, NO_PERMISSION, CONNECTING, CONNECTED }

    @Volatile var service: IShellService? = null
        private set

    /** Lives exactly as long as this process; the shell service watches it to clear our status bar flags if we die. */
    val clientToken: IBinder = Binder()

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = mutableListOf<() -> Unit>()
    private lateinit var app: android.app.Application
    private lateinit var host: ShellHost
    /** The selected host's generation: a report from an earlier host (or an earlier selection) is ignored. */
    private var generation = 0

    private val sink = object : ShellHost.Sink {
        override fun connected(generation: Int, service: IShellService) {
            if (generation != this@ShizukuLink.generation) return
            this@ShizukuLink.service = service
            onConnected(service)
            changed()
        }

        override fun lost(generation: Int) {
            if (generation != this@ShizukuLink.generation) return
            service = null
            SystemRestore.onServiceLost()
            changed()
        }

        override fun changed() { this@ShizukuLink.changed() }
    }

    fun init(app: android.app.Application) {
        this.app = app
        host = if (OwnHost.selected(app)) OwnHost() else ShizukuHost()
        AppLog.log("[shell] host: ${host.javaClass.simpleName}")
        host.init(app, sink, ++generation)
        if (host !is OwnHost) OwnHost.settleLeftover(app)
        OwnHost.registerTestRoute(app)
    }

    /** What runs once a host has given us a service, the same for every host. */
    private fun onConnected(s: IShellService) {
        io.execute {
            grantSelf(s)
            Watchdog.sync(app, s)
            if (SystemRestore.gesturesWanted(app) && !EngineHost.current("nav.ready").nav.ready) {
                AppLog.log("[nav] accessibility service: ${NavAccessibilityService.enable(app)}")
            }
            // A fresh service holds no flags: put back what we want (stock bar/gestures show until this runs).
            // Older builds zeroed the system animation scales while gesture nav was on; give them back.
            if (SystemRestore.gesturesWanted(app)) {
                SystemRestore.restoreScalesIfChanged(app, s)
                SystemRestore.ensureScalesOn(app, s)
                SystemRestore.enableOwnStatusBarOnce(app)
            }
            SystemRestore.applyFlags(app, s)
        }
    }

    fun state(): State = if (service != null) State.CONNECTED else host.state()

    /** The launcher's own process is this start's host (the switch applies at a start). */
    fun ownHostSelected(): Boolean = host is OwnHost

    /** Asks for permission if needed, then binds. Safe to call repeatedly. */
    fun connect() { if (service == null) host.connect() }

    /**
     * Grants the permissions that keep the safety path working without Shizuku: WRITE_SECURE_SETTINGS (animation scales)
     * and POST_NOTIFICATIONS (the Restore notification). Also the overlay permission our gesture strip and cards need.
     * All stay granted until the app is uninstalled.
     */
    private fun grantSelf(s: IShellService) {
        val pkg = app.packageName
        if (!Settings.canDrawOverlays(app)) {
            try {
                AppLog.log("[shizuku] self-grant overlay: ${s.runShell("appops set $pkg SYSTEM_ALERT_WINDOW allow").trim()}")
            } catch (t: Throwable) {
                AppLog.log("[shizuku] overlay grant failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        try {
            // Wallpaper bitmap for the glass dock (all-files access); harmless to repeat.
            s.runShell("appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
        } catch (_: Throwable) { }
        try {
            // Notification access for the icon badges.
            val nm = app.getSystemService(android.app.NotificationManager::class.java)
            val comp = EngineHost.current("listenerComponent").listenerComponent()
            if (!nm.isNotificationListenerAccessGranted(comp))
                AppLog.log("[shizuku] badge access: ${s.runShell("cmd notification allow_listener ${comp.flattenToString()}").trim()}")
        } catch (t: Throwable) { AppLog.log("[shizuku] badge access failed: ${t.message}") }
        val missing = listOf(Manifest.permission.WRITE_SECURE_SETTINGS, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_PHONE_STATE)
            .filter { app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return
        try {
            val out = s.runShell(missing.joinToString("; ") { "pm grant $pkg $it" }).trim()
            AppLog.log("[shizuku] self-grant ${missing.joinToString { it.substringAfterLast('.') }}: $out")
            if (Manifest.permission.POST_NOTIFICATIONS in missing) main.post { SafetyNotification.show(app) }
        } catch (t: Throwable) {
            AppLog.log("[shizuku] self-grant failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Blocking: returns the service, waiting up to [timeoutMs] for it to bind (e.g. right after a process start). */
    fun awaitService(timeoutMs: Long): IShellService? {
        service?.let { return it }
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        main.post { host.bindQuietly() }
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            service?.let { return it }
            Thread.sleep(100)
        }
        return service
    }

    /** Main thread only. */
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = main.post { listeners.toList().forEach { it() } }
}
