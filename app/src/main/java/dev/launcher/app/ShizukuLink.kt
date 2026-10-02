package dev.launcher.app

import android.Manifest
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/**
 * App-wide connection to Shizuku and our shell-uid user service.
 * The launcher works without it; [service] is null whenever Shizuku is missing, denied or not yet bound.
 */
object ShizukuLink {
    private const val PERMISSION_REQUEST = 1001

    enum class State { NOT_RUNNING, NO_PERMISSION, CONNECTING, CONNECTED }

    @Volatile var service: IShellService? = null
        private set

    /** Lives exactly as long as this process; the shell service watches it to clear our status bar flags if we die. */
    val clientToken: IBinder = Binder()

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = mutableListOf<() -> Unit>()
    private lateinit var app: LauncherApp
    private var binding = false

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(app.packageName, ShellService::class.java.name))
            .processNameSuffix("shell")
            .daemon(false)
            .tag("launcher-shell")
            // A new build must replace a still-running service from the old build.
            .version(app.buildStamp().hashCode())
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder != null && binder.pingBinder()) IShellService.Stub.asInterface(binder) else null
            binding = false
            AppLog.log("[shizuku] service connected: ${service != null}")
            service?.let { s ->
                io.execute {
                    grantSelf(s)
                    Watchdog.sync(app, s)
                    if (SystemRestore.gesturesWanted(app) && !GestureNav.ready) {
                        AppLog.log("[nav] accessibility service: ${NavAccessibilityService.enable(app)}")
                    }
                    // A fresh service holds no flags: put back what we want (stock bar/gestures show until this runs).
                    // Older builds zeroed the system animation scales while gesture nav was on; give them back.
                    if (SystemRestore.gesturesWanted(app)) SystemRestore.restoreScalesIfChanged(app, s)
                    SystemRestore.applyFlags(app, s)
                }
            }
            changed()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            AppLog.log("[shizuku] service disconnected")
            SystemRestore.onServiceLost()
            // If only our service died (Shizuku still up), bring it back. If Shizuku died, binder-received rebinds later.
            main.postDelayed({ if (service == null && hasPermission()) bind() }, 500)
            changed()
        }
    }

    fun init(app: LauncherApp) {
        this.app = app
        Shizuku.addBinderReceivedListenerSticky {
            AppLog.log("[shizuku] binder received")
            if (hasPermission()) bind()
            changed()
        }
        Shizuku.addBinderDeadListener {
            service = null
            binding = false
            AppLog.log("[shizuku] binder dead (Shizuku stopped)")
            SystemRestore.onServiceLost()
            changed()
        }
        Shizuku.addRequestPermissionResultListener { _, result ->
            val granted = result == PackageManager.PERMISSION_GRANTED
            AppLog.log("[shizuku] permission ${if (granted) "granted" else "denied"}")
            if (granted) bind()
            changed()
        }
    }

    fun state(): State = when {
        service != null -> State.CONNECTED
        !running() -> State.NOT_RUNNING
        !hasPermission() -> State.NO_PERMISSION
        else -> State.CONNECTING
    }

    /** Asks for permission if needed, then binds. Safe to call repeatedly. */
    fun connect() {
        when {
            !running() -> AppLog.log("[shizuku] not running. Start Shizuku (wireless debugging), then tap Connect again.")
            !hasPermission() -> {
                AppLog.log("[shizuku] requesting permission; approve the dialog")
                Shizuku.requestPermission(PERMISSION_REQUEST)
            }
            service == null -> bind()
        }
    }

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
        val missing = listOf(Manifest.permission.WRITE_SECURE_SETTINGS, Manifest.permission.POST_NOTIFICATIONS)
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
        main.post { if (hasPermission()) bind() }
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            service?.let { return it }
            Thread.sleep(100)
        }
        return service
    }

    private fun bind() {
        if (service != null || binding) return
        binding = true
        try {
            Shizuku.bindUserService(args, conn)
        } catch (t: Throwable) {
            binding = false
            AppLog.log("[shizuku] bind failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        changed()
    }

    private fun running() = Shizuku.pingBinder() && !Shizuku.isPreV11()
    private fun hasPermission() = running() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    /** Main thread only. */
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = main.post { listeners.toList().forEach { it() } }
}
