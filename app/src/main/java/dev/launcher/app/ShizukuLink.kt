package dev.launcher.app

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku

/**
 * App-wide connection to Shizuku and our shell-uid user service.
 * The launcher works without it; [service] is null whenever Shizuku is missing, denied or not yet bound.
 */
object ShizukuLink {
    private const val PERMISSION_REQUEST = 1001

    enum class State { NOT_RUNNING, NO_PERMISSION, CONNECTING, CONNECTED }

    @Volatile var service: IShellService? = null
        private set

    private val main = Handler(Looper.getMainLooper())
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
            changed()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            AppLog.log("[shizuku] service disconnected")
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
